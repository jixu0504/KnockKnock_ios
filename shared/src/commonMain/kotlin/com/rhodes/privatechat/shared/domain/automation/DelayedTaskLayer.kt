package com.rhodes.privatechat.shared.domain.automation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * 通用延迟任务层 DelayedTaskLayer（A7 spec §A-12 冻结契约；A1 群聊波次先落地）。
 *
 * 口径（lead 裁决 + a7-预研 §2a 薄层结论）：iOS 不移植 Android 的持久任务调度框架；本层是唯一共享的调度原语。
 * **领域接口不依赖任何宿主后台框架（iOS 后台任务 / Android 持久任务 / 通知 SDK）**，宿主（iOS 壳波次 / T22 胶水批）
 * 实现 [DelayedTaskChannel]：
 * - 前台 `TimerDelayedTaskChannel`（进程内 Timer，主通道）
 * - `BackgroundRefreshDelayedTaskChannel`（系统后台刷新任务兜底通道；Info.plist 注册 permitted identifiers）
 *
 * 层语义（逐条对应 A7 §A-12）：
 * 1. 唯一键入队 + KEEP：同键已存在挂起/执行中任务时不重复入队、不重置 dueAt；
 * 2. 延迟下限钳 0：过期任务立即执行；
 * 3. 到期判定在执行时：不信任通道准点（系统后台调度为机会型，T13 实测）；早到则按剩余延迟重挂；
 * 4. 三态协议：`SUCCESS`/`DONE` 销号、`RETRY` 按 EXP 退避后重入队；
 * 5. 启动 reconcile：扫描窗口默认 24h，过期任务立即交给前台主通道，超窗任务丢弃；
 * 6. 双通道：主通道 + 兜底通道同时 schedule，两通道最终都调同一个执行体入口，内存去重保证只执行一次；
 * 7. 恢复护栏：备份/恢复进行中不执行、直接 retry；
 * 8. 层不做领域互斥（租约/锁由执行体负责）；
 * 9. cancel 只销号并取消通道任务，**不承诺中断已 running 任务**（等价 Android 语义）。
 */
data class DelayedTaskDue(
    val key: String,
    val dueAt: Long,
    val payload: Map<String, String> = emptyMap(),
    val attemptCount: Int = 0,
)

/** 冻结协议（A7 §A-12 / T22 契约；A1 波次与 A7 波次均不得单方面修改）。 */
interface DelayedTaskChannel {
    /** 入队/重挂（同键覆盖由通道实现按宿主能力决定；层已保证不重复 enqueue 未到期任务）。 */
    suspend fun schedule(key: String, dueAt: Long, payload: Map<String, String>)

    /** 取消通道侧挂起任务；不中断已开始执行的执行体。 */
    suspend fun cancel(key: String)

    /** 通道侧仍挂起的唯一键（启动 reconcile 对账用）。 */
    suspend fun pendingKeys(): Set<String>

    /** 到期回调（通道在到期点回调；语义为「机会型」，层负责真实到期判定）。 */
    fun onDue(callback: suspend (DelayedTaskDue) -> Unit)
}

/** 执行体三态结果（oracle MomentReplyWorker.kt:28,33 / ContextMaintenanceWorker.kt:28,31,39）。 */
enum class DelayedTaskOutcome { SUCCESS, RETRY, DONE }

/** 任务执行体（领域层注册；无此任务/已终态/会话已删 → DONE 幂等成功）。 */
fun interface DelayedTaskExecutor {
    suspend fun execute(task: DelayedTaskDue): DelayedTaskOutcome
}

/** 层配置（测试可压缩）。 */
data class DelayedTaskLayerConfig(
    /** 启动 reconcile 扫描窗口（oracle MainViewModel.kt:140 REPLY_RECONCILE_WINDOW_MS = 24h）。 */
    val reconcileWindowMs: Long = 24 * 60 * 60 * 1000L,
    /** EXP 退避初值 1min（oracle MomentReplyWorker.kt:19 / ContextMaintenanceWorker.kt:18）。 */
    val initialBackoffMs: Long = 60_000L,
    /** 退避指数上限（2^6=64min，超过后按上限线性）。 */
    val maxBackoffShift: Int = 6,
    /** RETRY 上限，超过后销号（避免无限重挂；领域级更精细退避优先）。 */
    val maxRetryAttempts: Int = 8,
    /** 到期容忍窗口：now + tolerance >= dueAt 即视为到期（不信任通道准点）。 */
    val dueToleranceMs: Long = 1_000L,
)

/**
 * 通用延迟任务层。
 *
 * @param primaryChannel 前台 Timer 主通道（进程内）
 * @param fallbackChannel 后台兜底通道（进程被杀后由系统择机唤醒；可为 null）
 * @param isRecoveryActive 备份/恢复护栏（true 时到期任务一律 retry，不执行）
 */
