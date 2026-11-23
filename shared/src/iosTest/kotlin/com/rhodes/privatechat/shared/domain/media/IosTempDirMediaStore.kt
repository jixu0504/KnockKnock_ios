@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.domain.media

import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * iOS 临时目录 MediaStore（:shared:iosSimulatorArm64Test 的目录清理/幂等创建测试用真实文件系统）。
 * 基目录 = NSTemporaryDirectory()/rhodes_media_test_<uuid>/，media 根 = 其下 media/。
 * 字节读写复用 T9 FileIo（已在 ios-data-base 验证）；目录创建复用 MediaDirectory.ensureDirectory。
 */
class IosTempDirMediaStore : TestDirMediaStore {

    private val base = NSTemporaryDirectory() + "rhodes_media_test_" + NSUUID().UUIDString + "/"
    private val root = base + "media/"
    private val fm = NSFileManager.defaultManager

    init {
        MediaDirectory.ensureDirectory(root)
    }

    private fun absolute(relativePath: String): String = root + relativePath

    override fun ensureDirectories(): List<String> {
        val dirs = listOf("images", "chat_images", "voice", "newspapers")
        dirs.forEach { MediaDirectory.ensureDirectory(root + it) }
        return dirs.filter { FileIo.exists(root + it) }
    }

    override fun readSourceBytes(ref: String): ByteArray? {
        if (ref.startsWith("content://")) return null
        return FileIo.readBytes(ref.removePrefix("file://"))
    }

    override fun writeBytes(relativePath: String, bytes: ByteArray): Boolean {
        val abs = absolute(relativePath)
        MediaDirectory.ensureDirectory(abs.substringBeforeLast('/'))
        return FileIo.writeBytes(abs, bytes) && FileIo.exists(abs)
    }

    override fun readBytes(relativePath: String): ByteArray? = FileIo.readBytes(absolute(relativePath))

    override fun exists(relativePath: String): Boolean = FileIo.exists(absolute(relativePath))

    override fun delete(relativePath: String): Boolean = FileIo.delete(absolute(relativePath))

    override fun fileUrl(relativePath: String): String = "file://${absolute(relativePath)}"

    override fun deleteAbsolutePath(path: String): Boolean {
        if (!path.startsWith(root)) return false
        return FileIo.delete(path)
    }

    override fun relativeFiles(): List<String> {
        val out = mutableListOf<String>()
        walk(root, out)
        return out.map { it.removePrefix(root) }
    }

    /** contentsOfDirectoryAtPath 对目录返回列表、对文件返回 null（error）→ 递归目录、记录文件。 */
    private fun walk(dir: String, acc: MutableList<String>) {
        val names = fm.contentsOfDirectoryAtPath(dir, null) ?: run {
            acc += dir
            return
        }
        for (name in names) {
            walk(dir + "/" + name, acc)
        }
    }

    override fun createSourceFile(name: String, bytes: ByteArray): String {
        val path = base + "src_" + name
        MediaDirectory.ensureDirectory(base)
        // 空字节：FileIo.writeBytes 经 ByteArray.toNSData 对空数组会抛 AIOOBE——
        // 用 NSFileManager.createFileAtPath(contents=null) 创建 0 字节文件（空源用例需要真实空文件）。
        if (bytes.isEmpty()) {
            fm.createFileAtPath(path, contents = null, attributes = null)
        } else {
            FileIo.writeBytes(path, bytes)
        }
        return path
    }

    override fun cleanup() {
        FileIo.delete(base.removeSuffix("/"))
    }
}
