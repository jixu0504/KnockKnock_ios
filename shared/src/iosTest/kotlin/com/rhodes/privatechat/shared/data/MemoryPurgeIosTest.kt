package com.rhodes.privatechat.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T63：生命周期清理的「软删 → 宽限 → 硬删」语义。
 *
 * 只测 SQL 层语义（CleanupRepository 依赖平台 DatabaseWrapper）：
 * - 过期但仍在宽限期内的行保留（用户还能在记忆管理页看到/恢复）；
 * - 超过宽限期的 expired 行物理删除；
 * - 指向被删行的 memory_links 同步清理，不留下悬空父子关系。
 */
class MemoryPurgeIosTest {

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun purgeDeletesOnlyExpiredBeyondGraceAndCleansOrphanLinks() {
        val unique = "${platform.posix.getpid()}_${nowSuffix()}"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "memory_purge_test_$unique.db")
        val db = RhodesDatabase(driver)
        val now = 1_800_000_000_000L
        val grace = 30L * 86_400_000L
        try {
            fun insertItem(id: Long, status: String, updatedAt: Long) {
                db.memoryItemsQueries.insertMemoryItem(
                    ownerType = "operator",
                    ownerId = "op1",
                    memoryLevel = "L1",
                    memoryType = "event",
                    sourceKind = "PRIVATE_CHAT",
                    sourceRefId = "src-$id",
                    sessionId = "s1",
                    content = "记忆$id",
                    nickname = "用户",
                    importance = 50,
                    privacy = "private",
                    unmetNeed = 0,
                    location = null,
                    emotionValence = "neutral",
                    eventTime = null,
                    createdAt = updatedAt,
                    updatedAt = updatedAt,
                    expiresAt = now - 1,
                    status = status,
                    scheduledTime = null,
                    action = "",
                    careType = "none",
                    topicKey = "event:x",
                    sourceActor = "用户",
                    sourceTarget = "op1",
                    lastUsedAt = 0,
                    usedCount = 0,
                    confidence = 0.8,
                    rawJson = "",
                    vectorId = "",
                )
            }
            // 1：刚过期（宽限内）；2：过期超过宽限期；3：仍在有效期；4：过期超过宽限期（父子链的一端）。
            insertItem(1, "expired", now - 1_000L)
            insertItem(2, "expired", now - grace - 1_000L)
            insertItem(3, "active", now - 1_000L)
            insertItem(4, "expired", now - grace - 1_000L)
            db.memoryLinksQueries.insertMemoryLink(4, 3, "merge_l1_to_l2", now)
            db.memoryLinksQueries.insertMemoryLink(1, 3, "merge_l1_to_l2", now)

            db.transaction {
                db.memoryItemsQueries.purgeExpiredMemoryItems(now - grace)
                db.memoryLinksQueries.deleteOrphanedMemoryLinks()
            }

            val remainingIds = db.memoryItemsQueries.getAllMemoryItems().executeAsList().map { it.id }.sorted()
            assertEquals(listOf(1L, 3L), remainingIds, "宽限期内的 expired 行与 active 行必须保留")
            val links = db.memoryLinksQueries.getAllMemoryLinks().executeAsList()
            assertEquals(1, links.size, "指向被删父项的关系必须清理")
            assertEquals(1L, links.single().parentMemoryId)
        } finally {
            driver.close()
        }
    }

    private fun nowSuffix(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}