package com.winlator.star.ui.controllertest

import android.view.KeyEvent
import com.winlator.star.inputcontrols.Binding
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.ExternalController
import com.winlator.star.inputcontrols.ExternalControllerBinding

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The bindable physical-controller elements for the visual binder. Each maps a drawn element to the
// REAL storage keyCode used by ExternalController.controllerBindings (buttons/dpad = KeyEvent keycodes;
// stick directions = ExternalControllerBinding's negative axis pseudo-keycodes) and to its own native
// Xbox `Binding` (used by "Set to native" / "Fill rest → native Xbox"). This is the SAME data the list
// editor (ExternalControllerBindingsActivity) reads/writes — NOT a parallel store. Guide has no native
// gamepad Binding, so its `native` is null (fill-native skips it).
// ─────────────────────────────────────────────────────────────────────────────────────────────────

internal data class BindTarget(
    val id: String,        // matches the pad element id where one exists (a/b/.../dup/l3…); lsu/… are list-only
    val name: String,
    val keyCode: Int,
    val native: Binding?,
)

internal val BIND_TARGETS: List<BindTarget> = listOf(
    BindTarget("a", "A", KeyEvent.KEYCODE_BUTTON_A, Binding.GAMEPAD_BUTTON_A),
    BindTarget("b", "B", KeyEvent.KEYCODE_BUTTON_B, Binding.GAMEPAD_BUTTON_B),
    BindTarget("x", "X", KeyEvent.KEYCODE_BUTTON_X, Binding.GAMEPAD_BUTTON_X),
    BindTarget("y", "Y", KeyEvent.KEYCODE_BUTTON_Y, Binding.GAMEPAD_BUTTON_Y),
    BindTarget("lb", "LB", KeyEvent.KEYCODE_BUTTON_L1, Binding.GAMEPAD_BUTTON_L1),
    BindTarget("rb", "RB", KeyEvent.KEYCODE_BUTTON_R1, Binding.GAMEPAD_BUTTON_R1),
    BindTarget("lt", "LT", KeyEvent.KEYCODE_BUTTON_L2, Binding.GAMEPAD_BUTTON_L2),
    BindTarget("rt", "RT", KeyEvent.KEYCODE_BUTTON_R2, Binding.GAMEPAD_BUTTON_R2),
    BindTarget("l3", "L3 (stick click)", KeyEvent.KEYCODE_BUTTON_THUMBL, Binding.GAMEPAD_BUTTON_L3),
    BindTarget("r3", "R3 (stick click)", KeyEvent.KEYCODE_BUTTON_THUMBR, Binding.GAMEPAD_BUTTON_R3),
    BindTarget("start", "Start", KeyEvent.KEYCODE_BUTTON_START, Binding.GAMEPAD_BUTTON_START),
    BindTarget("back", "Back", KeyEvent.KEYCODE_BUTTON_SELECT, Binding.GAMEPAD_BUTTON_SELECT),
    BindTarget("guide", "Guide", KeyEvent.KEYCODE_BUTTON_MODE, null),
    BindTarget("dup", "D-Pad Up", KeyEvent.KEYCODE_DPAD_UP, Binding.GAMEPAD_DPAD_UP),
    BindTarget("ddown", "D-Pad Down", KeyEvent.KEYCODE_DPAD_DOWN, Binding.GAMEPAD_DPAD_DOWN),
    BindTarget("dleft", "D-Pad Left", KeyEvent.KEYCODE_DPAD_LEFT, Binding.GAMEPAD_DPAD_LEFT),
    BindTarget("dright", "D-Pad Right", KeyEvent.KEYCODE_DPAD_RIGHT, Binding.GAMEPAD_DPAD_RIGHT),
    BindTarget("lsu", "L-Stick Up", ExternalControllerBinding.AXIS_Y_NEGATIVE.toInt(), Binding.GAMEPAD_LEFT_THUMB_UP),
    BindTarget("lsd", "L-Stick Down", ExternalControllerBinding.AXIS_Y_POSITIVE.toInt(), Binding.GAMEPAD_LEFT_THUMB_DOWN),
    BindTarget("lsl", "L-Stick Left", ExternalControllerBinding.AXIS_X_NEGATIVE.toInt(), Binding.GAMEPAD_LEFT_THUMB_LEFT),
    BindTarget("lsr", "L-Stick Right", ExternalControllerBinding.AXIS_X_POSITIVE.toInt(), Binding.GAMEPAD_LEFT_THUMB_RIGHT),
    BindTarget("rsu", "R-Stick Up", ExternalControllerBinding.AXIS_RZ_NEGATIVE.toInt(), Binding.GAMEPAD_RIGHT_THUMB_UP),
    BindTarget("rsd", "R-Stick Down", ExternalControllerBinding.AXIS_RZ_POSITIVE.toInt(), Binding.GAMEPAD_RIGHT_THUMB_DOWN),
    BindTarget("rsl", "R-Stick Left", ExternalControllerBinding.AXIS_Z_NEGATIVE.toInt(), Binding.GAMEPAD_RIGHT_THUMB_LEFT),
    BindTarget("rsr", "R-Stick Right", ExternalControllerBinding.AXIS_Z_POSITIVE.toInt(), Binding.GAMEPAD_RIGHT_THUMB_RIGHT),
)

