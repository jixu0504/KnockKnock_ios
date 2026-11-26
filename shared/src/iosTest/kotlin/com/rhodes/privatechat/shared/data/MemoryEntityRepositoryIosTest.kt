package com.rhodes.privatechat.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T69 实体表在真实 SQLite 上的读写（NativeSqliteDriver + RhodesDatabase）。
 *
 * 覆盖：同 owner/kind/归一名的 upsert 去重、记忆关联幂等、按实体名与 owner 查 memoryId。
 */
class MemoryEntityRepositoryIosTest {

    private val now = 1_800_000_000_000L

    @OptIn(ExperimentalForeignApi::class)
    private fun withRepository(
        dbName: String,
        block: suspend (MemoryEntityRepository) -> Unit,
    ) = runBlocking {
        val driver = NativeSqliteDriver(
            RhodesDatabase.Schema,
            "${dbName}_${platform.posix.getpid()}_$now.db",
        )
        try {
            block(MemoryEntityRepository(RhodesDatabase(driver)))
        } finally {
            driver.close()
        }
    }

    @Test
    fun upsertEntityDeduplicatesWithinOwnerKindAndNormalizedName() = withRepository("memory_entity_upsert") { repository ->
        val first = repository.upsertEntity("operator", "op1", "entity", "川菜", "川菜", now)
        val again = repository.upsertEntity("operator", "op1", "entity", "川菜", "川菜", now + 1_000)
        assertTrue(first > 0)
        assertEquals(first, again, "同 owner/kind/归一名的实体应复用 id")

        val otherOwner = repository.upsertEntity("operator", "op2", "entity", "川菜", "川菜", now)
        val otherKind = repository.upsertEntity("operator", "op1", "dish", "川菜", "川菜", now)
        val otherName = repository.upsertEntity("operator", "op1", "entity", "粤菜", "粤菜", now)
        assertEquals(4, setOf(first, otherOwner, otherKind, otherName).size, "owner/kind/名字任一不同都应是新实体")

        // 去重后两条记忆指向同一实体 id，按名字查能同时命中
        repository.linkMemory(101L, first, "subject")
        repository.linkMemory(102L, again, "object")
        assertEquals(listOf(102L, 101L), repository.findMemoryIdsByNames("operator", "op1", listOf("川菜"), 10))
    }

    @Test
    fun linkMemoryIsIdempotentAndEntitiesComeBackWithRoles() = withRepository("memory_entity_link") { repository ->
        val subject = repository.upsertEntity("operator", "op1", "entity", "用户", "用户", now)
        val objectEntity = repository.upsertEntity("operator", "op1", "entity", "川菜", "川菜", now)

        repository.linkMemory(201L, subject, "subject")
        repository.linkMemory(201L, objectEntity, "object")
        repository.linkMemory(201L, objectEntity, "object")
        repository.linkMemory(201L, objectEntity, "mention")

        assertEquals(
            listOf("subject" to "用户", "object" to "川菜", "mention" to "川菜"),
            repository.getEntitiesForMemory(201L).map { it.role to it.name },
            "同一 (memory, entity, role) 只留一条；subject 排在 object 前",
        )
        assertTrue(repository.getEntitiesForMemory(201L).all { it.kind == "entity" })
        assertEquals(emptyList(), repository.getEntitiesForMemory(999L))
    }

    @Test
    fun findMemoryIdsByNamesScopesByOwnerAndHonoursLimit() = withRepository("memory_entity_find") { repository ->
        val sichuan = repository.upsertEntity("operator", "op1", "entity", "川菜", "川菜", now)
        val cat = repository.upsertEntity("operator", "op1", "entity", "猫", "猫", now)
        val coffee = repository.upsertEntity("operator", "op1", "entity", "咖啡", "咖啡", now)
        val otherOwner = repository.upsertEntity("operator", "op2", "entity", "川菜", "川菜", now)

        repository.linkMemory(301L, sichuan, "subject")
        repository.linkMemory(302L, sichuan, "object")
        repository.linkMemory(303L, cat, "object")
        repository.linkMemory(304L, coffee, "object")
        repository.linkMemory(305L, otherOwner, "object")

        assertEquals(listOf(302L, 301L), repository.findMemoryIdsByNames("operator", "op1", listOf("川菜"), 10))
        assertEquals(listOf(302L), repository.findMemoryIdsByNames("operator", "op1", listOf("川菜"), 1))
        assertEquals(
            listOf(304L, 303L, 302L, 301L),
            repository.findMemoryIdsByNames("operator", "op1", listOf("川菜", "猫", "咖啡"), 10),
        )
        assertEquals(listOf(305L), repository.findMemoryIdsByNames("operator", "op2", listOf("川菜"), 10))
        assertEquals(emptyList(), repository.findMemoryIdsByNames("operator", "op1", listOf("粤菜"), 10))
        assertEquals(emptyList(), repository.findMemoryIdsByNames("operator", "op1", emptyList(), 10))
        assertEquals(emptyList(), repository.findMemoryIdsByNames("operator", "op1", listOf("川菜"), 0))
    }
}