@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.iosbridge

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * 备份桥（最小可用版）：真实 DB 文件的导出 + 两阶段恢复。
 *
 * 设计（详见 ios-port/specs/ios-bridge-spec.md）：
 * - export：把 Application Support/rhodes.db 复制到调用方给定路径，进度 0.0→1.0；
 * - restore：**两阶段**。先把备份文件校验后复制到 `rhodes.db.restore-pending`，
 *   下次冷启动时 [IosAppGraph] 在打开数据库之前把 pending 文件换入；
 *   运行中数据库连接不会被打断（SQLite 在线替换会损坏，故不冒险）。
 * - 已知限制：导出是文件级复制，不做 WAL checkpoint/一致性快照；
 *   完整恢复执行器（BackupRestoreCoordinator 的 24 步流程）留 Phase 3 接线。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "BackupBridge")
class BackupBridge internal constructor(
    private val dbPath: String,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    fun databasePath(): String = dbPath

    fun pendingRestorePath(): String = pendingPath(dbPath)

    fun hasPendingRestore(): Boolean = IosFileSupport.exists(pendingPath(dbPath))

    fun exportToFile(destinationPath: String, onProgress: (BackupProgressDto) -> Unit) {
        workScope.launch {
            try {
                report(onProgress, "prepare", 0.0, "准备导出：$dbPath")
                if (!IosFileSupport.exists(dbPath)) {
                    report(onProgress, "failed", 0.0, "数据库文件不存在：$dbPath")
                    return@launch
                }
                IosFileSupport.ensureParentDirectory(destinationPath)
                report(onProgress, "copy", 0.3, "复制数据库文件 → $destinationPath")
                val copied = IosFileSupport.copyFile(dbPath, destinationPath)
                if (!copied) {
                    report(onProgress, "failed", 1.0, "复制失败（磁盘/权限）")
                    return@launch
                }
                report(onProgress, "verify", 0.9, "校验 SQLite 头")
                val valid = IosFileSupport.looksLikeSqlite(destinationPath)
                if (!valid) {
                    report(onProgress, "failed", 1.0, "导出文件不是合法 SQLite")
                    return@launch
                }
                report(onProgress, "done", 1.0, "导出完成：$destinationPath")
            } catch (t: Throwable) {
                report(onProgress, "failed", 1.0, t.message ?: "导出异常")
            }
        }
    }

    fun restoreFromFile(sourcePath: String, onProgress: (BackupProgressDto) -> Unit) {
        workScope.launch {
            try {
                report(onProgress, "prepare", 0.0, "准备恢复：$sourcePath")
                if (!IosFileSupport.exists(sourcePath)) {
                    report(onProgress, "failed", 0.0, "备份文件不存在")
                    return@launch
                }
                report(onProgress, "verify", 0.3, "校验 SQLite 头")
                if (!IosFileSupport.looksLikeSqlite(sourcePath)) {
                    report(onProgress, "failed", 0.3, "备份文件不是合法 SQLite")
                    return@launch
                }
                val pending = pendingPath(dbPath)
                IosFileSupport.ensureParentDirectory(pending)
                report(onProgress, "copy", 0.6, "写入待恢复文件")
                if (!IosFileSupport.copyFile(sourcePath, pending)) {
                    report(onProgress, "failed", 1.0, "写入待恢复文件失败")
                    return@launch
                }
                report(onProgress, "staged", 1.0, "已暂存，重启 App 后生效（冷启动前换入）")
            } catch (t: Throwable) {
                report(onProgress, "failed", 1.0, t.message ?: "恢复异常")
            }
        }
    }

    private fun report(onProgress: (BackupProgressDto) -> Unit, stage: String, progress: Double, message: String) {
        mainScope.launch { runCatching { onProgress(BackupProgressDto(stage, progress, message)) } }
    }

    companion object {
        internal fun pendingPath(dbPath: String): String = "$dbPath.restore-pending"

        /**
         * 冷启动换入（在 [IosAppGraph] 打开数据库之前调用）。
         * 返回 true 表示本次启动执行过换入。
         */
        internal fun applyPendingRestoreIfAny(dbPath: String): Boolean {
            val pending = pendingPath(dbPath)
            if (!IosFileSupport.exists(pending)) return false
            return try {
                IosFileSupport.deleteIfExists("$dbPath-wal")
                IosFileSupport.deleteIfExists("$dbPath-shm")
                IosFileSupport.deleteIfExists(dbPath)
                val moved = IosFileSupport.copyFile(pending, dbPath)
                if (moved) IosFileSupport.deleteIfExists(pending)
                // K/N 2.3 interop：NSString 不再自动映射 Kotlin String，%@ 变参必须显式 NSString.create；
                // 否则运行到此处会 SIGSEGV（T38 两阶段恢复换入实测，T29 遗留缺陷）。
                NSLog("[IosBridge][Backup] pending restore applied=%@", NSString.create(string = moved.toString()))
                moved
            } catch (t: Throwable) {
                NSLog("[IosBridge][Backup] pending restore failed: %@", NSString.create(string = t.message ?: "unknown"))
                false
            }
        }
    }
}

/** 最小 Foundation 文件工具（避免为 demo 引入额外 IO 依赖）。 */
internal object IosFileSupport {
    private val fileManager: NSFileManager get() = NSFileManager.defaultManager

    fun exists(path: String): Boolean = fileManager.fileExistsAtPath(path)

    fun ensureParentDirectory(filePath: String) {
        val parent = filePath.substringBeforeLast('/', "")
        if (parent.isEmpty()) return
        if (!exists(parent)) {
            fileManager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    fun readBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val length = data.length.toInt()
        if (length <= 0) return ByteArray(0)
        return data.bytes?.readBytes(length)
    }

    fun writeBytes(path: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return NSData.dataWithBytes(null, 0.convert()).writeToFile(path, atomically = true)
        }
        return bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.convert()).writeToFile(path, atomically = true)
        }
    }

    fun copyFile(source: String, destination: String): Boolean {
        val bytes = readBytes(source) ?: return false
        return writeBytes(destination, bytes)
    }

    fun deleteIfExists(path: String): Boolean {
        if (!exists(path)) return false
        return fileManager.removeItemAtPath(path, error = null)
    }

    /** SQLite 文件头 `SQLite format 3\0`。 */
    fun looksLikeSqlite(path: String): Boolean {
        val head = readBytes(path) ?: return false
        if (head.size < 16) return false
        val expected = "SQLite format 3\u0000"
        for (index in expected.indices) {
            if (head[index] != expected[index].code.toByte()) return false
        }
        return true
    }
}
