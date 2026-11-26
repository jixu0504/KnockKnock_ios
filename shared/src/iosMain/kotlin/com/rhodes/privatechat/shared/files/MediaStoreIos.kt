package com.rhodes.privatechat.shared.files

import com.rhodes.privatechat.shared.domain.media.MediaStore

/**
 * 生产 MediaStore：Application Support/media 落点（T9 契约 11，lead 裁决 2026-11-23）。
 * 复用 T9 `MediaDirectory` 的路径与幂等创建 + `FileIo` 的字节读写，不改动其公开行为。
 *
 * 相对路径均以 `Application Support/media/` 为根；`readSourceBytes` 只处理本地路径
 * （`file://` / 裸绝对路径），`content://` 在 iOS 无 ContentResolver，返回 null → ingest 回退原引用。
 *
 * 平台入口（T1/T15 阶段）接线：`MediaStoreProvider.register(MediaStoreIos())`。
 */
class MediaStoreIos : MediaStore {

    override fun ensureDirectories(): List<String> {
        MediaDirectory.ensureMediaStructure()
        return MEDIA_SUBDIRS.filter { FileIo.exists("${MediaDirectory.mediaRootPath()}/$it") }
    }

    override fun readSourceBytes(ref: String): ByteArray? {
        if (ref.startsWith("content://")) return null
        return FileIo.readBytes(ref.removePrefix("file://"))
    }

    override fun writeBytes(relativePath: String, bytes: ByteArray): Boolean {
        // 空字节拒绝：FileIo 底层 ByteArray.toNSData 对空数组会抛 AIOOBE（addressOf(0)）；
        // ingest 契约「复制后为空 → 回退原引用」，此处统一返回 false 不崩溃。
        if (bytes.isEmpty()) return false
        val absolute = "${MediaDirectory.mediaRootPath()}/$relativePath"
        MediaDirectory.ensureDirectory(absolute.substringBeforeLast('/'))
        return FileIo.writeBytes(absolute, bytes)
    }

    override fun readBytes(relativePath: String): ByteArray? =
        FileIo.readBytes("${MediaDirectory.mediaRootPath()}/$relativePath")

    override fun exists(relativePath: String): Boolean =
        FileIo.exists("${MediaDirectory.mediaRootPath()}/$relativePath")

    override fun delete(relativePath: String): Boolean =
        FileIo.delete("${MediaDirectory.mediaRootPath()}/$relativePath")

    override fun fileUrl(relativePath: String): String =
        "file://${MediaDirectory.mediaRootPath()}/$relativePath"

    override fun deleteAbsolutePath(path: String): Boolean {
        val root = MediaDirectory.mediaRootPath()
        if (!path.startsWith("$root/")) return false
        return FileIo.delete(path)
    }

    companion object {
        private val MEDIA_SUBDIRS = listOf("images", "chat_images", "voice", "newspapers")
    }
}
