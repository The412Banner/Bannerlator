package com.winlator.star.ui.deck

import androidx.compose.ui.geometry.Rect
import com.winlator.star.ui.screens.xmbRevealDelta
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure parts of the room the XMB's scrolling leaves beyond the highlighted item: its size, the grown request, the ends. */
class XmbRevealTest {

    // ---- The room ----

    @Test
    fun room_isAQuarterMoreThanTheDownloadsPageKeptUnderItsCards() {
        assertEquals(47f * 1.25f, XMB_REVEAL_ROOM_DP, 1f)
    }

    @Test
    fun room_inATallAreaIsTheWholeRoom() {
        assertEquals(150f, xmbRevealRoomIn(150f, 1000f), 0f)
    }

    @Test
    fun room_inAShortAreaIsAtMostItsShare() {
        assertEquals(100f, xmbRevealRoomIn(150f, 400f), 0f)
        assertEquals(25f, xmbRevealRoomIn(150f, 100f), 0f)
    }

    @Test
    fun room_neverNegative() {
        assertEquals(0f, xmbRevealRoomIn(150f, -10f), 0f)
        assertEquals(0f, xmbRevealRoomIn(-5f, 400f), 0f)
    }

    // ---- The request ----

    @Test
    fun request_growsAboveAndBelowAndKeepsItsSides() {
        val r = xmbRevealRect(Rect(10f, 200f, 90f, 260f), 50f)
        assertEquals(Rect(10f, 150f, 90f, 310f), r)
    }

    @Test
    fun request_withNoRoomIsTheItem() {
        val item = Rect(0f, 40f, 80f, 120f)
        assertEquals(item, xmbRevealRect(item, 0f))
    }

    // ---- The ends ----

    @Test
    fun step_stopsAtTheStartAndAtTheEnd() {
        assertEquals(0f, xmbRevealStep(-40f, canScrollBackward = false, canScrollForward = true), 0f)
        assertEquals(0f, xmbRevealStep(40f, canScrollBackward = true, canScrollForward = false), 0f)
    }

    @Test
    fun step_movesWhereTheAreaCanMove() {
        assertEquals(-40f, xmbRevealStep(-40f, canScrollBackward = true, canScrollForward = false), 0f)
        assertEquals(40f, xmbRevealStep(40f, canScrollBackward = false, canScrollForward = true), 0f)
        assertEquals(0f, xmbRevealStep(0f, canScrollBackward = false, canScrollForward = false), 0f)
    }

    // ---- With the shared delta ----

    @Test
    fun delta_goingDown_scrollsUntilTheRoomUnderTheItemIsThere() {
        // The item's bottom edge is on the viewport's: the plain scroll would stop here; the room asks for 50 px more.
        assertEquals(50f, xmbRevealDelta(cellTop = 540f, cellBottom = 600f, viewportHeight = 600f, topMargin = 50f, bottomMargin = 50f), 0f)
    }

    @Test
    fun delta_goingUp_scrollsUntilTheRoomOverTheItemIsThere() {
        assertEquals(-50f, xmbRevealDelta(cellTop = 0f, cellBottom = 60f, viewportHeight = 600f, topMargin = 50f, bottomMargin = 50f), 0f)
    }

    @Test
    fun delta_anItemWithTheRoomAroundIt_staysPut() {
        assertEquals(0f, xmbRevealDelta(cellTop = 200f, cellBottom = 260f, viewportHeight = 600f, topMargin = 50f, bottomMargin = 50f), 0f)
    }
}
