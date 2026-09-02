@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.MainActivity
import com.winlator.star.R
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.components.AudioSettingsDialog
import com.winlator.star.ui.components.audioConfigFromEnv
import com.winlator.star.ui.components.audioConfigToEnv
import com.winlator.star.contentdialog.DXVKConfigDialog
import com.winlator.star.contentdialog.VegasKeyCatalog
import com.winlator.star.contentdialog.VegasKeyKnowledge
import com.winlator.star.contentdialog.VegasTierPresets
import com.winlator.star.contentdialog.WineD3DConfigDialog
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.AppUtils
import com.winlator.star.core.DefaultVersion
import com.winlator.star.core.FileUtils
import com.winlator.star.core.GPUInformation
import com.winlator.star.core.ImageUtils
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WineUtils
import com.winlator.star.core.WineThemeManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.concurrent.Executors
import com.winlator.star.container.Container
import com.winlator.star.container.VegasLiveCheck
import com.winlator.star.core.HttpUtils
import com.winlator.star.widget.ColorPickerView
import com.winlator.star.widget.CPUListView
import com.winlator.star.widget.EnvVarsView

// Serializes all native adrenotools probing (isDriverSupported + enumerateExtensions) off the
// main thread. Serial = no concurrent AdrenoTools hooks (old SIGSEGV); off-main = no ANR.
private val graphicsProbeMutex = Mutex()

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
    var showStockConfigSheet     by remember { mutableStateOf(false) }
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
            containerRootDir = viewModel.container?.rootDir,
            refreshKey = dxvkRefreshKey,
            initialConfig = viewModel.dxWrapperConfig,
            onConfirm = { newConfig ->
                viewModel.dxWrapperConfig = newConfig
                // Persist the config-file pointer to the container immediately — OK alone (no
                // live key toggle) must not leave the launched game reading a stale container
                // config without dxvkConfigFile. Mirrors onLivePointerChanged below: the launch
                // path (XServerDisplayActivity) reads container.getDXWrapperConfig(), NOT the
                // in-memory viewModel, so the pointer has to land on disk here or VEGAS gets
                // no DXVK_CONFIG_FILE -> tier 1.
                viewModel.container?.let { c ->
                    val file = DXVKConfigDialog.parseConfig(newConfig).get("dxvkConfigFile")
                    val ckv = DXVKConfigDialog.parseConfig(c.getDXWrapperConfig())
                    if (ckv.get("dxvkConfigFile") != file) {
                        ckv.put("dxvkConfigFile", file)
                        c.setDXWrapperConfig(ckv.toString())
                        c.saveData()
                    }
                }
                showDxvkConfig = false
            },
            onDismiss = { showDxvkConfig = false },
            // Every live-file write points the container at that file immediately —
            // launched games see toggles without needing OK, and without the full
            // form save. Keeps viewModel state and disk in sync.
            onLivePointerChanged = { p ->
                val kv = DXVKConfigDialog.parseConfig(viewModel.dxWrapperConfig)
                if (kv.get("dxvkConfigFile") != p) {
                    kv.put("dxvkConfigFile", p)
                    viewModel.dxWrapperConfig = kv.toString()
                    viewModel.container?.let { c ->
                        val ckv = DXVKConfigDialog.parseConfig(c.getDXWrapperConfig())
                        ckv.put("dxvkConfigFile", p)
                        c.setDXWrapperConfig(ckv.toString())
                        c.saveData()
                    }
                }
            },
            // Close the config dialog first — the download sheet is a ModalBottomSheet (activity
            // window) and would otherwise render BEHIND this AlertDialog. It reopens on sheet dismiss.
            onDownloadDxvk = { showDxvkConfig = false; if (isVegasWrapper) showVegasDownloadSheet = true else showDxvkDownloadSheet = true },
            onOpenConfigDownload = { showDxvkConfig = false; showStockConfigSheet = true },
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
                ";swapRB=${viewModel.rendererSwapRB}" +
                ";sfCompatMode=${viewModel.rendererSfCompatMode}" +
                ";nativeBackend=${viewModel.rendererNativeBackend}",
            onConfirm = { newConfig ->
                val m = parseVulkanConfig(newConfig)
                viewModel.rendererNative      = m["native"] == "true"
                viewModel.rendererPresentMode = m["presentMode"] ?: "fifo"
                viewModel.rendererDriverId    = m["driverId"] ?: "system"
                viewModel.rendererFilterMode  = m["filterMode"]?.toIntOrNull() ?: 0
                viewModel.rendererSwapRB      = m["swapRB"] == "true"
                // Default ON: absent token (old config) resolves to true (correct colours).
                viewModel.rendererSfCompatMode = m["sfCompatMode"] != "false"
                // Default "auto": absent token (old config) preserves the current reroute behaviour.
                viewModel.rendererNativeBackend = m["nativeBackend"] ?: "auto"
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
    if (showStockConfigSheet) {
        StockConfigDownloadSheet(
            onDismiss = { showStockConfigSheet = false; showDxvkConfig = true }
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
    // Per-field "?" help (upstream pattern): this dialog carries its own helpRes.
    var helpRes by remember { mutableStateOf<Int?>(null) }
    helpRes?.let { HelpDialog(it) { helpRes = null } }
    var nativeRender by remember { mutableStateOf(cfg["native"] == "true") }
    var presentMode by remember { mutableStateOf(cfg["presentMode"] ?: "fifo") }
    var driverId by remember { mutableStateOf(cfg["driverId"] ?: "system") }
    // Read-only now: editing moved to the in-game drawer "Scaling mode". Kept so the
    // persisted value round-trips through this dialog and still seeds the launch default.
    val filterMode = remember { cfg["filterMode"]?.toIntOrNull() ?: 0 }
    var swapRB by remember { mutableStateOf(cfg["swapRB"] == "true") }
    // SurfaceFlinger (ASR) BGRA->RGBA colour correction (GN #1620). Default ON — an absent token
    // (old config) resolves to true. ASR-only; independent of swapRB (Vulkan/GL).
    var sfCompatMode by remember { mutableStateOf(cfg["sfCompatMode"] != "false") }
    // Native backend for Native Rendering: "auto"/"asr" -> hardened SurfaceFlinger (ASR) reroute;
    // "flip" -> force the leaner Vulkan FLIP direct-scanout. Default "auto" — an absent token (old
    // config) resolves to auto (unchanged behaviour). Only meaningful while Native Rendering is on.
    var nativeBackend by remember { mutableStateOf(cfg["nativeBackend"] ?: "auto") }

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

                // Native backend picker — only meaningful while Native Rendering is on, so it's shown
                // only then. "auto"/"asr" route to the hardened SurfaceFlinger (ASR) renderer when
                // eligible; "flip" forces the leaner legacy Vulkan direct-scanout path.
                if (nativeRender) {
                    val nativeBackends = listOf("auto", "asr", "flip")
                    val nativeBackendLabels = listOf(
                        stringResource(R.string.renderer_native_backend_auto),
                        stringResource(R.string.renderer_native_backend_asr),
                        stringResource(R.string.renderer_native_backend_flip)
                    )
                    val selectedBackendIdx = nativeBackends.indexOf(nativeBackend).coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabeledDropdown(
                            label = stringResource(R.string.renderer_native_backend),
                            options = nativeBackendLabels,
                            selectedOption = nativeBackendLabels[selectedBackendIdx],
                            onSelect = { nativeBackend = nativeBackends[nativeBackendLabels.indexOf(it)] },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { helpRes = R.string.help_renderer_native_backend }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
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
                // NOTE: "Correct SurfaceFlinger colours" (sfCompatMode) is NOT shown here — this
                // dialog only opens for the Vulkan renderer, and that toggle only affects
                // SurfaceFlinger. It's surfaced inline under the Renderer dropdown instead (see
                // below). sfCompatMode is still round-tripped through this dialog's config so a
                // Vulkan user hitting OK never drops the stored value.
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = "native=$nativeRender;presentMode=$presentMode;driverId=$driverId;filterMode=$filterMode;swapRB=$swapRB;sfCompatMode=$sfCompatMode;nativeBackend=$nativeBackend"
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
    // Per-field "?" help — a centered, scrollable Compose dialog (HelpDialog). null = no dialog.
    var helpRes by remember { mutableStateOf<Int?>(null) }
    var showAudioSettings by remember { mutableStateOf(false) }
    helpRes?.let { HelpDialog(it) { helpRes = null } }

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
        // SurfaceFlinger colour correction (ASR-only, GN #1620) — surfaced inline under the renderer
        // choice, only when SurfaceFlinger is selected (mirrors the per-game shortcut editor). The
        // renderer-settings gear only appears for Vulkan, so this toggle would otherwise be
        // unreachable for the very renderer it applies to.
        if (viewModel.selectedRenderer == "SurfaceFlinger") {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.renderer_sf_compat))
                    Text(
                        stringResource(R.string.renderer_sf_compat_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.rendererSfCompatMode,
                    onCheckedChange = { viewModel.rendererSfCompatMode = it }
                )
            }
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
        // DirectAudio only loads on the four arm64ec Proton builds its .drv is built for; off those
        // layers it does nothing / breaks audio. Grey the option out (keyed on the selected layer so it
        // re-evaluates when the Wine version changes) and never let it be picked there. The ViewModel
        // also coerces it back to the default on save / layer-change, so the two can't drift.
        val directAudioSupported = remember(viewModel.selectedWineVersion) {
            com.winlator.star.core.DirectAudioSupport.isSupported(viewModel.selectedWineVersion)
        }
        val directAudioEntry = remember(viewModel.audioDriverEntries) {
            viewModel.audioDriverEntries.firstOrNull { StringUtils.parseIdentifier(it) == "directaudio" }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabeledDropdown(
                label = stringResource(R.string.audio_driver),
                options = viewModel.audioDriverEntries,
                selectedOption = viewModel.selectedAudioDriver,
                disabledOptions = if (!directAudioSupported && directAudioEntry != null) setOf(directAudioEntry) else emptySet(),
                onSelect = {
                    viewModel.selectedAudioDriver = it
                    // DirectAudio is experimental — warn on select (reuses the HelpDialog surface).
                    if (StringUtils.parseIdentifier(it) == "directaudio") helpRes = R.string.directaudio_experimental_warning
                },
                modifier = Modifier.weight(1f)
            )
            // Cog → adaptive audio presets & fine-tuning. Both engines honor the same presets/knobs
            // (PulseAudio sink + ALSA player), so it's shown for either driver.
            val audioId = StringUtils.parseIdentifier(viewModel.selectedAudioDriver)
            if (audioId == "pulseaudio" || audioId == "alsa" || audioId == "directaudio") {
                IconButton(onClick = { showAudioSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Audio settings", modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = { helpRes = R.string.help_audio_driver }) {
                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
            }
        }
        if (!directAudioSupported && directAudioEntry != null) {
            Text(
                "DirectAudio requires Proton ${com.winlator.star.core.DirectAudioSupport.SUPPORTED_LABEL}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
        if (showAudioSettings) {
            AudioSettingsDialog(
                initial = audioConfigFromEnv(viewModel.envVarsStr, StringUtils.parseIdentifier(viewModel.selectedAudioDriver)),
                scopeLabel = "this container",
                latencyLive = true,
                driverLabel = when (StringUtils.parseIdentifier(viewModel.selectedAudioDriver)) {
                    "alsa" -> "ALSA"; "pulseaudio" -> "PulseAudio"; "directaudio" -> "DirectAudio"
                    else -> StringUtils.parseIdentifier(viewModel.selectedAudioDriver)
                },
                driverId = StringUtils.parseIdentifier(viewModel.selectedAudioDriver),
                onDismiss = { showAudioSettings = false },
                onSave = { cfg ->
                    viewModel.envVarsStr = audioConfigToEnv(viewModel.envVarsStr, cfg, StringUtils.parseIdentifier(viewModel.selectedAudioDriver))
                    showAudioSettings = false
                }
            )
        }
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

        // Fullscreen aspect-ratio mode (#71): Off (windowed letterbox) / Fit (letterbox) /
        // Stretch (fill) / Fill (crop) / Integer (pixel-perfect). Option index maps 1:1 to
        // Container.FULLSCREEN_OFF/FIT/STRETCH/FILL/INTEGER.
        val fullscreenModeLabels = listOf(
            stringResource(R.string.fullscreen_mode_off),
            stringResource(R.string.fullscreen_mode_fit),
            stringResource(R.string.fullscreen_mode_stretch),
            stringResource(R.string.fullscreen_mode_fill),
            stringResource(R.string.fullscreen_mode_integer)
        )
        val fsSelIdx = viewModel.fullscreenMode.coerceIn(0, fullscreenModeLabels.size - 1)
        LabeledDropdown(
            label = stringResource(R.string.fullscreen_mode),
            options = fullscreenModeLabels,
            selectedOption = fullscreenModeLabels[fsSelIdx],
            onSelect = { viewModel.fullscreenMode = fullscreenModeLabels.indexOf(it).coerceAtLeast(0) }
        )
        Spacer(Modifier.height(8.dp))

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

        // ReShade multi-effect loadout (vkBasalt drop-in), per-container default. The per-game shortcut
        // editor has the same picker and overrides this. Only applies to DXVK/VKD3D (Vulkan) games.
        val reshadeWrapper = StringUtils.parseIdentifier(viewModel.selectedDXWrapper ?: "")
        val reshadeSupported = reshadeWrapper.contains("dxvk") || reshadeWrapper.contains("vegas")
        ReshadeLoadoutEditor(
            state = viewModel.reshadeLoadout,
            effects = viewModel.reshadeEffects,
            supported = reshadeSupported,
            onCatalogChanged = { viewModel.rescanReshadeEffects() },
        )

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
            // Wallpaper picker (visible when Image selected). The picked image is written to a
            // GLOBAL user-wallpaper.png (shared by all containers) or a per-container
            // user-wallpaper-<id>.png depending on the scope selector. On Save
            // buildDesktopThemeStr() emits the scope + the chosen file's mtime, so switching scope
            // or overwriting the file regenerates this container's Wine wallpaper.
            if (viewModel.desktopBgTypeIndex == WineThemeManager.BackgroundType.IMAGE.ordinal) {
                val context = LocalContext.current
                val ioScope = rememberCoroutineScope()
                val scopeOptions = listOf("All containers", "This container")
                val currentScope = WineThemeManager.BackgroundScope.values()
                    .getOrElse(viewModel.desktopWallpaperScopeIndex) { WineThemeManager.BackgroundScope.GLOBAL }
                // File depends on the selected scope; getNextContainerId() is O(1) so recomputing
                // per recomposition is cheap.
                val wallpaperFile = viewModel.wallpaperFileFor(currentScope)
                // Both the file path AND the mtime can change (scope switch / new pick), so drive
                // preview reload off (scopeIndex, stamp). Stamp is bumped on the main thread after
                // a successful save.
                var wallpaperStamp by remember { mutableStateOf(0L) }
                var preview by remember { mutableStateOf<ImageBitmap?>(null) }

                LaunchedEffect(viewModel.desktopWallpaperScopeIndex, wallpaperStamp) {
                    preview = if (wallpaperFile.isFile) {
                        withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(wallpaperFile.path)?.asImageBitmap()
                        }
                    } else null
                }

                fun applyWallpaperFromUri(uri: Uri) {
                    ioScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            val bitmap = ImageUtils.getBitmapFromUri(context, uri, 1280)
                                ?: return@withContext false
                            wallpaperFile.parentFile?.mkdirs()
                            ImageUtils.save(bitmap, wallpaperFile, Bitmap.CompressFormat.PNG, 100)
                        }
                        if (ok) wallpaperStamp = wallpaperFile.lastModified()
                    }
                }

                // System SAF picker (secondary).
                val pickWallpaperLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri -> uri?.let { applyWallpaperFromUri(it) } }

                // Built-in in-app image picker (primary).
                val pickWallpaperInAppLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        InAppFilePicker.pickedUri(result.data)?.let { applyWallpaperFromUri(it) }
                    }
                }
                var showWallpaperMenu by remember { mutableStateOf(false) }

                Spacer(Modifier.height(8.dp))
                LabeledDropdown(
                    label = "Apply wallpaper to",
                    options = scopeOptions,
                    selectedOption = scopeOptions.getOrElse(viewModel.desktopWallpaperScopeIndex) { scopeOptions[0] },
                    onSelect = { opt -> viewModel.desktopWallpaperScopeIndex = scopeOptions.indexOf(opt).coerceAtLeast(0) }
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wallpaper Image", modifier = Modifier.weight(1f))
                    preview?.let { img ->
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Box {
                        OutlinedButton(onClick = { showWallpaperMenu = true }) {
                            Text(if (preview != null) "Change" else "Browse")
                        }
                        DropdownMenu(expanded = showWallpaperMenu, onDismissRequest = { showWallpaperMenu = false }) {
                            DropdownMenuItem(text = { Text("Browse files") }, onClick = {
                                showWallpaperMenu = false
                                pickWallpaperInAppLauncher.launch(
                                    InAppFilePicker.buildIntent(context, InAppFilePicker.IMAGES, "Select wallpaper")
                                )
                            })
                            DropdownMenuItem(text = { Text("Pick via system…") }, onClick = {
                                showWallpaperMenu = false
                                pickWallpaperLauncher.launch("image/*")
                            })
                        }
                    }
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
private fun SectionLabel(
    text: String,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.08.em,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
internal fun LabeledDropdown(
    label: String,
    options: List<String>,
    selectedOption: String,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    disabledOptions: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    // ── Controller / D-pad support (all defaulted, so every existing touch caller is unaffected) ──
    // [focused] draws the focus border on the anchor when this dropdown is the highlighted control.
    // [expandedOverride] (when non-null) lets a parent CONTROL the open state instead of the internal
    // one — the shortcut editor's root D-pad handler opens/closes exactly one dropdown at a time this
    // way. [onExpandedChange] fires on every open/close request (touch tap, item pick, outside dismiss)
    // so the parent's open-tracker stays in sync. [highlightedIndex] tints the option the D-pad cursor
    // is on. With all four at their defaults the box behaves exactly as before (own state, no highlight).
    focused: Boolean = false,
    expandedOverride: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    highlightedIndex: Int = -1,
) {
    var internalExpanded by remember { mutableStateOf(false) }
    val expanded = expandedOverride ?: internalExpanded
    val setExpanded: (Boolean) -> Unit = { want ->
        if (enabled) {
            onExpandedChange?.invoke(want)
            if (expandedOverride == null) internalExpanded = want
        }
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { setExpanded(it) },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .then(
                    if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall)
                    else Modifier
                )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { setExpanded(false) },
            modifier = Modifier.outlinedMenuCard(),
        ) {
            options.forEachIndexed { idx, opt ->
                if (idx > 0) MenuItemDivider()
                val optEnabled = opt !in disabledOptions
                DropdownMenuItem(
                    text = { Text(opt) },
                    enabled = optEnabled,
                    onClick = { if (optEnabled) { onSelect(opt); setExpanded(false) } },
                    modifier = if (idx == highlightedIndex)
                        Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    else Modifier,
                )
            }
        }
    }
}

@Composable
internal fun StartupServicesToggleList(
    enabled: Set<String>,
    onToggle: (rawName: String, on: Boolean) -> Unit
) {
    SectionBox(title = "Custom Services") {
        Text(
            "Custom starts with every service off — turn on only what you need. " +
                "Disabling Wine Bus/HID can break controllers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        WineUtils.STARTUP_SERVICES.forEachIndexed { i, entry ->
            val raw = WineUtils.startupServiceRawName(entry)
            val label = WineUtils.STARTUP_SERVICE_LABELS.getOrElse(i) { raw }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("$label ($raw)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = enabled.contains(raw), onCheckedChange = { onToggle(raw, it) })
            }
            if (i < WineUtils.STARTUP_SERVICES.lastIndex) Spacer(Modifier.height(4.dp))
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
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.outlinedMenuCard(),
        ) {
            options.forEachIndexed { idx, opt ->
                if (idx > 0) MenuItemDivider()
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
    // Per-field "?" help — a centered, scrollable Compose dialog (HelpDialog). null = no dialog.
    var helpRes by remember { mutableStateOf<Int?>(null) }
    helpRes?.let { HelpDialog(it) { helpRes = null } }

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
    // WRAPPER_BCN_ASTC — integrated-wrapper (Wrapper-gamenative/leegao) ASTC transcode path.
    // Off by default; only honored by the BCn-integrated wrapper ICD, ignored by others.
    var bcnEmulationAstc by remember { mutableStateOf(cfg["bcnEmulationAstc"] == "1") }
    var syncFrame        by remember { mutableStateOf(cfg["syncFrame"] == "1") }
    var disablePresentWait by remember { mutableStateOf(cfg["disablePresentWait"] == "1") }
    var fdDevFeatures    by remember { mutableStateOf(cfg["fdDevFeatures"] == "1") }

    // --- BCn Layer (leegao bcn_layer) settings; only meaningful when driver == wrapper-bcn_layer ---
    val isBcnLayer = graphicsDriver == "wrapper-bcn_layer"
    // The integrated-BCn wrapper (Wrapper-gamenative) is the only wrapper ICD that actually honors
    // WRAPPER_BCN_ASTC (see XServerDisplayActivity BCn env block). The older wrappers
    // (original/leegao/legacy) ignore it, and Wrapper + bcn_layer has its own ASTC control
    // (bcnTranscodeAstc), so the general "BCn -> ASTC transcode" toggle belongs to gamenative only.
    val isGamenative = graphicsDriver == "wrapper-gamenative"
    var bcnSectionExpanded by remember { mutableStateOf(false) }
    // Force decode on all GPUs -> BCN_COMPUTE_AUTO=0. Default ON (the Mali force-decode fix).
    var bcnLayerAuto      by remember { mutableStateOf(cfg["bcnLayerAuto"]?.let { it == "1" } ?: true) }
    var bcnTranscodeEtc2  by remember { mutableStateOf(cfg["bcnTranscodeEtc2"] == "1") }
    var bcnTranscodeAstc  by remember { mutableStateOf(cfg["bcnTranscodeAstc"] == "1") }
    // Storage image path -> BCN_COMPUTE_IMAGE_VIEW=1. Default ON.
    var bcnImageView      by remember { mutableStateOf(cfg["bcnImageView"]?.let { it == "1" } ?: true) }
    var bcnDebugLog       by remember { mutableStateOf(cfg["bcnDebugLog"] == "1") }

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
    // True when the picked custom driver couldn't load on this GPU and the native probe fell
    // back to the system ICD (instead of crashing). Drives the inline note under the dropdown.
    var driverFellBack by remember { mutableStateOf(false) }
    // True when the selected version is an installed custom (Qualcomm proprietary) Adreno
    // driver, whose extensions we intentionally don't probe here — the UI shows an explanatory
    // note instead of a misleading "0/0 extensions".
    var isCustomDriver by remember { mutableStateOf(false) }

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
        // isDriverSupported() is a native JNI call. It used to run on the main thread to keep
        // AdrenoTools hook calls serial (concurrency caused SIGSEGV); running it there blocked
        // the UI and caused ANRs. Run it off-main but serialized via graphicsProbeMutex instead.
        val wrapperVersions = context.resources
            .getStringArray(R.array.wrapper_graphics_driver_version_entries)
            .let { arr ->
                if (showAllDrivers) arr.toList()
                else withContext(Dispatchers.IO) {
                    graphicsProbeMutex.withLock { arr.filter { GPUInformation.isDriverSupported(it, context) } }
                }
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
        if (version.isEmpty()) {
            allExtensions = emptyList()
            driverFellBack = false
            isCustomDriver = false
            return@LaunchedEffect
        }
        // Proprietary Qualcomm (Adreno) blobs must NEVER be probed in-process: on some a6xx
        // devices the in-app instance creation aborts inside the vendor app-profile/log path
        // with a -fstack-protector stack smash (SIGABRT) — uncatchable by the SEGV/BUS guard —
        // or corrupts the linker heap on dlopen (hotice77's Redmi Note 11, Aug 2026). Mesa
        // wrappers (Turnip/freedreno, panfrost, ...) export libvulkan_*.so and ARE safe to
        // probe, so they still list their real extensions. Anything that isn't a libvulkan_*
        // Mesa wrapper (a vulkan.ad*.so Qualcomm blob, or an unreadable meta) is skipped here
        // and shows the "applied in-game" note; the driver still loads at game launch.
        val probeUnsafe = withContext(Dispatchers.IO) {
            graphicsProbeMutex.withLock {
                val mgr = AdrenotoolsManager(context)
                val installed = mgr.enumarateInstalledDrivers()
                if (installed.none { it.equals(version, ignoreCase = true) }) {
                    false // wrapper/bundled entry (not a custom import) -> safe to probe
                } else {
                    !mgr.getLibraryName(version).startsWith("libvulkan", ignoreCase = true)
                }
            }
        }
        if (probeUnsafe) {
            allExtensions = emptyList()
            driverFellBack = false
            isCustomDriver = true
            if (version != cfg["version"]) blacklisted = emptySet()
            return@LaunchedEffect
        }
        // Soft-probe the (Mesa/wrapper) driver. Serialized + off-main via the mutex so it can
        // never wedge the UI thread (ANR) or run concurrently with the isDriverSupported filter.
        val exts = withContext(Dispatchers.IO) {
            graphicsProbeMutex.withLock {
                GPUInformation.enumerateExtensions(version, context)?.toList() ?: emptyList()
            }
        }
        allExtensions = exts
        driverFellBack = GPUInformation.driverLoadedFellBack()
        isCustomDriver = exts.isEmpty()
        if (version != cfg["version"]) blacklisted = emptySet()
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
                // An explicit sub-1.3 pick is honored verbatim by the wrapper — warn here so
                // the resulting DXVK failure ("No adapters found") isn't a mystery later.
                val vkChosenMinor = vulkanVersion.substringAfter('.').toIntOrNull() ?: 3
                if (vkChosenMinor < 3) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Games using DXVK 2.x require Vulkan 1.3 and will fail to start with Vulkan $vulkanVersion",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(stringResource(R.string.graphics_driver_version), driverVersions, version, { version = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_graphics_driver_version }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showAllDrivers, onCheckedChange = { showAllDrivers = it })
                    Text(stringResource(R.string.graphics_driver_show_incompatible))
                }
                Spacer(Modifier.height(8.dp))
                if (isCustomDriver) {
                    Text(
                        text = "Custom Qualcomm (Adreno) driver — its extensions load when a game starts, so none are listed here. That's expected, not an error: the driver is applied in-game, where your HUD will show it's active.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp)
                    )
                } else if (allExtensions.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { showExtPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            val enabled = allExtensions.size - blacklisted.size
                            Text(stringResource(R.string.graphics_driver_available_extensions) + " ($enabled/${allExtensions.size})")
                        }
                        IconButton(onClick = { helpRes = R.string.help_available_extensions }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
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
                // ASTC transcode is offered by the BCn-integrated wrapper (Wrapper-gamenative).
                // The Wrapper + bcn_layer driver has its own ASTC control in its section below;
                // the older wrappers ignore WRAPPER_BCN_ASTC entirely, so only expose it here for
                // the gamenative integrated-BCn wrapper.
                if (isGamenative) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = bcnEmulationAstc, onCheckedChange = { bcnEmulationAstc = it })
                        Text(stringResource(R.string.graphics_driver_bcn_emulation_astc))
                    }
                }
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

                // BCn Layer Settings — only when the Wrapper + bcn_layer driver is selected.
                if (isBcnLayer) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { bcnSectionExpanded = !bcnSectionExpanded }
                    ) {
                        Text(
                            (if (bcnSectionExpanded) "▾  " else "▸  ") + stringResource(R.string.bcn_layer_section),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (bcnSectionExpanded) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.bcn_layer_section_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = bcnLayerAuto, onCheckedChange = { bcnLayerAuto = it })
                            Text(stringResource(R.string.bcn_layer_force_decode))
                        }
                        Text(
                            stringResource(R.string.bcn_layer_force_decode_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = bcnTranscodeEtc2, onCheckedChange = { bcnTranscodeEtc2 = it })
                            Text(stringResource(R.string.bcn_layer_transcode_etc2))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = bcnTranscodeAstc, onCheckedChange = { bcnTranscodeAstc = it })
                            Text(stringResource(R.string.bcn_layer_transcode_astc))
                        }
                        Text(
                            stringResource(R.string.bcn_layer_transcode_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = bcnImageView, onCheckedChange = { bcnImageView = it })
                            Text(stringResource(R.string.bcn_layer_image_view))
                        }
                        Text(
                            stringResource(R.string.bcn_layer_image_view_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = bcnDebugLog, onCheckedChange = { bcnDebugLog = it })
                            Text(stringResource(R.string.bcn_layer_debug_log))
                        }
                        Text(
                            stringResource(R.string.bcn_layer_debug_log_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                    "bcnEmulationAstc=${if (bcnEmulationAstc) "1" else "0"};" +
                    "bcnLayerAuto=${if (bcnLayerAuto) "1" else "0"};" +
                    "bcnTranscodeEtc2=${if (bcnTranscodeEtc2) "1" else "0"};" +
                    "bcnTranscodeAstc=${if (bcnTranscodeAstc) "1" else "0"};" +
                    "bcnImageView=${if (bcnImageView) "1" else "0"};" +
                    "bcnDebugLog=${if (bcnDebugLog) "1" else "0"};" +
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
    containerRootDir: java.io.File? = null,
    refreshKey: Int = 0,
    initialConfig: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDownloadDxvk: () -> Unit = {},
    onDownloadVkd3d: () -> Unit = {},
    onDownloadD7vk: () -> Unit = {},
    relaxDxvkFilter: Boolean = false,
    // Fires after every successful live-file write with the file the game must read.
    // The host persists dxvkConfigFile immediately — waiting for OK left toggles
    // invisible to launched games when the sheet was dismissed without OK (the
    // pointer kept aiming at the old path, e.g. a legacy /sdcard/dxvk.conf).
    onLivePointerChanged: (String) -> Unit = {},
    onOpenConfigDownload: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember(initialConfig) { DXVKConfigDialog.parseConfig(initialConfig) }
    val activity = context.findActivity() ?: return
    var isProcessing by remember { mutableStateOf(false) }

    // Virtual "Browse…" entry in the custom-source dropdown; launches the file picker.
    val BROWSE_CONFIG_MARKER = "Browse for file…"

    val allDxvkVersions = remember { mutableStateOf(listOf<String>()) }
    val vkd3dVersions   = remember { mutableStateOf(listOf<String>()) }
    val configSourceEntries = remember { mutableStateOf(listOf<String>()) }
    // Seeded with the bundled sentinel so the D7VK version dropdown always offers "Bundled (default)"
    // even before the async catalog load lands (or when there are no downloaded d7vk profiles).
    val d7vkVersions    = remember { mutableStateOf(listOf(DXVKConfigDialog.D7VK_BUNDLED)) }

    // ---- VEGAS config source: stock/custom two-source model (Tier-2B) ----
    // Declared before LaunchedEffect: the init block below references these on first load.
    val stockSources = remember { mutableStateOf(listOf<DXVKConfigDialog.StockSource>()) }
    // Installed VEGAS builds (verNames) — drives the inline ⬇ on the stock-config row:
    // versions whose parked .conf is missing can be fetched straight from the releases
    // feed without re-downloading the whole build.
    val installedVegasVersions = remember { mutableStateOf(listOf<String>()) }
    val customEntries = remember { mutableStateOf(listOf<String>()) }
    var useDefaults by remember { mutableStateOf(true) }
    var selectedStock by remember { mutableStateOf<String?>(null) }   // stock verName
    var selectedCustom by remember { mutableStateOf<String?>(null) }  // custom file path
    var stockEdited by remember { mutableStateOf(false) }
    var toggleVersion by remember { mutableStateOf(0) }               // bump after a write -> re-snapshot
    // Capture-once backups: the auto slot is the FILE <name>.bak beside the live file.
    // It is created on the FIRST edit after a fresh selection/restore and then left
    // alone — across edits AND sessions (no in-memory set to reset). Restore consumes
    // it, so the next edit recaptures. Manual "Backup now" copies use .bak-manual-N
    // and are never touched automatically.
    // §6c value editor: the row whose value picker is open + the freeform draft.
    var valuePickerRow by remember { mutableStateOf<VegasKeyKnowledge.EditRow?>(null) }
    var customValueDraft by remember { mutableStateOf("") }
    var pendingDeleteKey by remember { mutableStateOf<String?>(null) }
    // (+) add-key editor: freeform key/value appended to the live file (stock OR custom).
    var showAddKey by remember { mutableStateOf(false) }
    var addKeyDraft by remember { mutableStateOf("") }
    var addValueDraft by remember { mutableStateOf("") }
    // §tier: staged FAQ tier selection (null = auto). Applied through vegas.forceTier.
    var tierChoice by remember { mutableStateOf<Int?>(null) }
    // §tier: device detection, read once per dialog open (sysfs is cheap, still cached).
    val gpuModel = remember { VegasTierPresets.readGpuModel() }
    val detectedTier = remember { VegasTierPresets.classifyModel(gpuModel) }
    var showBackups by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<java.io.File?>(null) }
    // §7 release notes: live fetch (isygold/vegas-releases) cached per version per session,
    // bundled fallback (VegasTierPresets.BUNDLED_NOTES), hidden when neither.
    var notesCache by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var notesLoading by remember { mutableStateOf(false) }
    var notesSource by remember { mutableStateOf<String?>(null) }  // "live" | "bundled" | "none"
    var showNotes by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            val cm = ContentsManager(context)
            cm.syncContents()
            val versions = if (isVegas)
                DXVKConfigDialog.loadVegasVersionList(context, cm)
            else
                DXVKConfigDialog.loadDxvkVersionList(context, cm, isArm64EC)
            val vkd3d = DXVKConfigDialog.loadVkd3dVersionList(context, cm)
            val d7vk = DXVKConfigDialog.loadD7vkVersionList(context, cm)
            val cfgsrc = DXVKConfigDialog.loadVegasConfigSourceList(context)
            val stock = if (isVegas) DXVKConfigDialog.loadVegasStockSources(context, cm) else listOf()
            val installed = if (isVegas)
                cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS)
                    ?.mapNotNull { it.verName }?.distinct().orEmpty()
                else listOf()
            withContext(Dispatchers.Main) {
                allDxvkVersions.value = versions
                vkd3dVersions.value = vkd3d
                d7vkVersions.value = d7vk
                configSourceEntries.value = cfgsrc
                stockSources.value = stock
                installedVegasVersions.value = installed
                                // Option B init: the stored dxvkConfigFile IS the live file. Restore by matching:
                // parked stock file -> stock dropdown; sidecar of a known stock -> that stock
                // baseline (the pointer moved when the first edit happened); anything else
                // (custom path, incl. a legacy vegas/active.conf) -> custom dropdown, edited
                // in place. No active.conf copy is ever created or implied.
                val stored = config.get("dxvkConfigFile")
                val stockMatch = stock.firstOrNull { it.file.absolutePath == stored }
                val sDir = containerRootDir?.let { java.io.File(java.io.File(it, "vegas"), "configs") }
                val sidecarMatch = if (stored.isNotEmpty() && sDir != null && sDir.isDirectory) {
                    stock.firstOrNull { it.tag != null && java.io.File(sDir, it.tag + ".user.conf").absolutePath == stored }
                } else null
                val customBase = cfgsrc.filter { it != "None" }
                customEntries.value = if (stored.isNotEmpty() && stored !in customBase) customBase + stored else customBase
                when {
                    stored.isEmpty() -> {
                        useDefaults = true
                    }
                    stockMatch != null -> {
                        selectedStock = stockMatch.displayLabel()
                        selectedCustom = null
                        useDefaults = false
                    }
                    sidecarMatch != null -> {
                        selectedStock = sidecarMatch.displayLabel()
                        selectedCustom = null
                        useDefaults = false
                    }
                    else -> {
                        selectedCustom = stored
                        selectedStock = null
                        useDefaults = false
                    }
                }
            }
        }
    }

    var selectedVkd3d by remember { mutableStateOf(config.get("vkd3dVersion").ifEmpty { "None" }) }

    // VKD3D-Proton needs DXVK 2.x's DXGI; DXVK 1.x can't back it, so the DX12 test fails to start.
    // Filter the DXVK list to 2.x+ (keeping unparseable names, e.g. VEGAS) when VKD3D is enabled —
    // matches the shortcut-level dialog, which already enforces this. Fixes #113.
    // Exception: the Mali "Wrapper + compat + bcn" driver (relaxDxvkFilter) shows all DXVK versions
    // so testers can try the DXVK 1.10.3 adapter-accept workaround with VKD3D on (#137).
    val filteredDxvk = remember(selectedVkd3d, allDxvkVersions.value, relaxDxvkFilter) {
        if (selectedVkd3d != "None" && !relaxDxvkFilter) {
            allDxvkVersions.value.filter { v ->
                val major = DXVKConfigDialog.tryGetMajor(v)
                major == null || major >= 2
            }
        } else allDxvkVersions.value
    }

    var selectedDxvk by remember(allDxvkVersions.value) {
        val stored = config.get("version")
        mutableStateOf(allDxvkVersions.value.firstOrNull { it == stored } ?: allDxvkVersions.value.firstOrNull() ?: stored)
    }

    // Re-sync installed versions every time this dialog is composed (it only exists while
    // open): deletions made in the contents hub previously left ghosts in the uni-select.
    LaunchedEffect(Unit) {
        if (!isVegas) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val cm = ContentsManager(context)
            cm.syncContents()
            val versions = DXVKConfigDialog.loadVegasVersionList(context, cm)
            val stock = DXVKConfigDialog.loadVegasStockSources(context, cm)
            val installed = cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS)
                ?.mapNotNull { it.verName }?.distinct().orEmpty()
            withContext(Dispatchers.Main) {
                allDxvkVersions.value = versions
                stockSources.value = stock
                installedVegasVersions.value = installed
                if (selectedDxvk !in versions) selectedDxvk = versions.firstOrNull() ?: selectedDxvk
            }
        }
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
    // D7VK version (only meaningful when DDraw Wrapper == D7VK). Empty/unknown -> the bundled asset.
    var selectedD7vk         by remember(d7vkVersions.value) {
        val stored = config.get("d7vkVersion")
        mutableStateOf(d7vkVersions.value.firstOrNull { it == stored } ?: DXVKConfigDialog.D7VK_BUNDLED)
    }
    var asyncEnabled         by remember { mutableStateOf(config.get("async") == "1") }
    var asyncCacheEnabled    by remember { mutableStateOf(config.get("asyncCache") == "1") }

    // VEGAS knowledge layer: bundled asset or null (null -> unclassified fallback).
    val vegasKnowledge = remember {
        val k = DXVKConfigDialog.loadVegasKeyKnowledge(context)
        // Autonomy: re-apply feed-discovered version tail persisted from prior sessions.
        val saved = context.getSharedPreferences("vegas_config_ui", Context.MODE_PRIVATE)
            .getString("released_tail", null)?.split("|")?.filter { it.isNotBlank() }
        if (!saved.isNullOrEmpty()) k.mergeReleasedTail(saved)
        k
    }
    // VEGAS key catalog (classifier ground truth, §6b): null -> classifier off, rows unverified.
    // Heal with live tail — tags seen via GitHub feed that are newer than bundled catalog (heals "catalog behind").
    val vegasCatalog = remember {
        val cat = DXVKConfigDialog.loadVegasKeyCatalog(context)
        if (cat != null) {
            val tail = context.getSharedPreferences("vegas_config_ui", Context.MODE_PRIVATE)
                .getString("catalog_tail", null)?.split("|")?.filter { it.isNotBlank() }
            if (!tail.isNullOrEmpty()) cat.mergeTailTags(tail)
        }
        cat
    }
    val activeStockTag = remember(selectedStock, stockSources.value) {
        stockSources.value.firstOrNull { it.verName == selectedStock || it.displayLabel() == selectedStock }?.tag
    }
    // Coverage rule (STOCK rows only): installed tag missing from catalog (or no tag
    // recorded) -> "catalog behind build". Tail-healed tags count as covered.
    val catalogBehind = remember(vegasCatalog, selectedStock, activeStockTag) {
        vegasCatalog != null && selectedStock != null && (activeStockTag == null || !vegasCatalog.isCoveredOrTail(activeStockTag))
    }
    var showCatalogDialog by remember { mutableStateOf(false) }
    var showKeyDocSheet by remember { mutableStateOf(false) }
    // Fork-Feature filter persists across dialog opens (user request: toggle → OK → reopen
    // must stay filtered). Written through on every flip — cheap pref, no OK gate needed.
    val forkFilterPrefs = remember { context.getSharedPreferences("vegas_config_ui", Context.MODE_PRIVATE) }
    var forkFilter by remember { mutableStateOf(forkFilterPrefs.getBoolean("forkFilter", false)) }
    // §6a.6 schema-aware editor: wrong-family key awaiting the block-with-explanation dialog.
    var pendingSchemaBlock by remember { mutableStateOf<String?>(null) }
    // §6b.1 user-initiated "Check for new builds" (report only — observation, never mutation).
    var liveReport by remember { mutableStateOf<VegasLiveCheck.Report?>(null) }
    var liveChecking by remember { mutableStateOf(false) }

        // ---- Option B: "the selected path IS the live file" ----
    // Stock baseline: the parked stock file is read-only until the FIRST edit; that first
    // write creates the user's own sidecar <container>/vegas/configs/<tag>.user.conf which
    // becomes the live file (and the saved dxvkConfigFile pointer moves with it). Custom
    // selections are the live file from the start, edited in place. No active.conf.
    val stockPathForSelected = remember(selectedStock, stockSources.value) {
        selectedStock?.let { s ->
            stockSources.value.firstOrNull { it.verName == s || it.displayLabel() == s }?.file?.absolutePath
        }
    }
    // Sidecar lives inside the container so it survives WCP updates of the parked file.
    // Fix blue-button-not-staying: tag may be null when .provenance.json is missing (older installs).
    // Fall back to the stock verName (stripped) so the sidecar is always resolvable.
    val sidecarFallback = remember(selectedStock, stockSources.value) {
        stockSources.value.firstOrNull { it.verName == selectedStock || it.displayLabel() == selectedStock }
            ?.verName?.removePrefix("vegas-")?.substringBefore(" ·")
            ?: selectedStock?.removePrefix("vegas-")?.substringBefore(" ·")
    }
    val sidecarBase = remember(activeStockTag, sidecarFallback) {
        (activeStockTag?.takeIf { it.isNotBlank() } ?: sidecarFallback)?.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
    val sidecarPath = remember(containerRootDir, sidecarBase) {
        if (sidecarBase == null) null
        else {
            val dir = if (containerRootDir != null)
                java.io.File(java.io.File(containerRootDir, "vegas"), "configs")
            else
                java.io.File(context.filesDir, "vegas-defaults/configs")
            java.io.File(dir, sidecarBase + ".user.conf").absolutePath
        }
    }
    // Probe both tag-based and verName-based sidecar names so an older tag-sidecar is still found
    val sidecarExists = remember(toggleVersion, sidecarPath, containerRootDir, sidecarFallback, activeStockTag) {
        if (sidecarPath != null && java.io.File(sidecarPath).isFile) true
        else if (containerRootDir != null && sidecarFallback != null) {
            // legacy fallback: check the alternative naming
            val altBase = sidecarFallback.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (altBase != sidecarBase) java.io.File(java.io.File(java.io.File(containerRootDir, "vegas"), "configs"), altBase + ".user.conf").isFile
            else false
        } else false
    }
    // Resolve the actual existing sidecar file (prefer tag-based, fallback to verName-based)
    val resolvedSidecarPath = remember(sidecarPath, containerRootDir, sidecarFallback, toggleVersion) {
        if (sidecarPath != null && java.io.File(sidecarPath).isFile) sidecarPath
        else if (containerRootDir != null && sidecarFallback != null) {
            val alt = java.io.File(java.io.File(java.io.File(containerRootDir, "vegas"), "configs"), sidecarFallback.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".user.conf").absolutePath
            if (java.io.File(alt).isFile) alt else sidecarPath
        } else sidecarPath
    }
    val liveFile = remember(useDefaults, selectedStock, selectedCustom, stockPathForSelected, resolvedSidecarPath, sidecarExists) {
        when {
            useDefaults -> null
            selectedStock != null && sidecarExists && resolvedSidecarPath != null -> java.io.File(resolvedSidecarPath!!)
            selectedStock != null -> stockPathForSelected?.let { java.io.File(it) }
            selectedCustom != null -> java.io.File(selectedCustom!!)
            else -> null
        }
    }
    // "" = USE DEFAULTS (no file).
    val livePath = liveFile?.absolutePath ?: ""
    // Option B backups: .bak-* copies beside the CURRENT live file, newest first.
    val backupsList = remember(liveFile, toggleVersion) {
        val dir = liveFile?.parentFile
        val name = liveFile?.name
        if (dir == null || name == null || !dir.isDirectory) emptyList()
        else dir.listFiles { f -> f.isFile && f.name.startsWith(name + ".bak") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    // Config-file snapshot, read ONCE per source pick or per write (never re-read on the fly):
    // empty text = USE DEFAULTS; missing = live file not found on disk.
    val configSourceText = remember(useDefaults, liveFile, toggleVersion) {
        if (useDefaults || liveFile == null) ""
        else if (liveFile.isFile) runCatching { liveFile.readText() }.getOrDefault("")
        else ""
    }
    val configSourceMissing = remember(liveFile, useDefaults) {
        !useDefaults && liveFile != null && !liveFile.isFile
    }
    val configRows = remember(vegasKnowledge, configSourceText, selectedDxvk) {
        if (vegasKnowledge != null) vegasKnowledge.editRows(configSourceText, selectedDxvk)
        else VegasKeyKnowledge.editRowsUnclassified(configSourceText)
    }

    // §6c value editor ground truth: distinct enabled values per key across ALL installed
    // stock baseline files — the value-picker option pool. Boolean keys (only 0/1 values)
    // stay switch-driven. Nothing when no stock package is installed.
    val stockBaselineKeyValues = remember(stockSources.value, vegasKnowledge, selectedDxvk) {
        val m = mutableMapOf<String, MutableSet<String>>()
        for (src in stockSources.value) {
            val f = src.file
            if (!f.isFile) continue
            val rows = (vegasKnowledge?.editRows(runCatching { f.readText() }.getOrDefault(""), src.verName)
                ?: VegasKeyKnowledge.editRowsUnclassified(runCatching { f.readText() }.getOrDefault("")))
            for (r in rows) if (r.enabled && r.value.isNotEmpty()) m.getOrPut(r.key) { linkedSetOf() }.add(r.value)
        }
        m.mapValues { it.value.toList() }
    }
    // The SELECTED baseline's rows: value-picker reset target + pending-row source.
    val baselineRowsForSelected = remember(activeStockTag, vegasKnowledge, stockSources.value, selectedDxvk) {
        if (selectedStock == null) emptyList()
        else {
            val f = stockSources.value.firstOrNull { it.tag == activeStockTag }?.file
            if (f == null || !f.isFile) emptyList()
            else (vegasKnowledge?.editRows(runCatching { f.readText() }.getOrDefault(""), selectedDxvk)
                ?: VegasKeyKnowledge.editRowsUnclassified(runCatching { f.readText() }.getOrDefault("")))
        }
    }
    // Pending rows (stock editor only): baseline keys ABSENT from the active config —
    // switch OFF, "added on save" when enabled. Custom files are user-owned: never listed.
    val pendingRows = remember(baselineRowsForSelected, configRows) {
        val activeKeys = configRows.map { it.key }.toSet()
        baselineRowsForSelected.filter { it.key !in activeKeys }
    }
    // Keys whose active row differs from the selected baseline (value or comment state) —
    // "edited" mark + per-key reset in the value picker.
    val changedKeys = remember(configRows, baselineRowsForSelected, selectedStock) {
        if (selectedStock == null) emptySet()
        else {
            val base = baselineRowsForSelected.associateBy { it.key }
            configRows.filter { r ->
                val b = base[r.key]
                b != null && (b.value != r.value || b.enabled != r.enabled)
            }.map { it.key }.toSet()
        }
    }

    fun isBooleanKey(key: String): Boolean =
        stockBaselineKeyValues[key]?.isNotEmpty() == true && stockBaselineKeyValues[key]!!.all { it == "0" || it == "1" }

    // §tier: the vegas.forceTier value currently in the active config (3 = high, 2 = mid,
    // 1 = entry, 0 = auto, null = unset). Recomputed after every write (toggleVersion).
    val activeForceTier = remember(configRows, toggleVersion) {
        configRows.firstOrNull { it.key == "vegas.forceTier" }?.value?.toIntOrNull()
    }

    fun schemaName(s: VegasKeyCatalog.Schema?): String = when (s) {
        VegasKeyCatalog.Schema.SAREK -> "Sarek (dxvk.vegas.*)"
        VegasKeyCatalog.Schema.STAR -> "Star Engine (vegas.*)"
        null -> "unknown"
    }

    // Restore a .bak archive as the LIVE file: capture-once semantics — the backup is
    // CONSUMED (written into the live file, then deleted if it is the auto slot), so
    // nothing accumulates and the next edit recaptures fresh. Manual .bak-manual-N
    // copies survive restores: they are deliberate snapshots, not rolling state.
    fun restoreBackup(backup: java.io.File) {
        val target = liveFile ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (!target.isFile) return@withContext false
                val content = runCatching { backup.readText() }.getOrNull() ?: return@withContext false
                if (!runCatching { target.writeText(content) }.isSuccess) return@withContext false
                if (backup.name == target.name + ".bak") backup.delete()
                true
            }
            if (ok) { stockEdited = true; toggleVersion++ }
            else Toast.makeText(activity, "Failed to restore backup", Toast.LENGTH_SHORT).show()
        }
    }

    // Manual safety net for capture-once backups: an explicit user-taken snapshot.
    // Named <name>.bak-manual (-2, -3…) so it never collides with the auto slot,
    // shows up in the same Restore list, and survives restores and edits alike.
    fun manualBackup() {
        val target = liveFile ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (!target.isFile) return@withContext false
                var bak = java.io.File(target.absolutePath + ".bak-manual")
                var n = 2
                while (bak.isFile) { bak = java.io.File(target.absolutePath + ".bak-manual-" + n); n++ }
                runCatching { java.nio.file.Files.copy(target.toPath(), bak.toPath()) }.isSuccess
            }
            if (ok) toggleVersion++
            else Toast.makeText(activity, "Failed to create backup", Toast.LENGTH_SHORT).show()
        }
    }

    // §7 release notes: report-only fetch from the vegas-releases feed (the same source
    // the live-check uses), matched by the SELECTED version's tag, cached per session.
    // Falls back to the bundled per-build notes; hidden entirely when neither exists.
    fun openReleaseNotes() {
        val version = selectedDxvk.removePrefix("vegas-")
        val cached = notesCache
        if (cached != null && cached.first == version) {
            notesSource = "live"
            showNotes = true
            return
        }
        val bundled = VegasTierPresets.BUNDLED_NOTES[version]
        notesSource = if (bundled != null) "bundled" else "none"
        showNotes = true
        if (notesLoading) return
        notesLoading = true
        HttpUtils.download("https://api.github.com/repos/isygold/vegas-releases/releases") { body ->
            scope.launch {
                val parsed = runCatching {
                    val arr = JSONArray(body)
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val tag = o.optString("tag_name", "").removePrefix("vegas-")
                        val b = o.optString("body", "")
                        tag to b
                    }.firstOrNull { it.first == version }?.second
                        ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(8)
                }.getOrNull()
                if (parsed != null && parsed.isNotEmpty()) {
                    notesCache = version to parsed
                    notesSource = "live"
                }
                notesLoading = false
            }
        }
    }

    // §6b.1 user-initiated "Check for new builds" — report + heal catalog tail (so next open isn't "unverified").
    fun runLiveCheck() {
        if (liveChecking) return
        liveChecking = true
        HttpUtils.download("https://api.github.com/repos/isygold/vegas-releases/releases") { body ->
            try {
                scope.launch {
                    val catalogNewest = vegasCatalog?.newestTag()
                    val newestAt = catalogNewest?.let { vegasCatalog?.publishedAtOf(it) }
                    val report = VegasLiveCheck.check(body, activeStockTag, catalogNewest, newestAt)
                    liveReport = report
                    // heal: any newer tags seen live become covered
                    if (report.feedOk && report.newerTags.isNotEmpty()) {
                        val prefs = context.getSharedPreferences("vegas_config_ui", Context.MODE_PRIVATE)
                        val cur = prefs.getString("catalog_tail", "")?.split('|')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
                        var added = false
                        for (t in report.newerTags) if (t.isNotBlank() && cur.add(t)) added = true
                        // also ensure installed tag itself is considered seen (covers hash-renamed installs)
                        if (report.installedTag != null && report.installedTag.isNotBlank() && cur.add(report.installedTag!!)) added = true
                        // cap growth so the persisted tail stays bounded
                        VegasKeyCatalog.capToMax(cur)
                        if (added) {
                            prefs.edit().putString("catalog_tail", cur.joinToString("|")).apply()
                            vegasCatalog?.mergeTailTags(cur.toList())
                        }
                    }
                    liveChecking = false
                }
            } catch (_: IllegalStateException) {
                // coroutine scope already disposed (user navigated away) — ignore
            }
        }
    }


    val pickCustomConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val path = InAppFilePicker.pickedPath(result.data)
            if (path != null) {
                selectedCustom = path
                selectedStock = null
                useDefaults = false
                if (path !in customEntries.value) customEntries.value = customEntries.value + path
            }
        }
    }

    // Comment/uncomment the exact config line. Option B: the selected path IS the live
    // file. Stock baselines stay pristine until the FIRST edit — that write creates the
    // user's own sidecar copy (<container>/vegas/configs/<tag>.user.conf) which becomes
    // the live file. Custom files are written in place from the start. No active.conf,
    // no import, no seed/switch decision rows. §6a.6: wrong-schema keys are BLOCKED
    // with an explanation before anything else (stock rows only — custom is user-owned).
    fun commitConfigWrite(isStockPath: Boolean, transform: (String) -> String?) {
        val target = liveFile ?: return
        // Use the resolved sidecar path (existing file) for final pointer; fall back to intended new sidecar
        val intendedSidecar = sidecarPath
        val effectiveSidecar = resolvedSidecarPath ?: intendedSidecar
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (!target.isFile) return@withContext false
                val text = runCatching { target.readText() }.getOrNull() ?: return@withContext false
                val next = transform(text) ?: return@withContext false
                // Capture-once backup: create the auto slot ONLY when it does not exist
                // yet (first edit after a fresh selection/restore). Never .bak-2, -3…
                // from repeat edits or new sessions — the slot persists until consumed
                // by a restore. Manual "Backup now" copies use .bak-manual-N instead.
                val autoBak = java.io.File(target.absolutePath + ".bak")
                if (!autoBak.isFile) {
                    runCatching { java.nio.file.Files.copy(target.toPath(), autoBak.toPath()) }
                }
                // Ensure the target's parent directory exists (fallback sidecar or custom path).
                if (!target.parentFile.isDirectory) target.parentFile.mkdirs()
                // Stock + pristine baseline: materialize the sidecar with the edited content.
                // From then on the sidecar IS the live file (pointer saved on OK).
                if (isStockPath && !sidecarExists && effectiveSidecar != null) {
                    val s = java.io.File(effectiveSidecar!!)
                    if (!s.parentFile.exists() && !s.parentFile.mkdirs()) return@withContext false
                    if (!s.parentFile.isDirectory) return@withContext false
                    runCatching { s.writeText(next) }.isSuccess
                } else {
                    runCatching { target.writeText(next) }.isSuccess
                }
            }
            if (ok) {
                if (isStockPath) stockEdited = true
                toggleVersion++
                // The game reads dxvkConfigFile at launch — point it at the file we just
                // wrote NOW, not on OK. Dismissing the sheet must never orphan toggles.
                val finalPath = if (isStockPath && !sidecarExists && effectiveSidecar != null) effectiveSidecar!!
                                else target.absolutePath
                onLivePointerChanged(finalPath)
            }
            else Toast.makeText(activity, "Failed to update config file", Toast.LENGTH_SHORT).show()
        }
    }

    // Comment/uncomment the exact config line (Option B direct-write; §6a.6 wrong-schema
    // block). Pending (absent) keys: enabling appends the line with the row's stock
    // default; disabling an absent key is a structural no-op.
    fun applyToggle(key: String, value: String, enable: Boolean) {
        if (useDefaults || liveFile == null) return
        val isStockPath = selectedStock != null
        // §6a.6 schema-aware editor: block BEFORE the write — a wrong-family key can never
        // be meaningfully applied to this build's schema. Stock rows only.
        if (isStockPath && activeStockTag != null && vegasCatalog != null && vegasCatalog.isWrongFamily(key, activeStockTag)) {
            pendingSchemaBlock = key
            return
        }
        commitConfigWrite(isStockPath) { text ->
            VegasKeyKnowledge.toggleLine(text, key, enable)
                ?: if (enable) VegasKeyKnowledge.setLine(text, key, value) else text
        }
    }

    // Value edit (non-boolean keys via the value picker): same pipeline; setLine preserves
    // comment state and appends an enabled line for absent (pending) keys.
    fun applyValue(key: String, value: String) {
        if (useDefaults || liveFile == null) return
        val isStockPath = selectedStock != null
        if (isStockPath && activeStockTag != null && vegasCatalog != null && vegasCatalog.isWrongFamily(key, activeStockTag)) {
            pendingSchemaBlock = key
            return
        }
        commitConfigWrite(isStockPath) { text ->
            VegasKeyKnowledge.setLine(text, key, value)
        }
    }

    // (+) Add a brand-new key=value line to the live file (stock sidecar OR custom —
    // both are plain live files under Option B). setLine appends an enabled line at
    // the end when the key is absent; an existing key gets its value updated in
    // place instead of duplicating. Same guards as applyValue.
    fun applyAddKey(key: String, value: String) {
        if (useDefaults || liveFile == null) return
        val isStockPath = selectedStock != null
        if (isStockPath && activeStockTag != null && vegasCatalog != null && vegasCatalog.isWrongFamily(key, activeStockTag)) {
            pendingSchemaBlock = key
            return
        }
        commitConfigWrite(isStockPath) { text ->
            VegasKeyKnowledge.setLine(text, key, value)
        }
    }

    // Per-key delete: drops the key's line entirely from the live file (stock sidecar
    // OR custom — both plain live files under Option B). Same guards as applyValue so a
    // gated (wrong-schema) key can't be silently dropped without the user seeing why.
    fun applyDeleteKey(key: String) {
        if (useDefaults || liveFile == null) return
        val isStockPath = selectedStock != null
        if (isStockPath && activeStockTag != null && vegasCatalog != null && vegasCatalog.isWrongFamily(key, activeStockTag)) {
            pendingSchemaBlock = key
            return
        }
        commitConfigWrite(isStockPath) { text ->
            VegasKeyKnowledge.removeLine(text, key)
        }
    }

    val pickVegasLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = InAppFilePicker.pickedUri(result.data)
            if (uri != null) {
                isProcessing = true
                installContentFromUri(activity, uri) { success ->
                    if (success) {
                        Toast.makeText(activity, "VEGAS version installed", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val cm = ContentsManager(context)
                                cm.syncContents()
                                val newVersions = if (isVegas)
                                    DXVKConfigDialog.loadVegasVersionList(context, cm)
                                else
                                    DXVKConfigDialog.loadDxvkVersionList(context, cm, isArm64EC)
                                withContext(Dispatchers.Main) {
                                    allDxvkVersions.value = newVersions
                                }
                            }
                        }
                    }
                    isProcessing = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isVegas) "VEGAS ${stringResource(R.string.configuration)}" else "DXVK ${stringResource(R.string.configuration)}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                // §VEGAS version management (VEGAS mode): top section, inline action cluster
                // at the right edge — download gear, delete, install-from-file. Non-VEGAS
                // keeps the original DXVK-first order below.
                // §loglevel: VEGAS ignores the config-file log-level key; it must be an env var.
                Text(
                    stringResource(R.string.vegas_config_loglevel_envvar_note),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontWeight = FontWeight.Bold))
                )
                Spacer(Modifier.height(8.dp))
                if (isVegas) {
                    SectionLabel("VEGAS VERSION")
                    if (filteredDxvk.isEmpty()) {
                        Text(
                            "no VEGAS build installed — download one via the sheet",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LabeledDropdown(
                            "", filteredDxvk, selectedDxvk, { selectedDxvk = it },
                            modifier = Modifier.weight(1f)
                        )
                        ContentInstallGear(onDownloadFile = onDownloadDxvk)
                        IconButton(
                            onClick = {
                                isProcessing = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val cm = ContentsManager(context)
                                            cm.syncContents()
                                            // Match the exact "vegas-<selected>" name OR any hash-suffixed
                                            // variant ("vegas-2.4.1-cf04e7f" vs "-3137660" asset renames) —
                                            // exact-equality silently no-oped for renamed installs.
                                            val expectedName = "vegas-$selectedDxvk"
                                            val profile = cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS)
                                                .firstOrNull {
                                                    it.verName == expectedName ||
                                                        it.verName.removePrefix("vegas-") == selectedDxvk ||
                                                        it.verName == selectedDxvk
                                                }
                                            if (profile != null) {
                                                cm.removeContent(profile)
                                                cm.syncContents()
                                                val newVersions = DXVKConfigDialog.loadVegasVersionList(context, cm)
                                                withContext(Dispatchers.Main) {
                                                    allDxvkVersions.value = newVersions
                                                    if (selectedDxvk !in newVersions) {
                                                        selectedDxvk = newVersions.firstOrNull() ?: selectedDxvk
                                                    }
                                                    Toast.makeText(activity, "VEGAS version deleted", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(activity, "No installed VEGAS version to delete", Toast.LENGTH_SHORT).show()
        }
    }
}
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(activity, "ERROR: Failed to delete — ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) { isProcessing = false }
                                    }
                                }
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(
                            onClick = { pickVegasLauncher.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.WCP, "Select VEGAS package")) },
                            enabled = !isProcessing,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Install from file", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (isProcessing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    // §7 release-notes chip: visible when notes exist for the selected version
                    // (live-cached or bundled); ● live / ◐ bundled marker.
                    val verKey = selectedDxvk.removePrefix("vegas-")
                    if (notesCache?.first == verKey || VegasTierPresets.BUNDLED_NOTES[verKey] != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { openReleaseNotes() }) {
                                Text("What's new in $selectedDxvk", style = MaterialTheme.typography.bodySmall)
                            }
                            val live = notesCache?.first == verKey
                            Text(
                                if (live) "● live" else "◐ bundled",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledDropdown(
                        stringResource(R.string.vkd3d_version), vkd3dVersions.value, selectedVkd3d, { selectedVkd3d = it },
                        modifier = Modifier.weight(1f)
                    )
                    ContentInstallGear(onDownloadFile = onDownloadVkd3d)
                }
                Spacer(Modifier.height(8.dp))
                if (!isVegas) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LabeledDropdown(
                            stringResource(R.string.dxvk_version),
                            filteredDxvk, selectedDxvk, { selectedDxvk = it },
                            modifier = Modifier.weight(1f)
                        )
                        ContentInstallGear(onDownloadFile = onDownloadDxvk)
                    }
                    if (isProcessing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
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
                SectionLabel("API FEATURE LEVEL")
                LabeledDropdown("", featureLevelEntries, selectedFeatureLevel, { selectedFeatureLevel = it })
                Spacer(Modifier.height(8.dp))
                LabeledDropdown("DDraw Wrapper", ddraEntries, selectedDdra, { selectedDdra = it })
                // D7VK is a catalog-backed component: when it's the chosen DDraw wrapper, offer a
                // version dropdown ("Bundled (default)" + any downloaded profiles) and a cloud button
                // to fetch more — mirroring the DXVK/VKD3D version UI above.
                if (StringUtils.parseIdentifier(selectedDdra) == "d7vk") {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LabeledDropdown(
                            "D7VK Version", d7vkVersions.value, selectedD7vk, { selectedD7vk = it },
                            modifier = Modifier.weight(1f)
                        )
                        ContentInstallGear(onDownloadFile = onDownloadD7vk)
                    }
                }
                // §tier: FAQ performance tiers (docs/vegas_faq.html #11) — GPU detection,
                // staged selection → preview → apply as vegas.forceTier through the normal
                // config write pipeline. Rendered above the Config section; writing to a
                // file-based source only (defaults has no file, so Apply is gated).
                if (isVegas) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("PERFORMANCE TIER")
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            // effectiveTier = staged choice if present, else persisted value (0/null → Auto). Fixes
                            // "blue stays on Auto after reopen" — chips now reflect what is actually applied.
                            val staged = tierChoice
                            val effectiveTier: Int? = staged ?: activeForceTier?.let { if (it == 0) null else it }
                            val tierForPreview = staged
                            Text(
                                when {
                                    gpuModel == null -> "GPU model unreadable — tier is manual"
                                    detectedTier != null -> "$gpuModel · auto Tier $detectedTier"
                                    else -> "GPU: $gpuModel — no tier suggestion"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = effectiveTier == null,
                                    onClick = { tierChoice = null },
                                    label = { Text("Auto${if (detectedTier != null) " · T$detectedTier" else ""}") }
                                )
                                VegasTierPresets.TIERS.forEach { t ->
                                    FilterChip(
                                        selected = effectiveTier == t.number,
                                        onClick = { tierChoice = t.number },
                                        label = { Text(t.label) }
                                    )
                                }
                            }
                            val p = tierForPreview?.let { VegasTierPresets.PARAMS[it] }
                            if (tierForPreview != null && p != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Draw threshold ${p.drawThreshold} (D3D9 ${p.drawThresholdD3D9}) · HAAE pacing ${p.haaePacing}ms · governor cap ${p.governorCap} · shader zero-init ${p.shaderZeroInit} · frame-gen ${p.frameGen}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "will write: vegas.forceTier = $tierForPreview",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(modifier = Modifier.padding(top = 4.dp)) {
                                    TextButton(
                                        enabled = !useDefaults,
                                        onClick = { applyValue("vegas.forceTier", tierForPreview.toString()); tierChoice = null }
                                    ) { Text("Apply tier", style = MaterialTheme.typography.bodySmall) }
                                    if (useDefaults) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "pick a config source first — defaults has no file to write to",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            } else {
                                val applied = activeForceTier
                                if (applied != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (applied == 0) "Applied — auto (vegas.forceTier = 0)"
                                        else "Applied — vegas.forceTier = $applied",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Row(modifier = Modifier.padding(top = 4.dp)) {
                                        TextButton(
                                            enabled = !useDefaults,
                                            onClick = { applyValue("vegas.forceTier", "0"); tierChoice = null }
                                        ) { Text("Reset to auto", style = MaterialTheme.typography.bodySmall) }
                                        if (useDefaults) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "pick a config source first",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Mali hint: non-Adreno device — the experimental Mali "Wrapper + compat +
                    // bcn" driver (with the relaxed DXVK list) lives in the driver settings.
                    if (gpuModel != null && !gpuModel.contains("adreno", ignoreCase = true)) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Experimental — Mali driver", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Pair the Mali 'Wrapper + compat + bcn' driver with the relaxed DXVK list (all DXVK versions) in the container's driver settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (isVegas) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("CONFIG")
                    // Guidance for the three config audiences (new / customizer / file-savvy).
                    Text(
                        "New to configs? Grab a Stock config via ⬇, then pick it below. " +
                        "Want it your way? Edit anything in a stock config — changes auto-save as your own copy, the original stays untouched. " +
                        "Prefer managing files yourself? \"Custom config file\" points at any .conf (the built-in way of doing DXVK_CONFIG_FILE). " +
                        "Heads-up: builds SILENTLY IGNORE keys they don't recognise — if an added key does nothing, it's either not supported by that build or has a typo.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    // ---- config source: two-source model (stock/custom), one ACTIVE ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = useDefaults, onCheckedChange = { useDefaults = it }, modifier = Modifier.height(32.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Use defaults (no config file)", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!useDefaults) {
                        // Option B: the selected path IS the live file — no adoption banner,
                        // no seed/switch decisions. Legacy containers' parked stock files are
                        // simply live files (their content shows and applies).
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            LabeledDropdown(
                                "Stock config (per version)",
                                stockSources.value.map { it.displayLabel() },
                                selectedStock ?: "",
                                { s -> selectedStock = s; selectedCustom = null; useDefaults = false },
                                modifier = Modifier.weight(1f)
                            )
                            // Inline ⬇ hands off to the host-level config-download sheet
                            // (same layering as the build sheets: dialog hides, sheet shows).
                            if (isVegas) {
                                IconButton(onClick = onOpenConfigDownload) {
                                    Icon(
                                        Icons.Filled.Download,
                                        contentDescription = "Download stock configs",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        if (stockSources.value.isEmpty()) {
                            Text(
                                "no installed VEGAS package ships a config — install one via the sheet",
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // Option B stock edit info: pristine baselines stay read-only; the
                        // FIRST edit creates the user's own sidecar which takes over as the
                        // live file (and the saved pointer).
                        if (selectedStock != null && selectedCustom == null && stockPathForSelected != null && !configSourceMissing) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (sidecarExists)
                                    "Your edits live in your own copy — the stock version stays pristine."
                                else
                                    "Read-only until the first edit — changes create your own copy.",
                                color = if (sidecarExists) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LabeledDropdown(
                            "Custom config file",
                            customEntries.value + BROWSE_CONFIG_MARKER,
                            selectedCustom ?: "",
                            { c ->
                                if (c == BROWSE_CONFIG_MARKER) {
                                    pickCustomConfigLauncher.launch(
                                        InAppFilePicker.buildIntent(context, emptyArray(), "Select config file")
                                    )
                                } else {
                                    selectedCustom = c; selectedStock = null; useDefaults = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (livePath.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "📍 $livePath",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (configSourceMissing) {
                            Spacer(Modifier.height(4.dp))
                            Text("not found: $livePath", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            // #5 ghost → one-tap remove from dropdown
                            if (selectedCustom != null) {
                                TextButton(onClick = {
                                    customEntries.value = customEntries.value.filter { it != selectedCustom }
                                    selectedCustom = null
                                    if (selectedStock == null) useDefaults = true
                                }) { Text("Remove from list", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                        // Option B backups: .bak-* beside the CURRENT live file. Always visible
                        // once a live file exists — an empty list reads as "no backups yet" —
                        // so the restore entry point can never be hidden by state (user's #5).
                        if (liveFile != null && !configSourceMissing) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (backupsList.isEmpty())
                                        "No backups yet — the first edit keeps a copy of the current file."
                                    else
                                        "Backups: ${backupsList.size} — restore a previously saved state",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                // Manual safety net for capture-once backups: an explicit
                                // snapshot taken by the user, never overwritten automatically.
                                if (liveFile != null && liveFile.isFile) {
                                    TextButton(onClick = { manualBackup() }) {
                                        Text("Backup now", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (backupsList.isNotEmpty()) {
                                    TextButton(onClick = { showBackups = true }) { Text("Restore…", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        if (!configSourceMissing && configSourceText.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = forkFilter, onCheckedChange = { forkFilter = it; forkFilterPrefs.edit().putBoolean("forkFilter", it).apply() }, modifier = Modifier.height(32.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Fork-feature filter", style = MaterialTheme.typography.bodySmall)
                            }
                            if (forkFilter) {
                                // Combined semantics: fork-only view AND unavailable
                                // (LATE/REMOVED) keys hidden — count what actually shows.
                                val shownCount = configRows.count { row ->
                                    val k = vegasKnowledge
                                    k?.isForkKey(row.key) == true && !k.isGated(row.key, selectedDxvk)
                                }
                                Text(
                                    "Fork features only: $shownCount of ${configRows.size} keys shown",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { showAddKey = true; addKeyDraft = ""; addValueDraft = "" }) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add key", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            // #7 & #10 helpers: pre-filter + typo suggestion (conflict-free, no LazyColumn nesting)
                            val visibleRows = remember(configRows, forkFilter, selectedDxvk, vegasKnowledge) {
                                configRows.filter { r ->
                                    if (forkFilter && vegasKnowledge?.isForkKey(r.key) != true) false
                                    else if (forkFilter && vegasKnowledge != null && vegasKnowledge.isGated(r.key, selectedDxvk)) false
                                    else true
                                }
                            }
                            fun levenshtein(a: String, b: String): Int {
                                val dp = Array(a.length + 1) { IntArray(b.length + 1) }
                                for (i in 0..a.length) dp[i][0] = i
                                for (j in 0..b.length) dp[0][j] = j
                                for (i in 1..a.length) for (j in 1..b.length) {
                                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                                    dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                                }
                                return dp[a.length][b.length]
                            }
                            fun typoFor(key: String): String? {
                                if (vegasCatalog == null) return null
                                val bucket = vegasCatalog.classify(key, activeStockTag)
                                if (bucket != VegasKeyCatalog.Bucket.NOWHERE) return null
                                var best: String? = null
                                var bestDist = 3
                                for (cand in vegasCatalog.allKeys()) {
                                    val d = levenshtein(key, cand)
                                    if (d < bestDist) { bestDist = d; best = cand }
                                }
                                return if (bestDist in 1..2) best else null
                            }
                            // Gated keys are now *shown* as ineffective — no auto-off (per your "stop").
                            // They appear grey `needs 2.8.0+ · ineffective` with ○──, you decide to Add/Remove.
                            // No LaunchedEffect auto-mutation, Custom and Stock both stay as you left them.
                            // Brighten primary 40% toward white so changed-value text is readable on
                            // dark surfaces (AMOLED pure-black gives ~4.6:1 for #0055FF at bodySmall).
                            val changedTextColor = lerp(MaterialTheme.colorScheme.primary, Color.White, 0.40f)
                            visibleRows.forEach { row ->
                                val gated = vegasKnowledge != null && vegasKnowledge.isGated(row.key, selectedDxvk)
                                val baseBadge = vegasKnowledge?.badgeFor(row.key, selectedDxvk) ?: "unclassified"
                                // Bucket vocabulary describes VEGAS stock configs; a custom (user-owned) file is
                                // by definition not a VEGAS build — suffixes would read as warnings
                                // about something the user did deliberately. Stock-only.
                                // #1 fix: shorten chain so badge doesn't overflow on small screens
                                val bucketPart = if (vegasCatalog != null && selectedStock != null)
                                    when (vegasCatalog.classify(row.key, activeStockTag)) {
                                        VegasKeyCatalog.Bucket.IN_BUILD -> ""
                                        VegasKeyCatalog.Bucket.OTHER_BUILD -> if (catalogBehind) "" else " · other"
                                        VegasKeyCatalog.Bucket.UPSTREAM -> " · upstream"
                                        VegasKeyCatalog.Bucket.NOWHERE -> " · new"
                                    }
                                else ""
                                // cap length so 90-key screens don't wrap badly; UNKNOWN already user-friendly
                                val badgeRaw = baseBadge + bucketPart + if (catalogBehind) " · unverified" else ""
                                val badge = if (badgeRaw.length > 48) badgeRaw.take(45) + "…" else badgeRaw
                                val changed = selectedStock != null && row.key in changedKeys
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Switch(
                                        checked = row.enabled,
                                        onCheckedChange = { applyToggle(row.key, row.value, it) },
                                        modifier = Modifier.height(32.dp).width(48.dp)
                                    )
                                    Text(
                                        row.key,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (gated) MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                                                 else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isBooleanKey(row.key)) {
                                        // Boolean keys stay switch-driven; the value is the comment state.
                                        Text(
                                            row.value,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (changed) changedTextColor else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        // Non-boolean keys open the value picker (stock vocabulary + custom).
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(MaterialTheme.shapes.small)
                                                .heightIn(min = 40.dp)
                                                .clickable(enabled = !gated) {
                                                    valuePickerRow = row
                                                    customValueDraft = row.value
                                                }
                                        ) {
                                            Text(
                                                row.value,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (changed) changedTextColor
                                                        else if (gated) MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                            if (!gated) Icon(
                                                Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    if (!gated) IconButton(
                                        onClick = { pendingDeleteKey = row.key },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete key ${row.key}",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                // #10 typo inline suggestion (only for NOWHERE bucket, distance ≤2)
                                typoFor(row.key)?.let { sug ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 48.dp)) {
                                        Text("Typo? Did you mean $sug", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            // Fix: write correct key with same value, then delete typo key
                                            applyValue(sug, row.value)
                                            // delete old typo after a short delay so sidecar write settles
                                            scope.launch { kotlinx.coroutines.delay(300); applyDeleteKey(row.key) }
                                        }) { Text("Fix", style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                                Text(badge, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            // §6c pending rows (stock editor only): baseline keys ABSENT from the
                            // active config — #4 fix: Add button not inverted Switch OFF.
                            if (selectedStock != null && pendingRows.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("New in this baseline — tap Add to insert", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                pendingRows.forEach { row ->
                                    val gated = vegasKnowledge != null && vegasKnowledge.isGated(row.key, selectedDxvk)
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(
                                            enabled = !gated,
                                            onClick = { applyToggle(row.key, row.value, true) },
                                            modifier = Modifier.height(32.dp)
                                        ) { Text("Add", style = MaterialTheme.typography.bodySmall) }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            row.key,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (gated) MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                                                     else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isBooleanKey(row.key)) {
                                            Text(row.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(MaterialTheme.shapes.small)
                                                    .heightIn(min = 40.dp)
                                                    .clickable(enabled = !gated) {
                                                        valuePickerRow = row
                                                        customValueDraft = row.value
                                                    }
                                            ) {
                                                Text(row.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                                if (!gated) Icon(
                                                    Icons.Default.ExpandMore,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text("missing — will be added as ${row.key} = ${row.value}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    // Knowledge footer: provenance + state of the data layer
                    Spacer(Modifier.height(6.dp))
                    val footerText = if (vegasKnowledge != null)
                        "knowledge: fork ${vegasKnowledge.forkBuild()} · ${vegasKnowledge.generated()}"
                    else
                        "knowledge data unavailable — showing keys unclassified"
                    Text(footerText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val catalogFooter = when {
                        vegasCatalog == null -> "catalog unavailable — classifier off, rows marked unverified"
                        selectedCustom != null -> "catalog: newest ${vegasCatalog.newestTag()} · ${vegasCatalog.generatedAt()} — classifier applies to stock configs"
                        activeStockTag == null -> "catalog: newest ${vegasCatalog.newestTag()} · ${vegasCatalog.generatedAt()} — no stock source selected"
                        catalogBehind -> "catalog behind build — key classes unverified (newest known: ${vegasCatalog.newestTag()})"
                        else -> "catalog: covered · newest ${vegasCatalog.newestTag()} · ${vegasCatalog.generatedAt()}"
                    }
                    Text(catalogFooter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showKeyDocSheet = true }) {
                            Icon(Icons.Filled.Book, contentDescription = "Config key reference", tint = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { showCatalogDialog = true }) { Text("Check catalog", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (showCatalogDialog) {
                        AlertDialog(
                            onDismissRequest = { showCatalogDialog = false },
                            title = { Text("VEGAS key catalog") },
                            text = {
                                Column {
                                    Text("generated ${vegasCatalog?.generatedAt() ?: "n/a"} · upstream ${vegasCatalog?.upstreamSource() ?: "n/a"} (${vegasCatalog?.upstreamFetchedAt() ?: "n/a"})")
                                    Spacer(Modifier.height(4.dp))
                                    vegasCatalog?.knownTags()?.forEach { t ->
                                        val st = vegasCatalog.stateOf(t)
                                        Text("$t — ${st?.name?.lowercase()?.replace('_', '-') ?: "?"}")
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("Updated at build time (assistant-side maintenance).\nCheck for new builds")
                                    Spacer(Modifier.height(6.dp))
                                    TextButton(onClick = { runLiveCheck() }, enabled = !liveChecking) {
                                        Text(if (liveChecking) "Checking…" else "Check for new builds", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { showCatalogDialog = false }) { Text("OK") } }
                        )
                    }
                    if (showKeyDocSheet) {
                        VGlossarySheet(
                            onDismiss = { showKeyDocSheet = false },
                            vegasCatalog = vegasCatalog,
                            installedTag = activeStockTag,
                        )
                    }
                    // §6b.1 report dialog: observation only, zero writes. Shown regardless of
                    // the catalog dialog's own visibility so a report survives its dismissal.
                    liveReport?.let { r ->
                        AlertDialog(
                            onDismissRequest = { liveReport = null },
                            title = { Text("VEGAS new-build check") },
                            text = {
                                Column {
                                    if (!r.feedOk) {
                                        Text("Could not reach the release feed (network or API failure).")
                                        Spacer(Modifier.height(4.dp))
                                        Text("The bundled catalog is unchanged; keys for unknown builds stay 'unverified'.")
                                    } else {
                                        Text("Catalog newest: ${r.catalogNewestTag ?: "?"} (${r.catalogNewestAt.ifEmpty { "?" }}).")
                                        Spacer(Modifier.height(4.dp))
                                        if (r.newerCount == 0) {
                                            Text("No newer releases found.")
                                        } else {
                                            Text("${r.newerCount} newer release(s) found" +
                                                    (if (r.newBuildCount > 0) " — $r.newBuildCount stable" else " (all prerelease)") + ":")
                                            r.newerTags.forEach { t -> Text("· $t", style = MaterialTheme.typography.bodySmall) }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            if (r.installedFoundLive) "Your installed build is still listed upstream."
                                            else "Your installed build is not in the current release list."
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text("This check only reports — no writes, no catalog change. Download and classification still use the existing flows; the catalog asset updates at build time.")
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { liveReport = null }) { Text("OK") } }
                        )
                    }
                    // Only meaningful while a config file is actually in play — hidden under
                    // "Use defaults" (no file to be edited).
                    if (stockEdited && !useDefaults) {
                        Text(
                            if (sidecarExists) "Edited · yours now — saved to your own copy"
                            else "Edited · saved to the live config file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // §7 release-notes dialog: live or bundled notes for the selected version. Observation
                    // only — the fetch never writes anything.
                    if (showNotes) {
                        val verKey = selectedDxvk.removePrefix("vegas-")
                        val notes = notesCache?.takeIf { it.first == verKey }?.second
                            ?: VegasTierPresets.BUNDLED_NOTES[verKey]
                        AlertDialog(
                            onDismissRequest = { showNotes = false },
                            title = { Text("What's new — $verKey") },
                            text = {
                                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                    if (notes.isNullOrEmpty()) {
                                        Text(if (notesLoading) "Fetching notes…" else "No release notes for this version.")
                                    } else {
                                        notes.forEach { n -> Text("· $n", style = MaterialTheme.typography.bodySmall) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        if (notesSource == "live") "Fetched from the vegas-releases feed."
                                        else if (notesSource == "bundled") "Bundled with the app (offline)."
                                        else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            confirmButton = { TextButton(onClick = { showNotes = false }) { Text("OK") } }
                        )
                    }
                    // Option B backup picker: newest-first list of .bak archives beside the live
                    // file; tapping one opens the danger-confirm (the restore itself backs
                    // up the current state first).
                    if (showBackups) {
                        AlertDialog(
                            onDismissRequest = { showBackups = false },
                            title = { Text("Restore a backup") },
                            text = {
                                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                    if (backupsList.isEmpty()) {
                                        Text("No backups yet — they appear after the first edit to this config file.")
                                    }
                                    backupsList.forEach { b ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            TextButton(
                                                onClick = { restoreTarget = b; showBackups = false },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    "${b.name} · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(b.lastModified())}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            IconButton(onClick = {
                                                if (b.delete()) toggleVersion++
                                            }) {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = "Delete backup",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { showBackups = false }) { Text("Close") } }
                        )
                    }
                    restoreTarget?.let { backup ->
                        AlertDialog(
                            onDismissRequest = { restoreTarget = null },
                            title = { Text("Restore this backup?") },
                            text = {
                                Text("The live config file will be replaced by '${backup.name}'. The current state is backed up first — nothing is lost.")
                            },
                            confirmButton = {
                                TextButton(onClick = { restoreBackup(backup); restoreTarget = null }) { Text("Restore") }
                            },
                            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text(stringResource(android.R.string.cancel)) } }
                        )
                    }
                    // §6c value picker: tap a non-boolean key's value to pick from the stock vocabulary
                    // (current file value first), the selected baseline's default (reset), or a
                    // custom string. Value writes go through the same pipeline as toggles.
                    valuePickerRow?.let { row ->
                        val baseline = baselineRowsForSelected.firstOrNull { it.key == row.key }
                        AlertDialog(
                            onDismissRequest = { valuePickerRow = null },
                            title = { Text(row.key) },
                            confirmButton = {
                                TextButton(onClick = { if (customValueDraft.isNotBlank()) applyValue(row.key, customValueDraft.trim()); valuePickerRow = null }) { Text("Done") }
                            },
                            text = {
                                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                    Text(
                                        "Values used in stock configs — pick one, reset to stock, or type your own.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    val opts = linkedSetOf<String>()
                                    if (row.value.isNotEmpty()) opts.add(row.value)
                                    stockBaselineKeyValues[row.key].orEmpty().forEach { opts.add(it) }
                                    opts.forEach { v ->
                                        TextButton(
                                            onClick = { applyValue(row.key, v); customValueDraft = v },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(v, style = MaterialTheme.typography.bodySmall) }
                                    }
                                    if (baseline != null && (baseline.value != row.value || baseline.enabled != row.enabled)) {
                                        TextButton(
                                            onClick = { applyValue(row.key, baseline.value); customValueDraft = baseline.value },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Reset to stock (${baseline.value})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = customValueDraft,
                                        onValueChange = { customValueDraft = it },
                                        label = { Text("Custom value") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(modifier = Modifier.padding(top = 6.dp)) {
                                        TextButton(
                                            enabled = customValueDraft.isNotBlank(),
                                            onClick = { applyValue(row.key, customValueDraft.trim()) }
                                        ) { Text("Apply") }
                                    }
                                }
                            },
                            dismissButton = { TextButton(onClick = { valuePickerRow = null }) { Text(stringResource(android.R.string.cancel)) } }
                        )
                    }
                    // glass delete confirmation: translucent card over a light scrim (config dialog shows through)
                    pendingDeleteKey?.let { delKey ->
                        Dialog(onDismissRequest = { pendingDeleteKey = null }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f))
                            ) {
                                Card(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(24.dp)
                                        .fillMaxWidth(0.92f),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(20.dp)) {
                                        Text(
                                            "Remove key?",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "Are you sure you want '$delKey' removed? You can get it back via Backup → Restore or Add key.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(onClick = { pendingDeleteKey = null }) {
                                                Text("Cancel")
                                            }
                                            Button(
                                                onClick = {
                                                    applyDeleteKey(delKey)
                                                    pendingDeleteKey = null
                                                    Toast.makeText(activity, "Removed $delKey — Restore to undo", Toast.LENGTH_SHORT).show()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("Remove")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // §6a.6 schema block: the key belongs to the OTHER line's schema. Nothing
                    // is written — no decision row, no backup, just the explanation.
                    pendingSchemaBlock?.let { key ->
                        val keyFam = vegasCatalog?.familyOf(key)
                        val instFam = activeStockTag?.let { vegasCatalog?.schemaFamilyOf(it) }
                        val prefix = when (keyFam) {
                            VegasKeyCatalog.Schema.SAREK -> "dxvk.vegas."
                            VegasKeyCatalog.Schema.STAR -> if (key == "dxvk.enableStarProfile") key else "vegas."
                            null -> "?"
                        }
                        AlertDialog(
                            onDismissRequest = { pendingSchemaBlock = null },
                            title = { Text("Key not applicable to this build's schema") },
                            text = {
                                Text(
                                    "$key belongs to the ${schemaName(keyFam)} schema ($prefix…).\n\n" +
                                    "This build (${activeStockTag ?: "unknown"}) uses the ${schemaName(instFam)} schema — " +
                                    "the option cannot be applied and would be ignored."
                                )
                            },
                            confirmButton = { TextButton(onClick = { pendingSchemaBlock = null }) { Text("Got it") } }
                        )
                    }
                    // (+) add-key dialog: freeform key/value appended to the live file.
                    // Works for stock (writes the sidecar) and custom alike; an existing
                    // key updates in place instead of duplicating (setLine semantics).
                    if (showAddKey) {
                        val keyValid = VegasKeyKnowledge.isValidConfigKey(addKeyDraft.trim())
                        AlertDialog(
                            onDismissRequest = { showAddKey = false },
                            title = { Text("Add config entry") },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = addKeyDraft,
                                        onValueChange = { addKeyDraft = it },
                                        label = { Text("Key (e.g. dxvk.maxFrameLatency)") },
                                        singleLine = true,
                                        isError = addKeyDraft.isNotBlank() && !keyValid,
                                        supportingText = {
                                            if (addKeyDraft.isNotBlank() && !keyValid) {
                                                Text("Not a valid config key — use a dotted name or ENV_STYLE caps")
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = addValueDraft,
                                        onValueChange = { addValueDraft = it },
                                        label = { Text("Value") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = addKeyDraft.isNotBlank() && addValueDraft.isNotBlank() && keyValid,
                                    onClick = {
                                        applyAddKey(addKeyDraft.trim(), addValueDraft.trim())
                                        showAddKey = false
                                    }
                                ) { Text("Add") }
                            },
                            dismissButton = { TextButton(onClick = { showAddKey = false }) { Text(stringResource(android.R.string.cancel)) } }
                        )
                    }
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
                cfg.put("d7vkVersion", selectedD7vk)
                cfg.put("dxvkConfigFile", livePath)
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
                    val message = when (reason) {
                        ContentsManager.InstallFailedReason.ERROR_NOSPACE -> "Not enough storage space"
                        ContentsManager.InstallFailedReason.ERROR_BADTAR -> "Corrupted archive file"
                        ContentsManager.InstallFailedReason.ERROR_NOPROFILE -> "No valid profile found in package"
                        ContentsManager.InstallFailedReason.ERROR_BADPROFILE -> "Invalid profile in package"
                        ContentsManager.InstallFailedReason.ERROR_MISSINGFILES -> "Missing required files in package"
                        ContentsManager.InstallFailedReason.ERROR_EXIST -> "This version is already installed"
                        ContentsManager.InstallFailedReason.ERROR_UNTRUSTPROFILE -> "Untrusted profile, installation blocked"
                        ContentsManager.InstallFailedReason.ERROR_UNKNOWN -> "Unknown installation error"
                    }
                    activity.runOnUiThread {
                        Toast.makeText(activity, "ERROR: $message", Toast.LENGTH_LONG).show()
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
                            Toast.makeText(activity, "ERROR: Installation error — ${e.message}", Toast.LENGTH_LONG).show()
                            onResult(false)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            activity.runOnUiThread {
                Toast.makeText(activity, "ERROR: Installation error — ${e.message}", Toast.LENGTH_LONG).show()
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



// ─────────────────────────────────────────────────────────────────────────────
// Stock-config download sheet: every vegas-releases release that ships (or
// could ship) a .conf asset, listed like the build sheet — tag, date, asset
// name, and whether the matching build is installed. Tap a row to download +
// park + record provenance; the stock dropdown picks it up on next open.
@Composable
private fun StockConfigDownloadSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf(listOf<VegasStockConfigFetcher.ReleaseConf>()) }
    var parkedTag by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    LaunchedEffect(retryKey) {
        loading = true
        rows = withContext(Dispatchers.IO) { VegasStockConfigFetcher.listReleaseConfigs() }
        loading = false
        // Autonomy: persist any newly seen release versions so the classifier's
        // known list grows by itself — no bundled-asset regeneration ever needed.
        val prefs = context.getSharedPreferences("vegas_config_ui", Context.MODE_PRIVATE)
        val existing = prefs.getString("released_tail", "")?.split('|')
            ?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
        var added = false
        for (rel in rows) {
            val prefix = rel.tag.removePrefix("v").substringBefore('-')
            for (c in rel.verNames + prefix) {
                if (c.isNotBlank() && existing.add(c)) added = true
            }
        }
        if (added) prefs.edit().putString("released_tail", existing.joinToString("|")).apply()
        // Heal catalog tail too — tags seen live become "covered" so "unverified" doesn't stick forever
        val catExisting = prefs.getString("catalog_tail", "")?.split('|')
            ?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
        var catAdded = false
        for (rel in rows) if (rel.tag.isNotBlank() && catExisting.add(rel.tag)) catAdded = true
        VegasKeyCatalog.capToMax(catExisting)
        if (catAdded) prefs.edit().putString("catalog_tail", catExisting.joinToString("|")).apply()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Stock configs", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap a version to fetch its config. Builds without a shipped config show none.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        if (loading) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else if (rows.isEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "Couldn't reach the releases feed — check connection and retry. (GitHub limit 60/h)",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { retryKey++ }) { Text("Retry") }
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(rows.size) { idx ->
                    val rel = rows[idx]
                    val hasConf = rel.confUrl != null
                    val busy = parkedTag == rel.tag
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = hasConf && !busy) {
                                parkedTag = rel.tag
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) { VegasStockConfigFetcher.park(context, rel) }
                                    parkedTag = null
                                    when (res) {
                                        is VegasStockConfigFetcher.ParkResult.Ok -> {
                                            activity?.let {
                                                Toast.makeText(it,
                                                    "Parked as ${res.parkedAs}.conf — now select \"${res.parkedAs}\" under Stock config",
                                                    Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        is VegasStockConfigFetcher.ParkResult.Fail ->
                                            activity?.let { Toast.makeText(it, "Failed: ${res.reason}", Toast.LENGTH_LONG).show() }
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            if (hasConf) Icons.Filled.Download else Icons.Filled.Block,
                            contentDescription = null,
                            tint = if (hasConf) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(rel.tag.ifEmpty { "(untagged)" }, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append(rel.confName ?: "no config asset")
                                    if (rel.date.isNotEmpty()) append("  ·  ${rel.date}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

// ===== V-Glossary (VEGAS config-key dictionary) =====
// Curated one-line definitions. Keys absent here render with "—".
// VEGAS-specific entries are best-effort; verify against internal docs.
private val KEY_DEFINITIONS: Map<String, String> = mapOf(
    // --- environment / DXVK core ---
    "DXVK_CONFIG_FILE" to "Native DXVK config-file path. Set automatically when a custom .conf is selected.",
    "DXVK_FILTER_DEVICE_NAME" to "Substring filter to force or avoid a GPU by name.",
    "DXVK_LOG_LEVEL" to "Sets DXVK log verbosity (none/info/warn/error).",
    "GPU" to "Legacy environment hint; verify before use.",
    // --- dxvk.* core ---
    "dxvk.enableAsync" to "Enables async shader compilation (legacy; superseded by GPL).",
    "dxvk.gplAsyncCache" to "Caches pipeline state for fast GPU link (GPL async).",
    "dxvk.enableStarProfile" to "Enables the Star Engine tuning profile.",
    "dxvk.hud" to "Configures the DXVK on-screen HUD (e.g. 'devinfo,fps').",
    "dxvk.numCompilerThreads" to "Number of threads used for shader compilation.",
    "dxvk.tearFree" to "Enables tear-free presentation.",
    "dxvk.latencySleep" to "Inserts a sleep to reduce input latency (experimental).",
    "dxvk.maxFrameLatency" to "Caps the number of frames queued for presentation.",
    "dxvk.enableDebugUtils" to "Enables Vulkan debug-utils instrumentation.",
    "dxvk.numAsyncThreads" to "Legacy async compile thread count.",
    "dxvk.shrinkNvidiaHvvHeap" to "Reduces NV host-visible heap usage.",
    "dxvk.useRawSsbo" to "Uses raw SSBO bindings (compat workaround).",
    // --- dxvk.vegas.* (Sarek) / vegas.* (Star) — best-effort ---
    "dxvk.vegas.enable" to "Master switch for VEGAS / DXVK-VEGAS extensions.", // ⚠ best-effort
    "dxvk.vegas.gpuMask" to "Restricts rendering to a subset of GPUs (mask).", // ⚠ best-effort
    "dxvk.vegas.tbdr" to "Enables TBDR (tile-based) optimizations for Adreno.", // ⚠ best-effort
    "dxvk.vegas.threshold" to "Sets internal VEGAS quality / skip thresholds.", // ⚠ best-effort
    "dxvk.vegas.vramSwap" to "Controls VEGAS VRAM swap-to-storage behavior.", // ⚠ best-effort
    "vegas.enableUpscaler" to "Enables the VEGAS upscaler.",
    "vegas.forceTier" to "Selects a fixed VEGAS performance tier.", // ⚠ best-effort
    "vegas.profileDraws" to "Enables VEGAS draw-call profiling.", // ⚠ best-effort
    "vegas.telemetry" to "Enables VEGAS telemetry collection.", // ⚠ best-effort
    "VEGAS_GAME_CONFIG" to "Selects a per-game VEGAS config profile.", // ⚠ best-effort
    "VEGAS_THRESHOLDS" to "VEGAS internal tuning thresholds.", // ⚠ best-effort
    // --- d3d11.* ---
    "d3d11.samplerAnisotropy" to "Caps the max anisotropy for samplers.",
    "d3d11.maxFeatureLevel" to "Caps the reported D3D feature level.",
    "d3d11.maxTessFactor" to "Caps the tessellation factor.",
    "d3d11.ignoreGraphicsBarriers" to "Relaxes barrier insertion (compat).",
    "d3d11.invariantPosition" to "Forces invariant position math (strict).",
    "d3d11.enableDepthPrePass" to "Enables a depth pre-pass.",
    "d3d11.disableMsaa" to "Disables MSAA.",
    "d3d11.maxImplicitDiscardSize" to "Threshold for implicit resource discards.",
    "d3d11.relaxedBarriers" to "Relaxes resource barriers (compat).",
    "d3d11.cachedDynamicResources" to "Caches dynamic resource uploads.",
    "d3d11.constantBufferRangeCheck" to "Validates CB range access.",
    "d3d11.dcSingleUseMode" to "Uses single-use deferred contexts.",
    "d3d11.maxDynamicImageBufferSize" to "Caps dynamic image buffer size.",
    "d3d11.zeroWorkgroupMemory" to "Zeroes workgroup memory (compat).",
    // --- d3d9.* ---
    "d3d9.samplerAnisotropy" to "Caps anisotropy (D3D9).",
    "d3d9.maxFrameRate" to "Caps presentation rate (D3D9).",
    "d3d9.maxFrameLatency" to "Caps queued frames (D3D9).",
    "d3d9.disableMsaa" to "Disables MSAA (D3D9).",
    "d3d9.tearFree" to "Tear-free presentation (D3D9).",
    "d3d9.invariantPosition" to "Invariant position (D3D9).",
    "d3d9.forceAspectRatio" to "Forces a fixed aspect ratio.",
    "d3d9.shaderModel" to "Caps the D3D9 shader model.",
    "d3d9.enableDepthPrePass" to "Depth pre-pass (D3D9).",
    "d3d9.enableDialogMode" to "Dialog-mode tweaks.",
    "d3d9.evictManagedOnUnlock" to "Evicts managed textures on unlock.",
    "d3d9.deferSurfaceCreation" to "Defers swapchain surface creation.",
    "d3d9.customDeviceId" to "Spoofs the GPU device id.",
    "d3d9.customVendorId" to "Spoof the GPU vendor id.",
    "d3d9.customDeviceDesc" to "Spoofs the GPU description string.",
    "d3d9.supportD32" to "Enables D3D9 D32 depth format support.",
    "d3d9.supportDFFormats" to "Enables D3D9 float formats support.",
    "d3d9.supportVCache" to "Enables D3D9 vertex-cache support.",
    "d3d9.supportX4R4G4B4" to "Enables D3D9 X4R4G4B4 format support.",
    "d3d9.seamlessCubes" to "Fixes cube-map seam sampling.",
    "d3d9.strictConstantCopies" to "Stricter constant-buffer copies (compat).",
    "d3d9.strictPow" to "Stricter pow() math (compat).",
    "d3d9.floatEmulation" to "Controls float emulation mode.",
    "d3d9.lenientClear" to "Lenient clear behavior.",
    "d3d9.longMad" to "Uses long MAD (compat).",
    "d3d9.memoryTrackTest" to "Memory-tracking test hook.",
    "d3d9.noExplicitFrontBuffer" to "Avoids an explicit front buffer.",
    "d3d9.numBackBuffers" to "Sets back-buffer count.",
    "d3d9.presentInterval" to "Sets presentation interval.",
    "d3d9.allowDiscard" to "Allows discard usage flags.",
    "d3d9.allowDoNotWait" to "Allows do-not-wait usage flags.",
    "d3d9.alphaTestWiggleRoom" to "Alpha-test tolerance (compat).",
    "d3d9.cachedDynamicBuffers" to "Caches dynamic vertex/index buffers.",
    "d3d9.forceSwapchainMSAA" to "Forces MSAA on the swapchain.",
    "d3d9.maxAvailableMemory" to "Caps reported available memory.",
    "d3d9.enumerateByDisplays" to "Enumerates adapters by display.",
    "d3d9.dpiAware" to "Marks the app DPI-aware.",
    // --- d3d8.* ---
    "d3d8.batching" to "D3D8-era batching tweak (compat).", // ⚠ best-effort
    "d3d8.drefScaling" to "D3D8 depth-reference scaling (compat).", // ⚠ best-effort
    "d3d8.forceLegacyDiscard" to "Forces legacy discard behavior.", // ⚠ best-effort
    "d3d8.forceVsDecl" to "Forces a specific vertex declaration.", // ⚠ best-effort
    "d3d8.placeP8InScratch" to "Places P8 textures in scratch (compat).", // ⚠ best-effort
    "d3d8.shadowPerspectiveDivide" to "Shadow perspective-divide fix (compat).", // ⚠ best-effort
    // --- dxgi.* ---
    "dxgi.maxFrameRate" to "Caps presentation frame rate.",
    "dxgi.maxFrameLatency" to "Caps queued frames.",
    "dxgi.maxDeviceMemory" to "Caps reported GPU memory.",
    "dxgi.maxSharedMemory" to "Caps reported shared memory.",
    "dxgi.syncInterval" to "Sets vsync interval (0 = disabled).",
    "dxgi.tearFree" to "Tear-free presentation.",
    "dxgi.numBackBuffers" to "Sets back-buffer count.",
    "dxgi.customDeviceId" to "Spoofs the GPU device id.",
    "dxgi.customVendorId" to "Spoof the GPU vendor id.",
    "dxgi.customDeviceDesc" to "Spoofs the GPU description string.",
    "dxgi.deferSurfaceCreation" to "Defers surface creation.",
    "dxgi.emulateUMA" to "Emulates a UMA memory model.",
    "dxgi.enableDummyCompositionSwapchain" to "Creates a dummy composition swapchain.",
    "dxgi.hideAmdGpu" to "Hides the AMD GPU from the app.",
    "dxgi.hideIntelGpu" to "Hides the Intel GPU from the app.",
    "dxgi.hideNvidiaGpu" to "Hides the Nvidia GPU from the app.",
    "dxgi.enableHDR" to "Enables HDR output when supported by the display.",
    "dxgi.enableUe4Workarounds" to "Enables workarounds for Unreal Engine 4 rendering quirks.",
    "dxgi.forceRefreshRate" to "Forces a specific display refresh rate (Hz). 0 = auto.",
    "dxgi.hideNvkGpu" to "Hides the NVK (open-source NVIDIA) GPU from the app.",
    // --- dxvk.* extended ---
    "dxvk.allowFse" to "Allows fullscreen exclusive mode transitions.",
    "dxvk.deviceFilter" to "Substring filter to restrict Vulkan device selection by name.",
    "dxvk.disableNvLowLatency2" to "Disables the VK_NV_low_latency2 extension.",
    "dxvk.enableDescriptorBuffer" to "Enables VK_EXT_descriptor_buffer for faster descriptor access.",
    "dxvk.enableDescriptorHeap" to "Enables pooled descriptor heap allocation.",
    "dxvk.enableGraphicsPipelineLibrary" to "Enables VK_EXT_graphics_pipeline_library for faster pipeline creation.",
    "dxvk.enableImplicitResolves" to "Enables implicit MSAA/resolve transitions.",
    "dxvk.enableMemoryDefrag" to "Enables automatic Vulkan memory defragmentation.",
    "dxvk.enableNvRawAccessChains" to "Enables VK_NV_raw_access_chains for raw buffer access.",
    "dxvk.enableUnifiedImageLayouts" to "Uses unified image layout tracking.",
    "dxvk.hideIntegratedGraphics" to "Hides integrated GPUs from the app (discrete only).",
    "dxvk.latencyTolerance" to "Latency tolerance in microseconds for sleep decisions.",
    "dxvk.lowerSinCos" to "Uses lower-precision sin/cos for performance.",
    "dxvk.maxFrameRate" to "Caps presentation frame rate at the DXVK layer.",
    "dxvk.maxMemoryBudget" to "Overrides the Vulkan memory budget in MB.",
    "dxvk.tilerMode" to "Controls TBDR tiler behavior (Adreno-specific).",
    "dxvk.trackPipelineLifetime" to "Tracks pipeline object lifecycle for cache management.",
    "dxvk.zeroMappedMemory" to "Zeroes memory on allocation (debug / leak detection).",
    // --- d3d11.* extended ---
    "d3d11.clampNegativeLodBias" to "Clamps negative LOD bias to 0.",
    "d3d11.disableDirectImageMapping" to "Disables direct image-to-host mapping.",
    "d3d11.enableContextLock" to "Serializes D3D11 device context access (compat).",
    "d3d11.exposeDriverCommandLists" to "Exposes driver-level command list support.",
    "d3d11.forceComputeLdsBarriers" to "Forces barriers between compute LDS accesses.",
    "d3d11.forceComputeUavBarriers" to "Forces barriers between compute UAV accesses.",
    "d3d11.forceSampleRateShading" to "Forces sample-rate shading for all materials.",
    "d3d11.relaxedGraphicsBarriers" to "Relaxes barriers on graphics pipeline resources.",
    "d3d11.reproducibleCommandStream" to "Produces deterministic command streams (debug).",
    "d3d11.samplerLodBias" to "Global LOD bias applied to all samplers.",
    // --- d3d8.* extended ---
    "d3d8.scaleDref" to "Scales depth-reference values for D3D8 compat.",
    // --- d3d9.* extended ---
    "d3d9.cachedWriteOnlyBuffers" to "Caches write-only buffer data in system memory.",
    "d3d9.clampNegativeLodBias" to "Clamps negative LOD bias to 0 (D3D9).",
    "d3d9.countLosableResources" to "Counts resources that can be evicted (memory tracking).",
    "d3d9.deviceLocalConstantBuffers" to "Places constant buffers in device-local memory.",
    "d3d9.deviceLossOnFocusLoss" to "Reports device loss when the app loses focus.",
    "d3d9.disableA8RT" to "Disables A8 render-target format support.",
    "d3d9.extraFrontbuffer" to "Allocates an extra front buffer for compat.",
    "d3d9.forceRefreshRate" to "Forces a specific display refresh rate for D3D9 (Hz).",
    "d3d9.forceSampleRateShading" to "Forces sample-rate shading for D3D9 materials.",
    "d3d9.forceSamplerTypeSpecConstants" to "Uses spec constants to control sampler types.",
    "d3d9.hideAmdGpu" to "Hides the AMD GPU from D3D9 enumeration.",
    "d3d9.hideIntelGpu" to "Hides the Intel GPU from D3D9 enumeration.",
    "d3d9.hideNvidiaGpu" to "Hides the NVIDIA GPU from D3D9 enumeration.",
    "d3d9.hideNvkGpu" to "Hides the NVK GPU from D3D9 enumeration.",
    "d3d9.ignoreDefaultBufferLockRange" to "Ignores the default lock range on buffers.",
    "d3d9.modeCountCompatibility" to "Reports a compatible display mode count.",
    "d3d9.reproducibleCommandStream" to "Produces deterministic D3D9 command streams (debug).",
    "d3d9.samplerLodBias" to "Global LOD bias applied to D3D9 samplers.",
    "d3d9.supportCubeDepthFormats" to "Enables depth format support on cube textures.",
    "d3d9.textureMemory" to "Caps total D3D9 texture memory pool in MB.",
    "d3d9.useD32forD24" to "Uses D32 format where D24 would be used.",
    "d3d9.useFP16" to "Uses half-precision float where full precision is not required.",
    // --- HUD labels (VEGAS governor internal — not user-configurable) ---
    "cap" to "HUD: Maximum draw-batch size before forced flush (governor internal).",
    "draw" to "HUD: Draw calls per millisecond — game rendering workload.",
    "flush" to "HUD: Number of GPU flushes per frame (auto-tuned by governor).",
    "key" to "HUD/Sarek: Internal key identifier (not user-configurable).",
    "thr" to "HUD: Draw-batch threshold — triggers flush when reached.",
    // --- vegas.* extended ---
    "vegas.enableHud" to "Enables the VEGAS governor HUD overlay.",
)

@Composable
private fun VGlossarySheet(
    onDismiss: () -> Unit,
    vegasCatalog: VegasKeyCatalog?,
    installedTag: String?,
) {
    val bucketOrder = listOf(
        VegasKeyCatalog.Bucket.IN_BUILD,
        VegasKeyCatalog.Bucket.UPSTREAM,
        VegasKeyCatalog.Bucket.OTHER_BUILD,
        VegasKeyCatalog.Bucket.NOWHERE,
    )
    val bucketLabel: (VegasKeyCatalog.Bucket) -> String = { b ->
        when (b) {
            VegasKeyCatalog.Bucket.IN_BUILD -> "Documented by this build"
            VegasKeyCatalog.Bucket.UPSTREAM -> "Upstream only"
            VegasKeyCatalog.Bucket.OTHER_BUILD -> "Other build"
            VegasKeyCatalog.Bucket.NOWHERE -> "Not in catalog"
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f))
        ) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.86f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("V-Glossary", style = MaterialTheme.typography.titleLarge)
                Text(
                    buildString {
                        append(if (installedTag != null) "Build: $installedTag" else "No build selected")
                        append("  ·  catalog ${vegasCatalog?.generatedAt() ?: "n/a"}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (vegasCatalog == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Catalog offline — provenance hidden, definitions still shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(10.dp))
                val keys = if (vegasCatalog != null) vegasCatalog.allKeys() else KEY_DEFINITIONS.keys.toList()
                val grouped = keys.groupBy { key ->
                    if (vegasCatalog != null) vegasCatalog.classify(key, installedTag) else VegasKeyCatalog.Bucket.NOWHERE
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    bucketOrder.filter { grouped.containsKey(it) }.forEach { bucket ->
                        item {
                            Text(
                                bucketLabel(bucket),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(grouped.getValue(bucket).sorted()) { key ->
                            Column(modifier = Modifier.padding(vertical = 5.dp)) {
                                Text(
                                    key,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val fam = vegasCatalog?.familyOf(key)?.name ?: "—"
                                    Text("[$fam]", style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        KEY_DEFINITIONS[key] ?: "—",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            }
        }
        }
    }
}
