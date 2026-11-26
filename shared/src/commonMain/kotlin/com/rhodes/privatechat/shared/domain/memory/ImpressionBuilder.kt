package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel

/**
 * 印象草稿（T81）：由 L2/L3 记忆按事实键聚合而来，尚未落库。
 *
 * [legacyKey] 标记该条没有结构化事实键、退化用 `legacy:<memoryType>|<内容前 12 字>` 兜底，
 * 这类条目的 subject/predicate 为空，只有 [value] 有内容。
 */
data class ImpressionDraft(
    val ownerType: String,
    val ownerId: String,
    val characterId: String = "",
    val layer: String,
    val subject: String = "",
    val predicate: String = "",
    val value: String = "",
    val polarity: String = "unknown",
    val factKey: String,
    val confidence: Double,
    val tone: String = "neutral",
    val evidenceCount: Int = 0,
    val firstSeenAt: Long = 0,
    val lastConfirmedAt: Long = 0,
    val legacyKey: Boolean = false,
)

/**
 * 印象构建纯规则（T81）：`List<MemoryItem>` → `List<ImpressionDraft>`，无存储/时间依赖。
 *
 * 分组只看事实键（`MemoryParseRules.structuredFactOf` 产出的 `fact:<polarity>:<subject>|<predicate>|<object>`），
 * 同键的多条证据合并成一行：代表条目取最新一条，证据数即组内条数；层级由组内是否存在 L3 决定。
 * 置信度 = 0.6 + 0.1×证据数（上限 0.95），L3 组再 +0.1 后仍封顶 0.95。
 */
object ImpressionBuilder {

    const val LAYER_LONG_TERM = "long_term"
    const val LAYER_ONGOING = "ongoing"
    const val BASE_CONFIDENCE = 0.6
    const val CONFIDENCE_STEP = 0.1
    const val MAX_CONFIDENCE = 0.95
    const val L3_BONUS = 0.1
    const val LEGACY_PREFIX = "legacy:"
    const val LEGACY_CONTENT_CHARS = 12
    const val VALUE_CHARS = 120
    const val FACT_PREFIX = "fact:"

    fun build(items: List<MemoryItem>): List<ImpressionDraft> {
        val eligible = items.filter {
            (it.memoryLevel == MemoryLevel.L2 || it.memoryLevel == MemoryLevel.L3) && it.content.isNotBlank()
        }
        if (eligible.isEmpty()) return emptyList()
        return eligible
            .groupBy { "${it.ownerType}|${it.ownerId}|${factKeyOf(it)}" }
            .values
            .map { group -> draftOf(group) }
            .sortedBy { it.factKey }
    }

    /** 事实键：有结构化 topicKey 就用它，否则按类型 + 内容前缀兜底（同名主题可稳定合并）。 */
    fun factKeyOf(item: MemoryItem): String {
        val topic = item.topicKey.trim()
        if (topic.isNotEmpty()) return topic
        return LEGACY_PREFIX + item.memoryType + "|" + item.content.trim().take(LEGACY_CONTENT_CHARS)
    }

    fun confidenceOf(evidenceCount: Int, hasLongTerm: Boolean): Double {
        val base = BASE_CONFIDENCE + CONFIDENCE_STEP * evidenceCount.coerceAtLeast(0)
        val bonus = if (hasLongTerm) L3_BONUS else 0.0
        return (base + bonus).coerceAtMost(MAX_CONFIDENCE)
    }

    private fun draftOf(group: List<MemoryItem>): ImpressionDraft {
        val representative = group.maxWithOrNull(
            compareBy<MemoryItem> { it.updatedAt }.thenBy { it.createdAt }.thenBy { it.importance }.thenBy { it.id },
        ) ?: group.first()
        val hasLongTerm = group.any { it.memoryLevel == MemoryLevel.L3 }
        val factKey = factKeyOf(representative)
        val legacy = !factKey.startsWith(FACT_PREFIX)
        val fact = if (legacy) null else parseFactKey(factKey)
        return ImpressionDraft(
            ownerType = representative.ownerType,
            ownerId = representative.ownerId,
            layer = if (hasLongTerm) LAYER_LONG_TERM else LAYER_ONGOING,
            subject = fact?.subject.orEmpty(),
            predicate = fact?.predicate.orEmpty(),
            value = fact?.objectValue ?: representative.content.trim().take(VALUE_CHARS),
            polarity = fact?.polarity ?: "unknown",
            factKey = factKey,
            confidence = confidenceOf(group.size, hasLongTerm),
            tone = toneOf(fact?.polarity),
            evidenceCount = group.size,
            firstSeenAt = group.map { it.createdAt }.filter { it > 0 }.minOrNull() ?: 0,
            lastConfirmedAt = group.maxOf { maxOf(it.updatedAt, it.createdAt) },
            legacyKey = legacy,
        )
    }

    private data class Fact(val subject: String, val predicate: String, val objectValue: String, val polarity: String)

    /** `fact:<polarity>:<subject>|<predicate>|<object>[|q=…]`；结构不符时按无结构处理。 */
    private fun parseFactKey(key: String): Fact? {
        val rest = key.removePrefix(FACT_PREFIX)
        val polarity = rest.substringBefore(':')
        val base = rest.substringAfter(':', "").trim()
        if (base.isEmpty() || !rest.contains(':')) return null
        val parts = base.split('|')
        val subject = parts.getOrNull(0).orEmpty().trim()
        if (subject.isEmpty()) return null
        return Fact(
            subject = subject,
            predicate = parts.getOrNull(1).orEmpty().trim(),
            objectValue = parts.getOrNull(2).orEmpty().trim(),
            polarity = polarity.ifBlank { "unknown" },
        )
    }

    private fun toneOf(polarity: String?): String = when (polarity) {
        "positive" -> "positive"
        "negative" -> "negative"
        else -> "neutral"
    }
}