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
 * decompress-only and has NO srep codec.
 *
 * Archives with external layers (precomp, …) go to [LAYERS_BINARY] instead: the same FreeArc 0.67 unarc
 * rebuilt by build-repack-tools.yml with worker threads given a 16 MB stack - the bundled build
 * overflows Android's default thread stack in its external-layer code (about 1 run in 15 crashed).
 * It gets a per-job arc.ini ([RepackLayers.arcIni], `-cfg`) and a temp folder (`-w`) next to the
 * destination, because external layers are written out to files while they are decoded.
 *
 * Progress: `unarc` prints per-entry "Extracting …" lines but block-buffers stdout when it isn't a
 * TTY, so the lines arrive in bursts (and not at all during a multi-minute big block). The service
 * therefore drives the progress bar by POLLING the destination size against the total from [list];
 * this wrapper's job is to run the process and surface the error tail.
 */
object Unarc {
    private const val TAG = "Unarc"

    /** UnpackService engine ids: built-in layers only / with external layers. */
    const val ENGINE = "unarc"
    const val ENGINE_LAYERS = "unarc-layers"

    const val LAYERS_BINARY = "libunarcx.so"
    const val HELPER_BINARY = "libbfeext.so"

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libunarc.so")

    fun isAvailable(context: Context): Boolean = binary(context).canExecute()

    private fun layersBinary(context: Context) = File(context.applicationInfo.nativeLibraryDir, LAYERS_BINARY)
    private fun helper(context: Context) = File(context.applicationInfo.nativeLibraryDir, HELPER_BINARY)

    /** True when the external-layer engine (patched unarc + libbfeext + precomp) is bundled. */
    fun layersAvailable(context: Context): Boolean = layersBinary(context).canExecute() && helper(context).canExecute()

    /** How [extract] runs for an archive with external layers. */
    data class Layers(val config: File, val tempDir: File)

    /**
     * Writes the arc.ini for [archive]'s external layers into the cache and picks the temp folder
     * (beside [destDir], on the same storage). Returns null when the archive has no external layers.
     */
    fun prepareLayers(context: Context, archive: File, destDir: File): Layers? {
        val info = FreeArcInfo.read(archive) ?: return null
        val names = RepackLayers.analyze(info).external.map { it.name }
        if (names.isEmpty()) return null
        val dir = File(context.cacheDir, "unarc-cfg").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val cfg = File(dir, "arc.ini")
        cfg.writeText(RepackLayers.arcIni(names, helper(context).absolutePath))
        val tmp = File(destDir.absoluteFile.parentFile ?: destDir, ".bfe-unarc-tmp")
        return Layers(cfg, tmp)
    }

    /** Totals from `unarc l`, used to drive the size-based progress bar. */
    data class Listing(val totalFiles: Int, val totalBytes: Long)

    private val FOOTER = Regex("""(\d+)\s+files,\s+(\d+)\s+bytes""")

    /** Lists [archive] (`unarc l`) and returns its total file count + uncompressed size, or null. */
    fun list(context: Context, archive: File): Listing? {
        val bin = binary(context)
        if (!bin.canExecute()) return null
        // The table of contents is read in-app first: it needs no process and works for any engine.
        FreeArcInfo.read(archive)?.takeIf { !it.headersEncrypted && it.totalFiles > 0 }?.let {
            return Listing(it.totalFiles, it.totalBytes)
        }
        return try {
            val proc = process(context, bin, "l", "-ld-", archive.absolutePath).redirectErrorStream(true).start()
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
        layers: Layers? = null,
    ): Result {
        destDir.mkdirs()
        val args = mutableListOf("x", "-o+", "-ld-")
        val bin = if (layers != null) {
            layers.tempDir.deleteRecursively()
            layers.tempDir.mkdirs()
            args += "-cfg${layers.config.absolutePath}"
            args += "-w${layers.tempDir.absolutePath}"
            layersBinary(context)
        } else binary(context)
        args += "-dp${destDir.absolutePath}"
        args += archive.absolutePath
        FullUnpackLog.line("[BL] ${bin.name} ${args.joinToString(" ")}")
        layers?.let { runCatching { FullUnpackLog.line("[BL] arc.ini:\n" + it.config.readText().trimEnd()) } }
        val proc = process(context, bin, *args.toTypedArray()).redirectErrorStream(true).start()
        onProcess(proc)

        val tail = StringBuilder()
        runCatching {
            proc.inputStream.bufferedReader().forEachLine { line ->
                FullUnpackLog.line(line)
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
        Log.i(TAG, "${bin.name} exit=$exit for ${archive.name}")
        // A failed external layer can leave its temp files behind; they can be as big as the game.
        layers?.tempDir?.deleteRecursively()
        // libbfeext's own lines say exactly which layer stopped and why: put them last, where the
        // error card (which shows the end of the tail) can't cut them off.
        val helperLines = RepackLayers.helperMessages(tail.toString())
        val shown = if (exit != 0 && helperLines.isNotEmpty())
            tail.lineSequence().filterNot { it.trim().startsWith("BFE-EXT:") }.joinToString("\n").trim() +
                "\n" + helperLines.joinToString("\n")
        else tail.toString().trim()
        return Result(exit, shown)
    }

    private fun process(context: Context, bin: File, vararg args: String): ProcessBuilder {
        val libDir = context.applicationInfo.nativeLibraryDir
        return ProcessBuilder(bin.absolutePath, *args).apply {
            environment()["LD_LIBRARY_PATH"] = libDir
            environment()["TMPDIR"] = context.cacheDir.absolutePath
        }
    }
}
