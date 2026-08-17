package com.winlator.star.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TabType {
    GRAPHICS, HUD, RESHADE, CONTROLS, ADVANCED, TASK_MANAGER
}

object XServerDrawerState {

    private val _selectedTab = MutableStateFlow(TabType.GRAPHICS)
    val selectedTab: StateFlow<TabType> = _selectedTab

    fun selectTab(tab: TabType) { _selectedTab.value = tab }

    private val _isPaused                = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean>     = _isPaused

    private val _isRelativeMouseMovement = MutableStateFlow(false)
    val isRelativeMouseMovement: StateFlow<Boolean> = _isRelativeMouseMovement

    private val _isMouseDisabled         = MutableStateFlow(false)
    val isMouseDisabled: StateFlow<Boolean> = _isMouseDisabled

    private val _moveCursorToTouchpoint  = MutableStateFlow(false)
    val moveCursorToTouchpoint: StateFlow<Boolean> = _moveCursorToTouchpoint

    private val _showLogs                = MutableStateFlow(false)
    val showLogs: StateFlow<Boolean>     = _showLogs

    private val _showMagnifier           = MutableStateFlow(true)
    val showMagnifier: StateFlow<Boolean> = _showMagnifier

    private val _cursorExpanded          = MutableStateFlow(false)
    val cursorExpanded: StateFlow<Boolean> = _cursorExpanded

    private val _nativeRenderingEnabled = MutableStateFlow(false)
    @get:JvmName("getNativeRenderingEnabledState")
    val nativeRenderingEnabled: StateFlow<Boolean> = _nativeRenderingEnabled

    // Read-only runtime-backend chip (Graphics tab header): arch + translator, plus the FEX unixlib
    // mode. arch+translator are seeded immediately at launch; the unixlib segment fills in once the
    // maps read resolves (~2-3s after the guest is up). Populated by XServerDisplayActivity.
    private val _runtimeBackend = MutableStateFlow(RuntimeBackend())
    val runtimeBackend: StateFlow<RuntimeBackend> = _runtimeBackend

    // bionic-fg live controls (frame generation + fps limiter), driven from the in-game drawer.
    // bionicFgActive = the layer is loaded this session (FG or limiter was on at launch); live
    // tuning only takes effect when true.
    private val _bionicFgActive = MutableStateFlow(false)
    val bionicFgActive: StateFlow<Boolean> = _bionicFgActive

    private val _frameGenEnabled = MutableStateFlow(false)
    val frameGenEnabled: StateFlow<Boolean> = _frameGenEnabled

    // 0 = Off, else 2/3/4.
    private val _frameGenMultiplier = MutableStateFlow(2)
    val frameGenMultiplier: StateFlow<Int> = _frameGenMultiplier

    private val _frameGenFlowScale = MutableStateFlow(0.6f)
    val frameGenFlowScale: StateFlow<Float> = _frameGenFlowScale

    // Which FG engine the container runs: "off" / "bionic" / "lsfg". Shown as a label above the
    // in-game multiplier/flow controls so the user knows which engine they're tuning.
    private val _frameGenEngine = MutableStateFlow("off")
    val frameGenEngine: StateFlow<String> = _frameGenEngine

    private val _fpsLimiterEnabled = MutableStateFlow(false)
    val fpsLimiterEnabled: StateFlow<Boolean> = _fpsLimiterEnabled

    private val _fpsLimit = MutableStateFlow(60)
    val fpsLimit: StateFlow<Int> = _fpsLimit

    // VRR / refresh-rate matching: vote the panel refresh rate to follow the game's FPS. Default ON
    // (safe — only votes a rate while the FPS limiter is actually capping). Complementary to the
    // limiter (which caps the producer/render rate).
    private val _matchRefreshRate = MutableStateFlow(true)
    val matchRefreshRate: StateFlow<Boolean> = _matchRefreshRate

    // Whether the active display can actually do VRR (refresh-rate matching). Default true (assume
    // capable until the activity seeds the real value in setupUI) so the toggle doesn't flicker.
    private val _vrrSupported = MutableStateFlow(true)
    val vrrSupported: StateFlow<Boolean> = _vrrSupported

    // Manual refresh-rate lock (Hz), used when matchRefreshRate (Auto) is OFF. 0 = none/native.
    private val _manualRefreshRate = MutableStateFlow(0)
    val manualRefreshRate: StateFlow<Int> = _manualRefreshRate

    // Distinct refresh rates the active display supports (ascending). Empty = nothing to pick (the
    // panel has a single rate); seeded by the activity in setupUI.
    private val _supportedRefreshRates = MutableStateFlow<List<Int>>(emptyList())
    val supportedRefreshRates: StateFlow<List<Int>> = _supportedRefreshRates

    // Live (actual) display refresh rate in Hz; 0 = unknown. Updated by the activity from a display
    // listener so the readout can show what Auto landed on while the manual slider is greyed.
    private val _currentRefreshRate = MutableStateFlow(0)
    val currentRefreshRate: StateFlow<Int> = _currentRefreshRate

