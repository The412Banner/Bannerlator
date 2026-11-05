package com.winlator.star.core.unpack

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Decoder for AZO, ESTsoft's own compression method inside ALZip `.egg` archives (EGG method 3).
 *
 * A Kotlin port of Bandisoft's xunazo (https://github.com/kippler/xunazo, © 2018 Bandisoft /
 * kippler@gmail.com, zlib licence — see licenses/NOTICE_EGG.txt). Altered from the original:
 * translated from C++ to Kotlin, probability tables flattened into IntArrays, errors thrown as
 * IOException instead of returned as false. xunazo's arithmetic decoder is itself adapted from
 * Project Nayuki's Reference-arithmetic-coding (MIT).
 *
 * Stream layout: a 2-byte main header (version '1', x86-filter flag 0/1), then blocks of
 * `uncompressed size | compressed size | check` (big-endian u32 each) followed by the block data;
 * an all-zero block header ends the stream. A block whose two sizes match is stored. Every
 * compressed block is decoded with fresh model state into its own buffer (AZO never references
 * an earlier block), so memory use is bounded by the largest block.
 */
class AzoDecoder {

    /** Decodes a whole AZO stream from [input], handing each decoded block to [sink] in order. */
    fun decode(input: InputStream, sink: (ByteArray, Int) -> Unit) {
        val din = DataInputStream(input)
        val x86 = readMainHeader(din)
        while (true) {
            val block = nextBlock(din, x86) ?: return
            sink(block, block.size)
        }
    }

