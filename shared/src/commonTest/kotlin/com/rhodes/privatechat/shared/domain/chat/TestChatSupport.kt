package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TestTimeSource

/**
 * 纯内存 ChatStore：逐条复刻 ReplyTurns.sq 的转移语义（claim 条件 / release / fail / cancel /
 * retryable 过滤），供引擎行为测试使用（状态机测试另用真实 SQL 驱动，见 ReplyTurnStateMachineTest）。
 */
class InMemoryChatStore : ChatStore {
    private val mutex = Mutex()
    private val messages = mutableListOf<ChatMessage>()
    private val sessions = mutableListOf<ChatSession>()
    private val operators = mutableListOf<Operator>()
    private val turns = mutableListOf<ReplyTurn>()
    private val displayEvents = mutableListOf<ChatDisplayEventView>()

    suspend fun seedMessage(message: ChatMessage) = mutex.withLock {
        messages += message
        maxMessageId = maxOf(maxMessageId, message.id)
    }
    suspend fun seedSession(session: ChatSession) = mutex.withLock { sessions += session }
    suspend fun seedOperator(operator: Operator) = mutex.withLock { operators += operator }
    suspend fun seedTurn(turn: ReplyTurn) = mutex.withLock { turns += turn }
    suspend fun seedDisplayEvent(event: ChatDisplayEventView) = mutex.withLock { displayEvents += event }

    /** MessageIdAllocator 的 DB max 源（oracle ChatMessages.sq:40-41 的阻塞形态）。 */
    private var maxMessageId: Long = 0L

    fun blockingMaxId(): Long? = maxMessageId.takeIf { it > 0L }

    /** 模拟写库延迟（超时矩阵用；测试线程先行赋值、管线后读，新 MM 下无可见性问题）。 */
    var writeDelayMillis: Long = 0L
    var replyWriteDelayMillis: Long = 0L
    var readDelayMillis: Long = 0L

    private suspend fun writeBarrier() {
        if (writeDelayMillis > 0) delay(writeDelayMillis)
    }

    private suspend fun replyWriteBarrier() {
        if (replyWriteDelayMillis > 0) delay(replyWriteDelayMillis)
    }

    private suspend fun readBarrier() {
        if (readDelayMillis > 0) delay(readDelayMillis)
    }

    override suspend fun getMessageById(id: Long): ChatMessage? = mutex.withLock {
        readBarrier()
        messages.firstOrNull { it.id == id }
    }

    override suspend fun getMessagesSync(sessionId: String): List<ChatMessage> = mutex.withLock {
        readBarrier()
        messages.filter { it.sessionId == sessionId }.sortedBy { it.id }
    }

    override suspend fun getRecentMessagesSync(sessionId: String, limit: Long): List<ChatMessage> = mutex.withLock {
        readBarrier()
        // oracle getRecentMessagesSync 语义：**最新在前**（readPrivateHistoryWindow 依赖此顺序分页）。
        messages.filter { it.sessionId == sessionId }.sortedByDescending { it.id }.take(limit.toInt())
    }

    override suspend fun getMessagesBefore(sessionId: String, beforeTimestamp: Long, beforeId: Long, limit: Long): List<ChatMessage> = mutex.withLock {
        readBarrier()
        messages.filter { it.sessionId == sessionId && (it.timestamp < beforeTimestamp || (it.timestamp == beforeTimestamp && it.id < beforeId)) }
            .sortedByDescending { it.id }.take(limit.toInt())
    }

    override suspend fun sendMessageAndCreateReplyTurn(sessionId: String, message: ChatMessage, turn: ReplyTurn) = mutex.withLock {
        writeBarrier()
        messages += message
        maxMessageId = maxOf(maxMessageId, message.id)
        if (turns.none { it.id == turn.id }) turns += turn
    }

    override suspend fun sendMessage(sessionId: String, message: ChatMessage) = mutex.withLock {
        writeBarrier()
        messages += message
        maxMessageId = maxOf(maxMessageId, message.id)
    }

