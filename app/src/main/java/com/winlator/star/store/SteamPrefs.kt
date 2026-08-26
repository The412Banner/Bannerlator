package com.winlator.star.store

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists Steam session data in SharedPreferences "steam_prefs".
 *
 * Initialised once via SteamPrefs.init(ctx) — call this from SteamMainActivity.onCreate().
 * After that the object is safe to read/write from any thread.
 */
object SteamPrefs {

    private const val PREFS_NAME = "steam_prefs"

    private const val K_USERNAME         = "username"
    private const val K_REFRESH_TOKEN    = "refresh_token"
    private const val K_STEAM_ID_64      = "steam_id_64"
    private const val K_ACCOUNT_ID       = "account_id"
    private const val K_DISPLAY_NAME     = "display_name"
    private const val K_CELL_ID          = "cell_id"
    private const val K_LAST_PICS_CHANGE = "last_pics_change"

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** Steam account name (login name, not display name). */
    var username: String
        get() = prefs.getString(K_USERNAME, "") ?: ""
        set(v) { prefs.edit().putString(K_USERNAME, v).apply() }

    /**
     * Long-lived Steam refresh token (returned by AuthPollResult.refreshToken).
     * Stored plaintext — acceptable for a sideloaded APK on a personal device.
     */
    var refreshToken: String
        get() = prefs.getString(K_REFRESH_TOKEN, "") ?: ""
        set(v) { prefs.edit().putString(K_REFRESH_TOKEN, v).apply() }

    /** 64-bit SteamID. */
    var steamId64: Long
        get() = prefs.getLong(K_STEAM_ID_64, 0L)
        set(v) { prefs.edit().putLong(K_STEAM_ID_64, v).apply() }

    /** 32-bit account ID (lower 32 bits of SteamID). */
    var accountId: Int
        get() = prefs.getInt(K_ACCOUNT_ID, 0)
        set(v) { prefs.edit().putInt(K_ACCOUNT_ID, v).apply() }

    /** Steam display / persona name. */
    var displayName: String
        get() = prefs.getString(K_DISPLAY_NAME, "") ?: ""
        set(v) { prefs.edit().putString(K_DISPLAY_NAME, v).apply() }

    /** Steam cell ID — returned by LoggedOnCallback, used for server selection. */
    var cellId: Int
        get() = prefs.getInt(K_CELL_ID, 0)
        set(v) { prefs.edit().putInt(K_CELL_ID, v).apply() }

    /**
     * Last PICS change number seen. Used for incremental library sync.
     * 0 means full sync required.
     */
    var lastPicsChangeNumber: Int
        get() = prefs.getInt(K_LAST_PICS_CHANGE, 0)
        set(v) { prefs.edit().putInt(K_LAST_PICS_CHANGE, v).apply() }

    /** True if a session exists (refresh token present). */
    val isLoggedIn: Boolean
        get() = refreshToken.isNotEmpty() && username.isNotEmpty()

    // ── Goldberg (gbe_fork) per-game emulator mode ───────────────────────────
    // Keyed by appId so it survives uninstall/reinstall of the app's session.
    // Stored here (not in SteamDatabase) to avoid a Room migration + versionCode
    // bump — it's per-game install config, not credentials, so clear() leaves it.

    private const val K_GOLDBERG_PREFIX = "goldberg_mode_"

    /** Persisted Goldberg mode for [appId]; unknown/absent → OFF. */
    fun getGoldbergMode(appId: Int): GoldbergMode =
        GoldbergMode.fromKey(prefs.getString(K_GOLDBERG_PREFIX + appId, null))

    /** Persist the chosen Goldberg mode for [appId]. */
    fun setGoldbergMode(appId: Int, mode: GoldbergMode) {
        prefs.edit().putString(K_GOLDBERG_PREFIX + appId, mode.name).apply()
    }

    // ── DLC picker: per-game EXCLUDED DLC (opt-out) ──────────────────────────
    // Owned DLC downloads with the game by default; the picker lets the user opt
    // OUT of specific DLC. We store the excluded set (CSV of DLC appIds) rather
    // than the included set, so the default (nothing stored) = include everything.
    // Per-game user preference (not synced library data), so it lives here.

    private const val K_EXCLUDED_DLC_PREFIX = "excluded_dlc_"

