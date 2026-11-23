package com.rhodes.privatechat.shared.domain.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 通用延迟任务层（A7 §A-12 验收 #1-#3 的 shared 层矩阵）：
 * 唯一键 KEEP / 延迟钳 0 / 到期判定在执行时 / 三态协议 + EXP 1min / 双通道去重 /
 * 启动 reconcile 24h 窗口 / 恢复护栏 / cancel 不中断 running。
 */
class DelayedTaskLayerTest {

    /** 可手动触发的 fake 通道（模拟 Timer/BGTask 两种宿主实现）。 */
    private class FakeChannel(private val name: String) : DelayedTaskChannel {
        val scheduled = mutableMapOf<String, Pair<Long, Map<String, String>>>()
        val cancelled = mutableListOf<String>()
        private var callback: (suspend (DelayedTaskDue) -> Unit)? = null
        var scheduleCount = 0

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

    private class Clock(var now: Long = 1_700_000_000_000L) {
        fun read(): Long = now
    }

    private class Env(
        val config: DelayedTaskLayerConfig = DelayedTaskLayerConfig(),
        dualChannel: Boolean = false,
        val recoveryActive: Boolean = false,
    ) {
        val clock = Clock()
        val primary = FakeChannel("timer")
        val fallback = if (dualChannel) FakeChannel("bgtask") else null
        val scope = CoroutineScope(Dispatchers.Default)
        val executed = mutableListOf<DelayedTaskDue>()
        val outcomes = mutableListOf<DelayedTaskOutcome>()
        var handlerCalls = 0
        val layer = DelayedTaskLayer(
            primaryChannel = primary,
            fallbackChannel = fallback,
            scope = scope,
            clock = clock::read,
            config = config,
            isRecoveryActive = { recoveryActive },
        )

        fun handler(outcome: DelayedTaskOutcome = DelayedTaskOutcome.SUCCESS): DelayedTaskExecutor =
            DelayedTaskExecutor { task ->
                handlerCalls++
                executed += task
                outcomes += outcome
                outcome
            }
    }

    private suspend fun awaitExecutions(count: Int, env: Env): Boolean =
        withTimeoutOrNull(3_000L) {
            while (env.executed.size < count) kotlinx.coroutines.delay(5)
            true
        } == true

    private suspend fun awaitHandlerCalls(count: Int, env: Env): Boolean =
        withTimeoutOrNull(3_000L) {
            while (env.handlerCalls < count) kotlinx.coroutines.delay(5)
            true
        } == true

    // === 验收 #1：入队/重复入队矩阵 ===

    @Test
    fun enqueueKeepsExistingTaskWithoutResettingDueAt() = runBlocking {
        val env = Env()
        assertTrue(env.layer.enqueue("k1", env.clock.now + 10_000L, mapOf("a" to "1")))
        assertFalse(env.layer.enqueue("k1", env.clock.now + 99_000L, mapOf("a" to "2")), "同键二次入队必须被忽略")
        assertEquals(env.clock.now + 10_000L, env.primary.scheduled["k1"]?.first)
        assertEquals(mapOf("a" to "1"), env.primary.scheduled["k1"]?.second)
        assertEquals(1, env.primary.scheduleCount)
    }

    @Test
    fun cancelThenEnqueueWorksAndCancelMissingKeyIsNoOp() = runBlocking {
        val env = Env()
        env.layer.enqueue("k1", env.clock.now + 5_000L)
        env.layer.cancel("k1")
        assertNull(env.layer.task("k1"))
        assertTrue(env.primary.cancelled.contains("k1"))
        env.layer.cancel("does-not-exist")
        assertTrue(env.layer.enqueue("k1", env.clock.now + 1_000L))
        assertEquals(env.clock.now + 1_000L, env.primary.scheduled["k1"]?.first)
    }

    // === 验收 #2：到期执行 ===

