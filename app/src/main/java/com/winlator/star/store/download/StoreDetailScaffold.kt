package com.winlator.star.store.download

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.deck.xmbSolidBackground
import com.winlator.star.ui.screens.MenuItemDivider
import com.winlator.star.ui.screens.outlinedMenuCard
import com.winlator.star.store.SteamStoreSearch
import com.winlator.star.ui.simple.LabArt
import com.winlator.star.ui.simple.LabPicture
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.labSgdbHero
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.winlator.star.ui.simple.labTile

/**
 * The game details page every store shares: the game's art fills the screen behind the page, and the page sits on it.
 *
 * Portrait (touch): back row, the art, then the name, ONE primary button (or its progress fill while downloading) +
 * gear, the download line, the tab chips and the selected tab's body, all in one scroll.
 * Landscape (controller): the name and buttons in a left column, the tabs and body in a panel on the right.
 *
 * Every button and tab is a Lab tile, so the D-pad moves between them and A presses them. Colours come from the app
 * theme (accent, background), so the page looks the same in every interface.
 */

/** One entry in the ⚙ gear dropdown. */
data class StoreGearItem(
    val emoji: String,
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** What the big primary button shows right now. */
sealed interface StorePrimaryAction {
    /** An actionable accent button (Install / Launch / Get for free …). */
    data class Button(val label: String, val onClick: () -> Unit, val enabled: Boolean = true) : StorePrimaryAction

    /**
     * Read-only download state: the SINGLE progress indicator. [fraction] is the solid front fill
     * (bytes on disk); [backFraction], when larger, is the lighter fill behind it (bytes fetched).
     * Pause / cancel live in the gear.
     */
    data class Progress(val label: String, val fraction: Float, val backFraction: Float = 0f) : StorePrimaryAction
}

@Composable
fun StoreDetailScaffold(
    onBack: () -> Unit,
    title: String,
    hero: @Composable BoxScope.() -> Unit,
    primary: StorePrimaryAction,
    gear: List<StoreGearItem>,
    tabs: List<String>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
    storeBadge: @Composable () -> Unit = {},
    headerActions: @Composable RowScope.() -> Unit = { DownloadsButton() },
    /** Small line(s) between the name and the button — install status, exe name. */
    subtitle: (@Composable ColumnScope.() -> Unit)? = null,
    /** Line under the primary button (download rate / ETA). */
    infoLine: String? = null,
    tabBadges: Map<Int, String> = emptyMap(),
    /** Extra floating content (dialogs, result bar) drawn over the page. */
    overlay: @Composable BoxScope.() -> Unit = {},
    /** A second, outlined button beside the primary one (Xbox: Stream next to Install). */
    secondary: StorePrimaryAction.Button? = null,
    /** This game's key in [DownloadRegistry] ("EPIC:<id>"): while it downloads, the page shows its speed and time left. */
    downloadKey: String? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val bg = xmbSolidBackground()
    val cs = MaterialTheme.colorScheme
    val all by DownloadRegistry.entries.collectAsState()
    val download = if (downloadKey == null) null else all.firstOrNull { it.key == downloadKey && it.isActive }

    // The same page as Steam's: one column over the art (the left 60 % of the screen in landscape), no boxes.
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(bg)) {
        val pageH = maxHeight
        val wide = maxWidth > maxHeight
        // Under the store's own art: the Steam / SteamGridDB art the Lab finds by the game's name, for a store with none.
        StoreFallbackArt(title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = hero)
        Box(
            Modifier.fillMaxSize().background(
                if (wide) Brush.horizontalGradient(0f to bg.copy(alpha = 0.95f), 0.5f to bg.copy(alpha = 0.82f), 0.8f to bg.copy(alpha = 0.3f), 1f to bg.copy(alpha = 0.45f))
                else Brush.verticalGradient(0f to bg.copy(alpha = 0.25f), 0.30f to bg.copy(alpha = 0.35f), 0.55f to bg.copy(alpha = 0.93f), 0.75f to bg),
            ),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (wide) 0.6f else 1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).labTile(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onBackground)
                }
                // Landscape: the badge and Downloads sit beside Back at the far left; portrait keeps them on the right.
                if (!wide) Spacer(Modifier.weight(1f))
                storeBadge()
                headerActions()
            }
            Spacer(Modifier.height(pageH * if (wide) 0.04f else 0.28f))
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall, color = cs.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) subtitle()
            }
            StorePrimaryActionRow(primary = primary, gear = gear, secondary = secondary)
            if (download != null) StoreDownloadBar(download, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            if (!infoLine.isNullOrBlank()) {
                Text(infoLine, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            }
            if (tabs.size > 1) StoreDetailTabs(labels = tabs, selected = selectedTab, badges = tabBadges, onSelect = onSelectTab)
            body()
        }
        overlay()
    }
}

private val fallbackArt = java.util.concurrent.ConcurrentHashMap<String, List<LabArt.Pic>>()

