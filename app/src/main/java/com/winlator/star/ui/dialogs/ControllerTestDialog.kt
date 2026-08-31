package com.winlator.star.ui.dialogs

import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.star.ui.XServerDialogState
import kotlin.math.min
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Controls > Players — landscape controller-test popup. A SUPERSET of the old Players sub-tab: the
// LEFT rail keeps every per-device slot control (Auto/P1-4/Ignore dropdown, status subtitle, the OSC
// row, global Reset Input) and adds per-card Identify + Reset; the RIGHT panel draws the selected
// pad's physical picture and lights its buttons/sticks/dpad/triggers LIVE as they're pressed.
//
// WHY A Dialog WINDOW (same reason as ControllerToastOverlay/PauseBoxOverlay): the game renders into a
// SurfaceView composited ABOVE the host ComposeView, so an inline Box would hide behind the frame. The
// window is made FLAG_NOT_FOCUSABLE (so gamepad key/axis events fall THROUGH to the Activity's
// dispatch chokepoints, where the test-mode fork feeds the visualizer) but stays TOUCHABLE (finger
// taps drive this UI). Isolation is armed via onControllerTestActive tied to this composable's
// visibility, so the game's real input path is byte-for-byte unchanged whenever the popup is closed.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

// Element ids for the pad picture + the verified tally (17, matching the mock's sweep).
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

private fun pressedSet(snap: XServerDialogState.ControllerTestSnapshot?): Set<String> {
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

@Composable
fun ControllerTestDialog(state: XServerDialogState) {
    // Arm/disarm input isolation with the popup's visibility. onDispose also fires on a normal close;
    // the Activity ALSO clears the flag in onPause/onStop for the background-SIGSTOP case.
    DisposableEffect(Unit) {
        state.onControllerTestActive?.invoke(true)
        onDispose {
            state.clearControllerTestSnapshot()
            state.onControllerTestActive?.invoke(false)
        }
    }
    // Devices hot-plug — pull a fresh snapshot when the popup opens.
    LaunchedEffect(Unit) { state.onPlayerSlotsRefresh?.run() }

    Dialog(
        onDismissRequest = { state.dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                // NOT focusable → gamepad key + joystick-axis events are NOT captured by this window and
                // fall through to the Activity chokepoints. The window stays touchable, so finger taps
                // still drive the UI. This is the crux of the axis-fall-through spike.
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
            }
        }
        ControllerTestScaffold(state)
    }
}

@Composable
private fun ControllerTestScaffold(state: XServerDialogState) {
    val cs = MaterialTheme.colorScheme
    val cfg = LocalConfiguration.current
    // Fit-to-screen: never exceed the device's landscape bounds (minus margins) so small screens can't
    // clip. Left rail + right panel each scroll; nothing is pinned off-screen.
    val dialogW = min(940, cfg.screenWidthDp - 24).coerceAtLeast(320).dp
    val dialogH = min(470, cfg.screenHeightDp - 24).coerceAtLeast(240).dp

    // Which device the RIGHT panel is testing — hoisted here so both halves share one selection.
    var selected by remember { mutableStateOf<String?>(null) }

    // Full-screen scrim that consumes stray taps so they don't poke the game behind the popup.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.padding(12.dp).width(dialogW).height(dialogH),
            shape = RoundedCornerShape(18.dp),
            color = cs.surface,
            contentColor = cs.onSurface,
            shadowElevation = 26.dp,
        ) {
            Row(Modifier.fillMaxSize()) {
                PlayerRail(
                    state = state,
                    selected = selected,
                    onSelect = { selected = it },
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(min(320, (dialogW.value * 0.42f).roundToInt()).dp)
                        .background(cs.surfaceVariant.copy(alpha = 0.35f))
                        .padding(12.dp)
                )
                HorizontalDivider(
                    modifier = Modifier.fillMaxHeight().width(1.dp),
                    color = cs.outline
                )
                TestPanel(
                    state = state,
                    selected = selected,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(14.dp)
                )
            }
        }
    }
}

