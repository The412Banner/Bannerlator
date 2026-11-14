package com.winlator.star.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.reshade.ReshadeLoadout
import com.winlator.star.reshade.ReshadeManager
import com.winlator.star.ui.ReshadeLoadoutItem
import com.winlator.star.ui.ScreenEffectLooks
import com.winlator.star.ui.XServerDialogState
import com.winlator.star.ui.XServerDrawerState
import com.winlator.star.ui.simple.L
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Lab-drawn Visual effects page: the Classic drawer's GraphicsEffectsSection (XServerDrawer.kt) and ReshadeSection on
// the same XServerDialogState / XServerDrawerState flows and callbacks, in the same order, drawn with the Ig widgets.

/** The Looks chip that stands for "Custom" (a state you arrive at; picking it does nothing, as in Classic). */
private const val LABFX_CUSTOM = -1

/** The nine scaling modes in the Classic picker's order (mode ints are persisted per game: never renumber). */
private val LABFX_SCALING_MODES = listOf(
    0 to "None", 1 to "Linear", 2 to "Nearest",
    3 to "SGSR", 8 to "SGSR HQ", 4 to "FSR",
    5 to "FSR (Fit)", 6 to "Sharpen", 7 to "NIS",
)

/** Same as XServerDrawer.pushSgsrUpdate. */
private fun labFxPushSgsr(enabled: Boolean, sharpness: Int, hdr: Boolean) {
    XServerDialogState.onSgsrUpdate?.invoke(enabled, sharpness, hdr)
}

/** A 0..100 sharpness slider value: whole numbers, or the five stops 0/25/50/75/100 (Classic IntSlider steps = 3). */
private fun labFxSharpnessValue(v: Float, fiveStops: Boolean): Int =
    if (fiveStops) ((v / 25f).roundToInt() * 25).coerceIn(0, 100) else v.roundToInt()

private fun labFxInt(v: Float): String = "${v.roundToInt()}"

/**
 * The Lab version of GraphicsEffectsSection: the renderer's own effect and scaling controls (OpenGL or Vulkan, nothing
 * on SurfaceFlinger), then Native rendering and the Wayland rows. Drawn under the page's own heading.
 */
@Composable
internal fun LabEffectsPanel(state: XServerDrawerState) {
    val effectsSupported by XServerDialogState.effectsSupported.collectAsState() // OpenGL renderer
    val vulkanSupported by XServerDialogState.vulkanSupported.collectAsState() // Vulkan renderer
    val nativeRenderingEnabled by state.nativeRenderingEnabled.collectAsState()
    val isWaylandSession by state.isWaylandMode.collectAsState()
    val waylandEffectsOk by state.waylandEffectsAvailable.collectAsState()
    val waylandFxBlocked = isWaylandSession && !waylandEffectsOk
    val glEnabled = !nativeRenderingEnabled && !waylandFxBlocked
    val vkEnabled = !waylandFxBlocked
    val game = igGameScope()

    if (effectsSupported) LabFxGlBlock(glEnabled, waylandFxBlocked, game)
    if (vulkanSupported) LabFxVkBlock(vkEnabled, waylandFxBlocked, game)
    if (!effectsSupported && !vulkanSupported) {
        IgCard { IgInfo(L("No graphics enhancements are available with the SurfaceFlinger renderer.")) }
    }

    val nativeRenderingSupported by state.nativeRenderingSupported.collectAsState()
    val hdrOutputAvailable by state.waylandHdrAvailable.collectAsState()
    if (nativeRenderingSupported || isWaylandSession) {
        IgSectionLabel(L("Rendering"))
        IgCard {
            var first = true
            if (nativeRenderingSupported) {
                // Wayland: X11 direct scanout never runs, so the row is greyed and points at Zero-copy presentation.
                IgToggleRow(
                    L("Native rendering"),
                    nativeRenderingEnabled,
                    hint = if (isWaylandSession) L("X11 direct scanout is not used on Wayland; Zero-copy presentation below is the Wayland equivalent.") else null,
                    scopes = listOf(SaveScope.SESSION),
                    enabled = !isWaylandSession,
                ) {
                    state.onNativeRenderingToggle?.run()
                }
                first = false
            }
            if (isWaylandSession) {
                if (hdrOutputAvailable) {
                    if (!first) IgDivider()
                    LabFxWaylandHdrOutputRow(state)
                    first = false
                }
                if (!first) IgDivider()
                LabFxWaylandZeroCopyRow(state, waylandEffectsOk, listOf(game))
                IgDivider()
                LabFxWaylandGlSafeModeRow(state, listOf(game))
            }
        }
    }
}

