@file:OptIn(ExperimentalMaterial3Api::class)
package com.winlator.star.ui.screens

import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.MainActivity
import com.winlator.star.R
import com.winlator.star.contentdialog.DXVKConfigDialog
import com.winlator.star.contentdialog.WineD3DConfigDialog
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.AppUtils
import com.winlator.star.core.DefaultVersion
import com.winlator.star.core.FileUtils
import com.winlator.star.core.GPUInformation
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WineThemeManager
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import com.winlator.star.container.Container
import com.winlator.star.widget.ColorPickerView
import com.winlator.star.widget.CPUListView
import com.winlator.star.widget.EnvVarsView

// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ContainerDetailScreen(
    containerId: Int,
    onNavigateBack: () -> Unit,
    viewModel: ContainerDetailViewModel = viewModel()
) {
    val context = LocalContext.current

    LaunchedEffect(containerId) { viewModel.init(containerId) }

    var showGraphicsDriverConfig by remember { mutableStateOf(false) }
    var showDxvkConfig           by remember { mutableStateOf(false) }
    var showWineD3DConfig        by remember { mutableStateOf(false) }
    var showFpsConfig            by remember { mutableStateOf(false) }
    var showWineDownloadSheet    by remember { mutableStateOf(false) }
    var showBox64DownloadSheet   by remember { mutableStateOf(false) }
    var showFexCoreDownloadSheet by remember { mutableStateOf(false) }
    var showDxvkDownloadSheet    by remember { mutableStateOf(false) }
    var showVegasDownloadSheet   by remember { mutableStateOf(false) }
    var showVkd3dDownloadSheet   by remember { mutableStateOf(false) }

    // AndroidView references for custom views
    val envVarsViewRef      = remember { mutableStateOf<EnvVarsView?>(null)      }
    val cpuListViewRef      = remember { mutableStateOf<CPUListView?>(null)      }
    val cpuListWoW64Ref     = remember { mutableStateOf<CPUListView?>(null)      }
    val colorPickerViewRef  = remember { mutableStateOf<ColorPickerView?>(null)  }

    val tabTitles = listOf(
        stringResource(R.string.wine_configuration),
        stringResource(R.string.win_components),
        stringResource(R.string.environment_variables),
        stringResource(R.string.drives),
        stringResource(R.string.advanced),
        stringResource(R.string.xr)
    )

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!viewModel.isSaving) viewModel.confirm(
                        resolvedGraphicsDriverConfig = viewModel.graphicsDriverConfig,
                        resolvedDXWrapperConfig      = viewModel.dxWrapperConfig,
                        resolvedFPSCounterConfig     = viewModel.fpsCounterConfig,
                        resolvedEnvVars      = envVarsViewRef.value?.envVars ?: viewModel.envVarsStr,
                        resolvedCPUList      = cpuListViewRef.value?.checkedCPUListAsString ?: viewModel.cpuList,
                        resolvedCPUListWoW64 = cpuListWoW64Ref.value?.checkedCPUListAsString ?: viewModel.cpuListWoW64,
                        resolvedColorAsString = colorPickerViewRef.value?.colorAsString ?: "#0277bd",
                        onDone = onNavigateBack
                    )
                },
                containerColor = if (viewModel.isSaving)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                else
                    MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Check, contentDescription = "Confirm")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Top-level fields ───────────────────────────────────────────────
            TopLevelFields(
                viewModel = viewModel,
                onShowGfxConfig = { showGraphicsDriverConfig = true },
                onShowDxvkConfig = { showDxvkConfig = true },
                onShowWineD3DConfig = { showWineD3DConfig = true },
                onShowFpsConfig = { showFpsConfig = true },
                onShowWineDownloadSheet = { showWineDownloadSheet = true },
            )

            // ── Tabs ───────────────────────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = viewModel.selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                edgePadding = 0.dp
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = viewModel.selectedTab == index,
                        onClick = { viewModel.selectedTab = index },
                        text = { Text(title, fontSize = 12.sp) }
                    )
                }
            }

            // ── Tab content ────────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                when (viewModel.selectedTab) {
                    0 -> WineConfigTab(viewModel, colorPickerViewRef)
                    1 -> WinComponentsTab(viewModel)
                    2 -> EnvVarsTab(viewModel, envVarsViewRef)
                    3 -> DrivesTab(viewModel)
                    4 -> AdvancedTab(
                        viewModel,
                        cpuListViewRef,
                        cpuListWoW64Ref,
                        onShowBox64DownloadSheet = { showBox64DownloadSheet = true },
                        onShowFexCoreDownloadSheet = { showFexCoreDownloadSheet = true },
                    )
                    5 -> XRTab(viewModel)
                }
            }

            Spacer(modifier = Modifier.height(80.dp)) // room for FAB
        }
    }

    if (showGraphicsDriverConfig) {
        GraphicsDriverConfigDialog(
            graphicsDriver = StringUtils.parseIdentifier(viewModel.selectedGraphicsDriver),
            initialConfig = viewModel.graphicsDriverConfig,
            onConfirm = { newConfig -> viewModel.graphicsDriverConfig = newConfig; showGraphicsDriverConfig = false },
            onDismiss = { showGraphicsDriverConfig = false }
        )
    }
    val isVegasWrapper by remember {
        mutableStateOf(StringUtils.parseIdentifier(viewModel.selectedDXWrapper ?: "").contains("vegas"))
    }
    if (showDxvkConfig) {
        DxvkConfigDialog(
            isArm64EC = viewModel.isArm64EC,
            isVegas = isVegasWrapper,
            initialConfig = viewModel.dxWrapperConfig,
            onConfirm = { newConfig -> viewModel.dxWrapperConfig = newConfig; showDxvkConfig = false },
            onDismiss = { showDxvkConfig = false },
            onDownloadDxvk = { if (isVegasWrapper) showVegasDownloadSheet = true else showDxvkDownloadSheet = true },
            onDownloadVkd3d = { showVkd3dDownloadSheet = true }
        )
    }
    if (showWineD3DConfig) {
        WineD3DConfigDialog(
            initialConfig = viewModel.dxWrapperConfig,
            onConfirm = { newConfig -> viewModel.dxWrapperConfig = newConfig; showWineD3DConfig = false },
            onDismiss = { showWineD3DConfig = false }
        )
    }
    if (showFpsConfig) {
        FpsCounterConfigDialog(
            initialConfig = viewModel.fpsCounterConfig,
            onConfirm = { newConfig -> viewModel.fpsCounterConfig = newConfig; showFpsConfig = false },
            onDismiss = { showFpsConfig = false }
        )
    }

    // ── Content download sheets ────────────────────────────────────────────
    if (showWineDownloadSheet) {
        ContentDownloadSheet(
            contentTypes = listOf(
                com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_WINE,
                com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_PROTON,
            ),
            onDismiss = { showWineDownloadSheet = false },
            onContentChanged = { viewModel.refreshWineVersions() }
        )
    }
    if (showBox64DownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_BOX64,
            onDismiss = { showBox64DownloadSheet = false },
            onContentChanged = { viewModel.refreshBox64Versions() }
        )
    }
    if (showFexCoreDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
            onDismiss = { showFexCoreDownloadSheet = false },
            onContentChanged = { viewModel.refreshFEXCoreVersions() }
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
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TopLevelFields(
    viewModel: ContainerDetailViewModel,
    onShowGfxConfig: () -> Unit,
    onShowDxvkConfig: () -> Unit,
    onShowWineD3DConfig: () -> Unit,
    onShowFpsConfig: () -> Unit,
    onShowWineDownloadSheet: () -> Unit,
) {
    val context = LocalContext.current

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {

        // Name
        OutlinedTextField(
            value = viewModel.containerName,
            onValueChange = { viewModel.containerName = it },
            label = { Text(stringResource(R.string.name)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        // Screen Size
        LabeledDropdown(
            label = stringResource(R.string.screen_size),
            options = viewModel.screenSizeEntries,
            selectedOption = viewModel.selectedScreenSize,
            onSelect = { viewModel.selectedScreenSize = it }
        )
        if (viewModel.selectedScreenSize.equals("custom", ignoreCase = true)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = viewModel.customWidth,
                    onValueChange = { viewModel.customWidth = it },
                    label = { Text(stringResource(R.string.width)) },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = viewModel.customHeight,
                    onValueChange = { viewModel.customHeight = it },
                    label = { Text(stringResource(R.string.height)) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Wine Version + download gear
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LabeledDropdown(
                label = stringResource(R.string.wine_version),
                options = viewModel.wineVersionEntries,
                selectedOption = viewModel.selectedWineVersion,
                enabled = viewModel.wineVersionEnabled,
                onSelect = { viewModel.onWineVersionChanged(it) },
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onShowWineDownloadSheet,
                modifier = Modifier.size(40.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(8.dp))

        // Graphics Driver + config button
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LabeledDropdown(
                label = stringResource(R.string.graphics_driver),
                options = viewModel.graphicsDriverEntries,
                selectedOption = viewModel.selectedGraphicsDriver,
                onSelect = { viewModel.selectedGraphicsDriver = it },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onShowGfxConfig) {
                Icon(Icons.Default.Settings, contentDescription = null)
            }
        }
        Spacer(Modifier.height(8.dp))

        // DX Wrapper + config button
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                LabeledDropdown(
                    label = stringResource(R.string.dxwrapper),
                    options = viewModel.dxWrapperEntries,
                    selectedOption = viewModel.selectedDXWrapper,
                    onSelect = { viewModel.selectedDXWrapper = it },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    AppUtils.showHelpBox(context, View(context), R.string.dxwrapper_help_content)
                }) {
                    Icon(Icons.Default.Help, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = {
                val wrapper = StringUtils.parseIdentifier(viewModel.selectedDXWrapper ?: "")
                if (wrapper.contains("dxvk") || wrapper.contains("vegas")) onShowDxvkConfig() else onShowWineD3DConfig()
            }) {
                Icon(Icons.Default.Settings, contentDescription = null)
            }
        }
        Spacer(Modifier.height(8.dp))

        // LSFG (Lossless Scaling Frame Generation)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = viewModel.lsfgEnabled,
                onCheckedChange = { viewModel.lsfgEnabled = it }
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lsfg_enabled), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))

        // Audio Driver
        LabeledDropdown(
            label = stringResource(R.string.audio_driver),
            options = viewModel.audioDriverEntries,
            selectedOption = viewModel.selectedAudioDriver,
            onSelect = { viewModel.selectedAudioDriver = it }
        )
        Spacer(Modifier.height(8.dp))

        // Emulator (arm64ec only)
        if (viewModel.isArm64EC) {
            LabeledDropdown(
                label = "Emulator",
                options = viewModel.emulatorEntries,
                selectedOption = viewModel.selectedEmulator,
                enabled = viewModel.emulatorEnabled,
                onSelect = { viewModel.selectedEmulator = it }
            )
            Spacer(Modifier.height(8.dp))
        }

        // MIDI Sound Font
        LabeledDropdown(
            label = stringResource(R.string.midi_sound_font),
            options = viewModel.midiEntries,
            selectedOption = viewModel.midiEntries.getOrElse(viewModel.selectedMidiIndex) { "" },
            onSelect = { opt -> viewModel.selectedMidiIndex = viewModel.midiEntries.indexOf(opt).coerceAtLeast(0) }
        )
        Spacer(Modifier.height(8.dp))

        // Show FPS + config
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = viewModel.showFPS,
                onCheckedChange = { viewModel.showFPS = it }
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.show_fps), modifier = Modifier.weight(1f))
            IconButton(onClick = onShowFpsConfig) {
                Icon(Icons.Default.Settings, contentDescription = null)
            }
        }

        // Fullscreen Stretched
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = viewModel.fullscreenStretched,
                onCheckedChange = { viewModel.fullscreenStretched = it }
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.fullscreen_stretched))
        }

        // LC_ALL
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = viewModel.lcAll,
                onValueChange = { viewModel.lcAll = it },
                label = { Text("LC_ALL") },
                modifier = Modifier.weight(1f)
            )
            var showLcMenu by remember { mutableStateOf(false) }
            IconButton(onClick = { showLcMenu = true }) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
            }
            DropdownMenu(expanded = showLcMenu, onDismissRequest = { showLcMenu = false }) {
                viewModel.lcAllEntries.forEach { lc ->
                    DropdownMenuItem(
                        text = { Text("$lc.UTF-8") },
                        onClick = { viewModel.lcAll = "$lc.UTF-8"; showLcMenu = false }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun WineConfigTab(
    viewModel: ContainerDetailViewModel,
    colorPickerViewRef: MutableState<ColorPickerView?>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Desktop section
        SectionBox(title = "Desktop") {
            LabeledDropdown(
                label = stringResource(R.string.theme),
                options = listOf("Light", "Dark"),
                selectedOption = listOf("Light", "Dark").getOrElse(viewModel.desktopThemeIndex) { "Light" },
                onSelect = { opt -> viewModel.desktopThemeIndex = listOf("Light", "Dark").indexOf(opt).coerceAtLeast(0) }
            )
            Spacer(Modifier.height(8.dp))
            LabeledDropdown(
                label = stringResource(R.string.background),
                options = listOf("Image", "Solid Color"),
                selectedOption = listOf("Image", "Solid Color").getOrElse(viewModel.desktopBgTypeIndex) { "Image" },
                onSelect = { opt -> viewModel.desktopBgTypeIndex = listOf("Image", "Solid Color").indexOf(opt).coerceAtLeast(0) }
            )
            // Color picker (visible when Solid Color selected)
            if (viewModel.desktopBgTypeIndex == WineThemeManager.BackgroundType.COLOR.ordinal) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Background Color", modifier = Modifier.weight(1f))
                    AndroidView(
                        factory = { ctx ->
                            ColorPickerView(ctx).also { cpv ->
                                cpv.setColor(viewModel.desktopBgColorInt)
                                colorPickerViewRef.value = cpv
                            }
                        },
                        update = { cpv -> cpv.setColor(viewModel.desktopBgColorInt) },
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }

        // DirectInput section
        SectionBox(title = "DirectInput") {
            LabeledDropdown(
                label = stringResource(R.string.mouse_warp_override),
                options = viewModel.mouseWarpEntries,
                selectedOption = viewModel.mouseWarpEntries.getOrElse(viewModel.selectedMouseWarpIndex) { "" },
                onSelect = { opt -> viewModel.selectedMouseWarpIndex = viewModel.mouseWarpEntries.indexOf(opt).coerceAtLeast(0) }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun WinComponentsTab(viewModel: ContainerDetailViewModel) {
    val directxItems by remember {
        derivedStateOf { viewModel.winComponents.filter { it.key.startsWith("direct") } }
    }
    val generalItems by remember {
        derivedStateOf { viewModel.winComponents.filterNot { it.key.startsWith("direct") } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (directxItems.isNotEmpty()) {
            SectionBox(title = "DirectX") {
                directxItems.forEach { comp ->
                    WinComponentRow(comp) { idx ->
                        val i = viewModel.winComponents.indexOfFirst { it.key == comp.key }
                        if (i >= 0) viewModel.winComponents[i] = viewModel.winComponents[i].copy(selectedIndex = idx)
                    }
                }
            }
        }
        if (generalItems.isNotEmpty()) {
            SectionBox(title = "General") {
                generalItems.forEach { comp ->
                    WinComponentRow(comp) { idx ->
                        val i = viewModel.winComponents.indexOfFirst { it.key == comp.key }
                        if (i >= 0) viewModel.winComponents[i] = viewModel.winComponents[i].copy(selectedIndex = idx)
                    }
                }
            }
        }
    }
}

@Composable
private fun WinComponentRow(comp: WinComponentEntry, onSelect: (Int) -> Unit) {
    val options = listOf("Builtin (Wine)", "Native (Windows)")
    LabeledDropdown(
        label = comp.label,
        options = options,
        selectedOption = options.getOrElse(comp.selectedIndex) { options[0] },
        onSelect = { opt -> onSelect(options.indexOf(opt).coerceAtLeast(0)) }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun EnvVarsTab(
    viewModel: ContainerDetailViewModel,
    envVarsViewRef: MutableState<EnvVarsView?>
) {
    var showAddEnvVar by remember { mutableStateOf(false) }
    // Flush the legacy EnvVarsView's contents back to the ViewModel before the
    // tab leaves composition, so a tab switch doesn't drop in-progress edits.
    DisposableEffect(Unit) {
        onDispose {
            envVarsViewRef.value?.let { viewModel.envVarsStr = it.envVars }
            envVarsViewRef.value = null
        }
    }
    Column {
        AndroidView(
            factory = { ctx ->
                EnvVarsView(ctx).also { ev ->
                    ev.setDarkMode(true)
                    ev.setEnvVars(com.winlator.star.core.EnvVars(viewModel.envVarsStr))
                    envVarsViewRef.value = ev
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showAddEnvVar = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.add) + " " + stringResource(R.string.environment_variables))
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

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DrivesTab(viewModel: ContainerDetailViewModel) {
    val context = LocalContext.current
    var pendingDriveUid by remember { mutableStateOf<Long?>(null) }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null && pendingDriveUid != null) {
                val path = FileUtils.getFilePathFromUri(context, uri)
                if (path != null) {
                    viewModel.updateDrivePath(pendingDriveUid!!, path)
                }
            }
        }
        pendingDriveUid = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (viewModel.drives.isEmpty()) {
            Text(
                stringResource(R.string.no_items_to_display),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        viewModel.drives.forEach { drive ->
            DriveRow(
                drive = drive,
                letterOptions = viewModel.driveLetterOptions,
                onLetterChange = { viewModel.updateDriveLetter(drive.uid, it) },
                onPathChange   = { viewModel.updateDrivePath(drive.uid, it)   },
                onBrowse = {
                    pendingDriveUid = drive.uid
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                            Uri.fromFile(Environment.getExternalStorageDirectory()))
                    }
                    dirPickerLauncher.launch(intent)
                },
                onRemove = { viewModel.removeDrive(drive.uid) }
            )
        }
        Button(
            onClick = { viewModel.addDrive() },
            enabled = viewModel.drives.size < Container.MAX_DRIVE_LETTERS,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.add) + " " + stringResource(R.string.drives))
        }
    }
}

@Composable
private fun DriveRow(
    drive: DriveEntry,
    letterOptions: List<String>,
    onLetterChange: (String) -> Unit,
    onPathChange: (String) -> Unit,
    onBrowse: () -> Unit,
    onRemove: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CompactDropdown(
            options = letterOptions,
            selectedOption = "${drive.letter}:",
            onSelect = { onLetterChange(it.trimEnd(':')) },
            modifier = Modifier.width(64.dp)
        )
        OutlinedTextField(
            value = drive.path,
            onValueChange = onPathChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Path") }
        )
        IconButton(onClick = onBrowse) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = null)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AdvancedTab(
    viewModel: ContainerDetailViewModel,
    cpuListViewRef: MutableState<CPUListView?>,
    cpuListWoW64Ref: MutableState<CPUListView?>,
    onShowBox64DownloadSheet: () -> Unit = {},
    onShowFexCoreDownloadSheet: () -> Unit = {},
) {
    val context = LocalContext.current
    // Flush legacy CPUListView selections back to the ViewModel before the tab
    // leaves composition, so a tab switch doesn't drop in-progress edits.
    DisposableEffect(Unit) {
        onDispose {
            cpuListViewRef.value?.let { viewModel.cpuList = it.checkedCPUListAsString }
            cpuListWoW64Ref.value?.let { viewModel.cpuListWoW64 = it.checkedCPUListAsString }
            cpuListViewRef.value = null
            cpuListWoW64Ref.value = null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Box64 section
        SectionBox(title = "Box64") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LabeledDropdown(
                    label = stringResource(R.string.box64_version),
                    options = viewModel.box64VersionEntries,
                    selectedOption = viewModel.selectedBox64Version,
                    onSelect = { viewModel.selectedBox64Version = it },
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onShowBox64DownloadSheet,
                    modifier = Modifier.size(40.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            LabeledDropdown(
                label = stringResource(R.string.box64_preset),
                options = viewModel.box64PresetEntries,
                selectedOption = viewModel.box64PresetEntries.getOrElse(viewModel.selectedBox64PresetIndex) { "" },
                onSelect = { opt -> viewModel.selectedBox64PresetIndex = viewModel.box64PresetEntries.indexOf(opt).coerceAtLeast(0) }
            )
        }

        // FEXCore section (arm64ec only)
        if (viewModel.isArm64EC) {
            SectionBox(title = "FEXCore") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledDropdown(
                        label = stringResource(R.string.fexcore_version),
                        options = viewModel.fexCoreVersionEntries,
                        selectedOption = viewModel.selectedFEXCoreVersion,
                        onSelect = { viewModel.selectedFEXCoreVersion = it },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onShowFexCoreDownloadSheet,
                        modifier = Modifier.size(40.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(
                    label = stringResource(R.string.fexcore_preset),
                    options = viewModel.fexCorePresetEntries,
                    selectedOption = viewModel.fexCorePresetEntries.getOrElse(viewModel.selectedFEXCorePresetIndex) { "" },
                    onSelect = { opt -> viewModel.selectedFEXCorePresetIndex = viewModel.fexCorePresetEntries.indexOf(opt).coerceAtLeast(0) }
                )
            }
        }

        // Game Controller section
        SectionBox(title = stringResource(R.string.game_controller)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = viewModel.enableXInput,
                    onCheckedChange = { viewModel.enableXInput = it },
                    enabled = viewModel.exclusiveXInput
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.enable_xinput_for_wine_game), modifier = Modifier.weight(1f))
                IconButton(onClick = { AppUtils.showHelpBox(context, View(context), R.string.help_xinput) }) {
                    Icon(Icons.Default.Help, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = viewModel.enableDInput,
                    onCheckedChange = { viewModel.enableDInput = it },
                    enabled = viewModel.exclusiveXInput
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.enable_dinput_for_wine_game), modifier = Modifier.weight(1f))
                IconButton(onClick = { AppUtils.showHelpBox(context, View(context), R.string.help_dinput) }) {
                    Icon(Icons.Default.Help, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = viewModel.exclusiveXInput,
                    onCheckedChange = { viewModel.onExclusiveXInputChanged(it) }
                )
                Spacer(Modifier.width(8.dp))
                Text("Exclusive Input", modifier = Modifier.weight(1f))
                IconButton(onClick = { AppUtils.showHelpBox(context, View(context), R.string.help_exclusive_xinput) }) {
                    Icon(Icons.Default.Help, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Startup Selection
        LabeledDropdown(
            label = stringResource(R.string.startup_selection),
            options = viewModel.startupSelectionEntries,
            selectedOption = viewModel.startupSelectionEntries.getOrElse(viewModel.selectedStartupSelection) { "" },
            onSelect = { opt -> viewModel.selectedStartupSelection = viewModel.startupSelectionEntries.indexOf(opt).coerceAtLeast(0) }
        )

        // Processor Affinity
        SectionBox(title = stringResource(R.string.processor_affinity)) {
            Text(
                stringResource(R.string.processor_affinity),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            AndroidView(
                factory = { ctx ->
                    CPUListView(ContextThemeWrapper(ctx, R.style.AppTheme_Dark)).also { cpv ->
                        cpv.setCheckedCPUList(viewModel.cpuList)
                        cpuListViewRef.value = cpv
                    }
                },
                update = {},
                modifier = Modifier.fillMaxWidth().wrapContentHeight()
            )
            if (viewModel.isArm64EC) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.processor_affinity_32_bit_apps),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                AndroidView(
                    factory = { ctx ->
                        CPUListView(ContextThemeWrapper(ctx, R.style.AppTheme_Dark)).also { cpv ->
                            cpv.setCheckedCPUList(viewModel.cpuListWoW64)
                            cpuListWoW64Ref.value = cpv
                        }
                    },
                    update = {},
                    modifier = Modifier.fillMaxWidth().wrapContentHeight()
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun XRTab(viewModel: ContainerDetailViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Primary controller
        LabeledDropdown(
            label = stringResource(R.string.primary_controller),
            options = viewModel.primaryControllerEntries,
            selectedOption = viewModel.primaryControllerEntries.getOrElse(viewModel.selectedPrimaryController) { "" },
            onSelect = { opt -> viewModel.selectedPrimaryController = viewModel.primaryControllerEntries.indexOf(opt).coerceAtLeast(0) }
        )

        // Controller button mappings
        SectionBox(title = "Controller Mapping") {
            viewModel.xrMappingLabels.forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(label, modifier = Modifier.weight(1f))
                    CompactDropdown(
                        options = viewModel.xrKeycodeNames,
                        selectedOption = viewModel.xrKeycodeNames.getOrElse(viewModel.xrMappingIndices.getOrElse(i) { 0 }) { "" },
                        onSelect = { opt ->
                            val idx = viewModel.xrKeycodeNames.indexOf(opt).coerceAtLeast(0)
                            if (i < viewModel.xrMappingIndices.size) viewModel.xrMappingIndices[i] = idx
                        },
                        modifier = Modifier.width(160.dp)
                    )
                }
                if (i < viewModel.xrMappingLabels.lastIndex) Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared composables
// ─────────────────────────────────────────────────────────────────────────────
@Composable
internal fun AddEnvVarComposable(
    onConfirm: (name: String, value: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var showPresets by remember { mutableStateOf(false) }

    val knownNames = remember { EnvVarsView.knownEnvVars.map { it[0] } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_environment_variable)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Box {
                    OutlinedButton(onClick = { showPresets = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Presets")
                    }
                    DropdownMenu(expanded = showPresets, onDismissRequest = { showPresets = false }) {
                        knownNames.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset) },
                                onClick = { name = preset; showPresets = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim().replace(" ", "")
                val v = value.trim().replace(" ", "")
                onConfirm(n, v)
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun SectionBox(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val accentColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp), content = content)
        }
    }
}

@Composable
internal fun LabeledDropdown(
    label: String,
    options: List<String>,
    selectedOption: String,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun CompactDropdown(
    options: List<String>,
    selectedOption: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedCard(
            modifier = Modifier
                .menuAnchor()
                .height(56.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = selectedOption,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
internal fun GraphicsDriverConfigDialog(
    graphicsDriver: String,
    initialConfig: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val cfg = remember(initialConfig) {
        initialConfig.split(";").associate { elem ->
            val parts = elem.split("=")
            parts[0] to if (parts.size > 1) parts[1] else ""
        }
    }

    var version          by remember { mutableStateOf(cfg["version"] ?: "") }
    var vulkanVersion    by remember { mutableStateOf(cfg["vulkanVersion"] ?: "1.3") }
    var gpuName          by remember { mutableStateOf(cfg["gpuName"] ?: "Device") }
    var presentMode      by remember { mutableStateOf(cfg["presentMode"] ?: "mailbox") }
    var resourceType     by remember { mutableStateOf(cfg["resourceType"] ?: "auto") }
    var bcnEmulation     by remember { mutableStateOf(cfg["bcnEmulation"] ?: "auto") }
    var bcnEmulationType by remember { mutableStateOf(cfg["bcnEmulationType"] ?: "software") }
    var bcnEmulationCache by remember { mutableStateOf(cfg["bcnEmulationCache"] ?: "0") }
    var syncFrame        by remember { mutableStateOf(cfg["syncFrame"] == "1") }
    var disablePresentWait by remember { mutableStateOf(cfg["disablePresentWait"] == "1") }
    var fdDevFeatures    by remember { mutableStateOf(cfg["fdDevFeatures"] == "1") }

    val deviceMemoryEntries = remember { context.resources.getStringArray(R.array.device_memory_entries).toList() }
    var selectedMemoryEntry by remember {
        val storedNum = cfg["maxDeviceMemory"] ?: "0"
        mutableStateOf(deviceMemoryEntries.firstOrNull { StringUtils.parseNumber(it) == storedNum } ?: deviceMemoryEntries.first())
    }

    var driverVersions      by remember { mutableStateOf(listOf<String>()) }
    var gpuNames            by remember { mutableStateOf(listOf("Device")) }
    var allExtensions       by remember { mutableStateOf(listOf<String>()) }
    val initialBlacklist = remember(initialConfig) {
        (cfg["blacklistedExtensions"] ?: "").split(",").filter { it.isNotEmpty() }.toSet()
    }
    var blacklisted   by remember { mutableStateOf(initialBlacklist) }
    var showAllDrivers by remember { mutableStateOf(false) }
    var showExtPicker by remember { mutableStateOf(false) }

    LaunchedEffect(showAllDrivers) {
        val atVersions = withContext(Dispatchers.IO) {
            AdrenotoolsManager(context).enumarateInstalledDrivers()
        }
        val gpuList = withContext(Dispatchers.IO) {
            val list = mutableListOf("Device")
            try {
                val json = FileUtils.readString(context, "gpu_cards.json")
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i).getString("name"))
            } catch (_: Exception) {}
            list
        }
        // isDriverSupported() is a native JNI call — must run on main thread to avoid
        // concurrent AdrenoTools hook invocations that cause SIGSEGV.
        val wrapperVersions = context.resources
            .getStringArray(R.array.wrapper_graphics_driver_version_entries)
            .let { arr ->
                if (showAllDrivers) arr.toList()
                else arr.filter { GPUInformation.isDriverSupported(it, context) }
            }

        driverVersions = wrapperVersions + atVersions
        gpuNames = gpuList
        if (version.isEmpty() || (wrapperVersions + atVersions).none { it.equals(version, ignoreCase = true) }) {
            version = wrapperVersions.firstOrNull { it.equals(DefaultVersion.WRAPPER_ADRENO, ignoreCase = true) }
                ?: wrapperVersions.firstOrNull { it.equals(DefaultVersion.WRAPPER, ignoreCase = true) }
                ?: wrapperVersions.firstOrNull() ?: version
        }
    }

    LaunchedEffect(version) {
        if (version.isNotEmpty()) {
            val exts = GPUInformation.enumerateExtensions(version, context)?.toList() ?: emptyList()
            allExtensions = exts
            if (version != cfg["version"]) blacklisted = emptySet()
        }
    }

    if (showExtPicker) {
        ExtensionPickerDialog(
            extensions = allExtensions,
            blacklisted = blacklisted,
            onDismiss = { showExtPicker = false },
            onConfirm = { newBlacklist -> blacklisted = newBlacklist; showExtPicker = false }
        )
    }

    val vulkanVersions      = remember { context.resources.getStringArray(R.array.vulkan_version_entries).toList() }
    val presentModeEntries  = remember { context.resources.getStringArray(R.array.present_mode_entries).toList() }
    val resourceTypeEntries = remember { context.resources.getStringArray(R.array.resource_type_entries).toList() }
    val bcnEmulationEntries = remember { context.resources.getStringArray(R.array.bcn_emulation_entries).toList() }
    val bcnTypeEntries      = remember { context.resources.getStringArray(R.array.bcn_emulation_type_entries).toList() }
    val bcnCacheEntries     = remember { context.resources.getStringArray(R.array.bcn_emulation_cache_entries).toList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.graphics_driver_configuration)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                LabeledDropdown(stringResource(R.string.graphics_driver_vulkan_version), vulkanVersions, vulkanVersion, { vulkanVersion = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(stringResource(R.string.graphics_driver_version), driverVersions, version, { version = it })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showAllDrivers, onCheckedChange = { showAllDrivers = it })
                    Text(stringResource(R.string.graphics_driver_show_incompatible))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showExtPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val enabled = allExtensions.size - blacklisted.size
                    Text(stringResource(R.string.graphics_driver_available_extensions) + " ($enabled/${allExtensions.size})")
                }
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(stringResource(R.string.gpu_name), gpuNames, gpuName, { gpuName = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(stringResource(R.string.graphics_driver_max_device_memory), deviceMemoryEntries, selectedMemoryEntry, { selectedMemoryEntry = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(stringResource(R.string.graphics_driver_present_modes), presentModeEntries, presentMode, { presentMode = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(stringResource(R.string.graphics_driver_resource_type), resourceTypeEntries, resourceType, { resourceType = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(stringResource(R.string.graphics_driver_bcn_emulation), bcnEmulationEntries, bcnEmulation, { bcnEmulation = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(stringResource(R.string.graphics_driver_bcn_emulation_type), bcnTypeEntries, bcnEmulationType, { bcnEmulationType = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(stringResource(R.string.graphics_driver_bcn_emulation_cache), bcnCacheEntries, bcnEmulationCache, { bcnEmulationCache = it })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = syncFrame, onCheckedChange = { syncFrame = it })
                    Text(stringResource(R.string.graphics_driver_sync_frame))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = disablePresentWait, onCheckedChange = { disablePresentWait = it })
                    Text(stringResource(R.string.graphics_driver_disable_present_wait))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fdDevFeatures, onCheckedChange = { fdDevFeatures = it })
                    Text("OneUI / HyperOS Fix")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = "vulkanVersion=$vulkanVersion;" +
                    "version=$version;" +
                    "blacklistedExtensions=${blacklisted.joinToString(",")};" +
                    "maxDeviceMemory=${StringUtils.parseNumber(selectedMemoryEntry)};" +
                    "presentMode=$presentMode;" +
                    "syncFrame=${if (syncFrame) "1" else "0"};" +
                    "disablePresentWait=${if (disablePresentWait) "1" else "0"};" +
                    "resourceType=$resourceType;" +
                    "bcnEmulation=$bcnEmulation;" +
                    "bcnEmulationType=$bcnEmulationType;" +
                    "bcnEmulationCache=$bcnEmulationCache;" +
                    "gpuName=$gpuName" +
                    ";fdDevFeatures=${if (fdDevFeatures) "1" else "0"}"
                onConfirm(config)
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
internal fun ExtensionPickerDialog(
    extensions: List<String>,
    blacklisted: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var state by remember(extensions, blacklisted) {
        mutableStateOf(extensions.associateWith { !blacklisted.contains(it) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.graphics_driver_available_extensions)) },
        text = {
            if (extensions.isEmpty()) {
                Text("No extensions available for this driver.")
            } else {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(extensions) { ext ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = state[ext] == true,
                                onCheckedChange = { checked ->
                                    state = state.toMutableMap().also { it[ext] = checked }
                                }
                            )
                            Text(ext, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newBlacklist = extensions.filter { state[it] != true }.toSet()
                onConfirm(newBlacklist)
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
internal fun DxvkConfigDialog(
    isArm64EC: Boolean,
    isVegas: Boolean = false,
    initialConfig: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDownloadDxvk: () -> Unit = {},
    onDownloadVkd3d: () -> Unit = {},
) {
    val context = LocalContext.current
    val config = remember(initialConfig) { DXVKConfigDialog.parseConfig(initialConfig) }

    val allDxvkVersions = remember { mutableStateOf(listOf<String>()) }
    val vkd3dVersions   = remember { mutableStateOf(listOf<String>()) }
    val configSourceEntries = remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cm = ContentsManager(context)
            cm.syncContents()
            val versions = if (isVegas)
                DXVKConfigDialog.loadVegasVersionList(context, cm)
            else
                DXVKConfigDialog.loadDxvkVersionList(context, cm, isArm64EC)
            val vkd3d = DXVKConfigDialog.loadVkd3dVersionList(context, cm)
            val cfgsrc = DXVKConfigDialog.loadVegasConfigSourceList(context)
            withContext(Dispatchers.Main) {
                allDxvkVersions.value = versions
                vkd3dVersions.value = vkd3d
                configSourceEntries.value = cfgsrc
            }
        }
    }

    var selectedVkd3d by remember { mutableStateOf(config.get("vkd3dVersion").ifEmpty { "None" }) }

    val filteredDxvk = remember(selectedVkd3d, allDxvkVersions.value) {
        if (selectedVkd3d != "None") {
            allDxvkVersions.value.filter { v ->
                val major = DXVKConfigDialog.tryGetMajor(v)
                major == null || major >= 2
            }
        } else allDxvkVersions.value
    }

    var selectedDxvk by remember(filteredDxvk) {
        val stored = config.get("version")
        mutableStateOf(filteredDxvk.firstOrNull { it == stored } ?: filteredDxvk.firstOrNull() ?: stored)
    }

    val dxvkType = remember(selectedDxvk) { DXVKConfigDialog.getDXVKType(selectedDxvk) }

    val framerateEntries  = remember { context.resources.getStringArray(R.array.dxvk_framerate_entries).toList() }
    val featureLevelEntries = remember { DXVKConfigDialog.VKD3D_FEATURE_LEVEL.toList() }
    val ddraEntries       = remember { context.resources.getStringArray(R.array.ddrawrapper_entries).toList() }
    val videoMemEntries   = remember { context.resources.getStringArray(R.array.dxvk_max_device_memory_entries).toList() }

    var selectedFramerate by remember {
        val stored = config.get("framerate")
        mutableStateOf(framerateEntries.firstOrNull { StringUtils.parseNumber(it) == stored } ?: framerateEntries.first())
    }
    var selectedFeatureLevel by remember { mutableStateOf(featureLevelEntries.firstOrNull { it == config.get("vkd3dLevel") } ?: featureLevelEntries.first()) }
    var selectedDdra         by remember { mutableStateOf(ddraEntries.firstOrNull { StringUtils.parseIdentifier(it) == config.get("ddrawrapper") } ?: ddraEntries.first()) }
    var selectedConfigSource by remember(configSourceEntries.value) {
        val stored = config.get("dxvkConfigFile")
        mutableStateOf(configSourceEntries.value.firstOrNull { it == stored } ?: configSourceEntries.value.firstOrNull() ?: "None")
    }
    var asyncEnabled         by remember { mutableStateOf(config.get("async") == "1") }
    var asyncCacheEnabled    by remember { mutableStateOf(config.get("asyncCache") == "1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isVegas) "VEGAS ${stringResource(R.string.configuration)}" else "DXVK ${stringResource(R.string.configuration)}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledDropdown(
                        stringResource(R.string.vkd3d_version), vkd3dVersions.value, selectedVkd3d, { selectedVkd3d = it },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onDownloadVkd3d,
                        modifier = Modifier.size(40.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Download VKD3D", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledDropdown(
                        if (isVegas) "Vegas Selector" else stringResource(R.string.dxvk_version),
                        filteredDxvk, selectedDxvk, { selectedDxvk = it },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onDownloadDxvk,
                        modifier = Modifier.size(40.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Download DXVK", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (dxvkType != DXVKConfigDialog.DXVK_TYPE_NONE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = asyncEnabled, onCheckedChange = { asyncEnabled = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Async")
                    }
                }
                if (dxvkType == DXVKConfigDialog.DXVK_TYPE_GPLASYNC) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = asyncCacheEnabled, onCheckedChange = { asyncCacheEnabled = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Async Cache")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                LabeledDropdown(stringResource(R.string.frame_rate), framerateEntries, selectedFramerate, { selectedFramerate = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown("VKD3D Feature Level", featureLevelEntries, selectedFeatureLevel, { selectedFeatureLevel = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown("DDraw Wrapper", ddraEntries, selectedDdra, { selectedDdra = it })
                if (isVegas) {
                    Spacer(Modifier.height(8.dp))
                    LabeledDropdown("Config Source", configSourceEntries.value, selectedConfigSource, { selectedConfigSource = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cfg = DXVKConfigDialog.parseConfig(initialConfig)
                cfg.put("version", selectedDxvk)
                cfg.put("framerate", StringUtils.parseNumber(selectedFramerate))
                cfg.put("async", if (asyncEnabled && dxvkType != DXVKConfigDialog.DXVK_TYPE_NONE) "1" else "0")
                cfg.put("asyncCache", if (asyncCacheEnabled && dxvkType == DXVKConfigDialog.DXVK_TYPE_GPLASYNC) "1" else "0")
                cfg.put("vkd3dVersion", selectedVkd3d)
                cfg.put("vkd3dLevel", selectedFeatureLevel)
                cfg.put("ddrawrapper", StringUtils.parseIdentifier(selectedDdra))
                cfg.put("dxvkConfigFile", if (selectedConfigSource == "None") "" else selectedConfigSource)
                onConfirm(cfg.toString())
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
internal fun WineD3DConfigDialog(
    initialConfig: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val config = remember(initialConfig) { WineD3DConfigDialog.parseConfig(initialConfig) }

    val csmtOptions   = remember { listOf("Enabled", "Disabled") }
    val ssmOptions    = remember { listOf("Enabled", "Disabled") }
    val ormOptions    = remember { listOf("fbo", "backbuffer") }
    val rendOptions   = remember { listOf("gl", "vulkan", "gdi") }
    val ddraEntries   = remember { context.resources.getStringArray(R.array.ddrawrapper_entries).toList() }
    val videoMemEntries = remember { context.resources.getStringArray(R.array.video_memory_size_entries).toList() }
    var gpuNames      by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val names = WineD3DConfigDialog.loadGpuNames(context)
            withContext(Dispatchers.Main) { gpuNames = names }
        }
    }

    var csmt      by remember { mutableStateOf(if (config.get("csmt") == "3") "Enabled" else "Disabled") }
    var gpuName   by remember { mutableStateOf(config.get("gpuName")) }
    var ddra      by remember { mutableStateOf(ddraEntries.firstOrNull { StringUtils.parseIdentifier(it) == config.get("ddrawrapper") } ?: ddraEntries.first()) }
    var videoMem  by remember {
        val stored = config.get("videoMemorySize")
        mutableStateOf(videoMemEntries.firstOrNull { StringUtils.parseNumber(it) == stored } ?: videoMemEntries.first())
    }
    var ssm       by remember { mutableStateOf(if (config.get("strict_shader_math") == "1") "Enabled" else "Disabled") }
    var orm       by remember { mutableStateOf(config.get("OffscreenRenderingMode").ifEmpty { "fbo" }) }
    var renderer  by remember { mutableStateOf(config.get("renderer").ifEmpty { "gl" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WineD3D ${stringResource(R.string.configuration)}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                LabeledDropdown("CSMT", csmtOptions, csmt, { csmt = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(stringResource(R.string.gpu_name), gpuNames, gpuName, { gpuName = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown("DDraw Wrapper", ddraEntries, ddra, { ddra = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown(stringResource(R.string.graphics_driver_max_device_memory), videoMemEntries, videoMem, { videoMem = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown("Strict Shader Math", ssmOptions, ssm, { ssm = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown("Offscreen Rendering Mode", ormOptions, orm, { orm = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown("Renderer", rendOptions, renderer, { renderer = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cfg = WineD3DConfigDialog.parseConfig(initialConfig)
                cfg.put("csmt", if (csmt == "Enabled") "3" else "0")
                cfg.put("strict_shader_math", if (ssm == "Enabled") "1" else "0")
                cfg.put("OffscreenRenderingMode", orm)
                cfg.put("gpuName", gpuName)
                cfg.put("ddrawrapper", StringUtils.parseIdentifier(ddra))
                cfg.put("videoMemorySize", StringUtils.parseNumber(videoMem))
                cfg.put("renderer", renderer)
                onConfirm(cfg.toString())
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
internal fun FpsCounterConfigDialog(
    initialConfig: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    fun parseConfig(s: String): Map<String, String> {
        if (s.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        s.split(",").forEach { part ->
            val eq = part.indexOf('=')
            if (eq >= 0) map[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return map
    }

    val cfg = remember(initialConfig) { parseConfig(initialConfig) }
    val modeOptions = remember { listOf("Vertical", "Horizontal") }
    var selectedMode by remember {
        mutableStateOf(
            if (cfg.getOrDefault("hudMode", "vertical") == "horizontal") "Horizontal" else "Vertical"
        )
    }
    var hudScale by remember { mutableStateOf(cfg.getOrDefault("hudScale", "100").toIntOrNull() ?: 100) }
    var hudTransparency by remember { mutableStateOf(cfg.getOrDefault("hudTransparency", "0").toIntOrNull() ?: 0) }

    fun buildConfig(): String = listOf(
        "hudMode=${if (selectedMode == "Horizontal") "horizontal" else "vertical"}",
        "showFPS=1",
        "showCPULoad=0",
        "showGPULoad=0",
        "showRAM=0",
        "showRenderer=0",
        "showBatteryTemp=0",
        "hudScale=$hudScale",
        "hudTransparency=$hudTransparency"
    ).joinToString(",")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("FPS Counter Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LabeledDropdown(
                    label = "FPS Counter Mode",
                    options = modeOptions,
                    selectedOption = selectedMode,
                    onSelect = { selectedMode = it }
                )

                Spacer(Modifier.height(12.dp))
                Text("HUD Scale: $hudScale%", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = hudScale.toFloat(),
                    onValueChange = { hudScale = it.toInt().coerceAtLeast(50) },
                    valueRange = 50f..150f,
                    steps = 99
                )
                Spacer(Modifier.height(4.dp))
                Text("HUD Transparency: $hudTransparency", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = hudTransparency.toFloat(),
                    onValueChange = { hudTransparency = it.toInt() },
                    valueRange = 0f..50f,
                    steps = 49
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(buildConfig()) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}


