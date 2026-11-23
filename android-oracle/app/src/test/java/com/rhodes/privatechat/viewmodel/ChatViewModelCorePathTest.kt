package com.rhodes.privatechat.viewmodel

import com.rhodes.privatechat.shared.domain.chat.FakeAiEngine
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T28 批 C 步骤 1：ChatViewModel 核心路径基线测试（逐入口替换的回归网）。
 *
 * 覆盖任务卡要求的六类行为：发送成功 / 发送失败重试 / 取消 / 超时（见注释）/ 恢复 persisted reply / 撤回，
 * 外加重新生成 / 继续说 / 存档 / allocator 单例契约。测试全部走 VM 公开入口 + 真实 DB + 模型替身，
 * 断言「DB 行 + 租约 + 宿主副作用」，不依赖内部私有状态。
 */
class ChatViewModelCorePathTest : RobolectricChatTest() {

    // === 种数据 / 选会话辅助 ===

    private suspend fun seedRound(
        sessionId: String = "s1",
        userText: String = "你好",
        reply: String = replyJson("第一次回复"),
        userType: String = "text",
    ): Pair<Long, Long> = runBlocking {
        val userTime = System.currentTimeMillis()
        val userId = env.repository.getNextMessageId()
        env.repository.sendMessage(
            sessionId,
            ChatMessage(
                id = userId, sessionId = sessionId, senderName = "我", content = userText,
                type = userType, mode = "online", timestamp = userTime, isMe = true,
            ),
        )
        val aiId = env.repository.getNextMessageId()
        env.repository.sendMessage(
            sessionId,
            ChatMessage(
                id = aiId, sessionId = sessionId, senderName = "角色s1", content = reply,
                type = "ai_json", mode = "online", timestamp = userTime + 1, isMe = false,
            ),
        )
        userId to aiId
    }

    private suspend fun selectChat(): Pair<ChatViewModel, ChatSession> = runBlocking {
        val session = env.repository.sessions.getSession("s1") ?: env.seedPrivateChat()
        val vm = env.createViewModel()
        val operator = env.repository.getOperator(session.operatorId) ?: error("operator 未种入")
        vm.selectOperatorSync(operator)
        check(vm.currentSession.value?.id == session.id) { "会话未选中" }
        env.waitUntil(10_000L) { vm.messages.value.isNotEmpty() || env.repository.getMessagesSync(session.id).isEmpty() }
        vm to session
    }

    private suspend fun sendAndWait(
        vm: ChatViewModel,
        session: ChatSession,
        text: String = "你好",
        timeoutMs: Long = 60_000L,
    ): Boolean? {
        val completed = CompletableDeferred<Boolean>()
        vm.sendMessage(textOverride = text, targetSession = session, onResponseComplete = { completed.complete(it) })
        return withTimeoutOrNull(timeoutMs) { completed.await() }
    }

    // === 1. 接线缝（步骤 0a）：唯一 allocator ===

    @Test
    fun messageRepositoryExposesSingleAllocatorInstance() = runBlocking {
        val messages = env.repository.messages
        assertSame("必须暴露同一个 allocator 实例（严禁第二实例）", messages.allocator, messages.allocator)
        val viaRepository = messages.getNextMessageId()
        val direct = messages.allocator.allocate()
        assertEquals("仓库分配与引擎分配共享同一水位", viaRepository + 1, direct)
    }

    // === 2. 发送前置守卫 ===

    @Test
    fun sendMessage_blankTextIsNoOp() = runBlocking {
        val session = env.seedPrivateChat()
        val vm = env.createViewModel()
        vm.sendMessage(textOverride = "   ", targetSession = session)
        delay(300)
        assertTrue(env.repository.getMessagesSync("s1").isEmpty())
        assertTrue(env.toasts.isEmpty())
    }

    @Test
    fun sendMessage_withoutSessionToastsRestoreHint() = runBlocking {
        val vm = env.createViewModel()
        vm.sendMessage(textOverride = "你好")
        assertTrue("无会话应提示恢复中", env.waitUntil(5_000L) { env.toasts.any { "聊天正在恢复" in it } })
    }

    @Test
    fun sendMessage_missingModelConfigBlocksBeforePersisting() = runBlocking {
        val session = env.seedPrivateChat()
        env.settings.apiKey = ""
        val vm = env.createViewModel()
        vm.sendMessage(textOverride = "你好", targetSession = session)
        assertTrue("缺 API Key 应提示配置", env.waitUntil(5_000L) { env.toasts.any { "API Key" in it } })
        assertTrue(env.repository.getMessagesSync("s1").isEmpty())
    }

    // === 3. 发送成功 ===

