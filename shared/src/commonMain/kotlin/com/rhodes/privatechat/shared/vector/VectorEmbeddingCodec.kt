package com.rhodes.privatechat.shared.vector

/**
 * 向量嵌入的 float32 小端编解码（T71）。
 *
 * 存储形态：每个分量 4 字节 IEEE-754 单精度、显式按小端拼装，不依赖 JVM/平台字节序，
 * 因此 iOS / Android 与 SQLite BLOB 之间可互读。
 */
object VectorEmbeddingCodec {
    const val DTYPE_FLOAT32: String = "float32"
    const val BYTES_PER_COMPONENT: Int = 4

    /** 编码为非空的 float32 小端字节数组；[values] 必须非空且全部有限。 */
    fun encode(values: List<Double>): ByteArray {
        require(isUsable(values)) { "向量为空或包含非有限值，无法编码" }
        val bytes = ByteArray(values.size * BYTES_PER_COMPONENT)
        values.forEachIndexed { index, value ->
            val bits = value.toFloat().toRawBits()
            val offset = index * BYTES_PER_COMPONENT
            bytes[offset] = (bits and 0xFF).toByte()
            bytes[offset + 1] = ((bits ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((bits ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
        return bytes
    }

    /** 解码 float32 小端 BLOB；空、非 4 字节对齐或含非有限值时返回 null。 */
    fun decode(blob: ByteArray?): List<Double>? {
        if (blob == null || blob.isEmpty() || blob.size % BYTES_PER_COMPONENT != 0) return null
        val values = ArrayList<Double>(blob.size / BYTES_PER_COMPONENT)
        var offset = 0
        while (offset < blob.size) {
            val bits = (blob[offset].toInt() and 0xFF) or
                ((blob[offset + 1].toInt() and 0xFF) shl 8) or
                ((blob[offset + 2].toInt() and 0xFF) shl 16) or
                ((blob[offset + 3].toInt() and 0xFF) shl 24)
            values += Float.fromBits(bits).toDouble()
            offset += BYTES_PER_COMPONENT
        }
        return values.takeIf { isUsable(it) }
    }

    /** 非空、分量全部有限（无 NaN/Inf）、且维度与 [expectedDimension] 一致（0 表示不校验）。 */
    fun isUsable(values: List<Double>, expectedDimension: Int = 0): Boolean =
        values.isNotEmpty() &&
            (expectedDimension <= 0 || values.size == expectedDimension) &&
            values.all { it.isFinite() }

    /** 编码后字节数；用于诊断/体积对比。 */
    fun encodedSize(dimension: Int): Int = dimension * BYTES_PER_COMPONENT
}