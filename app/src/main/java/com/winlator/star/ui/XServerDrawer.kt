package com.winlator.star.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.reshade.ReshadeLoadout
import com.winlator.star.reshade.ReshadeManager
import com.winlator.star.ui.components.ColorPicker
import com.winlator.star.ui.screens.MenuItemDivider
import com.winlator.star.ui.screens.outlinedMenuCard
import com.winlator.star.ui.theme.LocalAccentDim
import com.winlator.star.ui.theme.WinlatorTheme
import com.winlator.star.widget.perfhud.parseHudOutline

// Accent colors route to the live MaterialTheme.colorScheme (primary/surface) so the drawer
// follows the user's theme preset / custom accent. The dim accent (low-emphasis fills/borders/
// tracks) routes to LocalAccentDim.current — AMOLED maps that to the exact legacy #002277 so the
// default look stays identical, while other presets/custom accents recolor it.
// Phase 2: the neutral surface/text/divider constants are gone — call sites read
// MaterialTheme.colorScheme directly (surface / onSurface / onSurfaceVariant / outline) so the
// whole drawer (every tab) follows the theme. AMOLED's tokens match the legacy neutrals closely,
// so the default look stays near-identical.
// PureBlack is kept only for spots that need a true literal black that must NOT theme (the
// color-picker knob outline); panel/rail backgrounds route to colorScheme.surface instead.
private val PureBlack = Color(0xFF000000)
private val ToggleTrackOff = Color(0xFF333333)
private val ToggleThumbOff = Color(0xFF666666)

fun setupComposeView(view: ComposeView) {
    view.setContent {
        WinlatorTheme {
            XServerDrawer()
        }
    }
}

@Composable
fun XServerDrawer() {
    val state = XServerDrawerState
    val selectedTab by state.selectedTab.collectAsState()
    val isPaused by state.isPaused.collectAsState()
    val pauseIcon = if (isPaused) R.drawable.icon_play else R.drawable.icon_pause
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(surface)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .width(60.dp)
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(surface, MaterialTheme.colorScheme.surface, surface),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                ),
        ) {
            // The rail scrolls when the screen is too short to fit every icon
            // (so the bottom Exit/Pause buttons stay reachable). When it does
            // fit, heightIn(min) + SpaceEvenly reproduces the distributed look.
            val railMinHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = railMinHeight)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // Top group: section tabs
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TabIconButton(R.drawable.icon_display, selectedTab == TabType.GRAPHICS) {
                            handleTabClick(TabType.GRAPHICS, state)
                        }
                        Spacer(Modifier.height(6.dp))
                        FpsTabButton(isSelected = selectedTab == TabType.HUD) {
                            handleTabClick(TabType.HUD, state)
                        }
                        Spacer(Modifier.height(6.dp))
                        TabIconButton(R.drawable.icon_screen_effect, selectedTab == TabType.RESHADE) {
                            handleTabClick(TabType.RESHADE, state)
                        }
                        Spacer(Modifier.height(6.dp))
                        TabIconButton(R.drawable.icon_input_controls, selectedTab == TabType.CONTROLS) {
                            handleTabClick(TabType.CONTROLS, state)
                        }
                        Spacer(Modifier.height(6.dp))
                        TabIconButton(R.drawable.icon_debug, selectedTab == TabType.ADVANCED) {
                            handleTabClick(TabType.ADVANCED, state)
                        }
                    }

                    // Bottom group: task manager / pause / exit
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(2.dp)
                                .background(accent, RoundedCornerShape(1.dp))
                        )

                        Spacer(Modifier.height(10.dp))

                        TabIconButton(R.drawable.icon_task_manager, selectedTab == TabType.TASK_MANAGER) {
                            state.selectTab(TabType.TASK_MANAGER)
                            state.onTaskManager?.run()
                        }
                        Spacer(Modifier.height(6.dp))
                        TabIconButton(pauseIcon, isSelected = false) {
                            state.onPauseResume?.run(); state.onClose?.run()
                        }
                        Spacer(Modifier.height(6.dp))
                        TabIconButton(R.drawable.icon_exit, isSelected = false) {
                            state.onExit?.run()
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            when (selectedTab) {
                TabType.GRAPHICS -> GraphicsContent(state)
                TabType.HUD -> HudContent(state)
                TabType.RESHADE -> ReshadeContent(state)
                TabType.CONTROLS -> ControlsContent(state)
                TabType.ADVANCED -> AdvancedContent(state)
                TabType.TASK_MANAGER -> TmContent()
            }
        }
    }
}

private fun handleTabClick(tab: TabType, state: XServerDrawerState) {
    state.selectTab(tab)
}

// ───── Modern Tab Button ─────

@Composable
private fun TabIconButton(iconRes: Int, isSelected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = LocalAccentDim.current
    // Selected = filled accent pill (accent → dim), matching the rebuild preview.
    val bgBrush = if (isSelected)
        Brush.verticalGradient(listOf(accent, accentDim))
    else
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))

    val borderColor = if (isSelected) accent.copy(alpha = 0.6f) else Color(0xFF333333)
    val tintColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgBrush, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Canvas(Modifier.size(44.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.25f), Color.Transparent),
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f
                )
            }
        }
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tintColor,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun FpsTabButton(isSelected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = LocalAccentDim.current
    // Selected = filled accent pill (accent → dim), matching the rebuild preview.
    val bgBrush = if (isSelected)
        Brush.verticalGradient(listOf(accent, accentDim))
    else
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))

    val borderColor = if (isSelected) accent.copy(alpha = 0.6f) else Color(0xFF333333)
    val textColor = if (isSelected) Color.White else accent

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgBrush, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Canvas(Modifier.size(44.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.25f), Color.Transparent),
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f
                )
            }
        }
        Text(
            text = "FPS",
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ───── Section Header ─────

@Composable
private fun SectionHeader(title: String) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.1f))),
                    RoundedCornerShape(1.dp)
                )
        )
    }
}

// ───── Modern Toggle Row ─────

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = LocalAccentDim.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier.alpha(0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accent,
                checkedTrackColor = accentDim,
                uncheckedThumbColor = ToggleThumbOff,
                uncheckedTrackColor = ToggleTrackOff,
            )
        )
    }
}

// ───── Modern Slider Row ─────

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    format: (Float) -> String = { "%.0f".format(it) }
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.padding(vertical = 4.dp).then(if (enabled) Modifier else Modifier.alpha(0.4f))) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished ?: {},
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = ToggleTrackOff,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ───── Modern Accent Button ─────

@Composable
private fun AccentButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accentDim = LocalAccentDim.current
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accentDim,
            contentColor = Color.White
        )
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

