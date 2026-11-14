package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The XMB's fixed scale: the display's short side is always [XMB_REFERENCE_SHORT_SIDE_DP] dp at 100 % Interface scale, whatever the
 * phone's pixel count, density or Display size, and the window's dp follow it.
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
    fun aPhoneAtTheReferenceSizeKeepsItsDensity() {
        // 393 dp across the short side at density 2.0 is 786 px.
        assertEquals(2.0f, xmbBaseDensity(786), DELTA)
        // The phone the scale was set from: 393 dp at density 2.29 is 900 px, so the density (and, times the Deck's 0.8, what the
        // screens are drawn at) is the one the app had there.
        assertEquals(2.29f, xmbBaseDensity(900), 0.005f)
        assertEquals(2.29f * DECK_UI_SCALE, xmbAppDensity(900, 1f) * DECK_UI_SCALE, 0.005f)
    }

    @Test
    fun theInterfaceScaleMultipliesTheFixedDensity() {
        assertEquals(1.8f, xmbAppDensity(786, 0.9f), DELTA)
        assertEquals(3.0f, xmbAppDensity(786, 1.5f), DELTA)
        assertEquals(1.0f, xmbAppDensity(786, 0.5f), DELTA)
    }

    @Test
    fun everyResolutionDrawsTheSameShareOfTheShortSide() {
        // A 100 dp square is the same share of the short side on a 720 px, a 1080 px and a 1600 px wide screen.
        for (shortSidePx in listOf(720, 1080, 1600)) {
            assertEquals(100f / XMB_REFERENCE_SHORT_SIDE_DP, 100f * xmbBaseDensity(shortSidePx) / shortSidePx, DELTA)
        }
    }

    @Test
    fun theWindowsDpDoNotFollowTheDisplaySize() {
        // A 2400 x 1080 phone in landscape at three Display sizes (system density 2.2, 2.75, 3.3): the phone reports the window as
        // 1091 x 491, 873 x 393 and 727 x 327 dp, and it is 873 x 393 dp of the XMB's in every one of them.
        val base = xmbBaseDensity(1080)
        for ((density, widthDp, heightDp) in listOf(Triple(2.2f, 1091, 491), Triple(2.75f, 873, 393), Triple(3.3f, 727, 327))) {
            assertEquals(873f, xmbDp(widthDp, density, base).toFloat(), 1f)
            assertEquals(393f, xmbDp(heightDp, density, base).toFloat(), 1f)
        }
    }
}
