package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L

/*
 * The few texts of a game's settings page that are the container page's own, said for a game. In the XMB the two pages are
 * the same page (ContainerLayout.forScope: the container's tabs, rows, groups and wording), so what differs is only what a game
 * is: the kicker over its name and what a row it sets itself reads and resets to. The other interfaces keep their own wording.
 * Pure strings, so they can be unit-tested.
 */
internal object PageWording {
    /** The kicker over the container's name in the XMB rail (and the other interfaces' header). */
    fun containerKicker(): String = L("Container settings")

    /** The kicker over a game's name: "Shortcut settings" for every kind of game in the XMB; the other interfaces say what kind it is. */
    fun shortcutKicker(xmb: Boolean, world: World): String = when {
        xmb -> L("Shortcut settings")
        world == World.LINUX -> L("Linux Steam client")
        world == World.ANDROID -> L("Android game")
        else -> L("Game settings")
    }

    /** The button on a row the game sets itself (XMB): what it goes back to, by what the row follows while unset. */
    fun resetLabel(inherit: Inherit?): String = when (inherit) {
        Inherit.APP -> L("Reset to app setting")
        Inherit.BUILT_IN -> L("Reset to default")
        else -> L("Reset to container")
    }

    /** The note beside the accent dot of such a row: the value the game follows while unset, [value] as the row shows it. */
    fun followsNote(inherit: Inherit?, value: String): String = when (inherit) {
        Inherit.APP -> L("App setting: %1\$s", value)
        Inherit.BUILT_IN -> L("Default: %1\$s", value)
        else -> L("Container: %1\$s", value)
    }
}