// ── LEFT RAIL — player/device cards (superset of the old Players sub-tab) ────────────────────────
@Composable
private fun PlayerRail(
    state: XServerDialogState,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val cs = MaterialTheme.colorScheme
    val rows by state.playerSlots.collectAsState()

    // Keep a valid selection as the list changes (default to the first game controller, else first row).
    LaunchedEffect(rows) {
        if (rows.none { it.descriptor == selected }) {
            onSelect(
                rows.firstOrNull { it.isGameController }?.descriptor
                    ?: rows.firstOrNull()?.descriptor
            )
        }
    }

    Column(modifier.fillMaxSize()) {
        Text("Player slots", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            "Pads emulate as Xbox 360 to the game; the picture shows the physical pad you're holding.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (rows.isEmpty()) {
                Text(
                    "No input devices detected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            } else {
                rows.forEach { row ->
                    PlayerCard(row, row.descriptor == selected, state) { onSelect(row.descriptor) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        // Global "Reset Input" — rebuild the whole fake-input transport in place (existing recovery).
        OutlinedButton(
            onClick = {
                state.onResetInput?.run()
                state.onPlayerSlotsRefresh?.run()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) { Text("Reset Input") }
        Text(
            "Re-handshake controllers & on-screen if input stops responding.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun PlayerCard(
    row: XServerDialogState.PlayerSlotRow,
    selected: Boolean,
    state: XServerDialogState,
    onSelect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary
    val subtitle = when {
        row.currentSlot >= 0 -> "Currently Player ${row.currentSlot + 1}"
        row.override == XServerDialogState.SLOT_IGNORE -> "Ignored"
        else -> "Unassigned"
    } + if (row.isOnScreen) " · on-screen" else ""

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else cs.surface)
            .border(
                1.dp,
                if (selected) accent else cs.outline,
                RoundedCornerShape(12.dp)
            )
            .clickable { onSelect() }
            .padding(10.dp)
    ) {
        Text(
            row.displayName,
            color = cs.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        SlotPicker(row, state)

        // Identify + per-card Reset appear on the selected card only, to keep the rail uncluttered.
        if (selected) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { state.onControllerIdentify?.invoke(row.currentSlot) },
                    enabled = row.currentSlot >= 0 && row.hasVibrator,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                ) { Text("Identify", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        // Per-card reset: clear this device's override → Auto, then rebuild the pipeline.
                        state.onPlayerSlotChanged?.invoke(row.descriptor, XServerDialogState.SLOT_AUTO)
                        state.onResetInput?.run()
                        state.onPlayerSlotsRefresh?.run()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                ) { Text("Reset", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SlotPicker(row: XServerDialogState.PlayerSlotRow, state: XServerDialogState) {
    val options = remember {
        buildList {
            add("Auto" to XServerDialogState.SLOT_AUTO)
            for (i in 0 until 4) add("Player ${i + 1}" to i)
            add("Ignore" to XServerDialogState.SLOT_IGNORE)
        }
    }
    val selectedLabel = options.firstOrNull { it.second == row.override }?.first ?: "Auto"
    var expanded by remember(row.descriptor, row.override) { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(9.dp),
        ) { Text("Slot: $selectedLabel", fontSize = 12.sp) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    if (value != row.override) {
                        state.onPlayerSlotChanged?.invoke(row.descriptor, value)
                    }
                })
            }
        }
    }
}

// ── RIGHT PANEL — live controller picture + readouts ─────────────────────────────────────────────
@Composable
private fun TestPanel(state: XServerDialogState, selected: String?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary
    val rows by state.playerSlots.collectAsState()
    val snap by state.controllerTestSnapshot.collectAsState()
    val selectedRow = rows.firstOrNull { it.descriptor == selected }

    // Manual picture-type override via the segmented control; null = auto-detect from the live pad
    // (matching the mock's assignGamepads) with a fall-back to the selected row's name.
    var manualIsPs by remember(selected) { mutableStateOf<Boolean?>(null) }
    val autoIsPs = when {
        snap != null && snap!!.padType == 1 -> true          // ExternalController.PadType.PS ordinal
        snap != null && snap!!.padType == 0 -> false          // XBOX
        else -> {
            val n = selectedRow?.displayName?.lowercase() ?: ""
            n.contains("playstation") || n.contains("dualshock") || n.contains("dualsense")
        }
    }
    val isPs = manualIsPs ?: autoIsPs

    val pressed = pressedSet(snap)
    // Verified tally — grey out (mark verified) each element once it's been pressed this session.
    val seen = remember(selected) { mutableStateListOf<String>() }
    var lastInput by remember(selected) { mutableStateOf("—") }
    LaunchedEffect(pressed) {
        pressed.forEach {
            if (it !in seen && it in ALL_ELEMENTS) seen.add(it)
        }
        pressed.firstOrNull { it in ALL_ELEMENTS }?.let { lastInput = friendlyName(it) }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        // Header row.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Controller Test", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    selectedRow?.displayName ?: "No controller selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TypeSegment(isPs) { manualIsPs = it }
        }
        Spacer(Modifier.height(6.dp))

        // Live-connection + battery banner.
        val live = snap != null && snap!!.deviceId >= 0
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
            val batt = snap?.batteryPct ?: -1
            if (batt in 0..100) {
                Text("Battery $batt%", fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
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
                drawPad(isPs, snap, pressed, seen, accent)
            }
        }
        Spacer(Modifier.height(10.dp))

        // Readout strip.
        val ls = snap?.let { fmt2(it.thumbLX) + ", " + fmt2(it.thumbLY) } ?: "0.00, 0.00"
        val rs = snap?.let { fmt2(it.thumbRX) + ", " + fmt2(it.thumbRY) } ?: "0.00, 0.00"
        val tr = snap?.let { pct(it.triggerL) + " · " + pct(it.triggerR) } ?: "0% · 0%"
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Last input", lastInput, Modifier.weight(1f))
            StatTile("Left stick", ls, Modifier.weight(1f))
            StatTile("Right stick", rs, Modifier.weight(1f))
            StatTile("Triggers L·R", tr, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))

        // Verified checklist tally + footer actions.
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
            Button(
                onClick = { state.onControllerIdentify?.invoke(selectedRow?.currentSlot ?: -1) },
                enabled = (selectedRow?.currentSlot ?: -1) >= 0 && (selectedRow?.hasVibrator == true),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
            ) { Text("Identify", fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { state.dismiss() }) { Text("Done") }
        }
    }
}

@Composable
private fun TypeSegment(isPs: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
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
private fun SegButton(label: String, on: Boolean, cs: androidx.compose.material3.ColorScheme, onClick: () -> Unit) {
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

private fun fmt2(v: Float): String {
    val r = (v * 100).roundToInt() / 100f
    return String.format(java.util.Locale.US, "%.2f", r)
}

private fun pct(v: Float): String = "${(v.coerceIn(0f, 1f) * 100).roundToInt()}%"

// ── The parametric pad picture (Compose Canvas, mirroring the mock's padSVG in a 420×250 space) ──
private fun DrawScope.drawPad(
    isPs: Boolean,
    snap: XServerDialogState.ControllerTestSnapshot?,
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

    // Body outline (simplified rounded gamepad silhouette).
    drawRoundRect(
        color = bodyFill,
        topLeft = off(48f, 54f),
        size = Size(px(324f), px(150f)),
        cornerRadius = CornerRadius(px(60f), px(60f)),
        style = Fill
    )
    drawRoundRect(
        color = bodyStroke,
        topLeft = off(48f, 54f),
        size = Size(px(324f), px(150f)),
        cornerRadius = CornerRadius(px(60f), px(60f)),
        style = Stroke(width = px(2f))
    )

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

    // Sticks (well + cap offset by thumb, ring when clicked or deflected).
    drawStick(this, stickL, snap?.thumbLX ?: 0f, snap?.thumbLY ?: 0f, "l3" in pressed, s, accent, well, wellStroke, "l3" in seen, verified)
    drawStick(this, stickR, snap?.thumbRX ?: 0f, snap?.thumbRY ?: 0f, "r3" in pressed, s, accent, well, wellStroke, "r3" in seen, verified)

    // Face buttons (diamond around (312,116)).
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
        drawCircle(
            color = if (down) col else Color(0xFF1E2A36),
            radius = px(15f), center = off(pos.x, pos.y), style = Fill
        )
        drawCircle(
            color = if (id in seen && !down) verified else col,
            radius = px(15f), center = off(pos.x, pos.y), style = Stroke(width = px(1.8f))
        )
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
        drawCircle(
            color = if (hot) accent else Color(0xFF2B3A49),
            radius = 16f * s, center = Offset(capX, capY), style = Fill
        )
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
