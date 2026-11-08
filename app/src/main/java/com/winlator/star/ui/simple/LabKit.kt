package com.winlator.star.ui.simple

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import com.winlator.star.ui.deck.DeckRoutes
import com.winlator.star.ui.deck.settings.DeckSettingsRoutes
import com.winlator.star.ui.theme.AppThemeState
import kotlin.math.abs

// Lab: Bannerlator's own interface (the successor of the Simple style). A dashboard of live tiles, a rail in landscape
// and a drawer in portrait, the game's main settings one press away, on a graph-paper wallpaper with signals running
// along its lines. Dark or light (AppThemeState.labLight); the accent is always the theme preset's or the custom one.

/** The Lab routes of its own (the rest are the Simple pages and the settings editors). */
internal object LabRoutes {
    const val LIBRARY = "lab_library"
    const val STORES = "lab_stores"
    const val GAME = "lab_game?path={path}"
    fun game(path: String) = "lab_game?path=" + android.net.Uri.encode(path)
    const val DETAILS_PREFIX = "lab_details"
    const val DETAILS = "lab_details?path={path}&tab={tab}"
    fun details(path: String, tab: Int = 0) = "lab_details?path=" + android.net.Uri.encode(path) + "&tab=" + tab
}

internal class LabSection(val label: String, val short: String, val icon: ImageVector, val route: String)

internal val LAB_SECTIONS = listOf(
    LabSection(T("Home"), T("Home"), Icons.Filled.Home, DeckRoutes.HOME),
    LabSection(T("Library"), T("Library"), Icons.Filled.GridView, LabRoutes.LIBRARY),
    LabSection(T("Stores"), T("Stores"), Icons.Filled.Storefront, LabRoutes.STORES),
    LabSection(T("Containers"), T("Containers"), Icons.Filled.Inventory2, SimpleRoutes.CONTAINERS),
    LabSection(T("Contents"), T("Contents"), Icons.Filled.Memory, SimpleRoutes.DRIVERS),
    LabSection(T("Controls"), T("Controls"), Icons.Filled.SportsEsports, SimpleRoutes.CONTROLS),
    LabSection(T("Files & tools"), T("Tools"), Icons.Filled.FolderOpen, SimpleRoutes.TOOLS),
    LabSection(T("Save Manager"), T("Saves"), Icons.Filled.Save, SimpleRoutes.SAVES),
    LabSection(T("Settings"), T("Settings"), Icons.Filled.Settings, DeckSettingsRoutes.APP),
)

/** Which section a route belongs to, for the rail and the drawer. */
internal fun labSectionOf(route: String?): Int {
    val r = route ?: return 0
    return when {
        r == DeckRoutes.HOME -> 0
        r.startsWith("lab_library") || r.startsWith("lab_game") || r.startsWith("lab_details") || r.startsWith("deck_settings_game") -> 1
        r.startsWith("lab_stores") -> 2
        r.startsWith("simple_containers") || r.startsWith("deck_settings_container") || r == DeckSettingsRoutes.DEFAULTS || r.startsWith("container_detail") -> 3
        r.startsWith("simple_driver") || r.startsWith("simple_component") || r.startsWith("adreno") || r.startsWith("contents") -> 4
        r.startsWith("simple_controls") || r.startsWith("input_controls") -> 5
        r.startsWith("simple_tools") || r.startsWith("simple_files") || r.startsWith("simple_logs") || r.startsWith("wrappers") -> 6
        r.startsWith("simple_saves") || r.startsWith("save_manager") -> 7
        r.startsWith("deck_settings_app") || r.startsWith("simple_appearance") || r.startsWith("settings") || r.startsWith("appearance") -> 8
        else -> 0
    }
}

/**
 * Lab's colours for the light look: light surfaces with the theme's accent. An accent too pale to read on white (Frost,
 * Monochrome) is darkened for text and outlines.
 */
internal fun labLightScheme(base: ColorScheme): ColorScheme {
    val p = if (base.primary.luminance() > 0.55f) lerp(base.primary, Color.Black, 0.45f) else base.primary
    return lightColorScheme(
        primary = p,
        onPrimary = Color.White,
        primaryContainer = lerp(Color.White, p, 0.16f),
        onPrimaryContainer = Color(0xFF0B0F17),
        secondary = p,
        onSecondary = Color.White,
        secondaryContainer = lerp(Color.White, p, 0.12f),
        onSecondaryContainer = Color(0xFF0B0F17),
        tertiary = base.tertiary,
        background = Color(0xFFEDF1F6),
        onBackground = Color(0xFF111722),
        surface = Color(0xFFF8FAFC),
        onSurface = Color(0xFF111722),
        surfaceVariant = Color(0xFFE3E8F0),
        onSurfaceVariant = Color(0xFF4A5566),
        surfaceTint = p,
        outline = Color(0xFFB8C2D1),
        outlineVariant = Color(0xFFD9E0EA),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFBFCFD),
        surfaceContainer = Color(0xFFF5F7FA),
        surfaceContainerHigh = Color(0xFFEEF2F6),
        surfaceContainerHighest = Color(0xFFE6EBF2),
        inverseSurface = Color(0xFF1A2029),
        inverseOnSurface = Color(0xFFEEF1F6),
        error = Color(0xFFD32F45),
        onError = Color.White,
        scrim = Color.Black,
    )
}

