@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 消息变更矩阵（验收 #5）：整条撤回（turn cancelled + 行删除）、分段撤回（display event 删除 +
 * JSON 标记）、replaceReplyIfUnchanged 乐观锁、重试防抖、重新生成失败保留原回复、续写。
 */
class ChatEngineMutationTest {

    private fun session() = ChatSession(id = "s1", operatorId = "op1", operatorName = "角色")
    private fun msg(id: Long, type: String = "text", content: String = "你好", isMe: Boolean = true) =
        ChatMessage(id = id, sessionId = "s1", senderName = if (isMe) "我" else "角色", content = content, type = type, isMe = isMe)

    private val aiJson = """{"emotion":"平静","state":"坐着","location":"宿舍","continuity":"继续","segments":[{"type":"dialogue","content":"第一段台词。","speaker":""},{"type":"dialogue","content":"第二段台词。","speaker":""}]}"""

    private fun await(job: kotlinx.coroutines.Job, timeoutMs: Long = 15_000L) = runBlocking {
        withTimeoutOrNull(timeoutMs) { job.join() } != null
    }

    @Test
    fun recallMessage_deletesRowAndCancelsTurn() = runBlocking {
        val harness = ChatEngineTestHarness()
        harness.store.seedSession(session())
        harness.store.seedMessage(msg(1))
        harness.store.seedMessage(msg(2, type = "ai_json", isMe = false))
        harness.store.seedTurn(
            com.rhodes.privatechat.shared.model.ReplyTurn(
                "private:s1:1", "s1", "private", "manual", 1L, "", "online", ReplyTurnStatus.RUNNING,
                1, 0L, "tok", 999_999L, null, "", 1_000L, 1_000L, 0L,
            ),
        )
        val engine = harness.engine()
        val job = engine.recallMessage("s1", 1L)!!
        assertTrue(await(job))
        assertNull(harness.store.getMessageById(1))
        assertNotNull(harness.store.getMessageById(2)) // 只删源消息
        assertEquals("cancelled", harness.store.getTurn("private:s1:1")!!.status)
        // 会话预览更新为最新一条（AI 台词）。
        val sessionAfter = harness.store.getSession("s1")!!
        assertTrue(sessionAfter.lastMessage.contains("你好")) // 预览=最新一条 AI 消息内容
    }

    @Test
    fun recallMessageSegment_marksJsonAndDeletesDisplayEvent() = runBlocking {
        val harness = ChatEngineTestHarness()
        harness.store.seedSession(session())
        harness.store.seedMessage(msg(2, type = "ai_json", content = aiJson, isMe = false))
        harness.store.seedDisplayEvent(ChatDisplayEventView(2, 0, 100L))
        harness.store.seedDisplayEvent(ChatDisplayEventView(2, 1, 200L))
        val engine = harness.engine()
        val job = engine.recallMessageSegment("s1", 2L, 1)!!
        assertTrue(await(job))
        val updated = harness.store.getMessageById(2)!!
        assertTrue(PrivateReplyFormat.isPrivateSegmentRecalled(updated.content, 1))
        assertFalse(PrivateReplyFormat.isPrivateSegmentRecalled(updated.content, 0))
        val events = harness.store.getDisplayEvents("s1")
        assertEquals(1, events.size)
        assertEquals(0, events.single().segmentIndex)
    }

    @Test
    fun recallMessageSegment_invalidTargetFallsBackToWholeRecall() = runBlocking {
        val harness = ChatEngineTestHarness()
        harness.store.seedSession(session())
        harness.store.seedMessage(msg(1))
        harness.store.seedTurn(
            com.rhodes.privatechat.shared.model.ReplyTurn(
                "private:s1:1", "s1", "private", "manual", 1L, "", "online", ReplyTurnStatus.PENDING,
                0, 0L, "", 0L, null, "", 1_000L, 1_000L, 0L,
            ),
        )
        val engine = harness.engine()
        // 非 ai_json → 整条撤回；越界 index → 整条撤回。
        val job1 = engine.recallMessageSegment("s1", 1L, 0)!!
        assertTrue(await(job1))
        assertNull(harness.store.getMessageById(1))
        assertEquals("cancelled", harness.store.getTurn("private:s1:1")!!.status)
    }