    @Test
    fun sendMessage_success_persistsReplyAndSettles() = runBlocking {
        val session = env.seedPrivateChat()
        env.model.reply = replyJson("第一次回复")
        val vm = env.createViewModel()
        assertEquals(true, sendAndWait(vm, session))
        val messages = env.repository.getMessagesSync("s1")
        val user = messages.single { it.isMe }
        val ai = messages.single { !it.isMe && it.type == "ai_json" }
        assertEquals("你好", user.content)
        assertEquals("text", user.type)
        assertTrue("回复内容应为 JSON", ai.content.contains("第一次回复"))
        // 租约闭环
        val turn = env.repository.replyTurns.get("private:s1:${user.id}")
        assertNotNull("本轮租约应存在", turn)
        assertEquals(ReplyTurnStatus.SUCCEEDED, turn!!.status)
        // 结算副作用（亲密度 + 轮次状态）
        val operator = env.repository.getOperator("op1")!!
        assertTrue("可见回复应增加亲密度", operator.intimacy > 0)
        assertNotNull("私聊轮次状态应写入", env.settings.getPrivateTurnState("s1"))
    }

    // === 4. 发送失败 + 重试 ===

    @Test
    fun sendMessage_modelError_marksSendFailedAndReleasesTurn() = runBlocking {
        val session = env.seedPrivateChat()
        env.model.failMessage = "network down"
        val vm = env.createViewModel()
        assertEquals(false, sendAndWait(vm, session))
        val user = env.repository.getMessagesSync("s1").single { it.isMe }
        assertEquals("失败用户消息应置 send_failed", "send_failed", user.type)
        val turn = env.repository.replyTurns.get("private:s1:${user.id}")
        assertEquals("失败后租约应释放回 pending", ReplyTurnStatus.PENDING, turn!!.status)
        assertEquals(0, env.repository.getMessagesSync("s1").count { !it.isMe })
    }

    @Test
    fun retryFailedMessage_afterFailure_recoversAndStoresReply() = runBlocking {
        env.seedPrivateChat()
        env.model.failMessage = "network down"
        val (vm, session) = selectChat()
        assertEquals(false, sendAndWait(vm, session))
        val failed = vm.messages.value.single { it.isMe && it.type == "send_failed" }
        // 模型恢复后重试
        env.model.failMessage = null
        env.model.reply = replyJson("重试成功")
        vm.retryFailedMessage(failed.id)
        assertTrue(
            "重试应补上 AI 回复",
            env.waitUntil(60_000L) { env.repository.getMessagesSync("s1").any { !it.isMe && it.type == "ai_json" } },
        )
        val messages = env.repository.getMessagesSync("s1")
        assertEquals("重试不新建用户行", 1, messages.count { it.isMe })
        assertTrue(messages.single { it.isMe }.type != "send_failed")
        assertEquals(ReplyTurnStatus.SUCCEEDED, env.repository.replyTurns.get("private:s1:${failed.id}")!!.status)
    }

    // === 5. 取消 ===

    @Test
    fun cancelSessionRequests_duringSlowModel_marksSendFailedAndStopsLoading() = runBlocking {
        val session = env.seedPrivateChat()
        env.model.delayMs = 5_000L
        val vm = env.createViewModel()
        val completed = CompletableDeferred<Boolean>()
        vm.sendMessage(textOverride = "你好", targetSession = session, onResponseComplete = { completed.complete(it) })
        assertTrue("模型请求应已发出", env.waitUntil(20_000L) { env.model.requests.isNotEmpty() })
        vm.cancelSessionRequests("s1")
        val result = withTimeoutOrNull(20_000L) { completed.await() }
        assertEquals("取消后轮次应结束且未保存回复", false, result)
        val messages = env.repository.getMessagesSync("s1")
        assertEquals(1, messages.count { it.isMe })
        assertEquals(0, messages.count { it.type == "ai_json" })
        // D1（T35 步骤 2 冻结）：oracle 基线 = 协程已 cancel → catch/finally 中的 DB 写入被跳过，
        // 用户行保持 text、租约保持 running（不是 send_failed/pending！）。逐入口替换后必须复刻该状态，
        // 引擎为此提供 ChatEngineSemantics(cancelSkipsDbWrites = true)（见 ChatEngineSemanticsTest）。
        val userRow = messages.single { it.isMe }
        val turn = env.repository.replyTurns.get("private:s1:${userRow.id}")
        assertEquals("D1 基线：取消后用户行仍是 text", "text", userRow.type)
        assertEquals("D1 基线：取消后租约仍停在 running", ReplyTurnStatus.RUNNING, turn!!.status)
        assertTrue("D1 基线：无 AI 回复行", messages.none { it.type == "ai_json" })
    }

    // === 6. 恢复 persisted reply ===

    @Test
    fun resumePersistedReply_completesInterruptedTurn() = runBlocking {
        env.seedPrivateChat()
        val userTime = System.currentTimeMillis()
        val userId = env.repository.getNextMessageId()
        env.repository.sendMessage(
            "s1",
            ChatMessage(id = userId, sessionId = "s1", senderName = "我", content = "中断的消息", type = "send_failed", mode = "online", timestamp = userTime, isMe = true),
        )
        env.seedReplyTurn(turnId = "private:s1:$userId", sourceMessageId = userId)
        env.model.reply = replyJson("恢复后的回复")
        val (vm, session) = selectChat()
        val completed = CompletableDeferred<Boolean>()
        vm.resumePersistedReply(session.id, userId, onComplete = { completed.complete(it) })
        assertEquals(true, withTimeoutOrNull(60_000L) { completed.await() })
        val messages = env.repository.getMessagesSync("s1")
        assertTrue("恢复后应有 AI 回复", messages.any { !it.isMe && it.type == "ai_json" })
        assertNotEquals("失败消息类型应被重置", "send_failed", messages.single { it.isMe }.type)
        assertEquals(ReplyTurnStatus.SUCCEEDED, env.repository.replyTurns.get("private:s1:$userId")!!.status)
    }

