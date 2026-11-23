@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.domain.data.MessageIdAllocator
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.OfflineModeResponse
import com.rhodes.privatechat.shared.model.PrivateTurnState
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.model.Segment
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.TimeSource

private val engineJson = Json { ignoreUnknownKeys = true }

/**
 * 私聊轮次引擎（规格卡 private-chat-engine-spec.md 20 条行为契约的域实现）。
 *
 * Android oracle：app/.../viewmodel/ChatViewModel.kt（sendMessage:1298 / cancelSessionRequests:1258 /
 * resumePersistedReply:1796 / sendImageMessage:1819 / recallMessage:2083 / retryFailedMessage:2150 /
 * recallMessageSegment:2173 / regenerateAiMessage:2363 / continueAiMessage:2443 / resumeImageReply:2742）。
 *
 * 分层（lead 裁决）：
 * - 内存层：isLoading / sessionGenerations / pendingUserMessageIds / chatAiJobs / retryingMessageIds…（本类）；
 * - 持久化租约层：reply_turns pending→running→succeeded/failed/cancelled（TurnStateMachine + ChatStore）；
 * - 恢复语义：iOS 无 WorkManager——启动 reconcile（onAppStartReconcile）+ 前台补算（resumePersistedReply /
 *   resumeSessionTurns），领域层不依赖 BGTask（规格卡 R1 lead 裁决）；
 * - 流式：只依赖 StreamingClient 抽象，Ktor Darwin 取消语义（T9 验证）留在宿主接线；整包落库（R2 裁决）。
 */
