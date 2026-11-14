package com.winlator.star.store.download

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.FocusRequester
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
import com.winlator.star.store.StoreEntry
import com.winlator.star.store.rememberStoreEntryFocus
import com.winlator.star.store.storeBackKey
import com.winlator.star.store.storeEntryTarget
import com.winlator.star.store.storeFocus
import com.winlator.star.ui.simple.LabArt
import com.winlator.star.ui.simple.LabPicture
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.labSgdbHero
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The game details page every store shares: the game's art fills the screen behind the page, and the page sits on it.
 *
 * The page is laid out by [StorePage]: a header band with the back button, then ONE primary button (or its progress fill while
 * downloading) with the Settings and Downloads buttons; landscape has the game's cover and name fixed on the right and a left
 * column under the buttons that scrolls (the download line, the tab chips and the selected tab's body); portrait is one column
 * that scrolls, with the cover and name on top.
 *
 * Every button and tab is a stop of the D-pad that takes the XMB highlight; A presses it, B goes back, and the page scrolls
 * to keep the focused one on screen (see [storeFocus]). Colours come from the app theme (accent, background), so the page
 * looks the same in every interface.
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
    /** The game's landscape art beside its name, best first; the Steam art found by the game's name follows it. */
    cover: List<String> = emptyList(),
    /** Small line(s) under the buttons — install status, exe name. */
    subtitle: (@Composable ColumnScope.() -> Unit)? = null,
    /** Line under the primary button (download rate / ETA). */
    infoLine: String? = null,
    tabBadges: Map<Int, String> = emptyMap(),
    /** Extra floating content (dialogs, result bar) drawn over the page. */
    overlay: @Composable BoxScope.() -> Unit = {},
    /** A second, outlined button under the primary one (Xbox: Stream under Install). */
    secondary: StorePrimaryAction.Button? = null,
    /** This game's key in [DownloadRegistry] ("EPIC:<id>"): while it downloads, the page shows its speed and time left. */
    downloadKey: String? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val bg = xmbSolidBackground()
    val cs = MaterialTheme.colorScheme
    val all by DownloadRegistry.entries.collectAsState()
    val download = if (downloadKey == null) null else all.firstOrNull { it.key == downloadKey && it.isActive }
    // Art found by the game's name: it goes under the store's own art as the page's backdrop and follows the store's cover.
    val fallbackArt = rememberFallbackArt(title)
    val coverUrls = remember(cover, fallbackArt) { storeCoverUrls(cover + fallbackArt.asReversed()) }
    val entryTarget = storeEntryTarget(primary is StorePrimaryAction.Button && primary.enabled, gear.isNotEmpty())
    val entry = rememberStoreEntryFocus(entryTarget)
    val tileFill = bg.copy(alpha = 0.55f)
    val tileLine = cs.outline.copy(alpha = 0.5f)
    val gearButton: @Composable (Modifier) -> Unit = { m ->
        StoreGearButton(gear, tileFill, tileLine, m, entry.takeIf { entryTarget == StoreEntry.GEAR })
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(bg).storeBackKey(onBack)) {
        val geometry = rememberStorePageGeometry(maxWidth, maxHeight, hasSettings = gear.isNotEmpty())
        // Under the store's own art: the Steam / SteamGridDB art the Lab finds by the game's name, for a store with none.
        StoreFallbackArt(fallbackArt, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = hero)
        if (geometry.layout == StorePageLayout.SIDE_BY_SIDE) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to bg.copy(alpha = 0.94f), 0.42f to bg.copy(alpha = 0.72f), 0.75f to bg.copy(alpha = 0.25f), 1f to bg.copy(alpha = 0.45f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to bg.copy(alpha = 0.8f))))
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to bg.copy(alpha = 0.25f), 0.30f to bg.copy(alpha = 0.35f), 0.62f to bg.copy(alpha = 0.93f), 0.84f to bg)))
        }
        StorePage(
            geometry = geometry,
            coverUrls = coverUrls,
            coverShape = SimpleShape,
            back = { m ->
                StoreBackTile(onBack, SimpleShape, bg.copy(alpha = 0.6f), cs.outline.copy(alpha = 0.35f), cs.onBackground, m, entry.takeIf { entryTarget == StoreEntry.BACK })
            },
            badge = storeBadge,
            name = {
                Text(
                    text = title,
                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, shadow = Shadow(bg.copy(alpha = 0.7f), blurRadius = 12f)),
                    color = cs.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            primary = { m -> StorePrimaryButton(primary, m, entry.takeIf { entryTarget == StoreEntry.PRIMARY }) },
            settings = gearButton.takeIf { gear.isNotEmpty() },
            downloads = { m -> StoreDownloadsTile(SimpleShape, tileFill, tileLine, cs.primary, m) },
        ) {
            if (subtitle != null) subtitle()
            if (secondary != null) {
                StoreSecondaryButton(secondary, Modifier.padding(top = 2.dp).width(geometry.installWidth.dp).height(geometry.buttonSize.dp))
            }
            if (download != null) StoreDownloadBar(download)
            if (!infoLine.isNullOrBlank()) {
                Text(infoLine, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            if (tabs.size > 1) StoreDetailTabs(labels = tabs, selected = selectedTab, badges = tabBadges, onSelect = onSelectTab)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(SimpleShape)
                    .background(bg.copy(alpha = 0.86f))
                    .border(1.dp, cs.outline.copy(alpha = 0.25f), SimpleShape)
                    .padding(vertical = 6.dp),
                content = body,
            )
        }
        overlay()
    }
}

