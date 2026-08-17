package com.winlator.star.core.unpack

import android.content.Context
import android.util.Log
import com.winlator.star.core.StringUtils
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Thin wrapper around the bundled 7-Zip standalone console binary (`7zz`, vendored as
 * `jniLibs/arm64-v8a/lib7zz.so` — see NOTICE_7ZIP.txt for version/source/licence).
 *
 * The single extraction engine for the File Manager. Why a bundled native binary rather than a Java
 * archive library:
 * the disc images games arrive on are ISO9660/UDF hybrids, and the target device kernel has NO
 * iso9660/udf filesystem support (`/proc/filesystems` shows only fuse*), so a loop-mount fails with
 * "No such device". Extraction therefore has to happen in userspace, and it has to handle single
 * files well over 4 GB (an 80 GB+ InnoSetup repack inside an 82 GB image). 7-Zip does both, plus RAR
 * (including multi-part), 7z, split volumes (.001/.bin) and zip in one engine.
 *
 * The .so trick: Android extracts native libs to `nativeLibraryDir`, a directory that permits `exec`
 * even under scoped storage / W^X, so we resolve the binary there and exec it directly — no chmod,
 * and crucially NOT from filesDir (exec-from-filesDir is blocked on Android 10+). The vendored binary
 * is the STATICALLY linked `7zzs` build, renamed to `lib7zz.so`: a static aarch64 ELF makes raw
 * syscalls and needs neither glibc nor bionic .so files, so it runs on Android's kernel unchanged.
 */
object SevenZip {
    private const val TAG = "SevenZip"

