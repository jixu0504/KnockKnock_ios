package com.rhodes.privatechat.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.memory.PlannedImpressionEvidence
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T81a：memory_impression_evidence 在真实 SQLite 上的读写（NativeSqliteDriver + RhodesDatabase）。
 *
 * 覆盖：link 幂等（同 pair 覆盖权重不增行）、按印象计数、权重降序读取、
 * 孤儿证据清理（记忆不存在的行删掉、有效行保留）、按记忆删除、整印象 unlink。
 */
class ImpressionEvidenceIosTest {

    private val now = 1_800_000_000_000L

    private fun unique(suffix: Long) = "${platform.posix.getpid()}_${now}_$suffix"

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun linkIsIdempotentAndCountsPerImpression() = runBlocking {
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "impression_evidence_test_${unique(0)}.db")
        val db = RhodesDatabase(driver)
        try {
            val repository = ImpressionEvidenceRepository(db)

            repository.link(impressionId = 100L, memoryId = 1L, weight = 0.7, now = now)
            assertEquals(1L, repository.count(100L))
            assertEquals(listOf(ImpressionEvidence(100L, 1L, 0.7, now)), repository.list(100L))

            // 同 (impressionId, memoryId) 重挂：覆盖权重与时间，不产生第二行
            repository.link(impressionId = 100L, memoryId = 1L, weight = 0.9, now = now + 1_000)
            assertEquals(1L, repository.count(100L), "link 必须幂等")
            assertEquals(listOf(ImpressionEvidence(100L, 1L, 0.9, now + 1_000)), repository.list(100L))

            repository.link(impressionId = 100L, memoryId = 2L, weight = 0.5, now = now + 2_000)
            repository.link(impressionId = 101L, memoryId = 3L, weight = 0.9, now = now + 3_000)
            assertEquals(2L, repository.count(100L))
            assertEquals(1L, repository.count(101L), "不同印象独立计数")
            assertEquals(listOf(1L, 2L), repository.list(100L).map { it.memoryId }, "权重降序")
            assertEquals(0L, repository.count(999L), "未知印象计数为 0")
        } finally {
            driver.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun bulkLinkAndUnlinkClearWholeImpression() = runBlocking {
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "impression_evidence_test_${unique(1)}.db")
        val db = RhodesDatabase(driver)
        try {
            val repository = ImpressionEvidenceRepository(db)

            repository.linkAll(
                impressionId = 200L,
                evidence = listOf(
                    PlannedImpressionEvidence(memoryId = 11L, weight = 0.9),
                    PlannedImpressionEvidence(memoryId = 12L, weight = 0.5),
                ),
                now = now,
            )
            assertEquals(listOf(11L, 12L), repository.list(200L).map { it.memoryId })
            assertEquals(2L, repository.count(200L))

            repository.unlink(200L)
            assertEquals(0L, repository.count(200L), "unlink 清空该印象的全部证据")
            assertEquals(emptyList(), repository.list(200L))
        } finally {
            driver.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun deleteOrphansKeepsEvidenceOfLiveMemories() = runBlocking {
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "impression_evidence_test_${unique(2)}.db")
        val db = RhodesDatabase(driver)
        try {
            fun insertItem(id: Long) {
                db.memoryItemsQueries.insertMemoryItem(
                    ownerType = "operator",
                    ownerId = "op1",
                    memoryLevel = "L1",
                    memoryType = "preference_expression",
                    sourceKind = "PRIVATE_CHAT",
                    sourceRefId = "src-$id",
                    sessionId = "s1",
                    content = "证据$id",
                    nickname = "用户",
                    importance = 50,
                    privacy = "private",
                    unmetNeed = 0,
                    location = null,
                    emotionValence = "neutral",
                    eventTime = null,
                    createdAt = now,
                    updatedAt = now,
                    expiresAt = Long.MAX_VALUE,
                    status = "active",
                    scheduledTime = null,
                    action = "",
                    careType = "none",
                    topicKey = "taste:coffee",
                    sourceActor = "用户",
                    sourceTarget = "op1",
                    lastUsedAt = 0,
                    usedCount = 0,
                    confidence = 0.8,
                    rawJson = "",
                    vectorId = "",
                )
            }

            insertItem(1L)
            insertItem(2L)
            val liveIds = db.memoryItemsQueries.getAllMemoryItems().executeAsList().map { it.id }.sorted()
            assertEquals(listOf(1L, 2L), liveIds)

            val repository = ImpressionEvidenceRepository(db)
            repository.link(300L, liveIds[0], 0.9, now)
            repository.link(300L, liveIds[1], 0.7, now)
            repository.link(300L, 9_999L, 0.5, now) // 指向不存在的记忆 → 孤儿
            assertEquals(3L, repository.count(300L))

            repository.deleteOrphans()
            assertEquals(2L, repository.count(300L), "只删孤儿证据")
            assertEquals(liveIds, repository.list(300L).map { it.memoryId }.sorted())

            repository.deleteByMemory(liveIds[1])
            assertEquals(listOf(liveIds[0]), repository.list(300L).map { it.memoryId })

            // 记忆被物理清理后，deleteOrphans 也应回收它的证据行
            db.memoryItemsQueries.deleteMemoryItem(liveIds[0])
            repository.deleteOrphans()
            assertEquals(0L, repository.count(300L))
        } finally {
            driver.close()
        }
    }
}