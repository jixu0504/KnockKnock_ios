package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel

/** 一条候选证据：引用哪条记忆、权重多少（由层级决定）。 */
data class PlannedImpressionEvidence(val memoryId: Long, val weight: Double)

/**
 * 印象证据规划（T81a）：决定「同一条印象该挂哪些记忆作为证据」。
 *
 * 纯函数，不碰数据库：输入同一 factKey 下的候选记忆，输出 ≤8 条证据。
 * 层级越高越能代表这条印象（L3 稳定印象 > L2 结构化事实 > L1 单次表达），
 * 同层内按 importance 降序；证据只是「引用」，不复制记忆内容。
 */
object ImpressionEvidencePlanner {

    const val MAX_EVIDENCE = 8
    const val L3_WEIGHT = 0.9
    const val L2_WEIGHT = 0.7
    const val L1_WEIGHT = 0.5

    fun weightFor(level: MemoryLevel): Double = when (level) {
        MemoryLevel.L3 -> L3_WEIGHT
        MemoryLevel.L2 -> L2_WEIGHT
        MemoryLevel.L1 -> L1_WEIGHT
    }

    /**
     * @param candidates 同一 factKey 下的候选记忆（允许重复 id、允许未落库的临时条目）。
     * 未落库条目（id <= 0）无法作为证据行引用，直接跳过。
     */
    fun plan(candidates: List<MemoryItem>, limit: Int = MAX_EVIDENCE): List<PlannedImpressionEvidence> {
        if (limit <= 0) return emptyList()
        val seen = mutableSetOf<Long>()
        return candidates
            .filter { it.id > 0 }
            .sortedWith(
                compareByDescending<MemoryItem> { levelRank(it.memoryLevel) }
                    .thenByDescending { it.importance }
                    .thenBy { it.id },
            )
            .mapNotNull { item ->
                if (!seen.add(item.id)) return@mapNotNull null
                PlannedImpressionEvidence(memoryId = item.id, weight = weightFor(item.memoryLevel))
            }
            .take(limit)
    }

    private fun levelRank(level: MemoryLevel): Int = when (level) {
        MemoryLevel.L3 -> 3
        MemoryLevel.L2 -> 2
        MemoryLevel.L1 -> 1
    }
}