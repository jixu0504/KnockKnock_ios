package com.rhodes.privatechat.viewmodel

import com.rhodes.privatechat.shared.domain.chat.ChatEngine
import com.rhodes.privatechat.shared.domain.chat.ChatEngineConfig
import com.rhodes.privatechat.shared.domain.chat.EngineEvent
import com.rhodes.privatechat.shared.domain.chat.ReplyDiagnostics
import com.rhodes.privatechat.shared.domain.chat.ChatEngineSemantics
import com.rhodes.privatechat.shared.domain.chat.group.GroupChatSemantics
import com.rhodes.privatechat.shared.domain.chat.group.GroupEngineEvent
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * T35 批 C 续作 步骤 1+2 验收：ChatEngineFactory 适配层（生产 DB + 生产提示词组装器 + 模型替身）
 * 与 D1/D2 基线对齐证据。
 *
 * 与 T28 的 `ChatViewModelCorePathTest` 使用同一宿主环境（真实 SQLDelight 库 + Ktor MockEngine），
 * 区别是这里走**适配层构造的引擎**，因此同时验证：
 * 1) 依赖映射正确（allocator 单实例 / 真实 ChatStore / 真实 PrivatePromptAssembler / SharedUtils 模型调用）；
 * 2) 事件映射表（11 种）落到宿主回调；
 * 3) D1 取消、D2 撤回在真实库上的可观测结果与 Android oracle 基线一致。
 */
class ChatEngineFactoryTest : RobolectricChatTest() {

    private fun replyJson(text: String): String =
        """{"emotion":"平静","state":"坐着","location":"房间","continuity":"继续","segments":[{"type":"dialogue","content":"$text","speaker":""}]}"""

