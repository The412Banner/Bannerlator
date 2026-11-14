package com.winlator.star.ui.simple

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.preference.PreferenceManager
import com.winlator.star.MainActivity
import com.winlator.star.R
import com.winlator.star.SettingsFragment
import com.winlator.star.core.FileUtils
import com.winlator.star.core.UpdateManager
import com.winlator.star.core.WinFgCapture
import com.winlator.star.core.WinFgDiag
import com.winlator.star.midi.MidiManager
import com.winlator.star.store.NetworkProbe
import com.winlator.star.store.SteamLibraryImport
import com.winlator.star.store.SteamLibraryLocations
import com.winlator.star.store.SteamRegion
import com.winlator.star.store.SteamSdInstall
import com.winlator.star.store.locationDescription
import com.winlator.star.ui.screens.PresetKind
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.xenvironment.ImageFsInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Lab's app-settings pages: what Classic's Settings screen did with a button, a picker or a dialog, as Lab lists. Lab's
// Settings (SimpleOneMenu) opens them through AppSettingsRegistry.labHandoffs. Each one writes the same preference and runs
// the same code as Classic (and the XMB page items of AppSettingsExtras), at once. Root, heat, Lossless.dll and the
// translator presets are in LabPerformancePages.kt.

/** The routes of Lab's settings pages (all start with "lab_settings", which the rail files under Settings). */
internal object LabSettingsRoutes {
    const val UPDATES = "lab_settings_updates"
    const val STORAGE = "lab_settings_storage"
    const val BACKUP = "lab_settings_backup"
    const val SOUND_BANKS = "lab_settings_soundbanks"
    const val STEAM_CONNECTION = "lab_settings_steam"
    const val STEAM_LIBRARIES = "lab_settings_steamlibs"
    const val FG_CAPTURE = "lab_settings_fgcapture"
    const val PERFORMANCE = "lab_settings_performance"
    const val LOSSLESS = "lab_settings_lossless"
    const val PRESETS = "lab_settings_presets"
    const val PRESET_EDIT = "lab_settings_preset?kind={kind}&id={id}"
    fun presetEdit(kind: PresetKind, id: String?) = "lab_settings_preset?kind=" + kind.name + "&id=" + Uri.encode(id ?: "")
}

/** App-wide settings pages (Lab). */
internal fun NavGraphBuilder.labSettingsRoutes(open: (String) -> Unit, back: () -> Unit) {
    composable(LabSettingsRoutes.UPDATES) { LabUpdatesPage(onBack = back) }
    composable(LabSettingsRoutes.STORAGE) { LabStoragePage(onBack = back) }
    composable(LabSettingsRoutes.BACKUP) { LabBackupPage(onBack = back) }
    composable(LabSettingsRoutes.SOUND_BANKS) { LabSoundBanksPage(onBack = back) }
    composable(LabSettingsRoutes.STEAM_CONNECTION) { LabSteamConnectionPage(onBack = back) }
    composable(LabSettingsRoutes.STEAM_LIBRARIES) { LabSteamLibrariesPage(onBack = back) }
    composable(LabSettingsRoutes.FG_CAPTURE) { LabCapturePage(onBack = back) }
    composable(LabSettingsRoutes.PERFORMANCE) { LabPerformancePage(onBack = back) }
    composable(LabSettingsRoutes.LOSSLESS) { LabLosslessPage(onBack = back) }
    composable(LabSettingsRoutes.PRESETS) { LabPresetsPage(open = open, onBack = back) }
    composable(
        LabSettingsRoutes.PRESET_EDIT,
        arguments = listOf(
            navArgument("kind") { type = NavType.StringType; defaultValue = PresetKind.BOX64.name },
            navArgument("id") { type = NavType.StringType; defaultValue = "" },
        ),
    ) { e ->
        val kind = runCatching { PresetKind.valueOf(e.arguments?.getString("kind").orEmpty()) }.getOrDefault(PresetKind.BOX64)
        LabPresetEditPage(kind = kind, presetId = e.arguments?.getString("id")?.ifEmpty { null }, onBack = back)
    }
}

