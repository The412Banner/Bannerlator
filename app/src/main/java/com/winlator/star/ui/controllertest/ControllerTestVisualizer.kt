package com.winlator.star.ui.controllertest

import android.view.InputDevice
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Path
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
// Controls). It draws an ACCURATE per-family pad (Xbox 360 / Xbox / DualSense / DualShock 4 / Switch
// Pro / 8BitDo / Generic, + DualShock 3 / Steam) on a Compose Canvas, auto-picked from the connected
// device (PadArt in the snapshot). Face buttons light by POSITION (N/E/S/W ↔ GamepadState Y/B/A/X
// bits) so pressing the physical button always lights the right on-screen spot; each family only
// changes the glyph/color drawn there. The Activity that owns input forks a throwaway ExternalController
// to produce the snapshot — this file never touches input.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Plain live snapshot of the pad being pressed. `buttons` is the GamepadState button bitfield
 *  (ExternalController IDX_* bit positions); dpad up/right/down/left match GamepadState.dpad[0..3].
 *  `padArt` is PadArt.ordinal (which drawn family to show). Produced by whichever Activity owns input;
 *  consumed only for drawing — it never reaches the guest. */
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
    val padArt: Int,
    val batteryPct: Int,   // -1 when unknown; 0..100 otherwise
    val hasVibrator: Boolean,
)

/**
 * Settings-side channel: MainActivity forks input into this snapshot flow while the at-rest test is
 * open and wires the arm/disarm + native-rumble callbacks. The in-game path keeps its own flow on
 * XServerDialogState, so the two producers stay independent while sharing the snapshot type + panel.
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
    @JvmField var onActiveChanged: ActiveCallback? = null
    @JvmField var onIdentify: Runnable? = null

    fun setActive(v: Boolean) {
        active = v
        onActiveChanged?.invoke(v)
    }
}

// ── Pad-art catalog + matcher ────────────────────────────────────────────────────────────────────

/** Which drawn controller family to show. Order is the ordinal contract stored in the snapshot. */
enum class PadArt { XBOX_360, XBOX_MODERN, DUALSENSE, DUALSHOCK4, SWITCH_PRO, EIGHTBITDO, GENERIC, DUALSHOCK3, STEAM }

fun padArtLabel(a: PadArt): String = when (a) {
    PadArt.XBOX_360 -> "Xbox 360"
    PadArt.XBOX_MODERN -> "Xbox"
    PadArt.DUALSENSE -> "DualSense"
    PadArt.DUALSHOCK4 -> "DualShock 4"
    PadArt.SWITCH_PRO -> "Switch Pro"
    PadArt.EIGHTBITDO -> "8BitDo"
    PadArt.GENERIC -> "Generic"
    PadArt.DUALSHOCK3 -> "DualShock 3"
    PadArt.STEAM -> "Steam"
}

private fun padArtSafe(ordinal: Int): PadArt = PadArt.values().getOrElse(ordinal) { PadArt.GENERIC }

/** Finer-grained mapper (additive to ExternalController.classifyType): vendorId + productId + name →
 *  PadArt. Null-safe; used by the snapshot producers so the picture matches the device Android reports. */
fun classifyPadArt(device: InputDevice?): PadArt {
    if (device == null) return PadArt.GENERIC
    val n = (device.name ?: "").lowercase(java.util.Locale.US)
    // 8BitDo often re-reports as an Xbox/Switch vendor in different modes — honor its name first.
    if (n.contains("8bitdo") || n.contains("sn30")) return PadArt.EIGHTBITDO
    val p = device.productId
    return when (device.vendorId) {
        0x045E -> if (n.contains("360")) PadArt.XBOX_360 else PadArt.XBOX_MODERN
        0x054C -> when {
            n.contains("dualsense") || p == 0x0CE6 || p == 0x0DF2 -> PadArt.DUALSENSE
            n.contains("dualshock 4") || n.contains("wireless controller") ||
                p == 0x05C4 || p == 0x09CC || p == 0x0BA0 -> PadArt.DUALSHOCK4
            n.contains("motion") || p == 0x0268 -> PadArt.DUALSHOCK3
            else -> PadArt.DUALSHOCK4
        }
        0x057E -> PadArt.SWITCH_PRO
        0x2DC8 -> PadArt.EIGHTBITDO
        0x28DE -> PadArt.STEAM
        else -> classifyPadArtByName(n)
    }
}

