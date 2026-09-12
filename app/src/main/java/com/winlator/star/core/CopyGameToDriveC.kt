package com.winlator.star.core

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import java.io.File
import java.io.IOException

/**
 * Copies a game's folder off internal/SD (FUSE-backed shared storage) onto the container's C:
 * drive (`.wine/drive_c/Games/<name>`) and repoints the shortcut to `C:\Games\<name>\…`, so the
 * game runs from native app-private ext4/f2fs instead of FUSE.
 *
 * Device-proven rationale: games that stream assets/intro-movies from shared storage stall at
 * ~0.5 MB/s over FUSE and hang; the same files on C: read at native speed and the game runs.
 *
 * This object is the PURE (no-Compose) half — path maths, sizing, the free-space check, the copy
 * engine and the shortcut repoint. The Compose coordinator (confirm-source, progress, prompts)
 * lives in ShortcutsScreen and drives these on a background thread. Exe<->drive conversion goes
 * through [WinePath] exactly like the importer, so a file placed under drive_c resolves to `C:\…`.
 */
object CopyGameToDriveC {
    private const val TAG = "CopyGameToDriveC"

    /** Sub-dir under drive_c that copied games land in: `C:\Games\<name>\…`. */
    const val GAMES_SUBDIR = "Games"

    /** Headroom kept free on top of the copy size so the copy can't fill the data partition. */
    const val FREE_SPACE_MARGIN = 250L * 1024 * 1024 // 250 MB

    /** 1 MB copy buffer — big enough that FUSE reads aren't syscall-bound. */
    private const val COPY_BUFFER = 1 shl 20

    /**
     * Path tails (case-insensitive, `/`-separated) that are a game's BINARY sub-folder, never its
     * root. When the exe's parent matches one of these we walk UP to the plausible game root so the
     * WHOLE game copies, not just the Win64 binaries. Ordered longest-first so a two-segment tail
     * (`binaries/win64`) is preferred over its one-segment suffix (`win64`), and each tail's segment
     * count is how many levels we climb.
     */
    private val BINARY_SUBFOLDER_TAILS = listOf(
        "game/binaries", "binaries/win64", "binaries/win32", "builds/release",
        "bin", "binaries", "builds", "build", "release", "x64", "x86", "win64", "win32",
    ).sortedByDescending { it.count { c -> c == '/' } }

    /**
     * The exe path split into its executable and any trailing launch args, its resolved Android
     * file, and its Wine drive letter. [argsSuffix] keeps its leading space so it re-appends
     * verbatim on repoint; [exeAndroid] is null when the drive letter isn't mapped.
     */
    data class ExeInfo(
        val exeWin: String,
        val argsSuffix: String,
        val exeAndroid: File?,
        val driveLetter: String,
    ) {
        val onDriveC: Boolean get() = driveLetter == "C"
    }

    /** Parse [shortcut]'s exec into its exe (drive path + Android file) and any launch args. */
    fun parse(shortcut: Shortcut): ExeInfo {
        val raw = shortcut.path.trim().trim('"')
        val (exeWin, argsSuffix) = splitExeAndArgs(raw)
        val letter = if (exeWin.length >= 2 && exeWin[1] == ':') exeWin.take(1).uppercase() else ""
        val android = runCatching { WinePath.resolveAndroidPath(shortcut.container, exeWin) }.getOrNull()
        return ExeInfo(exeWin, argsSuffix, android, letter)
    }

    /**
     * Splits a shortcut path into (exe, trailing-args) the same way the launcher does
     * ([com.winlator.star.XServerDisplayActivity.getWineStartCommand]): args are whatever follows
     * the first space AFTER the filename's extension dot. The returned args suffix keeps its leading
     * space (or is empty), so `exe + argsSuffix == input`.
     */
    private fun splitExeAndArgs(path: String): Pair<String, String> {
        val lastSep = maxOf(path.lastIndexOf('\\'), path.lastIndexOf('/'))
        val filename = if (lastSep >= 0) path.substring(lastSep + 1) else path
        val dot = filename.lastIndexOf('.')
        val space = if (dot != -1) filename.indexOf(' ', dot) else -1
        if (space == -1) return path to ""
        val cut = (if (lastSep >= 0) lastSep + 1 else 0) + space
        return path.substring(0, cut) to path.substring(cut)
    }

    /**
     * A human label for where [shortcut]'s exe currently runs from — Drive C:, internal shared
     * storage, or SD card / USB — for the Storage row in the shortcut editor.
     */
    fun storageLabel(shortcut: Shortcut): String {
        val info = parse(shortcut)
        if (info.onDriveC) return "Drive C: — app storage (native, fast)"
        val root = info.exeAndroid?.absolutePath?.let { WinePath.storageVolumeRootOf(it) }
        val where = when {
            root == null -> if (info.driveLetter.isEmpty()) "Unknown location" else "${info.driveLetter}: drive"
            root.startsWith("/storage/emulated") -> "Internal shared storage"
            else -> "SD card / USB storage"
        }
        return if (info.driveLetter.isEmpty()) where else "$where (${info.driveLetter}:)"
    }

