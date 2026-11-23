package com.rhodes.privatechat.shared.backup.chatimport

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.rhodes.privatechat.shared.backup.InMemoryObservableSettings
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository

/** T58：Markdown 聊天导入执行单测的 JVM 路径（内存 SQLite）。 */
class MarkdownChatImportExecutionTestJvm : MarkdownChatImportExecutionTest() {
    override fun createEnv(): TestChatImportEnv {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        return TestChatImportEnv(
            driver = driver,
            database = database,
            settings = SettingsRepository(InMemoryObservableSettings()),
        )
    }
}
