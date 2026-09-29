package com.winlator.star.ui.deck

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.container.Shortcut
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.ui.deck.settings.DeckSettingsBus
import com.winlator.star.ui.screens.GameMenuAction
import com.winlator.star.ui.screens.ShortcutLaunchDialogs
import com.winlator.star.ui.screens.ShortcutSettingsDialogScreen
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.addToHomeScreen
import com.winlator.star.ui.screens.exportShortcut
import com.winlator.star.ui.screens.isAmazonShortcut
import com.winlator.star.ui.screens.isCustomOriginShortcut
import com.winlator.star.ui.screens.isCustomShortcut
import com.winlator.star.ui.screens.isGogShortcut
import com.winlator.star.ui.screens.isSteamOriginShortcut
import com.winlator.star.ui.screens.launchSaveManager
import com.winlator.star.ui.screens.rememberShortcutLauncher
import com.winlator.star.ui.screens.steamAppIdOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Total playtime (ms) and play count for one game, from the prefs XServerDisplayActivity writes. */
private data class PlayStats(val ms: Long, val count: Int)

private fun readPlayStats(context: Context, shortcuts: List<Shortcut>): Map<String, PlayStats> {
    val prefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
    return shortcuts.associate { s ->
        s.file.path to PlayStats(prefs.getLong("${s.name}_playtime", 0L), prefs.getInt("${s.name}_play_count", 0))
    }
}

private fun playtimeLabel(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        ms <= 0L -> "Not played yet"
        minutes < 60L -> "${minutes.coerceAtLeast(1L)} min played"
        else -> "%.1f h played".format(ms / 3_600_000.0)
    }
}

/** Store shelves in the order the Stores tab lists them. Linux runtime entries sit in no store shelf. */
private val STORE_SHELVES: List<Pair<String, (Shortcut) -> Boolean>> = listOf(
    "Steam" to { s: Shortcut -> isSteamOriginShortcut(s) && !LinuxShortcuts.isLinuxEntry(s) },
    "Epic Games" to { s: Shortcut -> s.getExtra("storeSource") == "epic" },
    "GOG" to { s: Shortcut -> isGogShortcut(s) },
    "Amazon Games" to { s: Shortcut -> isAmazonShortcut(s) },
    "Added by me" to { s: Shortcut -> isCustomOriginShortcut(s) },
)

private fun storeLabelOf(s: Shortcut): String = when {
    LinuxShortcuts.isLinuxEntry(s) -> "Linux"
    else -> STORE_SHELVES.firstOrNull { it.second(s) }?.first ?: "Added by me"
}

/**
 * Two backdrop tints from a cover: the average of its top-right and bottom-left quarters, pushed to a
 * readable saturation and brightness. Runs off the main thread; null when the bitmap can't be read.
 */
