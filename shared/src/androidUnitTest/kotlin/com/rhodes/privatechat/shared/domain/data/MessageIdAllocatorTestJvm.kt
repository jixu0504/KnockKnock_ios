package com.rhodes.privatechat.shared.domain.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.rhodes.privatechat.shared.backup.InMemoryObservableSettings
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository

/**
 * JVM 路径具体测试（:shared:testDebugUnitTest）：sqldelight-sqlite 内存驱动（libs.versions.toml:50）。
 */
class MessageIdAllocatorTestJvm : MessageIdAllocatorTest() {
    override fun createEnv(): TestAllocatorEnv {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        return TestAllocatorEnv(
            driver = driver,
            database = database,
            settings = SettingsRepository(InMemoryObservableSettings()),
        )
    }
}
