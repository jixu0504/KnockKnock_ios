package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.domain.chat.ChatStageTimeoutException
import com.rhodes.privatechat.shared.domain.chat.ChatStore
import com.rhodes.privatechat.shared.domain.chat.StreamingClient
import com.rhodes.privatechat.shared.domain.chat.SystemChatClock
import com.rhodes.privatechat.shared.domain.chat.ChatClock
import com.rhodes.privatechat.shared.domain.chat.TurnStateMachine
import com.rhodes.privatechat.shared.domain.chat.withChatStageTimeout
import com.rhodes.privatechat.shared.domain.data.MessageIdAllocator
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.GroupMsgResult
import com.rhodes.privatechat.shared.model.GroupTurnState
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * 群聊轮次引擎（规格卡 group-chat-engine-spec.md 20 条行为契约的域实现）。
 *
 * Android oracle：`app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt`
 * （T11 拆分后的结构：sendGroupMessage:817 → executeGroupTurn:937，内部 prepareTurn:1040 / runModelTurn:1514；
 *  解析与降级 :2099-2680；维护 :2693-2929）。
 *
 * 分层（同 T19 私聊引擎）：
 * - 内存层：groupGenerations / pendingUserMessageIds / groupAiJobs / loading / 自动 generation（本类）；
 * - 持久化租约层：reply_turns surface="group"、triggerKind ∈ {manual, gift, image, group_auto}（TurnStateMachine）；
 * - 提示词/上下文：GroupPromptAssembler（契约 10 注入顺序）+ GroupRuntimeContext（别名去重）；
 * - 恢复语义：iOS 无 WorkManager——启动 reconcile（onAppStartReconcile）+ 前台补算（resumePersistedReply），
 *   自动互动到期经通用 DelayedTaskLayer（契约见 T22 §A-12；通道适配归 T22 胶水批）。
 * - 群聊差异白名单：无重新生成/续写/存档/擦除，无亲密度/催眠结算；仅手动轮次每日龙门币 50。
 */
