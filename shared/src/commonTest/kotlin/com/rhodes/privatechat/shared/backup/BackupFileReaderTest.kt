package com.rhodes.privatechat.shared.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 测试用最小 ZIP 写出器（stored 条目，够构造坏包变体；不压缩）。 */
object TestZipWriter {
    fun write(entries: List<Pair<String, ByteArray>>): ByteArray {
        val locals = ArrayList<ByteArray>()
        var offset = 0
        val central = ArrayList<ByteArray>()
        entries.forEach { (name, data) ->
            val nameBytes = name.encodeToByteArray()
            val local = ByteArray(30 + nameBytes.size + data.size)
            writeLe32(local, 0, 0x04034b50L)
            writeLe16(local, 8, 0)   // method stored
            writeLe32(local, 14, BackupCrypto.crc32(data))
            writeLe32(local, 18, data.size.toLong())
            writeLe32(local, 22, data.size.toLong())
            writeLe16(local, 26, nameBytes.size)
            nameBytes.copyInto(local, 30)
            data.copyInto(local, 30 + nameBytes.size)
            locals += local

            val cd = ByteArray(46 + nameBytes.size)
            writeLe32(cd, 0, 0x02014b50L)
            writeLe16(cd, 10, 0)
            writeLe32(cd, 16, BackupCrypto.crc32(data))
            writeLe32(cd, 20, data.size.toLong())
            writeLe32(cd, 24, data.size.toLong())
            writeLe16(cd, 28, nameBytes.size)
            writeLe32(cd, 42, offset.toLong())
            nameBytes.copyInto(cd, 46)
            central += cd
            offset += local.size
        }
        val cdSize = central.sumOf { it.size }
        val eocd = ByteArray(22)
        writeLe32(eocd, 0, 0x06054b50L)
        writeLe16(eocd, 10, entries.size)
        writeLe32(eocd, 12, cdSize.toLong())
        writeLe32(eocd, 16, offset.toLong())
        val out = ByteArray(offset + cdSize + 22)
        var p = 0
        locals.forEach { it.copyInto(out, p); p += it.size }
        central.forEach { it.copyInto(out, p); p += it.size }
        eocd.copyInto(out, p)
        return out
    }

    private fun writeLe16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun writeLe32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
}

class BackupFileReaderTest {

    private val reader = BackupFileReader()

    @Test
    fun goldenPassesAllReaderChecks() {
        val archive = reader.read(GoldenBackupFixture.zipBytes)
        val m = archive.manifest
        assertEquals(BACKUP_FULL_FORMAT, m.format)
        assertEquals(BACKUP_FORMAT_VERSION, m.formatVersion)
        assertEquals("full", m.scope)
        assertEquals("1.14.27", m.appVersion)
        assertEquals(1, m.schemaVersion)
        assertEquals("paged_payload", m.layout)
        assertTrue(m.mediaIncluded)
        assertEquals(listOf("credentials", "vector_index", "worker_state", "temporary_files"), m.excludedCategories)

        // 分页：3 页、路径与 ZIP 一致（reader 内部已校验；此处复验形状）
        assertEquals(
            setOf("data/pages/roles/000000.json", "data/pages/chats/000000.json", "data/pages/chats/010000.json"),
            m.pages.map { it.path }.toSet(),
        )
        // 合并结果：1 角色 / 1 会话 / 7 消息 / 1 媒体
        assertEquals(1, archive.payload.content.operators.orEmpty().size)
        assertEquals(1, archive.payload.content.sessions.orEmpty().size)
        assertEquals(7, archive.payload.content.messages.orEmpty().size)
        assertEquals(1, archive.payload.media.size)
        assertEquals(emptyList(), archive.issues)

        // 消息形状（对应金样 md）：msg4 image、msg5 ai_json 内嵌 imageUri
        val messages = archive.payload.content.messages.orEmpty()
        assertEquals("image", messages[3].type)
        assertTrue(messages[3].content.contains("\"imageUri\""))
        assertEquals("ai_json", messages[4].type)
        assertTrue(messages[4].content.contains("\"imageUri\""))
        // 会话 id 规则 session_<operatorId>
        assertEquals("session_golden", archive.payload.content.sessions.orEmpty().single().id)
        assertEquals("golden", archive.payload.content.sessions.orEmpty().single().operatorId)
        // mediaId 溯源：sha256(canonicalPath)[:24]（BackupMediaCollector.kt:71-72）
        val canonical = "/data/data/com.buzidongshouhuoji.privatechat/files/images/golden_1x1.png"
        assertEquals("local-image/" + BackupCrypto.sha256Hex(canonical.encodeToByteArray()).take(24), archive.payload.media.single().mediaId)
    }

