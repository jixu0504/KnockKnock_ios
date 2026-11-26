package com.rhodes.privatechat.shared.backup.wire

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.rhodes.privatechat.shared.backup.InMemoryObservableSettings
import com.rhodes.privatechat.shared.backup.TestBackupEnv
import com.rhodes.privatechat.shared.backup.TestBackupRepository
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository

/** JVM 路径（:shared:testDebugUnitTest）：sqldelight-sqlite 内存驱动 + Map 设置（与 T10/T30 同款）。 */
class FullBackupGoldenTestJvm : FullBackupGoldenTest() {
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
