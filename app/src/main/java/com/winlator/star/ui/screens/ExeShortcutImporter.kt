package com.winlator.star.ui.screens

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.FileUtils
import com.winlator.star.core.GameIdentifier
import com.winlator.star.core.WinePath
import com.winlator.star.store.SteamStoreSearch
import com.winlator.star.store.StarLaunchBridge
import java.io.File

/**
 * Single source of truth for turning a Windows executable on disk into a permanent
 * Games/Shortcuts entry (a `.desktop` in the container's desktop dir). Shared by the
 * Games-tab "+" importer ([ShortcutsViewModel.importExe]) and the File Manager's
 * "Add to shortcuts" action so the two never drift.
 */
internal object ExeShortcutImporter {
    private const val TAG = "ExeShortcutImporter"

    /**
     * Write a permanent shortcut for [exeFile] into [container]'s desktop dir and return the
     * `.desktop` file. Name/cover resolution runs on a background thread:
     *   1. Cover art resolves keyed to the current name: Steam CDN 600x900 portrait (by [steamAppId])
     *      → SGDB-by-appid → SGDB-by-name → PE-icon fallback. [onCoverArtReady] fires (off the main
     *      thread) once this finishes so callers can refresh their list.
     *   2. When a Steam [steamAppId] is known, Steam's authoritative store name is fetched and, if it
     *      beats the on-disk name (a launcher/loader PE name like "GSE" or "Rockstar Games Launcher"),
     *      the shortcut is renamed via [renameShortcutFiles] — which moves the cover/icon written in
     *      step 1 so the art follows. Guarded against the confirm/rename dialog: it only renames while
     *      the shortcut is still at its original name (the user hasn't already Saved a different one),
     *      and reports the new base through [onNameResolved] (on the MAIN thread) so an open dialog can
     *      keep its Save target in sync.
     * Throws [java.io.IOException] if the shortcut can't be written.
     */
    fun addToShortcuts(
        context: Context,
        container: Container,
        exeFile: File,
        displayName: String,
        steamAppId: Int? = null,
        onCoverArtReady: () -> Unit = {},
        onNameResolved: (oldBase: String, newBase: String) -> Unit = { _, _ -> },
    ): File {
        val shortcutFile = writeExeShortcut(container, exeFile, displayName)
        val appCtx = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val desktopDir = container.getDesktopDir()
            // Current on-disk base name; may be upgraded to the authoritative Steam name below.
            val base = shortcutFile.nameWithoutExtension
            try {
                // 1. Cover art keyed to the current name — Steam CDN 600x900 portrait first, then the
                //    existing SGDB chain (by appid, then by name) inside saveCoverArt. Guarded on the
                //    .desktop still existing so a user rename-via-dialog can't make saveCoverArt
                //    recreate a stray shortcut for the old name.
                if (File(desktopDir, "$base.desktop").isFile) {
                    val steamCover = steamAppId?.takeIf { it > 0 }?.let { SteamStoreSearch.coverUrl(it) }
                    StarLaunchBridge.saveCoverArt(
                        appCtx, container, File(desktopDir, "$base.desktop"), base, steamCover, steamAppId,
                    )
                    val iconFile = container.getIconsDir(64)?.let { File(it, "$base.png") }
                    if ((iconFile == null || !iconFile.exists()) && File(desktopDir, "$base.desktop").isFile) {
                        // SGDB/Steam miss — try extracting an icon from the EXE itself.
                        ExeIconExtractor.extract(exeFile)?.let { bmp ->
                            container.getIconsDir(64)?.let { iconsDir ->
                                if (!iconsDir.exists()) iconsDir.mkdirs()
                                FileUtils.saveBitmapToFile(bmp, File(iconsDir, "$base.png"))
                            }
                            try {
                                Shortcut(container, File(desktopDir, "$base.desktop")).saveCustomCoverArt(bmp)
                            } catch (e: Exception) {
                                Log.w(TAG, "saveCustomCoverArt failed for $base", e)
                            }
                            Log.d(TAG, "PE icon extraction succeeded for $base")
                        }
                    }
                }
                onCoverArtReady()

                // 2. Steam authoritative name (network) — corrects a launcher/loader PE name.
                //    renameShortcutFiles moves the cover/icon just written, so the art follows.
                if (steamAppId != null && steamAppId > 0) {
                    val better = betterSteamName(base, SteamStoreSearch.resolveName(steamAppId))
                    // Race guard: only rename while the shortcut is still at its original name.
                    // If the user already Saved a different name via the confirm dialog, the
                    // original .desktop is gone and we leave their choice alone.
                    if (better != null &&
                        File(desktopDir, "$base.desktop").isFile &&
                        renameShortcutFiles(container, base, better)
                    ) {
                        main.post { onNameResolved(base, better) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Name/cover-art resolution failed for $base", e)
            }
        }, "exe-import-cover-art").start()
        return shortcutFile
    }

    /**
     * Decide whether Steam's authoritative [steam] name should replace the current on-disk
     * [local] base. Returns a filesystem-safe replacement base, or null to keep [local]. Prefers
     * Steam whenever it names a genuinely different title (so "GSE"/"Rockstar Games Launcher" lose
     * to the real game); keeps [local] when the two are equivalent ignoring case/punctuation, so a
     * name already resolved from disk (Steam manifest / GOG info) isn't churned by a redundant rename.
     */
    private fun betterSteamName(local: String, steam: String?): String? {
        val candidate = steam?.let { GameIdentifier.normalizeName(it) }?.takeIf { it.isNotBlank() }
            ?: return null
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        if (key(candidate) == key(local)) return null
        return candidate
    }

    /**
     * Robustly rename a shortcut from [oldBase] to [newBase] within [container]: moves the
     * `.desktop` (and any sibling `.lnk`), moves the icon PNGs across all size buckets, moves the
     * cover-art PNG, and rewrites the `Icon=` line + `customCoverArtPath` extra so the tile and the
     * detail view still resolve. Returns false (no-op) if [oldBase] is missing or [newBase] exists.
     * Shared by the importer's auto-rename and the confirm dialog's Save so the two never drift.
     * (No `component_installs`-style per-name state exists — those prefs are keyed by container id.)
     */
    internal fun renameShortcutFiles(container: Container, oldBase: String, newBase: String): Boolean {
        if (newBase.isBlank() || oldBase == newBase) return false
        val desktopDir = container.getDesktopDir()
        val oldFile = File(desktopDir, "$oldBase.desktop")
        val newFile = File(desktopDir, "$newBase.desktop")
        if (!oldFile.isFile || newFile.isFile) return false
        if (!oldFile.renameTo(newFile)) return false

        // Sibling .lnk (from the .desktop/.lnk import pair), if any.
        File(desktopDir, "$oldBase.lnk").let { lnk ->
            if (lnk.isFile) lnk.renameTo(File(desktopDir, "$newBase.lnk"))
        }

        // Icon PNGs — the importer writes 64, but move every bucket that exists.
        for (size in intArrayOf(64, 48, 32, 16)) {
            val dir = container.getIconsDir(size) ?: continue
            val oldIcon = File(dir, "$oldBase.png")
            val newIcon = File(dir, "$newBase.png")
            if (oldIcon.isFile && !newIcon.isFile) oldIcon.renameTo(newIcon)
        }

        // Cover-art PNG (container root: app_data/cover_arts/<base>.png).
        val coverDir = File(container.getRootDir(), "app_data/cover_arts")
        val oldCover = File(coverDir, "$oldBase.png")
        val newCover = File(coverDir, "$newBase.png")
        if (oldCover.isFile && !newCover.isFile) oldCover.renameTo(newCover)

        // Rewrite the Icon= line (raw — Shortcut has no setter), then repoint customCoverArtPath.
        // saveData() copies the Desktop Entry lines verbatim, so the rewritten Icon= survives.
        try {
            val rewritten = newFile.readText().replace(Regex("(?m)^Icon=.*$"), "Icon=$newBase")
            newFile.writeText(rewritten)
            if (newCover.isFile) {
                Shortcut(container, newFile).setCustomCoverArtPath(newCover.absolutePath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "rename fixup (Icon=/coverArt) failed for $newBase", e)
        }
        Log.d(TAG, "Renamed shortcut '$oldBase' -> '$newBase'")
        return true
    }

    private fun writeExeShortcut(container: Container, exeFile: File, displayName: String): File {
        val desktopDir = container.getDesktopDir()
        if (!desktopDir.exists()) desktopDir.mkdirs()

        val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "game" }
        val shortcutFile = File(desktopDir, "$safeName.desktop")

        // Resolve to a Wine drive letter against the container's mount map. Z: would
        // map to imagefs root (chroot view) and not reach external storage, so we use
        // F:/D:/etc. as defined in container.drives. If no existing drive contains the
        // EXE path we add and persist a new letter for the whole storage VOLUME, so the
        // next game on the same card or stick reuses it instead of taking another letter.
        val winPath = WinePath.resolveWindowsPath(container, exeFile.absolutePath)
        // 4-backslash separators per Winlator's two-pass StringUtils.unescape().
        val escaped = WinePath.escapeForExec(winPath)
        val content = buildString {
            append("[Desktop Entry]\n")
            append("Name=").append(displayName).append("\n")
            append("Exec=wine ").append(escaped).append("\n")
            append("Icon=").append(safeName).append("\n")
            append("Type=Application\n")
            append("StartupWMClass=explorer\n")
            append("\n")
            append("[Extra Data]\n")
        }
        shortcutFile.writeText(content)
        Log.d(TAG, "Wrote EXE shortcut: ${shortcutFile.path} -> $winPath ($exeFile)")
        return shortcutFile
    }
}
