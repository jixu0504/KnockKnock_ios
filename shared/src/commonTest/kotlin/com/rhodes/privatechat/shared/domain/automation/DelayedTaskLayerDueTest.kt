package com.rhodes.privatechat.shared.domain.automation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验收 #2（承接 A1 交付层的验证责任）：到期判定——fake-clock 加速。
 * dueAt≤now → 通道到期立即执行；dueAt=now+Δ → 推进前不执行、推进 Δ 后执行恰好一次；
 * 通道早到 → 按剩余延迟重挂而非在错误时刻执行（§A-3，不信任通道准点）。
 */
class DelayedTaskLayerDueTest {

    private class Env {
        val clock = TestAutomationClock()
        val channel = FakeDelayedTaskChannel("timer")
        val scope = CoroutineScope(Dispatchers.Default)
        val executed = mutableListOf<DelayedTaskDue>()
        val layer = DelayedTaskLayer(
            primaryChannel = channel,
            scope = scope,
            clock = clock::read,
            config = DelayedTaskLayerConfig(dueToleranceMs = 1_000L),
        )

        init {
            layer.registerHandler("due-") { task ->
                executed += task
                DelayedTaskOutcome.SUCCESS
            }
        }

        suspend fun awaitExecutions(count: Int): Boolean = withTimeoutOrNull(3_000L) {
            while (executed.size < count) delay(5)
            true
        } == true
    }

    @Test
    fun overdueTaskExecutesImmediatelyWhenChannelFires() = runBlocking {
        val env = Env()
        assertTrue(env.layer.enqueue("due-overdue", env.clock.now - 60_000L))
        assertEquals(env.clock.now, env.channel.scheduled.getValue("due-overdue").first, "延迟钳 0（§A-2）")
        env.channel.fire("due-overdue")
        assertTrue(env.awaitExecutions(1))
        assertEquals("due-overdue", env.executed.single().key)
    }

    @Test
    fun futureTaskDoesNotExecuteEarlyAndExecutesExactlyOnceWhenDue() = runBlocking {
        val env = Env()
        val dueAt = env.clock.now + 5_000L
        env.layer.enqueue("due-future", dueAt)

        env.channel.fire("due-future")
        delay(100)
        assertEquals(0, env.executed.size, "未到期不得执行（§A-3）")
        assertEquals(dueAt, env.channel.scheduled.getValue("due-future").first, "早到 → 按剩余延迟重挂")

        env.clock.now = dueAt
        env.channel.fire("due-future")
        assertTrue(env.awaitExecutions(1))
        assertEquals(dueAt, env.executed.single().dueAt)

        env.channel.fire("due-future")
        delay(100)
        assertEquals(1, env.executed.size, "到期后重挂的通道回调不得重复执行")
    }

    @Test
    fun withinToleranceWindowIsTreatedAsDue() = runBlocking {
        val env = Env()
        val dueAt = env.clock.now + 5_000L
        env.layer.enqueue("due-tolerance", dueAt)
        // 容忍窗口内（dueAt - 500ms，now + tolerance >= dueAt）视为到期，不再重挂。
        env.clock.now = dueAt - 500L
        env.channel.fire("due-tolerance")
        assertTrue(env.awaitExecutions(1))
    }

    @Test
    fun outsideToleranceWindowIsRescheduledNotExecuted() = runBlocking {
        val env = Env()
        val dueAt = env.clock.now + 5_000L
        env.layer.enqueue("due-early", dueAt)
        env.clock.now = dueAt - 3_000L // now + 1000 < dueAt → 早到
        env.channel.fire("due-early")
        delay(100)
        assertEquals(0, env.executed.size)
        assertEquals(dueAt, env.channel.scheduled.getValue("due-early").first)
    }
}
