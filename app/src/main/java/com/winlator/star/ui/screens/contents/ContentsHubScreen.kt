package com.winlator.star.ui.screens.contents

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.contents.ContentProfile
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.ContentDownloadState
import com.winlator.star.ui.screens.MenuItemDivider
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.outlinedMenuCard

private val InstalledGreen = Color(0xFF37C26B)
private val SavedBlue = Color(0xFF3D9BFF)

private enum class HubTab(val label: String) { DOWNLOAD("Download Components"), MY_FILES("My Files") }

@Composable
fun ContentsHubScreen(vm: ContentsHubViewModel = viewModel()) {
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(HubTab.DOWNLOAD) }

    Column(modifier = Modifier.fillMaxSize().background(cs.background)) {
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = cs.background,
            contentColor = cs.primary,
        ) {
            HubTab.values().forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label, fontWeight = FontWeight.Bold) },
                    selectedContentColor = cs.primary,
                    unselectedContentColor = cs.onSurfaceVariant,
                )
            }
        }
        when (tab) {
            HubTab.DOWNLOAD -> DownloadTab(vm)
            HubTab.MY_FILES -> MyFilesTab(vm)
        }
    }
}

// ── Download tab (master–detail) ───────────────────────────────────────────────
@Composable
private fun DownloadTab(vm: ContentsHubViewModel) {
    val sources by vm.sources.collectAsState()
    val selected by vm.selected.collectAsState()
    val query by vm.query.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLocation by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<RemoteSourceRepository.RemoteSource?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp

        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.width(360.dp).fillMaxSize()) {
                    SourceListPane(vm, sources, query, selected,
                        onAdd = { showAdd = true }, onImport = { showImport = true },
                        onSettings = { showSettings = true }, onMenu = { menuFor = it })
                }
                Divider(modifier = Modifier.fillMaxSize().width(1.dp), color = MaterialTheme.colorScheme.outline)
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    if (selected == null) EmptyDetailPlaceholder() else RepoDetail(vm, showBack = false)
                }
            }
        } else {
            if (selected == null) {
                SourceListPane(vm, sources, query, selected,
                    onAdd = { showAdd = true }, onImport = { showImport = true },
                    onSettings = { showSettings = true }, onMenu = { menuFor = it })
            } else {
                RepoDetail(vm, showBack = true)
            }
        }
    }

    if (showAdd) AddRepoDialog(onDismiss = { showAdd = false }, onAdd = { vm.addSource(it); showAdd = false })
    if (showImport) ImportExportDialog(vm, onDismiss = { showImport = false })
    if (showSettings) SettingsDialog(vm,
        onDismiss = { showSettings = false },
        onLocation = { showSettings = false; showLocation = true })
    if (showLocation) LocationDialog(vm, onDismiss = { showLocation = false })
    menuFor?.let { RepoMenuDialog(vm, it, onDismiss = { menuFor = null }) }
}

@Composable
private fun SourceListPane(
    vm: ContentsHubViewModel,
    sources: List<RemoteSourceRepository.RemoteSource>,
    query: String,
    selected: RemoteSourceRepository.RemoteSource?,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onMenu: (RemoteSourceRepository.RemoteSource) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val searchResults by vm.searchResults.collectAsState()
    val searching by vm.searching.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { vm.setQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search all repositories…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = cs.onSurfaceVariant) },
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))

        if (query.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Select Online Repository", style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.reloadSources(); vm.refreshSelected() }) {
                    Icon(Icons.Filled.Refresh, "Refresh", tint = cs.primary)
                }
                IconButton(onClick = onImport) { Icon(Icons.Filled.FileDownload, "Import list", tint = cs.primary) }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.FolderSpecial, "Settings", tint = cs.primary) }
                IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Add repository", tint = cs.primary) }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(sources, key = { i, s -> "$i-${s.name}-${s.url}" }) { _, source ->
                    RepoCard(source, isSelected = selected?.name == source.name,
                        onClick = { vm.selectSource(source) }, onMenu = { onMenu(source) })
                    Spacer(Modifier.height(10.dp))
                }
            }
        } else {
            if (searching) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = cs.primary) }
            } else {
                Text("${searchResults.size} result${if (searchResults.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Spacer(Modifier.height(8.dp))
                if (searchResults.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No components match “$query”.", color = cs.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(searchResults, key = { i, it -> "$i-${it.sourceName}-${it.type}-${it.downloadUrl}" }) { _, item ->
                            ComponentRow(vm, item, showSource = true)
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoCard(
    source: RemoteSourceRepository.RemoteSource,
    isSelected: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val driverOnly = source.supportedTypes.size == 1 &&
        ContentsTypes.isDriver(source.supportedTypes.first())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isSelected) cs.primary else cs.outline, RoundedCornerShape(16.dp))
            .background(if (isSelected) cs.primary.copy(alpha = 0.12f) else cs.surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Icon(if (driverOnly) Icons.Filled.ViewInAr else Icons.Filled.Folder, null,
            tint = cs.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Text(source.format.name.replace('_', ' '), style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (source.supportedTypes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    source.supportedTypes.take(4).forEach { TypePill(it) }
                    if (source.supportedTypes.size > 4) TypePill("+${source.supportedTypes.size - 4}")
                }
            }
        }
        IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, "Options", tint = cs.onSurfaceVariant) }
        Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant)
    }
}

