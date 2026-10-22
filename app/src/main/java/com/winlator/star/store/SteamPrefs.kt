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
    private lateinit var picsPrefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        if (!::picsPrefs.isInitialized) {
            picsPrefs = ctx.applicationContext.getSharedPreferences(PICS_INSTALLDIR_PREFS, Context.MODE_PRIVATE)
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

    /**
     * Replace the whole active-account view in ONE write (account switch — see [SteamAccountStore]).
     * The other keys of the old account that are per-account (`last_pics_change`) are dropped with it.
     */
    fun setActiveAccount(username: String, refreshToken: String, steamId64: Long, accountId: Int, displayName: String) {
        prefs.edit()
            .putString(K_USERNAME, username)
            .putString(K_REFRESH_TOKEN, refreshToken)
            .putLong(K_STEAM_ID_64, steamId64)
            .putInt(K_ACCOUNT_ID, accountId)
            .putString(K_DISPLAY_NAME, displayName)
            .remove(K_LAST_PICS_CHANGE)
            .apply()
    }

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

    // ── Friend-chat notifications (default ON) ───────────────────────────────
    // Gates whether an incoming friend message (while its chat isn't open) is posted to the Android
    // shade by SteamChatNotifier. On by default — it's the expected behaviour for a chat feature; the
    // user can turn it off in the app settings. Self-inits like the sync-back gate so the store hook
    // can read it from the CM pump without a prior init call. Not a credential, so clear() leaves it.

    private const val K_CHAT_NOTIFICATIONS = "steam_chat_notifications"

    /** True (default) if incoming friend messages should raise an Android notification. */
    fun isChatNotificationsEnabled(ctx: Context): Boolean {
        init(ctx)
        return prefs.getBoolean(K_CHAT_NOTIFICATIONS, true)
    }

    /** Enable/disable friend-chat notifications in the system shade. */
    fun setChatNotificationsEnabled(ctx: Context, v: Boolean) {
        init(ctx)
        prefs.edit().putBoolean(K_CHAT_NOTIFICATIONS, v).apply()
    }

    // ── Friend-chat notification SOUND (default ON) ──────────────────────────
    // Gates whether an incoming-message notification makes a sound / heads-up (routes it to the alert
    // channel) vs. lands quietly (the silent channel). Only meaningful while chat notifications are on.
    // Self-inits like the toggles above so SteamChatNotifier can read it from the CM pump. Preference,
    // not a credential, so clear() leaves it.

    private const val K_CHAT_SOUND = "steam_chat_sound"

    /** True (default) if incoming-message notifications should play a sound / heads-up. */
    fun isChatSoundEnabled(ctx: Context): Boolean {
        init(ctx)
        return prefs.getBoolean(K_CHAT_SOUND, true)
    }

    /** Enable/disable the sound on friend-chat notifications. */
    fun setChatSoundEnabled(ctx: Context, v: Boolean) {
        init(ctx)
        prefs.edit().putBoolean(K_CHAT_SOUND, v).apply()
    }

    // ── Library sort (A-Z / Steam rating) ─────────────────────────────────────
    private const val K_LIBRARY_SORT = "steam_library_sort"

    /**
     * The Library tab's sort, as a [LibrarySort] name. Default "NAME" (A-Z), which is how the
     * library has always been ordered, so an update never reshuffles anyone's library.
     */
    fun getLibrarySort(ctx: Context): String {
        init(ctx)
        return prefs.getString(K_LIBRARY_SORT, "NAME") ?: "NAME"
    }

    fun setLibrarySort(ctx: Context, v: String) {
        init(ctx)
        prefs.edit().putString(K_LIBRARY_SORT, v).apply()
    }

    // ── Library type filter (All / Games / Demos) ─────────────────────────────
    private const val K_LIBRARY_TYPE_FILTER = "steam_library_type_filter"

    /**
     * The Library tab's type chip, as a [LibraryTypeFilter] name. Default "GAMES" so an update
     * never silently adds demos to an existing user's library — the filter is opt-in.
     */
    fun getLibraryTypeFilter(ctx: Context): String {
        init(ctx)
        return prefs.getString(K_LIBRARY_TYPE_FILTER, "GAMES") ?: "GAMES"
    }

    fun setLibraryTypeFilter(ctx: Context, v: String) {
        init(ctx)
        prefs.edit().putString(K_LIBRARY_TYPE_FILTER, v).apply()
    }

    // ── Extra Steam library locations (store/SteamLibraryLocations owns the logic) ──
    // The user's extra install folders as a JSON array of {id,label,path}, plus the id of the location
    // the install dialog pre-selects ("" = the internal default). Preferences, not credentials — NOT
    // cleared on logout, since the folders (and the games in them) outlive a Steam sign-out.
    private const val K_LIBRARY_LOCATIONS        = "steam_library_locations"
    private const val K_DEFAULT_INSTALL_LOCATION = "steam_default_install_location"

    fun getLibraryLocationsJson(ctx: Context): String {
        init(ctx)
        return prefs.getString(K_LIBRARY_LOCATIONS, "") ?: ""
    }

    fun setLibraryLocationsJson(ctx: Context, v: String) {
        init(ctx)
        prefs.edit().putString(K_LIBRARY_LOCATIONS, v).apply()
    }

    fun getDefaultInstallLocation(ctx: Context): String {
        init(ctx)
        return prefs.getString(K_DEFAULT_INSTALL_LOCATION, "") ?: ""
    }

    fun setDefaultInstallLocation(ctx: Context, id: String) {
        init(ctx)
        prefs.edit().putString(K_DEFAULT_INSTALL_LOCATION, id).apply()
    }

    // ── PICS config.installdir per app ───────────────────────────────────────
    // Valve's folder name under steamapps/common — the one every Steam client (and the appmanifest's
    // "installdir") uses. Written by the library sync (SteamRepository.processAppKv) and read when a game
    // is installed into a steamapps library, so other clients find it by that exact name. One entry per
    // owned app, so they live in their own prefs file ([PICS_INSTALLDIR_PREFS]) rather than beside the
    // login, and not as a steam.db column so no schema change is needed. Not credentials — NOT cleared on
    // logout.
    const val PICS_INSTALLDIR_PREFS = "steam_pics_installdirs"
    const val K_PICS_INSTALLDIR_PREFIX = "pics_installdir_"

    /** The PICS `config.installdir` recorded for [appId], or "" when it has not been seen yet. */
    fun getPicsInstallDir(appId: Int): String =
        picsPrefs.getString(K_PICS_INSTALLDIR_PREFIX + appId, "") ?: ""

    /**
     * Moves installdir entries a test build wrote into the login prefs over to [PICS_INSTALLDIR_PREFS], in
     * one edit each. Cheap when there is nothing to move. Never throws.
     */
    @JvmStatic
    fun movePicsInstallDirs(ctx: Context) {
        try {
            init(ctx)
            val old = prefs.all.filterKeys { it.startsWith(K_PICS_INSTALLDIR_PREFIX) }
            if (old.isEmpty()) return
            val add = picsPrefs.edit()
            for ((k, v) in old) if (v is String) add.putString(k, v)
            add.apply()
            val drop = prefs.edit()
            for (k in old.keys) drop.remove(k)
            drop.apply()
        } catch (_: Throwable) {}
    }

    // ── Master opt-in for the whole friends/chat feature (default OFF) ────────
    // The friends list, presence sharing and chat are OFF until the user opts in. Default FALSE so a
    // brand-new sign-in shows the store working with the social side dormant (no online status shared)
    // until the user turns it on. Mirrored by two cogs (Steam store + Friends screen) and the
    // SteamFriendsStore.socialEnabled flow. A preference, not a credential — NOT cleared on logout.

    private const val K_SOCIAL_ENABLED = "steam_social_enabled"

    /** True if the user has opted into Steam friends & chat. DEFAULT FALSE — dormant until turned on. */
    fun isSocialEnabled(ctx: Context): Boolean {
        init(ctx)
        return prefs.getBoolean(K_SOCIAL_ENABLED, false)
    }

    /** Enable/disable the whole Steam friends & chat feature. */
    fun setSocialEnabled(ctx: Context, v: Boolean) {
        init(ctx)
        prefs.edit().putBoolean(K_SOCIAL_ENABLED, v).apply()
    }

    // ── "In game" presence for offline (Goldberg / Raw) launches (default ON) ──
    // When a Steam-origin game launches WITHOUT the genuine client (Goldberg or Raw), the app's own
    // CM session reports it as the game being played (CMsgClientGamesPlayed) — friends see "playing
    // <game>" and Steam records the playtime, exactly as the real client would. A RealSteam launch
    // never uses this (the genuine client reports itself while the app session is paused). Preference,
    // not a credential, so clear() leaves it. Read by XServerDisplayActivity at guest start.

    private const val K_OFFLINE_PRESENCE = "steam_offline_presence"

    /** True (default) if Goldberg / Raw launches of Steam games should show the account as in-game. */
    fun isOfflinePresenceEnabled(ctx: Context): Boolean {
        init(ctx)
        return prefs.getBoolean(K_OFFLINE_PRESENCE, true)
    }

    /** Enable/disable the in-game presence report for offline launches. */
    fun setOfflinePresenceEnabled(ctx: Context, v: Boolean) {
        init(ctx)
        prefs.edit().putBoolean(K_OFFLINE_PRESENCE, v).apply()
    }

    // ── Steam Cloud support cache (per-app UFS verdict) ───────────────────────
    // SteamCloudSaveManager.hasCloudSupport() hits PICS to learn whether an app has a UFS cloud store.
    // That verdict is stable per app, so cache the DEFINITIVE true/false here (survives process death)
    // to avoid a PICS round-trip on every launch/exit. "Unknown" (never resolved) is represented by the
    // absence of the key — we NEVER persist an unknown, so it can always be retried later.

    private const val K_CLOUD_SUPPORT_PREFIX = "cloud_support_"

    /**
     * Version of the rule that produced the cached verdicts. Rule 1 only looked at `ufs/savefiles`, so
     * every game that keeps its saves through the Steam API (a UFS quota but no savefiles rules —
     * Left 4 Dead 2, Counter-Strike: Source, …) was cached as "no cloud" and never uploaded again.
     * Rule 2 also counts the quota / file limit and a non-empty cloud manifest. The key deliberately
     * does NOT start with [K_CLOUD_SUPPORT_PREFIX], so the migration below can't mistake it for a verdict.
     */
    private const val K_CLOUD_RULE_VERSION = "cloud_rule_version"
    private const val CLOUD_RULE_VERSION = 2

    @Volatile private var cloudRuleChecked = false

    /** One-time: drop every "no cloud" verdict cached by rule 1 so those games are probed again. */
    private fun migrateCloudSupportVerdicts() {
        if (cloudRuleChecked) return
        synchronized(this) {
            if (cloudRuleChecked) return
            if (prefs.getInt(K_CLOUD_RULE_VERSION, 1) < CLOUD_RULE_VERSION) {
                val stale = prefs.all.filter { (k, v) -> k.startsWith(K_CLOUD_SUPPORT_PREFIX) && v == false }.keys
                val ed = prefs.edit()
                for (k in stale) ed.remove(k)
                ed.putInt(K_CLOUD_RULE_VERSION, CLOUD_RULE_VERSION).apply()
                android.util.Log.i("BH_STEAM_CLOUD", "cloud-support rule $CLOUD_RULE_VERSION: cleared ${stale.size} stale 'no cloud' verdict(s)")
            }
            cloudRuleChecked = true
        }
    }

    /** Cached Steam-Cloud-support verdict for [appId]: true/false if resolved before, null if never. */
    fun getCloudSupportCached(ctx: Context, appId: Int): Boolean? {
        init(ctx)
        migrateCloudSupportVerdicts()
        val key = K_CLOUD_SUPPORT_PREFIX + appId
        if (!prefs.contains(key)) return null
        return prefs.getBoolean(key, false)
    }

    // ── Cached UFS config (PICS `ufs`: quota, file limit, savefiles rules) ─────────────────────
    // Stored as the JSON [SteamUfsConfig] writes, so Collect can scope itself to the game's save
    // rules without a network round-trip (it runs on game exit, possibly offline).

    private const val K_UFS_PREFIX = "ufs_config_v1_"

    fun getUfsConfigJson(ctx: Context, appId: Int): String? {
        init(ctx)
        return prefs.getString(K_UFS_PREFIX + appId, null)
    }

    fun setUfsConfigJson(ctx: Context, appId: Int, json: String) {
        init(ctx)
        prefs.edit().putString(K_UFS_PREFIX + appId, json).apply()
    }

    /** The signed-in account's 32-bit id (SteamID3), derived from the 64-bit id if needed; 0 = unknown. */
    fun accountIdOrDerived(ctx: Context): Int {
        init(ctx)
        val a = prefs.getInt(K_ACCOUNT_ID, 0)
        if (a != 0) return a
        return (prefs.getLong(K_STEAM_ID_64, 0L) and 0xFFFFFFFFL).toInt()
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
