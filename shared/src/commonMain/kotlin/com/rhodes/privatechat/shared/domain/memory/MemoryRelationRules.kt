package com.rhodes.privatechat.shared.domain.memory

/**
 * 事实关系规则（T78 最小落地）：只做**确定性判定**，不做模型裁决。
 *
 * 同一事实键基座（`fact:<polarity>:<base>`）：
 * - 极性相同 → `supports`（新证据再次确认旧事实）；
 * - 极性相反 → `contradicts`（不关闭旧事实，只记录冲突）；
 * - 基座不同 → 无关系（refines/contextual/supersedes 需要模型或用户裁决，留给 T78 完整版）。
 */
object MemoryRelationRules {

    const val SUPPORTS = "supports"
    const val CONTRADICTS = "contradicts"
    const val FACT_PREFIX = "fact:"

    fun relationType(newTopicKey: String, existingTopicKey: String): String? {
        val newBase = baseOf(newTopicKey) ?: return null
        val existingBase = baseOf(existingTopicKey) ?: return null
        if (newBase != existingBase) return null
        return if (polarityOf(newTopicKey) == polarityOf(existingTopicKey)) SUPPORTS else CONTRADICTS
    }

    /** `fact:<polarity>:<base>` → base；非结构化键返回 null。 */
    fun baseOf(topicKey: String): String? {
        if (!topicKey.startsWith(FACT_PREFIX)) return null
        val rest = topicKey.removePrefix(FACT_PREFIX)
        val index = rest.indexOf(':')
        if (index <= 0 || index >= rest.length - 1) return null
        return rest.substring(index + 1)
    }

    fun polarityOf(topicKey: String): String = topicKey.removePrefix(FACT_PREFIX).substringBefore(':')

    fun oppositeKey(topicKey: String): String? {
        val base = baseOf(topicKey) ?: return null
        val other = if (polarityOf(topicKey) == "negative") "positive" else "negative"
        return "$FACT_PREFIX$other:$base"
    }
}
