package com.rhodes.privatechat.data.backup

import android.content.Context
import android.net.Uri
import com.rhodes.privatechat.data.MessageExport
import com.rhodes.privatechat.data.OperatorExport
import com.rhodes.privatechat.data.SessionExport
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.model.ChatArchive
import com.rhodes.privatechat.shared.model.ChatHistorySegment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 单会话结构化聊天导出（RhodesLinkChatExport v1，T30）。
 *
 * 行为 oracle / 复用点（全部只读引用，不改现有备份代码）：
 *  - ZIP + SHA-256 + manifest 模式     `BackupFileWriter.kt:46-97,133-141`（≈90% 同构：manifest 最后写、
 *    逐条目 `{path,size,sha256}`、`encodeDefaults=true`）
 *  - manifest 模型                     `BackupModels.kt:36-76`（[BackupManifest]/[BackupFileEntry] 原样复用）
 *  - 媒体引用扫描 + 自有文件白名单       `BackupMediaCollector.kt:17-36,43-69,74-80,86`
 *  - 图片消息 JSON / ai_json 内嵌图     `ChatViewModel.kt:1859-1863`（content 原样保留，仅媒体清单登记）
 *  - 会话/消息投影                      `ExportPayload.kt:86-113`
 *  - 消息排序                           `ChatMessages.sq:24`（ORDER BY timestamp ASC, id ASC → repository.getMessagesSync）
 *
 * 相对 oracle 的唯一差异（T6 裁决，规格卡 §风险 3）：mediaId 改「内容派生」`image/<sha256(bytes)>`，
 * 而非 `local-image/<sha256(path)[:24]>`（路径哈希在 iOS 无意义）；archivePath 仍沿用 24 hex 约定。
 */