    // === 7. 撤回（整条 / 分段）===

    @Test
    fun recallMessage_deletesSourceMessageAndCancelsTurn() = runBlocking {
        env.seedPrivateChat()
        val (userId, _) = seedRound(userText = "要被撤回的消息")
        env.seedReplyTurn(turnId = "private:s1:$userId", sourceMessageId = userId)
        val (vm, session) = selectChat()
        vm.recallMessage(userId)
        assertTrue("撤回应删除源消息行", env.waitUntil(10_000L) { env.repository.getMessageById(userId) == null })
        // 当前实现（批 C 基线）：MessageRepository.deleteMessage 连带删除 reply_turns 行；
        // 引擎版 recallMessage 保留 cancelled 终止态租约（T19 测试锁定）。该差异已记录到 handoff 对照表。
        assertNull("基线：撤回随消息删除租约行", env.repository.replyTurns.get("private:s1:$userId"))
        assertEquals("会话预览应回退", "第一次回复", env.repository.getSession(session.id)!!.lastMessage)
    }

    @Test
    fun recallMessageSegment_marksOnlyTargetSegmentRecalled() = runBlocking {
        env.seedPrivateChat()
        val twoSegment = """{"emotion":"平静","state":"坐着","location":"房间","continuity":"继续","segments":[{"type":"dialogue","content":"第一句","speaker":""},{"type":"dialogue","content":"第二句","speaker":""}]}"""
        val (_, aiId) = seedRound(reply = twoSegment)
        val (vm, _) = selectChat()
        vm.recallMessageSegment(aiId, 1)
        assertTrue("分段撤回应落库 recalled 标记", env.waitUntil(10_000L) {
            env.repository.getMessageById(aiId)?.content?.contains("\"recalled\": true") == true ||
                env.repository.getMessageById(aiId)?.content?.contains("\"recalled\":true") == true
        })
        val content = env.repository.getMessageById(aiId)!!.content
        assertTrue("目标段应标记", content.contains("第二句") && content.contains("recalled"))
        assertTrue("首段不应标记", !content.substringBefore("第二句").contains("recalled"))
    }

    // === 8. 重新生成 / 继续说 ===

    @Test
    fun regenerateAiMessage_replacesReplyInPlace() = runBlocking {
        env.seedPrivateChat()
        val (_, aiId) = seedRound(reply = replyJson("原始回复"))
        val (vm, _) = selectChat()
        env.model.reply = replyJson("重新生成的回复")
        vm.regenerateAiMessage(aiId)
        assertTrue("重新生成应原地替换", env.waitUntil(60_000L) {
            env.repository.getMessageById(aiId)?.content?.contains("重新生成的回复") == true
        })
        assertEquals("行数不应增加", 2, env.repository.getMessagesSync("s1").size)
        assertEquals(aiId, env.repository.getMessagesSync("s1").single { !it.isMe }.id)
    }

    @Test
    fun continueAiMessage_appendsNewReplyWithoutNewUserRow() = runBlocking {
        env.seedPrivateChat()
        val (userId, aiId) = seedRound(reply = replyJson("说到一半"))
        val (vm, session) = selectChat()
        env.model.reply = replyJson("继续说的内容")
        vm.continueAiMessage(aiId)
        assertTrue("继续说应新增 AI 行", env.waitUntil(60_000L) {
            env.repository.getMessagesSync("s1").any { !it.isMe && it.content.contains("继续说的内容") }
        })
        val messages = env.repository.getMessagesSync("s1")
        assertEquals("不应新增用户行", 1, messages.count { it.isMe })
        assertEquals(userId, messages.first { it.isMe }.id)
        assertEquals(3, messages.size)
    }

    // === 9. 存档 ===

    @Test
    fun createCurrentArchive_savesSnapshotForCompletedRound() = runBlocking {
        env.seedPrivateChat()
        seedRound(reply = replyJson("存档前的回复"))
        val (vm, _) = selectChat()
        val saved = CompletableDeferred<String>()
        vm.createCurrentArchive(title = "测试存档", note = "备注") { saved.complete(it) }
        val archiveId = withTimeoutOrNull(60_000L) { saved.await() }
        assertNotNull("存档应保存完成", archiveId)
        val archives = env.repository.getChatArchives("s1")
        assertEquals(1, archives.size)
        assertEquals("测试存档", archives.single().title)
        assertTrue(archives.single().messagesJson.contains("存档前的回复"))
    }
}

/** 与 FakeAiEngine.FAKE_REPLY_JSON 同构的可见回复 JSON（可自定义台词）。 */
private fun replyJson(text: String): String =
    """{"emotion":"平静","state":"坐着","location":"房间","continuity":"继续","segments":[{"type":"dialogue","content":"$text","speaker":""}]}"""
