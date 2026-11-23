package com.rhodes.privatechat.shared.domain.media

import com.rhodes.privatechat.shared.backup.BackupCrypto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 规格卡验收 #1 金样路径转换矩阵（9 行用例表逐行断言）+ 验收 #2 备份 mediaId 公式复刻。
 * 平台无关（InMemoryMediaStore），commonTest 直接跑两条路径：
 *   ./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "*MediaPathGoldenMatrixTest*"（真目标）
 *   ./gradlew --no-daemon :shared:testDebugUnitTest --tests "*MediaPathGoldenMatrixTest*"（JVM 对照）
 */
class MediaPathGoldenMatrixTest {

    private val fakePng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4)
    private val fakePcm = ByteArray(64) { it.toByte() }
    private val fakeMp3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 9, 9)

    /** v4 UUID 形态断言（契约 12：UUID 随机）。 */
    private val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private fun uuidOf(id: String, extension: String): String =
        id.removePrefix("media/").removeSuffix(".$extension")

    private fun sha24(path: String): String =
        BackupCrypto.sha256Hex(path.encodeToByteArray()).take(24)

    @Test
    fun goldenMatrix_allNineRows() = runBlocking {
        val store = InMemoryMediaStore()
        val repo = MediaRepository(store)

        // 行 1：备份包内 originalUri → local-image/<sha24>，落 restored_media/<backupId>/media/images/<sha24>.png
        val backupRef = "file:///data/data/com.rhodes.privatechat/files/images/golden_1x1.png"
        store.registerSource(backupRef, fakePng)
        val backupId = repo.ingest(MediaSource.BACKUP_IMPORT, backupRef, backupId = "backup_20261123")
        val expectedSha = sha24(backupRef.removePrefix("file://"))
        assertEquals("local-image/$expectedSha", backupId, "行 1：备份导入 ID = local-image/<sha256(canonicalPath)[:24]>")
        assertContentEquals(
            fakePng,
            store.fileBytes("restored_media/backup_20261123/media/images/$expectedSha.png")!!,
            "行 1：落点 restored_media/<backupId>/media/images/<sha24>.png 且字节一致",
        )

        // 行 2：相册 content://（头像裁剪确认）→ media/<u>.jpg → images/
        val pickerRef = "content://media/external/images/media/123"
        store.registerSource(pickerRef, fakePng)
        val pickerId = repo.ingest(MediaSource.PICKER, pickerRef)
        assertTrue(pickerId.matches(Regex("media/[0-9a-f\\-]{36}\\.jpg")), "行 2：ID 形态 media/<u>.jpg，实际 $pickerId")
        assertTrue(uuidRegex.matches(uuidOf(pickerId, "jpg")), "行 2：UUID v4 形态")
        assertContentEquals(fakePng, store.fileBytes("images/$pickerId")!!, "行 2：落点 media/images/<u>.jpg 且字节一致")

        // 行 3：相机 cacheDir/camera/photo_<ts>.jpg → media/<u>.jpg → chat_images/
        val cameraRef = "cacheDir/camera/photo_1730000000000.jpg"
        store.registerSource(cameraRef, fakePng)
        val cameraId = repo.ingest(MediaSource.CAMERA, cameraRef)
        assertTrue(cameraId.matches(Regex("media/[0-9a-f\\-]{36}\\.jpg")), "行 3：ID 形态 media/<u>.jpg")
        assertContentEquals(fakePng, store.fileBytes("chat_images/$cameraId")!!, "行 3：落点 media/chat_images/<u>.jpg 且字节一致")

        // 行 4：裁剪缓存 cacheDir/crop_<ts>（背景确认，无扩展名）→ 默认 jpg → images/
        val cropRef = "cacheDir/crop_1730000000123"
        store.registerSource(cropRef, fakePng)
        val cropId = repo.ingest(MediaSource.PICKER, cropRef)
        assertTrue(cropId.matches(Regex("media/[0-9a-f\\-]{36}\\.jpg")), "行 4：无扩展名 → 默认 .jpg")
        assertContentEquals(fakePng, store.fileBytes("images/$cropId")!!, "行 4：落点 media/images/<u>.jpg 且字节一致")

        // 行 5：录音 voice_<UUID>.pcm → media/<u>.pcm → voice/
        val voiceRef = "filesDir/voice/voice_3f2a1b9c-1111-2222-3333-444455556666.pcm"
        store.registerSource(voiceRef, fakePcm)
        val voiceId = repo.ingest(MediaSource.VOICE, voiceRef)
        assertTrue(voiceId.matches(Regex("media/[0-9a-f\\-]{36}\\.pcm")), "行 5：ID 形态 media/<u>.pcm，扩展名保真")
        assertContentEquals(fakePcm, store.fileBytes("voice/$voiceId")!!, "行 5：落点 media/voice/<u>.pcm 且字节一致")

        // 行 6：TTS tts_<UUID>.mp3 → media/<u>.mp3 → voice/
        val ttsRef = "filesDir/voice/tts_3f2a1b9c-1111-2222-3333-444455556666.mp3"
        store.registerSource(ttsRef, fakeMp3)
        val ttsId = repo.ingest(MediaSource.VOICE, ttsRef)
        assertTrue(ttsId.matches(Regex("media/[0-9a-f\\-]{36}\\.mp3")), "行 6：ID 形态 media/<u>.mp3")
        assertContentEquals(fakeMp3, store.fileBytes("voice/$ttsId")!!, "行 6：落点 media/voice/<u>.mp3 且字节一致")

        // 行 7：内置头像 "42" → 原样保留（不 ID 化、不落盘）
        val filesBefore = store.fileCount()
        assertEquals("42", repo.ingest(MediaSource.BUILTIN_AVATAR, "42"), "行 7：内置头像数字串原样")
        assertEquals(filesBefore, store.fileCount(), "行 7：内置头像不落 media 目录")

        // 行 8：远程 https://（AI 生成图）→ 原样透传（不落盘、不进备份收集）
        val remoteRef = "https://cdn.example/x.png"
        assertEquals(remoteRef, repo.ingest(MediaSource.REMOTE, remoteRef), "行 8：远程引用原样透传")
        assertEquals(filesBefore, store.fileCount(), "行 8：远程引用不落盘")

        // 行 9：非法引用 → 拒绝（T9 契约 11 同断言集）：不产生任何落盘副作用
        val invalidRefs = listOf("../evil.png", "/abs/x.png", "file:///x")
        for (bad in invalidRefs) {
            assertNull(MediaId.parse(bad), "行 9：非法 ID 必须被拒绝（$bad）")
            assertEquals(bad, repo.ingest(MediaSource.PICKER, bad), "行 9：非法引用原样返回（$bad）")
        }
        assertEquals(filesBefore, store.fileCount(), "行 9：非法引用不得产生任何文件")
    }

    @Test
    fun backupFormulas_matchAndroidBackupMediaCollector() {
        val repo = MediaRepository(InMemoryMediaStore())
        val canonicalPath = "/data/data/com.rhodes.privatechat/files/images/avatar_x.png"
        val expectedSha = sha24(canonicalPath)
        // oracle BackupMediaCollector.kt:26,71-72 —— mediaId = local-image/<sha256(canonicalPath)[:24]>
        assertEquals("local-image/$expectedSha", repo.backupMediaIdFor(canonicalPath))
        // oracle BackupMediaCollector.kt:27 —— archivePath = media/images/<sha24>.<ext>
        assertEquals("media/images/$expectedSha.png", repo.backupArchivePathFor(canonicalPath, "png"))
        assertEquals("media/images/$expectedSha.jpg", repo.backupArchivePathFor(canonicalPath, ".JPG"), "扩展名小写保真")
        assertEquals("media/images/$expectedSha", repo.backupArchivePathFor(canonicalPath, ""), "无扩展名不带点号后缀")

        // 同公式 Kotlin 版与已知向量互验（与 T10 验收 #6 同断言：sha256 前缀 24）
        val knownPath = "/data/user/0/com.rhodes.privatechat/files/images/golden_1x1.png"
        assertEquals(
            "local-image/" + BackupCrypto.sha256Hex(knownPath.encodeToByteArray()).take(24),
            repo.backupMediaIdFor(knownPath),
        )
    }
}