private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

/** The "Browse files" / "Pick via system…" choice Classic offers on every import (issue #73). */
private fun pickActions(inApp: () -> Unit, system: () -> Unit) = listOf(
    MenuAction(L("Browse files"), inApp),
    MenuAction(L("Pick via system…"), system),
)

// ───────────────────────────── Updates ─────────────────────────────

/** Classic Settings › Updates: installed and latest version, what's new, Download & install, Check for updates. */
@Composable
internal fun LabUpdatesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var notify by remember { mutableStateOf(UpdateManager.isNotifyEnabled(context)) }
    var pre by remember { mutableStateOf(UpdateManager.isIncludePrereleases(context)) }
    var reading by remember { mutableStateOf<Pair<String, String>?>(null) }
    // [report]: the toast Classic's Check for updates gives when there is nothing new or the check failed.
    fun check(force: Boolean, report: Boolean) {
        if (force) checking = true
        UpdateManager.check(context, force = force) { r ->
            mainHandler.post {
                info = r
                if (force) checking = false
                if (report) {
                    if (r == null) toast(context, L("Couldn't check for updates"))
                    else if (!r.isNewer) toast(context, L("You're on the latest version"))
                }
            }
        }
    }
    LaunchedEffect(Unit) { check(force = false, report = false) }
    val latest = info
    val entries = buildList {
        add(MenuEntry("installed", L("Installed"), trailing = "V " + UpdateManager.installedVersionName(), icon = Icons.Filled.Info))
        add(MenuEntry(
            "latest", L("Latest"),
            subtitle = when {
                latest == null -> null
                latest.isNewer -> L("Update available")
                else -> L("You're up to date.")
            },
            trailing = latest?.let { "V " + it.versionName } ?: if (checking) "…" else "—",
            icon = Icons.Filled.SystemUpdate,
        ))
        if (latest != null && latest.isNewer) {
            add(MenuEntry(
                "install", L("Download and install V %1\$s", latest.versionName), icon = Icons.Filled.Download,
                onA = { context.labSetActivity()?.let { UpdateManager.downloadAndInstall(it, latest) {} } },
            ))
            if (latest.highlights.isNotEmpty()) {
                latest.highlights.forEachIndexed { i, h ->
                    val title = h.title.ifEmpty { h.text }
                    add(MenuEntry(
                        "h$i", title, subtitle = if (h.title.isNotEmpty()) h.text else null, group = L("What's new"),
                        onA = { reading = title to h.text },
                    ))
                }
            } else if (latest.notes.isNotBlank()) {
                add(MenuEntry("notes", L("Release notes"), subtitle = latest.notes, icon = Icons.Filled.Description, group = L("What's new"),
                    onA = { reading = L("Release notes") to latest.notes }))
            }
        }
        add(MenuEntry(
            "check", if (checking) L("Checking…") else L("Check for updates"), icon = Icons.Filled.Refresh, group = L("Updates"),
            onA = { if (!checking) check(force = true, report = true) },
        ))
        add(MenuEntry("notify", L("Tell me about new versions"), group = L("Updates"), toggle = notify, onA = {
            notify = !notify
            UpdateManager.setNotifyEnabled(context, notify)
        }))
        // A channel switch re-checks at once, as Classic does.
        add(MenuEntry("pre", L("Include beta builds"), L("Pre-releases may be less stable."), group = L("Updates"), toggle = pre, onA = {
            pre = !pre
            UpdateManager.setIncludePrereleases(context, pre)
            check(force = true, report = false)
        }))
    }
    SimpleMenuPage(
        overline = L("Settings"),
        title = L("Updates"),
        sections = listOf(MenuSection("updates", L("Updates"), Icons.Filled.SystemUpdate, entries)),
        onBack = onBack,
    )
    reading?.let { (t, b) -> LabSetInfo(t, b) { reading = null } }
}

