package com.rhodes.privatechat.shared.backup.wire

/**
 * 全类目孤儿过滤（T31；规格卡契约 C10）。
 *
 * 与 T10 `BackupContentFilter` 同语义、同 5 个 issue code（oracle：`BackupRestoreCoordinator.kt:135-155`
 * filterIssues；issue code 来源 `BackupFileReader.kt:182-203`）。未命中 `skipIssueCodes` 时原样保留
 * （与 Android 一致：孤儿记录允许落库，DB 无外键约束）。
 */
object FullBackupContentFilter {

    fun filterIssues(payload: FullBackupPayloadWire, skipCodes: Set<String>): FullBackupPayloadWire {
        if (skipCodes.isEmpty()) return payload
        val operatorIds = payload.content.operators.orEmpty().mapTo(mutableSetOf()) { it.id }
        val sessionIds = payload.content.sessions.orEmpty().mapTo(mutableSetOf()) { it.id }
        val momentIds = payload.content.moments.orEmpty().mapTo(mutableSetOf()) { it.id }
        val commentIds = payload.content.momentComments.orEmpty().mapTo(mutableSetOf()) { it.id }
        val experienceIds = payload.sharedExperiences.mapTo(mutableSetOf()) { it.id }
        return payload.copy(
            content = payload.content.copy(
                memories = if ("ORPHAN_MEMORY" in skipCodes) payload.content.memories.orEmpty().filter { memory ->
                    (memory.type.name == "DAILY" && memory.operatorId == "daily" &&
                        memory.sessionId.startsWith("daily_")) ||
                        (memory.operatorId in operatorIds && memory.sessionId in sessionIds)
                } else payload.content.memories,
                anchors = if ("ORPHAN_ANCHOR" in skipCodes) payload.content.anchors.orEmpty().filter { anchor ->
                    anchor.operatorId in operatorIds && anchor.sessionId in sessionIds
                } else payload.content.anchors,
                momentComments = if ("ORPHAN_MOMENT_COMMENT" in skipCodes) {
                    payload.content.momentComments.orEmpty().filter { comment ->
                        comment.momentId in momentIds &&
                            (comment.parentCommentId == 0L || comment.parentCommentId in commentIds)
                    }
                } else payload.content.momentComments,
                momentLikes = if ("ORPHAN_MOMENT_LIKE" in skipCodes) {
                    payload.content.momentLikes.orEmpty().filter { like -> like.momentId in momentIds }
                } else payload.content.momentLikes,
            ),
            sharedExperienceParticipants = if ("ORPHAN_SHARED_EXPERIENCE_PARTICIPANT" in skipCodes) {
                payload.sharedExperienceParticipants.filter { participant ->
                    participant.experienceId in experienceIds && participant.operatorId in operatorIds
                }
            } else payload.sharedExperienceParticipants,
        )
    }
}
