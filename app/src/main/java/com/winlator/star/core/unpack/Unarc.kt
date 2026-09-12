package com.winlator.star.core.unpack

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Wrapper around the bundled FreeArc `unarc` decompressor — the engine for FreeArc/ISDone game
 * repacks (FitGirl/DODI/…). Their game data lives in `Setup-1.bin`, `Setup-2.bin`, … FreeArc volumes
 * next to the InnoSetup `Setup.exe`; 7-Zip and innoextract can't read FreeArc, but `unarc` decodes it
 * (lzma/tornado/rep/grzip/4x4/exe/delta), so the game can be unpacked IN-APP instead of only by
 * running the installer in a Wine container.
 *
 * Vendored as a bionic (Android NDK r29) build — `libunarc.so` — with the .so-name trick; exec'd from
 * `nativeLibraryDir` with LD_LIBRARY_PATH so the loader finds `libc++_shared.so` (already bundled).
 * Bionic is required to survive the app seccomp filter (the exit-159 lesson). This build is
 * decompress-only and has NO srep codec: a srep-layered repack fails at runtime, which the UI turns
 * into an honest "install on a PC / in a container" message.
 *
 * Progress: `unarc` prints per-entry "Extracting …" lines but block-buffers stdout when it isn't a
 * TTY, so the lines arrive in bursts (and not at all during a multi-minute big block). The service
 * therefore drives the progress bar by POLLING the destination size against the total from [list];
 * this wrapper's job is to run the process and surface the error tail.
 */
object Unarc {
    private const val TAG = "Unarc"

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libunarc.so")

    fun isAvailable(context: Context): Boolean = binary(context).canExecute()

    /** Totals from `unarc l`, used to drive the size-based progress bar. */
    data class Listing(val totalFiles: Int, val totalBytes: Long)

    private val FOOTER = Regex("""(\d+)\s+files,\s+(\d+)\s+bytes""")

    /** Lists [archive] (`unarc l`) and returns its total file count + uncompressed size, or null. */
    fun list(context: Context, archive: File): Listing? {
        val bin = binary(context)
        if (!bin.canExecute()) return null
        return try {
            val proc = process(context, "l", "-ld-", archive.absolutePath).redirectErrorStream(true).start()
            var listing: Listing? = null
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    FOOTER.find(line)?.let {
                        listing = Listing(it.groupValues[1].toInt(), it.groupValues[2].toLongOrNull() ?: 0L)
                    }
                }
            }
            proc.waitFor()
            listing
        } catch (e: Exception) {
            Log.e(TAG, "list failed for ${archive.name}", e)
            null
        }
    }

    fun interface Listener {
        /** The entry `unarc` last reported (best-effort — stdout is block-buffered). */
        fun onFile(name: String)
    }

    data class Result(val exitCode: Int, val stderrTail: String)

    /**
     * Extracts [archive] (the first `Setup-*.bin` FreeArc volume) into [destDir] with:
     *   `unarc x -o+ -ld- -dp<destDir> <archive>`
     * The **`-ld-` (no memory limit) flag is required** — the default decompression memory cap
     * corrupts the huge (96 MB window) lzma/rep chains these repacks use.
     */
    fun extract(
        context: Context,
        archive: File,
        destDir: File,
        listener: Listener,
        onProcess: (Process) -> Unit,
    ): Result {
        destDir.mkdirs()
        val proc = process(
            context, "x", "-o+", "-ld-", "-dp${destDir.absolutePath}", archive.absolutePath,
        ).redirectErrorStream(true).start()
        onProcess(proc)

        val tail = StringBuilder()
        runCatching {
            proc.inputStream.bufferedReader().forEachLine { line ->
                val t = line.trim()
                if (t.isNotEmpty()) {
                    tail.append(t).append('\n')
                    if (tail.length > 4096) tail.delete(0, tail.length - 4096)
                    // "Extracting <path>" / "Extracting <path> (N bytes)"
                    if (t.startsWith("Extracting ")) {
                        val name = t.removePrefix("Extracting ").substringBeforeLast(" (").trim()
                        if (name.isNotEmpty() && !name.endsWith("/")) listener.onFile(name)
                    }
                }
            }
        }
        val exit = proc.waitFor()
        Log.i(TAG, "unarc exit=$exit for ${archive.name}")
        return Result(exit, tail.toString().trim())
    }

    private fun process(context: Context, vararg args: String): ProcessBuilder {
        val libDir = context.applicationInfo.nativeLibraryDir
        return ProcessBuilder(binary(context).absolutePath, *args).apply {
            environment()["LD_LIBRARY_PATH"] = libDir
            environment()["TMPDIR"] = context.cacheDir.absolutePath
        }
    }
}
