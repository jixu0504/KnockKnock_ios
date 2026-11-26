package com.rhodes.privatechat.shared.vector

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T71 gateway 存取（iOS 模拟器真实 SQLite）：新行写 float32 BLOB 且清空 JSON，
 * 旧行（blob 为 NULL）回退 embeddingJson，二者同库共存。
 */
class LocalVectorStoreGatewayBlobTest {

    private var driver: NativeSqliteDriver? = null

    @AfterTest
    fun tearDown() {
        driver?.close()
        driver = null
    }

    private fun newGateway(): Pair<LocalVectorStoreGateway, RhodesDatabase> {
        val dbName = "vector_blob_test_${getpid()}_${Random.nextInt(1_000_000)}.db"
        val created = NativeSqliteDriver(RhodesDatabase.Schema, dbName)
        driver = created
        val database = RhodesDatabase(created)
        val wrapper = DatabaseWrapper().also { it.injectForTest(database) }
        return LocalVectorStoreGateway(wrapper) to database
    }

    private fun memory(id: String = "v1", embedding: List<Double> = listOf(1.0, 2.0, 3.0)) = VectorMemory(
        id = id,
        ownerType = "operator",
        ownerId = "user",
        sourceType = "memory_v2_l1",
        sourceId = "src",
        content = "hello",
        importance = 1.0,
        embedding = embedding,
        tags = "",
        visibility = "public",
        embeddingSignature = "sig-v1",
        createdAt = 1L,
        expiresAt = Long.MAX_VALUE,
    )

    private fun request() = VectorSearchRequest(
        ownerType = "operator",
        ownerId = "user",
        query = "hello",
        queryEmbedding = listOf(1.0, 2.0, 3.0),
        limit = 6,
        embeddingSignature = "sig-v1",
    )

    @Test
    fun upsertWritesBlobAndClearsJsonThenSearchDecodesIt() = runBlocking {
        val (gateway, database) = newGateway()
        gateway.upsert(memory())

        val row = database.vectorMemoriesQueries.getVectorMemoriesByOwner("operator", "user").executeAsOne()
        assertEquals("", row.embeddingJson, "写入 BLOB 后 embeddingJson 必须为空串")
        assertEquals(3L, row.embeddingDimension)
        assertEquals(VectorEmbeddingCodec.DTYPE_FLOAT32, row.embeddingDtype)
        val blob = assertNotNull(row.embeddingBlob)
        assertEquals(3 * 4, blob.size)
        assertEquals(listOf(1.0, 2.0, 3.0), VectorEmbeddingCodec.decode(blob))

        val hit = gateway.search(request()).single()
        assertEquals(listOf(1.0, 2.0, 3.0), hit.embedding)
        assertTrue(hit.similarity > 0.999, "同向量余弦应接近 1，实际 ${hit.similarity}")
    }

    @Test
    fun legacyJsonRowWithoutBlobFallsBackToEmbeddingJson() = runBlocking {
        val (gateway, database) = newGateway()
        database.vectorMemoriesQueries.insertVectorMemory(
            "legacy", "operator", "user", "memory_v2_l1", "src", "old", 1.0,
            "[1.0,2.0,3.0]", "", "public", "sig-v1", 1L, Long.MAX_VALUE,
        )

        val row = database.vectorMemoriesQueries.getVectorMemoriesByOwner("operator", "user").executeAsOne()
        assertNull(row.embeddingBlob)
        assertEquals(0L, row.embeddingDimension)
        assertEquals(listOf(1.0, 2.0, 3.0), gateway.listMemories("operator", "user").single().embedding)
        assertEquals(listOf(1.0, 2.0, 3.0), gateway.search(request()).single().embedding)
    }

    @Test
    fun blobTakesPriorityWhenBothBlobAndJsonPresent() = runBlocking {
        val (gateway, database) = newGateway()
        database.vectorMemoriesQueries.insertVectorMemoryWithBlob(
            id = "both",
            ownerType = "operator",
            ownerId = "user",
            sourceType = "memory_v2_l1",
            sourceId = "src",
            content = "both",
            importance = 1.0,
            embeddingJson = "[0.0]",
            tags = "",
            visibility = "public",
            embeddingSignature = "sig-v1",
            createdAt = 1L,
            expiresAt = Long.MAX_VALUE,
            embeddingBlob = VectorEmbeddingCodec.encode(listOf(2.0, 0.0)),
            embeddingDimension = 2L,
            embeddingDtype = VectorEmbeddingCodec.DTYPE_FLOAT32,
        )

        assertEquals(listOf(2.0, 0.0), gateway.listMemories("operator", "user").single().embedding)
    }

    @Test
    fun emptyEmbeddingStoresNullBlobWithZeroDimension() = runBlocking {
        val (gateway, database) = newGateway()
        gateway.upsert(memory(embedding = emptyList()))

        val row = database.vectorMemoriesQueries.getVectorMemoriesByOwner("operator", "user").executeAsOne()
        assertNull(row.embeddingBlob)
        assertEquals(0L, row.embeddingDimension)
        assertEquals("", row.embeddingJson)
        assertTrue(gateway.listMemories("operator", "user").single().embedding.isEmpty())
        assertTrue(gateway.diagnose(request()).decodeFailures == 0, "空向量不应计为解码失败")
    }

    @Test
    fun upsertRejectsNonFiniteEmbedding() {
        val (gateway, _) = newGateway()
        assertFailsWith<IllegalArgumentException> {
            runBlocking { gateway.upsert(memory(embedding = listOf(1.0, Double.NaN))) }
        }
    }
}