package com.winlator.star.ui.deck

// "Press B again to exit" on the XMB home, as two small pure pieces the shell wires together (DeckShell). Kept free of Android
// and Compose so the timing and the key bookkeeping can be unit-tested.

/** How long after the first B at Home the second one still exits; the "Press B again to exit" pill stays up as long. */
internal const val XMB_EXIT_WINDOW_MS = 2000L

/**
 * The double-press rule: a press starts the window, a second one inside it exits. [now] is any monotonic millisecond clock
 * (the shell passes SystemClock.elapsedRealtime()). A clock that went backwards counts as a new first press.
 */
internal class XmbHomeExit(private val windowMs: Long = XMB_EXIT_WINDOW_MS) {
    private var firstAt = NONE

    /** One back press at Home: true when it is the second inside the window (the app should exit), false when it is the first. */
    fun press(now: Long): Boolean {
        val first = firstAt
        if (first != NONE && now - first in 0L..windowMs) {
            firstAt = NONE
            return true
        }
        firstAt = now
        return false
    }

    /** Forget a waiting first press (the page changed). */
    fun reset() {
        firstAt = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/**
 * Which B key-ups at Home are real presses. The shell is offered only the keys the view tree left unhandled, and the Home view
 * consumes a B key-down (closing a menu, leaving a page) but never the key-up; so the shell also sees the key-up of a B whose
 * work was already done. Only a key-up whose key-down the shell saw ARRIVE (as a fresh, non-repeat press) counts. Repeats of a
 * held key neither arm nor count, so holding B is at most one press.
 */
internal class XmbBackKeyArm {
    private var armed = false

    /**
     * Feed it every B event the shell is offered at Home. True exactly once per physical press: on the key-up that matches an
     * unconsumed key-down (a cancelled key-up counts for nothing).
     */
    fun onKey(down: Boolean, repeatCount: Int, canceled: Boolean): Boolean {
        if (down) {
            if (repeatCount == 0) armed = true
            return false
        }
        val counted = armed && !canceled
        armed = false
        return counted
    }

    /** Drop a waiting key-down (the shell left Home, or a different key took over). */
    fun reset() {
        armed = false
    }
}
