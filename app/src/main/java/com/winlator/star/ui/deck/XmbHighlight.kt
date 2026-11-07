package com.winlator.star.ui.deck

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

// The XMB gliding highlight: ONE outline per window that moves from the focused item to the next one, instead of every item
// drawing its own ring. Look: a thin (2 dp) accent outline with only a faint accent fill (the empty part of the item is barely
// coloured), drawn over the item; the item's icon and text turn accent-coloured through [XmbInk].
//
// How it fits together
//  * A host ([XmbHighlightHost] / [xmbHighlightOverlay]) sits above everything that can take focus in one window and owns the
//    overlay. Every window (the activity, each dialog) needs its own host: dialogs are separate windows, so [DeckDialogScaled]
//    puts one in itself.
//  * An item registers with [xmbHighlight] (deckFocusRing and steamFocusRing do it for you inside a host). It reports its
//    layout coordinates and its focus; the host decides which focused item is "hot" and moves the outline there.
//  * Moving between items glides (a spring); an item that just scrolls under the outline moves it without animating.
//  * The cost is per hot item: items only store coordinates in a plain field (no state writes), the host does one state
//    write per frame while the hot item moves, and nothing recomposes while the outline travels (it is a draw-phase effect).
//
// Design after DroidDeck's approach (GPL-3.0), written from a description only; no code was taken from it.

/** The host of the window this is composed in, or null: then items fall back to their own ring. */
internal val LocalXmbHighlight = staticCompositionLocalOf<XmbHighlightHost?> { null }

/** The highlight item of the enclosing [XmbGroupItem] (or other owner of a registered item), for [XmbInk]. */
internal val LocalXmbHighlightItem = compositionLocalOf<XmbHighlightItem?> { null }

// ---------------------------------------------------------------------------------------------------------------------
// Pure helpers (no Compose runtime needed; unit-tested).
// ---------------------------------------------------------------------------------------------------------------------

internal const val XMB_HL_STROKE_DP = 2f

/** The accent fill inside the outline: faint on purpose. */
internal const val XMB_HL_FILL_ALPHA = 0.10f

/** The static outline an outer item keeps while focus is on one of its inner buttons. */
internal const val XMB_HL_DIM_ALPHA = 0.35f

internal const val XMB_HL_SPRING_DAMPING = 0.8f
internal const val XMB_HL_SPRING_STIFFNESS = 900f
internal const val XMB_HL_COLOR_MS = 160
internal const val XMB_HL_FADE_IN_MS = 120
internal const val XMB_HL_FADE_OUT_MS = 160

/** How long the outline waits for a new item to claim it before it fades (focus often passes through "nothing" for a frame). */
internal const val XMB_HL_HIDE_GRACE_MS = 140L

/** Text/icons that turn accent-coloured keep at least this contrast on the glass panel (WCAG AA for text). */
internal const val XMB_INK_MIN_CONTRAST = 4.5

/** How the outline gets to a new target rect. */
internal enum class XmbHighlightMove { SNAP, GLIDE }

/**
 * SNAP when the outline was hidden (it appears in place), or when the same item only moved because the page scrolled; GLIDE
 * when the hot item changed, or when a glide is still running (the new target replaces it, keeping the speed).
 */
internal fun xmbMoveFor(wasHidden: Boolean, itemChanged: Boolean, animating: Boolean): XmbHighlightMove =
    if (wasHidden) XmbHighlightMove.SNAP else if (itemChanged || animating) XmbHighlightMove.GLIDE else XmbHighlightMove.SNAP

/**
 * Which of the focused items (all on one focus path, so each contains the next) is the hot one: the smallest, that is the
 * innermost. Equal sizes: the one that claimed focus last (the later entry). -1 for none.
 */
internal fun xmbPickHot(areas: FloatArray): Int {
    var best = -1
    var bestArea = Float.MAX_VALUE
    for (i in areas.indices) {
        if (best < 0 || areas[i] <= bestArea) {
            best = i
            bestArea = areas[i]
        }
    }
    return best
}

/** [radius] held to what a w x h box can show (half its short side). */
internal fun xmbCornerClamp(radius: Float, width: Float, height: Float): Float =
    radius.coerceIn(0f, min(width, height) / 2f)

/** [a] to [b] by [t], channel by channel (alpha too). Exactly [a] at 0 and [b] at 1. */
internal fun xmbMix(a: Color, b: Color, t: Float): Color {
    if (t <= 0f) return a
    if (t >= 1f) return b
    return Color(
        a.red + (b.red - a.red) * t,
        a.green + (b.green - a.green) * t,
        a.blue + (b.blue - a.blue) * t,
        a.alpha + (b.alpha - a.alpha) * t,
    )
}

