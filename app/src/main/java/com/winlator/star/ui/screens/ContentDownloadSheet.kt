package com.winlator.star.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.core.TarCompressorUtils
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.ContentDownloadState
import com.winlator.star.store.download.formatEta
import com.winlator.star.store.download.startContentDownload
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.screens.contents.RemoteSourceRepository
import kotlin.math.absoluteValue
import com.winlator.star.util.ImportEtaTracker
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Adrenotools-style download menu for the given content type(s).
 * Works on any screen (including inside other dialogs).
 * After install/remove, calls [onContentChanged] so the parent can refresh version lists.
 * [inUseKey] (optional) marks the version the container currently uses (matched best-effort).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentDownloadSheet(
    contentTypes: List<ContentProfile.ContentType>,
    onDismiss: () -> Unit,
    onContentChanged: () -> Unit,
    inUseKey: String? = null,
) {
    val context = LocalContext.current
    val cm = remember { ContentsManager(context) }

    var profiles by remember { mutableStateOf<List<ContentProfile>>(emptyList()) }
    // Component download/install state now lives on a PROCESS-lifetime registry (see
    // ContentDownloadController) rather than composition-scoped state, so it keeps advancing while
    // the app is backgrounded and the sheet re-attaches to an in-flight download on reopen.
    val contentStates by ContentDownloadRegistry.states.collectAsState()
    // Which in-flight catalog download the progress card is showing. Seeded on tap; re-attached to
    // any still-running download when the sheet (re)enters composition (see LaunchedEffect below).
    var dialogKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(contentStates.keys) {
        val current = dialogKey
        if (current == null || current !in contentStates) {
            dialogKey = contentStates.entries.firstOrNull { !it.value.terminal }?.key
        }
    }
    // The content-card install dialog for LOCAL-FILE import (catalog downloads render from the
    // registry via dialogKey instead) — null when idle.
    var installDialog by remember { mutableStateOf<InstallCardState?>(null) }
    var showInfoProfile by remember { mutableStateOf<ContentProfile?>(null) }
    var confirmRemoveProfile by remember { mutableStateOf<ContentProfile?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isLoadingRemote by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    // When more than one content type is shown (Wine + Proton), chips at the top filter the list.
    var selectedType by remember(contentTypes) { mutableStateOf(contentTypes.first()) }

    // ── Community-repo source toggle (opt-in; persisted globally). OFF = Official contents.json only.
    val srcPrefs = remember { context.getSharedPreferences(COMMUNITY_PREFS, Context.MODE_PRIVATE) }
    var includeCommunity by remember { mutableStateOf(srcPrefs.getBoolean(COMMUNITY_KEY, false)) }
    var communityGroups by remember { mutableStateOf<List<CommunityGroup>>(emptyList()) }
    var communityLoading by remember { mutableStateOf(false) }
    // Which groups are shown via the chip row. Official is always available and can't be emptied out.
    var activeSources by remember { mutableStateOf(setOf(OFFICIAL_KEY)) }
    var selectAllOnLoad by remember { mutableStateOf(includeCommunity) }

    LaunchedEffect(contentTypes, refreshKey) {
        val json = withContext(Dispatchers.IO) {
            Downloader.downloadString(ContentsManager.REMOTE_PROFILES)
        }
        if (json != null) cm.setRemoteProfiles(json) else cm.syncContents()
        loadProfiles(cm, contentTypes) { profiles = it }
        isLoadingRemote = false
    }

    // Fetch community versions for the ACTIVE type from the Contents-screen COMPONENT sources — only
    // when the toggle is on (network-heavy), lazily and per type. Driver sources are never consulted.
    LaunchedEffect(includeCommunity, selectedType, refreshKey) {
        if (!includeCommunity) {
            communityGroups = emptyList(); communityLoading = false
            return@LaunchedEffect
        }
        communityLoading = true
        val typeStr = selectedType.toString()
        val groups = withContext(Dispatchers.IO) {
            val repo = RemoteSourceRepository(context)
            repo.getAllSources()
                .filter { sourceSupportsType(it, typeStr) }
                .mapNotNull { s ->
                    val items = runCatching { repo.fetchFromSource(s, typeStr) }.getOrDefault(emptyList())
                        .map { it.toProfile(selectedType) }
                        .distinctBy { it.remoteUrl }
                    if (items.isEmpty()) null else CommunityGroup(s.name, items)
                }
        }
        communityGroups = groups
        if (selectAllOnLoad) {
            activeSources = setOf(OFFICIAL_KEY) + groups.map { it.name }
            selectAllOnLoad = false
        }
        communityLoading = false
    }

    // Manual "install from file" picker. Handles both the in-app picker (selectedFile path,
    // wrapped as a file:// Uri) and the system SAF picker (result.data.data).
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        (result.data?.data ?: InAppFilePicker.pickedUri(result.data))?.let { uri ->
            // Version/desc aren't known until the archive is parsed — seed the card with the filename +
            // (single-type screens) the content type, then let the % bar carry the rest.
            val fname = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "Content file"
            installDialog = InstallCardState(
                title = fname,
                type = contentTypes.singleOrNull()?.toString(),
                phase = InstallCardPhase.INSTALLING,
            )
            installContent(context, cm, uri, onProgress = { f, _ ->
                installDialog = installDialog?.copy(fraction = f, phase = InstallCardPhase.INSTALLING)
            }) { ok ->
                if (ok) {
                    installDialog = installDialog?.copy(fraction = 1f, phase = InstallCardPhase.DONE)
                    loadProfiles(cm, contentTypes) { profiles = it }
                    refreshKey++
                    onContentChanged()
                } else {
                    installDialog = installDialog?.copy(phase = InstallCardPhase.ERROR, error = "Install failed.")
                }
            }
        }
    }

    // Info sub-dialog
    showInfoProfile?.let { profile ->
        OutlinedAlertDialog(
            onDismissRequest = { showInfoProfile = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Content Info", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                androidx.compose.foundation.rememberScrollState().let { scroll ->
                    Column(Modifier.verticalScroll(scroll)) {
                        InfoField("Type", profile.type.toString())
                        InfoField("Version", profile.verName)
                        InfoField("Code", profile.verCode.toString())
                        if (!profile.desc.isNullOrEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(profile.desc, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInfoProfile = null }) { Text("OK") } }
        )
    }

    // Remove confirmation
    confirmRemoveProfile?.let { profile ->
        OutlinedAlertDialog(
            onDismissRequest = { confirmRemoveProfile = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Remove content?", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("Remove \"${profile.verName}\"?", color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = {
                TextButton(onClick = {
                    cm.removeContent(profile)
                    cm.syncContents()
                    loadProfiles(cm, contentTypes) { profiles = it }
                    confirmRemoveProfile = null
                    onContentChanged()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveProfile = null }) { Text("Cancel") } }
        )
    }

    // Error sub-dialog
    errorMsg?.let { msg ->
        OutlinedAlertDialog(
            onDismissRequest = { errorMsg = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            text = { Text(msg, color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = { TextButton(onClick = { errorMsg = null }) { Text("OK") } }
        )
    }

    // Content-card install dialog (shows Content-Info fields + a live 0..100% bar). Blocks dismiss
    // while the install is running; auto-closes shortly after it finishes.
    // Catalog download → render its progress card straight from the process-lifetime registry, so it
    // reflects live phase+percent even after backgrounding and re-attaches on reopen. The local-file
    // import dialog only shows when no catalog card is active.
    val catalogState = dialogKey?.let { contentStates[it] }
    if (catalogState != null) {
        InstallProgressDialog(catalogState.toInstallCardState(), onClose = {
            ContentDownloadRegistry.remove(catalogState.key)
            dialogKey = null
        })
    } else {
        installDialog?.let { st -> InstallProgressDialog(st, onClose = { installDialog = null }) }
    }
    // Local-file import DONE auto-close (unchanged).
    LaunchedEffect(installDialog?.phase) {
        if (installDialog?.phase == InstallCardPhase.DONE) {
            delay(900)
            installDialog = null
        }
    }
    // Catalog DONE: refresh the profile list + notify the parent, then auto-close the card. Fires
    // once per transition to DONE (keyed on the phase); on reopen after completion the sheet's
    // initial loadProfiles already reflects the installed component.
    LaunchedEffect(catalogState?.phase) {
        if (catalogState?.phase == ContentDownloadPhase.DONE) {
            loadProfiles(cm, contentTypes) { profiles = it }
            refreshKey++
            onContentChanged()
            delay(900)
            ContentDownloadRegistry.remove(catalogState.key)
            dialogKey = null
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        run {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(bottom = 12.dp)) {
                val multiType = contentTypes.size > 1
                // Title + "install from file"
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (multiType) "Compatibility Layer" else "${contentTypes.first()} Downloads",
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // Install-from-file entry point. Progress now lives in the content-card dialog.
                    var showPickMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showPickMenu = true }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = "Install from file",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        // Outlined-card look to match the content rows / FileManager idiom (shared
                        // helper — this Material3 build has no DropdownMenu shape/border params).
                        DropdownMenu(
                            expanded = showPickMenu,
                            onDismissRequest = { showPickMenu = false },
                            modifier = Modifier.outlinedMenuCard(),
                        ) {
                            DropdownMenuItem(text = { Text("Browse files") }, onClick = {
                                showPickMenu = false
                                filePicker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.WCP, "Select content file"))
                            })
                            MenuItemDivider()
                            DropdownMenuItem(text = { Text("Pick via system…") }, onClick = {
                                showPickMenu = false
                                filePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                                })
                            })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Wine / Proton chips (only when more than one type is shown).
                if (multiType) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        contentTypes.forEach { t -> TypeChip(t.toString(), t == selectedType) { selectedType = t } }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Divider(color = MaterialTheme.colorScheme.outline)

                // ── Component source control: Official (contents.json) default, opt-in community repos.
                SourceToggleBox(
                    includeCommunity = includeCommunity,
                    communityGroups = communityGroups,
                    activeSources = activeSources,
                    onToggle = { on ->
                        includeCommunity = on
                        srcPrefs.edit().putBoolean(COMMUNITY_KEY, on).apply()
                        if (on) selectAllOnLoad = true else activeSources = setOf(OFFICIAL_KEY)
                    },
                    onChipToggle = { key ->
                        activeSources = activeSources.toMutableSet().apply {
                            if (contains(key)) remove(key) else add(key)
                            if (isEmpty()) add(OFFICIAL_KEY) // never let the user empty the list
                        }
                    },
                )

                if (isLoadingRemote) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    val official = remember(profiles, selectedType, multiType) {
                        profiles
                            .filter { !multiType || it.type == selectedType }
                            .sortedByDescending { p -> if (p.remoteUrl == null) 1 else 0 }
                    }
                    // Versions already installed locally (normalized type+version) → a community duplicate
                    // shows an Installed badge instead of a redundant second download.
                    val installedKeys = remember(official) {
                        official.filter { it.remoteUrl == null }
                            .map { normVer(it.type.toString(), it.verName) }.toSet()
                    }
                    // Ordered sections: Official first, then each ACTIVE community source (in source order).
                    val sections = remember(official, communityGroups, activeSources, includeCommunity) {
                        buildList {
                            if (OFFICIAL_KEY in activeSources && official.isNotEmpty())
                                add(SheetSection("Official", community = false, color = OfficialColor, rows = official))
                            if (includeCommunity) communityGroups.forEach { g ->
                                if (g.name in activeSources)
                                    add(SheetSection(g.name, community = true, color = sourceColorFor(g.name), rows = g.profiles))
                            }
                        }
                    }
                    val totalRows = sections.sumOf { it.rows.size }

                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (totalRows == 0 && !communityLoading) {
                            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text("No content available.", color = MaterialTheme.colorScheme.onSurface)
                            }
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item(key = "count") {
                                    val nSrc = sections.size
                                    Text(
                                        "Showing $totalRows version${if (totalRows != 1) "s" else ""} from " +
                                            "$nSrc source${if (nSrc != 1) "s" else ""}" +
                                            (if (!includeCommunity) " · community repos off" else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                                    )
                                }
                                sections.forEachIndexed { si, section ->
                                    item(key = "hdr-$si-${section.label}") { SourceGroupHeader(section) }
                                    itemsIndexed(
                                        section.rows,
                                        // Collision-proof: section+index prefix so an official + community
                                        // duplicate of the same version can never share a LazyColumn key.
                                        key = { ri, p -> "row-$si-$ri-${p.remoteUrl ?: ContentsManager.getEntryName(p)}" },
                                    ) { _, profile ->
                                        val key = ContentsManager.getEntryName(profile)
                                        val isLocal = profile.remoteUrl == null
                                        val cds = contentStates[key]
                                        val communityInstalled = section.community &&
                                            normVer(profile.type.toString(), profile.verName) in installedKeys
                                        DownloadContentItem(
                                            profile = profile,
                                            isLocal = isLocal,
                                            isInUse = isInUse(profile, inUseKey),
                                            isDownloading = cds?.phase == ContentDownloadPhase.DOWNLOADING,
                                            isInstalling = cds?.phase == ContentDownloadPhase.INSTALLING,
                                            progress = if (cds?.phase == ContentDownloadPhase.DOWNLOADING) cds.fraction else null,
                                            installProgress = if (cds?.phase == ContentDownloadPhase.INSTALLING) cds.fraction else null,
                                            sourceLabel = if (section.community) section.label else null,
                                            sourceColor = section.color,
                                            communityInstalled = communityInstalled,
                                            onDownload = {
                                                // Same process-lifetime pipeline for official + community;
                                                // community installs from the bridged profile's remoteUrl.
                                                startContentDownload(context.applicationContext, profile)
                                                dialogKey = key
                                            },
                                            onInfo = { showInfoProfile = profile },
                                            onRemove = { confirmRemoveProfile = profile },
                                        )
                                    }
                                }
                                if (communityLoading) item(key = "community-loading") {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close", color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

// ── Single-type overload ──────────────────────────────────────────────────────
@Composable
fun ContentDownloadSheet(
    contentType: ContentProfile.ContentType,
    onDismiss: () -> Unit,
    onContentChanged: () -> Unit,
    inUseKey: String? = null,
) = ContentDownloadSheet(listOf(contentType), onDismiss, onContentChanged, inUseKey)

// ── Row (outlined card matching the FileManager / CommunityCard idiom: surfaceContainer fill,
// 1dp outline, rounded 10dp; Memory icon, name, trailing cloud / state) ──
@Composable
private fun DownloadContentItem(
    profile: ContentProfile,
    isLocal: Boolean,
    isInUse: Boolean,
    isDownloading: Boolean,
    isInstalling: Boolean,
    progress: Float?,
    installProgress: Float?,
    onDownload: () -> Unit,
    onInfo: () -> Unit,
    onRemove: () -> Unit,
    // Community provenance: a small colored source pill + (if the same version is already installed)
    // an Installed badge instead of a redundant download action.
    sourceLabel: String? = null,
    sourceColor: Color = Color.Unspecified,
    communityInstalled: Boolean = false,
) {
    val busy = isDownloading || isInstalling
    val installedBlue = Color(0xFF4FC3F7) // intentional: distinct installed/in-use status blue, not the accent
    val cs = MaterialTheme.colorScheme
    // Whole card is tappable to download when it's an available (not-installed, not-busy) entry —
    // matches the adrenotools EntryRow behaviour.
    val rowClickable = !busy && !isLocal && !communityInstalled
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (rowClickable) Modifier.clickable(onClick = onDownload) else Modifier),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        border = BorderStroke(1.dp, cs.outline),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Memory, contentDescription = null, tint = cs.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.verName, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (sourceLabel != null && sourceColor != Color.Unspecified) {
                            Spacer(Modifier.width(8.dp))
                            SourcePill(sourceLabel, sourceColor)
                        }
                    }
                    val sub = when {
                        isInUse -> "In use"
                        isLocal || communityInstalled -> "Installed"
                        !profile.desc.isNullOrEmpty() -> profile.desc
                        else -> null
                    }
                    if (sub != null) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isInUse) installedBlue else cs.onSurfaceVariant,
                        )
                    }
                }
                when {
                    busy -> {}
                    isLocal -> {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Installed", tint = installedBlue,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Icon(Icons.Filled.Info, contentDescription = "Info", tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(20.dp).clickable(onClick = onInfo))
                        Spacer(Modifier.width(14.dp))
                        Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = Color(0xFFEF5350), // intentional: destructive-action red
                            modifier = Modifier.size(20.dp).clickable(onClick = onRemove))
                    }
                    communityInstalled -> Icon(Icons.Filled.CheckCircle, contentDescription = "Installed",
                        tint = installedBlue, modifier = Modifier.size(20.dp))
                    else -> Icon(Icons.Filled.CloudDownload, contentDescription = "Download", tint = cs.primary,
                        modifier = Modifier.size(22.dp))
                }
            }
            // 0→100 determinate bar for both phases — blue "Downloading", green "Installing".
            if (busy) {
                Spacer(Modifier.height(6.dp))
                val frac = (if (isInstalling) installProgress else progress)?.coerceIn(0f, 1f) ?: 0f
                val barColor = if (isInstalling) Color(0xFF4CAF50) else cs.primary // intentional: green = "installing" phase, distinct from blue download phase
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (isInstalling) "Installing" else "Downloading",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text("${(frac * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(progress = frac, modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = barColor, trackColor = cs.surfaceContainerHighest)
            }
        }
    }
}

