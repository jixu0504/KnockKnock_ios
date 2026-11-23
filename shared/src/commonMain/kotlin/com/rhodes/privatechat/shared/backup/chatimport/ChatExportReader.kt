package com.rhodes.privatechat.shared.backup.chatimport

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupFileEntryWire
import com.rhodes.privatechat.shared.backup.BackupFileReader
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.backup.BackupManifestWire
import com.rhodes.privatechat.shared.backup.BackupZip
import com.rhodes.privatechat.shared.data.BackupChatDisplayEvent
import com.rhodes.privatechat.shared.model.ChatArchive
import com.rhodes.privatechat.shared.model.ChatHistorySegment
import com.rhodes.privatechat.shared.model.MessageExport
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.SessionExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * RhodesLinkChatExport v1 wire 模型（规格卡 `ios-port/specs/chat-import-impl-spec.md` §B；格式冻结于
 * `ios-port/golden/chat-import/README.md`）。
 *
 * 与 Android 侧 `app/.../data/backup/ChatExportModels.kt` 字段逐一同名；`ignoreUnknownKeys=true` 前向兼容。
 * `operator/session/messages` 直接复用 shared 模型（与 `app/data/ExportPayload.kt:46-113` 同形），
 * `displayEvents/chatArchives/chatHistorySegments` 复用备份页同形模型（`data/MessageRepository.kt:34-40`、`model/ChatArchive.kt`）。
 */
@Serializable
data class ChatExportConversationWire(
    val formatVersion: Int = 1,
    val exportedAt: Long = 0L,
    val operator: Operator? = null,
    val session: SessionExport? = null,
    val messages: List<MessageExport> = emptyList(),
    val displayEvents: List<BackupChatDisplayEvent> = emptyList(),
    val chatArchives: List<ChatArchive> = emptyList(),
    val chatHistorySegments: List<ChatHistorySegment> = emptyList(),
    val mediaIndex: List<ChatExportMediaItemWire> = emptyList(),
)

@Serializable
data class ChatExportMediaItemWire(
    val mediaId: String,
    val archivePath: String,
    val mimeType: String = "application/octet-stream",
    val originalUri: String = "",
    val size: Long = 0L,
    val sha256: String = "",
)

/** 解析/校验通过后的单会话导出包。`mediaBytes` 以 archivePath 为键（供导入媒体 sink 落盘）。 */
data class ChatExportArchive(
    val manifest: BackupManifestWire,
    val conversation: ChatExportConversationWire,
    val mediaBytes: Map<String, ByteArray>,
    val alerts: List<String> = emptyList(),
)

sealed interface ChatExportValidationResult {
    data class Valid(
        val manifest: BackupManifestWire,
        val conversation: ChatExportConversationWire,
        val messageCount: Int,
        val mediaCount: Int,
        val alerts: List<String> = emptyList(),
    ) : ChatExportValidationResult

    data class Invalid(val reason: String) : ChatExportValidationResult
}

/**
 * 单会话导出读取/校验（规格卡契约 B8-B11）。
 *
 * 复用 T10 备份读取器的校验思路（ZIP 路径安全/上限/CRC 走 [BackupZip]；`manifest.files` 与 ZIP 严格一致、
 * 逐条目 size+SHA-256 镜像 `shared/.../backup/BackupFileReader.kt:49-68`），但容器三元组与载荷层为
 * chat v1（`format=rhodes-chat` / `scope=conversation` / `conversation.json`）。
 *
 * 注意：本格式**不是** `rhodes-backup` 子集，T10 [BackupFileReader] 会在首道门禁拒绝（预期行为，规格卡验收 #6）。
 */
