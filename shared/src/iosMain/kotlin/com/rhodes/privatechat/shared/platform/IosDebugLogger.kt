@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.platform

import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.security.toNSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData
import kotlin.time.Clock

/**
 * iOS 文件日志（Android `DebugLogger` 的 iOS 落盘对应物）。
 *
 * - 目录：`Application Support/logs/`，当前文件 `rhodes.log`（UTF-8，一行一条）；
 * - 轮转：单文件超过 [MAX_FILE_BYTES] 时归档为 `rhodes-<epochMs>.log`（最多保留 [MAX_ARCHIVES] 份）；
 * - 线程安全：`NSLock` 串行化写入（日志可能来自 Main/Default 任意线程）；
 * - 导出：复用 T9 文件 IO + [IosFileTransfer]（导出到 `Application Support/exports/` 后再由
 *   Swift 侧 share sheet 或系统保存面板分享）。
 *
 * 会话内自检/诊断直接调 [info]/[warn]/[error]；后续把 `PlatformLog.logError` 接到本对象即可
 * 让全局错误日志落盘（Phase 3 接线项，见 ios-platform-services-spec §已知限制）。
 */
object IosDebugLogger {

    const val MAX_FILE_BYTES: Long = 512L * 1024L
    const val MAX_ARCHIVES: Int = 5
    const val LOG_FILE_NAME: String = "rhodes.log"

    private val lock = NSLock()

    fun logsDirectory(): String = "${MediaDirectory.applicationSupportDirectory()}/logs"

    fun currentLogPath(): String = "${logsDirectory()}/$LOG_FILE_NAME"

    /** 写一行日志（level 如 INFO/WARN/ERROR/DEBUG）。 */
    fun log(level: String, tag: String, message: String) {
        append("${Clock.System.now()} [${level.uppercase()}] [$tag] $message\n")
    }

    fun info(message: String) = log("INFO", "RhodesLink", message)
    fun info(tag: String, message: String) = log("INFO", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun error(tag: String, message: String) = log("ERROR", tag, message)
    fun debug(tag: String, message: String) = log("DEBUG", tag, message)

    fun configured(): Boolean = ensureLogsDirectory()

    /** 当前日志全文（不存在返回空串；自检/诊断展示用）。 */
    fun readCurrentLog(): String {
        val bytes = FileIo.readBytes(currentLogPath()) ?: return ""
        return bytes.decodeToString()
    }

    /** 全部日志文件绝对路径（当前 + 归档，新的在前）。 */
    fun listLogFiles(): List<String> {
        val dir = logsDirectory()
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null) ?: return emptyList()
        return names.mapNotNull { it as? String }
            .filter { it.endsWith(".log") }
            .sortedDescending()
            .map { "$dir/$it" }
    }

    /** 当前日志字节数（不存在=0）。 */
    fun currentLogBytes(): Long = fileSize(currentLogPath())

    fun clearAll(): Boolean {
        lock.lock()
        try {
            return listLogFiles().all { FileIo.delete(it) }
        } finally {
            lock.unlock()
        }
    }

    /**
     * 把当前日志复制到 `Application Support/exports/`，返回导出文件绝对路径（失败/无日志返回空串）。
     * Swift 侧拿到路径后可直接用 `UIActivityViewController` 做 share sheet。
     */
    fun exportToFile(): String {
        val content = readCurrentLog()
        if (content.isEmpty()) return ""
        val dir = IosFileTransfer.exportsDirectory()
        if (!MediaDirectory.ensureDirectory(dir)) return ""
        val target = "$dir/rhodes-log-${Clock.System.now().toEpochMilliseconds()}.log"
        return if (FileIo.writeBytes(target, content.encodeToByteArray())) target else ""
    }

    /** 导出当前日志并通过注入的系统保存面板（[IosFileTransfer]）呈现。 */
    fun exportAndPresent(onResult: (FileOperationResult) -> Unit) {
        val source = currentLogPath()
        if (!FileIo.exists(source)) {
            onResult(FileOperationResult(false, source, "log file not found", false))
            return
        }
        val name = "rhodes-log-${Clock.System.now().toEpochMilliseconds()}.log"
        IosFileTransfer.exportExistingFile(source, name, onResult)
    }

    // ---------------------------------------------------------------- 内部

    private fun append(line: String) {
        lock.lock()
        try {
            if (!ensureLogsDirectory()) return
            val path = currentLogPath()
            rotateIfNeeded(path)
            val fm = NSFileManager.defaultManager
            if (!fm.fileExistsAtPath(path)) {
                fm.createFileAtPath(path, contents = null, attributes = null)
            }
            val handle = NSFileHandle.Companion.fileHandleForWritingAtPath(path) ?: return
            try {
                handle.seekToEndOfFile()
                val data = line.encodeToByteArray().toNSData() ?: return
                handle.writeData(data)
            } finally {
                handle.closeFile()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun ensureLogsDirectory(): Boolean = MediaDirectory.ensureDirectory(logsDirectory())

    private fun rotateIfNeeded(path: String) {
        if (fileSize(path) < MAX_FILE_BYTES) return
        val fm = NSFileManager.defaultManager
        val archive = "${logsDirectory()}/rhodes-${Clock.System.now().toEpochMilliseconds()}.log"
        fm.moveItemAtPath(path, archive, null)
        val archives = listLogFiles().filter { it != currentLogPath() }
        if (archives.size > MAX_ARCHIVES) {
            archives.drop(MAX_ARCHIVES).forEach { FileIo.delete(it) }
        }
    }

    private fun fileSize(path: String): Long {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return 0L
        val size = attributes[NSFileSize] as? NSNumber ?: return 0L
        return size.longLongValue
    }
}
