package com.rhodes.privatechat.shared.memory.oracle

import com.rhodes.privatechat.shared.domain.memory.FixtureVectorStore
import com.rhodes.privatechat.shared.domain.memory.FixedEmbeddingGateway
import com.rhodes.privatechat.shared.domain.memory.GoldenFixtures
import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 金样生成器（一次性 harness，由 ios-port/golden/memory/gen_golden.sh 注入后运行、运行完删除）。
 *
 * 生成来源纪律：本轮金样的**被测逻辑**全部来自 `v1.14.27-android-baseline` tag 的 Android oracle
 * 源码——`MemoryV2Pipeline.kt`（本包 oracle 副本，整文件逐字节取自 tag，仅 package/import 调整 +
 * repository 参数置空的 `!!` 机械替换，召回路径不触碰它）、`UnifiedMemoryContext.kt`（baseline 副本）、
 * `LocalHashEmbeddingGateway.kt`（baseline 副本）。stable/relationship/injection 三处的 oracle 逻辑在
 * ChatViewModel.kt（:3163-3172/:3236-3264）与 SharedUtils.kt（:720-745、:683-694），
 * 因此在本文件内以 **逐行复制** 的局部函数复刻（下方标注 oracle 行号），只把输入换成 fixtures。
 * 任何字节差异的合法修复 = 重跑本脚本，禁止手改金样。
 */
class MemoryGoldenGenTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val embeddedGoldens = Json { prettyPrint = false }

    private fun outDir(): File {
        val env = System.getenv("MEMORY_GOLDEN_DIR")
        val candidates = buildList {
            if (!env.isNullOrBlank()) add(File(env))
            add(File("../ios-port/golden/memory"))
            add(File("ios-port/golden/memory"))
        }
        return candidates.firstOrNull { File(it, "fixtures.json").exists() }
            ?: error("找不到 fixtures.json（MEMORY_GOLDEN_DIR=${env}）")
    }

    private fun settings(fixtures: GoldenFixtures): SettingsRepository {
        val settings = SettingsRepository(PromptTestSettings())
        fixtures.settings["memory_recall_mode"]?.let { settings.memoryRecallMode = it }
        fixtures.settings["memory_recall_candidate_limit"]?.let { settings.memoryRecallCandidateLimit = it.toInt() }
        fixtures.settings["clean_days_memory_items"]?.let { settings.cleanDaysMemoryItems = it.toInt() }
        fixtures.settings["memory_v2_enabled"]?.let { settings.memoryV2Enabled = it.toBoolean() }
        return settings
    }

    private fun writeRecalls(out: File, fixtures: GoldenFixtures) = runBlocking {
        fun pipeline(fixtures: GoldenFixtures, modeOverride: String = ""): Pair<MemoryV2Pipeline, FixtureVectorStore> {
            val settings = settings(fixtures)
            if (modeOverride.isNotBlank()) settings.memoryRecallMode = modeOverride
            val store = FixtureVectorStore(fixtures.vectors)
            val service = MemoryVectorService(settings, store, { FixedEmbeddingGateway() })
            return MemoryV2Pipeline(
                repository = null,
                settings = settings,
                aiService = com.rhodes.privatechat.shared.network.AIService(),
                memoryVectorService = service,
                userNicknameProvider = { "用户" },
            ) to store
        }
        val recallDir = File(out, "recall").apply { mkdirs() }
        for (case in fixtures.cases) {
            val output: String = when (case.kind) {
                "private_chat" -> {
                    val (pipeline, _) = pipeline(fixtures)
                    pipeline.buildPrivateChatMemoryContext(
                        operatorId = "op1",
                        query = case.query,
                        allowedSources = case.allowedSources.toSet(),
                        allowPrivateVisualRecall = case.allowVisual,
                    )
                }
                "owner" -> {
                    val (pipeline, _) = pipeline(fixtures, case.mode)
                    pipeline.buildOwnerMemoryContext(
                        ownerType = case.ownerType,
                        ownerId = case.ownerId,
                        limitL1 = case.limitL1,
                        limitL2 = case.limitL2,
                        limitL3 = case.limitL3,
                        query = case.query,
                        allowedSources = case.allowedSources.toSet(),
                    )
                }
                "public" -> {
                    val (pipeline, _) = pipeline(fixtures, case.mode)
                    pipeline.buildPublicMemoryContext(
                        query = case.query,
                        limit = case.limit,
                        allowedSources = case.allowedSources.toSet(),
                    )
                }
                "stable" -> oracleStableImpression(fixtures, case.limit)
                "relationship" -> oracleRelationshipContext(fixtures.relationshipCandidates)
                "merge" -> UnifiedMemoryContext.mergeBlocks(case.maxChars, *case.blocks.toTypedArray())
                "injection" -> {
                    val blocks = case.injectionBlocks ?: error("injection case 缺 blocks")
                    oraclePrivateInjection(
                        OracleInjectionBlocks(
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
                }
                else -> error("未知 case kind: ${case.kind}")
            }
            File(recallDir, case.name + ".md").writeText(output)
            println("golden generated: recall/${case.name}.md (${output.length} chars)")
        }
        // 本地哈希嵌入金样：baseline 的 LocalHashEmbeddingGateway.kt 副本（384 维，FNV-1a）
        val gateway = OracleLocalHashEmbeddingGateway(384)
        val vectors = fixtures.embeddingTexts.map { gateway.embed(it) }
        File(out, "embedding_local.json").writeText(
            embeddedGoldens.encodeToString(ListSerializer(ListSerializer(Double.serializer())), vectors),
        )
        println("golden generated: embedding_local.json (${vectors.size} texts)")
    }

    /**
     * oracle MemoryV2Pipeline.kt:686-695（buildPrivateStableImpression）的纯格式化部分逐行复制：
     * items 已由 fixtures 模拟 `getActiveMemoryItemsByLevel(...).filter { sourceKind==PRIVATE_CHAT }.take(limit)`。
     */
    private fun oracleStableImpression(fixtures: GoldenFixtures, limit: Int): String {
        val items = fixtures.stableItems
            .filter { it.sourceKind == com.rhodes.privatechat.shared.model.MemorySourceKind.PRIVATE_CHAT }
            .take(limit)
        return items.joinToString("\n") { "- ${it.content.take(180)}" }
            .takeIf { it.isNotBlank() }
            ?.let { "【该角色对用户的长期稳定印象】\n$it" }
            .orEmpty()
    }

    /** oracle MemoryV2Pipeline.kt:1213-1215（normalizeForDedup）逐行复制。 */
    private fun oracleNormalizeForDedup(type: String, content: String): String =
        type + ":" + content.lowercase().filterNot { it.isWhitespace() || it in "，。！？；,.!?;：:" }

    /** oracle MemoryV2Pipeline.kt:660-681（buildRelationshipPrivateMemoryContext 的排序/去重/包裹部分）逐行复制。 */
    private fun oracleRelationshipContext(candidates: List<VectorMemory>): String {
        val lines = candidates
            .sortedWith(compareByDescending<VectorMemory> { it.importance }.thenByDescending { it.createdAt })
            .distinctBy { oracleNormalizeForDedup("relationship", it.content) }
            .take(3)
            .map { "- ${it.content.take(180)}" }
        return lines.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?.let { "【与当前话题相关的关系网经历】\n$it" }
            .orEmpty()
    }

    private data class OracleInjectionBlocks(
        val groupContext: String,
        val shortTermSummary: String,
        val stableImpression: String,
        val personalMemory: String,
        val relationshipMemory: String,
        val publicMemory: String,
        val knowledgeBase: String,
    )

    /** oracle SharedUtils.kt:720-732（trimContextBlock）逐行复制。 */
    private fun oracleTrimContextBlock(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        val lines = text.lines().filter { it.isNotBlank() }
        val picked = mutableListOf<String>()
        var used = 0
        for (line in lines) {
            val next = line.take(maxChars)
            if (used + next.length + 1 > maxChars) break
            picked.add(next)
            used += next.length + 1
        }
        return picked.joinToString("\n").ifBlank { text.take(maxChars) }
    }

    /**
     * oracle ChatViewModel.kt:3163-3172（memoryV2Context / recallMemoryContext 合并）+
     * :3236-3264 中记忆/知识库 replacements 键值，逐行复制。
     * 输出 = [MEMORY_V2_CONTEXT, __KNOWLEDGE_BASE_CONTEXT, LONG_TERM_IMPRESSION, SHORT_TERM_SUMMARY, GROUP_CONTEXT]
     * joinToString("\n")，与 shared MemoryInjection.assemblePrivate 的同名输出比对。
     */
    private fun oraclePrivateInjection(blocks: OracleInjectionBlocks, contextBlockLimit: Int): String {
        val memoryV2Context = oracleTrimContextBlock(
            UnifiedMemoryContext.mergeBlocks(
                maxChars = contextBlockLimit,
                blocks.personalMemory,
                blocks.relationshipMemory,
            ).ifBlank { "无" },
            contextBlockLimit,
        )
        val publicBlock = blocks.publicMemory.takeIf { it != "无" }?.let { "【公开动态与评论】\n$it" }.orEmpty()
        val recallMemoryContext = UnifiedMemoryContext.mergeBlocks(
            maxChars = contextBlockLimit * 2,
            memoryV2Context,
            publicBlock,
        )
        return listOf(
            recallMemoryContext,
            blocks.knowledgeBase.ifBlank { "无" },
            blocks.stableImpression.ifBlank { "无" },
            blocks.shortTermSummary.ifBlank { "无" },
            blocks.groupContext.ifBlank { "无" },
        ).joinToString("\n")
    }

    @Test
    fun generateMemoryGoldens() {
        val out = outDir()
        val fixtures = json.decodeFromString<GoldenFixtures>(File(out, "fixtures.json").readText())
        OracleFixedClock.now = fixtures.now
        assertTrue(out.exists(), "金样输出目录不存在")
        writeRecalls(out, fixtures)
        println("memory goldens -> ${out.absolutePath}")
    }
}