    @Test
    fun validateReturnsValid() {
        val result = reader.validate(GoldenBackupFixture.zipBytes)
        val valid = assertIs<BackupValidationResult.Valid>(result)
        assertEquals("1f3d9b7c-2a4e-4c8b-9d01-0000000000a1", valid.manifest.backupId)
        assertTrue(valid.issues.isEmpty())
    }

    @Test
    fun missingManifestRejected() {
        val z = BackupZip.read(GoldenBackupFixture.zipBytes, 1000, 100L * 1024 * 1024, 500L * 1024 * 1024)
        val entries = z.filterNot { it.name == BACKUP_MANIFEST_PATH }.map { it.name to it.data }
        val bad = TestZipWriter.write(entries)
        assertFailsWith<BackupFormatException> { reader.read(bad) }.also {
            assertTrue(it.message!!.contains("缺少 manifest.json"))
        }
    }

    @Test
    fun unsupportedFormatVersionRejected() {
        val z = BackupZip.read(GoldenBackupFixture.zipBytes, 1000, 100L * 1024 * 1024, 500L * 1024 * 1024)
        val manifest = z.single { it.name == BACKUP_MANIFEST_PATH }.data.decodeToString()
            .replace("\"formatVersion\":2", "\"formatVersion\":3")
        val entries = z.map { if (it.name == BACKUP_MANIFEST_PATH) it.name to manifest.encodeToByteArray() else it.name to it.data }
        assertFailsWith<BackupFormatException> { reader.read(TestZipWriter.write(entries)) }.also {
            assertTrue(it.message!!.contains("不支持的备份格式版本"))
        }
    }

    @Test
    fun unsafePathRejected() {
        val z = BackupZip.read(GoldenBackupFixture.zipBytes, 1000, 100L * 1024 * 1024, 500L * 1024 * 1024)
        val entries = z.map { it.name to it.data } + ("data/pages/../evil.json" to "{}".encodeToByteArray())
        assertFailsWith<BackupFormatException> { reader.read(TestZipWriter.write(entries)) }.also {
            assertTrue(it.message!!.contains("ZIP 路径不安全"))
        }
    }

    @Test
    fun duplicatePathRejected() {
        val z = BackupZip.read(GoldenBackupFixture.zipBytes, 1000, 100L * 1024 * 1024, 500L * 1024 * 1024)
        val entries = z.map { it.name to it.data } + (BACKUP_MANIFEST_PATH to "{}".encodeToByteArray())
        assertFailsWith<BackupFormatException> { reader.read(TestZipWriter.write(entries)) }.also {
            assertTrue(it.message!!.contains("ZIP 存在重复路径"))
        }
    }

    @Test
    fun filesMissingMediaRejected() {
        val z = BackupZip.read(GoldenBackupFixture.zipBytes, 1000, 100L * 1024 * 1024, 500L * 1024 * 1024)
        val manifest = z.single { it.name == BACKUP_MANIFEST_PATH }.data.decodeToString()
        // files 清单去掉媒体条目 → 「manifest 文件清单与 ZIP 内容不一致」
        val mediaEntry = ",{\"path\":\"media/images/8b9e940c3c9d6d39c4a1dd8c.png\",\"size\":70,\"sha256\":\"${GoldenBackupFixture.MEDIA_SHA256}\"}"
        assertTrue(manifest.contains(mediaEntry), "金样 manifest 形状变化，测试夹具需同步")
        val trimmed = manifest.replace(mediaEntry, "")
        val entries = z.map { if (it.name == BACKUP_MANIFEST_PATH) it.name to trimmed.encodeToByteArray() else it.name to it.data }
        assertFailsWith<BackupFormatException> { reader.read(TestZipWriter.write(entries)) }.also {
            assertTrue(it.message!!.contains("manifest 文件清单与 ZIP 内容不一致"))
        }
    }

    @Test
    fun corruptedMediaRejected() {
        val z = BackupZip.read(GoldenBackupFixture.zipBytes, 1000, 100L * 1024 * 1024, 500L * 1024 * 1024)
        val entries = z.map { entry ->
            if (entry.name.startsWith("media/")) {
                val b = entry.data.copyOf()
                b[0] = (b[0].toInt() xor 0xFF).toByte()
                entry.name to b
            } else entry.name to entry.data
        }
        // 破坏后：Zip 条目 CRC 校验（ZipInputStream 等价）或 manifest sha256 校验必拒其一。
        assertFailsWith<BackupFormatException> { reader.read(TestZipWriter.write(entries)) }
    }

    @Test
    fun garbageInputRejected() {
        val bad = assertIs<BackupValidationResult.Invalid>(reader.validate("not a zip".encodeToByteArray()))
        assertTrue(bad.reason.isNotBlank())
    }
}
