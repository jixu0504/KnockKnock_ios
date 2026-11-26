package com.rhodes.privatechat.shared.db

import app.cash.sqldelight.db.SqlDriver

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver = DatabaseLocations.createDefaultDriver()

    /** 显式路径（测试/诊断/恢复用，如 problem-probe 的 rhodes_*_$checkId.db）。 */
    fun createDriver(dbPath: String): SqlDriver = DatabaseLocations.createDriverAt(dbPath)
}
