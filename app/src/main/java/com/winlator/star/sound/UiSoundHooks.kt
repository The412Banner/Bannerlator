package com.winlator.star.sound

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.KeyEvent
import android.view.Window

/** Compose hooks that raise [UiSounds] cues. All of them do nothing while sounds are off. */

private class Last<T>(var value: T)

/** Move sound when this element gains focus from a direction press. */
fun Modifier.focusSound(): Modifier = onFocusChanged { if (it.isFocused) UiSounds.focusGained() }

/** Move sound when [position] (a custom list's selection) changes because of a direction press. */
@Composable
fun MoveSound(position: Any?) {
    val last = remember { Last(position) }
    SideEffect {
        if (last.value != position) {
            last.value = position
            UiSounds.focusGained()
        }
    }
}

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

/**
 * Dialog open / close sound; call inside the dialog's content. A dialog is its own window, so its keys never pass
 * through the activity: this also lets [UiSounds.key] see them (move, edge, select and back sounds inside it).
 */
@Composable
fun DialogSound() {
    ShownSound(UiCue.DIALOG_OPEN, UiCue.DIALOG_CLOSE)
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
        val w = window
        val original = w?.callback
        if (w == null || original == null || original is SoundKeys) return@DisposableEffect onDispose {}
        val wrapper = SoundKeys(original)
        w.callback = wrapper
        onDispose { if (w.callback === wrapper) w.callback = original }
    }
}

private class SoundKeys(private val original: Window.Callback) : Window.Callback by original {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val used = original.dispatchKeyEvent(event)
        UiSounds.key(event, used)
        return used
    }
}

/** Game page in / out sound; call inside the page's content. */
@Composable
fun PageSound() = ShownSound(UiCue.PAGE_IN, UiCue.PAGE_OUT)

/** Toggle sound for a switch whose value the user just set. */
fun toggleSound(on: Boolean) = UiSounds.cue(if (on) UiCue.TOGGLE_ON else UiCue.TOGGLE_OFF)

/** Slider sound for a value change; [up] = the value rose. */
fun sliderSound(up: Boolean) = UiSounds.cue(if (up) UiCue.SLIDER_UP else UiCue.SLIDER_DOWN)

/**
 * For the content of a focusable Popup (its own window, no Window object to watch): notes the keys pressed in it so
 * focus moves there make the move sound, A the select sound and B the back sound.
 */
fun Modifier.popupKeySounds(): Modifier = onPreviewKeyEvent { UiSounds.key(it.nativeKeyEvent, true); false }

/**
 * Sound for a settings editor change from [old] to [new]: "0"/"1" values are a switch, two numbers a slider step, any
 * other change a confirmed choice.
 */
fun settingSound(old: String?, new: String) {
    if (old == new) return
    val a = old?.toDoubleOrNull()
    val b = new.toDoubleOrNull()
    when {
        (old == "0" || old == "1" || old == null) && (new == "0" || new == "1") -> toggleSound(new == "1")
        a != null && b != null -> sliderSound(b > a)
        else -> UiSounds.cue(UiCue.CONFIRM)
    }
}
