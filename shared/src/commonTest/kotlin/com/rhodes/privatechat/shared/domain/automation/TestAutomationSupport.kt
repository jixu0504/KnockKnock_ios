package com.rhodes.privatechat.shared.domain.automation

import com.rhodes.privatechat.shared.domain.chat.FakeAiEngine
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Moment
import com.rhodes.privatechat.shared.model.MomentComment
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A7 胶水测试夹具（T25）：
 * - 可推进 fake-clock（不依赖真实时间，验收 #2/#5 的「加速」要求）
 * - fake 通道（模拟前台 Timer + 后台任务双通道；手动 `fire` 到期）
 * - 纯内存 MomentReplyPort / ContextMaintenancePort：逐条复刻 SQL 转移语义
 *   （claim 条件 / 租约内落库同事务 / release / fail / cancel / reset）
 * - 确定性 fake AI（复用 T19 FakeAiEngine，无网络）
 */

/** 可推进挂钟（fake-clock）。 */
class TestAutomationClock(var now: Long = 1_750_000_000_000L) {
    fun read(): Long = now

    fun advanceBy(millis: Long) {
        now += millis
    }
}

/** 可手动触发的 fake 通道（模拟 Timer/后台任务两种宿主实现；fire = 通道到期回调）。 */
class FakeDelayedTaskChannel(private val name: String) : DelayedTaskChannel {
    val scheduled = mutableMapOf<String, Pair<Long, Map<String, String>>>()
    val cancelled = mutableListOf<String>()
    var scheduleCount = 0
    private var callback: (suspend (DelayedTaskDue) -> Unit)? = null

    override suspend fun schedule(key: String, dueAt: Long, payload: Map<String, String>) {
        scheduled[key] = dueAt to payload
        scheduleCount++
    }

    override suspend fun cancel(key: String) {
        cancelled += key
        scheduled.remove(key)
    }

    override suspend fun pendingKeys(): Set<String> = scheduled.keys.toSet()

    override fun onDue(callback: suspend (DelayedTaskDue) -> Unit) {
        this.callback = callback
    }

    suspend fun fire(key: String, attemptCount: Int = 0) {
        val (dueAt, payload) = scheduled[key] ?: return
        callback?.invoke(DelayedTaskDue(key, dueAt, payload, attemptCount))
    }

    override fun toString(): String = name
}

/**
 * 纯内存 MomentReplyPort：逐条复刻 ReplyTurns.sq / MomentRepository.insertCommentForReplyTurn 的转移语义。
 * - claim：仅 pending/failed/running + nextAttemptAt<=now +（running 仅租约过期）可抢占，attemptCount++
 * - 租约内落库：校验 running+leaseToken，来源消失 → cancel turn 并返回 null，否则插评论 + complete turn
 */
class InMemoryMomentReplyPort : MomentReplyPort {
    private val mutex = Mutex()
    private val turns = mutableMapOf<String, ReplyTurn>()
    private val seededComments = mutableListOf<MomentComment>()
    private val insertedComments = mutableListOf<MomentComment>()
    private val moments = mutableMapOf<Long, Moment>()
    private val operators = mutableMapOf<String, Operator>()
    private var commentIdSeq = 0L

    val claimCount: Int get() = claimCalls
    private var claimCalls = 0
    /** (turnId, now, leaseUntil) —— 断言 10 分钟租约。 */
    val claims = mutableListOf<Triple<String, Long, Long>>()
    val released = mutableListOf<Triple<String, Long, String>>()
    val failed = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val commentCountRefreshes = mutableListOf<Long>()
    val createTurnCalls = mutableListOf<String>()

    suspend fun seedTurn(turn: ReplyTurn) = mutex.withLock { turns[turn.id] = turn }
    suspend fun seedMoment(moment: Moment) = mutex.withLock { moments[moment.id] = moment }
    suspend fun seedOperator(operator: Operator) = mutex.withLock { operators[operator.id] = operator }
    suspend fun seedComment(comment: MomentComment) = mutex.withLock {
        commentIdSeq = maxOf(commentIdSeq, comment.id)
        seededComments += comment
    }

