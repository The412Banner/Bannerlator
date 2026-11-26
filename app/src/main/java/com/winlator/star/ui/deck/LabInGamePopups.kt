package com.winlator.star.ui.deck

import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.star.R
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.ExternalController
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.ui.XServerDialogState
import com.winlator.star.ui.controllertest.ALL_ELEMENTS
import com.winlator.star.ui.controllertest.BIND_BLUE
import com.winlator.star.ui.controllertest.BIND_BY_ID
import com.winlator.star.ui.controllertest.BIND_TARGETS
import com.winlator.star.ui.controllertest.BindCategory
import com.winlator.star.ui.controllertest.ControllerTestSnapshot
import com.winlator.star.ui.controllertest.PadArt
import com.winlator.star.ui.controllertest.PadArtView
import com.winlator.star.ui.controllertest.STEAM_ELEMENTS
import com.winlator.star.ui.controllertest.bindingLabelOrNull
import com.winlator.star.ui.controllertest.boundCount
import com.winlator.star.ui.controllertest.categoryLabels
import com.winlator.star.ui.controllertest.categoryValues
import com.winlator.star.ui.controllertest.clearTarget
import com.winlator.star.ui.controllertest.fillNative
import com.winlator.star.ui.controllertest.friendlyName
import com.winlator.star.ui.controllertest.padArtLabel
import com.winlator.star.ui.controllertest.padArtOf
import com.winlator.star.ui.controllertest.pressedSet
import com.winlator.star.ui.controllertest.resolveBindController
import com.winlator.star.ui.controllertest.setTarget
import com.winlator.star.ui.dialogs.RunPlace
import com.winlator.star.ui.dialogs.isProgram
import com.winlator.star.ui.dialogs.listFolder
import com.winlator.star.ui.dialogs.runPlaces
import com.winlator.star.ui.dialogs.samePath
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.MenuAction
import com.winlator.star.ui.simple.SimpleActionSheet
import com.winlator.star.ui.simple.SimpleCard
import com.winlator.star.ui.simple.SimpleLine
import com.winlator.star.ui.simple.SimpleMuted
import com.winlator.star.ui.simple.SimplePanelBrush
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.SimpleStrong
import com.winlator.star.ui.simple.labTile
import com.winlator.star.ui.simple.simpleDepth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// Lab-drawn variants of the in-game pop-ups the dialog host shows (XServerDialogHost.kt): the same state flows, callbacks
// and dismiss paths as the Classic dialogs in ui/dialogs, drawn with the Lab in-game kit.

private val LabPopLive = Color(0xFF57C777)
private val LabPopWarn = Color(0xFFE7B64C)

// Copies of ControllerTestVisualizer.kt's private fmt2 / pct (two lines each).
private fun labPopFmt2(v: Float): String {
    val r = (v * 100).roundToInt() / 100f
    return String.format(java.util.Locale.US, "%.2f", r)
}

private fun labPopPct(v: Float): String = "${(v.coerceIn(0f, 1f) * 100).roundToInt()}%"

// ───── Controller test (ControllerTestDialog.kt) ─────

/**
 * Lab look of the in-game controller test. Its own window, like Classic: not focusable while testing (the pad's keys and
 * sticks fall through to the activity, which feeds the picture and keeps them from the game), focusable in Bind (the
 * profile-name field takes the keyboard, B closes). A full-window shield keeps stray taps off the game.
 */
@Composable
internal fun LabControllerTestDialog(state: XServerDialogState) {
    DisposableEffect(Unit) {
        state.onControllerTestActive?.invoke(true)
        onDispose {
            state.clearControllerTestSnapshot()
            state.onControllerTestActive?.invoke(false)
        }
    }
    LaunchedEffect(Unit) { state.onPlayerSlotsRefresh?.run() }

    var bindMode by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { state.dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                if (bindMode) clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                else addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
            }
        }
        com.winlator.star.sound.DialogSound()
        DeckTheme {
            LabPopCtlScaffold(state, bindMode) { bindMode = it }
        }
    }
}