/**
 * The colour an item's text and icons take when it is hot: [accent], moved towards [text] only as far as it takes to read on
 * [ground] ([minContrast]). A bright accent stays as it is; a deep blue becomes a pale one instead of vanishing.
 */
internal fun xmbInkColor(accent: Color, text: Color, ground: Color, minContrast: Double = XMB_INK_MIN_CONTRAST): Color {
    val groundLum = xmbLuminance(ground.red, ground.green, ground.blue)
    for (step in 0..10) {
        val c = xmbMix(accent, text, step / 10f)
        if (xmbContrast(xmbLuminance(c.red, c.green, c.blue), groundLum) >= minContrast) return c
    }
    return text
}

/** The corner radius (px) a [shape] has in a w x h box: the mean of its four corners, 0 for a shape without corners. */
internal fun xmbShapeCornerPx(shape: Shape, width: Float, height: Float, density: Density): Float {
    if (shape !is CornerBasedShape) return 0f
    val size = Size(width, height)
    val mean = (shape.topStart.toPx(size, density) + shape.topEnd.toPx(size, density) +
        shape.bottomStart.toPx(size, density) + shape.bottomEnd.toPx(size, density)) / 4f
    return xmbCornerClamp(mean, width, height)
}

// ---------------------------------------------------------------------------------------------------------------------
// The item and the host.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * One focusable thing that can carry the highlight. Created with [rememberXmbHighlightItem] and handed to [xmbHighlight]; the
 * same object goes to [XmbInk] so the item's text knows when it is hot.
 */
@Stable
internal class XmbHighlightItem {
    /** Plain fields (written during layout / composition, read when the outline is placed): no state, no recomposition. */
    internal var coords: LayoutCoordinates? = null
    internal var shape: Shape = RectangleShape
    internal var tint: Color = Color.Unspecified
    internal var dimWhenInside: Boolean = false
    internal var focused: Boolean = false

    /** [ROLE_HOT], [ROLE_SECONDARY] or [ROLE_NONE]; Compose state, changed only for the items that gain or lose the outline. */
    var role: Int by mutableIntStateOf(ROLE_NONE)
        internal set

    internal fun area(): Float {
        val c = coords ?: return Float.MAX_VALUE
        return c.size.width.toFloat() * c.size.height
    }

    companion object {
        const val ROLE_NONE = 0

        /** The outline is on this item. */
        const val ROLE_HOT = 1

        /** An outer item whose inner button holds the outline: it keeps a dim static outline. */
        const val ROLE_SECONDARY = 2
    }
}

@Composable
internal fun rememberXmbHighlightItem(): XmbHighlightItem = remember { XmbHighlightItem() }

/**
 * The gliding highlight of one window. Make it with [XmbHighlightHost] (a composable that also provides it), or with
 * [rememberXmbHighlightHost] + [xmbHighlightOverlay] + a [LocalXmbHighlight] provider for a window whose root you cannot wrap.
 */
@Stable
internal class XmbHighlightHost internal constructor(internal val view: View) {
    internal var layerCoords: LayoutCoordinates? = null
    internal var density: Density = Density(1f)
    internal var accent: State<Color>? = null

    // The items that are focused right now, in the order they claimed it (they are all on one focus path).
    private val focused = ArrayList<XmbHighlightItem>(4)
    private var hot by mutableStateOf<XmbHighlightItem?>(null)
    private var secondaryItem by mutableStateOf<XmbHighlightItem?>(null)

    /** Written when the hot item's position may have changed (it was laid out while hot); the flow re-measures on it. */
    private val tick = mutableIntStateOf(0)

    /** The last input was a touch: the outline is hidden until a key is pressed. */
    private var touchMode = false

    private val rect = Animatable(Rect.Zero, Rect.VectorConverter)
    private val alpha = Animatable(0f)
    private val corner = Animatable(0f)
    private val colorT = Animatable(1f)
    private var colorFrom = Color.Unspecified
    private var colorTo = Color.Unspecified
    private var secondaryRect by mutableStateOf<Rect?>(null)
    private var secondaryCorner by mutableFloatStateOf(0f)
    private var lastItem: XmbHighlightItem? = null

    internal fun bump() {
        tick.intValue = tick.intValue + 1
    }