    /** 执行体实际落库的回复评论数（不含测试预置的用户来源评论）。 */
    suspend fun commentCount(): Int = mutex.withLock { insertedComments.size }

    /** 执行体实际落库的回复评论（不含预置来源评论）。 */
    suspend fun comments(): List<MomentComment> = mutex.withLock { insertedComments.toList() }
    suspend fun storedComment(id: Long): MomentComment? = mutex.withLock { insertedComments.firstOrNull { it.id == id } }
    suspend fun turnSnapshot(id: String): ReplyTurn? = mutex.withLock { turns[id] }

    override suspend fun getTurn(id: String): ReplyTurn? = mutex.withLock { turns[id] }

    override suspend fun claimTurn(id: String, token: String, now: Long, leaseUntil: Long): ReplyTurn? =
        mutex.withLock {
            claimCalls++
            claims += Triple(id, now, leaseUntil)
            val turn = turns[id] ?: return@withLock null
            val claimable = turn.status in CLAIMABLE &&
                turn.nextAttemptAt <= now &&
                (turn.status != "running" || turn.leaseUntil <= now)
            if (!claimable) return@withLock null
            val claimed = turn.copy(
                status = "running",
                attemptCount = turn.attemptCount + 1,
                leaseToken = token,
                leaseUntil = leaseUntil,
                updatedAt = now,
                lastError = "",
            )
            turns[id] = claimed
            claimed
        }

    override suspend fun createTurnIfAbsent(turn: ReplyTurn): ReplyTurn? = mutex.withLock {
        createTurnCalls += turn.id
        val existing = turns[turn.id]
        if (existing != null) return@withLock existing
        turns[turn.id] = turn
        turn
    }

    override suspend fun getComment(commentId: Long): MomentComment? = mutex.withLock {
        seededComments.firstOrNull { it.id == commentId } ?: insertedComments.firstOrNull { it.id == commentId }
    }

    override suspend fun getMoment(momentId: Long): Moment? = mutex.withLock { moments[momentId] }

    override suspend fun getOperator(operatorId: String): Operator? = mutex.withLock { operators[operatorId] }

    override suspend fun recentComments(momentId: Long): List<MomentComment> = mutex.withLock {
        (seededComments + insertedComments).filter { it.momentId == momentId }.sortedBy { it.id }
    }

    override suspend fun insertCommentForReplyTurn(
        comment: MomentComment,
        turnId: String,
        leaseToken: String,
        sourceCommentId: Long?,
    ): Long? = mutex.withLock {
        val turn = turns[turnId] ?: return@withLock null
        if (turn.status != "running" || turn.leaseToken != leaseToken) return@withLock null
        val momentExists = moments.containsKey(comment.momentId)
        val required = listOfNotNull(comment.parentCommentId.takeIf { it > 0 }, sourceCommentId).distinct()
        val parentsOk = required.all { id ->
            (seededComments.firstOrNull { it.id == id } ?: insertedComments.firstOrNull { it.id == id })?.momentId == comment.momentId
        }
        if (!momentExists || !parentsOk) {
            turns[turnId] = turn.copy(status = "cancelled", leaseToken = "", leaseUntil = 0L)
            cancelled += turnId
            return@withLock null
        }
        commentIdSeq++
        val stored = comment.copy(id = commentIdSeq)
        insertedComments += stored
        turns[turnId] = turn.copy(
            status = "succeeded",
            leaseToken = "",
            leaseUntil = 0L,
            completedAt = comment.createdAt,
            updatedAt = comment.createdAt,
            lastError = "",
        )
        stored.id
    }

    override suspend fun releaseTurn(id: String, token: String, retryAt: Long, now: Long, error: String) =
        mutex.withLock {
            val turn = turns[id] ?: return@withLock
            if (turn.status != "running" || turn.leaseToken != token) return@withLock
            released += Triple(id, retryAt, error)
            turns[id] = turn.copy(
                status = "pending",
                leaseToken = "",
                leaseUntil = 0L,
                nextAttemptAt = retryAt,
                updatedAt = now,
                lastError = error,
            )
        }

