package com.winlator.star.ui.deck

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The XMB interface's backgrounds.
//
// Home and the All Games page keep the flat wash: the theme colour as an even, soft tint over the preset's own background,
// with no gradient and nothing depending on the position (the old glows and the black scrim over the Home art read as the
// colour "dimming" towards the left). It is composited ahead of time into one opaque colour ([XmbWash.flat]), so it looks
// the same on every Android version and a page painting it twice doesn't get a second layer of tint.
//
// Every other XMB page (the Deck tabs and their sub-pages, the store screens, the downloads, the search and the dialogs) sits
// on the glow below: a few large, soft patches of light in shades of the theme colour over the preset's dark base, with the
// cards on top as frosted glass (a thin light fill and hairline that let the glow show through).

/**
 * Most of the accent that is mixed into the background; the wash backs off from this when text would lose contrast. At 0.20
 * every built-in preset keeps its secondary text above 4.5:1 with some room (0.22 is the first to tip Phosphor and Frost), so
 * they all get the same strength and only an unusual custom accent is held back.
 */
internal const val XMB_WASH_MAX_ALPHA = 0.20f

/** The faint white lift that makes the tint read as frosted glass rather than a coloured fill (dark themes only). */
internal const val XMB_WASH_VEIL_ALPHA = 0.04f

/** WCAG AA for body text; the secondary text colour is held to it too. */
internal const val XMB_WASH_MIN_CONTRAST = 4.5

