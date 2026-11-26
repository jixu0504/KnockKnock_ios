package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.domain.chat.TestChatClock
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自动互动计划状态机（验收 #6）：ensurePlan 幂等/claim 前置条件（pending+到期）/lease 5min/
 * isCurrentClaim/scheduleNext 轮次上限/失败 releaseClaim 保一轮/releaseClaimForRetry 60s/MIN_AUTO_LOOP_DELAY 1s 下限。
 * oracle：automation/GroupAutoChatScheduler.kt:30-121 纯逻辑部分（Worker/WorkManager 部分留 Android 侧）。
 */
class GroupAutoChatPlanTest {

    private class FakePlanStore : GroupAutoChatPlanStore {
        val plans = mutableMapOf<String, SettingsRepository.GroupAutoChatPlan>()
        val complete = mutableMapOf<String, Boolean>()
        var autoEnabled = true
        var groupAuto = mutableSetOf("g1")
        var minSeconds = 10
        var maxSeconds = 30
        var maxRounds = 5

        override fun isAutoEnabled() = autoEnabled
        override fun isGroupAutoEnabled(groupId: String) = groupId in groupAuto
        override fun isComplete(groupId: String) = complete[groupId] ?: false
        override fun getPlan(groupId: String) = plans[groupId] ?: SettingsRepository.GroupAutoChatPlan()
        override fun putPlan(groupId: String, plan: SettingsRepository.GroupAutoChatPlan) {
            plans[groupId] = plan
        }
        override fun clearPlan(groupId: String) {
            plans.remove(groupId)
        }
        override fun putComplete(groupId: String, value: Boolean) {
            complete[groupId] = value
        }
        override fun minIntervalSeconds() = minSeconds
        override fun maxIntervalSeconds() = maxSeconds
        override fun maxRounds() = maxRounds
    }

    private fun setup(): Triple<FakePlanStore, TestChatClock, DefaultAutoChatPlanPort> {
        val store = FakePlanStore()
        val clock = TestChatClock()
        return Triple(store, clock, DefaultAutoChatPlanPort(store, clock))
    }

    @Test
    fun ensurePlanCreatesPendingPlanInsideConfiguredInterval() = runBlocking {
        val (_, clock, port) = setup()
        val plan = assertNotNull(port.ensurePlan("g1"))
        assertEquals("pending", plan.state)
        assertEquals(1, plan.round)
        assertEquals(1L, plan.revision)
        assertTrue(plan.dueAt in (clock.now + 10_000L)..(clock.now + 30_000L))
        assertTrue(plan.token.isNotBlank())
    }

    @Test
    fun ensurePlanIsIdempotentForPendingAndClaimedPlans() = runBlocking {
        val (_, clock, port) = setup()
        val first = assertNotNull(port.ensurePlan("g1"))
        val second = assertNotNull(port.ensurePlan("g1"))
        assertEquals(first.token, second.token)
        assertEquals(first.dueAt, second.dueAt)

        clock.now = first.dueAt
        assertNotNull(port.claim("g1", first.token, first.revision))
        val third = assertNotNull(port.ensurePlan("g1"))
        assertEquals(first.token, third.token)
        assertEquals("claimed", third.state)
    }

    @Test
    fun ensurePlanRecoversExpiredClaimWithNewRevision() = runBlocking {
        val (_, clock, port) = setup()
        val first = assertNotNull(port.ensurePlan("g1"))
        clock.now = first.dueAt
        assertNotNull(port.claim("g1", first.token, first.revision))
        // 租约过期（>5min）后重新安排下一轮 plan。
        clock.now += GroupStageBudget.AUTO_CLAIM_LEASE_MS + 1L
        val recovered = assertNotNull(port.ensurePlan("g1"))
        assertEquals("pending", recovered.state)
        assertEquals(2L, recovered.revision)
    }

    @Test
    fun claimRequiresPendingNotBeforeDueAndMatchingToken() = runBlocking {
        val (_, clock, port) = setup()
        val plan = assertNotNull(port.ensurePlan("g1"))
        assertNull(port.claim("g1", plan.token, plan.revision), "未到期不可 claim")
        assertNull(port.claim("g1", "wrong", plan.revision), "token 不符")
        assertNull(port.claim("g1", plan.token, plan.revision + 1), "revision 不符")
        clock.now = plan.dueAt
        val claimed = assertNotNull(port.claim("g1", plan.token, plan.revision))
        assertEquals(plan.token, claimed.token)
        assertNull(port.claim("g1", plan.token, plan.revision), "同 token 二次 claim 不允许")
    }

    @Test
    fun isCurrentClaimExpiresWithLease() = runBlocking {
        val (store, clock, port) = setup()
        val plan = assertNotNull(port.ensurePlan("g1"))
        clock.now = plan.dueAt
        port.claim("g1", plan.token, plan.revision)
        assertTrue(port.isCurrentClaim("g1", plan.token, plan.revision))
        val stored = store.plans["g1"]!!
        assertEquals(clock.now + GroupStageBudget.AUTO_CLAIM_LEASE_MS, stored.leaseExpiresAt)
        clock.now = clock.now + GroupStageBudget.AUTO_CLAIM_LEASE_MS + 1L
        assertFalse(port.isCurrentClaim("g1", plan.token, plan.revision))
    }