    override suspend fun failTurn(id: String, token: String, now: Long, error: String) = mutex.withLock {
        val turn = turns[id] ?: return@withLock
        if (turn.status != "running" || turn.leaseToken != token) return@withLock
        failed += id
        turns[id] = turn.copy(status = "failed", leaseToken = "", leaseUntil = 0L, updatedAt = now, lastError = error)
    }

    override suspend fun cancelTurn(id: String, error: String, now: Long) = mutex.withLock {
        val turn = turns[id] ?: return@withLock
        if (turn.status !in CLAIMABLE) return@withLock
        cancelled += id
        turns[id] = turn.copy(status = "cancelled", leaseToken = "", leaseUntil = 0L, updatedAt = now, lastError = error)
    }

    override suspend fun refreshMomentCommentCount(momentId: Long) = mutex.withLock {
        commentCountRefreshes += momentId
    }

    override suspend fun pendingMomentTurnIds(): List<String> = mutex.withLock {
        turns.values
            .filter { it.surface == "moment_comment" && it.status in setOf("pending", "running") }
            .sortedBy { it.createdAt }
            .map { it.id }
    }

    override suspend fun momentTurns(momentId: Long): List<ReplyTurn> = mutex.withLock {
        turns.values.filter { it.surface == "moment_comment" && it.sessionId == "moment:$momentId" }.sortedBy { it.createdAt }
    }

    override suspend fun resetFailedMomentTurn(id: String, now: Long) = mutex.withLock {
        val turn = turns[id] ?: return@withLock
        if (turn.surface != "moment_comment" || turn.status != "failed") return@withLock
        turns[id] = turn.copy(status = "pending", attemptCount = 0, nextAttemptAt = now, updatedAt = now, lastError = "")
    }

    private companion object {
        val CLAIMABLE = setOf("pending", "failed", "running")
    }
}

