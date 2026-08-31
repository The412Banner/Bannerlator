package com.winlator.star.ui.controllertest

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.ExternalController
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// At-rest (Settings → Input Controls) Controller Test + Bind. TEST mode = the shared ControllerTestPanel
// (picture, live highlight, verified checklist, battery, Identify). BIND mode = the shared
// VisualControllerBinder (tap a button on the pad to remap it, profiles, pass-through banner).
//
// Focusability is toggled by mode: TEST keeps FLAG_NOT_FOCUSABLE so gamepad key/axis events fall through
// to MainActivity's fork (live highlight); BIND clears it so the profile-name text field can take focus
// and the soft keyboard opens.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsControllerTestDialog(
    onDismiss: () -> Unit,
    profile: ControlsProfile?,
    allProfiles: List<ControlsProfile>,
    onSelectProfile: (ControlsProfile) -> Unit,
    onCreateProfile: (String) -> Unit,
    onRenameProfile: () -> Unit,
    onBindingsChanged: () -> Unit,
    onOpenAllBindings: () -> Unit,
    startInBind: Boolean = false,
) {
    val snap by ControllerTestBus.snapshot.collectAsState()
    var manualArt by remember { mutableStateOf<PadArt?>(null) }
    var bindMode by remember { mutableStateOf(startInBind) }
    // Name from the connected pad so the art matches even before any press (bind mode has no live fork).
    val padName = remember { ExternalController.getControllers().firstOrNull()?.name }

    // Reset the throwaway controller when the dialog opens; drop the snapshot + gate when it closes.
    // (The GATE itself is armed SYNCHRONOUSLY in the SideEffect below, not here, so the first press
    // after opening is already captured — fixes the "doesn't react + leaks until rotate" bug.)
    DisposableEffect(Unit) {
        ControllerTestBus.onActiveChanged?.invoke(true)
        onDispose {
            ControllerTestBus.active = false
            ControllerTestBus.onActiveChanged?.invoke(false)
            ControllerTestBus.setSnapshot(null)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            // Arm the fork gate SYNCHRONOUSLY (test mode only). MainActivity reads ControllerTestBus.active
            // directly at its dispatch chokepoints, so this is live before the first input event.
            ControllerTestBus.active = !bindMode
            window?.apply {
                if (bindMode) clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                else addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
            }
        }

        val cs = MaterialTheme.colorScheme
        val cfg = LocalConfiguration.current
        val w = min(760, cfg.screenWidthDp - 24).coerceAtLeast(300).dp
        val maxH = (cfg.screenHeightDp - 24).coerceAtLeast(220).dp

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.padding(12.dp).width(w).heightIn(max = maxH),
                shape = RoundedCornerShape(18.dp),
                color = cs.surface,
                contentColor = cs.onSurface,
                border = BorderStroke(1.dp, cs.outline),
                shadowElevation = 24.dp,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        TestBindToggle(bindMode) { bindMode = it }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (bindMode) {
                        VisualControllerBinder(
                            profile = profile,
                            art = padArtOf(snap, padName),
                            snapshot = snap,
                            allProfiles = allProfiles,
                            onSelectProfile = onSelectProfile,
                            onCreateProfile = onCreateProfile,
                            onRenameProfile = onRenameProfile,
                            onSaved = onBindingsChanged,
                            onOpenAllBindings = onOpenAllBindings,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        ControllerTestPanel(
                            snapshot = snap,
                            title = "Test your controller",
                            subtitle = snap?.deviceName?.takeIf { it.isNotEmpty() } ?: (padName ?: "Press any button on your controller"),
                            resetKey = Unit,
                            manualArt = manualArt,
                            onArtChange = { manualArt = it },
                            identifyEnabled = snap?.hasVibrator == true,
                            onIdentify = { ControllerTestBus.onIdentify?.run() },
                            onDone = null,
                            modifier = Modifier.fillMaxWidth(),
                            nameHint = padName,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TestBindToggle(bind: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, cs.outline, RoundedCornerShape(10.dp))
    ) {
        SegItem("Test", !bind, cs.primary, cs.onPrimary, cs.onSurfaceVariant) { onChange(false) }
        SegItem("Bind", bind, cs.primary, cs.onPrimary, cs.onSurfaceVariant) { onChange(true) }
    }
}

@Composable
private fun SegItem(label: String, on: Boolean, onBg: Color, onFg: Color, offFg: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (on) onBg else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) onFg else offFg)
    }
}
