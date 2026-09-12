package com.winlator.star.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import java.io.File

/**
 * A persistent, local-only save vault for CUSTOM (non-Steam) games — the counterpart of the Steam
 * auto-Collect-on-exit. Each game keeps ONE current snapshot keyed by its stable identity, living on
 * external storage independent of the container/install, so it survives the shortcut (and even the
 * game) being removed. No cloud is ever involved.
 *
 * Layout: every custom game's backups — manual AND this auto-on-exit vault — share ONE per-game
 * folder, `Downloads/Bannerlator/game saves/<GameName>/` (see [perGameDir]). The auto snapshot is a
 * single `auto-latest.zip` overwritten each exit; the manual flow drops timestamped
 * `<GameName>_<epoch>.zip` siblings (history). The zip is a [GameSaveBackup.BackupLayout.WINLATOR]
 * archive, so it restores through the same [GameSaveBackup.restore] path used elsewhere.
 *
 * Everything here is best-effort and never throws to the caller; the blocking work ([snapshot]) is
 * synchronous so callers can run it on their own worker thread and bound-wait on it.
 */
object CustomSaveVault {

    private const val TAG = "BH_SAVE_SYNC"

    /** Filename of the single overwrite-on-exit auto snapshot inside a game's [perGameDir]. */
    private const val AUTO_LATEST = "auto-latest.zip"

