package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.backup.BackupContentSelection
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.backup.BackupRestoreRepository
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.Operator

/**
 * 全类目字段级恢复执行器（T31；规格卡契约 C2/C3/C4/C5/C6/C7）。
 *
 * 严格镜像 Android oracle `RestoreDatabaseExecutor.kt:30-174`：
 *  - **删除**（子先于父，仅选中类目）：extras → memories → chats → social → knowledgeBases → roles&&chats；
 *  - **插入**（依赖拓扑）：operators → KB 三表 → relationships → sessions → messages → displayEvents →
 *    memories → anchors → moments → comments（父级拓扑循环）→ likes → backfill 计数 → diaries →
 *    gifts → dispatches → mahjong → sharedExperiences → participants → archives → historySegments →
 *    memoryItems（最后，消费 moments/comments 的新 id 映射）；
 *  - 自增表 id **不保留**（memories/anchors/moments/comments/likes/diaries/memoryItems/experiences），
 *    通过 `last_insert_rowid()` 建 old→new 映射；固定 id 表原样保留（operators/sessions/messages/
 *    KB/chunks/assignments/archives/history/gifts/dispatches/mahjong）；
 *  - 派生字段归一：KB `indexStatus="pending"`、chunk `indexedAt=0`、dispatch `active|generating→paused`、
 *    memoryItems L2/L3 `status="archived"` + `vectorId=""`、moments 计数 backfill；
 *  - **提交前自检**（D2）：插入完成后按过滤后 payload 的期望计数逐表对账 + 字段级不变量；任一失配抛
 *    [BackupFormatException] → 调用方单事务回滚（DB 零残留）。
 *
 * 插入门禁沿用 T30 裁决（未选中类目不写；Android 仅删除门禁）——平台差异白名单 P1。
 * 本类非线程安全，由 `BackupFullRestoreCoordinator` 串行调用（同 T10 restoreMutex 语义）。
 */
