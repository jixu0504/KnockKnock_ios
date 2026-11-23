@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.domain.chat.ChatStageTimeoutException
import com.rhodes.privatechat.shared.domain.chat.FakeAiEngine
import com.rhodes.privatechat.shared.domain.chat.ChatDisplayEventView
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.GroupMsgResult
import com.rhodes.privatechat.shared.model.GroupTurnState
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 群聊引擎恢复/取消/降级矩阵（验收 #7）：发送成功闭环、取消→未送达、双发合并 supersede、
 * claim 拒绝、名册未解析 release(5s)、全员禁言 complete、kill 进程恢复（type 重置/租约透传）、
 * 上下文超限 6 次降级、内容重试一次、阶段超时、自动轮次、图片/礼物、分段撤回、启动 reconcile。
 */
class GroupChatEngineRecoveryTest {

    private val g = GroupEngineHarness.GROUP_ID

    private fun turn(id: String, status: String = ReplyTurnStatus.PENDING) = ReplyTurn(
        id = id, sessionId = g, surface = "group", triggerKind = "manual", sourceMessageId = 1L,
        autoPlanToken = "", mode = "online", status = status, attemptCount = 0, nextAttemptAt = 0L,
        leaseToken = "", leaseUntil = 0L, responseMessageId = null, lastError = "",
        createdAt = 1_000L, updatedAt = 1_000L, completedAt = 0L,
    )

    // === 1. 发送成功闭环 ===

    @Test
    fun manualTurnStoresReplyAndSettlesWithoutAffection() = runBlocking {
        val h = GroupEngineHarness(aiClient = FakeAiEngine(listOf(GroupEngineHarness.taggedReply())))
        h.seedGroup()
        val (job, done) = h.send()
        h.awaitJob(job)
        assertTrue(done.await())
        val messages = h.messages()
        val user = messages.first { it.isMe }
        val ai = messages.first { !it.isMe && it.type == "ai_json" }
        assertEquals("text", user.type)
        assertEquals("我在听。", GroupReplyJson.decodeResults(ai.content).first().message)
        val storedTurn = h.store.getTurn("group:$g:${user.id}")
        assertEquals(ReplyTurnStatus.SUCCEEDED, storedTurn?.status)
        // 群回合状态 + 主线摘要落库
        assertEquals("测试主线", h.settings.getGroupTurnState(g)?.currentTopic)
        assertEquals("测试主线", h.settings.getGroupPlotSummary(g))
        assertEquals("amiya=担忧;blaze=不耐烦", h.settings.getGroupTurnState(g)?.memberEmotions)
        // 结算：群聊无亲密度/催眠；仅手动轮次每日龙门币 50。
        val settled = h.settled().single()
        assertEquals(50, settled.dailyLmb)
        assertEquals(2, settled.visibleCount)
        assertTrue(h.replies().isNotEmpty())
        assertTrue(h.maintenance().any { it.reason == "post_reply" })
        assertTrue(h.events.any { it is GroupEngineEvent.UnreadHint })
        val session = h.store.getSession(g)
        assertTrue(session?.lastMessage?.startsWith("煌：") == true)
    }

    // === 2. 取消 → 未送达 + 租约释放 ===

