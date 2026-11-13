package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Steam-side seeding for the cross-store Download Manager (Phase 2).
 *
 * Bridges the Steam DB into the store-agnostic [DownloadRegistry] so the Library
 * section isn't empty on first open: every already-installed Steam game (the
 * `steam_games` rows with `is_installed = 1` and a real `install_dir`) is upserted
 * as an INSTALLED entry, which the registry persists to its durable library.
 *
 * This lives on the Steam side ON PURPOSE — the registry imports zero Steam types.
 * All Steam knowledge (the DB, [SteamRepository], the appId→cover convention) stays
 * here; the registry only ever sees normalized [DownloadEntry]s.
 *
 * Call once at Steam startup, right after [SteamRepository.initialize] and
 * [DownloadRegistry.init] (see [SteamForegroundService]). Idempotent.
 */
object SteamLibrarySync {

    private const val TAG = "SteamLibrarySync"

    private val seeded = AtomicBoolean(false)

    /**
     * One-time sync of the installed Steam library into the registry. Safe to call
     * repeatedly; only the first call does work. Never throws — a seeding failure must
     * not take down Steam startup.
     */
    fun seed(ctx: Context) {
        if (!seeded.compareAndSet(false, true)) return
        try {
            val db = SteamRepository.getInstance().getDatabase()
            val installed = db.getInstalledGames()
            var added = 0
            for (g in installed) {
                if (g.installDir.isNullOrEmpty()) continue
                val key = "${Store.STEAM}:${g.appId}"
                // Don't clobber a live/queued download that may already own this key.
                if (DownloadRegistry.isActive(key)) continue
                DownloadRegistry.upsert(DownloadEntry(
                    store = Store.STEAM,
                    id = g.appId.toString(),
                    name = g.name,
                    cover = g.appId.toString(),   // GameCoverArt resolves Steam art by appId
                    state = DownloadState.INSTALLED,
                    pct = 100,
                    installDone = g.sizeBytes,
                    installTotal = g.sizeBytes,
                    supportsPause = true,
                    installPath = g.installDir,
                ))
                added++
            }
            Log.i(TAG, "Seeded $added installed Steam game(s) into DownloadRegistry")
            // Downloads the app was interrupted in come back as paused rows the Download Manager can resume.
            SteamDownloadControl.seedInterrupted()
        } catch (t: Throwable) {
            // Reset so a later call (once the DB is ready) can retry.
            seeded.set(false)
            Log.w(TAG, "Library seed skipped: ${t.message}")
        }
    }
}
