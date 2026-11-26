package com.rhodes.privatechat.shared.domain.media

import com.rhodes.privatechat.shared.backup.BackupCrypto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 规格卡验收 #8-#10 + 契约 17/18 的媒体目录行为测试（真实文件系统）。
 * 平台源集（androidUnitTest / iosTest）各提供临时目录 TestDirMediaStore。
 */
abstract class MediaRepositoryTest {

    /** 平台真实临时目录 store（androidUnitTest = java.io 临时目录；iosTest = NSTemporaryDirectory）。 */
    protected abstract fun createStore(): TestDirMediaStore

    // --- 验收 #8：ingest 失败（空源/不可读）→ 无残留文件 ---
    @Test
    fun cleanup_failedIngestLeavesNoResidue() = runBlocking {
        val store = createStore()
        try {
            val repo = MediaRepository(store)
            // 不可读源：不存在的路径
            val missingRef = "file:///no/such/dir/golden.png"
            assertEquals(missingRef, repo.ingest(MediaSource.PICKER, missingRef), "不可读源必须回退原引用")
            // 空源：0 字节文件（镜像 ImageUtils.kt:27 「Never leave an empty owned image」）
            val emptyRef = store.createSourceFile("empty.png", ByteArray(0))
            assertEquals(emptyRef, repo.ingest(MediaSource.PICKER, emptyRef), "空源必须回退原引用")
            assertTrue(store.relativeFiles().isEmpty(), "失败 ingest 不得残留任何文件，实际：${store.relativeFiles()}")
        } finally {
            store.cleanup()
        }
    }

    // --- 契约 18：取消录音删除 voice 文件（oracle LocalAudioController.kt:140-142） ---
    @Test
    fun cleanup_deleteAudioRemovesVoiceFile() = runBlocking {
        val store = createStore()
        try {
            val repo = MediaRepository(store)
            val pcm = ByteArray(32) { (it * 7).toByte() }
            val src = store.createSourceFile("rec.pcm", pcm)
            val id = repo.ingest(MediaSource.VOICE, src)
            assertTrue(id.startsWith("media/"), "voice ingest 应返回 media ID，实际 $id")
            assertTrue(store.exists("voice/$id"))
            assertTrue(repo.deleteAudio(id), "deleteAudio 返回成功")
            assertFalse(store.exists("voice/$id"), "voice 文件必须消失")
            assertTrue(store.relativeFiles().isEmpty(), "deleteAudio 后 media 根无残留")
            assertTrue(repo.deleteAudio(id), "重复删除幂等")
        } finally {
            store.cleanup()
        }
    }

    // --- 契约 13/边界：媒体目录幂等创建 ---
    @Test
    fun cleanup_mediaDirectoriesAreCreatedIdempotently() {
        val store = createStore()
        try {
            val first = store.ensureDirectories()
            val second = store.ensureDirectories()
            assertEquals(first, second, "幂等创建：两次结果一致")
            assertTrue(first.containsAll(listOf("images", "chat_images", "voice", "newspapers")), "四个媒体子目录齐备：$first")
        } finally {
            store.cleanup()
        }
    }

    // --- 验收 #10：resolve 往返字节 sha256 一致（契约 17） ---
    @Test
    fun roundtrip_ingestThenResolveYieldsIdenticalBytes() = runBlocking {
        val store = createStore()
        try {
            val repo = MediaRepository(store)
            val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 11, 22, 33, 44)
            val src = store.createSourceFile("avatar.png", png)
            val id = repo.ingest(MediaSource.PICKER, src)
            val resolved = repo.resolve(id)
            assertEquals(store.fileUrl("images/$id"), resolved, "resolve 返回落点 file:// URL")
            assertContentEquals(png, store.readBytes("images/$id")!!, "落盘字节与源一致")
            assertEquals(BackupCrypto.sha256Hex(png), BackupCrypto.sha256Hex(store.readBytes("images/$id")!!), "sha256 一致")
        } finally {
            store.cleanup()
        }
    }

    // --- 验收 #9：媒体 ID 校验拒绝（T9 契约 11 同断言集） ---
    @Test
    fun rejectInvalidIds_fiveInvalidForms() {
        val store = createStore()
        try {
            val repo = MediaRepository(store)
            val invalid = listOf(
                "",                 // 空
                "../evil.png",      // ..
                "/abs/x.png",       // 绝对路径
                "file:///x.png",    // file://
                "https://cdn/x.png",// ://
            )
            for (bad in invalid) {
                assertNull(MediaId.parse(bad), "校验必须拒绝：'$bad'")
                assertNull(repo.resolve(bad), "resolve 必须拒绝：'$bad'")
            }
            // resolve 语义补充（契约 17）：缺失文件 / 内置头像 / 备份格式 ID → nil（渲染占位）
            assertNull(repo.resolve("media/00000000-0000-4000-8000-000000000000.jpg"), "缺失文件 → nil")
            assertNull(repo.resolve("42"), "内置头像 → nil（渲染层走 bundle 分支）")
            assertNull(repo.resolve("local-image/8b9e940c3c9d6d39c4a1dd8c"), "备份格式 ID 不经运行时 resolve")
        } finally {
            store.cleanup()
        }
    }

    // --- 边界用例清单：重复 ingest 同一源得不同 ID（Android img_<ts> 同语义） ---
    @Test
    fun duplicateIngest_sameSourceYieldsDistinctIds() = runBlocking {
        val store = createStore()
        try {
            val repo = MediaRepository(store)
            val bytes = ByteArray(16) { it.toByte() }
            val src = store.createSourceFile("dup.png", bytes)
            val id1 = repo.ingest(MediaSource.PICKER, src)
            val id2 = repo.ingest(MediaSource.PICKER, src)
            assertNotEquals(id1, id2, "重复 ingest 同一源必须得不同 ID")
            assertContentEquals(bytes, store.readBytes("images/$id1")!!)
            assertContentEquals(bytes, store.readBytes("images/$id2")!!)
        } finally {
            store.cleanup()
        }
    }

    // --- 契约 13/14：备份导入落点与确定性 ID ---
    @Test
    fun backupImport_landsUnderRestoredMediaWithDeterministicId() = runBlocking {
        val store = createStore()
        try {
            val repo = MediaRepository(store)
            val bytes = ByteArray(8) { (it + 1).toByte() }
            val src = store.createSourceFile("golden_1x1.png", bytes)
            val id1 = repo.ingest(MediaSource.BACKUP_IMPORT, "file://$src", backupId = "bak_1")
            val sha24 = BackupCrypto.sha256Hex(src.encodeToByteArray()).take(24)
            assertEquals("local-image/$sha24", id1, "备份导入 ID = local-image/<sha256(canonicalPath)[:24]>")
            assertContentEquals(bytes, store.readBytes("restored_media/bak_1/media/images/$sha24.png")!!)
            // 同源换 backupId：ID 确定不变，落点按 backupId 隔离
            val id2 = repo.ingest(MediaSource.BACKUP_IMPORT, "file://$src", backupId = "bak_2")
            assertEquals(id1, id2, "mediaId 路径派生语义：同 canonicalPath 同 ID")
            assertContentEquals(bytes, store.readBytes("restored_media/bak_2/media/images/$sha24.png")!!)
            // 非法 backupId → 失败语义（返回原引用，无落盘）
            val before = store.relativeFiles().size
            assertEquals("file://$src", repo.ingest(MediaSource.BACKUP_IMPORT, "file://$src", backupId = "../evil"))
            assertEquals(before, store.relativeFiles().size)
        } finally {
            store.cleanup()
        }
    }
}
