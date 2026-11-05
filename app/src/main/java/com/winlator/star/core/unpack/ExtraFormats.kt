package com.winlator.star.core.unpack

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The archive formats BFE unpacks without 7-Zip — the ones ZArchiver / MT Manager open that the bundled
 * 7-Zip can't: lzip, lzop, LZ4, ECM CD images, ALZip `.alz` / `.egg`, ACE 1.x, Amiga LZX and Android
 * OTA `payload.bin`. They all run through the one service engine [ENGINE] ("extra").
 *
 * Two kinds of engine sit behind it:
 *  - small single-purpose native tools, vendored like 7zz as bionic `lib<name>.so` executables
 *    (built by `.github/workflows/build-extract-tools.yml`; licences in licenses/NOTICE_<tool>.txt):
 *    lunzip, lzop, lz4, unecm, unalz, unace and amiga-lzx's `lzx`;
 *  - in-app Kotlin decoders: [EggArchive] (with [AzoDecoder]) and [OtaPayload].
 *
 * The single-stream compressors (lzip / lzop / LZ4) are fed the file on stdin, so progress is exact
 * (bytes handed over vs file size) and the output goes straight to a file; a `.tar` that comes out is
 * then unpacked by 7-Zip like any `.tar.gz` (the service's inner-tar step). Formats are recognised by
 * their header, never by extension alone.
 */
object ExtraFormats {
    private const val TAG = "ExtraFormats"

    /** The service engine name for every format here. */
    const val ENGINE = "extra"

    enum class Format(val label: String, val tool: String?, internal val extensions: List<String>) {
        LZIP("lzip", "liblunzip.so", listOf(".tlz", ".lz")),
        LZOP("lzop", "liblzop.so", listOf(".tzo", ".lzo")),
        LZ4("LZ4", "liblz4.so", listOf(".lz4")),
        ECM("ECM disc image", "libunecm.so", listOf(".ecm")),
        ALZ("ALZip (.alz)", "libunalz.so", listOf(".alz")),
        ACE("ACE", "libunace.so", listOf(".ace")),
        LZX("Amiga LZX", "liblzx.so", listOf(".lzx")),
        EGG("ALZip (.egg)", null, listOf(".egg")),
        PAYLOAD("Android OTA payload", null, emptyList()),
    }

    /** The bundled tools' file names — what the service's Pause / Resume look for among its children. */
    val TOOL_BINARIES: List<String> = Format.entries.mapNotNull { it.tool }

    private val STREAM = setOf(Format.LZIP, Format.LZOP, Format.LZ4)

    fun binary(context: Context, format: Format): File? =
        format.tool?.let { File(context.applicationInfo.nativeLibraryDir, it) }

    /** True when [name] carries one of these formats' extensions (payload.bin is covered by `.bin`). */
    fun hasExtension(name: String): Boolean {
        val n = name.lowercase()
        return Format.entries.any { f -> f.extensions.any { n.endsWith(it) } }
    }

    private fun sig(head: ByteArray, n: Int, off: Int, vararg bytes: Int): Boolean {
        if (n < off + bytes.size) return false
        for (i in bytes.indices) if ((head[off + i].toInt() and 0xFF) != bytes[i]) return false
        return true
    }

    /** Which of these formats [file] is, judged by its header (16 bytes), or null. */
    fun detect(file: File): Format? {
        if (!file.isFile || file.length() < 4) return null
        val head = ByteArray(16)
        val n = runCatching { RandomAccessFile(file, "r").use { it.read(head) } }.getOrDefault(-1)
        return if (n < 4) null else detect(head, n)
    }

    /** [detect] on bytes already read — shared with [SevenZip.sniffArchiveMagic]. */
    fun detect(head: ByteArray, n: Int): Format? = when {
        sig(head, n, 0, 0x4C, 0x5A, 0x49, 0x50) -> Format.LZIP                                  // "LZIP"
        sig(head, n, 0, 0x89, 0x4C, 0x5A, 0x4F, 0x00, 0x0D, 0x0A, 0x1A, 0x0A) -> Format.LZOP    // "\x89LZO\0\r\n\x1a\n"
        sig(head, n, 0, 0x04, 0x22, 0x4D, 0x18) -> Format.LZ4                                   // LZ4 frame
        sig(head, n, 0, 0x02, 0x21, 0x4C, 0x18) -> Format.LZ4                                   // LZ4 legacy
        n >= 4 && (head[0].toInt() and 0xF0) == 0x50 && sig(head, n, 1, 0x2A, 0x4D, 0x18) -> Format.LZ4  // skippable frame
        sig(head, n, 0, 0x45, 0x43, 0x4D, 0x00) -> Format.ECM                                   // "ECM\0"
        sig(head, n, 0, 0x41, 0x4C, 0x5A, 0x01) -> Format.ALZ                                   // "ALZ\x01"
        sig(head, n, 0, 0x45, 0x47, 0x47, 0x41) -> Format.EGG                                   // "EGGA"
        sig(head, n, 0, 0x43, 0x72, 0x41, 0x55) -> Format.PAYLOAD                               // "CrAU"
        sig(head, n, 7, 0x2A, 0x2A, 0x41, 0x43, 0x45, 0x2A, 0x2A) -> Format.ACE                 // "**ACE**" at 7
        sig(head, n, 0, 0x4C, 0x5A, 0x58) -> Format.LZX                                         // "LZX"
        else -> null
    }

    class Partition(val name: String, val size: Long, val incremental: Boolean)

    /** What the Unpack screen needs to know before offering Extract. */
    class Probe(
        val format: Format,
        /** The Source card's type line. */
        val typeLabel: String,
        /** Why it can't be unpacked (shown to the user as-is), or null when it can. */
        val problem: String?,
        /** Uncompressed total when known, else 0. */
        val totalBytes: Long = 0,
        val entryCount: Int = 0,
        /** OTA payloads: every partition, incremental ones included (they can't be picked). */
        val partitions: List<Partition> = emptyList(),
        /** One line naming the engine, shown under the type. */
        val engineNote: String? = null,
    )

    /** Reads headers / listings only. Null when [file] isn't one of these formats. Call off the main thread. */
    fun probe(context: Context, file: File): Probe? {
        val format = detect(file) ?: return null
        binary(context, format)?.let {
            if (!it.canExecute()) return Probe(format, format.label, "The ${format.tool} engine isn't available in this build.")
        }
        return try {
            when (format) {
                Format.LZIP, Format.LZOP, Format.LZ4 -> Probe(
                    format, "${format.label} compressed file", null, file.length(), 1,
                    engineNote = "${engineName(format)} will decompress it to ${outputName(file)}" +
                        (if (outputName(file).endsWith(".tar")) " and unpack that tar." else "."),
                )
                Format.ECM -> Probe(
                    format, format.label, null, file.length(), 1,
                    engineNote = "unecm will rebuild ${outputName(file)} (error-correction data restored).",
                )
                Format.ALZ -> probeAlz(context, file)
                Format.ACE -> probeAce(file)
                Format.LZX -> probeLzx(context, file)
                Format.EGG -> {
                    val a = EggArchive.open(file)
                    Probe(
                        format,
                        buildString {
                            append("${format.label} • ${a.fileCount} file${if (a.fileCount == 1) "" else "s"}")
                            if (a.methods.isNotEmpty()) append(" • ${a.methods.joinToString("/")}")
                            if (a.volumes.size > 1) append(" • ${a.volumes.size} volumes")
                            if (a.solid) append(" • solid")
                        },
                        a.problem, a.totalSize, a.fileCount,
                        engineNote = "Bannerlator unpacks EGG archives itself (no external engine).",
                    )
                }
                Format.PAYLOAD -> {
                    val p = OtaPayload.open(file)
                    val parts = p.partitions.map { Partition(it.name, it.imageSize(p.blockSize), it.incremental) }
                    Probe(
                        format,
                        "${format.label} • ${parts.size} partition${if (parts.size == 1) "" else "s"}",
                        if (p.incremental) {
                            "This is an incremental OTA: it patches the images already on the phone, so it " +
                                "can't be extracted without them. Use a full OTA (or factory image) instead."
                        } else null,
                        parts.filter { !it.incremental }.sumOf { it.size }, parts.size, parts,
                        engineNote = "Each partition is written out as <name>.img.",
                    )
                }
            }
        } catch (e: IOException) {
            Probe(format, format.label, e.message ?: "This ${format.label} file couldn't be read.")
        }
    }

    private fun engineName(format: Format) = when (format) {
        Format.LZIP -> "lunzip"
        Format.LZOP -> "lzop"
        Format.LZ4 -> "lz4"
        else -> format.tool ?: "built-in"
    }

    /** The decompressed file's name: one compressor extension off, `.tlz` / `.tzo` back to `.tar`. */
    fun outputName(file: File): String {
        val name = file.name
        val lower = name.lowercase()
        if ((lower.endsWith(".tlz") || lower.endsWith(".tzo")) && name.length > 4) return name.dropLast(4) + ".tar"
        for (ext in listOf(".lz", ".lzo", ".lz4", ".ecm")) {
            if (lower.endsWith(ext) && name.length > ext.length) return name.dropLast(ext.length)
        }
        return "$name.out"
    }

    private fun process(context: Context, format: Format, args: List<String>): ProcessBuilder {
        val libDir = context.applicationInfo.nativeLibraryDir
        return ProcessBuilder(listOf(binary(context, format)!!.absolutePath) + args).apply {
            environment()["LD_LIBRARY_PATH"] = libDir
            environment()["TMPDIR"] = context.cacheDir.absolutePath
        }
    }

    /** Runs a tool to completion and returns (exit code, merged output). For short listings only. */
    private fun capture(context: Context, format: Format, args: List<String>): Pair<Int, String> {
        val p = process(context, format, args).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun unsafe(name: String): Boolean {
        val n = name.replace('\\', '/')
        return n.startsWith("/") || n.split('/').any { it == ".." }
    }

    // unalz -l: "A___        66431        64459 12/29/23 13:11:58  name" … "    2524195      2485618 Total 6 files"
    private val ALZ_ROW = Regex("""^\S{4}\s+(\d+)\s+\d+\s+\S+\s+\S+\s+(.+)$""")
    private val ALZ_TOTAL = Regex("""^\s*(\d+)\s+\d+\s+Total\s+(\d+)\s+file""")

    private fun probeAlz(context: Context, file: File): Probe {
        val (_, out) = capture(context, Format.ALZ, listOf("-utf8", "-l", file.absolutePath))
        var total = 0L
        var count = 0
        var bad = false
        for (line in out.lineSequence()) {
            ALZ_TOTAL.find(line)?.let { total = it.groupValues[1].toLong(); count = it.groupValues[2].toInt() }
                ?: ALZ_ROW.find(line)?.let { if (unsafe(it.groupValues[2].trim())) bad = true }
        }
        // "It's corrupted file." alone is only a warning: unalz still recovers the intact entries.
        val problem = when {
            out.contains("file open error") ->
                "This ALZ archive couldn't be opened: " + out.lineSequence().firstOrNull { it.startsWith("err code") }.orEmpty().trim()
            bad -> "This archive contains paths that point outside the folder it would be extracted to, so Bannerlator won't unpack it."
            else -> null
        }
        return Probe(
            Format.ALZ, "${Format.ALZ.label} • $count file${if (count == 1) "" else "s"}", problem, total, count,
            engineNote = "unalz will unpack it (Korean CP949 names converted to UTF-8).",
        )
    }

    // lzx l: "      size       crc32  name" then "    128000  574ee617  readme.txt"
    private val LZX_ROW = Regex("""^\s*(\d+)\s+[0-9a-f]{8}\s+(.+)$""")

    private fun probeLzx(context: Context, file: File): Probe {
        val (code, out) = capture(context, Format.LZX, listOf("l", file.absolutePath))
        var total = 0L
        var count = 0
        for (line in out.lineSequence()) LZX_ROW.find(line)?.let { total += it.groupValues[1].toLong(); count++ }
        val problem = if (code != 0) {
            "This LZX archive couldn't be read: " + out.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().removePrefix("lzx: ")
        } else null
        return Probe(
            Format.LZX, "${Format.LZX.label} • $count file${if (count == 1) "" else "s"}", problem, total, count,
            engineNote = "amiga-lzx will unpack it.",
        )
    }

    /**
     * Walks an ACE archive's headers (the format is simple: crc16, size16, then the header; file blocks
     * are followed by their packed data). Decides up front what unace 1.2b can't do — ACE 2.0 compression
     * or headers, passwords — instead of letting it fail halfway.
     */
    private fun probeAce(file: File): Probe {
        var total = 0L
        var count = 0
        var v20 = false
        var password = false
        var bad = false
        RandomAccessFile(file, "r").use { r ->
            val len = r.length()
            var pos = 0L
            val fixed = ByteArray(4)
            while (pos + 4 <= len) {
                r.seek(pos)
                r.readFully(fixed)
                val size = (fixed[2].toInt() and 0xFF) or ((fixed[3].toInt() and 0xFF) shl 8)
                if (size < 3 || pos + 4 + size > len) break
                val h = ByteArray(size)
                r.readFully(h)
                val type = h[0].toInt() and 0xFF
                val flags = (h[1].toInt() and 0xFF) or ((h[2].toInt() and 0xFF) shl 8)
                fun u32(o: Int) = if (o + 4 > size) 0L else
                    (h[o].toLong() and 0xFF) or ((h[o + 1].toLong() and 0xFF) shl 8) or
                        ((h[o + 2].toLong() and 0xFF) shl 16) or ((h[o + 3].toLong() and 0xFF) shl 24)
                var add = if (flags and 0x0001 != 0) u32(3) else 0L
                when (type) {
                    1 -> {      // FILE32: PSIZE (=ADDSIZE), SIZE, FTIME, ATTR, CRC32, TYPE, QUAL, PARM, RES, FNAME_SIZE, FNAME
                        if (size >= 31) {
                            if ((h[23].toInt() and 0xFF) >= 2) v20 = true
                            if (flags and 0x4000 != 0) password = true
                            val nameLen = (h[29].toInt() and 0xFF) or ((h[30].toInt() and 0xFF) shl 8)
                            val name = String(h, 31, minOf(nameLen, size - 31), Charsets.ISO_8859_1)
                            if (unsafe(name)) bad = true
                            total += u32(7)
                            count++
                        }
                    }
                    3 -> { v20 = true; add = 0; break }   // FILE64: an ACE 2.0 header unace 1.2b doesn't know
                    0, 2 -> Unit                           // MAIN, RECOVERY32
                    else -> { v20 = true; break }          // RECOVERY64 / unknown: ACE 2.0 territory
                }
                pos += 4 + size + add
            }
        }
        val problem = when {
            v20 -> "This archive uses ACE 2.0 compression. The ACE engine (unace 1.2b) only unpacks ACE 1.x; " +
                "the newer method has no decoder that can be included."
            password -> "This ACE archive is password-protected; unace can't decrypt it."
            bad -> "This archive contains paths that point outside the folder it would be extracted to, so Bannerlator won't unpack it."
            else -> null
        }
        return Probe(
            Format.ACE, "${Format.ACE.label} • $count file${if (count == 1) "" else "s"}", problem, total, count,
            engineNote = "unace 1.2b will unpack it.",
        )
    }

    /** Outcome: [exitCode] 0 = success (anything else an error with [tail] as the message), [files] written. */
    data class Result(val exitCode: Int, val tail: String, val files: Int)

    /**
     * Unpacks [file] into [destDir]. [parts] limits an OTA payload to those partitions (null = all
     * extractable ones). [total] is the probe's uncompressed total, used by the tools whose progress
     * comes from watching the destination fill up. [cancelled] is polled by the in-app decoders (the
     * service makes it wait while paused); native tools are stopped through the [Process] handed to
     * [onProcess].
     */
    fun extract(
        context: Context,
        file: File,
        destDir: File,
        parts: Set<String>?,
        total: Long,
        sink: ExtractSink,
        onProcess: (Process) -> Unit,
        cancelled: () -> Boolean,
    ): Result {
        val format = detect(file) ?: return Result(2, "${file.name} isn't a recognised archive.", 0)
        destDir.mkdirs()
        return try {
            when (format) {
                in STREAM -> runStream(context, format, file, destDir, sink, onProcess, cancelled)
                Format.ECM -> runEcm(context, file, destDir, sink, onProcess)
                Format.ALZ, Format.ACE, Format.LZX -> runMulti(context, format, file, destDir, total, sink, onProcess)
                Format.EGG -> {
                    val n = EggArchive.extract(EggArchive.open(file), destDir, sink, cancelled)
                    Result(0, "", n)
                }
                Format.PAYLOAD -> {
                    val n = OtaPayload.extract(OtaPayload.open(file), destDir, parts, sink, cancelled)
                    Result(0, "", n)
                }
                else -> Result(2, "Unsupported format", 0)
            }
        } catch (c: ExtractCancelled) {
            Result(2, "Cancelled", 0)
        } catch (e: IOException) {
            Log.w(TAG, "${format.name} extract failed for ${file.name}", e)
            Result(2, e.message ?: e.javaClass.simpleName, 0)
        }
    }

    /** lunzip / lzop / lz4: the file goes in on stdin, the decompressed data straight to a file. */
    private fun runStream(
        context: Context, format: Format, file: File, destDir: File,
        sink: ExtractSink, onProcess: (Process) -> Unit, cancelled: () -> Boolean,
    ): Result {
        val out = File(destDir, outputName(file))
        sink.onFile(out.name)
        val args = if (format == Format.LZIP) listOf("-c") else listOf("-d", "-c")
        val proc = process(context, format, args).redirectOutput(out).start()
        onProcess(proc)
        val err = StringBuilder()
        val errThread = SevenZip.collectStderr(proc.errorStream, err)
        val size = file.length().coerceAtLeast(1L)
        var fed = 0L
        try {
            FileInputStream(file).use { input ->
                proc.outputStream.use { stdin ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        if (cancelled()) { proc.destroy(); break }
                        val n = input.read(buf)
                        if (n < 0) break
                        stdin.write(buf, 0, n)
                        fed += n
                        sink.onBytes(fed, size)
                    }
                }
            }
        } catch (e: IOException) {
            // The tool stopped reading (bad data, or killed by Cancel): its exit code and stderr tell why.
        }
        val exit = proc.waitFor()
        runCatching { errThread.join(500) }
        val tail = synchronized(err) { err.toString().trim() }
        return if (exit == 0) Result(0, tail, 1)
        else Result(2, tail.ifBlank { "${engineName(format)} exit code $exit" }, 0)
    }

    /** unecm: file to file (it seeks), percent from its "Decode(NN%)" stderr; then fetch a sibling .cue. */
    private fun runEcm(context: Context, file: File, destDir: File, sink: ExtractSink, onProcess: (Process) -> Unit): Result {
        val out = File(destDir, outputName(file))
        out.delete()   // unecm refuses to overwrite; the destination is ours
        sink.onFile(out.name)
        val proc = process(context, Format.ECM, listOf(file.absolutePath, out.absolutePath)).start()
        onProcess(proc)
        proc.outputStream.close()
        val log = StringBuilder()
        val logThread = SevenZip.collectStderr(proc.inputStream, log)   // unecm reports to stdout
        val size = file.length().coerceAtLeast(1L)
        SevenZip.pumpProgress(proc.errorStream, 64 * 1024, object : SevenZip.Listener {
            override fun onProgress(percent: Int, currentFile: String?) = sink.onBytes(size * percent / 100, size)
            override fun onFile(name: String) = Unit
        })
        val exit = proc.waitFor()
        runCatching { logThread.join(500) }
        val tail = synchronized(log) { log.toString().trim() }
        if (exit != 0) return Result(2, tail.ifBlank { "unecm exit code $exit" }, 0)
        // A CD image's .cue usually sits next to the .ecm; bring it along so the pair stays usable.
        val stem = out.name.substringBeforeLast('.')
        file.parentFile?.listFiles()
            ?.firstOrNull { it.isFile && it.name.equals("$stem.cue", ignoreCase = true) }
            ?.let { cue -> File(destDir, cue.name).takeIf { !it.exists() }?.let { runCatching { cue.copyTo(it) } } }
        sink.onBytes(size, size)
        return Result(0, tail, 1)
    }

    private val UNALZ_FILE = Regex("""unalziiiing : (.+) \(\d+bytes\)""")
    private val LZX_FILE = Regex("""^\s*- (.+) \(\d+ bytes\)$""")

    /**
     * unalz / unace / lzx: whole-archive extractors. None reports an overall percent, so a poller
     * measures what this job adds to [destDir] against the probe's [total], like the unarc route.
     */
    private fun runMulti(
        context: Context, format: Format, file: File, destDir: File, total: Long,
        sink: ExtractSink, onProcess: (Process) -> Unit,
    ): Result {
        val pb = when (format) {
            Format.ALZ -> process(context, format, listOf("-utf8", "-d", destDir.absolutePath, file.absolutePath))
            // unace extracts into its working directory; -y answers every question (overwrite, volumes).
            Format.ACE -> process(context, format, listOf("x", "-y", file.absolutePath)).directory(destDir)
            else -> process(context, format, listOf("x", file.absolutePath, destDir.absolutePath))
        }.redirectErrorStream(true)
        val base = dirStats(destDir)
        val proc = pb.start()
        onProcess(proc)
        proc.outputStream.close()   // unalz would otherwise wait for a password on stdin

        val tot = total.coerceAtLeast(1L)
        val polling = java.util.concurrent.atomic.AtomicBoolean(true)
        val poller = Thread {
            while (polling.get()) {
                runCatching { Thread.sleep(1000) }
                val written = (dirStats(destDir).second - base.second).coerceAtLeast(0L)
                sink.onBytes(minOf(written, tot), tot)
            }
        }.also { it.name = "extra-poller"; it.start() }

        val tail = StringBuilder()
        runCatching {
            proc.inputStream.bufferedReader().forEachLine { raw ->
                for (line in raw.split('\r')) {
                    val t = line.trimEnd()
                    if (t.isBlank()) continue
                    FullUnpackLog.line(t)
                    tail.append(t).append('\n')
                    if (tail.length > 4096) tail.delete(0, tail.length - 4096)
                    when (format) {
                        Format.ALZ -> UNALZ_FILE.find(t)?.let { sink.onFile(it.groupValues[1]) }
                        Format.LZX -> LZX_FILE.find(t)?.let { sink.onFile(it.groupValues[1]) }
                        else -> if (!t.startsWith(" ") && !t.startsWith("UNACE") && !t.startsWith("Processing archive")) sink.onFile(t)
                    }
                }
            }
        }
        val exit = proc.waitFor()
        polling.set(false)
        runCatching { poller.join(2000) }
        val out = tail.toString().trim()
        val added = (dirStats(destDir).first - base.first).coerceAtLeast(0)
        // unalz exits 0 whatever happens; its output is the only verdict.
        val ok = when (format) {
            Format.ALZ -> exit == 0 && out.contains("done.") && !out.contains("failed.") &&
                !out.contains("err code(") && !out.contains("file open error") && !out.contains("Enter Password")
            else -> exit == 0
        }
        if (ok) { sink.onBytes(tot, tot); return Result(0, out, added) }
        val why = when {
            format == Format.ALZ && out.contains("Enter Password") -> "This ALZ archive is password-protected; Bannerlator can't decrypt it."
            format == Format.ACE && exit == 9 -> "CRC errors — the ACE archive is damaged.\n" + out.takeLast(600)
            else -> out.ifBlank { "${format.tool} exit code $exit" }
        }
        return Result(2, why, added)
    }

    /** (file count, total bytes) under [dir]. */
    private fun dirStats(dir: File): Pair<Int, Long> = runCatching {
        var c = 0
        var b = 0L
        dir.walkTopDown().forEach { if (it.isFile) { c++; b += it.length() } }
        c to b
    }.getOrDefault(0 to 0L)
}
