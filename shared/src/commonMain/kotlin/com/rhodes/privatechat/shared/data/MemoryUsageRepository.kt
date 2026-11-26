package com.rhodes.privatechat.shared.data

import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单条记忆的注入反馈计数（T73 切片）。
 *
 * 本切片只写 [injectionCount]/[lastInjectedAt]；retrieval/useful 先占列，
 * 供后续 salience 切片复用同一行，避免再动表结构。
 */
data class MemoryUsage(
    val memoryId: Long,
    val retrievalCount: Int = 0,
    val injectionCount: Int = 0,
    val usefulCount: Int = 0,
    val lastInjectedAt: Long = 0,
)

/**
 * memory_usage 读写：注入是召回热路径，只写独立窄表，不改 memory_items 主体行。
 *
 * bumpInjected 先 INSERT OR IGNORE 建行再 UPDATE 自增（SQLite 3.18 无 UPSERT）；
 * 同一轮里重复的 id 只算一次，非正 id（未落库的向量）直接忽略。
 */
class MemoryUsageRepository(private val db: RhodesDatabase) {

    suspend fun bumpInjected(ids: List<Long>, now: Long): Unit = withContext(Dispatchers.Default) {
        val targets = ids.filter { it > 0 }.distinct()
        if (targets.isEmpty()) return@withContext
        db.transaction {
            targets.forEach { id -> db.memoryUsageQueries.upsert(id) }
            db.memoryUsageQueries.bumpInjected(now, targets)
        }
    }

    /** 按 id 取已存在的计数行；不存在的 id（从未注入/已清理）不返回占位行。 */
    suspend fun get(ids: List<Long>): List<MemoryUsage> = withContext(Dispatchers.Default) {
        ids.filter { it > 0 }.distinct().mapNotNull { id ->
            db.memoryUsageQueries.getByMemory(id).executeAsOneOrNull()?.let { row ->
                MemoryUsage(
                    memoryId = row.memoryId,
                    retrievalCount = row.retrievalCount.toInt(),
                    injectionCount = row.injectionCount.toInt(),
                    usefulCount = row.usefulCount.toInt(),
                    lastInjectedAt = row.lastInjectedAt,
                )
            }
        }
    }
}