// ───── Graphics Tab ─────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphicsContent(state: XServerDrawerState) {
    val accent = MaterialTheme.colorScheme.primary
    LaunchedEffect(Unit) {
        XServerDialogState.onInitGraphicsTab?.run()
    }

    // Title on the left, runtime-backend diagnostic chip pinned top-right.
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { SectionHeader("Graphics") }
        RuntimeBackendChip(state)
    }

    // Frame Generation pinned to the top of the Graphics tab.
    FrameGenSection(state)

    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))

    // Fullscreen aspect-ratio mode (#71 Stage 2): a segmented selector (Off/Fit/Stretch/Fill/Integer)
    // that sets the mode live WITHOUT closing the drawer, so the user can compare modes before
    // dismissing it — same box-chip idiom as the Scaling-mode row.
    val fullscreenMode by state.fullscreenMode.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.fullscreen_mode),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(R.drawable.icon_fullscreen),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp)
        )
    }
    Spacer(Modifier.height(6.dp))
    FullscreenModeButtons(selected = fullscreenMode) { state.onSetFullscreenMode?.accept(it) }

    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))

    // Renderer-specific graphics controls. Each host renderer has its own set, so
    // show ONLY the set that applies to the active renderer instead of packing the
    // tab with disabled rows: GL effects on OpenGL, Scaling mode on Vulkan, and
    // nothing on SurfaceFlinger (frames are scanned out directly, bypassing the
    // compositor post-process pass). The two flags are mutually exclusive and fixed
    // for the session (the renderer is chosen at launch).
    val effectsSupported by XServerDialogState.effectsSupported.collectAsState() // OpenGL renderer
    val vulkanSupported  by XServerDialogState.vulkanSupported.collectAsState()  // Vulkan renderer

    // P5: GL Native Rendering (direct scanout) bypasses the entire EffectComposer chain + the GL
    // scaling/upscaler modes, so grey those controls out while native is on (they'd be dead toggles).
    // Reactive — flipping the Native Rendering toggle below recomposes this and updates the grey-out
    // live without reopening the drawer. (Only the GL block is gated; the Vulkan block uses its own
    // reset-on-enable mutual exclusion and stays interactive.)
    val nativeRenderingEnabled by state.nativeRenderingEnabled.collectAsState()
    val glEnabled = !nativeRenderingEnabled
    val glHeaderColor = if (glEnabled) accent else accent.copy(alpha = 0.4f)

    if (effectsSupported) {
        // ---- OpenGL: Scaling mode (real SGSR / FSR1 spatial upscalers; parity with the
        //      Vulkan picker). Modes 0/1/2 drive the base sampler filter; 3/4/5 engage the
        //      EffectComposer low-res upscale stage; 6 = the existing CAS sharpen.
        //      Drawer-only / session-live. ----
        val initGlUpscalerMode by XServerDialogState.glUpscalerMode.collectAsState()
        var glUpscalerMode by remember(initGlUpscalerMode) { mutableIntStateOf(initGlUpscalerMode) }

        Text("Scaling mode", color = glHeaderColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        UpscalerModeButtons(glUpscalerMode, glEnabled) {
            glUpscalerMode = it
            XServerDialogState.onGlUpscalerApply?.invoke(it)
        }
        // "Sharpness" drives SGSR EdgeSharpness / FSR RCAS / CAS / NIS, for the sharpening modes.
        if (glUpscalerMode == 3 || glUpscalerMode == 4 || glUpscalerMode == 5 || glUpscalerMode == 6 || glUpscalerMode == 7) {
            val initGlUpscaleSharpness by XServerDialogState.glUpscaleSharpness.collectAsState()
            var glUpscaleSharpness by remember(initGlUpscaleSharpness) { mutableIntStateOf(initGlUpscaleSharpness) }
            Spacer(Modifier.height(4.dp))
            // Continuous for SGSR/FSR (3/4/5); snapped to 5 stops {0,25,50,75,100} for
            // Sharpen mode (6), where stop 0 = OFF (no CAS pass).
            IntSlider("Sharpness", glUpscaleSharpness, 0..100,
                onValueChange = { glUpscaleSharpness = it },
                onValueChangeFinished = {
                    XServerDialogState.onGlUpscaleSharpnessApply?.invoke(glUpscaleSharpness)
                },
                steps = if (glUpscalerMode == 6) 3 else -1,
                enabled = glEnabled)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))

        // ---- OpenGL: SGSR / HDR + Screen Effects (GL EffectComposer features) ----
        val initSgsrEnabled   by XServerDialogState.sgsrEnabled.collectAsState()
        val initSgsrSharpness by XServerDialogState.sgsrSharpness.collectAsState()
        val initHdrEnabled    by XServerDialogState.hdrEnabled.collectAsState()
        var sgsrEnabled   by remember(initSgsrEnabled)   { mutableStateOf(initSgsrEnabled) }
        var sgsrSharpness by remember(initSgsrSharpness) { mutableIntStateOf(initSgsrSharpness) }
        var hdrEnabled    by remember(initHdrEnabled)    { mutableStateOf(initHdrEnabled) }

        ToggleRow("Sharpen (CAS)", sgsrEnabled, glEnabled) { sgsrEnabled = it; pushSgsrUpdate(sgsrEnabled, sgsrSharpness, hdrEnabled) }
        if (sgsrEnabled) {
            Spacer(Modifier.height(4.dp))
            // Standalone CAS sharpen: always snapped to 5 stops {0,25,50,75,100}, stop 0 = OFF.
            IntSlider("Sharpness", sgsrSharpness, 0..100,
                onValueChange = { sgsrSharpness = it },
                onValueChangeFinished = { pushSgsrUpdate(sgsrEnabled, sgsrSharpness, hdrEnabled) },
                steps = 3, enabled = glEnabled)
        }
        ToggleRow("HDR", hdrEnabled, glEnabled) { hdrEnabled = it; pushSgsrUpdate(sgsrEnabled, sgsrSharpness, hdrEnabled) }

        // Terminal debanding (TPDF dither) — kills 8-bit gradient banding. Drawer-only / session-live.
        DebandControls(glEnabled)

        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))

        Text("Screen Effects", color = glHeaderColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))

        val seBrightness by XServerDialogState.seBrightness.collectAsState()
        val seContrast by XServerDialogState.seContrast.collectAsState()
        val seGamma by XServerDialogState.seGamma.collectAsState()
        val seFxaa by XServerDialogState.seFxaa.collectAsState()
        val seCrt by XServerDialogState.seCrt.collectAsState()
        val seToon by XServerDialogState.seToon.collectAsState()
        val seNtsc by XServerDialogState.seNtsc.collectAsState()
        var localBrightness by remember(seBrightness) { mutableFloatStateOf(seBrightness) }
        var localContrast by remember(seContrast) { mutableFloatStateOf(seContrast) }
        var localGamma by remember(seGamma) { mutableFloatStateOf(seGamma) }
        var localFxaa by remember(seFxaa) { mutableStateOf(seFxaa) }
        var localCrt by remember(seCrt) { mutableStateOf(seCrt) }
        var localToon by remember(seToon) { mutableStateOf(seToon) }
        var localNtsc by remember(seNtsc) { mutableStateOf(seNtsc) }

        fun applySe() {
            XServerDialogState.onScreenEffectsApply?.invoke(localBrightness, localContrast, localGamma, localFxaa, localCrt, localToon, localNtsc, 0)
        }

        LabeledSlider("Brightness", localBrightness, -100f..100f, { localBrightness = it; applySe() }, enabled = glEnabled)
        LabeledSlider("Contrast", localContrast, -100f..100f, { localContrast = it; applySe() }, enabled = glEnabled)
        LabeledSlider("Gamma", localGamma, 0.5f..3.0f, { localGamma = it; applySe() }, enabled = glEnabled, format = { "%.2f".format(it) })

        SeShaderToggle("FXAA", localFxaa, glEnabled) { localFxaa = it; applySe() }
        SeShaderToggle("CRT", localCrt, glEnabled) { localCrt = it; applySe() }
        SeShaderToggle("Toon", localToon, glEnabled) { localToon = it; applySe() }
        SeShaderToggle("NTSC", localNtsc, glEnabled) { localNtsc = it; applySe() }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
    }

    if (vulkanSupported) {
        // ---- Vulkan: Scaling mode (spatial upscaler) ----
        // Single source of truth for scaling/filtering on the Vulkan renderer (modes
        // 1/2 drive the base sampler filter natively). Keyed on the live value so the
        // picker reflects the seeded/launch config. Drawer-only / session-live.
        val initUpscalerMode by XServerDialogState.upscalerMode.collectAsState()
        var upscalerMode by remember(initUpscalerMode) { mutableIntStateOf(initUpscalerMode) }

        Text("Scaling mode", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        UpscalerModeButtons(upscalerMode, true) {
            upscalerMode = it
            XServerDialogState.onUpscalerApply?.invoke(it)
        }

        // "Sharpness" controls the REAL upscaler sharpness (RCAS stops / SGSR EdgeSharpness /
        // NIS sharpness) and only applies to the sharpening scaling modes (SGSR/FSR/FSR-Fit/Sharpen/NIS).
        if (upscalerMode == 3 || upscalerMode == 4 || upscalerMode == 5 || upscalerMode == 6 || upscalerMode == 7) {
            val initUpscaleSharpness by XServerDialogState.upscaleSharpness.collectAsState()
            var upscaleSharpness by remember(initUpscaleSharpness) { mutableIntStateOf(initUpscaleSharpness) }
            Spacer(Modifier.height(4.dp))
            IntSlider("Sharpness", upscaleSharpness, 0..100, { upscaleSharpness = it }, {
                XServerDialogState.onUpscaleSharpnessApply?.invoke(upscaleSharpness)
            })
        }

        Spacer(Modifier.height(4.dp))

        // ---- Composable post effects (layer on top of any scaling mode) ----
        val initCasEnabled   by XServerDialogState.casEnabled.collectAsState()
        val initCasSharpness by XServerDialogState.casSharpness.collectAsState()
        val initHdrVkEnabled by XServerDialogState.hdrVkEnabled.collectAsState()
        var casEnabled   by remember(initCasEnabled)   { mutableStateOf(initCasEnabled) }
        var casSharpness by remember(initCasSharpness) { mutableIntStateOf(initCasSharpness) }
        var hdrVkEnabled by remember(initHdrVkEnabled) { mutableStateOf(initHdrVkEnabled) }

        ToggleRow("CAS", casEnabled, true) {
            casEnabled = it
            XServerDialogState.onCasApply?.invoke(casEnabled, casSharpness)
        }
        if (casEnabled) {
            Spacer(Modifier.height(4.dp))
            IntSlider("CAS Sharpness", casSharpness, 0..100, { casSharpness = it }, {
                XServerDialogState.onCasApply?.invoke(casEnabled, casSharpness)
            })
        }
        ToggleRow("HDR", hdrVkEnabled, true) {
            hdrVkEnabled = it
            XServerDialogState.onHdrApply?.invoke(hdrVkEnabled)
        }

        // Terminal debanding (TPDF dither) — kills 8-bit gradient banding. Drawer-only / session-live.
        DebandControls()

        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))

        // ---- Screen Effects (GL EffectComposer parity, ported to the Vulkan post
        //      chain). Color grade is always-applied via the sliders (neutral = no-op);
        //      FXAA/Toon/CRT/NTSC are toggles. Drawer-only / session-live. ----
        Text("Screen Effects", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))

        val initVkBrightness by XServerDialogState.vkBrightness.collectAsState()
        val initVkContrast   by XServerDialogState.vkContrast.collectAsState()
        val initVkGamma      by XServerDialogState.vkGamma.collectAsState()
        val initVkFxaa       by XServerDialogState.vkFxaa.collectAsState()
        val initVkToon       by XServerDialogState.vkToon.collectAsState()
        val initVkCrt        by XServerDialogState.vkCrt.collectAsState()
        val initVkNtsc       by XServerDialogState.vkNtsc.collectAsState()
        var vkBrightness by remember(initVkBrightness) { mutableFloatStateOf(initVkBrightness) }
        var vkContrast   by remember(initVkContrast)   { mutableFloatStateOf(initVkContrast) }
        var vkGamma      by remember(initVkGamma)      { mutableFloatStateOf(initVkGamma) }
        var vkFxaa       by remember(initVkFxaa)       { mutableStateOf(initVkFxaa) }
        var vkToon       by remember(initVkToon)       { mutableStateOf(initVkToon) }
        var vkCrt        by remember(initVkCrt)        { mutableStateOf(initVkCrt) }
        var vkNtsc       by remember(initVkNtsc)       { mutableStateOf(initVkNtsc) }

        fun applyVkSe() {
            XServerDialogState.onVulkanScreenEffectsApply?.invoke(
                vkBrightness, vkContrast, vkGamma, vkFxaa, vkToon, vkCrt, vkNtsc)
        }

        LabeledSlider("Brightness", vkBrightness, -100f..100f, { vkBrightness = it; applyVkSe() }, enabled = true)
        LabeledSlider("Contrast", vkContrast, -100f..100f, { vkContrast = it; applyVkSe() }, enabled = true)
        LabeledSlider("Gamma", vkGamma, 0.5f..3.0f, { vkGamma = it; applyVkSe() }, enabled = true, format = { "%.2f".format(it) })

        // Four independent shader flags with identical wiring — one row of chips instead of four
        // switch rows. Same applyVkSe() round-trip as before.
        ToggleChipGrid(
            listOf(
                ToggleChipItem("FXAA", vkFxaa) { vkFxaa = it; applyVkSe() },
                ToggleChipItem("Toon", vkToon) { vkToon = it; applyVkSe() },
                ToggleChipItem("CRT", vkCrt) { vkCrt = it; applyVkSe() },
                ToggleChipItem("NTSC", vkNtsc) { vkNtsc = it; applyVkSe() },
            ),
            perRow = 4
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
    }

    if (!effectsSupported && !vulkanSupported) {
        // ---- SurfaceFlinger: direct scanout bypasses the compositor, so no
        //      post-process / scaling controls apply. ----
        Text(
            "No graphics enhancements are available with the SurfaceFlinger renderer.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
    }

    // nativeRenderingEnabled is collected once at the top of GraphicsContent (drives the GL grey-out).
    // Native Rendering is only offered on renderers that support direct scanout (Vulkan); it's hidden
    // on the OpenGL renderer, where the bespoke GL scanout path is disabled for now.
    val nativeRenderingSupported by state.nativeRenderingSupported.collectAsState()
    if (nativeRenderingSupported)
        ToggleRow("Native Rendering", nativeRenderingEnabled) { state.onNativeRenderingToggle?.run() }

}

