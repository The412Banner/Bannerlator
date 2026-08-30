package com.winlator.star.store

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.winlator.star.core.CopyGameToDriveC
import com.winlator.star.core.StorageRoots
import com.winlator.star.core.WinePath
import java.io.File

/**
 * SD-card install target for a Steam download.
 *
 * Steam games install to internal app-private storage (`imagefs/steam_games`) by default — real
 * ext4/f2fs, fast, but on the same flash as everything else. On a device with a physical SD card the
 * user can instead park a game on the card to free internal space. The card is FUSE-backed (like
 * `/storage/emulated/0`), so streaming-heavy games can stall on intro-movies / asset loads the same
 * way shared storage does — the same reason "Copy game to Drive C" exists ([CopyGameToDriveC]). So
 * this is an explicit, off-by-default opt-in surfaced with a warning, never the default.
 *
 * The install still runs through Wine via a drive letter: an SD install dir is mapped to a container
 * drive by [WinePath.resolveWindowsPath] at shortcut-creation time, exactly like the "+" add-game
 * importer, so no launch/mount code changes.
 */
object SteamSdInstall {
    private const val TAG = "SteamSdInstall"

    /** Sub-path under an SD volume root that Steam games land in: `<sd>/bannerlator/steam_games`. */
    const val SD_SUBDIR = "bannerlator/steam_games"

    /** Headroom kept free on top of the install size, shared with [CopyGameToDriveC]'s copy guard. */
    val FREE_SPACE_MARGIN = CopyGameToDriveC.FREE_SPACE_MARGIN

    /** A usable SD-card target: its volume root, a display label, and the free bytes on it. */
    data class SdTarget(val root: File, val label: String, val freeBytes: Long) {
        /** The `<sd>/bannerlator/steam_games` base a game's own folder is created under. */
        val steamGamesBase: File get() = File(root, SD_SUBDIR)
    }

    /**
     * The first mounted, removable SD-card volume, or null when the device has none.
     *
     * Reuses [StorageRoots] (the same enumeration the file manager's drive menu and the container
     * drive-map pre-declaration use) so we honour the framework's authoritative volume list rather
     * than a bare `/storage` listing. We only accept an entry whose reported dir is a genuine volume
     * ROOT (matching ContainerDetailViewModel's conservative stance): a degraded entry pointing part
     * way down a card would make [WinePath.resolveWindowsPath] mount a sub-folder instead of the card.
     */
    fun detect(context: Context): SdTarget? {
        val root = runCatching { StorageRoots.list(context) }.getOrNull().orEmpty()
            .firstOrNull { it.removable && WinePath.storageVolumeRootOf(it.dir.absolutePath) == it.dir.absolutePath }
            ?: return null
        return SdTarget(root.dir, root.label, freeBytes(root.dir))
    }

    /** Free bytes on the volume [dir] lives on. Falls back to 0 (treated as "no room") on failure. */
    fun freeBytes(dir: File): Long = try {
        StatFs(dir.absolutePath).availableBytes
    } catch (e: Exception) {
        Log.w(TAG, "StatFs on ${dir.absolutePath} failed", e)
        0L
    }

    /** Short human size for the dialog's free-space line ("12.3 GB free"). */
    fun fmtBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.0f MB".format(bytes / 1_048_576.0)
        else                    -> "%.0f KB".format(bytes / 1024.0)
    }
}
