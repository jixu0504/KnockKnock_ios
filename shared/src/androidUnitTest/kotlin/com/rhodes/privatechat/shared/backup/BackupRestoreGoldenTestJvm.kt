package com.rhodes.privatechat.shared.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository

/**
 * JVM 路径具体测试（:shared:testDebugUnitTest）：sqldelight-sqlite 内存驱动（libs.versions.toml:50，lead 批准）。
 */
class BackupRestoreGoldenTestJvm : BackupRestoreGoldenTest() {
    override fun createEnv(): TestBackupEnv {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        return TestBackupEnv(
            driver = driver,
            database = database,
            repository = TestBackupRepository(database),
            settings = SettingsRepository(InMemoryObservableSettings()),
        )
    }
}