    @Test
    fun pastDueTasksExecuteImmediatelyAfterClamp() = runBlocking {
        val env = Env()
        env.layer.registerHandler("k", env.handler())
        env.layer.enqueue("k1", env.clock.now - 60_000L)
        assertEquals(env.clock.now, env.primary.scheduled["k1"]?.first ?: -1L, "延迟下限钳 0（过期任务落 now）")
        env.primary.fire("k1")
        assertTrue(awaitExecutions(1, env))
        assertEquals(1, env.handlerCalls)
        assertNull(env.layer.task("k1"), "SUCCESS 后销号")
    }

    @Test
    fun earlyChannelCallbackReschedulesInsteadOfExecuting() = runBlocking {
        val env = Env()
        env.layer.registerHandler("k", env.handler())
        env.layer.enqueue("k1", env.clock.now + 30_000L)
        env.primary.fire("k1")
        kotlinx.coroutines.delay(100)
        assertEquals(0, env.handlerCalls, "通道早到不得执行")
        assertEquals(env.clock.now + 30_000L, env.primary.scheduled["k1"]?.first)
        env.clock.now += 30_000L
        env.primary.fire("k1")
        assertTrue(awaitExecutions(1, env))
    }

    // === 验收 #3：三态协议 + EXP 1min ===

    @Test
    fun retrySchedulesExponentialBackoffStartingAtOneMinute() = runBlocking {
        val env = Env()
        env.layer.registerHandler("k", env.handler(DelayedTaskOutcome.RETRY))
        env.layer.enqueue("k1", env.clock.now)
        env.primary.fire("k1")
        assertTrue(awaitHandlerCalls(1, env))
        kotlinx.coroutines.delay(100)
        val firstRetryAt = env.primary.scheduled["k1"]?.first
        assertEquals(env.clock.now + 60_000L, firstRetryAt)

        // 第二次失败 → 2min；时间推进到 dueAt 后 fire。
        env.clock.now = firstRetryAt!!
        env.primary.fire("k1")
        assertTrue(awaitHandlerCalls(2, env))
        kotlinx.coroutines.delay(100)
        assertEquals(env.clock.now + 120_000L, env.primary.scheduled["k1"]?.first)
        assertEquals(2, env.layer.task("k1")?.attemptCount ?: -1)
    }

    @Test
    fun retryStopsAfterMaxAttempts() = runBlocking {
        val env = Env(DelayedTaskLayerConfig(maxRetryAttempts = 2))
        env.layer.registerHandler("k", env.handler(DelayedTaskOutcome.RETRY))
        env.layer.enqueue("k1", env.clock.now)
        repeat(6) {
            env.primary.fire("k1")
            kotlinx.coroutines.delay(60)
            val next = env.primary.scheduled["k1"]?.first ?: return@repeat
            env.clock.now = next
        }
        assertTrue(env.handlerCalls <= 3)
        assertNull(env.layer.task("k1"), "超限后销号")
    }

    @Test
    fun doneAndSuccessBothDeregister() = runBlocking {
        for (outcome in listOf(DelayedTaskOutcome.DONE, DelayedTaskOutcome.SUCCESS)) {
            val env = Env()
            env.layer.registerHandler("k", env.handler(outcome))
            env.layer.enqueue("k1", env.clock.now)
            env.primary.fire("k1")
            assertTrue(awaitHandlerCalls(1, env))
            assertNull(env.layer.task("k1"))
        }
    }

    @Test
    fun unregisteredHandlerDeregistersIdempotently() = runBlocking {
        val env = Env()
        env.layer.enqueue("unknown-key", env.clock.now)
        env.primary.fire("unknown-key")
        kotlinx.coroutines.delay(100)
        assertNull(env.layer.task("unknown-key"))
        assertEquals(0, env.handlerCalls)
    }

    // === 双通道去重 ===

