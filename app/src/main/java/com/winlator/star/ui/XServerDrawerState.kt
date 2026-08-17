package com.winlator.star.ui

import com.winlator.star.perf.PerfRootApplier
import com.winlator.star.perf.PerformanceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class TabType {
    GRAPHICS, HUD, RESHADE, CONTROLS, ADVANCED, TASK_MANAGER, TV, AUDIO
}

object XServerDrawerState {

    private val _selectedTab = MutableStateFlow(TabType.GRAPHICS)
    val selectedTab: StateFlow<TabType> = _selectedTab

    fun selectTab(tab: TabType) { _selectedTab.value = tab }

    // Which of the Controls tab's segmented sub-tabs is showing: 0 = Touch, 1 = Mouse,
    // 2 = Vibration, 3 = Gyro. Lives here (not in a local remember) so it survives the drawer being
    // closed and reopened — mid-game tuning means reopening the same area over and over, and
    // snapping back to Touch each time is worse than the single long scroll it replaced.
    private val _controlsSubTab = MutableStateFlow(0)
    val controlsSubTab: StateFlow<Int> = _controlsSubTab

    fun setControlsSubTab(v: Int) { _controlsSubTab.value = v }

    private val _isPaused                = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean>     = _isPaused

    private val _isRelativeMouseMovement = MutableStateFlow(false)
    val isRelativeMouseMovement: StateFlow<Boolean> = _isRelativeMouseMovement

    private val _isMouseDisabled         = MutableStateFlow(false)
    val isMouseDisabled: StateFlow<Boolean> = _isMouseDisabled

    private val _moveCursorToTouchpoint  = MutableStateFlow(false)
    val moveCursorToTouchpoint: StateFlow<Boolean> = _moveCursorToTouchpoint

    // Per-gesture config, shown in the Controls > Mouse pane whenever Cursor to Touch is on. Each
    // gesture is independently switchable because which of them is welcome is per-game: an RTS wants
    // both, a mouse-look shooter wants neither. Seeded from prefs and pushed to TouchpadView live.
    private val _gestureDragSelect = MutableStateFlow(true)
    val gestureDragSelect: StateFlow<Boolean> = _gestureDragSelect

    private val _gestureLongPressRightClick = MutableStateFlow(true)
    val gestureLongPressRightClick: StateFlow<Boolean> = _gestureLongPressRightClick

    private val _gestureLongPressMs = MutableStateFlow(300)
    val gestureLongPressMs: StateFlow<Int> = _gestureLongPressMs

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

    // bionic-fg interpolation model (0-3). Switchable live: the layer rebuilds its framegen
    // context on a model change (layer.cpp needsContextRebuild), same as a multiplier change.
    private val _frameGenModel = MutableStateFlow(0)
    val frameGenModel: StateFlow<Int> = _frameGenModel

    // Which FG engine the container runs: "off" / "bionic" / "lsfg". Shown as a label above the
    // in-game multiplier/flow controls so the user knows which engine they're tuning.
    private val _frameGenEngine = MutableStateFlow("off")
    val frameGenEngine: StateFlow<String> = _frameGenEngine

    // ── Live Present Mode selector (Graphics tab, Vulkan renderer only) ──
    // presentMode = the EFFECTIVE/displayed mode ("fifo"/"mailbox"/"immediate"). While frame gen is
    // multiplying the activity forces "mailbox" (effectivePresentMode()) and sets presentModeLocked =
    // true; the drawer keeps the chips interactive but BLOCKS FIFO/Immediate taps (Mailbox is required
    // for FG's extra presents) WITHOUT touching the user's saved preference — so presentMode snaps back
    // to their mode when FG turns off. rendererIsVulkan gates the whole section (OpenGL/SurfaceFlinger
    // have no present-mode control). onPresentModeChange persists + applies the user's pick live.
    private val _presentMode = MutableStateFlow("fifo")
    val presentMode: StateFlow<String> = _presentMode
    fun setPresentMode(v: String) { _presentMode.value = v }

    private val _presentModeLocked = MutableStateFlow(false)
    val presentModeLocked: StateFlow<Boolean> = _presentModeLocked
    fun setPresentModeLocked(v: Boolean) { _presentModeLocked.value = v }

    private val _rendererIsVulkan = MutableStateFlow(false)
    val rendererIsVulkan: StateFlow<Boolean> = _rendererIsVulkan
    fun setRendererIsVulkan(v: Boolean) { _rendererIsVulkan.value = v }

