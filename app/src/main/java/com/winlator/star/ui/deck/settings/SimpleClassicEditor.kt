package com.winlator.star.ui.deck.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.UnsavedChangesDialog
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.LabDownloadPicker
import com.winlator.star.ui.simple.LabDownloads
import com.winlator.star.ui.simple.SIn
import com.winlator.star.ui.simple.SimpleCover
import com.winlator.star.ui.simple.SimpleDim
import com.winlator.star.ui.simple.SimpleGlyph
import com.winlator.star.ui.simple.SimpleInk
import com.winlator.star.ui.simple.SimpleLine
import com.winlator.star.ui.simple.SimpleRowDivider
import com.winlator.star.ui.simple.SimpleMuted
import com.winlator.star.ui.simple.SimplePanelBrush
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.SimpleStrong
import com.winlator.star.ui.simple.SimpleFlat
import com.winlator.star.ui.simple.keepVisible
import com.winlator.star.ui.simple.keepInViewWhen
import com.winlator.star.ui.simple.simpleDepth
import com.winlator.star.ui.simple.simpleRowFocus
import com.winlator.star.ui.simple.tabFill
import com.winlator.star.ui.simple.simpleInputOf
import com.winlator.star.ui.simple.simpleTap
import com.winlator.star.ui.theme.AppThemeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

// The Simple style's container, new-container, All-containers and game menus, laid out like the Classic editors:
// Classic's tabs, Classic's order and names, every option shown (no Simple/Advanced/Expert), the gear dialogs as
// sub-pages. The rows, their storage and Save are the settings editor's own (the same SettingsSource and SettingDefs
// as every other style), so only the layout is new. A row a Classic tab does not place goes to "More" at the end of
// the tab its category belongs to, so nothing the app can set is left out.

// ───────────────────────────── layout ─────────────────────────────

private sealed interface CItem
private class CHead(val title: String) : CItem
/** One setting by id, with Classic's name for it ([only] picks between rows that share an id). */
private class CRow(val id: String, val label: String? = null, val only: ((SettingDef) -> Boolean)? = null) : CItem
/** Every not yet placed setting [test] matches, in the registry's order. */
private class CMatch(val test: (SettingDef) -> Boolean) : CItem
/** A gear dialog of the Classic editor: its rows on a page of their own. */
private class CPage(val title: String, val items: List<CItem>) : CItem
/** A section's lists and actions (drives, environment variables, components ...): the [category]'s extra items whose key [keys] accepts. */
private class CExtra(val category: String, val title: String, val keys: (String) -> Boolean = { true }, val top: Boolean? = null) : CItem
/** The [category]'s hand-offs (change the program, move to another container ...). */
private class CLinks(val category: String) : CItem
/** A category's extra drawn in place among the rows (not behind a row of its own), e.g. the adapter report. */
private class CInline(val category: String, val keys: (String) -> Boolean) : CItem
private class CTab(val title: String, val icon: ImageVector, val items: List<CItem>, val leftovers: Set<String> = emptySet())

private fun pre(vararg p: String): (SettingDef) -> Boolean = { d -> p.any { d.id.startsWith(it) } }

/** The Wayland compositor's driver (SettingsRegistry's own id; the X11 driver configuration's version is graphics.gdc.version). */
private const val COMPOSITOR_DRIVER = "graphics.compositorDriver"

/** DeckWrapperDetectedSection's invisible extra that reads the picked wrapper's detected keys (kept running like GRAPHICS_PROBES). */
private const val WRAPPER_DETECTED_PROBE = "wrapper-detected-probe"

private fun graphicsItems(game: Boolean): List<CItem> = listOf(
    CHead(L("Graphics")),
    CRow("performance.syncMode", L("Sync")).takeIf { game },
    CRow("graphics.driver", L("Graphics Driver")),
    CPage(L("Driver settings"), listOf(CMatch(pre("graphics.gdc.")))),
    CRow(COMPOSITOR_DRIVER, L("Graphics Driver")),
    CPage(L("Wayland game driver"), listOf(CInline("graphics") { it == WAYLAND_ADAPTER_REPORT }, CMatch(pre("graphics.wl.")))),
    CRow("display.hdrOutput", L("HDR output (HDR10)")),
    CRow("graphics.fastOpenGL", L("OpenGL: Fast")),
    CRow("graphics.glFpsHud", L("OpenGL: Show FPS")),
    CRow("graphics.glVsyncOff", L("OpenGL: Vsync off")),
    CRow("graphics.unrealHdr", L("Unreal Engine HDR")),
    CRow("graphics.dxWrapper", L("DX Wrapper")),
    CPage(L("DX wrapper settings"), listOf(CMatch(pre("graphics.dx.")))),
    CRow("graphics.renderer", L("Renderer")),
    CPage(
        L("Vulkan settings"),
        listOf(
            CRow("graphics.nativeRenderer", L("Native Renderer")), CRow("graphics.nativeBackend", L("Native backend")),
            CRow("graphics.presentMode", L("Present Mode")), CRow("graphics.rendererDriver", L("Renderer Driver")),
            CRow("graphics.swapColours", L("Colors")),
        ),
    ),
    CRow("graphics.sfColours", L("Correct SurfaceFlinger colours")),
    CRow("display.renderScale", L("Render scale (supersampling)")),
    CPage(
        L("Wrapper settings and tools"),
        listOf(CMatch(pre("graphics.wrapperDetected.")), CExtra("graphics", L("Detected in this wrapper"), { it.startsWith("wrapper-detected") }), CExtra("graphics", L("Downloads and tools"))),
    ),
).filterNotNull()

private fun audioItems(game: Boolean): List<CItem> = listOf(
    CHead(L("Audio")),
    CRow("audio.driver", L("Audio Driver")),
    CPage(if (game) L("Audio settings · this game") else L("Audio settings"), listOf(CMatch(pre("audio.latency")), CExtra("audio", L("Sound")))),
    CRow("audio.microphone", L("Microphone")),
    CRow("audio.midiSoundfont", L("MIDI SoundFont")),
)

private fun componentsTab(): CTab = CTab(
    L("Win Components"), Icons.Filled.GridView,
    listOf(
        CExtra(SettingsRegistry.CAT_COMPONENTS, L("This game needs"), top = true),
        CExtra(SettingsRegistry.CAT_COMPONENTS, L("Browse & install components"), top = false),
        CMatch { it.category == SettingsRegistry.CAT_COMPONENTS },
        CHead(L("Codecs")),
        CRow("windows.codecBackend", L("Builtin decoder")),
        CRow("windows.softwareDecoding", L("Software decoding")),
    ),
    leftovers = setOf(SettingsRegistry.CAT_COMPONENTS),
)

private fun envTab(title: String): CTab = CTab(
    title, Icons.Filled.Build,
    listOf(CMatch { it.category == "expert" }, CExtra("expert", title)),
    leftovers = setOf("expert"),
)

