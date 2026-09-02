package com.winlator.star.store.blsteam

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Switch for the native Rust Steam engine (`libblsteam.so`).
 *
 * Default ON since 3.0.4: `SteamRepository` drives the whole Steam stack (sign-in, session,
 * library, downloads, cloud, achievements, social, presence) through [BlSteamEngine]. OFF is the
 * legacy JavaSteam path, kept for one release as an emergency fallback (Log Manager toggle); while
 * OFF the engine's only runtime effect is one boot log line proving the library loads and its JNI
 * symbols bind (see [BlSteamClient.probe]). See docs/STEAM_RUST_ENGINE_PLAN.md.
 *
 * Read once at `SteamRepository.initialize()`; flipping it takes effect on the next process
 * start so a live session is never torn down mid-flight.
 */
object BlSteamEngineFlag {

    /** Default-SharedPreferences key. */
    const val PREF_KEY = "use_rust_steam_engine"

    /** Engine used when the user has never touched the toggle. */
    const val DEFAULT = true

    @JvmStatic
    fun isEnabled(ctx: Context): Boolean =
        try {
            PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_KEY, DEFAULT)
        } catch (_: Throwable) {
            DEFAULT
        }

    @JvmStatic
    fun setEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(PREF_KEY, enabled).apply()
    }
}
