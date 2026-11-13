package com.winlator.star.store

import com.winlator.star.store.download.DownloadState
import java.io.File

/**
 * Pure decisions of the Download Manager's pause / resume controls, kept free of Android types so they run in JVM tests
 * (see [SteamDownloadControl] and [EpicDownloadControl], which act on them).
 */

/** What the Pause / Resume button of a card does for a download in a given state. */
internal enum class PauseToggle { PAUSE, RESUME, NONE }

/** Running or waiting downloads pause, a paused one resumes, anything else (installed, failed, cancelled) has nothing to toggle. */
internal fun pauseToggleFor(state: DownloadState): PauseToggle = when (state) {
    DownloadState.DOWNLOADING, DownloadState.QUEUED -> PauseToggle.PAUSE
    DownloadState.PAUSED -> PauseToggle.RESUME
    DownloadState.INSTALLED, DownloadState.FAILED, DownloadState.CANCELLED -> PauseToggle.NONE
}

/** Whole percent of [done] out of [total], 0..100; 0 while the total is unknown. */
internal fun downloadPercent(done: Long, total: Long): Int =
    if (total <= 0L) 0 else (done.coerceAtLeast(0L) * 100L / total).toInt().coerceIn(0, 100)

/**
 * The status line of a PAUSED card: where it stopped ([pct], with the optional byte pair [sizePart]) and, when a Resume was refused
 * before any transfer began (no login, no manifest), the reason [error], so the card says why instead of silently staying paused.
 */
internal fun pausedStatusText(pct: Int, sizePart: String, error: String?): String =
    "Paused — $pct%$sizePart" + (error?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")

/**
 * True when the shortcuts of an uninstalled game should go with it: the files were really removed ([ok], not [folderKept] for another
 * game or client, not [imported] from another Steam client). A folder that still stands still holds the exes other shortcuts run.
 */
internal fun shouldRemoveShortcuts(ok: Boolean, folderKept: Boolean, imported: Boolean): Boolean =
    ok && !folderKept && !imported

/** What cancelling a paused Epic download leaves behind. */
internal enum class EpicCancelOutcome { RESTORE_INSTALLED, DELETE_PARTIAL, KEEP_PARTIAL }

/**
 * The outcome of cancelling a paused Epic download: an update of an [installed] game goes back to installed whatever was chosen (the
 * game's files are not the partial download), a fresh install deletes its partial files when [deleteChosen], else keeps them.
 */
internal fun epicCancelOutcome(installed: Boolean, deleteChosen: Boolean): EpicCancelOutcome = when {
    installed -> EpicCancelOutcome.RESTORE_INSTALLED
    deleteChosen -> EpicCancelOutcome.DELETE_PARTIAL
    else -> EpicCancelOutcome.KEEP_PARTIAL
}

/**
 * True when [installDir] still holds something a resumed Epic install can build on: the folder exists and has at least one entry
 * (the `.chunks` resume cache, a half-written file, a finished file). A missing folder or an empty one means the paused download has
 * nothing left to resume, so it is not offered again after a restart.
 */
internal fun epicHasPartialData(installDir: File?): Boolean {
    if (installDir == null || !installDir.isDirectory) return false
    return installDir.list()?.isNotEmpty() == true
}

/** Where a paused Epic download stopped, as kept in prefs between runs of the app. */
internal data class EpicPausedInfo(val installDir: String, val pct: Int, val done: Long, val total: Long)

/** One prefs line for [EpicPausedInfo]: install dir, percent, bytes done, bytes total, '\n'-joined (a path never holds a newline). */
internal fun encodeEpicPaused(info: EpicPausedInfo): String =
    listOf(info.installDir, info.pct.toString(), info.done.toString(), info.total.toString()).joinToString("\n")

/** The inverse of [encodeEpicPaused]; a bare path (no numbers) still reads, as 0 / 0 / 0. Null for a blank or non-text value. */
internal fun decodeEpicPaused(raw: String?): EpicPausedInfo? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split('\n')
    val dir = parts[0].takeIf { it.isNotBlank() } ?: return null
    return EpicPausedInfo(
        installDir = dir,
        pct = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 100),
        done = (parts.getOrNull(2)?.toLongOrNull() ?: 0L).coerceAtLeast(0L),
        total = (parts.getOrNull(3)?.toLongOrNull() ?: 0L).coerceAtLeast(0L),
    )
}