/** Name-only fallback for the idle case (no live snapshot yet) and unknown-vendor pads. */
private fun classifyPadArtByName(nameRaw: String): PadArt {
    val n = nameRaw.lowercase(java.util.Locale.US)
    return when {
        n.contains("8bitdo") || n.contains("sn30") || n.contains("pro 2") -> PadArt.EIGHTBITDO
        n.contains("dualsense") -> PadArt.DUALSENSE
        n.contains("dualshock 4") || n.contains("dualshock4") -> PadArt.DUALSHOCK4
        n.contains("dualshock 3") || n.contains("dualshock3") || n.contains("motion") -> PadArt.DUALSHOCK3
        n.contains("dualshock") || n.contains("playstation") -> PadArt.DUALSHOCK4
        n.contains("xbox") -> if (n.contains("360")) PadArt.XBOX_360 else PadArt.XBOX_MODERN
        n.contains("switch") || n.contains("joy-con") || n.contains("pro controller") -> PadArt.SWITCH_PRO
        n.contains("steam") -> PadArt.STEAM
        else -> PadArt.GENERIC
    }
}

private fun isPsFamily(art: PadArt) =
    art == PadArt.DUALSENSE || art == PadArt.DUALSHOCK4 || art == PadArt.DUALSHOCK3

// ── Element bookkeeping (unchanged: bit ids → verified tally) ─────────────────────────────────────
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