private fun channelToLinear(c: Float): Double {
    val v = c.toDouble()
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/** WCAG relative luminance of an sRGB colour given as 0..1 channels. */
internal fun xmbLuminance(r: Float, g: Float, b: Float): Double =
    0.2126 * channelToLinear(r) + 0.7152 * channelToLinear(g) + 0.0722 * channelToLinear(b)

/** WCAG contrast ratio (1..21) between two relative luminances, in either order. */
internal fun xmbContrast(l1: Double, l2: Double): Double {
    val hi = maxOf(l1, l2)
    val lo = minOf(l1, l2)
    return (hi + 0.05) / (lo + 0.05)
}

// A Color keeps 8 bits per channel, so the contrast is judged on the colour that is really drawn: a value that passes by a
// hair before the rounding could land just under the minimum after it.
private fun to8Bit(c: Float): Float = (c.coerceIn(0f, 1f) * 255f).roundToInt() / 255f

/**
 * The opaque colour (r, g, b as 0..1, rounded to the 8 bits a Color keeps) that [accent] at [alpha] makes over [base], then
 * the white veil of [veil] over that.
 */
internal fun xmbWashedRgb(base: Color, accent: Color, alpha: Float, veil: Float): FloatArray {
    val r = base.red * (1f - alpha) + accent.red * alpha
    val g = base.green * (1f - alpha) + accent.green * alpha
    val b = base.blue * (1f - alpha) + accent.blue * alpha
    return floatArrayOf(to8Bit(r * (1f - veil) + veil), to8Bit(g * (1f - veil) + veil), to8Bit(b * (1f - veil) + veil))
}

private fun isDarkBase(background: Color): Boolean = xmbLuminance(background.red, background.green, background.blue) < 0.18

/** The veil strength for a background: only dark themes get the white lift, a light one would just go grey. */
internal fun xmbWashVeil(background: Color): Float = if (isDarkBase(background)) XMB_WASH_VEIL_ALPHA else 0f

/**
 * How much of [accent] to mix into [background]: [XMB_WASH_MAX_ALPHA] unless that would drop the contrast of any of [texts]
 * on the washed colour below [XMB_WASH_MIN_CONTRAST], then the largest value that keeps it (down to no tint at all). The
 * presets all pass at the maximum; this keeps a very light custom accent, or a light scheme, readable too.
 */
internal fun xmbWashAlpha(background: Color, accent: Color, texts: List<Color>): Float {
    val veil = xmbWashVeil(background)
    var steps = (XMB_WASH_MAX_ALPHA * 100f).roundToInt()
    while (steps > 0) {
        val a = steps / 100f
        val rgb = xmbWashedRgb(background, accent, a, veil)
        val lum = xmbLuminance(rgb[0], rgb[1], rgb[2])
        if (texts.all { xmbContrast(xmbLuminance(it.red, it.green, it.blue), lum) >= XMB_WASH_MIN_CONTRAST }) return a
        steps--
    }
    return 0f
}

/** The wash for one colour scheme: how much accent and white veil went in, and the opaque colour they make over the plain background. */
internal class XmbWash(val accentAlpha: Float, val veilAlpha: Float, val flat: Color)

internal fun xmbWashFor(background: Color, accent: Color, texts: List<Color>): XmbWash {
    val alpha = xmbWashAlpha(background, accent, texts)
    val veilAlpha = xmbWashVeil(background)
    val rgb = xmbWashedRgb(background, accent, alpha, veilAlpha)
    return XmbWash(alpha, veilAlpha, Color(rgb[0].coerceIn(0f, 1f), rgb[1].coerceIn(0f, 1f), rgb[2].coerceIn(0f, 1f)))
}

// ---------------------------------------------------------------------------------------------------------------------
// The glow: the maths. Everything here is pure (no Compose runtime), so the contrast of the text over it is unit-tested.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * One soft patch of light. [cx] / [cy] are its centre as fractions of the box, [radius] its reach as a fraction of the box's
 * longer side, [alpha] the peak opacity of [color] at the centre (it fades out smoothly to nothing at the reach).
 */
internal class XmbGlowBlob(val cx: Float, val cy: Float, val radius: Float, val color: Color, val alpha: Float)

/**
 * The frosted-glass fills for the cards on the glow. [card] is a card or panel, [raised] something on a card (a chip, a
 * button, a field), [bar] a top strip / rail / bottom bar (a veil of the base, so its text stays clear of the brightest part
 * of the glow), [line] the hairline round all of them.
 */
internal class XmbFrost(val card: Color, val raised: Color, val bar: Color, val line: Color, val tint: Color)

/**
 * One glow: the opaque [base] under the [blobs], the [frost] that sits on top of it, how strong it ended up
 * ([strength] of the blobs, [frostScale] of the glass; both 1 when nothing had to be held back) and the secondary text
 * colour the pages use over it ([secondaryText], the theme's own lifted a little so it keeps its contrast on the lit glass).
 */
internal class XmbGlow(
    val base: Color,
    val blobs: List<XmbGlowBlob>,
    val frost: XmbFrost,
    val strength: Float,
    val frostScale: Float,
    val secondaryText: Color,
)

/** Peak opacities of the three blobs at full strength: the big main light, the darker secondary one, the faint third. */
private const val GLOW_MAIN_ALPHA = 0.60f
private const val GLOW_SECOND_ALPHA = 0.50f
private const val GLOW_THIRD_ALPHA = 0.34f

/** How much of the accent the plain base picks up at full strength, so even the dark parts lean to the theme colour. */
private const val GLOW_BASE_TINT = 0.06f

/** Opacity of a card / of something on a card over the glow, on a dark theme (a light one needs more white to read as glass). */
private const val FROST_CARD_ALPHA = 0.08f
private const val FROST_RAISED_ALPHA = 0.12f
private const val FROST_CARD_ALPHA_LIGHT = 0.45f
private const val FROST_RAISED_ALPHA_LIGHT = 0.60f

/** The hairline round glass: ~1 dp of white at this opacity on a dark theme. */
private const val FROST_LINE_ALPHA = 0.16f

/** How much of the base a bar keeps over the glow (it only darkens it). */
private const val FROST_BAR_ALPHA = 0.42f

/** The contrast the glow is tuned to; the extra 0.2 over [XMB_WASH_MIN_CONTRAST] covers the samples between grid points. */
internal const val XMB_GLOW_TARGET_CONTRAST = 4.7

/** One blob's fall-off: its opacity at the given fraction of its reach, in the shape of a soft bell so it has no visible edge. */
private val GLOW_STOPS = floatArrayOf(0f, 0.2f, 0.45f, 0.7f, 0.9f, 1f)
private val GLOW_LEVELS = floatArrayOf(1f, 0.82f, 0.37f, 0.09f, 0.02f, 0f)

/** The shapes of the boxes the glow is judged on: a wide landscape screen, a square panel, a tall portrait phone (width / height). */
private val GLOW_ASPECTS = floatArrayOf(2.2f, 1f, 0.45f)

private fun mixColor(a: Color, b: Color, t: Float): Color =
    Color(
        (a.red + (b.red - a.red) * t).coerceIn(0f, 1f),
        (a.green + (b.green - a.green) * t).coerceIn(0f, 1f),
        (a.blue + (b.blue - a.blue) * t).coerceIn(0f, 1f),
    )

/** [fill] (its alpha as the opacity) laid over the opaque [ground]. */
private fun overColor(ground: Color, fill: Color): Color = mixColor(ground, fill, fill.alpha)

/** An sRGB colour as hue (0..360), saturation and lightness (0..1). */
private fun toHsl(c: Color): FloatArray {
    val mx = max(c.red, max(c.green, c.blue))
    val mn = min(c.red, min(c.green, c.blue))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d < 1e-6f) return floatArrayOf(0f, 0f, l)
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        c.red -> (c.green - c.blue) / d + (if (c.green < c.blue) 6f else 0f)
        c.green -> (c.blue - c.red) / d + 2f
        else -> (c.red - c.green) / d + 4f
    }
    return floatArrayOf(h * 60f, s, l)
}

