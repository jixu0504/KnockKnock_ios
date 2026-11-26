package com.rhodes.privatechat.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T73：memory_usage 在真实 SQLite 上的读写与自增（NativeSqliteDriver + RhodesDatabase）。
 *
 * 覆盖：首次 bump 建行、重复 bump 自增并更新 lastInjectedAt、同批重复/非正 id 只算一次、
 * get 只返回已存在的行。
 */
class MemoryUsageIosTest {

    private val now = 1_800_000_000_000L

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun bumpInjectedCreatesRowAndIncrementsCounters() = runBlocking {
        val unique = "${platform.posix.getpid()}_$now"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "memory_usage_test_$unique.db")
        val db = RhodesDatabase(driver)
        try {
            val repository = MemoryUsageRepository(db)

            repository.bumpInjected(listOf(11L), now)
            assertEquals(
                listOf(MemoryUsage(memoryId = 11L, injectionCount = 1, lastInjectedAt = now)),
                repository.get(listOf(11L)),
            )

            // 同一批里的重复 id、0 与负数都不应多算或建行
            repository.bumpInjected(listOf(11L, 11L, 0L, -2L), now + 1_000)
            assertEquals(
                listOf(MemoryUsage(memoryId = 11L, injectionCount = 2, lastInjectedAt = now + 1_000)),
                repository.get(listOf(11L)),
            )

            repository.bumpInjected(listOf(12L), now + 2_000)
            assertEquals(
                listOf(
                    MemoryUsage(memoryId = 11L, injectionCount = 2, lastInjectedAt = now + 1_000),
                    MemoryUsage(memoryId = 12L, injectionCount = 1, lastInjectedAt = now + 2_000),
                ),
                repository.get(listOf(11L, 12L)),
            )
            assertEquals(2, repository.get(listOf(11L, 12L)).size, "不应为重复 id 建第二行")
        } finally {
            driver.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun getOnlyReturnsExistingRowsAndEmptyInputIsNoOp() = runBlocking {
        val unique = "${platform.posix.getpid()}_${now + 1}"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "memory_usage_test_$unique.db")
        val db = RhodesDatabase(driver)
        try {
            val repository = MemoryUsageRepository(db)

            assertEquals(emptyList(), repository.get(listOf(99L)))
            assertEquals(emptyList(), repository.get(emptyList()))

            repository.bumpInjected(emptyList(), now)
            assertEquals(emptyList(), repository.get(listOf(99L)))

            repository.bumpInjected(listOf(21L), now)
            assertEquals(listOf(21L), repository.get(listOf(21L, 99L)).map { it.memoryId })
            assertEquals(0, repository.get(listOf(21L)).single().retrievalCount, "本切片不写 retrievalCount")
            assertEquals(0, repository.get(listOf(21L)).single().usefulCount, "本切片不写 usefulCount")
        } finally {
            driver.close()
        }
    }
}