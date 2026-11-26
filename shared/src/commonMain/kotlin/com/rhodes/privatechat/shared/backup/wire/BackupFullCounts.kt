package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.backup.BackupContentSelection
import com.rhodes.privatechat.shared.db.RhodesDatabase

/**
 * 全类目计数口径（T31；规格卡契约 C7「提交前自检」/C7.2「提交后对账」）。
 *
 * - [expected]：按**过滤后 payload** 推导的期望计数（权威口径），按分类选择门禁裁剪；
 *   roles-only（roles=true、chats=false）时 operators/relationships 为 upsert 语义，计数不可预期，
 *   故不进入严格对账（与 T30 `BackupRestoreSelfCheck` 同原则）。
 * - [read]：单事务内读取 26 张恢复表的实际计数（全部走 SQLDelight 查询，零新迁移）。
 * - [invariantViolations]：字段级不变量（向量清空 / 动态计数回填），以 mismatch 文本返回。
 */
object BackupFullCounts {

    /** 自检 checked 键（26 张恢复表 + settings；按选择裁剪后可能少于 27）。 */
    val ALL_KEYS = listOf(
        "operators", "relationships", "sessions", "messages", "displayEvents",
        "archives", "historySegments", "memories", "anchors", "memoryItems",
        "memoryFacts", "memoryRelations", "impressions", "impressionEvidence",
        "moments", "comments", "likes", "diaries", "sharedExperiences", "participants",
        "knowledgeBases", "chunks", "assignments", "gifts", "dispatches", "mahjong", "settings",
    )

    fun expected(
        payload: FullBackupPayloadWire,
        selection: BackupContentSelection,
    ): Map<String, Int> {
        val s = selection.normalized()
        val c = payload.content
        val out = linkedMapOf<String, Int>()
        // roles-only 为 upsert（T30 口径），不做严格计数。
        if (s.roles && s.chats) {
            out["operators"] = c.operators.orEmpty().size
            out["relationships"] = c.relationships.orEmpty().size
        }
        if (s.chats) {
            out["sessions"] = c.sessions.orEmpty().size
            out["messages"] = c.messages.orEmpty().size
            out["displayEvents"] = payload.displayEvents.size
            out["archives"] = payload.chatArchives.size
            out["historySegments"] = payload.chatHistorySegments.size
        }
        if (s.memories) {
            out["memories"] = c.memories.orEmpty().size
            out["anchors"] = c.anchors.orEmpty().size
            out["memoryItems"] = c.memoryItems.orEmpty().size
            out["memoryFacts"] = c.memoryFacts.orEmpty().size
            out["memoryRelations"] = c.memoryRelations.orEmpty().size
            out["impressions"] = c.impressions.orEmpty().size
            out["impressionEvidence"] = c.impressionEvidence.orEmpty().size
        }
        if (s.social) {
            out["moments"] = c.moments.orEmpty().size
            out["comments"] = c.momentComments.orEmpty().size
            out["likes"] = c.momentLikes.orEmpty().size
            out["diaries"] = c.diaries.orEmpty().size
            out["sharedExperiences"] = payload.sharedExperiences.size
            out["participants"] = payload.sharedExperienceParticipants.size
        }
        if (s.knowledgeBases) {
            out["knowledgeBases"] = c.knowledgeBases.orEmpty().size
            out["chunks"] = c.knowledgeBaseChunks.orEmpty().size
            out["assignments"] = c.operatorKnowledgeBaseAssignments.orEmpty().size
        }
        if (s.extras) {
            out["gifts"] = payload.giftRecords.size
            out["dispatches"] = payload.dispatchRecords.size
            out["mahjong"] = if (payload.mahjongSave != null) 1 else 0
        }
        if (s.settings) out["settings"] = c.settings.orEmpty().size
        return out
    }

