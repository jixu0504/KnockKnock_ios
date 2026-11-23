package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收 #4：召回限额/优先级矩阵（数值与 oracle MemoryV2Pipeline.kt:533-537/:576-586/:592-605/
 * :607-633/:714-731/:1239-1245、UnifiedMemoryContext、MemoryInjection 逐项对照）。
 */
class MemoryRecallMatrixTest {

    private val fixtures = MemoryGoldenEnv.load()
    private val now = fixtures.now

    private fun settings(mode: String = "balanced"): SettingsRepository =
        SettingsRepository(PromptTestSettings()).apply { memoryRecallMode = mode }

    private fun engine(mode: String = "balanced"): Pair<MemoryRecallEngine, FixtureVectorStore> {
        val store = FixtureVectorStore(fixtures.vectors)
        val service = MemoryVectorService(settings(mode), store, { FixedEmbeddingGateway() })
        return MemoryRecallEngine(settings(mode), service) { now } to store
    }

    @Test
    fun privateRecallBudgetMatrixMatchesOracle() {
        val (engine, _) = engine()
        fun budget(mode: String) = engine.privateRecallBudget(mode)
        assertEquals(listOf(80, 20, 30, 20, 20), budget("fast").let {
            listOf(it.recentL1, it.olderL1, it.l2, it.l3, it.manual)
        })
        assertEquals(listOf(150, 50, 60, 30, 30), budget("balanced").let {
            listOf(it.recentL1, it.olderL1, it.l2, it.l3, it.manual)
        })
        assertEquals(listOf(260, 100, 100, 40, 40), budget("deep").let {
            listOf(it.recentL1, it.olderL1, it.l2, it.l3, it.manual)
        })
        assertEquals(budget("balanced").let { listOf(it.recentL1, it.olderL1, it.l2, it.l3, it.manual) }, budget("unknown").let {
            listOf(it.recentL1, it.olderL1, it.l2, it.l3, it.manual)
        })
    }

    @Test
    fun ownerCandidatePoolAndMinScoreMatrixMatchesOracle() = runBlocking {
        val expectations = listOf(
            Triple("fast", 100, 0.24),
            Triple("balanced", 160, 0.16),
            Triple("deep", 700, 0.16),
        )
        for ((mode, pool, minScore) in expectations) {
            val store = FixtureVectorStore(fixtures.vectors)
            val settings = settings(mode)
            val service = MemoryVectorService(settings, store, { FixedEmbeddingGateway() })
            val engine = MemoryRecallEngine(settings, service) { now }
            engine.buildOwnerMemoryContext(
                "operator", "op1", 4, 2, 1, "我们之间的约定",
                setOf("PRIVATE_CHAT", "GROUP_CHAT", "MOMENT", "MOMENT_COMMENT", "DIARY", "MANUAL_MEMORY"),
            )
            assertEquals(true, store.requests.isNotEmpty())
            assertTrue(store.requests.all { it.candidateLimit == pool }, "$mode 候选池上限应为 $pool")
            assertTrue(store.requests.all { it.minScore == minScore }, "$mode minScore 应为 $minScore")
            if (mode == "fast") {
                assertTrue(store.requests.all { it.minCreatedAt == now - 30L * 86_400_000L }, "fast 只取近 30 天")
            } else {
                assertTrue(store.requests.all { it.minCreatedAt == 0L })
            }
        }
    }

    @Test
    fun minimumPrivateSimilarityMatrixMatchesOracle() {
        val (engine, _) = engine()
        val reviewQuery = "还记得上次那件事吗"
        val plainQuery = "随便聊聊吧"
        assertEquals(0.14, engine.minimumPrivateSimilarity("memory_v2_l1", "agreement_commitment", reviewQuery))
        assertEquals(0.22, engine.minimumPrivateSimilarity("anchor_vision", "event", plainQuery))
        assertEquals(0.22, engine.minimumPrivateSimilarity("memory_v2_l1", "emotion_state", plainQuery))
        assertEquals(0.22, engine.minimumPrivateSimilarity("memory_v2_l1", "event", plainQuery))
        assertEquals(0.18, engine.minimumPrivateSimilarity("memory_v2_l3", "evaluation_opinion", plainQuery))
        assertEquals(0.18, engine.minimumPrivateSimilarity("manual_memory", "preference_expression", plainQuery))
        assertEquals(0.18, engine.minimumPrivateSimilarity("memory_v2_l2", "care_reminder", plainQuery))
        assertEquals(0.20, engine.minimumPrivateSimilarity("memory_v2_l2", "event", plainQuery))
    }

