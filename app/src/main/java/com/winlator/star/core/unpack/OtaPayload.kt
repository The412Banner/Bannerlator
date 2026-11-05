package com.winlator.star.core.unpack

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Extractor for Android A/B OTA `payload.bin` files (update_engine's "CrAU" format — the payload
 * inside a full OTA zip), writing each partition out as `<name>.img`, like MT Manager's payload
 * dumper. Pure Kotlin: the DeltaArchiveManifest protobuf is hand-parsed for the few fields needed
 * (field numbers from AOSP's system/update_engine update_metadata.proto, Apache-2.0), so no protobuf
 * runtime is bundled.
 *
 * Full OTAs only — REPLACE, REPLACE_BZ, REPLACE_XZ, ZERO and DISCARD. An incremental OTA
 * patches the images already on the phone (SOURCE_COPY, SOURCE_BSDIFF, PUFFDIFF, BROTLI_BSDIFF,
 * ZUCCHINI, LZ4DIFF_*), so it can't be rebuilt without those source images; such partitions are
 * marked [Partition.incremental] and refused. Each operation's data is checked against the SHA-256
 * recorded in the manifest.
 */
object OtaPayload {
    private const val OP_REPLACE = 0
    private const val OP_REPLACE_BZ = 1
    private const val OP_ZERO = 6
    private const val OP_DISCARD = 7
    private const val OP_REPLACE_XZ = 8
    private val FULL_OPS = setOf(OP_REPLACE, OP_REPLACE_BZ, OP_ZERO, OP_DISCARD, OP_REPLACE_XZ)

    /** Guard against a garbage header making us allocate gigabytes for the manifest. */
    private const val MAX_MANIFEST = 64L * 1024 * 1024

    class Extent(val startBlock: Long, val numBlocks: Long)

    class Operation(
        val type: Int,
        val dataOffset: Long,
        val dataLength: Long,
        val dst: List<Extent>,
        val sha256: ByteArray?,
    )

    class Partition(val name: String, private val declaredSize: Long, val operations: List<Operation>, private val hasSourceInfo: Boolean) {
        /** Needs the device's current image (an incremental OTA) — can't be extracted on its own. */
        val incremental: Boolean get() = hasSourceInfo || operations.any { it.type !in FULL_OPS }
        /** Payload bytes this partition's operations read. */
        val dataBytes: Long get() = operations.sumOf { it.dataLength }
        fun imageSize(blockSize: Int): Long =
            declaredSize.takeIf { it > 0 }
                ?: operations.flatMap { it.dst }.filter { it.startBlock != -1L }
                .maxOfOrNull { (it.startBlock + it.numBlocks) * blockSize } ?: 0L
    }

    class Payload(val file: File, val blockSize: Int, val dataStart: Long, val partitions: List<Partition>) {
        val incremental: Boolean get() = partitions.isNotEmpty() && partitions.all { it.incremental }
    }

    /** True when [file] starts with the "CrAU" payload signature. */
    fun isPayload(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { r ->
            val b = ByteArray(4); r.readFully(b); String(b, Charsets.US_ASCII) == "CrAU"
        }
    }.getOrDefault(false)

    /** Reads the header and manifest of [file] (a few hundred KB at most; the data isn't touched). */
    fun open(file: File): Payload {
        RandomAccessFile(file, "r").use { r ->
            val magic = ByteArray(4)
            r.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "CrAU") throw IOException("Not an Android OTA payload")
            val version = r.readLong()
            if (version != 2L) {
                throw UnsupportedArchiveException(
                    "This payload uses update_engine format version $version; only version 2 can be read.",
                )
            }
            val manifestSize = r.readLong()
            val sigSize = r.readInt().toLong() and 0xFFFFFFFFL
            if (manifestSize !in 1..MAX_MANIFEST) throw IOException("Corrupt OTA payload (manifest size $manifestSize)")
            val manifest = ByteArray(manifestSize.toInt())
            r.readFully(manifest)
            val dataStart = 4L + 8 + 8 + 4 + manifestSize + sigSize
            var blockSize = 4096
            val parts = ArrayList<Partition>()
            val pb = Pb(manifest, 0, manifest.size)
            while (pb.hasMore()) {
                val (field, wire) = pb.tag()
                when {
                    field == 3 && wire == 0 -> blockSize = pb.varint().toInt()
                    field == 13 && wire == 2 -> parts += parsePartition(pb.sub())
                    else -> pb.skip(wire)
                }
            }
            if (blockSize <= 0 || blockSize > 1 shl 20) throw IOException("Corrupt OTA payload (block size $blockSize)")
            if (parts.isEmpty()) throw IOException("This OTA payload lists no partitions")
            return Payload(file, blockSize, dataStart, parts)
        }
    }

    private fun parsePartition(pb: Pb): Partition {
        var name = ""
        var size = 0L
        var hasOld = false
        val ops = ArrayList<Operation>()
        while (pb.hasMore()) {
            val (field, wire) = pb.tag()
            when {
                field == 1 && wire == 2 -> name = String(pb.bytes(), Charsets.UTF_8)
                field == 6 && wire == 2 -> { hasOld = true; pb.skip(wire) }       // old_partition_info
                field == 7 && wire == 2 -> {                                     // new_partition_info
                    val info = pb.sub()
                    while (info.hasMore()) {
                        val (f, w) = info.tag()
                        if (f == 1 && w == 0) size = info.varint() else info.skip(w)
                    }
                }
                field == 8 && wire == 2 -> ops += parseOperation(pb.sub())
                else -> pb.skip(wire)
            }
        }
        return Partition(name, size, ops, hasOld)
    }

    private fun parseOperation(pb: Pb): Operation {
        var type = -1
        var offset = 0L
        var length = 0L
        var sha: ByteArray? = null
        val dst = ArrayList<Extent>()
        while (pb.hasMore()) {
            val (field, wire) = pb.tag()
            when {
                field == 1 && wire == 0 -> type = pb.varint().toInt()
                field == 2 && wire == 0 -> offset = pb.varint()
                field == 3 && wire == 0 -> length = pb.varint()
                field == 6 && wire == 2 -> {
                    val e = pb.sub()
                    var start = 0L
                    var num = 0L
                    while (e.hasMore()) {
                        val (f, w) = e.tag()
                        when {
                            f == 1 && w == 0 -> start = e.varint()
                            f == 2 && w == 0 -> num = e.varint()
                            else -> e.skip(w)
                        }
                    }
                    dst += Extent(start, num)
                }
                field == 8 && wire == 2 -> sha = pb.bytes()
                else -> pb.skip(wire)
            }
        }
        return Operation(type, offset, length, dst, sha)
    }

    /**
     * Writes `<partition>.img` into [destDir] for every partition in [names] (all extractable ones
     * when null). Progress is payload bytes read against the selected partitions' data total.
     * Returns the number of images written.
     */
    fun extract(payload: Payload, destDir: File, names: Set<String>?, sink: ExtractSink, cancelled: () -> Boolean): Int {
        val chosen = payload.partitions.filter { names == null || it.name in names }
        val blocked = chosen.filter { it.incremental }
        if (chosen.isEmpty()) throw IOException("None of the chosen partitions are in this payload")
        if (blocked.size == chosen.size) {
            throw UnsupportedArchiveException(
                "This is an incremental OTA: it patches the images already on the phone, so it can't be " +
                    "extracted without them. Use a full OTA (or factory image) instead.",
            )
        }
        destDir.mkdirs()
        val todo = chosen - blocked.toSet()
        val total = todo.sumOf { it.dataBytes }.coerceAtLeast(1L)
        var done = 0L
        var written = 0
        val buf = ByteArray(1 shl 20)
        FileInputStream(payload.file).channel.use { src ->
            for (part in todo) {
                if (cancelled()) throw ExtractCancelled()
                sink.onFile(part.name)
                val safe = part.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "partition" }
                val outFile = File(destDir, "$safe.img")
                RandomAccessFile(outFile, "rw").use { out ->
                    // A fresh, sparse, all-zero file of the final size: ZERO / DISCARD then cost nothing.
                    out.setLength(0)
                    out.setLength(part.imageSize(payload.blockSize))
                    val ch = out.channel
                    for ((i, op) in part.operations.withIndex()) {
                        if (op.type == OP_ZERO || op.type == OP_DISCARD) continue
                        val raw = RangeInputStream(src, payload.dataStart + op.dataOffset, op.dataLength, cancelled) { n ->
                            done += n
                            sink.onBytes(done, total)
                        }
                        val md = op.sha256?.let { MessageDigest.getInstance("SHA-256") }
                        val hashed: InputStream = if (md != null) DigestInputStream(raw, md) else raw
                        val data: InputStream = when (op.type) {
                            OP_REPLACE -> hashed
                            OP_REPLACE_BZ -> BZip2CompressorInputStream(BufferedInputStream(hashed, 1 shl 16))
                            OP_REPLACE_XZ -> XZInputStream(BufferedInputStream(hashed, 1 shl 16))
                            else -> throw UnsupportedArchiveException("Unsupported OTA operation ${op.type} in ${part.name}")
                        }
                        data.use { writeExtents(it, ch, op.dst, payload.blockSize, buf, part.name) }
                        // Hash the whole blob even if the decoder stopped short of its last bytes.
                        while (hashed.read(buf) >= 0) Unit
                        if (md != null && !md.digest().contentEquals(op.sha256)) {
                            throw IOException("Data check failed in ${part.name} (operation ${i + 1}); the payload is damaged")
                        }
                    }
                }
                written++
            }
        }
        sink.onBytes(total, total)
        return written
    }

    /** Lays [data] out over the destination [extents] in order; any surplus output is discarded. */
    private fun writeExtents(data: InputStream, ch: FileChannel, extents: List<Extent>, blockSize: Int, buf: ByteArray, name: String) {
        for (e in extents) {
            // A start block of kuint64max marks a sparse hole: its share of the data is skipped, not written.
            val hole = e.startBlock == -1L
            var pos = e.startBlock * blockSize
            var left = e.numBlocks * blockSize
            while (left > 0) {
                val n = data.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) return   // short REPLACE data: the rest of the extent stays zero
                if (!hole) {
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    while (bb.hasRemaining()) pos += ch.write(bb, pos)
                }
                left -= n
            }
        }
        while (data.read(buf) >= 0) Unit
    }

    /** Minimal protobuf wire-format reader over a byte range — just enough for the manifest. */
    private class Pb(private val b: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end || shift > 63) throw IOException("Corrupt OTA payload manifest")
                val x = b[pos++].toInt()
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
        }

        fun tag(): Pair<Int, Int> {
            val t = varint()
            return (t ushr 3).toInt() to (t and 7).toInt()
        }

        private fun lengthDelimited(): Int {
            val len = varint()
            if (len < 0 || len > end - pos) throw IOException("Corrupt OTA payload manifest")
            return len.toInt()
        }

        fun sub(): Pb {
            val len = lengthDelimited()
            return Pb(b, pos, pos + len).also { pos += len }
        }

        fun bytes(): ByteArray {
            val len = lengthDelimited()
            return b.copyOfRange(pos, pos + len).also { pos += len }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                // Read the length first: `pos += lengthDelimited()` would add to the pre-call pos.
                2 -> { val n = lengthDelimited(); pos += n }
                5 -> pos += 4
                else -> throw IOException("Corrupt OTA payload manifest (wire type $wire)")
            }
            if (pos > end) throw IOException("Corrupt OTA payload manifest")
        }
    }
}
