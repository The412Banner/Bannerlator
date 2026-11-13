package com.winlator.star.ui.deck

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Shared building blocks for the Deck pages (game page, Stores, Components, Controls, Tools, onboarding).
// Everything reads MaterialTheme.colorScheme, so the user's preset and accent carry over.

/** Page side gutter: 16 dp on a phone, 24 dp in landscape. */
@Composable
internal fun deckGutter(): Dp =
    if (deckCompact()) 16.dp else 24.dp

/**
 * The phone layout: a portrait screen narrower than [DECK_COMPACT_WIDTH_DP], or a landscape one narrower than
 * [DECK_RAILS_MIN_WIDTH_DP]. A 1080p phone or handheld at the stock or a larger display size is 640-700 dp wide in
 * landscape, so landscape keeps the console layout well below the portrait breakpoint.
 */
internal fun isDeckCompact(cfg: Configuration): Boolean =
    cfg.screenWidthDp < if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) DECK_RAILS_MIN_WIDTH_DP else DECK_COMPACT_WIDTH_DP

/** True in the phone layout ([isDeckCompact]), where pages stack instead of splitting into columns. */
@Composable
internal fun deckCompact(): Boolean = isDeckCompact(LocalConfiguration.current)

/**
 * The landscape frame: main tabs and page tabs as side rails, one slim title line, no page headers.
 * Portrait keeps the top bar (and a phone its bottom bar).
 */
@Composable
internal fun deckRails(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE && !isDeckCompact(cfg)
}

// ---------------------------------------------------------------------------------------------------------------------
// XMB interface helpers: the gliding highlight's ink, two-level rows and the glass slices of a grouped card. Each one is the
// plain old thing outside the XMB interface, so Classic and the plain Deck compose exactly as before.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * A focusable element whose text and icons turn accent-coloured while the gliding outline is on it (XMB). [content] gets the
 * element's highlight [item] (hand it to [deckFocusRingInk]) and the colour scheme AS INKED: read colours from that `cs`, not
 * from one taken outside, or the ink never reaches them. Outside XMB it is just [content].
 */
@Composable
internal fun DeckInk(content: @Composable (item: XmbHighlightItem, cs: ColorScheme) -> Unit) {
    val item = rememberXmbHighlightItem()
    XmbInk(item) { content(item, MaterialTheme.colorScheme) }
}

/**
 * [deckFocusRing] for an element wrapped in [DeckInk]: under a highlight host it registers [item] as the stop (so the element's
 * text turns accent); without one it is the ordinary ring, as before.
 */
@Composable
internal fun Modifier.deckFocusRingInk(
    shape: Shape,
    item: XmbHighlightItem,
    scaleTo: Float = 1.05f,
    onFocused: (() -> Unit)? = null,
): Modifier =
    if (xmbHighlightHostOrNull() != null) this.xmbHighlight(shape, item, onFocused = onFocused)
    else this.deckFocusRing(shape, scaleTo, onFocused)

/**
 * The element is not a D-pad stop of its own inside an XMB item (the item is the stop); taps still work. Put it before the
 * element's clickable. Elsewhere the modifier unchanged.
 */
@Composable
internal fun Modifier.xmbSkipFocus(): Modifier =
    if (LocalXmbGroup.current != null) this.focusProperties { canFocus = false } else this

/**
 * Which of a row of choices (pills, chips) takes the focus when A enters the row: the selected one if it can take focus, else
 * the first that can. -1 when none can. [selected] and [focusable] are per choice, same length.
 */
internal fun xmbLandingIndex(selected: List<Boolean>, focusable: List<Boolean>): Int {
    for (i in selected.indices) if (selected[i] && focusable.getOrElse(i) { false }) return i
    for (i in focusable.indices) if (focusable[i]) return i
    return -1
}

/** A slider's value one key press to the left (-1) or right (+1): [step] further, kept to [min]..[max]. */
internal fun xmbSliderNudge(value: Float, direction: Int, step: Float, min: Float, max: Float): Float =
    (value + direction * step).coerceIn(min, max)

/** How far past a slice the shadow layers are stretched where the neighbouring slice carries them on (the clip cuts them off). */
internal const val XMB_SLICE_OVERSHOOT_DP = 64f