/**
 * Lab's live wallpaper: graph paper drifting slowly, with signals running along its lines in the accent colour and a
 * soft accent glow. Redrawn about 30 times a second while it is on screen (the frame clock stops when it is not).
 */
@Composable
internal fun LabWallpaper(modifier: Modifier = Modifier, fill: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val light = AppThemeState.labLight
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = -1L
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                if (start < 0) start = now
                if (now - last >= 33) { last = now; t = (now - start) / 1000f }
            }
        }
    }
    val accent = cs.primary
    val bg = cs.background
    val grid = if (light) Color(0x12111722) else Color(0x0DFFFFFF)
    Canvas(modifier.fillMaxSize()) {
        if (fill) drawRect(bg)
        val s = 48.dp.toPx()
        val drift = (t * 6.dp.toPx()) % s
        var x = drift
        while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += s }
        var y = 0f
        while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += s }
        // Signals: each runs along a grid line, then starts again on another one.
        val lanesH = (size.height / s).toInt().coerceAtLeast(1)
        val lanesV = (size.width / s).toInt().coerceAtLeast(1)
        for (i in 0 until 14) {
            val horizontal = i % 2 == 0
            val speed = (90 + (i * 37) % 90).dp.toPx()
            val len = (60 + (i * 53) % 100).dp.toPx()
            val span = (if (horizontal) size.width else size.height) + len * 2
            val travel = t * speed + i * 211.dp.toPx()
            val cycle = (travel / span).toInt()
            val head = travel % span - len
            val lane = abs((i * 7919 + cycle * 104729) % (if (horizontal) lanesH else lanesV))
            val alphaHead = if (light) 0.55f else 0.8f
            if (horizontal) {
                val yy = lane * s
                drawLine(
                    Brush.horizontalGradient(listOf(accent.copy(alpha = 0f), accent.copy(alpha = alphaHead)), startX = head - len, endX = head),
                    Offset(head - len, yy), Offset(head, yy), 2.dp.toPx(),
                )
                drawCircle(accent.copy(alpha = alphaHead), 2.4.dp.toPx(), Offset(head, yy))
            } else {
                val xx = lane * s + drift
                drawLine(
                    Brush.verticalGradient(listOf(accent.copy(alpha = 0f), accent.copy(alpha = alphaHead)), startY = head - len, endY = head),
                    Offset(xx, head - len), Offset(xx, head), 2.dp.toPx(),
                )
                drawCircle(accent.copy(alpha = alphaHead), 2.4.dp.toPx(), Offset(xx, head))
            }
        }
        if (!fill) return@Canvas
        val c = Offset(size.width * 0.78f, size.height * 0.12f)
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = if (light) 0.08f else 0.13f), Color.Transparent), center = c, radius = size.maxDimension * 0.65f),
            radius = size.maxDimension * 0.65f, center = c,
        )
    }
}

private val A_KEYS = setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

/**
 * A Lab tile or button: focusable by the controller (the D-pad moves between them), A or a tap runs [onClick], X or a
 * long press runs [onMenu] when given (a game's quick menu, a setting's full list). The focused one gets an accent
 * outline and a light tint; no press shading.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.labTile(
    onClick: () -> Unit,
    onMenu: (() -> Unit)? = null,
    requester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    /** On an element filled with the accent (the Play button): a contrasting ring instead of the accent one, and a bigger lift. */
    onAccent: Boolean = false,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    // Eased, so the D-pad glides from tile to tile: outline, tint and a slight lift follow one progress.
    val p by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1f else 0f,
        androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "labTileFocus",
    )
    return this
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged {
            if (it.isFocused && !focused) onFocus?.invoke()
            focused = it.isFocused
        }
        .graphicsLayer { val k = if (onAccent) 0.06f else 0.02f; scaleX = 1f + k * p; scaleY = 1f + k * p }
        .then(
            if (p <= 0.01f) Modifier
            else if (onAccent) Modifier.border(3.dp, SimpleStrong.copy(alpha = p), SimpleShape)
            else Modifier.background(accent.copy(alpha = 0.14f * p), SimpleShape).border(2.dp, accent.copy(alpha = p), SimpleShape)
        )
        .onKeyEvent { ev ->
            val k = ev.nativeKeyEvent
            when {
                k.keyCode in A_KEYS -> { if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) onClick(); true }
                k.keyCode == KeyEvent.KEYCODE_BUTTON_X && onMenu != null -> { if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) onMenu(); true }
                else -> false
            }
        }
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onMenu)
}

/** A Lab panel: the see-through card every tile sits on. */
@Composable
internal fun Modifier.labPanel(): Modifier =
    this.simpleDepth().background(SimplePanelBrush, SimpleShape).border(1.dp, SimpleLine, SimpleShape)

/** A small line chart of [values] in the accent colour (a game's FPS over its last sessions). */
@Composable
internal fun LabSparkline(values: List<Int>, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val line = SimpleLine
    Canvas(modifier) {
        for (i in 1..3) drawLine(line, Offset(0f, size.height * i / 4f), Offset(size.width, size.height * i / 4f), 1f)
        if (values.size < 2) {
            values.firstOrNull()?.let { drawCircle(accent, 3.dp.toPx(), Offset(size.width / 2f, size.height / 2f)) }
            return@Canvas
        }
        val lo = (values.min() - 3).toFloat()
        val hi = (values.max() + 3).toFloat()
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - (v - lo) / (hi - lo) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}
