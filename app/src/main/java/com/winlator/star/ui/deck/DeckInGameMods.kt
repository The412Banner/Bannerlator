package com.winlator.star.ui.deck

import androidx.compose.runtime.Composable
import com.winlator.star.ui.DrawerMod
import com.winlator.star.ui.XServerDrawerState
import com.winlator.star.ui.simple.L

/**
 * The in-game menu's Mods page (Deck and Lab), the Classic drawer's Mods tab (XServerDrawer.ModsContent) on the same
 * list: one row per mod this game's EXE can take, set at launch (XServerDisplayActivity.applyGameModsEnv). A loaded one
 * shows its release, the DLL it loads as and its bits, and a button that closes the menu and shows or hides the mod's
 * own menu (XServerDrawerState.onModMenu; for the program started with the game, starts it); an off or not-loaded one
 * is dimmed.
 */
@Composable
internal fun ModsPage(state: XServerDrawerState, mods: List<DrawerMod>) {
    IgCard {
        mods.forEachIndexed { i, mod ->
            if (i > 0) IgDivider()
            val tag = when {
                mod.loaded -> listOf(mod.version, mod.file, mod.bits).filter { it.isNotEmpty() }.joinToString(" · ")
                !mod.on -> L("Off")
                else -> L("Not loaded")
            }
            if (mod.loaded) {
                IgRow(L(mod.name), hint = tag.ifEmpty { null }) {
                    IgButton(modButtonLabel(mod.button), null, {
                        DeckInGameState.close()
                        state.onModMenu?.accept(mod.id)
                    })
                }
            } else {
                IgRow(L(mod.name), hint = tag, enabled = false)
            }
        }
    }
}

private fun modButtonLabel(button: String): String = when (button) {
    "Show / hide menu" -> L("Show / hide menu")
    "Start" -> L("Start")
    else -> L(button)
}
