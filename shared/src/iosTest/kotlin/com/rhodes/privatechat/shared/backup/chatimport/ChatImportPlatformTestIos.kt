package com.rhodes.privatechat.shared.backup.chatimport

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.backup.BackupRestoreCompletionTest
import com.rhodes.privatechat.shared.backup.TestBackupEnv
import com.rhodes.privatechat.shared.backup.TestBackupRepository
import com.rhodes.privatechat.shared.backup.operatorcard.OperatorCardTest
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.security.toByteArray
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfFile

/**
 * iOS 路径金样资源读取（test bundle resourcePath；KGP 可能把 commonTest resources 放 bundle 根或
 * compose-resources 子目录，与 T18 GoldenTestResourcesIos 同款双候选）。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun readTestResourceBytes(path: String): ByteArray {
    val root = NSBundle.mainBundle.resourcePath ?: error("iOS test bundle resourcePath is null")
    val candidates = listOf("$root/$path", "$root/compose-resources/$path")
    for (full in candidates) {
        NSData.dataWithContentsOfFile(full)?.let { return it.toByteArray() }
    }
    val file = path.substringAfterLast('/')
    val dir = path.substringBeforeLast('/', "")
    NSBundle.mainBundle.pathForResource(
        name = file.substringBeforeLast('.', file),
        ofType = file.substringAfterLast('.', "").ifEmpty { null },
        inDirectory = dir.ifEmpty { null },
    )?.let { found -> NSData.dataWithContentsOfFile(found)?.let { return it.toByteArray() } }
    error("iOS test resource missing: $path (tried $candidates + bundle lookup)")
}

/** iOS 路径：NativeSqliteDriver（唯一文件名）+ 独立 NSUserDefaults suite。 */
class ChatImportGoldenTestIos : ChatImportGoldenTest() {

    companion object {
        private var counter = 0
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun createEnv(): TestChatImportEnv {
        val unique = "${platform.posix.getpid()}_${counter++}"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "chat_import_test_$unique.db")
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        val suiteName = "chat_import_test_$unique"
        return TestChatImportEnv(
            driver = driver,
            database = database,
            settings = SettingsRepository(NSUserDefaultsSettings(NSUserDefaults(suiteName = suiteName))),
            cleanup = {
                runCatching { NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suiteName) }
            },
        )
    }
}

class OperatorCardTestIos : OperatorCardTest() {
    companion object {
        private var counter = 0
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun createEnv(): TestChatImportEnv {
        val unique = "${platform.posix.getpid()}_opcard_${counter++}"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "operator_card_test_$unique.db")
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        val suiteName = "operator_card_test_$unique"
        return TestChatImportEnv(
            driver = driver,
            database = database,
            settings = SettingsRepository(NSUserDefaultsSettings(NSUserDefaults(suiteName = suiteName))),
            cleanup = {
                runCatching { NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suiteName) }
            },
        )
    }
}

class BackupRestoreCompletionTestIos : BackupRestoreCompletionTest() {
    companion object {
        private var counter = 0
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun createEnv(): TestBackupEnv {
        val unique = "${platform.posix.getpid()}_${counter++}"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, "completion_test_$unique.db")
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        val suiteName = "completion_test_$unique"
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
