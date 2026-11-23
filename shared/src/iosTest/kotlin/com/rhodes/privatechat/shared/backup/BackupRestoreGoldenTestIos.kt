package com.rhodes.privatechat.shared.backup

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults

/**
 * iOS 路径具体测试（:shared:iosSimulatorArm64Test，iOS 27.2 模拟器 runtime）：
 * NativeSqliteDriver（每次调用唯一文件名，避免同进程多测试共享库文件残留）+ 独立 NSUserDefaults suite；
 * close 时清除 suite，保证测试互不污染（库文件名唯一，残留文件不影响后续测试）。
 */
class BackupRestoreGoldenTestIos : BackupRestoreGoldenTest() {

    companion object {
        private var counter = 0
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun createEnv(): TestBackupEnv {
        val unique = "${platform.posix.getpid()}_${counter++}"
        val dbName = "backup_test_$unique.db"
        val suiteName = "backup_test_$unique"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, dbName)
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        return TestBackupEnv(
            driver = driver,
            database = database,
            repository = TestBackupRepository(database),
            settings = SettingsRepository(NSUserDefaultsSettings(NSUserDefaults(suiteName = suiteName))),
            cleanup = {
                runCatching { NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suiteName) }
            },
        )
    }
}
