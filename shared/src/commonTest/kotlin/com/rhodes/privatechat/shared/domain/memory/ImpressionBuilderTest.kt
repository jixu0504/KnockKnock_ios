package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T81 印象构建规则：L2/L3 按事实键聚合、层级映射、置信度公式（含封顶）、空输入与兜底键。
 */
class ImpressionBuilderTest {

    private val now = 1_800_000_000_000L
    private val factKey = "fact:positive:用户|prefer|川菜"

    private fun memory(
        level: MemoryLevel,
        content: String = "用户喜欢吃川菜",
        topicKey: String = factKey,
        memoryType: String = "preference_expression",
        updatedAt: Long = now,
        createdAt: Long = now,
        id: Long = 0,
    ) = MemoryItem(
        id = id,
        ownerType = "operator",
        ownerId = "op1",
        memoryLevel = level,
        memoryType = memoryType,
        sourceKind = MemorySourceKind.PRIVATE_CHAT,
        content = content,
        importance = 70,
        topicKey = topicKey,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun assertConfidence(expected: Double, actual: Double, message: String) {
        assertTrue(abs(expected - actual) < 1e-9, "$message（期望 $expected，实际 $actual）")
    }

    @Test
    fun sameFactKeyMergesIntoOneDraftWithEvidenceCount() {
        val drafts = ImpressionBuilder.build(
            listOf(
                memory(MemoryLevel.L2, createdAt = now - 1_000, updatedAt = now - 1_000, id = 1),
                memory(MemoryLevel.L2, updatedAt = now, id = 2, content = "用户爱吃川菜"),
            )
        )
        assertEquals(1, drafts.size, "同一事实键只产出一条印象")
        val draft = drafts.single()
        assertEquals(2, draft.evidenceCount)
        assertEquals(factKey, draft.factKey)
        assertEquals("用户", draft.subject)
        assertEquals("prefer", draft.predicate)
        assertEquals("川菜", draft.value)
        assertEquals("positive", draft.polarity)
        assertEquals("positive", draft.tone)
        assertEquals(now, draft.lastConfirmedAt, "代表条目取最新一条")
        assertEquals(now - 1_000, draft.firstSeenAt, "首见时间取组内最早")
        assertTrue(!draft.legacyKey)
    }

    @Test
    fun layerFollowsStrongestEvidenceAndConfidenceAddsLongTermBonus() {
        val ongoing = ImpressionBuilder.build(listOf(memory(MemoryLevel.L2))).single()
        assertEquals(ImpressionBuilder.LAYER_ONGOING, ongoing.layer)
        assertConfidence(0.7, ongoing.confidence, "L2 单条 = 0.6 + 0.1")

        val promoted = ImpressionBuilder.build(
            listOf(memory(MemoryLevel.L2), memory(MemoryLevel.L3, updatedAt = now + 1_000))
        ).single()
        assertEquals(ImpressionBuilder.LAYER_LONG_TERM, promoted.layer, "组内出现 L3 即为长期印象")
        assertConfidence(0.9, promoted.confidence, "0.6 + 0.1×2 + 0.1(L3)")

        val onlyLongTerm = ImpressionBuilder.build(listOf(memory(MemoryLevel.L3))).single()
        assertEquals(ImpressionBuilder.LAYER_LONG_TERM, onlyLongTerm.layer)
        assertConfidence(0.8, onlyLongTerm.confidence, "0.6 + 0.1 + 0.1")
    }

    @Test
    fun confidenceIsCappedAtMaximum() {
        val items = (1..8).map { index -> memory(MemoryLevel.L3, updatedAt = now + index, id = index.toLong()) }
        val draft = ImpressionBuilder.build(items).single()
        assertConfidence(0.95, draft.confidence, "证据再多也不越过 0.95")
        assertConfidence(0.95, ImpressionBuilder.confidenceOf(evidenceCount = 8, hasLongTerm = false), "封顶")
        assertConfidence(0.6, ImpressionBuilder.confidenceOf(evidenceCount = 0, hasLongTerm = false), "无证据时只有基础值")
        assertEquals(8, draft.evidenceCount)
    }

    @Test
    fun emptyInputYieldsNothingAndL1IsIgnored() {
        assertEquals(emptyList(), ImpressionBuilder.build(emptyList()))
        assertEquals(emptyList(), ImpressionBuilder.build(listOf(memory(MemoryLevel.L1), memory(MemoryLevel.L1))))
        assertEquals(emptyList(), ImpressionBuilder.build(listOf(memory(MemoryLevel.L2, content = "   "))))
    }

    @Test
    fun blankTopicKeyFallsBackToLegacyKey() {
        val draft = ImpressionBuilder.build(
            listOf(
                memory(MemoryLevel.L2, content = "用户最近在练吉他", topicKey = "", memoryType = "intent_wish"),
                memory(MemoryLevel.L2, content = "用户最近在练吉他", topicKey = "", memoryType = "intent_wish", updatedAt = now + 5),
            )
        ).single()
        assertEquals("legacy:intent_wish|用户最近在练吉他", draft.factKey)
        assertTrue(draft.legacyKey, "兜底键需标注")
        assertEquals("", draft.subject)
        assertEquals("", draft.predicate)
        assertEquals("unknown", draft.polarity)
        assertEquals("neutral", draft.tone)
        assertEquals("用户最近在练吉他", draft.value)
        assertEquals(2, draft.evidenceCount)
    }

    @Test
    fun legacyKeyTruncatesContentToTwelveCharacters() {
        val draft = ImpressionBuilder.build(
            listOf(memory(MemoryLevel.L2, content = "用户最近在练习吉他曲子还买了新琴", topicKey = ""))
        ).single()
        assertEquals("legacy:preference_expression|用户最近在练习吉他曲子还", draft.factKey)
    }

    @Test
    fun distinctFactsStayApart() {
        val drafts = ImpressionBuilder.build(
            listOf(
                memory(MemoryLevel.L2),
                memory(MemoryLevel.L3, topicKey = "fact:positive:用户|own|猫"),
            )
        )
        assertEquals(2, drafts.size)
        assertEquals(listOf("fact:positive:用户|own|猫", factKey), drafts.map { it.factKey })
    }
}