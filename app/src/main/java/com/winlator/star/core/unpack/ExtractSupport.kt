package com.winlator.star.core.unpack

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

// Plain-JVM helpers shared by the in-app (Kotlin) extractors — EggArchive and OtaPayload. Nothing
// here touches the Android framework, so those decoders can be exercised on a desktop JVM.

/** Progress surface for an in-app extractor. Every callback fires on the extraction thread. */
interface ExtractSink {
    /** [done] of [total] bytes processed (the unit is the extractor's own: output bytes, or input). */
    fun onBytes(done: Long, total: Long)
    /** Now working on [name] (an entry path or a partition name). */
    fun onFile(name: String)
}

/** Thrown when the job is cancelled mid-extraction; the caller turns it into the CANCELLED state. */
class ExtractCancelled : IOException("cancelled")

/** A known-unsupported input (encrypted entry, incremental OTA, …): [message] is shown to the user as-is. */
class UnsupportedArchiveException(message: String) : IOException(message)

object ExtractPaths {
    /**
     * [destDir]/[name] with the archive-supplied [name] made safe to create: `\` becomes `/`, a drive
     * letter or leading slashes are dropped, `.` and empty components are skipped. Returns null when a
     * `..` component would climb out of [destDir] (a hostile or broken archive) — the caller skips it.
     */
    fun safeChild(destDir: File, name: String): File? {
        var n = name.replace('\\', '/')
        if (n.length >= 2 && n[1] == ':' && n[0].isLetter()) n = n.substring(2)
        val parts = ArrayList<String>()
        for (p in n.split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> return null
                else -> parts += p
            }
        }
        if (parts.isEmpty()) return null
        return File(destDir, parts.joinToString("/"))
    }
}

/**
 * [length] bytes of [channel] starting at [start], read with positional reads (the channel's own
 * position is never touched, so several of these can share one channel). [onRead] reports each chunk
 * as it is consumed; [cancelled] is polled per read.
 */
internal class RangeInputStream(
    private val channel: FileChannel,
    start: Long,
    private val length: Long,
    private val cancelled: () -> Boolean = { false },
    private val onRead: (Int) -> Unit = {},
) : InputStream() {
    private var pos = start
    private val end = start + length
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (cancelled()) throw ExtractCancelled()
        val left = end - pos
        if (left <= 0) return -1
        val want = minOf(len.toLong(), left).toInt()
        val n = channel.read(ByteBuffer.wrap(b, off, want), pos)
        if (n < 0) throw IOException("Unexpected end of file (archive truncated?)")
        pos += n
        onRead(n)
        return n
    }

    override fun available(): Int = minOf(end - pos, Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Several files' byte ranges read back-to-back as one seekable little-endian stream — how a split
 * archive's volumes join up after each volume's own header. A single-volume archive is one segment.
 */
internal class SegmentedInput(private val segments: List<Segment>) : AutoCloseable {
    class Segment(val file: File, val start: Long, val length: Long)

    private val files = arrayOfNulls<RandomAccessFile>(segments.size)
    private val starts = LongArray(segments.size)
    val length: Long

    init {
        var acc = 0L
        for (i in segments.indices) { starts[i] = acc; acc += segments[i].length }
        length = acc
    }

    var position = 0L
        private set

    fun seek(pos: Long) { position = pos }

    fun skip(n: Long) {
        if (n < 0 || position + n > length) throw IOException("Unexpected end of archive")
        position += n
    }

    private fun raf(i: Int): RandomAccessFile = files[i] ?: RandomAccessFile(segments[i].file, "r").also { files[i] = it }

    /** Reads up to [len] bytes at the current position, crossing segment boundaries; -1 at the end. */
    fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= length) return -1
        var i = starts.indexOfLast { it <= position }
        var done = 0
        while (done < len && position < length) {
            while (i < segments.size - 1 && position >= starts[i] + segments[i].length) i++
            val seg = segments[i]
            val inSeg = position - starts[i]
            val n = minOf((len - done).toLong(), seg.length - inSeg).toInt()
            val r = raf(i)
            r.seek(seg.start + inSeg)
            r.readFully(b, off + done, n)
            done += n
            position += n
        }
        return done
    }

    fun readFully(b: ByteArray, off: Int = 0, len: Int = b.size) {
        if (read(b, off, len) != len) throw IOException("Unexpected end of archive")
    }

    private val scratch = ByteArray(8)

    fun u8(): Int { readFully(scratch, 0, 1); return scratch[0].toInt() and 0xFF }
    fun u16(): Int { readFully(scratch, 0, 2); return (scratch[0].toInt() and 0xFF) or ((scratch[1].toInt() and 0xFF) shl 8) }
    fun i32(): Int {
        readFully(scratch, 0, 4)
        return (scratch[0].toInt() and 0xFF) or ((scratch[1].toInt() and 0xFF) shl 8) or
            ((scratch[2].toInt() and 0xFF) shl 16) or ((scratch[3].toInt() and 0xFF) shl 24)
    }
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun i64(): Long = u32() or (u32() shl 32)

    /** [len] bytes from logical [start] as an InputStream (it keeps its own cursor). */
    fun range(start: Long, len: Long, cancelled: () -> Boolean, onRead: (Int) -> Unit): InputStream = object : InputStream() {
        private var pos = start
        private val end = start + len
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, l: Int): Int {
            if (l == 0) return 0
            if (cancelled()) throw ExtractCancelled()
            if (pos >= end) return -1
            val saved = position
            position = pos
            val n = this@SegmentedInput.read(b, off, minOf(l.toLong(), end - pos).toInt())
            position = saved
            if (n <= 0) throw IOException("Unexpected end of archive")
            pos += n
            onRead(n)
            return n
        }
    }

    override fun close() { files.forEach { runCatching { it?.close() } } }
}
