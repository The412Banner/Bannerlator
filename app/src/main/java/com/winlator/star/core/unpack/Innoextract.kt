package com.winlator.star.core.unpack

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Wrapper around the bundled `innoextract` (dscharrer/innoextract, zlib licence) — the engine for
 * STANDARD InnoSetup / GOG installers, which 7-Zip can only see as a PE (it cannot decompress the
 * Inno-packed game data). innoextract reads the Inno archive format directly.
 *
 * Vendored as a bionic (Android NDK) build with its dependency closure (boost, liblzma, libiconv,
 * libz, libbz2, libc++_shared) alongside it in the native-lib dir — see NOTICE_INNOEXTRACT.txt. Like
 * lib7zz.so it is exec'd from `nativeLibraryDir` with LD_LIBRARY_PATH pointed there so the loader
 * finds the bundled libraries, and being bionic it survives the app's seccomp filter (unlike a
 * generic-Linux build, which SIGSYS-dies — the exit-159 lesson).
 *
 * Device-verified: extracts a real GOG installer (Stronghold Crusader HD, Inno 5.6.2) — 4095 files /
 * 868 MB — from inside the exec sandbox.
 */
object Innoextract {
    private const val TAG = "Innoextract"

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libinnoextract.so")

    fun isAvailable(context: Context): Boolean = binary(context).canExecute()

    fun interface Listener {
        fun onProgress(percent: Int)
    }

    data class Result(val exitCode: Int, val stderrTail: String)

    // innoextract's --progress bar is CR-redrawn and looks like " 42% [====>   ] path"; the percent
    // is complete the instant its '%' byte arrives (digits immediately precede it).
    private val TRAILING_PCT = Regex("""(\d{1,3})%$""")

    /**
     * Extracts [installer] into [destDir] via `innoextract --extract --progress`.
     * @param onProcess handed the live [Process] so the caller can destroy it to cancel.
     */
    fun extract(
        context: Context,
        installer: File,
        destDir: File,
        listener: Listener,
        onProcess: (Process) -> Unit,
    ): Result {
        destDir.mkdirs()
        val libDir = context.applicationInfo.nativeLibraryDir
        val proc = ProcessBuilder(
            binary(context).absolutePath,
            "--extract", "--progress", "--color=0",
            "-d", destDir.absolutePath,
            installer.absolutePath,
        ).apply {
            redirectErrorStream(true)   // progress + messages come mixed; parse % and keep a tail
            environment()["LD_LIBRARY_PATH"] = libDir
            environment()["TMPDIR"] = context.cacheDir.absolutePath
        }.start()
        onProcess(proc)

        val tail = StringBuilder()
        val seg = ByteArrayOutputStream(256)
        val buf = ByteArray(64 * 1024)
        val input = proc.inputStream
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                when (val b = buf[i].toInt()) {
                    '\r'.code, '\n'.code -> {
                        if (seg.size() > 0) {
                            val line = seg.toByteArray().toString(Charsets.UTF_8)
                            seg.reset()
                            if (line.isNotBlank()) {
                                tail.append(line).append('\n')
                                if (tail.length > 4096) tail.delete(0, tail.length - 4096)
                            }
                        }
                    }
                    else -> {
                        seg.write(b)
                        if (b == '%'.code) {
                            val t = seg.toByteArray().toString(Charsets.UTF_8)
                            TRAILING_PCT.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let {
                                listener.onProgress(it.coerceIn(0, 100))
                            }
                        }
                    }
                }
            }
        }
        val exit = proc.waitFor()
        if (exit == 0) listener.onProgress(100)
        Log.i(TAG, "innoextract exit=$exit for ${installer.name}")
        return Result(exit, tail.toString().trim())
    }
}
