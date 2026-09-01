package com.winlator.star.store.blsteam

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Hidden developer switch for the native Rust Steam engine (`libblsteam.so`).
 *
 * Default OFF. While OFF the only runtime effect of the engine is one boot log line proving
 * the library loads and its JNI symbols bind (see [BlSteamClient.probe]). When ON,
 * `SteamRepository` drives its CM session through [BlSteamEngine] instead of JavaSteam
 * (Phase 0: connect + refresh-token logon only — see docs/STEAM_RUST_ENGINE_PLAN.md).
 *
 * Read once at `SteamRepository.initialize()`; flipping it takes effect on the next process
 * start so a live JavaSteam session is never torn down mid-flight.
 */
object BlSteamEngineFlag {

    /** Default-SharedPreferences key. */
    const val PREF_KEY = "use_rust_steam_engine"

    @JvmStatic
    fun isEnabled(ctx: Context): Boolean =
        try {
            PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_KEY, false)
        } catch (_: Throwable) {
            false
        }

    @JvmStatic
    fun setEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(PREF_KEY, enabled).apply()
    }
}
