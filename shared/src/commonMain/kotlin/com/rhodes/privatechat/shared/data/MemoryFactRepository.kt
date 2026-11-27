package com.rhodes.privatechat.shared.data

import com.rhodes.privatechat.shared.db.FindByFactKey
import com.rhodes.privatechat.shared.db.GetByMemory
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 结构化事实行（T69）：一条 L1 记忆最多对应一条事实，`memoryId` 是主键。
 *
 * `validFrom/validTo` 给出事实的有效窗口（null = 未知/未关闭），`timeSource` 标记时间来源
 * （message / recorded / model…）。冲突状态先占位，真正的 supersede/contradicts 裁决属 T78。
 */
data class MemoryFact(
    val memoryId: Long,
    val ownerType: String,
    val ownerId: String,
    val subject: String = "",
    val predicate: String = "",
    val objectValue: String = "",
    val polarity: String = "unknown",
    val qualifiers: List<String> = emptyList(),
    val factKey: String = "",
    val validFrom: Long? = null,
    val validTo: Long? = null,
    val recordedAt: Long = 0,
    val timeSource: String = "unknown",
    val conflictStatus: String = "none",
    val updatedAt: Long = 0,
)

/**
 * 事实表读写（T69）。与 memory_items 分表：`SELECT *` 映射不动，事实字段独立演进。
 *
 * `findByFactKey` 只按 owner + factKey 取候选，「哪条仍有效」由调用方按 validTo/conflictStatus 判定。
 */
class MemoryFactRepository(private val db: RhodesDatabase) {

    private val queries get() = db.memoryFactsQueries

    suspend fun upsert(fact: MemoryFact): Unit = withContext(Dispatchers.Default) {
        queries.upsertFact(
            fact.memoryId,
            fact.ownerType,
            fact.ownerId,
            fact.subject,
            fact.predicate,
            fact.objectValue,
            fact.polarity,
            encodeQualifiers(fact.qualifiers),
            fact.factKey,
            fact.validFrom,
            fact.validTo,
            fact.recordedAt,
            fact.timeSource,
            fact.conflictStatus,
            fact.updatedAt,
        )
        Unit
    }

    suspend fun get(memoryId: Long): MemoryFact? = withContext(Dispatchers.Default) {
        queries.getByMemory(memoryId).executeAsOneOrNull()?.toModel()
    }

    suspend fun findByFactKey(ownerType: String, ownerId: String, factKey: String, limit: Int): List<MemoryFact> =
        withContext(Dispatchers.Default) {
            if (limit <= 0) return@withContext emptyList()
            queries.findByFactKey(ownerType, ownerId, factKey, limit.toLong()).executeAsList().map { it.toModel() }
        }

    /** T78：把涉及冲突的事实行标为待确认；不存在的行忽略，返回实际更新行数。 */
    suspend fun markConflict(memoryIds: Collection<Long>, now: Long): Int = withContext(Dispatchers.Default) {
        val ids = memoryIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return@withContext 0
        queries.transactionWithResult {
            ids.count { id -> queries.markFactConflict(now, id).value > 0 }
        }
    }

    /** T78：用户确认新事实后，新行 confirmed、旧行 superseded 并关闭有效期。 */
    suspend fun confirmConflict(newMemoryId: Long, oldMemoryId: Long, now: Long) = withContext(Dispatchers.Default) {
        db.transaction {
            queries.resolveFactConflict(now, newMemoryId)
            queries.supersedeFact(now, now, oldMemoryId)
        }
    }

    /** T78：单值谓词换值 → 旧事实 superseded 并关闭有效期。 */
    suspend fun markSuperseded(memoryIds: Collection<Long>, now: Long): Int = withContext(Dispatchers.Default) {
        val ids = memoryIds.distinct()
        if (ids.isEmpty()) return@withContext 0
        queries.supersedeFactsByIds(now, now, ids)
        ids.size
    }

    suspend fun countSuspected(ownerType: String, ownerId: String): Long = withContext(Dispatchers.Default) {
        queries.countSuspectedFacts(ownerType, ownerId).executeAsOne()
    }

    suspend fun listSuspected(ownerType: String, ownerId: String, limit: Int): List<Long> =
        withContext(Dispatchers.Default) {
            if (limit <= 0) return@withContext emptyList()
            queries.listSuspectedFacts(ownerType, ownerId, limit.toLong()).executeAsList()
        }

    private fun encodeQualifiers(values: List<String>): String =
        Json.encodeToString(ListSerializer(String.serializer()), values)

    private fun decodeQualifiers(text: String): List<String> =
        runCatching { Json.decodeFromString(ListSerializer(String.serializer()), text) }.getOrDefault(emptyList())

    private fun GetByMemory.toModel(): MemoryFact = toModel(
        memoryId = memoryId, ownerType = ownerType, ownerId = ownerId, subject = subject,
        predicate = predicate, objectValue = objectValue, polarity = polarity, qualifiers = qualifiers,
        factKey = factKey, validFrom = validFrom, validTo = validTo, recordedAt = recordedAt,
        timeSource = timeSource, conflictStatus = conflictStatus, updatedAt = updatedAt,
    )

    private fun FindByFactKey.toModel(): MemoryFact = toModel(
        memoryId = memoryId, ownerType = ownerType, ownerId = ownerId, subject = subject,
        predicate = predicate, objectValue = objectValue, polarity = polarity, qualifiers = qualifiers,
        factKey = factKey, validFrom = validFrom, validTo = validTo, recordedAt = recordedAt,
        timeSource = timeSource, conflictStatus = conflictStatus, updatedAt = updatedAt,
    )

    private fun toModel(
        memoryId: Long,
        ownerType: String,
        ownerId: String,
        subject: String,
        predicate: String,
        objectValue: String,
        polarity: String,
        qualifiers: String,
        factKey: String,
        validFrom: Long?,
        validTo: Long?,
        recordedAt: Long,
        timeSource: String,
        conflictStatus: String,
        updatedAt: Long,
    ): MemoryFact = MemoryFact(
        memoryId = memoryId,
        ownerType = ownerType,
        ownerId = ownerId,
        subject = subject,
        predicate = predicate,
        objectValue = objectValue,
        polarity = polarity,
        qualifiers = decodeQualifiers(qualifiers),
        factKey = factKey,
        validFrom = validFrom,
        validTo = validTo,
        recordedAt = recordedAt,
        timeSource = timeSource,
        conflictStatus = conflictStatus,
        updatedAt = updatedAt,
    )
}