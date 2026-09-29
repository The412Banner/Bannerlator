package com.winlator.star.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.container.GameDetails
import com.winlator.star.container.Shortcut
import com.winlator.star.core.WinePath
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.store.SteamAchievementStore
import com.winlator.star.ui.screens.GameMenuAction
import com.winlator.star.ui.screens.ShortcutActionDialogs
import com.winlator.star.ui.screens.ShortcutLaunchDialogs
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.isCustomShortcut
import com.winlator.star.ui.screens.isSteamOriginShortcut
import com.winlator.star.ui.screens.rememberShortcutActions
import com.winlator.star.ui.screens.rememberShortcutLauncher
import com.winlator.star.ui.screens.steamAppIdOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A game's page in Deck mode: hero art, the facts (store, playtime, last played, achievements,
 * container), Play / Game settings / Options, the setup at a glance, and the whole per-game menu.
 * Play is the shared ShortcutLauncher and every menu item runs through ShortcutActions, so each
 * dialog opens right here rather than on the classic Games screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeckGamePage(
    shortcutPath: String,
    onBack: () -> Unit,
    onOpenContainer: (Int) -> Unit,
    vm: ShortcutsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val shortcuts by vm.shortcuts.collectAsState(initial = emptyList())
    val launcher = rememberShortcutLauncher()
    val actions = rememberShortcutActions(vm)
    val shortcut = shortcuts.firstOrNull { it.file.path == shortcutPath }

    // Same resume refresh as the library, so playtime and edits made in the editor show up.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Removed (or renamed away) while the page was open: go back once the list has loaded without it.
    LaunchedEffect(shortcuts.isNotEmpty(), shortcut == null) {
        if (shortcuts.isNotEmpty() && shortcut == null) onBack()
    }

    var menuOpen by remember { mutableStateOf(false) }
    val deckActions = LocalDeckActions.current
    DisposableEffect(deckActions) {
        val open: () -> Unit = { menuOpen = true }
        deckActions.options.value = open
        deckActions.optionsLabel.value = "Game menu"
        onDispose { if (deckActions.options.value === open) deckActions.options.value = null }
    }

    // Backdrop tint follows this game's cover, as on Home.
    val ambient = LocalDeckAmbient.current
    val tints by produceState<Pair<Color, Color>?>(null, shortcut?.file?.path, shortcut?.icon) {
        val bmp = shortcut?.icon
        value = if (bmp == null) null else withContext(Dispatchers.Default) { coverTints(bmp) }
    }
    LaunchedEffect(tints) { ambient.value = tints }
    DisposableEffect(ambient) { onDispose { ambient.value = null } }

    if (shortcut == null) {
        ShortcutActionDialogs(actions)
        return
    }
    val s: Shortcut = shortcut
    // Re-read on every refresh: shortcuts is rebuilt by refresh(), so keying on it picks up new playtime and extras.
    val stats = remember(shortcuts) { readPlayStats(context, listOf(s))[s.file.path] ?: PlayStats(0L, 0, 0L) }
    val details = remember(shortcuts) { GameDetails.from(s) }
    val onSd = remember(s) { runCatching { WinePath.isOnRemovableStorage(s.container, s.path) }.getOrDefault(false) }
    val achievements by produceState<Pair<Int, Int>?>(null, s.file.path) {
        val appId = if (isSteamOriginShortcut(s)) steamAppIdOf(s) else 0
        value = if (appId <= 0) null else withContext(Dispatchers.IO) {
            SteamAchievementStore.cached(context, appId).takeIf { it.isNotEmpty() }?.let { list -> list.count { it.unlocked } to list.size }
        }
    }
    val playRequester = remember { FocusRequester() }
    LaunchedEffect(s.file.path) {
        withFrameNanos { }
        runCatching { playRequester.requestFocus() }
    }
    var remembered by remember(shortcuts) { mutableStateOf(rememberedLaunchLabel(s)) }
    val gutter = deckGutter()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        // Cover art across the top, fading into the page.
        s.icon?.let { bmp ->
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.55f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (wide) 420.dp else 300.dp)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.9f), 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
        val main: @Composable (Modifier) -> Unit = { mod ->
            Column(modifier = mod, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .deckFocusRing(RoundedCornerShape(16.dp), scaleTo = 1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(deckGlass())
                            .border(1.dp, deckLine(), RoundedCornerShape(16.dp))
                            .clickable(onClick = onBack)
                            .size(52.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                    }
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Filled.Storefront, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = (storeLabelOf(s) + if (LinuxShortcuts.isLinuxEntry(s)) " · Linux client" else "").uppercase(),
                        color = cs.primary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        letterSpacing = 2.sp,
                    )
                }
                Text(
                    text = s.name,
                    color = cs.onSurface,
                    fontFamily = SoraFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (wide) 48.sp else 32.sp,
                    lineHeight = if (wide) 52.sp else 36.sp,
                    letterSpacing = (-0.8).sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The container chip opens that container's settings.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .deckFocusRing(RoundedCornerShape(16.dp), scaleTo = 1.03f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, deckLine(), RoundedCornerShape(16.dp))
                            .clickable { onOpenContainer(s.container.id) }
                            .heightIn(min = 38.dp)
                            .padding(horizontal = 14.dp),
                    ) {
                        Icon(Icons.Filled.Inventory2, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(17.dp))
                        Text(s.container.name, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    s.getExtra("dxwrapper", s.container.getDXWrapper()).takeIf { it.isNotBlank() }?.let { DeckPill(it.uppercase()) }
                    s.getExtra("launchMode", "").takeIf { it.isNotBlank() }?.let {
                        DeckPill(when (it) { "RealSteam" -> "SteamLite"; else -> it })
                    }
                    if (onSd) DeckPill("SD card")
                }
                // Stats: playtime, last played, achievements (Steam, when cached), storage.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val tile = Modifier.widthIn(min = 150.dp, max = 240.dp)
                    DeckStat(playtimeShort(stats.ms), "Playtime", tile)
                    DeckStat(lastPlayedLabel(context, stats.lastPlayed) ?: if (stats.ms > 0L) "Before tracking" else "Never", "Last played", tile)
                    achievements?.let { (got, total) ->
                        DeckStat("$got / $total", "Achievements", tile, progress = got.toFloat() / total.coerceAtLeast(1))
                    }
                    DeckStat(if (stats.count > 0) "${stats.count}" else "—", if (stats.count == 1) "Launch" else "Launches", tile)
                    DeckStat(if (onSd) "SD card" else "Internal", "Storage", tile)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    // Room for the focus glow, which a scroll container would otherwise clip.
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(6.dp),
                ) {
                    DeckButton("Play", Icons.Filled.PlayArrow, { launcher.requestLaunch(s) }, primary = true, big = true, glyph = "A", modifier = Modifier.focusRequester(playRequester))
                    DeckButton("Game settings", Icons.Filled.Tune, { actions.perform(GameMenuAction.SETTINGS, s) }, big = true)
                    DeckButton("Options", Icons.Filled.MoreHoriz, { menuOpen = true }, big = true, glyph = "X")
                }
                remembered?.let { how ->
                    DeckCard {
                        DeckRow(
                            title = "Starts with $how every time",
                            subtitle = "The launch choice is remembered for this game.",
                            icon = Icons.Filled.RocketLaunch,
                            trailing = {
                                DeckButton("Ask every time", Icons.AutoMirrored.Filled.Undo, {
                                    s.putExtra("launchModeRemembered", "0")
                                    s.saveData()
                                    remembered = null
                                })
                            },
                        )
                    }
                }
                // Setup at a glance: does this game follow its container, or carry its own settings?
                val overrides = overrideCount(s)
                val setupShape = RoundedCornerShape(20.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .deckFocusRing(setupShape, scaleTo = 1.01f)
                        .clip(setupShape)
                        .background(cs.primary.copy(alpha = 0.10f))
                        .border(1.dp, cs.primary.copy(alpha = 0.45f), setupShape)
                        .clickable { actions.perform(GameMenuAction.SETTINGS, s) }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Icon(Icons.Filled.AccountTree, contentDescription = null, tint = cs.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (overrides == 0) "Uses its container’s setup"
                                   else "$overrides setting${if (overrides == 1) "" else "s"} set just for this game",
                            color = cs.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                        Text(setupSummary(s), color = cs.onSurfaceVariant, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
                }
                if (details.hasDisplayableDetails()) {
                    Column {
                        DeckSectionLabel("About this game")
                        DeckCard {
                            val facts = listOfNotNull(
                                details.releaseYear,
                                details.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                details.metacritic?.let { "Metacritic $it" },
                            ).joinToString(" · ")
                            DeckRow(
                                title = if (facts.isNotEmpty()) facts else "Details",
                                subtitle = details.description,
                                onClick = { actions.perform(GameMenuAction.GAME_DETAILS, s) },
                            )
                        }
                    }
                }
            }
        }

        if (wide) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = gutter),
            ) {
                main(
                    Modifier
                        .weight(0.62f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(0.38f)
                        .fillMaxHeight()
                        .padding(vertical = 24.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(deckGlass())
                        .border(1.dp, deckLine(), RoundedCornerShape(28.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    DeckSectionLabel("Game menu", Modifier.padding(start = 6.dp))
                    DeckGameMenuList(s, onPlay = { launcher.requestLaunch(s) }, onAction = { actions.perform(it, s) }, onContainer = { onOpenContainer(s.container.id) })
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = gutter, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                main(Modifier.fillMaxWidth())
                Column {
                    DeckSectionLabel("Game menu")
                    DeckGameMenuList(s, onPlay = { launcher.requestLaunch(s) }, onAction = { actions.perform(it, s) }, onContainer = { onOpenContainer(s.container.id) })
                }
            }
        }
    }

    if (menuOpen) {
        DeckGameMenuSheet(
            shortcut = s,
            subtitle = storeLabelOf(s) + " · " + playtimeLabel(stats.ms),
            onDismiss = { menuOpen = false },
            onPlay = { menuOpen = false; launcher.requestLaunch(s) },
            onAction = { menuOpen = false; actions.perform(it, s) },
            onContainer = { menuOpen = false; onOpenContainer(s.container.id) },
        )
    }

    ShortcutActionDialogs(actions)
    ShortcutLaunchDialogs(launcher)
}

/** One Deck game-menu entry: a classic ⋮ item (or Play / Container settings) with a line saying what it does. */
private data class GameMenuEntry(val title: String, val hint: String, val action: GameMenuAction?, val danger: Boolean = false)

/** Pseudo-actions the classic ⋮ menu doesn't have. */
private const val ENTRY_PLAY = "play"
private const val ENTRY_CONTAINER = "container"

/**
 * The classic ⋮ menu for [s] in Deck groups: same items, same visibility rules (Cloud Saves for Steam
 * games, Back up / Restore saves for the others), plus Play and Container settings at the top.
 */
private fun gameMenuGroups(s: Shortcut): List<List<Pair<String?, GameMenuEntry>>> {
    val steam = isSteamOriginShortcut(s)
    val custom = isCustomShortcut(s)
    fun e(a: GameMenuAction, hint: String, danger: Boolean = false) = null as String? to GameMenuEntry(menuTitle(a), hint, a, danger)
    return listOf(
        listOf(ENTRY_PLAY to GameMenuEntry("Play", "Same launch path from every view: launch options, Steam pre-flight, Goldberg, cloud-save sync.", null)),
        listOf(
            e(GameMenuAction.SETTINGS, "Display, graphics, controls… everything shows whether it follows the container or is set for this game."),
            ENTRY_CONTAINER to GameMenuEntry("Container settings", "Opens the container this game runs in.", null),
        ),
        listOfNotNull(
            e(GameMenuAction.GAME_DETAILS, "Name, Steam link, genres, year, description."),
            e(GameMenuAction.SCRAPE_COVER, "Find cover art on SteamGridDB and pick one."),
            e(GameMenuAction.COMMUNITY_CONFIGS, "Settings other players tuned for this game on their devices."),
        ),
        listOfNotNull(
            if (steam) e(GameMenuAction.CLOUD_SAVES, "Steam Cloud sync for this game.") else null,
            if (custom) e(GameMenuAction.BACKUP_SAVES, "Zip this game's saves into its backup folder.") else null,
            if (custom) e(GameMenuAction.RESTORE_SAVES, "Pick a save backup and restore it into a container.") else null,
            e(GameMenuAction.VIEW_LOGS, "This game's captured logs."),
            e(GameMenuAction.PROPERTIES, "Times played and total playtime."),
        ),
        listOf(
            e(GameMenuAction.CLONE, "Copy this shortcut into another container."),
            e(GameMenuAction.COPY_TO_DRIVE_C, "Copy the game folder onto the container's C: drive and repoint it."),
            e(GameMenuAction.CHANGE_EXE, "Point the shortcut at a different .exe in the game's folder."),
            e(GameMenuAction.ADD_TO_HOME, "Pin a launcher icon to Android's home screen."),
            e(GameMenuAction.EXPORT, "Save this shortcut to your export folder."),
        ),
        listOf(e(GameMenuAction.REMOVE, "Asks first. Only the shortcut goes; the game files stay.", danger = true)),
    )
}

private fun menuTitle(a: GameMenuAction): String = when (a) {
    GameMenuAction.SETTINGS -> "Game settings"
    GameMenuAction.GAME_DETAILS -> "Game details"
    GameMenuAction.SCRAPE_COVER -> "Cover art"
    GameMenuAction.COMMUNITY_CONFIGS -> "Community configs for this game"
    GameMenuAction.CLOUD_SAVES -> "Cloud saves"
    GameMenuAction.VIEW_LOGS -> "View logs"
    else -> a.label
}

/** The grouped game menu, as cards of rows. Used on the game page's side column and in [DeckGameMenuSheet]. */
@Composable
internal fun DeckGameMenuList(
    s: Shortcut,
    onPlay: () -> Unit,
    onAction: (GameMenuAction) -> Unit,
    onContainer: () -> Unit,
    firstRequester: FocusRequester? = null,
) {
    val groups = remember(s) { gameMenuGroups(s) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        groups.forEachIndexed { gi, group ->
            DeckCard {
                group.forEachIndexed { i, (key, entry) ->
                    if (i > 0) DeckRowDivider()
                    DeckRow(
                        title = entry.title,
                        subtitle = entry.hint,
                        danger = entry.danger,
                        modifier = if (gi == 0 && i == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier,
                        onClick = {
                            when {
                                key == ENTRY_PLAY -> onPlay()
                                key == ENTRY_CONTAINER -> onContainer()
                                entry.action != null -> onAction(entry.action)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The game menu as a right-hand sheet (a bottom-anchored full-width one on a phone): cover, title and
 * a line of facts on top, the grouped menu below. X / Start opens it on Home and on the game page.
 */
@Composable
internal fun DeckGameMenuSheet(
    shortcut: Shortcut,
    subtitle: String,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onAction: (GameMenuAction) -> Unit,
    onContainer: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val compact = deckCompact()
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstRequester.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Box(
            contentAlignment = if (compact) Alignment.BottomCenter else Alignment.CenterEnd,
            modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
        ) {
            val shape = if (compact) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
            Column(
                modifier = Modifier
                    .then(if (compact) Modifier.fillMaxWidth().fillMaxHeight(0.9f) else Modifier.fillMaxHeight().widthIn(max = 520.dp).fillMaxWidth(0.5f))
                    .clip(shape)
                    .background(cs.surfaceContainerHigh)
                    .border(1.dp, deckLine(), shape)
                    // Swallow taps so they don't reach the dismiss scrim behind the sheet.
                    .clickable(onClick = {}, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() })
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Game menu", color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .deckFocusRing(RoundedCornerShape(14.dp), scaleTo = 1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onDismiss)
                            .size(44.dp),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = cs.onSurface)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 14.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(width = 54.dp, height = 76.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant),
                    ) {
                        val bmp = shortcut.icon
                        if (bmp != null) {
                            val img = remember(bmp) { bmp.asImageBitmap() }
                            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = cs.primary)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(shortcut.name, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, color = cs.onSurfaceVariant, fontSize = 13.5.sp, maxLines = 1)
                    }
                }
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                    DeckGameMenuList(shortcut, onPlay, onAction, onContainer, firstRequester)
                }
            }
        }
    }
}