class ChatEngine(
    private val scope: CoroutineScope,
    private val store: ChatStore,
    private val allocator: MessageIdAllocator,
    private val settings: SettingsRepository,
    private val assembler: PromptAssembler,
    private val aiClient: StreamingClient,
    private val codec: ModelOutputCodec = DefaultReplyCodec(),
    private val clock: ChatClock = SystemChatClock(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val config: ChatEngineConfig = ChatEngineConfig(),
    /** T35 D1/D2 对齐模式（默认 = 引擎既有行为，shared 既有测试零改动）。 */
    private val semantics: ChatEngineSemantics = ChatEngineSemantics(),
    private val events: EngineEventSink = EngineEventSink {},
    private val visionConfigured: () -> Boolean = { false },
    private val visionAnalyze: suspend (imageForModel: String) -> String? = { null },
    private val imageForModelResolver: (uri: String) -> String? = { null },
    private val chatConfigError: () -> String? = { null },
    private val promptBuildDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val turns = TurnStateMachine(store)

    // === 内存层状态（oracle :182-230）===
    private val stateMutex = Mutex()
    private val _loadingSessions = MutableStateFlow<Set<String>>(emptySet())
    val loadingSessions: StateFlow<Set<String>> = _loadingSessions.asStateFlow()
    private val sessionGenerations = mutableMapOf<String, Long>()
    private val pendingUserMessageIds = mutableMapOf<String, MutableSet<Long>>()
    private val retryingMessageIds = mutableSetOf<Long>()
    private val recalledSourceMessageIds = mutableSetOf<Long>()
    private val chatAiJobs = mutableMapOf<String, Job>()
    private val promptBuildJobs = mutableMapOf<String, Job>()
    private val activeReplySourceIds = mutableMapOf<String, Long>()
    private val activeRequestBySession = mutableMapOf<String, Long>()
    private val chatAiMutexes = mutableMapOf<String, Mutex>()
    private val restartCleanupJobs = mutableMapOf<String, Job>()
    private var requestSequence = 0L
    private var leaseTokenCounter = 0L

    private fun newRequestId(): Long = ++requestSequence
    private fun newLeaseToken(): String =
        "lease-${++leaseTokenCounter}-${clock.nowMillis()}-${Random.nextInt(0xFFFF).toString(16)}"

    private suspend fun <T> withState(block: () -> T): T = stateMutex.withLock { block() }

    private fun generationOf(sessionId: String): Long = sessionGenerations[sessionId] ?: 0L

    private suspend fun beginLoading(sessionId: String, requestId: Long) = withState {
        activeRequestBySession[sessionId] = requestId
        _loadingSessions.value = _loadingSessions.value + sessionId
    }

    private suspend fun finishLoading(sessionId: String, requestId: Long) = withState {
        if (activeRequestBySession.remove(sessionId) == requestId) {
            _loadingSessions.value = _loadingSessions.value - sessionId
        }
    }

    private suspend fun aiMutexFor(sessionId: String): Mutex = withState {
        chatAiMutexes.getOrPut(sessionId) { Mutex() }
    }

    /** oracle cancelSessionRequests :1258-1266 —— generation 递增使一切在飞轮次失效。 */
    fun cancelSessionRequests(sessionId: String) {
        scope.launch {
            val (cleanupJob, aiJob, promptJob) = withState {
                sessionGenerations[sessionId] = (sessionGenerations[sessionId] ?: 0L) + 1L
                pendingUserMessageIds.remove(sessionId)
                activeRequestBySession.remove(sessionId)
                _loadingSessions.value = _loadingSessions.value - sessionId
                activeReplySourceIds.remove(sessionId)
                Triple(
                    restartCleanupJobs.remove(sessionId),
                    chatAiJobs.remove(sessionId),
                    promptBuildJobs.remove(sessionId),
                )
            }
            cleanupJob?.cancel()
            aiJob?.cancel()
            promptJob?.cancel()
        }
    }

    /**
     * 时间线失效（oracle sessionGenerations.merge 的独立入口）：会话切换/读档/重启时若不存在
     * 在飞任务（或已在飞任务未被取消），跨代写入由管线内 generation 复核拦截（oracle :1470-1476/:1592）。
     */
    fun invalidateSession(sessionId: String) {
        scope.launch {
            withState { sessionGenerations[sessionId] = (sessionGenerations[sessionId] ?: 0L) + 1L }
        }
    }

    // === 发送主流程（oracle sendMessage :1298-1794）===

    /** 发送用户文本；guard 拒绝返回 null。返回值为 pipeline Job。 */
    fun sendMessage(
        textOverride: String?,
        session: ChatSession,
        mode: String,
        persistedContentOverride: String? = null,
        retryMessageId: Long? = null,
        messageTypeOverride: String? = null,
        replyTurnIdOverride: String? = null,
        replyLeaseTokenOverride: String? = null,
        onResponseComplete: (Boolean) -> Unit = {},
    ): Job? {
        val text = (textOverride ?: "").trim()
        if (text.isEmpty()) return null // oracle :1316
        chatConfigError()?.let { error -> // oracle :1317-1322
            retryMessageId?.let { scope.launch { withState { retryingMessageIds.remove(it) } } }
            events.emit(EngineEvent.Toast(error))
            return null
        }
        val requestId = newRequestId()
        val generation = generationOf(session.id)
        return scope.launch {
            runSendPipeline(
                session = session,
                text = text,
                mode = mode,
                persistedContentOverride = persistedContentOverride,
                retryMessageId = retryMessageId,
                messageTypeOverride = messageTypeOverride,
                replyTurnIdOverride = replyTurnIdOverride,
                replyLeaseTokenOverride = replyLeaseTokenOverride,
                generation = generation,
                requestId = requestId,
                onResponseComplete = onResponseComplete,
            )
        }
    }

    /**
     * 中断恢复（oracle resumePersistedReply :1796-1817）：消息必须是 isMe 且属该会话；
     * type 重置（gift_reply_failed→gift_hidden，其余→text）后走重试分支；租约令牌透传。
     * iOS：由启动 reconcile + 前台补算驱动，无 WorkManager（R1 裁决）。
     */
    suspend fun resumePersistedReply(
        sessionId: String,
        messageId: Long,
        replyTurnId: String? = null,
        replyLeaseToken: String? = null,
    ): Boolean {
        val session = store.getSession(sessionId)
        val message = store.getMessageById(messageId)?.takeIf { it.sessionId == sessionId && it.isMe }
        if (session == null || message == null) return true // oracle :1798-1803
        store.updateMessageType(messageId, if (message.type == "gift_reply_failed") "gift_hidden" else "text")
        val isGift = message.type == "gift_reply_failed" || message.type == "gift_hidden"
        val deferred = CompletableDeferred<Boolean>()
        val job = sendMessage(
            textOverride = if (isGift) PrivateReplyFormat.giftPromptText(message.content) else message.content,
            session = session,
            mode = message.mode,
            retryMessageId = messageId,
            messageTypeOverride = if (isGift) "gift_hidden" else null,
            replyTurnIdOverride = replyTurnId,
            replyLeaseTokenOverride = replyLeaseToken,
            onResponseComplete = { deferred.complete(it) },
        )
        if (job == null) return true
        return deferred.await()
    }

    /**
     * 图片回复恢复（oracle resumeImageReply :2742-2756）：从图片 JSON 还原 uri/caption 后
     * 重走 sendImageMessage(existingMessageId=…)。
     */
    suspend fun resumeImageReply(sessionId: String, messageId: Long, replyLeaseToken: String? = null): Boolean {
        val session = store.getSession(sessionId)
        if (session == null) return false
        val message = store.getMessageById(messageId)?.takeIf { it.sessionId == sessionId && it.isMe && it.type == "image" }
            ?: return false
        val image = ImageMessageContract.parse(message.content) ?: return false
        if (image.imageUri.isBlank()) return false
        val imageForModel = imageForModelResolver(image.imageUri) ?: return false
        val deferred = CompletableDeferred<Boolean>()
        val job = sendImageMessage(
            imageUri = image.imageUri,
            imageForModel = imageForModel,
            caption = image.caption,
            existingMessageId = messageId,
            replyLeaseTokenOverride = replyLeaseToken,
            session = session,
            mode = message.mode,
            onReplyComplete = { deferred.complete(it) },
        )
        if (job == null) return false
        return deferred.await()
    }

    /** 启动 reconcile（R1 裁决）：捞出杀进程遗留的可重试 turn，由前台补算驱动。 */
    suspend fun onAppStartReconcile(): List<String> {
        runCatching { turns.cleanupOrphanedSucceededTurns() }
        return runCatching { turns.retryableIds(since = 0L, now = clock.nowMillis()) }
            .getOrDefault(emptyList())
    }

    /** 前台补算：进入会话时把该会话全部可重试 turn 驱动一遍（resumePersistedReply）。返回成功数。 */
    suspend fun resumeSessionTurns(sessionId: String): Int {
        var resumed = 0
        val ids = turns.retryableIds(since = 0L, now = clock.nowMillis())
        for (id in ids) {
            val turn = store.getTurn(id) ?: continue
            if (turn.sessionId != sessionId || turn.triggerKind != "manual") continue
            val sourceId = turn.sourceMessageId ?: continue
            if (resumePersistedReply(sessionId, sourceId, replyTurnId = id)) resumed++
        }
        return resumed
    }

    /**
     * 核心发送管线（oracle :1329-1794 逐段对应）。
     * 返回 responseStored（可见回复是否落库并完成租约）。
     */
    private suspend fun runSendPipeline(
        session: ChatSession,
        text: String,
        mode: String,
        persistedContentOverride: String?,
        retryMessageId: Long?,
        messageTypeOverride: String?,
        replyTurnIdOverride: String?,
        replyLeaseTokenOverride: String?,
        generation: Long,
        requestId: Long,
        onResponseComplete: (Boolean) -> Unit,
    ): Boolean {
        var msgId = retryMessageId ?: 0L
        var aiMsgId = 0L
        var mutexLocked = false
        var userMessagePersisted = false
        var responseStored = false
        /** D1：本轮是否被宿主取消（legacy 语义下取消路径完全不写 DB）。 */
        var cancelledByHost = false
        var batchIds = emptySet<Long>()
        var pipelineStage = "user_message_prepare"
        var replyTurnId = ""
        var replyLeaseToken = ""
        val turnBudgetMs = config.effectiveTurnBudgetMs // oracle :1344 进轮固化

        try {
            // 重启清理等待（oracle :1363-1388）：join ≤10s，超时取消继续发送。
            val restartCleanup = withState { restartCleanupJobs[session.id] }
            if (restartCleanup?.isActive == true) {
                val completed = kotlinx.coroutines.withTimeoutOrNull(config.restartCleanupJoinMillis) {
                    restartCleanup.join()
                    true
                } == true
                if (!completed) {
                    restartCleanup.cancel()
                    restartCleanup.join()
                }
            }
            if (msgId == 0L) {
                pipelineStage = "user_message_id"
                msgId = withTimeout(config.messageWriteBudgetMs) { allocator.allocate() } // oracle :1391
            }
            val hiddenGift = messageTypeOverride == "gift_hidden"
            val userMessage = ChatMessage(
                id = msgId, sessionId = session.id,
                senderName = "我", content = if (hiddenGift) persistedContentOverride ?: text else text,
                type = messageTypeOverride ?: "text", mode = mode,
                timestamp = clock.nowMillis(), isMe = true,
            )
            if (retryMessageId == null) {
                pipelineStage = "user_message_write"
                replyTurnId = replyTurnIdOverride ?: TurnStateMachine.turnId(session.id, msgId)
                val now = clock.nowMillis()
                withTimeout(config.messageWriteBudgetMs) { // oracle :1410-1415
                    store.sendMessageAndCreateReplyTurn(
                        session.id, userMessage,
                        ReplyTurn(replyTurnId, session.id, "private", if (hiddenGift) "gift" else "manual", msgId, "", mode, "pending", 0, now, "", 0, null, "", now, now, 0),
                    )
                }
                val persisted = withTimeout(config.messageWriteBudgetMs) { // oracle :1418-1420
                    store.getMessageById(msgId)?.let { it.sessionId == session.id && it.isMe } == true
                }
                check(persisted) { "用户消息写入后无法从数据库读回" }
            } else {
                replyTurnId = replyTurnIdOverride ?: TurnStateMachine.turnId(session.id, msgId)
                if (store.getTurn(replyTurnId) == null) { // oracle :1425-1428
                    val now = clock.nowMillis()
                    store.createTurnIfAbsent(
                        ReplyTurn(replyTurnId, session.id, "private", if (messageTypeOverride == "gift_hidden") "gift" else "manual", msgId, "", mode, "pending", 0, now, "", 0, null, "", now, now, 0),
                    )
                }
                withTimeout(config.messageWriteBudgetMs) { store.updateMessageType(msgId, messageTypeOverride ?: "text") } // oracle :1430
            }
            userMessagePersisted = true
            replyLeaseToken = replyLeaseTokenOverride ?: newLeaseToken()
            // claim 租约（oracle :1446-1455）；iOS 语义：claim 被拒 → 消息已保存，
            // 由启动 reconcile / 前台补算补答（无 WorkManager，R1 裁决）。
            if (replyLeaseTokenOverride == null) {
                val claim = turns.claim(replyTurnId, replyLeaseToken, clock.nowMillis(), clock.nowMillis() + turnBudgetMs)
                if (claim is TurnStateMachine.ClaimResult.Denied) {
                    events.emit(EngineEvent.ClaimDenied(session.id, msgId, replyTurnId))
                    if (retryMessageId == null) events.emit(EngineEvent.Toast("消息已保存，回复稍后自动补上"))
                    return false
                }
            }
            batchIds = setOf(msgId)
            withState { pendingUserMessageIds.getOrPut(session.id) { mutableSetOf() }.add(msgId) }
            events.emit(EngineEvent.UnhideSession(session.id))
            pipelineStage = "ai_message_id"
            aiMsgId = withTimeout(config.messageWriteBudgetMs) { allocator.allocate() } // oracle :1465
            pipelineStage = "ai_mutex_wait"
            val aiMutex = aiMutexFor(session.id)
            aiMutex.lock()
            mutexLocked = true
            if (generationOf(session.id) != generation) return false // oracle :1470-1476
            val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            withState {
                if (currentJob != null) chatAiJobs[session.id] = currentJob
                activeReplySourceIds[session.id] = msgId
            }
            beginLoading(session.id, requestId)
            // 250ms 防抖 + pendingIds 复核（oracle :1482-1492）。
            delay(config.debounceBeforeAiMillis)
            val pendingIds = withState { pendingUserMessageIds[session.id]?.toSet().orEmpty() }
            if (msgId !in pendingIds) return false // supersede → finally 释放租约，恢复补答
            val candidateIds = listOf(msgId)
            val batchMessages = candidateIds.mapNotNull { store.getMessageById(it)?.takeIf { row -> row.sessionId == session.id } }
                .asSequence()
                .filter { it.id in candidateIds }
                .sortedBy { it.id }
                .fold(mutableListOf<ChatMessage>()) { acc, message ->
                    if (acc.isEmpty() || acc.sumOf { it.content.length } + message.content.length <= config.maxMergedUserChars) acc += message
                    acc
                }
            batchIds = batchMessages.map { it.id }.ifEmpty { listOf(msgId) }.toSet()
            withState { pendingUserMessageIds[session.id]?.removeAll(batchIds.toSet()) }

            var retryCount = 0
            var lastError: Throwable? = null
            var effectiveHistoryMessages = settings.historyMessages
            val turnStartedAt = timeSource.markNow()
            fun remainingTurnBudget(): Long =
                (turnBudgetMs - turnStartedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(1L)

            while (retryCount < config.maxRetries) { // oracle :1534-1722
                try {
                    pipelineStage = "prompt_build"
                    val oldPromptJob = withState { promptBuildJobs.remove(session.id) }
                    oldPromptJob?.cancel()
                    val promptBudget = minOf(config.promptBuildBudgetMs, remainingTurnBudget()) // oracle :1546
                    val promptJob = scope.async(promptBuildDispatcher) {
                        assembler.build(
                            PromptBuildRequest(
                                session = session,
                                userContent = text,
                                historyLimitOverride = effectiveHistoryMessages,
                                excludeMessageIds = batchIds,
                                mode = mode,
                            )
                        )
                    }
                    withState { promptBuildJobs[session.id] = promptJob }
                    val apiMessages = try {
                        withChatStageTimeout("private", "prompt_build", promptBudget) { promptJob.await() } // oracle :1547-1552
                    } catch (error: ChatStageTimeoutException) {
                        promptJob.cancel()
                        throw error
                    }
                    withState { if (promptBuildJobs[session.id] === promptJob) promptBuildJobs.remove(session.id) }
                    pipelineStage = "ai_request"
                    val parsed = generateCompleteReply(apiMessages, mode, remainingTurnBudget()) // oracle :1565
                    val protocolComplete = VisibleReplyContract.isCompletePrivateReply(parsed, mode)
                    val sanitizedParsed = VisibleReplyContract.sanitizePrivateResponse(parsed).let { response -> // oracle :1572-1576
                        if (response.segments.orEmpty().any { it.content.isNotBlank() && !it.type.equals("narration", true) }) response
                        else response.copy(segments = listOf(Segment(type = "dialogue", content = VisibleReplyContract.replyPreview(response).ifBlank { "模型回复内容格式异常，请重试。" })))
                    }
                    val serializedJson = runCatching { engineJson.encodeToString(OfflineModeResponse.serializer(), sanitizedParsed) }
                        .getOrElse { sanitizedParsed.toString() }
                    val rawJson = codec.cleanJson(serializedJson)
                    val hasVisibleReply = rawJson.isNotBlank() && sanitizedParsed.segments.orEmpty()
                        .any { it.content.isNotBlank() && !it.type.equals("narration", true) } // oracle :1582-1584
                    val aiResponseCount = if (hasVisibleReply) VisibleReplyContract.visiblePrivateSegmentCount(sanitizedParsed, mode) else 0
                    if (hasVisibleReply) {
                        if (generationOf(session.id) != generation) return false // oracle :1592
                        pipelineStage = "ai_reply_write"
                        val nextTurnState = buildNextTurnState(settings.getPrivateTurnState(session.id), sanitizedParsed, clock.nowMillis())
                        try {
                            withChatStageTimeout("private", "ai_reply_write", minOf(config.messageWriteBudgetMs, remainingTurnBudget())) { // oracle :1617-1619
                                check(store.sendReplyAndCompleteTurn(
                                    session.id,
                                    ChatMessage(id = aiMsgId, sessionId = session.id, senderName = session.operatorName, content = rawJson, type = "ai_json", mode = mode, emotion = parsed.emotion, activity = parsed.state, location = parsed.location, isMe = false),
                                    replyTurnId, replyLeaseToken, clock.nowMillis(),
                                )) { "reply turn lease lost" }
                            }
                        } catch (error: Exception) {
                            pipelineStage = "ai_reply_write_failed"
                            throw error
                        }
                        settings.putPrivateTurnState(session.id, nextTurnState)
                        events.emit(EngineEvent.TurnStateUpdated(session.id))
                        responseStored = true
                        val preview = VisibleReplyContract.replyPreview(sanitizedParsed).ifBlank { "发来一条消息" }
                        // T28 步骤 0：宿主诊断快照（B1），随回复落库/结算事件下发。
                        val diagnostics = ReplyDiagnostics(
                            mode = mode,
                            promptMessageCount = apiMessages.size,
                            historyMessageCount = effectiveHistoryMessages,
                            contentRetryCount = retryCount,
                            segmentCount = sanitizedParsed.segments.orEmpty().size,
                            visibleCount = aiResponseCount,
                            protocolComplete = protocolComplete,
                        )
                        events.emit(EngineEvent.ReplyStored(session.id, batchIds, aiMsgId, diagnostics))
                        events.emit(EngineEvent.UnhideSession(session.id))
                        // 仅可见回复结算（oracle :1649-1655；亲密/龙门币写入由宿主执行）。
                        val affectionMod = SettlementPolicy.affectionMod(parsed)
                        events.emit(EngineEvent.Settled(session.id, session.operatorId, affectionMod, SettlementPolicy.DAILY_LMB, aiResponseCount, preview, diagnostics))
                        settings.clearPendingPrivateModeTransition(session.id)
                        events.emit(EngineEvent.MaintenanceRequested(session.id))
                        events.emit(EngineEvent.UnreadHint(session.id, aiResponseCount))
                        lastError = null
                        break // oracle :1669
                    } else {
                        markPrivateMessagesUndelivered(session.id, batchIds.toSet()) // oracle :1656-1667
                        lastError = null
                        break
                    }
                } catch (e: ChatStageTimeoutException) {
                    // oracle :1670-1684 —— 阶段超时：标记未送达 + Toast。
                    if (generationOf(session.id) != generation) return false
                    markPrivateMessagesUndelivered(session.id, batchIds.toSet())
                    events.emit(EngineEvent.Toast(e.userMessage("私聊")))
                    break
                } catch (e: TimeoutCancellationException) {
                    // oracle :1685-1696 —— 整轮超时（裸 withTimeout 溢出）。
                    if (generationOf(session.id) != generation) return false
                    markPrivateMessagesUndelivered(session.id, batchIds.toSet())
                    events.emit(EngineEvent.Toast("私聊整轮处理超时（预算${turnBudgetMs / 1000}秒），本轮未生成或保存AI回复，请重试"))
                    break
                } catch (e: CancellationException) {
                    throw e // oracle :1697-1699 —— 取消透传，finally 统一处置。
                } catch (e: Exception) {
                    // oracle :1700-1721 —— 400/上下文超限 → 历史轮数减半（min 5）最多 3 次。
                    if (SettlementPolicy.isContextLimitError(e) && retryCount < config.maxRetries - 1 && effectiveHistoryMessages > config.degradedHistoryFloor) {
                        effectiveHistoryMessages = (effectiveHistoryMessages / 2).coerceAtLeast(config.degradedHistoryFloor)
                        retryCount++
                        continue
                    }
                    lastError = e
                    break
                }
            }
            if (lastError != null) { // oracle :1723-1732
                if (generationOf(session.id) != generation) return false
                markPrivateMessagesUndelivered(session.id, batchIds.toSet())
            }
        } catch (e: TimeoutCancellationException) {
            // oracle :1733-1742 —— 写库/外层超时：未持久化回滚（宿主 UI 为 flow 驱动，无乐观行需回滚）。
            if (!userMessagePersisted) {
                events.emit(EngineEvent.Toast("消息保存超时，请稍后重试"))
            } else {
                markPrivateMessagesUndelivered(session.id, batchIds.ifEmpty { setOf(msgId) })
            }
        } catch (e: CancellationException) {
            // oracle :1743-1748 —— 取消：已持久化用户消息置 send_failed（撤回源除外）。
            cancelledByHost = true
            if (!semantics.cancelSkipsDbWrites) {
                val affectedMessageIds = batchIds.ifEmpty { setOf(msgId) } - withState { recalledSourceMessageIds.toSet() }
                if (userMessagePersisted && affectedMessageIds.isNotEmpty()) {
                    markPrivateMessagesUndelivered(session.id, affectedMessageIds)
                }
            }
            throw e
        } catch (e: Exception) {
            // oracle :1749-1761。
            if (!userMessagePersisted) {
                events.emit(EngineEvent.Toast("消息保存失败，请重试"))
            } else {
                markPrivateMessagesUndelivered(session.id, batchIds.ifEmpty { setOf(msgId) })
            }
        } finally {
            finishSendPipeline(
                sessionId = session.id,
                requestId = requestId,
                responseStored = responseStored,
                replyTurnId = replyTurnId,
                replyLeaseToken = replyLeaseToken,
                retryMessageId = retryMessageId,
                mutexLocked = mutexLocked,
                ownJob = kotlinx.coroutines.currentCoroutineContext()[Job],
                cancelledByHost = cancelledByHost,
                onResponseComplete = onResponseComplete,
            )
        }
        return responseStored
    }

    /**
     * 发送管线收尾（oracle :1762-1792）。抽成独立方法：
     * 1) runSendPipeline 已接近 JVM 64KB 方法上限（Kotlin 2.3 + 内联 lambda 膨胀，T11 同类问题）；
     * 2) D1 legacy 语义要求「DB 写入跳过、宿主侧清理必须在 NonCancellable 中完成」，集中在此一处表达。
     */
    private suspend fun finishSendPipeline(
        sessionId: String,
        requestId: Long,
        responseStored: Boolean,
        replyTurnId: String,
        replyLeaseToken: String,
        retryMessageId: Long?,
        mutexLocked: Boolean,
        ownJob: Job?,
        cancelledByHost: Boolean,
        onResponseComplete: (Boolean) -> Unit,
    ) {
        suspend fun releaseTurnIfNeeded() {
            if (!responseStored && replyTurnId.isNotBlank() && replyLeaseToken.isNotBlank()) {
                val now = clock.nowMillis()
                runCatching { turns.release(replyTurnId, replyLeaseToken, now, now, "reply_not_completed") }
            }
        }

        suspend fun cleanupHostSide() {
            retryMessageId?.let { id -> withState { retryingMessageIds.remove(id) } }
            finishLoading(sessionId, requestId)
            withState {
                if (chatAiJobs[sessionId] === ownJob) {
                    chatAiJobs.remove(sessionId)
                    activeReplySourceIds.remove(sessionId)
                }
            }
            if (mutexLocked) aiMutexFor(sessionId).unlock()
        }

        if (semantics.cancelSkipsDbWrites && cancelledByHost) {
            // D1 基线（Android oracle 实测）：取消发生在已取消的协程里，catch/finally 的 DB 写入被跳过，
            // 用户消息保持 text、租约保持 running（不可 reconcile 的「假在飞」）。宿主侧清理不是 DB 写入，
            // 必须在 NonCancellable 中执行，否则 loading/回调会丢失（基线也会正常结束回调 false）。
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                cleanupHostSide()
                onResponseComplete(responseStored)
            }
        } else {
            releaseTurnIfNeeded()
            cleanupHostSide()
            onResponseComplete(responseStored)
        }
    }

    /** oracle :1147-1203 —— 模型请求 + 内容重试 1 次（剩余 <5s 跳过）。 */
    private suspend fun generateCompleteReply(
        messages: List<AiMessage>,
        mode: String,
        turnBudgetMs: Long,
    ): OfflineModeResponse {
        val modelBudgetMs = config.effectiveModelBudgetMs // oracle :1158 进轮固化
        val startedAt = timeSource.markNow()
        fun remainingBudget(maxStepMillis: Long): Long =
            (turnBudgetMs - startedAt.elapsedNow().inWholeMilliseconds).coerceAtMost(maxStepMillis).coerceAtLeast(1L)

        suspend fun request(stage: String, requestMessages: List<AiMessage>): String =
            withChatStageTimeout("private", stage, remainingBudget(modelBudgetMs)) {
                aiClient.complete(requestMessages, stage)
            }

        fun parseVisible(raw: String): OfflineModeResponse? = runCatching {
            VisibleReplyContract.ensureVisiblePrivateReply(codec.normalize(raw), mode)
                .takeIf { VisibleReplyContract.isCompletePrivateReply(it, mode) }
        }.getOrNull()

        val firstRawText = request("model_primary_request", messages)
        parseVisible(firstRawText)?.let { firstParsed ->
            // 段数不符合设置时做一次有界纠错重试（原版只记日志；iOS 侧补强，保证「段数设置」真正生效）
            val issues = VisibleReplyContract.privateReplyStructureIssues(
                firstParsed, mode,
                settings.diaSegMin, settings.diaSegMax,
                settings.narSegMin, settings.narSegMax,
            )
            val countIssue = issues.any { it.startsWith("台词段数") || it.startsWith("旁白段数") }
            if (!countIssue || remainingBudget(modelBudgetMs) < config.contentRetryMinRemainingMs) {
                return firstParsed
            }
            val corrective = buildStructureRetryInstruction(mode)
            val retryMessages = messages.mapIndexed { index, message ->
                if (index == 0 && message.role == "system") {
                    message.copy(content = message.content + "\n\n" + corrective)
                } else message
            }
            val retryParsed = runCatching { request("model_structure_retry", retryMessages) }
                .getOrNull()
                ?.let { parseVisible(it) }
            return retryParsed ?: firstParsed
        }

        // oracle :1186-1189 —— 剩余整轮预算不足 5s 跳过内容重试。
        if (remainingBudget(modelBudgetMs) < config.contentRetryMinRemainingMs) {
            throw InvalidModelResponseException()
        }
        val retryMessages = messages.mapIndexed { index, message -> // oracle :1190-1193
            if (index == 0 && message.role == "system") {
                message.copy(content = message.content + "\n\n【重新生成要求】上一版无法展示。请至少输出一条角色台词；可使用【台词】标签或直接输出自然台词。不要输出 JSON、Markdown、工具调用、空内容或仅旁白。")
            } else message
        }
        val retryRawText = request("model_content_retry", retryMessages)
        parseVisible(retryRawText)?.let { return it }
        throw InvalidModelResponseException() // oracle :1202
    }

    /** 段数纠错指令：把本轮实际设置写清楚，要求重写且只输出规定段数。 */
    private fun buildStructureRetryInstruction(mode: String): String {
        val diaMin = settings.diaSegMin
        val diaMax = settings.diaSegMax
        val narMin = settings.narSegMin
        val narMax = settings.narSegMax
        val diaChars = "${settings.diaMin}~${settings.diaMax}字"
        val narChars = "${settings.narMin}~${settings.narMax}字"
        return if (mode == "online") {
            "【段数纠错要求】上一版台词段数不符合设置。本轮必须严格输出 $diaMin~$diaMax 段【台词】（每段 $diaChars），" +
                "每段台词前单独写一次【台词】标签，一个标签只能写一段正文；不得用换行或空行伪造段数；线上模式禁止输出【旁白】。"
        } else {
            "【段数纠错要求】上一版段数不符合设置。本轮必须严格输出 $narMin~$narMax 段【旁白】（每段 $narChars）" +
                "和 $diaMin~$diaMax 段【台词】（每段 $diaChars）；每段前单独写对应标签，一个标签只能写一段正文；" +
                "第一段为【旁白】，最后一段为【台词】。"
        }
    }

    /** oracle :1281-1296 —— 未送达标记（内存层由宿主 flow 同步，域层只落库 + 发事件）。 */
    private suspend fun markPrivateMessagesUndelivered(sessionId: String, messageIds: Set<Long>) {
        if (messageIds.isEmpty()) return
        val undeliveredMessages = store.getMessagesSync(sessionId).filter { it.id in messageIds }
        undeliveredMessages.forEach { message ->
            store.updateMessageType(message.id, if (message.type == "gift_hidden") "gift_reply_failed" else "send_failed")
        }
        events.emit(EngineEvent.Undelivered(sessionId, undeliveredMessages.map { it.id }.toSet()))
    }

    /** oracle :1268-1279 —— 失败系统消息（撤回源跳过）。 */
    private suspend fun savePrivateFailure(sessionId: String, messageId: Long, mode: String, error: Throwable? = null) {
        if (messageId <= 0L) return
        if (withState { messageId in recalledSourceMessageIds }) return
        store.sendMessage(
            sessionId,
            ChatMessage(
                id = messageId, sessionId = sessionId, senderName = "系统",
                content = FailureMessages.classify(error), type = "system", mode = mode, isMe = false,
            ),
        )
        events.emit(EngineEvent.FailureMessageStored(sessionId, messageId))
    }

    /** oracle :1593-1613 —— 轮次状态结算（缺失字段沿用上一轮）。 */
    private fun buildNextTurnState(previous: PrivateTurnState?, parsed: OfflineModeResponse, now: Long): PrivateTurnState {
        val prev = previous ?: PrivateTurnState()
        return PrivateTurnState(
            emotion = parsed.emotion.ifBlank { prev.emotion },
            location = parsed.location.ifBlank { prev.location },
            activity = parsed.state.ifBlank { prev.activity },
            updatedAt = now,
            currentTopic = parsed.continuity.ifBlank { prev.currentTopic },
            unresolvedThread = when (parsed.turnStatus) {
                "已收束", "已转题" -> "无"
                else -> parsed.turnUnresolved.take(160).ifBlank { prev.unresolvedThread }
            },
            pendingAction = when (parsed.turnStatus) {
                "已收束", "已转题" -> "无"
                else -> parsed.turnAdvance.take(160).ifBlank { prev.pendingAction }
            },
            userIntentAnalysis = "",
            userTurnType = parsed.turnUserType.take(16),
            currentAnchor = parsed.turnAnchor.take(160),
            turnAdvance = parsed.turnAdvance.take(160),
            threadStatus = parsed.turnStatus.take(16),
        )
    }

    // === 图片消息（oracle sendImageMessage :1819-2006）===

    /** 发送图片消息；guard 拒绝返回 null。 */
    fun sendImageMessage(
        imageUri: String,
        imageForModel: String?,
        caption: String = "",
        existingMessageId: Long? = null,
        replyLeaseTokenOverride: String? = null,
        session: ChatSession,
        mode: String,
        onReplyComplete: (Boolean) -> Unit = {},
    ): Job? {
        if (!visionConfigured()) {
            events.emit(EngineEvent.Toast("图片聊天需要先设置识图模型，请在模型设置中填写识图地址、模型名和密钥。")) // oracle :1832-1835
            return null
        }
        if (imageForModel.isNullOrBlank()) {
            events.emit(EngineEvent.Toast("无法读取这张图片，请重新选择后再试。")) // oracle :1837-1841
            return null
        }
        val requestId = newRequestId()
        val generation = generationOf(session.id)
        return scope.launch {
            runImagePipeline(
                imageUri = imageUri,
                imageForModel = imageForModel,
                caption = caption,
                existingMessageId = existingMessageId,
                replyLeaseTokenOverride = replyLeaseTokenOverride,
                session = session,
                mode = mode,
                generation = generation,
                requestId = requestId,
                onReplyComplete = onReplyComplete,
            )
        }
    }

    private suspend fun runImagePipeline(
        imageUri: String,
        imageForModel: String,
        caption: String,
        existingMessageId: Long?,
        replyLeaseTokenOverride: String?,
        session: ChatSession,
        mode: String,
        generation: Long,
        requestId: Long,
        onReplyComplete: (Boolean) -> Unit,
    ): Boolean {
        var imageMsgId = 0L
        var replyTurnId = ""
        var replyLeaseToken = ""
        var imagePersisted = false
        var mutexLocked = false
        val turnBudgetMs = config.effectiveTurnBudgetMs
        try {
            // 1. 占位落库（oracle :1856-1881）。
            imageMsgId = existingMessageId ?: withTimeout(config.messageWriteBudgetMs) { allocator.allocate() }
            val placeholderJson = ImageMessageContract.placeholder(imageUri, caption)
            replyTurnId = TurnStateMachine.turnId(session.id, imageMsgId)
            val turnNow = clock.nowMillis()
            if (existingMessageId == null) {
                withTimeout(config.messageWriteBudgetMs) {
                    store.sendMessageAndCreateReplyTurn(
                        session.id,
                        ChatMessage(id = imageMsgId, sessionId = session.id, senderName = "我", content = placeholderJson, type = "image", mode = mode, timestamp = turnNow, isMe = true),
                        ReplyTurn(replyTurnId, session.id, "private", "image", imageMsgId, "", mode, "pending", 0, turnNow, "", 0, null, "", turnNow, turnNow, 0),
                    )
                }
            } else {
                if (store.getTurn(replyTurnId) == null) {
                    store.createTurnIfAbsent(ReplyTurn(replyTurnId, session.id, "private", "image", imageMsgId, "", mode, "pending", 0, turnNow, "", 0, null, "", turnNow, turnNow, 0))
                }
                withTimeout(config.messageWriteBudgetMs) { store.updateMessageContent(imageMsgId, placeholderJson) }
            }
            imagePersisted = true
            replyLeaseToken = replyLeaseTokenOverride ?: newLeaseToken()
            if (replyLeaseTokenOverride == null) {
                val claim = turns.claim(replyTurnId, replyLeaseToken, clock.nowMillis(), clock.nowMillis() + turnBudgetMs)
                if (claim is TurnStateMachine.ClaimResult.Denied) {
                    events.emit(EngineEvent.ClaimDenied(session.id, imageMsgId, replyTurnId))
                    return false
                }
            }
            events.emit(EngineEvent.UnhideSession(session.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            events.emit(EngineEvent.Toast(if (imagePersisted) "图片已保存，回复暂时失败" else "图片未发送，请重试")) // oracle :1887
            onReplyComplete(false)
            return false
        }
        beginLoading(session.id, requestId)
        try {
            val aiMutex = aiMutexFor(session.id)
            aiMutex.lock()
            mutexLocked = true
            if (generationOf(session.id) != generation) return false
            // 2. 识图（oracle :1899-1933）。
            val visionText = try {
                visionAnalyze(imageForModel)?.take(2000)?.ifBlank { null }
                    ?: throw IllegalStateException("没有识别到图片内容")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generationOf(session.id) != generation) return false
                withTimeout(config.messageWriteBudgetMs) {
                    store.updateMessageContent(imageMsgId, ImageMessageContract.failed(imageUri, caption))
                }
                events.emit(EngineEvent.Toast("图片已保留，但识别失败。请检查识图模型后重新发送。")) // oracle :1929
                onReplyComplete(false)
                return false
            }
            // 3. visionSummary 回填（≤800 字，oracle :1936-1942）。
            withTimeout(config.messageWriteBudgetMs) {
                store.updateMessageContent(imageMsgId, ImageMessageContract.withVisionSummary(imageUri, caption, visionText))
            }
            val cleanText = visionText.trim().removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
            val visionSummary = runCatching {
                (engineJson.parseToJsonElement(cleanText) as? kotlinx.serialization.json.JsonObject)
                    ?.get("visibleSummary") as? kotlinx.serialization.json.JsonPrimitive
            }.getOrNull()?.content?.take(500)
            val userContent = buildString { // oracle :1952-1958
                append("用户发送了一张图片。")
                if (caption.isNotBlank()) append("\n用户附带文字：${caption.trim()}")
                append("\n【用户发送的图片分析】")
                append("\n画面内容：${visionSummary ?: visionText.take(500)}")
                append("\n请你作为当前角色自然回应这张图片和用户的话，不要像识图工具一样机械描述。")
            }
            val apiMessages = assembler.build(
                PromptBuildRequest(session = session, userContent = userContent, historyLimitOverride = settings.historyMessages, mode = mode),
            )
            val parsed = generateCompleteReply(apiMessages, mode, turnBudgetMs)
            val rawJson = codec.cleanJson(runCatching { engineJson.encodeToString(OfflineModeResponse.serializer(), parsed) }.getOrElse { parsed.toString() })
            val aiMsgId = withTimeout(config.messageWriteBudgetMs) { allocator.allocate() }
            val hasVisibleReply = rawJson.isNotBlank() && VisibleReplyContract.isCompletePrivateReply(parsed, mode)
            if (hasVisibleReply) {
                if (generationOf(session.id) != generation) return false
                withChatStageTimeout("private", "ai_reply_write", minOf(config.messageWriteBudgetMs, turnBudgetMs)) {
                    check(store.sendReplyAndCompleteTurn(
                        session.id,
                        ChatMessage(id = aiMsgId, sessionId = session.id, senderName = session.operatorName, content = rawJson, type = "ai_json", mode = mode, isMe = false),
                        replyTurnId, replyLeaseToken, clock.nowMillis(),
                    )) { "reply turn lease lost" }
                }
                events.emit(EngineEvent.ReplyStored(
                    session.id, setOf(imageMsgId), aiMsgId,
                    ReplyDiagnostics(
                        mode = mode,
                        promptMessageCount = apiMessages.size,
                        historyMessageCount = settings.historyMessages,
                        contentRetryCount = 0,
                        segmentCount = parsed.segments.orEmpty().size,
                        visibleCount = VisibleReplyContract.visiblePrivateSegmentCount(parsed, mode),
                        protocolComplete = VisibleReplyContract.isCompletePrivateReply(parsed, mode),
                    ),
                ))
                events.emit(EngineEvent.UnhideSession(session.id))
                events.emit(EngineEvent.UnreadHint(session.id, VisibleReplyContract.visiblePrivateSegmentCount(parsed, mode)))
            } else {
                savePrivateFailure(session.id, aiMsgId, mode)
            }
            onReplyComplete(hasVisibleReply)
            return hasVisibleReply
        } catch (e: CancellationException) {
            // oracle :1980-1990 —— 取消时图片 JSON 置 failed。
            if (imageMsgId != 0L) {
                runCatching { store.updateMessageContent(imageMsgId, ImageMessageContract.failed(imageUri, caption)) }
            }
            throw e
        } catch (e: Exception) {
            // oracle :1991-1996 —— 失败系统消息。
            val errId = runCatching { allocator.allocate() }.getOrDefault(0L)
            if (errId > 0L) savePrivateFailure(session.id, errId, mode, e)
            onReplyComplete(false)
            return false
        } finally {
            if (replyTurnId.isNotBlank() && replyLeaseToken.isNotBlank()) {
                val now = clock.nowMillis()
                runCatching { turns.release(replyTurnId, replyLeaseToken, now, now, "image_reply_not_completed") }
            }
            if (mutexLocked) aiMutexFor(session.id).unlock()
            finishLoading(session.id, requestId)
            val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            withState {
                if (chatAiJobs[session.id] === currentJob) chatAiJobs.remove(session.id)
            }
        }
    }

    // === 撤回 / 重试 / 重新生成 / 续写 ===

    /**
     * oracle retryFailedMessage :2150-2170 —— 仅 send_failed/gift_reply_failed，防抖去重。
     * 域层做成 suspend（iOS facade 直接 await；Android 迁移时映射回 VM 的 fire-and-forget）。
     */
    suspend fun retryFailedMessage(sessionId: String, msgId: Long): Boolean {
        val acquired = withState {
            if (msgId in retryingMessageIds) false else {
                retryingMessageIds.add(msgId)
                true
            }
        }
        if (!acquired) return false
        try {
            val message = store.getMessageById(msgId)
            val session = store.getSession(sessionId)
            if (message == null || session == null || !message.isMe ||
                (message.type != "send_failed" && message.type != "gift_reply_failed")
            ) {
                withState { retryingMessageIds.remove(msgId) }
                return false
            }
            val isGift = message.type == "gift_reply_failed"
            val deferred = CompletableDeferred<Boolean>()
            val job = sendMessage(
                textOverride = if (isGift) PrivateReplyFormat.giftPromptText(message.content) else message.content,
                session = session,
                mode = message.mode,
                retryMessageId = message.id,
                messageTypeOverride = if (isGift) "gift_hidden" else null,
                onResponseComplete = { deferred.complete(it) },
            )
            if (job == null) {
                withState { retryingMessageIds.remove(msgId) }
                return false
            }
            return deferred.await()
        } catch (e: CancellationException) {
            withState { retryingMessageIds.remove(msgId) }
            throw e
        }
    }

    /** oracle recallMessage :2083-2114 —— 取消在飞 job、turn 终态 cancelled、删行、会话预览更新。 */
    fun recallMessage(sessionId: String, msgId: Long, segmentIndex: Int? = null): Job? = scope.launch {
        val message = store.getMessageById(msgId) ?: return@launch
        withState {
            recalledSourceMessageIds.add(msgId)
            pendingUserMessageIds[sessionId]?.remove(msgId)
            if (activeReplySourceIds[sessionId] == msgId) {
                chatAiJobs.remove(sessionId)?.cancel(CancellationException("source message recalled"))
                promptBuildJobs.remove(sessionId)?.cancel()
                activeRequestBySession.remove(sessionId)
                _loadingSessions.value = _loadingSessions.value - sessionId
            }
        }
        try {
            turns.cancelBySource(sessionId, msgId)
            store.deleteMessage(msgId)
            val latest = store.getRecentMessagesSync(sessionId, 1).firstOrNull()
            store.updateLastMessage(
                sessionId,
                latest?.let { PrivateReplyFormat.messagePreviewForSession(it, codec) }.orEmpty(),
                latest?.timestamp ?: clock.nowMillis(),
            )
        } finally {
            withState { recalledSourceMessageIds.remove(msgId) }
        }
    }

    /** oracle recallMessageSegment :2173-2190 —— 非法参数退化整条撤回。 */
    fun recallMessageSegment(sessionId: String, msgId: Long, segmentIndex: Int): Job? = scope.launch {
        val message = store.getMessageById(msgId)
        if (message == null || message.type != "ai_json" || segmentIndex < 0) {
            recallMessage(sessionId, msgId)?.join() // 非法参数退化整条撤回（同步等待其完成）
            return@launch
        }
        val updated = ReplySegmentMutation.markSegmentRecalled(message.content, segmentIndex)
        if (updated == null) {
            recallMessage(sessionId, msgId)?.join()
            return@launch
        }
        store.updateMessageContentAndPreview(sessionId, msgId, updated, message.timestamp)
        store.deleteDisplayEvent(msgId, segmentIndex)
    }

    /** oracle regenerateAiMessage :2363-2421 —— replaceReplyIfUnchanged 乐观锁；失败保留原回复。 */
    fun regenerateAiMessage(sessionId: String, msgId: Long): Job? {
        val requestId = newRequestId()
        val generation = generationOf(sessionId)
        return scope.launch {
            val session = store.getSession(sessionId) ?: return@launch
            val originalReply = store.getMessageById(msgId) ?: return@launch
            val previousReply = originalReply.content
            val mode = originalReply.mode.ifBlank { settings.getString("last_mode_${session.operatorId}", "online") }
            withState { chatAiJobs.remove(sessionId)?.cancel() }
            var mutexLocked = false
            try {
                beginLoading(sessionId, requestId)
                val aiMutex = aiMutexFor(sessionId)
                aiMutex.lock()
                mutexLocked = true
                // oracle :2457-2460（regenerate 版：重说任务最高优先级，注入 TRANSITION_NOTICE）。
                settings.putPendingPrivateModeTransition(
                    sessionId,
                    "【重说任务 · 最高优先级】\n用户要求你重新回答上一轮消息。禁止复刻、续写或默认承认上一版回复中的动作、状态、承诺、情绪或剧情结果；必须改变至少两项（回应重点、情绪走向、角色动作、台词信息或场景细节）；不要提到“重说”“上一版”。",
                )
                val apiMessages = assembler.build(
                    PromptBuildRequest(
                        session = session,
                        userContent = previousReply,
                        excludeMessageIds = setOf(msgId),
                        historyBeforeMessageId = msgId,
                        mode = mode,
                    ),
                )
                val parsed = generateCompleteReply(apiMessages, mode, config.effectiveTurnBudgetMs)
                val serializedJson = runCatching { engineJson.encodeToString(OfflineModeResponse.serializer(), parsed) }
                    .getOrElse { parsed.toString() }
                val rawJson = codec.cleanJson(serializedJson)
                if (rawJson.isNotBlank() && VisibleReplyContract.isCompletePrivateReply(parsed, mode)) {
                    // oracle :2395 —— 原回复被改/被撤回则不覆盖。
                    check(store.replaceReplyIfUnchanged(originalReply, rawJson)) { "原回复已被修改或撤回，本次重说结果未覆盖记录" }
                    store.deleteMessageDisplayEvents(originalReply.id)
                    settings.putPrivateTurnState(sessionId, buildNextTurnState(settings.getPrivateTurnState(sessionId), parsed, clock.nowMillis()))
                    events.emit(EngineEvent.TurnStateUpdated(sessionId))
                    events.emit(EngineEvent.UnreadHint(sessionId, 1))
                } else {
                    savePrivateFailure(sessionId, runCatching { allocator.allocate() }.getOrDefault(0L), mode)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // oracle :2413-2417 —— 失败保留原回复 + 系统失败消息。
                savePrivateFailure(sessionId, runCatching { allocator.allocate() }.getOrDefault(0L), mode, e)
            } finally {
                settings.clearPendingPrivateModeTransition(sessionId)
                finishLoading(sessionId, requestId)
                if (mutexLocked) aiMutexFor(sessionId).unlock()
            }
        }
    }

    /** oracle continueAiMessage :2443-2482 —— 追加新 ai_json 消息（新 msgId）；失败 → 系统消息。 */
    fun continueAiMessage(sessionId: String, msgId: Long): Job? {
        val requestId = newRequestId()
        return scope.launch {
            val session = store.getSession(sessionId) ?: return@launch
            val target = store.getMessageById(msgId) ?: return@launch
            val mode = target.mode.ifBlank { settings.getString("last_mode_${session.operatorId}", "online") }
            withState { chatAiJobs.remove(sessionId)?.cancel() }
            val aiMsgId = runCatching { allocator.allocate() }.getOrDefault(0L)
            var mutexLocked = false
            try {
                beginLoading(sessionId, requestId)
                val aiMutex = aiMutexFor(sessionId)
                aiMutex.lock()
                mutexLocked = true
                // oracle :2457 —— 继续指令注入。
                settings.putPendingPrivateModeTransition(
                    sessionId,
                    "【继续指令】只承接紧邻上一条角色回复尚未说完的内容、动作、情绪或问题；不要复述、总结、重新开场或换新话题。若上一条已自然结束，只补充一句与它直接相关的内容。",
                )
                val apiMessages = assembler.build(
                    PromptBuildRequest(session = session, userContent = "请自然地继续你上一条未说完的内容，不要重复已经说过的话。", mode = mode),
                )
                val parsed = generateCompleteReply(apiMessages, mode, config.effectiveTurnBudgetMs)
                val sanitizedParsed = VisibleReplyContract.sanitizePrivateResponse(parsed)
                val serializedJson = runCatching { engineJson.encodeToString(OfflineModeResponse.serializer(), sanitizedParsed) }.getOrNull()
                if (serializedJson != null && VisibleReplyContract.isCompletePrivateReply(sanitizedParsed, mode)) {
                    store.sendMessage(
                        session.id,
                        ChatMessage(id = aiMsgId, sessionId = session.id, senderName = session.operatorName, content = codec.cleanJson(serializedJson), type = "ai_json", mode = mode, emotion = sanitizedParsed.emotion, activity = sanitizedParsed.state, location = sanitizedParsed.location, isMe = false),
                    )
                    settings.putPrivateTurnState(sessionId, buildNextTurnState(settings.getPrivateTurnState(sessionId), sanitizedParsed, clock.nowMillis()))
                    events.emit(EngineEvent.TurnStateUpdated(sessionId))
                    events.emit(EngineEvent.UnhideSession(sessionId))
                    events.emit(EngineEvent.UnreadHint(sessionId, 1))
                } else {
                    savePrivateFailure(sessionId, aiMsgId, mode)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                savePrivateFailure(sessionId, aiMsgId, mode, e)
            } finally {
                settings.clearPendingPrivateModeTransition(sessionId)
                finishLoading(sessionId, requestId)
                if (mutexLocked) aiMutexFor(sessionId).unlock()
            }
        }
    }
}

/** ChatRepository → ChatStore 生产适配器（Phase 2 Android 迁移 / iOS 宿主接线用）。 */
class RepositoryChatStore(
    private val repository: ChatRepository,
) : ChatStore {
    override suspend fun getMessageById(id: Long) = repository.getMessageById(id)
    override suspend fun getMessagesSync(sessionId: String) = repository.getMessagesSync(sessionId)
    override suspend fun getRecentMessagesSync(sessionId: String, limit: Long) = repository.getRecentMessagesSync(sessionId, limit)
    override suspend fun getMessagesBefore(sessionId: String, beforeTimestamp: Long, beforeId: Long, limit: Long) =
        repository.getMessagesBefore(sessionId, beforeTimestamp, beforeId, limit)

    override suspend fun sendMessageAndCreateReplyTurn(sessionId: String, message: ChatMessage, turn: ReplyTurn) =
        repository.sendMessageAndCreateReplyTurn(sessionId, message, turn)

    override suspend fun sendMessage(sessionId: String, message: ChatMessage) = repository.sendMessage(sessionId, message)
    override suspend fun sendReplyAndCompleteTurn(sessionId: String, message: ChatMessage, turnId: String, leaseToken: String, now: Long) =
        repository.sendReplyAndCompleteTurn(sessionId, message, turnId, leaseToken, now)

    override suspend fun updateMessageType(id: Long, type: String) {
        repository.updateMessageType(id, type)
    }

    override suspend fun updateMessageContent(id: Long, content: String) {
        repository.updateMessageContent(id, content)
    }

    override suspend fun updateMessageContentAndPreview(sessionId: String, id: Long, content: String, timestamp: Long) {
        repository.updateMessageContentAndPreview(sessionId, id, content, timestamp)
    }

    override suspend fun deleteMessage(id: Long) {
        repository.deleteMessage(id)
    }

    override suspend fun replaceReplyIfUnchanged(original: ChatMessage, replacement: String): Boolean =
        repository.messages.replaceReplyIfUnchanged(original, replacement)

    override suspend fun updateLastMessage(sessionId: String, lastMessage: String, lastTime: Long) {
        repository.updateLastMessage(sessionId, lastMessage, lastTime)
    }

    override suspend fun getDisplayEvents(sessionId: String): List<ChatDisplayEventView> =
        repository.getDisplayEvents(sessionId).map { ChatDisplayEventView(it.messageId, it.segmentIndex, it.revealOrder) }

    override suspend fun deleteDisplayEvent(messageId: Long, segmentIndex: Int) {
        repository.deleteDisplayEvent(messageId, segmentIndex)
    }

    override suspend fun deleteMessageDisplayEvents(messageId: Long) {
        repository.deleteMessageDisplayEvents(messageId)
    }

    override suspend fun getSession(id: String) = repository.getSession(id)
    override suspend fun getOperator(id: String) = repository.getOperator(id)

    override suspend fun getTurn(id: String) = repository.replyTurns.get(id)
    override suspend fun getTurnBySource(sessionId: String, messageId: Long) = repository.replyTurns.getBySource(sessionId, messageId)
    override suspend fun createTurnIfAbsent(turn: ReplyTurn) = repository.replyTurns.createIfAbsent(turn)
    override suspend fun claimTurn(id: String, token: String, now: Long, leaseUntil: Long) = repository.replyTurns.claim(id, token, now, leaseUntil)
    override suspend fun completeTurn(id: String, token: String, now: Long) = repository.replyTurns.complete(id, token, now)
    override suspend fun isTurnOwned(id: String, token: String) = repository.replyTurns.isOwned(id, token)
    override suspend fun releaseTurn(id: String, token: String, retryAt: Long, now: Long, error: String) {
        repository.replyTurns.release(id, token, retryAt, now, error)
    }

    override suspend fun failTurn(id: String, token: String, now: Long, error: String) {
        repository.replyTurns.fail(id, token, now, error)
    }

    override suspend fun cancelTurn(id: String, error: String, now: Long) {
        repository.replyTurns.cancel(id, error, now)
    }

    override suspend fun cancelTurnBySource(sessionId: String, messageId: Long, error: String) {
        repository.replyTurns.cancelBySourceMessage(sessionId, messageId, error)
    }

    override suspend fun retryableTurnIds(since: Long, now: Long, limit: Long) =
        repository.replyTurns.retryableTurnIds(since, now, limit)

    override suspend fun cleanupOrphanedSucceededTurns(): Long =
        repository.replyTurns.cleanupOrphanedSucceededTurns()
}