/** Classic's container editor: GENERAL · ENVIRONMENT · DRIVES · WIN COMPONENTS · ADVANCED (All containers has no drives). */
private fun containerLayout(): List<CTab> = listOf(
    CTab(
        L("General"), Icons.Filled.Settings,
        listOf(
            CHead(L("Container")),
            CRow("windows.containerName", L("Name")),
            CRow("display.resolution", L("Screen Size")),
            CRow("windows.wineVersion", L("Wine Version")),
            CRow("performance.syncMode", L("Sync")),
            CRow(SettingsEnv.ID_BACKEND, L("Display backend")),
        ) + graphicsItems(game = false) + listOf(
            CHead(L("Display")),
            CRow("display.fullscreenMode", L("Fullscreen Mode")),
            CRow("display.alignment", L("Screen Alignment")),
            CRow("performance.frameGen", L("Frame Generation (AI)")),
            CRow("performance.frameGenModel", L("Interpolation model")),
            CRow("performance.fgCapture", L("Capture resolution")),
            CRow("performance.lsfgVk11", L("LSFG on Vulkan 1.1 drivers (compat)")),
            CRow("performance.fpsLimiter", L("FPS Limiter")),
            CRow("display.refreshAuto", L("Auto (match FPS)")),
            CRow("display.refreshManual", L("Manual refresh rate")),
            CRow("display.refreshInGame", L("In-game refresh rate")),
            CRow("hud.show", L("Show FPS Counter")),
            CPage(L("FPS counter settings"), listOf(CMatch(pre("hud.")), CExtra("hud", L("Diagnostics")))),
        ) + audioItems(game = false) + listOf(
            CHead(L("Emulation")),
            CRow("performance.translator", L("Emulator (32-bit)")),
            CRow("performance.translator64", L("Emulator (64-bit)")),
            CHead(L("Other")),
            CRow("windows.closeOnExit", L("Close when game exits")),
            CPage(L("ReShade loadout"), listOf(CMatch(pre("visuals.reshade.")), CExtra("visuals", L("Effects"), { it.startsWith("reshade") }))),
            CRow("windows.locale", "LC_ALL"),
            CHead(L("Desktop")),
            CRow("windows.theme", L("Theme")),
            CRow("windows.desktopBackground", L("Background")),
            CRow("windows.wallpaperScope", L("Apply wallpaper to")),
            CExtra("windows", L("Wallpaper Image"), { it.startsWith("windows-wallpaper") }),
            CRow("windows.desktopColor", L("Background Color")),
            CRow("input.mouseWarp", L("Mouse Warp Override")),
            CRow("windows.runAsAdmin", L("Run as administrator")),
        ),
        leftovers = setOf("game", "launch", "display", "graphics", "audio", "hud", "windows", "linux"),
    ),
    envTab(L("Environment")),
    CTab(L("Drives"), Icons.Filled.Storage, listOf(CExtra("windows", L("Drives"), { it.startsWith("windows-drive") }))),
    componentsTab(),
    CTab(
        L("Advanced"), Icons.Filled.Memory,
        listOf(
            CHead("Box64EC"),
            CRow("performance.box64ecVersion", L("Box64EC Version")),
            CHead("Box64"),
            CRow("performance.box64Version", L("Version")),
            CRow("performance.box64Preset", L("Preset")),
            CHead("FEXCore"),
            CRow("performance.fexVersion", L("FEXCore Version")),
            CRow("performance.fexPreset", L("FEXCore Preset")),
            CRow("performance.forceSsbs", L("Force SSBS (CPU speed fix)")),
            CPage(L("Preset values"), listOf(CExtra("performance", L("Translator presets"), { it.startsWith("perf-preset") }))),
        ) + controllerItems(game = false) + listOf(
            CHead(L("System")),
            CRow("performance.startupServices", L("Startup Selection")),
            CPage(L("Custom Services"), listOf(CMatch(pre("performance.service.")))),
            CRow("performance.cpuAffinity", L("Processor Affinity")),
            CRow("performance.cpuAffinity32", L("Processor Affinity (32-bit apps)")),
            CHead("XR"),
            CRow("input.xr.primary", L("Primary controller")),
            CPage(L("Controller Mapping"), listOf(CMatch(pre("input.xr.map")))),
        ),
        leftovers = setOf("input", "performance", "visuals"),
    ),
)

private fun controllerItems(game: Boolean): List<CItem> = listOf(
    CHead(L("Game Controller")),
    CRow("input.dinputVisible", L("DirectInput games can see the controller")),
    CRow("input.dinputVibration", L("DirectInput vibration")),
    CRow("input.bannerinXInput", L("Direct XInput (BANNERIN)")),
    CRow("input.bannerinXInputSwitching", L("Allow in-game switching")),
    CRow("input.stickPacing", L("Stick updates")),
    CRow("input.controllerIdentity", L("Controller identity")),
    CRow("input.disableXInput", L("Disable XInput")),
    CHead(L("Vibration")),
    CRow("input.vibrationTarget", L("Rumble Target")),
    CRow("input.vibrationIntensity", L("Rumble Intensity")),
    CRow("input.rumbleMotors", L("Rumble Motors")),
    CHead(if (game) L("Input") else L("Player Slots")),
    CRow("input.numControllers", L("Num Controllers")),
    CRow("input.onScreenPriority", L("On-screen priority")),
    CRow("input.hideOnController", L("Hide on-screen controls when a controller connects")),
    CPage(L("Player Slots"), listOf(CExtra("input", L("Slots"), { it.startsWith("slots") }))),
    CRow("input.touchMode", L("Touch")),
    CRow("input.aimMode", L("Aim mode")),
    CHead(L("Gyro (Motion Aim)")),
    CRow("input.gyro.enabled", L("Enable Motion Aim")),
    CRow("input.gyro.mode", L("Motion Mode")),
    CRow("input.gyro.target", L("Motion Target")),
    CRow("input.gyro.activator", L("Activator Button")),
    CRow("input.gyro.activation", L("Activation")),
    CRow("input.gyro.sensitivity", L("Sensitivity")),
    CRow("input.gyro.deadzone", L("Deadzone")),
    CRow("input.gyro.smoothing", L("Smoothing")),
    CRow("input.gyro.invertX", L("Invert X")),
    CRow("input.gyro.invertY", L("Invert Y")),
)

