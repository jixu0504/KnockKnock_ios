package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.DisabledVectorStoreGateway
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T73 切片：召回引擎在最终注入选择后回调 originMemoryId（只做注入计数，不改 salience）。
 *
 * 回调必须在 dedupe/矛盾消解/同主题上限之后触发，且只含过滤 0、去重后的最终 id；
 * 不传回调时输出与旧版一致（金样契约不变）。
 */
class MemoryInjectionFeedbackTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun candidate(
        id: Long,
        level: String,
        content: String,
        createdAt: Long = now - day,
        originMemoryId: Long = id,
        vectorId: String = "memory_v2_operator_op1_${level.lowercase()}_$id",
    ) = VectorMemory(
        id = vectorId,
        ownerType = "operator",
        ownerId = "op1",
        sourceType = "memory_v2_${level.lowercase()}",
        sourceId = "s1",
        content = "[2026-03-14·私聊] $content",
        importance = 0.8,
        tags = "$level,preference_expression,PRIVATE_CHAT",
        visibility = "private",
        createdAt = createdAt,
        originMemoryId = originMemoryId,
        lexicalScore = 4.0,
        lexicalMatchCount = 2,
        lexicalMaxTermIdf = 2.5,
    )

    private fun engine(
        candidates: List<VectorMemory>,
        factKeys: Map<Long, String> = emptyMap(),
        onSelected: (suspend (List<Long>) -> Unit)? = null,
    ): MemoryRecallEngine {
        val settings = SettingsRepository(PromptTestSettings())
        return MemoryRecallEngine(
            settings = settings,
            memoryVectorService = MemoryVectorService(settings, DisabledVectorStoreGateway()),
            lexicalRecall = { _, _ -> candidates },
            factKeyResolver = { ids -> factKeys.filterKeys { it in ids } },
            onSelected = onSelected,
            nowMillis = { now },
        )
    }

    @Test
    fun callbackReceivesFinalIdsAfterSameFactDedupe() = runBlocking {
        val l1 = candidate(1, "L1", "用户喜欢吃辣（短期观察到）")
        val l2 = candidate(2, "L2", "用户长期喜欢吃辣（稳定偏好）", createdAt = now - 5 * day)
        val keys = mapOf(1L to "fact:positive:用户|prefer|川菜", 2L to "fact:positive:用户|prefer|川菜")
        val reported = mutableListOf<List<Long>>()
        val block = engine(listOf(l1, l2), keys, onSelected = { ids -> reported += ids })
            .buildPrivateChatMemoryContext("op1", "我喜欢吃什么口味", allowedSources = setOf("PRIVATE_CHAT"))

        assertTrue(block.contains("长期喜欢吃辣"), "L2 应被注入：$block")
        assertEquals(listOf(listOf(2L)), reported, "回调只应收到去重后实际注入的 id")
    }

    @Test
    fun callbackIdsAreFilteredAndDistinct() = runBlocking {
        // 同一 originMemoryId 的两条候选（不同向量 id/正文）：最终回调只应出现一次。
        val first = candidate(7, "L1", "用户养了一只叫团子的猫", vectorId = "v7a")
        val second = candidate(7, "L1", "用户周末带团子去洗澡", vectorId = "v7b")
        val reported = mutableListOf<List<Long>>()
        val block = engine(listOf(first, second), onSelected = { ids -> reported += ids })
            .buildPrivateChatMemoryContext("op1", "团子最近怎么样", allowedSources = setOf("PRIVATE_CHAT"))

        assertTrue(block.contains("团子"), "两条不同正文都应被注入：$block")
        assertEquals(listOf(listOf(7L)), reported, "同一 originMemoryId 在回调中必须去重")
    }

    @Test
    fun withoutCallbackRecallStillWorks() = runBlocking {
        val memory = candidate(3, "L1", "用户生日是三月十四日")
        val block = engine(listOf(memory))
            .buildPrivateChatMemoryContext("op1", "用户生日是哪天", allowedSources = setOf("PRIVATE_CHAT"))
        assertTrue(block.contains("三月十四日"), "无回调时召回输出不变：$block")
    }

    @Test
    fun emptySelectionDoesNotInvokeCallback() = runBlocking {
        val reported = mutableListOf<List<Long>>()
        val block = engine(emptyList(), onSelected = { ids -> reported += ids })
            .buildPrivateChatMemoryContext("op1", "用户生日是哪天", allowedSources = setOf("PRIVATE_CHAT"))
        assertTrue(block.isEmpty(), "无候选时注入块为空：$block")
        assertFalse(reported.any(), "没有最终选择时不应回调：$reported")
    }
}