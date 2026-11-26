package com.rhodes.privatechat.shared.domain.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSUserDefaults

/**
 * iOS 路径具体测试（:shared:iosSimulatorArm64Test，iPhone 17 模拟器）：
 * NativeSqliteDriver（唯一文件名，T9 实证 name 不得含路径分隔符）+ 独立 NSUserDefaults suite；
 * close 时清除 suite（与 T10 BackupRestoreGoldenTestIos 同模式）。
 */
class MessageIdAllocatorTestIos : MessageIdAllocatorTest() {

    companion object {
        private var counter = 0
    }

    override fun createEnv(): TestAllocatorEnv {
        val unique = "${platform.posix.getpid()}_${counter++}"
        val dbName = "allocator_test_$unique.db"
        val suiteName = "allocator_test_$unique"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, dbName)
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        return TestAllocatorEnv(
            driver = driver,
            database = database,
            settings = SettingsRepository(NSUserDefaultsSettings(NSUserDefaults(suiteName = suiteName))),
            cleanup = {
                runCatching { NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suiteName) }
            },
        )
    }
}