// ───────────────────────────── Storage and folders ─────────────────────────────

/**
 * Space used, Classic's Path Settings (app data and shortcut export folders) and Reinstall ImageFS. A picked folder is
 * saved at once (Classic keeps it until its Save button).
 */
@Composable
internal fun LabStoragePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    fun readTree(key: String, default: String): String {
        val s = prefs.getString(key, null) ?: return default
        return FileUtils.getFilePathFromUri(context, Uri.parse(s)) ?: s
    }
    var dataPath by remember { mutableStateOf(readTree("winlator_path_uri", SettingsFragment.DEFAULT_WINLATOR_PATH)) }
    var exportPath by remember { mutableStateOf(readTree("shortcuts_export_path_uri", SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH)) }
    var treeKey by remember { mutableStateOf<String?>(null) }
    var askImageFs by remember { mutableStateOf(false) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val key = treeKey
        treeKey = null
        if (uri != null && key != null) {
            prefs.edit().putString(key, uri.toString()).apply()
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
            dataPath = readTree("winlator_path_uri", SettingsFragment.DEFAULT_WINLATOR_PATH)
            exportPath = readTree("shortcuts_export_path_uri", SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH)
            toast(context, L("Saved"))
        }
    }
    val dev = rememberLabDevice()
    val space = dev?.volumes.orEmpty().mapIndexed { i, v ->
        MenuEntry(
            "vol$i", L(v.label), subtitle = L("%1\$s of %2\$s GB used", fmtGb(v.usedGb), fmtGb(v.totalGb)),
            trailing = "${(v.fraction * 100).toInt()}%", icon = if (v.removable) Icons.Filled.SdCard else Icons.Filled.Storage, group = L("Space used"),
        )
    }
    val folders = listOf(
        MenuEntry("data", L("App data folder"), dataPath, icon = Icons.Filled.Folder, group = L("Folders"),
            onA = { treeKey = "winlator_path_uri"; tree.launch(null) }),
        MenuEntry("export", L("Shortcut export folder"), exportPath, icon = Icons.Filled.Folder, group = L("Folders"),
            onA = { treeKey = "shortcuts_export_path_uri"; tree.launch(null) }),
        MenuEntry("imagefs", L("Reinstall the base system (ImageFS)"), L("Restores the system files. Your containers are not changed."),
            icon = Icons.Filled.Storage, group = L("Base system"), onA = { askImageFs = true }),
    )
    SimpleMenuPage(
        overline = L("Settings"),
        title = L("Storage and folders"),
        sections = listOf(MenuSection("storage", L("Storage"), Icons.Filled.Storage, space + folders)),
        onBack = onBack,
    )
    if (askImageFs) {
        SimpleConfirm(L("Reinstall ImageFS"), context.getString(R.string.do_you_want_to_reinstall_imagefs), danger = false, onDismiss = { askImageFs = false }) {
            askImageFs = false
            (context.labSetActivity() as? MainActivity)?.let { ImageFsInstaller.installFromAssets(it) }
        }
    }
}

// ───────────────────────────── Backup and restore ─────────────────────────────

