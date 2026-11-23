package com.rhodes.privatechat.shared.memory

import com.rhodes.privatechat.shared.domain.memory.policy.MaintenancePolicy as DomainMaintenancePolicy

/**
 * @deprecated iOS-port T24：维护轮次纯函数已迁至 `shared.domain.memory.policy.MaintenancePolicy`。
 * 薄委托保留给 app/ 未迁移调用点，Android 调用点改造后删除。
 */
@Suppress("DEPRECATION")
@Deprecated("迁移至 com.rhodes.privatechat.shared.domain.memory.policy.MaintenancePolicy")
object MaintenancePolicy {
    fun eligibleSummaryRounds(total: Int, retained: Int): Int =
        DomainMaintenancePolicy.eligibleSummaryRounds(total, retained)
    fun mayAttempt(state: String, nextRetryAt: Long, now: Long): Boolean =
        DomainMaintenancePolicy.mayAttempt(state, nextRetryAt, now)
    fun remainingSummaryRounds(total: Int, retained: Int, processed: Int): Int =
        DomainMaintenancePolicy.remainingSummaryRounds(total, retained, processed)
}
