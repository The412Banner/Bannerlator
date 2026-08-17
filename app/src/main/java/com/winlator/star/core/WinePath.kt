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
     * [container]'s drive map. If no existing drive contains the path, a new
     * letter is allocated to the parent folder and persisted on the container.
     */
    fun resolveWindowsPath(container: Container, path: String): String {
        val match = bestDriveMatch(container, path)
        if (match != null) {
            val (letter, mountPath) = match
            val rel = path.removePrefix(mountPath).removePrefix("/").replace("/", "\\")
            return "$letter:\\$rel"
        }
        // No existing drive — allocate one for the parent folder and persist.
        val parent = File(path).parentFile?.absolutePath ?: "/"
        val letter = allocateDriveLetter(container)
            ?: throw IOException("No free drive letter available to map $parent")
        container.drives = container.drives + "$letter:$parent"
        try {
            container.saveData()
            Log.d(TAG, "Auto-added drive $letter: -> $parent (container ${container.id})")
        } catch (e: Exception) {
            Log.w(TAG, "Drive persist failed (continuing with in-memory mapping)", e)
        }
        val fileName = File(path).name.replace("/", "\\")
        return "$letter:\\$fileName"
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
