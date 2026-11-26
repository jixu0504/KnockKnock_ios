package com.rhodes.privatechat.shared.data

import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一条记忆上挂的实体及其角色（subject/object）。 */
data class MemoryEntityRef(
    val entityId: Long,
    val role: String,
    val kind: String,
    val name: String,
    val normalizedName: String,
)

/**
 * 实体索引读写（T69）。实体按 owner 隔离：同一名字在不同角色/隐私范围下是不同记录。
 *
 * [upsertEntity] 先按 (owner, kind, normalizedName) 查，命中即复用 id，未命中才插入；
 * 调用方可在同一写入流程里重复调用而不产生重复实体。
 */
class MemoryEntityRepository(private val db: RhodesDatabase) {

    suspend fun upsertEntity(
        ownerType: String,
        ownerId: String,
        kind: String,
        name: String,
        normalizedName: String,
        now: Long,
    ): Long = withContext(Dispatchers.Default) {
        db.transactionWithResult {
            val queries = db.memoryEntitiesQueries
            queries.findEntity(ownerType, ownerId, kind, normalizedName).executeAsOneOrNull() ?: run {
                queries.insertEntity(ownerType, ownerId, kind, name, normalizedName, DEFAULT_CONFIDENCE, now)
                queries.lastInsertedEntityId().executeAsOne()
            }
        }
    }

    suspend fun linkMemory(memoryId: Long, entityId: Long, role: String): Unit =
        withContext(Dispatchers.Default) {
            db.memoryEntitiesQueries.linkMemory(memoryId, entityId, role)
        }

    /** owner 下的实体目录（实体通道用：把查询里出现的实体名换成 memoryId）。 */
    suspend fun listEntities(ownerType: String, ownerId: String, limit: Int): List<MemoryEntityRef> =
        withContext(Dispatchers.Default) {
            if (limit <= 0) return@withContext emptyList()
            db.memoryEntitiesQueries.listEntitiesForOwner(ownerType, ownerId, limit.toLong())
                .executeAsList()
                .map { row ->
                    MemoryEntityRef(
                        entityId = row.id,
                        role = "",
                        kind = row.kind,
                        name = row.name,
                        normalizedName = row.normalizedName,
                    )
                }
        }

    suspend fun findMemoryIdsByNames(
        ownerType: String,
        ownerId: String,
        normalizedNames: List<String>,
        limit: Int,
    ): List<Long> = withContext(Dispatchers.Default) {
        val names = normalizedNames.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty() || limit <= 0) return@withContext emptyList()
        db.memoryEntitiesQueries
            .findMemoryIdsByNames(ownerType, ownerId, names, limit.toLong())
            .executeAsList()
    }

    suspend fun getEntitiesForMemory(memoryId: Long): List<MemoryEntityRef> =
        withContext(Dispatchers.Default) {
            db.memoryEntitiesQueries.getEntitiesForMemory(memoryId).executeAsList().map { row ->
                MemoryEntityRef(
                    entityId = row.entityId,
                    role = row.role,
                    kind = row.kind,
                    name = row.name,
                    normalizedName = row.normalizedName,
                )
            }
        }

    private companion object {
        const val DEFAULT_CONFIDENCE = 0.8
    }
}