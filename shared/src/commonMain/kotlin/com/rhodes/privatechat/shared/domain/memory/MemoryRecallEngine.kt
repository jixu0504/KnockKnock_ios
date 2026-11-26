package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import com.rhodes.privatechat.shared.vector.VectorSearchRequest
import kotlin.time.TimeSource

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
    /** 词法通道（BM25）。为 null 时行为与旧版完全一致（金样/矩阵测试不需要它）。 */
    private val lexicalRecall: (suspend (operatorId: String, query: String) -> List<VectorMemory>)? = null,
    /** 选中后的同事实去重：memoryId → fact/topic key（X-03，同一事只注入最合适的一条）。 */
    private val factKeyResolver: (suspend (memoryIds: List<Long>) -> Map<Long, String>)? = null,
    /**
     * 最终注入选择（去重/矛盾消解/同主题上限之后）的反馈回调（T73）：收到本轮实际注入的
     * originMemoryId（已过滤 0、已去重）。为 null 时行为与旧版完全一致。
     */
    private val onSelected: (suspend (List<Long>) -> Unit)? = null,
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
        val startedAt = TimeSource.Monotonic.markNow()
        val now = nowMillis()
        val vectorService = memoryVectorService ?: run {
            MemoryDiagnostics.log("Memory/Recall/降级", "未配置向量服务，本轮跳过记忆召回")
            return ""
        }
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

        // 词法通道：只在「向量召回之后」补充候选；两条通道按 memoryId 去重。
        val lexicalCandidates = lexicalRecall?.let { provider ->
            runCatching { provider.invoke(operatorId, query) }
                .onFailure {
                    MemoryDiagnostics.log(
                        "Memory/Recall/降级",
                        "词法通道失败=${it::class.simpleName} 原因=${it.message?.take(80) ?: "未知"}",
                    )
                }
                .getOrDefault(emptyList())
        }.orEmpty()
        val intent = MemoryQueryIntent.classify(query)
        val lexicalThreshold = lexicalAdmissionThreshold(intent)
        // 同一记忆可能同时出现在两条通道：按向量 id 合并，保留两边的信号（不能丢掉词法分，
        // 否则「向量相似度低但词法很强」的条目会被当作纯向量候选拒掉）。
        val merged = (candidates + lexicalCandidates)
            .groupBy { it.id }
            .map { (_, group) ->
                val vector = group.firstOrNull { it.similarity > 0.0 } ?: group.first()
                val lexical = group.maxByOrNull { it.lexicalScore }
                if (lexical != null && lexical.lexicalScore > vector.lexicalScore) {
                    vector.copy(
                        lexicalScore = lexical.lexicalScore,
                        lexicalMatchCount = lexical.lexicalMatchCount,
                        lexicalMaxTermIdf = lexical.lexicalMaxTermIdf,
                    )
                } else {
                    vector
                }
            }
        val lexicalOnlyIds = merged.asSequence()
            .filter { it.lexicalScore > 0.0 && it.similarity <= 0.0 }
            .mapNotNull { it.originMemoryId.takeIf { id -> id > 0 } }
            .toSet()

        val admitted = merged.mapNotNull { memory ->
            val type = memory.tags.split(',').getOrNull(1).orEmpty()
            val threshold = minimumPrivateSimilarity(memory.sourceType, type, query)
            val vectorPass = memory.similarity >= threshold
            // 判别性词门槛：只命中通用词（最近/在/什么）的词法结果不算强信号。
            val discriminative = memory.lexicalMaxTermIdf >= MIN_DISCRIMINATIVE_IDF
            val lexicalPass = discriminative && memory.lexicalScore >= lexicalThreshold
            // 双通道一致时放宽单通道门槛（计划书 §4.6.3：至少两个通道同时命中）。
            // 还要求命中 ≥2 个查询词：否则「最近」这类通用词 + 哈希向量的偶然重合会把背景记忆带进来。
            val dualPass = discriminative &&
                memory.lexicalScore >= DUAL_CHANNEL_LEXICAL_FLOOR &&
                memory.similarity >= DUAL_CHANNEL_VECTOR_FLOOR &&
                memory.lexicalMatchCount >= DUAL_CHANNEL_MIN_MATCHED_TERMS
            if (!vectorPass && !lexicalPass && !dualPass) return@mapNotNull null
            val score = privateRecallScore(memory, type, now) + lexicalBoost(memory.lexicalScore)
            memory to score
        }
        val ranked = fuseRanks(merged, admitted)

        MemoryDiagnostics.log(
            "Memory/Recall/词法",
            "intent=$intent 词法候选=${lexicalCandidates.size} 合并=${merged.size} " +
                "准入=${ranked.size} 阈值=$lexicalThreshold",
        )

        val recallQuestion = UnifiedMemoryContext.shouldIncludeTimeSummary(query)
        val injected = selectPrivateInjection(
            ranked,
            recallQuestion,
            isVisualRecallQuery(query),
            now,
            lexicalOnlyIds = lexicalOnlyIds,
        )
        val factKeys = resolveFactKeys(injected)
        val selected = capPerTopic(
            resolveContradictions(dedupeByFact(injected, factKeys), factKeys),
            factKeys,
        )
        notifySelected(selected)
        // 诊断：只写计数/耗时/阈值口径，不写记忆正文或用户原文（计划书 §4.11 隐私约定）。
        MemoryDiagnostics.log(
            "Memory/Recall",
            "私聊召回：候选=${candidates.size} 过阈值=${ranked.size} 注入=${selected.size} " +
                "查询字=${query.length} 扩展=${recallQuestion} 耗时=${startedAt.elapsedNow().inWholeMilliseconds}ms",
        )
        val lines = selected.joinToString("\n") { "- ${it.content.take(180)}" }
        return lines.takeIf { it.isNotBlank() }?.let { "【与当前话题相关的经历】\n$it" }.orEmpty()
    }

    internal data class PrivateRecallBudget(val recentL1: Int, val olderL1: Int, val l2: Int, val l3: Int, val manual: Int)

    /**
     * 只在最终注入集上回调（T73）：反馈计数是旁路记账，失败只记诊断，不能影响本轮召回输出。
     */
    private suspend fun notifySelected(selected: List<VectorMemory>) {
        val callback = onSelected ?: return
        val ids = selected.asSequence()
            .mapNotNull { it.originMemoryId.takeIf { id -> id > 0 } }
            .distinct()
            .toList()
        if (ids.isEmpty()) return
        try {
            callback.invoke(ids)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            MemoryDiagnostics.log(
                "Memory/Recall/降级",
                "注入计数写入失败=${error::class.simpleName} 原因=${error.message?.take(80) ?: "未知"}",
            )
        }
    }

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
        }.onFailure {
            MemoryDiagnostics.log(
                "Memory/Recall/降级",
                "通道=$sourceType 失败=${it::class.simpleName} 原因=${it.message?.take(80) ?: "未知"}",
            )
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

    internal companion object {
        /**
         * 双通道一致时的放宽门槛（任一通道单独达到此值不足以准入）。
         * 1.0 太低：像「最近」这类通用时间词在几十条语料里 BM25 约 1.5–1.7，
         * 会配合哈希向量的偶然重合把背景记忆带进注入（T68 回放实测）。
         */
        const val DUAL_CHANNEL_LEXICAL_FLOOR = 1.5
        const val DUAL_CHANNEL_VECTOR_FLOOR = 0.15
        const val DUAL_CHANNEL_MIN_MATCHED_TERMS = 2
        /** 区分度门槛：IDF 低于此值的词（最近/现在/今天等通用词）不能单独构成词法强信号。 */
        const val MIN_DISCRIMINATIVE_IDF = 1.5
        /** 词法单通道命中最多占用的注入名额。 */
        const val MAX_LEXICAL_ONLY_INJECTION = 2
        /** RRF 平滑常数（计划书 §4.6.3：1/(60+rank)）。 */
        const val RRF_RANK_OFFSET = 60
        /** MMR-lite：同一主题最多注入条数。 */
        const val MAX_INJECTIONS_PER_TOPIC = 2
        const val FACT_TOPIC_PREFIX = "fact:"
        const val LEXICAL_BOOST_CAP = 12.0
    }

    internal fun isVisualRecallQuery(query: String): Boolean = listOf(
        "图片", "照片", "相片", "图里", "图中", "这张", "那张", "截图", "画面", "看见", "看到", "物品", "东西"
    ).any { query.contains(it) }

    /**
     * RRF 排名融合（计划书 §4.6.3）：`fused = Σ 1/(60+rank_channel)`，只影响排序，准入仍由调用方判定。
     *
     * 词法通道整轮没有正分命中时不存在可融合的第二路排名，保持原有标定分排序（金样契约）；
     * 否则先按 fused 降序，再按原召回分降序。两通道都未命中的候选 fused=0，同样落回原分数排序。
     */
    private fun fuseRanks(
        merged: List<VectorMemory>,
        admitted: List<Pair<VectorMemory, Double>>,
    ): List<VectorMemory> {
        if (merged.none { it.lexicalScore > 0.0 }) {
            return admitted.sortedByDescending { it.second }.map { it.first }
        }
        val vectorRanks = channelRanks(merged) { it.similarity }
        val lexicalRanks = channelRanks(merged) { it.lexicalScore }
        fun fused(memory: VectorMemory): Double {
            val vector = vectorRanks[memory.id]?.let { 1.0 / (RRF_RANK_OFFSET + it) } ?: 0.0
            val lexical = lexicalRanks[memory.id]?.let { 1.0 / (RRF_RANK_OFFSET + it) } ?: 0.0
            return vector + lexical
        }
        return admitted
            .sortedWith(
                compareByDescending<Pair<VectorMemory, Double>> { fused(it.first) }
                    .thenByDescending { it.second },
            )
            .map { it.first }
    }

    /** 单通道名次（1 起，按分数降序）；分数 <= 0 的候选不参与该通道排名。 */
    private fun channelRanks(
        merged: List<VectorMemory>,
        score: (VectorMemory) -> Double,
    ): Map<String, Int> = merged
        .filter { score(it) > 0.0 }
        .sortedByDescending(score)
        .mapIndexed { index, memory -> memory.id to index + 1 }
        .toMap()

    /** 一次批量取回选中记忆的 fact/topic key（去重、矛盾消解、同主题上限共用，避免重复查库）。 */
    private suspend fun resolveFactKeys(selected: List<VectorMemory>): Map<Long, String> {
        val resolver = factKeyResolver ?: return emptyMap()
        if (selected.size <= 1) return emptyMap()
        val ids = selected.map { it.originMemoryId }.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return runCatching { resolver.invoke(ids) }.getOrDefault(emptyMap())
    }

    /**
     * 同一事实的 L1/L2/L3 版本只注入一条（X-03）：按 fact/topic key 分组，
     * 组内保留层级最高的一条（L3 > L2 > L1），层级相同则保留先被选中的（排序更靠前）。
     */
    private fun dedupeByFact(selected: List<VectorMemory>, keys: Map<Long, String>): List<VectorMemory> {
        if (selected.size <= 1 || keys.isEmpty()) return selected
        val chosen = mutableListOf<VectorMemory>()
        selected.forEach { memory ->
            val key = keys[memory.originMemoryId]?.trim().orEmpty()
            if (key.isEmpty()) {
                chosen += memory
                return@forEach
            }
            val existingIndex = chosen.indexOfFirst { keys[it.originMemoryId]?.trim().orEmpty() == key }
            if (existingIndex < 0) {
                chosen += memory
            } else if (memoryLevelRank(memory) > memoryLevelRank(chosen[existingIndex])) {
                chosen[existingIndex] = memory
            }
        }
        return chosen
    }

    /**
     * 同一事实的相反极性只注入一条（计划书 §4.3 contradicts：不得同轮注入两条）。
     *
     * 这里只做**展示层消解**：较旧的一条不删、不归档，仍留在记忆库（供审计与 T78 的
     * 关系/双时间使用）；因为旧事实可能只是「已失效」而用户还没确认，删除是错误动作。
     * 等 T78 的 conflict_status 队列落地后，改为「不注入 + 进入待确认」。
     */
    private fun resolveContradictions(selected: List<VectorMemory>, keys: Map<Long, String>): List<VectorMemory> {
        if (selected.size <= 1 || keys.isEmpty()) return selected
        val chosen = mutableListOf<VectorMemory>()
        selected.forEach { memory ->
            val key = keys[memory.originMemoryId]?.trim().orEmpty()
            val base = factKeyBase(key)
            if (base == null) {
                chosen += memory
                return@forEach
            }
            val conflictIndex = chosen.indexOfFirst {
                val otherKey = keys[it.originMemoryId]?.trim().orEmpty()
                val otherBase = factKeyBase(otherKey)
                otherBase == base && polarityOf(key) != polarityOf(otherKey)
            }
            if (conflictIndex < 0) {
                chosen += memory
            } else {
                val existing = chosen[conflictIndex]
                // 保留更新的一条；时间相同则保留层级更高的一条。
                val replace = memory.createdAt > existing.createdAt ||
                    (memory.createdAt == existing.createdAt && memoryLevelRank(memory) > memoryLevelRank(existing))
                if (replace) chosen[conflictIndex] = memory
                MemoryDiagnostics.log(
                    "Memory/Recall/矛盾消解",
                    "同一事实相反极性，仅注入较新一条：keep=${(if (replace) memory else existing).originMemoryId}",
                )
            }
        }
        return chosen
    }

    /**
     * MMR-lite：同一主题最多注入 [MAX_INJECTIONS_PER_TOPIC] 条，保持已选顺序；空键不限制。
     *
     * 主题取 fact/topic key 的粗粒度投影：结构化事实
     * `fact:<polarity>:<subject>|<predicate>|<object>[|q=...]` 归到 `subject|predicate`
     * （同一事实的极性、对象变体共享主题）；非结构化 topicKey 原样作为主题。
     * 同一事实的父子版本与相反极性已在前两步消解，这里只限制仍不同的同主题事实。
     */
    private fun capPerTopic(selected: List<VectorMemory>, keys: Map<Long, String>): List<VectorMemory> {
        if (selected.size <= MAX_INJECTIONS_PER_TOPIC || keys.isEmpty()) return selected
        val counts = mutableMapOf<String, Int>()
        return selected.filter { memory ->
            val topic = topicOfFactKey(keys[memory.originMemoryId]?.trim().orEmpty()) ?: return@filter true
            val used = counts[topic] ?: 0
            if (used >= MAX_INJECTIONS_PER_TOPIC) return@filter false
            counts[topic] = used + 1
            true
        }
    }

    private fun topicOfFactKey(key: String): String? {
        if (key.isEmpty()) return null
        val base = factKeyBase(key) ?: return key
        val parts = base.split('|')
        return if (parts.size >= 2) parts[0] + "|" + parts[1] else base
    }

    /** `fact:<polarity>:<base>` → base；非结构化键返回 null。 */
    private fun factKeyBase(key: String): String? {
        if (!key.startsWith(FACT_TOPIC_PREFIX)) return null
        val rest = key.removePrefix(FACT_TOPIC_PREFIX)
        val index = rest.indexOf(':')
        if (index <= 0 || index >= rest.length - 1) return null
        return rest.substring(index + 1)
    }

    private fun polarityOf(key: String): String {
        val rest = key.removePrefix(FACT_TOPIC_PREFIX)
        return rest.substringBefore(':')
    }

    private fun memoryLevelRank(memory: VectorMemory): Int = when (memory.tags.split(',').firstOrNull()) {
        "L3" -> 3
        "L2" -> 2
        "L1" -> 1
        else -> 0
    }

    /** 词法主信号门槛（回放集标定前的保守初始值，见计划书 §4.6.3）。 */
    internal fun lexicalAdmissionThreshold(intent: MemoryQueryIntent): Double = when (intent) {
        MemoryQueryIntent.FACT -> 2.0
        MemoryQueryIntent.TIME -> 2.5
        // 关系类问题经常只共用一个强词（答应/生日），3.0 会把正确答案挡在门外（T68 tune 实测）。
        MemoryQueryIntent.RELATIONSHIP -> 2.0
        MemoryQueryIntent.PREFERENCE -> 3.5
        MemoryQueryIntent.UNKNOWN -> 3.0
    }

    /** 词法分的加分上限：不让 BM25 的绝对尺度压过向量相似度。 */
    internal fun lexicalBoost(lexicalScore: Double): Double =
        if (lexicalScore <= 0.0) 0.0 else minOf(lexicalScore, LEXICAL_BOOST_CAP) / LEXICAL_BOOST_CAP * 0.25

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

    internal fun selectPrivateInjection(
        candidates: List<VectorMemory>,
        expanded: Boolean,
        visualQuery: Boolean,
        now: Long,
        lexicalOnlyIds: Set<Long> = emptySet(),
        lexicalOnlyLimit: Int = MAX_LEXICAL_ONLY_INJECTION,
    ): List<VectorMemory> {
        val maxCount = if (expanded) 6 else 4
        val recentL1Limit = if (expanded) 3 else 2
        val selected = mutableListOf<VectorMemory>()
        val selectedTopics = mutableSetOf<String>()

        fun isLexicalOnly(memory: VectorMemory): Boolean =
            memory.originMemoryId > 0 && memory.originMemoryId in lexicalOnlyIds

        fun addMatching(limit: Int, predicate: (VectorMemory) -> Boolean) {
            candidates.asSequence().filter(predicate).forEach { memory ->
                if (selected.size >= maxCount || selected.count(predicate) >= limit) return@forEach
                // 词法单通道命中可以补充召回，但不能挤满整个注入预算（避免字面重合主导对话）。
                if (isLexicalOnly(memory) && selected.count(::isLexicalOnly) >= lexicalOnlyLimit) return@forEach
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
