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

    data class RestoreResult(
        val ok: Boolean,
        val filesWritten: Int,
        val error: String?,
        /**
         * Emulator account ids in the backup that DISAGREE with the ones the target prefix is
         * already using. Held back rather than applied, because the id is prefix-global: silently
         * repointing it would fix this game's saves and orphan every other emulated game's in the
         * same container. The caller asks the user, then calls [applyEmuIdentity] or
         * [discardEmuIdentity] per entry. Empty on the common paths — a matching id, or a rebuilt
         * prefix with no id at all, is applied during the restore with nothing to ask about.
         */
        val emuConflicts: List<EmuIdConflict> = emptyList(),
    )

    data class BackupResult(val ok: Boolean, val path: String?, val fileCount: Int, val error: String?)

    /**
     * One emulator's identity from a backup, staged on disk and awaiting the user's decision.
     * [stagedDir] holds the backup's copy of [root]; applying it copies that over the prefix.
     */
    data class EmuIdConflict(
        val label: String,
        val root: String,
        val currentId: String,
        val backupId: String,
        val stagedDir: String,
        val containerRootDir: String,
    ) {
        /** The save folder [backupId] names, for a message the user can match against their saves. */
        val backupSaveFolder: String? get() = EmuAccountIdentity.accountFolderId(backupId)
        val currentSaveFolder: String? get() = EmuAccountIdentity.accountFolderId(currentId)
    }

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
        val profile = File(driveC, "users/${ImageFs.USER}")
        // Emulator identity is diverted here first instead of straight into the prefix: whether it
        // may be applied depends on the id, which can arrive in a later entry than the file holding
        // it, and a conflict must survive the stream so the user can be asked afterwards.
        val stagingRoot = File(context.cacheDir, "emu-id-restore")
        // Sweep any previous restore's leftovers first: a new restore supersedes an unanswered
        // conflict from an old one, and this keeps the cache from accreting stale prefixes.
        stagingRoot.deleteRecursively()
        val staging = File(stagingRoot, System.currentTimeMillis().toString())
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
                            // Emulator identity is diverted to staging; save data lands in the prefix.
                            val identityRel = profileRelative(rel)
                                ?.takeIf { EmuAccountIdentity.isIdentityPath(it) }
                            if (entry.isDirectory) {
                                if (identityRel == null) out.mkdirs()
                            } else if (identityRel != null) {
                                val staged = File(staging, identityRel)
                                staged.parentFile?.mkdirs()
                                Files.copy(zis, staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

        val (applied, conflicts) = resolveEmuIdentity(staging, profile, container)
        if (conflicts.isEmpty()) staging.deleteRecursively()
        return RestoreResult(true, written + applied, null, conflicts)
    }

    /**
     * Decides what to do with each emulator identity staged out of a backup:
     *
     *  - the prefix has no id yet (a fresh or rebuilt prefix — the case that loses saves) → apply it,
     *    which is the whole point of carrying the id in the backup;
     *  - the ids match → apply it, a no-op the user need not hear about;
     *  - they differ → hold it back and report a conflict for the caller to ask about.
     *
     * Returns the number of files written and the conflicts left staged.
     */
    private fun resolveEmuIdentity(
        staging: File,
        profile: File,
        container: Container,
    ): Pair<Int, List<EmuIdConflict>> {
        if (!staging.isDirectory) return 0 to emptyList()
        var applied = 0
        val conflicts = mutableListOf<EmuIdConflict>()
        for (emu in EmuAccountIdentity.EMULATORS) {
            val stagedDir = File(staging, emu.root)
            if (!stagedDir.isDirectory) continue
            val backupId = EmuAccountIdentity.readId(staging, emu) ?: continue
            val currentId = EmuAccountIdentity.readId(profile, emu)
            if (currentId == null || currentId == backupId) {
                applied += applyIdentity(stagedDir, File(profile, emu.root), emu)
            } else {
                conflicts += EmuIdConflict(
                    label = emu.label,
                    root = emu.root,
                    currentId = currentId,
                    backupId = backupId,
                    stagedDir = stagedDir.absolutePath,
                    containerRootDir = container.rootDir.absolutePath,
                )
            }
        }
        return applied to conflicts
    }

    /**
     * Applies a held-back emulator identity after the user chose the backup's id. Returns false if
     * the staged copy is gone (the cache was cleared out from under us), in which case nothing was
     * written and the prefix keeps the id it had.
     */
    fun applyEmuIdentity(conflict: EmuIdConflict): Boolean {
        val staged = File(conflict.stagedDir)
        if (!staged.isDirectory) return false
        val emu = EmuAccountIdentity.emuForRoot(conflict.root) ?: return false
        val target = File(File(conflict.containerRootDir), ".wine/drive_c/users/${ImageFs.USER}/${conflict.root}")
        val written = applyIdentity(staged, target, emu)
        discardEmuIdentity(conflict)
        return written > 0
    }

    /**
     * Writes one emulator's identity from [staged] over [target].
     *
     * Copying alone is not enough. These emulators accept the account id under several filenames and
     * read them in a fixed order, so a stale file the backup doesn't supply but that OUTRANKS what it
     * does supply would keep winning: the user would be told the account was switched while the game
     * carried on under the old one. Only those shadowing leftovers are deleted; anything the backup's file
     * already outranks stays, so lower-priority files and the shared settings living beside them
     * (language, ip_country in Goldberg's configs.user.ini) survive untouched.
     */
    private fun applyIdentity(staged: File, target: File, emu: EmuAccountIdentity.Emu): Int {
        val supplied = emu.idFiles.indexOfFirst { File(staged, it).isFile }
        if (supplied > 0) emu.idFiles.take(supplied).forEach { File(target, it).delete() }
        return copyTree(staged, target)
    }

    /** Drops a held-back identity's staged copy; the prefix keeps the id it already had. */
    fun discardEmuIdentity(conflict: EmuIdConflict) {
        File(conflict.stagedDir).deleteRecursively()
    }

    /** Copies a staged subtree over [dst], returning the number of files written. */
    private fun copyTree(src: File, dst: File): Int {
        var n = 0
        src.walkTopDown().forEach { f ->
            val rel = f.relativeTo(src).path
            val out = if (rel.isEmpty()) dst else File(dst, rel)
            if (f.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                Files.copy(f.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                n++
            }
        }
        return n
    }

    /**
     * A drive_c-relative restore path re-expressed relative to the Wine user profile, or null when
     * it isn't under this container's profile at all (Public, or a stray non-user entry).
     */
    private fun profileRelative(driveCRel: String): String? {
        val prefix = "users/${ImageFs.USER}/"
        return if (driveCRel.startsWith(prefix, ignoreCase = true)) driveCRel.substring(prefix.length)
        else null
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
        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Winlator/Backups/GameSaves"
        )
        outDir.mkdirs()
        val safeName = sanitize((gameName ?: container.name).ifBlank { "container-${container.id}" })
        val outFile = File(outDir, "${safeName}_${System.currentTimeMillis()}.zip")
        return backupToFile(container, roots, layout, outFile)
    }

    /**
     * Additive: zip a container's saves to a caller-specified [outFile] and return synchronously
     * (no threading, no result posting). Same walk / layout re-rooting / noise-filtering as the
     * Downloads-writing [backup]; the ONLY difference is the output location is the caller's, which
     * lets callers manage their own destination (e.g. a persistent per-game vault). Creates the
     * parent dir; deletes [outFile] and reports failure when there is nothing to back up. Callers are
     * responsible for running this off the main thread.
     */
    fun backupToFile(
        container: Container,
        roots: List<String>?,
        layout: BackupLayout,
        outFile: File,
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

        // Subtrees to walk. Unscoped already covers the whole profile, emulator identity included;
        // a SCOPED backup must be told about it, or the archive holds saves the game can't read back
        // after a prefix rebuild rerolls the account id (see [EmuAccountIdentity]).
        val scopeRoots: List<String>? = roots?.plus(EmuAccountIdentity.BACKUP_ROOTS)
        val scopeDirs: List<File> = if (scopeRoots == null) listOf(profile)
        else scopeRoots.map { File(profile, it) }.filter { it.exists() }

        outFile.parentFile?.mkdirs()

        var count = 0
        var identityCount = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zos ->
            val stack = ArrayDeque<File>()
            // Entry names already written. Scoped roots can nest (a picked root under AppData/Roaming
            // can contain an identity root, and the picker lets two roots overlap), and a repeated
            // name makes ZipOutputStream throw "duplicate entry" — a crashed backup, not a bad one.
            val seen = HashSet<String>()
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
                if (!seen.add(entryName)) continue
                zos.putNextEntry(ZipEntry(entryName))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                if (EmuAccountIdentity.isIdentityPath(rel)) identityCount++ else count++
            }
        }

        // Counted apart from save files on purpose: an account id with no save beside it is not a
        // backup of anything, so it must not turn "nothing to back up" into a hollow success.
        if (count == 0) {
            outFile.delete()
            return BackupResult(false, null, 0, "No save files to back up")
        }
        return BackupResult(true, outFile.absolutePath, count + identityCount, null)
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
