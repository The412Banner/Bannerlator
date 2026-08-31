package com.winlator.star.ui.controllertest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Reusable controller-test visualizer, decoupled from any game/xserver state so it can be hosted from
// BOTH the in-game Players popup (ControllerTestDialog) and the at-rest Settings screen (Input
// Controls). The picture is a parametric Xbox/PS/generic pad drawn on a Compose Canvas that highlights
// each button/dpad/stick/trigger live from a plain ControllerTestSnapshot. The producer of the
// snapshot (the owning Activity) forks a throwaway ExternalController — this file never touches input.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Plain live snapshot of the pad being pressed. `buttons` is the GamepadState button bitfield
 *  (ExternalController IDX_* bit positions); dpad up/right/down/left match GamepadState.dpad[0..3].
 *  `padType` is ExternalController.PadType.ordinal (0=XBOX 1=PS 2=GENERIC). Produced by whichever
 *  Activity owns input; consumed only for drawing — it never reaches the guest. */
data class ControllerTestSnapshot(
    val buttons: Int,
    val dpadUp: Boolean,
    val dpadRight: Boolean,
    val dpadDown: Boolean,
    val dpadLeft: Boolean,
    val thumbLX: Float,
    val thumbLY: Float,
    val thumbRX: Float,
    val thumbRY: Float,
    val triggerL: Float,
    val triggerR: Float,
    val guide: Boolean,
    val deviceId: Int,
    val deviceName: String,
    val padType: Int,
    val batteryPct: Int,   // -1 when unknown; 0..100 otherwise
    val hasVibrator: Boolean,
)

/**
 * Settings-side channel: MainActivity forks input into this snapshot flow while the at-rest test is
 * open and wires the arm/disarm + native-rumble callbacks. The in-game path does NOT use this bus (it
 * keeps its own flow on XServerDialogState), so the two producers stay independent while sharing the
 * snapshot type + the panel below.
 */
object ControllerTestBus {
    private val _snapshot = MutableStateFlow<ControllerTestSnapshot?>(null)
    val snapshot: StateFlow<ControllerTestSnapshot?> = _snapshot
    fun setSnapshot(v: ControllerTestSnapshot?) { _snapshot.value = v }

    /** True while the settings test dialog is open (set by its DisposableEffect via setActive). The
     *  owning Activity re-reads this in onResume so a background/foreground can't leave the fork latched
     *  OFF while the dialog is still up. */
    @JvmField @Volatile var active: Boolean = false

    fun interface ActiveCallback { fun invoke(active: Boolean) }
    /** Set by MainActivity: arm/disarm the settings-side input fork with the test dialog's visibility. */
    @JvmField var onActiveChanged: ActiveCallback? = null
    /** Set by MainActivity: natively rumble the live pad (InputDevice.getVibrator) for "Identify". */
    @JvmField var onIdentify: Runnable? = null

    /** Flip the open/closed state: records it for onResume re-arm AND notifies the Activity. */
    fun setActive(v: Boolean) {
        active = v
        onActiveChanged?.invoke(v)
    }
}

// The 17 element ids for the pad picture + the verified tally.
private val ALL_ELEMENTS = listOf(
    "a", "b", "x", "y", "lb", "rb", "lt", "rt",
    "back", "start", "l3", "r3", "dup", "ddown", "dleft", "dright", "guide"
)

private fun friendlyName(id: String): String = when (id) {
    "a" -> "A / ✕"; "b" -> "B / ○"; "x" -> "X / □"; "y" -> "Y / △"
    "lb" -> "LB / L1"; "rb" -> "RB / R1"; "lt" -> "LT / L2"; "rt" -> "RT / R2"
    "back" -> "View / Share"; "start" -> "Menu / Options"
    "l3" -> "L-stick click"; "r3" -> "R-stick click"
    "dup" -> "D-Up"; "ddown" -> "D-Down"; "dleft" -> "D-Left"; "dright" -> "D-Right"
    "guide" -> "Guide"; else -> id
}

private fun pressedSet(snap: ControllerTestSnapshot?): Set<String> {
    if (snap == null) return emptySet()
    val s = HashSet<String>()
    fun bit(i: Int) = (snap.buttons and (1 shl i)) != 0
    if (bit(0)) s.add("a"); if (bit(1)) s.add("b"); if (bit(2)) s.add("x"); if (bit(3)) s.add("y")
    if (bit(4)) s.add("lb"); if (bit(5)) s.add("rb")
    if (bit(6)) s.add("back"); if (bit(7)) s.add("start")
    if (bit(8)) s.add("l3"); if (bit(9)) s.add("r3")
    if (bit(10) || snap.triggerL > 0.5f) s.add("lt")
    if (bit(11) || snap.triggerR > 0.5f) s.add("rt")
    if (snap.dpadUp) s.add("dup"); if (snap.dpadDown) s.add("ddown")
    if (snap.dpadLeft) s.add("dleft"); if (snap.dpadRight) s.add("dright")
    if (snap.guide) s.add("guide")
    return s
}

