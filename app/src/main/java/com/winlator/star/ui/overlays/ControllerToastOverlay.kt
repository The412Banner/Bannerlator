package com.winlator.star.ui.overlays

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.winlator.star.ui.XServerDialogState
import com.winlator.star.ui.XServerDialogState.ControllerToastData
import com.winlator.star.ui.XServerDialogState.ControllerToastRow
import com.winlator.star.ui.XServerDialogState.ToastBadgeType
import com.winlator.star.ui.XServerDialogState.ToastIconType
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------
// P5b — Controller-status toast. A small, app-themed card that fades into the TOP-RIGHT of the
// in-game screen (below the Fusion HUD), lists each detected input device, holds, then fades out.
// Design is pixel-locked to controller-toast-mockup.html — every colour/measurement below traces
// back to that mockup.
//
// WHY A Dialog WINDOW (same reason as PauseBoxOverlay / MagnifierOverlay): the game renders into a
// SurfaceView that composites ABOVE the host ComposeView's window, so an inline Box would hide behind
// the game frame. A Dialog escapes into its own top-level window the compositor stacks above the game.
// The window is made fully non-interactive (FLAG_NOT_TOUCHABLE + NOT_FOCUSABLE, no dim) so it NEVER
// captures input from the game — it is purely informational.
// ---------------------------------------------------------------------------------------------------

// Timing (ms) — defaults from the mockup; tweak freely.
private const val TOAST_FADE_IN_MS = 280
private const val TOAST_HOLD_MS = 3200
private const val TOAST_FADE_OUT_MS = 450

// Placement: top-right, tucked close to the corner. Small top offset keeps it high; the card shrinks
// toward its top-right origin (TOAST_SCALE) so it hugs the corner without crowding gameplay center.
private const val TOAST_TOP_OFFSET_DP = 24
private const val TOAST_END_MARGIN_DP = 12
private const val TOAST_WIDTH_DP = 288
// Uniform render scale for the whole card (1.0 = full mockup size). 0.75 = 25% smaller.
private const val TOAST_SCALE = 0.75f

// --- Palette (mockup 1:1) ---
private val CardTop     = Color(0xF7131A24) // rgba(19,26,36,.97)
private val CardBottom  = Color(0xF70E141C) // rgba(14,19,27,.97)
// Accent-role colors (border, glow, top-rail, dot, icon tint, P1 badge, countdown) are derived from
// MaterialTheme.colorScheme.primary at render time so the toast follows the app theme. Only the
// theme-independent tokens live here as constants.
private val StrokeSoft  = Color(0x12FFFFFF) // rgba(255,255,255,.07)
private val Txt         = Color(0xFFE9F1F5)
private val TxtDim      = Color(0xFF8EA0AD)
private val RowBg       = Color(0xFF0E141C) // card-2
private val BadgeText   = Color(0xFF06121A)
// Player-identity badge colors — FIXED and distinct regardless of the app theme, so P1-P4 stay
// instantly tellable. (The card chrome still follows MaterialTheme.colorScheme.primary.)
private val P1Cyan      = Color(0xFF5FC7DD)
private val P2Amber     = Color(0xFFC8944E)
private val P3Green     = Color(0xFF7EC46B)
private val P4Purple    = Color(0xFFC77EC4)

private val EaseIn  = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f) // overshoot, matches keyframe tIn
private val EaseOut = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

@Composable
fun ControllerToastOverlay(state: XServerDialogState) {
    val data by state.controllerToast.collectAsState()
    val d = data ?: return
    ControllerToastCard(d) { state.clearControllerToast() }
}