    @Test
    fun dualChannelsDeliverButExecuteExactlyOnce() = runBlocking {
        val env = Env(dualChannel = true)
        env.layer.registerHandler("k", env.handler())
        env.layer.enqueue("k1", env.clock.now)
        assertTrue(env.primary.scheduled.containsKey("k1"))
        assertTrue(env.fallback?.scheduled?.containsKey("k1") == true, "兜底通道也必须 schedule")
        env.primary.fire("k1")
        env.fallback?.fire("k1")
        assertTrue(awaitExecutions(1, env))
        kotlinx.coroutines.delay(150)
        assertEquals(1, env.handlerCalls, "双通道并发到期只执行一次")
    }

    // === 启动 reconcile ===

    @Test
    fun reconcileEnqueuesWithinWindowAndDropsOlderThan24h() = runBlocking {
        val env = Env()
        val records = listOf(
            DelayedTaskDue("fresh", env.clock.now - 60_000L),
            DelayedTaskDue("edge", env.clock.now - 24 * 60 * 60 * 1000L),
            DelayedTaskDue("stale", env.clock.now - 24 * 60 * 60 * 1000L - 1L),
            DelayedTaskDue("future", env.clock.now + 10_000L),
        )
        val enqueued = env.layer.reconcile(records)
        assertEquals(listOf("fresh", "edge", "future"), enqueued)
        assertEquals(env.clock.now, env.primary.scheduled["fresh"]?.first, "过期任务立即执行（钳 now）")
        assertEquals(env.clock.now + 10_000L, env.primary.scheduled["future"]?.first)
    }

    @Test
    fun reconcileKeepsAlreadyPendingKeysAndReportsChannelPending() = runBlocking {
        val env = Env()
        env.layer.enqueue("k1", env.clock.now + 5_000L)
        env.layer.registerHandler("k", env.handler())
        assertTrue(env.layer.reconcile(listOf(DelayedTaskDue("k1", env.clock.now))).isEmpty())
        assertTrue(env.layer.channelPendingKeys().contains("k1"))
        assertTrue(env.layer.pendingKeys().contains("k1"))
    }

    // === 恢复护栏 ===

    @Test
    fun recoveryGuardRetriesInsteadOfExecuting() = runBlocking {
        val env = Env(recoveryActive = true)
        env.layer.registerHandler("k", env.handler())
        env.layer.enqueue("k1", env.clock.now)
        env.primary.fire("k1")
        kotlinx.coroutines.delay(200)
        assertEquals(0, env.handlerCalls, "备份/恢复进行中不执行")
        assertEquals(env.clock.now + 60_000L, env.primary.scheduled["k1"]?.first, "直接 retry")
    }

    // === cancel 语义 ===

    @Test
    fun cancelDoesNotInterruptRunningTaskAndIgnoresItsOutcome() = runBlocking {
        val env = Env()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        env.layer.registerHandler(
            "k",
            DelayedTaskExecutor {
                started.complete(Unit)
                release.await()
                DelayedTaskOutcome.SUCCESS
            },
        )
        env.layer.enqueue("k1", env.clock.now)
        env.primary.fire("k1")
        assertTrue(withTimeoutOrNull(2_000L) { started.await(); true } == true)
        env.layer.cancel("k1")
        assertNull(env.layer.task("k1"))
        release.complete(Unit)
        kotlinx.coroutines.delay(150)
        // 不中断、不复活：任务保持销号，无异常。
        assertNull(env.layer.task("k1"))
    }

    // === 冻结接口形态（A7 §A-12） ===

    @Test
    fun channelProtocolExposesTheFourFrozenOperations() {
        // 编译期锁定：schedule / cancel / pendingKeys / onDue（A1 与 A7 波次不得单方面修改）。
        val channel: DelayedTaskChannel = FakeChannel("contract")
        assertNotNull(channel)
        assertEquals(1_000L, DelayedTaskLayerConfig().dueToleranceMs)
        assertEquals(60_000L, DelayedTaskLayerConfig().initialBackoffMs)
        assertEquals(24 * 60 * 60 * 1000L, DelayedTaskLayerConfig().reconcileWindowMs)
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
}
