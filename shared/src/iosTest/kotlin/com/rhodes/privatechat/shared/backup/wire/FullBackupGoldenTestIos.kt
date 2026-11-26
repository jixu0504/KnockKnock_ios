package com.rhodes.privatechat.shared.backup.wire

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.backup.TestBackupEnv
import com.rhodes.privatechat.shared.backup.TestBackupRepository
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults

/** iOS 路径（:shared:iosSimulatorArm64Test）：NativeSqliteDriver（唯一库名）+ 独立 NSUserDefaults suite。 */
class FullBackupGoldenTestIos : FullBackupGoldenTest() {

    companion object {
        private var counter = 0
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun createEnv(): TestBackupEnv {
        val unique = "${platform.posix.getpid()}_${counter++}"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "full_backup_test_$unique.db")
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        val suiteName = "full_backup_test_$unique"
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