private fun hueChannel(p: Float, q: Float, tIn: Float): Float {
    var t = tIn
    if (t < 0f) t += 1f
    if (t > 1f) t -= 1f
    return when {
        t < 1f / 6f -> p + (q - p) * 6f * t
        t < 0.5f -> q
        t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
        else -> p
    }
}

private fun fromHsl(hue: Float, s: Float, l: Float): Color {
    val h = (((hue % 360f) + 360f) % 360f) / 360f
    if (s < 1e-6f) return Color(l.coerceIn(0f, 1f), l.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    return Color(
        hueChannel(p, q, h + 1f / 3f).coerceIn(0f, 1f),
        hueChannel(p, q, h).coerceIn(0f, 1f),
        hueChannel(p, q, h - 1f / 3f).coerceIn(0f, 1f),
    )
}

/**
 * The three blobs, in shades of [accent] at [strength] (0..1). A big lighter patch up and left of centre, a darker one low
 * on the right and a faint one low on the left, so the light falls unevenly the way it does in the reference and nothing runs
 * as a straight left-to-right ramp. Saturation is capped and the lightness is kept in a band, so a loud accent still makes a
 * dark, readable glow; a grey accent (Steel, Monochrome) makes a grey one.
 */
internal fun xmbGlowBlobs(accent: Color, strength: Float): List<XmbGlowBlob> {
    val hsl = toHsl(accent)
    val h = hsl[0]
    val s = hsl[1]
    val l = hsl[2]
    return listOf(
        XmbGlowBlob(0.36f, 0.20f, 0.80f, fromHsl(h, min(s, 0.80f), (l + 0.02f).coerceIn(0.36f, 0.54f)), GLOW_MAIN_ALPHA * strength),
        XmbGlowBlob(0.90f, 0.88f, 0.70f, fromHsl(h, min(s, 0.70f), (l - 0.14f).coerceIn(0.16f, 0.36f)), GLOW_SECOND_ALPHA * strength),
        XmbGlowBlob(0.10f, 0.78f, 0.55f, fromHsl(h + 28f, min(s, 0.60f), (l - 0.04f).coerceIn(0.30f, 0.50f)), GLOW_THIRD_ALPHA * strength),
    )
}

/** The opaque colour under the blobs: the preset's background, leaning a little to the accent. */
internal fun xmbGlowBase(background: Color, accent: Color, strength: Float): Color =
    mixColor(background, accent, GLOW_BASE_TINT * strength)

/** The glass fills for [background] / [accent]: [scale] 1 is full strength, lower makes the glass thinner. */
internal fun xmbFrostFor(background: Color, accent: Color, base: Color, scale: Float): XmbFrost {
    val dark = isDarkBase(background)
    val tint = if (dark) mixColor(Color.White, accent, 0.10f) else Color.White
    val cardAlpha = (if (dark) FROST_CARD_ALPHA else FROST_CARD_ALPHA_LIGHT) * scale
    val raisedAlpha = (if (dark) FROST_RAISED_ALPHA else FROST_RAISED_ALPHA_LIGHT) * scale
    return XmbFrost(
        card = tint.copy(alpha = cardAlpha),
        raised = tint.copy(alpha = raisedAlpha),
        bar = if (dark) base.copy(alpha = FROST_BAR_ALPHA) else Color.White.copy(alpha = 0.60f),
        line = if (dark) Color.White.copy(alpha = FROST_LINE_ALPHA) else Color.Black.copy(alpha = 0.12f),
        tint = tint,
    )
}

/** The secondary text over the glow: the theme's own, lifted halfway to the main text colour on a dark theme (a grey like #AAAAAA is the first to run out of contrast). */
internal fun xmbGlowSecondaryText(background: Color, onBackground: Color, onSurfaceVariant: Color): Color =
    if (isDarkBase(background)) mixColor(onSurfaceVariant, onBackground, 0.5f) else onSurfaceVariant

private fun glowFalloff(t: Float): Float {
    if (t >= 1f) return 0f
    if (t <= 0f) return 1f
    for (i in 1 until GLOW_STOPS.size) {
        if (t <= GLOW_STOPS[i]) {
            val u = (t - GLOW_STOPS[i - 1]) / (GLOW_STOPS[i] - GLOW_STOPS[i - 1])
            return GLOW_LEVELS[i - 1] + (GLOW_LEVELS[i] - GLOW_LEVELS[i - 1]) * u
        }
    }
    return 0f
}

/**
 * The colour the backdrop really is at ([x], [y]) (0..1 across the box) for a box [aspect] (width / height) wide: the base
 * with each blob over it, as the gradients composite on screen.
 */
internal fun xmbGlowColorAt(glow: XmbGlow, x: Float, y: Float, aspect: Float): Color {
    val reachBox = max(aspect, 1f)
    var c = glow.base
    for (b in glow.blobs) {
        val dx = (x - b.cx) * aspect
        val dy = y - b.cy
        val d = sqrt(dx * dx + dy * dy) / (reachBox * b.radius)
        c = mixColor(c, b.color, b.alpha * glowFalloff(d))
    }
    return c
}

/**
 * The lowest contrast any of [texts] gets anywhere on the glow: bare, on a card, and on a chip on a card (the glass stacks,
 * and each layer lifts the ground a little more). Sampled on a [gridX] x [gridY] grid over the boxes of every shape in
 * [GLOW_ASPECTS], plus the centre of each blob, where the glow is brightest.
 */
internal fun xmbGlowWorstContrast(glow: XmbGlow, texts: List<Color>, gridX: Int = 16, gridY: Int = 10): Double {
    val inks = texts.map { xmbLuminance(it.red, it.green, it.blue) }
    var worst = Double.MAX_VALUE
    for (aspect in GLOW_ASPECTS) {
        val points = ArrayList<FloatArray>()
        for (i in 0..gridX) for (j in 0..gridY) points.add(floatArrayOf(i / gridX.toFloat(), j / gridY.toFloat()))
        for (b in glow.blobs) points.add(floatArrayOf(b.cx, b.cy))
        for (p in points) {
            val bare = xmbGlowColorAt(glow, p[0], p[1], aspect)
            val onCard = overColor(bare, glow.frost.card)
            val onChip = overColor(onCard, glow.frost.raised)
            for (ground in listOf(bare, onCard, onChip)) {
                val lum = xmbLuminance(ground.red, ground.green, ground.blue)
                for (ink in inks) worst = min(worst, xmbContrast(ink, lum))
            }
        }
    }
    return worst
}

private fun buildGlow(background: Color, accent: Color, secondary: Color, strength: Float, frostScale: Float): XmbGlow {
    val base = xmbGlowBase(background, accent, strength)
    return XmbGlow(
        base = base,
        blobs = xmbGlowBlobs(accent, strength),
        frost = xmbFrostFor(background, accent, base, frostScale),
        strength = strength,
        frostScale = frostScale,
        secondaryText = secondary,
    )
}

/**
 * The glow for one colour scheme, as strong as it can be while the text stays at [XMB_GLOW_TARGET_CONTRAST] everywhere on it:
 * the blobs are dimmed first (down to 0.3 of their strength), and only when that is not enough the glass is thinned. A plain
 * preset keeps most of its glow; a bright accent (yellow, cyan) or a pale one is held back more. The accent can never take the
 * text below the minimum, whatever it is.
 */
internal fun xmbGlowFor(background: Color, accent: Color, onBackground: Color, onSurface: Color, onSurfaceVariant: Color): XmbGlow {
    val key = listOf(background.value, accent.value, onBackground.value, onSurface.value, onSurfaceVariant.value)
    synchronized(glowCache) { glowCache[key]?.let { return it } }
    val glow = searchGlow(background, accent, onBackground, onSurface, onSurfaceVariant)
    synchronized(glowCache) { glowCache[key] = glow }
    return glow
}

/**
 * Glows already settled on, newest kept: the search below is a few thousand colour mixes, which the Appearance screen would
 * otherwise repeat for all of its swatches each time it opens (and for every step of a dragged custom accent).
 */
private val glowCache = object : LinkedHashMap<List<ULong>, XmbGlow>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<ULong>, XmbGlow>?): Boolean = size > 48
}

