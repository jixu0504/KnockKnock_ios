package com.rhodes.privatechat.shared.backup.chatimport

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupFileEntryWire
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.backup.BackupManifestWire
import com.rhodes.privatechat.shared.backup.BackupZipWriter
import com.rhodes.privatechat.shared.data.BackupChatDisplayEvent
import com.rhodes.privatechat.shared.model.ChatArchive
import com.rhodes.privatechat.shared.model.ChatHistorySegment
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.MessageExport
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.SessionExport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * RhodesLinkChatExport v1 Writer（T38 iOS UI 接线配套；格式冻结于
 * `ios-port/specs/chat-import-impl-spec.md` §A + `ios-port/golden/chat-import/README.md`）。
 *
 * Android oracle：`app/src/main/java/com/rhodes/privatechat/data/backup/ChatExportService.kt`
 * 与 `ChatExportModels.kt`（T30 新增）；本类只做「单会话数据 → 冻结容器字节」的纯函数式组装，
 * 读写模型直接复用 [ChatExportConversationWire]，保证 iOS 导出可被 iOS/Android 读取器校验通过。
 *
 * 容器：`manifest.json` + `conversation.json` + `media/images/<sha256(content)[:24]>.<ext>`；
 * ZIP 走 commonMain 零依赖 [BackupZipWriter]（STORED）；
 * mediaId 内容派生 `image/<sha256 全 64 hex>`（与备份格式的路径派生不同，见规格卡风险 3）。
 *
 * 本类**不**决定文件保存位置（DocumentPicker/Files 由 Swift 侧负责），只返回 zip 字节与清单。
 */