/**
 * The bounds of one shadow layer of a slice in a w x h box, as left, top, right, bottom: [spread] bigger than the slice at the
 * sides, and at the top / bottom only when this slice is the [first] / [last] of its card (else stretched by [overshoot] so
 * the rounded corners of the layer fall outside the clip and the sides run on into the neighbour). [dy] drops the shadow.
 */
internal fun xmbSliceLayerBounds(w: Float, h: Float, spread: Float, dy: Float, first: Boolean, last: Boolean, overshoot: Float): FloatArray =
    floatArrayOf(
        -spread,
        if (first) dy - spread else -overshoot,
        w + spread,
        if (last) h + spread + dy else h + overshoot,
    )

/** The part of the surroundings the slice's shadow may paint: the sides always, the top and bottom only at the card's ends. */
internal fun xmbSliceClip(w: Float, h: Float, first: Boolean, last: Boolean, reach: Float): FloatArray =
    floatArrayOf(-reach, if (first) -reach else 0f, w + reach, if (last) h + reach else h)

/** The card shadow of one slice (put it BEFORE the clip), continuous with its neighbours'. The card's corner radius is 18 dp. */
private fun xmbSliceShadow(shape: Shape, first: Boolean, last: Boolean): Modifier = Modifier.drawWithCache {
    val cutOut = shapePath(shape)
    val corner = 18.dp.toPx()
    val overshoot = XMB_SLICE_OVERSHOOT_DP.dp.toPx()
    val reach = XMB_SLICE_OVERSHOOT_DP.dp.toPx()
    onDrawBehind {
        val c = xmbSliceClip(size.width, size.height, first, last, reach)
        clipPath(cutOut, ClipOp.Difference) {
            clipRect(c[0], c[1], c[2], c[3]) {
                for (layer in XMB_SHADOW_LAYERS) {
                    val spread = layer.spreadDp.dp.toPx()
                    val b = xmbSliceLayerBounds(size.width, size.height, spread, layer.dyDp.dp.toPx(), first, last, overshoot)
                    drawRoundRect(
                        color = Color.Black.copy(alpha = layer.alpha),
                        topLeft = Offset(b[0], b[1]),
                        size = Size(b[2] - b[0], b[3] - b[1]),
                        cornerRadius = CornerRadius(corner + spread),
                    )
                }
            }
        }
    }
}

/** The edge light of a card's first slice: a 1 dp line along its edge, brightest at the top, gone by the slice's bottom. */
private fun xmbSliceHairline(shape: Shape): Modifier = Modifier.drawWithCache {
    val edge = shapePath(shape)
    val brush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = XMB_HAIRLINE_TOP_ALPHA), Color.White.copy(alpha = 0f)),
        startY = 0f,
        endY = max(1f, size.height),
    )
    // Stroked 2 dp on the edge; the clip keeps the inner 1 dp.
    val stroke = Stroke(width = 2.dp.toPx())
    onDrawBehind { drawPath(edge, brush, style = stroke) }
}

/**
 * One slice of a card made of stacked rows (the settings pages' group cards: each row is its own lazy item). Pops like
 * [xmbGlassCard] on the glass pages: a shadow round the whole card (every slice paints its own part and they join up), the sheen
 * and an edge light on the [first] slice. Off the glass pages it is `clip + background`,
 * as the slices were.
 */
@Composable
internal fun Modifier.xmbGlassSlice(shape: Shape, first: Boolean, last: Boolean, fill: Color = deckCardFill()): Modifier {
    if (xmbPageFrost() == null) return this.clip(shape).background(fill)
    val shadow = remember(shape, first, last) { xmbSliceShadow(shape, first, last) }
    val hairline = remember(shape) { xmbSliceHairline(shape) }
    return this
        .then(shadow)
        .clip(shape)
        .background(fill)
        .then(if (first) Modifier.xmbSheen() else Modifier)
        .then(if (first) hairline else Modifier)
}

/**
 * Hands the page's title and description to the shell's slim title line (landscape only; elsewhere
 * the page draws its own header). The last page to register wins, and a page clears only its own.
 */
@Composable
internal fun DeckPageTitle(title: String, description: String?) {
    if (!deckRails()) return
    val actions = LocalDeckActions.current
    val info = remember(title, description) { DeckPageInfo(title, description) }
    DisposableEffect(actions, info) {
        actions.pageInfo.value = info
        onDispose { if (actions.pageInfo.value === info) actions.pageInfo.value = null }
    }
}

