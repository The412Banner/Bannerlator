package com.winlator.star.ui.deck

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.ui.LocalDeckDensity

// Deck look: Sora for headings and Nunito Sans for body text, both under the SIL Open Font License 1.1.
// The TTFs are static instances from Google Fonts; the attribution is in THIRD-PARTY-LICENSES.md.
// Colours are never set here; everything reads MaterialTheme.colorScheme, so the user's preset and custom accent carry over.
internal val SoraFamily = FontFamily(
    Font(R.font.sora_semibold, FontWeight.SemiBold),
    Font(R.font.sora_bold, FontWeight.Bold),
    Font(R.font.sora_extrabold, FontWeight.ExtraBold),
)

internal val NunitoSansFamily = FontFamily(
    Font(R.font.nunito_sans_regular, FontWeight.Normal),
    Font(R.font.nunito_sans_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_sans_bold, FontWeight.Bold),
    Font(R.font.nunito_sans_extrabold, FontWeight.ExtraBold),
)

private fun TextStyle.heading(weight: FontWeight) = copy(fontFamily = SoraFamily, fontWeight = weight)
private fun TextStyle.body(weight: FontWeight) = copy(fontFamily = NunitoSansFamily, fontWeight = weight)

/**
 * Wraps the Deck shell. Keeps the ambient colour scheme and swaps only typography and shapes, so
 * the screens hosted inside (Containers, Settings, ...) pick up the Deck type without any colour change.
 */
@Composable
internal fun DeckTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val typography = Typography(
        displayLarge   = base.displayLarge.heading(FontWeight.ExtraBold),
        displayMedium  = base.displayMedium.heading(FontWeight.ExtraBold),
        displaySmall   = base.displaySmall.heading(FontWeight.ExtraBold),
        headlineLarge  = base.headlineLarge.heading(FontWeight.Bold),
        headlineMedium = base.headlineMedium.heading(FontWeight.Bold),
        headlineSmall  = base.headlineSmall.heading(FontWeight.Bold),
        titleLarge     = base.titleLarge.heading(FontWeight.Bold),
        titleMedium    = base.titleMedium.heading(FontWeight.SemiBold),
        titleSmall     = base.titleSmall.heading(FontWeight.SemiBold),
        bodyLarge      = base.bodyLarge.body(FontWeight.SemiBold),
        bodyMedium     = base.bodyMedium.body(FontWeight.Normal),
        bodySmall      = base.bodySmall.body(FontWeight.Normal),
        labelLarge     = base.labelLarge.body(FontWeight.Bold),
        labelMedium    = base.labelMedium.body(FontWeight.Bold),
        labelSmall     = base.labelSmall.body(FontWeight.Bold),
    )
    val shapes = Shapes(
        extraSmall = deckShape(8.dp),
        small      = deckShape(12.dp),
        medium     = deckShape(16.dp),
        large      = deckShape(18.dp),
        extraLarge = deckShape(24.dp),
    )
    // The Deck style is drawn DECK_UI_SCALE smaller than the rest of the app (on top of the interface-size setting),
    // so a phone or handheld at its stock display size has room for the console layout. Once only: a dialog's
    // DeckTheme in a dialog of a Deck screen takes the screen's density, not the dialog window's raw one.
    val outer = LocalDensity.current
    val density = LocalDeckDensity.current ?: Density(outer.density * DECK_UI_SCALE, outer.fontScale)
    // Left-to-right always, whatever the phone's language (see AppLocale): the XMB rows place with Modifier.offset, which mirrors.
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalDeckDensity provides density,
        LocalLayoutDirection provides LayoutDirection.Ltr,
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}

/** The Simple style's hard corner. */
private val SimpleCorner = RoundedCornerShape(3.dp)

/**
 * A Deck corner of [radius], or the Simple style's hard 3 dp corner while Simple is on. Reads snapshot state
 * (AppThemeState.simpleLook), so a composable that asks for it recomposes when the style changes.
 */