@Composable
private fun TypePill(label: String) {
    val cs = MaterialTheme.colorScheme
    val driver = ContentsTypes.isDriver(label)
    Text(
        text = label.uppercase(),
        fontSize = 10.sp, fontWeight = FontWeight.Bold,
        color = if (driver) cs.primary else cs.onSurfaceVariant,
        modifier = Modifier
            .background(if (driver) cs.primary.copy(alpha = 0.14f) else cs.onSurface.copy(alpha = 0.06f),
                RoundedCornerShape(20.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyDetailPlaceholder() {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Apps, null, tint = cs.outline, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text("Pick a repository", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text("Choose a source from the list to browse and download its components.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun RepoDetail(vm: ContentsHubViewModel, showBack: Boolean) {
    val cs = MaterialTheme.colorScheme
    val source by vm.selected.collectAsState()
    val types by vm.detailTypes.collectAsState()
    val items by vm.detailItems.collectAsState()
    val loading by vm.detailLoading.collectAsState()
    val keepRaw by vm.keepRaw.collectAsState()
    val src = source ?: return
    var filter by remember(src.name) { mutableStateOf("All") }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (showBack) {
                IconButton(onClick = { vm.selectSource(null) }) { Icon(Icons.Filled.ArrowBack, "Back", tint = cs.onSurface) }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(src.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text("${src.format.name.replace('_', ' ')} · ${items.size} components",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            IconButton(onClick = { vm.refreshSelected() }) { Icon(Icons.Filled.Refresh, "Refresh", tint = cs.primary) }
        }

        Spacer(Modifier.height(6.dp))
        // Keep-raw bar
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, cs.outline, RoundedCornerShape(14.dp))
                .background(cs.surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
            Icon(Icons.Filled.Save, null, tint = cs.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep raw archive", style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, fontWeight = FontWeight.Bold)
                Text("Also save the downloaded file to My Files", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Switch(checked = keepRaw, onCheckedChange = { vm.setKeepRaw(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = cs.primary))
        }

        Spacer(Modifier.height(12.dp))
        // Filter chips
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("All", filter == "All") { filter = "All" }
            types.forEach { t -> FilterPill(t, filter == t) { filter = t } }
        }

        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = cs.primary) }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No components available.", color = cs.onSurfaceVariant)
            }
            else -> {
                val shown = if (filter == "All") items else items.filter { it.type == filter }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { i, it -> "$i-${it.type}-${it.downloadUrl}" }) { _, item ->
                        ComponentRow(vm, item, showSource = false)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (active) Color.Black else cs.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .border(1.dp, if (active) cs.primary else cs.outline, RoundedCornerShape(20.dp))
            .background(if (active) cs.primary else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
    )
}

// ── Component / driver row ─────────────────────────────────────────────────────
@Composable
private fun ComponentRow(vm: ContentsHubViewModel, item: ContentsHubViewModel.CatalogItem, showSource: Boolean) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val installedKeys by vm.installedKeys.collectAsState()
    val savedKeys by vm.savedKeys.collectAsState()
    val registry by ContentDownloadRegistry.states.collectAsState()

    // recompute cheap booleans against the observed sets
    val installed = installedKeys.let { vm.isInstalled(item) }
    val saved = savedKeys.let { vm.isSaved(item) }
    val state: ContentDownloadState? = registry[ContentsInstaller.keyFor(item.type, item.sourceName, item.versionName)]
    val busy = state != null && !state.terminal

    Column(modifier = Modifier.fillMaxWidth()
        .border(1.dp, cs.outline, RoundedCornerShape(14.dp))
        .background(cs.surface, RoundedCornerShape(14.dp))
        .padding(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.size(38.dp)
                .background(if (item.isDriver) cs.primary.copy(alpha = 0.14f) else cs.onSurface.copy(alpha = 0.05f),
                    RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                Icon(typeIcon(item.type), null,
                    tint = if (item.isDriver) cs.primary else cs.onSurface, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (showSource) Text(item.sourceName, style = MaterialTheme.typography.labelSmall, color = cs.primary, fontWeight = FontWeight.Bold)
                Text("${item.displayName} ${item.versionName}".trim(), style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MetaBit(Icons.Filled.Inventory2, item.fileName)
                    item.sizeBytes?.let { MetaBit(Icons.Filled.SdStorage, RemoteSourceRepository.formatFileSize(it)) }
                    item.publishedAt?.let { MetaBit(Icons.Filled.Event, it) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(item.type, cs.onSurface.copy(alpha = 0.08f), cs.onSurfaceVariant, typeIcon(item.type))
                    if (installed) StatusBadge("Installed", InstalledGreen.copy(alpha = 0.16f), InstalledGreen, Icons.Filled.CheckCircle)
                    if (saved) StatusBadge("Saved", SavedBlue.copy(alpha = 0.16f), SavedBlue, Icons.Filled.Save)
                }
            }
        }

        if (state != null) {
            Spacer(Modifier.height(10.dp))
            val label = when (state.phase) {
                ContentDownloadPhase.DOWNLOADING -> "Downloading ${(state.fraction * 100).toInt()}%"
                ContentDownloadPhase.INSTALLING -> "Installing"
                ContentDownloadPhase.DONE -> "Installed"
                ContentDownloadPhase.ERROR -> state.error ?: "Failed"
            }
            if (!state.terminal) {
                LinearProgressIndicator(progress = { state.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(), color = cs.primary)
                Spacer(Modifier.height(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = if (state.phase == ContentDownloadPhase.ERROR) cs.error else cs.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (installed) {
                PrimaryButton("Installed", Icons.Filled.CheckCircle, enabled = false,
                    container = InstalledGreen.copy(alpha = 0.16f), content = InstalledGreen, modifier = Modifier.weight(1f)) {}
            } else {
                PrimaryButton(if (busy) "Working…" else "Download & Install", Icons.Filled.Download,
                    enabled = !busy, container = cs.primary, content = Color.Black, modifier = Modifier.weight(1f)) {
                    ContentsInstaller.install(
                        context.applicationContext, item.type, item.sourceName, item.versionName,
                        item.downloadUrl, vm.keepRaw.value,
                    ) { vm.refreshStatus(); vm.refreshFolders() }
                }
            }
        }
    }
}

@Composable
private fun MetaBit(icon: ImageVector, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusBadge(text: String, bg: Color, fg: Color, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.background(bg, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PrimaryButton(
    text: String, icon: ImageVector, enabled: Boolean, container: Color, content: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .background(container.copy(alpha = if (enabled) 1f else 0.9f), RoundedCornerShape(11.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp)) {
        Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = content, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

// ── My Files tab ───────────────────────────────────────────────────────────────
@Composable
private fun MyFilesTab(vm: ContentsHubViewModel) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val folders by vm.savedFolders.collectAsState()
    val baseDisplay by vm.baseDisplay.collectAsState()
    var showLocation by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateOf(setOf<String>()) }

    val installPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val type = if (name.endsWith(".wcp", true) || name.endsWith(".tzst", true)) "" else ContentsTypes.GPU_DRIVERS
                ContentsInstaller.installFromFile(context.applicationContext, type, name, uri) { vm.refreshStatus() }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        // Info bar
        Row(modifier = Modifier.fillMaxWidth()
            .background(cs.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp)) {
            Icon(Icons.Filled.Inventory2, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Text("Raw .wcp / .zip / .tzst archives you chose to keep, filed by component type. Re-install offline, share, or delete.",
                style = MaterialTheme.typography.bodySmall, color = cs.primary)
        }
        Spacer(Modifier.height(12.dp))
        // Path bar
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, cs.outline, RoundedCornerShape(14.dp))
                .background(cs.surface, RoundedCornerShape(14.dp))
                .clickable { showLocation = true }
                .padding(12.dp)) {
            Icon(Icons.Filled.FolderSpecial, null, tint = cs.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("SAVE LOCATION", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Text(baseDisplay, style = MaterialTheme.typography.bodySmall, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { showLocation = true }) { Text("Change", color = cs.onSurface) }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Install content from file…", Icons.Filled.FolderOpen, enabled = true,
            container = cs.onSurface.copy(alpha = 0.06f), content = cs.onSurface, modifier = Modifier.fillMaxWidth()) {
            installPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            })
        }
        Spacer(Modifier.height(14.dp))

        if (folders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saved archives yet.\nEnable “Keep raw archive” before downloading.",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(folders.keys.toList(), key = { i, t -> "$i-$t" }) { _, type ->
                    FolderCard(vm, type, folders[type].orEmpty(),
                        open = type in expanded.value,
                        onToggle = {
                            expanded.value = if (type in expanded.value) expanded.value - type else expanded.value + type
                        })
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
    if (showLocation) LocationDialog(vm, onDismiss = { showLocation = false })
}

@Composable
private fun FolderCard(
    vm: ContentsHubViewModel, type: String, files: List<ComponentLibrary.SavedFile>,
    open: Boolean, onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()
        .border(1.dp, cs.outline, RoundedCornerShape(14.dp))
        .background(cs.surface, RoundedCornerShape(14.dp))) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(15.dp)) {
            Icon(if (ContentsTypes.isDriver(type)) Icons.Filled.ViewInAr else Icons.Filled.Folder, null,
                tint = cs.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(13.dp))
            Text(type, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${files.size}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                modifier = Modifier.background(cs.onSurface.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 9.dp, vertical = 2.dp))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ExpandMore, null, tint = cs.onSurfaceVariant)
        }
        if (open) {
            Divider(color = cs.outline)
            files.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Icon(if (f.name.endsWith(".zip", true)) Icons.Filled.FolderZip else Icons.Filled.Inventory2,
                        null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodySmall, color = cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(RemoteSourceRepository.formatFileSize(f.sizeBytes), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        ContentsInstaller.installFromFile(context.applicationContext, f.type, f.name, f.uri) { vm.refreshStatus() }
                    }) { Icon(Icons.Filled.InstallDesktop, "Install", tint = cs.primary) }
                    IconButton(onClick = { shareArchive(context, f) }) { Icon(Icons.Filled.Share, "Share", tint = cs.onSurfaceVariant) }
                    IconButton(onClick = { vm.deleteSaved(f) }) { Icon(Icons.Filled.DeleteOutline, "Delete", tint = cs.onSurfaceVariant) }
                }
            }
        }
    }
}

private fun shareArchive(context: Context, f: ComponentLibrary.SavedFile) {
    runCatching {
        val shareUri = if (f.uri.scheme == "content") f.uri else {
            val file = java.io.File(f.uri.path!!)
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.tileprovider", file)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${f.name}"))
    }
}

// ── Type icon ──────────────────────────────────────────────────────────────────
private fun typeIcon(type: String): ImageVector = when {
    ContentsTypes.isDriver(type) -> Icons.Filled.ViewInAr
    type.equals("DXVK", true) -> Icons.Filled.GridView
    type.equals("VKD3D", true) -> Icons.Filled.Widgets
    type.equals("Box64", true) -> Icons.Filled.Memory
    type.equals("WOWBox64", true) -> Icons.Filled.DeveloperBoard
    type.equals("FEXCore", true) -> Icons.Filled.Dns
    type.equals("Wine", true) -> Icons.Filled.LocalBar
    type.equals("Proton", true) -> Icons.Filled.Science
    else -> Icons.Filled.Extension
}

// ── Dialogs ────────────────────────────────────────────────────────────────────
@Composable
private fun AddRepoDialog(onDismiss: () -> Unit, onAdd: (RemoteSourceRepository.RemoteSource) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(RemoteSourceRepository.SourceFormat.WCP_JSON) }
    var formatMenu by remember { mutableStateOf(false) }

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerHigh,
        title = { Text("Add repository", color = cs.onSurface) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Paste any component or GPU-driver source — it joins the list like a built-in.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true,
                    label = { Text("URL (repo, releases API, or JSON)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Source format", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Box {
                    TextButton(onClick = { formatMenu = true }) { Text(format.name.replace('_', ' '), color = cs.primary) }
                    androidx.compose.material3.DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false },
                        modifier = Modifier.outlinedMenuCard()) {
                        RemoteSourceRepository.SourceFormat.values().forEach { fmt ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(fmt.name.replace('_', ' ')) },
                                onClick = { format = fmt; formatMenu = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && url.isNotBlank()) {
                    onAdd(RemoteSourceRepository.RemoteSource(
                        name = name.trim(), url = url.trim(), format = format,
                        supportedTypes = emptyList(), isCustom = true))
                }
            }) { Text("Add source", color = cs.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = cs.primary) } },
    )
}

@Composable
private fun RepoMenuDialog(
    vm: ContentsHubViewModel, source: RemoteSourceRepository.RemoteSource, onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerHigh,
        title = { Text(source.name, color = cs.onSurface) },
        text = {
            Column {
                MenuRow(Icons.Filled.Apps, "Browse components") { onDismiss(); vm.selectSource(source) }
                MenuItemDivider()
                MenuRow(Icons.Filled.OpenInNew, "Open source page") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.browseUrl(source)))) }
                    onDismiss()
                }
                MenuItemDivider()
                MenuRow(Icons.Filled.Delete, if (source.isCustom) "Remove repository" else "Hide default repository",
                    tint = cs.error) { vm.removeSource(source); onDismiss() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = cs.primary) } },
    )
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ImportExportDialog(vm: ContentsHubViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                runCatching {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@let
                    vm.importRepoListJson(json, merge = true)
                }
                onDismiss()
            }
        }
    }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerHigh,
        title = { Text("Import / export sources", color = cs.onSurface) },
        text = {
            Column {
                MenuRow(Icons.Filled.FileDownload, "Import list from file…") {
                    importer.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                    })
                }
                MenuItemDivider()
                MenuRow(Icons.Filled.FileUpload, "Export current list") {
                    runCatching {
                        val json = vm.exportRepoListJson()
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"; putExtra(Intent.EXTRA_TEXT, json)
                        }
                        context.startActivity(Intent.createChooser(share, "Export sources"))
                    }
                    onDismiss()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = cs.primary) } },
    )
}

