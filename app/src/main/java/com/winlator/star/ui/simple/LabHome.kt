package com.winlator.star.ui.simple

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.winlator.star.ui.deck.DeckGameMenuSheet
import com.winlator.star.ui.deck.LibrarySort
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.gameSortKeyOf
import com.winlator.star.ui.deck.lastPlayedLabel
import com.winlator.star.ui.deck.playtimeLabel
import com.winlator.star.ui.deck.readPlayStats
import com.winlator.star.ui.deck.setupSummary
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

/** A text button the controller can reach: the accent one for the main action. */
@Composable
internal fun LabButton(
    label: String,
    primary: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    requester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = modifier
            .height(40.dp)
            .background(if (primary) accent else SimpleCard, SimpleShape)
            .labTile(onClick = onClick, requester = requester)
            .padding(horizontal = 16.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (primary) Color.White else SimpleInk, modifier = Modifier.size(18.dp))
        Text(label, color = if (primary) Color.White else SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
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
    val playFocus = remember { FocusRequester() }
    val enterTick = deckActions.enterTick.intValue
    LaunchedEffect(resumeTick, enterTick, recent.isNotEmpty()) {
        delay(120)
        runCatching { playFocus.requestFocus() }
    }
    DisposableEffect(deckActions) {
        val legend = listOf("A" to "Open", "X" to "Game menu", "L1 R1" to "Section")
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }
    val addGame = { GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.ADD_GAME) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val gap = 12.dp
        val pad = if (portrait) 12.dp else 18.dp
        val rowH: Dp = if (portrait) 140.dp else ((maxHeight - pad * 2 - gap * 2 - 30.dp) / 3).coerceIn(118.dp, 200.dp)
        val game = recent.firstOrNull()
        val next = recent.drop(1).take(if (portrait) 3 else 4)

        @Composable
        fun ContinueTile(m: Modifier) {
            if (game == null) {
                LabDashTile(m, onClick = addGame, requester = playFocus) {
                    LabTileLabel("Get started")
                    Text("Add your first game", color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text("An .exe from your files, or a game from a store.", color = SimpleMuted, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                }
                return
            }
            val st = stats[game.file.path]
            Row(m.labPanel().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SimpleCover(game, Modifier.width(if (portrait) 150.dp else 170.dp).fillMaxHeight(), title = false)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabTileLabel("Continue")
                    Text(game.name, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(playtimeLabel(st?.ms ?: 0L), lastPlayedLabel(context, st?.lastPlayed ?: 0L), storeLabelOf(game)).joinToString(" · "),
                        color = SimpleMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        setupSummary(game).split(" · ").filter { it.isNotBlank() }.take(4).forEach { LabChip(it) }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabButton("Play", primary = true, icon = Icons.Filled.PlayArrow, requester = playFocus) { launcher.requestLaunch(game) }
                        LabButton("Game page") { onNavigate(LabRoutes.game(game.file.path)) }
                        LabButton("", icon = Icons.Filled.MoreHoriz) { menuFor = game }
                    }
                }
            }
        }

        @Composable
        fun RecentTile(m: Modifier) {
            Column(m.labPanel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabTileLabel("Recent")
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    next.forEach { s ->
                        Box(Modifier.fillMaxHeight().width((rowH - 46.dp) * 0.75f).labTile(onClick = { onNavigate(LabRoutes.game(s.file.path)) }, onMenu = { menuFor = s })) {
                            SimpleCover(s, Modifier.fillMaxSize(), title = false)
                        }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxHeight().width((rowH - 46.dp) * 0.75f).background(SimpleCard, SimpleShape).labTile(onClick = { onNavigate(LabRoutes.LIBRARY) }),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${recent.size}", color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("All games", color = SimpleMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        @Composable
        fun StorageTile(m: Modifier) {
            LabDashTile(m, onClick = { onNavigate(SimpleRoutes.TOOLS) }) {
                LabTileLabel("Storage")
                val f = if (dev != null && dev.diskTotalGb > 0) dev.diskUsedGb / dev.diskTotalGb else 0f
                // With an SD card or a USB drive in: a bar for each volume. Internal only: the ring.
                if (dev != null && dev.volumes.size > 1) {
                    dev.volumes.forEach { v ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(v.label, color = SimpleInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${fmtGb(v.usedGb)} / ${fmtGb(v.totalGb)} GB", color = SimpleMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
                    Text(if (dev != null) "${fmtGb(dev.diskUsedGb)} of ${fmtGb(dev.diskTotalGb)} GB" else "…", color = SimpleMuted, fontSize = 12.sp)
                }
            }
        }

        @Composable
        fun DriverTile(m: Modifier) {
            LabDashTile(m, onClick = { onNavigate(SimpleRoutes.DRIVERS) }) {
                LabTileLabel("GPU drivers")
                val list = drivers
                Text(if (list == null) "…" else "${list.size} installed", color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                list?.take(2)?.forEach { Text(it, color = SimpleMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }

        @Composable
        fun DownloadTile(m: Modifier) {
            LabDashTile(m, onClick = { onNavigate(SimpleRoutes.COMPONENTS) }) {
                LabTileLabel("Downloads")
                if (active != null) {
                    Text(active.title, color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LabBar(active.fraction, Modifier.fillMaxWidth())
                    Text("${(active.fraction * 100).roundToInt()}%", color = SimpleMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                } else {
                    Text("Nothing downloading", color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Components and drivers", color = SimpleMuted, fontSize = 12.sp)
                }
            }
        }

        @Composable
        fun PlaytimeTile(m: Modifier) {
            val total = stats.values.sumOf { it.ms }
            LabDashTile(m, onClick = { onNavigate(LabRoutes.LIBRARY) }) {
                LabTileLabel("Playtime")
                Text(playtimeLabel(total), color = SimpleStrong, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, maxLines = 1)
                Text("across ${stats.values.count { it.ms > 0 }} games", color = SimpleMuted, fontSize = 12.sp)
            }
        }

        @Composable
        fun SystemTile(m: Modifier) {
            LabDashTile(m, onClick = { onNavigate(com.winlator.star.ui.deck.settings.DeckSettingsRoutes.APP) }) {
                LabTileLabel("This device")
                if (dev != null) {
                    @Composable fun line(k: String, f: Float, v: String) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(k, color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.width(54.dp))
                            LabBar(f, Modifier.weight(1f))
                            Text(v, color = SimpleInk, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (dev.battery >= 0) line("Battery", dev.battery / 100f, "${dev.battery}%")
                    line("RAM", if (dev.ramTotalGb > 0) dev.ramUsedGb / dev.ramTotalGb else 0f, "${fmtGb(dev.ramUsedGb)} GB")
                    dev.tempC?.let { line("Temp", it / 90f, "$it°C") }
                }
            }
        }

        @Composable
        fun ContainersTile(m: Modifier) {
            LabDashTile(m, onClick = { onNavigate(SimpleRoutes.CONTAINERS) }) {
                LabTileLabel("Containers")
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
        DeckGameMenuSheet(
            shortcut = s,
            subtitle = storeLabelOf(s) + " · " + playtimeLabel(stats[s.file.path]?.ms ?: 0L),
            onDismiss = { menuFor = null },
            onPlay = { menuFor = null; launcher.requestLaunch(s) },
            onAction = { menuFor = null; actions.perform(it, s) },
            onContainer = { menuFor = null; onOpenContainer(s.container.id) },
        )
    }
    ShortcutActionDialogs(actions)
    ShortcutLaunchDialogs(launcher, onRemove = { vm.remove(it, context) })
}
