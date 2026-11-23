package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemorySourceKind

data class MemoryVisibility(
    val privacy: String,
    val ownerType: String,
    val ownerId: String,
    val writeGlobalPublic: Boolean = false,
)

object MemoryPrivacyPolicy {
    /**
     * 敏感词表（18 条，逐字节对齐 Android oracle MemoryPrivacyPolicy.kt:55-61）。
     * T47 记忆管理页展示用：`MemoryBridge.privacySettings()` 直接把本表暴露给 UI。
     */
    val sensitiveWords: List<String> = listOf(
        "别告诉", "不要告诉", "不能告诉", "秘密", "隐私", "私密", "保密",
        "焦虑", "崩溃", "撑不住", "害怕", "难过", "抑郁", "自杀", "自伤",
        "亲密", "只告诉你", "不想让别人知道"
    )

    fun forSource(
        sourceKind: MemorySourceKind,
        ownerType: String,
        ownerId: String,
        content: String,
        explicitPrivate: Boolean = false,
    ): MemoryVisibility {
        val sensitive = explicitPrivate || isSensitive(content)
        return when (sourceKind) {
            MemorySourceKind.PRIVATE_CHAT -> MemoryVisibility(
                privacy = if (sensitive) "private" else "shared",
                ownerType = ownerType,
                ownerId = ownerId,
                writeGlobalPublic = false,
            )
            MemorySourceKind.GROUP_CHAT -> MemoryVisibility(
                privacy = "public",
                ownerType = "group",
                ownerId = ownerId,
                writeGlobalPublic = false,
            )
            MemorySourceKind.MOMENT, MemorySourceKind.MOMENT_COMMENT -> MemoryVisibility(
                privacy = "public",
                ownerType = ownerType,
                ownerId = ownerId,
                writeGlobalPublic = true,
            )
            MemorySourceKind.DIARY -> MemoryVisibility(
                privacy = "private",
                ownerType = ownerType,
                ownerId = ownerId,
                writeGlobalPublic = false,
            )
            MemorySourceKind.MANUAL_MEMORY -> MemoryVisibility(
                privacy = if (sensitive) "private" else "shared",
                ownerType = ownerType,
                ownerId = ownerId,
                writeGlobalPublic = false,
            )
        }
    }

    fun isSensitive(content: String): Boolean {
        val text = content.lowercase()
        return sensitiveWords.any { text.contains(it.lowercase()) }
    }
}