/** Classic's Backup Data / Restore Data (in-app or system picker), with the same questions first. */
@Composable
internal fun LabBackupPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var askBackup by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val inApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { restoreUri = it }
    }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) restoreUri = uri }
    fun runBackup() {
        backingUp = true
        val ctx = context.applicationContext
        Thread({
            val dataDir = ctx.filesDir.parentFile
            val backupFile = File(Environment.getExternalStorageDirectory(), "app_data_backup.tar")
            val ok = try {
                com.winlator.star.core.TarCompressorUtils.archive(arrayOf(dataDir), backupFile) { file -> !file.absolutePath.contains("imagefs/tmp/.sysvshm") }
                true
            } catch (_: Exception) { false }
            mainHandler.post {
                backingUp = false
                toast(context, if (ok) L("Backup completed: %1\$s", backupFile.path) else L("Backup failed."))
            }
        }, "app-data-backup").start()
    }
    val entries = listOf(
        MenuEntry("backup", L("Back up app data"), L("Containers, settings and prefix in one file: app_data_backup.tar in your storage root."),
            icon = Icons.Filled.FileUpload, onA = { askBackup = true }),
        MenuEntry("restore", L("Restore app data"), L("Pick a backup file. The app restarts."), icon = Icons.Filled.Archive,
            actions = pickActions(
                inApp = { inApp.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.SAVE, L("Select backup"))) },
                system = { system.launch(arrayOf("*/*")) },
            )),
    )
    SimpleMenuPage(
        overline = L("Settings"),
        title = L("Backup and restore"),
        sections = listOf(MenuSection("backup", L("Backup and restore"), Icons.Filled.Archive, entries)),
        onBack = onBack,
    )
    if (askBackup) {
        SimpleConfirm(L("Back up app data"), L("Do you want to create a backup of the app's data directory?"), danger = false, onDismiss = { askBackup = false }) {
            askBackup = false
            runBackup()
        }
    }
    if (backingUp) LabSetBusy(L("Backing up data"), L("This can take a while."))
    restoreUri?.let { uri ->
        SimpleConfirm(L("Restore app data"), L("This will restart the app. Continue?"), danger = true, onDismiss = { restoreUri = null }) {
            restoreUri = null
            val intent = Intent(context, com.winlator.star.restore.RestoreActivity::class.java)
            intent.data = uri
            context.startActivity(intent)
            context.labSetActivity()?.finish()
        }
    }
}

// ───────────────────────────── MIDI sound banks ─────────────────────────────

/** Classic's SoundFont install (in-app or system picker) and remove. Picking the default bank is a row in Settings. */
@Composable
internal fun LabSoundBanksPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val banks = remember(tick) {
        MidiManager.getSoundFontDir(context).listFiles()
            ?.map { it.name }?.filter { it.endsWith(".sf2") && it != MidiManager.DEFAULT_SF2_FILE }?.sorted() ?: emptyList()
    }
    fun install(uri: Uri) {
        busy = true
        MidiManager.installSF2File(context, uri, object : MidiManager.OnSoundFontInstalledCallback {
            override fun onSuccess() {
                mainHandler.post {
                    busy = false
                    result = context.getString(R.string.sound_font_installed_success)
                    tick++
                }
            }
            override fun onFailed(reason: Int) {
                val resId = when (reason) {
                    MidiManager.ERROR_BADFORMAT -> R.string.sound_font_bad_format
                    MidiManager.ERROR_EXIST -> R.string.sound_font_already_exist
                    else -> R.string.sound_font_installed_failed
                }
                mainHandler.post {
                    busy = false
                    result = context.getString(resId)
                }
            }
        })
    }
    val inApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { install(it) }
    }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { install(it) } }
    val entries = listOf(
        MenuEntry("builtin", L("Built-in General MIDI bank"), L("Always available; it cannot be removed."), icon = Icons.Filled.MusicNote),
    ) + banks.map { name ->
        MenuEntry(
            "sf:$name", name, icon = Icons.Filled.MusicNote,
            actions = listOf(MenuAction(L("Remove"), {
                if (MidiManager.removeSF2File(context, name)) {
                    toast(context, context.getString(R.string.sound_font_removed_success))
                    tick++
                } else toast(context, context.getString(R.string.sound_font_removed_failed))
            }, danger = true, confirm = context.getString(R.string.do_you_want_to_remove_this_sound_font))),
        )
    }
    SimpleMenuPage(
        overline = L("Settings"),
        title = L("MIDI sound banks"),
        sections = listOf(MenuSection(
            "banks", L("Sound banks"), Icons.Filled.MusicNote, entries,
            primary = MenuAction(L("Add a sound bank (.sf2)"), { picking = true }),
        )),
        onBack = onBack,
    )
    if (picking) {
        SimpleActionSheet(L("Add a sound bank (.sf2)"), pickActions(
            inApp = { inApp.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.SF2, L("Select SoundFont"))) },
            system = { system.launch(arrayOf("*/*")) },
        ), onDismiss = { picking = false }) { a -> picking = false; a.run() }
    }
    if (busy) LabSetBusy(L("Sound banks"), context.getString(R.string.installing_content))
    result?.let { LabSetInfo(L("Sound banks"), it) { result = null } }
}

