package com.winlator.star.ui.simple

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.preference.PreferenceManager
import com.winlator.star.R
import com.winlator.star.core.ControllerLog
import com.winlator.star.core.ExitReasonReporter
import com.winlator.star.core.LogInventory
import com.winlator.star.core.LogLocation
import com.winlator.star.core.LogcatCapture
import com.winlator.star.store.StoreDownloadTier
import com.winlator.star.store.UbisoftDiag
import com.winlator.star.store.blsteam.BlSteamEngineFlag
import com.winlator.star.store.blsteam.BlStoreEngineFlag
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.screens.COMMON_WINE_CHANNELS
import com.winlator.star.ui.screens.LogCopy
import com.winlator.star.ui.screens.WineChannelInfo
import com.winlator.star.ui.screens.relativeTime
import com.winlator.star.ui.screens.shareLogGroup
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

// Lab's Log Manager: Classic's Log Manager (LogManagerScreen) as Lab lists, on the same preferences and LogInventory.
// Logs: every game's logs (View, Share, its files, Delete), the total, Browse and Clear all. What to record: the log
// switches (each with its "?" text on X), Wine debug channels, Capture logcat now, Read last crash. Where logs go: the
// folder, a folder per game, how many runs to keep. Download engines: the native Steam engine, the Rust store engines and
// the store download speed. Help: what each log is for and what is removed before sharing. Every change is written at
// once, as in Classic.

private const val PREF_CHANNELS = "wine_debug_channels"

private fun readChannels(context: Context): List<String> =
    (PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_CHANNELS, com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS)
        ?: com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS).split(",").filter { it.isNotBlank() }

private fun saveChannels(context: Context, list: List<String>) =
    PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_CHANNELS, list.joinToString(",")).apply()

/** Lab's Log Manager page (Tools › Game logs, Search › Logs, Settings' "Open the Log Manager"). */
@Composable
internal fun LabLogManagerPage(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val sections = labLogSections(onOpen = onOpen, flat = false)
    SimpleMenuPage(overline = L("Tools"), title = L("Log Manager"), sections = sections, onBack = onBack)
}

/**
 * The Log Manager inside Lab's Settings (its Logs tab): the same rows as one list with the sections as headings, for
 * the panel Settings gives it. Up / Down move, A runs a row, X its options, B goes back to Settings ([onClose]).
 */
@Composable
internal fun LabLogManagerPanel(onClose: () -> Unit) {
    SimpleFlat {
        val sections = labLogSections(onOpen = null, flat = true)
        val entries = sections.flatMap { it.entries }
        var row by remember { mutableIntStateOf(0) }
        var col by remember { mutableIntStateOf(0) }
        var actionsFor by remember { mutableStateOf<MenuEntry?>(null) }
        var confirmFor by remember { mutableStateOf<MenuAction?>(null) }
        val rowNow = row.coerceIn(0, max(0, entries.size - 1))
        val cur = entries.getOrNull(rowNow)
        LaunchedEffect(rowNow) { col = 0 }
        com.winlator.star.sound.MoveSound(listOf(rowNow, col))
        val focus = remember { FocusRequester() }
        val dialogOpen = actionsFor != null || confirmFor != null
        LaunchedEffect(dialogOpen) {
            if (dialogOpen) return@LaunchedEffect
            delay(60)
            runCatching { focus.requestFocus() }
        }
        val deckActions = LocalDeckActions.current
        val legend = buildList {
            add("B" to L("Back"))
            add("A" to L("Open"))
            if (cur?.actions?.isNotEmpty() == true) add("X" to L("Options"))
            add("L2 R2" to L("Tab"))
        }
        DisposableEffect(deckActions, legend) {
            deckActions.ownLegend.value = legend
            onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
        }
        fun runAction(a: MenuAction) { if (a.confirm != null) confirmFor = a else a.run() }
        fun activate(e: MenuEntry?) {
            val f = e?.onA
            if (f != null) {
                val was = e.toggle
                f()
                if (was != null) com.winlator.star.sound.toggleSound(!was)
            } else if (e != null && e.actions.isNotEmpty()) actionsFor = e
        }
        val section = MenuSection("logs", L("Logs"), Icons.Filled.Description, entries)
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (dialogOpen) return@onPreviewKeyEvent false
                    val a = simpleInputOf(ev.nativeKeyEvent) ?: return@onPreviewKeyEvent false
                    when (a) {
                        SIn.UP -> { if (rowNow > 0) row = rowNow - 1 else com.winlator.star.sound.UiSounds.cue(com.winlator.star.sound.UiCue.NAVIGATE_EDGE) }
                        SIn.DOWN -> { if (rowNow < entries.size - 1) row = rowNow + 1 else com.winlator.star.sound.UiSounds.cue(com.winlator.star.sound.UiCue.NAVIGATE_EDGE) }
                        SIn.RIGHT -> { if (col == 0 && cur?.actions?.isNotEmpty() == true) col = 2 }
                        SIn.LEFT -> { if (col != 0) col = 0 else return@onPreviewKeyEvent false }
                        SIn.A -> { if (col == 2 && cur != null && cur.actions.isNotEmpty()) actionsFor = cur else activate(cur) }
                        SIn.X -> { if (cur != null && cur.actions.isNotEmpty()) actionsFor = cur }
                        SIn.B -> onClose()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        ) {
            EntryPanel(
                section, entries, rowNow, active = true, modifier = Modifier.fillMaxSize(), emptyText = null, col = col,
                onMenu = { i -> entries.getOrNull(i)?.takeIf { it.actions.isNotEmpty() }?.let { actionsFor = it } },
            ) { i -> if (i == rowNow) activate(entries.getOrNull(i)) else row = i }
        }
        actionsFor?.let { e -> SimpleActionSheet(e.title, e.actions, onDismiss = { actionsFor = null }) { a -> actionsFor = null; runAction(a) } }
        confirmFor?.let { a -> SimpleConfirm(a.label, a.confirm ?: "", danger = a.danger, onDismiss = { confirmFor = null }) { confirmFor = null; a.run() } }
    }
}

