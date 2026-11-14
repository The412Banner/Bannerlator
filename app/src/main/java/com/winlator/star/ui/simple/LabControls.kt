package com.winlator.star.ui.simple

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.star.R
import com.winlator.star.core.GyroCalibrator
import com.winlator.star.core.HttpUtils
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.inputcontrols.SteamControllerBackend
import com.winlator.star.ui.components.GlobalControllerPrefs
import com.winlator.star.ui.components.MenuButtonCaptureBus
import com.winlator.star.ui.components.buildPlayerSlotEditorRows
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.LayoutPreviewFrame
import com.winlator.star.ui.screens.STEAM_PADDLE_NAMES
import com.winlator.star.ui.screens.htmlToAnnotated
import com.winlator.star.ui.screens.steamPaddleOptions
import com.winlator.star.winhandler.WinHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Where Classic's Download Profiles list (index.txt) and its .icp files come from. */
internal const val COMMUNITY_PROFILES_URL = "https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/"

private fun profileTitle(file: String) = file.removeSuffix(".icpx").removeSuffix(".icp")

/** A profile's on-screen layout as a small picture (Classic's On-Screen preview), read off the main thread; a tap runs [onTap]. */
@Composable
internal fun LabProfileThumb(profileId: Int, refreshKey: Int, onTap: () -> Unit) {
    val context = LocalContext.current
    // A fresh instance per picture: the preview lays its elements out against its own small size.
    val profile by produceState<ControlsProfile?>(null, profileId, refreshKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { InputControlsManager.loadProfile(context, ControlsProfile.getProfileFile(context, profileId)) }.getOrNull()
        }
    }
    LayoutPreviewFrame(profile = profile, onTap = onTap)
}

/**
 * Classic's Download Profiles as a Lab list: A ticks a profile, X (or the eye) shows its layout first, Download fetches
 * and imports the ticked ones. [items] is null while the list loads.
 */
