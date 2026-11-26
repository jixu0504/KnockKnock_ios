package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import com.rhodes.privatechat.shared.vector.VectorSearchRequest

/**
 * 记忆召回/评分/注入选择（规格卡契约 12-15、验收 #2/#4）。
 *
 * 由 MemoryV2Pipeline（oracle MemoryV2Pipeline.kt:444-743）拆出的召回组件：向量候选收集、分桶预算、
 * 相似度阈值、评分排序、注入选择、关系网亲密度策略、上下文包裹。代码与 oracle 逐行一致，
 * 仅把「repository 查询 + AI 调用」留在 MemoryV2Pipeline（本组件无存储/AI 依赖），
 * 因此 JVM/iOS 双路径可用同一批 fixture 直接验证召回输出（金样门禁）。
 */
internal class MemoryRecallEngine(
    private val settings: SettingsRepository,
    private val memoryVectorService: MemoryVectorService?,
    private val nowMillis: () -> Long,
) {

    suspend fun buildPrivateMemoryContext(
        operatorId: String,
        limitL1: Int,
        limitL2: Int,
        limitL3: Int,
        query: String = "",
        applyPrivateSourceFilter: Boolean = false,
        allowedSources: Set<String>? = null,
        visibilities: List<String> = emptyList(),
    ): String {
        if (!settings.memoryV2Enabled) return ""
        val sourcePolicy = allowedSources ?: if (applyPrivateSourceFilter) privateChatAllowedSources() else null
        if (sourcePolicy != null && sourcePolicy.isEmpty()) return ""
        val personal = buildOwnerMemoryContext(
            "operator", operatorId, limitL1, limitL2, limitL3, query,
            sourcePolicy, visibilities = visibilities
        )
        return listOf(
            personal.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString("\n")
    }

    /** Private chat prioritizes recent facts without letting long-term memories fill the candidate pool. */
    suspend fun buildPrivateChatMemoryContext(
        operatorId: String,
        query: String,
        allowedSources: Set<String> = privateChatAllowedSources(),
        allowPrivateVisualRecall: Boolean = settings.privateRecallPrivateChatMemory,
    ): String {
        if (!settings.memoryV2Enabled) return ""
        if (query.isBlank()) return ""
        val now = nowMillis()
        val vectorService = memoryVectorService ?: return ""
        val budget = privateRecallBudget(settings.memoryRecallMode)
        val recentCutoff = now - 30L * 86_400_000L
        val candidates = buildList {
            addAll(recallPrivateTier(vectorService, operatorId, query, "memory_v2_l1", budget.recentL1, recentCutoff, Long.MAX_VALUE, true, now, allowedSources))
            addAll(recallPrivateTier(vectorService, operatorId, query, "memory_v2_l1", budget.olderL1, 0L, recentCutoff, false, now, allowedSources))
            addAll(recallPrivateTier(vectorService, operatorId, query, "memory_v2_l2", budget.l2, 0L, Long.MAX_VALUE, false, now, allowedSources))
            addAll(recallPrivateTier(vectorService, operatorId, query, "memory_v2_l3", budget.l3, 0L, Long.MAX_VALUE, false, now, allowedSources))
            if (MemorySourceKind.MANUAL_MEMORY.name in allowedSources) {
                addAll(recallPrivateTier(
                    vectorService, operatorId, query, "manual_memory", budget.manual, 0L,
                    Long.MAX_VALUE, false, now, setOf(MemorySourceKind.MANUAL_MEMORY.name)
                ))
            }
            if (isVisualRecallQuery(query) && allowPrivateVisualRecall) {
                // Legacy visual anchors predate source-kind tags; they are private owner-scoped records.
                addAll(recallPrivateTier(vectorService, operatorId, query, "anchor_vision", 10, recentCutoff, Long.MAX_VALUE, true, now, null))
            }
        }.distinctBy { it.id }

        val ranked = candidates.mapNotNull { memory ->
            val type = memory.tags.split(',').getOrNull(1).orEmpty()
            val threshold = minimumPrivateSimilarity(memory.sourceType, type, query)
            if (memory.similarity < threshold) null else memory to privateRecallScore(memory, type, now)
        }.sortedByDescending { it.second }.map { it.first }

        val recallQuestion = UnifiedMemoryContext.shouldIncludeTimeSummary(query)
        val selected = selectPrivateInjection(ranked, recallQuestion, isVisualRecallQuery(query), now)
        val lines = selected.joinToString("\n") { "- ${it.content.take(180)}" }
        return lines.takeIf { it.isNotBlank() }?.let { "【与当前话题相关的经历】\n$it" }.orEmpty()
    }

    internal data class PrivateRecallBudget(val recentL1: Int, val olderL1: Int, val l2: Int, val l3: Int, val manual: Int)

    internal fun privateRecallBudget(mode: String): PrivateRecallBudget = when (mode) {
        "fast" -> PrivateRecallBudget(recentL1 = 80, olderL1 = 20, l2 = 30, l3 = 20, manual = 20)
        "deep" -> PrivateRecallBudget(recentL1 = 260, olderL1 = 100, l2 = 100, l3 = 40, manual = 40)
        else -> PrivateRecallBudget(recentL1 = 150, olderL1 = 50, l2 = 60, l3 = 30, manual = 30)
    }

    private suspend fun recallPrivateTier(
        vectorService: MemoryVectorService,
        operatorId: String,
        query: String,
        sourceType: String,
        limit: Int,
        minCreatedAt: Long,
        maxCreatedAt: Long,
        preferRecent: Boolean,
        now: Long,
        allowedSources: Set<String>?,
    ): List<VectorMemory> {
        if (limit <= 0 || allowedSources?.isEmpty() == true) return emptyList()
        return runCatching {
            vectorService.search(VectorSearchRequest(
                ownerType = "operator", ownerId = operatorId, query = query,
                limit = limit, sourceTypes = listOf(sourceType), minScore = 0.0, now = now,
                candidateLimit = limit, minCreatedAt = minCreatedAt,
                maxCreatedAt = maxCreatedAt, candidateSourceType = sourceType, preferRecentCandidates = preferRecent,
                sourceKinds = allowedSources?.toList().orEmpty(),
            ))
        }.getOrDefault(emptyList())
    }

    fun privateChatAllowedSources(): Set<String> = buildSet {
        listOf(
            MemorySourceKind.PRIVATE_CHAT,
            MemorySourceKind.GROUP_CHAT,
            MemorySourceKind.MOMENT,
            MemorySourceKind.MOMENT_COMMENT,
            MemorySourceKind.DIARY,
            MemorySourceKind.MANUAL_MEMORY,
        ).forEach { source ->
            if (settings.isMemoryInjectionAllowed("private_chat", source.name)) add(source.name)
        }
    }

    internal fun minimumPrivateSimilarity(sourceType: String, memoryType: String, query: String): Double {
        val protected = memoryType in setOf("agreement_commitment", "care_reminder", "preference_expression")
        val recallQuestion = UnifiedMemoryContext.shouldIncludeTimeSummary(query)
        return when {
            protected && recallQuestion -> 0.14
            sourceType == "anchor_vision" -> 0.22
            sourceType == "memory_v2_l1" && memoryType in setOf("emotion_state", "behavior_state", "physiological_state", "event") -> 0.22
            sourceType in setOf("memory_v2_l3", "manual_memory") || protected -> 0.18
            else -> 0.20
        }
    }

    internal fun isVisualRecallQuery(query: String): Boolean = listOf(
        "图片", "照片", "相片", "图里", "图中", "这张", "那张", "截图", "画面", "看见", "看到", "物品", "东西"
    ).any { query.contains(it) }

    internal fun privateRecallScore(memory: VectorMemory, memoryType: String, now: Long): Double {
        val ageDays = ((now - memory.createdAt).coerceAtLeast(0L) / 86_400_000L).coerceAtMost(90L)
        val recency = when (memory.sourceType) {
            "memory_v2_l1" -> (30.0 - ageDays.coerceAtMost(30L)) / 30.0
            "memory_v2_l2" -> (45.0 - ageDays.coerceAtMost(45L)) / 45.0
            else -> 0.5
        }
        val typeBonus = when (memoryType) {
            "agreement_commitment", "care_reminder" -> 0.05
            "preference_expression" -> 0.03
            else -> 0.0
        }
        return memory.similarity * 0.70 + memory.importance * 0.15 + recency * 0.10 + typeBonus
    }

    internal fun selectPrivateInjection(candidates: List<VectorMemory>, expanded: Boolean, visualQuery: Boolean, now: Long): List<VectorMemory> {
        val maxCount = if (expanded) 6 else 4
        val recentL1Limit = if (expanded) 3 else 2
        val selected = mutableListOf<VectorMemory>()
        val selectedTopics = mutableSetOf<String>()

        fun addMatching(limit: Int, predicate: (VectorMemory) -> Boolean) {
            candidates.asSequence().filter(predicate).forEach { memory ->
                if (selected.size >= maxCount || selected.count(predicate) >= limit) return@forEach
                val topic = normalizeForDedup("injected", memory.content)
                if (selectedTopics.add(topic)) selected += memory
            }
        }

        addMatching(recentL1Limit) { it.sourceType == "memory_v2_l1" && now - it.createdAt <= 30L * 86_400_000L }
        if (visualQuery) {
            // A directly relevant image should not be crowded out by generic long-term context.
            addMatching(1) { it.sourceType == "anchor_vision" }
        }
        addMatching(1) { it.sourceType in setOf("memory_v2_l2", "memory_v2_l3", "manual_memory") }
        addMatching(1) {
            val type = it.tags.split(',').getOrNull(1)
            type in setOf("agreement_commitment", "care_reminder", "preference_expression")
        }
        addMatching(maxCount) { true }
        return selected.take(maxCount)
    }

    suspend fun buildPublicMemoryContext(
        query: String,
        limit: Int = 3,
        allowedSources: Set<String> = buildSet {
            if (settings.isMemoryInjectionAllowed("private_chat", "MOMENT")) add(MemorySourceKind.MOMENT.name)
            if (settings.isMemoryInjectionAllowed("private_chat", "MOMENT_COMMENT")) add(MemorySourceKind.MOMENT_COMMENT.name)
        },
    ): String {
        if (!settings.memoryV2Enabled) return ""
        return if (allowedSources.isEmpty()) "" else buildOwnerMemoryContext(
            "global", "public", limit, 0, 0, query, allowedSources
        )
    }

    suspend fun buildOwnerMemoryContext(ownerType: String, ownerId: String, limitL1: Int, limitL2: Int, limitL3: Int, query: String = "", allowedSources: Set<String>? = null, minCreatedAt: Long = 0L, visibilities: List<String> = emptyList()): String {
        if (!settings.memoryV2Enabled) return ""
        val now = nowMillis()
        val vectorService = memoryVectorService ?: return ""
        if (query.isBlank() || allowedSources?.isEmpty() == true) return ""
        val candidateLimit = when (settings.memoryRecallMode) {
            "fast" -> 100
            "deep" -> 700
            else -> settings.memoryRecallCandidateLimit
        }
        suspend fun recallLevel(limit: Int, sourceTypes: List<String>): List<VectorMemory> {
            if (limit <= 0) return emptyList()
            return runCatching {
                vectorService.search(VectorSearchRequest(
                    ownerType = ownerType, ownerId = ownerId, query = query, limit = limit,
                    sourceTypes = sourceTypes, sourceKinds = allowedSources?.toList().orEmpty(),
                    candidateSourceType = sourceTypes.singleOrNull().orEmpty(),
                    visibilities = visibilities,
                    minScore = if (settings.memoryRecallMode == "fast") 0.24 else 0.16,
                    now = now, candidateLimit = candidateLimit,
                    minCreatedAt = maxOf(minCreatedAt, if (settings.memoryRecallMode == "fast") now - 30L * 86_400_000L else 0L),
                ))
            }.getOrDefault(emptyList())
        }
        val semantic = buildList {
            addAll(recallLevel(limitL1, listOf("memory_v2_l1")))
            // Manual entries are user-authored durable knowledge and belong with L2 recall.
            addAll(recallLevel(limitL2, listOf("memory_v2_l2", "manual_memory")))
            addAll(recallLevel(limitL3, listOf("memory_v2_l3")))
        }
        val semanticLines = semantic
            .distinctBy { normalizeForDedup("semantic", it.content) }
            .joinToString("\n") { "- ${it.content.take(180)}" }
        return semanticLines.takeIf { it.isNotBlank() }?.let { "【与当前话题相关的经历】\n$it" }.orEmpty()
    }


    fun publicMemoryAllowedSources(): Set<String> = buildSet {
        if (settings.isMemoryInjectionAllowed("private_chat", "MOMENT")) add(MemorySourceKind.MOMENT.name)
        if (settings.isMemoryInjectionAllowed("private_chat", "MOMENT_COMMENT")) add(MemorySourceKind.MOMENT_COMMENT.name)
    }

    /** buildPrivateStableImpression 的纯逻辑部分（oracle MemoryV2Pipeline.kt:686-695）。 */
    fun buildStableImpressionText(items: List<MemoryItem>): String {
        return items.joinToString("\n") { "- ${it.content.take(180)}" }
            .takeIf { it.isNotBlank() }
            ?.let { "【该角色对用户的长期稳定印象】\n$it" }
            .orEmpty()
    }

    internal data class RelationshipRecallPolicy(val candidateLimit: Int, val minImportance: Int)

    internal fun relationshipRecallPolicy(intimacy: Int): RelationshipRecallPolicy? = when (intimacy) {
        in 0..19 -> null
        in 20..39 -> RelationshipRecallPolicy(candidateLimit = 1, minImportance = 80)
        in 40..59 -> RelationshipRecallPolicy(candidateLimit = 2, minImportance = 65)
        in 60..79 -> RelationshipRecallPolicy(candidateLimit = 3, minImportance = 50)
        else -> RelationshipRecallPolicy(candidateLimit = 5, minImportance = 35)
    }


    /** buildRelationshipPrivateMemoryContext 的纯逻辑部分（oracle MemoryV2Pipeline.kt:660-681）。 */
    fun buildRelationshipContextText(candidates: List<VectorMemory>): String {
        val lines = candidates
            .sortedWith(compareByDescending<VectorMemory> { it.importance }.thenByDescending { it.createdAt })
            .distinctBy { normalizeForDedup("relationship", it.content) }
            .take(3)
            .map { "- ${it.content.take(180)}" }
        return lines.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?.let { "【与当前话题相关的关系网经历】\n$it" }
            .orEmpty()
    }

    private fun normalizeForDedup(type: String, content: String): String = memoryNormalizeForDedup(type, content)
}

/** 记忆内容去重归一（oracle MemoryV2Pipeline.kt:1213-1215）。 */
internal fun memoryNormalizeForDedup(type: String, content: String): String {
    return type + ":" + content.lowercase().filterNot { it.isWhitespace() || it in "，。！？；,.!?;：:" }
}
