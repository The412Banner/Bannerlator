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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
    fun lsfgDllStatusText(): String =
        if (lsfgDllFile.isFile && lsfgDllFile.length() > 0)
            context.getString(
                R.string.compose_settings_lsfg_imported,
                lsfgDllFile.length() / (1024 * 1024)
            )
        else context.getString(R.string.compose_settings_lsfg_not_set)
    var lsfgDllStatus by remember { mutableStateOf(lsfgDllStatusText()) }
    fun importLosslessDllFromUri(uri: Uri) {
        try {
            lsfgDllFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                lsfgDllFile.outputStream().use { output -> input.copyTo(output) }
            }
            lsfgDllStatus = lsfgDllStatusText()
        } catch (e: Exception) {
            android.util.Log.e("SettingsScreen", "Lossless Scaling DLL import failed", e)
            lsfgDllStatus = context.getString(
                R.string.compose_settings_lsfg_import_failed,
                context.getString(R.string.final_errors_unknown_error),
            )
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
                Text(stringResource(R.string.compose_settings_backing_up))
            }
        }
        return
    }

    if (showBackupDialog) {
        OutlinedAlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text(stringResource(R.string.compose_settings_backup_title)) },
            text = { Text(stringResource(R.string.compose_settings_backup_question)) },
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
                                AppUtils.showToast(
                                    context,
                                    context.getString(
                                        R.string.compose_settings_backup_completed,
                                        backupFile.path
                                    )
                                )
                            }
                        } catch (_: Exception) {
                            (context as? Activity)?.runOnUiThread {
                                isBackingUp = false
                                AppUtils.showToast(
                                    context,
                                    context.getString(R.string.compose_settings_backup_failed)
                                )
                            }
                        }
                    }
                }) { Text(stringResource(R.string.compose_settings_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text(stringResource(R.string.compose_settings_no))
                }
            }
        )
    }

    if (showRestoreConfirm) {
        OutlinedAlertDialog(
            onDismissRequest = { showRestoreConfirm = false; pendingRestoreUri = null },
            title = { Text(stringResource(R.string.compose_settings_restore_title)) },
            text = { Text(stringResource(R.string.compose_settings_restore_restart_question)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    pendingRestoreUri?.let { uri ->
                        val intent = Intent(context, com.winlator.star.restore.RestoreActivity::class.java)
                        intent.data = uri
                        context.startActivity(intent)
                        (context as? Activity)?.finish()
                    }
                }) { Text(stringResource(R.string.compose_settings_restore_action)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    pendingRestoreUri = null
                }) { Text(stringResource(R.string.compose_settings_cancel)) }
            }
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
        FieldSetLabel(stringResource(R.string.language_title))
        FieldSet {
            val appLocales = AppCompatDelegate.getApplicationLocales()
            val currentLanguage = if (appLocales.isEmpty) {
                if (context.resources.configuration.locales[0].language == "ru") "ru" else "en"
            } else {
                if (appLocales[0]?.language == "ru") "ru" else "en"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LanguageOption(
                    label = stringResource(R.string.language_russian),
                    selected = currentLanguage == "ru",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        saveSettings()
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags("ru")
                        )
                    }
                )
                LanguageOption(
                    label = stringResource(R.string.language_english),
                    selected = currentLanguage == "en",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        saveSettings()
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags("en")
                        )
                    }
                )
            }
        }

        // ── Updates ──────────────────────────────────────────────────
        FieldSetLabel(stringResource(R.string.compose_settings_updates))
        FieldSet {
            val latest = updateInfo
            val installedVersion = UpdateManager.installedVersionName()
            val installedVersionText = if (latest != null) {
                stringResource(
                    R.string.compose_settings_installed_latest_versions,
                    installedVersion,
                    latest.versionName
                )
            } else {
                stringResource(R.string.compose_settings_installed_version, installedVersion)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    installedVersionText,
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f)
                )
                if (checkingUpdate) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (latest != null && latest.isNewer) {
                Text(
                    stringResource(R.string.compose_settings_update_available),
                    color = Color(0xFFFFC107), fontSize = 13.sp, // intentional: amber = update-available status, semantic not themeable
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
                ) {
                    Text(
                        stringResource(
                            R.string.compose_settings_download_install_version,
                            latest.versionName
                        ),
                        color = Color.White
                    )
                } // intentional: high-contrast label on green fill
            } else if (latest != null) {
                Text(
                    stringResource(R.string.compose_settings_up_to_date),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            Button(
                onClick = {
                    checkingUpdate = true
                    UpdateManager.check(context) { info ->
                        activity?.runOnUiThread {
                            updateInfo = info
                            checkingUpdate = false
                            when {
                                info == null -> AppUtils.showToast(
                                    context,
                                    context.getString(R.string.compose_settings_update_check_failed)
                                )
                                !info.isNewer -> AppUtils.showToast(
                                    context,
                                    context.getString(R.string.compose_settings_latest_version_toast)
                                )
                                else -> {}
                            }
                        }
                    }
                },
                enabled = !checkingUpdate,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    if (checkingUpdate) {
                        stringResource(R.string.compose_settings_checking)
                    } else {
                        stringResource(R.string.compose_settings_check_updates)
                    },
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = notifyUpdates, onCheckedChange = {
                    notifyUpdates = it
                    UpdateManager.setNotifyEnabled(context, it)
                })
                Text(
                    stringResource(R.string.compose_settings_notify_updates),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
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
                Text(
                    stringResource(R.string.compose_settings_include_prereleases),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
        }

        // ── Box64 Preset ─────────────────────────────────────────────
        FieldSetLabel(stringResource(R.string.compose_settings_box64))
        FieldSet {
            Text(
                stringResource(R.string.compose_settings_box64_preset),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
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
                }) {
                    Icon(
                        Icons.Default.Add,
                        stringResource(R.string.compose_settings_add),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    Box64EditPresetDialog(context, "box64", selectedBox64Preset).apply {
                        setOnConfirmCallback { refreshBox64Presets() }
                        show()
                    }
                }) {
                    Icon(
                        Icons.Default.Edit,
                        stringResource(R.string.compose_settings_edit),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    ContentDialog.confirm(context, R.string.do_you_want_to_duplicate_this_preset) {
                        Box64PresetManager.duplicatePreset("box64", context, selectedBox64Preset)
                        refreshBox64Presets()
                    }
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        stringResource(R.string.compose_settings_duplicate),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    if (selectedBox64Preset.startsWith(Box64Preset.CUSTOM)) {
                        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_preset) {
                            Box64PresetManager.removePreset("box64", context, selectedBox64Preset)
                            refreshBox64Presets()
                        }
                    } else AppUtils.showToast(context, R.string.you_cannot_remove_this_preset)
                }) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(R.string.compose_settings_remove),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    if (selectedBox64Preset.startsWith(Box64Preset.CUSTOM)) {
                        Box64PresetManager.exportPreset("box64", context, selectedBox64Preset)
                    } else AppUtils.showToast(
                        context,
                        context.getString(R.string.compose_settings_cannot_export_preset)
                    )
                }) {
                    Icon(
                        Icons.Default.FileUpload,
                        stringResource(R.string.compose_settings_export),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                ImportSourceIconButton(
                    icon = Icons.Default.FileDownload,
                    contentDescription = stringResource(R.string.compose_settings_import),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onInApp = {
                        importBox64InAppLauncher.launch(
                            InAppFilePicker.buildIntent(
                                context,
                                emptyArray(),
                                context.getString(R.string.compose_settings_select_box64_preset)
                            )
                        )
                    },
                    onSystem = { importBox64Launcher.launch(arrayOf("*/*")) },
                )
            }
        }

        // ── FEXCore Preset ────────────────────────────────────────────
        FieldSetLabel(stringResource(R.string.compose_settings_fexcore_config))
        FieldSet {
            Text(
                stringResource(R.string.compose_settings_fexcore_preset),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
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
                }) {
                    Icon(
                        Icons.Default.Add,
                        stringResource(R.string.compose_settings_add),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    FEXCoreEditPresetDialog(context, selectedFEXCorePreset).apply {
                        setOnConfirmCallback { refreshFEXCorePresets() }
                        show()
                    }
                }) {
                    Icon(
                        Icons.Default.Edit,
                        stringResource(R.string.compose_settings_edit),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    ContentDialog.confirm(context, R.string.do_you_want_to_duplicate_this_preset) {
                        FEXCorePresetManager.duplicatePreset(context, selectedFEXCorePreset)
                        refreshFEXCorePresets()
                    }
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        stringResource(R.string.compose_settings_duplicate),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    if (selectedFEXCorePreset.startsWith(FEXCorePreset.CUSTOM)) {
                        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_preset) {
                            FEXCorePresetManager.removePreset(context, selectedFEXCorePreset)
                            refreshFEXCorePresets()
                        }
                    } else AppUtils.showToast(context, R.string.you_cannot_remove_this_preset)
                }) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(R.string.compose_settings_remove),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    if (selectedFEXCorePreset.startsWith(FEXCorePreset.CUSTOM)) {
                        FEXCorePresetManager.exportPreset(context, selectedFEXCorePreset)
                    } else AppUtils.showToast(
                        context,
                        context.getString(R.string.compose_settings_cannot_export_preset)
                    )
                }) {
                    Icon(
                        Icons.Default.FileUpload,
                        stringResource(R.string.compose_settings_export),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                ImportSourceIconButton(
                    icon = Icons.Default.FileDownload,
                    contentDescription = stringResource(R.string.compose_settings_import),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onInApp = {
                        importFEXCoreInAppLauncher.launch(
                            InAppFilePicker.buildIntent(
                                context,
                                emptyArray(),
                                context.getString(R.string.compose_settings_select_fexcore_preset)
                            )
                        )
                    },
                    onSystem = { importFEXCoreLauncher.launch(arrayOf("*/*")) },
                )
            }
        }

        // ── Sound ─────────────────────────────────────────────────────
        FieldSetLabel(stringResource(R.string.compose_settings_sound))
        FieldSet {
            Text(
                stringResource(R.string.compose_settings_midi_soundfont),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    Button(onClick = { showSFDropdown = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth()) {
                        val defaultSoundFont = stringResource(R.string.compose_settings_default)
                        Text(
                            sfNames.getOrElse(selectedSF) { defaultSoundFont },
                            color = MaterialTheme.colorScheme.onSurface
                        )
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
                    contentDescription = stringResource(R.string.compose_settings_install),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onInApp = {
                        prepareSFInstall()
                        installSFInAppLauncher.launch(
                            InAppFilePicker.buildIntent(
                                context,
                                InAppFilePicker.SF2,
                                context.getString(R.string.compose_settings_select_soundfont)
                            )
                        )
                    },
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
                }) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(R.string.compose_settings_remove),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // ── Path Settings ─────────────────────────────────────────────
        FieldSetLabel(stringResource(R.string.compose_settings_path_settings))
        FieldSet {
            Text(
                stringResource(R.string.compose_settings_winlator_path),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(winlatorPath, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { winlatorPathLauncher.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Text(
                        stringResource(R.string.compose_settings_choose_path),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.compose_settings_shortcut_export_path),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shortcutExportPath, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { shortcutExportPathLauncher.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Text(
                        stringResource(R.string.compose_settings_choose_path),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // ── Default Screen on Launch ──────────────────────────────────
        FieldSetLabel(stringResource(R.string.compose_settings_default_screen))
        FieldSet {
            Text(
                stringResource(R.string.compose_settings_default_screen_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = defaultLandingScreen == "games",
                    onClick = { defaultLandingScreen = "games" },
                )
                Text(
                    stringResource(R.string.compose_settings_game_shortcuts),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = defaultLandingScreen == "containers",
                    onClick = { defaultLandingScreen = "containers" },
                )
                Text(
                    stringResource(R.string.compose_settings_containers),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
        }

        // ── Big Picture Mode ──────────────────────────────────────────
        FieldSetLabel(stringResource(R.string.compose_settings_big_picture))
        FieldSet {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = bigPictureMode, onCheckedChange = { bigPictureMode = it })
                Text(
                    stringResource(R.string.compose_settings_enable_big_picture_on_launch),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            FieldSet {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = customApiKeyEnabled, onCheckedChange = { customApiKeyEnabled = it })
                    Text(
                        stringResource(R.string.compose_settings_steamgrid_api_key),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        val url = "https://www.steamgriddb.com/profile/preferences/api"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }) {
                        Icon(
                            Icons.Default.Help,
                            stringResource(R.string.compose_settings_help),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (customApiKeyEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customApiKey,
                        onValueChange = { customApiKey = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.compose_settings_api_key_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ── XServer ──────────────────────────────────────────────────
        FieldSetLabel(stringResource(R.string.compose_settings_xserver))
        FieldSet {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.compose_settings_cursor_speed),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(
                        R.string.compose_settings_percent,
                        (cursorSpeed * 100).toInt()
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
            Slider(
                value = cursorSpeed,
                onValueChange = { cursorSpeed = it },
                valueRange = 0.1f..2.0f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useDRI3, onCheckedChange = { useDRI3 = it })
                Text(
                    stringResource(R.string.compose_settings_use_dri3),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useXR, onCheckedChange = { useXR = it })
                Text(
                    stringResource(R.string.compose_settings_use_xr),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = cursorLock, onCheckedChange = { cursorLock = it })
                Text(
                    stringResource(R.string.compose_settings_true_mouse),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = xinputToggle, onCheckedChange = { xinputToggle = it })
                Text(
                    stringResource(R.string.compose_settings_disable_xinput),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
        }

        // ── Logs ─────────────────────────────────────────────────────
        // Everything logging now lives in the Log Manager: location, which types to record, the
        // Wine channel chips, retention, and what is on disk per game. Deliberately NOT duplicated
        // here — the toggles used to exist in both places writing the same preferences, and this
        // screen saves on the FAB while the manager writes immediately, so one would silently
        // overwrite the other.
        FieldSetLabel(stringResource(R.string.compose_settings_logs))
        FieldSet {
            Text(
                stringResource(R.string.compose_settings_logs_description),
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Button(
                onClick = { showLogManager = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    stringResource(R.string.compose_settings_open_log_manager),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // ── Experimental ──────────────────────────────────────────────
        FieldSetLabel(stringResource(R.string.compose_settings_experimental))
        FieldSet {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enableFileProvider, onCheckedChange = { enableFileProvider = it })
                Text(
                    stringResource(R.string.compose_settings_enable_file_provider),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    AppUtils.showHelpBox(context, android.view.View(context), R.string.help_file_provider)
                }) {
                    Icon(
                        Icons.Default.Help,
                        stringResource(R.string.compose_settings_help),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = openWithBrowser, onCheckedChange = { openWithBrowser = it })
                Text(
                    stringResource(R.string.compose_settings_open_android_browser),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = shareClipboard, onCheckedChange = { shareClipboard = it })
                Text(
                    stringResource(R.string.compose_settings_share_android_clipboard),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.compose_settings_downloadable_contents_url),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
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
        FieldSetLabel(stringResource(R.string.compose_settings_imagefs))
        FieldSet {
            Button(
                onClick = {
                    ContentDialog.confirm(context, R.string.do_you_want_to_reinstall_imagefs) {
                        activity?.let { ImageFsInstaller.installFromAssets(it) }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // intentional: green = success/safe action (install/backup/restore), distinct from accent
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    stringResource(R.string.compose_settings_reinstall_imagefs),
                    color = Color.White
                )
            } // intentional: high-contrast label on green fill
            Button(
                onClick = { showBackupDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // intentional: green = success/safe action (install/backup/restore), distinct from accent
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    stringResource(R.string.compose_settings_backup_action),
                    color = Color.White
                )
            } // intentional: high-contrast label on green fill
            Button(
                onClick = {
                    restoreFileInAppLauncher.launch(
                        InAppFilePicker.buildIntent(
                            context,
                            InAppFilePicker.SAVE,
                            context.getString(R.string.compose_settings_select_backup)
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // intentional: green = success/safe action (install/backup/restore), distinct from accent
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    stringResource(R.string.compose_settings_restore_data_action),
                    color = Color.White
                )
            } // intentional: high-contrast label on green fill
            TextButton(
                onClick = { restoreFileLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.compose_settings_pick_via_system),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ── Frame Generation: lsfg-vk (Lossless Scaling DLL) ─────────────
        FieldSetLabel(stringResource(R.string.compose_settings_frame_generation_lsfg))
        FieldSet {
            Text(
                stringResource(R.string.compose_settings_lsfg_description),
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                stringResource(R.string.compose_settings_status, lsfgDllStatus),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Button(
                onClick = {
                    importLosslessDllInAppLauncher.launch(
                        InAppFilePicker.buildIntent(
                            context,
                            InAppFilePicker.DLL,
                            context.getString(R.string.compose_settings_select_lossless_dll)
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // intentional: green = success/safe action (install/backup/restore), distinct from accent
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    stringResource(R.string.compose_settings_import_lossless_dll),
                    color = Color.White
                )
            } // intentional: high-contrast label on green fill
            TextButton(
                onClick = { importLosslessDllLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.compose_settings_pick_via_system),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (lsfgDllFile.isFile) {
                Button(
                    onClick = { lsfgDllFile.delete(); lsfgDllStatus = lsfgDllStatusText() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.compose_settings_remove),
                        color = Color.White
                    )
                } // intentional: high-contrast label on error/destructive fill
            }
        }

        // ── Performance (power-user toggles) ─────────────────────────────
        FieldSetLabel(stringResource(R.string.compose_settings_performance))
        FieldSet {
            Text(
                stringResource(R.string.compose_settings_performance_description),
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Button(
                onClick = { showPerformanceMenu = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    stringResource(R.string.compose_settings_open_performance_settings),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
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
                AppUtils.showToast(
                    context,
                    context.getString(R.string.compose_settings_saved)
                )
                onSaved()
            },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(
                Icons.Default.Check,
                stringResource(R.string.compose_settings_save),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }

}

// ─── Reusable components ──────────────────────────────────────────────────

/**
 * Import trigger that offers the built-in file picker (primary) with the system SAF picker as a
 * secondary "Pick via system…" option (issue #73).
 */
@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        )
    }
}

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
            DropdownMenuItem(
                text = { Text(stringResource(R.string.compose_settings_browse_files)) },
                onClick = { expanded = false; onInApp() }
            )
            MenuItemDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.compose_settings_pick_via_system)) },
                onClick = { expanded = false; onSystem() }
            )
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
