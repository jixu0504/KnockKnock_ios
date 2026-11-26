package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.EmbeddingConfigurationSignature
import com.rhodes.privatechat.shared.vector.EmbeddingGateway
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import com.rhodes.privatechat.shared.vector.VectorSearchRequest
import com.rhodes.privatechat.shared.vector.VectorStoreGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 验收 #7（向量签名/会话隔离矩阵）：签名格式（openai_compat vs aliyun_native）、配置漂移 →
 * 空召回/抛错、旧签名向量不召回、upsert 原记忆校验失败拒写、嵌入失败不覆盖旧索引
 * （oracle MemoryVectorReliabilityTest.kt 迁移 + EmbeddingConfigurationSignature/MemoryVectorService 契约 18/19）。
 */
class MemoryVectorSignatureTest {

    private fun repository(): SettingsRepository = SettingsRepository(PromptTestSettings())

    private class Store : VectorStoreGateway {
        val records = mutableMapOf<String, VectorMemory>()
        var searches = 0
        override suspend fun upsert(memory: VectorMemory) { records[memory.id] = memory }
        override suspend fun search(request: VectorSearchRequest): List<VectorMemory> {
            searches++
            return records.values.toList()
        }
        override suspend fun listMemories(ownerType: String, ownerId: String) = emptyList<VectorMemory>()
        override suspend fun delete(memoryId: String) { records.remove(memoryId) }
        override suspend fun deleteBySource(ownerType: String, ownerId: String, sourceId: String) {}
        override suspend fun clearOwnerMemory(ownerType: String, ownerId: String) {}
        override suspend fun clearAllMemories() { records.clear() }
    }

    private fun memory(text: String = "原记忆") = VectorMemory(
        "id", "operator", "op", "memory_v2_l1", "source", text, embedding = listOf(1.0, 0.0),
    )

    @Test
    fun signatureFormatKeepsLegacyOpenAiShapeAndSeparatesNativeProtocols() {
        assertEquals(
            "third_party|https://example.com/v1/|embedding-model",
            EmbeddingConfigurationSignature.create("third_party", "openai", "https://example.com/v1/", "embedding-model"),
        )
        assertEquals(
            "third_party|https://example.com/v1/|embedding-model|protocol=aliyun_native",
            EmbeddingConfigurationSignature.create(
                "third_party", "openai", "https://example.com/v1/", "embedding-model", "aliyun_native",
            ),
        )
        assertTrue(
            EmbeddingConfigurationSignature.create("third_party", "x", "u", "m", "aliyun_native") !=
                EmbeddingConfigurationSignature.create("third_party", "x", "u", "m", "openai_compat"),
            "不同协议必须隔离向量空间",
        )
    }

    @Test
    fun failedReplacementPreservesExistingVector() = runBlocking {
        val store = Store().apply { records["id"] = memory() }
        val service = MemoryVectorService(repository(), store) { object : EmbeddingGateway {
            override suspend fun embed(text: String): List<Double> = throw IllegalStateException("offline")
        } }
        assertFailsWith<IllegalStateException> { service.saveMemory(memory("新记忆")) }
        assertEquals("原记忆", store.records["id"]?.content)
    }

    @Test
    fun emptyOrNonFiniteEmbeddingCannotReplaceWorkingIndex() = runBlocking {
        for (vector in listOf(emptyList(), listOf(Double.NaN))) {
            val store = Store().apply { records["id"] = memory() }
            val service = MemoryVectorService(repository(), store) { object : EmbeddingGateway {
                override suspend fun embed(text: String): List<Double> = vector
            } }
            assertFailsWith<IllegalArgumentException> { service.saveMemory(memory("新记忆")) }
            assertEquals("原记忆", store.records["id"]?.content)
        }
    }

    @Test
    fun changingModelDuringEmbeddingCannotPublishUnderWrongSignature() = runBlocking {
        val settingsRepository = repository()
        val store = Store().apply { records["id"] = memory() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = MemoryVectorService(settingsRepository, store) { object : EmbeddingGateway {
            override suspend fun embed(text: String): List<Double> {
                entered.complete(Unit)
                release.await()
                return listOf(1.0, 0.0)
            }
        } }
        val job = async { runCatching { service.saveMemory(memory("新记忆")) } }
        entered.await()
        settingsRepository.vectorModelName = "new-model"
        release.complete(Unit)
        assertTrue(job.await().isFailure)
        assertEquals("原记忆", store.records["id"]?.content)
    }

    @Test
    fun cancellationDoesNotEraseOldIndex() = runBlocking {
        val store = Store().apply { records["id"] = memory() }
        val entered = CompletableDeferred<Unit>()
        val service = MemoryVectorService(repository(), store) { object : EmbeddingGateway {
            override suspend fun embed(text: String): List<Double> {
                entered.complete(Unit)
                awaitCancellation()
            }
        } }
        val job = launch { service.saveMemory(memory("新记忆")) }
        entered.await()
        job.cancelAndJoin()
        assertEquals("原记忆", store.records["id"]?.content)
    }

    @Test
    fun precomputedQueryFromAnotherModelIsNeverSearched() = runBlocking {
        val settingsRepository = repository()
        val store = Store()
        val service = MemoryVectorService(settingsRepository, store)
        val signature = service.currentEmbeddingSignature()
        settingsRepository.vectorModelName = "other"
        assertTrue(
            service.searchWithEmbedding(
                VectorSearchRequest("operator", "op", "query", embeddingSignature = signature),
                listOf(1.0, 0.0),
            ).isEmpty(),
        )
        assertEquals(0, store.searches, "旧签名向量检索必须在进入存储层前短路")
    }

    @Test
    fun staleSignatureSaveIsRejectedBeforeSpendingEmbeddingRequest() = runBlocking {
        val settingsRepository = repository()
        val store = Store()
        var calls = 0
        val service = MemoryVectorService(settingsRepository, store) { object : EmbeddingGateway {
            override suspend fun embed(text: String): List<Double> {
                calls++
                return listOf(1.0, 0.0)
            }
        } }
        assertFailsWith<IllegalStateException> { service.saveMemory(memory().copy(embeddingSignature = "old-model")) }
        assertEquals(0, calls)
    }

    @Test
    fun providerChangeRecreatesGatewayAndDoesNotReuseCachedVector() = runBlocking {
        val settingsRepository = repository()
        val store = Store()
        var calls = 0
        var factories = 0
        val service = MemoryVectorService(settingsRepository, store) {
            factories++
            object : EmbeddingGateway {
                override suspend fun embed(text: String): List<Double> {
                    calls++
                    return listOf(1.0, 0.0)
                }
            }
        }
        service.saveMemory(memory())
        service.saveMemory(memory())
        assertEquals(1, calls, "相同签名命中嵌入缓存")
        settingsRepository.vectorProvider = "different-provider"
        service.saveMemory(memory())
        assertEquals(2, factories)
        assertEquals(2, calls)
    }
}