    internal fun onLayerPositioned(c: LayoutCoordinates) {
        val first = layerCoords == null
        layerCoords = c
        if (first) bump()
    }

    internal fun claim(item: XmbHighlightItem) {
        focused.remove(item)
        focused.add(item)
        refresh()
    }

    internal fun release(item: XmbHighlightItem) {
        if (focused.remove(item)) refresh()
    }

    internal fun setTouch(touch: Boolean) {
        if (touchMode == touch) return
        touchMode = touch
        refresh()
    }

    private fun refresh() {
        var newHot: XmbHighlightItem? = null
        var newSecondary: XmbHighlightItem? = null
        val n = focused.size
        if (n > 0 && !touchMode) {
            val areas = FloatArray(n) { focused[it].area() }
            val h = focused[xmbPickHot(areas)]
            newHot = h
            val hotArea = h.area()
            for (i in n - 1 downTo 0) {
                val c = focused[i]
                if (c !== h && c.dimWhenInside && c.area() > hotArea) {
                    newSecondary = c
                    break
                }
            }
        }
        val prevHot = hot
        val prevSecondary = secondaryItem
        if (prevHot !== newHot) hot = newHot
        if (prevSecondary !== newSecondary) secondaryItem = newSecondary
        if (prevHot != null && prevHot !== newHot && prevHot !== newSecondary) prevHot.role = XmbHighlightItem.ROLE_NONE
        if (prevSecondary != null && prevSecondary !== newSecondary && prevSecondary !== newHot) prevSecondary.role = XmbHighlightItem.ROLE_NONE
        newHot?.role = XmbHighlightItem.ROLE_HOT
        newSecondary?.role = XmbHighlightItem.ROLE_SECONDARY
    }

    private fun boundsOf(item: XmbHighlightItem): Rect? {
        val layer = layerCoords ?: return null
        val c = item.coords ?: return null
        if (!layer.isAttached || !c.isAttached) return null
        // clipBounds: the part of the item that its scrolling parents really show, so the outline stops at a list's edge.
        val r = runCatching { layer.localBoundingBoxOf(c, clipBounds = true) }.getOrNull() ?: return null
        return if (r.width > 0f && r.height > 0f) r else null
    }

    private fun cornerOf(shape: Shape, r: Rect): Float = xmbShapeCornerPx(shape, r.width, r.height, density)

    private data class Sample(val item: XmbHighlightItem?, val rect: Rect?, val secondary: Rect?, val secondaryCorner: Float)

    private fun sample(): Sample {
        @Suppress("UNUSED_VARIABLE")
        val dependency = tick.intValue
        val h = hot ?: return Sample(null, null, null, 0f)
        val r = boundsOf(h)
        val s = secondaryItem
        val sr = if (s != null) boundsOf(s) else null
        return Sample(h, r, sr, if (s != null && sr != null) cornerOf(s.shape, sr) else 0f)
    }

    private fun currentColor(accent: Color): Color {
        val from = if (colorFrom.isSpecified) colorFrom else accent
        val to = if (colorTo.isSpecified) colorTo else accent
        return xmbMix(from, to, colorT.value)
    }

    /** Follows the hot item for as long as the window lives. Run it from a LaunchedEffect. */
    internal suspend fun run() {
        snapshotFlow { sample() }.collectLatest { handle(it) }
    }

    private suspend fun handle(s: Sample) {
        secondaryRect = s.secondary
        secondaryCorner = s.secondaryCorner
        val item = s.item
        val target = s.rect
        if (item == null || target == null) {
            // Nothing holds it (focus is between two items, or the item scrolled out): give the next claim a moment, then fade.
            delay(XMB_HL_HIDE_GRACE_MS)
            alpha.animateTo(0f, tween(XMB_HL_FADE_OUT_MS))
            return
        }
        coroutineScope {
            val wasHidden = alpha.value < 0.02f
            val changed = item !== lastItem
            val animating = rect.value != rect.targetValue
            lastItem = item
            val move = xmbMoveFor(wasHidden, changed, animating)
            val c = cornerOf(item.shape, target)
            if (changed && item.tint != colorTo) {
                val acc = accent?.value ?: Color.Unspecified
                if (acc.isSpecified) {
                    colorFrom = currentColor(acc)
                    colorTo = item.tint
                    launch {
                        colorT.snapTo(0f)
                        colorT.animateTo(1f, tween(XMB_HL_COLOR_MS))
                    }
                } else {
                    colorFrom = item.tint
                    colorTo = item.tint
                }
            }
            if (alpha.value < 1f) launch { alpha.animateTo(1f, tween(XMB_HL_FADE_IN_MS)) }
            if (move == XmbHighlightMove.SNAP) {
                rect.snapTo(target)
                corner.snapTo(c)
            } else {
                launch { corner.animateTo(c, tween(XMB_HL_COLOR_MS)) }
                rect.animateTo(
                    target,
                    spring<Rect>(
                        dampingRatio = XMB_HL_SPRING_DAMPING,
                        stiffness = XMB_HL_SPRING_STIFFNESS,
                        visibilityThreshold = Rect(0.5f, 0.5f, 0.5f, 0.5f),
                    ),
                )
            }
        }
    }