class ChatExportService(
    private val context: Context,
    private val repository: ChatRepository,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { encodeDefaults = true }

    /**
     * 导出单会话到 [openOutput] 返回的流。调用方负责 SAF 写盘与失败清理（BackupRestoreScreen 同款）。
     * 失败抛 [BackupFormatException]（中文可直接展示）。
     */
    suspend fun export(sessionId: String, openOutput: () -> OutputStream): ChatExportResult {
        val session = repository.getSession(sessionId) ?: throw BackupFormatException("找不到要导出的会话")
        val messages = repository.getMessagesSync(sessionId)
        if (messages.isEmpty()) throw BackupFormatException("这个聊天还没有可以导出的消息")
        val operator = repository.getOperator(session.operatorId)
        val displayEvents = repository.messages.getAllDisplayEvents()
            .filter { it.sessionId == sessionId }
            .sortedWith(compareBy({ it.revealOrder }, { it.messageId }, { it.segmentIndex }))
        val archives: List<ChatArchive> = repository.archives.getArchives(sessionId)
        val historySegments: List<ChatHistorySegment> = repository.archives.getHistorySegments(sessionId)

        // 媒体收集：只收自有文件（owned root 白名单），内容哈希去重。
        val mediaFiles = linkedMapOf<String, Pair<ChatExportMediaItem, ByteArray>>()
        val skipped = linkedSetOf<String>()
        collectReferencedUris(operator?.avatarUri, session.avatarUri, messages.map { it.content })
            .filter { it.isNotBlank() }
            .forEach { ref ->
                val file = ownedImageFile(ref)
                if (file == null) {
                    skipped += ref
                    return@forEach
                }
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    skipped += ref
                    return@forEach
                }
                if (bytes.size.toLong() > MAX_ENTRY_BYTES) throw BackupFormatException("聊天图片过大，无法导出：${file.name}")
                val sha = sha256(bytes)
                if (mediaFiles.containsKey(sha)) return@forEach
                val extension = extensionOf(file).ifBlank { "bin" }
                mediaFiles[sha] = ChatExportMediaItem(
                    mediaId = "image/$sha",
                    archivePath = "media/images/${sha.take(24)}.$extension",
                    mimeType = mimeType(extension),
                    originalUri = Uri.fromFile(file).toString(),
                    size = bytes.size.toLong(),
                    sha256 = sha,
                ) to bytes
            }

        val conversation = ChatExportConversation(
            formatVersion = FORMAT_VERSION,
            exportedAt = now(),
            operator = operator?.let(OperatorExport::fromEntity),
            session = SessionExport.fromEntity(session),
            messages = messages.map(MessageExport::fromEntity),
            displayEvents = displayEvents,
            chatArchives = archives,
            chatHistorySegments = historySegments,
            mediaIndex = mediaFiles.values.map { it.first },
        )
        val conversationBytes = json.encodeToString(conversation).encodeToByteArray()
        if (conversationBytes.size.toLong() > MAX_CONVERSATION_BYTES) {
            throw BackupFormatException("聊天记录过大，暂不支持单会话导出超过 100MB")
        }

        // 条目清单（写入序）：conversation.json → media/**（按 archivePath 排序）→ manifest.json。
        val mediaEntries = mediaFiles.values.sortedBy { it.first.archivePath }
        val entries = mutableListOf<BackupFileEntry>()
        entries += BackupFileEntry(CONVERSATION_PATH, conversationBytes.size.toLong(), sha256(conversationBytes))
        mediaEntries.forEach { (item, bytes) -> entries += BackupFileEntry(item.archivePath, bytes.size.toLong(), sha256(bytes)) }

        val manifest = BackupManifest(
            format = CHAT_FORMAT,
            formatVersion = FORMAT_VERSION,
            scope = "conversation",
            appVersion = appVersion,
            schemaVersion = SCHEMA_VERSION,
            createdAt = now(),
            backupId = UUID.randomUUID().toString(),
            mediaIncluded = mediaEntries.isNotEmpty(),
            recordCounts = mapOf(
                "operators" to if (operator == null) 0 else 1,
                "sessions" to 1,
                "messages" to messages.size,
                "displayEvents" to displayEvents.size,
                "archives" to archives.size,
                "historySegments" to historySegments.size,
                "mediaFiles" to mediaEntries.size,
            ),
            files = entries.toList(),
            excludedCategories = EXCLUDED_CATEGORIES.toList(),
            layout = "single_payload",
            pages = emptyList(),
        )

        openOutput().buffered().use { output ->
            ZipOutputStream(output).use { zip ->
                putBytes(zip, CONVERSATION_PATH, conversationBytes)
                mediaEntries.forEach { (item, bytes) -> putBytes(zip, item.archivePath, bytes) }
                // manifest 最后写（BackupFileWriter.kt:96 同序）
                putBytes(zip, MANIFEST_PATH, json.encodeToString(manifest).encodeToByteArray())
            }
        }
        return ChatExportResult(manifest, messages.size, mediaEntries.size, skipped.toList())
    }

    // ---- 媒体引用扫描（镜像 BackupMediaCollector.kt:43-57 的扫描对象，限定单会话） ----

    private fun collectReferencedUris(
        operatorAvatar: String?,
        sessionAvatar: String,
        messageContents: List<String>,
    ): Sequence<String> = sequence {
        yield(operatorAvatar.orEmpty())
        yield(sessionAvatar)
        for (content in messageContents) {
            for (match in imageUriPattern.findAll(content)) {
                yield(match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\"))
            }
        }
    }

    /** 自有文件白名单（镜像 BackupMediaCollector.kt:59-69：file scheme + 三根目录 + canonical 前缀）。 */
    private fun ownedImageFile(value: String): File? {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null)
        if (!file.isFile || !file.canRead() || file.length() <= 0L) return null
        if (file.length() > MAX_ENTRY_BYTES) return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { candidate -> ownedRoots.any { root -> candidate.path.startsWith(root.path + File.separator) } }
    }

    private val ownedRoots: List<File>
        get() = listOf("images", "chat_images", "restored_media").map { File(context.filesDir, it).canonicalFile }

    private fun extensionOf(file: File): String =
        file.extension.lowercase().takeIf { it.isNotBlank() } ?: "bin"

    private fun mimeType(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val CHAT_FORMAT = "rhodes-chat"
        const val FORMAT_VERSION = 1
        const val MANIFEST_PATH = "manifest.json"
        const val CONVERSATION_PATH = "conversation.json"
        const val SCHEMA_VERSION = 1
        private const val MAX_CONVERSATION_BYTES = 100L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 100L * 1024 * 1024
        val EXCLUDED_CATEGORIES = listOf(
            "memories", "diaries", "moments", "knowledge_bases",
            "relationships", "settings", "credentials",
        )
        private val imageUriPattern = Regex("\\\"imageUri\\\"\\s*:\\s*\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"")
    }
}
