package com.winlator.star.ui.simple

import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.border
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import kotlin.math.max

/**
 * No press or focus shading. The Simple menus draw their own focus (the lit row, the orange frame); the
 * default indication would add a grey box inside it on rows that come from the Deck pages.
 */
private object SimpleNoIndication : Indication {
    private object Instance : IndicationInstance {
        override fun ContentDrawScope.drawIndication() { drawContent() }
    }

    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance = Instance
}

/** Runs [content] without the default press / focus shading (see [SimpleNoIndication]). */
@Composable
internal fun SimpleFlat(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides SimpleNoIndication, content = content)
}

/**
 * Keeps item [index] on screen with a row of room above and below, scrolling only when it would leave
 * the visible part. The list stays still while the focus moves inside it.
 */
internal suspend fun LazyListState.keepVisible(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) {
        scrollToItem(max(0, index - 1))
        return
    }
    val first = visible.first().index
    val last = visible.last().index
    val viewEnd = layoutInfo.viewportEndOffset
    val lastFull = visible.lastOrNull { it.offset + it.size <= viewEnd }?.index ?: last
    when {
        index <= first -> animateScrollToItem(max(0, index - 1))
        index >= lastFull -> animateScrollToItem(max(0, first + (index - lastFull) + 1))
    }
}

/**
 * The focused row's highlight, eased in and out (about 180 ms) instead of switched, so moving with the D-pad glides
 * from row to row: the accent tint, its bar at the left edge and the lift all follow the same progress.
 * [active] is false while the controller is in another part of the page (the row keeps a quiet grey mark).
 */
@androidx.compose.runtime.Composable
internal fun Modifier.simpleRowFocus(focused: Boolean, active: Boolean): Modifier {
    val p by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1f else 0f,
        androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "simpleRowFocus",
    )
    if (p < 0.01f) return this
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val light = com.winlator.star.ui.theme.AppThemeState.labLight
    val quiet = if (light) androidx.compose.ui.graphics.Color(0xFF111722) else androidx.compose.ui.graphics.Color.White
    val top = if (active) accent.copy(alpha = 0.33f * p) else quiet.copy(alpha = 0.12f * p)
    val bottom = if (active) accent.copy(alpha = 0.16f * p) else quiet.copy(alpha = 0.08f * p)
    return this
        .shadow((9f * p).dp, SimpleShape, clip = false, ambientColor = androidx.compose.ui.graphics.Color.Black, spotColor = androidx.compose.ui.graphics.Color.Black)
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(top, bottom)), SimpleShape)
        .drawBehind { if (active) drawRect(accent.copy(alpha = p), size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
}

/** A tab's selected look (fill, outline, lift), eased as the selection moves with L/R or a tap. */
@androidx.compose.runtime.Composable
internal fun Modifier.tabFill(on: Boolean, accent: androidx.compose.ui.graphics.Color): Modifier {
    val t by androidx.compose.animation.core.animateFloatAsState(
        if (on) 1f else 0f,
        androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "tabFill",
    )
    if (t < 0.01f) return this.border(1.dp, androidx.compose.ui.graphics.Color.Transparent, SimpleShape)
    return this
        .shadow((9f * t).dp, SimpleShape, clip = false, ambientColor = androidx.compose.ui.graphics.Color.Black, spotColor = androidx.compose.ui.graphics.Color.Black)
        .background(accent.copy(alpha = 0.24f * t), SimpleShape)
        .border(1.dp, accent.copy(alpha = t), SimpleShape)
}

/**
 * Keeps this element in view while [on]: the scrolling row it sits in glides to it (a tab row stepped with L2/R2, so the
 * selected tab never goes past the edge).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
internal fun Modifier.keepInViewWhen(on: Boolean): Modifier {
    val req = androidx.compose.runtime.remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(on) { if (on) runCatching { req.bringIntoView() } }
    return this.bringIntoViewRequester(req)
}

/**
 * Lab dialogs: a focused clickable (an option card, a list row) gets Lab's accent tint and outline, a pressed one a light
 * tint, instead of Material's faint grey state layer, so the controller's place is plain to see.
 */
internal class LabFocusIndication(private val accent: androidx.compose.ui.graphics.Color) : Indication {
    private class Inst(
        val accent: androidx.compose.ui.graphics.Color,
        val focused: androidx.compose.runtime.State<Boolean>,
        val pressed: androidx.compose.runtime.State<Boolean>,
    ) : IndicationInstance {
        override fun ContentDrawScope.drawIndication() {
            drawContent()
            if (focused.value) {
                drawRect(accent.copy(alpha = 0.14f))
                drawRect(accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            } else if (pressed.value) {
                drawRect(accent.copy(alpha = 0.10f))
            }
        }
    }

    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance {
        val focused = interactionSource.collectIsFocusedAsState()
        val pressed = interactionSource.collectIsPressedAsState()
        return androidx.compose.runtime.remember(interactionSource, accent) { Inst(accent, focused, pressed) }
    }
}
