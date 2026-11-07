package com.winlator.star.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.InputDevice
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleEventObserver
import com.winlator.star.R
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Shortcut
import com.winlator.star.inputcontrols.ExternalController
import com.winlator.star.ui.LocalTopBarOverlayInset
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.XmbStoreMark
import com.winlator.star.ui.deck.rememberScreenScale
import com.winlator.star.ui.deck.rememberXmbLogo
import com.winlator.star.ui.deck.storeMarkOf
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.theme.DangerRed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One row of the XMB options column: the same entries as a game card's ⋮ menu. */
internal data class XmbAction(
    val label: String,
    val icon: ImageVector,
    val subtitle: String? = null,
    val danger: Boolean = false,
    /** Opens a nested XMB column (Settings, Game Details, tools…) instead of running [onClick]. */
    val menu: ((XmbScope) -> XmbMenu)? = null,
    /** An upper-row card's picture (a store's logo on its colours); without one the card shows [icon]. */
    val art: XmbCardArt? = null,
    val onClick: () -> Unit = {},
)

/**
 * An upper-row card's background, drawn edge to edge on the card and, faintly blurred, behind the home: a store's
 * [logo] on its brand colours, or with no logo the card's own icon in white on [colors]. [tintWhite] = draw the logo white.
 */
internal data class XmbCardArt(@DrawableRes val logo: Int?, val colors: List<Color>, val tintWhite: Boolean = false)

/**
 * Games-tab XMB layout (PS3 cross media bar). Games run along a horizontal bar and the focused
 * one sits enlarged at the "cross"; the column under it holds that game's options, with Play first.
 * Every size is derived from the content box, so portrait and landscape are the same code; a
 * rotation only re-measures. Touch: swipe the bar (with fling), tap a cover to focus it, tap the
 * focused cover to launch, swipe or tap the column. Controller / D-pad: Left/Right games, Up/Down
 * options, A select, B back to Play, L1/R1 jump 5. Up on Play is left unhandled, so focus can
 * climb to the top bar. [centeredHome] turns it into the Deck's PS4-style home (see there).
 */
