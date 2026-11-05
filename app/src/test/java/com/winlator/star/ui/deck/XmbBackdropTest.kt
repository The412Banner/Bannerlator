package com.winlator.star.ui.deck

import androidx.compose.ui.graphics.Color
import com.winlator.star.ui.theme.ThemePreset
import com.winlator.star.ui.theme.themePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The XMB wash and glow must never cost the text its contrast. These check the maths behind [xmbWashFor] (Home, All Games)
 * and [xmbGlowFor] (every other page) against every theme preset and a spread of custom accents, the same way the screen
 * composites them (accent over background, then the veil; blobs over the base, then the glass).
 */
class XmbBackdropTest {

    private fun contrastOn(text: Color, ground: Color): Double =
        xmbContrast(xmbLuminance(text.red, text.green, text.blue), xmbLuminance(ground.red, ground.green, ground.blue))

    @Test
    fun luminanceAndContrastMatchWcag() {
        assertEquals(1.0, xmbLuminance(1f, 1f, 1f), 1e-9)
        assertEquals(0.0, xmbLuminance(0f, 0f, 0f), 1e-9)
        assertEquals(21.0, xmbContrast(1.0, 0.0), 1e-9)
        assertEquals(21.0, xmbContrast(0.0, 1.0), 1e-9)
        assertEquals(1.0, xmbContrast(0.5, 0.5), 1e-9)
    }

    @Test
    fun washedRgbEndsAreTheBaseAndTheAccent() {
        val base = Color(0xFF204060)
        val accent = Color(0xFFE0A82E)
        val none = xmbWashedRgb(base, accent, 0f, 0f)
        assertEquals(base.red, none[0], 1e-6f)
        assertEquals(base.green, none[1], 1e-6f)
        assertEquals(base.blue, none[2], 1e-6f)
        val full = xmbWashedRgb(base, accent, 1f, 0f)
        assertEquals(accent.red, full[0], 1e-6f)
        assertEquals(accent.green, full[1], 1e-6f)
        assertEquals(accent.blue, full[2], 1e-6f)
    }

    @Test
    fun everyPresetKeepsItsTextReadableAtFullStrength() {
        for (p in themePresets) {
            val texts = listOf(p.onBackground, p.onSurface, p.onSurfaceVariant)
            val wash = xmbWashFor(p.background, p.primary, texts)
            // All presets get the same strength: none is held back, so the look is the same in every theme.
            assertEquals("${p.name} is held back", XMB_WASH_MAX_ALPHA, wash.accentAlpha, 1e-6f)
            for (t in texts) {
                val ratio = contrastOn(t, wash.flat)
                assertTrue("${p.name}: $ratio on the wash", ratio >= XMB_WASH_MIN_CONTRAST)
            }
        }
    }