// ───── Runtime-backend diagnostic chip (Graphics tab header) ─────
// Read-only status: arch · translator, plus the FEX unixlib mode. unixlib (native .so loaded) =
// accent/green; DLL (self-contained FEX DLL path) = muted. Box64/x86-64 shows no unixlib segment.
// It is a status readout, never a "faster" flag. Hidden until the activity seeds arch+translator.
@Composable
private fun RuntimeBackendChip(state: XServerDrawerState) {
    val backend by state.runtimeBackend.collectAsState()
    if (!backend.isValid) return

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            "${backend.arch} · ${backend.translator}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        if (backend.showsFexMode) {
            val (label, color) = when (backend.fexMode) {
                FexMode.UNIXLIB -> "unixlib" to Color(0xFF4CAF50) // native .so loaded
                FexMode.DLL     -> "DLL"     to muted             // self-contained DLL path
                FexMode.NA      -> "N/A"     to muted             // maps not resolved yet
            }
            Text(" · ", color = muted, fontSize = 10.sp)
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ───── Frame Generation section (pinned to top of Graphics tab) ─────
// On/off is per-container; multiplier & flow scale are tuned live here and hot-reload
// via conf.toml. Multiplier is a segmented button row (Off / 2× / 3× / 4×); the Flow
// Scale slider collapses while Off and expands when a multiplier is selected.
@Composable
private fun FrameGenSection(state: XServerDrawerState) {
    val accent = MaterialTheme.colorScheme.primary
    val frameGenEnabled by state.frameGenEnabled.collectAsState()
    val initFgMult by state.frameGenMultiplier.collectAsState()
    val initFgFlow by state.frameGenFlowScale.collectAsState()
    val initFgModel by state.frameGenModel.collectAsState()
    val engine by state.frameGenEngine.collectAsState()
    val layerActive by state.bionicFgActive.collectAsState()
    val initLsfgPerf by state.lsfgPerformanceMode.collectAsState()

    // Title on the left, engine badge on the right (green dot = engine actually running this
    // session). Replaces the old standalone "Frame Generation (AI)" header so the engine isn't
    // labeled twice. Badge shows bionic-fg / lsfg-vk depending on the container's selection.
    val engineLabel = when (engine) {
        "lsfg"   -> "lsfg-vk"
        "bionic" -> "bionic-fg"
        else     -> "Off"
    }
    // Green dot = engine actually multiplying frames right now. Frame gen starts at multiplier 0
    // (Off) every launch even when the container has an engine selected, so gate on initFgMult too
    // — otherwise the dot would show green while FG is idle. Tracks live as the user toggles Off/2×/…
    val isRunning = layerActive && engine != "off" && initFgMult > 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Frame Generation", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            )
            Spacer(Modifier.width(5.dp))
            Text(
                engineLabel,
                color = if (isRunning) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    if (frameGenEnabled) {
        var fgMult by remember(initFgMult) { mutableIntStateOf(initFgMult) }
        var fgFlow by remember(initFgFlow) { mutableFloatStateOf(initFgFlow) }
        var fgModel by remember(initFgModel) { mutableIntStateOf(initFgModel) }
        fun applyFg() {
            state.setFrameGenMultiplier(fgMult)
            state.setFrameGenFlowScale(fgFlow)
            state.setFrameGenModel(fgModel)
            state.onBionicFgConfigChange?.run()
        }

        FgMultiplierButtons(fgMult) { fgMult = it; applyFg() }

        // Interpolation model, bionic-fg only. The layer rebuilds its framegen context when the
        // model changes (same path as a multiplier change), so this switches live. Hidden while
        // frame gen is Off, where it would have nothing to act on.
        AnimatedVisibility(
            visible = engine == "bionic" && fgMult > 0,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Model",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                FgModelButtons(fgModel) { fgModel = it; applyFg() }
            }
        }

        // Flow Scale only matters with frame gen actually on -> collapse it while Off.
        AnimatedVisibility(
            visible = fgMult > 0,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    "Flow Scale", fgFlow, 0.2f..1.0f,
                    { fgFlow = it }, { applyFg() },
                    format = { "%.2f".format(it) }
                )
                Text(
                    "Higher flow scale = smoother motion estimate, more GPU cost.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }
        }

        // lsfg-vk only: performance_mode (bionic-fg has no such setting). Toggling rewrites conf.toml
        // via the same applyFg -> onBionicFgConfigChange path (mtime bump -> layer re-reads live) and
        // persists to the container there.
        if (engine == "lsfg") {
            var lsfgPerf by remember(initLsfgPerf) { mutableStateOf(initLsfgPerf) }
            Spacer(Modifier.height(8.dp))
            ToggleRow("Performance mode", lsfgPerf) {
                lsfgPerf = it
                state.setLsfgPerformanceMode(it)
                applyFg()
            }
            Text(
                "Lower quality for higher FPS — helps on low-end devices.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    } else {
        Text(
            "Enable Frame Generation in this container's settings to tune it here.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}

// Off / 2× / 3× / 4× segmented button row. mult values 0/2/3/4; selected = filled accent.
@Composable
private fun FgModelButtons(selected: Int, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = LocalAccentDim.current
    // 0 is the long-standing default chain; 1-3 are newer engines, not yet device-proven.
    val options = listOf(0 to "Default", 1 to "Traced", 2 to "V2", 3 to "FSR3")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (model, label) ->
            val isSel = selected == model
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSel) accent else Color.Black)
                    .border(
                        width = 1.dp,
                        color = if (isSel) accent else accentDim,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(model) }
                    .padding(vertical = 9.dp)
            ) {
                Text(
                    label,
                    color = if (isSel) Color.Black else accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun FgMultiplierButtons(selected: Int, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = LocalAccentDim.current
    val options = listOf(0 to "Off", 2 to "2×", 3 to "3×", 4 to "4×")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (mult, label) ->
            val isSel = selected == mult
            // Unselected: black fill, dark-blue outline. Selected: solid blue fill, black text.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSel) accent else Color.Black)
                    .border(
                        width = 1.dp,
                        color = if (isSel) accent else accentDim,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(mult) }
                    .padding(vertical = 9.dp)
            ) {
                Text(
                    label,
                    color = if (isSel) Color.Black else accent,
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

private fun pushSgsrUpdate(enabled: Boolean, sharpness: Int, hdr: Boolean) {
    XServerDialogState.onSgsrUpdate?.invoke(enabled, sharpness, hdr)
}

// ───── ReShade tab ─────
// First-class drawer tab (peer to Graphics/FPS). Header + the ReShade section body.
@Composable
private fun ReshadeContent(state: XServerDrawerState) {
    SectionHeader("ReShade")
    ReshadeSection()
}

// Tier 1 multi-effect LOADOUT — the effects picked pre-launch, switchable LIVE here. A master
// on/off (whole chain) + a Solo/Stack mode switch + one row per effect: an activation control (radio
// in solo, checkbox in stack) and an expander revealing that effect's typed controls (BOOL -> toggle,
// COMBO/RADIO/LIST -> dropdown, COLOR (floatN) -> HSV picker, FLOAT/INT -> slider) + a per-effect
// Reset. In solo mode, activating one deactivates the others. Shows a placeholder on non-DXVK/VKD3D
// games or with an empty loadout. Effect SELECTION is pre-launch (shortcut/container editor); this
// toggles + tunes the loaded set. Every change rides the single onReshadeApply seam (-> applyReshadeLive:
// conf rewrite the patched libvkbasalt mtime-watch picks up live, and persists to Container/shortcut).
@Composable
private fun ReshadeSection() {
    val accent = MaterialTheme.colorScheme.primary
    val supported by XServerDialogState.reshadeSupported.collectAsState()
    // Seed ONCE from the flows (the launch/last-applied state). Read via .value (not collectAsState)
    // so writing the snapshot back on each apply — for reopen consistency — doesn't re-key the live
    // edit state and collapse the open row. The section is recomposed fresh whenever the tab reopens.
    val seed = remember { XServerDialogState.reshadeLoadout.value }

    if (!supported || seed.isEmpty()) {
        Text(
            "No ReShade effects selected. Add one or more in this game's settings (or the container's) to switch and tune them here.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
        )
        return
    }

    var master by remember { mutableStateOf(XServerDialogState.reshadeMasterEnabled.value) }
    var mode by remember { mutableStateOf(XServerDialogState.reshadeMode.value) }
    val enabledState = remember { mutableStateMapOf<String, Boolean>().apply { seed.forEach { put(it.name, it.enabled) } } }
    // One SnapshotStateMap of live values per effect (keyed by effect name).
    val valueState = remember { seed.associate { it.name to mutableStateMapOf<String, Float>().apply { putAll(it.values) } } }
    // Bumped on any per-effect Reset so COLOR pickers (internal HSV state) re-seed from `values`.
    var resetNonce by remember { mutableIntStateOf(0) }
    val colorSeed = remember(resetNonce) { Any() }
    // Which effect row is expanded to reveal its params (default: the first).
    var expanded by remember { mutableStateOf(seed.firstOrNull()?.name) }

    fun snapshot(): List<ReshadeLoadoutItem> = seed.map { item ->
        item.copy(
            enabled = enabledState[item.name] ?: item.enabled,
            values = valueState[item.name]?.toMap() ?: item.values
        )
    }
    fun apply() {
        val snap = snapshot()
        XServerDialogState.setReshadeMasterEnabled(master)
        XServerDialogState.setReshadeMode(mode)
        XServerDialogState.setReshadeLoadout(snap)
        XServerDialogState.onReshadeApply?.invoke(master, mode, snap)
    }
    fun setEnabled(name: String, on: Boolean) {
        if (mode == ReshadeLoadout.MODE_SOLO && on) {
            // Solo: activating one deactivates the rest.
            enabledState.keys.toList().forEach { enabledState[it] = (it == name) }
        } else {
            enabledState[name] = on
        }
        apply()
    }
    fun setMode(newMode: String) {
        mode = newMode
        // Switching to solo: keep only the first enabled effect active.
        if (newMode == ReshadeLoadout.MODE_SOLO) {
            var seen = false
            seed.forEach { item ->
                val on = enabledState[item.name] ?: false
                if (on && !seen) seen = true else enabledState[item.name] = false
            }
        }
        apply()
    }

    // "Live preview" — persisted global toggle. ON = changes apply live while the game runs. OFF
    // (default) = freeze-frame + pulse preview (first change SIGSTOPs; each later change briefly
    // resumes 1–2 frames to reveal it, then re-freezes). The activity owns the flag + the freeze/
    // pulse; this just reports it. Independent of `master` so it can be set before enabling ReShade.
    var livePreview by remember { mutableStateOf(XServerDialogState.reshadeLivePreview.value) }

    ToggleRow("ReShade", master, true) { master = it; apply() }

    ToggleRow("Live preview", livePreview, true) {
        livePreview = it
        XServerDialogState.setReshadeLivePreview(it)
        XServerDialogState.onReshadeLivePreviewChange?.invoke(it)
    }
    Text(
        if (livePreview) "Changes apply live; the game keeps running."
        else "Game freezes while tuning; each change pulses briefly to preview, then re-freezes.",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
    )

    if (master) {
        ReshadeModeSelector(mode) { setMode(it) }
        seed.forEach { item ->
            val itemEnabled = enabledState[item.name] ?: item.enabled
            val isOpen = expanded == item.name
            // Activation control + label + expander.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (mode == ReshadeLoadout.MODE_SOLO) {
                    RadioButton(
                        selected = itemEnabled,
                        onClick = { setEnabled(item.name, true) },
                        colors = RadioButtonDefaults.colors(selectedColor = accent)
                    )
                } else {
                    Checkbox(
                        checked = itemEnabled,
                        onCheckedChange = { setEnabled(item.name, it) },
                        colors = CheckboxDefaults.colors(checkedColor = accent)
                    )
                }
                Text(
                    item.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = if (itemEnabled) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f).clickable { expanded = if (isOpen) null else item.name }
                )
                IconButton(onClick = { expanded = if (isOpen) null else item.name }) {
                    Icon(
                        if (isOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isOpen) {
                val values = valueState[item.name] ?: mutableStateMapOf()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        values.clear()
                        item.params.forEach { p -> ReshadeManager.seedValues(p, null, values) }
                        resetNonce++
                        apply()
                    }, enabled = item.params.isNotEmpty()) {
                        Text("Reset", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (item.params.isEmpty()) {
                    Text(
                        "No tunable parameters.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                } else {
                    ReshadeEffectParams(item.params, values, colorSeed) { apply() }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

// Solo/Stack mode switch — two pills. Solo = one effect active (A/B); Stack = layered subset.
@Composable
private fun ReshadeModeSelector(mode: String, onChange: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(ReshadeLoadout.MODE_SOLO to "Solo", ReshadeLoadout.MODE_STACK to "Stack").forEach { (value, label) ->
            val selected = mode == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface)
                    .border(1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable { onChange(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
    Text(
        if (mode == ReshadeLoadout.MODE_SOLO) "One effect at a time (A/B compare)."
        else "Layer any subset of effects.",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

// The typed controls for ONE effect (reused by each expanded loadout row). BOOL -> toggle,
// COMBO/RADIO/LIST -> dropdown, COLOR (floatN) -> HSV picker, FLOAT/INT (slider|drag) -> slider.
@Composable
private fun ReshadeEffectParams(
    params: List<ReshadeManager.ReshadeParam>,
    values: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Float>,
    colorSeed: Any,
    onApply: () -> Unit,
) {
    params.forEach { p ->
        when (p.type) {
            ReshadeManager.ParamType.BOOL -> {
                val v = values[p.name] ?: p.defaultValue
                Spacer(Modifier.height(4.dp))
                ToggleRow(p.label, v >= 0.5f, true) {
                    values[p.name] = if (it) 1f else 0f; onApply()
                }
            }
            ReshadeManager.ParamType.COMBO -> {
                val idx = (values[p.name] ?: p.defaultValue).roundToInt()
                ReshadeDropdown(p.label, p.options ?: emptyList(), idx) { sel ->
                    values[p.name] = sel.toFloat(); onApply()
                }
            }
            ReshadeManager.ParamType.COLOR -> {
                ReshadeColorControl(
                    label = p.label,
                    components = p.components,
                    seedKey = colorSeed,
                    component = { c -> values["${p.name}_$c"] ?: p.componentDefaults?.getOrNull(c) ?: 0f },
                    onChange = { comps ->
                        comps.forEachIndexed { c, value -> values["${p.name}_$c"] = value }
                        onApply()
                    }
                )
            }
            else -> {
                val v = (values[p.name] ?: p.defaultValue).coerceIn(p.min, p.max)
                Spacer(Modifier.height(4.dp))
                LabeledSlider(
                    p.label, v, p.min..p.max,
                    { values[p.name] = it },
                    { onApply() },
                    format = {
                        if (p.type == ReshadeManager.ParamType.INT) it.toInt().toString()
                        else "%.2f".format(it)
                    }
                )
            }
        }
    }
}

// COMBO/RADIO/LIST dropdown — shows the ui_items labels; reports the selected index.
@Composable
private fun ReshadeDropdown(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    options.getOrElse(selected) { options.firstOrNull() ?: "" },
                    color = accent, fontWeight = FontWeight.Medium, fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.outlinedMenuCard()
            ) {
                options.forEachIndexed { i, opt ->
                    if (i > 0) MenuItemDivider()
                    DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(i); expanded = false })
                }
            }
        }
    }
}

// COLOR (float3/float4) — full HSV color picker: preview swatch + hue/saturation/brightness (and
// alpha for float4) gradient sliders. Emits the RGB(A) components as 0..1 floats. Internal HSV state
// is keyed on [seedKey] so a Reset (or fresh seed) snaps the widget back to the resolved values.
@Composable
private fun ReshadeColorControl(
    label: String,
    components: Int,
    seedKey: Any,
    component: (Int) -> Float,
    onChange: (FloatArray) -> Unit
) {
    val initHsv = remember(seedKey) {
        val r = (component(0).coerceIn(0f, 1f) * 255f).roundToInt()
        val g = (component(1).coerceIn(0f, 1f) * 255f).roundToInt()
        val b = (component(2).coerceIn(0f, 1f) * 255f).roundToInt()
        FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.rgb(r, g, b), it) }
    }
    var hue by remember(seedKey) { mutableFloatStateOf(initHsv[0]) }
    var sat by remember(seedKey) { mutableFloatStateOf(initHsv[1]) }
    var valv by remember(seedKey) { mutableFloatStateOf(initHsv[2]) }
    var alpha by remember(seedKey) { mutableFloatStateOf(if (components >= 4) component(3).coerceIn(0f, 1f) else 1f) }

    fun rgbInt() = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, valv))
    fun emit() {
        val c = rgbInt()
        val out = FloatArray(components)
        if (components >= 1) out[0] = android.graphics.Color.red(c) / 255f
        if (components >= 2) out[1] = android.graphics.Color.green(c) / 255f
        if (components >= 3) out[2] = android.graphics.Color.blue(c) / 255f
        if (components >= 4) out[3] = alpha
        onChange(out)
    }

    // Collapsed by default — a deep shader (e.g. Technicolor) has several color params, and
    // expanding every Hue/Sat/Brightness set at once is a wall of rainbow sliders. The header row
    // (label + swatch + chevron) is tappable to reveal this param's sliders.
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(rgbInt()))
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(6.dp))
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        if (expanded) {
            GradientSlider(
                "Hue", hue, 0f..360f,
                Brush.horizontalGradient((0..12).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f))) }),
                { hue = it; emit() }
            )
            GradientSlider(
                "Saturation", sat, 0f..1f,
                Brush.horizontalGradient(listOf(
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0f, valv))),
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, valv)))
                )),
                { sat = it; emit() }
            )
            GradientSlider(
                "Brightness", valv, 0f..1f,
                Brush.horizontalGradient(listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f))))),
                { valv = it; emit() }
            )
            if (components >= 4) {
                GradientSlider(
                    "Alpha", alpha, 0f..1f,
                    Brush.horizontalGradient(listOf(Color.Black, Color(rgbInt()))),
                    { alpha = it; emit() }
                )
            }
        }
    }
}

