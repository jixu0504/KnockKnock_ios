package com.rhodes.privatechat.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T69：memory_facts 在真实 SQLite 上的读写（NativeSqliteDriver + RhodesDatabase，不构造 DatabaseWrapper）。
 *
 * 覆盖：upsert 幂等（同一 memoryId 覆盖）、get、按 owner+factKey 查询与 limit 排序、
 * qualifiers JSON 往返、不存在时返回 null。
 */
class MemoryFactIosTest {

    private val now = 1_800_000_000_000L
    private val key = "fact:positive:用户|prefer|川菜"

    private fun fact(memoryId: Long, updatedAt: Long = now, qualifiers: List<String> = listOf("周末")) = MemoryFact(
        memoryId = memoryId,
        ownerType = "operator",
        ownerId = "op1",
        subject = "用户",
        predicate = "prefer",
        objectValue = "川菜",
        polarity = "positive",
        qualifiers = qualifiers,
        factKey = key,
        validFrom = now - 1_000,
        validTo = null,
        recordedAt = now,
        timeSource = "message",
        conflictStatus = "none",
        updatedAt = updatedAt,
    )

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun upsertGetAndFindByFactKeyRoundTripThroughSqlite() = runBlocking {
        val unique = "${platform.posix.getpid()}_$now"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "memory_fact_test_$unique.db")
        val db = RhodesDatabase(driver)
        try {
            val repository = MemoryFactRepository(db)

            repository.upsert(fact(11L))
            repository.upsert(fact(12L, updatedAt = now + 1_000, qualifiers = emptyList()))

            assertEquals(fact(11L), repository.get(11L))
            assertEquals(emptyList(), repository.get(12L)?.qualifiers)
            assertNull(repository.get(99L))

            val hits = repository.findByFactKey("operator", "op1", key, limit = 10)
            assertEquals(listOf(12L, 11L), hits.map { it.memoryId }, "同一事实键按 updatedAt 倒序")
            assertEquals(listOf(12L), repository.findByFactKey("operator", "op1", key, limit = 1).map { it.memoryId })
            assertEquals(emptyList(), repository.findByFactKey("operator", "op2", key, limit = 10))
            assertEquals(emptyList(), repository.findByFactKey("operator", "op1", key, limit = 0))

            // 同一 memoryId 再次 upsert：覆盖而不是新增（INSERT OR REPLACE）
            repository.upsert(
                fact(11L, updatedAt = now + 2_000).copy(
                    validTo = now + 1_500,
                    conflictStatus = "superseded",
                    timeSource = "recorded",
                )
            )
            val replaced = repository.get(11L)
            assertEquals(now + 2_000, replaced?.updatedAt)
            assertEquals(now + 1_500, replaced?.validTo)
            assertEquals("superseded", replaced?.conflictStatus)
            assertEquals("recorded", replaced?.timeSource)
            assertEquals(2, repository.findByFactKey("operator", "op1", key, limit = 10).size)
            assertEquals(listOf(11L, 12L), repository.findByFactKey("operator", "op1", key, limit = 10).map { it.memoryId })
        } finally {
            driver.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun qualifiersAndNullableValiditySurviveJsonEncoding() = runBlocking {
        val unique = "${platform.posix.getpid()}_${now + 1}"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "memory_fact_test_$unique.db")
        val db = RhodesDatabase(driver)
        try {
            val repository = MemoryFactRepository(db)
            val multi = fact(21L, qualifiers = listOf("周末", "聚餐", "辣")).copy(validFrom = null, validTo = now)
            repository.upsert(multi)
            val loaded = repository.get(21L)
            assertEquals(multi, loaded)
        } finally {
            driver.close()
        }
    }
}