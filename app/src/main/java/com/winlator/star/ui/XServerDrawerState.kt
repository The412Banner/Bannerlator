package com.winlator.star.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object XServerDrawerState {

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

    private val _lsfgEnabled              = MutableStateFlow(false)
    @get:JvmName("getLsfgEnabledState")
    val lsfgEnabled: StateFlow<Boolean>   = _lsfgEnabled

    private val _cursorExpanded          = MutableStateFlow(false)
    val cursorExpanded: StateFlow<Boolean> = _cursorExpanded

    // LSFG runtime settings (shared with Graphics Engine overlay)
    private val _lsfgMultiplier  = MutableStateFlow(2)
    val lsfgMultiplier: StateFlow<Int> = _lsfgMultiplier

    private val _lsfgQuality     = MutableStateFlow("balanced")
    val lsfgQuality: StateFlow<String> = _lsfgQuality

    private val _lsfgFlowScale   = MutableStateFlow(100)
    val lsfgFlowScale: StateFlow<Int> = _lsfgFlowScale

    private val _lsfgMaxLatency  = MutableStateFlow(16)
    val lsfgMaxLatency: StateFlow<Int> = _lsfgMaxLatency

    private val _lsfgGpuArch     = MutableStateFlow("auto")
    val lsfgGpuArch: StateFlow<String> = _lsfgGpuArch

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
    @JvmField var onLsfgToggle:             Runnable? = null
    @JvmField var onMoveCursorToTouchpoint: Runnable? = null
    @JvmField var onRelativeMouseMovement:  Runnable? = null
    @JvmField var onDisableMouse:           Runnable? = null
    @JvmField var onApplyLsfg:             Runnable? = null
    @JvmField var onResetLsfg:             Runnable? = null
    var onCursorExpandedChanged: ((Boolean) -> Unit)? = null

    // Setters called from Java
    fun setIsPaused(v: Boolean)                { _isPaused.value = v }
    fun setIsRelativeMouseMovement(v: Boolean) { _isRelativeMouseMovement.value = v }
    fun setIsMouseDisabled(v: Boolean)         { _isMouseDisabled.value = v }
    fun setMoveCursorToTouchpoint(v: Boolean)  { _moveCursorToTouchpoint.value = v }
    fun setShowLogs(v: Boolean)                { _showLogs.value = v }
    fun setShowMagnifier(v: Boolean)           { _showMagnifier.value = v }
    fun setLsfgEnabled(v: Boolean)              { _lsfgEnabled.value = v }
    fun getLsfgEnabled(): Boolean = _lsfgEnabled.value
    fun setCursorExpanded(v: Boolean)          { _cursorExpanded.value = v }
    fun setLsfgMultiplier(v: Int)       { _lsfgMultiplier.value = v }
    fun setLsfgQuality(v: String)       { _lsfgQuality.value = v }
    fun setLsfgFlowScale(v: Int)        { _lsfgFlowScale.value = v }
    fun setLsfgMaxLatency(v: Int)       { _lsfgMaxLatency.value = v }
    fun setLsfgGpuArch(v: String)       { _lsfgGpuArch.value = v }
    fun getLsfgMultiplier(): Int         = _lsfgMultiplier.value
    fun getLsfgQuality(): String         = _lsfgQuality.value
    fun getLsfgFlowScale(): Int          = _lsfgFlowScale.value
    fun getLsfgMaxLatency(): Int         = _lsfgMaxLatency.value
    fun getLsfgGpuArch(): String         = _lsfgGpuArch.value

    fun toggleCursorExpanded() {
        val next = !_cursorExpanded.value
        _cursorExpanded.value = next
        onCursorExpandedChanged?.invoke(next)
    }

    fun reset() {
        _isPaused.value = false
        _isRelativeMouseMovement.value = false
        _isMouseDisabled.value = false
        _moveCursorToTouchpoint.value = false
        _showLogs.value = false
        _showMagnifier.value = true
        _lsfgEnabled.value = false
        _lsfgMultiplier.value = 2
        _lsfgQuality.value = "balanced"
        _lsfgFlowScale.value = 100
        _lsfgMaxLatency.value = 16
        _lsfgGpuArch.value = "auto"
        _cursorExpanded.value = false
        onClose = null; onKeyboard = null; onInputControls = null
        onScreenEffects = null; onGraphicEngine = null; onVibration = null
        onToggleFullscreen = null; onPauseResume = null; onPipMode = null
        onActiveWindows = null; onTaskManager = null; onMagnifier = null
        onLogs = null; onExit = null; onLsfgToggle = null; onMoveCursorToTouchpoint = null
        onRelativeMouseMovement = null; onDisableMouse = null
        onApplyLsfg = null; onResetLsfg = null
        onCursorExpandedChanged = null
    }
}