@Composable
internal fun LabCommunityProfilesDialog(items: List<String>?, manager: InputControlsManager, onDismiss: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    var picked by remember { mutableStateOf(setOf<String>()) }
    var preview by remember { mutableStateOf<String?>(null) }
    // Files fetched for a preview, so importing them needs no second download.
    val cache = remember { mutableMapOf<String, String>() }
    val first = remember { FocusRequester() }
    LaunchedEffect(items?.size) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    fun toggle(f: String) { picked = if (f in picked) picked - f else picked + f }
    fun download() {
        val files = items.orEmpty().filter { it in picked }
        if (files.isEmpty()) return
        onDismiss()
        toast(context, if (files.size == 1) L("Downloading %1\$s…", profileTitle(files[0])) else L("Downloading %1\$s profiles…", files.size))
        val done = AtomicInteger()
        val failed = AtomicInteger()
        files.forEach { f ->
            val importOne: (String?) -> Unit = { content ->
                val ok = content != null && runCatching { manager.importProfile(JSONObject(content)) != null }.getOrDefault(false)
                if (!ok) failed.incrementAndGet()
                if (done.incrementAndGet() == files.size) (context as? Activity)?.runOnUiThread {
                    onImported()
                    val bad = failed.get()
                    toast(
                        context,
                        when {
                            bad > 0 -> L("%1\$s of %2\$s profiles could not be imported", bad, files.size)
                            files.size == 1 -> L("Imported %1\$s", profileTitle(files[0]))
                            else -> L("Imported %1\$s profiles", files.size)
                        },
                    )
                }
            }
            val cached = cache[f]
            if (cached != null) Thread { importOne(cached) }.start()
            else HttpUtils.download(COMMUNITY_PROFILES_URL + f) { content -> importOne(content) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .heightIn(max = 640.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(14.dp),
        ) {
            Text(L("Community profiles"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            when {
                items == null -> Text(L("Loading the list…"), color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                items.isEmpty() -> Text(L("Nothing in the list."), color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                else -> Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    items.forEachIndexed { i, f ->
                        val on = f in picked
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .labTile(onClick = { toggle(f) }, onMenu = { preview = f }, requester = if (i == 0) first else null)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(if (on) accent else androidx.compose.ui.graphics.Color.Transparent, SimpleShape)
                                    .border(1.5.dp, if (on) accent else SimpleMuted, SimpleShape),
                            ) { if (on) Text("✓", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(12.dp))
                            Text(profileTitle(f), color = SimpleStrong, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).simpleTap { preview = f }) {
                                Icon(Icons.Filled.Visibility, contentDescription = L("View example"), tint = accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                if (!items.isNullOrEmpty()) {
                    LabButton(if (picked.isEmpty()) L("Download") else L("Download (%1\$s)", picked.size), primary = picked.isNotEmpty(), onClick = { download() })
                }
                LabButton(L("Close"), onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                if (!items.isNullOrEmpty()) SimpleLegend(listOf("A" to L("Select"), "X" to L("View example")))
            }
        }
    }

    preview?.let { f ->
        LabCommunityPreview(
            file = f, cache = cache, picked = f in picked,
            onPick = { picked = picked + f; preview = null },
            onClose = { preview = null },
        )
    }
}

/** "View example": a community profile's layout before it is imported (Classic's preview popover). */
@Composable
private fun LabCommunityPreview(file: String, cache: MutableMap<String, String>, picked: Boolean, onPick: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var profile by remember(file) { mutableStateOf<ControlsProfile?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(file) {
        val toProfile: (String?) -> ControlsProfile? = { c -> if (c == null) null else runCatching { ControlsProfile.createPreview(context, JSONObject(c)) }.getOrNull() }
        val cached = cache[file]
        if (cached != null) {
            profile = toProfile(cached)
            failed = profile == null
        } else HttpUtils.download(COMMUNITY_PROFILES_URL + file) { content ->
            (context as? Activity)?.runOnUiThread {
                if (content != null) cache[file] = content
                profile = toProfile(content)
                failed = profile == null
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focus.requestFocus() }
    }
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 620.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(14.dp),
        ) {
            Text(profileTitle(file), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            val p = profile
            when {
                p != null -> Box {
                    LayoutPreviewFrame(profile = p, onTap = null)
                    // Keeps touches off the overlay view underneath (it is only a picture here).
                    Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
                }
                failed -> Text(L("Could not download it."), color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                else -> Text(L("Loading…"), color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                LabButton(if (picked) L("Selected") else L("Select"), primary = !picked, requester = focus, onClick = onPick)
                LabButton(L("Close"), onClick = onClose)
            }
        }
    }
}

private fun marked(label: String, on: Boolean) = if (on) "$label  ✓" else label

/** Slot values of the saved pins (as PlayerSlotsEditor): Auto is "no entry", 0..3 a player, Ignore keeps a device out. */
private const val SLOT_AUTO = -1

/** Controls › Assign and Device in Lab: the rows of both tabs and the pop-ups they open (call [dialogs] on the page). */
internal class LabControlsExtra(val assign: List<MenuEntry>, val device: List<MenuEntry>, val dialogs: @Composable () -> Unit)

/**
 * Classic's Assign tab (GlobalPlayerSlotsSection: on-screen priority, hide the on-screen controls when a controller
 * connects, every device's player slot, the help) and Device tab (GyroscopeSection, SteamControllerSection,
 * MenuButtonSection) as Lab rows, on the same GlobalControllerPrefs, GyroCalibrator and SteamControllerBackend calls.
 * [refresh] changes when a controller comes or goes, so the slot list is read again.
 */
@Composable
internal fun rememberLabControlsExtra(refresh: Int): LabControlsExtra {
    val context = LocalContext.current
    val prefs = GlobalControllerPrefs
    var note by remember { mutableStateOf<LabHelpText?>(null) }
    fun help(title: String, res: Int) {
        note = LabHelpText(title, runCatching { htmlToAnnotated(context.getString(res)).text }.getOrDefault(""))
    }

    // ── Assign: the default player slots of new containers ──
    var json by remember { mutableStateOf(prefs.getSlotOverridesJson(context)) }
    var onScreenMode by remember { mutableIntStateOf(prefs.getOnScreenMode(context)) }
    var autoHide by remember { mutableStateOf(prefs.getAutoHideControlsOnPad(context)) }
    val rows = remember(json, refresh) { runCatching { buildPlayerSlotEditorRows(json) }.getOrDefault(emptyList()) }
    fun pin(descriptor: String, slot: Int) {
        val map = WinHandler.parseSlotOverridesJson(json).toMutableMap()
        if (slot == SLOT_AUTO) map.remove(descriptor) else map[descriptor] = slot
        val next = WinHandler.buildSlotOverridesJson(map)
        json = next
        prefs.setSlotOverridesJson(context, next)
    }
    val modeLabels = listOf(L("Keep on-screen player"), L("Yield Player 1 to pad"), L("Share the player"))
    val slotOptions = listOf(SLOT_AUTO to L("Auto")) + (0 until 4).map { it to L("Player %1\$s", it + 1) } + listOf(WinHandler.SLOT_IGNORE to L("Ignore"))
    val assign = buildList {
        val g = L("New containers start with")
        add(
            MenuEntry(
                "a-help", L("Player slots"),
                L("The default for newly-created containers. Assign two devices to one player to share control. Applies to new containers only — existing containers keep their own settings."),
                icon = Icons.Filled.HelpOutline, group = g, onA = { help(L("Player slots"), R.string.help_player_slots) },
            ),
        )
        add(
            MenuEntry(
                "a-mode", L("On-screen priority"), trailing = modeLabels.getOrElse(onScreenMode) { modeLabels[0] }, icon = Icons.Filled.TouchApp, group = g,
                actions = modeLabels.mapIndexed { i, l -> MenuAction(marked(l, i == onScreenMode), { onScreenMode = i; prefs.setOnScreenMode(context, i) }) },
            ),
        )
        add(
            MenuEntry(
                "a-hide", L("Hide on-screen controls when a controller connects"), icon = Icons.Filled.VisibilityOff, group = g, toggle = autoHide,
                onA = { autoHide = !autoHide; prefs.setAutoHideControlsOnPad(context, autoHide) },
            ),
        )
        val pg = L("Player slots")
        rows.forEach { r ->
            val status = when {
                r.override == WinHandler.SLOT_IGNORE -> L("Ignored")
                r.override >= 0 -> L("Pinned to Player %1\$s", r.override + 1)
                else -> L("Auto (assigned by launch order)")
            }
            val suffix = when {
                r.isOnScreen -> null
                !r.connected -> L("not connected (saved pin)")
                else -> null
            }
            add(
                MenuEntry(
                    "a-${r.descriptor}", if (r.isOnScreen) L("On-screen controls") else r.displayName,
                    listOfNotNull(status, suffix).joinToString(" · "),
                    trailing = slotOptions.firstOrNull { it.first == r.override }?.second ?: L("Auto"),
                    current = r.override >= 0,
                    icon = if (r.isOnScreen) Icons.Filled.TouchApp else Icons.Filled.SportsEsports, group = pg,
                    actions = slotOptions.map { (v, l) -> MenuAction(marked(l, v == r.override), { if (v != r.override) pin(r.descriptor, v) }) },
                ),
            )
        }
    }

    // ── Device: gyroscope calibration ──
    val sensor = remember { runCatching { GyroCalibrator.getSensor(context) }.getOrNull() }
    var calibrated by remember { mutableStateOf(GyroCalibrator.isCalibrated(context)) }
    var biasX by remember { mutableFloatStateOf(0f) }
    var biasY by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var activeRun by remember { mutableStateOf<GyroCalibrator.Run?>(null) }
    fun refreshBias() {
        val bias = FloatArray(2)
        GyroCalibrator.loadBias(context, bias)
        biasX = bias[0]
        biasY = bias[1]
        calibrated = GyroCalibrator.isCalibrated(context)
    }
    DisposableEffect(Unit) {
        refreshBias()
        // A calibration left running past this page would keep a listener on the sensor.
        onDispose {
            activeRun?.cancel()
            activeRun = null
        }
    }
    fun calibrate() {
        if (sensor == null || activeRun != null) return
        status = L("Hold still…")
        activeRun = GyroCalibrator.calibrate(context) { result ->
            activeRun = null
            status = when (result) {
                is GyroCalibrator.Result.Success -> if (result.negligible) L("Calibrated — nothing to remove") else L("Calibrated")
                GyroCalibrator.Result.Moved -> L("Hold the device still and try again")
                GyroCalibrator.Result.NotEnoughSamples -> L("Couldn't sample — try again")
                GyroCalibrator.Result.Unavailable -> L("Gyroscope unavailable")
            }
            refreshBias()
        }
    }

    // ── Device: Steam Controller ──
    var steamOn by remember { mutableStateOf(prefs.isSteamControllerEnabled(context)) }
    var trackpadMode by remember { mutableIntStateOf(prefs.getSteamTrackpadMouseMode(context)) }
    var bluetoothGranted by remember { mutableStateOf(SteamControllerBackend.hasBluetoothPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        bluetoothGranted = SteamControllerBackend.hasBluetoothPermission(context)
    }
    fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 && !bluetoothGranted) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
    val trackpadLabels = listOf(L("Right trackpad"), L("Left trackpad"), L("Both trackpads"), L("Off"))
    val trackpadModes = listOf(
        SteamControllerBackend.TRACKPAD_MOUSE_RIGHT, SteamControllerBackend.TRACKPAD_MOUSE_LEFT,
        SteamControllerBackend.TRACKPAD_MOUSE_BOTH, SteamControllerBackend.TRACKPAD_MOUSE_OFF,
    )
    val paddleOptions = remember { steamPaddleOptions() }
    var paddles by remember { mutableStateOf(prefs.getSteamPaddleBindings(context).toList()) }

    // ── Device: menu button ──
    var keyCode by remember { mutableIntStateOf(prefs.getMenuButtonKeyCode(context)) }
    var capturing by remember { mutableStateOf(false) }

    val device = buildList {
        val gg = L("Gyroscope")
        val summary = when {
            sensor == null -> L("No gyroscope on this device")
            !calibrated -> L("Not calibrated")
            abs(biasX) < GyroCalibrator.NEGLIGIBLE_BIAS && abs(biasY) < GyroCalibrator.NEGLIGIBLE_BIAS -> L("Very little drift detected — your device already compensates")
            else -> L("Bias removed: x %1\$s, y %2\$s rad/s", "%.4f".format(biasX), "%.4f".format(biasY))
        }
        add(
            MenuEntry(
                "g-cal", L("Calibrate the gyroscope"),
                subtitle = if (sensor == null) summary else listOf(summary, status.ifBlank { L("Rest the device on a flat surface, then calibrate.") }).joinToString(" · "),
                trailing = if (activeRun != null) L("Hold still…") else null,
                icon = Icons.Filled.ScreenRotation, group = gg,
                onA = if (sensor != null && activeRun == null) ({ calibrate() }) else null,
            ),
        )
        if (sensor != null && calibrated && activeRun == null) add(
            MenuEntry(
                "g-reset", L("Reset the calibration"), icon = Icons.Filled.RestartAlt, group = gg,
                onA = { GyroCalibrator.clearBias(context); status = L("Calibration reset"); refreshBias() },
            ),
        )

        val sg = L("Steam Controller")
        add(
            MenuEntry(
                "s-on", L("Steam Controller support"),
                L("Reads it directly, as Steam does. Pair it in Bluetooth settings or plug it in. Other controllers are not affected. Applies from the next game start."),
                icon = Icons.Filled.SportsEsports, group = sg, toggle = steamOn,
                onA = {
                    steamOn = !steamOn
                    prefs.setSteamControllerEnabled(context, steamOn)
                    if (steamOn) requestBluetooth()
                },
            ),
        )
        if (steamOn) {
            val ti = trackpadModes.indexOf(trackpadMode).coerceAtLeast(0)
            add(
                MenuEntry(
                    "s-pad", L("Trackpad mouse"), L("Which trackpad moves the mouse. Clicking it is a left click; with both, the left trackpad clicks right."),
                    trailing = trackpadLabels[ti], icon = Icons.Filled.Mouse, group = sg,
                    actions = trackpadLabels.mapIndexed { i, l ->
                        MenuAction(marked(l, i == ti), { trackpadMode = trackpadModes[i]; prefs.setSteamTrackpadMouseMode(context, trackpadModes[i]) })
                    },
                ),
            )
            STEAM_PADDLE_NAMES.forEachIndexed { i, name ->
                val now = paddles.getOrNull(i)
                add(
                    MenuEntry(
                        "s-p$i", name,
                        // Classic's note on the extra buttons, under the first of them.
                        subtitle = if (i == 0) L("What the four back buttons and the … button do, in every game. The other buttons use the Default / Any controller bindings (Controller tab).") else null,
                        trailing = paddleOptions.firstOrNull { it.second == now }?.first ?: paddleOptions.firstOrNull()?.first,
                        icon = Icons.Filled.Gamepad, group = sg,
                        actions = paddleOptions.map { (l, b) ->
                            MenuAction(marked(l, b == now), {
                                paddles = paddles.toMutableList().also { if (i < it.size) it[i] = b }
                                prefs.setSteamPaddleBinding(context, i, b)
                            })
                        },
                    ),
                )
            }
            if (!bluetoothGranted) add(
                MenuEntry(
                    "s-bt", L("Bluetooth permission is off, so only a USB-connected controller will work."),
                    trailing = if (Build.VERSION.SDK_INT >= 31) L("Allow Bluetooth") else null,
                    icon = Icons.Filled.Bluetooth, group = sg,
                    onA = if (Build.VERSION.SDK_INT >= 31) ({ requestBluetooth() }) else null,
                ),
            )
        }

        val mg = L("Menu button")
        add(
            MenuEntry(
                "m-btn", L("Menu button"),
                L("For handhelds with built-in controls: a button that opens the in-game side menu. Off until you set one. The chosen button then only opens the menu and isn't sent to the game."),
                trailing = prefs.menuButtonName(keyCode), icon = Icons.Filled.Menu, group = mg,
                onA = { capturing = true },
                actions = buildList {
                    add(MenuAction(L("Set button"), { capturing = true }))
                    if (keyCode != 0) add(MenuAction(L("None"), { prefs.setMenuButtonKeyCode(context, 0); keyCode = prefs.getMenuButtonKeyCode(context) }))
                    add(MenuAction(L("What is this?"), { help(L("Menu button"), R.string.help_menu_button) }))
                },
            ),
        )
    }

    val dialogs: @Composable () -> Unit = {
        note?.let { n -> LabHelpSheet(n) { note = null } }
        if (capturing) {
            LabMenuButtonCapture { picked ->
                capturing = false
                if (picked > 0) {
                    prefs.setMenuButtonKeyCode(context, picked)
                    keyCode = prefs.getMenuButtonKeyCode(context)
                }
            }
        }
    }
    return LabControlsExtra(assign, device, dialogs)
}

/**
 * Classic's "Press a button" prompt for the menu button, in Lab's look. Its window takes no focus, so the next key press
 * reaches MainActivity, which hands it to [onResult] (-1 = cancelled; the screen's own Back cancels).
 */
@Composable
private fun LabMenuButtonCapture(onResult: (Int) -> Unit) {
    DisposableEffect(Unit) {
        MenuButtonCaptureBus.listener = { onResult(it) }
        onDispose { MenuButtonCaptureBus.listener = null }
    }
    Dialog(onDismissRequest = { onResult(-1) }, properties = DialogProperties(dismissOnClickOutside = true)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) }
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 480.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(L("Press the button you want to open the menu"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(L("Use your device's own controls. B and the volume keys can't be used."), color = SimpleMuted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LabButton(L("Cancel"), onClick = { onResult(-1) })
            }
        }
    }
}
