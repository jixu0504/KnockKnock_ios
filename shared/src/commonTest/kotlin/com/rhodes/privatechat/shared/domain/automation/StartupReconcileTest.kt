package com.rhodes.privatechat.shared.domain.automation

import com.rhodes.privatechat.shared.model.ReplyTurn
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收 #3：启动 reconcile。
 * moment 侧：预置 pending turns → 每条按 `nextAttemptAt` 重入队（过期钳 now；超 24h 窗口丢弃）。
 * context 侧：`context_rebuild_pending_` 置位会话入队（dueAt=now）；failed 且自动重试开启按 nextRetryAt；
 * failed 且 `summaryAutoRetryEnabled=false` 的会话不得入队（对照 MainViewModel.kt:632-639 分支）。
 */
class StartupReconcileTest {

    private fun turnFixture(
        id: String,
        momentId: Long = 7L,
        nextAttemptAt: Long,
        status: String = "pending",
        attemptCount: Int = 0,
    ): ReplyTurn = ReplyTurn(
        id = id,
        sessionId = "moment:$momentId",
        surface = "moment_comment",
        triggerKind = "moment_comment",
        sourceMessageId = null,
        autoPlanToken = JsonMomentReplyPayloadCodec().encode(
            com.rhodes.privatechat.shared.model.MomentReplyTask(
                momentId = momentId, operatorId = "op-1", speakerName = "银灰",
                userContent = "内容", parentCommentId = 0L, userName = "博士", sourceCommentId = 11L,
            ),
        ),
        mode = "online",
        status = status,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        leaseToken = "",
        leaseUntil = 0L,
        responseMessageId = null,
        lastError = "",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        completedAt = 0L,
    )

    @Test
    fun momentReconcileRearmsPendingTurnsByNextAttemptAt() = runBlocking {
        val harness = MomentReplyHarness()
        val past = turnFixture("moment-past", nextAttemptAt = harness.clock.now - 5_000L, attemptCount = 2)
        val future = turnFixture("moment-future", nextAttemptAt = harness.clock.now + 5_000L)
        harness.port.seedTurn(past)
        harness.port.seedTurn(future)
        harness.port.seedTurn(turnFixture("moment-done", nextAttemptAt = 1L, status = "succeeded"))

        val keys = harness.task.reconcile(harness.layer)

        assertEquals(listOf("moment-reply-moment-past", "moment-reply-moment-future"), keys)
        assertEquals(harness.clock.now, harness.primary.scheduled["moment-reply-moment-past"]?.first, "过期任务钳 now 立即补算")
        assertEquals(harness.clock.now + 5_000L, harness.primary.scheduled["moment-reply-moment-future"]?.first)
        assertEquals(2, harness.layer.task("moment-reply-moment-past")?.attemptCount ?: -1, "层保留持久化的 attemptCount")
        assertNull(harness.layer.task("moment-reply-moment-done"))
        assertTrue(harness.events.any { it is MomentReplyEvent.Reconciled })
    }

    @Test
    fun momentReconcileDropsRecordsOutsideTwentyFourHourWindow() = runBlocking {
        val harness = MomentReplyHarness()
        val stale = turnFixture(
            "moment-stale",
            nextAttemptAt = harness.clock.now - 25L * 60L * 60L * 1000L,
        )
        harness.port.seedTurn(stale)
        val keys = harness.task.reconcile(harness.layer)
        assertTrue(keys.isEmpty(), "超 24h 扫描窗口的旧任务丢弃（oracle MainViewModel.kt:140/622）")
        assertNull(harness.layer.task("moment-reply-moment-stale"))
    }

    @Test
    fun contextReconcileSplitsRebuildAutoRetryAndDisabledSessions() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val rebuildSession = harness.seedPrivateSession("s-rebuild")
        val autoRetrySession = harness.seedPrivateSession("s-auto", operatorId = "op-2")
        val disabledSession = harness.seedPrivateSession("s-disabled", operatorId = "op-3")
        val idleSession = harness.seedPrivateSession("s-idle", operatorId = "op-4")
        val now = harness.clock.now
        harness.settings.putBoolean(ContextMaintenanceKeys.rebuildPendingKey(rebuildSession.id), true)
        harness.settings.putRollingSummaryStatus(
            autoRetrySession.id,
            com.rhodes.privatechat.shared.settings.SettingsRepository.RollingSummaryStatus(
                state = "failed",
                nextRetryAt = now + 120_000L,
                failureCount = 2,
            ),
        )
        harness.settings.putRollingSummaryStatus(
            disabledSession.id,
            com.rhodes.privatechat.shared.settings.SettingsRepository.RollingSummaryStatus(
                state = "failed",
                nextRetryAt = now + 60_000L,
                failureCount = 1,
            ),
        )
        harness.settings.summaryAutoRetryEnabled = false

        val records = harness.task.startupReconcileRecords(
            sessions = listOf(rebuildSession, autoRetrySession, disabledSession, idleSession),
            now = now,
        )
        assertEquals(listOf("context-maintenance-s-rebuild"), records.map { it.key }, "只入队 rebuild pending（自动重试关闭）")
        assertEquals(now, records.single().dueAt)

        harness.settings.summaryAutoRetryEnabled = true
        val records2 = harness.task.startupReconcileRecords(
            sessions = listOf(rebuildSession, autoRetrySession, disabledSession, idleSession),
            now = now,
        )
        assertEquals(
            listOf("context-maintenance-s-rebuild", "context-maintenance-s-auto", "context-maintenance-s-disabled"),
            records2.map { it.key },
        )
        assertEquals(now + 120_000L, records2.first { it.key.endsWith("s-auto") }.dueAt, "failed+自动重试按 nextRetryAt")
        assertEquals(now + 60_000L, records2.first { it.key.endsWith("s-disabled") }.dueAt)

        val keys = harness.task.reconcile(harness.layer, listOf(rebuildSession, autoRetrySession))
        assertEquals(listOf("context-maintenance-s-rebuild", "context-maintenance-s-auto"), keys)
        assertTrue("context-maintenance-s-rebuild" in harness.primary.scheduled)
    }

    @Test
    fun groupSessionReconcileCarriesGroupPayload() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val group = harness.seedGroupSession("g-1")
        harness.settings.putBoolean(ContextMaintenanceKeys.rebuildPendingKey(group.id), true)
        val records = harness.task.startupReconcileRecords(listOf(group), harness.clock.now)
        assertEquals("true", records.single().payload[ContextMaintenanceKeys.PAYLOAD_GROUP] ?: "<null>", "群聊分支 payload group=true（oracle :20,37-38）")
        assertEquals("g-1", records.single().payload[ContextMaintenanceKeys.PAYLOAD_SESSION] ?: "<null>")
    }
}