class DelayedTaskLayer(
    private val primaryChannel: DelayedTaskChannel,
    private val fallbackChannel: DelayedTaskChannel? = null,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val config: DelayedTaskLayerConfig = DelayedTaskLayerConfig(),
    private val isRecoveryActive: () -> Boolean = { false },
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private val tasks = mutableMapOf<String, DelayedTaskDue>()
    private val runningKeys = mutableSetOf<String>()
    private val handlers = mutableListOf<Pair<String, DelayedTaskExecutor>>()
    private var installed = false

    /** 注册执行体：按唯一键前缀匹配（最长前缀优先；无匹配按 DONE 处理）。 */
    fun registerHandler(prefix: String, executor: DelayedTaskExecutor) {
        handlers += prefix to executor
    }

    init {
        install()
    }

    private fun install() {
        if (installed) return
        installed = true
        val callback: suspend (DelayedTaskDue) -> Unit = { due -> onChannelDue(due) }
        primaryChannel.onDue(callback)
        fallbackChannel?.onDue(callback)
    }

    /**
     * 唯一键入队（KEEP 语义）：同键存在时不重复入队、不重置 dueAt。
     * @return true 表示新入队/重挂，false 表示被 KEEP 忽略。
     */
    suspend fun enqueue(key: String, dueAt: Long, payload: Map<String, String> = emptyMap()): Boolean {
        val now = clock()
        val clamped = dueAt.coerceAtLeast(0L).coerceAtLeast(now)
        val accepted = mutex.withLock {
            if (tasks.containsKey(key)) return@withLock false
            tasks[key] = DelayedTaskDue(key, clamped, payload, 0)
            true
        }
        if (!accepted) return false
        primaryChannel.schedule(key, clamped, payload)
        fallbackChannel?.schedule(key, clamped, payload)
        return true
    }

    /** 唯一键取消；不承诺中断已 running 任务（其执行结果被忽略）。 */
    suspend fun cancel(key: String) {
        mutex.withLock { tasks.remove(key) }
        primaryChannel.cancel(key)
        fallbackChannel?.cancel(key)
    }

    /** 内存视图的挂起键（测试/诊断用）。 */
    suspend fun pendingKeys(): Set<String> = mutex.withLock { tasks.keys.toSet() }

    /** 通道侧挂起键（启动 reconcile 对账用）。 */
    suspend fun channelPendingKeys(): Set<String> = primaryChannel.pendingKeys() + (fallbackChannel?.pendingKeys().orEmpty())

    /** 某键的当前记录。 */
    suspend fun task(key: String): DelayedTaskDue? = mutex.withLock { tasks[key] }

    /**
     * 启动 reconcile：把领域来源的应重试清单重新入队。
     * 超过 [DelayedTaskLayerConfig.reconcileWindowMs] 的旧任务丢弃（过期不补），窗口内任务交给主通道立即执行。
     * @return 实际入队的键（顺序与入参一致）
     */
    suspend fun reconcile(records: List<DelayedTaskDue>): List<String> {
        val now = clock()
        val enqueued = mutableListOf<String>()
        for (record in records) {
            if (record.dueAt < now - config.reconcileWindowMs) continue
            val dueAt = record.dueAt.coerceAtLeast(now)
            val accepted = mutex.withLock {
                if (tasks.containsKey(record.key)) return@withLock false
                tasks[record.key] = record.copy(dueAt = dueAt)
                true
            }
            if (accepted) {
                primaryChannel.schedule(record.key, dueAt, record.payload)
                fallbackChannel?.schedule(record.key, dueAt, record.payload)
                enqueued += record.key
            }
        }
        return enqueued
    }

    /** 通道到期回调入口：真实到期判定 + 双通道去重 + 三态调度。 */
    private suspend fun onChannelDue(due: DelayedTaskDue) {
        val now = clock()
        val task = mutex.withLock {
            val current = tasks[due.key] ?: return@withLock null
            // 双通道都到期：第一个进入者执行，其余忽略。
            if (due.key in runningKeys) return@withLock null
            if (now + config.dueToleranceMs < current.dueAt) {
                // 通道早到：按剩余延迟重挂，不在错误时刻执行（契约 #3）。
                current
            } else {
                runningKeys += due.key
                current
            }
        } ?: return
        if (now + config.dueToleranceMs < task.dueAt) {
            primaryChannel.schedule(task.key, task.dueAt, task.payload)
            fallbackChannel?.schedule(task.key, task.dueAt, task.payload)
            return
        }
        scope.launch {
            try {
                execute(task, now)
            } finally {
                mutex.withLock { runningKeys.remove(task.key) }
            }
        }
    }

    private suspend fun execute(task: DelayedTaskDue, now: Long) {
        // 恢复护栏（契约 #9）：不执行、直接 retry。
        if (isRecoveryActive()) {
            rescheduleRetry(task, now)
            return
        }
        val executor = handlerFor(task.key) ?: run {
            mutex.withLock { tasks.remove(task.key) }
            return
        }
        val outcome = try {
            executor.execute(task)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 执行异常 = retry（oracle ContextMaintenanceWorker.kt:40-41）。
            DelayedTaskOutcome.RETRY
        }
        when (outcome) {
            DelayedTaskOutcome.SUCCESS, DelayedTaskOutcome.DONE -> mutex.withLock { tasks.remove(task.key) }
            DelayedTaskOutcome.RETRY -> rescheduleRetry(task, clock())
        }
    }

    private suspend fun rescheduleRetry(task: DelayedTaskDue, now: Long) {
        val attempts = task.attemptCount + 1
        if (attempts > config.maxRetryAttempts) {
            mutex.withLock { tasks.remove(task.key) }
            return
        }
        val shift = (attempts - 1).coerceIn(0, config.maxBackoffShift)
        val delayMs = config.initialBackoffMs shl shift
        val next = task.copy(dueAt = now + delayMs, attemptCount = attempts)
        val keep = mutex.withLock {
            if (tasks.containsKey(task.key)) {
                tasks[task.key] = next
                true
            } else {
                false
            }
        }
        if (!keep) return
        primaryChannel.schedule(next.key, next.dueAt, next.payload)
        fallbackChannel?.schedule(next.key, next.dueAt, next.payload)
    }

    private fun handlerFor(key: String): DelayedTaskExecutor? =
        handlers.filter { key.startsWith(it.first) }.maxByOrNull { it.first.length }?.second
}