    private fun newScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)

    private fun buildEngine(
        host: RecordingChatEngineHost = RecordingChatEngineHost(),
        config: ChatEngineConfig = ChatEngineFactory.buildConfig(env.settings),
        semantics: ChatEngineSemantics = ChatEngineFactory.ANDROID_BASELINE_SEMANTICS,
    ): Pair<ChatEngine, RecordingChatEngineHost> {
        val engine = ChatEngineFactory.createPrivateEngine(
            scope = newScope(),
            repository = env.repository,
            settings = env.settings,
            sharedUtils = env.sharedUtils,
            eventSink = ChatEngineEventMapper(host),
            config = config,
            semantics = semantics,
            imageForModelResolver = { null },
        )
        return engine to host
    }

    private fun awaitJob(job: Job?, timeoutMs: Long = 60_000L): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMs) { job?.join() } != null
    }

    // === 1. 适配层生产接线：一次完整发送 ===

    @Test
    fun factoryWiredEngine_sendSuccess_persistsRowsAndMapsEvents() = runBlocking {
        val session = env.seedPrivateChat()
        env.model.reply = replyJson("适配层回复")
        val (engine, host) = buildEngine()
        val completed = CompletableDeferred<Boolean>()
        engine.sendMessage(
            textOverride = "你好",
            session = session,
            mode = "online",
            onResponseComplete = { completed.complete(it) },
        )
        assertEquals(true, withTimeoutOrNull(90_000L) { completed.await() })

        // 真实 DB：用户行 + ai_json 行 + 租约闭环（与 oracle 基线一致）。
        val messages = env.repository.getMessagesSync("s1")
        val user = messages.single { it.isMe }
        val ai = messages.single { !it.isMe && it.type == "ai_json" }
        assertEquals("你好", user.content)
        assertTrue(ai.content.contains("适配层回复"))
        assertEquals(ReplyTurnStatus.SUCCEEDED, env.repository.replyTurns.get("private:s1:${user.id}")!!.status)

        // 事件映射（冻结表）：ReplyStored / Settled / Maintenance / Unhide / UnreadHint / Loading。
        assertEquals(1, host.replyStored.size)
        assertEquals(1, host.settled.size)
        assertEquals("op1", host.settled.single().operatorId)
        assertEquals(2, host.settled.single().affectionMod) // 默认 affection_mod=0 → 2
        assertEquals(50, host.settled.single().dailyLmb)
        val diagnostics = host.replyStored.single().diagnostics
        assertNotNull("ReplyStored 应携带诊断快照（T28 步骤 0）", diagnostics)
        assertTrue("真实提示词组装器应产出非空消息序列", diagnostics!!.promptMessageCount > 0)
        assertEquals(1, host.maintenance.size)
        assertTrue("首页应取消隐藏", host.unhidden.isNotEmpty())
        // 注：T19 引擎的真实 loading 状态经 `engine.loadingSessions` StateFlow 暴露，未发射 LoadingChanged 事件
        // （映射表保留该事件位；接线层应订阅 StateFlow）。此处断言引擎侧 loading 已回落。
        assertFalse(engine.loadingSessions.value.contains("s1"))
        assertTrue("成功轮不应有未送达事件", host.undelivered.isEmpty())
    }

    // === 2. D1 取消（真实库 + 基线语义）===

    @Test
    fun d1Cancel_legacySemanticsKeepsTextAndRunningTurnLikeBaseline() = runBlocking {
        val session = env.seedPrivateChat()
        env.model.delayMs = 5_000L
        val (engine, host) = buildEngine()
        val completed = CompletableDeferred<Boolean>()
        engine.sendMessage(
            textOverride = "你好",
            session = session,
            mode = "online",
            onResponseComplete = { completed.complete(it) },
        )
        assertTrue("模型请求应已发出", env.waitUntil(20_000L) { env.model.requests.isNotEmpty() })
        engine.cancelSessionRequests("s1")
        assertEquals("取消后回调 false", false, withTimeoutOrNull(20_000L) { completed.await() })

        // D1 oracle 基线（T28 ChatViewModelCorePathTest 实测 + 本卡真实库复验）：
        // 已取消协程中的 DB 写入被跳过 → 用户行仍为 text、租约仍为 running。
        val user = env.repository.getMessagesSync("s1").single { it.isMe }
        assertEquals("text", user.type)
        assertEquals(ReplyTurnStatus.RUNNING, env.repository.replyTurns.get("private:s1:${user.id}")!!.status)
        assertEquals(0, env.repository.getMessagesSync("s1").count { it.type == "ai_json" })
        assertTrue("基线取消不产生未送达事件", host.undelivered.isEmpty())
    }

    // === 3. D2 撤回（真实库：deleteMessage 级联删 reply_turns，同 oracle 基线）===

    @Test
    fun d2Recall_productionStoreDropsTurnRowLikeBaseline() = runBlocking {
        env.seedPrivateChat()
        val now = System.currentTimeMillis()
        val userId = env.repository.getNextMessageId()
        env.repository.sendMessage(
            "s1",
            ChatMessage(id = userId, sessionId = "s1", senderName = "我", content = "要被撤回的消息", type = "text", mode = "online", timestamp = now, isMe = true),
        )
        val aiId = env.repository.getNextMessageId()
        env.repository.sendMessage(
            "s1",
            ChatMessage(id = aiId, sessionId = "s1", senderName = "角色op1", content = replyJson("上一轮回复"), type = "ai_json", mode = "online", timestamp = now + 1, isMe = false),
        )
        env.seedReplyTurn(turnId = "private:s1:$userId", sourceMessageId = userId)
        val (engine, _) = buildEngine()
        assertTrue("撤回 job 应执行", awaitJob(engine.recallMessage("s1", userId)))

        // D2 oracle 基线：MessageRepository.deleteMessage 与 reply_turns 同事务 → 租约行随消息删除。
        assertNull("源消息应删除", env.repository.getMessageById(userId))
        assertNull("基线：租约行随消息级联删除（非 cancelled 终止态残留）", env.repository.replyTurns.get("private:s1:$userId"))
        assertEquals("会话预览应回退", "上一轮回复", env.repository.getSession("s1")!!.lastMessage)
    }

    // === 4. 对照证据：引擎默认语义在生产 DB 上同样被协程取消吞掉（说明为何工厂默认 legacy）===

    @Test
    fun defaultEngineSemanticsOnProductionStoreHasSameCancellationBlindSpot() = runBlocking {
        val session = env.seedPrivateChat()
        env.model.delayMs = 5_000L
        val (engine, _) = buildEngine(semantics = ChatEngineSemantics()) // 引擎默认语义（非基线）
        val completed = CompletableDeferred<Boolean>()
        engine.sendMessage("你好", session, "online", onResponseComplete = { completed.complete(it) })
        assertTrue(env.waitUntil(20_000L) { env.model.requests.isNotEmpty() })
        engine.cancelSessionRequests("s1")
        withTimeoutOrNull(20_000L) { completed.await() } ?: false

        // 关键结论（写入 handoff）：引擎文档化的「取消→send_failed+release」只在纯内存替身上成立；
        // 换成真实 SQLDelight 库（挂起写会检查协程取消）后，默认语义与 Android 基线是同一个盲区
        // ——用户行 text、租约 running。因此适配层默认取 legacy 语义，把「宿主侧清理必须完成」
        // 变成显式契约（NonCancellable），而不是依赖 finally 里非挂起语句的运气。
        val user = env.repository.getMessagesSync("s1").single { it.isMe }
        assertEquals("text", user.type)
        assertEquals(ReplyTurnStatus.RUNNING, env.repository.replyTurns.get("private:s1:${user.id}")!!.status)
    }

    // === 5. 事件映射表冻结（11 + 12 全量）===

    @Test
    fun privateEventMapperCoversAllEngineEvents() {
        val host = RecordingChatEngineHost()
        val sink = ChatEngineEventMapper(host)
        val diagnostics = ReplyDiagnostics(mode = "online", promptMessageCount = 7, historyMessageCount = 15, contentRetryCount = 1, segmentCount = 3, visibleCount = 2, protocolComplete = true)
        val events = listOf(
            EngineEvent.LoadingChanged("s1", true),
            EngineEvent.Toast("t1"),
            EngineEvent.ClaimDenied("s1", 11L, "private:s1:11"),
            EngineEvent.Undelivered("s1", setOf(11L)),
            EngineEvent.ReplyStored("s1", setOf(11L), 12L, diagnostics),
            EngineEvent.Settled("s1", "op1", 3, 50, 2, "预览", diagnostics),
            EngineEvent.TurnStateUpdated("s1"),
            EngineEvent.MaintenanceRequested("s1"),
            EngineEvent.UnhideSession("s1"),
            EngineEvent.UnreadHint("s1", 2),
            EngineEvent.FailureMessageStored("s1", 13L),
        )
        events.forEach(sink::emit)
        assertEquals(listOf("loading:s1:true", "toast:t1", "claim:s1:11", "undelivered:s1:11"), host.calls.take(4))
        assertEquals(1, host.replyStored.size)
        assertEquals(12L, host.replyStored.single().aiMessageId)
        assertEquals(diagnostics, host.replyStored.single().diagnostics)
        assertEquals(3, host.settled.single().affectionMod)
        assertEquals("预览", host.settled.single().preview)
        assertEquals(
            listOf("settled:s1", "turnState:s1", "maintenance:s1", "unhide:s1", "unread:s1:2", "failure:s1:13"),
            host.calls.drop(5),
        )
        assertEquals(11, host.calls.size)
    }

    @Test
    fun groupEventMapperCoversAllGroupEngineEvents() {
        val host = RecordingGroupChatEngineHost()
        val sink = GroupChatEngineEventMapper(host)
        val events = listOf(
            GroupEngineEvent.LoadingChanged("g1", true),
            GroupEngineEvent.Toast("t2"),
            GroupEngineEvent.ClaimDenied("g1", 21L, "group:g1:21"),
            GroupEngineEvent.Undelivered("g1", setOf(21L)),
            GroupEngineEvent.ReplyStored("g1", setOf(21L), 22L, listOf("阿米娅"), 1),
            GroupEngineEvent.SystemMessageStored("g1", 23L),
            GroupEngineEvent.TurnStateUpdated("g1", true),
            GroupEngineEvent.Settled("g1", 50, 2, "群预览"),
            GroupEngineEvent.MaintenanceRequested("g1", "summary"),
            GroupEngineEvent.UnhideSession("g1"),
            GroupEngineEvent.UnreadHint("g1", 3),
            GroupEngineEvent.AutoPlanChanged("g1", "scheduled", 1_000L, 2),
        )
        events.forEach(sink)
        assertEquals(12, host.calls.size)
        assertEquals("loading:g1:true", host.calls.first())
        assertEquals("autoPlan:g1:scheduled:1000:2", host.calls.last())
        assertEquals(1, host.replyStored.size)
        assertEquals(listOf("阿米娅"), host.replyStored.single().speakers)
        assertEquals(50, host.settled.single().dailyLmb)
    }

    // === 6. 预算配置映射（深度思考加成同源 oracle :107-140）===

    @Test
    fun buildConfig_mapsDeepseekThinkingBonusLikeOracle() {
        val base = ChatEngineConfig()
        val plain = ChatEngineFactory.buildConfig(env.settings, base, deepseekThinkingActive = false)
        assertEquals(180_000L, plain.effectiveTurnBudgetMs)
        assertEquals(120_000L, plain.effectiveModelBudgetMs)
        val thinking = ChatEngineFactory.buildConfig(env.settings, base, deepseekThinkingActive = true)
        assertEquals(240_000L, thinking.effectiveTurnBudgetMs)
        assertEquals(180_000L, thinking.effectiveModelBudgetMs)
    }

    // === 辅助：事件记录宿主 ===

    private class RecordingChatEngineHost : ChatEngineHost {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val replyStored: MutableList<EngineEvent.ReplyStored> = Collections.synchronizedList(mutableListOf())
        val settled: MutableList<EngineEvent.Settled> = Collections.synchronizedList(mutableListOf())
        val maintenance: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val unhidden: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val undelivered: MutableList<EngineEvent.Undelivered> = Collections.synchronizedList(mutableListOf())
        val loading: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(mutableListOf())

        override fun onLoadingChanged(sessionId: String, loading: Boolean) {
            this.loading += sessionId to loading
            calls += "loading:$sessionId:$loading"
        }

        override fun onToast(message: String) {
            calls += "toast:$message"
        }

        override fun onClaimDenied(sessionId: String, messageId: Long, turnId: String) {
            calls += "claim:$sessionId:$messageId"
        }

        override fun onUndelivered(sessionId: String, messageIds: Set<Long>) {
            undelivered += EngineEvent.Undelivered(sessionId, messageIds)
            calls += "undelivered:$sessionId:${messageIds.sorted().joinToString(",")}"
        }

        override fun onReplyStored(sessionId: String, userMessageIds: Set<Long>, aiMessageId: Long, diagnostics: ReplyDiagnostics?) {
            replyStored += EngineEvent.ReplyStored(sessionId, userMessageIds, aiMessageId, diagnostics)
            calls += "replyStored:$sessionId:$aiMessageId"
        }

        override fun onSettled(
            sessionId: String,
            operatorId: String,
            affectionMod: Int,
            dailyLmb: Int,
            visibleCount: Int,
            preview: String,
            diagnostics: ReplyDiagnostics?,
        ) {
            settled += EngineEvent.Settled(sessionId, operatorId, affectionMod, dailyLmb, visibleCount, preview, diagnostics)
            calls += "settled:$sessionId"
        }

        override fun onTurnStateUpdated(sessionId: String) {
            calls += "turnState:$sessionId"
        }

        override fun onMaintenanceRequested(sessionId: String) {
            maintenance += sessionId
            calls += "maintenance:$sessionId"
        }

        override fun onUnhideSession(sessionId: String) {
            unhidden += sessionId
            calls += "unhide:$sessionId"
        }

        override fun onUnreadHint(sessionId: String, count: Int) {
            calls += "unread:$sessionId:$count"
        }

        override fun onFailureMessageStored(sessionId: String, messageId: Long) {
            calls += "failure:$sessionId:$messageId"
        }
    }

    private class RecordingGroupChatEngineHost : GroupChatEngineHost {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val replyStored: MutableList<GroupEngineEvent.ReplyStored> = Collections.synchronizedList(mutableListOf())
        val settled: MutableList<GroupEngineEvent.Settled> = Collections.synchronizedList(mutableListOf())

        override fun onLoadingChanged(groupId: String, loading: Boolean) {
            calls += "loading:$groupId:$loading"
        }

        override fun onToast(message: String) {
            calls += "toast:$message"
        }

        override fun onClaimDenied(groupId: String, messageId: Long, turnId: String) {
            calls += "claim:$groupId:$messageId"
        }

        override fun onUndelivered(groupId: String, messageIds: Set<Long>) {
            calls += "undelivered:$groupId"
        }

        override fun onReplyStored(groupId: String, userMessageIds: Set<Long>, aiMessageId: Long, speakers: List<String>, narrationCount: Int) {
            replyStored += GroupEngineEvent.ReplyStored(groupId, userMessageIds, aiMessageId, speakers, narrationCount)
            calls += "replyStored:$groupId:$aiMessageId"
        }

        override fun onSystemMessageStored(groupId: String, messageId: Long) {
            calls += "systemMessage:$groupId:$messageId"
        }

        override fun onTurnStateUpdated(groupId: String, fallbackDerived: Boolean) {
            calls += "turnState:$groupId"
        }

        override fun onSettled(groupId: String, dailyLmb: Int?, visibleCount: Int, preview: String) {
            settled += GroupEngineEvent.Settled(groupId, dailyLmb, visibleCount, preview)
            calls += "settled:$groupId"
        }

        override fun onMaintenanceRequested(groupId: String, reason: String) {
            calls += "maintenance:$groupId"
        }

        override fun onUnhideSession(groupId: String) {
            calls += "unhide:$groupId"
        }

        override fun onUnreadHint(groupId: String, count: Int) {
            calls += "unread:$groupId:$count"
        }

        override fun onAutoPlanChanged(groupId: String, state: String, dueAt: Long, round: Int) {
            calls += "autoPlan:$groupId:$state:$dueAt:$round"
        }
    }
}
