package com.winlator.star.sound

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Decides which UI sound plays and when, then hands it to the loaded [UiSoundBank].
 *
 * Rules:
 * - One input makes one sound. A/Enter (select) and B/Back wait [SOFT_WAIT_MS]; any stronger cue the
 *   press causes in that time (dialog, toggle, tab, page, launch) plays instead. A strong cue plays at
 *   once and keeps weaker input cues quiet for [AFTER_STRONG_MS].
 * - The move sound plays only when focus moves within [MOVE_LINK_MS] of a direction press, so the
 *   focus a screen or dialog sets by itself stays quiet.
 * - A direction press the UI did not use (end of a list) plays the edge sound.
 * - Event cues (rank 0: notifications, startup) never merge and play as soon as they are raised.
 * - The same cue never repeats closer than its [UiCue.gapMs].
 */
object UiSounds {
    private const val SOFT_WAIT_MS = 70L
    private const val AFTER_STRONG_MS = 150L
    private const val MOVE_LINK_MS = 250L
    private const val STRONG_RANK = 50

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val lastAt = LongArray(UiCue.entries.size)

    @Volatile private var bank: UiSoundBank? = null
    @Volatile private var volume = 0.6f
    @Volatile private var lastDirectionAt = 0L
    @Volatile private var quietBelow = 0
    @Volatile private var quietUntil = 0L
    private var soft: UiCue? = null
    private val flushSoft = Runnable {
        val c = soft
        soft = null
        if (c != null) emit(c)
    }

    private const val PREVIEW_KEEP_MS = 20_000L
    @Volatile private var files: Map<String, List<java.io.File>> = emptyMap()
    @Volatile private var filesKey = ""
    private var previewBank: UiSoundBank? = null
    private val closePreview = Runnable {
        previewBank?.close()
        previewBank = null
    }

    /** True while a pack is loaded and sounds are on; hooks skip their work when false. */
    val active: Boolean get() = bank != null

    /** Sets what plays: [on] + the resolved Steam-name → files map ([key] changes when the files do). */
    internal fun configure(on: Boolean, newFiles: Map<String, List<java.io.File>>, key: String, newVolume: Float) {
        main.post {
            files = newFiles
            filesKey = key
            volume = newVolume.coerceIn(0f, 1f)
            val want = on && newFiles.isNotEmpty()
            val old = bank
            if (!want) {
                bank = null
                old?.close()
            } else if (old == null || old.key != key) {
                bank = UiSoundBank.open(key, newFiles)
                old?.close()
                if (SystemClock.uptimeMillis() < startupUntil) {
                    startupUntil = 0L
                    cue(UiCue.STARTUP)
                }
            }
        }
    }

    internal fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    /**
     * Plays [cues] one after another from [previewFiles] (a pack that need not be in use), even while sounds are
     * off; the pack stays loaded for a short while so a second preview starts at once.
     */
    fun preview(key: String, previewFiles: Map<String, List<java.io.File>>, cues: List<UiCue>) {
        main.post {
            if (previewBank?.key != key) {
                previewBank?.close()
                previewBank = previewFiles.takeIf { it.isNotEmpty() }?.let { UiSoundBank.open(key, it) }
            }
            val b = previewBank ?: return@post
            cues.forEachIndexed { i, c -> main.postDelayed({ if (previewBank === b) b.play(c, volume) }, 150L + i * 300L) }
            main.removeCallbacks(closePreview)
            main.postDelayed(closePreview, PREVIEW_KEEP_MS)
        }
    }

    /** Plays [cue] by the rules above. Safe from any thread. */
    fun cue(cue: UiCue) {
        if (bank == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cue(cue) }
            return
        }
        val now = SystemClock.uptimeMillis()
        when {
            cue.isEvent -> emit(cue)
            cue.rank >= STRONG_RANK -> {
                main.removeCallbacks(flushSoft)
                soft = null
                quietBelow = cue.rank
                quietUntil = now + AFTER_STRONG_MS
                emit(cue)
            }
            now < quietUntil && cue.rank < quietBelow -> Unit
            cue == UiCue.SELECT || cue == UiCue.BACK -> {
                main.removeCallbacks(flushSoft)
                soft = cue
                main.postDelayed(flushSoft, SOFT_WAIT_MS)
            }
            else -> emit(cue)
        }
    }

    @Volatile private var startupDone = false

    /** The start-up sound, at most once per process; it waits for the pack to load. */
    fun startup() {
        if (startupDone) return
        startupDone = true
        main.post {
            // The pack may still be loading in the background: play it when the bank opens.
            if (bank != null) cue(UiCue.STARTUP) else startupUntil = SystemClock.uptimeMillis() + 5_000L
        }
    }
    private var startupUntil = 0L

    /** A focus highlight was gained; plays the move sound only when a direction press caused it. */
    fun focusGained() {
        if (bank == null) return
        // Compose moves focus inside the key dispatch, before key() has noted the press: check once it has.
        main.post { if (SystemClock.uptimeMillis() - lastDirectionAt <= MOVE_LINK_MS) cue(UiCue.NAVIGATE) }
    }

    /**
     * Call from an Activity's dispatchKeyEvent with the result of the normal dispatch. [consumed] =
     * the UI used the key. Only for keys that reached the app's own UI (not a running game).
     */
    fun key(event: KeyEvent, consumed: Boolean) {
        if (bank == null) return
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                cue(UiCue.VOLUME)
                return
            }
            if (isDirection(code)) {
                lastDirectionAt = SystemClock.uptimeMillis()
                if (!consumed) cue(UiCue.NAVIGATE_EDGE)
            }
            return
        }
        if (event.action != KeyEvent.ACTION_UP || event.isCanceled || !consumed) return
        when (code) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> cue(UiCue.SELECT)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> cue(UiCue.BACK)
        }
    }

    private fun isDirection(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    private fun emit(cue: UiCue) {
        val b = bank ?: return
        val now = SystemClock.uptimeMillis()
        val i = cue.ordinal
        if (cue.gapMs > 0 && lastAt[i] > 0 && now - lastAt[i] < cue.gapMs) return
        lastAt[i] = now
        b.play(cue, volume)
    }
}
