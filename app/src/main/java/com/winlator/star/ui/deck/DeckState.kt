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
}

/**
 * What the current Deck page offers to the controller: X/Start = [options], Y = [search]. A page sets
 * these while it is on screen and clears only its own value when it leaves. The legend reads them too,
 * so a page without options simply shows no X hint.
 */
@Stable
internal class DeckActions {
    val options: MutableState<(() -> Unit)?> = mutableStateOf(null)
    val optionsLabel: MutableState<String> = mutableStateOf("Options")
    val search: MutableState<(() -> Unit)?> = mutableStateOf(null)
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