class ChatExportReader(
    private val maxEntries: Int = 1_000,
    private val maxEntryBytes: Long = 100L * 1024 * 1024,
    private val maxTotalBytes: Long = 500L * 1024 * 1024,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(bytes: ByteArray): ChatExportValidationResult = runCatching { read(bytes) }.fold(
        onSuccess = { archive ->
            ChatExportValidationResult.Valid(
                manifest = archive.manifest,
                conversation = archive.conversation,
                messageCount = archive.conversation.messages.size,
                mediaCount = archive.mediaBytes.size,
                alerts = archive.alerts,
            )
        },
        onFailure = { ChatExportValidationResult.Invalid(it.message ?: "聊天包无效") },
    )

    fun read(bytes: ByteArray): ChatExportArchive {
        // ---- ZIP 读取：路径安全/重复/上限/CRC（BackupFileReader.kt:32-47 同构，ZIP 层走 BackupZip）----
        val zipEntries = runCatching {
            BackupZip.read(bytes, maxEntries, maxEntryBytes, maxTotalBytes)
        }.getOrElse { throw BackupFormatException(it.message ?: "聊天包无效") }
        val entries = linkedMapOf<String, BackupZip.Entry>()
        var totalBytes = 0L
        for (entry in zipEntries) {
            val path = entry.name.replace('\\', '/')
            if (!BackupFileReader.isSafeArchivePath(path)) throw BackupFormatException("ZIP 路径不安全：$path")
            if (entries.containsKey(path)) throw BackupFormatException("ZIP 存在重复路径：$path")
            if (entry.isDirectory) continue
            totalBytes += entry.uncompressedSize
            if (totalBytes > maxTotalBytes) throw BackupFormatException("ZIP 解压总大小超过限制")
            entries[path] = entry
        }

        // ---- manifest 三元组门禁（契约 B9；BackupFileReader.kt:49-57 同构）----
        val manifestContent = entries[CHAT_EXPORT_MANIFEST_PATH]?.data ?: throw BackupFormatException("缺少 manifest.json")
        val manifest = runCatching {
            json.decodeFromString<BackupManifestWire>(manifestContent.decodeToString())
        }.getOrElse { throw BackupFormatException("manifest.json 无法解析") }
        if (manifest.format != CHAT_EXPORT_FORMAT) {
            throw BackupFormatException("不支持的聊天导出类型：${manifest.format}")
        }
        if (manifest.formatVersion != CHAT_EXPORT_FORMAT_VERSION) {
            throw BackupFormatException("不支持的聊天导出格式版本：${manifest.formatVersion}")
        }
        if (manifest.scope != CHAT_EXPORT_SCOPE) throw BackupFormatException("这不是单会话聊天导出包")
        if (manifest.layout != "single_payload" || manifest.pages.isNotEmpty()) {
            throw BackupFormatException("聊天导出包布局无效")
        }

        // ---- files 清单 ↔ ZIP 严格一致 + 逐条目 size/SHA-256（BackupFileReader.kt:58-68 同构）----
        if (manifest.files.map { it.path }.distinct().size != manifest.files.size) {
            throw BackupFormatException("manifest 存在重复文件条目")
        }
        if (manifest.files.map { it.path }.toSet() != entries.keys - CHAT_EXPORT_MANIFEST_PATH) {
            throw BackupFormatException("manifest 文件清单与 ZIP 内容不一致")
        }
        manifest.files.forEach { file ->
            val actual = entries[file.path] ?: throw BackupFormatException("缺少文件：${file.path}")
            if (actual.uncompressedSize != file.size) throw BackupFormatException("文件大小不一致：${file.path}")
            if (BackupCrypto.sha256Hex(actual.data) != file.sha256.lowercase()) {
                throw BackupFormatException("文件校验失败：${file.path}")
            }
        }

        // ---- conversation.json（契约 B10）----
        val conversationContent = entries[CHAT_EXPORT_CONVERSATION_PATH]?.data
            ?: throw BackupFormatException("缺少 conversation.json")
        val conversation = runCatching {
            json.decodeFromString<ChatExportConversationWire>(conversationContent.decodeToString())
        }.getOrElse { throw BackupFormatException("conversation.json 无法解析") }
        if (conversation.formatVersion != CHAT_EXPORT_FORMAT_VERSION) {
            throw BackupFormatException("不支持的聊天会话格式版本：${conversation.formatVersion}")
        }
        val session = conversation.session ?: throw BackupFormatException("聊天包缺少会话信息")
        if (session.id.isBlank() || session.operatorId.isBlank()) throw BackupFormatException("聊天包会话 ID 不完整")
        if (conversation.messages.isEmpty()) throw BackupFormatException("聊天包没有消息")
        if (conversation.messages.map { it.id }.distinct().size != conversation.messages.size) {
            throw BackupFormatException("聊天包包含重复消息 id")
        }
        if (conversation.messages.any { it.sessionId != session.id }) {
            throw BackupFormatException("消息 sessionId 与会话 id 不一致（仅支持单会话导出）")
        }
        if (conversation.operator != null && conversation.operator.id != session.operatorId) {
            throw BackupFormatException("角色 id 与会话角色不一致")
        }
        val alerts = mutableListOf<String>()
        val sortedIds = conversation.messages.sortedWith(compareBy({ it.timestamp }, { it.id })).map { it.id }
        if (sortedIds != conversation.messages.map { it.id }) alerts += "消息未按 (timestamp,id) 升序导出，导入时将按时间重新排序"

        // ---- displayEvents 引用 + revealOrder 归一（契约 B10）----
        val messageIds = conversation.messages.map { it.id }.toSet()
        if (conversation.displayEvents.map { it.messageId to it.segmentIndex }.distinct().size != conversation.displayEvents.size) {
            throw BackupFormatException("聊天包包含重复消息展示事件")
        }
        conversation.displayEvents.forEach { event ->
            if (event.messageId !in messageIds) throw BackupFormatException("消息展示事件引用了不存在的消息：${event.messageId}")
            if (event.sessionId != session.id) throw BackupFormatException("消息展示事件 sessionId 不一致")
        }
        val normalizedEvents = conversation.displayEvents
            .sortedWith(compareBy({ it.revealOrder }, { it.messageId }, { it.segmentIndex }))
            .mapIndexed { index, event ->
                if (event.revealOrder > 0L) event else event.copy(revealOrder = index + 1L)
            }
        if (conversation.displayEvents.any { it.revealOrder <= 0L }) alerts += "部分消息展示事件缺少揭示顺序，已按文件顺序补齐"

        // ---- 存档/历史片段：引用可重映射（契约 B16 前置校验）----
        val archiveIds = conversation.chatArchives.flatMap { archiveIdsIn(it.messagesJson) }
        val historyIds = conversation.chatHistorySegments.flatMap { archiveIdsIn(it.messagesJson) }
        (archiveIds + historyIds).filter { it !in messageIds }.firstOrNull()?.let { missing ->
            throw BackupFormatException("聊天存档引用了不存在的消息：$missing")
        }
        conversation.chatArchives.forEach { archive ->
            if (archive.sessionId != session.id) throw BackupFormatException("聊天存档 sessionId 不一致")
        }
        conversation.chatHistorySegments.forEach { segment ->
            if (segment.sessionId != session.id) throw BackupFormatException("聊天历史片段 sessionId 不一致")
        }

        // ---- mediaIndex ↔ manifest ↔ ZIP 三方一致（契约 B11）----
        val mediaIndex = conversation.mediaIndex
        if (mediaIndex.map { it.mediaId }.distinct().size != mediaIndex.size) throw BackupFormatException("聊天包包含重复媒体 id")
        if (mediaIndex.map { it.archivePath }.distinct().size != mediaIndex.size) throw BackupFormatException("聊天包包含重复媒体路径")
        val manifestMedia = manifest.files.filter { it.path.startsWith("$CHAT_EXPORT_MEDIA_ROOT/") }
        if (manifestMedia.map { it.path }.toSet() != mediaIndex.map { it.archivePath }.toSet()) {
            throw BackupFormatException("媒体索引与 manifest 媒体清单不一致")
        }
        val zipMedia = entries.keys.filter { it.startsWith("$CHAT_EXPORT_MEDIA_ROOT/") }.toSet()
        if (zipMedia != manifestMedia.map { it.path }.toSet()) {
            throw BackupFormatException("媒体清单与 ZIP 内容不一致")
        }
        val mediaBytes = linkedMapOf<String, ByteArray>()
        mediaIndex.forEach { item ->
            val entry = entries[item.archivePath] ?: throw BackupFormatException("缺少媒体文件：${item.archivePath}")
            if (item.size != entry.uncompressedSize) throw BackupFormatException("媒体大小不一致：${item.archivePath}")
            val sha = BackupCrypto.sha256Hex(entry.data)
            if (item.sha256.lowercase() != sha) throw BackupFormatException("媒体校验失败：${item.archivePath}")
            if (item.mediaId != "image/$sha") throw BackupFormatException("媒体 id 不是内容派生：${item.mediaId}")
            mediaBytes[item.archivePath] = entry.data
        }
        if (manifest.mediaIncluded != mediaBytes.isNotEmpty()) throw BackupFormatException("媒体标记与媒体清单不一致")

        // ---- recordCounts 对账（契约 B11/导入结果自检基线）----
        val counts = manifest.recordCounts
        fun checkCount(key: String, expected: Int) {
            val actual = counts[key] ?: return
            if (actual != expected) throw BackupFormatException("recordCounts.$key 与聊天包内容不一致")
        }
        checkCount("operators", if (conversation.operator == null) 0 else 1)
        checkCount("sessions", 1)
        checkCount("messages", conversation.messages.size)
        checkCount("displayEvents", normalizedEvents.size)
        checkCount("archives", conversation.chatArchives.size)
        checkCount("historySegments", conversation.chatHistorySegments.size)
        checkCount("mediaFiles", mediaIndex.size)

        return ChatExportArchive(
            manifest = manifest,
            conversation = conversation.copy(displayEvents = normalizedEvents),
            mediaBytes = mediaBytes,
            alerts = alerts,
        )
    }

    /** 收集存档/历史片段 messagesJson 中出现过的所有消息 id（键名 id/messageId 的数字值）。 */
    private fun archiveIdsIn(messagesJson: String): List<Long> {
        if (messagesJson.isBlank()) return emptyList()
        val element = runCatching { json.parseToJsonElement(messagesJson) }.getOrElse {
            throw BackupFormatException("聊天存档内容无法解析")
        }
        val found = mutableListOf<Long>()
        fun walk(node: kotlinx.serialization.json.JsonElement) {
            when (node) {
                is kotlinx.serialization.json.JsonObject -> node.forEach { (key, value) ->
                    if (key == "id" || key == "messageId") {
                        (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.let { found += it }
                    }
                    walk(value)
                }
                is kotlinx.serialization.json.JsonArray -> node.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(element)
        return found
    }

    companion object {
        const val CHAT_EXPORT_FORMAT = "rhodes-chat"
        const val CHAT_EXPORT_FORMAT_VERSION = 1
        const val CHAT_EXPORT_SCOPE = "conversation"
        const val CHAT_EXPORT_MANIFEST_PATH = "manifest.json"
        const val CHAT_EXPORT_CONVERSATION_PATH = "conversation.json"
        const val CHAT_EXPORT_MEDIA_ROOT = "media"
    }
}

/** manifest 媒体条目清单（供导入媒体 sink 校验用；与 `manifest.files` 过滤 media/ 前缀等价）。 */
internal fun BackupManifestWire.mediaFileEntries(): List<BackupFileEntryWire> =
    files.filter { it.path.startsWith("${ChatExportReader.CHAT_EXPORT_MEDIA_ROOT}/") }
