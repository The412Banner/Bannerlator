package com.winlator.star.ui.deck

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.FeatureFlags
import com.winlator.star.R
import com.winlator.star.store.InGameFriendsSource
import com.winlator.star.ui.ControlsContent
import com.winlator.star.ui.FexMode
import com.winlator.star.ui.FrameGenSection
import com.winlator.star.ui.FriendsContent
import com.winlator.star.ui.GraphicsEffectsSection
import com.winlator.star.ui.HudContent
import com.winlator.star.ui.LinuxComponentsContent
import com.winlator.star.ui.LinuxSteamSection
import com.winlator.star.ui.PerformanceDashboardDialog
import com.winlator.star.ui.ProcessorAffinityDialog
import com.winlator.star.ui.ReshadeSection
import com.winlator.star.ui.TmStatGrid
import com.winlator.star.ui.TvContent
import com.winlator.star.ui.XServerDialogState
import com.winlator.star.ui.XServerDrawerState
import com.winlator.star.ui.deck.DeckInGameState.MenuTab
import com.winlator.star.ui.deck.DeckInGameState.Panel
import com.winlator.star.ui.deck.DeckInGameState.QuickTab
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// The Deck in-game UI: a quick menu that slides in from the right and a full game menu on the left over the dimmed
// game. It sits in its own full-screen ComposeView above the game (XServerDisplayActivity adds it only in Deck style),
// draws nothing while both panels are closed, and so never takes a touch from the game then.

fun setupDeckInGame(view: ComposeView) {
    view.setContent {
        WinlatorTheme {
            DeckTheme {
                DeckInGameOverlay()
            }
        }
    }
}

@Composable
fun DeckInGameOverlay() {
    val panel by DeckInGameState.panel.collectAsState()
    val corner by DeckInGameState.cornerButton.collectAsState()
    val menuOpen = panel == Panel.MENU
    val quickOpen = panel == Panel.QUICK
    // Hoisted so both panels share one exit confirmation.
    var confirmExit by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val menuWidth = if (compact) maxWidth else (maxWidth * 0.86f).coerceAtMost(1100.dp)
        val quickWidth = if (compact) maxWidth else (maxWidth * 0.42f).coerceIn(340.dp, 470.dp)
        CompositionLocalProvider(LocalIgCompact provides compact) {
            // The corner button: faint, top right, only while nothing is open. Off unless the user turns it on.
            if (corner && panel == Panel.NONE) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .size(44.dp)
                        .alpha(0.55f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .clickable { DeckInGameState.openQuick() },
                ) {
                    Icon(Icons.Outlined.MoreHoriz, contentDescription = "Open the quick menu", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            // Behind a panel: the full menu dims the game, the quick menu leaves it visible. A tap there closes either one.
            if (panel != Panel.NONE) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (menuOpen) Color.Black.copy(alpha = 0.55f) else Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { DeckInGameState.close() }
                )
            }

            AnimatedVisibility(
                visible = menuOpen,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                DeckInGameMenu(Modifier.width(menuWidth).fillMaxHeight(), onExit = { confirmExit = true })
            }

            AnimatedVisibility(
                visible = quickOpen,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                DeckQuickMenu(Modifier.width(quickWidth).fillMaxHeight(), onExit = { confirmExit = true })
            }
        }
    }

    if (confirmExit) {
        IgConfirmDialog(
            title = "Exit the game?",
            body = "The game closes and Bannerlator saves and syncs as usual. Progress the game itself hasn't saved is lost.",
            confirmLabel = "Exit game",
            confirmIcon = Icons.AutoMirrored.Outlined.Logout,
            onConfirm = {
                confirmExit = false
                DeckInGameState.close()
                XServerDrawerState.onExit?.run()
            },
            onDismiss = { confirmExit = false },
        )
    }
}

/** Puts controller focus on [requester] whenever the panel asks for it (opened, page changed, a stray d-pad press). */
@Composable
private fun FocusOnRequest(requester: FocusRequester) {
    val tick by DeckInGameState.focusRequest.collectAsState()
    LaunchedEffect(tick) {
        // One frame for a just-switched page to lay out before focus moves.
        delay(60)
        runCatching { requester.requestFocus() }
    }
}

/** Pause mirrors the Classic rail: freeze (or resume) the game and close the menu so the Paused pill shows. */
private fun pauseAndClose() {
    XServerDrawerState.onPauseResume?.run()
    DeckInGameState.close()
}

// ───────────────────────────── Quick menu ─────────────────────────────

@Composable
private fun DeckQuickMenu(modifier: Modifier, onExit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tab by DeckInGameState.quickTab.collectAsState()
    val title by DeckInGameState.gameTitle.collectAsState()
    val friendsSource by InGameFriendsSource.state.collectAsState()
    val isPaused by XServerDrawerState.isPaused.collectAsState()
    val tabs = DeckInGameState.availableQuickTabs(friendsSource.tabVisible)
    val shown = if (tab in tabs) tab else QuickTab.QUICK
    val tabFocus = remember { FocusRequester() }
    FocusOnRequest(tabFocus)

    Column(
        modifier = modifier
            .background(cs.background.copy(alpha = 0.97f))
            .border(1.dp, deckLine().copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(top = 18.dp),
    ) {
        // Header: the game, device temperature and battery.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Text(
                title.ifEmpty { "In game" },
                color = cs.onSurface,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DeviceStatus()
        }
        Spacer(Modifier.height(14.dp))

        // Icon tabs between the bumper glyphs.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            DeckGlyph("L1", GlyphKind.BUMPER)
            tabs.forEach { t ->
                val sel = t == shown
                val shape = RoundedCornerShape(16.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(if (sel) Modifier.focusRequester(tabFocus) else Modifier)
                        .deckFocusRing(shape)
                        .clip(shape)
                        .background(if (sel) cs.primary else Color.Transparent)
                        .clickable { DeckInGameState.selectQuickTab(t) }
                        .size(width = 58.dp, height = 50.dp),
                ) {
                    Icon(quickTabIcon(t), contentDescription = quickTabTitle(t), tint = if (sel) cs.onPrimary else cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
            }
            DeckGlyph("R1", GlyphKind.BUMPER)
        }

        Text(
            quickTabTitle(shown),
            color = cs.onSurface,
            fontFamily = SoraFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 23.sp,
            modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 6.dp),
        )

        val bodyMod = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)
        if (shown == QuickTab.FRIENDS) {
            // The roster owns its scrolling (a LazyColumn), so it gets the space without a scroll wrapper.
            Box(bodyMod.padding(bottom = 8.dp)) { FriendsContent(XServerDrawerState) }
        } else {
            Column(bodyMod.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                when (shown) {
                    QuickTab.QUICK -> QuickControls()
                    QuickTab.TOOLS -> QuickTools()
                    QuickTab.OPENERS -> OpenersPage()
                    QuickTab.FRIENDS -> Unit
                }
            }
        }

        // Action bar: the full menu (X swaps to it), pause, exit.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(cs.surface.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            IgButton("Game menu", Icons.Outlined.Menu, { DeckInGameState.openMenu() }, modifier = Modifier.weight(1f), primary = true, glyph = "X")
            IgButton(null, if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, ::pauseAndClose)
            IgButton(null, Icons.AutoMirrored.Outlined.Logout, onExit, danger = true)
        }
    }
}

