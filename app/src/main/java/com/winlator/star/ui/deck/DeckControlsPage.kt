package com.winlator.star.ui.deck

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.ExternalController
import com.winlator.star.ui.LocalXmbHintInset
import com.winlator.star.ui.screens.InputControlsDeckState
import com.winlator.star.ui.screens.InputControlsScreen
import com.winlator.star.ui.screens.MenuItemDivider
import com.winlator.star.ui.screens.OnScreenLayoutPreview
import com.winlator.star.ui.screens.ScrollingDropdownMenu
import com.winlator.star.ui.screens.outlinedMenuCard
import com.winlator.star.ui.screens.pulsePad
import com.winlator.star.ui.screens.xmbMenuBack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The Deck / XMB Controls page. It has no header or banner of its own: the sub-tab strip (the rail in landscape, L2/R2 to cycle) and
// the information are all there is. The state, the dialogs and every action are InputControlsScreen's own, handed over through its
// InputControlsDeckState, so Classic (which draws the same state as its tabbed screen) is untouched.
//
//   On-screen   the profiles as a list, the selected one's layout preview and its actions
//   Controller  the pads (and the Default / Any template) as a list, the selected one's bindings as a table and its actions
//   Assign      the four player slots and who is pinned to each (DeckControlsDevices.kt)
//   Device      the connected controllers and the device-level settings (DeckControlsDevices.kt)

/** The page: the tab strip over the hosted screen. */
@Composable
internal fun DeckControlsPage() {
    // XMB only: the plain Deck keeps its header over the classic Input Controls screen (DeckPages.kt).
    if (!rememberXmbHome()) {
        DeckControlsPageHosted()
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // The Controller tab's selected pad, kept here: the tab's own state would reset every time another tab is shown.
    var padId by rememberSaveable { mutableStateOf(ControlsProfile.DEFAULT_CONTROLLER_ID) }
    val tabs = listOf(
        DeckTabItem("On-screen", Icons.Filled.TouchApp),
        DeckTabItem("Controller", Icons.Filled.Gamepad),
        DeckTabItem("Assign", Icons.Filled.People),
        DeckTabItem("Device", Icons.Filled.PhoneAndroid),
    )
    if (deckRails()) {
        // Landscape: the shell draws the tabs as the rail beside the page and the title line is left out, so the page gets the room.
        DeckTabStrip(tabs = tabs, selected = tab, onSelect = { tab = it }, label = "Tab")
        Box(modifier = Modifier.fillMaxSize()) {
            InputControlsScreen(deckHost = { s -> DeckControlsContent(tab, s, padId) { padId = it } })
        }
        return
    }
    val gutter = deckGutter()
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        DeckTabStrip(
            tabs = tabs,
            selected = tab,
            onSelect = { tab = it },
            label = "Tab",
            modifier = Modifier.padding(horizontal = gutter),
        )
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            InputControlsScreen(deckHost = { s -> DeckControlsContent(tab, s, padId) { padId = it } })
        }
    }
}

/** The content of the [tab] picked on the strip. */
@Composable
private fun DeckControlsContent(tab: Int, s: InputControlsDeckState, padId: String, onPadId: (String) -> Unit) {
    // Nothing selected yet: take the first profile, so the layout and the bindings have something to show.
    LaunchedEffect(s.profiles.size, s.current) {
        if (s.current == null) {
            val first = s.profiles.firstOrNull()
            if (first != null) s.select(first)
        }
    }
    when (tab) {
        0 -> DeckOnScreenTab(s)
        1 -> DeckControllerTab(s, padId, onPadId)
        2 -> DeckAssignTab(s)
        else -> DeckDeviceTab(s)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Pieces the four tabs share
// ---------------------------------------------------------------------------------------------------------------------

/** Intentional status colours (connected / not connected / paired), kept off-theme as in Classic. */
internal val CTL_GREEN = Color(0xFF4CAF50)
internal val CTL_RED = Color(0xFFE57373)

/** A card's shadow reaches about this far to the side: the panes keep it clear of their scroll edge. */
private val CTL_SHADOW = 12.dp

/**
 * The page's body: two panes side by side in landscape (each scrolls on its own), one scrolling column elsewhere.
 * The bottom padding keeps the last card above the floating Back / Search hints.
 */
@Composable
internal fun CtlPanes(
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
    leftWeight: Float = 1f,
    rightWeight: Float = 1f,
) {
    val edge = (deckGutter() - CTL_SHADOW).coerceAtLeast(0.dp)
    val bottom = 16.dp + LocalXmbHintInset.current
    if (deckRails()) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = edge)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(leftWeight)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = CTL_SHADOW, end = CTL_SHADOW, top = 10.dp, bottom = bottom),
                content = left,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(rightWeight)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = CTL_SHADOW, end = CTL_SHADOW, top = 10.dp, bottom = bottom),
                content = right,
            )
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = edge + CTL_SHADOW, end = edge + CTL_SHADOW, top = 10.dp, bottom = bottom),
        ) {
            left()
            right()
        }
    }
}

