package com.winlator.star.ui.deck

import androidx.compose.ui.graphics.Color
import com.winlator.star.ui.theme.themePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raised elements of the launch popup sit on the popup's own opaque panel: their fill ([xmbPopFillOf]) has to be an
 * opaque, slightly lighter tone of it that never costs the text its contrast.
 */
class XmbPopCardTest {

    private fun contrastOn(text: Color, ground: Color): Double =
        xmbContrast(xmbLuminance(text.red, text.green, text.blue), xmbLuminance(ground.red, ground.green, ground.blue))

    private fun lum(c: Color): Double = xmbLuminance(c.red, c.green, c.blue)

    @Test
    fun noLiftIsTheSurfaceItself() {
        val surface = Color(0xFF1E1E1E)
        val fill = xmbPopFillOf(surface, 0f, null, 0f, emptyList())
        assertEquals(surface.red, fill.red, 1e-6f)
        assertEquals(surface.green, fill.green, 1e-6f)
        assertEquals(surface.blue, fill.blue, 1e-6f)
    }

    @Test
    fun theFillIsOpaqueAndLighterThanThePanel() {
        val surface = Color(0xFF1E1E1E)
        for (lift in listOf(XMB_POP_LIFT, XMB_POP_LIFT_SMALL)) {
            val fill = xmbPopFillOf(surface, lift, null, 0f, emptyList())
            assertEquals(1f, fill.alpha, 0f)
            assertTrue(lum(fill) > lum(surface))
        }
        // The stronger lift of a chip is the lighter of the two.
        val card = xmbPopFillOf(surface, XMB_POP_LIFT, null, 0f, emptyList())
        val chip = xmbPopFillOf(surface, XMB_POP_LIFT_SMALL, null, 0f, emptyList())
        assertTrue(lum(chip) > lum(card))
    }

    @Test
    fun aTintedFillIsOpaqueToo() {
        val surface = Color(0xFF1E1E1E)
        val fill = xmbPopFillOf(surface, XMB_POP_LIFT, Color(0xFF82AAFF), 0.16f, emptyList())
        assertEquals(1f, fill.alpha, 0f)
        // The accent leans the fill towards blue.
        assertTrue(fill.blue > fill.red)
    }

    @Test
    fun theLiftBacksOffUntilTheTextIsReadable() {
        // A grey that is just readable on the plain panel but not on the lifted one.
        val surface = Color(0xFF202020)
        val ink = Color(0xFF9A9A9A)
        val plain = contrastOn(ink, surface)
        assertTrue("setup: $plain", plain >= XMB_GLASS_MIN_CONTRAST)
        val lifted = xmbPopFillOf(surface, 0.5f, null, 0f, emptyList())
        assertTrue(contrastOn(ink, lifted) < XMB_GLASS_MIN_CONTRAST)
        val fill = xmbPopFillOf(surface, 0.5f, null, 0f, listOf(ink))
        assertTrue("${contrastOn(ink, fill)} after the back-off", contrastOn(ink, fill) >= XMB_GLASS_MIN_CONTRAST)
        assertTrue(lum(fill) >= lum(surface))
    }

    @Test
    fun anUnreadableSurfaceGetsNoLiftAtAll() {
        val surface = Color(0xFF202020)
        val ink = Color(0xFF303030)
        val fill = xmbPopFillOf(surface, XMB_POP_LIFT, null, 0f, listOf(ink))
        assertEquals(surface.red, fill.red, 1e-6f)
        assertEquals(surface.green, fill.green, 1e-6f)
        assertEquals(surface.blue, fill.blue, 1e-6f)
    }

    @Test
    fun everyPresetKeepsItsTextReadableOnTheRaisedFills() {
        for (p in themePresets) {
            val inks = listOf(p.onSurface, p.onSurfaceVariant)
            for (small in listOf(false, true)) {
                val fill = xmbPopFillOf(p.surface, if (small) XMB_POP_LIFT_SMALL else XMB_POP_LIFT, null, 0f, inks)
                assertEquals(1f, fill.alpha, 0f)
                for (t in inks) {
                    // Only a preset whose own panel already reads is held to it: the lift never makes it worse than that.
                    if (contrastOn(t, p.surface) >= XMB_GLASS_MIN_CONTRAST) {
                        val ratio = contrastOn(t, fill)
                        assertTrue("${p.name}: $ratio on the raised fill", ratio >= XMB_GLASS_MIN_CONTRAST)
                    }
                }
            }
        }
    }
}
