package com.rhodes.privatechat.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** T78 数据基础：关系表写入/读取与幂等（同一 from/to/type 只留一条）。 */
class MemoryRelationIosTest {

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun relationsAreInsertedOnceAndQueryableBothDirections() = runBlocking {
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "memory_relation_test_${platform.posix.getpid()}.db")
        val db = RhodesDatabase(driver)
        try {
            val queries = db.memoryRelationsQueries
            queries.insertMemoryRelation(10L, 11L, "supports", 0.85, "rule", 1_000L)
            queries.insertMemoryRelation(10L, 11L, "supports", 0.85, "rule", 1_000L)
            queries.insertMemoryRelation(12L, 10L, "contradicts", 0.6, "rule", 2_000L)

            assertEquals(2L, queries.countMemoryRelations().executeAsOne())
            assertEquals(2, queries.getRelationsForMemory(10L, 10L).executeAsList().size)
            assertEquals(1, queries.getRelationsForMemory(11L, 11L).executeAsList().size)
            assertEquals(
                "contradicts",
                queries.getRelationsForMemory(12L, 12L).executeAsList().single().relationType,
            )
        } finally {
            driver.close()
        }
    }
}