/** A full-screen Lab page in a dialog (the log viewer, and from Settings' Logs tab the channels and the file manager). */
@Composable
internal fun LabFullScreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DeckDialogBars()
        DeckDialogScaled {
            Box(Modifier.fillMaxSize()) {
                LabWallpaper()
                content()
            }
        }
    }
}

/**
 * The Log Manager's state, rows and dialogs. [onOpen] opens a Lab page (the channels, the file manager at the log
 * folder); without it (Settings' Logs tab) they open full screen over it. [flat]: every row carries its section's
 * name as its group (one list).
 */
@Composable
private fun labLogSections(onOpen: ((String) -> Unit)?, flat: Boolean): List<MenuSection> {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val scope = rememberCoroutineScope()

    var perGame by remember { mutableStateOf(prefs.getBoolean(LogLocation.PREF_PER_GAME, true)) }
    var keepLast by remember { mutableStateOf(prefs.getInt(LogLocation.PREF_KEEP_LAST, LogLocation.DEFAULT_KEEP_LAST)) }
    var wineDebug by remember { mutableStateOf(prefs.getBoolean("enable_wine_debug", false)) }
    var box64Logs by remember { mutableStateOf(prefs.getBoolean("enable_box64_logs", false)) }
    var dxvkLogs by remember { mutableStateOf(prefs.getBoolean("enable_dxvk_logs", true)) }
    var controllerLogs by remember { mutableStateOf(prefs.getBoolean(ControllerLog.PREF_KEY, false)) }
    var steamLite by remember { mutableStateOf(prefs.getBoolean("enable_steamlite_logs", false)) }
    var headlessSteam by remember { mutableStateOf(prefs.getBoolean("enable_headless_steam_logs", false)) }
    var ubisoftReport by remember { mutableStateOf(prefs.getBoolean(UbisoftDiag.PREF_REPORT, false)) }
    var ubisoftDeep by remember { mutableStateOf(prefs.getBoolean(UbisoftDiag.PREF_DEEP_TRACE, false)) }
    var logcat by remember { mutableStateOf(prefs.getBoolean("enable_logcat", true)) }
    var crashReports by remember { mutableStateOf(prefs.getBoolean("enable_crash_reports", true)) }
    var exitAutosave by remember { mutableStateOf(prefs.getBoolean(ExitReasonReporter.PREF_AUTOSAVE, false)) }
    var rustSteamEngine by remember { mutableStateOf(prefs.getBoolean(BlSteamEngineFlag.PREF_KEY, BlSteamEngineFlag.DEFAULT)) }
    var rustEpicEngine by remember { mutableStateOf(prefs.getBoolean(BlStoreEngineFlag.PREF_KEY_EPIC, BlStoreEngineFlag.DEFAULT)) }
    var rustGogEngine by remember { mutableStateOf(prefs.getBoolean(BlStoreEngineFlag.PREF_KEY_GOG, BlStoreEngineFlag.DEFAULT)) }
    var rustAmazonEngine by remember { mutableStateOf(prefs.getBoolean(BlStoreEngineFlag.PREF_KEY_AMAZON, BlStoreEngineFlag.DEFAULT)) }
    var locationMode by remember { mutableStateOf(prefs.getString(LogLocation.PREF_MODE, LogLocation.MODE_DOCUMENTS) ?: LogLocation.MODE_DOCUMENTS) }
    var customPath by remember { mutableStateOf(prefs.getString(LogLocation.PREF_CUSTOM_PATH, "") ?: "") }
    var channels by remember { mutableStateOf(readChannels(context)) }
    var storeDlTier by remember { mutableStateOf(StoreDownloadTier.get(context)) }
    var refreshTick by remember { mutableIntStateOf(0) }
    OnResume { refreshTick++; channels = readChannels(context) }

    fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun saveMode(mode: String) {
        locationMode = mode
        prefs.edit().putString(LogLocation.PREF_MODE, mode).apply()
        refreshTick++
    }
    val dirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            InAppFilePicker.pickedPath(result.data)?.let { path ->
                customPath = path
                prefs.edit().putString(LogLocation.PREF_CUSTOM_PATH, path).apply()
                saveMode(LogLocation.MODE_CUSTOM)
            }
        }
    }

    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var exitReport by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<LogInventory.Entry?>(null) }
    var filesOf by remember { mutableStateOf<LogInventory.Entry?>(null) }
    var confirmDelete by remember { mutableStateOf<LogInventory.Entry?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var browseHere by remember { mutableStateOf<File?>(null) }
    var channelsHere by remember { mutableStateOf(false) }
    val entries = remember(refreshTick, perGame) { LogInventory.scan(context) }

    fun what(title: String, body: String) = listOf(MenuAction(L("What's this?"), { info = title to body }))
    fun grp(section: String, sub: String? = null): String? = if (flat) section else sub
    fun browse() {
        val dir = LogLocation.resolveLogDir(context) ?: return
        if (onOpen != null) onOpen(LabToolRoutes.filesAt(dir)) else browseHere = dir
    }
    fun openChannels() { if (onOpen != null) onOpen(LabToolRoutes.LOG_CHANNELS) else channelsHere = true }

    // ── Logs ──
    val sLogs = L("Logs")
    val logs = entries.map { e ->
        val title = if (e.isAppBucket) L("App & crash logs") else e.name
        MenuEntry(
            key = "g:" + e.dir.absolutePath,
            title = title,
            subtitle = buildString {
                append(if (e.fileCount == 1) L("1 file") else L("%1\$s files", e.fileCount))
                append(" · ").append(LogInventory.humanBytes(e.totalBytes))
                if (e.archivedRuns > 0) append(" · ").append(L("%1\$s kept", e.archivedRuns))
                append(" · ").append(relativeTime(e.lastModified))
            },
            icon = when {
                e.isAppBucket -> Icons.Filled.PhoneAndroid
                e.isLooseBucket -> Icons.Filled.History
                e.dir.name.startsWith("Container") -> Icons.Filled.Settings
                else -> Icons.Filled.SportsEsports
            },
            group = grp(sLogs),
            onA = { viewing = e },
            actions = listOf(
                MenuAction(L("View"), { viewing = e }),
                MenuAction(L("Share"), { shareLogGroup(context, e) }),
                MenuAction(L("Its files"), { filesOf = e }),
                MenuAction(L("Delete"), { confirmDelete = e }, danger = true),
            ),
        )
    }
    val sHouse = L("Housekeeping")
    val housekeeping = listOf(
        MenuEntry(
            "total", L("Total size"),
            L("%1\$s across %2\$s", LogInventory.humanBytes(LogInventory.totalBytes(entries)), if (entries.size == 1) L("1 folder") else L("%1\$s folders", entries.size)),
            icon = Icons.Filled.Storage, group = grp(sLogs, sHouse), onA = { browse() },
        ),
        MenuEntry("browse", L("Browse the log folder"), LogLocation.resolveLogDir(context)?.absolutePath, icon = Icons.Filled.FolderOpen, group = grp(sLogs, sHouse), onA = { browse() }),
        MenuEntry(
            "clear", L("Clear all logs"), if (entries.isEmpty()) L("Nothing to clear") else null, icon = Icons.Filled.DeleteSweep, group = grp(sLogs, sHouse),
            onA = { if (entries.isNotEmpty()) confirmClearAll = true },
        ),
    )

    // ── What to record ──
    val sRec = L("What to record")
    val ubiReport = context.getString(R.string.ubisoft_diag_report_title)
    val ubiDeep = context.getString(R.string.ubisoft_deep_trace_title)
    val record = buildList {
        fun toggle(key: String, title: String, hint: String?, on: Boolean, help: Pair<String, String>, flip: () -> Unit) =
            add(MenuEntry(key, title, hint, icon = Icons.Filled.Tune, group = grp(sRec), toggle = on, onA = flip, actions = what(help.first, help.second)))
        toggle("wine", L("Wine debug"), L("Slows games down — for diagnosing a problem"), wineDebug, L("Wine debug") to LogCopy.WINE) {
            wineDebug = !wineDebug; putBool("enable_wine_debug", wineDebug)
        }
        if (wineDebug) add(
            MenuEntry(
                "channels", L("Wine debug channels"), channels.joinToString(", ").ifEmpty { L("None selected") },
                icon = Icons.Filled.ListAlt, group = grp(sRec), onA = { openChannels() },
                actions = listOf(
                    MenuAction(L("Choose channels"), { openChannels() }),
                    MenuAction(L("Reset to defaults"), {
                        channels = com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS.split(",").filter { it.isNotBlank() }
                        saveChannels(context, channels)
                    }),
                ),
            ),
        )
        toggle("box64", L("Box64 / FEXCore"), L("Slows games down at higher verbosity"), box64Logs, L("Box64 / FEXCore") to LogCopy.BOX64) {
            box64Logs = !box64Logs; putBool("enable_box64_logs", box64Logs)
        }
        toggle("dxvk", L("DXVK & VKD3D"), L("Safe to leave on — mostly written at startup"), dxvkLogs, L("DXVK & VKD3D") to LogCopy.DXVK) {
            dxvkLogs = !dxvkLogs; putBool("enable_dxvk_logs", dxvkLogs)
        }
        toggle("controller", L("Controller input"), L("For controls that don't work — written when the game exits"), controllerLogs, L("Controller input") to LogCopy.CONTROLLER) {
            controllerLogs = !controllerLogs; putBool(ControllerLog.PREF_KEY, controllerLogs)
        }
        toggle("steamlite", L("SteamLite (Steam client)"), L("Only when a game launches through SteamLite"), steamLite, L("SteamLite (Steam client)") to LogCopy.STEAMLITE) {
            steamLite = !steamLite; putBool("enable_steamlite_logs", steamLite)
        }
        toggle("headless", L("Headless Steam (app session host)"), L("Only when a game launches through Headless Steam"), headlessSteam, L("Headless Steam (app session host)") to LogCopy.HEADLESS_STEAM) {
            headlessSteam = !headlessSteam; putBool("enable_headless_steam_logs", headlessSteam)
        }
        toggle("ubireport", ubiReport, context.getString(R.string.ubisoft_diag_report_hint), ubisoftReport, ubiReport to context.getString(R.string.ubisoft_diag_report_info)) {
            ubisoftReport = !ubisoftReport; putBool(UbisoftDiag.PREF_REPORT, ubisoftReport)
        }
        toggle("ubideep", ubiDeep, context.getString(R.string.ubisoft_deep_trace_hint), ubisoftDeep, ubiDeep to context.getString(R.string.ubisoft_deep_trace_info)) {
            ubisoftDeep = !ubisoftDeep; putBool(UbisoftDiag.PREF_DEEP_TRACE, ubisoftDeep)
        }
        toggle("logcat", L("Android logcat"), L("Bannerlator's own output only"), logcat, L("Android logcat") to LogCopy.LOGCAT) {
            logcat = !logcat; putBool("enable_logcat", logcat)
        }
        toggle("crash", L("Crash reports"), L("Nothing runs until something crashes"), crashReports, L("Crash reports") to LogCopy.CRASH) {
            crashReports = !crashReports; putBool("enable_crash_reports", crashReports)
        }
        val exitOk = ExitReasonReporter.isSupported()
        toggle(
            "exit", if (exitOk) L("Exit reasons (auto-save on launch)") else L("Exit reasons (needs Android 11+)"),
            L("Records native crashes without root"), exitAutosave, L("Exit reasons") to LogCopy.EXIT_REASONS,
        ) {
            // As Classic's switch, which is disabled on Android 10 and older: the "?" still explains it.
            if (exitOk) { exitAutosave = !exitAutosave; putBool(ExitReasonReporter.PREF_AUTOSAVE, exitAutosave) }
            else { info = L("Exit reasons") to LogCopy.EXIT_REASONS }
        }
        val sNow = L("Capture")
        add(
            MenuEntry(
                "capture", L("Capture logcat now"), if (logcat) null else L("Turn on Android logcat first"), icon = Icons.Filled.Terminal,
                group = grp(sRec, sNow), actions = what(L("Capture logcat now"), LogCopy.CAPTURE_NOW),
                onA = {
                    if (logcat) scope.launch {
                        withContext(Dispatchers.IO) { LogcatCapture.captureToFile(context, LogcatCapture.DEFAULT_LINES) }
                        refreshTick++
                        toast(context, L("Logcat saved with your logs"))
                    }
                },
            ),
        )
        add(
            MenuEntry(
                "lastcrash", L("Read last crash"), if (exitOk) null else L("Needs Android 11 or newer"), icon = Icons.Filled.BugReport,
                group = grp(sRec, sNow), actions = what(L("Exit reasons"), LogCopy.EXIT_REASONS),
                onA = {
                    if (exitOk) scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            ExitReasonReporter.captureToFile(context)
                            ExitReasonReporter.capture(context)
                        }
                        exitReport = text
                        refreshTick++
                    }
                },
            ),
        )
    }

    // ── Where logs go ──
    val sWhere = L("Where logs go")
    val modeLabel = when (locationMode) {
        LogLocation.MODE_DOWNLOAD -> L("Download")
        LogLocation.MODE_CUSTOM -> if (customPath.isNotEmpty()) L("Custom folder") else L("Choose folder…")
        // MODE_DOCUMENTS (default) and the retired MODE_APP_DATA both resolve to Documents.
        else -> L("Documents")
    }
    fun mark(label: String, on: Boolean) = if (on) "$label  ✓" else label
    val keepChoices = listOf(0, 1, 3, 5, 10, 20, 50)
    val where = listOf(
        MenuEntry(
            "location", L("Log folder"), LogLocation.resolveLogDir(context)?.absolutePath ?: "—", trailing = modeLabel, icon = Icons.Filled.Folder, group = grp(sWhere),
            actions = listOf(
                MenuAction(mark(L("Download"), locationMode == LogLocation.MODE_DOWNLOAD), { saveMode(LogLocation.MODE_DOWNLOAD) }),
                MenuAction(mark(L("Documents"), locationMode != LogLocation.MODE_DOWNLOAD && locationMode != LogLocation.MODE_CUSTOM), { saveMode(LogLocation.MODE_DOCUMENTS) }),
                MenuAction(mark(L("Choose folder…"), locationMode == LogLocation.MODE_CUSTOM), { dirLauncher.launch(InAppFilePicker.buildDirIntent(context, L("Select log folder"))) }),
            ),
        ),
        MenuEntry(
            "pergame", L("Folder for each game"), L("Each game keeps its own folder"), icon = Icons.Filled.Tune, group = grp(sWhere), toggle = perGame,
            onA = { perGame = !perGame; putBool(LogLocation.PREF_PER_GAME, perGame); refreshTick++ },
            actions = what(L("Folder for each game"), LogCopy.PER_GAME),
        ),
        MenuEntry(
            "keep", L("Keep last"),
            if (keepLast == 0) L("No history — each run replaces the last") else if (keepLast == 1) L("1 launch per game") else L("%1\$s launches per game", keepLast),
            trailing = if (keepLast == 0) L("No history") else "$keepLast", icon = Icons.Filled.Restore, group = grp(sWhere),
            actions = keepChoices.map { n ->
                MenuAction(mark(if (n == 0) L("No history") else if (n == 1) L("1 launch") else L("%1\$s launches", n), n == keepLast), {
                    keepLast = n
                    prefs.edit().putInt(LogLocation.PREF_KEEP_LAST, n).apply()
                })
            } + what(L("Keep last launches"), LogCopy.KEEP_LAST),
        ),
    )

    // ── Download engines ──
    val sEng = L("Download engines")
    val engines = listOf(
        MenuEntry(
            "rustSteam", L("Native Steam engine (restart required)"), L("On by default · its log joins the SteamLite bundle · off = legacy Java engine"),
            icon = Icons.Filled.CloudDownload, group = grp(sEng), toggle = rustSteamEngine,
            onA = { rustSteamEngine = !rustSteamEngine; putBool(BlSteamEngineFlag.PREF_KEY, rustSteamEngine) },
            actions = what(L("Rust Steam engine"), LogCopy.RUST_ENGINE),
        ),
        MenuEntry(
            "rustEpic", L("Rust engine: Epic downloads"), L("On by default · next download · off = Java downloader"),
            icon = Icons.Filled.CloudDownload, group = grp(sEng), toggle = rustEpicEngine,
            onA = { rustEpicEngine = !rustEpicEngine; putBool(BlStoreEngineFlag.PREF_KEY_EPIC, rustEpicEngine) },
            actions = what(L("Rust store engines"), LogCopy.RUST_STORE_ENGINES),
        ),
        MenuEntry(
            "rustGog", L("Rust engine: GOG downloads"), L("On by default · next download · off = Java downloader"),
            icon = Icons.Filled.CloudDownload, group = grp(sEng), toggle = rustGogEngine,
            onA = { rustGogEngine = !rustGogEngine; putBool(BlStoreEngineFlag.PREF_KEY_GOG, rustGogEngine) },
            actions = what(L("Rust store engines"), LogCopy.RUST_STORE_ENGINES),
        ),
        MenuEntry(
            "rustAmazon", L("Rust engine: Amazon downloads"), L("On by default · next download · off = Java downloader"),
            icon = Icons.Filled.CloudDownload, group = grp(sEng), toggle = rustAmazonEngine,
            onA = { rustAmazonEngine = !rustAmazonEngine; putBool(BlStoreEngineFlag.PREF_KEY_AMAZON, rustAmazonEngine) },
            actions = what(L("Rust store engines"), LogCopy.RUST_STORE_ENGINES),
        ),
        MenuEntry(
            "speed", L("Store download speed"), L("Ceiling for the Rust Epic / GOG / Amazon engines; next download"),
            trailing = StoreDownloadTier.label(storeDlTier), icon = Icons.Filled.Speed, group = grp(sEng),
            // As Classic: A steps to the next speed; X lists them all.
            onA = { storeDlTier = StoreDownloadTier.next(storeDlTier); StoreDownloadTier.set(context, storeDlTier) },
            actions = StoreDownloadTier.ALL.map { t -> MenuAction(mark(StoreDownloadTier.label(t), t == storeDlTier), { storeDlTier = t; StoreDownloadTier.set(context, t) }) } +
                what(L("Rust store engines"), LogCopy.RUST_STORE_ENGINES),
        ),
    )

    // ── Help ──
    val sHelp = L("Help")
    val privacy = L("E-mail addresses, auth tokens and your Steam account name are scrubbed as logs are written. File paths are kept so they stay useful for debugging — glance over them before sharing if a folder name identifies you. Logcat only ever contains Bannerlator's own output.")
    val help = listOf(
        MenuEntry("explain", L("Explain the log types"), L("What each log is for and what it costs"), icon = Icons.Filled.HelpOutline, group = grp(sHelp), onA = { info = L("What each log is for") to LogCopy.explainAll() }),
        MenuEntry("privacy", L("Before you share a log"), L("What is removed and what is kept"), icon = Icons.Filled.PrivacyTip, group = grp(sHelp), onA = { info = L("Before you share a log") to privacy }),
    )

    // ── Dialogs ──
    info?.let { (t, b) -> LabReadDialog(t, b, onDismiss = { info = null }) }
    exitReport?.let { r ->
        LabReadDialog(
            L("Last crash / exit reasons"), r, onDismiss = { exitReport = null }, mono = true,
            intro = L("Saved with your logs under the \"%1\$s\" folder. Exit #0 is the most recent; a NATIVE_CRASH entry includes the signal and the top backtrace frames.", ExitReasonReporter.FOLDER),
        )
    }
    filesOf?.let { e ->
        val files = remember(e.dir) { LogInventory.filesIn(e.dir) }
        LabReadDialog(
            if (e.isAppBucket) L("App & crash logs") else e.name,
            if (files.isEmpty()) L("This folder has no log files right now.") else files.joinToString("\n") { "• ${it.name}  (${LogInventory.humanBytes(it.length())})" },
            onDismiss = { filesOf = null },
        )
    }
    confirmDelete?.let { e ->
        val count = remember(e.dir) { LogInventory.deletableCount(e) }
        val title = when {
            e.isAppBucket -> L("Delete app & crash logs?")
            e.isLooseBucket -> L("Delete %1\$s?", e.name.replaceFirstChar { it.lowercase() })
            else -> L("Delete %1\$s logs?", e.name)
        }
        if (count == 0) {
            SimpleActionSheet(
                title, listOf(MenuAction(L("OK"), { confirmDelete = null })), onDismiss = { confirmDelete = null },
                message = L("Nothing here was written by Bannerlator, so there is nothing to delete. The files in this folder are yours and are left alone."),
            ) { it.run() }
        } else {
            SimpleActionSheet(
                title,
                listOf(
                    MenuAction(L("Delete"), {
                        val n = LogInventory.deleteGroup(context, e)
                        confirmDelete = null
                        refreshTick++
                        toast(context, if (n == 1) L("Deleted 1 file") else L("Deleted %1\$s files", n))
                    }, danger = true),
                    MenuAction(L("Cancel"), { confirmDelete = null }),
                ),
                onDismiss = { confirmDelete = null },
                message = (if (count == 1) L("Deletes 1 log file, including any kept history.") else L("Deletes %1\$s log files, including any kept history.", count)) +
                    "\n\n" + L("Only files Bannerlator wrote are removed — anything else in that folder is left alone."),
            ) { it.run() }
        }
    }
    if (confirmClearAll) {
        val count = remember(refreshTick) { entries.sumOf { LogInventory.deletableCount(it) } }
        SimpleActionSheet(
            L("Clear all logs?"),
            listOf(
                MenuAction(L("Delete all"), {
                    var n = 0
                    entries.forEach { n += LogInventory.deleteGroup(context, it) }
                    confirmClearAll = false
                    refreshTick++
                    toast(context, if (n == 1) L("Deleted 1 file") else L("Deleted %1\$s files", n))
                }, danger = true),
                MenuAction(L("Cancel"), { confirmClearAll = false }),
            ),
            onDismiss = { confirmClearAll = false },
            message = L("Deletes %1\$s log files across %2\$s folders, including kept history.", count, entries.size) + "\n\n" +
                L("Only files Bannerlator wrote are removed. Other files in your log folder — including anything you put there yourself — are left alone."),
        ) { it.run() }
    }
    viewing?.let { e -> LabFullScreen(onDismiss = { viewing = null }) { LabLogViewer(entry = e, onClose = { viewing = null }) } }
    browseHere?.let { dir ->
        LabFullScreen(onDismiss = { browseHere = null; refreshTick++ }) {
            LabFileManager(onBack = { browseHere = null; refreshTick++ }, initialDir = dir, standalone = true)
        }
    }
    if (channelsHere) {
        LabFullScreen(onDismiss = { channelsHere = false; channels = readChannels(context) }) {
            LabWineChannelsPage(onBack = { channelsHere = false; channels = readChannels(context) }, standalone = true)
        }
    }

    return listOf(
        MenuSection("logs", sLogs, Icons.Filled.Description, logs + housekeeping),
        MenuSection("record", sRec, Icons.Filled.Tune, record),
        MenuSection("where", sWhere, Icons.Filled.Folder, where),
        MenuSection("engines", sEng, Icons.Filled.CloudDownload, engines),
        MenuSection("help", sHelp, Icons.Filled.HelpOutline, help),
    )
}

