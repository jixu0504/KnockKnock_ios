package com.rhodes.privatechat.shared.domain.data

import app.cash.sqldelight.db.SqlDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository

/**
 * 分配器测试环境：commonTest 定义，平台源集（androidUnitTest / iosTest）以具体子类提供驱动与设置实现
 * （与 T10 backup/TestBackupEnv 同模式）。
 */
class TestAllocatorEnv(
    val driver: SqlDriver,
    val database: RhodesDatabase,
    val settings: SettingsRepository,
    private val cleanup: (() -> Unit)? = null,
) {
    fun close() {
        runCatching { driver.close() }
        cleanup?.invoke()
    }
}
