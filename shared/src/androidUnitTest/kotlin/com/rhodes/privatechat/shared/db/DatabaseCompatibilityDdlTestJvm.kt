package com.rhodes.privatechat.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertTrue

/** JVM 路径（:shared:testDebugUnitTest）：迁移 24–32 兼容 DDL 的最小结构断言。 */
class DatabaseCompatibilityDdlTestJvm {

    @Test
    fun compatibilityDdlCreatesMemoryV2TablesAndEmbeddingColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE vector_memories (id TEXT NOT NULL PRIMARY KEY)", 0)
            MemoryV2CompatibilityDdl.tables.values.forEach { driver.execute(null, it, 0) }
            MemoryV2CompatibilityDdl.indexes.forEach { driver.execute(null, it, 0) }
            MemoryV2CompatibilityDdl.vectorMemoryEmbeddingColumns.forEach { (name, definition) ->
                driver.execute(null, "ALTER TABLE vector_memories ADD COLUMN $name $definition", 0)
            }

            val tables = queryColumn(driver, "SELECT name FROM sqlite_master WHERE type='table'", 0)
            MemoryV2CompatibilityDdl.tables.keys.forEach { table ->
                assertTrue(table in tables, "兼容 DDL 缺少表 $table")
            }

            val columns = queryColumn(driver, "PRAGMA table_info(vector_memories)", 1)
            MemoryV2CompatibilityDdl.vectorMemoryEmbeddingColumns.forEach { (name, _) ->
                assertTrue(name in columns, "vector_memories 缺少列 $name")
            }
        } finally {
            driver.close()
        }
    }

    private fun queryColumn(driver: SqlDriver, sql: String, columnIndex: Int): Set<String> {
        val values = mutableSetOf<String>()
        driver.executeQuery(null, sql, { cursor ->
            while (cursor.next().value) {
                cursor.getString(columnIndex)?.let { values.add(it) }
            }
            QueryResult.Value(Unit)
        }, 0)
        return values
    }
}