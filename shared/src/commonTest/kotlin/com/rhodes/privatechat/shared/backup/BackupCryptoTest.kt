package com.rhodes.privatechat.shared.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupCryptoTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun sha256KnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            BackupCrypto.sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BackupCrypto.sha256Hex("abc".encodeToByteArray()),
        )
        val long = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            BackupCrypto.sha256Hex(long),
        )
    }

    @Test
    fun crc32KnownVector() {
        // zlib 标准向量："123456789" → 0xCBF43926
        assertEquals(0xCBF43926L, BackupCrypto.crc32("123456789".encodeToByteArray()))
    }

    @Test
    fun hexIsLowercase() {
        val h = BackupCrypto.sha256Hex(hex("deadbeef"))
        assertEquals(h, h.lowercase())
        assertEquals(64, h.length)
    }

    @Test
    fun goldenFixtureBindsToCanonicalArtifact() {
        // 与 ios-port/golden/backup/minimal-1.14.27.md 记录值一致（金样冻结绑定）。
        assertEquals(GoldenBackupFixture.ZIP_SHA256, BackupCrypto.sha256Hex(GoldenBackupFixture.zipBytes))
        assertEquals(3703, GoldenBackupFixture.zipBytes.size)
    }

    @Test
    fun goldenMediaSha256() {
        val archive = BackupFileReader().read(GoldenBackupFixture.zipBytes)
        val entries = BackupZip.read(GoldenBackupFixture.zipBytes, 1_000, 100L * 1024 * 1024, 500L * 1024 * 1024)
        val mediaPath = archive.payload.media.single().archivePath
        val mediaBytes = entries.single { it.name == mediaPath }.data
        assertEquals(GoldenBackupFixture.MEDIA_SHA256, BackupCrypto.sha256Hex(mediaBytes))
        assertTrue(mediaBytes.size == 70)
    }
}