// ── OpenGL: scaling mode, CAS, HDR, debanding, Looks + colour grade + shader toggles (EffectComposer) ──
@Composable
private fun LabFxGlBlock(glEnabled: Boolean, waylandFxBlocked: Boolean, game: SaveScope) {
    val gameScopes = listOf(game)
    val session = listOf(SaveScope.SESSION)

    val initGlUpscalerMode by XServerDialogState.glUpscalerMode.collectAsState()
    var glUpscalerMode by remember(initGlUpscalerMode) { mutableIntStateOf(initGlUpscalerMode) }

    val initSgsrEnabled by XServerDialogState.sgsrEnabled.collectAsState()
    val initSgsrSharpness by XServerDialogState.sgsrSharpness.collectAsState()
    val initHdrEnabled by XServerDialogState.hdrEnabled.collectAsState()
    var sgsrEnabled by remember(initSgsrEnabled) { mutableStateOf(initSgsrEnabled) }
    var sgsrSharpness by remember(initSgsrSharpness) { mutableIntStateOf(initSgsrSharpness) }
    var hdrEnabled by remember(initHdrEnabled) { mutableStateOf(initHdrEnabled) }

    val seBrightness by XServerDialogState.seBrightness.collectAsState()
    val seContrast by XServerDialogState.seContrast.collectAsState()
    val seGamma by XServerDialogState.seGamma.collectAsState()
    val seSaturation by XServerDialogState.seSaturation.collectAsState()
    val seFxaa by XServerDialogState.seFxaa.collectAsState()
    val seCrt by XServerDialogState.seCrt.collectAsState()
    val seToon by XServerDialogState.seToon.collectAsState()
    val seNtsc by XServerDialogState.seNtsc.collectAsState()
    val seDeband by XServerDialogState.debandEnabled.collectAsState()
    var localBrightness by remember(seBrightness) { mutableFloatStateOf(seBrightness) }
    var localContrast by remember(seContrast) { mutableFloatStateOf(seContrast) }
    var localGamma by remember(seGamma) { mutableFloatStateOf(seGamma) }
    var localSaturation by remember(seSaturation) { mutableFloatStateOf(seSaturation) }
    var localFxaa by remember(seFxaa) { mutableStateOf(seFxaa) }
    var localCrt by remember(seCrt) { mutableStateOf(seCrt) }
    var localToon by remember(seToon) { mutableStateOf(seToon) }
    var localNtsc by remember(seNtsc) { mutableStateOf(seNtsc) }

    fun applySe() {
        XServerDialogState.onScreenEffectsApply?.invoke(localBrightness, localContrast, localGamma, localSaturation, localFxaa, localCrt, localToon, localNtsc, 0)
    }

    // Seeded by matching the live values, so a session launched with effects on shows "Custom".
    var selectedLook by remember {
        mutableStateOf(
            ScreenEffectLooks.indexOfMatch(
                seBrightness, seContrast, seGamma, seSaturation,
                if (initSgsrEnabled) initSgsrSharpness else 0,
                seFxaa, seCrt, seToon, seNtsc, seDeband, initGlUpscalerMode
            )
        )
    }

    fun applyGlLook(index: Int) {
        val look = ScreenEffectLooks.LOOKS[index]
        selectedLook = index
        // 1. Colour grade + the four shader toggles -> onScreenEffectsApply.
        localBrightness = look.brightness
        localContrast = look.contrast
        localGamma = look.gamma
        localSaturation = look.saturation.toFloat()
        localFxaa = look.fxaa; localCrt = look.crt; localToon = look.toon; localNtsc = look.ntsc
        XServerDialogState.setSeBrightness(localBrightness)
        XServerDialogState.setSeContrast(localContrast)
        XServerDialogState.setSeGamma(localGamma)
        XServerDialogState.setSeSaturation(localSaturation)
        XServerDialogState.setSeFxaa(localFxaa)
        XServerDialogState.setSeCrt(localCrt)
        XServerDialogState.setSeToon(localToon)
        XServerDialogState.setSeNtsc(localNtsc)
        applySe()
        // 2. Sharpening -> pushSgsrUpdate (cas 0 = pass off; the level is kept).
        sgsrEnabled = look.cas > 0
        if (look.cas > 0) sgsrSharpness = look.cas
        XServerDialogState.setSgsrEnabled(sgsrEnabled)
        XServerDialogState.setSgsrSharpness(sgsrSharpness)
        labFxPushSgsr(sgsrEnabled, sgsrSharpness, hdrEnabled)
        // 3. Debanding -> onDebandApply (the deband rows re-seed off the flow).
        XServerDialogState.setDebandEnabled(look.deband)
        XServerDialogState.onDebandApply?.invoke(look.deband, XServerDialogState.debandStrength.value)
        // 4. Scaling mode only when the Look names one.
        look.scalingMode?.let { mode ->
            glUpscalerMode = mode
            XServerDialogState.setGlUpscalerMode(mode)
            XServerDialogState.onGlUpscalerApply?.invoke(mode)
        }
    }

    fun lookTouched() { selectedLook = null }
    fun lookTouchedScaling() {
        if (ScreenEffectLooks.LOOKS.getOrNull(selectedLook ?: -1)?.scalingMode != null) selectedLook = null
    }

    IgCard {
        LabFxScalingRow(glUpscalerMode, glEnabled, waylandFxBlocked, gameScopes) {
            glUpscalerMode = it
            XServerDialogState.setGlUpscalerMode(it)
            lookTouchedScaling()
            XServerDialogState.onGlUpscalerApply?.invoke(it)
        }
        // SGSR EdgeSharpness / FSR RCAS / CAS / NIS, for the sharpening modes. Sharpen (6) snaps to five stops, 0 = off.
        if (glUpscalerMode in 3..8) {
            val initGlUpscaleSharpness by XServerDialogState.glUpscaleSharpness.collectAsState()
            var glUpscaleSharpness by remember(initGlUpscaleSharpness) { mutableIntStateOf(initGlUpscaleSharpness) }
            val fiveStops = glUpscalerMode == 6
            IgDivider()
            IgSliderRow(
                title = L("Sharpness"),
                value = glUpscaleSharpness.toFloat(),
                range = 0f..100f,
                step = if (fiveStops) 25f else 5f,
                format = ::labFxInt,
                onChange = { glUpscaleSharpness = labFxSharpnessValue(it, fiveStops) },
                onFinished = { XServerDialogState.onGlUpscaleSharpnessApply?.invoke(glUpscaleSharpness) },
                scopes = gameScopes,
                enabled = glEnabled,
            )
        }
        IgDivider()
        IgToggleRow(L("Sharpen (CAS)"), sgsrEnabled, scopes = gameScopes, enabled = glEnabled) {
            sgsrEnabled = it
            XServerDialogState.setSgsrEnabled(it)
            lookTouched()
            labFxPushSgsr(sgsrEnabled, sgsrSharpness, hdrEnabled)
        }
        if (sgsrEnabled) {
            // Standalone CAS: always the five stops, 0 = off.
            IgDivider()
            IgSliderRow(
                title = L("Sharpness"),
                value = sgsrSharpness.toFloat(),
                range = 0f..100f,
                step = 25f,
                format = ::labFxInt,
                onChange = { sgsrSharpness = labFxSharpnessValue(it, true); lookTouched() },
                onFinished = {
                    XServerDialogState.setSgsrSharpness(sgsrSharpness)
                    labFxPushSgsr(sgsrEnabled, sgsrSharpness, hdrEnabled)
                },
                scopes = gameScopes,
                enabled = glEnabled,
            )
        }
        IgDivider()
        IgToggleRow("HDR", hdrEnabled, scopes = session, enabled = glEnabled) {
            hdrEnabled = it
            labFxPushSgsr(sgsrEnabled, sgsrSharpness, hdrEnabled)
        }
        IgDivider()
        LabFxDebandRows(glEnabled, gameScopes) { lookTouched() }
    }

    IgSectionLabel(L("Screen effects"))
    IgCard {
        LabFxLooksRow(selectedLook, glEnabled) { applyGlLook(it) }
        IgDivider()
        IgSliderRow(
            title = L("Brightness"), value = localBrightness, range = -100f..100f, step = 10f,
            format = { "%.0f".format(it) },
            onChange = { localBrightness = it; lookTouched(); applySe() },
            onFinished = { XServerDialogState.setSeBrightness(localBrightness) },
            scopes = session, enabled = glEnabled,
        )
        IgDivider()
        IgSliderRow(
            title = L("Contrast"), value = localContrast, range = -100f..100f, step = 10f,
            format = { "%.0f".format(it) },
            onChange = { localContrast = it; lookTouched(); applySe() },
            onFinished = { XServerDialogState.setSeContrast(localContrast) },
            scopes = session, enabled = glEnabled,
        )
        IgDivider()
        IgSliderRow(
            title = L("Gamma"), value = localGamma, range = 0.5f..3.0f, step = 0.125f,
            format = { "%.2f".format(it) },
            onChange = { localGamma = it; lookTouched(); applySe() },
            onFinished = { XServerDialogState.setSeGamma(localGamma) },
            scopes = session, enabled = glEnabled,
        )
        IgDivider()
        // 0..200 percent, 100 = neutral.
        IgSliderRow(
            title = L("Saturation"), value = localSaturation, range = 0f..200f, step = 10f,
            format = { "%.0f".format(it) },
            onChange = { localSaturation = it; lookTouched(); applySe() },
            onFinished = { XServerDialogState.setSeSaturation(localSaturation) },
            scopes = session, enabled = glEnabled,
        )
        IgDivider()
        IgRow(L("Shader effects"), scopes = session, enabled = glEnabled, wideControl = true) {
            LabFxToggleChips(
                listOf(
                    LabFxToggle("FXAA", localFxaa) { localFxaa = it; XServerDialogState.setSeFxaa(it); lookTouched(); applySe() },
                    LabFxToggle("CRT", localCrt) { localCrt = it; XServerDialogState.setSeCrt(it); lookTouched(); applySe() },
                    LabFxToggle("Toon", localToon) { localToon = it; XServerDialogState.setSeToon(it); lookTouched(); applySe() },
                    LabFxToggle("NTSC", localNtsc) { localNtsc = it; XServerDialogState.setSeNtsc(it); lookTouched(); applySe() },
                ),
                enabled = glEnabled,
            )
        }
    }
}

