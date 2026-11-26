package com.rhodes.privatechat.shared.domain.automation

import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验收 #7：记忆维护锁 + 重建保留旧摘要 + 5 类触发点等价入口。
 * 对照 ChatViewModel.kt:2216-2267 / GroupChatViewModel.kt:484-535 / MainViewModel.kt:632-639。
 */
class ContextMaintenanceLockTest {

    @Test
    fun sessionLockHeldReturnsRetryThenSucceedsAfterUnlock() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val session = harness.seedPrivateSession()
        harness.settings.putBoolean(ContextMaintenanceKeys.rebuildPendingKey(session.id), true)

        val lock = ContextMaintenanceLocks.forSession(session.id)
        assertTrue(lock.tryLock(), "测试手动持有会话锁")
        try {
            assertEquals(DelayedTaskOutcome.RETRY, harness.task.maintainNow(session.id, false))
            assertTrue(harness.events.any { it is ContextMaintenanceEvent.LockHeld })
            assertEquals(0, harness.port.summaryCalls.size)
        } finally {
            lock.unlock()
        }
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.maintainNow(session.id, false))
        assertTrue(harness.settings.getRollingSummaryStatus(session.id).state != "failed")
    }

    @Test
    fun deletedSessionIsDoneWithoutTouchingPipeline() = runBlocking {
        val harness = ContextMaintenanceHarness()
        assertEquals(DelayedTaskOutcome.DONE, harness.task.maintainNow("s-missing", false))
        assertEquals(0, harness.port.summaryCalls.size)
        assertTrue(harness.events.any { it is ContextMaintenanceEvent.Skipped })
    }

    @Test
    fun rebuildPreservesOldSummaryResetsCursorsAndIngestsBatches() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val session = harness.seedPrivateSession()
        val now = harness.clock.now
        harness.port.summaries[session.id] = "旧摘要（重建成功前必须可见）"
        harness.settings.putBoolean(ContextMaintenanceKeys.rebuildPendingKey(session.id), true)
        harness.settings.putString(ContextMaintenanceKeys.rebuildChangedKey(session.id), "42")
        harness.settings.putMemoryExtractionCursor(session.id, 7L)
        harness.settings.putSummaryCursor(session.id, 9L)
        repeat(30) { index ->
            harness.port.seedMessage(
                session.id,
                com.rhodes.privatechat.shared.model.ChatMessage(
                    id = index + 1L,
                    sessionId = session.id,
                    senderName = "银灰",
                    content = "消息$index",
                    timestamp = 1_000L + index,
                    isMe = false,
                ),
            )
        }
        // 被排除的消息类型不进入抽取批次，但游标仍推进到页最大 id。
        harness.port.seedTypedMessage(session.id, 31L, "system", 2_000L)
        harness.port.seedTypedMessage(session.id, 32L, "send_failed", 2_001L)
        harness.port.seedTypedMessage(session.id, 33L, "gift_reply_failed", 2_002L)
        harness.port.seedTypedMessage(session.id, 34L, "text", 2_003L)

        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.maintainNow(session.id, false))

        // 保留旧摘要：重建前写入 previous 键；重建成功后摘要被替换。
        assertEquals("旧摘要（重建成功前必须可见）", harness.settings.getString(ContextMaintenanceKeys.rebuildPreviousSummaryKey(session.id)))
        assertEquals("重建后的摘要", harness.port.summaries[session.id] ?: "<null>")
        assertTrue(harness.events.any { it is ContextMaintenanceEvent.OldSummaryPreserved })
        // 纪元推进 + 游标重置后按批推进。
        assertEquals(34L, harness.settings.getMemoryExtractionCursor(session.id), "游标推进到页最大 id")
        assertEquals(0L, harness.settings.getSummaryCursor(session.id), "摘要游标已重置")
        assertEquals(listOf(session.id to 42L), harness.port.invalidated, "invalidateChangedMessage 已调用")
        // 批次 1 = 1..30；批次 2 只含 id34（31/32/33 被类型过滤）。
        assertEquals((1L..30L).toList(), harness.port.ingestCalls[0].second)
        assertEquals(listOf(34L), harness.port.ingestCalls[1].second)
        assertTrue(harness.events.any { it is ContextMaintenanceEvent.MemoryIngested && it.batches == 2 })
        assertEquals(1, harness.port.summaryCalls.size)
        assertEquals(false, harness.settings.getBoolean(ContextMaintenanceKeys.rebuildPendingKey(session.id)))
        assertTrue(harness.events.any { it is ContextMaintenanceEvent.Completed })
        // 重复触发幂等：pending 已清，第二次不再跑摘要/抽取。
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.maintainNow(session.id, false))
        assertEquals(1, harness.port.summaryCalls.size, "重复触发不重复生成摘要")
        assertEquals(2, harness.port.ingestCalls.size, "重复触发不重复抽取")
    }

    @Test
    fun rebuildSummaryFailureKeepsOldSummaryAndPendingFlag() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val session = harness.seedPrivateSession()
        harness.port.summaries[session.id] = "旧摘要"
        harness.port.summarySucceeds = false
        harness.settings.putBoolean(ContextMaintenanceKeys.rebuildPendingKey(session.id), true)

        assertEquals(DelayedTaskOutcome.RETRY, harness.task.maintainNow(session.id, false))
        assertEquals("旧摘要", harness.port.summaries[session.id] ?: "<null>", "重建失败不得擦除旧摘要")
        assertEquals("旧摘要", harness.settings.getString(ContextMaintenanceKeys.rebuildPreviousSummaryKey(session.id)))
        assertEquals(true, harness.settings.getBoolean(ContextMaintenanceKeys.rebuildPendingKey(session.id)), "失败保留 pending 继续补")
        assertTrue(harness.events.any { it is ContextMaintenanceEvent.Deferred })
    }

    @Test
    fun failedSummarySkipsWhenAutoRetryDisabledAndDefersDuringBackoff() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val session = harness.seedPrivateSession()
        harness.settings.putRollingSummaryStatus(
            session.id,
            SettingsRepository.RollingSummaryStatus(state = "failed", failureCount = 3, nextRetryAt = harness.clock.now + 60_000L),
        )
        harness.settings.summaryAutoRetryEnabled = false
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.maintainNow(session.id, false))
        assertEquals(0, harness.port.summaryCalls.size, "failed 且未开自动重试 → 跳过（§C-5）")

        harness.settings.summaryAutoRetryEnabled = true
        assertEquals(DelayedTaskOutcome.RETRY, harness.task.maintainNow(session.id, false), "未到 nextRetryAt → retry")
        assertEquals(0, harness.port.summaryCalls.size)
        assertTrue(
            harness.events.any {
                it is ContextMaintenanceEvent.Deferred && it.reason == ContextMaintenanceDeferReason.SUMMARY_BACKOFF
            },
        )

        harness.clock.now += 120_000L
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.maintainNow(session.id, false))
        assertEquals(1, harness.port.summaryCalls.size, "到点后重跑摘要")
        assertEquals("idle", harness.settings.getRollingSummaryStatus(session.id).state)
    }

    @Test
    fun extractionFailureDefersRetry() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val session = harness.seedPrivateSession()
        harness.port.ingestSucceeds = false
        harness.settings.putBoolean(ContextMaintenanceKeys.rebuildPendingKey(session.id), true)
        repeat(5) { index ->
            harness.port.seedMessage(
                session.id,
                com.rhodes.privatechat.shared.model.ChatMessage(
                    id = index + 1L, sessionId = session.id, senderName = "银灰",
                    content = "消息", timestamp = 1_000L + index, isMe = false,
                ),
            )
        }
        assertEquals(DelayedTaskOutcome.RETRY, harness.task.maintainNow(session.id, false))
        assertTrue(
            harness.events.any {
                it is ContextMaintenanceEvent.Deferred && it.reason == ContextMaintenanceDeferReason.EXTRACTION_PENDING
            },
        )
        assertEquals(0, harness.port.summaryCalls.size)
    }

    @Test
    fun retryPendingSourcesRunsAsPartOfMaintenance() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val session = harness.seedPrivateSession()
        harness.port.pendingSourcesToRetry = 3
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.maintainNow(session.id, false))
        assertEquals(1, harness.port.pendingSourceRetryCalls)
        assertTrue(harness.events.any { it is ContextMaintenanceEvent.PendingSourcesRetried && it.count == 3 })
    }

    @Test
    fun memoryV2DisabledSkipsPendingSourceRetries() = runBlocking {
        val harness = ContextMaintenanceHarness()
        harness.seedPrivateSession()
        harness.settings.memoryV2Enabled = false
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.maintainNow("s-1", false))
        assertEquals(0, harness.port.pendingSourceRetryCalls)
    }

    @Test
    fun recoveryAndNetworkGuardsRetryWithoutTouchingStore() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val session = harness.seedPrivateSession()
        harness.recoveryActive = true
        assertEquals(DelayedTaskOutcome.RETRY, harness.task.maintainNow(session.id, false))
        assertTrue(
            harness.events.any {
                it is ContextMaintenanceEvent.Deferred && it.reason == ContextMaintenanceDeferReason.RECOVERY_ACTIVE
            },
        )
        harness.recoveryActive = false
        harness.networkAvailable = false
        assertEquals(DelayedTaskOutcome.RETRY, harness.task.maintainNow(session.id, false))
        assertTrue(
            harness.events.any {
                it is ContextMaintenanceEvent.Deferred && it.reason == ContextMaintenanceDeferReason.NETWORK_UNAVAILABLE
            },
        )
        harness.networkAvailable = true
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.maintainNow(session.id, false))
    }

    // === 5 类触发点等价入口的接线断言 ===

    @Test
    fun triggerPrivateContextChangedMarksPendingPreservesSummaryAndArmsNow() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val session = harness.seedPrivateSession()
        harness.port.summaries[session.id] = "旧摘要"
        val key = requireNotNull(harness.task.onPrivateContextChanged(harness.layer, session.id, changedMessageId = 42L))
        assertEquals("context-maintenance-${session.id}", key)
        assertEquals(true, harness.settings.getBoolean(ContextMaintenanceKeys.rebuildPendingKey(session.id)))
        assertEquals("42", harness.settings.getString(ContextMaintenanceKeys.rebuildChangedKey(session.id)))
        assertEquals("旧摘要", harness.settings.getString(ContextMaintenanceKeys.rebuildPreviousSummaryKey(session.id)))
        assertEquals(harness.clock.now, harness.primary.scheduled.getValue(key).first, "上下文变更 dueAt=now")
        assertEquals("false", harness.primary.scheduled.getValue(key).second[ContextMaintenanceKeys.PAYLOAD_GROUP] ?: "<null>")
    }

    @Test
    fun triggerGroupContextChangedCarriesGroupFlag() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val group = harness.seedGroupSession()
        val key = requireNotNull(harness.task.onGroupContextChanged(harness.layer, group.id))
        assertEquals("context-maintenance-g-1", key)
        assertEquals("true", harness.primary.scheduled.getValue(key).second[ContextMaintenanceKeys.PAYLOAD_GROUP] ?: "<null>")
        assertEquals(harness.clock.now, harness.primary.scheduled.getValue(key).first)
    }

    @Test
    fun triggerForegroundInlineMaintenanceArmsNowAndCanRunDirectly() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val session = harness.seedPrivateSession()
        val key = requireNotNull(harness.task.onForegroundReplyCompleted(harness.layer, session.id, group = false))
        assertEquals("context-maintenance-s-1", key)
        assertEquals(harness.clock.now, harness.primary.scheduled.getValue(key).first)
        // 前台内联形态：可直接 await 同一执行体（与层共用锁与语义）。
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.maintainNow(session.id, false))
    }

    @Test
    fun executeMapsMemberPrefixKeyToSessionAndGroupPayload() = runBlocking {
        val harness = ContextMaintenanceHarness()
        val session = harness.seedPrivateSession()
        val outcome = harness.task.execute(
            DelayedTaskDue(
                key = "context-maintenance-${session.id}",
                dueAt = harness.clock.now,
                payload = mapOf(ContextMaintenanceKeys.PAYLOAD_GROUP to "true"),
            ),
        )
        assertEquals(DelayedTaskOutcome.SUCCESS, outcome)
        assertTrue(harness.events.any { it is ContextMaintenanceEvent.Completed && it.group })
        assertEquals(DelayedTaskOutcome.DONE, harness.task.execute(DelayedTaskDue(key = "unknown-key", dueAt = harness.clock.now)))
    }
}