    /** Draws the outline into the host's box. Called from the overlay's draw phase, so every state read here only redraws. */
    internal fun draw(scope: DrawScope) {
        val a = alpha.value
        if (a < 0.01f) return
        val acc = accent?.value ?: return
        val color = currentColor(acc)
        with(scope) {
            val stroke = XMB_HL_STROKE_DP.dp.toPx()
            val dim = secondaryRect
            if (dim != null) strokeBox(dim, secondaryCorner, color.copy(alpha = XMB_HL_DIM_ALPHA * a), stroke)
            val r = rect.value
            if (r.width > stroke && r.height > stroke) {
                val cr = corner.value
                drawRoundRect(
                    color = color.copy(alpha = XMB_HL_FILL_ALPHA * a),
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(cr, cr),
                )
                strokeBox(r, cr, color.copy(alpha = a), stroke)
            }
        }
    }

    private fun DrawScope.strokeBox(r: Rect, cornerPx: Float, color: Color, stroke: Float) {
        val half = stroke / 2f
        if (r.width <= stroke || r.height <= stroke) return
        val cr = max(0f, cornerPx - half)
        drawRoundRect(
            color = color,
            topLeft = Offset(r.left + half, r.top + half),
            size = Size(r.width - stroke, r.height - stroke),
            cornerRadius = CornerRadius(cr, cr),
            style = Stroke(width = stroke),
        )
    }
}

@Composable
internal fun rememberXmbHighlightHost(): XmbHighlightHost {
    val view = LocalView.current
    return remember(view) { XmbHighlightHost(view) }
}

/**
 * Turns this element into the overlay of [host]: the outline is drawn over its content, and it listens (without consuming
 * anything) for touches and keys to hide and show the outline. For a window root you cannot wrap in [XmbHighlightHost]; the
 * caller also provides [LocalXmbHighlight] with [host] around the content.
 */
@Composable
internal fun Modifier.xmbHighlightOverlay(host: XmbHighlightHost): Modifier {
    val accent = rememberUpdatedState(MaterialTheme.colorScheme.primary)
    val density = LocalDensity.current
    host.accent = accent
    SideEffect { host.density = density }
    LaunchedEffect(host) { host.run() }
    return this
        .onGloballyPositioned { host.onLayerPositioned(it) }
        .onPreviewKeyEvent {
            host.setTouch(false)
            false
        }
        .pointerInput(host) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && (it.type == PointerType.Touch || it.type == PointerType.Stylus) }) host.setTouch(true)
                }
            }
        }
        .drawWithContent {
            drawContent()
            host.draw(this)
        }
}

/**
 * The highlight host of a window: wraps [content] in a Box that draws the one gliding outline over it and provides the host
 * to everything inside ([deckFocusRing], [steamFocusRing], [xmbHighlight] and [XmbGroupItem] then register with it). One per
 * window: the shell's root, each dialog ([DeckDialogScaled] does it), each store screen. [enabled] false (not the XMB
 * interface): just a Box, nothing provided, so Classic and plain Deck compose as before.
 */
@Composable
internal fun XmbHighlightHost(
    enabled: Boolean = rememberXmbHome(),
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!enabled) {
        Box(modifier, content = content)
        return
    }
    val host = rememberXmbHighlightHost()
    CompositionLocalProvider(LocalXmbHighlight provides host) {
        Box(modifier.xmbHighlightOverlay(host), content = content)
    }
}

/**
 * The host this composition should register with: the one above, if it belongs to this window. A popup, menu or sheet
 * that has no host of its own has a different view, whose coordinates cannot be compared with the host's, so its items
 * fall back to their own ring.
 */
@Composable
internal fun xmbHighlightHostOrNull(): XmbHighlightHost? {
    val host = LocalXmbHighlight.current ?: return null
    return if (host.view === LocalView.current) host else null
}

