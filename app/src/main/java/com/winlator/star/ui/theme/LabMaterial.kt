package com.winlator.star.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.star.ui.deck.NunitoSansFamily
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.LocalLabLook
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.SimpleStrong

// Lab's Material layer: while the Lab style is on (AppThemeState.simpleLook), WinlatorTheme gives every Material screen and
// pop-up that Lab still shows (a store's old library screen, a sign-in page, the file picker, a Classic dialog) Lab's corners,
// type, panel colours and frames, as a safety net under the screens that draw Lab's own look. Off Lab nothing here is used.
// Hosts that draw Lab's own look (DeckTheme: the Lab shell, the in-game menu) keep the theme's own colours (themeColorsFor).

/** Lab's corners for Material: the hard 3 dp corner on fields, menus, chips and cards, 4 dp on dialogs and sheets. */
internal val LabShapes = Shapes(
    extraSmall = SimpleShape,
    small = SimpleShape,
    medium = SimpleShape,
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

private fun TextStyle.labHeading(weight: FontWeight) = copy(fontFamily = SoraFamily, fontWeight = weight)
private fun TextStyle.labBody(weight: FontWeight) = copy(fontFamily = NunitoSansFamily, fontWeight = weight)

/** Lab's type on [base]'s sizes: Sora for display, headline and title styles, Nunito Sans for body and labels (as DeckTheme). */
internal fun labTypography(base: Typography): Typography = Typography(
    displayLarge = base.displayLarge.labHeading(FontWeight.ExtraBold),
    displayMedium = base.displayMedium.labHeading(FontWeight.ExtraBold),
    displaySmall = base.displaySmall.labHeading(FontWeight.ExtraBold),
    headlineLarge = base.headlineLarge.labHeading(FontWeight.Bold),
    headlineMedium = base.headlineMedium.labHeading(FontWeight.Bold),
    headlineSmall = base.headlineSmall.labHeading(FontWeight.Bold),
    titleLarge = base.titleLarge.labHeading(FontWeight.Bold),
    titleMedium = base.titleMedium.labHeading(FontWeight.SemiBold),
    titleSmall = base.titleSmall.labHeading(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.labBody(FontWeight.SemiBold),
    bodyMedium = base.bodyMedium.labBody(FontWeight.Normal),
    bodySmall = base.bodySmall.labBody(FontWeight.Normal),
    labelLarge = base.labelLarge.labBody(FontWeight.Bold),
    labelMedium = base.labelMedium.labBody(FontWeight.Bold),
    labelSmall = base.labelSmall.labBody(FontWeight.Bold),
)

/**
 * Lab's colours for Material: the light look is Lab's light scheme (as the Lab shell uses); the dark look keeps the theme's
 * page background and accent and takes Lab's panel, line and ink colours for surfaces, containers and outlines (the stores'
 * dark scheme), with no accent tint on raised surfaces. Only the slots that scheme sets are changed.
 */
internal fun labMaterialScheme(base: ColorScheme, light: Boolean): ColorScheme =
    if (light) com.winlator.star.ui.simple.labLightScheme(base)
    else com.winlator.star.store.storeLabScheme(base).copy(background = base.background, onBackground = base.onBackground)

/** The scheme WinlatorTheme put in place for Lab, the theme's own one under it, and which Lab look made it; null off Lab. */
internal class LabMaterialLayer(val scheme: ColorScheme, val base: ColorScheme, val light: Boolean)

internal val LocalLabMaterialLayer = staticCompositionLocalOf<LabMaterialLayer?> { null }

private fun ColorScheme.sameColorsAs(o: ColorScheme): Boolean = this === o || (
    primary == o.primary && background == o.background && surface == o.surface && surfaceVariant == o.surfaceVariant &&
        surfaceContainer == o.surfaceContainer && outline == o.outline && onSurface == o.onSurface && surfaceTint == o.surfaceTint
    )

/**
 * The colours a host that draws Lab's own look (DeckTheme) starts from. In Lab's dark look, where [current] still has the
 * Material layer's colours, the theme's own (the Lab shell and the in-game menu draw the theme's background and surfaces, as
 * before the layer). Anything else is kept: a screen that set colours of its own (the store screens), the light look (its
 * layer is Lab's light scheme, which those hosts use anyway), and everything off Lab (no layer).
 */
@Composable
internal fun themeColorsFor(current: ColorScheme): ColorScheme {
    val layer = LocalLabMaterialLayer.current ?: return current
    return if (!layer.light && current.sameColorsAs(layer.scheme)) layer.base else current
}

/**
 * For a Compose view that does not use WinlatorTheme and keeps colours of its own (the controls editor): Lab's corners, type
 * and frames around [content] while Lab is on; [content] as it is otherwise.
 */
@Composable
fun LabMaterialFrame(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember { AppThemeState.ensureInit(context) }
    if (!AppThemeState.simpleLook) {
        content()
        return
    }
    val base = MaterialTheme.typography
    val typography = remember(base) { labTypography(base) }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = typography, shapes = LabShapes) {
        CompositionLocalProvider(LocalLabLook provides true, content = content)
    }
}

/** Java hosts: `setContent` with [content] inside [LabMaterialFrame]. */
fun setLabContent(view: ComposeView, content: @Composable () -> Unit) {
    view.setContent { LabMaterialFrame(content) }
}

/** A pill (Material's default button shape) becomes Lab's hard corner; any other shape the caller chose stays. */
private fun labButtonShape(shape: Shape): Shape = if (shape == CircleShape) SimpleShape else shape

/** Material's pill, or Lab's hard corner while Lab is on: the outline shape of a button the Lab drop-ins below draw. */
internal fun labPillShape(): Shape = if (AppThemeState.simpleLook) SimpleShape else CircleShape

/** True where something around the buttons already draws Lab's focus outline (a Lab dialog's button row). */
internal val LocalLabFocusDrawn = staticCompositionLocalOf { false }

/**
 * Lab's focus on a Material button: the accent outline (a contrasting one on an accent-filled button) in the button's shape, and
 * the move sound, as Lab's own tiles. Not under a highlight host (the store screens draw their own gliding outline there).
 */
@Composable
private fun Modifier.labButtonFocus(interactionSource: MutableInteractionSource, shape: Shape, onAccent: Boolean): Modifier {
    val drawn = LocalLabFocusDrawn.current
    val hosted = com.winlator.star.ui.deck.xmbHighlightHostOrNull() != null
    if (drawn || hosted) return this
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) com.winlator.star.sound.UiSounds.focusGained() }
    if (!focused) return this
    return if (onAccent) this.border(3.dp, SimpleStrong, shape) else this.border(2.dp, MaterialTheme.colorScheme.primary, shape)
}

// Drop-ins for Material's buttons with the same parameters (import one `as Button` and so on). Off Lab each is exactly the
// Material button; in Lab its pill turns into Lab's hard corner and focus takes Lab's outline.

@Composable
internal fun LabButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    if (!AppThemeState.simpleLook) {
        androidx.compose.material3.Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
        return
    }
    val s = labButtonShape(shape)
    androidx.compose.material3.Button(onClick, modifier.labButtonFocus(interactionSource, s, onAccent = true), enabled, s, colors, elevation, border, contentPadding, interactionSource, content)
}

@Composable
internal fun LabOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    if (!AppThemeState.simpleLook) {
        androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
        return
    }
    val s = labButtonShape(shape)
    androidx.compose.material3.OutlinedButton(onClick, modifier.labButtonFocus(interactionSource, s, onAccent = false), enabled, s, colors, elevation, border, contentPadding, interactionSource, content)
}

@Composable
internal fun LabTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    if (!AppThemeState.simpleLook) {
        androidx.compose.material3.TextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
        return
    }
    val s = labButtonShape(shape)
    androidx.compose.material3.TextButton(onClick, modifier.labButtonFocus(interactionSource, s, onAccent = false), enabled, s, colors, elevation, border, contentPadding, interactionSource, content)
}
