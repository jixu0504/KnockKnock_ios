package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.prompt.readTestResource
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.EmbeddingGateway
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import com.rhodes.privatechat.shared.vector.VectorSearchRequest
import com.rhodes.privatechat.shared.vector.VectorStoreGateway
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 记忆召回金样 fixture（ios-port/golden/memory/fixtures.json 同步进 commonTest resources）。
 *
 * 金样生成来源：`ios-port/golden/memory/gen_golden.sh` 从 `v1.14.27-android-baseline` tag 取
 * Android oracle 源码（app/viewmodel/shared/MemoryV2Pipeline.kt 等）在 JVM 上用同一 fixtures 运行，
 * 输出写入 ios-port/golden/memory/{recall/{name}.md, embedding_local.json}。本测试只用迁移后的 shared
 * 实现运行同一 fixtures 与之逐字节比对——**禁止**用 shared 实现生成金样（防自证）。
 */

@Serializable
internal data class GoldenFixtures(
    val comment: String = "",
    val now: Long,
    val settings: Map<String, String> = emptyMap(),
    val vectors: List<VectorMemory> = emptyList(),
    val stableItems: List<MemoryItem> = emptyList(),
    val relationshipCandidates: List<VectorMemory> = emptyList(),
    val embeddingTexts: List<String> = emptyList(),
    val cases: List<GoldenCase> = emptyList(),
)

@Serializable
internal data class GoldenCase(
    val name: String,
    val kind: String,
    val query: String = "",
    val ownerType: String = "",
    val ownerId: String = "",
    val limitL1: Int = 0,
    val limitL2: Int = 0,
    val limitL3: Int = 0,
    val limit: Int = 3,
    val mode: String = "",
    val allowedSources: List<String> = emptyList(),
    val allowVisual: Boolean = false,
    val maxChars: Int = 0,
    val blocks: List<String> = emptyList(),
    val contextBlockLimit: Int = 0,
    val injectionBlocks: InjectionBlocks? = null,
)

@Serializable
internal data class InjectionBlocks(
    val groupContext: String = "无",
    val shortTermSummary: String = "无",
    val stableImpression: String = "",
    val personalMemory: String = "",
    val relationshipMemory: String = "",
    val publicMemory: String = "",
    val knowledgeBase: String = "无",
)

/**
 * 假向量库：按 LocalVectorStoreGateway 的候选过滤语义返回 fixtures（owner 作用域、sourceType、
 * sourceKinds、时间窗、minImportance、相似度排序、limit/candidateLimit 截断），
 * 但相似度由 fixture 给定（金样只覆盖纯逻辑层，真实余弦归 local 嵌入金样与假网关测试）。
 */
internal class FixtureVectorStore(private val all: List<VectorMemory>) : VectorStoreGateway {
    val requests = mutableListOf<VectorSearchRequest>()

    override suspend fun search(request: VectorSearchRequest): List<VectorMemory> {
        requests += request
        val filtered = all.filter { memory ->
            memory.ownerType == request.ownerType &&
                memory.ownerId == request.ownerId &&
                (request.sourceTypes.isEmpty() || memory.sourceType in request.sourceTypes) &&
                (request.candidateSourceType.isBlank() || memory.sourceType == request.candidateSourceType) &&
                (request.sourceKinds.isEmpty() || memory.tags.split(',').any { it in request.sourceKinds }) &&
                (request.visibilities.isEmpty() || memory.visibility in request.visibilities) &&
                (request.minCreatedAt <= 0L || memory.createdAt >= request.minCreatedAt) &&
                (request.maxCreatedAt == Long.MAX_VALUE || memory.createdAt <= request.maxCreatedAt) &&
                (request.minImportance <= 0.0 || memory.importance >= request.minImportance)
        }
        val limit = if (request.candidateLimit > 0) minOf(request.limit, request.candidateLimit) else request.limit
        return filtered.sortedWith(
            compareByDescending<VectorMemory> { it.similarity }.thenByDescending { it.createdAt },
        ).take(limit)
    }

    override suspend fun upsert(memory: VectorMemory) = Unit
    override suspend fun listMemories(ownerType: String, ownerId: String) = emptyList<VectorMemory>()
    override suspend fun delete(memoryId: String) = Unit
    override suspend fun deleteBySource(ownerType: String, ownerId: String, sourceId: String) = Unit
    override suspend fun clearOwnerMemory(ownerType: String, ownerId: String) = Unit
    override suspend fun clearAllMemories() = Unit
}

internal class FixedEmbeddingGateway : EmbeddingGateway {
    override suspend fun embed(text: String): List<Double> = listOf(1.0, 0.0)
}

internal object MemoryGoldenEnv {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(): GoldenFixtures = json.decodeFromString(readTestResource("golden/memory/fixtures.json"))

    fun settings(fixtures: GoldenFixtures): SettingsRepository {
        val settings = SettingsRepository(PromptTestSettings())
        fixtures.settings["memory_recall_mode"]?.let { settings.memoryRecallMode = it }
        fixtures.settings["memory_recall_candidate_limit"]?.let { settings.memoryRecallCandidateLimit = it.toInt() }
        fixtures.settings["clean_days_memory_items"]?.let { settings.cleanDaysMemoryItems = it.toInt() }
        fixtures.settings["memory_v2_enabled"]?.let { settings.memoryV2Enabled = it.toBoolean() }
        return settings
    }

    fun engine(fixtures: GoldenFixtures, mode: String = ""): Pair<MemoryRecallEngine, Pair<SettingsRepository, MemoryVectorService>> {
        val settings = settings(fixtures)
        if (mode.isNotBlank()) settings.memoryRecallMode = mode
        val store = FixtureVectorStore(fixtures.vectors)
        val service = MemoryVectorService(settings, store, { FixedEmbeddingGateway() })
        return MemoryRecallEngine(settings, service) { fixtures.now } to (settings to service)
    }

    fun golden(path: String): String = readTestResource("golden/memory/$path")
}
