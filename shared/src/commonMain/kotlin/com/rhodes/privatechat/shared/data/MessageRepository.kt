package com.rhodes.privatechat.shared.data

import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.db.DatabaseDispatcher
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.data.DatabaseMessageMaxIdSource
import com.rhodes.privatechat.shared.domain.data.MessageIdAllocator
import com.rhodes.privatechat.shared.model.*
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

data class ChatDisplayEvent(
    val messageId: Long,
    val segmentIndex: Int,
    val revealOrder: Long,
)

/**
 * 2000-01-01. Messages below this are not old data but invalid data (the schema default is 0 and the
 * legacy repair can write 1970-era values), so retention deletion must never treat them as expired.
 */
private const val SANE_TIMESTAMP_FLOOR_MS = 946_684_800_000L

@Serializable
data class BackupChatDisplayEvent(
    val messageId: Long,
    val segmentIndex: Int,
    val sessionId: String,
    val revealOrder: Long,
)

class MessageRepository(
    private val wrapper: DatabaseWrapper,
    private val settings: SettingsRepository? = null,
) {

    private val db: RhodesDatabase get() = wrapper.database
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idMutex = Mutex()

    /**
     * 消息 id 单一来源（T27 接线批 B）：公式本体为 [MessageIdAllocator]（oracle :296-305 逐行复刻），
     * 本类不再维护第二份 `nextMessageId` 公式。发送/恢复路径用 [MessageIdAllocator.observeUsed] 抬高水位，
     * 分配用 [MessageIdAllocator.allocate]；两者共享同一内存高水位与 `max_used_message_id` 持久化键。
     *
     * 懒构造的原因：`db` 是 `wrapper.database` 的 getter，立即构造会触发 DatabaseWrapper 的惰性开库
     * （Android 侧 `prepareBeforeOpen` + 驱动创建），把开库从 Application 的 IO warmup 挪到主线程。
     * 保持 lazy 后首次分配仍在既有 IO 车道初始化，启动路径与改造前一致。
     */
    private val messageIdAllocator by lazy { MessageIdAllocator(DatabaseMessageMaxIdSource(db), settings) }

    /**
     * 接线缝（T28 批 C 步骤 0，lead 裁决《impl-wiring-b.md》§7.2）：暴露本仓库持有的**唯一**
     * [MessageIdAllocator] 实例，供 [com.rhodes.privatechat.shared.domain.chat.ChatEngine] /
     * GroupChatEngine 复用。**严禁在别处新建第二个 allocator**：两个实例各有独立 Mutex 与内存高水位，
     * 并发分配会撞 id（见《impl-wiring-b.md》§3.1 B4）。
     *
     * 生命周期注意：本实例沿用 `lazy` 语义，首次取用才会触发 `wrapper.database` 惰性开库。
     * 宿主接线层（ChatEngineFactory）必须惰性持有/在 IO 车道首次取用，不得在 Application 启动主线程直接读取。
     */
    val allocator: MessageIdAllocator get() = messageIdAllocator

    suspend fun getMessageById(id: Long): ChatMessage? = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getMessageById(id) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsOneOrNull()
    }

    suspend fun countMessages(sessionId: String): Long = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.countMessagesBySession(sessionId).executeAsOne()
    }

    suspend fun getMessagesAfterCursor(sessionId: String, cursor: Long, restartAt: Long, limit: Long): List<ChatMessage> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getMessagesAfterCursor(sessionId, cursor, restartAt, limit) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()
    }

    /** Replaced replies are committed only if the source still exists and has not changed. */
    suspend fun replaceReplyIfUnchanged(original: ChatMessage, replacement: String): Boolean = withContext(DatabaseDispatcher.dispatcher) {
        db.transactionWithResult {
            val current = db.chatMessagesQueries.getMessageById(original.id) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsOneOrNull()
            if (current?.sessionId != original.sessionId || current.content != original.content) return@transactionWithResult false
            db.chatMessagesQueries.replaceMessageContentIfUnchanged(replacement, original.id, original.content)
            db.chatSessionsQueries.updateLastMessageIfNotNewer(previewFromAiJson(replacement), original.timestamp, original.sessionId, original.timestamp)
            db.chatMessagesQueries.getMessageById(original.id) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsOneOrNull()?.content == replacement
        }
    }

    // --- Messages ---
    fun getMessages(sessionId: String): Flow<List<ChatMessage>> =
        db.chatMessagesQueries.getMessages(sessionId) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.asFlow().mapToList(DatabaseDispatcher.dispatcher)

    fun getRecentMessages(sessionId: String, limit: Long): Flow<List<ChatMessage>> =
        db.chatMessagesQueries.getRecentMessages(sessionId, limit) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.asFlow().mapToList(DatabaseDispatcher.dispatcher)

    suspend fun getMessagesSync(sessionId: String): List<ChatMessage> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getMessagesSync(sessionId) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()
    }

    suspend fun getAllMessagesForBackup(): List<ChatMessage> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getAllMessagesForBackup { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()
    }

    suspend fun getRecentMessagesSync(sessionId: String, limit: Long): List<ChatMessage> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getRecentMessages(sessionId, limit) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()
    }

    suspend fun getMessagesAfterPosition(sessionId: String, timestamp: Long, id: Long, limit: Long): List<ChatMessage> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getMessagesAfterPosition(sessionId, timestamp, timestamp, id, limit) { mid, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, time, isMe ->
            ChatMessage(mid, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), time, isMe != 0L)
        }.executeAsList()
    }

    suspend fun getMessagesBefore(sessionId: String, beforeTimestamp: Long, beforeId: Long, limit: Long): List<ChatMessage> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getMessagesBefore(sessionId, beforeTimestamp, beforeTimestamp, beforeId, limit) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()
    }

    suspend fun updateMessageContent(id: Long, content: String) = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.updateContent(content, id)
    }

    suspend fun updateMessageType(id: Long, type: String) = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.updateType(type, id)
    }

    suspend fun updateMessageContentAndPreview(sessionId: String, id: Long, content: String, timestamp: Long) = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.updateContent(content, id)
        // Regenerating an older reply must not replace a newer chat-list preview or move the session backward.
        db.chatSessionsQueries.updateLastMessageIfNotNewer(previewFromAiJson(content), timestamp, sessionId, timestamp)
    }

    suspend fun getDisplayEvents(sessionId: String): List<ChatDisplayEvent> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatDisplayEventsQueries.getDisplayEvents(sessionId) { messageId, segmentIndex, revealOrder ->
            ChatDisplayEvent(messageId, segmentIndex.toInt(), revealOrder)
        }.executeAsList()
    }

    suspend fun getAllDisplayEvents(): List<BackupChatDisplayEvent> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatDisplayEventsQueries.getAllDisplayEvents { messageId, segmentIndex, sessionId, revealOrder ->
            BackupChatDisplayEvent(messageId, segmentIndex.toInt(), sessionId, revealOrder)
        }.executeAsList()
    }

    suspend fun addDisplayEventIfAbsent(sessionId: String, messageId: Long, segmentIndex: Int, revealedAt: Long = 0L): Long = idMutex.withLock {
        withContext(DatabaseDispatcher.dispatcher) {
            db.chatDisplayEventsQueries.getDisplayEventOrder(messageId, segmentIndex.toLong()).executeAsOneOrNull()?.let { return@withContext it }
            // Timestamp-based orders allow later-revealed bubbles to follow intervening user
            // messages. Existing small sequence orders remain valid legacy display events.
            val nextOrder = revealedAt.takeIf { it >= 1_000_000_000_000L }
                ?: Clock.System.now().toEpochMilliseconds()
            db.chatDisplayEventsQueries.insertDisplayEventIfAbsent(messageId, segmentIndex.toLong(), sessionId, nextOrder)
            db.chatDisplayEventsQueries.getDisplayEventOrder(messageId, segmentIndex.toLong()).executeAsOneOrNull() ?: nextOrder
        }
    }

    suspend fun deleteMessageDisplayEvents(messageId: Long) = withContext(DatabaseDispatcher.dispatcher) {
        db.chatDisplayEventsQueries.deleteMessageDisplayEvents(messageId)
    }

    suspend fun deleteDisplayEvent(messageId: Long, segmentIndex: Int) = withContext(DatabaseDispatcher.dispatcher) {
        db.chatDisplayEventsQueries.deleteDisplayEvent(messageId, segmentIndex.toLong())
    }

    suspend fun sendMessage(sessionId: String, message: ChatMessage) = idMutex.withLock { withContext(DatabaseDispatcher.dispatcher) {
        messageIdAllocator.observeUsed(message.id)
        val ts = if (message.timestamp > 0) message.timestamp else Clock.System.now().toEpochMilliseconds()
        db.chatMessagesQueries.insertMessage(
            message.id, sessionId, message.senderId, message.senderName, message.content,
            message.type, message.mode, message.emotion, message.activity, message.location,
            message.narration, message.segmentGroup, message.intimacyChange.toLong(), ts,
            if (message.isMe) 1L else 0L
        )
        // System markers, failures and transient placeholders should not replace a real chat preview.
        if (message.type != "system" && !message.content.startsWith("正在重新生成") && !message.content.startsWith("上下文超限")) {
            val preview = when (message.type) {
                "ai_json" -> previewFromAiJson(message.content)
                "image" -> "[图片]"
                "gift_hidden", "gift_reply_failed" -> previewFromGiftPayload(message.content)
                else -> message.content.take(50)
            }
            // A delayed reply or retry must never move a newer home preview backwards.
            db.chatSessionsQueries.updateLastMessageIfNotNewer(preview, ts, sessionId, ts)
        }
    } }

    /** Atomically makes a user message visible and records its durable reply recovery turn. */
    suspend fun sendMessageAndCreateReplyTurn(sessionId: String, message: ChatMessage, turn: ReplyTurn) = idMutex.withLock {
        // 水位抬高移到事务外：oracle 的 Kotlin 赋值本就不参与 SQL 回滚，事务失败时抬水位只会多跳号，
        // 不会造成 id 复用；放在 execute 之前保持与原「事务首句赋值」同序可见。
        messageIdAllocator.observeUsed(message.id)
        DatabaseDispatcher.execute("message_write_user") {
            val ts = if (message.timestamp > 0) message.timestamp else Clock.System.now().toEpochMilliseconds()
            db.transaction {
                db.chatMessagesQueries.insertMessage(
                    message.id, sessionId, message.senderId, message.senderName, message.content,
                    message.type, message.mode, message.emotion, message.activity, message.location,
                    message.narration, message.segmentGroup, message.intimacyChange.toLong(), ts,
                    if (message.isMe) 1L else 0L,
                )
                if (message.type != "system" && !message.content.startsWith("正在重新生成") && !message.content.startsWith("上下文超限")) {
                    val preview = when (message.type) {
                        "ai_json" -> previewFromAiJson(message.content)
                        "image" -> "[图片]"
                        "gift_hidden", "gift_reply_failed" -> previewFromGiftPayload(message.content)
                        else -> message.content.take(50)
                    }
                    db.chatSessionsQueries.updateLastMessageIfNotNewer(preview, ts, sessionId, ts)
                }
                db.replyTurnsQueries.insertReplyTurnIfAbsent(
                    turn.id, turn.sessionId, turn.surface, turn.triggerKind, turn.sourceMessageId,
                    turn.autoPlanToken, turn.mode, turn.nextAttemptAt, turn.createdAt, turn.updatedAt,
                )
            }
        }
    }

    /** A reply row is committed only together with the token-fenced turn completion. */
    suspend fun sendReplyAndCompleteTurn(sessionId: String, message: ChatMessage, turnId: String, leaseToken: String, now: Long): Boolean = idMutex.withLock {
        val completed = DatabaseDispatcher.execute("message_write_ai_reply") {
            var completed = false
            db.transaction {
                if (!db.replyTurnsQueries.isReplyTurnOwned(turnId, leaseToken).executeAsOne()) return@transaction
                val ts = if (message.timestamp > 0) message.timestamp else Clock.System.now().toEpochMilliseconds()
                db.chatMessagesQueries.insertMessage(
                    message.id, sessionId, message.senderId, message.senderName, message.content,
                    message.type, message.mode, message.emotion, message.activity, message.location,
                    message.narration, message.segmentGroup, message.intimacyChange.toLong(), ts,
                    if (message.isMe) 1L else 0L,
                )
                val preview = if (message.type == "ai_json") previewFromAiJson(message.content) else message.content.take(50)
                db.chatSessionsQueries.updateLastMessageIfNotNewer(preview, ts, sessionId, ts)
                db.replyTurnsQueries.completeReplyTurn(now, now, turnId, leaseToken)
                completed = true
            }
            completed
        }
        // 仅在「本 token 成功提交回复行」后抬水位，与 oracle 事务内赋值的位置语义一致（未持有 token 时不动）。
        if (completed) messageIdAllocator.observeUsed(message.id)
        completed
    }

    /** Restores backups without allowing an old row ID to overwrite newer local content. */
    suspend fun restoreMessage(message: ChatMessage) = idMutex.withLock { withContext(DatabaseDispatcher.dispatcher) {
        // 恢复路径 = oracle :243-244 的「抬内存水位 + 持久化」，allocator.observeUsed 同时覆盖两者。
        messageIdAllocator.observeUsed(message.id)
        val ts = if (message.timestamp > 0) message.timestamp else Clock.System.now().toEpochMilliseconds()
        db.chatMessagesQueries.insertMessageIfAbsent(
            message.id, message.sessionId, message.senderId, message.senderName, message.content,
            message.type, message.mode, message.emotion, message.activity, message.location,
            message.narration, message.segmentGroup, message.intimacyChange.toLong(), ts,
            if (message.isMe) 1L else 0L
        )
    } }

    /** Repairs previews written by older builds that could not parse otherwise valid AI payloads. */
    suspend fun repairAiSessionPreviews() = withContext(DatabaseDispatcher.dispatcher) {
        val sessions = db.chatSessionsQueries.getAllSessions { id, operatorId, operatorName, lastMessage, lastTime, mode, isPinned, unreadCount, members, rules, avatarUri, mutedMembers ->
            ChatSession(id, operatorId, operatorName, lastMessage, lastTime, mode, isPinned != 0L, unreadCount.toInt(), members, rules, avatarUri, mutedMembers)
        }.executeAsList()
        sessions.forEach { session ->
            val latest = db.chatMessagesQueries.getRecentMessages(session.id, 1) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
                ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
            }.executeAsOneOrNull()
            if (latest == null || latest.timestamp <= 0L) return@forEach
            val preview = when (latest.type) {
                "ai_json" -> AiReplyPreview.extract(latest.content)
                "image" -> "[图片]"
                "gift_hidden", "gift_reply_failed" -> previewFromGiftPayload(latest.content)
                "system" -> null
                else -> latest.content.take(50)
            } ?: return@forEach
            db.chatSessionsQueries.updateLastMessageIfNotNewer(preview, latest.timestamp, session.id, latest.timestamp)
        }
    }

    private fun previewFromAiJson(content: String): String {
        return AiReplyPreview.extract(content) ?: "AI 回复"
    }

    private fun previewFromGiftPayload(content: String): String = runCatching {
        val root = json.parseToJsonElement(content) as? kotlinx.serialization.json.JsonObject
        val giftName = (root?.get("giftName") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (giftName.isBlank()) "[送出了礼物]" else "[送出了礼物：${giftName.take(30)}]"
    }.getOrDefault("[送出了礼物]")

    /**
     * 消息 id 单一来源：委托 [MessageIdAllocator.allocate]（oracle :296-305 逐行复刻：三源 max 自愈
     * + 内存高水位 + `max_used_message_id` 持久化）。`idMutex` 保留，维持与发送/恢复路径的串行顺序；
     * `withContext(DatabaseDispatcher.dispatcher)` 保持原 `DatabaseDispatcher.execute` 的单车道线程模型。
     */
    suspend fun getNextMessageId(): Long = idMutex.withLock {
        withContext(DatabaseDispatcher.dispatcher) { messageIdAllocator.allocate() }
    }

    suspend fun deleteSessionMessages(sessionId: String) = withContext(DatabaseDispatcher.dispatcher) {
        db.chatDisplayEventsQueries.deleteSessionDisplayEvents(sessionId)
        db.chatMessagesQueries.deleteSessionMessages(sessionId)
        // Turns without their source messages are orphans and can block a reused message id later.
        db.replyTurnsQueries.deleteReplyTurnsBySession(sessionId)
    }

    suspend fun clearSessionPreview(sessionId: String, timestamp: Long = Clock.System.now().toEpochMilliseconds()) = withContext(DatabaseDispatcher.dispatcher) {
        db.chatSessionsQueries.updateLastMessage("", timestamp, sessionId)
    }

    suspend fun deleteMessage(id: Long) = withContext(DatabaseDispatcher.dispatcher) {
        db.transaction {
            db.chatDisplayEventsQueries.deleteMessageDisplayEvents(id)
            db.replyTurnsQueries.deleteReplyTurnsBySourceMessageId(id)
            db.chatMessagesQueries.deleteMessage(id)
        }
    }
    suspend fun deleteOldMessages(cutoff: Long) = withContext(DatabaseDispatcher.dispatcher) {
        db.chatDisplayEventsQueries.deleteOldDisplayEvents(cutoff)
        db.chatMessagesQueries.deleteOldMessages(cutoff, SANE_TIMESTAMP_FLOOR_MS)
    }
    suspend fun getMessageCount(): Int = withContext(DatabaseDispatcher.dispatcher) { db.chatMessagesQueries.getMessageCount().executeAsOne().toInt() }

    suspend fun getMessageCountPerSender(): List<SenderCount> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getMessageCountPerSender().executeAsList().map { SenderCount(it.senderName, it.cnt) }
    }

    suspend fun getMessageCountPerSenderSince(since: Long): List<SenderCount> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getMessageCountPerSenderSince(since).executeAsList().map { SenderCount(it.senderName, it.cnt) }
    }

    suspend fun getMessagesInRange(start: Long, end: Long): List<ChatMessage> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getMessagesInRange(start, end) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()
    }

    suspend fun searchMessagesInSession(sessionId: String, keyword: String, limit: Long = 200): List<ChatMessage> = withContext(DatabaseDispatcher.dispatcher) {
        val like = "%${keyword.replace("%", "\\%").replace("_", "\\_")}%"
        db.chatMessagesQueries.searchMessagesInSession(sessionId, like, limit) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()
    }

    suspend fun getMessagesBySessionInRange(sessionId: String, start: Long, end: Long): List<ChatMessage> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getMessagesBySessionInRange(sessionId, start, end) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()
    }

    suspend fun getMessageDatesBySession(sessionId: String): List<String> = withContext(DatabaseDispatcher.dispatcher) {
        db.chatMessagesQueries.getMessageDatesBySession(sessionId).executeAsList().mapNotNull { it as? String }
    }
}
