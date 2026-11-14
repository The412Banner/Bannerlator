package com.winlator.star.ui.simple

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Touch for the Lab pages and pop-ups that are driven by controller buttons: in portrait people mostly tap, so every
// button hint can be tapped and the pages in their own window have a back arrow. A tap goes the way a controller press
// goes, so it does exactly what that button does on that page.

/** The controller button a hint glyph stands for, or null for a glyph that names a direction or two buttons. */
internal fun labGlyphKey(glyph: String): Int? = when (glyph.trim().uppercase()) {
    "A" -> KeyEvent.KEYCODE_BUTTON_A
    "B" -> KeyEvent.KEYCODE_BUTTON_B
    "X" -> KeyEvent.KEYCODE_BUTTON_X
    "Y" -> KeyEvent.KEYCODE_BUTTON_Y
    "L1", "LB" -> KeyEvent.KEYCODE_BUTTON_L1
    "R1", "RB" -> KeyEvent.KEYCODE_BUTTON_R1
    "L2", "LT" -> KeyEvent.KEYCODE_BUTTON_L2
    "R2", "RT" -> KeyEvent.KEYCODE_BUTTON_R2
    "START" -> KeyEvent.KEYCODE_BUTTON_START
    "SELECT" -> KeyEvent.KEYCODE_BUTTON_SELECT
    else -> null
}

/**
 * Presses [keyCode] (down, then up) in the window [view] is in, through its window callback as a real controller press
 * goes: the page's own key handling first, then the app's shortcuts. A B that nothing handles is sent as Back, the way
 * Android turns an unhandled game-pad B into Back.
 */
/**
 * What a page does with a button when no row or tile has the controller's focus (after touch nothing has it), for the
 * hints whose button acts on the focused item: Home's X opens the menu of the game on the big tile. Set with
 * [LabHintFallback] while the page shows.
 */
internal object LabHintFallbacks {
    val actions = androidx.compose.runtime.snapshots.SnapshotStateMap<Int, () -> Unit>()
}

/** While on screen: a tap on [keyCode]'s hint runs [action] when the press itself reached nothing. */
@Composable
internal fun LabHintFallback(keyCode: Int, action: () -> Unit) {
    val latest = androidx.compose.runtime.rememberUpdatedState(action)
    androidx.compose.runtime.DisposableEffect(keyCode) {
        val run: () -> Unit = { latest.value() }
        LabHintFallbacks.actions[keyCode] = run
        onDispose { if (LabHintFallbacks.actions[keyCode] === run) LabHintFallbacks.actions.remove(keyCode) }
    }
}

internal fun labPressButton(view: View, keyCode: Int) {
    val root = view.rootView ?: return
    fun press(code: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_GAMEPAD)
        val handledDown = root.dispatchKeyEvent(down)
        val handledUp = root.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
        return handledDown || handledUp
    }
    if (press(keyCode)) return
    // Nothing had the focus the button acts on: the page's own default for it, else B as Back.
    val fallback = LabHintFallbacks.actions[keyCode]
    if (fallback != null) fallback()
    else if (keyCode == KeyEvent.KEYCODE_BUTTON_B) press(KeyEvent.KEYCODE_BACK)
}

/** A button hint ("B Back"): a tap presses that button. Hints for directions or button pairs are labels only. */
@Composable
internal fun LabHint(glyph: String, label: String, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val key = labGlyphKey(glyph)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.then(
            if (key == null) Modifier
            // A finger-sized target that the d-pad skips (the controller has the button itself).
            else Modifier.heightIn(min = 40.dp).clip(SimpleShape).focusProperties { canFocus = false }
                .clickable { labPressButton(view, key) }.padding(horizontal = 6.dp),
        ),
    ) {
        SimpleGlyph(glyph)
        Text(label, color = SimpleMuted, fontSize = 12.sp, maxLines = 1)
    }
}

/** The back arrow of a Lab page in a window of its own, the same as the Lab top line's: a tap presses B there. */
@Composable
internal fun LabBackButton(modifier: Modifier = Modifier) {
    val view = LocalView.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(40.dp).focusProperties { canFocus = false }.labPanel()
            .labTile(onClick = { labPressButton(view, KeyEvent.KEYCODE_BUTTON_B) }),
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L("Back"), tint = SimpleInk, modifier = Modifier.size(22.dp)) }
}

/** A close (✕) button for a Lab pop-up: runs [onClose]. */
@Composable
internal fun LabCloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(40.dp).focusProperties { canFocus = false }.labTile(onClick = onClose),
    ) { Icon(Icons.Filled.Close, contentDescription = L("Close"), tint = SimpleInk, modifier = Modifier.size(20.dp)) }
}
