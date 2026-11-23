package com.rhodes.privatechat.shared.backup

/**
 * 纯 Kotlin raw DEFLATE（RFC 1951）解码器。
 * 支撑 BackupZipReader 读取 ZIP method=8 条目；ZIP 使用 raw deflate（无 zlib 头/Adler32）。
 * 覆盖 stored / fixed Huffman / dynamic Huffman 三种块类型；输出长度按 ZIP 头校验。
 */
object BackupInflate {

    class InflateException(message: String) : IllegalArgumentException(message)

    fun inflate(input: ByteArray, expectedSize: Long, maxOutput: Long = 16L * 1024 * 1024 + 4096): ByteArray {
        val reader = BitReader(input)
        val out = GrowableByteArray()
        try {
            var final = false
            while (!final) {
                final = reader.readBits(1) == 1
                when (val btype = reader.readBits(2)) {
                    0 -> inflateStored(reader, out)
                    1 -> inflateHuffman(reader, out, FIXED_LITLEN, FIXED_DIST, fixedDist = true)
                    2 -> inflateDynamic(reader, out)
                    else -> throw InflateException("无效块类型 3")
                }
                if (out.size > maxOutput) throw InflateException("解压输出超过上限")
            }
        } catch (e: InflateException) {
            throw e
        } catch (e: Exception) {
            throw InflateException("deflate 数据损坏: ${e.message}")
        }
        if (expectedSize >= 0 && out.size.toLong() != expectedSize) {
            throw InflateException("解压长度不一致: 期望 $expectedSize 实际 ${out.size}")
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- stored block
    private fun inflateStored(reader: BitReader, out: GrowableByteArray) {
        reader.alignToByte()
        val len = reader.readBits(16)
        val nlen = reader.readBits(16)
        if (len != nlen.inv() and 0xFFFF) throw InflateException("stored 块 LEN/NLEN 校验失败")
        for (i in 0 until len) out.append(reader.readByte().toInt() and 0xFF)
    }

    // ---------------------------------------------------------------- Huffman
    private fun inflateHuffman(reader: BitReader, out: GrowableByteArray, litlen: HuffmanTable, dist: HuffmanTable, fixedDist: Boolean) {
        while (true) {
            val symbol = litlen.decode(reader)
            when {
                symbol < 256 -> out.append(symbol)
                symbol == 256 -> return
                symbol in 257..285 -> {
                    val length = lengthFor(symbol, reader)
                    val distSymbol = dist.decode(reader)
                    if (distSymbol > 29) throw InflateException("距离码越界: $distSymbol")
                    val distance = distanceFor(distSymbol, reader)
                    out.repeatLast(distance, length)
                }
                else -> throw InflateException("无效字面量码 $symbol")
            }
        }
    }

    private fun inflateDynamic(reader: BitReader, out: GrowableByteArray) {
        val hlit = reader.readBits(5) + 257
        val hdist = reader.readBits(5) + 1
        val hclen = reader.readBits(4) + 4
        val codeLengths = IntArray(19)
        for (i in 0 until hclen) codeLengths[CODE_LENGTH_ORDER[i]] = reader.readBits(3)
        val clTable = buildTable(codeLengths)
        if (clTable == null) throw InflateException("码长表无效")

        val lengths = IntArray(hlit + hdist)
        var i = 0
        while (i < lengths.size) {
            val sym = clTable.decode(reader)
            when {
                sym < 16 -> { lengths[i] = sym; i++ }
                sym == 16 -> {
                    if (i == 0) throw InflateException("重复码无前值")
                    val repeat = 3 + reader.readBits(2)
                    repeat(repeat) { lengths[i] = lengths[i - 1]; i++ }
                }
                sym == 17 -> { val repeat = 3 + reader.readBits(3); repeat(repeat) { lengths[i] = 0; i++ } }
                sym == 18 -> { val repeat = 11 + reader.readBits(7); repeat(repeat) { lengths[i] = 0; i++ } }
                else -> throw InflateException("无效码长符号 $sym")
            }
        }
        if (i != lengths.size) throw InflateException("码长表长度不一致")
        val litlen = buildTable(lengths.copyOfRange(0, hlit)) ?: throw InflateException("字面量表无效")
        val dist = buildTable(lengths.copyOfRange(hlit, lengths.size)) ?: throw InflateException("距离表无效")
        inflateHuffman(reader, out, litlen, dist, fixedDist = false)
    }

    private fun lengthFor(symbol: Int, reader: BitReader): Int {
        val base: Int
        val extra: Int
        when {
            symbol in 257..264 -> { base = 3 + (symbol - 257); extra = 0 }
            symbol in 265..268 -> { base = 11 + 2 * (symbol - 265); extra = 1 }
            symbol in 269..272 -> { base = 19 + 4 * (symbol - 269); extra = 2 }
            symbol in 273..276 -> { base = 35 + 8 * (symbol - 273); extra = 3 }
            symbol in 277..280 -> { base = 67 + 16 * (symbol - 277); extra = 4 }
            symbol in 281..284 -> { base = 131 + 32 * (symbol - 281); extra = 5 }
            symbol == 285 -> return 258
            else -> throw InflateException("无效长度码 $symbol")
        }
        return base + reader.readBits(extra)
    }

    private fun distanceFor(symbol: Int, reader: BitReader): Int {
        val base: Int
        val extra: Int
        when {
            symbol in 0..3 -> { base = 1 + symbol; extra = 0 }
            symbol in 4..5 -> { base = 5 + 2 * (symbol - 4); extra = 1 }
            symbol in 6..7 -> { base = 9 + 4 * (symbol - 6); extra = 2 }
            symbol in 8..9 -> { base = 17 + 8 * (symbol - 8); extra = 3 }
            symbol in 10..11 -> { base = 33 + 16 * (symbol - 10); extra = 4 }
            symbol in 12..13 -> { base = 65 + 32 * (symbol - 12); extra = 5 }
            symbol in 14..15 -> { base = 129 + 64 * (symbol - 14); extra = 6 }
            symbol in 16..17 -> { base = 257 + 128 * (symbol - 16); extra = 7 }
            symbol in 18..19 -> { base = 513 + 256 * (symbol - 18); extra = 8 }
            symbol in 20..21 -> { base = 1025 + 512 * (symbol - 20); extra = 9 }
            symbol in 22..23 -> { base = 2049 + 1024 * (symbol - 22); extra = 10 }
            symbol in 24..25 -> { base = 4097 + 2048 * (symbol - 24); extra = 11 }
            symbol in 26..27 -> { base = 8193 + 4096 * (symbol - 26); extra = 12 }
            symbol in 28..29 -> { base = 16385 + 8192 * (symbol - 28); extra = 13 }
            else -> throw InflateException("无效距离码 $symbol")
        }
        return base + reader.readBits(extra)
    }

    // ---------------------------------------------------------------- canonical Huffman
    private class HuffmanTable(val counts: IntArray, val symbols: IntArray) {
        fun decode(reader: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1 until counts.size) {
                code = code or reader.readBit()
                val count = counts[len]
                if (code - first < count) return symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw InflateException("Huffman 码溢出")
        }
    }

    private fun buildTable(lengths: IntArray): HuffmanTable? {
        if (lengths.all { it == 0 }) return null
        val maxLen = lengths.maxOrNull() ?: return null
        if (maxLen > 15) return null
        val counts = IntArray(maxLen + 1)
        for (l in lengths) if (l != 0) counts[l]++
        // 过订阅校验
        var left = 1
        for (len in 1..maxLen) {
            left = (left shl 1) - counts[len]
            if (left < 0) return null
        }
        val offsets = IntArray(maxLen + 1)
        for (len in 1 until maxLen) offsets[len + 1] = offsets[len] + counts[len]
        val symbols = IntArray(lengths.size) { -1 }
        for (sym in lengths.indices) {
            val len = lengths[sym]
            if (len != 0) {
                symbols[offsets[len]] = sym
                offsets[len]++
            }
        }
        return HuffmanTable(counts, symbols)
    }

    private val FIXED_LITLEN: HuffmanTable by lazy {
        val lengths = IntArray(288)
        for (i in 0 until 144) lengths[i] = 8
        for (i in 144 until 256) lengths[i] = 9
        for (i in 256 until 280) lengths[i] = 7
        for (i in 280 until 288) lengths[i] = 8
        buildTable(lengths)!!
    }

    private val FIXED_DIST: HuffmanTable by lazy {
        val lengths = IntArray(30) { 5 }
        buildTable(lengths)!!
    }

    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    // ---------------------------------------------------------------- bit reader（LSB 优先）
    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0
        private var bitBuf = 0
        private var bitCount = 0

        fun readBit(): Int {
            if (bitCount == 0) {
                if (bytePos >= data.size) throw InflateException("数据意外结束")
                bitBuf = data[bytePos++].toInt() and 0xFF
                bitCount = 8
            }
            val bit = bitBuf and 1
            bitBuf = bitBuf ushr 1
            bitCount--
            return bit
        }

        fun readBits(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = v or (readBit() shl i)
            return v
        }

        fun readByte(): Byte {
            if (bitCount != 0) throw InflateException("字节边界错位")
            if (bytePos >= data.size) throw InflateException("数据意外结束")
            return data[bytePos++]
        }

        fun alignToByte() {
            bitBuf = 0
            bitCount = 0
        }
    }

    private class GrowableByteArray {
        private var buf = ByteArray(1024)
        var size = 0
            private set

        fun append(b: Int) {
            ensure(1)
            buf[size++] = b.toByte()
        }

        fun repeatLast(distance: Int, length: Int) {
            if (distance <= 0 || distance > size) throw InflateException("距离越界: $distance")
            ensure(length)
            for (i in 0 until length) {
                buf[size] = buf[size - distance]
                size++
            }
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun ensure(n: Int) {
            if (size + n > buf.size) {
                var cap = buf.size
                while (size + n > cap) cap *= 2
                buf = buf.copyOf(cap)
            }
        }
    }
}
