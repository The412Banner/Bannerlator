package com.winlator.star.ui

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object XServerDialogState {

    enum class ActiveDialog {
        NONE, VIBRATION, DEBUG, INPUT_CONTROLS, SCREEN_EFFECTS, ACTIVE_WINDOWS, NEW_TASK
    }

    // -------------------------------------------------------------------------
    // Active dialog
    // -------------------------------------------------------------------------
    private val _activeDialog = MutableStateFlow(ActiveDialog.NONE)
    val activeDialog: StateFlow<ActiveDialog> = _activeDialog

    fun show(d: ActiveDialog) { _activeDialog.value = d }
    fun dismiss() { _activeDialog.value = ActiveDialog.NONE }

    // -------------------------------------------------------------------------
    // Magnifier overlay
    // -------------------------------------------------------------------------
    private val _magnifierVisible = MutableStateFlow(false)
    val magnifierVisible: StateFlow<Boolean> = _magnifierVisible

    private val _magnifierZoom = MutableStateFlow(1.0f)
    val magnifierZoom: StateFlow<Float> = _magnifierZoom

    fun setMagnifierVisible(v: Boolean) { _magnifierVisible.value = v }
    fun setMagnifierZoom(v: Float)      { _magnifierZoom.value = v }

    fun interface FloatCallback { fun invoke(delta: Float) }
    @JvmField var onMagnifierZoom: FloatCallback? = null
    @JvmField var onMagnifierHide: Runnable? = null

    // -------------------------------------------------------------------------
    // SGSR state
    // -------------------------------------------------------------------------
    private val _sgsrEnabled   = MutableStateFlow(false)
    val sgsrEnabled: StateFlow<Boolean> = _sgsrEnabled

    private val _sgsrSharpness = MutableStateFlow(50)
    val sgsrSharpness: StateFlow<Int> = _sgsrSharpness

    private val _hdrEnabled    = MutableStateFlow(false)
    val hdrEnabled: StateFlow<Boolean> = _hdrEnabled

    // SGSR/HDR are GL EffectComposer features; the Vulkan renderer has no post-process
    // pipeline, so they do nothing there. Drives the grayed-out state in the drawer.
    private val _effectsSupported = MutableStateFlow(true)
    val effectsSupported: StateFlow<Boolean> = _effectsSupported
    fun setEffectsSupported(v: Boolean) { _effectsSupported.value = v }

    fun setSgsrEnabled(v: Boolean)    { _sgsrEnabled.value = v }
    fun setSgsrSharpness(v: Int)      { _sgsrSharpness.value = v }
    fun setHdrEnabled(v: Boolean)     { _hdrEnabled.value = v }

    fun interface SgsrUpdateCallback { fun invoke(enabled: Boolean, sharpness: Int, hdr: Boolean) }
    @JvmField var onSgsrUpdate: SgsrUpdateCallback? = null

    // -------------------------------------------------------------------------
    // Scaling mode (GL spatial upscaler) — parity with the Vulkan picker below.
    // 0=None 1=Linear 2=Nearest 3=SGSR 4=FSR 5=FSR(Fit) 6=Sharpen(CAS). Modes 0/1/2
    // drive GLRenderer.setFilterMode; 3/4/5 engage the EffectComposer SGSR/FSR low-res
    // stage; 6 reuses the existing CAS post-effect. Drawer-only / session-live; gated to
    // the GL renderer via effectsSupported.
    // -------------------------------------------------------------------------
    private val _glUpscalerMode = MutableStateFlow(0)
    val glUpscalerMode: StateFlow<Int> = _glUpscalerMode
    fun setGlUpscalerMode(v: Int) { _glUpscalerMode.value = v }

    // 0..100; drives SGSR EdgeSharpness / FSR RCAS / CAS level for the GL picker.
    private val _glUpscaleSharpness = MutableStateFlow(75)
    val glUpscaleSharpness: StateFlow<Int> = _glUpscaleSharpness
    fun setGlUpscaleSharpness(v: Int) { _glUpscaleSharpness.value = v }

    fun interface GlUpscalerApplyCallback { fun invoke(mode: Int) }
    @JvmField var onGlUpscalerApply: GlUpscalerApplyCallback? = null

    fun interface GlUpscaleSharpnessCallback { fun invoke(sharpness: Int) }
    @JvmField var onGlUpscaleSharpnessApply: GlUpscaleSharpnessCallback? = null

    // -------------------------------------------------------------------------
    // Scaling mode (Vulkan spatial upscaler)
    // -------------------------------------------------------------------------
    // 0=none 1=linear 2=nearest 3=sgsr 4=fsr(fill) 5=fsr_fit(letterbox). Drawer-only /
    // session-live (not persisted to the Container). Modes 1/2 also drive the base sampler
    // filter natively, so this is the single source of truth for Vulkan scaling/filtering.
    private val _upscalerMode = MutableStateFlow(0)
    val upscalerMode: StateFlow<Int> = _upscalerMode
    fun setUpscalerMode(v: Int) { _upscalerMode.value = v }

    // The spatial upscaler lives on the Vulkan renderer only (inverse of effectsSupported,
    // which is GL-only). False on the OpenGL and SurfaceFlinger renderers -> grays the picker.
    private val _vulkanSupported = MutableStateFlow(false)
    val vulkanSupported: StateFlow<Boolean> = _vulkanSupported
    fun setVulkanSupported(v: Boolean) { _vulkanSupported.value = v }

    fun interface UpscalerApplyCallback { fun invoke(mode: Int) }
    @JvmField var onUpscalerApply: UpscalerApplyCallback? = null

    // -------------------------------------------------------------------------
    // Vulkan composable post effects (CAS sharpen + fake-HDR) + real upscaler
    // sharpness. Drawer-only / session-live, same lifecycle as the scaling mode.
    // -------------------------------------------------------------------------
    private val _casEnabled = MutableStateFlow(false)
    val casEnabled: StateFlow<Boolean> = _casEnabled
    fun setCasEnabled(v: Boolean) { _casEnabled.value = v }

    private val _casSharpness = MutableStateFlow(60)
    val casSharpness: StateFlow<Int> = _casSharpness
    fun setCasSharpness(v: Int) { _casSharpness.value = v }

    private val _hdrVkEnabled = MutableStateFlow(false)
    val hdrVkEnabled: StateFlow<Boolean> = _hdrVkEnabled
    fun setHdrVkEnabled(v: Boolean) { _hdrVkEnabled.value = v }

    // 0..100; 75 == the legacy 0.25 RCAS stops. Drives RCAS + SGSR EdgeSharpness.
    private val _upscaleSharpness = MutableStateFlow(75)
    val upscaleSharpness: StateFlow<Int> = _upscaleSharpness
    fun setUpscaleSharpness(v: Int) { _upscaleSharpness.value = v }

    fun interface CasApplyCallback { fun invoke(enabled: Boolean, sharpness: Int) }
    @JvmField var onCasApply: CasApplyCallback? = null

    fun interface HdrApplyCallback { fun invoke(enabled: Boolean) }
    @JvmField var onHdrApply: HdrApplyCallback? = null

    fun interface UpscaleSharpnessCallback { fun invoke(sharpness: Int) }
    @JvmField var onUpscaleSharpnessApply: UpscaleSharpnessCallback? = null

    // Terminal debanding / dither. strength 0..200 -> strength/100 LSBs (100 = 1 LSB,
    // the default). Drawer-only / session-live, same lifecycle as CAS/HDR.
    private val _debandEnabled = MutableStateFlow(false)
    val debandEnabled: StateFlow<Boolean> = _debandEnabled
    fun setDebandEnabled(v: Boolean) { _debandEnabled.value = v }

    private val _debandStrength = MutableStateFlow(100)
    val debandStrength: StateFlow<Int> = _debandStrength
    fun setDebandStrength(v: Int) { _debandStrength.value = v }

    fun interface DebandApplyCallback { fun invoke(enabled: Boolean, strength: Int) }
    @JvmField var onDebandApply: DebandApplyCallback? = null

    // -------------------------------------------------------------------------
    // Vulkan Phase 2 screen effects (GL EffectComposer parity): Color grade
    // (brightness/contrast/gamma sliders) + FXAA/Toon/CRT/NTSC toggles. Drawer-only
    // / session-live, same lifecycle as the scaling mode + CAS/HDR.
    // -------------------------------------------------------------------------
    private val _vkBrightness = MutableStateFlow(0f)       // -100..100, 0 = neutral
    val vkBrightness: StateFlow<Float> = _vkBrightness
    fun setVkBrightness(v: Float) { _vkBrightness.value = v }

    private val _vkContrast = MutableStateFlow(0f)         // -100..100, 0 = neutral
    val vkContrast: StateFlow<Float> = _vkContrast
    fun setVkContrast(v: Float) { _vkContrast.value = v }

    private val _vkGamma = MutableStateFlow(1.0f)          // 0.5..3.0, 1.0 = neutral
    val vkGamma: StateFlow<Float> = _vkGamma
    fun setVkGamma(v: Float) { _vkGamma.value = v }

    private val _vkFxaa = MutableStateFlow(false)
    val vkFxaa: StateFlow<Boolean> = _vkFxaa
    fun setVkFxaa(v: Boolean) { _vkFxaa.value = v }

    private val _vkToon = MutableStateFlow(false)
    val vkToon: StateFlow<Boolean> = _vkToon
    fun setVkToon(v: Boolean) { _vkToon.value = v }

    private val _vkCrt = MutableStateFlow(false)
    val vkCrt: StateFlow<Boolean> = _vkCrt
    fun setVkCrt(v: Boolean) { _vkCrt.value = v }

    private val _vkNtsc = MutableStateFlow(false)
    val vkNtsc: StateFlow<Boolean> = _vkNtsc
    fun setVkNtsc(v: Boolean) { _vkNtsc.value = v }

    // Single applier mirroring the GL onScreenEffectsApply signature.
    fun interface VulkanScreenEffectsCallback {
        fun invoke(brightness: Float, contrast: Float, gamma: Float,
                   fxaa: Boolean, toon: Boolean, crt: Boolean, ntsc: Boolean)
    }
    @JvmField var onVulkanScreenEffectsApply: VulkanScreenEffectsCallback? = null

    // -------------------------------------------------------------------------
    // ReShade (vkBasalt) — in-game tab. Tier 1: a multi-effect LOADOUT the user switches between
    // LIVE. All loadout effects are compiled into the chain up front; per-effect flags decide which
    // present. A solo/stack MODE governs how many can be active at once (solo = radio/one-at-a-time
    // for A/B; stack = checkboxes/layered). The effects are chosen pre-launch (shortcut/container
    // editor); this tab toggles + tunes them. Supported only on DXVK/VKD3D (Vulkan) games — the tab
    // greys/hides otherwise (mirrors how SGSR/HDR are gated). Every change rides a SINGLE pluggable
    // seam (onReshadeApply -> applyReshadeLive: conf rewrite the patched libvkbasalt mtime-watch
    // reloads live, and persists to Container/shortcut).
    // -------------------------------------------------------------------------
    private val _reshadeSupported = MutableStateFlow(false)
    val reshadeSupported: StateFlow<Boolean> = _reshadeSupported
    fun setReshadeSupported(v: Boolean) { _reshadeSupported.value = v }

    // Master (whole-chain) on/off — enableOnLaunch. Independent of the per-effect flags.
    private val _reshadeMasterEnabled = MutableStateFlow(false)
    val reshadeMasterEnabled: StateFlow<Boolean> = _reshadeMasterEnabled
    fun setReshadeMasterEnabled(v: Boolean) { _reshadeMasterEnabled.value = v }

    // "solo" (one active) | "stack" (any subset). Governs the activation controls (radio vs checkbox).
    private val _reshadeMode = MutableStateFlow(com.winlator.star.reshade.ReshadeLoadout.MODE_SOLO)
    val reshadeMode: StateFlow<String> = _reshadeMode
    fun setReshadeMode(v: String) { _reshadeMode.value = com.winlator.star.reshade.ReshadeLoadout.normalizeMode(v) }

    // The loadout: ordered effects, each with enabled + reflected params + current values. Set once
    // at launch (seeded after the container is assigned); the drawer edits copies + reports snapshots.
    private val _reshadeLoadout = MutableStateFlow<List<ReshadeLoadoutItem>>(emptyList())
    val reshadeLoadout: StateFlow<List<ReshadeLoadoutItem>> = _reshadeLoadout
    fun setReshadeLoadout(v: List<ReshadeLoadoutItem>) { _reshadeLoadout.value = v }

    // Full drawer snapshot: master on/off + mode + the per-effect (enabled + values) loadout. The
    // activity rewrites the conf (mtime bump → live) and persists in applyReshadeLive.
    fun interface ReshadeApplyCallback {
        fun invoke(masterEnabled: Boolean, mode: String, items: List<ReshadeLoadoutItem>)
    }
    @JvmField var onReshadeApply: ReshadeApplyCallback? = null

    // "Live preview" — persisted global toggle at the top of the ReShade tab. ON = ReShade changes
    // apply live while the game keeps running (today's behaviour). OFF (default) = "freeze-frame +
    // pulse" preview: the first committed change SIGSTOPs the game; each subsequent change briefly
    // resumes for 1–2 presents to reveal the new look, then re-freezes. The activity owns the flag
    // (seeded from SharedPreferences in setupUI); this flow mirrors it for the drawer toggle.
    private val _reshadeLivePreview = MutableStateFlow(false)
    val reshadeLivePreview: StateFlow<Boolean> = _reshadeLivePreview
    fun setReshadeLivePreview(v: Boolean) { _reshadeLivePreview.value = v }

    fun interface BoolCallback { fun invoke(value: Boolean) }
    @JvmField var onReshadeLivePreviewChange: BoolCallback? = null

    // -------------------------------------------------------------------------
    // Paused / frozen state — single mirror of the activity's `isPaused`. Drives the centered
    // "Paused — tap to resume" box in the dialog host (covers BOTH the ReShade freeze-frame preview
    // and a normal manual Pause). The activity is the single source of truth; it writes this flow
    // whenever the guest is suspended/resumed. Tapping the box fires onRequestResume (full resume).
    // -------------------------------------------------------------------------
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    fun setPaused(v: Boolean) { _paused.value = v }
    @JvmField var onRequestResume: Runnable? = null

    // -------------------------------------------------------------------------
    // Vibration dialog
    // -------------------------------------------------------------------------
    private val _vibrationSlots = MutableStateFlow<List<Pair<String, Boolean>>>(emptyList())
    val vibrationSlots: StateFlow<List<Pair<String, Boolean>>> = _vibrationSlots

    fun setVibrationSlots(slots: List<Pair<String, Boolean>>) { _vibrationSlots.value = slots }

    fun interface VibrationSlotCallback { fun invoke(slot: Int, enabled: Boolean) }
    @JvmField var onVibrationSlotChanged: VibrationSlotCallback? = null

    // -------------------------------------------------------------------------
    // Debug / Logs dialog
    // -------------------------------------------------------------------------
    private val _logLines  = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines

    private val _logPaused = MutableStateFlow(false)
    val logPaused: StateFlow<Boolean> = _logPaused

    fun appendLog(line: String) {
        if (!_logPaused.value) {
            val current = _logLines.value
            // Cap at 3000 lines to avoid unbounded growth
            _logLines.value = if (current.size >= 3000) current.drop(1) + line else current + line
        }
    }
    fun clearLog()              { _logLines.value = emptyList() }
    fun setLogPaused(v: Boolean) { _logPaused.value = v }

    // -------------------------------------------------------------------------
    // Input Controls dialog
    // -------------------------------------------------------------------------
    private val _inputProfiles      = MutableStateFlow<List<String>>(emptyList())
    val inputProfiles: StateFlow<List<String>> = _inputProfiles

    private val _selectedProfileIdx = MutableStateFlow(0)  // 0 = Disabled
    val selectedProfileIdx: StateFlow<Int> = _selectedProfileIdx

    private val _showTouchscreen    = MutableStateFlow(false)
    val showTouchscreen: StateFlow<Boolean> = _showTouchscreen

    private val _timeoutEnabled     = MutableStateFlow(false)
    val timeoutEnabled: StateFlow<Boolean> = _timeoutEnabled

    private val _hapticsEnabled     = MutableStateFlow(false)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled

    fun setInputProfiles(profiles: List<String>) { _inputProfiles.value = profiles }
    fun setSelectedProfileIdx(v: Int)  { _selectedProfileIdx.value = v }
    fun setShowTouchscreen(v: Boolean) { _showTouchscreen.value = v }
    fun setTimeoutEnabled(v: Boolean)  { _timeoutEnabled.value = v }
    fun setHapticsEnabled(v: Boolean)  { _hapticsEnabled.value = v }

    fun interface InputConfirmCallback {
        fun invoke(profileIndex: Int, showTouchscreen: Boolean, timeout: Boolean, haptics: Boolean)
    }
    @JvmField var onInputControlsConfirm: InputConfirmCallback? = null
    @JvmField var onInputControlsSettings: Runnable? = null

    // -------------------------------------------------------------------------
    // Screen Effects dialog
    // -------------------------------------------------------------------------
    private val _seBrightness      = MutableStateFlow(0f)
    val seBrightness: StateFlow<Float> = _seBrightness

    private val _seContrast        = MutableStateFlow(0f)
    val seContrast: StateFlow<Float> = _seContrast

    private val _seGamma           = MutableStateFlow(1.0f)
    val seGamma: StateFlow<Float> = _seGamma

    private val _seFxaa            = MutableStateFlow(false)
    val seFxaa: StateFlow<Boolean> = _seFxaa

    private val _seCrt             = MutableStateFlow(false)
    val seCrt: StateFlow<Boolean> = _seCrt

    private val _seToon            = MutableStateFlow(false)
    val seToon: StateFlow<Boolean> = _seToon

    private val _seNtsc            = MutableStateFlow(false)
    val seNtsc: StateFlow<Boolean> = _seNtsc

    private val _seProfiles        = MutableStateFlow<List<String>>(emptyList())
    val seProfiles: StateFlow<List<String>> = _seProfiles

    private val _seSelectedProfile = MutableStateFlow(0)  // 0 = default
    val seSelectedProfile: StateFlow<Int> = _seSelectedProfile

    fun setSeBrightness(v: Float)   { _seBrightness.value = v }
    fun setSeContrast(v: Float)     { _seContrast.value = v }
    fun setSeGamma(v: Float)        { _seGamma.value = v }
    fun setSeFxaa(v: Boolean)       { _seFxaa.value = v }
    fun setSeCrt(v: Boolean)        { _seCrt.value = v }
    fun setSeToon(v: Boolean)       { _seToon.value = v }
    fun setSeNtsc(v: Boolean)       { _seNtsc.value = v }
    fun setSeProfiles(p: List<String>) { _seProfiles.value = p }
    fun setSeSelectedProfile(v: Int)   { _seSelectedProfile.value = v }

    fun interface ScreenEffectsApplyCallback {
        fun invoke(
            brightness: Float, contrast: Float, gamma: Float,
            fxaa: Boolean, crt: Boolean, toon: Boolean, ntsc: Boolean,
            profileIndex: Int
        )
    }
    @JvmField var onScreenEffectsApply: ScreenEffectsApplyCallback? = null

    fun interface StringCallback { fun invoke(value: String) }
    @JvmField var onSeAddProfile: StringCallback? = null
    @JvmField var onSeRemoveProfile: StringCallback? = null

    // -------------------------------------------------------------------------
    // Active Windows dialog
    // -------------------------------------------------------------------------
    data class ActiveWindow(
        val title: String,
        val className: String,
        val icon: Bitmap?,
        val screenshot: Bitmap?,
        val handle: Long
    )

    private val _awWindows = MutableStateFlow<List<ActiveWindow>>(emptyList())
    val awWindows: StateFlow<List<ActiveWindow>> = _awWindows

    fun setAwWindows(windows: List<ActiveWindow>) { _awWindows.value = windows }
    fun updateAwScreenshot(index: Int, bitmap: Bitmap) {
        val list = _awWindows.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(screenshot = bitmap)
            _awWindows.value = list
        }
    }

    fun interface WindowClickCallback { fun invoke(className: String, handle: Long) }
    @JvmField var onWindowClick: WindowClickCallback? = null

    // -------------------------------------------------------------------------
    // Task Manager dialog
    // -------------------------------------------------------------------------
    data class TmProcess(
        val index: Int,
        val pid: Int,
        val name: String,
        val formattedMemory: String,
        val wow64: Boolean,
        val icon: Bitmap?
    )

    private val _tmProcesses = MutableStateFlow<List<TmProcess>>(emptyList())
    val tmProcesses: StateFlow<List<TmProcess>> = _tmProcesses

    private val _tmCpuCores  = MutableStateFlow<List<String>>(emptyList())
    val tmCpuCores: StateFlow<List<String>> = _tmCpuCores

    private val _tmCpuTitle  = MutableStateFlow("CPU")
    val tmCpuTitle: StateFlow<String> = _tmCpuTitle

    private val _tmMemTitle  = MutableStateFlow("Memory")
    val tmMemTitle: StateFlow<String> = _tmMemTitle

    private val _tmMemInfo   = MutableStateFlow("")
    val tmMemInfo: StateFlow<String> = _tmMemInfo

    private val _tmCount     = MutableStateFlow(0)
    val tmCount: StateFlow<Int> = _tmCount

    fun setTmProcesses(list: List<TmProcess>) { _tmProcesses.value = list }
    fun setTmCpuCores(cores: List<String>)    { _tmCpuCores.value = cores }
    fun setTmCpuTitle(s: String)              { _tmCpuTitle.value = s }
    fun setTmMemTitle(s: String)              { _tmMemTitle.value = s }
    fun setTmMemInfo(s: String)               { _tmMemInfo.value = s }
    fun setTmCount(n: Int)                    { _tmCount.value = n }

    @JvmField var onTmRefresh: Runnable? = null
    @JvmField var onTmDismissed: Runnable? = null
    // Opens the New Task prompt. Wired to show the Compose NEW_TASK dialog (it used to fire a native
    // ContentDialog.prompt, which is invisible over the Vulkan/ASR fullscreen SurfaceView).
    @JvmField var onTmNewTask: Runnable? = null
    // Runs the command entered in the New Task dialog (same path the native prompt's OK ran:
    // winHandler.exec(command)).
    @JvmField var onTmNewTaskSubmit: TmStringCallback? = null

    fun interface TmStringCallback { fun invoke(name: String) }
    fun interface TmFrontCallback { fun invoke(name: String, pid: Int) }
    // Bring to Front needs the pid so the host can resolve the real X window + handle.
    @JvmField var onTmBringToFront: TmFrontCallback? = null
    @JvmField var onTmKillProcess: TmStringCallback? = null

    fun interface TmAffinityCallback { fun invoke(pid: Int, mask: Int) }
    @JvmField var onTmSetAffinity: TmAffinityCallback? = null

    fun interface FpsConfigCallback { fun invoke(config: String) }

    // -------------------------------------------------------------------------
    // Inline tab initialization callbacks
    // -------------------------------------------------------------------------
    @JvmField var onInitGraphicsTab: Runnable? = null

    // -------------------------------------------------------------------------
    // Reset — call when activity is destroyed or restarted
    // -------------------------------------------------------------------------
    fun reset() {
        _activeDialog.value    = ActiveDialog.NONE
        _magnifierVisible.value = false
        _magnifierZoom.value   = 1.0f
        _sgsrEnabled.value     = false
        _sgsrSharpness.value   = 50
        _hdrEnabled.value      = false
        _upscalerMode.value    = 0
        _vulkanSupported.value = false
        _casEnabled.value      = false
        _casSharpness.value    = 60
        _hdrVkEnabled.value    = false
        _upscaleSharpness.value = 75
        _debandEnabled.value   = false
        _debandStrength.value  = 100
        _vkBrightness.value    = 0f
        _vkContrast.value      = 0f
        _vkGamma.value         = 1.0f
        _vkFxaa.value          = false
        _vkToon.value          = false
        _vkCrt.value           = false
        _vkNtsc.value          = false
        _reshadeSupported.value = false
        _reshadeMasterEnabled.value = false
        _reshadeMode.value     = com.winlator.star.reshade.ReshadeLoadout.MODE_SOLO
        _reshadeLoadout.value  = emptyList()
        _reshadeLivePreview.value = false
        _paused.value          = false
        _vibrationSlots.value  = emptyList()
        _logLines.value        = emptyList()
        _logPaused.value       = false
        _inputProfiles.value   = emptyList()
        _selectedProfileIdx.value = 0
        _showTouchscreen.value = false
        _timeoutEnabled.value  = false
        _hapticsEnabled.value  = false
        _seBrightness.value    = 0f
        _seContrast.value      = 0f
        _seGamma.value         = 1.0f
        _seFxaa.value          = false
        _seCrt.value           = false
        _seToon.value          = false
        _seNtsc.value          = false
        _seProfiles.value      = emptyList()
        _seSelectedProfile.value = 0
        _awWindows.value       = emptyList()
        _tmProcesses.value     = emptyList()
        _tmCpuCores.value      = emptyList()
        _tmCpuTitle.value      = "CPU"
        _tmMemTitle.value      = "Memory"
        _tmMemInfo.value       = ""
        _tmCount.value         = 0
        onMagnifierZoom = null; onMagnifierHide = null
        onSgsrUpdate = null
        onUpscalerApply = null
        onCasApply = null; onHdrApply = null; onUpscaleSharpnessApply = null
        onDebandApply = null
        onVulkanScreenEffectsApply = null
        onReshadeApply = null
        onReshadeLivePreviewChange = null
        onRequestResume = null
        onVibrationSlotChanged = null
        onInputControlsConfirm = null; onInputControlsSettings = null
        onScreenEffectsApply = null; onSeAddProfile = null; onSeRemoveProfile = null
        onWindowClick = null
        onTmRefresh = null; onTmDismissed = null; onTmNewTask = null; onTmNewTaskSubmit = null
        onTmBringToFront = null; onTmKillProcess = null; onTmSetAffinity = null
        onInitGraphicsTab = null
    }
}