// ───────────────────────────── Steam connection ─────────────────────────────

private const val REGION_HELP =
    "Which Steam datacenter to connect to for the store, downloads and the in-game Steam session. Auto pings each one and " +
        "keeps the fastest for a day, re-testing after a failed connect. Pick a specific one only if Auto keeps choosing badly: " +
        "the store applies it on its next connect."
private const val TESTS_HELP =
    "Test regions pings every Steam datacenter now and shows each one's response time in the list; Auto also remembers the " +
        "fastest as its pick.\n\nTest network checks how this network handles online game traffic (the same check the SteamLite " +
        "launch shows). Steam sign-in and downloads work on any NAT; games with their own servers (e.g. Brawlhalla) can fail " +
        "behind a strict/symmetric NAT such as phone hotspots or many VPNs. Fix: home Wi-Fi, a hotspot with port mapping, or a " +
        "VPN with port forwarding."

/** Classic Settings › Steam: the connection region (with each datacenter's ping once tested), Test regions, Test network. */
@Composable
internal fun LabSteamConnectionPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(SteamRegion.mode(context)) }
    var remembered by remember { mutableStateOf(SteamRegion.rememberedAuto(context)) }
    var choices by remember { mutableStateOf(SteamRegion.CATALOG) }
    var probe by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var probing by remember { mutableStateOf(false) }
    var net by remember { mutableStateOf(NetworkProbe.cached()) }
    var netProbing by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun testRegions() {
        probing = true
        Thread({
            val res = try { SteamRegion.probeAll(context) } catch (_: Throwable) { emptyList() }
            val discovered = try { SteamRegion.datacenters(SteamRegion.fetchDirectory()) } catch (_: Throwable) { SteamRegion.CATALOG }
            mainHandler.post {
                probe = res.associate { it.dc to it.ms }
                choices = discovered
                remembered = SteamRegion.rememberedAuto(context)
                probing = false
            }
        }, "steam-region-probe-ui").start()
    }
    fun testNetwork() {
        netProbing = true
        Thread({
            val res = NetworkProbe.probe()   // never throws; about 2.5 s at most
            mainHandler.post { net = res; netProbing = false }
        }, "steam-net-probe-ui").start()
    }
    fun pick(code: String) {
        mode = code
        SteamRegion.setMode(context, code)
    }
    val n = net
    val tests = listOf(
        MenuEntry(
            "regions", if (probing) L("Testing regions…") else L("Test regions"),
            subtitle = if (probing) L("Pinging every Steam datacenter…") else L("Pings every Steam datacenter now. Auto remembers the fastest."),
            icon = Icons.Filled.Refresh, group = L("Tests"), onA = { if (!probing) testRegions() },
        ),
        MenuEntry(
            "network", if (netProbing) L("Testing network…") else L("Test network"),
            subtitle = when {
                netProbing -> L("Checking how this network handles online game traffic…")
                n == null -> L("Checks the NAT type and UDP for online games (the same check the SteamLite launch shows).")
                else -> L("Network: %1\$s", n.verdict()) +
                    (if (n.nat != NetworkProbe.Nat.OPEN_CONE && n.maskedIp != null) " (IP ${n.maskedIp})" else "") +
                    (if (n.ipv6) " · IPv6" else "")
            },
            trailing = if (n != null && n.isWarning && !netProbing) "⚠" else null,
            icon = Icons.Filled.Speed, group = L("Tests"), onA = { if (!netProbing) testNetwork() },
        ),
        MenuEntry("testshelp", L("About these tests"), icon = Icons.Filled.HelpOutline, group = L("Tests"),
            onA = { help = L("Test regions / Test network") to L(TESTS_HELP) }),
    )
    val r = remembered
    val regions = listOf(
        MenuEntry(
            "auto", L("Auto (nearest by ping)"),
            subtitle = if (r != null) "${SteamRegion.nameOf(r.dc)} ${r.dc}, ${r.ms} ms" else L("Remembers the winner"),
            current = mode == SteamRegion.AUTO, icon = Icons.Filled.Public, group = L("Steam connection region"),
            onA = { pick(SteamRegion.AUTO) },
        ),
    ) + choices.map { dc ->
        val ping = probe[dc.code]
        MenuEntry(
            "dc:${dc.code}", dc.name + "  (" + dc.code + ")",
            trailing = ping?.let { if (it < 0) L("no response") else "$it ms" },
            current = mode == dc.code, group = L("Steam connection region"),
            onA = { pick(dc.code) },
        )
    } + MenuEntry("regionhelp", L("About the region"), icon = Icons.Filled.HelpOutline, group = L("Steam connection region"),
        onA = { help = L("Steam connection region") to L(REGION_HELP) })
    SimpleMenuPage(
        overline = L("Settings"),
        title = L("Steam connection"),
        sections = listOf(MenuSection("steam", L("Steam connection"), Icons.Filled.Public, tests + regions)),
        onBack = onBack,
    )
    help?.let { (t, b) -> LabSetInfo(t, b) { help = null } }
}

