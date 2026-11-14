package com.winlator.star.ui.simple

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.winlator.star.R
import com.winlator.star.contents.WrapperCatalog
import com.winlator.star.contents.WrapperCatalogDownloader
import com.winlator.star.contents.WrapperCatalogEntry
import com.winlator.star.contents.WrapperManager
import com.winlator.star.contents.WrapperSettingsDictionary
import com.winlator.star.core.StringUtils
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.screens.GpuInfo
import com.winlator.star.ui.screens.applicabilityChip
import com.winlator.star.ui.screens.applicabilityNote
import com.winlator.star.ui.screens.capLabels
import com.winlator.star.ui.screens.editableWrapperKeys
import com.winlator.star.ui.screens.formatSize
import com.winlator.star.ui.screens.gpuApplicability
import com.winlator.star.ui.screens.importDescription
import com.winlator.star.ui.screens.importNeeds
import com.winlator.star.ui.screens.probeWrapperGpu
import com.winlator.star.ui.screens.wrapperImportBaseName
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Containers, wrappers, graphics tools and downloads (Lab). */
@Suppress("UNUSED_PARAMETER")
internal fun NavGraphBuilder.labContainerRoutes(open: (String) -> Unit, back: () -> Unit) {
    composable(LabParityRoutes.WRAPPERS) { LabWrapperManagerPage(onBack = back) }
}

/** The wrapper manager over a settings editor (Graphics › "Manage graphics wrappers"): the same Lab page, full screen. */
@Composable
internal fun LabWrapperManagerDialog(onDismiss: () -> Unit) {
    LabOverlay(onDismiss) { LabWrapperManagerPage(onBack = onDismiss) }
}

/** A wrapper whose details sheet is open: a bundled slot or an imported one. */
private sealed interface WrapperTarget {
    class Slot(val slot: WrapperManager.WrapperSlot) : WrapperTarget
    class Imported(val imported: WrapperManager.Imported) : WrapperTarget
}

/**
 * Classic's wrapper manager (WrapperManagerScreen) as a Lab page: the built-in slots (Update from a file or the catalog,
 * Reset, Reset all, catalog update badges), the imported wrappers (Import with inspection and a name, Update, Edit
 * settings, Delete) and the curated catalog (Download). Same WrapperManager / WrapperCatalog calls, toasts and confirms.
 */
