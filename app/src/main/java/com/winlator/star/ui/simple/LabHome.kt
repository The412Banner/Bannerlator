package com.winlator.star.ui.simple

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.xbox.XboxCloudShortcuts
import com.winlator.star.ui.deck.LibrarySort
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.gameSortKeyOf
import com.winlator.star.ui.deck.lastPlayedLabel
import com.winlator.star.ui.deck.playtimeLabel
import com.winlator.star.ui.deck.readPlayStats
import com.winlator.star.ui.deck.sortGames
import com.winlator.star.ui.deck.storeLabelOf
import com.winlator.star.ui.screens.GameMenuAction
import com.winlator.star.ui.screens.GamesScreenRequests
import com.winlator.star.ui.screens.ShortcutActionDialogs
import com.winlator.star.ui.screens.ShortcutLaunchDialogs
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.rememberShortcutActions
import com.winlator.star.ui.screens.rememberShortcutLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A small label over a tile's content. */
@Composable
internal fun LabTileLabel(text: String) {
    Text(text.uppercase(), color = SimpleDim, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp, maxLines = 1)
}

/** One dashboard tile: a panel, focusable when it does something. */
@Composable
private fun LabDashTile(
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    requester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .labPanel()
            .then(if (onClick != null) Modifier.labTile(onClick = onClick, onMenu = onMenu, requester = requester) else Modifier)
            .padding(14.dp),
        content = content,
    )
}

