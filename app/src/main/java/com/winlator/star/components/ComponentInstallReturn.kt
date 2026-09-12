package com.winlator.star.components

import android.content.Context

/**
 * Session-return plumbing for installer-based component installs (Phase 3b).
 *
 * An `install_exe`/`install_msi` component installs by launching a container session, which restarts
 * the whole app (XServerDisplayActivity.exit -> AppUtils.restartApplication). By the time the resume
 * dialog ([ComponentInstallResume]) finishes on the fresh process, the add-game / shortcut-settings
 * flow that started the install is gone and the user is stranded on the default landing screen.
 *
 * This tiny store (its OWN SharedPreferences, deliberately NOT entangled with [ComponentExecInstaller]'s
 * install plan) records where to send the user back to. It's written at the SOURCE right before the
 * install launches (it survives the app restart), and read once the install completes/aborts to
 * navigate back to the Games screen (Tier 1) and — when a shortcut base name is known — re-open that
 * shortcut's settings on its recommended-components tab (Tier 2).
 *
 * Best-effort: a missing/partial target just means fall back to Games (or no-op) — never a crash.
 */
object ComponentInstallReturn {
    private const val PREFS = "component_install_return"
    private const val KEY_CONTAINER = "containerId"
    private const val KEY_SHORTCUT = "shortcutBase"

    /** Where to return after an installer-based component install. [shortcutBase] null = Tier 1 only. */
    data class Target(val containerId: Int, val shortcutBase: String?)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Record a return target just before launching an installer session. */
    fun set(context: Context, containerId: Int, shortcutBase: String?) {
        val e = prefs(context).edit()
        e.putInt(KEY_CONTAINER, containerId)
        if (!shortcutBase.isNullOrEmpty()) e.putString(KEY_SHORTCUT, shortcutBase) else e.remove(KEY_SHORTCUT)
        e.apply()
    }

    /** The pending return target, or null if none was recorded. */
    fun get(context: Context): Target? {
        val p = prefs(context)
        if (!p.contains(KEY_CONTAINER)) return null
        return Target(
            containerId = p.getInt(KEY_CONTAINER, 0),
            shortcutBase = p.getString(KEY_SHORTCUT, null)?.takeIf { it.isNotEmpty() },
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
