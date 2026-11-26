package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind

/**
 * 「大家的印象」的展示过滤（审计 I-05 / 计划书 B7）。
 *
 * 背景：v0.2.0 的印象页是 `memory_items` 的只读过滤视图，L1 段没有任何门槛，
 * 任何偏好/情绪/身体状态都会长期堆积（一年后单角色数百条），且同一主题的
 * L1 与晋升后的 L2/L3 会同时出现。这里做三件事：
 * 1. L1 互动印象要求 `importance >= 40` 且落在最近 30 天内；
 * 2. 同一角色同一 `topicKey` 只保留排序后的第一条（L3 优先 → 更新时间 → 重要度）；
 * 3. 只收私聊来源的 active 条目（群聊/动态不进入私聊印象页）。
 *
 * 这是**展示层**过滤，不改变记忆本身，也不影响召回。
 */
object ImpressionFilter {

    val L1_TYPES = setOf("preference_expression", "emotion_state", "physiological_state")

    val L2_TYPES = setOf(
        "preference_expression",
        "agreement_commitment",
        "care_reminder",
        "intent_wish",
        "evaluation_opinion",
        "self_cognition_statement",
    )

    const val L1_MIN_IMPORTANCE = 40
    const val L1_WINDOW_MS = 30L * 86_400_000L
    const val L2_MIN_IMPORTANCE = 60

    fun isCurrent(item: MemoryItem, now: Long): Boolean {
        if (item.ownerType != "operator") return false
        if (item.sourceKind != MemorySourceKind.PRIVATE_CHAT) return false
        if (item.status != "active" || item.content.isBlank()) return false
        val recentCutoff = now - L1_WINDOW_MS
        return when (item.memoryLevel) {
            MemoryLevel.L1 -> item.importance >= L1_MIN_IMPORTANCE &&
                item.createdAt >= recentCutoff &&
                item.memoryType in L1_TYPES
            MemoryLevel.L2 -> item.expiresAt > now &&
                item.importance >= L2_MIN_IMPORTANCE &&
                item.memoryType in L2_TYPES
            MemoryLevel.L3 -> item.expiresAt > now
        }
    }

    /** 过滤 + 排序 + 同主题去重（保留排序后的第一条，通常是更高层级或更新的一条）。 */
    fun selectCurrent(items: List<MemoryItem>, now: Long): List<MemoryItem> {
        val sorted = items.filter { isCurrent(it, now) }
            .sortedWith(
                compareByDescending<MemoryItem> { it.memoryLevel == MemoryLevel.L3 }
                    .thenByDescending { it.updatedAt }
                    .thenByDescending { it.importance },
            )
        val seenTopics = mutableSetOf<String>()
        return sorted.filter { item ->
            val topic = item.topicKey.trim()
            if (topic.isEmpty()) true else seenTopics.add("${item.ownerId}|$topic")
        }
    }
}