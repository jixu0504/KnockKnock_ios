package com.rhodes.privatechat.shared.domain.media

/**
 * 内存媒体 sink（金样路径转换矩阵用）：引用别名表（ref → 字节）+ media 根内相对路径文件表。
 * 平台无关，commonTest 直接实例化，两条测试路径跑同一份断言。
 */
class InMemoryMediaStore : MediaStore {

    private val aliases = linkedMapOf<String, ByteArray>()
    private val files = linkedMapOf<String, ByteArray>()

    /** 注册源引用 → 字节（模拟 content:// 等平台源的可读性）。 */
    fun registerSource(ref: String, bytes: ByteArray) {
        aliases[ref] = bytes
    }

    fun fileCount(): Int = files.size

    fun fileBytes(relativePath: String): ByteArray? = files[relativePath]

    override fun ensureDirectories(): List<String> {
        val dirs = listOf("images", "chat_images", "voice", "newspapers")
        return dirs // 内存实现目录始终「存在」
    }

    override fun readSourceBytes(ref: String): ByteArray? = aliases[ref]

    override fun writeBytes(relativePath: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        files[relativePath] = bytes
        return true
    }

    override fun readBytes(relativePath: String): ByteArray? = files[relativePath]

    override fun exists(relativePath: String): Boolean = files.containsKey(relativePath)

    override fun delete(relativePath: String): Boolean {
        files.remove(relativePath)
        return true
    }

    override fun fileUrl(relativePath: String): String = "mem://$relativePath"

    override fun deleteAbsolutePath(path: String): Boolean {
        val relative = path.removePrefix("mem://")
        files.remove(relative)
        return true
    }
}