private val fallbackArtCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

/** The art found by the game's name, empty until it is found (and when there is none): Steam's library hero, SteamGridDB's hero, then Steam's header. */
@Composable
private fun rememberFallbackArt(title: String): List<String> {
    val ctx = LocalContext.current.applicationContext
    val urls by produceState(fallbackArtCache[title] ?: emptyList(), title) {
        if (title.isBlank() || fallbackArtCache.containsKey(title)) return@produceState
        value = withContext(Dispatchers.IO) {
            val steam = runCatching { SteamStoreSearch.findAppIdByName(ctx, title) }.getOrNull()
            buildList {
                if (steam != null) add("https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/$steam/library_hero.jpg")
                runCatching { labSgdbHero(ctx, steam, title) }.getOrNull()?.let { add(it) }
                if (steam != null) add("https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/$steam/header.jpg")
            }
        }.also { fallbackArtCache[title] = it }
    }
    return urls
}

/** Full-screen art behind the page, from [urls] (see [rememberFallbackArt]). */
@Composable
private fun StoreFallbackArt(urls: List<String>, modifier: Modifier = Modifier) {
    if (urls.isNotEmpty()) LabPicture(urls.map { LabArt.Pic(it) }, modifier)
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
 * The primary button, at the size and place [modifier] gives it: an accent block (or the two-layer progress fill while
 * downloading). [entry] marks it as the button the page focuses on open (see [rememberStoreEntryFocus]).
 */
@Composable
private fun StorePrimaryButton(primary: StorePrimaryAction, modifier: Modifier, entry: FocusRequester?) {
    val shape = SimpleShape
    val cs = MaterialTheme.colorScheme
    val bg = xmbSolidBackground()
    when (primary) {
        is StorePrimaryAction.Progress -> {
            val front = primary.fraction.coerceIn(0f, 1f)
            val back = primary.backFraction.coerceIn(0f, 1f)
            Box(
                modifier = modifier
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
            modifier = modifier
                // Filled with the accent: the outline takes the ink on it.
                .then(if (primary.enabled) Modifier.storeFocus(shape, primary.onClick, tint = cs.onPrimary, entry = entry) else Modifier)
                .clip(shape)
                .background(if (primary.enabled) cs.primary else cs.primary.copy(alpha = 0.4f))
                .then(if (primary.enabled) Modifier.clickable(onClick = primary.onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(primary.label, color = cs.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** The outlined button under the primary one, at the size and place [modifier] gives it. */
@Composable
private fun StoreSecondaryButton(secondary: StorePrimaryAction.Button, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val bg = xmbSolidBackground()
    Box(
        modifier = modifier
            .then(if (secondary.enabled) Modifier.storeFocus(SimpleShape, secondary.onClick) else Modifier)
            .clip(SimpleShape)
            .background(bg.copy(alpha = 0.55f))
            .border(1.dp, if (secondary.enabled) cs.primary else cs.outline, SimpleShape)
            .then(if (secondary.enabled) Modifier.clickable(onClick = secondary.onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(secondary.label, color = if (secondary.enabled) cs.primary else cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * The Settings (⚙) button, at the size and place [modifier] gives it, opening the app's outlined dropdown card of [gear].
 * [entry] marks it as the button the page focuses on open.
 */
@Composable
private fun StoreGearButton(gear: List<StoreGearItem>, fill: Color, line: Color, modifier: Modifier, entry: FocusRequester?) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box {
        StoreActionTile(onClick = { expanded = true }, shape = SimpleShape, fill = fill, line = line, modifier = modifier, entry = entry) {
            Text("⚙", fontSize = 16.sp, color = cs.primary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.outlinedMenuCard().storeBackKey { expanded = false },
        ) {
            gear.forEachIndexed { i, item ->
                if (i > 0) MenuItemDivider()
                val base = if (item.danger) cs.error else cs.onSurface
                val pick = { expanded = false; item.onClick() }
                DropdownMenuItem(
                    modifier = if (item.enabled) Modifier.storeFocus(SimpleShape, pick) else Modifier,
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
                    onClick = pick,
                )
            }
        }
    }
}

/**
 * The detail page's tab chips: small, an icon and a short label each (see [StoreTabChips]); the selected one is filled with the
 * accent. A tab may carry a small count badge.
 */
@Composable
fun StoreDetailTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    badges: Map<Int, String> = emptyMap(),
) {
    val cs = MaterialTheme.colorScheme
    val bg = xmbSolidBackground()
    StoreTabChips(
        tabs = labels.mapIndexed { index, label -> StoreTab(storeTabIcon(label), label, badges[index]) },
        selected = selected,
        onSelect = onSelect,
        shape = SimpleShape,
        idle = StoreChipStyle(bg.copy(alpha = 0.55f), cs.outline.copy(alpha = 0.4f), cs.onSurfaceVariant),
        active = StoreChipStyle(cs.primary, cs.primary, cs.onPrimary, focusTint = cs.onPrimary),
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
}