@Composable
private fun ControllerToastCard(data: ControllerToastData, onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val progress = remember { Animatable(1f) }
    val density = LocalDensity.current

    // Drive the whole fade-in → hold (with countdown) → fade-out cycle. Keyed on token, so a new
    // event fired while showing cancels this and restarts fresh with the new content.
    LaunchedEffect(data.token) {
        progress.snapTo(1f)
        alpha.animateTo(1f, tween(TOAST_FADE_IN_MS, easing = EaseIn))
        // The countdown bar's animation duration IS the hold — awaiting it holds the card up.
        progress.animateTo(0f, tween(TOAST_HOLD_MS, easing = LinearEasing))
        alpha.animateTo(0f, tween(TOAST_FADE_OUT_MS, easing = EaseOut))
        onFinished()
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                // Fully pass-through: the toast never steals touch/keys from the game.
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                setDimAmount(0f)
                setGravity(Gravity.TOP or Gravity.END)
                val attrs = attributes
                attrs.x = with(density) { TOAST_END_MARGIN_DP.dp.toPx() }.roundToInt()
                attrs.y = with(density) { TOAST_TOP_OFFSET_DP.dp.toPx() }.roundToInt()
                attributes = attrs
            }
        }

        Box(
            Modifier.graphicsLayer {
                this.alpha = alpha.value
                // Subtle slide + scale to echo the mockup's tIn/tOut keyframes.
                translationY = (1f - alpha.value) * -10.dp.toPx()
                val sc = (0.93f + 0.07f * alpha.value) * TOAST_SCALE
                scaleX = sc; scaleY = sc
                transformOrigin = TransformOrigin(1f, 0f) // top-right, like the CSS transform-origin
            }
        ) {
            ToastBody(data, progress.value)
        }
    }
}

@Composable
private fun ToastBody(data: ControllerToastData, progress: Float) {
    val accent = MaterialTheme.colorScheme.primary       // follow the app theme accent
    val accentGlow = accent.copy(alpha = 0.35f)
    val stroke55 = accent.copy(alpha = 0.55f)
    Column(
        Modifier
            .width(TOAST_WIDTH_DP.dp)
            // Soft outer glow + drop shadow (approximates the mockup's box-shadow).
            .graphicsLayer {
                shadowElevation = 16.dp.toPx()
                shape = RoundedCornerShape(16.dp)
                clip = false
                ambientShadowColor = accentGlow
                spotShadowColor = accentGlow
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(CardTop, CardBottom)))
            .border(1.dp, stroke55, RoundedCornerShape(16.dp))
    ) {
        // 3px accent top-rail (accent → transparent).
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Brush.horizontalGradient(listOf(accent, Color.Transparent), endX = 850f))
        )

        // Header: accent dot + bold uppercase title + right-aligned dim sub-label.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 8.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = data.title,
                color = Txt,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(text = data.subLabel, color = TxtDim, fontSize = 10.5.sp)
        }

        // Hairline divider, inset like the mockup (margin 0 12px).
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(StrokeSoft)
        )

        // Optional full-width message line (info toasts with no device rows, e.g. TV notifications).
        data.message?.let { msg ->
            Text(
                text = msg,
                color = Txt,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 3.dp)
            )
        }

        // Rows.
        Column(
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 11.dp)
        ) {
            data.rows.forEach { DeviceRow(it) }
        }

        // Countdown progress bar along the bottom during the hold (scaleX from 1 → 0, left-anchored).
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .graphicsLayer {
                    scaleX = progress
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(accent.copy(alpha = 0.5f))
        )
    }
}

@Composable
private fun DeviceRow(row: ControllerToastRow) {
    val accent = MaterialTheme.colorScheme.primary       // follow the app theme accent
    val accentGlow = accent.copy(alpha = 0.35f)
    val stroke55 = accent.copy(alpha = 0.55f)
    val iconBorder = accent.copy(alpha = 0.25f)
    val iconTintHi = accent.copy(alpha = 0.18f)
    val iconTintLo = accent.copy(alpha = 0.04f)
    val newBorder = if (row.isNew) stroke55 else StrokeSoft
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(RowBg)
            .then(
                // A hot-plugged row gets a subtle accent glow inset on top of the accent border.
                if (row.isNew) Modifier.drawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = accentGlow,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx())
                    )
                } else Modifier
            )
            .border(1.dp, newBorder, RoundedCornerShape(11.dp))
            .padding(8.dp)
    ) {
        // Icon tile.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(iconTintHi, iconTintLo),
                        center = Offset(0.35f * 38f, 0.30f * 38f)
                    )
                )
                .border(1.dp, iconBorder, RoundedCornerShape(10.dp))
        ) {
            TypeIcon(row.icon, Modifier.size(22.dp))
        }

        Spacer(Modifier.width(11.dp))

        // Name + kind.
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    color = Txt,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (row.isNew) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "NEW",
                        color = accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }
            }
            Text(text = row.kind, color = TxtDim, fontSize = 10.5.sp)
        }

        Spacer(Modifier.width(8.dp))

        Badge(row)
    }
}