@Composable
private fun TypeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) cs.onPrimary else cs.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
    }
}

// ── Install dialog (content-card styled) ──────────────────────────────────────
private enum class InstallCardPhase { DOWNLOADING, INSTALLING, DONE, ERROR }

// Snapshot the install-card dialog renders. Catalog installs seed every field up front; local-file
// imports seed title/type only and fill the % bar as extraction runs.
private data class InstallCardState(
    val title: String,
    val type: String? = null,
    val verName: String? = null,
    val verCode: String? = null,
    val desc: String? = null,
    val fraction: Float = 0f,
    val phase: InstallCardPhase = InstallCardPhase.INSTALLING,
    val error: String? = null,
)

// Map a process-lifetime registry snapshot onto the sheet's install-card model, so a catalog
// download rendered from ContentDownloadRegistry reuses the exact same card UI as before.
private fun ContentDownloadState.toInstallCardState() = InstallCardState(
    title = title,
    type = type,
    verName = verName,
    verCode = verCode,
    desc = desc,
    fraction = fraction,
    phase = when (phase) {
        ContentDownloadPhase.DOWNLOADING -> InstallCardPhase.DOWNLOADING
        ContentDownloadPhase.INSTALLING -> InstallCardPhase.INSTALLING
        ContentDownloadPhase.DONE -> InstallCardPhase.DONE
        ContentDownloadPhase.ERROR -> InstallCardPhase.ERROR
    },
    error = error,
)