    @Test
    fun scheduleNextAdvancesRoundAndRevisionWhenStillCurrentClaim() = runBlocking {
        val (store, clock, port) = setup()
        val plan = assertNotNull(port.ensurePlan("g1"))
        clock.now = plan.dueAt
        port.claim("g1", plan.token, plan.revision)
        val next = assertNotNull(port.scheduleNext("g1", plan.round, plan.token, plan.revision))
        assertEquals("pending", next.state)
        assertEquals(2, next.round)
        assertEquals(plan.revision + 1, next.revision)
        assertTrue(next.token != plan.token)
        assertEquals(next, store.plans["g1"])
    }

    @Test
    fun scheduleNextRejectsStaleClaim() = runBlocking {
        val (_, clock, port) = setup()
        val plan = assertNotNull(port.ensurePlan("g1"))
        clock.now = plan.dueAt
        port.claim("g1", plan.token, plan.revision)
        assertNull(port.scheduleNext("g1", plan.round, "stale", plan.revision))
        assertNull(port.scheduleNext("g1", plan.round, plan.token, plan.revision + 9))
    }

    @Test
    fun roundLimitMarksComplete() = runBlocking {
        val (store, clock, port) = setup()
        store.maxRounds = 2
        val plan = assertNotNull(port.ensurePlan("g1"))
        clock.now = plan.dueAt
        port.claim("g1", plan.token, plan.revision)
        val next = assertNotNull(port.scheduleNext("g1", plan.round, plan.token, plan.revision))
        clock.now = next.dueAt
        port.claim("g1", next.token, next.revision)
        assertNull(port.scheduleNext("g1", next.round, next.token, next.revision), "达上限不再安排")
        assertTrue(store.isComplete("g1"))
        assertNull(port.ensurePlan("g1"))
    }

    @Test
    fun releaseClaimKeepsSameRoundForRetry() = runBlocking {
        val (store, clock, port) = setup()
        val plan = assertNotNull(port.ensurePlan("g1"))
        clock.now = plan.dueAt
        port.claim("g1", plan.token, plan.revision)
        // 失败自动轮次：completedRound-1 = 0 → 重排第 1 轮（不推进轮次）。
        port.releaseClaim("g1", plan.round - 1, plan.token, plan.revision)
        val pending = store.plans["g1"]!!
        assertEquals(1, pending.round)
        assertEquals("pending", pending.state)
    }

    @Test
    fun releaseClaimForRetryReschedulesSixtySeconds() = runBlocking {
        val (store, clock, port) = setup()
        val plan = assertNotNull(port.ensurePlan("g1"))
        clock.now = plan.dueAt
        port.claim("g1", plan.token, plan.revision)
        val pending = assertNotNull(port.releaseClaimForRetry("g1", plan.token, plan.revision))
        assertEquals("pending", pending.state)
        assertEquals(clock.now + 60_000L, pending.dueAt)
        assertEquals(0L, pending.claimedAt)
        assertEquals(0L, pending.leaseExpiresAt)
        assertEquals(pending, store.plans["g1"])
        assertNull(port.releaseClaimForRetry("g1", "stale", plan.revision), "非当前 claim 不可重试排期")
    }

    @Test
    fun cancelClearsPlanAndDisabledGroupIgnoresEverything() = runBlocking {
        val (store, clock, port) = setup()
        val plan = assertNotNull(port.ensurePlan("g1"))
        port.cancel("g1")
        assertNull(store.plans["g1"])

        store.groupAuto.remove("g1")
        assertNull(port.ensurePlan("g1"))
        clock.now = plan.dueAt
        assertNull(port.claim("g1", plan.token, plan.revision))
        assertFalse(port.isCurrentClaim("g1", plan.token, plan.revision))

        store.groupAuto.add("g1")
        store.autoEnabled = false
        assertNull(port.ensurePlan("g1"))
    }

    @Test
    fun reconcileOnlyRebuildsEnabledGroups() = runBlocking {
        val (store, _, port) = setup()
        store.groupAuto.add("g2")
        val plans = port.reconcile(listOf("g1", "g2", "g3"))
        assertEquals(2, plans.size)
        assertNotNull(store.plans["g1"])
        assertNotNull(store.plans["g2"])
        assertNull(store.plans["g3"])

        store.autoEnabled = false
        assertTrue(port.reconcile(listOf("g1")).isEmpty())
    }

    @Test
    fun minAutoLoopDelayIsOneSecond() {
        assertEquals(1_000L, GroupStageBudget.MIN_AUTO_LOOP_DELAY_MS)
        assertEquals(5 * 60_000L, GroupStageBudget.AUTO_CLAIM_LEASE_MS)
        assertEquals(60_000L, GroupStageBudget.AUTO_RETRY_DELAY_MS)
    }
}
