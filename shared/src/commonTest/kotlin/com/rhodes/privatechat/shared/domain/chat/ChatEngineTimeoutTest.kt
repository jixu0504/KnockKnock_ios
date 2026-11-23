@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 超时矩阵（验收 #3）：prompt_build 50s / model 120s / ai_reply_write 15s / 整轮 180s(240s) /
 * 内容重试（1 次、剩余 <5s 跳过）/ 上下文降级（减半 min5、≤3 次）。
 * 注入压缩预算 + fake clock/TestTimeSource，真实耗时保持毫秒级。
 */
class ChatEngineTimeoutTest {

    private fun session() = ChatSession(id = "s1", operatorId = "op1", operatorName = "角色")
    private fun userMessage(id: Long, type: String = "text") =
        ChatMessage(id = id, sessionId = "s1", senderName = "我", content = "你好", type = type, isMe = true)
    private fun turn(id: String, sourceMessageId: Long, status: String = ReplyTurnStatus.PENDING) = ReplyTurn(
        id = id, sessionId = "s1", surface = "private", triggerKind = "manual", sourceMessageId = sourceMessageId,
        autoPlanToken = "", mode = "online", status = status, attemptCount = 0, nextAttemptAt = 0L,
        leaseToken = "", leaseUntil = 0L, responseMessageId = null, lastError = "",
        createdAt = 1_000L, updatedAt = 1_000L, completedAt = 0L,
    )

    private fun await(job: kotlinx.coroutines.Job, timeoutMs: Long = 15_000L) = runBlocking {
        withTimeoutOrNull(timeoutMs) { job.join() } != null
    }

    @Test
    fun budgetConstantsMatchSpec() {
        assertEquals(180_000L, ChatStageBudget.TURN_BUDGET_MS)
        assertEquals(60_000L, ChatStageBudget.THINKING_BUDGET_BONUS_MS)
        assertEquals(50_000L, ChatStageBudget.PROMPT_BUILD_BUDGET_MS)
        assertEquals(120_000L, ChatStageBudget.MODEL_BUDGET_MS)
        assertEquals(15_000L, ChatStageBudget.MESSAGE_WRITE_TIMEOUT_MS)
        assertEquals(12_000L, ChatStageBudget.OPTIONAL_CONTEXT_POOL_MS)
        assertEquals(5_000L, ChatStageBudget.CONTENT_RETRY_MIN_REMAINING_MS)
        assertEquals(3, ChatStageBudget.MAX_RETRIES)
        assertEquals(5, ChatStageBudget.MIN_DEGRADED_HISTORY)
        val base = ChatEngineConfig()
        assertEquals(180_000L, base.effectiveTurnBudgetMs)
        assertEquals(120_000L, base.effectiveModelBudgetMs)
        base.deepseekThinkingActive = true
        assertEquals(240_000L, base.effectiveTurnBudgetMs)
        assertEquals(180_000L, base.effectiveModelBudgetMs)
    }