// ---------------------------------------------------------------------------------------------------------------------
// The item side.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Registers this element as a stop of the highlight. Put it BEFORE the clickable / focusable it observes (the same place as
 * [deckFocusRing]), after the modifiers that size and place the element so the outline gets its real bounds.
 *
 * With a host above it draws nothing itself: the host's outline moves here when the element holds focus. Without a host
 * (the XMB shell has not wrapped this screen yet, or a popup window) it falls back to a plain 2 dp accent border, so
 * focus is never invisible. [shape] gives the outline's corners; [tint] an outline colour other than the accent (null =
 * accent); [wholeSubtree] counts focus anywhere inside this element, not only on its own focus target; [dimWhenInside]
 * keeps a dim static outline here while an inner item holds the highlight (see [XmbGroupItem]); [onFocused] runs when it
 * gains focus. Hand the same [item] to [XmbInk] to colour the element's text and icons while it is hot.
 */
@Composable
internal fun Modifier.xmbHighlight(
    shape: Shape,
    item: XmbHighlightItem = rememberXmbHighlightItem(),
    tint: Color = Color.Unspecified,
    wholeSubtree: Boolean = false,
    dimWhenInside: Boolean = false,
    onFocused: (() -> Unit)? = null,
): Modifier {
    val host = xmbHighlightHostOrNull()
    val onFocusedNow by rememberUpdatedState(onFocused)
    item.shape = shape
    item.tint = tint
    item.dimWhenInside = dimWhenInside
    if (host == null) {
        val accent = MaterialTheme.colorScheme.primary
        var lit by remember { mutableStateOf(false) }
        return this
            .onFocusChanged { st ->
                val now = if (wholeSubtree) st.hasFocus else st.isFocused
                if (now && !lit) onFocusedNow?.invoke()
                lit = now
            }
            .then(if (lit) Modifier.border(XMB_HL_STROKE_DP.dp, accent, shape) else Modifier)
    }
    DisposableEffect(host, item) {
        onDispose {
            item.focused = false
            host.release(item)
        }
    }
    return this
        .onFocusChanged { st ->
            val now = if (wholeSubtree) st.hasFocus else st.isFocused
            if (now != item.focused) {
                item.focused = now
                if (now) {
                    host.claim(item)
                    onFocusedNow?.invoke()
                } else {
                    host.release(item)
                }
            }
        }
        .onGloballyPositioned { c ->
            item.coords = c
            // Only the item that holds the outline (or keeps the dim one) makes the host look again.
            if (item.focused) host.bump()
        }
}

/**
 * Colours the text and icons of one item while it holds the highlight (and a little less while it keeps the dim outline): the
 * theme's `onSurface`, `onSurfaceVariant`, `onBackground` and the ambient content colour move towards the accent, animated.
 * `primary` / `onPrimary` stay as they are, so a filled button inside keeps its look. It always puts the same theme wrapper
 * around [content], hot or not, so the state of what is inside never resets. Outside the XMB interface, or without an
 * [item], it is [content] and nothing else.
 */
@Composable
internal fun XmbInk(item: XmbHighlightItem? = LocalXmbHighlightItem.current, content: @Composable () -> Unit) {
    if (item == null || !rememberXmbHome()) {
        content()
        return
    }
    val cs = MaterialTheme.colorScheme
    val glass = rememberXmbGlass()
    val ink = remember(cs.primary, cs.onSurface, glass) { xmbInkColor(cs.primary, cs.onSurface, glass.panel) }
    val target = when (item.role) {
        XmbHighlightItem.ROLE_HOT -> 1f
        XmbHighlightItem.ROLE_SECONDARY -> 0.85f
        else -> 0f
    }
    val t by animateFloatAsState(target, tween(XMB_HL_COLOR_MS), label = "xmbInk")
    val scheme = if (t <= 0f) cs else cs.copy(
        onSurface = xmbMix(cs.onSurface, ink, t),
        onSurfaceVariant = xmbMix(cs.onSurfaceVariant, ink, t),
        onBackground = xmbMix(cs.onBackground, ink, t),
    )
    val contentColor = LocalContentColor.current
    val textStyle = LocalTextStyle.current
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        // MaterialTheme resets the ambient text style; put the one that was in effect back.
        CompositionLocalProvider(
            LocalContentColor provides xmbMix(contentColor, ink, t),
            LocalTextStyle provides textStyle,
        ) {
            content()
        }
    }
}
