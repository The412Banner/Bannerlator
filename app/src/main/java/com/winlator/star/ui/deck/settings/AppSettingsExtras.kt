package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.preference.PreferenceManager
import com.winlator.star.MainActivity
import com.winlator.star.R
import com.winlator.star.SettingsFragment
import com.winlator.star.contentdialog.ContentDialog
import com.winlator.star.core.AppUtils
import com.winlator.star.core.FileUtils
import com.winlator.star.core.PreloaderDialog
import com.winlator.star.core.UpdateManager
import com.winlator.star.core.WinFgCapture
import com.winlator.star.core.WinFgDiag
import com.winlator.star.midi.MidiManager
import com.winlator.star.store.NetworkProbe
import com.winlator.star.store.SteamLibraryManagerDialog
import com.winlator.star.store.SteamRegion
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.HelpSupportDialog
import com.winlator.star.ui.StorageWidget
import com.winlator.star.ui.components.ColorPicker
import com.winlator.star.ui.deck.DeckSetup
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.PerformanceSettingsScreen
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.xenvironment.ImageFsInstaller
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// The page items (not rows) of the XMB Settings tab (the plain Deck and Lab have none of these): everything the Classic Settings screen did with a button, a picker or a
// dialog, ported as real in-page items. The registry's rows can't be these (they are a control and a stored value), so they come
// as page extras, keyed by TAB id (AppSettingsLayout feeds each source to the tab of its own key):
//   General      Version (installed / latest, Check for updates, Download and install), About and help (About, Help, My account, Run setup again)
//   Appearance   (top) the Theme preset line, which opens a popup grid of themes with their colour squares; (bottom) the custom accent colour picker
//   Library      (top, the first item of the tab) Steam library locations; (bottom) Steam connection tests (regions, network)
//   Performance  Root controls and heat safety (the Performance screen in a dialog), Lossless.dll for LSFG Native
//   Storage      Space used, Folders (app data, shortcut export, reinstall the base system), Sound banks, Backup and restore
//   Advanced     Frame-gen training capture (consent dialog, resolution, extra logging, diagnostic log)
// Nothing about logs is here (what is recorded, where it goes and how long it is kept are in Tools > Logs). The plain Deck keeps
// the logs rows and the Wine channel chooser in its own Settings > Storage & data, which does not use this file.
// Every action writes the same preference / runs the same code as the classic screen, at once. The dialogs and the activity-result
// launchers live in [Dialogs], which DeckAppSettings composes beside the editor (a dialog inside a scrolling item would close with it).

/** One line of a card of page items. */
private class AppLine(val key: String, val content: @Composable () -> Unit)

private fun actionLine(a: DeckAction) = AppLine(a.key) { DeckActionRow(a) }

private fun infoLine(key: String, label: String, hint: String?) = AppLine(key) { DeckLine(label = label, hint = hint) }

/** [lines] as page items in one rounded card under [label] (like [actionItems], for lines that are not all actions). */
private fun cardItems(prefix: String, label: String?, lines: List<AppLine>): List<PageExtra> {
    if (lines.isEmpty()) return emptyList()
    return listOfNotNull(label?.let { l -> PageExtra("$prefix-label") { GroupLabel(l) } }) +
        lines.mapIndexed { i, line ->
            PageExtra("$prefix:${line.key}") {
                GroupCard(first = i == 0, last = i == lines.lastIndex, highlight = false) { line.content() }
            }
        }
}

