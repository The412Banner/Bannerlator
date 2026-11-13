package com.winlator.star.store.download

import com.winlator.star.ui.deck.ScreenScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The game page's layout rules: side by side or stacked, and the places and widths of the action row, the cover and the left column. Plain JVM. */
class StorePageGeometryTest {

    /** The window sizes the page is checked in (dp): a phone, a large phone, a huge window, a small phone and a tablet, all landscape. */
    private val landscapes = listOf(874f to 393f, 2000f to 918f, 2400f to 1080f, 640f to 360f, 1280f to 800f)

    private fun scaleOf(width: Float, height: Float) = ScreenScale(width, height).factor

    private fun geometry(width: Float, height: Float, safeSide: Float = 0f, safeTop: Float = 0f, buttons: Int = 2) =
        StorePageGeometry.of(width, height, scaleOf(width, height), safeSide, safeTop, buttons)

    /** The window's left side to the square buttons' near side. */
    private fun StorePageGeometry.pairStart() = edge + installWidth + primaryGap

    /** The window's left side to the cover's near side, in a window [width] wide. */
    private fun StorePageGeometry.coverStart(width: Float) = width - edge - coverWidth

    @Test fun landscapeWindowsGoSideBySide() {
        assertEquals(StorePageLayout.SIDE_BY_SIDE, storePageLayoutFor(874f, 393f))
        assertEquals(StorePageLayout.SIDE_BY_SIDE, storePageLayoutFor(1280f, 800f))
    }

    @Test fun portraitAndNearlySquareWindowsStack() {
        assertEquals(StorePageLayout.STACKED, storePageLayoutFor(393f, 874f))
        assertEquals(StorePageLayout.STACKED, storePageLayoutFor(600f, 600f))
        // A little under the ratio stacks, a little over goes side by side.
        assertEquals(StorePageLayout.STACKED, storePageLayoutFor(119f, 100f))
        assertEquals(StorePageLayout.SIDE_BY_SIDE, storePageLayoutFor(121f, 100f))
    }

    @Test fun theCoverIsExactlyAsWideAsThePrimaryButton() {
        for ((w, h) in landscapes + listOf(412f to 892f, 800f to 1280f, 300f to 600f)) {
            val g = geometry(w, h)
            assertEquals("$w x $h", g.installWidth, g.coverWidth, 0f)
        }
    }

    @Test fun sideBySide_theButtonsAreCentredOnTheWindow() {
        for ((w, h) in landscapes) {
            for (buttons in 1..2) {
                for (safeSide in listOf(0f, 48f)) {
                    val g = geometry(w, h, safeSide = safeSide, buttons = buttons)
                    assertEquals("$w x $h, $buttons buttons, safe $safeSide", w / 2f, g.pairStart() + g.pairWidth / 2f, 0.01f)
                }
            }
        }
    }

    @Test fun sideBySide_theGapsOnBothSidesOfTheButtonsAreEqualAndRoomy() {
        for ((w, h) in landscapes) {
            val g = geometry(w, h)
            val least = StorePageGeometry.FREE_GAP_MIN * scaleOf(w, h)
            assertEquals("$w x $h", g.primaryGap, g.coverStart(w) - (g.pairStart() + g.pairWidth), 0.01f)
            assertTrue("$w x $h", g.primaryGap >= least - 0.01f)
        }
    }

    @Test fun sideBySide_theLeftColumnRunsFromTheEdgeToTheFarSideOfTheButtons() {
        for ((w, h) in landscapes) {
            val g = geometry(w, h, safeSide = 30f)
            assertEquals("$w x $h", w / 2f + g.pairWidth / 2f, g.edge + g.leftWidth, 0.01f)
        }
    }

    @Test fun sideBySide_theColumnAndTheCoverNeverMeet() {
        for ((w, h) in landscapes + listOf(500f to 400f, 360f to 300f, 480f to 360f)) {
            val g = geometry(w, h, safeSide = 24f)
            assertTrue("$w x $h", g.edge + g.leftWidth < g.coverStart(w))
        }
    }

    @Test fun sideBySide_thePrimaryButtonTakesAShareOfTheWidth() {
        val g = geometry(874f, 393f)
        val scale = scaleOf(874f, 393f)
        assertEquals(874f * StorePageGeometry.INSTALL_SHARE, g.installWidth, 0.001f)
        assertEquals(StorePageGeometry.BUTTON_SIZE * scale, g.buttonSize, 0.001f)
        assertEquals(StorePageGeometry.GAP * scale, g.gap, 0.001f)
        assertEquals(0f, g.artReveal, 0f)
    }

    @Test fun sideBySide_theShareIsHeldAtTheLargestWidth() {
        for ((w, h) in listOf(2000f to 918f, 2400f to 1080f)) {
            assertEquals("$w x $h", StorePageGeometry.INSTALL_MAX * scaleOf(w, h), geometry(w, h).installWidth, 0.001f)
        }
    }

    @Test fun sideBySide_aVerySmallWindowShrinksTheButtonInsteadOfOverlapping() {
        val g = geometry(360f, 300f)
        val scale = scaleOf(360f, 300f)
        assertTrue(g.installWidth < StorePageGeometry.INSTALL_MIN * scale)
        assertEquals(StorePageGeometry.FREE_GAP_MIN * scale, g.primaryGap, 0.001f)
    }

    @Test fun theEdgesAndTheBandFollowThePageMarginAndTheSafeRoom() {
        val g = geometry(874f, 393f, safeSide = 40f, safeTop = 10f)
        val scale = scaleOf(874f, 393f)
        assertEquals(40f + StorePageGeometry.MARGIN * scale, g.edge, 0.001f)
        assertEquals(10f + StorePageGeometry.MARGIN * scale, g.top, 0.001f)
        assertEquals(StorePageGeometry.MARGIN * scale, g.margin, 0.001f)
        assertEquals(StorePageGeometry.BACK_SIZE * scale, g.bandHeight, 0.001f)
        assertEquals(StorePageGeometry.BAND_GAP * scale, g.bandGap, 0.001f)
    }

    @Test fun stacked_theColumnFillsTheWindowBetweenTheEdges() {
        val g = geometry(412f, 892f)
        assertEquals(StorePageLayout.STACKED, g.layout)
        assertEquals(412f - 2f * StorePageGeometry.MARGIN, g.leftWidth, 0.001f)
        assertEquals(g.leftWidth - 2f * (StorePageGeometry.BUTTON_SIZE + StorePageGeometry.GAP), g.installWidth, 0.001f)
        assertEquals(g.gap, g.primaryGap, 0f)
    }

    @Test fun stacked_aTabletDoesNotStretchTheColumn() {
        val g = geometry(1200f, 1900f)
        assertEquals(StorePageGeometry.STACKED_MAX * scaleOf(1200f, 1900f), g.leftWidth, 0.001f)
    }

    @Test fun stacked_withoutACoverTheTopOfThePageIsLeftToTheArt() {
        assertEquals(892f * StorePageGeometry.STACKED_ART_SHARE, geometry(412f, 892f).artReveal, 0.001f)
    }

    @Test fun theButtonNeverGetsNegativeWidth() {
        assertEquals(0f, geometry(100f, 400f).installWidth, 0f)
        assertEquals(0f, geometry(60f, 30f).installWidth, 0f)
    }
}
