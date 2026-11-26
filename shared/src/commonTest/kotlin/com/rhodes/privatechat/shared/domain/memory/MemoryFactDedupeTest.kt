package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.DisabledVectorStoreGateway
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** X-03：同一事实的 L1/L2/L3 多版本只注入一条（保留更高层级）。 */
class MemoryFactDedupeTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun candidate(
        id: Long,
        level: String,
        content: String,
        createdAt: Long,
    ) = VectorMemory(
        id = "memory_v2_operator_op1_${level.lowercase()}_$id",
        ownerType = "operator",
        ownerId = "op1",
        sourceType = "memory_v2_${level.lowercase()}",
        sourceId = "s1",
        content = "[2026-03-14·私聊] $content",
        importance = 0.8,
        tags = "$level,preference_expression,PRIVATE_CHAT",
        visibility = "private",
        createdAt = createdAt,
        originMemoryId = id,
        lexicalScore = 4.0,
        lexicalMatchCount = 2,
        lexicalMaxTermIdf = 2.5,
    )

    private fun engine(
        candidates: List<VectorMemory>,
        keys: Map<Long, String>,
    ): MemoryRecallEngine {
        val settings = SettingsRepository(PromptTestSettings())
        return MemoryRecallEngine(
            settings = settings,
            memoryVectorService = MemoryVectorService(settings, DisabledVectorStoreGateway()),
            lexicalRecall = { _, _ -> candidates },
            factKeyResolver = { ids -> keys.filterKeys { it in ids } },
            nowMillis = { now },
        )
    }

    @Test
    fun sameFactVersionsKeepHighestLevelOnly() = runBlocking {
        val l1 = candidate(1, "L1", "用户喜欢吃辣（短期观察到）", now - day)
        val l2 = candidate(2, "L2", "用户长期喜欢吃辣（稳定偏好）", now - 5 * day)
        val block = engine(listOf(l1, l2), mapOf(1L to "fact:positive:用户|prefer|川菜", 2L to "fact:positive:用户|prefer|川菜"))
            .buildPrivateChatMemoryContext("op1", "我喜欢吃什么口味", allowedSources = setOf("PRIVATE_CHAT"))
        assertTrue(block.contains("长期喜欢吃辣"), "应注入 L2：$block")
        assertFalse(block.contains("短期观察到"), "同一事实的 L1 不应重复注入：$block")
    }

    @Test
    fun contradictoryPolarityOnlyInjectsNewerVersion() = runBlocking {
        val olderPositive = candidate(1, "L1", "用户喜欢吃辣", now - 10 * day)
        val newerNegative = candidate(2, "L1", "用户不喜欢吃辣", now - day)
        val block = engine(
            listOf(olderPositive, newerNegative),
            mapOf(1L to "fact:positive:用户|prefer|辣", 2L to "fact:negative:用户|prefer|辣"),
        ).buildPrivateChatMemoryContext("op1", "我喜欢吃辣吗", allowedSources = setOf("PRIVATE_CHAT"))
        assertTrue(block.contains("用户不喜欢吃辣"), "应保留较新的否定事实：$block")
        assertFalse(block.contains("用户喜欢吃辣"), "同轮不得同时注入相反极性：$block")
    }

    @Test
    fun differentFactsAreBothInjected() = runBlocking {
        val spicy = candidate(1, "L1", "用户喜欢吃辣", now - day)
        val coriander = candidate(2, "L1", "用户不喜欢吃香菜", now - 2 * day)
        val block = engine(listOf(spicy, coriander), mapOf(1L to "fact:positive:用户|prefer|辣", 2L to "fact:negative:用户|prefer|香菜"))
            .buildPrivateChatMemoryContext("op1", "我喜欢吃什么", allowedSources = setOf("PRIVATE_CHAT"))
        assertTrue(block.contains("喜欢吃辣") && block.contains("不喜欢吃香菜"), "不同事实都要保留：$block")
    }
}
