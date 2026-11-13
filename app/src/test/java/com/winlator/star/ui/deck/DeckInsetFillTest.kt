package com.winlator.star.ui.deck

import androidx.compose.ui.graphics.Color
import com.winlator.star.ui.theme.themePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings controls' inset fill and soft line. On the XMB glass `surfaceVariant` is translucent white (alpha 0.12) and the line
 * is white at 13%; `copy(alpha = 0.55f)` / `copy(alpha = 0.5f)` on them gave the near-white tracks with a bright border. These check
 * the replacements: the same colour as before on an opaque scheme, a darker groove and a dim line on the glass, and the labels still
 * readable on it for every preset.
 */
class DeckInsetFillTest {

    // An sRGB Color keeps each channel in 8 bits, so an alpha reads back to within half a step (1/510) of what was asked.
    private val ALPHA_TOLERANCE = 0.005f

    private fun lum(c: Color): Double = xmbLuminance(c.red, c.green, c.blue)

    private fun contrastOn(text: Color, ground: Color): Double = xmbContrast(lum(text), lum(ground))

    /** [fill] (its alpha as the opacity) laid over the opaque [ground]. */
    private fun over(ground: Color, fill: Color): Color = Color(
        ground.red + (fill.red - ground.red) * fill.alpha,
        ground.green + (fill.green - ground.green) * fill.alpha,
        ground.blue + (fill.blue - ground.blue) * fill.alpha,
    )

    @Test
    fun anOpaqueSchemeKeepsTheOldFill() {
        val variant = Color(0xFF2A2A2A)
        val fill = deckInsetFill(variant, 0.55f)
        assertEquals(variant.red, fill.red, 1e-6f)
        assertEquals(variant.green, fill.green, 1e-6f)
        assertEquals(variant.blue, fill.blue, 1e-6f)
        assertEquals(0.55f, fill.alpha, ALPHA_TOLERANCE)
        assertEquals(0.45f, deckInsetFill(variant, 0.45f).alpha, ALPHA_TOLERANCE)
    }

    @Test
    fun theGlassVariantTurnsIntoABlackGroove() {
        val frost = xmbGlassFor(Color(0xFF151515), Color(0xFF1F1F1F), Color.White, Color(0xFFC0C0C0)).frost
        // The variant on the glass is the frost's `raised` (translucent), whatever alpha the caller asked for.
        val fill = deckInsetFill(frost.raised, 0.55f)
        assertEquals(0f, fill.red, 0f)
        assertEquals(0f, fill.green, 0f)
        assertEquals(0f, fill.blue, 0f)
        assertEquals(XMB_INSET_BLACK_ALPHA, fill.alpha, ALPHA_TOLERANCE)
        assertEquals(fill.alpha, deckInsetFill(frost.raised, 0.8f).alpha, 0f)
    }

    @Test
    fun theOldFillWashedOutAndTheNewOneIsDarkerThanTheCard() {
        for (p in themePresets) {
            val glass = xmbGlassFor(p.background, p.surface, p.onBackground, p.onSurfaceVariant)
            val card = over(glass.base, glass.frost.card)
            val washed = over(card, glass.frost.raised.copy(alpha = 0.55f))
            val inset = over(card, deckInsetFill(glass.frost.raised, 0.55f))
            assertTrue("${p.name}: the old track was lighter than its card", lum(washed) > lum(card) * 2)
            assertTrue("${p.name}: the inset is darker than its card", lum(inset) < lum(card))
        }
    }

    @Test
    fun unselectedLabelsStayReadableOnTheInsetForEveryPreset() {
        for (p in themePresets) {
            val glass = xmbGlassFor(p.background, p.surface, p.onBackground, p.onSurfaceVariant)
            val fill = deckInsetFill(glass.frost.raised, 0.55f)
            for (ground in xmbGlassGrounds(glass.base, glass.sweep, glass.frost)) {
                val track = over(ground, fill)
                val worst = contrastOn(glass.secondaryText, track)
                assertTrue("${p.name}: secondary text on the track is $worst", worst >= 4.5)
                assertTrue("${p.name}: main text on the track", contrastOn(p.onSurface, track) >= 4.5)
            }
        }
    }

    @Test
    fun aSoftGlassLineIsDimmerThanTheLineAndNeverBrighter() {
        val line = Color.White.copy(alpha = 0.13f)
        val soft = deckSoftLine(line, 0.5f)
        assertTrue(soft.alpha < line.alpha)
        assertEquals(line.alpha * (0.5f / DECK_LINE_BASE_ALPHA), soft.alpha, ALPHA_TOLERANCE)
        // A request at or above the base alpha is the line itself, not brighter.
        assertEquals(line.alpha, deckSoftLine(line, 0.8f).alpha, ALPHA_TOLERANCE)
        assertEquals(line.alpha, deckSoftLine(line, 1f).alpha, ALPHA_TOLERANCE)
        // The old expression replaced the alpha, which made the glass line far brighter than it is.
        assertTrue(line.copy(alpha = 0.5f).alpha > line.alpha * 3)
    }
}