    // Fired when the user taps a present-mode chip while FG is OFF: the activity persists the chosen
    // mode (per-game shortcut override, else the container), re-applies it live (applyEffectivePresentMode)
    // and echoes the effective mode back via setPresentMode. Consumer<String> so Java assigns `mode -> {}`.
    @JvmField var onPresentModeChange: java.util.function.Consumer<String>? = null

    // lsfg-vk only: performance_mode (lower interpolation quality, higher FPS — for low-end devices).
    // Seeded from the container when the drawer opens; toggled live from the FG pane (rewrites conf.toml).
    private val _lsfgPerformanceMode = MutableStateFlow(false)
    val lsfgPerformanceMode: StateFlow<Boolean> = _lsfgPerformanceMode

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

    // ---- External display / TV (Version A) --------------------------------------------------------
    // Whether a TV/external presentation display is currently connected. Gates the TV tab's visibility.
    private val _tvConnected = MutableStateFlow(false)
    val tvConnected: StateFlow<Boolean> = _tvConnected
    fun setTvConnected(v: Boolean) { _tvConnected.value = v }

    // Human-readable name of the connected external display (for the TV tab readout).
    private val _tvDisplayName = MutableStateFlow("")
    val tvDisplayName: StateFlow<String> = _tvDisplayName
    fun setTvDisplayName(v: String) { _tvDisplayName.value = v }

    // "Play on TV" master switch and "Auto-switch on connect" (both default on). When auto-switch is
    // off, connecting a display only notifies and waits for the user to move the game from this tab.
    private val _tvPlayOnTv = MutableStateFlow(true)
    val tvPlayOnTv: StateFlow<Boolean> = _tvPlayOnTv
    fun setTvPlayOnTv(v: Boolean) { _tvPlayOnTv.value = v }

    private val _tvAutoSwap = MutableStateFlow(true)
    val tvAutoSwap: StateFlow<Boolean> = _tvAutoSwap
    fun setTvAutoSwap(v: Boolean) { _tvAutoSwap.value = v }

    // Whether the game is currently shown on the external display (drives the Move / Bring-back button).
    private val _tvGameOnExternal = MutableStateFlow(false)
    val tvGameOnExternal: StateFlow<Boolean> = _tvGameOnExternal
    fun setTvGameOnExternal(v: Boolean) { _tvGameOnExternal.value = v }

    // Activity wires these to ExternalDisplayController. Consumer<Boolean> / Runnable for easy Java assign.
    @JvmField var onTvPlayOnTvChange: java.util.function.Consumer<Boolean>? = null
    @JvmField var onTvAutoSwapChange: java.util.function.Consumer<Boolean>? = null
    @JvmField var onMoveToTv: Runnable? = null
    @JvmField var onBringBackFromTv: Runnable? = null
    // Rebuild the guest audio sink (fixes silence after backgrounding / HDMI route changes).
    @JvmField var onResetAudio: Runnable? = null
    // Re-apply the audio config live in-game after the preset/fine-tune dialog saves (sink recreate
    // reads the just-written banner_audio prefs). Guest latency change still needs a relaunch.
    @JvmField var onReapplyAudio: Runnable? = null
    // Engine that actually launched ("PulseAudio" / "ALSA"), shown at the top of the in-game AUDIO tab
    // so the user knows which engine these settings hit. Set by the activity at launch.
    @JvmField var audioDriverLabel: String = ""
    // Engine id ("alsa" / "pulseaudio") — selects the per-engine prefs file the in-game tab reads/writes.
    @JvmField var audioDriverId: String = ""

    // TV output display modes (resolution + refresh rate). Seeded by the activity from the connected
    // display's getSupportedModes() so the user can switch off e.g. 4K@30 to 1080p@60. id 0 = default.
    data class TvDisplayMode(val id: Int, val label: String)

    private val _tvModes = MutableStateFlow<List<TvDisplayMode>>(emptyList())
    val tvModes: StateFlow<List<TvDisplayMode>> = _tvModes
    fun setTvModes(v: List<TvDisplayMode>) { _tvModes.value = v }

    private val _tvCurrentModeId = MutableStateFlow(0)
    val tvCurrentModeId: StateFlow<Int> = _tvCurrentModeId
    fun setTvCurrentModeId(v: Int) { _tvCurrentModeId.value = v }

    // Fired with the chosen Display.Mode id (0 = system default): activity → controller.setPreferredModeId.
    @JvmField var onTvModeChange: java.util.function.Consumer<Int>? = null

