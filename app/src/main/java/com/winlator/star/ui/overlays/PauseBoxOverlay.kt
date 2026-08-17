package com.winlator.star.ui.overlays

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.star.ui.XServerDialogState

// Compact centered "Paused — tap to resume" indicator, shown whenever the guest is frozen (the
// ReShade freeze-frame preview pause OR a normal manual Pause). Tapping fires onRequestResume
// (SIGCONT + clear paused).
//
// WHY A Dialog WINDOW (not an inline Box in the dialog-host ComposeView): the game renders into a
// SurfaceView (Vulkan/GL, plus the ASR SurfaceControl scanout path) that composites ABOVE the host
// ComposeView's own window, so anything drawn inline there is hidden behind the (frozen) game frame.
// The other in-game surfaces that DO show over the game — NewTaskDialog (AlertDialog), MagnifierOverlay
// (Dialog) — each escape into their own top-level window, which the compositor stacks above the game
// surface. The pause box must do the same or it never appears on the freeze. We clear FLAG_DIM_BEHIND
// so the frozen frame stays visible (no scrim) and add FLAG_NOT_TOUCH_MODAL so only the pill consumes
// touches (the rest of the frozen frame is left untouched).
@Composable
fun PauseBoxOverlay(state: XServerDialogState) {
    Dialog(
        onDismissRequest = { state.onRequestResume?.run() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = false
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                setGravity(Gravity.CENTER)
                // No scrim over the frozen frame; let touches/keys outside the pill fall through so
                // the drawer stays fully interactive during a Live-preview-OFF freeze while tuning.
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                setDimAmount(0f)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .wrapContentSize()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .clickable { state.onRequestResume?.run() }
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Paused — tap to resume",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