@Composable
internal fun ShortcutsXmbView(
    shortcuts: List<Shortcut>,
    selectionMode: Boolean,
    selectedPaths: Set<String>,
    onToggleSelect: (Shortcut) -> Unit,
    onExitSelection: () -> Unit,
    onPlay: (Shortcut) -> Unit,
    actionsFor: (Shortcut) -> List<XmbAction>,
    storeBadges: @Composable (Shortcut) -> Unit,
    sdBadge: @Composable (Shortcut) -> Unit,
    /** The game list changed (rename, clone, remove, new exe…): reload it. */
    onReloadGames: () -> Unit = {},
    /** True while a nested menu is open (the Games tab hides its + button). */
    onNestedChange: (Boolean) -> Unit = {},
    /** The game under the cursor changed (the Deck home tints its backdrop and aims its game menu with it). */
    onFocusedChange: (Shortcut) -> Unit = {},
    /**
     * Drawn inside the Deck shell: the shell keeps Android's bars as it set them, and L1/R1 at the top
     * level fall through to it (they move its tabs, as on every Deck page) instead of jumping five games.
     */
    inDeckShell: Boolean = false,
    /** The top-left controller hints (root and nested menus). Off when the host has its own. */
    showHints: Boolean = true,
    /** The top-right "n / N" counter and clock. Off when the host draws its own floating status there. */
    showClock: Boolean = true,
    /**
     * [centeredHome] only: cards for the row above the games. Up from the centred row opens it, the games slide
     * down half off the screen, Left/Right pick a card, A runs it, Down or B go back. Empty = no row.
     */
    upperRow: List<XmbAction> = emptyList(),
    /**
     * PS4-style home: the games rest centred with no options and the [upperRow] cards peeking above them.
     * A (or a tap on the focused cover) shows the options; B, Up on Play or moving to another game hides them again.
     */
    centeredHome: Boolean = false,
    /** Asked before the view takes D-pad focus (entry, resume): false leaves focus where it is. */
    takeFocus: () -> Boolean = { true },
    /** Changes when the host wants the view to take focus again (the Deck shell, when a page should take its first focus). */
    focusTick: Int = 0,
    /**
     * Every game the host has. Given, the row ends with an All Games card that opens the All Games page over this view, and
     * [shortcuts] is the part of the library the row shows (the host limits it to the most recent games). With no games the
     * card is the whole row.
     */
    allShortcuts: List<Shortcut>? = null,
) {
    if (shortcuts.isEmpty() && allShortcuts == null) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val n = shortcuts.size
    // The All Games card is the slot after the last game (slot n), so a game's slot is its index in `shortcuts` and the row's
    // index maths (target, pos, the swipes, the counters) runs on slots; slot 0 is the first game.
    val hasAll = allShortcuts != null
    val slots = n + if (hasAll) 1 else 0
    val allTotal = allShortcuts?.size ?: 0
    val allCount = remember(allTotal) { context.resources.getQuantityString(R.plurals.xmb_all_games_count, allTotal, allTotal) }
    val allLabel = stringResource(R.string.xmb_all_games_open)
    var allOpen by rememberSaveable { mutableStateOf(false) }
    // What the All Games card shows of the library (the mosaic on the card, the collage behind it while it is hovered): the
    // games the row shows come first (the most recent), then the rest, games with art ahead of those without. Built from the
    // covers already decoded for the row, and kept until the game list changes.
    val libraryOrder = remember(shortcuts, allShortcuts) { if (hasAll) xmbRecentOrder(shortcuts, allShortcuts.orEmpty()) { it.file.path } else emptyList() }
    val mosaic = remember(libraryOrder) { xmbMosaicPick(libraryOrder) { it.icon != null } }
    val collageArts = remember(libraryOrder) { xmbCollagePick(libraryOrder) { it.icon != null }.mapNotNull { it.icon } }

    // The focused game is remembered by path so it survives rotation, leaving the tab, and a
    // refresh or sort that reorders the list. The All Games card is remembered as XMB_ALL_PATH.
    var focusedPath by rememberSaveable { mutableStateOf<String?>(null) }
    fun slotOfPath(path: String?): Int = xmbSlotOf(shortcuts.map { it.file.path }, path, hasAll)
    // Opens on the first game (slot 0) unless the cursor was somewhere else.
    var target by remember { mutableIntStateOf(slotOfPath(focusedPath).takeIf { it >= 0 } ?: 0) }
    val pos = remember { mutableFloatStateOf(target.toFloat()) } // visual position, fractional mid-swipe
    var act by remember { mutableIntStateOf(0) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // Centred home: whether the focused game's options are showing. They belong to one game, so moving closes them.
    var optsOpen by remember { mutableStateOf(false) }
    val centered = centeredHome && !optsOpen

    LaunchedEffect(shortcuts, hasAll) {
        val i = slotOfPath(focusedPath)
        val idx = if (i >= 0) i else target.coerceIn(0, slots - 1)
        settleJob?.cancel()
        target = idx
        pos.floatValue = idx.toFloat()
    }
    // The game under the cursor, or null on the All Games card (the slot after the last game), which has no game behind it.
    val focused: Shortcut? = if (hasAll && target >= n) null else shortcuts[target.coerceIn(0, n - 1)]
    val onAll = focused == null
    val focusKey = focused?.file?.path ?: XMB_ALL_PATH
    LaunchedEffect(focusKey) {
        focusedPath = focusKey
        if (focused != null) onFocusedChange(focused)
    }

    // Moving to another game always lands back on Play, so A launches (like tapping a card).
    fun settleTo(i: Int) {
        val t = i.coerceIn(0, slots - 1)
        if (t != target) { target = t; act = 0; optsOpen = false }
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(pos.floatValue, t.toFloat(), animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f)) { v, _ ->
                pos.floatValue = v
            }
        }
    }

    val playtime = remember(focused) { focused?.let { xmbPlaytime(context, it.name) } }
    val isSelected = focused != null && focused.file.path in selectedPaths
    // The All Games card offers just "Open".
    val actions = if (focused == null) listOf(XmbAction(allLabel, Icons.Filled.GridView, allCount) { allOpen = true }) else buildList<XmbAction> {
        add(
            if (selectionMode) XmbAction(if (isSelected) "Deselect" else "Select", Icons.Filled.Checklist) { onToggleSelect(focused) }
            else XmbAction("Play", Icons.Filled.PlayArrow, playtime?.let { "$it played" } ?: "Not played yet") { onPlay(focused) }
        )
        addAll(actionsFor(focused))
    }
    val actIdx = act.coerceIn(0, actions.lastIndex)

    // Nested XMB menus (Settings, Game Details, tools…): model in XmbModel.kt, drawing in XmbMenuHost.kt.
    val nav = remember { XmbNavState() }
    val nested = nav.depth > 0
    val reload by rememberUpdatedState(onReloadGames)
    val appCtx = context
    val coScope = scope
    val xmbScope = remember(nav) {
        object : XmbScope {
            override val context: Context get() = appCtx
            override val scope: CoroutineScope get() = coScope
            override fun push(menu: XmbMenu) = nav.push(menu)
            override fun pop() = nav.pop()
            override fun popToRoot() = nav.popTo(0)
            override fun refresh() = nav.bump()
            override fun toast(message: String) { Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show() }
            override fun saved() { nav.savedAt = System.currentTimeMillis() }
            override fun confirm(confirm: XmbConfirm, onOk: () -> Unit) = nav.push(xmbConfirmMenu(nav, confirm, onOk))
            override fun reloadGames() = reload()
        }
    }
    fun runAction(a: XmbAction) { val mk = a.menu; if (mk != null) nav.push(mk(xmbScope)) else a.onClick() }
    // The All Games page counts as nested for the host: the Games tab's floating + button would sit on the page's last row.
    LaunchedEffect(nested, allOpen) { onNestedChange(nested || allOpen) }
    DisposableEffect(Unit) { onDispose { onNestedChange(false) } }
    BackHandler(enabled = nested) { nav.pop() }

    // The row above the games, a second bar that runs like the games: the chosen card sits enlarged at the cross with
    // the earlier ones to its left. Leaving it always lands back on Play; opening a nested menu closes it. Whether it's
    // open and which card is chosen (by its label) are saved, so coming back from a card's own screen (a store,
    // Downloads...) or a recreated activity finds the row as it was left.
    var upOpen by rememberSaveable { mutableStateOf(false) }
    var upLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var upSel by remember { mutableIntStateOf(upperRow.indexOfFirst { it.label == upLabel }.coerceAtLeast(0)) }
    val upPos = remember { mutableFloatStateOf(upSel.toFloat()) } // the cards' visual position, fractional mid-swipe
    var upJob by remember { mutableStateOf<Job?>(null) }
    val upperOpen = upOpen && upperRow.isNotEmpty() && !nested
    val upIdx = upSel.coerceIn(0, (upperRow.size - 1).coerceAtLeast(0))
    // The card list changes while the screen is up (Friends appears or goes): keep the same card chosen, by its label; a card
    // that is gone falls back to the first.
    LaunchedEffect(upperRow) {
        val i = upperRow.indexOfFirst { it.label == upLabel }
        when {
            i >= 0 && i != upSel -> { upJob?.cancel(); upSel = i; upPos.floatValue = i.toFloat() }
            i < 0 && upLabel != null && upperRow.isNotEmpty() -> { upJob?.cancel(); upSel = 0; upPos.floatValue = 0f }
        }
    }
    LaunchedEffect(upIdx, upperRow) { upLabel = upperRow.getOrNull(upIdx)?.label }
    val upT by animateFloatAsState(if (upperOpen) 1f else 0f, tween(300), label = "xmbUpper")
    fun upSettle(i: Int) {
        val t = i.coerceIn(0, (upperRow.size - 1).coerceAtLeast(0))
        upSel = t
        upJob?.cancel()
        upJob = scope.launch {
            animate(upPos.floatValue, t.toFloat(), animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f)) { v, _ ->
                upPos.floatValue = v
            }
        }
    }
    // Up from the games lands on the card that was chosen when the row was left (the first one the first time), as the games
    // row lands on its game.
    fun openUpper() {
        upJob?.cancel()
        val i = upperRow.indexOfFirst { it.label == upLabel }.coerceAtLeast(0)
        upSel = i
        upPos.floatValue = i.toFloat()
        upOpen = true
    }
    // The chosen card stays chosen (and the peeking row stays on it) for the next time the row opens.
    fun closeUpper() { upOpen = false; act = 0 }
    LaunchedEffect(nested) { if (nested) upOpen = false }
    BackHandler(enabled = upperOpen) { closeUpper() }
    fun closeOpts() { optsOpen = false; act = 0 }
    BackHandler(enabled = centeredHome && optsOpen && !nested && !upperOpen) { closeOpts() }
    // The All Games page is open: Back closes it onto its card. Registered after the others, so it is asked first (the page's own
    // BackHandler, which closes its sort menu before the page, is registered later still and takes the press before this one).
    BackHandler(enabled = allOpen) { allOpen = false }
    // The Deck shell leaves its floating tab bar out while the page is up (the page has its own header there). Cleared again when the
    // page closes or this view leaves the composition (another tab, the mode switched); a recreated activity restores allOpen, so the
    // effect sets it again.
    val deckActions = LocalDeckActions.current
    DisposableEffect(allOpen, inDeckShell, deckActions) {
        if (inDeckShell && allOpen) deckActions.xmbAllGamesOpen.value = true
        onDispose { deckActions.xmbAllGamesOpen.value = false }
    }
    // 1 = centred (no options), 0 = the options layout; the cards peek above the centred row.
    val cT by animateFloatAsState(if (centered) 1f else 0f, tween(300), label = "xmbCentred")
    val peekT by animateFloatAsState(if (centered && upperRow.isNotEmpty() && !upperOpen && !nested) 1f else 0f, tween(300), label = "xmbPeek")

    // Take D-pad focus on entry and again whenever the app comes back (e.g. after a game exits).
    val focusRequester = remember { FocusRequester() }
    nav.refocus = { runCatching { focusRequester.requestFocus() } }
    val hostMayTakeFocus by rememberUpdatedState(takeFocus)
    // The All Games page holds the keys while it is up (it takes focus back itself when the app returns).
    val pageUp by rememberUpdatedState(allOpen)
    val mayTakeFocus = { hostMayTakeFocus() && !pageUp }
    LaunchedEffect(focusTick) { if (mayTakeFocus()) runCatching { focusRequester.requestFocus() } }
    // Closing the page hands the keys back to the row, with its card still under the cursor.
    var pageWasUp by remember { mutableStateOf(allOpen) }
    LaunchedEffect(allOpen) {
        if (pageWasUp && !allOpen) runCatching { focusRequester.requestFocus() }
        pageWasUp = allOpen
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && mayTakeFocus()) runCatching { focusRequester.requestFocus() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Landscape XMB is a couch front end: the Android status bar and nav buttons are hidden (an edge
    // swipe brings them back briefly). Shown again on rotating to portrait, switching view, or leaving the tab.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current
    DisposableEffect(landscape, lifecycleOwner, inDeckShell) {
        if (inDeckShell) return@DisposableEffect onDispose {}
        val window = context.findActivity()?.window
        val bars = if (landscape && window != null) WindowCompat.getInsetsController(window, view) else null
        val hideNav = {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        hideNav()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) hideNav() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // Space under a see-through top bar: the backdrop fills it, the bar/cover layout starts below it.
    val topInset = LocalTopBarOverlayInset.current

    // The clock ticks, and the pad is watched, only while something shows them: a host with its own status line and no
    // hints would otherwise recompose this view every 15 s for nothing.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (showClock) LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000L) } }
    val clock = remember(now / 60_000L) {
        DateUtils.formatDateTime(
            context, now,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_ALL,
        ) + "  " + DateUtils.formatDateTime(context, now, DateUtils.FORMAT_SHOW_TIME)
    }
    val hasPad = showHints && rememberGamepadConnected()

    // Centred home: the edge the cards' peek is cut at, clear of the host's floating tab bar, scaled with the screen
    // as that bar is.
    val ss = rememberScreenScale()
    val clipTop = ss.dp(XMB_PEEK_CLIP).value
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val m = remember(maxWidth, maxHeight, topInset) { XmbMetrics(maxWidth.value, maxHeight.value - topInset.value) }
        // The collage behind the hovered All Games card: a 5 x 3 grid of covers (repeated when the library is small), drawn once
        // off the main thread.
        val collageSpec = xmbCollageSpec(maxWidth.value / maxHeight.value.coerceAtLeast(1f), collageArts.size)
        val collageState = remember(collageArts, collageSpec) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(collageState, onAll) {
            if (onAll && collageSpec != null && collageState.value == null) {
                collageState.value = withContext(Dispatchers.Default) { xmbCollageBitmap(collageArts, collageSpec) }
            }
        }
        // Centred home: the row is drawn larger while it's the only thing on screen (and under the open cards),
        // as large as fits between the peeking cards and the bottom edge, at most XMB_HOME_MAX_GROW. The options shrink it back.
        val homeK = if (!centeredHome) 1f
            else min(XMB_HOME_MAX_GROW, (m.h / 2f - clipTop - m.sH / 2f - 10f) * 2f / m.fH.coerceAtLeast(1f)).coerceAtLeast(1f)
        // Read where it's used (draw / drag), so the animation doesn't recompose the view.
        val rowScale = { 1f + (homeK - 1f) * cT * (1f - upT) }
        val pitchPx = with(density) { m.pitch.dp.toPx() }
        val rowPx = with(density) { m.rowH.dp.toPx() }

        var swipe by remember { mutableFloatStateOf(0f) }
        val swipeBackPx = with(density) { 60.dp.toPx() }
        val hDrag = rememberDraggableState { delta ->
            if (nav.depth > 0) { swipe += delta; return@rememberDraggableState }
            // The open cards follow the finger like the games (at their larger size).
            if (upperOpen) {
                upJob?.cancel()
                val p = (upPos.floatValue - delta / (pitchPx * homeK)).coerceIn(-0.45f, upperRow.size - 1 + 0.45f)
                upPos.floatValue = p
                upSel = p.roundToInt().coerceIn(0, upperRow.lastIndex)
                return@rememberDraggableState
            }
            settleJob?.cancel()
            // One cover's step on screen, so the row follows the finger at the larger size too.
            val p = (pos.floatValue - delta / (pitchPx * rowScale())).coerceIn(-0.45f, slots - 1 + 0.45f)
            pos.floatValue = p
            val r = p.roundToInt().coerceIn(0, slots - 1)
            if (r != target) { target = r; act = 0; optsOpen = false }
        }
        var vAcc by remember { mutableFloatStateOf(0f) }
        val vDrag = rememberDraggableState { d ->
            if (nav.depth > 0 || upperOpen || centered) return@rememberDraggableState
            vAcc += d
            val step = rowPx * 0.9f
            while (vAcc <= -step) { vAcc += step; act = (act + 1).coerceAtMost(actions.lastIndex) }
            while (vAcc >= step) { vAcc -= step; act = (act - 1).coerceAtLeast(0) }
        }

        // Centred home, touch: a short swipe moves between the levels the D-pad moves between. Down from the games lifts the
        // cards, down from the options goes back to the games, and up from the cards goes back to the games. The options'
        // own column scrolls with its vertical drag (vDrag), which takes a swipe that starts on it first, so only a swipe
        // away from them gets here.
        var swipeAcc by remember { mutableFloatStateOf(0f) }
        var swipeDone by remember { mutableStateOf(false) }
        val swipePx = with(density) { XMB_SWIPE_DP.dp.toPx() }
        val swipeDrag = rememberDraggableState { d ->
            if (swipeDone || !centeredHome || nav.depth > 0) return@rememberDraggableState
            swipeAcc += d
            when {
                swipeAcc >= swipePx -> {
                    swipeDone = true
                    when {
                        optsOpen -> closeOpts()
                        !upperOpen && upperRow.isNotEmpty() && !selectionMode -> openUpper()
                    }
                }
                swipeAcc <= -swipePx -> {
                    swipeDone = true
                    if (upperOpen) closeUpper()
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (nav.depth > 0) {
                        // Typing into a Text row: the field gets the keys; A = done, B = cancel.
                        if (nav.editingKey != null) {
                            return@onPreviewKeyEvent when (e.key) {
                                Key.ButtonA -> { nav.commitEdit(xmbScope); true }
                                Key.ButtonB -> { nav.pop(); true }
                                else -> false
                            }
                        }
                        val k = when (e.key) {
                            Key.DirectionUp -> XmbKey.Up
                            Key.DirectionDown -> XmbKey.Down
                            Key.DirectionLeft -> XmbKey.Left
                            Key.DirectionRight -> XmbKey.Right
                            Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> XmbKey.A
                            Key.ButtonB, Key.Escape -> XmbKey.B
                            Key.ButtonL1 -> XmbKey.L1
                            Key.ButtonR1 -> XmbKey.R1
                            else -> null
                        } ?: return@onPreviewKeyEvent false
                        return@onPreviewKeyEvent nav.onKey(k, xmbScope)
                    }
                    // The row above the games: Left/Right pick a card, the games stay where they are.
                    if (upperOpen) {
                        return@onPreviewKeyEvent when (e.key) {
                            Key.DirectionLeft -> { upSettle(upIdx - 1); true }
                            Key.DirectionRight -> { upSettle(upIdx + 1); true }
                            Key.DirectionUp -> true
                            Key.DirectionDown, Key.ButtonB, Key.Escape -> { closeUpper(); true }
                            // Running a card leaves the row open on it, so Back from that card's screen returns to the same card.
                            Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> { runAction(upperRow[upIdx]); true }
                            else -> false
                        }
                    }
                    // Centred home, no options showing: A shows them, Up lifts the cards, Down has nowhere to go.
                    if (centered) {
                        return@onPreviewKeyEvent when (e.key) {
                            Key.DirectionLeft -> { settleTo(target - 1); true }
                            Key.DirectionRight -> { settleTo(target + 1); true }
                            Key.ButtonL1 -> if (inDeckShell) false else { settleTo(target - 5); true }
                            Key.ButtonR1 -> if (inDeckShell) false else { settleTo(target + 5); true }
                            Key.DirectionDown -> true
                            Key.DirectionUp -> if (upperRow.isNotEmpty() && !selectionMode) { openUpper(); true } else false
                            // The All Games card has no options to show: A opens its page.
                            Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> { if (onAll) allOpen = true else { optsOpen = true; act = 0 }; true }
                            Key.ButtonB -> if (selectionMode) { onExitSelection(); true } else false
                            else -> false
                        }
                    }
                    when (e.key) {
                        Key.DirectionLeft -> { settleTo(target - 1); true }
                        Key.DirectionRight -> { settleTo(target + 1); true }
                        Key.ButtonL1 -> if (inDeckShell) false else { settleTo(target - 5); true }
                        Key.ButtonR1 -> if (inDeckShell) false else { settleTo(target + 5); true }
                        Key.DirectionDown -> { act = (actIdx + 1).coerceAtMost(actions.lastIndex); true }
                        Key.DirectionUp -> when {
                            actIdx > 0 -> { act = actIdx - 1; true }
                            centeredHome -> { closeOpts(); true }
                            else -> false
                        }
                        Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> { runAction(actions[actIdx]); true }
                        Key.ButtonB -> when {
                            centeredHome -> { closeOpts(); true }
                            actIdx > 0 -> { act = 0; true }
                            selectionMode -> { onExitSelection(); true }
                            else -> false
                        }
                        else -> false
                    }
                }
                .draggable(
                    hDrag, Orientation.Horizontal,
                    onDragStarted = { swipe = 0f },
                    onDragStopped = { velocity ->
                        if (nav.depth > 0) { if (swipe > swipeBackPx) nav.pop() }
                        else if (upperOpen) upSettle((upPos.floatValue - velocity / (pitchPx * homeK) * 0.14f).roundToInt())
                        else settleTo((pos.floatValue - velocity / (pitchPx * rowScale()) * 0.14f).roundToInt())
                    },
                )
                .draggable(swipeDrag, Orientation.Vertical, onDragStarted = { swipeAcc = 0f; swipeDone = false }),
        ) {
            // Background: black, the focused cover (with the cards up, the chosen card's art) blurred at full brightness, one flat
            // veil for the text. Nothing else (no accent glow, no scrims, no preset colour), so the home looks the same under
            // every preset. The All Games card has no art, so on it only the black shows.
            val card = if (upperOpen) upperRow.getOrNull(upIdx) else null
            // On the All Games card, the collage of the games inside it (a plain Bitmap, so it is blurred, dimmed and crossfaded
            // exactly like a cover). It is built the first time the card is hovered, then kept until the games change.
            val collage = collageState.value
            // Keyed on the card's art and icon (not the card itself, whose lambdas change every recomposition).
            val picture: Any? = card?.let { c -> c.art?.let { XmbBackdropArt(it, c.icon) } } ?: focused?.icon
                ?: (if (onAll && !upperOpen) collage?.let { XmbCollagePicture(it) } else null)
            XmbHomeBackdrop(picture)
            // Faint shade behind a see-through top bar so its title and buttons stay readable.
            if (topInset > 0.dp) {
                Box(Modifier.fillMaxWidth().height(topInset + 24.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))))
            }

            // The focus target starts BELOW the see-through top bar: D-pad focus search skips a target that
            // overlaps the bar, so Down from the top-bar buttons could never come back to the XMB.
            Box(Modifier.fillMaxSize().padding(top = topInset).focusRequester(focusRequester).focusable()) {
                // Top line: controller hints (only with a pad attached) · position · clock. Larger in
                // landscape, where the game row leaves the space above it free.
                val hs = m.headerScale
                if (showHints) Row(
                    Modifier.align(Alignment.TopStart).padding(start = 16.dp * hs, top = 8.dp * hs),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * hs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasPad && nested) {
                        val hi = nav.hint(xmbScope)
                        XmbButtonHint("A", Color(0xFF3DDC84), hi.a, hs)
                        XmbButtonHint("B", Color(0xFFFF5252), "Back", hs)
                        if (!m.port && m.w >= 600f) {
                            XmbKeyHint("▲ ▼", "Move", hs)
                            if (hi.change) XmbKeyHint("◀ ▶", "Change", hs)
                        }
                        if (hi.sections) XmbKeyHint("L1 R1", "Section", hs)
                    } else if (hasPad) {
                        XmbButtonHint("A", Color(0xFF3DDC84), if (upperOpen) upperRow[upIdx].label else if (onAll) allLabel else if (centered) "Options" else actions[actIdx].label, hs)
                        XmbButtonHint("B", Color(0xFFFF5252), "Back", hs)
                        if (!m.port && m.w >= 600f) {
                            XmbKeyHint("◀ ▶", "Games", hs); XmbKeyHint("▲ ▼", "Options", hs); XmbKeyHint("L1 R1", if (inDeckShell) "Tabs" else "Jump", hs)
                        }
                    }
                }
                if (showClock) Row(
                    Modifier.align(Alignment.TopEnd).padding(end = 16.dp * hs, top = 8.dp * hs),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * hs),
                ) {
                    Text(if (onAll) "All / $allTotal" else "${target + 1} / $n", color = Color(0xFFAAAAAA), fontSize = 12.sp * hs)
                    Text(clock, color = Color(0xFFDDDDDD), fontSize = 12.sp * hs, fontWeight = FontWeight.Light)
                }

                val rootAlpha by animateFloatAsState(if (nested) 0f else 1f, tween(260), label = "xmbRootAlpha")
                // With the upper row open the games shrink to the small, dimmed peek the cards have on the games row,
                // and drop until only their top half shows at the bottom edge.
                val lower = (m.h - m.sH * 0.5f - m.top).coerceAtLeast(0f)
                // Centred home: the row rests in the middle of the screen until the options open under it.
                val centreShift = (m.h - m.fH) / 2f - m.top
                // Everything that belongs to the options layout fades out while centred or with the cards up. A lambda,
                // read in the graphics layers, so the fade doesn't recompose this view every frame.
                val optsA = { (1f - upT) * (1f - cT) }
                Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = rootAlpha
                    translationY = (1f - rootAlpha) * -12.dp.toPx() + (cT * centreShift * (1f - upT) + upT * lower).dp.toPx()
                }) {
                // The bar. Only covers near the cross are composed; each one's placement is read from
                // `pos` in its graphics layer, so a swipe re-draws without recomposing.
                // From the row's anchor (the same one the covers are drawn from, see xmbCoverSlot): the chosen card while the row
                // is in use, the first one while the cards are up, so the covers at the left edge are composed then.
                val baseIdx by remember { derivedStateOf { floor(xmbRowAnchor(pos.floatValue, 1f - upT)).toInt() } }
                val ahead = ceil((m.w - m.cross) / m.pitch).toInt() + 1
                // Centred home: the covers grow around the focused cover's left edge at the row's middle. A layer
                // transform, so taps land on the covers as drawn.
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val s = rowScale()
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(m.cross / m.w, (m.top + m.fH / 2f) / m.h)
                    alpha = 1f - 0.5f * upT
                }) {
                for (i in (baseIdx - 3).coerceAtLeast(0)..(baseIdx + ahead + 1).coerceAtMost(slots - 1)) {
                    if (i >= n) {
                        key(XMB_ALL_PATH) {
                            XmbAllGamesCover(
                                index = i, pos = pos, m = m, accent = accent, count = allCount, mosaic = mosaic,
                                focused = i == target && !upperOpen,
                                grow = { 1f - upT },
                                // Like a game, a tap first brings it to the cross; on the focused one it opens the page.
                                onClick = { if (nav.depth == 0) { if (i != target) settleTo(i) else allOpen = true } },
                            )
                        }
                        continue
                    }
                    val s = shortcuts[i]
                    key(s.file.path) {
                        // With the cards up no game is chosen: none is enlarged or ringed.
                        XmbCover(
                            shortcut = s, index = i, pos = pos, m = m, accent = accent,
                            focused = i == target && !upperOpen, selectionMode = selectionMode, selected = s.file.path in selectedPaths,
                            grow = { 1f - upT },
                            onClick = {
                                if (nav.depth == 0) when {
                                    i != target -> settleTo(i)
                                    selectionMode -> onToggleSelect(s)
                                    centered -> { optsOpen = true; act = 0 }
                                    else -> onPlay(s)
                                }
                            },
                        )
                    }
                }
                }

                // Focused game's name + "container · resolution", right of its cover under the rest of the bar.
                // On the All Games card: its name and the number of games.
                AnimatedContent(
                    targetState = focused,
                    contentKey = { it?.file?.path ?: XMB_ALL_PATH },
                    transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 6 }) togetherWith fadeOut(tween(90)) },
                    label = "xmbTitle",
                    // On the centred home it follows the focused cover at its larger size (text kept at its own size),
                    // narrowed by the cover's extra width so it still ends at the screen edge.
                    modifier = Modifier
                        .offset(m.titleX.dp, m.titleY.dp)
                        .width((m.titleW - (homeK - 1f) * (m.fW + 16f)).coerceAtLeast(80f).dp)
                        .graphicsLayer {
                            alpha = 1f - upT
                            val e = rowScale() - 1f
                            translationX = (e * (m.titleX - m.cross)).dp.toPx()
                            translationY = (e * (m.titleY - m.top - m.fH / 2f)).dp.toPx()
                        },
                ) { s ->
                    if (s == null) {
                        Column {
                            Text(
                                stringResource(R.string.xmb_all_games), color = Color.White, fontSize = m.titleSize.sp,
                                lineHeight = (m.titleSize * 1.15f).sp, maxLines = m.titleLines, overflow = TextOverflow.Ellipsis,
                            )
                            Text(allCount, color = Color(0xFFC4C4C4), fontSize = if (centeredHome) ss.sp(12f) else 12.sp, maxLines = 1)
                        }
                    } else {
                        // An Android game has no container/resolution line; its package name stands in.
                        val meta = remember(s) {
                            AndroidGames.packageOf(s) ?: buildLaunchSpec(s, context).meta
                        }
                        Column {
                            Text(
                                s.name, color = Color.White, fontSize = m.titleSize.sp, lineHeight = (m.titleSize * 1.15f).sp,
                                maxLines = m.titleLines, overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (meta.isNotEmpty()) {
                                    Text(
                                        meta, color = Color(0xFFC4C4C4), fontSize = if (centeredHome) ss.sp(12f) else 12.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                    )
                                }
                                sdBadge(s)
                            }
                        }
                    }
                }

                // The cross: the chosen option always sits in the first slot under the bar. Options
                // you pass slide up behind it and fade; the next few stay visible below.
                Box(
                    Modifier
                        .offset(m.colX.dp, m.colTop.dp)
                        .width((m.iconBox + 12f + m.labelW).dp)
                        .height((m.nVis * m.rowH + m.extra).dp)
                        .graphicsLayer { alpha = optsA() }
                        // Only while the options show: otherwise this (invisible) column would swallow a swipe down that starts on it.
                        .draggable(vDrag, Orientation.Vertical, enabled = !centered && !upperOpen && !nested, onDragStarted = { vAcc = 0f }),
                ) {
                    actions.forEachIndexed { i, a ->
                        key(a.label) {
                            val d = i - actIdx
                            val vis = d in 0 until m.nVis
                            val y by animateFloatAsState(d * m.rowH + if (d > 0) m.extra else 0f, spring(dampingRatio = 0.9f, stiffness = 500f), label = "xmbRowY")
                            val a2 by animateFloatAsState(if (!vis) 0f else if (d == 0) 1f else max(0.35f, 1f - d * 0.13f), tween(220), label = "xmbRowA")
                            val src = remember { MutableInteractionSource() }
                            XmbActionRow(
                                action = a, selected = d == 0, accent = accent, iconBox = m.iconBox,
                                modifier = Modifier
                                    .graphicsLayer { translationY = y.dp.toPx(); alpha = a2 }
                                    .then(if (vis && !nested && !upperOpen && !centered) Modifier.xmbClick(src) { act = i; runAction(a) } else Modifier),
                            )
                        }
                    }
                }

                // Nothing about a game to show on the All Games card.
                if (m.infoW >= 120f && m.infoH >= 40f && focused != null) {
                    Column(
                        Modifier.offset(m.infoX.dp, m.infoY.dp).width(m.infoW.dp).height(m.infoH.dp).graphicsLayer { alpha = optsA() }.clipToBounds(),
                    ) {
                        XmbInfo(focused, playtime, if (m.port) 6 else 3, storeBadges)
                    }
                }
                }
                // Composed while the cards show or animate; derived, so the animation doesn't recompose this view per frame.
                val upperShown by remember(upperOpen) { derivedStateOf { upperOpen || upT > 0.01f || peekT > 0.01f } }
                if (centeredHome && upperRow.isNotEmpty() && upperShown) {
                    // A tap anywhere off the cards (the lowered games included) goes back to them.
                    if (upperOpen) {
                        val back = remember { MutableInteractionSource() }
                        Box(Modifier.fillMaxSize().xmbClick(back) { closeUpper() })
                    }
                    // Open: a tap on another card brings it to the cross, on the chosen one runs it. Peeking: any card opens the row.
                    val onCard: (Int) -> Unit = { i ->
                        when {
                            !upperOpen -> openUpper()
                            i != upIdx -> upSettle(i)
                            else -> runAction(upperRow[i])
                        }
                    }
                    // The cards peek above the centred row and run up under the host's floating tab bar, fading out toward
                    // the top edge (an offscreen layer masked by a gradient) instead of stopping at a hard line. Fully open
                    // they sit well below that edge, so the mask (and its full-screen offscreen layer) is dropped.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = if (upT < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                            .drawWithContent {
                                drawContent()
                                if (upT < 1f) {
                                    val edge = clipTop.dp.toPx()
                                    drawRect(
                                        Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = edge * 0.15f, endY = edge * 1.25f),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                            },
                    ) {
                        Box(Modifier.fillMaxSize().padding(top = clipTop.dp)) {
                            XmbUpperBar(
                                upperRow, upIdx, upPos, { upT }, { peekT }, m, homeK, clipTop, accent,
                                enabled = upperOpen || centered, metaSize = ss.sp(12f), onTap = onCard,
                            )
                        }
                    }
                    // A faint shade behind the tab bar so its tabs and clock stay readable over the fading cards.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((clipTop * 1.3f).dp)
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
                    )
                }
                if (nested) {
                    XmbNestedLayer(nav, xmbScope, m.w, m.h, m.port, accent, focused?.name.orEmpty(), focused?.icon, actions.map { it.icon }, actIdx)
                }
            }
        }
        // The All Games page covers the row; closing it lands back here with the All Games card still under the cursor.
        if (allOpen && allShortcuts != null) {
            XmbAllGamesPage(allShortcuts, inDeckShell, onPlay = onPlay, onClose = { allOpen = false })
        }
    }
}

