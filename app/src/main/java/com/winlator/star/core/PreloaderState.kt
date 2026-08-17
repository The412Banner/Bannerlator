package com.winlator.star.core

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which face of the launch overlay is currently showing. */
enum class Phase { SETUP, GUEST, FAILED }

/** Populated only when [Phase.FAILED] — drives the failure card. */
data class Failure(
    val stage: String,
    val what: String,
    val detail: String?,
    val logDir: String?,
    val loggingEnabled: Boolean,
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

    /** Set (or clear, with null) the not-frozen reassurance line. */
    @JvmStatic fun hint(text: String?) {
        val cur = _ui.value ?: return
        _ui.value = cur.copy(hint = text)
    }

    /** Surface a launch failure card instead of dismissing. */
    @JvmStatic fun fail(stage: String, what: String, detail: String?, logDir: String?, loggingEnabled: Boolean) {
        val cur = _ui.value ?: PreloaderUi(title = "")
        _ui.value = cur.copy(phase = Phase.FAILED, failure = Failure(stage, what, detail, logDir, loggingEnabled))
    }

    @JvmStatic fun hide() { _ui.value = null }
    @JvmStatic fun isVisible(): Boolean = _ui.value != null
}
