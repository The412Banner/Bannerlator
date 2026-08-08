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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.R
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.core.TarCompressorUtils
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.ContentDownloadState
import com.winlator.star.store.download.startContentDownload
import com.winlator.star.ui.findActivity
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

    LaunchedEffect(contentTypes, refreshKey) {
        val json = withContext(Dispatchers.IO) {
            Downloader.downloadString(ContentsManager.REMOTE_PROFILES)
        }
        if (json != null) cm.setRemoteProfiles(json) else cm.syncContents()
        loadProfiles(cm, contentTypes) { profiles = it }
        isLoadingRemote = false
    }

    // Manual "install from file" picker. Handles both the in-app picker (selectedFile path,
    // wrapped as a file:// Uri) and the system SAF picker (result.data.data).
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        (result.data?.data ?: InAppFilePicker.pickedUri(result.data))?.let { uri ->
            // Version/desc aren't known until the archive is parsed — seed the card with the filename +
            // (single-type screens) the content type, then let the % bar carry the rest.
            val fname = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.compose_content_file)
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
                    installDialog = installDialog?.copy(
                        phase = InstallCardPhase.ERROR,
                        error = context.getString(R.string.compose_content_install_failed),
                    )
                }
            }
        }
    }

    // Info sub-dialog
    showInfoProfile?.let { profile ->
        OutlinedAlertDialog(
            onDismissRequest = { showInfoProfile = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.compose_content_content_info),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                androidx.compose.foundation.rememberScrollState().let { scroll ->
                    Column(Modifier.verticalScroll(scroll)) {
                        InfoField(stringResource(R.string.compose_content_type), profile.type.toString())
                        InfoField(stringResource(R.string.compose_content_version), profile.verName)
                        InfoField(stringResource(R.string.compose_content_code), profile.verCode.toString())
                        if (!profile.desc.isNullOrEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(profile.desc, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoProfile = null }) {
                    Text(stringResource(R.string.compose_content_ok))
                }
            }
        )
    }

    // Remove confirmation
    confirmRemoveProfile?.let { profile ->
        OutlinedAlertDialog(
            onDismissRequest = { confirmRemoveProfile = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.compose_content_remove_content_title),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    stringResource(R.string.compose_content_remove_named, profile.verName),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    cm.removeContent(profile)
                    cm.syncContents()
                    loadProfiles(cm, contentTypes) { profiles = it }
                    confirmRemoveProfile = null
                    onContentChanged()
                }) { Text(stringResource(R.string.compose_content_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveProfile = null }) {
                    Text(stringResource(R.string.compose_content_cancel))
                }
            }
        )
    }

    // Error sub-dialog
    errorMsg?.let { msg ->
        OutlinedAlertDialog(
            onDismissRequest = { errorMsg = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            text = { Text(msg, color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = {
                TextButton(onClick = { errorMsg = null }) {
                    Text(stringResource(R.string.compose_content_ok))
                }
            }
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
                        if (multiType) {
                            stringResource(R.string.compose_content_compatibility_layer)
                        } else {
                            stringResource(
                                R.string.compose_content_type_downloads,
                                contentTypes.first().toString(),
                            )
                        },
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // Install-from-file entry point. Progress now lives in the content-card dialog.
                    var showPickMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showPickMenu = true }) {
                            Icon(Icons.Filled.FolderOpen,
                                contentDescription = stringResource(R.string.compose_content_install_from_file),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        // Outlined-card look to match the content rows / FileManager idiom (shared
                        // helper — this Material3 build has no DropdownMenu shape/border params).
                        DropdownMenu(
                            expanded = showPickMenu,
                            onDismissRequest = { showPickMenu = false },
                            modifier = Modifier.outlinedMenuCard(),
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.compose_content_browse_files)) },
                                onClick = {
                                showPickMenu = false
                                filePicker.launch(
                                    InAppFilePicker.buildIntent(
                                        context,
                                        InAppFilePicker.WCP,
                                        context.getString(R.string.compose_content_select_content_file),
                                    )
                                )
                            })
                            MenuItemDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.compose_content_pick_via_system)) },
                                onClick = {
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

                if (isLoadingRemote) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    val shown = remember(profiles, selectedType, multiType) {
                        profiles
                            .filter { !multiType || it.type == selectedType }
                            .sortedByDescending { p -> if (p.remoteUrl == null) 1 else 0 }
                    }
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.compose_content_no_content_available),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(shown, key = { ContentsManager.getEntryName(it) }) { profile ->
                                    val key = ContentsManager.getEntryName(profile)
                                    val isLocal = profile.remoteUrl == null
                                    // Live phase/percent for this row come from the process-lifetime
                                    // registry, so a backgrounded download keeps the bar moving.
                                    val cds = contentStates[key]
                                    DownloadContentItem(
                                        profile = profile,
                                        isLocal = isLocal,
                                        isInUse = isInUse(profile, inUseKey),
                                        isDownloading = cds?.phase == ContentDownloadPhase.DOWNLOADING,
                                        isInstalling = cds?.phase == ContentDownloadPhase.INSTALLING,
                                        progress = if (cds?.phase == ContentDownloadPhase.DOWNLOADING) cds.fraction else null,
                                        installProgress = if (cds?.phase == ContentDownloadPhase.INSTALLING) cds.fraction else null,
                                        onDownload = {
                                            // Fire-and-forget onto the process-lifetime controller (bracketed by the
                                            // shared download foreground service). It seeds + drives the registry; the
                                            // sheet just re-attaches its progress card to this key.
                                            startContentDownload(context.applicationContext, profile)
                                            dialogKey = key
                                        },
                                        onInfo = { showInfoProfile = profile },
                                        onRemove = { confirmRemoveProfile = profile },
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.compose_content_close),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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
) {
    val busy = isDownloading || isInstalling
    val installedBlue = Color(0xFF4FC3F7) // intentional: distinct installed/in-use status blue, not the accent
    val cs = MaterialTheme.colorScheme
    // Whole card is tappable to download when it's an available (not-installed, not-busy) entry —
    // matches the adrenotools EntryRow behaviour.
    val rowClickable = !busy && !isLocal
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
                    Text(profile.verName, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    val sub = when {
                        isInUse -> stringResource(R.string.compose_content_in_use)
                        isLocal -> stringResource(R.string.compose_content_installed)
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
                        Icon(Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.compose_content_installed),
                            tint = installedBlue,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Icon(Icons.Filled.Info,
                            contentDescription = stringResource(R.string.compose_content_info),
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(20.dp).clickable(onClick = onInfo))
                        Spacer(Modifier.width(14.dp))
                        Icon(Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.compose_content_remove),
                            tint = Color(0xFFEF5350), // intentional: destructive-action red
                            modifier = Modifier.size(20.dp).clickable(onClick = onRemove))
                    }
                    else -> Icon(Icons.Filled.CloudDownload,
                        contentDescription = stringResource(R.string.compose_content_download),
                        tint = cs.primary,
                        modifier = Modifier.size(22.dp))
                }
            }
            // 0→100 determinate bar for both phases — blue "Downloading", green "Installing".
            if (busy) {
                Spacer(Modifier.height(6.dp))
                val frac = (if (isInstalling) installProgress else progress)?.coerceIn(0f, 1f) ?: 0f
                val barColor = if (isInstalling) Color(0xFF4CAF50) else cs.primary // intentional: green = "installing" phase, distinct from blue download phase
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (isInstalling) {
                            stringResource(R.string.compose_content_installing)
                        } else {
                            stringResource(R.string.compose_content_downloading)
                        },
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text(stringResource(R.string.compose_content_percent, (frac * 100).toInt()),
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
        Text(
            stringResource(R.string.compose_content_label_with_colon, label),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
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
                state.type?.let { InfoField(stringResource(R.string.compose_content_type), it) }
                state.verName?.let { InfoField(stringResource(R.string.compose_content_version), it) }
                state.verCode?.let { InfoField(stringResource(R.string.compose_content_code), it) }
                if (!state.desc.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(state.desc, color = cs.onSurface, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                if (state.phase == InstallCardPhase.ERROR) {
                    Text(
                        state.error ?: stringResource(R.string.compose_content_install_failed),
                        color = cs.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) {
                            Text(stringResource(R.string.compose_content_close), color = cs.primary)
                        }
                    }
                } else {
                    val frac = state.fraction.coerceIn(0f, 1f)
                    val label = when (state.phase) {
                        InstallCardPhase.DOWNLOADING -> stringResource(R.string.compose_content_downloading)
                        InstallCardPhase.DONE -> stringResource(R.string.compose_content_done)
                        else -> stringResource(R.string.compose_content_installing)
                    }
                    val barColor = if (state.phase == InstallCardPhase.DONE) Color(0xFF4CAF50) else cs.primary
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Text(
                            stringResource(R.string.compose_content_percent, (frac * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
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
                    activity.runOnUiThread {
                        onProgress(
                            (read.toFloat() / tot).coerceIn(0f, 1f),
                            formatContentEta(context, p.etaSeconds),
                        )
                    }
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

internal fun formatContentEta(context: Context, seconds: Long): String {
    if (seconds < 0L) return ""
    if (seconds < 60L) return context.getString(R.string.compose_content_eta_less_than_minute)

    val hours = (seconds / 3600L).toInt()
    val minutes = ((seconds % 3600L) / 60L).toInt()
    val duration = if (hours == 0) {
        context.resources.getQuantityString(
            R.plurals.compose_content_eta_minutes_short,
            minutes,
            minutes,
        )
    } else {
        val hoursText = context.resources.getQuantityString(
            R.plurals.compose_content_eta_hours_short,
            hours,
            hours,
        )
        if (minutes == 0) {
            hoursText
        } else {
            val minutesText = context.resources.getQuantityString(
                R.plurals.compose_content_eta_minutes_short,
                minutes,
                minutes,
            )
            context.getString(
                R.string.compose_content_eta_hours_minutes,
                hoursText,
                minutesText,
            )
        }
    }
    return context.getString(R.string.compose_content_eta_remaining, duration)
}
