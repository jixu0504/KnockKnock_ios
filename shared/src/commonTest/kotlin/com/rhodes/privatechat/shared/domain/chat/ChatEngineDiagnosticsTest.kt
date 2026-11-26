@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T28 批 C 步骤 0（诊断事件字段）：`ReplyDiagnostics` 随 [EngineEvent.ReplyStored]/[EngineEvent.Settled]
 * 下发的数据必须等于引擎内部中间量（宿主 finishDebugRound 的等价性前提，见《impl-wiring-b.md》§3.1 B1）。
 */
class ChatEngineDiagnosticsTest {

    private fun session() = ChatSession(id = "s1", operatorId = "op1", operatorName = "角色")

    /** 发送并等的轮完成回调；返回 null 表示超时未完成。 */
    private fun sendAndAwait(engine: ChatEngine, text: String = "你好"): Boolean? = runBlocking {
        val completed = CompletableDeferred<Boolean>()
        engine.sendMessage(text, session(), "online", onResponseComplete = { completed.complete(it) })
        withTimeoutOrNull(15_000L) { completed.await() }
    }

    @Test
    fun successfulPrivateTurnCarriesDiagnosticsOnReplyStoredAndSettled() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        val engine = harness.engine()
        assertEquals(true, sendAndAwait(engine))

        val stored = harness.replyStored().single()
        val diagnostics = assertNotNull(stored.diagnostics, "ReplyStored 必须携带诊断快照")
        assertEquals("online", diagnostics.mode)
        // harness 的假 assembler 固定返回 system + user 两条构建消息。
        assertEquals(2, diagnostics.promptMessageCount)
        assertEquals(harness.settingsRepository.historyMessages, diagnostics.historyMessageCount)
        assertEquals(0, diagnostics.contentRetryCount)
        assertEquals(1, diagnostics.segmentCount)
        assertEquals(1, diagnostics.visibleCount)
        assertTrue(diagnostics.protocolComplete)

        val settled = harness.settled().single()
        assertEquals(diagnostics, settled.diagnostics, "结算事件与落库事件必须携带同一份快照")
        assertEquals(diagnostics.visibleCount, settled.visibleCount)
    }

    @Test
    fun contextDegradationRetryIsReflectedInDiagnostics() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        // 第一次请求模拟上下文超限（触发历史轮数减半重试），第二次正常返回。
        val engine = harness.engine(aiClientOverride = object : StreamingClient {
            private var calls = 0
            override suspend fun complete(messages: List<AiMessage>, stage: String): String {
                calls++
                if (calls == 1) throw Exception("context length exceeded for this request")
                return FakeAiEngine.FAKE_REPLY_JSON
            }
        })
        assertEquals(true, sendAndAwait(engine))

        val diagnostics = assertNotNull(harness.replyStored().single().diagnostics)
        assertEquals(1, diagnostics.contentRetryCount)
        // 默认 historyMessages=15 → 降级一次变 7（oracle :1700-1721）。
        assertEquals(7, diagnostics.historyMessageCount)
    }

    @Test
    fun imageTurnCarriesDiagnostics() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        val engine = harness.engine()
        val completed = CompletableDeferred<Boolean>()
        val job = engine.sendImageMessage(
            imageUri = "file:///tmp/a.png",
            imageForModel = "file:///tmp/a.png",
            session = session(),
            mode = "online",
            onReplyComplete = { completed.complete(it) },
        )
        assertNotNull(job)
        assertTrue(withTimeoutOrNull(15_000L) { completed.await() } == true)
        val diagnostics = assertNotNull(harness.replyStored().single().diagnostics)
        assertEquals("online", diagnostics.mode)
        assertTrue(diagnostics.promptMessageCount > 0)
        assertEquals(1, diagnostics.visibleCount)
    }

    @Test
    fun undeliveredPathDoesNotEmitReplyStoredDiagnostics() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.aiEngine = FakeAiEngine(
            scripts = listOf(FakeAiEngine.NARRATION_ONLY_JSON, FakeAiEngine.NARRATION_ONLY_JSON),
        )
        val engine = harness.engine()
        assertEquals(false, sendAndAwait(engine))
        // 无可见回复：仅未送达，不发 ReplyStored/Settled（失败路径诊断仍为宿主侧遗留，见 handoff）。
        assertTrue(harness.replyStored().isEmpty())
        assertTrue(harness.settled().isEmpty())
        assertTrue(harness.undelivered().isNotEmpty())
    }
}
