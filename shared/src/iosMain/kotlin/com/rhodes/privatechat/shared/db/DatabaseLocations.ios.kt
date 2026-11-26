package com.rhodes.privatechat.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.files.MediaDirectory

/**
 * iOS 数据库文件位置 + 驱动构造（Phase 0 骨架）。
 *
 * 关键事实（K/N 2.3.21 + sqldelight 2.2.1 → sqliter 1.3.3，模拟器实测）：
 * NativeSqliteDriver 的 `name` 参数禁止包含路径分隔符（sqliter DatabaseConfiguration
 * 会抛 "File ... contains a path separator"）。目录必须经 onConfiguration 里的
 * sqliter Extended.basePath 传入，name 只给纯文件名。
 *
 * Android 端 "rhodes.db" 落在 context.filesDir 根；iOS 对应 Application Support
 * （≈ filesDir，见 ios-port/audit/paths.md §4）。T1 骨架的相对路径在 iOS 会解析到
 * 进程 CWD（真机为 "/"，不可写），Phase 2 正式版必须用本对象的方法。
 */
object DatabaseLocations {
    const val DB_FILE_NAME = "rhodes.db"

    fun applicationDatabaseDirectory(): String {
        val dir = MediaDirectory.applicationSupportDirectory()
        MediaDirectory.ensureDirectory(dir)
        return dir
    }

    /** 逻辑完整路径（basePath + name）；用于日志/诊断/备份定位。 */
    fun defaultDatabasePath(): String = "${applicationDatabaseDirectory()}/$DB_FILE_NAME"

    /**
     * 在给定完整路径建驱动：目录部分走 sqliter Extended.basePath，文件名为 name。
     * 目录不存在时自动创建（withIntermediateDirectories）。
     */
    fun createDriverAt(dbPath: String): SqlDriver {
        val dir = dbPath.substringBeforeLast('/', "").ifEmpty { "." }
        val name = dbPath.substringAfterLast('/')
        if (dir != ".") MediaDirectory.ensureDirectory(dir)
        return NativeSqliteDriver(RhodesDatabase.Schema, name, onConfiguration = { config ->
            config.copy(extendedConfig = config.extendedConfig.copy(basePath = dir))
        })
    }

    fun createDefaultDriver(): SqlDriver = createDriverAt(defaultDatabasePath())
}