    @Test
    fun promptBuildStageTimeout_marksUndeliveredAndToast() = runBlocking {
        val harness = ChatEngineTestHarness(
            config = ChatEngineConfig(promptBuildBudgetMs = 100, debounceBeforeAiMillis = 10),
            extraAssemblerDelayMillis = 800,
        )
        val engine = harness.engine()
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == false)
        assertTrue(await(job))
        assertEquals("send_failed", harness.store.getMessageById(1)!!.type)
        assertTrue(harness.undelivered().any { 1L in it.messageIds })
        assertTrue(harness.toastMessages().any { "提示词构建超时" in it })
        // 租约释放回 pending（reconcile 可再补答）。
        assertEquals(ReplyTurnStatus.PENDING, harness.store.getTurn("private:s1:1")!!.status)
    }

    @Test
    fun modelStageTimeout_marksUndelivered() = runBlocking {
        val harness = ChatEngineTestHarness(
            config = ChatEngineConfig(modelBudgetMs = 100, debounceBeforeAiMillis = 10),
        )
        harness.aiEngine.suspendBeforeCompleteMillis = 800
        val engine = harness.engine()
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == false)
        assertTrue(await(job))
        assertEquals("send_failed", harness.store.getMessageById(1)!!.type)
        assertTrue(harness.toastMessages().any { "模型请求超时" in it })
        assertEquals(ReplyTurnStatus.PENDING, harness.store.getTurn("private:s1:1")!!.status)
    }

    @Test
    fun aiReplyWriteStageTimeout_marksUndelivered() = runBlocking {
        val harness = ChatEngineTestHarness(
            config = ChatEngineConfig(messageWriteBudgetMs = 100, debounceBeforeAiMillis = 10),
        )
        harness.store.replyWriteDelayMillis = 800
        val engine = harness.engine()
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == false)
        assertTrue(await(job))
        assertEquals("send_failed", harness.store.getMessageById(1)!!.type)
        assertTrue(harness.toastMessages().any { "AI回复保存超时" in it })
        assertEquals(ReplyTurnStatus.PENDING, harness.store.getTurn("private:s1:1")!!.status)
    }

    @Test
    fun wholeTurnTimeoutToast_textFormatMatchesOracle() {
        // oracle ChatStageTimeout.kt:35-36 —— 整轮超时文案（预算=240s 深度思考口径）。
        val message = ChatStageTimeoutException("private", "turn_total", 240_000L, 239_000L).userMessage("私聊")
        assertEquals("私聊整轮处理超时（预算240秒），本轮未生成或保存AI回复，请重试", message)
        assertEquals("模型请求", "model_primary_request".chatStageLabel())
        assertEquals("AI回复保存", "ai_reply_write".chatStageLabel())
        assertEquals("提示词构建", "prompt_build".chatStageLabel())
    }

    @Test
    fun aiMessageIdAllocationTimeout_userPersistedMarksUndeliveredSilently() = runBlocking {
        // AI 消息 ID 分配（第二次 allocate）超时 → 外层裸 TCE（oracle :1733-1742）：
        // 用户消息已持久化 → 静默未送达（不 Toast）。注：oracle 与 iOS 的 withTimeout 均无法抢占
        // 不挂起的阻塞调用（ChatViewModel.kt:3079-3081 注释），此处用同步抛 TCE 模拟超时裁决。
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(messageWriteBudgetMs = 100, debounceBeforeAiMillis = 10))
        var allocateCalls = 0
        val engine = ChatEngine(
            scope = harness.scope, store = harness.store,
            allocator = com.rhodes.privatechat.shared.domain.data.MessageIdAllocator(
                com.rhodes.privatechat.shared.domain.data.MessageMaxIdSource {
                    allocateCalls++
                    if (allocateCalls >= 2) {
                        // TCE 构造器在 kotlinx-coroutines 为 internal：借真实 withTimeout 取得实例。
                        throw kotlinx.coroutines.runBlocking {
                            runCatching { kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.awaitCancellation() } }
                                .exceptionOrNull() ?: error("no timeout")
                        }
                    }
                    harness.store.blockingMaxId()
                },
                harness.settingsRepository,
            ),
            settings = harness.settingsRepository, assembler = harness.assembler,
            aiClient = harness.aiEngine, clock = harness.clock, timeSource = harness.timeSource,
            config = harness.config, events = EngineEventSink { harness.events += it },
            promptBuildDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2),
        )
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == false)
        assertTrue(await(job))
        assertEquals("send_failed", harness.store.getMessageById(1)!!.type)
        assertTrue(harness.toastMessages().none { "整轮处理超时" in it }) // 外层分支只标记，不 Toast
        assertEquals(ReplyTurnStatus.PENDING, harness.store.getTurn("private:s1:1")!!.status)
    }

    @Test
    fun contentRetry_onceOnNarrationOnly_thenSuccess() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.aiEngine = FakeAiEngine(listOf(FakeAiEngine.NARRATION_ONLY_JSON, FakeAiEngine.dialogue("这次可以了。")))
        val engine = harness.engine()
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == true)
        assertTrue(await(job))
        // 两次模型调用：首次 + 内容重试（改写 system 提示）。
        val requests = harness.aiEngine.requests
        assertEquals(2, requests.size)
        assertEquals("model_primary_request", requests[0].stage)
        assertEquals("model_content_retry", requests[1].stage)
        assertTrue(requests[1].messages.first().content.contains("【重新生成要求】"))
        val reply = harness.store.getMessageById(2)!!
        assertEquals("ai_json", reply.type)
        assertTrue(reply.content.contains("这次可以了。"))
        assertEquals(ReplyTurnStatus.SUCCEEDED, harness.store.getTurn("private:s1:1")!!.status)
        assertEquals(1, harness.settled().size)
    }

    @Test
    fun contentRetry_skippedWhenRemainingTurnBudgetBelow5s() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        // 首次返回旁白-only，同时在首次模型调用内推进测试时钟 236s → 剩余整轮 < 5s → 跳过重试。
        val inner = FakeAiEngine(listOf(FakeAiEngine.NARRATION_ONLY_JSON))
        val advancing = object : StreamingClient {
            override suspend fun complete(messages: List<com.rhodes.privatechat.shared.model.AiMessage>, stage: String): String {
                harness.timeSource += 236_000.milliseconds
                return inner.complete(messages, stage)
            }
        }
        val engine = ChatEngine(
            scope = harness.scope, store = harness.store,
            allocator = com.rhodes.privatechat.shared.domain.data.MessageIdAllocator(
                com.rhodes.privatechat.shared.domain.data.MessageMaxIdSource { harness.store.blockingMaxId() }, harness.settingsRepository,
            ),
            settings = harness.settingsRepository, assembler = harness.assembler,
            aiClient = advancing, clock = harness.clock, timeSource = harness.timeSource,
            config = harness.config, events = EngineEventSink { harness.events += it },
            promptBuildDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2),
        )
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == false)
        assertTrue(await(job))
        assertEquals(1, inner.requests.size) // 未发起内容重试
        assertEquals("send_failed", harness.store.getMessageById(1)!!.type)
        assertTrue(harness.settled().isEmpty()) // 不可见回复不结算
    }

    @Test
    fun contextLimitError_degradesHistoryHalvedMax3Retries() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.settingsRepository.historyMessages = 30
        var calls = 0
        val client = object : StreamingClient {
            override suspend fun complete(messages: List<com.rhodes.privatechat.shared.model.AiMessage>, stage: String): String {
                calls++
                return if (calls <= 2) throw RuntimeException("400 context_length exceeded, maximum context length is 8192 tokens")
                else FakeAiEngine.dialogue("降级后成功。")
            }
        }
        val engine = ChatEngine(
            scope = harness.scope, store = harness.store,
            allocator = com.rhodes.privatechat.shared.domain.data.MessageIdAllocator(
                com.rhodes.privatechat.shared.domain.data.MessageMaxIdSource { harness.store.blockingMaxId() }, harness.settingsRepository,
            ),
            settings = harness.settingsRepository, assembler = harness.assembler,
            aiClient = client, clock = harness.clock, timeSource = harness.timeSource,
            config = harness.config, events = EngineEventSink { harness.events += it },
            promptBuildDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2),
        )
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == true)
        assertTrue(await(job))
        assertEquals(3, calls)
        // 历史轮数减半：30 → 15 → 7。
        assertEquals(listOf(30, 15, 7), harness.assemblerRequests.map { it.historyLimitOverride })
        assertEquals("ai_json", harness.store.getMessageById(2)!!.type)
    }

    @Test
    fun contextDegrade_floorIs5AndRetriesCapped() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.settingsRepository.historyMessages = 6
        val client = object : StreamingClient {
            override suspend fun complete(messages: List<com.rhodes.privatechat.shared.model.AiMessage>, stage: String): String =
                throw RuntimeException("400 上下文超限")
        }
        val engine = ChatEngine(
            scope = harness.scope, store = harness.store,
            allocator = com.rhodes.privatechat.shared.domain.data.MessageIdAllocator(
                com.rhodes.privatechat.shared.domain.data.MessageMaxIdSource { harness.store.blockingMaxId() }, harness.settingsRepository,
            ),
            settings = harness.settingsRepository, assembler = harness.assembler,
            aiClient = client, clock = harness.clock, timeSource = harness.timeSource,
            config = harness.config, events = EngineEventSink { harness.events += it },
            promptBuildDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2),
        )
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == false)
        assertTrue(await(job))
        // 6/2=3 → coerce 5；此后 5 > 5 不成立，停止降级。
        assertEquals(listOf(6, 5), harness.assemblerRequests.map { it.historyLimitOverride })
        assertEquals("send_failed", harness.store.getMessageById(1)!!.type)
    }

    @Test
    fun userMessageWriteTimeout_noPersistenceAndToast() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(messageWriteBudgetMs = 100, debounceBeforeAiMillis = 10))
        harness.store.writeDelayMillis = 800
        val engine = harness.engine()
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == false)
        assertTrue(await(job))
        assertTrue(harness.toastMessages().any { "消息保存超时" in it })
        assertTrue(harness.store.getMessagesSync("s1").isEmpty())
        assertTrue(harness.store.getTurn("private:s1:1") == null)
    }
}
