package com.rhodes.privatechat.shared.eval

import com.rhodes.privatechat.shared.backup.wire.readFullGoldenBytes
import com.rhodes.privatechat.shared.domain.memory.MemoryBm25
import com.rhodes.privatechat.shared.domain.memory.MemoryLexicalTokenizer
import com.rhodes.privatechat.shared.domain.memory.MemoryRecallEngine
import com.rhodes.privatechat.shared.domain.memory.MemoryVectorFormatter
import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.LocalHashEmbeddingGateway
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import com.rhodes.privatechat.shared.vector.VectorSearchRequest
import com.rhodes.privatechat.shared.vector.VectorStoreGateway
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T68（检索层回放 v0）：用固定数据集评估 `MemoryRecallEngine` 的召回/准入/abstention。
 *
 * - 数据：`resources/eval/retrieval-cases-v0.jsonl`，20 组 × 2–5 个问题；`tune` 11 组用于调阈值，
 *   `holdout` 9 组只在报告里出现，**不参与断言**（计划书 §8.1 的拆分纪律）。
 *   覆盖：偏好/事实/否定/关系/无关问题/同事实多版本，g11 状态变更、g12 同名多角色、
 *   g13 错误诱导、g14 额外 abstention；v0.2 新增 g15 时间变化、g16 共同经历、
 *   g17 情绪事件、g18 私聊/群聊来源隔离、g19 长对话干扰、g20 拒答边界（g18–g20 进 holdout）。
 * - 通道：本地哈希向量（与生产默认同算法）+ 真实 BM25（用与 DB 索引相同的分词/统计口径）。
 * - 这是「检索层」基线，不含模型抽取；抽取层回放需要真实模型路由，留给 G0/G1 实测。
 */
class RetrievalReplayEvalIosTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private data class CaseResult(
        val group: String,
        val split: String,
        val id: String,
        val expected: Set<Long>,
        val injected: Set<Long>,
        val expectAbstain: Boolean,
        val hasDuplicateTopic: Boolean,
        val hasContradiction: Boolean,
    )

    @Test
    fun retrievalReplayV0Baseline() = runBlocking {
        val lines = readFullGoldenBytes("eval/retrieval-cases-v0.jsonl")
            .decodeToString()
            .lines()
            .filter { it.isNotBlank() }
        assertTrue(lines.size >= 20, "回放集至少 20 组，实际 ${lines.size}")
        val groups = lines.map { line -> json.parseToJsonElement(line).jsonObject }
        val tuneGroups = groups.count { (it["split"]?.jsonPrimitive?.contentOrNull ?: "tune") == "tune" }
        val holdoutGroups = groups.size - tuneGroups
        val caseCount = groups.sumOf { it["cases"]!!.jsonArray.size }
        println("[T68][dataset] groups=${groups.size} tune=$tuneGroups holdout=$holdoutGroups cases=$caseCount")
        assertTrue(
            tuneGroups >= 8 && holdoutGroups >= 6,
            "拆分纪律：tune ≥8 组、holdout ≥6 组，实际 $tuneGroups/$holdoutGroups",
        )

        val results = mutableListOf<CaseResult>()
        groups.forEach { group -> evalGroup(group, results) }

        val tune = results.filter { it.split == "tune" }
        val holdout = results.filter { it.split == "holdout" }
        assertTrue(tune.isNotEmpty() && holdout.isNotEmpty(), "调参集与保留集都必须非空")

        report("tune", tune)
        report("holdout", holdout)

        // 回归基线（在 tune 上断言；holdout 只看数字，避免调参泄漏）。
        val tuneExpected = tune.filter { it.expected.isNotEmpty() }
        val tuneHit = tuneExpected.count { case -> case.expected.any { it in case.injected } }.toDouble() / tuneExpected.size
        val tuneAbstainCases = tune.filter { it.expectAbstain }
        val tuneAbstainOk = tuneAbstainCases.count { it.injected.isEmpty() }.toDouble() /
            tuneAbstainCases.size.coerceAtLeast(1)
        val tuneInjected = tuneExpected.sumOf { it.injected.size }
        val tuneRelevant = tuneExpected.sumOf { it.expected.intersect(it.injected).size }
        val tunePrecision = if (tuneInjected == 0) 1.0 else tuneRelevant.toDouble() / tuneInjected
        // 回归基线（v1，2026-11-26）：tune hit 17/19、recall 0.81、precision 0.83、abstention 3/3。
        // 回放集扩到 20 组后（v2，2026-11-26）：tune hit 26/28、recall 0.87、precision 0.83、abstention 3/3。
        assertTrue(tuneHit >= 0.80, "tune hit@4 低于回归基线 0.80，实际 $tuneHit")
        assertTrue(tunePrecision >= 0.75, "tune precision@4 低于回归基线 0.75，实际 $tunePrecision")
        assertEquals(1.0, tuneAbstainOk, "tune 无关问题必须 abstain")
        assertTrue(tuneAbstainCases.size >= 3, "tune 至少 3 个 abstention 用例（含额外 abstention 组）")
        assertEquals(0, tune.count { it.hasDuplicateTopic }, "同一事实的多个版本不得同时注入（X-03）")
        assertEquals(0, tune.count { it.hasContradiction }, "相反极性不得同轮注入（T78 前置保护）")
    }

    // ------------------------------------------------------------------ 单组执行

    private suspend fun evalGroup(group: JsonObject, results: MutableList<CaseResult>) {
        val groupId = group["id"]!!.jsonPrimitive.content
        val split = group["split"]?.jsonPrimitive?.contentOrNull ?: "tune"
        val ownerId = "op-$groupId"
        val allowedSources = setOf(MemorySourceKind.PRIVATE_CHAT.name)
        val coreItems = group["memories"]!!.jsonArray.map { element ->
            val memory = element.jsonObject
            val level = MemoryLevel.valueOf(memory["level"]!!.jsonPrimitive.content)
            val sourceKind = MemorySourceKind.valueOf(
                memory["source"]?.jsonPrimitive?.contentOrNull ?: MemorySourceKind.PRIVATE_CHAT.name,
            )
            MemoryItem(
                id = memory["id"]!!.jsonPrimitive.content.toLong(),
                ownerType = "operator",
                ownerId = ownerId,
                memoryLevel = level,
                memoryType = memory["type"]!!.jsonPrimitive.content,
                sourceKind = sourceKind,
                content = memory["content"]!!.jsonPrimitive.content,
                importance = memory["importance"]?.jsonPrimitive?.intOrNull ?: 50,
                privacy = "private",
                createdAt = now - (memory["daysAgo"]?.jsonPrimitive?.intOrNull ?: 30) * day,
                updatedAt = now - (memory["daysAgo"]?.jsonPrimitive?.intOrNull ?: 30) * day,
                expiresAt = Long.MAX_VALUE,
                status = "active",
                topicKey = memory["topicKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        // 背景记忆：让 BM25 的 N/avgdl 接近真实使用（真实角色通常有几十到几百条 L1）。
        // 文案刻意避开所有查询词，只测试「语料规模对 IDF 的影响」，不改变标注期望。
        val background = BACKGROUND_TEXTS.mapIndexed { index, text ->
            MemoryItem(
                id = BACKGROUND_ID_BASE + index,
                ownerType = "operator",
                ownerId = ownerId,
                memoryLevel = MemoryLevel.L1,
                memoryType = "event",
                sourceKind = MemorySourceKind.PRIVATE_CHAT,
                content = text,
                importance = 35,
                privacy = "private",
                createdAt = now - (40L + index * 3L) * day,
                updatedAt = now - (40L + index * 3L) * day,
                expiresAt = Long.MAX_VALUE,
                status = "active",
            )
        }
        val items = coreItems + background
        val contentToId = items.associate { MemoryVectorFormatter.content(it).take(180) to it.id }
        val embedder = LocalHashEmbeddingGateway()
        val storedVectors = items.map { item ->
            val content = MemoryVectorFormatter.content(item)
            item.toStoredVector(content = content, embedding = embedder.embed(content))
        }
        val gateway = InMemoryVectorGateway(storedVectors)
        val settings = SettingsRepository(PromptTestSettings())
        val vectorService = MemoryVectorService(settings, gateway)
        val engine = MemoryRecallEngine(
            settings = settings,
            memoryVectorService = vectorService,
            lexicalRecall = { _, query -> lexicalCandidates(items, query, allowedSources) },
            factKeyResolver = { ids ->
                items.filter { it.id in ids && it.topicKey.isNotBlank() }.associate { it.id to it.topicKey }
            },
            nowMillis = { now },
        )

        group["cases"]!!.jsonArray.forEach { element ->
            val case = element.jsonObject
            val query = case["query"]!!.jsonPrimitive.content
            val expected = case["expected"]?.jsonArray?.map { it.jsonPrimitive.content.toLong() }?.toSet().orEmpty()
            val expectAbstain = case["abstain"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            val block = engine.buildPrivateChatMemoryContext(
                operatorId = ownerId,
                query = query,
                allowedSources = allowedSources,
                allowPrivateVisualRecall = false,
            )
            val injected = block.lines()
                .filter { it.startsWith("- ") }
                .mapNotNull { line -> contentToId[line.removePrefix("- ").trim()] }
                .toSet()
            val forbidden = case["forbidden"]?.jsonArray
                ?.map { it.jsonPrimitive.content.toLong() }
                ?.toSet()
                .orEmpty()
            assertTrue(
                expected.all { id -> coreItems.any { it.id == id } },
                "数据集错误：${case["id"]} 的 expected 含不存在的记忆 id",
            )
            assertTrue(
                forbidden.all { id -> coreItems.any { it.id == id } },
                "数据集错误：${case["id"]} 的 forbidden 含不存在的记忆 id",
            )
            assertTrue(
                injected.none { it in forbidden },
                "来源/干扰越界：${case["id"]} 注入了 forbidden=$forbidden，实际 injected=$injected",
            )
            val topics = injected.mapNotNull { id -> coreItems.firstOrNull { it.id == id }?.topicKey?.takeIf { it.isNotBlank() } }
            val injectedKeys = injected.mapNotNull { id -> coreItems.firstOrNull { it.id == id }?.topicKey?.takeIf { it.isNotBlank() } }
            val hasContradiction = injectedKeys.any { left ->
                injectedKeys.any { right -> left != right && factBase(left) == factBase(right) && polarity(left) != polarity(right) }
            }
            results += CaseResult(
                groupId,
                split,
                case["id"]!!.jsonPrimitive.content,
                expected,
                injected,
                expectAbstain,
                hasDuplicateTopic = topics.size != topics.distinct().size,
                hasContradiction = hasContradiction,
            )
        }
    }

    // ------------------------------------------------------------------ 指标

    private fun report(split: String, cases: List<CaseResult>) {
        val expectedCases = cases.filter { it.expected.isNotEmpty() }
        val hit = expectedCases.count { case -> case.expected.any { it in case.injected } }
        val recall = expectedCases.sumOf { case ->
            case.expected.intersect(case.injected).size.toDouble() / case.expected.size
        } / expectedCases.size.coerceAtLeast(1)
        val totalInjected = expectedCases.sumOf { it.injected.size }
        val totalRelevant = expectedCases.sumOf { it.expected.intersect(it.injected).size }
        val precision = if (totalInjected == 0) 1.0 else totalRelevant.toDouble() / totalInjected
        val abstainCases = cases.filter { it.expectAbstain }
        val abstainOk = abstainCases.count { it.injected.isEmpty() }
        val duplicateCases = cases.count { case -> case.hasDuplicateTopic }
        val contradictionCases = cases.count { case -> case.hasContradiction }
        println(
            "[T68][$split] cases=${cases.size} hit@4=$hit/${expectedCases.size} " +
                "recall=${fmt(recall)} precision=${fmt(precision)} " +
                "abstain=$abstainOk/${abstainCases.size} duplicateCases=$duplicateCases contradictionCases=$contradictionCases",
        )
        cases.forEach { case ->
            val status = when {
                case.expectAbstain -> if (case.injected.isEmpty()) "ABSTAIN-OK" else "ABSTAIN-FAIL"
                case.expected.any { it in case.injected } -> "HIT"
                else -> "MISS"
            }
            println(
                "[T68][$split][${case.group}/${case.id}] $status expected=${case.expected.sorted()} " +
                    "injected=${case.injected.sorted()}",
            )
        }
    }

    private fun fmt(value: Double): String = (value * 100).toInt().div(100.0).toString()

    private fun factBase(key: String): String? {
        if (!key.startsWith("fact:")) return null
        val rest = key.removePrefix("fact:")
        val index = rest.indexOf(':')
        if (index <= 0 || index >= rest.length - 1) return null
        return rest.substring(index + 1)
    }

    private fun polarity(key: String): String = key.removePrefix("fact:").substringBefore(':')

    private companion object {
        const val BACKGROUND_ID_BASE = 90_000L
        val BACKGROUND_TEXTS = listOf(
            "用户提到通勤时间变长",
            "用户这段时间在整理房间",
            "用户说办公室换了新工位",
            "用户分享了一首老歌",
            "用户说楼下开了家便利店",
            "用户提到这段时间常去公园散步",
            "用户说手机屏幕摔了一道痕",
            "用户问干员忙不忙",
            "用户说这段时间在学做菜",
            "用户提到想换个新背包",
            "用户说这段时间在追一部剧",
            "用户说家里换了新的窗帘",
            "用户提到这段时间常下雨",
            "用户说想买双新鞋",
            "用户提到同事养了只仓鼠",
            "用户说这段时间在练习早睡",
            "用户说这段时间在读一本书",
            "用户提到楼上的邻居很吵",
            "用户说想换个发型",
            "用户提到这段时间在学英语",
            "用户说咖啡喝多了睡不着",
            "用户提到在收拾旧照片",
            "用户说想给房间添盆绿植",
            "用户说晚上风有点大",
        )
    }

    // ------------------------------------------------------------------ 通道适配

    private fun lexicalCandidates(
        items: List<MemoryItem>,
        query: String,
        allowedSources: Set<String>,
    ): List<VectorMemory> {
        val scopedItems = items.filter { it.sourceKind.name in allowedSources }
        val terms = MemoryLexicalTokenizer.queryTerms(query).distinct()
        if (terms.isEmpty() || scopedItems.isEmpty()) return emptyList()
        val tokenized = scopedItems.map { it to MemoryLexicalTokenizer.tokenize(it.content) }
        val docCount = tokenized.size
        val averageLength = tokenized.sumOf { it.second.values.sum() }.toDouble() / docCount
        val documentFrequency = terms.associateWith { term -> tokenized.count { term in it.second } }
        return tokenized.mapNotNull { (item, tokens) ->
            val length = tokens.values.sum()
            var score = 0.0
            var matched = 0
            var maxIdf = 0.0
            terms.forEach { term ->
                val tf = tokens[term] ?: 0
                if (tf > 0) {
                    val df = documentFrequency[term] ?: 0
                    score += MemoryBm25.score(
                        tf = tf,
                        df = df,
                        docLength = length,
                        docCount = docCount,
                        averageLength = averageLength,
                    )
                    matched++
                    maxIdf = maxOf(maxIdf, MemoryBm25.idf(docCount, df))
                }
            }
            if (score <= 0.0) null else Hit(item, score, matched, maxIdf)
        }.sortedByDescending { it.score }
            .take(120)
            .map {
                it.item.toVectorMemory(
                    lexicalScore = it.score,
                    matchedTerms = it.matched,
                    maxTermIdf = it.maxIdf,
                )
            }
    }

    private data class Hit(val item: MemoryItem, val score: Double, val matched: Int, val maxIdf: Double)

    private fun MemoryItem.toVectorMemory(
        lexicalScore: Double = 0.0,
        matchedTerms: Int = 0,
        maxTermIdf: Double = 0.0,
    ): VectorMemory = VectorMemory(
        id = MemoryVectorFormatter.vectorId(this),
        ownerType = ownerType,
        ownerId = ownerId,
        sourceType = MemoryVectorFormatter.sourceType(this),
        sourceId = sourceRefId.ifBlank { sessionId },
        content = MemoryVectorFormatter.content(this),
        importance = importance.coerceIn(0, 100) / 100.0,
        tags = MemoryVectorFormatter.tags(this),
        visibility = privacy ?: "private",
        createdAt = createdAt,
        expiresAt = expiresAt,
        lexicalScore = lexicalScore,
        lexicalMatchCount = matchedTerms,
        lexicalMaxTermIdf = maxTermIdf,
        originMemoryId = id,
        originContent = content,
    )

    private fun MemoryItem.toStoredVector(content: String, embedding: List<Double>): VectorMemory = VectorMemory(
        id = MemoryVectorFormatter.vectorId(this),
        ownerType = ownerType,
        ownerId = ownerId,
        sourceType = MemoryVectorFormatter.sourceType(this),
        sourceId = sourceRefId.ifBlank { sessionId },
        content = content,
        importance = importance.coerceIn(0, 100) / 100.0,
        embedding = embedding,
        tags = MemoryVectorFormatter.tags(this),
        visibility = privacy ?: "private",
        createdAt = createdAt,
        expiresAt = expiresAt,
        originMemoryId = id,
        originContent = this.content,
    )

    private class InMemoryVectorGateway(private val stored: List<VectorMemory>) : VectorStoreGateway {

        override suspend fun upsert(memory: VectorMemory) = Unit

        override suspend fun search(request: VectorSearchRequest): List<VectorMemory> {
            val candidates = stored.asSequence()
                .filter { it.ownerType == request.ownerType && it.ownerId == request.ownerId }
                .filter { it.expiresAt > request.now }
                .filter { request.sourceTypes.isEmpty() || it.sourceType in request.sourceTypes }
                .filter { request.candidateSourceType.isEmpty() || it.sourceType == request.candidateSourceType }
                .filter { it.createdAt >= request.minCreatedAt && it.createdAt <= request.maxCreatedAt }
                .filter { request.sourceKinds.isEmpty() || it.tags.split(',').any { tag -> tag in request.sourceKinds } }
                .map { it.copy(similarity = cosine(request.queryEmbedding, it.embedding)) }
                .sortedByDescending { it.similarity }
                .take(request.limit)
                .toList()
            return candidates
        }

        override suspend fun listMemories(ownerType: String, ownerId: String): List<VectorMemory> =
            stored.filter { it.ownerType == ownerType && it.ownerId == ownerId }

        override suspend fun delete(memoryId: String) = Unit
        override suspend fun deleteBySource(ownerType: String, ownerId: String, sourceId: String) = Unit
        override suspend fun clearOwnerMemory(ownerType: String, ownerId: String) = Unit
        override suspend fun clearAllMemories() = Unit

        private fun cosine(left: List<Double>, right: List<Double>): Double {
            if (left.isEmpty() || right.isEmpty()) return 0.0
            val size = minOf(left.size, right.size)
            var dot = 0.0
            var leftNorm = 0.0
            var rightNorm = 0.0
            for (index in 0 until size) {
                dot += left[index] * right[index]
                leftNorm += left[index] * left[index]
                rightNorm += right[index] * right[index]
            }
            val denominator = sqrt(leftNorm) * sqrt(rightNorm)
            return if (denominator == 0.0) 0.0 else dot / denominator
        }
    }
}