    /**
     * Best-guess game root to copy: the exe's parent, unless that parent is a known binary
     * sub-folder ([BINARY_SUBFOLDER_TAILS]) — in which case walk up to the folder above the binaries
     * so the whole game comes along. The user can still override this in the confirm-source step.
     */
    fun defaultSourceRoot(exe: File): File {
        val parent = exe.parentFile ?: return exe
        val parentPath = parent.absolutePath.replace('\\', '/').trimEnd('/').lowercase()
        val tail = BINARY_SUBFOLDER_TAILS.firstOrNull { parentPath == it || parentPath.endsWith("/$it") }
            ?: return parent
        val hops = tail.count { it == '/' } + 1
        var root = parent
        repeat(hops) { root = root.parentFile ?: return root }
        return root
    }

    /**
     * True when [exe] is [folder] itself or lives underneath it. The repoint is only correct when
     * the chosen source folder is an ancestor of the exe, so the confirm step blocks on this.
     */
    fun isAncestor(folder: File?, exe: File?): Boolean {
        if (folder == null || exe == null) return false
        val f = folder.absolutePath.trimEnd('/')
        val e = exe.absolutePath
        return e == f || e.startsWith("$f/")
    }

    /** Total size of [dir]'s file tree in bytes. Honours [isCancelled] so a huge tree can bail. */
    fun folderSize(dir: File, isCancelled: () -> Boolean = { false }): Long {
        var total = 0L
        val stack = ArrayDeque<File>().apply { addLast(dir) }
        while (stack.isNotEmpty()) {
            if (isCancelled()) return total
            val kids = stack.removeLast().listFiles() ?: continue
            for (k in kids) if (k.isDirectory) stack.addLast(k) else total += k.length()
        }
        return total
    }

    /** Free bytes on the data partition drive_c lives on. Falls back to MAX_VALUE if StatFs fails. */
    fun freeBytes(context: Context): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (e: Exception) {
        Log.w(TAG, "StatFs on filesDir failed", e)
        Long.MAX_VALUE
    }

    /** True when [sizeBytes] plus [FREE_SPACE_MARGIN] fits in the data partition's free space. */
    fun hasRoomFor(context: Context, sizeBytes: Long): Boolean =
        freeBytes(context) >= sizeBytes + FREE_SPACE_MARGIN

    /** Destination root for a game folder named [sourceFolderName]: `drive_c/Games/<name>`. */
    fun destRootFor(container: Container, sourceFolderName: String): File =
        File(File(container.getRootDir(), ".wine/drive_c"), "$GAMES_SUBDIR/$sourceFolderName")