/**
 * Big page header: an accent icon tile, the title and a one-line description, with optional
 * buttons underneath (Search / Refresh / Settings on Components, for example).
 */
@Composable
internal fun DeckPageHeader(
    icon: ImageVector,
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    // Landscape: the shell's title line shows the title, with the description behind its info button,
    // and the screen's top-bar actions on the right.
    if (deckRails()) {
        DeckPageTitle(title, description)
        return
    }
    val cs = MaterialTheme.colorScheme
    val compact = deckCompact()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(if (compact) 44.dp else 56.dp)
                    .clip(deckShape(16.dp))
                    .background(cs.primary.copy(alpha = 0.16f)),
            ) {
                Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(if (compact) 24.dp else 30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = cs.onSurface,
                    fontFamily = SoraFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 24.sp else 30.sp,
                    lineHeight = if (compact) 28.sp else 34.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description != null) {
                    Text(
                        text = description,
                        color = cs.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.widthIn(max = 720.dp),
                    )
                }
            }
        }
        if (actions != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                // Padding inside the scroll keeps the focus glow from being clipped.
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
                content = actions,
            )
        }
    }
}

/** Small uppercase section label ("GAME MENU", "ABOUT THIS GAME", ...). */
@Composable
internal fun DeckSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = NunitoSansFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp,
        letterSpacing = 1.6.sp,
        modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp),
    )
}

/**
 * A page's second-level tab strip (Library / Store / Friends..., Browse / Installed...), with L2 and R2
 * glyphs either side. It registers itself with the shell so the controller's L2/R2 move between these
 * tabs while the page is up (L1/R1 always move the top-level tabs).
 * In landscape the shell draws the tabs as a rail beside the content instead, icon-only when
 * [compactRail] is set or the hosted screen brings its own side list; nothing is drawn here then.
 */