    @Test
    fun anyCustomAccentOnAnyPresetKeepsTheTextReadable() {
        val accents = listOf(
            Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFFFFFF00), Color(0xFF00FFFF),
            Color(0xFFFF00FF), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFF88C0D0),
        )
        for (p in themePresets) {
            val texts = listOf(p.onBackground, p.onSurface, p.onSurfaceVariant)
            for (accent in accents) {
                val wash = xmbWashFor(p.background, accent, texts)
                assertTrue(wash.accentAlpha in 0f..XMB_WASH_MAX_ALPHA)
                for (t in texts) {
                    val ratio = contrastOn(t, wash.flat)
                    assertTrue("${p.name} + $accent: $ratio", ratio >= XMB_WASH_MIN_CONTRAST)
                }
            }
        }
    }

    @Test
    fun lightSchemeIsHeldBackInsteadOfLosingContrast() {
        val background = Color(0xFFF5F5F5)
        val texts = listOf(Color(0xFF1A1A1A), Color(0xFF1A1A1A), Color(0xFF555555))
        for (accent in listOf(Color(0xFF0055FF), Color(0xFF000000), Color(0xFF800000), Color(0xFFFFFFFF))) {
            val wash = xmbWashFor(background, accent, texts)
            // No white lift on a light background; it would only turn it grey.
            assertEquals(0f, wash.veilAlpha, 0f)
            assertTrue(wash.accentAlpha in 0f..XMB_WASH_MAX_ALPHA)
            for (t in texts) {
                assertTrue(contrastOn(t, wash.flat) >= XMB_WASH_MIN_CONTRAST)
            }
        }
    }

    @Test
    fun washBacksOffToNothingWhenTheTextCannotKeepUp() {
        // Mid-grey text on a mid-grey ground is below the minimum already, so no amount of wash can fix it.
        val alpha = xmbWashAlpha(Color(0xFF808080), Color(0xFFFFFFFF), listOf(Color(0xFF909090)))
        assertEquals(0f, alpha, 0f)
    }

    // ---- The glow behind every XMB page but Home and All Games ----

    private fun glowOf(p: ThemePreset, accent: Color = p.primary): XmbGlow =
        xmbGlowFor(p.background, accent, p.onBackground, p.onSurface, p.onSurfaceVariant)

    private fun textsOf(p: ThemePreset, glow: XmbGlow): List<Color> = listOf(p.onBackground, p.onSurface, glow.secondaryText)

    // A finer grid than the one the glow is tuned on, so a dip between its sample points would show up here.
    private fun worstOf(glow: XmbGlow, texts: List<Color>): Double = xmbGlowWorstContrast(glow, texts, gridX = 120, gridY = 70)

    @Test
    fun oneBlobFallsOffSmoothlyAndEndsAtTheBase() {
        val frost = XmbFrost(Color.White.copy(alpha = 0.1f), Color.White.copy(alpha = 0.1f), Color.Black, Color.White, Color.White)
        val glow = XmbGlow(Color.Black, listOf(XmbGlowBlob(0.5f, 0.5f, 0.4f, Color.White, 1f)), frost, 1f, 1f, Color.White)
        val centre = xmbGlowColorAt(glow, 0.5f, 0.5f, 1f)
        assertEquals(1f, centre.red, 1e-3f)
        var last = centre.red
        for (x in listOf(0.55f, 0.6f, 0.65f, 0.7f, 0.8f)) {
            val r = xmbGlowColorAt(glow, x, 0.5f, 1f).red
            assertTrue("not fading at $x", r <= last)
            last = r
        }
        // Past the reach (0.4 of the longer side) nothing is left of it.
        assertEquals(0f, xmbGlowColorAt(glow, 0f, 0f, 1f).red, 1e-6f)
        assertEquals(0f, xmbGlowColorAt(glow, 0.95f, 0.5f, 1f).red, 1e-6f)
    }

    @Test
    fun blobsFollowTheAccentAndAreNotAStraightRamp() {
        val blobs = xmbGlowBlobs(Color(0xFF0055FF), 1f)
        assertEquals(3, blobs.size)
        // The big light is up and left of centre, the secondary one low on the right: uneven, not left-to-right.
        assertTrue(blobs[0].cx < 0.5f && blobs[0].cy < 0.5f)
        assertTrue(blobs[1].cx > 0.5f && blobs[1].cy > 0.5f)
        assertEquals(3, blobs.map { it.cx }.toSet().size)
        assertEquals(3, blobs.map { it.cy }.toSet().size)
        // A blue accent makes blue lights; the third one is only shifted a little round the colour wheel.
        for (b in blobs.take(2)) assertTrue("blue first: ${b.color}", b.color.blue > b.color.red && b.color.blue > b.color.green)
        // The main light is the lighter shade, the secondary the darker one.
        assertTrue(xmbLuminance(blobs[0].color.red, blobs[0].color.green, blobs[0].color.blue) >
            xmbLuminance(blobs[1].color.red, blobs[1].color.green, blobs[1].color.blue))
        for (b in xmbGlowBlobs(Color(0xFF0055FF), 0f)) assertEquals(0f, b.alpha, 0f)
    }

    @Test
    fun aGreyAccentMakesAGreyGlow() {
        for (b in xmbGlowBlobs(Color(0xFF808080), 1f)) {
            assertEquals(b.color.red, b.color.green, 0.01f)
            assertEquals(b.color.green, b.color.blue, 0.01f)
        }
    }

    @Test
    fun glassIsThinAndLightOnADarkTheme() {
        val p = themePresets.first { it.name == "Ocean" }
        val glow = glowOf(p)
        val frost = glow.frost
        assertTrue(frost.card.alpha in 0.05f..0.22f)
        assertTrue(frost.raised.alpha in 0.05f..0.22f)
        assertTrue(frost.raised.alpha > frost.card.alpha)
        assertTrue(frost.line.alpha in 0.12f..0.18f)
        // The bar only darkens the glow it sits on.
        assertTrue(xmbLuminance(frost.bar.red, frost.bar.green, frost.bar.blue) <= xmbLuminance(p.primary.red, p.primary.green, p.primary.blue))
    }

    @Test
    fun everyPresetGlowKeepsItsTextReadableAndStaysVisible() {
        for (p in themePresets) {
            val glow = glowOf(p)
            val worst = worstOf(glow, textsOf(p, glow))
            assertTrue("${p.name}: worst contrast $worst", worst >= XMB_WASH_MIN_CONTRAST)
            // Held back for contrast, never down to nothing: every built-in preset keeps most of its glow and its glass.
            assertTrue("${p.name}: strength ${glow.strength}", glow.strength >= 0.25f)
            assertTrue("${p.name}: glass ${glow.frostScale}", glow.frostScale >= 0.55f)
        }
    }

    @Test
    fun theSecondaryTextIsLiftedOnADarkThemeAndLeftAloneOnALightOne() {
        val dark = Color(0xFF101010)
        val lifted = xmbGlowSecondaryText(dark, Color.White, Color(0xFFAAAAAA))
        assertTrue(xmbLuminance(lifted.red, lifted.green, lifted.blue) > xmbLuminance(0.667f, 0.667f, 0.667f))
        val light = Color(0xFFF5F5F5)
        assertEquals(Color(0xFF555555), xmbGlowSecondaryText(light, Color(0xFF1A1A1A), Color(0xFF555555)))
    }

    @Test
    fun anyCustomAccentOnAnyPresetKeepsTheGlowTextReadable() {
        val accents = listOf(
            Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFFFFFF00), Color(0xFF00FFFF),
            Color(0xFFFF00FF), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFF88C0D0),
        )
        for (p in themePresets) {
            for (accent in accents) {
                val glow = glowOf(p, accent)
                val worst = worstOf(glow, textsOf(p, glow))
                assertTrue("${p.name} + $accent: $worst", worst >= XMB_WASH_MIN_CONTRAST)
            }
        }
    }

    @Test
    fun lightSchemeGlowKeepsItsTextReadable() {
        val background = Color(0xFFF5F5F5)
        val ink = Color(0xFF1A1A1A)
        val secondary = Color(0xFF555555)
        for (accent in listOf(Color(0xFF0055FF), Color(0xFF000000), Color(0xFF800000), Color(0xFFFFFFFF), Color(0xFFFFFF00))) {
            val glow = xmbGlowFor(background, accent, ink, ink, secondary)
            // The glass is white on a light theme, not the dark theme's thin lift.
            assertTrue(glow.frost.card.alpha > 0.15f)
            val worst = worstOf(glow, listOf(ink, ink, glow.secondaryText))
            assertTrue("$accent: $worst", worst >= XMB_WASH_MIN_CONTRAST)
        }
    }

    @Test
    fun glowBacksOffToNoneWhenTheTextCannotKeepUp() {
        // Mid-grey text on a mid-grey scheme is below the minimum already, so no glow can fix it: none is drawn.
        val grey = Color(0xFF909090)
        val glow = xmbGlowFor(Color(0xFF808080), Color(0xFFFFFFFF), grey, grey, grey)
        assertEquals(0f, glow.strength, 0f)
    }
}
