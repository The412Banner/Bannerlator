package com.winlator.star.core

import android.util.Log
import com.winlator.star.container.Container
import java.io.File
import java.io.IOException

/**
 * Translates an Android filesystem path into a Wine-side Windows path using a
 * container's drive map, allocating and persisting a new drive letter when the
 * path isn't already reachable.
 *
 * Single source of truth shared by the Games/Shortcuts importer
 * ([com.winlator.star.ui.screens.ShortcutsViewModel]) and the in-app File
 * Manager Run action, so both map drives and escape paths identically.
 */
object WinePath {
    private const val TAG = "WinePath"

    /**
     * Builds a Wine-side Windows path (e.g. `F:\Games\foo.exe`) for [path] using
     * [container]'s drive map. Paths inside the container's own prefix resolve to `C:`. If no
     * existing drive contains the path, a new letter is allocated for the whole storage volume
     * and persisted on the container.
     */
    fun resolveWindowsPath(container: Container, path: String): String {
        // C: first. The prefix is always reachable as C:, but it is NOT part of drivesIterator(),
        // so without this every path under drive_c missed every drive and burned a fresh letter —
        // e.g. the component installer's windows/temp/bannerlator_components. resolveAndroidPath
        // has always special-cased C:, so this also makes the two directions symmetric.
        val driveC = File(container.getRootDir(), ".wine/drive_c").absolutePath.trimEnd('/')
        if (path == driveC || path.startsWith("$driveC/")) {
            return "C:\\" + relativeTo(driveC, path)
        }

        val match = bestDriveMatch(container, path)
        if (match != null) {
            val (letter, mountPath) = match
            return "$letter:\\" + relativeTo(mountPath, path)
        }

        // No existing drive. Mount the whole STORAGE VOLUME rather than this file's own folder:
        // mounting the leaf folder is what made every SD/USB game claim its own letter, since one
        // game's folder is never an ancestor of the next game's. Paths that aren't on a storage
        // volume (app-private dirs and the like) keep the old parent-folder behaviour.
        val mountPath = storageVolumeRootOf(path)
            ?: File(path).parentFile?.absolutePath
            ?: "/"
        val letter = allocateDriveLetter(container)
            ?: throw NoFreeDriveLetterException(mountPath)
        container.drives = container.drives + "$letter:$mountPath"
        try {
            container.saveData()
            Log.d(TAG, "Auto-added drive $letter: -> $mountPath (container ${container.id})")
        } catch (e: Exception) {
            Log.w(TAG, "Drive persist failed (continuing with in-memory mapping)", e)
        }
        // MUST be the full path relative to the mount. This used to return the bare file name,
        // which was only ever correct because the mount was the file's own parent folder.
        return "$letter:\\" + relativeTo(mountPath, path)
    }

    /** Thrown when every drive letter is taken, so callers can say something useful. */
    class NoFreeDriveLetterException(val mountPath: String) :
        IOException("No free drive letter available to map $mountPath")

    /**
     * The storage volume [path] lives on, or null when it isn't on one.
     *
     * Pure string-shape derivation so [WinePath] stays free of Android dependencies. Note this is
     * NOT the same thing as `StorageRoots.volumeRootOf`, which walks up from an app-specific
     * directory looking for an `Android` folder — different input, different job.
     */
    fun storageVolumeRootOf(path: String): String? {
        val parts = path.trim().trimEnd('/').split("/").filter { it.isNotEmpty() }
        return when {
            // /storage/emulated/<n>/... -> /storage/emulated/<n>
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" ->
                "/${parts[0]}/${parts[1]}/${parts[2]}"
            // /storage/<UUID>/... -> /storage/<UUID>
            parts.size >= 2 && parts[0] == "storage" && parts[1] !in RESERVED_STORAGE_NAMES ->
                "/${parts[0]}/${parts[1]}"
            // /mnt/media_rw/<UUID>/... -> /mnt/media_rw/<UUID>
            parts.size >= 3 && parts[0] == "mnt" && parts[1] == "media_rw" ->
                "/${parts[0]}/${parts[1]}/${parts[2]}"
            else -> null
        }
    }

    private val RESERVED_STORAGE_NAMES = setOf("emulated", "self")

    /**
     * True when the game behind [winPath] lives on removable storage (SD card or USB) rather than
     * internal, so the UI can badge it. Anything not on a recognised storage volume — app-private
     * dirs, the container prefix — counts as not removable.
     */
    fun isOnRemovableStorage(container: Container, winPath: String): Boolean {
        val android = runCatching { resolveAndroidPath(container, winPath) }.getOrNull() ?: return false
        val root = storageVolumeRootOf(android.absolutePath) ?: return false
        return !root.startsWith("/storage/emulated")
    }

    /** Windows-style path of [path] relative to [mountPath]. */
    private fun relativeTo(mountPath: String, path: String): String =
        path.removePrefix(mountPath).removePrefix("/").replace("/", "\\")

    /**
     * The inverse of [resolveWindowsPath]: maps a Wine-side Windows path (e.g.
     * `F:\Games\foo\foo.exe`) back to a file on the Android side using [container]'s
     * drive map, with C: resolving to the container's own prefix. Returns null when the
     * letter isn't mapped or the path isn't drive-qualified — callers treat that as
     * "unknown location" rather than guessing.
     */
    fun resolveAndroidPath(container: Container, winPath: String): File? {
        val path = winPath.trim().trim('"')
        if (path.length < 3 || path[1] != ':') return null
        val letter = path.substring(0, 1).uppercase()
        val rel = path.substring(2).trimStart('\\', '/').replace("\\", "/")
        val root: String = if (letter == "C") {
            File(container.getRootDir(), ".wine/drive_c").absolutePath
        } else {
            container.drivesIterator()
                .firstOrNull { it[0].equals(letter, ignoreCase = true) }
                ?.get(1)?.trimEnd('/') ?: return null
        }
        return if (rel.isEmpty()) File(root) else File(root, rel)
    }

    /**
     * Escapes a Windows path for an `Exec=wine ...` line, using 4-backslash
     * separators per Winlator's two-pass [StringUtils.unescape].
     */
    fun escapeForExec(winPath: String): String = winPath.replace("\\", "\\\\\\\\")

    /** Returns (letter, mountPath) of the longest-matching drive prefix, or null. */
    private fun bestDriveMatch(container: Container, path: String): Pair<String, String>? {
        var best: Pair<String, String>? = null
        for (entry in container.drivesIterator()) {
            val letter = entry[0]
            val mountPath = entry[1].trimEnd('/')
            if (mountPath.isEmpty()) continue
            val matches = path == mountPath ||
                    path.startsWith(if (mountPath.endsWith("/")) mountPath else "$mountPath/")
            if (matches && (best == null || mountPath.length > best!!.second.length)) {
                best = letter to mountPath
            }
        }
        return best
    }

    /** Picks the next free drive letter (skips C: and Z:, plus anything already mapped). */
    private fun allocateDriveLetter(container: Container): String? {
        val used = mutableSetOf("C", "Z")
        for (entry in container.drivesIterator()) used += entry[0].uppercase()
        // Try G..Y first to avoid stomping on common user-set letters (D/E/F).
        val order = ('G'..'Y').map { it.toString() } + listOf("A", "B", "D", "E", "F")
        return order.firstOrNull { it !in used }
    }
}
