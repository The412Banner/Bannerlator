package com.winlator.star.ui.simple

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.lastPlayedLabel
import com.winlator.star.ui.deck.playtimeLabel
import com.winlator.star.ui.deck.readPlayStats
import com.winlator.star.ui.deck.settings.Control
import com.winlator.star.ui.deck.settings.DeckSettingsRoutes
import com.winlator.star.ui.deck.settings.EditorScope
import com.winlator.star.ui.deck.settings.GameSource
import com.winlator.star.ui.deck.settings.Origin
import com.winlator.star.ui.deck.settings.SettingDef
import com.winlator.star.ui.deck.settings.SettingsEnv
import com.winlator.star.ui.deck.settings.SettingsRegistry
import com.winlator.star.ui.deck.settings.SimplePicker
import com.winlator.star.ui.deck.settings.labelFor
import com.winlator.star.ui.deck.settings.optionsOf
import com.winlator.star.ui.deck.storeLabelOf
import com.winlator.star.ui.screens.ContainerDetailViewModel
import com.winlator.star.ui.screens.GameMenuAction
import com.winlator.star.ui.screens.GamesScreenRequests
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.ShortcutActionDialogs
import com.winlator.star.ui.screens.ShortcutLaunchDialogs
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.rememberShortcutActions
import com.winlator.star.ui.screens.rememberShortcutLauncher
import java.io.File
import kotlin.math.roundToInt

/** The game page's quick settings: the first of each group that applies to this game (X11 or Wayland driver ...). */
private val QUICK = listOf(
    listOf("graphics.driver", "graphics.compositorDriver") to L("GPU driver"),
    listOf("graphics.dxWrapper") to L("DX wrapper"),
    listOf("display.resolution") to L("Resolution"),
    listOf("hud.show") to L("FPS overlay"),
    listOf("performance.frameGen") to L("Frame gen"),
    listOf("performance.fpsLimiter") to L("FPS limiter"),
    listOf("performance.syncMode") to L("Sync"),
    listOf("input.controlsProfile") to L("Controls"),
)

/**
 * A game's Lab page: its cover, Play and the game menu, its main settings as tiles (A: the next value, X: every value;
 * each change is saved at once, on the same storage as the full editor), its playtime, and All settings (the full
 * Classic-shaped editor).
 */
