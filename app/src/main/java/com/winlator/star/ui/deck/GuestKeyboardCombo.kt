package com.winlator.star.ui.deck

import android.view.KeyEvent

/**
 * The pad combo that opens and closes the in-game keyboard: X pressed while Select (or the pad's Back) is held. Pure
 * state, fed every key event of the game before anything else sees it.
 *  - Select is never touched: it passes through when it goes down and when it comes up, so it behaves as without the combo.
 *  - The X of a combo is taken together with its repeats and its release, so neither the game nor the panel sees half a press.
 *  - An X pressed before Select, or while the combo is not armed, is an ordinary X.
 *  - Only a pad counts. The phone's own Back is not Select, nor is the Back Android makes out of a pad's unhandled B.
 */
class GuestKeyboardCombo {
    enum class Verdict {
        /** Not part of the combo: handle the key as usual. */
        PASS,

        /** X went down with Select held: toggle the keyboard, and the key goes no further. */
        TOGGLE,

        /** The repeat or release of the X that toggled: the key goes no further. */
        CONSUME,
    }

    private var selectHeld = false
    private var xTaken = false

    /**
     * [armed] is false while something else owns X (a menu over the game): Select is still followed, but an X goes through.
     * [fromPad] is whether the event comes from a game controller, [madeFromB] whether a Back is the fallback Android
     * builds from an unhandled B (it keeps B's scan code) and not the pad's own Back key.
     */
    fun onKey(keyCode: Int, action: Int, repeatCount: Int, fromPad: Boolean, madeFromB: Boolean, armed: Boolean): Verdict {
        if (!fromPad) return Verdict.PASS
        val down = action == KeyEvent.ACTION_DOWN
        val up = action == KeyEvent.ACTION_UP
        if (!down && !up) return Verdict.PASS
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectHeld = down
            KeyEvent.KEYCODE_BACK -> if (!madeFromB) selectHeld = down
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (up) return if (xTaken) release() else Verdict.PASS
                if (xTaken) return Verdict.CONSUME
                if (repeatCount == 0 && selectHeld && armed) {
                    xTaken = true
                    return Verdict.TOGGLE
                }
            }
        }
        return Verdict.PASS
    }

    /** The window lost the focus or the game went to the background: releases made there never arrive, so nothing stays held. */
    fun reset() {
        selectHeld = false
        xTaken = false
    }

    private fun release(): Verdict {
        xTaken = false
        return Verdict.CONSUME
    }
}