@Composable
private fun SettingsDialog(vm: ContentsHubViewModel, onDismiss: () -> Unit, onLocation: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val keepRaw by vm.keepRaw.collectAsState()
    val baseDisplay by vm.baseDisplay.collectAsState()
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerHigh,
        title = { Text("Contents settings", color = cs.onSurface) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keep raw archive by default", style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                        Text("Save every download to My Files", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Switch(checked = keepRaw, onCheckedChange = { vm.setKeepRaw(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = cs.primary))
                }
                MenuItemDivider()
                MenuRow(Icons.Filled.FolderSpecial, "Save location") { onLocation() }
                Text(baseDisplay, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp))
                MenuItemDivider()
                MenuRow(Icons.Filled.Restore, "Restore default repositories") { vm.restoreDefaultSources(); onDismiss() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = cs.primary) } },
    )
}

@Composable
private fun LocationDialog(vm: ContentsHubViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val label = uri.lastPathSegment?.substringAfterLast(':') ?: "Custom folder"
                vm.library.setTreeBase(uri, label)
            }
            vm.refreshBase(); vm.refreshFolders(); onDismiss()
        }
    }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerHigh,
        title = { Text("Component save location", color = cs.onSurface) },
        text = {
            Column {
                Text("Raw archives are filed under <folder>/components/<type>/ — next to Bannerlator's logs and saves.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                MenuRow(Icons.Filled.Folder, "Downloads › bannerlator (default)") {
                    vm.library.setDefaultBase(); vm.refreshBase(); vm.refreshFolders(); onDismiss()
                }
                MenuItemDivider()
                MenuRow(Icons.Filled.FolderSpecial, "App-private storage") {
                    vm.library.setFileBase(vm.library.appPrivateBasePath()); vm.refreshBase(); vm.refreshFolders(); onDismiss()
                }
                MenuItemDivider()
                MenuRow(Icons.Filled.FolderOpen, "Choose another folder…") { treePicker.launch(null) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = cs.primary) } },
    )
}
