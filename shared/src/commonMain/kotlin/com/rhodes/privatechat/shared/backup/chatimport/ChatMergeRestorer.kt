package com.rhodes.privatechat.shared.backup.chatimport

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.data.BackupChatDisplayEvent
import com.rhodes.privatechat.shared.domain.data.MessageIdAllocator
import com.rhodes.privatechat.shared.domain.data.MessageMaxIdSource
import com.rhodes.privatechat.shared.model.ChatArchive
import com.rhodes.privatechat.shared.model.ChatHistorySegment
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.MessageExport
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.SessionExport
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 聊天导入阶段（规格卡契约 B21）。
 */
enum class ChatImportStage { VALIDATING, STAGING_MEDIA, RESTORING_DATABASE, COMPLETED, FAILED }

data class ChatImportProgress(
    val stage: ChatImportStage,
    val detail: String,
    val completed: Int? = null,
    val total: Int? = null,
)

data class ChatImportResult(
    val success: Boolean,
    val stage: ChatImportStage,
    val sessionId: String = "",
    val importedMessages: Int = 0,
    val dedupedMessages: Int = 0,
    val importedMedia: Int = 0,
    val importedArchives: Int = 0,
    val importedHistorySegments: Int = 0,
    val reason: String = "",
    val alerts: List<String> = emptyList(),
)

/**
 * 单会话聊天合并导入（规格卡契约 B12-B22；T6 裁决格式）。
 *
 * 与 T10 全量恢复（删+插）不同，本类实现**合并**语义（无 Android oracle，T6 §2.2 唯一从零设计的部分）：
 *  1. 媒体先落运行时媒体库（T17 [com.rhodes.privatechat.shared.domain.media.MediaStore]），引用重写为运行时媒体 ID；
 *  2. 会话匹配：`session.id` 精确 → 私聊确定性 `session_<operatorId>`（SessionRepository.kt:42-43）→ 新建；
 *  3. 幂等去重：`sha256(sessionId,timestamp,senderId,type,isMe,content)`（T6 开放问题 2 的收敛键）；
 *  4. 非重复消息经 T17 [MessageIdAllocator] 重分配 id（三源 max 自愈，oracle MessageRepository.kt:296-305），
 *     displayEvents / 存档 / 历史片段按 old→new 重映射；
 *  5. 全部写入包在单事务（生产 = `ChatRepository.runRestoreTransaction`，`:55`），提交前自检；
 *     任一失败 → DB 回滚 + 删除本次新增媒体（三层回滚，镜像 T10 规格卡裁决 b）。
 */
