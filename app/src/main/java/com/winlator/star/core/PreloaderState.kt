package com.winlator.star.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Global singleton that drives the Compose PreloaderOverlay.
 * PreloaderDialog.java calls show()/hide() to update state.
 * MainActivity observes [text] and renders the overlay.
 */
object PreloaderState {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text

    @JvmStatic fun show(t: String?) { _text.value = t }
    @JvmStatic fun hide()           { _text.value = null }
    @JvmStatic fun isVisible(): Boolean = _text.value != null
}
