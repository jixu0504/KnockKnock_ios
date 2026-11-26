package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 整合（L1→L2→L3）的幂等、退避、dead-letter 与预算策略（计划书 T77 的最小落地）。
 *
 * 设计取舍：本切片不引入独立 Worker/队列表，而是在现有晋升路径入口做「同一候选集合只跑一次」
 * 与「失败退避/封顶」；真正的独立调度（跨角色轮转、checkpoint、可中断续跑）留给 T77 完整版。
 *
 * 幂等键 = owner + sourceKind + 候选集合签名 + promptVersion。签名变化（有新 L1）即解除
 * dead-letter，允许重新尝试；签名不变则永不重复付费。
 */
object MemoryConsolidationPolicy {

    const val MAX_ATTEMPTS = 3
    private val BACKOFF_MS = longArrayOf(10_000L, 60_000L, 300_000L)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class State(
        val status: String = "idle",      // idle | done | retry_wait | failed
        val attempts: Int = 0,
        val nextRetryAt: Long = 0L,
        val lastSignature: String = "",
        val lastRunAt: Long = 0L,
        val lastError: String = "",
    )

    fun decode(raw: String): State =
        if (raw.isBlank()) State() else runCatching { json.decodeFromString<State>(raw) }.getOrDefault(State())

    fun encode(state: State): String = json.encodeToString(state)

    /** 候选集合签名：id + updatedAt + importance 排序后做 FNV-1a，避免长字符串落库。 */
    fun signature(candidates: List<Triple<Long, Long, Int>>, promptVersion: String): String {
        val payload = candidates.sortedBy { it.first }
            .joinToString(";") { "${it.first}:${it.second}:${it.third}" } + "|" + promptVersion
        var hash = -0x7ee3623b
        payload.forEach { char ->
            hash = hash xor char.code
            hash *= 16777619
        }
        return "v3:" + hash.toUInt().toString(16) + ":" + candidates.size
    }

    /**
     * 是否跳过本次整合：
     * - 退避未到；
     * - 同一签名已成功（无新证据）；
     * - 同一签名已 dead-letter（失败 3 次，需要新证据才重试）。
     */
    fun shouldSkip(state: State, signature: String, now: Long): Boolean = when (state.status) {
        "done" -> state.lastSignature == signature
        "failed" -> state.lastSignature == signature
        "retry_wait" -> state.lastSignature == signature && now < state.nextRetryAt
        else -> false
    }

    fun isDeadLetter(state: State): Boolean = state.status == "failed"

    fun onSuccess(signature: String, now: Long): State = State(
        status = "done",
        attempts = 0,
        nextRetryAt = 0L,
        lastSignature = signature,
        lastRunAt = now,
    )

    fun onFailure(previous: State, signature: String, now: Long, error: String): State {
        val attempts = if (previous.lastSignature == signature) previous.attempts + 1 else 1
        val capped = attempts.coerceAtMost(MAX_ATTEMPTS)
        return State(
            status = if (capped >= MAX_ATTEMPTS) "failed" else "retry_wait",
            attempts = capped,
            nextRetryAt = if (capped >= MAX_ATTEMPTS) 0L else now + BACKOFF_MS[(capped - 1).coerceIn(0, BACKOFF_MS.lastIndex)],
            lastSignature = signature,
            lastRunAt = now,
            lastError = error.take(160),
        )
    }

    // ------------------------------------------------------------------ 预算

    private fun ownerKey(ownerId: String, day: String): String = "memory_consolidation_calls_${ownerId}_$day"
    private fun deviceKey(day: String): String = "memory_consolidation_calls_device_$day"

    /** 一次整合运行按 1 次调用计（内部最多 L2+L3 两次模型请求），预算按运行数封顶。 */
    fun canSpend(settings: SettingsRepository, ownerId: String, day: String): Boolean {
        val ownerUsed = settings.getInt(ownerKey(ownerId, day), 0)
        val deviceUsed = settings.getInt(deviceKey(day), 0)
        return ownerUsed < settings.memoryConsolidationPerOwnerDailyLimit &&
            deviceUsed < settings.memoryConsolidationDeviceDailyLimit
    }

    fun recordSpend(settings: SettingsRepository, ownerId: String, day: String) {
        val ownerK = ownerKey(ownerId, day)
        val deviceK = deviceKey(day)
        settings.putInt(ownerK, settings.getInt(ownerK, 0) + 1)
        settings.putInt(deviceK, settings.getInt(deviceK, 0) + 1)
    }
}