private fun searchGlow(background: Color, accent: Color, onBackground: Color, onSurface: Color, onSurfaceVariant: Color): XmbGlow {
    val secondary = xmbGlowSecondaryText(background, onBackground, onSurfaceVariant)
    val texts = listOf(onBackground, onSurface, secondary)
    for (frostScale in floatArrayOf(1f, 0.85f, 0.7f, 0.55f, 0.4f)) {
        for (step in 20 downTo 6) {
            val glow = buildGlow(background, accent, secondary, step * 0.05f, frostScale)
            if (xmbGlowWorstContrast(glow, texts) >= XMB_GLOW_TARGET_CONTRAST) return glow
        }
    }
    // Still short: the thinnest glass and a fainter and fainter glow, down to none. With no glow at all whatever the text does
    // is the theme's own doing (a mid-grey on mid-grey scheme), not the glow's.
    for (step in 5 downTo 1) {
        val glow = buildGlow(background, accent, secondary, step * 0.05f, 0.4f)
        if (xmbGlowWorstContrast(glow, texts) >= XMB_GLOW_TARGET_CONTRAST) return glow
    }
    return buildGlow(background, accent, secondary, 0f, 0.4f)
}

// ---------------------------------------------------------------------------------------------------------------------
// The glow: drawing and theming.
// ---------------------------------------------------------------------------------------------------------------------

