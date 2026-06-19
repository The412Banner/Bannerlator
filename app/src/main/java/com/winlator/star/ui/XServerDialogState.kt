package com.winlator.star.ui

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object XServerDialogState {

    enum class ActiveDialog {
        NONE, VIBRATION, DEBUG, INPUT_CONTROLS, SCREEN_EFFECTS, ACTIVE_WINDOWS
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

    fun setSgsrEnabled(v: Boolean)    { _sgsrEnabled.value = v }
    fun setSgsrSharpness(v: Int)      { _sgsrSharpness.value = v }
    fun setHdrEnabled(v: Boolean)     { _hdrEnabled.value = v }

    fun interface SgsrUpdateCallback { fun invoke(enabled: Boolean, sharpness: Int, hdr: Boolean) }
    @JvmField var onSgsrUpdate: SgsrUpdateCallback? = null

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
    @JvmField var onTmNewTask: Runnable? = null

    fun interface TmStringCallback { fun invoke(name: String) }
    @JvmField var onTmBringToFront: TmStringCallback? = null
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
        onVibrationSlotChanged = null
        onInputControlsConfirm = null; onInputControlsSettings = null
        onScreenEffectsApply = null; onSeAddProfile = null; onSeRemoveProfile = null
        onWindowClick = null
        onTmRefresh = null; onTmDismissed = null; onTmNewTask = null
        onTmBringToFront = null; onTmKillProcess = null; onTmSetAffinity = null
        onInitGraphicsTab = null
    }
}