// Tappable/draggable gradient track slider (drawer dark idiom) used by the color picker.
@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    track: Brush,
    onValueChange: (Float) -> Unit
) {
    var width by remember { mutableIntStateOf(0) }
    fun pick(x: Float) {
        if (width > 0) {
            val frac = (x / width).coerceIn(0f, 1f)
            onValueChange(range.start + frac * (range.endInclusive - range.start))
        }
    }
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(track)
                .border(1.dp, ToggleTrackOff, RoundedCornerShape(11.dp))
                .onSizeChanged { width = it.width }
                .pointerInput(range) { detectTapGestures { pick(it.x) } }
                .pointerInput(range) { detectHorizontalDragGestures { ch, _ -> pick(ch.position.x) } }
        ) {
            val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxSize().padding(horizontal = 3.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth(frac)) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.dp, PureBlack, CircleShape)
                    )
                }
            }
        }
    }
}

// Scaling-mode picker: 7 options (0=None 1=Linear 2=Nearest 3=SGSR 4=FSR 5=FSR Fit
// 6=Sharpen) laid out as rows of four segmented chips (same box-chip idiom as
// FgMultiplierButtons). Grayed out when the active host renderer is not Vulkan.
// Terminal debanding controls (toggle + optional dither-strength slider), shared by the
// GL and Vulkan graphics blocks. Reads/writes the single _debandEnabled/_debandStrength
// state and fires onDebandApply; only one renderer block is shown per session, so the
// shared state never conflicts. strength 0..200 (CPU maps /100 to LSBs, default 100 = 1 LSB).
@Composable
private fun DebandControls(enabled: Boolean = true) {
    val initDebandEnabled  by XServerDialogState.debandEnabled.collectAsState()
    val initDebandStrength by XServerDialogState.debandStrength.collectAsState()
    var debandEnabled  by remember(initDebandEnabled)  { mutableStateOf(initDebandEnabled) }
    var debandStrength by remember(initDebandStrength) { mutableIntStateOf(initDebandStrength) }
    ToggleRow("Debanding", debandEnabled, enabled) {
        debandEnabled = it
        XServerDialogState.onDebandApply?.invoke(debandEnabled, debandStrength)
    }
    if (debandEnabled) {
        Spacer(Modifier.height(4.dp))
        IntSlider("Dither strength", debandStrength, 0..200,
            onValueChange = { debandStrength = it },
            onValueChangeFinished = {
                XServerDialogState.onDebandApply?.invoke(debandEnabled, debandStrength)
            },
            enabled = enabled)
    }
}

