package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules the XMB tabs page's two-column lists share: the columns, the lines of a flat list, and the cursor's moves between them. */
class XmbTwoColumnNavTest {

    @Test fun columns_twoOnAWideScreen_oneOnANarrowOne() {
        assertEquals(1, xmbTwoColColumns(0f))
        assertEquals(1, xmbTwoColColumns(XMB_TWO_COL_MIN_DP - 1f))
        assertEquals(2, xmbTwoColColumns(XMB_TWO_COL_MIN_DP))
        assertEquals(2, xmbTwoColColumns(2000f))
    }

    // 0 heading, 1 2 3 cards, 4 heading, 5 6 cards, 7 heading (nothing under it), 8 card
    private val full = listOf(true, false, false, false, true, false, false, true, false)

    @Test fun lines_aHeadingIsAlone_cardsFillLinesOfTwo_aRunEndsAtTheNextHeading() {
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6), listOf(7), listOf(8)),
            xmbTwoColLines(full, 2),
        )
    }

    @Test fun lines_oneColumnIsOneItemPerLine_noItemsNoLines() {
        assertEquals(full.indices.map { listOf(it) }, xmbTwoColLines(full, 1))
        assertEquals(full.indices.map { listOf(it) }, xmbTwoColLines(full, 0))
        assertTrue(xmbTwoColLines(emptyList(), 2).isEmpty())
    }

    @Test fun lines_everyItemIsOnExactlyOneLine_inOrder() {
        for (cols in 1..3) {
            assertEquals(full.indices.toList(), xmbTwoColLines(full, cols).flatten())
        }
    }

    @Test fun lineOfAndCol_findAnItemsPlace() {
        val lines = xmbTwoColLines(full, 2)
        assertEquals(1, xmbTwoColLineOf(lines, 2))
        assertEquals(1, xmbTwoColCol(lines, 2))
        assertEquals(0, xmbTwoColCol(lines, 1))
        assertEquals(2, xmbTwoColLineOf(lines, 3))
        assertEquals(0, xmbTwoColCol(lines, 3))
        assertEquals(-1, xmbTwoColLineOf(lines, 99))
        assertEquals(-1, xmbTwoColCol(lines, 99))
    }

    @Test fun side_movesWithinTheLine_stopsAtItsEnds() {
        val lines = xmbTwoColLines(full, 2)
        assertEquals(2, xmbTwoColSide(lines, 1, 1))
        assertEquals(1, xmbTwoColSide(lines, 2, -1))
        assertEquals(2, xmbTwoColSide(lines, 2, 1))
        assertEquals(1, xmbTwoColSide(lines, 1, -1))
        assertEquals(3, xmbTwoColSide(lines, 3, 1))
        assertEquals(99, xmbTwoColSide(lines, 99, 1))
    }

    @Test fun vertical_keepsTheWantedColumn_acrossHeadingsAndShortLines() {
        val lines = xmbTwoColLines(full, 2)
        // Right column, down through the lone card 3 (clamped), the heading 4, onto the right column of [5 6].
        assertEquals(3, xmbTwoColVertical(lines, 2, down = true, wantCol = 1))
        assertEquals(4, xmbTwoColVertical(lines, 3, down = true, wantCol = 1))
        assertEquals(6, xmbTwoColVertical(lines, 4, down = true, wantCol = 1))
        assertEquals(5, xmbTwoColVertical(lines, 4, down = true, wantCol = 0))
        // And back up the same way.
        assertEquals(4, xmbTwoColVertical(lines, 6, down = false, wantCol = 1))
        assertEquals(3, xmbTwoColVertical(lines, 4, down = false, wantCol = 1))
        assertEquals(2, xmbTwoColVertical(lines, 3, down = false, wantCol = 1))
        assertEquals(1, xmbTwoColVertical(lines, 3, down = false, wantCol = 0))
    }

    @Test fun vertical_skipsWhatIsNotSelectable_theEndsGoToTheBarOrStay() {
        val lines = xmbTwoColLines(full, 2)
        val noHeadings = { i: Int -> !full[i] }
        // From card 3 down: heading 4 skipped, [5 6] reached.
        assertEquals(5, xmbTwoColVertical(lines, 3, down = true, wantCol = 0, selectable = noHeadings))
        assertEquals(3, xmbTwoColVertical(lines, 5, down = false, wantCol = 0, selectable = noHeadings))
        // Up from the first selectable line is the bar; Down from the last stays (the headings above are skipped).
        assertEquals(XMB_TWO_COL_TO_BAR, xmbTwoColVertical(lines, 1, down = false, wantCol = 0, selectable = noHeadings))
        assertEquals(8, xmbTwoColVertical(lines, 8, down = true, wantCol = 0, selectable = noHeadings))
        assertEquals(8, xmbTwoColVertical(lines, 6, down = true, wantCol = 1, selectable = noHeadings))
    }

    @Test fun vertical_oneColumnStepsLineByLine() {
        val lines = xmbTwoColLines(listOf(false, false, false), 1)
        assertEquals(1, xmbTwoColVertical(lines, 0, down = true, wantCol = 1))
        assertEquals(2, xmbTwoColVertical(lines, 2, down = true, wantCol = 0))
        assertEquals(XMB_TWO_COL_TO_BAR, xmbTwoColVertical(lines, 0, down = false, wantCol = 0))
    }
}