/**
 * Wine debug channels (Lab): the common ones and every channel Wine has, grouped by family, each a switch. Y searches
 * all of them; "What each does" adds a line under every channel, "Only the ones on" keeps the list short. Saved at
 * once, as in Classic. [standalone]: over Settings' Logs tab (no Lab shell to show the button hints).
 */
@Composable
internal fun LabWineChannelsPage(onBack: () -> Unit, standalone: Boolean = false) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(readChannels(context)) }
    var query by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }
    var selectedOnly by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val all = remember {
        try {
            val arr = org.json.JSONArray(com.winlator.star.core.FileUtils.readString(context, "wine_debug_channels.json"))
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
    fun set(list: List<String>) { selected = list; saveChannels(context, list) }
    fun flip(ch: String) = set(if (ch in selected) selected - ch else selected + ch)
    fun chEntry(ch: String, key: String, group: String? = null) = MenuEntry(
        key = key, title = ch, subtitle = if (showHelp) WineChannelInfo.describe(ch) else null, group = group,
        toggle = ch in selected, onA = { flip(ch) },
    )
    // As Classic's chip grid: what is on first (so it can always be switched off), then the common set; a search lists
    // up to 60 matches from all of them.
    val q = query.trim()
    val common = buildList {
        add(MenuEntry("reset", L("Reset to defaults"), com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS.replace(",", ", "), icon = Icons.Filled.Restore, onA = {
            set(com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS.split(",").filter { it.isNotBlank() })
            query = ""
        }))
        val shown = if (q.isBlank()) (selected + COMMON_WINE_CHANNELS).distinct() else all.filter { it.contains(q, ignoreCase = true) }.take(60)
        shown.forEach { add(chEntry(it, "c:$it")) }
    }
    // As Classic's "Browse all": grouped by family, the everyday ones first in each, then the ones with a description.
    val grouped = remember(all) {
        all.groupBy { WineChannelInfo.categoryOf(it) }.toList()
            .sortedBy { (cat, _) -> WineChannelInfo.CATEGORY_ORDER.indexOf(cat).let { if (it < 0) WineChannelInfo.CATEGORY_ORDER.size else it } }
            .map { (cat, names) ->
                cat to names.sortedWith(
                    compareBy<String> { val i = COMMON_WINE_CHANNELS.indexOf(it); if (i < 0) COMMON_WINE_CHANNELS.size else i }
                        .thenByDescending { WineChannelInfo.hasDetail(it) }
                        .thenBy { it },
                )
            }
    }
    val everything = buildList {
        add(MenuEntry("help", L("What each does"), L("A line under every channel"), icon = Icons.Filled.HelpOutline, toggle = showHelp, onA = { showHelp = !showHelp }))
        add(MenuEntry("onOnly", L("Only the ones on"), L("%1\$s on", selected.size), icon = Icons.Filled.Tune, toggle = selectedOnly, onA = { selectedOnly = !selectedOnly }))
        grouped.forEach { (cat, names) ->
            names.filter { ch -> (q.isBlank() || ch.contains(q, ignoreCase = true) || cat.contains(q, ignoreCase = true)) && (!selectedOnly || ch in selected) }
                .forEach { add(chEntry(it, "a:$it", group = cat)) }
        }
    }
    val search = MenuAction(L("Search"), { searching = true })
    val deckActions = LocalDeckActions.current
    SimpleMenuPage(
        overline = L("Wine debug"),
        title = L("Channels"),
        sections = listOf(
            MenuSection("common", L("Common"), Icons.Filled.Tune, common, empty = L("No channel matches."), primary = search),
            MenuSection("all", L("All channels"), Icons.Filled.ListAlt, everything, primary = search),
        ),
        onBack = onBack,
        banner = buildString {
            append(L("%1\$s available · %2\$s on", all.size, selected.size))
            if (q.isNotBlank()) append(" · ").append(L("Search \"%1\$s\"", q))
        },
    )
    if (standalone) {
        // No Lab shell over Settings: draw the hints the shell would.
        val legend = deckActions.ownLegend.value
        if (legend != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            SimpleLegend(legend, Modifier.padding(start = 24.dp, bottom = 10.dp))
        }
    }
    if (searching) {
        LabTextPrompt(
            title = L("Search all %1\$s channels", all.size), initial = query, confirm = L("Done"), placeholder = L("Channel name"),
            valid = { true }, onChange = { query = it }, onDismiss = { searching = false },
        ) { v -> query = v; searching = false }
    }
}