    // Read-only HDR capability of the connected display (e.g. "HDR10, Dolby Vision"); "" if none.
    private val _tvHdr = MutableStateFlow("")
    val tvHdr: StateFlow<String> = _tvHdr
    fun setTvHdr(v: String) { _tvHdr.value = v }

    // Wireless casting (screen mirroring to a Google TV / Chromecast / Miracast) is available on this
    // device (Google Cast / Wireless Display present). Gates the TV tab so a "Cast" button is reachable
    // even with no wired display connected. onOpenCastPicker opens the system cast/mirror device chooser.
    private val _castSupported = MutableStateFlow(false)
    val castSupported: StateFlow<Boolean> = _castSupported
    fun setCastSupported(v: Boolean) { _castSupported.value = v }
    @JvmField var onOpenCastPicker: Runnable? = null

    // ---- TV Options v2 (NEW behaviours, TV-scoped via tv.* container extras) ----------------------
    // Overscan / safe-area inset for TVs that crop edges: 0..8 % padding on GamePresentation.root.
    private val _tvOverscan = MutableStateFlow(0)
    val tvOverscan: StateFlow<Int> = _tvOverscan
    fun setTvOverscan(v: Int) { _tvOverscan.value = v.coerceIn(0, 8) }
    @JvmField var onTvOverscanChange: java.util.function.IntConsumer? = null

    // Dim the handheld screen while the game is on the TV (battery/heat saver). Default on.
    private val _tvDimHandheld = MutableStateFlow(true)
    val tvDimHandheld: StateFlow<Boolean> = _tvDimHandheld
    fun setTvDimHandheld(v: Boolean) { _tvDimHandheld.value = v }
    @JvmField var onTvDimHandheldChange: java.util.function.Consumer<Boolean>? = null

    // Audio output preference while on TV: 0 = follow system, 1 = force TV/HDMI, 2 = force handheld.
    // Experimental — routes the Android media output; the guest AAudio sink may not always follow.
    private val _tvAudioOut = MutableStateFlow(0)
    val tvAudioOut: StateFlow<Int> = _tvAudioOut
    fun setTvAudioOut(v: Int) { _tvAudioOut.value = v }
    @JvmField var onTvAudioOutChange: java.util.function.IntConsumer? = null

    // TV render resolution: 0 = Match TV, 1 = Match handheld, 2 = 1080p, 3 = 1440p. Applied on next
    // launch (the X server resolution is fixed at bring-up), so this only stores the choice + notifies.
    private val _tvRenderRes = MutableStateFlow(0)
    val tvRenderRes: StateFlow<Int> = _tvRenderRes
    fun setTvRenderRes(v: Int) { _tvRenderRes.value = v }
    @JvmField var onTvRenderResChange: java.util.function.IntConsumer? = null

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

    // ── Power-user performance toggles (non-root; live-toggleable in-game) ──
    // Seeded from the resolved container/shortcut config in setupUI; each has an onXxx callback the
    // Activity assigns to apply + persist live.
    private val _sustainedPerfMode = MutableStateFlow(false)
    val sustainedPerfMode: StateFlow<Boolean> = _sustainedPerfMode

    private val _perfPriorityBoost = MutableStateFlow(false)
    val perfPriorityBoost: StateFlow<Boolean> = _perfPriorityBoost

    private val _preferBigCores = MutableStateFlow(false)
    val preferBigCores: StateFlow<Boolean> = _preferBigCores

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
    // Fired when any gesture chip/slider under the Cursor to Touch cog changes; the activity reads
    // the flows above, persists them, and pushes the set to the live TouchpadView.
    @JvmField var onGestureConfigChange:    Runnable? = null
    @JvmField var onRelativeMouseMovement:  Runnable? = null
    @JvmField var onDisableMouse:           Runnable? = null
    @JvmField var onNativeRenderingToggle: Runnable? = null

    // Whether the active renderer supports Native Rendering (direct scanout). True for Vulkan;
    // false for OpenGL (GL scanout is disabled for now — bespoke path, unresolved brightness).
    // Set once at launch from the renderer type; drives whether the drawer shows the toggle.
    private val _nativeRenderingSupported = MutableStateFlow(true)
    @get:JvmName("getNativeRenderingSupportedState")
    val nativeRenderingSupported: StateFlow<Boolean> = _nativeRenderingSupported
    fun setNativeRenderingSupported(v: Boolean) { _nativeRenderingSupported.value = v }
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