@Composable
internal fun LabGamePage(path: String, onNavigate: (String) -> Unit, onBack: () -> Unit, vm: ShortcutsViewModel = viewModel()) {
    val context = LocalContext.current
    val loaded = remember(path) {
        runCatching {
            val manager = ContainerManager(context)
            // The Linux Steam client and Android games live in their own homes, which are never in the container list.
            (manager.containers + listOfNotNull(runCatching { manager.linuxContainer }.getOrNull(), runCatching { manager.androidGamesContainer }.getOrNull())).firstNotNullOfOrNull { c ->
                val file = File(path)
                if (file.isFile && file.parentFile?.canonicalPath == c.desktopDir.canonicalPath) Shortcut(c, file) else null
            }
        }.getOrNull()
    }
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(L("This game is no longer in your library."), color = SimpleMuted) }
        return
    }
    val shortcutState = remember(path) { mutableStateOf(loaded) }
    val containerId = loaded.container.id
    val containerVm: ContainerDetailViewModel = viewModel(key = "lab-game-$containerId-c")
    val defaultsVm: ContainerDetailViewModel = viewModel(key = "lab-game-$containerId-d")
    var ready by remember(containerVm) { mutableStateOf(false) }
    LaunchedEffect(containerVm, defaultsVm) {
        containerVm.init(containerId)
        defaultsVm.init(ContainerDetailViewModel.EDIT_DEFAULTS_ID)
        defaultsVm.selectDefaultsArch(
            if (containerVm.isArm64EC) com.winlator.star.core.NewContainerDefaults.ARCH_ARM64EC else com.winlator.star.core.NewContainerDefaults.ARCH_X86_64,
        )
        ready = true
    }
    val env = remember(containerVm) { SettingsEnv(context, EditorScope.GAME, containerVm, shortcutState.value) }
    val source = remember(env, defaultsVm) { GameSource(env, shortcutState, containerVm, defaultsVm) }
    LaunchedEffect(env) { env.loadAsync() }
    val launcher = rememberShortcutLauncher()
    val actions = rememberShortcutActions(vm)
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { resumeTick++; source.reloadFrom() } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val deckActions = LocalDeckActions.current
    DisposableEffect(deckActions) {
        val legend = listOf("B" to L("Back"), "A" to L("Change / open"), "X" to L("All values"), "L1 R1" to L("Section"))
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }
    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        return
    }
    val shortcut = shortcutState.value
    LaunchedEffect(shortcut.file.path) { LabArt.selected = shortcut; LabArt.resolve(context, shortcut) }
    val defs = SettingsRegistry.defs
    val byId = remember(defs) { defs.associateBy { it.id } }
    val values: (String) -> String = { id -> byId[id]?.takeIf { source.shows(it) }?.let { source.value(it) } ?: "" }
    fun applies(d: SettingDef) = source.shows(d) && env.world in d.worlds && d.visible(env, values)
    val quick = QUICK.mapNotNull { (ids, label) -> ids.firstNotNullOfOrNull { id -> defs.firstOrNull { it.id == id && applies(it) } }?.let { it to label } }
    val stats = remember(resumeTick) { readPlayStats(context, listOf(shortcut)) }[shortcut.file.path]

    var pickerFor by remember { mutableStateOf<Pair<SettingDef, String>?>(null) }
    var confirm by remember { mutableStateOf<Pair<SettingDef, String>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(120); runCatching { playFocus.requestFocus() } }

    fun commit(def: SettingDef, v: String) {
        source.set(def, v)
        source.save { ok -> if (!ok) Toast.makeText(context, L("Could not save"), Toast.LENGTH_SHORT).show() }
    }
    fun take(def: SettingDef, v: String) {
        if (source.value(def) == v) return
        if (def.confirm?.invoke(v) != null) confirm = def to v else commit(def, v)
    }
    fun next(def: SettingDef, label: String) {
        def.disabled(env, values)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); return }
        if (!source.editable(def)) { Toast.makeText(context, L("%1\$s is a container setting.", label), Toast.LENGTH_SHORT).show(); return }
        when (val c = def.control(env, values)) {
            Control.Toggle -> take(def, if (source.value(def) == "1") "0" else "1")
            is Control.Segmented, is Control.Chips, is Control.Select -> {
                val opts = optionsOf(c).filter { it.disabledReason == null }
                if (opts.isEmpty()) return
                if (opts.size > 6) { pickerFor = def to label; return }
                val i = opts.indexOfFirst { it.value == source.value(def) }
                take(def, opts[(i + 1).mod(opts.size)].value)
            }
            else -> pickerFor = def to label
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val cols = if (portrait) 2 else 4
        val tileH = 84.dp

        @Composable
        fun QuickTile(def: SettingDef, label: String, m: Modifier) {
            val control = def.control(env, values)
            val v = L(runCatching { labelFor(control, source.value(def)) }.getOrDefault(source.value(def)))
            val origin = source.origin(def)
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = m.labPanel().labTile(onClick = { next(def, label) }, onMenu = { pickerFor = def to label }).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabTileLabel(label)
                    Spacer(Modifier.weight(1f))
                    if (origin == Origin.SET_HERE) Text(L("This game"), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(v.ifBlank { "—" }, color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        val header: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabTitle(shortcut, 72.dp, 24.sp)
                Text(
                    listOfNotNull(storeLabelOf(shortcut), playtimeLabel(stats?.ms ?: 0L), lastPlayedLabel(context, stats?.lastPlayed ?: 0L), shortcut.container.name).joinToString(" · "),
                    color = SimpleMuted, fontSize = 13.sp, maxLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton(L("Play"), primary = true, icon = Icons.Filled.PlayArrow, requester = playFocus) { launcher.requestLaunch(shortcut) }
                    LabButton("", icon = Icons.Filled.MoreHoriz) { menuOpen = true }
                }
            }
        }

        val grid: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                quick.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (def, label) -> QuickTile(def, label, Modifier.weight(1f).height(tileH)) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f).height(tileH).labPanel()
                            .labTile(onClick = {
                                onBack()
                                GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.MOVE_TO_CONTAINER, shortcut.file.path)
                            })
                            .padding(12.dp),
                    ) {
                        LabTileLabel(L("Container"))
                        Text(shortcut.container.name, color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                    }
                    // Lab's Game details: the details, the pictures and this game's background.
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f).height(tileH).labPanel().labTile(onClick = { onNavigate(LabRoutes.details(shortcut.file.path)) }).padding(12.dp),
                    ) {
                        LabTileLabel(L("Game details"))
                        Text(L("Details, artwork, background ›"), color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f).height(tileH).labPanel().labTile(onClick = { onNavigate(DeckSettingsRoutes.game(shortcut.file.path)) }).padding(12.dp),
                    ) {
                        LabTileLabel(L("Everything else"))
                        Text(L("All settings ›"), color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
                // The saved FPS of its sessions (a chart), or its playtime until it has one.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val fps = remember(resumeTick) { com.winlator.star.ui.deck.readFpsStats(context, shortcut) }
                    Column(Modifier.weight(1f).height(if (fps != null) 110.dp else tileH).labPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (fps != null) {
                            LabTileLabel(L("FPS · last %1\$s sessions", fps.history.size))
                            Text(
                                L("%1\$s avg", fps.lastAvg.roundToInt()) + (if (fps.lastLow > 0) L(" · 1%% low %1\$s", fps.lastLow) else "") + " · ${playtimeLabel(stats?.ms ?: 0L)}",
                                color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1,
                            )
                            LabSparkline(fps.history, Modifier.fillMaxWidth().weight(1f))
                        } else {
                            LabTileLabel(L("Playtime"))
                            Text(
                                L("%1\$s · %2\$s sessions", playtimeLabel(stats?.ms ?: 0L), stats?.count ?: 0),
                                color = SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        if (portrait) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SimpleCover(shortcut, Modifier.width(150.dp).height(200.dp), title = false)
                    header(Modifier.weight(1f))
                }
                grid(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SimpleCover(shortcut, Modifier.fillMaxWidth().height(300.dp), title = false)
                    header(Modifier.fillMaxWidth())
                }
                grid(Modifier.weight(1f).verticalScroll(rememberScrollState()))
            }
        }
    }

    pickerFor?.let { (def, label) ->
        val c = def.control(env, values)
        SimplePicker(
            title = label,
            options = optionsOf(c),
            current = source.value(def),
            customLabel = null,
            onDismiss = { pickerFor = null },
            onPick = { v -> pickerFor = null; take(def, v) },
            onCustom = { pickerFor = null },
        )
    }
    confirm?.let { (def, v) ->
        val ask = def.confirm?.invoke(v)
        if (ask == null) { confirm = null; commit(def, v) }
        else OutlinedAlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = { TextButton(onClick = { confirm = null; commit(def, v) }) { Text(ask.confirmLabel) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(L("Cancel")) } },
            title = { Text(ask.title) },
            text = { Text(ask.body) },
        )
    }
    if (menuOpen) {
        LabGameMenu(
            shortcut = shortcut,
            subtitle = storeLabelOf(shortcut) + " · " + playtimeLabel(stats?.ms ?: 0L),
            onDismiss = { menuOpen = false },
            onPlay = { menuOpen = false; launcher.requestLaunch(shortcut) },
            onAction = { menuOpen = false; actions.perform(it, shortcut) },
            onContainer = { menuOpen = false; onNavigate(DeckSettingsRoutes.container(shortcut.container.id)) },
            onDetails = { tab -> menuOpen = false; onNavigate(LabRoutes.details(shortcut.file.path, tab)) },
        )
    }
    ShortcutActionDialogs(actions)
    ShortcutLaunchDialogs(launcher, onRemove = { val removed = vm.remove(it, context); if (removed) onBack(); removed })
}
