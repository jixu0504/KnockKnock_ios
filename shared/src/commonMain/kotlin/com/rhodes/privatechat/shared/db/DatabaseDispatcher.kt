package com.rhodes.privatechat.shared.db

import com.rhodes.privatechat.shared.ioDispatcher
import com.rhodes.privatechat.shared.platformSynchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.time.TimeSource

/**
 * SQLDelight calls are synchronous. Keep critical message and vector operations on one owned
 * lane so a timed-out caller cannot multiply those high-contention driver operations.
 */
object DatabaseDispatcher {
    val dispatcher: CoroutineDispatcher = ioDispatcher.limitedParallelism(1)
    private val lock = Any()
    private var runningTask = "idle"
    private var runningSince = 0L
    private var queuedTasks = 0

    data class Snapshot(val runningTask: String, val runningForMs: Long, val queuedTasks: Int)

    fun snapshot(): Snapshot = platformSynchronized(lock) {
        Snapshot(runningTask, if (runningSince == 0L) 0L else (Clock.System.now().toEpochMilliseconds() - runningSince).coerceAtLeast(0L), queuedTasks)
    }

    suspend fun <T> execute(task: String, block: () -> T): T {
        platformSynchronized(lock) { queuedTasks++ }
        val queuedAt = TimeSource.Monotonic.markNow()
        var started = false
        return try {
            withContext(dispatcher) {
                platformSynchronized(lock) {
                    queuedTasks--
                    started = true
                    runningTask = task
                    runningSince = Clock.System.now().toEpochMilliseconds()
                }
                try {
                    block()
                } finally {
                    platformSynchronized(lock) {
                        runningTask = "idle"
                        runningSince = 0L
                    }
                }
            }
        } catch (error: Throwable) {
            if (!started) platformSynchronized(lock) { queuedTasks-- }
            throw error
        }
    }
}