@Composable
private fun Badge(row: ControllerToastRow) {
    // Player badge colors are FIXED (theme-independent) so P1-P4 stay distinguishable under any theme.
    val playerColor = when (row.slot) {
        1 -> P2Amber
        2 -> P3Green
        3 -> P4Purple
        else -> P1Cyan
    }
    when (row.badge) {
        ToastBadgeType.PLAYER -> SolidBadge("P${row.slot + 1}", playerColor, BadgeText)
        ToastBadgeType.SHARE -> OutlineBadge("P${row.slot + 1} · share", playerColor, playerColor.copy(alpha = 0.55f))
        ToastBadgeType.IGNORED -> OutlineBadge("Ignored", TxtDim, StrokeSoft)
        ToastBadgeType.DETECTED -> OutlineBadge("Detected", TxtDim, StrokeSoft)
    }
}

@Composable
private fun SolidBadge(text: String, bg: Color, textColor: Color) {
    val glow = bg.copy(alpha = 0.35f) // glow matches the badge color
    Box(
        Modifier
            .graphicsLayer {
                shadowElevation = 6.dp.toPx()
                shape = RoundedCornerShape(50)
                clip = false
                ambientShadowColor = glow
                spotShadowColor = glow
            }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OutlineBadge(text: String, textColor: Color, borderColor: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// The touch/on-screen icon is a stroke path lifted verbatim from the mockup; PathParser handles the
// arcs cleanly. The other three are simple primitives drawn in the same 24-unit space.
private const val TOUCH_PATH =
    "M9 11V6.5a1.6 1.6 0 0 1 3.2 0V11 " +
    "M12.2 11V9.2a1.5 1.5 0 0 1 3 0V11 " +
    "M15.2 11v-.5a1.5 1.5 0 0 1 3 0V15a4.5 4.5 0 0 1-4.5 4.5h-1.6a4 4 0 0 1-3-1.4l-2.4-2.8a1.5 1.5 0 0 1 2.1-2.1L9 14.5V11"

@Composable
private fun TypeIcon(type: ToastIconType, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary // follow the app theme accent
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val w = 1.8f // stroke width in the 24-unit space
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(s, s, pivot = Offset.Zero) {
            when (type) {
                ToastIconType.CONTROLLER -> {
                    // Capsule body (rx = half height → rounded ends).
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(2f, 7f),
                        size = androidx.compose.ui.geometry.Size(20f, 10f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f),
                        style = stroke
                    )
                    drawLine(accent, Offset(7f, 10f), Offset(7f, 14f), w, StrokeCap.Round)
                    drawLine(accent, Offset(5f, 12f), Offset(9f, 12f), w, StrokeCap.Round)
                    drawCircle(accent, 0.9f, Offset(16f, 11f), style = stroke)
                    drawCircle(accent, 0.9f, Offset(18.5f, 13f), style = stroke)
                }
                ToastIconType.TOUCH -> {
                    val path = PathParser().parsePathString(TOUCH_PATH).toPath()
                    drawPath(path, accent, style = stroke)
                }
                ToastIconType.MOUSE -> {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(6f, 3f),
                        size = androidx.compose.ui.geometry.Size(12f, 18f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                        style = stroke
                    )
                    drawLine(accent, Offset(12f, 7f), Offset(12f, 11f), w, StrokeCap.Round)
                }
                ToastIconType.KEYBOARD -> {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(2.5f, 6f),
                        size = androidx.compose.ui.geometry.Size(19f, 12f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                        style = stroke
                    )
                    // Top key-row: five dots.
                    for (x in intArrayOf(6, 9, 12, 15, 18)) {
                        drawCircle(accent, 0.35f, Offset(x.toFloat(), 9.5f))
                    }
                    // Spacebar.
                    drawLine(accent, Offset(8f, 14f), Offset(16f, 14f), w, StrokeCap.Round)
                }
            }
        }
    }
}
