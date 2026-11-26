package com.rhodes.privatechat.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** T78：确认冲突后新事实 confirmed、旧事实 superseded 且 validTo 关闭。 */
class MemoryFactConfirmIosTest {

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun confirmingConflictClosesOldValidity() = runBlocking {
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "fact_confirm_${platform.posix.getpid()}.db")
        val db = RhodesDatabase(driver)
        try {
            val q = db.memoryFactsQueries
            q.upsertFact(1L, "operator", "op1", "用户", "prefer", "川菜", "positive", "[]", "fact:positive:用户|prefer|川菜", 0L, null, 0L, "message", "suspected", 0L)
            q.upsertFact(2L, "operator", "op1", "用户", "prefer", "川菜", "negative", "[]", "fact:negative:用户|prefer|川菜", 0L, null, 0L, "message", "suspected", 0L)

            MemoryFactRepository(db).confirmConflict(newMemoryId = 2L, oldMemoryId = 1L, now = 9_000L)

            assertEquals("superseded", q.getByMemory(1L).executeAsOne().conflictStatus)
            assertEquals(9_000L, q.getByMemory(1L).executeAsOne().validTo)
            assertEquals("confirmed", q.getByMemory(2L).executeAsOne().conflictStatus)
            assertEquals(null, q.getByMemory(2L).executeAsOne().validTo)
            assertEquals(0L, q.countSuspectedFacts("operator", "op1").executeAsOne())
        } finally {
            driver.close()
        }
    }
}