/** The wash [XmbWashTheme] settled on for this subtree, so a nested reader doesn't wash the already washed background again. */
private val LocalXmbWash = staticCompositionLocalOf<XmbWash?> { null }

/** The glow [XmbWashTheme] settled on (set in every XMB subtree, glow pages or not), for the panels that draw their own. */
private val LocalXmbGlow = staticCompositionLocalOf<XmbGlow?> { null }

/** True where the page itself sits on the glow (every XMB page but Home), so its cards turn to glass. */
private val LocalXmbOnGlow = staticCompositionLocalOf { false }

/** The scheme [XmbWashTheme] started from, before the glow changed some of it: [XmbFlatTheme] goes back to it. */
private val LocalXmbBaseScheme = staticCompositionLocalOf<ColorScheme?> { null }

/** The wash for the current theme; recomputed only when the colour scheme changes. */
@Composable
internal fun rememberXmbWash(): XmbWash {
    val provided = LocalXmbWash.current
    val cs = MaterialTheme.colorScheme
    return provided ?: remember(cs.background, cs.primary, cs.onBackground, cs.onSurface, cs.onSurfaceVariant) {
        xmbWashFor(cs.background, cs.primary, listOf(cs.onBackground, cs.onSurface, cs.onSurfaceVariant))
    }
}

/** The glow for the current theme; recomputed only when the colour scheme changes. */
@Composable
internal fun rememberXmbGlow(): XmbGlow {
    val provided = LocalXmbGlow.current
    val cs = MaterialTheme.colorScheme
    return provided ?: remember(cs.background, cs.primary, cs.onBackground, cs.onSurface, cs.onSurfaceVariant) {
        xmbGlowFor(cs.background, cs.primary, cs.onBackground, cs.onSurface, cs.onSurfaceVariant)
    }
}

/**
 * Paints [glow] into whatever box this is applied to: the base, then the blobs. The gradients are built once per size (the
 * cache), nothing is allocated while drawing, and nothing in it reads state, so a scroll behind or over it never redraws it.
 * Plain radial gradients with a bell fall-off look blurred on every Android version without a blur effect, and have no edges
 * to artifact (a real blur of a full-screen layer would cost more than it adds).
 */
