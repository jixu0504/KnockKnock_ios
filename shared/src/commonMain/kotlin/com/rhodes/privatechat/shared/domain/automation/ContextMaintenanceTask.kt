package com.rhodes.privatechat.shared.domain.automation

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.platformSynchronized
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.sync.Mutex

/**
 * 记忆维护（ContextMaintenanceWorker / runContextMaintenance 的 shared 域层等价物）。
 *
 * oracle 位置：
 *  - `app/src/main/java/com/rhodes/privatechat/automation/ContextMaintenanceWorker.kt`（1-43 行）
 *  - `viewmodel/ChatViewModel.kt:2205-2267`（会话锁 + runContextMaintenanceLocked 重建/摘要分支）、
 *    `:636-638`（摘要失败退避入队）、`:2211`（私聊上下文变更入队）、`:2776/:2803-2808`（前台内联维护）
 *  - `viewmodel/GroupChatViewModel.kt:479,485,2802`（群聊触发/锁/摘要失败）
 *  - `viewmodel/MainViewModel.kt:632-639`（启动重挂）、`:140`（24h 扫描窗口）
 *
 * 五类触发点等价入口（§C-1 全清单；本类逐点提供方法）：
 *  ① 启动重挂      → [reconcile]/[startupReconcileRecords]（`context_rebuild_pending_` 或 failed+自动重试）
 *  ② 私聊上下文变更 → [onPrivateContextChanged]（标记 rebuild pending + 保留旧摘要 + dueAt=now）
 *  ③ 群聊回复/变更  → [onGroupContextChanged]（group=true，dueAt=now）
 *  ④ 摘要生成失败   → [onSummaryFailure]（写 failed 状态 + 退避 dueAt，仅自动重试开启时入队）
 *  ⑤ 前台内联维护   → [onForegroundReplyCompleted] / [maintainNow]（回复后同管线，非 worker）
 *
 * 唯一键 `context-maintenance-$sessionId` + KEEP（ContextMaintenanceWorker.kt:21）；不 import 平台框架；
 * 记忆维护不产生任何系统通知（§C-11）。
 */
object ContextMaintenanceKeys {
    const val PREFIX = "context-maintenance-"
    const val PAYLOAD_SESSION = "session"
    const val PAYLOAD_GROUP = "group"

    /** 重建标记设置键（oracle MainViewModel.kt:634 / ChatViewModel.kt:2224-2239）。 */
    const val REBUILD_PENDING_PREFIX = "context_rebuild_pending_"
    const val REBUILD_CHANGED_PREFIX = "context_rebuild_changed_"
    const val REBUILD_PREVIOUS_SUMMARY_PREFIX = "context_rebuild_previous_summary_"

    fun forSession(sessionId: String): String = PREFIX + sessionId

    fun sessionIdOf(key: String): String? =
        key.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }

    fun rebuildPendingKey(sessionId: String): String = REBUILD_PENDING_PREFIX + sessionId
    fun rebuildChangedKey(sessionId: String): String = REBUILD_CHANGED_PREFIX + sessionId
    fun rebuildPreviousSummaryKey(sessionId: String): String = REBUILD_PREVIOUS_SUMMARY_PREFIX + sessionId
}

/**
 * 会话锁（幂等核心，oracle ChatViewModel.kt:2216-2219 `ContextMaintenanceLocks.forSession(id).tryLock()`）。
 *
 * T23 未提供该原语（`MemoryPromotionLocks` 仅服务晋升且 internal），本卡新建；实现沿用共享层平台化先例
 * （`MutableMap + platformSynchronized` 惰性建锁，语义等价 Android ConcurrentHashMap<String, Mutex>）。
 */
object ContextMaintenanceLocks {
    private val table = mutableMapOf<String, Mutex>()

    fun forSession(sessionId: String): Mutex = platformSynchronized(table) { table.getOrPut(sessionId) { Mutex() } }
}

/** 摘要/维护退避策略（纯函数，oracle ChatViewModel.kt:636-638）。 */
object ContextMaintenancePolicy {
    /** oracle 基数：60s。 */
    const val SUMMARY_RETRY_BASE_MS = 60_000L