class BackupFullRestoreExecutor(
    private val runTransaction: suspend (RhodesDatabase.() -> Unit) -> Unit,
) {
    constructor(repository: BackupRestoreRepository) : this(
        runTransaction = { block -> repository.runRestoreTransaction(block) },
    )

    constructor(repository: ChatRepository) : this(
        runTransaction = { block -> repository.runRestoreTransaction(block) },
    )

    suspend fun restore(
        payload: FullBackupPayloadWire,
        requestedSelection: BackupContentSelection = BackupContentSelection.All,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val selection = requestedSelection.normalized()
        val expected = BackupFullCounts.expected(payload, selection)
        val total = expected.values.sum() + (payload.content.settings?.size ?: 0)
        var completed = 0
        fun report(added: Int) {
            completed += added
            onProgress(completed, total)
        }
        runTransaction {
            deleteSelected(selection)
            insertSelected(payload, selection, ::report)
            // ---- 提交前自检（D2）：单事务内对账，失配抛错 → 整体回滚 ----
            // settings 不在 SQLite 内（Preferences 由 coordinator 在事务外恢复 + 提交后自检），此处只核对 DB 表。
            val actual = BackupFullCounts.read(this)
            val mismatches = expected.mapNotNull { (key, wanted) ->
                if (key == "settings") return@mapNotNull null
                val got = actual[key] ?: 0
                if (got != wanted) "$key：期望 $wanted，实际 $got" else null
            }
            val invariants = BackupFullCounts.invariantViolations(this, selection)
            if (mismatches.isNotEmpty() || invariants.isNotEmpty()) {
                throw BackupFormatException("恢复前自检失败：" + (mismatches + invariants).joinToString("；"))
            }
        }
    }

    // ------------------------------------------------------------------ 删除（oracle :32-74）

    private fun RhodesDatabase.deleteSelected(selection: BackupContentSelection) {
        if (selection.extras) {
            dailyDeliveriesQueries.deleteAllDailyDeliveries()
            giftRecordsQueries.deleteAllGifts()
            dispatchRecordsQueries.deleteAllDispatches()
            mahjongSavesQueries.deleteSave()
        }
        if (selection.memories) {
            memoryLinksQueries.deleteAllMemoryLinks()
            memorySourceQueueQueries.deleteAllMemorySources()
            memoryBatchesQueries.deleteAllMemoryBatches()
            vectorMemoriesQueries.deleteAllVectorMemories()
            memoryItemsQueries.deleteAllMemoryItems()
            memoryAnchorsQueries.deleteAllAnchors()
            memoriesQueries.deleteAllMemories()
        }
        if (selection.chats) {
            replyTurnsQueries.deleteAllReplyTurns()
            chatDisplayEventsQueries.deleteAllDisplayEvents()
            chatArchivesQueries.deleteAllArchives()
            chatArchivesQueries.deleteAllHistorySegments()
            chatMessagesQueries.deleteAllMessages()
            chatSessionsQueries.deleteAllSessions()
            chatSessionsQueries.deleteAllGroups()
        }
        if (selection.social) {
            momentLikesQueries.deleteAllLikes()
            momentCommentsQueries.deleteAllComments()
            momentsQueries.deleteAllMoments()
            diariesQueries.deleteAllDiaries()
            sharedExperiencesQueries.deleteAllSharedExperienceParticipants()
            sharedExperiencesQueries.deleteAllSharedExperiences()
        }
        if (selection.knowledgeBases) {
            knowledgeBasesQueries.deleteAllKnowledgeBaseAssignments()
            knowledgeBasesQueries.deleteAllKnowledgeBaseChunks()
            knowledgeBasesQueries.deleteAllKnowledgeBases()
        }
        if (selection.roles && selection.chats) {
            relationshipsQueries.deleteAllRelationships()
            operatorsQueries.deleteAllOperators()
        }
    }

    // ------------------------------------------------------------------ 插入（oracle :76-174）

    private fun RhodesDatabase.insertSelected(
        payload: FullBackupPayloadWire,
        selection: BackupContentSelection,
        report: (Int) -> Unit,
    ) {
        val c = payload.content

        if (selection.roles) {
            c.operators.orEmpty().forEach { insertOperator(it) }
            report(c.operators.orEmpty().size)
        }
        if (selection.knowledgeBases) {
            c.knowledgeBases.orEmpty().forEach { book ->
                knowledgeBasesQueries.insertKnowledgeBase(
                    book.id, book.name, book.rawContent, book.sourceFileName, book.sourceFormat,
                    book.sourceType, book.chunkingMode, "pending", "", book.createdAt, book.updatedAt,
                )
            }
            c.knowledgeBaseChunks.orEmpty().forEach { chunk ->
                knowledgeBasesQueries.insertKnowledgeBaseChunk(
                    chunk.id, chunk.knowledgeBaseId, chunk.ordinal.toLong(), chunk.sourceHeading,
                    chunk.content, chunk.userKeywords, if (chunk.enabled) 1L else 0L, 0L, "",
                    chunk.createdAt, chunk.updatedAt,
                )
            }
            c.operatorKnowledgeBaseAssignments.orEmpty().forEach { assignment ->
                knowledgeBasesQueries.insertKnowledgeBaseAssignment(
                    assignment.operatorId, assignment.knowledgeBaseId,
                    if (assignment.enabled) 1L else 0L, assignment.sortOrder.toLong(),
                )
            }
            report(c.knowledgeBases.orEmpty().size + c.knowledgeBaseChunks.orEmpty().size +
                c.operatorKnowledgeBaseAssignments.orEmpty().size)
        }
        if (selection.roles) {
            c.relationships.orEmpty().forEach { rel ->
                relationshipsQueries.insertAllRelationships(
                    rel.operatorId, rel.relatedOperatorId, rel.relatedOperatorName, rel.type,
                    rel.intimacy.toLong(), if (rel.isPreset) 1L else 0L, rel.note,
                )
            }
            report(c.relationships.orEmpty().size)
        }
        if (selection.chats) {
            c.sessions.orEmpty().forEach { session ->
                chatSessionsQueries.insertSession(
                    session.id, session.operatorId, session.operatorName, session.lastMessage,
                    session.lastTime, session.mode, if (session.isPinned) 1L else 0L,
                    session.unreadCount.toLong(), session.members, session.rules,
                    session.avatarUri, session.mutedMembers,
                )
            }
            c.messages.orEmpty().forEach { message ->
                chatMessagesQueries.insertAllMessages(
                    message.id, message.sessionId, message.senderId, message.senderName,
                    message.content, message.type, message.mode, message.emotion, message.activity,
                    message.location, message.narration, message.segmentGroup,
                    message.intimacyChange.toLong(), message.timestamp,
                    if (message.isMe) 1L else 0L,
                )
            }
            payload.displayEvents.forEach { event ->
                chatDisplayEventsQueries.insertDisplayEventIfAbsent(
                    event.messageId, event.segmentIndex.toLong(), event.sessionId, event.revealOrder,
                )
            }
            report(c.sessions.orEmpty().size + c.messages.orEmpty().size + payload.displayEvents.size)
        }
        if (selection.memories) {
            c.memories.orEmpty().forEach { memory ->
                memoriesQueries.insertMemory(
                    memory.sessionId, memory.operatorId, memory.type.name, memory.content,
                    memory.keywords, memory.preferences, memory.taboos, memory.createdAt, memory.expiresAt,
                )
            }
            c.anchors.orEmpty().forEach { anchor ->
                memoryAnchorsQueries.insertAnchor(
                    anchor.sessionId, anchor.operatorId, anchor.type.name, anchor.content,
                    if (anchor.isPrivate) 1L else 0L, anchor.createdAt, anchor.expiresAt,
                    anchor.source, anchor.sourceName, anchor.sourceActor, anchor.sourceTarget,
                    anchor.importance, anchor.knownFrom,
                )
            }
            report(c.memories.orEmpty().size + c.anchors.orEmpty().size)
        }

        val momentIdMap = linkedMapOf<Long, Long>()
        val commentIdMap = linkedMapOf<Long, Long>()
        val experienceIdMap = linkedMapOf<Long, Long>()
        if (selection.social) {
            c.moments.orEmpty().forEach { moment ->
                momentsQueries.insertMoment(
                    moment.operatorId, moment.operatorName, moment.content,
                    if (moment.isUserPost) 1L else 0L, moment.mentionedOperatorIds,
                    moment.likeCount.toLong(), moment.commentCount.toLong(), moment.createdAt,
                )
                momentIdMap[moment.id] = momentsQueries.getLastInsertRowId().executeAsOne()
            }
            // 父评论拓扑排序（oracle :121-132）：parentCommentId==0 或父已插入的评论才 ready。
            val pending = c.momentComments.orEmpty().toMutableList()
            while (pending.isNotEmpty()) {
                val ready = pending.filter { it.parentCommentId == 0L || it.parentCommentId in commentIdMap }
                check(ready.isNotEmpty()) { "评论父级引用无法恢复" }
                ready.forEach { comment ->
                    val newMomentId = requireNotNull(momentIdMap[comment.momentId]) { "评论引用的动态无法恢复" }
                    val parentId = if (comment.parentCommentId == 0L) 0L else
                        requireNotNull(commentIdMap[comment.parentCommentId]) { "评论父级无法恢复" }
                    momentCommentsQueries.insertComment(
                        newMomentId, comment.operatorId, comment.operatorName, comment.content,
                        parentId, comment.replyToName, comment.createdAt, if (comment.isRead) 1L else 0L,
                    )
                    commentIdMap[comment.id] = momentCommentsQueries.getLastInsertRowId().executeAsOne()
                }
                pending.removeAll(ready.toSet())
            }
            c.momentLikes.orEmpty().forEach { like ->
                momentIdMap[like.momentId]?.let { newMomentId ->
                    momentLikesQueries.insertLike(
                        newMomentId, like.operatorId, like.operatorName, like.createdAt,
                    )
                }
            }
            momentsQueries.backfillLikeCounts()
            momentsQueries.backfillCommentCounts()
            c.diaries.orEmpty().forEach { diary ->
                diariesQueries.insertDiary(
                    diary.operatorId, diary.operatorName, diary.content, diary.date,
                    diary.version.toLong(), diary.createdAt,
                )
            }
            report(c.moments.orEmpty().size + c.momentComments.orEmpty().size +
                c.momentLikes.orEmpty().size + c.diaries.orEmpty().size)
        }
        if (selection.extras) {
            payload.giftRecords.forEach { gift ->
                giftRecordsQueries.insert(
                    gift.id, gift.operatorId, gift.imageUri, gift.giftName, gift.senderName, gift.createdAt,
                )
            }
            payload.dispatchRecords.forEach { dispatch ->
                // 恢复后不自动续跑（oracle :146-149）。
                val status = when (dispatch.status) {
                    "active", "generating" -> "paused"
                    else -> dispatch.status
                }
                dispatchRecordsQueries.insertDispatch(
                    dispatch.id, dispatch.taskType, dispatch.durationHours.toLong(),
                    dispatch.budget.toLong(), dispatch.netProfit.toLong(), dispatch.operatorIds,
                    dispatch.logChain, status, dispatch.startTime, dispatch.endTime,
                    dispatch.totalSegments.toLong(), dispatch.segmentInterval, dispatch.items,
                )
            }
            payload.mahjongSave?.let { save ->
                mahjongSavesQueries.insertSave(save.id, save.saveJson, save.ruleType, save.savedAt)
            }
            report(payload.giftRecords.size + payload.dispatchRecords.size +
                if (payload.mahjongSave != null) 1 else 0)
        }
        if (selection.social) {
            payload.sharedExperiences.forEach { experience ->
                sharedExperiencesQueries.insertSharedExperience(
                    experience.sourceKind, experience.sourceRefId, experience.groupId,
                    experience.content, experience.importance.toLong(), experience.status,
                    experience.createdAt, experience.expiresAt,
                )
                experienceIdMap[experience.id] = sharedExperiencesQueries.getLastSharedExperienceId().executeAsOne()
            }
            payload.sharedExperienceParticipants.forEach { participant ->
                experienceIdMap[participant.experienceId]?.let { newId ->
                    sharedExperiencesQueries.insertSharedExperienceParticipant(
                        newId, participant.operatorId, participant.role,
                    )
                }
            }
            report(payload.sharedExperiences.size + payload.sharedExperienceParticipants.size)
        }
        if (selection.chats) {
            payload.chatArchives.forEach { archive ->
                chatArchivesQueries.insertArchive(
                    archive.id, archive.sessionId, archive.operatorId, archive.title, archive.note,
                    archive.mode, archive.messagesJson, archive.summary, archive.stateJson,
                    archive.status, archive.createdAt, archive.updatedAt,
                )
            }
            payload.chatHistorySegments.forEach { history ->
                chatArchivesQueries.insertHistorySegment(
                    history.id, history.sessionId, history.title, history.reason,
                    history.messagesJson, history.createdAt,
                )
            }
            report(payload.chatArchives.size + payload.chatHistorySegments.size)
        }
        if (selection.memories) {
            c.memoryItems.orEmpty().forEach { item ->
                val status = if (item.memoryLevel == MemoryLevel.L1) item.status else "archived"
                val sourceRefId = when (item.sourceKind.name) {
                    "MOMENT" -> item.sourceRefId.toLongOrNull()?.let(momentIdMap::get)?.toString()
                        ?: item.sourceRefId
                    "MOMENT_COMMENT" -> item.sourceRefId.toLongOrNull()?.let(commentIdMap::get)?.toString()
                        ?: item.sourceRefId
                    else -> item.sourceRefId
                }
                memoryItemsQueries.insertMemoryItem(
                    item.ownerType, item.ownerId, item.memoryLevel.name, item.memoryType,
                    item.sourceKind.name, sourceRefId, item.sessionId, item.content, item.nickname,
                    item.importance.toLong(), item.privacy, if (item.unmetNeed) 1L else 0L,
                    item.location, item.emotionValence, item.eventTime, item.createdAt,
                    item.updatedAt, item.expiresAt, status, item.scheduledTime, item.action,
                    item.careType, item.topicKey, item.sourceActor, item.sourceTarget,
                    item.lastUsedAt, item.usedCount.toLong(), item.confidence, item.rawJson, "",
                )
            }
            report(c.memoryItems.orEmpty().size)
        }
    }

    private fun RhodesDatabase.insertOperator(op: Operator) {
        operatorsQueries.insertAllOperators(
            op.id, op.name, op.title, op.description, op.gender, op.avatarUri, op.location,
            op.activity, op.emotion, op.intimacy.toLong(), op.privatePrompt, op.groupPrompt,
            op.memoryInjection, op.userRelation, op.lmb.toLong(), op.attack.toDouble(),
            op.defense.toDouble(), op.meldPref, op.activityLevel.toDouble(), op.voiceName,
            op.voiceSpeed, op.voicePitch,
        )
    }
}
