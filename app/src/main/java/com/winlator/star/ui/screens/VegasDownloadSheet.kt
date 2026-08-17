package com.winlator.star.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.theme.Surface as SurfaceColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Holds info about one downloadable vegas release from GitHub.
 */
private data class VegasRelease(
    val tagName: String,
    val displayName: String,
    val wcpAssetUrl: String?,
    val rawZipAssetUrl: String?,
)

/**
 * Composable bottom-sheet-style dialog listing available VEGAS releases
 * from isygold/vegas-releases. Downloads + installs via ContentsManager.
 */
@Composable
fun VegasDownloadSheet(
    onDismiss: () -> Unit,
    onContentChanged: () -> Unit,
) {
    val context = LocalContext.current
    val cm = remember { ContentsManager(context) }
    val scope = rememberCoroutineScope()

    var releases by remember { mutableStateOf<List<VegasRelease>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var downloadingTag by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var installing by remember { mutableStateOf(false) }

    // Fetch releases from GitHub API
    LaunchedEffect(Unit) {
        val json = withContext(Dispatchers.IO) {
            Downloader.downloadString("https://api.github.com/repos/isygold/vegas-releases/releases")
        }
        if (json != null) {
            try {
                val arr = JSONArray(json)
                val list = mutableListOf<VegasRelease>()
                for (i in 0 until arr.length()) {
                    val rel = arr.getJSONObject(i)
                    val tag = rel.getString("tag_name")
                    val name = rel.optString("name", tag)
                    var wcpUrl: String? = null
                    var zipUrl: String? = null
                    val assets = rel.optJSONArray("assets")
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val a = assets.getJSONObject(j)
                            val aname = a.getString("name")
                    if (aname.startsWith("vegas-") && aname.endsWith(".wcp")) {
                        wcpUrl = a.getString("browser_download_url")
                    } else if (aname.startsWith("dxvk-") && aname.endsWith(".zip")) {
                        zipUrl = a.getString("browser_download_url")
                    }
                        }
                    }
                    if (wcpUrl != null) {
                        list.add(VegasRelease(tag, name, wcpUrl, zipUrl))
                    }
                }
                releases = list
            } catch (e: Exception) {
                errorMsg = "Failed to parse releases: ${e.message}"
            }
        } else {
            errorMsg = "Failed to fetch releases from GitHub"
        }
        isLoading = false
    }

    // Installing overlay
    if (installing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 8.dp
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Installing VEGAS\u2026", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }

    // Error dialog
    errorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMsg = null },
            title = { Text("Error", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(msg, color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = { TextButton(onClick = { errorMsg = null }) { Text("OK") } }
        )
    }

    // Main dialog
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("VEGAS Downloads", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            if (isLoading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (releases.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (errorMsg != null) "Could not load releases." else "No releases available.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                if (downloadingTag != null) {
                    LinearProgressIndicator(
                        progress = downloadProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(releases, key = { it.tagName }) { release ->
                        val isDownloading = downloadingTag == release.tagName
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(release.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(release.tagName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(
                                    onClick = {
                                        val url = release.wcpAssetUrl ?: return@IconButton
                                        downloadingTag = release.tagName
                                        downloadProgress = 0f
                                        scope.launch {
                                            val uri = withContext(Dispatchers.IO) {
                                                downloadWcp(context, url, release.tagName) { progress ->
                                                    downloadProgress = progress
                                                }
                                            }
                                            downloadingTag = null
                                            if (uri != null) {
                                                installing = true
                                                installWcp(context, cm, uri) { ok ->
                                                    installing = false
                                                    if (ok) {
                                                        cm.syncContents()
                                                        onContentChanged()
                                                        onDismiss()
                                                    } else {
                                                        errorMsg = "Install failed."
                                                    }
                                                }
                                            } else {
                                                errorMsg = "Download failed."
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Download a .wcp file to cache dir and return a content:// URI. */
private fun downloadWcp(context: Context, url: String, tag: String, onProgress: ((Float) -> Unit)? = null): Uri? {
    val f = File(context.cacheDir, "vegas_${tag}.wcp")
    val listener = if (onProgress != null) object : Downloader.ProgressListener {
        override fun onProgress(fraction: Float) { onProgress(fraction) }
    } else null
    return if (Downloader.downloadFile(url, f, listener)) Uri.fromFile(f) else null
}

/** Install a .wcp content package via ContentsManager. */
private fun installWcp(
    context: Context,
    cm: ContentsManager,
    uri: Uri,
    onDone: (Boolean) -> Unit,
) {
    val activity = context.findActivity()
    if (activity == null) {
        onDone(false)
        return
    }
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
