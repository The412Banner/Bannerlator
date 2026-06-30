package com.winlator.star.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.star.reshade.ReshadeCatalog
import com.winlator.star.reshade.ReshadeCatalogEntry
import com.winlator.star.reshade.ReshadeDownloader
import com.winlator.star.reshade.ReshadeManager
import com.winlator.star.ui.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pre-launch ReShade effect picker (used by the container editor + per-game shortcut editor).
 * Shows the current selection + a "Browse / download" button that opens a catalog sheet listing
 * EVERY effect from reshade.json: already-installed effects are selectable; not-yet-downloaded ones
 * are greyed with a download affordance. Tapping a greyed row downloads its archive, extracts it
 * into the drop-in folder, and the row "fills in" → selectable. [onCatalogChanged] lets the parent
 * rescan the drop-in folder (so the param list seeds) after an install or a new selection.
 */
@Composable
fun ReshadeEffectPicker(
    selected: String,
    supported: Boolean,
    onSelect: (String) -> Unit,
    onCatalogChanged: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("ReShade effect", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Text(
                if (selected.isBlank()) "None" else selected,
                style = MaterialTheme.typography.bodyLarge, color = cs.onSurface,
            )
        }
        OutlinedButton(onClick = { showSheet = true }) {
            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Browse")
        }
    }
    if (!supported && selected != "None" && selected.isNotBlank()) {
        Text(
            "ReShade only applies to DXVK/VKD3D (Vulkan) games; it has no effect with this DX wrapper.",
            style = MaterialTheme.typography.bodySmall, color = cs.error,
            modifier = Modifier.padding(top = 2.dp),
        )
    }

    if (showSheet) {
        ReshadeCatalogSheet(
            selected = selected,
            onDismiss = { showSheet = false },
            onSelect = { name -> onSelect(name); onCatalogChanged(); showSheet = false },
            onCatalogChanged = onCatalogChanged,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReshadeCatalogSheet(
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCatalogChanged: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    var catalog by remember { mutableStateOf<List<ReshadeCatalogEntry>>(emptyList()) }
    var installed by remember { mutableStateOf(ReshadeManager.scanEffectNames(context).toSet()) }
    var loading by remember { mutableStateOf(true) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var phaseLabel by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        catalog = withContext(Dispatchers.IO) { ReshadeCatalog.load() }
        installed = withContext(Dispatchers.IO) { ReshadeManager.scanEffectNames(context).toSet() }
        loading = false
    }

    // Rows: catalog entries, plus any locally-installed effect not present in the catalog
    // (user-dropped), each surfaced as a pseudo-entry so it stays selectable.
    val rows = remember(catalog, installed) {
        val catIds = catalog.map { it.id }.toSet()
        val extras = installed.filter { it !in catIds }
            .map { ReshadeCatalogEntry(it, it, "", "Installed", "", "", "", 0L, "", "", "$it.fx") }
        (catalog + extras).sortedWith(compareBy({ it.category }, { it.name.lowercase() }))
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface,
        contentColor = cs.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(bottom = 12.dp)) {
            Text(
                "ReShade Effects",
                color = cs.onSurface, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            Divider(color = cs.outlineVariant)

            errorMsg?.let {
                Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = cs.primary)
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        // "None" clears the selection.
                        item {
                            ReshadeNoneRow(isSelected = selected == "None" || selected.isBlank()) { onSelect("None") }
                            Divider(color = cs.outlineVariant.copy(alpha = 0.5f))
                        }
                        items(rows, key = { it.id }) { entry ->
                            val isInstalled = entry.id in installed
                            val isBusy = downloadingId == entry.id
                            ReshadeCatalogRow(
                                entry = entry,
                                isInstalled = isInstalled,
                                isSelected = selected.equals(entry.id, ignoreCase = true),
                                isBusy = isBusy,
                                phaseLabel = if (isBusy) phaseLabel else "",
                                progress = if (isBusy) progress else null,
                                onClick = {
                                    when {
                                        isInstalled -> onSelect(entry.id)
                                        downloadingId != null -> {}      // one at a time
                                        else -> {
                                            downloadingId = entry.id
                                            phaseLabel = "Downloading"; progress = 0f; errorMsg = null
                                            scope.launch {
                                                val ok = ReshadeDownloader.install(context, entry) { phase, f ->
                                                    activity?.runOnUiThread {
                                                        phaseLabel = if (phase == ReshadeDownloader.Phase.EXTRACT) "Installing" else "Downloading"
                                                        progress = f
                                                    }
                                                }
                                                downloadingId = null
                                                if (ok) {
                                                    installed = installed + entry.id
                                                    onCatalogChanged()      // parent rescans → params seed
                                                    onSelect(entry.id)      // auto-select the freshly installed effect
                                                } else errorMsg = "Failed to download ${entry.name}."
                                            }
                                        }
                                    }
                                },
                            )
                            Divider(color = cs.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close", color = cs.primary) }
            }
        }
    }
}

@Composable
private fun ReshadeNoneRow(isSelected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("None", style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
        Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ReshadeCatalogRow(
    entry: ReshadeCatalogEntry,
    isInstalled: Boolean,
    isSelected: Boolean,
    isBusy: Boolean,
    phaseLabel: String,
    progress: Float?,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val installedBlue = Color(0xFF4FC3F7)
    // Not-installed rows render greyed; tapping them downloads. Installed rows are full-opacity and
    // selectable.
    val contentAlpha = if (isInstalled || isBusy) 1f else 0.5f
    Column(
        Modifier.fillMaxWidth().clickable(enabled = !isBusy, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name, style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface.copy(alpha = contentAlpha),
                )
                val sub = buildString {
                    if (entry.category.isNotBlank()) append(entry.category)
                    if (entry.author.isNotBlank()) { if (isNotEmpty()) append(" · "); append(entry.author) }
                    if (entry.license.isNotBlank()) { if (isNotEmpty()) append(" · "); append(entry.license) }
                }
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = contentAlpha))
                }
                if (entry.description.isNotBlank()) {
                    Text(entry.description, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = contentAlpha))
                }
            }
            when {
                isBusy -> {}
                isInstalled -> Icon(
                    if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null, tint = if (isSelected) installedBlue else cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                else -> Icon(Icons.Filled.CloudDownload, contentDescription = "Download", tint = cs.primary, modifier = Modifier.size(22.dp))
            }
        }
        if (isBusy) {
            Spacer(Modifier.height(6.dp))
            val frac = progress?.coerceIn(0f, 1f) ?: 0f
            val barColor = if (phaseLabel == "Installing") Color(0xFF4CAF50) else cs.primary
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(phaseLabel, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
                Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
            }
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(progress = frac, modifier = Modifier.fillMaxWidth().height(4.dp),
                color = barColor, trackColor = Color(0xFF333333))
        }
    }
}
