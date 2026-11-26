package com.rhodes.privatechat.shared.backup

/**
 * 纯 Kotlin 最小 ZIP 写入器（commonMain；与 [BackupZip] 读取器对称，零新依赖）。
 *
 * 只写 STORED（method 0，不压缩）条目 —— 安全、确定性、跨平台一致；读取侧 [BackupZip] 同时支持
 * stored 与 deflate（Android 写出的包可正常读取）。用于：
 *  - rhodes-operator v1 角色卡写出（iOS 侧，规格卡契约 C23）；
 *  - 测试构造 v1 单 payload 备份包（验收 #14）。
 *
 * 产物形态与 `java.util.zip.ZipOutputStream` 兼容：Local Header + Data + Central Directory + EOCD；
 * 逐条目 CRC-32（[BackupCrypto.crc32]），无 data descriptor、无 ZIP64、无加密。
 */
object BackupZipWriter {

    data class Entry(
        val name: String,
        val data: ByteArray,
        /** DOS time/date（默认固定 2026-01-01 00:00，保证确定性）。 */
        val dosTime: Int = 0,
        val dosDate: Int = (2026 - 1980) shl 9 or (1 shl 5) or 1,
    )

    /** @param allowUnsafePaths 仅供测试构造「坏包」变体（生产调用一律 false，路径安全由读取器拒绝）。 */
    fun write(entries: List<Entry>, allowUnsafePaths: Boolean = false): ByteArray {
        val output = ByteAccumulator()
        val centralDirectory = ByteAccumulator()
        entries.forEach { entry ->
            if (!allowUnsafePaths) {
                require(!entry.name.startsWith("/") && !entry.name.contains("..")) { "ZIP 路径不安全：${entry.name}" }
            }
            val nameBytes = entry.name.encodeToByteArray()
            val crc = BackupCrypto.crc32(entry.data)
            val localOffset = output.size

            // Local file header
            output.writeInt(0x04034b50)
            output.writeShort(20)
            output.writeShort(0)
            output.writeShort(0) // method: stored
            output.writeShort(entry.dosTime)
            output.writeShort(entry.dosDate)
            output.writeInt(crc.toInt())
            output.writeInt(entry.data.size)
            output.writeInt(entry.data.size)
            output.writeShort(nameBytes.size)
            output.writeShort(0)
            output.writeBytes(nameBytes)
            output.writeBytes(entry.data)

            // Central directory entry
            centralDirectory.writeInt(0x02014b50)
            centralDirectory.writeShort(20)
            centralDirectory.writeShort(20)
            centralDirectory.writeShort(0)
            centralDirectory.writeShort(0)
            centralDirectory.writeShort(entry.dosTime)
            centralDirectory.writeShort(entry.dosDate)
            centralDirectory.writeInt(crc.toInt())
            centralDirectory.writeInt(entry.data.size)
            centralDirectory.writeInt(entry.data.size)
            centralDirectory.writeShort(nameBytes.size)
            centralDirectory.writeShort(0)
            centralDirectory.writeShort(0)
            centralDirectory.writeShort(0)
            centralDirectory.writeShort(0)
            centralDirectory.writeInt(0)
            centralDirectory.writeInt(localOffset)
            centralDirectory.writeBytes(nameBytes)
        }

        val cdOffset = output.size
        output.writeBytes(centralDirectory.toByteArray())
        val cdSize = output.size - cdOffset

        // End of central directory
        output.writeInt(0x06054b50)
        output.writeShort(0)
        output.writeShort(0)
        output.writeShort(entries.size)
        output.writeShort(entries.size)
        output.writeInt(cdSize)
        output.writeInt(cdOffset)
        output.writeShort(0)
        return output.toByteArray()
    }

    private class ByteAccumulator {
        private var buffer = ByteArray(1024)
        var size: Int = 0
            private set

        private fun ensure(additional: Int) {
            if (size + additional <= buffer.size) return
            var newSize = buffer.size
            while (newSize < size + additional) newSize *= 2
            buffer = buffer.copyOf(newSize)
        }

        fun writeByte(value: Int) {
            ensure(1)
            buffer[size++] = value.toByte()
        }

        fun writeShort(value: Int) {
            writeByte(value and 0xFF)
            writeByte((value ushr 8) and 0xFF)
        }

        fun writeInt(value: Int) {
            writeByte(value and 0xFF)
            writeByte((value ushr 8) and 0xFF)
            writeByte((value ushr 16) and 0xFF)
            writeByte((value ushr 24) and 0xFF)
        }

        fun writeBytes(bytes: ByteArray) {
            ensure(bytes.size)
            bytes.copyInto(buffer, size)
            size += bytes.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}
