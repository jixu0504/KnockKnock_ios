package com.rhodes.privatechat.shared.domain.chat

import app.cash.sqldelight.db.SqlDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 状态机转移矩阵（验收 #2）：SQLDelight 内存驱动跑真实 SQL（ReplyTurns.sq:40-62/:94/:99/:106-112）。
 * 平台子类（androidUnitTest JdbcSqliteDriver / iosTest NativeSqliteDriver）提供驱动。
 */
abstract class ReplyTurnStateMachineTest {

    abstract fun createEnv(): TurnDbTestEnv

    private fun turn(
        id: String, sessionId: String = "s1", triggerKind: String = "manual",
        sourceMessageId: Long? = null, status: String = ReplyTurnStatus.PENDING,
        attemptCount: Int = 0, nextAttemptAt: Long = 0L, leaseToken: String = "", leaseUntil: Long = 0L,
        createdAt: Long = 1_000L, updatedAt: Long = 1_000L, responseMessageId: Long? = null, lastError: String = "",
    ) = ReplyTurn(
        id = id, sessionId = sessionId, surface = "private", triggerKind = triggerKind,
        sourceMessageId = sourceMessageId, autoPlanToken = "", mode = "online", status = status,
        attemptCount = attemptCount, nextAttemptAt = nextAttemptAt, leaseToken = leaseToken,
        leaseUntil = leaseUntil, responseMessageId = responseMessageId, lastError = lastError,
        createdAt = createdAt, updatedAt = updatedAt, completedAt = 0L,
    )

    private fun message(id: Long, sessionId: String = "s1") = ChatMessage(
        id = id, sessionId = sessionId, senderName = "我", content = "内容", isMe = true,
    )

    private fun session(id: String = "s1") = ChatSession(id = id, operatorId = "op1", operatorName = "角色")

    private fun operator(id: String = "op1") = Operator(id = id, name = "角色")