    override suspend fun sendReplyAndCompleteTurn(sessionId: String, message: ChatMessage, turnId: String, leaseToken: String, now: Long): Boolean = mutex.withLock {
        replyWriteBarrier()
        val index = turns.indexOfFirst { it.id == turnId }
        if (index < 0) return false
        val turn = turns[index]
        if (turn.status != ReplyTurnStatus.RUNNING || turn.leaseToken != leaseToken) return false
        messages += message
        maxMessageId = maxOf(maxMessageId, message.id)
        turns[index] = turn.copy(
            status = ReplyTurnStatus.SUCCEEDED, leaseToken = "", leaseUntil = 0,
            responseMessageId = message.id, updatedAt = now, completedAt = now, lastError = "",
        )
        true
    }

    override suspend fun updateMessageType(id: Long, type: String) = mutex.withLock {
        writeBarrier()
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = messages[index].copy(type = type)
    }

    override suspend fun updateMessageContent(id: Long, content: String) = mutex.withLock {
        writeBarrier()
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = messages[index].copy(content = content)
    }

    override suspend fun updateMessageContentAndPreview(sessionId: String, id: Long, content: String, timestamp: Long) = mutex.withLock {
        writeBarrier()
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = messages[index].copy(content = content)
    }

    override suspend fun deleteMessage(id: Long) = mutex.withLock {
        writeBarrier()
        messages.removeAll { it.id == id }
        displayEvents.removeAll { it.messageId == id }
        Unit
    }

    override suspend fun replaceReplyIfUnchanged(original: ChatMessage, replacement: String): Boolean = mutex.withLock {
        writeBarrier()
        val index = messages.indexOfFirst { it.id == original.id }
        if (index < 0 || messages[index].content != original.content) return false
        messages[index] = messages[index].copy(content = replacement)
        true
    }

