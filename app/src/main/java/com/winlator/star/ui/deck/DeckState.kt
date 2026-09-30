package com.winlator.star.ui.deck

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color

/** Deck-only routes, registered on top of AppNavGraph's routes. */
internal object DeckRoutes {
    const val HOME = "deck_home"
    const val STORES = "deck_stores"
    const val TOOLS = "deck_tools"
    const val COMPONENTS = "deck_components"
    const val CONTROLS = "deck_controls"
    /** A game's page. The shortcut's .desktop path rides along URL-encoded in [GAME_ARG]. */
    const val GAME = "deck_game?path={path}"
    const val GAME_ARG = "path"

    fun game(path: String): String = "deck_game?path=" + android.net.Uri.encode(path)
}

/**
 * What the current Deck page offers to the controller: X/Start = [options], Y = [search], L2/R2 =
 * [subTab] (a page's own tab strip, -1 / +1). L1/R1 always
 * move the top-level tabs. A page sets these while it is on screen and clears only its own value when
 * it leaves. The legend reads them too, so a page without options simply shows no X hint. Search is the shell's own overlay unless a page swaps it.
 */
@Stable
internal class DeckActions {
    val options: MutableState<(() -> Unit)?> = mutableStateOf(null)
    val optionsLabel: MutableState<String> = mutableStateOf("Options")
    val search: MutableState<(() -> Unit)?> = mutableStateOf(null)
    val subTab: MutableState<((Int) -> Unit)?> = mutableStateOf(null)
    val subTabLabel: MutableState<String> = mutableStateOf("Tab")
    /** True while a top-bar tab holds focus (L1/R1 just switched menus): a page opening then leaves focus up there. */
    val navHold: MutableState<Boolean> = mutableStateOf(false)
    /** Bumped when Down leaves the top bar, so the page on screen takes focus again through [DeckEntryFocus]. */
    val enterTick: MutableIntState = mutableIntStateOf(0)
    /** True while a page's own tab strip holds focus; Down from the top bar goes past it, since L2/R2 already move it. */
    val stripFocused: MutableState<Boolean> = mutableStateOf(false)
}

/**
 * A page's first focus: when it opens, when [keys] change, and when Down leaves the top bar.
 * Skipped while the top bar holds focus, so L1/R1 can keep moving across the tabs.
 */
@Composable
internal fun DeckEntryFocus(vararg keys: Any?, focus: () -> Unit) {
    val actions = LocalDeckActions.current
    val tick = actions.enterTick.intValue
    val currentFocus by rememberUpdatedState(focus)
    LaunchedEffect(tick, *keys) {
        withFrameNanos { }
        if (!actions.navHold.value) runCatching { currentFocus() }
    }
}

internal val LocalDeckActions = compositionLocalOf { DeckActions() }

/** Two colours pulled from the focused game's cover; the shell tints its backdrop with them. null = accent only. */
internal val LocalDeckAmbient = compositionLocalOf<MutableState<Pair<Color, Color>?>> { mutableStateOf(null) }

/**
 * Controller shortcuts for the Deck shell. MainActivity hands every key event that its own view tree
 * left unhandled to [handler]; the Deck shell sets it while it is composed and clears it on dispose.
 * The game runs in XServerDisplayActivity, which never calls this, so none of it reaches a game.
 */
object DeckInput {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null

    private var l2Down = false
    private var r2Down = false

    /**
     * Pads that report L2/R2 only as analog axes (the built-in Xbox-layout pad on handhelds has no trigger
     * buttons in its key layout) reach the shell as BUTTON_L2/R2 presses: down past 0.6, up again below 0.3.
     */
    fun onMotion(ev: MotionEvent) {
        val h = handler ?: return
        if (ev.action != MotionEvent.ACTION_MOVE) return
        if ((ev.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return
        val l = maxOf(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r = maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
        l2Down = trigger(h, ev, l, l2Down, KeyEvent.KEYCODE_BUTTON_L2)
        r2Down = trigger(h, ev, r, r2Down, KeyEvent.KEYCODE_BUTTON_R2)
    }

    private fun trigger(h: (KeyEvent) -> Boolean, ev: MotionEvent, value: Float, down: Boolean, code: Int): Boolean {
        val nowDown = if (down) value > 0.3f else value > 0.6f
        if (nowDown != down) {
            val action = if (nowDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            h(KeyEvent(ev.downTime, ev.eventTime, action, code, 0, 0, ev.deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD))
        }
        return nowDown
    }
}

/**
 * The Deck first-run (ui/deck/DeckOnboarding.kt). It shows once when the app starts in Deck style and
 * [PREF_DONE] is not set; Tools and Settings can ask for it again through [requestRerun].
 */
object DeckSetup {
    const val PREF_DONE = "deck_onboarding_done"

    /** Set by "Run setup again"; MainActivity shows the onboarding while it is true. */
    val rerun: MutableState<Boolean> = mutableStateOf(false)

    fun requestRerun() { rerun.value = true }
}