/** All XMB geometry in dp, from the content box. Portrait = taller than wide. */
private class XmbMetrics(val w: Float, val h: Float) {
    val port = h > w * 1.05f
    val fH = if (port) min(w * 0.52f, h * 0.29f) else (h * 0.36f).coerceIn(96f, 280f)
    val fW = fH * 2f / 3f                  // 2:3, same as the grid covers
    val sH = fH * 0.62f
    val sW = sH * 2f / 3f
    val gap = (fH * 0.09f).roundToInt().toFloat()
    val pitch = sW + gap
    val grow = fW - sW
    // Landscape: the row of games sits three quarters up the screen (its middle a quarter down),
    // never so low that fewer than three option rows fit under the focused cover. Portrait keeps it
    // at the top.
    val top = if (port) 34f else (h * 0.25f - sH / 2f).coerceAtMost(h - fH - 12f - 3 * 40f - 6f - 12f).coerceAtLeast(34f)
    val cross = if (port) 16f + sW * 0.42f + gap else max(16f + pitch, w * 0.13f)
    val iconBox = 34f
    val headerScale = if (port) 1f else 1.4f   // the top line; landscape has room above the row
    val rowH = if (port) 46f else 40f
    val extra = 6f
    val colX = cross + fW / 2f - iconBox / 2f
    val colTop = top + fH + 12f
    val labelW = if (port) min(220f, w - colX - iconBox - 28f) else 176f
    val nVis = if (port) ((h * 0.38f) / rowH).toInt().coerceIn(3, 8) else max(2, ((h - colTop - 12f - extra) / rowH).toInt())
    val infoX = if (port) 16f else colX + iconBox + 12f + labelW + 12f
    val infoY = if (port) colTop + nVis * rowH + extra + 10f else colTop
    val infoW = if (port) w - 32f else w - infoX - 80f   // 80 keeps clear of the + button
    val infoH = if (port) h - infoY - 84f else h - infoY - 12f
    val titleX = cross + fW + 16f
    val titleY = top + sH + 8f
    val titleW = w - titleX - 16f
    val titleH = fH - sH - 8f
    val titleLines = if (titleH > 58f) 2 else 1
    val titleSize = (if (titleLines == 2) (titleH - 18f) / 2.4f else titleH - 22f).coerceIn(15f, 26f)
}