    override suspend fun updateLastMessage(sessionId: String, lastMessage: String, lastTime: Long) = mutex.withLock {
        writeBarrier()
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index >= 0) sessions[index] = sessions[index].copy(lastMessage = lastMessage, lastTime = lastTime)
    }

    override suspend fun getDisplayEvents(sessionId: String): List<ChatDisplayEventView> = mutex.withLock {
        readBarrier()
        displayEvents.sortedBy { it.revealOrder }
    }

    override suspend fun deleteDisplayEvent(messageId: Long, segmentIndex: Int) = mutex.withLock {
        writeBarrier()
        displayEvents.removeAll { it.messageId == messageId && it.segmentIndex == segmentIndex }
        Unit
    }

    override suspend fun deleteMessageDisplayEvents(messageId: Long) = mutex.withLock {
        writeBarrier()
        displayEvents.removeAll { it.messageId == messageId }
        Unit
    }

    override suspend fun getSession(id: String): ChatSession? = mutex.withLock {
        readBarrier()
        sessions.firstOrNull { it.id == id }
    }

    override suspend fun getOperator(id: String): Operator? = mutex.withLock {
        readBarrier()
        operators.firstOrNull { it.id == id }
    }

    override suspend fun getTurn(id: String): ReplyTurn? = mutex.withLock {
        readBarrier()
        turns.firstOrNull { it.id == id }
    }

    override suspend fun getTurnBySource(sessionId: String, messageId: Long): ReplyTurn? = mutex.withLock {
        readBarrier()
        turns.firstOrNull { it.sessionId == sessionId && it.sourceMessageId == messageId }
    }

    override suspend fun createTurnIfAbsent(turn: ReplyTurn): ReplyTurn? = mutex.withLock {
        writeBarrier()
        val existing = turns.firstOrNull { it.id == turn.id }
        if (existing != null) existing else {
            turns += turn
            turn
        }
    }

    /** ReplyTurns.sq:40-46 逐条复刻。 */
    override suspend fun claimTurn(id: String, token: String, now: Long, leaseUntil: Long): ReplyTurn? = mutex.withLock {
        writeBarrier()
        val index = turns.indexOfFirst { it.id == id }
        if (index < 0) return null
        val turn = turns[index]
        val claimable = turn.status in setOf(ReplyTurnStatus.PENDING, ReplyTurnStatus.FAILED, ReplyTurnStatus.RUNNING) &&
            turn.nextAttemptAt <= now &&
            (turn.status != ReplyTurnStatus.RUNNING || turn.leaseUntil <= now)
        if (!claimable) return null
        val updated = turn.copy(
            status = ReplyTurnStatus.RUNNING, attemptCount = turn.attemptCount + 1,
            leaseToken = token, leaseUntil = leaseUntil, updatedAt = now, lastError = "",
        )
        turns[index] = updated
        updated
    }

    override suspend fun completeTurn(id: String, token: String, now: Long): Boolean = mutex.withLock {
        writeBarrier()
        val index = turns.indexOfFirst { it.id == id }
        if (index < 0 || turns[index].status != ReplyTurnStatus.RUNNING || turns[index].leaseToken != token) return false
        turns[index] = turns[index].copy(status = ReplyTurnStatus.SUCCEEDED, leaseToken = "", leaseUntil = 0, completedAt = now, updatedAt = now, lastError = "")
        true
    }

    override suspend fun isTurnOwned(id: String, token: String): Boolean = mutex.withLock {
        readBarrier()
        turns.any { it.id == id && it.status == ReplyTurnStatus.RUNNING && it.leaseToken == token }
    }

    override suspend fun releaseTurn(id: String, token: String, retryAt: Long, now: Long, error: String) = mutex.withLock {
        writeBarrier()
        val index = turns.indexOfFirst { it.id == id }
        if (index < 0 || turns[index].status != ReplyTurnStatus.RUNNING || turns[index].leaseToken != token) return
        turns[index] = turns[index].copy(status = ReplyTurnStatus.PENDING, leaseToken = "", leaseUntil = 0, nextAttemptAt = retryAt, updatedAt = now, lastError = error.take(240))
    }

    override suspend fun failTurn(id: String, token: String, now: Long, error: String) = mutex.withLock {
        writeBarrier()
        val index = turns.indexOfFirst { it.id == id }
        if (index < 0 || turns[index].status != ReplyTurnStatus.RUNNING || turns[index].leaseToken != token) return
        turns[index] = turns[index].copy(status = ReplyTurnStatus.FAILED, leaseToken = "", leaseUntil = 0, updatedAt = now, lastError = error.take(240))
    }

    override suspend fun cancelTurn(id: String, error: String, now: Long) = mutex.withLock {
        writeBarrier()
        val index = turns.indexOfFirst { it.id == id }
        if (index < 0 || turns[index].status !in setOf(ReplyTurnStatus.PENDING, ReplyTurnStatus.FAILED, ReplyTurnStatus.RUNNING)) return
        turns[index] = turns[index].copy(status = "cancelled", leaseToken = "", leaseUntil = 0, updatedAt = now, lastError = error.take(240))
    }

    override suspend fun cancelTurnBySource(sessionId: String, messageId: Long, error: String) = mutex.withLock {
        writeBarrier()
        val index = turns.indexOfFirst { it.sessionId == sessionId && it.sourceMessageId == messageId && it.status in setOf(ReplyTurnStatus.PENDING, ReplyTurnStatus.FAILED, ReplyTurnStatus.RUNNING) }
        if (index < 0) return
        val now = turns[index].updatedAt
        turns[index] = turns[index].copy(status = "cancelled", leaseToken = "", leaseUntil = 0, updatedAt = now, lastError = error.take(240))
    }

    /** ReplyTurns.sq:106-112 逐条复刻（triggerKind='manual' 限定）。 */
    override suspend fun retryableTurnIds(since: Long, now: Long, limit: Long): List<String> = mutex.withLock {
        readBarrier()
        turns.filter {
            it.triggerKind == "manual" &&
                it.status in setOf(ReplyTurnStatus.PENDING, ReplyTurnStatus.FAILED, ReplyTurnStatus.RUNNING) &&
                it.createdAt >= since && it.nextAttemptAt <= now &&
                (it.status != ReplyTurnStatus.RUNNING || it.leaseUntil <= now)
        }.sortedBy { it.createdAt }.take(limit.toInt()).map { it.id }
    }

    override suspend fun cleanupOrphanedSucceededTurns(): Long = mutex.withLock {
        writeBarrier()
        val orphans = turns.filter {
            it.sourceMessageId != null && it.status == ReplyTurnStatus.SUCCEEDED &&
                messages.none { m -> m.id == it.sourceMessageId && m.sessionId == it.sessionId }
        }
        turns.removeAll(orphans.toSet())
        orphans.size.toLong()
    }
}

