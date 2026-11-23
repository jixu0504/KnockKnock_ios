package com.rhodes.privatechat.shared.domain.media

import java.io.File

/**
 * JVM 临时目录 MediaStore（:shared:testDebugUnitTest 的目录清理/幂等创建测试用真实文件系统）。
 * 基目录 = java.io.tmpdir/rhodes_media_test_<nano>/，media 根 = 其下 media/。
 */
class JvmTempDirMediaStore : TestDirMediaStore {

    private val base = File(System.getProperty("java.io.tmpdir"), "rhodes_media_test_${System.nanoTime()}")
    private val root = File(base, "media")

    private fun file(relativePath: String): File = File(root, relativePath)

    override fun ensureDirectories(): List<String> {
        val dirs = listOf("images", "chat_images", "voice", "newspapers")
        dirs.forEach { File(root, it).mkdirs() }
        return dirs.filter { File(root, it).isDirectory }
    }

    override fun readSourceBytes(ref: String): ByteArray? {
        if (ref.startsWith("content://")) return null
        val f = File(ref.removePrefix("file://"))
        return if (f.isFile) f.readBytes() else null
    }

    override fun writeBytes(relativePath: String, bytes: ByteArray): Boolean {
        val f = file(relativePath)
        f.parentFile?.mkdirs()
        return runCatching {
            f.writeBytes(bytes)
            f.isFile && f.length() > 0L
        }.getOrDefault(false)
    }

    override fun readBytes(relativePath: String): ByteArray? =
        file(relativePath).takeIf { it.isFile }?.readBytes()

    override fun exists(relativePath: String): Boolean = file(relativePath).exists()

    override fun delete(relativePath: String): Boolean {
        val f = file(relativePath)
        return if (!f.exists()) true else f.deleteRecursively()
    }

    override fun fileUrl(relativePath: String): String = "file://${file(relativePath).absolutePath}"

    override fun deleteAbsolutePath(path: String): Boolean {
        val f = File(path)
        if (!f.absolutePath.startsWith(root.absolutePath + File.separator)) return false
        return if (!f.exists()) true else f.delete()
    }

    override fun relativeFiles(): List<String> =
        if (!root.isDirectory) emptyList() else root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .toList()

    override fun createSourceFile(name: String, bytes: ByteArray): String {
        val f = File(base, "src_$name")
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f.absolutePath
    }

    override fun cleanup() {
        base.deleteRecursively()
    }
}