    // Fired when a power-user performance toggle changes in the drawer; the Activity reads the flow,
    // applies it live (sustained-perf window flag / thread priority / big-core affinity) and persists.
    @JvmField var onSustainedPerfModeChange: Runnable? = null
    @JvmField var onPerfPriorityBoostChange: Runnable? = null
    @JvmField var onPreferBigCoresChange:    Runnable? = null

    var onCursorExpandedChanged: ((Boolean) -> Unit)? = null

    // Setters called from Java
    fun setIsPaused(v: Boolean)                { _isPaused.value = v }
    fun setIsRelativeMouseMovement(v: Boolean) { _isRelativeMouseMovement.value = v }
    fun setIsMouseDisabled(v: Boolean)         { _isMouseDisabled.value = v }
    fun setMoveCursorToTouchpoint(v: Boolean)  { _moveCursorToTouchpoint.value = v }
    fun setGestureDragSelect(v: Boolean)          { _gestureDragSelect.value = v }
    fun setGestureLongPressRightClick(v: Boolean) { _gestureLongPressRightClick.value = v }
    fun setGestureLongPressMs(v: Int)             { _gestureLongPressMs.value = v }
    fun getGestureDragSelectValue(): Boolean          = _gestureDragSelect.value
    fun getGestureLongPressRightClickValue(): Boolean = _gestureLongPressRightClick.value
    fun getGestureLongPressMsValue(): Int             = _gestureLongPressMs.value
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
    fun setFrameGenModel(v: Int)           { _frameGenModel.value = v.coerceIn(0, 4) }
    fun setFrameGenEngine(v: String)       { _frameGenEngine.value = v }
    fun setLsfgPerformanceMode(v: Boolean) { _lsfgPerformanceMode.value = v }
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

    fun setSustainedPerfMode(v: Boolean) { _sustainedPerfMode.value = v }
    fun setPerfPriorityBoost(v: Boolean) { _perfPriorityBoost.value = v }
    fun setPreferBigCores(v: Boolean)    { _preferBigCores.value = v }

    // ── Two-way sync + per-game override tracking (unified for all 9 perf keys) ──
    // overriddenKeys = the perf keys the running game overrides per-game (shortcut.hasExtra at launch,
    // updated as the user flips/resets). A key IN the set is pinned to its per-game value; a key NOT in
    // the set mirrors the App Settings global default live. Drives the override/global indicator +
    // reset affordance in the drawer, and gates the global->drawer mirror so an override isn't clobbered.
    private val syncScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var perfSyncJob: Job? = null
    private val _overriddenKeys = MutableStateFlow<Set<String>>(emptySet())
    val overriddenKeys: StateFlow<Set<String>> = _overriddenKeys
    fun isOverridden(key: String): Boolean = key in _overriddenKeys.value

    fun startPerfSync(overridden: Set<String>) {
        _overriddenKeys.value = overridden
        perfSyncJob?.cancel()
        perfSyncJob = syncScope.launch {
            launch { PerformanceSettings.sustainedPerfMode.collect { if ("sustainedPerfMode" !in _overriddenKeys.value) _sustainedPerfMode.value = it } }
            launch { PerformanceSettings.perfPriorityBoost.collect { if ("perfPriorityBoost" !in _overriddenKeys.value) _perfPriorityBoost.value = it } }
            launch { PerformanceSettings.preferBigCores.collect { if ("preferBigCores" !in _overriddenKeys.value) _preferBigCores.value = it } }
            for (key in PerfRootApplier.ROOT_KEYS) {
                launch { PerformanceSettings.rootDefaultFlow(key).collect { v -> if (key !in _overriddenKeys.value) _rootToggles.value = _rootToggles.value + (key to v) } }
            }
        }
    }

    fun markOverridden(key: String) { _overriddenKeys.value = _overriddenKeys.value + key }

    // A key re-inherits the global default: drop it from the overridden set AND immediately reflect the
    // current global value in the drawer flow/map so the UI updates without waiting for the next emit.
    fun markInherited(key: String) {
        _overriddenKeys.value = _overriddenKeys.value - key
        when (key) {
            "sustainedPerfMode" -> _sustainedPerfMode.value = PerformanceSettings.sustainedPerfMode.value
            "perfPriorityBoost" -> _perfPriorityBoost.value = PerformanceSettings.perfPriorityBoost.value
            "preferBigCores"    -> _preferBigCores.value = PerformanceSettings.preferBigCores.value
            else -> _rootToggles.value = _rootToggles.value + (key to PerformanceSettings.rootDefaultValue(key))
        }
    }