    // Current fullscreen aspect-ratio mode (#71): Container.FULLSCREEN_OFF/FIT/STRETCH. Shown next
    // to the in-game "Toggle Fullscreen" row so the user sees which mode the cycle landed on.
    private val _fullscreenMode = MutableStateFlow(0)
    val fullscreenMode: StateFlow<Int> = _fullscreenMode

    private val _fpsExpanded = MutableStateFlow(false)
    val fpsExpanded: StateFlow<Boolean> = _fpsExpanded

    private val _fpsConfig = MutableStateFlow("")
    val fpsConfig: StateFlow<String> = _fpsConfig

    // On-screen controls overlay opacity (0..1), tuned live from the Controls tab.
    private val _overlayOpacity = MutableStateFlow(0.75f)
    val overlayOpacity: StateFlow<Float> = _overlayOpacity

    // Per-profile on-screen controls accent. When controlsFollowTheme is true the controls follow
    // the app theme accent; when false they use controlsAccentColor (ARGB). Both mirror the active
    // ControlsProfile and are seeded/persisted by the activity (see onControlsColorChange).
    private val _controlsFollowTheme = MutableStateFlow(true)
    val controlsFollowTheme: StateFlow<Boolean> = _controlsFollowTheme

    private val _controlsAccentColor = MutableStateFlow(0xFF0055FF.toInt())
    val controlsAccentColor: StateFlow<Int> = _controlsAccentColor

    // Callbacks wired by XServerDisplayActivity.
    // @JvmField exposes these as public fields so Java can assign them directly.
    // Runnable avoids the kotlin.Unit return-type mismatch for Java void lambdas.
    @JvmField var onClose:                  Runnable? = null
    @JvmField var onKeyboard:               Runnable? = null
    @JvmField var onInputControls:          Runnable? = null
    @JvmField var onScreenEffects:          Runnable? = null
    @JvmField var onGraphicEngine:          Runnable? = null
    @JvmField var onVibration:              Runnable? = null
    @JvmField var onToggleFullscreen:       Runnable? = null
    // Direct set of the fullscreen aspect-ratio mode (#71 Stage 2): the drawer's segmented
    // selector picks a mode without cycling and WITHOUT closing the drawer. Takes the target
    // Container.FULLSCREEN_* value.
    @JvmField var onSetFullscreenMode:      java.util.function.IntConsumer? = null
    @JvmField var onPauseResume:            Runnable? = null
    @JvmField var onPipMode:               Runnable? = null
    @JvmField var onActiveWindows:          Runnable? = null
    @JvmField var onTaskManager:            Runnable? = null
    @JvmField var onMagnifier:              Runnable? = null
    @JvmField var onLogs:                   Runnable? = null
    @JvmField var onExit:                   Runnable? = null
    @JvmField var onMoveCursorToTouchpoint: Runnable? = null
    @JvmField var onRelativeMouseMovement:  Runnable? = null
    @JvmField var onDisableMouse:           Runnable? = null
    @JvmField var onNativeRenderingToggle: Runnable? = null
    @JvmField var onFpsConfigApply: XServerDialogState.FpsConfigCallback? = null

    // Fired after any bionic-fg control changes; the handler reads the StateFlows above and
    // rewrites conf.toml (hot-reload) + persists. Runnable avoids Java void-lambda mismatch.
    @JvmField var onBionicFgConfigChange: Runnable? = null
    // FPS limiter is a standalone host-side present pacer, independent of frame gen. Fired when the
    // in-game Limit FPS toggle/slider changes; the activity applies it to the host renderer live.
    @JvmField var onFpsLimitChange: Runnable? = null
    // Fired when the in-game "Match refresh rate to FPS" toggle changes; the activity persists it
    // and re-applies the panel refresh-rate vote (applyVrr) live.
    @JvmField var onMatchRefreshChange: Runnable? = null
    // Fired when the in-game manual refresh-rate chip selection changes (Auto OFF); the activity
    // persists it and re-applies the panel refresh-rate vote (reapplyVrr) live.
    @JvmField var onManualRefreshChange: Runnable? = null
    // Fired when the HUD/FPS drawer tab opens; the activity re-reads the live display refresh rate
    // so the "Rate" readout is fresh on open (the display listener keeps it current thereafter).
    @JvmField var onRefreshRatePoll: Runnable? = null
    // Fired when the Controls-tab opacity slider moves; the activity reads overlayOpacity,
    // applies it to the live InputControlsView and persists the pref.
    @JvmField var onOverlayOpacityChange: Runnable? = null
    // Fired when the Controls-tab "Follow app theme" toggle or custom color changes; the activity
    // reads controlsFollowTheme/controlsAccentColor, writes them onto the ACTIVE ControlsProfile,
    // saves it, and invalidates the InputControlsView for a live redraw.
    @JvmField var onControlsColorChange: Runnable? = null

    var onCursorExpandedChanged: ((Boolean) -> Unit)? = null

