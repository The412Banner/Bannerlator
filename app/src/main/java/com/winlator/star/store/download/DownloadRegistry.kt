package com.winlator.star.store.download

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Store-agnostic, observable registry every storefront's downloader routes through —
 * the Kotlin/StateFlow port of BannerHub's `BhDownloadService` static registry. One
 * registry, all stores; StateFlow replaces the Java listener fan-out (a Compose
 * screen just collects [entries] / [activeCount]).
 *
 * Thread-safe: mutations arrive off background download threads. All map writes go
 * through [MutableStateFlow.update]'s atomic CAS loop — no locks needed.
 *
 * ── Where the phases attach ────────────────────────────────────────────────────
 * Phase 2 (Steam producer): [com.winlator.star.store.SteamDepotDownloader] is the
 *   first producer. When it starts a download it will `upsert()` a QUEUED entry with
 *   `pause`/`cancel` wired to its `DownloadControl`, then translate each
 *   `DownloadProgress:appId:iDone:iTotal:dDone:dTotal` event into
 *   `update("STEAM:$appId") { it.copy(state = DOWNLOADING, installDone = i…, …) }`,
 *   and finally `update{ it.copy(state = INSTALLED, installPath = …) }` on
 *   `DownloadComplete` (which auto-persists to the library). A thin adapter that
 *   subscribes to `SteamRepository`'s event flow is the intended glue — this data
 *   layer stays store-agnostic and imports no Steam types.
 * Phase 3 (Compose consumer): a screen collects [entries] with
 *   `collectAsStateWithLifecycle()`, keys a `LazyColumn` on `entry.key`, renders the
 *   two-bar model from the byte pairs, and calls `entry.pause?.invoke()` /
 *   `entry.cancel?.invoke()`. The ⬇ toolbar badge collects [activeCount].
 */
object DownloadRegistry {