// ── Vulkan: scaling mode, CAS, HDR, debanding, Looks + colour grade + shader toggles (post chain) ──
@Composable
private fun LabFxVkBlock(vkEnabled: Boolean, waylandFxBlocked: Boolean, game: SaveScope) {
    val gameScopes = listOf(game)
    val session = listOf(SaveScope.SESSION)

    val initUpscalerMode by XServerDialogState.upscalerMode.collectAsState()
    var upscalerMode by remember(initUpscalerMode) { mutableIntStateOf(initUpscalerMode) }

    val initCasEnabled by XServerDialogState.casEnabled.collectAsState()
    val initCasSharpness by XServerDialogState.casSharpness.collectAsState()
    val initHdrVkEnabled by XServerDialogState.hdrVkEnabled.collectAsState()
    var casEnabled by remember(initCasEnabled) { mutableStateOf(initCasEnabled) }
    var casSharpness by remember(initCasSharpness) { mutableIntStateOf(initCasSharpness) }
    var hdrVkEnabled by remember(initHdrVkEnabled) { mutableStateOf(initHdrVkEnabled) }

    val initVkBrightness by XServerDialogState.vkBrightness.collectAsState()
    val initVkContrast by XServerDialogState.vkContrast.collectAsState()
    val initVkGamma by XServerDialogState.vkGamma.collectAsState()
    val initVkSaturation by XServerDialogState.vkSaturation.collectAsState()
    val initVkFxaa by XServerDialogState.vkFxaa.collectAsState()
    val initVkToon by XServerDialogState.vkToon.collectAsState()
    val initVkCrt by XServerDialogState.vkCrt.collectAsState()
    val initVkNtsc by XServerDialogState.vkNtsc.collectAsState()
    val initVkDeband by XServerDialogState.debandEnabled.collectAsState()
    var vkBrightness by remember(initVkBrightness) { mutableFloatStateOf(initVkBrightness) }
    var vkContrast by remember(initVkContrast) { mutableFloatStateOf(initVkContrast) }
    var vkGamma by remember(initVkGamma) { mutableFloatStateOf(initVkGamma) }
    var vkSaturation by remember(initVkSaturation) { mutableFloatStateOf(initVkSaturation) }
    var vkFxaa by remember(initVkFxaa) { mutableStateOf(initVkFxaa) }
    var vkToon by remember(initVkToon) { mutableStateOf(initVkToon) }
    var vkCrt by remember(initVkCrt) { mutableStateOf(initVkCrt) }
    var vkNtsc by remember(initVkNtsc) { mutableStateOf(initVkNtsc) }

    fun applyVkSe() {
        XServerDialogState.onVulkanScreenEffectsApply?.invoke(
            vkBrightness, vkContrast, vkGamma, vkSaturation, vkFxaa, vkToon, vkCrt, vkNtsc)
    }

    var selectedLook by remember {
        mutableStateOf(
            ScreenEffectLooks.indexOfMatch(
                initVkBrightness, initVkContrast, initVkGamma, initVkSaturation,
                if (initCasEnabled) initCasSharpness else 0,
                initVkFxaa, initVkCrt, initVkToon, initVkNtsc, initVkDeband, initUpscalerMode
            )
        )
    }

    fun applyVkLook(index: Int) {
        val look = ScreenEffectLooks.LOOKS[index]
        selectedLook = index
        // 1. Colour grade + the four shader toggles -> onVulkanScreenEffectsApply.
        vkBrightness = look.brightness
        vkContrast = look.contrast
        vkGamma = look.gamma
        vkSaturation = look.saturation.toFloat()
        vkFxaa = look.fxaa; vkCrt = look.crt; vkToon = look.toon; vkNtsc = look.ntsc
        XServerDialogState.setVkBrightness(vkBrightness)
        XServerDialogState.setVkContrast(vkContrast)
        XServerDialogState.setVkGamma(vkGamma)
        XServerDialogState.setVkSaturation(vkSaturation)
        XServerDialogState.setVkFxaa(vkFxaa)
        XServerDialogState.setVkCrt(vkCrt)
        XServerDialogState.setVkToon(vkToon)
        XServerDialogState.setVkNtsc(vkNtsc)
        applyVkSe()
        // 2. Sharpening -> onCasApply (cas 0 = pass off; the level is kept).
        casEnabled = look.cas > 0
        if (look.cas > 0) casSharpness = look.cas
        XServerDialogState.setCasEnabled(casEnabled)
        XServerDialogState.setCasSharpness(casSharpness)
        XServerDialogState.onCasApply?.invoke(casEnabled, casSharpness)
        // 3. Debanding -> onDebandApply.
        XServerDialogState.setDebandEnabled(look.deband)
        XServerDialogState.onDebandApply?.invoke(look.deband, XServerDialogState.debandStrength.value)
        // 4. Scaling mode only when the Look names one.
        look.scalingMode?.let { mode ->
            upscalerMode = mode
            XServerDialogState.setUpscalerMode(mode)
            XServerDialogState.onUpscalerApply?.invoke(mode)
        }
    }

    fun lookTouched() { selectedLook = null }
    fun lookTouchedScaling() {
        if (ScreenEffectLooks.LOOKS.getOrNull(selectedLook ?: -1)?.scalingMode != null) selectedLook = null
    }

    IgCard {
        LabFxScalingRow(upscalerMode, vkEnabled, waylandFxBlocked, gameScopes) {
            upscalerMode = it
            XServerDialogState.setUpscalerMode(it)
            lookTouchedScaling()
            XServerDialogState.onUpscalerApply?.invoke(it)
        }
        // The real upscaler sharpness (RCAS / SGSR EdgeSharpness / NIS), for the sharpening modes.
        if (upscalerMode in 3..8) {
            val initUpscaleSharpness by XServerDialogState.upscaleSharpness.collectAsState()
            var upscaleSharpness by remember(initUpscaleSharpness) { mutableIntStateOf(initUpscaleSharpness) }
            IgDivider()
            IgSliderRow(
                title = L("Sharpness"),
                value = upscaleSharpness.toFloat(),
                range = 0f..100f,
                step = 5f,
                format = ::labFxInt,
                onChange = { upscaleSharpness = it.roundToInt() },
                onFinished = { XServerDialogState.onUpscaleSharpnessApply?.invoke(upscaleSharpness) },
                scopes = gameScopes,
                enabled = vkEnabled,
            )
        }
        IgDivider()
        IgToggleRow("CAS", casEnabled, scopes = gameScopes, enabled = vkEnabled) {
            casEnabled = it
            XServerDialogState.setCasEnabled(it)
            lookTouched()
            XServerDialogState.onCasApply?.invoke(casEnabled, casSharpness)
        }
        if (casEnabled) {
            IgDivider()
            IgSliderRow(
                title = L("CAS sharpness"),
                value = casSharpness.toFloat(),
                range = 0f..100f,
                step = 5f,
                format = ::labFxInt,
                onChange = { casSharpness = it.roundToInt(); lookTouched() },
                onFinished = {
                    XServerDialogState.setCasSharpness(casSharpness)
                    XServerDialogState.onCasApply?.invoke(casEnabled, casSharpness)
                },
                scopes = gameScopes,
                enabled = vkEnabled,
            )
        }
        IgDivider()
        IgToggleRow("HDR", hdrVkEnabled, scopes = gameScopes, enabled = vkEnabled) {
            hdrVkEnabled = it
            XServerDialogState.onHdrApply?.invoke(hdrVkEnabled)
        }
        IgDivider()
        LabFxDebandRows(vkEnabled, gameScopes) { lookTouched() }
    }

    IgSectionLabel(L("Screen effects"))
    IgCard {
        LabFxLooksRow(selectedLook, vkEnabled) { applyVkLook(it) }
        IgDivider()
        IgSliderRow(
            title = L("Brightness"), value = vkBrightness, range = -100f..100f, step = 10f,
            format = { "%.0f".format(it) },
            onChange = { vkBrightness = it; lookTouched(); applyVkSe() },
            onFinished = { XServerDialogState.setVkBrightness(vkBrightness) },
            scopes = session, enabled = vkEnabled,
        )
        IgDivider()
        IgSliderRow(
            title = L("Contrast"), value = vkContrast, range = -100f..100f, step = 10f,
            format = { "%.0f".format(it) },
            onChange = { vkContrast = it; lookTouched(); applyVkSe() },
            onFinished = { XServerDialogState.setVkContrast(vkContrast) },
            scopes = session, enabled = vkEnabled,
        )
        IgDivider()
        IgSliderRow(
            title = L("Gamma"), value = vkGamma, range = 0.5f..3.0f, step = 0.125f,
            format = { "%.2f".format(it) },
            onChange = { vkGamma = it; lookTouched(); applyVkSe() },
            onFinished = { XServerDialogState.setVkGamma(vkGamma) },
            scopes = session, enabled = vkEnabled,
        )
        IgDivider()
        // 0..200 percent, 100 = neutral.
        IgSliderRow(
            title = L("Saturation"), value = vkSaturation, range = 0f..200f, step = 10f,
            format = { "%.0f".format(it) },
            onChange = { vkSaturation = it; lookTouched(); applyVkSe() },
            onFinished = { XServerDialogState.setVkSaturation(vkSaturation) },
            scopes = session, enabled = vkEnabled,
        )
        IgDivider()
        IgRow(L("Shader effects"), scopes = session, enabled = vkEnabled, wideControl = true) {
            LabFxToggleChips(
                listOf(
                    LabFxToggle("FXAA", vkFxaa) { vkFxaa = it; XServerDialogState.setVkFxaa(it); lookTouched(); applyVkSe() },
                    LabFxToggle("Toon", vkToon) { vkToon = it; XServerDialogState.setVkToon(it); lookTouched(); applyVkSe() },
                    LabFxToggle("CRT", vkCrt) { vkCrt = it; XServerDialogState.setVkCrt(it); lookTouched(); applyVkSe() },
                    LabFxToggle("NTSC", vkNtsc) { vkNtsc = it; XServerDialogState.setVkNtsc(it); lookTouched(); applyVkSe() },
                ),
                enabled = vkEnabled,
            )
        }
    }
}

