package com.rhodes.privatechat.shared.backup

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.Operator

/**
 * 分类选择恢复执行器（规格卡契约 D28；T30 对 T10 最小闭环的「分类备份/恢复」补齐）。
 *
 * 与 T10 [BackupRestoreExecutor] 的差异（仅此一处语义补强，T10 类保持不动）：
 *  - 删除：按 `BackupContentSelection.normalized()` 逐类目决定（与 oracle RestoreDatabaseExecutor.kt:30-74 一致）；
 *  - **插入也按选择门禁**：未选中类目既不删也不插（T10 最小闭环只做了删除门禁，插入会越界覆盖）；
 *  - roles-only（roles=true、chats=false）采用 upsert 语义，不删除现有角色（镜像 oracle `:69-74` 注释语义）。
 *
 * 类别范围：T10 最小闭环已建模的 roles/chats（operators/relationships/sessions/messages/displayEvents）；
 * memories/social/knowledgeBases/extras 的字段级重放按 lead 2026-11-23 裁决单列 T31（本卡不做）。
 */
class BackupSelectiveRestoreExecutor(
    private val runTransaction: suspend (RhodesDatabase.() -> Unit) -> Unit,
) {
    constructor(repository: ChatRepository) : this(
        runTransaction = { block -> repository.runRestoreTransaction(block) },
    )

    constructor(repository: BackupRestoreRepository) : this(
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
            // 删除：子先于父（oracle RestoreDatabaseExecutor.kt:47-55；仅选中类目）。
            if (selection.chats) {
                replyTurnsQueries.deleteAllReplyTurns()
                chatDisplayEventsQueries.deleteAllDisplayEvents()
                chatArchivesQueries.deleteAllArchives()
                chatArchivesQueries.deleteAllHistorySegments()
                chatMessagesQueries.deleteAllMessages()
                chatSessionsQueries.deleteAllSessions()
                chatSessionsQueries.deleteAllGroups()
            }
            // 角色仅在 roles+chats 都选中时整体替换；roles-only 走 upsert 保留现有角色（oracle :69-74）。
            if (selection.roles && selection.chats) {
                relationshipsQueries.deleteAllRelationships()
                operatorsQueries.deleteAllOperators()
            }

            // 插入（门禁：未选中类目不写）。
            if (selection.roles) {
                payload.content.operators.orEmpty().forEach { op -> insertOperator(op) }
                payload.content.relationships.orEmpty().forEach { rel ->
                    relationshipsQueries.insertAllRelationships(
                        rel.operatorId, rel.relatedOperatorId, rel.relatedOperatorName, rel.type,
                        rel.intimacy.toLong(), if (rel.isPreset) 1 else 0, rel.note,
                    )
                }
            }
            if (selection.chats) {
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
