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
