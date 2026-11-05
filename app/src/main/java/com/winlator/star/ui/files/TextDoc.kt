package com.winlator.star.ui.files

// Ported from BFE (The412Banner/BFE, app/.../docs/TextDoc.kt, v1.5.0); the CSV parser is left out.

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** How a text file's bytes are read: the charset, a byte-order mark to skip, and the code-unit width. */
data class TextEncoding(val charset: Charset, val label: String, val bom: Int, val unit: Int, val bigEndian: Boolean = false) {
    companion object {
        val UTF8 = TextEncoding(Charsets.UTF_8, "UTF-8", 0, 1)
        val UTF8_BOM = TextEncoding(Charsets.UTF_8, "UTF-8 (BOM)", 3, 1)
        val UTF16LE = TextEncoding(Charsets.UTF_16LE, "UTF-16 LE", 2, 2)
        val UTF16BE = TextEncoding(Charsets.UTF_16BE, "UTF-16 BE", 2, 2, bigEndian = true)
        val UTF16LE_NOBOM = UTF16LE.copy(bom = 0)
        val UTF16BE_NOBOM = UTF16BE.copy(bom = 0)
        val LATIN1: TextEncoding = TextEncoding(
            runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1), "Latin-1 (Windows-1252)", 0, 1,
        )
    }
}

/** One find-in-text hit: display row and the character range in it. */
data class TextMatch(val row: Int, val start: Int, val length: Int)

/**
 * A text file indexed for the text viewer without decoding it as a whole: the bytes stay in a
 * read-only memory map and only the rows on screen are decoded. A row is one line, or a [ROW_BYTES]
 * slice of a very long one (a minified JSON / one-line log), so no single row is too big to lay out.
 */