/** Classic's game editor: General · Win Components · Env Vars · Advanced · Controller (+ TV). */
private fun gameLayout(): List<CTab> = listOf(
    CTab(
        L("General"), Icons.Filled.Settings,
        listOf(
            CHead(L("Game")),
            CRow("game.name", L("Name")),
            CRow("game.arguments", L("Exec Arguments")),
            CLinks("game"),
            CPage(L("Icon"), listOf(CExtra("game", L("Icon"), { it.startsWith("icon") }))),
            CHead(L("Launch")),
            CMatch(pre("launch.")),
            CLinks("launch"),
            CHead(L("Display")),
            CRow("display.resolution", L("Screen Size")),
            CRow("display.alignment", L("Screen Alignment")),
            CRow("display.fullscreenMode", L("Fullscreen Mode")),
            CRow("display.refreshInGame", L("In-game refresh rate")),
            CRow(SettingsEnv.ID_BACKEND, L("Display backend")),
        ) + graphicsItems(game = true) + listOf(
            CRow("performance.frameGen", L("Frame Generation (AI)")),
            CRow("performance.frameGenModel", L("Interpolation model")),
            CRow("performance.fpsLimiter", L("FPS Limiter")),
            CPage(L("Performance"), listOf(CMatch(pre("performance.power.", "performance.root.")), CExtra("performance", L("Power profile and root"), { it.startsWith("perf-power") }))),
            CRow("hud.show", L("Show FPS Counter")),
            CPage(L("FPS counter settings"), listOf(CMatch(pre("hud.")))),
        ) + audioItems(game = true) + listOf(
            CHead(L("Other")),
            CRow("performance.translator", L("Emulator (32-bit)")),
            CRow("performance.translator64", L("Emulator (64-bit)")),
            CRow("windows.locale", "LC_ALL"),
            CRow("windows.closeOnExit", L("Close when game exits")),
            CHead(L("Linux runtime")),
            CMatch { it.category == "linux" },
            CExtra("linux", L("Games folders and components")),
        ),
        leftovers = setOf("game", "launch", "display", "graphics", "audio", "hud", "windows", "linux"),
    ),
    componentsTab(),
    envTab(L("Env Vars")),
    CTab(
        L("Advanced"), Icons.Filled.Memory,
        listOf(
            CHead("Box64"),
            CRow("performance.box64Version", L("Version")),
            CRow("performance.box64Preset", L("Preset")),
            CHead("Box64EC"),
            CRow("performance.box64ecVersion", L("Box64EC Version")),
            CHead("FEXCore"),
            CRow("performance.fexVersion", L("FEXCore Version")),
            CRow("performance.fexPreset", L("FEXCore Preset")),
            CRow("performance.forceSsbs", L("Force SSBS (CPU speed fix)")),
            CPage(L("Preset values"), listOf(CExtra("performance", L("Translator presets"), { it.startsWith("perf-preset") }))),
            CHead(L("System")),
            CRow("input.controlsProfile", L("Controls Profile")),
            CRow("performance.startupServices", L("Startup Selection")),
            CPage(L("Custom Services"), listOf(CMatch(pre("performance.service.")))),
            CRow("performance.cpuAffinity", L("Processor Affinity")),
            CRow("performance.cpuAffinity32", L("Processor Affinity (32-bit apps)")),
            CHead(L("Sharpness (VKBasalt)")),
            CRow("visuals.vkbasalt", L("Effect")),
            CMatch(pre("visuals.vkbasalt")),
            CHead(L("ReShade")),
            CPage(L("ReShade loadout"), listOf(CMatch(pre("visuals.reshade.")), CExtra("visuals", L("Effects"), { it.startsWith("reshade") }))),
        ),
        leftovers = setOf("performance", "visuals"),
    ),
    CTab(L("Controller"), Icons.Filled.Gamepad, controllerItems(game = true), leftovers = setOf("input")),
    CTab("TV", Icons.Filled.Tv, listOf(CMatch(pre("display.tv.")))),
)

// ───────────────────────────── resolved lines ─────────────────────────────

private sealed interface RLine
private class RHead(val title: String) : RLine
private class RSet(val def: SettingDef, val label: String) : RLine
private class RPage(val title: String, val lines: List<RLine>) : RLine
private class RExtra(val title: String, val items: List<PageExtra>) : RLine
private class RLink(val h: Handoff) : RLine
private class RInline(val items: List<PageExtra>) : RLine
private class RTab(val title: String, val icon: ImageVector, val lines: List<RLine>)

private fun pruneHeads(lines: List<RLine>): List<RLine> = lines.filterIndexed { i, l ->
    if (l !is RHead) true
    else {
        var j = i + 1
        var any = false
        while (j < lines.size && lines[j] !is RHead) { any = true; j++ }
        any
    }
}

private fun resolve(
    tabs: List<CTab>,
    defs: List<SettingDef>,
    applies: (SettingDef) -> Boolean,
    extras: Map<String, CategoryExtra>,
    handoffs: Map<String, List<Handoff>>,
    categoryLabel: (String) -> String,
): List<RTab> {
    // Placed rows by identity: a few rows share an id (see isCompositorRow).
    val placed = HashSet<SettingDef>()
    val extraItems: Map<String, List<Pair<PageExtra, Boolean>>> =
        extras.mapValues { (_, e) -> e.top().map { it to true } + e.bottom().map { it to false } }
    // The Graphics lists reader and the wrapper keys reader draw nothing; the editor keeps them running itself (see SimpleClassicEditor).
    val placedExtra = hashSetOf("graphics|$GRAPHICS_PROBES", "graphics|$WRAPPER_DETECTED_PROBE")
    fun res(items: List<CItem>): List<RLine> = buildList {
        for (item in items) when (val it = item) {
            is CHead -> add(RHead(it.title))
            is CRow -> defs.firstOrNull { d -> d.id == it.id && d !in placed && (it.only?.invoke(d) ?: true) && applies(d) }
                ?.let { d -> placed += d; add(RSet(d, it.label ?: d.label)) }
            is CMatch -> defs.filter { d -> d !in placed && applies(d) && it.test(d) }.forEach { d -> placed += d; add(RSet(d, d.label)) }
            is CPage -> {
                val lines = pruneHeads(res(it.items))
                if (lines.isNotEmpty()) add(RPage(it.title, lines))
            }
            is CExtra -> {
                val list = extraItems[it.category].orEmpty()
                    .filter { (p, top) -> "${it.category}|${p.key}" !in placedExtra && it.keys(p.key) && (it.top == null || it.top == top) }
                    .map { (p, _) -> p }
                list.forEach { p -> placedExtra += "${it.category}|${p.key}" }
                if (list.isNotEmpty()) add(RExtra(it.title, list))
            }
            is CInline -> {
                val list = extraItems[it.category].orEmpty()
                    .filter { (p, _) -> "${it.category}|${p.key}" !in placedExtra && it.keys(p.key) }
                    .map { (p, _) -> p }
                list.forEach { p -> placedExtra += "${it.category}|${p.key}" }
                if (list.isNotEmpty()) add(RInline(list))
            }
            is CLinks -> handoffs[it.category].orEmpty()
                .filter { h -> !h.target.startsWith(SettingsRegistry.TARGET_CATEGORY) && h.target != SettingsRegistry.TARGET_NONE }
                .forEach { h -> add(RLink(h)) }
        }
    }
    val resolved = tabs.map { t -> t to res(t.items).toMutableList() }
    for ((t, lines) in resolved) {
        val left = defs.filter { d -> d !in placed && d.category in t.leftovers && applies(d) }
        if (left.isNotEmpty()) {
            lines += RHead(L("More"))
            left.forEach { d -> placed += d; lines += RSet(d, d.label) }
        }
        for (cat in t.leftovers) {
            val rest = extraItems[cat].orEmpty().filter { (p, _) -> "$cat|${p.key}" !in placedExtra }.map { (p, _) -> p }
            if (rest.isNotEmpty()) {
                rest.forEach { p -> placedExtra += "$cat|${p.key}" }
                if (lines.none { it is RHead && it.title == L("More") }) lines += RHead(L("More"))
                lines += RExtra(categoryLabel(cat), rest)
            }
        }
    }
    return resolved.map { (t, lines) -> RTab(t.title, t.icon, pruneHeads(lines)) }.filter { tab -> tab.lines.any { it !is RHead } }
}

