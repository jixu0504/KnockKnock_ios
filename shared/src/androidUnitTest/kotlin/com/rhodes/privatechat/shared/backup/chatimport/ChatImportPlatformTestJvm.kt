package com.rhodes.privatechat.shared.backup.chatimport

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.rhodes.privatechat.shared.backup.BackupRestoreCompletionTest
import com.rhodes.privatechat.shared.backup.InMemoryObservableSettings
import com.rhodes.privatechat.shared.backup.TestBackupEnv
import com.rhodes.privatechat.shared.backup.TestBackupRepository
import com.rhodes.privatechat.shared.backup.operatorcard.OperatorCardTest
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository

/** JVM 路径金样资源读取（classpath：`shared/src/androidUnitTest/resources/golden/chat-import/`）。 */
internal actual fun readTestResourceBytes(path: String): ByteArray {
    val stream = checkNotNull(object {}.javaClass.classLoader?.getResourceAsStream(path)) {
        "JVM test resource missing: $path（先运行 ios-port/golden/chat-import/sync_golden.sh --sync-only）"
    }
    return stream.use { it.readBytes() }
}

/** JVM 路径：sqldelight-sqlite 内存驱动 + Map 设置（与 T10 同款）。 */
class ChatImportGoldenTestJvm : ChatImportGoldenTest() {
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

class OperatorCardTestJvm : OperatorCardTest() {
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

class BackupRestoreCompletionTestJvm : BackupRestoreCompletionTest() {
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
