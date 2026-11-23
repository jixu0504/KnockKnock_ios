package com.rhodes.privatechat.shared.backup

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.Operator

/**
 * 恢复执行器的最小移植（oracle：app/.../data/backup/RestoreDatabaseExecutor.kt:30-105）。
 *
 * Phase 0 最小闭环 = roles + chats 类别（规格卡「数据与迁移」）：
 *  - 单事务内「子先于父」删除：reply_turns → chat_display_events → chat_archives(+history_segments)
 *    → chat_messages → chat_sessions(+groups)；roles+chats 同时选中才删 relationships → operators（:47-55,71-74）；
 *  - 插入顺序：operators → relationships → sessions → messages → displayEvents（:77-104）；
 *  - 任何一步抛错 → 事务整体回滚，DB 无残留。
 *
 * 事务入口以 lambda 注入：生产 = ChatRepository.runRestoreTransaction（ChatRepository.kt:55），
 * 测试 = RhodesDatabase.transaction（JVM 内存驱动 / NativeSqliteDriver），两者语义一致（SQLDelight 单事务）。
 */
class BackupRestoreExecutor(
    private val runTransaction: suspend (RhodesDatabase.() -> Unit) -> Unit,
) {
    constructor(repository: ChatRepository) : this(
        runTransaction = { block -> repository.runRestoreTransaction(block) },
    )

    suspend fun restore(
        payload: BackupPayloadWire,
        requestedSelection: BackupContentSelection = BackupContentSelection.All,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val selection = requestedSelection.normalized()
        val total = payload.content.operators.orEmpty().size + payload.content.sessions.orEmpty().size +
            payload.content.messages.orEmpty().size + payload.displayEvents.size
        var completed = 0
        fun report(added: Int) {
            completed += added
            onProgress(completed, total)
        }
        runTransaction {
            // 删除：子先于父（oracle :47-55；chats 类别）。
            if (selection.chats) {
                replyTurnsQueries.deleteAllReplyTurns()
                chatDisplayEventsQueries.deleteAllDisplayEvents()
                chatArchivesQueries.deleteAllArchives()
                chatArchivesQueries.deleteAllHistorySegments()
                chatMessagesQueries.deleteAllMessages()
                chatSessionsQueries.deleteAllSessions()
                chatSessionsQueries.deleteAllGroups()
            }
            // 角色仅在 roles+chats 都选中时替换（oracle :69-74）。
            if (selection.roles && selection.chats) {
                relationshipsQueries.deleteAllRelationships()
                operatorsQueries.deleteAllOperators()
            }

            // 插入（oracle :77-104）。
            payload.content.operators.orEmpty().forEach { op -> insertOperator(op) }
            payload.content.relationships.orEmpty().forEach { rel ->
                relationshipsQueries.insertAllRelationships(
                    rel.operatorId, rel.relatedOperatorId, rel.relatedOperatorName, rel.type,
                    rel.intimacy.toLong(), if (rel.isPreset) 1 else 0, rel.note,
                )
            }
            payload.content.sessions.orEmpty().forEach { session ->
                chatSessionsQueries.insertSession(
                    session.id, session.operatorId, session.operatorName, session.lastMessage, session.lastTime,
                    session.mode, if (session.isPinned) 1 else 0, session.unreadCount.toLong(), session.members,
                    session.rules, session.avatarUri, session.mutedMembers,
                )
            }
            payload.content.messages.orEmpty().forEach { message ->
                chatMessagesQueries.insertAllMessages(
                    message.id, message.sessionId, message.senderId, message.senderName, message.content,
                    message.type, message.mode, message.emotion, message.activity, message.location,
                    message.narration, message.segmentGroup, message.intimacyChange.toLong(), message.timestamp,
                    if (message.isMe) 1 else 0,
                )
            }
            payload.displayEvents.forEach { event ->
                chatDisplayEventsQueries.insertDisplayEventIfAbsent(
                    event.messageId, event.segmentIndex.toLong(), event.sessionId, event.revealOrder,
                )
            }
            report(payload.content.operators.orEmpty().size + payload.content.relationships.orEmpty().size)
            report(payload.content.sessions.orEmpty().size + payload.content.messages.orEmpty().size + payload.displayEvents.size)
        }
    }

    private fun RhodesDatabase.insertOperator(op: Operator) {
        operatorsQueries.insertAllOperators(
            op.id, op.name, op.title, op.description, op.gender, op.avatarUri, op.location, op.activity,
            op.emotion, op.intimacy.toLong(), op.privatePrompt, op.groupPrompt, op.memoryInjection,
            op.userRelation, op.lmb.toLong(), op.attack.toDouble(), op.defense.toDouble(), op.meldPref,
            op.activityLevel.toDouble(), op.voiceName, op.voiceSpeed, op.voicePitch,
        )
    }
}