    @Test
    fun cancelDuringGenerationMarksUndeliveredAndReleasesTurn() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply())).apply { suspendBeforeCompleteMillis = 2_000L }
        val h = GroupEngineHarness(aiClient = ai)
        h.seedGroup()
        val (job, _) = h.send()
        // 等模型请求真正开始（保证取消发生在生成中）。
        withTimeoutOrNull(5_000L) { while (ai.requests.isEmpty()) delay(10) }
        h.engine.cancelGroupRequests(g)
        h.awaitJob(job)
        val user = h.messages().first { it.isMe }
        assertEquals("send_failed", user.type)
        val storedTurn = h.store.getTurn("group:$g:${user.id}")
        assertEquals(ReplyTurnStatus.PENDING, storedTurn?.status, "取消后租约释放回 pending 由恢复补答")
        assertTrue(h.undelivered().isNotEmpty())
    }

    // === 3. 双发：第二发被合并批次 supersede → merged_into_batch 终态 ===

    @Test
    fun secondSendIsMergedIntoSingleBatchAndCancelled() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply()))
        val h = GroupEngineHarness(aiClient = ai)
        h.seedGroup()
        val (job1, done1) = h.send(text = "在吗")
        val (job2, done2) = h.send(text = "我是说今天")
        h.awaitJob(job1)
        h.awaitJob(job2)
        val outcomes = listOf(done1.await(), done2.await())
        assertEquals(1, outcomes.count { it }, "恰好一轮成功（另一轮被合并批次 supersede）")
        val messages = h.messages()
        val userIds = messages.filter { it.isMe }.map { it.id }
        assertEquals(2, userIds.size)
        // 恰一次模型调用；第二发 turn 走 cancelled 终态且不可再被 claim。
        assertEquals(1, ai.requests.size)
        val cancelledUserIds = userIds.filter { id ->
            h.store.getTurn("group:$g:$id")?.status.let { it == "cancelled" || it == ReplyTurnStatus.PENDING || it == null }
        }
        assertTrue(cancelledUserIds.isNotEmpty(), "被合并轮次的 turn 必须走 cancelled 终态")
        cancelledUserIds.forEach { id ->
            val turnId = "group:$g:$id"
            assertEquals("cancelled", h.store.getTurn(turnId)?.status)
            assertNull(h.store.claimTurn(turnId, "x", h.clock.now, h.clock.now + 1_000L))
        }
        // 合并提示进入单次请求的用户内容（最后一条 user 是输出检查清单，故按内容定位）。
        val mergedContent = ai.requests.single().messages.map { it.content }
            .first { it.contains("【用户本轮消息】") }
        assertTrue(mergedContent.contains("用户连续补充了以下消息"), mergedContent)
    }

    // === 4. claim 被拒 → ClaimDenied 事件，不调模型 ===

    @Test
    fun claimDeniedKeepsMessageForRecovery() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply()))
        val h = GroupEngineHarness(aiClient = ai)
        h.seedGroup()
        val foreignTurn = turn("group:$g:1")
        h.store.seedTurn(foreignTurn)
        h.store.claimTurn("group:$g:1", "other-worker", h.clock.now, h.clock.now + 60_000L)
        val (job, done) = h.send()
        h.awaitJob(job)
        assertFalse(done.await())
        assertEquals(0, ai.requests.size)
        assertTrue(h.events.any { it is GroupEngineEvent.ClaimDenied })
        assertTrue(h.toastMessages().any { it.contains("恢复发送") })
        // 用户消息已落库，turn 仍是他人 running 租约（等待恢复）。
        assertEquals(ReplyTurnStatus.RUNNING, h.store.getTurn("group:$g:1")?.status)
    }

    // === 5. 名册未解析 → release(+5s)，不燃尽 turn ===

    @Test
    fun unresolvedRosterReleasesWithRetryInFiveSeconds() = runBlocking {
        val h = GroupEngineHarness(aiClient = FakeAiEngine(listOf(GroupEngineHarness.taggedReply())))
        h.seedGroup(memberIds = listOf("ghost"), members = emptyList())
        h.extras.operators = emptyList()
        val (job, done) = h.send()
        h.awaitJob(job)
        assertFalse(done.await())
        val user = h.messages().first { it.isMe }
        val storedTurn = h.store.getTurn("group:$g:${user.id}")
        assertEquals(ReplyTurnStatus.PENDING, storedTurn?.status)
        assertEquals("group_members_unresolved", storedTurn?.lastError)
        assertTrue((storedTurn?.nextAttemptAt ?: 0L) >= h.clock.now + 4_000L)
        assertTrue(h.toastMessages().any { it.contains("群成员资料尚未同步") })
    }

    // === 6. 全员禁言 → 系统消息 + complete turn ===

    @Test
    fun allMutedStoresSystemMessageAndCompletesTurn() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply()))
        val h = GroupEngineHarness(aiClient = ai)
        h.seedGroup(mutedMembers = "amiya,blaze")
        val (job, done) = h.send()
        h.awaitJob(job)
        assertFalse(done.await())
        val messages = h.messages()
        val user = messages.first { it.isMe }
        assertTrue(messages.any { it.type == "system" && it.content.contains("所有成员已被禁言") })
        assertEquals(ReplyTurnStatus.SUCCEEDED, h.store.getTurn("group:$g:${user.id}")?.status)
        assertEquals(0, ai.requests.size)
        assertTrue(h.toastMessages().any { it.contains("已被禁言") })
    }

    // === 7. 杀进程恢复：type 重置 + 租约透传 ===

    @Test
    fun resumePersistedReplyResetsTypeAndKeepsLeaseToken() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply()))
        val h = GroupEngineHarness(aiClient = ai)
        h.seedGroup()
        val giftPayload = GroupReplyParser.buildGiftPayload("送花", "file://g.png", "花束", listOf("阿米娅"))
        val user = h.seedUserMessage(id = 1L, type = "gift_reply_failed", content = giftPayload)
        val pendingTurn = ReplyTurn(
            id = "group:$g:1", sessionId = g, surface = "group", triggerKind = "gift", sourceMessageId = 1L,
            autoPlanToken = "", mode = "online", status = ReplyTurnStatus.PENDING, attemptCount = 0,
            nextAttemptAt = 0L, leaseToken = "", leaseUntil = 0L, responseMessageId = null, lastError = "",
            createdAt = 1_000L, updatedAt = 1_000L, completedAt = 0L,
        )
        h.store.seedTurn(pendingTurn)
        h.store.claimTurn("group:$g:1", "worker-lease", h.clock.now, h.clock.now + 60_000L)
        val resumed = h.engine.resumePersistedReply(g, user.id, replyTurnId = "group:$g:1", replyLeaseToken = "worker-lease")
        assertTrue(resumed)
        assertEquals("gift_hidden", h.store.getMessageById(1L)?.type)
        assertEquals(ReplyTurnStatus.SUCCEEDED, h.store.getTurn("group:$g:1")?.status)
    }

    // === 8. 启动 reconcile + 前台补算 ===

    @Test
    fun startupReconcileListsRetryableTurnsAndRebuildsAutoPlans() = runBlocking {
        val h = GroupEngineHarness()
        h.seedGroup()
        h.seedUserMessage(id = 1L)
        h.store.seedTurn(turn("group:$g:1"))
        h.settings.autoAiEnabled = true
        h.settings.putGroupAuto(g, true)
        val ids = h.engine.onAppStartReconcile()
        assertTrue(ids.contains("group:$g:1"), "可重试 turn 应被列出：$ids")
        val plan = h.settings.getGroupAutoChatPlan(g)
        assertTrue(plan.token.isNotBlank(), "自动计划应被重建")
        assertEquals("pending", plan.state)
    }

    // === 9. 上下文超限降级（≤6 次，阶梯减半） ===

    @Test
    fun contextLimitErrorDegradesHistoryAndRetries() = runBlocking {
        var attempts = 0
        val client = RecordingAiClient { _, _ ->
            attempts++
            if (attempts <= 2) throw IllegalStateException("maximum context length exceeded") else GroupEngineHarness.taggedReply()
        }
        val h = GroupEngineHarness(aiClient = client)
        h.seedGroup()
        // 预置多轮历史，保证 remainingHistoryCount > 0。
        repeat(12) { index ->
            h.seedAiReply(id = 100L + index, timestamp = 1_000L + index)
        }
        val (job, done) = h.send()
        h.awaitJob(job)
        assertTrue(done.await())
        assertEquals(3, client.requests.size)
        val firstHistoryCount = client.requests[0].second.count { it.role != "system" }
        val secondHistoryCount = client.requests[1].second.count { it.role != "system" }
        assertTrue(secondHistoryCount < firstHistoryCount, "上下文超限后历史条数应减少")
        assertTrue(h.replies().isNotEmpty())
    }

    // === 10. 内容重试一次（无条件） ===

    @Test
    fun emptyVisibleReplyRetriesOnceWithRegenerateRequirement() = runBlocking {
        val narrationOnly = "【群聊回合状态】\n【当前主线】测试\n【旁白】\n没有人开口。"
        val ai = FakeAiEngine(listOf(narrationOnly, GroupEngineHarness.taggedReply()))
        val h = GroupEngineHarness(aiClient = ai)
        h.seedGroup()
        val (job, done) = h.send()
        h.awaitJob(job)
        assertTrue(done.await())
        assertEquals(2, ai.requests.size)
        val retrySystem = ai.requests[1].messages.first { it.role == "system" }.content
        assertTrue(retrySystem.contains("【重新生成要求】"), retrySystem)
        assertTrue(h.replies().isNotEmpty())
    }

    @Test
    fun twoEmptyRepliesMarkUndelivered() = runBlocking {
        val narrationOnly = "【群聊回合状态】\n【当前主线】测试\n【旁白】\n没有人开口。"
        val ai = FakeAiEngine(listOf(narrationOnly, narrationOnly))
        val h = GroupEngineHarness(aiClient = ai)
        h.seedGroup()
        val (job, done) = h.send()
        h.awaitJob(job)
        assertFalse(done.await())
        assertEquals("send_failed", h.messages().first { it.isMe }.type)
        assertEquals(2, ai.requests.size)
        assertTrue(h.undelivered().isNotEmpty())
    }

    // === 11. 阶段超时 → 未送达 + Toast ===

    @Test
    fun modelStageTimeoutMarksUndelivered() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply())).apply { suspendBeforeCompleteMillis = 1_000L }
        val h = GroupEngineHarness(
            aiClient = ai,
            config = GroupChatEngineConfig(
                turnBudgetMs = 600L, modelBudgetMs = 400L, promptBuildBudgetMs = 5_000L, messageWriteBudgetMs = 5_000L,
            ),
        )
        h.seedGroup()
        val (job, done) = h.send()
        h.awaitJob(job)
        assertFalse(done.await())
        assertEquals("send_failed", h.messages().first { it.isMe }.type)
        assertTrue(h.toastMessages().any { it.contains("超时") }, h.toastMessages().toString())
    }

    // === 12. 自动轮次：无用户消息、无龙门币结算、计划推进 ===

    @Test
    fun scheduledAutoTurnStoresReplyWithoutUserBubbleOrDailyLmb() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply(topic = "自动延续")))
        val h = GroupEngineHarness(aiClient = ai)
        h.seedGroup()
        h.settings.autoAiEnabled = true
        h.settings.putGroupAuto(g, true)
        h.settings.putGroupAutoChatComplete(g, false)
        h.settings.putGroupAutoChatPlan(
            g,
            SettingsRepository.GroupAutoChatPlan(token = "tok", dueAt = 0L, round = 1, state = "pending", revision = 1L),
        )
        val succeeded = h.engine.runScheduledAutoTurn(g, "tok", null, 1L)
        assertTrue(succeeded)
        val messages = h.messages()
        assertEquals(0, messages.count { it.isMe }, "自动轮次不写用户消息")
        assertEquals(1, messages.count { it.type == "ai_json" })
        val autoTurn = h.store.getTurn("auto:$g:tok")
        assertEquals(ReplyTurnStatus.SUCCEEDED, autoTurn?.status)
        assertEquals("group_auto", autoTurn?.triggerKind)
        assertEquals("自动延续", h.settings.getGroupTurnState(g)?.currentTopic)
        // 自动轮次不结算：Settled.dailyLmb == null。
        assertNull(h.settled().single().dailyLmb)
        assertEquals(2, h.settings.getGroupAutoChatPlan(g).round)
    }

    @Test
    fun scheduledAutoTurnRejectsInactivePlan() = runBlocking {
        val h = GroupEngineHarness(aiClient = FakeAiEngine(listOf(GroupEngineHarness.taggedReply())))
        h.seedGroup()
        h.settings.autoAiEnabled = true
        h.settings.putGroupAuto(g, true)
        h.settings.putGroupAutoChatPlan(
            g,
            SettingsRepository.GroupAutoChatPlan(token = "tok", dueAt = 0L, round = 1, state = "pending", revision = 1L),
        )
        assertFalse(h.engine.runScheduledAutoTurn(g, "wrong-token", null, 1L))
        assertEquals(0, h.messages().size)
    }

    // === 13. 图片 / 礼物管线 ===

    @Test
    fun groupImagePipelineFillsVisionSummaryAndReplies() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply(amiyaLine = "好可爱的猫。")))
        val h = GroupEngineHarness(aiClient = ai)
        h.seedGroup()
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val job = h.engine.sendGroupImageMessage(
            groupId = g, groupName = "测试群", imageUri = "file://cat.png",
            imageForModel = "data:image/png;base64,xxx", caption = "看这个", mode = "online",
            onReplyComplete = { done.complete(it) },
        )
        h.awaitJob(job)
        assertTrue(done.await())
        h.awaitUntil { h.messages().any { it.type == "ai_json" } }
        val image = h.messages().first { it.type == "image" }
        val imageJson = assertNotNull(GroupImageJsonContract.parse(image.content))
        assertTrue(imageJson.visionSummary.contains("visibleSummary"))
        assertEquals(ReplyTurnStatus.SUCCEEDED, h.store.getTurn("group:$g:${image.id}")?.status)
        assertTrue(h.messages().count { it.type == "ai_json" } == 1)
        // 识图 prompt 让所有在场成员回应图片：图片管线把「用户发送的图片分析」交给群轮次。
        val allPromptText = ai.requests.single().messages.joinToString("\n") { it.content }
        assertTrue(allPromptText.contains("【用户本轮消息】"), "图片轮次必须有用户本轮消息")
        assertTrue(allPromptText.contains("用户发送了一张图片") || allPromptText.contains("图片"), "识图 prompt 应进入模型输入")
    }

    @Test
    fun groupGiftPipelineStoresHiddenMessageAndCompletesTurn() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply(amiyaLine = "谢谢你。")) )
        val h = GroupEngineHarness(aiClient = ai)
        h.seedGroup()
        val job = h.engine.sendHiddenGiftMessage(g, "测试群", "送你们的花", "online", "file://gift.png", "花束", listOf("阿米娅", "煌"))
        h.awaitJob(job)
        h.awaitUntil { h.messages().any { it.type == "ai_json" } }
        val gift = h.messages().first { it.type == "gift_hidden" }
        val storedTurn = h.store.getTurn("group:$g:${gift.id}")
        assertEquals("gift", storedTurn?.triggerKind)
        assertEquals(ReplyTurnStatus.SUCCEEDED, storedTurn?.status)
        assertEquals(1, h.messages().count { it.type == "ai_json" })
    }

    // === 14. 分段撤回：口径 + 情绪快照重算 + 维护意图 ===

    @Test
    fun recallSegmentRecomputesEmotionSnapshotAndRequestsContextRebuild() = runBlocking {
        val h = GroupEngineHarness()
        h.seedGroup()
        val reply = h.seedAiReply(
            id = 100L,
            results = listOf(
                GroupMsgResult("阿米娅", "第一段", "dialogue", "担忧", "amiya"),
                GroupMsgResult("阿米娅", "第二段", "dialogue", "放松", "amiya"),
            ),
        )
        h.settings.putGroupTurnState(
            g,
            GroupTurnState(currentTopic = "主线", memberEmotions = "amiya=担忧", emotionFallbackKeys = "", updatedAt = h.clock.now - 1_000L),
        )
        h.store.seedDisplayEvent(ChatDisplayEventView(reply.id, 0, 0L))
        h.engine.recallMessageSegment(g, reply.id, 0)?.join()
        val updated = h.store.getMessageById(reply.id)?.content.orEmpty()
        assertTrue(GroupReplyParser.isGroupSegmentRecalled(updated, 0))
        assertFalse(GroupReplyParser.isGroupSegmentRecalled(updated, 1))
        assertEquals("amiya=放松", h.settings.getGroupTurnState(g)?.memberEmotions)
        assertTrue(h.settings.getBoolean("context_rebuild_pending_$g"))
        assertTrue(h.maintenance().any { it.reason == "context_rebuild" })
    }

    @Test
    fun recallWholeMessageWhenSegmentIndexInvalid() = runBlocking {
        val h = GroupEngineHarness()
        h.seedGroup()
        val reply = h.seedAiReply(id = 100L)
        h.engine.recallMessageSegment(g, reply.id, -1)?.join()
        assertNull(h.store.getMessageById(reply.id))
    }

    // === 15. 阶段预算常数与配置压缩 ===

    @Test
    fun budgetConstantsMatchApprovedSpec() {
        assertEquals(210_000L, GroupStageBudget.TURN_BUDGET_MS)
        assertEquals(60_000L, GroupStageBudget.THINKING_BUDGET_BONUS_MS)
        assertEquals(60_000L, GroupStageBudget.PROMPT_BUILD_BUDGET_MS)
        assertEquals(150_000L, GroupStageBudget.MODEL_BUDGET_MS)
        assertEquals(15_000L, GroupStageBudget.MESSAGE_WRITE_TIMEOUT_MS)
        assertEquals(6, GroupStageBudget.MAX_CONTEXT_RETRIES)
        assertEquals(2, GroupStageBudget.MAX_MERGED_USER_MESSAGES)
        assertEquals(600, GroupStageBudget.MAX_MERGED_USER_CHARS)
        val config = GroupChatEngineConfig()
        assertEquals(210_000L, config.effectiveTurnBudgetMs)
        config.deepseekThinkingActive = true
        assertEquals(270_000L, config.effectiveTurnBudgetMs)
        assertEquals(210_000L, config.effectiveModelBudgetMs)
    }
}
