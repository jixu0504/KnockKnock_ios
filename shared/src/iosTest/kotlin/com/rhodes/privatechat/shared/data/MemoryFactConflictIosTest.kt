package com.rhodes.privatechat.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.memory.MemoryRelationRules
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T78：冲突标记与待确认计数（NativeSqliteDriver + RhodesDatabase，不构造 DatabaseWrapper）。
 *
 * 覆盖：规则判定 contradicts 后新旧两条事实都标 suspected、supports 不标、
 * 不存在的 fact 忽略、owner 隔离、list 排序/limit、重复标记幂等。
 */
class MemoryFactConflictIosTest {

    private val now = 1_800_000_000_000L
    private val ownerType = "operator"
    private val ownerId = "op1"
    private val base = "用户|prefer|川菜"
    private val positiveKey = "fact:positive:$base"
    private val negativeKey = "fact:negative:$base"

    private fun fact(
        memoryId: Long,
        factKey: String,
        polarity: String,
        updatedAt: Long = now,
        ownerId: String = this.ownerId,
    ) = MemoryFact(
        memoryId = memoryId,
        ownerType = ownerType,
        ownerId = ownerId,
        subject = "用户",
        predicate = "prefer",
        objectValue = "川菜",
        polarity = polarity,
        factKey = factKey,
        validFrom = now - 1_000,
        recordedAt = now,
        timeSource = "message",
        conflictStatus = "none",
        updatedAt = updatedAt,
    )

    @OptIn(ExperimentalForeignApi::class)
    private fun withRepository(name: String, block: suspend (MemoryFactRepository) -> Unit) = runBlocking {
        val unique = "${platform.posix.getpid()}_${now}_$name"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "memory_fact_conflict_$unique.db")
        try {
            block(MemoryFactRepository(RhodesDatabase(driver)))
        } finally {
            driver.close()
        }
    }

    @Test
    fun contradictsRuleMarksBothFactsSuspected() = withRepository("contradicts") { repository ->
        repository.upsert(fact(1L, positiveKey, "positive"))
        repository.upsert(fact(2L, negativeKey, "negative", updatedAt = now + 1))

        assertEquals(
            MemoryRelationRules.CONTRADICTS,
            MemoryRelationRules.relationType(negativeKey, positiveKey),
            "同基座相反极性应判定为 contradicts",
        )

        assertEquals(2, repository.markConflict(listOf(2L, 1L), now + 10))
        assertEquals("suspected", repository.get(1L)?.conflictStatus)
        assertEquals(now + 10, repository.get(1L)?.updatedAt)
        assertEquals("suspected", repository.get(2L)?.conflictStatus)
        assertEquals(now + 10, repository.get(2L)?.updatedAt)
        assertEquals(2L, repository.countSuspected(ownerType, ownerId))
        assertEquals(listOf(2L, 1L), repository.listSuspected(ownerType, ownerId, limit = 10))
        assertEquals(listOf(2L), repository.listSuspected(ownerType, ownerId, limit = 1))
        assertEquals(emptyList(), repository.listSuspected(ownerType, ownerId, limit = 0))
        assertEquals(0L, repository.countSuspected(ownerType, "other"))
    }

    @Test
    fun supportsDoesNotMarkAndMissingFactIsIgnored() = withRepository("supports") { repository ->
        repository.upsert(fact(11L, positiveKey, "positive"))
        repository.upsert(fact(12L, positiveKey, "positive", updatedAt = now + 1))
        assertEquals(
            MemoryRelationRules.SUPPORTS,
            MemoryRelationRules.relationType(positiveKey, positiveKey),
            "同基座同极性应判定为 supports",
        )

        assertEquals(0, repository.markConflict(emptyList(), now))
        assertEquals(0, repository.markConflict(listOf(-1L, 0L), now))
        assertEquals("none", repository.get(11L)?.conflictStatus)
        assertEquals(0L, repository.countSuspected(ownerType, ownerId))

        repository.upsert(fact(13L, negativeKey, "negative", updatedAt = now + 2))
        assertEquals(1, repository.markConflict(listOf(13L, 999L), now + 10))
        assertNull(repository.get(999L))
        assertEquals(1L, repository.countSuspected(ownerType, ownerId))
        assertEquals(listOf(13L), repository.listSuspected(ownerType, ownerId, limit = 10))
    }

    @Test
    fun suspectedListIsOwnerScopedAndMarkIsIdempotent() = withRepository("owner") { repository ->
        repository.upsert(fact(21L, positiveKey, "positive"))
        repository.upsert(fact(22L, negativeKey, "negative", ownerId = "op2"))
        repository.upsert(fact(23L, positiveKey, "positive", updatedAt = now + 2))

        assertEquals(3, repository.markConflict(listOf(21L, 22L, 23L), now + 5))
        assertEquals(2L, repository.countSuspected(ownerType, "op1"))
        assertEquals(1L, repository.countSuspected(ownerType, "op2"))
        assertEquals(0L, repository.countSuspected(ownerType, "op3"))
        assertEquals(listOf(23L, 21L), repository.listSuspected(ownerType, "op1", limit = 10))
        assertEquals(listOf(22L), repository.listSuspected(ownerType, "op2", limit = 10))

        assertEquals(1, repository.markConflict(listOf(21L), now + 20))
        assertEquals(now + 20, repository.get(21L)?.updatedAt)
        assertEquals(2L, repository.countSuspected(ownerType, "op1"))
        assertEquals(listOf(21L, 23L), repository.listSuspected(ownerType, "op1", limit = 10))
    }
}