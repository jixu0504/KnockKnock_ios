package com.rhodes.privatechat.shared.db

import app.cash.sqldelight.db.QueryResult
import com.rhodes.privatechat.shared.testutil.withTempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.length

/**
 * SQLDelight 打开库 + 23 迁移验证（iOS 模拟器真实运行）。
 *
 * 关键语义（生成代码 RhodesDatabaseImpl 实证）：
 * - fresh 冷开走 Schema.create()：只建当前 28 张 .sq 表，**不执行迁移**，sqliter 盖
 *   user_version = Schema.version（23 个迁移 + 1 = **24**）。
 * - 迁移（1..23.sqm）只在**升级路径**（旧库重开）执行：migrate(oldVersion, newVersion)
 *   逐段 `oldVersion <= N && newVersion > N`。
 * - 因此「28（运行时 29）表」的口径是：fresh 冷开 28 表；data_repair_runs 仅由迁移 18
 *   在 ≤v18 → 新版升级时创建（Android 同语义——fresh 安装也不会有该表）。
 * - user_version 从 18 升到 24 时，迁移 20 的 ALTER 会与当前 schema 冲突（sourceType
 *   已存在），所以升级用例里先 DROP COLUMN sourceType 模拟 v18 库形态。
 */
class DatabaseMigrationTest {

    /** fresh 冷开应有的 28 张表（.sq 静态声明） */
    private val currentSchemaTables = setOf(
        "chat_archives", "chat_display_events", "chat_history_segments", "chat_messages", "chat_sessions",
        "daily_deliveries", "diaries", "dispatch_records", "gift_records",
        "knowledge_base_chunks", "knowledge_bases", "mahjong_saves", "memories", "memory_anchors",
        "canonical_entities", "entity_aliases", "impression_history", "impressions", "memory_batches",
        "memory_corpus_stats", "memory_document_stats", "memory_entities", "memory_facts",
        "memory_impression_evidence", "memory_items",
        "memory_links", "memory_relations", "memory_source_queue", "memory_term_stats", "memory_terms",
        "memory_usage",
        "moment_comments", "moment_likes", "moments", "operator_knowledge_bases", "operators",
        "relationships", "reply_turns", "shared_experience_participants", "shared_experiences", "vector_memories",
    )

