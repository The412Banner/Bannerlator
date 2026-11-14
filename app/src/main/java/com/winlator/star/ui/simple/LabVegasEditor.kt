package com.winlator.star.ui.simple

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.text.HtmlCompat
import com.winlator.star.R
import com.winlator.star.container.VegasLiveCheck
import com.winlator.star.contentdialog.DXVKConfigDialog
import com.winlator.star.contentdialog.VegasKeyCatalog
import com.winlator.star.contentdialog.VegasKeyKnowledge
import com.winlator.star.contentdialog.VegasTierPresets
import com.winlator.star.core.HttpUtils
import com.winlator.star.core.StringUtils
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.settings.Opt
import com.winlator.star.ui.deck.settings.SimplePicker
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.screens.VEGAS_ANISOTROPY_LABELS
import com.winlator.star.ui.screens.VEGAS_CONFIG_PREFS
import com.winlator.star.ui.screens.VEGAS_LOD_BIAS_LABELS
import com.winlator.star.ui.screens.VEGAS_RELEASES_FEED
import com.winlator.star.ui.screens.installContentFromUri
import com.winlator.star.ui.screens.loadVegasEditorLists
import com.winlator.star.ui.screens.loadVegasInstalled
import com.winlator.star.ui.screens.reloadVegasVersions
import com.winlator.star.ui.screens.vegasActiveForceTier
import com.winlator.star.ui.screens.vegasBackups
import com.winlator.star.ui.screens.vegasBadge
import com.winlator.star.ui.screens.vegasBaselineRows
import com.winlator.star.ui.screens.vegasBuildConfig
import com.winlator.star.ui.screens.vegasCatalogBehind
import com.winlator.star.ui.screens.vegasChangedKeys
import com.winlator.star.ui.screens.vegasDeleteBuild
import com.winlator.star.ui.screens.vegasFilterDxvk
import com.winlator.star.ui.screens.vegasHealCatalogTail
import com.winlator.star.ui.screens.vegasInitialSource
import com.winlator.star.ui.screens.vegasIsBooleanKey
import com.winlator.star.ui.screens.vegasKeyDefinition
import com.winlator.star.ui.screens.vegasLiveFile
import com.winlator.star.ui.screens.vegasLoadCatalog
import com.winlator.star.ui.screens.vegasLoadKnowledge
import com.winlator.star.ui.screens.vegasManualBackup
import com.winlator.star.ui.screens.vegasParseReleaseNotes
import com.winlator.star.ui.screens.vegasPendingRows
import com.winlator.star.ui.screens.vegasReadLive
import com.winlator.star.ui.screens.vegasReferenceKeys
import com.winlator.star.ui.screens.vegasResolvedSidecar
import com.winlator.star.ui.screens.vegasRestoreBackup
import com.winlator.star.ui.screens.vegasRows
import com.winlator.star.ui.screens.vegasSchemaBlockText
import com.winlator.star.ui.screens.vegasSidecarBase
import com.winlator.star.ui.screens.vegasSidecarExists
import com.winlator.star.ui.screens.vegasSidecarFallback
import com.winlator.star.ui.screens.vegasSidecarPath
import com.winlator.star.ui.screens.vegasStockFor
import com.winlator.star.ui.screens.vegasStockKeyValues
import com.winlator.star.ui.screens.vegasToggleTransform
import com.winlator.star.ui.screens.vegasTypoFor
import com.winlator.star.ui.screens.vegasWriteLive
import com.winlator.star.ui.screens.vegasWrittenPath
import com.winlator.star.ui.screens.vegasWrongSchema
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One of the page's choice lists, shown as a SimplePicker. */
private class VegasPick(
    val title: String,
    val options: List<Opt>,
    val current: String,
    val customLabel: String? = null,
    val onCustom: () -> Unit = {},
    val onPick: (String) -> Unit,
)

private const val RESET_TO_STOCK = "\u0000reset"

/**
 * The VEGAS config editor of Lab's editors (Graphics › "Edit the VEGAS config file"): Classic's DxvkConfigDialog
 * (VEGAS mode) as a Lab page over the editor. Build, VKD3D, async, frame rate, texture filtering, feature level, DDraw
 * wrapper, the performance tier, the config file (defaults, stock, custom, backups) and its keys, and the key catalog.
 * The lists, the live-file writes, backups, the build delete, notes and the catalog check are the Classic dialog's own
 * steps (VegasConfigOps.kt). Save = Classic's OK ([onConfirm]); leaving without saving = Cancel ([onDismiss]).
 */