/** 测试时钟：可推进的挂钟（引擎预算用 TestTimeSource，挂钟用本类）。 */
class TestChatClock : ChatClock {
    var now: Long = 1_750_000_000_000L
    override fun nowMillis(): Long = now
}

/** 引擎行为测试环境：fake store + 确定性 fake AI + 可注入 TestTimeSource + 事件收集。 */
class ChatEngineTestHarness(
    val store: InMemoryChatStore = InMemoryChatStore(),
    val settings: com.russhwolf.settings.ObservableSettings = com.rhodes.privatechat.shared.prompt.PromptTestSettings(),
    var aiEngine: FakeAiEngine = FakeAiEngine(emptyList()),
    val config: ChatEngineConfig = ChatEngineConfig(),
    val timeSource: TestTimeSource = TestTimeSource(),
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    extraAssemblerDelayMillis: Long = 0L,
) {
    val settingsRepository = com.rhodes.privatechat.shared.settings.SettingsRepository(settings)
    val clock = TestChatClock()
    val events = mutableListOf<EngineEvent>()
    val assemblerRequests = mutableListOf<PromptBuildRequest>()

    val assembler = object : PromptAssembler {
        override suspend fun build(request: PromptBuildRequest): List<com.rhodes.privatechat.shared.model.AiMessage> {
            if (extraAssemblerDelayMillis > 0) delay(extraAssemblerDelayMillis)
            assemblerRequests += request
            return listOf(com.rhodes.privatechat.shared.model.AiMessage("system", "system"), com.rhodes.privatechat.shared.model.AiMessage("user", "用户：${request.userContent}"))
        }
    }

    fun engine(
        visionConfigured: Boolean = true,
        visionAnalyze: suspend (String) -> String? = { """{"visibleSummary":"一张图","sceneQuality":"clear"}""" },
        imageForModelResolver: (String) -> String? = { it },
        chatConfigError: () -> String? = { null },
        aiClientOverride: StreamingClient? = null,
        semantics: ChatEngineSemantics = ChatEngineSemantics(),
    ) = ChatEngine(
        scope = scope,
        store = store,
        allocator = com.rhodes.privatechat.shared.domain.data.MessageIdAllocator(
            com.rhodes.privatechat.shared.domain.data.MessageMaxIdSource { store.blockingMaxId() },
            settingsRepository,
        ),
        settings = settingsRepository,
        assembler = assembler,
        aiClient = aiClientOverride ?: aiEngine,
        clock = clock,
        timeSource = timeSource,
        config = config,
        semantics = semantics,
        events = EngineEventSink { events += it },
        visionConfigured = { visionConfigured },
        visionAnalyze = visionAnalyze,
        imageForModelResolver = imageForModelResolver,
        chatConfigError = chatConfigError,
        promptBuildDispatcher = Dispatchers.Default.limitedParallelism(2),
    )

    fun toastMessages(): List<String> = events.filterIsInstance<EngineEvent.Toast>().map { it.message }
    fun undelivered(): List<EngineEvent.Undelivered> = events.filterIsInstance<EngineEvent.Undelivered>()
    fun settled(): List<EngineEvent.Settled> = events.filterIsInstance<EngineEvent.Settled>()
    fun replyStored(): List<EngineEvent.ReplyStored> = events.filterIsInstance<EngineEvent.ReplyStored>()
}
