package com.rhodes.privatechat.shared.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupInflateTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun storedBlock() {
        // raw deflate：stored 块（BTYPE=00，LEN=3/NLEN=0xFFFC）"Hi!"
        val out = BackupInflate.inflate(hex("010300fcff486921"), 3)
        assertContentEquals("Hi!".encodeToByteArray(), out)
    }

    @Test
    fun fixedHuffman() {
        val out = BackupInflate.inflate(
            hex("cb48cdc9c957c8c04e0200"),
            29,
        )
        assertEquals("hello hello hello hello hello", out.decodeToString())
    }

    @Test
    fun dynamicHuffman() {
        val expected = "abcdef0123456789".repeat(30)
        val out = BackupInflate.inflate(
            hex("4b4c4a4e494d3330343236313533b7b04c1ce50f2b3e00"),
            expected.length.toLong(),
        )
        assertEquals(expected, out.decodeToString())
    }

    @Test
    fun truncatedInputFails() {
        assertFailsWith<BackupInflate.InflateException> {
            BackupInflate.inflate(hex("cb48cdc9"), 29)
        }
    }

    @Test
    fun sizeMismatchFails() {
        assertFailsWith<BackupInflate.InflateException> {
            BackupInflate.inflate(hex("010300fcff486921"), 4)
        }
    }
}
