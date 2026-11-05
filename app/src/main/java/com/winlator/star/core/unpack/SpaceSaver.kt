package com.winlator.star.core.unpack

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * "Unpack and delete the download as it goes": runs BFE's own engine `libbfecut.so`
 * (native/bfecut). It unpacks an archive from its end and shrinks the archive as files land
 * (ZIP, tar, 7z, RAR, FreeArc, split sets), or reads a .gz/.xz stream from the front and frees
 * what it has read. A journal in `<dest>/.bfe-inplace/` makes every step resumable; giving up
 * leaves a normal archive with what is left (except streams, which can only be continued).
 * The engine prints one JSON object per line; this wrapper turns them into a plan and progress.
 */
object SpaceSaver {
    private const val TAG = "SpaceSaver"
    const val ENGINE = "inplace"
    const val BINARY = "libbfecut.so"
    /** Never fill the storage below this (the engine keeps it free the whole way). */
    const val KEEP_FREE = 2_000_000_000L

    fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, BINARY)
    fun isAvailable(context: Context) = binary(context).canExecute()

    data class Plan(
        val supported: Boolean,
        val reason: String?,
        val format: String?,
        val stream: Boolean,
        val pieces: Int,
        val archiveBytes: Long,
        /** -1 when the engine can't know before a full check (some .gz files). */
        val totalOut: Long,
        val free: Long,
        val fitsNormal: Boolean,
        val fits: Boolean,
        /** Bytes still missing for the space-saving unpack (0 = fits). */
        val short: Long,
        val sameStorage: Boolean,
        val resume: Boolean,
        val done: Int,
    ) {
        /** A space-saving unpack can start (or continue) here. */
        val usable: Boolean get() = supported && sameStorage && (fits || resume)
        /** More than one piece: the download really shrinks along the way. */
        val shrinks: Boolean get() = stream || pieces > 1
        /** ZIP/tar/RAR wait for low space before freeing anything; "delete as it goes" changes that. */
        val eagerMatters: Boolean get() = !stream && pieces > 1 && format in setOf("zip", "tar", "rar")
    }

    private fun base(context: Context, vararg args: String): ProcessBuilder {
        val lib = context.applicationInfo.nativeLibraryDir
        val cmd = mutableListOf(binary(context).absolutePath)
        cmd += args
        cmd += listOf("--7z", File(lib, "lib7zz.so").absolutePath, "--unarc", File(lib, "libunarc.so").absolutePath)
        return ProcessBuilder(cmd).apply {
            environment()["LD_LIBRARY_PATH"] = lib
            environment()["TMPDIR"] = context.cacheDir.absolutePath
            redirectErrorStream(true)
        }
    }

    /** Reads the archive's index and the free space; changes nothing. Null when the engine is missing. */
    fun plan(context: Context, archive: File, dest: File): Plan? {
        if (!isAvailable(context)) return null
        return try {
            val p = base(context, "plan", archive.absolutePath, "--dest", dest.absolutePath, "--keep-free", KEEP_FREE.toString()).start()
            val line = p.inputStream.bufferedReader().readLines().lastOrNull { it.startsWith("{") }
            p.waitFor()
            val j = JSONObject(line ?: return null)
            Plan(
                supported = j.optBoolean("supported", false),
                reason = j.optString("reason").takeIf { it.isNotBlank() },
                format = j.optString("format").takeIf { it.isNotBlank() },
                stream = j.optBoolean("stream", false),
                pieces = j.optInt("pieces", 0),
                archiveBytes = j.optLong("archive", archive.length()),
                totalOut = if (j.isNull("total_out")) -1L else j.optLong("total_out", -1L),
                free = j.optLong("free", 0L),
                fitsNormal = j.optBoolean("fits_normal", false),
                fits = j.optBoolean("fits", false),
                short = j.optLong("short", 0L),
                sameStorage = j.optBoolean("same_storage", false),
                resume = j.optBoolean("resume", false),
                done = j.optInt("done", 0),
            )
        } catch (e: Exception) {
            Log.e(TAG, "plan failed for ${archive.name}", e)
            null
        }
    }

    data class Progress(val out: Long, val totalOut: Long, val freed: Long, val file: String?, val done: Int, val total: Int)

    data class Result(val exitCode: Int, val errorCode: String?, val message: String?, val tail: String)

    fun run(
        context: Context,
        archive: File,
        dest: File,
        verify: Boolean,
        /** Free each piece as soon as it's out, even while there is room (ZIP/tar/RAR; 7z/FreeArc always do). */
        eager: Boolean,
        onProgress: (Progress) -> Unit,
        onPhase: (String) -> Unit,
        onProcess: (Process) -> Unit,
    ): Result {
        dest.mkdirs()
        val args = mutableListOf("run", archive.absolutePath, "--dest", dest.absolutePath, "--keep-free", KEEP_FREE.toString())
        if (!verify) args += "--no-verify"
        if (eager) args += "--eager"
        val p = base(context, *args.toTypedArray()).start()
        onProcess(p)
        val tail = StringBuilder()
        var errCode: String? = null
        var errMsg: String? = null
        runCatching {
            p.inputStream.bufferedReader().forEachLine { line ->
                tail.append(line).append('\n')
                if (tail.length > 8192) tail.delete(0, tail.length - 8192)
                FullUnpackLog.line(line)
                val j = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                when (j.optString("ev")) {
                    "progress" -> onProgress(
                        Progress(
                            out = j.optLong("out"), totalOut = j.optLong("total_out"), freed = j.optLong("freed"),
                            file = j.optString("file").takeIf { it.isNotBlank() }, done = j.optInt("done"), total = j.optInt("total"),
                        )
                    )
                    "verify" -> onPhase("verify")
                    "error" -> { errCode = j.optString("code"); errMsg = j.optString("msg") }
                }
            }
        }
        val exit = p.waitFor()
        Log.i(TAG, "bfecut exit=$exit for ${archive.name} ($errCode)")
        return Result(exit, errCode, errMsg, tail.toString().trim())
    }

    /** Gives up: the archive gets a correct index for what is left; unpacked files stay. */
    fun abandon(context: Context, archive: File, dest: File): Result {
        val p = base(context, "abandon", archive.absolutePath, "--dest", dest.absolutePath).start()
        val out = p.inputStream.bufferedReader().readText()
        val exit = p.waitFor()
        val err = out.lines().lastOrNull { it.contains("\"ev\":\"error\"") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        return Result(exit, err?.optString("code"), err?.optString("msg"), out.trim())
    }

    /** Plain words for the engine's error codes. */
    fun explain(code: String?, msg: String?): String = when (code) {
        "NO_SPACE" -> "Storage ran too low to go on safely. Free some space and tap Continue; nothing was lost."
        "PAUSED" -> "Stopped. Tap Continue to pick up where it left off."
        "DAMAGED" -> "The download is damaged: ${msg ?: ""}. Nothing more was deleted."
        "PASSWORD" -> "Password-protected archives can't be unpacked this way. Use the normal unpack."
        "UNSUPPORTED" -> msg ?: "This archive can't be unpacked this way. Use the normal unpack."
        "UNSAFE" -> "The archive has unsafe paths: ${msg ?: ""}."
        else -> msg ?: "Something went wrong; the progress is saved."
    }
}