    @Test
    fun privateRecallScoreFormulaMatchesOracle() {
        val (engine, _) = engine()
        val recentL1 = VectorMemory(
            id = "x", ownerType = "operator", ownerId = "op1", sourceType = "memory_v2_l1",
            sourceId = "s", content = "c", importance = 0.8, createdAt = now - 15L * 86_400_000L, similarity = 0.5,
        )
        // similarity*0.70 + importance*0.15 + recency*0.10 + typeBonus(0) = 0.35+0.12+0.05 = 0.52
        assertEquals(0.52, engine.privateRecallScore(recentL1, "event", now), 1e-9)
        assertEquals(0.57, engine.privateRecallScore(recentL1, "care_reminder", now), 1e-9)
        assertEquals(0.55, engine.privateRecallScore(recentL1, "preference_expression", now), 1e-9)
        val l2 = recentL1.copy(sourceType = "memory_v2_l2", createdAt = now - 45L * 86_400_000L)
        // l2 recency: (45-45)/45 = 0
        assertEquals(0.35 + 0.12 + 0.0, engine.privateRecallScore(l2, "event", now), 1e-9)
        val l3 = recentL1.copy(sourceType = "memory_v2_l3", createdAt = now)
        assertEquals(0.35 + 0.12 + 0.05, engine.privateRecallScore(l3, "event", now), 1e-9)
    }

    @Test
    fun selectPrivateInjectionCapsMatchOracle() {
        val (engine, _) = engine()
        fun memory(id: String, sourceType: String, type: String, ageDays: Long, content: String = id): VectorMemory =
            VectorMemory(
                id = id, ownerType = "operator", ownerId = "op1", sourceType = sourceType, sourceId = "s",
                content = content, importance = 0.5, tags = "L1,$type,PRIVATE_CHAT",
                createdAt = now - ageDays * 86_400_000L, similarity = 0.8,
            )
        val candidates = listOf(
            memory("l1a", "memory_v2_l1", "event", 1),
            memory("l1b", "memory_v2_l1", "event", 2),
            memory("l1c", "memory_v2_l1", "event", 3),
            memory("l1d", "memory_v2_l1", "event", 40),
            memory("l2", "memory_v2_l2", "event", 10),
            memory("l3", "memory_v2_l3", "event", 10),
            memory("manual", "manual_memory", "preference_expression", 10),
            memory("vision", "anchor_vision", "event", 2),
            memory("protected", "memory_v2_l2", "care_reminder", 5),
        )
        val regular = engine.selectPrivateInjection(candidates, expanded = false, visualQuery = false, now = now)
        assertEquals(4, regular.size)
        assertEquals(2, regular.count { it.sourceType == "memory_v2_l1" && now - it.createdAt <= 30L * 86_400_000L })
        assertEquals(2, regular.count { it.sourceType in setOf("memory_v2_l2", "memory_v2_l3", "manual_memory") }) // 含 protected L2 槽位

        val expanded = engine.selectPrivateInjection(candidates, expanded = true, visualQuery = false, now = now)
        assertEquals(6, expanded.size)
        assertEquals(3, expanded.count { it.sourceType == "memory_v2_l1" && now - it.createdAt <= 30L * 86_400_000L })

        val visual = engine.selectPrivateInjection(candidates, expanded = false, visualQuery = true, now = now)
        assertEquals(1, visual.count { it.sourceType == "anchor_vision" }, "视觉问句必须保留 1 条视觉锚点")
        assertTrue(visual.size <= 4)
    }

    @Test
    fun relationshipRecallPolicyMatrixMatchesOracle() {
        val (engine, _) = engine()
        assertNull(engine.relationshipRecallPolicy(0))
        assertNull(engine.relationshipRecallPolicy(19))
        assertEquals(1 to 80, engine.relationshipRecallPolicy(20)!!.let { it.candidateLimit to it.minImportance })
        assertEquals(1 to 80, engine.relationshipRecallPolicy(39)!!.let { it.candidateLimit to it.minImportance })
        assertEquals(2 to 65, engine.relationshipRecallPolicy(40)!!.let { it.candidateLimit to it.minImportance })
        assertEquals(2 to 65, engine.relationshipRecallPolicy(59)!!.let { it.candidateLimit to it.minImportance })
        assertEquals(3 to 50, engine.relationshipRecallPolicy(60)!!.let { it.candidateLimit to it.minImportance })
        assertEquals(3 to 50, engine.relationshipRecallPolicy(79)!!.let { it.candidateLimit to it.minImportance })
        assertEquals(5 to 35, engine.relationshipRecallPolicy(80)!!.let { it.candidateLimit to it.minImportance })
        assertEquals(5 to 35, engine.relationshipRecallPolicy(100)!!.let { it.candidateLimit to it.minImportance })
    }

    @Test
    fun privateChatAllowedSourcesFollowsInjectionSwitches() {
        val settings = SettingsRepository(PromptTestSettings())
        val service = MemoryVectorService(settings, FixtureVectorStore(fixtures.vectors), { FixedEmbeddingGateway() })
        val engine = MemoryRecallEngine(settings, service) { now }
        assertEquals(
            setOf("PRIVATE_CHAT", "GROUP_CHAT", "MOMENT", "MOMENT_COMMENT", "DIARY", "MANUAL_MEMORY"),
            engine.privateChatAllowedSources(),
        )
        settings.setMemoryInjectionAllowed("private_chat", "GROUP_CHAT", false)
        settings.setMemoryInjectionAllowed("private_chat", "MOMENT", false)
        assertEquals(setOf("PRIVATE_CHAT", "MOMENT_COMMENT", "DIARY", "MANUAL_MEMORY"), engine.privateChatAllowedSources())
    }

