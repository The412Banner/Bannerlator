package com.winlator.star.ui.screens

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleEventObserver
import com.winlator.star.container.Shortcut
import com.winlator.star.inputcontrols.ExternalController
import com.winlator.star.ui.LocalTopBarOverlayInset
import com.winlator.star.ui.deck.rememberScreenScale
import com.winlator.star.ui.deck.rememberXmbLogo
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.theme.DangerRed
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
) {
    if (shortcuts.isEmpty()) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val n = shortcuts.size

    // The focused game is remembered by path so it survives rotation, leaving the tab, and a
    // refresh or sort that reorders the list.
    var focusedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var target by remember { mutableIntStateOf(shortcuts.indexOfFirst { it.file.path == focusedPath }.coerceAtLeast(0)) }
    val pos = remember { mutableFloatStateOf(target.toFloat()) } // visual position, fractional mid-swipe
    var act by remember { mutableIntStateOf(0) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // Centred home: whether the focused game's options are showing. They belong to one game, so moving closes them.
    var optsOpen by remember { mutableStateOf(false) }
    val centered = centeredHome && !optsOpen

    LaunchedEffect(shortcuts) {
        val i = shortcuts.indexOfFirst { it.file.path == focusedPath }
        val idx = if (i >= 0) i else target.coerceIn(0, n - 1)
        settleJob?.cancel()
        target = idx
        pos.floatValue = idx.toFloat()
    }
    val focused = shortcuts[target.coerceIn(0, n - 1)]
    LaunchedEffect(focused.file.path) {
        focusedPath = focused.file.path
        onFocusedChange(focused)
    }

    // Moving to another game always lands back on Play, so A launches (like tapping a card).
    fun settleTo(i: Int) {
        val t = i.coerceIn(0, n - 1)
        if (t != target) { target = t; act = 0; optsOpen = false }
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(pos.floatValue, t.toFloat(), animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f)) { v, _ ->
                pos.floatValue = v
            }
        }
    }

    val playtime = remember(focused) { xmbPlaytime(context, focused.name) }
    val isSelected = focused.file.path in selectedPaths
    val actions = buildList<XmbAction> {
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
            override val scope: kotlinx.coroutines.CoroutineScope get() = coScope
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
    LaunchedEffect(nested) { onNestedChange(nested) }
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
    // The card list changes while the screen is up (Friends appears once you sign in): keep the same card chosen.
    LaunchedEffect(upperRow) {
        val i = upperRow.indexOfFirst { it.label == upLabel }
        if (i >= 0 && i != upSel) { upJob?.cancel(); upSel = i; upPos.floatValue = i.toFloat() }
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
    // Up from the games always lands on the first card.
    fun openUpper() { upJob?.cancel(); upSel = 0; upPos.floatValue = 0f; upOpen = true }
    // Closed, the peeking row eases back to its start.
    fun closeUpper() { upOpen = false; act = 0; upSettle(0) }
    LaunchedEffect(nested) { if (nested) upOpen = false }
    BackHandler(enabled = upperOpen) { closeUpper() }
    fun closeOpts() { optsOpen = false; act = 0 }
    BackHandler(enabled = centeredHome && optsOpen && !nested && !upperOpen) { closeOpts() }
    // 1 = centred (no options), 0 = the options layout; the cards peek above the centred row.
    val cT by animateFloatAsState(if (centered) 1f else 0f, tween(300), label = "xmbCentred")
    val peekT by animateFloatAsState(if (centered && upperRow.isNotEmpty() && !upperOpen && !nested) 1f else 0f, tween(300), label = "xmbPeek")

    // Take D-pad focus on entry and again whenever the app comes back (e.g. after a game exits).
    val focusRequester = remember { FocusRequester() }
    nav.refocus = { runCatching { focusRequester.requestFocus() } }
    val mayTakeFocus by rememberUpdatedState(takeFocus)
    LaunchedEffect(focusTick) { if (mayTakeFocus()) runCatching { focusRequester.requestFocus() } }
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
        // Centred home: the row is drawn larger while it's the only thing on screen (and under the open cards),
        // as large as fits between the peeking cards and the bottom edge, at most XMB_HOME_MAX_GROW. The options shrink it back.
        val homeK = if (!centeredHome) 1f
            else min(XMB_HOME_MAX_GROW, (m.h / 2f - clipTop - m.sH / 2f - 10f) * 2f / m.fH.coerceAtLeast(1f)).coerceAtLeast(1f)
        // Read where it's used (draw / drag), so the animation doesn't recompose the view.
        val rowScale = { 1f + (homeK - 1f) * cT * (1f - upT) }
        val pitchPx = with(density) { m.pitch.dp.toPx() }
        val rowPx = with(density) { m.rowH.dp.toPx() }
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }

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
            val p = (pos.floatValue - delta / (pitchPx * rowScale())).coerceIn(-0.45f, n - 1 + 0.45f)
            pos.floatValue = p
            val r = p.roundToInt().coerceIn(0, n - 1)
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
                .background(Color.Black)
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
                            Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> { optsOpen = true; act = 0; true }
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
            // Background: accent glow → the focused cover (with the cards up, the chosen card's art), blurred → shade → the moving waves.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        0f to accent.copy(alpha = 0.50f), 0.48f to accent.copy(alpha = 0.10f), 0.85f to Color.Black,
                        center = Offset(wPx * 0.18f, 0f), radius = max(wPx, hPx) * 1.1f,
                    )
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val card = if (upperOpen) upperRow.getOrNull(upIdx) else null
                // Keyed on the card's art and icon (not the card itself, whose lambdas change every recomposition).
                val picture: Any? = card?.let { c -> c.art?.let { XmbBackdropArt(it, c.icon) } } ?: focused.icon
                Crossfade(targetState = picture, animationSpec = tween(600), label = "xmbBackdrop") { pic ->
                    when (pic) {
                        is Bitmap -> Image(
                            bitmap = remember(pic) { pic.asImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().blur(14.dp).alpha(0.34f),
                        )
                        is XmbBackdropArt -> Box(
                            Modifier.fillMaxSize().blur(14.dp).alpha(0.34f).background(Brush.linearGradient(pic.art.colors)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val logo = pic.art.logo
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
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.55f to Color.Black.copy(alpha = 0.1f), 1f to Color.Black.copy(alpha = 0.45f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
            XmbWaves(accent, Modifier.fillMaxSize())
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
                        XmbHint("A", Color(0xFF3DDC84), hi.a, hs)
                        XmbHint("B", Color(0xFFFF5252), "Back", hs)
                        if (!m.port && m.w >= 600f) {
                            XmbKeyHint("▲ ▼", "Move", hs)
                            if (hi.change) XmbKeyHint("◀ ▶", "Change", hs)
                        }
                        if (hi.sections) XmbKeyHint("L1 R1", "Section", hs)
                    } else if (hasPad) {
                        XmbHint("A", Color(0xFF3DDC84), if (upperOpen) upperRow[upIdx].label else if (centered) "Options" else actions[actIdx].label, hs)
                        XmbHint("B", Color(0xFFFF5252), "Back", hs)
                        if (!m.port && m.w >= 600f) {
                            XmbKeyHint("◀ ▶", "Games", hs); XmbKeyHint("▲ ▼", "Options", hs); XmbKeyHint("L1 R1", if (inDeckShell) "Tabs" else "Jump", hs)
                        }
                    }
                }
                if (showClock) Row(
                    Modifier.align(Alignment.TopEnd).padding(end = 16.dp * hs, top = 8.dp * hs),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * hs),
                ) {
                    Text("${target + 1} / $n", color = Color(0xFFAAAAAA), fontSize = 12.sp * hs)
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
                val baseIdx by remember { derivedStateOf { floor(pos.floatValue).toInt() } }
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
                for (i in (baseIdx - 3).coerceAtLeast(0)..(baseIdx + ahead + 1).coerceAtMost(n - 1)) {
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
                AnimatedContent(
                    targetState = focused,
                    contentKey = { it.file.path },
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
                    // An Android game has no container/resolution line; its package name stands in.
                    val meta = remember(s) {
                        com.winlator.star.androidgames.AndroidGames.packageOf(s) ?: buildLaunchSpec(s, context).meta
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

                if (m.infoW >= 120f && m.infoH >= 40f) {
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
                    XmbNestedLayer(nav, xmbScope, m.w, m.h, m.port, accent, focused.name, focused.icon, actions.map { it.icon }, actIdx)
                }
            }
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
    grow: () -> Float = { 1f },
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .size(m.fW.dp, m.fH.dp)
            .graphicsLayer {
                // u = distance from the cross in covers; the focused one grows to full size and the
                // ones after it are pushed right by the extra width, so spacing stays even mid-swipe.
                val g = grow()
                val u = index - pos.floatValue
                val t = (1f - abs(u)).coerceAtLeast(0f) * g
                val sc = (m.sH + (m.fH - m.sH) * t) / m.fH
                val x = m.cross + u * m.pitch + m.grow * g * u.coerceIn(0f, 1f)
                translationX = x.dp.toPx()
                translationY = m.top.dp.toPx()
                scaleX = sc
                scaleY = sc
                transformOrigin = TransformOrigin(0f, 0f)
                alpha = ((if (u >= 0f) 0.82f else 0.82f + u * 0.32f) + 0.18f * t).coerceIn(0f, 1f)
            }
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

// Cover fallback: initials on an accent gradient (no art yet, or a custom game).
@Composable
private fun XmbMonogram(name: String, accent: Color) {
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
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .size(m.fW.dp, m.fH.dp)
            .graphicsLayer {
                val g = grow()
                val u = index - pos.floatValue
                val t = (1f - abs(u)).coerceAtLeast(0f) * g
                val sc = (m.sH + (m.fH - m.sH) * t) / m.fH
                translationX = (m.cross + u * m.pitch + m.grow * g * u.coerceIn(0f, 1f)).dp.toPx()
                scaleX = sc
                scaleY = sc
                transformOrigin = TransformOrigin(0f, 0f)
                alpha = ((if (u >= 0f) 0.82f else 0.82f + u * 0.32f) + 0.18f * t).coerceIn(0f, 1f)
            }
            .then(if (focused) Modifier.shadow(14.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            // Opaque, edge to edge: a see-through fill would let the focus shadow show through as a dark inner box.
            .background(Brush.linearGradient(art?.colors ?: listOf(Color(0xFF2A2D33), Color(0xFF16181C))))
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

// Right / lower pane: store badges, the same spec chips as the list card, playtime and Game Details.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun XmbInfo(s: Shortcut, playtime: String?, descLines: Int, storeBadges: @Composable (Shortcut) -> Unit) {
    val context = LocalContext.current
    val spec = remember(s) { buildLaunchSpec(s, context) }
    val details = remember(s) { buildLaunchDetails(s) }
    storeBadges(s)
    if (!remember(s) { com.winlator.star.androidgames.AndroidGames.isAndroidEntry(s) }) SpecChipRows(
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
private fun XmbHint(glyph: String, color: Color, label: String, s: Float = 1f) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(17.dp * s).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(glyph, color = Color.Black, fontSize = 10.sp * s, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(4.dp * s))
        Text(label, color = Color(0xFFB0B0B0), fontSize = 11.sp * s, maxLines = 1)
    }
}

@Composable
private fun XmbKeyHint(keys: String, label: String, s: Float = 1f) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            keys, color = Color(0xFFDDDDDD), fontSize = 10.sp * s,
            modifier = Modifier.border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp * s)).padding(horizontal = 5.dp * s),
        )
        Spacer(Modifier.width(4.dp * s))
        Text(label, color = Color(0xFFB0B0B0), fontSize = 11.sp * s, maxLines = 1)
    }
}

private class XmbRibbon(val y: Float, val amp: Float, val k: Float, val cycles: Int, val thick: Float, val alpha: Float)

private val XMB_RIBBONS = listOf(
    XmbRibbon(0.58f, 0.05f, 1.1f, 1, 0.07f, 0.10f),
    XmbRibbon(0.65f, 0.07f, 0.75f, -1, 0.11f, 0.07f),
    XmbRibbon(0.71f, 0.045f, 1.6f, 2, 0.05f, 0.12f),
    XmbRibbon(0.63f, 0.03f, 2.2f, -2, 0.02f, 0.16f),
)

// The XMB wave. Every time term is a whole number of turns per loop, so the 40 s cycle is seamless.
// The phase is read only inside the draw lambda, so this redraws without recomposing.
@Composable
private fun XmbWaves(accent: Color, modifier: Modifier) {
    val phase by rememberInfiniteTransition(label = "xmbWaves").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(40_000, easing = LinearEasing)),
        label = "xmbWavePhase",
    )
    val paths = remember { List(XMB_RIBBONS.size * 2) { Path() } }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tau = (2.0 * PI).toFloat()
        val step = 8.dp.toPx()
        XMB_RIBBONS.forEachIndexed { ri, r ->
            val ph = phase * tau * r.cycles
            val base = r.y * h
            val amp = r.amp * h
            val th = r.thick * h
            val edge = paths[ri * 2].apply { reset() }
            val band = paths[ri * 2 + 1].apply { reset() }
            val bottoms = ArrayList<Offset>()
            var x = 0f
            while (x <= w + step) {
                val u = x / w * tau * r.k
                val y = base + amp * sin(u + ph) + amp * 0.5f * sin(u * 0.55f - 2f * ph)
                if (x == 0f) { edge.moveTo(x, y); band.moveTo(x, y) } else { edge.lineTo(x, y); band.lineTo(x, y) }
                bottoms.add(Offset(x, y + th * (0.55f + 0.45f * sin(u * 0.7f + ph))))
                x += step
            }
            for (i in bottoms.indices.reversed()) band.lineTo(bottoms[i].x, bottoms[i].y)
            band.close()
            drawPath(
                band,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = r.alpha), accent.copy(alpha = 0f)),
                    startY = base - amp, endY = base + amp + th,
                ),
            )
            drawPath(edge, Color.White.copy(alpha = min(1f, r.alpha * 2.2f)), style = Stroke(width = 1.dp.toPx()))
        }
    }
}

// Controller hints only make sense with a pad attached; tracks hot-plug.
@Composable
private fun rememberGamepadConnected(): Boolean {
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

private fun xmbInitials(name: String): String {
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
