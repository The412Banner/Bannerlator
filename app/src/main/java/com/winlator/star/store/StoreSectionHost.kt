package com.winlator.star.store

import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import com.winlator.star.ui.simple.SimpleRowDivider
import com.winlator.star.ui.simple.labPanel
import com.winlator.star.ui.simple.SimpleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.simple.labTile
import com.winlator.star.ui.deck.rememberScreenScale
import com.winlator.star.ui.deck.rememberXmbRailState
import com.winlator.star.ui.deck.xmbRailGroup
import com.winlator.star.ui.theme.LocalAccentDim

/**
 * The store-AGNOSTIC section shell: the Steam storefront's four-tab host
 * ([SteamStorefrontHost]) re-cut so GOG and Epic get the same chrome without touching the Steam
 * code path.
 *
 * - **Portrait / compact** → a title row (store name + status slot + action icons) over a top
 *   [TabRow], content beneath.
 * - **Landscape at Medium width or wider** → a left [androidx.compose.material3.NavigationRail]
 *   of EXACT [RAIL_WIDTH] (see [SteamMainActivity]'s note on why a bounded Column and not a bare
 *   NavigationRail — a `fillMaxWidth()` descendant would otherwise inflate it to the whole screen),
 *   scrolling as a whole, with the secondary actions in its tail.
 * - The rail is one focus group whose D-pad RIGHT lands in the content pane.
 * - Hardware back returns to the first tab before it leaves the section.
 *
 * The host owns nothing store-specific: tabs, counts and every action come from the caller.
 */
data class StoreSectionTab(val key: String, val label: String, val icon: ImageVector)

@Composable
fun StoreSectionHost(
    storeName: String,
    tabs: List<StoreSectionTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    counts: Map<String, Int> = emptyMap(),
    /** Portrait: drawn after the store name in the title row (a signed-in chip, a status pill). */
    statusSlot: @Composable () -> Unit = {},
    /** Landscape: drawn at the top of the rail, above the tabs. */
    railStatusSlot: @Composable ColumnScope.() -> Unit = {},
    /** Portrait: the right-aligned action icons. */
    actions: @Composable RowScope.() -> Unit = {},
    /** Landscape: the rail tail (two or three icons plus an overflow). */
    railActions: @Composable ColumnScope.() -> Unit = {},
    /** Transient themed message, drawn LAST so it floats over the tab content. */
    message: String? = null,
    onMessageTimeout: () -> Unit = {},
    content: @Composable (wide: Boolean, modifier: Modifier) -> Unit,
) {
    val layout = rememberStorefrontLayout()
    val contentFocus = remember { FocusRequester() }
    // XMB home: StoreXmbScreen already lays the screen out edge to edge in its frame, with the bars hidden.
    val xmb = LocalStoreXmb.current

    BackHandler(enabled = true) {
        if (selected == 0) onClose() else onSelect(0)
    }

    // XMB home: L2 / R2 step through the rail's tabs and then its end actions (wrapping) from anywhere on the screen.
    val railStops = rememberXmbRailCycle(xmb && tabs.isNotEmpty(), tabs.size, selected, contentFocus, onSelect)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (xmb) Modifier else Modifier.systemBarsPadding().displayCutoutPadding()),
    ) {
        if (layout.wide) {
            Column(Modifier.fillMaxSize()) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalXmbRailStops provides if (xmb) railStops else null) {
                    SectionRail(
                        storeName = storeName,
                        tabs = tabs,
                        selected = selected,
                        counts = counts,
                        onSelect = onSelect,
                        contentFocus = contentFocus,
                        statusSlot = railStatusSlot,
                        actions = railActions,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // Requester BEFORE the focus group — the rail's `right = contentFocus`
                        // resolves against the group that follows it. Order is load-bearing.
                        .focusRequester(contentFocus)
                        .focusGroup(),
                ) { content(true, Modifier.fillMaxSize()) }
            }
            StoreLegend()
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StoreSwitcher(storeName)
                    Spacer(Modifier.width(8.dp))
                    statusSlot()
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                        actions()
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .focusRequester(contentFocus)
                        .focusGroup(),
                ) { content(false, Modifier.fillMaxSize()) }
                SectionTabRow(tabs = tabs, selected = selected, counts = counts, onSelect = onSelect)
            }
        }

        message?.let { StoreMessageBar(it, onMessageTimeout) }
    }
}

/** Fixed rail width — the store name and the tab names as text rows. */
private val RAIL_WIDTH = 124.dp