    /** The decoded AZO stream behind [input] as a plain InputStream, decoding one block at a time. */
    fun stream(input: InputStream): InputStream = object : InputStream() {
        private val din = DataInputStream(input)
        private var x86: Boolean? = null
        private var block = ByteArray(0)
        private var pos = 0
        private var done = false

        private fun fill(): Boolean {
            while (pos >= block.size) {
                if (done) return false
                val filter = x86 ?: readMainHeader(din).also { x86 = it }
                val next = nextBlock(din, filter)
                if (next == null) { done = true; return false }
                block = next; pos = 0
            }
            return true
        }

        override fun read(): Int = if (fill()) block[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, block.size - pos)
            System.arraycopy(block, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = input.close()
    }

    /** Reads the 2-byte main header; returns whether the x86 filter is on. */
    private fun readMainHeader(din: DataInputStream): Boolean {
        val version = din.readUnsignedByte()
        val filter = din.readUnsignedByte()
        if (version != '1'.code || filter > 1) throw IOException("Not an AZO stream (version $version, filter $filter)")
        return filter == 1
    }

    /** Decodes the next block, or returns null at the all-zero end-of-stream block header. */
    private fun nextBlock(din: DataInputStream, x86: Boolean): ByteArray? {
        val uncomp = din.readInt().toLong() and 0xFFFFFFFFL
        val comp = din.readInt().toLong() and 0xFFFFFFFFL
        val check = din.readInt()
        if (uncomp == 0L && comp == 0L && check == 0) return null
        if (uncomp > Int.MAX_VALUE - 16 || comp > Int.MAX_VALUE - 16) throw IOException("AZO block too large")
        val out: ByteArray
        if (comp == uncomp) {
            out = ByteArray(comp.toInt())
            din.readFully(out)
        } else {
            val inBuf = ByteArray(comp.toInt())
            din.readFully(inBuf)
            out = ByteArray(uncomp.toInt())
            decodeBlock(inBuf, out)
        }
        if (x86) x86FilterDecode(out, out.size)
        return out
    }

    // ── Block decoder (xunazo XAzoDec) ──

    private val arith = Arith()

    // Literal model: 256 previous-byte contexts + 8 coarse ones (prevByte >> 5), 8-bit symbols.
    private val litProb1 = Array(256) { IntArray(256) }
    private val litProb2 = Array(256 shr LIT_SHIFT) { IntArray(256) }
    private val litState = IntArray(256)

    // Length-index model: 128 distance-index contexts + 8 coarse ones (distIndex >> 4), 7-bit symbols.
    private val lenProb1 = Array(128) { IntArray(128) }
    private val lenProb2 = Array(128 shr LEN_SHIFT) { IntArray(128) }
    private val lenState = IntArray(128)

    private val probMatch = BoolProb()
    private val probHistory = BoolProb()
    private val probDistShortHistoryBit = BoolProb()
    private val probLenPosShortHistoryBit = BoolProb()

    private val probDistShortHistoryIndex = IntArray(2)
    private val probLenPosShortHistoryIndex = IntArray(2)
    private val probLenPosLongHistoryIndex = IntArray(128)
    private val probDistIndex = IntArray(128)

    private val historyShortDist = History(2)
    private val historyShortLen = History(2)
    private val historyLen = History(128)
    private val historyPos = History(128)

    private lateinit var out: ByteArray
    private var writePos = 0

    private fun decodeBlock(inBuf: ByteArray, outBuf: ByteArray) {
        out = outBuf
        writePos = 0
        arith.init(inBuf)

        val half10 = 1 shl (PROB_BITS - 1)
        for (p in litProb1) p.fill(half10)
        for (p in litProb2) p.fill(half10)
        litState.fill(0)
        for (p in lenProb1) p.fill(half10)
        for (p in lenProb2) p.fill(half10)
        lenState.fill(0)
        probMatch.init(); probHistory.init(); probDistShortHistoryBit.init(); probLenPosShortHistoryBit.init()
        probDistShortHistoryIndex.fill(half10)
        probLenPosShortHistoryIndex.fill(half10)
        probLenPosLongHistoryIndex.fill(half10)
        probDistIndex.fill(half10)
        for (i in 0 until 2) {
            historyShortDist.data[i] = i + MIN_DIST
            historyShortLen.data[i] = i
        }
        for (i in 0 until 128) {
            historyLen.data[i] = i + MIN_LEN
            historyPos.data[i] = 0
        }

        // The first symbol is always a literal.
        decodeLiteral()
        while (writePos < out.size) {
            if (arith.decodeBoolState(probMatch) == 0) {
                decodeLiteral()
            } else if (arith.decodeBoolState(probHistory) == 1) {
                decodeLenPosHistory()
            } else {
                decodeMatch()
            }
        }
    }

    private fun peek(distance: Long): Int {
        if (writePos > distance) return out[(writePos - distance - 1).toInt()].toInt() and 0xFF
        if (writePos == 0 && distance == 0L) return 0
        throw IOException("Corrupt AZO data (match before start of block)")
    }

    private fun write(c: Int) {
        if (writePos >= out.size) throw IOException("Corrupt AZO data (block overrun)")
        out[writePos++] = c.toByte()
    }

    private fun decodeLiteral() {
        val prevByte = peek(0)
        val prob1 = litProb1[prevByte]
        val prob2 = litProb2[prevByte shr LIT_SHIFT]
        val symbol: Int
        if (litState[prevByte] >= 0) {
            symbol = arith.decodeTree(prob1, 8, PROB_BITS, PROB_MOVE_BITS)
            arith.updateProb(prob2, symbol, 8, PROB_BITS, PROB_MOVE_BITS)
        } else {
            symbol = arith.decodeTree(prob2, 8, PROB_BITS, PROB_MOVE_BITS)
            arith.updateProb(prob1, symbol, 8, PROB_BITS, PROB_MOVE_BITS)
        }
        val cmp = arith.compareProbSum(prob1, prob2, symbol, 8, PROB_BITS)
        if (cmp > 0) litState[prevByte]++ else if (cmp < 0) litState[prevByte]--
        write(symbol)
    }

    private fun decodeMatch() {
        val dist = decodeDist()
        val len = decodeLen(dist)
        historyLen.addRecent(len.toInt())
        historyPos.addRecent(writePos)
        // AZO distances are 1-based where LZMA's are 0-based, hence dist - 1.
        for (i in 0 until len) write(peek(dist - 1))
    }

    private fun decodeDist(): Long {
        return if (arith.decodeBoolState(probDistShortHistoryBit) == 1) {
            val idx = arith.decodeTree(probDistShortHistoryIndex, 1, PROB_BITS, PROB_MOVE_BITS)
            historyShortDist.getAndUpdate(idx).toLong() and 0xFFFFFFFFL
        } else {
            val distIndex = arith.decodeTree(probDistIndex, 7, PROB_BITS, PROB_MOVE_BITS)
            var dist = DIST_TABLE[distIndex].toLong()
            val extra = DIST_EXTRA[distIndex]
            if (extra > 0) dist += arith.decodeDirect(extra)
            historyShortDist.addRecent(dist.toInt())
            dist
        }
    }

    private fun decodeLen(dist: Long): Long {
        val distIndex = distIndexOf(dist)
        val lenIndex = decodeLenIndex(distIndex)
        var len = LEN_TABLE[lenIndex].toLong()
        val extra = LEN_EXTRA[lenIndex]
        if (extra > 0) len += arith.decodeDirect(extra)
        return len
    }

    private fun decodeLenIndex(distIndex: Int): Int {
        val prob1 = lenProb1[distIndex]
        val prob2 = lenProb2[distIndex shr LEN_SHIFT]
        val lenIndex: Int
        if (lenState[distIndex] >= 0) {
            lenIndex = arith.decodeTree(prob1, 7, PROB_BITS, PROB_MOVE_BITS)
            arith.updateProb(prob2, lenIndex, 7, PROB_BITS, PROB_MOVE_BITS)
        } else {
            lenIndex = arith.decodeTree(prob2, 7, PROB_BITS, PROB_MOVE_BITS)
            arith.updateProb(prob1, lenIndex, 7, PROB_BITS, PROB_MOVE_BITS)
        }
        val cmp = arith.compareProbSum(prob1, prob2, lenIndex, 7, PROB_BITS)
        if (cmp > 0) lenState[distIndex]++ else if (cmp < 0) lenState[distIndex]--
        return lenIndex
    }

    private fun decodeLenPosHistory() {
        val historyIndex = if (arith.decodeBoolState(probLenPosShortHistoryBit) == 1) {
            val short = arith.decodeTree(probLenPosShortHistoryIndex, 1, PROB_BITS, PROB_MOVE_BITS)
            historyShortLen.getAndUpdate(short)
        } else {
            val long = arith.decodeTree(probLenPosLongHistoryIndex, 7, PROB_BITS, PROB_MOVE_BITS)
            historyShortLen.addRecent(long)
            long
        }
        if (historyIndex !in 0 until 128) throw IOException("Corrupt AZO data (history index)")
        val len = historyLen.getAndUpdate(historyIndex).toLong() and 0xFFFFFFFFL
        val pos = historyPos.getAndUpdate(historyIndex).toLong() and 0xFFFFFFFFL
        val dist = writePos - pos
        for (i in 0 until len) write(peek(dist - 1))
    }

    /** Maps a match distance back to its 0..127 distance index (xunazo XAzoTable::GetDistIndex). */
    private fun distIndexOf(dist: Long): Int {
        val d1 = dist - MIN_DIST
        if (d1 <= 20) return d1.toInt()
        var shift = 0
        var temp = ((dist - 1 - 16) / 4 + 1) ushr 1
        while (temp != 0L) { temp = temp ushr 1; shift++ }
        val index = 16 + shift * 4 + ((dist - 1 - 16) - ((1L shl shift) - 1) * 4) / (1L shl shift)
        if (index !in 0..127) throw IOException("Corrupt AZO data (distance)")
        return index.toInt()
    }

    /** One adaptive bit model with its own 8-bit history state (xunazo SBoolProb). */
    private class BoolProb {
        val prob = IntArray(256)
        var state = 0
        fun init() { prob.fill(1 shl (BOOL_PROB_BITS - 1)); state = 0 }
    }

    /** Move-to-front list of recent values (xunazo XAzoHistory). */
    private class History(size: Int) {
        val data = IntArray(size)
        fun getAndUpdate(index: Int): Int {
            if (index !in data.indices) throw IOException("Corrupt AZO data (history)")
            val v = data[index]
            for (i in index downTo 1) data[i] = data[i - 1]
            data[0] = v
            return v
        }
        fun addRecent(v: Int) {
            for (i in data.size - 1 downTo 1) data[i] = data[i - 1]
            data[0] = v
        }
    }

    /**
     * xunazo's 32-bit arithmetic decoder (XAzoArith). Its interface mirrors an LZMA range decoder
     * (bit trees, direct bits), but the coding itself is a classic low/high arithmetic coder with
     * underflow handling. All state fits in 33 bits, so Long arithmetic reproduces the uint64 code.
     */
    private class Arith {
        private var code = 0L
        private var low = 0L
        private var high = MASK
        private var buf = ByteArray(0)
        private var pos = -1
        private var remain = 0
        private var bitsLeft = 0
        private var readError = false

        fun init(input: ByteArray) {
            buf = input; pos = -1; remain = input.size; bitsLeft = 0; readError = false
            low = 0; high = MASK; code = 0
            repeat(32) { code = (code shl 1) or readCodeBit().toLong() }
        }

        private fun readCodeBit(): Int {
            if (bitsLeft == 0) {
                if (remain > 0) {
                    bitsLeft = 8; pos++; remain--
                } else {
                    // Out of input: the encoder's tail is implicit zero bits, unless the code is exhausted.
                    if (code == 0L) readError = true
                    return 0
                }
            }
            bitsLeft--
            return (buf[pos].toInt() shr bitsLeft) and 1
        }

        private fun update() {
            while (((low xor high) and TOP_MASK) == 0L) {
                code = ((code shl 1) and MASK) or readCodeBit().toLong()
                low = (low shl 1) and MASK
                high = ((high shl 1) and MASK) or 1
            }
            while ((low and high.inv() and SECOND_MASK) != 0L) {
                code = (code and TOP_MASK) or ((code shl 1) and (MASK ushr 1)) or readCodeBit().toLong()
                low = (low shl 1) and (MASK ushr 1)
                high = ((high shl 1) and (MASK ushr 1)) or TOP_MASK or 1
            }
        }

        /** Fixed-probability bits (LZMA's "direct bits"). */
        fun decodeDirect(bitsLen: Int): Long {
            val range = (high - low + 1) ushr bitsLen
            val symbol = ((code - low) / range) and 0xFFFFFFFFL
            high = low + range * (symbol + 1) - 1
            low += range * symbol
            update()
            return symbol
        }

        private fun decodeBit(probs: IntArray, idx: Int, probBits: Int, moveBits: Int): Int {
            val range = (high - low + 1) ushr probBits
            val bound = ((code - low) / range) and 0xFFFFFFFFL
            val p = probs[idx]
            val bit: Int
            if (bound < p) {
                high = (low + range * p - 1) and 0xFFFFFFFFL
                bit = 0
                probs[idx] = p + ((1 shl probBits) - p) / (1 shl moveBits)
            } else {
                low = (low + range * p) and 0xFFFFFFFFL
                bit = 1
                probs[idx] = p - p / (1 shl moveBits)
            }
            update()
            if (readError) throw IOException("Corrupt AZO data (input exhausted)")
            return bit
        }

        fun decodeBoolState(bp: BoolProb): Int {
            val bit = decodeBit(bp.prob, bp.state, BOOL_PROB_BITS, BOOL_PROB_SHIFT_BITS)
            bp.state = ((bp.state shl 1) and 0xFF) + bit
            return bit
        }

        /** LZMA-style bit tree: [bitsLen] bits MSB-first, each with its own context. */
        fun decodeTree(probs: IntArray, bitsLen: Int, probBits: Int, moveBits: Int): Int {
            var symbol = 1
            repeat(bitsLen) { symbol = (symbol shl 1) + decodeBit(probs, symbol, probBits, moveBits) }
            return symbol - (1 shl bitsLen)
        }

        /** Trains [probs] on [value] as if it had been decoded from that tree, without reading bits. */
        fun updateProb(probs: IntArray, value: Int, bitsLen: Int, probBits: Int, moveBits: Int) {
            var symbol = 1
            var v = value
            val msb = 1 shl (bitsLen - 1)
            repeat(bitsLen) {
                val bit = if (v and msb != 0) 1 else 0
                v = v shl 1
                val p = probs[symbol]
                probs[symbol] = if (bit == 0) p + ((1 shl probBits) - p) / (1 shl moveBits) else p - p / (1 shl moveBits)
                symbol = (symbol shl 1) + bit
            }
        }

        /**
         * Which of the two models predicted [value] better (xunazo CalcProbSum): >0 when [prob1]'s
         * product of bit probabilities is larger, <0 when [prob2]'s is, 0 on a tie. Uses the same
         * uint32 products with the same rescaling as the original so the model states stay in step.
         */
        fun compareProbSum(prob1: IntArray, prob2: IntArray, value: Int, bitsLen: Int, probBits: Int): Int {
            var prev = 1
            var v = value
            val msb = 1 shl (bitsLen - 1)
            val maxProb = 1L shl probBits
            var t1 = 1L
            var t2 = 1L
            repeat(bitsLen) {
                val p1 = prob1[prev].toLong()
                val p2 = prob2[prev].toLong()
                val bit = if (v and msb != 0) 1 else 0
                v = v shl 1
                if (((t1 or t2) and 0xFFC00000L) != 0L) { t1 = t1 ushr probBits; t2 = t2 ushr probBits }
                t1 = (t1 * (if (bit == 1) maxProb - p1 else p1)) and 0xFFFFFFFFL
                t2 = (t2 * (if (bit == 1) maxProb - p2 else p2)) and 0xFFFFFFFFL
                prev = (prev shl 1) + bit
            }
            return t1.compareTo(t2)
        }
    }

    companion object {
        private const val PROB_BITS = 10
        private const val PROB_MOVE_BITS = 4
        private const val BOOL_PROB_BITS = 12
        private const val BOOL_PROB_SHIFT_BITS = 6
        private const val LIT_SHIFT = 5
        private const val LEN_SHIFT = 4
        private const val MIN_DIST = 1
        private const val MIN_LEN = 2

        private const val MASK = (1L shl 32) - 1
        private const val TOP_MASK = 1L shl 31
        private const val SECOND_MASK = 1L shl 30

        // Length / distance base values and their extra-bit counts (xunazo XAzoTable).
        private val LEN_TABLE = IntArray(128)
        private val LEN_EXTRA = IntArray(128)
        private val DIST_TABLE = IntArray(128)
        private val DIST_EXTRA = IntArray(128)

        init {
            for (i in 0 until 40) { LEN_TABLE[i] = i + MIN_LEN; LEN_EXTRA[i] = 0 }
            for (i in 40 until 128) {
                LEN_EXTRA[i] = (i - 40) / 8 + 1
                LEN_TABLE[i] = LEN_TABLE[i - 1] + (1 shl LEN_EXTRA[i - 1])
            }
            for (i in 0 until 20) { DIST_TABLE[i] = i + MIN_DIST; DIST_EXTRA[i] = 0 }
            for (i in 20 until 128) {
                DIST_EXTRA[i] = (i - 20) / 4 + 1
                DIST_TABLE[i] = DIST_TABLE[i - 1] + (1 shl DIST_EXTRA[i - 1])
            }
        }

        /**
         * AZO's x86 filter: undoes the encoder's E8/E9 relative-to-absolute call/jump conversion,
         * applied per block (xunazo notes it only really matters for the first one).
         */
        fun x86FilterDecode(buf: ByteArray, size: Int) {
            if (size < 5) return
            var i = 0
            while (i < size - 4) {
                val op = buf[i].toInt() and 0xFF
                if (op == 0xE8 || op == 0xE9) {
                    val top = buf[i + 4].toInt() and 0xFF
                    if (top == 0 || top == 0xFF) {
                        var v = ((buf[i + 1].toLong() and 0xFF) or ((buf[i + 2].toLong() and 0xFF) shl 8) or
                            ((buf[i + 3].toLong() and 0xFF) shl 16) or ((buf[i + 4].toLong() and 0xFF) shl 24)) - i
                        v = v and 0xFFFFFFFFL
                        if ((v and 0xFF000000L) != 0xFF000000L) v = v and 0x00FFFFFFL
                        buf[i + 1] = v.toByte(); buf[i + 2] = (v shr 8).toByte()
                        buf[i + 3] = (v shr 16).toByte(); buf[i + 4] = (v shr 24).toByte()
                    }
                    i += 4
                }
                i++
            }
        }
    }
}