private fun coverTints(bmp: Bitmap): Pair<Color, Color>? = runCatching {
    val small = Bitmap.createScaledBitmap(bmp, 12, 12, true)
    fun avg(x0: Int, y0: Int): Color {
        var r = 0; var g = 0; var b = 0; var n = 0
        for (x in x0 until x0 + 6) for (y in y0 until y0 + 6) {
            val p = small.getPixel(x, y)
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r / n, g / n, b / n, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.45f)
        hsv[2] = 0.55f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
    val pair = avg(6, 0) to avg(0, 6)
    if (small !== bmp) small.recycle()
    pair
}.getOrNull()

/**
 * Deck home: a big hero for the focused game plus horizontal shelves. Games come from the same
 * ShortcutsViewModel the Games screen uses, and Play goes through the shared ShortcutLauncher, so a
 * launch from here is the Games screen's launch (EA checks, launch-method sheet, SteamLite pre-flight,
 * Goldberg, cloud pull). The ⋮ actions whose dialogs live inside the Games screen are handed to it
 * through [onGameAction]; Settings, Add to home screen, Export and Cloud Saves run right here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DeckHome(
    onGameAction: (GameMenuAction, Shortcut?) -> Unit,
    onOpenLibrary: () -> Unit,
    vm: ShortcutsViewModel = viewModel(),
) {
    val shortcuts by vm.shortcuts.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val compact = cfg.screenWidthDp < DECK_COMPACT_WIDTH_DP
    val launcher = rememberShortcutLauncher()
    // A SteamLite launch that failed inside the container asks for a relaunch.
    // The Deck home is the library this shell lands on, so it picks that up the same way the Games screen does.
    LaunchedEffect(shortcuts) { launcher.resumePendingRelaunch(shortcuts) }

    // Refresh whenever the app resumes, as the Games screen does, so playtime and new games show up.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val stats = remember(shortcuts) { readPlayStats(context, shortcuts) }
    val continuePlaying = remember(shortcuts, stats) {
        shortcuts.filter { (stats[it.file.path]?.ms ?: 0L) > 0L }
            .sortedByDescending { stats[it.file.path]?.ms ?: 0L }
            .take(12)
    }
    val recentlyAdded = remember(shortcuts) {
        shortcuts.sortedByDescending { runCatching { it.file.lastModified() }.getOrDefault(0L) }.take(12)
    }
    val storeShelves = remember(shortcuts) {
        STORE_SHELVES.map { (label, test) -> label to shortcuts.filter(test) }
            .filter { (_, list) -> list.isNotEmpty() && list.size < shortcuts.size }
    }

    var focusedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val focused = shortcuts.firstOrNull { it.file.path == focusedPath }
        ?: continuePlaying.firstOrNull()
        ?: shortcuts.firstOrNull()
    var optionsFor by remember { mutableStateOf<Shortcut?>(null) }
    var settingsShortcut by remember { mutableStateOf<Shortcut?>(null) }

    // X / Start on this page = the focused game's options.
    val deckActions = LocalDeckActions.current
    val focusedNow by rememberUpdatedState(focused)
    DisposableEffect(deckActions) {
        val open: () -> Unit = { focusedNow?.let { optionsFor = it } }
        deckActions.options.value = open
        deckActions.optionsLabel.value = "Options"
        onDispose { if (deckActions.options.value === open) deckActions.options.value = null }
    }

    // Backdrop tint follows the focused game's cover.
    val ambient = LocalDeckAmbient.current
    val tints by produceState<Pair<Color, Color>?>(null, focused?.file?.path, focused?.icon) {
        val bmp = focused?.icon
        value = if (bmp == null) null else withContext(Dispatchers.Default) { coverTints(bmp) }
    }
    LaunchedEffect(tints) { ambient.value = tints }
    DisposableEffect(ambient) { onDispose { ambient.value = null } }

    val gutter = if (compact) 16.dp else 24.dp
    val playRequester = remember { FocusRequester() }
    val addRequester = remember { FocusRequester() }
    val hasGames = shortcuts.isNotEmpty()
    // Initial focus: the hero's Play button (or "Add a game" when the library is empty), once it exists.
    LaunchedEffect(hasGames) {
        withFrameNanos { }
        runCatching { if (hasGames) playRequester.requestFocus() else addRequester.requestFocus() }
    }

    if (!hasGames) {
        DeckEmptyLibrary(
            modifier = Modifier.fillMaxSize().padding(gutter),
            addRequester = addRequester,
            onAddGame = { onGameAction(GameMenuAction.ADD_GAME, null) },
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = gutter, vertical = 16.dp),
        ) {
            val heroHeight = (cfg.screenHeightDp * 0.44f).coerceIn(210f, 300f).dp
            focused?.let { s ->
                val st = stats[s.file.path] ?: PlayStats(0L, 0)
                DeckHero(
                    shortcut = s,
                    eyebrow = when {
                        st.ms > 0L && continuePlaying.firstOrNull() == s -> "Continue playing"
                        st.ms > 0L -> "In your library"
                        else -> "Ready to play"
                    },
                    meta = buildList {
                        add(Icons.Filled.Storefront to storeLabelOf(s))
                        add(Icons.Filled.Schedule to playtimeLabel(st.ms))
                        if (st.count > 0) add(Icons.Filled.SportsEsports to if (st.count == 1) "Played once" else "Played ${st.count} times")
                        s.container?.name?.takeIf { it.isNotEmpty() }?.let { add(Icons.Filled.Inventory2 to it) }
                    },
                    height = heroHeight,
                    compact = compact,
                    playRequester = playRequester,
                    onPlay = { launcher.requestLaunch(s) },
                    onSettings = { settingsShortcut = s },
                    onOptions = { optionsFor = s },
                    onLibrary = onOpenLibrary,
                )
            }
            Spacer(Modifier.height(12.dp))
            val onFocus: (Shortcut) -> Unit = { focusedPath = it.file.path }
            val onPlay: (Shortcut) -> Unit = { launcher.requestLaunch(it) }
            val onOptions: (Shortcut) -> Unit = { optionsFor = it }
            if (continuePlaying.isNotEmpty()) {
                DeckShelf("Continue playing", "continue", continuePlaying, stats, onFocus, onPlay, onOptions)
            }
            DeckShelf("Installed", "installed", shortcuts, stats, onFocus, onPlay, onOptions)
            storeShelves.forEach { (label, list) ->
                DeckShelf(label, "store-$label", list, stats, onFocus, onPlay, onOptions)
            }
            if (shortcuts.size > 1) {
                DeckShelf("Recently added", "recent", recentlyAdded, stats, onFocus, onPlay, onOptions)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    optionsFor?.let { s ->
        DeckGameOptionsDialog(
            shortcut = s,
            onDismiss = { optionsFor = null },
            onAction = { action ->
                optionsFor = null
                when (action) {
                    // The Deck settings editor, or the classic dialog if the editor's route isn't registered.
                    GameMenuAction.SETTINGS -> DeckSettingsBus.openGame?.invoke(s.file.path) ?: run { settingsShortcut = s }
                    GameMenuAction.ADD_TO_HOME -> addToHomeScreen(context, s)
                    GameMenuAction.EXPORT -> exportShortcut(context, s)
                    GameMenuAction.CLOUD_SAVES -> launchSaveManager(context, steamAppIdOf(s))
                    else -> onGameAction(action, s)
                }
            },
        )
    }

    // The same per-game editor the Games screen opens.
    // Its "Move to Drive C" and "Change executable" buttons hand off to the Games screen, which owns those two flows.
    settingsShortcut?.let { s ->
        ShortcutSettingsDialogScreen(
            shortcut = s,
            onDismiss = { settingsShortcut = null; vm.refresh() },
            onMoveToDriveC = { settingsShortcut = null; onGameAction(GameMenuAction.COPY_TO_DRIVE_C, s) },
            onChangeExe = { settingsShortcut = null; onGameAction(GameMenuAction.CHANGE_EXE, s) },
        )
    }

    ShortcutLaunchDialogs(launcher)
}

@Composable
private fun DeckHero(
    shortcut: Shortcut,
    eyebrow: String,
    meta: List<Pair<ImageVector, String>>,
    height: Dp,
    compact: Boolean,
    playRequester: FocusRequester,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onOptions: () -> Unit,
    onLibrary: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(shape)
            .background(cs.surface)
            .border(1.dp, deckLine(), shape),
    ) {
        // Wide cover on the right, fading in from the left so the title always sits on plain surface.
        Crossfade(
            targetState = shortcut.icon,
            label = "deckHeroArt",
            // matchParentSize: the art follows the hero's height instead of setting it.
            modifier = Modifier.matchParentSize(),
        ) { bmp ->
            if (bmp != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(if (compact) 1f else 0.7f)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = if (compact) Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)
                                        else Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(if (compact) 1f else 0.62f)
                .widthIn(max = 620.dp)
                .padding(horizontal = if (compact) 18.dp else 26.dp, vertical = if (compact) 18.dp else 24.dp),
        ) {
            Text(
                text = eyebrow.uppercase(),
                color = cs.primary,
                fontFamily = NunitoSansFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
            )
            Text(
                text = shortcut.name,
                color = cs.onSurface,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 26.sp else 36.sp,
                lineHeight = if (compact) 28.sp else 38.sp,
                letterSpacing = (-0.5).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                meta.forEach { (icon, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState())
                    // Room for the focus glow, which a scroll container would otherwise clip.
                    .padding(6.dp),
            ) {
                DeckButton("Play", Icons.Filled.PlayArrow, onPlay, primary = true, big = true, modifier = Modifier.focusRequester(playRequester))
                DeckButton("Settings", Icons.Filled.Tune, onSettings)
                DeckButton("Options", Icons.Filled.MoreHoriz, onOptions, glyph = "X")
                DeckButton("All games", Icons.Filled.Apps, onLibrary)
            }
        }
    }
}

@Composable
private fun DeckShelf(
    title: String,
    key: String,
    games: List<Shortcut>,
    stats: Map<String, PlayStats>,
    onFocus: (Shortcut) -> Unit,
    onPlay: (Shortcut) -> Unit,
    onOptions: (Shortcut) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(title, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.width(10.dp))
            Text("${games.size}", color = cs.onSurfaceVariant, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(games, key = { "$key:${it.file.path}" }) { s ->
                DeckGameCard(
                    shortcut = s,
                    subtitle = playtimeLabel(stats[s.file.path]?.ms ?: 0L),
                    onFocus = { onFocus(s) },
                    onClick = { onPlay(s) },
                    onLongClick = { onOptions(s) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckGameCard(
    shortcut: Shortcut,
    subtitle: String,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "deckCardScale")
    Column(
        modifier = Modifier
            .width(128.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                if (it.isFocused && !focused) onFocus()
                focused = it.isFocused
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(
                    if (focused) Modifier
                        .shadow(18.dp, shape, ambientColor = cs.primary, spotColor = cs.primary)
                        .border(3.dp, cs.primary, shape)
                    else Modifier.shadow(6.dp, shape)
                )
                .clip(shape)
                .background(cs.surfaceVariant),
        ) {
            val bmp = shortcut.icon
            if (bmp != null) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = cs.primary, modifier = Modifier.size(40.dp))
            }
        }
        Text(
            text = shortcut.name,
            color = if (focused) cs.primary else cs.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            text = subtitle,
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun DeckEmptyLibrary(modifier: Modifier, addRequester: FocusRequester, onAddGame: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(deckCardFill())
                .border(1.dp, deckLine(), shape)
                .padding(28.dp),
        ) {
            Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = cs.primary, modifier = Modifier.size(48.dp))
            Text("Your library is empty", color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(
                "Add a game you already have on this device, or install one from a store on the Stores tab.",
                color = cs.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(6.dp))
            DeckButton("Add a game", Icons.Filled.Add, onAddGame, primary = true, big = true, modifier = Modifier.focusRequester(addRequester))
        }
    }
}

/** The classic ⋮ menu as a controller-friendly list. Same items, same order, same visibility rules. */
@Composable
private fun DeckGameOptionsDialog(shortcut: Shortcut, onDismiss: () -> Unit, onAction: (GameMenuAction) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val steam = remember(shortcut) { isSteamOriginShortcut(shortcut) }
    val custom = remember(shortcut) { isCustomShortcut(shortcut) }
    val items = remember(shortcut) {
        GameMenuAction.entries.filter {
            when (it) {
                GameMenuAction.ADD_GAME -> false
                GameMenuAction.CLOUD_SAVES -> steam
                GameMenuAction.BACKUP_SAVES, GameMenuAction.RESTORE_SAVES -> custom
                else -> true
            }
        }
    }
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstRequester.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(22.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(cs.surfaceContainerHigh)
                .border(1.dp, deckLine(), shape)
                .padding(vertical = 14.dp),
        ) {
            Text(
                text = shortcut.name,
                color = cs.onSurface,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                items.forEachIndexed { i, action ->
                    val rowShape = RoundedCornerShape(12.dp)
                    var rowFocused by remember { mutableStateOf(false) }
                    val danger = action == GameMenuAction.REMOVE
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (i == 0) Modifier.focusRequester(firstRequester) else Modifier)
                            .onFocusChanged { rowFocused = it.isFocused }
                            .clip(rowShape)
                            .then(
                                if (rowFocused) Modifier
                                    .background(cs.primary.copy(alpha = 0.16f))
                                    .border(2.dp, cs.primary, rowShape)
                                else Modifier
                            )
                            .clickable { onAction(action) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                    ) {
                        Icon(
                            imageVector = iconFor(action),
                            contentDescription = null,
                            tint = if (danger) cs.error else cs.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = action.label,
                            color = if (danger) cs.error else cs.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun iconFor(action: GameMenuAction): ImageVector = when (action) {
    GameMenuAction.SETTINGS -> Icons.Filled.Settings
    GameMenuAction.REMOVE -> Icons.Filled.Delete
    GameMenuAction.CLONE -> Icons.Filled.ContentCopy
    GameMenuAction.COPY_TO_DRIVE_C -> Icons.Filled.DriveFileMove
    GameMenuAction.CHANGE_EXE -> Icons.Filled.SwapHoriz
    GameMenuAction.ADD_TO_HOME -> Icons.Filled.AddToHomeScreen
    GameMenuAction.EXPORT -> Icons.Filled.Upload
    GameMenuAction.GAME_DETAILS -> Icons.Filled.Edit
    GameMenuAction.CLOUD_SAVES -> Icons.Filled.CloudSync
    GameMenuAction.BACKUP_SAVES -> Icons.Filled.Archive
    GameMenuAction.RESTORE_SAVES -> Icons.Filled.Unarchive
    GameMenuAction.SCRAPE_COVER -> Icons.Filled.Search
    GameMenuAction.COMMUNITY_CONFIGS -> Icons.Filled.Public
    GameMenuAction.VIEW_LOGS -> Icons.Filled.Description
    GameMenuAction.PROPERTIES -> Icons.Filled.Info
    GameMenuAction.ADD_GAME -> Icons.Filled.Add
}

/** A Deck button: rounded, bold label, optional leading icon and trailing controller glyph. */
@Composable
internal fun DeckButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    big: Boolean = false,
    glyph: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val ink = if (primary) cs.onPrimary else cs.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .deckFocusRing(shape, scaleTo = 1.04f)
            .clip(shape)
            .background(if (primary) cs.primary else cs.surfaceContainerHigh)
            .then(if (primary) Modifier else Modifier.border(1.dp, deckLine(), shape))
            .clickable(onClick = onClick)
            .heightIn(min = if (big) 48.dp else 40.dp)
            .padding(horizontal = if (big) 22.dp else 15.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(if (big) 22.dp else 18.dp))
        Text(
            text = label,
            color = ink,
            fontFamily = NunitoSansFamily,
            fontWeight = if (big) FontWeight.ExtraBold else FontWeight.Bold,
            fontSize = if (big) 16.sp else 13.5.sp,
            maxLines = 1,
        )
        if (glyph != null) DeckGlyph(glyph, GlyphKind.X)
    }
}
