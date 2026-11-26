package com.rhodes.privatechat.shared.backup

/**
 * 纯 Kotlin 最小 ZIP 读取器（Phase 0 零新依赖，lead 批准 2026-11-22）。
 *
 * 语义镜像 Android oracle 使用的 java.util.zip.ZipInputStream：
 *  - 解析 End-Of-Central-Directory + Central Directory + Local Header；
 *  - 支持 method 0（stored）与 method 8（deflate，raw deflate 经 BackupInflate）；
 *  - 逐条目 CRC-32 校验（ZipInputStream 在读完条目时校验 CRC，校验失败抛 IOException，
 *    BackupFileReader.kt:45 会把异常转为校验失败；此处同样抛 ZipFormatException）；
 *  - 不支持加密条目、ZIP64、数据描述符（Android 的 BackupFileWriter 也不会写出这些）。
 */
object BackupZip {

    class ZipFormatException(message: String) : IllegalArgumentException(message)

    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val uncompressedSize: Long,
        val crc32: Long,
        val data: ByteArray,
    )

    /** 按 ZIP 内出现顺序返回全部条目；重复路径由调用方（BackupFileReader）检测并拒绝。 */
    fun read(bytes: ByteArray, maxEntries: Int, maxEntryBytes: Long, maxTotalBytes: Long): List<Entry> {
        val end = findEndOfCentralDirectory(bytes)
            ?: throw ZipFormatException("ZIP 结尾记录缺失")
        val entryCount = end.entryCount
        if (entryCount > maxEntries) throw ZipFormatException("ZIP 条目数量超过限制")
        var cdPos = end.cdOffset
        val result = ArrayList<Entry>(entryCount)
        var total = 0L
        for (i in 0 until entryCount) {
            if (cdPos + 46 > bytes.size) throw ZipFormatException("中央目录损坏")
            if (readLe32(bytes, cdPos) != 0x02014b50L) throw ZipFormatException("中央目录签名无效")
            val method = readLe16(bytes, cdPos + 10)
            val crc = readLe32(bytes, cdPos + 16).toLong() and 0xFFFFFFFFL
            val compSize = readLe32(bytes, cdPos + 20).toLong()
            val uncompSize = readLe32(bytes, cdPos + 24).toLong()
            val nameLen = readLe16(bytes, cdPos + 28)
            val extraLen = readLe16(bytes, cdPos + 30)
            val commentLen = readLe16(bytes, cdPos + 32)
            val localOffset = readLe32(bytes, cdPos + 42)
            val name = bytes.decodeToString(cdPos + 46, cdPos + 46 + nameLen)
            if (uncompSize > maxEntryBytes) throw ZipFormatException("ZIP 单个文件超过限制")
            total += uncompSize
            if (total > maxTotalBytes) throw ZipFormatException("ZIP 解压总大小超过限制")

            val localEnd = localOffset + 30
            if (localOffset + 30 > bytes.size || readLe32(bytes, localOffset.toInt()) != 0x04034b50L) {
                throw ZipFormatException("本地头签名无效: $name")
            }
            val localNameLen = readLe16(bytes, localOffset.toInt() + 26)
            val localExtraLen = readLe16(bytes, localOffset.toInt() + 28)
            val dataStart = (localEnd + localNameLen + localExtraLen).toInt()
            if (dataStart < 0 || dataStart + compSize > bytes.size) throw ZipFormatException("条目数据越界: $name")

            val raw = bytes.copyOfRange(dataStart, (dataStart + compSize).toInt())
            val data = when (method) {
                0 -> raw
                8 -> BackupInflate.inflate(raw, uncompSize)
                else -> throw ZipFormatException("不支持的压缩方式: $name")
            }
            if (data.size.toLong() != uncompSize) throw ZipFormatException("条目长度不一致: $name")
            val actualCrc = BackupCrypto.crc32(data)
            if (actualCrc != crc) throw ZipFormatException("CRC 校验失败: $name")
            result.add(Entry(name, name.endsWith("/"), uncompSize, crc, data))
            cdPos += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    private class EndRecord(val entryCount: Int, val cdOffset: Int)

    private fun findEndOfCentralDirectory(bytes: ByteArray): EndRecord? {
        val minPos = if (bytes.size - 22 - 65535 > 0) bytes.size - 22 - 65535 else 0
        var pos = bytes.size - 22
        while (pos >= minPos) {
            if (readLe32(bytes, pos) == 0x06054b50L) {
                val entryCount = readLe16(bytes, pos + 10)
                val cdSize = readLe32(bytes, pos + 12)
                val cdOffset = readLe32(bytes, pos + 16)
                val commentLen = readLe16(bytes, pos + 20)
                if (pos + 22 + commentLen == bytes.size && cdOffset + cdSize <= bytes.size.toLong()) {
                    return EndRecord(entryCount, cdOffset.toInt())
                }
            }
            pos--
        }
        return null
    }

    private fun readLe16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun ByteArray.decodeToString(start: Int, end: Int): String {
        val sb = StringBuilder(end - start)
        for (i in start until end) sb.append((this[i].toInt() and 0xFF).toChar())
        return sb.toString()
    }
}