/** 动态回复测试环境：fake 通道 + fake port + 确定性 fake AI + fake-clock + 事件收集。 */
class MomentReplyHarness(
    val clock: TestAutomationClock = TestAutomationClock(),
    val port: InMemoryMomentReplyPort = InMemoryMomentReplyPort(),
    scripts: List<String> = listOf("今天食堂的汤有点咸。"),
    val settings: MomentReplySettings = MomentReplySettings(),
    config: MomentReplyConfig = MomentReplyConfig(contentRetryDelayMs = 0L, randomDelayMs = { 10_000L }),
    dualChannel: Boolean = false,
) {
    val primary = FakeDelayedTaskChannel("timer")
    val fallback = if (dualChannel) FakeDelayedTaskChannel("bgtask") else null
    val ai = FakeAiEngine(scripts)
    val events = mutableListOf<MomentReplyEvent>()
    val ingested = mutableListOf<Pair<MomentComment, Long>>()
    var recoveryActive: Boolean = false
    var networkAvailable: Boolean = true
    private var tokenSeq = 0

    val layer = DelayedTaskLayer(
        primaryChannel = primary,
        fallbackChannel = fallback,
        scope = CoroutineScope(Dispatchers.Default),
        clock = clock::read,
        config = DelayedTaskLayerConfig(),
        isRecoveryActive = { recoveryActive },
    )

    val task = MomentReplyTask(
        store = port,
        promptAssembler = MomentCommentPromptAssembler(),
        ai = ai,
        settings = settings,
        payloadCodec = JsonMomentReplyPayloadCodec(),
        clock = clock::read,
        randomToken = { "token-${++tokenSeq}" },
        config = config,
        isRecoveryActive = { recoveryActive },
        isNetworkAvailable = { networkAvailable },
        memoryIngest = { comment, momentId -> ingested += comment to momentId },
        events = { events += it },
    )

    init {
        task.register(layer)
    }

    fun notifications(): List<NotificationEvent> =
        events.filterIsInstance<MomentReplyEvent.NotificationRequested>().map { it.event }

    fun storedReplies(): List<MomentReplyEvent.ReplyStored> =
        events.filterIsInstance<MomentReplyEvent.ReplyStored>()

    /** 种一个可被回复的 moment + 角色 + 用户评论，返回 task payload。 */
    suspend fun seedScenario(
        momentId: Long = 7L,
        operatorId: String = "op-1",
        operatorName: String = "银灰",
        userName: String = "博士",
        sourceCommentId: Long? = 11L,
    ): com.rhodes.privatechat.shared.model.MomentReplyTask {
        port.seedMoment(
            Moment(
                id = momentId,
                operatorId = operatorId,
                operatorName = operatorName,
                content = "今天的训练场格外安静。",
                createdAt = clock.now - 60_000L,
            ),
        )
        port.seedOperator(
            Operator(
                id = operatorId,
                name = operatorName,
                gender = "男",
                description = "沉默寡言的近卫",
                groupPrompt = "说话简短直接",
            ),
        )
        if (sourceCommentId != null) {
            port.seedComment(
                MomentComment(
                    id = sourceCommentId,
                    momentId = momentId,
                    operatorId = "user",
                    operatorName = userName,
                    content = "训练场怎么这么安静？",
                    createdAt = clock.now - 30_000L,
                ),
            )
        }
        return com.rhodes.privatechat.shared.model.MomentReplyTask(
            momentId = momentId,
            operatorId = operatorId,
            speakerName = operatorName,
            userContent = "训练场怎么这么安静？",
            parentCommentId = 0L,
            userName = userName,
            sourceCommentId = sourceCommentId,
        )
    }

    suspend fun awaitComments(count: Int): Boolean = withTimeoutOrNull(3_000L) {
        while (port.commentCount() < count) delay(5)
        true
    } == true

    suspend fun awaitEvent(predicate: (MomentReplyEvent) -> Boolean): Boolean = withTimeoutOrNull(3_000L) {
        while (!events.any(predicate)) delay(5)
        true
    } == true
}

/** 纯内存 ContextMaintenancePort（会话/摘要/消息/抽取/维护重试计数）。 */
class InMemoryContextMaintenancePort : ContextMaintenancePort {
    val sessions = mutableMapOf<String, ChatSession>()
    val summaries = mutableMapOf<String, String>()
    val messages = mutableMapOf<String, MutableList<ChatMessage>>()
    var summarySucceeds: Boolean = true
    var ingestSucceeds: Boolean = true
    var pendingSourcesToRetry: Int = 0
    /** 摘要管线成功后应把滚动摘要状态写回 settings（生产由记忆管线负责）。 */
    var summaryStatusUpdater: ((ChatSession, Boolean) -> Unit)? = null
    val summaryCalls = mutableListOf<Pair<String, Boolean>>()
    val ingestCalls = mutableListOf<Pair<String, List<Long>>>()
    val invalidated = mutableListOf<Pair<String, Long>>()
    var pendingSourceRetryCalls = 0

    fun seedSession(session: ChatSession) {
        sessions[session.id] = session
    }

    fun seedMessage(sessionId: String, message: ChatMessage) {
        messages.getOrPut(sessionId) { mutableListOf() } += message
    }

    fun seedTypedMessage(sessionId: String, id: Long, type: String, timestamp: Long) {
        seedMessage(
            sessionId,
            ChatMessage(
                id = id,
                sessionId = sessionId,
                senderName = if (type == "text") "银灰" else "系统",
                content = "消息$id",
                type = type,
                timestamp = timestamp,
                isMe = false,
            ),
        )
    }

    override suspend fun getSession(id: String): ChatSession? = sessions[id]

    override suspend fun shortTermSummary(sessionId: String): String? = summaries[sessionId]

    override suspend fun messagesAfterCursor(
        sessionId: String,
        afterMessageId: Long,
        restartAt: Long,
        limit: Int,
    ): List<ChatMessage> = messages[sessionId].orEmpty()
        .filter { it.id > afterMessageId && it.timestamp >= restartAt }
        .sortedBy { it.id }
        .take(limit)