// ── The reusable panel ───────────────────────────────────────────────────────────────────────────
@Composable
fun ControllerTestPanel(
    snapshot: ControllerTestSnapshot?,
    title: String,
    subtitle: String,
    resetKey: Any?,
    manualArt: PadArt?,
    onArtChange: (PadArt?) -> Unit,
    identifyEnabled: Boolean,
    onIdentify: (() -> Unit)?,
    onDone: (() -> Unit)?,
    modifier: Modifier = Modifier,
    // Optional name used for the idle (no live pad) art auto-detect.
    nameHint: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary

    // Art follows the detected pad by default; the selector overrides via manualArt.
    val autoArt = when {
        snapshot != null -> padArtSafe(snapshot.padArt)
        nameHint != null -> classifyPadArtByName(nameHint)
        else -> PadArt.GENERIC
    }
    val art = manualArt ?: autoArt

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
            ArtSelector(art, manualArt, onArtChange)
        }
        Spacer(Modifier.height(6.dp))

        // Live-connection + battery banner.
        val live = snapshot != null && snapshot.deviceId >= 0
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(8.dp).clip(RoundedCornerShape(50))
                    .background(if (live) Color(0xFF57C777) else cs.onSurfaceVariant)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (live) "Live input" else "Press a button on the pad…",
                fontSize = 12.sp,
                color = if (live) Color(0xFF57C777) else cs.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            val batt = snapshot?.batteryPct ?: -1
            if (batt in 0..100) Text("Battery $batt%", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))

        // The pad picture (320×210 art space).
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0B111B))
                .border(1.dp, cs.outline, RoundedCornerShape(14.dp))
                .padding(8.dp)
        ) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(320f / 210f)) {
                drawPad(art, snapshot, pressed, seen, accent)
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
                color = if (done >= ALL_ELEMENTS.size) Color(0xFF57C777) else cs.onSurfaceVariant,
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
private fun ArtSelector(effective: PadArt, manual: PadArt?, onChange: (PadArt?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) { Text((if (manual == null) "Auto · " else "") + padArtLabel(effective), fontSize = 12.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Auto-detect") }, onClick = { open = false; onChange(null) })
            for (a in PadArt.values()) {
                DropdownMenuItem(text = { Text(padArtLabel(a)) }, onClick = { open = false; onChange(a) })
            }
        }
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

// ── Drawing (320×210 art space, ported from the artwork reference) ───────────────────────────────
private val PAD_BODY = Color(0xFF28323F)
private val PAD_LINE = Color(0xFF3D4A5C)
private val PAD_HOLLOW = Color(0xFF161E29)
private val PAD_STROKE = Color(0xFF4A586C)
private val VERIFIED = Color(0xFF57C777)
private val FAINT = Color(0xFF67748C)
private val NEUTRAL = Color(0xFF9FB0C8)
// Xbox face colors.
private val XA = Color(0xFF5CC46B); private val XB = Color(0xFFE5564F)
private val XX = Color(0xFF4A9FE8); private val XY = Color(0xFFF2C94C)
// PlayStation symbol colors.
private val PTRI = Color(0xFF54D6BB); private val PCIR = Color(0xFFF4676B)
private val PSQ = Color(0xFFE77FC0); private val PCROSS = Color(0xFF7AA0FF)

/** Glyph text + color for the four face positions, keyed by bit id (y=north, b=east, a=south, x=west). */
private fun faceGlyphs(art: PadArt): Map<String, Pair<String, Color>> = when {
    isPsFamily(art) -> mapOf(
        "y" to Pair("△", PTRI), "b" to Pair("○", PCIR), "a" to Pair("✕", PCROSS), "x" to Pair("□", PSQ)
    )
    art == PadArt.SWITCH_PRO -> mapOf(
        "y" to Pair("X", NEUTRAL), "b" to Pair("A", NEUTRAL), "a" to Pair("B", NEUTRAL), "x" to Pair("Y", NEUTRAL)
    )
    art == PadArt.GENERIC -> mapOf(
        "y" to Pair("Y", NEUTRAL), "b" to Pair("B", NEUTRAL), "a" to Pair("A", NEUTRAL), "x" to Pair("X", NEUTRAL)
    )
    else -> mapOf( // Xbox 360 / Xbox / 8BitDo / Steam — Xbox colors
        "y" to Pair("Y", XY), "b" to Pair("B", XB), "a" to Pair("A", XA), "x" to Pair("X", XX)
    )
}

private fun bumperLabels(art: PadArt): Pair<String, String> = when {
    isPsFamily(art) -> "L1" to "R1"
    art == PadArt.SWITCH_PRO -> "L" to "R"
    else -> "LB" to "RB"
}

private fun triggerLabels(art: PadArt): Pair<String, String> = when {
    isPsFamily(art) -> "L2" to "R2"
    art == PadArt.SWITCH_PRO -> "ZL" to "ZR"
    else -> "LT" to "RT"
}

private fun DrawScope.drawPad(
    art: PadArt,
    snap: ControllerTestSnapshot?,
    pressed: Set<String>,
    seen: List<String>,
    accent: Color,
) {
    val s = size.width / 320f
    val ps = isPsFamily(art)

    drawBody(art, s)

    // Triggers (analog fill) + bumpers.
    val (ltL, rtL) = triggerLabels(art)
    drawTriggers(s, snap, pressed, seen, accent, ltL, rtL)
    val (lbL, rbL) = bumperLabels(art)
    drawLitRect(66f, 34f, 56f, 15f, 7f, "lb", s, pressed, seen, accent)
    label(lbL, 94f, 42f, s, FAINT)
    drawLitRect(198f, 34f, 56f, 15f, 7f, "rb", s, pressed, seen, accent)
    label(rbL, 226f, 42f, s, FAINT)

    // Sticks + dpad (arrangement differs for the PS family).
    if (ps) {
        drawDpad(92f, 103f, s, pressed, seen, accent)
        drawStickEl("l3", 128f, 140f, 16f, snap?.thumbLX ?: 0f, snap?.thumbLY ?: 0f, s, pressed, seen, accent)
        drawStickEl("r3", 192f, 140f, 16f, snap?.thumbRX ?: 0f, snap?.thumbRY ?: 0f, s, pressed, seen, accent)
        drawFaces(228f, 97f, 12f, faceGlyphs(art), pressed, seen, accent, s)
    } else {
        drawStickEl("l3", 96f, 86f, 17f, snap?.thumbLX ?: 0f, snap?.thumbLY ?: 0f, s, pressed, seen, accent)
        drawDpad(110f, 126f, s, pressed, seen, accent)
        drawStickEl("r3", 206f, 122f, 16f, snap?.thumbRX ?: 0f, snap?.thumbRY ?: 0f, s, pressed, seen, accent)
        drawFaces(232f, 94f, 13f, faceGlyphs(art), pressed, seen, accent, s)
    }

    drawCenter(art, ps, s, pressed, seen, accent)
}

private fun DrawScope.drawBody(art: PadArt, s: Float) {
    when {
        art == PadArt.EIGHTBITDO -> {
            drawRoundRect(PAD_BODY, Offset(38f * s, 58f * s), Size(244f * s, 102f * s), CornerRadius(48f * s, 48f * s), style = Fill)
            drawRoundRect(PAD_LINE, Offset(38f * s, 58f * s), Size(244f * s, 102f * s), CornerRadius(48f * s, 48f * s), style = Stroke(2f * s))
        }
        isPsFamily(art) -> drawBodyPath(bodyPs(s))
        art == PadArt.GENERIC -> drawBodyPath(bodyGeneric(s))
        else -> drawBodyPath(bodyXbox(s))
    }
}

private fun DrawScope.drawBodyPath(p: Path) {
    drawPath(p, PAD_BODY, style = Fill)
    drawPath(p, PAD_LINE, style = Stroke(width = 2f))
}

private fun bodyXbox(s: Float): Path = Path().apply {
    moveTo(160f * s, 58f * s)
    cubicTo(122f * s, 58f * s, 96f * s, 54f * s, 74f * s, 60f * s)
    cubicTo(44f * s, 68f * s, 26f * s, 92f * s, 30f * s, 126f * s)
    cubicTo(33f * s, 152f * s, 52f * s, 170f * s, 78f * s, 166f * s)
    cubicTo(100f * s, 162f * s, 112f * s, 150f * s, 132f * s, 148f * s)
    cubicTo(148f * s, 146f * s, 172f * s, 146f * s, 188f * s, 148f * s)
    cubicTo(208f * s, 150f * s, 220f * s, 162f * s, 242f * s, 166f * s)
    cubicTo(268f * s, 170f * s, 287f * s, 152f * s, 290f * s, 126f * s)
    cubicTo(294f * s, 92f * s, 276f * s, 68f * s, 246f * s, 60f * s)
    cubicTo(224f * s, 54f * s, 198f * s, 58f * s, 160f * s, 58f * s)
    close()
}

private fun bodyPs(s: Float): Path = Path().apply {
    moveTo(160f * s, 60f * s)
    cubicTo(120f * s, 60f * s, 92f * s, 60f * s, 72f * s, 66f * s)
    cubicTo(46f * s, 74f * s, 32f * s, 96f * s, 40f * s, 126f * s)
    cubicTo(46f * s, 152f * s, 68f * s, 172f * s, 90f * s, 164f * s)
    cubicTo(106f * s, 158f * s, 112f * s, 150f * s, 130f * s, 149f * s)
    cubicTo(148f * s, 148f * s, 172f * s, 148f * s, 190f * s, 149f * s)
    cubicTo(208f * s, 150f * s, 214f * s, 158f * s, 230f * s, 164f * s)
    cubicTo(252f * s, 172f * s, 274f * s, 152f * s, 280f * s, 126f * s)
    cubicTo(288f * s, 96f * s, 274f * s, 74f * s, 248f * s, 66f * s)
    cubicTo(228f * s, 60f * s, 200f * s, 60f * s, 160f * s, 60f * s)
    close()
}

private fun bodyGeneric(s: Float): Path = Path().apply {
    moveTo(74f * s, 72f * s)
    cubicTo(50f * s, 72f * s, 40f * s, 86f * s, 42f * s, 108f * s)
    cubicTo(44f * s, 132f * s, 60f * s, 152f * s, 84f * s, 152f * s)
    cubicTo(104f * s, 152f * s, 110f * s, 144f * s, 130f * s, 143f * s)
    cubicTo(150f * s, 142f * s, 170f * s, 142f * s, 190f * s, 143f * s)
    cubicTo(210f * s, 144f * s, 216f * s, 152f * s, 236f * s, 152f * s)
    cubicTo(260f * s, 152f * s, 276f * s, 132f * s, 278f * s, 108f * s)
    cubicTo(280f * s, 86f * s, 270f * s, 72f * s, 246f * s, 72f * s)
    cubicTo(206f * s, 72f * s, 114f * s, 72f * s, 74f * s, 72f * s)
    close()
}

private fun DrawScope.drawTriggers(
    s: Float, snap: ControllerTestSnapshot?, pressed: Set<String>, seen: List<String>,
    accent: Color, ltLabel: String, rtLabel: String,
) {
    drawLitRect(70f, 13f, 52f, 12f, 6f, "lt", s, pressed, seen, accent)
    val ltv = (snap?.triggerL ?: 0f).coerceIn(0f, 1f)
    if (ltv > 0.02f) drawRoundRect(accent, Offset(70f * s, 13f * s), Size(52f * ltv * s, 12f * s), CornerRadius(6f * s, 6f * s))
    drawLitRect(198f, 13f, 52f, 12f, 6f, "rt", s, pressed, seen, accent)
    val rtv = (snap?.triggerR ?: 0f).coerceIn(0f, 1f)
    if (rtv > 0.02f) drawRoundRect(accent, Offset((198f + 52f * (1f - rtv)) * s, 13f * s), Size(52f * rtv * s, 12f * s), CornerRadius(6f * s, 6f * s))
    label(ltLabel, 96f, 9f, s, FAINT)
    label(rtLabel, 224f, 9f, s, FAINT)
}

private fun DrawScope.drawLitRect(
    x: Float, y: Float, w: Float, h: Float, rad: Float, id: String,
    s: Float, pressed: Set<String>, seen: List<String>, accent: Color,
) {
    val down = id in pressed
    drawRoundRect(
        if (down) accent.copy(alpha = 0.20f) else PAD_HOLLOW,
        Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(rad * s, rad * s), style = Fill
    )
    drawRoundRect(
        if (down) accent else if (id in seen) VERIFIED else PAD_STROKE,
        Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(rad * s, rad * s), style = Stroke(width = 1.8f * s)
    )
}

private fun DrawScope.drawDpad(cx: Float, cy: Float, s: Float, pressed: Set<String>, seen: List<String>, accent: Color) {
    drawLitRect(cx - 6f, cy - 22f, 12f, 18f, 3f, "dup", s, pressed, seen, accent)
    drawLitRect(cx - 6f, cy + 4f, 12f, 18f, 3f, "ddown", s, pressed, seen, accent)
    drawLitRect(cx - 22f, cy - 6f, 18f, 12f, 3f, "dleft", s, pressed, seen, accent)
    drawLitRect(cx + 4f, cy - 6f, 18f, 12f, 3f, "dright", s, pressed, seen, accent)
}

private fun DrawScope.drawStickEl(
    id: String, cx: Float, cy: Float, r: Float, tx: Float, ty: Float,
    s: Float, pressed: Set<String>, seen: List<String>, accent: Color,
) {
    drawCircle(PAD_HOLLOW, r * s, Offset(cx * s, cy * s), style = Fill)
    drawCircle(PAD_STROKE, r * s, Offset(cx * s, cy * s), style = Stroke(width = 2f * s))
    val capX = (cx + tx * 6f) * s
    val capY = (cy + ty * 6f) * s
    val deflected = (tx * tx + ty * ty) > 0.35f * 0.35f
    val hot = id in pressed || deflected
    val capR = r * 0.52f
    drawCircle(if (hot) accent.copy(alpha = 0.22f) else PAD_LINE, capR * s, Offset(capX, capY), style = Fill)
    drawCircle(
        if (hot) accent else if (id in seen) VERIFIED else PAD_STROKE,
        capR * s, Offset(capX, capY), style = Stroke(width = 2.4f * s)
    )
}

private fun DrawScope.drawFaces(
    cx: Float, cy: Float, r: Float, glyphs: Map<String, Pair<String, Color>>,
    pressed: Set<String>, seen: List<String>, accent: Color, s: Float,
) {
    val d = r + 7f
    val positions = listOf(
        "y" to Offset(cx, cy - d), "b" to Offset(cx + d, cy),
        "a" to Offset(cx, cy + d), "x" to Offset(cx - d, cy)
    )
    for ((id, pos) in positions) {
        val g = glyphs[id] ?: continue
        val (ch, col) = g
        val down = id in pressed
        drawCircle(if (down) col.copy(alpha = 0.22f) else PAD_HOLLOW, r * s, Offset(pos.x * s, pos.y * s), style = Fill)
        drawCircle(
            if (down) accent else if (id in seen) VERIFIED else col,
            r * s, Offset(pos.x * s, pos.y * s), style = Stroke(width = 2.4f * s)
        )
        label(ch, pos.x, pos.y + 0.5f, s, if (down) accent else col, bold = true, sizeUnits = 14f)
    }
}

private fun DrawScope.drawCenter(
    art: PadArt, ps: Boolean, s: Float, pressed: Set<String>, seen: List<String>, accent: Color,
) {
    if (ps) {
        // Touchpad (bigger on DualSense), then Share/Options + PS button.
        val tpW = if (art == PadArt.DUALSENSE) 82f else 74f
        val tpX = 160f - tpW / 2f
        drawRoundRect(PAD_HOLLOW, Offset(tpX * s, 58f * s), Size(tpW * s, 32f * s), CornerRadius(7f * s, 7f * s), style = Fill)
        drawRoundRect(PAD_STROKE, Offset(tpX * s, 58f * s), Size(tpW * s, 32f * s), CornerRadius(7f * s, 7f * s), style = Stroke(width = 1.6f * s))
        if (art == PadArt.DUALSENSE) drawCircle(FAINT, 2.5f * s, Offset(160f * s, 94f * s), style = Fill) // mic
        if (art == PadArt.DUALSHOCK4) drawLine(PCROSS, Offset(140f * s, 56f * s), Offset(180f * s, 56f * s), strokeWidth = 2f * s) // lightbar hint
        drawLitRect(108f, 63f, 9f, 13f, 2f, "back", s, pressed, seen, accent)     // Share
        drawLitRect(203f, 63f, 9f, 13f, 2f, "start", s, pressed, seen, accent)    // Options
        drawGuide(160f, 150f, 7f, s, pressed, seen, accent)                       // PS button
    } else if (art == PadArt.SWITCH_PRO) {
        // Minus / Plus / Home / Capture.
        drawLitRect(136f, 87f, 14f, 4f, 2f, "back", s, pressed, seen, accent)     // minus
        drawLitRect(170f, 82f, 12f, 12f, 2f, "start", s, pressed, seen, accent)   // plus
        label("+", 176f, 89f, s, NEUTRAL, bold = true, sizeUnits = 11f)
        drawGuide(160f, 150f, 7f, s, pressed, seen, accent)                       // Home
        drawRoundRect(PAD_STROKE, Offset(150f * s, 104f * s), Size(9f * s, 9f * s), CornerRadius(2f * s, 2f * s), style = Stroke(width = 1.5f * s)) // Capture
    } else {
        // Xbox 360 / Xbox / 8BitDo / Generic / Steam.
        drawGuide(160f, 72f, if (art == PadArt.STEAM) 11f else 10f, s, pressed, seen, accent)
        drawLitRect(140f, 88f, 12f, 9f, 2f, "back", s, pressed, seen, accent)
        drawLitRect(168f, 88f, 12f, 9f, 2f, "start", s, pressed, seen, accent)
        if (art == PadArt.XBOX_MODERN) // Share button hint
            drawRoundRect(PAD_STROKE, Offset(154f * s, 104f * s), Size(12f * s, 8f * s), CornerRadius(2f * s, 2f * s), style = Stroke(width = 1.5f * s))
    }
}

private fun DrawScope.drawGuide(cx: Float, cy: Float, r: Float, s: Float, pressed: Set<String>, seen: List<String>, accent: Color) {
    val down = "guide" in pressed
    drawCircle(if (down) accent.copy(alpha = 0.22f) else PAD_HOLLOW, r * s, Offset(cx * s, cy * s), style = Fill)
    drawCircle(
        if (down) accent else if ("guide" in seen) VERIFIED else PAD_STROKE,
        r * s, Offset(cx * s, cy * s), style = Stroke(width = 2f * s)
    )
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
