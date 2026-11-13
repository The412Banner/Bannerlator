package com.winlator.star.store.download

import com.winlator.star.ui.deck.ScreenScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The game page's layout rules: side by side or stacked, and the widths of the action row, the cover and the left column. Plain JVM. */
class StorePageGeometryTest {

    private val margin = 12f

    private fun geometry(width: Float, height: Float, sideMargin: Float = margin) =
        StorePageGeometry.of(width, height, ScreenScale(width, height).factor, sideMargin)

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
        for ((w, h) in listOf(874f to 393f, 1280f to 800f, 640f to 360f, 412f to 892f, 800f to 1280f, 300f to 600f)) {
            val g = geometry(w, h)
            assertEquals("$w x $h", g.installWidth, g.coverWidth, 0f)
        }
    }

    @Test fun theLeftColumnIsTheButtonAndTheTwoSquareButtonsWithTheirGaps() {
        for ((w, h) in listOf(874f to 393f, 1280f to 800f, 412f to 892f, 800f to 1280f)) {
            val g = geometry(w, h)
            assertEquals("$w x $h", g.installWidth + 2f * g.buttonSize + 2f * g.gap, g.leftWidth, 0.001f)
        }
    }

    @Test fun sideBySide_thePrimaryButtonTakesAShareOfTheWidth() {
        val g = geometry(874f, 393f)
        val scale = ScreenScale(874f, 393f).factor
        assertEquals(874f * StorePageGeometry.INSTALL_SHARE, g.installWidth, 0.001f)
        assertEquals(StorePageGeometry.BUTTON_SIZE * scale, g.buttonSize, 0.001f)
        assertEquals(StorePageGeometry.GAP * scale, g.gap, 0.001f)
        assertEquals(0f, g.artReveal, 0f)
    }

    @Test fun sideBySide_theShareIsHeldBetweenTheLimits() {
        val wide = geometry(2400f, 1080f)
        val scale = ScreenScale(2400f, 1080f).factor
        assertEquals(StorePageGeometry.INSTALL_MAX * scale, wide.installWidth, 0.001f)
        val narrow = geometry(400f, 300f)
        assertEquals(StorePageGeometry.INSTALL_MIN * ScreenScale(400f, 300f).factor, narrow.installWidth, 0.001f)
    }

    @Test fun sideBySide_theColumnAndTheCoverNeverMeet() {
        for ((w, h) in listOf(874f to 393f, 640f to 360f, 500f to 300f, 360f to 300f, 1280f to 800f, 2400f to 1080f)) {
            val g = geometry(w, h)
            val gapMin = StorePageGeometry.FREE_GAP_MIN * ScreenScale(w, h).factor
            assertTrue("$w x $h", g.leftWidth + g.coverWidth + gapMin <= w - 2f * margin + 0.001f)
        }
    }

    @Test fun sideBySide_aVerySmallWindowShrinksTheButtonInsteadOfOverlapping() {
        val g = geometry(360f, 300f)
        val scale = ScreenScale(360f, 300f).factor
        assertTrue(g.installWidth < StorePageGeometry.INSTALL_MIN * scale)
        assertEquals(360f - 2f * margin, g.leftWidth + g.coverWidth + StorePageGeometry.FREE_GAP_MIN * scale, 0.001f)
    }

    @Test fun stacked_theColumnFillsTheWindowBetweenTheMargins() {
        val g = geometry(412f, 892f, sideMargin = 16f)
        assertEquals(StorePageLayout.STACKED, g.layout)
        assertEquals(380f, g.leftWidth, 0.001f)
        assertEquals(380f - 2f * (StorePageGeometry.BUTTON_SIZE + StorePageGeometry.GAP), g.installWidth, 0.001f)
    }

    @Test fun stacked_aTabletDoesNotStretchTheColumn() {
        val g = geometry(1200f, 1900f)
        assertEquals(StorePageGeometry.STACKED_MAX * ScreenScale(1200f, 1900f).factor, g.leftWidth, 0.001f)
    }

    @Test fun stacked_withoutACoverTheTopOfThePageIsLeftToTheArt() {
        assertEquals(892f * StorePageGeometry.STACKED_ART_SHARE, geometry(412f, 892f).artReveal, 0.001f)
    }

    @Test fun theButtonNeverGetsNegativeWidth() {
        assertEquals(0f, geometry(100f, 400f).installWidth, 0f)
        assertEquals(0f, geometry(60f, 30f).installWidth, 0f)
    }
}