internal fun deckShape(radius: androidx.compose.ui.unit.Dp): androidx.compose.foundation.shape.CornerBasedShape =
    if (com.winlator.star.ui.theme.AppThemeState.simpleLook) SimpleCorner else RoundedCornerShape(radius)

/** How much smaller the Deck style draws than the rest of the app. */
internal const val DECK_UI_SCALE = 0.8f

/**
 * The density the Deck style draws at: the Deck screen's own when there is one, else the current density at [DECK_UI_SCALE].
 * The XMB "B Back" / "Y Search" hints are drawn at this density on every page, so a store or download screen (which runs at
 * the app's own, larger density) shows them at the size and place Home does.
 */
@Composable
internal fun rememberXmbHintDensity(): Density {
    val outer = LocalDensity.current
    return LocalDeckDensity.current ?: Density(outer.density * DECK_UI_SCALE, outer.fontScale)
}

// On an XMB glass page (see XmbBackdrop.kt) the three below turn to frosted glass: a slightly lighter translucent fill and a thin
// light border over the charcoal base, and a veil of the base for the bars. A card also gets the sheen at its top (xmbSheen,
// right after its background). Everywhere else, including Home and the dialogs, they are as they were.

/** Translucent bar fill (top strip, legend, bottom bar): the theme background at 72%. */
@Composable
internal fun deckGlass(): Color = xmbPageFrost()?.bar ?: MaterialTheme.colorScheme.background.copy(alpha = 0.72f)

/** A card fill a screen sets for the cards inside it (Lab's in-game menu over the game's art: see-through glass). */
internal val LocalDeckCardFill = androidx.compose.runtime.staticCompositionLocalOf<Color?> { null }

/** Card fill used by shelves and hub tiles: the theme surface at 90%. */
@Composable
internal fun deckCardFill(): Color = LocalDeckCardFill.current ?: xmbPageFrost()?.card ?: MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)

/** Hairline around cards and bars. */
@Composable
internal fun deckLine(): Color = xmbPageFrost()?.line ?: MaterialTheme.colorScheme.outline.copy(alpha = DECK_LINE_BASE_ALPHA)

// On the XMB glass `surfaceVariant` is translucent white (alpha 0.12) and `deckLine()` is white at 13%. `copy(alpha)` REPLACES the
// alpha, so the settings controls (segmented tracks, selects, chips, fields) written as `surfaceVariant.copy(alpha = 0.55f)` or
// `deckLine().copy(alpha = 0.5f)` would turn into washed light-grey tracks with a bright white border there. The helpers below keep
// the intent (an inset fill, a soft line) on the glass and are exactly those expressions everywhere else.

/** The alpha [deckLine] has outside the glass; [deckSoftLine] scales a softer line from it. */
internal const val DECK_LINE_BASE_ALPHA = 0.8f

/** How dark the inset controls are on the XMB glass: black over the card, so a track reads as a groove and its labels gain contrast. */
internal const val XMB_INSET_BLACK_ALPHA = 0.26f

/**
 * The fill of an inset control (a track, a select, a chip, a small button) from the scheme's [variant] surface. On an opaque scheme
 * that is [variant] at [alpha], as it always was. On the XMB glass the scheme's variant is translucent frost (alpha below 1), and
 * laying it at [alpha] would paint it as near-white; there the control is a darker translucent black over the card instead.
 */
internal fun deckInsetFill(variant: Color, alpha: Float): Color =
    if (variant.alpha < 1f) Color.Black.copy(alpha = XMB_INSET_BLACK_ALPHA) else variant.copy(alpha = alpha)

@Composable
internal fun deckInset(alpha: Float = 0.55f): Color = deckInsetFill(MaterialTheme.colorScheme.surfaceVariant, alpha)

/** A softer [line] (a glass line, whose alpha is already low): its own alpha scaled by [alpha] relative to [DECK_LINE_BASE_ALPHA]. */
internal fun deckSoftLine(line: Color, alpha: Float): Color =
    line.copy(alpha = line.alpha * (alpha / DECK_LINE_BASE_ALPHA).coerceIn(0f, 1f))

