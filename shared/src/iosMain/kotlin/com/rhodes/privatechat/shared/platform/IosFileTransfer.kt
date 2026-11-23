@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.platform

import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import platform.Foundation.NSLock
import kotlin.time.Clock

/**
 * 文件操作结果（导出/导入共用；Swift 侧只读）。
 *
 * @param ok 成功与否
 * @param path 导出时=已落盘的本地文件绝对路径；导入时=已复制进 App sandbox 的绝对路径
 * @param error 失败原因（空串=成功）
 * @param cancelled 用户在系统选择器里取消（ok=false 且 cancelled=true）
 */
class FileOperationResult(
    val ok: Boolean,
    val path: String,
    val error: String,
    val cancelled: Boolean,
) {
    override fun toString(): String = "FileOperationResult(ok=$ok, path=$path, error=$error, cancelled=$cancelled)"
}

/**
 * Swift 侧必须实现的「系统文件面板宿主」协议。
 *
 * 为什么需要宿主：`UIDocumentPickerViewController` 必须由持有 window/VC 上下文的
 * SwiftUI 壳来 present；Kotlin/Native 侧只负责「准备文件 / 校验 / 落盘 / 回传」，
 * 不硬编码任何 UI 操作（沿用 iosbridge 的「Kotlin 定义接口 + Swift 注入」窄门面思路）。
 *
 * 契约：
 * - [presentExport]：宿主用 `UIDocumentPickerViewController(forExporting: [url], asCopy: true)` 弹面板，
 *   成功/取消后回调 [onResult]（path 填用户可见位置或本地路径，均可）。
 * - [presentImport]：宿主用 `UIDocumentPickerViewController(forOpeningContentTypes:)` 选文件，
 *   **必须**把 security-scoped 文件复制到临时目录后再回调 `onResult(FileOperationResult(ok=true, path=<本地路径>))`；
 *   取消时回调 `cancelled=true`。
 * - 回调可以在任意线程；[IosFileTransfer] 汇入调用方线程不做保证，调用方自行切主线程。
 */
interface FilePickerHost {
    fun presentExport(url: String, suggestedName: String, onResult: (FileOperationResult) -> Unit)
    fun presentImport(onResult: (FileOperationResult) -> Unit)
}

/**
 * iOS 文件导出/导入服务（Kotlin 侧逻辑 + Swift 宿主注入）。
 *
 * 目录：`Application Support/exports/`（导出 staging）与 `Application Support/imports/`（导入落盘），
 * 复用 T9 的 [MediaDirectory.applicationSupportDirectory] 与 [FileIo] 文件 IO。
 */
object IosFileTransfer {

    private const val EXPORTS_DIR = "exports"
    private const val IMPORTS_DIR = "imports"

    private val lock = NSLock()
    private var host: FilePickerHost? = null

    /** 由 Swift 壳在启动时注入（`nil` = 卸载）。 */
    fun installHost(host: FilePickerHost?) {
        lock.lock()
        try {
            this.host = host
        } finally {
            lock.unlock()
        }
    }

    fun hasHost(): Boolean {
        lock.lock()
        return try {
            host != null
        } finally {
            lock.unlock()
        }
    }

    private fun currentHost(): FilePickerHost? {
        lock.lock()
        return try {
            host
        } finally {
            lock.unlock()
        }
    }

    fun exportsDirectory(): String =
        "${MediaDirectory.applicationSupportDirectory()}/$EXPORTS_DIR"

    fun importsDirectory(): String =
        "${MediaDirectory.applicationSupportDirectory()}/$IMPORTS_DIR"

    /** 导出文本文件（先写 staging，再交给系统保存面板）。 */
    fun exportText(fileName: String, text: String, onResult: (FileOperationResult) -> Unit) {
        exportBytes(fileName, text.encodeToByteArray(), onResult)
    }