    @Test
    fun pendingToRunningToSucceeded() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("t1"))
            val claimed = env.machine.claim("t1", "tok", now = 2_000L, leaseUntil = 2_000L + 180_000L)
            assertTrue(claimed is TurnStateMachine.ClaimResult.Claimed)
            assertEquals(1, (claimed as TurnStateMachine.ClaimResult.Claimed).turn.attemptCount)
            assertEquals(ReplyTurnStatus.RUNNING, claimed.turn.status)
            assertTrue(env.machine.complete("t1", "tok", now = 3_000L))
            val done = env.store.getTurn("t1")!!
            assertEquals(ReplyTurnStatus.SUCCEEDED, done.status)
            assertEquals("", done.leaseToken)
            assertEquals(0L, done.leaseUntil)
        } finally {
            env.close()
        }
    }

    @Test
    fun runningReleaseReturnsPendingWithRetryAt() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("t1"))
            env.machine.claim("t1", "tok", now = 2_000L, leaseUntil = 200_000L)
            env.machine.release("t1", "tok", retryAt = 5_000L, now = 3_000L, error = "e")
            val released = env.store.getTurn("t1")!!
            assertEquals(ReplyTurnStatus.PENDING, released.status)
            assertEquals(5_000L, released.nextAttemptAt)
            assertEquals("", released.leaseToken)
            // retryAt 未到 → 不可重试；到了 → 可重试。
            assertTrue(env.machine.retryableIds(since = 0, now = 4_999L).isEmpty())
            assertEquals(listOf("t1"), env.machine.retryableIds(since = 0, now = 5_000L))
        } finally {
            env.close()
        }
    }

    @Test
    fun runningFailIsClaimableAgain() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("t1"))
            env.machine.claim("t1", "tok", now = 2_000L, leaseUntil = 200_000L)
            env.machine.fail("t1", "tok", now = 3_000L, error = "boom")
            val failed = env.store.getTurn("t1")!!
            assertEquals(ReplyTurnStatus.FAILED, failed.status)
            assertEquals("boom", failed.lastError)
            val reclaimed = env.machine.claim("t1", "tok2", now = 4_000L, leaseUntil = 400_000L)
            assertTrue(reclaimed is TurnStateMachine.ClaimResult.Claimed)
            assertEquals(2, (reclaimed as TurnStateMachine.ClaimResult.Claimed).turn.attemptCount)
        } finally {
            env.close()
        }
    }

    @Test
    fun cancelIsTerminalAndNeverRetryable() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("t1"))
            env.store.createTurnIfAbsent(turn("t2"))
            env.machine.claim("t2", "tok", now = 2_000L, leaseUntil = 200_000L)
            env.machine.cancel("t1", "merged", now = 2_000L)
            env.machine.cancel("t2", "recalled", now = 2_000L)
            assertEquals("cancelled", env.store.getTurn("t1")!!.status)
            assertEquals("cancelled", env.store.getTurn("t2")!!.status)
            // 终止态：claim 拒绝 + reconcile 永不可见。
            assertTrue(env.machine.claim("t1", "tokX", now = 3_000L, leaseUntil = 300_000L) is TurnStateMachine.ClaimResult.Denied)
            assertTrue(env.machine.retryableIds(since = 0, now = 3_000L).isEmpty())
        } finally {
            env.close()
        }
    }

    @Test
    fun claimDeniedByForeignUnexpiredLease() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("t1"))
            env.machine.claim("t1", "foreign", now = 2_000L, leaseUntil = 2_000L + 600_000L)
            val denied = env.machine.claim("t1", "mine", now = 3_000L, leaseUntil = 3_000L + 180_000L)
            assertTrue(denied is TurnStateMachine.ClaimResult.Denied)
            // 仍被外方持有。
            val current = env.store.getTurn("t1")!!
            assertEquals("foreign", current.leaseToken)
            assertEquals(ReplyTurnStatus.RUNNING, current.status)
            // 租约未过期 → 不出现在 reconcile。
            assertTrue(env.machine.retryableIds(since = 0, now = 3_000L).isEmpty())
        } finally {
            env.close()
        }
    }

    @Test
    fun claimDeniedByNextAttemptAt() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("t1", nextAttemptAt = 60_000L))
            assertTrue(env.machine.claim("t1", "tok", now = 2_000L, leaseUntil = 200_000L) is TurnStateMachine.ClaimResult.Denied)
            assertEquals(ReplyTurnStatus.PENDING, env.store.getTurn("t1")!!.status)
            assertTrue(env.machine.retryableIds(since = 0, now = 2_000L).isEmpty())
        } finally {
            env.close()
        }
    }

    @Test
    fun expiredRunningLeaseIsRetryableAndReclaimable() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("t1"))
            env.machine.claim("t1", "old", now = 2_000L, leaseUntil = 10_000L)
            // 租约过期 → reconcile 可见（杀进程恢复语义）。
            assertEquals(listOf("t1"), env.machine.retryableIds(since = 0, now = 10_001L))
            val reclaimed = env.machine.claim("t1", "new", now = 11_000L, leaseUntil = 200_000L)
            assertTrue(reclaimed is TurnStateMachine.ClaimResult.Claimed)
            assertEquals("new", (reclaimed as TurnStateMachine.ClaimResult.Claimed).lease.token)
        } finally {
            env.close()
        }
    }

    @Test
    fun retryableSkipsSucceededCancelledNonManualAndSince() = runBlocking {
        val env = createEnv()
        try {
            // insertReplyTurnIfAbsent 恒以 pending 落库（SQL 层），终止态需经真实转移达成。
            env.store.createTurnIfAbsent(turn("t-done"))
            env.machine.claim("t-done", "tok", now = 2_000L, leaseUntil = 999_999L)
            env.machine.complete("t-done", "tok", now = 2_001L)
            env.store.createTurnIfAbsent(turn("t-cancelled"))
            env.machine.cancel("t-cancelled", "merged", now = 2_000L)
            env.store.createTurnIfAbsent(turn("t-image", triggerKind = "image"))
            env.store.createTurnIfAbsent(turn("t-old", createdAt = 500L))
            env.store.createTurnIfAbsent(turn("t-ok", createdAt = 2_000L))
            val ids = env.machine.retryableIds(since = 1_000L, now = 3_000L)
            assertEquals(listOf("t-ok"), ids)
        } finally {
            env.close()
        }
    }

    @Test
    fun orphanCleanupOnlySucceededWithMissingSource() = runBlocking {
        val env = createEnv()
        try {
            env.store.seedSession(session())
            // succeeded + source 存在 → 保留。
            env.store.seedMessage(message(1L))
            env.store.createTurnIfAbsent(turn("t-keep", sourceMessageId = 1L))
            env.machine.claim("t-keep", "tok", now = 2_000L, leaseUntil = 999_999L)
            env.machine.complete("t-keep", "tok", now = 2_001L)
            // succeeded + source 不存在 → 删除。
            env.store.createTurnIfAbsent(turn("t-orphan", sourceMessageId = 2L))
            env.machine.claim("t-orphan", "tok", now = 2_000L, leaseUntil = 999_999L)
            env.machine.complete("t-orphan", "tok", now = 2_001L)
            // running + source 不存在 → 保留（恢复 worker 可能仍持有）。
            env.store.createTurnIfAbsent(turn("t-running", sourceMessageId = 3L))
            env.machine.claim("t-running", "tok", now = 2_000L, leaseUntil = 999_999L)
            val deleted = env.machine.cleanupOrphanedSucceededTurns()
            assertEquals(1L, deleted)
            assertNotNull(env.store.getTurn("t-keep"))
            assertNull(env.store.getTurn("t-orphan"))
            assertNotNull(env.store.getTurn("t-running"))
            Unit
        } finally {
            env.close()
        }
    }

    @Test
    fun cancelBySourceOnlyTouchesActiveTurn() = runBlocking {
        val env = createEnv()
        try {
            env.store.createTurnIfAbsent(turn("t1", sourceMessageId = 1L))
            env.store.createTurnIfAbsent(turn("t2", sourceMessageId = 2L))
            env.machine.cancelBySource("s1", 1L)
            assertEquals("cancelled", env.store.getTurn("t1")!!.status)
            assertEquals(ReplyTurnStatus.PENDING, env.store.getTurn("t2")!!.status)
            assertFalse(env.machine.claim("t1", "tok", now = 2_000L, leaseUntil = 200_000L) is TurnStateMachine.ClaimResult.Claimed)
        } finally {
            env.close()
        }
    }
}

