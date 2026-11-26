package com.rhodes.privatechat.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T70：词法索引的建库/增量/重建行为（iOS 模拟器真实 SQLite）。
 *
 * 覆盖：
 * - 首次 ensureReady 按 active 记忆重建索引，BM25 能命中正确条目；
 * - 归档后 active 条数变化 → 下次 ensureReady 整所有者重建，归档项不再被检索；
 * - 增量写入（indexItem）后语料统计与 active 条数保持一致，不触发重建。
 */
class MemoryLexicalIndexIosTest {

    private val now = 1_800_000_000_000L

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun indexRebuildsOnCountMismatchAndRanksLexicalMatch() = runBlocking {
        val unique = "${platform.posix.getpid()}_${now}"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "memory_lexical_test_$unique.db")
        val db = RhodesDatabase(driver)
        try {
            insertItem(db, 1, "用户生日是三月十四日")
            insertItem(db, 2, "用户喜欢吃辣")
            insertItem(db, 3, "用户对花生过敏")

            val index = MemoryLexicalIndex(db)
            index.ensureReady("operator", "op1", now)

            val hits = index.search("operator", "op1", "用户的生日是哪天", limit = 5)
            assertEquals(1L, hits.first().memoryId, "生日问题应命中生日记忆：$hits")
            assertTrue(hits.first().score > 0.0)

            val allergy = index.search("operator", "op1", "花生过敏", limit = 5)
            assertEquals(3L, allergy.first().memoryId)

            val corpus = db.memoryLexicalQueries.lexicalGetCorpus("operator", "op1").executeAsOne()
            assertEquals(3L, corpus.docCount)
            assertTrue(corpus.totalDocumentLength > 0)

            // 归档一条 → active 条数 2 ≠ docCount 3 → 重建后不再命中
            db.memoryItemsQueries.archiveMemoryItem(now, 3L)
            index.ensureReady("operator", "op1", now)
            assertEquals(
                0,
                index.search("operator", "op1", "花生过敏", limit = 5).size,
                "归档记忆必须从索引中移除",
            )
            assertEquals(
                2L,
                db.memoryLexicalQueries.lexicalGetCorpus("operator", "op1").executeAsOne().docCount,
            )
        } finally {
            driver.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun incrementalIndexKeepsCorpusInSync() = runBlocking {
        val unique = "${platform.posix.getpid()}_${now}_inc"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "memory_lexical_inc_test_$unique.db")
        val db = RhodesDatabase(driver)
        try {
            val id = insertItem(db, 1, "用户养了一只叫团子的猫")
            val index = MemoryLexicalIndex(db)
            // 模拟仓库插入路径：同一事务内写倒排 + 语料统计
            index.indexItem(
                com.rhodes.privatechat.shared.model.MemoryItem(
                    id = id,
                    ownerType = "operator",
                    ownerId = "op1",
                    memoryLevel = com.rhodes.privatechat.shared.model.MemoryLevel.L1,
                    memoryType = "event",
                    sourceKind = com.rhodes.privatechat.shared.model.MemorySourceKind.PRIVATE_CHAT,
                    content = "用户养了一只叫团子的猫",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            // 无需重建：docCount == active 条数
            index.ensureReady("operator", "op1", now)
            val cat = index.search("operator", "op1", "团子", limit = 5)
            assertEquals(1L, cat.first().memoryId)
            assertEquals(
                1L,
                db.memoryLexicalQueries.lexicalGetCorpus("operator", "op1").executeAsOne().docCount,
            )
        } finally {
            driver.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun warmBuildsIndexesForAllActiveOwners() = runBlocking {
        val unique = "${platform.posix.getpid()}_${now}_warm"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "memory_lexical_warm_test_$unique.db")
        val db = RhodesDatabase(driver)
        try {
            insertItem(db, 1, "用户生日是三月十四日", ownerId = "op1")
            insertItem(db, 2, "群聊提到周末去看海", ownerId = "op2", ownerType = "group")
            MemoryLexicalIndex(db).warm(now)
            assertEquals(1L, db.memoryLexicalQueries.lexicalGetCorpus("operator", "op1").executeAsOne().docCount)
            assertEquals(1L, db.memoryLexicalQueries.lexicalGetCorpus("group", "op2").executeAsOne().docCount)
        } finally {
            driver.close()
        }
    }

    private fun insertItem(db: RhodesDatabase, id: Long, content: String, ownerId: String = "op1", ownerType: String = "operator"): Long {
        db.memoryItemsQueries.insertMemoryItem(
            ownerType = ownerType,
            ownerId = ownerId,
            memoryLevel = "L1",
            memoryType = "event",
            sourceKind = "PRIVATE_CHAT",
            sourceRefId = "src-$id",
            sessionId = "s1",
            content = content,
            nickname = "用户",
            importance = 60,
            privacy = "private",
            unmetNeed = 0,
            location = null,
            emotionValence = "neutral",
            eventTime = null,
            createdAt = now - id * 1_000L,
            updatedAt = now - id * 1_000L,
            expiresAt = now + 86_400_000L,
            status = "active",
            scheduledTime = null,
            action = "",
            careType = "none",
            topicKey = "",
            sourceActor = "用户",
            sourceTarget = "op1",
            lastUsedAt = 0,
            usedCount = 0,
            confidence = 0.8,
            rawJson = "",
            vectorId = "",
        )
        return db.memoryItemsQueries.getLastInsertedMemoryItemId().executeAsOne()
    }
}