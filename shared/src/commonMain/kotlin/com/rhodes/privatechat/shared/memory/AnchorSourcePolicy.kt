package com.rhodes.privatechat.shared.memory

import com.rhodes.privatechat.shared.domain.memory.policy.AnchorSourcePolicy as DomainAnchorSourcePolicy
import com.rhodes.privatechat.shared.model.AnchorType
import com.rhodes.privatechat.shared.model.MemoryAnchor

/**
 * @deprecated iOS-port T24：六类锚点来源策略已迁至 `shared.domain.memory.policy.AnchorSourcePolicy`。
 * 本对象仅为 app/ 侧未迁移调用点（SharedUtils.kt、锚点管理）保留的薄委托，行为与旧实现逐字一致；
 * Android 调用点改造后删除（见 ios-port/handoffs/impl-memory.md）。
 */
@Suppress("DEPRECATION")
@Deprecated("迁移至 com.rhodes.privatechat.shared.domain.memory.policy.AnchorSourcePolicy")
object AnchorSourcePolicy {
    const val PRIVATE_CHAT = DomainAnchorSourcePolicy.PRIVATE_CHAT
    const val GROUP_CHAT = DomainAnchorSourcePolicy.GROUP_CHAT
    const val MOMENT = DomainAnchorSourcePolicy.MOMENT
    const val COMMENT = DomainAnchorSourcePolicy.COMMENT
    const val DIARY = DomainAnchorSourcePolicy.DIARY
    const val DISPATCH = DomainAnchorSourcePolicy.DISPATCH
    const val MAHJONG = DomainAnchorSourcePolicy.MAHJONG
    const val STATUS = DomainAnchorSourcePolicy.STATUS
    const val LEGACY = DomainAnchorSourcePolicy.LEGACY
    const val STRONG = DomainAnchorSourcePolicy.STRONG
    const val MEDIUM = DomainAnchorSourcePolicy.MEDIUM
    const val WEAK = DomainAnchorSourcePolicy.WEAK

    fun buildAnchor(
        source: String, sourceName: String, sourceActor: String, sourceTarget: String,
        operatorId: String, type: AnchorType, content: String, importance: String,
        sessionId: String, isPrivate: Boolean = false, createdAt: Long = 0L,
        expiresAt: Long = Long.MAX_VALUE,
    ): MemoryAnchor = DomainAnchorSourcePolicy.buildAnchor(
        source, sourceName, sourceActor, sourceTarget, operatorId, type, content, importance,
        sessionId, isPrivate, createdAt, expiresAt,
    )

    fun inferLegacy(anchor: MemoryAnchor): MemoryAnchor = DomainAnchorSourcePolicy.inferLegacy(anchor)
    fun knownFrom(source: String, sourceName: String, sourceActor: String): String =
        DomainAnchorSourcePolicy.knownFrom(source, sourceName, sourceActor)
    fun toPromptLine(anchor: MemoryAnchor): String = DomainAnchorSourcePolicy.toPromptLine(anchor)
    fun normalizeImportance(value: String): String = DomainAnchorSourcePolicy.normalizeImportance(value)
}
