package com.winlator.star.ui.deck

import com.winlator.star.ui.simple.L
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
import androidx.compose.material.icons.outlined.Extension
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
// which is gone (no focus, no touches, no keys) while both panels are closed. The optional corner button is a separate
// small view of its own.

fun setupDeckInGame(view: ComposeView) {
    view.setContent {
        WinlatorTheme {
            // Lab: the in-game panels follow Lab's Dark / Light look, as the app does.
            val lab = com.winlator.star.ui.theme.AppThemeState.simpleLook
            val base = MaterialTheme.colorScheme
            MaterialTheme(colorScheme = if (lab && com.winlator.star.ui.theme.AppThemeState.labLight) com.winlator.star.ui.simple.labLightScheme(base) else base, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
                DeckTheme {
                    if (lab) com.winlator.star.ui.simple.LabDirection { DeckInGameOverlay() } else DeckInGameOverlay()
                }
            }
        }
    }
}

/** The optional corner button that opens the quick menu by touch, in its own small view (the activity shows it only while it is on and nothing is open). */
fun setupDeckInGameCorner(view: ComposeView) {
    view.setContent {
        WinlatorTheme {
            DeckTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .alpha(0.55f)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .clickable { DeckInGameState.openQuick() },
                    ) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = L("Open the quick menu"), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
fun DeckInGameOverlay() {
    val panel by DeckInGameState.panel.collectAsState()
    val menuOpen = panel == Panel.MENU
    val quickOpen = panel == Panel.QUICK
    // Hoisted so both panels share one exit confirmation.
    var confirmExit by remember { mutableStateOf(false) }

    // Tells the activity whether a control in the panels holds focus (see DeckInGameState.hasControlFocus).
    BoxWithConstraints(Modifier.fillMaxSize().onFocusChanged { DeckInGameState.setControlFocused(it.hasFocus) }) {
        val compact = maxWidth < 600.dp
        // The game menu leaves most of the game in view: about two thirds of the width, at least what its rail and a page need.
        val menuWidth = if (compact) maxWidth else (maxWidth * 0.64f).coerceIn(minOf(560.dp, maxWidth), 900.dp)
        val quickWidth = if (compact) maxWidth else (maxWidth * 0.42f).coerceIn(340.dp, 470.dp)
        // Simple style: a narrower quick menu on the left, hard-edged and lifted off the game.
        val simple = remember { com.winlator.star.ui.theme.AppThemeState.simpleHome.value }
        val simpleWidth = if (compact) maxWidth else (maxWidth * 0.34f).coerceIn(320.dp, 420.dp)
        // XMB mode: Lab's one-drawer structure in XMB's glass look, in place of the Deck pair below (never together with Lab).
        val xmbDrawer = remember { DeckInGameState.xmbDrawer }
        CompositionLocalProvider(LocalIgCompact provides compact) {
            // Behind a panel: the full menu dims the game, the quick menu leaves it visible. A tap there closes either one.
            if (panel != Panel.NONE) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (menuOpen && !simple && !xmbDrawer) Color.Black.copy(alpha = 0.55f) else Color.Transparent)
                        // Touch only: as a focus target the full-screen catcher could take the pad's focus and keep it.
                        .focusProperties { canFocus = false }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { DeckInGameState.close() }
                )
            }

            // Lab: one menu (Quick first, then every page), a little under half the screen wide.
            val labWidth = if (compact) maxWidth else (maxWidth * 0.46f).coerceIn(minOf(420.dp, maxWidth), 600.dp)
            AnimatedVisibility(
                visible = simple && panel != Panel.NONE,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                LabInGameMenu(Modifier.width(labWidth).fillMaxHeight(), onExit = { confirmExit = true })
            }

            // XMB: the same one drawer for Quick and the menu, sliding in from the left like Lab's.
            val xmbWidthDp = xmbInGameDrawerWidth(maxWidth.value, compact)
            AnimatedVisibility(
                visible = xmbDrawer && panel != Panel.NONE,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                XmbInGameMenu(Modifier.width(xmbWidthDp.dp).fillMaxHeight(), xmbWidthDp, onExit = { confirmExit = true })
            }

            AnimatedVisibility(
                visible = menuOpen && !simple && !xmbDrawer,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                DeckInGameMenu(Modifier.width(menuWidth).fillMaxHeight(), onExit = { confirmExit = true })
            }

            AnimatedVisibility(
                visible = quickOpen && !simple && !xmbDrawer,
                enter = slideInHorizontally { if (simple) -it else it } + fadeIn(),
                exit = slideOutHorizontally { if (simple) -it else it } + fadeOut(),
                modifier = Modifier.align(if (simple) Alignment.CenterStart else Alignment.CenterEnd),
            ) {
                if (simple) SimpleQuickMenu(Modifier.width(simpleWidth).fillMaxHeight(), onExit = { confirmExit = true })
                else DeckQuickMenu(Modifier.width(quickWidth).fillMaxHeight(), onExit = { confirmExit = true })
            }
        }
    }

    if (confirmExit) {
        IgConfirmDialog(
            title = L("Exit the game?"),
            body = L("The game closes and Bannerlator saves and syncs as usual. Progress the game itself hasn't saved is lost."),
            confirmLabel = L("Exit game"),
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

/**
 * Puts controller focus on [requester] whenever the panel asks for it (opened, page changed, a stray d-pad press), and
 * only while [panel] is the open one: during an X swap the panel sliding out must not take focus back from the new one.
 * [fallback] is focused first, so it keeps focus when nothing under [requester] can take it (a page without a control).
 */
@Composable
internal fun FocusOnRequest(panel: Panel, requester: FocusRequester, fallback: FocusRequester? = null) {
    val tick by DeckInGameState.focusRequest.collectAsState()
    LaunchedEffect(tick) {
        // One frame for a just-switched page to lay out before focus moves.
        delay(60)
        if (DeckInGameState.panel.value != panel) return@LaunchedEffect
        if (fallback != null) runCatching { fallback.requestFocus() }
        runCatching { requester.requestFocus() }
    }
}

/** Pause mirrors the Classic rail: freeze (or resume) the game and close the menu so the Paused pill shows. */
internal fun pauseAndClose() {
    XServerDrawerState.onPauseResume?.run()
    DeckInGameState.close()
}

// ───────────────────────────── Quick menu ─────────────────────────────

/**
 * The Simple style's quick menu: one compact page on the left with the same controls as the Deck quick menu's first
 * page (frame limit, frame generation, overlay, fit, brightness, mouse and on-screen controls; all through the same
 * rows, so every change goes the same way), then Game menu, pause and exit. Square edges and a deep shadow, like the
 * Simple home.
 */
@Composable
private fun SimpleQuickMenu(modifier: Modifier, onExit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val title by DeckInGameState.gameTitle.collectAsState()
    val isPaused by XServerDrawerState.isPaused.collectAsState()
    val kind by DeckInGameState.sessionKind.collectAsState()
    val pageFocus = remember { FocusRequester() }
    FocusOnRequest(Panel.QUICK, pageFocus)
    Column(
        modifier = modifier
            .shadow(26.dp, androidx.compose.ui.graphics.RectangleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(com.winlator.star.ui.simple.SimplePanelTop.copy(alpha = 0.97f), com.winlator.star.ui.simple.SimplePanelBottom.copy(alpha = 0.98f))))
            .border(1.dp, com.winlator.star.ui.simple.SimpleLine)
            .focusProperties { canFocus = false }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(top = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                title.ifEmpty { L("In game") },
                color = com.winlator.star.ui.simple.SimpleStrong,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DeviceStatus()
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 20.dp).width(36.dp).height(3.dp).background(cs.primary))
        Spacer(Modifier.height(8.dp))
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
            Column(Modifier.fillMaxWidth().focusRequester(pageFocus)) { QuickControls(kind) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(com.winlator.star.ui.simple.SimpleCard)
                .border(1.dp, com.winlator.star.ui.simple.SimpleLine)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            IgButton(L("Game menu"), Icons.Outlined.Menu, { DeckInGameState.openMenu() }, modifier = Modifier.weight(1f), primary = true, glyph = "X")
            IgButton(null, if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, ::pauseAndClose)
            IgButton(null, Icons.AutoMirrored.Outlined.Logout, onExit, danger = true)
        }
    }
}

