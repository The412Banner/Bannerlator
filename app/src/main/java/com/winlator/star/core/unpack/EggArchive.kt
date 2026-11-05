package com.winlator.star.core.unpack

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.LZMAInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * In-app reader for ALZip's EGG archives (`.egg`, ALZip 8 and later) — pure Kotlin, no native
 * engine. Written from ESTsoft's published "EGG Format Specification 1.0" with EggDotNet
 * (https://github.com/akolman/EggDotNet, MIT) as the reference implementation for the details the
 * spec leaves open; see licenses/NOTICE_EGG.txt. ESTsoft's own EGG module is not used.
 *
 * Handles store / Deflate / BZip2 / LZMA / AZO blocks ([AzoDecoder]), files split over several
 * blocks (anything over 4 GB), solid archives (one decoded stream holding every file in order),
 * split archives (`name.vol1.egg`, `name.vol2.egg`, … — chained by the header ids each volume
 * records, so it works from any volume), UTF-8 and code-page names (949 Korean, 932 Japanese), and
 * Windows / POSIX timestamps. Every block is checked against its CRC-32. Encrypted archives
 * (ZIP 2.0 / AES / LEA) are reported up front instead of failing halfway.
 */
object EggArchive {
    private const val MAGIC_EGG = 0x41474745          // "EGGA"
    private const val MAGIC_END = 0x08E28222
    private const val MAGIC_SPLIT = 0x24F5A262
    private const val MAGIC_SOLID = 0x24E5A060
    private const val MAGIC_GLOBAL_ENCRYPT = 0x08D144A8
    private const val MAGIC_FILE = 0x0A8590E3
    private const val MAGIC_FILENAME = 0x0A8591AC
    private const val MAGIC_COMMENT = 0x04C63672
    private const val MAGIC_WINFILE = 0x2C86950B
    private const val MAGIC_POSIXFILE = 0x1EE922E5
    private const val MAGIC_ENCRYPT = 0x08D1470F
    private const val MAGIC_BLOCK = 0x02B50C13
    private const val MAGIC_DUMMY = 0x07463307
    private const val MAGIC_SKIP = 0xFFFF0000.toInt()

    private val METHOD_NAMES = mapOf(0 to "Store", 1 to "Deflate", 2 to "BZip2", 3 to "AZO", 4 to "LZMA")

    /** One compressed block: [dataPos] is its offset in the joined (all-volume) stream. */
    class Block(val method: Int, val uncompSize: Long, val compSize: Long, val crc: Int, val dataPos: Long)

    class Entry(
        val name: String,
        val size: Long,
        val isDir: Boolean,
        val encrypted: Boolean,
        val mtimeMs: Long,
        val blocks: MutableList<Block> = ArrayList(1),
    )

    class Archive internal constructor(
        val volumes: List<File>,
        val solid: Boolean,
        val entries: List<Entry>,
        val solidBlocks: List<Block>,
        private val globalEncrypted: Boolean,
        internal val segments: List<SegmentedInput.Segment>,
    ) {
        val totalSize: Long get() = entries.sumOf { if (it.isDir) 0L else it.size }
        val fileCount: Int get() = entries.count { !it.isDir }
        private val allBlocks: List<Block> get() = solidBlocks + entries.flatMap { it.blocks }
        val methods: List<String> get() = allBlocks.map { METHOD_NAMES[it.method] ?: "method ${it.method}" }.distinct()

        /** Why this archive can't be unpacked in-app (shown as-is), or null when it can. */
        val problem: String?
            get() = when {
                globalEncrypted || entries.any { it.encrypted } ->
                    "This EGG archive is password-protected. Bannerlator can't decrypt EGG archives (ZIP 2.0 / AES / LEA encryption)."
                allBlocks.any { it.method !in METHOD_NAMES } ->
                    "This EGG archive uses a compression method Bannerlator doesn't know (${allBlocks.first { it.method !in METHOD_NAMES }.method})."
                else -> null
            }
    }

    private class VolumeHeader(
        val file: File, val id: Int, val prev: Int, val next: Int,
        val split: Boolean, val solid: Boolean, val encrypted: Boolean, val headerEnd: Long,
    )

    /** Field size after an extra field's bit flag: bit 0 set means a 4-byte size, else 2 bytes. */
    private fun fieldSize(inp: SegmentedInput, flag: Int): Long = if (flag and 1 != 0) inp.u32() else inp.u16().toLong()

    private fun readVolumeHeader(file: File): VolumeHeader? {
        if (!file.isFile || file.length() < 14) return null
        SegmentedInput(listOf(SegmentedInput.Segment(file, 0, file.length()))).use { inp ->
            if (inp.i32() != MAGIC_EGG) return null
            val version = inp.u16()
            if (version != 0x0100) throw IOException("Unsupported EGG version 0x%04x".format(version))
            val id = inp.i32()
            inp.i32()   // reserved
            var prev = 0; var next = 0; var split = false; var solid = false; var encrypted = false
            while (true) {
                val magic = inp.i32()
                if (magic == MAGIC_END) break
                val flag = inp.u8()
                val size = fieldSize(inp, flag)
                val start = inp.position
                when (magic) {
                    MAGIC_SPLIT -> { split = true; prev = inp.i32(); next = inp.i32() }
                    MAGIC_SOLID -> solid = true
                    MAGIC_GLOBAL_ENCRYPT -> encrypted = true
                }
                inp.seek(start + size)
            }
            return VolumeHeader(file, id, prev, next, split, solid, encrypted, inp.position)
        }
    }

    /** All volumes of the split archive [h] belongs to, first to last, found among its sibling .egg files. */
    private fun resolveChain(h: VolumeHeader): List<VolumeHeader> {
        val dir = h.file.absoluteFile.parentFile ?: return listOf(h)
        val byId = HashMap<Int, VolumeHeader>()
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".egg", ignoreCase = true) }?.forEach { f ->
            runCatching { readVolumeHeader(f) }.getOrNull()?.takeIf { it.split }?.let { byId[it.id] = it }
        }
        byId[h.id] = h
        fun missing(id: Int) = IOException(
            "This is part of a split EGG archive, but a volume (id $id) is missing. Put every " +
                ".vol*.egg part in the same folder.",
        )
        var first = h
        var guard = 0
        while (first.prev != 0) {
            first = byId[first.prev] ?: throw missing(first.prev)
            if (++guard > byId.size) throw IOException("Corrupt split EGG archive (volume loop)")
        }
        val chain = arrayListOf(first)
        while (chain.last().next != 0) {
            chain += byId[chain.last().next] ?: throw missing(chain.last().next)
            if (chain.size > byId.size) throw IOException("Corrupt split EGG archive (volume loop)")
        }
        return chain
    }

    /** True when [file] starts with the EGG signature. */
    fun isEgg(file: File): Boolean = runCatching {
        java.io.RandomAccessFile(file, "r").use { r ->
            val b = ByteArray(4); r.readFully(b); String(b, Charsets.US_ASCII) == "EGGA"
        }
    }.getOrDefault(false)

    /** Reads every header of [file] (and, for a split archive, of its sibling volumes). Seeks past the data. */
    fun open(file: File): Archive {
        val h = readVolumeHeader(file) ?: throw IOException("Not an EGG archive")
        val chain = if (h.split) resolveChain(h) else listOf(h)
        val segments = chain.map { SegmentedInput.Segment(it.file, it.headerEnd, it.file.length() - it.headerEnd) }
        val solid = chain.any { it.solid }
        val entries = ArrayList<Entry>()
        val solidBlocks = ArrayList<Block>()
        SegmentedInput(segments).use { inp ->
            loop@ while (inp.position + 4 <= inp.length) {
                when (val magic = inp.i32()) {
                    MAGIC_FILE -> {
                        val e = parseFile(inp)
                        entries += e
                        if (!solid) while (peek(inp) == MAGIC_BLOCK) { inp.skip(4); e.blocks += parseBlock(inp) }
                    }
                    // Solid archives keep every block after the file headers; elsewhere a stray block
                    // still belongs to the entry before it.
                    MAGIC_BLOCK -> {
                        val b = parseBlock(inp)
                        if (solid || entries.isEmpty()) solidBlocks += b else entries.last().blocks += b
                    }
                    MAGIC_COMMENT, MAGIC_DUMMY, MAGIC_SKIP -> { val flag = inp.u8(); inp.skip(fieldSize(inp, flag)) }
                    MAGIC_END -> break@loop
                    else -> throw IOException("Corrupt EGG archive (unknown header 0x%08x at %d)".format(magic, inp.position - 4))
                }
            }
        }
        return Archive(chain.map { it.file }, solid, entries, solidBlocks, chain.any { it.encrypted }, segments)
    }

    private fun peek(inp: SegmentedInput): Int {
        if (inp.position + 4 > inp.length) return 0
        val p = inp.position
        return inp.i32().also { inp.seek(p) }
    }

    private fun parseFile(inp: SegmentedInput): Entry {
        val id = inp.i32()
        val length = inp.i64()
        var name: String? = null
        var isDir = false
        var encrypted = false
        var mtime = 0L
        while (true) {
            val magic = inp.i32()
            if (magic == MAGIC_END) break
            val flag = inp.u8()
            val size = fieldSize(inp, flag)
            val start = inp.position
            when (magic) {
                MAGIC_FILENAME -> {
                    if (flag and 0x04 != 0) encrypted = true
                    // As in EggDotNet (and ALZip's output): the size counts the name bytes only; the
                    // optional locale (flag 0x08) and parent-path id (flag 0x10) come before them.
                    val locale = if (flag and 0x08 != 0) inp.u16() else -1
                    if (flag and 0x10 != 0) inp.skip(4)
                    if (size > 64 * 1024) throw IOException("Corrupt EGG archive (file name length $size)")
                    val bytes = ByteArray(size.toInt())
                    inp.readFully(bytes)
                    name = decodeName(bytes, locale)
                    continue
                }
                MAGIC_WINFILE -> {
                    val ft = inp.i64()
                    val attr = inp.u8()
                    if (attr and 0x80 != 0) isDir = true
                    if (ft > 0) mtime = ft / 10_000 - 11_644_473_600_000L
                }
                MAGIC_POSIXFILE -> {
                    val mode = inp.i32()
                    inp.i32(); inp.i32()   // uid, gid
                    val secs = inp.i64()
                    if (mode and 0xF000 == 0x4000) isDir = true
                    if (secs > 0) mtime = secs * 1000
                }
                MAGIC_ENCRYPT -> encrypted = true
            }
            inp.seek(start + size)
        }
        val n = (name ?: "file$id").replace('\\', '/')
        if (n.endsWith("/")) isDir = true
        return Entry(n.trimEnd('/'), length, isDir, encrypted, mtime)
    }

    private fun parseBlock(inp: SegmentedInput): Block {
        val method = inp.u8()
        inp.u8()   // hint
        val uncomp = inp.u32()
        val comp = inp.u32()
        val crc = inp.i32()
        while (true) {
            val magic = inp.i32()
            if (magic == MAGIC_END) break
            val flag = inp.u8()
            inp.skip(fieldSize(inp, flag))
        }
        val pos = inp.position
        inp.skip(comp)
        return Block(method, uncomp, comp, crc, pos)
    }

    /** UTF-8 by default; a code page when the header names one; for "system locale" (0), UTF-8 else Korean. */
    private fun decodeName(bytes: ByteArray, locale: Int): String {
        val cs = when (locale) {
            -1 -> Charsets.UTF_8
            0 -> null
            949 -> charset("x-windows-949", "MS949", "windows-949", "EUC-KR")
            932 -> charset("windows-31j", "MS932", "Shift_JIS")
            else -> charset("windows-$locale", "x-windows-$locale", "Cp$locale")
        }
        if (cs != null) return String(bytes, cs)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, charset("x-windows-949", "MS949", "windows-949", "EUC-KR") ?: Charsets.ISO_8859_1)
        }
    }

    private fun charset(vararg names: String): Charset? =
        names.firstNotNullOfOrNull { n -> runCatching { Charset.forName(n) }.getOrNull() }

    /**
     * Extracts every entry of [archive] into [destDir]. Progress is output bytes against the archive's
     * total uncompressed size. Entries whose names would escape [destDir] are skipped (their data is
     * still consumed in a solid archive). Returns the number of files written.
     */
    fun extract(archive: Archive, destDir: File, sink: ExtractSink, cancelled: () -> Boolean): Int {
        archive.problem?.let { throw UnsupportedArchiveException(it) }
        destDir.mkdirs()
        val total = archive.totalSize.coerceAtLeast(1L)
        var done = 0L
        var files = 0
        val buf = ByteArray(256 * 1024)

        fun copy(src: InputStream, n: Long, out: OutputStream?, name: String) {
            var left = n
            while (left > 0) {
                if (cancelled()) throw ExtractCancelled()
                val r = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw IOException("Unexpected end of data in $name")
                out?.write(buf, 0, r)
                left -= r
                done += r
                sink.onBytes(done, total)
            }
        }

        SegmentedInput(archive.segments).use { inp ->
            val solidStream = if (archive.solid) BlockChain(inp, archive.solidBlocks, cancelled) else null
            try {
                for (e in archive.entries) {
                    val target = ExtractPaths.safeChild(destDir, e.name)
                    if (e.isDir) {
                        target?.mkdirs()
                        continue
                    }
                    sink.onFile(e.name)
                    val src = solidStream ?: BlockChain(inp, e.blocks, cancelled)
                    try {
                        if (target == null) {
                            copy(src, e.size, null, e.name)
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { copy(src, e.size, it, e.name) }
                            if (e.mtimeMs > 0) target.setLastModified(e.mtimeMs)
                            files++
                        }
                    } finally {
                        if (solidStream == null) src.close()
                    }
                }
            } finally {
                solidStream?.close()
            }
        }
        sink.onBytes(total, total)
        return files
    }

    /**
     * The decoded contents of [blocks] read back-to-back. Each block is its own compressed stream;
     * when one has delivered its declared size its CRC-32 is checked before moving to the next.
     */
    private class BlockChain(
        private val inp: SegmentedInput,
        private val blocks: List<Block>,
        private val cancelled: () -> Boolean,
    ) : InputStream() {
        private var index = -1
        private var cur: InputStream? = null
        private var inflater: Inflater? = null
        private val crc = CRC32()
        private var got = 0L
        private val one = ByteArray(1)

        private fun open(b: Block): InputStream {
            val raw = inp.range(b.dataPos, b.compSize, cancelled) {}
            return when (b.method) {
                0 -> raw
                // Raw deflate; the trailing zero byte satisfies zlib's nowrap end-of-input quirk.
                1 -> Inflater(true).let { inf ->
                    inflater = inf
                    InflaterInputStream(SequenceInputStream(raw, ByteArrayInputStream(ByteArray(1))), inf, 64 * 1024)
                }
                2 -> BZip2CompressorInputStream(BufferedInputStream(raw, 64 * 1024))
                3 -> AzoDecoder().stream(BufferedInputStream(raw, 64 * 1024))
                4 -> {
                    // ZIP-style LZMA header: 2-byte SDK version, 2-byte props size (5), then the props.
                    val din = DataInputStream(BufferedInputStream(raw, 64 * 1024))
                    val hdr = ByteArray(9)
                    din.readFully(hdr)
                    val dict = (hdr[5].toInt() and 0xFF) or ((hdr[6].toInt() and 0xFF) shl 8) or
                        ((hdr[7].toInt() and 0xFF) shl 16) or ((hdr[8].toInt() and 0xFF) shl 24)
                    LZMAInputStream(din, b.uncompSize, hdr[4], dict).apply { enableRelaxedEndCondition() }
                }
                else -> throw UnsupportedArchiveException("Unknown EGG compression method ${b.method}")
            }
        }

        private fun closeCurrent() {
            runCatching { cur?.close() }
            inflater?.end()
            inflater = null
            cur = null
        }

        /** Moves to the next block that still has data; false at the end of the chain. */
        private fun advance(): Boolean {
            while (true) {
                closeCurrent()
                index++
                if (index >= blocks.size) return false
                crc.reset(); got = 0
                if (blocks[index].uncompSize > 0) { cur = open(blocks[index]); return true }
                if (blocks[index].crc != 0) throw IOException("CRC error in EGG block ${index + 1}")
            }
        }

        override fun read(): Int = if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val s = cur ?: if (advance()) cur!! else return -1
                val blk = blocks[index]
                val want = minOf(len.toLong(), blk.uncompSize - got).toInt()
                val n = s.read(b, off, want)
                if (n < 0) throw IOException("EGG block ${index + 1} ended early (archive damaged?)")
                crc.update(b, off, n)
                got += n
                if (got == blk.uncompSize) {
                    if (crc.value.toInt() != blk.crc) throw IOException("CRC error in EGG block ${index + 1} (archive damaged?)")
                    closeCurrent()
                }
                if (n > 0) return n
            }
        }

        override fun close() = closeCurrent()
    }
}
