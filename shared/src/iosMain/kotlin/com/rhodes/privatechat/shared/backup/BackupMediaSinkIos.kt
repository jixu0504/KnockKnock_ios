package com.rhodes.privatechat.shared.backup

import com.rhodes.privatechat.shared.security.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.writeToFile

/**
 * iOS 媒体落盘 actual（oracle：app/.../data/backup/BackupMediaStager.kt:9-32 的落盘语义）。
 * 目录：<documentsDirectory>/restored_media/<backupId>/<archivePath>（对应 paths.md §4 的
 * filesDir/restored_media/<backupId> 映射）。防穿越：archivePath 必须位于 media/ 内且无 .. 段。
 */
@OptIn(ExperimentalForeignApi::class)
class BackupMediaSinkIos(
    private val backupId: String,
    documentsDirectory: String,
) : BackupMediaSink {

    private val rootPath: String = documentsDirectory.trimEnd('/') + "/restored_media/" + backupId

    override fun stage(media: List<BackupMediaItemWire>, entryBytes: Map<String, ByteArray>): Map<String, String> {
        val restored = linkedMapOf<String, String>()
        val fileManager = NSFileManager.defaultManager
        for (item in media) {
            if (!BackupFileReader.isSafeArchivePath(item.archivePath) || !item.archivePath.startsWith("media/")) {
                throw BackupFormatException("媒体路径不安全：${item.archivePath}")
            }
            val data = entryBytes[item.archivePath] ?: throw BackupFormatException("缺少媒体文件：${item.archivePath}")
            val target = rootPath + "/" + item.archivePath
            val parent = target.substringBeforeLast('/', target)
            if (parent.isNotEmpty()) {
                val created = fileManager.createDirectoryAtPath(parent, true, null, null)
                if (!created && !fileManager.fileExistsAtPath(parent)) {
                    throw BackupFormatException("媒体目录创建失败：$parent")
                }
            }
            val nsData: NSData = data.toNSData()
                ?: throw BackupFormatException("媒体字节转换失败：${item.archivePath}")
            if (!nsData.writeToFile(target, atomically = false)) {
                throw BackupFormatException("媒体文件写入失败：$target")
            }
            restored[item.mediaId] = NSURL.fileURLWithPath(target).absoluteString ?: "file://$target"
        }
        if (restored.keys != media.map { it.mediaId }.toSet()) {
            throw BackupFormatException("备份媒体文件不完整，已取消恢复")
        }
        return restored
    }

    override fun cleanup() {
        runCatching { NSFileManager.defaultManager.removeItemAtPath(rootPath, null) }
    }
}
