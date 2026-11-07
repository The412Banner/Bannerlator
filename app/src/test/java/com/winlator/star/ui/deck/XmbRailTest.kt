package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the XMB side rails (XmbRail.kt): what a tab does when it gains focus (select it, send focus to the
 * selected tab, or nothing) and when an Up / Down key still counts as "focus moved inside the rail". No Compose runtime or
 * Android classes involved.
 */
class XmbRailTest {

    // ---- What a tab does when it gains focus ----

    @Test
    fun theSelectedTabNeverDoesAnything() {
        // Entering the rail lands on it (redirect target, first focus): nothing to select, nothing to redirect.
        assertEquals(XmbRailAct.NONE, xmbRailFocusAct(selected = true, moveFresh = false))
        assertEquals(XmbRailAct.NONE, xmbRailFocusAct(selected = true, moveFresh = true))
    }

    @Test
    fun anotherTabAfterAnUpDownKeyIsSelected() {
        assertEquals(XmbRailAct.SELECT, xmbRailFocusAct(selected = false, moveFresh = true))
    }

    @Test
    fun anotherTabWithoutAKeyMeansFocusEnteredSoItGoesToTheSelectedTab() {
        // Left from the page lands on the nearest tab: it is not selected, focus is handed on to the selected one.
        assertEquals(XmbRailAct.REDIRECT, xmbRailFocusAct(selected = false, moveFresh = false))
    }

    // ---- How long an Up / Down key counts ----

    @Test
    fun aKeyJustNowIsFresh() {
        assertTrue(xmbRailMoveFresh(now = 10_000L, stamp = 10_000L))
        assertTrue(xmbRailMoveFresh(now = 10_000L + XMB_RAIL_MOVE_WINDOW_MS, stamp = 10_000L))
    }

    @Test
    fun anOldKeyIsNotFresh() {
        assertFalse(xmbRailMoveFresh(now = 10_000L + XMB_RAIL_MOVE_WINDOW_MS + 1, stamp = 10_000L))
        // Left pressed seconds after the last Up / Down in the rail: that is an entry, not a move.
        assertFalse(xmbRailMoveFresh(now = 60_000L, stamp = 10_000L))
    }

    @Test
    fun noKeyYetIsNotFresh() {
        assertFalse(xmbRailMoveFresh(now = 500L, stamp = 0L))
    }

    @Test
    fun aStampFromTheFutureIsNotFresh() {
        // The clock never runs backwards, but a bad stamp must not select a tab.
        assertFalse(xmbRailMoveFresh(now = 5_000L, stamp = 9_000L))
    }

    @Test
    fun theWindowCanBeGiven() {
        assertTrue(xmbRailMoveFresh(now = 1_100L, stamp = 1_000L, windowMs = 100L))
        assertFalse(xmbRailMoveFresh(now = 1_101L, stamp = 1_000L, windowMs = 100L))
    }

    @Test
    fun theSelectWaitIsShorterThanTheKeyWindow() {
        // The wait runs after the focus gain, which is inside the window; it only has to be short enough to feel immediate.
        assertTrue(XMB_RAIL_SELECT_DELAY_MS in 1L until 200L)
        assertTrue(XMB_RAIL_MOVE_WINDOW_MS > XMB_RAIL_SELECT_DELAY_MS)
    }
}
