package com.rhodes.privatechat.shared.domain.memory

/**
 * 事实关系规则（T78 最小落地）：只做**确定性判定**，不做模型裁决。
 *
 * 同一归并基座（`subject|predicate|objectHead`，去掉限定词与客体尾部修饰）：
 * - 极性相同 → `supports`（新证据再次确认旧事实）；
 * - 极性相反 → `contradicts`（不关闭旧事实，只记录冲突）；
 * - 单值谓词（住址/工作/年龄这类同时只能有一个取值）换了客体 → `supersedes`（关闭旧事实有效期）。
 */
object MemoryRelationRules {

    const val SUPPORTS = "supports"
    const val CONTRADICTS = "contradicts"
    const val SUPERSEDES = "supersedes"
    const val FACT_PREFIX = "fact:"

    /**
     * 单值谓词：同一主体只可能有一个当前取值。换值即「改为」，不是新增事实。
     * 只收确定性高的几类，避免把「喜欢的猫」这类可多值谓词误判成取代。
     */
    val FUNCTIONAL_PREDICATES: Set<String> = setOf("reside", "work", "birthday", "age", "live_city")

    fun isFunctional(predicate: String): Boolean = predicate in FUNCTIONAL_PREDICATES

    fun relationType(newTopicKey: String, existingTopicKey: String): String? {
        val newParts = MemoryParseRules.parseTopicKey(newTopicKey) ?: return null
        val oldParts = MemoryParseRules.parseTopicKey(existingTopicKey) ?: return null
        if (newParts.base == oldParts.base) {
            return if (newParts.polarity == oldParts.polarity) SUPPORTS else CONTRADICTS
        }
        if (isFunctional(newParts.predicate) && isFunctional(oldParts.predicate) &&
            newParts.subject == oldParts.subject && newParts.predicate == oldParts.predicate
        ) {
            return SUPERSEDES
        }
        return null
    }

    /** `fact:<polarity>:<base>` → 归并基座；非结构化键返回 null。 */
    fun baseOf(topicKey: String): String? = MemoryParseRules.factBaseOf(topicKey)

    fun polarityOf(topicKey: String): String =
        MemoryParseRules.parseTopicKey(topicKey)?.polarity
            ?: topicKey.removePrefix(FACT_PREFIX).substringBefore(':')

    fun predicateOf(topicKey: String): String? = MemoryParseRules.parseTopicKey(topicKey)?.predicate

    fun oppositeKey(topicKey: String): String? {
        val parts = MemoryParseRules.parseTopicKey(topicKey) ?: return null
        val other = if (parts.polarity == "negative") "positive" else "negative"
        return "$FACT_PREFIX$other:${parts.base}"
    }
}