private enum class KZone { ROWS, TABS, EXTRAS }

// ───────────────────────────── editor ─────────────────────────────

@Composable
internal fun SimpleClassicEditor(
    header: EditorHeader,
    source: SettingsSource,
    categories: List<SettingsCategory>,
    defs: List<SettingDef>,
    handoffs: Map<String, List<Handoff>>,
    onExit: (() -> Unit)?,
    onHandoff: (Handoff) -> Unit,
    headerExtra: (@Composable () -> Unit)?,
    extras: Map<String, CategoryExtra>,
    createMode: Boolean,
    yAction: Pair<String, () -> Unit>?,
) {
    val context = LocalContext.current
    val env = source.env
    val game = env.scope == EditorScope.GAME
    val layout by AppThemeState.simpleLayout.collectAsState()
    val byId = remember(defs) { defs.associateBy { it.id } }
    val values: (String) -> String = { id -> byId[id]?.takeIf { source.shows(it) }?.let { source.value(it) } ?: "" }
    fun applies(def: SettingDef) = source.shows(def) && env.world in def.worlds && def.visible(env, values)
    val worldHandoffs = remember(handoffs, env) {
        handoffs.mapValues { (_, list) -> list.filter { env.world in it.worlds } }.filterValues { it.isNotEmpty() }
    }
    LaunchedEffect(env) { env.loadAsync() }
    // Driver versions, DXVK/VKD3D/VEGAS/D7VK builds and the GPU names are read by this extra while it is
    // composed. Lab has no Graphics page to host it, so it runs for as long as the editor is open.
    val graphicsProbes = remember(extras) { extras["graphics"]?.top()?.filter { it.key == GRAPHICS_PROBES }.orEmpty() }
    graphicsProbes.forEach { it.content() }
    // The picked wrapper's detected keys, so "Detected in this wrapper" only shows when it has rows. Read on every
    // composition: it applies only to X11 Wine containers and games, which the backend row can change.
    extras["graphics"]?.top()?.filter { it.key == WRAPPER_DETECTED_PROBE }.orEmpty().forEach { p -> key(p.key) { p.content() } }
    val catLabels = remember(categories) { categories.associate { it.id to it.label } }
    val tabs = resolve(
        if (game) gameLayout() else containerLayout(),
        defs, ::applies, extras, worldHandoffs,
    ) { catLabels[it] ?: it }

    var tabKey by rememberSaveable { mutableStateOf(tabs.firstOrNull()?.title ?: "") }
    val tabIndex = tabs.indexOfFirst { it.title == tabKey }.let { if (it < 0) 0 else it }
    val tab = tabs.getOrNull(tabIndex)
    // The open sub-pages, by title, and the row each was opened from.
    var stack by remember { mutableStateOf(listOf<Pair<String, Int>>()) }
    var row by rememberSaveable { mutableIntStateOf(0) }
    var zone by remember { mutableStateOf(KZone.ROWS) }

    // The lines on screen: the tab's, or the open sub-page's. A page that no longer applies closes.
    var lines: List<RLine> = tab?.lines.orEmpty()
    var openExtra: RExtra? = null
    var depth = 0
    for ((title, _) in stack) {
        val next = lines.firstOrNull { (it is RPage && it.title == title) || (it is RExtra && it.title == title) }
        if (next is RPage) { lines = next.lines; depth++ }
        else if (next is RExtra) { openExtra = next; depth++; break }
        else break
    }
    val liveDepth = depth
    LaunchedEffect(liveDepth, stack.size) { if (liveDepth < stack.size) stack = stack.take(liveDepth) }
    // A tab that is only a list (Drives, the environment variables) shows that list straight away.
    val tabIsExtra = stack.isEmpty() && lines.size == 1 && lines[0] is RExtra
    if (tabIsExtra) openExtra = lines[0] as RExtra
    val extraOpen = openExtra != null
    LaunchedEffect(extraOpen, tabKey) { if (extraOpen) zone = KZone.EXTRAS else if (zone == KZone.EXTRAS) zone = KZone.ROWS }
    fun canFocus(l: RLine?) = l != null && l !is RHead && l !is RInline
    val rowNow = row.coerceIn(0, max(0, lines.size - 1)).let { r ->
        if (canFocus(lines.getOrNull(r))) r else lines.indexOfFirst { canFocus(it) }.coerceAtLeast(0)
    }

    // Dialogs and pickers.
    var pickerFor by remember { mutableStateOf<SettingDef?>(null) }
    // The setting whose "+ Download from source" pages are open.
    var downloadFor by remember { mutableStateOf<SettingDef?>(null) }
    val downloadScope = rememberCoroutineScope()
    var textFor by remember { mutableStateOf<SettingDef?>(null) }
    var coresFor by remember { mutableStateOf<SettingDef?>(null) }
    var checklistFor by remember { mutableStateOf<SettingDef?>(null) }
    var customFor by remember { mutableStateOf<SettingDef?>(null) }
    var helpFor by remember { mutableStateOf<SettingDef?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<SettingDef, String>?>(null) }
    var afterUnsaved by remember { mutableStateOf<(() -> Unit)?>(null) }
    var saving by remember { mutableStateOf(false) }
    val dialogOpen = pickerFor != null || downloadFor != null || textFor != null || coresFor != null || checklistFor != null ||
        customFor != null || helpFor != null || pendingConfirm != null || afterUnsaved != null

    val pick: (SettingDef, String) -> Unit = { def, v ->
        val ask = def.confirm?.invoke(v)
        if (ask != null && source.value(def) != v) pendingConfirm = def to v else source.set(def, v)
    }
    fun save(after: (() -> Unit)? = null) {
        if (saving) return
        if (createMode && env.vm?.selectedWineVersion.isNullOrBlank()) {
            Toast.makeText(context, L("Install a Wine or Proton version first (Components)."), Toast.LENGTH_LONG).show()
            return
        }
        saving = true
        source.save { ok ->
            saving = false
            if (ok) {
                when {
                    createMode -> {
                        Toast.makeText(context, L("Container created"), Toast.LENGTH_SHORT).show()
                        onExit?.invoke()
                        return@save
                    }
                    env.scope != EditorScope.DEFAULTS -> Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                }
                after?.invoke()
            }
        }
    }
    val dirty = source.dirty
    // Touch has no X: Create / Save is also a button (portrait has no side card and no hint line).
    val showAction = createMode || dirty
    val actionLabel = if (createMode) L("Create container") else L("Save")
    val onAction: () -> Unit = { save() }
    val guard: (() -> Unit) -> Unit = { action -> if (dirty) afterUnsaved = action else action() }
    fun goTab(i: Int) {
        if (tabs.isEmpty()) return
        tabKey = tabs[(i + tabs.size) % tabs.size].title
        stack = emptyList()
        row = 0
        if (zone == KZone.EXTRAS) zone = KZone.ROWS
    }
    fun back() {
        when {
            stack.isNotEmpty() -> { row = stack.last().second; stack = stack.dropLast(1); zone = KZone.ROWS }
            else -> onExit?.let { guard(it) }
        }
    }

    fun change(def: SettingDef, d: Int) {
        if (!source.editable(def) || def.disabled(env, values) != null) return
        val v = source.value(def)
        when (val c = def.control(env, values)) {
            Control.Toggle -> pick(def, if (v == "1") "0" else "1")
            is Control.Segmented, is Control.Chips, is Control.Select -> {
                val opts = optionsOf(c).filter { it.disabledReason == null }
                if (opts.isEmpty()) return
                val i = opts.indexOfFirst { it.value == v }
                val next = if (i < 0) (if (d > 0) 0 else opts.size - 1) else (i + d + opts.size) % opts.size
                pick(def, opts[next].value)
            }
            is Control.Slider -> pick(def, c.encode(((v.toFloatOrNull() ?: c.min) + d * c.step).coerceIn(c.min, c.max)))
            else -> Unit
        }
    }
    fun activate(l: RLine) {
        when (l) {
            is RSet -> {
                val def = l.def
                if (!source.editable(def) || def.disabled(env, values) != null) { helpFor = def; return }
                when (val c = def.control(env, values)) {
                    Control.Toggle -> change(def, 1)
                    is Control.Segmented, is Control.Chips -> { if (optionsOf(c).size > 3) pickerFor = def else change(def, 1) }
                    is Control.Select -> pickerFor = def
                    is Control.Slider -> change(def, 1)
                    is Control.TextField -> textFor = def
                    Control.Cores -> coresFor = def
                    is Control.Checklist -> { if (!c.items.isNullOrEmpty()) checklistFor = def }
                }
            }
            is RPage -> { stack = stack + (l.title to rowNow); row = 0 }
            is RExtra -> { stack = stack + (l.title to rowNow); row = 0 }
            is RLink -> guard { onHandoff(l.h) }
            is RHead, is RInline -> Unit
        }
    }

    BackHandler(enabled = onExit != null || stack.isNotEmpty()) { back() }

    // The legend in the Simple frame.
    val deckActions = LocalDeckActions.current

    // L2/R2 through the shell as well: they then work wherever the controller focus is (the Lab rail, a tile, a dialog).
    val stepTabsNow = androidx.compose.runtime.rememberUpdatedState<(Int) -> Unit> { d -> goTab(tabIndex + d) }
    DisposableEffect(deckActions, tabs.size) {
        if (tabs.size < 2) return@DisposableEffect onDispose {}
        val step: (Int) -> Unit = { stepTabsNow.value(it) }
        deckActions.subTab.value = step
        deckActions.subTabLabel.value = L("Tab")
        onDispose { if (deckActions.subTab.value === step) deckActions.subTab.value = null }
    }
    val cur = lines.getOrNull(rowNow)
    val curDef = (cur as? RSet)?.def
    val legend = buildList {
        add("B" to L("Back"))
        if (zone == KZone.ROWS && curDef != null && isStepped(curDef.control(env, values)) && source.editable(curDef)) add("◀▶" to L("Change"))
        if (zone == KZone.ROWS && cur != null && cur !is RHead) add("A" to L("Open"))
        if (tabs.size > 1) add("LB RB" to L("Tab"))
        if (createMode) add("X" to L("Create")) else if (dirty) add("X" to L("Save"))
        yAction?.let { add("Y" to it.first) }
        if (game && curDef != null && source.origin(curDef) == Origin.SET_HERE) add("⧉" to L("Use container's"))
    }
    DisposableEffect(deckActions, legend) {
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }

    val focus = remember { FocusRequester() }
    val extrasAnchor = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(zone, dialogOpen, stack.size, tabKey) {
        if (dialogOpen) return@LaunchedEffect
        delay(if (zone == KZone.EXTRAS) 160 else 60)
        runCatching {
            if (zone == KZone.EXTRAS) {
                extrasAnchor.requestFocus()
                focusManager.moveFocus(FocusDirection.Next)
            } else focus.requestFocus()
        }
    }
    val onKey: (SIn) -> Boolean = onKey@{ a ->
        when {
            a == SIn.LB || a == SIn.LT -> goTab(tabIndex - 1)
            a == SIn.RB || a == SIn.RT -> goTab(tabIndex + 1)
            a == SIn.X -> if (createMode || dirty) save()
            a == SIn.Y -> yAction?.second?.invoke()
            a == SIn.B -> if (zone == KZone.TABS) zone = KZone.ROWS else back()
            zone == KZone.EXTRAS -> return@onKey false
            a == SIn.SELECT -> { if (curDef != null && source.origin(curDef) == Origin.SET_HERE) source.reset(curDef) }
            zone == KZone.TABS -> when (a) {
                SIn.UP -> { if (layout == AppThemeState.SIMPLE_LAYOUT_FOCUS) zone = KZone.ROWS else goTab(tabIndex - 1) }
                SIn.DOWN -> goTab(tabIndex + 1)
                SIn.LEFT -> goTab(tabIndex - 1)
                SIn.RIGHT -> { if (layout == AppThemeState.SIMPLE_LAYOUT_RAIL) zone = KZone.ROWS else goTab(tabIndex + 1) }
                SIn.A -> zone = KZone.ROWS
                else -> return@onKey a == SIn.START
            }
            else -> when (a) {
                SIn.UP -> {
                    var i = rowNow - 1
                    while (i >= 0 && !canFocus(lines.getOrNull(i))) i--
                    if (i >= 0) row = i
                }
                SIn.DOWN -> {
                    var i = rowNow + 1
                    while (i < lines.size && !canFocus(lines.getOrNull(i))) i++
                    if (i < lines.size) row = i
                    else if (layout == AppThemeState.SIMPLE_LAYOUT_FOCUS && stack.isEmpty() && tabs.size > 1) zone = KZone.TABS
                }
                SIn.LEFT, SIn.RIGHT -> {
                    val d = if (a == SIn.LEFT) -1 else 1
                    if (curDef != null && source.editable(curDef) && curDef.disabled(env, values) == null && isStepped(curDef.control(env, values))) change(curDef, d)
                    else if (a == SIn.LEFT && layout == AppThemeState.SIMPLE_LAYOUT_RAIL && stack.isEmpty()) zone = KZone.TABS
                }
                SIn.A -> cur?.let { activate(it) }
                SIn.START -> curDef?.let { d -> if ((d.help ?: d.hint) != null) helpFor = d }
                else -> return@onKey false
            }
        }
        true
    }

    SimpleFlat {
    // A game's settings sit on its own art, drawn by the shell edge to edge (under the bar areas too).
    DisposableEffect(deckActions, env.shortcut) {
        val s = env.shortcut
        deckActions.pageArt.value = s
        onDispose { if (deckActions.pageArt.value === s) deckActions.pageArt.value = null }
    }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (dialogOpen) return@onPreviewKeyEvent false
                    val a = simpleInputOf(ev.nativeKeyEvent) ?: return@onPreviewKeyEvent false
                    onKey(a)
                },
        ) {
            val narrow = maxWidth < 760.dp
            val crumb = stack.map { it.first }
            val title: @Composable (Boolean) -> Unit = { big ->
                ClassicTitle(header, if (big) (crumb.lastOrNull() ?: tab?.title ?: "") else header.title, if (big) header.title else null, crumb, tab?.title, dirty, createMode, big, onAction = onAction)
            }
            val body: @Composable (Modifier) -> Unit = { mod ->
                val ex = openExtra
                if (ex != null) {
                    ExtrasPanel(ex, extrasAnchor, active = zone == KZone.EXTRAS, modifier = mod)
                } else {
                    LinesPanel("$tabKey/${stack.joinToString("/") { it.first }}", lines, rowNow, active = zone == KZone.ROWS, source = source, env = env, values = values, game = game, modifier = mod, onHelp = { helpFor = it }) { i ->
                        zone = KZone.ROWS
                        if (i == rowNow) lines.getOrNull(i)?.let { activate(it) } else row = i
                    }
                }
            }
            val card: @Composable (Modifier) -> Unit = { mod -> SideCard(header, env, source, values, createMode, game, mod, if (showAction) actionLabel else null, onAction) }
            when (layout) {
                AppThemeState.SIMPLE_LAYOUT_RAIL -> Row(Modifier.fillMaxSize()) {
                    TabList(header, tabs, tabIndex, active = zone == KZone.TABS, modifier = Modifier.width(if (narrow) 210.dp else 250.dp).fillMaxHeight()) { goTab(it) }
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = 22.dp, end = 22.dp, top = 12.dp)) {
                        ClassicTitle(header, crumb.lastOrNull() ?: tab?.title ?: "", null, crumb, tab?.title, dirty, createMode, big = false, showOverline = false, onAction = onAction)
                        headerExtra?.invoke()
                        Spacer(Modifier.height(10.dp))
                        body(Modifier.weight(1f).fillMaxWidth())
                        if (showAction) {
                            Spacer(Modifier.height(10.dp))
                            ActionButton(actionLabel, onAction, if (narrow) Modifier.fillMaxWidth() else Modifier.align(Alignment.End).widthIn(min = 260.dp))
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                AppThemeState.SIMPLE_LAYOUT_FOCUS -> Column(Modifier.fillMaxSize().padding(start = 44.dp, end = 44.dp, top = 8.dp)) {
                    title(true)
                    headerExtra?.invoke()
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        body(Modifier.weight(1f).fillMaxHeight())
                        if (!narrow) { Spacer(Modifier.width(20.dp)); card(Modifier.width(250.dp).fillMaxHeight()) }
                    }
                    if (showAction && narrow) {
                        Spacer(Modifier.height(10.dp))
                        ActionButton(actionLabel, onAction, Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(10.dp))
                    TabStrip(tabs, tabIndex, active = zone == KZone.TABS) { goTab(it) }
                    Spacer(Modifier.height(6.dp))
                }
                else -> Column(Modifier.fillMaxSize().padding(start = 44.dp, end = 44.dp, top = 4.dp)) {
                    title(false)
                    headerExtra?.invoke()
                    Spacer(Modifier.height(10.dp))
                    TabTabs(tabs, tabIndex, compact = narrow) { goTab(it) }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        body(Modifier.weight(1f).fillMaxHeight())
                        if (!narrow) { Spacer(Modifier.width(22.dp)); card(Modifier.width(300.dp).fillMaxHeight()) }
                    }
                    if (showAction && narrow) {
                        Spacer(Modifier.height(10.dp))
                        ActionButton(actionLabel, onAction, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    // ── Dialogs (the same ones the other Simple editor uses) ──
    pickerFor?.let { def ->
        val c = def.control(env, values)
        SimplePicker(
            title = (lines.firstOrNull { it is RSet && it.def.id == def.id } as? RSet)?.label ?: def.label,
            options = optionsOf(c),
            current = source.value(def),
            customLabel = (c as? Control.Select)?.customLabel,
            onDismiss = { pickerFor = null },
            onPick = { v -> pickerFor = null; pick(def, v) },
            onCustom = { pickerFor = null; customFor = def },
            onDownload = LabDownloads.typesFor(def.id, env)?.let { { pickerFor = null; downloadFor = def } },
        )
    }
    downloadFor?.let { def ->
        val types = LabDownloads.typesFor(def.id, env)
        if (types == null) downloadFor = null
        else LabDownloadPicker(types, onClose = {
            downloadFor = null
            downloadScope.launch {
                // Back to the picker with the list read again; nothing is picked for the user.
                runCatching { LabDownloads.refresh(def.id, env) }
                pickerFor = def
                // A download still running when the pages closed is listed once it has installed.
                if (LabDownloads.downloading()) {
                    LabDownloads.awaitDownloads()
                    runCatching { LabDownloads.refresh(def.id, env) }
                }
            }
        })
    }
    customFor?.let { def ->
        val control = def.control(env, values) as? Control.Select
        if (control == null) customFor = null
        else CustomValueDialog(def = def, control = control, current = source.value(def), onDismiss = { customFor = null }, onUse = { v -> customFor = null; pick(def, v) })
    }
    textFor?.let { def ->
        val control = def.control(env, values) as? Control.TextField
        if (control == null) textFor = null
        else OutlinedAlertDialog(
            onDismissRequest = { textFor = null },
            confirmButton = { TextButton(onClick = { textFor = null }) { Text(L("Done")) } },
            title = { Text((lines.firstOrNull { it is RSet && it.def.id == def.id } as? RSet)?.label ?: def.label) },
            text = { TextControl(control, source.value(def), enabled = true) { v -> pick(def, v) } },
        )
    }
    coresFor?.let { def ->
        OutlinedAlertDialog(
            onDismissRequest = { coresFor = null },
            confirmButton = { TextButton(onClick = { coresFor = null }) { Text(L("Done")) } },
            title = { Text(L(def.label)) },
            text = { CoresControl(source.value(def), enabled = true) { v -> pick(def, v) } },
        )
    }
    checklistFor?.let { def ->
        val control = def.control(env, values) as? Control.Checklist
        val items = control?.items
        if (control == null || items == null) checklistFor = null
        else {
            val value = source.value(def)
            ChecklistDialog(
                title = control.title,
                all = items,
                off = value.split(',').filter { it.isNotEmpty() }.toSet(),
                onDismiss = { checklistFor = null },
                onDone = { picked ->
                    checklistFor = null
                    val next = items.filter { it in picked }.joinToString(",")
                    if (next != value) pick(def, next)
                },
            )
        }
    }
    helpFor?.let { def ->
        OutlinedAlertDialog(
            onDismissRequest = { helpFor = null },
            confirmButton = { TextButton(onClick = { helpFor = null }) { Text("OK") } },
            title = { Text(L(def.label)) },
            text = {
                Text(
                    listOfNotNull(
                        def.disabled(env, values),
                        if (!source.editable(def)) L("This is a container setting; change it in the container's settings.") else null,
                        (def.help ?: def.hint)?.let { L(it) },
                    ).joinToString("\n\n"),
                )
            },
        )
    }
    pendingConfirm?.let { (def, v) ->
        val ask = def.confirm?.invoke(v)
        if (ask == null) {
            pendingConfirm = null
            source.set(def, v)
        } else {
            OutlinedAlertDialog(
                onDismissRequest = { pendingConfirm = null },
                confirmButton = { TextButton(onClick = { pendingConfirm = null; source.set(def, v) }) { Text(L(ask.confirmLabel)) } },
                dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text(L("Cancel")) } },
                title = { Text(L(ask.title)) },
                text = { Text(L(ask.body)) },
            )
        }
    }
    afterUnsaved?.let { action ->
        UnsavedChangesDialog(
            onSave = { afterUnsaved = null; save(after = action) },
            onDiscard = { afterUnsaved = null; source.discard(); action() },
            onKeepEditing = { afterUnsaved = null },
        )
    }
    }
}

// ───────────────────────────── pieces ─────────────────────────────

@Composable
private fun ClassicTitle(
    header: EditorHeader,
    title: String,
    sub: String?,
    crumb: List<String>,
    tabTitle: String?,
    dirty: Boolean,
    createMode: Boolean,
    big: Boolean,
    showOverline: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        if (showOverline) Text(L(header.overline).uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
        // A fixed height: the Unsaved tag appearing must not move the page.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(if (big) 52.dp else 38.dp)) {
            Text(
                title,
                color = SimpleStrong,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (big) 38.sp else 26.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            if (dirty || createMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.background(accent.copy(alpha = 0.22f), SimpleShape)
                        .then(if (onAction != null) Modifier.pointerInput(onAction) { detectTapGestures(onTap = { onAction() }) } else Modifier)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(if (createMode) L("Not created yet") else L("Unsaved"), color = SimpleStrong, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    SimpleGlyph("X")
                }
            }
        }
        if (!sub.isNullOrBlank()) Text(sub, color = SimpleMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (crumb.isNotEmpty() && !big) {
            Text(
                (listOfNotNull(tabTitle) + crumb).joinToString("  ›  "),
                color = SimpleMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Shelf: the tabs between LB and RB. */
@Composable
private fun TabTabs(tabs: List<RTab>, index: Int, compact: Boolean = false, onPick: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        if (tabs.size > 1 && !compact) { SimpleGlyph("LB"); Spacer(Modifier.width(8.dp)) }
        tabs.forEachIndexed { i, t ->
            val on = i == index
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .then(Modifier.tabFill(on, accent))
                    .simpleTap { onPick(i) }
                    .keepInViewWhen(on)
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            ) {
                Icon(t.icon, contentDescription = null, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(18.dp))
                // Portrait: the other tabs as icons, so the row fits a phone's width.
                if (on || !compact) {
                    Spacer(Modifier.width(8.dp))
                    Text(t.title, color = if (on) SimpleStrong else SimpleMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        if (tabs.size > 1 && !compact) { Spacer(Modifier.width(2.dp)); SimpleGlyph("RB") }
    }
}

/** Rail: what is being edited, then its tabs down the left. */
@Composable
private fun TabList(header: EditorHeader, tabs: List<RTab>, index: Int, active: Boolean, modifier: Modifier, onPick: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.simpleDepth(high = true, shape = RectangleShape).background(SimplePanelBrush).padding(start = 10.dp, end = 10.dp, top = 18.dp)) {
        Text(L(header.overline).uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Text(L(header.title), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp))
        tabs.forEachIndexed { i, t ->
            val on = i == index
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .then(
                        if (on) Modifier
                            .background(if (active) accent.copy(alpha = 0.32f) else accent.copy(alpha = 0.16f), SimpleShape)
                            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                        else Modifier
                    )
                    .simpleTap { onPick(i) }
                    .padding(horizontal = 14.dp),
            ) {
                Icon(t.icon, contentDescription = null, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(t.title, color = if (on) SimpleStrong else SimpleInk.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Focus: the tabs as tiles under the menu. */
@Composable
private fun TabStrip(tabs: List<RTab>, index: Int, active: Boolean, onPick: (Int) -> Unit) {
    if (tabs.size < 2) return
    val accent = MaterialTheme.colorScheme.primary
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(80.dp)) {
        itemsIndexed(tabs, key = { _, t -> t.title }) { i, t ->
            val on = i == index
            val scale by animateFloatAsState(if (on && active) 1.08f else 1f, tween(200), label = "simpleClassicTab")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .width(124.dp)
                    .fillMaxHeight()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .simpleDepth(high = on)
                    .background(if (on) accent.copy(alpha = 0.3f) else Color(0xB30C0E13), SimpleShape)
                    .border(if (on && active) 2.dp else 1.dp, if (on) accent else SimpleLine, SimpleShape)
                    .simpleTap { onPick(i) }
                    .padding(8.dp),
            ) {
                Icon(t.icon, contentDescription = null, tint = if (on) SimpleStrong else SimpleMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text(t.title, color = if (on) SimpleStrong else SimpleMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** What is being edited, beside the menu: the game's cover, or the container's main choices. */
@Composable
private fun SideCard(
    header: EditorHeader,
    env: SettingsEnv,
    source: SettingsSource,
    values: (String) -> String,
    createMode: Boolean,
    game: Boolean,
    modifier: Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val vm = env.vm
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .simpleDepth(high = true)
            .background(SimplePanelBrush, SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape)
            .padding(16.dp),
    ) {
        val s = env.shortcut
        if (s != null) SimpleCover(s, Modifier.fillMaxWidth().height(150.dp))
        else Box(
            contentAlignment = Alignment.BottomStart,
            modifier = Modifier.fillMaxWidth().height(110.dp).simpleDepth()
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), Color(0xFF0E161D))), SimpleShape)
                .padding(12.dp),
        ) {
            Text(
                if (createMode) values("windows.containerName").ifBlank { header.title } else header.title,
                color = Color.White, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        val kv = buildList {
            if (game) {
                s?.container?.name?.let { add(L("Container") to it) }
            } else if (vm != null && env.scope != EditorScope.DEFAULTS) {
                add("Wine" to vm.selectedWineVersion)
            }
            if (vm != null) add("Arch" to if (vm.isArm64EC) "ARM64EC" else "x86-64")
            add(L("Display") to if (values(SettingsEnv.ID_BACKEND) == com.winlator.star.container.Container.DISPLAY_BACKEND_WAYLAND) "Wayland" else "X11")
            if (game) {
                val n = SettingsRegistry.defs.count { d -> source.shows(d) && d.game != null && runCatching { source.origin(d) }.getOrNull() == Origin.SET_HERE }
                add(L("Set for this game") to n.toString())
            }
        }
        kv.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth()) {
                Text(k, color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(v, color = SimpleInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            when {
                createMode -> L("Starts from your New Container Defaults. X creates it.")
                game -> L("Container = follows the container. This game = set here.")
                else -> ""
            },
            color = SimpleDim, fontSize = 12.sp,
        )
        if (actionLabel != null) ActionButton(actionLabel, onAction, Modifier.fillMaxWidth())
    }
}

/**
 * Create / Save for touch, next to the X a controller uses. It takes taps only and is not focusable, so the
 * D-pad focus stays on the rows and tabs.
 */
@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    var pressed by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(52.dp)
            .background(accent.copy(alpha = if (pressed) 0.72f else 1f), SimpleShape)
            .pointerInput(onClick) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onClick() },
                )
            }
            .padding(horizontal = 18.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        SimpleGlyph("X")
    }
}

@Composable
private fun LinesPanel(
    listKey: String,
    lines: List<RLine>,
    row: Int,
    active: Boolean,
    source: SettingsSource,
    env: SettingsEnv,
    values: (String) -> String,
    game: Boolean,
    modifier: Modifier,
    onHelp: (SettingDef) -> Unit,
    onTap: (Int) -> Unit,
) {
    // One list state per page (the lines are rebuilt on every change; the state must not be).
    val state = remember(listKey) { LazyListState() }
    LaunchedEffect(row, listKey) { if (lines.isNotEmpty()) state.keepVisible(row) }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 40.dp),
        modifier = modifier
            .simpleDepth(high = true)
            .background(SimplePanelBrush, SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape),
    ) {
        itemsIndexed(lines) { i, l ->
            Column {
                // A faint line between two options (none under a section title).
                if (l !is RHead && i > 0 && lines[i - 1] !is RHead) SimpleRowDivider()
                when (l) {
                    is RHead -> Text(l.title.uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
                    is RSet -> ClassicSettingLine(l, source, env, values, game, focused = i == row, active = active, onHelp = { onHelp(l.def) }) { onTap(i) }
                    is RPage -> ClassicLinkLine(l.title, pageSummary(l, source, env, values), focused = i == row, active = active) { onTap(i) }
                    is RExtra -> ClassicLinkLine(l.title, null, focused = i == row, active = active) { onTap(i) }
                    is RLink -> ClassicLinkLine(l.h.label, null, focused = i == row, active = active) { onTap(i) }
                    is RInline -> Column(Modifier.fillMaxWidth()) { l.items.forEach { it.content() } }
                }
            }
        }
    }
}

/** A sub-page row's summary: the first two values on it. */
private fun pageSummary(p: RPage, source: SettingsSource, env: SettingsEnv, values: (String) -> String): String? =
    p.lines.filterIsInstance<RSet>().take(2).mapNotNull { s ->
        runCatching { labelFor(s.def.control(env, values), source.value(s.def)) }.getOrNull()?.takeIf { it.isNotBlank() }
    }.joinToString(" · ").ifBlank { null }

/** A section's own lists and actions (drives, variables, components ...), driven by the controller's focus. B comes back. */
@Composable
private fun ExtrasPanel(ex: RExtra, anchor: FocusRequester, active: Boolean, modifier: Modifier) {
    Column(
        modifier = modifier
            .simpleDepth(high = true)
            .background(SimplePanelBrush, SimpleShape)
            .border(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else SimpleLine, SimpleShape)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(1.dp).focusRequester(anchor).focusable())
        ex.items.forEach { p -> Box(Modifier.fillMaxWidth()) { p.content() } }
    }
}

@Composable
private fun ClassicSettingLine(
    l: RSet,
    source: SettingsSource,
    env: SettingsEnv,
    values: (String) -> String,
    game: Boolean,
    focused: Boolean,
    active: Boolean,
    onHelp: () -> Unit,
    onTap: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val def = l.def
    val control = def.control(env, values)
    val value = source.value(def)
    val disabled = def.disabled(env, values)
    val editable = source.editable(def) && disabled == null
    val origin = source.origin(def)
    val note = def.note(env, values)
    val shown = runCatching { labelFor(control, value) }.getOrDefault(value)
    val shift by animateFloatAsState(if (focused && active) 4f else 0f, tween(160), label = "simpleClassicShift")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .graphicsLayer { translationX = shift * density }
            .simpleRowFocus(focused, active)
            .simpleTap(onTap)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(L(l.label), color = if (editable) (if (focused) SimpleStrong else SimpleInk.copy(alpha = 0.9f)) else SimpleDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = disabled ?: (if (focused) note else null)
                if (sub != null) Text(L(sub), color = SimpleDim, fontSize = 12.sp, maxLines = if (focused) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            if ((def.help ?: def.hint) != null) HelpDot(focused, onHelp)
        }
        if (game) {
            val tag = when (origin) {
                Origin.SET_HERE -> L("This game")
                Origin.FROM_CONTAINER -> L("Container")
                Origin.FROM_APP -> L("App")
                Origin.CONTAINER_ONLY -> L("Container only")
                else -> null
            }
            if (tag != null) {
                Text(
                    tag,
                    color = if (origin == Origin.SET_HERE) SimpleStrong else SimpleMuted,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(if (origin == Origin.SET_HERE) accent.copy(alpha = 0.3f) else Color(0x14FFFFFF), SimpleShape)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        val stepped = isStepped(control) && editable
        when {
            control == Control.Toggle -> ToggleBox(value == "1", editable)
            else -> {
                if (stepped) Text("‹", color = if (focused) accent else Color.Transparent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    L(shown).ifBlank { "—" },
                    color = if (editable) SimpleStrong else SimpleMuted,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 90.dp, max = 300.dp).padding(horizontal = 8.dp),
                )
                if (stepped) Text("›", color = if (focused) accent else Color.Transparent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                else if (editable) Text("›", color = SimpleMuted, fontSize = 16.sp)
            }
        }
    }
}

/** The "?" beside a setting with an explanation, as the classic editors have: opens it (START does too). */
@Composable
private fun HelpDot(focused: Boolean, onClick: () -> Unit) {
    val tint = if (focused) MaterialTheme.colorScheme.primary else SimpleMuted
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 8.dp)
            .size(22.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .border(1.dp, tint.copy(alpha = 0.7f), androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick),
    ) {
        Text("?", color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** A square on/off switch (hard corners, like the rest of Simple). */
@Composable
private fun ToggleBox(on: Boolean, enabled: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val x by animateFloatAsState(if (on) 1f else 0f, tween(160), label = "simpleToggle")
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 22.dp)
            .background(if (on) accent.copy(alpha = if (enabled) 1f else 0.4f) else Color(0x24FFFFFF), SimpleShape),
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .graphicsLayer { translationX = x * 20.dp.toPx() }
                .size(16.dp)
                .background(if (on) Color.White else Color(0xFFAAB3C2), RoundedSquare),
        )
    }
}

private val RoundedSquare = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)

@Composable
private fun ClassicLinkLine(title: String, summary: String?, focused: Boolean, active: Boolean, onTap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .simpleRowFocus(focused, active)
            .simpleTap(onTap)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(L(title), color = if (focused) SimpleStrong else SimpleInk.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (!summary.isNullOrBlank()) {
            Text(summary, color = SimpleMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp).padding(start = 10.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("›", color = if (focused) accent else SimpleMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
