package com.winlator.star.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TabType {
    GRAPHICS, HUD, CONTROLS, ADVANCED, TASK_MANAGER
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

    private val _fpsExpanded = MutableStateFlow(false)
    val fpsExpanded: StateFlow<Boolean> = _fpsExpanded

    private val _fpsConfig = MutableStateFlow("")
    val fpsConfig: StateFlow<String> = _fpsConfig

    private val _visualStyle = MutableStateFlow("GAMEHUB")
    val visualStyle: StateFlow<String> = _visualStyle

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

    fun setFpsExpanded(v: Boolean) { _fpsExpanded.value = v }
    fun setFpsConfig(v: String) { _fpsConfig.value = v }
    fun setVisualStyle(v: String) { _visualStyle.value = v }
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
        _cursorExpanded.value = false
        _fpsExpanded.value = false
        _fpsConfig.value = ""
        _visualStyle.value = "GAMEHUB"
        onClose = null; onKeyboard = null; onInputControls = null
        onScreenEffects = null; onGraphicEngine = null; onVibration = null
        onToggleFullscreen = null; onPauseResume = null; onPipMode = null
        onActiveWindows = null; onTaskManager = null; onMagnifier = null
        onLogs = null; onExit = null; onMoveCursorToTouchpoint = null
        onRelativeMouseMovement = null; onDisableMouse = null
        onNativeRenderingToggle = null; onFpsConfigApply = null
        onCursorExpandedChanged = null
    }
}
