package com.winlator.star.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf

// What a Classic screen needs to know when the Deck shell hosts it. Kept out of ui/deck so the
// Classic screens read two small locals and never depend on the Deck package.

/**
 * True while the Deck shell's landscape frame hosts this screen. The frame already shows the page
 * title and the Steam connection pill, so a screen that draws its own title row skips it.
 * Classic, portrait Deck and the screens' own activities leave it false.
 */
val LocalHostedInDeck = staticCompositionLocalOf { false }

/** Counts the side lists (a [com.winlator.star.ui.components.CollapsibleRail]) a hosted screen shows. */
class DeckSideLists {
    val count: MutableIntState = mutableIntStateOf(0)
}

/** Set by the Deck shell; null everywhere else. */
val LocalDeckSideLists = staticCompositionLocalOf<DeckSideLists?> { null }

/**
 * Called by a screen's own side list. Inside the Deck shell the page-tab rail then shrinks to icons,
 * so two full-width rails never sit side by side. Does nothing outside the Deck shell.
 */
@Composable
fun ReportSideListToDeck() {
    val lists = LocalDeckSideLists.current ?: return
    DisposableEffect(lists) {
        lists.count.intValue++
        onDispose { lists.count.intValue-- }
    }
}

/**
 * Whether the Deck shell has hidden Android's navigation bar (landscape Deck). A screen that hides
 * and restores the system bars itself (Big Picture) restores only the status bar while this is set.
 */
object DeckWindow {
    @Volatile
    var navBarHidden: Boolean = false
}
