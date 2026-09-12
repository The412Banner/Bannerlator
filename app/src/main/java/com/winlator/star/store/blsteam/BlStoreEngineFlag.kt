package com.winlator.star.store.blsteam

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Switches for the native Rust download engines of the non-Steam stores (`libblsteam.so`,
 * `fetch_core` + `store_dl::{epic,gog,amazon}`). Same shape as [BlSteamEngineFlag], one key per
 * store, all default ON.
 *
 * Unlike the Steam flag these are read at DOWNLOAD START (inside the store's Java manager, at the
 * point where its byte-fetch loop begins), not at process start: flipping a toggle takes effect on
 * the next download of that store without an app restart. ON = the Rust engine fetches the bytes;
 * OFF = the manager's existing Java loop, byte-identical. Everything around the loop (manifest,
 * plan, registry row, notifications, post-install steps) is shared by both engines.
 * See docs/RUST_STORE_ENGINES.md.
 */
object BlStoreEngineFlag {

    /** Default-SharedPreferences keys. */
    const val PREF_KEY_EPIC = "use_rust_epic_engine"
    const val PREF_KEY_GOG = "use_rust_gog_engine"
    const val PREF_KEY_AMAZON = "use_rust_amazon_engine"

    /** Engine used when the user has never touched the toggle. */
    const val DEFAULT = true

    @JvmStatic
    fun isEpicEnabled(ctx: Context): Boolean = read(ctx, PREF_KEY_EPIC)

    @JvmStatic
    fun isGogEnabled(ctx: Context): Boolean = read(ctx, PREF_KEY_GOG)

    @JvmStatic
    fun isAmazonEnabled(ctx: Context): Boolean = read(ctx, PREF_KEY_AMAZON)

    @JvmStatic
    fun setEpicEnabled(ctx: Context, enabled: Boolean) = write(ctx, PREF_KEY_EPIC, enabled)

    @JvmStatic
    fun setGogEnabled(ctx: Context, enabled: Boolean) = write(ctx, PREF_KEY_GOG, enabled)

    @JvmStatic
    fun setAmazonEnabled(ctx: Context, enabled: Boolean) = write(ctx, PREF_KEY_AMAZON, enabled)

    private fun read(ctx: Context, key: String): Boolean =
        try {
            PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(key, DEFAULT)
        } catch (_: Throwable) {
            DEFAULT
        }

    private fun write(ctx: Context, key: String, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(key, enabled).apply()
    }
}