class ChatMergeRestorer(
    private val repository: ChatImportRepository,
    private val mediaSink: ChatImportMediaSink,
    private val settings: SettingsRepository? = null,
    private val reader: ChatExportReader = ChatExportReader(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val importMutex = Mutex()

    suspend fun import(
        bytes: ByteArray,
        onProgress: (ChatImportProgress) -> Unit = {},
    ): ChatImportResult = importMutex.withLock {
        var manifestSessionId = ""
        try {
            // ---- VALIDATING（契约 B8-B11）----
            onProgress(ChatImportProgress(ChatImportStage.VALIDATING, "正在校验聊天包"))
            val archive = reader.read(bytes)
            val conversation = archive.conversation
            val exportSession = conversation.session ?: throw BackupFormatException("聊天包缺少会话信息")
            manifestSessionId = exportSession.id

            // ---- 媒体落库（契约 B18）----
            onProgress(ChatImportProgress(ChatImportStage.STAGING_MEDIA, "正在准备图片文件"))
            val mediaMapping: Map<String, String> = if (archive.mediaBytes.isEmpty()) {
                emptyMap()
            } else {
                mediaSink.stage(conversation.mediaIndex, archive.mediaBytes)
            }
            val rewritten = rewriteMediaReferences(conversation, mediaMapping)

            // ---- 合并计划（事务外读）----
            val targetSession = resolveTargetSession(exportSession)
            val sessionId = targetSession?.id ?: exportSession.id
            val existingMessages = targetSession?.let { repository.getMessages(it.id) }.orEmpty()
            val existingByKey = existingMessages.associateBy { dedupKeyOf(it) }
            val sortedMessages = rewritten.messages.sortedWith(compareBy({ it.timestamp }, { it.id }))
            val cachedMax = repository.maxMessageId()
            // 分配器 DB 分量使用事务外快照（事务内会再次断言无并发写入，见下）
            val allocator = MessageIdAllocator(MessageMaxIdSource { cachedMax }, settings)
            val idMap = linkedMapOf<Long, Long>()
            val toInsert = mutableListOf<Pair<MessageExport, Long>>()
            var deduped = 0
            for (message in sortedMessages) {
                val key = dedupKey(
                    sessionId = sessionId, timestamp = message.timestamp, senderId = message.senderId,
                    type = message.type, isMe = message.isMe, content = message.content,
                )
                val existing = existingByKey[key]
                if (existing != null) {
                    idMap[message.id] = existing.id
                    deduped++
                } else {
                    val newId = allocator.allocate()
                    idMap[message.id] = newId
                    toInsert += message to newId
                }
            }

            // ---- 存档/历史片段重映射（契约 B16）----
            val archiveIdsInUse = targetSession?.let { repository.getArchiveIds(it.id) }?.toMutableSet() ?: mutableSetOf()
            val plannedArchives = rewritten.chatArchives.map { archiveItem ->
                val uniqueId = uniqueStableId(archiveItem.id, archiveIdsInUse, exportSession)
                archiveItem.copy(
                    id = uniqueId,
                    sessionId = sessionId,
                    operatorId = exportSession.operatorId,
                    messagesJson = remapMessageIds(archiveItem.messagesJson, idMap),
                    stateJson = remapMessageIds(archiveItem.stateJson, idMap),
                )
            }
            val segmentIdsInUse = targetSession?.let { repository.getHistorySegmentIds(it.id) }?.toMutableSet() ?: mutableSetOf()
            val plannedHistory = rewritten.chatHistorySegments.map { segment ->
                segment.copy(
                    id = uniqueStableId(segment.id, segmentIdsInUse, exportSession),
                    sessionId = sessionId,
                    messagesJson = remapMessageIds(segment.messagesJson, idMap),
                )
            }

            // ---- 角色补齐（契约 B13）----
            val existingOperator = repository.getOperator(exportSession.operatorId)
            val operatorToInsert: Operator? = if (existingOperator == null) {
                rewritten.operator ?: throw BackupFormatException("聊天包缺少角色信息，且当前没有该角色")
            } else null

            // ---- 会话合并（契约 B12）----
            val sessionToWrite = mergeSession(targetSession, exportSession, sessionId)

            // ---- 单事务写入 + 自检（契约 B20）----
            onProgress(
                ChatImportProgress(
                    ChatImportStage.RESTORING_DATABASE, "正在导入聊天记录",
                    0, toInsert.size + plannedArchives.size + plannedHistory.size,
                ),
            )
            val firstNewId = toInsert.firstOrNull()?.second
            repository.runImportTransaction {
                if (firstNewId != null) {
                    val dbMax = chatMessagesQueries.getMaxId().executeAsOne().MAX
                    // 事务内断言：并发写入者没有抢走已分配的 id（否则重试）。
                    if (dbMax != null && dbMax >= firstNewId) {
                        throw BackupFormatException("消息 ID 分配冲突，请重试")
                    }
                }
                operatorToInsert?.let { op ->
                    operatorsQueries.insertAllOperators(
                        op.id, op.name, op.title, op.description, op.gender, op.avatarUri, op.location,
                        op.activity, op.emotion, op.intimacy.toLong(), op.privatePrompt, op.groupPrompt,
                        op.memoryInjection, op.userRelation, op.lmb.toLong(), op.attack.toDouble(),
                        op.defense.toDouble(), op.meldPref, op.activityLevel.toDouble(), op.voiceName,
                        op.voiceSpeed, op.voicePitch,
                    )
                }
                chatSessionsQueries.insertSession(
                    sessionToWrite.id, sessionToWrite.operatorId, sessionToWrite.operatorName,
                    sessionToWrite.lastMessage, sessionToWrite.lastTime, sessionToWrite.mode,
                    if (sessionToWrite.isPinned) 1L else 0L, sessionToWrite.unreadCount.toLong(),
                    sessionToWrite.members, sessionToWrite.rules, sessionToWrite.avatarUri,
                    sessionToWrite.mutedMembers,
                )
                toInsert.forEach { (message, newId) ->
                    chatMessagesQueries.insertMessage(
                        newId, sessionId, message.senderId, message.senderName, message.content,
                        message.type, message.mode, message.emotion, message.activity, message.location,
                        message.narration, message.segmentGroup, message.intimacyChange.toLong(),
                        message.timestamp, if (message.isMe) 1L else 0L,
                    )
                }
                rewritten.displayEvents.forEach { event ->
                    val mappedId = idMap[event.messageId] ?: return@forEach
                    chatDisplayEventsQueries.insertDisplayEventIfAbsent(
                        mappedId, event.segmentIndex.toLong(), sessionId, revealOrderOf(event),
                    )
                }
                plannedArchives.forEach { archiveItem ->
                    chatArchivesQueries.insertArchive(
                        archiveItem.id, archiveItem.sessionId, archiveItem.operatorId, archiveItem.title,
                        archiveItem.note, archiveItem.mode, archiveItem.messagesJson, archiveItem.summary,
                        archiveItem.stateJson, archiveItem.status, archiveItem.createdAt, archiveItem.updatedAt,
                    )
                }
                plannedHistory.forEach { segment ->
                    chatArchivesQueries.insertHistorySegment(
                        segment.id, segment.sessionId, segment.title, segment.reason,
                        segment.messagesJson, segment.createdAt,
                    )
                }
                // 自检（事务内；失败即回滚）
                val actualCount = chatMessagesQueries.getMessages(sessionId) { id, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> id }
                    .executeAsList().size
                if (actualCount != existingMessages.size + toInsert.size) {
                    throw BackupFormatException("聊天导入自检失败：消息数量不一致")
                }
            }

            onProgress(ChatImportProgress(ChatImportStage.COMPLETED, "聊天记录导入完成"))
            ChatImportResult(
                success = true,
                stage = ChatImportStage.COMPLETED,
                sessionId = sessionId,
                importedMessages = toInsert.size,
                dedupedMessages = deduped,
                importedMedia = mediaMapping.size,
                importedArchives = plannedArchives.size,
                importedHistorySegments = plannedHistory.size,
                alerts = archive.alerts,
            )
        } catch (cancelled: CancellationException) {
            runCatching { mediaSink.cleanup() }
            throw cancelled
        } catch (error: Exception) {
            runCatching { mediaSink.cleanup() }
            onProgress(ChatImportProgress(ChatImportStage.FAILED, error.message ?: "聊天导入失败"))
            ChatImportResult(
                success = false,
                stage = ChatImportStage.FAILED,
                sessionId = manifestSessionId,
                reason = error.message ?: "聊天导入失败",
            )
        }
    }

    // ---- 会话匹配/合并（契约 B12） ----

    private suspend fun resolveTargetSession(exportSession: SessionExport): ChatSession? {
        repository.getSession(exportSession.id)?.let { return it }
        if (!exportSession.operatorId.startsWith("group_")) {
            val canonicalId = "session_${exportSession.operatorId}"
            repository.getSession(canonicalId)?.let { return it }
            repository.getSessionByOperator(exportSession.operatorId)?.let { return it }
        }
        return null
    }

    private fun mergeSession(target: ChatSession?, export: SessionExport, sessionId: String): ChatSession {
        if (target == null) {
            return ChatSession(
                id = sessionId,
                operatorId = export.operatorId,
                operatorName = export.operatorName,
                lastMessage = export.lastMessage,
                lastTime = export.lastTime,
                mode = export.mode,
                isPinned = export.isPinned,
                unreadCount = 0,
                members = export.members,
                rules = export.rules,
                avatarUri = export.avatarUri,
                mutedMembers = export.mutedMembers,
            )
        }
        // 合并：保留现有会话的置顶/未读/成员/规则/头像；lastMessage/lastTime 仅在导出更新时覆盖
        // （镜像 ChatSessions.sq:45 updateLastMessageIfNotNewer 语义）。
        val newer = export.lastTime > target.lastTime
        return target.copy(
            lastMessage = if (newer) export.lastMessage else target.lastMessage,
            lastTime = if (newer) export.lastTime else target.lastTime,
            avatarUri = target.avatarUri.ifBlank { export.avatarUri },
        )
    }

    // ---- 媒体引用重写（契约 B19）----

    private fun rewriteMediaReferences(
        conversation: ChatExportConversationWire,
        mediaMapping: Map<String, String>,
    ): ChatExportConversationWire {
        if (mediaMapping.isEmpty()) return conversation
        val byRef = linkedMapOf<String, String>()
        conversation.mediaIndex.forEach { item ->
            mediaMapping[item.mediaId]?.let { newRef ->
                if (item.originalUri.isNotBlank()) byRef[item.originalUri] = newRef
                byRef[item.mediaId] = newRef
            }
        }
        fun ref(value: String): String = if (value.isBlank()) value else byRef[value] ?: value
        fun content(value: String): String = byRef.entries.fold(value) { acc, entry ->
            if (entry.key.isBlank()) acc else acc.replace(entry.key, entry.value)
        }
        return conversation.copy(
            operator = conversation.operator?.copy(avatarUri = ref(conversation.operator.avatarUri)),
            session = conversation.session?.copy(avatarUri = ref(conversation.session.avatarUri)),
            messages = conversation.messages.map { it.copy(content = content(it.content)) },
            chatArchives = conversation.chatArchives.map {
                it.copy(messagesJson = content(it.messagesJson), stateJson = content(it.stateJson))
            },
            chatHistorySegments = conversation.chatHistorySegments.map {
                it.copy(messagesJson = content(it.messagesJson))
            },
        )
    }

    // ---- 幂等去重键（契约 B17）----

    private fun dedupKeyOf(message: ChatMessage): String = dedupKey(
        sessionId = message.sessionId, timestamp = message.timestamp, senderId = message.senderId,
        type = message.type, isMe = message.isMe, content = message.content,
    )

    private fun dedupKey(
        sessionId: String,
        timestamp: Long,
        senderId: String,
        type: String,
        isMe: Boolean,
        content: String,
    ): String = BackupCrypto.sha256Hex(
        "$sessionId\u0000$timestamp\u0000$senderId\u0000$type\u0000$isMe\u0000$content".encodeToByteArray(),
    )

    // ---- 引用重映射（契约 B16）----

    private fun remapMessageIds(raw: String, idMap: Map<Long, Long>): String {
        if (raw.isBlank() || idMap.isEmpty()) return raw
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return raw
        return json.encodeToString(JsonElement.serializer(), remapElement(element, idMap))
    }

    private fun remapElement(node: JsonElement, idMap: Map<Long, Long>): JsonElement = when (node) {
        is JsonObject -> JsonObject(node.mapValues { (key, value) ->
            if (key == "id" || key == "messageId") {
                val number = (value as? JsonPrimitive)?.content?.toLongOrNull()
                val mapped = number?.let { idMap[it] }
                if (mapped != null) JsonPrimitive(mapped) else remapElement(value, idMap)
            } else {
                remapElement(value, idMap)
            }
        })
        is JsonArray -> JsonArray(node.map { remapElement(it, idMap) })
        else -> node
    }

    private fun uniqueStableId(candidate: String, inUse: MutableSet<String>, export: SessionExport): String {
        val base = candidate.ifBlank { "imported_${export.operatorId}" }
        if (base !in inUse) {
            inUse += base
            return base
        }
        var suffix = 2
        while ("${base}_imported_$suffix" in inUse) suffix++
        val unique = "${base}_imported_$suffix"
        inUse += unique
        return unique
    }

    private fun revealOrderOf(event: BackupChatDisplayEvent): Long =
        if (event.revealOrder > 0L) event.revealOrder else 1L
}
