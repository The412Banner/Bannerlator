package com.winlator.star.ui.controllertest

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// At-rest (Settings → Input Controls) Controller Test. Hosts the shared ControllerTestPanel — picture,
// live highlight, verified checklist, battery, Xbox/PS toggle, native Identify. NO player-slot rail
// (meaningless with no running container).
//
// Like the in-game popup this is a FLAG_NOT_FOCUSABLE window: a normal (focusable) Dialog would capture
// gamepad key/axis events itself and let the pad navigate the dialog's own buttons. Non-focusable →
// the events fall through to MainActivity's dispatch overrides, which fork them into the visualizer
// snapshot and CONSUME them (so the pad never moves Compose focus). Finger taps still drive the UI.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsControllerTestDialog(onDismiss: () -> Unit) {
    // Arm/disarm the MainActivity input fork with this dialog's visibility.
    DisposableEffect(Unit) {
        ControllerTestBus.setActive(true)
        onDispose {
            ControllerTestBus.setActive(false)
            ControllerTestBus.setSnapshot(null)
        }
    }

    val snap by ControllerTestBus.snapshot.collectAsState()
    var manualArt by remember { mutableStateOf<PadArt?>(null) }

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
            window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
            }
        }

        val cs = MaterialTheme.colorScheme
        val cfg = LocalConfiguration.current
        val w = min(760, cfg.screenWidthDp - 24).coerceAtLeast(300).dp
        val h = min(600, cfg.screenHeightDp - 24).coerceAtLeast(240).dp

        // Scrim consumes stray taps; the centered card holds the shared panel.
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
                modifier = Modifier.padding(12.dp).width(w).height(h),
                shape = RoundedCornerShape(18.dp),
                color = cs.surface,
                contentColor = cs.onSurface,
                border = BorderStroke(1.dp, cs.outline),
                shadowElevation = 24.dp,
            ) {
                ControllerTestPanel(
                    snapshot = snap,
                    title = "Test your controller",
                    subtitle = snap?.deviceName?.takeIf { it.isNotEmpty() }
                        ?: "Press any button on your controller",
                    resetKey = Unit,
                    manualArt = manualArt,
                    onArtChange = { manualArt = it },
                    identifyEnabled = snap?.hasVibrator == true,
                    onIdentify = { ControllerTestBus.onIdentify?.run() },
                    onDone = onDismiss,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            }
        }
    }
}
