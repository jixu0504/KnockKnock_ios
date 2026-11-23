@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.domain.chat.group.GroupChatSemantics
import com.rhodes.privatechat.shared.domain.chat.group.GroupEngineHarness
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T35 批 C 续作 步骤 2：D1/D3 语义对齐冻结测试（T28 §3.2 实测证据的引擎侧固化）。
 *
 * 每个场景都在「引擎默认语义」与「Android oracle 基线语义（semantics 开关）」两态下断言，
 * 证明：
 * 1) 默认值未改变引擎既有行为（既有 61 例 shared 测试继续锁定原契约）；
 * 2) 打开基线开关后，引擎复刻 oracle 的可观测结果（D1 取消：不写 DB、租约保持 running；
 *    D3 全员禁言：回调 true）。
 *
 * D2（撤回）不在此文件：引擎 `cancelBySource + deleteMessage` 与 oracle 的
 * `cancelReplyTurnForSource + deleteMessage` 在**生产 ChatStore**（RepositoryChatStore →
 * MessageRepository.deleteMessage 级联删 reply_turns）上可观测结果相同，差异只在纯内存测试替身；
 * 生产库证据由 app 侧 `ChatEngineFactoryTest` 覆盖。
 */
class ChatEngineSemanticsTest {

    private fun session() = ChatSession(id = "s1", operatorId = "op1", operatorName = "角色")

    private fun await(job: kotlinx.coroutines.Job, timeoutMs: Long = 15_000L) = runBlocking {
        withTimeoutOrNull(timeoutMs) { job.join() } != null
    }

    // === D1 取消 ===

    @Test
    fun cancelDuringSend_engineDefaultMarksUndeliveredAndReleasesTurn() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.aiEngine.suspendBeforeCompleteMillis = 5_000
        val engine = harness.engine()
        val completed = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { completed.complete(it) })!!
        withTimeoutOrNull(10_000L) {
            while (harness.aiEngine.requests.isEmpty() || !engine.loadingSessions.value.contains("s1")) delay(10)
        }
        engine.cancelSessionRequests("s1")
        assertTrue(await(job))
        assertEquals("send_failed", harness.store.getMessageById(1)!!.type)
        assertEquals(ReplyTurnStatus.PENDING, harness.store.getTurn("private:s1:1")!!.status)
        assertTrue(harness.undelivered().any { 1L in it.messageIds })
        assertFalse(engine.loadingSessions.value.contains("s1"))
        assertEquals(false, withTimeoutOrNull(10_000L) { completed.await() })
    }

    @Test
    fun cancelDuringSend_legacySemanticsSkipsDbWritesLikeAndroidBaseline() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.aiEngine.suspendBeforeCompleteMillis = 5_000
        val engine = harness.engine(semantics = ChatEngineSemantics(cancelSkipsDbWrites = true))
        val completed = CompletableDeferred<Boolean>()
        val job = engine.sendMessage("你好", session(), "online", onResponseComplete = { completed.complete(it) })!!
        withTimeoutOrNull(10_000L) {
            while (harness.aiEngine.requests.isEmpty() || !engine.loadingSessions.value.contains("s1")) delay(10)
        }
        engine.cancelSessionRequests("s1")
        assertTrue(await(job))

        // D1 基线：coroutine 已取消 → 用户消息保持 text、租约保持 running（oracle VM:1743-1748 的写入被跳过）。
        val user = assertNotNull(harness.store.getMessageById(1))
        assertEquals("text", user.type)
        assertEquals(ReplyTurnStatus.RUNNING, harness.store.getTurn("private:s1:1")!!.status)
        // 基线不产生「未送达」事件（T28 ChatViewModelCorePathTest 实测无 send_failed）。
        assertTrue(harness.undelivered().isEmpty())
        assertTrue(harness.replyStored().isEmpty())
        // 宿主侧清理仍必须完成（基线回调 false、loading 清空、无 AI 行）。
        assertEquals(false, withTimeoutOrNull(10_000L) { completed.await() })
        assertFalse(engine.loadingSessions.value.contains("s1"))
        assertEquals(0, harness.store.getMessagesSync("s1").count { !it.isMe })
    }

    // === D3 群聊全员禁言 ===

    @Test
    fun allMuted_engineDefaultReportsNoReplyButStoresSystemMessage() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply()))
        val harness = GroupEngineHarness(aiClient = ai)
        harness.seedGroup(mutedMembers = "amiya,blaze")
        val (job, done) = harness.send()
        harness.awaitJob(job)
        assertFalse(done.await())
        val messages = harness.messages()
        assertTrue(messages.any { it.type == "system" && it.content.contains("所有成员已被禁言") })
        assertEquals(ReplyTurnStatus.SUCCEEDED, harness.store.getTurn("group:${GroupEngineHarness.GROUP_ID}:${messages.first { it.isMe }.id}")?.status)
    }

    @Test
    fun allMuted_legacySemanticsReportsStoredLikeAndroidBaseline() = runBlocking {
        val ai = FakeAiEngine(listOf(GroupEngineHarness.taggedReply()))
        val harness = GroupEngineHarness(aiClient = ai, semantics = GroupChatSemantics(allMutedReportsStored = true))
        harness.seedGroup(mutedMembers = "amiya,blaze")
        val (job, done) = harness.send()
        harness.awaitJob(job)
        // D3 基线（GroupChatViewModelCorePathTest#7 实测）：该轮标记为已处理 → 回调 true。
        assertEquals(true, done.await())
        val messages = harness.messages()
        val user = messages.first { it.isMe }
        assertTrue(messages.any { it.type == "system" && it.content.contains("所有成员已被禁言") })
        // 租约/零模型调用与默认语义一致（差异只在回调值）。
        assertEquals(ReplyTurnStatus.SUCCEEDED, harness.store.getTurn("group:${GroupEngineHarness.GROUP_ID}:${user.id}")?.status)
        assertEquals(0, ai.requests.size)
    }
}