/** [deckLine] at [alpha]: `deckLine().copy(alpha = alpha)` outside the glass, a proportionally softer glass line on it. */
@Composable
internal fun deckLineAt(alpha: Float): Color {
    val frost = xmbPageFrost() ?: return deckLine().copy(alpha = alpha)
    return deckSoftLine(frost.line, alpha)
}

/**
 * The Deck focus treatment: the focused element scales up and gets an accent outline with a soft
 * accent glow. Put it BEFORE the clickable/focusable modifier so it can observe that node's focus.
 *
 * In the XMB interface, under a highlight host (XmbHighlight.kt: the shell and every [DeckDialogScaled] window have one), this
 * does none of that: [scaleTo], the shadow and the border are dropped and the element just registers as a stop of the one
 * gliding outline. Without a host, and everywhere outside XMB, it is exactly the ring described above.
 */
@Composable
internal fun Modifier.deckFocusRing(
    shape: Shape,
    scaleTo: Float = 1.05f,
    onFocused: (() -> Unit)? = null,
    /** On an accent-filled control: Lab draws a contrasting ring (an accent one would not show). */
    onAccent: Boolean = false,
): Modifier {
    if (xmbHighlightHostOrNull() != null) return this.xmbHighlight(shape, onFocused = onFocused)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) scaleTo else 1f, label = "deckFocusScale")
    val accent = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged {
            val now = it.isFocused
            if (now && !focused) onFocused?.invoke()
            focused = now
        }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            // Simple: a flat accent tint, no glow. Its shadow shows through the see-through Simple panels as a dark band.
            if (focused && com.winlator.star.ui.theme.AppThemeState.simpleLook && onAccent) Modifier
                .border(3.dp, com.winlator.star.ui.simple.SimpleStrong, shape)
            else if (focused && com.winlator.star.ui.theme.AppThemeState.simpleLook) Modifier
                .background(accent.copy(alpha = 0.16f), shape)
                .border(2.dp, accent, shape)
            else if (focused) Modifier
                .shadow(18.dp, shape, ambientColor = accent, spotColor = accent)
                .border(3.dp, accent, shape)
            else Modifier
        )
}

/** Which face a controller button glyph uses. Face buttons are round, bumpers are keycaps. */
internal enum class GlyphKind { A, B, X, Y, BUMPER, PLAIN }

/** A controller button glyph ("A", "L1", ...) drawn from theme colours only. */
@Composable
internal fun DeckGlyph(text: String, kind: GlyphKind, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val round = kind == GlyphKind.A || kind == GlyphKind.B || kind == GlyphKind.X || kind == GlyphKind.Y
    val shape = when {
        round -> CircleShape
        kind == GlyphKind.BUMPER -> RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
        else -> deckShape(7.dp)
    }
    val (fill, ink) = when (kind) {
        GlyphKind.A -> cs.primary to cs.onPrimary
        GlyphKind.B -> cs.error to cs.onError
        GlyphKind.X, GlyphKind.Y -> cs.surfaceVariant to cs.onSurface
        GlyphKind.BUMPER -> cs.surfaceContainerHigh.copy(alpha = 0.7f) to cs.onSurfaceVariant
        GlyphKind.PLAIN -> Color.Transparent to cs.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = if (kind == GlyphKind.BUMPER) 30.dp else 22.dp, minHeight = 22.dp)
            .background(fill, shape)
            .then(
                if (kind != GlyphKind.A && kind != GlyphKind.B) Modifier.border(1.5.dp, cs.onSurfaceVariant.copy(alpha = 0.6f), shape)
                else Modifier
            )
            .padding(horizontal = 5.dp),
    ) {
        Text(
            text = text,
            color = ink,
            fontFamily = SoraFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 10.5.sp,
            maxLines = 1,
        )
    }
}
