package com.winlator.star.core

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Which face of the launch overlay is currently showing. */
enum class Phase { SETUP, GUEST, FAILED }

/** One extra button on the failure card (e.g. "Retry", "Launch with Goldberg"). */
data class FailureAction(val label: String, val primary: Boolean, val run: Runnable)

/** Populated only when [Phase.FAILED] — drives the failure card. */
data class Failure(
    val stage: String,
    val what: String,
    val detail: String?,
    val logDir: String?,
    val loggingEnabled: Boolean,
    /** Optional extra actions rendered before the standard Close / Open-log buttons. */
    val actions: List<FailureAction> = emptyList(),
)

/**
 * Immutable snapshot the Compose PreloaderOverlay renders. null == overlay hidden.
 */
data class PreloaderUi(
    val title: String,
    val icon: Bitmap? = null,          // small shortcut icon (fallback art / corner)
    val coverArt: Bitmap? = null,      // full-bleed hero background when available
    val spec: PreloaderSpec? = null,   // card-mirrored component spec shown under the title
    val details: PreloaderDetails? = null, // accumulated game details, shown on the right-side panel
    val stepIndex: Int = 0,            // 0 = none yet; 1..stepTotal for the determinate bar
    val stepTotal: Int = 4,
    val stepLabel: String = "",
    val phase: Phase = Phase.SETUP,    // SETUP (determinate bar) | GUEST (spinner) | FAILED (card)
    val tailLabel: String = "",        // shown in GUEST phase
    val hint: String? = null,          // not-frozen reassurance line
    val failure: Failure? = null,      // set when phase == FAILED
    val cancellable: Boolean = false,  // true only during a game launch -> shows the Cancel button
    val centered: Boolean = false,     // true for the centered status/shutdown screen (no cover hero)
    val percent: Int = -1,             // centered screen: a determinate bar when >= 0 (a download's progress)
    val elapsed: String? = null,       // centered screen: the clock line, so a quiet log still shows time moving
    val linuxSteam: Boolean = false,   // centered screen: the Linux Steam session's black page with the entry's art
    val closingSince: Long = 0L,       // Linux page: elapsedRealtime when the close began; > 0 = closing, the page runs its own clock
)

/**
 * Global singleton that drives the Compose PreloaderOverlay.
 * PreloaderDialog.java calls the setters below to update state; the overlay observes [ui].
 * MutableStateFlow writes are thread-safe, so the setters may be called from any thread —
 * only work that touches Views/the Activity needs a main-thread hop by the caller.
 */
object PreloaderState {
    private val _ui = MutableStateFlow<PreloaderUi?>(null)
    val ui: StateFlow<PreloaderUi?> = _ui

    // The failure card's buttons route through these; the hosting activity registers them.
    @JvmStatic var onClose: Runnable? = null
    @JvmStatic var onOpenLog: Runnable? = null
    // Cancel button on the launch screen (SETUP/GUEST) — aborts the launch and tears down the game.
    @JvmStatic var onCancel: Runnable? = null

    /** Begin a game launch: title + shortcut icon + cover art, determinate SETUP phase. */
    @JvmStatic fun show(title: String?, icon: Bitmap?, coverArt: Bitmap?) {
        _ui.value = PreloaderUi(title = title ?: "", icon = icon, coverArt = coverArt, cancellable = true)
    }

    /** Begin a game launch with the card-mirrored component spec under the title. */
    @JvmStatic fun show(title: String?, icon: Bitmap?, coverArt: Bitmap?, spec: PreloaderSpec?) {
        _ui.value = PreloaderUi(title = title ?: "", icon = icon, coverArt = coverArt,
            spec = spec, cancellable = true)
    }

    /** Begin a game launch with the component spec + accumulated game details (right-side panel). */
    @JvmStatic fun show(title: String?, icon: Bitmap?, coverArt: Bitmap?, spec: PreloaderSpec?, details: PreloaderDetails?) {
        _ui.value = PreloaderUi(title = title ?: "", icon = icon, coverArt = coverArt,
            spec = spec, details = details, cancellable = true)
    }

    /** Begin a launch with only a shortcut icon (no cover art). */
    @JvmStatic fun show(title: String?, icon: Bitmap?) {
        _ui.value = PreloaderUi(title = title ?: "", icon = icon, cancellable = true)
    }