@Composable
private fun XmbCover(
    shortcut: Shortcut,
    index: Int,
    pos: MutableFloatState,
    m: XmbMetrics,
    accent: Color,
    focused: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    // 1 = the focused cover grows to full size (the usual row), 0 = every cover small (the home's lowered peek).
    grow: () -> Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val src = remember { MutableInteractionSource() }
    val mark = remember(shortcut) { storeMarkOf(shortcut) }
    Box(
        Modifier
            .xmbCoverSlot(index, pos, m, focused, grow)
            .then(if (focused && !selected) Modifier.shadow(14.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(Color(0xFF111111))
            .then(
                when {
                    selected -> Modifier.border(3.dp, DangerRed, shape)
                    focused -> Modifier.border(2.dp, accent, shape)
                    else -> Modifier
                }
            )
            .xmbClick(src, onClick),
    ) {
        val bmp = shortcut.icon
        if (bmp != null) {
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = shortcut.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            XmbMonogram(shortcut.name, accent)
        }
        // Bottom-end, so it clears the selection mark at the top-end; it scales with the cover.
        XmbStoreMark(mark, Modifier.align(Alignment.BottomEnd).padding(5.dp), (m.fW * 0.25f).coerceIn(22.5f, 32.5f).dp)
        if (selectionMode) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) DangerRed else Color.Black.copy(alpha = 0.55f))
                    .border(1.5.dp, if (selected) DangerRed else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
    }
}

// A card's place in a row (a game's, the All Games card's or an upper-row card's): its size, position and fade from its distance to
// the cross, read in the graphics layer so a swipe re-draws without recomposing. [y] is its top (dp) in the box it is drawn in.
private fun Modifier.xmbCoverSlot(
    index: Int, pos: MutableFloatState, m: XmbMetrics, focused: Boolean, grow: () -> Float, y: Float = m.top,
): Modifier = this
    .zIndex(if (focused) 1f else 0f)
    .size(m.fW.dp, m.fH.dp)
    .graphicsLayer {
        // u = distance from the chosen card in covers; the focused one grows to full size and the
        // ones after it are pushed right by the extra width, so spacing stays even mid-swipe.
        // ud = distance from the row's anchor (the cross): the chosen card while the row is in use, the first card while it is
        // not ([grow] is 0 then), so a row left on a far card rests from the left edge instead of being shoved off it.
        val g = grow()
        val p = pos.floatValue
        val u = index - p
        val ud = index - xmbRowAnchor(p, g)
        val t = (1f - abs(u)).coerceAtLeast(0f) * g
        val sc = (m.sH + (m.fH - m.sH) * t) / m.fH
        val x = m.cross + ud * m.pitch + m.grow * g * u.coerceIn(0f, 1f)
        translationX = x.dp.toPx()
        translationY = y.dp.toPx()
        scaleX = sc
        scaleY = sc
        transformOrigin = TransformOrigin(0f, 0f)
        alpha = ((if (ud >= 0f) 0.82f else 0.82f + ud * 0.32f) + 0.18f * t).coerceIn(0f, 1f)
    }

// The last card of the row: a folder-like 3 x 2 preview of the library (the grid glyph when there are no games) on the same plain
// surface as the upper row's Add game card, 2:3 like a cover and ringed like the focused one. Its name and game count are the
// title beside the card (like every card's), so only the semantics carry them here. It opens the All Games page and has no game
// behind it, so there is no store mark and no selection mark. One flat opaque fill and nothing nested: a see-through fill would
// let the focus shadow show through as a dark inner box.
@Composable
private fun XmbAllGamesCover(
    index: Int,
    pos: MutableFloatState,
    m: XmbMetrics,
    accent: Color,
    count: String,
    mosaic: XmbMosaic<Shortcut>,
    focused: Boolean,
    grow: () -> Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val src = remember { MutableInteractionSource() }
    val description = stringResource(R.string.xmb_all_games) + ", " + count
    Box(
        Modifier
            .xmbCoverSlot(index, pos, m, focused, grow)
            .then(if (focused) Modifier.shadow(14.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(Brush.linearGradient(XMB_PLAIN_CARD))
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else Color.White.copy(alpha = 0.14f), shape)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .xmbClick(src, onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (mosaic.covers.isEmpty()) {
            // No games yet: the grid glyph.
            Icon(Icons.Filled.GridView, contentDescription = null, tint = Color.White, modifier = Modifier.size((m.fW * 0.44f).dp))
        } else {
            // Like a folder: a 3 x 2 preview of the games filling the card. Everything is sized in dp inside the card, so it
            // scales with the card (the card itself is scaled by its graphics layer).
            XmbAllGamesMosaic(mosaic, accent, Modifier.fillMaxSize().padding(6.dp))
        }
    }
}

// The All Games card's folder-like preview: up to five covers and a "+N more" tile, in a grid of XMB_MOSAIC_ROWS rows x
// XMB_MOSAIC_COLS columns that fills the space it is given (the cells share it equally and crop the covers to fit, about square on
// the 2:3 card). Cells with no game stay faintly empty.
@Composable
private fun XmbAllGamesMosaic(mosaic: XmbMosaic<Shortcut>, accent: Color, modifier: Modifier) {
    val gap = 3.dp
    val tileShape = RoundedCornerShape(3.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in 0 until XMB_MOSAIC_ROWS) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (col in 0 until XMB_MOSAIC_COLS) {
                    val i = row * XMB_MOSAIC_COLS + col
                    val tile = Modifier.weight(1f).fillMaxHeight().clip(tileShape)
                    when {
                        i < mosaic.covers.size -> XmbMosaicCover(mosaic.covers[i], accent, tile)
                        i == mosaic.covers.size && mosaic.more > 0 -> Box(tile.background(Color(0xFF2E3138)), contentAlignment = Alignment.Center) {
                            // In a small cell "+N more" wraps at the space: "+N" over "more".
                            Text(
                                stringResource(R.string.xmb_all_games_more, mosaic.more), color = Color.White.copy(alpha = 0.92f),
                                fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2,
                            )
                        }
                        else -> Box(tile.background(Color.White.copy(alpha = 0.06f)))
                    }
                }
            }
        }
    }
}