// Fullscreen aspect-ratio selector (#71 Stage 2): 5 mode chips laid out as rows (3 + 2), same
// box-chip idiom as UpscalerModeButtons. Selecting a mode applies it live and does NOT close the
// drawer, so the user can flip between modes and settle on one before dismissing.
@Composable
private fun FullscreenModeButtons(selected: Int, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = LocalAccentDim.current
    val options = listOf(
        0 to stringResource(R.string.fullscreen_mode_off_short),
        1 to stringResource(R.string.fullscreen_mode_fit_short),
        2 to stringResource(R.string.fullscreen_mode_stretch_short),
        3 to stringResource(R.string.fullscreen_mode_fill_short),
        4 to stringResource(R.string.fullscreen_mode_integer_short)
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { (mode, label) ->
                    val isSel = selected == mode
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) accent else Color.Black)
                            .border(
                                width = 1.dp,
                                color = if (isSel) accent else accentDim,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelect(mode) }
                            .padding(vertical = 9.dp)
                    ) {
                        Text(
                            label,
                            color = if (isSel) Color.Black else accent,
                            fontSize = 12.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
                // Pad the short (2-chip) row so its buttons keep the same width as the 3-chip row.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun UpscalerModeButtons(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = LocalAccentDim.current
    val options = listOf(
        0 to "None", 1 to "Linear", 2 to "Nearest",
        3 to "SGSR", 4 to "FSR", 5 to "FSR (Fit)", 6 to "Sharpen", 7 to "NIS"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { (mode, label) ->
                    val isSel = selected == mode
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel && enabled) accent else Color.Black)
                            .border(
                                width = 1.dp,
                                color = if (isSel && enabled) accent else accentDim,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = enabled) { onSelect(mode) }
                            .padding(vertical = 9.dp)
                    ) {
                        Text(
                            label,
                            color = when {
                                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                isSel    -> Color.Black
                                else     -> accent
                            },
                            fontSize = 12.sp,
                            fontWeight = if (isSel && enabled) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

// Per-container rumble target picker (Off/Controller/Device/Both) — same segmented-chip style as
// UpscalerModeButtons above, just a fixed 4-wide row instead of chunked(4). "Device" = the phone's
// own vibrator (Container.VIBRATION_MODE_DEVICE); "Both" drives the physical controller AND the
// phone together (Container.VIBRATION_MODE_BOTH).
@Composable
private fun VibrationModeButtons(selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = LocalAccentDim.current
    val options = listOf(0 to "Off", 1 to "Controller", 2 to "Device", 3 to "Both")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (mode, label) ->
            val isSel = selected == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSel && enabled) accent else Color.Black)
                    .border(
                        width = 1.dp,
                        color = if (isSel && enabled) accent else accentDim,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = enabled) { onSelect(mode) }
                    .padding(vertical = 9.dp)
            ) {
                Text(
                    label,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        isSel    -> Color.Black
                        else     -> accent
                    },
                    fontSize = 11.sp,
                    fontWeight = if (isSel && enabled) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

// Multi-select cousin of FullscreenModeButtons: each item toggles independently, but shares the exact
// box style (accent fill + bold black text ON; black bg + accentDim 1dp border + accent medium text OFF)
// and the aligned equal-width grid (weight(1f), short rows padded with Spacer so widths stay equal).
// Callers build the list of currently-VISIBLE chips FIRST, then this chunks per row — so per-style
// gating never leaves holes or misaligns the grid.
// enabled=false greys the WHOLE grid and swallows taps, the same way VibrationModeButtons does it —
// for rows that stay on screen because they still explain something, but can't be acted on yet.
// disabledIndices greys INDIVIDUAL chips (indices into the flat items list) for the case where one
// option is unreachable in the current configuration but the rest of the row is still live — greyed
// rather than dropped, so the row doesn't reflow and the option is visibly still a thing that exists.
@Composable
private fun ModeChipGrid(items: List<Triple<String, Boolean, () -> Unit>>, perRow: Int,
                         enabled: Boolean = true, disabledIndices: Set<Int> = emptySet()) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = LocalAccentDim.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.withIndex().chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { (index, item) ->
                    val (label, isOn, onTap) = item
                    val chipEnabled = enabled && index !in disabledIndices
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isOn && chipEnabled) accent else Color.Black)
                            .border(
                                width = 1.dp,
                                color = if (isOn && chipEnabled) accent else accentDim,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = chipEnabled) { onTap() }
                            .padding(vertical = 9.dp)
                    ) {
                        Text(
                            label,
                            color = when {
                                !chipEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                isOn         -> Color.Black
                                else         -> accent
                            },
                            fontSize = 12.sp,
                            fontWeight = if (isOn && chipEnabled) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
                // Pad short final rows so every chip keeps the same width (grid stays aligned).
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// One on/off chip in a ToggleChipGrid. Same tuple ToggleRow takes (label + checked + enabled +
// callback), just laid out as a chip instead of a full-width switch row.
private data class ToggleChipItem(
    val label: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val onToggle: (Boolean) -> Unit
)

// Compact stand-in for a run of ToggleRows: same chip language as ModeChipGrid above (accent fill +
// bold black text ON; black bg + accentDim 1dp border + accent medium text OFF, equal widths, short
// rows padded with Spacer), but every chip toggles independently. Packing adjacent toggles 2–4 per
// row is where the vertical space comes back — a Switch row costs ~4x the height of a chip.
// Disabled chips keep ToggleRow's alpha-0.4 grey-out and swallow taps.
@Composable
private fun ToggleChipGrid(items: List<ToggleChipItem>, perRow: Int = 3) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = LocalAccentDim.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(perRow).forEach { row ->
            // IntrinsicSize.Min + fillMaxHeight keeps a row level when one label wraps to two lines.
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Split the padding for a short final row across both sides so it sits CENTRED,
                // and every chip in the grid keeps the identical width (no odd-sized leftovers).
                val missing = perRow - row.size
                val leading = missing / 2
                repeat(leading) { Spacer(Modifier.weight(1f)) }
                row.forEach { item ->
                    val isOn = item.checked && item.enabled
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isOn) accent else Color.Black)
                            .border(
                                width = 1.dp,
                                color = if (isOn) accent else accentDim,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .then(
                                if (item.enabled) Modifier.clickable { item.onToggle(!item.checked) }
                                else Modifier.alpha(0.4f)
                            )
                            .padding(horizontal = 6.dp, vertical = 9.dp)
                    ) {
                        Text(
                            item.label,
                            color = if (isOn) Color.Black else accent,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
                repeat(missing - leading) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// Manual refresh-rate slider — snaps to [Off] + each supported panel rate (which may be unevenly
// spaced, e.g. 60/90/120/144). Off (0) = no manual lock. The label tracks the snapped value live
// while dragging; the actual panel rate is applied on release so we don't flash through modes mid-drag.
// Greyed when disabled (Auto on or display not VRR-capable).
@Composable
private fun RefreshRateSlider(rates: List<Int>, selected: Int, enabled: Boolean, autoRate: Int, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val stops = remember(rates) { listOf(0) + rates }
    var idx by remember(selected, stops) { mutableStateOf(stops.indexOf(selected).coerceAtLeast(0)) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    // When the slider is disabled by Auto, the manual selection is meaningless — show the live actual
    // display rate instead, kept in normal blue so it reads as a real value, not a greyed leftover.
    val showAuto = !enabled && autoRate > 0
    val rightText = when {
        showAuto -> "$autoRate Hz"
        stops[idx] == 0 -> "Off"
        else -> "${stops[idx]} Hz"
    }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Rate", style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else dim)
            Text(
                rightText,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled || showAuto) accent else dim,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = idx.toFloat(),
            onValueChange = { idx = it.roundToInt().coerceIn(stops.indices) },
            onValueChangeFinished = { onSelect(stops[idx]) },
            valueRange = 0f..(stops.size - 1).toFloat(),
            steps = (stops.size - 2).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        // Tick labels under each notch so the snap values are visible, not just anonymous notches.
        // Padded by ~the thumb radius so the end labels line up with the end notches.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            stops.forEach { s ->
                Text(
                    if (s == 0) "Off" else "$s",
                    fontSize = 10.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else dim
                )
            }
        }
    }
}

@Composable
private fun IntSlider(label: String, value: Int, valueRange: IntRange, onValueChange: (Int) -> Unit, onValueChangeFinished: (() -> Unit)? = null, steps: Int = -1, enabled: Boolean = true) {
    val accent = MaterialTheme.colorScheme.primary
    // steps < 0 -> continuous (one stop per integer); steps >= 0 -> snap to that many
    // interior stops (e.g. steps = 3 over 0..100 yields the 5 positions {0,25,50,75,100}).
    val sliderSteps = if (steps >= 0) steps else (valueRange.last - valueRange.first - 1)
    Column(modifier = Modifier.padding(vertical = 4.dp).then(if (enabled) Modifier else Modifier.alpha(0.4f))) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text(text = "$value", style = MaterialTheme.typography.bodySmall, color = accent, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            onValueChangeFinished = { onValueChangeFinished?.invoke() },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = sliderSteps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SeShaderToggle(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier.alpha(0.4f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = accent,
                uncheckedColor = ToggleThumbOff,
                checkmarkColor = Color.White
            )
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
// ───── HUD Tab ─────

@Composable
private fun HudContent(state: XServerDrawerState) {
    val accent = MaterialTheme.colorScheme.primary
    val fpsConfig by state.fpsConfig.collectAsState()

    // Re-read the live display refresh rate when this tab opens so the "Rate" readout is fresh on
    // open; the display listener keeps it current while the drawer stays open.
    LaunchedEffect(Unit) { state.onRefreshRatePoll?.run() }

    SectionHeader("HUD")

    // ── FPS Limiter (standalone host-side cap; output-cap = on-screen fps, independent of frame gen) ──
    val fpsLimiterEnabled by state.fpsLimiterEnabled.collectAsState()
    val initFpsLimit by state.fpsLimit.collectAsState()

    Text("FPS Limiter", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))

    var limiterOn by remember(fpsLimiterEnabled) { mutableStateOf(fpsLimiterEnabled) }
    var limitVal by remember(initFpsLimit) { mutableIntStateOf(initFpsLimit) }
    fun applyLimiter() {
        state.setFpsLimiterEnabled(limiterOn)
        state.setFpsLimit(limitVal)
        // Standalone limiter: applies live to the host renderer regardless of frame-gen engine.
        state.onFpsLimitChange?.run()
    }

    ToggleRow("Limit FPS", limiterOn) { limiterOn = it; applyLimiter() }
    if (limiterOn) {
        LabeledSlider(
            "Max FPS", limitVal.toFloat(), 10f..200f,
            { limitVal = it.roundToInt() }, { applyLimiter() },
            format = { "${it.roundToInt()}" }
        )
        // Quick presets: set the cap in one tap. Shares limitVal with the slider above, so the
        // slider thumb snaps to the picked value (and the matching chip highlights on any value).
        Spacer(Modifier.height(6.dp))
        // Single aligned row of 4, same box style as the Fullscreen/Scaling mode buttons.
        ModeChipGrid(
            listOf(30, 60, 90, 120).map { preset ->
                Triple("$preset", limitVal == preset) { limitVal = preset; applyLimiter() }
            },
            perRow = 4
        )
        Text(
            "Caps on-screen FPS. Works with any frame-gen engine or none.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }

    // ── Refresh rate: Auto (match FPS / VRR) + manual snap to a supported panel rate ──
    val matchRefreshRate by state.matchRefreshRate.collectAsState()
    val vrrSupported by state.vrrSupported.collectAsState()
    val manualRefreshRate by state.manualRefreshRate.collectAsState()
    val supportedRefreshRates by state.supportedRefreshRates.collectAsState()
    val currentRefreshRate by state.currentRefreshRate.collectAsState()
    var matchRefreshOn by remember(matchRefreshRate) { mutableStateOf(matchRefreshRate) }

    Text("Refresh rate", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))

    // Auto (match FPS) == the existing VRR toggle; behavior unchanged.
    ToggleRow("Auto (match FPS)", matchRefreshOn && vrrSupported, enabled = vrrSupported) {
        matchRefreshOn = it
        state.setMatchRefreshRate(it)
        state.onMatchRefreshChange?.run()
    }
    // Manual rate slider: selectable only when Auto is OFF and the panel can switch rates.
    if (supportedRefreshRates.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        RefreshRateSlider(supportedRefreshRates, manualRefreshRate, vrrSupported && !matchRefreshOn, currentRefreshRate) { rate ->
            state.setManualRefreshRate(rate)
            state.onManualRefreshChange?.run()
        }
    }
    Text(
        when {
            !vrrSupported ->
                "Unavailable — this display has a single refresh rate, so there's nothing to match."
            matchRefreshOn ->
                "Auto is on — the display follows your FPS."
            manualRefreshRate > 0 ->
                "Display locked to ${manualRefreshRate} Hz."
            else ->
                "Pick a rate to lock the display, or turn Auto on to follow your FPS."
        },
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))

    fun parseConfig(s: String): Map<String, String> {
        if (s.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        s.split(",").forEach { part ->
            val eq = part.indexOf('=')
            if (eq >= 0) map[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return map
    }

    val cfg = remember(fpsConfig) { parseConfig(fpsConfig) }
    // Read with fallback across classic + gamehub key names (mirrors the container dialog).
    fun b(k: String, fb: String, d: String) = (cfg[k] ?: cfg[fb] ?: d) == "1"

    // Every HUD control is keyed on `cfg` so the drawer always mirrors the
    // setup currently in use: when a container launches, the overlay honors the
    // saved config and these re-initialize from that same live config (just like
    // the FPS-limiter rows above). Un-keyed remembers would capture stale values
    // once and drift from what's actually on screen.
    // Orientation is flipped by tapping the HUD in-game; preserve it on write-back.
    val hudMode = remember(cfg) { cfg.getOrDefault("hudMode", "vertical") }

    // 3-way HUD style: classic | gamehub | gamenative.
    val styles = listOf("classic", "gamehub", "gamenative")
    var hudStyle by remember(cfg) { mutableStateOf(cfg.getOrDefault("hudStyle", "classic")) }
    val gameHub = hudStyle == "gamehub"
    val gameNative = hudStyle == "gamenative"
    val rich = gameHub || gameNative   // both styles share opacity + FPS graph + GPU model + color/outline
    var showFPS by remember(cfg) { mutableStateOf(b("showFPS", "showFPS", "1")) }
    var showGraph by remember(cfg) { mutableStateOf(b("showFPSGraph", "showFPSGraph", "0")) }
    var showCPU by remember(cfg) { mutableStateOf(b("showCPUUsage", "showCPULoad", "1")) }
    var showGPU by remember(cfg) { mutableStateOf(b("showGPULoad", "showGPULoad", "1")) }
    var showRAM by remember(cfg) { mutableStateOf(b("showRAM", "showRAM", "1")) }
    var showPower by remember(cfg) { mutableStateOf(b("showPower", "showPower", "1")) }
    var showTemp by remember(cfg) { mutableStateOf(b("showTemp", "showBatteryTemp", "1")) }
    var showEngine by remember(cfg) { mutableStateOf(b("showEngine", "showRenderer", "1")) }
    var showGpuModel by remember(cfg) { mutableStateOf(b("showGpuModel", "showGpuModel", "0")) }
    var dualBattery by remember(cfg) { mutableStateOf(b("hudDualBattery", "hudDualBattery", "0")) }
    // GameNative-only extra metrics (absent = off is the intended default).
    var showGpuTemp by remember(cfg) { mutableStateOf(b("showGpuTemp", "showGpuTemp", "0")) }
    var showBattery by remember(cfg) { mutableStateOf(b("showBattery", "showBattery", "0")) }
    var showRuntime by remember(cfg) { mutableStateOf(b("showRuntime", "showRuntime", "0")) }
    var showClock by remember(cfg) { mutableStateOf(b("showClock", "showClock", "0")) }
    var showCpuGraph by remember(cfg) { mutableStateOf(b("showCPUGraph", "showCPUGraph", "0")) }
    var showGpuGraph by remember(cfg) { mutableStateOf(b("showGPUGraph", "showGPUGraph", "0")) }
    // Temperature display: unit, plus danger bands as a single 3-way (Off / Auto / Manual) rather
    // than two toggles — "banding on but auto off" and "banding off but auto on" aren't distinct
    // states worth exposing. Auto reads the device's own thermal trip points.
    var tempUnitF by remember(cfg) { mutableStateOf(cfg.getOrDefault("tempUnit", "c").equals("f", true)) }
    var tempBands by remember(cfg) { mutableStateOf(cfg.getOrDefault("tempBands", "1") != "0") }
    var tempAuto by remember(cfg) { mutableStateOf(cfg.getOrDefault("tempAuto", "1") != "0") }
    var tempRedCpu by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("tempRedCpu", "90").toFloatOrNull() ?: 90f) }
    var tempRedGpu by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("tempRedGpu", "90").toFloatOrNull() ?: 90f) }
    var tempRedBat by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("tempRedBat", "48").toFloatOrNull() ?: 48f) }

    var scaleValue by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("hudScale", Container.DEFAULT_HUD_SCALE.toString()).toFloatOrNull() ?: Container.DEFAULT_HUD_SCALE.toFloat()) }
    var opacityValue by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("hudOpacity", "80").toFloatOrNull() ?: 80f) }
    var transValue by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("hudTransparency", "0").toFloatOrNull() ?: 0f) }

    val skins = listOf("classic", "neon", "mono")
    val colors = listOf("soft", "mid", "vivid")
    var skin by remember(cfg) { mutableStateOf(cfg.getOrDefault("hudSkin", "classic")) }
    var color by remember(cfg) { mutableStateOf(cfg.getOrDefault("hudColor", "mid")) }
    // hudOutline is a 0..100 intensity (legacy off/soft/strong strings map via parseHudOutline).
    var outlineValue by remember(cfg) { mutableFloatStateOf(parseHudOutline(cfg.getOrDefault("hudOutline", "40")).toFloat()) }
    var outlineAccent by remember(cfg) { mutableStateOf(cfg.getOrDefault("hudOutlineAccent", "1") == "1") }

    fun i(v: Boolean) = if (v) "1" else "0"
    // Identical key set to ContainerDetailScreen.FpsCounterConfigDialog.buildConfig(),
    // so the in-game drawer and the pre-launch dialog stay fully interchangeable.
    fun buildConfig(): String = listOf(
        "hudStyle=$hudStyle",
        "hudMode=$hudMode",
        "showFPS=${i(showFPS)}",
        "showFPSGraph=${i(showGraph)}",
        "showCPUUsage=${i(showCPU)}",
        "showCPULoad=${i(showCPU)}",
        "showGPULoad=${i(showGPU)}",
        "showRAM=${i(showRAM)}",
        "showPower=${i(showPower)}",
        "showTemp=${i(showTemp)}",
        "showBatteryTemp=${i(showTemp)}",
        "showEngine=${i(showEngine)}",
        "showRenderer=${i(showEngine)}",
        "showGpuModel=${i(showGpuModel)}",
        "hudDualBattery=${i(dualBattery)}",
        "showGpuTemp=${i(showGpuTemp)}",
        "showBattery=${i(showBattery)}",
        "showRuntime=${i(showRuntime)}",
        "showClock=${i(showClock)}",
        "showCPUGraph=${i(showCpuGraph)}",
        "showGPUGraph=${i(showGpuGraph)}",
        "tempUnit=${if (tempUnitF) "f" else "c"}",
        "tempBands=${i(tempBands)}",
        "tempAuto=${i(tempAuto)}",
        "tempRedCpu=${tempRedCpu.toInt()}",
        "tempRedGpu=${tempRedGpu.toInt()}",
        "tempRedBat=${tempRedBat.toInt()}",
        "hudSkin=$skin",
        "hudColor=$color",
        "hudOutline=${outlineValue.toInt()}",
        "hudOutlineAccent=${if (outlineAccent) 1 else 0}",
        "hudScale=${scaleValue.toInt()}",
        "hudOpacity=${opacityValue.toInt()}",
        "hudTransparency=${transValue.toInt()}",
    ).joinToString(",")

    fun apply() { state.onFpsConfigApply?.invoke(buildConfig()) }

    HudChipRow("HUD style", listOf("Classic", "GameHub", "GameNative"), styles.indexOf(hudStyle).coerceAtLeast(0)) { hudStyle = styles[it]; apply() }
    Text(
        when (hudStyle) {
            "gamehub" -> "Rich overlay: skins, colored fields, live FPS graph. Style change applies on next launch."
            "gamenative" -> "GameNative-style overlay: compact pill or stacked list with live graphs. Style change applies on next launch."
            else -> "Classic Bannerlator overlay."
        },
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))

    LabeledSlider("HUD Scale", scaleValue, 50f..150f, { scaleValue = it }, { apply() }, format = { "${it.toInt()}%" })
    if (rich) LabeledSlider("HUD Opacity", opacityValue, 0f..100f, { opacityValue = it }, { apply() }, format = { "${it.toInt()}%" })
    else LabeledSlider("HUD Transparency", transValue, 0f..50f, { transValue = it }, { apply() }, format = { "${it.toInt()}" })

    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))

    // Compact multi-select metric chips (filled = on) in a wrap layout, replacing the
    // stacked Switch rows so ~13 metrics fit in a few rows. Each chip toggles the same
    // config key and live-applies, exactly like the old ToggleRows.
    Text("Metrics", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(4.dp))
    // Build the currently-VISIBLE chips first (respecting per-style gating), then chunk into an
    // aligned 3-wide grid — so hidden chips never leave holes. Each stays an independent toggle.
    val metricChips = buildList<Triple<String, Boolean, () -> Unit>> {
        add(Triple("FPS", showFPS) { showFPS = !showFPS; apply() })
        if (rich) add(Triple("FPS graph", showGraph) { showGraph = !showGraph; apply() })
        add(Triple("CPU", showCPU) { showCPU = !showCPU; apply() })
        if (gameNative) add(Triple("CPU graph", showCpuGraph) { showCpuGraph = !showCpuGraph; apply() })
        add(Triple("GPU", showGPU) { showGPU = !showGPU; apply() })
        if (gameNative) add(Triple("GPU graph", showGpuGraph) { showGpuGraph = !showGpuGraph; apply() })
        add(Triple("RAM", showRAM) { showRAM = !showRAM; apply() })
        add(Triple("Power", showPower) { showPower = !showPower; apply() })
        add(Triple("Temp", showTemp) { showTemp = !showTemp; apply() })
        if (gameNative) {
            add(Triple("GPU temp", showGpuTemp) { showGpuTemp = !showGpuTemp; apply() })
            add(Triple("Battery", showBattery) { showBattery = !showBattery; apply() })
            add(Triple("Runtime", showRuntime) { showRuntime = !showRuntime; apply() })
            add(Triple("Clock", showClock) { showClock = !showClock; apply() })
        }
        add(Triple("Engine", showEngine) { showEngine = !showEngine; apply() })
        if (rich) add(Triple("GPU model", showGpuModel) { showGpuModel = !showGpuModel; apply() })
        if (gameHub) add(Triple("Dual battery", dualBattery) { dualBattery = !dualBattery; apply() })
    }
    ModeChipGrid(metricChips, perRow = 3)

    // ── Temperature display ── only worth showing when a temperature is actually on screen.
    if (showTemp || (gameNative && (showGpuTemp || showBattery))) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
        HudChipRow("Temp unit", listOf("°C", "°F"), if (tempUnitF) 1 else 0) {
            tempUnitF = it == 1; apply()
        }
        val bandMode = if (!tempBands) 0 else if (tempAuto) 1 else 2
        HudChipRow("Danger colors", listOf("Off", "Auto", "Manual"), bandMode) {
            tempBands = it != 0
            tempAuto = it != 2
            apply()
        }
        Text(
            when (bandMode) {
                0 -> "Temperatures use their normal color."
                1 -> "Thresholds read from your device's own thermal trip points, falling back to safe defaults."
                else -> "Set the red point per sensor; amber sits just below it."
            },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
        )
        if (bandMode == 2) {
            // Only the red point is exposed; amber is derived. Nobody knows their preferred amber
            // in the abstract, and three sliders beat six. Values are always °C.
            LabeledSlider("CPU red at", tempRedCpu, 50f..110f, { tempRedCpu = it }, { apply() }, format = { "${it.toInt()}°C" })
            if (gameNative && showGpuTemp)
                LabeledSlider("GPU red at", tempRedGpu, 50f..110f, { tempRedGpu = it }, { apply() }, format = { "${it.toInt()}°C" })
            LabeledSlider("Battery red at", tempRedBat, 35f..60f, { tempRedBat = it }, { apply() }, format = { "${it.toInt()}°C" })
        }
    }

    if (gameHub) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
        HudChipRow("HUD skin", listOf("Classic", "Neon", "Mono"), skins.indexOf(skin)) { skin = skins[it]; apply() }
        HudChipRow("HUD color", listOf("Soft", "Mid", "Vivid"), colors.indexOf(color)) { color = colors[it]; apply() }
        LabeledSlider("HUD outline", outlineValue, 0f..100f, { outlineValue = it }, { apply() }, format = { "${it.toInt()}" })
        HudChipRow("Outline color", listOf("Gray", "Accent"), if (outlineAccent) 1 else 0) { outlineAccent = it == 1; apply() }
    } else if (gameNative) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
        HudChipRow("HUD color", listOf("Soft", "Mid", "Vivid"), colors.indexOf(color)) { color = colors[it]; apply() }
        LabeledSlider("HUD outline", outlineValue, 0f..100f, { outlineValue = it }, { apply() }, format = { "${it.toInt()}" })
        HudChipRow("Outline color", listOf("Gray", "Accent"), if (outlineAccent) 1 else 0) { outlineAccent = it == 1; apply() }
    }
}

