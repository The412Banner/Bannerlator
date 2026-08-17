@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.media.MediaScannerConnection
import android.os.Environment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.winlator.star.communityconfigs.AccountManager
import com.winlator.star.communityconfigs.CanonicalDevice
import com.winlator.star.communityconfigs.CanonicalGame
import com.winlator.star.communityconfigs.CommunityConfigApply
import com.winlator.star.communityconfigs.CommunityConfigRef
import com.winlator.star.communityconfigs.DeviceIdentity
import com.winlator.star.communityconfigs.ShortcutExporter
import com.winlator.star.communityconfigs.UploadedConfigsStore.UploadedConfig
import com.winlator.star.communityconfigs.GameMatcher
import com.winlator.star.communityconfigs.ShortcutConfig
import com.winlator.star.communityconfigs.WorkerConfigEntry
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.winlator.star.ui.AccountAvatar
import com.winlator.star.ui.AccountUiBus
import com.winlator.star.ui.LocalTopBarActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.preference.PreferenceManager
import androidx.compose.ui.viewinterop.AndroidView
import com.winlator.star.R
import com.winlator.star.SettingsFragment
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.XrActivity
import com.winlator.star.box64.Box64Preset
import com.winlator.star.box64.Box64PresetManager
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.reshade.ReshadeManager
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.screens.adrenodownload.AdrenoDriverDownloadSheet
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverEntry
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverRepository
import com.winlator.star.core.DefaultVersion
import com.winlator.star.core.FileUtils
import com.winlator.star.core.KeyValueSet
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WineInfo
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.fexcore.FEXCorePresetManager
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.midi.MidiManager
import com.winlator.star.store.StarLaunchBridge
import com.winlator.star.ui.theme.Divider as DividerColor
import com.winlator.star.ui.theme.LocalAccentDim
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.Surface as SurfaceColor
import com.winlator.star.ui.theme.SurfaceVariant as SurfaceVariantColor
import com.winlator.star.widget.CPUListView
import com.winlator.star.widget.EnvVarsView
import com.winlator.star.winhandler.WinHandler
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import java.lang.reflect.Field
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ShortcutsScreen(vm: ShortcutsViewModel = viewModel()) {
    val shortcuts by vm.shortcuts.collectAsState(initial = emptyList())
    val sortOrder by vm.sortOrder.collectAsState()
    val isGridView by vm.isGridView.collectAsState()
    val context = LocalContext.current
    val activity = context as Activity

    var confirmRemove by remember { mutableStateOf<Shortcut?>(null) }
    var cloneTarget by remember { mutableStateOf<Shortcut?>(null) }
    var settingsShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var propertiesShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showImportContainerPicker by remember { mutableStateOf(false) }
    var pendingImportContainerIndex by remember { mutableStateOf(-1) }
    // When checked, the shortcut import uses the system SAF picker instead of the in-app File Manager.
    var importUseSystemPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDialogName by remember { mutableStateOf("") }
    var renameDialogContainerIndex by remember { mutableStateOf(-1) }
    var scrapeTarget by remember { mutableStateOf<Shortcut?>(null) }
    val scrapeCovers = remember { mutableStateListOf<Pair<Bitmap, String>>() }
    var scrapeLoading by remember { mutableStateOf(false) }
    var communityTarget by remember { mutableStateOf<Shortcut?>(null) }
    var communityResult by remember { mutableStateOf<CommunityMatchResult?>(null) }
    var communityLoading by remember { mutableStateOf(false) }
    var communitySearch by remember(communityTarget) { mutableStateOf("") }
    var communitySearchResults by remember(communityTarget) { mutableStateOf<List<CanonicalGame>>(emptyList()) }
    // Catalog browser (catalog-first entry from the header) + the shared Phase 2 apply flow.
    var showCommunityBrowser by remember { mutableStateOf(false) }
    var applyPicker by remember { mutableStateOf<CommunityPick?>(null) }
    var applyMismatch by remember { mutableStateOf<Pair<Shortcut, CommunityPick>?>(null) }
    var applyBusy by remember { mutableStateOf(false) }
    var applyResult by remember { mutableStateOf<CommunityConfigApply.ConfigApplyResult?>(null) }
    // The shortcut the current result was applied to — threaded through so a post-install component
    // fixup can write the resolved version sub-field back to the right shortcut.
    var applyTarget by remember { mutableStateOf<Shortcut?>(null) }
    // Missing component the user tapped "Install" on → opens its single-type download sheet.
    var installSheetFor by remember { mutableStateOf<CommunityConfigApply.MissingComponent?>(null) }
    // Missing GPU driver the user tapped "Browse all drivers" on → opens the adrenotools driver browser.
    var driverSheetFor by remember { mutableStateOf<CommunityConfigApply.MissingDriver?>(null) }
    // Phase 3 step 2 — LOCAL export/import.
    // The generated export artifact awaiting a Share / Save-to-Downloads choice (null = no export sheet).
    var exportResult by remember { mutableStateOf<ShortcutExporter.ExportResult?>(null) }
    // The shortcut a freshly-picked import file applies to; null means it came from the catalog browser
    // (no target yet) so the picked file is stashed in [importedConfigUri] and a target picker is shown.
    var importPendingTarget by remember { mutableStateOf<Shortcut?>(null) }
    var importedConfigUri by remember { mutableStateOf<Uri?>(null) }
    // Phase 3 (online sharing) — UPLOAD. uploadingConfig gates the busy state; uploadStarted flips the
    // button text from "Preparing…" to "Uploading…" once the real upload begins (after any replace
    // confirm). When the user already shared a config for this game the worker gate is surfaced as a
    // replace-confirm: (existing record, proceed, cancel) — Replace calls proceed(), Cancel calls cancel()
    // so the parked coroutine unwinds cleanly.
    var uploadingConfig by remember { mutableStateOf(false) }
    var uploadStarted by remember { mutableStateOf(false) }
    var replaceUploadPrompt by remember { mutableStateOf<Triple<UploadedConfig, () -> Unit, () -> Unit>?>(null) }
    // Phase 3 (online sharing) — MY UPLOADS. showMyUploads opens the manager dialog; myUploads is the
    // loaded list (null = still loading). The list is expandable (single-expand via expandedUploadSha);
    // the expanded row's inline description editor shares uploadDescText / uploadDescLoading (reloaded on
    // expand). deleteUploadRow drives the delete-confirm sub-dialog.
    var showMyUploads by remember { mutableStateOf(false) }
    // Phase 2 (optional accounts) — the "My account" sheet. Opened from the globe browser's person icon;
    // hosts create/login/reset when logged out and profile + "My uploads" + "Log out" when signed in.
    var showMyAccount by remember { mutableStateOf(false) }
    var myUploads by remember { mutableStateOf<List<MyUploadRow>?>(null) }
    var deleteUploadRow by remember { mutableStateOf<MyUploadRow?>(null) }
    var expandedUploadSha by remember { mutableStateOf<String?>(null) }
    var uploadDescText by remember { mutableStateOf("") }
    var uploadDescLoading by remember { mutableStateOf(false) }
    // A tapped config row → small "Apply to game… | View details" chooser. The pair carries the picked
    // config (a specific uploaded file, or a device-row fallback) plus the in-context shortcut (non-null
    // from the per-shortcut sheet, null from the catalog browser where a target hasn't been chosen yet).
    var configAction by remember { mutableStateOf<Pair<CommunityPick, Shortcut?>?>(null) }
    // The config whose read-only detail page is open (same pick + optional-context-shortcut pair).
    var detailFor by remember { mutableStateOf<Pair<CommunityPick, Shortcut?>?>(null) }
    // Labels of missing components/drivers that resolved after an install (→ checkmark instead of a
    // button). Drivers are namespaced "driver:<wanted>" so they can't collide with component labels.
    val resolvedMissing = remember(applyResult) { mutableStateListOf<String>() }
    // Any install sheet (component OR driver) open → hide EVERY community dialog layer so the
    // ModalBottomSheet isn't rendered behind an AlertDialog's window; they reappear when it closes.
    // The chooser + detail layers join the predicate so the lower community dialogs (match/browser/
    // picker/result) don't stack behind them; the chooser/detail themselves are gated on installSheetOpen.
    val installSheetOpen = installSheetFor != null || driverSheetFor != null
    val communityDialogsGated = installSheetOpen || configAction != null || detailFor != null
    val scope = rememberCoroutineScope()

    // Shared apply runner — used by both the catalog browser and the per-shortcut sheet. Dispatches by
    // pick kind: a specific uploaded file applies THAT file; a device-row fallback applies the
    // best-for-device pick (offline path). Same downstream applyResult → smart-install flow either way.
    val runCommunityApply: (Shortcut, CommunityPick) -> Unit = { sc, pick ->
        applyBusy = true
        applyResult = null
        applyTarget = sc
        val onDone: (CommunityConfigApply.ConfigApplyResult) -> Unit = { res ->
            applyBusy = false
            applyResult = res
        }
        when (pick) {
            is CommunityPick.File -> vm.applyCommunityConfigFile(sc, pick.ref, onDone)
            is CommunityPick.Device -> vm.applyCommunityConfig(sc, pick.game, pick.device, onDone)
        }
    }
    // Kick off the real apply for a config: with an in-context shortcut (per-shortcut sheet) run it
    // straight; without one (browser) fall to the target picker. Reused by BOTH the chooser's "Apply to
    // game…" and the detail dialog's "Apply" so details never duplicates the apply/install flow.
    val startConfigApply: (CommunityPick, Shortcut?) -> Unit = { pick, sc ->
        if (sc != null) runCommunityApply(sc, pick) else applyPicker = pick
    }
    // Pick a target shortcut for a browser-selected config; warn when its game doesn't match.
    val chooseApplyTarget: (Shortcut, CommunityPick) -> Unit = { sc, pick ->
        applyPicker = null
        if (GameMatcher.match(sc.name, listOf(pick.game)).isNotEmpty()) runCommunityApply(sc, pick)
        else applyMismatch = sc to pick
    }

    // Phase 3 step 2 — IMPORT runner. Read + translate + apply an imported file to [sc], funnelling
    // into the SAME applyBusy → applyResult → smart-install flow a browsed config takes. A malformed
    // file returns a clean ok=false result (shown by the existing "Couldn't apply" dialog), never a crash.
    val runImport: (Shortcut, Uri) -> Unit = { sc, uri ->
        applyBusy = true
        applyResult = null
        applyTarget = sc
        vm.importConfigFile(uri, sc) { res ->
            applyBusy = false
            applyResult = res
        }
    }

    // Phase 3 step 2 — EXPORT. Write the generated config to cacheDir/community_configs/export/<file>
    // off-main, then hand it off. Share uses the app's existing FileProvider (${applicationId}.tileprovider,
    // the same authority the save-share + updater use); Save copies it to public Downloads and toasts.
    val shareExport: (ShortcutExporter.ExportResult) -> Unit = { res ->
        exportResult = null
        scope.launch(Dispatchers.IO) {
            val dir = File(context.cacheDir, "community_configs/export").apply { mkdirs() }
            val file = File(dir, res.fileName)
            file.writeText(res.json)
            withContext(Dispatchers.Main) {
                try {
                    val authority = context.packageName + ".tileprovider"
                    val uri = FileProvider.getUriForFile(context, authority, file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, res.game)
                        putExtra(Intent.EXTRA_TEXT, "Bannerlator config for ${res.game}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share config"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Couldn't share the config.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val saveExportToDownloads: (ShortcutExporter.ExportResult) -> Unit = { res ->
        exportResult = null
        scope.launch(Dispatchers.IO) {
            val ok = try {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                // Exported configs live under Download/bannerlator/game-configs/ (created if absent).
                val exportDir = File(downloads, "bannerlator/game-configs")
                if (!exportDir.exists()) exportDir.mkdirs()
                val out = File(exportDir, res.fileName)
                out.writeText(res.json)
                out.setReadable(true, false)
                MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null, null)
                out.absolutePath
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (ok != null) Toast.makeText(context, "Saved to $ok", Toast.LENGTH_LONG).show()
                else Toast.makeText(context, "Couldn't save the config.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Shared "Scrape cover" action so both grid tiles and list rows fire the same flow.
    val scrapeCoverFor: (Shortcut) -> Unit = { shortcut ->
        scrapeTarget = shortcut
        scrapeCovers.clear()
        scrapeLoading = true
        scope.launch(Dispatchers.IO) {
            val json = StarLaunchBridge.sgdbFetchGridsJson(shortcut.name)
            val covers = mutableListOf<Pair<Bitmap, String>>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val thumbUrl = obj.optString("thumb", "")
                    val fullUrl = obj.optString("url", "")
                    if (thumbUrl.isNotEmpty() && fullUrl.isNotEmpty()) {
                        val conn = java.net.URL(thumbUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        val bmp = BitmapFactory.decodeStream(conn.inputStream)
                        conn.disconnect()
                        if (bmp != null) covers.add(bmp to fullUrl)
                    }
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                scrapeCovers.clear()
                scrapeCovers.addAll(covers)
                scrapeLoading = false
            }
        }
    }

    // Shared "Community configs" action — opens the sheet and kicks off the offline-first match.
    val communityConfigsFor: (Shortcut) -> Unit = { shortcut ->
        communityTarget = shortcut
        communityResult = null
        communityLoading = true
        vm.matchCommunityConfigs(shortcut) { result ->
            communityResult = result
            communityLoading = false
        }
    }

    // Post-install fixup shared by the inline installer and the "Browse all versions" fallback sheet:
    // re-read what's on disk off-main, surgically auto-apply the resolved version to the target
    // shortcut, then mark the row done + refresh. Same behaviour the download sheet's onContentChanged had.
    val applyAfterInstall: (CommunityConfigApply.MissingComponent) -> Unit = { mc ->
        val target = applyTarget
        if (target != null) {
            scope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    val installed = com.winlator.star.communityconfigs.InstalledComponents.read(context)
                    // Try the exact wanted version first. If the user installed a CLOSEST build instead
                    // (e.g. a date-stamped FEX like "Fex-20260103" that has no exact catalog match), the
                    // wanted string never re-resolves — so fall back to the NEWEST installed build of this
                    // type, i.e. the one that was just installed, and apply that.
                    if (CommunityConfigApply.applyResolvedComponent(target, mc, installed)) {
                        true
                    } else {
                        val newest = CommunityConfigApply.installedTypeKey(mc.type)?.let { installed.newestToken(it) }
                        newest != null &&
                            CommunityConfigApply.applyResolvedComponent(target, mc.copy(wanted = newest), installed)
                    }
                }
                if (resolved) {
                    if (mc.label !in resolvedMissing) resolvedMissing.add(mc.label)
                    vm.refresh()
                    Toast.makeText(context, "Installed and applied to \"${target.name}\".", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "Installed, but couldn't auto-apply — open \"Browse all versions\" to finish.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun handleShortcutImport(uri: Uri) {
        if (pendingImportContainerIndex >= 0) {
            val result = vm.importShortcut(pendingImportContainerIndex, uri, context)
            when (result) {
                is ImportResult.Success -> {
                    renameDialogContainerIndex = pendingImportContainerIndex
                    renameDialogName = result.shortcutName
                    showRenameDialog = true
                }
                is ImportResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
            pendingImportContainerIndex = -1
        }
    }
    // System SAF picker (secondary).
    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handleShortcutImport(uri)
    }
    // Built-in in-app file picker (primary).
    val importFileInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { handleShortcutImport(it) }
    }
    // Phase 3 step 2 — config-import picker (in-app File Manager, `.json` only). A known
    // [importPendingTarget] applies straight to that shortcut; otherwise (from the catalog browser)
    // the picked file is stashed and a target picker is shown.
    val importConfigInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            InAppFilePicker.pickedUri(result.data)?.let { uri ->
                val target = importPendingTarget
                if (target != null) runImport(target, uri) else importedConfigUri = uri
            }
        }
    }
    // Launch the config-import picker for [target] (null = from the browser → pick a target afterwards).
    val launchConfigImport: (Shortcut?) -> Unit = { target ->
        importPendingTarget = target
        importConfigInAppLauncher.launch(
            InAppFilePicker.buildIntent(context, InAppFilePicker.JSON, "Select a config .json")
        )
    }
    // Open the My-uploads manager (shared by the per-game dialog button AND the My-account sheet).
    val openMyUploads: () -> Unit = {
        myUploads = null
        expandedUploadSha = null
        showMyUploads = true
        vm.loadMyUploads { myUploads = it }
    }
    // Open the My-account sheet (Phase 2), the globe browser's person-icon entry point.
    val openMyAccount: () -> Unit = { showMyAccount = true }
    // Phase 3: the nav-drawer's profile header lands here then flips this one-shot flag — open the sheet.
    LaunchedEffect(AccountUiBus.openMyAccountRequested) {
        if (AccountUiBus.openMyAccountRequested) {
            AccountUiBus.openMyAccountRequested = false
            showMyAccount = true
        }
    }

    val topBarActions = LocalTopBarActions.current
    // LaunchedEffect — not SideEffect — so this runs in the same dispatcher queue as
    // MainActivity's route-change clear (which is a LaunchedEffect). Parent enqueues
    // first and runs first (clears); we enqueue second and run after (sets). A
    // SideEffect would run synchronously during commit, getting steamrolled by the
    // parent's clear when it fires post-commit.
    LaunchedEffect(isGridView) {
        topBarActions.value = {
            IconButton(onClick = { showCommunityBrowser = true }) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = "Community configs",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            IconButton(onClick = { vm.setGridView(!isGridView) }) {
                Icon(
                    imageVector = if (isGridView) Icons.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = if (isGridView) "List view" else "Grid view",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.Filled.SwapVert, contentDescription = "Sort", tint = androidx.compose.ui.graphics.Color.White)
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    val orders = listOf(
                        ShortcutSortOrder.NAME_ASC  to "Name A→Z",
                        ShortcutSortOrder.NAME_DESC to "Name Z→A",
                        ShortcutSortOrder.CONTAINER to "Container",
                    )
                    orders.forEach { (order, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    color = if (sortOrder == order)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { vm.setSortOrder(order); showSortMenu = false },
                        )
                    }
                }
            }
        }
    }

    // Refresh list whenever this screen resumes (consistent with ContainersScreen and SavesScreen)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (shortcuts.isEmpty()) {
                Text(
                    text = "No shortcuts yet.",
                    color = OnSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                AnimatedContent(targetState = isGridView, label = "layout") { grid ->
                    if (grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 120.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(shortcuts, key = { it.file.path }) { shortcut ->
                                ShortcutGridItem(
                                    shortcut = shortcut,
                                    onRun = { runShortcut(activity, shortcut) },
                                    onSettings = { settingsShortcut = shortcut },
                                    onRemove = { confirmRemove = shortcut },
                                    onClone = { cloneTarget = shortcut },
                                    onAddToHome = { addToHomeScreen(context, shortcut) },
                                    onExport = { exportShortcut(context, shortcut) },
                                    onProperties = { propertiesShortcut = shortcut },
                                    onScrapeCover = { scrapeCoverFor(shortcut) },
                                    onCommunityConfigs = { communityConfigsFor(shortcut) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(shortcuts, key = { it.file.path }) { shortcut ->
                                val itemRun = { runShortcut(activity, shortcut) }
                                val itemSettings = { settingsShortcut = shortcut }
                                val itemRemove = { confirmRemove = shortcut }
                                val itemClone = { cloneTarget = shortcut }
                                val itemAddToHome = { addToHomeScreen(context, shortcut) }
                                val itemExport = { exportShortcut(context, shortcut) }
                                val itemProperties = { propertiesShortcut = shortcut }
                                ShortcutItemLayoutL(
                                    shortcut = shortcut,
                                    onRun = itemRun,
                                    onSettings = itemSettings,
                                    onRemove = itemRemove,
                                    onClone = itemClone,
                                    onAddToHome = itemAddToHome,
                                    onExport = itemExport,
                                    onProperties = itemProperties,
                                    onScrapeCover = { scrapeCoverFor(shortcut) },
                                    onCommunityConfigs = { communityConfigsFor(shortcut) },
                                )
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { showImportContainerPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add Shortcut",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }

    // Import container picker
    if (showImportContainerPicker) {
        val containers = vm.containers()
        AlertDialog(
            onDismissRequest = { showImportContainerPicker = false },
            title = { Text("Select container") },
            text = {
                Column {
                    if (containers.isEmpty()) {
                        Text("No containers found.", color = OnSurfaceVariant)
                    } else {
                        containers.forEachIndexed { index, c ->
                            Text(
                                text = c.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showImportContainerPicker = false
                                        pendingImportContainerIndex = index
                                        if (importUseSystemPicker) importFileLauncher.launch("*/*")
                                        else importFileInAppLauncher.launch(
                                            InAppFilePicker.buildIntent(context, InAppFilePicker.SHORTCUT, "Select .exe / .desktop / .lnk")
                                        )
                                    }
                                    .padding(vertical = 12.dp),
                                color = OnSurface,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Checkbox(checked = importUseSystemPicker, onCheckedChange = { importUseSystemPicker = it })
                            Text("Pick via system…", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showImportContainerPicker = false }) { Text("Cancel") } },
        )
    }

    // Rename after import
    if (showRenameDialog) {
        var newName by remember { mutableStateOf(renameDialogName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Shortcut") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Shortcut name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty()) {
                        vm.renameImportedShortcut(renameDialogContainerIndex, renameDialogName, name)
                    }
                    showRenameDialog = false
                    Toast.makeText(context, "Shortcut imported.", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    Toast.makeText(context, "Shortcut imported.", Toast.LENGTH_SHORT).show()
                }) { Text("Skip") }
            },
        )
    }

    // Remove confirmation
    confirmRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove shortcut?") },
            text = { Text("Remove \"${s.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = vm.remove(s, context)
                    confirmRemove = null
                    Toast.makeText(
                        context,
                        if (ok) "Shortcut removed." else "Failed to remove shortcut.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }

    // Clone-to-container dialog
    cloneTarget?.let { s ->
        val containers = vm.containers()
        AlertDialog(
            onDismissRequest = { cloneTarget = null },
            title = { Text("Select container") },
            text = {
                Column {
                    containers.forEach { c ->
                        Text(
                            text = c.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val ok = s.cloneToContainer(c)
                                    cloneTarget = null
                                    Toast.makeText(
                                        context,
                                        if (ok) "Shortcut cloned." else "Failed to clone shortcut.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    if (ok) vm.refresh()
                                }
                                .padding(vertical = 12.dp),
                            color = OnSurface,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { cloneTarget = null }) { Text("Cancel") } },
        )
    }

    // Shortcut properties dialog
    propertiesShortcut?.let { s ->
        val playtimePrefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
        val playtimeKey = "${s.name}_playtime"
        val playCountKey = "${s.name}_play_count"
        val totalMs = playtimePrefs.getLong(playtimeKey, 0L)
        val playCount = playtimePrefs.getInt(playCountKey, 0)
        val seconds = (totalMs / 1000) % 60
        val minutes = (totalMs / (1000 * 60)) % 60
        val hours   = (totalMs / (1000 * 60 * 60)) % 24
        val days    = (totalMs / (1000 * 60 * 60 * 24))
        val formatted = String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds)
        var didReset by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { propertiesShortcut = null },
            title = { Text("Properties") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (didReset) "Number of times played: 0" else "Number of times played: $playCount")
                    Text(if (didReset) "Playtime: 0d 00h 00m 00s" else "Playtime: $formatted")
                    Button(
                        onClick = {
                            playtimePrefs.edit().remove(playtimeKey).remove(playCountKey).apply()
                            didReset = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Reset Properties") }
                }
            },
            confirmButton = { TextButton(onClick = { propertiesShortcut = null }) { Text("Close") } }
        )
    }

    // Scrape cover dialog
    val sc = scrapeTarget
    if (sc != null) {
        AlertDialog(
            onDismissRequest = { scrapeTarget = null },
            title = { Text("Scrape cover for \"${sc.name}\"") },
            text = {
                if (scrapeLoading) {
                    Text("Searching SteamGridDB...", color = OnSurfaceVariant)
                } else if (scrapeCovers.isEmpty()) {
                    Text("No covers found.", color = OnSurfaceVariant)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        scrapeCovers.forEach { (bmp, url) ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        scope.launch(Dispatchers.IO) {
                                            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                            conn.connectTimeout = 15000
                                            conn.readTimeout = 20000
                                            val full = BitmapFactory.decodeStream(conn.inputStream)
                                            conn.disconnect()
                                            if (full != null) {
                                                sc.saveCustomCoverArt(full)
                                                sc.icon = full
                                                val iconsDir = sc.container.getIconsDir(64)
                                                if (iconsDir != null) {
                                                    if (!iconsDir.exists()) iconsDir.mkdirs()
                                                    val iconName = kotlin.runCatching { sc.file.readLines().firstOrNull { it.startsWith("Icon=") }?.substringAfter("Icon=")?.trim() }.getOrNull() ?: sc.name
                                                    FileUtils.saveBitmapToFile(full, File(iconsDir, iconName + ".png"))
                                                }
                                            }
                                            withContext(Dispatchers.Main) {
                                                scrapeTarget = null
                                                vm.reloadShortcut(sc.file.path, full)
                                                Toast.makeText(context, "Cover saved.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 120.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { scrapeTarget = null }) { Text("Cancel") } },
        )
    }

    // Community configs dialog (Phase 1 — match + suggest, read-only)
    if (!communityDialogsGated) communityTarget?.let { s ->
        val dismiss = { communityTarget = null; communityResult = null }
        val communityDialogShape = RoundedCornerShape(28.dp)
        AlertDialog(
            onDismissRequest = dismiss,
            // Drop the dialog surface a notch below the cards' surfaceContainer fill so the config
            // cards + their 1dp outline separate from the background the same way the game/container
            // cards do on the main screen (default surfaceContainerHigh washed the outline out).
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = communityDialogShape,
            // Outline the whole dialog box (AlertDialog has no border param) so it reads as a bordered
            // panel like the catalog browser, matched to the dialog's rounded shape.
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, communityDialogShape),
            title = { Text("Community configs") },
            text = {
                val result = communityResult
                val game = result?.match
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Phase 3 step 2 — local export/import for THIS shortcut. Share generates a config file
                // and opens the Share/Save sheet; Import picks a `.json` and applies it straight to `s`.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.exportShortcutConfig(s) { exportResult = it } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share config")
                    }
                    OutlinedButton(
                        onClick = { launchConfigImport(s) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.FileUpload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                }
                // Phase 3 (online sharing) — UPLOAD this shortcut's effective config to OUR community repo
                // (ns=bannerlator). Disabled + spinner while in flight; if the user already shared one for
                // this game, [replaceUploadPrompt] surfaces a replace-confirm before the upload proceeds.
                OutlinedButton(
                    onClick = {
                        uploadingConfig = true
                        uploadStarted = false
                        vm.uploadShortcutConfig(
                            s,
                            onExisting = { existing, proceed, cancel ->
                                replaceUploadPrompt = Triple(existing, proceed, cancel)
                            },
                            onStart = { uploadStarted = true },
                            onResult = { ok, msg ->
                                uploadingConfig = false
                                uploadStarted = false
                                replaceUploadPrompt = null
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            },
                        )
                    },
                    enabled = !uploadingConfig,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uploadingConfig) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(if (uploadStarted) "Uploading…" else "Preparing…")
                    } else {
                        Icon(Icons.Filled.CloudUpload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Upload to community")
                    }
                }
                // Manage the configs the user has shared (list / delete / edit description). Reinstall-
                // proof: the list hydrates from the durable manifest when SharedPreferences is empty.
                OutlinedButton(
                    onClick = openMyUploads,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AccountCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("My uploads")
                }
                Divider(color = DividerColor)
                OutlinedTextField(
                    value = communitySearch,
                    onValueChange = { q ->
                        communitySearch = q
                        if (q.trim().length >= 2) vm.searchCommunityGames(q) { communitySearchResults = it }
                        else communitySearchResults = emptyList()
                    },
                    label = { Text("Search all games") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (communitySearch.trim().length >= 2) {
                    if (communitySearchResults.isEmpty()) {
                        Text("No games match \"$communitySearch\".", color = OnSurfaceVariant)
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            communitySearchResults.forEach { cg ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            vm.selectCommunityGame(cg) { communityResult = it }
                                            communitySearch = ""
                                            communitySearchResults = emptyList()
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = cg.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${cg.configCount}", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                                    CommunityStoreBadge(isSteam = cg.isSteam)
                                }
                            }
                        }
                    }
                } else {
                when {
                    communityLoading -> Text("Matching \"${s.name}\"…", color = OnSurfaceVariant)
                    game == null -> Text("No auto-match for \"${s.name}\" — search above to pick one.", color = OnSurfaceVariant)
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = game.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = OnSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                CommunityStoreBadge(isSteam = game.isSteam)
                            }
                            val devWord = if (game.devices.size == 1) "device" else "devices"
                            val cfgWord = if (game.configCount == 1) "config" else "configs"
                            Text(
                                text = "${game.configCount} $cfgWord · ${game.devices.size} $devWord",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceVariant,
                            )
                            Text(
                                text = "Your device: ${deviceHeaderLabel(DeviceIdentity.deviceModel(), result?.userHardwareLabel)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceVariant,
                            )
                            // "Matches my device" — filters the per-config list to configs whose
                            // device/soc match your hardware. Mirrors the catalog browser's chip; enabled
                            // only when we actually detected a SoC/GPU to compare against.
                            val uSoc = result?.userSoc
                            val uGpu = result?.userGpu
                            var matchesMine by rememberSaveable(game.identity) { mutableStateOf(false) }
                            FilterChip(
                                selected = matchesMine,
                                onClick = { matchesMine = !matchesMine },
                                label = { Text("Matches my device") },
                                enabled = uSoc != null || uGpu != null,
                            )
                            Divider(color = DividerColor)
                            // One card per uploaded config from the worker (already votes-desc). Offline /
                            // bucket miss → fall back to the per-device index rows so apply-by-device still
                            // works (no vote counts in that mode). Whole card taps → the chooser; the
                            // in-context shortcut `s` is carried so details can preview the diff.
                            // Also look under THIS shortcut's own folder (sanitized the SAME way the
                            // exporter keys uploads) so the user's OWN Bannerlator upload shows up even
                            // though it isn't in the canonical index yet.
                            val myFolder = s.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                            val cfg = rememberGameConfigs(vm, game, extraBannerlatorFolders = listOf(myFolder))
                            when {
                                cfg.loading -> Text("Loading configs…", color = OnSurfaceVariant)
                                cfg.entries.isNotEmpty() -> {
                                    val shown = if (!matchesMine) cfg.entries
                                        else cfg.entries.filter {
                                            GameMatcher.hardwareMatchesUser(uSoc, uGpu, listOf(it.second.device, it.second.soc))
                                        }
                                    if (shown.isEmpty()) {
                                        Text("No uploaded configs match your device.", color = OnSurfaceVariant)
                                    } else {
                                        shown.forEach { (folder, e) ->
                                            val isMatch = (uSoc != null || uGpu != null) &&
                                                GameMatcher.hardwareMatchesUser(uSoc, uGpu, listOf(e.device, e.soc))
                                            CommunityConfigEntryCard(entry = e, isMatch = isMatch) {
                                                configAction = CommunityPick.File(
                                                    game,
                                                    CommunityConfigRef(game, folder, e.filename, e.sha.ifBlank { null }, ns = if (e.appSource == "bannerlator") "bannerlator" else ""),
                                                    e,
                                                ) to s
                                            }
                                        }
                                    }
                                }
                                result?.rankedDevices.isNullOrEmpty() -> Text("No device configs listed.", color = OnSurfaceVariant)
                                else -> {
                                    Text(
                                        "Showing device configs (vote counts unavailable offline).",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OnSurfaceVariant,
                                    )
                                    val hw = result?.userHardwareLabel?.lowercase()
                                    val devs = result?.rankedDevices.orEmpty().let { list ->
                                        if (!matchesMine) list
                                        else list.filter { GameMatcher.deviceMatchesUser(it, uSoc, uGpu) }
                                    }
                                    devs.forEach { d ->
                                        val isMatch = hw != null && (
                                            (d.soc.isNotBlank() && (hw.contains(d.soc.lowercase()) || d.soc.lowercase().contains(hw))) ||
                                            (d.gpu.isNotBlank() && (hw.contains(d.gpu.lowercase()) || d.gpu.lowercase().contains(hw)))
                                        )
                                        CommunityCard(onClick = { configAction = CommunityPick.Device(game, d) to s }) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = d.model.ifBlank { "Unknown device" },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (isMatch) MaterialTheme.colorScheme.primary else OnSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                val sub = listOf(d.gpu, d.soc).filter { it.isNotBlank() }.joinToString(" · ")
                                                if (sub.isNotEmpty()) {
                                                    Text(
                                                        text = sub,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = OnSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
        )

        // Replace-confirm for an already-shared config (surfaced by uploadShortcutConfig's onExisting).
        // Replace calls proceed() to resume the upload; Cancel calls cancel() so the parked coroutine
        // unwinds cleanly, then clears the busy state.
        replaceUploadPrompt?.let { (_, proceed, cancel) ->
            val dismissReplace = {
                replaceUploadPrompt = null
                uploadingConfig = false
                uploadStarted = false
                cancel()
            }
            AlertDialog(
                onDismissRequest = dismissReplace,
                title = { Text("Replace your shared config?") },
                text = {
                    Text(
                        "You already shared a config for \"${s.name}\". Replace it?",
                        color = OnSurface,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        replaceUploadPrompt = null
                        proceed()
                    }) { Text("Replace") }
                },
                dismissButton = {
                    TextButton(onClick = dismissReplace) { Text("Cancel") }
                },
            )
        }
    }

    // Phase 3 (online sharing) — MY UPLOADS manager. A summary header + expandable list of the user's OWN
    // shared configs (reinstall-proof: hydrates from the durable manifest when SharedPreferences is empty).
    // Expanding a row (single-expand via expandedUploadSha) reveals its inline description editor +
    // Save / Delete. The expanded row's description is (re)loaded from the worker by this LaunchedEffect.
    LaunchedEffect(expandedUploadSha, showMyUploads) {
        val sha = expandedUploadSha
        val row = if (showMyUploads && sha != null) myUploads?.firstOrNull { it.record.sha == sha } else null
        if (row != null) {
            uploadDescLoading = true
            uploadDescText = ""
            vm.loadMyUploadDescription(row) { uploadDescText = it; uploadDescLoading = false }
        }
    }
    if (showMyUploads) {
        val myUploadsShape = RoundedCornerShape(28.dp)
        val closeMyUploads = { showMyUploads = false; expandedUploadSha = null }
        AlertDialog(
            onDismissRequest = closeMyUploads,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = myUploadsShape,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, myUploadsShape),
            title = { Text("My uploads") },
            text = {
                when (val rows = myUploads) {
                    null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading…", color = OnSurfaceVariant)
                    }
                    else -> if (rows.isEmpty()) {
                        Text("You haven't shared any configs yet.", color = OnSurfaceVariant)
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Summary header — aggregate across every uploaded config.
                            Text(
                                "Shared ${rows.size} config${if (rows.size == 1) "" else "s"} · ↓ ${rows.sumOf { it.downloads }} · ★ ${rows.sumOf { it.votes }}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFFE0701C),
                            )
                            rows.forEach { row ->
                                val expanded = expandedUploadSha == row.record.sha
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        // Collapsed header — tapping anywhere on it toggles expand.
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                expandedUploadSha = if (expanded) null else row.record.sha
                                            },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    row.record.game,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = OnSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                                    .format(java.util.Date(row.record.date))
                                                val sub = listOf(row.record.device, row.record.soc, dateStr)
                                                    .filter { it.isNotBlank() }.joinToString(" · ") +
                                                    if (!row.stillOnline) " · offline" else ""
                                                Text(sub, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Text("★${row.votes}  ↓${row.downloads}", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                                            Icon(
                                                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = if (expanded) "Collapse" else "Expand",
                                            )
                                        }
                                        if (expanded) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                if (row.stillOnline) "● Online" else "● Removed",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (row.stillOnline) Color(0xFF3BA55D) else MaterialTheme.colorScheme.error,
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            OutlinedTextField(
                                                value = uploadDescText,
                                                onValueChange = { if (it.length <= 500) uploadDescText = it },
                                                label = { Text(if (uploadDescLoading) "Loading description…" else "Description") },
                                                enabled = !uploadDescLoading,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Text("${uploadDescText.length}/500", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                                            Spacer(Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                OutlinedButton(onClick = { deleteUploadRow = row }) {
                                                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Delete")
                                                }
                                                Spacer(Modifier.weight(1f))
                                                Button(
                                                    enabled = !uploadDescLoading,
                                                    onClick = {
                                                        val text = uploadDescText
                                                        vm.editMyUploadDescription(row, text) { ok ->
                                                            Toast.makeText(
                                                                context,
                                                                if (ok) "Description updated." else "Couldn't reach the server.",
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                        }
                                                    },
                                                ) { Text("Save") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = closeMyUploads) { Text("Close") } },
        )
    }

    // Delete-confirm for one of the user's uploads.
    deleteUploadRow?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteUploadRow = null },
            title = { Text("Delete shared config?") },
            text = { Text("Delete your shared config for \"${row.record.game}\"?", color = OnSurface) },
            confirmButton = {
                TextButton(onClick = {
                    deleteUploadRow = null
                    vm.deleteMyUpload(row) { ok ->
                        if (ok) {
                            myUploads = myUploads?.filterNot { it.record.sha == row.record.sha }
                            Toast.makeText(context, "Deleted your shared config.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Couldn't reach the server.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteUploadRow = null }) { Text("Cancel") } },
        )
    }

    // Catalog browser (Part A) — full-catalog entry from the header button.
    if (showCommunityBrowser && !communityDialogsGated) {
        CommunityCatalogBrowser(
            vm = vm,
            onDismiss = { showCommunityBrowser = false },
            // No in-context shortcut from the browser (null) → chooser's "Apply to game…" runs the picker.
            onPick = { pick -> configAction = pick to null },
            // My account — the global entry point (Phase 2); the sheet hosts the "My uploads" button.
            onMyAccount = openMyAccount,
        )
    }

    // Phase 2 (optional accounts) — the My-account sheet. Its "My uploads" button dismisses this and opens
    // the existing My-uploads manager (the per-game dialog still opens My uploads directly, unchanged).
    if (showMyAccount) {
        MyAccountDialog(
            vm = vm,
            onDismiss = { showMyAccount = false },
            onOpenMyUploads = {
                showMyAccount = false
                openMyUploads()
            },
            onLoggedIn = {
                // Phase 4 (cross-device recovery): if the My-uploads sheet is already open behind the
                // account dialog, reload it so the just-restored entries appear immediately.
                if (showMyUploads) vm.loadMyUploads { myUploads = it }
            },
        )
    }

    // Config-row chooser — "Apply to game… | View details" before any apply happens. Gated only on an
    // open install sheet (it IS one of the layers communityDialogsGated hides beneath itself).
    if (!installSheetOpen) configAction?.let { (pick, ctxShortcut) ->
        val subtitle = when (pick) {
            is CommunityPick.File -> "Config from ${pick.entry.device.ifBlank { pick.entry.soc.ifBlank { "that device" } }}."
            is CommunityPick.Device -> "Config from ${pick.device.model.ifBlank { "that device" }}."
        }
        AlertDialog(
            onDismissRequest = { configAction = null },
            title = { Text(pick.game.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
            },
            confirmButton = {
                TextButton(onClick = {
                    configAction = null
                    startConfigApply(pick, ctxShortcut)
                }) { Text("Apply to game…") }
            },
            dismissButton = {
                TextButton(onClick = {
                    configAction = null
                    detailFor = pick to ctxShortcut
                }) { Text("View details") }
            },
        )
    }

    // Read-only Community Config detail page. Loads fetch+translate (+preview when a shortcut is in
    // context) via the VM, then renders provenance + "what it sets" + the pre-apply diff. Apply reuses
    // the same startConfigApply → applyResult → smart-install flow, so details never duplicates apply.
    if (!installSheetOpen) detailFor?.let { (pick, ctxShortcut) ->
        var detail by remember(pick, ctxShortcut) { mutableStateOf<CommunityConfigDetail?>(null) }
        var detailLoading by remember(pick, ctxShortcut) { mutableStateOf(true) }
        var detailFailed by remember(pick, ctxShortcut) { mutableStateOf(false) }
        LaunchedEffect(pick, ctxShortcut) {
            detailLoading = true
            detailFailed = false
            val onDetail: (CommunityConfigDetail?) -> Unit = { d ->
                detail = d
                detailLoading = false
                detailFailed = d == null
            }
            when (pick) {
                is CommunityPick.File -> vm.loadCommunityConfigDetail(pick.ref, ctxShortcut, onDetail)
                is CommunityPick.Device -> vm.loadCommunityConfigDetail(pick.game, pick.device, ctxShortcut, onDetail)
            }
        }
        // Provenance fallback device — the real device row for a device pick, or one synthesized from
        // the uploaded config's own device/soc so the detail page reads identically.
        val provDevice = when (pick) {
            is CommunityPick.File -> CanonicalDevice(pick.entry.device, "", pick.entry.soc)
            is CommunityPick.Device -> pick.device
        }
        CommunityConfigDetailDialog(
            game = pick.game,
            device = provDevice,
            detail = detail,
            loading = detailLoading,
            failed = detailFailed,
            vm = vm,
            onApply = {
                detailFor = null
                startConfigApply(pick, ctxShortcut)
            },
            onDismiss = { detailFor = null },
        )
    }

    // Apply-target picker — choose which of your shortcuts to apply a browser-selected config to.
    if (!communityDialogsGated) applyPicker?.let { pick ->
        val shortcutList = vm.currentShortcuts()
        val fromLabel = when (pick) {
            is CommunityPick.File -> pick.entry.device.ifBlank { pick.entry.soc.ifBlank { "a device" } }
            is CommunityPick.Device -> pick.device.model.ifBlank { "a device" }
        }
        AlertDialog(
            onDismissRequest = { applyPicker = null },
            title = { Text("Apply to game…") },
            text = {
                if (shortcutList.isEmpty()) {
                    Text("You have no shortcuts yet.", color = OnSurfaceVariant)
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "Config from $fromLabel for \"${pick.game.name}\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        shortcutList.forEach { sc ->
                            Text(
                                text = sc.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { chooseApplyTarget(sc, pick) }
                                    .padding(vertical = 12.dp),
                                color = OnSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { applyPicker = null }) { Text("Cancel") } },
        )
    }

    // Mismatch confirmation — target shortcut's game doesn't match the config's game.
    applyMismatch?.let { (sc, pick) ->
        AlertDialog(
            onDismissRequest = { applyMismatch = null },
            title = { Text("Different game") },
            text = { Text("This config is for \"${pick.game.name}\" — apply to \"${sc.name}\" anyway?") },
            confirmButton = {
                TextButton(onClick = {
                    applyMismatch = null
                    runCommunityApply(sc, pick)
                }) { Text("Apply anyway") }
            },
            dismissButton = { TextButton(onClick = { applyMismatch = null }) { Text("Cancel") } },
        )
    }

    // Phase 3 step 2 — EXPORT hand-off. After a config artifact is generated, offer the two local
    // sinks: Share (ACTION_SEND via the app FileProvider) or Save to the public Downloads folder.
    exportResult?.let { res ->
        AlertDialog(
            onDismissRequest = { exportResult = null },
            title = { Text("Share this game's config") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A config file for \"${res.game}\" is ready.", color = OnSurface)
                    Text(res.fileName, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { saveExportToDownloads(res) }) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save to Downloads")
                    }
                    TextButton(onClick = { shareExport(res) }) {
                        Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { exportResult = null }) { Text("Cancel") } },
        )
    }

    // Phase 3 step 2 — IMPORT target picker. Reached only from the catalog browser (no in-context
    // shortcut): mirrors the apply-target picker — pick which shortcut the imported file applies to.
    importedConfigUri?.let { uri ->
        val shortcutList = vm.currentShortcuts()
        AlertDialog(
            onDismissRequest = { importedConfigUri = null },
            title = { Text("Apply imported config to…") },
            text = {
                if (shortcutList.isEmpty()) {
                    Text("You have no shortcuts yet.", color = OnSurfaceVariant)
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    ) {
                        shortcutList.forEach { sc ->
                            Text(
                                text = sc.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        importedConfigUri = null
                                        runImport(sc, uri)
                                    }
                                    .padding(vertical = 12.dp),
                                color = OnSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { importedConfigUri = null }) { Text("Cancel") } },
        )
    }

    // Applying spinner (blocking) while the config is fetched + merged.
    if (applyBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Applying config") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Fetching and merging…", color = OnSurfaceVariant)
                }
            },
            confirmButton = {},
        )
    }

    // Result summary — what changed + install / Proton advisories. Hidden while a component-install
    // sheet is open (a ModalBottomSheet renders behind an AlertDialog's window); it reappears — with
    // any new checkmark — once the sheet closes, since applyResult / resolvedMissing persist.
    if (!communityDialogsGated) applyResult?.let { res ->
        // Downloadable catalog for the inline installer — one fetch per result, per missing type.
        // null value = still loading (row shows a spinner instead of a button).
        val installCm = remember(res) { ContentsManager(context) }
        var remoteByType by remember(res) {
            mutableStateOf<Map<ContentProfile.ContentType, List<ContentProfile>>?>(null)
        }
        LaunchedEffect(res) {
            if (res.missingComponents.isEmpty()) { remoteByType = emptyMap(); return@LaunchedEffect }
            val types = res.missingComponents.map { it.type }.toSet()
            remoteByType = withContext(Dispatchers.IO) {
                val json = Downloader.downloadString(ContentsManager.REMOTE_PROFILES)
                if (json != null) installCm.setRemoteProfiles(json) else installCm.syncContents()
                types.associateWith { t ->
                    (installCm.getProfiles(t) ?: emptyList()).filter { it.remoteUrl != null }
                }
            }
        }
        AlertDialog(
            onDismissRequest = { applyResult = null },
            title = { Text(if (res.ok) "Config applied" else "Couldn't apply") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(res.message, color = OnSurface)
                    if (res.changed.isNotEmpty()) {
                        Divider(color = DividerColor)
                        Text("Changed", style = MaterialTheme.typography.labelLarge, color = OnSurface)
                        res.changed.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant) }
                    }
                    // Missing components — smart inline installer: exact-match confirm, or a shortlist of
                    // the closest catalog versions, with "Browse all versions" as the full-menu fallback.
                    if (res.missingComponents.isNotEmpty()) {
                        Divider(color = DividerColor)
                        Text("Needs a component", style = MaterialTheme.typography.labelLarge, color = OnSurface)
                        res.missingComponents.forEach { mc ->
                            SmartComponentInstallRow(
                                mc = mc,
                                done = mc.label in resolvedMissing,
                                candidates = remoteByType?.get(mc.type) ?: emptyList(),
                                catalogLoading = remoteByType == null,
                                cm = installCm,
                                onBrowseAll = { installSheetFor = mc },
                                onProfileInstalled = { applyAfterInstall(mc) },
                            )
                        }
                    }
                    // Missing GPU driver(s) — smart inline installer over all 5 adrenotools repos:
                    // every exact-version repo-variant as its own quick-install, then closest others,
                    // then the full driver browser. Adreno-only (the apply engine gates emission).
                    if (res.missingDrivers.isNotEmpty()) {
                        Divider(color = DividerColor)
                        Text("Needs a GPU driver", style = MaterialTheme.typography.labelLarge, color = OnSurface)
                        res.missingDrivers.forEach { md ->
                            SmartDriverInstallRow(
                                md = md,
                                vm = vm,
                                done = ("driver:" + md.wanted) in resolvedMissing,
                                onBrowseAll = { driverSheetFor = md },
                                onApplied = { driverId ->
                                    val target = applyTarget
                                    if (target != null) {
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                CommunityConfigApply.applyResolvedDriver(target, driverId)
                                            }
                                            if (ok) {
                                                if (("driver:" + md.wanted) !in resolvedMissing) {
                                                    resolvedMissing.add("driver:" + md.wanted)
                                                }
                                                vm.refresh()
                                                Toast.makeText(context, "Driver installed and applied to \"${target.name}\".", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Installed, but couldn't apply the driver.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (res.advisories.isNotEmpty()) {
                        Divider(color = DividerColor)
                        Text("Heads up", style = MaterialTheme.typography.labelLarge, color = OnSurface)
                        res.advisories.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { applyResult = null }) { Text("Done") } },
        )
    }

    // Install a config's missing component via the app's normal single-type download sheet. When a
    // build gets installed we re-resolve it against what's now on disk and, if it resolves, surgically
    // write the version sub-field back to the target shortcut (same merge the apply engine uses).
    installSheetFor?.let { mc ->
        ContentDownloadSheet(
            contentType = mc.type,
            onDismiss = { installSheetFor = null },
            onContentChanged = { applyAfterInstall(mc) },
        )
    }

    // "Browse all drivers" fallback — the full adrenotools driver browser (5 source chips). When a
    // driver installs, surgically write its id back to the target shortcut and mark the row done.
    driverSheetFor?.let { md ->
        AdrenoDriverDownloadSheet(
            onDismiss = { driverSheetFor = null },
            onDriverInstalled = { driverId ->
                driverSheetFor = null
                val target = applyTarget
                if (target != null) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            CommunityConfigApply.applyResolvedDriver(target, driverId)
                        }
                        if (ok) {
                            if (("driver:" + md.wanted) !in resolvedMissing) {
                                resolvedMissing.add("driver:" + md.wanted)
                            }
                            vm.refresh()
                            Toast.makeText(context, "Driver applied to \"${target.name}\".", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )
    }

    // Compose shortcut settings dialog
    settingsShortcut?.let { s ->
        ShortcutSettingsDialogScreen(
            shortcut = s,
            onDismiss = { settingsShortcut = null; vm.refresh() }
        )
    }
}

// Small "BANNERLATOR" source pill for configs shared through our own repo (app_source=bannerlator), so
// users can tell them apart from BannerHub-sourced configs. Subtle orange fill, same pill shape as
// [CommunityStoreBadge].
@Composable
private fun BannerlatorSourceBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFE0701C))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "BANNERLATOR",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

// Small Steam / Title provenance badge for the community-config sheet header.
@Composable
private fun CommunityStoreBadge(isSteam: Boolean) {
    val bg = if (isSteam) MaterialTheme.colorScheme.primary else SurfaceVariantColor
    val fg = if (isSteam) MaterialTheme.colorScheme.onPrimary else OnSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (isSteam) "STEAM" else "TITLE",
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

// Map a worker account error code to a human message. Reset reads "invalid" as a bad recovery key; every
// other flow reads it as bad credentials; unknown / "network" collapses to a connection message.
private fun accountErrorMessage(code: String, isReset: Boolean): String = when (code) {
    "invalid_username" -> "Usernames are 3–20 characters: letters, numbers, _ or -."
    "username_reserved" -> "That username is reserved — pick another."
    "weak_password" -> "Password must be at least 6 characters."
    "username_taken" -> "That username is taken."
    "rate_limited" -> "Too many attempts — please wait a bit and try again."
    "invalid" -> if (isReset) "That recovery key isn't right for this username." else "Wrong username or password."
    else -> "Couldn't reach the server. Check your connection and try again."
}

/** Phase 3 (optional accounts) — the worker's typed avatar-upload rejections, in plain language. */
private fun avatarErrorMessage(code: String): String = when (code) {
    "bad_type" -> "That image type isn't supported — pick a JPEG, PNG, or WebP."
    "bad_size" -> "That image is too large — pick a smaller one."
    "bad_image" -> "That file isn't a valid image."
    "not_signed_in" -> "Sign in first to set a profile picture."
    else -> "Couldn't reach the server. Check your connection and try again."
}

/**
 * Phase 3 (optional accounts) — decode [uri] downscaled (longest side ~512px via [ImageUtils]) and
 * re-encode to a JPEG ByteArray that fits the worker's 512KB cap, dropping quality until it does. Runs
 * off the main thread (bitmap work + IO); returns null on any read/decode failure so the caller degrades
 * to a toast rather than crashing.
 */
private fun compressAvatar(context: Context, uri: Uri): ByteArray? {
    return try {
        val bitmap = com.winlator.star.core.ImageUtils.getBitmapFromUri(context, uri, 512) ?: return null
        val maxBytes = 512 * 1024
        var quality = 85
        var bytes: ByteArray
        do {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 10
        } while (bytes.size > maxBytes && quality >= 35)
        if (bytes.size > maxBytes) null else bytes
    } catch (e: Exception) {
        null
    }
}

// Phase 2 (optional accounts) — the "My account" sheet. Accounts are OPTIONAL: they only let a user
// recover / attribute the community configs they share. Styled like the other community dialogs (outlined
// box, orange accent). Three states:
//  - LOGGED OUT: a Create / Login tab pair, plus a "Forgot password?" reset (username + recovery key +
//    new password). Passwords go only to the worker over HTTPS — never logged, never stored locally.
//  - AFTER CREATE: the one-time recovery key with a Copy button + an "I've saved it" confirm.
//  - LOGGED IN: the avatar (tap or "Change picture" → system image picker → ≤512KB JPEG → upload) + the
//    username, "Show my recovery key", "My uploads", and "Log out".
@Composable
private fun MyAccountDialog(
    vm: ShortcutsViewModel,
    onDismiss: () -> Unit,
    onOpenMyUploads: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(28.dp)
    val orange = Color(0xFFE0701C)

    // Local mirror of the (non-Compose) AccountManager state; re-read after every action that changes it.
    var account by remember { mutableStateOf(AccountManager.current(context)) }
    // Non-null right after a successful create → show the one-time recovery key before anything else.
    var justCreated by remember { mutableStateOf<AccountManager.CreateData?>(null) }

    // Shared form state (logged-out). Passwords live ONLY in this transient field state, never persisted.
    var tab by remember { mutableStateOf(0) } // 0 = Create, 1 = Login
    var showReset by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var recoveryKeyInput by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Logged-in: reveal the saved recovery key on demand.
    var revealRecovery by remember { mutableStateOf(false) }

    // Phase 3 (optional accounts) — AVATAR. avatarBusy gates the upload spinner. The cache-bust now lives in
    // AccountManager's avatar_version (Phase 4): uploadAvatar bumps it, so account.displayAvatarUrl below
    // changes and every avatar surface (this dialog + the ☰ + the drawer + the browser 👤) refetches in
    // lockstep. The picker compresses to ≤512KB JPEG off-main, then uploads via AccountManager.
    val scope = rememberCoroutineScope()
    var avatarBusy by remember { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        avatarBusy = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { compressAvatar(context, uri) }
            if (bytes == null) {
                avatarBusy = false
                Toast.makeText(context, "Couldn't read that image.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                AccountManager.uploadAvatar(context, bytes, "image/jpeg")
            }
            avatarBusy = false
            when (result) {
                is AccountManager.AccountResult.Success -> {
                    // uploadAvatar already bumped avatar_version → re-reading the account yields a fresh
                    // displayAvatarUrl, and refresh() propagates it to the ☰ / drawer / browser 👤 too.
                    account = AccountManager.current(context)
                    AccountUiBus.refresh(context)
                    Toast.makeText(context, "Profile picture updated.", Toast.LENGTH_SHORT).show()
                }
                is AccountManager.AccountResult.Error ->
                    Toast.makeText(context, avatarErrorMessage(result.code), Toast.LENGTH_LONG).show()
            }
        }
    }

    @Composable
    fun ErrorText() {
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = orange)
                Text("My account")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    // --- AFTER CREATE: one-time recovery key ---------------------------------------
                    justCreated != null -> {
                        val data = justCreated!!
                        Text(
                            "Account \"${data.username}\" created.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurface,
                        )
                        Text("Your recovery key", style = MaterialTheme.typography.labelLarge, color = orange)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, orange),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                data.recoveryKey,
                                style = MaterialTheme.typography.titleMedium,
                                color = OnSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                        Text(
                            "⚠️ Save this — it's the only way to reset your password if you forget it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(data.recoveryKey))
                                Toast.makeText(context, "Recovery key copied.", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy")
                            }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = {
                                justCreated = null
                                account = AccountManager.current(context)
                                AccountUiBus.refresh(context)
                            }) { Text("I've saved it") }
                        }
                    }

                    // --- LOGGED IN: profile + actions ---------------------------------------------
                    account != null -> {
                        val acc = account!!
                        // Versioned URL from AccountManager (Phase 4) — bumped on every picture change.
                        val displayAvatarUrl = acc.displayAvatarUrl
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                AccountAvatar(
                                    avatarUrl = displayAvatarUrl,
                                    size = 48.dp,
                                    fallbackTint = orange,
                                    modifier = Modifier.clickable(enabled = !avatarBusy) {
                                        avatarPicker.launch("image/*")
                                    },
                                )
                                if (avatarBusy) {
                                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    acc.username,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = OnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    onClick = { avatarPicker.launch("image/*") },
                                    enabled = !avatarBusy,
                                    contentPadding = PaddingValues(vertical = 2.dp),
                                ) { Text("Change picture", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        Divider(color = DividerColor)
                        OutlinedButton(
                            onClick = { revealRecovery = !revealRecovery },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (revealRecovery) "Hide my recovery key" else "Show my recovery key") }
                        if (revealRecovery) {
                            val key = AccountManager.recoveryKey(context)
                            if (key != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, orange),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(key, style = MaterialTheme.typography.titleMedium, color = OnSurface, modifier = Modifier.weight(1f))
                                        IconButton(onClick = {
                                            clipboard.setText(AnnotatedString(key))
                                            Toast.makeText(context, "Recovery key copied.", Toast.LENGTH_SHORT).show()
                                        }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy recovery key", modifier = Modifier.size(18.dp)) }
                                    }
                                }
                            } else {
                                Text(
                                    "No recovery key is saved on this device.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceVariant,
                                )
                            }
                        }
                        OutlinedButton(onClick = onOpenMyUploads, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.CloudUpload, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("My uploads")
                        }
                        OutlinedButton(
                            onClick = {
                                AccountManager.logout(context)
                                account = null
                                revealRecovery = false
                                username = ""; password = ""; error = null
                                AccountUiBus.refresh(context)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Log out") }
                    }

                    // --- LOGGED OUT: reset flow ---------------------------------------------------
                    showReset -> {
                        Text("Reset password", style = MaterialTheme.typography.labelLarge, color = orange)
                        Text(
                            "Enter your username, your recovery key, and a new password.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it; error = null },
                            label = { Text("Username") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = recoveryKeyInput,
                            onValueChange = { recoveryKeyInput = it; error = null },
                            label = { Text("Recovery key") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it; error = null },
                            label = { Text("New password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ErrorText()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showReset = false; error = null }, enabled = !busy) { Text("Back") }
                            Spacer(Modifier.weight(1f))
                            Button(
                                enabled = !busy && username.isNotBlank() && recoveryKeyInput.isNotBlank() && newPassword.isNotBlank(),
                                onClick = {
                                    busy = true; error = null
                                    vm.resetAccountPassword(username.trim(), recoveryKeyInput.trim(), newPassword) { result ->
                                        busy = false
                                        when (result) {
                                            is AccountManager.AccountResult.Success -> {
                                                account = AccountManager.current(context)
                                                AccountUiBus.refresh(context)
                                                showReset = false
                                                password = ""; newPassword = ""; recoveryKeyInput = ""
                                                Toast.makeText(context, "Password reset — you're signed in.", Toast.LENGTH_SHORT).show()
                                            }
                                            is AccountManager.AccountResult.Error ->
                                                error = accountErrorMessage(result.code, isReset = true)
                                        }
                                    }
                                },
                            ) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Reset") }
                        }
                    }

                    // --- LOGGED OUT: create / login tabs ------------------------------------------
                    else -> {
                        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = orange) {
                            Tab(selected = tab == 0, onClick = { tab = 0; error = null }, text = { Text("Create") })
                            Tab(selected = tab == 1, onClick = { tab = 1; error = null }, text = { Text("Log in") })
                        }
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it; error = null },
                            label = { Text("Username") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; error = null },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (tab == 0) {
                            Text(
                                "This is just to recover your shared configs — use a throwaway password, not one you use elsewhere.",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceVariant,
                            )
                        }
                        ErrorText()
                        Button(
                            enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true; error = null
                                if (tab == 0) {
                                    vm.createAccount(username.trim(), password) { result ->
                                        busy = false
                                        when (result) {
                                            is AccountManager.AccountResult.Success -> {
                                                justCreated = result.data
                                                password = ""
                                            }
                                            is AccountManager.AccountResult.Error ->
                                                error = accountErrorMessage(result.code, isReset = false)
                                        }
                                    }
                                } else {
                                    vm.loginAccount(username.trim(), password) { result ->
                                        busy = false
                                        when (result) {
                                            is AccountManager.AccountResult.Success -> {
                                                account = AccountManager.current(context)
                                                AccountUiBus.refresh(context)
                                                password = ""
                                                // Phase 4: the login already folded the account's uploads into
                                                // the local store — refresh My uploads if that sheet is open.
                                                onLoggedIn()
                                                Toast.makeText(context, "Signed in.", Toast.LENGTH_SHORT).show()
                                            }
                                            is AccountManager.AccountResult.Error ->
                                                error = accountErrorMessage(result.code, isReset = false)
                                        }
                                    }
                                }
                            },
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(if (tab == 0) "Create account" else "Log in")
                        }
                        TextButton(
                            onClick = { showReset = true; error = null },
                            enabled = !busy,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Forgot password? Reset with recovery key") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { if (!busy) onDismiss() }) { Text("Close") } },
    )
}

// One missing-component row on the "Config applied" screen — the SMART inline installer. Resolves the
// config's wanted version against the downloadable catalog and offers the shortest path:
//  - exact match  → "Install" → a small confirm → inline download+install → auto-apply → checkmark.
//  - no exact      → "Install" reveals the ~3 closest versions (press = install, no confirm) plus a
//                    "Browse all versions…" link that opens the full single-type download sheet.
// Download + install reuse the same downloadToCache / installContent path as the sheet; the actual
// version write-back (auto-apply) happens in the parent via [onProfileInstalled].
@Composable
private fun SmartComponentInstallRow(
    mc: CommunityConfigApply.MissingComponent,
    done: Boolean,
    candidates: List<ContentProfile>,
    catalogLoading: Boolean,
    cm: ContentsManager,
    onBrowseAll: () -> Unit,
    onProfileInstalled: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val installedBlue = Color(0xFF4FC3F7) // intentional: matches the sheet's installed/in-use status blue

    val shortlist = remember(candidates, mc.wanted) {
        CommunityConfigApply.rankVersions(mc.wanted, candidates)
    }

    var expanded by remember { mutableStateOf(false) }                       // shortlist revealed (no-exact case)
    var confirmProfile by remember { mutableStateOf<ContentProfile?>(null) } // exact-match confirm
    var busy by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }                     // false = downloading phase
    var progress by remember { mutableStateOf(0f) }

    fun install(profile: ContentProfile) {
        confirmProfile = null
        expanded = false
        busy = true
        installing = false
        progress = 0f
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                downloadToCache(context, profile) { frac -> activity?.runOnUiThread { progress = frac } }
            }
            if (uri == null) {
                busy = false
                Toast.makeText(context, "Download failed.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            installing = true
            progress = 0f
            // installContent already marshals onProgress / onDone back to the UI thread.
            installContent(context, cm, uri, onProgress = { f, _ -> progress = maxOf(progress, f) }) { ok ->
                busy = false
                if (ok) onProfileInstalled()
                else Toast.makeText(context, "Install failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "• ${mc.label}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            when {
                done -> Icon(
                    Icons.Filled.CheckCircle, contentDescription = "Installed",
                    tint = installedBlue, modifier = Modifier.size(20.dp),
                )
                busy || catalogLoading -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> {
                    val exact = shortlist.exact
                    TextButton(onClick = {
                        when {
                            exact != null -> confirmProfile = exact
                            shortlist.closest.isEmpty() -> onBrowseAll()
                            else -> expanded = !expanded
                        }
                    }) { Text("Install", color = MaterialTheme.colorScheme.primary) }
                }
            }
        }

        // Progress line under the label while downloading / installing.
        if (busy) {
            val frac = progress.coerceIn(0f, 1f)
            Text(
                if (installing) "Installing…" else "Downloading ${(frac * 100).toInt()}%…",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp),
            )
            LinearProgressIndicator(
                progress = frac,
                modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 2.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Shortlist (no exact match) — the ~3 closest versions + "Browse all versions".
        if (!busy && !done && expanded && shortlist.exact == null) {
            Column(
                modifier = Modifier.padding(start = 10.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                shortlist.closest.forEach { p ->
                    Text(
                        "Install ${p.verName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { install(p) }
                            .padding(vertical = 6.dp),
                    )
                }
                Text(
                    "Browse all versions…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = false; onBrowseAll() }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }

    // Exact-match confirm dialog (stacks over the result dialog).
    confirmProfile?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmProfile = null },
            title = { Text("Install ${p.verName}?") },
            text = { Text("Download and install ${mc.type} ${p.verName}, then apply it to this shortcut?") },
            confirmButton = { TextButton(onClick = { install(p) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { confirmProfile = null }) { Text("Cancel") } },
        )
    }
}

// One missing-GPU-driver row on the "Config applied" screen — the SMART inline adrenotools installer.
// Mirrors [SmartComponentInstallRow] but the catalog is the 5 remote Turnip repos (fetched on expand)
// and the pick axis is repo-source at a given mesa version:
//  - "Install" fetches+ranks, then reveals EVERY exact-version repo-variant as its own quick-install
//    (labelled "<source> · <displayName>" so identical versions are distinguishable), the ~3 closest
//    OTHER versions, and a "Browse all drivers…" link to the full driver browser.
//  - each quick-install → a small confirm → inline download+install → auto-apply → checkmark.
// Only reached on Adreno GPUs (the apply engine only emits MissingDriver there).
@Composable
private fun SmartDriverInstallRow(
    md: CommunityConfigApply.MissingDriver,
    vm: ShortcutsViewModel,
    done: Boolean,
    onBrowseAll: () -> Unit,
    onApplied: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedBlue = Color(0xFF4FC3F7) // matches the component row's installed/in-use status blue
    val repo = remember { RemoteDriverRepository(context) }

    val label = remember(md) {
        buildString {
            append("config wants ")
            append(md.wanted)
            md.current?.let { append("; you have ").append(it) }
        }
    }

    var loading by remember { mutableStateOf(false) }                         // fetching + ranking repos
    var shortlist by remember { mutableStateOf<CommunityConfigApply.DriverShortlist?>(null) }
    var expanded by remember { mutableStateOf(false) }                        // variants revealed
    var confirmEntry by remember { mutableStateOf<RemoteDriverEntry?>(null) } // per-variant confirm
    var busy by remember { mutableStateOf(false) }                           // download/install running
    var installing by remember { mutableStateOf(false) }                     // false = downloading phase
    var progress by remember { mutableStateOf(0) }                           // 0..100

    // Decide what to reveal once the shortlist is known: no options at all → open the full browser.
    fun reveal(sl: CommunityConfigApply.DriverShortlist) {
        if (sl.exactMatches.isEmpty() && sl.closest.isEmpty()) onBrowseAll() else expanded = true
    }

    fun onInstallClick() {
        val sl = shortlist
        if (sl != null) { reveal(sl); return }
        if (loading) return
        loading = true
        vm.fetchDriverShortlist(md.wanted) { fetched ->
            shortlist = fetched
            loading = false
            reveal(fetched)
        }
    }

    fun install(entry: RemoteDriverEntry) {
        confirmEntry = null
        expanded = false
        busy = true
        installing = false
        progress = 0
        scope.launch {
            repo.downloadEntry(entry) { pct -> progress = pct }.fold(
                onSuccess = { file ->
                    installing = true
                    val driverId = withContext(Dispatchers.IO) {
                        AdrenotoolsManager(context).installDriver(Uri.fromFile(file))
                    }
                    file.delete()
                    busy = false
                    if (driverId.isNotEmpty()) onApplied(driverId)
                    else Toast.makeText(context, "Install failed — invalid driver package", Toast.LENGTH_LONG).show()
                },
                onFailure = { t ->
                    busy = false
                    Toast.makeText(context, "Download failed: ${t.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "• $label",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            when {
                done -> Icon(
                    Icons.Filled.CheckCircle, contentDescription = "Installed",
                    tint = installedBlue, modifier = Modifier.size(20.dp),
                )
                busy || loading -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> TextButton(onClick = { onInstallClick() }) {
                    Text("Install", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Progress line under the label while downloading / installing.
        if (busy) {
            val frac = (progress / 100f).coerceIn(0f, 1f)
            Text(
                if (installing) "Installing…" else "Downloading $progress%…",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp),
            )
            LinearProgressIndicator(
                progress = frac,
                modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 2.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Revealed options: exact-version repo-variants first (each its own quick-install), then the
        // closest OTHER versions, then the full browser.
        val sl = shortlist
        if (!busy && !done && expanded && sl != null) {
            Column(
                modifier = Modifier.padding(start = 10.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                sl.exactMatches.forEach { e ->
                    Text(
                        "Install ${e.source} · ${e.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { confirmEntry = e }
                            .padding(vertical = 6.dp),
                    )
                }
                sl.closest.forEach { e ->
                    Text(
                        "Install ${e.source} · ${e.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { confirmEntry = e }
                            .padding(vertical = 6.dp),
                    )
                }
                Text(
                    "Browse all drivers…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = false; onBrowseAll() }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }

    // Per-variant confirm dialog (stacks over the result dialog).
    confirmEntry?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmEntry = null },
            title = { Text("Quick install ${e.displayName}?") },
            text = { Text("Download and install this Turnip driver from ${e.source}, then apply it to this shortcut?") },
            confirmButton = { TextButton(onClick = { install(e) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { confirmEntry = null }) { Text("Cancel") } },
        )
    }
}

private enum class CatalogStoreFilter { ALL, STEAM, TITLE }
private enum class CatalogSort { CONFIGS, NAME, DEVICES }

// Full-catalog browser (Part A) — a catalog-first entry from the header. Lists every game in the
// community index with search / device + store filters / sort; a tapped game opens its per-device
// config list (user's-hardware first), and a device row starts the Phase 2 apply flow.
@Composable
private fun CommunityCatalogBrowser(
    vm: ShortcutsViewModel,
    onDismiss: () -> Unit,
    onPick: (CommunityPick) -> Unit,
    onMyAccount: () -> Unit,
) {
    val context = LocalContext.current
    var catalog by remember { mutableStateOf<CommunityCatalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    // True while a manual index refresh is in flight (disables the button + shows a spinner).
    var refreshing by remember { mutableStateOf(false) }
    // Filters/search/selection survive rotation (rememberSaveable) so the user keeps their place;
    // the drilled-in game is keyed by its identity string (CanonicalGame isn't itself saveable).
    var query by rememberSaveable { mutableStateOf("") }
    var matchesMyDevice by rememberSaveable { mutableStateOf(false) }
    var storeFilter by rememberSaveable { mutableStateOf(CatalogStoreFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(CatalogSort.CONFIGS) }
    var selectedIdentity by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.getCommunityCatalog { catalog = it; loading = false } }

    val userSoc = catalog?.userSoc
    val userGpu = catalog?.userGpu
    val games = catalog?.games ?: emptyList()
    val selectedGame = selectedIdentity?.let { id -> games.firstOrNull { it.identity == id } }

    val visible: List<CanonicalGame> = remember(games, query, matchesMyDevice, storeFilter, sort, userSoc, userGpu) {
        val base = if (query.trim().length >= 2) GameMatcher.search(query, games, limit = 200) else games
        val filtered = base.asSequence()
            .filter { g ->
                when (storeFilter) {
                    CatalogStoreFilter.ALL -> true
                    CatalogStoreFilter.STEAM -> g.isSteam
                    CatalogStoreFilter.TITLE -> !g.isSteam
                }
            }
            .filter { g ->
                !matchesMyDevice || g.devices.any { GameMatcher.deviceMatchesUser(it, userSoc, userGpu) }
            }
            .toList()
        // Preserve search relevance while a query is active; otherwise honour the chosen sort.
        if (query.trim().length >= 2) filtered
        else when (sort) {
            CatalogSort.CONFIGS -> filtered.sortedByDescending { it.configCount }
            CatalogSort.NAME -> filtered.sortedBy { it.name.lowercase() }
            CatalogSort.DEVICES -> filtered.sortedByDescending { it.devices.size }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            // Outline the whole popup so the panel reads as a distinct bordered box (like the cards)
            // instead of bleeding edge-to-edge into the background behind the dialog.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            // Game-list controls (device line + search + filter/sort chips + count). Extracted as a
            // local composable so portrait (stacked) and landscape (left column) share one definition.
            @Composable
            fun ListControls(modifier: Modifier) {
                Column(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Your device: ${deviceHeaderLabel(catalog?.deviceModel, catalog?.hardwareLabel)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search all games") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Store filter + "matches my device".
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = storeFilter == CatalogStoreFilter.ALL, onClick = { storeFilter = CatalogStoreFilter.ALL }, label = { Text("All") })
                        FilterChip(selected = storeFilter == CatalogStoreFilter.STEAM, onClick = { storeFilter = CatalogStoreFilter.STEAM }, label = { Text("Steam") })
                        FilterChip(selected = storeFilter == CatalogStoreFilter.TITLE, onClick = { storeFilter = CatalogStoreFilter.TITLE }, label = { Text("Title") })
                    }
                    FilterChip(
                        selected = matchesMyDevice,
                        onClick = { matchesMyDevice = !matchesMyDevice },
                        label = { Text("Matches my device") },
                        enabled = userSoc != null || userGpu != null,
                    )
                    // Sort.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sort:", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                        FilterChip(selected = sort == CatalogSort.CONFIGS, onClick = { sort = CatalogSort.CONFIGS }, label = { Text("Configs") })
                        FilterChip(selected = sort == CatalogSort.NAME, onClick = { sort = CatalogSort.NAME }, label = { Text("Name") })
                        FilterChip(selected = sort == CatalogSort.DEVICES, onClick = { sort = CatalogSort.DEVICES }, label = { Text("Devices") })
                    }
                    Text(
                        "${visible.size} game${if (visible.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceVariant,
                    )
                }
            }

            // The scrollable game list (or its empty state). `modifier` sizes the LazyColumn.
            @Composable
            fun GameList(modifier: Modifier) {
                if (visible.isEmpty()) {
                    Text(
                        "No games match your filters.",
                        color = OnSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = modifier,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.identity }) { g ->
                            CommunityGameRow(game = g, onClick = { selectedIdentity = g.identity })
                        }
                    }
                }
            }

            Column {
                // Title bar (with a back affordance when drilled into a game).
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectedGame != null) {
                        IconButton(onClick = { selectedIdentity = null }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                    Text(
                        text = selectedGame?.name ?: "Community configs",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = if (selectedGame == null) 4.dp else 0.dp),
                    )
                    // Force-refresh the community index (bypass the 24h cache) so freshly-folded uploads
                    // appear now. Only at the top level; spinner + disabled while refreshing.
                    if (selectedGame == null) {
                        IconButton(
                            onClick = {
                                refreshing = true
                                vm.refreshCommunityIndex { fresh ->
                                    refreshing = false
                                    if (fresh != null) {
                                        catalog = fresh
                                        Toast.makeText(
                                            context,
                                            "Community index refreshed (${fresh.games.size} games)",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Couldn't refresh the community index.",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            enabled = !refreshing,
                        ) {
                            if (refreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh community index")
                            }
                        }
                    }
                    // My account — the global entry point (Phase 2). Opens the SAME My-account sheet as the
                    // nav-drawer, and reads the SAME AccountUiBus state (Phase 4) so signed-in + a picture →
                    // this 👤 shows the user's avatar (versioned, in lockstep with the ☰ / drawer). The 🌐
                    // globe that OPENS this browser stays a globe — only this person icon becomes the avatar.
                    if (selectedGame == null) {
                        val myAvatarUrl = AccountUiBus.account?.displayAvatarUrl
                        IconButton(onClick = onMyAccount) {
                            if (myAvatarUrl != null) {
                                AccountAvatar(avatarUrl = myAvatarUrl, size = 28.dp)
                            } else {
                                Icon(Icons.Filled.AccountCircle, contentDescription = "My account")
                            }
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                Divider(color = DividerColor)

                // Landscape (wide): controls/header become a left column so the scrollable list keeps
                // the full height. Portrait (narrow): the original single-column top-to-bottom stack.
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 600.dp
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        selectedGame != null -> CommunityDevicePanel(
                            vm = vm,
                            game = selectedGame,
                            userSoc = userSoc,
                            userGpu = userGpu,
                            hardwareLabel = catalog?.hardwareLabel,
                            deviceModel = catalog?.deviceModel,
                            onPick = onPick,
                            wide = wide,
                        )
                        games.isEmpty() -> Text(
                            "No community configs available yet (offline, or the index hasn't been fetched).",
                            color = OnSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            textAlign = TextAlign.Center,
                        )
                        wide -> Row(modifier = Modifier.fillMaxSize()) {
                            ListControls(
                                modifier = Modifier
                                    .width(320.dp)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(DividerColor))
                            GameList(modifier = Modifier.weight(1f).fillMaxHeight())
                        }
                        else -> Column(modifier = Modifier.fillMaxSize()) {
                            ListControls(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            Divider(color = DividerColor)
                            GameList(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

// Shared thin outlined card for the community browser's game + config rows. Matches the app's
// FileManager/Containers card idiom (surfaceContainer fill, 1dp outline, rounded 10dp) but with a
// tighter vertical rhythm so the rows read as a compact list. The whole card is the tap target.
@Composable
private fun CommunityCard(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

// What the chooser / detail / apply flow acts on when a config card is tapped. A [File] is a specific
// uploaded config from the worker (with votes/downloads, applied exactly); a [Device] is the offline
// fallback — a canonical device row whose best-matching file is resolved at apply time (no vote counts).
private sealed class CommunityPick {
    abstract val game: CanonicalGame

    data class File(
        override val game: CanonicalGame,
        val ref: CommunityConfigRef,
        val entry: WorkerConfigEntry,
    ) : CommunityPick()

    data class Device(
        override val game: CanonicalGame,
        val device: CanonicalDevice,
    ) : CommunityPick()
}

// Async state of the per-game worker fetch. [entries] is the merged, deduped, votes-desc list across
// ALL the game's folders; each entry is paired with the folder (`/list` key) it came from so its
// per-entry [CommunityConfigRef.workerGame] is correct. [loading] gates the spinner.
private data class GameConfigsState(
    val loading: Boolean,
    val entries: List<Pair<String, WorkerConfigEntry>>,
)

// Fetch (once per [game]) the uploaded configs for a game from the worker and expose them as Compose
// state. Shared by the per-shortcut sheet and the catalog browser's device panel. [extraBannerlatorFolders]
// (the per-shortcut sheet passes the shortcut's own sanitized folder name) is queried in the bannerlator
// namespace ONLY, so a user's own upload is surfaced even before it lands in the canonical index.
@Composable
private fun rememberGameConfigs(
    vm: ShortcutsViewModel,
    game: CanonicalGame,
    extraBannerlatorFolders: List<String> = emptyList(),
): GameConfigsState {
    var loading by remember(game) { mutableStateOf(true) }
    var entries by remember(game) { mutableStateOf<List<Pair<String, WorkerConfigEntry>>>(emptyList()) }
    LaunchedEffect(game) {
        loading = true
        vm.fetchGameConfigs(game, extraBannerlatorFolders) { list ->
            entries = list
            loading = false
        }
    }
    return GameConfigsState(loading, entries)
}

// One card per uploaded config: primary = the device it was captured on (soc/filename fallback),
// sub-line = soc · date, and a `★ votes  ↓ downloads` stats row (same iconography as the detail page).
// The primary line is emphasized in the theme's primary colour when this config matches your hardware.
@Composable
private fun CommunityConfigEntryCard(entry: WorkerConfigEntry, isMatch: Boolean, onClick: () -> Unit) {
    CommunityCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.device.ifBlank { entry.soc.ifBlank { entry.filename } },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isMatch) MaterialTheme.colorScheme.primary else OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOf(entry.soc, entry.date).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (entry.appSource == "bannerlator") BannerlatorSourceBadge()
            Text("★ ${entry.votes}", style = MaterialTheme.typography.labelMedium, color = OnSurface)
            Text("↓ ${entry.downloads}", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
        }
    }
}

// One game card in the catalog browser: name, Steam/Title badge, config + device counts.
@Composable
private fun CommunityGameRow(game: CanonicalGame, onClick: () -> Unit) {
    CommunityCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = game.name,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val cfgWord = if (game.configCount == 1) "config" else "configs"
            val devWord = if (game.devices.size == 1) "device" else "devices"
            Text(
                text = "${game.configCount} $cfgWord · ${game.devices.size} $devWord",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant,
            )
        }
        CommunityStoreBadge(isSteam = game.isSteam)
    }
}

// Compose the "Your device" header value: "<model> · <soc/gpu>" when both slots are known, otherwise
// the literal "Unresolved" fills whichever slot we couldn't detect — so the user can always tell which
// value was found and which wasn't. The both-missing case collapses to a single "Unresolved" (never
// "Unresolved · Unresolved"). Display-only; the caller decides which values to pass and this never
// affects device matching.
private fun deviceHeaderLabel(model: String?, hardware: String?): String {
    val m = model?.takeIf { it.isNotBlank() }
    val hw = hardware?.takeIf { it.isNotBlank() }
    return when {
        m != null && hw != null -> "$m · $hw"
        m != null -> "$m · Unresolved"
        hw != null -> "Unresolved · $hw"
        else -> "Unresolved"
    }
}

// Per-uploaded-config list for a browser-selected game: one card per config the worker returns (votes
// desc), each whole-row-tappable → the Apply-to-game… | View-details chooser. A "Matches my device"
// toggle filters to configs matching your hardware. Offline / bucket miss falls back to the per-device
// index rows (apply-by-device, no vote counts). Header sits on top in portrait, in the left column in
// landscape; the two-column landscape layout is preserved.
@Composable
private fun CommunityDevicePanel(
    vm: ShortcutsViewModel,
    game: CanonicalGame,
    userSoc: String?,
    userGpu: String?,
    hardwareLabel: String?,
    deviceModel: String?,
    onPick: (CommunityPick) -> Unit,
    wide: Boolean,
) {
    val cfg = rememberGameConfigs(vm, game)
    val fallback = remember(game, userSoc, userGpu) { GameMatcher.rankDevices(game.devices, userSoc, userGpu) }
    var matchesMyDevice by rememberSaveable(game.identity) { mutableStateOf(false) }
    val hwEnabled = userSoc != null || userGpu != null

    val shownEntries = remember(cfg.entries, matchesMyDevice, userSoc, userGpu) {
        if (!matchesMyDevice) cfg.entries
        else cfg.entries.filter { GameMatcher.hardwareMatchesUser(userSoc, userGpu, listOf(it.second.device, it.second.soc)) }
    }

    // Header (counts + store badge + your-device + the "Matches my device" toggle). Sits on top in
    // portrait, in the left column in landscape.
    @Composable
    fun Header() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cfgWord = if (game.configCount == 1) "config" else "configs"
            val devWord = if (game.devices.size == 1) "device" else "devices"
            Text(
                "${game.configCount} $cfgWord · ${game.devices.size} $devWord",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            CommunityStoreBadge(isSteam = game.isSteam)
        }
        Text(
            "Your device: ${deviceHeaderLabel(deviceModel, hardwareLabel)}",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
        )
        FilterChip(
            selected = matchesMyDevice,
            onClick = { matchesMyDevice = !matchesMyDevice },
            label = { Text("Matches my device") },
            enabled = hwEnabled,
        )
    }

    // The config cards (whole-row tap → chooser). `modifier` provides the scroll container.
    @Composable
    fun ConfigList(modifier: Modifier) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                cfg.loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Loading configs…", color = OnSurfaceVariant)
                    }
                }
                cfg.entries.isNotEmpty() -> {
                    if (shownEntries.isEmpty()) {
                        Text("No uploaded configs match your device.", color = OnSurfaceVariant)
                    } else {
                        shownEntries.forEach { (folder, e) ->
                            val isMatch = hwEnabled &&
                                GameMatcher.hardwareMatchesUser(userSoc, userGpu, listOf(e.device, e.soc))
                            CommunityConfigEntryCard(entry = e, isMatch = isMatch) {
                                onPick(
                                    CommunityPick.File(
                                        game,
                                        CommunityConfigRef(game, folder, e.filename, e.sha.ifBlank { null }, ns = if (e.appSource == "bannerlator") "bannerlator" else ""),
                                        e,
                                    )
                                )
                            }
                        }
                    }
                }
                fallback.isEmpty() -> Text("No configs listed.", color = OnSurfaceVariant)
                else -> {
                    // Offline fallback: per-device index rows (best-matching file resolved at apply).
                    Text(
                        "Showing device configs (vote counts unavailable offline).",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceVariant,
                    )
                    val hw = hardwareLabel?.lowercase()
                    val devs = if (!matchesMyDevice) fallback
                        else fallback.filter { GameMatcher.deviceMatchesUser(it, userSoc, userGpu) }
                    devs.forEach { d ->
                        val isMatch = hw != null && (
                            (d.soc.isNotBlank() && (hw.contains(d.soc.lowercase()) || d.soc.lowercase().contains(hw))) ||
                            (d.gpu.isNotBlank() && (hw.contains(d.gpu.lowercase()) || d.gpu.lowercase().contains(hw)))
                        )
                        CommunityCard(onClick = { onPick(CommunityPick.Device(game, d)) }) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = d.model.ifBlank { "Unknown device" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isMatch) MaterialTheme.colorScheme.primary else OnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val sub = listOf(d.gpu, d.soc).filter { it.isNotBlank() }.joinToString(" · ")
                                if (sub.isNotEmpty()) {
                                    Text(sub, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (wide) {
        // Landscape: header pinned in a left column, config list scrolls on the right at full height.
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { Header() }
            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(DividerColor))
            ConfigList(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    } else {
        // Portrait: the original single scrolling column (header, divider, then the config rows).
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header()
            Divider(color = DividerColor)
            ConfigList(modifier = Modifier.fillMaxWidth())
        }
    }
}

// Human display name for a config's meta.app_source — the actual project that produced it. BannerHub
// and BannerHub Lite are distinct apps writing "bannerhub" / "bannerhub_lite"; ours would be "bannerlator".
private fun communitySourceLabel(appSource: String?): String = when (appSource?.lowercase()?.trim()) {
    "bannerhub" -> "BannerHub"
    "bannerhub_lite" -> "BannerHub Lite"
    "bannerlator" -> "Bannerlator"
    null, "" -> "BannerHub"
    else -> appSource.split('_', ' ').filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

// Turn a translated config into "what it sets" lines in OUR component terms (the same fields the apply
// engine consumes). Only present fields are listed; Proton/wineVersion is advisory (container-only) so
// it is surfaced separately, not here.
private fun configSummaryLines(config: ShortcutConfig): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    config.dxwrapperConfig["version"]?.takeIf { it.isNotBlank() }?.let { out.add("DXVK" to it) }
    config.dxwrapperConfig["vkd3dVersion"]?.takeIf { it.isNotBlank() }?.let { out.add("VKD3D" to it) }
    config.dxwrapperConfig["async"]?.let { out.add("DXVK async" to if (it == "1") "on" else "off") }
    config.graphicsDriverConfig["version"]?.takeIf { it.isNotBlank() }?.let { out.add("Turnip driver" to it) }
    config.scalars["dxwrapper"]?.takeIf { it.isNotBlank() }?.let { out.add("DX wrapper" to it) }
    config.scalars["emulator"]?.let { emu ->
        val fex = config.scalars["fexcoreVersion"]
        out.add("x86 translator" to if (emu == "fexcore" && !fex.isNullOrBlank()) "FEXCore $fex" else emu)
    }
    config.scalars["audioDriver"]?.takeIf { it.isNotBlank() }?.let { out.add("Audio driver" to it) }
    config.scalars["inputType"]?.let { out.add("XInput" to if (it == "1") "on" else "off") }
    config.scalars["screenSize"]?.takeIf { it.isNotBlank() }?.let { out.add("Resolution" to it) }
    config.scalars["renderer"]?.takeIf { it.isNotBlank() }?.let { out.add("Renderer" to it) }
    config.scalars["execArgs"]?.takeIf { it.isNotBlank() }?.let { out.add("Launch args" to it) }
    config.scalars["envVars"]?.takeIf { it.isNotBlank() }?.let { out.add("Env vars" to it) }
    return out
}

// Read-only Community Config detail page. Renders only data we already fetch: provenance ([detail.meta]),
// the config in our own component terms ([configSummaryLines]), and (when a target shortcut was in
// context) the non-mutating pre-apply diff ([detail.preview]). Apply is delegated to the caller, which
// hands it to the shared apply → applyResult → smart-install flow — this page never applies/installs itself.
@Composable
private fun CommunityConfigDetailDialog(
    game: CanonicalGame,
    device: CanonicalDevice,
    detail: CommunityConfigDetail?,
    loading: Boolean,
    failed: Boolean,
    vm: ShortcutsViewModel,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Live social state, seeded from [detail] (re-seeds when the async load lands). Vote dedup is local
    // per-sha in a `banner_config_votes` prefs file, mirroring BannerHub's `bh_config_votes`; the worker
    // also enforces one vote / IP / 24h.
    val votePrefs = remember { context.getSharedPreferences("banner_config_votes", Context.MODE_PRIVATE) }
    var votes by remember(detail) { mutableStateOf(detail?.votes ?: 0) }
    var comments by remember(detail) { mutableStateOf(detail?.comments ?: emptyList()) }
    var voted by remember(detail) {
        mutableStateOf(detail?.sha?.let { votePrefs.getBoolean(it, false) } ?: false)
    }
    var voting by remember(detail) { mutableStateOf(false) }
    var commentText by remember(detail) { mutableStateOf("") }
    var commenting by remember(detail) { mutableStateOf(false) }

    // The live social block: ★ votes · ↓ downloads, uploader description, an Upvote button (local +
    // worker dedup), the comment thread, and a compact add-comment field. Only rendered when a worker
    // /list entry matched this file ([workerGame] != null) — otherwise there's no social data to show.
    @Composable
    fun Social(d: CommunityConfigDetail) {
        Divider(color = DividerColor)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("★ $votes", style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Text("↓ ${d.downloads}", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
        }
        if (d.description.isNotBlank()) {
            Text(d.description, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }
        d.sha?.let { sha ->
            OutlinedButton(
                onClick = {
                    val g = d.workerGame ?: return@OutlinedButton
                    if (voted || voting) return@OutlinedButton
                    voting = true
                    vm.voteConfig(sha, g, d.fileName) { newVotes ->
                        voting = false
                        if (newVotes != null) {
                            votes = newVotes
                            voted = true
                            votePrefs.edit().putBoolean(sha, true).apply()
                        } else {
                            Toast.makeText(context, "Couldn't record your vote.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !voted && !voting,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                if (voting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (voted) "Voted ✓" else "Upvote")
                }
            }
        }

        Text("Comments", style = MaterialTheme.typography.labelLarge, color = OnSurface)
        if (comments.isEmpty()) {
            Text("No comments yet.", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        } else {
            comments.forEach { c ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    val head = listOf(c.device, c.date).filter { it.isNotBlank() }.joinToString(" · ")
                    if (head.isNotEmpty()) {
                        Text(head, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                    }
                    Text(c.text, style = MaterialTheme.typography.bodySmall, color = OnSurface)
                }
            }
        }
        // Add a comment (worker caps text at 500 chars).
        val workerGame = d.workerGame
        if (workerGame != null) {
            OutlinedTextField(
                value = commentText,
                onValueChange = { if (it.length <= 500) commentText = it },
                label = { Text("Add a comment") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        val text = commentText.trim()
                        if (text.isEmpty() || commenting) return@TextButton
                        commenting = true
                        val dev = Build.MANUFACTURER + "_" + Build.MODEL
                        vm.addConfigComment(workerGame, d.fileName, text, dev) { refreshed ->
                            commenting = false
                            if (refreshed != null) {
                                comments = refreshed
                                commentText = ""
                            } else {
                                Toast.makeText(context, "Couldn't post your comment.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = commentText.isNotBlank() && !commenting,
                ) { Text(if (commenting) "Sending…" else "Send") }
            }
        }
    }

    // Provenance — game · device · soc · uploaded date · BannerHub source badge. Prefers the config's
    // own meta, falling back to the catalog device row when a field is blank.
    @Composable
    fun Provenance(modifier: Modifier) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                game.name,
                style = MaterialTheme.typography.titleMedium,
                color = OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = detail?.meta
            val dev = meta?.device ?: device.model.ifBlank { null }
            val soc = meta?.soc ?: device.soc.ifBlank { null }
            val hw = listOfNotNull(dev, device.gpu.ifBlank { null }, soc).distinct().joinToString(" · ")
            if (hw.isNotEmpty()) {
                Text(hw, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
            }
            meta?.uploadedDate?.let {
                Text("Uploaded $it", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommunityStoreBadge(isSteam = game.isSteam)
                if (meta != null) {
                    // Name the actual source project (meta.app_source distinguishes BannerHub vs
                    // BannerHub Lite vs a future Bannerlator upload), with the version appended if present.
                    val label = "From ${communitySourceLabel(meta.appSource)}" + (meta.bhVersion?.let { " $it" } ?: "")
                    Surface(
                        color = SurfaceVariantColor,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            // Uploader attribution (Phase 2 label + Phase 3 avatar). A signed-in upload carries
            // meta.uploader → "by <username>" plus its avatar when set; an anonymous config has none →
            // "Anonymous user" with the person-icon placeholder (AccountAvatar's null fallback).
            if (meta != null) {
                val uploaderLabel = meta.uploaderName?.let { "by $it" } ?: "Anonymous user"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AccountAvatar(
                        avatarUrl = meta.uploaderAvatarUrl,
                        size = 16.dp,
                        fallbackTint = OnSurfaceVariant,
                    )
                    Text(uploaderLabel, style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                }
            }
        }
    }

    // "What this config sets" + (when previewed) the pre-apply diff against the in-context shortcut.
    @Composable
    fun Body(modifier: Modifier) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Loading config…", color = OnSurfaceVariant)
                    }
                }
                failed || detail == null -> {
                    Text(
                        "Couldn't fetch this config (offline, or no matching file in the repo).",
                        color = OnSurfaceVariant,
                    )
                }
                else -> {
                    Text("What this config sets", style = MaterialTheme.typography.labelLarge, color = OnSurface)
                    val lines = configSummaryLines(detail.config)
                    if (lines.isEmpty()) {
                        Text("Nothing this app can set.", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    } else {
                        lines.forEach { (label, value) ->
                            Text(
                                "• $label: $value",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceVariant,
                            )
                        }
                    }
                    detail.config.advisories["wineVersion"]?.let { proton ->
                        Text(
                            "• Proton (container-only): $proton",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                        )
                    }

                    // Pre-apply diff — only when a target shortcut was in context.
                    detail.preview?.let { pre ->
                        Divider(color = DividerColor)
                        Text("Changes to \"${game.name}\"", style = MaterialTheme.typography.labelLarge, color = OnSurface)
                        Text(pre.message, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                        if (pre.changed.isNotEmpty()) {
                            Text("Would change", style = MaterialTheme.typography.labelMedium, color = OnSurface)
                            pre.changed.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant) }
                        }
                        if (pre.missingComponents.isNotEmpty()) {
                            Text("Needs a component", style = MaterialTheme.typography.labelMedium, color = OnSurface)
                            pre.missingComponents.forEach { Text("• ${it.label}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant) }
                        }
                        if (pre.missingDrivers.isNotEmpty()) {
                            Text("Needs a GPU driver", style = MaterialTheme.typography.labelMedium, color = OnSurface)
                            pre.missingDrivers.forEach {
                                val had = it.current?.let { c -> " (you have $c)" } ?: ""
                                Text("• Turnip ${it.wanted}$had", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                            }
                        }
                        if (pre.advisories.isNotEmpty()) {
                            Text("Heads up", style = MaterialTheme.typography.labelMedium, color = OnSurface)
                            pre.advisories.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant) }
                        }
                    }

                    // Live social layer (votes / downloads / description / comments) — only when a
                    // worker /list entry matched this file; otherwise there's nothing to show.
                    if (detail.workerGame != null) Social(detail)
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            // Outline the whole popup so the panel reads as a distinct bordered box (like the cards)
            // instead of bleeding edge-to-edge into the background behind the dialog.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Config details",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                Divider(color = DividerColor)

                // Landscape (wide): provenance pinned left, "what it sets" + diff scroll on the right.
                // Portrait (narrow): a single top-to-bottom scroll of both.
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth >= 600.dp) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Provenance(
                                modifier = Modifier
                                    .width(320.dp)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(DividerColor))
                            Body(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Provenance(modifier = Modifier.fillMaxWidth())
                            Divider(color = DividerColor)
                            Body(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Divider(color = DividerColor)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    TextButton(onClick = onApply, enabled = detail != null) { Text("Apply") }
                }
            }
        }
    }
}

// Game-list card (poster cover + primary chips + muted secondary line, issue #19). A tall
// 3:4 cover on the left, name + container · resolution subtitle in the middle. Components are
// split by how often you check them: renderer, DXVK and frame-gen are bright chips; driver,
// VKD3D and x86 backend sit on a calm muted line with a colour dot each. "Calm but complete."
@Composable
private fun ShortcutItemLayoutL(
    shortcut: Shortcut,
    onRun: () -> Unit,
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    onClone: () -> Unit,
    onAddToHome: () -> Unit,
    onExport: () -> Unit,
    onProperties: () -> Unit,
    onScrapeCover: () -> Unit,
    onCommunityConfigs: () -> Unit,
) {
    val container = shortcut.container
    val res = LocalContext.current.resources

    // Resolved component metadata (shortcut override → container default).
    val resolution = shortcut.getExtra("screenSize", container?.getScreenSize() ?: "")
    val driverCfg = shortcut.getExtra("graphicsDriverConfig", container?.getGraphicsDriverConfig() ?: "")
    val driverLabel = if (driverCfg.isNotEmpty()) GraphicsDriverConfigDialog.getVersion(driverCfg) else ""
    val dxwrapperCfg = shortcut.getExtra("dxwrapperConfig", container?.getDXWrapperConfig() ?: "")
    val (dxvkVersion, vkd3dVersion) = parseDxwrapperConfig(dxwrapperCfg)

    val rendererLabel = rendererLabelOf(shortcut.getExtra("renderer", container?.renderer ?: ""))
    val frameGenLabel = frameGenLabelOf(shortcut.getExtra("frameGenEngine", container?.frameGenEngine ?: "off"))
    // x86 backend (FEXCore / Box64). Preset suffix (e.g. "· TSO") deferred — it needs
    // the async Box64/FEXCore preset managers, too heavy to resolve per list-card.
    val backendLabel = run {
        val id = shortcut.getExtra("emulator", container?.emulator ?: "")
        res.getStringArray(R.array.emulator_entries)
            .firstOrNull { StringUtils.parseIdentifier(it) == id } ?: ""
    }

    val subtitle = listOf(container?.name ?: "", resolution).filter { it.isNotEmpty() }.joinToString(" · ")

    // Floating card to match the Containers list (rounded surfaceVariant panel + outline
    // border + side margins) instead of a flat edge-to-edge row.
    Card(
        onClick = onRun,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        // 3:4 poster cover (same as layout A); fall back to a glyph tile.
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceVariantColor),
            contentAlignment = Alignment.Center,
        ) {
            if (shortcut.icon != null) {
                Image(
                    bitmap = shortcut.icon.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortcut.name,
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Component specs: bright primary chips (renderer · DXVK · frame-gen) then a
            // muted secondary dot-line (driver · VKD3D · backend). Shared with Containers.
            SpecChipRows(
                rendererLabel = rendererLabel,
                dxvkVersion = dxvkVersion,
                frameGenLabel = frameGenLabel,
                driverLabel = driverLabel,
                vkd3dVersion = vkd3dVersion,
                backendLabel = backendLabel,
            )
        }
        ShortcutOverflowButton(
            onSettings = onSettings,
            onRemove = onRemove,
            onClone = onClone,
            onAddToHome = onAddToHome,
            onExport = onExport,
            onProperties = onProperties,
            onScrapeCover = onScrapeCover,
            onCommunityConfigs = onCommunityConfigs,
        )
      }
    }
}

// Shared overflow (⋮) button + menu for the list-view cards.
@Composable
private fun ShortcutOverflowButton(
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    onClone: () -> Unit,
    onAddToHome: () -> Unit,
    onExport: () -> Unit,
    onProperties: () -> Unit,
    onScrapeCover: () -> Unit,
    onCommunityConfigs: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = OnSurfaceVariant)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.outlinedMenuCard(),
        ) {
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                onClick = { menuExpanded = false; onSettings() },
            )
            MenuItemDivider()
            DropdownMenuItem(
                text = { Text("Remove") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { menuExpanded = false; onRemove() },
            )
            MenuItemDivider()
            DropdownMenuItem(
                text = { Text("Clone to container") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { menuExpanded = false; onClone() },
            )
            MenuItemDivider()
            DropdownMenuItem(
                text = { Text("Add to home screen") },
                leadingIcon = { Icon(Icons.Filled.AddToHomeScreen, null) },
                onClick = { menuExpanded = false; onAddToHome() },
            )
            MenuItemDivider()
            DropdownMenuItem(
                text = { Text("Export") },
                leadingIcon = { Icon(Icons.Filled.Upload, null) },
                onClick = { menuExpanded = false; onExport() },
            )
            MenuItemDivider()
            DropdownMenuItem(
                text = { Text("Scrape cover") },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { menuExpanded = false; onScrapeCover() },
            )
            MenuItemDivider()
            DropdownMenuItem(
                text = { Text("Community configs") },
                leadingIcon = { Icon(Icons.Filled.Public, null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { menuExpanded = false; onCommunityConfigs() },
            )
            MenuItemDivider()
            DropdownMenuItem(
                text = { Text("Properties") },
                leadingIcon = { Icon(Icons.Filled.Info, null) },
                onClick = { menuExpanded = false; onProperties() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutGridItem(
    shortcut: Shortcut,
    onRun: () -> Unit,
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    onClone: () -> Unit,
    onAddToHome: () -> Unit,
    onExport: () -> Unit,
    onProperties: () -> Unit,
    onScrapeCover: () -> Unit,
    onCommunityConfigs: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceColor)
            .border(
                width = 2.dp,
                brush = Brush.linearGradient(
                    // accent-family gradient: dim → accent → accent, so the grid-tile border follows the theme
                    colors = listOf(LocalAccentDim.current, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary),
                ),
                shape = RoundedCornerShape(8.dp),
            )
            .combinedClickable(onClick = onRun, onLongClick = { menuExpanded = true }),
    ) {
        // Cover image fills the entire tile
        if (shortcut.icon != null) {
            Image(
                bitmap = shortcut.icon.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            )
        }

        // Gradient scrim + name/container at the bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Column {
                Text(
                    text = shortcut.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!shortcut.container?.name.isNullOrEmpty()) {
                    Text(
                        text = shortcut.container?.name ?: "",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Long-press context menu
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Filled.Settings, null) }, onClick = { menuExpanded = false; onSettings() })
            DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menuExpanded = false; onRemove() })
            DropdownMenuItem(text = { Text("Clone to container") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menuExpanded = false; onClone() })
            DropdownMenuItem(text = { Text("Add to home screen") }, leadingIcon = { Icon(Icons.Filled.AddToHomeScreen, null) }, onClick = { menuExpanded = false; onAddToHome() })
            DropdownMenuItem(text = { Text("Export") }, leadingIcon = { Icon(Icons.Filled.Upload, null) }, onClick = { menuExpanded = false; onExport() })
            DropdownMenuItem(text = { Text("Scrape cover") }, leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { menuExpanded = false; onScrapeCover() })
            DropdownMenuItem(text = { Text("Community configs") }, leadingIcon = { Icon(Icons.Filled.Public, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { menuExpanded = false; onCommunityConfigs() })
            DropdownMenuItem(text = { Text("Properties") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { menuExpanded = false; onProperties() })
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ShortcutSettingsDialogScreen(shortcut: Shortcut, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = context.resources

    // Async-loaded state
    var isArm64EC by remember { mutableStateOf(false) }
    var box64Versions by remember { mutableStateOf(listOf<String>()) }
    var box64Presets by remember { mutableStateOf(listOf<Box64Preset>()) }
    var fexCoreVersions by remember { mutableStateOf(listOf<String>()) }
    var fexCorePresets by remember { mutableStateOf(listOf<FEXCorePreset>()) }
    var controlsProfiles by remember { mutableStateOf(listOf<ControlsProfile>()) }
    var midiList by remember { mutableStateOf(listOf<String>()) }

    // Screen size
    val screenSizeEntries = remember { res.getStringArray(R.array.screen_size_entries).toList() }
    val rawScreenSize = remember { shortcut.getExtra("screenSize", shortcut.container.getScreenSize()) }
    var selectedScreenSize by remember {
        val display = screenSizeEntries.firstOrNull {
            StringUtils.parseIdentifier(it).equals(rawScreenSize, ignoreCase = true)
        }
        mutableStateOf(display ?: "Custom")
    }
    var customWidth by remember {
        mutableStateOf(if (rawScreenSize.contains("x")) rawScreenSize.substringBefore("x") else "800")
    }
    var customHeight by remember {
        mutableStateOf(if (rawScreenSize.contains("x")) rawScreenSize.substringAfter("x") else "600")
    }

    // Graphics driver
    val graphicsDriverEntries = remember { res.getStringArray(R.array.graphics_driver_entries).toList() }
    var selectedGfxDriver by remember {
        val id = shortcut.getExtra("graphicsDriver", shortcut.container.graphicsDriver)
        mutableStateOf(graphicsDriverEntries.firstOrNull { StringUtils.parseIdentifier(it) == id }
            ?: graphicsDriverEntries.firstOrNull() ?: id)
    }
    var graphicsDriverConfig by remember {
        mutableStateOf(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()))
    }

    // DX wrapper
    val dxWrapperEntries = remember { res.getStringArray(R.array.dxwrapper_entries).toList() }
    var selectedDxWrapper by remember {
        val id = shortcut.getExtra("dxwrapper", shortcut.container.getDXWrapper())
        mutableStateOf(dxWrapperEntries.firstOrNull { StringUtils.parseIdentifier(it) == id }
            ?: dxWrapperEntries.firstOrNull() ?: id)
    }
    var dxWrapperConfig by remember {
        mutableStateOf(shortcut.getExtra("dxwrapperConfig", shortcut.container.getDXWrapperConfig()))
    }

    // Renderer (host: OpenGL / Vulkan) — per-game override, defaults to the container's value.
    var selectedRenderer by remember {
        val id = shortcut.getExtra("renderer", shortcut.container.renderer)
        mutableStateOf(
            when {
                id.equals("vulkan", ignoreCase = true) -> "Vulkan"
                id.equals("surfaceflinger", ignoreCase = true) -> "SurfaceFlinger"
                else -> "OpenGL"
            }
        )
    }

    // SurfaceFlinger colour correction (ASR-only, GN #1620) — per-game override, defaults to the
    // container's value. Stored via the shortcut "sfCompatMode" extra ("1"/"0"). Default ON.
    var sfCompatMode by remember {
        mutableStateOf(shortcut.getExtra("sfCompatMode",
            if (shortcut.container.getRendererSfCompatMode()) "1" else "0") == "1")
    }

    // Render scale (supersampling) — per-game override, defaults to the container's "renderScale"
    // extra. Stored via the shortcut "renderScale" extra. "1.0" = Off.
    var renderScale by remember {
        mutableStateOf(shortcut.getExtra("renderScale", shortcut.container.getExtra("renderScale", "1.0")))
    }

    // Frame Generation engine (off / bionic / lsfg) — per-game override.
    val fgEngines = remember { listOf("off", "bionic", "lsfg") }
    var frameGenEngine by remember {
        mutableStateOf(shortcut.getExtra("frameGenEngine", shortcut.container.frameGenEngine))
    }
    val lsfgDllAvailable = remember { File(context.filesDir, "lsfg-vk/Lossless.dll").isFile }

    // FPS limiter — per-game override.
    var fpsLimiterEnabled by remember {
        mutableStateOf(
            shortcut.getExtra("fpsLimiterEnabled", if (shortcut.container.isFpsLimiterEnabled) "1" else "0") == "1"
        )
    }

    // Audio driver
    val audioDriverEntries = remember { res.getStringArray(R.array.audio_driver_entries).toList() }
    var selectedAudioDriver by remember {
        val id = shortcut.getExtra("audioDriver", shortcut.container.audioDriver)
        mutableStateOf(audioDriverEntries.firstOrNull { StringUtils.parseIdentifier(it) == id }
            ?: audioDriverEntries.firstOrNull() ?: id)
    }

    // Emulator
    val emulatorEntries = remember { res.getStringArray(R.array.emulator_entries).toList() }
    var selectedEmulator by remember {
        val id = shortcut.getExtra("emulator", shortcut.container.emulator)
        mutableStateOf(emulatorEntries.firstOrNull { StringUtils.parseIdentifier(it) == id }
            ?: emulatorEntries.firstOrNull() ?: id)
    }

    // MIDI
    var selectedMidi by remember {
        mutableStateOf(shortcut.getExtra("midiSoundFont", shortcut.container.getMIDISoundFont()))
    }

    // Basic text fields
    var name by remember { mutableStateOf(shortcut.name) }
    var execArgs by remember { mutableStateOf(shortcut.getExtra("execArgs")) }
    var lcAll by remember { mutableStateOf(shortcut.getExtra("lc_all", shortcut.container.getLC_ALL())) }

    // Checkboxes / switches
    // Per-game fullscreen aspect-ratio override (#71): -1 = use container default, else
    // Container.FULLSCREEN_OFF/FIT/STRETCH. Migrates the legacy per-game "fullscreenStretched".
    var fullscreenModeOverride by remember {
        mutableStateOf(
            run {
                val m = shortcut.getExtra("fullscreenMode")
                when {
                    m.isNotEmpty() -> m.toIntOrNull() ?: -1
                    shortcut.getExtra("fullscreenStretched", "") == "1" -> com.winlator.star.container.Container.FULLSCREEN_STRETCH
                    else -> -1
                }
            }
        )
    }
    // Close the session when this game exits — per-game override, defaults to the container's setting (ON).
    var autoCloseOnExit by remember {
        mutableStateOf(shortcut.getExtra("autoCloseOnExit", shortcut.container.getExtra("autoCloseOnExit", "1")) == "1")
    }
    var exclusiveXInput by remember {
        val v = shortcut.getExtra("exclusiveXInput")
        mutableStateOf(if (v.isEmpty()) shortcut.container.isExclusiveXInput else v == "1")
    }
    val initialInputType = remember {
        shortcut.getExtra("inputType", shortcut.container.getInputType().toString()).toIntOrNull()
            ?: shortcut.container.getInputType()
    }
    var enableXInput by remember { mutableStateOf((initialInputType and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) != 0) }
    var enableDInput by remember { mutableStateOf((initialInputType and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) != 0) }
    var disabledXInput by remember { mutableStateOf(shortcut.getExtra("disableXinput", "0") == "1") }
    var simTouchScreen by remember { mutableStateOf(shortcut.getExtra("simTouchScreen", "0") == "1") }

    // Num controllers
    val numControllersEntries = remember { res.getStringArray(R.array.num_controllers_entries).toList() }
    var selectedNumControllers by remember {
        val n = (shortcut.getExtra("numControllers", "1").toIntOrNull() ?: 1).coerceIn(1, numControllersEntries.size)
        mutableStateOf(numControllersEntries.getOrElse(n - 1) { numControllersEntries.first() })
    }

    // Box64 / FEXCore / controls
    var selectedBox64Version by remember {
        mutableStateOf(shortcut.getExtra("box64Version", shortcut.container.getBox64Version()))
    }
    var selectedBox64PresetIndex by remember { mutableIntStateOf(0) }
    var selectedFexCoreVersion by remember {
        mutableStateOf(shortcut.getExtra("fexcoreVersion", shortcut.container.getFEXCoreVersion()))
    }
    var selectedFexCorePresetIndex by remember { mutableIntStateOf(0) }
    var selectedControlsProfileIndex by remember { mutableIntStateOf(0) }

    // Startup selection
    val startupSelectionEntries = remember { res.getStringArray(R.array.startup_selection_entries).toList() }
    var selectedStartupSelection by remember {
        val idx = (shortcut.getExtra("startupSelection", shortcut.container.getStartupSelection().toString())
            .toIntOrNull() ?: 0).coerceIn(0, startupSelectionEntries.lastIndex)
        mutableStateOf(startupSelectionEntries.getOrElse(idx) { startupSelectionEntries.first() })
    }

    // Sharpness
    val sharpnessEffectEntries = remember { res.getStringArray(R.array.vkbasalt_sharpness_entries).toList() }
    var selectedSharpnessEffect by remember {
        val v = shortcut.getExtra("sharpnessEffect", "None")
        mutableStateOf(sharpnessEffectEntries.firstOrNull { it == v } ?: sharpnessEffectEntries.firstOrNull() ?: v)
    }
    var sharpnessLevel by remember {
        mutableIntStateOf(shortcut.getExtra("sharpnessLevel", "100").toIntOrNull() ?: 100)
    }
    var sharpnessDenoise by remember {
        mutableIntStateOf(shortcut.getExtra("sharpnessDenoise", "100").toIntOrNull() ?: 100)
    }

    // ReShade multi-effect LOADOUT (vkBasalt drop-in). Scan the user folder; ReshadeLoadoutState holds
    // the ordered effects, per-effect enabled + params, and the solo/stack mode. Loaded from the
    // shortcut override → container default (migrating a legacy single effect + flat params). Serialized
    // back to the reshadeLoadout/reshadeMode/reshadeParams extras on save. reshadeEffects is mutable so
    // a catalog download can rescan the drop-in folder and surface the new effect.
    var reshadeEffects by remember { mutableStateOf(ReshadeManager.scanEffects(context)) }
    val reshadeLoadout = remember { ReshadeLoadoutState() }
    // Initial load (once): resolve the shortcut override else the container default.
    LaunchedEffect(Unit) {
        reshadeLoadout.init(
            reshadeEffects,
            shortcut.getExtra("reshadeLoadout", shortcut.container.getReshadeLoadout()).ifEmpty { null },
            shortcut.getExtra("reshadeMode", shortcut.container.getReshadeMode()),
            shortcut.getExtra("reshadeParams", shortcut.container.getReshadeParams()).ifEmpty { null },
            shortcut.getExtra("reshadeEffect", shortcut.container.getReshadeEffect()),
        )
    }

    // Win components
    val winComponents = remember {
        val raw = shortcut.getExtra("wincomponents", shortcut.container.getWinComponents())
        mutableStateListOf<WinComponentEntry>().also { list ->
            for (parts in KeyValueSet(raw)) {
                val key = parts[0]; val idx = parts[1].toIntOrNull() ?: 0
                val resId = res.getIdentifier(key, "string", context.packageName)
                val label = if (resId != 0) res.getString(resId) else key
                list.add(WinComponentEntry(key, idx, label))
            }
        }
    }

    // AndroidView refs
    val envVarsViewRef = remember { mutableStateOf<EnvVarsView?>(null) }
    val cpuListViewRef = remember { mutableStateOf<CPUListView?>(null) }

    // Icon
    var iconBitmap by remember { mutableStateOf<Bitmap?>(shortcut.icon) }

    // Sub-dialog show states
    var showGfxConfig by remember { mutableStateOf(false) }
    var showDxvkConfig by remember { mutableStateOf(false) }
    var showWineD3DConfig by remember { mutableStateOf(false) }
    var showBox64DownloadSheet by remember { mutableStateOf(false) }
    var showFexCoreDownloadSheet by remember { mutableStateOf(false) }
    var showDxvkDownloadSheet by remember { mutableStateOf(false) }
    var showVegasDownloadSheet by remember { mutableStateOf(false) }
    var showVkd3dDownloadSheet by remember { mutableStateOf(false) }

    // Tab
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Win Components", "Env Vars", "Advanced")

    // Icon picker
    fun applyIconFromUri(uri: Uri) {
        runCatching {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@runCatching
            shortcut.iconFile?.let { f ->
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            }
            shortcut.icon = bitmap
            iconBitmap = bitmap
        }
    }
    // System SAF picker (secondary).
    val iconPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { applyIconFromUri(it) } }
    // Built-in in-app image picker (primary).
    val iconPickerInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { applyIconFromUri(it) }
    }
    var showIconPickMenu by remember { mutableStateOf(false) }

    // Load async data
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cm = ContentsManager(context)
            cm.syncContents()
            val wineInfo = WineInfo.fromIdentifier(context, cm, shortcut.container.wineVersion)
            val arm64ec = wineInfo.isArm64EC()

            val b64Type = if (arm64ec) ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
                          else ContentProfile.ContentType.CONTENT_TYPE_BOX64
            val b64Arr = if (arm64ec) res.getStringArray(R.array.wowbox64_version_entries).toMutableList()
                         else res.getStringArray(R.array.box64_version_entries).toMutableList()
            for (p in cm.getProfiles(b64Type)) {
                val n = ContentsManager.getEntryName(p)
                b64Arr.add(n.substring(n.indexOf('-') + 1))
            }

            val fexList = res.getStringArray(R.array.fexcore_version_entries).toMutableList()
            for (p in cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)) {
                val n = ContentsManager.getEntryName(p)
                fexList.add(n.substring(n.indexOf('-') + 1))
            }

            val b64Presets = Box64PresetManager.getPresets("box64", context)
            val fexPresets = FEXCorePresetManager.getPresets(context)
            val profiles = InputControlsManager(context).getProfiles(true)

            val midi = mutableListOf("-- ${context.getString(R.string.disabled)} --", MidiManager.DEFAULT_SF2_FILE)
            val sfDir = File(context.filesDir, MidiManager.SF_DIR)
            if (sfDir.exists()) sfDir.listFiles()?.forEach { midi.add(it.name) }

            withContext(Dispatchers.Main) {
                isArm64EC = arm64ec
                box64Versions = b64Arr
                fexCoreVersions = fexList
                box64Presets = b64Presets
                fexCorePresets = fexPresets
                controlsProfiles = profiles
                midiList = midi

                val b64Id = shortcut.getExtra("box64Preset", shortcut.container.getBox64Preset())
                selectedBox64PresetIndex = b64Presets.indexOfFirst { it.id == b64Id }.coerceAtLeast(0)

                val fexId = shortcut.getExtra("fexcorePreset", shortcut.container.getFEXCorePreset())
                selectedFexCorePresetIndex = fexPresets.indexOfFirst { it.id == fexId }.coerceAtLeast(0)

                val cpId = shortcut.getExtra("controlsProfile", "0").toIntOrNull() ?: 0
                selectedControlsProfileIndex = if (cpId == 0) 0
                    else profiles.indexOfFirst { it.id == cpId }.let { if (it >= 0) it + 1 else 0 }

                if (selectedBox64Version.isEmpty()) selectedBox64Version = b64Arr.firstOrNull() ?: ""
            }
        }
    }

    // Save
    fun save() {
        val newName = name.trim()
        if (newName.isNotEmpty() && newName != shortcut.name) {
            renameShortcut(shortcut, newName)
        }

        val screenSize = if (selectedScreenSize == "Custom") {
            val w = customWidth.trim(); val h = customHeight.trim()
            if (w.matches(Regex("[0-9]+")) && h.matches(Regex("[0-9]+"))) {
                val wi = w.toInt(); val hi = h.toInt()
                if (wi % 2 == 0 && hi % 2 == 0) "${wi}x${hi}" else Container.DEFAULT_SCREEN_SIZE
            } else Container.DEFAULT_SCREEN_SIZE
        } else {
            StringUtils.parseIdentifier(selectedScreenSize)
        }

        var finalInputType = 0
        if (enableXInput) finalInputType = finalInputType or WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
        if (enableDInput) finalInputType = finalInputType or WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()

        val wincomps = winComponents.joinToString(",") { "${it.key}=${it.selectedIndex}" }
        val envVars = envVarsViewRef.value?.getEnvVars() ?: shortcut.getExtra("envVars")
        val cpuList = cpuListViewRef.value?.getCheckedCPUListAsString() ?: shortcut.getExtra("cpuList", shortcut.container.getCPUList(true))

        val b64PresetId = box64Presets.getOrElse(selectedBox64PresetIndex) { null }?.id ?: Box64Preset.COMPATIBILITY
        val fexPresetId = fexCorePresets.getOrElse(selectedFexCorePresetIndex) { null }?.id ?: FEXCorePreset.COMPATIBILITY
        val ctrlProfileId = if (selectedControlsProfileIndex == 0) 0
            else controlsProfiles.getOrElse(selectedControlsProfileIndex - 1) { null }?.id ?: 0

        val midiVal = if (midiList.isNotEmpty() && selectedMidi == midiList.firstOrNull()) "" else selectedMidi
        val startupIdx = startupSelectionEntries.indexOf(selectedStartupSelection).coerceAtLeast(0)
        val numCtrl = (numControllersEntries.indexOf(selectedNumControllers) + 1).coerceAtLeast(1)

        with(shortcut) {
            putExtra("execArgs", execArgs.ifEmpty { null })
            putExtra("screenSize", screenSize)
            putExtra("graphicsDriver", StringUtils.parseIdentifier(selectedGfxDriver))
            putExtra("graphicsDriverConfig", graphicsDriverConfig)
            putExtra("renderer", StringUtils.parseIdentifier(selectedRenderer))
            putExtra("sfCompatMode", if (sfCompatMode) "1" else "0")
            putExtra("renderScale", if (renderScale == "1.0") null else renderScale)
            putExtra("frameGenEngine", frameGenEngine)
            putExtra("fpsLimiterEnabled", if (fpsLimiterEnabled) "1" else "0")
            putExtra("dxwrapper", StringUtils.parseIdentifier(selectedDxWrapper))
            putExtra("dxwrapperConfig", dxWrapperConfig)
            putExtra("audioDriver", StringUtils.parseIdentifier(selectedAudioDriver))
            putExtra("emulator", StringUtils.parseIdentifier(selectedEmulator))
            putExtra("midiSoundFont", midiVal.ifEmpty { null })
            putExtra("lc_all", lcAll)
            // #71: write the per-game mode override (or null = use container default) and clear the
            // legacy boolean so it can never shadow the new key.
            putExtra("fullscreenMode", if (fullscreenModeOverride < 0) null else fullscreenModeOverride.toString())
            putExtra("fullscreenStretched", null)
            putExtra("autoCloseOnExit", if (autoCloseOnExit) "1" else "0")
            putExtra("inputType", finalInputType.toString())
            putExtra("exclusiveXInput", if (exclusiveXInput) "1" else "0")
            putExtra("disableXinput", if (disabledXInput) "1" else null)
            putExtra("simTouchScreen", if (simTouchScreen) "1" else "0")
            putExtra("numControllers", numCtrl.toString())
            putExtra("box64Version", selectedBox64Version)
            putExtra("box64Preset", b64PresetId)
            putExtra("fexcoreVersion", selectedFexCoreVersion)
            putExtra("fexcorePreset", fexPresetId)
            putExtra("controlsProfile", if (ctrlProfileId > 0) ctrlProfileId.toString() else null)
            putExtra("startupSelection", startupIdx.toString())
            putExtra("sharpnessEffect", selectedSharpnessEffect)
            putExtra("sharpnessLevel", sharpnessLevel.toString())
            putExtra("sharpnessDenoise", sharpnessDenoise.toString())
            putExtra("reshadeLoadout", reshadeLoadout.loadoutJsonOrNull())
            putExtra("reshadeMode", reshadeLoadout.mode)
            putExtra("reshadeParams", reshadeLoadout.paramsJsonOrNull())
            putExtra("reshadeEffect", reshadeLoadout.firstEffectName())
            putExtra("wincomponents", wincomps)
            putExtra("envVars", envVars.ifEmpty { null })
            putExtra("cpuList", cpuList)
            saveData()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column {
                // Title bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(shortcut.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Divider(color = DividerColor)

                // Scrollable content
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Exec Args
                    OutlinedTextField(
                        value = execArgs,
                        onValueChange = { execArgs = it },
                        label = { Text(stringResource(R.string.exec_arguments)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Screen size
                    LabeledDropdown(
                        label = stringResource(R.string.screen_size),
                        options = screenSizeEntries,
                        selectedOption = selectedScreenSize,
                        onSelect = { selectedScreenSize = it }
                    )
                    if (selectedScreenSize == "Custom") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customWidth,
                                onValueChange = { customWidth = it },
                                label = { Text("Width") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = customHeight,
                                onValueChange = { customHeight = it },
                                label = { Text("Height") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }

                    // Icon
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        iconBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(onClick = { showIconPickMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Select Icon")
                            }
                            DropdownMenu(expanded = showIconPickMenu, onDismissRequest = { showIconPickMenu = false }) {
                                DropdownMenuItem(text = { Text("Browse files") }, onClick = {
                                    showIconPickMenu = false
                                    iconPickerInAppLauncher.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.IMAGES, "Select icon image"))
                                })
                                DropdownMenuItem(text = { Text("Pick via system…") }, onClick = {
                                    showIconPickMenu = false
                                    iconPickerLauncher.launch("image/*")
                                })
                            }
                        }
                    }

                    // Graphics Driver
                    LabeledDropdown(
                        label = stringResource(R.string.graphics_driver),
                        options = graphicsDriverEntries,
                        selectedOption = selectedGfxDriver,
                        onSelect = { selectedGfxDriver = it }
                    )
                    OutlinedButton(onClick = { showGfxConfig = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${stringResource(R.string.graphics_driver)}: ${GraphicsDriverConfigDialog.getVersion(graphicsDriverConfig)}")
                    }

                    // DX Wrapper
                    LabeledDropdown(
                        label = stringResource(R.string.dxwrapper),
                        options = dxWrapperEntries,
                        selectedOption = selectedDxWrapper,
                        onSelect = { selectedDxWrapper = it }
                    )
                    OutlinedButton(
                        onClick = {
                            val w = StringUtils.parseIdentifier(selectedDxWrapper)
                            if (w.contains("dxvk") || w.contains("vegas")) showDxvkConfig = true
                            else showWineD3DConfig = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("DX Wrapper Config") }

                    // Renderer (host) — per-game override of the container's OpenGL/Vulkan choice.
                    var showSfWarning by remember { mutableStateOf(false) }
                    LabeledDropdown(
                        label = stringResource(R.string.renderer),
                        options = listOf("OpenGL", "Vulkan", "SurfaceFlinger"),
                        selectedOption = selectedRenderer,
                        onSelect = {
                            // SurfaceFlinger is experimental and can reboot some devices — require opt-in.
                            if (it == "SurfaceFlinger" && selectedRenderer != "SurfaceFlinger") showSfWarning = true
                            else selectedRenderer = it
                        }
                    )
                    if (showSfWarning) {
                        SurfaceFlingerWarningDialog(
                            onConfirm = { selectedRenderer = "SurfaceFlinger"; showSfWarning = false },
                            onDismiss = { showSfWarning = false }
                        )
                    }

                    // SurfaceFlinger colour correction (ASR-only, GN #1620) — only relevant when this
                    // game runs on the SurfaceFlinger renderer, so surface it under that choice.
                    if (selectedRenderer == "SurfaceFlinger") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.renderer_sf_compat))
                                Text(
                                    stringResource(R.string.renderer_sf_compat_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = sfCompatMode, onCheckedChange = { sfCompatMode = it })
                        }
                    }

                    // Render scale (supersampling) — per-game override of the container default.
                    run {
                        val renderScaleValues = listOf("1.0", "1.25", "1.5", "2.0")
                        val renderScaleLabels = listOf("Off", "1.25x", "1.5x", "2x")
                        val rsIdx = renderScaleValues.indexOf(renderScale).coerceAtLeast(0)
                        LabeledDropdown(
                            label = "Render scale (supersampling)",
                            options = renderScaleLabels,
                            selectedOption = renderScaleLabels[rsIdx],
                            onSelect = { renderScale = renderScaleValues[renderScaleLabels.indexOf(it)] }
                        )
                    }

                    // Frame Generation engine — per-game override (lsfg grayed without Lossless.dll).
                    run {
                        val fgLabels = listOf(
                            stringResource(R.string.frame_generation_off),
                            stringResource(R.string.frame_generation_bionic),
                            stringResource(R.string.frame_generation_lsfg)
                        )
                        val fgIdx = fgEngines.indexOf(frameGenEngine).coerceAtLeast(0)
                        LabeledDropdown(
                            label = stringResource(R.string.frame_generation),
                            options = fgLabels,
                            selectedOption = fgLabels[fgIdx],
                            onSelect = { frameGenEngine = fgEngines[fgLabels.indexOf(it)] },
                            disabledOptions = if (lsfgDllAvailable) emptySet() else setOf(fgLabels[2])
                        )
                        if (!lsfgDllAvailable) {
                            Text(
                                text = stringResource(R.string.frame_generation_lsfg_needs_dll),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // FPS limiter — per-game override.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = fpsLimiterEnabled, onCheckedChange = { fpsLimiterEnabled = it })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.fps_limiter), modifier = Modifier.weight(1f))
                    }

                    // Audio driver
                    LabeledDropdown(
                        label = stringResource(R.string.audio_driver),
                        options = audioDriverEntries,
                        selectedOption = selectedAudioDriver,
                        onSelect = { selectedAudioDriver = it }
                    )

                    // Emulator
                    LabeledDropdown(
                        label = "Emulator",
                        options = emulatorEntries,
                        selectedOption = selectedEmulator,
                        onSelect = { selectedEmulator = it },
                        enabled = isArm64EC
                    )

                    // MIDI
                    if (midiList.isNotEmpty()) {
                        val midiDisplay = midiList.firstOrNull { it == selectedMidi } ?: midiList.first()
                        LabeledDropdown(
                            label = "MIDI Sound Font",
                            options = midiList,
                            selectedOption = midiDisplay,
                            onSelect = { selectedMidi = it }
                        )
                    }

                    // LC_ALL
                    OutlinedTextField(
                        value = lcAll,
                        onValueChange = { lcAll = it },
                        label = { Text("LC_ALL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Fullscreen aspect-ratio mode (#71) — per-game override. Index 0 = use the
                    // container default; indices 1..5 map to Container.FULLSCREEN_OFF/FIT/STRETCH/FILL/INTEGER.
                    val fsOverrideLabels = listOf(
                        stringResource(R.string.fullscreen_mode_default),
                        stringResource(R.string.fullscreen_mode_off),
                        stringResource(R.string.fullscreen_mode_fit),
                        stringResource(R.string.fullscreen_mode_stretch),
                        stringResource(R.string.fullscreen_mode_fill),
                        stringResource(R.string.fullscreen_mode_integer)
                    )
                    val fsOverrideIdx = if (fullscreenModeOverride < 0) 0 else (fullscreenModeOverride + 1)
                        .coerceIn(1, fsOverrideLabels.size - 1)
                    LabeledDropdown(
                        label = stringResource(R.string.fullscreen_mode),
                        options = fsOverrideLabels,
                        selectedOption = fsOverrideLabels[fsOverrideIdx],
                        onSelect = { sel ->
                            val idx = fsOverrideLabels.indexOf(sel)
                            fullscreenModeOverride = if (idx <= 0) -1 else idx - 1
                        }
                    )

                    // Close the session when this game exits (per-game override; container default is ON)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoCloseOnExit, onCheckedChange = { autoCloseOnExit = it })
                        Text("Close when game exits")
                    }

                    // Input section
                    SectionBox(title = "Input") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = enableXInput,
                                onCheckedChange = { enableXInput = it },
                                enabled = exclusiveXInput
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.enable_xinput_for_wine_game), modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = enableDInput,
                                onCheckedChange = { enableDInput = it },
                                enabled = exclusiveXInput
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.enable_dinput_for_wine_game), modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = exclusiveXInput,
                                onCheckedChange = { checked ->
                                    exclusiveXInput = checked
                                    if (!checked) { enableXInput = true; enableDInput = true }
                                    else if (enableXInput && enableDInput) enableDInput = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Exclusive Input", modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = disabledXInput, onCheckedChange = { disabledXInput = it })
                            Text("Disable XInput")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = simTouchScreen, onCheckedChange = { simTouchScreen = it })
                            Text("Touchscreen Mode")
                        }
                        LabeledDropdown(
                            label = "Num Controllers",
                            options = numControllersEntries,
                            selectedOption = selectedNumControllers,
                            onSelect = { selectedNumControllers = it }
                        )
                    }

                    // Tabs
                    TabRow(selectedTabIndex = selectedTab) {
                        tabTitles.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(title) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // Tab content
                    when (selectedTab) {
                        0 -> ScWinComponentsTab(winComponents)
                        1 -> ScEnvVarsTab(shortcut, envVarsViewRef)
         2 -> ScAdvancedTab(
            isArm64EC = isArm64EC,
            box64Versions = box64Versions,
            selectedBox64Version = selectedBox64Version,
            onBox64VersionChange = { selectedBox64Version = it },
            box64Presets = box64Presets,
            selectedBox64PresetIndex = selectedBox64PresetIndex,
            onBox64PresetIndexChange = { selectedBox64PresetIndex = it },
            fexCoreVersions = fexCoreVersions,
            selectedFexCoreVersion = selectedFexCoreVersion,
            onFexVersionChange = { selectedFexCoreVersion = it },
            fexCorePresets = fexCorePresets,
            selectedFexPresetIndex = selectedFexCorePresetIndex,
            onFexPresetIndexChange = { selectedFexCorePresetIndex = it },
            controlsProfiles = controlsProfiles,
            selectedControlsProfileIndex = selectedControlsProfileIndex,
            onControlsProfileChange = { selectedControlsProfileIndex = it },
            startupSelectionEntries = startupSelectionEntries,
            selectedStartupSelection = selectedStartupSelection,
            onStartupChange = { selectedStartupSelection = it },
            cpuListViewRef = cpuListViewRef,
            initialCpuList = shortcut.getExtra("cpuList", shortcut.container.getCPUList(true)),
            onCpuListSnapshot = { shortcut.putExtra("cpuList", it) },
            sharpnessEffectEntries = sharpnessEffectEntries,
            selectedSharpnessEffect = selectedSharpnessEffect,
            onSharpnessEffectChange = { selectedSharpnessEffect = it },
            sharpnessLevel = sharpnessLevel,
            onSharpnessLevelChange = { sharpnessLevel = it },
            sharpnessDenoise = sharpnessDenoise,
            onSharpnessDenoiseChange = { sharpnessDenoise = it },
            reshadeLoadout = reshadeLoadout,
            reshadeEffects = reshadeEffects,
            onReshadeCatalogChanged = {
                reshadeEffects = ReshadeManager.scanEffects(context)
                reshadeLoadout.reconcile(reshadeEffects)
            },
            reshadeSupported = StringUtils.parseIdentifier(selectedDxWrapper).let { it.contains("dxvk") || it.contains("vegas") },
            onShowBox64DownloadSheet = { showBox64DownloadSheet = true },
            onShowFexCoreDownloadSheet = { showFexCoreDownloadSheet = true }
        )
                    }
                }

                Divider(color = DividerColor)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { save(); onDismiss() }) { Text(stringResource(android.R.string.ok)) }
                }
            }
        }

    // Config dialogs + download sheets are composed INSIDE the settings Dialog's window so the
    // ModalBottomSheet (ContentDownloadSheet) renders on top of it. When they were outside the
    // Dialog, the bottom sheet's window appeared BEHIND the settings dialog (couldn't be used).
    if (showGfxConfig) {
        GraphicsDriverConfigDialog(
            graphicsDriver = StringUtils.parseIdentifier(selectedGfxDriver),
            initialConfig = graphicsDriverConfig,
            onConfirm = { graphicsDriverConfig = it; showGfxConfig = false },
            onDismiss = { showGfxConfig = false }
        )
    }
    val isVegasCfg = StringUtils.parseIdentifier(selectedDxWrapper).contains("vegas")
    if (showDxvkConfig) {
        DxvkConfigDialog(
            isArm64EC = isArm64EC,
            isVegas = isVegasCfg,
            initialConfig = dxWrapperConfig,
            onConfirm = { dxWrapperConfig = it; showDxvkConfig = false },
            onDismiss = { showDxvkConfig = false },
            onDownloadDxvk = { showDxvkConfig = false; if (isVegasCfg) showVegasDownloadSheet = true else showDxvkDownloadSheet = true },
            onDownloadVkd3d = { showDxvkConfig = false; showVkd3dDownloadSheet = true }
        )
    }
    if (showWineD3DConfig) {
        WineD3DConfigDialog(
            initialConfig = dxWrapperConfig,
            onConfirm = { dxWrapperConfig = it; showWineD3DConfig = false },
            onDismiss = { showWineD3DConfig = false }
        )
    }

    if (showBox64DownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_BOX64,
            onDismiss = { showBox64DownloadSheet = false },
            onContentChanged = {}
        )
    }
    if (showFexCoreDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
            onDismiss = { showFexCoreDownloadSheet = false },
            onContentChanged = {}
        )
    }
    if (showDxvkDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_DXVK,
            onDismiss = { showDxvkDownloadSheet = false },
            onContentChanged = {}
        )
    }
    if (showVkd3dDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
            onDismiss = { showVkd3dDownloadSheet = false },
            onContentChanged = {}
        )
    }
    if (showVegasDownloadSheet) {
        VegasDownloadSheet(
            onDismiss = { showVegasDownloadSheet = false },
            onContentChanged = {}
        )
    }
    } // settings Dialog
}

@Composable
private fun ScWinComponentsTab(components: androidx.compose.runtime.snapshots.SnapshotStateList<WinComponentEntry>) {
    val directx = components.filter { it.key.startsWith("direct") }
    val general = components.filterNot { it.key.startsWith("direct") }
    val options = listOf("Builtin (Wine)", "Native (Windows)")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (directx.isNotEmpty()) {
            SectionBox(title = "DirectX") {
                directx.forEach { comp ->
                    LabeledDropdown(
                        label = comp.label,
                        options = options,
                        selectedOption = options.getOrElse(comp.selectedIndex) { options[0] },
                        onSelect = { opt ->
                            val i = components.indexOfFirst { it.key == comp.key }
                            if (i >= 0) components[i] = components[i].copy(selectedIndex = options.indexOf(opt).coerceAtLeast(0))
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (general.isNotEmpty()) {
            SectionBox(title = "General") {
                general.forEach { comp ->
                    LabeledDropdown(
                        label = comp.label,
                        options = options,
                        selectedOption = options.getOrElse(comp.selectedIndex) { options[0] },
                        onSelect = { opt ->
                            val i = components.indexOfFirst { it.key == comp.key }
                            if (i >= 0) components[i] = components[i].copy(selectedIndex = options.indexOf(opt).coerceAtLeast(0))
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ScEnvVarsTab(shortcut: Shortcut, envVarsViewRef: MutableState<EnvVarsView?>) {
    var showAddEnvVar by remember { mutableStateOf(false) }
    // Flush the legacy EnvVarsView's contents back into the Shortcut's in-memory
    // extras before the tab leaves composition, so a tab switch doesn't drop
    // in-progress edits. shortcut.putExtra mutates only the in-memory JSONObject;
    // disk persistence still happens later in save() -> saveData().
    DisposableEffect(Unit) {
        onDispose {
            envVarsViewRef.value?.let { shortcut.putExtra("envVars", it.envVars.ifEmpty { null }) }
            envVarsViewRef.value = null
        }
    }
    Column {
        AndroidView(
            factory = { ctx ->
                EnvVarsView(ctx).also { ev ->
                    ev.setDarkMode(true)
                    ev.setEnvVars(com.winlator.star.core.EnvVars(shortcut.getExtra("envVars")))
                    envVarsViewRef.value = ev
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showAddEnvVar = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add Environment Variable")
        }
    }
    if (showAddEnvVar) {
        AddEnvVarComposable(
            onConfirm = { name, value ->
                envVarsViewRef.value?.let { ev ->
                    if (name.isNotEmpty() && !ev.containsName(name)) ev.add(name, value)
                }
                showAddEnvVar = false
            },
            onDismiss = { showAddEnvVar = false }
        )
    }
}

@Composable
private fun ScAdvancedTab(
    isArm64EC: Boolean,
    box64Versions: List<String>,
    selectedBox64Version: String,
    onBox64VersionChange: (String) -> Unit,
    box64Presets: List<Box64Preset>,
    selectedBox64PresetIndex: Int,
    onBox64PresetIndexChange: (Int) -> Unit,
    fexCoreVersions: List<String>,
    selectedFexCoreVersion: String,
    onFexVersionChange: (String) -> Unit,
    fexCorePresets: List<FEXCorePreset>,
    selectedFexPresetIndex: Int,
    onFexPresetIndexChange: (Int) -> Unit,
    controlsProfiles: List<ControlsProfile>,
    selectedControlsProfileIndex: Int,
    onControlsProfileChange: (Int) -> Unit,
    startupSelectionEntries: List<String>,
    selectedStartupSelection: String,
    onStartupChange: (String) -> Unit,
    cpuListViewRef: MutableState<CPUListView?>,
    initialCpuList: String,
    onCpuListSnapshot: (String) -> Unit,
    sharpnessEffectEntries: List<String>,
    selectedSharpnessEffect: String,
    onSharpnessEffectChange: (String) -> Unit,
    sharpnessLevel: Int,
    onSharpnessLevelChange: (Int) -> Unit,
    sharpnessDenoise: Int,
    onSharpnessDenoiseChange: (Int) -> Unit,
    reshadeLoadout: ReshadeLoadoutState = ReshadeLoadoutState(),
    reshadeEffects: List<ReshadeManager.ReshadeEffect> = emptyList(),
    onReshadeCatalogChanged: () -> Unit = {},
    reshadeSupported: Boolean = true,
    onShowBox64DownloadSheet: () -> Unit = {},
    onShowFexCoreDownloadSheet: () -> Unit = {},
) {
    // Flush legacy CPUListView selection back to the parent (Shortcut extras)
    // before the tab leaves composition, so a tab switch doesn't drop edits.
    DisposableEffect(Unit) {
        onDispose {
            cpuListViewRef.value?.let { onCpuListSnapshot(it.checkedCPUListAsString) }
            cpuListViewRef.value = null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionBox(title = "Box64") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LabeledDropdown(
                    label = stringResource(R.string.box64_version),
                    options = box64Versions,
                    selectedOption = box64Versions.firstOrNull { it == selectedBox64Version } ?: selectedBox64Version,
                    onSelect = onBox64VersionChange,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onShowBox64DownloadSheet,
                    modifier = Modifier.size(40.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Download Box64", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            val presetNames = box64Presets.map { it.name }
            LabeledDropdown(
                label = stringResource(R.string.box64_preset),
                options = presetNames,
                selectedOption = presetNames.getOrElse(selectedBox64PresetIndex) { "" },
                onSelect = { opt -> onBox64PresetIndexChange(presetNames.indexOf(opt).coerceAtLeast(0)) }
            )
        }

        if (isArm64EC) {
            SectionBox(title = "FEXCore") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledDropdown(
                        label = stringResource(R.string.fexcore_version),
                        options = fexCoreVersions,
                        selectedOption = fexCoreVersions.firstOrNull { it == selectedFexCoreVersion } ?: selectedFexCoreVersion,
                        onSelect = onFexVersionChange,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onShowFexCoreDownloadSheet,
                        modifier = Modifier.size(40.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Download FEXCore", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val fexNames = fexCorePresets.map { it.name }
                LabeledDropdown(
                    label = stringResource(R.string.fexcore_preset),
                    options = fexNames,
                    selectedOption = fexNames.getOrElse(selectedFexPresetIndex) { "" },
                    onSelect = { opt -> onFexPresetIndexChange(fexNames.indexOf(opt).coerceAtLeast(0)) }
                )
            }
        }

        val profileNames = mutableListOf(stringResource(R.string.none))
        profileNames.addAll(controlsProfiles.map { it.getName() })
        LabeledDropdown(
            label = "Controls Profile",
            options = profileNames,
            selectedOption = profileNames.getOrElse(selectedControlsProfileIndex) { profileNames.first() },
            onSelect = { opt -> onControlsProfileChange(profileNames.indexOf(opt).coerceAtLeast(0)) }
        )

        LabeledDropdown(
            label = stringResource(R.string.startup_selection),
            options = startupSelectionEntries,
            selectedOption = selectedStartupSelection,
            onSelect = onStartupChange
        )

        SectionBox(title = stringResource(R.string.processor_affinity)) {
            AndroidView(
                factory = { ctx ->
                    CPUListView(ctx).also { cpv ->
                        cpv.setCheckedCPUList(initialCpuList)
                        cpuListViewRef.value = cpv
                    }
                },
                modifier = Modifier.fillMaxWidth().wrapContentHeight()
            )
        }

        SectionBox(title = "Sharpness (VKBasalt)") {
            LabeledDropdown(
                label = "Effect",
                options = sharpnessEffectEntries,
                selectedOption = selectedSharpnessEffect,
                onSelect = onSharpnessEffectChange
            )
            Spacer(Modifier.height(8.dp))
            Text("Level: $sharpnessLevel%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = sharpnessLevel.toFloat(),
                onValueChange = { onSharpnessLevelChange(it.toInt()) },
                valueRange = 0f..100f,
                steps = 99
            )
            Text("Denoise: $sharpnessDenoise%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = sharpnessDenoise.toFloat(),
                onValueChange = { onSharpnessDenoiseChange(it.toInt()) },
                valueRange = 0f..100f,
                steps = 99
            )
        }

        // ReShade multi-effect loadout (vkBasalt drop-in). Multi-select picker + solo/stack mode + a
        // collapsible per-effect param block. Only applies to DXVK/VKD3D (Vulkan) games — a hint shows.
        SectionBox(title = "ReShade loadout") {
            ReshadeLoadoutEditor(
                state = reshadeLoadout,
                effects = reshadeEffects,
                supported = reshadeSupported,
                onCatalogChanged = onReshadeCatalogChanged,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Non-composable helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun renameShortcut(shortcut: Shortcut, newName: String) {
    val parent = shortcut.file.parentFile ?: return
    val oldFile = shortcut.file
    val newFile = File(parent, "$newName.desktop")
    if (!newFile.isFile && oldFile.renameTo(newFile)) {
        runCatching {
            val field: Field = Shortcut::class.java.getDeclaredField("file")
            field.isAccessible = true
            field.set(shortcut, newFile)
        }
        val lnk = File(parent, "${shortcut.name}.lnk")
        if (lnk.isFile) lnk.renameTo(File(parent, "$newName.lnk"))
    }
}

private fun runShortcut(activity: Activity, shortcut: Shortcut) {
    if (!XrActivity.isEnabled(activity)) {
        val intent = Intent(activity, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", shortcut.container.id)
            putExtra("shortcut_path", shortcut.file.path)
            putExtra("shortcut_name", shortcut.name)
            putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"))
        }
        activity.startActivity(intent)
    } else {
        XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.path)
    }
}

private fun addToHomeScreen(context: Context, shortcut: Shortcut) {
    if (shortcut.getExtra("uuid").isEmpty()) shortcut.genUUID()
    try {
        val sm = ContextCompat.getSystemService(context, ShortcutManager::class.java)
        if (sm != null && sm.isRequestPinShortcutSupported) {
            val intent = Intent(context, XServerDisplayActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("container_id", shortcut.container.id)
                putExtra("shortcut_path", shortcut.file.path)
            }
            val bmp: Bitmap = shortcut.icon
                ?: BitmapFactory.decodeResource(context.resources, com.winlator.star.R.drawable.icon_wine)
            val info = ShortcutInfo.Builder(context, shortcut.getExtra("uuid"))
                .setShortLabel(shortcut.name)
                .setLongLabel(shortcut.name)
                .setIcon(Icon.createWithBitmap(bmp))
                .setIntent(intent)
                .build()
            sm.requestPinShortcut(info, null)
        }
    } catch (_: Exception) {}
}

private fun exportShortcut(context: Context, shortcut: Shortcut) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    val uriString = prefs.getString("shortcuts_export_path_uri", null)

    val shortcutsDir: File = if (uriString != null) {
        val folderUri = Uri.parse(uriString)
        val pickedDir = DocumentFile.fromTreeUri(context, folderUri)
        if (pickedDir == null || !pickedDir.canWrite()) {
            Toast.makeText(context, "Cannot write to the selected folder", Toast.LENGTH_SHORT).show()
            return
        }
        File(FileUtils.getFilePathFromUri(context, folderUri))
    } else {
        File(SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH)
    }

    if (!shortcutsDir.exists() && !shortcutsDir.mkdirs()) {
        Toast.makeText(context, "Failed to create default directory", Toast.LENGTH_SHORT).show()
        return
    }

    val exportFile = File(shortcutsDir, shortcut.file.name)
    val fileExists = exportFile.exists()

    try {
        val lines = mutableListOf<String>()
        var containerIdFound = false
        BufferedReader(FileReader(shortcut.file)).use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.startsWith("container_id:")) {
                    lines += "container_id:${shortcut.container.id}"
                    containerIdFound = true
                } else {
                    lines += line
                }
            }
        }
        if (!containerIdFound) lines += "container_id:${shortcut.container.id}"

        FileWriter(exportFile, false).use { w ->
            lines.forEach { w.write("$it\n") }
        }

        Toast.makeText(
            context,
            if (fileExists) "Shortcut updated at ${exportFile.path}" else "Shortcut exported to ${exportFile.path}",
            Toast.LENGTH_LONG,
        ).show()
    } catch (_: IOException) {
        Toast.makeText(context, "Failed to export shortcut", Toast.LENGTH_LONG).show()
    }
}

