package com.winlator.star.ui

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object XServerDialogState {

    enum class ActiveDialog {
        NONE, VIBRATION, DEBUG, INPUT_CONTROLS, SCREEN_EFFECTS, ACTIVE_WINDOWS, NEW_TASK, CAST
    }

    // -------------------------------------------------------------------------
    // Wireless cast — in-app device picker (Google Cast devices via mDNS).
    // -------------------------------------------------------------------------
    enum class CastStatus { IDLE, CONNECTING, CONNECTED, FAILED }

    private val _castDevices = MutableStateFlow<List<com.winlator.star.cast.CastDiscovery.Device>>(emptyList())
    val castDevices: StateFlow<List<com.winlator.star.cast.CastDiscovery.Device>> = _castDevices
    fun setCastDevices(v: List<com.winlator.star.cast.CastDiscovery.Device>) { _castDevices.value = v }

    private val _castScanning = MutableStateFlow(false)
    val castScanning: StateFlow<Boolean> = _castScanning
    fun setCastScanning(v: Boolean) { _castScanning.value = v }

    private val _castStatus = MutableStateFlow(CastStatus.IDLE)
    val castStatus: StateFlow<CastStatus> = _castStatus
    fun setCastStatus(v: CastStatus) { _castStatus.value = v }

    // Name of the device currently connecting/connected, and a human-readable detail line.
    private val _castTargetName = MutableStateFlow("")
    val castTargetName: StateFlow<String> = _castTargetName
    fun setCastTargetName(v: String) { _castTargetName.value = v }

    private val _castStatusDetail = MutableStateFlow("")
    val castStatusDetail: StateFlow<String> = _castStatusDetail
    fun setCastStatusDetail(v: String) { _castStatusDetail.value = v }

    @JvmField var onCastRefresh: Runnable? = null
    @JvmField var onCastConnect: java.util.function.Consumer<com.winlator.star.cast.CastDiscovery.Device>? = null
    @JvmField var onCastDisconnect: Runnable? = null

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

    // True while the game is shown on an external display (TV). Drives the on-handheld "playing on
    // external display" indicator so the phone isn't just a black screen once the game surface moves.
    private val _playingOnExternal = MutableStateFlow(false)
    val playingOnExternal: StateFlow<Boolean> = _playingOnExternal
    fun setPlayingOnExternal(v: Boolean) { _playingOnExternal.value = v }

    // True while the in-game side menu (drawer) is open — used to hide the external-display badge so
    // it doesn't overlap the menu.
    private val _menuOpen = MutableStateFlow(false)
    val menuOpen: StateFlow<Boolean> = _menuOpen
    fun setMenuOpen(v: Boolean) { _menuOpen.value = v }
    @JvmField var onRequestResume: Runnable? = null

    // -------------------------------------------------------------------------
    // Vibration dialog
    // -------------------------------------------------------------------------
    private val _vibrationSlots = MutableStateFlow<List<Pair<String, Boolean>>>(emptyList())
    val vibrationSlots: StateFlow<List<Pair<String, Boolean>>> = _vibrationSlots

    fun setVibrationSlots(slots: List<Pair<String, Boolean>>) { _vibrationSlots.value = slots }

    /** Reflect a per-slot toggle in the UI list IMMEDIATELY. The row's ToggleRow is controlled purely by
     *  this list, so without this a toggle would snap back visually (the WinHandler persist via
     *  onVibrationSlotChanged happened, but the list stayed stale). Call this alongside the callback. */
    fun updateVibrationSlot(index: Int, enabled: Boolean) {
        val cur = _vibrationSlots.value
        if (index in cur.indices) {
            _vibrationSlots.value = cur.toMutableList().also { it[index] = it[index].first to enabled }
        }
    }

    fun interface VibrationSlotCallback { fun invoke(slot: Int, enabled: Boolean) }
    @JvmField var onVibrationSlotChanged: VibrationSlotCallback? = null

    // Master controller-vibration switch (off = all rumble suppressed; disables the per-slot rows).
    private val _vibrationMasterEnabled = MutableStateFlow(true)
    val vibrationMasterEnabled: StateFlow<Boolean> = _vibrationMasterEnabled
    fun setVibrationMasterEnabled(enabled: Boolean) { _vibrationMasterEnabled.value = enabled }

    fun interface VibrationMasterCallback { fun invoke(enabled: Boolean) }
    @JvmField var onVibrationMasterChanged: VibrationMasterCallback? = null

    // Per-container rumble MODE (0=Off 1=Controller 2=Device 3=Both) + INTENSITY (0..100). Seeded
    // from Container in setupUI (after the container is assigned — seeding earlier would read a
    // still-null container) and pushed live to WinHandler + persisted to Container on every change,
    // same round-trip as the lsfg "Performance mode" toggle.
    private val _vibrationMode = MutableStateFlow(1) // Container.VIBRATION_MODE_DEFAULT (Controller)
    val vibrationMode: StateFlow<Int> = _vibrationMode
    fun setVibrationMode(v: Int) { _vibrationMode.value = v }

    private val _vibrationIntensity = MutableStateFlow(100)
    val vibrationIntensity: StateFlow<Int> = _vibrationIntensity
    fun setVibrationIntensity(v: Int) { _vibrationIntensity.value = v }

    fun interface VibrationModeCallback { fun invoke(mode: Int) }
    @JvmField var onVibrationModeChanged: VibrationModeCallback? = null

    fun interface VibrationIntensityCallback { fun invoke(intensity: Int) }
    @JvmField var onVibrationIntensityChanged: VibrationIntensityCallback? = null

    // -------------------------------------------------------------------------
    // Player Slots (manual per-device slot assignment) — Controls > Players sub-tab. One row per
    // detected input device (plus the on-screen pad). `override` is the user's choice: SLOT_AUTO
    // (-1) leaves auto-assignment alone, SLOT_IGNORE (-2) drops the device, 0..3 pins it to that
    // XInput player slot. `currentSlot` is the slot the device actually holds right now (-1 =
    // unassigned) and is shown as a subtitle. The list is re-read from WinHandler via
    // onPlayerSlotsRefresh (devices hot-plug), and each edit fires onPlayerSlotChanged, which the
    // activity applies live (WinHandler.setDeviceSlotAssignment) + persists per-container.
    // -------------------------------------------------------------------------
    const val SLOT_AUTO = -1
    const val SLOT_IGNORE = -2 // mirrors WinHandler.SLOT_IGNORE

    data class PlayerSlotRow(
        val displayName: String,
        val descriptor: String,
        val currentSlot: Int,
        val override: Int,
        val isOnScreen: Boolean,
        val isGameController: Boolean,
    )

    private val _playerSlots = MutableStateFlow<List<PlayerSlotRow>>(emptyList())
    val playerSlots: StateFlow<List<PlayerSlotRow>> = _playerSlots
    fun setPlayerSlots(rows: List<PlayerSlotRow>) { _playerSlots.value = rows }

    fun interface PlayerSlotCallback { fun invoke(descriptor: String, desiredSlot: Int) }
    /** Fired when the user changes a device's slot selection. desiredSlot = 0..3 pin, SLOT_IGNORE, or
     *  SLOT_AUTO. The activity applies it live and re-seeds the list afterwards. */
    @JvmField var onPlayerSlotChanged: PlayerSlotCallback? = null
    /** Re-reads the device list from WinHandler (call when the sub-tab opens / after a change). */
    @JvmField var onPlayerSlotsRefresh: Runnable? = null
    /** Manual "Reset Input" recovery — rebuilds the fake-input transport in place (no relaunch). */
    @JvmField var onResetInput: Runnable? = null

    // -------------------------------------------------------------------------
    // Controller-status TOAST (P5b) — a small app-themed card that fades into the TOP-RIGHT of the
    // in-game screen (below the Fusion HUD), lists each detected input device (type icon + name +
    // player badge), holds a few seconds, then fades out. Fired on: launch, controller hot-plug
    // (add/remove), manual slot reassignment, and Reset Input. Purely informational / non-interactive
    // (see ControllerToastOverlay). The activity reads getPlayerSlotAssignments (main-thread only) and
    // calls showControllerToastFor with the SAME PlayerSlotRow list the Players sub-tab uses, so the
    // toast and the editor never disagree.
    // -------------------------------------------------------------------------
    enum class ToastIconType { CONTROLLER, TOUCH, MOUSE, KEYBOARD }
    enum class ToastBadgeType { PLAYER, SHARE, IGNORED, DETECTED }

    data class ControllerToastRow(
        val name: String,
        val kind: String,
        val icon: ToastIconType,
        val badge: ToastBadgeType,
        val slot: Int,        // 0-based player slot for PLAYER/SHARE badges; -1 otherwise
        val isNew: Boolean,   // true for the device that just hot-plugged (drives the "NEW" tag)
    )

    data class ControllerToastData(
        val title: String,
        val subLabel: String,
        val rows: List<ControllerToastRow>,
        // Incremented on every event; the overlay keys its fade-in/hold/fade-out animation on this so a
        // new event fired while the toast is showing restarts the cycle with fresh content.
        val token: Long,
        // Optional full-width message line (used by info toasts that have no device rows, e.g. the
        // external-display / TV notification). Null for the controller toast.
        val message: String? = null,
    )

    private val _controllerToast = MutableStateFlow<ControllerToastData?>(null)
    val controllerToast: StateFlow<ControllerToastData?> = _controllerToast
    private var _toastToken = 0L

    /** Called by the overlay once the fade-out finishes (auto-dismiss). */
    fun clearControllerToast() { _controllerToast.value = null }

    /**
     * Show a simple informational toast that reuses the controller-toast card (title + right-aligned
     * subLabel + one full-width message line, no device rows). Used for external-display / TV
     * notifications so they match the existing input notifications. Main-thread only.
     */
    fun showInfoToast(title: String, subLabel: String, message: String?) {
        _toastToken += 1
        _controllerToast.value = ControllerToastData(title, subLabel, emptyList(), _toastToken, message)
    }

    /**
     * Build + show the controller-status toast from the current player-slot rows. Main-thread only
     * (the caller must have just read WinHandler.getPlayerSlotAssignments on the main thread).
     *
     * @param reason one of "launch", "connected", "disconnected", "reassign", "reset" — drives the
     *   header title + sub-label.
     * @param slots  the SAME PlayerSlotRow list the Players sub-tab renders (OSC first, then devices).
     * @param changedDescriptor descriptor of a just-hot-plugged device (marks its row NEW); may be null.
     */
    fun showControllerToastFor(reason: String, slots: List<PlayerSlotRow>, changedDescriptor: String?) {
        // OSC only appears as a row when it is actually seated as a player (has a slot); physical
        // controllers + any slot-holder always appear (getPlayerSlotAssignments already filtered those).
        val visible = slots.filter { !it.isOnScreen || it.currentSlot >= 0 }

        // Shared-slot detection: two visible rows holding the same real slot.
        val slotCounts = HashMap<Int, Int>()
        visible.forEach { if (it.currentSlot >= 0) slotCounts[it.currentSlot] = (slotCounts[it.currentSlot] ?: 0) + 1 }

        val rows = visible.map { r ->
            val lname = r.displayName.lowercase()
            val icon = when {
                r.isOnScreen -> ToastIconType.TOUCH
                r.isGameController -> ToastIconType.CONTROLLER
                lname.contains("mouse") -> ToastIconType.MOUSE
                lname.contains("keyboard") || lname.contains("keypad") -> ToastIconType.KEYBOARD
                else -> ToastIconType.CONTROLLER
            }
            val badge = when {
                r.override == SLOT_IGNORE -> ToastBadgeType.IGNORED
                r.currentSlot >= 0 && (slotCounts[r.currentSlot] ?: 0) > 1 -> ToastBadgeType.SHARE
                r.currentSlot >= 0 -> ToastBadgeType.PLAYER
                else -> ToastBadgeType.DETECTED
            }
            val kind = when {
                r.isOnScreen -> "Virtual gamepad"
                badge == ToastBadgeType.IGNORED -> "Controller · filtered"
                icon == ToastIconType.MOUSE -> "Pointer"
                icon == ToastIconType.KEYBOARD -> "Keys"
                else -> "Controller"
            }
            ControllerToastRow(
                name = r.displayName, kind = kind, icon = icon, badge = badge, slot = r.currentSlot,
                isNew = changedDescriptor != null && r.descriptor == changedDescriptor,
            )
        }
        if (rows.isEmpty()) return

        val hasShare = rows.any { it.badge == ToastBadgeType.SHARE }
        val hasMouseKb = rows.any { it.icon == ToastIconType.MOUSE || it.icon == ToastIconType.KEYBOARD }
        val sharedSlot = rows.firstOrNull { it.badge == ToastBadgeType.SHARE }?.slot ?: 0

        val (title, sub) = when (reason) {
            "connected"    -> "CONTROLLER CONNECTED" to "just now"
            "disconnected" -> "CONTROLLER DISCONNECTED" to "just now"
            "reset"        -> "INPUT RESET" to "just now"
            "reassign"     ->
                if (hasShare) "PLAYER ${sharedSlot + 1} · SHARED" to "updated"
                else "INPUT UPDATED" to "updated"
            else /* launch */ -> when {
                hasMouseKb     -> "INPUT DETECTED"
                rows.size == 1 -> "CONTROLLER DETECTED"
                else           -> "CONTROLLERS DETECTED"
            } to "on launch"
        }

        _toastToken += 1
        _controllerToast.value = ControllerToastData(title, sub, rows, _toastToken)
    }

    // -------------------------------------------------------------------------
    // Gyro (motion aim) — Controls tab. WinHandler owns the values and persists them to
    // SharedPreferences, same round-trip as the vibration master switch: the activity seeds these
    // flows in setupUI and each drawer change fires the matching callback straight back into
    // WinHandler (live, no restart). Per-container/per-game persistence is a later phase.
    // -------------------------------------------------------------------------
    // False on devices with no gyroscope — hides the whole section rather than offering dead controls.
    private val _gyroSupported = MutableStateFlow(false)
    val gyroSupported: StateFlow<Boolean> = _gyroSupported
    fun setGyroSupported(v: Boolean) { _gyroSupported.value = v }

    // Separate from gyroSupported: a device can have a gyroscope but no rotation-vector sensor, in
    // which case rate mode works and only the Orientation chip is dead. False renders that chip
    // disabled with a reason rather than hiding it.
    private val _gyroOrientationSupported = MutableStateFlow(false)
    val gyroOrientationSupported: StateFlow<Boolean> = _gyroOrientationSupported
    fun setGyroOrientationSupported(v: Boolean) { _gyroOrientationSupported.value = v }

    private val _gyroEnabled = MutableStateFlow(true)
    val gyroEnabled: StateFlow<Boolean> = _gyroEnabled
    fun setGyroEnabled(v: Boolean) { _gyroEnabled.value = v }

    // 0 = Rate (tilt SPEED drives the stick, which recentres when you stop), 1 = Orientation /
    // "tilt to aim" (the stick follows the ANGLE held, so a held tilt sustains). WinHandler.GYRO_MODE_*.
    private val _gyroMode = MutableStateFlow(0)
    val gyroMode: StateFlow<Int> = _gyroMode
    fun setGyroMode(v: Int) { _gyroMode.value = v }

    // 0=Right stick 1=Left stick 2=Mouse (WinHandler.GYRO_TARGET_*).
    private val _gyroTarget = MutableStateFlow(0)
    val gyroTarget: StateFlow<Int> = _gyroTarget
    fun setGyroTarget(v: Int) { _gyroTarget.value = v }

    private val _gyroSensitivity = MutableStateFlow(2.0f)
    val gyroSensitivity: StateFlow<Float> = _gyroSensitivity
    fun setGyroSensitivity(v: Float) { _gyroSensitivity.value = v }

    private val _gyroDeadzone = MutableStateFlow(0.05f)
    val gyroDeadzone: StateFlow<Float> = _gyroDeadzone
    fun setGyroDeadzone(v: Float) { _gyroDeadzone.value = v }

    private val _gyroSmoothing = MutableStateFlow(0.5f)
    val gyroSmoothing: StateFlow<Float> = _gyroSmoothing
    fun setGyroSmoothing(v: Float) { _gyroSmoothing.value = v }

    private val _gyroInvertX = MutableStateFlow(false)
    val gyroInvertX: StateFlow<Boolean> = _gyroInvertX
    fun setGyroInvertX(v: Boolean) { _gyroInvertX.value = v }

    private val _gyroInvertY = MutableStateFlow(false)
    val gyroInvertY: StateFlow<Boolean> = _gyroInvertY
    fun setGyroInvertY(v: Boolean) { _gyroInvertY.value = v }

    // 0=L1 1=L2 2=R1 3=R3 4=Always on (WinHandler.GYRO_ACTIVATOR_*). Hold-to-activate throughout;
    // a hold-vs-toggle mode is a later phase.
    private val _gyroActivator = MutableStateFlow(0)
    val gyroActivator: StateFlow<Int> = _gyroActivator
    fun setGyroActivator(v: Int) { _gyroActivator.value = v }

    // 0 = Hold (gyro runs while the activator is down), 1 = Toggle (a tap latches it on until the
    // next tap). Ignored when the activator is "Always" — there's no button to latch.
    private val _gyroActivationMode = MutableStateFlow(0)
    val gyroActivationMode: StateFlow<Int> = _gyroActivationMode
    fun setGyroActivationMode(v: Int) { _gyroActivationMode.value = v }

    fun interface GyroBoolCallback { fun invoke(value: Boolean) }
    fun interface GyroIntCallback { fun invoke(value: Int) }
    fun interface GyroFloatCallback { fun invoke(value: Float) }
    fun interface GyroActionCallback { fun invoke() }

    @JvmField var onGyroEnabledChanged: GyroBoolCallback? = null
    @JvmField var onGyroTargetChanged: GyroIntCallback? = null
    @JvmField var onGyroSensitivityChanged: GyroFloatCallback? = null
    @JvmField var onGyroDeadzoneChanged: GyroFloatCallback? = null
    @JvmField var onGyroSmoothingChanged: GyroFloatCallback? = null
    @JvmField var onGyroInvertXChanged: GyroBoolCallback? = null
    @JvmField var onGyroInvertYChanged: GyroBoolCallback? = null
    @JvmField var onGyroActivatorChanged: GyroIntCallback? = null
    @JvmField var onGyroActivationModeChanged: GyroIntCallback? = null
    @JvmField var onGyroModeChanged: GyroIntCallback? = null
    // Fired by the drawer's Recenter row: the next orientation sample recaptures the pose as centre.
    @JvmField var onGyroRecenterRequested: GyroActionCallback? = null

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
    fun interface InputSettingsCallback {
        fun invoke(profileIndex: Int)
    }
    @JvmField var onInputControlsConfirm: InputConfirmCallback? = null
    @JvmField var onInputControlsSettings: InputSettingsCallback? = null

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
        // Current CPU-affinity bitmask (bit i = CPU i) reported by the guest's GetProcessAffinityMask.
        // Feeds the Processor Affinity picker so it opens pre-ticked to the process's live cores.
        val affinityMask: Int,
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

    // Enriched Task Manager header: live perf stats (polled ~1s) + the running container's config
    // (set once — it doesn't change mid-session). Nullable Int = metric unreadable (show "—").
    data class TmHeaderStats(
        val cpuPct: Int?, val cpuTempC: Int?,
        val gpuPct: Int?, val gpuTempC: Int?, val gpuClockMhz: Int?,
        val fps: Int, val fpsMin: Int,
        val ramUsed: String, val ramTotal: String,
        val swap: String?,
        val batteryPct: Int?, val batteryWatts: Float, val batteryTempC: Int?, val charging: Boolean,
        val perCoreMhz: List<Int>,
    )
    data class TmContainerInfo(
        val wine: String, val dxWrapper: String, val renderer: String,
        val graphicsDriver: String, val resolution: String, val device: String,
    )

    private val _tmHeader = MutableStateFlow<TmHeaderStats?>(null)
    val tmHeader: StateFlow<TmHeaderStats?> = _tmHeader
    private val _tmContainerInfo = MutableStateFlow<TmContainerInfo?>(null)
    val tmContainerInfo: StateFlow<TmContainerInfo?> = _tmContainerInfo

    fun setTmHeader(v: TmHeaderStats?)          { _tmHeader.value = v }
    fun setTmContainerInfo(v: TmContainerInfo?) { _tmContainerInfo.value = v }

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

    // Live lookup of the mask the user last applied to a pid (or -1 if none). Lets the affinity
    // dialog show the user's real choice the instant it opens, without waiting on the async
    // process-list rebuild to carry the override into TmProcess.affinityMask.
    fun interface TmAffinityQuery { fun invoke(pid: Int): Int }
    @JvmField var onTmQueryAffinity: TmAffinityQuery? = null

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
        _controllerToast.value = null
        _vibrationSlots.value  = emptyList()
        _vibrationMode.value   = 1
        _vibrationIntensity.value = 100
        _playerSlots.value     = emptyList()
        _gyroSupported.value   = false
        _gyroOrientationSupported.value = false
        _gyroEnabled.value     = true
        _gyroMode.value        = 0
        _gyroTarget.value      = 0
        _gyroSensitivity.value = 2.0f
        _gyroDeadzone.value    = 0.05f
        _gyroSmoothing.value   = 0.5f
        _gyroInvertX.value     = false
        _gyroInvertY.value     = false
        _gyroActivator.value   = 0
        _gyroActivationMode.value = 0
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
        onVibrationModeChanged = null; onVibrationIntensityChanged = null
        onPlayerSlotChanged = null; onPlayerSlotsRefresh = null; onResetInput = null
        onGyroEnabledChanged = null; onGyroTargetChanged = null
        onGyroSensitivityChanged = null; onGyroDeadzoneChanged = null; onGyroSmoothingChanged = null
        onGyroInvertXChanged = null; onGyroInvertYChanged = null; onGyroActivatorChanged = null
        onGyroActivationModeChanged = null; onGyroModeChanged = null
        onGyroRecenterRequested = null
        onInputControlsConfirm = null; onInputControlsSettings = null
        onScreenEffectsApply = null; onSeAddProfile = null; onSeRemoveProfile = null
        onWindowClick = null
        onTmRefresh = null; onTmDismissed = null; onTmNewTask = null; onTmNewTaskSubmit = null
        onTmBringToFront = null; onTmKillProcess = null; onTmSetAffinity = null; onTmQueryAffinity = null
        onInitGraphicsTab = null
    }
}