/** Scaling mode: the nine modes as chips plus the Classic "?" (help_scaling_mode), which stays usable while greyed. */
@Composable
private fun LabFxScalingRow(
    selected: Int,
    enabled: Boolean,
    waylandFxBlocked: Boolean,
    scopes: List<SaveScope>,
    onSelect: (Int) -> Unit,
) {
    // The help string is HTML (<b>, <br />); the Lab sheet shows plain text.
    val helpRaw = stringResource(R.string.help_scaling_mode)
    val helpText = remember(helpRaw) {
        com.winlator.star.ui.screens.htmlToAnnotated(helpRaw).text.lines().joinToString("\n") { it.trim() }.trim()
    }
    IgRow(
        L("Scaling mode"),
        note = if (waylandFxBlocked) L("Not on Wayland in this build yet.") else null,
        scopes = scopes,
        enabled = enabled,
        wideControl = true,
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f, fill = false)) {
                IgChips(LABFX_SCALING_MODES.map { (mode, label) -> mode to L(label) }, selected, enabled = enabled, onSelect = onSelect)
            }
            IgHelpButton(L("Scaling mode"), helpText)
        }
    }
}

/** Debanding + dither strength (Classic DebandControls): one shared state for both renderer blocks. */
@Composable
private fun LabFxDebandRows(enabled: Boolean, scopes: List<SaveScope>, onUserChange: () -> Unit) {
    val initDebandEnabled by XServerDialogState.debandEnabled.collectAsState()
    val initDebandStrength by XServerDialogState.debandStrength.collectAsState()
    var debandEnabled by remember(initDebandEnabled) { mutableStateOf(initDebandEnabled) }
    var debandStrength by remember(initDebandStrength) { mutableIntStateOf(initDebandStrength) }
    IgToggleRow(L("Debanding"), debandEnabled, scopes = scopes, enabled = enabled) {
        debandEnabled = it
        XServerDialogState.setDebandEnabled(it)
        onUserChange()
        XServerDialogState.onDebandApply?.invoke(debandEnabled, debandStrength)
    }
    if (debandEnabled) {
        IgDivider()
        IgSliderRow(
            title = L("Dither strength"),
            value = debandStrength.toFloat(),
            range = 0f..200f,
            step = 10f,
            format = ::labFxInt,
            onChange = { debandStrength = it.roundToInt() },
            onFinished = {
                XServerDialogState.setDebandStrength(debandStrength)
                XServerDialogState.onDebandApply?.invoke(debandEnabled, debandStrength)
            },
            scopes = scopes,
            enabled = enabled,
        )
    }
}