class ChatExportWriter(
    private val appVersion: String = "RhodesLink-iOS",
    private val now: () -> Long = { 0L },
    private val newBackupId: () -> String = { randomUuid() },
) {
    private val json = Json { encodeDefaults = true }

    data class Written(
        val bytes: ByteArray,
        val manifest: BackupManifestWire,
        val messageCount: Int,
        val mediaCount: Int,
        val archiveCount: Int,
        val historySegmentCount: Int,
    )

    /**
     * 组装单会话导出包。
     *
     * @param resolveMedia 媒体引用（operator/session avatarUri、消息 content 内的 imageUri）→ 本地字节；
     *   返回 null 表示不可解析（远程/内置头像/文件缺失），该引用保留原值不重写（镜像 Android
     *   `ChatExportService.kt` 的 skippedUris 语义）。
     */
    fun write(
        operator: Operator?,
        session: ChatSession,
        messages: List<ChatMessage>,
        displayEvents: List<BackupChatDisplayEvent> = emptyList(),
        chatArchives: List<ChatArchive> = emptyList(),
        chatHistorySegments: List<ChatHistorySegment> = emptyList(),
        resolveMedia: (String) -> ByteArray? = { null },
    ): Written {
        if (messages.isEmpty()) throw BackupFormatException("该会话没有消息，无法导出聊天记录")
        val exportedAt = now()

        // ---- 消息：按 (timestamp, id) 升序（ChatMessages.sq:24）----
        val sortedMessages = messages.sortedWith(compareBy({ it.timestamp }, { it.id }))
        val messageIds = sortedMessages.map { it.id }.toSet()

        // ---- displayEvents：本会话 + revealOrder 归一（<=0 时按文件序 1-based）----
        val events = displayEvents
            .filter { it.sessionId == session.id }
            .filter { it.messageId in messageIds }
            .sortedWith(compareBy({ it.revealOrder }, { it.messageId }, { it.segmentIndex }))
            .mapIndexed { index, event ->
                if (event.revealOrder > 0L) event else event.copy(revealOrder = index + 1L)
            }

        // ---- 媒体收集：内容派生 mediaId（同字节去重）----
        val media = linkedMapOf<String, Pair<ChatExportMediaItemWire, ByteArray>>()
        val refs = linkedSetOf<String>()
        operator?.avatarUri?.takeIf { it.isNotBlank() }?.let { refs += it }
        session.avatarUri.takeIf { it.isNotBlank() }?.let { refs += it }
        sortedMessages.forEach { message -> refs += imageRefsIn(message.content) }
        refs.forEach { ref ->
            val bytes = runCatching { resolveMedia(ref) }.getOrNull() ?: return@forEach
            if (bytes.isEmpty()) return@forEach
            val sha = BackupCrypto.sha256Hex(bytes)
            if (media.containsKey(sha)) return@forEach
            val extension = extensionOf(ref)
            val item = ChatExportMediaItemWire(
                mediaId = "image/$sha",
                archivePath = "${ChatExportReader.CHAT_EXPORT_MEDIA_ROOT}/images/${sha.take(24)}.$extension",
                mimeType = mimeTypeOf(extension),
                originalUri = ref,
                size = bytes.size.toLong(),
                sha256 = sha,
            )
            media[sha] = item to bytes
        }

        val conversation = ChatExportConversationWire(
            formatVersion = ChatExportReader.CHAT_EXPORT_FORMAT_VERSION,
            exportedAt = exportedAt,
            operator = operator,
            session = session.toSessionExport(),
            messages = sortedMessages.map { it.toMessageExport() },
            displayEvents = events,
            chatArchives = chatArchives.filter { it.sessionId == session.id },
            chatHistorySegments = chatHistorySegments.filter { it.sessionId == session.id },
            mediaIndex = media.values.map { it.first },
        )

        // ---- 清单 + ZIP（manifest 最后写）----
        val conversationBytes = json.encodeToString(conversation).encodeToByteArray()
        val files = mutableListOf(
            BackupFileEntryWire(
                path = ChatExportReader.CHAT_EXPORT_CONVERSATION_PATH,
                size = conversationBytes.size.toLong(),
                sha256 = BackupCrypto.sha256Hex(conversationBytes),
            ),
        )
        media.values.sortedBy { it.first.archivePath }.forEach { (item, bytes) ->
            files += BackupFileEntryWire(item.archivePath, bytes.size.toLong(), item.sha256)
        }
        val manifest = BackupManifestWire(
            format = ChatExportReader.CHAT_EXPORT_FORMAT,
            formatVersion = ChatExportReader.CHAT_EXPORT_FORMAT_VERSION,
            scope = ChatExportReader.CHAT_EXPORT_SCOPE,
            appVersion = appVersion,
            schemaVersion = 1,
            createdAt = exportedAt,
            backupId = newBackupId(),
            mediaIncluded = media.isNotEmpty(),
            recordCounts = mapOf(
                "operators" to if (operator == null) 0 else 1,
                "sessions" to 1,
                "messages" to sortedMessages.size,
                "displayEvents" to events.size,
                "archives" to chatArchives.count { it.sessionId == session.id },
                "historySegments" to chatHistorySegments.count { it.sessionId == session.id },
                "mediaFiles" to media.size,
            ),
            files = files,
            excludedCategories = EXCLUDED_CATEGORIES,
        )
        val entries = buildList {
            add(BackupZipWriter.Entry(ChatExportReader.CHAT_EXPORT_CONVERSATION_PATH, conversationBytes))
            media.values.sortedBy { it.first.archivePath }.forEach { (item, bytes) ->
                add(BackupZipWriter.Entry(item.archivePath, bytes))
            }
            add(BackupZipWriter.Entry(ChatExportReader.CHAT_EXPORT_MANIFEST_PATH, json.encodeToString(manifest).encodeToByteArray()))
        }
        return Written(
            bytes = BackupZipWriter.write(entries),
            manifest = manifest,
            messageCount = sortedMessages.size,
            mediaCount = media.size,
            archiveCount = chatArchives.count { it.sessionId == session.id },
            historySegmentCount = chatHistorySegments.count { it.sessionId == session.id },
        )
    }

    /** 收集消息 content 内所有 `imageUri` 字符串（text/ai_json/image 三种形态递归扫描）。 */
    private fun imageRefsIn(content: String): List<String> {
        if (content.isBlank()) return emptyList()
        val element = runCatching { json.parseToJsonElement(content) }.getOrNull() ?: return emptyList()
        val found = mutableListOf<String>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    if (key == "imageUri") {
                        (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { found += it }
                    }
                    walk(value)
                }
                is JsonArray -> node.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(element)
        return found
    }

    private fun extensionOf(ref: String): String {
        val raw = ref.substringBefore('?').substringAfterLast('.', "").lowercase()
        return raw.takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: "bin"
    }

    private fun mimeTypeOf(extension: String): String = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    private fun ChatSession.toSessionExport(): SessionExport = SessionExport(
        id = id,
        operatorId = operatorId,
        operatorName = operatorName,
        lastMessage = lastMessage,
        lastTime = lastTime,
        mode = mode,
        isPinned = isPinned,
        unreadCount = unreadCount,
        members = members,
        rules = rules,
        avatarUri = avatarUri,
        mutedMembers = mutedMembers,
    )

    private fun ChatMessage.toMessageExport(): MessageExport = MessageExport(
        id = id,
        sessionId = sessionId,
        senderId = senderId,
        senderName = senderName,
        content = content,
        type = type,
        mode = mode,
        emotion = emotion,
        activity = activity,
        location = location,
        narration = narration,
        segmentGroup = segmentGroup,
        intimacyChange = intimacyChange,
        timestamp = timestamp,
        isMe = isMe,
    )

    companion object {
        private val EXCLUDED_CATEGORIES = listOf(
            "chat_archives_default", "memories", "diaries", "moments",
            "knowledge_bases", "relationships", "settings", "credentials",
        )

        internal fun randomUuid(): String {
            val bytes = kotlin.random.Random.nextBytes(16)
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            val hex = "0123456789abcdef"
            return buildString(36) {
                bytes.forEachIndexed { index, byte ->
                    if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
                    val value = byte.toInt() and 0xFF
                    append(hex[value ushr 4])
                    append(hex[value and 0x0F])
                }
            }
        }
    }
}
