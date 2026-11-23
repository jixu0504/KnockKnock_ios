package com.rhodes.privatechat.shared.domain.automation

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收 #5：退避序列 golden。
 *  - moment：失败后 nextAttemptAt 增量 1/2/4/8 min，第 4 次 claim 失败（attemptCount≥4）→ status=failed
 *    （oracle MainViewModel.kt:2907-2912）；
 *  - 摘要：retryAt = 60s << (failures-1) 上限 32min（oracle ChatViewModel.kt:637 `coerceAtMost(5)`；
 *    lead 裁决 oracle 优先、规格卡已修正），
 *    `summaryAutoRetryEnabled=false` 时只写状态不入队。
 */
class BackoffTest {

    @Test
    fun momentFailureBackoffGoldenOneTwoFourMinutesThenTerminal() = runBlocking {
        val harness = MomentReplyHarness(scripts = listOf("", ""))
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)

        val expectedDelays = listOf(60_000L, 120_000L, 240_000L)
        for ((index, expected) in expectedDelays.withIndex()) {
            assertEquals(DelayedTaskOutcome.RETRY, harness.task.executeTurn(turn.id), "第 ${index + 1} 次失败 → retry")
            val released = harness.port.released.last()
            assertEquals(expected, released.second - harness.clock.now, "第 ${index + 1} 次失败退避 = ${expected / 60_000}min")
            harness.clock.now = released.second
        }
        // 第 4 次执行失败：claim 后 attemptCount=4 → fail 终态（oracle `attemptCount >= 4`）。
        assertEquals(DelayedTaskOutcome.DONE, harness.task.executeTurn(turn.id))
        assertEquals("failed", harness.port.turnSnapshot(turn.id)?.status ?: "<null>")
        assertEquals(3, harness.port.released.size, "前 3 次 release，第 4 次直接 fail")
        assertEquals(1, harness.port.failed.size)
        assertTrue(harness.events.any { it is MomentReplyEvent.TaskFailed && it.terminal })
        assertTrue(harness.notifications().isEmpty())
    }

    @Test
    fun momentBackoffFormulaCoversEightMinuteBranch() {
        assertEquals(60_000L, MomentReplyContract.backoffDelayMs(1))
        assertEquals(120_000L, MomentReplyContract.backoffDelayMs(2))
        assertEquals(240_000L, MomentReplyContract.backoffDelayMs(3))
        assertEquals(480_000L, MomentReplyContract.backoffDelayMs(4), "60s<<3 = 8min（规格卡 §B-10 序列末项）")
        assertEquals(480_000L, MomentReplyContract.backoffDelayMs(9), "超过上限按 8min 封顶")
    }

    @Test
    fun summaryRetryBackoffGoldenCappedAtThirtyTwoMinutes() {
        val now = 1_750_000_000_000L
        val expectedMinutes = listOf(1L, 2L, 4L, 8L, 16L, 32L, 32L, 32L)
        expectedMinutes.forEachIndexed { index, minutes ->
            val failures = index + 1
            assertEquals(
                now + minutes * 60_000L,
                ContextMaintenancePolicy.summaryRetryAt(now, failures),
                "第 $failures 次失败退避 = ${minutes}min（上限 32min，oracle coerceAtMost(5)）",
            )
        }
    }

    @Test
    fun summaryFailureArmingGoldenAndDisabledAutoRetrySkipsEnqueue() = runBlocking {
        val harness = ContextMaintenanceHarness()
        for (failures in listOf(1, 4, 8)) {
            val sessionId = "s-$failures"
            val key = requireNotNull(
                harness.task.onSummaryFailure(
                    layer = harness.layer,
                    sessionId = sessionId,
                    group = false,
                    failures = failures,
                    reason = "Timeout",
                ),
            )
            assertEquals("context-maintenance-$sessionId", key)
            val scheduledDueAt = harness.primary.scheduled.getValue(key).first
            val expected = harness.clock.now + ContextMaintenancePolicy.summaryRetryAt(0L, failures)
            assertEquals(expected, scheduledDueAt, "failures=$failures 的入队时刻")
            val status = harness.settings.getRollingSummaryStatus(sessionId)
            assertEquals("failed", status.state)
            assertEquals(failures, status.failureCount)
            assertEquals(expected, status.nextRetryAt)
            assertEquals("Timeout", status.failureCode)
        }

        harness.settings.summaryAutoRetryEnabled = false
        val disabled = harness.task.onSummaryFailure(harness.layer, "s-disabled", false, 1, "Timeout")
        assertNull(disabled, "自动重试关闭 → 不入队")
        assertTrue("context-maintenance-s-disabled" !in harness.primary.scheduled)
        assertTrue(harness.events.any { it is ContextMaintenanceEvent.Skipped })
        assertEquals(0L, harness.settings.getRollingSummaryStatus("s-disabled").nextRetryAt)
    }

    @Test
    fun summaryBackoffPolicyFlags() {
        assertTrue(ContextMaintenancePolicy.shouldRetrySummary("failed", true))
        assertEquals(false, ContextMaintenancePolicy.shouldRetrySummary("failed", false))
        assertTrue(ContextMaintenancePolicy.shouldSkipSummary("failed", false))
        assertEquals(false, ContextMaintenancePolicy.shouldSkipSummary("idle", false))
        assertTrue(ContextMaintenancePolicy.summaryInBackoff("failed", true, 1_000L, 2_000L))
        assertEquals(false, ContextMaintenancePolicy.summaryInBackoff("failed", true, 2_000L, 2_000L))
        assertEquals(false, ContextMaintenancePolicy.summaryInBackoff("failed", false, 1_000L, 2_000L))
    }
}