/** The Looks chips (ScreenEffectLooks) with a trailing "Custom" once a Look's own control was moved, and its line. */
@Composable
private fun LabFxLooksRow(selected: Int?, enabled: Boolean, onPick: (Int) -> Unit) {
    val looks = ScreenEffectLooks.LOOKS
    val options = looks.mapIndexed { i, look -> i to L(look.name) } +
        (if (selected == null) listOf(LABFX_CUSTOM to L(ScreenEffectLooks.CUSTOM_LABEL)) else emptyList<Pair<Int, String>>())
    val line = if (selected != null) {
        val look = looks[selected]
        if (look.desc.isEmpty()) L(look.name) else L(look.name) + " — " + L(look.desc)
    } else {
        L("Adjusted — everything below is yours. Tap a look to go back.")
    }
    IgRow(L("Looks"), hint = line, scopes = listOf(SaveScope.SESSION), enabled = enabled, wideControl = true) {
        IgChips(options, selected ?: LABFX_CUSTOM, enabled = enabled) { if (it != LABFX_CUSTOM) onPick(it) }
    }
}

private class LabFxToggle(val label: String, val checked: Boolean, val onToggle: (Boolean) -> Unit)

/** Independent on/off chips (the Classic shader toggles), drawn like IgChips; each one toggles with A or a tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabFxToggleChips(items: List<LabFxToggle>, enabled: Boolean) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalIgCompact.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (compact) Alignment.Start else Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            val on = item.checked
            val shape = deckShape(4.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .deckFocusRing(shape)
                    .clip(shape)
                    .background(if (on) cs.primary.copy(alpha = 0.16f) else cs.surfaceContainerHigh.copy(alpha = igRaised(1f)))
                    .border(if (on) 2.dp else 1.dp, if (on) cs.primary else deckLineAt(0.4f), shape)
                    .toggleable(value = on, enabled = enabled, role = Role.Checkbox, onValueChange = { v -> item.onToggle(v) })
                    .heightIn(min = 34.dp)
                    .widthIn(min = 44.dp)
                    .padding(horizontal = 11.dp)
                    .alpha(if (enabled) 1f else 0.45f),
            ) {
                Text(item.label, color = if (on) cs.primary else cs.onSurface, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1)
            }
        }
    }
}

// ── Wayland rows ──

/** HDR output (Classic WaylandHdrOutputRow): live, this session only; shown only while the compositor has HDR open. */
@Composable
private fun LabFxWaylandHdrOutputRow(state: XServerDrawerState) {
    val available by state.waylandHdrAvailable.collectAsState()
    if (!available) return
    val output by state.waylandHdrOutput.collectAsState()
    val onScreen by state.waylandHdrOnScreen.collectAsState()
    val noHeadroom by state.waylandHdrNoHeadroom.collectAsState()
    val toneMapped by state.waylandHdrToneMapped.collectAsState()
    val screenCapable by state.waylandHdrScreenCapable.collectAsState()
    var checked by remember(output) { mutableStateOf(output) }
    val status = when {
        checked && onScreen -> L("On: HDR frames on screen now.")
        checked && noHeadroom -> L("On, but the screen gives HDR no headroom right now: brightness at maximum, or the screen is being recorded (Android turns HDR headroom off while recording).")
        checked && toneMapped -> L("On, but shown tone-mapped: frame generation on a screen with no HDR swapchain.")
        checked && !screenCapable -> L("On, but this screen has no HDR10: Android tone-maps the picture for it. The game keeps the HDR it was offered until it closes.")
        checked -> L("On: no HDR frames right now (is HDR on in the game's settings?).")
        onScreen || noHeadroom -> L("Off, but these frames cannot be tone-mapped here: they stay HDR.")
        toneMapped -> L("Off: HDR frames shown tone-mapped to SDR.")
        else -> L("Off: no HDR frames right now.")
    }
    IgToggleRow(
        L("HDR output"),
        checked,
        hint = L("On: HDR games show real HDR on this screen. Off: the same picture tone-mapped to SDR. Applies immediately; the game's own HDR setting is left alone."),
        note = status,
        scopes = listOf(SaveScope.SESSION),
    ) {
        checked = it
        state.setWaylandHdrOutput(it)
        state.onWaylandHdrOutputToggle?.accept(it)
    }
}

