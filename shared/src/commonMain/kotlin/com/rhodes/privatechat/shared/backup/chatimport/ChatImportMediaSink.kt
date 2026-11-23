package com.rhodes.privatechat.shared.backup.chatimport

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.domain.media.MediaStore

/**
 * 聊天导入媒体落盘抽象（规格卡契约 B18）。
 *
 * 与 T10 恢复的 `BackupMediaSink`（落 `restored_media/<backupId>` 暂存目录）不同：聊天导入把媒体写进
 * **运行时媒体库**（T17 `MediaStore` 的 media 根），返回可被 `MediaRepository.resolve` 命中的相对媒体 ID
 * （`media/import-<sha256(content)[:24]>.<ext>`）。内容寻址 → 同一聊天包重复导入不产生新文件（幂等）。
 *
 * 失败语义：任何一件写失败抛异常，由 [ChatMergeRestorer] 捕获后统一 [cleanup] 并整体失败（不留半套媒体）。
 */
interface ChatImportMediaSink {
    /**
     * @param media 解析后的媒体清单（`conversation.mediaIndex`）。
     * @param entryBytes archivePath → 字节（由 [ChatExportReader] 做完全部 size/SHA-256 校验）。
     * @return mediaId → 运行时媒体 ID（如 `media/import-3f2a…c1.jpg`）。
     */
    fun stage(media: List<ChatExportMediaItemWire>, entryBytes: Map<String, ByteArray>): Map<String, String>

    /** 删除本次导入新建的文件（已存在同名文件不删除，保证重复导入幂等）。 */
    fun cleanup()
}

/**
 * 生产实现：经 T17 [MediaStore] 写 media 根（iOS = Application Support/media，T9 契约 11）。
 * 写入路径 `images/media/import-<sha24>.<ext>`，返回 ID 与 [com.rhodes.privatechat.shared.domain.media.MediaRepository]
 * 的 `media/<uuid>.<ext>` 命名空间一致（`MediaId.parse` 合法、`resolve` 可检索）。
 */
class MediaStoreChatImportMediaSink(
    private val store: MediaStore,
) : ChatImportMediaSink {

    private val createdPaths = mutableListOf<String>()

    override fun stage(
        media: List<ChatExportMediaItemWire>,
        entryBytes: Map<String, ByteArray>,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (item in media) {
            val bytes = entryBytes[item.archivePath]
                ?: throw BackupFormatException("缺少媒体文件：${item.archivePath}")
            if (bytes.isEmpty()) throw BackupFormatException("媒体文件为空：${item.archivePath}")
            val extension = extensionOf(item.archivePath).ifBlank { "bin" }
            val sha = BackupCrypto.sha256Hex(bytes).take(24)
            val mediaId = "media/import-$sha.$extension"
            val relativePath = "images/$mediaId"
            val existed = store.exists(relativePath)
            if (!store.writeBytes(relativePath, bytes)) {
                store.delete(relativePath) // 镜像 ImageUtils.kt:24-32：绝不残留空/半写文件
                throw BackupFormatException("媒体文件写入失败：${item.archivePath}")
            }
            if (!existed) createdPaths += relativePath
            result[item.mediaId] = mediaId
        }
        return result
    }

    override fun cleanup() {
        createdPaths.forEach { store.delete(it) }
        createdPaths.clear()
    }

    private fun extensionOf(path: String): String =
        path.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) } ?: "bin"
}
