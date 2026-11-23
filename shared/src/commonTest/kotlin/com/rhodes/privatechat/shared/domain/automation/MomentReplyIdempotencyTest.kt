package com.rhodes.privatechat.shared.domain.automation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验收 #4：重复触发幂等——同一 `moment-reply-$id` 由两通道（前台 Timer + 后台任务模拟）并发到期，
 * 恰一条评论落库、另一执行体 retry（claim CAS 只放行一个）。
 */
class MomentReplyIdempotencyTest {

    @Test
    fun dualChannelFiresProduceExactlyOneComment() = runBlocking {
        val harness = MomentReplyHarness(
            scripts = listOf("训练场安静得能听见风声。"),
            dualChannel = true,
        )
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)
        val key = "moment-reply-${turn.id}"
        harness.task.arm(harness.layer, turn)
        val timerChannel = harness.primary
        val bgChannel = requireNotNull(harness.fallback)
        assertTrue(key in timerChannel.scheduled)
        assertTrue(key in bgChannel.scheduled, "双通道都 schedule（§A-12 双通道执行）")

        // 两通道同时到期。
        listOf(async { timerChannel.fire(key) }, async { bgChannel.fire(key) }).awaitAll()
        assertTrue(harness.awaitComments(1))
        delay(150) // 给另一执行体机会：若去重失效会出现第二条
        assertEquals(1, harness.port.commentCount(), "同 turn 两通道并发 → 恰一条评论落库")
        assertEquals(1, harness.storedReplies().size)
        assertEquals(1, harness.notifications().size, "恰一条完成通知")
        assertEquals("succeeded", harness.port.turnSnapshot(turn.id)?.status ?: "<null>")
    }

    @Test
    fun concurrentExecuteTurnOnlyOneWinsClaim() = runBlocking {
        val harness = MomentReplyHarness(scripts = listOf("听见风声了。"))
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)

        val results = listOf(
            async { harness.task.executeTurn(turn.id) },
            async { harness.task.executeTurn(turn.id) },
        ).awaitAll()

        assertEquals(1, harness.port.commentCount(), "claim CAS 只放行一个执行体")
        assertEquals(1, results.count { it == DelayedTaskOutcome.SUCCESS })
        assertTrue(
            results.any { it == DelayedTaskOutcome.RETRY || it == DelayedTaskOutcome.DONE },
            "另一执行体必须 retry 或被终态守卫按 done 忽略：$results",
        )
    }

    @Test
    fun repeatedTerminalTriggerIsIdempotentDone() = runBlocking {
        val harness = MomentReplyHarness(scripts = listOf("听见风声了。"))
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.executeTurn(turn.id))
        assertEquals(DelayedTaskOutcome.DONE, harness.task.executeTurn(turn.id), "已终态 → done 无副作用")
        assertEquals(DelayedTaskOutcome.DONE, harness.task.executeTurn(turn.id))
        assertEquals(1, harness.port.commentCount())
    }
}
