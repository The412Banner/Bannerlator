package com.winlator.star.core.unpack

import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Reads the table of contents of a FreeArc archive (the format of `Setup-N.bin` data in many game
 * installers) without unpacking anything: how many files, how big, and - the part BFE needs - the
 * compression chain of every solid block, e.g. `precomp047+srep:m3f+lzma:96mb`. Each `+` part is a
 * layer; the ones unarc doesn't decode itself are external programs (see [RepackLayers]).
 *
 * Layout as FreeArc 0.67 Unarc/ArcStructure.h reads it: the last ≤ 4096 bytes end with the footer's
 * local descriptor ("ArC\1", type, compressor, sizes, CRC, then its own CRC); the footer block lists
 * the control blocks; every directory block lists its data blocks' compressors and the files.
 * Control blocks are stored or LZMA-compressed. Every block read here is CRC-checked.
 *
 * Plain JVM (no Android), so it is unit-tested directly.
 */
object FreeArcInfo {

    data class Block(val compressor: String, val files: Int, val packedSize: Long)

    data class Info(
        val blocks: List<Block>,
        val totalFiles: Int,
        val totalBytes: Long,
        /** Headers are password-protected: nothing but this flag can be read without the password. */
        val headersEncrypted: Boolean = false,
    )

    private val SIGNATURE = byteArrayOf('A'.code.toByte(), 'r'.code.toByte(), 'C'.code.toByte(), 1)
    private const val TAIL = 4096
    private const val DIR_BLOCK = 3
    private const val FOOTER_BLOCK = 4
    /** Control blocks are small; anything claiming more is not a FreeArc header we should trust. */
    private const val MAX_CONTROL_BLOCK = 256L shl 20

    private val CIPHERS = listOf("aes", "blowfish", "twofish", "serpent")

    /** The method names of a compression chain, in the order they were applied: `rep:64mb+lzma` → [rep, lzma]. */
    fun methodNames(compressor: String): List<String> =
        compressor.split('+').map { it.substringBefore(':').trim().lowercase() }.filter { it.isNotEmpty() }

    /** True when [compressor] includes a cipher layer (`…+aes-256/ctr:…`). */
    fun isEncrypted(compressor: String): Boolean =
        methodNames(compressor).any { m -> CIPHERS.any { m.startsWith(it) } }

    /** Returns the archive's contents, or null when [file] isn't a readable FreeArc archive. */
    fun read(file: File): Info? = runCatching { RandomAccessFile(file, "r").use { parse(it) } }.getOrNull()

    private fun parse(raf: RandomAccessFile): Info? {
        val size = raf.length()
        if (size < 16) return null
        val tailLen = minOf(size, TAIL.toLong()).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(size - tailLen)
        raf.readFully(tail)
        val at = lastIndexOf(tail, SIGNATURE)
        if (at < 0) return null
        val descrPos = size - tailLen + at
        val descr = tail.copyOfRange(at, tailLen)
        // The descriptor ends with the CRC of everything before it.
        if (descr.size < 8 || crc(descr, 0, descr.size - 4) != le32(descr, descr.size - 4)) return null

        val d = Buf(descr, 4)
        val type = d.int()
        val footerComp = d.str()
        val footerOrig = d.long()
        val footerSize = d.long()
        val footerCrc = d.u32()
        if (type != FOOTER_BLOCK || footerSize <= 0 || footerSize > descrPos) return null
        if (isEncrypted(footerComp)) return Info(emptyList(), 0, 0, headersEncrypted = true)
        val footerPos = descrPos - footerSize
        val footer = controlBlock(raf, footerComp, footerPos, footerSize, footerOrig, footerCrc) ?: return null

        val f = Buf(footer, 0)
        val count = f.int()
        val blocks = ArrayList<Block>()
        var files = 0
        var bytes = 0L
        repeat(count) {
            val t = f.int()
            val comp = f.str()
            val relPos = f.long()
            val orig = f.long()
            val packed = f.long()
            val c = f.u32()
            if (t != DIR_BLOCK) return@repeat
            if (isEncrypted(comp)) return Info(emptyList(), 0, 0, headersEncrypted = true)
            val dir = controlBlock(raf, comp, footerPos - relPos, packed, orig, c) ?: return null
            val parsed = parseDirectory(dir)
            blocks += parsed.blocks
            files += parsed.totalFiles
            bytes += parsed.totalBytes
        }
        return Info(blocks, files, bytes)
    }