    /** 单事务内读取实际计数（只读；由调用方注入 `RhodesDatabase.() -> Unit` 事务）。 */
    fun read(database: RhodesDatabase): Map<String, Int> = with(database) {
        linkedMapOf(
            "operators" to operatorsQueries.getCount().executeAsOne().toInt(),
            "relationships" to relationshipsQueries.getCount().executeAsOne().toInt(),
            "sessions" to chatSessionsQueries.getSessionCount().executeAsOne().toInt(),
            "messages" to chatMessagesQueries.getMessageCount().executeAsOne().toInt(),
            "displayEvents" to chatDisplayEventsQueries.getAllDisplayEvents().executeAsList().size,
            "archives" to chatArchivesQueries.getAllArchives().executeAsList().size,
            "historySegments" to chatArchivesQueries.getAllHistorySegments().executeAsList().size,
            "memories" to memoriesQueries.getAllMemoriesForBackup().executeAsList().size,
            "anchors" to memoryAnchorsQueries.getAnchorCount().executeAsOne().toInt(),
            "memoryItems" to memoryItemsQueries.getAllMemoryItems().executeAsList().size,
            "memoryFacts" to memoryFactsQueries.countMemoryFacts().executeAsOne().toInt(),
            "memoryRelations" to memoryRelationsQueries.countMemoryRelations().executeAsOne().toInt(),
            "impressions" to impressionsQueries.selectAllForBackup().executeAsList().size,
            "impressionEvidence" to impressionEvidenceQueries.selectAllForBackup().executeAsList().size,
            "moments" to momentsQueries.getAllMoments().executeAsList().size,
            "comments" to momentCommentsQueries.getAllCommentsForBackup().executeAsList().size,
            "likes" to momentLikesQueries.getAllLikesForBackup().executeAsList().size,
            "diaries" to diariesQueries.getCount().executeAsOne().toInt(),
            "sharedExperiences" to sharedExperiencesQueries.getAllSharedExperiences().executeAsList().size,
            "participants" to sharedExperiencesQueries.getSharedExperienceParticipants().executeAsList().size,
            "knowledgeBases" to knowledgeBasesQueries.getKnowledgeBaseCount().executeAsOne().toInt(),
            "chunks" to knowledgeBasesQueries.getAllKnowledgeBaseChunks().executeAsList().size,
            "assignments" to knowledgeBasesQueries.getAllKnowledgeBaseAssignments().executeAsList().size,
            "gifts" to giftRecordsQueries.getAllGifts().executeAsList().size,
            "dispatches" to dispatchRecordsQueries.getAllDispatches().executeAsList().size,
            "mahjong" to if (mahjongSavesQueries.getSave().executeAsOneOrNull() != null) 1 else 0,
        )
    }

    /**
     * 字段级不变量（规格卡验收 #5）：
     *  - memories 选中：`memory_items.vectorId` 全空（向量属 excludedCategories「vector_index」，恢复后待重建）；
     *  - social 选中：每个 moment 的 likeCount/commentCount == 实际 likes/comments 行数（backfill 语义，
     *    oracle `RestoreDatabaseExecutor.kt:137-138`）。
     */
    fun invariantViolations(database: RhodesDatabase, selection: BackupContentSelection): List<String> {
        val s = selection.normalized()
        val violations = mutableListOf<String>()
        with(database) {
            if (s.memories) {
                val stale = memoryItemsQueries.getAllMemoryItems().executeAsList().count { it.vectorId.isNotBlank() }
                if (stale > 0) violations += "memoryItems.vectorId 非空：$stale"
            }
            if (s.social) {
                val moments = momentsQueries.getAllMoments().executeAsList()
                val likeMismatch = moments.count { moment ->
                    moment.likeCount != momentLikesQueries.getLikeCount(moment.id).executeAsOne()
                }
                val commentMismatch = moments.count { moment ->
                    moment.commentCount != momentCommentsQueries.getCommentCount(moment.id).executeAsOne()
                }
                if (likeMismatch > 0) violations += "moments.likeCount 未回填：$likeMismatch"
                if (commentMismatch > 0) violations += "moments.commentCount 未回填：$commentMismatch"
            }
        }
        return violations
    }
}