@Composable
private fun DeckQuickMenu(modifier: Modifier, onExit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tab by DeckInGameState.quickTab.collectAsState()
    val title by DeckInGameState.gameTitle.collectAsState()
    val friendsSource by InGameFriendsSource.state.collectAsState()
    val isPaused by XServerDrawerState.isPaused.collectAsState()
    val kind by DeckInGameState.sessionKind.collectAsState()
    val tabs = DeckInGameState.availableQuickTabs(friendsSource.tabVisible)
    val shown = if (tab in tabs) tab else QuickTab.QUICK
    val tabFocus = remember { FocusRequester() }
    // L1 / R1 and opening put focus on the page's first control, like the Deck's quick access menu; the tab is the
    // fallback for a page with nothing to focus.
    val pageFocus = remember { FocusRequester() }
    FocusOnRequest(Panel.QUICK, pageFocus, fallback = tabFocus)

    Column(
        modifier = modifier
            .background(cs.background.copy(alpha = 0.97f))
            .border(1.dp, deckLine().copy(alpha = 0.4f))
            .focusProperties { canFocus = false }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(top = 18.dp),
    ) {
        // Header: the game, device temperature and battery.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Text(
                title.ifEmpty { L("In game") },
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
                val shape = deckShape(16.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(if (sel) Modifier.focusRequester(tabFocus) else Modifier)
                        .focusProperties { down = pageFocus }
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
            Box(bodyMod.padding(bottom = 8.dp).igFocusOutline().focusRequester(pageFocus)) { FriendsContent(XServerDrawerState) }
        } else {
            // Each page starts at its top rather than at the previous page's scroll offset.
            val scroll = key(shown) { rememberScrollState() }
            Column(bodyMod.verticalScroll(scroll).padding(bottom = 12.dp)) {
                // Inside the scroll container, so the request lands on the page's first control, not the scroll group.
                Column(Modifier.fillMaxWidth().focusRequester(pageFocus)) {
                    when (shown) {
                        QuickTab.QUICK -> QuickControls(kind)
                        QuickTab.TOOLS -> QuickTools(kind)
                        QuickTab.OPENERS -> OpenersPage()
                        QuickTab.FRIENDS -> Unit
                    }
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
            IgButton(L("Game menu"), Icons.Outlined.Menu, { DeckInGameState.openMenu() }, modifier = Modifier.weight(1f), primary = true, glyph = "X")
            IgButton(null, if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, ::pauseAndClose)
            IgButton(null, Icons.AutoMirrored.Outlined.Logout, onExit, danger = true)
        }
    }
}

private fun quickTabTitle(t: QuickTab) = when (t) {
    QuickTab.QUICK -> L("Quick menu")
    QuickTab.TOOLS -> L("Tools")
    QuickTab.FRIENDS -> L("Friends")
    QuickTab.OPENERS -> L("Opening this menu")
}

private fun quickTabIcon(t: QuickTab): ImageVector = when (t) {
    QuickTab.QUICK -> Icons.Outlined.Bolt
    QuickTab.TOOLS -> Icons.Outlined.Build
    QuickTab.FRIENDS -> Icons.Outlined.People
    QuickTab.OPENERS -> Icons.Outlined.TouchApp
}

/** Battery level and temperature from the sticky battery broadcast, refreshed while the quick menu is open. */
@Composable
internal fun DeviceStatus() {
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
            Text(L("%1\$s°C", tempC.roundToInt()), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
        }
        if (level >= 0) {
            Icon(Icons.Outlined.BatteryFull, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text("$level%", color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/**
 * The quick menu's main page: the live controls people change most mid-game, each wired to the Classic drawer's code.
 * The Linux Steam client keeps its own touch choice (its Steam page), so the mouse and on-screen control rows stay out.
 */
@Composable
internal fun QuickControls(kind: InGameSessionKind) {
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
            L("Restart audio"),
            hint = L("Fixes crackle or silence without restarting the game."),
            scopes = listOf(SaveScope.SESSION),
        ) { IgButton(L("Restart"), Icons.Outlined.VolumeUp, { state.onResetAudio?.run() }) }
        if (kind != InGameSessionKind.LINUX_STEAM) {
            IgDivider()
            MouseQuickRows(state)
            IgDivider()
            OnScreenControlsRow()
        }
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
    val nativeFgName = when (fgEngine) { "lsfg-native" -> L("LSFG Native"); "wfg" -> "WFG"; else -> L("Win-FG Native") }
    val lsfgVkMultiplying = fgEngine == "lsfg" && fgEnabled && fgMult >= 2
    val hint = when {
        nativeFgLocks -> L("Caps the game's real frames. Locked on while %1\$s is generating; you'll see up to %2\$s × %3\$s = %4\$s.", nativeFgName, limit, fgMult, limit * fgMult)
        lsfgVkMultiplying -> L("Not applied while lsfg-vk is multiplying: it paces frames itself.")
        compactHint -> L("Steadier frame pacing and a cooler device.")
        else -> L("Caps how many frames per second the game draws. Saves battery and evens out stutter.")
    }
    IgRow(L("Frame rate limit"), hint = hint, scopes = listOf(igGameScope()), wideControl = true) {
        Column(horizontalAlignment = Alignment.End) {
            IgChips(
                options = listOf(CAP_OFF to L("Off")) + presets.map { it to "$it" } + listOf(CAP_CUSTOM to L("Custom…")),
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
                    format = { L("%1\$s fps", it.roundToInt()) },
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
    val note = L("Starts off each launch; the level you pick is kept for this container.")
    if (!fgEnabled || waylandBlocked) {
        IgRow(
            L("Frame generation"),
            hint = if (waylandBlocked) L("Not on Wayland in this build yet.") else L("Off for this container. Turn it on in the container's settings to use it here."),
            enabled = false,
        )
        return
    }
    val options = if (engine == "bionic") listOf(0 to L("Off"), 2 to L("On")) else listOf(0 to L("Off"), 2 to "2×", 3 to "3×", 4 to "4×")
    IgRow(
        L("Frame generation"),
        hint = if (unavailable.isNotEmpty()) unavailable else L("Inserts extra frames for smoother motion. The game pauses for a moment while it restarts."),
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
        L("Show performance overlay"),
        hint = L("FPS, CPU/GPU load and temperatures on top of the game. Tap it in-game to flip or resize it."),
        scopes = listOf(scope),
    ) {
        IgSwitch(on) { state.onFpsConfigApply?.invoke(hudConfigWith(cfgString, "hudEnabled" to if (it) "1" else "0")) }
    }
    if (on) {
        IgRow(L("Overlay style"), scopes = listOf(scope), wideControl = true) {
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
    IgRow(L("Fit to screen"), hint = L("How the game picture fills your display."), scopes = listOf(igGameScope()), wideControl = true) {
        IgSegmented(
            listOf(0 to L("Original"), 1 to L("Fit"), 2 to L("Stretch"), 3 to L("Fill (crop)"), 4 to L("Pixel-perfect")),
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
    IgRow(L("Brightness"), hint = L("Colour-grade brightness on top of the game."), scopes = listOf(SaveScope.SESSION), wideControl = true) {
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

/** Touch and Aim mode: the same choices as the Classic Controls › Mouse chips, saved for this game. */
@Composable
private fun MouseQuickRows(state: XServerDrawerState) {
    val touch by state.touchMode.collectAsState()
    val aim by state.aimMode.collectAsState()
    IgRow(L("Touch"), scopes = listOf(igGameScope()), wideControl = true) {
        IgSegmented(listOf("touchpad" to L("Touchpad"), "direct" to L("Direct"), "touchscreen" to L("Touchscreen")), touch) {
            state.onTouchMode?.accept(it)
        }
    }
    IgDivider()
    IgRow(L("Aim mode"), scopes = listOf(igGameScope()), wideControl = true) {
        IgSegmented(listOf("auto" to L("Auto"), "on" to L("On"), "off" to L("Off")), aim) {
            state.onAimMode?.accept(it)
        }
    }
}

/** On-screen controls on/off through onInputControlsConfirm, exactly like the Classic Touch Controls chip. */
@Composable
private fun OnScreenControlsRow() {
    val ds = XServerDialogState
    val shown by ds.showTouchscreen.collectAsState()
    IgRow(L("Show on-screen controls"), hint = L("The touch buttons of the current layout."), scopes = listOf(SaveScope.SESSION)) {
        IgSwitch(shown) { v ->
            ds.onInputControlsConfirm?.invoke(ds.selectedProfileIdx.value, v, ds.timeoutEnabled.value, ds.hapticsEnabled.value)
            // The confirm callback applies the flag but doesn't echo it; mirror it so this switch and the Controls page agree.
            ds.setShowTouchscreen(v)
        }
    }
}

/**
 * Tool shortcuts: each does what the Classic Advanced / Task Manager entries do, closing the panel first where they do.
 * The Wine tools (controller slots, the Wine and Box64 / FEX log, windows, programs) only show in a Wine session.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickTools(kind: InGameSessionKind) {
    val state = XServerDrawerState
    val castSupported by state.castSupported.collectAsState()
    var perfDialog by remember { mutableStateOf(false) }
    val compact = LocalIgCompact.current
    val closeThen: (Runnable?) -> Unit = { r -> DeckInGameState.close(); r?.run() }
    IgSectionLabel(L("Tools"))
    val tools = buildList<Triple<String, ImageVector, () -> Unit>> {
        if (kind.isWine) add(Triple(L("Controller test & remap"), Icons.Outlined.SportsEsports) { XServerDialogState.show(XServerDialogState.ActiveDialog.CONTROLLER_TEST) })
        add(Triple(L("Performance dashboard"), Icons.Outlined.Speed) { perfDialog = true })
        if (kind.isWine) {
            add(Triple(L("Live log"), Icons.Outlined.Description) { closeThen(state.onLogs) })
            add(Triple(L("Switch window"), Icons.Outlined.ViewCarousel) { closeThen(state.onActiveWindows) })
            add(Triple(L("Run a program"), Icons.Outlined.Terminal) {
                XServerDialogState.onTmDismissed?.run()
                (XServerDialogState.onRunProgramPicker ?: XServerDialogState.onTmNewTask)?.run()
            })
            add(Triple(L("Task manager"), Icons.Outlined.ListAlt) { DeckInGameState.openMenuAt(MenuTab.TOOLS) })
        }
        if (FeatureFlags.TV_OUTPUT_ENABLED && castSupported) add(Triple(L("Cast to a TV"), Icons.Outlined.Cast) { state.onOpenCastPicker?.run() })
    }
    ToolTiles(tools, if (compact) 2 else 3)
    IgSectionLabel(L("View"))
    ToolTiles(
        listOf(
            Triple(L("Magnifier"), Icons.Outlined.ZoomIn) { closeThen(state.onMagnifier) },
            Triple(L("Picture-in-picture"), Icons.Outlined.PictureInPictureAlt) { closeThen(state.onPipMode) },
            Triple(L("Show keyboard"), Icons.Outlined.Keyboard) { closeThen(state.onKeyboard) },
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
                    val shape = deckShape(16.dp)
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
            L("Menu button in the corner"),
            hint = L("A faint button at the top right that opens the quick menu by touch."),
            scopes = listOf(SaveScope.ALL),
        ) { IgSwitch(corner) { DeckInGameState.changeCornerButton(it) } }
    }
    IgSectionLabel(L("Ways in"))
    IgCard {
        IgRow(L("Back button"), hint = L("Opens the full game menu. Press Back again, or B on a controller, to close."))
        IgDivider()
        IgRow(L("Menu button"), hint = L("The button picked in Input Controls › Device › Menu button opens the quick menu, and closes it."))
        IgDivider()
        IgRow(L("Tap with four fingers"), hint = L("Opens the full game menu from the touch screen."))
        IgDivider()
        IgRow(L("Inside the menus"), hint = L("L1 / R1 change page, X swaps quick menu and game menu, B closes."))
    }
}

// ───────────────────────────── Full game menu ─────────────────────────────

internal fun menuTabTitle(t: MenuTab) = when (t) {
    MenuTab.SCREEN -> L("Screen")
    MenuTab.PERFORMANCE -> L("Performance")
    MenuTab.EFFECTS -> L("Visual effects")
    MenuTab.OVERLAY -> L("Performance overlay")
    MenuTab.CONTROLS -> L("Controls")
    MenuTab.AUDIO -> L("Audio")
    MenuTab.STEAM -> "Steam"
    MenuTab.TV -> "TV"
    MenuTab.FRIENDS -> L("Friends")
    MenuTab.MODS -> L("Mods")
    MenuTab.TOOLS -> L("Tools")
}

internal fun menuTabDesc(t: MenuTab, kind: InGameSessionKind) = when (t) {
    MenuTab.SCREEN -> L("Picture size, frame pacing and refresh rate.")
    MenuTab.PERFORMANCE -> L("Frame limit, frame generation and device power.")
    MenuTab.EFFECTS -> if (kind.isWine) L("Upscaling, sharpening, colour and ReShade.") else L("Upscaling, sharpening and colour.")
    MenuTab.OVERLAY -> L("The FPS/temperature overlay drawn over the game.")
    MenuTab.CONTROLS -> when (kind) {
        InGameSessionKind.LINUX_STEAM -> L("Rumble and motion aim.")
        InGameSessionKind.LINUX_RUNTIME -> L("On-screen controls, mouse, rumble, motion aim.")
        else -> L("On-screen controls, mouse, rumble, motion aim, players.")
    }
    MenuTab.AUDIO -> L("Sound profile, and a quick fix for crackle or silence.")
    MenuTab.STEAM -> L("The Linux Steam client session.")
    MenuTab.TV -> L("Play on a TV or cast to one.")
    MenuTab.FRIENDS -> L("Steam friends and chat.")
    MenuTab.MODS -> L("This game's mods and their menus.")
    MenuTab.TOOLS -> if (kind.isWine) L("Magnifier, windows, logs and running programs.") else L("Magnifier, picture-in-picture, keyboard and performance.")
}

internal fun menuTabIcon(t: MenuTab): ImageVector = when (t) {
    MenuTab.SCREEN -> Icons.Outlined.DesktopWindows
    MenuTab.PERFORMANCE -> Icons.Outlined.Speed
    MenuTab.EFFECTS -> Icons.Outlined.Palette
    MenuTab.OVERLAY -> Icons.Outlined.Layers
    MenuTab.CONTROLS -> Icons.Outlined.SportsEsports
    MenuTab.AUDIO -> Icons.Outlined.VolumeUp
    MenuTab.STEAM -> Icons.Outlined.Storefront
    MenuTab.TV -> Icons.Outlined.Tv
    MenuTab.FRIENDS -> Icons.Outlined.People
    MenuTab.MODS -> Icons.Outlined.Extension
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
    val kind by DeckInGameState.sessionKind.collectAsState()
    val tvConnected by state.tvConnected.collectAsState()
    val castSupported by state.castSupported.collectAsState()
    val friendsSource by InGameFriendsSource.state.collectAsState()
    val mods by state.mods.collectAsState()
    val tabs = DeckInGameState.availableMenuTabs(
        kind,
        FeatureFlags.TV_OUTPUT_ENABLED && (tvConnected || castSupported),
        friendsSource.tabVisible,
        mods.isNotEmpty(),
    )
    // A page that stops applying (the friends source dropped) falls back to the first page rather than an empty one.
    val shown = if (tab in tabs) tab else tabs.first()
    val tabFocus = remember { FocusRequester() }
    FocusOnRequest(Panel.MENU, tabFocus)
    // RIGHT from the rail (DOWN from the compact strip) enters the page at its first control, whatever the rail row.
    val pageFocus = remember { FocusRequester() }
    // Re-check the friends source each time the menu opens, as the Classic drawer does.
    LaunchedEffect(Unit) { InGameFriendsSource.poke() }

    val rail: @Composable () -> Unit = {
        tabs.forEach { t ->
            val sel = t == shown
            val shape = deckShape(14.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .then(if (sel) Modifier.focusRequester(tabFocus) else Modifier)
                    .focusProperties { if (compact) down = pageFocus else right = pageFocus }
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
                Text(menuTabDesc(shown, kind), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                IgScopeChips(SaveScope.LEGEND)
                Spacer(Modifier.height(4.dp))
            }
            if (shown == MenuTab.FRIENDS) {
                // The roster owns its scrolling (a LazyColumn), so it gets the space without a scroll wrapper.
                Column(pageMod.padding(horizontal = 24.dp, vertical = 18.dp).focusRequester(pageFocus)) {
                    header()
                    Spacer(Modifier.height(12.dp))
                    IgHostedCard { FriendsContent(state) }
                }
            } else {
                // Each page starts at its top rather than at the previous page's scroll offset.
                val scroll = key(shown) { rememberScrollState() }
                Column(pageMod.igPageScroll(scroll).padding(horizontal = if (compact) 16.dp else 24.dp, vertical = 18.dp)) {
                    header()
                    // Inside the scroll container, so the request lands on the page's first control, not the scroll group.
                    Column(Modifier.fillMaxWidth().focusRequester(pageFocus)) {
                        when (shown) {
                            MenuTab.SCREEN -> ScreenPage(state)
                            MenuTab.PERFORMANCE -> PerformancePage(state)
                            MenuTab.EFFECTS -> EffectsPage(state, kind)
                            MenuTab.OVERLAY -> OverlayPage(state)
                            MenuTab.CONTROLS -> ControlsPage(state, kind)
                            MenuTab.AUDIO -> AudioPage(state)
                            MenuTab.STEAM -> SteamPage(state)
                            MenuTab.TV -> TvPage(state)
                            MenuTab.MODS -> ModsPage(state, mods)
                            MenuTab.TOOLS -> ToolsPage(state, kind)
                            MenuTab.FRIENDS -> Unit
                        }
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
                IgButton(if (compact) null else L("Quick menu"), Icons.Outlined.Bolt, { DeckInGameState.openQuick() })
                Spacer(Modifier.weight(1f))
                IgButton(if (isPaused) L("Resume game") else L("Pause game"), if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, ::pauseAndClose)
                IgButton(L("Exit game"), Icons.AutoMirrored.Outlined.Logout, onExit, danger = true)
            }
        }
    }

    val panelMod = modifier
        .background(cs.background.copy(alpha = 0.97f))
        .focusProperties { canFocus = false }
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

/**
 * Lab's in-game menu: one panel a little under half the screen, the quick menu's controls as its first page (Quick) and
 * every page of the full menu after it, picked from a tab strip (L1 / R1, or a tap). The pages are the full menu's own.
 */
@Composable
private fun LabInGameMenu(modifier: Modifier, onExit: () -> Unit) {
    val state = XServerDrawerState
    val tab by DeckInGameState.menuTab.collectAsState()
    val quick by DeckInGameState.labQuick.collectAsState()
    val panel by DeckInGameState.panel.collectAsState()
    val title by DeckInGameState.gameTitle.collectAsState()
    val isPaused by state.isPaused.collectAsState()
    val kind by DeckInGameState.sessionKind.collectAsState()
    val tvConnected by state.tvConnected.collectAsState()
    val castSupported by state.castSupported.collectAsState()
    val friendsSource by InGameFriendsSource.state.collectAsState()
    val mods by state.mods.collectAsState()
    val tabs = DeckInGameState.availableMenuTabs(kind, FeatureFlags.TV_OUTPUT_ENABLED && (tvConnected || castSupported), friendsSource.tabVisible, mods.isNotEmpty())
    val shown = if (tab in tabs) tab else tabs.first()
    val accent = MaterialTheme.colorScheme.primary
    val stripFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    FocusOnRequest(panel, pageFocus, fallback = stripFocus)
    LaunchedEffect(Unit) { InGameFriendsSource.poke() }

    @Composable
    fun TabChip(icon: ImageVector, label: String, sel: Boolean, onPick: () -> Unit) {
        val shape = deckShape(4.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(if (sel) Modifier.focusRequester(stripFocus) else Modifier)
                .focusProperties { down = pageFocus }
                .deckFocusRing(shape, scaleTo = 1.03f)
                .clip(shape)
                .background(if (sel) accent.copy(alpha = 0.2f) else Color.Transparent)
                .clickable(onClick = onPick)
                .heightIn(min = 40.dp)
                .padding(horizontal = 10.dp),
        ) {
            // Icon only: the open page's name is the page title just below.
            Icon(icon, contentDescription = label, tint = if (sel) accent else com.winlator.star.ui.simple.SimpleMuted, modifier = Modifier.size(20.dp))
        }
    }

    // The running game's pictures (Game details › Artwork › In-game menu, else the app pages'): its art faded behind
    // the options, its icon and logo at the top in place of the name.
    val context = androidx.compose.ui.platform.LocalContext.current
    val gamePath by DeckInGameState.gameShortcutPath.collectAsState()
    val game = remember(gamePath) { gamePath?.let { com.winlator.star.ui.simple.loadLabShortcut(context, it) } }
    LaunchedEffect(game) { game?.let { com.winlator.star.ui.simple.LabArt.resolve(context, it) } }
    val artTick = com.winlator.star.ui.simple.LabArt.tick
    val art = remember(game, artTick) { game?.let { com.winlator.star.ui.simple.LabArt.inGameBackground(it) }.orEmpty() }
    val logo = remember(game, artTick) {
        game?.let { g -> com.winlator.star.ui.simple.LabArt.inGameModels(g, "logo").map { com.winlator.star.ui.simple.LabArt.Pic(it) } }.orEmpty()
    }
    val panelTop = com.winlator.star.ui.simple.SimplePanelTop
    val panelBottom = com.winlator.star.ui.simple.SimplePanelBottom
    val line = com.winlator.star.ui.simple.SimpleLine
    val hasArt = art.isNotEmpty()
    Box(
        if (hasArt) modifier
        else modifier.shadow(26.dp, androidx.compose.ui.graphics.RectangleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black),
    ) {
        if (hasArt) {
            // No hard edge over art: the last 16 dp of the panel fade out, so its side is soft where it meets the game.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val from = ((size.width - 16.dp.toPx()) / size.width).coerceIn(0f, 1f)
                        val span = 1f - from
                        drawRect(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                0f to Color.Black, from to Color.Black, from + span * 0.4f to Color.Black.copy(alpha = 0.75f),
                                from + span * 0.75f to Color.Black.copy(alpha = 0.3f), 1f to Color.Transparent,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    },
            ) {
                com.winlator.star.ui.simple.LabPicture(art, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(panelTop.copy(alpha = 0.45f), panelBottom.copy(alpha = 0.6f), panelBottom.copy(alpha = 0.74f))),
                    ),
                )
            }
        } else {
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(panelTop.copy(alpha = 0.97f), panelBottom.copy(alpha = 0.98f)))))
        }
    // Over the game's art, chips and buttons are see-through too (less than the cards).
    androidx.compose.runtime.CompositionLocalProvider(LocalIgRaisedAlpha provides if (art.isNotEmpty()) 0.6f else null) {
    Column(
        modifier = Modifier
            .matchParentSize()
            .then(if (hasArt) Modifier else Modifier.border(1.dp, line))
            .focusProperties { canFocus = false }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        // The icon on the left, the logo in the middle (the name on the left when there is no logo), the status on the right.
        val hasIcon = game != null && com.winlator.star.ui.simple.LabArt.pick(game, "igicon") != "none"
        Box(Modifier.fillMaxWidth().height(60.dp).padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
            val name: @Composable () -> Unit = {
                Text(
                    title.ifEmpty { L("In game") }, color = com.winlator.star.ui.simple.SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = if (hasIcon && game != null) 52.dp else 0.dp).fillMaxWidth(0.62f),
                )
            }
            if (hasIcon && game != null) com.winlator.star.ui.simple.LabInGameIcon(game, Modifier.align(Alignment.CenterStart).size(42.dp))
            if (logo.isEmpty()) name()
            else com.winlator.star.ui.simple.LabPicture(
                logo, Modifier.align(Alignment.Center).fillMaxWidth(0.46f).fillMaxHeight(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit, alignment = Alignment.Center, onNone = name,
            )
            Box(Modifier.align(Alignment.CenterEnd)) { DeviceStatus() }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = 16.dp).width(30.dp).height(3.dp).background(accent))
        Spacer(Modifier.height(8.dp))
        // The pages: Quick, then the full menu's, as icons (the open one tinted; its name is the page title).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            DeckGlyph("L1", GlyphKind.BUMPER)
            TabChip(Icons.Outlined.Bolt, L("Quick"), quick) { DeckInGameState.selectLabQuick() }
            tabs.forEach { t -> TabChip(menuTabIcon(t), menuTabTitle(t), !quick && t == shown) { DeckInGameState.selectMenuTab(t) } }
            DeckGlyph("R1", GlyphKind.BUMPER)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(com.winlator.star.ui.simple.SimpleLine))
        val pageTitle = if (quick) L("Quick") else menuTabTitle(shown)
        val pageDesc = if (quick) L("The settings changed most during a game.") else menuTabDesc(shown, kind)
        val pageHeader: @Composable () -> Unit = {
            Text(pageTitle, color = com.winlator.star.ui.simple.SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Text(pageDesc, color = com.winlator.star.ui.simple.SimpleMuted, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(4.dp))
        }
        if (!quick && shown == MenuTab.FRIENDS) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).focusRequester(pageFocus)) {
                pageHeader()
                Spacer(Modifier.height(8.dp))
                IgHostedCard { FriendsContent(state) }
            }
        } else {
            val scroll = key(quick, shown) { rememberScrollState() }
            // Over the game's art the cards are glass, so the art shows through the options too.
            androidx.compose.runtime.CompositionLocalProvider(LocalDeckCardFill provides if (art.isNotEmpty()) panelBottom.copy(alpha = 0.42f) else null) {
            Column(Modifier.weight(1f).fillMaxWidth().igPageScroll(scroll).padding(horizontal = 12.dp, vertical = 10.dp)) {
                pageHeader()
                Column(Modifier.fillMaxWidth().focusRequester(pageFocus)) {
                    if (quick) QuickControls(kind)
                    else when (shown) {
                        MenuTab.SCREEN -> ScreenPage(state)
                        MenuTab.PERFORMANCE -> PerformancePage(state)
                        MenuTab.EFFECTS -> EffectsPage(state, kind)
                        MenuTab.OVERLAY -> OverlayPage(state)
                        MenuTab.CONTROLS -> ControlsPage(state, kind)
                        MenuTab.AUDIO -> AudioPage(state)
                        MenuTab.STEAM -> SteamPage(state)
                        MenuTab.TV -> TvPage(state)
                        MenuTab.MODS -> ModsPage(state, mods)
                        MenuTab.TOOLS -> ToolsPage(state, kind)
                        MenuTab.FRIENDS -> Unit
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(com.winlator.star.ui.simple.SimpleCard)
                .then(
                    if (hasArt) Modifier.drawBehind { drawLine(line, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
                    else Modifier.border(1.dp, line),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            IgButton(if (isPaused) L("Resume") else L("Pause"), if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, ::pauseAndClose, modifier = Modifier.weight(1f))
            IgButton(L("Exit game"), Icons.AutoMirrored.Outlined.Logout, onExit, danger = true, modifier = Modifier.weight(1f))
        }
    }
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
internal fun ScreenPage(state: XServerDrawerState) {
    val backend by state.runtimeBackend.collectAsState()
    val kind by DeckInGameState.sessionKind.collectAsState()
    // The Wine architecture and x86 translator mean nothing to a Linux runtime session.
    if (backend.isValid && kind.isWine) {
        IgSectionLabel(L("Running on"))
        IgCard {
            val fex = if (backend.showsFexMode) when (backend.fexMode) {
                FexMode.UNIXLIB -> L(" · unixlib")
                FexMode.DLL -> L(" · DLL")
                FexMode.NA -> ""
            } else ""
            IgRow("${backend.arch} · ${backend.translator}$fex", hint = L("Architecture, translator and how FEX is loaded."))
        }
    }

    IgSectionLabel(L("Size & position"))
    IgCard {
        FitToScreenRow(state)
        IgDivider()
        val alignment by state.screenAlignment.collectAsState()
        IgRow(L("Position on screen"), hint = L("Top or Bottom keeps the other half free for on-screen controls."), scopes = listOf(igGameScope()), wideControl = true) {
            IgSegmented(listOf(0 to L("Center"), 1 to L("Top"), 2 to L("Bottom")), alignment) { state.onSetScreenAlignment?.accept(it) }
        }
    }

    val vulkan by state.rendererIsVulkan.collectAsState()
    if (vulkan) {
        IgSectionLabel(L("Frame pacing"))
        IgCard { PresentModeRow(state) }
    }

    IgSectionLabel(L("Refresh rate"))
    IgCard { RefreshRateRows(state) }
}

/** Present mode, with the same frame-generation lock the Classic section enforces (Mailbox while frames are generated). */
@Composable
private fun PresentModeRow(state: XServerDrawerState) {
    val mode by state.presentMode.collectAsState()
    val locked by state.presentModeLocked.collectAsState()
    IgRow(
        stringResource(R.string.renderer_present_mode),
        hint = L("How finished frames reach the screen: V-Sync (FIFO) never tears, Smooth (Mailbox) adds less delay, Fastest (Immediate) is lowest latency but may tear."),
        note = if (locked) stringResource(R.string.present_mode_fg_locked) else null,
        scopes = listOf(igGameScope()),
        wideControl = true,
    ) {
        IgSegmented(
            listOf("fifo" to L("V-Sync"), "mailbox" to L("Smooth"), "immediate" to L("Fastest")),
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
        !vrr -> L("Unavailable — this display has a single refresh rate, so there's nothing to match.")
        match && nativeFgLocks && fgAutoTurnedOn ->
            L("Turned on for frame generation — the display follows the frame limit × multiplier. ") +
                (if (fgAutoPerGame) L("Turn it off if you prefer; this game will remember.") else L("Turn it off if you prefer (for this session)."))
        match && nativeFgLocks -> L("On — with frame generation running, the display follows the frame limit × multiplier.")
        nativeFgLocks ->
            (if (fgAutoPerGame) L("Off for this game while frame generation runs. ") else L("Off while frame generation runs. ")) +
                L("Turn it on to fit the screen to the frame limit × multiplier.")
        match -> L("On — the display follows your FPS.")
        else -> L("Off — pick a rate below to lock the display.")
    }
    // Under native frame generation, Auto is a per-game opt-out (kept on the shortcut); otherwise it is a container setting.
    val matchScope = if (nativeFgLocks) igGameScope() else SaveScope.CONTAINER
    IgRow(L("Match refresh rate to FPS"), hint = explain, scopes = listOf(matchScope), enabled = vrr) {
        IgSwitch(match && vrr, enabled = vrr) {
            state.setMatchRefreshRate(it)
            state.onMatchRefreshChange?.run()
        }
    }
    if (rates.isNotEmpty()) {
        IgDivider()
        val lockOn = vrr && !match
        IgRow(
            L("Lock the display to"),
            hint = if (current > 0) L("The screen is at %1\$s Hz now.", current) else null,
            scopes = listOf(SaveScope.CONTAINER),
            enabled = lockOn,
            wideControl = true,
        ) {
            IgChips(listOf(0 to L("Off")) + rates.map { it to L("%1\$s Hz", it) }, manual, enabled = lockOn) {
                state.setManualRefreshRate(it)
                state.onManualRefreshChange?.run()
            }
        }
    }
}

// ── Performance ──
@Composable
internal fun PerformancePage(state: XServerDrawerState) {
    IgSectionLabel(L("Frame rate"))
    IgCard { FrameLimitRow(state) }

    IgSectionLabel(L("Frame generation"))
    IgHostedCard {
        FrameGenSection(state)
        SaveNotes(
            L("On / off and multiplier (starts off each launch)") to listOf(SaveScope.SESSION),
            L("Level, model, flow scale and performance mode") to listOf(SaveScope.CONTAINER),
        )
    }

    IgSectionLabel(L("Device power"))
    var perfDialog by remember { mutableStateOf(false) }
    val fromShortcut by DeckInGameState.launchedFromShortcut.collectAsState()
    IgCard {
        IgRow(
            L("Performance dashboard"),
            hint = L("CPU / GPU clocks, thermal, memory and power profile — live gauges, root-aware."),
            note = if (fromShortcut) L("Changes are kept for this game only where they differ from the global default.") else null,
            scopes = if (fromShortcut) listOf(SaveScope.GAME, SaveScope.ALL) else listOf(SaveScope.ALL),
        ) { IgButton(L("Open"), Icons.Outlined.Speed, { perfDialog = true }) }
    }
    if (perfDialog) PerformanceDashboardDialog(state) { perfDialog = false }
}

// ── Visual effects ──
@Composable
internal fun EffectsPage(state: XServerDrawerState, kind: InGameSessionKind) {
    val gl by XServerDialogState.effectsSupported.collectAsState()
    val wayland by state.isWaylandMode.collectAsState()
    val game = igGameScope()
    IgSectionLabel(L("Upscaling, sharpening & colour"))
    IgHostedCard {
        GraphicsEffectsSection(state)
        SaveNotes(
            L("Upscaling filter, sharpening and debanding") to listOf(game),
            "HDR" to listOf(if (gl) SaveScope.SESSION else game),
            L("Look, brightness, contrast, gamma, saturation, FXAA / CRT / Toon / NTSC") to listOf(SaveScope.SESSION),
            L("Native rendering") to listOf(SaveScope.SESSION),
        )
        if (wayland) {
            SaveNotes(
                L("Wayland HDR output") to listOf(SaveScope.SESSION),
                L("Zero-copy presentation and OpenGL safe mode") to listOf(game),
            )
        }
    }
    // ReShade is a Wine-side layer, as on the Classic rail.
    if (kind.isWine) {
        IgSectionLabel(L("ReShade"))
        IgHostedCard {
            ReshadeSection()
            SaveNotes(
                L("ReShade on / off, Solo / Stack, effects and their settings") to listOf(game),
                L("Live preview") to listOf(SaveScope.ALL),
            )
        }
    }
}

// ── Performance overlay ──
@Composable
internal fun OverlayPage(state: XServerDrawerState) {
    IgSectionLabel(L("Overlay"))
    IgHostedCard {
        HudContent(state, embedded = true)
        SaveNotes(
            L("Everything on this page") to listOf(hudScope()),
            L("Turning the overlay on when it wasn't built this launch also switches it on in the container") to listOf(SaveScope.CONTAINER),
        )
    }
    IgSectionLabel(L("On the game"))
    IgCard {
        IgInfo(L("Tap the overlay to flip it between vertical and horizontal (Fusion: next size). Drag it to move it; long-press to lock it in place."))
    }
}

// ── Controls ──
@Composable
internal fun ControlsPage(state: XServerDrawerState, kind: InGameSessionKind) {
    val sub by state.controlsSubTab.collectAsState()
    val game = igGameScope()
    // Pad and the rumble rows follow the drawer's "Save for" footer (ControllerSaveTarget).
    val saveScope by XServerDialogState.controllerSaveScope.collectAsState()
    val padScope = if (saveScope.gameName != null && saveScope.toGame) SaveScope.GAME else SaveScope.CONTAINER
    IgHostedCard {
        // Player slots are Wine's XInput slots; the Linux Steam client keeps its own touch choice (its Steam page).
        ControlsContent(state, playerSlots = kind.isWine, pointerAndTouch = kind != InGameSessionKind.LINUX_STEAM)
        when (sub) {
            0 -> SaveNotes(
                L("Show on-screen controls and the layout you pick") to listOf(SaveScope.SESSION),
                L("Choosing no layout, timeout, haptics and opacity") to listOf(SaveScope.ALL),
                L("Controls accent") to listOf(SaveScope.other(L("Saved in the controls profile"))),
            )
            1 -> SaveNotes(
                L("Touch") to listOf(game),
                L("Aim mode") to listOf(game),
                L("Touch gestures") to listOf(SaveScope.ALL),
                L("Disable mouse") to listOf(SaveScope.SESSION),
            )
            2 -> SaveNotes(
                L("Rumble on / off and per-slot switches") to listOf(SaveScope.ALL),
                L("Rumble target, intensity and motors (Save for, below)") to listOf(padScope),
            )
            3 -> SaveNotes(
                L("Motion aim settings") to listOf(game),
                L("Deadzone and smoothing") to listOf(SaveScope.CONTAINER),
            )
            4 -> SaveNotes(L("Player slot for each controller") to listOf(game))
            6 -> SaveNotes(L("Controller type, XInput source, DirectInput, stick updates (Save for, below)") to listOf(padScope))
            5 -> SaveNotes(L("Swipe between buttons") to listOf(SaveScope.ALL))
        }
    }
}

// ── Audio ──
@Composable
internal fun AudioPage(state: XServerDrawerState) {
    val ctx = LocalContext.current
    val driverId = state.audioDriverId
    val engine = state.audioDriverLabel
    val fromShortcut by DeckInGameState.launchedFromShortcut.collectAsState()
    var cfg by remember { mutableStateOf(com.winlator.star.ui.components.loadAudioConfig(ctx, driverId)) }
    var show by remember { mutableStateOf(false) }
    val presetLabel = when (cfg.preset) {
        com.winlator.star.ui.components.PRESET_AUTO -> L("Auto")
        com.winlator.star.ui.components.PRESET_LOW -> L("Low latency")
        com.winlator.star.ui.components.PRESET_BALANCED -> L("Balanced")
        com.winlator.star.ui.components.PRESET_STABLE -> L("No crackle")
        com.winlator.star.ui.components.PRESET_CUSTOM -> L("Custom")
        else -> cfg.preset
    }
    IgSectionLabel(L("Sound"))
    IgCard {
        IgRow(
            L("Audio profile"),
            hint = (if (engine.isNotBlank()) "$engine · " else "") + L("%1\$s. Balances crackle against delay; applies now, the guest buffer at the next launch.", presetLabel),
            scopes = if (fromShortcut) listOf(SaveScope.GAME, SaveScope.ALL) else listOf(SaveScope.ALL),
        ) { IgButton(L("Presets & tuning…"), Icons.Outlined.VolumeUp, { show = true }) }
        IgDivider()
        IgRow(
            L("Restart audio"),
            hint = L("Fixes crackle or silence without restarting the game."),
            scopes = listOf(SaveScope.SESSION),
        ) { IgButton(L("Restart audio"), null, { state.onResetAudio?.run() }) }
    }
    if (show) {
        com.winlator.star.ui.components.AudioSettingsDialog(
            initial = cfg,
            scopeLabel = if (fromShortcut) L("applies now · saved for this game and as this engine's default") else L("applies now · saved as this engine's default"),
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
internal fun SteamPage(state: XServerDrawerState) {
    IgSectionLabel(L("Steam client"))
    IgHostedCard {
        LinuxSteamSection(state)
        SaveNotes(L("Touch mode and every switch here") to listOf(SaveScope.GAME))
    }
    IgSectionLabel(L("Components"))
    IgHostedCard {
        LinuxComponentsContent(state)
        SaveNotes(L("FEX / DXVK / VKD3D choices") to listOf(SaveScope.other(L("Saved in that Proton, for every game using it"))))
    }
}

// ── TV ──
@Composable
internal fun TvPage(state: XServerDrawerState) {
    IgHostedCard {
        TvContent(state)
        SaveNotes(
            L("Play on TV, auto-switch, safe area, audio output, dimming and TV resolution") to listOf(SaveScope.CONTAINER),
            L("Display mode") to listOf(SaveScope.SESSION),
            L("Fit, scaling filter, frame pacing, frame cap and frame generation save as on their own pages") to emptyList<SaveScope>(),
        )
    }
}

// ── Tools ──
@Composable
internal fun ToolsPage(state: XServerDrawerState, kind: InGameSessionKind) {
    val closeThen: (Runnable?) -> Unit = { r -> DeckInGameState.close(); r?.run() }
    var perfDialog by remember { mutableStateOf(false) }
    // The XMB style types on its own panel, which a controller opens and closes with Select + X; every other style keeps Android's keyboard.
    val xmbKeyboard = isXmbHome(LocalContext.current)
    IgSectionLabel(L("View"))
    IgCard {
        IgRow(L("Magnifier"), hint = L("Zoom into part of the screen.")) { IgButton(L("Magnifier"), Icons.Outlined.ZoomIn, { closeThen(state.onMagnifier) }) }
        IgDivider()
        IgRow(L("Picture-in-picture"), hint = L("Shrink the game into a floating window.")) { IgButton(L("Picture-in-picture"), Icons.Outlined.PictureInPictureAlt, { closeThen(state.onPipMode) }) }
        IgDivider()
        IgRow(L("Show keyboard"), hint = stringResource(if (xmbKeyboard) R.string.ig_keyboard_hint_xmb else R.string.ig_keyboard_hint_android)) { IgButton(L("Show keyboard"), Icons.Outlined.Keyboard, { closeThen(state.onKeyboard) }) }
    }

    IgSectionLabel(if (kind.isWine) L("Windows & logs") else L("Performance"))
    IgCard {
        // Wine's windows, its log and its XInput slots: Wine sessions only.
        if (kind.isWine) {
            IgRow(L("Switch window"), hint = L("Bring another of the game's windows to the front.")) { IgButton(L("Switch window…"), Icons.Outlined.ViewCarousel, { closeThen(state.onActiveWindows) }) }
            IgDivider()
            IgRow(L("Live log"), hint = L("Wine and Box64 / FEX output while logging is on.")) { IgButton(L("Live log"), Icons.Outlined.Description, { closeThen(state.onLogs) }) }
            IgDivider()
            IgRow(L("Controller test & remap"), hint = L("Check buttons and sticks, bind a physical profile, set player slots.")) {
                IgButton(L("Controller test"), Icons.Outlined.SportsEsports, { XServerDialogState.show(XServerDialogState.ActiveDialog.CONTROLLER_TEST) })
            }
            IgDivider()
        }
        IgRow(L("Performance dashboard"), hint = L("Clocks, thermals, memory and power profile.")) { IgButton(L("Open"), Icons.Outlined.Speed, { perfDialog = true }) }
    }
    if (perfDialog) PerformanceDashboardDialog(state) { perfDialog = false }

    IgSectionLabel(L("Opening this menu"))
    val corner by DeckInGameState.cornerButton.collectAsState()
    IgCard {
        IgRow(
            L("Menu button in the corner"),
            hint = L("A faint button at the top right that opens the quick menu by touch. Back, the Menu button or a four-finger tap work without it."),
            scopes = listOf(SaveScope.ALL),
        ) { IgSwitch(corner) { DeckInGameState.changeCornerButton(it) } }
    }

    // The Task Manager's list is Wine's processes.
    if (kind.isWine) {
        IgSectionLabel(L("Running programs"))
        RunningPrograms(state)
    }
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
            IgInfo(L("No programs reported yet."))
        } else {
            processes.forEachIndexed { i, p ->
                if (i > 0) IgDivider()
                IgRow(p.name + if (p.wow64) L(" (32-bit)") else "", hint = L("PID %1\$s · %2\$s", p.pid, p.formattedMemory), wideControl = true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IgButton(L("Cores"), Icons.Outlined.Memory, { affinityFor = p })
                        IgButton(L("Front"), Icons.Outlined.FlipToFront, { XServerDialogState.onTmBringToFront?.invoke(p.name, p.pid) })
                        IgButton(L("End"), Icons.Outlined.Close, { endFor = p }, danger = true)
                    }
                }
            }
        }
        IgDivider()
        IgRow(L("Run a program"), hint = L("Start another program in this session.")) {
            IgButton(L("Run…"), Icons.Outlined.Terminal, {
                XServerDialogState.onTmDismissed?.run()
                (XServerDialogState.onRunProgramPicker ?: XServerDialogState.onTmNewTask)?.run()
            })
        }
    }
    Text(
        L("CPU core choices last until the program restarts."),
        color = cs.onSurfaceVariant,
        fontFamily = NunitoSansFamily,
        fontSize = 12.5.sp,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp),
    )

    affinityFor?.let { p -> ProcessorAffinityDialog(proc = p, onDismiss = { affinityFor = null }) }
    endFor?.let { p ->
        IgConfirmDialog(
            title = L("End %1\$s?", p.name),
            body = L("The program is closed straight away. Anything it hasn't saved is lost."),
            confirmLabel = L("End process"),
            confirmIcon = Icons.Outlined.Close,
            onConfirm = {
                endFor = null
                XServerDialogState.onTmKillProcess?.invoke(p.name)
            },
            onDismiss = { endFor = null },
        )
    }
}
