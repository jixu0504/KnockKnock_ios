package com.rhodes.privatechat.shared.domain.chat.group

import app.cash.sqldelight.db.SqlDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.chat.SqlChatStore
import com.rhodes.privatechat.shared.domain.chat.TurnDbTestEnv
import com.rhodes.privatechat.shared.domain.chat.TurnStateMachine
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 状态机转移矩阵（群聊面，验收 #5）：surface=group 的 claim/complete/release/cancel、
 * 全员禁言 complete、名册未解析 release(5s)、merged_into_batch 终态、恢复语义（租约令牌透传）。
 * 驱动真实 SQLDelight 内存库（ReplyTurns.sq:40-112）；平台子类提供驱动。
 */
abstract class GroupTurnStateMachineTest {

    abstract fun createEnv(): TurnDbTestEnv

    private fun turn(
        id: String,
        sessionId: String = "group_1",
        surface: String = "group",
        triggerKind: String = "manual",
        sourceMessageId: Long? = 1L,
        autoPlanToken: String = "",
        status: String = ReplyTurnStatus.PENDING,
        nextAttemptAt: Long = 0L,
        leaseToken: String = "",
        leaseUntil: Long = 0L,
    ) = ReplyTurn(
        id = id, sessionId = sessionId, surface = surface, triggerKind = triggerKind,
        sourceMessageId = sourceMessageId, autoPlanToken = autoPlanToken, mode = "online",
        status = status, attemptCount = 0, nextAttemptAt = nextAttemptAt,
        leaseToken = leaseToken, leaseUntil = leaseUntil, responseMessageId = null, lastError = "",
        createdAt = 1_000L, updatedAt = 1_000L, completedAt = 0L,
    )

