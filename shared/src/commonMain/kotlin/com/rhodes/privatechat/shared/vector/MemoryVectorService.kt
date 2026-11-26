package com.rhodes.privatechat.shared.vector

import com.rhodes.privatechat.shared.domain.memory.MemoryDiagnostics
import com.rhodes.privatechat.shared.platformSynchronized
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryVectorService(
    private val settings: SettingsRepository,
    private val vectorStoreGateway: VectorStoreGateway,
    private val embeddingFactory: (SettingsRepository) -> EmbeddingGateway = ::createEmbeddingGateway,
) {
    private var gatewaySignature = ""
    private var gateway: EmbeddingGateway? = null
    // iOS-port：原实现为 JVM 匿名 LinkedHashMap 子类（access-order + removeEldestEntry），
    // K/N 无此构造。改为公共 LRU（访问序，容量 32，逐出最旧），语义一致。
    private val embeddingCache = AccessOrderLruMap<String, List<Double>>(32)
    private val embeddingCacheMutex = Mutex()

    private fun activeGateway(): Pair<String, EmbeddingGateway> = platformSynchronized(this) {
        val signature = listOf(settings.vectorProviderMode, settings.vectorProvider, settings.vectorProtocol, settings.vectorBaseUrl, settings.vectorModelName, settings.vectorApiKey, settings.apiKey).joinToString("|")
        if (gateway == null || gatewaySignature != signature) {
            gateway = embeddingFactory(settings)
            gatewaySignature = signature
        }
        return signature to gateway!!
    }

    fun currentEmbeddingSignature(): String = EmbeddingConfigurationSignature.create(
        settings.vectorProviderMode,
        settings.vectorProvider,
        settings.vectorBaseUrl,
        settings.vectorModelName,
        settings.vectorProtocol,
    )

    suspend fun saveMemory(memory: VectorMemory) {
        val signature = currentEmbeddingSignature()
        check(memory.embeddingSignature.isBlank() || memory.embeddingSignature == signature) { "向量模型配置已变化，请重试" }
        val embedding = embedCached(memory.content)
        check(signature == currentEmbeddingSignature()) { "向量配置已变化，请重试；旧索引未覆盖" }
        require(embedding.isUsableEmbedding()) { "Embedding 服务没有返回有效向量" }
        vectorStoreGateway.upsert(memory.copy(embedding = embedding, embeddingSignature = signature))
    }

    suspend fun saveMemoryWithEmbedding(memory: VectorMemory) {
        require(memory.embedding.isUsableEmbedding()) { "Embedding 服务没有返回有效向量" }
        vectorStoreGateway.upsert(memory.copy(embeddingSignature = memory.embeddingSignature.ifBlank { currentEmbeddingSignature() }))
    }

    suspend fun recall(
        ownerType: String,
        ownerId: String,
        query: String,
        limit: Int = 6,
        visibilities: List<String> = emptyList(),
        minScore: Double = 0.0,
        now: Long = 0L,
    ): List<VectorMemory> {
        val signature = currentEmbeddingSignature()
        val queryEmbedding = embedCached(query)
        if (signature != currentEmbeddingSignature()) {
            MemoryDiagnostics.log("Memory/Vector/降级", "嵌入签名在检索过程中变化，本轮结果作废（需重建索引）")
            return emptyList()
        }
        if (!queryEmbedding.isUsableEmbedding()) {
            MemoryDiagnostics.log("Memory/Vector/降级", "查询向量不可用（嵌入服务未返回有效向量）")
            return emptyList()
        }
        return vectorStoreGateway.search(
            VectorSearchRequest(
                ownerType = ownerType,
                ownerId = ownerId,
                query = query,
                queryEmbedding = queryEmbedding,
                limit = limit,
                visibilities = visibilities,
                minScore = minScore,
                now = now,
                embeddingSignature = signature,
            )
        )
    }

    suspend fun search(request: VectorSearchRequest): List<VectorMemory> {
        val signature = currentEmbeddingSignature()
        val queryEmbedding = embedCached(request.query)
        if (signature != currentEmbeddingSignature()) {
            MemoryDiagnostics.log("Memory/Vector/降级", "嵌入签名在检索过程中变化，本轮结果作废（需重建索引）")
            return emptyList()
        }
        return searchWithEmbedding(request.copy(embeddingSignature = signature), queryEmbedding)
    }

    /** Keeps diagnostics able to distinguish gateway latency from local vector-store latency. */
    suspend fun embedForDiagnostics(text: String): List<Double> = embedCached(text)

    suspend fun searchWithEmbedding(request: VectorSearchRequest, queryEmbedding: List<Double>): List<VectorMemory> {
        if (!queryEmbedding.isUsableEmbedding()) return emptyList()
        val signature = currentEmbeddingSignature()
        if (request.embeddingSignature.isNotBlank() && request.embeddingSignature != signature) {
            MemoryDiagnostics.log("Memory/Vector/降级", "请求签名与当前嵌入配置不一致：历史向量需重建后才能召回")
            return emptyList()
        }
        return vectorStoreGateway.search(request.copy(queryEmbedding = queryEmbedding, embeddingSignature = signature))
    }

    suspend fun clearAllMemories() = vectorStoreGateway.clearAllMemories()

    suspend fun deleteMemory(memoryId: String) = vectorStoreGateway.delete(memoryId)

    suspend fun listMemories(ownerType: String, ownerId: String): List<VectorMemory> =
        vectorStoreGateway.listMemories(ownerType, ownerId)

    suspend fun clearSessionMemory(ownerType: String, ownerId: String, sourceId: String) =
        vectorStoreGateway.deleteBySource(ownerType, ownerId, sourceId)

    suspend fun clearOwnerMemory(ownerType: String, ownerId: String) =
        vectorStoreGateway.clearOwnerMemory(ownerType, ownerId)

    private suspend fun embedCached(text: String): List<Double> {
        val normalized = text.trim()
        if (normalized.isBlank()) return emptyList()
        val (signature, gateway) = activeGateway()
        val key = "$signature:$normalized"
        embeddingCacheMutex.withLock { embeddingCache[key] }?.let { return it }
        // Embedding belongs to its caller. A prompt timeout must also cancel this work instead of
        // leaving an application-wide request running after the reply has degraded.
        val embedding = gateway.embed(normalized)
        if (embedding.isUsableEmbedding()) {
            embeddingCacheMutex.withLock { embeddingCache[key] = embedding }
        }
        return embedding
    }

    private fun List<Double>.isUsableEmbedding(): Boolean = isNotEmpty() && all { it.isFinite() }
}

/** 访问序 LRU：读取/写入都把键移到末尾，超过容量逐出最旧键（等价 JVM LinkedHashMap accessOrder + removeEldestEntry）。 */
private class AccessOrderLruMap<K, V>(private val maxEntries: Int) {
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = map.remove(key)?.also { map[key] = it }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > maxEntries) {
            map.remove(map.keys.first())
        }
    }
}