    // Setters called from Java
    fun setIsPaused(v: Boolean)                { _isPaused.value = v }
    fun setIsRelativeMouseMovement(v: Boolean) { _isRelativeMouseMovement.value = v }
    fun setIsMouseDisabled(v: Boolean)         { _isMouseDisabled.value = v }
    fun setMoveCursorToTouchpoint(v: Boolean)  { _moveCursorToTouchpoint.value = v }
    fun setShowLogs(v: Boolean)                { _showLogs.value = v }
    fun setShowMagnifier(v: Boolean)           { _showMagnifier.value = v }
    fun setCursorExpanded(v: Boolean)          { _cursorExpanded.value = v }

    fun toggleCursorExpanded() {
        val next = !_cursorExpanded.value
        _cursorExpanded.value = next
        onCursorExpandedChanged?.invoke(next)
    }

    fun setNativeRenderingEnabled(v: Boolean) { _nativeRenderingEnabled.value = v }
    fun getNativeRenderingEnabled(): Boolean = _nativeRenderingEnabled.value

    fun setRuntimeBackend(v: RuntimeBackend) { _runtimeBackend.value = v }

    fun setFullscreenMode(v: Int) { _fullscreenMode.value = v }

    fun setBionicFgActive(v: Boolean)      { _bionicFgActive.value = v }
    fun setFrameGenEnabled(v: Boolean)     { _frameGenEnabled.value = v }
    fun setFrameGenMultiplier(v: Int)      { _frameGenMultiplier.value = v }
    fun setFrameGenFlowScale(v: Float)     { _frameGenFlowScale.value = v }
    fun setFrameGenEngine(v: String)       { _frameGenEngine.value = v }
    fun setFpsLimiterEnabled(v: Boolean)   { _fpsLimiterEnabled.value = v }
    fun setFpsLimit(v: Int)                { _fpsLimit.value = v }
    fun setMatchRefreshRate(v: Boolean)    { _matchRefreshRate.value = v }
    fun setVrrSupported(v: Boolean)        { _vrrSupported.value = v }
    fun setManualRefreshRate(v: Int)       { _manualRefreshRate.value = v }
    fun setSupportedRefreshRates(v: List<Int>) { _supportedRefreshRates.value = v }
    fun setCurrentRefreshRate(v: Int)      { _currentRefreshRate.value = v }

    fun setFpsExpanded(v: Boolean) { _fpsExpanded.value = v }
    fun setFpsConfig(v: String) { _fpsConfig.value = v }
    fun setOverlayOpacity(v: Float) { _overlayOpacity.value = v }
    fun getOverlayOpacityValue(): Float = _overlayOpacity.value
    fun setControlsFollowTheme(v: Boolean) { _controlsFollowTheme.value = v }
    fun getControlsFollowThemeValue(): Boolean = _controlsFollowTheme.value
    fun setControlsAccentColor(v: Int) { _controlsAccentColor.value = v }
    fun getControlsAccentColorValue(): Int = _controlsAccentColor.value
    fun toggleFpsExpanded() { _fpsExpanded.value = !_fpsExpanded.value }

    fun reset() {
        _selectedTab.value = TabType.GRAPHICS
        _isPaused.value = false
        _isRelativeMouseMovement.value = false
        _isMouseDisabled.value = false
        _moveCursorToTouchpoint.value = false
        _showLogs.value = false
        _showMagnifier.value = true
        _nativeRenderingEnabled.value = false
        _runtimeBackend.value = RuntimeBackend()
        _bionicFgActive.value = false
        _frameGenEnabled.value = false
        _frameGenMultiplier.value = 2
        _frameGenFlowScale.value = 0.6f
        _frameGenEngine.value = "off"
        _fpsLimiterEnabled.value = false
        _fpsLimit.value = 60
        _matchRefreshRate.value = true
        _vrrSupported.value = true
        _manualRefreshRate.value = 0
        _supportedRefreshRates.value = emptyList()
        _currentRefreshRate.value = 0
        _cursorExpanded.value = false
        _fpsExpanded.value = false
        _fpsConfig.value = ""
        _overlayOpacity.value = 0.75f
        _controlsFollowTheme.value = true
        _controlsAccentColor.value = 0xFF0055FF.toInt()
        onClose = null; onKeyboard = null; onInputControls = null
        onScreenEffects = null; onGraphicEngine = null; onVibration = null
        onToggleFullscreen = null; onSetFullscreenMode = null; onPauseResume = null; onPipMode = null
        onActiveWindows = null; onTaskManager = null; onMagnifier = null
        onLogs = null; onExit = null; onMoveCursorToTouchpoint = null
        onRelativeMouseMovement = null; onDisableMouse = null
        onNativeRenderingToggle = null; onFpsConfigApply = null
        onBionicFgConfigChange = null; onFpsLimitChange = null
        onMatchRefreshChange = null
        onManualRefreshChange = null
        onRefreshRatePoll = null
        onOverlayOpacityChange = null
        onControlsColorChange = null
        onCursorExpandedChanged = null
    }
}
