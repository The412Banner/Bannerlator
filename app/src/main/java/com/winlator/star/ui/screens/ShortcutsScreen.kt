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
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import android.graphics.drawable.Icon
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.SideEffect
import com.winlator.star.ui.AccountAvatar
import com.winlator.star.ui.AccountUiBus
import com.winlator.star.ui.ComponentReturnBus
import com.winlator.star.ui.LocalTopBarActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.winlator.star.container.Container
import com.winlator.star.container.GameDetails
import com.winlator.star.container.Shortcut
import com.winlator.star.reshade.ReshadeManager
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.WrapperManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.screens.adrenodownload.AdrenoDriverDownloadSheet
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverEntry
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverRepository
import com.winlator.star.core.DefaultVersion
import com.winlator.star.core.FileUtils
import com.winlator.star.core.GameFolderScanner
import com.winlator.star.core.CustomSaveVault
import com.winlator.star.core.GameSaveBackup
import com.winlator.star.core.KeyValueSet
import com.winlator.star.ui.components.ContainerGlossarySheet
import com.winlator.star.ui.components.DraggableAddButton
import com.winlator.star.ui.theme.DangerRed
import com.winlator.star.core.LogInventory
import com.winlator.star.core.LogLocation
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WineInfo
import com.winlator.star.core.WinePath
import com.winlator.star.core.WineUtils
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.fexcore.FEXCorePresetManager
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.midi.MidiManager
import com.winlator.star.store.StarLaunchBridge
import com.winlator.star.store.SteamSaveManagerActivity
import com.winlator.star.store.SteamStoreSearch
import com.winlator.star.store.compose.ContainerPickerDialog
import com.winlator.star.ui.theme.Divider as DividerColor
import com.winlator.star.ui.theme.LocalAccentDim
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.Surface as SurfaceColor
import com.winlator.star.ui.theme.SurfaceVariant
import com.winlator.star.ui.theme.SurfaceVariant as SurfaceVariantColor
import com.winlator.star.widget.CPUListView
import com.winlator.star.ui.components.EnvVarsEditor
import com.winlator.star.ui.components.PlayerSlotsEditor
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
    val viewMode by vm.viewMode.collectAsState()
    val context = LocalContext.current
    val activity = context as Activity

    var confirmRemove by remember { mutableStateOf<Shortcut?>(null) }
    // Multi-select. Keyed by file path rather than by Shortcut because refresh() rebuilds the
    // objects, and a set of stale instances would silently stop matching anything.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var confirmRemoveSelected by remember { mutableStateOf(false) }
    var cloneTarget by remember { mutableStateOf<Shortcut?>(null) }
    // Save Backup (custom-import games): a picked .zip awaiting a target-container choice, plus the
    // label of the game the restore was launched from (shown in the container picker title).
    var restoreZipUri by remember { mutableStateOf<Uri?>(null) }
    var restoreForName by remember { mutableStateOf("") }
    // The shortcut whose "Back up saves" layout-choice dialog is open (Winlator vs GameHub).
    var backupFormatShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var settingsShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var gameDetailsShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var propertiesShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var logsShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showImportContainerPicker by remember { mutableStateOf(false) }
    var pendingImportContainerIndex by remember { mutableStateOf(-1) }
    // Bulk games-folder import: pick one folder holding many game folders, scan each for its exe,
    // then confirm the findings before anything is written to the container.
    var showImportMethodPicker by remember { mutableStateOf(false) }
    var folderScanRunning by remember { mutableStateOf(false) }
    var folderScanResults by remember { mutableStateOf<List<GameFolderScanner.Candidate>>(emptyList()) }
    var folderScanSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var folderScanRoot by remember { mutableStateOf("") }
    var folderImportRunning by remember { mutableStateOf(false) }
    // Manual exe override for a scanned game. The scanner keeps every runner-up, so correcting a
    // pick is a choice from a list rather than a rescan.
    var exePickerFor by remember { mutableStateOf<GameFolderScanner.Candidate?>(null) }
    var exeBrowseForPath by remember { mutableStateOf("") }
    // When checked, the shortcut import uses the system SAF picker instead of the in-app File Manager.
    var importUseSystemPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDialogName by remember { mutableStateOf("") }          // current on-disk base (Save's oldName)
    var renameDialogContainerIndex by remember { mutableStateOf(-1) }
    // Confirm-game dialog (upgraded rename dialog) state.
    var confirmNameField by remember { mutableStateOf("") }          // editable name text field
    var confirmNameEdited by remember { mutableStateOf(false) }      // user typed → stop auto-overwriting
    var confirmAppId by remember { mutableStateOf<Int?>(null) }      // detected/selected Steam appId
    var steamSearchResults by remember { mutableStateOf<List<SteamStoreSearch.SteamSuggestion>>(emptyList()) }
    var steamSearching by remember { mutableStateOf(false) }
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
                    confirmNameField = result.shortcutName
                    confirmNameEdited = false
                    confirmAppId = result.appId
                    steamSearchResults = emptyList()
                    steamSearching = false
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

    // ── Save Backup / Restore (custom-import games only) ──────────────────────────────────────────
    // In-app file picker for a backup .zip; on pick we hold the uri and show a target-container picker.
    // (GameSaveBackup.restore auto-detects the layout — GameHub steamuser <-> our xuser — so a GameHub
    // or Bannerlator save both restore through this one path; no format prompt needed.)
    val restoreSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { restoreZipUri = it }
    }

    // "Back up saves" → first pick the archive layout (Winlator vs GameHub), mirroring the Containers
    // backup menu; the chosen layout runs in runCustomBackup below.
    fun startSaveBackup(shortcut: Shortcut) {
        backupFormatShortcut = shortcut
    }

    // Back up this game's saves into the shared per-game folder via the one shared impl
    // (CustomSaveVault.manualBackup) so the ⋮ menu and the Save Manager agree; the dialog/UX + toasts
    // stay here. manualBackup discovers roots (with whole-container fallback) and zips off the main
    // thread, posting its result on the main thread.
    fun runCustomBackup(shortcut: Shortcut, layout: GameSaveBackup.BackupLayout) {
        val name = shortcut.name
        Toast.makeText(context, "Backing up saves for \"$name\"…", Toast.LENGTH_SHORT).show()
        CustomSaveVault.manualBackup(context, shortcut.container, shortcut, layout) { r ->
            if (r.wholeContainer && r.ok) {
                Toast.makeText(context, "No per-game saves detected — backed up the whole container.", Toast.LENGTH_LONG).show()
            }
            Toast.makeText(
                context,
                if (r.ok) "Backed up ${r.fileCount} files → ${r.path?.substringAfterLast('/')}"
                else "Backup failed: ${r.error ?: "unknown error"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // Restore: pick a .zip (SAF) → then choose the target container (ContainerPickerDialog) → restore.
    fun startSaveRestore(shortcut: Shortcut) {
        restoreForName = shortcut.name
        restoreSaveLauncher.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.SAVE, "Select a save .zip"))
    }
    // Built-in in-app file picker (primary).
    val importFileInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { handleShortcutImport(it) }
    }
    // Games-folder picker. Uses buildDirIntent (a real absolute path, works on SD) rather than SAF,
    // which hands back /mnt/media_rw/... paths the scanner can't read.
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data) else null
        if (path == null) {
            pendingImportContainerIndex = -1
            return@rememberLauncherForActivityResult
        }
        val containerIndex = pendingImportContainerIndex
        folderScanRoot = path
        folderScanRunning = true
        scope.launch {
            // Filesystem-heavy: a large library on SD walks a lot of directories.
            val found = withContext(Dispatchers.IO) { vm.scanGamesFolder(containerIndex, path) }
            folderScanResults = found
            // Pre-select everything importable; duplicates stay off and can't be ticked.
            folderScanSelected = found.filter { !it.alreadyAdded }.map { it.exe.absolutePath }.toSet()
            folderScanRunning = false
            if (found.isEmpty()) {
                Toast.makeText(context, "No games found in that folder", Toast.LENGTH_LONG).show()
                pendingImportContainerIndex = -1
            }
        }
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
    // Tier-2 session-return: after an installer-based component install finishes and MainActivity has
    // routed us here, re-open the originating shortcut's settings (defaults to the Win Components tab,
    // where its recommended-components chips live). Keyed on the shortcuts list too, since it loads
    // async — wait for it, then resolve by container + base name. Best-effort: no match ⇒ stay on Games.
    LaunchedEffect(ComponentReturnBus.openShortcutSettings, shortcuts) {
        val target = ComponentReturnBus.openShortcutSettings ?: return@LaunchedEffect
        if (shortcuts.isEmpty()) return@LaunchedEffect // still loading (or none) — retry when it changes
        ComponentReturnBus.openShortcutSettings = null
        shortcuts.firstOrNull {
            it.container.id == target.containerId && it.name == target.shortcutBase
        }?.let { settingsShortcut = it }
    }

    val topBarActions = LocalTopBarActions.current
    // LaunchedEffect — not SideEffect — so this runs in the same dispatcher queue as
    // MainActivity's route-change clear (which is a LaunchedEffect). Parent enqueues
    // first and runs first (clears); we enqueue second and run after (sets). A
    // SideEffect would run synchronously during commit, getting steamrolled by the
    // parent's clear when it fires post-commit.
    LaunchedEffect(viewMode, selectionMode, selectedPaths) {
        topBarActions.value = {
            IconButton(onClick = { showCommunityBrowser = true }) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = "Community configs",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            if (selectionMode) {
                Text(
                    "${selectedPaths.size}",
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.padding(end = 2.dp),
                )
                IconButton(
                    onClick = { confirmRemoveSelected = true },
                    enabled = selectedPaths.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove selected",
                        tint = if (selectedPaths.isEmpty())
                            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f)
                        else DangerRed,
                    )
                }
            }
            IconButton(onClick = {
                selectionMode = !selectionMode
                if (!selectionMode) selectedPaths = emptySet()
            }) {
                Icon(
                    imageVector = if (selectionMode) Icons.Filled.Close else Icons.Filled.Checklist,
                    contentDescription = if (selectionMode) "Cancel selection" else "Select shortcuts",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            // One button cycling list → grid → compact grid. The icon shows what you get NEXT,
            // matching how the two-state version behaved.
            IconButton(onClick = { vm.cycleViewMode() }) {
                Icon(
                    imageVector = when (viewMode) {
                        ShortcutViewMode.LIST -> Icons.Filled.GridView
                        ShortcutViewMode.GRID -> Icons.Filled.Apps
                        ShortcutViewMode.GRID_COMPACT -> Icons.Filled.ViewList
                    },
                    contentDescription = when (viewMode) {
                        ShortcutViewMode.LIST -> "Grid view"
                        ShortcutViewMode.GRID -> "Compact grid view"
                        ShortcutViewMode.GRID_COMPACT -> "List view"
                    },
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
                AnimatedContent(targetState = viewMode, label = "layout") { mode ->
                    if (mode != ShortcutViewMode.LIST) {
                        LazyVerticalGrid(
                            // Compact fixes four columns; the original stays adaptive so it keeps
                            // whatever column count each screen size was already giving.
                            columns = if (mode == ShortcutViewMode.GRID_COMPACT) GridCells.Fixed(4)
                                      else GridCells.Adaptive(minSize = 120.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(shortcuts, key = { it.file.path }) { shortcut ->
                                ShortcutGridItem(
                                    shortcut = shortcut,
                                    selectionMode = selectionMode,
                                    selected = shortcut.file.path in selectedPaths,
                                    onRun = {
                                        if (selectionMode) selectedPaths = selectedPaths.toggle(shortcut.file.path)
                                        else runShortcut(activity, shortcut)
                                    },
                                    onSettings = { settingsShortcut = shortcut },
                                    onRemove = { confirmRemove = shortcut },
                                    onClone = { cloneTarget = shortcut },
                                    onAddToHome = { addToHomeScreen(context, shortcut) },
                                    onExport = { exportShortcut(context, shortcut) },
                                    onProperties = { propertiesShortcut = shortcut },
                                    onScrapeCover = { scrapeCoverFor(shortcut) },
                                    onCommunityConfigs = { communityConfigsFor(shortcut) },
                                    onGameDetails = { gameDetailsShortcut = shortcut },
                                    onViewLogs = { logsShortcut = shortcut },
                                    onCloudSaves = if (isSteamOriginShortcut(shortcut))
                                        ({ launchSaveManager(context, steamAppIdOf(shortcut)) }) else null,
                                    onBackupSaves = if (isCustomShortcut(shortcut))
                                        ({ startSaveBackup(shortcut) }) else null,
                                    onRestoreSaves = if (isCustomShortcut(shortcut))
                                        ({ startSaveRestore(shortcut) }) else null,
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(shortcuts, key = { it.file.path }) { shortcut ->
                                val itemRun = {
                                    if (selectionMode) selectedPaths = selectedPaths.toggle(shortcut.file.path)
                                    else runShortcut(activity, shortcut)
                                }
                                val itemSettings = { settingsShortcut = shortcut }
                                val itemRemove = { confirmRemove = shortcut }
                                val itemClone = { cloneTarget = shortcut }
                                val itemAddToHome = { addToHomeScreen(context, shortcut) }
                                val itemExport = { exportShortcut(context, shortcut) }
                                val itemProperties = { propertiesShortcut = shortcut }
                                ShortcutItemLayoutL(
                                    shortcut = shortcut,
                                    selectionMode = selectionMode,
                                    selected = shortcut.file.path in selectedPaths,
                                    onRun = itemRun,
                                    onSettings = itemSettings,
                                    onRemove = itemRemove,
                                    onClone = itemClone,
                                    onAddToHome = itemAddToHome,
                                    onExport = itemExport,
                                    onProperties = itemProperties,
                                    onScrapeCover = { scrapeCoverFor(shortcut) },
                                    onCommunityConfigs = { communityConfigsFor(shortcut) },
                                    onGameDetails = { gameDetailsShortcut = shortcut },
                                    onViewLogs = { logsShortcut = shortcut },
                                    onCloudSaves = if (isSteamOriginShortcut(shortcut))
                                        ({ launchSaveManager(context, steamAppIdOf(shortcut)) }) else null,
                                    onBackupSaves = if (isCustomShortcut(shortcut))
                                        ({ startSaveBackup(shortcut) }) else null,
                                    onRestoreSaves = if (isCustomShortcut(shortcut))
                                        ({ startSaveRestore(shortcut) }) else null,
                                )
                            }
                        }
                    }
                }
            }
            // Long-press and slide along the bottom to move it off a card's buttons.
            DraggableAddButton(
                prefKey = "games",
                onClick = { showImportContainerPicker = true },
                outerPadding = 16.dp,
                buttonModifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
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

    /**
     * Swaps the chosen exe for one scanned game. The user has now made the call explicitly, so the
     * "check this one" flag is cleared, the previous pick joins the alternatives (in case they want
     * to go back), and the selection set is re-keyed since it is keyed by exe path.
     */
    fun replaceScannedExe(target: GameFolderScanner.Candidate, newExe: File) {
        val oldKey = target.exe.absolutePath
        val newKey = newExe.absolutePath
        folderScanResults = folderScanResults.map { c ->
            if (c.exe.absolutePath != oldKey) c else c.copy(
                exe = newExe,
                uncertain = false,
                alternatives = (listOf(c.exe) + c.alternatives)
                    .distinctBy { it.absolutePath }
                    .filter { it.absolutePath != newKey },
            )
        }
        if (oldKey in folderScanSelected) folderScanSelected = folderScanSelected - oldKey + newKey
    }

    // "Browse…" result from the exe-override dialog: any file the user points at wins outright.
    val importExeBrowseLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data) else null
        val target = folderScanResults.firstOrNull { it.exe.absolutePath == exeBrowseForPath }
        if (path != null && target != null) replaceScannedExe(target, File(path))
        exeBrowseForPath = ""
    }

    // Manual exe override — every candidate exe found in that game's folder, best-ranked first.
    exePickerFor?.let { target ->
        val options = (listOf(target.exe) + target.alternatives).distinctBy { it.absolutePath }
        OutlinedAlertDialog(
            // The platform default width truncates exe names and their subfolder paths, which are
            // the whole point of this list.
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { exePickerFor = null },
            title = {
                Column {
                    Text("Choose the game's .exe")
                    Text(
                        target.name,
                        color = OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(options, key = { it.absolutePath }) { exe ->
                        val current = exe.absolutePath == target.exe.absolutePath
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable {
                                    replaceScannedExe(target, exe)
                                    exePickerFor = null
                                },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = current, onClick = {
                                replaceScannedExe(target, exe)
                                exePickerFor = null
                            })
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    exe.name,
                                    color = OnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                // Where it sits inside the game folder — the thing that actually
                                // distinguishes two same-named exes (x86 vs x64, bin/ vs root).
                                val rel = exe.absolutePath
                                    .removePrefix(target.folder.absolutePath)
                                    .removePrefix("/")
                                Text(
                                    if (rel.contains('/')) rel.substringBeforeLast('/') else "(folder root)",
                                    color = OnSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        }
                    }
                }
            },
            confirmButton = {
                // Escape hatch: the real exe can be missing from the list if it was filtered as an
                // installer/helper or sits deeper than the scan goes.
                TextButton(onClick = {
                    exeBrowseForPath = target.exe.absolutePath
                    exePickerFor = null
                    importExeBrowseLauncher.launch(
                        InAppFilePicker.buildIntent(context, InAppFilePicker.SHORTCUT, "Select the game's .exe")
                    )
                }) { Text("Browse…") }
            },
            dismissButton = {
                TextButton(onClick = { exePickerFor = null }) { Text("Cancel") }
            },
        )
    }

    // How to add: one exe (the original flow) or a whole folder of game folders.
    if (showImportMethodPicker) {
        OutlinedAlertDialog(
            onDismissRequest = {
                showImportMethodPicker = false
                pendingImportContainerIndex = -1
            },
            title = { Text("Add games") },
            text = {
                Column {
                    MenuOptionCard(
                        title = "Add game EXE",
                        subtitle = "Pick one game's .exe file",
                        icon = Icons.Default.InsertDriveFile,
                    ) {
                        showImportMethodPicker = false
                        if (importUseSystemPicker) importFileLauncher.launch("*/*")
                        else importFileInAppLauncher.launch(
                            InAppFilePicker.buildIntent(context, InAppFilePicker.SHORTCUT, "Select .exe / .desktop / .lnk")
                        )
                    }
                    MenuOptionCard(
                        title = "Add games folder",
                        subtitle = "Pick the folder holding all your games — each one is scanned for its .exe",
                        icon = Icons.Default.Folder,
                    ) {
                        showImportMethodPicker = false
                        importFolderLauncher.launch(
                            InAppFilePicker.buildDirIntent(context, "Select your games folder")
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showImportMethodPicker = false
                    pendingImportContainerIndex = -1
                }) { Text("Cancel") }
            },
        )
    }

    // Scanning progress — a big library on SD takes a moment.
    if (folderScanRunning) {
        OutlinedAlertDialog(
            onDismissRequest = {},
            title = { Text("Scanning for games") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(folderScanRoot, color = OnSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {},
        )
    }

    // Confirm screen — nothing is written until the user accepts this list.
    if (folderScanResults.isNotEmpty() && !folderScanRunning) {
        val importable = folderScanResults.filter { !it.alreadyAdded }
        val selectedCount = folderScanSelected.size
        OutlinedAlertDialog(
            // Each row carries art, a checkbox, three lines of text and a Change action; at the
            // platform default width the titles and the exe name truncate to the point of being
            // unreadable, which defeats a screen whose whole job is letting the user check them.
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = {
                if (!folderImportRunning) {
                    folderScanResults = emptyList()
                    folderScanSelected = emptySet()
                    pendingImportContainerIndex = -1
                }
            },
            title = {
                Column {
                    Text("Found ${importable.size} game${if (importable.size == 1) "" else "s"}")
                    val skipped = folderScanResults.size - importable.size
                    if (skipped > 0) {
                        Text(
                            "$skipped already added",
                            color = OnSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(folderScanResults, key = { it.exe.absolutePath }) { candidate ->
                        ScannedGameRow(
                            candidate = candidate,
                            checked = candidate.exe.absolutePath in folderScanSelected,
                            enabled = !candidate.alreadyAdded && !folderImportRunning,
                            onToggle = {
                                val key = candidate.exe.absolutePath
                                folderScanSelected = if (key in folderScanSelected) {
                                    folderScanSelected - key
                                } else {
                                    folderScanSelected + key
                                }
                            },
                            onChangeExe = { exePickerFor = candidate },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedCount > 0 && !folderImportRunning,
                    onClick = {
                        val containerIndex = pendingImportContainerIndex
                        val chosen = folderScanResults.filter { it.exe.absolutePath in folderScanSelected }
                        folderImportRunning = true
                        scope.launch {
                            val summary = withContext(Dispatchers.IO) {
                                vm.importScannedGames(containerIndex, chosen, context)
                            }
                            folderImportRunning = false
                            folderScanResults = emptyList()
                            folderScanSelected = emptySet()
                            pendingImportContainerIndex = -1
                            val message = if (summary.failed == 0) {
                                "Added ${summary.added} game${if (summary.added == 1) "" else "s"}"
                            } else {
                                "Added ${summary.added}, ${summary.failed} failed — ${summary.failures.firstOrNull().orEmpty()}"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Text(if (folderImportRunning) "Adding…" else "Add $selectedCount")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !folderImportRunning,
                    onClick = {
                        folderScanResults = emptyList()
                        folderScanSelected = emptySet()
                        pendingImportContainerIndex = -1
                    },
                ) { Text("Cancel") }
            },
        )
    }

    // Import container picker
    if (showImportContainerPicker) {
        val containers = vm.containers()
        OutlinedAlertDialog(
            onDismissRequest = { showImportContainerPicker = false },
            title = { Text("Select container") },
            text = {
                Column {
                    if (containers.isEmpty()) {
                        Text("No containers found.", color = OnSurfaceVariant)
                    } else {
                        // Scroll the container list so it can't be clipped when there are many
                        // containers and vertical space is tight (landscape). "Pick via system…"
                        // stays pinned below the scroll area so it's always reachable.
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            containers.forEachIndexed { index, c ->
                                MenuOptionCard(
                                    title = c.name,
                                    icon = Icons.Default.Folder,
                                ) {
                                    showImportContainerPicker = false
                                    pendingImportContainerIndex = index
                                    // Ask HOW to add before asking WHAT to add: one exe, or a whole
                                    // folder of game folders.
                                    showImportMethodPicker = true
                                }
                            }
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

    // Follow the importer's async Steam-name auto-rename inside an open confirm dialog: keep the
    // Save target (renameDialogName = the on-disk base) in sync, and update the editable field
    // unless the user has already typed into it. Race-free — all on the main thread, one file writer.
    val importedNameUpdate by vm.importedNameUpdate.collectAsState()
    LaunchedEffect(importedNameUpdate) {
        val update = importedNameUpdate ?: return@LaunchedEffect
        if (showRenameDialog && renameDialogName == update.oldBase) {
            renameDialogName = update.newBase
            if (!confirmNameEdited) confirmNameField = update.newBase
        }
        vm.consumeImportedNameUpdate()
    }

    // Confirm game after import: editable name + "Search Steam" picker (fixes launcher-named
    // shortcuts + wrong cover art). Reuses the existing rename mechanism on Save.
    if (showRenameDialog) {
        val confirmContainer = vm.containers().getOrNull(renameDialogContainerIndex)
        OutlinedAlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Confirm game") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = confirmNameField,
                        onValueChange = { confirmNameField = it; confirmNameEdited = true },
                        label = { Text("Game name") },
                        singleLine = true,
                        trailingIcon = {
                            if (confirmNameField.isNotEmpty()) {
                                IconButton(onClick = { confirmNameField = ""; confirmNameEdited = true }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Button(
                        onClick = {
                            val query = confirmNameField.trim()
                            if (query.isEmpty() || confirmContainer == null) return@Button
                            steamSearching = true
                            steamSearchResults = emptyList()
                            scope.launch(Dispatchers.IO) {
                                val results = SteamStoreSearch.searchByName(query)
                                withContext(Dispatchers.Main) {
                                    steamSearchResults = results
                                    steamSearching = false
                                }
                            }
                        },
                        enabled = !steamSearching && confirmNameField.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (steamSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Searching…")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Search Steam")
                        }
                    }

                    if (steamSearchResults.isNotEmpty()) {
                        Text(
                            "Tap a result to set the name + cover art:",
                            color = OnSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        steamSearchResults.forEach { hit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        // Set name + record appId; edit-guard off so the async
                                        // auto-rename won't clobber the user's explicit pick.
                                        confirmNameField = hit.name
                                        confirmNameEdited = true
                                        confirmAppId = hit.appId
                                        // Apply this appId's cover art to the current on-disk shortcut.
                                        if (confirmContainer != null) {
                                            val base = renameDialogName
                                            scope.launch(Dispatchers.IO) {
                                                val bmp = applySteamCover(confirmContainer, base, hit.appId)
                                                if (bmp != null) withContext(Dispatchers.Main) {
                                                    vm.reloadShortcut(
                                                        File(confirmContainer.getDesktopDir(), "$base.desktop").path,
                                                        bmp,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SteamResultThumbnail(hit.appId)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(hit.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("App ID: ${hit.appId}", fontSize = 11.sp, color = OnSurfaceVariant)
                                }
                            }
                        }
                    } else if (!steamSearching) {
                        Text(
                            "Not the right game? Edit the name and tap Search Steam to pick the correct one.",
                            color = OnSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }

                    // Recommended components — the redists this game bundles, one-tap installable into
                    // its container. Detection runs off-main-thread inside the section (Pillar 2/2.2).
                    if (confirmContainer != null) {
                        val importedExe = remember(confirmContainer, renameDialogName) {
                            runCatching {
                                val sc = Shortcut(
                                    confirmContainer,
                                    File(confirmContainer.getDesktopDir(), "$renameDialogName.desktop"),
                                )
                                WinePath.resolveAndroidPath(confirmContainer, sc.path)
                            }.getOrNull()
                        }
                        if (importedExe != null) {
                            Divider(color = DividerColor)
                            RecommendedComponentsSection(
                                container = confirmContainer,
                                exeFile = importedExe,
                                shortcutBaseName = renameDialogName,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = confirmNameField.trim()
                    // Record the confirmed appId on the current file first, so it rides through the rename.
                    confirmAppId?.let { id ->
                        confirmContainer?.let { c -> recordSteamAppId(c, renameDialogName, id) }
                    }
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
        OutlinedAlertDialog(
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

    // Bulk remove. Counts rather than names: a list of twenty titles is not something anyone
    // reads, and the number is the part that decides whether you meant it.
    if (confirmRemoveSelected) {
        val targets = shortcuts.filter { it.file.path in selectedPaths }
        OutlinedAlertDialog(
            onDismissRequest = { confirmRemoveSelected = false },
            title = { Text("Remove ${targets.size} shortcut${if (targets.size == 1) "" else "s"}?") },
            text = {
                Text(
                    if (targets.size == 1) "Remove \"${targets.first().name}\"?"
                    else "Remove these ${targets.size} shortcuts? The games themselves are left " +
                         "on disk — only the shortcuts go."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    var ok = 0
                    targets.forEach { if (vm.remove(it, context)) ok++ }
                    confirmRemoveSelected = false
                    selectedPaths = emptySet()
                    selectionMode = false
                    Toast.makeText(
                        context,
                        if (ok == targets.size) "Removed $ok shortcut${if (ok == 1) "" else "s"}."
                        else "Removed $ok of ${targets.size} — the rest could not be deleted.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("Remove", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveSelected = false }) { Text("Cancel") }
            },
        )
    }

    // Clone-to-container dialog
    cloneTarget?.let { s ->
        val containers = vm.containers()
        OutlinedAlertDialog(
            onDismissRequest = { cloneTarget = null },
            title = { Text("Select container") },
            text = {
                // Scroll so a long container list isn't clipped in landscape / on short screens.
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
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

    // Save Restore: a backup .zip has been picked — choose the TARGET container to restore it into,
    // then hand off to GameSaveBackup.restore (which unzips into that container and remaps the user).
    restoreZipUri?.let { uri ->
        ContainerPickerDialog(
            gameName = restoreForName,
            containers = vm.containers(),
            onDismiss = { restoreZipUri = null },
            onSelected = { chosen ->
                restoreZipUri = null
                Toast.makeText(context, "Restoring saves into \"${chosen.name}\"…", Toast.LENGTH_SHORT).show()
                GameSaveBackup.restore(context, uri, chosen) { r ->
                    Toast.makeText(
                        context,
                        if (r.ok) "Restored ${r.filesWritten} files to \"${chosen.name}\""
                        else "Restore failed: ${r.error ?: "unknown error"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }

    // Save Backup: choose the archive layout before backing up (mirrors the Containers backup menu's
    // GameHub / Winlator-native choice), then run the scoped backup into the per-game folder.
    backupFormatShortcut?.let { s ->
        OutlinedAlertDialog(
            onDismissRequest = { backupFormatShortcut = null },
            title = { Text(stringResource(R.string.save_backup_format_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.save_backup_format_prompt),
                        color = OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                backupFormatShortcut = null
                                runCustomBackup(s, GameSaveBackup.BackupLayout.WINLATOR)
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.save_backup_format_winlator), color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.save_backup_format_winlator_sub), color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                backupFormatShortcut = null
                                runCustomBackup(s, GameSaveBackup.BackupLayout.GAMEHUB)
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.save_backup_format_gamehub), color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.save_backup_format_gamehub_sub), color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { backupFormatShortcut = null }) { Text("Cancel") } },
        )
    }

    // "View logs" — straight to this game's logs, no trip through App Settings.
    //
    // Three outcomes, and it matters that they read differently: per-game folders off (nothing is
    // filed per game, so we cannot show "this game's" logs at all), on but nothing captured yet, or
    // the viewer. Silently opening an empty viewer for the first two would look like a bug.
    logsShortcut?.let { s ->
        val entry = remember(s.name) { LogInventory.forGame(context, s.name) }
        val perGameOff = remember { !LogLocation.isPerGameEnabled(context) }
        if (entry != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { logsShortcut = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LogViewerScreen(entry = entry, onClose = { logsShortcut = null })
                }
            }
        } else {
            OutlinedAlertDialog(
                onDismissRequest = { logsShortcut = null },
                title = { Text("No logs for ${s.name}") },
                text = {
                    Text(
                        if (perGameOff)
                            "Per-game log folders are turned off, so everything is written to one " +
                            "shared folder and logs can't be traced back to a single game. Turn them " +
                            "on in Settings › Logs, then play ${s.name} once."
                        else
                            "Nothing has been captured for ${s.name} yet. Logs are written while a " +
                            "game runs — play it once, then check back here."
                    )
                },
                confirmButton = { TextButton(onClick = { logsShortcut = null }) { Text("OK") } },
            )
        }
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
                                            // Manually correcting the game via search also sticks for
                                            // next time (issue #167), same as tapping a tie alternative.
                                            vm.rememberCommunityGame(s, cg)
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
                            // Genuine tie (e.g. "Dragon Age" → Inquisition vs Veilguard): let the user
                            // pick the right game rather than silently trusting the top candidate. The
                            // choice is remembered per shortcut, so this only asks once. (issue #167)
                            val tieOptions = result?.alternatives.orEmpty()
                            if (tieOptions.size > 1) {
                                Text(
                                    text = "Which game is this?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnSurface,
                                )
                                tieOptions.forEach { alt ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                vm.rememberCommunityGame(s, alt)
                                                vm.selectCommunityGame(alt) { communityResult = it }
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = alt.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = OnSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        CommunityStoreBadge(isSteam = alt.isSteam)
                                    }
                                }
                                Divider(color = DividerColor)
                            }
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
            OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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

    // Game Details editor (Edit Game): name + Steam link/search + genres/description/year/metacritic.
    gameDetailsShortcut?.let { s ->
        GameDetailsSheet(
            shortcut = s,
            onDismiss = { gameDetailsShortcut = null },
            onSaved = { vm.refresh() },
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
internal fun CommunityStoreBadge(isSteam: Boolean) {
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
internal fun MyAccountDialog(
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

    OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
        OutlinedAlertDialog(
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
internal fun CommunityCatalogBrowser(
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

    // Controller D-pad model — index-based with a SINGLE focus target on the panel (the same philosophy
    // Big Picture uses). gameFocus walks the visible game list; configFocus walks the drilled game's
    // published picks; drilledPicks is that in-render-order list the device panel hands up so A can apply
    // picks[configFocus]. Everything here is inert for touch/phone users: the handler consumes ONLY the
    // D-pad/A/B keys and returns false otherwise, so taps + the soft keyboard are unaffected.
    var gameFocus by remember { mutableStateOf(0) }
    var configFocus by remember { mutableStateOf(0) }
    var drilledPicks by remember { mutableStateOf<List<CommunityPick>>(emptyList()) }
    // Two-pane zone model (only meaningful at the top level, i.e. NOT drilled into a game):
    //   RIGHT (leftZone=false) = the game list — Up/Down walk it, A drills in, LEFT crosses to controls.
    //   LEFT  (leftZone=true)  = the controls column, walked top-to-bottom by [leftRow]:
    //        0 = Search field · 1 = store-filter group · 2 = Matches-my-device · 3 = sort group.
    //   For the two chip GROUPS, Left/Right cycles the focused chip; a further RIGHT past the last chip
    //   crosses to the list. On Search / Matches (single controls) RIGHT crosses to the list directly.
    //   [storeChipFocus]/[sortChipFocus] seed from the current selection so focus starts on it.
    var leftZone by remember { mutableStateOf(false) }
    var leftRow by remember { mutableStateOf(0) }
    var storeChipFocus by remember { mutableStateOf(storeFilter.ordinal) }
    var sortChipFocus by remember { mutableStateOf(sort.ordinal) }
    val browserFocus = remember { FocusRequester() }
    // A on the search control hands focus to the field + pops the soft keyboard; B pulls it back down
    // first (so the first B closes the keyboard, a second B closes/backs out of the browser).
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Reliable "keyboard is up" signal (WindowInsets.isImeVisible is unreliable in this dialog's window):
    // true while the search field holds focus. B closes the keyboard first, then closes the browser.
    var searchFieldFocused by remember { mutableStateOf(false) }
    val gameListState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.getCommunityCatalog { catalog = it; loading = false } }
    // Seed focus so the panel receives D-pad from the first frame (it's its own Dialog window, so the
    // Big Picture root handler never sees these keys).
    LaunchedEffect(Unit) { runCatching { browserFocus.requestFocus() } }
    // Reset the drilled cursor AND drop the previous game's picks the instant we drill in/out, so A can't
    // apply a stale pick in the frame before the new device panel republishes its list.
    LaunchedEffect(selectedIdentity) { configFocus = 0; drilledPicks = emptyList() }

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

    // Keep the game-list cursor in range as filters change, and keep the highlighted game on-screen.
    LaunchedEffect(visible.size) { if (gameFocus > visible.lastIndex) gameFocus = visible.lastIndex.coerceAtLeast(0) }
    LaunchedEffect(gameFocus, selectedGame, visible.size) {
        if (selectedGame == null && visible.isNotEmpty())
            runCatching { gameListState.animateScrollToItem(gameFocus.coerceIn(0, visible.lastIndex)) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .focusRequester(browserFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val drilled = selectedGame != null
                    when (event.key) {
                        Key.DirectionUp -> {
                            when {
                                drilled -> { if (configFocus > 0) configFocus-- }
                                leftZone -> { if (leftRow > 0) leftRow-- }
                                else -> { if (gameFocus > 0) gameFocus-- }
                            }
                            true
                        }
                        Key.DirectionDown -> {
                            when {
                                drilled -> { if (configFocus < drilledPicks.lastIndex) configFocus++ }
                                leftZone -> { if (leftRow < 3) leftRow++ }
                                else -> { if (gameFocus < visible.lastIndex) gameFocus++ }
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            when {
                                drilled -> {} // config list is single-column; nothing to the left
                                !leftZone -> leftZone = true // cross from the game list to the controls
                                leftRow == 1 -> { if (storeChipFocus > 0) storeChipFocus-- }
                                leftRow == 3 -> { if (sortChipFocus > 0) sortChipFocus-- }
                                else -> {} // Search / Matches: already at the left edge
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            when {
                                drilled -> {}
                                !leftZone -> {} // already on the game list (rightmost)
                                // Chip groups: step right through the chips, then cross to the list.
                                leftRow == 1 -> { if (storeChipFocus < 2) storeChipFocus++ else leftZone = false }
                                leftRow == 3 -> { if (sortChipFocus < 2) sortChipFocus++ else leftZone = false }
                                else -> leftZone = false // Search / Matches: cross straight to the list
                            }
                            true
                        }
                        Key.ButtonA, Key.Enter, Key.DirectionCenter -> {
                            when {
                                drilled -> drilledPicks.getOrNull(configFocus)?.let(onPick)
                                leftZone -> when (leftRow) {
                                    0 -> { searchFocus.requestFocus(); keyboard?.show() } // Search: pop the keyboard
                                    1 -> storeFilter = when (storeChipFocus) {
                                        0 -> CatalogStoreFilter.ALL
                                        1 -> CatalogStoreFilter.STEAM
                                        else -> CatalogStoreFilter.TITLE
                                    }
                                    2 -> matchesMyDevice = !matchesMyDevice
                                    3 -> sort = when (sortChipFocus) {
                                        0 -> CatalogSort.CONFIGS
                                        1 -> CatalogSort.NAME
                                        else -> CatalogSort.DEVICES
                                    }
                                }
                                else -> visible.getOrNull(gameFocus)?.let { selectedIdentity = it.identity }
                            }
                            true
                        }
                        // B / Back: first close the keyboard if it's up; else up a level when drilled,
                        // otherwise close the browser.
                        Key.ButtonB, Key.Back -> {
                            when {
                                searchFieldFocused -> { keyboard?.hide(); browserFocus.requestFocus() }
                                drilled -> selectedIdentity = null
                                else -> onDismiss()
                            }
                            true
                        }
                        else -> false
                    }
                },
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
                    // Left-pane control 0 — Search (D-pad highlight-reachable; text entry stays touch/IME).
                    DpadHighlight(focused = leftZone && leftRow == 0) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search all games") },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                                .focusRequester(searchFocus)
                                .onFocusChanged { searchFieldFocused = it.isFocused }
                                // Guaranteed B handling while the field holds focus: close the keyboard
                                // and hand focus back to the browser for D-pad nav.
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && (e.key == Key.ButtonB || e.key == Key.Back)) {
                                        keyboard?.hide(); browserFocus.requestFocus(); true
                                    } else false
                                },
                        )
                    }
                    // Left-pane control 1 — store filter group (Left/Right cycles the focused chip).
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        DpadHighlight(focused = leftZone && leftRow == 1 && storeChipFocus == 0) {
                            FilterChip(selected = storeFilter == CatalogStoreFilter.ALL, onClick = { storeFilter = CatalogStoreFilter.ALL; leftZone = true; leftRow = 1; storeChipFocus = 0 }, label = { Text("All") })
                        }
                        DpadHighlight(focused = leftZone && leftRow == 1 && storeChipFocus == 1) {
                            FilterChip(selected = storeFilter == CatalogStoreFilter.STEAM, onClick = { storeFilter = CatalogStoreFilter.STEAM; leftZone = true; leftRow = 1; storeChipFocus = 1 }, label = { Text("Steam") })
                        }
                        DpadHighlight(focused = leftZone && leftRow == 1 && storeChipFocus == 2) {
                            FilterChip(selected = storeFilter == CatalogStoreFilter.TITLE, onClick = { storeFilter = CatalogStoreFilter.TITLE; leftZone = true; leftRow = 1; storeChipFocus = 2 }, label = { Text("Title") })
                        }
                    }
                    // Left-pane control 2 — Matches my device.
                    DpadHighlight(focused = leftZone && leftRow == 2) {
                        FilterChip(
                            selected = matchesMyDevice,
                            onClick = { matchesMyDevice = !matchesMyDevice; leftZone = true; leftRow = 2 },
                            label = { Text("Matches my device") },
                            enabled = userSoc != null || userGpu != null,
                        )
                    }
                    // Left-pane control 3 — sort group (Left/Right cycles the focused chip).
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sort:", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                        DpadHighlight(focused = leftZone && leftRow == 3 && sortChipFocus == 0) {
                            FilterChip(selected = sort == CatalogSort.CONFIGS, onClick = { sort = CatalogSort.CONFIGS; leftZone = true; leftRow = 3; sortChipFocus = 0 }, label = { Text("Configs") })
                        }
                        DpadHighlight(focused = leftZone && leftRow == 3 && sortChipFocus == 1) {
                            FilterChip(selected = sort == CatalogSort.NAME, onClick = { sort = CatalogSort.NAME; leftZone = true; leftRow = 3; sortChipFocus = 1 }, label = { Text("Name") })
                        }
                        DpadHighlight(focused = leftZone && leftRow == 3 && sortChipFocus == 2) {
                            FilterChip(selected = sort == CatalogSort.DEVICES, onClick = { sort = CatalogSort.DEVICES; leftZone = true; leftRow = 3; sortChipFocus = 2 }, label = { Text("Devices") })
                        }
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
                        state = gameListState,
                        modifier = modifier,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(visible, key = { _, g -> g.identity }) { index, g ->
                            // The D-pad highlight border wraps the row; touch users see nothing extra. Only
                            // shown while the RIGHT (list) zone is active, so focus reads as being in one
                            // place at a time. A tap also snaps the cursor back to the list.
                            DpadHighlight(focused = !leftZone && index == gameFocus) {
                                CommunityGameRow(game = g, onClick = { leftZone = false; gameFocus = index; selectedIdentity = g.identity })
                            }
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
                            focusedIndex = configFocus,
                            onPicks = { drilledPicks = it },
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

// Draws the app's controller-focus border (primary, rounded to the card shape) around a game/config
// card when it's the D-pad-highlighted item, otherwise a plain passthrough. Kept as a wrapper (rather
// than a `focused` param on each card) so the touch/phone path renders byte-identically when nothing
// is focused. Same visual idiom as Big Picture's RailButton/CoverCard focus border.
@Composable
internal fun DpadHighlight(focused: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.then(
            if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
            else Modifier
        ),
    ) { content() }
}

// Shared thin outlined card for the community browser's game + config rows. Matches the app's
// FileManager/Containers card idiom (surfaceContainer fill, 1dp outline, rounded 10dp) but with a
// tighter vertical rhythm so the rows read as a compact list. The whole card is the tap target.
@Composable
internal fun CommunityCard(
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
internal sealed class CommunityPick {
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
internal fun CommunityConfigEntryCard(entry: WorkerConfigEntry, isMatch: Boolean, onClick: () -> Unit) {
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
internal fun deviceHeaderLabel(model: String?, hardware: String?): String {
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
    // Controller D-pad support (defaulted so the phone caller stays untouched): [focusedIndex] draws a
    // highlight border on the config card at that position, and [onPicks] publishes the CURRENT visible
    // config list — in render order — up to the browser so A can apply the highlighted one by index.
    focusedIndex: Int = -1,
    onPicks: (List<CommunityPick>) -> Unit = {},
) {
    val cfg = rememberGameConfigs(vm, game)
    val fallback = remember(game, userSoc, userGpu) { GameMatcher.rankDevices(game.devices, userSoc, userGpu) }
    var matchesMyDevice by rememberSaveable(game.identity) { mutableStateOf(false) }
    val hwEnabled = userSoc != null || userGpu != null

    val shownEntries = remember(cfg.entries, matchesMyDevice, userSoc, userGpu) {
        if (!matchesMyDevice) cfg.entries
        else cfg.entries.filter { GameMatcher.hardwareMatchesUser(userSoc, userGpu, listOf(it.second.device, it.second.soc)) }
    }
    // The offline per-device fallback list, filtered the same way the render below does — hoisted so the
    // published picks match exactly what ConfigList draws.
    val shownDevs = remember(fallback, matchesMyDevice, userSoc, userGpu) {
        if (!matchesMyDevice) fallback else fallback.filter { GameMatcher.deviceMatchesUser(it, userSoc, userGpu) }
    }
    // The flat, in-render-order list of picks the config list currently shows: worker entries when we have
    // them, else the device fallback. Published to the browser so its D-pad handler can apply picks[index].
    val orderedPicks = remember(shownEntries, shownDevs, cfg.entries, game) {
        if (cfg.entries.isNotEmpty()) shownEntries.map { (folder, e) ->
            CommunityPick.File(
                game,
                CommunityConfigRef(game, folder, e.filename, e.sha.ifBlank { null }, ns = if (e.appSource == "bannerlator") "bannerlator" else ""),
                e,
            )
        } else shownDevs.map { CommunityPick.Device(game, it) }
    }
    LaunchedEffect(orderedPicks) { onPicks(orderedPicks) }

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
                        shownEntries.forEachIndexed { idx, (folder, e) ->
                            val isMatch = hwEnabled &&
                                GameMatcher.hardwareMatchesUser(userSoc, userGpu, listOf(e.device, e.soc))
                            DpadHighlight(focused = idx == focusedIndex) {
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
                    shownDevs.forEachIndexed { idx, d ->
                        val isMatch = hw != null && (
                            (d.soc.isNotBlank() && (hw.contains(d.soc.lowercase()) || d.soc.lowercase().contains(hw))) ||
                            (d.gpu.isNotBlank() && (hw.contains(d.gpu.lowercase()) || d.gpu.lowercase().contains(hw)))
                        )
                        DpadHighlight(focused = idx == focusedIndex) {
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
    config.scalars["inputType"]?.let { out.add("XInput" to if (((it.toIntOrNull() ?: 0) and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) != 0) "on" else "off") }
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
// ─────────────────────────────────────────────────────────────────────────────
// Steam Save Manager entry point (Games-tab ⋮ menu). A shortcut is "Steam-origin" when it was
// tagged at creation (storeSource=steam) or, for pre-tagging shortcuts, when its exec path lives
// under the steam_games install root. The linked appId reuses the existing `steamAppId` extra.
private fun isSteamOriginShortcut(shortcut: Shortcut): Boolean {
    if (shortcut.getExtra("storeSource") == "steam") return true
    return shortcut.path.contains("steam_games", ignoreCase = true)
}

private fun steamAppIdOf(shortcut: Shortcut): Int =
    shortcut.getExtra("steamAppId", "").toIntOrNull() ?: 0

// A shortcut is "custom" (exe/folder import) when it is NOT a genuine Steam-library game. Steam games
// get the "Cloud Saves" item; custom games get the local-only "Back up / Restore saves" items.
private fun isCustomShortcut(shortcut: Shortcut): Boolean = !isSteamOriginShortcut(shortcut)

private fun launchSaveManager(context: Context, focusAppId: Int) {
    context.startActivity(
        Intent(context, SteamSaveManagerActivity::class.java)
            .putExtra(SteamSaveManagerActivity.EXTRA_FOCUS_APP_ID, focusAppId),
    )
}

// 3:4 cover on the left, name + container · resolution subtitle in the middle. Components are
// split by how often you check them: renderer, DXVK and frame-gen are bright chips; driver,
// VKD3D and x86 backend sit on a calm muted line with a colour dot each. "Calm but complete."
@Composable
private fun ShortcutItemLayoutL(
    shortcut: Shortcut,
    selectionMode: Boolean,
    selected: Boolean,
    onRun: () -> Unit,
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    onClone: () -> Unit,
    onAddToHome: () -> Unit,
    onExport: () -> Unit,
    onProperties: () -> Unit,
    onScrapeCover: () -> Unit,
    onCommunityConfigs: () -> Unit,
    onGameDetails: () -> Unit,
    onViewLogs: () -> Unit,
    onCloudSaves: (() -> Unit)? = null,
    onBackupSaves: (() -> Unit)? = null,
    onRestoreSaves: (() -> Unit)? = null,
) {
    val res = LocalContext.current.resources

    // Resolved component metadata (shortcut override → container default). Shared with the launch
    // overlay via buildLaunchSpec() so the card and the launch screen can never drift.
    val spec = buildLaunchSpec(shortcut, res)
    val rendererLabel = spec.rendererLabel
    val dxvkVersion = spec.dxvkVersion
    val vkd3dVersion = spec.vkd3dVersion
    val driverLabel = spec.driverLabel
    val frameGenLabel = spec.frameGenLabel
    val backendLabel = spec.backendLabel
    val subtitle = spec.meta

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
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) DangerRed else MaterialTheme.colorScheme.outline,
        ),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        // Checkbox replaces nothing — it is inserted ahead of the cover, so the row keeps its
        // shape and the cover does not jump when selection mode turns on.
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp).size(22.dp),
            )
        }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (remember(shortcut) { WinePath.isOnRemovableStorage(shortcut.container, shortcut.path) }) {
                    Spacer(Modifier.width(6.dp))
                    SdCardBadge()
                }
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
            onGameDetails = onGameDetails,
            onViewLogs = onViewLogs,
            onCloudSaves = onCloudSaves,
            onBackupSaves = onBackupSaves,
            onRestoreSaves = onRestoreSaves,
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
    onGameDetails: () -> Unit,
    onViewLogs: () -> Unit,
    onCloudSaves: (() -> Unit)? = null,
    onBackupSaves: (() -> Unit)? = null,
    onRestoreSaves: (() -> Unit)? = null,
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
                text = { Text("Game Details") },
                leadingIcon = { Icon(Icons.Filled.Edit, null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { menuExpanded = false; onGameDetails() },
            )
            // Steam-origin only — opens the Save Manager focused on this game.
            if (onCloudSaves != null) {
                MenuItemDivider()
                DropdownMenuItem(
                    text = { Text("Cloud Saves") },
                    leadingIcon = { Icon(Icons.Filled.CloudSync, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { menuExpanded = false; onCloudSaves() },
                )
            }
            // Custom-import games only — local save backup/restore (the non-Steam equivalent).
            if (onBackupSaves != null) {
                MenuItemDivider()
                DropdownMenuItem(
                    text = { Text("Back up saves") },
                    leadingIcon = { Icon(Icons.Filled.Archive, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { menuExpanded = false; onBackupSaves() },
                )
            }
            if (onRestoreSaves != null) {
                MenuItemDivider()
                DropdownMenuItem(
                    text = { Text("Restore saves") },
                    leadingIcon = { Icon(Icons.Filled.Unarchive, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { menuExpanded = false; onRestoreSaves() },
                )
            }
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
                text = { Text("View logs") },
                leadingIcon = { Icon(Icons.Filled.Description, null) },
                onClick = { menuExpanded = false; onViewLogs() },
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
    selectionMode: Boolean,
    selected: Boolean,
    onRun: () -> Unit,
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    onClone: () -> Unit,
    onAddToHome: () -> Unit,
    onExport: () -> Unit,
    onProperties: () -> Unit,
    onScrapeCover: () -> Unit,
    onCommunityConfigs: () -> Unit,
    onGameDetails: () -> Unit,
    onViewLogs: () -> Unit,
    onCloudSaves: (() -> Unit)? = null,
    onBackupSaves: (() -> Unit)? = null,
    onRestoreSaves: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceColor)
            .border(
                width = if (selected) 3.dp else 2.dp,
                brush = if (selected)
                    Brush.linearGradient(listOf(DangerRed, DangerRed))
                else Brush.linearGradient(
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

        // Removable-storage marker, over the art's top corner so it never fights the title scrim.
        if (remember(shortcut) { WinePath.isOnRemovableStorage(shortcut.container, shortcut.path) }) {
            SdCardBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
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

        // Selection badge. Only while selecting — a permanent empty circle on every tile would
        // read as part of the artwork.
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) DangerRed
                        else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        // Long-press context menu
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.outlinedMenuCard(),
        ) {
            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Filled.Settings, null) }, onClick = { menuExpanded = false; onSettings() })
            MenuItemDivider()
            DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menuExpanded = false; onRemove() })
            MenuItemDivider()
            DropdownMenuItem(text = { Text("Clone to container") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menuExpanded = false; onClone() })
            MenuItemDivider()
            DropdownMenuItem(text = { Text("Add to home screen") }, leadingIcon = { Icon(Icons.Filled.AddToHomeScreen, null) }, onClick = { menuExpanded = false; onAddToHome() })
            MenuItemDivider()
            DropdownMenuItem(text = { Text("Export") }, leadingIcon = { Icon(Icons.Filled.Upload, null) }, onClick = { menuExpanded = false; onExport() })
            MenuItemDivider()
            DropdownMenuItem(text = { Text("Game Details") }, leadingIcon = { Icon(Icons.Filled.Edit, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { menuExpanded = false; onGameDetails() })
            // Steam-origin only — opens the Save Manager focused on this game.
            if (onCloudSaves != null) {
                MenuItemDivider()
                DropdownMenuItem(text = { Text("Cloud Saves") }, leadingIcon = { Icon(Icons.Filled.CloudSync, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { menuExpanded = false; onCloudSaves() })
            }
            // Custom-import games only — local save backup/restore (the non-Steam equivalent).
            if (onBackupSaves != null) {
                MenuItemDivider()
                DropdownMenuItem(text = { Text("Back up saves") }, leadingIcon = { Icon(Icons.Filled.Archive, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { menuExpanded = false; onBackupSaves() })
            }
            if (onRestoreSaves != null) {
                MenuItemDivider()
                DropdownMenuItem(text = { Text("Restore saves") }, leadingIcon = { Icon(Icons.Filled.Unarchive, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { menuExpanded = false; onRestoreSaves() })
            }
            MenuItemDivider()
            DropdownMenuItem(text = { Text("Scrape cover") }, leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { menuExpanded = false; onScrapeCover() })
            MenuItemDivider()
            DropdownMenuItem(text = { Text("Community configs") }, leadingIcon = { Icon(Icons.Filled.Public, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { menuExpanded = false; onCommunityConfigs() })
            MenuItemDivider()
            DropdownMenuItem(text = { Text("View logs") }, leadingIcon = { Icon(Icons.Filled.Description, null) }, onClick = { menuExpanded = false; onViewLogs() })
            MenuItemDivider()
            DropdownMenuItem(text = { Text("Properties") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { menuExpanded = false; onProperties() })
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
/**
 * "Edit Game" details editor — a full-screen dialog (matching [ShortcutSettingsDialogScreen]'s idiom)
 * that lets the user set a shortcut's name + link it to a Steam app and accumulate editorial details
 * (genres, description, release year, metacritic) shown on the launch overlay. Ported from the
 * BannersComponentInjector `GameEditSheet`, adapted to this app's shortcut/extras model.
 *
 * Save is best-effort and entirely off the main thread: renames the shortcut if the name changed
 * (via [ExeShortcutImporter.renameShortcutFiles], which moves cover/icon too), writes the detail
 * extras ([GameDetails.writeTo]), and re-applies the Steam cover for the linked appId ([applySteamCover]).
 * Nothing here throws to the caller. Seeded via remember(shortcut) so re-opening for a different game
 * reseeds cleanly (the compose-state "key on the config" rule).
 */
@Composable
private fun GameDetailsSheet(
    shortcut: Shortcut,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = shortcut.container

    // Seed all editable state from the shortcut's current on-disk details (keyed on shortcut).
    val initial = remember(shortcut) { GameDetails.from(shortcut) }
    var nameField by remember(shortcut) { mutableStateOf(shortcut.name) }
    var genresField by remember(shortcut) { mutableStateOf(initial.genres.joinToString(", ")) }
    var descField by remember(shortcut) { mutableStateOf(initial.description ?: "") }
    var yearField by remember(shortcut) { mutableStateOf(initial.releaseYear ?: "") }
    var metaField by remember(shortcut) { mutableStateOf(initial.metacritic?.toString() ?: "") }
    var linkedAppId by remember(shortcut) { mutableStateOf(initial.steamAppId) }

    var searchResults by remember(shortcut) { mutableStateOf<List<SteamStoreSearch.SteamSuggestion>>(emptyList()) }
    var searching by remember(shortcut) { mutableStateOf(false) }
    var searchError by remember(shortcut) { mutableStateOf<String?>(null) }
    var filling by remember(shortcut) { mutableStateOf(false) }
    var saving by remember(shortcut) { mutableStateOf(false) }

    fun doSearch() {
        val query = nameField.trim()
        if (query.isEmpty()) return
        searching = true
        searchError = null
        searchResults = emptyList()
        scope.launch(Dispatchers.IO) {
            val results = SteamStoreSearch.searchByName(query)
            withContext(Dispatchers.Main) {
                searchResults = results
                if (results.isEmpty()) searchError = "No results found for \"$query\""
                searching = false
            }
        }
    }

    // Tapping a result auto-fills every field from Steam and links the appId. If the details fetch
    // fails (network), we still link the appId so the cover applies and the user can fill fields by hand.
    fun fillFromSteam(appId: Int) {
        filling = true
        searchResults = emptyList()
        scope.launch(Dispatchers.IO) {
            val info = SteamStoreSearch.fetchDetails(appId)
            withContext(Dispatchers.Main) {
                if (info != null) {
                    nameField = info.name
                    genresField = info.genres.joinToString(", ")
                    descField = info.shortDescription ?: ""
                    yearField = info.releaseYear ?: ""
                    metaField = info.metacritic?.toString() ?: ""
                }
                linkedAppId = appId
                filling = false
            }
        }
    }

    fun save() {
        if (saving) return
        saving = true
        val oldBase = shortcut.name
        val newBase = nameField.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        val genres = genresField.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val metacritic = metaField.trim().toIntOrNull()?.takeIf { it in 1..100 }
        val year = yearField.trim().takeIf { it.isNotBlank() }
        val desc = descField.trim().takeIf { it.isNotBlank() }
        val appId = linkedAppId
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Rename if the name changed (moves .desktop/.lnk + icon + cover, rewrites extras).
                var base = oldBase
                if (newBase.isNotBlank() && newBase != oldBase &&
                    ExeShortcutImporter.renameShortcutFiles(container, oldBase, newBase)
                ) {
                    base = newBase
                }
                val file = File(container.getDesktopDir(), "$base.desktop")
                if (file.isFile) {
                    // 2. Persist the editorial details (steamAppId included / cleared on unlink).
                    GameDetails(
                        steamAppId = appId,
                        genres = genres,
                        description = desc,
                        releaseYear = year,
                        metacritic = metacritic,
                    ).writeTo(Shortcut(container, file))
                    // 3. Re-apply the Steam cover for the linked appId (re-reads disk, so the detail
                    //    extras written in step 2 are preserved). No-op / cover untouched when unlinked.
                    if (appId != null && appId > 0) applySteamCover(container, base, appId)
                }
            } catch (_: Exception) {
                // Best-effort — never crash the shortcuts screen on a save.
            }
            withContext(Dispatchers.Main) {
                saving = false
                onSaved()
                onDismiss()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header: Close · title · Save.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = OnSurface)
                    }
                    Text(
                        text = "Edit Game",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { save() }, enabled = !saving) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Save", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Divider(color = DividerColor)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Linked Steam app (cover + appId + Unlink).
                    linkedAppId?.let { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SteamResultThumbnail(id)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Linked to Steam App ID:", fontSize = 11.sp, color = OnSurfaceVariant)
                                Text(
                                    "$id",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                TextButton(
                                    onClick = { linkedAppId = null },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(28.dp),
                                ) { Text("Unlink", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                        Divider(color = DividerColor)
                    }

                    // Game name + Search Steam.
                    Text("Game Name", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnSurfaceVariant)
                    OutlinedTextField(
                        value = nameField,
                        onValueChange = { nameField = it; searchResults = emptyList(); searchError = null },
                        placeholder = { Text("Enter game name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (nameField.isNotBlank()) {
                                IconButton(onClick = { nameField = ""; searchResults = emptyList() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { doSearch() },
                            enabled = nameField.isNotBlank() && !searching,
                        ) {
                            if (searching) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Search Steam", fontSize = 13.sp)
                        }
                        if (filling) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Loading…", fontSize = 12.sp, color = OnSurfaceVariant)
                        }
                    }
                    searchError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    if (searchResults.isNotEmpty()) {
                        Text("Tap a result to auto-fill all fields:", fontSize = 11.sp, color = OnSurfaceVariant)
                        searchResults.forEach { hit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { fillFromSteam(hit.appId) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SteamResultThumbnail(hit.appId)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(hit.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("App ID: ${hit.appId}", fontSize = 11.sp, color = OnSurfaceVariant)
                                }
                            }
                        }
                    }

                    Divider(color = DividerColor)

                    // Genres.
                    Text("Genres", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnSurfaceVariant)
                    OutlinedTextField(
                        value = genresField,
                        onValueChange = { genresField = it },
                        placeholder = { Text("e.g. Action, RPG, Strategy") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Comma-separated", fontSize = 10.sp) },
                    )

                    // Description.
                    Text("Description", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnSurfaceVariant)
                    OutlinedTextField(
                        value = descField,
                        onValueChange = { descField = it },
                        placeholder = { Text("Short description shown on the launch screen") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Release year + Metacritic.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Release Year", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = yearField,
                                onValueChange = { if (it.length <= 4) yearField = it.filter { c -> c.isDigit() } },
                                placeholder = { Text("e.g. 2023") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Metacritic", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = metaField,
                                onValueChange = { if (it.length <= 3) metaField = it.filter { c -> c.isDigit() } },
                                placeholder = { Text("1–100") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                supportingText = { Text("Leave blank to hide", fontSize = 10.sp) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// internal (not private) so the Big Picture screen can reuse the exact same shortcut editor dialog.
// ─────────────────────────────────────────────────────────────────────────────
// Controller / D-pad navigation for the Shortcut editor. Same MANUAL, single-root model as Big
// Picture's GameCommunitySheet / CommunityCatalogBrowser: ONE focusable root owns the keys; a per-frame
// ORDERED id list (`dpadIds`, rebuilt in the dialog body from current visibility so conditional rows
// survive) drives Up/Down; each control publishes its A / Left / Right actions into [actions] via a
// SideEffect so they always reflect current state (only the FOCUSED control's action is ever invoked,
// and every control re-publishes whenever the focus id changes because it reads it). Inert for touch:
// [focusedId] is null until the first D-pad key, and every control keeps its own onClick/onValueChange.
private class ControlActions(
    val activate: () -> Unit = {},
    val onLeft: (() -> Unit)? = null,
    val onRight: (() -> Unit)? = null,
)

private class SettingsDpad {
    var focusedId by mutableStateOf<String?>(null)
    var openId by mutableStateOf<String?>(null)      // the dropdown whose menu is open (sub-nav target)
    var menuIndex by mutableStateOf(0)
    var imeFieldId by mutableStateOf<String?>(null)  // a text field currently holding IME focus
    var menuOptions: List<String> = emptyList()
    var menuOnSelect: (String) -> Unit = {}
    val actions = HashMap<String, ControlActions>()
    val rootFocus = FocusRequester()

    fun isFocused(id: String) = focusedId == id
    fun openMenu(id: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
        openId = id; menuOptions = options; menuOnSelect = onSelect
        menuIndex = options.indexOf(selected).coerceAtLeast(0)
    }
    fun closeMenu() { openId = null }
}

// Root key handler for the whole editor window (placed on the dialog Surface). Consumes ONLY the
// DPAD / A / B keys and returns false otherwise, so phone touch, scrolling and the soft keyboard are
// unaffected. [ids] is a lambda so the handler always reads the freshest ordered id list.
private fun Modifier.settingsDpad(dp: SettingsDpad, ids: () -> List<String>, onDismiss: () -> Unit): Modifier =
    this
        .focusRequester(dp.rootFocus)
        .focusable()
        .onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // A focused text field owns the keys (its own on-field handler does B / close-keyboard).
            if (dp.imeFieldId != null) return@onPreviewKeyEvent false
            // Open dropdown → its options get Up/Down + A(select) + B(close JUST the menu).
            if (dp.openId != null) {
                return@onPreviewKeyEvent when (ev.key) {
                    Key.DirectionUp -> { if (dp.menuIndex > 0) dp.menuIndex--; true }
                    Key.DirectionDown -> { if (dp.menuIndex < dp.menuOptions.lastIndex) dp.menuIndex++; true }
                    Key.ButtonA, Key.Enter, Key.DirectionCenter -> {
                        dp.menuOptions.getOrNull(dp.menuIndex)?.let(dp.menuOnSelect); dp.closeMenu(); true
                    }
                    Key.ButtonB, Key.Back -> { dp.closeMenu(); true }
                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            }
            val order = ids()
            val idx = order.indexOf(dp.focusedId)
            when (ev.key) {
                Key.DirectionUp -> { dp.focusedId = if (idx <= 0) order.firstOrNull() else order[idx - 1]; true }
                Key.DirectionDown -> { dp.focusedId = when { idx < 0 -> order.firstOrNull(); idx < order.lastIndex -> order[idx + 1]; else -> order.getOrNull(idx) }; true }
                Key.DirectionLeft -> { dp.focusedId?.takeIf { it in order }?.let { dp.actions[it]?.onLeft?.invoke() }; true }
                Key.DirectionRight -> { dp.focusedId?.takeIf { it in order }?.let { dp.actions[it]?.onRight?.invoke() }; true }
                Key.ButtonA, Key.Enter, Key.DirectionCenter -> { dp.focusedId?.takeIf { it in order }?.let { dp.actions[it]?.activate?.invoke() }; true }
                Key.ButtonB, Key.Back -> { onDismiss(); true }
                else -> false
            }
        }

// Scrolls the focused control into view as the D-pad cursor moves down/up past the fold. The editor's
// body is one verticalScroll Column, which acts as the bring-into-view responder, so requesting it on the
// focused control makes the Column follow the highlight. Keyed on this control's own focused state so it
// only fires on the frame it gains focus.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.dpadBringIntoView(dp: SettingsDpad, id: String): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val focused = dp.focusedId == id
    LaunchedEffect(focused) { if (focused) runCatching { requester.bringIntoView() } }
    return this.bringIntoViewRequester(requester)
}

// ── Per-control wrappers. Each publishes its action(s) via SideEffect and renders with the focus
// highlight. `onLeftId`/`onRightId` move focus to a laterally-adjacent sibling (custom W|H, gfx
// driver|wrapper button, Cancel|OK). ──
@Composable
private fun DpField(
    dp: SettingsDpad, id: String, value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, singleLine: Boolean = false, onLeftId: String? = null, onRightId: String? = null,
) {
    val fr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    SideEffect {
        dp.actions[id] = ControlActions(
            activate = { runCatching { fr.requestFocus() }; keyboard?.show() },
            onLeft = onLeftId?.let { target -> { dp.focusedId = target } },
            onRight = onRightId?.let { target -> { dp.focusedId = target } },
        )
    }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = singleLine,
        modifier = modifier
            .dpadBringIntoView(dp, id)
            .focusRequester(fr)
            .onFocusChanged { st -> if (st.isFocused) dp.imeFieldId = id else if (dp.imeFieldId == id) dp.imeFieldId = null }
            .then(if (dp.isFocused(id)) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall) else Modifier)
            // On-field B fallback: first B closes the keyboard and hands focus back to the root.
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Back || ev.key == Key.ButtonB)) {
                    keyboard?.hide(); runCatching { dp.rootFocus.requestFocus() }; dp.imeFieldId = null; true
                } else false
            },
    )
}

@Composable
private fun DpDrop(
    dp: SettingsDpad, id: String, label: String, options: List<String>, selected: String, onSelect: (String) -> Unit,
    enabled: Boolean = true, disabledOptions: Set<String> = emptySet(), modifier: Modifier = Modifier,
    onLeftId: String? = null, onRightId: String? = null,
) {
    SideEffect {
        dp.actions[id] = ControlActions(
            activate = { if (enabled) dp.openMenu(id, options, selected, onSelect) },
            onLeft = onLeftId?.let { target -> { dp.focusedId = target } },
            onRight = onRightId?.let { target -> { dp.focusedId = target } },
        )
    }
    LabeledDropdown(
        label = label, options = options, selectedOption = selected, onSelect = onSelect,
        enabled = enabled, disabledOptions = disabledOptions, modifier = modifier.dpadBringIntoView(dp, id),
        focused = dp.isFocused(id),
        expandedOverride = dp.openId == id,
        onExpandedChange = { want -> if (want) dp.openMenu(id, options, selected, onSelect) else dp.closeMenu() },
        highlightedIndex = if (dp.openId == id) dp.menuIndex else -1,
    )
}

@Composable
private fun DpSwitch(dp: SettingsDpad, id: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    SideEffect { dp.actions[id] = ControlActions(activate = { if (enabled) onCheckedChange(!checked) }) }
    DpadHighlight(focused = dp.isFocused(id), modifier = Modifier.dpadBringIntoView(dp, id)) { Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) }
}

@Composable
private fun DpCheck(dp: SettingsDpad, id: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SideEffect { dp.actions[id] = ControlActions(activate = { onCheckedChange(!checked) }) }
    DpadHighlight(focused = dp.isFocused(id), modifier = Modifier.dpadBringIntoView(dp, id)) { Checkbox(checked = checked, onCheckedChange = onCheckedChange) }
}

// Labels for the six root perf keys (extraData name -> display label), matching PerfRootApplier.KEY_*.
private val ROOT_PERF_LABELS = mapOf(
    "rootCpuGovernorPerf" to "CPU governor → performance",
    "rootCpuFreqLockMax" to "Lock CPU frequency to max",
    "rootAllCoresOnline" to "Keep all cores online",
    "rootGpuMaxClockLock" to "Lock GPU to max clock",
    "rootThermalDisable" to "Disable thermal throttling",
    "rootFanMax" to "Fan to maximum",
)

/** Per-game override value to persist, or null (clear the extra) when it equals the global default. */
private fun perfExtraOrNull(value: Boolean, global: Boolean): String? =
    if (value == global) null else if (value) "1" else "0"

/** A per-game perf toggle row with an override/inherit indicator and a per-toggle Reset. */
@Composable
private fun PerfEditRow(dp: SettingsDpad, id: String, label: String, checked: Boolean, global: Boolean, onChange: (Boolean) -> Unit) {
    val overridden = checked != global
    // Compact single-line row: switch + label, with a trailing state hint. Overridden → an accent
    // "Reset" tap; otherwise a faint "default" marker. (Full explanation lives in the section "?" help.)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
    ) {
        DpSwitch(dp, id, checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (overridden) Text(
            "● Reset", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onChange(global) }.padding(start = 8.dp)
        ) else Text(
            "default", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun DpSlider(
    dp: SettingsDpad, id: String, value: Float, onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>, step: Float, steps: Int = 0, modifier: Modifier = Modifier,
) {
    SideEffect {
        dp.actions[id] = ControlActions(
            onLeft = { onValueChange((value - step).coerceIn(valueRange.start, valueRange.endInclusive)) },
            onRight = { onValueChange((value + step).coerceIn(valueRange.start, valueRange.endInclusive)) },
        )
    }
    DpadHighlight(focused = dp.isFocused(id), modifier = Modifier.dpadBringIntoView(dp, id)) {
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps, modifier = modifier)
    }
}

@Composable
private fun DpButton(
    dp: SettingsDpad, id: String, onActivate: () -> Unit, modifier: Modifier = Modifier,
    onLeftId: String? = null, onRightId: String? = null, content: @Composable () -> Unit,
) {
    SideEffect {
        dp.actions[id] = ControlActions(
            activate = onActivate,
            onLeft = onLeftId?.let { target -> { dp.focusedId = target } },
            onRight = onRightId?.let { target -> { dp.focusedId = target } },
        )
    }
    DpadHighlight(focused = dp.isFocused(id), modifier = modifier.dpadBringIntoView(dp, id)) { content() }
}

@Composable
private fun DpTabs(dp: SettingsDpad, id: String, selected: Int, count: Int, onSelect: (Int) -> Unit, content: @Composable () -> Unit) {
    SideEffect {
        dp.actions[id] = ControlActions(
            onLeft = { if (selected > 0) onSelect(selected - 1) },
            onRight = { if (selected < count - 1) onSelect(selected + 1) },
        )
    }
    DpadHighlight(focused = dp.isFocused(id), modifier = Modifier.dpadBringIntoView(dp, id)) { content() }
}

@Composable
internal fun ShortcutSettingsDialogScreen(shortcut: Shortcut, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = context.resources
    // Controller / D-pad state for this editor (see the SettingsDpad model above).
    val dp = remember { SettingsDpad() }

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

    // Graphics driver — bundled entries + user-imported wrappers (issue #132 Step 2), built via the
    // SHARED WrapperManager.driverEntries helper so this list matches ContainerDetailViewModel's
    // exactly (dynamic-dropdown drift is the feature's top-ranked risk). Keyed on wrapperRefreshKey
    // so a wrapper imported/deleted via the manager appears without reopening the editor.
    var wrapperRefreshKey by remember { mutableStateOf(0) }
    val graphicsDriverEntries = remember(wrapperRefreshKey) {
        WrapperManager.driverEntries(context, res.getStringArray(R.array.graphics_driver_entries))
    }
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

    // Gyro (motion aim) per-game overrides — seeded from the shortcut extra, falling back to the
    // container's value. Only the game-facing half lives here (deadzone/smoothing stay container-wide,
    // they're hand-tremor/latency settings, not game settings). These are ALWAYS written on save —
    // there's no "inherit" sentinel because enabled=false is a legitimate override — so once this
    // dialog has been saved for a shortcut, changing the container's gyro defaults only affects
    // NEW shortcuts.
    var gyroEnabled by remember {
        mutableStateOf(shortcut.getExtra("gyroEnabled",
            if (shortcut.container.isGyroEnabled) "1" else "0") == "1")
    }
    var gyroTarget by remember {
        mutableStateOf(shortcut.getExtra("gyroTarget",
            shortcut.container.gyroTarget.toString()).toIntOrNull() ?: Container.GYRO_TARGET_DEFAULT)
    }
    var gyroActivator by remember {
        mutableStateOf(shortcut.getExtra("gyroActivator",
            shortcut.container.gyroActivator.toString()).toIntOrNull() ?: Container.GYRO_ACTIVATOR_DEFAULT)
    }
    var gyroActivationMode by remember {
        mutableStateOf(shortcut.getExtra("gyroActivationMode",
            shortcut.container.gyroActivationMode.toString()).toIntOrNull() ?: Container.GYRO_ACTIVATION_MODE_DEFAULT)
    }
    var gyroMode by remember {
        mutableStateOf(shortcut.getExtra("gyroMode",
            shortcut.container.gyroMode.toString()).toIntOrNull() ?: Container.GYRO_MODE_DEFAULT)
    }
    var gyroSensitivity by remember {
        mutableStateOf(shortcut.getExtra("gyroSensitivity",
            shortcut.container.gyroSensitivity.toString()).toFloatOrNull() ?: Container.GYRO_SENSITIVITY_DEFAULT)
    }
    var gyroInvertX by remember {
        mutableStateOf(shortcut.getExtra("gyroInvertX",
            if (shortcut.container.isGyroInvertX) "1" else "0") == "1")
    }
    var gyroInvertY by remember {
        mutableStateOf(shortcut.getExtra("gyroInvertY",
            if (shortcut.container.isGyroInvertY) "1" else "0") == "1")
    }

    // Vulkan renderer per-game overrides (native / Colors=swapRB / present mode) — default to the
    // container's values; only shown + relevant when this shortcut runs on the Vulkan renderer.
    // Stored via the same "native"/"swapRB"/"presentMode" extras the launch resolver reads.
    var vkNative by remember {
        mutableStateOf(shortcut.getExtra("native",
            if (shortcut.container.isRendererNative()) "true" else "false") == "true")
    }
    var vkSwapRB by remember {
        mutableStateOf(shortcut.getExtra("swapRB",
            if (shortcut.container.getRendererSwapRB()) "true" else "false") == "true")
    }
    var vkPresentMode by remember {
        mutableStateOf(shortcut.getExtra("presentMode", shortcut.container.getRendererPresentMode()))
    }

    // Render scale (supersampling) — per-game override, defaults to the container's "renderScale"
    // extra. Stored via the shortcut "renderScale" extra. "1.0" = Off.
    var renderScale by remember {
        mutableStateOf(shortcut.getExtra("renderScale", shortcut.container.getExtra("renderScale", "1.0")))
    }

    // Single guest-side refresh control (per-game override). Backed by two extras that the merged
    // "In-game refresh rate" dropdown drives together: unlockGameRefreshRate ("" inherit / "1" / "0")
    // and maxGameRefreshRate ("" inherit / "0" unlimited / "N" cap). Both empty = inherit container.
    // See Container.isUnlockGameRefreshRate / getMaxGameRefreshRate.
    var maxGameRefreshRate by remember {
        mutableStateOf(shortcut.getExtra("maxGameRefreshRate", ""))
    }
    var unlockGameRefreshRate by remember {
        mutableStateOf(shortcut.getExtra("unlockGameRefreshRate", ""))
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

    // Power-user performance toggles — per-game overrides. Effective seed = per-game override (if the
    // shortcut already has the key) else the GLOBAL default (App Settings > Performance). No container
    // level. Saving writes the key ONLY when it DIFFERS from the global default, else clears it so the
    // game re-inherits (see the save block below).
    var sustainedPerfMode by remember {
        mutableStateOf(shortcut.getExtra("sustainedPerfMode",
            if (com.winlator.star.perf.PerformanceSettings.sustainedPerfMode.value) "1" else "0") == "1")
    }
    var perfPriorityBoost by remember {
        mutableStateOf(shortcut.getExtra("perfPriorityBoost",
            if (com.winlator.star.perf.PerformanceSettings.perfPriorityBoost.value) "1" else "0") == "1")
    }
    var preferBigCores by remember {
        mutableStateOf(shortcut.getExtra("preferBigCores",
            if (com.winlator.star.perf.PerformanceSettings.preferBigCores.value) "1" else "0") == "1")
    }
    // Root six — same override/inherit treatment, kept in an observable map keyed by extraData name.
    val rootOverrides = remember {
        mutableStateMapOf<String, Boolean>().apply {
            for (k in com.winlator.star.perf.PerfRootApplier.ROOT_KEYS)
                put(k, shortcut.getExtra(k, if (com.winlator.star.perf.PerformanceSettings.rootDefaultValue(k)) "1" else "0") == "1")
        }
    }
    // The 9 power-user perf toggles live in a collapsed "Performance" section to keep this dialog short.
    var perfExpanded by rememberSaveable { mutableStateOf(false) }

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

    // Per-game controller->player-slot pins. EMPTY string = no shortcut override (inherit the container's
    // Player Slots); any non-empty value means this shortcut OWNS the pins. Same JSON schema (and editor)
    // as the container editor and the in-game Players tab — mutated only via WinHandler.parse/build. The
    // launch resolver reads this extra first, else the container's (resolvedControllerSlotOverridesJson).
    var controllerSlotOverridesJson by remember { mutableStateOf(shortcut.getExtra("controllerSlotOverrides", "")) }

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
    // Custom-startup per-service enabled set (raw names). Inherits the container default when the
    // shortcut has no override, same fallback pattern as startupSelection above.
    var startupServicesEnabled by remember {
        mutableStateOf(
            WineUtils.parseStartupServicesCsv(
                shortcut.getExtra("startupServices", shortcut.container.startupServices)
            ).toSet()
        )
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

    // Env vars live in dialog-level state (not in the tab) so switching tabs can't drop
    // in-progress edits; written back to the shortcut's extras in save() below.
    var envVarsStr by remember { mutableStateOf(shortcut.getExtra("envVars")) }
    // The game's folder on the Android side, derived from the shortcut's Exec= path, so the
    // editor can look for DLLs the game ships. Null when the drive letter isn't mapped.
    val gameDir = remember(shortcut) {
        runCatching { WinePath.resolveAndroidPath(shortcut.container, shortcut.path)?.parentFile }
            .getOrNull()
    }
    // The game's .exe on the Android side — feeds DependencyDetector's game-root resolution for the
    // "Recommended components" chips in the Win Components tab.
    val gameExe = remember(shortcut) {
        runCatching { WinePath.resolveAndroidPath(shortcut.container, shortcut.path) }.getOrNull()
    }

    // AndroidView refs
    val cpuListViewRef = remember { mutableStateOf<CPUListView?>(null) }

    // Icon
    var iconBitmap by remember { mutableStateOf<Bitmap?>(shortcut.icon) }

    // Sub-dialog show states
    var showGfxConfig by remember { mutableStateOf(false) }
    var showDxvkConfig by remember { mutableStateOf(false) }
    var showWineD3DConfig by remember { mutableStateOf(false) }
    // Per-field "?" help (helpRes) + the newcomer glossary ("What is all this?"), mirrored from the
    // container editor. null = hidden; glossaryQuery == "" opens the glossary unfiltered.
    var helpRes by remember { mutableStateOf<Int?>(null) }
    var glossaryQuery by remember { mutableStateOf<String?>(null) }
    var showBox64DownloadSheet by remember { mutableStateOf(false) }
    var showFexCoreDownloadSheet by remember { mutableStateOf(false) }
    var showDxvkDownloadSheet by remember { mutableStateOf(false) }
    var showVegasDownloadSheet by remember { mutableStateOf(false) }
    var showVkd3dDownloadSheet by remember { mutableStateOf(false) }
    var showD7vkDownloadSheet by remember { mutableStateOf(false) }

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
        val envVars = envVarsStr
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
            // Gyro per-game overrides (read by the launch resolver in XServerDisplayActivity).
            putExtra("gyroEnabled", if (gyroEnabled) "1" else "0")
            putExtra("gyroTarget", gyroTarget.toString())
            putExtra("gyroActivator", gyroActivator.toString())
            putExtra("gyroActivationMode", gyroActivationMode.toString())
            putExtra("gyroMode", gyroMode.toString())
            putExtra("gyroSensitivity", gyroSensitivity.toString())
            putExtra("gyroInvertX", if (gyroInvertX) "1" else "0")
            putExtra("gyroInvertY", if (gyroInvertY) "1" else "0")
            // Vulkan per-game overrides (read by resolvedRendererNative/SwapRB/PresentMode at launch).
            putExtra("native", if (vkNative) "true" else "false")
            putExtra("swapRB", if (vkSwapRB) "true" else "false")
            putExtra("presentMode", vkPresentMode)
            putExtra("renderScale", if (renderScale == "1.0") null else renderScale)
            // "In-game refresh rate" per-game override: both extras written together, "" = inherit the
            // container (store null so the extra is cleared, not left empty → keeps the shortcut default).
            putExtra("maxGameRefreshRate", maxGameRefreshRate.ifEmpty { null })
            putExtra("unlockGameRefreshRate", unlockGameRefreshRate.ifEmpty { null })
            putExtra("frameGenEngine", frameGenEngine)
            putExtra("fpsLimiterEnabled", if (fpsLimiterEnabled) "1" else "0")
            // Override-when-different: write the per-game key only when it differs from the global
            // default; otherwise null clears the extra so the game re-inherits (hasExtra=false).
            putExtra("sustainedPerfMode", perfExtraOrNull(sustainedPerfMode, com.winlator.star.perf.PerformanceSettings.sustainedPerfMode.value))
            putExtra("perfPriorityBoost", perfExtraOrNull(perfPriorityBoost, com.winlator.star.perf.PerformanceSettings.perfPriorityBoost.value))
            putExtra("preferBigCores", perfExtraOrNull(preferBigCores, com.winlator.star.perf.PerformanceSettings.preferBigCores.value))
            for (rk in com.winlator.star.perf.PerfRootApplier.ROOT_KEYS)
                putExtra(rk, perfExtraOrNull(rootOverrides[rk] ?: false, com.winlator.star.perf.PerformanceSettings.rootDefaultValue(rk)))
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
            // Empty = clear the extra so the game re-inherits the container's Player Slots.
            putExtra("controllerSlotOverrides", controllerSlotOverridesJson.ifEmpty { null })
            putExtra("numControllers", numCtrl.toString())
            putExtra("box64Version", selectedBox64Version)
            putExtra("box64Preset", b64PresetId)
            putExtra("fexcoreVersion", selectedFexCoreVersion)
            putExtra("fexcorePreset", fexPresetId)
            putExtra("controlsProfile", if (ctrlProfileId > 0) ctrlProfileId.toString() else null)
            putExtra("startupSelection", startupIdx.toString())
            // Persist the Custom enabled set alongside the selection (launch reads it only when
            // the selection is Custom). Written regardless so switching presets keeps the picks.
            putExtra("startupServices", startupServicesEnabled.joinToString(","))
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

    // Panel refresh rates (drives whether the "In-game refresh rate" row exists) — hoisted so the D-pad
    // order list below can account for that conditional row.
    val panelRates = remember {
        com.winlator.star.widget.XServerView.getSupportedRefreshRates(
            if (android.os.Build.VERSION.SDK_INT >= 30) context.display
            else (context.getSystemService(android.content.Context.WINDOW_SERVICE)
                    as android.view.WindowManager).defaultDisplay)
    }

    // The ORDERED, currently-visible focusable ids (mirrors the render conditionals below). Rebuilt each
    // recomposition so conditional rows (custom W/H, SF/Vulkan blocks, refresh, MIDI, gyro block) drop in
    // and out of the D-pad order automatically. Tab content is touch-navigable (see report) — the tab ROW
    // itself is here so Left/Right switches tabs and the whole top form + OK/Cancel are controller-driven.
    val dpadIds = buildList {
        add("titleX"); add("name"); add("execArgs"); add("screenSize")
        if (selectedScreenSize == "Custom") { add("customW"); add("customH") }
        add("selectIcon"); add("gfxDriver"); add("gfxWrapper"); add("gfxConfig")
        add("dxWrapper"); add("dxConfig"); add("renderer")
        if (selectedRenderer == "SurfaceFlinger") add("sfCompat")
        if (selectedRenderer == "Vulkan") { add("vkNative"); add("vkColors"); add("vkPresent") }
        add("renderScale")
        if (panelRates.isNotEmpty()) add("refresh")
        add("frameGen"); add("fpsLimiter"); add("audio"); add("emulator")
        if (midiList.isNotEmpty()) add("midi")
        add("lcAll"); add("fullscreen"); add("autoClose")
        add("enableXInput"); add("enableDInput"); add("exclusiveXInput"); add("disableXInput"); add("simTouch"); add("numControllers")
        add("gyroEnabled")
        if (gyroEnabled) {
            add("gyroMode"); add("gyroTarget"); add("gyroActivator")
            if (gyroActivator != Container.GYRO_ACTIVATOR_ALWAYS) add("gyroActivationMode")
            add("gyroSensitivity"); add("gyroInvertX"); add("gyroInvertY")
        }
        add("tabs"); add("cancel"); add("ok")
    }
    // Seed the root focus so the editor receives D-pad from the first frame (it's its own Dialog window).
    LaunchedEffect(Unit) { runCatching { dp.rootFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f)
                .settingsDpad(dp, { dpadIds }, onDismiss),
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
                    DpButton(dp, "titleX", onActivate = onDismiss) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }
                Divider(color = DividerColor)

                // Scrollable content
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Name
                    DpField(
                        dp, "name",
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.name),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Exec Args
                    DpField(
                        dp, "execArgs",
                        value = execArgs,
                        onValueChange = { execArgs = it },
                        label = stringResource(R.string.exec_arguments),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Screen size
                    DpDrop(
                        dp, "screenSize",
                        label = stringResource(R.string.screen_size),
                        options = screenSizeEntries,
                        selected = selectedScreenSize,
                        onSelect = { selectedScreenSize = it }
                    )
                    if (selectedScreenSize == "Custom") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DpField(
                                dp, "customW",
                                value = customWidth,
                                onValueChange = { customWidth = it },
                                label = "Width",
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                onRightId = "customH"
                            )
                            DpField(
                                dp, "customH",
                                value = customHeight,
                                onValueChange = { customHeight = it },
                                label = "Height",
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                onLeftId = "customW"
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
                            DpButton(dp, "selectIcon", onActivate = { showIconPickMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { showIconPickMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Select Icon")
                                }
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

                    // "What is all this?" — the same newcomer glossary the container editor shows,
                    // reused verbatim so the per-game editor's terms match the container's.
                    TextButton(onClick = { glossaryQuery = "" }) {
                        Text("❔  What is all this?")
                    }

                    // Graphics Driver + wrapper manager (cloud)
                    var showWrapperManager by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DpDrop(
                            dp, "gfxDriver",
                            label = stringResource(R.string.graphics_driver),
                            options = graphicsDriverEntries,
                            selected = selectedGfxDriver,
                            onSelect = { selectedGfxDriver = it },
                            modifier = Modifier.weight(1f),
                            onRightId = "gfxWrapper"
                        )
                        IconButton(onClick = { helpRes = R.string.help_graphics_driver }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                        DpButton(dp, "gfxWrapper", onActivate = { showWrapperManager = true }, onLeftId = "gfxDriver") {
                            IconButton(onClick = { showWrapperManager = true }) {
                                Icon(Icons.Default.CloudDownload, contentDescription = stringResource(R.string.wrapper_manager_open))
                            }
                        }
                    }
                    if (showWrapperManager) WrapperManagerDialog(onDismiss = {
                        showWrapperManager = false
                        wrapperRefreshKey++ // pick up a just-imported/deleted wrapper
                    })
                    DpButton(dp, "gfxConfig", onActivate = { showGfxConfig = true }, modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { showGfxConfig = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("${stringResource(R.string.graphics_driver)}: ${GraphicsDriverConfigDialog.getVersion(graphicsDriverConfig)}")
                        }
                    }

                    // DX Wrapper
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DpDrop(
                            dp, "dxWrapper",
                            label = stringResource(R.string.dxwrapper),
                            options = dxWrapperEntries,
                            selected = selectedDxWrapper,
                            onSelect = { selectedDxWrapper = it },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { helpRes = R.string.dxwrapper_help_content }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
                    DpButton(dp, "dxConfig", onActivate = {
                        val w = StringUtils.parseIdentifier(selectedDxWrapper)
                        if (w.contains("dxvk") || w.contains("vegas")) showDxvkConfig = true
                        else showWineD3DConfig = true
                    }, modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                val w = StringUtils.parseIdentifier(selectedDxWrapper)
                                if (w.contains("dxvk") || w.contains("vegas")) showDxvkConfig = true
                                else showWineD3DConfig = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("DX Wrapper Config") }
                    }

                    // Renderer (host) — per-game override of the container's OpenGL/Vulkan choice.
                    var showSfWarning by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DpDrop(
                            dp, "renderer",
                            label = stringResource(R.string.renderer),
                            options = listOf("OpenGL", "Vulkan", "SurfaceFlinger"),
                            selected = selectedRenderer,
                            onSelect = {
                                // SurfaceFlinger is experimental and can reboot some devices — require opt-in.
                                if (it == "SurfaceFlinger" && selectedRenderer != "SurfaceFlinger") showSfWarning = true
                                else selectedRenderer = it
                            },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { helpRes = R.string.help_renderer }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
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
                            IconButton(onClick = { helpRes = R.string.help_renderer_sf_compat }) {
                                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                            }
                            DpSwitch(dp, "sfCompat", checked = sfCompatMode, onCheckedChange = { sfCompatMode = it })
                        }
                    }

                    // Vulkan renderer per-game overrides — only relevant when this game runs on Vulkan.
                    if (selectedRenderer == "Vulkan") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.renderer_native), Modifier.weight(1f))
                            IconButton(onClick = { helpRes = R.string.help_renderer_native }) {
                                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                            }
                            DpSwitch(dp, "vkNative", checked = vkNative, onCheckedChange = { vkNative = it })
                        }
                        // Colors = the game buffer's channel order. BGRA (default) presents as-is; RGBA
                        // swaps R/B (routes through the compositor — native can't swap). Per-game so one
                        // game can differ from the container / its siblings.
                        val vkColorOrders = listOf("BGRA", "RGBA")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DpDrop(
                                dp, "vkColors",
                                label = stringResource(R.string.renderer_colors),
                                options = vkColorOrders,
                                selected = if (vkSwapRB) "RGBA" else "BGRA",
                                onSelect = { vkSwapRB = (it == "RGBA") },
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { helpRes = R.string.help_renderer_colors }) {
                                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                            }
                        }
                        // Present mode is ignored under Native Rendering (direct scanout), so grey it out.
                        val vkPmValues = listOf("fifo", "mailbox", "immediate")
                        val vkPmLabels = listOf(
                            stringResource(R.string.renderer_present_mode_fifo),
                            stringResource(R.string.renderer_present_mode_mailbox),
                            stringResource(R.string.renderer_present_mode_immediate)
                        )
                        val vkPmIdx = vkPmValues.indexOf(vkPresentMode).coerceAtLeast(0)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DpDrop(
                                dp, "vkPresent",
                                label = stringResource(R.string.renderer_present_mode),
                                options = vkPmLabels,
                                selected = vkPmLabels[vkPmIdx],
                                onSelect = { vkPresentMode = vkPmValues[vkPmLabels.indexOf(it)] },
                                enabled = !vkNative,
                                modifier = (if (vkNative) Modifier.alpha(0.5f) else Modifier).weight(1f)
                            )
                            IconButton(onClick = { helpRes = R.string.renderer_present_mode_help_content }) {
                                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                            }
                        }
                        // FG temporarily forces Mailbox; caption the field so FIFO-while-FG-selected isn't confusing.
                        if (frameGenEngine != "off") {
                            Text(
                                stringResource(R.string.renderer_present_mode_fg_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Render scale (supersampling) — per-game override of the container default.
                    run {
                        val renderScaleValues = listOf("1.0", "1.25", "1.5", "2.0")
                        val renderScaleLabels = listOf("Off", "1.25x", "1.5x", "2x")
                        val rsIdx = renderScaleValues.indexOf(renderScale).coerceAtLeast(0)
                        DpDrop(
                            dp, "renderScale",
                            label = "Render scale (supersampling)",
                            options = renderScaleLabels,
                            selected = renderScaleLabels[rsIdx],
                            onSelect = { renderScale = renderScaleValues[renderScaleLabels.indexOf(it)] }
                        )
                    }

                    // In-game refresh rate — single per-game override of the container default. Options:
                    // Use container default (inherit) / Locked (60) / <rate> Hz / Unlimited. Drives the
                    // two underlying extras (unlock + cap) together; empty = inherit.
                    run {
                        // panelRates is hoisted to the dialog body (so the D-pad order can see this row).
                        if (panelRates.isNotEmpty()) {
                            // Only rates ABOVE 60 are cap options — "Locked (60)" already covers 60.
                            val ratesAbove60 = panelRates.filter { it > 60 }
                            // Sentinel value model: "" = inherit, "locked" = Locked(60), "0" = Unlimited,
                            // "N" = cap N. Maps to the (unlock, cap) extra pair on select.
                            val rrValues = listOf("", "locked", "0") + ratesAbove60.map { it.toString() }
                            val rrLabels = listOf(
                                stringResource(R.string.use_container_default),
                                stringResource(R.string.in_game_refresh_locked),
                                stringResource(R.string.max_game_refresh_rate_unlimited)) +
                                ratesAbove60.map { "$it Hz" }
                            val currentValue = when {
                                unlockGameRefreshRate.isEmpty() && maxGameRefreshRate.isEmpty() -> ""
                                unlockGameRefreshRate == "0" -> "locked"
                                else -> maxGameRefreshRate.ifEmpty { "0" }
                            }
                            val rrIdx = rrValues.indexOf(currentValue).coerceAtLeast(0)
                            DpDrop(
                                dp, "refresh",
                                label = stringResource(R.string.in_game_refresh_rate),
                                options = rrLabels,
                                selected = rrLabels[rrIdx],
                                onSelect = {
                                    when (val v = rrValues[rrLabels.indexOf(it)]) {
                                        ""       -> { unlockGameRefreshRate = "";  maxGameRefreshRate = "" }
                                        "locked" -> { unlockGameRefreshRate = "0"; maxGameRefreshRate = "0" }
                                        else     -> { unlockGameRefreshRate = "1"; maxGameRefreshRate = v }
                                    }
                                }
                            )
                        }
                    }

                    // Frame Generation engine — per-game override (lsfg grayed without Lossless.dll).
                    run {
                        val fgLabels = listOf(
                            stringResource(R.string.frame_generation_off),
                            stringResource(R.string.frame_generation_bionic),
                            stringResource(R.string.frame_generation_lsfg)
                        )
                        val fgIdx = fgEngines.indexOf(frameGenEngine).coerceAtLeast(0)
                        // FG's mailbox/present-mode delivery only exists on the Vulkan host renderer, so
                        // gate the whole dropdown on Vulkan (grey it out otherwise) — combined with the
                        // existing lsfg-DLL option gate. See ContainerDetailScreen for the rationale.
                        val fgVulkan = selectedRenderer == "Vulkan"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DpDrop(
                                dp, "frameGen",
                                label = stringResource(R.string.frame_generation),
                                options = fgLabels,
                                selected = fgLabels[fgIdx],
                                onSelect = { frameGenEngine = fgEngines[fgLabels.indexOf(it)] },
                                enabled = fgVulkan,
                                disabledOptions = buildSet {
                                    // bionic-fg re-enabled (2.9.4+) — see ContainerDetailScreen note.
                                    if (!lsfgDllAvailable) add(fgLabels[2])   // lsfg-vk — needs an imported Lossless.dll
                                },
                                modifier = (if (!fgVulkan) Modifier.alpha(0.5f) else Modifier).weight(1f)
                            )
                            IconButton(onClick = { helpRes = R.string.help_frame_generation }) {
                                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                            }
                        }
                        if (!fgVulkan) {
                            Text(
                                text = stringResource(R.string.frame_generation_requires_vulkan),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                        DpSwitch(dp, "fpsLimiter", checked = fpsLimiterEnabled, onCheckedChange = { fpsLimiterEnabled = it })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.fps_limiter), modifier = Modifier.weight(1f))
                        IconButton(onClick = { helpRes = R.string.help_fps_limiter }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }

                    // Power-user performance toggles — collapsed into an expandable "Performance" section
                    // (closed by default) so the shortcut dialog stays short. Each toggle is a compact row;
                    // a per-game toggle is only saved when it differs from the App Settings global default.
                    val anyPerfOverride = sustainedPerfMode != com.winlator.star.perf.PerformanceSettings.sustainedPerfMode.value ||
                        perfPriorityBoost != com.winlator.star.perf.PerformanceSettings.perfPriorityBoost.value ||
                        preferBigCores != com.winlator.star.perf.PerformanceSettings.preferBigCores.value ||
                        com.winlator.star.perf.PerfRootApplier.ROOT_KEYS.any { (rootOverrides[it] ?: false) != com.winlator.star.perf.PerformanceSettings.rootDefaultValue(it) }
                    val perfOverrideCount = (if (sustainedPerfMode != com.winlator.star.perf.PerformanceSettings.sustainedPerfMode.value) 1 else 0) +
                        (if (perfPriorityBoost != com.winlator.star.perf.PerformanceSettings.perfPriorityBoost.value) 1 else 0) +
                        (if (preferBigCores != com.winlator.star.perf.PerformanceSettings.preferBigCores.value) 1 else 0) +
                        com.winlator.star.perf.PerfRootApplier.ROOT_KEYS.count { (rootOverrides[it] ?: false) != com.winlator.star.perf.PerformanceSettings.rootDefaultValue(it) }

                    // Collapsible header.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { perfExpanded = !perfExpanded }.padding(vertical = 8.dp)
                    ) {
                        Text("Performance", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        Text(
                            if (perfOverrideCount > 0) "$perfOverrideCount overridden" else "Global defaults",
                            fontSize = 11.sp,
                            color = if (perfOverrideCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Icon(
                            if (perfExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (perfExpanded) "Collapse" else "Expand"
                        )
                    }

                    if (perfExpanded) {
                        PerfEditRow(dp, "sustainedPerf", "Sustained Performance Mode", sustainedPerfMode,
                            com.winlator.star.perf.PerformanceSettings.sustainedPerfMode.value) { sustainedPerfMode = it }
                        PerfEditRow(dp, "perfPriority", "Thread Priority Boost", perfPriorityBoost,
                            com.winlator.star.perf.PerformanceSettings.perfPriorityBoost.value) { perfPriorityBoost = it }
                        PerfEditRow(dp, "preferBigCores", "Prefer Big Cores", preferBigCores,
                            com.winlator.star.perf.PerformanceSettings.preferBigCores.value) { preferBigCores = it }
                        // Root six (per-game overrides; only take effect with root, honored at launch).
                        for (rk in com.winlator.star.perf.PerfRootApplier.ROOT_KEYS) {
                            PerfEditRow(dp, rk, ROOT_PERF_LABELS[rk] ?: rk, rootOverrides[rk] ?: false,
                                com.winlator.star.perf.PerformanceSettings.rootDefaultValue(rk)) { rootOverrides[rk] = it }
                        }
                        // Reset ALL 9 perf keys to the global defaults (visible when this game overrides any).
                        if (anyPerfOverride) {
                            Text("↺ Reset all performance toggles to global",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    sustainedPerfMode = com.winlator.star.perf.PerformanceSettings.sustainedPerfMode.value
                                    perfPriorityBoost = com.winlator.star.perf.PerformanceSettings.perfPriorityBoost.value
                                    preferBigCores = com.winlator.star.perf.PerformanceSettings.preferBigCores.value
                                    for (rk in com.winlator.star.perf.PerfRootApplier.ROOT_KEYS)
                                        rootOverrides[rk] = com.winlator.star.perf.PerformanceSettings.rootDefaultValue(rk)
                                }.padding(vertical = 6.dp))
                        }
                    }

                    // Audio driver
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DpDrop(
                            dp, "audio",
                            label = stringResource(R.string.audio_driver),
                            options = audioDriverEntries,
                            selected = selectedAudioDriver,
                            onSelect = { selectedAudioDriver = it },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { helpRes = R.string.help_audio_driver }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }

                    // Emulator
                    DpDrop(
                        dp, "emulator",
                        label = "Emulator",
                        options = emulatorEntries,
                        selected = selectedEmulator,
                        onSelect = { selectedEmulator = it },
                        enabled = isArm64EC
                    )

                    // MIDI
                    if (midiList.isNotEmpty()) {
                        val midiDisplay = midiList.firstOrNull { it == selectedMidi } ?: midiList.first()
                        DpDrop(
                            dp, "midi",
                            label = "MIDI Sound Font",
                            options = midiList,
                            selected = midiDisplay,
                            onSelect = { selectedMidi = it }
                        )
                    }

                    // LC_ALL
                    DpField(
                        dp, "lcAll",
                        value = lcAll,
                        onValueChange = { lcAll = it },
                        label = "LC_ALL",
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
                    DpDrop(
                        dp, "fullscreen",
                        label = stringResource(R.string.fullscreen_mode),
                        options = fsOverrideLabels,
                        selected = fsOverrideLabels[fsOverrideIdx],
                        onSelect = { sel ->
                            val idx = fsOverrideLabels.indexOf(sel)
                            fullscreenModeOverride = if (idx <= 0) -1 else idx - 1
                        }
                    )

                    // Close the session when this game exits (per-game override; container default is ON)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DpCheck(dp, "autoClose", checked = autoCloseOnExit, onCheckedChange = { autoCloseOnExit = it })
                        Text("Close when game exits")
                    }

                    // Input section
                    SectionBox(title = "Input") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DpSwitch(
                                dp, "enableXInput",
                                checked = enableXInput,
                                onCheckedChange = { enableXInput = it },
                                enabled = exclusiveXInput
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.enable_xinput_for_wine_game), modifier = Modifier.weight(1f))
                            IconButton(onClick = { helpRes = R.string.help_xinput }) {
                                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DpSwitch(
                                dp, "enableDInput",
                                checked = enableDInput,
                                onCheckedChange = { enableDInput = it },
                                enabled = exclusiveXInput
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.enable_dinput_for_wine_game), modifier = Modifier.weight(1f))
                            IconButton(onClick = { helpRes = R.string.help_dinput }) {
                                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DpSwitch(
                                dp, "exclusiveXInput",
                                checked = exclusiveXInput,
                                onCheckedChange = { checked ->
                                    exclusiveXInput = checked
                                    if (!checked) { enableXInput = true; enableDInput = true }
                                    else if (enableXInput && enableDInput) enableDInput = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Exclusive Input", modifier = Modifier.weight(1f))
                            IconButton(onClick = { helpRes = R.string.help_exclusive_xinput }) {
                                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DpCheck(dp, "disableXInput", checked = disabledXInput, onCheckedChange = { disabledXInput = it })
                            Text("Disable XInput")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DpCheck(dp, "simTouch", checked = simTouchScreen, onCheckedChange = { simTouchScreen = it })
                            Text("Touchscreen Mode")
                        }
                        DpDrop(
                            dp, "numControllers",
                            label = "Num Controllers",
                            options = numControllersEntries,
                            selected = selectedNumControllers,
                            onSelect = { selectedNumControllers = it }
                        )

                        // Player Slots (per-game override). Empty override = inherit the container's
                        // Player Slots; touching any slot makes this shortcut own the pins. The "Use
                        // container default" button clears the override so it re-inherits. Editing an
                        // empty override starts from an all-auto ("{}") view.
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Player Slots", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            IconButton(onClick = { helpRes = R.string.help_player_slots }) {
                                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                            }
                            if (controllerSlotOverridesJson.isNotEmpty()) {
                                TextButton(onClick = { controllerSlotOverridesJson = "" }) {
                                    Text("Use container default")
                                }
                            }
                        }
                        if (controllerSlotOverridesJson.isEmpty()) {
                            Text(
                                "Inheriting the container's Player Slots. Pin a controller below to set a per-game override.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PlayerSlotsEditor(
                            savedOverridesJson = controllerSlotOverridesJson.ifEmpty { "{}" },
                            onOverridesChange = { controllerSlotOverridesJson = it },
                        )

                        // Gyro (motion aim) per-game override. Deadzone/smoothing are deliberately
                        // absent — those stay on the container (Container Settings -> Gyro).
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DpSwitch(dp, "gyroEnabled", checked = gyroEnabled, onCheckedChange = { gyroEnabled = it })
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.gyro_enabled), modifier = Modifier.weight(1f))
                        }
                        if (gyroEnabled) {
                            // Same pairing rule as the container editor: Tilt to Aim and the Mouse
                            // target can't coexist (a held tilt is a constant pointer delta), so each
                            // selection knocks the other back to a working value.
                            val gyroModeLabels = listOf(
                                stringResource(R.string.gyro_mode_rate),
                                stringResource(R.string.gyro_mode_orientation),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DpDrop(
                                    dp, "gyroMode",
                                    label = stringResource(R.string.gyro_mode_label),
                                    options = gyroModeLabels,
                                    selected = gyroModeLabels.getOrElse(gyroMode) {
                                        gyroModeLabels[Container.GYRO_MODE_DEFAULT]
                                    },
                                    onSelect = { opt ->
                                        gyroMode = gyroModeLabels.indexOf(opt).coerceAtLeast(0)
                                        if (gyroMode == Container.GYRO_MODE_ORIENTATION && gyroTarget == Container.GYRO_TARGET_MOUSE)
                                            gyroTarget = Container.GYRO_TARGET_DEFAULT
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { helpRes = R.string.help_gyro_mode }) {
                                    Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            val gyroTargetLabels = listOf(
                                stringResource(R.string.gyro_target_right_stick),
                                stringResource(R.string.gyro_target_left_stick),
                                stringResource(R.string.gyro_target_mouse),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DpDrop(
                                    dp, "gyroTarget",
                                    label = stringResource(R.string.gyro_target_label),
                                    options = gyroTargetLabels,
                                    selected = gyroTargetLabels.getOrElse(gyroTarget) {
                                        gyroTargetLabels[Container.GYRO_TARGET_DEFAULT]
                                    },
                                    onSelect = { opt ->
                                        gyroTarget = gyroTargetLabels.indexOf(opt).coerceAtLeast(0)
                                        if (gyroTarget == Container.GYRO_TARGET_MOUSE)
                                            gyroMode = Container.GYRO_MODE_RATE
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { helpRes = R.string.help_gyro_target }) {
                                    Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                                }
                            }
                            val gyroActivatorLabels = listOf(
                                stringResource(R.string.gyro_activator_l1),
                                stringResource(R.string.gyro_activator_l2),
                                stringResource(R.string.gyro_activator_r1),
                                stringResource(R.string.gyro_activator_r3),
                                stringResource(R.string.gyro_activator_always),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DpDrop(
                                    dp, "gyroActivator",
                                    label = stringResource(R.string.gyro_activator_label),
                                    options = gyroActivatorLabels,
                                    selected = gyroActivatorLabels.getOrElse(gyroActivator) {
                                        gyroActivatorLabels[Container.GYRO_ACTIVATOR_DEFAULT]
                                    },
                                    onSelect = { opt -> gyroActivator = gyroActivatorLabels.indexOf(opt).coerceAtLeast(0) },
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { helpRes = R.string.help_gyro_activator }) {
                                    Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                                }
                            }
                            // Hold vs Toggle for that button — hidden under "Always On", which has no
                            // button to latch (same call the container editor makes).
                            if (gyroActivator != Container.GYRO_ACTIVATOR_ALWAYS) {
                                val gyroActivationModeLabels = listOf(
                                    stringResource(R.string.gyro_activation_hold),
                                    stringResource(R.string.gyro_activation_toggle),
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    DpDrop(
                                        dp, "gyroActivationMode",
                                        label = stringResource(R.string.gyro_activation_mode_label),
                                        options = gyroActivationModeLabels,
                                        selected = gyroActivationModeLabels.getOrElse(gyroActivationMode) {
                                            gyroActivationModeLabels[Container.GYRO_ACTIVATION_MODE_DEFAULT]
                                        },
                                        onSelect = { opt -> gyroActivationMode = gyroActivationModeLabels.indexOf(opt).coerceAtLeast(0) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { helpRes = R.string.help_gyro_activation_mode }) {
                                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${stringResource(R.string.gyro_sensitivity_label)}: ${"%.1f".format(gyroSensitivity)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { helpRes = R.string.help_gyro_sensitivity }) {
                                    Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                                }
                            }
                            DpSlider(
                                dp, "gyroSensitivity",
                                value = gyroSensitivity,
                                onValueChange = { gyroSensitivity = it },
                                valueRange = 0.1f..10f,
                                step = 0.5f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DpSwitch(dp, "gyroInvertX", checked = gyroInvertX, onCheckedChange = { gyroInvertX = it })
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.gyro_invert_x), modifier = Modifier.weight(1f))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DpSwitch(dp, "gyroInvertY", checked = gyroInvertY, onCheckedChange = { gyroInvertY = it })
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.gyro_invert_y), modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // Tabs — the tab ROW is one focusable node; Left/Right (while it's focused) switches
                    // tabs. Tab CONTENT below is touch-navigable (deferred — see report).
                    DpTabs(dp, "tabs", selected = selectedTab, count = tabTitles.size, onSelect = { selectedTab = it }) {
                        TabRow(selectedTabIndex = selectedTab) {
                            tabTitles.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(title) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // Tab content
                    when (selectedTab) {
                        0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RecommendedComponentsSection(
                                container = shortcut.container,
                                exeFile = gameExe,
                                gameDir = gameDir,
                                shortcutBaseName = shortcut.name,
                            )
                            ScWinComponentsTab(winComponents)
                        }
                        1 -> ScEnvVarsTab(envVarsStr, { envVarsStr = it }, gameDir)
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
            startupServicesEnabled = startupServicesEnabled,
            onStartupServiceToggle = { raw, on ->
                startupServicesEnabled =
                    if (on) startupServicesEnabled + raw else startupServicesEnabled - raw
            },
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
                    DpButton(dp, "cancel", onActivate = onDismiss, onRightId = "ok") {
                        TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    DpButton(dp, "ok", onActivate = { save(); onDismiss() }, onLeftId = "cancel") {
                        TextButton(onClick = { save(); onDismiss() }) { Text(stringResource(android.R.string.ok)) }
                    }
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
    // See ContainerDetailScreen: relax the #113 DXVK-2.x filter only for the Mali "Wrapper + compat
    // + bcn" driver, so per-game shortcuts can also reach the DXVK 1.10.3 workaround (#137).
    val relaxDxvkFilter = StringUtils.parseIdentifier(selectedGfxDriver) == "wrapper-compat-bcn"
    if (showDxvkConfig) {
        DxvkConfigDialog(
            isArm64EC = isArm64EC,
            isVegas = isVegasCfg,
            relaxDxvkFilter = relaxDxvkFilter,
            initialConfig = dxWrapperConfig,
            onConfirm = { dxWrapperConfig = it; showDxvkConfig = false },
            onDismiss = { showDxvkConfig = false },
            onDownloadDxvk = { showDxvkConfig = false; if (isVegasCfg) showVegasDownloadSheet = true else showDxvkDownloadSheet = true },
            onDownloadVkd3d = { showDxvkConfig = false; showVkd3dDownloadSheet = true },
            onDownloadD7vk = { showDxvkConfig = false; showD7vkDownloadSheet = true }
        )
    }
    if (showWineD3DConfig) {
        WineD3DConfigDialog(
            initialConfig = dxWrapperConfig,
            onConfirm = { dxWrapperConfig = it; showWineD3DConfig = false },
            onDismiss = { showWineD3DConfig = false }
        )
    }

    // Per-field "?" help + newcomer glossary — composed INSIDE the settings Dialog's window (like the
    // config dialogs above) so HelpDialog / the glossary ModalBottomSheet render on top of it.
    helpRes?.let { HelpDialog(it) { helpRes = null } }
    glossaryQuery?.let { ContainerGlossarySheet(initialQuery = it, onDismiss = { glossaryQuery = null }) }

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
    if (showD7vkDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_D7VK,
            onDismiss = { showD7vkDownloadSheet = false },
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

// A shortcut stores only the variables explicitly set on it; the container's own values are
// merged underneath at launch (XServerDisplayActivity), so an empty editor here still inherits
// everything from the container. Nothing is seeded from the container into the shortcut.
@Composable
private fun ScEnvVarsTab(
    envVars: String,
    onEnvVarsChange: (String) -> Unit,
    gameDir: File?,
) {
    EnvVarsEditor(
        value = envVars,
        onValueChange = onEnvVarsChange,
        modifier = Modifier.fillMaxWidth(),
        gameDir = gameDir
    )
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
    startupServicesEnabled: Set<String>,
    onStartupServiceToggle: (String, Boolean) -> Unit,
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
    // On arm64ec containers the x86 backend is WOWBox64, not Box64 — label it correctly (matching
    // the container editor). The dropdown data already reads the right content type; only the label
    // was hardcoded "Box64".
    val emulatorLabel = if (isArm64EC) "WOWBox64" else "Box64"
    // Per-field "?" help — this tab is its own composable, so it carries its own helpRes
    // (mirrors the container editor's per-composable HelpDialog pattern).
    var helpRes by remember { mutableStateOf<Int?>(null) }
    helpRes?.let { HelpDialog(it) { helpRes = null } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionBox(title = emulatorLabel) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LabeledDropdown(
                    label = "$emulatorLabel Version",
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
                label = "$emulatorLabel Preset",
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
                    IconButton(onClick = { helpRes = R.string.help_fexcore_version }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(
                        label = stringResource(R.string.fexcore_preset),
                        options = fexNames,
                        selectedOption = fexNames.getOrElse(selectedFexPresetIndex) { "" },
                        onSelect = { opt -> onFexPresetIndexChange(fexNames.indexOf(opt).coerceAtLeast(0)) },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { helpRes = R.string.help_fexcore_preset }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            LabeledDropdown(
                label = stringResource(R.string.startup_selection),
                options = startupSelectionEntries,
                selectedOption = selectedStartupSelection,
                onSelect = onStartupChange,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { helpRes = R.string.help_startup_selection }) {
                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
            }
        }

        // Custom per-service toggles — only shown when "Custom" (index 3) is selected. Shares the
        // container editor's list composable so the two screens can't drift.
        if (startupSelectionEntries.indexOf(selectedStartupSelection) == Container.STARTUP_SELECTION_CUSTOM.toInt()) {
            StartupServicesToggleList(
                enabled = startupServicesEnabled,
                onToggle = onStartupServiceToggle
            )
        }

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

/**
 * Small portrait thumbnail for a Steam search result: loads the 600x900 library cover via Coil,
 * falling back to the landscape header when the portrait 404s, then to a plain placeholder box.
 * Disk cache is disabled so Coil never serves a cached 404 for a since-published cover.
 */
@Composable
private fun SteamResultThumbnail(appId: Int) {
    val context = LocalContext.current
    var useHeader by remember(appId) { mutableStateOf(false) }
    val url = if (useHeader) SteamStoreSearch.headerUrl(appId) else SteamStoreSearch.coverUrl(appId)
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .diskCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(width = 34.dp, height = 50.dp)
            .clip(RoundedCornerShape(4.dp)),
        loading = {
            Box(Modifier.fillMaxSize().background(OnSurfaceVariant.copy(alpha = 0.1f)))
        },
        error = {
            if (!useHeader) useHeader = true
            else Box(Modifier.fillMaxSize().background(OnSurfaceVariant.copy(alpha = 0.15f)))
        },
    )
}

/** Record the resolved Steam [appId] as a shortcut extra (rides with the .desktop through renames). */
private fun recordSteamAppId(container: Container, base: String, appId: Int) {
    val f = File(container.getDesktopDir(), "$base.desktop")
    if (!f.isFile) return
    runCatching { Shortcut(container, f).apply { putExtra("steamAppId", appId.toString()); saveData() } }
}

/** Best-effort blocking image download (call off the main thread). Null on any failure. */
private fun downloadBitmapOrNull(url: String): Bitmap? = try {
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 15000
    conn.readTimeout = 20000
    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
    val bmp = if (conn.responseCode in 200..299) BitmapFactory.decodeStream(conn.inputStream) else null
    conn.disconnect()
    bmp
} catch (_: Exception) { null }

/**
 * Apply the Steam [appId] to the shortcut named [base] in [container]: records the appId as a
 * shortcut extra (seeds later redist detection; it rides with the .desktop through any rename) and
 * sets its cover art — Steam CDN 600x900 portrait, falling back to the landscape header. Writes
 * both customCoverArt and the grid-tile icon PNG (keyed on the current base). Returns the bitmap or null.
 */
private fun applySteamCover(container: Container, base: String, appId: Int): Bitmap? {
    val shortcutFile = File(container.getDesktopDir(), "$base.desktop")
    if (!shortcutFile.isFile) return null
    val bmp = downloadBitmapOrNull(SteamStoreSearch.coverUrl(appId))
        ?: downloadBitmapOrNull(SteamStoreSearch.headerUrl(appId))
    return try {
        val shortcut = Shortcut(container, shortcutFile)
        shortcut.putExtra("steamAppId", appId.toString())
        if (bmp != null) {
            shortcut.saveCustomCoverArt(bmp) // persists cover + saveData() (writes the extra too)
            container.getIconsDir(64)?.let { iconsDir ->
                if (!iconsDir.exists()) iconsDir.mkdirs()
                FileUtils.saveBitmapToFile(bmp, File(iconsDir, "$base.png"))
            }
        } else {
            shortcut.saveData() // no art, but still persist the recorded appId
        }
        bmp
    } catch (_: Exception) { null }
}

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

/** Add if absent, drop if present — the whole of what tapping a card in selection mode does. */
private fun Set<String>.toggle(path: String): Set<String> =
    if (path in this) this - path else this + path

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


/**
 * Marks a game whose files live on removable storage (SD card / USB) rather than internal.
 *
 * Worth surfacing because it explains behaviour the user would otherwise have to guess at: a game
 * on a card is slower to load, and it disappears entirely if the card is removed.
 */
@Composable
private fun SdCardBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.SdCard,
            contentDescription = "On SD card",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            "SD",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * A selectable option card, styled to match the File Manager's rows and the game cards behind the
 * dialog — outlined rounded rectangle, optional leading icon, title over a dimmer subtitle — so the
 * import menus read as part of the same surface rather than as bare dialog text.
 */
@Composable
private fun MenuOptionCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = OnSurface, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * One row of the bulk-import confirm list: cover art, resolved title, and the folder it came from.
 *
 * Games already in the container are shown dimmed and can't be ticked, so re-scanning the same
 * library after adding a few titles reads as "these are already here" rather than silently
 * duplicating them. Uncertain picks are badged instead of hidden — the scanner still chose its best
 * candidate, but the user gets told which ones are worth a second look before committing.
 */
@Composable
private fun ScannedGameRow(
    candidate: GameFolderScanner.Candidate,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onChangeExe: () -> Unit,
) {
    val alpha = if (candidate.alreadyAdded) 0.45f else 1f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(enabled = enabled, onClick = onToggle),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(candidate.coverUrl)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 40.dp, height = 56.dp)
                .clip(RoundedCornerShape(4.dp))
                .alpha(alpha),
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize().background(SurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp) }
            },
            error = {
                Box(
                    modifier = Modifier.fillMaxSize().background(SurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        candidate.name.take(1).uppercase(),
                        color = OnSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f).alpha(alpha)) {
            Text(
                candidate.name,
                color = OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                candidate.folder.name,
                color = OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                candidate.alreadyAdded -> Text(
                    "Already added",
                    color = OnSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                // Flagged rows name the exe, because that is what the user is being asked to judge.
                // Capped at two lines: a long name like AIO-Graphics-Test-64bit.exe otherwise wraps
                // to three and makes the cards uneven.
                candidate.uncertain -> Text(
                    "Check this one — ${candidate.exe.name}",
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
                else -> Text(
                    candidate.exe.name,
                    color = OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        // Available on EVERY row, not just flagged ones — a confident pick can still be the wrong
        // one, and the user is the only one who actually knows.
        if (!candidate.alreadyAdded) {
            TextButton(onClick = onChangeExe, enabled = enabled) { Text("Change") }
        }
    }
    }
}
