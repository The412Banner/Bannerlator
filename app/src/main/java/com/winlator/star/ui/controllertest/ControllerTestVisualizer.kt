package com.winlator.star.ui.controllertest

import android.view.InputDevice
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
// Order matters: the snapshot stores PadArt.ordinal, so GAMECUBE/SNES are appended at the end (they are
// selectable-only via the manual override and never auto-returned by classifyPadArt).
enum class PadArt { XBOX_360, XBOX_MODERN, DUALSENSE, DUALSHOCK4, SWITCH_PRO, EIGHTBITDO, GENERIC, DUALSHOCK3, STEAM, GAMECUBE, SNES }

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
    PadArt.GAMECUBE -> "GameCube"
    PadArt.SNES -> "SNES"
}

private fun padArtSafe(ordinal: Int): PadArt = PadArt.values().getOrElse(ordinal) { PadArt.GENERIC }

/** Resolve which family art to show: the live pad's detected family, else a name heuristic, else
 *  Generic. Exposed so the binder hosts pick the same art the test panel does. */
internal fun padArtOf(snapshot: ControllerTestSnapshot?, nameHint: String?): PadArt = when {
    snapshot != null -> padArtSafe(snapshot.padArt)
    nameHint != null -> classifyPadArtByName(nameHint)
    else -> PadArt.GENERIC
}

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