// One game's tile in the mosaic: its cover (the bitmap the row's cover shows), or its initials on the monogram's gradient.
@Composable
private fun XmbMosaicCover(s: Shortcut, accent: Color, modifier: Modifier) {
    val bmp = s.icon
    if (bmp != null) {
        Image(
            bitmap = remember(bmp) { bmp.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), Color(0xFF0A0A0A)))), contentAlignment = Alignment.Center) {
            Text(xmbInitials(s.name), color = Color.White.copy(alpha = 0.92f), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

// Cover fallback: initials on an accent gradient (no art yet, or a custom game).
@Composable
internal fun XmbMonogram(name: String, accent: Color) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), Color(0xFF0A0A0A))))
            .padding(10.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(xmbInitials(name), color = Color.White.copy(alpha = 0.92f), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(name, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun XmbActionRow(action: XmbAction, selected: Boolean, accent: Color, iconBox: Float, modifier: Modifier) {
    val ring = if (action.danger) DangerRed else accent
    val boxShape = RoundedCornerShape(10.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(iconBox.dp)
                .clip(boxShape)
                .background(if (selected) ring.copy(alpha = 0.25f) else Color.Transparent)
                .then(if (selected) Modifier.border(1.5.dp, ring, boxShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon, contentDescription = null, modifier = Modifier.size(19.dp),
                tint = when {
                    selected -> Color.White
                    action.danger -> Color(0xFFE8A197)
                    else -> Color(0xFFCFCFCF)
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                if (action.menu != null) "${action.label}  ›" else action.label,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.58f),
                fontSize = if (selected) 16.sp else 13.5.sp,
                fontWeight = if (selected) FontWeight.Normal else FontWeight.Light,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = action.subtitle
            if (selected && sub != null) {
                Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Centred home: the peeking cards' middle sits this far (dp, on a 412 dp phone) from the top, just below the host's floating tab bar; above it they fade out. */
private const val XMB_PEEK_CLIP = 50f

/** The surface of the upper row's plain cards (Add game) and of the All Games card, so they always match. */
internal val XMB_PLAIN_CARD = listOf(Color(0xFF2A2D33), Color(0xFF16181C))

/** Centred home: the most the cover row is drawn larger than its normal size. */
private const val XMB_HOME_MAX_GROW = 1.3f

/** Centred home: how far (dp) a vertical swipe has to travel to lift the cards or go back to the games. Short, so a flick is enough. */
private const val XMB_SWIPE_DP = 28f

/** How long (ms) a chosen card waits before its cut-off text starts scrolling. */
private const val XMB_MARQUEE_DELAY = 700

// The row above the games, drawn and moved like the game row: portrait cards (a store's logo on its colours, or an
// icon), the chosen one enlarged at the cross with the earlier ones to its left, and its name and details beside it.
// [open] is the open animation, 0..1; [peek] the resting state, where the cards sit small and dimmed with only their
// lower half below [clipTop] (the caller fades out what's above it). Open, the bar takes the game row's larger size ([scale]).
// Both are read where they're drawn, so the animation doesn't recompose the bar.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun XmbUpperBar(
    cards: List<XmbAction>,
    sel: Int,
    pos: MutableFloatState,
    open: () -> Float,
    peek: () -> Float,
    m: XmbMetrics,
    scale: Float,
    clipTop: Float,
    accent: Color,
    enabled: Boolean,
    metaSize: TextUnit,
    onTap: (Int) -> Unit,
) {
    // In the caller's box (its top at clipTop): peeking, the small cards' middle sits on that edge; open,
    // the bar is centred on the screen as the game row is when it's the one in use (the games peek below it then).
    val yPeek = -m.sH / 2f
    val yOpen = (m.h - m.fH) / 2f - clipTop
    val boxH = (m.h - clipTop).coerceAtLeast(1f)
    // The chosen card is ringed once the bar is more than half open; derived, so only that crossing recomposes.
    val halfOpen by remember { derivedStateOf { open() > 0.5f } }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val o = open()
                val s = 1f + (scale - 1f) * o
                alpha = max(o, 0.5f * peek())
                translationY = (yPeek + (yOpen - yPeek) * o).dp.toPx()
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(m.cross / m.w, (m.fH / 2f) / boxH)
            },
    ) {
        cards.forEachIndexed { i, c ->
            key(c.label) {
                XmbUpperCard(c, i, pos, m, accent, grow = open, focused = i == sel && halfOpen, enabled = enabled) { onTap(i) }
            }
        }
    }
    // The chosen card's name and details, right of it under the rest of the bar, as a game's are.
    val chosen = cards.getOrNull(sel) ?: return
    AnimatedContent(
        targetState = chosen,
        contentKey = { it.label },
        transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 6 }) togetherWith fadeOut(tween(90)) },
        label = "xmbCardTitle",
        modifier = Modifier
            .offset(m.titleX.dp, (yOpen + m.sH + 8f).dp)
            .width((m.titleW - (scale - 1f) * (m.fW + 16f)).coerceAtLeast(80f).dp)
            .graphicsLayer {
                alpha = open()
                val e = scale - 1f
                translationX = (e * (m.titleX - m.cross)).dp.toPx()
                translationY = (e * (m.sH + 8f - m.fH / 2f)).dp.toPx()
            },
    ) { c ->
        Column {
            Text(
                c.label, color = Color.White, fontSize = m.titleSize.sp, lineHeight = (m.titleSize * 1.15f).sp,
                maxLines = m.titleLines, overflow = TextOverflow.Ellipsis,
            )
            // Details that don't fit scroll right to left after a short pause.
            c.subtitle?.let {
                Text(
                    it, color = Color(0xFFC4C4C4), fontSize = metaSize, maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = XMB_MARQUEE_DELAY),
                )
            }
        }
    }
}

// One upper-row card, placed like a game cover: once the bar is open ([grow], 0..1) the chosen card grows to full size at
// the cross and the later ones make room for it.
@Composable
private fun XmbUpperCard(
    card: XmbAction,
    index: Int,
    pos: MutableFloatState,
    m: XmbMetrics,
    accent: Color,
    grow: () -> Float,
    focused: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val src = remember { MutableInteractionSource() }
    val art = card.art
    Box(
        // The bar's own box places the cards vertically (y = 0), unlike a game cover, which sits at the row's top.
        Modifier
            .xmbCoverSlot(index, pos, m, focused, grow, y = 0f)
            .then(if (focused) Modifier.shadow(14.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            // Opaque, edge to edge: a see-through fill would let the focus shadow show through as a dark inner box.
            .background(Brush.linearGradient(art?.colors ?: XMB_PLAIN_CARD))
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else Color.White.copy(alpha = 0.14f), shape)
            .then(if (enabled) Modifier.xmbClick(src, onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val logo = art?.logo
        if (logo != null) {
            rememberXmbLogo(logo)?.let { bmp ->
                Image(
                    bmp, contentDescription = card.label, contentScale = ContentScale.Fit,
                    colorFilter = if (art?.tintWhite == true) ColorFilter.tint(Color.White) else null,
                    modifier = Modifier.fillMaxSize(0.62f),
                )
            }
        } else {
            // Sized from the card, so the chosen (enlarged) card shows the same icon, just bigger.
            Icon(card.icon, contentDescription = card.label, tint = Color.White, modifier = Modifier.size((m.fW * 0.44f).dp))
        }
    }
}

/** What the backdrop shows for a chosen upper-row card: its [art], with [icon] standing in for a missing logo. */
private data class XmbBackdropArt(val art: XmbCardArt, val icon: ImageVector)

/** The home backdrop's blur: a medium one, so the art stays recognisable. */
private val XMB_BACKDROP_BLUR = 16.dp

/** The one flat black veil over the home backdrop's art, just enough for the text on top to stay readable. */
private const val XMB_BACKDROP_VEIL = 0.30f

/** Below API 31 the backdrop art is shrunk to 1/this of its size and scaled back up, which looks blurred. */
internal const val XMB_BACKDROP_SOFT_DIV = 16

/** The collage behind the hovered All Games card is blurred only very slightly (a tenth of [XMB_BACKDROP_BLUR]). */
private val XMB_COLLAGE_BLUR = 1.6.dp

/** Below API 31 the collage is not shrunk at all (it is already a small bitmap, scaled up with filtering), so it stays only slightly soft. */
internal const val XMB_COLLAGE_SOFT_DIV = 1

/** The size [xmbBackdropSoftBitmap] shrinks a [w] x [h] picture to (by [div]); never below 1 px. */
internal fun xmbBackdropSoftSize(w: Int, h: Int, div: Int = XMB_BACKDROP_SOFT_DIV): Pair<Int, Int> =
    max(1, w / div.coerceAtLeast(1)) to max(1, h / div.coerceAtLeast(1))

/** What the backdrop shows on the All Games card: the [bitmap] collage of its games (it gets its own, lighter blur). */
private data class XmbCollagePicture(val bitmap: Bitmap)

/**
 * The home backdrop, one implementation for the games row and the All Games page: black, then [picture] blurred at full
 * brightness and crossfaded when it changes, then one flat veil for the text. [picture] is a game's cover (a [Bitmap]), an upper-row
 * card's art, the All Games collage, or null for the black base alone. Fills its parent.
 */
@Composable
internal fun XmbHomeBackdrop(picture: Any?) {
    // Modifier.blur does nothing below API 31, where the art is drawn pre-shrunk and scaled up instead.
    val nativeBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        Crossfade(targetState = picture, animationSpec = tween(600), label = "xmbBackdrop") { pic ->
            when (pic) {
                is Bitmap -> XmbBackdropBitmap(pic, nativeBlur, XMB_BACKDROP_BLUR, XMB_BACKDROP_SOFT_DIV)
                // The collage is only slightly blurred (each crossfade layer has its own blur).
                is XmbCollagePicture -> XmbBackdropBitmap(pic.bitmap, nativeBlur, XMB_COLLAGE_BLUR, XMB_COLLAGE_SOFT_DIV)
                is XmbBackdropArt -> Box(
                    Modifier.fillMaxSize().blur(XMB_BACKDROP_BLUR).background(Brush.linearGradient(pic.art.colors)),
                    contentAlignment = Alignment.Center,
                ) {
                    val logo = pic.art.logo
                    // Below API 31 the logo would stay sharp, so only the card's colours show there.
                    if (nativeBlur) {
                        if (logo != null) {
                            rememberXmbLogo(logo)?.let { bmp ->
                                Image(
                                    bmp, contentDescription = null, contentScale = ContentScale.Fit,
                                    colorFilter = if (pic.art.tintWhite) ColorFilter.tint(Color.White) else null,
                                    modifier = Modifier.fillMaxSize(0.6f),
                                )
                            }
                        } else {
                            Icon(pic.icon, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize(0.4f))
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = XMB_BACKDROP_VEIL)))
    }
}

/**
 * A backdrop picture: [bmp] filling the screen, blurred by [radius] (Modifier.blur, API 31+) or, below that, shrunk by [softDiv]
 * and scaled back up (see [xmbBackdropSoftBitmap]).
 */
@Composable
private fun XmbBackdropBitmap(bmp: Bitmap, nativeBlur: Boolean, radius: Dp, softDiv: Int) {
    val art = remember(bmp, nativeBlur, softDiv) { if (nativeBlur) bmp else xmbBackdropSoftBitmap(bmp, softDiv) }
    if (art != null) Image(
        bitmap = remember(art) { art.asImageBitmap() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = if (nativeBlur) FilterQuality.Low else FilterQuality.Medium,
        modifier = Modifier.fillMaxSize().blur(radius),
    )
}

/**
 * A tiny copy of [src] for the backdrop below API 31, where Modifier.blur does nothing: drawn scaled up with filtering it
 * looks blurred. Halved step by step, as one big filtered jump skips pixels and aliases. Null if the bitmap can't be scaled
 * (the backdrop then shows only its black base). With a [div] of 1 the picture is used as it is.
 */
private fun xmbBackdropSoftBitmap(src: Bitmap, div: Int = XMB_BACKDROP_SOFT_DIV): Bitmap? = runCatching {
    if (div <= 1) return@runCatching src
    val (w, h) = xmbBackdropSoftSize(src.width, src.height, div)
    var cur = src
    while (cur.width / 2 > w && cur.height / 2 > h) cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
    Bitmap.createScaledBitmap(cur, w, h, true)
}.getOrNull()

/**
 * The backdrop behind the hovered All Games card: [arts] (decoded covers, most recent first) tiled row by row into the grid of
 * [spec], each centre-cropped to its cell, as one small bitmap. The backdrop then treats it as any other picture (the same veil
 * and crossfade, but a much lighter blur: XMB_COLLAGE_BLUR, XMB_COLLAGE_SOFT_DIV), so it costs one draw like a cover. Cells with no cover stay black; a
 * cover that can't be drawn is skipped. Null if the bitmap can't be made.
 */
private fun xmbCollageBitmap(arts: List<Bitmap>, spec: XmbCollageSpec): Bitmap? = runCatching {
    val out = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    for (i in 0 until min(spec.cols * spec.rows, arts.size)) {
        val art = arts[i]
        val cell = xmbCellRect(i, spec.cols, spec.rows, spec.width, spec.height)
        val crop = xmbCenterCrop(art.width, art.height, cell.width, cell.height)
        runCatching {
            canvas.drawBitmap(art, Rect(crop.left, crop.top, crop.right, crop.bottom), Rect(cell.left, cell.top, cell.right, cell.bottom), paint)
        }
    }
    out
}.getOrNull()

// Right / lower pane: store badges, the same spec chips as the list card, playtime and Game Details.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun XmbInfo(s: Shortcut, playtime: String?, descLines: Int, storeBadges: @Composable (Shortcut) -> Unit) {
    val context = LocalContext.current
    val spec = remember(s) { buildLaunchSpec(s, context) }
    val details = remember(s) { buildLaunchDetails(s) }
    storeBadges(s)
    if (!remember(s) { AndroidGames.isAndroidEntry(s) }) SpecChipRows(
        rendererLabel = spec.rendererLabel,
        dxvkVersion = spec.dxvkVersion,
        frameGenLabel = spec.frameGenLabel,
        driverLabel = spec.driverLabel,
        vkd3dVersion = spec.vkd3dVersion,
        backendLabel = spec.backendLabel,
        eosEnabled = spec.eosEnabled,
    )
    val factColor = Color(0xFFD0D0D0)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = Color(0xFFBBBBBB), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(playtime ?: "Not played yet", color = factColor, fontSize = 12.sp)
        }
        if (details.genres.isNotEmpty()) Text(details.genres.take(3).joinToString(" · "), color = factColor, fontSize = 12.sp)
        details.releaseYear?.takeIf { it.isNotBlank() }?.let { Text(it, color = factColor, fontSize = 12.sp) }
        details.metacritic?.let { score ->
            Text(
                "$score", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(metacriticColor(score)).padding(horizontal = 5.dp),
            )
        }
    }
    details.description?.takeIf { it.isNotBlank() }?.let {
        Text(
            it, color = Color(0xFFABABAB), fontSize = 12.sp, lineHeight = 17.sp,
            maxLines = descLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp),
        )
    }
}

@Composable
private fun XmbButtonHint(glyph: String, color: Color, label: String, s: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(17.dp * s).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(glyph, color = Color.Black, fontSize = 10.sp * s, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(4.dp * s))
        Text(label, color = Color(0xFFB0B0B0), fontSize = 11.sp * s, maxLines = 1)
    }
}

@Composable
private fun XmbKeyHint(keys: String, label: String, s: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            keys, color = Color(0xFFDDDDDD), fontSize = 10.sp * s,
            modifier = Modifier.border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp * s)).padding(horizontal = 5.dp * s),
        )
        Spacer(Modifier.width(4.dp * s))
        Text(label, color = Color(0xFFB0B0B0), fontSize = 11.sp * s, maxLines = 1)
    }
}

