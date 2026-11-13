package com.winlator.star.sound

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/** Compose hooks that raise [UiSounds] cues. All of them do nothing while sounds are off. */

private class Last<T>(var value: T)

/** Move sound when this element gains focus from a direction press. */
fun Modifier.focusSound(): Modifier = onFocusChanged { if (it.isFocused) UiSounds.focusGained() }

/** Same as [focusSound] for code that already tracks [focused]. */
@Composable
fun FocusSound(focused: Boolean) {
    val last = remember { Last(focused) }
    SideEffect {
        if (last.value != focused) {
            last.value = focused
            if (focused) UiSounds.focusGained()
        }
    }
}

/** Plays the cue [pick] returns when [value] changes between compositions (never on the first one). */
@Composable
fun <T> SoundOnChange(value: T, pick: (old: T, new: T) -> UiCue?) {
    val last = remember { Last(value) }
    SideEffect {
        if (last.value != value) {
            val old = last.value
            last.value = value
            pick(old, value)?.let(UiSounds::cue)
        }
    }
}

/** Tab change sound when [tab] changes. */
@Composable
fun <T> TabSound(tab: T) = SoundOnChange(tab) { _, _ -> UiCue.TAB }

/** Side menu fly-in / fly-out when [open] changes. */
@Composable
fun MenuSound(open: Boolean) = SoundOnChange(open) { _, o -> if (o) UiCue.MENU_OPEN else UiCue.MENU_CLOSE }

/** Plays [shown] when this enters the composition and [gone] when it leaves (dialogs, sheets, pages). */
@Composable
fun ShownSound(shown: UiCue, gone: UiCue?) {
    DisposableEffect(Unit) {
        UiSounds.cue(shown)
        onDispose { gone?.let(UiSounds::cue) }
    }
}

/** Dialog open / close sound; call inside the dialog's content. */
@Composable
fun DialogSound() = ShownSound(UiCue.DIALOG_OPEN, UiCue.DIALOG_CLOSE)

/** Game page in / out sound; call inside the page's content. */
@Composable
fun PageSound() = ShownSound(UiCue.PAGE_IN, UiCue.PAGE_OUT)

/** Toggle sound for a switch whose value the user just set. */
fun toggleSound(on: Boolean) = UiSounds.cue(if (on) UiCue.TOGGLE_ON else UiCue.TOGGLE_OFF)

/** Slider sound for a value change; [up] = the value rose. */
fun sliderSound(up: Boolean) = UiSounds.cue(if (up) UiCue.SLIDER_UP else UiCue.SLIDER_DOWN)