/** Zero-copy presentation (Classic WaylandZeroCopyRow): live, kept as the game's default; status polled every second. */
@Composable
private fun LabFxWaylandZeroCopyRow(state: XServerDrawerState, effectsAvailable: Boolean, scopes: List<SaveScope>) {
    val requested by state.waylandZeroCopyRequested.collectAsState()
    val frames by state.waylandZeroCopyFrames.collectAsState()
    val live by state.waylandZeroCopyLive.collectAsState()
    var checked by remember(requested) { mutableStateOf(requested) }

    // Polled whichever way the switch is set: the line has to be able to say "switching off" too.
    LaunchedEffect(Unit) {
        while (true) {
            state.onWaylandZeroCopyPoll?.run()
            delay(1000)
        }
    }
    val effectsOn = effectsAvailable && labFxCompositorEffectsOn()
    val status = when {
        checked && effectsOn -> L("On: screen effects are drawn into the game's display layer, so it keeps hardware composition.")
        checked && !live -> L("Switching on… the game is rebuilding its swapchain on display buffers.")
        checked && frames > 0 -> L("On: %1\$s zero-copy frames in the last 10 s", frames)
        checked -> L("On: frames are going straight to the display layer.")
        live -> L("Switching off… the game's last display-layer frames are still on screen.")
        else -> L("Off: the compositor copies each frame into its own swapchain.")
    }
    IgToggleRow(
        L("Zero-copy presentation"),
        checked,
        hint = L("Fullscreen games are shown on their own display layer with no compositor copy. Applies immediately."),
        note = status,
        scopes = scopes,
    ) {
        checked = it
        state.setWaylandZeroCopyRequested(it)
        state.onWaylandZeroCopyToggle?.accept(it)
    }
}