private fun activityOf(context: Context): Activity? {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

internal class AppSettingsExtras(
    private val context: Context,
    private val onAbout: () -> Unit,
    /** Null hides the My account line (the host has no account page to open). */
    private val onMyAccount: (() -> Unit)?,
) : DeckSection {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val activity: Activity? = activityOf(context)
    private val lsfgDllFile = File(context.filesDir, "lsfg-vk/Lossless.dll")
    private val lsfgShaderBuilding = AtomicBoolean(false)

    // Version
    private var updateInfo by mutableStateOf<UpdateManager.UpdateInfo?>(null)
    private var checking by mutableStateOf(false)
    // Dialogs
    private var showHelp by mutableStateOf(false)
    private var showPerf by mutableStateOf(false)
    private var showSteamLibraries by mutableStateOf(false)
    private var showThemes by mutableStateOf(false)
    private var showBackup by mutableStateOf(false)
    private var backingUp by mutableStateOf(false)
    private var restoreUri by mutableStateOf<Uri?>(null)
    private var showCaptureConsent by mutableStateOf(false)
    private var showCaptureHelp by mutableStateOf(false)
    // Steam connection tests
    private var regionProbe by mutableStateOf<Map<String, Long>>(emptyMap())
    private var regionProbing by mutableStateOf(false)
    private var netResult by mutableStateOf<NetworkProbe.Result?>(NetworkProbe.cached())
    private var netProbing by mutableStateOf(false)
    // Folders and sound banks
    private var dataPath by mutableStateOf(readTreePath("winlator_path_uri", SettingsFragment.DEFAULT_WINLATOR_PATH))
    private var exportPath by mutableStateOf(readTreePath("shortcuts_export_path_uri", SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH))
    private var soundBanks by mutableStateOf(readSoundBanks())
    // Lossless.dll
    private var lsfgStatus by mutableStateOf(lsfgStatusText())
    private var lsfgShaderStatus by mutableStateOf("")
    // Frame-gen training capture
    private var captureEnabled by mutableStateOf(WinFgCapture.isEnabled(context))
    private var captureRes by mutableStateOf(WinFgCapture.captureRes(context))
    private var extraLogging by mutableStateOf(WinFgDiag.isExtraLoggingEnabled(context))
    private var diagRecording by mutableStateOf(WinFgDiag.isRecording())
    private var diagLogPath by mutableStateOf<String?>(WinFgDiag.currentFile()?.absolutePath)

    // Pickers: set by [Dialogs], which owns the activity-result launchers. A pick calls back the lambda given when it was started.
    private var onInAppResult: ((Intent?) -> Unit)? = null
    private var onDocResult: ((Uri) -> Unit)? = null
    private var onTreeResult: ((Uri) -> Unit)? = null
    private var startInApp: (Intent, (Intent?) -> Unit) -> Unit = { _, _ -> }
    private var startDoc: ((Uri) -> Unit) -> Unit = { }
    private var startTree: ((Uri) -> Unit) -> Unit = { }

    private fun onUi(block: () -> Unit) {
        val a = activity
        if (a != null) a.runOnUiThread { block() } else block()
    }

    private fun toast(text: String, long: Boolean = false) {
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun extras(): Map<String, CategoryExtra> = mapOf(
        EditorTabs.APP_GENERAL to CategoryExtra(bottom = { generalBottom() }),
        EditorTabs.APP_APPEARANCE to CategoryExtra(top = { appearanceTop() }, bottom = { appearanceBottom() }),
        EditorTabs.APP_LIBRARY to CategoryExtra(top = { libraryTop() }, bottom = { libraryBottom() }, level = SettingLevel.ADVANCED),
        EditorTabs.APP_PERFORMANCE to CategoryExtra(bottom = { performanceBottom() }, level = SettingLevel.ADVANCED),
        EditorTabs.APP_STORAGE to CategoryExtra(top = { storageTop() }),
        EditorTabs.APP_ADVANCED to CategoryExtra(bottom = { advancedBottom() }, level = SettingLevel.ADVANCED),
    )

    // ── General ─────────────────────────────────────────────────────────────────────────────────

    private fun updateHint(latest: UpdateManager.UpdateInfo?): String? = when {
        latest == null -> null
        latest.isNewer -> buildString {
            append(L("Update available"))
            if (latest.highlights.isNotEmpty()) {
                latest.highlights.forEach { h ->
                    append("\n• ")
                    if (h.title.isNotEmpty()) append(h.title)
                    if (h.title.isNotEmpty() && h.text.isNotEmpty()) append(": ")
                    append(h.text)
                }
            } else if (latest.notes.isNotBlank()) {
                append("\n")
                append(latest.notes)
            }
        }
        else -> L("You're up to date.")
    }

    private fun checkNow() {
        checking = true
        UpdateManager.check(context, force = true) { info ->
            onUi {
                updateInfo = info
                checking = false
                when {
                    info == null -> AppUtils.showToast(context, L("Couldn't check for updates"))
                    !info.isNewer -> AppUtils.showToast(context, L("You're on the latest version"))
                    else -> {}
                }
            }
        }
    }

    private fun generalBottom(): List<PageExtra> {
        val latest = updateInfo
        val lines = ArrayList<AppLine>()
        lines += infoLine("status", "Installed: V " + UpdateManager.installedVersionName() + (latest?.let { "   ·   Latest: V " + it.versionName } ?: ""), updateHint(latest))
        if (latest != null && latest.isNewer) {
            lines += actionLine(DeckAction("install", Icons.Filled.Download, L("Download and install") + " V " + latest.versionName,
                L("Downloads the new version and starts the installer."), enabled = activity != null) {
                activity?.let { UpdateManager.downloadAndInstall(it, latest) {} }
            })
        }
        lines += actionLine(DeckAction("check", Icons.Filled.Refresh, if (checking) L("Checking...") else L("Check for updates"),
            L("Looks for a new version now."), enabled = !checking) { checkNow() })
        val about = ArrayList<DeckAction>()
        about += DeckAction("about", Icons.Filled.Info, L("About Bannerlator"), L("Version, what's powering it and credits.")) { onAbout() }
        about += DeckAction("help", Icons.Filled.HelpOutline, L("Help and support"), L("GitHub, issue tracker and Discord.")) { showHelp = true }
        if (onMyAccount != null) {
            about += DeckAction("account", Icons.Filled.AccountCircle, L("My account"), L("Sign in to manage your shared configs.")) { onMyAccount.invoke() }
        }
        about += DeckAction("setup", Icons.Filled.RestartAlt, L("Run setup again"), L("Walk through the first-run set-up: permissions, theme, stores.")) { DeckSetup.requestRerun() }
        return cardItems("version", L("Version"), lines) + actionItems("about", L("About and help"), about)
    }

    // ── Appearance ──────────────────────────────────────────────────────────────────────────────

    private fun appearanceTop(): List<PageExtra> =
        cardItems("theme", L("Theme"), listOf(AppLine("preset") { ThemePresetLine(onOpen = { showThemes = true }) }))

    private fun appearanceBottom(): List<PageExtra> = listOf(
        PageExtra("accent-label") { GroupLabel(L("Custom accent colour")) },
        PageExtra("accent-picker") { GroupCard(first = true, last = true, highlight = false) { AccentPicker() } },
    )

    // ── Games & library ─────────────────────────────────────────────────────────────────────────

    private fun regionHint(): String {
        val reachable = regionProbe.entries.filter { it.value >= 0 }.sortedBy { it.value }
        return when {
            regionProbing -> L("Pinging every Steam datacenter...")
            regionProbe.isEmpty() -> L("Pings every Steam datacenter now. Auto remembers the fastest.")
            reachable.isEmpty() -> L("No datacenter answered.")
            else -> reachable.take(4).joinToString(", ") { SteamRegion.nameOf(it.key) + " " + it.value + " ms" }
        }
    }

    private fun networkHint(): String {
        val net = netResult
        return when {
            netProbing -> L("Checking how this network handles online game traffic...")
            net == null -> L("Checks the NAT type and UDP for online games (the same check the SteamLite launch shows).")
            else -> "Network: " + net.verdict() +
                (if (net.nat != NetworkProbe.Nat.OPEN_CONE && net.maskedIp != null) " (IP ${net.maskedIp})" else "") +
                (if (net.ipv6) " · IPv6" else "")
        }
    }

    private fun testRegions() {
        regionProbing = true
        Thread({
            val res = try { SteamRegion.probeAll(context) } catch (_: Throwable) { emptyList() }
            onUi {
                regionProbe = res.associate { it.dc to it.ms }
                regionProbing = false
            }
        }, "steam-region-probe-ui").start()
    }

    private fun testNetwork() {
        netProbing = true
        Thread({
            val res = NetworkProbe.probe()   // never throws; about 2.5 s at most
            onUi {
                netResult = res
                netProbing = false
            }
        }, "steam-net-probe-ui").start()
    }

    // The Steam library locations are the first item of the tab (above every row).
    private fun libraryTop(): List<PageExtra> = actionItems("steam-lib", L("Steam library"), listOf(
        DeckAction("libraries", Icons.Filled.Folder, context.getString(R.string.steam_lib_title), context.getString(R.string.steam_lib_settings_subtitle)) { showSteamLibraries = true },
    ))

    // The locations are in [libraryTop].
    private fun libraryBottom(): List<PageExtra> = actionItems("steam-conn", L("Steam connection tests"), listOf(
        DeckAction("regions", Icons.Filled.Refresh, if (regionProbing) L("Testing regions...") else L("Test regions"), regionHint(), enabled = !regionProbing) { testRegions() },
        DeckAction("network", Icons.Filled.Speed, if (netProbing) L("Testing network...") else L("Test network"), networkHint(), enabled = !netProbing) { testNetwork() },
    ))

    // ── Performance ─────────────────────────────────────────────────────────────────────────────

    private fun lsfgReady() = lsfgDllFile.isFile && lsfgDllFile.length() > 0

    private fun lsfgStatusText(): String {
        if (!lsfgReady()) return L("Not set: LSFG Native stays off")
        val mb = lsfgDllFile.length() / (1024 * 1024)
        return when (prefs.getString("lsfg_dll_source", null)) {
            "store" -> L("Imported from the Steam store (Lossless Scaling)") + " · " + mb + " MB"
            "manual" -> L("Imported manually") + " · " + mb + " MB"
            else -> L("Imported") + " · " + mb + " MB"
        }
    }

    // LSFG Native translates the DLL's shader chain to SPIR-V once and caches it; doing it here, right after the import, puts the
    // wait where the user just acted (and gives it a status line) instead of the first game launch.
    private fun buildLsfgShaderCache() {
        if (!lsfgReady()) { lsfgShaderStatus = ""; return }
        if (!lsfgShaderBuilding.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        lsfgShaderStatus = L("Preparing shaders for LSFG Native (one time, can take a minute)")
        Thread({
            val started = System.currentTimeMillis()
            val status = try {
                com.winlator.star.core.LsfgNative.ensureCache(appCtx, false)
            } catch (e: Throwable) { -1 }
            val took = (System.currentTimeMillis() - started) / 1000
            val text = when (status) {
                com.winlator.star.core.LsfgNative.STATUS_OK ->
                    if (took >= 2) L("Shaders ready for LSFG Native") + " (" + took + " s)" else L("Shaders ready for LSFG Native")
                -1 -> L("Shader preparation failed; it is retried when a game launches")
                else -> com.winlator.star.core.LsfgNative.explain(status)
            }
            onUi {
                lsfgShaderStatus = text
                lsfgShaderBuilding.set(false)
            }
        }, "lsfg-native-cache").start()
    }

    private fun importLosslessDll(uri: Uri) {
        try {
            lsfgDllFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                lsfgDllFile.outputStream().use { output -> input.copyTo(output) }
            }
            prefs.edit().putString("lsfg_dll_source", "manual").apply()
            lsfgStatus = lsfgStatusText()
            buildLsfgShaderCache()
        } catch (e: Exception) {
            lsfgStatus = L("Import failed") + ": " + e.message
        }
    }

    // A Lossless.dll from a Steam-store install (Lossless Scaling, appId 993090, lands under imagefs/steam_games/<name>). The newest
    // by lastModified wins, so a re-download of an updated Lossless Scaling is picked up.
    private fun findStoreLosslessDll(): File? {
        val root = File(context.filesDir, "imagefs/steam_games")
        if (!root.isDirectory) return null
        return root.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.name.equals("Lossless.dll", ignoreCase = true) && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
    }

    private fun detectLosslessDll() {
        val src = findStoreLosslessDll()
        if (src == null) {
            toast(L("No Steam-store Lossless Scaling found: download it from the store or import it manually."), long = true)
            return
        }
        try {
            lsfgDllFile.parentFile?.mkdirs()
            src.inputStream().use { input -> lsfgDllFile.outputStream().use { output -> input.copyTo(output) } }
            prefs.edit().putString("lsfg_dll_source", "store").apply()
            lsfgStatus = lsfgStatusText()
            buildLsfgShaderCache()
            toast(L("Lossless.dll set from the Steam store install."))
        } catch (e: Exception) {
            lsfgStatus = L("Detect failed") + ": " + e.message
            toast(L("Detect failed") + ": " + e.message, long = true)
        }
    }

    private fun removeLosslessDll() {
        lsfgDllFile.delete()
        com.winlator.star.core.LsfgNative.cacheFile(context).delete()
        prefs.edit().remove("lsfg_dll_source").apply()
        lsfgStatus = lsfgStatusText()
        lsfgShaderStatus = ""
    }

    private fun lsfgLines(): List<AppLine> {
        val lines = ArrayList<AppLine>()
        val shaders = lsfgShaderStatus
        lines += infoLine("status", L("Lossless.dll"), lsfgStatus + (if (shaders.isNotEmpty()) "\n" + shaders else ""))
        lines += actionLine(DeckAction("detect", Icons.Filled.Download, L("Detect from the Steam store"),
            L("Download Lossless Scaling in the in-app Steam store first. The DLL is copied into the app and used by every container whose frame generation engine is LSFG Native.")) { detectLosslessDll() })
        lines += actionLine(DeckAction("import", Icons.Filled.FileDownload, L("Import Lossless.dll"),
            L("Pick a Lossless.dll yourself (in-app file picker).")) {
            startInApp(InAppFilePicker.buildIntent(context, InAppFilePicker.DLL, L("Select Lossless.dll"))) { data ->
                InAppFilePicker.pickedUri(data)?.let { importLosslessDll(it) }
            }
        })
        lines += actionLine(DeckAction("import-system", Icons.Filled.FileDownload, L("Import Lossless.dll with the system picker"),
            L("The same, with Android's own file picker.")) {
            startDoc { uri -> importLosslessDll(uri) }
        })
        if (lsfgDllFile.isFile) {
            lines += actionLine(DeckAction("remove", Icons.Filled.Delete, L("Remove Lossless.dll"),
                L("Deletes the copy and its shader cache; LSFG Native stays off.")) { removeLosslessDll() })
        }
        return lines
    }

    private fun performanceBottom(): List<PageExtra> =
        actionItems("perf-root", L("Root and heat"), listOf(
            DeckAction("root", Icons.Filled.Speed, L("Root controls and heat safety"),
                L("CPU governor and frequency locks, free memory and the temperature watchdog.")) { showPerf = true },
        )) + cardItems("lsfg", L("Frame generation: LSFG Native"), lsfgLines())

    // ── Storage & data ──────────────────────────────────────────────────────────────────────────

    private fun readTreePath(key: String, default: String): String {
        val uriStr = prefs.getString(key, null)
        return if (uriStr != null) (FileUtils.getFilePathFromUri(context, Uri.parse(uriStr)) ?: uriStr) else default
    }

    private fun pickTree(key: String, then: () -> Unit) {
        startTree { uri ->
            prefs.edit().putString(key, uri.toString()).apply()
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
            then()
        }
    }

    private fun readSoundBanks(): List<String> {
        val names = ArrayList<String>()
        names.add(MidiManager.DEFAULT_SF2_FILE)
        val files = MidiManager.getSoundFontDir(context).listFiles()
        if (files != null) {
            for (f in files.sortedBy { it.name }) {
                if (f.name.endsWith(".sf2") && f.name != MidiManager.DEFAULT_SF2_FILE) names.add(f.name)
            }
        }
        return names
    }

    private fun installSoundBank(uri: Uri) {
        val act = activity ?: return
        val dialog = PreloaderDialog(act)
        dialog.showOnUiThread(R.string.installing_content)
        MidiManager.installSF2File(context, uri, object : MidiManager.OnSoundFontInstalledCallback {
            override fun onSuccess() {
                dialog.closeOnUiThread()
                act.runOnUiThread {
                    ContentDialog.alert(context, R.string.sound_font_installed_success, null)
                    soundBanks = readSoundBanks()
                }
            }
            override fun onFailed(reason: Int) {
                dialog.closeOnUiThread()
                val resId = when (reason) {
                    MidiManager.ERROR_BADFORMAT -> R.string.sound_font_bad_format
                    MidiManager.ERROR_EXIST -> R.string.sound_font_already_exist
                    else -> R.string.sound_font_installed_failed
                }
                act.runOnUiThread { ContentDialog.alert(context, resId, null) }
            }
        })
    }

    private fun removeSoundBank(name: String) {
        if (name.isEmpty() || name == MidiManager.DEFAULT_SF2_FILE) {
            AppUtils.showToast(context, R.string.cannot_remove_default_sound_font)
            return
        }
        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_sound_font) {
            if (MidiManager.removeSF2File(context, name)) {
                AppUtils.showToast(context, R.string.sound_font_removed_success)
                soundBanks = readSoundBanks()
            } else AppUtils.showToast(context, R.string.sound_font_removed_failed)
        }
    }

    private fun runBackup() {
        showBackup = false
        backingUp = true
        val ctx = context
        Thread({
            val dataDir = ctx.filesDir.parentFile
            val backupFile = File(Environment.getExternalStorageDirectory(), "app_data_backup.tar")
            try {
                com.winlator.star.core.TarCompressorUtils.archive(
                    arrayOf(dataDir), backupFile
                ) { file -> !file.absolutePath.contains("imagefs/tmp/.sysvshm") }
                onUi {
                    backingUp = false
                    AppUtils.showToast(ctx, "Backup completed: ${backupFile.path}")
                }
            } catch (_: Exception) {
                onUi {
                    backingUp = false
                    AppUtils.showToast(ctx, "Backup failed.")
                }
            }
        }, "app-data-backup").start()
    }

    private fun storageTop(): List<PageExtra> {
        val soundLines = ArrayList<AppLine>()
        for (name in soundBanks) {
            val builtIn = name == MidiManager.DEFAULT_SF2_FILE
            soundLines += AppLine("sf:$name") {
                if (builtIn) DeckLine(label = L("Built-in General MIDI bank"), hint = L("Always available; it cannot be removed."))
                else DeckLine(label = name, trailing = { DeckSmallButton(L("Remove"), Icons.Filled.Delete) { removeSoundBank(name) } })
            }
        }
        soundLines += actionLine(DeckAction("add", Icons.Filled.Add, L("Add a sound bank (.sf2)"),
            L("Containers pick their MIDI sound bank under Audio. In-app file picker.")) {
            startInApp(InAppFilePicker.buildIntent(context, InAppFilePicker.SF2, L("Select SoundFont"))) { data ->
                InAppFilePicker.pickedUri(data)?.let { installSoundBank(it) }
            }
        })
        soundLines += actionLine(DeckAction("add-system", Icons.Filled.Add, L("Add a sound bank with the system picker"),
            L("The same, with Android's own file picker.")) {
            startDoc { uri -> installSoundBank(uri) }
        })

        val folders = buildList<DeckAction> {
            add(DeckAction("data", Icons.Filled.Folder, L("App data folder"), dataPath) { pickTree("winlator_path_uri") { dataPath = readTreePath("winlator_path_uri", SettingsFragment.DEFAULT_WINLATOR_PATH) } })
            add(DeckAction("export", Icons.Filled.Folder, L("Shortcut export folder"), exportPath) { pickTree("shortcuts_export_path_uri") { exportPath = readTreePath("shortcuts_export_path_uri", SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH) } })
            add(DeckAction("imagefs", Icons.Filled.Storage, L("Reinstall the base system (ImageFS)"),
                L("Restores the system files. Your containers are not changed.")) {
                ContentDialog.confirm(context, R.string.do_you_want_to_reinstall_imagefs) {
                    (activity as? MainActivity)?.let { ImageFsInstaller.installFromAssets(it) }
                }
            })
        }

        val backup = listOf(
            DeckAction("backup", Icons.Filled.FileUpload, L("Back up app data"),
                L("Containers, settings and prefix in one file (app_data_backup.tar in your storage root).")) { showBackup = true },
            DeckAction("restore", Icons.Filled.Archive, L("Restore app data"),
                L("Pick a backup with the in-app file picker. The app restarts.")) {
                startInApp(InAppFilePicker.buildIntent(context, InAppFilePicker.SAVE, L("Select backup"))) { data ->
                    InAppFilePicker.pickedUri(data)?.let { restoreUri = it }
                }
            },
            DeckAction("restore-system", Icons.Filled.Archive, L("Restore app data with the system picker"),
                L("The same, with Android's own file picker.")) {
                startDoc { uri -> restoreUri = uri }
            },
        )

        return listOf(
            PageExtra("space-label") { GroupLabel(L("Space used")) },
            PageExtra("space") { Column { StorageWidget() } },
        ) + actionItems("folders", L("Folders"), folders) +
            cardItems("banks", L("Sound banks"), soundLines) +
            actionItems("backup", L("Backup and restore"), backup)
    }

    // ── Advanced: frame-gen training capture ───────────────────────────────────────────────────

    private fun setCapture(want: Boolean) {
        if (want) {
            // Consent is mandatory: never enable directly, open the consent dialog.
            showCaptureConsent = true
        } else {
            WinFgCapture.disable(context)
            captureEnabled = false
            AppUtils.showToast(context, L("Frame-gen training capture off"))
        }
    }

    private fun applyExtraLogging(on: Boolean) {
        extraLogging = on
        WinFgDiag.setExtraLoggingEnabled(context, on)
        AppUtils.showToast(context, if (on) L("Extra win-fg logging on") else L("Extra win-fg logging off"))
    }

    private fun toggleDiagLog() {
        if (diagRecording) {
            WinFgDiag.stopDiagLog(context)
            diagRecording = false
            val p = diagLogPath
            AppUtils.showToast(context, if (p != null) L("Diagnostic log saved") + ": " + p else L("Diagnostic log stopped"))
        } else {
            val f = WinFgDiag.startDiagLog(context)
            if (f != null) {
                diagRecording = true
                diagLogPath = f.absolutePath
                AppUtils.showToast(context, L("Recording diagnostic log: reproduce the freeze, then stop"))
            } else {
                AppUtils.showToast(context, L("Could not start the diagnostic log (storage permission?)"))
            }
        }
    }

    private fun advancedBottom(): List<PageExtra> {
        val resolutions = listOf(
            WinFgCapture.RES_MATCH to L("Match game"),
            WinFgCapture.RES_720P to "720p",
            WinFgCapture.RES_1080P to "1080p",
        )
        val lines = ArrayList<AppLine>()
        lines += AppLine("capture") {
            DeckLine(
                label = L("Contribute frame-gen training capture"),
                hint = if (captureEnabled) L("Recording is ON: files are saved to Download/win-fg. Only records while a game's frame generation engine is win-fg. It lowers FPS while recording.")
                else L("Records raw in-game frames (no HUD, no personal data, no audio) to Download/win-fg for you to share. Turning it on asks you to confirm first."),
                activate = { setCapture(!captureEnabled) },
                trailing = { Switch(checked = captureEnabled, onCheckedChange = { setCapture(it) }) },
            )
        }
        lines += AppLine("resolution") {
            DeckLine(
                label = L("Capture resolution"),
                hint = L("Match game records at your play resolution (best data); 720p / 1080p force a fixed size."),
                trailing = {
                    resolutions.forEach { (value, label) ->
                        DeckSmallButton(label, if (captureRes == value) Icons.Filled.Check else null) {
                            captureRes = value
                            WinFgCapture.setCaptureRes(context, value)
                        }
                    }
                },
            )
        }
        lines += AppLine("extra") {
            DeckLine(
                label = L("Extra win-fg logging (verbose)"),
                hint = L("For debugging a win-fg freeze: turn this and the diagnostic log on, reproduce the freeze, then share the saved log."),
                activate = { applyExtraLogging(!extraLogging) },
                trailing = { Switch(checked = extraLogging, onCheckedChange = { applyExtraLogging(it) }) },
            )
        }
        lines += actionLine(DeckAction("diag", Icons.Filled.Description,
            if (diagRecording) L("Stop diagnostic log") else L("Start diagnostic log"),
            if (diagRecording) L("Recording: reproduce the freeze, then stop.")
            else if (diagLogPath != null) L("Last saved to") + " " + diagLogPath
            else L("Records this app's log to Download/win-fg-logs while you play. No root needed.")) { toggleDiagLog() })
        lines += actionLine(DeckAction("guide", Icons.Filled.HelpOutline, L("How to record good training data"),
            L("What to record, what to avoid and what is captured.")) { showCaptureHelp = true })
        return cardItems("fg", L("Frame-gen training capture"), lines)
    }

    // ── Dialogs and pickers ─────────────────────────────────────────────────────────────────────

    @Composable
    override fun Dialogs() {
        val inApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val done = onInAppResult
            onInAppResult = null
            if (result.resultCode == Activity.RESULT_OK) done?.invoke(result.data)
        }
        val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val done = onDocResult
            onDocResult = null
            if (uri != null) done?.invoke(uri)
        }
        val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val done = onTreeResult
            onTreeResult = null
            if (uri != null) done?.invoke(uri)
        }
        startInApp = { intent, done ->
            onInAppResult = done
            inApp.launch(intent)
        }
        startDoc = { done ->
            onDocResult = done
            doc.launch(arrayOf("*/*"))
        }
        startTree = { done ->
            onTreeResult = done
            tree.launch(null)
        }

        // The version line and the shader cache of an imported Lossless.dll, once when Settings opens (as the classic screen did).
        LaunchedEffect(Unit) {
            UpdateManager.check(context) { info -> onUi { updateInfo = info } }
            buildLsfgShaderCache()
        }

        if (showHelp) {
            HelpSupportDialog(
                onDismiss = { showHelp = false },
                onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            )
        }
        if (showPerf) {
            Dialog(onDismissRequest = { showPerf = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                DeckDialogBars()
                DeckDialogScaled {
                    PerformanceSettingsScreen(onClose = { showPerf = false })
                }
            }
        }
        if (showSteamLibraries) SteamLibraryManagerDialog(onDismiss = { showSteamLibraries = false })
        if (showThemes) ThemePresetDialog(onDismiss = { showThemes = false })

        if (showBackup) {
            OutlinedAlertDialog(
                onDismissRequest = { showBackup = false },
                title = { Text("Backup Data") },
                text = { Text("Do you want to create a backup of the app's data directory?") },
                confirmButton = { TextButton(onClick = { runBackup() }) { Text("Yes") } },
                dismissButton = { TextButton(onClick = { showBackup = false }) { Text("No") } },
            )
        }
        if (backingUp) {
            OutlinedAlertDialog(
                onDismissRequest = {},
                title = { Text(L("Backing up data")) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(L("This can take a while."), modifier = Modifier.padding(start = 16.dp))
                    }
                },
                confirmButton = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            )
        }
        restoreUri?.let { uri ->
            OutlinedAlertDialog(
                onDismissRequest = { restoreUri = null },
                title = { Text("Restore Data") },
                text = { Text("This will restart the app. Continue?") },
                confirmButton = {
                    TextButton(onClick = {
                        restoreUri = null
                        val intent = Intent(context, com.winlator.star.restore.RestoreActivity::class.java)
                        intent.data = uri
                        context.startActivity(intent)
                        activity?.finish()
                    }) { Text("Restore") }
                },
                dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
            )
        }

        // win-fg training-capture consent: gates enabling, needs the "I understand" tick.
        if (showCaptureConsent) {
            var consentChecked by remember { mutableStateOf(false) }
            val consentText = remember { context.getString(R.string.winfg_capture_consent_v1) }
            OutlinedAlertDialog(
                onDismissRequest = { showCaptureConsent = false },
                title = { Text(context.getString(R.string.winfg_capture_consent_title)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(consentText, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = consentChecked, onCheckedChange = { consentChecked = it })
                            Text(L("I understand"), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = consentChecked, // Enable stays disabled until "I understand" is ticked.
                        onClick = {
                            WinFgCapture.recordConsentAndEnable(context)
                            captureEnabled = true
                            showCaptureConsent = false
                            AppUtils.showToast(context, L("Frame-gen training capture enabled"))
                        },
                    ) { Text(L("Enable")) }
                },
                dismissButton = { TextButton(onClick = { showCaptureConsent = false }) { Text(L("Cancel")) } },
            )
        }
        if (showCaptureHelp) {
            OutlinedAlertDialog(
                onDismissRequest = { showCaptureHelp = false },
                title = { Text(L("How to record good training data")) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        val bullets = listOf(
                            "What this does:" to "records raw in-game frames while you play, to help train an open, clean frame-generation model. Off by default; it lowers FPS while recording. Files save to Download/win-fg for you to share.",
                            "Best games to record:" to "ones with smooth, CONTINUOUS motion: 3D games, racing, action, anything with camera movement. These teach real interpolation.",
                            "Games to avoid:" to "menu-heavy screens, 2D games where sprites snap or teleport, and heavy particle-storm or rapid-flashing effects (un-interpolatable noise).",
                            "Cap your FPS" to "to a stable value (for example 30 or 60) so frame timing is even: this makes much cleaner training data.",
                            "Resolution:" to "\"Match game\" is best (records at your actual play resolution); pick 720p or 1080p to force one. A mix across contributors is ideal.",
                            "Variety helps more than length:" to "a few minutes each across several different games beats a long session of one.",
                            "Privacy:" to "only the game's rendered frames are captured: NO HUD or overlay, NO personal info, NO account data, NO audio. The record is anonymous.",
                        )
                        bullets.forEach { (lead, body) ->
                            Text("• $lead $body", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showCaptureHelp = false }) { Text(L("Close")) } },
            )
        }
    }
}

/** The custom accent colour: picking one switches the theme to Custom, as on the Classic Appearance screen. */
@Composable
private fun AccentPicker() {
    val accent by AppThemeState.customAccent.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(
            L("Selecting a colour below switches the theme to Custom."),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.5.sp,
        )
        Spacer(Modifier.height(8.dp))
        ColorPicker(initialColor = accent, onColorChanged = { AppThemeState.setCustomAccent(it) })
    }
}
