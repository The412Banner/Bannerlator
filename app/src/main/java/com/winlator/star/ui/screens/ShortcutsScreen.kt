package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.winlator.star.ui.LocalTopBarActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.preference.PreferenceManager
import androidx.compose.ui.viewinterop.AndroidView
import com.winlator.star.R
import com.winlator.star.SettingsFragment
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.XrActivity
import com.winlator.star.box64.Box64Preset
import com.winlator.star.box64.Box64PresetManager
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.reshade.ReshadeManager
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.DefaultVersion
import com.winlator.star.core.FileUtils
import com.winlator.star.core.KeyValueSet
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WineInfo
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.fexcore.FEXCorePresetManager
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.midi.MidiManager
import com.winlator.star.store.StarLaunchBridge
import com.winlator.star.ui.theme.Divider as DividerColor
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.Surface as SurfaceColor
import com.winlator.star.ui.theme.SurfaceVariant as SurfaceVariantColor
import com.winlator.star.widget.CPUListView
import com.winlator.star.widget.EnvVarsView
import com.winlator.star.winhandler.WinHandler
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import java.lang.reflect.Field
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ShortcutsScreen(vm: ShortcutsViewModel = viewModel()) {
    val shortcuts by vm.shortcuts.collectAsState(initial = emptyList())
    val sortOrder by vm.sortOrder.collectAsState()
    val isGridView by vm.isGridView.collectAsState()
    val context = LocalContext.current
    val activity = context as Activity

    var confirmRemove by remember { mutableStateOf<Shortcut?>(null) }
    var cloneTarget by remember { mutableStateOf<Shortcut?>(null) }
    var settingsShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var propertiesShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showImportContainerPicker by remember { mutableStateOf(false) }
    var pendingImportContainerIndex by remember { mutableStateOf(-1) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDialogName by remember { mutableStateOf("") }
    var renameDialogContainerIndex by remember { mutableStateOf(-1) }
    var scrapeTarget by remember { mutableStateOf<Shortcut?>(null) }
    val scrapeCovers = remember { mutableStateListOf<Pair<Bitmap, String>>() }
    var scrapeLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (pendingImportContainerIndex >= 0) {
            val result = vm.importShortcut(pendingImportContainerIndex, uri, context)
            when (result) {
                is ImportResult.Success -> {
                    renameDialogContainerIndex = pendingImportContainerIndex
                    renameDialogName = result.shortcutName
                    showRenameDialog = true
                }
                is ImportResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
            pendingImportContainerIndex = -1
        }
    }

    val topBarActions = LocalTopBarActions.current
    // LaunchedEffect — not SideEffect — so this runs in the same dispatcher queue as
    // MainActivity's route-change clear (which is a LaunchedEffect). Parent enqueues
    // first and runs first (clears); we enqueue second and run after (sets). A
    // SideEffect would run synchronously during commit, getting steamrolled by the
    // parent's clear when it fires post-commit.
    LaunchedEffect(isGridView) {
        topBarActions.value = {
            IconButton(onClick = { vm.setGridView(!isGridView) }) {
                Icon(
                    imageVector = if (isGridView) Icons.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = if (isGridView) "List view" else "Grid view",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.Filled.SwapVert, contentDescription = "Sort", tint = androidx.compose.ui.graphics.Color.White)
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    val orders = listOf(
                        ShortcutSortOrder.NAME_ASC  to "Name A→Z",
                        ShortcutSortOrder.NAME_DESC to "Name Z→A",
                        ShortcutSortOrder.CONTAINER to "Container",
                    )
                    orders.forEach { (order, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    color = if (sortOrder == order)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { vm.setSortOrder(order); showSortMenu = false },
                        )
                    }
                }
            }
        }
    }

    // Refresh list whenever this screen resumes (consistent with ContainersScreen and SavesScreen)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (shortcuts.isEmpty()) {
                Text(
                    text = "No shortcuts yet.",
                    color = OnSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                AnimatedContent(targetState = isGridView, label = "layout") { grid ->
                    if (grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 120.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(shortcuts, key = { it.file.path }) { shortcut ->
                                ShortcutGridItem(
                                    shortcut = shortcut,
                                    onRun = { runShortcut(activity, shortcut) },
                                    onSettings = { settingsShortcut = shortcut },
                                    onRemove = { confirmRemove = shortcut },
                                    onClone = { cloneTarget = shortcut },
                                    onAddToHome = { addToHomeScreen(context, shortcut) },
                                    onExport = { exportShortcut(context, shortcut) },
                                    onProperties = { propertiesShortcut = shortcut },
                                    onScrapeCover = {
                                        scrapeTarget = shortcut
                                        scrapeCovers.clear()
                                        scrapeLoading = true
                                        scope.launch(Dispatchers.IO) {
                                            val json = StarLaunchBridge.sgdbFetchGridsJson(shortcut.name)
                                            val covers = mutableListOf<Pair<Bitmap, String>>()
                                            try {
                                                val arr = JSONArray(json)
                                                for (i in 0 until arr.length()) {
                                                    val obj = arr.getJSONObject(i)
                                                    val thumbUrl = obj.optString("thumb", "")
                                                    val fullUrl = obj.optString("url", "")
                                                    if (thumbUrl.isNotEmpty() && fullUrl.isNotEmpty()) {
                                                        val conn = java.net.URL(thumbUrl).openConnection() as java.net.HttpURLConnection
                                                        conn.connectTimeout = 10000
                                                        conn.readTimeout = 10000
                                                        val bmp = BitmapFactory.decodeStream(conn.inputStream)
                                                        conn.disconnect()
                                                        if (bmp != null) covers.add(bmp to fullUrl)
                                                    }
                                                }
                                            } catch (_: Exception) {}
                                            withContext(Dispatchers.Main) {
                                                scrapeCovers.clear()
                                                scrapeCovers.addAll(covers)
                                                scrapeLoading = false
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(shortcuts, key = { it.file.path }) { shortcut ->
                                val itemRun = { runShortcut(activity, shortcut) }
                                val itemSettings = { settingsShortcut = shortcut }
                                val itemRemove = { confirmRemove = shortcut }
                                val itemClone = { cloneTarget = shortcut }
                                val itemAddToHome = { addToHomeScreen(context, shortcut) }
                                val itemExport = { exportShortcut(context, shortcut) }
                                val itemProperties = { propertiesShortcut = shortcut }
                                ShortcutItemLayoutL(
                                    shortcut = shortcut,
                                    onRun = itemRun,
                                    onSettings = itemSettings,
                                    onRemove = itemRemove,
                                    onClone = itemClone,
                                    onAddToHome = itemAddToHome,
                                    onExport = itemExport,
                                    onProperties = itemProperties,
                                )
                                Divider(color = DividerColor)
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFF0055FF), RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable { showImportContainerPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add Shortcut",
                    tint = Color(0xFF0055FF),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }

    // Import container picker
    if (showImportContainerPicker) {
        val containers = vm.containers()
        AlertDialog(
            onDismissRequest = { showImportContainerPicker = false },
            title = { Text("Select container") },
            text = {
                Column {
                    if (containers.isEmpty()) {
                        Text("No containers found.", color = OnSurfaceVariant)
                    } else {
                        containers.forEachIndexed { index, c ->
                            Text(
                                text = c.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showImportContainerPicker = false
                                        pendingImportContainerIndex = index
                                        importFileLauncher.launch("*/*")
                                    }
                                    .padding(vertical = 12.dp),
                                color = OnSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showImportContainerPicker = false }) { Text("Cancel") } },
        )
    }

    // Rename after import
    if (showRenameDialog) {
        var newName by remember { mutableStateOf(renameDialogName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Shortcut") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Shortcut name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty()) {
                        vm.renameImportedShortcut(renameDialogContainerIndex, renameDialogName, name)
                    }
                    showRenameDialog = false
                    Toast.makeText(context, "Shortcut imported.", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    Toast.makeText(context, "Shortcut imported.", Toast.LENGTH_SHORT).show()
                }) { Text("Skip") }
            },
        )
    }

    // Remove confirmation
    confirmRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove shortcut?") },
            text = { Text("Remove \"${s.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = vm.remove(s, context)
                    confirmRemove = null
                    Toast.makeText(
                        context,
                        if (ok) "Shortcut removed." else "Failed to remove shortcut.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }

    // Clone-to-container dialog
    cloneTarget?.let { s ->
        val containers = vm.containers()
        AlertDialog(
            onDismissRequest = { cloneTarget = null },
            title = { Text("Select container") },
            text = {
                Column {
                    containers.forEach { c ->
                        Text(
                            text = c.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val ok = s.cloneToContainer(c)
                                    cloneTarget = null
                                    Toast.makeText(
                                        context,
                                        if (ok) "Shortcut cloned." else "Failed to clone shortcut.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    if (ok) vm.refresh()
                                }
                                .padding(vertical = 12.dp),
                            color = OnSurface,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { cloneTarget = null }) { Text("Cancel") } },
        )
    }

    // Shortcut properties dialog
    propertiesShortcut?.let { s ->
        val playtimePrefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
        val playtimeKey = "${s.name}_playtime"
        val playCountKey = "${s.name}_play_count"
        val totalMs = playtimePrefs.getLong(playtimeKey, 0L)
        val playCount = playtimePrefs.getInt(playCountKey, 0)
        val seconds = (totalMs / 1000) % 60
        val minutes = (totalMs / (1000 * 60)) % 60
        val hours   = (totalMs / (1000 * 60 * 60)) % 24
        val days    = (totalMs / (1000 * 60 * 60 * 24))
        val formatted = String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds)
        var didReset by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { propertiesShortcut = null },
            title = { Text("Properties") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (didReset) "Number of times played: 0" else "Number of times played: $playCount")
                    Text(if (didReset) "Playtime: 0d 00h 00m 00s" else "Playtime: $formatted")
                    Button(
                        onClick = {
                            playtimePrefs.edit().remove(playtimeKey).remove(playCountKey).apply()
                            didReset = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Reset Properties") }
                }
            },
            confirmButton = { TextButton(onClick = { propertiesShortcut = null }) { Text("Close") } }
        )
    }

    // Scrape cover dialog
    val sc = scrapeTarget
    if (sc != null) {
        AlertDialog(
            onDismissRequest = { scrapeTarget = null },
            title = { Text("Scrape cover for \"${sc.name}\"") },
            text = {
                if (scrapeLoading) {
                    Text("Searching SteamGridDB...", color = OnSurfaceVariant)
                } else if (scrapeCovers.isEmpty()) {
                    Text("No covers found.", color = OnSurfaceVariant)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        scrapeCovers.forEach { (bmp, url) ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        scope.launch(Dispatchers.IO) {
                                            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                            conn.connectTimeout = 15000
                                            conn.readTimeout = 20000
                                            val full = BitmapFactory.decodeStream(conn.inputStream)
                                            conn.disconnect()
                                            if (full != null) {
                                                sc.saveCustomCoverArt(full)
                                                sc.icon = full
                                                val iconsDir = sc.container.getIconsDir(64)
                                                if (iconsDir != null) {
                                                    if (!iconsDir.exists()) iconsDir.mkdirs()
                                                    val iconName = kotlin.runCatching { sc.file.readLines().firstOrNull { it.startsWith("Icon=") }?.substringAfter("Icon=")?.trim() }.getOrNull() ?: sc.name
                                                    FileUtils.saveBitmapToFile(full, File(iconsDir, iconName + ".png"))
                                                }
                                            }
                                            withContext(Dispatchers.Main) {
                                                scrapeTarget = null
                                                vm.reloadShortcut(sc.file.path, full)
                                                Toast.makeText(context, "Cover saved.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 120.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { scrapeTarget = null }) { Text("Cancel") } },
        )
    }

    // Compose shortcut settings dialog
    settingsShortcut?.let { s ->
        ShortcutSettingsDialogScreen(
            shortcut = s,
            onDismiss = { settingsShortcut = null; vm.refresh() }
        )
    }
}

// Game-list card (poster cover + primary chips + muted secondary line, issue #19). A tall
// 3:4 cover on the left, name + container · resolution subtitle in the middle. Components are
// split by how often you check them: renderer, DXVK and frame-gen are bright chips; driver,
// VKD3D and x86 backend sit on a calm muted line with a colour dot each. "Calm but complete."
@Composable
private fun ShortcutItemLayoutL(
    shortcut: Shortcut,
    onRun: () -> Unit,
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    onClone: () -> Unit,
    onAddToHome: () -> Unit,
    onExport: () -> Unit,
    onProperties: () -> Unit,
) {
    val container = shortcut.container
    val res = LocalContext.current.resources

    // Resolved component metadata (shortcut override → container default).
    val resolution = shortcut.getExtra("screenSize", container?.getScreenSize() ?: "")
    val driverCfg = shortcut.getExtra("graphicsDriverConfig", container?.getGraphicsDriverConfig() ?: "")
    val driverLabel = if (driverCfg.isNotEmpty()) GraphicsDriverConfigDialog.getVersion(driverCfg) else ""
    val dxwrapperCfg = shortcut.getExtra("dxwrapperConfig", container?.getDXWrapperConfig() ?: "")
    val (dxvkVersion, vkd3dVersion) = parseDxwrapperConfig(dxwrapperCfg)

    val rendererLabel = rendererLabelOf(shortcut.getExtra("renderer", container?.renderer ?: ""))
    val frameGenLabel = frameGenLabelOf(shortcut.getExtra("frameGenEngine", container?.frameGenEngine ?: "off"))
    // x86 backend (FEXCore / Box64). Preset suffix (e.g. "· TSO") deferred — it needs
    // the async Box64/FEXCore preset managers, too heavy to resolve per list-card.
    val backendLabel = run {
        val id = shortcut.getExtra("emulator", container?.emulator ?: "")
        res.getStringArray(R.array.emulator_entries)
            .firstOrNull { StringUtils.parseIdentifier(it) == id } ?: ""
    }

    val subtitle = listOf(container?.name ?: "", resolution).filter { it.isNotEmpty() }.joinToString(" · ")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor)
            .clickable(onClick = onRun)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // 3:4 poster cover (same as layout A); fall back to a glyph tile.
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceVariantColor),
            contentAlignment = Alignment.Center,
        ) {
            if (shortcut.icon != null) {
                Image(
                    bitmap = shortcut.icon.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortcut.name,
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Component specs: bright primary chips (renderer · DXVK · frame-gen) then a
            // muted secondary dot-line (driver · VKD3D · backend). Shared with Containers.
            SpecChipRows(
                rendererLabel = rendererLabel,
                dxvkVersion = dxvkVersion,
                frameGenLabel = frameGenLabel,
                driverLabel = driverLabel,
                vkd3dVersion = vkd3dVersion,
                backendLabel = backendLabel,
            )
        }
        ShortcutOverflowButton(
            onSettings = onSettings,
            onRemove = onRemove,
            onClone = onClone,
            onAddToHome = onAddToHome,
            onExport = onExport,
            onProperties = onProperties,
        )
    }
}

// Shared overflow (⋮) button + menu for the list-view cards.
@Composable
private fun ShortcutOverflowButton(
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    onClone: () -> Unit,
    onAddToHome: () -> Unit,
    onExport: () -> Unit,
    onProperties: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = OnSurfaceVariant)
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                onClick = { menuExpanded = false; onSettings() },
            )
            DropdownMenuItem(
                text = { Text("Remove") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { menuExpanded = false; onRemove() },
            )
            DropdownMenuItem(
                text = { Text("Clone to container") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { menuExpanded = false; onClone() },
            )
            DropdownMenuItem(
                text = { Text("Add to home screen") },
                leadingIcon = { Icon(Icons.Filled.AddToHomeScreen, null) },
                onClick = { menuExpanded = false; onAddToHome() },
            )
            DropdownMenuItem(
                text = { Text("Export") },
                leadingIcon = { Icon(Icons.Filled.Upload, null) },
                onClick = { menuExpanded = false; onExport() },
            )
            DropdownMenuItem(
                text = { Text("Properties") },
                leadingIcon = { Icon(Icons.Filled.Info, null) },
                onClick = { menuExpanded = false; onProperties() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutGridItem(
    shortcut: Shortcut,
    onRun: () -> Unit,
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    onClone: () -> Unit,
    onAddToHome: () -> Unit,
    onExport: () -> Unit,
    onProperties: () -> Unit,
    onScrapeCover: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceColor)
            .border(
                width = 2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF0033AA), Color(0xFF0055FF), Color(0xFF4488FF)),
                ),
                shape = RoundedCornerShape(8.dp),
            )
            .combinedClickable(onClick = onRun, onLongClick = { menuExpanded = true }),
    ) {
        // Cover image fills the entire tile
        if (shortcut.icon != null) {
            Image(
                bitmap = shortcut.icon.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            )
        }

        // Gradient scrim + name/container at the bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Column {
                Text(
                    text = shortcut.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!shortcut.container?.name.isNullOrEmpty()) {
                    Text(
                        text = shortcut.container?.name ?: "",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Long-press context menu
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Filled.Settings, null) }, onClick = { menuExpanded = false; onSettings() })
            DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menuExpanded = false; onRemove() })
            DropdownMenuItem(text = { Text("Clone to container") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menuExpanded = false; onClone() })
            DropdownMenuItem(text = { Text("Add to home screen") }, leadingIcon = { Icon(Icons.Filled.AddToHomeScreen, null) }, onClick = { menuExpanded = false; onAddToHome() })
            DropdownMenuItem(text = { Text("Export") }, leadingIcon = { Icon(Icons.Filled.Upload, null) }, onClick = { menuExpanded = false; onExport() })
            DropdownMenuItem(text = { Text("Scrape cover") }, leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color(0xFF1C85FE)) }, onClick = { menuExpanded = false; onScrapeCover() })
            DropdownMenuItem(text = { Text("Properties") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { menuExpanded = false; onProperties() })
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ShortcutSettingsDialogScreen(shortcut: Shortcut, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = context.resources

    // Async-loaded state
    var isArm64EC by remember { mutableStateOf(false) }
    var box64Versions by remember { mutableStateOf(listOf<String>()) }
    var box64Presets by remember { mutableStateOf(listOf<Box64Preset>()) }
    var fexCoreVersions by remember { mutableStateOf(listOf<String>()) }
    var fexCorePresets by remember { mutableStateOf(listOf<FEXCorePreset>()) }
    var controlsProfiles by remember { mutableStateOf(listOf<ControlsProfile>()) }
    var midiList by remember { mutableStateOf(listOf<String>()) }

    // Screen size
    val screenSizeEntries = remember { res.getStringArray(R.array.screen_size_entries).toList() }
    val rawScreenSize = remember { shortcut.getExtra("screenSize", shortcut.container.getScreenSize()) }
    var selectedScreenSize by remember {
        val display = screenSizeEntries.firstOrNull {
            StringUtils.parseIdentifier(it).equals(rawScreenSize, ignoreCase = true)
        }
        mutableStateOf(display ?: "Custom")
    }
    var customWidth by remember {
        mutableStateOf(if (rawScreenSize.contains("x")) rawScreenSize.substringBefore("x") else "800")
    }
    var customHeight by remember {
        mutableStateOf(if (rawScreenSize.contains("x")) rawScreenSize.substringAfter("x") else "600")
    }

    // Graphics driver
    val graphicsDriverEntries = remember { res.getStringArray(R.array.graphics_driver_entries).toList() }
    var selectedGfxDriver by remember {
        val id = shortcut.getExtra("graphicsDriver", shortcut.container.graphicsDriver)
        mutableStateOf(graphicsDriverEntries.firstOrNull { StringUtils.parseIdentifier(it) == id }
            ?: graphicsDriverEntries.firstOrNull() ?: id)
    }
    var graphicsDriverConfig by remember {
        mutableStateOf(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()))
    }

    // DX wrapper
    val dxWrapperEntries = remember { res.getStringArray(R.array.dxwrapper_entries).toList() }
    var selectedDxWrapper by remember {
        val id = shortcut.getExtra("dxwrapper", shortcut.container.getDXWrapper())
        mutableStateOf(dxWrapperEntries.firstOrNull { StringUtils.parseIdentifier(it) == id }
            ?: dxWrapperEntries.firstOrNull() ?: id)
    }
    var dxWrapperConfig by remember {
        mutableStateOf(shortcut.getExtra("dxwrapperConfig", shortcut.container.getDXWrapperConfig()))
    }

    // Renderer (host: OpenGL / Vulkan) — per-game override, defaults to the container's value.
    var selectedRenderer by remember {
        val id = shortcut.getExtra("renderer", shortcut.container.renderer)
        mutableStateOf(
            when {
                id.equals("vulkan", ignoreCase = true) -> "Vulkan"
                id.equals("surfaceflinger", ignoreCase = true) -> "SurfaceFlinger"
                else -> "OpenGL"
            }
        )
    }

    // Render scale (supersampling) — per-game override, defaults to the container's "renderScale"
    // extra. Stored via the shortcut "renderScale" extra. "1.0" = Off.
    var renderScale by remember {
        mutableStateOf(shortcut.getExtra("renderScale", shortcut.container.getExtra("renderScale", "1.0")))
    }

    // Frame Generation engine (off / bionic / lsfg) — per-game override.
    val fgEngines = remember { listOf("off", "bionic", "lsfg") }
    var frameGenEngine by remember {
        mutableStateOf(shortcut.getExtra("frameGenEngine", shortcut.container.frameGenEngine))
    }
    val lsfgDllAvailable = remember { File(context.filesDir, "lsfg-vk/Lossless.dll").isFile }

    // FPS limiter — per-game override.
    var fpsLimiterEnabled by remember {
        mutableStateOf(
            shortcut.getExtra("fpsLimiterEnabled", if (shortcut.container.isFpsLimiterEnabled) "1" else "0") == "1"
        )
    }

    // Audio driver
    val audioDriverEntries = remember { res.getStringArray(R.array.audio_driver_entries).toList() }
    var selectedAudioDriver by remember {
        val id = shortcut.getExtra("audioDriver", shortcut.container.audioDriver)
        mutableStateOf(audioDriverEntries.firstOrNull { StringUtils.parseIdentifier(it) == id }
            ?: audioDriverEntries.firstOrNull() ?: id)
    }

    // Emulator
    val emulatorEntries = remember { res.getStringArray(R.array.emulator_entries).toList() }
    var selectedEmulator by remember {
        val id = shortcut.getExtra("emulator", shortcut.container.emulator)
        mutableStateOf(emulatorEntries.firstOrNull { StringUtils.parseIdentifier(it) == id }
            ?: emulatorEntries.firstOrNull() ?: id)
    }

    // MIDI
    var selectedMidi by remember {
        mutableStateOf(shortcut.getExtra("midiSoundFont", shortcut.container.getMIDISoundFont()))
    }

    // Basic text fields
    var name by remember { mutableStateOf(shortcut.name) }
    var execArgs by remember { mutableStateOf(shortcut.getExtra("execArgs")) }
    var lcAll by remember { mutableStateOf(shortcut.getExtra("lc_all", shortcut.container.getLC_ALL())) }

    // Checkboxes / switches
    var fullscreenStretched by remember { mutableStateOf(shortcut.getExtra("fullscreenStretched", "0") == "1") }
    // Close the session when this game exits — per-game override, defaults to the container's setting (ON).
    var autoCloseOnExit by remember {
        mutableStateOf(shortcut.getExtra("autoCloseOnExit", shortcut.container.getExtra("autoCloseOnExit", "1")) == "1")
    }
    var exclusiveXInput by remember {
        val v = shortcut.getExtra("exclusiveXInput")
        mutableStateOf(if (v.isEmpty()) shortcut.container.isExclusiveXInput else v == "1")
    }
    val initialInputType = remember {
        shortcut.getExtra("inputType", shortcut.container.getInputType().toString()).toIntOrNull()
            ?: shortcut.container.getInputType()
    }
    var enableXInput by remember { mutableStateOf((initialInputType and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) != 0) }
    var enableDInput by remember { mutableStateOf((initialInputType and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) != 0) }
    var disabledXInput by remember { mutableStateOf(shortcut.getExtra("disableXinput", "0") == "1") }
    var simTouchScreen by remember { mutableStateOf(shortcut.getExtra("simTouchScreen", "0") == "1") }

    // Num controllers
    val numControllersEntries = remember { res.getStringArray(R.array.num_controllers_entries).toList() }
    var selectedNumControllers by remember {
        val n = (shortcut.getExtra("numControllers", "1").toIntOrNull() ?: 1).coerceIn(1, numControllersEntries.size)
        mutableStateOf(numControllersEntries.getOrElse(n - 1) { numControllersEntries.first() })
    }

    // Box64 / FEXCore / controls
    var selectedBox64Version by remember {
        mutableStateOf(shortcut.getExtra("box64Version", shortcut.container.getBox64Version()))
    }
    var selectedBox64PresetIndex by remember { mutableIntStateOf(0) }
    var selectedFexCoreVersion by remember {
        mutableStateOf(shortcut.getExtra("fexcoreVersion", shortcut.container.getFEXCoreVersion()))
    }
    var selectedFexCorePresetIndex by remember { mutableIntStateOf(0) }
    var selectedControlsProfileIndex by remember { mutableIntStateOf(0) }

    // Startup selection
    val startupSelectionEntries = remember { res.getStringArray(R.array.startup_selection_entries).toList() }
    var selectedStartupSelection by remember {
        val idx = (shortcut.getExtra("startupSelection", shortcut.container.getStartupSelection().toString())
            .toIntOrNull() ?: 0).coerceIn(0, startupSelectionEntries.lastIndex)
        mutableStateOf(startupSelectionEntries.getOrElse(idx) { startupSelectionEntries.first() })
    }

    // Sharpness
    val sharpnessEffectEntries = remember { res.getStringArray(R.array.vkbasalt_sharpness_entries).toList() }
    var selectedSharpnessEffect by remember {
        val v = shortcut.getExtra("sharpnessEffect", "None")
        mutableStateOf(sharpnessEffectEntries.firstOrNull { it == v } ?: sharpnessEffectEntries.firstOrNull() ?: v)
    }
    var sharpnessLevel by remember {
        mutableIntStateOf(shortcut.getExtra("sharpnessLevel", "100").toIntOrNull() ?: 100)
    }
    var sharpnessDenoise by remember {
        mutableIntStateOf(shortcut.getExtra("sharpnessDenoise", "100").toIntOrNull() ?: 100)
    }

    // ReShade effect (vkBasalt drop-in). Scan the user folder once; "None" + each effect subfolder.
    // Per-uniform values are seeded from the saved JSON (shortcut override → container default) and,
    // when the effect changes, from the reflected .fx defaults. Serialized back to a JSON extra on save.
    // Mutable so a catalog download can rescan the drop-in folder and surface the new effect.
    var reshadeEffects by remember { mutableStateOf(ReshadeManager.scanEffects(context)) }
    val reshadeEffectNames = remember(reshadeEffects) { listOf("None") + reshadeEffects.map { it.name } }
    var selectedReshadeEffect by remember {
        val v = shortcut.getExtra("reshadeEffect", shortcut.container.getReshadeEffect())
        mutableStateOf(reshadeEffectNames.firstOrNull { it == v } ?: "None")
    }
    val reshadeParamValues = remember { mutableStateMapOf<String, Float>() }
    // Seed values: saved JSON wins; otherwise the .fx-reflected defaults for the selected effect.
    // Keyed on reshadeEffects too, so a rescan (post-download) re-seeds with newly-available params.
    LaunchedEffect(selectedReshadeEffect, reshadeEffects) {
        reshadeParamValues.clear()
        val effect = reshadeEffects.firstOrNull { it.name == selectedReshadeEffect } ?: return@LaunchedEffect
        val savedRaw = shortcut.getExtra("reshadeParams", shortcut.container.getReshadeParams())
        val saved = runCatching { if (savedRaw.isNotEmpty()) JSONObject(savedRaw) else null }.getOrNull()
        // seedValues writes the value-map key scheme (one entry per uniform; per-component for COLOR).
        for (p in effect.params) ReshadeManager.seedValues(p, saved, reshadeParamValues)
    }

    // Win components
    val winComponents = remember {
        val raw = shortcut.getExtra("wincomponents", shortcut.container.getWinComponents())
        mutableStateListOf<WinComponentEntry>().also { list ->
            for (parts in KeyValueSet(raw)) {
                val key = parts[0]; val idx = parts[1].toIntOrNull() ?: 0
                val resId = res.getIdentifier(key, "string", context.packageName)
                val label = if (resId != 0) res.getString(resId) else key
                list.add(WinComponentEntry(key, idx, label))
            }
        }
    }

    // AndroidView refs
    val envVarsViewRef = remember { mutableStateOf<EnvVarsView?>(null) }
    val cpuListViewRef = remember { mutableStateOf<CPUListView?>(null) }

    // Icon
    var iconBitmap by remember { mutableStateOf<Bitmap?>(shortcut.icon) }

    // Sub-dialog show states
    var showGfxConfig by remember { mutableStateOf(false) }
    var showDxvkConfig by remember { mutableStateOf(false) }
    var showWineD3DConfig by remember { mutableStateOf(false) }
    var showBox64DownloadSheet by remember { mutableStateOf(false) }
    var showFexCoreDownloadSheet by remember { mutableStateOf(false) }
    var showDxvkDownloadSheet by remember { mutableStateOf(false) }
    var showVegasDownloadSheet by remember { mutableStateOf(false) }
    var showVkd3dDownloadSheet by remember { mutableStateOf(false) }

    // Tab
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Win Components", "Env Vars", "Advanced")

    // Icon picker
    val iconPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            runCatching {
                val bitmap = context.contentResolver.openInputStream(it)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: return@runCatching
                shortcut.iconFile?.let { f ->
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                }
                shortcut.icon = bitmap
                iconBitmap = bitmap
            }
        }
    }

    // Load async data
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cm = ContentsManager(context)
            cm.syncContents()
            val wineInfo = WineInfo.fromIdentifier(context, cm, shortcut.container.wineVersion)
            val arm64ec = wineInfo.isArm64EC()

            val b64Type = if (arm64ec) ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
                          else ContentProfile.ContentType.CONTENT_TYPE_BOX64
            val b64Arr = if (arm64ec) res.getStringArray(R.array.wowbox64_version_entries).toMutableList()
                         else res.getStringArray(R.array.box64_version_entries).toMutableList()
            for (p in cm.getProfiles(b64Type)) {
                val n = ContentsManager.getEntryName(p)
                b64Arr.add(n.substring(n.indexOf('-') + 1))
            }

            val fexList = res.getStringArray(R.array.fexcore_version_entries).toMutableList()
            for (p in cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)) {
                val n = ContentsManager.getEntryName(p)
                fexList.add(n.substring(n.indexOf('-') + 1))
            }

            val b64Presets = Box64PresetManager.getPresets("box64", context)
            val fexPresets = FEXCorePresetManager.getPresets(context)
            val profiles = InputControlsManager(context).getProfiles(true)

            val midi = mutableListOf("-- ${context.getString(R.string.disabled)} --", MidiManager.DEFAULT_SF2_FILE)
            val sfDir = File(context.filesDir, MidiManager.SF_DIR)
            if (sfDir.exists()) sfDir.listFiles()?.forEach { midi.add(it.name) }

            withContext(Dispatchers.Main) {
                isArm64EC = arm64ec
                box64Versions = b64Arr
                fexCoreVersions = fexList
                box64Presets = b64Presets
                fexCorePresets = fexPresets
                controlsProfiles = profiles
                midiList = midi

                val b64Id = shortcut.getExtra("box64Preset", shortcut.container.getBox64Preset())
                selectedBox64PresetIndex = b64Presets.indexOfFirst { it.id == b64Id }.coerceAtLeast(0)

                val fexId = shortcut.getExtra("fexcorePreset", shortcut.container.getFEXCorePreset())
                selectedFexCorePresetIndex = fexPresets.indexOfFirst { it.id == fexId }.coerceAtLeast(0)

                val cpId = shortcut.getExtra("controlsProfile", "0").toIntOrNull() ?: 0
                selectedControlsProfileIndex = if (cpId == 0) 0
                    else profiles.indexOfFirst { it.id == cpId }.let { if (it >= 0) it + 1 else 0 }

                if (selectedBox64Version.isEmpty()) selectedBox64Version = b64Arr.firstOrNull() ?: ""
            }
        }
    }

    // Save
    fun save() {
        val newName = name.trim()
        if (newName.isNotEmpty() && newName != shortcut.name) {
            renameShortcut(shortcut, newName)
        }

        val screenSize = if (selectedScreenSize == "Custom") {
            val w = customWidth.trim(); val h = customHeight.trim()
            if (w.matches(Regex("[0-9]+")) && h.matches(Regex("[0-9]+"))) {
                val wi = w.toInt(); val hi = h.toInt()
                if (wi % 2 == 0 && hi % 2 == 0) "${wi}x${hi}" else Container.DEFAULT_SCREEN_SIZE
            } else Container.DEFAULT_SCREEN_SIZE
        } else {
            StringUtils.parseIdentifier(selectedScreenSize)
        }

        var finalInputType = 0
        if (enableXInput) finalInputType = finalInputType or WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
        if (enableDInput) finalInputType = finalInputType or WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()

        val wincomps = winComponents.joinToString(",") { "${it.key}=${it.selectedIndex}" }
        val envVars = envVarsViewRef.value?.getEnvVars() ?: shortcut.getExtra("envVars")
        val cpuList = cpuListViewRef.value?.getCheckedCPUListAsString() ?: shortcut.getExtra("cpuList", shortcut.container.getCPUList(true))

        val b64PresetId = box64Presets.getOrElse(selectedBox64PresetIndex) { null }?.id ?: Box64Preset.COMPATIBILITY
        val fexPresetId = fexCorePresets.getOrElse(selectedFexCorePresetIndex) { null }?.id ?: FEXCorePreset.COMPATIBILITY
        val ctrlProfileId = if (selectedControlsProfileIndex == 0) 0
            else controlsProfiles.getOrElse(selectedControlsProfileIndex - 1) { null }?.id ?: 0

        val midiVal = if (midiList.isNotEmpty() && selectedMidi == midiList.firstOrNull()) "" else selectedMidi
        val startupIdx = startupSelectionEntries.indexOf(selectedStartupSelection).coerceAtLeast(0)
        val numCtrl = (numControllersEntries.indexOf(selectedNumControllers) + 1).coerceAtLeast(1)

        with(shortcut) {
            putExtra("execArgs", execArgs.ifEmpty { null })
            putExtra("screenSize", screenSize)
            putExtra("graphicsDriver", StringUtils.parseIdentifier(selectedGfxDriver))
            putExtra("graphicsDriverConfig", graphicsDriverConfig)
            putExtra("renderer", StringUtils.parseIdentifier(selectedRenderer))
            putExtra("renderScale", if (renderScale == "1.0") null else renderScale)
            putExtra("frameGenEngine", frameGenEngine)
            putExtra("fpsLimiterEnabled", if (fpsLimiterEnabled) "1" else "0")
            putExtra("dxwrapper", StringUtils.parseIdentifier(selectedDxWrapper))
            putExtra("dxwrapperConfig", dxWrapperConfig)
            putExtra("audioDriver", StringUtils.parseIdentifier(selectedAudioDriver))
            putExtra("emulator", StringUtils.parseIdentifier(selectedEmulator))
            putExtra("midiSoundFont", midiVal.ifEmpty { null })
            putExtra("lc_all", lcAll)
            putExtra("fullscreenStretched", if (fullscreenStretched) "1" else null)
            putExtra("autoCloseOnExit", if (autoCloseOnExit) "1" else "0")
            putExtra("inputType", finalInputType.toString())
            putExtra("exclusiveXInput", if (exclusiveXInput) "1" else "0")
            putExtra("disableXinput", if (disabledXInput) "1" else null)
            putExtra("simTouchScreen", if (simTouchScreen) "1" else "0")
            putExtra("numControllers", numCtrl.toString())
            putExtra("box64Version", selectedBox64Version)
            putExtra("box64Preset", b64PresetId)
            putExtra("fexcoreVersion", selectedFexCoreVersion)
            putExtra("fexcorePreset", fexPresetId)
            putExtra("controlsProfile", if (ctrlProfileId > 0) ctrlProfileId.toString() else null)
            putExtra("startupSelection", startupIdx.toString())
            putExtra("sharpnessEffect", selectedSharpnessEffect)
            putExtra("sharpnessLevel", sharpnessLevel.toString())
            putExtra("sharpnessDenoise", sharpnessDenoise.toString())
            putExtra("reshadeEffect", selectedReshadeEffect)
            putExtra("reshadeParams",
                if (selectedReshadeEffect == "None" || reshadeParamValues.isEmpty()) null
                else JSONObject().apply {
                    reshadeParamValues.forEach { (k, v) -> put(k, v.toDouble()) }
                }.toString())
            putExtra("wincomponents", wincomps)
            putExtra("envVars", envVars.ifEmpty { null })
            putExtra("cpuList", cpuList)
            saveData()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column {
                // Title bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(shortcut.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Divider(color = DividerColor)

                // Scrollable content
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Exec Args
                    OutlinedTextField(
                        value = execArgs,
                        onValueChange = { execArgs = it },
                        label = { Text(stringResource(R.string.exec_arguments)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Screen size
                    LabeledDropdown(
                        label = stringResource(R.string.screen_size),
                        options = screenSizeEntries,
                        selectedOption = selectedScreenSize,
                        onSelect = { selectedScreenSize = it }
                    )
                    if (selectedScreenSize == "Custom") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customWidth,
                                onValueChange = { customWidth = it },
                                label = { Text("Width") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = customHeight,
                                onValueChange = { customHeight = it },
                                label = { Text("Height") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }

                    // Icon
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        iconBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        OutlinedButton(onClick = { iconPickerLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                            Text("Select Icon")
                        }
                    }

                    // Graphics Driver
                    LabeledDropdown(
                        label = stringResource(R.string.graphics_driver),
                        options = graphicsDriverEntries,
                        selectedOption = selectedGfxDriver,
                        onSelect = { selectedGfxDriver = it }
                    )
                    OutlinedButton(onClick = { showGfxConfig = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${stringResource(R.string.graphics_driver)}: ${GraphicsDriverConfigDialog.getVersion(graphicsDriverConfig)}")
                    }

                    // DX Wrapper
                    LabeledDropdown(
                        label = stringResource(R.string.dxwrapper),
                        options = dxWrapperEntries,
                        selectedOption = selectedDxWrapper,
                        onSelect = { selectedDxWrapper = it }
                    )
                    OutlinedButton(
                        onClick = {
                            val w = StringUtils.parseIdentifier(selectedDxWrapper)
                            if (w.contains("dxvk") || w.contains("vegas")) showDxvkConfig = true
                            else showWineD3DConfig = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("DX Wrapper Config") }

                    // Renderer (host) — per-game override of the container's OpenGL/Vulkan choice.
                    var showSfWarning by remember { mutableStateOf(false) }
                    LabeledDropdown(
                        label = stringResource(R.string.renderer),
                        options = listOf("OpenGL", "Vulkan", "SurfaceFlinger"),
                        selectedOption = selectedRenderer,
                        onSelect = {
                            // SurfaceFlinger is experimental and can reboot some devices — require opt-in.
                            if (it == "SurfaceFlinger" && selectedRenderer != "SurfaceFlinger") showSfWarning = true
                            else selectedRenderer = it
                        }
                    )
                    if (showSfWarning) {
                        SurfaceFlingerWarningDialog(
                            onConfirm = { selectedRenderer = "SurfaceFlinger"; showSfWarning = false },
                            onDismiss = { showSfWarning = false }
                        )
                    }

                    // Render scale (supersampling) — per-game override of the container default.
                    run {
                        val renderScaleValues = listOf("1.0", "1.25", "1.5", "2.0")
                        val renderScaleLabels = listOf("Off", "1.25x", "1.5x", "2x")
                        val rsIdx = renderScaleValues.indexOf(renderScale).coerceAtLeast(0)
                        LabeledDropdown(
                            label = "Render scale (supersampling)",
                            options = renderScaleLabels,
                            selectedOption = renderScaleLabels[rsIdx],
                            onSelect = { renderScale = renderScaleValues[renderScaleLabels.indexOf(it)] }
                        )
                    }

                    // Frame Generation engine — per-game override (lsfg grayed without Lossless.dll).
                    run {
                        val fgLabels = listOf(
                            stringResource(R.string.frame_generation_off),
                            stringResource(R.string.frame_generation_bionic),
                            stringResource(R.string.frame_generation_lsfg)
                        )
                        val fgIdx = fgEngines.indexOf(frameGenEngine).coerceAtLeast(0)
                        LabeledDropdown(
                            label = stringResource(R.string.frame_generation),
                            options = fgLabels,
                            selectedOption = fgLabels[fgIdx],
                            onSelect = { frameGenEngine = fgEngines[fgLabels.indexOf(it)] },
                            disabledOptions = if (lsfgDllAvailable) emptySet() else setOf(fgLabels[2])
                        )
                        if (!lsfgDllAvailable) {
                            Text(
                                text = stringResource(R.string.frame_generation_lsfg_needs_dll),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // FPS limiter — per-game override.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = fpsLimiterEnabled, onCheckedChange = { fpsLimiterEnabled = it })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.fps_limiter), modifier = Modifier.weight(1f))
                    }

                    // Audio driver
                    LabeledDropdown(
                        label = stringResource(R.string.audio_driver),
                        options = audioDriverEntries,
                        selectedOption = selectedAudioDriver,
                        onSelect = { selectedAudioDriver = it }
                    )

                    // Emulator
                    LabeledDropdown(
                        label = "Emulator",
                        options = emulatorEntries,
                        selectedOption = selectedEmulator,
                        onSelect = { selectedEmulator = it },
                        enabled = isArm64EC
                    )

                    // MIDI
                    if (midiList.isNotEmpty()) {
                        val midiDisplay = midiList.firstOrNull { it == selectedMidi } ?: midiList.first()
                        LabeledDropdown(
                            label = "MIDI Sound Font",
                            options = midiList,
                            selectedOption = midiDisplay,
                            onSelect = { selectedMidi = it }
                        )
                    }

                    // LC_ALL
                    OutlinedTextField(
                        value = lcAll,
                        onValueChange = { lcAll = it },
                        label = { Text("LC_ALL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Fullscreen stretched
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fullscreenStretched, onCheckedChange = { fullscreenStretched = it })
                        Text(stringResource(R.string.fullscreen_stretched))
                    }

                    // Close the session when this game exits (per-game override; container default is ON)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoCloseOnExit, onCheckedChange = { autoCloseOnExit = it })
                        Text("Close when game exits")
                    }

                    // Input section
                    SectionBox(title = "Input") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = enableXInput,
                                onCheckedChange = { enableXInput = it },
                                enabled = exclusiveXInput
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.enable_xinput_for_wine_game), modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = enableDInput,
                                onCheckedChange = { enableDInput = it },
                                enabled = exclusiveXInput
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.enable_dinput_for_wine_game), modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = exclusiveXInput,
                                onCheckedChange = { checked ->
                                    exclusiveXInput = checked
                                    if (!checked) { enableXInput = true; enableDInput = true }
                                    else if (enableXInput && enableDInput) enableDInput = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Exclusive Input", modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = disabledXInput, onCheckedChange = { disabledXInput = it })
                            Text("Disable XInput")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = simTouchScreen, onCheckedChange = { simTouchScreen = it })
                            Text("Touchscreen Mode")
                        }
                        LabeledDropdown(
                            label = "Num Controllers",
                            options = numControllersEntries,
                            selectedOption = selectedNumControllers,
                            onSelect = { selectedNumControllers = it }
                        )
                    }

                    // Tabs
                    TabRow(selectedTabIndex = selectedTab) {
                        tabTitles.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(title) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // Tab content
                    when (selectedTab) {
                        0 -> ScWinComponentsTab(winComponents)
                        1 -> ScEnvVarsTab(shortcut, envVarsViewRef)
         2 -> ScAdvancedTab(
            isArm64EC = isArm64EC,
            box64Versions = box64Versions,
            selectedBox64Version = selectedBox64Version,
            onBox64VersionChange = { selectedBox64Version = it },
            box64Presets = box64Presets,
            selectedBox64PresetIndex = selectedBox64PresetIndex,
            onBox64PresetIndexChange = { selectedBox64PresetIndex = it },
            fexCoreVersions = fexCoreVersions,
            selectedFexCoreVersion = selectedFexCoreVersion,
            onFexVersionChange = { selectedFexCoreVersion = it },
            fexCorePresets = fexCorePresets,
            selectedFexPresetIndex = selectedFexCorePresetIndex,
            onFexPresetIndexChange = { selectedFexCorePresetIndex = it },
            controlsProfiles = controlsProfiles,
            selectedControlsProfileIndex = selectedControlsProfileIndex,
            onControlsProfileChange = { selectedControlsProfileIndex = it },
            startupSelectionEntries = startupSelectionEntries,
            selectedStartupSelection = selectedStartupSelection,
            onStartupChange = { selectedStartupSelection = it },
            cpuListViewRef = cpuListViewRef,
            initialCpuList = shortcut.getExtra("cpuList", shortcut.container.getCPUList(true)),
            onCpuListSnapshot = { shortcut.putExtra("cpuList", it) },
            sharpnessEffectEntries = sharpnessEffectEntries,
            selectedSharpnessEffect = selectedSharpnessEffect,
            onSharpnessEffectChange = { selectedSharpnessEffect = it },
            sharpnessLevel = sharpnessLevel,
            onSharpnessLevelChange = { sharpnessLevel = it },
            sharpnessDenoise = sharpnessDenoise,
            onSharpnessDenoiseChange = { sharpnessDenoise = it },
            reshadeEffectNames = reshadeEffectNames,
            selectedReshadeEffect = selectedReshadeEffect,
            onReshadeEffectChange = { selectedReshadeEffect = it },
            onReshadeCatalogChanged = { reshadeEffects = ReshadeManager.scanEffects(context) },
            reshadeParams = reshadeEffects.firstOrNull { it.name == selectedReshadeEffect }?.params ?: emptyList(),
            reshadeParamValues = reshadeParamValues,
            onReshadeParamChange = { name, v -> reshadeParamValues[name] = v },
            reshadeSupported = StringUtils.parseIdentifier(selectedDxWrapper).let { it.contains("dxvk") || it.contains("vegas") },
            onShowBox64DownloadSheet = { showBox64DownloadSheet = true },
            onShowFexCoreDownloadSheet = { showFexCoreDownloadSheet = true }
        )
                    }
                }

                Divider(color = DividerColor)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { save(); onDismiss() }) { Text(stringResource(android.R.string.ok)) }
                }
            }
        }

    // Config dialogs + download sheets are composed INSIDE the settings Dialog's window so the
    // ModalBottomSheet (ContentDownloadSheet) renders on top of it. When they were outside the
    // Dialog, the bottom sheet's window appeared BEHIND the settings dialog (couldn't be used).
    if (showGfxConfig) {
        GraphicsDriverConfigDialog(
            graphicsDriver = StringUtils.parseIdentifier(selectedGfxDriver),
            initialConfig = graphicsDriverConfig,
            onConfirm = { graphicsDriverConfig = it; showGfxConfig = false },
            onDismiss = { showGfxConfig = false }
        )
    }
    val isVegasCfg = StringUtils.parseIdentifier(selectedDxWrapper).contains("vegas")
    if (showDxvkConfig) {
        DxvkConfigDialog(
            isArm64EC = isArm64EC,
            isVegas = isVegasCfg,
            initialConfig = dxWrapperConfig,
            onConfirm = { dxWrapperConfig = it; showDxvkConfig = false },
            onDismiss = { showDxvkConfig = false },
            onDownloadDxvk = { showDxvkConfig = false; if (isVegasCfg) showVegasDownloadSheet = true else showDxvkDownloadSheet = true },
            onDownloadVkd3d = { showDxvkConfig = false; showVkd3dDownloadSheet = true }
        )
    }
    if (showWineD3DConfig) {
        WineD3DConfigDialog(
            initialConfig = dxWrapperConfig,
            onConfirm = { dxWrapperConfig = it; showWineD3DConfig = false },
            onDismiss = { showWineD3DConfig = false }
        )
    }

    if (showBox64DownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_BOX64,
            onDismiss = { showBox64DownloadSheet = false },
            onContentChanged = {}
        )
    }
    if (showFexCoreDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
            onDismiss = { showFexCoreDownloadSheet = false },
            onContentChanged = {}
        )
    }
    if (showDxvkDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_DXVK,
            onDismiss = { showDxvkDownloadSheet = false },
            onContentChanged = {}
        )
    }
    if (showVkd3dDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
            onDismiss = { showVkd3dDownloadSheet = false },
            onContentChanged = {}
        )
    }
    if (showVegasDownloadSheet) {
        VegasDownloadSheet(
            onDismiss = { showVegasDownloadSheet = false },
            onContentChanged = {}
        )
    }
    } // settings Dialog
}

@Composable
private fun ScWinComponentsTab(components: androidx.compose.runtime.snapshots.SnapshotStateList<WinComponentEntry>) {
    val directx = components.filter { it.key.startsWith("direct") }
    val general = components.filterNot { it.key.startsWith("direct") }
    val options = listOf("Builtin (Wine)", "Native (Windows)")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (directx.isNotEmpty()) {
            SectionBox(title = "DirectX") {
                directx.forEach { comp ->
                    LabeledDropdown(
                        label = comp.label,
                        options = options,
                        selectedOption = options.getOrElse(comp.selectedIndex) { options[0] },
                        onSelect = { opt ->
                            val i = components.indexOfFirst { it.key == comp.key }
                            if (i >= 0) components[i] = components[i].copy(selectedIndex = options.indexOf(opt).coerceAtLeast(0))
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (general.isNotEmpty()) {
            SectionBox(title = "General") {
                general.forEach { comp ->
                    LabeledDropdown(
                        label = comp.label,
                        options = options,
                        selectedOption = options.getOrElse(comp.selectedIndex) { options[0] },
                        onSelect = { opt ->
                            val i = components.indexOfFirst { it.key == comp.key }
                            if (i >= 0) components[i] = components[i].copy(selectedIndex = options.indexOf(opt).coerceAtLeast(0))
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ScEnvVarsTab(shortcut: Shortcut, envVarsViewRef: MutableState<EnvVarsView?>) {
    var showAddEnvVar by remember { mutableStateOf(false) }
    // Flush the legacy EnvVarsView's contents back into the Shortcut's in-memory
    // extras before the tab leaves composition, so a tab switch doesn't drop
    // in-progress edits. shortcut.putExtra mutates only the in-memory JSONObject;
    // disk persistence still happens later in save() -> saveData().
    DisposableEffect(Unit) {
        onDispose {
            envVarsViewRef.value?.let { shortcut.putExtra("envVars", it.envVars.ifEmpty { null }) }
            envVarsViewRef.value = null
        }
    }
    Column {
        AndroidView(
            factory = { ctx ->
                EnvVarsView(ctx).also { ev ->
                    ev.setDarkMode(true)
                    ev.setEnvVars(com.winlator.star.core.EnvVars(shortcut.getExtra("envVars")))
                    envVarsViewRef.value = ev
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showAddEnvVar = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add Environment Variable")
        }
    }
    if (showAddEnvVar) {
        AddEnvVarComposable(
            onConfirm = { name, value ->
                envVarsViewRef.value?.let { ev ->
                    if (name.isNotEmpty() && !ev.containsName(name)) ev.add(name, value)
                }
                showAddEnvVar = false
            },
            onDismiss = { showAddEnvVar = false }
        )
    }
}

@Composable
private fun ScAdvancedTab(
    isArm64EC: Boolean,
    box64Versions: List<String>,
    selectedBox64Version: String,
    onBox64VersionChange: (String) -> Unit,
    box64Presets: List<Box64Preset>,
    selectedBox64PresetIndex: Int,
    onBox64PresetIndexChange: (Int) -> Unit,
    fexCoreVersions: List<String>,
    selectedFexCoreVersion: String,
    onFexVersionChange: (String) -> Unit,
    fexCorePresets: List<FEXCorePreset>,
    selectedFexPresetIndex: Int,
    onFexPresetIndexChange: (Int) -> Unit,
    controlsProfiles: List<ControlsProfile>,
    selectedControlsProfileIndex: Int,
    onControlsProfileChange: (Int) -> Unit,
    startupSelectionEntries: List<String>,
    selectedStartupSelection: String,
    onStartupChange: (String) -> Unit,
    cpuListViewRef: MutableState<CPUListView?>,
    initialCpuList: String,
    onCpuListSnapshot: (String) -> Unit,
    sharpnessEffectEntries: List<String>,
    selectedSharpnessEffect: String,
    onSharpnessEffectChange: (String) -> Unit,
    sharpnessLevel: Int,
    onSharpnessLevelChange: (Int) -> Unit,
    sharpnessDenoise: Int,
    onSharpnessDenoiseChange: (Int) -> Unit,
    reshadeEffectNames: List<String> = listOf("None"),
    selectedReshadeEffect: String = "None",
    onReshadeEffectChange: (String) -> Unit = {},
    onReshadeCatalogChanged: () -> Unit = {},
    reshadeParams: List<ReshadeManager.ReshadeParam> = emptyList(),
    reshadeParamValues: Map<String, Float> = emptyMap(),
    onReshadeParamChange: (String, Float) -> Unit = { _, _ -> },
    reshadeSupported: Boolean = true,
    onShowBox64DownloadSheet: () -> Unit = {},
    onShowFexCoreDownloadSheet: () -> Unit = {},
) {
    // Flush legacy CPUListView selection back to the parent (Shortcut extras)
    // before the tab leaves composition, so a tab switch doesn't drop edits.
    DisposableEffect(Unit) {
        onDispose {
            cpuListViewRef.value?.let { onCpuListSnapshot(it.checkedCPUListAsString) }
            cpuListViewRef.value = null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionBox(title = "Box64") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LabeledDropdown(
                    label = stringResource(R.string.box64_version),
                    options = box64Versions,
                    selectedOption = box64Versions.firstOrNull { it == selectedBox64Version } ?: selectedBox64Version,
                    onSelect = onBox64VersionChange,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onShowBox64DownloadSheet,
                    modifier = Modifier.size(40.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Download Box64", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            val presetNames = box64Presets.map { it.name }
            LabeledDropdown(
                label = stringResource(R.string.box64_preset),
                options = presetNames,
                selectedOption = presetNames.getOrElse(selectedBox64PresetIndex) { "" },
                onSelect = { opt -> onBox64PresetIndexChange(presetNames.indexOf(opt).coerceAtLeast(0)) }
            )
        }

        if (isArm64EC) {
            SectionBox(title = "FEXCore") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledDropdown(
                        label = stringResource(R.string.fexcore_version),
                        options = fexCoreVersions,
                        selectedOption = fexCoreVersions.firstOrNull { it == selectedFexCoreVersion } ?: selectedFexCoreVersion,
                        onSelect = onFexVersionChange,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onShowFexCoreDownloadSheet,
                        modifier = Modifier.size(40.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Download FEXCore", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val fexNames = fexCorePresets.map { it.name }
                LabeledDropdown(
                    label = stringResource(R.string.fexcore_preset),
                    options = fexNames,
                    selectedOption = fexNames.getOrElse(selectedFexPresetIndex) { "" },
                    onSelect = { opt -> onFexPresetIndexChange(fexNames.indexOf(opt).coerceAtLeast(0)) }
                )
            }
        }

        val profileNames = mutableListOf(stringResource(R.string.none))
        profileNames.addAll(controlsProfiles.map { it.getName() })
        LabeledDropdown(
            label = "Controls Profile",
            options = profileNames,
            selectedOption = profileNames.getOrElse(selectedControlsProfileIndex) { profileNames.first() },
            onSelect = { opt -> onControlsProfileChange(profileNames.indexOf(opt).coerceAtLeast(0)) }
        )

        LabeledDropdown(
            label = stringResource(R.string.startup_selection),
            options = startupSelectionEntries,
            selectedOption = selectedStartupSelection,
            onSelect = onStartupChange
        )

        SectionBox(title = stringResource(R.string.processor_affinity)) {
            AndroidView(
                factory = { ctx ->
                    CPUListView(ctx).also { cpv ->
                        cpv.setCheckedCPUList(initialCpuList)
                        cpuListViewRef.value = cpv
                    }
                },
                modifier = Modifier.fillMaxWidth().wrapContentHeight()
            )
        }

        SectionBox(title = "Sharpness (VKBasalt)") {
            LabeledDropdown(
                label = "Effect",
                options = sharpnessEffectEntries,
                selectedOption = selectedSharpnessEffect,
                onSelect = onSharpnessEffectChange
            )
            Spacer(Modifier.height(8.dp))
            Text("Level: $sharpnessLevel%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = sharpnessLevel.toFloat(),
                onValueChange = { onSharpnessLevelChange(it.toInt()) },
                valueRange = 0f..100f,
                steps = 99
            )
            Text("Denoise: $sharpnessDenoise%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = sharpnessDenoise.toFloat(),
                onValueChange = { onSharpnessDenoiseChange(it.toInt()) },
                valueRange = 0f..100f,
                steps = 99
            )
        }

        // ReShade effect (vkBasalt drop-in). Mirrors the Sharpness (VKBasalt) section above: an
        // effect picker (None + scanned drop-in folders) plus a slider/switch per uniform reflected
        // from the .fx. Only applies to DXVK/VKD3D (Vulkan) games — a hint shows otherwise.
        SectionBox(title = "ReShade effect") {
            // Catalog picker: browse/download effects from reshade.json; installed ones are selectable.
            ReshadeEffectPicker(
                selected = selectedReshadeEffect,
                supported = reshadeSupported,
                onSelect = onReshadeEffectChange,
                onCatalogChanged = onReshadeCatalogChanged,
            )
            if (selectedReshadeEffect != "None" && reshadeParams.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                reshadeParams.forEach { p ->
                    val value = reshadeParamValues[p.name] ?: p.defaultValue
                    when (p.type) {
                        ReshadeManager.ParamType.BOOL -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Switch(
                                    checked = value >= 0.5f,
                                    onCheckedChange = { onReshadeParamChange(p.name, if (it) 1f else 0f) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(p.label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        ReshadeManager.ParamType.COMBO -> {
                            val options = p.options ?: emptyList()
                            LabeledDropdown(
                                label = p.label,
                                options = options,
                                selectedOption = options.getOrElse(value.toInt()) { options.firstOrNull() ?: "" },
                                onSelect = { sel -> onReshadeParamChange(p.name, options.indexOf(sel).coerceAtLeast(0).toFloat()) }
                            )
                        }
                        ReshadeManager.ParamType.COLOR -> {
                            Text(p.label, style = MaterialTheme.typography.bodySmall)
                            val comp = listOf("R", "G", "B", "A")
                            for (c in 0 until p.components) {
                                val k = "${p.name}_$c"
                                val cv = reshadeParamValues[k] ?: p.componentDefaults?.getOrNull(c) ?: 0f
                                Text("${comp.getOrElse(c) { "$c" }}: ${"%.2f".format(cv)}", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = cv.coerceIn(0f, 1f),
                                    onValueChange = { onReshadeParamChange(k, it) },
                                    valueRange = 0f..1f
                                )
                            }
                        }
                        else -> {
                            val display = if (p.type == ReshadeManager.ParamType.INT)
                                value.toInt().toString() else "%.2f".format(value)
                            Text("${p.label}: $display", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = value.coerceIn(p.min, p.max),
                                onValueChange = { onReshadeParamChange(p.name, it) },
                                valueRange = p.min..p.max
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Non-composable helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun renameShortcut(shortcut: Shortcut, newName: String) {
    val parent = shortcut.file.parentFile ?: return
    val oldFile = shortcut.file
    val newFile = File(parent, "$newName.desktop")
    if (!newFile.isFile && oldFile.renameTo(newFile)) {
        runCatching {
            val field: Field = Shortcut::class.java.getDeclaredField("file")
            field.isAccessible = true
            field.set(shortcut, newFile)
        }
        val lnk = File(parent, "${shortcut.name}.lnk")
        if (lnk.isFile) lnk.renameTo(File(parent, "$newName.lnk"))
    }
}

private fun runShortcut(activity: Activity, shortcut: Shortcut) {
    if (!XrActivity.isEnabled(activity)) {
        val intent = Intent(activity, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", shortcut.container.id)
            putExtra("shortcut_path", shortcut.file.path)
            putExtra("shortcut_name", shortcut.name)
            putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"))
        }
        activity.startActivity(intent)
    } else {
        XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.path)
    }
}

private fun addToHomeScreen(context: Context, shortcut: Shortcut) {
    if (shortcut.getExtra("uuid").isEmpty()) shortcut.genUUID()
    try {
        val sm = ContextCompat.getSystemService(context, ShortcutManager::class.java)
        if (sm != null && sm.isRequestPinShortcutSupported) {
            val intent = Intent(context, XServerDisplayActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("container_id", shortcut.container.id)
                putExtra("shortcut_path", shortcut.file.path)
            }
            val bmp: Bitmap = shortcut.icon
                ?: BitmapFactory.decodeResource(context.resources, com.winlator.star.R.drawable.icon_wine)
            val info = ShortcutInfo.Builder(context, shortcut.getExtra("uuid"))
                .setShortLabel(shortcut.name)
                .setLongLabel(shortcut.name)
                .setIcon(Icon.createWithBitmap(bmp))
                .setIntent(intent)
                .build()
            sm.requestPinShortcut(info, null)
        }
    } catch (_: Exception) {}
}

private fun exportShortcut(context: Context, shortcut: Shortcut) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    val uriString = prefs.getString("shortcuts_export_path_uri", null)

    val shortcutsDir: File = if (uriString != null) {
        val folderUri = Uri.parse(uriString)
        val pickedDir = DocumentFile.fromTreeUri(context, folderUri)
        if (pickedDir == null || !pickedDir.canWrite()) {
            Toast.makeText(context, "Cannot write to the selected folder", Toast.LENGTH_SHORT).show()
            return
        }
        File(FileUtils.getFilePathFromUri(context, folderUri))
    } else {
        File(SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH)
    }

    if (!shortcutsDir.exists() && !shortcutsDir.mkdirs()) {
        Toast.makeText(context, "Failed to create default directory", Toast.LENGTH_SHORT).show()
        return
    }

    val exportFile = File(shortcutsDir, shortcut.file.name)
    val fileExists = exportFile.exists()

    try {
        val lines = mutableListOf<String>()
        var containerIdFound = false
        BufferedReader(FileReader(shortcut.file)).use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.startsWith("container_id:")) {
                    lines += "container_id:${shortcut.container.id}"
                    containerIdFound = true
                } else {
                    lines += line
                }
            }
        }
        if (!containerIdFound) lines += "container_id:${shortcut.container.id}"

        FileWriter(exportFile, false).use { w ->
            lines.forEach { w.write("$it\n") }
        }

        Toast.makeText(
            context,
            if (fileExists) "Shortcut updated at ${exportFile.path}" else "Shortcut exported to ${exportFile.path}",
            Toast.LENGTH_LONG,
        ).show()
    } catch (_: IOException) {
        Toast.makeText(context, "Failed to export shortcut", Toast.LENGTH_LONG).show()
    }
}

