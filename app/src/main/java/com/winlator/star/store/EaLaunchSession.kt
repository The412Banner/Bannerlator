package com.winlator.star.store

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.winlator.star.store.blsteam.BlEaLsx

/**
 * Puts our own licence server in front of a launch, or stays out of the way.
 *
 * One EA game runs at a time, so this holds a single listener for the current launch. [prepare]
 * returns the environment additions that point the game at us; [finish] tears the listener down and
 * hands back what was said, for the launch log.
 *
 * ### The rule
 * Every failure returns an empty map. An empty map means `EALsxPort` is never set, the game looks
 * for EA Desktop exactly as it does today, and the launch behaves the way it did before this feature
 * existed. There is no failure mode here that can stop a game that currently starts.
 *
 * ### Why nothing needs to be looked up first
 * The game announces its own EA content id during the handshake, so serving it requires knowing
 * nothing about the title in advance — no catalogue, no entitlement call, and for the empty-licence
 * path, no EA account at all.
 */
object EaLaunchSession {

    private const val TAG = "BL_EA"

    /** Setting key. Values are the [Mode] names; absent means [Mode.SERVE]. */
    const val PREF_MODE = "ea_lsx_mode"

    enum class Mode {
        /** Answer the game ourselves. EA Desktop stays out of the launch. */
        SERVE,

        /**
         * Sit between the game and EA Desktop and write down both sides.
         *
         * The launch behaves exactly as it does today, so this costs nothing but produces the
         * transcript that explains a failure without needing a second attempt.
         */
        CAPTURE,

        /** Do nothing at all. The behaviour that shipped before this feature. */
        OFF,
    }

    @Volatile
    private var active: BlEaLsx? = null

    @Volatile
    private var activeMode: Mode = Mode.OFF

    fun mode(ctx: Context): Mode = try {
        val raw = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_MODE, Mode.SERVE.name)
        Mode.valueOf(raw ?: Mode.SERVE.name)
    } catch (_: Exception) {
        Mode.SERVE
    }

    fun setMode(ctx: Context, mode: Mode) {
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .edit().putString(PREF_MODE, mode.name).apply()
    }

    /**
     * Start a listener for the launch about to happen.
     *
     * @param eaDesktopLsxPort the port real EA Desktop is on, needed only by [Mode.CAPTURE]. When it
     *   is unknown, capture cannot relay anywhere and is skipped rather than breaking the launch.
     * @return environment to merge into the game's, or empty to stay out of the launch.
     */
    @JvmStatic
    @JvmOverloads
    fun prepare(ctx: Context, eaDesktopLsxPort: Int = 0): Map<String, String> {
        finish() // never leave a previous launch's listener bound

        val mode = mode(ctx)
        if (mode == Mode.OFF) {
            Log.i(TAG, "LSX disabled by setting; EA Desktop will handle the launch")
            return emptyMap()
        }

        val lsx = BlEaLsx.create()
        if (lsx == null) {
            Log.i(TAG, "native EA LSX unavailable; EA Desktop will handle the launch")
            return emptyMap()
        }

        val port = when (mode) {
            Mode.SERVE -> {
                loadTokens(ctx, lsx)
                lsx.startServe(BlEaLsx.Strategy.AUTO)
            }
            Mode.CAPTURE -> {
                if (eaDesktopLsxPort <= 0) {
                    Log.i(TAG, "capture asked for but EA Desktop's port is unknown; staying out of the launch")
                    0
                } else {
                    lsx.startCapture(eaDesktopLsxPort)
                }
            }
            Mode.OFF -> 0
        }

        if (port <= 0) {
            Log.w(TAG, "could not start LSX (${lsx.lastError}); EA Desktop will handle the launch")
            lsx.close()
            return emptyMap()
        }

        active = lsx
        activeMode = mode

        val env = LinkedHashMap<String, String>()
        env["EALsxPort"] = port.toString()
        Log.i(TAG, "EALsxPort=$port mode=$mode")
        return env
    }

    /**
     * Hand the listener any licences we hold.
     *
     * Absent a licence the session answers empty, which is a legitimate answer and may be all a
     * title without Denuvo ever needs — so having none here is not a failure.
     */
    private fun loadTokens(ctx: Context, lsx: BlEaLsx) {
        val creds = EaCredentialStore.load(ctx)
        if (!creds.isSignedIn) {
            Log.i(TAG, "no EA account signed in; licences will be answered empty")
            return
        }
        // Held licences arrive here once the licence fetch lands. Until then a signed-in account
        // changes nothing about the launch, which is why the empty path is worth testing first.
    }

    /** Stop the listener and return everything that was said, or empty if there was no session. */
    @JvmStatic
    fun finish(): String {
        val lsx = active ?: return ""
        active = null
        val transcript = try {
            val summary = lsx.lastError
            val body = lsx.drainTranscript()
            buildString {
                append("EA LSX session (").append(activeMode).append(")\n")
                if (summary.isNotEmpty()) append(summary).append('\n')
                if (body.isNotEmpty()) append(body)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read LSX transcript", t)
            ""
        }
        lsx.stop()
        lsx.close()
        activeMode = Mode.OFF
        return transcript
    }

    /** True while a launch is being served or captured. */
    @JvmStatic
    fun isActive(): Boolean = active != null

    /**
     * End the session, and put what was said somewhere it can be read afterwards.
     *
     * This is the reason capture ships in the finished build rather than being a separate
     * experiment: when a launch misbehaves, the transcript already exists. Diagnosing it costs
     * nothing extra — and on this feature an extra attempt is genuinely expensive, because every EA
     * launch is spent against an allowance that refills slowly.
     *
     * Written to `filesDir/ea/lsx-<time>.log` and echoed to logcat a line at a time, so the built-in
     * log capture picks it up without any line being truncated.
     */
    @JvmStatic
    fun finishAndLog(ctx: Context) {
        val transcript = finish()
        if (transcript.isBlank()) return

        for (line in transcript.lineSequence()) {
            if (line.isNotBlank()) Log.i(TAG, line)
        }

        try {
            val dir = java.io.File(ctx.filesDir, "ea").apply { mkdirs() }
            // Keep only the last few. A transcript is worth having; a folder of them is not.
            dir.listFiles { f -> f.name.startsWith("lsx-") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(4)
                ?.forEach { it.delete() }
            java.io.File(dir, "lsx-${System.currentTimeMillis()}.log").writeText(transcript)
        } catch (t: Throwable) {
            Log.w(TAG, "could not save LSX transcript", t)
        }
    }
}
