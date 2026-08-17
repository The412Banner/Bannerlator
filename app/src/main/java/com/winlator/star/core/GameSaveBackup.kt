package com.winlator.star.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.winlator.star.container.Container
import com.winlator.star.xenvironment.ImageFs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Two-way game-save transfer for a container's Wine prefix, using GameHub's on-disk layout so
 * the archives are interchangeable with GameHub and other Winlator builds.
 *
 * A GameHub backup is a snapshot of a Proton prefix's drive_c: every entry is rooted at
 * "/drive_c/...". GameHub runs Proton as the Wine user "steamuser"; OUR containers run Wine as
 * [ImageFs.USER] ("xuser"). So the user segment of every "users/<name>/..." path must be
 * translated between the two worlds or the game never sees the save:
 *
 *   Restore (import):  drive_c/users/steamuser/... ->  <container>/.wine/drive_c/users/xuser/...
 *   Back up (export):  <container>/.wine/drive_c/users/xuser/...  ->  zip /drive_c/users/steamuser/...
 *
 * "Public" is shared under both and is never translated.
 */
object GameSaveBackup {

    /**
     * Target on-disk layout for a backup zip. The file walk is identical across layouts — the ONLY
     * difference is how each entry is rooted, which is what makes a game "see" its save in the tool
     * you're restoring into.
     *
     *  - [GAMEHUB]  → "/drive_c/users/steamuser/…"  (GameHub / Proton, the default)
     *  - [WINLATOR] → "drive_c/users/xuser/…"       (sibling Winlator / WinNative / Bannerlator builds)
     *
     * Both re-import into OUR builds regardless: [remapForRestore] rewrites any non-Public user
     * segment to [ImageFs.USER], so steamuser→xuser and xuser→xuser both land correctly.
     */
    enum class BackupLayout { GAMEHUB, WINLATOR }

    data class RestoreResult(val ok: Boolean, val filesWritten: Int, val error: String?)
    data class BackupResult(val ok: Boolean, val path: String?, val fileCount: Int, val error: String?)

    // ---------------------------------------------------------------- restore (import)

    /**
     * Human-readable game name parsed from a picked backup's display name.
     * GameHub stamps "<Game Name>_<epochMillis>.zip" → "Titanfall® 2".
     */
    fun gameNameFromUri(context: Context, uri: Uri): String {
        val display = queryDisplayName(context, uri) ?: "backup"
        val base = display.substringBeforeLast('.', display)
        return base.replace(Regex("_\\d{10,}$"), "").trim().ifEmpty { base }
    }