/** Portrait: the tabs along the bottom edge, under the thumb. The selected one carries the accent line. */
@Composable
private fun SectionTabRow(
    tabs: List<StoreSectionTab>,
    selected: Int,
    counts: Map<String, Int>,
    onSelect: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = cs.outline.copy(alpha = 0.3f))
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, t ->
                val on = selected == index
                val n = counts[t.key]?.takeIf { it > 0 }
                Column(
                    modifier = Modifier.weight(1f).labTile(onClick = { onSelect(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) cs.primary else androidx.compose.ui.graphics.Color.Transparent))
                    Text(
                        text = if (n != null) "${t.label} $n" else t.label,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) cs.onBackground else cs.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                    )
                }
            }
        }
    }
}

/** Landscape: the store name, then the tabs as rows; the selected one has the accent line on its left. */
@Composable
private fun SectionRail(
    storeName: String,
    tabs: List<StoreSectionTab>,
    selected: Int,
    counts: Map<String, Int>,
    onSelect: (Int) -> Unit,
    contentFocus: FocusRequester,
    statusSlot: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            // EXACT width. Do not replace with widthIn/wrapContentWidth.
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(cs.onBackground.copy(alpha = 0.04f))
            .verticalScroll(rememberScrollState())
            .focusGroup()
            .focusProperties { right = contentFocus }
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        StoreSwitcher(storeName)
        statusSlot()
        Spacer(Modifier.height(6.dp))

        tabs.forEachIndexed { index, t ->
            val on = selected == index
            val n = counts[t.key]?.takeIf { it > 0 }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (on) cs.onBackground.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
                    .labTile(onClick = { onSelect(index) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(2.dp).height(30.dp).background(if (on) cs.primary else androidx.compose.ui.graphics.Color.Transparent))
                Text(
                    text = if (n != null) "${t.label} $n" else t.label,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) cs.onBackground else cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.width(RAIL_WIDTH - 24.dp), thickness = 1.dp, color = cs.outline.copy(alpha = 0.4f))
        Spacer(Modifier.height(4.dp))
        CompositionLocalProvider(LocalStoreRail provides true) { actions() }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The store's name, which opens the list of stores: picking one opens that store in place of this one.
 */
@Composable
internal fun StoreSwitcher(storeName: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val stores = listOf(
        "Steam" to SteamMainActivity::class.java,
        "GOG" to GogMainActivity::class.java,
        "Epic Games" to EpicMainActivity::class.java,
        "Amazon Games" to AmazonMainActivity::class.java,
        "Xbox" to com.winlator.star.store.xbox.XboxMainActivity::class.java,
    )
    Box(modifier) {
        Row(Modifier.labTile(onClick = { open = true }).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(storeName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(" ▾", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // The store's own dark panel with the thin outline, and a line between the stores.
            modifier = Modifier.labPanel(),
        ) {
            stores.forEachIndexed { i, (label, cls) ->
                if (i > 0) SimpleRowDivider()
                val here = (ctx as? android.app.Activity)?.javaClass == cls
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, fontSize = 14.sp, fontWeight = if (here) FontWeight.Bold else FontWeight.Normal, color = if (here) cs.primary else cs.onSurface) },
                    onClick = {
                        open = false
                        if (!here) {
                            runCatching { ctx.startActivity(android.content.Intent(ctx, cls)) }
                            (ctx as? android.app.Activity)?.finish()
                        }
                    },
                )
            }
        }
    }
}

/** Landscape: what the controller's buttons do here, along the bottom edge. */
@Composable
internal fun StoreLegend(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().background(cs.onBackground.copy(alpha = 0.04f)).padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("A" to "Open", "B" to "Back").forEach { (k, v) ->
            Text(k, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cs.onBackground,
                modifier = Modifier.padding(start = 14.dp).border(1.dp, cs.onSurfaceVariant, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 6.dp))
            Text(v, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 5.dp))
        }
    }
}

/** True inside a store's landscape rail: its icon buttons stretch to the rail's width. */
internal val LocalStoreRail = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A store screen's icon button: a small glass tile with the thin Lab outline and the icon in ink, the same in the top
 * row and in the rail. Takes the place of the stock round icon button, so it is called the same way.
 */
@Composable
internal fun StoreGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Draw whatever [content] paints in the ink colour (off for content with a badge of its own colour). */
    ink: Boolean = true,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val inkColor = if (enabled) cs.onBackground else cs.onBackground.copy(alpha = 0.4f)
    // In the landscape rail a button spans the rail, centred; elsewhere it is a square tile.
    val inRail = LocalStoreRail.current
    Box(
        modifier = modifier
            .padding(3.dp)
            .then(if (inRail) Modifier.fillMaxWidth().height(34.dp) else Modifier.size(34.dp))
            .clip(SimpleShape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(cs.onBackground.copy(alpha = 0.10f), cs.onBackground.copy(alpha = 0.04f))))
            .border(1.dp, cs.onBackground.copy(alpha = 0.16f), SimpleShape)
            .then(if (enabled) Modifier.labTile(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .then(
                    if (!ink) Modifier else Modifier
                        .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(inkColor, blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn)
                        },
                ),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
