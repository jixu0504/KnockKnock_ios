package com.rhodes.privatechat.shared.vector

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.abs

/**
 * T71 float32 小端编解码：往返、长度/字节序、无效输入，以及 BLOB 相对 JSON 文本的体积收益。
 * 纯函数测试，不依赖数据库。
 */
class VectorEmbeddingCodecTest {

    @Test
    fun roundTripKeepsValuesWithinFloatPrecision() {
        val values = listOf(0.1, -3.25, 1e-7, 123456.789, 0.0, -1.0, 2.0)
        val decoded = VectorEmbeddingCodec.decode(VectorEmbeddingCodec.encode(values))
        assertEquals(values.size, decoded?.size)
        values.forEachIndexed { index, expected ->
            val actual = decoded!![index]
            val tolerance = maxOf(1e-6, abs(expected) * 1e-6)
            assertTrue(abs(actual - expected) <= tolerance, "index=$index expected=$expected actual=$actual")
        }
    }

    @Test
    fun encodesLittleEndianFloat32Bytes() {
        // 1.0f=0x3F800000、-2.0f=0xC0000000、0.0f=0x00000000，按小端逐字节排列。
        val bytes = VectorEmbeddingCodec.encode(listOf(1.0, -2.0, 0.0))
        assertContentEquals(
            byteArrayOf(
                0x00, 0x00, 0x80.toByte(), 0x3F,
                0x00, 0x00, 0x00, 0xC0.toByte(),
                0x00, 0x00, 0x00, 0x00,
            ),
            bytes,
        )
        assertContentEquals(listOf(1.0, -2.0, 0.0), VectorEmbeddingCodec.decode(bytes))
    }

    @Test
    fun byteLengthIsFourTimesDimension() {
        assertEquals(0, VectorEmbeddingCodec.encodedSize(0))
        assertEquals(4, VectorEmbeddingCodec.encodedSize(1))
        assertEquals(1536 * 4, VectorEmbeddingCodec.encodedSize(1536))
        assertEquals(1536 * 4, VectorEmbeddingCodec.encode(List(1536) { it / 1536.0 }).size)
    }

    @Test
    fun rejectsInvalidEncodeInput() {
        assertFailsWith<IllegalArgumentException> { VectorEmbeddingCodec.encode(emptyList()) }
        assertFailsWith<IllegalArgumentException> { VectorEmbeddingCodec.encode(listOf(1.0, Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { VectorEmbeddingCodec.encode(listOf(Double.POSITIVE_INFINITY)) }
    }

    @Test
    fun decodeRejectsMissingOrMalformedBlobs() {
        assertNull(VectorEmbeddingCodec.decode(null))
        assertNull(VectorEmbeddingCodec.decode(ByteArray(0)))
        // 非 4 字节对齐
        assertNull(VectorEmbeddingCodec.decode(byteArrayOf(0x00, 0x00, 0x80.toByte())))
        assertNull(VectorEmbeddingCodec.decode(ByteArray(6)))
        // 0x7FC00000 = NaN：字节合法但不是可用向量
        assertNull(VectorEmbeddingCodec.decode(byteArrayOf(0x00, 0x00, 0xC0.toByte(), 0x7F)))
    }

    @Test
    fun isUsableChecksEmptinessFinitenessAndDimension() {
        assertFalse(VectorEmbeddingCodec.isUsable(emptyList()))
        assertFalse(VectorEmbeddingCodec.isUsable(listOf(Double.NaN)))
        assertFalse(VectorEmbeddingCodec.isUsable(listOf(Double.NEGATIVE_INFINITY)))
        assertTrue(VectorEmbeddingCodec.isUsable(listOf(0.0, 1.0)))
        assertTrue(VectorEmbeddingCodec.isUsable(listOf(0.0, 1.0), expectedDimension = 2))
        assertFalse(VectorEmbeddingCodec.isUsable(listOf(0.0, 1.0), expectedDimension = 3))
        assertTrue(VectorEmbeddingCodec.isUsable(listOf(0.0, 1.0), expectedDimension = 0))
    }

    @Test
    fun blobIsLessThanHalfTheLegacyJsonText() {
        val embedding = List(1536) { (it % 17) / 17.0 }
        val jsonText = Json.encodeToString(embedding)
        val jsonBytes = jsonText.encodeToByteArray().size
        val blobBytes = VectorEmbeddingCodec.encode(embedding).size
        assertEquals(1536 * 4, blobBytes)
        assertTrue(jsonBytes > blobBytes * 2, "JSON=$jsonBytes BLOB=$blobBytes，BLOB 应小于 JSON 文本的一半")
        assertEquals(embedding.size, VectorEmbeddingCodec.decode(VectorEmbeddingCodec.encode(embedding))?.size)
    }
}