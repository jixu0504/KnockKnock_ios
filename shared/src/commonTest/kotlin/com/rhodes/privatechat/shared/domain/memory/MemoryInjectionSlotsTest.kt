package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.DisabledVectorStoreGateway
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 注入名额分配（58 轮实测）：近期泛化 L1 先占满名额，语义强命中进不了注入预算，
 * 导致「团子几岁 / 我讨厌什么天气」这类查询全部答“不知道”。
 */
class MemoryInjectionSlotsTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun engine() = MemoryRecallEngine(
        settings = SettingsRepository(PromptTestSettings()),
        memoryVectorService = MemoryVectorService(
            SettingsRepository(PromptTestSettings()),
            DisabledVectorStoreGateway(),
        ),
        nowMillis = { now },
    )

    private fun candidate(
        id: Long,
        content: String,
        createdAt: Long,
        lexicalScore: Double = 0.0,
        sourceType: String = "memory_v2_l1",
    ) = VectorMemory(
        id = "memory_v2_operator_op1_l1_$id",
        ownerType = "operator",
        ownerId = "op1",
        sourceType = sourceType,
        sourceId = "s1",
        content = content,
        importance = 0.6,
        tags = "L1,event,PRIVATE_CHAT",
        visibility = "private",
        createdAt = createdAt,
        originMemoryId = id,
        lexicalScore = lexicalScore,
        lexicalMatchCount = if (lexicalScore > 0) 2 else 0,
        lexicalMaxTermIdf = if (lexicalScore > 0) 2.6 else 0.0,
    )

    @Test
    fun strongLexicalHitKeepsASlotAheadOfRecentGenericMemories() {
        val recent = (1L..4L).map { id -> candidate(id, "最近发生的普通事件 $id", now - id * 3_600_000L) }
        val strong = candidate(9, "用户养了一只叫团子的布偶猫", now - 20 * day, lexicalScore = 6.0)
        val selected = engine().selectPrivateInjection(
            candidates = listOf(strong) + recent,
            expanded = false,
            visualQuery = false,
            now = now,
        )
        assertTrue(
            selected.any { it.originMemoryId == 9L },
            "语义强命中必须进入注入：${selected.map { it.originMemoryId }}",
        )
        assertTrue(selected.size <= 4, "普通查询最多注入 4 条")
    }

    @Test
    fun recentGenericMemoriesStillFillRemainingSlots() {
        val recent = (1L..4L).map { id -> candidate(id, "最近发生的普通事件 $id", now - id * 3_600_000L) }
        val selected = engine().selectPrivateInjection(
            candidates = recent,
            expanded = false,
            visualQuery = false,
            now = now,
        )
        assertEquals(4, selected.size, "没有强命中时仍按原逻辑填满")
    }
}