    /**
     * The one per-game backup folder shared by the manual "Back up saves" flow and the auto vault:
     * `Downloads/Bannerlator/game saves/<sanitized game name>/`. Public so the manual flow writes its
     * timestamped zips into the same place as the auto snapshot.
     */
    fun perGameDir(gameName: String): File =
        File(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Bannerlator/game saves",
            ),
            sanitize(gameName),
        )

    /** The timestamped manual-backup file for [gameName]: `<perGameDir>/<GameName>_<epoch>.zip`. */
    fun perGameBackupFile(gameName: String, epochMillis: Long): File =
        File(perGameDir(gameName), "${sanitize(gameName)}_$epochMillis.zip")

    /** Filesystem-safe per-game identity key (wmClass when present, else the shortcut filename). */
    fun key(shortcut: Shortcut): String =
        sanitize(SaveLocator.gameKey(shortcut.wmClass, shortcut.file.name))

    /** The auto-on-exit snapshot file for this game (one current backup, overwritten each exit). */
    private fun vaultFile(shortcut: Shortcut): File = File(perGameDir(shortcut.name), AUTO_LATEST)

    /** Whether this game already has a vault snapshot. */
    fun hasBackup(shortcut: Shortcut): Boolean = vaultFile(shortcut).isFile

    /** Last snapshot time (epoch millis), or 0 if none. */
    fun backupTimeMillis(shortcut: Shortcut): Long {
        val f = vaultFile(shortcut)
        return if (f.isFile) f.lastModified() else 0L
    }

    data class VaultResult(
        val ok: Boolean,
        val fileCount: Int,
        val path: String?,
        val error: String?,
        /** True when nothing per-game was discovered and the whole container was backed up instead. */
        val wholeContainer: Boolean = false,
    )

    /** Per-custom-game row for the Save Manager's Custom tab — local vault status only, no cloud. */
    data class CustomGameStatus(
        val shortcut: Shortcut,
        val name: String,
        val containerLabel: String?,
        val hasBackup: Boolean,
        val lastBackupMillis: Long,
    )

    /**
     * Enumerate every CUSTOM (non-store) shortcut across all containers and build its local-vault
     * status. Custom = neither a Steam, an Epic, nor a GOG game (each has its own tab): `storeSource`
     * is not "steam"/"epic"/"gog" AND the exec path is not under `steam_games`/`epic_games`/`gog_games`.
     * Sorted no-backup-first, then by name. BLOCKING (loads containers + scans desktop dirs) — call
     * off the main thread.
     */
    fun listStatuses(context: Context): List<CustomGameStatus> {
        val shortcuts = try {
            ContainerManager(context).loadShortcuts()
        } catch (t: Throwable) {
            Log.w(TAG, "listStatuses: loadShortcuts failed", t)
            return emptyList()
        }
        return shortcuts
            .filter { isCustom(it) }
            .map { sc ->
                CustomGameStatus(
                    shortcut = sc,
                    name = sc.name,
                    containerLabel = sc.container?.name?.takeIf { it.isNotBlank() },
                    hasBackup = hasBackup(sc),
                    lastBackupMillis = backupTimeMillis(sc),
                )
            }
            .sortedWith(compareBy({ it.hasBackup }, { it.name.lowercase() }))
    }

    /**
     * Mirror of ShortcutsScreen's store-origin gate, inverted: true only for genuinely custom games.
     * Excludes ALL storefronts — Steam, Epic, and GOG each own a dedicated Save Manager tab, so a
     * store-tagged shortcut (or one whose exec lives under `steam_games`/`epic_games`/`gog_games`)
     * must not also leak into the Custom tab. NB: GOG shortcuts are written UNTAGGED (StarLaunchBridge's
     * legacy overload stamps no `storeSource`), so the load-bearing GOG exclusion is the `gog_games`
     * path segment; the `"gog"` storeSource branch is defensive / forward-compat.
     */
    private fun isCustom(shortcut: Shortcut): Boolean {
        when (shortcut.getExtra("storeSource")) {
            "steam", "epic", "gog" -> return false
        }
        val p = shortcut.path ?: return true
        return !p.contains("steam_games", ignoreCase = true) &&
            !p.contains("epic_games", ignoreCase = true) &&
            !p.contains("gog_games", ignoreCase = true)
    }

    /**
     * Snapshot this game's saves into `<vault>/<key>.zip`, overwriting any previous snapshot.
     * Discovers the game's save roots via [SaveLocator] (persisted sidecar first, else a fresh
     * discover); if none are found this is a no-op ("no saves"). SYNCHRONOUS — run off the main
     * thread. Never throws.
     *
     * Writes to a temp sibling then atomically renames over the current zip, so a killed process (the
     * emulator restarts on exit) can never leave a half-written snapshot in place of the last good one.
     */
    fun snapshot(context: Context, container: Container, shortcut: Shortcut): VaultResult {
        return try {
            val gameKey = SaveLocator.gameKey(shortcut.wmClass, shortcut.file.name)
            val roots: List<String> = run {
                val saved = SaveLocator.loadMap(container, gameKey)
                if (saved != null && saved.roots.isNotEmpty()) saved.roots
                else SaveLocator.discover(container, shortcut.name, shortcut.path ?: "", shortcut.wmClass ?: "")
                    .map { it.relPath }
            }
            if (roots.isEmpty()) return VaultResult(false, 0, null, "no saves")

            val out = vaultFile(shortcut)
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, out.name + ".tmp")

            val res = GameSaveBackup.backupToFile(container, roots, GameSaveBackup.BackupLayout.WINLATOR, tmp)
            if (!res.ok) {
                tmp.delete()
                return VaultResult(false, res.fileCount, null, res.error)
            }

            // Overwrite the current snapshot atomically (rename; copy-fallback if rename is refused).
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "vault snapshot \"${shortcut.name}\" → ${res.fileCount} files (${out.name})")
            VaultResult(true, res.fileCount, out.absolutePath, null)
        } catch (t: Throwable) {
            Log.w(TAG, "vault snapshot failed for \"${shortcut.name}\"", t)
            VaultResult(false, 0, null, t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Restore this game's latest vault snapshot into [targetContainer], reusing [GameSaveBackup.restore]
     * (off the UI thread; posts [onResult] on the main thread). If there is no snapshot, reports a
     * failure result rather than doing nothing silently.
     */
    fun restoreLatest(
        context: Context,
        shortcut: Shortcut,
        targetContainer: Container,
        onResult: (GameSaveBackup.RestoreResult) -> Unit,
    ) {
        val f = vaultFile(shortcut)
        if (!f.isFile) {
            Handler(Looper.getMainLooper()).post {
                onResult(GameSaveBackup.RestoreResult(false, 0, "No vault backup for this game"))
            }
            return
        }
        GameSaveBackup.restore(context, Uri.fromFile(f), targetContainer, onResult)
    }

    /**
     * Manual "Back up saves": discover the game's save roots (persisted sidecar first, else a fresh
     * discover; whole-container fallback when none found) and zip them to a timestamped
     * `<perGameDir>/<GameName>_<epoch>.zip` in the chosen [layout]. Shared by the shortcut ⋮ menu AND
     * the Save Manager Custom tab so both agree. Off the UI thread; posts [onResult] on the main
     * thread. [VaultResult.wholeContainer] is true when the whole-container fallback was used.
     */
    fun manualBackup(
        context: Context,
        container: Container,
        shortcut: Shortcut,
        layout: GameSaveBackup.BackupLayout,
        onResult: (VaultResult) -> Unit,
    ) {
        Thread {
            val res = try {
                val gameKey = SaveLocator.gameKey(shortcut.wmClass, shortcut.file.name)
                val saved = SaveLocator.loadMap(container, gameKey)
                val roots = if (saved != null && saved.roots.isNotEmpty()) saved.roots
                    else SaveLocator.discover(container, shortcut.name, shortcut.path ?: "", shortcut.wmClass ?: "")
                        .map { it.relPath }
                // Empty → whole-container backup (roots = null), matching the Containers "whole" scope.
                val effectiveRoots: List<String>? = if (roots.isEmpty()) null else roots
                val outFile = perGameBackupFile(shortcut.name, System.currentTimeMillis())
                val r = GameSaveBackup.backupToFile(container, effectiveRoots, layout, outFile)
                VaultResult(r.ok, r.fileCount, r.path, r.error, wholeContainer = effectiveRoots == null)
            } catch (t: Throwable) {
                Log.w(TAG, "manual backup failed for \"${shortcut.name}\"", t)
                VaultResult(false, 0, null, t.message ?: t.javaClass.simpleName)
            }
            Handler(Looper.getMainLooper()).post { onResult(res) }
        }.start()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "game" }
}