/** Full-screen art found by the game's name: Steam's library hero, SteamGridDB's hero, then Steam's header. */
@Composable
private fun StoreFallbackArt(title: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current.applicationContext
    val pics by produceState(fallbackArt[title] ?: emptyList(), title) {
        if (title.isBlank() || fallbackArt.containsKey(title)) return@produceState
        value = withContext(Dispatchers.IO) {
            val steam = runCatching { SteamStoreSearch.findAppIdByName(ctx, title) }.getOrNull()
            buildList {
                if (steam != null) add(LabArt.Pic("https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/$steam/library_hero.jpg"))
                runCatching { labSgdbHero(ctx, steam, title) }.getOrNull()?.let { add(LabArt.Pic(it)) }
                if (steam != null) add(LabArt.Pic("https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/$steam/header.jpg"))
            }
        }.also { fallbackArt[title] = it }
    }
    if (pics.isNotEmpty()) LabPicture(pics, modifier)
}

/**
 * A download as every store shows it: a thin bar, then "37% · 24.6 MB/s · ~12 min left" with the bytes done on the right.
 * The parts the downloader has not reported are left out.
 */
@Composable
fun StoreDownloadBar(entry: DownloadEntry, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val done = if (entry.installTotal > 0L) entry.installDone else entry.downloadDone
    val total = if (entry.installTotal > 0L) entry.installTotal else entry.downloadTotal
    val frac = (if (total > 0L) done.toFloat() / total else entry.pct / 100f).coerceIn(0f, 1f)
    val status = when (entry.state) {
        DownloadState.QUEUED -> "Queued"
        DownloadState.PAUSED -> "Paused · ${entry.pct}%"
        else -> listOf("${entry.pct}%", formatDownloadSpeed(entry.speedBps), formatEta(entry.etaSeconds)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(SimpleShape).background(cs.onBackground.copy(alpha = 0.14f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(cs.primary))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(status, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground, maxLines = 1, modifier = Modifier.weight(1f))
            if (total > 0L) {
                Text("${formatDownloadSize(done)} / ${formatDownloadSize(total)}", fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * The primary button + gear row. The button is a 40dp accent block (or the two-layer progress fill
 * while downloading); the gear is a 40dp outlined square opening the app's outlined dropdown card.
 */
@Composable
fun StorePrimaryActionRow(
    primary: StorePrimaryAction,
    gear: List<StoreGearItem>,
    modifier: Modifier = Modifier,
    secondary: StorePrimaryAction.Button? = null,
) {
    var gearExpanded by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val cs = MaterialTheme.colorScheme
    val bg = xmbSolidBackground()
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (primary) {
            is StorePrimaryAction.Progress -> {
                val front = primary.fraction.coerceIn(0f, 1f)
                val back = primary.backFraction.coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(shape)
                        .background(bg.copy(alpha = 0.6f))
                        .border(1.dp, cs.outline.copy(alpha = 0.5f), shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (back > front) {
                        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(back).background(cs.primary.copy(alpha = 0.14f)))
                    }
                    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(front).background(cs.primary.copy(alpha = 0.32f)))
                    Text(primary.label, color = cs.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }

            is StorePrimaryAction.Button -> Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(shape)
                    .background(if (primary.enabled) cs.primary else cs.primary.copy(alpha = 0.4f))
                    .then(if (primary.enabled) Modifier.labTile(onClick = primary.onClick, onAccent = true) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(primary.label, color = cs.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        if (secondary != null) Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(shape)
                .background(bg.copy(alpha = 0.55f))
                .border(1.dp, if (secondary.enabled) cs.primary else cs.outline, shape)
                .then(if (secondary.enabled) Modifier.labTile(onClick = secondary.onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(secondary.label, color = if (secondary.enabled) cs.primary else cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }

        if (gear.isNotEmpty()) {
            Box {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(shape)
                        .background(bg.copy(alpha = 0.55f))
                        .border(1.dp, cs.outline.copy(alpha = 0.5f), shape)
                        .labTile(onClick = { gearExpanded = true }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚙", fontSize = 18.sp, color = cs.primary)
                }
                DropdownMenu(
                    expanded = gearExpanded,
                    onDismissRequest = { gearExpanded = false },
                    modifier = Modifier.outlinedMenuCard(),
                ) {
                    gear.forEachIndexed { i, item ->
                        if (i > 0) MenuItemDivider()
                        val base = if (item.danger) cs.error else cs.onSurface
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = item.label,
                                    color = if (item.enabled) base else base.copy(alpha = 0.4f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                            leadingIcon = { Text(item.emoji, fontSize = 14.sp) },
                            enabled = item.enabled,
                            onClick = { gearExpanded = false; item.onClick() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The detail page's tab chips — horizontally scrollable; the selected one is filled with the accent. A tab may carry
 * a small count badge.
 */
@Composable
fun StoreDetailTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    badges: Map<Int, String> = emptyMap(),
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val isSel = index == selected
            val badge = badges[index]
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .clip(shape)
                    .background(if (isSel) cs.primary.copy(alpha = 0.14f) else cs.surface)
                    .border(1.dp, if (isSel) cs.primary.copy(alpha = 0.45f) else cs.outline.copy(alpha = 0.5f), shape)
                    .labTile(onClick = { onSelect(index) })
                    .padding(horizontal = 15.dp, vertical = 9.dp),
            ) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (isSel) cs.primary else cs.onSurfaceVariant, maxLines = 1)
                if (badge != null) {
                    Text(badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isSel) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
                }
            }
        }
    }
}
