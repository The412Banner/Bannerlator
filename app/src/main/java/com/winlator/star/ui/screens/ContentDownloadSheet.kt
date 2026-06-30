package com.winlator.star.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.core.TarCompressorUtils
import com.winlator.star.ui.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val activity = context.findActivity()
    val cm = remember { ContentsManager(context) }
    val scope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf<List<ContentProfile>>(emptyList()) }
    var downloadingKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var downloadProgress by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    // Install progress (0..1) — a time-based ramp for now; byte-accurate plumbing lands next build.
    var installProgress by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var installingKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fileInstalling by remember { mutableStateOf(false) }
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

    // Manual "install from file" picker.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri ->
            fileInstalling = true
            installContent(context, cm, uri, onProgress = {}) { ok ->
                fileInstalling = false
                if (ok) {
                    loadProfiles(cm, contentTypes) { profiles = it }
                    refreshKey++
                    onContentChanged()
                } else errorMsg = "Install failed."
            }
        }
    }

    // Info sub-dialog
    showInfoProfile?.let { profile ->
        AlertDialog(
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
        AlertDialog(
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
        AlertDialog(
            onDismissRequest = { errorMsg = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            text = { Text(msg, color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = { TextButton(onClick = { errorMsg = null }) { Text("OK") } }
        )
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
                    if (fileInstalling) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = {
                            filePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                            })
                        }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = "Install from file",
                                tint = MaterialTheme.colorScheme.primary)
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
                            Text("No content available.", color = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(shown, key = { ContentsManager.getEntryName(it) }) { profile ->
                                    val key = ContentsManager.getEntryName(profile)
                                    val isLocal = profile.remoteUrl == null
                                    DownloadContentItem(
                                        profile = profile,
                                        isLocal = isLocal,
                                        isInUse = isInUse(profile, inUseKey),
                                        isDownloading = key in downloadingKeys,
                                        isInstalling = key in installingKeys,
                                        progress = downloadProgress[key],
                                        installProgress = installProgress[key],
                                        onDownload = {
                                            downloadingKeys = downloadingKeys + key
                                            downloadProgress = downloadProgress + (key to 0f)
                                            scope.launch {
                                                val uri = withContext(Dispatchers.IO) {
                                                    downloadToCache(context, profile) { frac ->
                                                        activity?.runOnUiThread {
                                                            downloadProgress = downloadProgress + (key to frac)
                                                        }
                                                    }
                                                }
                                                if (uri != null) {
                                                    installingKeys = installingKeys + key
                                                    downloadingKeys = downloadingKeys - key
                                                    downloadProgress = downloadProgress - key
                                                    installProgress = installProgress + (key to 0f)
                                                    installContent(context, cm, uri, onProgress = { f ->
                                                        // Monotonic: ignore the brief XZ-probe reset before the ZSTD pass.
                                                        val prev = installProgress[key] ?: 0f
                                                        installProgress = installProgress + (key to maxOf(prev, f))
                                                    }) { ok ->
                                                        installingKeys = installingKeys - key
                                                        installProgress = installProgress - key
                                                        if (ok) {
                                                            loadProfiles(cm, contentTypes) { profiles = it }
                                                            refreshKey++
                                                            onContentChanged()
                                                        } else errorMsg = "Install failed."
                                                    }
                                                } else {
                                                    downloadingKeys = downloadingKeys - key
                                                    downloadProgress = downloadProgress - key
                                                    errorMsg = "Download failed."
                                                }
                                            }
                                        },
                                        onInfo = { showInfoProfile = profile },
                                        onRemove = { confirmRemoveProfile = profile },
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
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

// ── Row (adrenotools style: flat row, Memory icon, name, trailing cloud / state) ──
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
    // Whole row is tappable to download when it's an available (not-installed, not-busy) entry —
    // matches the adrenotools EntryRow behaviour.
    val rowClickable = !busy && !isLocal
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (rowClickable) Modifier.clickable(onClick = onDownload) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Memory, contentDescription = null, tint = cs.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.verName, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                val sub = when {
                    isInUse -> "In use"
                    isLocal -> "Installed"
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

private fun downloadToCache(context: Context, profile: ContentProfile, onProgress: (Float) -> Unit): Uri? {
    val f = File(context.cacheDir, "temp_${System.currentTimeMillis()}")
    return if (Downloader.downloadFile(profile.remoteUrl, f) { frac -> onProgress(frac) }) Uri.fromFile(f) else null
}

private fun installContent(
    context: Context,
    cm: ContentsManager,
    uri: Uri,
    onProgress: (Float) -> Unit,
    onDone: (Boolean) -> Unit,
) {
    val activity = context.findActivity()
    if (activity == null) { onDone(false); return }
    // Byte-accurate denominator = the compressed source size (file uris only; 0 for content uris).
    val total = uri.path?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
    Executors.newSingleThreadExecutor().execute {
        try {
            val progress = TarCompressorUtils.OnReadProgressListener { read, tot ->
                if (tot > 0) activity.runOnUiThread { onProgress((read.toFloat() / tot).coerceIn(0f, 1f)) }
            }
            cm.extraContentFile(uri, total, progress, object : ContentsManager.OnInstallFinishedCallback {
                var phase = 0
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    activity.runOnUiThread { onDone(false) }
                }
                override fun onSucceed(profile: ContentProfile) {
                    try {
                        if (phase == 0) {
                            phase = 1
                            cm.finishInstallContent(profile, this)
                        } else {
                            cm.syncContents()
                            activity.runOnUiThread { onProgress(1f); onDone(true) }
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