    /** DLC appIds the user has opted OUT of for [appId]. Empty = include all owned DLC. */
    fun getExcludedDlc(appId: Int): Set<Int> {
        val csv = prefs.getString(K_EXCLUDED_DLC_PREFIX + appId, "") ?: ""
        if (csv.isEmpty()) return emptySet()
        return csv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    /** Persist the DLC appIds the user opted out of for [appId] (empty clears it). */
    fun setExcludedDlc(appId: Int, excluded: Set<Int>) {
        val csv = excluded.joinToString(",")
        prefs.edit().putString(K_EXCLUDED_DLC_PREFIX + appId, csv).apply()
    }

    // ── Beta-branch selector: per-game SELECTED branch ───────────────────────
    // The branch the user chose on the detail page to download/install. Default
    // "public" = the normal stable build; anything else is a beta branch (which
    // may require a verified access code, stored in SteamDatabase). Per-game user
    // preference (not synced library data), so it lives here like the DLC opt-out.

    private const val K_SELECTED_BRANCH_PREFIX = "selected_branch_"

    /** The branch chosen for [appId], or "public" (the stable default) if none set. */
    fun getSelectedBranch(appId: Int): String =
        prefs.getString(K_SELECTED_BRANCH_PREFIX + appId, "public") ?: "public"

    /** Persist the branch chosen for [appId]. Empty/blank falls back to "public". */
    fun setSelectedBranch(appId: Int, branch: String) {
        val value = branch.ifBlank { "public" }
        prefs.edit().putString(K_SELECTED_BRANCH_PREFIX + appId, value).apply()
    }

    // ── Achievement sync-back (SAFETY GATE, default OFF) ─────────────────────
    // When OFF (the default) the achievement pipeline is READ-ONLY: we fetch/cache/display the real
    // profile's achievements and record locally-earned unlocks to a pending queue, but NEVER write
    // them to the user's real Steam profile. Enabling this flips SteamAchievementStore.flushPendingSyncBack
    // to actually push queued unlocks via storeUserStats. Kept default-FALSE so test builds can never
    // mutate a live profile without an explicit, deliberate opt-in.

    private const val K_ACHV_SYNCBACK = "achievement_syncback_enabled"

    /** True if the user has explicitly opted into pushing locally-earned achievements back to their
     *  real Steam profile. DEFAULT FALSE — the pipeline is read-only until this is turned on. */
    fun isAchievementSyncBackEnabled(ctx: Context): Boolean {
        init(ctx)
        return prefs.getBoolean(K_ACHV_SYNCBACK, false)
    }

    /** Enable/disable achievement sync-back (writing locally-earned unlocks to the real profile). */
    fun setAchievementSyncBackEnabled(ctx: Context, v: Boolean) {
        init(ctx)
        prefs.edit().putBoolean(K_ACHV_SYNCBACK, v).apply()
    }

    // ── Steam Cloud support cache (per-app UFS verdict) ───────────────────────
    // SteamCloudSaveManager.hasCloudSupport() hits PICS to learn whether an app has a UFS cloud store.
    // That verdict is stable per app, so cache the DEFINITIVE true/false here (survives process death)
    // to avoid a PICS round-trip on every launch/exit. "Unknown" (never resolved) is represented by the
    // absence of the key — we NEVER persist an unknown, so it can always be retried later.

    private const val K_CLOUD_SUPPORT_PREFIX = "cloud_support_"

    /** Cached Steam-Cloud-support verdict for [appId]: true/false if resolved before, null if never. */
    fun getCloudSupportCached(ctx: Context, appId: Int): Boolean? {
        init(ctx)
        val key = K_CLOUD_SUPPORT_PREFIX + appId
        if (!prefs.contains(key)) return null
        return prefs.getBoolean(key, false)
    }

    /** Persist a DEFINITIVE Steam-Cloud-support verdict for [appId]. Only call with a known true/false. */
    fun setCloudSupportCached(ctx: Context, appId: Int, v: Boolean) {
        init(ctx)
        prefs.edit().putBoolean(K_CLOUD_SUPPORT_PREFIX + appId, v).apply()
    }

    /** Wipe all Steam credentials and session state. */
    fun clear() {
        prefs.edit()
            .remove(K_USERNAME)
            .remove(K_REFRESH_TOKEN)
            .remove(K_STEAM_ID_64)
            .remove(K_ACCOUNT_ID)
            .remove(K_DISPLAY_NAME)
            .remove(K_LAST_PICS_CHANGE)
            .apply()
        // Keep K_CELL_ID — it's a network routing hint, not sensitive
    }
}