/** 平台子类提供的 SQL 环境。 */
class TurnDbTestEnv(
    val driver: SqlDriver,
    val database: RhodesDatabase,
    val store: SqlChatStore,
    val machine: TurnStateMachine,
    private val cleanup: (() -> Unit)? = null,
) {
    fun close() {
        runCatching { driver.close() }
        cleanup?.invoke()
    }
}

/**
 * 直接驱动 RhodesDatabase 的测试 store（仅状态机测试所需的查询面）。
 */
class SqlChatStore(private val db: RhodesDatabase) : ChatStore {

    private fun map(
        id: String, sessionId: String, surface: String, triggerKind: String, sourceMessageId: Long?, autoPlanToken: String,
        mode: String, status: String, attemptCount: Long, nextAttemptAt: Long, leaseToken: String, leaseUntil: Long,
        responseMessageId: Long?, lastError: String, createdAt: Long, updatedAt: Long, completedAt: Long,
    ) = ReplyTurn(
        id, sessionId, surface, triggerKind, sourceMessageId, autoPlanToken, mode, status,
        attemptCount.toInt(), nextAttemptAt, leaseToken, leaseUntil, responseMessageId, lastError, createdAt, updatedAt, completedAt,
    )

    override suspend fun getTurn(id: String): ReplyTurn? = db.replyTurnsQueries.getReplyTurn(id, ::map).executeAsOneOrNull()

    override suspend fun createTurnIfAbsent(turn: ReplyTurn): ReplyTurn? {
        db.replyTurnsQueries.insertReplyTurnIfAbsent(
            turn.id, turn.sessionId, turn.surface, turn.triggerKind, turn.sourceMessageId,
            turn.autoPlanToken, turn.mode, turn.nextAttemptAt, turn.createdAt, turn.updatedAt,
        )
        return getTurn(turn.id)
    }

    override suspend fun claimTurn(id: String, token: String, now: Long, leaseUntil: Long): ReplyTurn? {
        db.replyTurnsQueries.claimReplyTurn(token, leaseUntil, now, id, now, now)
        return getTurn(id)?.takeIf { it.status == ReplyTurnStatus.RUNNING && it.leaseToken == token }
    }

    override suspend fun completeTurn(id: String, token: String, now: Long): Boolean {
        db.replyTurnsQueries.completeReplyTurn(now, now, id, token)
        return getTurn(id)?.status == ReplyTurnStatus.SUCCEEDED
    }

