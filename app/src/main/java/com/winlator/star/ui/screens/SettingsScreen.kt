package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.winlator.star.R
import com.winlator.star.SettingsFragment
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.box64.Box64EditPresetDialog
import com.winlator.star.box64.Box64Preset
import com.winlator.star.box64.Box64PresetManager
import com.winlator.star.contentdialog.ContentDialog
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.AppUtils
import com.winlator.star.core.FileUtils
import com.winlator.star.core.PreloaderDialog
import com.winlator.star.core.UpdateManager
import com.winlator.star.core.WinFgCapture
import com.winlator.star.fexcore.FEXCoreEditPresetDialog
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.fexcore.FEXCorePresetManager
import com.winlator.star.midi.MidiManager
import com.winlator.star.xenvironment.ImageFsInstaller
import com.winlator.star.MainActivity
import java.io.File
import java.util.concurrent.Executors

@Composable
fun SettingsScreen(onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    var box64Presets by remember { mutableStateOf(listOf<Box64Preset>()) }
    var selectedBox64Preset by remember { mutableStateOf(prefs.getString("box64_preset", Box64Preset.COMPATIBILITY) ?: Box64Preset.COMPATIBILITY) }
    var fexcorePresets by remember { mutableStateOf(listOf<FEXCorePreset>()) }
    var selectedFEXCorePreset by remember { mutableStateOf(prefs.getString("fexcore_preset", FEXCorePreset.COMPATIBILITY) ?: FEXCorePreset.COMPATIBILITY) }
    var sfNames by remember { mutableStateOf(listOf<String>()) }
    var selectedSF by remember { mutableStateOf(0) }

    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
    var bigPictureMode by remember { mutableStateOf(prefs.getBoolean("enable_big_picture_mode", false)) }
    // Default screen the app opens to: "games" (Game Shortcuts, historical default) or "containers".
    var defaultLandingScreen by remember { mutableStateOf(prefs.getString("default_landing_screen", "games") ?: "games") }
    var customApiKeyEnabled by remember { mutableStateOf(prefs.getBoolean("enable_custom_api_key", false)) }
    var customApiKey by remember { mutableStateOf(prefs.getString("custom_api_key", "") ?: "") }
    var cursorLock by remember { mutableStateOf(prefs.getBoolean("cursor_lock", false)) }
    var xinputToggle by remember { mutableStateOf(prefs.getBoolean("xinput_toggle", false)) }
    var useDRI3 by remember { mutableStateOf(prefs.getBoolean("use_dri3", true)) }
    var useXR by remember { mutableStateOf(prefs.getBoolean("use_xr", true)) }
    var cursorSpeed by remember { mutableFloatStateOf(prefs.getFloat("cursor_speed", 1.0f)) }

    // Updates
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var notifyUpdates by remember { mutableStateOf(UpdateManager.isNotifyEnabled(context)) }
    var includePrereleases by remember { mutableStateOf(UpdateManager.isIncludePrereleases(context)) }
    LaunchedEffect(Unit) {
        UpdateManager.check(context) { info -> activity?.runOnUiThread { updateInfo = info } }
    }
    var enableFileProvider by remember { mutableStateOf(prefs.getBoolean("enable_file_provider", true)) }
    var openWithBrowser by remember { mutableStateOf(prefs.getBoolean("open_with_android_browser", false)) }
    var shareClipboard by remember { mutableStateOf(prefs.getBoolean("share_android_clipboard", false)) }
    var downloadableContentsURL by remember { mutableStateOf(prefs.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES) ?: ContentsManager.REMOTE_PROFILES) }
    // win-fg training capture (global opt-in; consent-gated). Enabling opens the consent dialog.
    var captureEnabled by remember { mutableStateOf(WinFgCapture.isEnabled(context)) }
    var showCaptureConsent by remember { mutableStateOf(false) }

    var winlatorPath by remember { mutableStateOf(
        run {
            val uriStr = prefs.getString("winlator_path_uri", null)
            if (uriStr != null) {
                val path = FileUtils.getFilePathFromUri(context, Uri.parse(uriStr))
                path ?: uriStr
            } else SettingsFragment.DEFAULT_WINLATOR_PATH
        }
    ) }
    var shortcutExportPath by remember { mutableStateOf(
        run {
            val uriStr = prefs.getString("shortcuts_export_path_uri", null)
            if (uriStr != null) {
                val path = FileUtils.getFilePathFromUri(context, Uri.parse(uriStr))
                path ?: uriStr
            } else SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH
        }
    ) }

    var winlatorPathUri by remember { mutableStateOf<Uri?>(null) }
    var shortcutExportPathUri by remember { mutableStateOf<Uri?>(null) }

    var showBox64Dropdown by remember { mutableStateOf(false) }
    var showFEXCoreDropdown by remember { mutableStateOf(false) }
    var showSFDropdown by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showPerformanceMenu by remember { mutableStateOf(false) }
    var showLogManager by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var isBackingUp by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var installSFCallback by remember { mutableStateOf<((Uri) -> Unit)?>(null) }

    fun refreshBox64Presets() {
        box64Presets = Box64PresetManager.getPresets("box64", context)
    }

    fun refreshFEXCorePresets() {
        fexcorePresets = FEXCorePresetManager.getPresets(context)
    }

    fun refreshSF() {
        val names = mutableListOf<String>()
        names.add(MidiManager.DEFAULT_SF2_FILE)
        val sfDir = MidiManager.getSoundFontDir(context)
        val files = sfDir.listFiles()
        if (files != null) {
            for (f in files) if (f.name.endsWith(".sf2")) names.add(f.name)
        }
        sfNames = names
    }

    fun saveSettings() {
        val editor = prefs.edit()
        editor.putString("box64_preset", selectedBox64Preset)
        editor.putString("fexcore_preset", selectedFEXCorePreset)
        editor.putBoolean("dark_mode", darkMode)
        editor.putBoolean("enable_big_picture_mode", bigPictureMode)
        editor.putString("default_landing_screen", defaultLandingScreen)
        editor.putBoolean("enable_custom_api_key", customApiKeyEnabled)
        if (customApiKeyEnabled) editor.putString("custom_api_key", customApiKey)
        else editor.remove("custom_api_key")
        editor.putBoolean("cursor_lock", cursorLock)
        editor.putBoolean("xinput_toggle", xinputToggle)
        editor.putBoolean("use_dri3", useDRI3)
        editor.putBoolean("use_xr", useXR)
        editor.putFloat("cursor_speed", cursorSpeed)
        // NOTE: enable_wine_debug / wine_debug_channels / enable_box64_logs / log_location_mode /
        // log_location_custom_path are owned by the Log Manager now and are deliberately NOT saved
        // here. This screen snapshots preferences into state at first composition and writes them
        // back on the Save FAB; the Log Manager (opened as a dialog from this very screen, so this
        // composition is never disposed) writes immediately. Saving them here would write the stale
        // pre-dialog snapshot back over whatever the user just changed in the manager.
        editor.putBoolean("enable_file_provider", enableFileProvider)
        editor.putBoolean("open_with_android_browser", openWithBrowser)
        editor.putBoolean("share_android_clipboard", shareClipboard)
        editor.putString("downloadable_contents_url", downloadableContentsURL)
        if (winlatorPathUri != null) {
            editor.putString("winlator_path_uri", winlatorPathUri.toString())
            try {
                context.contentResolver.takePersistableUriPermission(winlatorPathUri!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
        }
        if (shortcutExportPathUri != null) {
            editor.putString("shortcuts_export_path_uri", shortcutExportPathUri.toString())
            try {
                context.contentResolver.takePersistableUriPermission(shortcutExportPathUri!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
        }
        editor.commit()
    }

    DisposableEffect(Unit) {
        refreshBox64Presets()
        refreshFEXCorePresets()
        refreshSF()
        onDispose { }
    }

    val winlatorPathLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            winlatorPathUri = uri
            val path = FileUtils.getFilePathFromUri(context, uri)
            winlatorPath = path ?: uri.toString()
        }
    }

    val shortcutExportPathLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            shortcutExportPathUri = uri
            val path = FileUtils.getFilePathFromUri(context, uri)
            shortcutExportPath = path ?: uri.toString()
        }
    }

    // SoundFont (.sf2). installSFCallback is set by the trigger before launching.
    val installSFLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && installSFCallback != null) {
            installSFCallback!!(uri)
            installSFCallback = null
        }
    }
    val installSFInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data) else null
        if (uri != null && installSFCallback != null) installSFCallback!!(uri)
        installSFCallback = null
    }

    fun importBox64FromUri(uri: Uri) {
        try {
            val `is` = context.contentResolver.openInputStream(uri)
            Box64PresetManager.importPreset("box64", context, `is`)
            refreshBox64Presets()
        } catch (_: Exception) { }
    }
    val importBox64Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importBox64FromUri(uri) }
    val importBox64InAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { importBox64FromUri(it) }
    }

    fun importFEXCoreFromUri(uri: Uri) {
        try {
            val `is` = context.contentResolver.openInputStream(uri)
            FEXCorePresetManager.importPreset(context, `is`)
            refreshFEXCorePresets()
        } catch (_: Exception) { }
    }
    val importFEXCoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFEXCoreFromUri(uri) }
    val importFEXCoreInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { importFEXCoreFromUri(it) }
    }

    fun beginRestoreFromUri(uri: Uri) {
        pendingRestoreUri = uri
        showRestoreConfirm = true
    }
    val restoreFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) beginRestoreFromUri(uri) }
    val restoreFileInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { beginRestoreFromUri(it) }
    }

    // lsfg-vk: the user picks their own Lossless Scaling DLL once; we COPY it into app files
    // (filesDir/lsfg-vk/Lossless.dll) and load from that copy forever after — the SAF Uri grant
    // can be revoked, so the local copy is the source of truth. The launch wiring points
    // LSFG_DLL_PATH at this exact file.
    val lsfgDllFile = remember { File(context.filesDir, "lsfg-vk/Lossless.dll") }
    // Where the current copy came from ("store" | "manual"), so the status line can label it and the
    // badge survives an app restart. The DLL file itself is still the single runtime source of truth;
    // this pref is purely cosmetic provenance. Cleared when the DLL is removed.
    fun lsfgDllStatusText(): String {
        if (!(lsfgDllFile.isFile && lsfgDllFile.length() > 0)) return "Not set — lsfg-vk will stay off"
        val mb = lsfgDllFile.length() / (1024 * 1024)
        return when (prefs.getString("lsfg_dll_source", null)) {
            "store"  -> "Imported from Steam store (Lossless Scaling) — $mb MB"
            "manual" -> "Imported manually — $mb MB"
            else     -> "Imported ($mb MB)"
        }
    }
    var lsfgDllStatus by remember { mutableStateOf(lsfgDllStatusText()) }
    fun importLosslessDllFromUri(uri: Uri) {
        try {
            lsfgDllFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                lsfgDllFile.outputStream().use { output -> input.copyTo(output) }
            }
            prefs.edit().putString("lsfg_dll_source", "manual").apply()
            lsfgDllStatus = lsfgDllStatusText()
        } catch (e: Exception) {
            lsfgDllStatus = "Import failed: " + e.message
        }
    }
    // Locate a Lossless.dll from a Steam-store install (appId 993090 lands under
    // filesDir/imagefs/steam_games/<name>/…). Lossless Scaling ships the DLL at its install root, but
    // we walk a bounded tree in case a future layout nests it. Prefer the newest by lastModified so a
    // re-download of an updated Lossless Scaling wins.
    fun findStoreLosslessDll(): File? {
        val root = File(context.filesDir, "imagefs/steam_games")
        if (!root.isDirectory) return null
        return root.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.name.equals("Lossless.dll", ignoreCase = true) && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
    }
    fun detectLosslessDllFromStore() {
        val src = findStoreLosslessDll()
        if (src == null) {
            Toast.makeText(context,
                "No Steam-store Lossless Scaling found — download it from the store or import manually.",
                Toast.LENGTH_LONG).show()
            return
        }
        try {
            lsfgDllFile.parentFile?.mkdirs()
            src.inputStream().use { input -> lsfgDllFile.outputStream().use { output -> input.copyTo(output) } }
            prefs.edit().putString("lsfg_dll_source", "store").apply()
            lsfgDllStatus = lsfgDllStatusText()
            Toast.makeText(context, "Lossless.dll set from Steam store install.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            lsfgDllStatus = "Detect failed: " + e.message
            Toast.makeText(context, "Detect failed: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
    val importLosslessDllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importLosslessDllFromUri(uri) }
    val importLosslessDllInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { importLosslessDllFromUri(it) }
    }

    if (isBackingUp) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Backing up data...")
            }
        }
        return
    }

    if (showBackupDialog) {
        OutlinedAlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Backup Data") },
            text = { Text("Do you want to create a backup of the app's data directory?") },
            confirmButton = {
                TextButton(onClick = {
                    showBackupDialog = false
                    isBackingUp = true
                    val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
                    executor.execute {
                        val dataDir = context.filesDir.parentFile
                        val backupFile = File(Environment.getExternalStorageDirectory(), "app_data_backup.tar")
                        try {
                            com.winlator.star.core.TarCompressorUtils.archive(
                                arrayOf(dataDir), backupFile
                            ) { file -> !file.absolutePath.contains("imagefs/tmp/.sysvshm") }
                            (context as? Activity)?.runOnUiThread {
                                isBackingUp = false
                                AppUtils.showToast(context, "Backup completed: ${backupFile.path}")
                            }
                        } catch (_: Exception) {
                            (context as? Activity)?.runOnUiThread {
                                isBackingUp = false
                                AppUtils.showToast(context, "Backup failed.")
                            }
                        }
                    }
                }) { Text("Yes") }
            },
            dismissButton = { TextButton(onClick = { showBackupDialog = false }) { Text("No") } }
        )
    }

    if (showRestoreConfirm) {
        OutlinedAlertDialog(
            onDismissRequest = { showRestoreConfirm = false; pendingRestoreUri = null },
            title = { Text("Restore Data") },
            text = { Text("This will restart the app. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    pendingRestoreUri?.let { uri ->
                        val intent = Intent(context, com.winlator.star.restore.RestoreActivity::class.java)
                        intent.data = uri
                        context.startActivity(intent)
                        (context as? Activity)?.finish()
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false; pendingRestoreUri = null }) { Text("Cancel") } }
        )
    }

    // ── Main content ──────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Updates ──────────────────────────────────────────────────
        FieldSetLabel("Updates")
        FieldSet {
            val latest = updateInfo
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Installed: V ${UpdateManager.installedVersionName()}" +
                        (latest?.let { "   ·   Latest: V ${it.versionName}" } ?: ""),
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f)
                )
                if (checkingUpdate) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (latest != null && latest.isNewer) {
                Text(
                    "Update available", color = Color(0xFFFFC107), fontSize = 13.sp, // intentional: amber = update-available status, semantic not themeable
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp)
                )
                if (latest.notes.isNotBlank()) {
                    Text(
                        latest.notes, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Button(
                    onClick = { activity?.let { UpdateManager.downloadAndInstall(it, latest) {} } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // intentional: green = success/safe action (install/backup/restore), distinct from accent
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) { Text("Download & install V ${latest.versionName}", color = Color.White) } // intentional: high-contrast label on green fill
            } else if (latest != null) {
                Text("You're up to date.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Button(
                onClick = {
                    checkingUpdate = true
                    UpdateManager.check(context) { info ->
                        activity?.runOnUiThread {
                            updateInfo = info
                            checkingUpdate = false
                            when {
                                info == null -> AppUtils.showToast(context, "Couldn't check for updates")
                                !info.isNewer -> AppUtils.showToast(context, "You're on the latest version")
                                else -> {}
                            }
                        }
                    }
                },
                enabled = !checkingUpdate,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text(if (checkingUpdate) "Checking…" else "Check for updates", color = MaterialTheme.colorScheme.onPrimary) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = notifyUpdates, onCheckedChange = {
                    notifyUpdates = it
                    UpdateManager.setNotifyEnabled(context, it)
                })
                Text("Notify me about updates", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = includePrereleases, onCheckedChange = {
                    includePrereleases = it
                    UpdateManager.setIncludePrereleases(context, it)
                    // Re-check immediately so the readout reflects the new setting.
                    checkingUpdate = true
                    UpdateManager.check(context) { info ->
                        activity?.runOnUiThread { updateInfo = info; checkingUpdate = false }
                    }
                })
                Text("Include pre-releases (beta builds)", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
        }

        // ── Box64 Preset ─────────────────────────────────────────────
        FieldSetLabel("Box64")
        FieldSet {
            Text("Box64 Preset", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Box {
                Button(onClick = { showBox64Dropdown = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()) {
                    val label = box64Presets.find { it.id == selectedBox64Preset }?.name ?: selectedBox64Preset
                    Text(label, color = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(
                    expanded = showBox64Dropdown,
                    onDismissRequest = { showBox64Dropdown = false },
                    modifier = Modifier.outlinedMenuCard()
                ) {
                    box64Presets.forEachIndexed { i, preset ->
                        if (i > 0) MenuItemDivider()
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = { selectedBox64Preset = preset.id; showBox64Dropdown = false }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = {
                    Box64EditPresetDialog(context, "box64", null).apply {
                        setOnConfirmCallback { refreshBox64Presets() }
                        show()
                    }
                }) { Icon(Icons.Default.Add, "Add", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    Box64EditPresetDialog(context, "box64", selectedBox64Preset).apply {
                        setOnConfirmCallback { refreshBox64Presets() }
                        show()
                    }
                }) { Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    ContentDialog.confirm(context, R.string.do_you_want_to_duplicate_this_preset) {
                        Box64PresetManager.duplicatePreset("box64", context, selectedBox64Preset)
                        refreshBox64Presets()
                    }
                }) { Icon(Icons.Default.ContentCopy, "Duplicate", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    if (selectedBox64Preset.startsWith(Box64Preset.CUSTOM)) {
                        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_preset) {
                            Box64PresetManager.removePreset("box64", context, selectedBox64Preset)
                            refreshBox64Presets()
                        }
                    } else AppUtils.showToast(context, R.string.you_cannot_remove_this_preset)
                }) { Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    if (selectedBox64Preset.startsWith(Box64Preset.CUSTOM)) {
                        Box64PresetManager.exportPreset("box64", context, selectedBox64Preset)
                    } else AppUtils.showToast(context, "Cannot export this preset")
                }) { Icon(Icons.Default.FileUpload, "Export", tint = MaterialTheme.colorScheme.onSurface) }
                ImportSourceIconButton(
                    icon = Icons.Default.FileDownload,
                    contentDescription = "Import",
                    tint = MaterialTheme.colorScheme.onSurface,
                    onInApp = { importBox64InAppLauncher.launch(InAppFilePicker.buildIntent(context, emptyArray(), "Select box64 preset")) },
                    onSystem = { importBox64Launcher.launch(arrayOf("*/*")) },
                )
            }
        }

        // ── FEXCore Preset ────────────────────────────────────────────
        FieldSetLabel("FEXCore Config")
        FieldSet {
            Text("FEXCore Preset", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Box {
                Button(onClick = { showFEXCoreDropdown = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()) {
                    val label = fexcorePresets.find { it.id == selectedFEXCorePreset }?.name ?: selectedFEXCorePreset
                    Text(label, color = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(
                    expanded = showFEXCoreDropdown,
                    onDismissRequest = { showFEXCoreDropdown = false },
                    modifier = Modifier.outlinedMenuCard()
                ) {
                    fexcorePresets.forEachIndexed { i, preset ->
                        if (i > 0) MenuItemDivider()
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = { selectedFEXCorePreset = preset.id; showFEXCoreDropdown = false }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = {
                    FEXCoreEditPresetDialog(context, null).apply {
                        setOnConfirmCallback { refreshFEXCorePresets() }
                        show()
                    }
                }) { Icon(Icons.Default.Add, "Add", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    FEXCoreEditPresetDialog(context, selectedFEXCorePreset).apply {
                        setOnConfirmCallback { refreshFEXCorePresets() }
                        show()
                    }
                }) { Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    ContentDialog.confirm(context, R.string.do_you_want_to_duplicate_this_preset) {
                        FEXCorePresetManager.duplicatePreset(context, selectedFEXCorePreset)
                        refreshFEXCorePresets()
                    }
                }) { Icon(Icons.Default.ContentCopy, "Duplicate", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    if (selectedFEXCorePreset.startsWith(FEXCorePreset.CUSTOM)) {
                        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_preset) {
                            FEXCorePresetManager.removePreset(context, selectedFEXCorePreset)
                            refreshFEXCorePresets()
                        }
                    } else AppUtils.showToast(context, R.string.you_cannot_remove_this_preset)
                }) { Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    if (selectedFEXCorePreset.startsWith(FEXCorePreset.CUSTOM)) {
                        FEXCorePresetManager.exportPreset(context, selectedFEXCorePreset)
                    } else AppUtils.showToast(context, "Cannot export this preset")
                }) { Icon(Icons.Default.FileUpload, "Export", tint = MaterialTheme.colorScheme.onSurface) }
                ImportSourceIconButton(
                    icon = Icons.Default.FileDownload,
                    contentDescription = "Import",
                    tint = MaterialTheme.colorScheme.onSurface,
                    onInApp = { importFEXCoreInAppLauncher.launch(InAppFilePicker.buildIntent(context, emptyArray(), "Select FEXCore preset")) },
                    onSystem = { importFEXCoreLauncher.launch(arrayOf("*/*")) },
                )
            }
        }

        // ── Sound ─────────────────────────────────────────────────────
        FieldSetLabel("Sound")
        FieldSet {
            Text("MIDI Sound Font", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    Button(onClick = { showSFDropdown = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(sfNames.getOrElse(selectedSF) { "Default" }, color = MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(
                        expanded = showSFDropdown,
                        onDismissRequest = { showSFDropdown = false },
                        modifier = Modifier.outlinedMenuCard()
                    ) {
                        sfNames.forEachIndexed { i, name ->
                            if (i > 0) MenuItemDivider()
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { selectedSF = i; showSFDropdown = false }
                            )
                        }
                    }
                }
                val prepareSFInstall = {
                    installSFCallback = { uri ->
                        val act = activity
                        if (act != null) {
                            val dialog = PreloaderDialog(act)
                            dialog.showOnUiThread(R.string.installing_content)
                            MidiManager.installSF2File(context, uri, object : MidiManager.OnSoundFontInstalledCallback {
                                override fun onSuccess() {
                                    dialog.closeOnUiThread()
                                    (context as? Activity)?.runOnUiThread {
                                        ContentDialog.alert(context, R.string.sound_font_installed_success, null)
                                        refreshSF()
                                    }
                                }
                                override fun onFailed(reason: Int) {
                                    dialog.closeOnUiThread()
                                    val resId = when (reason) {
                                        MidiManager.ERROR_BADFORMAT -> R.string.sound_font_bad_format
                                        MidiManager.ERROR_EXIST -> R.string.sound_font_already_exist
                                        else -> R.string.sound_font_installed_failed
                                    }
                                    (context as? Activity)?.runOnUiThread {
                                        ContentDialog.alert(context, resId, null)
                                    }
                                }
                            })
                        }
                    }
                }
                ImportSourceIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "Install",
                    tint = MaterialTheme.colorScheme.onSurface,
                    onInApp = { prepareSFInstall(); installSFInAppLauncher.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.SF2, "Select SoundFont")) },
                    onSystem = { prepareSFInstall(); installSFLauncher.launch(arrayOf("*/*")) },
                )
                IconButton(onClick = {
                    if (selectedSF != 0) {
                        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_sound_font) {
                            if (MidiManager.removeSF2File(context, sfNames[selectedSF])) {
                                AppUtils.showToast(context, R.string.sound_font_removed_success)
                                refreshSF()
                            } else AppUtils.showToast(context, R.string.sound_font_removed_failed)
                        }
                    } else AppUtils.showToast(context, R.string.cannot_remove_default_sound_font)
                }) { Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurface) }
            }
        }

        // ── Path Settings ─────────────────────────────────────────────
        FieldSetLabel("Path Settings")
        FieldSet {
            Text("Winlator Path", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(winlatorPath, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { winlatorPathLauncher.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Text("Choose Path", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Shortcut Export Path", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shortcutExportPath, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { shortcutExportPathLauncher.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Text("Choose Path", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
            }
        }

        // ── Default Screen on Launch ──────────────────────────────────
        FieldSetLabel("Default Screen on Launch")
        FieldSet {
            Text(
                "Which screen the app opens to.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = defaultLandingScreen == "games",
                    onClick = { defaultLandingScreen = "games" },
                )
                Text("Game Shortcuts", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = defaultLandingScreen == "containers",
                    onClick = { defaultLandingScreen = "containers" },
                )
                Text("Containers", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
        }

        // ── Big Picture Mode ──────────────────────────────────────────
        FieldSetLabel("Big Picture Mode")
        FieldSet {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = bigPictureMode, onCheckedChange = { bigPictureMode = it })
                Text("Enable Big Picture Mode on App Launch", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            FieldSet {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = customApiKeyEnabled, onCheckedChange = { customApiKeyEnabled = it })
                    Text("Set SteamGrid API Key? (Cover Art)", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        val url = "https://www.steamgriddb.com/profile/preferences/api"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }) { Icon(Icons.Default.Help, "Help", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (customApiKeyEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customApiKey,
                        onValueChange = { customApiKey = it },
                        placeholder = { Text("Enter your API Key here", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ── XServer ──────────────────────────────────────────────────
        FieldSetLabel("XServer")
        FieldSet {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cursor Speed", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${(cursorSpeed * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            Slider(
                value = cursorSpeed,
                onValueChange = { cursorSpeed = it },
                valueRange = 0.1f..2.0f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useDRI3, onCheckedChange = { useDRI3 = it })
                Text("Use DRI3 Extension", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useXR, onCheckedChange = { useXR = it })
                Text("Use XR", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = cursorLock, onCheckedChange = { cursorLock = it })
                Text("True Mouse Control (Deactivate with Volume Down)", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = xinputToggle, onCheckedChange = { xinputToggle = it })
                Text("Disable Xinput (Used for Exclusive M/KB support)", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
        }

        // ── Logs ─────────────────────────────────────────────────────
        // Everything logging now lives in the Log Manager: location, which types to record, the
        // Wine channel chips, retention, and what is on disk per game. Deliberately NOT duplicated
        // here — the toggles used to exist in both places writing the same preferences, and this
        // screen saves on the FAB while the manager writes immediately, so one would silently
        // overwrite the other.
        FieldSetLabel("Logs")
        FieldSet {
            Text(
                "Where logs are saved, which ones to record, how many past runs to keep, and what is " +
                "on disk for each game. Two log types slow games down while enabled — the manager " +
                "says which.",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Button(
                onClick = { showLogManager = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text("Open Log Manager", color = MaterialTheme.colorScheme.onPrimary) }
        }

        // ── Experimental ──────────────────────────────────────────────
        FieldSetLabel("Experimental")
        FieldSet {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enableFileProvider, onCheckedChange = { enableFileProvider = it })
                Text("Enable File Provider", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    AppUtils.showHelpBox(context, android.view.View(context), R.string.help_file_provider)
                }) { Icon(Icons.Default.Help, "Help", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = openWithBrowser, onCheckedChange = { openWithBrowser = it })
                Text("Open with Android Browser", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = shareClipboard, onCheckedChange = { shareClipboard = it })
                Text("Share Android Clipboard", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Downloadable Contents URL", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = downloadableContentsURL,
                onValueChange = { downloadableContentsURL = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall
            )
        }

        // ── ImageFS ──────────────────────────────────────────────────
        FieldSetLabel("ImageFS")
        FieldSet {
            Button(
                onClick = {
                    ContentDialog.confirm(context, R.string.do_you_want_to_reinstall_imagefs) {
                        activity?.let { ImageFsInstaller.installFromAssets(it) }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // intentional: green = success/safe action (install/backup/restore), distinct from accent
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text("Reinstall ImageFS", color = Color.White) } // intentional: high-contrast label on green fill
            Button(
                onClick = { showBackupDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // intentional: green = success/safe action (install/backup/restore), distinct from accent
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text("Backup Data", color = Color.White) } // intentional: high-contrast label on green fill
            Button(
                onClick = { restoreFileInAppLauncher.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.SAVE, "Select backup")) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // intentional: green = success/safe action (install/backup/restore), distinct from accent
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text("Restore Data", color = Color.White) } // intentional: high-contrast label on green fill
            TextButton(
                onClick = { restoreFileLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Pick via system…", color = MaterialTheme.colorScheme.primary) }
        }

        // ── Frame Generation: lsfg-vk (Lossless Scaling DLL) ─────────────
        FieldSetLabel("Frame Generation — lsfg-vk")
        FieldSet {
            Text(
                "lsfg-vk needs a Lossless Scaling \"Lossless.dll\". Download Lossless Scaling from the in-app " +
                "Steam store and tap Detect below — or import your own copy. Either way it is copied into the " +
                "app and reused by any container whose Frame Generation engine is set to lsfg-vk.",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                "Status: " + lsfgDllStatus, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Button(
                onClick = { detectLosslessDllFromStore() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // intentional: green = success/safe action, distinct from accent
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text("Detect from Steam store", color = Color.White) } // intentional: high-contrast label on green fill
            Text(
                "Manual override — pick a Lossless.dll yourself:",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
            Button(
                onClick = { importLosslessDllInAppLauncher.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.DLL, "Select Lossless.dll")) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // intentional: green = success/safe action (install/backup/restore), distinct from accent
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text("Import Lossless.dll", color = Color.White) } // intentional: high-contrast label on green fill
            TextButton(
                onClick = { importLosslessDllLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Pick via system…", color = MaterialTheme.colorScheme.primary) }
            if (lsfgDllFile.isFile) {
                Button(
                    onClick = { lsfgDllFile.delete(); prefs.edit().remove("lsfg_dll_source").apply(); lsfgDllStatus = lsfgDllStatusText() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) { Text("Remove", color = Color.White) } // intentional: high-contrast label on error/destructive fill
            }
        }

        // ── Developer: frame-gen training capture (crowdsourced win-fg dataset) ──
        FieldSetLabel("Developer — Frame-gen training capture")
        FieldSet {
            Text(
                "Help improve Bannerlator's open frame generation. When on, the win-fg frame-gen layer " +
                "records raw in-game frames while you play and saves them to Download/win-fg for you to " +
                "share with us. It records only the game's rendered image — no personal info, no " +
                "account, no Android system data, no audio. It lowers FPS while recording.",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                "Only records when a game's Frame Generation engine is set to win-fg. It does not force " +
                "frame generation on.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = captureEnabled,
                    onCheckedChange = { want ->
                        if (want) {
                            // Consent is mandatory — never enable directly; open the consent dialog.
                            showCaptureConsent = true
                        } else {
                            WinFgCapture.disable(context)
                            captureEnabled = false
                            AppUtils.showToast(context, "Frame-gen training capture off")
                        }
                    }
                )
                Text("Contribute frame-gen training capture", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            if (captureEnabled) {
                Text(
                    "Recording is ON — files saved to Download/win-fg.",
                    color = Color(0xFF4CAF50), fontSize = 12.sp, // intentional: green = active/success status
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // ── Performance (power-user toggles) ─────────────────────────────
        FieldSetLabel("Performance")
        FieldSet {
            Text(
                "Global performance defaults (Sustained Performance Mode, Thread Priority Boost, " +
                "Prefer Big Cores, GPU clock lock) plus the opt-in root controls. Per-game overrides live in " +
                "each game's settings and the in-game menu.",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Button(
                onClick = { showPerformanceMenu = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text("Open Performance settings", color = MaterialTheme.colorScheme.onPrimary) }
        }

        Spacer(Modifier.height(72.dp))
    }

    // ── Performance menu (full-screen dialog; inline settings have no sub-screen nav host) ──
    if (showPerformanceMenu) {
        Dialog(
            onDismissRequest = { showPerformanceMenu = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            PerformanceSettingsScreen(onClose = { showPerformanceMenu = false })
        }
    }

    // ── win-fg training-capture consent (gates enabling; needs the "I understand" tick) ──
    if (showCaptureConsent) {
        var consentChecked by remember { mutableStateOf(false) }
        val consentText = remember { context.getString(R.string.winfg_capture_consent_v1) }
        AlertDialog(
            onDismissRequest = { showCaptureConsent = false },
            title = { Text(context.getString(R.string.winfg_capture_consent_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(consentText, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consentChecked, onCheckedChange = { consentChecked = it })
                        Text("I understand", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
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
                        AppUtils.showToast(context, "Frame-gen training capture enabled")
                    }
                ) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { showCaptureConsent = false }) { Text("Cancel") }
            }
        )
    }

    if (showLogManager) {
        Dialog(
            onDismissRequest = { showLogManager = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            LogManagerScreen(onClose = { showLogManager = false })
        }
    }

    // ── FAB Save ─────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize()) {
        FloatingActionButton(
            onClick = {
                saveSettings()
                AppUtils.showToast(context, "Settings saved!")
                onSaved()
            },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Check, "Save", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }

}

// ─── Reusable components ──────────────────────────────────────────────────

/**
 * Import trigger that offers the built-in file picker (primary) with the system SAF picker as a
 * secondary "Pick via system…" option (issue #73).
 */
@Composable
private fun ImportSourceIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onInApp: () -> Unit,
    onSystem: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(icon, contentDescription, tint = tint) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.outlinedMenuCard()
        ) {
            DropdownMenuItem(text = { Text("Browse files") }, onClick = { expanded = false; onInApp() })
            MenuItemDivider()
            DropdownMenuItem(text = { Text("Pick via system…") }, onClick = { expanded = false; onSystem() })
        }
    }
}

@Composable
private fun FieldSetLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun FieldSet(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            // Outline each settings section to match the game/container/community card idiom.
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        content()
    }
}