internal fun pressedSet(snap: ControllerTestSnapshot?): Set<String> {
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

        // The pad picture — a pre-rendered per-family PNG with a live-highlight overlay. Kept COMPACT
        // and centered (PadArtView caps its own size and keeps the 500:350 art aspect).
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PadArtView(art = art, snapshot = snapshot)
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

// ── Pad art (pre-rendered PNG + live-highlight overlay + tap-to-bind) ────────────────────────────
internal val BIND_BLUE = Color(0xFF7AA0FF)

/** Resolve the manifest element id to LIGHT for a pressed GamepadState logical id (a/b/…/dup/back/…),
 *  honoring per-family names (back→share on DS4, →minus on Switch, …) and presence on that pad. */
private fun highlightManifest(ref: PadArtRef, logical: String): String? {
    val cands = when (logical) {
        "a", "b", "x", "y", "lt", "rt", "l3", "r3" -> listOf(logical)
        "lb" -> listOf("lb", "l")
        "rb" -> listOf("rb", "r")
        "dup" -> listOf("dpad_up"); "ddown" -> listOf("dpad_down")
        "dleft" -> listOf("dpad_left"); "dright" -> listOf("dpad_right")
        "back" -> listOf("back", "select", "share", "minus", "view")
        "start" -> listOf("start", "options", "plus", "menu")
        "guide" -> listOf("guide", "home")
        else -> emptyList()
    }
    return cands.firstOrNull { ref.el.containsKey(it) }
}

/** Manifest element id (as tapped on the pad) → BindTarget id. */
internal fun manifestToBindId(manifestId: String): String? = when (manifestId) {
    "a", "b", "x", "y", "lt", "rt", "l3", "r3", "lb", "rb", "start", "back", "guide" -> manifestId
    "dpad_up" -> "dup"; "dpad_down" -> "ddown"; "dpad_left" -> "dleft"; "dpad_right" -> "dright"
    "l" -> "lb"; "r" -> "rb"
    "lstick" -> "l3"; "rstick" -> "r3"; "cstick" -> "r3"
    "home" -> "guide"
    "options" -> "start"; "plus" -> "start"; "menu" -> "start"
    "select" -> "back"; "share" -> "back"; "minus" -> "back"; "view" -> "back"
    else -> null
}

/** BindTarget id → manifest element id (for bound markers + the selection ring). */
private fun bindToManifest(ref: PadArtRef, bindId: String): String? {
    val cands = when (bindId) {
        "a", "b", "x", "y", "lt", "rt", "l3", "r3" -> listOf(bindId)
        "lb" -> listOf("lb", "l"); "rb" -> listOf("rb", "r")
        "dup" -> listOf("dpad_up"); "ddown" -> listOf("dpad_down")
        "dleft" -> listOf("dpad_left"); "dright" -> listOf("dpad_right")
        "back" -> listOf("back", "select", "share", "minus", "view")
        "start" -> listOf("start", "options", "plus", "menu")
        "guide" -> listOf("guide", "home")
        "lsu", "lsd", "lsl", "lsr" -> listOf("lstick")
        "rsu", "rsd", "rsl", "rsr" -> listOf("rstick", "cstick")
        else -> emptyList()
    }
    return cands.firstOrNull { ref.el.containsKey(it) }
}

/**
 * The pad picture: a pre-rendered per-family PNG fit into a compact bounded box (keeping the 500:350
 * art aspect), with a Compose Canvas overlay that (a) glows each pressed element live, (b) in bind mode
 * marks bound elements + rings the selected one, and (c) hit-tests taps to the nearest element for
 * tap-to-bind. Coords come from the hardcoded PAD_REFS manifest.
 */
@Composable
internal fun PadArtView(
    art: PadArt,
    snapshot: ControllerTestSnapshot?,
    modifier: Modifier = Modifier,
    boundBindIds: Set<String> = emptySet(),
    selectedBindId: String? = null,
    onTapBind: ((String) -> Unit)? = null,
) {
    val ref = PAD_REFS[art] ?: PAD_REFS[PadArt.GENERIC]!!
    val accent = MaterialTheme.colorScheme.primary
    val pressedManifest = remember(snapshot, art) {
        val out = HashSet<String>()
        for (p in pressedSet(snapshot)) highlightManifest(ref, p)?.let { out.add(it) }
        out
    }
    val boundManifest = remember(boundBindIds, art) {
        val out = HashSet<String>()
        for (bid in boundBindIds) bindToManifest(ref, bid)?.let { out.add(it) }
        out
    }
    val selectedManifest = selectedBindId?.let { bindToManifest(ref, it) }

    Box(
        modifier
            .widthIn(max = 248.dp)
            .heightIn(max = 136.dp)
            .aspectRatio(ref.vbW / ref.vbH)
    ) {
        Image(
            painter = painterResource(ref.drawableRes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(
            Modifier.matchParentSize().then(
                if (onTapBind != null) Modifier.pointerInput(art) {
                    detectTapGestures { pos ->
                        val sx = size.width / ref.vbW
                        val sy = size.height / ref.vbH
                        val sr = maxOf(sx, sy)
                        var best: String? = null
                        var bestD = Float.MAX_VALUE
                        for ((id, t) in ref.el) {
                            val cx = t.first * sx; val cy = t.second * sy; val rr = t.third * sr
                            val dx = pos.x - cx; val dy = pos.y - cy; val d = dx * dx + dy * dy
                            if (d <= rr * rr && d < bestD) { bestD = d; best = id }
                        }
                        best?.let { manifestToBindId(it) }?.let { onTapBind(it) }
                    }
                } else Modifier
            )
        ) {
            val sx = size.width / ref.vbW
            val sy = size.height / ref.vbH
            val sr = maxOf(sx, sy)
            for (mid in boundManifest) {
                val t = ref.el[mid] ?: continue
                drawCircle(BIND_BLUE, 4f, Offset(t.first * sx + t.third * sr * 0.6f, t.second * sy - t.third * sr * 0.6f))
            }
            for (mid in pressedManifest) {
                val t = ref.el[mid] ?: continue
                val rr = t.third * sr
                drawCircle(accent.copy(alpha = 0.30f), rr * 1.5f, Offset(t.first * sx, t.second * sy))
                drawCircle(accent, rr, Offset(t.first * sx, t.second * sy), style = Stroke(width = 2f))
            }
            selectedManifest?.let { mid ->
                val t = ref.el[mid]
                if (t != null) {
                    drawCircle(accent, t.third * sr * 1.25f, Offset(t.first * sx, t.second * sy), style = Stroke(width = 2.5f))
                }
            }
        }
    }
}
