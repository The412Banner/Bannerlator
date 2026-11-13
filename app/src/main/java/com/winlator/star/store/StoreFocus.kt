package com.winlator.star.store

import android.view.InputDevice
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.winlator.star.ui.deck.xmbHighlight
import kotlinx.coroutines.launch

// Controller support for the game pages of the stores: every control is a stop of the D-pad that takes the XMB highlight (the
// one gliding outline of the store screen's highlight host), A presses it, B goes back, and the page scrolls to keep it on screen.

/** The control of a game's page that takes the controller's focus when the page opens. */
internal enum class StoreEntry { PRIMARY, GEAR, BACK }

/**
 * The first stop of a game's page: its primary action while that can be pressed (not while it only shows a download's progress),
 * else the gear (Pause and Cancel live in its menu), else the back button.
 */
internal fun storeEntryTarget(primaryActionable: Boolean, hasGear: Boolean): StoreEntry = when {
    primaryActionable -> StoreEntry.PRIMARY
    hasGear -> StoreEntry.GEAR
    else -> StoreEntry.BACK
}

/**
 * Focus for a game page's entry stop: asks for it once the page has its first frame, and again whenever [target] changes (the
 * button that held it turned into a progress bar), but only while a game pad is attached, so a touch user sees no outline on open.
 * Hand the result to the [target] control's [storeFocus] as `entry`.
 */
@Composable
internal fun rememberStoreEntryFocus(target: StoreEntry): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(target) {
        withFrameNanos { }
        if (storeControllerAttached()) runCatching { requester.requestFocus() }
    }
    return requester
}

private fun storeControllerAttached(): Boolean = InputDevice.getDeviceIds().any { id ->
    val device = InputDevice.getDevice(id)
    device != null && !device.isVirtual && (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
}

/**
 * One control of a game's page as a stop of the D-pad. Under the store screen's highlight host the control takes the gliding
 * XMB outline (a plain accent border in a pop-up window, which has no host); focus scrolls the page until the control is on
 * screen; A runs [onClick] (touch and the keyboard's Enter stay with the control's own clickable). [tint] is the outline's colour
 * for a control filled with the accent, where the accent itself would vanish. [entry] marks the control the page focuses on open.
 * Put it before the control's clip, background and clickable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.storeFocus(
    shape: Shape,
    onClick: () -> Unit,
    tint: Color = Color.Unspecified,
    entry: FocusRequester? = null,
): Modifier {
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .xmbHighlight(shape, tint = tint, wholeSubtree = true)
        .bringIntoViewRequester(bring)
        .onFocusChanged { if (it.hasFocus) scope.launch { bring.bringIntoView() } }
        .then(if (entry != null) Modifier.focusRequester(entry) else Modifier)
        .onKeyEvent { k ->
            if (k.key != Key.ButtonA) {
                false
            } else {
                if (k.type == KeyEventType.KeyDown && k.nativeKeyEvent.repeatCount == 0) onClick()
                true
            }
        }
}

/**
 * B runs [onBack] from anywhere inside this element (on key-up, as the shell does), unless something inside it took the key first.
 * Where the system already turns B into Back this is never reached and Back does the same.
 */
internal fun Modifier.storeBackKey(onBack: () -> Unit): Modifier = this.onKeyEvent { k ->
    if (k.key != Key.ButtonB) {
        false
    } else {
        if (k.type == KeyEventType.KeyUp) onBack()
        true
    }
}