    override suspend fun releaseTurn(id: String, token: String, retryAt: Long, now: Long, error: String) {
        db.replyTurnsQueries.releaseReplyTurn(retryAt, now, error.take(240), id, token)
    }

    override suspend fun failTurn(id: String, token: String, now: Long, error: String) {
        db.replyTurnsQueries.failReplyTurn(now, error.take(240), id, token)
    }

    override suspend fun cancelTurn(id: String, error: String, now: Long) {
        db.replyTurnsQueries.cancelReplyTurn(now, error, id)
    }

    override suspend fun cancelTurnBySource(sessionId: String, messageId: Long, error: String) {
        db.replyTurnsQueries.cancelReplyTurnBySource(nowForCancel(), error.take(240), sessionId, messageId)
    }

    private fun nowForCancel(): Long = 2_000L

    override suspend fun retryableTurnIds(since: Long, now: Long, limit: Long): List<String> =
        db.replyTurnsQueries.selectRetryableReplyTurns(since, now, limit).executeAsList()

    override suspend fun cleanupOrphanedSucceededTurns(): Long {
        val count = db.replyTurnsQueries.countOrphanedSucceededReplyTurns().executeAsOne()
        if (count > 0L) db.replyTurnsQueries.deleteOrphanedSucceededReplyTurns()
        return count
    }

    override suspend fun isTurnOwned(id: String, token: String): Boolean =
        db.replyTurnsQueries.isReplyTurnOwned(id, token).executeAsOne()

    override suspend fun getTurnBySource(sessionId: String, messageId: Long): ReplyTurn? =
        db.replyTurnsQueries.getReplyTurnBySource(sessionId, messageId, ::map).executeAsOneOrNull()

    suspend fun seedMessage(message: ChatMessage) {
        db.chatMessagesQueries.insertMessage(
            message.id, message.sessionId, message.senderId, message.senderName, message.content,
            message.type, message.mode, message.emotion, message.activity, message.location,
            message.narration, message.segmentGroup, message.intimacyChange.toLong(), message.timestamp,
            if (message.isMe) 1L else 0L,
        )
    }

    suspend fun seedSession(session: ChatSession) {
        db.chatSessionsQueries.insertSession(
            session.id, session.operatorId, session.operatorName, session.lastMessage, session.lastTime,
            session.mode, if (session.isPinned) 1L else 0L, session.unreadCount.toLong(),
            session.members, session.rules, session.avatarUri, session.mutedMembers,
        )
    }

    // —— 未使用（状态机测试不需要的查询面）——
    override suspend fun getMessageById(id: Long): ChatMessage? = null
    override suspend fun getMessagesSync(sessionId: String): List<ChatMessage> = emptyList()
    override suspend fun getRecentMessagesSync(sessionId: String, limit: Long): List<ChatMessage> = emptyList()
    override suspend fun getMessagesBefore(sessionId: String, beforeTimestamp: Long, beforeId: Long, limit: Long): List<ChatMessage> = emptyList()
    override suspend fun sendMessageAndCreateReplyTurn(sessionId: String, message: ChatMessage, turn: ReplyTurn) = seedMessage(message)
    override suspend fun sendMessage(sessionId: String, message: ChatMessage) = seedMessage(message)
    override suspend fun sendReplyAndCompleteTurn(sessionId: String, message: ChatMessage, turnId: String, leaseToken: String, now: Long): Boolean = false
    override suspend fun updateMessageType(id: Long, type: String) {}
    override suspend fun updateMessageContent(id: Long, content: String) {}
    override suspend fun updateMessageContentAndPreview(sessionId: String, id: Long, content: String, timestamp: Long) {}
    override suspend fun deleteMessage(id: Long) {}
    override suspend fun replaceReplyIfUnchanged(original: ChatMessage, replacement: String): Boolean = false
    override suspend fun updateLastMessage(sessionId: String, lastMessage: String, lastTime: Long) {}
    override suspend fun getDisplayEvents(sessionId: String): List<ChatDisplayEventView> = emptyList()
    override suspend fun deleteDisplayEvent(messageId: Long, segmentIndex: Int) {}
    override suspend fun deleteMessageDisplayEvents(messageId: Long) {}
    override suspend fun getSession(id: String): ChatSession? = null
    override suspend fun getOperator(id: String): Operator? = null
}