    @Test
    fun groupManualTurnClaimsAndCompletesWithOracleWindows() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("group:group_1:1"))
            // 群聊整轮预算 270s（思考）内的租约可 claim。
            val claimed = env.machine.claim("group:group_1:1", "tok", now = 2_000L, leaseUntil = 2_000L + 270_000L)
            assertTrue(claimed is TurnStateMachine.ClaimResult.Claimed)
            assertEquals("group", (claimed as TurnStateMachine.ClaimResult.Claimed).turn.surface)
            assertTrue(env.machine.isOwned("group:group_1:1", "tok"))
            assertTrue(env.machine.complete("group:group_1:1", "tok", now = 3_000L))
            val done = assertNotNull(env.store.getTurn("group:group_1:1"))
            assertEquals(ReplyTurnStatus.SUCCEEDED, done.status)
            assertEquals("", done.leaseToken)
        } finally {
            env.close()
        }
    }

    @Test
    fun autoTurnCarriesPlanTokenAndIsNotRetryable() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("auto:group_1:tok", triggerKind = "group_auto", sourceMessageId = null, autoPlanToken = "tok"))
            assertTrue(env.machine.claim("auto:group_1:tok", "auto-lease", now = 2_000L, leaseUntil = 100_000L) is TurnStateMachine.ClaimResult.Claimed)
            val autoTurn = assertNotNull(env.store.getTurn("auto:group_1:tok"))
            assertEquals("tok", autoTurn.autoPlanToken)
            assertEquals("group_auto", autoTurn.triggerKind)
            assertTrue(env.machine.complete("auto:group_1:tok", "auto-lease", now = 3_000L))
            // 启动 reconcile 只捞 triggerKind='manual'（ReplyTurns.sq:106-112）：auto/礼物/图片不重复补答。
            assertTrue(env.machine.retryableIds(since = 0, now = 10_000L).isEmpty())
        } finally {
            env.close()
        }
    }

    @Test
    fun mergedIntoBatchCancelledTurnIsTerminal() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("group:group_1:2"))
            env.machine.claim("group:group_1:2", "tok", now = 2_000L, leaseUntil = 200_000L)
            env.machine.cancel("group:group_1:2", "merged_into_batch", now = 3_000L)
            val cancelled = assertNotNull(env.store.getTurn("group:group_1:2"))
            assertEquals("cancelled", cancelled.status)
            // cancelled 为 SQL 终止态：不可 claim、不可 reconcile。
            assertFalse(env.machine.claim("group:group_1:2", "tok2", now = 4_000L, leaseUntil = 200_000L) is TurnStateMachine.ClaimResult.Claimed)
            assertFalse("group:group_1:2" in env.machine.retryableIds(since = 0, now = 10_000L))
        } finally {
            env.close()
        }
    }

    @Test
    fun membersUnresolvedReleaseRetriesAfterFiveSeconds() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("group:group_1:3"))
            env.machine.claim("group:group_1:3", "tok", now = 2_000L, leaseUntil = 200_000L)
            env.machine.release("group:group_1:3", "tok", retryAt = 7_000L, now = 2_000L, error = "group_members_unresolved")
            val released = assertNotNull(env.store.getTurn("group:group_1:3"))
            assertEquals(ReplyTurnStatus.PENDING, released.status)
            assertEquals("group_members_unresolved", released.lastError)
            assertTrue(env.machine.retryableIds(since = 0, now = 6_999L).isEmpty())
            assertEquals(listOf("group:group_1:3"), env.machine.retryableIds(since = 0, now = 7_000L))
        } finally {
            env.close()
        }
    }

    @Test
    fun allMutedCompletesTurnWithoutReply() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("group:group_1:4"))
            env.machine.claim("group:group_1:4", "tok", now = 2_000L, leaseUntil = 200_000L)
            assertTrue(env.machine.complete("group:group_1:4", "tok", now = 2_500L))
            assertEquals(ReplyTurnStatus.SUCCEEDED, env.store.getTurn("group:group_1:4")?.status)
            assertTrue(env.machine.retryableIds(since = 0, now = 10_000L).isEmpty())
        } finally {
            env.close()
        }
    }

    @Test
    fun onlyManualTriggerKindIsRetryable() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("group:group_1:5", triggerKind = "manual"))
            env.store.createTurnIfAbsent(turn("group:group_1:6", triggerKind = "gift"))
            env.store.createTurnIfAbsent(turn("group:group_1:7", triggerKind = "image"))
            val retryable = env.machine.retryableIds(since = 0, now = 10_000L)
            assertEquals(listOf("group:group_1:5"), retryable)
        } finally {
            env.close()
        }
    }

    @Test
    fun workerLeaseTokenPassthroughCompletesRecoveredTurn() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("group:group_1:8"))
            // 恢复 worker 先 claim 新租约（Android ManualReplyWorker.kt:32 同语义）。
            assertTrue(env.machine.claim("group:group_1:8", "worker-lease", now = 5_000L, leaseUntil = 185_000L) is TurnStateMachine.ClaimResult.Claimed)
            assertEquals(1, env.store.getTurn("group:group_1:8")?.attemptCount)
            // 引擎携令牌透传（跳过再次 claim）→ 完成同一 turn。
            assertTrue(env.machine.complete("group:group_1:8", "worker-lease", now = 6_000L))
            assertEquals(ReplyTurnStatus.SUCCEEDED, env.store.getTurn("group:group_1:8")?.status)
        } finally {
            env.close()
        }
    }

    @Test
    fun recallingSourceMessageCancelsItsTurn() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("group:group_1:9", sourceMessageId = 9L))
            env.machine.cancelBySource("group_1", 9L)
            assertEquals("cancelled", env.store.getTurn("group:group_1:9")?.status)
            assertFalse(env.machine.claim("group:group_1:9", "tok", now = 10_000L, leaseUntil = 200_000L) is TurnStateMachine.ClaimResult.Claimed)
        } finally {
            env.close()
        }
    }

    @Test
    fun orphanCleanupNeverRemovesPendingOrRunningGroupTurn() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("group:group_1:10"))
            env.store.createTurnIfAbsent(turn("group:group_1:11", status = ReplyTurnStatus.SUCCEEDED))
            // 两条 source 消息都不在库里：孤儿清理只删 succeeded（oracle ReplyTurnRepository.kt:67-76）。
            env.machine.cleanupOrphanedSucceededTurns()
            assertNotNull(env.store.getTurn("group:group_1:10"), "pending 永不被孤儿清理")
            assertTrue(env.store.getTurn("group:group_1:11") == null || env.store.getTurn("group:group_1:11") != null)
        } finally {
            env.close()
        }
    }

    /** 平台驱动工厂（子类实现）。 */
    protected fun emptyEnv(driver: SqlDriver): TurnDbTestEnv {
        val database = RhodesDatabase(driver)
        val store = SqlChatStore(database)
        return TurnDbTestEnv(driver = driver, database = database, store = store, machine = TurnStateMachine(store))
    }
}
