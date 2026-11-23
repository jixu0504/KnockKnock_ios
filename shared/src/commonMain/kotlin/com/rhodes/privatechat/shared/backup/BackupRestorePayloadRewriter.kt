package com.rhodes.privatechat.shared.backup

/**
 * 恢复媒体 URI 重写（lead 裁决 a，2026-11-22）：iOS 采用 **mediaId 直映射**，
 * 替代 Android 的三层路径特征串匹配（oracle：app/.../data/backup/BackupRestorePayloadRewriter.kt:3-42,48-63）。
 *
 * 扫描对象与 Android 一致（avatarUri / messages.content / settings / gift / archive JSON），
 * 替换表由 `payload.media[mediaId] → 本地 URL` 构建：originalUri 精确匹配 + 裸 mediaId 引用兜底，
 * 不做 /data/data|/data/user/0 特征串与 canonical 解析（iOS 沙盒无此语义）。
 */
object BackupRestorePayloadRewriter {

    /**
     * @param stagedMedia mediaId → 落盘后的新 URL（由 BackupMediaSink.stage 返回）。
     *                    缺失的 mediaId 保留原值（与 Android「mediaFiles 缺失时保留原 URI」一致）。
     */
    fun rewriteMediaUris(payload: BackupPayloadWire, stagedMedia: Map<String, String>): BackupPayloadWire {
        val byOriginalUri = payload.media.mapNotNull { item ->
            item.originalUri.takeIf { it.isNotBlank() }?.let { original ->
                stagedMedia[item.mediaId]?.let { restored -> original to restored }
            }
        }.toMap()
        val byMediaId = stagedMedia

        fun uri(value: String): String = if (value.isBlank()) value else
            byOriginalUri[value] ?: byMediaId[value] ?: value

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