// ───────────────────────────── Steam library locations ─────────────────────────────

/**
 * The Steam library locations manager (Classic Settings › Steam and the Steam store's menu): add a folder, make one the
 * default, remove one (only forgets it; nothing on the disk is deleted), each with its free space and games.
 */
@Composable
internal fun LabSteamLibrariesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<SteamLibraryLocations.Entry>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var intro by remember { mutableStateOf(false) }
    // describe() walks the volume list, StatFs and two DB reads: off the main thread.
    LaunchedEffect(tick) { entries = withContext(Dispatchers.IO) { SteamLibraryLocations.describe(context) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data) else null
        if (path != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { SteamLibraryLocations.add(context, path) }
                if (r is SteamLibraryLocations.AddResult.Added) {
                    // Games another Steam client already installed in its steamapps are picked up right away.
                    val found = withContext(Dispatchers.IO) { SteamLibraryImport.importInstalled(context) }
                    val added = if (found > 0) context.getString(R.string.steam_lib_added_found, r.library.label, found)
                    else context.getString(R.string.steam_lib_added, r.library.label)
                    message = if (r.lowSpace) added + " " + context.getString(R.string.steam_lib_low_space) else added
                    tick++
                } else if (r is SteamLibraryLocations.AddResult.Rejected) {
                    message = r.reason
                }
            }
        }
    }
    fun remove(loc: SteamLibraryLocations.Location) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { SteamLibraryLocations.remove(context, loc.id) }
            if (r is SteamLibraryLocations.RemoveResult.Removed) {
                message = context.getString(R.string.steam_lib_removed, r.library.label)
            } else if (r is SteamLibraryLocations.RemoveResult.Blocked) {
                message = context.getString(R.string.steam_lib_remove_blocked, r.library.label, r.games.joinToString(", "))
            }
            tick++
        }
    }
    val rows = entries.orEmpty().map { entry ->
        val loc = entry.location
        val free = if (loc.available) context.getString(R.string.steam_lib_free, SteamSdInstall.fmtBytes(loc.freeBytes))
        else context.getString(R.string.steam_lib_unavailable)
        val games = if (entry.games.isEmpty()) context.getString(R.string.steam_lib_games_none)
        else context.getString(R.string.steam_lib_games_list, entry.games.take(3).joinToString(", ") + if (entry.games.size > 3) " +${entry.games.size - 3}" else "")
        val canDefault = !entry.isDefault && loc.available
        val canRemove = loc.kind == SteamLibraryLocations.Kind.LIBRARY
        MenuEntry(
            "loc:${loc.id}", loc.label,
            subtitle = locationDescription(loc) + " · " + free + " · " + games,
            trailing = if (entry.isDefault) context.getString(R.string.steam_lib_default_badge) else null,
            current = entry.isDefault,
            icon = if (loc.kind == SteamLibraryLocations.Kind.INTERNAL) Icons.Filled.Storage else Icons.Filled.SdCard,
            actions = listOfNotNull(
                if (canDefault) MenuAction(context.getString(R.string.steam_lib_make_default), {
                    SteamLibraryLocations.setDefault(context, loc.id)
                    tick++
                }) else null,
                if (canRemove) MenuAction(context.getString(R.string.steam_lib_remove), { remove(loc) }, danger = true,
                    confirm = context.getString(R.string.steam_lib_remove_confirm_body, loc.label)) else null,
            ),
        )
    } + MenuEntry("about", L("About library locations"), icon = Icons.Filled.HelpOutline, onA = { intro = true })
    SimpleMenuPage(
        overline = L("Settings"),
        title = context.getString(R.string.steam_lib_title),
        banner = message,
        sections = listOf(MenuSection(
            "libs", context.getString(R.string.steam_lib_title), Icons.Filled.Folder,
            if (entries == null) emptyList() else rows,
            empty = context.getString(R.string.steam_lib_loading),
            primary = MenuAction(context.getString(R.string.steam_lib_add), {
                pick.launch(InAppFilePicker.buildDirIntent(context, context.getString(R.string.steam_lib_pick_title)))
            }),
        )),
        onBack = onBack,
    )
    if (intro) LabSetInfo(context.getString(R.string.steam_lib_title), context.getString(R.string.steam_lib_intro)) { intro = false }
}