    /** 导出字节文件（先写 staging，再交给系统保存面板）。 */
    fun exportBytes(fileName: String, bytes: ByteArray, onResult: (FileOperationResult) -> Unit) {
        val safeName = sanitizeFileName(fileName)
        val target = stagedExportPath(safeName)
        if (!ensureDirectory(exportsDirectory()) || !FileIo.writeBytes(target, bytes)) {
            onResult(FileOperationResult(false, target, "failed to write export staging file", false))
            return
        }
        presentExport(target, safeName, onResult)
    }

    /** 导出已存在的文件（复制到 staging 后交给系统保存面板；日志导出等复用）。 */
    fun exportExistingFile(sourcePath: String, suggestedName: String, onResult: (FileOperationResult) -> Unit) {
        if (sourcePath.isEmpty() || !FileIo.exists(sourcePath)) {
            onResult(FileOperationResult(false, sourcePath, "source file not found", false))
            return
        }
        val bytes = FileIo.readBytes(sourcePath)
        if (bytes == null) {
            onResult(FileOperationResult(false, sourcePath, "failed to read source file", false))
            return
        }
        exportBytes(suggestedName, bytes, onResult)
    }

    /** 弹出系统文件选择器导入；选中文件复制进 `imports/` 后回调。 */
    fun importFile(onResult: (FileOperationResult) -> Unit) {
        val picker = currentHost()
        if (picker == null) {
            onResult(FileOperationResult(false, "", "file picker host not installed", false))
            return
        }
        picker.presentImport { picked ->
            if (!picked.ok || picked.path.isEmpty()) {
                onResult(picked)
                return@presentImport
            }
            copyIntoImports(picked.path, picked.path.substringAfterLast('/'), onResult)
        }
    }

    /** 复制外部文件到 `imports/`（幂等命名：时间戳前缀避免覆盖）。 */
    fun copyIntoImports(sourcePath: String, displayName: String, onResult: (FileOperationResult) -> Unit) {
        if (!FileIo.exists(sourcePath)) {
            onResult(FileOperationResult(false, sourcePath, "picked file not found", false))
            return
        }
        val bytes = FileIo.readBytes(sourcePath)
        if (bytes == null) {
            onResult(FileOperationResult(false, sourcePath, "failed to read picked file", false))
            return
        }
        if (!ensureDirectory(importsDirectory())) {
            onResult(FileOperationResult(false, sourcePath, "failed to create imports directory", false))
            return
        }
        val target = "${importsDirectory()}/imported-${Clock.System.now().toEpochMilliseconds()}-${sanitizeFileName(displayName)}"
        if (!FileIo.writeBytes(target, bytes)) {
            onResult(FileOperationResult(false, target, "failed to write imported file", false))
            return
        }
        onResult(FileOperationResult(true, target, "", false))
    }

    /** 文件名清洗：去掉路径分隔/控制字符，限长 120，保留扩展名；空名回退 `rhodes-export`。 */
    fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base
            .map { ch ->
                if (ch == '/' || ch == '\\' || ch == ':' || ch == '\u0000' || ch.isISOControl()) '_' else ch
            }
            .joinToString("")
            .trim()
            .trimStart('.')
        if (cleaned.isEmpty()) return "rhodes-export"
        if (cleaned.length <= 120) return cleaned
        val dot = cleaned.lastIndexOf('.')
        if (dot <= 0 || dot >= cleaned.length - 1) return cleaned.take(120)
        val ext = cleaned.substring(dot).take(16)
        return cleaned.take(120 - ext.length) + ext
    }

    private fun stagedExportPath(safeName: String): String = "${exportsDirectory()}/$safeName"

    private fun presentExport(stagedPath: String, suggestedName: String, onResult: (FileOperationResult) -> Unit) {
        val picker = currentHost()
        if (picker == null) {
            // 无 UI 宿主时仍返回 staging 路径（调用方可自行用 share sheet 分享）
            onResult(FileOperationResult(true, stagedPath, "file picker host not installed (local export only)", false))
            return
        }
        picker.presentExport(stagedPath, suggestedName, onResult)
    }

    private fun ensureDirectory(path: String): Boolean = MediaDirectory.ensureDirectory(path)
}