    /**
     * 摘要退避上限：60s << 5 = 32min。
     *
     * 口径（lead 裁决 2026-11-23，**oracle 优先于规格卡**）：oracle ChatViewModel.kt:637 为
     * `(failures-1).coerceAtMost(5)` → 封顶 32min；规格卡 §C-7/验收 #5 原写「上限 64min」是规格卡错误，
     * 已由 lead 修正为 32min（规格卡内已标注）。
     */
    const val SUMMARY_RETRY_MAX_SHIFT = 5

    /** 第 n 次失败后的重试时刻：`now + 60s << (n-1)`，n≥6 起封顶 32min（oracle coerceAtMost(5)）。 */
    fun summaryRetryAt(
        now: Long,
        failures: Int,
        baseMs: Long = SUMMARY_RETRY_BASE_MS,
        maxShift: Int = SUMMARY_RETRY_MAX_SHIFT,
    ): Long = now + (baseMs shl (failures - 1).coerceIn(0, maxShift))

    /** 启动重挂分流：rebuild pending 一律入队；failed 且开启自动重试才入队（§C-1 ①）。 */
    fun shouldEnqueueOnStartup(rebuildPending: Boolean, summaryState: String, autoRetryEnabled: Boolean): Boolean =
        rebuildPending || (summaryState == "failed" && autoRetryEnabled)

    /** 非重建分支：failed 且开启自动重试 → 需要重跑摘要。 */
    fun shouldRetrySummary(state: String, autoRetryEnabled: Boolean): Boolean =
        state == "failed" && autoRetryEnabled

    /** failed 但未开启自动重试 → 跳过（§C-5）。 */
    fun shouldSkipSummary(state: String, autoRetryEnabled: Boolean): Boolean =
        state == "failed" && !autoRetryEnabled

    /** 未到退避时刻 → 本次不跑（oracle `now < retryStatus.nextRetryAt`）。 */
    fun summaryInBackoff(state: String, autoRetryEnabled: Boolean, now: Long, nextRetryAt: Long): Boolean =
        state == "failed" && autoRetryEnabled && now < nextRetryAt
}

/** 记忆维护配置（测试可压缩/注入）。 */
data class ContextMaintenanceConfig(
    /** 记忆抽取批大小 30（oracle ChatViewModel.kt:2231 `getMessagesAfterCursor(..., 30)`）。 */
    val extractionBatchLimit: Int = 30,
    /** 重建时最多推进 6 批（oracle `repeat(6)`）。 */
    val extractionMaxBatches: Int = 6,
    /** 维护尾部重试挂起源上限（MemoryV2Pipeline.retryPendingSources 默认 20）。 */
    val retryPendingSourcesLimit: Int = 20,
    /** 不进抽取的消息类型（oracle ChatViewModel.kt:2233）。 */
    val excludedMessageTypes: Set<String> = setOf("system", "send_failed", "gift_reply_failed"),
)

/**
 * 记忆维护持久化端口（生产适配器包 SessionRepository / MessageRepository / MemoryRepository /
 * MemoryV2Pipeline / 摘要生成管线；测试用内存 fake）。
 *
 * @param group true = 群聊分支（oracle ContextMaintenanceWorker.kt:37-38 分派
 *        groupChatViewModel / chatViewModel）；适配器据此选择群聊/私聊管线。
 */
interface ContextMaintenancePort {
    suspend fun getSession(id: String): ChatSession?

    /** 滚动摘要正文（oracle `repository.getShortTermMemory(sessionId)?.content`）。 */
    suspend fun shortTermSummary(sessionId: String): String?

    /** 游标之后的原始消息（oracle `repository.getMessagesAfterCursor(sessionId, cursor, restartAt, limit)`）。 */
    suspend fun messagesAfterCursor(sessionId: String, afterMessageId: Long, restartAt: Long, limit: Int): List<ChatMessage>

    /** 记忆纪元失效（oracle `memoryV2.invalidateChangedMessage`）。 */
    suspend fun invalidateChangedMessage(sessionId: String, changedMessageId: Long)

    /** 生成滚动摘要；成功返回 true（失败由摘要管线写 failed 状态与退避，oracle generateShortTermSummary）。 */
    suspend fun generateShortTermSummary(session: ChatSession, group: Boolean): Boolean

