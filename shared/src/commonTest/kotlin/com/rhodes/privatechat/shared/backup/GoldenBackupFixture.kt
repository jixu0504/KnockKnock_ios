package com.rhodes.privatechat.shared.backup

/**
 * 金样备份包字节（ios-port/golden/backup/minimal-1.14.27.zip，base64 内嵌）。
 * 与磁盘金样的绑定：ZIP_SHA256 必须等于金样 md 记录值 799b2ba0…（测试内断言）。
 */
object GoldenBackupFixture {
    const val ZIP_SHA256 = "799b2ba06b4167d8245ba888c3db586bde8e24707f2a5fb7dd218c66ef5b5209"
    const val MEDIA_SHA256 = "c414cd0e204de974f73753c7e28d7638e7b3691bb8b1a2bab6b25bb7fed7ce77"
    val zipBytes: ByteArray by lazy { decodeBase64() }
    private val lines = listOf(
"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",
"dC5qc29uUEsFBgAAAAAFAAUAcAEAAPEMAAAAAA=="
    )
    private fun decodeBase64(): ByteArray {
        val s = lines.joinToString("")
        val out = ByteArray(s.length * 3 / 4)
        var outLen = 0
        var acc = 0
        var bits = 0
        for (c in s) {
            if (c == '=') break
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> continue
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outLen++] = ((acc shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(outLen)
    }
}
