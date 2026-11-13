package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // The games here are plain strings; one with an "x" in front has no art.
    private val hasArt: (String) -> Boolean = { !it.startsWith("x") }

    @Test fun recentOrder_rowFirst_thenTheRest_withoutRepeats() {
        assertEquals(listOf("c", "a", "b", "d"), xmbRecentOrder(listOf("c", "a"), listOf("a", "b", "c", "d")) { it })
        assertEquals(listOf("a", "b"), xmbRecentOrder(emptyList(), listOf("a", "b")) { it })
    }

    @Test fun mosaic_cellsFillTheGrid_andOneIsLeftForTheMoreTile() {
        assertEquals(XMB_MOSAIC_ROWS * XMB_MOSAIC_COLS, XMB_MOSAIC_CELLS)
        assertEquals(XMB_MOSAIC_CELLS - 1, XMB_MOSAIC_COVERS)
    }

    @Test fun mosaic_moreThanSix_fiveCoversAndTheRestCounted() {
        val m = xmbMosaicPick((1..8).map { "g$it" }, hasArt)
        assertEquals(listOf("g1", "g2", "g3", "g4", "g5"), m.covers)
        assertEquals(3, m.more)
        assertEquals(1, xmbMosaicPick((1..7).map { "g$it" }, hasArt).more)
    }

    @Test fun mosaic_skipsGamesWithoutArtWhenItCan() {
        val m = xmbMosaicPick(listOf("xa", "b", "xc", "d", "e", "f", "g", "h"), hasArt)
        assertEquals(listOf("b", "d", "e", "f", "g"), m.covers)
        assertEquals(3, m.more)
    }

    @Test fun mosaic_fewGamesWithArt_areFilledUpWithTheOthersInOrder() {
        val m = xmbMosaicPick(listOf("xa", "b", "xc", "xd", "xe", "xf", "xg"), hasArt)
        assertEquals(listOf("b", "xa", "xc", "xd", "xe"), m.covers)
        assertEquals(2, m.more)
    }

    @Test fun mosaic_sixOrFewerGames_showEveryCoverAndNoMoreTile() {
        val six = xmbMosaicPick((1..6).map { "g$it" }, hasArt)
        assertEquals((1..6).map { "g$it" }, six.covers)
        assertEquals(0, six.more)
        val five = xmbMosaicPick((1..5).map { "g$it" }, hasArt)
        assertEquals((1..5).map { "g$it" }, five.covers)
        assertEquals(0, five.more)
        val three = xmbMosaicPick(listOf("a", "b", "c"), hasArt)
        assertEquals(listOf("a", "b", "c"), three.covers)
        assertEquals(0, three.more)
        val two = xmbMosaicPick(listOf("xa", "b"), hasArt)
        assertEquals(listOf("b", "xa"), two.covers)
        assertEquals(0, two.more)
    }

    @Test fun mosaic_noGames_isEmpty() {
        val m = xmbMosaicPick(emptyList<String>(), hasArt)
        assertTrue(m.covers.isEmpty())
        assertEquals(0, m.more)
    }

    @Test fun collagePick_moreThanFifteen_takesTheFifteenMostRecentWithArt() {
        val games = listOf("xa") + (1..20).map { "g$it" }
        assertEquals((1..15).map { "g$it" }, xmbCollagePick(games, hasArt))
    }

    @Test fun collagePick_exactlyFifteen_isThemInOrder() {
        assertEquals((1..15).map { "g$it" }, xmbCollagePick((1..15).map { "g$it" }, hasArt))
    }

    @Test fun collagePick_fewerThanFifteen_repeatsCyclicallyUntilAllCellsAreFilled() {
        val p = xmbCollagePick(listOf("xa", "a", "b", "c", "xd"), hasArt)
        assertEquals(15, p.size)
        assertEquals(listOf("a", "b", "c", "a", "b", "c", "a", "b", "c", "a", "b", "c", "a", "b", "c"), p)
        val one = xmbCollagePick(listOf("only"), hasArt)
        assertEquals(List(15) { "only" }, one)
        val fourteen = xmbCollagePick((1..14).map { "g$it" }, hasArt)
        assertEquals((1..14).map { "g$it" } + "g1", fourteen)
    }

    @Test fun collagePick_noGameWithArt_isEmpty() {
        assertTrue(xmbCollagePick(listOf("xa", "xb"), hasArt).isEmpty())
        assertTrue(xmbCollagePick(emptyList<String>(), hasArt).isEmpty())
    }

    @Test fun collageSpec_noCovers_isNone() {
        assertNull(xmbCollageSpec(2.2f, 0))
    }

    @Test fun collageSpec_alwaysFiveByThree() {
        for (aspect in listOf(0.46f, 1f, 1.78f, 2.2f)) {
            val s = xmbCollageSpec(aspect, XMB_COLLAGE_MAX)!!
            assertEquals(5, s.cols)
            assertEquals(3, s.rows)
        }
    }

    @Test fun collageSpec_landscape_is540TallAndFollowsTheShape() {
        val s = xmbCollageSpec(1.78f, 15)!!
        assertEquals(XMB_COLLAGE_HEIGHT, s.height)
        assertEquals(961, s.width)
    }

    @Test fun collageSpec_veryWideScreen_isCappedAtTheMaxSide() {
        val s = xmbCollageSpec(2.2f, 15)!!
        assertEquals(XMB_COLLAGE_HEIGHT, s.height)
        assertEquals(XMB_COLLAGE_MAX_SIDE, s.width)
    }

    @Test fun collageSpec_portrait_is540WideAtMostAndFollowsTheShape() {
        val s = xmbCollageSpec(0.5f, 15)!!
        assertEquals(540, s.width)
        assertEquals(1080, s.height)
        val tall = xmbCollageSpec(0.46f, 15)!!
        assertEquals(XMB_COLLAGE_MAX_SIDE, tall.height)
        assertEquals(497, tall.width)
    }

    @Test fun collageSpec_staysUnderTwoAndAHalfMegabytes() {
        for (aspect in listOf(0.3f, 0.46f, 0.75f, 1f, 1.78f, 2.2f, 3f)) {
            val s = xmbCollageSpec(aspect, 15)!!
            assertTrue(s.width * s.height * 4 <= 2_500_000)
        }
    }

    @Test fun collageBackdrop_isSofterThanTheOtherBackdrops() {
        // Below API 31 the collage is shrunk far less than a cover (here by 1 = not at all), and the divisor never goes below 1.
        assertEquals(1080 to 540, xmbBackdropSoftSize(1080, 540, XMB_COLLAGE_SOFT_DIV))
        assertEquals(1080 to 540, xmbBackdropSoftSize(1080, 540, 0))
        assertTrue(XMB_COLLAGE_SOFT_DIV < XMB_BACKDROP_SOFT_DIV)
    }

    @Test fun collageSpec_nonsenseAspect_stillGivesAGrid() {
        val s = xmbCollageSpec(0f, 4)!!
        assertEquals(XMB_COLLAGE_HEIGHT, s.height)
        assertEquals(5 * 3, s.cols * s.rows)
    }

    @Test fun cellRect_cellsTileThePictureExactly() {
        assertEquals(XmbRect(0, 0, 33, 25), xmbCellRect(0, 3, 2, 100, 50))
        assertEquals(XmbRect(66, 0, 100, 25), xmbCellRect(2, 3, 2, 100, 50))
        assertEquals(XmbRect(0, 25, 33, 50), xmbCellRect(3, 3, 2, 100, 50))
        assertEquals(XmbRect(66, 25, 100, 50), xmbCellRect(5, 3, 2, 100, 50))
    }

    @Test fun centerCrop_widePicture_losesItsSides() {
        assertEquals(XmbRect(50, 0, 150, 100), xmbCenterCrop(200, 100, 100, 100))
    }

    @Test fun centerCrop_tallPicture_losesTopAndBottom() {
        assertEquals(XmbRect(0, 50, 100, 150), xmbCenterCrop(100, 200, 100, 100))
    }

    @Test fun centerCrop_sameShape_keepsEverything() {
        assertEquals(XmbRect(0, 0, 600, 900), xmbCenterCrop(600, 900, 200, 300))
    }

    @Test fun centerCrop_emptyBox_keepsEverything() {
        assertEquals(XmbRect(0, 0, 10, 20), xmbCenterCrop(10, 20, 0, 5))
    }

    @Test fun allHeader_isAtLeastAsTallAsTheBackArrowsTapArea() {
        assertEquals(XMB_ALL_HEADER_MIN_HEIGHT, xmbAllHeader(0f, 0.85f).height, 0f)
        assertEquals(48f, xmbAllHeader(0f, 1f).height, 0f)
        // A big screen: the row grows with the scale.
        assertEquals(64f, xmbAllHeader(0f, 1.6f).height, 0.001f)
    }

    @Test fun allHeader_startsUnderTheInsetAndTheBandCoversTheRowAndItsGap() {
        val h = xmbAllHeader(30f, 1f)
        assertEquals(30f + XMB_ALL_HEADER_TOP, h.top, 0f)
        assertEquals(h.top + h.height + XMB_ALL_HEADER_GAP, h.band, 0f)
    }

    @Test fun allHeader_noInsetOrANegativeOne_startsAtTheRowsOwnGap() {
        assertEquals(XMB_ALL_HEADER_TOP, xmbAllHeader(0f, 1f).top, 0f)
        assertEquals(XMB_ALL_HEADER_TOP, xmbAllHeader(-5f, 1f).top, 0f)
    }

    @Test fun allHeader_fadeStartsInsideTheRowAndEndsAtTheBand() {
        for (scale in listOf(0.85f, 1f, 1.6f)) {
            val h = xmbAllHeader(24f, scale)
            assertTrue(h.fadeStart > h.top)
            assertTrue(h.fadeStart < h.band)
        }
    }

    @Test fun revealDelta_cellBelowTheHeaderAndAboveTheHints_staysPut() {
        assertEquals(0f, xmbRevealDelta(cellTop = 200f, cellBottom = 400f, viewportHeight = 600f, topMargin = 100f, bottomMargin = 50f), 0f)
    }

    @Test fun revealDelta_cellUnderTheHeader_scrollsBackUpToTheBandsEdge() {
        assertEquals(-60f, xmbRevealDelta(cellTop = 40f, cellBottom = 240f, viewportHeight = 600f, topMargin = 100f, bottomMargin = 50f), 0f)
    }

    @Test fun revealDelta_cellPastTheBottom_scrollsForwardJustEnough() {
        assertEquals(70f, xmbRevealDelta(cellTop = 400f, cellBottom = 620f, viewportHeight = 600f, topMargin = 100f, bottomMargin = 50f), 0f)
    }

    @Test fun revealDelta_cellTallerThanTheRoom_showsItsTopNotItsBottom() {
        // 700 px tall in 450 px of room: scrolling its whole overhang would push its top under the header.
        assertEquals(100f, xmbRevealDelta(cellTop = 200f, cellBottom = 900f, viewportHeight = 600f, topMargin = 100f, bottomMargin = 50f), 0f)
    }
}