private fun fmt2(v: Float): String {
    val r = (v * 100).roundToInt() / 100f
    return String.format(java.util.Locale.US, "%.2f", r)
}

private fun pct(v: Float): String = "${(v.coerceIn(0f, 1f) * 100).roundToInt()}%"

/**
 * The reusable test panel. Fully decoupled: the caller supplies the live [snapshot], header text, the
 * picture-type toggle state ([manualIsPs] + [onTypeChange]; null = auto-detect from the pad), the
 * Identify enablement + callback, and an optional [onDone]. [resetKey] resets the verified-tally +
 * last-input when it changes (e.g. the selected/target pad changed).
 */
@Composable
fun ControllerTestPanel(
    snapshot: ControllerTestSnapshot?,
    title: String,
    subtitle: String,
    resetKey: Any?,
    manualIsPs: Boolean?,
    onTypeChange: (Boolean?) -> Unit,
    identifyEnabled: Boolean,
    onIdentify: (() -> Unit)?,
    onDone: (() -> Unit)?,
    modifier: Modifier = Modifier,
    // Optional name used for the idle (no live pad / generic pad) picture auto-detect. Callers with a
    // known target name pass it; otherwise the live snapshot's device name is used.
    nameHint: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary

    // Auto-detect the picture from the live pad (matches the mock's assignGamepads), falling back to a
    // name heuristic; the segmented control overrides via manualIsPs.
    val autoIsPs = when {
        snapshot != null && snapshot.padType == 1 -> true
        snapshot != null && snapshot.padType == 0 -> false
        else -> {
            val n = (nameHint ?: snapshot?.deviceName)?.lowercase() ?: ""
            n.contains("playstation") || n.contains("dualshock") || n.contains("dualsense")
        }
    }
    val isPs = manualIsPs ?: autoIsPs

    val pressed = pressedSet(snapshot)
    val seen = remember(resetKey) { mutableStateListOf<String>() }
    var lastInput by remember(resetKey) { mutableStateOf("—") }
    LaunchedEffect(pressed) {
        pressed.forEach { if (it !in seen && it in ALL_ELEMENTS) seen.add(it) }
        pressed.firstOrNull { it in ALL_ELEMENTS }?.let { lastInput = friendlyName(it) }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        // Header row.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TypeSegment(isPs, cs) { onTypeChange(it) }
        }
        Spacer(Modifier.height(6.dp))

        // Live-connection + battery banner.
        val live = snapshot != null && snapshot.deviceId >= 0
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(8.dp).clip(RoundedCornerShape(50))
                    .background(if (live) Color(0xFF3DDC84) else cs.onSurfaceVariant)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (live) "Live input" else "Press a button on the pad…",
                fontSize = 12.sp,
                color = if (live) Color(0xFF3DDC84) else cs.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            val batt = snapshot?.batteryPct ?: -1
            if (batt in 0..100) Text("Battery $batt%", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))

        // The pad picture.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0E141B))
                .border(1.dp, cs.outline, RoundedCornerShape(14.dp))
                .padding(8.dp)
        ) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(420f / 250f)) {
                drawPad(isPs, snapshot, pressed, seen, accent)
            }
        }
        Spacer(Modifier.height(10.dp))

        // Readout strip.
        val ls = snapshot?.let { fmt2(it.thumbLX) + ", " + fmt2(it.thumbLY) } ?: "0.00, 0.00"
        val rs = snapshot?.let { fmt2(it.thumbRX) + ", " + fmt2(it.thumbRY) } ?: "0.00, 0.00"
        val tr = snapshot?.let { pct(it.triggerL) + " · " + pct(it.triggerR) } ?: "0% · 0%"
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Last input", lastInput, Modifier.weight(1f))
            StatTile("Left stick", ls, Modifier.weight(1f))
            StatTile("Right stick", rs, Modifier.weight(1f))
            StatTile("Triggers L·R", tr, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))

        // Verified tally + footer actions.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val done = seen.count { it in ALL_ELEMENTS }
            Text(
                if (done >= ALL_ELEMENTS.size) "All inputs registering (${ALL_ELEMENTS.size} tested)"
                else "$done / ${ALL_ELEMENTS.size} inputs verified",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (done >= ALL_ELEMENTS.size) Color(0xFF3DDC84) else cs.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (onIdentify != null) {
                Button(
                    onClick = { onIdentify() },
                    enabled = identifyEnabled,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) { Text("Identify", fontSize = 12.sp) }
            }
            if (onDone != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onDone() }) { Text("Done") }
            }
        }
    }
}