    // Backing store: key ("STEAM:220") -> entry. Single source of truth for the UI,
    // including INSTALLED rows (seeded from the durable library on init).
    private val _entries = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())

    // Own scope so the derived StateFlows survive as long as the process (this is a
    // process-wide singleton, mirroring the Java static registry's lifetime).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Observable, sorted list for the Download Manager screen: active downloads first
     * (DOWNLOADING → QUEUED → PAUSED), then failures, then the installed library, then
     * dismissed rows; ties broken by name. Derived; recomputed on every mutation.
     */
    val entries: StateFlow<List<DownloadEntry>> =
        _entries
            .map { it.values.sortedWith(DISPLAY_ORDER) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Count of active entries (QUEUED/DOWNLOADING/PAUSED) — drives the ⬇ badge. */
    val activeCount: StateFlow<Int> =
        _entries
            .map { m -> m.values.count { it.isActive } }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * Live view of the durable library (the INSTALLED subset), as [LibraryEntry]s.
     * Backed by [entries] so it's always consistent with the on-screen state.
     */
    val library: StateFlow<List<LibraryEntry>> =
        _entries
            .map { m ->
                m.values
                    .filter { it.state == DownloadState.INSTALLED }
                    .map { it.toLibraryEntry() }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // -------------------------------------------------------------------------
    // Init / persistence
    // -------------------------------------------------------------------------

    private const val PREFS_LIBRARY = "bh_library"
    private const val LIB_SEP = "\n"

    private lateinit var libPrefs: SharedPreferences

    // Retained application Context so store-agnostic collaborators that have no Context of
    // their own (e.g. StoreDownloadHooks, which is a stateless object) can start the shared
    // download foreground service / post notifications. Always the *application* context —
    // never an Activity — so it's leak-safe to hold for the process lifetime. Null until
    // the first init(); callers treat a null as "not ready yet" and no-op.
    @Volatile private var appCtx: Context? = null

    /** Application Context captured at [init], or null before the first init. Leak-safe. */
    fun appContext(): Context? = appCtx

    /**
     * Initialise the durable-library store and seed the registry with the previously
     * installed games. Call once early (e.g. from the app / a store main activity),
     * like [com.winlator.star.store.SteamPrefs.init]. Idempotent.
     */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext   // publish first so it's set even on the early-return path
        if (::libPrefs.isInitialized) return
        libPrefs = ctx.applicationContext.getSharedPreferences(PREFS_LIBRARY, Context.MODE_PRIVATE)
        val loaded = loadLibrary().associateBy { it.key }
        if (loaded.isNotEmpty()) {
            _entries.update { it + loaded }
        }
    }

    // -------------------------------------------------------------------------
    // Mutations (thread-safe via atomic StateFlow.update)
    // -------------------------------------------------------------------------

    /** Insert or replace an entry by its [DownloadEntry.key]. Persists if INSTALLED. */
    fun upsert(entry: DownloadEntry) {
        val was = _entries.value[entry.key]?.state
        _entries.update { it + (entry.key to entry) }
        writeThrough(entry)
        stateSound(was, entry.state)
    }

    /**
     * Atomically mutate an existing entry (no-op if absent). Use for progress ticks
     * and state transitions, e.g. `update(key) { it.copy(pct = 42, …) }`. The
     * transient pause/cancel handles ride along through `copy()` for free.
     */
    fun update(key: String, transform: (DownloadEntry) -> DownloadEntry) {
        var updated: DownloadEntry? = null
        var was: DownloadState? = null
        _entries.update { map ->
            val cur = map[key] ?: return@update map
            val next = transform(cur)
            was = cur.state
            updated = next
            // key can legally change (shouldn't in practice); re-key defensively.
            if (next.key == key) map + (key to next)
            else map - key + (next.key to next)
        }
        updated?.let { writeThrough(it); stateSound(was, it.state) }
    }

    /** UI sound for a download that starts, finishes or fails. */
    private fun stateSound(was: DownloadState?, now: DownloadState) {
        if (was == now) return
        val cue = when (now) {
            DownloadState.INSTALLED -> if (was != null) com.winlator.star.sound.UiCue.TASK_DONE else null
            DownloadState.FAILED -> com.winlator.star.sound.UiCue.ERROR
            DownloadState.QUEUED, DownloadState.DOWNLOADING ->
                if (was == null || was == DownloadState.FAILED || was == DownloadState.CANCELLED || was == DownloadState.INSTALLED) {
                    com.winlator.star.sound.UiCue.TASK_START
                } else null
            else -> null
        } ?: return
        com.winlator.star.sound.UiSounds.cue(cue)
    }

    /** Remove an entry (e.g. user dismissed a failed/cancelled row). Also drops it
     *  from the durable library if it was installed. */
    fun remove(key: String) {
        _entries.update { it - key }
        removeLibraryEntry(key)
    }

    /** Drop every entry AND wipe the durable library. */
    fun clear() {
        _entries.update { emptyMap() }
        clearLibrary()
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    fun get(key: String): DownloadEntry? = _entries.value[key]

    /** The INSTALLED rows right now (not via the derived [entries] flow, which updates a moment later). */
    fun installedNow(): List<DownloadEntry> = _entries.value.values.filter { it.state == DownloadState.INSTALLED }

    /** True if an active (QUEUED/DOWNLOADING/PAUSED) entry exists for [key]. */
    fun isActive(key: String): Boolean = _entries.value[key]?.isActive == true

    // -------------------------------------------------------------------------
    // Durable library (bh_library-style pref)
    // -------------------------------------------------------------------------
    // Value format, LIB_SEP('\n')-joined: name \n store \n installPath [\n cover].
    // Cover is an optional 4th field (Steam covers are derivable from appId, so it's
    // only meaningful for Epic/GOG/Amazon url-based art); parsed defensively.

    /** Remove one installed game from the durable library and the live registry. */
    fun removeLibraryEntry(key: String) {
        if (::libPrefs.isInitialized) libPrefs.edit().remove(key).apply()
        // Only strip it from the map if it's the installed row (don't clobber a
        // fresh re-download the user may have just re-queued under the same key).
        _entries.update { map ->
            if (map[key]?.state == DownloadState.INSTALLED) map - key else map
        }
    }

    /** Wipe the entire durable library and its INSTALLED rows. */
    fun clearLibrary() {
        if (::libPrefs.isInitialized) libPrefs.edit().clear().apply()
        _entries.update { map -> map.filterValues { it.state != DownloadState.INSTALLED } }
    }

    /** Persist to the library only when INSTALLED; other states are in-memory only. */
    private fun writeThrough(entry: DownloadEntry) {
        if (!::libPrefs.isInitialized) return
        if (entry.state != DownloadState.INSTALLED) return
        val val0 = buildString {
            append(entry.name).append(LIB_SEP)
            append(entry.store.name).append(LIB_SEP)
            append(entry.installPath ?: "")
            entry.cover?.let { append(LIB_SEP).append(it) }
        }
        libPrefs.edit().putString(entry.key, val0).apply()
    }

    private fun loadLibrary(): List<DownloadEntry> {
        if (!::libPrefs.isInitialized) return emptyList()
        return libPrefs.all.mapNotNull { (key, raw) ->
            val parts = (raw?.toString() ?: "").split(LIB_SEP, limit = 4)
            val store = runCatching { Store.valueOf(parts.getOrElse(1) { "" }) }.getOrNull()
                ?: return@mapNotNull null
            val id = key.substringAfter(':', "")
            DownloadEntry(
                store = store,
                id = id,
                name = parts.getOrElse(0) { "" },
                cover = parts.getOrNull(3),
                state = DownloadState.INSTALLED,
                pct = 100,
                installPath = parts.getOrElse(2) { "" },
            )
        }
    }

    private fun DownloadEntry.toLibraryEntry() =
        LibraryEntry(key, name, store, installPath ?: "", cover)

    // -------------------------------------------------------------------------
    // Display ordering
    // -------------------------------------------------------------------------

    private val STATE_RANK = mapOf(
        DownloadState.DOWNLOADING to 0,
        DownloadState.QUEUED to 1,
        DownloadState.PAUSED to 2,
        DownloadState.FAILED to 3,
        DownloadState.INSTALLED to 4,
        DownloadState.CANCELLED to 5,
    )

    private val DISPLAY_ORDER = compareBy<DownloadEntry>(
        { STATE_RANK[it.state] ?: Int.MAX_VALUE },
        { it.name.lowercase() },
        { it.key },
    )
}