@Composable
internal fun LabWrapperManagerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    // WrapperManager needs only a Context (never cast it: in the editors this page sits in a dialog).
    val manager = remember { WrapperManager(context) }
    val gpu = remember { probeWrapperGpu() }
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    fun str(id: Int) = context.getString(id)

    var slots by remember { mutableStateOf(manager.listSlots().toList()) }
    var imported by remember { mutableStateOf(manager.enumerateImported().toList()) }
    // The catalog (network first, then the cache), read once: the update badges and the catalog section.
    var catalog by remember { mutableStateOf<List<WrapperCatalogEntry>>(emptyList()) }
    var catalogSource by remember { mutableStateOf(WrapperCatalog.Source.NONE) }
    var catalogLoading by remember { mutableStateOf(true) }
    // Catalog ids on the device (imports and slot overrides) with their versions; plus the ones added this visit.
    var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installedVersions by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var sessionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // A slot or imported wrapper being updated from the catalog, and its 0..100 progress (Classic's manager).
    var updatingId by remember { mutableStateOf<String?>(null) }
    var updateProgress by remember { mutableIntStateOf(0) }
    // A catalog download (Classic's catalog picker): one at a time.
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableIntStateOf(0) }

    // Dialogs and their steps (as Classic's body).
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    var importMode by remember { mutableStateOf(false) }
    var pickPrompt by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingInspection by remember { mutableStateOf<WrapperManager.Inspection?>(null) }
    var importNameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<WrapperManager.Imported?>(null) }
    var editTarget by remember { mutableStateOf<WrapperManager.Imported?>(null) }
    var chooserSlot by remember { mutableStateOf<WrapperManager.WrapperSlot?>(null) }
    var catalogSlot by remember { mutableStateOf<WrapperManager.WrapperSlot?>(null) }
    var detailsFor by remember { mutableStateOf<WrapperTarget?>(null) }
    var envFor by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var catalogInfo by remember { mutableStateOf<WrapperCatalogEntry?>(null) }

    suspend fun reloadInstalled() {
        val ids = withContext(Dispatchers.IO) { runCatching { manager.installedCatalogIds() }.getOrDefault(emptySet()) }
        installedIds = ids
        installedVersions = withContext(Dispatchers.IO) {
            runCatching { ids.associateWith { manager.installedCatalogVersion(it) } }.getOrDefault(emptyMap())
        }
    }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { WrapperCatalog.loadCached(context) }
        catalog = result.entries
        catalogSource = result.source
        reloadInstalled()
        catalogLoading = false
    }

    fun refresh() {
        slots = manager.listSlots().toList()
        imported = manager.enumerateImported().toList()
        scope.launch { reloadInstalled() }
    }

    // Re-download an imported wrapper's catalog entry in place (its catalogId makes importWrapper update it).
    fun startUpdate(identifier: String, entry: WrapperCatalogEntry) {
        updatingId = identifier
        updateProgress = 0
        scope.launch {
            val ok = WrapperCatalogDownloader.install(context, entry) { pct -> activity?.runOnUiThread { updateProgress = pct } } != null
            updatingId = null
            if (ok) { toast(context, str(R.string.wrapper_updated_toast)); refresh() } else toast(context, str(R.string.wrapper_invalid_toast))
        }
    }

    // One-tap update of a built-in slot to the newer catalog build (installed as the slot's override).
    fun startSlotUpdate(slot: WrapperManager.WrapperSlot, entry: WrapperCatalogEntry) {
        updatingId = slot.fileName
        updateProgress = 0
        scope.launch {
            val ok = WrapperCatalogDownloader.installToSlot(context, entry, slot.fileName) { pct -> activity?.runOnUiThread { updateProgress = pct } }
            updatingId = null
            if (ok) { toast(context, str(R.string.wrapper_updated_toast)); refresh() } else toast(context, str(R.string.wrapper_invalid_toast))
        }
    }

    // A catalog download: a free-form import, or (with [targetSlot]) that slot's override.
    fun startDownload(entry: WrapperCatalogEntry, targetSlot: String?) {
        if (downloadingId != null) return
        downloadingId = entry.id
        downloadProgress = 0
        scope.launch {
            val ok = if (targetSlot != null) {
                WrapperCatalogDownloader.installToSlot(context, entry, targetSlot) { pct -> activity?.runOnUiThread { downloadProgress = pct } }
            } else {
                WrapperCatalogDownloader.install(context, entry) { pct -> activity?.runOnUiThread { downloadProgress = pct } } != null
            }
            downloadingId = null
            if (ok) {
                sessionIds = sessionIds + entry.id
                toast(context, context.getString(R.string.wrapper_catalog_download_ok, entry.name))
                refresh()
            } else toast(context, context.getString(R.string.wrapper_catalog_download_failed, entry.name))
        }
    }

    // One file picker for a slot update and an import (importMode tells them apart), as Classic.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pendingFileName
        val wasImport = importMode
        pendingFileName = null
        importMode = false
        if (result.resultCode == Activity.RESULT_OK) {
            val path = InAppFilePicker.pickedPath(result.data)
            val uri = result.data?.data ?: path?.let { InAppFilePicker.asUri(it) }
            if (uri != null) {
                if (wasImport) {
                    // Inspect first, install nothing: an invalid file stops here; a valid one opens the inspection and name.
                    val inspection = manager.inspectUri(uri)
                    if (!inspection.valid) toast(context, str(R.string.wrapper_invalid_toast))
                    else {
                        importNameDraft = wrapperImportBaseName(path, uri)
                        pendingInspection = inspection
                        pendingImportUri = uri
                    }
                } else if (target != null) {
                    if (manager.installOverride(target, uri)) { toast(context, str(R.string.wrapper_updated_toast)); refresh() }
                    else toast(context, str(R.string.wrapper_invalid_toast))
                }
            }
        }
    }

    // Provenance per slot / import (file reads), read again whenever the lists or the catalog change.
    val slotUpdates = remember(slots, catalog) {
        slots.associate { slot ->
            val catId = if (slot.isOverridden) manager.slotCatalogId(slot.fileName) else manager.bundledCatalogId(slot.fileName)
            val matched = catId?.let { id -> catalog.firstOrNull { it.id == id } }
            val installedVer = if (slot.isOverridden) manager.slotCatalogVersion(slot.fileName) else manager.bundledCatalogVersion(slot.fileName)
            slot.fileName to matched?.takeIf { it.version > installedVer }
        }
    }
    val importInfo = remember(imported, catalog) {
        imported.associate { imp ->
            val catId = manager.catalogIdFor(imp.identifier)
            val matched = catId?.let { id -> catalog.firstOrNull { it.id == id } }
            imp.identifier to Pair(catId != null, matched?.takeIf { it.version > manager.catalogVersionFor(imp.identifier) })
        }
    }

    fun slotActions(slot: WrapperManager.WrapperSlot, withDetails: Boolean): List<MenuAction> = buildList {
        val newer = slotUpdates[slot.fileName]
        if (newer != null && updatingId != slot.fileName) add(MenuAction(str(R.string.wrapper_update_from_catalog), { startSlotUpdate(slot, newer) }))
        add(MenuAction(str(R.string.wrapper_update) + "…", { chooserSlot = slot }))
        if (slot.isOverridden) add(MenuAction(str(R.string.wrapper_reset), {
            manager.removeOverride(slot.fileName)
            toast(context, str(R.string.wrapper_reset_toast))
            refresh()
        }))
        if (withDetails) add(MenuAction(str(R.string.wrapper_details), { detailsFor = WrapperTarget.Slot(slot) }))
        else add(MenuAction(L("Environment variables"), { envFor = slot.fileName to false }))
    }
    fun importActions(imp: WrapperManager.Imported, withDetails: Boolean): List<MenuAction> = buildList {
        val newer = importInfo[imp.identifier]?.second
        if (newer != null && updatingId != imp.identifier) add(MenuAction(str(R.string.wrapper_update), { startUpdate(imp.identifier, newer) }))
        add(MenuAction(str(R.string.wrapper_edit_settings), { editTarget = imp }))
        add(MenuAction(str(R.string.wrapper_delete), { deleteTarget = imp }, danger = true))
        if (withDetails) add(MenuAction(str(R.string.wrapper_details), { detailsFor = WrapperTarget.Imported(imp) }))
        else add(MenuAction(L("Environment variables"), { envFor = imp.identifier to true }))
    }

    val slotEntries = slots.map { slot ->
        val state = str(if (slot.isOverridden) R.string.wrapper_updated_label else R.string.wrapper_bundled)
        val updating = updatingId == slot.fileName
        MenuEntry(
            key = "s:${slot.fileName}",
            title = slot.label,
            subtitle = "${slot.fileName} · " + L("Version") + ": ${slot.version} ($state)",
            icon = Icons.Filled.Layers,
            pills = listOfNotNull(
                if (updating) context.getString(R.string.wrapper_updating_progress, updateProgress)
                else if (slotUpdates[slot.fileName] != null) str(R.string.wrapper_update_available) else null,
            ),
            progress = if (updating) updateProgress / 100f else null,
            onA = { detailsFor = WrapperTarget.Slot(slot) },
            actions = slotActions(slot, withDetails = true),
        )
    }
    val importEntries = imported.map { imp ->
        val (fromCatalog, newer) = importInfo[imp.identifier] ?: Pair(false, null)
        val updating = updatingId == imp.identifier
        MenuEntry(
            key = "i:${imp.identifier}",
            title = imp.label,
            subtitle = "${imp.identifier}.tzst · " + str(R.string.wrapper_imported_label),
            icon = Icons.Filled.Layers,
            pills = listOfNotNull(
                when {
                    updating -> context.getString(R.string.wrapper_updating_progress, updateProgress)
                    newer != null -> str(R.string.wrapper_update_available)
                    fromCatalog -> str(R.string.wrapper_from_catalog)
                    else -> null
                },
            ),
            progress = if (updating) updateProgress / 100f else null,
            onA = { detailsFor = WrapperTarget.Imported(imp) },
            actions = importActions(imp, withDetails = true),
        )
    }
    // Only a live network load can download (asset links may have moved since the cache was written).
    val offline = catalogSource != WrapperCatalog.Source.NETWORK
    fun catalogState(entry: WrapperCatalogEntry): Triple<Boolean, Boolean, Boolean> {
        val durable = entry.id in installedIds
        val installed = durable || entry.id in sessionIds
        val newer = durable && entry.version > (installedVersions[entry.id] ?: 0)
        // installed, update available, can download now
        return Triple(installed, newer, downloadingId == null && !offline && (newer || !installed))
    }
    val catalogEntries = catalog.map { entry ->
        val (installed, newer, can) = catalogState(entry)
        val busy = downloadingId == entry.id
        MenuEntry(
            key = "c:${entry.id}",
            title = entry.name,
            subtitle = catalogSubtitle(entry),
            trailing = when {
                busy -> "$downloadProgress%"
                installed && !newer -> str(R.string.wrapper_installed)
                else -> null
            },
            icon = Icons.Filled.CloudDownload,
            pills = listOfNotNull(
                if (newer) str(R.string.wrapper_update_available) else null,
                applicabilityChip(entry.gpuTargets, gpu.isQualcomm),
            ),
            progress = if (busy) downloadProgress / 100f else null,
            onA = { catalogInfo = entry },
            play = if (can) ({ startDownload(entry, null) }) else null,
            playIcon = Icons.Filled.CloudDownload,
            playLabel = str(R.string.wrapper_catalog_download),
            actions = listOfNotNull(
                if (can) MenuAction(str(R.string.wrapper_catalog_download), { startDownload(entry, null) }) else null,
                MenuAction(str(R.string.wrapper_details), { catalogInfo = entry }),
            ),
        )
    }

    SimpleMenuPage(
        overline = L("Graphics"),
        title = str(R.string.wrapper_manager_title),
        sections = listOf(
            MenuSection(
                "slots", L("Built-in"), Icons.Filled.Layers, slotEntries,
                primary = MenuAction(str(R.string.wrapper_reset_all), { confirmResetAll = true }),
            ),
            MenuSection(
                "imported", str(R.string.wrapper_imported_label), Icons.Filled.LibraryAdd, importEntries,
                empty = str(R.string.wrapper_import_subtitle),
                primary = MenuAction(str(R.string.wrapper_import_title), { importMode = true; pickPrompt = true }),
            ),
            MenuSection(
                "catalog", str(R.string.wrapper_catalog_dialog_title), Icons.Filled.CloudDownload, catalogEntries,
                empty = if (catalogLoading) L("Loading the list…") else str(R.string.wrapper_catalog_empty),
            ),
        ),
        onBack = onBack,
        banner = listOfNotNull(
            str(R.string.wrapper_manager_header),
            if (offline && !catalogLoading) str(if (catalogSource == WrapperCatalog.Source.CACHE) R.string.wrapper_catalog_offline_cache else R.string.wrapper_catalog_offline) else null,
        ).joinToString("\n"),
    )

    // ── The steps over the page ──

    when (val t = detailsFor) {
        null -> {}
        is WrapperTarget.Slot -> {
            val slot = slots.firstOrNull { it.fileName == t.slot.fileName } ?: t.slot
            val state = str(if (slot.isOverridden) R.string.wrapper_updated_label else R.string.wrapper_bundled)
            val caps = remember(slot.fileName) { manager.capsFor(slot.fileName.removeSuffix(".tzst")) }
            SimpleActionSheet(
                title = slot.label,
                message = wrapperDetails(slot.fileName, state, slot.version, slot.notes, caps, gpu),
                actions = slotActions(slot, withDetails = false),
                onDismiss = { detailsFor = null },
            ) { a -> detailsFor = null; a.run() }
        }
        is WrapperTarget.Imported -> {
            val imp = t.imported
            val info by produceState<Pair<WrapperManager.WrapperCaps, List<String>>?>(null, imp.identifier) {
                value = withContext(Dispatchers.IO) {
                    runCatching { manager.capsFor(imp.identifier) to manager.readVersionInfo(manager.importedFileFor(imp.identifier)).toList() }.getOrNull()
                }
            }
            val state = str(R.string.wrapper_imported_label)
            SimpleActionSheet(
                title = imp.label,
                message = info?.let { (caps, v) -> wrapperDetails("${imp.identifier}.tzst", state, v.getOrElse(0) { "" }, v.getOrElse(1) { "" }, caps, gpu) } ?: L("Loading…"),
                actions = importActions(imp, withDetails = false),
                onDismiss = { detailsFor = null },
            ) { a -> detailsFor = null; a.run() }
        }
    }

    envFor?.let { (id, isImported) ->
        val keys by produceState<List<String>?>(null, id) {
            value = withContext(Dispatchers.IO) {
                runCatching { if (isImported) manager.detectedEnvKeys(id) else manager.scanBundledEnvKeys(id) }.getOrDefault(emptyList())
            }
        }
        val list = keys
        androidx.compose.runtime.key(list?.size ?: -1) {
            SimpleActionSheet(
                title = list?.let { L("Environment variables (%1\$s)", it.size) } ?: L("Environment variables"),
                message = when {
                    list == null -> L("Scanning…")
                    list.isEmpty() -> L("No environment variables detected.")
                    else -> null
                },
                actions = list.orEmpty().map { k ->
                    val label = WrapperSettingsDictionary.defFor(k).label
                    MenuAction(if (label != k) "$label · $k" else k, {})
                },
                onDismiss = { envFor = null },
            ) { it.run() }
        }
    }

    catalogInfo?.let { entry ->
        val (installed, _, can) = catalogState(entry)
        val busy = downloadingId == entry.id
        SimpleActionSheet(
            title = entry.name,
            message = buildList {
                if (entry.description.isNotBlank()) add(entry.description)
                if (entry.license.isNotBlank()) add(L("License") + ": " + entry.license)
                formatSize(entry.fileSize)?.let { add(L("Size") + ": " + it) }
                if (entry.gpuTargets.isNotEmpty()) add(L("GPU targets") + ": " + entry.gpuTargets.joinToString(", "))
                applicabilityNote(entry.gpuTargets, gpu.isQualcomm, gpu.model)?.let { add(it) }
                if (offline && !installed && !busy) add(str(R.string.wrapper_catalog_needs_connection))
                if (busy) add(str(if (downloadProgress >= 100) R.string.wrapper_catalog_installing else R.string.wrapper_catalog_downloading) + " $downloadProgress%")
            }.joinToString("\n"),
            actions = listOfNotNull(
                if (can) MenuAction(str(R.string.wrapper_catalog_download), { startDownload(entry, null) }) else null,
                MenuAction(str(R.string.wrapper_catalog_close), { catalogInfo = null }),
            ),
            onDismiss = { catalogInfo = null },
        ) { it.run() }
    }

    // A built-in slot's Update: from a file or from the catalog.
    chooserSlot?.let { slot ->
        SimpleActionSheet(
            title = str(R.string.wrapper_update),
            message = str(R.string.wrapper_manager_header),
            actions = listOf(
                MenuAction(str(R.string.wrapper_update_from_file), {
                    chooserSlot = null
                    pendingFileName = slot.fileName
                    importMode = false
                    pickPrompt = true
                }),
                MenuAction(str(R.string.wrapper_update_from_catalog), { chooserSlot = null; catalogSlot = slot }),
            ),
            onDismiss = { chooserSlot = null },
        ) { it.run() }
    }

    // The catalog in "replace this slot" mode: a download becomes the slot's override (a wrapper that doesn't match is rejected).
    catalogSlot?.let { slot ->
        SimpleActionSheet(
            title = L("Replace %1\$s from catalog", slot.label),
            message = listOfNotNull(
                L("Downloading a wrapper installs it as this slot's override. A wrapper that doesn't match the slot is rejected."),
                if (offline && !catalogLoading) str(if (catalogSource == WrapperCatalog.Source.CACHE) R.string.wrapper_catalog_offline_cache else R.string.wrapper_catalog_offline) else null,
                when {
                    catalogLoading -> L("Loading the list…")
                    catalog.isEmpty() -> str(R.string.wrapper_catalog_empty)
                    else -> null
                },
            ).joinToString("\n\n"),
            actions = catalog.map { entry ->
                val (installed, newer, can) = catalogState(entry)
                val note = when {
                    downloadingId == entry.id -> "$downloadProgress%"
                    newer -> str(R.string.wrapper_update_available)
                    installed -> str(R.string.wrapper_installed)
                    else -> null
                }
                val label = listOfNotNull(entry.name, catalogSubtitle(entry).takeIf { it.isNotBlank() }, note, applicabilityChip(entry.gpuTargets, gpu.isQualcomm)).joinToString(" · ")
                MenuAction(label, { if (can) startDownload(entry, slot.fileName) })
            },
            onDismiss = { catalogSlot = null },
        ) { it.run() }
    }

    // Pick a file: the built-in picker or the system one; shared by a slot update and an import.
    if (pickPrompt) {
        fun cancel() { pickPrompt = false; pendingFileName = null; importMode = false }
        SimpleActionSheet(
            title = if (importMode) str(R.string.wrapper_import_title).removeSuffix("…") else str(R.string.wrapper_update),
            message = str(R.string.wrapper_manager_header),
            actions = listOf(
                MenuAction(L("Browse files"), {
                    pickPrompt = false
                    filePicker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.WRAPPER, str(R.string.wrapper_select_title)))
                }),
                MenuAction(L("Pick via system…"), {
                    pickPrompt = false
                    filePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" })
                }),
                MenuAction(L("Cancel"), { cancel() }),
            ),
            onDismiss = { cancel() },
        ) { it.run() }
    }

    // The inspection of a picked import and its name.
    val importUri = pendingImportUri
    val inspection = pendingInspection
    if (importUri != null && inspection != null) {
        fun closeInspect() { pendingImportUri = null; pendingInspection = null }
        LabWrapperImportDialog(inspection, gpu, importNameDraft, onDismiss = { closeInspect() }) { draft ->
            val name = draft.trim()
            val id = StringUtils.parseIdentifier(name)
            when {
                name.isEmpty() -> toast(context, str(R.string.wrapper_import_name_empty_toast))
                manager.isReservedIdentifier(id) -> toast(context, str(R.string.wrapper_import_reserved_toast))
                else -> {
                    closeInspect()
                    if (manager.importWrapper(importUri, name) != null) { toast(context, str(R.string.wrapper_imported_toast)); refresh() }
                    else toast(context, str(R.string.wrapper_invalid_toast))
                }
            }
        }
    }

    deleteTarget?.let { imp ->
        SimpleActionSheet(
            title = str(R.string.wrapper_delete_confirm_title),
            message = str(R.string.wrapper_delete_confirm_message),
            actions = listOf(
                MenuAction(str(R.string.wrapper_delete), {
                    manager.deleteImported(imp.identifier)
                    deleteTarget = null
                    toast(context, str(R.string.wrapper_reset_toast))
                    refresh()
                }, danger = true),
                MenuAction(L("Cancel"), { deleteTarget = null }),
            ),
            onDismiss = { deleteTarget = null },
        ) { it.run() }
    }

    editTarget?.let { imp -> LabEditWrapperSettingsDialog(manager, imp, onDismiss = { editTarget = null }) }

    if (confirmResetAll) {
        SimpleActionSheet(
            title = str(R.string.wrapper_reset_all_confirm_title),
            message = str(R.string.wrapper_reset_all_confirm_message),
            actions = listOf(
                MenuAction(str(R.string.wrapper_reset_all), {
                    manager.resetAll()
                    confirmResetAll = false
                    toast(context, str(R.string.wrapper_reset_toast))
                    refresh()
                }, danger = true),
                MenuAction(L("Cancel"), { confirmResetAll = false }),
            ),
            onDismiss = { confirmResetAll = false },
        ) { it.run() }
    }
}

