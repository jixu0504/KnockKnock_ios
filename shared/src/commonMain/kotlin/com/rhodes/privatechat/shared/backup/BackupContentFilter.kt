package com.rhodes.privatechat.shared.backup

/**
 * 恢复选项过滤（oracle：app/.../data/backup/BackupRestoreCoordinator.kt:135-155 filterIssues）。
 * skipIssueCodes 命中时剔除对应孤儿记录；未命中则原样保留。
 */
object BackupContentFilter {

    fun filterIssues(payload: BackupPayloadWire, skipCodes: Set<String>): BackupPayloadWire {
        val operatorIds = payload.content.operators.orEmpty().map { it.id }.toSet()
        val sessionIds = payload.content.sessions.orEmpty().map { it.id }.toSet()
        val momentIds = payload.content.moments.orEmpty().map { it.id }.toSet()
        val commentIds = payload.content.momentComments.orEmpty().map { it.id }.toSet()
        val experienceIds = payload.sharedExperiences.map { it.id }.toSet()
        return payload.copy(
            content = payload.content.copy(
                memories = if ("ORPHAN_MEMORY" in skipCodes) payload.content.memories.orEmpty().filter {
                    (it.type == "DAILY" && it.operatorId == "daily" && it.sessionId.startsWith("daily_")) ||
                        (it.operatorId in operatorIds && it.sessionId in sessionIds)
                } else payload.content.memories,
                anchors = if ("ORPHAN_ANCHOR" in skipCodes) payload.content.anchors.orEmpty().filter {
                    it.operatorId in operatorIds && it.sessionId in sessionIds
                } else payload.content.anchors,
                momentComments = if ("ORPHAN_MOMENT_COMMENT" in skipCodes) payload.content.momentComments.orEmpty().filter {
                    it.momentId in momentIds && (it.parentCommentId == 0L || it.parentCommentId in commentIds)
                } else payload.content.momentComments,
                momentLikes = if ("ORPHAN_MOMENT_LIKE" in skipCodes) payload.content.momentLikes.orEmpty().filter {
                    it.momentId in momentIds
                } else payload.content.momentLikes,
            ),
            sharedExperienceParticipants = if ("ORPHAN_SHARED_EXPERIENCE_PARTICIPANT" in skipCodes) {
                payload.sharedExperienceParticipants.filter {
                    it.experienceId in experienceIds && it.operatorId in operatorIds
                }
            } else payload.sharedExperienceParticipants,
        )
    }
}
