package com.rhodes.privatechat.shared.data

import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

class CleanupRepository(private val wrapper: DatabaseWrapper) {

    private val db: RhodesDatabase get() = wrapper.database

    // --- Cleanup ---
    suspend fun cleanupExpiredData() = withContext(Dispatchers.Default) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.memoryAnchorsQueries.deleteExpiredAnchors(now)
        db.memoriesQueries.deleteExpired(now)
        db.memoryItemsQueries.deleteExpiredMemoryItems(now, now)
        db.vectorMemoriesQueries.deleteExpiredVectorMemories(now)
        // Extraction sources are temporary work records, not another permanent chat archive.
        db.memorySourceQueueQueries.deleteProcessedMemorySourcesBefore(now - 30L * 86_400_000L)
        // Soft delete is a grace window, not the final state: purge rows that have been expired for
        // longer than the window and drop the parent/child links that pointed at them. Without this
        // the table only grows (audit P1-3) and "已过期" filtering is the only visible effect.
        db.transaction {
            db.memoryItemsQueries.purgeExpiredMemoryItems(now - EXPIRED_GRACE_MS)
            db.memoryLinksQueries.deleteOrphanedMemoryLinks()
        }
    }

    companion object {
        /** 软删后的宽限期：用户仍可在记忆管理页看到并恢复，之后物理删除。 */
        const val EXPIRED_GRACE_MS = 30L * 86_400_000L
    }
}