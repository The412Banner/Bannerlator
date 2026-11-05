package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** The XMB games row's slot maths (the All Games card after the last game), its row limit, and the All Games page's grid. */
class XmbRowLogicTest {

    private val paths = listOf("a.desktop", "b.desktop", "c.desktop")

    @Test fun slotOf_withAllCard_leavesTheGamesWhereTheyAre() {
        assertEquals(0, xmbSlotOf(paths, "a.desktop", hasAll = true))
        assertEquals(2, xmbSlotOf(paths, "c.desktop", hasAll = true))
    }

    @Test fun slotOf_withoutAllCard_isThePlainIndex() {
        assertEquals(0, xmbSlotOf(paths, "a.desktop", hasAll = false))
        assertEquals(2, xmbSlotOf(paths, "c.desktop", hasAll = false))
    }

    @Test fun slotOf_allCardPath_isTheSlotAfterTheLastGameOnlyWhenThereIsACard() {
        assertEquals(3, xmbSlotOf(paths, XMB_ALL_PATH, hasAll = true))
        assertEquals(-1, xmbSlotOf(paths, XMB_ALL_PATH, hasAll = false))
    }

    @Test fun slotOf_noGames_theCardIsSlotZero() {
        assertEquals(0, xmbSlotOf(emptyList(), XMB_ALL_PATH, hasAll = true))
    }

    @Test fun gridColumns_wideScreen_isSix() {
        assertEquals(6, xmbGridColumns(avail = 740f, gap = 12f, minCell = 80f))
        assertEquals(6, xmbGridColumns(avail = 1400f, gap = 12f, minCell = 80f))
    }

    @Test fun gridColumns_narrowScreen_dropsColumnsButNotBelowThree() {
        assertEquals(4, xmbGridColumns(avail = 380f, gap = 12f, minCell = 80f))
        assertEquals(3, xmbGridColumns(avail = 280f, gap = 12f, minCell = 80f))
        assertEquals(3, xmbGridColumns(avail = 100f, gap = 12f, minCell = 80f))
    }

    @Test fun slotOf_unknownOrMissingPath_isNone() {
        assertEquals(-1, xmbSlotOf(paths, "gone.desktop", hasAll = true))
        assertEquals(-1, xmbSlotOf(paths, null, hasAll = true))
        assertEquals(-1, xmbSlotOf(emptyList(), "a.desktop", hasAll = true))
    }

    @Test fun rowOf_keepsTheFirstSix_unlessTurnedOff() {
        val ten = (1..10).toList()
        assertEquals((1..6).toList(), xmbRowOf(ten))
        assertEquals(ten, xmbRowOf(ten, enabled = false))
        assertEquals(listOf(1, 2, 3), xmbRowOf(listOf(1, 2, 3)))
        assertEquals(XMB_ROW_LIMIT, xmbRowOf(ten).size)
    }

    @Test fun gridMove_leftRight_walkTheReadingOrder() {
        assertEquals(4, xmbGridMove(5, XmbMove.LEFT, cols = 3, count = 10, page = 9))
        assertEquals(3, xmbGridMove(2, XmbMove.RIGHT, cols = 3, count = 10, page = 9))
        assertEquals(0, xmbGridMove(0, XmbMove.LEFT, cols = 3, count = 10, page = 9))
        assertEquals(9, xmbGridMove(9, XmbMove.RIGHT, cols = 3, count = 10, page = 9))
    }

    @Test fun gridMove_upDown_stepARow() {
        assertEquals(1, xmbGridMove(4, XmbMove.UP, cols = 3, count = 10, page = 9))
        assertEquals(7, xmbGridMove(4, XmbMove.DOWN, cols = 3, count = 10, page = 9))
        assertEquals(1, xmbGridMove(1, XmbMove.UP, cols = 3, count = 10, page = 9))
    }

    @Test fun gridMove_down_fromAboveAShortLastRow_landsOnTheLastCell() {
        // 10 cells in 3 columns: the last row holds only cell 9.
        assertEquals(9, xmbGridMove(7, XmbMove.DOWN, cols = 3, count = 10, page = 9))
        assertEquals(9, xmbGridMove(8, XmbMove.DOWN, cols = 3, count = 10, page = 9))
        // Already on the last row: nowhere to go.
        assertEquals(9, xmbGridMove(9, XmbMove.DOWN, cols = 3, count = 10, page = 9))
    }

    @Test fun gridMove_list_isOneColumn() {
        assertEquals(3, xmbGridMove(2, XmbMove.DOWN, cols = 1, count = 5, page = 3))
        assertEquals(1, xmbGridMove(2, XmbMove.UP, cols = 1, count = 5, page = 3))
    }

    @Test fun gridMove_pageJumps_clampToTheEnds() {
        assertEquals(9, xmbGridMove(5, XmbMove.PAGE_DOWN, cols = 3, count = 10, page = 9))
        assertEquals(0, xmbGridMove(5, XmbMove.PAGE_UP, cols = 3, count = 10, page = 9))
    }

    @Test fun gridMove_emptyGrid_staysAtZero() {
        assertEquals(0, xmbGridMove(0, XmbMove.DOWN, cols = 3, count = 0, page = 9))
    }
}
