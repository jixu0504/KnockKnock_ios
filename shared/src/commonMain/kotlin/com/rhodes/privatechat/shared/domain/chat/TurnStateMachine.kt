package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.model.ReplyTurnStatus

/**
 * reply_turns 租约状态机（持久化层）。
 *
 * Android oracle：sq/ReplyTurns.sq:40-62 的 SQL 转移 +
 * ReplyTurnRepository.kt:111-114（retryableTurnIds = 启动 reconcile 原语）。
 *
 * 状态图（oracle 规格卡 §行为契约 头部图）：
 * ```
 * pending ──claim(token,leaseUntil)──▶ running ──complete──▶ succeeded（终态，清租约）
 *    ▲                                   │  ├──fail──────▶ failed（终态，可再被 claim）
 *    │                                   │  └──release───▶ pending（nextAttemptAt=retryAt）
 *    └───────────────────────────────────┘
 * pending / running ──cancel──▶ cancelled（SQL 终止态，无 Kotlin 常量；
 *                               不可再被 claim/恢复/孤儿清理触及，ReplyTurns.sq:94/:99）
 * claim 条件（ReplyTurns.sq:40-46）：status ∈ {pending,failed,running}
 *   ∧ nextAttemptAt <= now ∧ (status != running ∨ leaseUntil <= now)
 * ```
 * 领域层不依赖 BGTask/WorkManager（lead 裁决）：孤儿防护 = 启动 reconcile
 * （retryableTurnIds）+ 前台补算（resumePersistedReply），见 ChatEngine。
 */
class TurnStateMachine(private val store: ChatStore) {

    /** 租约（token + 到期时间）。oracle :1447。 */
    data class Lease(val token: String, val untilMillis: Long)

    /** claim 结果（oracle ChatViewModel.kt:1447-1454）。 */
    sealed interface ClaimResult {
        /** 租约到手，可独占推进本轮。 */
        data class Claimed(val turn: ReplyTurn, val lease: Lease) : ClaimResult

        /** 被他人持有（未到期 running）或已被取消/完成。消息已保存，由恢复补答。 */
        object Denied : ClaimResult
    }

    /**
     * oracle ReplyTurnRepository.claim :36-39 —— SQL 条件见上；成功后读回校验
     * status==running ∧ leaseToken==token。
     */
    suspend fun claim(id: String, token: String, now: Long, leaseUntil: Long): ClaimResult {
        val claimed = store.claimTurn(id, token, now, leaseUntil)
        return if (claimed == null || claimed.status != ReplyTurnStatus.RUNNING || claimed.leaseToken != token) {
            ClaimResult.Denied
        } else {
            ClaimResult.Claimed(claimed, Lease(token, leaseUntil))
        }
    }

    /** oracle ReplyTurnRepository.complete :41-45 —— running+token → succeeded，清租约。 */
    suspend fun complete(id: String, token: String, now: Long): Boolean = store.completeTurn(id, token, now)

    /** oracle ReplyTurnRepository.release :50-52 —— running+token → pending（retryAt=nextAttemptAt）。 */
    suspend fun release(id: String, token: String, retryAt: Long, now: Long, error: String) =
        store.releaseTurn(id, token, retryAt, now, error)

    /** oracle ReplyTurnRepository.fail :54-56 —— running+token → failed（可被 claim 重提）。 */
    suspend fun fail(id: String, token: String, now: Long, error: String) =
        store.failTurn(id, token, now, error)

    /**
     * SQL 终止态 cancelled（ReplyTurns.sq:94）——被合并批次 / 被 supersede / 源消息被撤回。
     * 之后任何 claim/reconcile/孤儿清理都不可再触及该 turn。
     */
    suspend fun cancel(id: String, error: String, now: Long) = store.cancelTurn(id, error, now)

    /** oracle ReplyTurnRepository.cancelBySourceMessage :71 —— 撤回源消息时取消其活跃 turn。 */
    suspend fun cancelBySource(sessionId: String, messageId: Long, error: String = "source_message_recalled") =
        store.cancelTurnBySource(sessionId, messageId, error)

    /**
     * 启动 reconcile 原语（ReplyTurns.sq:106-112）：杀进程遗留的 pending/failed/租约过期 running。
     * iOS 语义（lead 裁决）：应用启动与进入会话时调用，捞出 id 后由前台补算驱动。
     */
    suspend fun retryableIds(since: Long, now: Long, limit: Long = 50L): List<String> =
        store.retryableTurnIds(since, now, limit)

    /**
     * 孤儿清理（ReplyTurnRepository.kt:67-76）：只删「source 已不存在且 succeeded」的行，
     * pending/running 永不动（恢复 worker 可能仍持有）。备份恢复/读档路径必须保留该调用点。
     */
    suspend fun cleanupOrphanedSucceededTurns(): Long = store.cleanupOrphanedSucceededTurns()

    /** 租约是否仍归我（oracle ReplyTurnRepository.isOwned :47-49）。 */
    suspend fun isOwned(id: String, token: String): Boolean = store.isTurnOwned(id, token)

    companion object {
        /** 唯一 turn id 规则（oracle :1408）。 */
        fun turnId(sessionId: String, messageId: Long): String = "private:$sessionId:$messageId"

        /** 反向解析（恢复入口用）。 */
        fun sourceMessageId(turnId: String): Long? =
            turnId.substringAfterLast(':').toLongOrNull()
    }
}
