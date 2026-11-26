package com.rhodes.privatechat.shared.domain.media

import com.rhodes.privatechat.shared.backup.BackupCrypto
import kotlin.random.Random

/**
 * 媒体 ID 化运行时层（规格卡 message-id-media-spec.md 行为契约 B.12-B.20 的 iOS 复刻）。
 *
 * Android oracle（行为依据，非代码移植）：
 *   - ImageUtils.kt:13-33  copyToInternalStorage（失败回退原引用、绝不残留空自有图）
 *   - MainActivity.kt:170-193 相机 → cacheDir/camera → copyChatImageToFiles（chat_images，1920 缩放 JPEG 80）
 *   - MainActivity.kt:197-198 prepareImageForModel（http/https/data: 原样透传）
 *   - LocalAudioController.kt:44/129/135/140-142 语音/TTS 落盘与路径解析/删除
 *   - AvatarImage.kt:24-64 内置头像数字解析（1..147 → bundle 资源，非数字非空 → 远程/本地）
 *   - BackupMediaCollector.kt:24-30,59-72 备份 mediaId/archivePath 公式与 file scheme 白名单
 *
 * 核心规则（契约 12）：新写入文件 ID = `media/<uuid>.<ext>`（UUID v4 随机、扩展名保真小写），
 * 落盘文件名与 ID 后缀一致；平台差异白名单（P0）：iOS 以相对 ID 替代 Android 时间戳文件名
 * （img_<ts>_<hash>.jpg / image_<ts>.jpg），目录与生命周期语义不变。
 *
 * 清理策略（契约 18，Android 现状即契约）：删除会话/消息/角色不删图片语音文件（孤儿残留现状保留）；
 * 取消录音删除 voice 文件（deleteAudio）；恢复失败清理 restored_media/<backupId> 由 T10 管线负责；
 * 孤儿 GC（引用扫描）默认关闭，随 A5。
 */