@Composable
private fun LabPopCtlScaffold(state: XServerDialogState, bindMode: Boolean, onBindModeChange: (Boolean) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val rows by state.playerSlots.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    // Keep a valid selection as the list changes (the first game controller, else the first row), as Classic's rail does.
    LaunchedEffect(rows) {
        if (rows.none { it.descriptor == selected }) {
            selected = rows.firstOrNull { it.isGameController }?.descriptor ?: rows.firstOrNull()?.descriptor
        }
    }
    val toggleFocus = remember { FocusRequester() }
    // Bind makes the window focusable: start the controller on the Test / Bind switch.
    LaunchedEffect(bindMode) {
        if (bindMode) {
            delay(120)
            runCatching { toggleFocus.requestFocus() }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        val compact = LocalIgCompact.current || maxWidth < 600.dp
        val panelW = (maxWidth * 0.94f).coerceAtMost(880.dp)
        val panelH = if (compact) maxHeight * 0.92f else (maxHeight * 0.92f).coerceAtMost(560.dp)
        val railW = (panelW * 0.38f).coerceIn(220.dp, 300.dp)
        Column(
            modifier = Modifier
                .width(panelW)
                .height(panelH)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    L("Controller test"),
                    color = SimpleStrong,
                    fontFamily = SoraFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.focusRequester(toggleFocus)) {
                    IgSegmented(
                        options = listOf(false to L("Test"), true to L("Bind")),
                        selected = bindMode,
                    ) { onBindModeChange(it) }
                }
                Spacer(Modifier.width(8.dp))
                IgButton(null, Icons.Outlined.Close, { state.dismiss() })
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(30.dp).height(3.dp).background(accent))
            Spacer(Modifier.height(10.dp))

            if (compact) {
                // Portrait: one scroll, the test / bind panel first, the player slots under it.
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    if (bindMode) LabPopCtlBindPanel(state, Modifier.fillMaxWidth())
                    else LabPopCtlTestPanel(state, selected, compact = true, pinFooter = false, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    LabPopCtlRail(state, rows, selected, { selected = it }, scroll = false, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    LabPopCtlRail(state, rows, selected, { selected = it }, scroll = true, modifier = Modifier.width(railW).fillMaxHeight())
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(SimpleLine))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (bindMode) {
                            LabPopCtlBindPanel(state, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                        } else {
                            LabPopCtlTestPanel(state, selected, compact = false, pinFooter = true, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** The player / device cards (Classic PlayerRail): slot pick, Identify, per-card Reset and the global Reset Input. */
@Composable
private fun LabPopCtlRail(
    state: XServerDialogState,
    rows: List<XServerDialogState.PlayerSlotRow>,
    selected: String?,
    onSelect: (String?) -> Unit,
    scroll: Boolean,
    modifier: Modifier,
) {
    Column(modifier) {
        IgSectionLabel(L("Player slots"))
        Text(
            L("Pads emulate as Xbox 360 to the game; the picture shows the physical pad you're holding."),
            color = SimpleMuted,
            fontFamily = NunitoSansFamily,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(if (scroll) Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth()) {
            if (rows.isEmpty()) {
                IgInfo(L("No input devices detected."))
            } else {
                rows.forEach { row ->
                    LabPopCtlPlayerCard(row, row.descriptor == selected, state) { onSelect(row.descriptor) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // Rebuild the whole fake-input transport in place (the existing recovery), then re-read the list.
        IgButton(
            L("Reset Input"),
            Icons.Outlined.RestartAlt,
            {
                state.onResetInput?.run()
                state.onPlayerSlotsRefresh?.run()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            L("Re-handshake controllers & on-screen if input stops responding."),
            color = SimpleMuted,
            fontFamily = NunitoSansFamily,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}

@Composable
private fun LabPopCtlPlayerCard(
    row: XServerDialogState.PlayerSlotRow,
    selected: Boolean,
    state: XServerDialogState,
    onSelect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val subtitle = when {
        row.currentSlot >= 0 -> L("Currently Player %1\$s", row.currentSlot + 1)
        row.override == XServerDialogState.SLOT_IGNORE -> L("Ignored")
        else -> L("Unassigned")
    } + if (row.isOnScreen) L(" · on-screen") else ""

    Column(
        Modifier
            .fillMaxWidth()
            .clip(SimpleShape)
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else SimpleCard)
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else SimpleLine, SimpleShape),
    ) {
        Column(Modifier.fillMaxWidth().labTile(onClick = onSelect).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                row.displayName,
                color = SimpleStrong,
                fontFamily = NunitoSansFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, color = SimpleMuted, fontFamily = NunitoSansFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
            LabPopCtlSlotPicker(row, state)
            // Identify + this card's Reset on the selected card only, as Classic.
            if (selected) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    IgButton(
                        L("Identify"),
                        null,
                        { state.onControllerIdentify?.invoke(row.currentSlot) },
                        modifier = Modifier.weight(1f),
                        enabled = row.currentSlot >= 0 && row.hasVibrator,
                    )
                    IgButton(
                        L("Reset"),
                        null,
                        {
                            // This device back to Auto, then rebuild the pipeline.
                            state.onPlayerSlotChanged?.invoke(row.descriptor, XServerDialogState.SLOT_AUTO)
                            state.onResetInput?.run()
                            state.onPlayerSlotsRefresh?.run()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LabPopCtlSlotPicker(row: XServerDialogState.PlayerSlotRow, state: XServerDialogState) {
    val options = remember {
        buildList {
            add(L("Auto") to XServerDialogState.SLOT_AUTO)
            for (i in 0 until 4) add(L("Player %1\$s", i + 1) to i)
            add(L("Ignore") to XServerDialogState.SLOT_IGNORE)
        }
    }
    // An override the list doesn't know shows as Auto, as Classic.
    val current = options.indexOfFirst { it.second == row.override }.let { if (it < 0) 0 else it }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(L("Slot"), color = SimpleMuted, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
        Spacer(Modifier.weight(1f))
        IgPickButton(L("Slot"), options.map { it.first }, current) { i ->
            val v = options[i].second
            if (v != row.override) state.onPlayerSlotChanged?.invoke(row.descriptor, v)
        }
    }
}

/** Classic TestPanel + ControllerTestPanel: the live picture, readouts, verified tally, Identify and Done. */
@Composable
private fun LabPopCtlTestPanel(
    state: XServerDialogState,
    selected: String?,
    compact: Boolean,
    pinFooter: Boolean,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val rows by state.playerSlots.collectAsState()
    val snap by state.controllerTestSnapshot.collectAsState()
    val s: ControllerTestSnapshot? = snap
    val selectedRow = rows.firstOrNull { it.descriptor == selected }
    var manualArt by remember(selected) { mutableStateOf<PadArt?>(null) }
    var artMenu by remember { mutableStateOf(false) }

    // The picture follows the detected pad; the picker overrides it. The tally follows the detected pad (18 for a Steam Controller).
    val autoArt = padArtOf(s, selectedRow?.displayName)
    val art = manualArt ?: autoArt
    val pressed = pressedSet(s)
    val elements = if (autoArt == PadArt.STEAM) STEAM_ELEMENTS else ALL_ELEMENTS
    val seen = remember(selected) { mutableStateListOf<String>() }
    var lastInput by remember(selected) { mutableStateOf("—") }
    LaunchedEffect(pressed) {
        pressed.forEach { if (it !in seen && it in elements) seen.add(it) }
        pressed.firstOrNull { it in elements }?.let { lastInput = friendlyName(it) }
    }
    val identifyEnabled = (selectedRow?.currentSlot ?: -1) >= 0 && (selectedRow?.hasVibrator == true)

    Column(modifier) {
        Column(if (pinFooter) Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    selectedRow?.displayName ?: L("No controller selected"),
                    color = SimpleStrong,
                    fontFamily = NunitoSansFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IgButton((if (manualArt == null) L("Auto · ") else "") + padArtLabel(art) + "  ▾", null, { artMenu = true })
            }
            Spacer(Modifier.height(8.dp))

            val live = s != null && s.deviceId >= 0
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(8.dp).background(if (live) LabPopLive else cs.onSurfaceVariant, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (live) L("Live input") else L("Press a button on the pad…"),
                    color = if (live) LabPopLive else cs.onSurfaceVariant,
                    fontFamily = NunitoSansFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f),
                )
                val batt = s?.batteryPct ?: -1
                if (batt in 0..100) Text(L("Battery %1\$s%%", batt), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))

            val ls = s?.let { labPopFmt2(it.thumbLX) + ", " + labPopFmt2(it.thumbLY) } ?: "0.00, 0.00"
            val rs = s?.let { labPopFmt2(it.thumbRX) + ", " + labPopFmt2(it.thumbRY) } ?: "0.00, 0.00"
            val tr = s?.let { labPopPct(it.triggerL) + " · " + labPopPct(it.triggerR) } ?: "0% · 0%"
            val readouts = listOf(
                L("Last input") to lastInput,
                L("Left stick") to ls,
                L("Right stick") to rs,
                L("Triggers L·R") to tr,
            )
            val tiles: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (pair in readouts.chunked(2)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            for ((k, v) in pair) LabPopStatTile(k, v, Modifier.weight(1f))
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (compact) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PadArtView(art = art, snapshot = s) }
                Spacer(Modifier.height(10.dp))
                tiles(Modifier.fillMaxWidth())
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.weight(0.52f), contentAlignment = Alignment.Center) { PadArtView(art = art, snapshot = s) }
                    tiles(Modifier.weight(0.48f))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Verified tally + Identify + Done, below the scroll so they stay in view.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val done = seen.count { it in elements }
            Text(
                if (done >= elements.size) L("All inputs registering (%1\$s tested)", elements.size)
                else L("%1\$s / %2\$s inputs verified", done, elements.size),
                color = if (done >= elements.size) LabPopLive else cs.onSurfaceVariant,
                fontFamily = NunitoSansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.5.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IgButton(
                L("Identify"),
                Icons.Outlined.Vibration,
                { state.onControllerIdentify?.invoke(selectedRow?.currentSlot ?: -1) },
                primary = true,
                enabled = identifyEnabled,
            )
            Spacer(Modifier.width(8.dp))
            IgButton(L("Done"), null, { state.dismiss() })
        }
    }

    if (artMenu) {
        val actions = listOf(MenuAction(if (manualArt == null) L("Auto-detect") + "  ✓" else L("Auto-detect"), { manualArt = null })) +
            PadArt.values().map { a -> MenuAction(if (manualArt == a) padArtLabel(a) + "  ✓" else padArtLabel(a), { manualArt = a }) }
        SimpleActionSheet(title = L("Controller picture"), actions = actions, onDismiss = { artMenu = false }) { a ->
            artMenu = false
            a.run()
        }
    }
}

@Composable
private fun LabPopStatTile(key: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .background(SimpleCard, SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape)
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Text(key.uppercase(), color = SimpleMuted, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, color = SimpleStrong, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Classic InGameBindPanel: the physical-lane profile (pick = activate on the pad, None = native Xbox), create, rename, delete. */
@Composable
private fun LabPopCtlBindPanel(state: XServerDialogState, modifier: Modifier) {
    val context = LocalContext.current
    val snap by state.controllerTestSnapshot.collectAsState()
    val manager = remember { InputControlsManager(context) }
    var profilesRev by remember { mutableIntStateOf(0) }
    val profiles = remember(profilesRev) { manager.getProfiles().toList() }
    // Seeded from the profile ACTIVE on the physical pad (not the on-screen one); -1 = None (native passthrough).
    var selectedProfileId by remember { mutableStateOf(state.physicalProfileId) }
    val selectedProfile = remember(profilesRev, selectedProfileId) {
        if (selectedProfileId < 0) null
        else profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
    }
    val padName = remember { ExternalController.getControllers().firstOrNull()?.name }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }

    LabPopBinder(
        profile = selectedProfile,
        art = padArtOf(snap, padName),
        snapshot = snap,
        allProfiles = profiles,
        onSelectProfile = { p ->
            selectedProfileId = p.id
            state.onPhysicalProfileChanged?.invoke(p.id)
        },
        onSelectNone = {
            selectedProfileId = -1
            state.onPhysicalProfileChanged?.invoke(-1)
        },
        onCreateProfile = { name ->
            val p = manager.createProfile(name)
            selectedProfileId = p.id
            profilesRev++
            state.onPhysicalProfileChanged?.invoke(p.id)
            state.onProfilesChanged?.run()
        },
        onRenameProfile = { renameText = selectedProfile?.name ?: ""; renaming = true },
        onDeleteProfile = { deleting = true },
        // The binder saves on its own manager; re-apply so the running pad reads the saved bindings.
        onSaved = { if (selectedProfileId >= 0) state.onPhysicalProfileChanged?.invoke(selectedProfileId) },
        modifier = modifier,
    )

    if (renaming) {
        val submit = {
            val p = selectedProfile
            if (p != null && renameText.isNotBlank()) {
                p.name = renameText.trim(); p.save(); profilesRev++
                state.onProfilesChanged?.run()
            }
            renaming = false
        }
        LabInGameDialog(
            title = L("Rename profile"),
            onDismiss = { renaming = false },
            footer = {
                IgButton(L("Cancel"), null, { renaming = false })
                IgButton(L("OK"), null, submit, primary = true)
            },
        ) {
            IgTextField(renameText, { renameText = it }, modifier = Modifier.fillMaxWidth())
        }
    }

    if (deleting) {
        val p = selectedProfile
        IgConfirmDialog(
            title = L("Delete profile"),
            body = L("Delete \"%1\$s\"? This can't be undone.", p?.name ?: ""),
            confirmLabel = L("Delete"),
            confirmIcon = Icons.Outlined.Delete,
            onConfirm = {
                if (p != null) {
                    val wasActive = selectedProfileId == p.id
                    manager.removeProfile(p)
                    selectedProfileId = -1
                    profilesRev++
                    if (wasActive) state.onPhysicalProfileChanged?.invoke(-1)
                    state.onProfilesChanged?.run()
                }
                deleting = false
            },
            onDismiss = { deleting = false },
        )
    }
}

/** Classic VisualControllerBinder in Lab rows: profile pick / new, the pass-through warning, tap-to-bind picture, list, editor. */
@Composable
private fun LabPopBinder(
    profile: ControlsProfile?,
    art: PadArt,
    snapshot: ControllerTestSnapshot?,
    allProfiles: List<ControlsProfile>,
    onSelectProfile: (ControlsProfile) -> Unit,
    onSelectNone: () -> Unit,
    onCreateProfile: (String) -> Unit,
    onRenameProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Bumped after each write so the controller-backed reads below recompose.
    var bindRev by remember { mutableIntStateOf(0) }
    val controller: ExternalController? = remember(profile, bindRev) { profile?.let { resolveBindController(it) } }
    var selectedId by remember(profile) { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf(BindCategory.KEYBOARD) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var profMenu by remember { mutableStateOf(false) }

    fun bumpSaved() { bindRev++; onSaved() }

    val boundN = remember(bindRev, controller) { boundCount(controller) }
    val labels = remember(bindRev, controller) { BIND_TARGETS.associate { it.id to bindingLabelOrNull(controller, it.id) } }
    val boundBindIds = remember(bindRev, controller) { labels.filterValues { it != null }.keys.toSet() }

    Column(modifier) {
        IgCard {
            val shown = (profile?.name ?: L("— none —")).let { if (it.length > 24) it.take(23) + "…" else it }
            IgRow(L("Profile")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IgButton("$shown  ▾", null, { profMenu = true })
                    IgButton(L("+ New"), null, { showNew = !showNew })
                }
            }
            if (showNew) {
                IgDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    IgTextField(newName, { newName = it }, modifier = Modifier.weight(1f), placeholder = L("New profile name"))
                    Spacer(Modifier.width(8.dp))
                    IgButton(L("Create"), null, {
                        if (newName.isNotBlank()) {
                            onCreateProfile(newName.trim()); newName = ""; showNew = false
                        }
                    }, primary = true)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (profile == null || controller == null) {
            IgInfo(L("Select or create a profile to bind this controller."))
        } else {
            if (boundN > 0) {
                IgCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                L("Remapping is ON — %1\$s of %2\$s mapped", boundN, BIND_TARGETS.size),
                                color = LabPopWarn,
                                fontFamily = NunitoSansFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                            Text(
                                L("Unbound buttons go SILENT in-game (they no longer act as native Xbox)."),
                                color = cs.onSurfaceVariant,
                                fontFamily = NunitoSansFamily,
                                fontSize = 12.sp,
                                lineHeight = 15.sp,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IgButton(L("Fill → native"), null, { fillNative(controller, profile); bumpSaved() })
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            val padBox: @Composable () -> Unit = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    PadArtView(
                        art = art,
                        snapshot = snapshot,
                        boundBindIds = boundBindIds,
                        selectedBindId = selectedId,
                        onTapBind = { id -> selectedId = id },
                    )
                }
            }
            val panel: @Composable () -> Unit = {
                val sel = selectedId
                if (sel == null) {
                    LabPopBindSummary(labels, boundN) { id -> selectedId = id }
                } else {
                    LabPopBindEditor(
                        id = sel,
                        currentLabel = labels[sel],
                        category = category,
                        onCategory = { category = it },
                        onPick = { bnd -> setTarget(controller, profile, sel, bnd); bumpSaved() },
                        onNative = {
                            val nat = BIND_BY_ID[sel]?.native
                            if (nat != null) { setTarget(controller, profile, sel, nat); bumpSaved() }
                        },
                        onClear = { clearTarget(controller, profile, sel); bumpSaved() },
                        onDone = { selectedId = null },
                    )
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 520.dp) {
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { padBox() }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) { panel() }
                    }
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        padBox()
                        Spacer(Modifier.height(12.dp))
                        panel()
                    }
                }
            }
        }
    }

    if (profMenu) {
        val actions = buildList {
            add(MenuAction(if (profile == null) L("— None (native Xbox) —") + "  ✓" else L("— None (native Xbox) —"), onSelectNone))
            allProfiles.forEach { p ->
                add(MenuAction(if (profile != null && p.id == profile.id) p.name + "  ✓" else p.name, { onSelectProfile(p) }))
            }
            if (profile != null) {
                add(MenuAction(L("Rename…"), onRenameProfile))
                add(MenuAction(L("Delete…"), onDeleteProfile, danger = true))
            }
        }
        SimpleActionSheet(title = L("Profile"), actions = actions, onDismiss = { profMenu = false }) { a ->
            profMenu = false
            a.run()
        }
    }
}

@Composable
private fun LabPopBindSummary(labels: Map<String, String?>, boundN: Int, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    IgCard {
        Text(
            L("Bindings · %1\$s of %2\$s mapped", boundN, BIND_TARGETS.size),
            color = cs.onSurface,
            fontFamily = NunitoSansFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.5.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        BIND_TARGETS.forEach { t ->
            val label = labels[t.id]
            IgDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .labTile(onClick = { onSelect(t.id) })
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(t.name, color = cs.onSurface, fontFamily = NunitoSansFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("→", color = cs.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    label ?: L("native"),
                    color = if (label != null) BIND_BLUE else cs.onSurfaceVariant,
                    fontFamily = NunitoSansFamily,
                    fontWeight = if (label != null) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LabPopBindEditor(
    id: String,
    currentLabel: String?,
    category: BindCategory,
    onCategory: (BindCategory) -> Unit,
    onPick: (com.winlator.star.inputcontrols.Binding) -> Unit,
    onNative: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val t = BIND_BY_ID[id]
    IgCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(L("Bind: "), color = cs.onSurface, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(t?.name ?: id, color = BIND_BLUE, fontFamily = NunitoSansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                IgButton(L("Done"), null, onDone)
            }
            Text(
                L("Currently: ") + (currentLabel ?: L("native (pass-through)")),
                color = if (currentLabel != null) BIND_BLUE else cs.onSurfaceVariant,
                fontFamily = NunitoSansFamily,
                fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            IgSegmented(
                options = BindCategory.values().map { c ->
                    c to when (c) {
                        BindCategory.KEYBOARD -> L("Keyboard")
                        BindCategory.MOUSE -> L("Mouse")
                        BindCategory.XBOX -> L("Xbox")
                        BindCategory.NONE -> L("None")
                    }
                },
                selected = category,
            ) { onCategory(it) }
            Spacer(Modifier.height(8.dp))

            val labels = categoryLabels(category)
            val values = categoryValues(category)
            val n = minOf(labels.size, values.size)
            val perRow = 4
            for (start in 0 until n step perRow) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    for (col in 0 until perRow) {
                        val idx = start + col
                        if (idx < n) LabPopBindCell(labels[idx], Modifier.weight(1f)) { onPick(values[idx]) }
                        else Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val nativeName = t?.name
                if (t?.native != null && nativeName != null) IgButton(L("Native (%1\$s)", nativeName), null, onNative)
                IgButton(L("Clear"), null, onClear, danger = true)
            }
        }
    }
}

/** One choice in the binding grid: a Lab cell the D-pad reaches, A or a tap binds it. */
@Composable
private fun LabPopBindCell(label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = deckShape(4.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .deckFocusRing(shape)
            .clip(shape)
            .background(cs.surfaceContainerHigh.copy(alpha = igRaised(0.85f)))
            .border(1.dp, deckLineAt(0.4f), shape)
            .clickable(onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 4.dp),
    ) {
        Text(label, color = cs.onSurface, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ───── Cast (CastDialog.kt) ─────

@Composable
internal fun LabCastDialog(state: XServerDialogState) {
    val devices by state.castDevices.collectAsState()
    val scanning by state.castScanning.collectAsState()
    val status by state.castStatus.collectAsState()
    val targetName by state.castTargetName.collectAsState()
    val detail by state.castStatusDetail.collectAsState()

    var showHelp by remember { mutableStateOf(false) }
    // Which device is open. Picking one only shows its details and Connect; it never connects by itself.
    var selected by remember { mutableStateOf<String?>(null) }

    LabInGameDialog(
        title = if (showHelp) L("Casting — options & tips") else L("Cast to a TV (wireless)"),
        onDismiss = { state.dismiss() },
        maxWidth = 640.dp,
        actions = {
            if (!showHelp) {
                if (scanning) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                IgButton(null, Icons.AutoMirrored.Outlined.HelpOutline, { showHelp = true })
                IgButton(L("Refresh"), Icons.Outlined.Refresh, { state.onCastRefresh?.run() })
            }
        },
        footer = {
            if (showHelp) IgButton(L("Back"), Icons.AutoMirrored.Filled.ArrowBack, { showHelp = false })
            else IgButton(L("Close"), null, { state.dismiss() })
        },
    ) {
        if (showHelp) {
            LabPopCastHelp()
        } else {
            Text(
                L("Google TV / Chromecast devices on your Wi-Fi. Nothing to install on the TV. Tap the “?” for how casting works and the trade-offs."),
                color = SimpleMuted,
                fontFamily = NunitoSansFamily,
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            if (devices.isEmpty()) {
                IgCard {
                    IgInfo(
                        if (scanning) L("Searching…")
                        else L("No devices found. Make sure your TV is on the same Wi-Fi, then tap Refresh.")
                    )
                }
            } else {
                IgCard {
                    devices.forEachIndexed { i, d ->
                        if (i > 0) IgDivider()
                        LabPopCastDevice(
                            name = d.name,
                            type = d.type,
                            host = d.host,
                            // Active = the device being connected to or connected.
                            active = d.name == targetName && status != XServerDialogState.CastStatus.IDLE,
                            open = d.name == selected,
                            status = status,
                            detail = detail,
                            onSelect = { selected = d.name },
                            onConnect = { state.onCastConnect?.accept(d) },
                            onDisconnect = { state.onCastDisconnect?.run() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabPopCastDevice(
    name: String,
    type: String,
    host: String,
    active: Boolean,
    open: Boolean,
    status: XServerDialogState.CastStatus,
    detail: String,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val expanded = open || active
    Column(Modifier.fillMaxWidth().background(if (expanded) cs.primary.copy(alpha = 0.10f) else Color.Transparent)) {
        Column(Modifier.fillMaxWidth().labTile(onClick = onSelect).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(name, color = SimpleStrong, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(type, color = SimpleMuted, fontFamily = NunitoSansFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (expanded && host.isNotBlank()) {
                Text(host, color = SimpleMuted, fontFamily = NunitoSansFamily, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                if (active) {
                    val label = when (status) {
                        XServerDialogState.CastStatus.CONNECTING -> L("Connecting…")
                        XServerDialogState.CastStatus.CONNECTED -> L("Connected")
                        XServerDialogState.CastStatus.FAILED -> L("Failed to connect")
                        else -> ""
                    }
                    val color = when (status) {
                        XServerDialogState.CastStatus.CONNECTED -> cs.primary
                        XServerDialogState.CastStatus.FAILED -> cs.error
                        else -> cs.onSurfaceVariant
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (status == XServerDialogState.CastStatus.CONNECTING) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = cs.primary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(label, color = color, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    if (detail.isNotBlank()) Text(detail, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    IgButton(L("Disconnect"), null, onDisconnect, danger = true)
                } else {
                    IgButton(L("Connect"), Icons.Outlined.Cast, onConnect, primary = true)
                }
            }
        }
    }
}

/** The "?" page: the ways to get the game onto a TV and the trade-offs of each (Classic CastHelp). */
@Composable
private fun LabPopCastHelp() {
    LabPopCastHelpBlock(
        title = L("Cast — no app on the TV (this screen) · EXPERIMENTAL"),
        body = L("Pick a Google TV or Chromecast from the list — nothing to install on the TV."),
        pros = listOf(L("Nothing to set up on the TV"), L("Uses devices you already have")),
        cons = listOf(
            L("A few seconds of lag (video is buffered) — good for slower games, not fast ones"),
            L("Video only for now — the game's sound stays on this device"),
            L("Google TV / Chromecast only — Roku isn't listed here"),
        ),
    )
    LabPopCastHelpBlock(
        title = L("Cast — with a receiver app (coming later)"),
        body = L("Install our small app on the Google TV once, then cast from this same list."),
        pros = listOf(L("Crisp, low-lag — good for any game"), L("Phone stays free (blank phone, game on TV)")),
        cons = listOf(L("Needs a one-time install on the Google TV"), L("Won't work on Roku (closed device)")),
    )
    LabPopCastHelpBlock(
        title = L("Wired cable — lowest lag today"),
        body = L("A USB-C→HDMI cable moves the game to the TV with almost no lag."),
        pros = listOf(L("Best quality and responsiveness"), L("No Wi-Fi needed")),
        cons = listOf(L("Needs a cable and an HDMI port")),
    )
    LabPopCastHelpBlock(
        title = L("Roku TVs"),
        body = L("Roku can't run our app and can't take our stream. Use Android's built-in Screen Mirroring (in Settings) with the Roku's Screen-mirroring turned on."),
        pros = listOf(L("No app needed")),
        cons = listOf(L("Uses Android's system screen, not this in-app list"), L("Depends on your phone's Miracast")),
    )
}

@Composable
private fun LabPopCastHelpBlock(title: String, body: String, pros: List<String>, cons: List<String>) {
    val cs = MaterialTheme.colorScheme
    IgCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, color = cs.onSurface, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(body, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            pros.forEach { Text("✓  $it", color = cs.primary, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, lineHeight = 16.sp) }
            cons.forEach { Text("✗  $it", color = cs.error, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, lineHeight = 16.sp) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ───── Active windows (ActiveWindowsDialog.kt) ─────

@Composable
internal fun LabActiveWindowsDialog(state: XServerDialogState) {
    val windows by state.awWindows.collectAsState()
    LabInGameDialog(
        title = L("Active Windows"),
        onDismiss = { state.dismiss() },
        maxWidth = 720.dp,
        footer = { IgButton(L("Close"), null, { state.dismiss() }) },
    ) {
        if (windows.isEmpty()) {
            IgCard { IgInfo(L("No windows open")) }
        } else {
            val cols = 2
            windows.chunked(cols).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    rowItems.forEach { w ->
                        LabPopWindowTile(w, Modifier.weight(1f)) {
                            state.onWindowClick?.invoke(w.className, w.handle)
                            state.dismiss()
                        }
                    }
                    repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun LabPopWindowTile(window: XServerDialogState.ActiveWindow, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .background(SimpleCard, SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape)
            .labTile(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(SimpleShape)
                .background(cs.outline.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            val shot = window.screenshot
            if (shot != null) {
                Image(bitmap = shot.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(80.dp))
            } else {
                Text("…", color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = window.icon
            if (icon != null) {
                Image(bitmap = icon.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Column {
                Text(
                    window.title.ifBlank { window.className },
                    color = SimpleStrong,
                    fontFamily = NunitoSansFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (window.className.isNotBlank()) {
                    Text(window.className, color = SimpleMuted, fontFamily = NunitoSansFamily, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ───── Live log (DebugDialogContent.kt) ─────

@Composable
internal fun LabDebugDialog(state: XServerDialogState) {
    val logLines by state.logLines.collectAsState()
    val logPaused by state.logPaused.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty() && !logPaused) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    LabInGameDialog(
        title = L("Logs"),
        onDismiss = { state.dismiss() },
        maxWidth = 1000.dp,
        tall = true,
        scroll = false,
        footer = {
            IgButton(L("Copy"), null, {
                // The whole accumulated log (incl. BCn transfer stats).
                clipboard.setText(AnnotatedString(logLines.joinToString("\n")))
                Toast.makeText(context, L("Log copied"), Toast.LENGTH_SHORT).show()
            })
            IgButton(L("Clear"), null, { state.clearLog() })
            IgButton(if (logPaused) L("Resume") else L("Pause"), null, { state.setLogPaused(!logPaused) })
            Spacer(Modifier.weight(1f))
            IgButton(L("Close"), null, { state.dismiss() })
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(SimpleCard, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            items(logLines) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = SimpleStrong,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
            if (logLines.isEmpty()) {
                item {
                    Text(
                        text = L("No log output yet."),
                        fontFamily = NunitoSansFamily,
                        fontSize = 12.5.sp,
                        color = SimpleMuted,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

// ───── Run a program (RunProgramDialog.kt) ─────

/**
 * Lab look of the in-game "Run a program" browser: the device's storage first, then C: and the game's folder; the
 * picked file goes to [XServerDialogState.onRunBrowserPick]. B (or a tap outside) goes up a folder, then to the
 * places, then closes, as Classic.
 */
@Composable
internal fun LabRunProgramDialog(state: XServerDialogState) {
    val context = LocalContext.current
    val places = remember { runPlaces(context, state.runBrowserDriveC, state.runBrowserGameDir) }
    var place by remember { mutableStateOf<RunPlace?>(null) }
    var dir by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(dir) {
        val d = dir
        entries = if (d == null) emptyList() else withContext(Dispatchers.IO) { listFolder(d) }
    }

    val close = { state.dismiss() }
    val up: () -> Unit = {
        val d = dir
        val p = place
        when {
            d == null -> close()
            p == null || samePath(d, p.dir) || d.parentFile == null -> { dir = null; place = null }
            else -> dir = d.parentFile
        }
    }
    val pick: (File) -> Unit = { f ->
        val callback = state.onRunBrowserPick
        close()
        callback?.invoke(f.absolutePath)
    }

    val d0 = dir
    val p0 = place
    val subtitle = if (d0 != null && p0 != null) {
        val rel = d0.absolutePath.removePrefix(p0.dir.absolutePath).trimStart('/')
        if (rel.isEmpty()) p0.label else p0.label + " / " + rel.replace("/", " / ")
    } else null

    LabInGameDialog(
        title = L("Run a program"),
        onDismiss = up,
        onClose = { state.dismiss() },
        subtitle = subtitle,
        maxWidth = 640.dp,
        tall = true,
        scroll = false,
        footer = { IgButton(L("Close"), null, close) },
    ) {
        val firstRow = remember { FocusRequester() }
        // The list changes under the controller on every folder: put it back on the first row.
        LaunchedEffect(dir) {
            delay(120)
            runCatching { firstRow.requestFocus() }
        }
        IgCard(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                val d = dir
                if (d == null) {
                    itemsIndexed(places, key = { _, p -> p.dir.absolutePath + "|" + p.label }) { i, p ->
                        if (i > 0) IgDivider()
                        LabPopRunRow(p.icon, p.label, if (i == 0) firstRow else null) { place = p; dir = p.dir }
                    }
                } else {
                    item(key = "..") { LabPopRunRow(Icons.AutoMirrored.Filled.ArrowBack, L("Up"), firstRow, onClick = up) }
                    itemsIndexed(entries, key = { _, f -> f.absolutePath }) { _, f ->
                        IgDivider()
                        when {
                            f.isDirectory -> LabPopRunRow(Icons.Filled.Folder, f.name) { dir = f }
                            isProgram(f) -> LabPopRunRow(Icons.Filled.PlayArrow, f.name) { pick(f) }
                            else -> LabPopRunRow(Icons.Outlined.Description, f.name) { pick(f) }
                        }
                    }
                    if (entries.isEmpty()) item(key = "empty") {
                        IgDivider()
                        IgInfo(L("Nothing here"))
                    }
                }
            }
        }
    }
}

@Composable
private fun LabPopRunRow(icon: ImageVector, title: String, requester: FocusRequester? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .labTile(onClick = onClick, requester = requester)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            color = SimpleStrong,
            fontFamily = NunitoSansFamily,
            fontSize = 14.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ───── New task (NewTaskDialog.kt) ─────

@Composable
internal fun LabNewTaskDialog(state: XServerDialogState) {
    // Browse… leaves the picked program's path here once; otherwise the box opens on taskmgr.exe.
    var command by remember {
        val prefill = state.newTaskPrefill
        state.newTaskPrefill = null
        mutableStateOf(prefill ?: "taskmgr.exe")
    }
    val submit = {
        val cmd = command.trim()
        if (cmd.isNotEmpty()) state.onTmNewTaskSubmit?.invoke(cmd)
        state.dismiss()
    }
    LabInGameDialog(
        title = stringResource(R.string.new_task),
        onDismiss = { state.dismiss() },
        footer = {
            if (state.onNewTaskBrowse != null) {
                IgButton(L("Browse…"), null, {
                    state.dismiss()
                    state.onNewTaskBrowse?.run()
                })
            }
            IgButton(L("Cancel"), null, { state.dismiss() })
            IgButton(L("OK"), null, submit, primary = true)
        },
    ) {
        IgTextField(command, { command = it }, modifier = Modifier.fillMaxWidth())
    }
}

// ───── Processor affinity (XServerDrawer.kt ProcessorAffinityDialog) ─────

/**
 * Lab look of the Task Manager's "Set affinity": <All Processors> plus one switch per logical CPU, opened on the
 * process's current cores, applied live through [XServerDialogState.onTmSetAffinity] on OK. Not saved: it lasts until
 * the process restarts.
 */
@Composable
internal fun LabProcessorAffinityDialog(proc: XServerDialogState.TmProcess, onDismiss: () -> Unit) {
    val coreCount = remember { Runtime.getRuntime().availableProcessors().coerceIn(1, 32) }
    val allMask = remember(coreCount) { if (coreCount >= 32) -1 else (1 shl coreCount) - 1 }
    // The user's last choice for this pid first (the guest's readback is unreliable under wow64/FEX), then the guest's
    // mask, then all.
    var mask by remember(proc.pid) {
        val stored = XServerDialogState.onTmQueryAffinity?.invoke(proc.pid) ?: -1
        val m = (if (stored > 0) stored else proc.affinityMask) and allMask
        mutableStateOf(if (m == 0) allMask else m)
    }
    val allChecked = (mask and allMask) == allMask

    LabInGameDialog(
        title = L("Processor Affinity"),
        onDismiss = onDismiss,
        subtitle = L("Which processors are allowed to run \"%1\$s\"?", proc.name),
        footer = {
            IgButton(L("Cancel"), null, onDismiss)
            IgButton(
                L("OK"),
                null,
                {
                    XServerDialogState.onTmSetAffinity?.invoke(proc.pid, mask and allMask)
                    onDismiss()
                },
                primary = true,
                enabled = (mask and allMask) != 0,
            )
        },
    ) {
        IgCard {
            IgToggleRow(L("<All Processors>"), checked = allChecked) { mask = if (allChecked) 0 else allMask }
        }
        Spacer(Modifier.height(10.dp))
        IgCard {
            for (i in 0 until coreCount) {
                if (i > 0) IgDivider()
                IgToggleRow("CPU$i", checked = ((mask shr i) and 1) == 1) { mask = (mask xor (1 shl i)) and allMask }
            }
        }
    }
}
