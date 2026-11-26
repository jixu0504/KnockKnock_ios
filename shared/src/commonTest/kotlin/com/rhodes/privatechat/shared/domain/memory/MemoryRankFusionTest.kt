package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import com.rhodes.privatechat.shared.vector.VectorSearchRequest
import com.rhodes.privatechat.shared.vector.VectorStoreGateway
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T72：RRF 排名融合与同主题上限（MMR-lite）。
 *
 * RRF 只改排序（准入阈值不变）：`fused = 1/(60+vectorRank) + 1/(60+lexicalRank)`；
 * 同主题上限在 lineage 去重与矛盾消解之后执行，同一主题最多保留 2 条、保持已选顺序。
 */
class MemoryRankFusionTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun memory(
        id: Long,
        content: String,
        similarity: Double = 0.0,
        lexicalScore: Double = 0.0,
        level: String = "L1",
        type: String = "event",
        createdAt: Long = now - day,
    ) = VectorMemory(
        id = "v$id",
        ownerType = "operator",
        ownerId = "op1",
        sourceType = "memory_v2_${level.lowercase()}",
        sourceId = "s1",
        content = "[2026-03-14·私聊] $content",
        importance = 0.8,
        tags = "$level,$type,PRIVATE_CHAT",
        visibility = "private",
        createdAt = createdAt,
        originMemoryId = id,
        similarity = similarity,
        lexicalScore = lexicalScore,
        lexicalMatchCount = if (lexicalScore > 0.0) 2 else 0,
        lexicalMaxTermIdf = if (lexicalScore > 0.0) 2.5 else 0.0,
    )

    private class FakeVectorStore(private val hits: List<VectorMemory>) : VectorStoreGateway {
        override suspend fun search(request: VectorSearchRequest): List<VectorMemory> = hits
            .filter { it.sourceType in request.sourceTypes }
            .filter { it.createdAt >= request.minCreatedAt && it.createdAt <= request.maxCreatedAt }
            .sortedByDescending { it.similarity }

        override suspend fun upsert(memory: VectorMemory) = Unit
        override suspend fun listMemories(ownerType: String, ownerId: String) = emptyList<VectorMemory>()
        override suspend fun delete(memoryId: String) = Unit
        override suspend fun deleteBySource(ownerType: String, ownerId: String, sourceId: String) = Unit
        override suspend fun clearOwnerMemory(ownerType: String, ownerId: String) = Unit
        override suspend fun clearAllMemories() = Unit
    }

    private fun engine(
        vectorHits: List<VectorMemory> = emptyList(),
        lexicalHits: List<VectorMemory> = emptyList(),
        factKeys: Map<Long, String> = emptyMap(),
    ): MemoryRecallEngine {
        val settings = SettingsRepository(PromptTestSettings())
        return MemoryRecallEngine(
            settings = settings,
            memoryVectorService = MemoryVectorService(settings, FakeVectorStore(vectorHits)),
            lexicalRecall = { _, _ -> lexicalHits },
            factKeyResolver = { ids -> factKeys.filterKeys { it in ids } },
            nowMillis = { now },
        )
    }

    @Test
    fun rrfRanksDualChannelCandidateAboveStrongerVectorOnly() = runBlocking {
        // 向量更强（0.9）但只命中一个通道；双通道候选向量较弱（0.3）却同时被词法强命中。
        val vectorOnly = memory(2, "用户生日是三月十四日", similarity = 0.9)
        val dualChannel = memory(1, "用户生日那天一起吃了草莓蛋糕", similarity = 0.3, lexicalScore = 3.0)
        val block = engine(listOf(vectorOnly, dualChannel), lexicalHits = listOf(dualChannel))
            .buildPrivateChatMemoryContext("op1", "用户生日是哪天", allowedSources = setOf("PRIVATE_CHAT"))

        val dualIndex = block.indexOf("草莓蛋糕")
        val vectorIndex = block.indexOf("三月十四日")
        assertTrue(dualIndex >= 0 && vectorIndex >= 0, "两条候选都应通过准入：$block")
        assertTrue(
            dualIndex < vectorIndex,
            "RRF 应把双通道命中的候选排在只有向量更强的候选之前，实际：$block",
        )
    }

    @Test
    fun sameTopicInjectionIsCappedAtTwo() = runBlocking {
        val seaside = memory(1, "用户喜欢去海边", similarity = 0.5, lexicalScore = 4.0, type = "preference_expression")
        val hiking = memory(2, "用户喜欢去爬山", similarity = 0.5, lexicalScore = 4.0, type = "preference_expression")
        val coffee = memory(3, "用户喜欢喝咖啡", similarity = 0.5, lexicalScore = 4.0, type = "preference_expression")
        val keys = mapOf(
            1L to "fact:positive:用户|prefer|海边",
            2L to "fact:positive:用户|prefer|爬山",
            3L to "fact:positive:用户|prefer|咖啡",
        )
        val block = engine(listOf(seaside, hiking, coffee), lexicalHits = listOf(seaside, hiking, coffee), factKeys = keys)
            .buildPrivateChatMemoryContext("op1", "最近我喜欢做什么", allowedSources = setOf("PRIVATE_CHAT"))

        val lines = block.lines().filter { it.startsWith("- ") }
        assertEquals(2, lines.size, "同一主题最多保留 2 条，实际：$block")
        assertTrue(lines[0].contains("海边"), "保持已选顺序，第一条应是海边：$block")
        assertTrue(lines[1].contains("爬山"), "保持已选顺序，第二条应是爬山：$block")
        assertFalse(block.contains("咖啡"), "第三条同主题候选应被上限裁掉：$block")
    }
}