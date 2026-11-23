@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import com.rhodes.privatechat.shared.security.toByteArray
import com.rhodes.privatechat.shared.security.toNSData
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * 媒体目录初始化 + 相对媒体 ID 规则占位（与 ios-port/audit/paths.md P0 结论一致）。
 *
 * paths.md P0 结论：DB/settings 中的 `file://` 绝对路径必须改为「媒体 ID 化」——
 * 模型里存相对 ID（如 `avatar:<uuid>`），渲染层经 MediaRepository 按 ID 落盘/解析。
 * 本文件是 Phase 0 的最小占位：目录结构 + ID 校验/映射规则，正式 MediaRepository
 * 由 Phase 2 实现（备份/恢复管线的 mediaId 字段届时与此对齐）。
 *
 * 目录映射（Application Support ≈ Android filesDir，见 paths.md §4）：
 *   images/      ← Android filesDir/images（头像/图片主存储，备份白名单根）
 *   voice/       ← Android filesDir/voice（语音文件）
 *   chat_images/ ← Android filesDir/chat_images（聊天图）
 *   newspapers/  ← Android filesDir/newspapers（报纸缓存，P2）
 */
object MediaDirectory {

    const val MEDIA_ROOT_NAME = "media"

    /** 相对 ID 前缀 → 子目录。未知前缀落到 media/ 根下。 */
    private val categoryDirs: Map<String, String> = mapOf(
        "avatar" to "images",
        "image" to "images",
        "bg" to "images",
        "gbg" to "images",
        "voice" to "voice",
        "tts" to "voice",
        "chat" to "chat_images",
        "newspaper" to "newspapers",
    )

    fun applicationSupportDirectory(): String =
        (NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory, NSUserDomainMask, true
        ).firstOrNull() as? String) ?: NSTemporaryDirectory()

    fun mediaRootPath(): String = applicationSupportDirectory() + "/$MEDIA_ROOT_NAME"

    /** 创建 media 根目录 + 全部子目录；返回实际存在的目录路径列表。 */
    fun ensureMediaStructure(): List<String> {
        val fm = NSFileManager.defaultManager
        val paths = buildList {
            add(mediaRootPath())
            categoryDirs.values.toSet().forEach { add("${mediaRootPath()}/$it") }
        }
        paths.forEach { ensureDirectory(it) }
        return paths.filter { fm.fileExistsAtPath(it) }
    }

    fun ensureDirectory(path: String): Boolean {
        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(path)) return true
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            fm.createDirectoryAtPath(
                path, withIntermediateDirectories = true, attributes = null, error = error.ptr
            )
        }
    }

    /**
     * 相对媒体 ID 校验 + 映射为 media 根下的相对路径（含子目录）。
     * 非法输入（空、`..`、绝对路径、`file://`/`://`）返回 null。
     * 规则占位：`<category>:<rest>` → `<subdir>/<category>_<rest>`（':' → '_'，防御性 '/' → '_'）。
     */
    fun resolve(relativeId: String): String? {
        if (relativeId.isBlank()) return null
        if (relativeId.contains("..")) return null
        if (relativeId.startsWith("/")) return null
        if (relativeId.contains("://")) return null
        if (relativeId.contains("file://")) return null
        val category = relativeId.substringBefore(':')
        val dir = categoryDirs[category] ?: MEDIA_ROOT_NAME
        val safeName = relativeId.replace(':', '_').replace('/', '_')
        return "$dir/$safeName"
    }

    /** 相对 ID → Application Support 下的绝对路径；非法 ID 返回 null。 */
    fun absolutePathFor(relativeId: String): String? =
        resolve(relativeId)?.let { "${mediaRootPath()}/$it" }

    /** 清理 media 根（测试/恢复前用）。返回是否成功（不存在视作成功）。 */
    fun wipeMediaRoot(): Boolean {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(mediaRootPath())) return true
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            var ok = fm.removeItemAtPath(mediaRootPath(), error.ptr)
            if (!ok) {
                // 目录非空时 removeItemAtPath 会失败——先删已知子目录再重试
                (categoryDirs.values.toSet() + MEDIA_ROOT_NAME).forEach { sub ->
                    fm.removeItemAtPath("${mediaRootPath()}/$sub", null)
                }
                ok = fm.removeItemAtPath(mediaRootPath(), error.ptr)
            }
            ok
        }
    }
}

/** 最小二进制文件读写（Phase 0 骨架；Phase 2 由 MediaRepository/FileIo 正式化）。 */
object FileIo {

    fun writeBytes(path: String, bytes: ByteArray): Boolean {
        val data = bytes.toNSData() ?: return false
        return data.writeToFile(path, atomically = true)
    }

    fun readBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return data.toByteArray()
    }

    fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    fun delete(path: String): Boolean {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(path)) return true
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            fm.removeItemAtPath(path, error.ptr)
        }
    }
}
