package com.winlator.star.ui.components

import android.content.Context
import androidx.preference.PreferenceManager
import com.winlator.star.container.Container

// ───── Global (app-drawer) Player-Slots defaults ─────
// A single global default for the controller Player-Slots pins and the On-screen priority mode, edited
// from the app-drawer Input Controls screen. These are SEED-ONLY: they are copied into a container's
// per-container settings ONCE, at container CREATION (ContainerDetailViewModel create path). They are
// NOT a live launch-time fallback and editing them NEVER touches an already-created container. Stored in
// the app's default SharedPreferences, the same store WinHandler uses for its other global toggles.
object GlobalControllerPrefs {
    // Canonical controllerSlotOverrides JSON (WinHandler.parse/buildSlotOverridesJson schema). "{}" = all-auto.
    private const val KEY_SLOT_OVERRIDES = "global_controller_slot_overrides"
    // On-screen priority mode default (Container.ON_SCREEN_MODE_*).
    private const val KEY_ON_SCREEN_MODE = "global_on_screen_controller_mode"
    // Auto-hide on-screen controls when a controller takes the on-screen slot (issue #333). Global
    // default is ON so newly-created containers get the seamless behavior; existing containers are
    // untouched (they keep the FALSE container-level fallback unless the user opts in).
    private const val KEY_AUTO_HIDE_ON_PAD = "global_auto_hide_controls_on_pad"
    private const val DEFAULT_AUTO_HIDE_ON_PAD = true

    fun getSlotOverridesJson(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY_SLOT_OVERRIDES, "{}") ?: "{}"
    }

    fun setSlotOverridesJson(context: Context, json: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY_SLOT_OVERRIDES, if (json.isEmpty()) "{}" else json)
            .apply()
    }

    fun getOnScreenMode(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val m = prefs.getInt(KEY_ON_SCREEN_MODE, Container.ON_SCREEN_MODE_DEFAULT)
        return if (m < Container.ON_SCREEN_MODE_KEEP || m > Container.ON_SCREEN_MODE_SHARE)
            Container.ON_SCREEN_MODE_DEFAULT else m
    }

    fun setOnScreenMode(context: Context, mode: Int) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt(KEY_ON_SCREEN_MODE, mode)
            .apply()
    }

    fun getAutoHideControlsOnPad(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_AUTO_HIDE_ON_PAD, DEFAULT_AUTO_HIDE_ON_PAD)
    }

    fun setAutoHideControlsOnPad(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_AUTO_HIDE_ON_PAD, enabled)
            .apply()
    }

    // ───── Steam Controller support (device-level, LIVE — read at every game launch) ─────
    // Unlike the seed-only keys above, these apply to every session: they describe the phone's
    // hardware, not a container. OFF by default: when off, SDL is never loaded and the normal input
    // path is byte-for-byte unchanged (see SteamControllerBackend).
    private const val KEY_STEAM_CONTROLLER = "steam_controller_sdl_enabled"
    // Right trackpad drives the mouse, click = left button (Steam's default). Only used when the
    // Steam Controller support itself is on.
    private const val KEY_STEAM_TRACKPAD_MOUSE = "steam_controller_trackpad_mouse"

    @JvmStatic
    fun isSteamControllerEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_STEAM_CONTROLLER, false)

    @JvmStatic
    fun setSteamControllerEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_STEAM_CONTROLLER, enabled)
            .apply()
    }

    @JvmStatic
    fun isSteamTrackpadMouseEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_STEAM_TRACKPAD_MOUSE, true)

    @JvmStatic
    fun setSteamTrackpadMouseEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_STEAM_TRACKPAD_MOUSE, enabled)
            .apply()
    }
}