/** A catalog entry's author · size (its license when it has neither), as Classic's card. */
private fun catalogSubtitle(entry: WrapperCatalogEntry): String = buildString {
    if (entry.author.isNotBlank()) append(entry.author)
    val sz = formatSize(entry.fileSize)
    if (sz != null) { if (isNotEmpty()) append(" · "); append(sz) }
}.ifBlank { entry.license }

/** Classic's detail box as lines: file, state, version, notes, detected capabilities and whether they apply to this GPU. */
private fun wrapperDetails(fileName: String, state: String, version: String, notes: String, caps: WrapperManager.WrapperCaps, gpu: GpuInfo): String =
    buildList {
        add(L("File") + ": " + fileName)
        add(L("State") + ": " + state)
        add(L("Version") + ": " + version)
        if (notes.isNotBlank()) add(L("Notes") + ": " + notes)
        capLabels(caps).takeIf { it.isNotEmpty() }?.let { add(L("Detected capabilities") + ": " + it.joinToString(", ")) }
        gpuApplicability(caps, gpu)?.let { add(it) }
    }.joinToString("\n")

/** A focusable text area the D-pad scrolls (Up / Down) before the focus moves on; touch scrolls it as usual. */
@Composable
private fun Modifier.labScrollArea(state: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { ev ->
            val k = ev.nativeKeyEvent
            if (k.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            when (k.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> state.canScrollForward.also { if (it) scope.launch { state.animateScrollBy(160f) } }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> state.canScrollBackward.also { if (it) scope.launch { state.animateScrollBy(-160f) } }
                else -> false
            }
        }
        .focusable()
        .then(if (focused) Modifier.border(1.dp, accent, SimpleShape) else Modifier)
}

/**
 * Classic's import inspection in a Lab panel: what the picked wrapper is, its version and detected capabilities, what
 * it adds and needs, the settings found in it, then its name. [onAdd] gets the name (the caller checks it and closes).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabWrapperImportDialog(
    inspection: WrapperManager.Inspection,
    gpu: GpuInfo,
    initialName: String,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialName) }
    val ok = remember { FocusRequester() }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { ok.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 600.dp)
                .heightIn(max = 640.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(context.getString(R.string.wrapper_import_title).removeSuffix("…"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().labScrollArea(scroll).verticalScroll(scroll).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                InfoLine(L("What it is"), importDescription(inspection.caps))
                InfoLine(L("Version"), if (inspection.notes.isNotBlank()) "${inspection.version} · ${inspection.notes}" else inspection.version)
                val labels = capLabels(inspection.caps)
                if (labels.isNotEmpty()) {
                    Heading(L("Detected capabilities"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { labels.forEach { SimpleChip(it) } }
                }
                Heading(L("What will be added"))
                importNeeds(inspection.caps, gpu, inspection.version).forEach { Text("• $it", color = SimpleInk, fontSize = 13.sp) }
                if (inspection.envKeys.isNotEmpty()) {
                    Heading(L("Detected settings (%1\$s)", inspection.envKeys.size))
                    Text(inspection.envKeys.joinToString(", "), color = SimpleMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text(context.getString(R.string.wrapper_import_name_message), color = SimpleMuted, fontSize = 13.sp)
            val accent = MaterialTheme.colorScheme.primary
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                shape = SimpleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SimpleStrong,
                    unfocusedTextColor = SimpleInk,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = SimpleLine,
                    cursorColor = accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                LabButton(L("Cancel"), onClick = onDismiss)
                LabButton(L("OK"), primary = true, requester = ok, onClick = { onAdd(name) })
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = SimpleMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", color = SimpleMuted, fontSize = 13.sp)
        Text(value, color = SimpleStrong, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * Classic's "Edit detected settings" in a Lab panel: each detected setting of an imported wrapper that can be a
 * setting, on (shown) or off (hidden). OK stores the hidden ones in the wrapper's .meta; Cancel or B leaves them.
 */
@Composable
private fun LabEditWrapperSettingsDialog(manager: WrapperManager, imported: WrapperManager.Imported, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var keys by remember(imported.identifier) { mutableStateOf<List<String>?>(null) }
    val hidden = remember(imported.identifier) { mutableStateListOf<String>() }
    val first = remember { FocusRequester() }
    val ok = remember { FocusRequester() }
    LaunchedEffect(imported.identifier) {
        val eligible = withContext(Dispatchers.IO) { runCatching { editableWrapperKeys(manager, imported.identifier) }.getOrDefault(emptyList()) }
        val stored = withContext(Dispatchers.IO) { runCatching { manager.hiddenKeys(imported.identifier) }.getOrDefault(emptySet()) }
        hidden.clear()
        hidden.addAll(stored.filter { it in eligible })
        keys = eligible
        delay(60)
        runCatching { if (eligible.isNotEmpty()) first.requestFocus() else ok.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 600.dp)
                .heightIn(max = 640.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(context.getString(R.string.wrapper_edit_settings_title), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(context.getString(R.string.wrapper_edit_settings_message), color = SimpleMuted, fontSize = 13.sp)
            val list = keys
            when {
                list == null -> Text(L("Scanning…"), color = SimpleMuted, fontSize = 13.sp)
                list.isEmpty() -> Text(context.getString(R.string.wrapper_edit_settings_empty), color = SimpleMuted, fontSize = 13.sp)
                else -> Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    list.forEachIndexed { i, key ->
                        val shown = key !in hidden
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .labTile(onClick = { if (shown) hidden.add(key) else hidden.remove(key) }, requester = if (i == 0) first else null)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(WrapperSettingsDictionary.defFor(key).label, color = SimpleStrong, fontSize = 14.sp)
                                Text(key, color = SimpleDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.width(10.dp))
                            LabSwitch(shown)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                LabButton(L("Cancel"), onClick = onDismiss)
                LabButton(L("OK"), primary = true, requester = ok, onClick = {
                    manager.setHiddenKeys(imported.identifier, hidden.toSet())
                    onDismiss()
                })
            }
        }
    }
}
