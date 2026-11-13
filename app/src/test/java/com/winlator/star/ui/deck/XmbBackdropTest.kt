package com.winlator.star.ui.deck

import androidx.compose.ui.graphics.Color
import com.winlator.star.ui.theme.ThemePreset
import com.winlator.star.ui.theme.themePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The XMB wash (Home) and glass (every other page) must never cost the text its contrast. These check the maths
 * behind [xmbWashFor] and [xmbGlassFor] against every theme preset and a spread of extreme colours, the same way the screen
 * composites them (accent over background, then the veil; the card over the base, the sheen over the card, a chip over that).
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
                assertTrue("${p.name}: $ratio on the wash", ratio >= XMB_GLASS_MIN_CONTRAST)
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
                    assertTrue("${p.name} + $accent: $ratio", ratio >= XMB_GLASS_MIN_CONTRAST)
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
                assertTrue(contrastOn(t, wash.flat) >= XMB_GLASS_MIN_CONTRAST)
            }
        }
    }

    @Test
    fun washBacksOffToNothingWhenTheTextCannotKeepUp() {
        // Mid-grey text on a mid-grey ground is below the minimum already, so no amount of wash can fix it.
        val alpha = xmbWashAlpha(Color(0xFF808080), Color(0xFFFFFFFF), listOf(Color(0xFF909090)))
        assertEquals(0f, alpha, 0f)
    }

    // ---- The glass behind every XMB page but Home (and its All Games page) ----

    private fun glassOf(p: ThemePreset): XmbGlass = xmbGlassFor(p.background, p.surface, p.onBackground, p.onSurfaceVariant)

    private fun textsOf(p: ThemePreset, glass: XmbGlass): List<Color> = listOf(p.onBackground, p.onSurface, glass.secondaryText)

    /** The lowest contrast any of [texts] gets on any ground of [glass] (see [xmbGlassGrounds]). */
    private fun worstContrast(glass: XmbGlass, texts: List<Color>): Double =
        xmbGlassGrounds(glass.base, glass.sweep, glass.frost).minOf { ground -> texts.minOf { contrastOn(it, ground) } }

    private fun lightness(c: Color): Float = toHsl(c)[2]

    private fun saturation(c: Color): Float = toHsl(c)[1]

    // A Color keeps 8 bits per channel, so these are the 0..255 values that are really drawn.
    private fun channels(c: Color): List<Int> = listOf((c.red * 255f).roundToInt(), (c.green * 255f).roundToInt(), (c.blue * 255f).roundToInt())

    private fun neutral(c: Color): Boolean = abs(c.red - c.green) < 0.002f && abs(c.green - c.blue) < 0.002f

    @Test
    fun hslRoundTripsAndNamesTheGreys() {
        for (c in listOf(Color(0xFF0D1B2A), Color(0xFFA855F7), Color(0xFF2BE05B), Color(0xFFE0A82E), Color(0xFF808080))) {
            val hsl = toHsl(c)
            val back = fromHsl(hsl[0], hsl[1], hsl[2])
            assertEquals(c.red, back.red, 0.004f)
            assertEquals(c.green, back.green, 0.004f)
            assertEquals(c.blue, back.blue, 0.004f)
        }
        assertEquals(0f, toHsl(Color(0xFF404040))[1], 1e-6f)
    }

    @Test
    fun charcoalIsNeutralNearBlackGlass() {
        val p = themePresets.first { it.name == "Charcoal" }
        val glass = glassOf(p)
        assertTrue("base ${glass.base}", neutral(glass.base))
        assertTrue("sweep ${glass.sweep}", neutral(glass.sweep))
        assertTrue("panel ${glass.panel}", neutral(glass.panel))
        // The base sits in #121212..#181818. The panel (a card over it) is lighter than the flat reference's #1f1f1f..#262626 now
        // that the cards pop: 8.5% white over #161616 is #2a2a2a, so #1f1f1f..#2c2c2c.
        assertTrue("base ${channels(glass.base)}", channels(glass.base)[0] in 0x12..0x18)
        assertTrue("panel ${channels(glass.panel)}", channels(glass.panel)[0] in 0x1F..0x2C)
        // The border is white at 10-14%, the sheen white at 4-6%, the card about 8.5% white (alphas are kept in 8 bits).
        assertTrue(glass.frost.line.alpha in 0.10f..0.14f)
        assertEquals(Color.White.copy(alpha = glass.frost.line.alpha), glass.frost.line)
        assertTrue(glass.frost.sheen.alpha in 0.04f..0.06f)
        assertTrue(glass.frost.card.alpha in 0.075f..0.095f)
        assertTrue(glass.frost.raised.alpha in 0.11f..0.13f)
        assertTrue(glass.frost.raised.alpha > glass.frost.card.alpha)
        // The lightest ground is a chip (12% white) on a lit card on the sweep, about #545454: #c0c0c0 on it is just under the
        // 4.6 target (4.2), so the secondary text moves a step towards white (about #c9c9c9) and no further.
        assertTrue("secondary ${glass.secondaryText}", glass.secondaryText.red > p.onSurfaceVariant.red)
        assertTrue("secondary ${glass.secondaryText}", glass.secondaryText.red <= p.onSurfaceVariant.red + 0.08f)
        assertTrue("secondary ${glass.secondaryText}", neutral(glass.secondaryText))
    }

    @Test
    fun panelsPopOffThePage() {
        // The cards are lighter than before (6% / 9%) so they stand off the page, without the sheen or the border growing.
        for (p in themePresets) {
            val frost = glassOf(p).frost
            assertTrue("${p.name}: card ${frost.card.alpha}", frost.card.alpha in 0.08f..0.09f)
            assertTrue("${p.name}: raised ${frost.raised.alpha}", frost.raised.alpha in 0.11f..0.13f)
            assertTrue("${p.name}: sheen ${frost.sheen.alpha}", frost.sheen.alpha <= 0.06f)
            assertTrue("${p.name}: line ${frost.line.alpha}", frost.line.alpha in 0.10f..0.14f)
        }
        // The drop shadow: stacked soft layers, widest first, none reaching further than the documented ~10 dp to the sides and
        // 14 dp below, and together darker than a hint but never a black slab (the card's own area is cut out).
        val layers = xmbShadowLayers()
        assertEquals(5, layers.size)
        for (i in 1 until layers.size) assertTrue(layers[i].spreadDp <= layers[i - 1].spreadDp)
        for (l in layers) {
            assertTrue("spread ${l.spreadDp}", l.spreadDp in 0f..10.5f)
            assertTrue("reach below ${l.spreadDp + l.dyDp}", l.spreadDp + l.dyDp <= 14.5f)
            assertTrue("alpha ${l.alpha}", l.alpha in 0.05f..0.4f)
        }
        var clear = 1f
        for (l in layers) clear *= 1f - l.alpha
        val edgeOpacity = 1f - clear
        assertTrue("edge opacity $edgeOpacity", edgeOpacity in 0.3f..0.6f)
        assertTrue(XMB_HAIRLINE_TOP_ALPHA > XMB_HAIRLINE_BOTTOM_ALPHA)
        assertTrue(XMB_HAIRLINE_TOP_ALPHA in 0.2f..0.3f)
        assertTrue(XMB_HAIRLINE_BOTTOM_ALPHA in 0.05f..0.12f)
    }

    @Test
    fun everyPresetGlassIsDarkAndMuted() {
        for (p in themePresets) {
            val glass = glassOf(p)
            val l = lightness(glass.base)
            assertTrue("${p.name}: base lightness $l", l in 0.05f..0.10f)
            val s = saturation(glass.base)
            // Muted: designed at 8-18% saturation, and at 8 bits per channel a base this dark lands within a couple of points of it.
            assertTrue("${p.name}: base saturation $s", s <= 0.20f)
            assertTrue("${p.name}: saturation $s is neither grey nor a tint", s < 1e-3f || s >= 0.06f)
            // The panel is a few percent lighter than the base, and the sweep a few percent lighter than it, never more.
            val lift = lightness(glass.panel) - l
            assertTrue("${p.name}: panel lift $lift", lift in 0.03f..0.09f)
            val sweep = lightness(glass.sweep) - l
            assertTrue("${p.name}: sweep lift $sweep", sweep in 0.02f..0.05f)
        }
    }

    @Test
    fun neutralPresetsGetGreyAndFamilyPresetsGetTheirOwnHue() {
        for (name in listOf("Charcoal", "Classic Dark", "AMOLED", "Monochrome")) {
            val p = themePresets.first { it.name == name }
            assertTrue("$name is grey", neutral(glassOf(p).base))
        }
        // Each coloured preset's base leans to its own family: its dominant channel is the surface's.
        val ocean = glassOf(themePresets.first { it.name == "Ocean" }).base
        assertTrue(ocean.blue > ocean.red)
        val forest = glassOf(themePresets.first { it.name == "Forest" }).base
        assertTrue(forest.green > forest.red && forest.green > forest.blue)
        val sunset = glassOf(themePresets.first { it.name == "Sunset" }).base
        assertTrue(sunset.red > sunset.blue && sunset.red > sunset.green)
    }

    @Test
    fun everyPresetKeepsItsTextReadableOnEveryGround() {
        for (p in themePresets) {
            val glass = glassOf(p)
            val worst = worstContrast(glass, textsOf(p, glass))
            assertTrue("${p.name}: worst contrast $worst", worst >= XMB_GLASS_MIN_CONTRAST)
            // The grounds really include the sheen: the lightest of them is a chip on a lit card on the sweep.
            val grounds = xmbGlassGrounds(glass.base, glass.sweep, glass.frost)
            assertEquals(10, grounds.size)
            val lightest = grounds.maxByOrNull { xmbLuminance(it.red, it.green, it.blue) }!!
            assertTrue(lightness(lightest) > lightness(glass.panel))
        }
    }

    @Test
    fun theSecondaryTextIsLiftedOnlyAsFarAsItNeeds() {
        val p = themePresets.first { it.name == "Classic Dark" }
        val glass = glassOf(p)
        // #AAAAAA on the lightest glass is just short, so it moves a little towards white (to about #cccccc, a step is 1/20 of the
        // way to white) and no further.
        assertTrue(xmbLuminance(glass.secondaryText.red, glass.secondaryText.green, glass.secondaryText.blue) >
            xmbLuminance(p.onSurfaceVariant.red, p.onSurfaceVariant.green, p.onSurfaceVariant.blue))
        assertTrue("secondary ${glass.secondaryText}", glass.secondaryText.red < 0.85f)
        val worst = worstContrast(glass, listOf(glass.secondaryText))
        assertTrue("worst $worst", worst >= XMB_GLASS_TARGET_CONTRAST - 0.05)
    }

    @Test
    fun extremeSurfacesStillMakeADarkMutedReadableGlass() {
        val extremes = listOf(
            Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFFFFFF00), Color(0xFF00FFFF),
            Color(0xFFFF00FF), Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF808080),
        )
        for (surface in extremes) {
            for (background in listOf(Color(0xFF000000), Color(0xFF151515), Color(0xFF2A2A2A), surface)) {
                val glass = xmbGlassFor(background, surface, Color.White, Color(0xFFAAAAAA))
                if (!(xmbLuminance(background.red, background.green, background.blue) < 0.18)) continue
                assertTrue("$surface/$background: ${lightness(glass.base)}", lightness(glass.base) <= 0.10f)
                assertTrue("$surface/$background: ${saturation(glass.base)}", saturation(glass.base) <= 0.22f)
                val worst = worstContrast(glass, listOf(Color.White, Color(0xFFE0E0E0), glass.secondaryText))
                assertTrue("$surface/$background: $worst", worst >= XMB_GLASS_MIN_CONTRAST)
            }
        }
    }

    @Test
    fun secondaryTextFallsBackToTheMainColourWhenNothingElseCanKeepUp() {
        val grounds = listOf(Color(0xFF808080))
        // Mid-grey on mid-grey and a mid-grey main colour: nothing to lift to, so the main colour is what is used.
        assertEquals(Color(0xFF909090), xmbGlassSecondaryText(grounds, Color(0xFF909090), Color(0xFF909090)))
        // A dark text on a dark ground is lifted towards the main colour until it is clear.
        val lifted = xmbGlassSecondaryText(listOf(Color(0xFF202020)), Color.White, Color(0xFF303030))
        assertTrue(lifted.red > Color(0xFF303030).red)
        assertTrue(contrastOn(lifted, Color(0xFF202020)) >= XMB_GLASS_TARGET_CONTRAST)
        // A text that is already clear is not touched.
        assertEquals(Color(0xFFDDDDDD), xmbGlassSecondaryText(listOf(Color(0xFF101010)), Color.White, Color(0xFFDDDDDD)))
    }

    @Test
    fun lightSchemeGlassKeepsItsTextReadable() {
        val background = Color(0xFFF5F5F5)
        val ink = Color(0xFF1A1A1A)
        val secondary = Color(0xFF555555)
        val glass = xmbGlassFor(background, Color.White, ink, secondary)
        // White glass on a light theme, not the dark theme's thin lift.
        assertTrue(glass.frost.card.alpha > 0.30f)
        assertEquals(background, glass.base)
        val worst = worstContrast(glass, listOf(ink, ink, glass.secondaryText))
        assertTrue("worst $worst", worst >= XMB_GLASS_MIN_CONTRAST)
    }

    @Test
    fun barsOnlyVeilThePageAndTheBordersAreLight() {
        for (p in themePresets) {
            val frost = glassOf(p).frost
            assertEquals(glassOf(p).base.copy(alpha = frost.bar.alpha), frost.bar)
            assertTrue(frost.bar.alpha in 0.5f..0.9f)
        }
    }
}
