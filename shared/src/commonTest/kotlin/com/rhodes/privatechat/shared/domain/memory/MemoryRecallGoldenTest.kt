package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.vector.LocalHashEmbeddingGateway
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验收 #2（召回结果金样）+ #3（本地哈希嵌入金样）。
 *
 * 金样由 `ios-port/golden/memory/gen_golden.sh` 用 `v1.14.27-android-baseline` tag 的 Android
 * oracle 源码（MemoryV2Pipeline.kt / UnifiedMemoryContext.kt / LocalHashEmbeddingGateway.kt）跑
 * 同一 fixtures 生成；本测试用迁移后的 shared 实现跑同一 fixtures 逐字节比对。
 * 任何字节差异的合法修复路径 = 重新用 oracle 代码生成金样（禁止手改、禁止用 shared 自证）。
 */
class MemoryRecallGoldenTest {

    private val fixtures = MemoryGoldenEnv.load()
    private val json = Json { prettyPrint = false }

    private fun golden(name: String): String = MemoryGoldenEnv.golden("recall/$name.md")

    private fun assertGolden(name: String, actual: String) {
        assertEquals(golden(name), actual, "金样不一致：$name（须由 baseline oracle 重新生成，禁止手改）")
    }

    private fun caseAt(name: String): GoldenCase =
        fixtures.cases.firstOrNull { it.name == name } ?: error("fixtures 缺少用例 $name")

    @Test
    fun privateChatMemoryContextMatchesOracleGolden() = runBlocking {
        for (name in listOf("private_chat_balanced", "private_chat_review", "private_chat_visual")) {
            val case = caseAt(name)
            val (engine, _) = MemoryGoldenEnv.engine(fixtures)
            val output = engine.buildPrivateChatMemoryContext(
                operatorId = "op1",
                query = case.query,
                allowedSources = case.allowedSources.toSet(),
                allowPrivateVisualRecall = case.allowVisual,
            )
            assertGolden(name, output)
        }
    }

    @Test
    fun ownerMemoryContextMatchesOracleGoldenForAllRecallModes() = runBlocking {
        for (name in listOf("owner_balanced", "owner_fast", "owner_deep")) {
            val case = caseAt(name)
            val (engine, _) = MemoryGoldenEnv.engine(fixtures, case.mode)
            val output = engine.buildOwnerMemoryContext(
                ownerType = case.ownerType,
                ownerId = case.ownerId,
                limitL1 = case.limitL1,
                limitL2 = case.limitL2,
                limitL3 = case.limitL3,
                query = case.query,
                allowedSources = case.allowedSources.toSet(),
            )
            assertGolden(name, output)
        }
    }

    @Test
    fun publicMemoryContextMatchesOracleGolden() = runBlocking {
        val case = caseAt("public_memory")
        val (engine, _) = MemoryGoldenEnv.engine(fixtures, case.mode)
        val output = engine.buildPublicMemoryContext(
            query = case.query,
            limit = case.limit,
            allowedSources = case.allowedSources.toSet(),
        )
        assertGolden("public_memory", output)
    }

    @Test
    fun stableImpressionMatchesOracleGolden() {
        val case = caseAt("stable_impression")
        val (engine, _) = MemoryGoldenEnv.engine(fixtures)
        val items = fixtures.stableItems
            .filter { it.sourceKind == com.rhodes.privatechat.shared.model.MemorySourceKind.PRIVATE_CHAT }
            .take(case.limit)
        assertGolden("stable_impression", engine.buildStableImpressionText(items))
    }

    @Test
    fun relationshipContextMatchesOracleGolden() {
        val (engine, _) = MemoryGoldenEnv.engine(fixtures)
        assertGolden("relationship", engine.buildRelationshipContextText(fixtures.relationshipCandidates))
    }

    @Test
    fun mergeBlocksMatchesOracleGolden() {
        val case = caseAt("merge_blocks")
        assertGolden("merge_blocks", UnifiedMemoryContext.mergeBlocks(case.maxChars, *case.blocks.toTypedArray()))
    }

    @Test
    fun injectionAssemblyMatchesOracleGolden() {
        val case = caseAt("injection_private")
        val blocks = case.injectionBlocks ?: error("injection_private 缺少 blocks")
        val context = MemoryInjection.assemblePrivate(
            MemoryInjection.PrivateBlocks(
                groupContext = blocks.groupContext,
                shortTermSummary = blocks.shortTermSummary,
                stableImpression = blocks.stableImpression,
                personalMemory = blocks.personalMemory,
                relationshipMemory = blocks.relationshipMemory,
                publicMemory = blocks.publicMemory,
                knowledgeBase = blocks.knowledgeBase,
            ),
            case.contextBlockLimit,
        )
        assertGolden("injection_private", listOf(
            context.memoryV2Context,
            context.knowledgeBaseContext,
            context.replacements.getValue("LONG_TERM_IMPRESSION"),
            context.replacements.getValue("SHORT_TERM_SUMMARY"),
            context.replacements.getValue("GROUP_CONTEXT"),
        ).joinToString("\n"))

        // 注入顺序（契约 16）：群聊近况 → 稳定印象 → 个人 → 关系网 → 公开 → 知识库
        assertEquals(
            listOf("GROUP_CONTEXT", "LONG_TERM_IMPRESSION", "PERSONAL_MEMORY", "RELATIONSHIP_MEMORY", "PUBLIC_MEMORY", "__KNOWLEDGE_BASE_CONTEXT"),
            context.orderedBlocks.map { it.first },
        )
    }

    @Test
    fun localHashEmbeddingMatchesOracleGoldenBitForBit() = runBlocking {
        val reader = Json { ignoreUnknownKeys = true }
        val goldenVectors: List<List<Double>> = reader.decodeFromString(
            ListSerializer(ListSerializer(Double.serializer())),
            MemoryGoldenEnv.golden("embedding_local.json"),
        )
        val gateway = LocalHashEmbeddingGateway(384)
        assertEquals(fixtures.embeddingTexts.size, goldenVectors.size, "嵌入金样条数不一致")
        fixtures.embeddingTexts.forEachIndexed { index, text ->
            val actual = gateway.embed(text)
            val expected = goldenVectors[index]
            assertEquals(384, actual.size, "维度")
            assertTrue(
                expected.size == actual.size && expected.indices.all { expected[it] == actual[it] },
                "第 ${index + 1} 条文本的 384 维哈希嵌入与 Android oracle 金样不是逐位一致（FNV-1a 跨平台语义漂移，禁止静默改哈希）",
            )
        }
    }
}
