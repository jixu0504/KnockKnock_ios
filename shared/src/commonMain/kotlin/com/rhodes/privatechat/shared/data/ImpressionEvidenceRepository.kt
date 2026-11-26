package com.rhodes.privatechat.shared.data

import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.memory.PlannedImpressionEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** memory_impression_evidence 的一行（长期记录：这个印象当时由哪条记忆支撑、权重多少）。 */
data class ImpressionEvidence(
    val impressionId: Long,
    val memoryId: Long,
    val weight: Double,
    val createdAt: Long,
)

/**
 * 印象证据链读写（T81a）。
 *
 * link 用 INSERT OR REPLACE：同一 (impressionId, memoryId) 重复挂链只覆盖权重与时间，不产生第二行，
 * 因此「重建某印象的证据」可以重复执行。记忆被物理清理后，证据行由 [deleteOrphans] 或
 * [deleteByMemory] 回收；印象删除时用 [unlink] 一次清空。
 */
class ImpressionEvidenceRepository(private val db: RhodesDatabase) {

    suspend fun link(impressionId: Long, memoryId: Long, weight: Double = 1.0, now: Long = 0L) {
        withContext(Dispatchers.Default) {
            db.impressionEvidenceQueries.link(impressionId, memoryId, weight, now)
        }
    }

    /** 批量挂链（通常直接喂 ImpressionEvidencePlanner.plan 的结果），单事务内完成。 */
    suspend fun linkAll(impressionId: Long, evidence: List<PlannedImpressionEvidence>, now: Long) {
        if (evidence.isEmpty()) return
        withContext(Dispatchers.Default) {
            db.transaction {
                evidence.forEach { planned ->
                    db.impressionEvidenceQueries.link(impressionId, planned.memoryId, planned.weight, now)
                }
            }
        }
    }

    /** 印象被删除/重建时清空它的全部证据。 */
    suspend fun unlink(impressionId: Long) {
        withContext(Dispatchers.Default) {
            db.impressionEvidenceQueries.unlinkByImpression(impressionId)
        }
    }

    /** 权重降序（最有力的证据在前），同权重按 memoryId 升序保证顺序稳定。 */
    suspend fun list(impressionId: Long): List<ImpressionEvidence> = withContext(Dispatchers.Default) {
        db.impressionEvidenceQueries.listForImpression(impressionId).executeAsList().map { row ->
            ImpressionEvidence(
                impressionId = row.impressionId,
                memoryId = row.memoryId,
                weight = row.weight,
                createdAt = row.createdAt,
            )
        }
    }

    suspend fun count(impressionId: Long): Long = withContext(Dispatchers.Default) {
        db.impressionEvidenceQueries.countForImpression(impressionId).executeAsOne()
    }

    /** 清理指向已不存在记忆的证据行。 */
    suspend fun deleteOrphans() {
        withContext(Dispatchers.Default) {
            db.impressionEvidenceQueries.deleteOrphans()
        }
    }

    suspend fun deleteByMemory(memoryId: Long) {
        withContext(Dispatchers.Default) {
            db.impressionEvidenceQueries.deleteByMemory(memoryId)
        }
    }
}