@Composable
internal fun LabVegasConfigEditor(
    isArm64EC: Boolean,
    containerRootDir: File?,
    refreshKey: Int,
    initialConfig: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDownloadDxvk: () -> Unit,
    onDownloadVkd3d: () -> Unit,
    onDownloadD7vk: () -> Unit,
    relaxDxvkFilter: Boolean,
    onOpenConfigDownload: () -> Unit,
    /** After each write to the live file: the file the game must read (as Classic's onLivePointerChanged). */
    onLivePointerChanged: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context.findActivity() ?: return
    val config = remember(initialConfig) { DXVKConfigDialog.parseConfig(initialConfig) }
    var isProcessing by remember { mutableStateOf(false) }
    // A choice was changed or the config file was written: leaving asks whether to save.
    var touched by remember { mutableStateOf(false) }

    var allVersions by remember { mutableStateOf(listOf<String>()) }
    var vkd3dVersions by remember { mutableStateOf(listOf<String>()) }
    var d7vkVersions by remember { mutableStateOf(listOf(DXVKConfigDialog.D7VK_BUNDLED)) }
    var stockSources by remember { mutableStateOf(listOf<DXVKConfigDialog.StockSource>()) }
    var customEntries by remember { mutableStateOf(listOf<String>()) }
    var useDefaults by remember { mutableStateOf(true) }
    var selectedStock by remember { mutableStateOf<String?>(null) }
    var selectedCustom by remember { mutableStateOf<String?>(null) }
    var stockEdited by remember { mutableStateOf(false) }
    var toggleVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            val lists = loadVegasEditorLists(context, true, isArm64EC)
            withContext(Dispatchers.Main) {
                allVersions = lists.versions
                vkd3dVersions = lists.vkd3d
                d7vkVersions = lists.d7vk
                stockSources = lists.stock
                val pick = vegasInitialSource(config.get("dxvkConfigFile"), lists.stock, lists.configSources, containerRootDir)
                customEntries = pick.customEntries
                if (pick.useDefaults) {
                    useDefaults = true
                } else {
                    selectedStock = pick.stock
                    selectedCustom = pick.custom
                    useDefaults = false
                }
            }
        }
    }

    var selectedVkd3d by remember { mutableStateOf(config.get("vkd3dVersion").ifEmpty { "None" }) }
    val filteredDxvk = remember(selectedVkd3d, allVersions, relaxDxvkFilter) { vegasFilterDxvk(allVersions, selectedVkd3d, relaxDxvkFilter) }
    var selectedDxvk by remember(allVersions) {
        val stored = config.get("version")
        mutableStateOf(allVersions.firstOrNull { it == stored } ?: allVersions.firstOrNull() ?: stored)
    }
    // As Classic: the installed builds are read again each time the editor opens (no ghosts of deleted ones).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val (versions, stock, _) = loadVegasInstalled(context)
            withContext(Dispatchers.Main) {
                allVersions = versions
                stockSources = stock
                if (selectedDxvk !in versions) selectedDxvk = versions.firstOrNull() ?: selectedDxvk
            }
        }
    }
    val dxvkType = remember(selectedDxvk) { DXVKConfigDialog.getDXVKType(selectedDxvk) }

    val framerateEntries = remember { context.resources.getStringArray(R.array.dxvk_framerate_entries).toList() }
    val featureLevelEntries = remember { DXVKConfigDialog.VKD3D_FEATURE_LEVEL.toList() }
    val ddrawEntries = remember { context.resources.getStringArray(R.array.ddrawrapper_entries).toList() }
    var selectedFramerate by remember {
        val stored = config.get("framerate")
        mutableStateOf(framerateEntries.firstOrNull { StringUtils.parseNumber(it) == stored } ?: framerateEntries.first())
    }
    var selectedFeatureLevel by remember { mutableStateOf(featureLevelEntries.firstOrNull { it == config.get("vkd3dLevel") } ?: featureLevelEntries.first()) }
    var selectedDdraw by remember { mutableStateOf(ddrawEntries.firstOrNull { StringUtils.parseIdentifier(it) == config.get("ddrawrapper") } ?: ddrawEntries.first()) }
    var selectedD7vk by remember(d7vkVersions) {
        val stored = config.get("d7vkVersion")
        mutableStateOf(d7vkVersions.firstOrNull { it == stored } ?: DXVKConfigDialog.D7VK_BUNDLED)
    }
    var asyncEnabled by remember { mutableStateOf(config.get("async") == "1") }
    var asyncCacheEnabled by remember { mutableStateOf(config.get("asyncCache") == "1") }
    var selectedAnisotropy by remember {
        val i = DXVKConfigDialog.ANISOTROPY_VALUES.indexOf(config.get("anisotropy"))
        mutableStateOf(VEGAS_ANISOTROPY_LABELS[if (i >= 0) i else 0])
    }
    var selectedLodBias by remember {
        val i = DXVKConfigDialog.LOD_BIAS_VALUES.indexOf(config.get("lodBias"))
        mutableStateOf(VEGAS_LOD_BIAS_LABELS[if (i >= 0) i else 0])
    }

    // Performance tier (staged, null = auto) and the device's GPU.
    var tierChoice by remember { mutableStateOf<Int?>(null) }
    val gpuModel = remember { VegasTierPresets.readGpuModel(context) }
    val detectedTier = remember { VegasTierPresets.classifyModel(gpuModel) }
    // Release notes: live (cached per version) or bundled.
    var notesCache by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var notesLoading by remember { mutableStateOf(false) }
    var notesSource by remember { mutableStateOf<String?>(null) }
    var showNotes by remember { mutableStateOf(false) }

    val knowledge = remember { vegasLoadKnowledge(context) }
    val catalog = remember { vegasLoadCatalog(context) }
    val activeStockTag = remember(selectedStock, stockSources) { vegasStockFor(stockSources, selectedStock)?.tag }
    val catalogBehind = remember(catalog, selectedStock, activeStockTag) { vegasCatalogBehind(catalog, selectedStock, activeStockTag) }
    val forkPrefs = remember { context.getSharedPreferences(VEGAS_CONFIG_PREFS, Context.MODE_PRIVATE) }
    var forkFilter by remember { mutableStateOf(forkPrefs.getBoolean("forkFilter", false)) }
    var pendingSchemaBlock by remember { mutableStateOf<String?>(null) }
    var liveReport by remember { mutableStateOf<VegasLiveCheck.Report?>(null) }
    var liveChecking by remember { mutableStateOf(false) }

    // Option B: the selected path IS the live file (a stock baseline's first edit creates the user's own copy).
    val stockPath = remember(selectedStock, stockSources) { selectedStock?.let { s -> vegasStockFor(stockSources, s)?.file?.absolutePath } }
    val sidecarFallback = remember(selectedStock, stockSources) { vegasSidecarFallback(stockSources, selectedStock) }
    val sidecarBase = remember(activeStockTag, sidecarFallback) { vegasSidecarBase(activeStockTag, sidecarFallback) }
    val sidecarPath = remember(containerRootDir, sidecarBase) { vegasSidecarPath(context, containerRootDir, sidecarBase) }
    val sidecarExists = remember(toggleVersion, sidecarPath, containerRootDir, sidecarFallback, activeStockTag) {
        vegasSidecarExists(sidecarPath, containerRootDir, sidecarFallback, sidecarBase)
    }
    val resolvedSidecar = remember(sidecarPath, containerRootDir, sidecarFallback, toggleVersion) { vegasResolvedSidecar(sidecarPath, containerRootDir, sidecarFallback) }
    val liveFile = remember(useDefaults, selectedStock, selectedCustom, stockPath, resolvedSidecar, sidecarExists) {
        vegasLiveFile(useDefaults, selectedStock, selectedCustom, stockPath, resolvedSidecar, sidecarExists)
    }
    val livePath = liveFile?.absolutePath ?: ""
    val backups = remember(liveFile, toggleVersion) { vegasBackups(liveFile) }
    val configText = remember(useDefaults, liveFile, toggleVersion) { vegasReadLive(useDefaults, liveFile) }
    val configMissing = remember(liveFile, useDefaults) { !useDefaults && liveFile != null && !liveFile.isFile }
    val configRows = remember(knowledge, configText, selectedDxvk) { vegasRows(knowledge, configText, selectedDxvk) }
    val stockKeyValues = remember(stockSources, knowledge, selectedDxvk) { vegasStockKeyValues(stockSources, knowledge, selectedDxvk) }
    val baselineRows = remember(activeStockTag, knowledge, stockSources, selectedDxvk) {
        vegasBaselineRows(selectedStock, stockSources, activeStockTag, knowledge, selectedDxvk)
    }
    val pendingRows = remember(baselineRows, configRows) { vegasPendingRows(baselineRows, configRows) }
    val changedKeys = remember(configRows, baselineRows, selectedStock) { vegasChangedKeys(configRows, baselineRows, selectedStock) }
    val activeForceTier = remember(configRows, toggleVersion) { vegasActiveForceTier(configRows) }

    // ── Dialog state ──
    var pick by remember { mutableStateOf<VegasPick?>(null) }
    var customValueFor by remember { mutableStateOf<VegasKeyKnowledge.EditRow?>(null) }
    var pendingDeleteKey by remember { mutableStateOf<String?>(null) }
    var showAddKey by remember { mutableStateOf(false) }
    var showBackups by remember { mutableStateOf(false) }
    var backupFor by remember { mutableStateOf<File?>(null) }
    var restoreTarget by remember { mutableStateOf<File?>(null) }
    var showCatalog by remember { mutableStateOf(false) }
    var confirmDeleteBuild by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, String>?>(null) }
    var leaving by remember { mutableStateOf(false) }

    fun restoreBackup(backup: File) {
        val target = liveFile ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { vegasRestoreBackup(target, backup) }
            if (ok) { stockEdited = true; touched = true; toggleVersion++ }
            else toast(activity, L("Failed to restore backup"))
        }
    }

    fun manualBackup() {
        val target = liveFile ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { vegasManualBackup(target) }
            if (ok) toggleVersion++ else toast(activity, L("Failed to create backup"))
        }
    }

    fun openReleaseNotes() {
        val version = selectedDxvk.removePrefix("vegas-")
        val cached = notesCache
        if (cached != null && cached.first == version) {
            notesSource = "live"
            showNotes = true
            return
        }
        notesSource = if (VegasTierPresets.BUNDLED_NOTES[version] != null) "bundled" else "none"
        showNotes = true
        if (notesLoading) return
        notesLoading = true
        HttpUtils.download(VEGAS_RELEASES_FEED) { body ->
            scope.launch {
                val parsed = vegasParseReleaseNotes(body, version)
                if (parsed != null && parsed.isNotEmpty()) {
                    notesCache = version to parsed
                    notesSource = "live"
                }
                notesLoading = false
            }
        }
    }

    fun runLiveCheck() {
        if (liveChecking) return
        liveChecking = true
        HttpUtils.download(VEGAS_RELEASES_FEED) { body ->
            try {
                scope.launch {
                    val catalogNewest = catalog?.newestTag()
                    val newestAt = catalogNewest?.let { catalog?.publishedAtOf(it) }
                    val report = VegasLiveCheck.check(body, activeStockTag, catalogNewest, newestAt)
                    liveReport = report
                    vegasHealCatalogTail(context, report, catalog)
                    liveChecking = false
                }
            } catch (_: IllegalStateException) {
                // The page is gone (the scope was cancelled).
            }
        }
    }

    fun commitWrite(isStockPath: Boolean, transform: (String) -> String?) {
        val target = liveFile ?: return
        val effectiveSidecar = resolvedSidecar ?: sidecarPath
        val hadSidecar = sidecarExists
        scope.launch {
            val ok = withContext(Dispatchers.IO) { vegasWriteLive(target, isStockPath, hadSidecar, effectiveSidecar, transform) }
            if (ok) {
                if (isStockPath) stockEdited = true
                touched = true
                toggleVersion++
                onLivePointerChanged(vegasWrittenPath(target, isStockPath, hadSidecar, effectiveSidecar))
            } else toast(activity, L("Failed to update config file"))
        }
    }

    /** The write steps' shared guard: a file in play, and the §6a.6 wrong-schema block (stock only). */
    fun guarded(key: String, write: (Boolean) -> Unit) {
        if (useDefaults || liveFile == null) return
        val isStockPath = selectedStock != null
        if (vegasWrongSchema(isStockPath, activeStockTag, catalog, key)) {
            pendingSchemaBlock = key
            return
        }
        write(isStockPath)
    }
    fun applyToggle(key: String, value: String, enable: Boolean) = guarded(key) { stock -> commitWrite(stock, vegasToggleTransform(key, value, enable)) }
    fun applyValue(key: String, value: String) = guarded(key) { stock -> commitWrite(stock) { text -> VegasKeyKnowledge.setLine(text, key, value) } }
    fun applyDeleteKey(key: String) = guarded(key) { stock -> commitWrite(stock) { text -> VegasKeyKnowledge.removeLine(text, key) } }

    fun deleteBuild() {
        isProcessing = true
        scope.launch {
            try {
                val newVersions = withContext(Dispatchers.IO) { vegasDeleteBuild(context, selectedDxvk) }
                if (newVersions != null) {
                    allVersions = newVersions
                    if (selectedDxvk !in newVersions) selectedDxvk = newVersions.firstOrNull() ?: selectedDxvk
                    toast(activity, L("VEGAS version deleted"))
                } else toast(activity, L("No installed VEGAS version to delete"))
            } catch (e: Exception) {
                toast(activity, L("ERROR: Failed to delete — %1\$s", e.message))
            } finally {
                isProcessing = false
            }
        }
    }

    val pickVegasLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = InAppFilePicker.pickedUri(result.data) ?: return@rememberLauncherForActivityResult
        isProcessing = true
        installContentFromUri(activity, uri) { success ->
            if (success) {
                toast(activity, L("VEGAS version installed"))
                scope.launch {
                    val newVersions = withContext(Dispatchers.IO) { reloadVegasVersions(context, true, isArm64EC) }
                    allVersions = newVersions
                }
            }
            isProcessing = false
        }
    }
    val pickConfigLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val path = InAppFilePicker.pickedPath(result.data) ?: return@rememberLauncherForActivityResult
        selectedCustom = path
        selectedStock = null
        useDefaults = false
        touched = true
        if (path !in customEntries) customEntries = customEntries + path
    }

    fun save() {
        onConfirm(vegasBuildConfig(
            initialConfig, selectedDxvk, selectedFramerate, asyncEnabled, asyncCacheEnabled, dxvkType,
            selectedAnisotropy, selectedLodBias, selectedVkd3d, selectedFeatureLevel, selectedDdraw, selectedD7vk, livePath,
        ))
    }
    fun requestLeave() { if (touched) leaving = true else onDismiss() }
    fun staged(change: () -> Unit) { change(); touched = true }
    fun choose(title: String, options: List<String>, current: String, labels: (String) -> String = { it }, onPick: (String) -> Unit) {
        pick = VegasPick(title, options.map { Opt(it, labels(it)) }, current, onPick = { v -> staged { onPick(v) } })
    }
    fun help(res: Int) {
        val raw = context.getString(res)
        note = "" to HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }
    fun needsFile(run: () -> Unit) {
        if (useDefaults) note = L("Performance tier") to L("Pick a config source first — defaults has no file to write to.")
        else run()
    }

    val saveEntry = MenuEntry("save", L("Save and close"), if (touched) L("Unsaved changes") else null, icon = Icons.Filled.Check, onA = { save() })
    val savePrimary = MenuAction(L("Save"), { save() })

    // ── Build ──
    val verKey = selectedDxvk.removePrefix("vegas-")
    val notesLive = notesCache?.first == verKey
    val hasNotes = notesLive || VegasTierPresets.BUNDLED_NOTES[verKey] != null
    val buildEntries = buildList {
        add(saveEntry)
        add(MenuEntry("loglevel", L("Log level"), context.getString(R.string.vegas_config_loglevel_envvar_note), icon = Icons.Filled.Info,
            onA = { note = L("Log level") to context.getString(R.string.vegas_config_loglevel_envvar_note) }))
        add(MenuEntry(
            "version", L("VEGAS version"),
            if (filteredDxvk.isEmpty()) L("No VEGAS build installed — download one.") else null,
            trailing = selectedDxvk.ifEmpty { "—" }, icon = Icons.Filled.Memory, group = L("VEGAS version"),
            onA = { if (filteredDxvk.isNotEmpty()) choose(L("VEGAS version"), filteredDxvk, selectedDxvk) { selectedDxvk = it } },
        ))
        add(MenuEntry("dl-vegas", L("Download VEGAS builds"), icon = Icons.Filled.CloudDownload, group = L("VEGAS version"), onA = onDownloadDxvk))
        add(MenuEntry("wcp", L("Install from a file (.wcp)"), if (isProcessing) L("Working…") else null, icon = Icons.Filled.FolderOpen, group = L("VEGAS version"),
            onA = { if (!isProcessing) pickVegasLauncher.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.WCP, L("Select VEGAS package"))) }))
        add(MenuEntry("delete-build", L("Delete this VEGAS build"), icon = Icons.Filled.Delete, group = L("VEGAS version"),
            onA = { if (!isProcessing) confirmDeleteBuild = true }))
        if (hasNotes) add(MenuEntry("notes", L("What's new in %1\$s", selectedDxvk), trailing = if (notesLive) L("● live") else L("◐ bundled"),
            icon = Icons.Filled.NewReleases, group = L("VEGAS version"), onA = { openReleaseNotes() }))
        add(MenuEntry("vkd3d", L("VKD3D version"), trailing = selectedVkd3d, icon = Icons.Filled.Memory, group = "VKD3D",
            onA = { choose(L("VKD3D version"), vkd3dVersions, selectedVkd3d) { selectedVkd3d = it } },
            actions = listOf(MenuAction(L("Download VKD3D versions"), onDownloadVkd3d))))
        add(MenuEntry("dl-vkd3d", L("Download VKD3D versions"), icon = Icons.Filled.CloudDownload, group = "VKD3D", onA = onDownloadVkd3d))
        if (dxvkType != DXVKConfigDialog.DXVK_TYPE_NONE) add(MenuEntry("async", L("Async"), toggle = asyncEnabled, group = L("Shaders"), onA = { staged { asyncEnabled = !asyncEnabled } }))
        if (dxvkType == DXVKConfigDialog.DXVK_TYPE_GPLASYNC) add(MenuEntry("asyncCache", L("Async Cache"), toggle = asyncCacheEnabled, group = L("Shaders"), onA = { staged { asyncCacheEnabled = !asyncCacheEnabled } }))
        add(MenuEntry("framerate", L("Frame Rate"), trailing = selectedFramerate, icon = Icons.Filled.Speed, group = L("Shaders"),
            onA = { choose(L("Frame Rate"), framerateEntries, selectedFramerate) { selectedFramerate = it } }))
    }

    // ── Rendering ──
    val texNote = L("DirectX 9-11 games only. Applies the next time the game starts.")
    val renderEntries = buildList {
        add(MenuEntry("aniso", L("Anisotropic filtering"), texNote, trailing = L(selectedAnisotropy), icon = Icons.Filled.Tune, group = L("Texture filtering"),
            onA = { choose(L("Anisotropic filtering"), VEGAS_ANISOTROPY_LABELS, selectedAnisotropy) { selectedAnisotropy = it } },
            actions = listOf(MenuAction(L("What is this?"), { help(R.string.help_anisotropic_filtering) }))))
        add(MenuEntry("lod", L("Texture sharpness"), texNote, trailing = L(selectedLodBias), icon = Icons.Filled.Tune, group = L("Texture filtering"),
            onA = { choose(L("Texture sharpness"), VEGAS_LOD_BIAS_LABELS, selectedLodBias) { selectedLodBias = it } },
            actions = listOf(MenuAction(L("What is this?"), { help(R.string.help_texture_sharpness) }))))
        add(MenuEntry("level", L("API feature level"), trailing = selectedFeatureLevel, group = L("Direct3D"),
            onA = { choose(L("API feature level"), featureLevelEntries, selectedFeatureLevel) { selectedFeatureLevel = it } }))
        add(MenuEntry("ddraw", L("DDraw Wrapper"), trailing = selectedDdraw, group = L("Direct3D"),
            onA = { choose(L("DDraw Wrapper"), ddrawEntries, selectedDdraw) { selectedDdraw = it } }))
        if (StringUtils.parseIdentifier(selectedDdraw) == "d7vk") {
            add(MenuEntry("d7vk", L("D7VK Version"), trailing = selectedD7vk, group = L("Direct3D"),
                onA = { choose(L("D7VK Version"), d7vkVersions, selectedD7vk) { selectedD7vk = it } },
                actions = listOf(MenuAction(L("Download D7VK versions"), onDownloadD7vk))))
            add(MenuEntry("dl-d7vk", L("Download D7VK versions"), icon = Icons.Filled.CloudDownload, group = L("Direct3D"), onA = onDownloadD7vk))
        }
    }

    // ── Performance tier ──
    val effectiveTier: Int? = tierChoice ?: activeForceTier?.let { if (it == 0) null else it }
    val tierEntries = buildList {
        add(MenuEntry(
            "gpu",
            when {
                gpuModel == null -> L("GPU model unreadable — tier is manual")
                detectedTier != null -> L("%1\$s · auto Tier %2\$s", gpuModel, detectedTier)
                else -> L("GPU: %1\$s — no tier suggestion", gpuModel)
            },
            icon = Icons.Filled.Memory,
        ))
        add(MenuEntry("tier-auto", if (detectedTier != null) L("Auto · T%1\$s", detectedTier) else L("Auto"), current = effectiveTier == null,
            group = L("Tier"), onA = { tierChoice = null }))
        VegasTierPresets.TIERS.forEach { t ->
            add(MenuEntry("tier-${t.number}", t.label, t.devices, current = effectiveTier == t.number, group = L("Tier"), onA = { tierChoice = t.number }))
        }
        val staged = tierChoice
        val p = staged?.let { VegasTierPresets.PARAMS[it] }
        if (staged != null && p != null) {
            add(MenuEntry(
                "tier-apply", L("Apply tier"),
                L("Draw threshold %1\$s (D3D9 %2\$s) · HAAE pacing %3\$sms · governor cap %4\$s · shader zero-init %5\$s · frame-gen %6\$s",
                    p.drawThreshold, p.drawThresholdD3D9, p.haaePacing, p.governorCap, p.shaderZeroInit, p.frameGen),
                trailing = "vegas.forceTier = $staged", icon = Icons.Filled.Check, group = L("Apply"),
                onA = { needsFile { applyValue("vegas.forceTier", staged.toString()); tierChoice = null } },
            ))
        } else {
            val applied = activeForceTier
            if (applied != null) add(MenuEntry(
                "tier-reset", L("Reset to auto"),
                if (applied == 0) L("Applied — auto (vegas.forceTier = 0)") else L("Applied — vegas.forceTier = %1\$s", applied),
                icon = Icons.Filled.Restore, group = L("Apply"),
                onA = { needsFile { applyValue("vegas.forceTier", "0"); tierChoice = null } },
            ))
        }
        // Not an Adreno: the experimental Mali driver lives in the driver settings.
        if (gpuModel != null && !gpuModel.contains("adreno", ignoreCase = true)) {
            val mali = L("Pair the Mali 'Wrapper + compat + bcn' driver with the relaxed DXVK list (all DXVK versions) in the container's driver settings.")
            add(MenuEntry("mali", L("Experimental — Mali driver"), mali, icon = Icons.Filled.Info, group = L("Note"),
                onA = { note = L("Experimental — Mali driver") to mali }))
        }
    }

    // ── Config file ──
    val guide = L("New to configs? Grab a Stock config via Download, then pick it below. ") +
        L("Want it your way? Edit anything in a stock config — changes auto-save as your own copy, the original stays untouched. ") +
        L("Prefer managing files yourself? \"Custom config file\" points at any .conf (the built-in way of doing DXVK_CONFIG_FILE). ") +
        L("Heads-up: builds SILENTLY IGNORE keys they don't recognise — if an added key does nothing, it's either not supported by that build or has a typo.")
    val fileEntries = buildList {
        add(MenuEntry("guide", L("How configs work"), icon = Icons.Filled.Info, onA = { note = L("Config") to guide }))
        add(MenuEntry("defaults", L("Use defaults (no config file)"), toggle = useDefaults, onA = { staged { useDefaults = !useDefaults } }))
        if (!useDefaults) {
            val stockNote = when {
                stockSources.isEmpty() -> L("No installed VEGAS package ships a config — install one.")
                selectedStock != null && selectedCustom == null && stockPath != null && !configMissing ->
                    if (sidecarExists) L("Your edits live in your own copy — the stock version stays pristine.")
                    else L("Read-only until the first edit — changes create your own copy.")
                else -> null
            }
            add(MenuEntry(
                "stock", L("Stock config (per version)"), stockNote, trailing = selectedStock ?: "—", icon = Icons.Filled.Description,
                current = selectedStock != null, group = L("Source"),
                onA = {
                    if (stockSources.isNotEmpty()) choose(L("Stock config (per version)"), stockSources.map { it.displayLabel() }, selectedStock ?: "") { s ->
                        selectedStock = s; selectedCustom = null; useDefaults = false
                    }
                },
                actions = listOf(MenuAction(L("Download stock configs"), onOpenConfigDownload)),
            ))
            add(MenuEntry("dl-stock", L("Download stock configs"), icon = Icons.Filled.CloudDownload, group = L("Source"), onA = onOpenConfigDownload))
            add(MenuEntry(
                "custom", L("Custom config file"), selectedCustom, trailing = selectedCustom?.let { File(it).name } ?: "—", icon = Icons.Filled.FolderOpen,
                current = selectedCustom != null, group = L("Source"),
                onA = {
                    pick = VegasPick(
                        L("Custom config file"), customEntries.map { Opt(it, File(it).name) }, selectedCustom ?: "",
                        customLabel = L("Browse for file…"),
                        onCustom = { pick = null; pickConfigLauncher.launch(InAppFilePicker.buildIntent(context, emptyArray(), L("Select config file"))) },
                        onPick = { c -> selectedCustom = c; selectedStock = null; useDefaults = false; touched = true },
                    )
                },
            ))
            if (configMissing) {
                add(MenuEntry(
                    "missing", L("Not found"), livePath, icon = Icons.Filled.Info, group = L("Live file"),
                    onA = if (selectedCustom != null) ({
                        customEntries = customEntries.filter { it != selectedCustom }
                        selectedCustom = null
                        if (selectedStock == null) useDefaults = true
                        touched = true
                    }) else null,
                    trailing = if (selectedCustom != null) L("Remove from list") else null,
                ))
            } else if (livePath.isNotEmpty()) {
                add(MenuEntry("live", L("Live file"), livePath, icon = Icons.Filled.Description, group = L("Live file"),
                    onA = { note = L("Live file") to livePath }))
            }
            if (liveFile != null && !configMissing) {
                add(MenuEntry(
                    "backups", L("Backups"),
                    if (backups.isEmpty()) L("No backups yet — the first edit keeps a copy of the current file.")
                    else L("%1\$s — restore a previously saved state", backups.size),
                    trailing = backups.size.toString(), icon = Icons.Filled.Restore, group = L("Live file"),
                    onA = { showBackups = true },
                    actions = buildList {
                        if (liveFile.isFile) add(MenuAction(L("Backup now"), { manualBackup() }))
                        if (backups.isNotEmpty()) add(MenuAction(L("Restore…"), { showBackups = true }))
                    },
                ))
            }
        }
    }

    // ── Keys ──
    val keysShown = !useDefaults && !configMissing && configText.isNotEmpty()
    val visibleRows = remember(configRows, forkFilter, selectedDxvk, knowledge) {
        configRows.filter { r ->
            if (forkFilter && knowledge?.isForkKey(r.key) != true) false
            else if (forkFilter && knowledge != null && knowledge.isGated(r.key, selectedDxvk)) false
            else true
        }
    }
    fun valueActions(row: VegasKeyKnowledge.EditRow): MenuAction = MenuAction(L("Change value…"), {
        val baseline = baselineRows.firstOrNull { it.key == row.key }
        val opts = linkedSetOf<String>()
        if (row.value.isNotEmpty()) opts.add(row.value)
        stockKeyValues[row.key].orEmpty().forEach { opts.add(it) }
        val options = opts.map { Opt(it, it) } +
            listOfNotNull(baseline?.takeIf { it.value != row.value || it.enabled != row.enabled }?.let { Opt(RESET_TO_STOCK, L("Reset to stock (%1\$s)", it.value)) })
        pick = VegasPick(
            row.key, options, row.value, customLabel = L("Custom value…"),
            onCustom = { pick = null; customValueFor = row },
            onPick = { v -> if (v == RESET_TO_STOCK) baseline?.let { applyValue(row.key, it.value) } else applyValue(row.key, v) },
        )
    })
    val keyEntries = if (!keysShown) emptyList() else buildList {
        add(MenuEntry(
            "fork", L("Fork-feature filter"),
            if (forkFilter) L("Fork features only: %1\$s of %2\$s keys shown",
                configRows.count { row -> knowledge != null && knowledge.isForkKey(row.key) && !knowledge.isGated(row.key, selectedDxvk) }, configRows.size)
            else null,
            toggle = forkFilter,
            onA = { forkFilter = !forkFilter; forkPrefs.edit().putBoolean("forkFilter", forkFilter).apply() },
        ))
        visibleRows.forEach { row ->
            val gated = knowledge != null && knowledge.isGated(row.key, selectedDxvk)
            val badge = vegasBadge(knowledge, catalog, row.key, selectedDxvk, selectedStock, activeStockTag, catalogBehind)
            val changed = selectedStock != null && row.key in changedKeys
            val typo = vegasTypoFor(catalog, row.key, activeStockTag)
            val boolean = vegasIsBooleanKey(stockKeyValues, row.key)
            add(MenuEntry(
                "k:${row.key}", row.key,
                listOfNotNull(if (changed) L("edited") else null, badge, typo?.let { L("Typo? Did you mean %1\$s", it) }).joinToString(" · "),
                trailing = row.value, toggle = row.enabled, group = L("Keys"),
                onA = { applyToggle(row.key, row.value, !row.enabled) },
                actions = buildList {
                    if (!boolean && !gated) add(valueActions(row))
                    if (typo != null) add(MenuAction(L("Fix: use %1\$s", typo), {
                        // As Classic: the right key with the same value, then the typo goes once that write settled.
                        applyValue(typo, row.value)
                        scope.launch { delay(300); applyDeleteKey(row.key) }
                    }))
                    add(MenuAction(L("What is this?"), { note = row.key to vegasKeyDefinition(row.key) }))
                    if (!gated) add(MenuAction(L("Remove key"), { pendingDeleteKey = row.key }, danger = true))
                },
            ))
        }
        if (selectedStock != null) pendingRows.forEach { row ->
            val gated = knowledge != null && knowledge.isGated(row.key, selectedDxvk)
            val boolean = vegasIsBooleanKey(stockKeyValues, row.key)
            add(MenuEntry(
                "p:${row.key}", row.key, L("missing — will be added as %1\$s = %2\$s", row.key, row.value),
                trailing = row.value, icon = Icons.Filled.Add, group = L("New in this baseline"),
                onA = if (gated) null else ({ applyToggle(row.key, row.value, true) }),
                actions = if (gated) emptyList() else buildList {
                    add(MenuAction(L("Add"), { applyToggle(row.key, row.value, true) }))
                    if (!boolean) add(valueActions(row))
                },
            ))
        }
    }

    // ── Catalog and key reference ──
    val knowledgeFooter = if (knowledge != null) L("knowledge: fork %1\$s · %2\$s", knowledge.forkBuild(), knowledge.generated())
        else L("knowledge data unavailable — showing keys unclassified")
    val catalogFooter = when {
        catalog == null -> L("catalog unavailable — classifier off, rows marked unverified")
        selectedCustom != null -> L("catalog: newest %1\$s · %2\$s — classifier applies to stock configs", catalog.newestTag(), catalog.generatedAt())
        activeStockTag == null -> L("catalog: newest %1\$s · %2\$s — no stock source selected", catalog.newestTag(), catalog.generatedAt())
        catalogBehind -> L("catalog behind build — key classes unverified (newest known: %1\$s)", catalog.newestTag())
        else -> L("catalog: covered · newest %1\$s · %2\$s", catalog.newestTag(), catalog.generatedAt())
    }
    val catalogEntries = listOf(
        MenuEntry("knowledge", L("Knowledge"), knowledgeFooter, icon = Icons.Filled.Info),
        MenuEntry("catalog", L("Check catalog"), catalogFooter, icon = Icons.Filled.Book, onA = { showCatalog = true }),
        MenuEntry("live-check", L("Check for new builds"), trailing = if (liveChecking) L("Checking…") else null, icon = Icons.Filled.CloudDownload, onA = { runLiveCheck() }),
    )
    val bucketLabel: (VegasKeyCatalog.Bucket) -> String = { b ->
        when (b) {
            VegasKeyCatalog.Bucket.IN_BUILD -> L("Documented by this build")
            VegasKeyCatalog.Bucket.UPSTREAM -> L("Upstream only")
            VegasKeyCatalog.Bucket.OTHER_BUILD -> L("Other build")
            VegasKeyCatalog.Bucket.NOWHERE -> L("Not in catalog")
        }
    }
    val referenceEntries = remember(catalog, activeStockTag) {
        val keys = vegasReferenceKeys(catalog)
        val grouped = keys.groupBy { key -> catalog?.classify(key, activeStockTag) ?: VegasKeyCatalog.Bucket.NOWHERE }
        listOf(VegasKeyCatalog.Bucket.IN_BUILD, VegasKeyCatalog.Bucket.UPSTREAM, VegasKeyCatalog.Bucket.OTHER_BUILD, VegasKeyCatalog.Bucket.NOWHERE)
            .filter { grouped.containsKey(it) }
            .flatMap { bucket ->
                grouped.getValue(bucket).sorted().map { key ->
                    val fam = catalog?.familyOf(key)?.name ?: "—"
                    MenuEntry("r:$key", key, "[$fam] ${vegasKeyDefinition(key)}", group = bucketLabel(bucket),
                        onA = { note = key to "[$fam] ${vegasKeyDefinition(key)}" })
                }
            }
    }
    val referenceHead = MenuEntry(
        "ref-head",
        if (activeStockTag != null) L("Build: %1\$s", activeStockTag) else L("No build selected"),
        listOfNotNull(L("catalog %1\$s", catalog?.generatedAt() ?: "n/a"), if (catalog == null) L("Catalog offline — provenance hidden, definitions still shown.") else null).joinToString(" · "),
        icon = Icons.Filled.Book,
    )

    val sections = buildList {
        add(MenuSection("build", L("Build"), Icons.Filled.Memory, buildEntries, primary = savePrimary))
        add(MenuSection("render", L("Rendering"), Icons.Filled.Tune, renderEntries, primary = savePrimary))
        add(MenuSection("tier", L("Performance tier"), Icons.Filled.Speed, tierEntries, primary = savePrimary))
        add(MenuSection("file", L("Config file"), Icons.Filled.Description, fileEntries, primary = savePrimary))
        if (keysShown) add(MenuSection("keys", L("Keys"), Icons.Filled.VpnKey, keyEntries, primary = MenuAction(L("Add key"), { showAddKey = true })))
        add(MenuSection("catalog", L("Catalog"), Icons.Filled.Book, catalogEntries, primary = savePrimary))
        add(MenuSection("reference", L("Key reference"), Icons.Filled.Book, listOf(referenceHead) + referenceEntries, primary = savePrimary))
    }
    val banner = listOfNotNull(
        if (isProcessing) L("Working…") else null,
        if (stockEdited && !useDefaults) (if (sidecarExists) L("Edited · yours now — saved to your own copy") else L("Edited · saved to the live config file")) else null,
    ).joinToString("\n").ifEmpty { null }

    LabOverlay(onDismiss = { requestLeave() }) {
        SimpleMenuPage(
            overline = L("Graphics"),
            title = L("VEGAS configuration"),
            sections = sections,
            onBack = { requestLeave() },
            banner = banner,
        )
    }

    // ── Dialogs ──
    pick?.let { p ->
        SimplePicker(
            title = p.title,
            options = p.options,
            current = p.current,
            customLabel = p.customLabel,
            onDismiss = { pick = null },
            onPick = { v -> pick = null; p.onPick(v) },
            onCustom = p.onCustom,
        )
    }
    customValueFor?.let { row ->
        LabFieldDialog(
            title = row.key,
            initial = row.value,
            hint = L("Custom value"),
            confirm = L("Apply"),
            onDismiss = { customValueFor = null },
        ) { v ->
            customValueFor = null
            if (v.isNotBlank()) applyValue(row.key, v.trim())
        }
    }
    if (showAddKey) {
        AddKeyDialog(onDismiss = { showAddKey = false }) { k, v ->
            showAddKey = false
            applyValue(k, v)
        }
    }
    pendingDeleteKey?.let { key ->
        SimpleConfirm(
            title = L("Remove key?"),
            body = L("Are you sure you want '%1\$s' removed? You can get it back via Backup → Restore or Add key.", key),
            danger = true,
            onDismiss = { pendingDeleteKey = null },
        ) {
            pendingDeleteKey = null
            applyDeleteKey(key)
            toast(activity, L("Removed %1\$s — Restore to undo", key))
        }
    }
    if (showBackups) {
        val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT) }
        SimpleActionSheet(
            title = L("Restore a backup"),
            message = if (backups.isEmpty()) L("No backups yet — they appear after the first edit to this config file.") else null,
            actions = buildList {
                if (liveFile?.isFile == true) add(MenuAction(L("Backup now"), { showBackups = false; manualBackup() }))
                backups.forEach { b -> add(MenuAction("${b.name} · ${fmt.format(b.lastModified())}", { showBackups = false; backupFor = b })) }
                add(MenuAction(L("Close"), { showBackups = false }))
            },
            onDismiss = { showBackups = false },
        ) { it.run() }
    }
    backupFor?.let { b ->
        SimpleActionSheet(
            title = b.name,
            actions = listOf(
                MenuAction(L("Restore"), { backupFor = null; restoreTarget = b }),
                MenuAction(L("Delete backup"), { backupFor = null; if (b.delete()) toggleVersion++ }, danger = true),
                MenuAction(L("Cancel"), { backupFor = null }),
            ),
            onDismiss = { backupFor = null },
        ) { it.run() }
    }
    restoreTarget?.let { b ->
        SimpleConfirm(
            title = L("Restore this backup?"),
            body = L("The live config file will be replaced by '%1\$s'. The current state is backed up first — nothing is lost.", b.name),
            danger = false,
            onDismiss = { restoreTarget = null },
        ) { restoreTarget = null; restoreBackup(b) }
    }
    if (confirmDeleteBuild) {
        SimpleConfirm(
            title = L("Delete this VEGAS build"),
            body = L("Remove VEGAS %1\$s from the installed builds?", selectedDxvk),
            danger = true,
            onDismiss = { confirmDeleteBuild = false },
        ) { confirmDeleteBuild = false; deleteBuild() }
    }
    if (showNotes) {
        val notes = notesCache?.takeIf { it.first == verKey }?.second ?: VegasTierPresets.BUNDLED_NOTES[verKey]
        val body = buildList {
            if (notes.isNullOrEmpty()) add(if (notesLoading) L("Fetching notes…") else L("No release notes for this version."))
            else notes.forEach { add("· $it") }
            when (notesSource) {
                "live" -> add("\n" + L("Fetched from the vegas-releases feed."))
                "bundled" -> add("\n" + L("Bundled with the app (offline)."))
            }
        }.joinToString("\n")
        LabNote(title = L("What's new — %1\$s", verKey), body = body, onDismiss = { showNotes = false })
    }
    if (showCatalog) {
        val tags = catalog?.knownTags().orEmpty().joinToString("\n") { t -> "$t — ${catalog?.stateOf(t)?.name?.lowercase()?.replace('_', '-') ?: "?"}" }
        SimpleActionSheet(
            title = L("VEGAS key catalog"),
            message = listOf(
                L("generated %1\$s · upstream %2\$s (%3\$s)", catalog?.generatedAt() ?: "n/a", catalog?.upstreamSource() ?: "n/a", catalog?.upstreamFetchedAt() ?: "n/a"),
                tags,
                L("Updated at build time (assistant-side maintenance)."),
            ).filter { it.isNotBlank() }.joinToString("\n\n"),
            actions = listOf(
                MenuAction(if (liveChecking) L("Checking…") else L("Check for new builds"), { runLiveCheck() }),
                MenuAction(L("OK"), { showCatalog = false }),
            ),
            onDismiss = { showCatalog = false },
        ) { it.run() }
    }
    liveReport?.let { r ->
        val body = if (!r.feedOk) {
            L("Could not reach the release feed (network or API failure).") + "\n\n" +
                L("The bundled catalog is unchanged; keys for unknown builds stay 'unverified'.")
        } else buildList {
            add(L("Catalog newest: %1\$s (%2\$s).", r.catalogNewestTag ?: "?", r.catalogNewestAt.ifEmpty { "?" }))
            if (r.newerCount == 0) add(L("No newer releases found."))
            else {
                add(L("%1\$s newer release(s) found", r.newerCount) +
                    (if (r.newBuildCount > 0) L(" — %1\$s stable", r.newBuildCount) else L(" (all prerelease)")) + ":")
                r.newerTags.forEach { add("· $it") }
            }
            add(if (r.installedFoundLive) L("Your installed build is still listed upstream.") else L("Your installed build is not in the current release list."))
            add(L("This check only reports — no writes, no catalog change. Download and classification still use the existing flows; the catalog asset updates at build time."))
        }.joinToString("\n\n")
        LabNote(title = L("VEGAS new-build check"), body = body, onDismiss = { liveReport = null })
    }
    pendingSchemaBlock?.let { key ->
        LabNote(title = L("Key not applicable to this build's schema"), body = vegasSchemaBlockText(key, catalog, activeStockTag), onDismiss = { pendingSchemaBlock = null })
    }
    note?.let { (title, body) ->
        LabNote(title = title, body = body, onDismiss = { note = null })
    }
    if (leaving) {
        SimpleActionSheet(
            title = L("Save the VEGAS config?"),
            actions = listOf(
                MenuAction(L("Save"), { leaving = false; save() }),
                MenuAction(L("Discard changes"), { leaving = false; onDismiss() }, danger = true),
                MenuAction(L("Keep editing"), { leaving = false }),
            ),
            onDismiss = { leaving = false },
        ) { it.run() }
    }
}

/** (+) Add config entry: a key and its value, appended to the live file (an existing key gets the new value). */
@Composable
private fun AddKeyDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    val keyValid = VegasKeyKnowledge.isValidConfigKey(key.trim())
    val accent = MaterialTheme.colorScheme.primary
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = SimpleStrong,
        unfocusedTextColor = SimpleInk,
        focusedBorderColor = accent,
        unfocusedBorderColor = SimpleLine,
        cursorColor = accent,
    )
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(L("Add config entry"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                singleLine = true,
                placeholder = { Text(L("Key (e.g. dxvk.maxFrameLatency)"), color = SimpleDim) },
                isError = key.isNotBlank() && !keyValid,
                shape = SimpleShape,
                colors = colors,
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
            if (key.isNotBlank() && !keyValid) {
                Text(L("Not a valid config key — use a dotted name or ENV_STYLE caps"), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text(L("Value"), color = SimpleDim) },
                shape = SimpleShape,
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                LabButton(L("Cancel"), onClick = onDismiss)
                LabButton(L("Add"), primary = true, onClick = {
                    if (key.isNotBlank() && value.isNotBlank() && keyValid) onAdd(key.trim(), value.trim())
                })
            }
        }
    }
}
