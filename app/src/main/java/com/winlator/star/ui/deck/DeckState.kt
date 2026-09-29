package com.winlator.star.ui.deck

import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
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
 * What the current Deck page offers to the controller: X/Start = [options], Y = [search], L1/R1 =
 * [subTab] (a page's own tab strip; the top-level tabs then move with L2/R2). A page sets these while
 * it is on screen and clears only its own value when it leaves. The legend reads them too, so a page
 * without options simply shows no X hint. Search is the shell's own overlay unless a page swaps it.
 */
@Stable
internal class DeckActions {
    val options: MutableState<(() -> Unit)?> = mutableStateOf(null)
    val optionsLabel: MutableState<String> = mutableStateOf("Options")
    val search: MutableState<(() -> Unit)?> = mutableStateOf(null)
    val subTab: MutableState<((Int) -> Unit)?> = mutableStateOf(null)
    val subTabLabel: MutableState<String> = mutableStateOf("Tab")
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