    /** `<base>`, or `<base> (2)`, `<base> (3)`… — the first name that doesn't already exist. */
    fun autoRenamedDest(base: File): File {
        if (!base.exists()) return base
        var i = 2
        while (true) {
            val candidate = File(base.parentFile, "${base.name} ($i)")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    /** Thrown by [copyTree] when [copyTree]'s isCancelled callback goes true. */
    class CancelledException : IOException("Copy cancelled")

    /** Live copy progress: bytes done / total, and the file currently being written. */
    data class Progress(val copiedBytes: Long, val totalBytes: Long, val currentFile: String)

    /**
     * Mirrors [src]'s whole tree into [dest] on the CALLING (background) thread, reporting progress
     * via [onProgress] (throttled to ~every 80 ms plus once per file). Honours [isCancelled] between
     * files and between buffer reads. On cancel OR any IO failure the partial [dest] is deleted and
     * the throwable is rethrown, so the shortcut is never repointed at a half-copy.
     */
    fun copyTree(
        src: File,
        dest: File,
        totalBytes: Long,
        isCancelled: () -> Boolean,
        onProgress: (Progress) -> Unit,
    ) {
        var copied = 0L
        var lastEmit = 0L
        fun emit(current: String, force: Boolean) {
            val now = System.currentTimeMillis()
            if (force || now - lastEmit >= 80) {
                lastEmit = now
                onProgress(Progress(copied, totalBytes, current))
            }
        }
        try {
            if (!dest.exists() && !dest.mkdirs()) throw IOException("Could not create ${dest.absolutePath}")
            val stack = ArrayDeque<Pair<File, File>>().apply { addLast(src to dest) }
            val buffer = ByteArray(COPY_BUFFER)
            while (stack.isNotEmpty()) {
                if (isCancelled()) throw CancelledException()
                val (sdir, ddir) = stack.removeLast()
                val kids = sdir.listFiles() ?: continue
                for (k in kids) {
                    if (isCancelled()) throw CancelledException()
                    val target = File(ddir, k.name)
                    if (k.isDirectory) {
                        if (!target.exists() && !target.mkdirs()) {
                            throw IOException("Could not create ${target.absolutePath}")
                        }
                        stack.addLast(k to target)
                    } else {
                        emit(k.name, force = true)
                        k.inputStream().use { input ->
                            target.outputStream().use { output ->
                                while (true) {
                                    if (isCancelled()) throw CancelledException()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    copied += n
                                    emit(k.name, force = false)
                                }
                            }
                        }
                        runCatching { target.setLastModified(k.lastModified()) }
                    }
                }
            }
            emit("", force = true)
        } catch (t: Throwable) {
            runCatching { dest.deleteRecursively() }
            throw t
        }
    }

    /**
     * Repoints [shortcut] from [sourceRoot] to [destRoot] AFTER a successful copy: rewrites only the
     * `Exec=` line's exe path (any trailing launch args preserved) to the `C:\Games\<name>\…`
     * location, via [WinePath.resolveWindowsPath]. Returns the new Windows path, or null if the exe
     * wasn't under [sourceRoot] or the file had no Exec line (caller has validated, so null == abort
     * without having touched the shortcut).
     */
    fun repoint(shortcut: Shortcut, sourceRoot: File, destRoot: File): String? {
        val info = parse(shortcut)
        val exeAndroid = info.exeAndroid ?: return null
        val srcPath = sourceRoot.absolutePath.trimEnd('/')
        val exePath = exeAndroid.absolutePath
        val rel = when {
            exePath == srcPath -> ""
            exePath.startsWith("$srcPath/") -> exePath.substring(srcPath.length + 1)
            else -> return null
        }
        val newExeAndroid = if (rel.isEmpty()) destRoot else File(destRoot, rel)
        return setShortcutExe(shortcut, newExeAndroid, info.argsSuffix)
    }

    /**
     * THE single source of truth for repointing a shortcut's `Exec=` line at a new target. Converts
     * [newExeAndroid] to a Wine drive path via [WinePath.resolveWindowsPath] (mapping/persisting a
     * drive letter for its volume exactly like the importer does), escapes it, appends [argsSuffix]
     * verbatim (pass "" to drop launch args, or a leading-space suffix to keep them), and rewrites
     * the file's Exec line. Returns the new Windows path, or null when the path can't be mapped to a
     * drive (e.g. every drive letter is taken) or the file has no Exec line — in which case nothing
     * was written, so the caller can warn and abort with the shortcut left untouched.
     *
     * [imageFsRoot], when given, applies the shortcut WRITER's rule for a target under `imagefs/`
     * (see StarLaunchBridge's Exec builder): that tree is the container's fixed `Z:` drive, so it is
     * addressed as `Z:\…` rather than pushed through the drive map. Without it a target on
     * app-private storage — which isn't a storage volume — would fall back to mounting the exe's own
     * parent folder and burn a fresh drive letter per game. Callers repointing INTO imagefs (moving
     * a game back to internal storage) must pass it; callers that never target imagefs, like
     * copy-to-C and "Change executable", leave it null and keep their previous behaviour exactly.
     *
     * Used by copy-to-C's [repoint], the "Change executable" flow and
     * [com.winlator.star.store.MoveGameStorage] so the rewrite logic never forks.
     */
    @JvmOverloads
    fun setShortcutExe(
        shortcut: Shortcut,
        newExeAndroid: File,
        argsSuffix: String,
        imageFsRoot: File? = null,
    ): String? {
        val newWin = runCatching {
            winPathFor(shortcut, newExeAndroid, imageFsRoot)
        }.getOrNull() ?: return null
        val newExecValue = WinePath.escapeForExec(newWin) + argsSuffix
        if (!rewriteExecLine(shortcut.file, newExecValue)) return null
        Log.d(TAG, "setShortcutExe '${shortcut.name}': ${shortcut.path} -> $newWin")
        return newWin
    }

    /**
     * The Wine-side path to write for [newExeAndroid]: `Z:\…` when it lives under [imageFsRoot]
     * (the container's implicit imagefs drive), otherwise through the container's drive map. Mirrors
     * the rule StarLaunchBridge applies when it first writes a Steam shortcut, so a repointed
     * shortcut is byte-identical to a freshly created one for the same target.
     */
    private fun winPathFor(shortcut: Shortcut, newExeAndroid: File, imageFsRoot: File?): String {
        val path = newExeAndroid.absolutePath
        if (imageFsRoot != null) {
            val root = imageFsRoot.absolutePath.trimEnd('/')
            if (path.startsWith("$root/")) {
                return "Z:\\" + path.substring(root.length + 1).replace("/", "\\")
            }
        }
        return WinePath.resolveWindowsPath(shortcut.container, path)
    }

    /** Rewrites the single `Exec=` line to `Exec=wine <value>`. Returns false if there was none. */
    private fun rewriteExecLine(desktop: File, value: String): Boolean {
        val lines = desktop.readLines()
        if (lines.none { it.startsWith("Exec=") }) return false
        val rewritten = lines.map { if (it.startsWith("Exec=")) "Exec=wine $value" else it }
        desktop.writeText(rewritten.joinToString("\n") + "\n")
        return true
    }
}