// ───────────────────────────── Frame-gen training capture ─────────────────────────────

private val CAPTURE_GUIDE = listOf(
    "What this does:" to "records raw in-game frames while you play, to help train an open, clean frame-generation model. Off by default; it lowers FPS while recording. Files save to Download/win-fg for you to share.",
    "Best games to record:" to "ones with smooth, CONTINUOUS motion: 3D games, racing, action, anything with camera movement. These teach real interpolation.",
    "Games to avoid:" to "menu-heavy screens, 2D games where sprites snap or teleport, and heavy particle-storm or rapid-flashing effects (un-interpolatable noise).",
    "Cap your FPS" to "to a stable value (for example 30 or 60) so frame timing is even: this makes much cleaner training data.",
    "Resolution:" to "\"Match game\" is best (records at your actual play resolution); pick 720p or 1080p to force one. A mix across contributors is ideal.",
    "Variety helps more than length:" to "a few minutes each across several different games beats a long session of one.",
    "Privacy:" to "only the game's rendered frames are captured: NO HUD or overlay, NO personal info, NO account data, NO audio. The record is anonymous.",
)

/** Classic Settings › Developer: frame-gen training capture (consent first), its resolution, extra logging, the diagnostic log. */
@Composable
internal fun LabCapturePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(WinFgCapture.isEnabled(context)) }
    var res by remember { mutableStateOf(WinFgCapture.captureRes(context)) }
    var extra by remember { mutableStateOf(WinFgDiag.isExtraLoggingEnabled(context)) }
    var recording by remember { mutableStateOf(WinFgDiag.isRecording()) }
    var logPath by remember { mutableStateOf<String?>(WinFgDiag.currentFile()?.absolutePath) }
    var consent by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    val resolutions = listOf(
        WinFgCapture.RES_MATCH to L("Match game (native)"),
        WinFgCapture.RES_720P to "720p (1280×720)",
        WinFgCapture.RES_1080P to "1080p (1920×1080)",
    )
    fun toggleDiag() {
        if (recording) {
            WinFgDiag.stopDiagLog(context)
            recording = false
            val p = logPath
            toast(context, if (p != null) L("Diagnostic log saved: %1\$s", p) else L("Diagnostic log stopped"))
        } else {
            val f = WinFgDiag.startDiagLog(context)
            if (f != null) {
                recording = true
                logPath = f.absolutePath
                toast(context, L("Recording diagnostic log: reproduce the freeze, then stop"))
            } else toast(context, L("Could not start the diagnostic log (storage permission?)"))
        }
    }
    val entries = listOf(
        MenuEntry(
            "capture", L("Contribute frame-gen training capture"),
            subtitle = if (enabled) L("Recording is ON: files are saved to Download/win-fg. Only records while a game's frame generation engine is win-fg.")
            else L("Records raw in-game frames (no HUD, no personal data, no audio) to Download/win-fg for you to share. It lowers FPS while recording."),
            icon = Icons.Filled.Movie, toggle = enabled,
            onA = {
                // Consent is mandatory: turning it on always goes through the consent pop-up.
                if (!enabled) consent = true
                else {
                    WinFgCapture.disable(context)
                    enabled = false
                    toast(context, L("Frame-gen training capture off"))
                }
            },
        ),
        MenuEntry(
            "res", L("Capture resolution"), L("Match game records at your play resolution (best data); 720p / 1080p force a fixed size."),
            trailing = resolutions.firstOrNull { it.first == res }?.second, icon = Icons.Filled.Tune,
            actions = resolutions.map { (v, l) -> MenuAction(labSetMark(l, v == res), { res = v; WinFgCapture.setCaptureRes(context, v) }) },
        ),
        MenuEntry(
            "extra", L("Extra win-fg logging (verbose)"),
            L("For debugging a win-fg freeze: turn this and the diagnostic log on, reproduce the freeze, then share the saved log."),
            group = L("Debug a win-fg freeze"), toggle = extra,
            onA = {
                extra = !extra
                WinFgDiag.setExtraLoggingEnabled(context, extra)
                toast(context, if (extra) L("Extra win-fg logging on") else L("Extra win-fg logging off"))
            },
        ),
        MenuEntry(
            "diag", if (recording) L("Stop diagnostic log") else L("Start diagnostic log"),
            subtitle = when {
                recording -> L("Recording: reproduce the freeze, then stop.")
                logPath != null -> L("Saved to %1\$s", logPath.orEmpty())
                else -> L("Records this app's log to Download/win-fg-logs while you play. No root needed.")
            },
            icon = Icons.Filled.Description, group = L("Debug a win-fg freeze"), onA = { toggleDiag() },
        ),
        MenuEntry("guide", L("How to record good training data"), L("What to record, what to avoid and what is captured."),
            icon = Icons.Filled.HelpOutline, group = L("Debug a win-fg freeze"), onA = { guide = true }),
    )
    SimpleMenuPage(
        overline = L("Settings"),
        title = L("Frame-gen training capture"),
        sections = listOf(MenuSection("capture", L("Frame-gen training capture"), Icons.Filled.Movie, entries)),
        onBack = onBack,
    )
    if (consent) {
        LabSetGate(
            title = context.getString(R.string.winfg_capture_consent_title),
            body = context.getString(R.string.winfg_capture_consent_v1),
            checkLabel = L("I understand"),
            confirmLabel = L("Enable"),
            requireScroll = false,
            danger = false,
            onConfirm = {
                WinFgCapture.recordConsentAndEnable(context)
                enabled = true
                consent = false
                toast(context, L("Frame-gen training capture enabled"))
            },
            onDismiss = { consent = false },
        )
    }
    if (guide) {
        LabSetInfo(L("How to record good training data"), CAPTURE_GUIDE.joinToString("\n\n") { (lead, body) -> "• " + L(lead) + " " + L(body) }) { guide = false }
    }
}