    private fun tableNames(driver: app.cash.sqldelight.db.SqlDriver): Set<String> {
        val tables = mutableSetOf<String>()
        driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table'", { cursor ->
            while (cursor.next().value) {
                cursor.getString(0)?.let { tables += it }
            }
            QueryResult.Value(Unit)
        }, 0)
        return tables.filterNot { it.startsWith("sqlite_") }.toSet()
    }

    private fun userVersion(driver: app.cash.sqldelight.db.SqlDriver): Long? =
        driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0))
        }, 0).value

    @Test
    fun coldOpenCreatesCurrentSchemaAndStampsVersion24() = withTempDir("rhodes-db-test") { dir ->
        val dbPath = "$dir/rhodes-test.db"
        val coldStart = TimeSource.Monotonic.markNow()
        val driver = DatabaseDriverFactory().createDriver(dbPath)
        val coldMs = coldStart.elapsedNow().inWholeMilliseconds
        val database = RhodesDatabase(driver)
        var dbSizeBytes = "?"
        try {
            // SQLDelight 版本口径：23 个迁移文件 + 1 = 当前版本 24
            assertEquals(33L, RhodesDatabase.Schema.version, "Schema.version 应为 33（32 迁移 + 1）")
            assertEquals(33L, userVersion(driver), "fresh 冷开后 user_version 应为 33")

            // fresh 冷开 = 41 张当前表（迁移不在 create 路径执行；41 = 原 28 + 词法 4 + 关系 1 + 事实 1 + 使用 1 + 实体 3 + 印象 2 + 印象历史 1）
            val tables = tableNames(driver)
            val missing = currentSchemaTables - tables
            val extra = tables - currentSchemaTables
            assertTrue(missing.isEmpty(), "缺少表: $missing")
            assertTrue(extra.isEmpty(), "意外表: $extra（fresh 冷开不应有迁移专属表）")
            assertEquals(currentSchemaTables, tables)

            // 读写 sanity（临时探针表）
            driver.execute(null, "CREATE TABLE t9_probe(x INTEGER)", 0)
            driver.execute(null, "INSERT INTO t9_probe(x) VALUES (7)", 0)
            val probe = driver.executeQuery(null, "SELECT x FROM t9_probe", { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0))
            }, 0).value
            assertEquals(7L, probe)
            driver.execute(null, "DROP TABLE t9_probe", 0)

            dbSizeBytes = NSData.dataWithContentsOfFile(dbPath)?.length?.toString() ?: "?"
        } finally {
            driver.close()
        }
        println("IOS-DB cold-open-and-create: ${coldMs}ms, db-file=$dbSizeBytes bytes")
        // 冷启动含 28 表建表，10s 为宽松上限（只防病态回归，不设性能达标线）
        assertTrue(coldMs < 10_000, "冷启动建库应 <10s，实测 ${coldMs}ms")
    }

    @Test
    fun upgradePathRunsPendingMigrationsAndCreatesMigrationOnlyTable() = withTempDir("rhodes-db-test") { dir ->
        val dbPath = "$dir/rhodes-test.db"
        // 1) fresh 建库后，模拟停在 v18 的旧库形态：
        //    - 迁移 20 会 ALTER knowledge_bases ADD sourceType，故先 DROP COLUMN（SQLite 3.35+ 支持）
        //    - PRAGMA user_version = 18
        val driver1 = DatabaseDriverFactory().createDriver(dbPath)
        driver1.execute(null, "ALTER TABLE knowledge_bases DROP COLUMN sourceType", 0)
        // 迁移 26 会 ALTER vector_memories ADD COLUMN：先 DROP 掉新列，模拟 v18 库形态
        driver1.execute(null, "ALTER TABLE vector_memories DROP COLUMN embeddingBlob", 0)
        driver1.execute(null, "ALTER TABLE vector_memories DROP COLUMN embeddingDimension", 0)
        driver1.execute(null, "ALTER TABLE vector_memories DROP COLUMN embeddingDtype", 0)
        driver1.execute(null, "PRAGMA user_version = 18", 0)
        assertEquals(18L, userVersion(driver1))
        driver1.close()

        // 2) 重开触发升级 18→33：迁移 18…32 依次执行
        val upgradeStart = TimeSource.Monotonic.markNow()
        val driver2 = DatabaseDriverFactory().createDriver(dbPath)
        val upgradeMs = upgradeStart.elapsedNow().inWholeMilliseconds
        try {
            assertEquals(33L, userVersion(driver2), "升级后 user_version 应为 33")
            assertTrue("memory_terms" in tableNames(driver2), "迁移 24 应创建词法索引表")
            assertTrue("memory_relations" in tableNames(driver2), "迁移 25 应创建事实关系表")
            val vectorCols = mutableSetOf<String>()
            driver2.executeQuery(null, "PRAGMA table_info(vector_memories)", { cursor ->
                while (cursor.next().value) {
                    cursor.getString(1)?.let { vectorCols += it }
                }
                QueryResult.Value(Unit)
            }, 0)
            assertTrue("embeddingBlob" in vectorCols, "迁移 26 应增加 vector_memories.embeddingBlob，实际列: $vectorCols")
            assertTrue("memory_facts" in tableNames(driver2), "迁移 27 应创建事实表")
            assertTrue("memory_usage" in tableNames(driver2), "迁移 28 应创建使用反馈表")
            assertTrue("canonical_entities" in tableNames(driver2), "迁移 29 应创建实体表")
            assertTrue("impressions" in tableNames(driver2), "迁移 30 应创建印象表")
            assertTrue("memory_impression_evidence" in tableNames(driver2), "迁移 31 应创建印象证据表")
            assertTrue("impression_history" in tableNames(driver2), "迁移 32 应创建印象历史表")
            // 迁移 18 创建了 data_repair_runs（fresh 冷开没有的表）
            assertTrue("data_repair_runs" in tableNames(driver2), "迁移 18 应创建 data_repair_runs")
            // 迁移 20 恢复了 sourceType 列
            val cols = mutableSetOf<String>()
            driver2.executeQuery(null, "PRAGMA table_info(knowledge_bases)", { cursor ->
                while (cursor.next().value) {
                    cursor.getString(1)?.let { cols += it }
                }
                QueryResult.Value(Unit)
            }, 0)
            assertTrue("sourceType" in cols, "迁移 20 应恢复 sourceType 列，实际列: $cols")
        } finally {
            driver2.close()
        }
        println("IOS-DB upgrade-18-to-33: ${upgradeMs}ms")
        assertTrue(upgradeMs < 10_000, "升级路径应 <10s，实测 ${upgradeMs}ms")
    }

    @Test
    fun warmReopenWorksAndKeepsData() = withTempDir("rhodes-db-test") { dir ->
        val dbPath = "$dir/rhodes-test.db"
        val driver1 = DatabaseDriverFactory().createDriver(dbPath)
        driver1.execute(null, "CREATE TABLE t9_warm(x INTEGER)", 0)
        driver1.execute(null, "INSERT INTO t9_warm(x) VALUES (9)", 0)
        driver1.close()

        val warmStart = TimeSource.Monotonic.markNow()
        val driver2 = DatabaseDriverFactory().createDriver(dbPath)
        val warmMs = warmStart.elapsedNow().inWholeMilliseconds
        val value = driver2.executeQuery(null, "SELECT x FROM t9_warm", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0))
        }, 0).value
        driver2.close()
        assertEquals(9L, value, "重开后应保留数据")
        println("IOS-DB warm-reopen: ${warmMs}ms")
        assertTrue(warmMs < 10_000)
    }
}
