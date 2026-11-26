package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T77 最小切片：整合幂等签名、退避、dead-letter 与日预算。 */
class MemoryConsolidationPolicyTest {

    private val now = 1_800_000_000_000L
    private fun settings() = SettingsRepository(PromptTestSettings())
    private val sigA = MemoryConsolidationPolicy.signature(listOf(Triple(1, 100, 50)), "v3")

    @Test
    fun signatureChangesOnlyWhenCandidatesChange() {
        val base = listOf(Triple(1L, 100L, 50), Triple(2L, 200L, 60))
        assertEquals(
            MemoryConsolidationPolicy.signature(base, "v3"),
            MemoryConsolidationPolicy.signature(base.reversed(), "v3"),
            "签名与顺序无关",
        )
        assertTrue(
            MemoryConsolidationPolicy.signature(base, "v3") !=
                MemoryConsolidationPolicy.signature(base + Triple(3L, 300L, 70), "v3"),
            "新增候选必须改变签名",
        )
        assertTrue(
            MemoryConsolidationPolicy.signature(base, "v3") !=
                MemoryConsolidationPolicy.signature(listOf(Triple(1L, 100L, 50), Triple(2L, 999L, 60)), "v3"),
            "候选更新必须改变签名",
        )
    }

    @Test
    fun successSkipsSameSignatureButNewEvidenceRunsAgain() {
        val done = MemoryConsolidationPolicy.onSuccess(sigA, now)
        assertTrue(MemoryConsolidationPolicy.shouldSkip(done, sigA, now + 86_400_000L))
        assertFalse(MemoryConsolidationPolicy.shouldSkip(done, sigA + "-new", now))
    }

    @Test
    fun failuresBackOffAndDeadLetterAfterThreeAttempts() {
        var state = MemoryConsolidationPolicy.State()
        state = MemoryConsolidationPolicy.onFailure(state, sigA, now, "boom")
        assertEquals("retry_wait", state.status)
        assertTrue(MemoryConsolidationPolicy.shouldSkip(state, sigA, now + 1_000L), "退避未到应跳过")
        assertFalse(MemoryConsolidationPolicy.shouldSkip(state, sigA, now + 10_000L), "退避到期应允许重试")

        state = MemoryConsolidationPolicy.onFailure(state, sigA, now + 10_000L, "boom2")
        state = MemoryConsolidationPolicy.onFailure(state, sigA, now + 70_000L, "boom3")
        assertEquals("failed", state.status)
        assertTrue(MemoryConsolidationPolicy.isDeadLetter(state))
        assertFalse(MemoryConsolidationPolicy.isDeadLetter(MemoryConsolidationPolicy.State()))
        assertEquals(MemoryConsolidationPolicy.MAX_ATTEMPTS, state.attempts)
        assertTrue(MemoryConsolidationPolicy.shouldSkip(state, sigA, now + 10_000_000L), "dead-letter 后同签名不再重试")
        assertFalse(MemoryConsolidationPolicy.shouldSkip(state, "sig-new", now), "新证据应解除 dead-letter")
    }

    @Test
    fun budgetCapsPerOwnerAndPerDevice() {
        val s = settings()
        val day = "2026-11-26"
        s.memoryConsolidationPerOwnerDailyLimit = 2
        assertTrue(MemoryConsolidationPolicy.canSpend(s, "op1", day))
        MemoryConsolidationPolicy.recordSpend(s, "op1", day)
        MemoryConsolidationPolicy.recordSpend(s, "op1", day)
        assertFalse(MemoryConsolidationPolicy.canSpend(s, "op1", day), "每角色预算用尽")
        assertTrue(MemoryConsolidationPolicy.canSpend(s, "op2", day), "其它角色仍有额度")

        s.memoryConsolidationDeviceDailyLimit = 3
        MemoryConsolidationPolicy.recordSpend(s, "op2", day)
        assertFalse(MemoryConsolidationPolicy.canSpend(s, "op3", day), "设备预算用尽")
        assertTrue(MemoryConsolidationPolicy.canSpend(s, "op3", "2026-11-27"), "次日额度重置")
    }

    @Test
    fun stateRoundTripsThroughStorageFormat() {
        val state = MemoryConsolidationPolicy.State(
            status = "retry_wait",
            attempts = 2,
            nextRetryAt = now + 60_000,
            lastSignature = sigA,
            lastRunAt = now,
            lastError = "boom",
        )
        assertEquals(state, MemoryConsolidationPolicy.decode(MemoryConsolidationPolicy.encode(state)))
        assertEquals(MemoryConsolidationPolicy.State(), MemoryConsolidationPolicy.decode("not-json"))
    }
}