/** True when any Vulkan-block effect that runs in the compositor pass is on (Classic waylandCompositorEffectsOn). */
@Composable
private fun labFxCompositorEffectsOn(): Boolean {
    val upscaler by XServerDialogState.upscalerMode.collectAsState()
    val cas by XServerDialogState.casEnabled.collectAsState()
    val hdr by XServerDialogState.hdrVkEnabled.collectAsState()
    val deband by XServerDialogState.debandEnabled.collectAsState()
    val brightness by XServerDialogState.vkBrightness.collectAsState()
    val contrast by XServerDialogState.vkContrast.collectAsState()
    val gamma by XServerDialogState.vkGamma.collectAsState()
    val saturation by XServerDialogState.vkSaturation.collectAsState()
    val fxaa by XServerDialogState.vkFxaa.collectAsState()
    val toon by XServerDialogState.vkToon.collectAsState()
    val crt by XServerDialogState.vkCrt.collectAsState()
    val ntsc by XServerDialogState.vkNtsc.collectAsState()
    return upscaler >= 3 || cas || hdr || deband || fxaa || toon || crt || ntsc ||
        brightness != 0f || contrast != 0f || gamma != 1f || saturation != 100f
}

/** OpenGL safe mode (Classic WaylandGlSafeModeRow): a saved preference for the next launch, not a live switch. */
@Composable
private fun LabFxWaylandGlSafeModeRow(state: XServerDrawerState, scopes: List<SaveScope>) {
    val safeMode by state.waylandGlSafeMode.collectAsState()
    var checked by remember(safeMode) { mutableStateOf(safeMode) }
    IgToggleRow(
        L("OpenGL safe mode"),
        checked,
        hint = if (checked)
            L("On: stops native OpenGL games disappearing with no error. Costs a little OpenGL speed; DirectX games are unaffected. Takes effect the next time the game starts.")
        else
            L("Off: OpenGL games keep Mesa's extra draw thread (slightly faster), but a fault on it can close the game with no error message. Takes effect the next time the game starts."),
        scopes = scopes,
    ) {
        checked = it
        state.setWaylandGlSafeMode(it)
        state.onWaylandGlSafeModeToggle?.accept(it)
    }
}

// ── ReShade ──

/**
 * The Lab version of ReshadeSection: master on/off, Live preview, Solo / Stack and one row per loadout effect (Solo: a
 * radio, Stack: a switch) with an expander for its typed controls and Reset. Every change goes through onReshadeApply.
 */
