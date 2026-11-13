package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB Home's tabs page: the tab order and stepping along it, and the Stores page's 3 + 2 grid and the D-pad's moves in it. */
class XmbTabsLogicTest {

    private val delta = 0.001f

    @Test fun tabs_allThreeInBarOrder_friendsAlwaysThere() {
        assertEquals(
            listOf(XmbSteamTab.STORES, XmbSteamTab.DOWNLOADS, XmbSteamTab.FRIENDS),
            XMB_PAGE_TABS,
        )
    }

    @Test fun step_wrapsRoundTheEnds() {
        assertEquals(1, xmbTabStep(0, 1, 3, wrap = true))
        assertEquals(2, xmbTabStep(0, -1, 3, wrap = true))
        assertEquals(0, xmbTabStep(2, 1, 3, wrap = true))
        assertEquals(2, xmbTabStep(0, -4, 3, wrap = true))
    }

    @Test fun step_stopsAtTheEndsWithoutWrap() {
        assertEquals(0, xmbTabStep(0, -1, 3, wrap = false))
        assertEquals(2, xmbTabStep(2, 1, 3, wrap = false))
        assertEquals(2, xmbTabStep(1, 1, 3, wrap = false))
    }

    @Test fun step_aStrayIndexOrNoTabsIsSafe() {
        assertEquals(0, xmbTabStep(7, 1, 3, wrap = true))
        assertEquals(2, xmbTabStep(7, 0, 3, wrap = false))
        assertEquals(0, xmbTabStep(0, 1, 0, wrap = true))
    }

    @Test fun rows_fiveStoresAreThreeThenTwo() {
        assertEquals(listOf(3, 2), xmbStoreRows(5))
        assertEquals(listOf(3), xmbStoreRows(3))
        assertEquals(listOf(3, 3, 1), xmbStoreRows(7))
        assertEquals(emptyList<Int>(), xmbStoreRows(0))
    }

    @Test fun grid_cardsAreAsLargeAsFitAndSquare_heightLimited() {
        // 600 x 200: two rows and a 12 gap leave 94 each, narrower than the 192 the width would allow.
        val g = xmbStoresGrid(5, 600f, 200f)
        assertEquals(94f, g.side, delta)
    }

    @Test fun grid_cardsAreAsLargeAsFitAndSquare_widthLimited() {
        // 300 x 800: three cards and two gaps share 300 wide, 92 each.
        val g = xmbStoresGrid(5, 300f, 800f)
        assertEquals((300f - 24f) / 3f, g.side, delta)
    }

    @Test fun grid_topRowIsThreeAndBottomRowTwoCentredBeneath() {
        val g = xmbStoresGrid(5, 600f, 200f)
        val s = g.side
        // The top row spans the whole of its own width, centred.
        val topW = 3 * s + 2 * 12f
        assertEquals((600f - topW) / 2f, g.x(0), delta)
        assertEquals(g.x(0) + s + 12f, g.x(1), delta)
        assertEquals(g.x(1) + s + 12f, g.x(2), delta)
        // The bottom row's two cards sit under the gaps between the top three: centred about the same middle.
        val bottomW = 2 * s + 12f
        assertEquals((600f - bottomW) / 2f, g.x(3), delta)
        assertEquals(g.x(3) + s + 12f, g.x(4), delta)
        assertEquals(300f, (g.x(3) + g.x(4) + s) / 2f, delta)
        assertEquals(300f, (g.x(0) + g.x(2) + s) / 2f, delta)
    }

    @Test fun grid_rowsAreEvenlySpacedAndCentredInTheHeight() {
        val g = xmbStoresGrid(5, 800f, 400f)
        val s = g.side
        assertEquals(g.y(0) + s + 12f, g.y(3), delta)
        assertEquals(g.y(0), g.y(1), delta)
        assertEquals(g.y(3), g.y(4), delta)
        // The two rows together sit in the middle of the height.
        assertEquals(200f, (g.y(0) + g.y(3) + s) / 2f, delta)
        assertEquals(5, g.count)
    }

    @Test fun grid_aBoxTooSmallGivesNoNegativeSide() {
        assertEquals(0f, xmbStoresGrid(5, 10f, 10f).side, delta)
        assertEquals(0f, xmbStoresGrid(0, 600f, 200f).side, delta)
    }

    @Test fun grid_rowAndColumnOfEachCard() {
        val g = xmbStoresGrid(5, 600f, 300f)
        assertEquals(listOf(0, 0, 0, 1, 1), (0..4).map { g.rowOf(it) })
        assertEquals(listOf(0, 1, 2, 0, 1), (0..4).map { g.colOf(it) })
    }

    private val rows = listOf(3, 2)

    @Test fun move_leftAndRightStayInTheirRow() {
        assertEquals(1, xmbStoresMove(rows, 0, 1, 0))
        assertEquals(2, xmbStoresMove(rows, 1, 1, 0))
        assertEquals(2, xmbStoresMove(rows, 2, 1, 0))
        assertEquals(0, xmbStoresMove(rows, 0, -1, 0))
        assertEquals(3, xmbStoresMove(rows, 3, -1, 0))
        assertEquals(4, xmbStoresMove(rows, 3, 1, 0))
        assertEquals(4, xmbStoresMove(rows, 4, 1, 0))
    }

    @Test fun move_downLandsOnTheNearestCardBeneath() {
        assertEquals(3, xmbStoresMove(rows, 0, 0, 1))
        // The middle card is as near to either of the two: the first wins.
        assertEquals(3, xmbStoresMove(rows, 1, 0, 1))
        assertEquals(4, xmbStoresMove(rows, 2, 0, 1))
        // Nothing under the last row.
        assertEquals(3, xmbStoresMove(rows, 3, 0, 1))
        assertEquals(4, xmbStoresMove(rows, 4, 0, 1))
    }

    @Test fun move_upLandsOnTheNearestCardAboveAndLeavesForTheBarFromTheTopRow() {
        assertEquals(0, xmbStoresMove(rows, 3, 0, -1))
        assertEquals(1, xmbStoresMove(rows, 4, 0, -1))
        for (i in 0..2) assertEquals(XMB_STORES_TO_BAR, xmbStoresMove(rows, i, 0, -1))
    }

    @Test fun move_noKeyStaysPut_andNoStoresGoesToTheBar() {
        assertEquals(2, xmbStoresMove(rows, 2, 0, 0))
        assertEquals(XMB_STORES_TO_BAR, xmbStoresMove(emptyList(), 0, 0, 1))
        assertTrue(xmbStoresMove(listOf(1), 0, 0, 1) == 0)
    }
}