// ───── 3-stop chip selector (skin / color / outline) ─────

@Composable
private fun HudChipRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { idx, opt ->
                val sel = idx == selected
                // Selected = accent fill / black text, matching the scaling + frame-gen buttons.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = if (idx < options.lastIndex) 6.dp else 0.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) accent else MaterialTheme.colorScheme.surface)
                        .clickable { onSelect(idx) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        opt,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sel) Color.Black else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ───── Controls Tab ─────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsContent(state: XServerDrawerState) {
    val accent = MaterialTheme.colorScheme.primary
    val profiles by XServerDialogState.inputProfiles.collectAsState()
    val initProfileIdx by XServerDialogState.selectedProfileIdx.collectAsState()
    val initTouchscreen by XServerDialogState.showTouchscreen.collectAsState()
    val initTimeout by XServerDialogState.timeoutEnabled.collectAsState()
    val initHaptics by XServerDialogState.hapticsEnabled.collectAsState()

    val moveCursorToTouch by state.moveCursorToTouchpoint.collectAsState()
    val isRelativeMouse by state.isRelativeMouseMovement.collectAsState()
    val isMouseDisabled by state.isMouseDisabled.collectAsState()
    val initOverlayOpacity by state.overlayOpacity.collectAsState()
    val controlsFollowTheme by state.controlsFollowTheme.collectAsState()
    val initControlsAccent by state.controlsAccentColor.collectAsState()

    SectionHeader("Controls")

    // Four unrelated feature areas live under this tab, so they're segmented rather than stacked —
    // exactly one renders at a time. ModeChipGrid is already the drawer's segmented-control language
    // (equal-width accent-filled chips), so the bar reads as native here instead of a new widget.
    val subTab by state.controlsSubTab.collectAsState()
    ModeChipGrid(
        listOf(
            Triple("Touch", subTab == 0) { state.setControlsSubTab(0) },
            Triple("Mouse", subTab == 1) { state.setControlsSubTab(1) },
            Triple("Vibration", subTab == 2) { state.setControlsSubTab(2) },
            Triple("Gyro", subTab == 3) { state.setControlsSubTab(3) },
        ),
        perRow = 4
    )
    Spacer(Modifier.height(8.dp))

    // Input Controls section — hoisted above the sub-tab switch so the in-flight profile/flag edits
    // survive a hop to another sub-tab and back.
    var selectedIdx by remember(initProfileIdx) { mutableIntStateOf(initProfileIdx) }
    var showTouchscreen by remember(initTouchscreen) { mutableStateOf(initTouchscreen) }
    var timeoutEnabled by remember(initTimeout) { mutableStateOf(initTimeout) }
    var hapticsEnabled by remember(initHaptics) { mutableStateOf(initHaptics) }
    val allItems = listOf("-- Disabled --") + profiles
    var dropdownExpanded by remember { mutableStateOf(false) }

    when (subTab) {
        // ── Touch ──
        0 -> {
            Text("Input Controls", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))

            ExposedDropdownMenuBox(expanded = dropdownExpanded, onExpandedChange = { dropdownExpanded = it }) {
                OutlinedTextField(
                    value = allItems.getOrElse(selectedIdx) { "-- Disabled --" },
                    onValueChange = {}, readOnly = true,
                    label = { Text("Profile", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    singleLine = true,
                )
                ExposedDropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                    allItems.forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            selectedIdx = i
                            dropdownExpanded = false
                            XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
                        })
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Three plain on/off flags that all round-trip through the same onInputControlsConfirm call —
            // packed as chips rather than full-width switch rows to keep the sub-tab short.
            ToggleChipGrid(
                listOf(
                    ToggleChipItem("Touch Controls", showTouchscreen) {
                        showTouchscreen = it
                        XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
                    },
                    ToggleChipItem("Timeout", timeoutEnabled) {
                        timeoutEnabled = it
                        XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
                    },
                    ToggleChipItem("Haptics", hapticsEnabled) {
                        hapticsEnabled = it
                        XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
                    },
                ),
                perRow = 3
            )

            // On-screen controls opacity — live, applied to the visible overlay as you drag.
            var overlayOpacity by remember(initOverlayOpacity) { mutableFloatStateOf(initOverlayOpacity) }
            LabeledSlider(
                label = "Overlay Opacity",
                value = overlayOpacity,
                valueRange = 0f..1f,
                onValueChange = {
                    overlayOpacity = it
                    state.setOverlayOpacity(it)
                    state.onOverlayOpacityChange?.run()
                },
                format = { "${(it * 100).toInt()}%" },
            )

            // On-screen controls accent — per-profile override. Follow the app theme (default) or pick a
            // custom accent for the active profile; idle controls stay white, pressed auto-brightens.
            Spacer(Modifier.height(4.dp))
            ToggleChipGrid(
                listOf(
                    ToggleChipItem("App Theme", controlsFollowTheme) {
                        state.setControlsFollowTheme(it)
                        state.onControlsColorChange?.run()
                    }
                ),
                perRow = 3
            )
            if (!controlsFollowTheme) {
                Spacer(Modifier.height(8.dp))
                Text("Controls Accent", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ColorPicker(
                    initialColor = Color(initControlsAccent),
                    onColorChanged = {
                        state.setControlsAccentColor(it.toArgb())
                        state.onControlsColorChange?.run()
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
                    XServerDialogState.onInputControlsSettings?.invoke(selectedIdx)
                },
                enabled = selectedIdx > 0,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Profile Settings\u2026") }

            Spacer(Modifier.height(4.dp))

            AccentButton("Apply & Close") {
                XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
                state.onClose?.run()
                Unit
            }
        }

        // ── Mouse ──
        1 -> {
            Text("Mouse & Cursor", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))

            // Each of these flips its flag host-side and stays open — like the fullscreen selector, the
            // drawer keeps rendering so the chip's new on/off state is visible where you tapped it.
            ToggleChipGrid(
                listOf(
                    ToggleChipItem("Cursor to Touch", moveCursorToTouch) {
                        state.onMoveCursorToTouchpoint?.run()
                    },
                    ToggleChipItem("Relative Mouse", isRelativeMouse) {
                        state.onRelativeMouseMovement?.run()
                    },
                    ToggleChipItem("Disable Mouse", isMouseDisabled) {
                        state.onDisableMouse?.run()
                    },
                ),
                perRow = 3
            )

            // Tied directly to the toggle: the gestures only exist in absolute-cursor mode, so the
            // pane appears as part of switching Cursor to Touch on and leaves with it. No cog — one
            // less tap, and turning the mode on now shows you exactly what you turned on.
            if (moveCursorToTouch) TouchGestureSettings(state)
        }

        // ── Vibration ──
        2 -> {
            Text("Vibration", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))

            // Master kill-switch — off suppresses ALL controller rumble regardless of slot (and hides the
            // rumble target, intensity, and per-slot rows below, which are moot while it's off). Persists
            // globally.
            val vibrationMasterOn by XServerDialogState.vibrationMasterEnabled.collectAsState()
            ToggleChipGrid(
                listOf(
                    ToggleChipItem("Enabled", vibrationMasterOn) {
                        XServerDialogState.setVibrationMasterEnabled(it)
                        XServerDialogState.onVibrationMasterChanged?.invoke(it)
                    }
                ),
                perRow = 3
            )
            if (vibrationMasterOn) {
                // Per-container rumble target + intensity (PC-accurate dual-motor rumble). Keyed on the
                // incoming config so re-opening the drawer doesn't drift from a stale capture — same pattern
                // as the lsfg "Performance mode" toggle elsewhere in this drawer.
                val initVibrationMode by XServerDialogState.vibrationMode.collectAsState()
                var vibrationMode by remember(initVibrationMode) { mutableIntStateOf(initVibrationMode) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Rumble Target",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                VibrationModeButtons(vibrationMode) {
                    vibrationMode = it
                    XServerDialogState.setVibrationMode(it)
                    XServerDialogState.onVibrationModeChanged?.invoke(it)
                }

                if (vibrationMode != 0) {
                    val initVibrationIntensity by XServerDialogState.vibrationIntensity.collectAsState()
                    var vibrationIntensity by remember(initVibrationIntensity) { mutableIntStateOf(initVibrationIntensity) }
                    IntSlider("Intensity", vibrationIntensity, 0..100,
                        onValueChange = { vibrationIntensity = it },
                        onValueChangeFinished = {
                            XServerDialogState.setVibrationIntensity(vibrationIntensity)
                            XServerDialogState.onVibrationIntensityChanged?.invoke(vibrationIntensity)
                        }
                    )
                }
            }
            val vibrationSlots by XServerDialogState.vibrationSlots.collectAsState()
            if (vibrationMasterOn && vibrationSlots.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                ToggleChipGrid(
                    vibrationSlots.mapIndexed { index, slot ->
                        ToggleChipItem(slot.first, slot.second) {
                            XServerDialogState.updateVibrationSlot(index, it)          // reflect in the UI immediately
                            XServerDialogState.onVibrationSlotChanged?.invoke(index, it) // persist to WinHandler
                        }
                    },
                    perRow = 3
                )
            }
        }

        // ── Gyro ── its own branch, deliberately OUTSIDE the vibration block above: the gyro section
        // is unrelated to rumble and must render whether or not vibration is switched on.
        3 -> GyroSection()
    }
}

// ───── Touch gesture settings — Controls > Mouse, shown while Cursor to Touch is on ─────
// These gestures only exist in absolute-cursor mode, so the pane lives and dies with that toggle
// rather than behind its own control. Each gesture is independently switchable because the right set
// is per-game: an RTS wants both, a mouse-look shooter wants neither stealing its drags. Changes
// apply to the live touchpad immediately and persist, so a game can be tuned without relaunching.
// There is no pinch-to-zoom — two-finger pan already emits the same wheel events.
@Composable
private fun TouchGestureSettings(state: XServerDrawerState) {
    val accent = MaterialTheme.colorScheme.primary
    val dragSelect by state.gestureDragSelect.collectAsState()
    val longPress by state.gestureLongPressRightClick.collectAsState()

    Spacer(Modifier.height(10.dp))
    Text("Touch Gestures", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(2.dp))
    Text(
        "Drag to box-select, hold for right click. Two-finger drag scrolls the wheel.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))

    ToggleChipGrid(
        listOf(
            ToggleChipItem("Box Select", dragSelect) {
                state.setGestureDragSelect(it); state.onGestureConfigChange?.run()
            },
            ToggleChipItem("Hold = Right", longPress) {
                state.setGestureLongPressRightClick(it); state.onGestureConfigChange?.run()
            },
        ),
        perRow = 2
    )

    // The slider only exists while its gesture does — a hold delay with holds switched off is the
    // kind of dead control the sub-tab split was meant to get rid of.
    if (longPress) {
        val initHoldMs by state.gestureLongPressMs.collectAsState()
        var holdMs by remember(initHoldMs) { mutableIntStateOf(initHoldMs) }
        IntSlider("Hold Delay", holdMs, 150..800,
            onValueChange = { holdMs = it },
            onValueChangeFinished = {
                state.setGestureLongPressMs(holdMs); state.onGestureConfigChange?.run()
            }
        )
    }
}

// ───── Gyro (motion aim) section — Controls tab, "Gyro" sub-tab ─────
// Progressive disclosure: the master chip is always visible; everything downstream only appears once
// the gyro is on, so the tab isn't a wall of dead controls. Order is by how often a control is
// touched — Enable, target, sensitivity, activation, then the set-once fine tuning. Hidden entirely
// on devices with no gyroscope.
@Composable
private fun GyroSection() {
    val accent = MaterialTheme.colorScheme.primary
    val gyroSupported by XServerDialogState.gyroSupported.collectAsState()
    if (!gyroSupported) return

    Text(stringResource(R.string.gyro_drawer_title), color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))

    val gyroEnabled by XServerDialogState.gyroEnabled.collectAsState()
    ToggleChipGrid(
        listOf(
            ToggleChipItem(stringResource(R.string.gyro_drawer_enabled), gyroEnabled) {
                XServerDialogState.setGyroEnabled(it)
                XServerDialogState.onGyroEnabledChanged?.invoke(it)
            }
        ),
        perRow = 3
    )
    if (!gyroEnabled) return

    // Where the tilt goes. Right/Left stick overlay the gamepad; Mouse drives the pointer instead,
    // which is the only target that does anything on a Wine desktop or in a mouse-look game.
    val initGyroTarget by XServerDialogState.gyroTarget.collectAsState()
    var gyroTarget by remember(initGyroTarget) { mutableIntStateOf(initGyroTarget) }

    // How the tilt is READ. Rate = the tilt speed drives the stick and it recentres when you stop;
    // Tilt to aim = the stick follows the angle you hold, so a held tilt keeps aiming. The two are
    // mutually exclusive with the Mouse target (a held tilt would be a constant pointer delta and the
    // pointer would run to a screen edge), so each greys the other's chip out with a reason.
    val orientationSupported by XServerDialogState.gyroOrientationSupported.collectAsState()
    val initGyroMode by XServerDialogState.gyroMode.collectAsState()
    var gyroMode by remember(initGyroMode) { mutableIntStateOf(initGyroMode) }
    val orientationBlockedByMouse = gyroTarget == 2
    val orientationSelectable = orientationSupported && !orientationBlockedByMouse
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.gyro_drawer_mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    ModeChipGrid(
        listOf(
            Triple(stringResource(R.string.gyro_drawer_mode_rate), gyroMode == 0) { setGyroModeLive(0) { gyroMode = it } },
            Triple(stringResource(R.string.gyro_drawer_mode_orientation), gyroMode == 1) { setGyroModeLive(1) { gyroMode = it } },
        ),
        perRow = 2,
        disabledIndices = if (orientationSelectable) emptySet() else setOf(1)
    )
    if (!orientationSupported) {
        GyroHint(stringResource(R.string.gyro_drawer_orientation_unsupported))
    }
    else if (orientationBlockedByMouse) {
        GyroHint(stringResource(R.string.gyro_drawer_orientation_mouse_hint))
    }
    else if (gyroMode == 1) {
        GyroHint(stringResource(R.string.gyro_drawer_orientation_hint))
        // Mandatory, not polish: with the "Always" activator there is never a rising edge, so this is
        // the only way to fix a centre that has drifted. Deliberately not bound to a gamepad button —
        // the activator already costs one.
        Spacer(Modifier.height(6.dp))
        AccentButton(stringResource(R.string.gyro_drawer_recenter)) {
            XServerDialogState.onGyroRecenterRequested?.invoke()
        }
        GyroHint(stringResource(R.string.gyro_drawer_recenter_hint))
    }

    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.gyro_drawer_apply_to), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    ModeChipGrid(
        listOf(
            Triple(stringResource(R.string.gyro_drawer_target_right_stick), gyroTarget == 0) { setGyroTargetLive(0) { gyroTarget = it } },
            Triple(stringResource(R.string.gyro_drawer_target_left_stick), gyroTarget == 1) { setGyroTargetLive(1) { gyroTarget = it } },
            Triple(stringResource(R.string.gyro_drawer_target_mouse), gyroTarget == 2) { setGyroTargetLive(2) { gyroTarget = it } },
        ),
        perRow = 3,
        disabledIndices = if (gyroMode == 1) setOf(2) else emptySet()
    )
    if (gyroMode == 1) {
        GyroHint(stringResource(R.string.gyro_drawer_mouse_unavailable_hint))
    }
    else if (gyroTarget == 2) {
        GyroHint(stringResource(R.string.gyro_drawer_mouse_hint))
    }

    // Sensitivity is the one knob people reach for constantly, so it sits right under the target.
    val initGyroSensitivity by XServerDialogState.gyroSensitivity.collectAsState()
    var gyroSensitivity by remember(initGyroSensitivity) { mutableFloatStateOf(initGyroSensitivity) }
    LabeledSlider(
        label = stringResource(R.string.gyro_sensitivity_label),
        value = gyroSensitivity,
        valueRange = 0.1f..10f,
        onValueChange = { gyroSensitivity = it },
        onValueChangeFinished = {
            XServerDialogState.setGyroSensitivity(gyroSensitivity)
            XServerDialogState.onGyroSensitivityChanged?.invoke(gyroSensitivity)
        },
        format = { "%.1f".format(it) }
    )

    // Which button gates the tilt. "Always on" removes the gate entirely — the only option that
    // works with no controller attached (e.g. gyro-as-mouse on the Wine desktop).
    val initGyroActivator by XServerDialogState.gyroActivator.collectAsState()
    var gyroActivator by remember(initGyroActivator) { mutableIntStateOf(initGyroActivator) }
    Spacer(Modifier.height(2.dp))
    Text(stringResource(R.string.gyro_drawer_activation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    ModeChipGrid(
        listOf(
            Triple(stringResource(R.string.gyro_activator_l1), gyroActivator == 0) { setGyroActivatorLive(0) { gyroActivator = it } },
            Triple(stringResource(R.string.gyro_activator_l2), gyroActivator == 1) { setGyroActivatorLive(1) { gyroActivator = it } },
            Triple(stringResource(R.string.gyro_activator_r1), gyroActivator == 2) { setGyroActivatorLive(2) { gyroActivator = it } },
            Triple(stringResource(R.string.gyro_activator_r3), gyroActivator == 3) { setGyroActivatorLive(3) { gyroActivator = it } },
            Triple(stringResource(R.string.gyro_drawer_activator_always), gyroActivator == 4) { setGyroActivatorLive(4) { gyroActivator = it } },
        ),
        perRow = 5
    )

    // Hold vs Toggle for that button. Greyed rather than hidden under "Always" — there's no button
    // to latch, but hiding the row would make it look like the setting vanished for good.
    val initGyroActivationMode by XServerDialogState.gyroActivationMode.collectAsState()
    var gyroActivationMode by remember(initGyroActivationMode) { mutableIntStateOf(initGyroActivationMode) }
    val activationModeEnabled = gyroActivator != 4
    Spacer(Modifier.height(4.dp))
    ModeChipGrid(
        listOf(
            Triple(stringResource(R.string.gyro_activation_hold), gyroActivationMode == 0) {
                setGyroActivationModeLive(0) { gyroActivationMode = it }
            },
            Triple(stringResource(R.string.gyro_activation_toggle), gyroActivationMode == 1) {
                setGyroActivationModeLive(1) { gyroActivationMode = it }
            },
        ),
        perRow = 2,
        enabled = activationModeEnabled
    )
    if (activationModeEnabled && gyroActivationMode == 1) {
        GyroHint(stringResource(R.string.gyro_drawer_toggle_hint))
    }

    // ---- Fine tuning: set once, then forgotten. Kept last so it never crowds the controls above. ----
    val initGyroDeadzone by XServerDialogState.gyroDeadzone.collectAsState()
    var gyroDeadzone by remember(initGyroDeadzone) { mutableFloatStateOf(initGyroDeadzone) }
    LabeledSlider(
        label = stringResource(R.string.gyro_deadzone_label),
        value = gyroDeadzone,
        valueRange = 0f..0.5f,
        onValueChange = { gyroDeadzone = it },
        onValueChangeFinished = {
            XServerDialogState.setGyroDeadzone(gyroDeadzone)
            XServerDialogState.onGyroDeadzoneChanged?.invoke(gyroDeadzone)
        },
        format = { "%.2f".format(it) }
    )

    val initGyroSmoothing by XServerDialogState.gyroSmoothing.collectAsState()
    var gyroSmoothing by remember(initGyroSmoothing) { mutableFloatStateOf(initGyroSmoothing) }
    LabeledSlider(
        label = stringResource(R.string.gyro_smoothing_label),
        value = gyroSmoothing,
        valueRange = 0f..0.95f,
        onValueChange = { gyroSmoothing = it },
        onValueChangeFinished = {
            XServerDialogState.setGyroSmoothing(gyroSmoothing)
            XServerDialogState.onGyroSmoothingChanged?.invoke(gyroSmoothing)
        },
        format = { "%.2f".format(it) }
    )

    val gyroInvertX by XServerDialogState.gyroInvertX.collectAsState()
    val gyroInvertY by XServerDialogState.gyroInvertY.collectAsState()
    ToggleChipGrid(
        listOf(
            ToggleChipItem(stringResource(R.string.gyro_invert_x), gyroInvertX) {
                XServerDialogState.setGyroInvertX(it)
                XServerDialogState.onGyroInvertXChanged?.invoke(it)
            },
            ToggleChipItem(stringResource(R.string.gyro_invert_y), gyroInvertY) {
                XServerDialogState.setGyroInvertY(it)
                XServerDialogState.onGyroInvertYChanged?.invoke(it)
            },
        ),
        perRow = 3
    )
}

// The one hint-text treatment used throughout the gyro section: dimmed, small, tucked under the row
// it explains. Also how a disabled chip states its reason, so "greyed out" is never unexplained.
@Composable
private fun GyroHint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

// Target/activator changes have to hit the local picker state AND WinHandler (which resets the
// overlay so the old stick can't stay deflected) — kept out of the composable so the chip lambdas
// stay one-liners.
private fun setGyroTargetLive(target: Int, reflect: (Int) -> Unit) {
    reflect(target)
    XServerDialogState.setGyroTarget(target)
    XServerDialogState.onGyroTargetChanged?.invoke(target)
}

private fun setGyroActivatorLive(activator: Int, reflect: (Int) -> Unit) {
    reflect(activator)
    XServerDialogState.setGyroActivator(activator)
    XServerDialogState.onGyroActivatorChanged?.invoke(activator)
}

// Read-mode switch. The activity re-registers the sensor on the way through (rate and orientation
// read DIFFERENT sensors), so this is the one gyro setting whose callback has a side effect beyond
// WinHandler's own fields.
private fun setGyroModeLive(mode: Int, reflect: (Int) -> Unit) {
    reflect(mode)
    XServerDialogState.setGyroMode(mode)
    XServerDialogState.onGyroModeChanged?.invoke(mode)
}

// Same deal for the activation mode: WinHandler drops the toggle latch on the way through, so
// switching to Hold can't leave a latched-on gyro behind.
private fun setGyroActivationModeLive(mode: Int, reflect: (Int) -> Unit) {
    reflect(mode)
    XServerDialogState.setGyroActivationMode(mode)
    XServerDialogState.onGyroActivationModeChanged?.invoke(mode)
}

// ───── Advanced Tab ─────

@Composable
private fun AdvancedContent(state: XServerDrawerState) {
    SectionHeader("Advanced")

    AdvancedActionRow("Magnifier", R.drawable.icon_magnifier) {
        state.onClose?.run(); state.onMagnifier?.run()
    }
    AdvancedActionRow("Active Windows", R.drawable.icon_active_windows) {
        state.onClose?.run(); state.onActiveWindows?.run()
    }
    AdvancedActionRow("Debug Logs", R.drawable.icon_debug) {
        state.onClose?.run(); state.onLogs?.run()
    }
    AdvancedActionRow("Picture-in-Picture", R.drawable.ic_picture_in_picture_alt) {
        state.onClose?.run(); state.onPipMode?.run()
    }
    AdvancedActionRow("Show Keyboard", R.drawable.icon_keyboard) {
        state.onClose?.run(); state.onKeyboard?.run()
    }
}

@Composable
private fun AdvancedActionRow(label: String, iconRes: Int, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

// ───── Task Manager Tab ─────

@Composable
private fun TmContent() {
    val accent = MaterialTheme.colorScheme.primary
    val processes by XServerDialogState.tmProcesses.collectAsState()
    val cpuCores by XServerDialogState.tmCpuCores.collectAsState()
    val cpuTitle by XServerDialogState.tmCpuTitle.collectAsState()
    val memTitle by XServerDialogState.tmMemTitle.collectAsState()
    val memInfo by XServerDialogState.tmMemInfo.collectAsState()
    val count by XServerDialogState.tmCount.collectAsState()

    // Polling is driven by a render-independent Handler timer in XServerDisplayActivity
    // (started via onTaskManager). A Compose LaunchedEffect delay() loop here stalls on the
    // Vulkan host-render path and left the Task Manager empty. onTmRefresh kicks an immediate
    // first refresh on entry; onTmDismissed stops the Activity timer on exit.
    LaunchedEffect(Unit) {
        XServerDialogState.onTmRefresh?.run()
    }

    DisposableEffect(Unit) {
        onDispose { XServerDialogState.onTmDismissed?.run() }
    }

    SectionHeader("Task Manager")

    Text(
        text = "Processes: $count",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
    )

    Spacer(Modifier.height(6.dp))

    if (processes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No processes", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    } else {
        // Each process is its own card (matching the app File Manager rows), not a flat
        // divider-separated list; cards self-space via their vertical padding.
        Column(modifier = Modifier.fillMaxWidth()) {
            processes.forEach { proc ->
                TmProcessRow(proc)
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    // CPU info
    Text(cpuTitle, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(2.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp)
    ) {
        cpuCores.forEach { core ->
            Text(core, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp))
        }
    }

    Spacer(Modifier.height(8.dp))

    // Memory info
    Text(memTitle, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(2.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp)
    ) {
        Text(memInfo, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }

    Spacer(Modifier.height(10.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = {
                XServerDialogState.onTmDismissed?.run()
                XServerDialogState.onTmNewTask?.run()
            }
        ) { Text("New Task\u2026", color = accent) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { XServerDialogState.onTmDismissed?.run() }) { Text("Clear", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun TmProcessRow(proc: XServerDialogState.TmProcess) {
    val accent = MaterialTheme.colorScheme.primary
    var menuExpanded by remember { mutableStateOf(false) }

    // Card per process, matching the app File Manager item style (rounded surfaceContainer
    // panel + outline border + vertical margin) instead of a flat divider-separated row.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (proc.icon != null) {
            Image(
                bitmap = proc.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.taskmgr_process),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = proc.name + if (proc.wow64) " *32" else "",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "PID ${proc.pid}  \u2022  ${proc.formattedMemory}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.outlinedMenuCard()
            ) {
                DropdownMenuItem(
                    text = { Text("Bring to Front") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.FlipToFront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        XServerDialogState.onTmBringToFront?.invoke(proc.name, proc.pid)
                    },
                )
                MenuItemDivider()
                DropdownMenuItem(
                    text = { Text("End Process", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        XServerDialogState.onTmKillProcess?.invoke(proc.name)
                    },
                )
            }
        }
      }
    }
}
