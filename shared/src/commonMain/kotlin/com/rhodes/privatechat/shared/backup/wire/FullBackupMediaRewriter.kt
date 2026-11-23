package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.backup.BackupRestorePayloadRewriter

/**
 * 全类目媒体 URI 重写（T31；规格卡契约 C9）。
 *
 * 语义与 T10 [BackupRestorePayloadRewriter] 完全一致（lead 裁决 a：mediaId 直映射，不做 Android
 * 路径特征串/canonical 解析），扫描域与 Android `BackupRestorePayloadRewriter.kt:3-42` 同：
 * operators.avatarUri / sessions.avatarUri / messages.content / settings(user_avatar_uri, support_conversation) /
 * giftRecords.imageUri / chatArchives(messagesJson, stateJson) / chatHistorySegments.messagesJson。
 * 不含 moments/diaries/memories/memoryItems 内容（Android 同款，不扩大扫描域，见规格卡 R3）。
 */
object FullBackupMediaRewriter {

    fun rewrite(payload: FullBackupPayloadWire, stagedMedia: Map<String, String>): FullBackupPayloadWire {
        if (stagedMedia.isEmpty()) return payload
        val byOriginalUri = payload.media.mapNotNull { item ->
            item.originalUri.takeIf { it.isNotBlank() }?.let { original ->
                stagedMedia[item.mediaId]?.let { restored -> original to restored }
            }
        }.toMap()

        fun uri(value: String): String = if (value.isBlank()) value else byOriginalUri[value] ?: stagedMedia[value] ?: value

        fun content(value: String): String = byOriginalUri.entries.fold(value) { result, entry ->
            result.replace(entry.key, entry.value)
        }

        return payload.copy(
            content = payload.content.copy(
                operators = payload.content.operators?.map { it.copy(avatarUri = uri(it.avatarUri)) },
                sessions = payload.content.sessions?.map { it.copy(avatarUri = uri(it.avatarUri)) },
                messages = payload.content.messages?.map { it.copy(content = content(it.content)) },
                settings = payload.content.settings?.toMutableMap()?.apply {
                    this["user_avatar_uri"] = this["user_avatar_uri"]?.let { value ->
                        if (value.startsWith("s:")) "s:${uri(value.removePrefix("s:"))}" else uri(value)
                    }.orEmpty()
                    this["support_conversation"] = this["support_conversation"]?.let { value ->
                        val prefix = if (value.startsWith("s:")) "s:" else ""
                        prefix + content(value.removePrefix("s:"))
                    }.orEmpty()
                },
            ),
            giftRecords = payload.giftRecords.map { it.copy(imageUri = uri(it.imageUri)) },
            chatArchives = payload.chatArchives.map { archive ->
                archive.copy(messagesJson = content(archive.messagesJson), stateJson = content(archive.stateJson))
            },
            chatHistorySegments = payload.chatHistorySegments.map { history ->
                history.copy(messagesJson = content(history.messagesJson))
            },
        )
    }
}
