package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Steam-side seeding for the cross-store Download Manager (Phase 2).
 *
 * Bridges the Steam DB into the store-agnostic [DownloadRegistry] so the Library
 * section isn't empty on first open: an installed Steam game (a `steam_games` row with
 * `is_installed = 1` and a real `install_dir`) is upserted as an INSTALLED entry, which
 * the registry persists to its durable library. A runtime, redistributable or other tool
 * is left out, and so is a game the library import merely marked installed — a game found
 * in a library folder is [SteamLibraryFound]'s, whose row carries [DownloadEntry.found].
 * The folder the user brought by "Import game library" keeps an ordinary card (its marker
 * says so), as does a game this app downloaded.
 *
 * This lives on the Steam side ON PURPOSE — the registry imports zero Steam types.
 * All Steam knowledge (the DB, [SteamRepository], the appId→cover convention) stays
 * here; the registry only ever sees normalized [DownloadEntry]s.
 *
 * Call [seed] once at Steam startup, right after [SteamRepository.initialize] and
 * [DownloadRegistry.init] (see [SteamForegroundService]); [seedInstalled] may run again
 * at any time. Idempotent.
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
            seedInstalled(ctx)
            // Downloads the app was interrupted in come back as paused rows the Download Manager can resume.
            SteamDownloadControl.seedInterrupted()
        } catch (t: Throwable) {
            // Reset so a later call (once the DB is ready) can retry.
            seeded.set(false)
            Log.w(TAG, "Library seed skipped: ${t.message}")
        }
    }

    /**
     * [seed] without the once-per-process gate: the installed games are read again and their rows upserted, and a row an older
     * build left for a tool or a runtime is dropped. A game the library import merely marked installed keeps whatever card it
     * already has (its card is [SteamLibraryFound]'s); it is not removed here, because that hid it while a library was unmounted
     * or before its type was known. Runs when a library folder is added and on every library scan, so a game installed while the
     * app runs reaches Downloads then, not at the next start. Idempotent (the key is the same). Never throws.
     */
    fun seedInstalled(ctx: Context) {
        try {
            val app = ctx.applicationContext
            // A seed started from Settings must load the persisted found cards too, or the reads and removals below would run
            // against an empty registry.
            DownloadRegistry.init(app)
            val db = SteamRepository.getInstance().getDatabase()
            val installed = db.getInstalledGames()
            var added = 0
            for (g in installed) {
                if (g.installDir.isNullOrEmpty()) continue
                val key = "${Store.STEAM}:${g.appId}"
                // An ignored game is never seeded here: this seeder creates normal cards, and a removed game may only reach
                // Downloads through the found scan (which the show setting governs). When that setting hides it, drop its whole
                // terminal card — an INSTALLED one or a failed/cancelled attempt — but never a live download.
                if (SteamLibraryImport.isIgnored(app, g.appId)) {
                    if (SteamLibraryImport.isHiddenFromDownloads(app, g.appId)) {
                        DownloadRegistry.removeTerminal(key)
                    }
                    continue
                }
                // A failed download/update keeps its error: never overwrite that row with INSTALLED.
                val current = DownloadRegistry.get(key)
                if (current != null && current.state != DownloadState.INSTALLED) continue
                // A runtime, redistributable or tool in the library is not a game: the Steam tab may list what the account owns,
                // but Downloads only lists games. A row an older build seeded for it goes too.
                if (!SteamLibraryFound.isGame(g.appId, File(g.installDir).name, g.name)) {
                    if (!DownloadRegistry.isActive(key)) DownloadRegistry.removeLibraryEntry(key)
                    continue
                }
                // A game the library import merely marked installed was never downloaded here: its Downloads card is the found
                // scan's. Keep the existing card instead of removing it — the removal hid it while an SD library was unmounted
                // or before its type was known/online; the found scan replaces it when it can. A folder the user brought by
                // "Import game library" keeps an ordinary card (its marker says so), and so does a game this app downloaded.
                val adopted = File(g.installDir, SteamInstallPaths.ADOPTED_MARKER).exists()
                if (SteamLibraryImport.isImported(app, g.appId) && !adopted) continue
                // Don't clobber a live/queued download that may already own this key.
                if (DownloadRegistry.isActive(key) || DownloadQueue.isQueued(g.appId)) continue
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
            if (added > 0) Log.i(TAG, "Seeded $added installed Steam game(s) into DownloadRegistry")
        } catch (t: Throwable) {
            Log.w(TAG, "Installed-game seed failed: ${t.message}")
        }
    }
}