/**
 * One tappable row of a list card: a flat D-pad stop (the gliding outline in the XMB interface, the Deck focus ring elsewhere).
 * Inset a little from the card's edge so the outline sits inside its rounded corners. [selected] tints it and the caller marks it.
 */
@Composable
internal fun CtlRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.(cs: ColorScheme) -> Unit,
) {
    val shape = deckShape(12.dp)
    DeckInk { item, cs ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .deckFocusRingInk(shape, item, scaleTo = 1f)
                .clip(shape)
                .background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(onClick = onClick)
                .heightIn(min = 46.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            content(cs)
        }
    }
}

/** A row of a list card that only informs (not a D-pad stop). */
@Composable
internal fun CtlInfoRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(cs: ColorScheme) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .heightIn(min = 46.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        content(cs)
    }
}

/**
 * A "label ... value" row that opens a menu of [options] and reports the index picked. A flat D-pad stop; the menu is the
 * app's outlined one, B closes it in the XMB interface.
 */
@Composable
internal fun CtlChoiceRow(
    label: String,
    value: String,
    options: List<String>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        CtlRow(onClick = { open = true }) { cs ->
            Column(Modifier.weight(1f)) {
                Text(label, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                value,
                color = cs.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = cs.onSurfaceVariant)
        }
        ScrollingDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.xmbMenuBack { open = false },
        ) {
            options.forEachIndexed { i, option ->
                if (i > 0) MenuItemDivider()
                DropdownMenuItem(text = { Text(option) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}

/** A short caption or empty-state line inside a card. */
@Composable
internal fun CtlNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** A small tinted pill ("12 bindings", "Connected"). */
@Composable
internal fun CtlChip(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(deckShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The round status dot of a connected / not connected device. */
@Composable
internal fun CtlDot(color: Color) {
    Box(Modifier.size(9.dp).background(color, CircleShape))
}

// ---------------------------------------------------------------------------------------------------------------------
// On-screen
// ---------------------------------------------------------------------------------------------------------------------

/** Profiles on the left (name and what each holds), the selected one's overlay preview and actions on the right. */
@Composable
private fun DeckOnScreenTab(s: InputControlsDeckState) {
    CtlPanes(
        left = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeckButton("New profile", Icons.Filled.Add, s.newProfile)
                DeckButton("Import", Icons.Filled.FileDownload, s.importProfile)
            }
            DeckCard {
                if (s.profiles.isEmpty()) {
                    CtlNote("No profiles yet. Create one with New profile, or import one.")
                } else {
                    Spacer(Modifier.height(4.dp))
                    s.profiles.forEachIndexed { i, p ->
                        if (i > 0) DeckRowDivider()
                        ProfileRow(p, s)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        right = { ProfileDetailCard(s) },
        leftWeight = 1f,
        rightWeight = 1.15f,
    )
}

/** What a profile's file holds: its on-screen control count and its physical binding count (null while unread or unreadable). */
private fun profileCounts(file: java.io.File): Pair<Int, Int>? = runCatching {
    val data = org.json.JSONObject(file.readText())
    val controls = data.optJSONArray("elements")?.length() ?: 0
    var binds = 0
    val pads = data.optJSONArray("controllers")
    if (pads != null) {
        for (i in 0 until pads.length()) {
            binds += pads.optJSONObject(i)?.optJSONArray("controllerBindings")?.length() ?: 0
        }
    }
    Pair(controls, binds)
}.getOrNull()

@Composable
private fun ProfileRow(p: ControlsProfile, s: InputControlsDeckState) {
    val context = LocalContext.current
    val selected = s.current === p
    val counts by produceState<Pair<Int, Int>?>(null, p.id, s.layoutRev, s.binds) {
        value = withContext(Dispatchers.IO) { profileCounts(ControlsProfile.getProfileFile(context, p.id)) }
    }
    CtlRow(onClick = { s.select(p) }, selected = selected) { cs ->
        Column(Modifier.weight(1f)) {
            Text(
                p.getName(),
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val c = counts
            Text(
                text = if (c == null) " " else listOf(
                    if (c.first > 0) "${c.first} on-screen controls" else "No on-screen layout",
                    if (c.second > 0) "${c.second} bindings" else "No bindings",
                ).joinToString(" · "),
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = cs.primary, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileDetailCard(s: InputControlsDeckState) {
    val p = s.current
    val cs = MaterialTheme.colorScheme
    DeckCard {
        if (p == null) {
            CtlNote("No profile selected. Pick one from the list, or create a new one.")
        } else {
            var exportOpen by remember { mutableStateOf(false) }
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        p.getName(),
                        color = cs.onSurface,
                        fontFamily = SoraFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (s.binds > 0) CtlChip("${s.binds} bindings", CTL_GREEN)
                }
                if (s.hasLayout) {
                    // The preview's tap-catcher opens the editor on a tap; the D-pad uses the Edit layout button instead.
                    Box(Modifier.widthIn(max = 380.dp).focusProperties { canFocus = false }) {
                        OnScreenLayoutPreview(profileId = p.id, refreshKey = s.layoutRev, onOpenEditor = { s.editLayout(p) })
                    }
                } else {
                    Text(
                        "No on-screen layout yet. Edit layout adds one.",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DeckButton("Edit layout", Icons.Filled.Edit, { s.editLayout(p) }, primary = true)
                    DeckButton("Rename", null, { s.rename(p) })
                    DeckButton("Duplicate", Icons.Filled.ContentCopy, { s.duplicate(p) })
                    DeckButton("Delete", Icons.Filled.Delete, { s.delete(p) })
                    Box {
                        DeckButton("Export", Icons.Filled.FileUpload, { exportOpen = true })
                        DropdownMenu(
                            expanded = exportOpen,
                            onDismissRequest = { exportOpen = false },
                            modifier = Modifier.outlinedMenuCard().xmbMenuBack { exportOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_control_profile_icpx)) },
                                leadingIcon = { Icon(Icons.Filled.FileUpload, null) },
                                onClick = { exportOpen = false; s.export(p, false) },
                            )
                            MenuItemDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_control_profile_icp_legacy)) },
                                leadingIcon = { Icon(Icons.Filled.FileUpload, null) },
                                onClick = { exportOpen = false; s.export(p, true) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Controller
// ---------------------------------------------------------------------------------------------------------------------

/** A connected (or saved) pad as the page shows it. [device] is null for a pad that is not plugged in; [battery] is -1 when unknown. */
internal class CtlPad(val device: InputDevice?, val connected: Boolean, val battery: Int, val vibrator: Boolean)

/** The live facts of [c]: connected, battery and whether it has a vibrator to test. */
internal fun ctlPadOf(c: ExternalController): CtlPad {
    val connected = c.isConnected()
    val device = if (connected) InputDevice.getDevice(c.getDeviceId()) else null
    var battery = -1
    if (device != null && Build.VERSION.SDK_INT >= 31) {
        try {
            val bs = device.batteryState
            if (bs != null && bs.isPresent) {
                val cap = bs.capacity
                if (cap >= 0f) battery = Math.round(cap * 100f)
            }
        } catch (_: Throwable) {}
    }
    val vibrator = device?.vibrator?.hasVibrator() == true
    return CtlPad(device, connected, battery, vibrator)
}

/** "Not connected · 12 bindings · battery 80%". */
private fun ctlPadLine(pad: CtlPad, bindings: Int): String {
    val parts = ArrayList<String>()
    if (!pad.connected) parts.add("Not connected")
    parts.add(if (bindings > 0) "$bindings bindings" else "No bindings")
    if (pad.battery >= 0) parts.add("battery ${pad.battery}%")
    return parts.joinToString(" · ")
}

/** The pads (and the Default / Any template) on the left, the selected one's bindings and actions on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckControllerTab(s: InputControlsDeckState, selId: String, onSelId: (String) -> Unit) {
    val defaultId = ControlsProfile.DEFAULT_CONTROLLER_ID
    val pads = s.controllers
    // A pad that was unplugged and has no saved bindings is gone from the list: fall back to the template.
    val activeId = if (selId == defaultId || pads.any { it.getId() == selId }) selId else defaultId
    val isDefault = activeId == defaultId
    val target: ExternalController? = if (isDefault) s.defaultTemplate else pads.firstOrNull { it.getId() == activeId }
    val count = target?.getControllerBindingCount() ?: 0

    CtlPanes(
        left = {
            DeckButton("Test and bind", Icons.Filled.Gamepad, s.testAndBind, primary = true)
            DeckCard {
                Spacer(Modifier.height(4.dp))
                CtlChoiceRow(
                    label = "Profile",
                    value = s.current?.getName() ?: "None",
                    options = s.profiles.map { it.getName() },
                    onPick = { i -> s.select(s.profiles.getOrNull(i)) },
                )
                DeckRowDivider()
                CtlRow(onClick = { onSelId(defaultId) }, selected = isDefault) { cs ->
                    Icon(Icons.Filled.Gamepad, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Default / Any controller", color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val n = s.defaultTemplate?.getControllerBindingCount() ?: 0
                        Text(
                            if (n > 0) "$n bindings · new pads start from these" else "No bindings · new pads start from these",
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pads.isEmpty()) {
                    DeckRowDivider()
                    CtlNote("No controller connected. Connect one to see and bind it here.")
                }
                for (c in pads) {
                    DeckRowDivider()
                    val pad = ctlPadOf(c)
                    CtlRow(onClick = { onSelId(c.getId()) }, selected = activeId == c.getId()) { cs ->
                        CtlDot(if (pad.connected) CTL_GREEN else CTL_RED)
                        Column(Modifier.weight(1f)) {
                            Text(c.getName() ?: c.getId(), color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ctlPadLine(pad, c.getControllerBindingCount()), color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        right = {
            DeckCard {
                val cs = MaterialTheme.colorScheme
                if (s.current == null) {
                    CtlNote("No profile selected. Create one on the On-screen tab to set bindings.")
                } else {
                    val pad = if (target != null && !isDefault) ctlPadOf(target) else null
                    var copyOpen by remember(activeId) { mutableStateOf(false) }
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (isDefault) "Default / Any controller" else (target?.getName() ?: "Controller"),
                                color = cs.onSurface,
                                fontFamily = SoraFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (pad != null) {
                                CtlChip(if (pad.connected) "Connected" else "Not connected", if (pad.connected) CTL_GREEN else CTL_RED)
                                if (pad.battery >= 0) CtlChip("${pad.battery}%", cs.primary)
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            DeckButton("Edit bindings", Icons.Filled.Edit, { s.openBindings(activeId) }, primary = true)
                            val dev = pad?.device
                            if (pad != null && dev != null && pad.vibrator) {
                                DeckButton("Rumble", Icons.Filled.Vibration, { pulsePad(dev) })
                            }
                            if (target != null && !isDefault) {
                                Box {
                                    DeckButton("Copy from", Icons.Filled.ContentCopy, { copyOpen = true })
                                    DropdownMenu(
                                        expanded = copyOpen,
                                        onDismissRequest = { copyOpen = false },
                                        modifier = Modifier.outlinedMenuCard().xmbMenuBack { copyOpen = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("From Default / Any Controller") },
                                            onClick = { copyOpen = false; s.copyBindings(target, defaultId) },
                                        )
                                        for (other in pads) {
                                            if (other.getId() == target.getId() || other.getControllerBindingCount() == 0) continue
                                            MenuItemDivider()
                                            DropdownMenuItem(
                                                text = { Text("From ${other.getName()}") },
                                                onClick = { copyOpen = false; s.copyBindings(target, other.getId()) },
                                            )
                                        }
                                    }
                                }
                                if (count > 0) DeckButton("Remove", Icons.Filled.Delete, { s.removeController(target) })
                            }
                        }
                        if (target == null || count == 0) {
                            Text(
                                if (isDefault) "No default bindings yet. Edit bindings sets what new controllers start from."
                                else "No bindings saved for this controller. Edit bindings maps its buttons, or copy them from another controller.",
                                color = cs.onSurfaceVariant,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                        } else {
                            MappingTable(target)
                        }
                    }
                }
            }
        },
        leftWeight = 1f,
        rightWeight = 1.25f,
    )
}

/** The bindings of [pad] as a grid of "input -> action" cells, grouped into buttons, sticks and D-pad, and triggers. */
@Composable
private fun MappingTable(pad: ExternalController) {
    val buttons = ArrayList<Pair<String, String>>()
    val sticks = ArrayList<Pair<String, String>>()
    val triggers = ArrayList<Pair<String, String>>()
    for (i in 0 until pad.getControllerBindingCount()) {
        val b = pad.getControllerBindingAt(i)
        val cell = Pair(b.toString(), b.getBinding().toString())
        val k = b.getKeyCode()
        when {
            k < 0 || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN ||
                k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT -> sticks.add(cell)
            k == KeyEvent.KEYCODE_BUTTON_L2 || k == KeyEvent.KEYCODE_BUTTON_R2 -> triggers.add(cell)
            else -> buttons.add(cell)
        }
    }
    val columns = if (deckCompact()) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (buttons.isNotEmpty()) MappingGroup("Buttons", buttons, columns)
        if (sticks.isNotEmpty()) MappingGroup("Sticks and D-pad", sticks, columns)
        if (triggers.isNotEmpty()) MappingGroup("Triggers", triggers, columns)
    }
}

@Composable
private fun MappingGroup(title: String, cells: List<Pair<String, String>>, columns: Int) {
    DeckSectionLabel(title)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cells.chunked(columns).forEach { line ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { cell -> MappingCell(cell.first, cell.second, Modifier.weight(1f)) }
                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MappingCell(from: String, to: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(deckShape(8.dp))
            .background(deckInset(0.5f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(from, color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f))
        Text("→", color = cs.onSurfaceVariant, fontSize = 12.sp)
        Text(to, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.55f))
    }
}