internal val BIND_BY_ID: Map<String, BindTarget> = BIND_TARGETS.associateBy { it.id }

/** Map an Android keyCode captured from a real key press to a Binding, or null if none matches. */
internal fun bindingForCapturedKeyCode(keyCode: Int): Binding? {
    val label = KeyEvent.keyCodeToString(keyCode) // e.g. "KEYCODE_SPACE"
    val bare = label.removePrefix("KEYCODE_")
    // Try KEY_<bare> against the keyboard values (e.g. SPACE → KEY_SPACE, A → KEY_A).
    for (bnd in Binding.keyboardBindingValues()) {
        if (bnd.name == "KEY_$bare") return bnd
    }
    return null
}

/** Human label for a bound target, or null when unbound (native pass-through). */
internal fun bindingLabelOrNull(controller: ExternalController?, id: String): String? {
    val t = BIND_BY_ID[id] ?: return null
    val b = controller?.getControllerBinding(t.keyCode) ?: return null
    return b.binding?.toString()
}

internal fun isBound(controller: ExternalController?, id: String): Boolean {
    val t = BIND_BY_ID[id] ?: return false
    return controller?.getControllerBinding(t.keyCode) != null
}

internal fun boundCount(controller: ExternalController?): Int {
    if (controller == null) return 0
    return BIND_TARGETS.count { controller.getControllerBinding(it.keyCode) != null }
}

/** Get-or-create a binding for the target and set it, then persist — the reused write path from
 *  ExternalControllerBindingsActivity.updateControllerBinding (minus the auto-capture). */
internal fun setTarget(controller: ExternalController, profile: ControlsProfile, id: String, binding: Binding) {
    val t = BIND_BY_ID[id] ?: return
    if (t.keyCode == KeyEvent.KEYCODE_UNKNOWN) return
    var b = controller.getControllerBinding(t.keyCode)
    if (b == null) {
        b = ExternalControllerBinding()
        b.keyCode = t.keyCode
        b.binding = binding
        controller.addControllerBinding(b)
    } else {
        b.binding = binding
    }
    profile.save()
}

/** Remove a target's binding entirely (element returns to unbound). */
internal fun clearTarget(controller: ExternalController, profile: ControlsProfile, id: String) {
    val t = BIND_BY_ID[id] ?: return
    val b = controller.getControllerBinding(t.keyCode) ?: return
    controller.removeControllerBinding(b)
    profile.save()
}

/** Bind every still-unbound target to its OWN native Xbox binding, so unbound buttons stop going
 *  silent once remapping is active. Skips Guide (no native gamepad binding). One save at the end. */
internal fun fillNative(controller: ExternalController, profile: ControlsProfile) {
    var changed = false
    for (t in BIND_TARGETS) {
        if (t.native == null) continue
        if (controller.getControllerBinding(t.keyCode) == null) {
            val b = ExternalControllerBinding()
            b.keyCode = t.keyCode
            b.binding = t.native
            controller.addControllerBinding(b)
            changed = true
        }
    }
    if (changed) profile.save()
}

// Category pickers — thin wrappers over the existing Binding label/value helpers.
internal enum class BindCategory { KEYBOARD, MOUSE, XBOX, NONE }

internal fun categoryLabels(cat: BindCategory): Array<String> = when (cat) {
    BindCategory.KEYBOARD -> Binding.keyboardBindingLabels()
    BindCategory.MOUSE -> Binding.mouseBindingLabels()
    BindCategory.XBOX -> Binding.gamepadBindingLabels()
    BindCategory.NONE -> arrayOf(Binding.NONE.toString())
}

internal fun categoryValues(cat: BindCategory): Array<Binding> = when (cat) {
    BindCategory.KEYBOARD -> Binding.keyboardBindingValues()
    BindCategory.MOUSE -> Binding.mouseBindingValues()
    BindCategory.XBOX -> Binding.gamepadBindingValues()
    BindCategory.NONE -> arrayOf(Binding.NONE)
}

/** Resolve which controller the binder edits on a profile: the first connected game controller
 *  (get-or-create on the profile), else the profile's Default / Any Controller. */
internal fun resolveBindController(profile: ControlsProfile): ExternalController {
    val first = ExternalController.getControllers().firstOrNull()
    if (first != null) {
        return profile.getController(first.id) ?: profile.addController(first.id)
    }
    return profile.getOrCreateDefaultController()
}