internal fun xmbGlowDraw(glow: XmbGlow): Modifier = Modifier.drawWithCache {
    val reach = max(size.width, size.height)
    val brushes = glow.blobs.map { b ->
        val stops = Array(GLOW_STOPS.size) { i -> GLOW_STOPS[i] to b.color.copy(alpha = b.alpha * GLOW_LEVELS[i]) }
        Brush.radialGradient(
            *stops,
            center = Offset(size.width * b.cx, size.height * b.cy),
            radius = reach * b.radius,
        )
    }
    onDrawBehind {
        drawRect(glow.base)
        for (brush in brushes) drawRect(brush)
    }
}

/**
 * The glow behind this element, drawn straight into it (no layer of its own): for a screen whose root is a scrolling column
 * or grid, which must not become an offscreen layer. Needs the glow of the current theme, so use it under [XmbWashTheme].
 */
@Composable
internal fun Modifier.xmbGlowBackground(): Modifier {
    val glow = rememberXmbGlow()
    return this.then(remember(glow) { xmbGlowDraw(glow) })
}

/** A small glow for a swatch or a preview: [glow] drawn into [modifier]'s box, with no layer of its own. */
@Composable
internal fun XmbGlowCanvas(glow: XmbGlow, modifier: Modifier = Modifier) {
    val draw = remember(glow) { xmbGlowDraw(glow) }
    Box(modifier.then(draw))
}

/**
 * The backdrop of an XMB page: the glow for the current theme, filling the box it is given. It is one cached layer (drawn
 * once and then just composited), so it costs nothing per frame while the page on top of it scrolls.
 */
@Composable
internal fun XmbGlowBackdrop(modifier: Modifier = Modifier) {
    val glow = rememberXmbGlow()
    val draw = remember(glow) { xmbGlowDraw(glow) }
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.then(draw))
}

/** The scheme colours that carry the glass look: the page background goes clear, and the two "raised" surfaces turn to frost. */
private fun ColorScheme.onGlow(glow: XmbGlow): ColorScheme =
    copy(
        background = Color.Transparent,
        surfaceVariant = glow.frost.raised,
        surfaceContainerHighest = glow.frost.raised,
        onSurfaceVariant = glow.secondaryText,
    )

/**
 * In XMB mode, a theme for the pages inside. With [glow] (every page but Home) the pages sit on [XmbGlowBackdrop]: their own
 * `colorScheme.background` fills go clear so the glow shows through, the two raised surfaces become glass and the secondary
 * text is lifted to stay readable on it. Without it (Home) `background` is the flat wash, as before. Everything else in the
 * scheme is untouched, so stock dialogs, menus and sheets keep their opaque surfaces. Off: [content] as is.
 */
