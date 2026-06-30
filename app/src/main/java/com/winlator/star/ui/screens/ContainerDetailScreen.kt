@file:OptIn(ExperimentalMaterial3Api::class)
package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.MainActivity
import com.winlator.star.R
import com.winlator.star.ui.findActivity
import com.winlator.star.contentdialog.DXVKConfigDialog
import com.winlator.star.contentdialog.WineD3DConfigDialog
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.AppUtils
import com.winlator.star.core.DefaultVersion
import com.winlator.star.core.FileUtils
import com.winlator.star.core.GPUInformation
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WineThemeManager
import androidx.compose.foundation.lazy.items
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.Executors
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
    var showVulkanConfig          by remember { mutableStateOf(false) }
    // Bumped after a DXVK/VKD3D/Vegas download so the open DxvkConfigDialog re-reads its version lists.
    var dxvkRefreshKey           by remember { mutableStateOf(0) }

    // AndroidView references for custom views
    val envVarsViewRef      = remember { mutableStateOf<EnvVarsView?>(null)      }
    val cpuListViewRef      = remember { mutableStateOf<CPUListView?>(null)      }
    val cpuListWoW64Ref     = remember { mutableStateOf<CPUListView?>(null)      }
    val colorPickerViewRef  = remember { mutableStateOf<ColorPickerView?>(null)  }

    val tabTitles = listOf(
        "GENERAL",
        "ENVIROMENT",
        "DRIVES",
        "WIN COMPONENTS",
        "ADVANCED"
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
                    0 -> Column {
                        TopLevelFields(
                            viewModel = viewModel,
                            onShowGfxConfig = { showGraphicsDriverConfig = true },
                            onShowDxvkConfig = { showDxvkConfig = true },
                            onShowWineD3DConfig = { showWineD3DConfig = true },
                            onShowFpsConfig = { showFpsConfig = true },
                            onShowWineDownloadSheet = { showWineDownloadSheet = true },
                            onShowVulkanConfig = { showVulkanConfig = true },
                        )
                        WineConfigTab(viewModel, colorPickerViewRef)
                    }
                    1 -> EnvVarsTab(viewModel, envVarsViewRef)
                    2 -> DrivesTab(viewModel)
                    3 -> WinComponentsTab(viewModel)
                    4 -> Column {
                        AdvancedTab(
                            viewModel,
                            cpuListViewRef,
                            cpuListWoW64Ref,
                            onShowBox64DownloadSheet = { showBox64DownloadSheet = true },
                            onShowFexCoreDownloadSheet = { showFexCoreDownloadSheet = true },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        XRTab(viewModel)
                    }
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
    val isVegasWrapper = StringUtils.parseIdentifier(viewModel.selectedDXWrapper ?: "").contains("vegas")
    if (showDxvkConfig) {
        DxvkConfigDialog(
            isArm64EC = viewModel.isArm64EC,
            isVegas = isVegasWrapper,
            refreshKey = dxvkRefreshKey,
            initialConfig = viewModel.dxWrapperConfig,
            onConfirm = { newConfig -> viewModel.dxWrapperConfig = newConfig; showDxvkConfig = false },
            onDismiss = { showDxvkConfig = false },
            // Close the config dialog first — the download sheet is a ModalBottomSheet (activity
            // window) and would otherwise render BEHIND this AlertDialog. It reopens on sheet dismiss.
            onDownloadDxvk = { showDxvkConfig = false; if (isVegasWrapper) showVegasDownloadSheet = true else showDxvkDownloadSheet = true },
            onDownloadVkd3d = { showDxvkConfig = false; showVkd3dDownloadSheet = true }
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

    if (showVulkanConfig) {
        VulkanSettingsDialog(
            initialConfig = "native=${viewModel.rendererNative}" +
                ";presentMode=${viewModel.rendererPresentMode}" +
                ";driverId=${viewModel.rendererDriverId}" +
                ";filterMode=${viewModel.rendererFilterMode}" +
                ";swapRB=${viewModel.rendererSwapRB}",
            onConfirm = { newConfig ->
                val m = parseVulkanConfig(newConfig)
                viewModel.rendererNative      = m["native"] == "true"
                viewModel.rendererPresentMode = m["presentMode"] ?: "fifo"
                viewModel.rendererDriverId    = m["driverId"] ?: "system"
                viewModel.rendererFilterMode  = m["filterMode"]?.toIntOrNull() ?: 0
                viewModel.rendererSwapRB      = m["swapRB"] == "true"
                showVulkanConfig = false
            },
            onDismiss = { showVulkanConfig = false }
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
            onContentChanged = { viewModel.refreshWineVersions() },
            inUseKey = viewModel.selectedWineVersion,
        )
    }
    if (showBox64DownloadSheet) {
        ContentDownloadSheet(
            contentType = if (viewModel.isArm64EC)
                com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
            else
                com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_BOX64,
            onDismiss = { showBox64DownloadSheet = false },
            onContentChanged = { viewModel.refreshBox64Versions() },
            inUseKey = viewModel.selectedBox64Version,
        )
    }
    if (showFexCoreDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
            onDismiss = { showFexCoreDownloadSheet = false },
            onContentChanged = { viewModel.refreshFEXCoreVersions() },
            inUseKey = viewModel.selectedFEXCoreVersion,
        )
    }
    if (showDxvkDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_DXVK,
            onDismiss = { showDxvkDownloadSheet = false; showDxvkConfig = true },
            onContentChanged = { dxvkRefreshKey++ }
        )
    }
    if (showVkd3dDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
            onDismiss = { showVkd3dDownloadSheet = false; showDxvkConfig = true },
            onContentChanged = { dxvkRefreshKey++ }
        )
    }
    if (showVegasDownloadSheet) {
        VegasDownloadSheet(
            onDismiss = { showVegasDownloadSheet = false; showDxvkConfig = true },
            onContentChanged = { dxvkRefreshKey++ }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Parse the semicolon-separated Vulkan settings string ("native=..;presentMode=..;..") into a map.
private fun parseVulkanConfig(s: String): Map<String, String> =
    s.split(";").mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
    }.toMap()

@Composable
internal fun VulkanSettingsDialog(
    initialConfig: String,
    onConfirm: (newConfig: String) -> Unit,
    onDismiss: () -> Unit
) {
    // The config string is SEMICOLON-separated (see the confirm button below), so parse it that way.
    // (The old KeyValueSet path split on commas and silently returned every default.)
    val cfg = remember { parseVulkanConfig(initialConfig) }
    var nativeRender by remember { mutableStateOf(cfg["native"] == "true") }
    var presentMode by remember { mutableStateOf(cfg["presentMode"] ?: "fifo") }
    var driverId by remember { mutableStateOf(cfg["driverId"] ?: "system") }
    // Read-only now: editing moved to the in-game drawer "Scaling mode". Kept so the
    // persisted value round-trips through this dialog and still seeds the launch default.
    val filterMode = remember { cfg["filterMode"]?.toIntOrNull() ?: 0 }
    var swapRB by remember { mutableStateOf(cfg["swapRB"] == "true") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vulkan_settings)) },
        text = {
            // Cap the scrollable region so tall content scrolls inside the dialog instead of
            // pushing the OK/Cancel buttons off-screen (Material3 AlertDialog doesn't bound its
            // text slot height on its own).
            val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.renderer_native), Modifier.weight(1f))
                    Switch(checked = nativeRender, onCheckedChange = { nativeRender = it })
                }

                val presentModes = listOf("fifo", "mailbox", "immediate")
                val presentModeLabels = listOf(
                    stringResource(R.string.renderer_present_mode_fifo),
                    stringResource(R.string.renderer_present_mode_mailbox),
                    stringResource(R.string.renderer_present_mode_immediate)
                )
                val selectedPresentIdx = presentModes.indexOf(presentMode).coerceAtLeast(0)
                LabeledDropdown(
                    label = stringResource(R.string.renderer_present_mode),
                    options = presentModeLabels,
                    selectedOption = presentModeLabels[selectedPresentIdx],
                    onSelect = { presentMode = presentModes[presentModeLabels.indexOf(it)] }
                )

                val drivers = listOf("system", "turnip")
                val driverLabels = listOf(
                    stringResource(R.string.renderer_driver_system),
                    stringResource(R.string.renderer_driver_turnip)
                )
                val selectedDriverIdx = drivers.indexOf(driverId).coerceAtLeast(0)
                LabeledDropdown(
                    label = stringResource(R.string.renderer_driver_id),
                    options = driverLabels,
                    selectedOption = driverLabels[selectedDriverIdx],
                    onSelect = { driverId = drivers[driverLabels.indexOf(it)] }
                )

                // Filter mode (Nearest/Linear) is no longer edited here: the in-game
                // drawer's "Scaling mode" picker is the single source of truth for
                // Vulkan scaling/filtering (modes 1/2 drive the base sampler natively).
                // The persisted `filterMode` value is preserved untouched below and
                // still seeds the drawer's initial scaling mode at launch
                // (XServerDisplayActivity: getRendererFilterMode -> initialUpscaler).

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.renderer_swap_rb), Modifier.weight(1f))
                    Switch(checked = swapRB, onCheckedChange = { swapRB = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = "native=$nativeRender;presentMode=$presentMode;driverId=$driverId;filterMode=$filterMode;swapRB=$swapRB"
                onConfirm(config)
            }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TopLevelFields(
    viewModel: ContainerDetailViewModel,
    onShowGfxConfig: () -> Unit,
    onShowDxvkConfig: () -> Unit,
    onShowWineD3DConfig: () -> Unit,
    onShowFpsConfig: () -> Unit,
    onShowVulkanConfig: () -> Unit,
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
            ContentInstallGear(onDownloadFile = onShowWineDownloadSheet)
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

        // Renderer
        var showSfWarning by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LabeledDropdown(
                label = stringResource(R.string.renderer),
                options = viewModel.rendererEntries,
                selectedOption = viewModel.selectedRenderer,
                onSelect = {
                    // SurfaceFlinger is experimental and can reboot some devices — require opt-in.
                    if (it == "SurfaceFlinger" && viewModel.selectedRenderer != "SurfaceFlinger") showSfWarning = true
                    else viewModel.selectedRenderer = it
                },
                modifier = Modifier.weight(1f)
            )
            if (viewModel.selectedRenderer == "Vulkan") {
                IconButton(onClick = onShowVulkanConfig) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                }
            }
        }
        if (showSfWarning) {
            SurfaceFlingerWarningDialog(
                onConfirm = { viewModel.selectedRenderer = "SurfaceFlinger"; showSfWarning = false },
                onDismiss = { showSfWarning = false }
            )
        }
        Spacer(Modifier.height(8.dp))

        // Render scale (supersampling) — pre-launch override stored via the "renderScale" extra.
        // The game renders at this multiple of the display res; the Vulkan compositor then does a
        // quality downscale. "1.0" = Off.
        run {
            val renderScaleValues = listOf("1.0", "1.25", "1.5", "2.0")
            val renderScaleLabels = listOf("Off", "1.25x", "1.5x", "2x")
            val rsIdx = renderScaleValues.indexOf(viewModel.renderScale).coerceAtLeast(0)
            LabeledDropdown(
                label = "Render scale (supersampling)",
                options = renderScaleLabels,
                selectedOption = renderScaleLabels[rsIdx],
                onSelect = { viewModel.renderScale = renderScaleValues[renderScaleLabels.indexOf(it)] }
            )
        }
        Spacer(Modifier.height(8.dp))

        // Auto-close the session when the launched game exits (default ON). Avoids being left on the
        // empty Wine desktop (black screen) after quitting. Applies to game-shortcut launches.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = viewModel.autoCloseOnExit,
                onCheckedChange = { viewModel.autoCloseOnExit = it }
            )
            Spacer(Modifier.width(8.dp))
            Text("Close when game exits")
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

        // Frame Generation engine: Off / bionic-fg / lsfg-vk (mutually exclusive). lsfg-vk is grayed
        // out until a Lossless.dll is imported (Settings). This is the ONLY per-container FG control;
        // the multiplier & flow scale for BOTH engines are tuned live from the in-game side menu.
        val fgEngines = listOf("off", "bionic", "lsfg")
        val fgEngineLabels = listOf(
            stringResource(R.string.frame_generation_off),
            stringResource(R.string.frame_generation_bionic),
            stringResource(R.string.frame_generation_lsfg)
        )
        val lsfgDllAvailable = remember { java.io.File(context.filesDir, "lsfg-vk/Lossless.dll").isFile }
        val fgDisabledOpts = if (lsfgDllAvailable) emptySet() else setOf(fgEngineLabels[2])
        val fgSelIdx = fgEngines.indexOf(viewModel.frameGenEngine).coerceAtLeast(0)
        LabeledDropdown(
            label = stringResource(R.string.frame_generation),
            options = fgEngineLabels,
            selectedOption = fgEngineLabels[fgSelIdx],
            onSelect = { viewModel.frameGenEngine = fgEngines[fgEngineLabels.indexOf(it)] },
            disabledOptions = fgDisabledOpts
        )
        if (!lsfgDllAvailable) {
            Text(
                text = stringResource(R.string.frame_generation_lsfg_needs_dll),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
            )
        }
        if (viewModel.frameGenEngine == "bionic") {
            Text(
                text = stringResource(R.string.frame_generation_ingame_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
            )
        }
        if (viewModel.frameGenEngine == "lsfg") {
            Text(
                text = stringResource(R.string.frame_generation_lsfg_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
            )
        }

        // FPS Limiter (bionic-fg). This switch just loads the layer; the cap value is set live
        // from the in-game FPS menu. (Frame Generation also loads the layer, so this is only
        // needed if you want a cap without frame gen.)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = viewModel.fpsLimiterEnabled,
                onCheckedChange = { viewModel.fpsLimiterEnabled = it }
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.fps_limiter), modifier = Modifier.weight(1f))
        }
        if (viewModel.fpsLimiterEnabled) {
            Text(
                text = stringResource(R.string.fps_limiter_ingame_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
            )
        }

        // Match refresh rate to FPS (VRR). Greyed out on displays that can't do it (single refresh
        // rate or pre-Android-11); otherwise safe to leave on (no-op unless the FPS limiter is capping).
        val vrrCtx = LocalContext.current
        val vrrDisplay = remember {
            if (android.os.Build.VERSION.SDK_INT >= 30) vrrCtx.display
            else (vrrCtx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        }
        val vrrCapable = remember { com.winlator.star.widget.XServerView.isDisplayVrrCapable(vrrDisplay) }
        val supportedRates = remember { com.winlator.star.widget.XServerView.getSupportedRefreshRates(vrrDisplay) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = viewModel.matchRefreshRate && vrrCapable,
                enabled = vrrCapable,
                onCheckedChange = { viewModel.matchRefreshRate = it }
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.auto_match_fps), modifier = Modifier.weight(1f))
        }
        Text(
            text = if (vrrCapable) stringResource(R.string.match_refresh_rate_hint)
                   else stringResource(R.string.match_refresh_rate_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
        )
        // Manual refresh-rate lock (Auto OFF). Persists viewModel.manualRefreshRate (0 = free).
        if (vrrCapable && supportedRates.isNotEmpty()) {
            val manualEnabled = !viewModel.matchRefreshRate
            Text(
                stringResource(R.string.manual_refresh_rate),
                style = MaterialTheme.typography.bodySmall,
                color = if (manualEnabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp)
            )
            Row(modifier = Modifier.padding(start = 52.dp, top = 2.dp)) {
                FilterChip(
                    selected = viewModel.manualRefreshRate == 0,
                    enabled = manualEnabled,
                    onClick = { viewModel.manualRefreshRate = 0 },
                    label = { Text("Off") },
                    modifier = Modifier.padding(end = 6.dp)
                )
                supportedRates.forEach { rate ->
                    FilterChip(
                        selected = viewModel.manualRefreshRate == rate,
                        enabled = manualEnabled,
                        onClick = { viewModel.manualRefreshRate = rate },
                        label = { Text("$rate") },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.manual_refresh_rate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
            )
        }

        // ReShade effect (vkBasalt drop-in), per-container default. The per-game shortcut editor has
        // the same picker and overrides this. Only applies to DXVK/VKD3D (Vulkan) games.
        val reshadeWrapper = StringUtils.parseIdentifier(viewModel.selectedDXWrapper ?: "")
        val reshadeSupported = reshadeWrapper.contains("dxvk") || reshadeWrapper.contains("vegas")
        // Catalog picker: browse/download effects from reshade.json; installed ones are selectable.
        ReshadeEffectPicker(
            selected = viewModel.reshadeEffect,
            supported = reshadeSupported,
            onSelect = { viewModel.selectReshadeEffect(it, viewModel.container?.getReshadeParams() ?: "") },
            onCatalogChanged = { viewModel.rescanReshadeEffects() },
        )
        if (viewModel.reshadeEffect != "None") {
            val params = viewModel.reshadeEffects.firstOrNull { it.name == viewModel.reshadeEffect }?.params ?: emptyList()
            params.forEach { p ->
                val value = viewModel.reshadeParamValues[p.name] ?: p.defaultValue
                when (p.type) {
                    com.winlator.star.reshade.ReshadeManager.ParamType.BOOL -> {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 52.dp)) {
                            Switch(checked = value >= 0.5f, onCheckedChange = { viewModel.reshadeParamValues[p.name] = if (it) 1f else 0f })
                            Spacer(Modifier.width(8.dp))
                            Text(p.label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    com.winlator.star.reshade.ReshadeManager.ParamType.COMBO -> {
                        val options = p.options ?: emptyList()
                        LabeledDropdown(
                            label = p.label,
                            options = options,
                            selectedOption = options.getOrElse(value.toInt()) { options.firstOrNull() ?: "" },
                            onSelect = { sel -> viewModel.reshadeParamValues[p.name] = options.indexOf(sel).coerceAtLeast(0).toFloat() }
                        )
                    }
                    com.winlator.star.reshade.ReshadeManager.ParamType.COLOR -> {
                        Text(p.label, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 52.dp, top = 2.dp))
                        val comp = listOf("R", "G", "B", "A")
                        for (c in 0 until p.components) {
                            val k = "${p.name}_$c"
                            val cv = viewModel.reshadeParamValues[k] ?: p.componentDefaults?.getOrNull(c) ?: 0f
                            Text("${comp.getOrElse(c) { "$c" }}: ${"%.2f".format(cv)}",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 52.dp))
                            Slider(
                                value = cv.coerceIn(0f, 1f),
                                onValueChange = { viewModel.reshadeParamValues[k] = it },
                                valueRange = 0f..1f,
                                modifier = Modifier.padding(start = 52.dp)
                            )
                        }
                    }
                    else -> {
                        val display = if (p.type == com.winlator.star.reshade.ReshadeManager.ParamType.INT)
                            value.toInt().toString() else "%.2f".format(value)
                        Text("${p.label}: $display", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 52.dp, top = 2.dp))
                        Slider(
                            value = value.coerceIn(p.min, p.max),
                            onValueChange = { viewModel.reshadeParamValues[p.name] = it },
                            valueRange = p.min..p.max,
                            modifier = Modifier.padding(start = 52.dp)
                        )
                    }
                }
            }
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
    var showComponentsSheet by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Components installer (only for an existing container with a Wine prefix).
        if (viewModel.container != null) {
            SectionBox(title = "Components") {
                Text(
                    "Install Wine dependencies — mono, gecko, .NET, VC++ runtimes, DirectX libraries, fonts and more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showComponentsSheet = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Browse & install components")
                }
            }
        }
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

    if (showComponentsSheet) {
        viewModel.container?.let { container ->
            ComponentsSheet(container = container, onDismiss = { showComponentsSheet = false })
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

        // Box64 / WOWBox64 section — arm64ec wrappers use WOWBox64, everything else Box64.
        val emulatorLabel = if (viewModel.isArm64EC) "WOWBox64" else "Box64"
        SectionBox(title = emulatorLabel) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LabeledDropdown(
                    label = "$emulatorLabel Version",
                    options = viewModel.box64VersionEntries,
                    selectedOption = viewModel.selectedBox64Version,
                    onSelect = { viewModel.selectedBox64Version = it },
                    modifier = Modifier.weight(1f)
                )
                ContentInstallGear(onDownloadFile = onShowBox64DownloadSheet)
            }
            Spacer(Modifier.height(8.dp))
            LabeledDropdown(
                label = "$emulatorLabel Preset",
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
                    ContentInstallGear(onDownloadFile = onShowFexCoreDownloadSheet)
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
    disabledOptions: Set<String> = emptySet(),
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
                val optEnabled = opt !in disabledOptions
                DropdownMenuItem(
                    text = { Text(opt) },
                    enabled = optEnabled,
                    onClick = { if (optEnabled) { onSelect(opt); expanded = false } }
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
            // Cap the scrollable region so tall content scrolls inside the dialog instead of
            // pushing the OK/Cancel buttons off-screen (Material3 AlertDialog doesn't bound its
            // text slot height on its own).
            val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState())
            ) {
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
    refreshKey: Int = 0,
    initialConfig: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDownloadDxvk: () -> Unit = {},
    onDownloadVkd3d: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember(initialConfig) { DXVKConfigDialog.parseConfig(initialConfig) }

    val allDxvkVersions = remember { mutableStateOf(listOf<String>()) }
    val vkd3dVersions   = remember { mutableStateOf(listOf<String>()) }
    val configSourceEntries = remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(refreshKey) {
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

    var selectedDxvk by remember(allDxvkVersions.value) {
        val stored = config.get("version")
        mutableStateOf(allDxvkVersions.value.firstOrNull { it == stored } ?: allDxvkVersions.value.firstOrNull() ?: stored)
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
                    ContentInstallGear(onDownloadFile = onDownloadVkd3d)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledDropdown(
                        if (isVegas) "Vegas Selector" else stringResource(R.string.dxvk_version),
                        allDxvkVersions.value, selectedDxvk, { selectedDxvk = it },
                        modifier = Modifier.weight(1f)
                    )
                    ContentInstallGear(
                        onDownloadFile = onDownloadDxvk
                    )
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
    fun bool(k: String, fallbackKey: String, d: String) =
        cfg.getOrDefault(k, cfg.getOrDefault(fallbackKey, d)) == "1"

    // Orientation (vertical/horizontal) is toggled live by tapping the HUD in-game; preserve it.
    val hudMode = remember { cfg.getOrDefault("hudMode", "vertical") }
    var gameHub by remember { mutableStateOf(cfg.getOrDefault("hudStyle", "classic") == "gamehub") }

    // Unified metric toggles (emitted under both classic + gamehub key names so either HUD honors them).
    var showFPS      by remember { mutableStateOf(bool("showFPS", "showFPS", "1")) }
    var showGraph    by remember { mutableStateOf(bool("showFPSGraph", "showFPSGraph", "0")) }
    var showCPU      by remember { mutableStateOf(bool("showCPUUsage", "showCPULoad", "1")) }
    var showGPU      by remember { mutableStateOf(bool("showGPULoad", "showGPULoad", "1")) }
    var showRAM      by remember { mutableStateOf(bool("showRAM", "showRAM", "1")) }
    var showPower    by remember { mutableStateOf(bool("showPower", "showPower", "1")) }
    var showTemp     by remember { mutableStateOf(bool("showTemp", "showBatteryTemp", "1")) }
    var showEngine   by remember { mutableStateOf(bool("showEngine", "showRenderer", "1")) }
    var showGpuModel by remember { mutableStateOf(bool("showGpuModel", "showGpuModel", "0")) }
    var dualBattery  by remember { mutableStateOf(bool("hudDualBattery", "hudDualBattery", "0")) }

    var hudScale by remember { mutableStateOf(cfg.getOrDefault("hudScale", "92").toIntOrNull() ?: 92) }
    var hudOpacity by remember { mutableStateOf(cfg.getOrDefault("hudOpacity", "80").toIntOrNull() ?: 80) }
    var hudTransparency by remember { mutableStateOf(cfg.getOrDefault("hudTransparency", "0").toIntOrNull() ?: 0) }

    val skins = listOf("classic", "neon", "mono")
    val colors = listOf("soft", "mid", "vivid")
    val outlines = listOf("off", "soft", "strong")
    var skin by remember { mutableStateOf(cfg.getOrDefault("hudSkin", "classic")) }
    var color by remember { mutableStateOf(cfg.getOrDefault("hudColor", "mid")) }
    var outline by remember { mutableStateOf(cfg.getOrDefault("hudOutline", "soft")) }

    fun i(v: Boolean) = if (v) "1" else "0"
    fun buildConfig(): String = listOf(
        "hudStyle=${if (gameHub) "gamehub" else "classic"}",
        "hudMode=$hudMode",
        "showFPS=${i(showFPS)}",
        "showFPSGraph=${i(showGraph)}",
        "showCPUUsage=${i(showCPU)}",
        "showCPULoad=${i(showCPU)}",
        "showGPULoad=${i(showGPU)}",
        "showRAM=${i(showRAM)}",
        "showPower=${i(showPower)}",
        "showTemp=${i(showTemp)}",
        "showBatteryTemp=${i(showTemp)}",
        "showEngine=${i(showEngine)}",
        "showRenderer=${i(showEngine)}",
        "showGpuModel=${i(showGpuModel)}",
        "hudDualBattery=${i(dualBattery)}",
        "hudSkin=$skin",
        "hudColor=$color",
        "hudOutline=$outline",
        "hudScale=$hudScale",
        "hudOpacity=$hudOpacity",
        "hudTransparency=$hudTransparency"
    ).joinToString(",")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("FPS Counter Settings") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.7f).dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = gameHub, onCheckedChange = { gameHub = it })
                    Spacer(Modifier.width(8.dp))
                    Text("GameHub-style HUD", modifier = Modifier.weight(1f))
                }
                Text(
                    if (gameHub) "Rich overlay: skins, colored fields, live FPS graph."
                    else "Classic Bannerlator overlay.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tip: tap the HUD in-game to switch vertical/horizontal layout.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))

                HudToggleRow("Frame rate (FPS)", showFPS) { showFPS = it }
                if (gameHub) HudToggleRow("FPS graph", showGraph) { showGraph = it }
                HudToggleRow("CPU", showCPU) { showCPU = it }
                HudToggleRow("GPU", showGPU) { showGPU = it }
                HudToggleRow("Memory (RAM)", showRAM) { showRAM = it }
                HudToggleRow("Power", showPower) { showPower = it }
                HudToggleRow("Temperature", showTemp) { showTemp = it }
                HudToggleRow("Engine", showEngine) { showEngine = it }
                if (gameHub) {
                    HudToggleRow("GPU model", showGpuModel) { showGpuModel = it }
                    HudToggleRow("Dual-battery power fix", dualBattery) { dualBattery = it }
                }

                Spacer(Modifier.height(12.dp))
                Text("HUD Scale: $hudScale%", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = hudScale.toFloat(),
                    onValueChange = { hudScale = it.toInt().coerceAtLeast(50) },
                    valueRange = 50f..150f, steps = 99
                )

                if (gameHub) {
                    Spacer(Modifier.height(4.dp))
                    Text("HUD Opacity: $hudOpacity%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = hudOpacity.toFloat(),
                        onValueChange = { hudOpacity = it.toInt() },
                        valueRange = 0f..100f, steps = 99
                    )
                    Spacer(Modifier.height(8.dp))
                    HudThreeStop("HUD skin", listOf("Classic", "Neon", "Mono"), skins.indexOf(skin)) { skin = skins[it] }
                    HudThreeStop("HUD color", listOf("Soft", "Mid", "Vivid"), colors.indexOf(color)) { color = colors[it] }
                    HudThreeStop("HUD outline", listOf("Off", "Soft", "Strong"), outlines.indexOf(outline)) { outline = outlines[it] }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text("HUD Transparency: $hudTransparency", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = hudTransparency.toFloat(),
                        onValueChange = { hudTransparency = it.toInt() },
                        valueRange = 0f..50f, steps = 49
                    )
                }
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

@Composable
private fun HudToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HudThreeStop(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    Row {
        options.forEachIndexed { idx, opt ->
            FilterChip(
                selected = selected == idx,
                onClick = { onSelect(idx) },
                label = { Text(opt) },
                modifier = Modifier.padding(end = 6.dp)
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// Inline install helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun installContentFromUri(activity: Activity, uri: Uri, onResult: (Boolean) -> Unit) {
    val cm = ContentsManager(activity)
    Executors.newSingleThreadExecutor().execute {
        try {
            cm.extraContentFile(uri, object : ContentsManager.OnInstallFinishedCallback {
                var phase = 0
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Install failed: $reason", Toast.LENGTH_LONG).show()
                        onResult(false)
                    }
                }
                override fun onSucceed(profile: ContentProfile) {
                    try {
                        if (phase == 0) {
                            phase = 1
                            cm.finishInstallContent(profile, this)
                        } else {
                            cm.syncContents()
                            activity.runOnUiThread { onResult(true) }
                        }
                    } catch (e: Exception) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
                            onResult(false)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            activity.runOnUiThread {
                Toast.makeText(activity, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
                onResult(false)
            }
        }
    }
}

@Composable
private fun ContentInstallGear(
    onDownloadFile: () -> Unit,
) {
    // Cloud opens the download menu directly. Browse/download + "install from file" both live in the sheet.
    IconButton(onClick = onDownloadFile, modifier = Modifier.size(40.dp)) {
        Icon(
            Icons.Default.CloudDownload,
            contentDescription = "Download / install",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}