    /**
     * Centered indeterminate status screen (shutdown, create-container, install, backup/restore…).
     * Distinct from the launch hero: a calm logo + message + slim bar on a plain dark ground.
     */
    @JvmStatic fun show(title: String?) {
        _ui.value = PreloaderUi(title = "", tailLabel = title ?: "", phase = Phase.GUEST, centered = true)
    }

    /**
     * The Linux Steam session's loading screen: the centered status screen, on plain black with the Steam (Linux) entry's art instead of the neon wallpaper.
     * [linuxProgress] keeps the flag, since it copies the state it updates.
     */
    @JvmStatic fun showLinuxSteam(title: String?) {
        _ui.value = PreloaderUi(title = "", tailLabel = title ?: "", phase = Phase.GUEST, centered = true,
            linuxSteam = true)
    }

    /**
     * The same Linux Steam page for the session's close, with [hint] under [title] and a clock the page counts from now.
     * [linuxProgress] leaves it alone, so a late update from the loading watcher cannot put a startup line back on it.
     */
    @JvmStatic fun showLinuxSteamClosing(title: String?, hint: String?) {
        _ui.value = PreloaderUi(title = "", tailLabel = title ?: "", phase = Phase.GUEST, centered = true,
            linuxSteam = true, hint = hint, closingSince = SystemClock.elapsedRealtime())
    }

    /** Advance the determinate bar to [index]/stepTotal with [label]. */
    @JvmStatic fun step(index: Int, label: String) {
        val cur = _ui.value ?: PreloaderUi(title = "")
        _ui.value = cur.copy(stepIndex = index, stepLabel = label, phase = Phase.SETUP, failure = null)
    }

    /** Switch to the indeterminate spinner for the unmeasurable guest-boot tail. */
    @JvmStatic fun enterGuest(tailLabel: String) {
        val cur = _ui.value ?: PreloaderUi(title = "")
        _ui.value = cur.copy(phase = Phase.GUEST, tailLabel = tailLabel)
    }

    /**
     * The Linux session's loading screen, every half second while the client boots: the runtime's
     * newest milestone (or a download's percentage) as the message, the clock, and a rotating hint.
     * Updates ONLY an overlay that is up: re-creating a closed one here would resurrect it over a
     * running session (the first-run mirror did exactly that once).
     */
    @JvmStatic fun linuxProgress(step: String, percent: Int, elapsed: String?, hint: String?) {
        // A compare-and-set, because the watcher calls this from its own thread and a plain read-then-write could land a startup line over a close that began in between.
        _ui.update { cur ->
            if (cur == null || !cur.centered || cur.closingSince > 0L) cur
            else cur.copy(tailLabel = step, percent = percent, elapsed = elapsed, hint = hint)
        }
    }

    /** Set (or clear, with null) the not-frozen reassurance line. */
    @JvmStatic fun hint(text: String?) {
        val cur = _ui.value ?: return
        _ui.value = cur.copy(hint = text)
    }

    /** Surface a launch failure card instead of dismissing. */
    @JvmStatic fun fail(stage: String, what: String, detail: String?, logDir: String?, loggingEnabled: Boolean) {
        fail(stage, what, detail, logDir, loggingEnabled, emptyList())
    }

    /** Failure card with extra action buttons (SteamLite: Retry / Launch with Goldberg). */
    @JvmStatic fun fail(stage: String, what: String, detail: String?, logDir: String?, loggingEnabled: Boolean,
                        actions: List<FailureAction>) {
        val cur = _ui.value ?: PreloaderUi(title = "")
        _ui.value = cur.copy(phase = Phase.FAILED, failure = Failure(stage, what, detail, logDir, loggingEnabled, actions))
    }

    @JvmStatic fun hide() { _ui.value = null }

    /**
     * Failure-card Close. Runs the owning activity's callback (finish) if one is still registered,
     * then clears the shared state. The state is app-wide and the same overlay is composed in
     * MainActivity too, so a card left in FAILED after the game activity finished (or was swiped
     * away, which never runs its callbacks) kept showing on the Games screen with an inert Close —
     * the only way out was killing the app. Clearing here fixes both paths.
     */
    @JvmStatic fun close() {
        onClose?.run()
        hide()
    }

    /** Clear a lingering failure card only (used from the game activity's teardown). */
    @JvmStatic fun hideIfFailed() {
        if (_ui.value?.phase == Phase.FAILED) _ui.value = null
    }
    @JvmStatic fun isVisible(): Boolean = _ui.value != null
}