    /** 批量抽取记忆（oracle `ingestPrivateMemoryV2`；失败返回 false → retry）。 */
    suspend fun ingestMemory(session: ChatSession, messages: List<ChatMessage>, group: Boolean): Boolean

    /** MemoryV2 维护：重试pending来源（oracle MemoryV2Pipeline.retryPendingSources；返回实际重试条数）。 */
    suspend fun retryPendingMemorySources(limit: Int): Int
}

/** 记忆维护语义事件（UI/诊断观察；域层不直接写通知）。 */
sealed interface ContextMaintenanceEvent {
    /** 会话锁被另一执行体持有 → retry（§C-4）。 */
    data class LockHeld(val sessionId: String) : ContextMaintenanceEvent

    /** 重建路径开始（§C-5）。 */
    data class RebuildStarted(val sessionId: String) : ContextMaintenanceEvent

    /** 重建前保留旧摘要（重建成功前不擦除上下文，§C-5）。 */
    data class OldSummaryPreserved(val sessionId: String, val summary: String) : ContextMaintenanceEvent

    data class SummaryGenerated(val sessionId: String, val success: Boolean) : ContextMaintenanceEvent

    data class MemoryIngested(val sessionId: String, val batches: Int, val messages: Int) : ContextMaintenanceEvent

    data class PendingSourcesRetried(val count: Int) : ContextMaintenanceEvent

    data class SummaryRetryScheduled(val sessionId: String, val retryAt: Long) : ContextMaintenanceEvent

    /** 本次不执行/未完成（RETRY 原因）。 */
    data class Deferred(val sessionId: String, val reason: String, val retryAt: Long = 0L) : ContextMaintenanceEvent

    /** 本次跳过（会话已删 / 自动重试关闭等）。 */
    data class Skipped(val sessionId: String, val reason: String) : ContextMaintenanceEvent

    data class Completed(val sessionId: String, val group: Boolean) : ContextMaintenanceEvent
}

/** 延迟原因常量（语义事件里的 reason）。 */
object ContextMaintenanceDeferReason {
    const val RECOVERY_ACTIVE = "backup_restore_active"
    const val NETWORK_UNAVAILABLE = "network_unavailable"
    const val LOCK_HELD = "session_lock_held"
    const val EXTRACTION_PENDING = "memory_extraction_pending"
    const val TIMELINE_CHANGED = "memory_timeline_changed"
    const val SUMMARY_BACKOFF = "summary_retry_backoff"
    const val SUMMARY_PENDING = "summary_rounds_pending"
}

/**
 * 记忆维护执行体 + 五类触发点入口。
 *
 * @param store 持久化端口（生产适配器见 handoff）
 * @param settings 设置仓库（复用既有键：context_rebuild_pending_/summaryAutoRetryEnabled/滚动摘要状态/游标；
 *        本卡零新增设置键、零新增迁移）
 * @param clock 挂钟（测试 fake-clock）
 * @param isRecoveryActive 备份/恢复护栏（oracle ContextMaintenanceWorker.kt:27）
 * @param isNetworkAvailable 网络约束语义化入口（oracle :19 CONNECTED）
 * @param events 语义事件出口
 */