@Composable
internal fun XmbWashTheme(enabled: Boolean, glow: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val wash = rememberXmbWash()
    val spec = rememberXmbGlow()
    val base = MaterialTheme.colorScheme
    // Not remembered: MaterialTheme keeps one ColorScheme and updates it in place, so `base` is the same object after a theme
    // change; reading it here is what makes this recompose, and the copy is cheap.
    val themed = if (glow) base.onGlow(spec) else base.copy(background = wash.flat)
    CompositionLocalProvider(
        LocalXmbWash provides wash,
        LocalXmbGlow provides spec,
        LocalXmbOnGlow provides glow,
        LocalXmbBaseScheme provides (LocalXmbBaseScheme.current ?: base),
    ) {
        MaterialTheme(
            colorScheme = themed,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

/**
 * For a part that has to look the same on every XMB page and over Home, and sits inside the glow pages' theme (the XMB
 * keyboard, drawn in a window of its own): the scheme the glow started from with the flat wash as its background, and none of
 * the glass. Not in XMB mode: [content] as is.
 */
@Composable
internal fun XmbFlatTheme(content: @Composable () -> Unit) {
    val base = LocalXmbBaseScheme.current
    val wash = LocalXmbWash.current
    if (base == null || wash == null) {
        content()
        return
    }
    CompositionLocalProvider(LocalXmbOnGlow provides false) {
        MaterialTheme(
            colorScheme = base.copy(background = wash.flat),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

/**
 * The wash already settled on the theme background, as one opaque fill: for a screen that is the bottom layer of Home or the
 * All Games page (the glow pages use [XmbGlowBackdrop]). It is one colour, so a fill under [XmbWashTheme] and a fill painted
 * again on top of it look the same, and a host without the shell still gets the wash. In XMB mode only; elsewhere keep the
 * plain `background(colorScheme.background)`.
 */
@Composable
internal fun Modifier.xmbWashBackground(): Modifier = this.background(rememberXmbWash().flat)

/**
 * The page background as a solid colour, for something drawn in it (a fade into the page, a cut-out). On the glow pages the
 * scheme's `background` is clear, which would draw nothing, so this is the glow's own base there; elsewhere the scheme's.
 */
@Composable
internal fun xmbSolidBackground(): Color {
    val background = MaterialTheme.colorScheme.background
    return if (background.alpha > 0f) background else LocalXmbGlow.current?.base ?: background
}

/** A card's fill: the glass on a glow page, else [fallback] (the card's own colour, untouched off the glow, in dialogs and over Home). */
@Composable
internal fun xmbCardFill(fallback: Color): Color = xmbPageFrost()?.card ?: fallback

/** The hairline round a card: the light glass line on a glow page, else [fallback]. */
@Composable
internal fun xmbCardLine(fallback: Color): Color = xmbPageFrost()?.line ?: fallback

/**
 * A solid chip fill for a label over artwork (a badge on a cover). `surfaceContainerHighest`, as before, except on the glow
 * pages where that is glass: the artwork would show through it, so there it is the opaque `surfaceContainerHigh`.
 */
@Composable
internal fun xmbChipOverArt(): Color {
    val cs = MaterialTheme.colorScheme
    return if (xmbOnGlow()) cs.surfaceContainerHigh else cs.surfaceContainerHighest
}

/** True while the page sits on the glow, so a card there is glass. */
@Composable
internal fun xmbOnGlow(): Boolean = LocalXmbOnGlow.current && LocalXmbGlow.current != null

/**
 * The glass fills for a card on a glow page, or null: off the glow, and inside a dialog, menu or sheet (their window is the
 * page's composition but not its pixels, so a see-through card would show the dimmed page behind it instead of the glow).
 */
@Composable
internal fun xmbPageFrost(): XmbFrost? {
    if (!LocalXmbOnGlow.current) return null
    val glow = LocalXmbGlow.current ?: return null
    return if (inFloatingWindow()) null else glow.frost
}

private fun Context.hostActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return c as? Activity
}

/** Whether this composition is drawn in a window of its own (a dialog, a popup menu, a sheet) rather than in the activity's. */
@Composable
private fun inFloatingWindow(): Boolean {
    val view = LocalView.current
    return remember(view) {
        val decor = view.context.hostActivity()?.window?.decorView
        decor != null && view.rootView !== decor
    }
}

/**
 * The look of a panel in a window of its own (a dialog, the search overlay): clipped to [shape], the glow drawn inside it as
 * its own opaque backdrop, the glass over that and the hairline round it. A panel can't be see-through (the dimmed page is
 * behind it), so it carries its own glow instead. Only on a glow page (or inside [XmbPanelTheme]), where the text is the
 * lifted one the glow was tuned for; elsewhere [fill] and the Deck hairline, as before.
 */
@Composable
internal fun Modifier.xmbPanel(shape: Shape, fill: Color): Modifier {
    val glow = if (LocalXmbOnGlow.current) LocalXmbGlow.current else null
    if (glow == null) return this.clip(shape).background(fill).border(1.dp, deckLine(), shape)
    val draw = remember(glow) { xmbGlowDraw(glow) }
    return this.clip(shape).then(draw).background(glow.frost.card).border(1.dp, glow.frost.line, shape)
}

/**
 * For a panel that draws its own glow ([xmbPanel]) outside the glow pages (the search over Home): the same scheme the glow
 * pages have, so its text is the lifted one the glow was tuned for. Not in XMB mode: [content] as is.
 */
@Composable
internal fun XmbPanelTheme(content: @Composable () -> Unit) {
    val glow = LocalXmbGlow.current
    if (glow == null) {
        content()
        return
    }
    CompositionLocalProvider(LocalXmbOnGlow provides true) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.onGlow(glow),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
