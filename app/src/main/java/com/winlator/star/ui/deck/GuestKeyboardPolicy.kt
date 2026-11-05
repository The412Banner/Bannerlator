package com.winlator.star.ui.deck

/** What the host does with the in-game keyboard panel after an event. */
enum class GuestKeyboardAction { NONE, SHOW, HIDE }

/**
 * When the in-game keyboard panel is up. Pure state: the panel only opens when the user asks (the show-keyboard button or
 * Select + X) and goes when they ask again, close it with B or Back, or a menu opens. A menu over the game and the panel
 * both own the pad, so only one is up: opening a menu takes the panel down, and the panel cannot be opened over a menu.
 */
class GuestKeyboardPolicy {
    var visible = false
        private set

    private var menu = false

    /** The show-keyboard button or Select + X: opens the panel, or closes it when it is up. */
    fun onUserToggle(): GuestKeyboardAction {
        if (visible) return close()
        if (menu) return GuestKeyboardAction.NONE
        visible = true
        return GuestKeyboardAction.SHOW
    }

    /** A menu over the game opened or closed. */
    fun onMenuOpen(open: Boolean): GuestKeyboardAction {
        menu = open
        return if (open) close() else GuestKeyboardAction.NONE
    }

    /** B, the hide key, Back, or the game going to the background or ending: down at once. */
    fun close(): GuestKeyboardAction {
        if (!visible) return GuestKeyboardAction.NONE
        visible = false
        return GuestKeyboardAction.HIDE
    }
}
