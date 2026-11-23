package com.rhodes.privatechat.shared.domain.automation

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验收 #6：来源删除与终态守卫（对照 MainViewModel.kt:2877-2889 / MomentReplyWorker.kt:27）。
 * - source/parent/moment/operator 删除 → turn 变 cancelled 且无评论生成；
 * - 已 succeeded/cancelled turn 再触发 → done 无副作用；
 * - 恢复标志置位 / 网络不可用 → retry 不执行；
 * - claim 被占 → retry。
 */
class MomentReplyGuardTest {

    @Test
    fun terminalAndForeignSurfaceTurnsAreDoneWithoutSideEffects() = runBlocking {
        val harness = MomentReplyHarness()
        val payload = harness.seedScenario()
        val succeeded = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
            .copy(id = "moment-succeeded", status = "succeeded")
        val cancelled = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
            .copy(id = "moment-cancelled", status = "cancelled")
        val foreign = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
            .copy(id = "moment-foreign", surface = "private")
        harness.port.seedTurn(succeeded)
        harness.port.seedTurn(cancelled)
        harness.port.seedTurn(foreign)

        assertEquals(DelayedTaskOutcome.DONE, harness.task.executeTurn(succeeded.id))
        assertEquals(DelayedTaskOutcome.DONE, harness.task.executeTurn(cancelled.id))
        assertEquals(DelayedTaskOutcome.DONE, harness.task.executeTurn(foreign.id))
        assertEquals(0, harness.port.claimCount, "守卫命中不得 claim")
        assertEquals(0, harness.port.commentCount())
        assertEquals(0, harness.ai.requests.size)
    }

    @Test
    fun missingTurnIsDoneIdempotently() = runBlocking {
        val harness = MomentReplyHarness()
        assertEquals(DelayedTaskOutcome.DONE, harness.task.executeTurn("moment-not-exist"))
        assertEquals(0, harness.port.claimCount)
    }

    @Test
    fun deletedSourceCommentCancelsTurnWithoutReply() = runBlocking {
        val harness = MomentReplyHarness()
        val payload = harness.seedScenario().copy(sourceCommentId = 999L) // 来源评论不存在
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)

        assertEquals(DelayedTaskOutcome.DONE, harness.task.executeTurn(turn.id))
        assertEquals("cancelled", harness.port.turnSnapshot(turn.id)?.status ?: "<null>")
        assertEquals(0, harness.port.commentCount())
        assertEquals(0, harness.ai.requests.size, "来源删除不得再跑生成")
        assertTrue(harness.events.any { it is MomentReplyEvent.SourceRemoved })
    }

    @Test
    fun deletedParentCommentCancelsTurn() = runBlocking {
        val harness = MomentReplyHarness()
        val payload = harness.seedScenario().copy(parentCommentId = 888L)
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)
        assertEquals(DelayedTaskOutcome.DONE, harness.task.executeTurn(turn.id))
        assertEquals("cancelled", harness.port.turnSnapshot(turn.id)?.status ?: "<null>")
        assertEquals(0, harness.port.commentCount())
    }

    @Test
    fun deletedMomentOrOperatorCancelsTurn() = runBlocking {
        val harness = MomentReplyHarness()
        val payload = harness.seedScenario().copy(operatorId = "op-gone", speakerName = "已删除角色")
        // operator op-gone 不存在（seedScenario 只种了 op-1）。
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)
        assertEquals(DelayedTaskOutcome.DONE, harness.task.executeTurn(turn.id))
        assertEquals("cancelled", harness.port.turnSnapshot(turn.id)?.status ?: "<null>")
        assertEquals(0, harness.port.commentCount())
    }

    @Test
    fun recoveryActiveDefersWithoutClaim() = runBlocking {
        val harness = MomentReplyHarness()
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)
        harness.recoveryActive = true

        assertEquals(DelayedTaskOutcome.RETRY, harness.task.executeTurn(turn.id))
        assertEquals(0, harness.port.claimCount, "恢复中不执行、不 claim（oracle MomentReplyWorker.kt:27）")
        assertEquals("pending", harness.port.turnSnapshot(turn.id)?.status ?: "<null>")
        assertTrue(
            harness.events.any {
                it is MomentReplyEvent.Deferred && it.reason == MomentReplyDeferReason.RECOVERY_ACTIVE
            },
        )
        harness.recoveryActive = false
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.executeTurn(turn.id), "恢复完成后补算")
    }

    @Test
    fun networkUnavailableDefersWithoutClaim() = runBlocking {
        val harness = MomentReplyHarness()
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)
        harness.networkAvailable = false

        assertEquals(DelayedTaskOutcome.RETRY, harness.task.executeTurn(turn.id))
        assertEquals(0, harness.port.claimCount, "网络不可用 → 不执行（CONNECTED 语义事件化）")
        assertTrue(
            harness.events.any {
                it is MomentReplyEvent.Deferred && it.reason == MomentReplyDeferReason.NETWORK_UNAVAILABLE
            },
        )
        harness.networkAvailable = true
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.executeTurn(turn.id))
    }

    @Test
    fun claimHeldByOtherExecutorReturnsRetry() = runBlocking {
        val harness = MomentReplyHarness()
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true).copy(
            status = "running",
            leaseToken = "other-token",
            leaseUntil = harness.clock.now + 300_000L,
            attemptCount = 1,
        )
        harness.port.seedTurn(turn)

        assertEquals(DelayedTaskOutcome.RETRY, harness.task.executeTurn(turn.id))
        assertEquals(0, harness.port.commentCount())
        assertEquals("running", harness.port.turnSnapshot(turn.id)?.status ?: "<null>")
        assertTrue(harness.events.any { it is MomentReplyEvent.ClaimDenied })
    }

    @Test
    fun invalidPayloadFailsIntoRetryPath() = runBlocking {
        val harness = MomentReplyHarness()
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true).copy(autoPlanToken = "not-json")
        harness.port.seedTurn(turn)

        assertEquals(DelayedTaskOutcome.RETRY, harness.task.executeTurn(turn.id))
        assertEquals("pending", harness.port.turnSnapshot(turn.id)?.status ?: "<null>")
        assertEquals(1, harness.port.released.size, "payload 解码失败走失败退避（oracle 通用 catch）")
    }

    @Test
    fun retryFailedMomentTurnsResetsAndRearms() = runBlocking {
        val harness = MomentReplyHarness()
        val payload = harness.seedScenario()
        val failedTurn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
            .copy(id = "moment-failed", status = "failed", attemptCount = 4)
        val succeededTurn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
            .copy(id = "moment-ok", status = "succeeded")
        harness.port.seedTurn(failedTurn)
        harness.port.seedTurn(succeededTurn)

        val keys = harness.task.retryFailed(harness.layer, momentId = 7L)
        assertEquals(listOf("moment-reply-moment-failed"), keys)
        assertEquals("pending", harness.port.turnSnapshot("moment-failed")?.status ?: "<null>")
        assertEquals(0, harness.port.turnSnapshot("moment-failed")?.attemptCount)
        assertEquals(harness.clock.now, harness.primary.scheduled.getValue("moment-reply-moment-failed").first)
    }
}