// Controller hints only make sense with a pad attached; tracks hot-plug.
@Composable
internal fun rememberGamepadConnected(): Boolean {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val check = {
            connected = InputDevice.getDeviceIds().any { ExternalController.isGameController(InputDevice.getDevice(it)) }
        }
        check()
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = check()
            override fun onInputDeviceRemoved(deviceId: Int) = check()
            override fun onInputDeviceChanged(deviceId: Int) = check()
        }
        im?.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { im?.unregisterInputDeviceListener(listener) }
    }
    return connected
}

// "Xh Ym" from the same prefs XServerDisplayActivity writes; null = never played.
private fun xmbPlaytime(context: Context, name: String): String? {
    val ms = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE).getLong("${name}_playtime", 0L)
    if (ms <= 0L) return null
    val mins = ms / 60_000L
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
}

internal fun xmbInitials(name: String): String {
    val words = name.replace(Regex("[^A-Za-z0-9 ]"), " ").split(' ').filter { it.isNotBlank() }
    val first = words.firstOrNull() ?: return name.trim().take(1).ifEmpty { "?" }
    if (first.length <= 4 && first == first.uppercase() && first.any { it.isLetter() }) return first
    return words.filterNot { it.lowercase() in setOf("the", "of", "and", "a") }
        .take(2).joinToString("") { it.first().uppercase() }
        .ifEmpty { first.take(1).uppercase() }
}

private fun metacriticColor(score: Int): Color = when {
    score >= 75 -> Color(0xFF66CC33)
    score >= 50 -> Color(0xFFFFCC33)
    else -> Color(0xFFFF4444)
}