    // ── Root-tier toggles (in-game). Keyed by PerfRootApplier.ROOT_KEYS. The Activity seeds the
    // effective values and applies live; the drawer displays this map and fires onRootToggleChange. ──
    private val _rootToggles = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val rootToggles: StateFlow<Map<String, Boolean>> = _rootToggles
    fun setRootToggles(m: Map<String, Boolean>) { _rootToggles.value = m }
    fun setRootToggle(key: String, v: Boolean) { _rootToggles.value = _rootToggles.value + (key to v) }

    // Live readouts (governor / GPU MHz / SoC temp / fan RPM), refreshed by the Activity while the
    // root section is open. Keyed by a small readout id.
    private val _rootReadouts = MutableStateFlow<Map<String, String>>(emptyMap())
    val rootReadouts: StateFlow<Map<String, String>> = _rootReadouts
    fun setRootReadouts(m: Map<String, String>) { _rootReadouts.value = m }

    // Fired when a root toggle flips: the Activity writes the per-game override (or the global default
    // for a container-direct launch) and applies it live via PerfRootApplier.
    @JvmField var onRootToggleChange: java.util.function.BiConsumer<String, Boolean>? = null
    // TIER 1 — one-shot drop-file-caches action (drop_caches; light, near-invisible RAM).
    @JvmField var onFreeMemory: Runnable? = null
    // TIER 2 — one-shot deep clean (root-only): `am kill-all` frees real RAM without touching the game.
    @JvmField var onDeepClean: Runnable? = null
    // Drawer asks the Activity to refresh the live readouts (cheap sysfs/HudMetrics reads).
    @JvmField var onRootReadoutPoll: Runnable? = null
    // Reset ONE perf key's per-game override so it re-inherits the global default (Activity removes the
    // shortcut extra, marks inherited, re-applies the global value live).
    @JvmField var onResetPerfKey: java.util.function.Consumer<String>? = null
    // Reset ALL 9 perf keys for this game so it fully re-inherits.
    @JvmField var onResetAllPerf: Runnable? = null

    fun toggleFpsExpanded() { _fpsExpanded.value = !_fpsExpanded.value }

    fun reset() {
        _selectedTab.value = TabType.GRAPHICS
        _controlsSubTab.value = 0
        _isPaused.value = false
        _isRelativeMouseMovement.value = false
        _isMouseDisabled.value = false
        _moveCursorToTouchpoint.value = false
        _gestureDragSelect.value = true
        _gestureLongPressRightClick.value = true
        _gestureLongPressMs.value = 300
        _showLogs.value = false
        _showMagnifier.value = true
        _nativeRenderingEnabled.value = false
        _nativeRenderingSupported.value = true
        _runtimeBackend.value = RuntimeBackend()
        _bionicFgActive.value = false
        _frameGenEnabled.value = false
        _frameGenMultiplier.value = 2
        _frameGenFlowScale.value = 0.6f
        _frameGenModel.value = 0
        _frameGenEngine.value = "off"
        _presentMode.value = "fifo"
        _presentModeLocked.value = false
        _rendererIsVulkan.value = false
        _lsfgPerformanceMode.value = false
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
        _sustainedPerfMode.value = false
        _perfPriorityBoost.value = false
        _preferBigCores.value = false
        perfSyncJob?.cancel(); perfSyncJob = null
        _overriddenKeys.value = emptySet()
        _rootToggles.value = emptyMap()
        _rootReadouts.value = emptyMap()
        onRootToggleChange = null; onFreeMemory = null; onDeepClean = null; onRootReadoutPoll = null
        onResetPerfKey = null; onResetAllPerf = null
        onClose = null; onKeyboard = null; onInputControls = null
        onScreenEffects = null; onGraphicEngine = null; onVibration = null
        onToggleFullscreen = null; onSetFullscreenMode = null; onPauseResume = null; onPipMode = null
        onActiveWindows = null; onTaskManager = null; onMagnifier = null
        onLogs = null; onExit = null; onMoveCursorToTouchpoint = null; onGestureConfigChange = null
        onRelativeMouseMovement = null; onDisableMouse = null
        onNativeRenderingToggle = null; onFpsConfigApply = null
        onBionicFgConfigChange = null; onFpsLimitChange = null
        onPresentModeChange = null
        onMatchRefreshChange = null
        onManualRefreshChange = null
        onRefreshRatePoll = null
        onOverlayOpacityChange = null
        onControlsColorChange = null
        onSustainedPerfModeChange = null
        onPerfPriorityBoostChange = null
        onPreferBigCoresChange = null
        onCursorExpandedChanged = null
    }
}
