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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R

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
 * How much smaller the Deck draws than Classic, on top of Appearance's interface size (WinlatorTheme
 * already folds that into the density). Everything under [DeckTheme] scales together: the Deck pages,
 * the Classic screens and dialogs hosted in the shell, and the first-run.
 */
internal const val DECK_SCALE = 0.85f

/**
 * Wraps the Deck shell and the Deck first-run. Keeps the ambient colour scheme and swaps only typography and shapes, so
 * the screens hosted inside (Containers, Settings, ...) pick up the Deck type without any colour change, and draws
 * everything at [DECK_SCALE] (text included, since the font scale rides on the density).
 */
@Composable
internal fun DeckTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaled = remember(density) { Density(density.density * DECK_SCALE, density.fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled) {
        DeckMaterialTheme(content)
    }
}

@Composable
private fun DeckMaterialTheme(content: @Composable () -> Unit) {
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
        extraSmall = RoundedCornerShape(8.dp),
        small      = RoundedCornerShape(12.dp),
        medium     = RoundedCornerShape(16.dp),
        large      = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}

/** Translucent bar fill (top strip, legend, bottom bar): the theme background at 72%. */
@Composable
internal fun deckGlass(): Color = MaterialTheme.colorScheme.background.copy(alpha = 0.72f)

/** Card fill used by shelves and hub tiles: the theme surface at 90%. */
@Composable
internal fun deckCardFill(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)

/** Hairline around cards and bars. */
@Composable
internal fun deckLine(): Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)

/**
 * The Deck focus treatment: the focused element scales up and gets an accent outline with a soft
 * accent glow. Put it BEFORE the clickable/focusable modifier so it can observe that node's focus.
 */
@Composable
internal fun Modifier.deckFocusRing(
    shape: Shape,
    scaleTo: Float = 1.05f,
    onFocused: (() -> Unit)? = null,
): Modifier {
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
            if (focused) Modifier
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
        else -> RoundedCornerShape(7.dp)
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
                if (round && kind != GlyphKind.A && kind != GlyphKind.B) Modifier.border(1.5.dp, cs.onSurfaceVariant.copy(alpha = 0.6f), shape)
                else if (!round) Modifier.border(1.5.dp, cs.onSurfaceVariant.copy(alpha = 0.6f), shape)
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