    @Test
    fun regenerateAiMessage_replacesInPlaceAndClearsDisplayEvents() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        harness.store.seedMessage(msg(2, type = "ai_json", content = aiJson, isMe = false))
        harness.store.seedDisplayEvent(ChatDisplayEventView(2, 0, 100L))
        harness.aiEngine = FakeAiEngine(listOf(FakeAiEngine.dialogue("重新生成的版本。")))
        val engine = harness.engine()
        val job = engine.regenerateAiMessage("s1", 2L)!!
        assertTrue(await(job))
        val regenerated = harness.store.getMessageById(2)!!
        assertTrue(regenerated.content.contains("重新生成的版本。"))
        assertTrue(harness.store.getDisplayEvents("s1").isEmpty())
        assertEquals(1, harness.store.getMessagesSync("s1").size) // 同 id 原地替换
    }

    @Test
    fun regenerateAiMessage_optimisticLockKeepsOriginalOnConcurrentChange() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        harness.store.seedMessage(msg(2, type = "ai_json", content = aiJson, isMe = false))
        val original = harness.store.getMessageById(2)!!
        // 模型返回前原回复被第三方改写 → replaceReplyIfUnchanged 乐观锁拒绝覆盖。
        val client = object : StreamingClient {
            override suspend fun complete(messages: List<com.rhodes.privatechat.shared.model.AiMessage>, stage: String): String {
                harness.store.updateMessageContent(2L, """{"emotion":"愤怒","segments":[{"type":"dialogue","content":"已被修改。","speaker":""}]}""")
                return FakeAiEngine.dialogue("本次重说结果。")
            }
        }
        val engine = ChatEngine(
            scope = harness.scope, store = harness.store,
            allocator = com.rhodes.privatechat.shared.domain.data.MessageIdAllocator(
                com.rhodes.privatechat.shared.domain.data.MessageMaxIdSource { harness.store.blockingMaxId() }, harness.settingsRepository,
            ),
            settings = harness.settingsRepository, assembler = harness.assembler, aiClient = client,
            clock = harness.clock, timeSource = harness.timeSource, config = harness.config,
            events = EngineEventSink { harness.events += it },
            promptBuildDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2),
        )
        val job = engine.regenerateAiMessage("s1", 2L)!!
        assertTrue(await(job))
        // 原回复保留 + 系统失败消息落库。
        val kept = harness.store.getMessageById(2)!!
        assertTrue(kept.content.contains("已被修改。"))
        val failure = harness.store.getMessagesSync("s1").firstOrNull { it.type == "system" }
        assertNotNull(failure)
        Unit
    }

    @Test
    fun regenerateAiMessage_noVisibleReplyKeepsOriginalAndStoresFailure() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        harness.store.seedMessage(msg(2, type = "ai_json", content = aiJson, isMe = false))
        harness.aiEngine = FakeAiEngine(listOf(FakeAiEngine.NARRATION_ONLY_JSON))
        val engine = harness.engine()
        val job = engine.regenerateAiMessage("s1", 2L)!!
        assertTrue(await(job))
        assertEquals(aiJson, harness.store.getMessageById(2)!!.content) // 原回复保留
        assertNotNull(harness.store.getMessagesSync("s1").firstOrNull { it.type == "system" })
        Unit
    }

    @Test
    fun continueAiMessage_appendsNewAiJsonMessage() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        harness.store.seedMessage(msg(2, type = "ai_json", content = aiJson, isMe = false))
        harness.aiEngine = FakeAiEngine(listOf(FakeAiEngine.dialogue("继续说下去的内容。")))
        val engine = harness.engine()
        val job = engine.continueAiMessage("s1", 2L)!!
        assertTrue(await(job))
        val messages = harness.store.getMessagesSync("s1")
        assertEquals(2, messages.size)
        assertEquals("ai_json", messages.last().type)
        assertEquals(3L, messages.last().id) // 新 msgId 追加
        assertTrue(messages.last().content.contains("继续说下去的内容。"))
    }

    @Test
    fun retryFailedMessage_guardsTypeAndDeduplicates() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        harness.store.seedMessage(msg(1, type = "send_failed"))
        harness.store.seedTurn(
            com.rhodes.privatechat.shared.model.ReplyTurn(
                "private:s1:1", "s1", "private", "manual", 1L, "", "online", ReplyTurnStatus.PENDING,
                0, 0L, "", 0L, null, "", 1_000L, 1_000L, 0L,
            ),
        )
        // 非 send_failed/gift_reply_failed → 拒绝。
        harness.store.seedMessage(msg(2, type = "text"))
        val engine = harness.engine()
        assertFalse(engine.retryFailedMessage("s1", 2L))
        assertFalse(engine.retryFailedMessage("s1", 99L)) // 不存在
        // 合法重试成功。
        assertTrue(engine.retryFailedMessage("s1", 1L))
        assertEquals("text", harness.store.getMessageById(1)!!.type)
        assertEquals("ai_json", harness.store.getMessageById(3)!!.type)
        assertEquals(ReplyTurnStatus.SUCCEEDED, harness.store.getTurn("private:s1:1")!!.status)
    }

    @Test
    fun retryFailedMessage_deduplicatesConcurrentCalls() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        harness.store.seedMessage(msg(4, type = "send_failed"))
        harness.store.seedTurn(
            com.rhodes.privatechat.shared.model.ReplyTurn(
                "private:s1:4", "s1", "private", "manual", 4L, "", "online", ReplyTurnStatus.PENDING,
                0, 0L, "", 0L, null, "", 1_000L, 1_000L, 0L,
            ),
        )
        harness.aiEngine.suspendBeforeCompleteMillis = 800
        val engine = harness.engine()
        val gate = CompletableDeferred<Unit>()
        val first = async { gate.await(); engine.retryFailedMessage("s1", 4L) }
        val second = async { gate.await(); engine.retryFailedMessage("s1", 4L) }
        gate.complete(Unit)
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it }) // 防抖：并发第二次被去重拒绝
        assertEquals(1, results.count { !it })
        assertEquals(1, harness.store.getMessagesSync("s1").count { it.type == "ai_json" })
    }

    @Test
    fun sendMessage_guardRejectsEmptyTextAndConfigError() = runBlocking {
        val harness = ChatEngineTestHarness()
        val engine = harness.engine(chatConfigError = { "缺少 API Key" })
        assertNull(engine.sendMessage("   ", session(), "online")) // trim 后空文本静默拒绝
        assertNull(engine.sendMessage("你好", session(), "online")) // 配置错误 → Toast + 拒绝
        assertTrue(harness.toastMessages().any { it.contains("API Key") })
        assertTrue(harness.store.getMessagesSync("s1").isEmpty())
    }
}