// The same outlined-card look as the content rows, carrying the Content-Info fields plus a live bar.
// Dismiss is blocked until the install reaches a terminal (DONE / ERROR) state.
@Composable
private fun InstallProgressDialog(state: InstallCardState, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val terminal = state.phase == InstallCardPhase.DONE || state.phase == InstallCardPhase.ERROR
    Dialog(
        onDismissRequest = { if (terminal) onClose() },
        // usePlatformDefaultWidth=false lets the card use the wider padding below so long .wcp names
        // (e.g. proton-10.0-2-arm64ec-controllerfix-unixlib.wcp) get the room to wrap tidily.
        properties = DialogProperties(
            dismissOnBackPress = terminal,
            dismissOnClickOutside = terminal,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
            border = BorderStroke(1.dp, cs.outline),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Memory, contentDescription = null, tint = cs.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    // weight(1f) => the title takes the full remaining width before wrapping; capped at
                    // 2 lines with an end-ellipsis so a long name never orphans a single trailing char.
                    Text(state.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                state.type?.let { InfoField("Type", it) }
                state.verName?.let { InfoField("Version", it) }
                state.verCode?.let { InfoField("Code", it) }
                if (!state.desc.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(state.desc, color = cs.onSurface, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                if (state.phase == InstallCardPhase.ERROR) {
                    Text(state.error ?: "Install failed.", color = cs.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) { Text("Close", color = cs.primary) }
                    }
                } else {
                    val frac = state.fraction.coerceIn(0f, 1f)
                    val label = when (state.phase) {
                        InstallCardPhase.DOWNLOADING -> "Downloading"
                        InstallCardPhase.DONE -> "Done"
                        else -> "Installing"
                    }
                    val barColor = if (state.phase == InstallCardPhase.DONE) Color(0xFF4CAF50) else cs.primary
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = frac, modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = barColor, trackColor = cs.surfaceContainerHighest)
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun isInUse(profile: ContentProfile, inUseKey: String?): Boolean {
    if (inUseKey.isNullOrEmpty()) return false
    return inUseKey == ContentsManager.getEntryName(profile) ||
        inUseKey == profile.verName ||
        inUseKey == "${profile.type}-${profile.verName}-${profile.verCode}"
}

private fun loadProfiles(
    cm: ContentsManager,
    contentTypes: List<ContentProfile.ContentType>,
    onResult: (List<ContentProfile>) -> Unit,
) {
    cm.syncContents()
    val all = mutableListOf<ContentProfile>()
    for (type in contentTypes) cm.getProfiles(type)?.let { all.addAll(it) }
    onResult(all.distinctBy { ContentsManager.getEntryName(it) })
}

// internal (not private): the community-config inline installer reuses this same download path.
internal fun downloadToCache(context: Context, profile: ContentProfile, onProgress: (Float) -> Unit): Uri? {
    // Stable per-component temp name (NOT temp_<millis>, which orphans partials): a fixed name
    // lets an interrupted download resume via HTTP Range on the next attempt instead of
    // restarting from zero. Range-aware Downloader appends to this file when a partial exists.
    val safe = ContentsManager.getEntryName(profile).replace(Regex("[^A-Za-z0-9._-]"), "_")
    val f = File(context.cacheDir, "content_dl_$safe.part")
    return if (Downloader.downloadFile(profile.remoteUrl, f, /* resume = */ true) { frac -> onProgress(frac) }) Uri.fromFile(f) else null
}

// internal (not private): reused by the community-config inline installer (same install path).
internal fun installContent(
    context: Context,
    cm: ContentsManager,
    uri: Uri,
    onProgress: (fraction: Float, etaText: String) -> Unit,
    onDone: (Boolean) -> Unit,
) {
    val activity = context.findActivity()
    if (activity == null) { onDone(false); return }
    // Byte-accurate denominator = the compressed source size (file uris only; 0 for content uris).
    val total = uri.path?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
    val etaTracker = ImportEtaTracker()
    Executors.newSingleThreadExecutor().execute {
        try {
            val progress = TarCompressorUtils.OnReadProgressListener { read, tot ->
                if (tot > 0) {
                    val p = etaTracker.update(read, tot)
                    activity.runOnUiThread { onProgress((read.toFloat() / tot).coerceIn(0f, 1f), formatEta(p.etaSeconds)) }
                }
            }
            cm.extraContentFile(uri, total, progress, object : ContentsManager.OnInstallFinishedCallback {
                var phase = 0
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    // A component that's already installed is NOT a failure — the caller's post-install
                    // apply re-resolves against what's on disk and finds it. Report success so the
                    // community inline installer writes the version to the shortcut instead of showing
                    // a misleading "install failed" on a build the user already has.
                    if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) {
                        Log.i("CommunityConfigs", "Component already installed (ERROR_EXIST) — treating as success")
                        activity.runOnUiThread { onProgress(1f, ""); onDone(true) }
                        return
                    }
                    // Every other reason previously surfaced as a bare "fails" with nothing in the log.
                    Log.w("CommunityConfigs", "Component install failed: $reason", e)
                    activity.runOnUiThread { onDone(false) }
                }
                override fun onSucceed(profile: ContentProfile) {
                    try {
                        if (phase == 0) {
                            phase = 1
                            cm.finishInstallContent(profile, this)
                        } else {
                            cm.syncContents()
                            activity.runOnUiThread { onProgress(1f, ""); onDone(true) }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        activity.runOnUiThread { onDone(false) }
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            activity.runOnUiThread { onDone(false) }
        }
    }
}

// ── Community source unification (opt-in) ─────────────────────────────────────

private const val COMMUNITY_PREFS = "component_download_prefs"
private const val COMMUNITY_KEY = "include_community"
private const val OFFICIAL_KEY = "__official__"

private val OfficialColor = Color(0xFF37C26B)
private val CommunityPalette = listOf(
    Color(0xFF3D9BFF), Color(0xFFB98CFF), Color(0xFFFF7A18),
    Color(0xFF4DD0E1), Color(0xFFFFB300), Color(0xFF66BB6A),
)
private fun sourceColorFor(name: String): Color =
    CommunityPalette[name.hashCode().absoluteValue % CommunityPalette.size]

/** A community repo's versions for the active type, bridged to installable ContentProfiles. */
private data class CommunityGroup(val name: String, val profiles: List<ContentProfile>)

/** One rendered group in the sheet: Official (contents.json) or a community source. */
private data class SheetSection(
    val label: String,
    val community: Boolean,
    val color: Color,
    val rows: List<ContentProfile>,
)

/** True if [source] lists this sheet's [typeStr] (case-insensitive; fex/fexcore bridged). Empty = include. */
private fun sourceSupportsType(source: RemoteSourceRepository.RemoteSource, typeStr: String): Boolean {
    if (source.supportedTypes.isEmpty()) return true
    val t = typeStr.lowercase()
    return source.supportedTypes.any { st ->
        val s = st.lowercase()
        s == t || (t.contains("fex") && s.contains("fex"))
    }
}

/**
 * Bridge a RemoteItem → an installable ContentProfile (remoteUrl set), so the existing
 * startContentDownload / ContentsManager pipeline installs it unchanged. verCode is derived from the
 * URL only to make the registry/entry key unique — the real type/version/code come from the archive's
 * own profile.json at extract time, so this synthetic code never affects where it installs.
 */
private fun RemoteSourceRepository.RemoteItem.toProfile(type: ContentProfile.ContentType): ContentProfile =
    ContentProfile().also {
        it.type = type
        it.verName = versionName
        it.verCode = downloadUrl.hashCode().absoluteValue
        it.remoteUrl = downloadUrl
        it.desc = description
    }

/** Normalized identity for cross-checking an installed version against a community duplicate. */
private fun normVer(type: String, ver: String?): String =
    (type + (ver ?: "")).lowercase().filter { it.isLetterOrDigit() }

@Composable
private fun SourceToggleBox(
    includeCommunity: Boolean,
    communityGroups: List<CommunityGroup>,
    activeSources: Set<String>,
    onToggle: (Boolean) -> Unit,
    onChipToggle: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        border = BorderStroke(1.dp, cs.outline),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Icon(Icons.Filled.Hub, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Include community repos", style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                    Text("Add versions from the Contents screen's sources",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                Switch(checked = includeCommunity, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = cs.primary))
            }
            if (includeCommunity) {
                Divider(color = cs.outline.copy(alpha = 0.4f))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SourceChip("Official", OfficialColor, OFFICIAL_KEY in activeSources) { onChipToggle(OFFICIAL_KEY) }
                    communityGroups.forEach { g ->
                        SourceChip(g.name, sourceColorFor(g.name), g.name in activeSources) { onChipToggle(g.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 12.dp),
    ) {
        if (!selected) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (selected) cs.onPrimary else cs.onSurface,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SourceGroupHeader(section: SheetSection) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
    ) {
        Box(Modifier.size(11.dp).clip(RoundedCornerShape(6.dp)).background(section.color))
        Spacer(Modifier.width(9.dp))
        Text(section.label, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(
            if (section.community) "Community" else "Official",
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = section.color,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(section.color.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        Text("${section.rows.size}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun SourcePill(label: String, color: Color) {
    Text(
        label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}
