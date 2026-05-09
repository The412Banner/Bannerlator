package com.winlator.cmod.ui.screens.adrenodownload

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.contents.AdrenotoolsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdrenoDriverDownloadSheet(
    onDismiss: () -> Unit,
    onDriverInstalled: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme

    val repo = remember { RemoteDriverRepository(context) }
    val sources = remember { DriverSources.ALL }

    var selectedSourceIndex by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<RemoteDriverEntry>>(emptyList()) }

    var pendingEntry by remember { mutableStateOf<RemoteDriverEntry?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadStage by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(selectedSourceIndex, refreshKey) {
        loading = true
        errorMessage = null
        entries = emptyList()
        val source = sources[selectedSourceIndex]
        repo.fetchEntries(source).fold(
            onSuccess = { entries = it },
            onFailure = { errorMessage = it.message ?: it::class.java.simpleName },
        )
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface,
        contentColor = cs.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
        ) {
            Text(
                text = "Download GPU drivers",
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            )

            // Horizontally scrollable source chip row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sources.forEachIndexed { index, source ->
                    SourceChip(
                        text = source.name,
                        selected = index == selectedSourceIndex,
                        onClick = { selectedSourceIndex = index },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = cs.outline.copy(alpha = 0.4f))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> CenteredLoading()
                    errorMessage != null -> CenteredError(
                        message = errorMessage ?: "",
                        onRetry = { refreshKey++ },
                    )
                    entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No drivers found.",
                            color = cs.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(entries, key = { it.source + "_" + it.downloadUrl }) { entry ->
                            EntryRow(entry = entry, onClick = { pendingEntry = entry })
                            Divider(color = cs.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            }
        }
    }

    pendingEntry?.let { entry ->
        if (!downloading) {
            AlertDialog(
                onDismissRequest = { pendingEntry = null },
                title = { Text("Download driver?") },
                text = {
                    Column {
                        Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Source: ${entry.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The driver will be downloaded and installed automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        downloading = true
                        downloadProgress = 0
                        downloadStage = "Downloading"
                        scope.launch {
                            repo.downloadEntry(entry) { pct -> downloadProgress = pct }
                                .fold(
                                    onSuccess = { file ->
                                        downloadStage = "Installing"
                                        val activity = context as? Activity ?: context
                                        val manager = AdrenotoolsManager(activity)
                                        val driverId = manager.installDriver(Uri.fromFile(file))
                                        file.delete()
                                        downloading = false
                                        pendingEntry = null
                                        if (driverId.isNotEmpty()) {
                                            Toast.makeText(context, "Installed: $driverId", Toast.LENGTH_SHORT).show()
                                            onDriverInstalled(driverId)
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Install failed — invalid driver package",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    },
                                    onFailure = { t ->
                                        downloading = false
                                        pendingEntry = null
                                        Toast.makeText(
                                            context,
                                            "Download failed: ${t.message ?: "unknown error"}",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    },
                                )
                        }
                    }) { Text("Download") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingEntry = null }) { Text("Cancel") }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { /* block dismiss while busy */ },
                title = { Text(downloadStage) },
                text = {
                    Column {
                        Text(
                            entry.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (downloadStage == "Downloading") {
                            LinearProgressIndicator(
                                progress = downloadProgress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "$downloadProgress%",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {},
            )
        }
    }
}

@Composable
private fun SourceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primary else cs.surfaceVariant
    val fg = if (selected) cs.onPrimary else cs.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun EntryRow(entry: RemoteDriverEntry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Memory,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.CloudDownload,
            contentDescription = "Download",
            tint = cs.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun CenteredLoading() {
    val cs = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(
                "Loading drivers…",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CenteredError(message: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = cs.error,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Could not load drivers",
                color = cs.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