@Composable
internal fun LabReshadePanel() {
    val supported by XServerDialogState.reshadeSupported.collectAsState()
    // Seeded once from the flows (read via .value), so writing the snapshot back on each apply doesn't re-key the edits.
    val seed = remember { XServerDialogState.reshadeLoadout.value }

    if (!supported || seed.isEmpty()) {
        IgCard { IgInfo(L("No ReShade effects selected. Add one or more in this game's settings (or the container's) to switch and tune them here.")) }
        return
    }

    val game = igGameScope()
    var master by remember { mutableStateOf(XServerDialogState.reshadeMasterEnabled.value) }
    var mode by remember { mutableStateOf(XServerDialogState.reshadeMode.value) }
    val enabledState = remember { mutableStateMapOf<String, Boolean>().apply { seed.forEach { put(it.name, it.enabled) } } }
    val valueState = remember { seed.associate { it.name to mutableStateMapOf<String, Float>().apply { putAll(it.values) } } }
    // Bumped on any per-effect Reset so the colour controls re-seed from `values`.
    var resetNonce by remember { mutableIntStateOf(0) }
    val colorSeed = remember(resetNonce) { Any() }
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

    var livePreview by remember { mutableStateOf(XServerDialogState.reshadeLivePreview.value) }

    IgCard {
        IgToggleRow(L("ReShade"), master, scopes = listOf(game)) { master = it; apply() }
        IgDivider()
        IgToggleRow(
            L("Live preview"),
            livePreview,
            hint = if (livePreview) L("Changes apply live; the game keeps running.")
            else L("Game freezes while tuning; each change pulses briefly to preview, then re-freezes."),
            scopes = listOf(SaveScope.ALL),
        ) {
            livePreview = it
            XServerDialogState.setReshadeLivePreview(it)
            XServerDialogState.onReshadeLivePreviewChange?.invoke(it)
        }
        if (master) {
            IgDivider()
            IgRow(
                L("Mode"),
                hint = if (mode == ReshadeLoadout.MODE_SOLO) L("One effect at a time (A/B compare).") else L("Layer any subset of effects."),
                scopes = listOf(game),
                wideControl = true,
            ) {
                IgSegmented(listOf(ReshadeLoadout.MODE_SOLO to L("Solo"), ReshadeLoadout.MODE_STACK to L("Stack")), mode) { setMode(it) }
            }
        }
    }

    if (master) {
        IgSectionLabel(L("Effects"))
        IgCard {
            seed.forEachIndexed { i, item ->
                if (i > 0) IgDivider()
                val itemEnabled = enabledState[item.name] ?: item.enabled
                val isOpen = expanded == item.name
                IgRow(item.name, scopes = listOf(game)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (mode == ReshadeLoadout.MODE_SOLO) {
                            LabFxRadio(itemEnabled) { setEnabled(item.name, true) }
                        } else {
                            IgSwitch(itemEnabled) { setEnabled(item.name, it) }
                        }
                        IgButton(
                            null,
                            if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            { expanded = if (isOpen) null else item.name },
                        )
                    }
                }
                if (isOpen) {
                    val values = valueState[item.name] ?: mutableStateMapOf()
                    Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        ) {
                            IgButton(
                                L("Reset"),
                                Icons.Filled.Restore,
                                {
                                    values.clear()
                                    item.params.forEach { p -> ReshadeManager.seedValues(p, null, values) }
                                    resetNonce++
                                    apply()
                                },
                                enabled = item.params.isNotEmpty(),
                            )
                        }
                        if (item.params.isEmpty()) {
                            IgNote(L("No tunable parameters."))
                        } else {
                            LabFxReshadeParams(item.params, values, colorSeed) { apply() }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

/** A Lab radio mark for Solo mode: A or a tap makes this effect the active one. */
@Composable
private fun LabFxRadio(selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .deckFocusRing(CircleShape)
            .clip(CircleShape)
            .border(2.dp, if (selected) cs.primary else deckLineAt(0.8f), CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .size(30.dp),
    ) {
        if (selected) Box(Modifier.size(14.dp).clip(CircleShape).background(cs.primary))
    }
}

/** One effect's typed controls (Classic ReshadeEffectParams): BOOL switch, COMBO list, COLOR picker, FLOAT/INT slider. */
@Composable
private fun LabFxReshadeParams(
    params: List<ReshadeManager.ReshadeParam>,
    values: SnapshotStateMap<String, Float>,
    colorSeed: Any,
    onApply: () -> Unit,
) {
    params.forEachIndexed { i, p ->
        if (i > 0) IgDivider()
        when (p.type) {
            ReshadeManager.ParamType.BOOL -> {
                val v = values[p.name] ?: p.defaultValue
                IgToggleRow(p.label, v >= 0.5f) {
                    values[p.name] = if (it) 1f else 0f; onApply()
                }
            }
            ReshadeManager.ParamType.COMBO -> {
                val idx = (values[p.name] ?: p.defaultValue).roundToInt()
                val options: List<String> = p.options ?: emptyList()
                // Classic shows the first item for an index out of range.
                IgPickRow(p.label, options, if (idx in options.indices) idx else 0) { sel ->
                    values[p.name] = sel.toFloat(); onApply()
                }
            }
            ReshadeManager.ParamType.COLOR -> {
                LabFxColorControl(
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
                IgSliderRow(
                    title = p.label,
                    value = v,
                    range = p.min..p.max,
                    step = (p.max - p.min) / 20f,
                    format = {
                        if (p.type == ReshadeManager.ParamType.INT) it.toInt().toString()
                        else "%.2f".format(it)
                    },
                    onChange = { values[p.name] = it },
                    onFinished = { onApply() },
                )
            }
        }
    }
}

/**
 * COLOR (float3/float4): swatch + expander, then hue / saturation / brightness (and alpha for float4) gradient
 * sliders. Emits RGB(A) as 0..1 floats. The HSV state is keyed on [seedKey] so a Reset snaps it back.
 */
@Composable
private fun LabFxColorControl(
    label: String,
    components: Int,
    seedKey: Any,
    component: (Int) -> Float,
    onChange: (FloatArray) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
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

    // Collapsed by default, as in Classic.
    var expanded by remember { mutableStateOf(false) }
    IgRow(label) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val swatch = deckShape(6.dp)
            Box(
                Modifier
                    .size(26.dp)
                    .clip(swatch)
                    .background(Color(rgbInt()))
                    .border(1.dp, cs.onSurfaceVariant, swatch)
            )
            Spacer(Modifier.width(10.dp))
            IgButton(null, if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, { expanded = !expanded })
        }
    }
    if (expanded) {
        LabFxGradientSlider(
            L("Hue"), hue, 0f..360f,
            Brush.horizontalGradient((0..12).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f))) })
        ) { hue = it; emit() }
        LabFxGradientSlider(
            L("Saturation"), sat, 0f..1f,
            Brush.horizontalGradient(listOf(
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0f, valv))),
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, valv)))
            ))
        ) { sat = it; emit() }
        LabFxGradientSlider(
            L("Brightness"), valv, 0f..1f,
            Brush.horizontalGradient(listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f)))))
        ) { valv = it; emit() }
        if (components >= 4) {
            LabFxGradientSlider(
                L("Alpha"), alpha, 0f..1f,
                Brush.horizontalGradient(listOf(Color.Black, Color(rgbInt())))
            ) { alpha = it; emit() }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * A gradient-track slider for the colour picker (Classic GradientSlider): tap or drag to pick; with the controller,
 * focus it and d-pad left / right steps a twentieth of the range.
 */
@Composable
private fun LabFxGradientSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    track: Brush,
    onValueChange: (Float) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // The pointer handlers live across recompositions; they call the current callback.
    val latest by rememberUpdatedState(onValueChange)
    var width by remember { mutableIntStateOf(0) }
    fun pick(x: Float) {
        if (width > 0) {
            val frac = (x / width).coerceIn(0f, 1f)
            latest.invoke(range.start + frac * (range.endInclusive - range.start))
        }
    }
    val shape = deckShape(11.dp)
    val step = (range.endInclusive - range.start) / 20f
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Text(label, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .deckFocusRing(shape, scaleTo = 1.02f)
                .clip(shape)
                .background(track)
                .border(1.dp, deckLineAt(0.5f), shape)
                .onSizeChanged { width = it.width }
                .onPreviewKeyEvent { ev ->
                    val dir = when (ev.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> 0
                    }
                    if (dir == 0) return@onPreviewKeyEvent false
                    if (ev.type == KeyEventType.KeyDown) {
                        onValueChange((value + dir * step).coerceIn(range.start, range.endInclusive))
                    }
                    true
                }
                .focusable()
                .pointerInput(range) { detectTapGestures { pick(it.x) } }
                .pointerInput(range) { detectHorizontalDragGestures { ch, _ -> pick(ch.position.x) } },
        ) {
            val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxSize().padding(horizontal = 3.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth(frac)) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.dp, Color.Black, CircleShape)
                    )
                }
            }
        }
    }
}