    /** Unzips [uri] into [container]'s drive_c off the UI thread; posts [onResult] on the main thread. */
    fun restore(context: Context, uri: Uri, container: Container, onResult: (RestoreResult) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            val res = try {
                doRestore(appContext, uri, container)
            } catch (e: Exception) {
                RestoreResult(false, 0, e.message ?: e.javaClass.simpleName)
            }
            Handler(Looper.getMainLooper()).post { onResult(res) }
        }.start()
    }

    private fun doRestore(context: Context, uri: Uri, container: Container): RestoreResult {
        val driveC = File(container.rootDir, ".wine/drive_c")
        // Canonical base for the Zip-Slip guard — resolves ".." lexically and follows symlinks
        // on the existing prefix, so an escaping entry can't slip past it.
        val driveCCanon = driveC.canonicalFile
        val basePrefix = driveCCanon.path + File.separator
        var written = 0

        val input = context.contentResolver.openInputStream(uri)
            ?: return RestoreResult(false, 0, "Could not open backup file")

        input.use { rawIn ->
            ZipInputStream(BufferedInputStream(rawIn)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val rel = remapForRestore(entry.name)
                    if (rel != null) {
                        val out = File(driveC, rel).canonicalFile
                        if (out.path == driveCCanon.path || out.path.startsWith(basePrefix)) {
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                Files.copy(zis, out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                written++
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return RestoreResult(true, written, null)
    }

    /**
     * Maps a raw backup entry to a path relative to drive_c, translating the Proton user to ours.
     * Returns null for entries not rooted under drive_c (defensive — GameHub always roots at
     * "/drive_c", but we skip anything unexpected rather than dumping it at the prefix root).
     */
    private fun remapForRestore(rawName: String): String? {
        val segs = splitDriveC(rawName) ?: return null
        if (segs.isEmpty()) return null
        if (isFrontendShortcut(segs)) return null
        // users/<name>/... where <name> is a profile other than the shared "Public" → xuser.
        if (segs.size >= 2 &&
            segs[0].equals("users", ignoreCase = true) &&
            !segs[1].equals("Public", ignoreCase = true)
        ) {
            segs[1] = ImageFs.USER
        }
        return segs.joinToString("/")
    }

    /**
     * GameHub bundles launcher shortcuts in its backups — a "proton_shortcuts/" tree (its own
     * frontend) and .lnk/.desktop files on the Wine Desktop. Restoring the Desktop ones drops
     * phantom game cards into Bannerlator's Games grid, because ContainerManager.loadShortcuts()
     * scans the container's Desktop dir and auto-imports every .lnk as a shortcut. These are
     * never save data, so skip them on restore.
     */
    private fun isFrontendShortcut(segs: List<String>): Boolean {
        if (segs[0].equals("proton_shortcuts", ignoreCase = true)) return true
        val last = segs.last().lowercase()
        val isShortcutFile = last.endsWith(".lnk") || last.endsWith(".desktop") || last.endsWith(".url")
        return isShortcutFile && segs.any { it.equals("Desktop", ignoreCase = true) }
    }

    // ---------------------------------------------------------------- backup (export)

    /**
     * Whole-container backup in the default GameHub layout — thin delegate kept for existing
     * callers; equivalent to [backup] with `roots = null`, `gameName = null`, GAMEHUB.
     */
    fun backup(context: Context, container: Container, onResult: (BackupResult) -> Unit) =
        backup(context, container, null, null, BackupLayout.GAMEHUB, onResult)

    /**
     * Zips a container's saves into an archive under Downloads/Winlator/Backups/GameSaves, off the
     * UI thread (posts [onResult] on the main thread).
     *
     *  - [roots] == null  → the whole xuser profile (whole-container backup).
     *  - [roots] != null  → only those subtrees under the xuser profile (per-game backup); each
     *    entry is a path relative to xuser (e.g. "Documents/My Games/Elden Ring").
     *  - [gameName] names the zip ("&lt;name&gt;_&lt;epoch&gt;.zip"); falls back to the container name.
     *  - [layout] chooses how each entry is rooted (see [BackupLayout]).
     */
    fun backup(
        context: Context,
        container: Container,
        roots: List<String>?,
        gameName: String?,
        layout: BackupLayout,
        onResult: (BackupResult) -> Unit,
    ) {
        Thread {
            val res = try {
                doBackup(container, roots, gameName, layout)
            } catch (e: Exception) {
                BackupResult(false, null, 0, e.message ?: e.javaClass.simpleName)
            }
            Handler(Looper.getMainLooper()).post { onResult(res) }
        }.start()
    }

    private fun doBackup(
        container: Container,
        roots: List<String>?,
        gameName: String?,
        layout: BackupLayout,
    ): BackupResult {
        val profile = File(container.rootDir, ".wine/drive_c/users/${ImageFs.USER}")
        if (!profile.isDirectory) {
            return BackupResult(false, null, 0, "No save profile found in this container")
        }

        // The only per-layout state: the entry root prefix + which user segment to write.
        val (rootPrefix, userSeg) = when (layout) {
            BackupLayout.GAMEHUB -> "/drive_c/" to "steamuser"
            BackupLayout.WINLATOR -> "drive_c/" to ImageFs.USER
        }

        // Subtrees to walk: the whole profile when unscoped, else each existing scoped root.
        val scopeDirs: List<File> = if (roots == null) listOf(profile)
        else roots.map { File(profile, it) }.filter { it.exists() }

        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Winlator/Backups/GameSaves"
        )
        outDir.mkdirs()
        val safeName = sanitize((gameName ?: container.name).ifBlank { "container-${container.id}" })
        val outFile = File(outDir, "${safeName}_${System.currentTimeMillis()}.zip")

        var count = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zos ->
            val stack = ArrayDeque<File>()
            scopeDirs.forEach { d ->
                if (d.isDirectory) d.listFiles()?.forEach { stack.addLast(it) }
                else if (d.isFile) stack.addLast(d)
            }
            while (stack.isNotEmpty()) {
                val f = stack.removeLast()
                // Skip volatile noise that isn't a save — keeps backups small and GameHub-clean,
                // even inside a scoped root.
                if (f.isDirectory && isNoiseDir(profile, f)) continue
                if (f.isDirectory) {
                    f.listFiles()?.forEach { stack.addLast(it) }
                    continue
                }
                // Relative path inside the xuser profile, re-rooted per the chosen layout.
                val rel = f.relativeTo(profile).path.replace(File.separatorChar, '/')
                val entryName = "${rootPrefix}users/$userSeg/$rel"
                zos.putNextEntry(ZipEntry(entryName))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                count++
            }
        }

        if (count == 0) {
            outFile.delete()
            return BackupResult(false, null, 0, "No save files to back up")
        }
        return BackupResult(true, outFile.absolutePath, count, null)
    }

    /** AppData/Local/Temp and crash dumps are pure churn, never save data. */
    private fun isNoiseDir(profile: File, dir: File): Boolean {
        val rel = dir.relativeTo(profile).path.replace(File.separatorChar, '/')
        return rel.equals("AppData/Local/Temp", ignoreCase = true) ||
            rel.equals("AppData/Local/CrashDumps", ignoreCase = true)
    }

    // ---------------------------------------------------------------- shared helpers

    /**
     * Normalizes a zip entry name and returns its path segments *below* the leading "drive_c/"
     * root (mutable, so callers can rewrite a segment), or null if it isn't under drive_c.
     */
    private fun splitDriveC(rawName: String): MutableList<String>? {
        var name = rawName.replace('\\', '/')
        while (name.startsWith("/")) name = name.substring(1)
        val segs = name.split("/").filter { it.isNotEmpty() && it != "." }
        if (segs.isEmpty() || !segs[0].equals("drive_c", ignoreCase = true)) return null
        return segs.drop(1).toMutableList()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "backup" }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