    /** The vendored 7zz, resolved from the native-lib dir where it is unpacked and exec-able. */
    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "lib7zz.so")

    fun isAvailable(context: Context): Boolean = binary(context).canExecute()

    /**
     * The bundled 7zz is a bionic (NDK) build that dynamically links `libc++_shared.so` — shipped
     * alongside it in the same native-lib dir. A freshly exec'd child does NOT inherit the app's
     * linker namespaces, so we point its loader at that dir via LD_LIBRARY_PATH. Also set TMPDIR to a
     * dir 7-Zip can actually write to.
     *
     * (The previous generic-Linux static build failed on-device with SIGSYS/exit 159: it inherited
     * the app's seccomp-bpf filter and hit a syscall the filter TRAPs. A bionic build integrates with
     * Android's seccomp — the same reason Termux's p7zip runs under an app.)
     */
    private fun newProcess(context: Context, vararg args: String): ProcessBuilder {
        val libDir = context.applicationInfo.nativeLibraryDir
        return ProcessBuilder(binary(context).absolutePath, *args).apply {
            environment()["LD_LIBRARY_PATH"] = libDir
            environment()["TMPDIR"] = context.cacheDir.absolutePath
        }
    }

    // Extensions the Unpack action is offered for. A superset of the plain-Extract set:
    // disc images + RAR + split volumes that commons-compress cannot read.
    private val SUPPORTED = listOf(
        ".iso", ".udf", ".img", ".7z", ".zip", ".rar", ".r00", ".001", ".bin",
        ".cab", ".wim", ".vhd", ".vhdx", ".dmg", ".cso", ".cue", ".tar",
        // compressors + tar shorthands (strict superset of the retired Java extractor): gzip, xz,
        // bzip2, zstd — the bundled 7-Zip build supports all of these (verified via `7zz i`).
        ".gz", ".xz", ".bz2", ".zst", ".tgz", ".txz", ".tbz2", ".tzst",
        // Winlator Component Package = a tar.zst; 7-Zip opens it as zstd.
        ".wcp",
    )

    /** True when [file]'s extension marks it as something the 7-Zip engine can be pointed at. */
    fun isSupported(file: File): Boolean {
        if (file.isDirectory) return false
        val name = file.name.lowercase()
        return SUPPORTED.any { name.endsWith(it) }
    }

    /**
     * True when [file] is an archive/disc-image the engine handles — by EXTENSION, else by a cheap
     * magic-byte sniff so files with an unlisted extension (a `.wcp` before it was listed, a `.bin`
     * that is really a 7z, a renamed archive) still get offered "Unpack Archive…". This is the
     * ⋮-menu visibility gate: it reads only a few header bytes, never runs `7zz l` per entry (that
     * would be far too slow for a directory listing). A false positive is harmless — the Unpack
     * screen's real content pre-flight then shows the friendly "not a recognized archive" message.
     */
    fun looksLikeArchive(file: File): Boolean = isSupported(file) || sniffArchiveMagic(file)

    private fun sig(head: ByteArray, vararg bytes: Int): Boolean {
        if (head.size < bytes.size) return false
        for (i in bytes.indices) if ((head[i].toInt() and 0xFF) != bytes[i]) return false
        return true
    }

    /** Header-only signature check for the common archive/disc-image formats. */
    fun sniffArchiveMagic(file: File): Boolean {
        if (!file.isFile || file.length() < 8L) return false
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(16)
                raf.seek(0)
                val n = raf.read(head)
                if (n >= 4 && (
                        sig(head, 0x28, 0xB5, 0x2F, 0xFD) ||             // zstd
                        sig(head, 0xFD, 0x37, 0x7A, 0x58, 0x5A) ||       // xz
                        sig(head, 0x1F, 0x8B) ||                         // gzip
                        sig(head, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) || // 7z
                        sig(head, 0x50, 0x4B, 0x03, 0x04) ||             // zip
                        sig(head, 0x50, 0x4B, 0x05, 0x06) ||             // zip (empty)
                        sig(head, 0x50, 0x4B, 0x07, 0x08) ||             // zip (spanned)
                        sig(head, 0x52, 0x61, 0x72, 0x21) ||             // rar
                        sig(head, 0x42, 0x5A, 0x68)                      // bzip2
                    )
                ) return true
                // tar: "ustar" at offset 257
                if (file.length() > 262L) {
                    raf.seek(257); val t = ByteArray(5)
                    if (raf.read(t) == 5 && String(t, Charsets.US_ASCII) == "ustar") return true
                }
                // ISO9660: "CD001" at offset 32769
                if (file.length() > 32774L) {
                    raf.seek(32769); val c = ByteArray(5)
                    if (raf.read(c) == 5 && String(c, Charsets.US_ASCII) == "CD001") return true
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // A Setup-1.bin / game-2.bin style InnoSetup data volume.
    private val INNO_BIN = Regex("""(?i)-\d+\.bin$""")

    /**
     * If [file] is part of an InnoSetup repack (the layout games arrive in — a `Setup.exe` next to
     * `Setup-1.bin`, `Setup-2.bin`, …), returns the installer `.exe` that 7-Zip must be pointed at to
     * unpack the game payload; otherwise null. 7-Zip opens the payload behind the `.bin` volumes only
     * when given the `.exe`, never a lone `.bin`.
     *
     * Handles both entry points: the user selecting `Setup.exe`, or selecting a `Setup-N.bin` volume.
     */
    fun resolveInnoTarget(file: File): File? {
        if (file.isDirectory) return null
        val parent = file.parentFile ?: return null
        val siblings = parent.listFiles() ?: return null
        val ext = file.extension.lowercase()
        val hasInnoBin = siblings.any { it.isFile && INNO_BIN.containsMatchIn(it.name) }
        // A GOG/InnoSetup installer is named setup.exe or setup_<game>_<ver>.exe (GOG), or sits next
        // to Setup-*.bin data volumes.
        val looksLikeSetupExe = ext == "exe" && (hasInnoBin || file.name.startsWith("setup", ignoreCase = true))
        return when {
            looksLikeSetupExe -> file
            // A Setup-N.bin was picked directly — resolve the sibling installer .exe instead.
            ext == "bin" && INNO_BIN.containsMatchIn(file.name) ->
                siblings.firstOrNull { it.isFile && it.extension.equals("exe", true) && it.name.startsWith("setup", true) }
                    ?: siblings.firstOrNull { it.isFile && it.extension.equals("exe", true) }
            else -> null
        }
    }

    /** True when [file] is (part of) an InnoSetup installer/repack — see [resolveInnoTarget]. */
    fun isInnoSetup(file: File): Boolean = resolveInnoTarget(file) != null

    /** How an InnoSetup installer must be unpacked. */
    enum class InnoRoute {
        /** Standard InnoSetup / GOG — extract the game files in-app with innoextract. */
        INNOEXTRACT,
        /** FreeArc/ISDone repack — decode the Setup-*.bin FreeArc volumes in-app with unarc. */
        FREEARC_NATIVE,
        /** Can't be unpacked in-app (unarc unavailable, or srep-layered) — run Setup.exe in a container. */
        CONTAINER_ONLY,
    }

    data class InnoClassification(
        val installer: File,
        val route: InnoRoute,
        /** Human compression name from Records.ini, e.g. "FreeArc", when known. */
        val compression: String?,
        /** Declared payload size from Records.ini (already human-formatted), when known. */
        val declaredSize: String?,
        /** For FREEARC_NATIVE: the first Setup-*.bin FreeArc volume to hand to unarc. */
        val freeArcArchive: File? = null,
    )

    /** The lowest-numbered `Setup-N.bin` FreeArc data volume next to [installer] (the unarc entry point). */
    private fun firstFreeArcVolume(installer: File): File? =
        installer.parentFile?.listFiles()
            ?.filter { it.isFile && INNO_BIN.containsMatchIn(it.name) }
            ?.minByOrNull { INNO_BIN.find(it.name)?.groupValues?.get(0)?.filter { c -> c.isDigit() }?.toIntOrNull() ?: Int.MAX_VALUE }

    /**
     * Decides how an InnoSetup [installer] must be unpacked. Standard InnoSetup / GOG installers are
     * read directly by innoextract (7-Zip only sees them as a PE and cannot decompress the game).
     * The exception is a FreeArc/ISDone repack (FitGirl/DODI/…): its game data is FreeArc-compressed,
     * which neither innoextract nor 7-Zip can unpack — the only way in is to run Setup.exe in a Wine
     * container so the repack's own installer decompresses it.
     *
     * Discriminator: a sibling `Records.ini` whose `Type=` starts with "Freearc" → CONTAINER_ONLY
     * (authoritative; its `Type`/`Size` are surfaced for the UI). Everything else → INNOEXTRACT.
     *
     * Reads a sibling file, so call it off the main thread.
     */
    fun classifyInno(context: Context, installer: File): InnoClassification {
        val records = readRecordsIni(installer.parentFile)
        val rawType = records?.first
        val freeArc = rawType?.trim()?.startsWith("freearc", ignoreCase = true) == true
        // FreeArc → decode the Setup-*.bin volumes natively with unarc when it's available; otherwise
        // fall back to the container installer. (A srep-layered repack that unarc can't decode fails at
        // runtime, and the UI turns that into the honest container/PC message — no proactive probe.)
        val firstBin = if (freeArc) firstFreeArcVolume(installer) else null
        val route = when {
            !freeArc -> InnoRoute.INNOEXTRACT
            firstBin != null && Unarc.isAvailable(context) -> InnoRoute.FREEARC_NATIVE
            else -> InnoRoute.CONTAINER_ONLY
        }
        return InnoClassification(
            installer = installer,
            route = route,
            compression = rawType?.let { prettyCompression(it) },
            declaredSize = records?.second?.let { prettySize(it) },
            freeArcArchive = firstBin,
        )
    }

    /** Fast "can 7-Zip even open this?" pre-flight via `7zz l`. */
    fun sevenZipCanOpen(context: Context, file: File): Boolean {
        val bin = binary(context)
        if (!bin.canExecute()) return false
        return try {
            val proc = newProcess(context, "l", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            code == 0 && !out.contains("Cannot open the file as archive", ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "canOpen pre-flight failed for ${file.name}", e)
            false
        }
    }

    /** Reads the first `Type=` and `Size=` from a repack's `Records.ini`, or null if absent. */
    private fun readRecordsIni(dir: File?): Pair<String?, String?>? {
        val ini = dir?.listFiles()?.firstOrNull { it.isFile && it.name.equals("Records.ini", ignoreCase = true) }
            ?: return null
        var type: String? = null
        var size: String? = null
        runCatching {
            ini.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = line.trim()
                    if (type == null && t.startsWith("Type=", ignoreCase = true)) type = t.substringAfter('=').trim()
                    if (size == null && t.startsWith("Size=", ignoreCase = true)) size = t.substringAfter('=').trim()
                    if (type != null && size != null) break
                }
            }
        }
        return type to size
    }

    private fun prettyCompression(rawType: String): String = when {
        rawType.startsWith("freearc", ignoreCase = true) -> "FreeArc"
        else -> rawType.substringBefore('_')
    }

    /** Records.ini Size is sometimes a raw byte count, sometimes already "81GB" — normalise for display. */
    private fun prettySize(raw: String): String =
        raw.toLongOrNull()?.let { StringUtils.formatBytes(it) } ?: raw

    /** A friendly default folder name to extract [archive] into — its name minus one extension. */
    fun suggestedTargetName(archive: File): String {
        val name = archive.name
        val lower = name.lowercase()
        // Handle multi-part markers so "Game.part1.rar" -> "Game", "disc.001" -> "disc".
        for (suffix in listOf(".part1.rar", ".part01.rar", ".tar.gz", ".tar.xz", ".tar.bz2")) {
            if (lower.endsWith(suffix)) return name.dropLast(suffix.length).ifBlank { name }
        }
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    data class ArchiveInfo(val type: String?, val entryCount: Int)

    /**
     * Lists [archive] with `7zz l -slt` and reports its declared Type plus a rough entry count.
     * Metadata-only, so it is cheap even for an 80 GB image. Returns null if 7zz can't open it.
     */
    fun list(context: Context, archive: File): ArchiveInfo? {
        val bin = binary(context)
        if (!bin.canExecute()) return null
        return try {
            val proc = newProcess(context, "l", "-slt", archive.absolutePath)
                .redirectErrorStream(true)
                .start()
            var type: String? = null
            var pathCount = 0
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    when {
                        type == null && line.startsWith("Type = ") -> type = line.substring(7).trim()
                        line.startsWith("Path = ") -> pathCount++
                    }
                }
            }
            proc.waitFor()
            // The first "Path =" block is the archive itself; the rest are entries.
            ArchiveInfo(type, (pathCount - 1).coerceAtLeast(0))
        } catch (e: Exception) {
            Log.e(TAG, "list failed for ${archive.name}", e)
            null
        }
    }

    /** Progress + logging surface for [extract]. Every callback fires off the extraction thread. */
    interface Listener {
        /** [percent] 0..100; [currentFile] is the entry 7zz is on, when it names one. */
        fun onProgress(percent: Int, currentFile: String?)
        /** A processed-file line (from `-bb1`); used to count extracted files. */
        fun onFile(name: String)
    }

    /** Outcome of an extraction. [exitCode] is 7-Zip's own code (0 ok, 1 warnings, ≥2 error). */
    data class Result(val exitCode: Int, val stderrTail: String)

    // Matches the "NN%" the instant its '%' byte arrives (digits immediately preceding the percent).
    private val TRAILING_PCT = Regex("""(\d{1,3})%$""")

    /**
     * Extracts [archive] into [destDir] via `7zz x`, streaming progress to [listener].
     *
     * @param mmt        thread count for `-mmt=` (see [UnpackManager.mmtFor]).
     * @param bufferBytes size of the pipe read buffer — the Read-buffer knob. It only affects how we
     *                    drain 7zz's stdout pipe (the sole stream we own here); 7-Zip does its own
     *                    file IO. Honest naming: it is a FUSE/pipe throughput knob, not a speed dial.
     * @param onProcess  handed the live [Process] so the caller (service) can destroy it to cancel.
     */
    fun extract(
        context: Context,
        archive: File,
        destDir: File,
        mmt: Int,
        bufferBytes: Int,
        listener: Listener,
        onProcess: (Process) -> Unit,
    ): Result {
        destDir.mkdirs()
        val proc = newProcess(
            context, "x", archive.absolutePath,
            "-o${destDir.absolutePath}",
            "-y",        // assume Yes (overwrite) — the screen owns the destination folder
            "-bsp1",     // progress -> stdout so we can parse it
            "-bb1",      // log each processed file
            "-mmt=$mmt",
        ).start()
        onProcess(proc)

        // stderr collected on its own thread for the failure tail.
        val stderr = StringBuilder()
        val errThread = Thread {
            runCatching {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderr) {
                        stderr.append(line).append('\n')
                        // Keep only a tail so a pathological archive can't balloon memory.
                        if (stderr.length > 8192) stderr.delete(0, stderr.length - 8192)
                    }
                }
            }
        }.also { it.start() }

        // Device-verified stream shape (7-Zip 24.08, -bsp1 -bb1): the percentage is redrawn IN PLACE
        // with BACKSPACES, not carriage returns/newlines — e.g. "  0%\b\b\b\b 50%\b\b\b\b100%…". So a
        // flush-on-newline parser would erase every intermediate percent before it saw it and jump
        // 0→100. Instead we parse the percentage inline the instant a '%' byte arrives (its digits are
        // whatever precedes it in the buffer), and use \n-delimited segments only for the "- path"
        // processed-file lines (-bb1). Backspaces edit the buffer; \r/\n flush it.
        val seg = ByteArrayOutputStream(256)
        val buf = ByteArray(bufferBytes.coerceAtLeast(64 * 1024))
        val input = proc.inputStream
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                when (val b = buf[i].toInt()) {
                    '\r'.code, '\n'.code -> flushSegment(seg, listener)
                    '\b'.code -> { val a = seg.toByteArray(); seg.reset(); if (a.isNotEmpty()) seg.write(a, 0, a.size - 1) }
                    else -> {
                        seg.write(b)
                        if (b == '%'.code) {
                            // Trailing "NN%" just completed — fire it live.
                            val tail = seg.toByteArray().toString(Charsets.UTF_8)
                            TRAILING_PCT.find(tail)?.groupValues?.get(1)?.toIntOrNull()?.let {
                                listener.onProgress(it.coerceIn(0, 100), null)
                            }
                        }
                    }
                }
            }
        }
        flushSegment(seg, listener)

        val exit = proc.waitFor()
        runCatching { errThread.join(500) }
        // Push a final 100% so a clean finish never leaves the bar short of the end.
        if (exit <= 1) listener.onProgress(100, null)
        return Result(exit, synchronized(stderr) { stderr.toString().trim() })
    }

    /**
     * A `.wcp`/`.tzst`/`.tar.gz`/… is a tar INSIDE a compressor, so `7zz x` yields a single inner
     * `.tar`. If [destDir] holds exactly that — one lone `.tar` file — extract it in place and delete
     * it, so one Unpack action lands the real files rather than a `.tar`. Best-effort: any failure
     * leaves the `.tar` in place (still a valid result). Reuses the full [extract] pump for progress.
     */
    fun unwrapSingleTar(
        context: Context,
        destDir: File,
        mmt: Int,
        bufferBytes: Int,
        listener: Listener,
        onProcess: (Process) -> Unit,
    ): Boolean {
        val files = destDir.listFiles()?.toList() ?: return false
        val tar = files.singleOrNull { it.isFile && it.extension.equals("tar", ignoreCase = true) } ?: return false
        if (files.size != 1) return false   // only unwrap when the tar is the sole product
        val r = extract(context, tar, destDir, mmt, bufferBytes, listener, onProcess)
        return if (r.exitCode <= 1) { tar.delete(); true } else false
    }

    /** A \n/\r-delimited segment: used for the -bb1 "- path" processed-file lines (not percentages). */
    private fun flushSegment(seg: ByteArrayOutputStream, listener: Listener) {
        if (seg.size() == 0) return
        val text = seg.toByteArray().toString(Charsets.UTF_8)
        seg.reset()
        if (text.isBlank()) return

        val fileName = when {
            text.contains(" - ") -> text.substringAfterLast(" - ").trim().ifBlank { null }
            text.startsWith("- ") -> text.substring(2).trim().ifBlank { null }
            else -> null
        }
        if (fileName != null) listener.onFile(fileName)
    }
}
