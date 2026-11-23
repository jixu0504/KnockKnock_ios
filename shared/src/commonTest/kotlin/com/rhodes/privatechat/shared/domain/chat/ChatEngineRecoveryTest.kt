@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.CompletableDeferred
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
 * 取消 / 中断恢复矩阵（验收 #4）：发送中取消→send_failed、双发防抖、claim 拒绝、
 * resumePersistedReply 语义（type 重置、租约透传）、启动 reconcile + 前台补算。
 */
class ChatEngineRecoveryTest {

    private fun session() = ChatSession(id = "s1", operatorId = "op1", operatorName = "角色")
    private fun message(id: Long, type: String = "send_failed", content: String = "你好") =
        ChatMessage(id = id, sessionId = "s1", senderName = "我", content = content, type = type, isMe = true)
    private fun turn(id: String, sourceMessageId: Long, status: String = ReplyTurnStatus.PENDING, createdAt: Long = 1_000L, leaseToken: String = "", leaseUntil: Long = 0L, nextAttemptAt: Long = 0L) = ReplyTurn(
        id = id, sessionId = "s1", surface = "private", triggerKind = "manual", sourceMessageId = sourceMessageId,
        autoPlanToken = "", mode = "online", status = status, attemptCount = 0, nextAttemptAt = nextAttemptAt,
        leaseToken = leaseToken, leaseUntil = leaseUntil, responseMessageId = null, lastError = "",
        createdAt = createdAt, updatedAt = createdAt, completedAt = 0L,
    )

    private fun await(job: kotlinx.coroutines.Job, timeoutMs: Long = 15_000L) = runBlocking {
        withTimeoutOrNull(timeoutMs) { job.join() } != null
    }