@Composable
internal fun DeckTabStrip(
    tabs: List<DeckTabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    compactRail: Boolean = false,
) {
    val deckActions = LocalDeckActions.current
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val count = tabs.size
    DisposableEffect(deckActions, count) {
        val cycle: (Int) -> Unit = { delta -> if (count > 0) currentOnSelect((currentSelected + delta + count) % count) }
        deckActions.subTab.value = cycle
        deckActions.subTabLabel.value = label
        onDispose {
            if (deckActions.subTab.value === cycle) deckActions.subTab.value = null
            deckActions.stripFocused.value = false
        }
    }
    if (deckRails()) {
        val rail = remember { DeckPageTabs() }
        SideEffect {
            rail.tabs = tabs
            rail.selected = selected
            rail.compact = compactRail
            rail.onSelect = onSelect
        }
        DisposableEffect(deckActions, rail) {
            deckActions.pageTabs.value = rail
            onDispose { if (deckActions.pageTabs.value === rail) deckActions.pageTabs.value = null }
        }
        return
    }
    // XMB mode: no L2 / R2 glyphs either side (the triggers still switch the tabs).
    val bumperGlyphs = !deckCompact() && !rememberXmbHome()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (bumperGlyphs) {
            DeckGlyph("L2", GlyphKind.BUMPER, Modifier.clip(deckShape(9.dp)).clickable { currentOnSelect((selected - 1 + count) % count) })
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f, fill = false)
                .onFocusChanged { deckActions.stripFocused.value = it.hasFocus }
                .xmbGlassCard(RoundedCornerShape(18.dp))
                .border(1.dp, deckLine(), deckShape(18.dp))
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                val sel = i == selected
                val shape = deckShape(12.dp)
                DeckInk { item, cs ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .deckFocusRingInk(shape, item, scaleTo = 1f)
                            .clip(shape)
                            .background(if (sel) cs.primary else Color.Transparent)
                            .clickable { if (!sel) onSelect(i) }
                            .heightIn(min = 40.dp)
                            .padding(horizontal = 14.dp),
                    ) {
                        if (t.icon != null) {
                            Icon(t.icon, contentDescription = null, tint = if (sel) cs.onPrimary else cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            text = t.label,
                            color = if (sel) cs.onPrimary else cs.onSurfaceVariant,
                            fontFamily = SoraFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                        )
                        if (t.badge != null && t.badge > 0) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(if (sel) cs.onPrimary else cs.primary, CircleShape),
                            ) {
                                Text(
                                    "${t.badge}",
                                    color = if (sel) cs.primary else cs.onPrimary,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (bumperGlyphs) {
            DeckGlyph("R2", GlyphKind.BUMPER, Modifier.clip(deckShape(9.dp)).clickable { currentOnSelect((selected + 1) % count) })
        }
    }
}

/** One entry of a [DeckTabStrip]. [badge] shows a count bubble when above zero. */
internal data class DeckTabItem(val label: String, val icon: ImageVector? = null, val badge: Int? = null)

/** A category rail entry: grouped under [group], with an optional count on the right. */
internal data class DeckRailItem(val key: String, val label: String, val icon: ImageVector, val group: String? = null, val count: Int? = null)

/**
 * The left-hand category rail used by Components and Tools. On a phone it turns into a horizontal
 * row of chips so the content keeps the full width.
 */
@Composable
internal fun DeckCategoryRail(
    items: List<DeckRailItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    if (deckCompact()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp),
        ) {
            items.forEach { item ->
                DeckChoiceChip(item.label, item.key == selectedKey, icon = item.icon) { onSelect(item.key) }
            }
        }
        return
    }
    Column(modifier = modifier) {
        var lastGroup: String? = null
        items.forEach { item ->
            if (item.group != null && item.group != lastGroup) {
                lastGroup = item.group
                DeckSectionLabel(item.group, Modifier.padding(start = 12.dp, top = 10.dp))
            }
            val sel = item.key == selectedKey
            val shape = deckShape(14.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .deckFocusRing(shape, scaleTo = 1f)
                    .clip(shape)
                    .background(if (sel) cs.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(item.key) }
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Icon(item.icon, contentDescription = null, tint = if (sel) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(
                    text = item.label,
                    color = if (sel) cs.onSurface else cs.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.count != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(26.dp).background(cs.surfaceVariant, CircleShape),
                    ) {
                        Text("${item.count}", color = cs.onSurfaceVariant, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

/** A selectable pill (store switcher, view filters, onboarding choices). */
@Composable
internal fun DeckChoiceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dot: Color? = null,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val shape = deckShape(14.dp)
    DeckInk { item, cs ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier
                .deckFocusRingInk(shape, item, scaleTo = 1.03f)
                .clip(shape)
                .background(if (selected) cs.primary.copy(alpha = 0.18f) else deckCardFill())
                .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else deckLine(), shape)
                .clickable(onClick = onClick)
                .heightIn(min = 42.dp)
                .padding(horizontal = 14.dp),
        ) {
            if (dot != null) Box(Modifier.size(10.dp).background(dot, CircleShape))
            if (icon != null) Icon(icon, contentDescription = null, tint = if (selected) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.5.sp,
                maxLines = 1,
            )
            if (trailing != null) {
                Text(
                    text = trailing.uppercase(),
                    color = cs.onSurfaceVariant,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.5.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .clip(deckShape(8.dp))
                        .background(cs.surfaceVariant)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** A rounded card that groups [DeckRow]s with hairlines between them. */
@Composable
internal fun DeckCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = deckShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Simple style: lifted off the wallpaper with a soft shadow.
            .then(if (com.winlator.star.ui.theme.AppThemeState.simpleLook) Modifier.shadow(10.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .xmbGlassCard(shape)
            .border(1.dp, deckLine(), shape),
        content = content,
    )
}

/** Hairline between rows of a [DeckCard]. */
@Composable
internal fun DeckRowDivider() {
    HorizontalDivider(color = deckLineAt(0.5f), thickness = 1.dp)
}

/**
 * One row of a [DeckCard]: title, optional subtitle and leading icon, and either a [trailing] slot
 * (a button, a switch) or a chevron when the whole row is clickable.
 */
@Composable
internal fun DeckRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    if (rememberXmbHome()) {
        // XMB: the gliding outline marks the focused row (a faint fill and accent text, no flood fill). The row is inset a
        // little so the outline sits inside the rounded corners of its card.
        val shape = deckShape(12.dp)
        val inset = modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
        when {
            // A clickable row is one stop; a button in its trailing slot (the same action) is tap-only.
            onClick != null -> DeckInk { item, cs ->
                DeckRowBody(
                    cs, title, subtitle, icon, danger, focused = false, horizontal = 14.dp, trailing = trailing,
                    trailingModifier = Modifier.focusProperties { canFocus = false },
                    modifier = inset.deckFocusRingInk(shape, item, scaleTo = 1f).clip(shape).clickable(onClick = onClick),
                )
            }
            // A row with buttons is one stop too; A enters it and the buttons take the focus.
            trailing != null -> XmbGroupItem(rememberXmbFocusGroup(), shape, inset) {
                DeckRowBody(
                    MaterialTheme.colorScheme, title, subtitle, icon, danger, focused = false, horizontal = 14.dp, trailing = trailing,
                    // Everything in the trailing slot is an inner button: reached after A, which lands on the first of them.
                    trailingModifier = Modifier.xmbInner(first = true),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> DeckRowBody(
                MaterialTheme.colorScheme, title, subtitle, icon, danger, focused = false, horizontal = 20.dp, trailing = null,
                trailingModifier = Modifier, modifier = modifier.fillMaxWidth(), chevron = false,
            )
        }
        return
    }
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    DeckRowBody(
        cs, title, subtitle, icon, danger, focused, horizontal = 20.dp, trailing = trailing, trailingModifier = Modifier,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.background(cs.primary.copy(alpha = 0.12f)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        chevron = onClick != null,
    )
}

/** The content of a [DeckRow]; [modifier] carries whatever makes the row focusable, this adds the size and padding. */
@Composable
private fun DeckRowBody(
    cs: ColorScheme,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    danger: Boolean,
    focused: Boolean,
    horizontal: Dp,
    trailing: (@Composable RowScope.() -> Unit)?,
    trailingModifier: Modifier,
    modifier: Modifier,
    chevron: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 64.dp)
            .padding(horizontal = horizontal, vertical = 14.dp),
    ) {
        if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(deckShape(12.dp))
                    .background((if (danger) cs.error else cs.primary).copy(alpha = 0.14f)),
            ) {
                Icon(icon, contentDescription = null, tint = if (danger) cs.error else cs.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                color = if (danger) cs.error else if (focused) cs.primary else cs.onSurface,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            if (subtitle != null) {
                Text(subtitle, color = cs.onSurfaceVariant, fontSize = 13.5.sp, lineHeight = 18.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = trailingModifier, content = trailing)
        } else if (chevron) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
        }
    }
}

/** Big number over a small label ("41.5 h / Playtime"), with an optional progress bar. */
@Composable
internal fun DeckStat(value: String, label: String, modifier: Modifier = Modifier, progress: Float? = null) {
    val cs = MaterialTheme.colorScheme
    val shape = deckShape(18.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .xmbGlassCard(shape)
            .border(1.dp, deckLine(), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(value, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
        if (progress != null) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .heightIn(min = 6.dp)
                    .clip(deckShape(3.dp))
                    .background(cs.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .heightIn(min = 6.dp)
                        .background(cs.primary, deckShape(3.dp)),
                )
            }
        }
    }
}

/** The portrait form of a row of [DeckStat]s: one slim strip of equal cells (value over label), split by hairlines. */
@Composable
internal fun DeckStatStrip(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = deckShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .xmbGlassCard(shape)
            .border(1.dp, deckLine(), shape),
    ) {
        stats.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(deckLine()))
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Text(value, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(label, color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A small grey pill used for facts ("DX9", "SteamLite", "12.4 GB"). */
@Composable
internal fun DeckPill(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        color = cs.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = modifier
            .clip(deckShape(14.dp))
            .background(deckInset(0.8f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The green ANDROID pill: an Android game in the list, which opens as its own app rather than in Wine. [small] sits on a cover. */
@Composable
internal fun DeckAndroidPill(modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text = "ANDROID",
        color = Color(0xFF062B17),
        fontWeight = FontWeight.ExtraBold,
        fontSize = if (small) 10.sp else 13.sp,
        letterSpacing = 0.6.sp,
        maxLines = 1,
        modifier = modifier
            .clip(deckShape(14.dp))
            .background(Color(0xFF3DDC84))
            .padding(horizontal = if (small) 8.dp else 12.dp, vertical = if (small) 3.dp else 6.dp),
    )
}
