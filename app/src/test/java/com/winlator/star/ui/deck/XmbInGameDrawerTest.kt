package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB in-game drawer's width (Lab's: 46% of the screen, 420..600 dp) and when it stacks its rows. No Android classes involved. */
class XmbInGameDrawerTest {

    @Test
    fun aCompactScreenGetsTheWholeWidth() {
        assertEquals(412f, xmbInGameDrawerWidth(412f, compact = true), 0.001f)
    }

    @Test
    fun aWideScreenGetsFortySixPercent() {
        assertEquals(460f, xmbInGameDrawerWidth(1000f, compact = false), 0.001f)
    }

    @Test
    fun theWidthIsHeldBetween420And600() {
        assertEquals(420f, xmbInGameDrawerWidth(700f, compact = false), 0.001f)
        assertEquals(600f, xmbInGameDrawerWidth(2000f, compact = false), 0.001f)
    }

    @Test
    fun aScreenNarrowerThanTheMinimumIsNeverExceeded() {
        // Not compact (e.g. a landscape window) but under 420 dp: the drawer fits the screen instead of overflowing it.
        assertEquals(300f, xmbInGameDrawerWidth(300f, compact = false), 0.001f)
    }

    @Test
    fun narrowDrawersStackTheirRows() {
        assertTrue(xmbInGameDrawerStacksRows(420f))
        assertTrue(xmbInGameDrawerStacksRows(XMB_DRAWER_STACK_BELOW_DP - 1f))
        assertFalse(xmbInGameDrawerStacksRows(XMB_DRAWER_STACK_BELOW_DP))
        assertFalse(xmbInGameDrawerStacksRows(600f))
    }

    @Test
    fun theChromeLeavesTheOptionsTheirRoomOnAShortLandscapePhone() {
        // 393 dp tall: the header, strip, title and footer total at most 125 dp, so 265 dp or more is left for the page.
        val m = xmbInGameDrawerMetrics(393f)
        assertTrue(m.chromeDp <= 125f)
        assertTrue(393f - m.chromeDp >= 265f)
    }

    @Test
    fun theChromeShrinksUnderTheShortHeight() {
        val roomy = xmbInGameDrawerMetrics(XMB_DRAWER_SHORT_BELOW_DP)
        val tight = xmbInGameDrawerMetrics(XMB_DRAWER_SHORT_BELOW_DP - 1f)
        assertTrue(tight.chromeDp < roomy.chromeDp)
        assertTrue(tight.chromeDp <= 100f)
        assertTrue(tight.fadeDp < roomy.fadeDp)
    }

    @Test
    fun theRoomyChromeSizesAreTheSlimOnes() {
        val m = xmbInGameDrawerMetrics(393f)
        assertEquals(28f, m.headerDp, 0.001f)
        assertEquals(30f, m.chipHeightDp, 0.001f)
        assertEquals(34f, m.stripDp, 0.001f)
        assertEquals(34f, m.buttonDp, 0.001f)
        assertEquals(122f, m.chromeDp, 0.001f)
    }

    @Test
    fun theShortChromeSizesAreSlimmerStill() {
        assertEquals(97f, xmbInGameDrawerMetrics(340f).chromeDp, 0.001f)
    }

    @Test
    fun thePageFadeIsShorterThanHalfTheRoomItGets() {
        // The fade doubles as the page's padding at both ends; it never eats the whole viewport of even a short page.
        assertTrue(xmbInGameDrawerMetrics(393f).fadeDp * 2f < 393f - xmbInGameDrawerMetrics(393f).chromeDp)
        assertTrue(xmbInGameDrawerMetrics(300f).fadeDp * 2f < 300f - xmbInGameDrawerMetrics(300f).chromeDp)
    }
}