@Composable
private fun TypeSegment(isPs: Boolean, cs: ColorScheme, onChange: (Boolean?) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, cs.outline, RoundedCornerShape(10.dp))
    ) {
        SegButton("Xbox", !isPs, cs) { onChange(false) }
        SegButton("PlayStation", isPs, cs) { onChange(true) }
    }
}

@Composable
private fun SegButton(label: String, on: Boolean, cs: ColorScheme, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (on) cs.primary else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (on) cs.onPrimary else cs.onSurfaceVariant
        )
    }
}

@Composable
private fun StatTile(key: String, value: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, cs.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Text(key.uppercase(), fontSize = 9.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── The parametric pad picture (mirrors the mock's padSVG in a 420×250 space) ────────────────────
private fun DrawScope.drawPad(
    isPs: Boolean,
    snap: ControllerTestSnapshot?,
    pressed: Set<String>,
    seen: List<String>,
    accent: Color,
) {
    val s = size.width / 420f
    fun px(v: Float) = v * s
    fun off(x: Float, y: Float) = Offset(px(x), px(y))

    val bodyFill = Color(0xFF1B2833)
    val bodyStroke = Color(0xFF3A4A5C)
    val baseFill = Color(0xFF243140)
    val baseStroke = Color(0xFF3A4A5C)
    val verified = Color(0xFF3DDC84)
    val well = Color(0xFF161F28)
    val wellStroke = Color(0xFF2B3A49)

    fun stateColor(id: String): Color = when {
        id in pressed -> accent
        id in seen -> verified.copy(alpha = 0.55f)
        else -> baseStroke
    }

    // Body silhouette.
    drawRoundRect(color = bodyFill, topLeft = off(48f, 54f), size = Size(px(324f), px(150f)),
        cornerRadius = CornerRadius(px(60f), px(60f)), style = Fill)
    drawRoundRect(color = bodyStroke, topLeft = off(48f, 54f), size = Size(px(324f), px(150f)),
        cornerRadius = CornerRadius(px(60f), px(60f)), style = Stroke(width = px(2f)))

    // Cluster positions: xbox = stick top-left / dpad lower; ps = dpad top-left / stick lower.
    val stickL = if (isPs) Offset(150f, 150f) else Offset(104f, 104f)
    val dpad = if (isPs) Offset(104f, 104f) else Offset(150f, 150f)
    val stickR = Offset(258f, 150f)

    // Triggers (analog fill).
    drawBase(off(70f, 14f), Size(px(60f), px(16f)), px(7f), stateColor("lt"), baseFill)
    val ltW = 60f * (snap?.triggerL ?: 0f).coerceIn(0f, 1f)
    if (ltW > 0.5f) drawRoundRect(accent, off(70f, 14f), Size(px(ltW), px(16f)), CornerRadius(px(7f), px(7f)))
    drawBase(off(290f, 14f), Size(px(60f), px(16f)), px(7f), stateColor("rt"), baseFill)
    val rtW = 60f * (snap?.triggerR ?: 0f).coerceIn(0f, 1f)
    if (rtW > 0.5f) drawRoundRect(accent, off(350f - rtW, 14f), Size(px(rtW), px(16f)), CornerRadius(px(7f), px(7f)))
    label(if (isPs) "L2" else "LT", 100f, 10f, s, Color(0xFF8AA3B5))
    label(if (isPs) "R2" else "RT", 320f, 10f, s, Color(0xFF8AA3B5))

    // Bumpers.
    drawBase(off(86f, 40f), Size(px(70f), px(14f)), px(7f), stateColor("lb"), baseFill)
    drawBase(off(264f, 40f), Size(px(70f), px(14f)), px(7f), stateColor("rb"), baseFill)
    label(if (isPs) "L1" else "LB", 121f, 51f, s, Color(0xFF0B1219))
    label(if (isPs) "R1" else "RB", 299f, 51f, s, Color(0xFF0B1219))

    // Center cluster: Back/View, Start/Menu, Guide.
    drawBase(off(176f, 98f), Size(px(18f), px(12f)), px(4f), stateColor("back"), baseFill)
    drawBase(off(226f, 98f), Size(px(18f), px(12f)), px(4f), stateColor("start"), baseFill)
    drawCircle(
        color = if ("guide" in pressed) accent else if ("guide" in seen) verified.copy(alpha = 0.55f) else baseFill,
        radius = px(12f), center = off(210f, 88f), style = Fill
    )
    drawCircle(color = stateColor("guide"), radius = px(12f), center = off(210f, 88f), style = Stroke(width = px(1.4f)))

    // D-pad arms.
    drawBase(off(dpad.x - 8f, dpad.y - 24f), Size(px(16f), px(18f)), px(3f), stateColor("dup"), baseFill)
    drawBase(off(dpad.x - 8f, dpad.y + 6f), Size(px(16f), px(18f)), px(3f), stateColor("ddown"), baseFill)
    drawBase(off(dpad.x - 24f, dpad.y - 8f), Size(px(18f), px(16f)), px(3f), stateColor("dleft"), baseFill)
    drawBase(off(dpad.x + 6f, dpad.y - 8f), Size(px(18f), px(16f)), px(3f), stateColor("dright"), baseFill)

    // Sticks.
    drawStick(this, stickL, snap?.thumbLX ?: 0f, snap?.thumbLY ?: 0f, "l3" in pressed, s, accent, well, wellStroke, "l3" in seen, verified)
    drawStick(this, stickR, snap?.thumbRX ?: 0f, snap?.thumbRY ?: 0f, "r3" in pressed, s, accent, well, wellStroke, "r3" in seen, verified)

    // Face buttons.
    val faces = if (isPs) listOf(
        Triple("y", Offset(312f, 90f), Pair("△", Color(0xFF4FD6A8))),
        Triple("b", Offset(336f, 116f), Pair("○", Color(0xFFFF6B7A))),
        Triple("a", Offset(312f, 142f), Pair("✕", Color(0xFF7AA7FF))),
        Triple("x", Offset(288f, 116f), Pair("□", Color(0xFFE879C9))),
    ) else listOf(
        Triple("y", Offset(312f, 90f), Pair("Y", Color(0xFFFFCE46))),
        Triple("b", Offset(336f, 116f), Pair("B", Color(0xFFFF5C6C))),
        Triple("a", Offset(312f, 142f), Pair("A", Color(0xFF3DDC84))),
        Triple("x", Offset(288f, 116f), Pair("X", Color(0xFF4AA3FF))),
    )
    for ((id, pos, glyph) in faces) {
        val (ch, col) = glyph
        val down = id in pressed
        drawCircle(color = if (down) col else Color(0xFF1E2A36), radius = px(15f), center = off(pos.x, pos.y), style = Fill)
        drawCircle(color = if (id in seen && !down) verified else col, radius = px(15f), center = off(pos.x, pos.y), style = Stroke(width = px(1.8f)))
        label(ch, pos.x, pos.y + 5f, s, if (down) Color(0xFF0B1219) else col, bold = true, sizeUnits = 14f)
    }
}

private fun DrawScope.drawBase(topLeft: Offset, size: Size, radius: Float, stroke: Color, fill: Color) {
    drawRoundRect(color = fill, topLeft = topLeft, size = size, cornerRadius = CornerRadius(radius, radius), style = Fill)
    drawRoundRect(color = stroke, topLeft = topLeft, size = size, cornerRadius = CornerRadius(radius, radius), style = Stroke(width = size.height * 0.09f))
}

private fun drawStick(
    scope: DrawScope, base: Offset, x: Float, y: Float, clicked: Boolean,
    s: Float, accent: Color, well: Color, wellStroke: Color, seen: Boolean, verified: Color,
) {
    with(scope) {
        val cx = base.x * s
        val cy = base.y * s
        drawCircle(color = well, radius = 24f * s, center = Offset(cx, cy), style = Fill)
        drawCircle(color = wellStroke, radius = 24f * s, center = Offset(cx, cy), style = Stroke(width = 1.4f * s))
        val capX = cx + x * 8f * s
        val capY = cy + y * 8f * s
        val deflected = (x * x + y * y) > 0.35f * 0.35f
        val hot = clicked || deflected
        drawCircle(color = if (hot) accent else Color(0xFF2B3A49), radius = 16f * s, center = Offset(capX, capY), style = Fill)
        drawCircle(
            color = if (seen && !hot) verified else if (hot) accent else Color(0xFF43586B),
            radius = 16f * s, center = Offset(capX, capY), style = Stroke(width = 1.6f * s)
        )
    }
}

private fun DrawScope.label(
    text: String, xUnits: Float, yUnits: Float, s: Float, color: Color,
    bold: Boolean = false, sizeUnits: Float = 9f,
) {
    val paint = android.graphics.Paint().apply {
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).roundToInt(),
            (color.red * 255).roundToInt(),
            (color.green * 255).roundToInt(),
            (color.blue * 255).roundToInt()
        )
        textSize = sizeUnits * s
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = bold
    }
    drawContext.canvas.nativeCanvas.drawText(text, xUnits * s, yUnits * s, paint)
}
