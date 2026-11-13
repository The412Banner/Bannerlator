package com.winlator.star.core

import android.util.Log
import com.winlator.star.container.Shortcut

/**
 * Launch fixes for specific games, whatever store they came from: a game is known by its exe's name.
 * A fix only sets a variable the launch environment does not carry yet, so the user's own value in
 * the container or the shortcut still wins.
 *
 * - Batman: Arkham City (BatmanAC.exe): FEX with full x87 precision. The game leans on x87 and
 *   crashes at start with reduced precision, which most FEX presets turn on. Same fix as
 *   DroidDeck's `arkham_city_x87` (Droid-Deck/DroidDeck #792), after the FEX wiki page for the game.
 */
object GameLaunchFixes {
    private const val TAG = "GameLaunchFixes"

    /** Environment defaults per exe name (lower case). */
    private val ENV = mapOf(
        "batmanac.exe" to mapOf("FEX_X87REDUCEDPRECISION" to "0"),
    )

    @JvmStatic
    fun applyEnv(shortcut: Shortcut?, envVars: EnvVars?) {
        if (shortcut == null || envVars == null) return
        try {
            val exe = exeName(shortcut.path)
            val defaults = ENV[exe] ?: return
            for ((name, value) in defaults) {
                if (envVars.has(name)) continue
                envVars.put(name, value)
                Log.i(TAG, "$exe: $name=$value")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyEnv failed", t)
        }
    }

    /** The exe's file name from a shortcut's path (Windows or Unix separators, quotes and arguments dropped). */
    internal fun exeName(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val lower = path.trim().trim('"').lowercase()
        val end = lower.indexOf(".exe")
        val upTo = if (end >= 0) lower.substring(0, end + 4) else lower
        return upTo.substringAfterLast('\\').substringAfterLast('/')
    }
}