    @Test
    fun cancelDuringSend_marksUndeliveredAndReleasesTurn() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.aiEngine.suspendBeforeCompleteMillis = 5_000
        val engine = harness.engine()
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        // 等到 loading 与模型请求已开始。
        withTimeoutOrNull(10_000L) {
            while (harness.aiEngine.requests.isEmpty() || !engine.loadingSessions.value.contains("s1")) {
                delay(10)
            }
        }
        engine.cancelSessionRequests("s1")
        assertTrue(await(job))
        assertTrue(!engine.loadingSessions.value.contains("s1"))
        assertEquals("send_failed", harness.store.getMessageById(1)!!.type)
        assertTrue(harness.undelivered().any { 1L in it.messageIds })
        // 租约释放回 pending → reconcile 可补答。
        assertEquals(ReplyTurnStatus.PENDING, harness.store.getTurn("private:s1:1")!!.status)
        assertTrue(harness.replyStored().isEmpty())
    }

    @Test
    fun recallDuringDebounce_cancelsPipelineAndTurnBecomesCancelled() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 800))
        val engine = harness.engine()
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        delay(150) // debounce 窗口内撤回
        engine.recallMessage("s1", 1L)
        assertTrue(await(job))
        assertNull(harness.store.getMessageById(1)) // 行已删除
        assertEquals("cancelled", harness.store.getTurn("private:s1:1")!!.status) // 终止态
        assertTrue(harness.replyStored().isEmpty())
        // cancelled 永不可被 reconcile 拾起。
        assertTrue(harness.store.retryableTurnIds(since = 0, now = Long.MAX_VALUE, limit = 50).isEmpty())
    }

    @Test
    fun doubleSendSecondRetryWhileInflight_claimDeniedByFirstLease() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        harness.aiEngine.suspendBeforeCompleteMillis = 2_000
        val engine = harness.engine()
        val first = CompletableDeferred<Boolean>()
        engine.sendMessage("你好", session(), "online", onResponseComplete = { first.complete(it) })
        // 第一条在飞时，对同一条消息补发（等价 oracle 的 resume/双发第二发）。
        withTimeoutOrNull(10_000L) {
            while (harness.aiEngine.requests.isEmpty()) delay(10)
        }
        val resumed = engine.resumePersistedReply("s1", 1L)
        assertFalse(resumed)
        assertTrue(harness.events.filterIsInstance<EngineEvent.ClaimDenied>().any { it.messageId == 1L })
        // oracle :1452 —— Toast 仅非重试路径；resume/补发（retryMessageId != null）静默让位。
        assertTrue(harness.toastMessages().none { "消息已保存，回复稍后自动补上" in it })
        // 第一条照常完成，只有一条 AI 回复。
        assertTrue(withTimeoutOrNull(15_000L) { first.await() } == true)
        assertEquals("ai_json", harness.store.getMessageById(2)!!.type)
        assertEquals(ReplyTurnStatus.SUCCEEDED, harness.store.getTurn("private:s1:1")!!.status)
        assertEquals(1, harness.replyStored().size)
    }

    @Test
    fun resumePersistedReply_resetsTypeAndCompletesTurn() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        harness.store.seedMessage(message(1, type = "send_failed"))
        harness.store.seedTurn(turn("private:s1:1", 1L))
        val engine = harness.engine()
        val ok = engine.resumePersistedReply("s1", 1L)
        assertTrue(ok)
        assertEquals("text", harness.store.getMessageById(1)!!.type) // type 重置
        assertEquals("ai_json", harness.store.getMessageById(2)!!.type)
        assertEquals(ReplyTurnStatus.SUCCEEDED, harness.store.getTurn("private:s1:1")!!.status)
    }

    @Test
    fun resumePersistedReply_giftReplyFailedResetsToGiftHidden() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        val giftPayload = """{"event":"gift","prompt":"送了你一份礼物","imageUri":"","giftName":"巧克力","recipientNames":[]}"""
        harness.store.seedMessage(message(1, type = "gift_reply_failed", content = giftPayload))
        harness.store.seedTurn(turn("private:s1:1", 1L))
        val engine = harness.engine()
        val ok = engine.resumePersistedReply("s1", 1L)
        assertTrue(ok)
        assertEquals("gift_hidden", harness.store.getMessageById(1)!!.type)
        // 礼物载荷还原为提示词文本（giftPromptText）。
        val lastUser = harness.aiEngine.lastUserContent()
        assertNotNull(lastUser)
        assertTrue(lastUser.contains("送了你一份礼物"))
    }

    @Test
    fun resumePersistedReply_leaseTokenPassthrough_skipsClaim() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        harness.store.seedMessage(message(1, type = "send_failed"))
        // 模拟 iOS 前台补算先 claim（租约透传：引擎不再抢租约）。
        harness.store.seedTurn(turn("private:s1:1", 1L, status = ReplyTurnStatus.RUNNING, leaseToken = "worker-token", leaseUntil = 9_999_999_999L))
        val engine = harness.engine()
        val ok = engine.resumePersistedReply("s1", 1L, replyTurnId = "private:s1:1", replyLeaseToken = "worker-token")
        assertTrue(ok)
        assertEquals("text", harness.store.getMessageById(1)!!.type)
        assertEquals(ReplyTurnStatus.SUCCEEDED, harness.store.getTurn("private:s1:1")!!.status)
        assertTrue(harness.events.filterIsInstance<EngineEvent.ClaimDenied>().isEmpty())
    }

    @Test
    fun resumePersistedReply_missingSessionOrMessage_returnsTrueWithoutSideEffects() = runBlocking {
        val harness = ChatEngineTestHarness()
        val engine = harness.engine()
        // 无 session / 无消息 → onComplete(true)，零副作用（oracle :1798-1803）。
        assertTrue(engine.resumePersistedReply("s1", 99L))
        assertTrue(harness.store.getMessagesSync("s1").isEmpty())
        assertTrue(harness.aiEngine.requests.isEmpty())
        // 非 isMe 消息同样直接返回。
        harness.store.seedSession(session())
        harness.store.seedMessage(ChatMessage(id = 5L, sessionId = "s1", senderName = "角色", content = "hi", type = "ai_json", isMe = false))
        assertTrue(engine.resumePersistedReply("s1", 5L))
        assertEquals("ai_json", harness.store.getMessageById(5)!!.type) // 未重置
    }

    @Test
    fun onAppStartReconcile_listsExactlyRetryableTurns() = runBlocking {
        val harness = ChatEngineTestHarness()
        // 引擎以注入时钟（1_750_000_000_000）为 now；租约相对该时钟取值。
        val base = harness.clock.now
        harness.store.seedTurn(turn("t-pending", 1L, createdAt = base - 8_000L, nextAttemptAt = 0L))
        harness.store.seedTurn(turn("t-failed", 2L, status = ReplyTurnStatus.FAILED, createdAt = base - 7_000L))
        harness.store.seedTurn(turn("t-expired-running", 3L, status = ReplyTurnStatus.RUNNING, leaseToken = "x", leaseUntil = base - 100L, createdAt = base - 6_000L))
        harness.store.seedTurn(turn("t-fresh-running", 4L, status = ReplyTurnStatus.RUNNING, leaseToken = "y", leaseUntil = base + 1_000_000L, createdAt = base - 5_000L))
        harness.store.seedTurn(turn("t-succeeded", 5L, status = ReplyTurnStatus.SUCCEEDED, createdAt = base - 4_000L))
        harness.store.seedTurn(turn("t-cancelled", 6L, status = "cancelled", createdAt = base - 3_000L))
        val engine = harness.engine()
        val ids = engine.onAppStartReconcile()
        val ordered = mutableListOf<Pair<String, Long>>()
        for (id in ids) ordered += id to (harness.store.getTurn(id)!!.createdAt)
        assertEquals(listOf("t-pending", "t-failed", "t-expired-running"), ordered.sortedBy { it.second }.map { it.first })
    }

    @Test
    fun resumeSessionTurns_foregroundCompletionDrivesRetryableTurns() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        harness.store.seedMessage(message(1, type = "send_failed"))
        harness.store.seedTurn(turn("private:s1:1", 1L))
        val engine = harness.engine()
        val resumed = engine.resumeSessionTurns("s1")
        assertEquals(1, resumed)
        assertEquals("text", harness.store.getMessageById(1)!!.type)
        assertEquals("ai_json", harness.store.getMessageById(2)!!.type)
        assertEquals(ReplyTurnStatus.SUCCEEDED, harness.store.getTurn("private:s1:1")!!.status)
    }

    @Test
    fun sessionGenerationGuard_cancelDuringModelCallPreventsReplyWrite() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.aiEngine.suspendBeforeCompleteMillis = 3_000
        val engine = harness.engine()
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { deferred.complete(it) })!!
        withTimeoutOrNull(10_000L) {
            while (harness.aiEngine.requests.isEmpty()) delay(10)
        }
        engine.invalidateSession("s1") // 模型在飞时仅失效时间线（不取消 job，oracle :1592 复核）
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == false)
        assertTrue(await(job))
        // 不写入新时间线：用户消息保持原样（不误标失败）、无 AI 回复、租约释放回 pending 供恢复补答。
        assertEquals("text", harness.store.getMessageById(1)!!.type)
        assertNull(harness.store.getMessageById(2))
        assertEquals(ReplyTurnStatus.PENDING, harness.store.getTurn("private:s1:1")!!.status)
        assertTrue(harness.replyStored().isEmpty())
    }
}