class MediaRepository(
    private val store: MediaStore = MediaStoreProvider.require(),
) {

    /**
     * 媒体入库：source 来源 + 原始引用 → 媒体 ID 字符串。副作用：文件落 media 根下对应 category 目录。
     *
     * 返回语义（镜像 ImageUtils.kt:24-32 回退）：失败（源不可读/复制后为空/写盘失败）返回原引用字符串，
     * 不残留空文件；远程引用与内置头像数字串原样返回（不落盘）。
     *
     * @param backupId 仅 BACKUP_IMPORT 使用：restored_media/<backupId>/media/images/ 落点。
     *                 null 或非法（含 `/`、`..`）时按失败语义返回原引用。
     */
    suspend fun ingest(source: MediaSource, ref: String, backupId: String? = null): String {
        if (ref.isBlank()) return ref
        // oracle MainActivity.kt:197-198 / BackupMediaCollector.kt:61 —— 远程引用透传，不落盘、不进 ID 化
        if (isRemoteReference(ref)) return ref
        when (source) {
            // oracle AvatarImage.kt:31-34 —— 数字 1..147 是内置头像，原样保留；非数字按本地图入库
            MediaSource.BUILTIN_AVATAR -> {
                if (isBuiltinAvatar(ref)) return ref
                return ingest(MediaSource.PICKER, ref, backupId)
            }
            // oracle BackupMediaCollector.kt:24-30 —— 备份导入：local-image/<sha24> 直映射 + restored_media 落点
            MediaSource.BACKUP_IMPORT -> return ingestBackupImport(ref, backupId)
            MediaSource.REMOTE -> return ref
            else -> Unit
        }
        val category = categoryDir(source) ?: return ref
        val extension = extensionOf(ref) ?: defaultExtension(source) ?: return ref
        // 契约 12 —— ID 形态 media/<uuid>.<ext>，落盘文件名与 ID 后缀一致
        val id = "media/${randomUuid()}.$extension"
        return copyInto(id, category, ref)
    }

    /**
     * 媒体 ID → 本地文件 URL（file://）。文件缺失/ID 非法 → null（调用方渲染占位，契约 17）。
     *
     * 不处理：
     *   - 内置头像数字串（bundle 资源，渲染层走 AvatarImage.kt:31-34 分支，返回 null 由渲染层分支）
     *   - 远程引用（http/https/data: 不是媒体 ID，MediaId 校验即拒）
     *   - `local-image/<sha24>`（备份格式 ID：恢复管线经 mediaId 直映射重写为落盘 URL，运行时不 resolve）
     * 搜索顺序：音频扩展名优先 voice/，其余 images → chat_images → voice → newspapers
     * （ID 不含 category 前缀，契约 12 的 ID 形态与契约 13 的落点分离所致；UUID 唯一性保证跨目录不撞名）。
     */
    fun resolve(mediaId: String): String? {
        val id = MediaId.parse(mediaId) ?: return null
        if (isBuiltinAvatar(id.value)) return null
        if (id.value.startsWith(LOCAL_IMAGE_PREFIX)) return null
        for (dir in searchDirsFor(id.value)) {
            val relative = "$dir/${id.value}"
            if (store.exists(relative)) return store.fileUrl(relative)
        }
        return null
    }

    /**
     * 删除语音文件（取消录音/未发送语音必须调用，语音文件会快速膨胀——契约 18）。
     * oracle：LocalAudioController.kt:140-142 `File(path.removePrefix("file://")).delete()`。
     * 输入可为 media ID（删除对应 voice/ 下文件）或 file:// 绝对路径（仅限 media 根内）。
     * 目标不存在视作成功（幂等）；内置头像/远程引用/空串返回 false。
     */
    fun deleteAudio(ref: String): Boolean {
        if (ref.isBlank()) return false
        if (isRemoteReference(ref)) return false
        val id = MediaId.parse(ref)
        if (id != null && !isBuiltinAvatar(ref)) {
            if (id.value.startsWith(LOCAL_IMAGE_PREFIX)) return false
            for (dir in searchDirsFor(id.value)) {
                val relative = "$dir/${id.value}"
                if (store.exists(relative)) return store.delete(relative)
            }
            return true // 未找到即已不存在（幂等删除语义）
        }
        return store.deleteAbsolutePath(ref.removePrefix("file://"))
    }

    /**
     * 备份 mediaId 公式复刻（契约 14，lead 裁决：路径派生语义原样保留，不得改为内容派生）。
     * oracle：BackupMediaCollector.kt:26,71-72 —— `sha256(canonicalPath)[:24]`，前缀 `local-image/`。
     */
    fun backupMediaIdFor(canonicalPath: String): String =
        "$LOCAL_IMAGE_PREFIX${stableIdHex(canonicalPath)}"

    /**
     * 备份 archivePath 公式复刻（契约 14）。oracle：BackupMediaCollector.kt:27 ——
     * `media/images/<sha24>.<ext>`，无扩展名时不带点号后缀。
     */
    fun backupArchivePathFor(canonicalPath: String, extension: String): String {
        val ext = extension.trim().removePrefix(".").lowercase().takeIf { it.isNotBlank() }
        return "media/images/${stableIdHex(canonicalPath)}${ext?.let { ".$it" } ?: ""}"
    }

    /** oracle MainActivity.kt:197-198 —— http(s)/data: 原样透传（大小写敏感，与 Android 一致）。 */
    fun isRemoteReference(ref: String): Boolean =
        ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("data:")

    /** oracle AvatarImage.kt:31-34 —— 纯数字 1..147 为内置头像。 */
    fun isBuiltinAvatar(ref: String): Boolean =
        ref.toIntOrNull()?.let { it in 1..147 } == true

    /** 备份收集白名单根（契约 15）：仅 images/chat_images/restored_media 三根。供 A5 导出器/GC 复用。 */
    val ownedRoots: List<String> = listOf("images", "chat_images", "restored_media")

    // --- 内部实现 ---

    private fun ingestBackupImport(ref: String, backupId: String?): String {
        val bid = backupId?.takeIf { it.isNotBlank() && !it.contains("..") && !it.contains("/") } ?: return ref
        val canonical = ref.removePrefix("file://")
        if (!canonical.startsWith("/")) return ref
        val sha24 = stableIdHex(canonical)
        val extension = extensionOf(ref) ?: "bin"
        // T10 mediaId 直映射落点（lead 裁决 2026-11-22）：restored_media/<backupId>/media/images/<sha24>.<ext>
        val relative = "restored_media/$bid/media/images/$sha24.$extension"
        val bytes = store.readSourceBytes(ref) ?: return ref
        if (bytes.isEmpty()) return ref
        if (!store.writeBytes(relative, bytes)) {
            store.delete(relative) // 镜像 ImageUtils.kt:27 —— 绝不残留空/半写文件
            return ref
        }
        return "$LOCAL_IMAGE_PREFIX$sha24"
    }

    /** 通用复制入库：返回 id；失败返回原引用并清残（镜像 ImageUtils.kt:24-32）。 */
    private fun copyInto(id: String, category: String, ref: String): String {
        val relative = "$category/$id"
        val bytes = store.readSourceBytes(ref) ?: return ref
        if (bytes.isEmpty()) return ref
        if (!store.writeBytes(relative, bytes)) {
            store.delete(relative)
            return ref
        }
        return id
    }

    private fun categoryDir(source: MediaSource): String? = when (source) {
        MediaSource.PICKER -> "images"
        MediaSource.CAMERA -> "chat_images"
        MediaSource.VOICE -> "voice"
        else -> null
    }

    private fun defaultExtension(source: MediaSource): String? = when (source) {
        MediaSource.PICKER, MediaSource.CAMERA -> "jpg"
        MediaSource.VOICE -> "pcm"
        else -> null
    }

    /** 扩展名保真小写（契约 12）：取引用路径最后一段的 `.ext`，1..5 位字母数字；无则 null（走默认扩展名）。 */
    private fun extensionOf(ref: String): String? {
        val tail = ref.substringAfterLast('.', "")
        return tail.lowercase().takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
    }

    private fun searchDirsFor(mediaId: String): List<String> {
        val audioExt = extensionOf(mediaId) in AUDIO_EXTENSIONS
        return if (audioExt) listOf("voice", "images", "chat_images", "newspapers")
        else listOf("images", "chat_images", "voice", "newspapers")
    }

    private fun stableIdHex(canonicalPath: String): String =
        BackupCrypto.sha256Hex(canonicalPath.encodeToByteArray()).take(24)

    companion object {
        const val MEDIA_ID_PREFIX = "media/"
        const val LOCAL_IMAGE_PREFIX = "local-image/"
        const val BUILTIN_AVATAR_MAX = 147
        private val AUDIO_EXTENSIONS = setOf("pcm", "wav", "mp3", "m4a", "aac", "ogg")
    }

    private fun randomUuid(): String {
        val bytes = Random.nextBytes(16)
        // RFC 4122 v4：version=4、variant=10xx
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = "0123456789abcdef"
        return buildString(36) {
            bytes.forEachIndexed { i, b ->
                if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
                val v = b.toInt() and 0xFF
                append(hex[v ushr 4])
                append(hex[v and 0x0F])
            }
        }
    }
}
