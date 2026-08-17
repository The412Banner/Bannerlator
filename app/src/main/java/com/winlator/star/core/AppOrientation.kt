package com.winlator.star.core

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.preference.PreferenceManager

/**
 * The user's "App orientation" preference — applied to the app's OWN UI activity only. The game's
 * [com.winlator.star.XServerDisplayActivity] sets its own orientation and is never touched here, so
 * launching a game always releases/ignores this app-side lock.
 *
 * Modes: "auto" (follow device rotation — the default / historical behavior), "portrait", "landscape".
 */
object AppOrientation {
    const val PREF_KEY = "app_orientation"
    const val AUTO = "auto"
    const val PORTRAIT = "portrait"
    const val LANDSCAPE = "landscape"

    fun mode(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, AUTO) ?: AUTO

    fun setMode(context: Context, mode: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_KEY, mode).apply()
    }

    private fun requested(mode: String): Int = when (mode) {
        PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR   // "auto" — matches the manifest default
    }

    /** Apply the stored preference to [activity]'s requestedOrientation (call on start + on change). */
    fun apply(activity: Activity) {
        activity.requestedOrientation = requested(mode(activity))
    }
}