    @Test
    fun injectionLimitsAndTrimMatchOracle() {
        // contextBlockLimit: economy=1000 / full=5000 / 其他=2500，weight 放大，下限 200
        assertEquals(1_000, MemoryInjection.contextBlockLimit("economy"))
        assertEquals(5_000, MemoryInjection.contextBlockLimit("full"))
        assertEquals(2_500, MemoryInjection.contextBlockLimit("standard"))
        assertEquals(5_000, MemoryInjection.contextBlockLimit("standard", weight = 2))
        assertEquals(200, MemoryInjection.contextBlockLimit("economy", weight = 0))
        // trimContextBlock: 按行取，超限即止
        assertEquals("a\nb", MemoryInjection.trimContextBlock("a\nb\nccc", 4))
        assertEquals("abc", MemoryInjection.trimContextBlock("abc", 0))
        assertEquals("长行", MemoryInjection.trimContextBlock("长行内容很长", 2))
    }

    @Test
    fun memoryInjectionAssemblePrivateOrderAndMerging() {
        val context = MemoryInjection.assemblePrivate(
            MemoryInjection.PrivateBlocks(
                groupContext = "近况",
                shortTermSummary = "摘要",
                stableImpression = "印象",
                personalMemory = "【与当前话题相关的经历】\n- 个人",
                relationshipMemory = "【与当前话题相关的关系网经历】\n- 关系",
                publicMemory = "【与当前话题相关的经历】\n- 公开",
                knowledgeBase = "【知识库资料开始】\n知识\n【知识库资料结束】",
            ),
            contextBlockLimit = 1_000,
        )
        assertEquals(listOf("GROUP_CONTEXT", "LONG_TERM_IMPRESSION", "PERSONAL_MEMORY", "RELATIONSHIP_MEMORY", "PUBLIC_MEMORY", "__KNOWLEDGE_BASE_CONTEXT"), context.orderedBlocks.map { it.first })
        assertEquals("近况", context.replacements["GROUP_CONTEXT"])
        assertEquals("印象", context.replacements["LONG_TERM_IMPRESSION"])
        assertEquals("摘要", context.replacements["SHORT_TERM_SUMMARY"])
        // 注意 oracle mergeBlocks 会丢弃「纯【标题】行」（normalize 后为空），保留内容行
        assertTrue(context.memoryV2Context.contains("- 个人"))
        assertTrue(context.memoryV2Context.contains("- 关系"))
        assertTrue(context.memoryV2Context.contains("- 公开"))
        assertTrue(!context.memoryV2Context.contains("【公开动态与评论】"), "纯标题行被 mergeBlocks 过滤（oracle 行为）")
        assertTrue(context.mergedRecallBlock.contains("- 个人") && context.mergedRecallBlock.contains("- 关系"))
        assertTrue(context.orderedBlocks.first { it.first == "PUBLIC_MEMORY" }.second.contains("【公开动态与评论】"))
        assertEquals(context.knowledgeBaseContext, context.replacements["__KNOWLEDGE_BASE_CONTEXT"])
        // 全空 → "无"
        assertEquals("无", MemoryInjection.assemblePrivate(MemoryInjection.PrivateBlocks(), 2_500).memoryV2Context)
    }

    @Test
    fun timeSummaryAndVisualQueryDetection() {
        assertTrue(UnifiedMemoryContext.shouldIncludeTimeSummary("还记得上次的事吗"))
        assertTrue(UnifiedMemoryContext.shouldIncludeTimeSummary("最近怎么样"))
        assertTrue(UnifiedMemoryContext.shouldIncludeTimeSummary("回顾一下以前"))
        assertTrue(!UnifiedMemoryContext.shouldIncludeTimeSummary("随便聊聊吧"))
        assertTrue(!UnifiedMemoryContext.shouldIncludeTimeSummary("   "))
        val (engine, _) = engine()
        assertTrue(engine.isVisualRecallQuery("这张照片里是什么"))
        assertTrue(engine.isVisualRecallQuery("截图给我看看"))
        assertTrue(!engine.isVisualRecallQuery("今天心情不错"))
    }

    @Test
    fun publicMemoryAllowedSourcesUsesMomentSwitches() {
        val settings = SettingsRepository(PromptTestSettings())
        val service = MemoryVectorService(settings, FixtureVectorStore(fixtures.vectors), { FixedEmbeddingGateway() })
        val engine = MemoryRecallEngine(settings, service) { now }
        assertEquals(setOf(MemorySourceKind.MOMENT.name, MemorySourceKind.MOMENT_COMMENT.name), engine.publicMemoryAllowedSources())
        settings.setMemoryInjectionAllowed("private_chat", "MOMENT_COMMENT", false)
        assertEquals(setOf(MemorySourceKind.MOMENT.name), engine.publicMemoryAllowedSources())
    }
}