class GroupChatEngine(
    private val scope: CoroutineScope,
    private val store: ChatStore,
    private val extras: GroupChatExtras,
    private val allocator: MessageIdAllocator,
    private val settings: SettingsRepository,
    private val assembler: GroupPromptAssembler,
    private val aiClient: StreamingClient,
    private val contextSource: GroupContextSource = GroupContextSourceNoop,
    private val autoPlans: AutoChatPlanPort = DefaultAutoChatPlanPort(SettingsGroupAutoChatPlanStore(settings)),
    private val promptSource: GroupPromptSource = SettingsGroupPromptSource(settings),
    private val clock: ChatClock = SystemChatClock(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val config: GroupChatEngineConfig = GroupChatEngineConfig(),
    /** T35 D3 全员禁言对齐模式（默认 = 引擎既有行为，shared 既有群聊测试零改动）。 */
    private val semantics: GroupChatSemantics = GroupChatSemantics(),
    private val events: (GroupEngineEvent) -> Unit = {},
    private val visionConfigured: () -> Boolean = { false },
    private val visionAnalyze: suspend (imageForModel: String) -> String? = { null },
    private val imageForModelResolver: (uri: String) -> String? = { null },
    private val chatConfigError: () -> String? = { null },
) {
    private val turns = TurnStateMachine(store)

    // === 内存层状态（oracle :239-249） ===
    private val stateMutex = Mutex()
    private val _loadingGroups = MutableStateFlow<Set<String>>(emptySet())
    val loadingGroups: StateFlow<Set<String>> = _loadingGroups.asStateFlow()
    private val groupGenerations = mutableMapOf<String, Long>()
    private val pendingUserMessageIds = mutableMapOf<String, MutableSet<Long>>()
    private val retryingMessageIds = mutableSetOf<Long>()
    private val groupAiJobs = mutableMapOf<String, Job>()
    private val groupMutexes = mutableMapOf<String, Mutex>()
    private val autoChatGenerations = mutableMapOf<String, Long>()
    private val restartCleanupJobs = mutableMapOf<String, Job>()
    private var leaseTokenCounter = 0L
    private var autoRunCounter = 0L

    private fun emit(event: GroupEngineEvent) = events(event)

    private fun newLeaseToken(): String =
        "gleas-${++leaseTokenCounter}-${clock.nowMillis()}-${Random.nextInt(0xFFFF).toString(16)}"

    private suspend fun <T> withState(block: () -> T): T = stateMutex.withLock { block() }

    private fun generationOf(groupId: String): Long = groupGenerations[groupId] ?: 0L

    /** 非挂起快照（sendMessage guard 用；等价 oracle groupAiJobs 直接读）。 */
    private fun isGroupBusyNow(groupId: String): Boolean = groupAiJobs[groupId]?.isActive == true

    private suspend fun beginLoading(groupId: String) = withState {
        _loadingGroups.value = _loadingGroups.value + groupId
    }

    private suspend fun finishLoading(groupId: String) = withState {
        _loadingGroups.value = _loadingGroups.value - groupId
    }

    private suspend fun groupMutex(groupId: String): Mutex = withState {
        groupMutexes.getOrPut(groupId) { Mutex() }
    }

    private fun nextAutoGeneration(): Long = ++autoRunCounter

    private fun isAutoGroupChatEnabled(groupId: String): Boolean =
        settings.autoAiEnabled && settings.getGroupAuto(groupId)

    // === 取消语义（oracle :261-271） ===

    /** 取消本群在飞请求：generation+1 + 清 pending + 取消 job + auto generation 换代 + 清 loading。 */
    fun cancelGroupRequests(groupId: String) {
        scope.launch {
            val (aiJob, cleanupJob) = withState {
                groupGenerations[groupId] = (groupGenerations[groupId] ?: 0L) + 1L
                pendingUserMessageIds.remove(groupId)
                _loadingGroups.value = _loadingGroups.value - groupId
                autoChatGenerations[groupId] = nextAutoGeneration()
                Pair(groupAiJobs.remove(groupId), restartCleanupJobs.remove(groupId))
            }
            aiJob?.cancel()
            cleanupJob?.cancel()
            emit(GroupEngineEvent.LoadingChanged(groupId, false))
        }
    }

    /** 会话切换/重启时使在飞轮次失效（不取消 job，由管线的 generation 复核拦截写入）。 */
    fun invalidateGroup(groupId: String) {
        scope.launch { withState { groupGenerations[groupId] = (groupGenerations[groupId] ?: 0L) + 1L } }
    }

    // === 发送主流程（oracle sendGroupMessage :817-930 → executeGroupTurn :937-1846） ===

    /** 发送群消息；guard 拒绝返回 null。返回 pipeline Job。 */
    fun sendGroupMessage(
        groupId: String,
        groupName: String,
        text: String,
        mode: String = "online",
        autoSpeak: Boolean = false,
        isAuto: Boolean = false,
        autoGeneration: Long? = null,
        autoPlanToken: String? = null,
        autoPlanRevision: Long? = null,
        userMessageAlreadyStored: Boolean = false,
        sourceMessageId: Long? = null,
        retryMessageId: Long? = null,
        replyTurnIdOverride: String? = null,
        replyLeaseTokenOverride: String? = null,
        onReplyTurnClaimed: suspend () -> Unit = {},
        onMessageSent: () -> Unit = {},
        onResponseComplete: (Boolean) -> Unit = {},
    ): Job? {
        // oracle :822-824 —— auto 守卫。
        if (isAuto && !isAutoGroupChatEnabled(groupId)) {
            onResponseComplete(false); return null
        }
        if (isAuto && autoPlanToken != null && autoPlanRevision != null &&
            !autoPlans.isCurrentClaim(groupId, autoPlanToken, autoPlanRevision)
        ) {
            onResponseComplete(false); return null
        }
        if (isAuto && isGroupBusyNow(groupId)) {
            onResponseComplete(false); return null
        }
        // oracle :826-832 —— 非 auto 且需要写用户消息时校验配置。
        if (!isAuto && !userMessageAlreadyStored && text.isNotBlank()) {
            chatConfigError()?.let { error ->
                emit(GroupEngineEvent.Toast(error))
                onResponseComplete(false)
                return null
            }
        }
        val generation = generationOf(groupId)
        return scope.launch {
            runTurn(
                groupId = groupId,
                groupName = groupName,
                text = text,
                mode = mode,
                autoSpeak = autoSpeak,
                isAuto = isAuto,
                autoGeneration = autoGeneration,
                autoPlanToken = autoPlanToken,
                autoPlanRevision = autoPlanRevision,
                userMessageAlreadyStored = userMessageAlreadyStored,
                sourceMessageId = sourceMessageId,
                retryMessageId = retryMessageId,
                replyTurnIdOverride = replyTurnIdOverride,
                replyLeaseTokenOverride = replyLeaseTokenOverride,
                generation = generation,
                onReplyTurnClaimed = onReplyTurnClaimed,
                onMessageSent = onMessageSent,
                onResponseComplete = onResponseComplete,
            )
        }
    }

    private suspend fun runTurn(
        groupId: String,
        groupName: String,
        text: String,
        mode: String,
        autoSpeak: Boolean,
        isAuto: Boolean,
        autoGeneration: Long?,
        autoPlanToken: String?,
        autoPlanRevision: Long?,
        userMessageAlreadyStored: Boolean,
        sourceMessageId: Long?,
        retryMessageId: Long?,
        replyTurnIdOverride: String?,
        replyLeaseTokenOverride: String?,
        generation: Long,
        onReplyTurnClaimed: suspend () -> Unit,
        onMessageSent: () -> Unit,
        onResponseComplete: (Boolean) -> Unit,
    ) {
        var userMessageId: Long? = null
        var replyTurnId = ""
        var replyLeaseToken = ""
        var failureMessageId: Long? = retryMessageId ?: sourceMessageId

        // === 步骤1：用户消息同步落库（oracle :852-918） ===
        try {
            val cleanup = withState { restartCleanupJobs[groupId] }
            if (cleanup?.isActive == true) {
                withTimeoutOrNull(config.restartCleanupJoinMillis) { cleanup.join() }
            }
            if (!isAuto && !userMessageAlreadyStored && text.isNotBlank()) {
                val userMsgId = withTimeout(config.messageWriteBudgetMs) { allocator.allocate() }
                userMessageId = userMsgId
                failureMessageId = userMsgId
                val timestamp = clock.nowMillis()
                replyTurnId = "group:$groupId:$userMsgId"
                val turnNow = clock.nowMillis()
                withTimeout(config.messageWriteBudgetMs) {
                    store.sendMessageAndCreateReplyTurn(
                        groupId,
                        ChatMessage(
                            id = userMsgId, sessionId = groupId, senderName = "我", content = text,
                            type = "text", mode = mode, timestamp = timestamp, isMe = true,
                        ),
                        ReplyTurn(
                            replyTurnId, groupId, "group", "manual", userMsgId, "", mode,
                            "pending", 0, turnNow, "", 0, null, "", turnNow, turnNow, 0,
                        ),
                    )
                }
                onMessageSent()
                withState { pendingUserMessageIds.getOrPut(groupId) { mutableSetOf() }.add(userMsgId) }
                resetAutoGroupChatTimer(groupId)
                emit(GroupEngineEvent.UnhideSession(groupId))
            }
        } catch (e: TimeoutCancellationException) {
            // 写超时是 CancellationException，必须先于取消分支处理（oracle :897-906）。
            emit(GroupEngineEvent.Toast("群消息保存超时，请稍后重试"))
            onResponseComplete(false)
            return
        } catch (e: CancellationException) {
            onResponseComplete(false)
            throw e
        } catch (e: Exception) {
            emit(GroupEngineEvent.Toast("群消息保存失败，请重试"))
            onResponseComplete(false)
            return
        }

        // === 步骤2：整轮执行体（oracle executeGroupTurn :937-1846） ===
        try {
            val responseStored = executeGroupTurn(
                groupId = groupId,
                groupName = groupName,
                text = text,
                mode = mode,
                autoSpeak = autoSpeak,
                isAuto = isAuto,
                autoGeneration = autoGeneration,
                autoPlanToken = autoPlanToken,
                autoPlanRevision = autoPlanRevision,
                userMessageAlreadyStored = userMessageAlreadyStored,
                sourceMessageId = sourceMessageId,
                retryMessageId = retryMessageId,
                replyTurnIdOverride = replyTurnIdOverride,
                replyLeaseTokenOverride = replyLeaseTokenOverride,
                generation = generation,
                userMessageId = userMessageId,
                replyTurnId = replyTurnId,
                replyLeaseToken = replyLeaseToken,
                failureMessageId = failureMessageId,
                onReplyTurnClaimed = onReplyTurnClaimed,
                onResponseComplete = onResponseComplete,
            )
            onResponseComplete(responseStored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(GroupEngineEvent.Toast(GroupReplyParser.classifyGroupError(e)))
            onResponseComplete(false)
        }
    }

    /** 轮次执行期可变状态（JVM 64KB 方法上限拆分：oracle T11 的 prepareTurn/runModelTurn 同款）。 */
    private class TurnRuntime(
        var turnId: String,
        var leaseToken: String,
        var failureMessageId: Long?,
    ) {
        var batchIds: Set<Long> = emptySet()
        var responseStored: Boolean = false
        var turnReleased: Boolean = false
        var mutexLocked: Boolean = false
    }

    private data class ParsedGroupReply(val raw: String, val results: List<GroupMsgResult>)

    /**
     * 整轮执行体（oracle executeGroupTurn :937-1846）。
     * 为 JVM 64KB 方法上限拆为 prepare（租约/锁/批次/提示词）→ request（模型/解析）→ persist（落库/结算）。
     */
    private suspend fun executeGroupTurn(
        groupId: String,
        groupName: String,
        text: String,
        mode: String,
        autoSpeak: Boolean,
        isAuto: Boolean,
        autoGeneration: Long?,
        autoPlanToken: String?,
        autoPlanRevision: Long?,
        userMessageAlreadyStored: Boolean,
        sourceMessageId: Long?,
        retryMessageId: Long?,
        replyTurnIdOverride: String?,
        replyLeaseTokenOverride: String?,
        generation: Long,
        userMessageId: Long?,
        replyTurnId: String,
        replyLeaseToken: String,
        failureMessageId: Long?,
        onReplyTurnClaimed: suspend () -> Unit,
        onResponseComplete: (Boolean) -> Unit,
    ): Boolean {
        val runtime = TurnRuntime(replyTurnId, replyLeaseToken, failureMessageId)
        val turnBudgetMs = config.effectiveTurnBudgetMs // 进轮固化（oracle :986）
        val turnStartedAt = timeSource.markNow()
        val elapsed: () -> Long = { turnStartedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L) }
        val remaining: () -> Long = { (turnBudgetMs - elapsed()).coerceAtLeast(1L) }
        try {
            val prompt = prepareGroupTurn(
                groupId, groupName, text, mode, autoSpeak, isAuto, autoGeneration,
                userMessageAlreadyStored, sourceMessageId, retryMessageId,
                replyTurnIdOverride, replyLeaseTokenOverride, generation, userMessageId,
                runtime, elapsed, remaining, turnBudgetMs, onReplyTurnClaimed,
            ) ?: return false
            val parsed = requestGroupReply(groupId, groupName, mode, text, isAuto, autoGeneration, generation, prompt, runtime, remaining)
                ?: return false
            return persistGroupReply(groupId, groupName, mode, text, isAuto, generation, autoGeneration, prompt, parsed, runtime, remaining)
        } catch (e: GroupMembersUnresolvedException) {
            // 不燃尽 turn：release + 5s 后由恢复补答（oracle :1142-1154）。
            val now = clock.nowMillis()
            runCatching { turns.release(runtime.turnId, runtime.leaseToken, now + 5_000L, now, "group_members_unresolved") }
            runtime.turnReleased = true
            emit(GroupEngineEvent.Toast("群成员资料尚未同步，请稍后重试"))
            return false
        } catch (e: GroupAllMutedException) {
            // 全员禁言：系统消息 + complete turn（oracle :1155-1167）。
            val sysId = runCatching { allocator.allocate() }.getOrDefault(0L)
            if (sysId > 0L) {
                runCatching {
                    store.sendMessage(
                        groupId,
                        ChatMessage(
                            id = sysId, sessionId = groupId, senderName = "系统",
                            content = "所有成员已被禁言，无法回复", type = "system", mode = mode, isMe = false,
                        ),
                    )
                }
                emit(GroupEngineEvent.SystemMessageStored(groupId, sysId))
            }
            runCatching { turns.complete(runtime.turnId, runtime.leaseToken, clock.nowMillis()) }
            runtime.responseStored = true
            emit(GroupEngineEvent.Toast("所有群成员已被禁言，无法回复"))
            // D3（T35）：引擎默认无回复 → false；Android 基线把该轮视为已处理 → true。
            return if (semantics.allMutedReportsStored) runtime.responseStored else false
        } catch (e: GroupSessionUnavailableException) {
            emit(GroupEngineEvent.Toast("群聊尚未保存完成，请返回后重新进入群聊再发送"))
            return false
        } catch (e: ChatStageTimeoutException) {
            if (generationOf(groupId) != generation) return false
            if (isAuto && autoGeneration != null && withState { autoChatGenerations[groupId] } != autoGeneration) return false
            markGroupMessagesUndelivered(groupId, runtime.batchIds.ifEmpty { runtime.failureMessageId?.let(::setOf).orEmpty() })
            emit(GroupEngineEvent.Toast(e.userMessage("群聊")))
            return false
        } catch (e: TimeoutCancellationException) {
            if (generationOf(groupId) != generation) return false
            markGroupMessagesUndelivered(groupId, runtime.batchIds.ifEmpty { runtime.failureMessageId?.let(::setOf).orEmpty() })
            emit(GroupEngineEvent.Toast("群聊整轮处理超时（预算${turnBudgetMs / 1000}秒），本轮未生成或保存AI回复，请重试"))
            return false
        } catch (e: CancellationException) {
            markGroupMessagesUndelivered(groupId, runtime.batchIds.ifEmpty { runtime.failureMessageId?.let(::setOf).orEmpty() })
            throw e
        } catch (e: Exception) {
            if (generationOf(groupId) != generation) return false
            if (isAuto && autoGeneration != null && withState { autoChatGenerations[groupId] } != autoGeneration) return false
            markGroupMessagesUndelivered(groupId, runtime.batchIds.ifEmpty { runtime.failureMessageId?.let(::setOf).orEmpty() })
            emit(GroupEngineEvent.Toast(GroupReplyParser.classifyGroupError(e)))
            return false
        } finally {
            if (!runtime.responseStored && !runtime.turnReleased &&
                runtime.turnId.isNotBlank() && runtime.leaseToken.isNotBlank()
            ) {
                val now = clock.nowMillis()
                runCatching { turns.release(runtime.turnId, runtime.leaseToken, now, now, "reply_not_completed") }
            }
            finishLoading(groupId)
            val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            withState { if (groupAiJobs[groupId] === currentJob) groupAiJobs.remove(groupId) }
            if (runtime.mutexLocked) groupMutex(groupId).unlock()
        }
    }

    /** prepareTurn（oracle :1040-1513）：租约 claim → mutex → 防抖/批次合并 → 群资料注入与提示词组装。 */
    private suspend fun prepareGroupTurn(
        groupId: String,
        groupName: String,
        text: String,
        mode: String,
        autoSpeak: Boolean,
        isAuto: Boolean,
        autoGeneration: Long?,
        userMessageAlreadyStored: Boolean,
        sourceMessageId: Long?,
        retryMessageId: Long?,
        replyTurnIdOverride: String?,
        replyLeaseTokenOverride: String?,
        generation: Long,
        userMessageId: Long?,
        runtime: TurnRuntime,
        elapsed: () -> Long,
        remaining: () -> Long,
        turnBudgetMs: Long,
        onReplyTurnClaimed: suspend () -> Unit,
    ): GroupPromptBuildResult? {
        if (runtime.turnId.isBlank()) {
            runtime.turnId = replyTurnIdOverride
                ?: "group:$groupId:${retryMessageId ?: sourceMessageId ?: userMessageId ?: return null}"
        }
        runtime.leaseToken = replyLeaseTokenOverride ?: newLeaseToken()
        if (replyLeaseTokenOverride == null) {
            if (store.getTurn(runtime.turnId) == null) {
                val now = clock.nowMillis()
                store.createTurnIfAbsent(
                    ReplyTurn(
                        runtime.turnId, groupId, "group", "manual",
                        retryMessageId ?: sourceMessageId ?: userMessageId, "", mode,
                        "pending", 0, now, "", 0, null, "", now, now, 0,
                    ),
                )
            }
            val claim = turns.claim(runtime.turnId, runtime.leaseToken, clock.nowMillis(), clock.nowMillis() + turnBudgetMs)
            if (claim is TurnStateMachine.ClaimResult.Denied) {
                // oracle :1049-1056 —— 保留消息，恢复补答。
                emit(GroupEngineEvent.ClaimDenied(groupId, retryMessageId ?: sourceMessageId ?: userMessageId ?: 0L, runtime.turnId))
                emit(GroupEngineEvent.Toast("该消息正在恢复发送，请稍候"))
                return null
            }
            onReplyTurnClaimed()
        }
        groupMutex(groupId).lock()
        runtime.mutexLocked = true
        if (generationOf(groupId) != generation) return null
        if (isAuto && autoGeneration != null && withState { autoChatGenerations[groupId] } != autoGeneration) return null

        // 250ms 防抖 + pendingIds 复核 + 合并批次（oracle :1064-1107）。
        val requestText: String
        if (!isAuto) delay(config.debounceBeforeAiMillis)
        if (!isAuto && text.isNotBlank() && !userMessageAlreadyStored && userMessageId != null) {
            val pendingIds = withState { pendingUserMessageIds[groupId]?.toSet().orEmpty() }
            if (userMessageId !in pendingIds) {
                // 被并入其他轮次/被取消：turn 走 cancelled 终态，避免恢复 worker 二次作答（oracle G-02）。
                runCatching { turns.cancel(runtime.turnId, "merged_into_batch", clock.nowMillis()) }
                return null
            }
            val candidateIds = pendingIds.sorted().take(config.maxMergedUserMessages)
            if (candidateIds.firstOrNull() != userMessageId) {
                runCatching { turns.cancel(runtime.turnId, "merged_into_batch", clock.nowMillis()) }
                return null
            }
            val batch = candidateIds.mapNotNull { store.getMessageById(it)?.takeIf { row -> row.sessionId == groupId } }
                .asSequence()
                .filter { it.isMe && it.type == "text" && it.mode == mode }
                .filter { it.id in candidateIds }
                .sortedBy { it.id }
                .fold(mutableListOf<ChatMessage>()) { acc, message ->
                    if (acc.isEmpty() || acc.sumOf { it.content.length } + message.content.length <= config.maxMergedUserChars) acc += message
                    acc
                }
            val ids = batch.map { it.id }
            runtime.batchIds = ids.toSet()
            withState { pendingUserMessageIds[groupId]?.removeAll(runtime.batchIds) }
            requestText = if (ids.size > 1) {
                "用户连续补充了以下消息，请按顺序视为同一轮表达并综合回应：\n" +
                    batch.mapIndexed { index, message -> "[${index + 1}] ${message.content}" }.joinToString("\n")
            } else {
                text
            }
        } else {
            requestText = text
        }

        val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        withState { if (currentJob != null) groupAiJobs[groupId] = currentJob }
        beginLoading(groupId)

        // userMessage 注入文本（oracle :1304）。
        val userMessage = when {
            isAuto -> "（用户没有新发言。请只根据最近群聊自然延续话题，不要替用户发言。）"
            autoSpeak -> "（群聊已空闲一段时间，代理人自然地闲聊起来，无需等待用户发言。）"
            else -> requestText
        }

        // 群资料注入 + 提示词组装（契约 10/11，成员权限异常即 guards）。
        return withChatStageTimeout("group", "prompt_build", minOf(config.promptBuildBudgetMs, remaining())) {
            assembler.assemble(
                GroupPromptBuildRequest(
                    groupId = groupId,
                    groupName = groupName,
                    mode = mode,
                    isAuto = isAuto,
                    autoSpeak = autoSpeak,
                    requestText = requestText,
                    originalUserText = text,
                    userMessage = userMessage,
                    batchIds = runtime.batchIds,
                    retryMessageId = retryMessageId,
                    sourceMessageId = sourceMessageId,
                    userMessageAlreadyStored = userMessageAlreadyStored,
                    turnElapsedMs = elapsed,
                ),
            )
        }
    }

    /** runModelTurn（oracle :1514-1716）：上下文超限降级 ≤6 次 → 内容重试 1 次 → 三级解析。 */
    private suspend fun requestGroupReply(
        groupId: String,
        groupName: String,
        mode: String,
        text: String,
        isAuto: Boolean,
        autoGeneration: Long?,
        generation: Long,
        prompt: GroupPromptBuildResult,
        runtime: TurnRuntime,
        remaining: () -> Long,
    ): ParsedGroupReply? {
        val apiMessages = prompt.apiMessages.toMutableList()
        var remainingHistoryCount = prompt.remainingHistoryCount
        var rawBase: String
        var contextRetryCount = 0
        while (true) { // 上下文超限降级 ≤6 次、阶梯减半保最新 10（oracle :1648-1671）
            try {
                rawBase = generateGroupReply(apiMessages, stage = "model_primary_request", remainingTurnBudget = remaining)
                break
            } catch (error: Exception) {
                if (!com.rhodes.privatechat.shared.domain.chat.SettlementPolicy.isContextLimitError(error) ||
                    contextRetryCount >= config.maxContextRetries
                ) {
                    throw error
                }
                val historyCount = remainingHistoryCount
                if (historyCount == 0) throw error
                val targetHistoryCount = when {
                    historyCount > 20 -> (historyCount / 2).coerceAtLeast(GroupStageBudget.CONTEXT_RETRY_KEEP_LATEST)
                    historyCount > 10 -> GroupStageBudget.CONTEXT_RETRY_KEEP_LATEST
                    historyCount > 5 -> 5
                    else -> 1
                }
                val removeCount = (historyCount - targetHistoryCount).coerceAtLeast(1)
                repeat(removeCount) { if (apiMessages.size > 1) apiMessages.removeAt(1) }
                remainingHistoryCount -= removeCount
                contextRetryCount++
            }
        }
        var filtered = GroupReplyParser.parseGroupReply(rawBase, prompt.activeMembers, groupName, mode)
        if (filtered.isEmpty()) {
            // 内容重试：无条件一次（oracle :1677-1698，与私聊不同不设剩余预算门槛）。
            val retryMessages = apiMessages.mapIndexed { index, message ->
                if (index == 0 && message.role == "system") {
                    message.copy(content = message.content + GroupPromptTexts.contentRetryRequirement(mode))
                } else {
                    message
                }
            }
            rawBase = generateGroupReply(retryMessages, stage = "model_content_retry", remainingTurnBudget = remaining)
            filtered = GroupReplyParser.parseGroupReply(rawBase, prompt.activeMembers, groupName, mode)
        }
        if (filtered.isEmpty()) {
            if (generationOf(groupId) != generation) return null
            if (isAuto && autoGeneration != null && withState { autoChatGenerations[groupId] } != autoGeneration) return null
            markGroupMessagesUndelivered(groupId, runtime.batchIds.ifEmpty { runtime.failureMessageId?.let(::setOf).orEmpty() })
            return null
        }
        return ParsedGroupReply(rawBase, filtered)
    }

    /** 落库与结算（oracle :1717-1789）：租约写回 + 回合状态/主线 + 情绪快照 + 结算事件。 */
    private suspend fun persistGroupReply(
        groupId: String,
        groupName: String,
        mode: String,
        text: String,
        isAuto: Boolean,
        generation: Long,
        autoGeneration: Long?,
        prompt: GroupPromptBuildResult,
        parsed: ParsedGroupReply,
        runtime: TurnRuntime,
        remaining: () -> Long,
    ): Boolean {
        val filtered = parsed.results
        if (isAuto && autoGeneration != null && withState { autoChatGenerations[groupId] } != autoGeneration) return false
        if (generationOf(groupId) != generation) return false
        val aiMsgId = withChatStageTimeout(
            "group", "ai_reply_id", minOf(config.messageWriteBudgetMs, remaining()),
        ) { allocator.allocate() }
        val membersByName = prompt.activeMembers.groupBy { it.name }
        val storedResults = GroupReplyParser.enrichGroupSpeakerIds(filtered, membersByName)
        val storedContent = if (storedResults.isNotEmpty()) {
            GroupReplyParser.encodeStoredResults(storedResults)
        } else {
            parsed.raw
        }
        // 成员心情快照（oracle :1729-1753）：本轮解析 > 【成员心情】声明 > 6h 回退。
        val parsedTurnState = GroupTurnStatePolicy.parseGroupTurnState(parsed.raw)?.let(GroupTurnStatePolicy::validateGroupTurnState)
        val emotionResolvedAt = clock.nowMillis()
        val restartAt = settings.getSessionRestartAt(groupId)
        val roundEmotions = GroupEmotionRules.resolveRoundEmotions(
            nowMs = emotionResolvedAt,
            results = storedResults,
            declaredSnapshot = parsedTurnState?.memberEmotions.orEmpty(),
            previousSnapshot = prompt.groupTurnState?.memberEmotions.orEmpty(),
            previousSnapshotAtMs = prompt.groupTurnState?.updatedAt ?: 0L,
            previousInheritedKeys = prompt.groupTurnState?.emotionFallbackKeys.orEmpty(),
            history = GroupReplyParser.trackedEmotionsFromHistory(
                store.getMessagesSync(groupId), restartAt, excludeMessageId = aiMsgId,
            ),
        )
        val verifiedState = (parsedTurnState
            ?: GroupTurnStatePolicy.deriveGroupTurnState(userMsg = if (isAuto) "" else text, previous = prompt.groupTurnState))
            .copy(
                memberEmotions = GroupEmotionRules.formatEmotionSnapshot(roundEmotions.emotions),
                emotionFallbackKeys = GroupEmotionRules.formatKeyList(roundEmotions.inheritedKeys),
            )
        withChatStageTimeout("group", "ai_reply_write", minOf(config.messageWriteBudgetMs, remaining())) {
            check(
                store.sendReplyAndCompleteTurn(
                    groupId,
                    ChatMessage(
                        id = aiMsgId, sessionId = groupId, senderName = groupName, content = storedContent,
                        type = "ai_json", mode = mode, isMe = false,
                    ),
                    runtime.turnId, runtime.leaseToken, clock.nowMillis(),
                ),
            ) { "reply turn lease lost" }
        }
        settings.putGroupTurnState(groupId, verifiedState.copy(updatedAt = emotionResolvedAt))
        settings.putGroupPlotSummary(groupId, verifiedState.currentTopic)
        runtime.responseStored = true
        if (prompt.pendingModeTransition.isNotBlank()) settings.clearPendingGroupModeTransition(groupId)
        emit(GroupEngineEvent.UnhideSession(groupId))
        val dialogueCount = filtered.count { it.type == "dialogue" }
        val narrationCount = filtered.count { it.type == "narration" }
        emit(
            GroupEngineEvent.ReplyStored(
                groupId, runtime.batchIds, aiMsgId,
                speakers = filtered.filter { it.type == "dialogue" }.map { it.speaker },
                narrationCount = narrationCount,
            ),
        )
        emit(GroupEngineEvent.TurnStateUpdated(groupId, fallbackDerived = parsedTurnState == null))
        val last = filtered.last()
        store.updateLastMessage(groupId, "${last.speaker}：${last.message.take(50)}", clock.nowMillis())
        emit(
            GroupEngineEvent.Settled(
                groupId = groupId,
                // 群聊无亲密度/催眠结算；仅手动轮次每日龙门币 50（oracle :1783-1786）。
                dailyLmb = if (isAuto) null else 50,
                visibleCount = GroupReplyParser.visibleGroupMessageCount(filtered, mode),
                preview = "${last.speaker}：${last.message.take(50)}",
            ),
        )
        emit(GroupEngineEvent.UnreadHint(groupId, GroupReplyParser.visibleGroupMessageCount(filtered, mode)))
        emit(GroupEngineEvent.MaintenanceRequested(groupId, "post_reply"))
        return dialogueCount > 0 || narrationCount > 0
    }


    /** oracle :1613-1623 —— 单次模型请求；预算 min(模型预算, 剩余整轮)；群聊显式输出预算 5000。 */
    private suspend fun generateGroupReply(
        messages: List<AiMessage>,
        stage: String,
        remainingTurnBudget: () -> Long,
    ): String {
        val budget = minOf(config.effectiveModelBudgetMs, remainingTurnBudget())
        // 每位成员多一行【心情】后输出变长约 10~15%：群聊显式输出预算由宿主模型客户端消费
        // （GroupOutputBudget.groupMaxOutputTokens(settings.provider)，oracle GVM:1617-1620）。
        return withChatStageTimeout("group", stage, budget) {
            aiClient.complete(messages, stage)
        }.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    /** oracle :1919-1930 —— 未送达标记（gift_hidden→gift_reply_failed，其余→send_failed）。 */
    private suspend fun markGroupMessagesUndelivered(groupId: String, messageIds: Set<Long>) {
        if (messageIds.isEmpty()) return
        val affected = store.getMessagesSync(groupId).filter { it.id in messageIds && it.isMe }
        affected.forEach { message ->
            store.updateMessageType(message.id, if (message.type == "gift_hidden") "gift_reply_failed" else "send_failed")
        }
        if (affected.isNotEmpty()) emit(GroupEngineEvent.Undelivered(groupId, affected.map { it.id }.toSet()))
    }

    // === 重试 / 恢复（oracle :1886-1930 / :2124-2146 / :2862-2874） ===

    /** 手动重试失败消息；防抖去重。oracle retryFailedMessage :1886-1917。 */
    suspend fun retryFailedMessage(groupId: String, msgId: Long): Boolean {
        val acquired = withState {
            if (msgId in retryingMessageIds) false else {
                retryingMessageIds.add(msgId); true
            }
        }
        if (!acquired) return false
        try {
            val message = store.getMessageById(msgId)
            val session = store.getSession(groupId)
            if (message == null || session == null || !message.isMe ||
                (message.type != "send_failed" && message.type != "gift_reply_failed" && message.type != "image")
            ) {
                withState { retryingMessageIds.remove(msgId) }
                return false
            }
            val isGift = message.type == "gift_reply_failed"
            if (message.type == "image") {
                val image = GroupImageJsonContract.parse(message.content)
                val imageUri = image?.imageUri.orEmpty()
                if (imageUri.isBlank()) {
                    withState { retryingMessageIds.remove(msgId) }
                    return false
                }
                val imageForModel = imageForModelResolver(imageUri)
                val deferred = CompletableDeferred<Boolean>()
                val job = sendGroupImageMessage(
                    groupId = groupId, groupName = session.operatorName, imageUri = imageUri,
                    imageForModel = imageForModel, caption = image?.caption.orEmpty(), mode = message.mode,
                    existingMessageId = msgId, onReplyComplete = { deferred.complete(it) },
                )
                if (job == null) {
                    withState { retryingMessageIds.remove(msgId) }
                    return false
                }
                return deferred.await()
            }
            val deferred = CompletableDeferred<Boolean>()
            val job = sendGroupMessage(
                groupId = groupId,
                groupName = session.operatorName,
                text = if (isGift) GroupReplyParser.giftPromptText(message.content) else message.content,
                mode = message.mode,
                userMessageAlreadyStored = true,
                retryMessageId = msgId,
                onReplyTurnClaimed = { store.updateMessageType(msgId, if (isGift) "gift_hidden" else "text") },
                onResponseComplete = { deferred.complete(it) },
            )
            if (job == null) {
                withState { retryingMessageIds.remove(msgId) }
                return false
            }
            return deferred.await()
        } catch (e: CancellationException) {
            throw e
        } finally {
            withState { retryingMessageIds.remove(msgId) }
        }
    }

    /** 中断恢复（oracle resumePersistedReply :2124-2146）：type 重置后走重试分支，租约令牌透传。 */
    suspend fun resumePersistedReply(
        groupId: String,
        msgId: Long,
        replyTurnId: String? = null,
        replyLeaseToken: String? = null,
    ): Boolean {
        val session = store.getSession(groupId)
        val message = store.getMessageById(msgId)?.takeIf { it.sessionId == groupId && it.isMe }
        if (session == null || message == null) return true // oracle :2128-2131
        val isGift = message.type == "gift_reply_failed" || message.type == "gift_hidden"
        store.updateMessageType(msgId, if (isGift) "gift_hidden" else "text")
        val deferred = CompletableDeferred<Boolean>()
        val job = sendGroupMessage(
            groupId = groupId,
            groupName = session.operatorName,
            text = if (isGift) GroupReplyParser.giftPromptText(message.content) else message.content,
            mode = message.mode,
            userMessageAlreadyStored = true,
            retryMessageId = msgId,
            replyTurnIdOverride = replyTurnId,
            replyLeaseTokenOverride = replyLeaseToken,
            onResponseComplete = { deferred.complete(it) },
        )
        if (job == null) return true
        return deferred.await()
    }

    /** 图片恢复（oracle resumeImageReply :2862-2874）：从图片 JSON 还原后重走图片管线。 */
    suspend fun resumeImageReply(groupId: String, msgId: Long, replyLeaseToken: String? = null): Boolean {
        val session = store.getSession(groupId) ?: return false
        val message = store.getMessageById(msgId)?.takeIf { it.sessionId == groupId && it.isMe && it.type == "image" }
            ?: return false
        val image = GroupImageJsonContract.parse(message.content) ?: return false
        if (image.imageUri.isBlank()) return false
        val imageForModel = imageForModelResolver(image.imageUri) ?: return false
        val deferred = CompletableDeferred<Boolean>()
        val job = sendGroupImageMessage(
            groupId = groupId, groupName = session.operatorName, imageUri = image.imageUri,
            imageForModel = imageForModel, caption = image.caption, mode = message.mode,
            existingMessageId = msgId, onReplyComplete = { deferred.complete(it) },
        )
        if (job == null) return false
        return deferred.await()
    }

    /** 启动 reconcile（R1 裁决）：孤儿清理 + 可重试 turn 清单 + 自动计划重建。 */
    suspend fun onAppStartReconcile(): List<String> {
        runCatching { turns.cleanupOrphanedSucceededTurns() }
        val retryable = runCatching { turns.retryableIds(since = 0L, now = clock.nowMillis()) }
            .getOrDefault(emptyList())
        val groupSessions = runCatching { extras.allSessions() }
            .getOrDefault(emptyList())
            .filter { it.operatorId.startsWith("group") }
            .map { it.id }
        runCatching { autoPlans.reconcile(groupSessions) }
        return retryable
    }

    /** 前台补算：进入群聊时驱动该群全部可重试 turn。 */
    suspend fun resumeSessionTurns(groupId: String): Int {
        var resumed = 0
        val ids = turns.retryableIds(since = 0L, now = clock.nowMillis())
        for (id in ids) {
            val turn = store.getTurn(id) ?: continue
            if (turn.sessionId != groupId || turn.triggerKind != "manual") continue
            val sourceId = turn.sourceMessageId ?: continue
            if (resumePersistedReply(groupId, sourceId, replyTurnId = id)) resumed++
        }
        return resumed
    }

    // === 图片 / 礼物（oracle :2247-2398 / :1849-1884） ===

    fun sendHiddenGiftMessage(
        groupId: String,
        groupName: String,
        text: String,
        mode: String = "online",
        imageUri: String,
        giftName: String,
        recipientNames: List<String>,
    ): Job? = scope.launch {
        val id = allocator.allocate()
        val timestamp = clock.nowMillis()
        val giftPayload = GroupReplyParser.buildGiftPayload(text, imageUri, giftName, recipientNames)
        val turnId = "group:$groupId:$id"
        store.sendMessageAndCreateReplyTurn(
            groupId,
            ChatMessage(
                id = id, sessionId = groupId, senderName = "我", content = giftPayload,
                type = "gift_hidden", mode = mode, timestamp = timestamp, isMe = true,
            ),
            ReplyTurn(turnId, groupId, "group", "gift", id, "", mode, "pending", 0, timestamp, "", 0, null, "", timestamp, timestamp, 0),
        )
        emit(GroupEngineEvent.UnhideSession(groupId))
        sendGroupMessage(
            groupId, groupName, text, mode,
            userMessageAlreadyStored = true, sourceMessageId = id, replyTurnIdOverride = turnId,
        )
    }

    /** 群图片（oracle :2247-2398）：占位落库 → 识图回填(≤800)/failed → 重走文本轮次。 */
    fun sendGroupImageMessage(
        groupId: String,
        groupName: String,
        imageUri: String,
        imageForModel: String?,
        caption: String,
        mode: String = "online",
        existingMessageId: Long? = null,
        replyLeaseTokenOverride: String? = null,
        onMessageSent: () -> Unit = {},
        onResult: (Boolean) -> Unit = {},
        onReplyComplete: (Boolean) -> Unit = {},
    ): Job? {
        if (groupId.isBlank()) {
            onResult(false); return null
        }
        if (!visionConfigured()) {
            emit(GroupEngineEvent.Toast("图片聊天需要先设置识图模型，请在模型设置中填写识图地址、模型名和密钥。"))
            onResult(false); return null
        }
        if (imageForModel.isNullOrBlank()) {
            emit(GroupEngineEvent.Toast("无法读取这张图片，请重新选择后再试。"))
            onResult(false); return null
        }
        val generation = generationOf(groupId)
        return scope.launch {
            var visionMutexLocked = false
            var imageMessageId = 0L
            var replyTurnId = ""
            var leaseToken = ""
            var handedOff = false
            try {
                val imageJob = kotlinx.coroutines.currentCoroutineContext()[Job]!!
                withState { groupAiJobs[groupId] = imageJob }
                if (generationOf(groupId) != generation) return@launch
                // 先占住群队列，避免文本/自动轮次基于未识图的历史构建提示词（oracle :2275-2278）。
                groupMutex(groupId).lock()
                visionMutexLocked = true
                val id = existingMessageId ?: allocator.allocate()
                imageMessageId = id
                val placeholder = GroupImageJsonContract.placeholder(imageUri, caption)
                if (existingMessageId == null) {
                    val timestamp = clock.nowMillis()
                    replyTurnId = "group:$groupId:$id"
                    store.sendMessageAndCreateReplyTurn(
                        groupId,
                        ChatMessage(
                            id = id, sessionId = groupId, senderName = "我", content = placeholder,
                            type = "image", mode = mode, timestamp = timestamp, isMe = true,
                        ),
                        ReplyTurn(replyTurnId, groupId, "group", "image", id, "", mode, "pending", 0, timestamp, "", 0, null, "", timestamp, timestamp, 0),
                    )
                } else {
                    replyTurnId = "group:$groupId:$id"
                    val now = clock.nowMillis()
                    if (store.getTurn(replyTurnId) == null) {
                        store.createTurnIfAbsent(
                            ReplyTurn(replyTurnId, groupId, "group", "image", id, "", mode, "pending", 0, now, "", 0, null, "", now, now, 0),
                        )
                    }
                    store.updateMessageContent(id, placeholder)
                }
                if (existingMessageId == null) onMessageSent()
                onResult(true)
                leaseToken = replyLeaseTokenOverride ?: newLeaseToken()
                if (replyLeaseTokenOverride == null) {
                    val claimed = turns.claim(replyTurnId, leaseToken, clock.nowMillis(), clock.nowMillis() + config.effectiveTurnBudgetMs)
                    if (claimed is TurnStateMachine.ClaimResult.Denied) {
                        onResult(true)
                        return@launch
                    }
                }
                emit(GroupEngineEvent.UnhideSession(groupId))
                if (generationOf(groupId) != generation) return@launch
                val visionText = try {
                    (visionAnalyze(imageForModel)?.take(2000)?.ifBlank { null })
                        ?: throw IllegalStateException("没有识别到图片内容")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    store.updateMessageContent(id, GroupImageJsonContract.failed(imageUri, caption))
                    emit(GroupEngineEvent.Toast("图片识别失败，请检查识图模型设置后重试。"))
                    onResult(false)
                    onReplyComplete(false)
                    return@launch
                }
                if (generationOf(groupId) != generation) return@launch
                // visionSummary ≤800、visibleSummary ≤500（oracle :2352-2360）。
                val cleanText = visionText.trim().removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
                val visionSummary = runCatching {
                    GroupReplyJson.json.parseToJsonElement(cleanText)
                        .let { it as? kotlinx.serialization.json.JsonObject }
                        ?.get("visibleSummary")
                        ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                }.getOrNull()?.contentOrNull?.take(500)
                store.updateMessageContent(id, GroupImageJsonContract.withVisionSummary(imageUri, caption, visionText))
                val promptText = buildString {
                    append("用户在群聊中发送了一张图片。")
                    if (caption.isNotBlank()) append("\n用户附带文字：${caption.trim()}")
                    append("\n【用户发送的图片分析】")
                    append("\n画面内容：${visionSummary ?: visionText.take(500)}")
                    append("\n请所有在场成员根据自己的性格、关系和当前群聊氛围自然回应这张图片。")
                }
                if (generationOf(groupId) != generation) return@launch
                handedOff = true
                sendGroupMessage(
                    groupId, groupName, promptText, mode,
                    userMessageAlreadyStored = true, sourceMessageId = id,
                    replyTurnIdOverride = replyTurnId, replyLeaseTokenOverride = leaseToken,
                    onResponseComplete = onReplyComplete,
                )
            } catch (e: CancellationException) {
                if (imageMessageId != 0L) {
                    runCatching { store.updateMessageContent(imageMessageId, GroupImageJsonContract.failed(imageUri, caption)) }
                }
                throw e
            } catch (e: Exception) {
                emit(GroupEngineEvent.Toast(GroupReplyParser.classifyGroupError(e)))
                onResult(false)
                onReplyComplete(false)
            } finally {
                onResult(false)
                if (!handedOff && replyTurnId.isNotBlank() && leaseToken.isNotBlank()) {
                    val now = clock.nowMillis()
                    runCatching { turns.release(replyTurnId, leaseToken, now, now, "image_reply_not_completed") }
                }
                if (visionMutexLocked) groupMutex(groupId).unlock()
                val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                withState { if (groupAiJobs[groupId] === currentJob) groupAiJobs.remove(groupId) }
            }
        }
    }

    // === 撤回（oracle :393-421 / :427-481；群聊仅分段撤回） ===

    /** 整条撤回/删除（非法分段参数退化路径）。 */
    fun recallMessage(groupId: String, msgId: Long): Job? = scope.launch {
        val message = store.getMessageById(msgId) ?: return@launch
        withState { pendingUserMessageIds[groupId]?.remove(msgId) }
        runCatching { turns.cancelBySource(groupId, msgId) }
        store.deleteMessage(msgId)
        refreshLatestRoundEmotionSnapshot(groupId, msgId)
        rebuildGroupContextAfterRecall(groupId, msgId)
    }

    /** 分段撤回（oracle :393-421）：非法参数退化整条删除；成功→删 display event + 情绪重算 + 重建意图。 */
    fun recallMessageSegment(groupId: String, msgId: Long, segmentIndex: Int): Job? = scope.launch {
        val message = store.getMessageById(msgId)
        if (message == null || message.type != "ai_json" || segmentIndex < 0) {
            recallMessage(groupId, msgId)?.join()
            return@launch
        }
        val updated = GroupReplyParser.markSegmentRecalled(message.content, segmentIndex)
        if (updated == null) {
            recallMessage(groupId, msgId)?.join()
            return@launch
        }
        store.updateMessageContentAndPreview(message.sessionId, msgId, updated, message.timestamp)
        store.deleteDisplayEvent(msgId, segmentIndex)
        refreshLatestRoundEmotionSnapshot(message.sessionId, msgId)
        rebuildGroupContextAfterRecall(message.sessionId, msgId)
    }

    /** 撤回/删除后重算最新一轮成员心情快照（oracle :427-458）。 */
    private suspend fun refreshLatestRoundEmotionSnapshot(groupId: String, affectedMessageId: Long) {
        if (groupId.isBlank()) return
        runCatching {
            val state = settings.getGroupTurnState(groupId) ?: return@runCatching
            val restartAt = settings.getSessionRestartAt(groupId)
            val latestAi = store.getMessagesSync(groupId)
                .filter { it.type == "ai_json" && !it.isMe && (restartAt <= 0L || it.timestamp >= restartAt) }
                .maxByOrNull { it.id }
            if (latestAi != null && latestAi.id > affectedMessageId) return@runCatching
            if (latestAi == null || latestAi.id != affectedMessageId) {
                settings.putGroupTurnState(groupId, state.copy(memberEmotions = "", emotionFallbackKeys = ""))
                return@runCatching
            }
            val results = GroupReplyParser.extractGroupResults(latestAi.content)
                .filterIndexed { index, _ -> !GroupReplyParser.isGroupSegmentRecalled(latestAi.content, index) }
            val resolved = GroupEmotionRules.resolveRoundEmotions(
                nowMs = state.updatedAt.takeIf { it > 0L } ?: clock.nowMillis(),
                results = results,
                previousSnapshot = "",
                previousSnapshotAtMs = 0L,
                previousInheritedKeys = "",
                history = GroupReplyParser.trackedEmotionsFromHistory(
                    store.getMessagesSync(groupId), restartAt, excludeMessageId = latestAi.id,
                ),
            )
            settings.putGroupTurnState(
                groupId,
                state.copy(
                    memberEmotions = GroupEmotionRules.formatEmotionSnapshot(resolved.emotions),
                    emotionFallbackKeys = GroupEmotionRules.formatKeyList(resolved.inheritedKeys),
                ),
            )
        }
    }

    /** 撤回后上下文重建意图落盘（oracle :461-481）：先写意图再失效，杀进程可续。 */
    private suspend fun rebuildGroupContextAfterRecall(groupId: String, changedMessageId: Long) {
        if (groupId.isBlank()) return
        settings.putString("context_rebuild_changed_$groupId", changedMessageId.toString())
        settings.putBoolean("context_rebuild_pending_$groupId", true)
        settings.advanceMemoryTimelineEpoch(groupId)
        settings.putMemoryExtractionCursor(groupId, 0L)
        settings.putSummaryCursor(groupId, 0L)
        settings.putRollingSummaryStatus(groupId, SettingsRepository.RollingSummaryStatus())
        val old = runCatching { extras.shortTermSummary(groupId) }.getOrNull()
        if (old != null) settings.putString("context_rebuild_previous_summary_$groupId", old.content)
        settings.putString("context_rebuild_changed_$groupId", "")
        emit(GroupEngineEvent.MaintenanceRequested(groupId, "context_rebuild"))
    }

    // === 自动互动（oracle :647-815 / :1933-1979；R1：到期触发经 DelayedTaskLayer） ===

    fun setAutoGroupChatEnabled(groupId: String, enabled: Boolean) {
        val wasEnabled = settings.getGroupAuto(groupId)
        settings.putGroupAuto(groupId, enabled)
        if (enabled == wasEnabled) {
            if (enabled && settings.autoAiEnabled) refreshAutoGroupChats()
            return
        }
        if (enabled) settings.putGroupAutoChatComplete(groupId, false)
        if (enabled && settings.autoAiEnabled) {
            autoPlans.resetPlan(groupId)?.let { plan ->
                emit(GroupEngineEvent.AutoPlanChanged(groupId, plan.state, plan.dueAt, plan.round))
            }
        } else {
            stopAutoGroupChat(groupId)
        }
    }

    fun stopAutoGroupChat(groupId: String) {
        scope.launch {
            withState { autoChatGenerations[groupId] = nextAutoGeneration() }
            autoPlans.cancel(groupId)
            emit(GroupEngineEvent.AutoPlanChanged(groupId, "cancelled", 0L, 0))
        }
    }

    /** 启动/恢复后重建所有已开自动群计划（oracle GroupAutoChatScheduler.ensurePlan 批量）。 */
    fun refreshAutoGroupChats() {
        if (!settings.autoAiEnabled) return
        scope.launch {
            extras.allSessions()
                .filter { it.operatorId.startsWith("group") }
                .forEach { group ->
                    if (settings.getGroupAuto(group.id)) {
                        autoPlans.ensurePlan(group.id)?.let { plan ->
                            emit(GroupEngineEvent.AutoPlanChanged(group.id, plan.state, plan.dueAt, plan.round))
                        }
                    } else {
                        stopAutoGroupChat(group.id)
                    }
                }
        }
    }

    /** 用户发言即重置自动计时（oracle resetAutoGroupChatTimer :742-760）。 */
    private fun resetAutoGroupChatTimer(groupId: String) {
        if (!settings.getGroupAuto(groupId)) return
        val plan = autoPlans.resetPlan(groupId) ?: return
        emit(GroupEngineEvent.AutoPlanChanged(groupId, plan.state, plan.dueAt, plan.round))
    }

    fun autoGroupChatStatus(groupId: String): String {
        val now = clock.nowMillis()
        if (!settings.getGroupAuto(groupId)) return "本群已关闭"
        if (!settings.autoAiEnabled) return "总自动内容开关已关闭"
        if (settings.isGroupAutoChatComplete(groupId)) return "已达到自动轮数上限"
        val plan = settings.getGroupAutoChatPlan(groupId)
        return when (plan.state) {
            "claimed" -> "正在生成"
            "pending" -> if (plan.dueAt > now) "等待下一轮自动聊天（约${((plan.dueAt - now) / 1000).coerceAtLeast(1)}秒）" else "等待触发"
            else -> "正在安排下一轮"
        }
    }

    /**
     * 自动轮次执行（oracle runScheduledAutoTurn :1933-1979）。
     * 前台 Timer / BGTask 两通道都只需调本入口（通道适配归 T22 胶水批）。
     */
    suspend fun runScheduledAutoTurn(
        groupId: String,
        token: String,
        expectedGeneration: Long? = null,
        planRevision: Long? = null,
    ): Boolean {
        // 用户回复优先：不在队列空闲前消耗持久计划（oracle :1935）。
        while (withState { groupAiJobs[groupId]?.isActive == true } && isAutoGroupChatEnabled(groupId)) {
            delay(config.autoPollStepMs)
        }
        val revision = planRevision ?: settings.getGroupAutoChatPlan(groupId).revision
        val plan = autoPlans.claim(groupId, token, revision) ?: return false
        if (expectedGeneration != null && withState { autoChatGenerations[groupId] } != expectedGeneration) {
            autoPlans.releaseClaim(groupId, plan.round - 1, plan.token, plan.revision)
            return false
        }
        val session = store.getSession(groupId) ?: run {
            autoPlans.cancel(groupId)
            return false
        }
        if (!autoPlans.isCurrentClaim(groupId, plan.token, plan.revision)) return false
        val turnId = "auto:$groupId:${plan.token}"
        val turnNow = clock.nowMillis()
        store.createTurnIfAbsent(
            ReplyTurn(
                turnId, groupId, "group", "group_auto", null, plan.token, settings.getGroupMode(groupId),
                "pending", 0, turnNow, "", 0, null, "", turnNow, turnNow, 0,
            ),
        )
        val leaseToken = newLeaseToken()
        val claimed = turns.claim(turnId, leaseToken, clock.nowMillis(), clock.nowMillis() + config.effectiveTurnBudgetMs)
        if (claimed is TurnStateMachine.ClaimResult.Denied) {
            autoPlans.releaseClaim(groupId, plan.round - 1, plan.token, plan.revision)
            return false
        }
        val deferred = CompletableDeferred<Boolean>()
        val job = sendGroupMessage(
            groupId = groupId,
            groupName = session.operatorName,
            text = "",
            mode = settings.getGroupMode(groupId),
            isAuto = true,
            autoGeneration = expectedGeneration,
            autoPlanToken = plan.token,
            autoPlanRevision = plan.revision,
            replyTurnIdOverride = turnId,
            replyLeaseTokenOverride = leaseToken,
            onResponseComplete = { deferred.complete(it) },
        )
        if (job == null) {
            autoPlans.releaseClaim(groupId, plan.round - 1, plan.token, plan.revision)
            return false
        }
        val succeeded = runCatching { deferred.await() }.getOrDefault(false)
        if (succeeded && autoPlans.isCurrentClaim(groupId, plan.token, plan.revision)) {
            autoPlans.scheduleNext(groupId, plan.round, plan.token, plan.revision)?.let { next ->
                emit(GroupEngineEvent.AutoPlanChanged(groupId, next.state, next.dueAt, next.round))
            }
        } else {
            // 失败自动轮次没有用户气泡可重试：保留同一轮 pending 交给调度层重试（oracle :1967-1971）。
            autoPlans.releaseClaim(groupId, plan.round - 1, plan.token, plan.revision)
        }
        return succeeded
    }

    // === 重启 / 删群（oracle :598-645；群聊无存档/擦除） ===

    fun restartGroupSession(groupId: String) {
        if (groupId.isBlank()) return
        cancelGroupRequests(groupId)
        val now = clock.nowMillis()
        settings.advanceMemoryTimelineEpoch(groupId)
        // 先发布新边界再清理，保证首个新轮次看不到旧历史（oracle :603-604）。
        settings.putSessionRestartAt(groupId, now)
        settings.clearGroupPlotSummary(groupId)
        settings.clearGroupTurnState(groupId)
        settings.putSummaryCursor(groupId, 0L)
        settings.putMemoryExtractionCursor(groupId, 0L)
        val cleanupJob = scope.launch {
            // G-05：重启前在飞的 turn 不得写入新时间线（oracle :614）。
            runCatching { extras.deleteReplyTurnsBySession(groupId) }
            extras.deleteShortTermMemory(groupId)
            extras.clearGroupRestartMemory(groupId)
            val sysId = runCatching { allocator.allocate() }.getOrDefault(0L)
            if (sysId > 0L) {
                store.sendMessage(
                    groupId,
                    ChatMessage(
                        id = sysId, sessionId = groupId, senderName = "系统",
                        content = "已从这里开始新的群聊。上方旧群聊会保留为浅灰色，后续回复默认只参考新群聊。",
                        type = "system", timestamp = now, isMe = false,
                    ),
                )
                emit(GroupEngineEvent.SystemMessageStored(groupId, sysId))
            }
            withState { restartCleanupJobs.remove(groupId) }
        }
        restartCleanupJobs[groupId] = cleanupJob
    }

    fun deleteGroup(groupId: String) {
        cancelGroupRequests(groupId)
        stopAutoGroupChat(groupId)
        settings.remove("group_auto_$groupId")
        settings.remove("group_auto_plan_complete_$groupId")
        settings.remove("group_event_auto_$groupId")
        settings.putBoolean("group_deleted_$groupId", true)
        settings.clearGroupPlotSummary(groupId)
        settings.clearGroupTurnState(groupId)
    }

    /** 测试/宿主检查用：该群是否有在飞 AI job。 */
    suspend fun isGroupBusy(groupId: String): Boolean = withState { groupAiJobs[groupId]?.isActive == true }
}

/** 空上下文源（无记忆/知识库注入；生产由 iOS 宿主接线）。 */
private object GroupContextSourceNoop : GroupContextSource
