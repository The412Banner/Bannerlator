package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.ui.theme.Divider as DividerColor
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.Surface as SurfaceColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Dialog showing downloadable + installed content items for given content type(s).
 * Works on any screen (including inside other dialogs).
 * After install/remove, calls [onContentChanged] so the parent can refresh version lists.
 */
@Composable
fun ContentDownloadSheet(
    contentTypes: List<ContentProfile.ContentType>,
    onDismiss: () -> Unit,
    onContentChanged: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val cm = remember { ContentsManager(context) }
    val scope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf<List<ContentProfile>>(emptyList()) }
    var downloadingKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showInfoProfile by remember { mutableStateOf<ContentProfile?>(null) }
    var confirmRemoveProfile by remember { mutableStateOf<ContentProfile?>(null) }
    var installing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isLoadingRemote by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(contentTypes, refreshKey) {
        val json = withContext(Dispatchers.IO) {
            Downloader.downloadString(ContentsManager.REMOTE_PROFILES)
        }
        if (json != null) {
            cm.setRemoteProfiles(json)
        } else {
            cm.syncContents()
        }
        loadProfiles(cm, contentTypes) { profiles = it }
        isLoadingRemote = false
    }

    // Info sub-dialog
    showInfoProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { showInfoProfile = null },
            containerColor = Color(0xFF2A2A2A),
            title = { Text("Content Info", color = Color.White) },
            text = {
                androidx.compose.foundation.rememberScrollState().let { scroll ->
                    Column(Modifier.verticalScroll(scroll)) {
                        InfoField("Type", profile.type.toString())
                        InfoField("Version", profile.verName)
                        InfoField("Code", profile.verCode.toString())
                        if (!profile.desc.isNullOrEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(profile.desc, color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodySmall)
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
            containerColor = Color(0xFF2A2A2A),
            title = { Text("Remove content?", color = Color.White) },
            text = { Text("Remove \"${profile.verName}\"?", color = Color(0xFFCCCCCC)) },
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

    // Installing overlay
    if (installing) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Surface(color = Color(0xFF2A2A2A), shape = RoundedCornerShape(12.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    CircularProgressIndicator(color = Color(0xFF0055FF))
                    Spacer(Modifier.height(16.dp))
                    Text("Installing\u2026", color = Color.White)
                }
            }
        }
    }

    // Error toast sub-dialog
    errorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMsg = null },
            containerColor = Color(0xFF2A2A2A),
            text = { Text(msg, color = Color(0xFFCCCCCC)) },
            confirmButton = { TextButton(onClick = { errorMsg = null }) { Text("OK") } }
        )
    }

    // Main dialog
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color.Black,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF555555)),
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("${contentTypes.joinToString(" + ") { it.toString() }} Downloads", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                if (isLoadingRemote) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (profiles.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No content available.", color = Color(0xFFCCCCCC))
                    }
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(profiles, key = { ContentsManager.getEntryName(it) }) { profile ->
                                val key = ContentsManager.getEntryName(profile)
                                val isLocal = profile.remoteUrl == null
                                val isDownloading = key in downloadingKeys

                                DownloadContentItem(
                                    profile = profile,
                                    isLocal = isLocal,
                                    isDownloading = isDownloading,
                                    onDownload = {
                                        downloadingKeys = downloadingKeys + key
                                        scope.launch {
                                            val uri = withContext(Dispatchers.IO) {
                                                downloadToCache(context, profile)
                                            }
                                            downloadingKeys = downloadingKeys - key
                                            if (uri != null) {
                                                installing = true
                                                installContent(context, cm, uri) { ok ->
                                                installing = false
                                                if (ok) {
                                                    loadProfiles(cm, contentTypes) { profiles = it }
                                                    refreshKey++
                                                    onContentChanged()
                                                    } else {
                                                        errorMsg = "Install failed."
                                                    }
                                                }
                                            } else {
                                                errorMsg = "Download failed."
                                            }
                                        }
                                    },
                                    onInfo = { showInfoProfile = profile },
                                    onRemove = { confirmRemoveProfile = profile },
                                )
                                Divider(color = DividerColor)
                            }
                        }

                        if (installing) {
                            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(12.dp))
                                    Text("Installing...", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close", color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

// ── Content download sheet for a single type ──────────────────────────────────
@Composable
fun ContentDownloadSheet(
    contentType: ContentProfile.ContentType,
    onDismiss: () -> Unit,
    onContentChanged: () -> Unit,
) = ContentDownloadSheet(listOf(contentType), onDismiss, onContentChanged)

// ── Internal composables ──────────────────────────────────────────────────────

@Composable
private fun DownloadContentItem(
    profile: ContentProfile,
    isLocal: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onInfo: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A), shape = RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(profile.verName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text("Code: ${profile.verCode}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFCCCCCC))
        }
        if (!isLocal) {
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            IconButton(onClick = onInfo) {
                Icon(Icons.Filled.Info, contentDescription = "Info", tint = Color(0xFFCCCCCC))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = Color(0xFFEF5350))
            }
        }
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color(0xFFE0E0E0), style = MaterialTheme.typography.bodySmall)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun loadProfiles(
    cm: ContentsManager,
    contentTypes: List<ContentProfile.ContentType>,
    onResult: (List<ContentProfile>) -> Unit,
) {
    cm.syncContents()
    val all = mutableListOf<ContentProfile>()
    for (type in contentTypes) {
        cm.getProfiles(type)?.let { all.addAll(it) }
    }
    onResult(all.distinctBy { ContentsManager.getEntryName(it) })
}

private fun downloadToCache(context: Context, profile: ContentProfile): Uri? {
    val f = File(context.cacheDir, "temp_${System.currentTimeMillis()}")
    return if (Downloader.downloadFile(profile.remoteUrl, f)) Uri.fromFile(f) else null
}

private fun installContent(
    context: Context,
    cm: ContentsManager,
    uri: Uri,
    onDone: (Boolean) -> Unit,
) {
    val activity = context as Activity
    Executors.newSingleThreadExecutor().execute {
        try {
            cm.extraContentFile(uri, object : ContentsManager.OnInstallFinishedCallback {
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
                            activity.runOnUiThread { onDone(true) }
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
