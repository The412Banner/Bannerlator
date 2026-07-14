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
    val icon: Bitmap? = null,
    val stepIndex: Int = 0,        // 0 = none yet; 1..stepTotal for the determinate bar
    val stepTotal: Int = 4,
    val stepLabel: String = "",
    val phase: Phase = Phase.SETUP,   // SETUP (determinate bar) | GUEST (spinner) | FAILED (card)
    val tailLabel: String = "",       // shown in GUEST phase
    val hint: String? = null,         // not-frozen reassurance line
    val failure: Failure? = null,     // set when phase == FAILED
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

    /** Begin a launch: title + shortcut icon, determinate SETUP phase. */
    @JvmStatic fun show(title: String?, icon: Bitmap?) {
        _ui.value = PreloaderUi(title = title ?: "", icon = icon)
    }

    /** Simple indeterminate message with no step bar/heading (e.g. shutdown, create-container). */
    @JvmStatic fun show(title: String?) {
        _ui.value = PreloaderUi(title = "", tailLabel = title ?: "", phase = Phase.GUEST)
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
