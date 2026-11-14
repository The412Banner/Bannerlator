package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The XMB's fixed scale: the display's short side is always [XMB_REFERENCE_SHORT_SIDE_DP] dp on the reference scale, whatever the
 * phone's pixel count, density or Display size, and the XMB's 100 % is the look it was tuned at ([XMB_BASE_UI_BOOST],
 * [XMB_BASE_FONT_BOOST] on top of it). The window's dp follow the reference scale, without the boost.
 */
class XmbScaleTest {

    private val DELTA = 0.001f

    @Test
    fun theShortSideIsTheReferenceInDp() {
        for (shortSidePx in listOf(720, 918, 1080, 1440, 1600, 2560)) {
            assertEquals(XMB_REFERENCE_SHORT_SIDE_DP, shortSidePx / xmbBaseDensity(shortSidePx), DELTA)
        }
    }

    @Test
    fun theBaselineIsTheLookItWasTunedAt() {
        assertEquals(1.10f, XMB_BASE_UI_BOOST, DELTA)
        assertEquals(1.05f, XMB_BASE_FONT_BOOST, DELTA)
        // 100 % / 100 % is the old 110 % Interface scale and 105 % Font size on the reference scale.
        assertEquals(xmbBaseDensity(900) * 1.10f, xmbAppDensity(900, 1f), DELTA)
        assertEquals(1.05f, xmbFontScale(1f), DELTA)
    }

    @Test
    fun aPhoneAtTheReferenceSizeKeepsItsDensityTimesTheBoost() {
        // 393 dp across the short side at density 2.0 is 786 px.
        assertEquals(2.0f, xmbBaseDensity(786), DELTA)
        assertEquals(2.2f, xmbAppDensity(786, 1f), DELTA)
        // 393 dp at density 2.29 is 900 px: 2.29 * 1.10 = 2.519, and the Deck then draws 0.8 of it.
        assertEquals(2.29f * 1.10f, xmbAppDensity(900, 1f), 0.005f)
        assertEquals(2.29f * 1.10f * DECK_UI_SCALE, xmbAppDensity(900, 1f) * DECK_UI_SCALE, 0.005f)
    }

    @Test
    fun theSlidersMultiplyTheBaseline() {
        assertEquals(1.98f, xmbAppDensity(786, 0.9f), DELTA)
        assertEquals(3.3f, xmbAppDensity(786, 1.5f), DELTA)
        assertEquals(1.1f, xmbAppDensity(786, 0.5f), DELTA)
        assertEquals(1.05f * 0.5f, xmbFontScale(0.5f), DELTA)
        assertEquals(1.05f * 1.5f, xmbFontScale(1.5f), DELTA)
    }

    @Test
    fun everyResolutionDrawsTheSameShareOfTheShortSide() {
        // A 100 dp square is the same share of the short side on a 720 px phone, a 1080 px phone and a 1600 px tablet.
        for (shortSidePx in listOf(720, 1080, 1600)) {
            assertEquals(100f * XMB_BASE_UI_BOOST / XMB_REFERENCE_SHORT_SIDE_DP, 100f * xmbAppDensity(shortSidePx, 1f) / shortSidePx, DELTA)
        }
        assertEquals(3.0229f, xmbAppDensity(1080, 1f), DELTA)
        assertEquals(4.4784f, xmbAppDensity(1600, 1f), DELTA)
    }

    @Test
    fun theWindowsDpDoNotFollowTheDisplaySize() {
        // A 2400 x 1080 phone in landscape at three Display sizes (system density 2.2, 2.75, 3.3): the phone reports the window as
        // 1091 x 491, 873 x 393 and 727 x 327 dp, and it is 873 x 393 dp of the reference scale in every one of them.
        val base = xmbBaseDensity(1080)
        for ((density, widthDp, heightDp) in listOf(Triple(2.2f, 1091, 491), Triple(2.75f, 873, 393), Triple(3.3f, 727, 327))) {
            assertEquals(873f, xmbDp(widthDp, density, base).toFloat(), 1f)
            assertEquals(393f, xmbDp(heightDp, density, base).toFloat(), 1f)
        }
    }
}