    override suspend fun invalidateChangedMessage(sessionId: String, changedMessageId: Long) {
        invalidated += sessionId to changedMessageId
    }

    override suspend fun generateShortTermSummary(session: ChatSession, group: Boolean): Boolean {
        summaryCalls += session.id to group
        if (summarySucceeds) summaries[session.id] = "重建后的摘要"
        summaryStatusUpdater?.invoke(session, summarySucceeds)
        return summarySucceeds
    }

    override suspend fun ingestMemory(session: ChatSession, messages: List<ChatMessage>, group: Boolean): Boolean {
        ingestCalls += session.id to messages.map { it.id }
        return ingestSucceeds
    }

    override suspend fun retryPendingMemorySources(limit: Int): Int {
        pendingSourceRetryCalls++
        return pendingSourcesToRetry
    }
}

/** 记忆维护测试环境：真实 SettingsRepository(内存实现) + fake 端口 + fake-clock。 */
class ContextMaintenanceHarness(
    val clock: TestAutomationClock = TestAutomationClock(),
    val port: InMemoryContextMaintenancePort = InMemoryContextMaintenancePort(),
    config: ContextMaintenanceConfig = ContextMaintenanceConfig(),
) {
    val settings = SettingsRepository(PromptTestSettings())
    val primary = FakeDelayedTaskChannel("timer")
    val events = mutableListOf<ContextMaintenanceEvent>()
    var recoveryActive: Boolean = false
    var networkAvailable: Boolean = true

    val layer = DelayedTaskLayer(
        primaryChannel = primary,
        scope = CoroutineScope(Dispatchers.Default),
        clock = clock::read,
        isRecoveryActive = { recoveryActive },
    )

    val task = ContextMaintenanceTask(
        store = port,
        settings = settings,
        clock = clock::read,
        config = config,
        isRecoveryActive = { recoveryActive },
        isNetworkAvailable = { networkAvailable },
        events = { events += it },
    )

    init {
        task.register(layer)
        // 摘要成功 → 状态回到 idle；失败 → failed + 1min 退避（生产由记忆管线写入）。
        port.summaryStatusUpdater = { session, success ->
            val current = settings.getRollingSummaryStatus(session.id)
            if (success) {
                settings.putRollingSummaryStatus(
                    session.id,
                    current.copy(state = "idle", lastSuccessAt = clock.now, failureCount = 0, nextRetryAt = 0L),
                )
            } else {
                settings.putRollingSummaryStatus(
                    session.id,
                    current.copy(
                        state = "failed",
                        lastAttemptAt = clock.now,
                        failureCount = current.failureCount + 1,
                        nextRetryAt = clock.now + 60_000L,
                    ),
                )
            }
        }
    }

    fun seedPrivateSession(sessionId: String = "s-1", operatorId: String = "op-1"): ChatSession {
        val session = ChatSession(id = sessionId, operatorId = operatorId, operatorName = "银灰")
        port.seedSession(session)
        return session
    }

    fun seedGroupSession(sessionId: String = "g-1", groupOwnerId: String = "group-1"): ChatSession {
        val session = ChatSession(id = sessionId, operatorId = groupOwnerId, operatorName = "群聊")
        port.seedSession(session)
        return session
    }

    fun seedMessages(sessionId: String, count: Int, startId: Long = 1L, startTime: Long = 1_000L) {
        repeat(count) { index ->
            port.seedMessage(
                sessionId,
                ChatMessage(
                    id = startId + index,
                    sessionId = sessionId,
                    senderName = if (index % 2 == 0) "银灰" else "博士",
                    content = "消息$index",
                    timestamp = startTime + index * 1000L,
                    isMe = index % 2 == 1,
                ),
            )
        }
    }

    suspend fun firedExecute(key: String): DelayedTaskOutcome = task.execute(DelayedTaskDue(key, clock.now, emptyMap()))
}