    private fun parseDirectory(dir: ByteArray): Info {
        val b = Buf(dir, 0)
        val n = b.int()
        val perBlock = IntArray(n) { b.int() }
        val comps = Array(n) { b.str() }
        repeat(n) { b.long() }                      // offsets (position relative to this block)
        val packed = LongArray(n) { b.long() }
        val dirs = b.int()
        repeat(dirs) { b.str() }
        val total = perBlock.sum()
        repeat(total) { b.str() }                   // names
        repeat(total) { b.long() }                  // directory numbers
        var bytes = 0L
        repeat(total) { bytes += b.long() }         // sizes
        return Info(List(n) { Block(comps[it], perBlock[it], packed[it]) }, total, bytes)
    }

    private fun controlBlock(raf: RandomAccessFile, comp: String, pos: Long, packed: Long, orig: Long, crc: Long): ByteArray? {
        if (pos < 0 || packed <= 0 || orig <= 0 || packed > MAX_CONTROL_BLOCK || orig > MAX_CONTROL_BLOCK) return null
        val raw = ByteArray(packed.toInt())
        raf.seek(pos)
        raf.readFully(raw)
        val data = when (comp.substringBefore(':').lowercase()) {
            "storing" -> raw
            "lzma" -> lzma(comp, raw, orig.toInt())
            else -> return null
        }
        if (data.size.toLong() != orig || crc(data, 0, data.size) != crc) return null
        return data
    }

    /** FreeArc's lzma = raw LZMA1 with lc/lp/pb from the method string (defaults 3/0/2). */
    private fun lzma(method: String, raw: ByteArray, orig: Int): ByteArray {
        var lc = 3
        var lp = 0
        var pb = 2
        for (p in method.split(':').drop(1).map { it.trimStart('*') }) {
            when {
                p.startsWith("lc") -> p.drop(2).toIntOrNull()?.let { lc = it }
                p.startsWith("lp") -> p.drop(2).toIntOrNull()?.let { lp = it }
                p.startsWith("pb") -> p.drop(2).toIntOrNull()?.let { pb = it }
            }
        }
        // The output is only `orig` bytes long, so a dictionary of that size is always enough.
        val dict = maxOf(orig, 4096)
        val out = ByteArray(orig)
        LZMAInputStream(ByteArrayInputStream(raw), orig.toLong(), lc, lp, pb, dict, null).use {
            // FreeArc writes an end marker even though the size is known; strict mode calls that corrupt.
            it.enableRelaxedEndCondition()
            DataInputStream(it).readFully(out)
        }
        return out
    }

    private fun lastIndexOf(hay: ByteArray, needle: ByteArray): Int {
        var i = hay.size - needle.size
        while (i >= 0) {
            var ok = true
            for (j in needle.indices) if (hay[i + j] != needle[j]) { ok = false; break }
            if (ok) return i
            i--
        }
        return -1
    }

    private fun crc(b: ByteArray, off: Int, len: Int): Long = CRC32().apply { update(b, off, len) }.value

    private fun le32(b: ByteArray, p: Int): Long =
        (b[p].toLong() and 0xff) or ((b[p + 1].toLong() and 0xff) shl 8) or
            ((b[p + 2].toLong() and 0xff) shl 16) or ((b[p + 3].toLong() and 0xff) shl 24)

    /** Cursor over a control block: FreeArc's variable-length integers and NUL-terminated strings. */
    private class Buf(val b: ByteArray, var p: Int) {
        private fun byte(i: Int): Long = if (i < b.size) b[i].toLong() and 0xff else 0L

        /** 1..9-byte integer: the count of low 1-bits in the first byte = extra bytes (Unarc readInteger). */
        fun long(): Long {
            require(p < b.size) { "truncated" }
            val first = byte(p).toInt()
            var n = 1
            while (n <= 8 && (first shr (n - 1)) and 1 == 1) n++
            if (n == 9) {
                var v = 0L
                for (i in 0 until 8) v = v or (byte(p + 1 + i) shl (8 * i))
                p += 9
                return v
            }
            var v = 0L
            for (i in 0 until n) v = v or (byte(p + i) shl (8 * i))
            p += n
            require(p <= b.size) { "truncated" }
            return v ushr n
        }

        fun int(): Int = long().also { require(it in 0..Int.MAX_VALUE) { "bad count" } }.toInt()

        fun u32(): Long {
            require(p + 4 <= b.size) { "truncated" }
            return le32(b, p).also { p += 4 }
        }

        fun str(): String {
            var e = p
            while (e < b.size && b[e] != 0.toByte()) e++
            require(e < b.size) { "unterminated string" }
            return String(b, p, e - p, Charsets.UTF_8).also { p = e + 1 }
        }
    }
}
