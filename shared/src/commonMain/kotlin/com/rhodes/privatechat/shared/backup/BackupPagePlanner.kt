package com.rhodes.privatechat.shared.backup

/**
 * BackupPagePlanner.merge 的最小移植（oracle：app/.../data/backup/BackupPagePlanner.kt:102-122）。
 * 只做合并（Phase 0 最小闭环不产生分页写出）；version 固定 5 与 Android 一致。
 */
object BackupPagePlanner {

    fun merge(pages: List<BackupPayloadPageWire>): BackupPayloadWire {
        val content = BackupContentWire(
            version = 5,
            type = "full_backup",
            operators = pages.flatMap { it.payload.content.operators.orEmpty() }.ifEmpty { null },
            relationships = pages.flatMap { it.payload.content.relationships.orEmpty() }.ifEmpty { null },
            sessions = pages.flatMap { it.payload.content.sessions.orEmpty() }.ifEmpty { null },
            messages = pages.flatMap { it.payload.content.messages.orEmpty() }.ifEmpty { null },
            memories = pages.flatMap { it.payload.content.memories.orEmpty() }.ifEmpty { null },
            anchors = pages.flatMap { it.payload.content.anchors.orEmpty() }.ifEmpty { null },
            moments = pages.flatMap { it.payload.content.moments.orEmpty() }.ifEmpty { null },
            momentLikes = pages.flatMap { it.payload.content.momentLikes.orEmpty() }.ifEmpty { null },
            momentComments = pages.flatMap { it.payload.content.momentComments.orEmpty() }.ifEmpty { null },
            diaries = pages.flatMap { it.payload.content.diaries.orEmpty() }.ifEmpty { null },
            memoryItems = pages.flatMap { it.payload.content.memoryItems.orEmpty() }.ifEmpty { null },
            knowledgeBases = pages.flatMap { it.payload.content.knowledgeBases.orEmpty() }.ifEmpty { null },
            knowledgeBaseChunks = pages.flatMap { it.payload.content.knowledgeBaseChunks.orEmpty() }.ifEmpty { null },
            operatorKnowledgeBaseAssignments = pages.flatMap { it.payload.content.operatorKnowledgeBaseAssignments.orEmpty() }.ifEmpty { null },
            settings = pages.lastOrNull { it.payload.content.settings != null }?.payload?.content?.settings,
        )
        return BackupPayloadWire(
            content = content,
            displayEvents = pages.flatMap { it.payload.displayEvents },
            chatArchives = pages.flatMap { it.payload.chatArchives },
            chatHistorySegments = pages.flatMap { it.payload.chatHistorySegments },
            giftRecords = pages.flatMap { it.payload.giftRecords },
            dispatchRecords = pages.flatMap { it.payload.dispatchRecords },
            mahjongSave = pages.mapNotNull { it.payload.mahjongSave }.lastOrNull(),
            sharedExperiences = pages.flatMap { it.payload.sharedExperiences },
            sharedExperienceParticipants = pages.flatMap { it.payload.sharedExperienceParticipants },
            media = pages.flatMap { it.payload.media },
        )
    }
}