class ContextMaintenanceTask(
    private val store: ContextMaintenancePort,
    private val settings: SettingsRepository,
    private val clock: () -> Long,
    private val config: ContextMaintenanceConfig = ContextMaintenanceConfig(),
    private val isRecoveryActive: () -> Boolean = { false },
    private val isNetworkAvailable: () -> Boolean = { true },
    private val events: (ContextMaintenanceEvent) -> Unit = {},
) : DelayedTaskExecutor {

    /** 任务声明需要网络（A7 §A-5；oracle ContextMaintenanceWorker.kt:19）。 */
    val requiresNetwork: Boolean get() = true

    /** 注册进层（唯一键前缀 → 执行体；A7 §A-12 接线点）。 */
    fun register(layer: DelayedTaskLayer) {
        layer.registerHandler(ContextMaintenanceKeys.PREFIX, this)
    }

    // —— 触发点 ①：启动重挂（oracle MainViewModel.kt:632-639） ——

    /** 纯函数：把「应重挂」的会话换算成层 reconcile 记录（不触碰层，便于断言分流）。 */
    fun startupReconcileRecords(sessions: List<ChatSession>, now: Long = clock()): List<DelayedTaskDue> {
        val records = mutableListOf<DelayedTaskDue>()
        for (session in sessions) {
            val rebuildPending = settings.getBoolean(ContextMaintenanceKeys.rebuildPendingKey(session.id))
            val summary = settings.getRollingSummaryStatus(session.id)
            if (!ContextMaintenancePolicy.shouldEnqueueOnStartup(
                    rebuildPending = rebuildPending,
                    summaryState = summary.state,
                    autoRetryEnabled = settings.summaryAutoRetryEnabled,
                )
            ) {
                continue
            }
            val group = session.operatorId.startsWith("group")
            // rebuild pending 立即补算；仅摘要失败的会话按 nextRetryAt 入队（oracle :634-639）。
            val dueAt = if (rebuildPending) now else summary.nextRetryAt
            records += DelayedTaskDue(
                key = ContextMaintenanceKeys.forSession(session.id),
                dueAt = dueAt,
                payload = payloadOf(session.id, group),
            )
        }
        return records
    }

    /** 启动/回前台 reconcile：把应重挂会话重新入队（层超 24h 窗口丢弃）。 */
    suspend fun reconcile(layer: DelayedTaskLayer, sessions: List<ChatSession>, now: Long = clock()): List<String> =
        layer.reconcile(startupReconcileRecords(sessions, now))

    // —— 触发点 ②③⑤：上下文变更 / 前台内联维护 ——

    /** 触发点 ②：私聊消息编辑/上下文变更（oracle ChatViewModel.kt:2211）。 */
    suspend fun onPrivateContextChanged(
        layer: DelayedTaskLayer,
        sessionId: String,
        changedMessageId: Long?,
        now: Long = clock(),
    ): String? {
        store.shortTermSummary(sessionId)?.takeIf { it.isNotBlank() }?.let { old ->
            settings.putString(ContextMaintenanceKeys.rebuildPreviousSummaryKey(sessionId), old)
            events(ContextMaintenanceEvent.OldSummaryPreserved(sessionId, old))
        }
        settings.putBoolean(ContextMaintenanceKeys.rebuildPendingKey(sessionId), true)
        settings.putString(ContextMaintenanceKeys.rebuildChangedKey(sessionId), changedMessageId?.toString().orEmpty())
        return arm(layer, sessionId, group = false, dueAt = now)
    }

    /** 触发点 ③：群聊回复/变更（oracle GroupChatViewModel.kt:479）。 */
    suspend fun onGroupContextChanged(
        layer: DelayedTaskLayer,
        groupId: String,
        now: Long = clock(),
    ): String? = arm(layer, groupId, group = true, dueAt = now)

    /** 触发点 ⑤：前台回复完成后的内联维护（oracle ChatViewModel.kt:2776/:2803-2808；非 worker）。 */
    suspend fun onForegroundReplyCompleted(
        layer: DelayedTaskLayer,
        sessionId: String,
        group: Boolean,
        now: Long = clock(),
    ): String? = arm(layer, sessionId, group = group, dueAt = now)

    /** 触发点 ⑤ 的直接执行形态（前台可在本轮之外直接 await 维护；不进层）。 */
    suspend fun maintainNow(sessionId: String, group: Boolean): DelayedTaskOutcome =
        runSession(sessionId, group)

    // —— 触发点 ④：摘要生成失败 → 退避入队（oracle ChatViewModel.kt:636-638） ——

    /**
     * 摘要失败入口：写 failed 状态（failureCount/nextRetryAt/failureCode）并按领域退避时刻入队；
     * 仅 `summaryAutoRetryEnabled` 时入队（关闭时只记状态、不入队，oracle :637-638）。
     * @param failures 已失败次数（含本次；调用方传 `previous.failureCount + 1`）
     */
    suspend fun onSummaryFailure(
        layer: DelayedTaskLayer,
        sessionId: String,
        group: Boolean,
        failures: Int,
        reason: String,
        now: Long = clock(),
    ): String? {
        val retryAt = ContextMaintenancePolicy.summaryRetryAt(now, failures)
        val autoRetry = settings.summaryAutoRetryEnabled
        val previous = settings.getRollingSummaryStatus(sessionId)
        val updated = previous.copy(
            state = "failed",
            lastAttemptAt = now,
            failureCount = maxOf(previous.failureCount, failures),
            nextRetryAt = if (autoRetry) retryAt else 0L,
            failureCode = reason.take(120),
        )
        settings.putRollingSummaryStatus(sessionId, updated)
        if (!autoRetry) {
            events(ContextMaintenanceEvent.Skipped(sessionId, "summary_auto_retry_disabled"))
            return null
        }
        events(ContextMaintenanceEvent.SummaryRetryScheduled(sessionId, retryAt))
        return arm(layer, sessionId, group, dueAt = retryAt)
    }

    /** 入队（唯一键 KEEP 由层保证；dueAt 缺省 now）。 */
    suspend fun arm(
        layer: DelayedTaskLayer,
        sessionId: String,
        group: Boolean,
        dueAt: Long = clock(),
    ): String? {
        val key = ContextMaintenanceKeys.forSession(sessionId)
        val accepted = layer.enqueue(key, dueAt, payloadOf(sessionId, group))
        return if (accepted) key else null
    }

    // —— 执行体（oracle ContextMaintenanceWorker.kt:25-42） ——

    override suspend fun execute(task: DelayedTaskDue): DelayedTaskOutcome {
        val sessionId = ContextMaintenanceKeys.sessionIdOf(task.key)
            ?: task.payload[ContextMaintenanceKeys.PAYLOAD_SESSION]
            ?: return DelayedTaskOutcome.DONE
        val group = task.payload[ContextMaintenanceKeys.PAYLOAD_GROUP]?.toBooleanStrictOrNull() ?: false
        return runSession(sessionId, group)
    }

    /** 执行体入口：守卫 → 会话锁（幂等核心）→ 维护内容。 */
    suspend fun runSession(sessionId: String, group: Boolean): DelayedTaskOutcome {
        // §C-3 执行守卫（oracle ContextMaintenanceWorker.kt:27-31）。
        if (isRecoveryActive()) {
            events(ContextMaintenanceEvent.Deferred(sessionId, ContextMaintenanceDeferReason.RECOVERY_ACTIVE))
            return DelayedTaskOutcome.RETRY
        }
        if (!isNetworkAvailable()) {
            events(ContextMaintenanceEvent.Deferred(sessionId, ContextMaintenanceDeferReason.NETWORK_UNAVAILABLE))
            return DelayedTaskOutcome.RETRY
        }
        if (sessionId.isBlank()) return DelayedTaskOutcome.DONE
        val session = store.getSession(sessionId) ?: run {
            events(ContextMaintenanceEvent.Skipped(sessionId, "session_deleted"))
            return DelayedTaskOutcome.DONE
        }
        // §C-4 会话锁：同一会话只允许一个执行体维护；锁被占 → retry。
        val lock = ContextMaintenanceLocks.forSession(sessionId)
        if (!lock.tryLock()) {
            events(ContextMaintenanceEvent.LockHeld(sessionId))
            return DelayedTaskOutcome.RETRY
        }
        return try {
            runLocked(session, group)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun runLocked(session: ChatSession, group: Boolean): DelayedTaskOutcome {
        val now = clock()
        val pendingKey = ContextMaintenanceKeys.rebuildPendingKey(session.id)
        val rebuildPending = settings.getBoolean(pendingKey)
        if (rebuildPending) {
            val rebuildOutcome = runRebuild(session, group, now)
            if (rebuildOutcome != null) return finishRebuildSession(rebuildOutcome, session.id, group)
        } else {
            // §C-5 非重建分支：
            // - failed 且关闭自动重试 → 跳过；
            // - failed 且未到退避时刻 → 延后；
            // - 其余（含 idle：回复后刚入队）→ 尝试生成一次。
            //   生成器只在「确有可压缩的旧轮次」时才调用模型，没有到期内容时会立刻返回 false 并把
            //   状态写回 idle，因此这里不必再自行门槛——否则回复后的触发永远不会产生摘要（审计 P0-2）。
            val status = settings.getRollingSummaryStatus(session.id)
            val autoRetry = settings.summaryAutoRetryEnabled
            when {
                ContextMaintenancePolicy.shouldSkipSummary(status.state, autoRetry) ->
                    events(ContextMaintenanceEvent.Skipped(session.id, "summary_auto_retry_disabled"))

                ContextMaintenancePolicy.summaryInBackoff(status.state, autoRetry, now, status.nextRetryAt) -> {
                    events(ContextMaintenanceEvent.Deferred(session.id, ContextMaintenanceDeferReason.SUMMARY_BACKOFF, status.nextRetryAt))
                    return finishRebuildSession(DelayedTaskOutcome.RETRY, session.id, group)
                }

                else -> {
                    val success = store.generateShortTermSummary(session, group)
                    events(ContextMaintenanceEvent.SummaryGenerated(session.id, success))
                    val after = settings.getRollingSummaryStatus(session.id)
                    if (after.state == "failed") {
                        events(ContextMaintenanceEvent.Deferred(session.id, ContextMaintenanceDeferReason.SUMMARY_BACKOFF, after.nextRetryAt))
                        return finishRebuildSession(DelayedTaskOutcome.RETRY, session.id, group)
                    }
                }
            }
        }
        // MemoryV2 维护：重试 pending 来源（oracle MemoryV2Pipeline.retryPendingSources）。
        runPendingSourceRetriesById(session.id)
        events(ContextMaintenanceEvent.Completed(session.id, group))
        return DelayedTaskOutcome.SUCCESS
    }

    /**
     * 重建路径（oracle ChatViewModel.kt:2222-2267）：
     * 推进记忆纪元 + 重置游标 + invalidateChangedMessage + 保留旧摘要 + 批量抽取 + 滚动摘要。
     * @return null = 重建完成可继续走尾部维护；非 null = 本次应直接返回的三态
     */
    private suspend fun runRebuild(session: ChatSession, group: Boolean, now: Long): DelayedTaskOutcome? {
        events(ContextMaintenanceEvent.RebuildStarted(session.id))
        // 重建成功前保留旧摘要可见（先读后写，绝不擦除）。
        store.shortTermSummary(session.id)?.takeIf { it.isNotBlank() }?.let { old ->
            settings.putString(ContextMaintenanceKeys.rebuildPreviousSummaryKey(session.id), old)
            events(ContextMaintenanceEvent.OldSummaryPreserved(session.id, old))
        }
        val changedId = settings.getString(ContextMaintenanceKeys.rebuildChangedKey(session.id)).toLongOrNull()
        if (changedId != null) {
            settings.advanceMemoryTimelineEpoch(session.id)
            settings.putMemoryExtractionCursor(session.id, 0L)
            settings.putSummaryCursor(session.id, 0L)
            store.invalidateChangedMessage(session.id, changedId)
            settings.putString(ContextMaintenanceKeys.rebuildChangedKey(session.id), "")
        }
        val memoryEnabled = if (group) settings.groupMemoryGenerationEnabled else settings.privateMemoryGenerationEnabled
        if (settings.memoryV2Enabled && memoryEnabled) {
            var batches = 0
            var messages = 0
            repeat(config.extractionMaxBatches) {
                val batchEpoch = settings.getMemoryTimelineEpoch(session.id)
                val cursor = settings.getMemoryExtractionCursor(session.id)
                val page = store.messagesAfterCursor(
                    sessionId = session.id,
                    afterMessageId = cursor,
                    restartAt = settings.getSessionRestartAt(session.id),
                    limit = config.extractionBatchLimit,
                )
                if (page.isEmpty()) return@repeat
                val batch = page.filter { it.type !in config.excludedMessageTypes }
                if (batch.isNotEmpty() && !store.ingestMemory(session, batch, group)) {
                    events(ContextMaintenanceEvent.Deferred(session.id, ContextMaintenanceDeferReason.EXTRACTION_PENDING))
                    return finishRebuildSession(DelayedTaskOutcome.RETRY, session.id, group)
                }
                if (batchEpoch != settings.getMemoryTimelineEpoch(session.id)) {
                    events(ContextMaintenanceEvent.Deferred(session.id, ContextMaintenanceDeferReason.TIMELINE_CHANGED))
                    return finishRebuildSession(DelayedTaskOutcome.RETRY, session.id, group)
                }
                settings.putMemoryExtractionCursor(session.id, page.maxOf { it.id })
                batches++
                messages += batch.size
            }
            if (batches > 0) events(ContextMaintenanceEvent.MemoryIngested(session.id, batches, messages))
            val more = store.messagesAfterCursor(
                sessionId = session.id,
                afterMessageId = settings.getMemoryExtractionCursor(session.id),
                restartAt = settings.getSessionRestartAt(session.id),
                limit = 1,
            )
            if (more.isNotEmpty()) {
                events(ContextMaintenanceEvent.Deferred(session.id, ContextMaintenanceDeferReason.EXTRACTION_PENDING))
                return finishRebuildSession(DelayedTaskOutcome.RETRY, session.id, group)
            }
        }
        val summaryEnabled = if (group) settings.groupSummaryGenerationEnabled else settings.privateSummaryGenerationEnabled
        if (summaryEnabled) {
            val status = settings.getRollingSummaryStatus(session.id)
            if (ContextMaintenancePolicy.shouldSkipSummary(status.state, settings.summaryAutoRetryEnabled)) {
                settings.putBoolean(ContextMaintenanceKeys.rebuildPendingKey(session.id), false)
                events(ContextMaintenanceEvent.Skipped(session.id, "summary_auto_retry_disabled"))
                return finishRebuildSession(DelayedTaskOutcome.SUCCESS, session.id, group)
            }
            if (ContextMaintenancePolicy.summaryInBackoff(
                    state = status.state,
                    autoRetryEnabled = settings.summaryAutoRetryEnabled,
                    now = now,
                    nextRetryAt = status.nextRetryAt,
                )
            ) {
                events(ContextMaintenanceEvent.Deferred(session.id, ContextMaintenanceDeferReason.SUMMARY_BACKOFF, status.nextRetryAt))
                return finishRebuildSession(DelayedTaskOutcome.RETRY, session.id, group)
            }
            val success = store.generateShortTermSummary(session, group)
            events(ContextMaintenanceEvent.SummaryGenerated(session.id, success))
            val after = settings.getRollingSummaryStatus(session.id)
            if (!success || after.state == "failed") {
                // 重建失败：保留 rebuild pending，后续触发继续补；旧摘要仍然可见。
                events(ContextMaintenanceEvent.Deferred(session.id, ContextMaintenanceDeferReason.SUMMARY_BACKOFF, after.nextRetryAt))
                return finishRebuildSession(DelayedTaskOutcome.RETRY, session.id, group)
            }
        }
        if (settings.summaryCursorEnabled && settings.getRollingSummaryStatus(session.id).pendingRounds > 0) {
            events(ContextMaintenanceEvent.Deferred(session.id, ContextMaintenanceDeferReason.SUMMARY_PENDING))
            return finishRebuildSession(DelayedTaskOutcome.RETRY, session.id, group)
        }
        settings.putBoolean(ContextMaintenanceKeys.rebuildPendingKey(session.id), false)
        return null
    }

    private suspend fun finishRebuildSession(
        outcome: DelayedTaskOutcome,
        sessionId: String,
        group: Boolean,
    ): DelayedTaskOutcome {
        if (outcome == DelayedTaskOutcome.SUCCESS) {
            runPendingSourceRetriesById(sessionId)
            events(ContextMaintenanceEvent.Completed(sessionId, group))
        }
        return outcome
    }

    /** MemoryV2 维护：重试 pending 记忆来源（§C-6；oracle MemoryV2Pipeline.retryPendingSources）。 */
    private suspend fun runPendingSourceRetriesById(sessionId: String) {
        if (!settings.memoryV2Enabled) return
        val retried = runCatching { store.retryPendingMemorySources(config.retryPendingSourcesLimit) }.getOrDefault(0)
        if (retried > 0) events(ContextMaintenanceEvent.PendingSourcesRetried(retried))
    }

    private fun payloadOf(sessionId: String, group: Boolean): Map<String, String> = mapOf(
        ContextMaintenanceKeys.PAYLOAD_SESSION to sessionId,
        ContextMaintenanceKeys.PAYLOAD_GROUP to group.toString(),
    )
}
