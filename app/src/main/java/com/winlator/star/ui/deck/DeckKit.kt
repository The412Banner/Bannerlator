package com.winlator.star.ui.deck

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
    if (LocalConfiguration.current.screenWidthDp < DECK_COMPACT_WIDTH_DP) 16.dp else 24.dp

/** True below the phone breakpoint, where pages stack instead of splitting into columns. */
@Composable
internal fun deckCompact(): Boolean = LocalConfiguration.current.screenWidthDp < DECK_COMPACT_WIDTH_DP

/**
 * The landscape frame: main tabs and page tabs as side rails, one slim title line, no page headers.
 * Portrait keeps the top bar (and a phone its bottom bar).
 */
@Composable
internal fun deckRails(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.screenWidthDp >= DECK_COMPACT_WIDTH_DP && cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
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
                    .clip(RoundedCornerShape(16.dp))
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
    val cs = MaterialTheme.colorScheme
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (!deckCompact()) {
            DeckGlyph("L2", GlyphKind.BUMPER, Modifier.clip(RoundedCornerShape(9.dp)).clickable { currentOnSelect((selected - 1 + count) % count) })
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f, fill = false)
                .onFocusChanged { deckActions.stripFocused.value = it.hasFocus }
                .clip(RoundedCornerShape(18.dp))
                .background(deckCardFill())
                .border(1.dp, deckLine(), RoundedCornerShape(18.dp))
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                val sel = i == selected
                val shape = RoundedCornerShape(12.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .deckFocusRing(shape, scaleTo = 1f)
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
        if (!deckCompact()) {
            DeckGlyph("R2", GlyphKind.BUMPER, Modifier.clip(RoundedCornerShape(9.dp)).clickable { currentOnSelect((selected + 1) % count) })
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
            val shape = RoundedCornerShape(14.dp)
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
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .deckFocusRing(shape, scaleTo = 1.03f)
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
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.surfaceVariant)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

/** A rounded card that groups [DeckRow]s with hairlines between them. */
@Composable
internal fun DeckCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(deckCardFill())
            .border(1.dp, deckLine(), shape),
        content = content,
    )
}

/** Hairline between rows of a [DeckCard]. */
@Composable
internal fun DeckRowDivider() {
    HorizontalDivider(color = deckLine().copy(alpha = 0.5f), thickness = 1.dp)
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
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.background(cs.primary.copy(alpha = 0.12f)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
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
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        } else if (onClick != null) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
        }
    }
}

/** Big number over a small label ("41.5 h / Playtime"), with an optional progress bar. */
@Composable
internal fun DeckStat(value: String, label: String, modifier: Modifier = Modifier, progress: Float? = null) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(shape)
            .background(deckCardFill())
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
                    .clip(RoundedCornerShape(3.dp))
                    .background(cs.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .heightIn(min = 6.dp)
                        .background(cs.primary, RoundedCornerShape(3.dp)),
                )
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
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.8f))
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
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF3DDC84))
            .padding(horizontal = if (small) 8.dp else 12.dp, vertical = if (small) 3.dp else 6.dp),
    )
}