/** A text button the controller can reach: the accent one for the main action, red text and icon when it removes something. */
@Composable
internal fun LabButton(
    label: String,
    primary: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    requester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val red = MaterialTheme.colorScheme.error
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = modifier
            .height(40.dp)
            .background(if (primary) accent else SimpleCard, SimpleShape)
            .labTile(onClick = onClick, requester = requester, onAccent = primary)
            .padding(horizontal = 16.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (primary) Color.White else if (danger) red else SimpleInk, modifier = Modifier.size(18.dp))
        Text(label, color = if (primary) Color.White else if (danger) red else SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

/** A value chip: what a game runs with, at a glance. */
@Composable
internal fun LabChip(text: String) {
    Text(text, color = SimpleInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(SimpleCard, SimpleShape).padding(horizontal = 9.dp, vertical = 5.dp))
}

/**
 * Lab's home: a dashboard. Continue (the last game: its setup, Play, its page), the next recent games, then live tiles
 * for storage, GPU drivers, downloads, playtime, the device and containers. Every tile opens where it points.
 */
@Composable
internal fun LabHome(
    onNavigate: (String) -> Unit,
    onOpenContainer: (Int) -> Unit,
    vm: ShortcutsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val shortcuts by vm.shortcuts.collectAsState(initial = SimpleSession.lastShortcuts)
    LaunchedEffect(shortcuts) { if (shortcuts.isNotEmpty()) SimpleSession.lastShortcuts = shortcuts }
    val launcher = rememberShortcutLauncher()
    // A SteamLite retry / Goldberg relaunch or a Ubisoft set-up-then-launch waits for the restarted app's library, as on the Deck and Games screens.
    LaunchedEffect(shortcuts) { launcher.resumePendingRelaunch(shortcuts) }
    val actions = rememberShortcutActions(vm)
    val deckActions = LocalDeckActions.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { vm.refresh(); resumeTick++ } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val stats = remember(shortcuts, resumeTick) { readPlayStats(context, shortcuts) }
    LaunchedEffect(shortcuts) { LabTitles.refresh(context, shortcuts) }
    val recent = remember(shortcuts, stats) { sortGames(shortcuts, LibrarySort.RECENT) { gameSortKeyOf(it, stats) } }
    val dev = rememberLabDevice()
    val downloads by ContentDownloadRegistry.states.collectAsState()
    val active = downloads.values.firstOrNull { !it.terminal }
    var drivers by remember { mutableStateOf<List<String>?>(null) }
    var containers by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(resumeTick) {
        drivers = withContext(Dispatchers.IO) {
            runCatching { (context as? Activity)?.let { a -> val m = AdrenotoolsManager(a); m.enumarateInstalledDrivers().map { m.getDriverName(it) ?: it } } }.getOrNull()
        }
        containers = withContext(Dispatchers.IO) { runCatching { ContainerManager(context).containers.map { it.name } }.getOrNull() }
    }
    var menuFor by remember { mutableStateOf<Shortcut?>(null) }
    var selPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // The game launched last: the big tile's game until another is picked. The row's first entry is not it when the
    // Windows Steam client is listed (it is pinned first in every order).
    val lastGame = remember(recent, stats) {
        recent.filter { (stats[it.file.path]?.lastPlayed ?: 0L) > 0L }.maxByOrNull { stats[it.file.path]?.lastPlayed ?: 0L } ?: recent.firstOrNull()
    }
    // A game played since the last look takes the tile back from an earlier pick.
    val lastAt = lastGame?.let { stats[it.file.path]?.lastPlayed } ?: 0L
    var seenLastAt by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(lastAt) {
        if (lastAt != seenLastAt) {
            if (seenLastAt != 0L) selPath = null
            seenLastAt = lastAt
        }
    }
    val playFocus = remember { FocusRequester() }
    val enterTick = deckActions.enterTick.intValue
    LaunchedEffect(resumeTick, enterTick, recent.isNotEmpty()) {
        delay(120)
        runCatching { playFocus.requestFocus() }
    }
    DisposableEffect(deckActions) {
        val legend = listOf("A" to L("Open"), "X" to L("Game menu"), "L1 R1" to L("Section"))
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }
    val addGame = { GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.ADD_GAME) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val gap = 12.dp
        val pad = if (portrait) 12.dp else 18.dp
        val rowH: Dp = if (portrait) 140.dp else ((maxHeight - pad * 2 - gap * 2 - 30.dp) / 3).coerceIn(118.dp, 200.dp)
        // The game in the big tile: the one picked in the library row (a tap, or the controller resting on it), else the last played.
        val game = recent.firstOrNull { it.file.path == selPath } ?: lastGame
        // Its art goes behind the page (Appearance › Game art background).
        LaunchedEffect(game?.file?.path) { LabArt.selected = game }
        // Games without a Steam link: find their art by name (once per game).
        LaunchedEffect(recent) { recent.forEach { LabArt.resolve(context, it) } }

        @Composable
        fun ContinueTile(m: Modifier) {
            if (game == null) {
                LabDashTile(m, onClick = addGame, requester = playFocus) {
                    LabTileLabel(L("Get started"))
                    Text(L("Add your first game"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(L("An .exe from your files, or a game from a store."), color = SimpleMuted, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                }
                return
            }
            // A different game picked below: the big tile fades over to it.
            // The tile's size (its weight in the row) goes on the fade itself: inside it a weight would not apply, and the
            // tile took the whole row (the library row and the tiles beside it had no room left).
            androidx.compose.animation.Crossfade(targetState = game, modifier = m, animationSpec = androidx.compose.animation.core.tween(220), label = "labContinue") { game ->
            val st = stats[game.file.path]
            // The setup as Classic's list card and the launch screen read it (the driver's version, Wayland only when it really runs on it).
            val chips = remember(game.file.path, LabArt.tick, resumeTick) { labSetupChips(context, game) }
            val meta = listOfNotNull(
                if (XboxCloudShortcuts.isNotInstalled(game)) L("Not installed") else null,
                playtimeLabel(st?.ms ?: 0L), lastPlayedLabel(context, st?.lastPlayed ?: 0L), storeLabelOf(game),
            ).joinToString(" · ")
            // Art and previews: the tile becomes the game's art with its trailer behind the logo, info and buttons.
            if (LabArt.hasClips(LabArt.gameMode(game))) {
                LabHeroTile(
                    game,
                    label = if (game == lastGame) L("Continue") else L("Selected"),
                    meta = meta,
                    chips = chips,
                    playFocus = playFocus,
                    onPlay = { launcher.requestLaunch(game) },
                    onPage = { onNavigate(LabRoutes.game(game.file.path)) },
                    onMenu = { menuFor = game },
                )
                return@Crossfade
            }
            Row(Modifier.fillMaxSize().labPanel().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SimpleCover(game, Modifier.width(if (portrait) 150.dp else 170.dp).fillMaxHeight(), title = false)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabTileLabel(if (game == lastGame) L("Continue") else L("Selected"))
                    LabTitle(game, 56.dp, 22.sp)
                    Text(meta, color = SimpleMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LabRuntimeStatus(game, fontSize = 12.sp, color = SimpleMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.take(4).forEach { LabChip(it) }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabButton(L("Play"), primary = true, icon = Icons.Filled.PlayArrow, requester = playFocus) { launcher.requestLaunch(game) }
                        LabButton(L("Game page")) { onNavigate(LabRoutes.game(game.file.path)) }
                        LabButton("", icon = Icons.Filled.MoreHoriz) { menuFor = game }
                    }
                }
            }
            }
        }

        @Composable
        fun RecentTile(m: Modifier) {
            // The whole library, last played first, in a row that scrolls. A tap (or the controller resting on a cover)
            // puts that game in the big tile above; a long press or X opens its menu.
            val coverW = (rowH - 46.dp) * 0.75f
            // Appearance › Library cards: tall covers, wide covers, or icons with the name under them.
            val style = LabArt.cards
            val cardW = when (style) { LabArt.WIDE -> (rowH - 46.dp) * 2.14f; LabArt.ICONS -> rowH - 84.dp; else -> coverW }
            Column(m.labPanel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabTileLabel(if (recent.size == 1) L("Library · 1 game") else L("Library · %1\$s games", recent.size))
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recent, key = { it.file.path }) { s ->
                        val sel = s.file.path == game?.file?.path
                        Box(
                            Modifier.fillMaxHeight().width(cardW)
                                .then(if (sel) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, SimpleShape) else Modifier)
                                .labTile(onClick = { selPath = s.file.path }, onMenu = { menuFor = s }, onFocus = { selPath = s.file.path }),
                        ) {
                            val inner = Modifier.fillMaxSize().padding(if (sel) 3.dp else 0.dp)
                            when (style) {
                                // No store pills on these small covers: the big tile above names the selected game's store.
                                LabArt.WIDE -> LabWideCard(s, inner, title = false)
                                LabArt.ICONS -> LabIconCard(s, inner, Modifier.fillMaxWidth().aspectRatio(1f))
                                else -> SimpleCover(s, inner, title = false)
                            }
                        }
                    }
                    item(key = "add") {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxHeight().width(coverW).background(SimpleCard, SimpleShape).labTile(onClick = addGame),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                                Text(L("Add a game"), color = SimpleMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        @Composable
        fun StorageTile(m: Modifier) {
            LabDashTile(m, onClick = { onNavigate(SimpleRoutes.TOOLS) }) {
                LabTileLabel(L("Storage"))
                val f = if (dev != null && dev.diskTotalGb > 0) dev.diskUsedGb / dev.diskTotalGb else 0f
                // With an SD card or a USB drive in: a bar for each volume. Internal only: the ring.
                if (dev != null && dev.volumes.size > 1) {
                    dev.volumes.forEach { v ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(L(v.label), color = SimpleInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(L("%1\$s / %2\$s GB", fmtGb(v.usedGb), fmtGb(v.totalGb)), color = SimpleMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            LabBar(v.fraction, Modifier.fillMaxWidth())
                        }
                    }
                    return@LabDashTile
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                        CircularProgressIndicator(progress = { f }, modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primary, trackColor = SimpleLine, strokeWidth = 7.dp)
                        Text("${(f * 100).roundToInt()}%", color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Text(if (dev != null) L("%1\$s of %2\$s GB", fmtGb(dev.diskUsedGb), fmtGb(dev.diskTotalGb)) else "…", color = SimpleMuted, fontSize = 12.sp)
                }
            }
        }

        @Composable
        fun DriverTile(m: Modifier) {
            LabDashTile(m, onClick = { onNavigate(SimpleRoutes.contents("installed")) }) {
                LabTileLabel(L("GPU drivers"))
                val list = drivers
                Text(if (list == null) "…" else L("%1\$s installed", list.size), color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                list?.take(2)?.forEach { Text(it, color = SimpleMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }

        @Composable
        fun DownloadTile(m: Modifier) {
            LabDashTile(m, onClick = { onNavigate(SimpleRoutes.DRIVERS) }) {
                LabTileLabel(L("Downloads"))
                if (active != null) {
                    Text(active.title, color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LabBar(active.fraction, Modifier.fillMaxWidth())
                    Text("${(active.fraction * 100).roundToInt()}%", color = SimpleMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                } else {
                    Text(L("Nothing downloading"), color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(L("Components and drivers"), color = SimpleMuted, fontSize = 12.sp)
                }
            }
        }

        @Composable
        fun PlaytimeTile(m: Modifier) {
            val total = stats.values.sumOf { it.ms }
            // The last game's last session, once it has saved one; until then the total playtime.
            val fps = remember(game, resumeTick) { game?.let { com.winlator.star.ui.deck.readFpsStats(context, it) } }
            if (game != null && fps != null) {
                LabDashTile(m, onClick = { onNavigate(LabRoutes.game(game.file.path)) }) {
                    LabTileLabel(L("Last session"))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${fps.lastAvg.roundToInt()}", color = SimpleStrong, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, fontFamily = FontFamily.Monospace)
                        Text(L("fps avg"), color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        if (fps.lastLow > 0) Text(L("· 1%% low %1\$s", fps.lastLow), color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    LabSparkline(fps.history, Modifier.fillMaxWidth().weight(1f))
                }
                return
            }
            LabDashTile(m, onClick = { onNavigate(LabRoutes.LIBRARY) }) {
                LabTileLabel(L("Playtime"))
                Text(playtimeLabel(total), color = SimpleStrong, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, maxLines = 1)
                Text(L("across %1\$s games", stats.values.count { it.ms > 0 }), color = SimpleMuted, fontSize = 12.sp)
            }
        }

        @Composable
        fun SystemTile(m: Modifier) {
            LabDashTile(m, onClick = { onNavigate(com.winlator.star.ui.deck.settings.DeckSettingsRoutes.APP) }) {
                LabTileLabel(L("This device"))
                if (dev != null) {
                    @Composable fun line(k: String, f: Float, v: String) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(k, color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.width(54.dp))
                            LabBar(f, Modifier.weight(1f))
                            Text(v, color = SimpleInk, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (dev.battery >= 0) line(L("Battery"), dev.battery / 100f, "${dev.battery}%")
                    line("RAM", if (dev.ramTotalGb > 0) dev.ramUsedGb / dev.ramTotalGb else 0f, "${fmtGb(dev.ramUsedGb)} GB")
                    dev.tempC?.let { line(L("Temp"), it / 90f, L("%1\$s°C", it)) }
                }
            }
        }

        @Composable
        fun ContainersTile(m: Modifier) {
            LabDashTile(m, onClick = { onNavigate(SimpleRoutes.CONTAINERS) }) {
                LabTileLabel(L("Containers"))
                Text("${containers?.size ?: "…"}", color = SimpleStrong, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                Text(containers?.joinToString(" · ").orEmpty(), color = SimpleMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        @Composable
        fun RowScope.cell(w: Float, h: Dp = rowH, content: @Composable (Modifier) -> Unit) = content(Modifier.weight(w).height(h))

        if (portrait) {
            Column(
                verticalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
            ) {
                ContinueTile(Modifier.fillMaxWidth().height(rowH * 2 + gap))
                RecentTile(Modifier.fillMaxWidth().height(rowH))
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { cell(1f) { StorageTile(it) }; cell(1f) { DriverTile(it) } }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { cell(1f) { DownloadTile(it) }; cell(1f) { PlaytimeTile(it) } }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { cell(1f) { SystemTile(it) }; cell(1f) { ContainersTile(it) } }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxSize().padding(start = pad, end = pad, top = 4.dp, bottom = pad)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    ContinueTile(Modifier.weight(2f).height(rowH * 2 + gap))
                    Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(gap)) {
                        RecentTile(Modifier.fillMaxWidth().height(rowH))
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) { cell(1f) { StorageTile(it) }; cell(1f) { DriverTile(it) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    cell(1f) { DownloadTile(it) }; cell(1f) { PlaytimeTile(it) }; cell(1f) { SystemTile(it) }; cell(1f) { ContainersTile(it) }
                }
            }
        }
    }

    menuFor?.let { s ->
        LabGameMenu(
            shortcut = s,
            subtitle = storeLabelOf(s) + " · " + playtimeLabel(stats[s.file.path]?.ms ?: 0L),
            onDismiss = { menuFor = null },
            onPlay = { menuFor = null; launcher.requestLaunch(s) },
            onAction = { menuFor = null; actions.perform(it, s) },
            onContainer = { menuFor = null; onOpenContainer(s.container.id) },
            onDetails = { tab -> menuFor = null; onNavigate(LabRoutes.details(s.file.path, tab)) },
        )
    }
    ShortcutActionDialogs(actions)
    ShortcutLaunchDialogs(launcher, onRemove = { vm.remove(it, context) })
}

/**
 * Home's big tile with previews on: the game's art filling the tile and its preview clip over it, the logo at its
 * Adjust logo spot, then what it is, its setup and the buttons along the bottom over a dark fade. With 3D art the art
 * drifts slowly in depth and the logo less.
 */
@Composable
internal fun LabHeroTile(
    game: Shortcut,
    label: String,
    meta: String,
    chips: List<String>,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onPage: () -> Unit,
    onMenu: () -> Unit,
) {
    val tick = LabArt.tick
    val p3d = LabArt.has3d(LabArt.gameMode(game))
    val bg = remember(game.file.path, tick) { LabArt.models(game, "bg").map { LabArt.Pic(it) } + listOfNotNull(LabArt.coverModel(game)?.let { LabArt.Pic(it, blur = true) }) }
    Box(Modifier.fillMaxSize().simpleDepth().clip(SimpleShape).background(Color(0xFF101318)).border(1.dp, SimpleLine, SimpleShape)) {
        val layer = Modifier.fillMaxSize().graphicsLayer {
            val p = LabParallax.offset(p3d)
            scaleX = 1.08f
            scaleY = 1.08f
            translationX = -p.first * 12.dp.toPx()
            translationY = -p.second * 9.dp.toPx()
        }
        LabPicture(bg, layer)
        // The player is never moved by the drift (an AndroidView under a moving layer has to be re-placed every frame).
        LabClipPlayer(game, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.08f), 0.35f to Color.Transparent, 0.66f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.86f))))
        Box(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 156.dp).graphicsLayer {
                val p = LabParallax.offset(p3d)
                translationX = p.first * 8.dp.toPx()
                translationY = p.second * 6.dp.toPx()
            },
        ) { LabHeroLogo(game) }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.uppercase(), color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp, maxLines = 1)
            Text(meta, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LabRuntimeStatus(game, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.take(4).forEach {
                    Text(it, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.background(Color.Black.copy(alpha = 0.38f), SimpleShape).padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabButton(L("Play"), primary = true, icon = Icons.Filled.PlayArrow, requester = playFocus, onClick = onPlay)
                LabButton(L("Game page"), onClick = onPage)
                LabButton("", icon = Icons.Filled.MoreHoriz, onClick = onMenu)
            }
        }
    }
}