private fun quickTabTitle(t: QuickTab) = when (t) {
    QuickTab.QUICK -> "Quick menu"
    QuickTab.TOOLS -> "Tools"
    QuickTab.FRIENDS -> "Friends"
    QuickTab.OPENERS -> "Opening this menu"
}

private fun quickTabIcon(t: QuickTab): ImageVector = when (t) {
    QuickTab.QUICK -> Icons.Outlined.Bolt
    QuickTab.TOOLS -> Icons.Outlined.Build
    QuickTab.FRIENDS -> Icons.Outlined.People
    QuickTab.OPENERS -> Icons.Outlined.TouchApp
}

/** Battery level and temperature from the sticky battery broadcast, refreshed while the quick menu is open. */
@Composable
private fun DeviceStatus() {
    val ctx = LocalContext.current
    var level by remember { mutableStateOf(-1) }
    var tempC by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(Unit) {
        while (true) {
            val i = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            if (i != null) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                level = if (l >= 0 && s > 0) l * 100 / s else -1
                val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                tempC = if (t != Int.MIN_VALUE) t / 10f else -1f
            }
            delay(10_000)
        }
    }
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (tempC >= 0f) {
            Icon(Icons.Outlined.Thermostat, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text("${tempC.roundToInt()}°C", color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
        }
        if (level >= 0) {
            Icon(Icons.Outlined.BatteryFull, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text("$level%", color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/** The quick menu's main page: the live controls people change most mid-game, each wired to the Classic drawer's code. */
@Composable
private fun QuickControls() {
    val state = XServerDrawerState
    IgCard {
        FrameLimitRow(state, compactHint = true)
        IgDivider()
        FrameGenQuickRow(state)
        IgDivider()
        HudQuickRows(state)
        IgDivider()
        FitToScreenRow(state)
        BrightnessRow()
        IgDivider()
        IgRow(
            "Restart audio",
            hint = "Fixes crackle or silence without restarting the game.",
            scopes = listOf(SaveScope.SESSION),
        ) { IgButton("Restart", Icons.Outlined.VolumeUp, { state.onResetAudio?.run() }) }
        IgDivider()
        MouseQuickRows(state)
        IgDivider()
        OnScreenControlsRow()
    }
}

// ───────────────────────────── Shared rows (quick menu + full menu) ─────────────────────────────

private const val CAP_OFF = -1
private const val CAP_CUSTOM = -2

/**
 * One frame-limit control: Off, the usual caps, or Custom with a slider. Writes the same flows and fires the same
 * onFpsLimitChange the Classic HUD tab's Limit FPS switch, Max FPS slider and preset chips do.
 */
@Composable
private fun FrameLimitRow(state: XServerDrawerState, compactHint: Boolean = false) {
    val enabled by state.fpsLimiterEnabled.collectAsState()
    val limit by state.fpsLimit.collectAsState()
    val nativeFgLocks by state.nativeFgLocks.collectAsState()
    val fgEngine by state.frameGenEngine.collectAsState()
    val fgEnabled by state.frameGenEnabled.collectAsState()
    val fgMult by state.frameGenMultiplier.collectAsState()
    val presets = listOf(30, 40, 45, 60, 72, 90, 120)
    // Keyed on the live values so the row always mirrors what is applied; Custom stays picked while the slider is in use.
    var custom by remember(enabled) { mutableStateOf(enabled && limit !in presets) }
    var sliderVal by remember(limit) { mutableFloatStateOf(limit.toFloat()) }
    val selected = when {
        !enabled -> CAP_OFF
        custom || limit !in presets -> CAP_CUSTOM
        else -> limit
    }
    fun apply(on: Boolean, value: Int) {
        state.setFpsLimiterEnabled(on)
        state.setFpsLimit(value)
        state.onFpsLimitChange?.run()
    }
    val nativeFgName = if (fgEngine == "lsfg-native") "LSFG Native" else "Win-FG Native"
    val lsfgVkMultiplying = fgEngine == "lsfg" && fgEnabled && fgMult >= 2
    val hint = when {
        nativeFgLocks -> "Caps the game's real frames. Locked on while $nativeFgName is generating; you'll see up to $limit × $fgMult = ${limit * fgMult}."
        lsfgVkMultiplying -> "Not applied while lsfg-vk is multiplying: it paces frames itself."
        compactHint -> "Steadier frame pacing and a cooler device."
        else -> "Caps how many frames per second the game draws. Saves battery and evens out stutter."
    }
    IgRow("Frame rate limit", hint = hint, scopes = listOf(igGameScope()), wideControl = true) {
        Column(horizontalAlignment = Alignment.End) {
            IgChips(
                options = listOf(CAP_OFF to "Off") + presets.map { it to "$it" } + listOf(CAP_CUSTOM to "Custom…"),
                selected = selected,
                isEnabled = { it != CAP_OFF || !nativeFgLocks },
            ) { v ->
                when (v) {
                    CAP_OFF -> { custom = false; apply(false, limit) }
                    CAP_CUSTOM -> { custom = true; apply(true, limit) }
                    else -> { custom = false; apply(true, v) }
                }
            }
            if (selected == CAP_CUSTOM) {
                Spacer(Modifier.height(10.dp))
                IgSlider(
                    value = sliderVal,
                    range = 10f..200f,
                    step = 1f,
                    format = { "${it.roundToInt()} fps" },
                    onChange = { sliderVal = it },
                    onFinished = { apply(true, sliderVal.roundToInt()) },
                )
            }
        }
    }
}

/** Frame generation Off / 2× / 3× / 4× (win-fg: Off / On) through the same applyFg path the Classic section uses. */
@Composable
private fun FrameGenQuickRow(state: XServerDrawerState) {
    val fgEnabled by state.frameGenEnabled.collectAsState()
    val mult by state.frameGenMultiplier.collectAsState()
    val engine by state.frameGenEngine.collectAsState()
    val unavailable by state.fgUnavailableReason.collectAsState()
    val wayland by state.isWaylandMode.collectAsState()
    val waylandFgOk by state.waylandFrameGenAvailable.collectAsState()
    val waylandBlocked = wayland && !waylandFgOk
    val scopes = listOf(SaveScope.SESSION)
    val note = "Starts off each launch; the level you pick is kept for this container."
    if (!fgEnabled || waylandBlocked) {
        IgRow(
            "Frame generation",
            hint = if (waylandBlocked) "Not on Wayland in this build yet." else "Off for this container. Turn it on in the container's settings to use it here.",
            enabled = false,
        )
        return
    }
    val options = if (engine == "bionic") listOf(0 to "Off", 2 to "On") else listOf(0 to "Off", 2 to "2×", 3 to "3×", 4 to "4×")
    IgRow(
        "Frame generation",
        hint = if (unavailable.isNotEmpty()) unavailable else "Inserts extra frames for smoother motion. The game pauses for a moment while it restarts.",
        note = note,
        scopes = scopes,
        wideControl = true,
    ) {
        IgSegmented(options, mult, enabled = unavailable.isEmpty()) { m ->
            state.setFrameGenMultiplier(m)
            state.setFrameGenFlowScale(state.frameGenFlowScale.value)
            state.setFrameGenModel(state.frameGenModel.value)
            state.setFrameGenPerfPreset(state.frameGenPerfPreset.value)
            state.onBionicFgConfigChange?.run()
        }
    }
}

/** Parses the HUD config string the same way the Classic HUD tab does. */
private fun parseHudConfig(s: String): Map<String, String> {
    if (s.isEmpty()) return emptyMap()
    val map = LinkedHashMap<String, String>()
    s.split(",").forEach { part ->
        val eq = part.indexOf('=')
        if (eq >= 0) map[part.substring(0, eq)] = part.substring(eq + 1)
    }
    return map
}

/** The live HUD config with [changes] applied and every other key kept as it is, for onFpsConfigApply. */
private fun hudConfigWith(current: String, vararg changes: Pair<String, String>): String {
    val map = LinkedHashMap(parseHudConfig(current))
    changes.forEach { (k, v) -> map[k] = v }
    return map.entries.joinToString(",") { "${it.key}=${it.value}" }
}

@Composable
private fun hudScope(): SaveScope {
    val onShortcut by DeckInGameState.hudSavedToShortcut.collectAsState()
    return if (onShortcut) SaveScope.GAME else SaveScope.CONTAINER
}

/** Overlay on/off and style, sent through onFpsConfigApply like the Classic Show HUD switch and HUD style chips. */
@Composable
private fun HudQuickRows(state: XServerDrawerState) {
    val cfgString by state.fpsConfig.collectAsState()
    val cfg = remember(cfgString) { parseHudConfig(cfgString) }
    val on = (cfg["hudEnabled"] ?: "1") == "1"
    val style = cfg["hudStyle"] ?: "fusion"
    val scope = hudScope()
    IgRow(
        "Show performance overlay",
        hint = "FPS, CPU/GPU load and temperatures on top of the game. Tap it in-game to flip or resize it.",
        scopes = listOf(scope),
    ) {
        IgSwitch(on) { state.onFpsConfigApply?.invoke(hudConfigWith(cfgString, "hudEnabled" to if (it) "1" else "0")) }
    }
    if (on) {
        IgRow("Overlay style", scopes = listOf(scope), wideControl = true) {
            IgChips(
                listOf("classic" to "Classic", "gamehub" to "GameHub", "gamenative" to "GameNative", "fusion" to "Fusion"),
                style,
            ) { state.onFpsConfigApply?.invoke(hudConfigWith(cfgString, "hudStyle" to it)) }
        }
    }
}

@Composable
private fun FitToScreenRow(state: XServerDrawerState) {
    val mode by state.fullscreenMode.collectAsState()
    IgRow("Fit to screen", hint = "How the game picture fills your display.", scopes = listOf(igGameScope()), wideControl = true) {
        IgSegmented(
            listOf(0 to "Original", 1 to "Fit", 2 to "Stretch", 3 to "Fill (crop)", 4 to "Pixel-perfect"),
            mode,
        ) { state.onSetFullscreenMode?.accept(it) }
    }
}

/**
 * Brightness from the screen-effects colour grade, where the renderer has one. It re-sends the other three grade values
 * and the four shader flags unchanged, the same single applier the Classic sliders use (session only, like theirs).
 */
@Composable
private fun BrightnessRow() {
    val ds = XServerDialogState
    val d = XServerDrawerState
    val gl by ds.effectsSupported.collectAsState()
    val vk by ds.vulkanSupported.collectAsState()
    val native by d.nativeRenderingEnabled.collectAsState()
    val wayland by d.isWaylandMode.collectAsState()
    val waylandFxOk by d.waylandEffectsAvailable.collectAsState()
    val fxBlocked = wayland && !waylandFxOk
    val glLive = gl && !native && !fxBlocked
    val vkLive = vk && !fxBlocked
    if (!glLive && !vkLive) return
    val flow = if (glLive) ds.seBrightness else ds.vkBrightness
    val init by flow.collectAsState()
    var value by remember(init) { mutableFloatStateOf(init) }
    fun push() {
        if (glLive) {
            ds.onScreenEffectsApply?.invoke(
                value, ds.seContrast.value, ds.seGamma.value, ds.seSaturation.value,
                ds.seFxaa.value, ds.seCrt.value, ds.seToon.value, ds.seNtsc.value, 0,
            )
        } else {
            ds.onVulkanScreenEffectsApply?.invoke(
                value, ds.vkContrast.value, ds.vkGamma.value, ds.vkSaturation.value,
                ds.vkFxaa.value, ds.vkToon.value, ds.vkCrt.value, ds.vkNtsc.value,
            )
        }
    }
    IgDivider()
    IgRow("Brightness", hint = "Colour-grade brightness on top of the game.", scopes = listOf(SaveScope.SESSION), wideControl = true) {
        IgSlider(
            value = value,
            range = -100f..100f,
            step = 5f,
            format = { "${it.roundToInt()}" },
            onChange = { value = it; push() },
            onFinished = { if (glLive) ds.setSeBrightness(value) else ds.setVkBrightness(value) },
        )
    }
}

/** Relative mouse and Cursor to Touch: the same toggles as the Classic Controls › Mouse chips. */
@Composable
private fun MouseQuickRows(state: XServerDrawerState) {
    val relative by state.isRelativeMouseMovement.collectAsState()
    val cursorToTouch by state.moveCursorToTouchpoint.collectAsState()
    IgRow("Relative mouse", hint = "For games that lock the pointer (camera look).", scopes = listOf(igGameScope())) {
        IgSwitch(relative) { state.onRelativeMouseMovement?.run() }
    }
    IgDivider()
    IgRow("Cursor to touch", hint = "The pointer jumps to where you touch.", scopes = listOf(SaveScope.ALL)) {
        IgSwitch(cursorToTouch) { state.onMoveCursorToTouchpoint?.run() }
    }
}

/** On-screen controls on/off through onInputControlsConfirm, exactly like the Classic Touch Controls chip. */
@Composable
private fun OnScreenControlsRow() {
    val ds = XServerDialogState
    val shown by ds.showTouchscreen.collectAsState()
    IgRow("Show on-screen controls", hint = "The touch buttons of the current layout.", scopes = listOf(SaveScope.SESSION)) {
        IgSwitch(shown) { v ->
            ds.onInputControlsConfirm?.invoke(ds.selectedProfileIdx.value, v, ds.timeoutEnabled.value, ds.hapticsEnabled.value)
            // The confirm callback applies the flag but doesn't echo it; mirror it so this switch and the Controls page agree.
            ds.setShowTouchscreen(v)
        }
    }
}

/** Tool shortcuts: each does what the Classic Advanced / Task Manager entries do, closing the panel first where they do. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickTools() {
    val state = XServerDrawerState
    val castSupported by state.castSupported.collectAsState()
    var perfDialog by remember { mutableStateOf(false) }
    val compact = LocalIgCompact.current
    val closeThen: (Runnable?) -> Unit = { r -> DeckInGameState.close(); r?.run() }
    IgSectionLabel("Tools")
    val tools = buildList<Triple<String, ImageVector, () -> Unit>> {
        add(Triple("Controller test & remap", Icons.Outlined.SportsEsports) { XServerDialogState.show(XServerDialogState.ActiveDialog.CONTROLLER_TEST) })
        add(Triple("Performance dashboard", Icons.Outlined.Speed) { perfDialog = true })
        add(Triple("Live log", Icons.Outlined.Description) { closeThen(state.onLogs) })
        add(Triple("Switch window", Icons.Outlined.ViewCarousel) { closeThen(state.onActiveWindows) })
        add(Triple("Run a program", Icons.Outlined.Terminal) {
            XServerDialogState.onTmDismissed?.run()
            XServerDialogState.onTmNewTask?.run()
        })
        add(Triple("Task manager", Icons.Outlined.ListAlt) { DeckInGameState.openMenuAt(MenuTab.TOOLS) })
        if (FeatureFlags.TV_OUTPUT_ENABLED && castSupported) add(Triple("Cast to a TV", Icons.Outlined.Cast) { state.onOpenCastPicker?.run() })
    }
    ToolTiles(tools, if (compact) 2 else 3)
    IgSectionLabel("View")
    ToolTiles(
        listOf(
            Triple("Magnifier", Icons.Outlined.ZoomIn) { closeThen(state.onMagnifier) },
            Triple("Picture-in-picture", Icons.Outlined.PictureInPictureAlt) { closeThen(state.onPipMode) },
            Triple("Show keyboard", Icons.Outlined.Keyboard) { closeThen(state.onKeyboard) },
        ),
        if (compact) 2 else 3,
    )
    if (perfDialog) PerformanceDashboardDialog(state) { perfDialog = false }
}

@Composable
private fun ToolTiles(tools: List<Triple<String, ImageVector, () -> Unit>>, perRow: Int) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tools.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, icon, action) ->
                    val shape = RoundedCornerShape(16.dp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .deckFocusRing(shape, scaleTo = 1.03f)
                            .clip(shape)
                            .background(deckCardFill())
                            .border(1.dp, deckLine().copy(alpha = 0.45f), shape)
                            .clickable(onClick = action)
                            .heightIn(min = 96.dp)
                            .padding(14.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(label, color = cs.onSurface, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp)
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** How to open these panels, plus the optional corner button (the one setting here, app-wide). */
@Composable
private fun OpenersPage() {
    val corner by DeckInGameState.cornerButton.collectAsState()
    IgCard {
        IgRow(
            "Menu button in the corner",
            hint = "A faint button at the top right that opens the quick menu by touch.",
            scopes = listOf(SaveScope.ALL),
        ) { IgSwitch(corner) { DeckInGameState.changeCornerButton(it) } }
    }
    IgSectionLabel("Ways in")
    IgCard {
        IgRow("Controller: Select + Start together", hint = "Opens the quick menu; press it again for the full game menu, a third time to close.")
        IgDivider()
        IgRow("Back button", hint = "Opens the full game menu. Press Back again, or B on a controller, to close.")
        IgDivider()
        IgRow("Tap with four fingers", hint = "Opens the full game menu from the touch screen.")
        IgDivider()
        IgRow("Inside the menus", hint = "L1 / R1 change page, X swaps quick menu and game menu, B closes.")
    }
}

// ───────────────────────────── Full game menu ─────────────────────────────

private fun menuTabTitle(t: MenuTab) = when (t) {
    MenuTab.SCREEN -> "Screen"
    MenuTab.PERFORMANCE -> "Performance"
    MenuTab.EFFECTS -> "Visual effects"
    MenuTab.OVERLAY -> "Performance overlay"
    MenuTab.CONTROLS -> "Controls"
    MenuTab.AUDIO -> "Audio"
    MenuTab.STEAM -> "Steam"
    MenuTab.TV -> "TV"
    MenuTab.FRIENDS -> "Friends"
    MenuTab.TOOLS -> "Tools"
}

private fun menuTabDesc(t: MenuTab) = when (t) {
    MenuTab.SCREEN -> "Picture size, frame pacing and refresh rate."
    MenuTab.PERFORMANCE -> "Frame limit, frame generation and device power."
    MenuTab.EFFECTS -> "Upscaling, sharpening, colour and ReShade."
    MenuTab.OVERLAY -> "The FPS/temperature overlay drawn over the game."
    MenuTab.CONTROLS -> "On-screen controls, mouse, rumble, motion aim, players."
    MenuTab.AUDIO -> "Sound profile, and a quick fix for crackle or silence."
    MenuTab.STEAM -> "The Linux Steam client session."
    MenuTab.TV -> "Play on a TV or cast to one."
    MenuTab.FRIENDS -> "Steam friends and chat."
    MenuTab.TOOLS -> "Magnifier, windows, logs and running programs."
}

private fun menuTabIcon(t: MenuTab): ImageVector = when (t) {
    MenuTab.SCREEN -> Icons.Outlined.DesktopWindows
    MenuTab.PERFORMANCE -> Icons.Outlined.Speed
    MenuTab.EFFECTS -> Icons.Outlined.Palette
    MenuTab.OVERLAY -> Icons.Outlined.Layers
    MenuTab.CONTROLS -> Icons.Outlined.SportsEsports
    MenuTab.AUDIO -> Icons.Outlined.VolumeUp
    MenuTab.STEAM -> Icons.Outlined.Storefront
    MenuTab.TV -> Icons.Outlined.Tv
    MenuTab.FRIENDS -> Icons.Outlined.People
    MenuTab.TOOLS -> Icons.Outlined.Build
}

@Composable
private fun DeckInGameMenu(modifier: Modifier, onExit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = XServerDrawerState
    val compact = LocalIgCompact.current
    val tab by DeckInGameState.menuTab.collectAsState()
    val title by DeckInGameState.gameTitle.collectAsState()
    val isPaused by state.isPaused.collectAsState()
    val linuxSteam by state.linuxSteamSession.collectAsState()
    val tvConnected by state.tvConnected.collectAsState()
    val castSupported by state.castSupported.collectAsState()
    val friendsSource by InGameFriendsSource.state.collectAsState()
    val tabs = DeckInGameState.availableMenuTabs(
        linuxSteam,
        FeatureFlags.TV_OUTPUT_ENABLED && (tvConnected || castSupported),
        friendsSource.tabVisible,
    )
    // A page that stops applying (the friends source dropped) falls back to the first page rather than an empty one.
    val shown = if (tab in tabs) tab else tabs.first()
    val tabFocus = remember { FocusRequester() }
    FocusOnRequest(tabFocus)
    // Re-check the friends source each time the menu opens, as the Classic drawer does.
    LaunchedEffect(Unit) { InGameFriendsSource.poke() }

    val rail: @Composable () -> Unit = {
        tabs.forEach { t ->
            val sel = t == shown
            val shape = RoundedCornerShape(14.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .then(if (sel) Modifier.focusRequester(tabFocus) else Modifier)
                    .deckFocusRing(shape, scaleTo = 1.03f)
                    .clip(shape)
                    .background(if (sel) cs.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { DeckInGameState.selectMenuTab(t) }
                    .then(if (compact) Modifier else Modifier.fillMaxWidth())
                    .heightIn(min = 50.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(menuTabIcon(t), contentDescription = if (compact && !sel) menuTabTitle(t) else null, tint = if (sel) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                if (!compact || sel) {
                    Spacer(Modifier.width(12.dp))
                    Text(menuTabTitle(t), color = if (sel) cs.onSurface else cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 19.sp)
                }
            }
        }
    }

    val content: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            val pageMod = Modifier.weight(1f).fillMaxWidth()
            val header: @Composable () -> Unit = {
                Text(
                    title.uppercase(),
                    color = cs.primary,
                    fontFamily = SoraFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.5.sp,
                    letterSpacing = 3.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(menuTabTitle(shown), color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 26.sp else 30.sp)
                Text(menuTabDesc(shown), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                IgScopeChips(SaveScope.LEGEND)
                Spacer(Modifier.height(4.dp))
            }
            if (shown == MenuTab.FRIENDS) {
                // The roster owns its scrolling (a LazyColumn), so it gets the space without a scroll wrapper.
                Column(pageMod.padding(horizontal = 24.dp, vertical = 18.dp)) {
                    header()
                    Spacer(Modifier.height(12.dp))
                    IgHostedCard { FriendsContent(state) }
                }
            } else {
                Column(pageMod.verticalScroll(rememberScrollState()).padding(horizontal = if (compact) 16.dp else 24.dp, vertical = 18.dp)) {
                    header()
                    when (shown) {
                        MenuTab.SCREEN -> ScreenPage(state)
                        MenuTab.PERFORMANCE -> PerformancePage(state)
                        MenuTab.EFFECTS -> EffectsPage(state)
                        MenuTab.OVERLAY -> OverlayPage(state)
                        MenuTab.CONTROLS -> ControlsPage(state)
                        MenuTab.AUDIO -> AudioPage(state)
                        MenuTab.STEAM -> SteamPage(state)
                        MenuTab.TV -> TvPage(state)
                        MenuTab.TOOLS -> ToolsPage(state)
                        MenuTab.FRIENDS -> Unit
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            // Footer: back to the quick menu, pause, exit (which asks first).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surface.copy(alpha = 0.6f))
                    .padding(horizontal = if (compact) 12.dp else 24.dp, vertical = 12.dp),
            ) {
                IgButton(if (compact) null else "Quick menu", Icons.Outlined.Bolt, { DeckInGameState.openQuick() })
                Spacer(Modifier.weight(1f))
                IgButton(if (isPaused) "Resume game" else "Pause game", if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, ::pauseAndClose)
                IgButton("Exit game", Icons.AutoMirrored.Outlined.Logout, onExit, danger = true)
            }
        }
    }

    val panelMod = modifier
        .background(cs.background.copy(alpha = 0.97f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    if (compact) {
        Column(panelMod) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surface.copy(alpha = 0.6f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) { rail() }
            content(Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(panelMod) {
            Column(
                modifier = Modifier
                    .width(184.dp)
                    .fillMaxHeight()
                    .background(cs.surface.copy(alpha = 0.55f))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                    DeckGlyph("L1", GlyphKind.BUMPER)
                    Spacer(Modifier.weight(1f))
                    DeckGlyph("R1", GlyphKind.BUMPER)
                }
                Spacer(Modifier.height(6.dp))
                rail()
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(deckLine().copy(alpha = 0.4f)))
            content(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** A short "what → where it saves" list under a group whose controls come from the Classic drawer unchanged. */
@Composable
private fun SaveNotes(vararg lines: Pair<String, List<SaveScope>>) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { (what, scopes) ->
            if (LocalIgCompact.current) {
                Column {
                    Text(what, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    IgScopeChips(scopes)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(what, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    IgScopeChips(scopes)
                }
            }
        }
    }
}

// ── Screen ──
@Composable
private fun ScreenPage(state: XServerDrawerState) {
    val backend by state.runtimeBackend.collectAsState()
    if (backend.isValid) {
        IgSectionLabel("Running on")
        IgCard {
            val fex = if (backend.showsFexMode) when (backend.fexMode) {
                FexMode.UNIXLIB -> " · unixlib"
                FexMode.DLL -> " · DLL"
                FexMode.NA -> ""
            } else ""
            IgRow("${backend.arch} · ${backend.translator}$fex", hint = "Architecture, translator and how FEX is loaded.")
        }
    }

    IgSectionLabel("Size & position")
    IgCard {
        FitToScreenRow(state)
        IgDivider()
        val alignment by state.screenAlignment.collectAsState()
        IgRow("Position on screen", hint = "Top or Bottom keeps the other half free for on-screen controls.", scopes = listOf(igGameScope()), wideControl = true) {
            IgSegmented(listOf(0 to "Center", 1 to "Top", 2 to "Bottom"), alignment) { state.onSetScreenAlignment?.accept(it) }
        }
    }

    val vulkan by state.rendererIsVulkan.collectAsState()
    if (vulkan) {
        IgSectionLabel("Frame pacing")
        IgCard { PresentModeRow(state) }
    }

    IgSectionLabel("Refresh rate")
    IgCard { RefreshRateRows(state) }
}

/** Present mode, with the same frame-generation lock the Classic section enforces (Mailbox while frames are generated). */
@Composable
private fun PresentModeRow(state: XServerDrawerState) {
    val mode by state.presentMode.collectAsState()
    val locked by state.presentModeLocked.collectAsState()
    IgRow(
        stringResource(R.string.renderer_present_mode),
        hint = "How finished frames reach the screen: V-Sync (FIFO) never tears, Smooth (Mailbox) adds less delay, Fastest (Immediate) is lowest latency but may tear.",
        note = if (locked) stringResource(R.string.present_mode_fg_locked) else null,
        scopes = listOf(igGameScope()),
        wideControl = true,
    ) {
        IgSegmented(
            listOf("fifo" to "V-Sync", "mailbox" to "Smooth", "immediate" to "Fastest"),
            mode,
            isEnabled = { !locked || it == "mailbox" },
        ) { if (!locked) state.onPresentModeChange?.accept(it) }
    }
}

/** Auto (match FPS) and a manual lock, the Classic Frame rate & refresh controls with their explanations. */
@Composable
private fun RefreshRateRows(state: XServerDrawerState) {
    LaunchedEffect(Unit) { state.onRefreshRatePoll?.run() }
    val match by state.matchRefreshRate.collectAsState()
    val vrr by state.vrrSupported.collectAsState()
    val manual by state.manualRefreshRate.collectAsState()
    val rates by state.supportedRefreshRates.collectAsState()
    val current by state.currentRefreshRate.collectAsState()
    val nativeFgLocks by state.nativeFgLocks.collectAsState()
    val fgAutoTurnedOn by state.fgAutoTurnedOn.collectAsState()
    val fgAutoPerGame by state.fgAutoPerGame.collectAsState()
    val explain = when {
        !vrr -> "Unavailable — this display has a single refresh rate, so there's nothing to match."
        match && nativeFgLocks && fgAutoTurnedOn ->
            "Turned on for frame generation — the display follows the frame limit × multiplier. " +
                (if (fgAutoPerGame) "Turn it off if you prefer; this game will remember." else "Turn it off if you prefer (for this session).")
        match && nativeFgLocks -> "On — with frame generation running, the display follows the frame limit × multiplier."
        nativeFgLocks ->
            (if (fgAutoPerGame) "Off for this game while frame generation runs. " else "Off while frame generation runs. ") +
                "Turn it on to fit the screen to the frame limit × multiplier."
        match -> "On — the display follows your FPS."
        else -> "Off — pick a rate below to lock the display."
    }
    // Under native frame generation, Auto is a per-game opt-out (kept on the shortcut); otherwise it is a container setting.
    val matchScope = if (nativeFgLocks) igGameScope() else SaveScope.CONTAINER
    IgRow("Match refresh rate to FPS", hint = explain, scopes = listOf(matchScope), enabled = vrr) {
        IgSwitch(match && vrr, enabled = vrr) {
            state.setMatchRefreshRate(it)
            state.onMatchRefreshChange?.run()
        }
    }
    if (rates.isNotEmpty()) {
        IgDivider()
        val lockOn = vrr && !match
        IgRow(
            "Lock the display to",
            hint = if (current > 0) "The screen is at $current Hz now." else null,
            scopes = listOf(SaveScope.CONTAINER),
            enabled = lockOn,
            wideControl = true,
        ) {
            IgChips(listOf(0 to "Off") + rates.map { it to "$it Hz" }, manual, enabled = lockOn) {
                state.setManualRefreshRate(it)
                state.onManualRefreshChange?.run()
            }
        }
    }
}

// ── Performance ──
@Composable
private fun PerformancePage(state: XServerDrawerState) {
    IgSectionLabel("Frame rate")
    IgCard { FrameLimitRow(state) }

    IgSectionLabel("Frame generation")
    IgHostedCard {
        FrameGenSection(state)
        SaveNotes(
            "On / off and multiplier (starts off each launch)" to listOf(SaveScope.SESSION),
            "Level, model, flow scale and performance mode" to listOf(SaveScope.CONTAINER),
        )
    }

    IgSectionLabel("Device power")
    var perfDialog by remember { mutableStateOf(false) }
    val fromShortcut by DeckInGameState.launchedFromShortcut.collectAsState()
    IgCard {
        IgRow(
            "Performance dashboard",
            hint = "CPU / GPU clocks, thermal, memory and power profile — live gauges, root-aware.",
            note = if (fromShortcut) "Changes are kept for this game only where they differ from the global default." else null,
            scopes = if (fromShortcut) listOf(SaveScope.GAME, SaveScope.ALL) else listOf(SaveScope.ALL),
        ) { IgButton("Open", Icons.Outlined.Speed, { perfDialog = true }) }
    }
    if (perfDialog) PerformanceDashboardDialog(state) { perfDialog = false }
}

// ── Visual effects ──
@Composable
private fun EffectsPage(state: XServerDrawerState) {
    val gl by XServerDialogState.effectsSupported.collectAsState()
    val wayland by state.isWaylandMode.collectAsState()
    val game = igGameScope()
    IgSectionLabel("Upscaling, sharpening & colour")
    IgHostedCard {
        GraphicsEffectsSection(state)
        SaveNotes(
            "Upscaling filter, sharpening and debanding" to listOf(game),
            "HDR" to listOf(if (gl) SaveScope.SESSION else game),
            "Look, brightness, contrast, gamma, saturation, FXAA / CRT / Toon / NTSC" to listOf(SaveScope.SESSION),
            "Native rendering" to listOf(SaveScope.SESSION),
        )
        if (wayland) {
            SaveNotes(
                "Wayland HDR output" to listOf(SaveScope.SESSION),
                "Zero-copy presentation and OpenGL safe mode" to listOf(game),
            )
        }
    }
    IgSectionLabel("ReShade")
    IgHostedCard {
        ReshadeSection()
        SaveNotes(
            "ReShade on / off, Solo / Stack, effects and their settings" to listOf(game),
            "Live preview" to listOf(SaveScope.ALL),
        )
    }
}

// ── Performance overlay ──
@Composable
private fun OverlayPage(state: XServerDrawerState) {
    IgSectionLabel("Overlay")
    IgHostedCard {
        HudContent(state, embedded = true)
        SaveNotes(
            "Everything on this page" to listOf(hudScope()),
            "Turning the overlay on when it wasn't built this launch also switches it on in the container" to listOf(SaveScope.CONTAINER),
        )
    }
    IgSectionLabel("On the game")
    IgCard {
        IgInfo("Tap the overlay to flip it between vertical and horizontal (Fusion: next size). Drag it to move it; long-press to lock it in place.")
    }
}

// ── Controls ──
@Composable
private fun ControlsPage(state: XServerDrawerState) {
    val sub by state.controlsSubTab.collectAsState()
    val game = igGameScope()
    IgHostedCard {
        ControlsContent(state)
        when (sub) {
            0 -> SaveNotes(
                "Show on-screen controls and the layout you pick" to listOf(SaveScope.SESSION),
                "Choosing no layout, timeout, haptics and opacity" to listOf(SaveScope.ALL),
                "Controls accent" to listOf(SaveScope.other("Saved in the controls profile")),
            )
            1 -> SaveNotes(
                "Relative mouse" to listOf(game),
                "Cursor to touch and its touch gestures" to listOf(SaveScope.ALL),
                "Disable mouse" to listOf(SaveScope.SESSION),
            )
            2 -> SaveNotes(
                "Rumble on / off and per-slot switches" to listOf(SaveScope.ALL),
                "Rumble target and intensity (this game when its settings carry them)" to listOf(SaveScope.CONTAINER),
            )
            3 -> SaveNotes(
                "Motion aim settings" to listOf(game),
                "Deadzone and smoothing" to listOf(SaveScope.CONTAINER),
            )
            4 -> SaveNotes("Player slot for each controller" to listOf(game))
            5 -> SaveNotes("Swipe between buttons" to listOf(SaveScope.ALL))
        }
    }
}

// ── Audio ──
@Composable
private fun AudioPage(state: XServerDrawerState) {
    val ctx = LocalContext.current
    val driverId = state.audioDriverId
    val engine = state.audioDriverLabel
    val fromShortcut by DeckInGameState.launchedFromShortcut.collectAsState()
    var cfg by remember { mutableStateOf(com.winlator.star.ui.components.loadAudioConfig(ctx, driverId)) }
    var show by remember { mutableStateOf(false) }
    val presetLabel = when (cfg.preset) {
        com.winlator.star.ui.components.PRESET_AUTO -> "Auto"
        com.winlator.star.ui.components.PRESET_LOW -> "Low latency"
        com.winlator.star.ui.components.PRESET_BALANCED -> "Balanced"
        com.winlator.star.ui.components.PRESET_STABLE -> "No crackle"
        com.winlator.star.ui.components.PRESET_CUSTOM -> "Custom"
        else -> cfg.preset
    }
    IgSectionLabel("Sound")
    IgCard {
        IgRow(
            "Audio profile",
            hint = (if (engine.isNotBlank()) "$engine · " else "") + "$presetLabel. Balances crackle against delay; applies now, the guest buffer at the next launch.",
            scopes = if (fromShortcut) listOf(SaveScope.GAME, SaveScope.ALL) else listOf(SaveScope.ALL),
        ) { IgButton("Presets & tuning…", Icons.Outlined.VolumeUp, { show = true }) }
        IgDivider()
        IgRow(
            "Restart audio",
            hint = "Fixes crackle or silence without restarting the game.",
            scopes = listOf(SaveScope.SESSION),
        ) { IgButton("Restart audio", null, { state.onResetAudio?.run() }) }
    }
    if (show) {
        com.winlator.star.ui.components.AudioSettingsDialog(
            initial = cfg,
            scopeLabel = if (fromShortcut) "applies now · saved for this game and as this engine's default" else "applies now · saved as this engine's default",
            latencyLive = false,
            driverLabel = engine,
            driverId = driverId,
            onDismiss = { show = false },
            onSave = { newCfg ->
                com.winlator.star.ui.components.saveAudioConfig(ctx, driverId, newCfg)
                cfg = newCfg
                state.onReapplyAudio?.run()
                show = false
            }
        )
    }
}

// ── Steam (Linux) ──
@Composable
private fun SteamPage(state: XServerDrawerState) {
    IgSectionLabel("Steam client")
    IgHostedCard {
        LinuxSteamSection(state)
        SaveNotes("Touch mode and every switch here" to listOf(SaveScope.GAME))
    }
    IgSectionLabel("Components")
    IgHostedCard {
        LinuxComponentsContent(state)
        SaveNotes("FEX / DXVK / VKD3D choices" to listOf(SaveScope.other("Saved in that Proton, for every game using it")))
    }
}

// ── TV ──
@Composable
private fun TvPage(state: XServerDrawerState) {
    IgHostedCard {
        TvContent(state)
        SaveNotes(
            "Play on TV, auto-switch, safe area, audio output, dimming and TV resolution" to listOf(SaveScope.CONTAINER),
            "Display mode" to listOf(SaveScope.SESSION),
            "Fit, scaling filter, frame pacing, frame cap and frame generation save as on their own pages" to emptyList<SaveScope>(),
        )
    }
}

// ── Tools ──
@Composable
private fun ToolsPage(state: XServerDrawerState) {
    val closeThen: (Runnable?) -> Unit = { r -> DeckInGameState.close(); r?.run() }
    var perfDialog by remember { mutableStateOf(false) }
    IgSectionLabel("View")
    IgCard {
        IgRow("Magnifier", hint = "Zoom into part of the screen.") { IgButton("Magnifier", Icons.Outlined.ZoomIn, { closeThen(state.onMagnifier) }) }
        IgDivider()
        IgRow("Picture-in-picture", hint = "Shrink the game into a floating window.") { IgButton("Picture-in-picture", Icons.Outlined.PictureInPictureAlt, { closeThen(state.onPipMode) }) }
        IgDivider()
        IgRow("Show keyboard", hint = "The Android keyboard, typing into the game.") { IgButton("Show keyboard", Icons.Outlined.Keyboard, { closeThen(state.onKeyboard) }) }
    }

    IgSectionLabel("Windows & logs")
    IgCard {
        IgRow("Switch window", hint = "Bring another of the game's windows to the front.") { IgButton("Switch window…", Icons.Outlined.ViewCarousel, { closeThen(state.onActiveWindows) }) }
        IgDivider()
        IgRow("Live log", hint = "Wine and Box64 / FEX output while logging is on.") { IgButton("Live log", Icons.Outlined.Description, { closeThen(state.onLogs) }) }
        IgDivider()
        IgRow("Controller test & remap", hint = "Check buttons and sticks, bind a physical profile, set player slots.") {
            IgButton("Controller test", Icons.Outlined.SportsEsports, { XServerDialogState.show(XServerDialogState.ActiveDialog.CONTROLLER_TEST) })
        }
        IgDivider()
        IgRow("Performance dashboard", hint = "Clocks, thermals, memory and power profile.") { IgButton("Open", Icons.Outlined.Speed, { perfDialog = true }) }
    }
    if (perfDialog) PerformanceDashboardDialog(state) { perfDialog = false }

    IgSectionLabel("Opening this menu")
    val corner by DeckInGameState.cornerButton.collectAsState()
    IgCard {
        IgRow(
            "Menu button in the corner",
            hint = "A faint button at the top right that opens the quick menu by touch. Select + Start on a controller, Back, or a four-finger tap work without it.",
            scopes = listOf(SaveScope.ALL),
        ) { IgSwitch(corner) { DeckInGameState.changeCornerButton(it) } }
    }

    IgSectionLabel("Running programs")
    RunningPrograms(state)
}

/** The Task Manager's live list, Deck style: cores, bring to front, and End (which asks first). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunningPrograms(state: XServerDrawerState) {
    val cs = MaterialTheme.colorScheme
    val processes by XServerDialogState.tmProcesses.collectAsState()
    val header by XServerDialogState.tmHeader.collectAsState()
    // The same start/stop the Classic Task Manager tab uses: the rail tap starts the poll, the pane refreshes on entry
    // and stops it when it leaves.
    LaunchedEffect(Unit) {
        state.onTaskManager?.run()
        XServerDialogState.onTmRefresh?.run()
    }
    DisposableEffect(Unit) { onDispose { XServerDialogState.onTmDismissed?.run() } }

    var affinityFor by remember { mutableStateOf<XServerDialogState.TmProcess?>(null) }
    var endFor by remember { mutableStateOf<XServerDialogState.TmProcess?>(null) }

    IgHostedCard { TmStatGrid(header) }
    Spacer(Modifier.height(10.dp))
    IgCard {
        if (processes.isEmpty()) {
            IgInfo("No programs reported yet.")
        } else {
            processes.forEachIndexed { i, p ->
                if (i > 0) IgDivider()
                IgRow(p.name + if (p.wow64) " (32-bit)" else "", hint = "PID ${p.pid} · ${p.formattedMemory}", wideControl = true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IgButton("Cores", Icons.Outlined.Memory, { affinityFor = p })
                        IgButton("Front", Icons.Outlined.FlipToFront, { XServerDialogState.onTmBringToFront?.invoke(p.name, p.pid) })
                        IgButton("End", Icons.Outlined.Close, { endFor = p }, danger = true)
                    }
                }
            }
        }
        IgDivider()
        IgRow("Run a program", hint = "Start another program in this session.") {
            IgButton("Run…", Icons.Outlined.Terminal, {
                XServerDialogState.onTmDismissed?.run()
                XServerDialogState.onTmNewTask?.run()
            })
        }
    }
    Text(
        "CPU core choices last until the program restarts.",
        color = cs.onSurfaceVariant,
        fontFamily = NunitoSansFamily,
        fontSize = 12.5.sp,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp),
    )

    affinityFor?.let { p -> ProcessorAffinityDialog(proc = p, onDismiss = { affinityFor = null }) }
    endFor?.let { p ->
        IgConfirmDialog(
            title = "End ${p.name}?",
            body = "The program is closed straight away. Anything it hasn't saved is lost.",
            confirmLabel = "End process",
            confirmIcon = Icons.Outlined.Close,
            onConfirm = {
                endFor = null
                XServerDialogState.onTmKillProcess?.invoke(p.name)
            },
            onDismiss = { endFor = null },
        )
    }
}