class TextDoc private constructor(
    private val buf: ByteBuffer,
    val encoding: TextEncoding,
    private val starts: IntArray,
    private val ends: IntArray,
    private val lineOf: IntArray,
    val rowCount: Int,
    /** Logical lines (rows minus long-line continuations). */
    val lineCount: Int,
    /** The longest row, in code units (≈ characters), for the no-wrap width. */
    val longestRow: Int,
    /** The file had more than [MAX_ROWS] rows / [MAX_BYTES] bytes; the rest isn't shown. */
    val truncated: Boolean,
    /** Mostly control bytes: probably not text at all. */
    val binary: Boolean,
) {
    val size: Int get() = buf.limit()

    /** 1-based line number of display row [i]. */
    fun lineNumber(i: Int): Int = lineOf[i] + 1

    /** Row [i] continues the previous row's line (a long line split up). */
    fun isContinuation(i: Int): Boolean = i > 0 && lineOf[i] == lineOf[i - 1]

    fun row(i: Int): String = decode(starts[i], ends[i])

    private fun decode(start: Int, end: Int): String {
        if (end <= start) return ""
        val slice = buf.duplicate().apply { limit(end); position(start) }
        return encoding.charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(slice).toString()
    }

    /** The whole text (CSV table view, copying), up to [maxChars]. */
    fun fullText(maxChars: Int = 32 * 1024 * 1024): String {
        val end = minOf(buf.limit().toLong(), encoding.bom + maxChars.toLong() * encoding.unit).toInt()
        return decode(encoding.bom, end)
    }

    /**
     * Every occurrence of [query] (up to [limit]), row by row. [cancelled] is polled so a new query can
     * stop an old search on a big log.
     */
    fun find(query: String, ignoreCase: Boolean = true, limit: Int = 10_000, cancelled: () -> Boolean = { false }): List<TextMatch> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<TextMatch>()
        for (r in 0 until rowCount) {
            if (r and 255 == 0 && cancelled()) break
            val s = row(r)
            var i = s.indexOf(query, 0, ignoreCase)
            while (i >= 0) {
                out.add(TextMatch(r, i, query.length))
                if (out.size >= limit) return out
                i = s.indexOf(query, i + query.length, ignoreCase)
            }
        }
        return out
    }

    companion object {
        /** Longest display row, in bytes. */
        const val ROW_BYTES = 4096
        const val MAX_ROWS = 4_000_000
        const val MAX_BYTES = 1L shl 30

        fun open(file: File): TextDoc {
            val len = file.length()
            val mapLen = minOf(len, MAX_BYTES)
            val buf: ByteBuffer = if (mapLen < 256 * 1024) ByteBuffer.wrap(file.readBytes().let { if (it.size > mapLen) it.copyOf(mapLen.toInt()) else it })
            else RandomAccessFile(file, "r").use { raf -> raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, mapLen) }
            return index(buf, len > MAX_BYTES)
        }

        fun of(bytes: ByteArray): TextDoc = index(ByteBuffer.wrap(bytes), false)

        private fun index(buf: ByteBuffer, cut: Boolean): TextDoc {
            val size = buf.limit()
            val head = ByteArray(minOf(size, 64 * 1024)).also { buf.duplicate().get(it) }
            val enc = detect(head, size)
            val rows = RowIndex()
            var truncated = cut
            if (enc.unit == 1) {
                var start = enc.bom
                var i = start
                while (i < size) {
                    val b = buf.get(i)
                    if (b == '\n'.code.toByte() || (b == '\r'.code.toByte() && (i + 1 >= size || buf.get(i + 1) != '\n'.code.toByte()))) {
                        val end = if (b == '\n'.code.toByte() && i > start && buf.get(i - 1) == '\r'.code.toByte()) i - 1 else i
                        rows.addLine(buf, start, end, 1, enc)
                        start = i + 1
                        if (rows.count >= MAX_ROWS) { truncated = true; break }
                    }
                    i++
                }
                if (!truncated && start < size) rows.addLine(buf, start, size, 1, enc)
            } else {
                var start = enc.bom
                var i = start
                fun unit(at: Int): Int {
                    val a = buf.get(at).toInt() and 0xFF
                    val b = buf.get(at + 1).toInt() and 0xFF
                    return if (enc.bigEndian) (a shl 8) or b else (b shl 8) or a
                }
                while (i + 1 < size) {
                    val u = unit(i)
                    if (u == 0x0A || (u == 0x0D && (i + 3 >= size || unit(i + 2) != 0x0A))) {
                        val end = if (u == 0x0A && i - 2 >= start && unit(i - 2) == 0x0D) i - 2 else i
                        rows.addLine(buf, start, end, 2, enc)
                        start = i + 2
                        if (rows.count >= MAX_ROWS) { truncated = true; break }
                    }
                    i += 2
                }
                if (!truncated && start < size - 1) rows.addLine(buf, start, size - (size - start) % 2, 2, enc)
            }
            if (rows.count == 0) rows.add(enc.bom, enc.bom, 0)
            return TextDoc(
                buf, enc, rows.starts, rows.ends, rows.lines, rows.count, rows.line, rows.longest, truncated,
                binary = !looksLikeText(head),
            )
        }

        /** BOM → UTF-8 / UTF-16; no BOM: UTF-16 by its zero bytes, UTF-8 if the head decodes, else Latin-1. */
        fun detect(head: ByteArray, total: Int = head.size): TextEncoding {
            fun b(i: Int) = head[i].toInt() and 0xFF
            if (head.size >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) return TextEncoding.UTF8_BOM
            if (head.size >= 2 && b(0) == 0xFF && b(1) == 0xFE) return TextEncoding.UTF16LE
            if (head.size >= 2 && b(0) == 0xFE && b(1) == 0xFF) return TextEncoding.UTF16BE
            if (head.size >= 4) {
                var evenZero = 0
                var oddZero = 0
                val n = head.size - head.size % 2
                for (i in 0 until n step 2) { if (head[i].toInt() == 0) evenZero++; if (head[i + 1].toInt() == 0) oddZero++ }
                val half = n / 2
                if (oddZero > half * 0.3 && evenZero < half * 0.05) return TextEncoding.UTF16LE_NOBOM
                if (evenZero > half * 0.3 && oddZero < half * 0.05) return TextEncoding.UTF16BE_NOBOM
            }
            // A multi-byte sequence cut by the end of the sample isn't an error.
            var end = head.size
            if (total > head.size) {
                var k = 0
                while (k < 3 && end > 0 && (head[end - 1].toInt() and 0xC0) == 0x80) { end--; k++ }
                if (end > 0 && (head[end - 1].toInt() and 0xC0) == 0xC0) end--
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(head, 0, end))
                TextEncoding.UTF8
            } catch (_: CharacterCodingException) {
                TextEncoding.LATIN1
            }
        }

        /** Few control bytes and no stray NULs (outside a UTF-16 pattern): reads as text. */
        fun looksLikeText(head: ByteArray): Boolean {
            if (head.isEmpty()) return true
            val enc = detect(head)
            if (enc.unit == 2) return true
            var bad = 0
            for (x in head) {
                val c = x.toInt() and 0xFF
                if (c == 0) return false
                if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D && c != 0x0C && c != 0x1B) bad++
            }
            return bad <= head.size / 50
        }
    }

    /** Growable parallel arrays of row start / end / line number. */
    private class RowIndex {
        var starts = IntArray(1024); var ends = IntArray(1024); var lines = IntArray(1024)
        var count = 0
        var line = 0
        var longest = 0

        fun add(s: Int, e: Int, l: Int) {
            if (count == starts.size) {
                val n = starts.size * 2
                starts = starts.copyOf(n); ends = ends.copyOf(n); lines = lines.copyOf(n)
            }
            starts[count] = s; ends[count] = e; lines[count] = l
            count++
        }

        /** One logical line [s, e), split into rows of at most ROW_BYTES on character boundaries. */
        fun addLine(buf: ByteBuffer, s: Int, e: Int, unit: Int, enc: TextEncoding) {
            var a = s
            while (e - a > ROW_BYTES) {
                var cut = a + ROW_BYTES
                if (unit == 1) {
                    if (enc.charset == Charsets.UTF_8) while (cut > a + 1 && (buf.get(cut).toInt() and 0xC0) == 0x80) cut--
                } else {
                    cut -= (cut - a) % 2
                    // Don't split a surrogate pair: back off when the unit before the cut is a high surrogate.
                    val hi = if (enc.bigEndian) buf.get(cut - 2).toInt() and 0xFF else buf.get(cut - 1).toInt() and 0xFF
                    if (hi in 0xD8..0xDB) cut -= 2
                }
                add(a, cut, line)
                longest = maxOf(longest, (cut - a) / unit)
                a = cut
            }
            add(a, e, line)
            longest = maxOf(longest, (e - a) / unit)
            line++
            if (count >= MAX_ROWS) return
        }
    }
}
