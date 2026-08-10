@file:OptIn(ExperimentalMaterial3Api::class)
package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.content.res.Configuration
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
import com.winlator.star.contents.WrapperManager
import com.winlator.star.contents.WrapperSettingsDictionary
import com.winlator.star.core.AppUtils
import com.winlator.star.core.DefaultVersion
import com.winlator.star.core.FileUtils
import com.winlator.star.core.GPUInformation
import com.winlator.star.core.ImageUtils
import com.winlator.star.util.InAppFilePicker
import java.io.File
import com.winlator.star.core.StringUtils
import com.winlator.star.ui.components.AudioSettingsDialog
import com.winlator.star.ui.components.audioConfigFromEnv
import com.winlator.star.ui.components.audioConfigToEnv
import com.winlator.star.core.WineThemeManager
import com.winlator.star.core.WineUtils
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.lazy.items
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.Executors
import com.winlator.star.container.Container
import com.winlator.star.widget.perfhud.parseHudOutline
import com.winlator.star.widget.exportHudDiagnostics
import com.winlator.star.widget.ColorPickerView
import com.winlator.star.widget.CPUListView
import com.winlator.star.ui.components.CollapsibleRail
import com.winlator.star.ui.components.ContainerGlossarySheet
import com.winlator.star.ui.components.EnvVarsEditor
import com.winlator.star.ui.components.PlayerSlotsEditor
import com.winlator.star.ui.components.RailItem
import com.winlator.star.ui.components.RailLink
import com.winlator.star.ui.components.RailSection
import com.winlator.star.ui.components.rememberRailState

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
    // null = hidden; "" = glossary open unfiltered (the button); "term" = open at a field's term.
    var glossaryQuery            by remember { mutableStateOf<String?>(null) }
    var showVegasDownloadSheet   by remember { mutableStateOf(false) }
    var showVkd3dDownloadSheet   by remember { mutableStateOf(false) }
    var showD7vkDownloadSheet    by remember { mutableStateOf(false) }
    var showVulkanConfig          by remember { mutableStateOf(false) }
    // Bumped after a DXVK/VKD3D/Vegas download so the open DxvkConfigDialog re-reads its version lists.
    var dxvkRefreshKey           by remember { mutableStateOf(0) }

    // AndroidView references for custom views
    val cpuListViewRef      = remember { mutableStateOf<CPUListView?>(null)      }
    val cpuListWoW64Ref     = remember { mutableStateOf<CPUListView?>(null)      }
    val colorPickerViewRef  = remember { mutableStateOf<ColorPickerView?>(null)  }

    // DRIVES is per-container (letters map to real paths), so it's dropped in "New Container Defaults"
    // mode. Content is dispatched by TITLE below (not raw index) so removing a tab never misaligns.
    val tabTitles = if (viewModel.defaultsMode)
        listOf("GENERAL", "ENVIROMENT", "WIN COMPONENTS", "ADVANCED")
    else
        listOf("GENERAL", "ENVIROMENT", "DRIVES", "WIN COMPONENTS", "ADVANCED")

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val duplicates = viewModel.duplicateDriveLetters
                    if (duplicates.isNotEmpty()) {
                        // Saving would write two drives onto one letter; send the user to the tab.
                        viewModel.selectedTab = tabTitles.indexOf("DRIVES")
                        Toast.makeText(
                            context,
                            "Two drives share " + duplicates.sorted().joinToString(", ") { "$it:" } +
                                " — give each drive its own letter",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else if (!viewModel.isSaving) viewModel.confirm(
                        resolvedGraphicsDriverConfig = viewModel.graphicsDriverConfig,
                        resolvedDXWrapperConfig      = viewModel.dxWrapperConfig,
                        resolvedFPSCounterConfig     = viewModel.fpsCounterConfig,
                        resolvedEnvVars      = viewModel.envVarsStr,
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
        val contentScroll = rememberScrollState()

        // ── Shared collapsible left rail (landscape: expanded by default + per-screen persistence A;
        //    portrait: always collapsed, no toggle). See ui/components/CollapsibleRail. ─────────────
        val railState = rememberRailState("containers")
        val railCollapsed = railState.collapsed

        val activeTab = tabTitles.getOrNull(viewModel.selectedTab) ?: "GENERAL"
        val screenTitle = when {
            viewModel.defaultsMode -> "New Container Defaults"
            containerId <= 0 -> "New Container"
            else -> "Edit Container"
        }
        val railLinks = buildList {
            add(RailLink("What is all this?", Icons.Filled.Help) { glossaryQuery = "" })
            if (viewModel.defaultsMode) {
                add(RailLink(stringResource(R.string.reset_to_app_defaults), Icons.Filled.Restore) { viewModel.resetDefaults() })
            }
        }

        val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

        // The single scrolling content region — identical in portrait and landscape, so both layouts
        // reuse it. A bottom buffer (FAB + nav-bar inset + margin) is the ONLY reserved space, so
        // content reaches near the bottom instead of stopping in a dead zone.
        val mainContent: @Composable () -> Unit = {
            val bottomBuffer = 72.dp +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(contentScroll)
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp)
            ) {
                Column {
                    // Dispatch by tab TITLE (not index): DRIVES is absent in defaults mode, so a
                    // raw index would misalign the remaining tabs.
                    when (activeTab) {
                        "GENERAL" -> Column {
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
                        "ENVIROMENT" -> EnvVarsTab(viewModel)
                        "DRIVES" -> DrivesTab(viewModel)
                        "WIN COMPONENTS" -> WinComponentsTab(viewModel)
                        "ADVANCED" -> Column {
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
                    // Clears the save FAB + nav-bar inset so the last setting can scroll above them,
                    // leaving only a small buffer rather than a large empty band.
                    Spacer(modifier = Modifier.height(bottomBuffer))
                }
            }
        }

        if (isPortrait) {
            // PORTRAIT (the 3 container screens): tabs run across the TOP as a horizontal icon bar,
            // with the help/reset links in a slim row above them; content gets the full width. The
            // collapsible left rail is landscape-only. Same icons/labels as the rail — repositioned.
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                ContainerTopTabs(
                    tabs = tabTitles,
                    selected = viewModel.selectedTab,
                    links = railLinks,
                    onSelect = { viewModel.selectedTab = it },
                )
                mainContent()
            }
        } else {
            // LANDSCAPE: unchanged — the shared collapsible left rail beside full-height content.
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                CollapsibleRail(
                    state = railState,
                    title = screenTitle,
                    headerIcon = R.drawable.icon_menu_container,
                    links = railLinks,
                    sections = listOf(
                        RailSection(
                            header = null,
                            items = tabTitles.mapIndexed { index, tab ->
                                RailItem(tab, tabIcon(tab), index == viewModel.selectedTab) { viewModel.selectedTab = index }
                            },
                        )
                    ),
                )

                // ── Content: full height beside the rail ───────────────────────────────────────
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Collapsed rail hides the tab labels, so surface the active tab name over the
                    // content (mockup: "ctxhdr") — the user never loses their place.
                    if (railCollapsed) {
                        Text(
                            activeTab,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    mainContent()
                }
            }
        }
    }

    glossaryQuery?.let { q ->
        ContainerGlossarySheet(initialQuery = q, onDismiss = { glossaryQuery = null })
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
    // Mali compat/bcn testers need DXVK 1.x reachable even with VKD3D selected, to try the
    // 1.10.3 adapter-accept workaround (#137). Relax the #113 DXVK-2.x-only filter ONLY for the
    // "Wrapper + compat + bcn" driver; every other driver keeps the guard unchanged.
    val relaxDxvkFilter = StringUtils.parseIdentifier(viewModel.selectedGraphicsDriver) == "wrapper-compat-bcn"
    if (showDxvkConfig) {
        DxvkConfigDialog(
            isArm64EC = viewModel.isArm64EC,
            isVegas = isVegasWrapper,
            relaxDxvkFilter = relaxDxvkFilter,
            refreshKey = dxvkRefreshKey,
            initialConfig = viewModel.dxWrapperConfig,
            onConfirm = { newConfig -> viewModel.dxWrapperConfig = newConfig; showDxvkConfig = false },
            onDismiss = { showDxvkConfig = false },
            // Close the config dialog first — the download sheet is a ModalBottomSheet (activity
            // window) and would otherwise render BEHIND this AlertDialog. It reopens on sheet dismiss.
            onDownloadDxvk = { showDxvkConfig = false; if (isVegasWrapper) showVegasDownloadSheet = true else showDxvkDownloadSheet = true },
            onDownloadVkd3d = { showDxvkConfig = false; showVkd3dDownloadSheet = true },
            onDownloadD7vk = { showDxvkConfig = false; showD7vkDownloadSheet = true }
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
                ";sfCompatMode=${viewModel.rendererSfCompatMode}",
            onConfirm = { newConfig ->
                val m = parseVulkanConfig(newConfig)
                viewModel.rendererNative      = m["native"] == "true"
                viewModel.rendererPresentMode = m["presentMode"] ?: "fifo"
                viewModel.rendererDriverId    = m["driverId"] ?: "system"
                viewModel.rendererFilterMode  = m["filterMode"]?.toIntOrNull() ?: 0
                viewModel.rendererSwapRB      = m["swapRB"] == "true"
                // Default ON: absent token (old config) resolves to true (correct colours).
                viewModel.rendererSfCompatMode = m["sfCompatMode"] != "false"
                showVulkanConfig = false
            },
            onDismiss = { showVulkanConfig = false },
            frameGenSelected = viewModel.frameGenEngine != "off"
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
    if (showD7vkDownloadSheet) {
        ContentDownloadSheet(
            contentType = com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_D7VK,
            onDismiss = { showD7vkDownloadSheet = false; showDxvkConfig = true },
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

/** The Material icon for each container-settings tab (mirrors the mockup's glyphs). */
private fun tabIcon(title: String): ImageVector = when (title) {
    "GENERAL" -> Icons.Filled.Settings
    "ENVIROMENT" -> Icons.Filled.Extension
    "DRIVES" -> Icons.Filled.Storage
    "WIN COMPONENTS" -> Icons.Filled.Widgets
    "ADVANCED" -> Icons.Filled.Tune
    else -> Icons.Filled.Settings
}

/** Abbreviates the two long tab titles so the portrait top bar stays tidy (mirrors the rail's
 *  collapsed labels). */
private fun topTabLabel(title: String): String = when (title) {
    "ENVIROMENT" -> "ENVIRON"
    "WIN COMPONENTS" -> "WIN COMP"
    else -> title
}

/**
 * Portrait-only: the container-editor tabs as a horizontal icon bar across the top (landscape keeps
 * the collapsible left rail instead). Same icons/labels as the rail — just repositioned. The help /
 * reset-to-defaults buttons are appended INLINE at the end of the same row (a faint divider hints
 * they're actions, not tabs). Every cell gets equal weight, so the tabs + buttons evenly fill the
 * full width. Used by all 3 container screens (New / Edit = tabs + help; Defaults = tabs + help +
 * reset). No separate button row above the tabs, so the top stays tight under the header.
 */
@Composable
private fun ContainerTopTabs(
    tabs: List<String>,
    selected: Int,
    links: List<RailLink>,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                TopCell(
                    icon = tabIcon(tab),
                    label = topTabLabel(tab),
                    tint = if (active) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    highlight = active,
                    indicatorOn = active,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (links.isNotEmpty()) {
                // Faint separator: everything past here is an action button, not a tab.
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(30.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                )
                links.forEach { link ->
                    TopCell(
                        icon = link.icon,
                        label = topActionLabel(link.icon),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        highlight = false,
                        indicatorOn = false,
                        onClick = link.onClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

/** One cell of the portrait top bar — a tab or an action button (icon over a small label). The
 *  active underline collapses to zero width when off, so every cell keeps the same height. */
@Composable
private fun TopCell(
    icon: ImageVector,
    label: String,
    tint: Color,
    highlight: Boolean,
    indicatorOn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 1.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = tint,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(if (indicatorOn) 16.dp else 0.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (indicatorOn) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

/** Short label for the top-bar action buttons — only Help + Restore ever appear there. */
private fun topActionLabel(icon: ImageVector): String =
    if (icon == Icons.Filled.Restore) "RESET" else "HELP"

@Composable
internal fun VulkanSettingsDialog(
    initialConfig: String,
    onConfirm: (newConfig: String) -> Unit,
    onDismiss: () -> Unit,
    // The FG engine dropdown lives on the main screen, not in this dialog — pass whether one is
    // selected so we can caption the Present Mode field about the temporary auto-switch to Mailbox.
    frameGenSelected: Boolean = false
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
    // SurfaceFlinger (ASR) BGRA->RGBA colour correction (GN #1620). Default ON — an absent token
    // (old config) resolves to true. ASR-only; independent of swapRB (Vulkan/GL).
    var sfCompatMode by remember { mutableStateOf(cfg["sfCompatMode"] != "false") }

    // Per-field "?" help — this dialog is its own composable, so it carries its own helpRes.
    // HelpDialog renders as a Dialog on top of this AlertDialog (fine — same pattern as elsewhere).
    var helpRes by remember { mutableStateOf<Int?>(null) }
    helpRes?.let { HelpDialog(it) { helpRes = null } }

    OutlinedAlertDialog(
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
                    IconButton(onClick = { helpRes = R.string.help_renderer_native }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                    Switch(checked = nativeRender, onCheckedChange = { nativeRender = it })
                }

                val presentModes = listOf("fifo", "mailbox", "immediate")
                val presentModeLabels = listOf(
                    stringResource(R.string.renderer_present_mode_fifo),
                    stringResource(R.string.renderer_present_mode_mailbox),
                    stringResource(R.string.renderer_present_mode_immediate)
                )
                val selectedPresentIdx = presentModes.indexOf(presentMode).coerceAtLeast(0)
                // Present mode is ignored under Native Rendering (direct scanout goes straight to
                // SurfaceFlinger, bypassing the swapchain), so grey it out while native is on.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(
                        label = stringResource(R.string.renderer_present_mode),
                        options = presentModeLabels,
                        selectedOption = presentModeLabels[selectedPresentIdx],
                        onSelect = { presentMode = presentModes[presentModeLabels.indexOf(it)] },
                        enabled = !nativeRender,
                        modifier = (if (nativeRender) Modifier.alpha(0.5f) else Modifier).weight(1f)
                    )
                    IconButton(onClick = { helpRes = R.string.renderer_present_mode_help_content }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                // FG temporarily forces Mailbox; caption the field so FIFO-while-FG-selected isn't confusing.
                if (frameGenSelected) {
                    Text(
                        stringResource(R.string.renderer_present_mode_fg_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Renderer (compositor) driver: which Vulkan driver the present layer itself runs on —
                // "System" (Android's own driver, the safe default) or an installed Turnip. This is the
                // compositor, NOT where your game renders (that's the top-level Graphics Driver). Applied
                // at launch by XServerDisplayActivity (VulkanRenderer.setDriverInfo before nativeInit).
                // Vulkan-renderer only; a no-op on SurfaceFlinger/OpenGL. Options = System + installed
                // adrenotools drivers; default stays System because a Turnip compositor can black-screen
                // on builds whose WSI doesn't support the surface.
                val vkCtx = androidx.compose.ui.platform.LocalContext.current
                val rendererDriverOptions = remember {
                    val installed = try {
                        com.winlator.star.contents.AdrenotoolsManager(vkCtx).enumarateInstalledDrivers()
                    } catch (e: Exception) { arrayListOf<String>() }
                    (listOf("system") + installed).distinct()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(
                        label = stringResource(R.string.renderer_driver_id),
                        options = rendererDriverOptions,
                        selectedOption = if (rendererDriverOptions.contains(driverId)) driverId else "system",
                        onSelect = { driverId = it },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { helpRes = R.string.help_renderer_driver }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }

                // Filter mode (Nearest/Linear) is no longer edited here: the in-game
                // drawer's "Scaling mode" picker is the single source of truth for
                // Vulkan scaling/filtering (modes 1/2 drive the base sampler natively).
                // The persisted `filterMode` value is preserved untouched below and
                // still seeds the drawer's initial scaling mode at launch
                // (XServerDisplayActivity: getRendererFilterMode -> initialUpscaler).

                // Colors = the game buffer's channel order. Device-confirmed: DXVK's scanout buffer is
                // BGRA_8888 (AHardwareBuffer format 5), so BGRA (default) presents as-is; RGBA swaps R/B
                // for the rare title whose buffer is actually RGBA-ordered (red/blue reversed on default).
                // Backed by the same `swapRB` boolean (BGRA=false, RGBA=true); RGBA (swap) routes the
                // container through the compositor (native scanout can't swap channels).
                val colorOrders = listOf("BGRA", "RGBA")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(
                        label = stringResource(R.string.renderer_colors),
                        options = colorOrders,
                        selectedOption = if (swapRB) "RGBA" else "BGRA",
                        onSelect = { swapRB = (it == "RGBA") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { helpRes = R.string.help_renderer_colors }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
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
                val config = "native=$nativeRender;presentMode=$presentMode;driverId=$driverId;filterMode=$filterMode;swapRB=$swapRB;sfCompatMode=$sfCompatMode"
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
    // Per-field "?" help — a centered, scrollable Compose dialog (HelpDialog), replacing the old
    // top-left PopupWindow. null = no dialog; otherwise the string res of the field's help text.
    var helpRes by remember { mutableStateOf<Int?>(null) }
    var showAudioSettings by remember { mutableStateOf(false) }
    helpRes?.let { HelpDialog(it) { helpRes = null } }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {

        // Name — per-container identity, hidden in "New Container Defaults" mode (not templatable).
        if (!viewModel.defaultsMode) {
            OutlinedTextField(
                value = viewModel.containerName,
                onValueChange = { viewModel.containerName = it },
                label = { Text(stringResource(R.string.name)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }

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

        // Wine Version (create/edit) OR Architecture selector (defaults mode). Defaults are stored
        // per-arch (box64/wowbox64/emulator/FEXCore are arch-coupled) and wine is NEVER templated, so
        // in defaults mode the arch selector replaces the wine version dropdown + its download gear and
        // drives the arch-dependent fields via setDefaultsArch (which reloads that arch's profile).
        if (viewModel.defaultsMode) {
            val archValues = listOf(
                com.winlator.star.core.NewContainerDefaults.ARCH_X86_64,
                com.winlator.star.core.NewContainerDefaults.ARCH_ARM64EC,
            )
            val archLabels = listOf("x86-64", "arm64ec")
            val archIdx = archValues.indexOf(viewModel.defaultsArch).coerceAtLeast(0)
            LabeledDropdown(
                label = "Architecture",
                options = archLabels,
                selectedOption = archLabels[archIdx],
                onSelect = { viewModel.selectDefaultsArch(archValues[archLabels.indexOf(it)]) }
            )
        } else {
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
                IconButton(onClick = { helpRes = R.string.help_wine_version }) {
                    Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Graphics Driver + wrapper manager (cloud) + config button
        var showWrapperManager by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LabeledDropdown(
                label = stringResource(R.string.graphics_driver),
                options = viewModel.graphicsDriverEntries,
                selectedOption = viewModel.selectedGraphicsDriver,
                onSelect = { viewModel.selectedGraphicsDriver = it },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { helpRes = R.string.help_graphics_driver }) {
                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { showWrapperManager = true }) {
                Icon(Icons.Default.CloudDownload, contentDescription = stringResource(R.string.wrapper_manager_open))
            }
            IconButton(onClick = onShowGfxConfig) {
                Icon(Icons.Default.Settings, contentDescription = null)
            }
        }
        if (showWrapperManager) WrapperManagerDialog(onDismiss = {
            showWrapperManager = false
            viewModel.refreshGraphicsDriverEntries() // pick up a just-imported/deleted wrapper
        })
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
                IconButton(onClick = { helpRes = R.string.dxwrapper_help_content }) {
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
            IconButton(onClick = { helpRes = R.string.help_renderer }) {
                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
            }
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
                IconButton(onClick = { helpRes = R.string.help_renderer_sf_compat }) {
                    Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabeledDropdown(
                label = stringResource(R.string.audio_driver),
                options = viewModel.audioDriverEntries,
                selectedOption = viewModel.selectedAudioDriver,
                onSelect = { viewModel.selectedAudioDriver = it },
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
        if (showAudioSettings) {
            AudioSettingsDialog(
                initial = audioConfigFromEnv(viewModel.envVarsStr, StringUtils.parseIdentifier(viewModel.selectedAudioDriver)),
                scopeLabel = "this container",
                latencyLive = true,
                driverLabel = when (StringUtils.parseIdentifier(viewModel.selectedAudioDriver)) {
                    "alsa" -> "ALSA"; "pulseaudio" -> "PulseAudio"; "directaudio" -> "DirectAudio"
                    else -> StringUtils.parseIdentifier(viewModel.selectedAudioDriver)
                },
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
        val fgDisabledOpts = buildSet {
            // bionic-fg re-enabled (2.9.4+): the FIFO-backpressure present-mode fix is the likely
            // root of its old "doesn't reliably work" reports; still experimental — see the note below.
            if (!lsfgDllAvailable) add(fgEngineLabels[2])   // lsfg-vk — needs an imported Lossless.dll
        }
        val fgSelIdx = fgEngines.indexOf(viewModel.frameGenEngine).coerceAtLeast(0)
        // FG's present-mode/mailbox delivery only exists on the Vulkan host renderer; OpenGL (GLRenderer)
        // and SurfaceFlinger (ASR) have no present-mode control, so FG is unsupported there — gate the
        // whole dropdown on Vulkan and grey it out otherwise (combined with the lsfg-DLL option gate).
        val fgVulkan = viewModel.selectedRenderer == "Vulkan"
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabeledDropdown(
                label = stringResource(R.string.frame_generation),
                options = fgEngineLabels,
                selectedOption = fgEngineLabels[fgSelIdx],
                onSelect = { viewModel.frameGenEngine = fgEngines[fgEngineLabels.indexOf(it)] },
                enabled = fgVulkan,
                disabledOptions = fgDisabledOpts,
                modifier = (if (!fgVulkan) Modifier.alpha(0.5f) else Modifier).weight(1f)
            )
            IconButton(onClick = { helpRes = R.string.help_frame_generation }) {
                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
            }
        }
        if (!fgVulkan) {
            Text(
                text = stringResource(R.string.frame_generation_requires_vulkan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
            )
        }
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
            // Interpolation model. Default (0) is the long-standing chain; 1-3 are newer engines
            // that are not yet device-proven, hence the explicit "experimental" labelling.
            val fgModelLabels = listOf(
                stringResource(R.string.frame_generation_model_default),
                stringResource(R.string.frame_generation_model_traced),
                stringResource(R.string.frame_generation_model_v2),
                stringResource(R.string.frame_generation_model_fsr3),
                stringResource(R.string.frame_generation_model_fsr3_v2)
            )
            LabeledDropdown(
                label = stringResource(R.string.frame_generation_model),
                options = fgModelLabels,
                selectedOption = fgModelLabels[viewModel.frameGenModel.coerceIn(0, 4)],
                onSelect = { viewModel.frameGenModel = fgModelLabels.indexOf(it) }
            )
            if (viewModel.frameGenModel != 0) {
                Text(
                    text = stringResource(R.string.frame_generation_model_experimental_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
                )
            }
        }
        if (viewModel.frameGenEngine == "lsfg") {
            Text(
                text = stringResource(R.string.frame_generation_lsfg_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
            )
            // lsfg-vk performance_mode: lower quality for higher FPS. Also live-toggleable in-game.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = viewModel.lsfgPerformanceMode,
                    onCheckedChange = { viewModel.lsfgPerformanceMode = it }
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fg_performance_mode), modifier = Modifier.weight(1f))
            }
            Text(
                text = stringResource(R.string.fg_performance_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
            )
            // lsfg-vk auto-enable at launch: start frame gen live at the saved multiplier from launch.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = viewModel.lsfgAutoEnable,
                    onCheckedChange = { viewModel.lsfgAutoEnable = it }
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lsfg_auto_enable), modifier = Modifier.weight(1f))
            }
            Text(
                text = stringResource(R.string.lsfg_auto_enable_hint),
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
            IconButton(onClick = { helpRes = R.string.help_fps_limiter }) {
                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
            }
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

        // Single guest-side refresh control. Collapses the unlock toggle + rate cap into one dropdown:
        //   Locked (60)  -> emulation stays on, game sits at 60 (unlockGameRefreshRate = false)
        //   <rate> Hz    -> unlock on, capped at that rate (unlock = true, maxGameRefreshRate = rate)
        //   Unlimited    -> unlock on, no cap (unlock = true, maxGameRefreshRate = 0)
        // Default Unlimited. Drives the two underlying extras so the launch resolver is unchanged. Not
        // gated on vrrCapable — a game choosing 120 Hz is meaningful even where the panel can't do VRR.
        if (supportedRates.isNotEmpty()) {
            val lockedLabel = stringResource(R.string.in_game_refresh_locked)
            val unlimitedLabel = stringResource(R.string.max_game_refresh_rate_unlimited)
            // Only rates ABOVE 60 are cap options — "Locked (60)" already covers 60.
            val ratesAbove60 = supportedRates.filter { it > 60 }
            // value model: -1 = Locked, 0 = Unlimited, N = cap N
            val rrOptionValues = listOf(-1, 0) + ratesAbove60
            val rrOptionLabels = listOf(lockedLabel, unlimitedLabel) + ratesAbove60.map { "$it Hz" }
            val rrCurrentValue = if (!viewModel.unlockGameRefreshRate) -1 else viewModel.maxGameRefreshRate
            val rrIdx = rrOptionValues.indexOf(rrCurrentValue).let { if (it >= 0) it else 1 } // fall back to Unlimited
            LabeledDropdown(
                label = stringResource(R.string.in_game_refresh_rate),
                options = rrOptionLabels,
                selectedOption = rrOptionLabels[rrIdx],
                onSelect = {
                    when (val v = rrOptionValues[rrOptionLabels.indexOf(it)]) {
                        -1 -> { viewModel.unlockGameRefreshRate = false; viewModel.maxGameRefreshRate = 0 }
                        else -> { viewModel.unlockGameRefreshRate = true; viewModel.maxGameRefreshRate = v }
                    }
                }
            )
            Text(
                text = stringResource(R.string.in_game_refresh_rate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
            )
            // Warn when a non-Locked choice is set but the selected Proton has no xrandr — the unlock
            // will be skipped at launch. Keyed on the wine version so the cached probe only re-runs when
            // the layer changes.
            val wineXrandrCapable = remember(viewModel.selectedWineVersion) {
                viewModel.isWineXrandrCapable(viewModel.selectedWineVersion)
            }
            if (viewModel.unlockGameRefreshRate && !wineXrandrCapable) {
                Text(
                    text = stringResource(R.string.refresh_unlock_layer_incompatible_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 4.dp)
                )
            }
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

        // System section — "Run as administrator" (default ON) toggles UAC in the prefix. Backed by
        // EnableLUA in .wine/system.reg (source of truth): the VM reads it on load and writes it on
        // save/create (mirrors the DirectInput mouse-warp registry idiom above).
        SectionBox(title = "System") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Run as administrator")
                    Text(
                        "Disables UAC so everything runs elevated (helps installers/tools that require admin)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.runAsAdmin,
                    onCheckedChange = { viewModel.runAsAdmin = it }
                )
            }
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
private fun EnvVarsTab(viewModel: ContainerDetailViewModel) {
    // The editor writes straight through to the ViewModel on every edit, so a tab switch
    // can't drop in-progress edits and nothing has to be flushed back on dispose.
    // gameDir is null here: a container has no single game folder to scan.
    EnvVarsEditor(
        value = viewModel.envVarsStr,
        onValueChange = { viewModel.envVarsStr = it },
        modifier = Modifier.fillMaxWidth()
    )
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DrivesTab(viewModel: ContainerDetailViewModel) {
    val context = LocalContext.current
    var pendingDriveUid by remember { mutableStateOf<Long?>(null) }

    // Uses the built-in picker rather than SAF: it returns a real absolute path, so a folder on the
    // SD card yields /storage/<uuid>/... The SAF mapping produced /mnt/media_rw/<uuid>/..., the raw
    // vold mount, which neither the app nor the container can read.
    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val path = InAppFilePicker.pickedPath(result.data)
            val uid = pendingDriveUid
            if (path != null && uid != null) viewModel.updateDrivePath(uid, path)
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
        val duplicateLetters = viewModel.duplicateDriveLetters
        viewModel.drives.forEach { drive ->
            DriveRow(
                drive = drive,
                letterOptions = viewModel.driveLetterOptions,
                isDuplicate = drive.letter in duplicateLetters,
                onLetterChange = { viewModel.updateDriveLetter(drive.uid, it) },
                onPathChange   = { viewModel.updateDrivePath(drive.uid, it)   },
                onBrowse = {
                    pendingDriveUid = drive.uid
                    dirPickerLauncher.launch(
                        InAppFilePicker.buildDirIntent(
                            context,
                            title = "Select folder for drive ${drive.letter}:",
                            initialDir = drive.path.takeIf { it.isNotBlank() && File(it).isDirectory },
                        )
                    )
                },
                onRemove = { viewModel.removeDrive(drive.uid) }
            )
        }
        if (duplicateLetters.isNotEmpty()) {
            Text(
                "Each drive needs its own letter. Duplicated: " +
                    duplicateLetters.sorted().joinToString(", ") { "$it:" },
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
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
    isDuplicate: Boolean,
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
            modifier = Modifier
                .width(64.dp)
                .then(
                    if (isDuplicate) Modifier.border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        RoundedCornerShape(4.dp),
                    ) else Modifier
                )
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
    // Per-field "?" help — centered scrollable Compose dialog (same as the General tab).
    var helpRes by remember { mutableStateOf<Int?>(null) }
    helpRes?.let { HelpDialog(it) { helpRes = null } }
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
                    IconButton(onClick = { helpRes = R.string.help_fexcore_version }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                    ContentInstallGear(onDownloadFile = onShowFexCoreDownloadSheet)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(
                        label = stringResource(R.string.fexcore_preset),
                        options = viewModel.fexCorePresetEntries,
                        selectedOption = viewModel.fexCorePresetEntries.getOrElse(viewModel.selectedFEXCorePresetIndex) { "" },
                        onSelect = { opt -> viewModel.selectedFEXCorePresetIndex = viewModel.fexCorePresetEntries.indexOf(opt).coerceAtLeast(0) },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { helpRes = R.string.help_fexcore_preset }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
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
                IconButton(onClick = { helpRes = R.string.help_xinput }) {
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
                IconButton(onClick = { helpRes = R.string.help_dinput }) {
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
                IconButton(onClick = { helpRes = R.string.help_exclusive_xinput }) {
                    Icon(Icons.Default.Help, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Vibration section — per-container rumble target + intensity default. Also live-tunable
        // in-game from the drawer (this is just the value a new session launches with).
        SectionBox(title = stringResource(R.string.vibration)) {
            val vibrationModeLabels = listOf(
                stringResource(R.string.vibration_mode_off),
                stringResource(R.string.vibration_mode_controller),
                stringResource(R.string.vibration_mode_device),
                stringResource(R.string.vibration_mode_both),
            )
            LabeledDropdown(
                label = stringResource(R.string.vibration_mode_label),
                options = vibrationModeLabels,
                selectedOption = vibrationModeLabels.getOrElse(viewModel.vibrationMode) {
                    vibrationModeLabels[Container.VIBRATION_MODE_DEFAULT]
                },
                onSelect = { opt -> viewModel.vibrationMode = vibrationModeLabels.indexOf(opt).coerceAtLeast(0) }
            )
            if (viewModel.vibrationMode != Container.VIBRATION_MODE_OFF) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${stringResource(R.string.vibration_intensity_label)}: ${viewModel.vibrationIntensity}%",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = viewModel.vibrationIntensity.toFloat(),
                    onValueChange = { viewModel.vibrationIntensity = it.toInt() },
                    valueRange = 0f..100f, steps = 99
                )
            }
        }

        // Player Slots section — manual controller->player-slot pins that the launch pre-assignment
        // applies. Same JSON schema and editor the in-game "Players" drawer tab uses, but here it's a
        // saved default (applied on next launch), not a live reassignment.
        SectionBox(title = "Player Slots") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pin controllers to players; assign two to one player to share control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { helpRes = R.string.help_player_slots }) {
                    Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                }
            }
            // On-screen priority: what happens to the on-screen pad when a physical pad connects mid-game.
            val onScreenModeLabels = listOf("Keep on-screen player", "Yield Player 1 to pad", "Share the player")
            LabeledDropdown(
                label = "On-screen priority",
                options = onScreenModeLabels,
                selectedOption = onScreenModeLabels.getOrElse(viewModel.onScreenControllerMode) { onScreenModeLabels[0] },
                onSelect = { viewModel.onScreenControllerMode = onScreenModeLabels.indexOf(it).coerceAtLeast(0) },
            )
            Spacer(Modifier.height(8.dp))
            // #333: auto-hide the on-screen touch controls when a physical controller takes over the
            // on-screen pad's player slot; they reappear when it leaves. Slot-aware — a controller pinned
            // to a DIFFERENT player (2-player setup) leaves the overlay up. Overrides Share while active.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = viewModel.autoHideControlsOnPad,
                    onCheckedChange = { viewModel.autoHideControlsOnPad = it }
                )
                Spacer(Modifier.width(8.dp))
                Text("Hide on-screen controls when a controller connects")
            }
            Spacer(Modifier.height(8.dp))
            PlayerSlotsEditor(
                savedOverridesJson = viewModel.controllerSlotOverridesJson,
                onOverridesChange = { viewModel.controllerSlotOverridesJson = it },
            )
        }

        // Gyro (motion aim) section — the default a session (and a new shortcut) launches with.
        // Enabled/target/activator/sensitivity/invert can be overridden per game in the shortcut
        // editor; deadzone/smoothing stay container-wide because they track the hand and the device,
        // not the game. All of them are also live-tunable from the in-game drawer.
        SectionBox(title = stringResource(R.string.gyro)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = viewModel.gyroEnabled,
                    onCheckedChange = { viewModel.gyroEnabled = it }
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.gyro_enabled), modifier = Modifier.weight(1f))
            }
            if (viewModel.gyroEnabled) {
                // Rate = tilt SPEED drives the target and it recentres when you stop; Tilt to Aim =
                // the ANGLE held drives it, so a held tilt sustains. Mutually exclusive with the Mouse
                // target: the mouse path emits relative deltas, so a held tilt would be a constant
                // delta and the pointer would run to a screen edge and stay there. Each selection
                // therefore knocks the other back to a working value (WinHandler enforces the same
                // rule at launch, so a hand-edited container JSON can't reach the broken pair either).
                val gyroModeLabels = listOf(
                    stringResource(R.string.gyro_mode_rate),
                    stringResource(R.string.gyro_mode_orientation),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(
                        label = stringResource(R.string.gyro_mode_label),
                        options = gyroModeLabels,
                        selectedOption = gyroModeLabels.getOrElse(viewModel.gyroMode) {
                            gyroModeLabels[Container.GYRO_MODE_DEFAULT]
                        },
                        onSelect = { opt ->
                            val mode = gyroModeLabels.indexOf(opt).coerceAtLeast(0)
                            viewModel.gyroMode = mode
                            if (mode == Container.GYRO_MODE_ORIENTATION && viewModel.gyroTarget == Container.GYRO_TARGET_MOUSE)
                                viewModel.gyroTarget = Container.GYRO_TARGET_DEFAULT
                        },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { helpRes = R.string.help_gyro_mode }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                val gyroTargetLabels = listOf(
                    stringResource(R.string.gyro_target_right_stick),
                    stringResource(R.string.gyro_target_left_stick),
                    stringResource(R.string.gyro_target_mouse),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(
                        label = stringResource(R.string.gyro_target_label),
                        options = gyroTargetLabels,
                        selectedOption = gyroTargetLabels.getOrElse(viewModel.gyroTarget) {
                            gyroTargetLabels[Container.GYRO_TARGET_DEFAULT]
                        },
                        onSelect = { opt ->
                            val target = gyroTargetLabels.indexOf(opt).coerceAtLeast(0)
                            viewModel.gyroTarget = target
                            if (target == Container.GYRO_TARGET_MOUSE)
                                viewModel.gyroMode = Container.GYRO_MODE_RATE
                        },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { helpRes = R.string.help_gyro_target }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                val gyroActivatorLabels = listOf(
                    stringResource(R.string.gyro_activator_l1),
                    stringResource(R.string.gyro_activator_l2),
                    stringResource(R.string.gyro_activator_r1),
                    stringResource(R.string.gyro_activator_r3),
                    stringResource(R.string.gyro_activator_always),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(
                        label = stringResource(R.string.gyro_activator_label),
                        options = gyroActivatorLabels,
                        selectedOption = gyroActivatorLabels.getOrElse(viewModel.gyroActivator) {
                            gyroActivatorLabels[Container.GYRO_ACTIVATOR_DEFAULT]
                        },
                        onSelect = { opt -> viewModel.gyroActivator = gyroActivatorLabels.indexOf(opt).coerceAtLeast(0) },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { helpRes = R.string.help_gyro_activator }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                // Hold vs Toggle for that button. Pointless with "Always On" (no button to latch), so
                // the picker is hidden rather than greyed here — an editor row has no live state to
                // explain, unlike the in-game drawer where the row stays visible but disabled.
                if (viewModel.gyroActivator != Container.GYRO_ACTIVATOR_ALWAYS) {
                    val gyroActivationModeLabels = listOf(
                        stringResource(R.string.gyro_activation_hold),
                        stringResource(R.string.gyro_activation_toggle),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabeledDropdown(
                            label = stringResource(R.string.gyro_activation_mode_label),
                            options = gyroActivationModeLabels,
                            selectedOption = gyroActivationModeLabels.getOrElse(viewModel.gyroActivationMode) {
                                gyroActivationModeLabels[Container.GYRO_ACTIVATION_MODE_DEFAULT]
                            },
                            onSelect = { opt -> viewModel.gyroActivationMode = gyroActivationModeLabels.indexOf(opt).coerceAtLeast(0) },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { helpRes = R.string.help_gyro_activation_mode }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${stringResource(R.string.gyro_sensitivity_label)}: ${"%.1f".format(viewModel.gyroSensitivity)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { helpRes = R.string.help_gyro_sensitivity }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Slider(
                    value = viewModel.gyroSensitivity,
                    onValueChange = { viewModel.gyroSensitivity = it },
                    valueRange = 0.1f..10f
                )
                Text(
                    "${stringResource(R.string.gyro_deadzone_label)}: ${"%.2f".format(viewModel.gyroDeadzone)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = viewModel.gyroDeadzone,
                    onValueChange = { viewModel.gyroDeadzone = it },
                    valueRange = 0f..0.5f
                )
                Text(
                    "${stringResource(R.string.gyro_smoothing_label)}: ${"%.2f".format(viewModel.gyroSmoothing)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = viewModel.gyroSmoothing,
                    onValueChange = { viewModel.gyroSmoothing = it },
                    valueRange = 0f..0.95f
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = viewModel.gyroInvertX,
                        onCheckedChange = { viewModel.gyroInvertX = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.gyro_invert_x), modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = viewModel.gyroInvertY,
                        onCheckedChange = { viewModel.gyroInvertY = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.gyro_invert_y), modifier = Modifier.weight(1f))
                }
            }
        }

        // Startup Selection
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabeledDropdown(
                label = stringResource(R.string.startup_selection),
                options = viewModel.startupSelectionEntries,
                selectedOption = viewModel.startupSelectionEntries.getOrElse(viewModel.selectedStartupSelection) { "" },
                onSelect = { opt -> viewModel.selectedStartupSelection = viewModel.startupSelectionEntries.indexOf(opt).coerceAtLeast(0) },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { helpRes = R.string.help_startup_selection }) {
                Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
            }
        }

        // Custom per-service toggles — only shown when "Custom" (index 3) is selected.
        if (viewModel.selectedStartupSelection == Container.STARTUP_SELECTION_CUSTOM.toInt()) {
            StartupServicesToggleList(
                enabled = viewModel.startupServicesEnabled,
                onToggle = { raw, on ->
                    viewModel.startupServicesEnabled =
                        if (on) viewModel.startupServicesEnabled + raw
                        else viewModel.startupServicesEnabled - raw
                }
            )
        }

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

// Per-service on/off list for the "Custom" startup selection. Shared by the container editor and the
// shortcut Advanced tab (both are in this package), so the service list, labels and ordering come
// from the single WineUtils source of truth and can't drift between the two screens.
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

    val cfg = remember(initialConfig) {
        initialConfig.split(";").associate { elem ->
            val parts = elem.split("=")
            parts[0] to if (parts.size > 1) parts[1] else ""
        }
    }

    var version          by remember { mutableStateOf(cfg["version"] ?: "") }
    var vulkanVersion    by remember { mutableStateOf(cfg["vulkanVersion"] ?: "1.4") }
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

    // --- Turnip GMEM tri-state (task #1) + advanced TU_DEBUG tokens (task #2) ---
    // turnipGmem: "auto" (default) | "on" | "off". Auto adds the `gmem` token only on Adreno
    // 710/720/722 at launch (resolved in XServerDisplayActivity). turnip* token flags map to the
    // opt-in advanced TU_DEBUG tokens, stored comma-joined under "turnipTokens".
    var turnipGmem       by remember { mutableStateOf(cfg["turnipGmem"] ?: "auto") }
    val initialTurnipTokens = remember(initialConfig) {
        (cfg["turnipTokens"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    var turnipForceCb    by remember { mutableStateOf("forcecb" in initialTurnipTokens) }
    var turnipNoCb       by remember { mutableStateOf("nocb" in initialTurnipTokens) }
    var turnipSysmem     by remember { mutableStateOf("sysmem" in initialTurnipTokens) }
    var turnipDeckEmu    by remember { mutableStateOf("deck_emu" in initialTurnipTokens) }
    var turnipSectionExpanded by remember { mutableStateOf(false) }

    // --- #132 Smart Wrapper Manager: capability + GPU gating (replaces the old exact-name gates) ---
    // Gate the BCn options by WHAT THE WRAPPER CONTAINS, not by its identifier string. capsFor()
    // returns a bundled driver's known caps, or — for an imported wrapper — the caps detected from
    // its .tzst at import time (libvulkan_wrapper.so / libbcn_layer.so / libdxvk_mali_compat_layer.so,
    // cached in the .meta). This reproduces every bundled driver's current gating exactly, while
    // letting an imported wrapper that actually carries a BCn layer (e.g. "112") show the same
    // options. hasCompatLayer drives the DX12/compat UI (sparse binding / "Use GameNative engine")
    // brought in from the Mali branch below.
    val caps = remember(graphicsDriver) { WrapperManager(context).capsFor(graphicsDriver) }
    val isImported = remember(graphicsDriver) { WrapperManager(context).isImported(graphicsDriver) }
    // Integrated-BCn ICD = a wrapper ICD that honors WRAPPER_BCN_ASTC. Bundled: only wrapper-gamenative
    // (hasIcd + hasBcnLayer). For IMPORTS we can't yet tell integrated-BCn (env baked into the ICD, no
    // separate .so) from a plain ICD without an env-scan of the binary (Step-3 Layer 1, deferred), so
    // any imported ICD shows the toggle — a non-gamenative ICD simply ignores WRAPPER_BCN_ASTC (inert,
    // harmless). This keeps an imported GameNative wrapper from losing its ASTC toggle.
    val isIntegratedBcn = (caps.hasIcd && caps.hasBcnLayer) || (isImported && caps.hasIcd)
    // Standalone BCn Layer Settings (the implicit bcn_layer overlay's env block). Bundled: only
    // wrapper-bcn_layer (hasBcnLayer, no own ICD). For imports: any archive carrying a BCn layer.
    // The bundled integrated-BCn ICD (gamenative, hasIcd + hasBcnLayer but NOT an import) is excluded
    // — it drives BCn through WRAPPER_EMULATE_BCN, not the implicit-layer env — so its dialog stays
    // byte-for-byte as today.
    val showBcnLayerSettings = caps.hasBcnLayer && (isImported || !caps.hasIcd)
    // GPU awareness: BCn transcode/emulation is inert on Qualcomm/Adreno (native BCn). Mirror XSDA's
    // activateBcnLayer = getVendorID != 0x5143 gate so we MARK (never silently hide) those options.
    val isQualcomm = remember { GPUInformation.getVendorID(null, null) == 0x5143 }
    val gpuModel = remember { GPUInformation.extractModelName(GPUInformation.getRenderer(null, context)) ?: "" }
    // --- Mali branch: name-based gates for the new "Wrapper + compat + bcn" (DX12) driver. Kept
    // alongside the capability gates above — downstream UI uses BOTH (caps gates for the generic BCn
    // panel; these for the compat-driver-specific DX12/GameNative-engine controls). ---
    // BCn Layer (leegao bcn_layer) settings; meaningful for both the bcn_layer driver and the
    // "Wrapper + compat + bcn" driver, which reuses the same BCn transcode panel.
    val isBcnLayer = graphicsDriver == "wrapper-bcn_layer" || graphicsDriver == "wrapper-compat-bcn"
    // compat_layer (DX12 feature emulation) is exclusive to "Wrapper + compat + bcn".
    val isCompatDriver = graphicsDriver == "wrapper-compat-bcn"
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
    // compat_layer: emulate D3D12 tiled/sparse resources (COMPAT_EMULATE_SPARSE_BINDING). Opt-in,
    // only honored by the "Wrapper + compat + bcn" driver on a Valhall Mali. Default OFF.
    var bcnCompatSparse   by remember { mutableStateOf(cfg["bcnCompatSparse"] == "1") }
    // compat engine selection: OFF (default) = leegao bcn_layer + compat_layer (BCn textures only,
    // no DX12). ON = swap the ICD base to the GameNative wrapper (wrapper-gamenative.tzst), which
    // reports Vulkan 1.3 + emulates the promoted entrypoints so DXVK/VKD3D accept the adapter (DX12),
    // and uses its own integrated BCn. Only for "Wrapper + compat + bcn"; a Valhall Mali (r32p1+) is
    // required at activation time (XServerDisplayActivity gates it and falls back with a warning).
    var compatUseGamenative by remember { mutableStateOf(cfg["compatUseGamenative"] == "1") }

    // --- #132 Smart Wrapper Manager, Layer 1: auto-detected settings for IMPORTED wrappers only ---
    // The env-var NAMES were scanned out of the wrapper's binaries at import and cached in its .meta.
    // We only READ the .meta here (off-main via IO, keyed on the config so it can't drift), then render
    // one control per detected key that ISN'T already exposed by a curated control above. Values live in
    // graphicsDriverConfig under the RAW ENV KEY so they round-trip and XSDA emits them generically.
    var detectedKeys by remember(graphicsDriver) { mutableStateOf<List<String>>(emptyList()) }
    val detectedValues = remember(graphicsDriver) { mutableStateMapOf<String, String>() }
    LaunchedEffect(graphicsDriver, isImported) {
        if (!isImported) { detectedKeys = emptyList(); return@LaunchedEffect }
        val wm = WrapperManager(context)
        // A detected key is a settable SETTING only if it isn't already exposed by a curated control
        // (HANDLED_ENV_KEYS), isn't debug/diagnostics plumbing (isDebugEnvKey), and hasn't been hidden
        // for this wrapper via "Edit settings" (hiddenKeys). Same predicate as XSDA emission + the
        // Edit-settings dialog.
        val hidden = withContext(Dispatchers.IO) { wm.hiddenKeys(graphicsDriver) }
        val keys = withContext(Dispatchers.IO) { wm.detectedEnvKeys(graphicsDriver) }
            .filter {
                it !in WrapperManager.HANDLED_ENV_KEYS && !WrapperManager.isDebugEnvKey(it) &&
                    !WrapperManager.isDriverInternalEnvKey(it) && it !in hidden
            }
        keys.forEach { k ->
            val def = WrapperSettingsDictionary.defFor(k)
            // Seed from the stored config; a toggle normalises to "1"/"0", others keep the raw string.
            detectedValues[k] = when (def.type) {
                WrapperSettingsDictionary.Type.TOGGLE -> if (cfg[k] == "1") "1" else "0"
                else -> cfg[k] ?: ""
            }
        }
        detectedKeys = keys
    }

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

    // Per-option "?" help — this dialog is its own composable, so it carries its own helpRes.
    // HelpDialog renders as a Dialog on top of this AlertDialog (same pattern as elsewhere).
    var helpRes by remember { mutableStateOf<Int?>(null) }
    helpRes?.let { HelpDialog(it) { helpRes = null } }

    OutlinedAlertDialog(
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(stringResource(R.string.graphics_driver_vulkan_version), vulkanVersions, vulkanVersion, { vulkanVersion = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_vulkan_version }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
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
                    Text(stringResource(R.string.graphics_driver_show_incompatible), modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_show_incompatible_drivers }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
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
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(stringResource(R.string.gpu_name), gpuNames, gpuName, { gpuName = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_gpu_name }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(stringResource(R.string.graphics_driver_max_device_memory), deviceMemoryEntries, selectedMemoryEntry, { selectedMemoryEntry = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_max_device_memory }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(stringResource(R.string.graphics_driver_present_modes), presentModeEntries, presentMode, { presentMode = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_wrapper_present_modes }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(stringResource(R.string.graphics_driver_resource_type), resourceTypeEntries, resourceType, { resourceType = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_resource_type }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // #132 GPU-context note: BCn transcode/emulation (and the compat layers) only do
                // anything on Mali/non-Qualcomm GPUs — Adreno has native BCn. Surface the real chip so
                // Adreno users understand why these options are inert for them (a real point of confusion).
                Text(
                    (if (gpuModel.isNotEmpty()) "GPU: $gpuModel — " else "") +
                        "BCn/compat layers apply to Mali and other non-Qualcomm GPUs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(stringResource(R.string.graphics_driver_bcn_emulation), bcnEmulationEntries, bcnEmulation, { bcnEmulation = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_bcn_emulation }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(stringResource(R.string.graphics_driver_bcn_emulation_type), bcnTypeEntries, bcnEmulationType, { bcnEmulationType = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_bcn_emulation_type }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown(stringResource(R.string.graphics_driver_bcn_emulation_cache), bcnCacheEntries, bcnEmulationCache, { bcnEmulationCache = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_bcn_emulation_cache }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // ASTC transcode is offered by any integrated-BCn wrapper ICD (bundled Wrapper-
                // gamenative, or an imported wrapper whose archive carries a BCn layer). The standalone
                // bcn_layer driver has its own ASTC control in its section below; the older wrappers
                // ignore WRAPPER_BCN_ASTC entirely — so gate on the detected capability, not the name.
                if (isIntegratedBcn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = bcnEmulationAstc, onCheckedChange = { bcnEmulationAstc = it })
                        Text(stringResource(R.string.graphics_driver_bcn_emulation_astc), modifier = Modifier.weight(1f))
                        IconButton(onClick = { helpRes = R.string.help_bcn_transcode_astc }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
                    if (isQualcomm) {
                        Text(
                            "No effect on Adreno (native BCn)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Compute-layer BCn -> ASTC target on the DEFAULT driver. When BCn type = compute, the
                // implicit leegao bcn_layer is the active decoder (ENABLE_BCN_COMPUTE) and honors
                // BCN_TRANSCODE_TO_ASTC — previously only exposed on the explicit Wrapper + bcn_layer
                // driver, so Mali users had to hand-add the env var. Reuses the same bcnTranscodeAstc
                // state (already persisted); default off.
                if (!isBcnLayer && bcnEmulationType == "compute" &&
                    (bcnEmulation == "auto" || bcnEmulation == "full")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = bcnTranscodeAstc, onCheckedChange = { bcnTranscodeAstc = it })
                        Text(stringResource(R.string.bcn_layer_transcode_astc), modifier = Modifier.weight(1f))
                        IconButton(onClick = { helpRes = R.string.help_bcn_transcode_astc }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
                    if (isQualcomm) {
                        Text(
                            "No effect on Adreno (native BCn)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = syncFrame, onCheckedChange = { syncFrame = it })
                    Text(stringResource(R.string.graphics_driver_sync_frame), modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_sync_every_frame }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = disablePresentWait, onCheckedChange = { disablePresentWait = it })
                    Text(stringResource(R.string.graphics_driver_disable_present_wait), modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_disable_present_wait }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fdDevFeatures, onCheckedChange = { fdDevFeatures = it })
                    Text("OneUI / HyperOS Fix", modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_oneui_hyperos_fix }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }

                // --- Turnip GMEM (task #1) — always visible: this is the escape hatch for 710/720/722
                // users on a STOCK driver. Tri-state maps to whether `gmem` joins TU_DEBUG at launch. ---
                Spacer(Modifier.height(12.dp))
                Text(
                    "Turnip GMEM",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                val gmemLabels = listOf("Auto (Adreno 710/720/722)", "Force On", "Force Off")
                val gmemValues = listOf("auto", "on", "off")
                val gmemSel = gmemLabels[gmemValues.indexOf(turnipGmem).coerceAtLeast(0)]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledDropdown("GMEM (tiled rendering)", gmemLabels, gmemSel, { picked ->
                        turnipGmem = gmemValues[gmemLabels.indexOf(picked).coerceAtLeast(0)]
                    }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { helpRes = R.string.help_turnip_gmem }) {
                        Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    "Auto forces GMEM tiled rendering (TU_DEBUG=gmem) only on Adreno 710/720/722; " +
                        "Force On applies it on any GPU; Force Off never applies it. Leave on Auto unless a " +
                        "game misbehaves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // --- Advanced TU_DEBUG tokens (task #2) — collapsed expert section, off by default. ---
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { turnipSectionExpanded = !turnipSectionExpanded }
                ) {
                    Text(
                        (if (turnipSectionExpanded) "▾  " else "▸  ") + "Advanced Turnip (TU_DEBUG)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (turnipSectionExpanded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Expert-only Turnip debug tokens. Off by default — enable only if you know what " +
                            "they do. These are unioned with the GMEM setting above into TU_DEBUG.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = turnipForceCb, onCheckedChange = { turnipForceCb = it })
                        Text("forcecb — force concurrent binning", modifier = Modifier.weight(1f))
                        IconButton(onClick = { helpRes = R.string.help_turnip_concurrent_binning }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = turnipNoCb, onCheckedChange = { turnipNoCb = it })
                        Text("nocb — disable concurrent binning", modifier = Modifier.weight(1f))
                        IconButton(onClick = { helpRes = R.string.help_turnip_concurrent_binning }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
                    // sysmem is the direct opposite of gmem and Turnip lets it defeat gmem, so when
                    // GMEM = Force On the two would contradict. Grey out the pick (gmem always wins the
                    // launch-time merge anyway) so the UI can't express the contradiction.
                    val sysmemBlockedByGmem = turnipGmem == "on"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = turnipSysmem && !sysmemBlockedByGmem,
                            onCheckedChange = { turnipSysmem = it },
                            enabled = !sysmemBlockedByGmem
                        )
                        Text(
                            "sysmem — force sysmem (bypass GMEM)",
                            modifier = Modifier.weight(1f),
                            color = if (sysmemBlockedByGmem) MaterialTheme.colorScheme.onSurfaceVariant
                                    else Color.Unspecified
                        )
                        IconButton(onClick = { helpRes = R.string.help_turnip_sysmem }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
                    if (sysmemBlockedByGmem) {
                        Text(
                            "Disabled — Turnip GMEM = Force On overrides sysmem.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = turnipDeckEmu, onCheckedChange = { turnipDeckEmu = it })
                        Text("deck_emu — advertise as SteamDeck", modifier = Modifier.weight(1f))
                        IconButton(onClick = { helpRes = R.string.help_turnip_deck_emu }) {
                            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        "deck_emu requires a Banners-Turnip driver (ignored on stock Turnip).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // BCn Layer Settings — shown when the wrapper carries a BCn layer as an implicit
                // overlay: bundled wrapper-bcn_layer, or an imported wrapper whose .tzst contains
                // libbcn_layer.so (e.g. "112"). Detected via caps, not the driver name.
                if (showBcnLayerSettings) {
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
                        if (isQualcomm) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "No effect on Adreno (native BCn) — these apply to Mali/non-Qualcomm GPUs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                        // compat_layer DX12 sparse-binding emulation — only for Wrapper + compat + bcn.
                        if (isCompatDriver) {
                            // Engine selector: swap the ICD base from leegao to the GameNative wrapper
                            // for real DX12 support. Primary switch, shown above the sparse opt-in.
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = compatUseGamenative, onCheckedChange = { compatUseGamenative = it })
                                Text(stringResource(R.string.compat_use_gamenative))
                            }
                            Text(
                                stringResource(R.string.compat_use_gamenative_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = bcnCompatSparse, onCheckedChange = { bcnCompatSparse = it })
                                Text(stringResource(R.string.bcn_compat_sparse))
                            }
                            Text(
                                stringResource(R.string.bcn_compat_sparse_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // --- #132 Smart Wrapper Manager, Layer 1: auto-detected settings (imports only) ---
                // One control per env-var name scanned from THIS wrapper's binaries, minus the keys a
                // curated control above already exposes (HANDLED_ENV_KEYS). Values are stored under the
                // raw env key and passed to the wrapper verbatim at launch. Bundled wrappers: not shown.
                if (isImported) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Detected settings (advanced) — from scanning this wrapper",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Values are passed to the wrapper as-is; unknown ones are safe to leave blank.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    if (detectedKeys.isEmpty()) {
                        Text(
                            "No extra settings detected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        detectedKeys.forEach { key ->
                            val def = WrapperSettingsDictionary.defFor(key)
                            val current = detectedValues[key] ?: ""
                            when (def.type) {
                                WrapperSettingsDictionary.Type.TOGGLE -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = current == "1",
                                            onCheckedChange = { detectedValues[key] = if (it) "1" else "0" }
                                        )
                                        Text(def.label)
                                    }
                                }
                                WrapperSettingsDictionary.Type.DROPDOWN -> {
                                    val selected = current.ifEmpty { def.default }.ifEmpty { def.choices.firstOrNull() ?: "" }
                                    LabeledDropdown(def.label, def.choices, selected, { detectedValues[key] = it })
                                }
                                WrapperSettingsDictionary.Type.SLIDER -> {
                                    val fv = current.toFloatOrNull() ?: def.default.toFloatOrNull() ?: def.min
                                    Text("${def.label}: ${fv.toInt()}", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = fv,
                                        onValueChange = { detectedValues[key] = it.toInt().toString() },
                                        valueRange = def.min..(if (def.max > def.min) def.max else def.min + 1f),
                                        steps = if (def.step > 0f && def.max > def.min)
                                            (((def.max - def.min) / def.step).toInt() - 1).coerceAtLeast(0) else 0
                                    )
                                }
                                WrapperSettingsDictionary.Type.TEXT -> {
                                    OutlinedTextField(
                                        value = current,
                                        onValueChange = { detectedValues[key] = it },
                                        singleLine = true,
                                        label = { Text(def.label) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            if (def.hint.isNotBlank()) {
                                Text(
                                    def.hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
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
                    "bcnCompatSparse=${if (bcnCompatSparse) "1" else "0"};" +
                    "compatUseGamenative=${if (compatUseGamenative) "1" else "0"};" +
                    "gpuName=$gpuName" +
                    ";fdDevFeatures=${if (fdDevFeatures) "1" else "0"}" +
                    ";turnipGmem=$turnipGmem" +
                    ";turnipTokens=" + buildList {
                        if (turnipForceCb) add("forcecb")
                        if (turnipNoCb) add("nocb")
                        // Don't persist sysmem when GMEM = Force On overrides it (matches the greyed-out
                        // checkbox); the launch-time merge would strip it anyway.
                        if (turnipSysmem && turnipGmem != "on") add("sysmem")
                        if (turnipDeckEmu) add("deck_emu")
                    }.joinToString(",")
                // #132 Layer 1: append auto-detected wrapper settings under their RAW ENV KEY. Sanitise
                // values so they can't break the ";"/"=" k=v format the config round-trips through.
                val detectedPart = detectedKeys.joinToString("") { key ->
                    val v = (detectedValues[key] ?: "").replace(";", "").replace("=", "")
                    ";$key=$v"
                }
                onConfirm(config + detectedPart)
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

    OutlinedAlertDialog(
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
    relaxDxvkFilter: Boolean = false,
    refreshKey: Int = 0,
    initialConfig: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDownloadDxvk: () -> Unit = {},
    onDownloadVkd3d: () -> Unit = {},
    onDownloadD7vk: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember(initialConfig) { DXVKConfigDialog.parseConfig(initialConfig) }
    val activity = context.findActivity() ?: return
    var isProcessing by remember { mutableStateOf(false) }

    val allDxvkVersions = remember { mutableStateOf(listOf<String>()) }
    val vkd3dVersions   = remember { mutableStateOf(listOf<String>()) }
    // Seeded with the bundled sentinel so the D7VK version dropdown always offers "Bundled (default)"
    // even before the async catalog load lands (or when there are no downloaded d7vk profiles).
    val d7vkVersions    = remember { mutableStateOf(listOf(DXVKConfigDialog.D7VK_BUNDLED)) }
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
            val d7vk = DXVKConfigDialog.loadD7vkVersionList(context, cm)
            val cfgsrc = DXVKConfigDialog.loadVegasConfigSourceList(context)
            withContext(Dispatchers.Main) {
                allDxvkVersions.value = versions
                vkd3dVersions.value = vkd3d
                d7vkVersions.value = d7vk
                configSourceEntries.value = cfgsrc
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
    // D7VK version (only meaningful when DDraw Wrapper == D7VK). Empty/unknown -> the bundled asset.
    var selectedD7vk         by remember(d7vkVersions.value) {
        val stored = config.get("d7vkVersion")
        mutableStateOf(d7vkVersions.value.firstOrNull { it == stored } ?: DXVKConfigDialog.D7VK_BUNDLED)
    }
    var selectedConfigSource by remember(configSourceEntries.value) {
        val stored = config.get("dxvkConfigFile")
        mutableStateOf(configSourceEntries.value.firstOrNull { it == stored } ?: configSourceEntries.value.firstOrNull() ?: "None")
    }
    var asyncEnabled         by remember { mutableStateOf(config.get("async") == "1") }
    var asyncCacheEnabled    by remember { mutableStateOf(config.get("asyncCache") == "1") }

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

    OutlinedAlertDialog(
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
                        filteredDxvk, selectedDxvk, { selectedDxvk = it },
                        modifier = Modifier.weight(1f)
                    )
                    ContentInstallGear(
                        onDownloadFile = onDownloadDxvk
                    )
                    if (isVegas) {
                        IconButton(
                            onClick = {
                                isProcessing = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val cm = ContentsManager(context)
                                            cm.syncContents()
                                            val expectedName = "vegas-$selectedDxvk"
                                            val profile = cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS)
                                                .firstOrNull { it.verName == expectedName }
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
                }
                if (isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
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
                cfg.put("d7vkVersion", selectedD7vk)
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

    OutlinedAlertDialog(
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
    val diagContext = LocalContext.current
    fun bool(k: String, fallbackKey: String, d: String) =
        cfg.getOrDefault(k, cfg.getOrDefault(fallbackKey, d)) == "1"

    // Master HUD on/off — mirrors the in-game drawer's "Show HUD" toggle so the two surfaces keep an
    // identical key set. When off, the overlay stays hidden even while a game window is bound.
    var hudEnabled by remember { mutableStateOf(bool("hudEnabled", "hudEnabled", "1")) }
    // Orientation (vertical/horizontal) is toggled live by tapping the HUD in-game; preserve it.
    val hudMode = remember { cfg.getOrDefault("hudMode", "vertical") }
    // 4-way HUD style: classic | gamehub | gamenative | fusion.
    val styles = listOf("classic", "gamehub", "gamenative", "fusion")
    var hudStyle by remember { mutableStateOf(cfg.getOrDefault("hudStyle", "fusion")) }
    val gameHub = hudStyle == "gamehub"
    val gameNative = hudStyle == "gamenative"
    val fusion = hudStyle == "fusion"
    val rich = gameHub || gameNative || fusion   // opacity + FPS graph + GPU model + color/outline
    // Fusion size mode (Full/Tiles/Pill/Minimal/Mega); also live-cycled by tapping the Fusion HUD in-game.
    val fusionSizes = listOf("full", "tiles", "pill", "minimal", "mega")
    var fusionSize by remember { mutableStateOf(cfg.getOrDefault("hudSize", "pill")) }
    // The chips this Fusion size actually renders (single source of truth in FusionSize) — used below to
    // show only the relevant metric chips for the selected view/size.
    val fusionChips = com.winlator.star.widget.fusionhud.FusionSize.from(fusionSize).supportedChips()
    // GPU model defaults ON for Fusion (its spec), OFF for the others — matching each view's default.
    val gpuModelDefault = if (cfg.getOrDefault("hudStyle", "fusion") == "fusion") "1" else "0"
    // Clock defaults ON for Fusion (subtle corner readout), OFF elsewhere.
    val clockDefault = if (cfg.getOrDefault("hudStyle", "fusion") == "fusion") "1" else "0"

    // Unified metric toggles (emitted under both classic + gamehub key names so either HUD honors them).
    var showFPS      by remember { mutableStateOf(bool("showFPS", "showFPS", "1")) }
    var showGraph    by remember { mutableStateOf(bool("showFPSGraph", "showFPSGraph", "0")) }
    var showCPU      by remember { mutableStateOf(bool("showCPUUsage", "showCPULoad", "1")) }
    var showGPU      by remember { mutableStateOf(bool("showGPULoad", "showGPULoad", "1")) }
    var showRAM      by remember { mutableStateOf(bool("showRAM", "showRAM", "1")) }
    var showPower    by remember { mutableStateOf(bool("showPower", "showPower", "1")) }
    var showTemp     by remember { mutableStateOf(bool("showTemp", "showBatteryTemp", "1")) }
    var showEngine   by remember { mutableStateOf(bool("showEngine", "showRenderer", "1")) }
    var showGpuModel by remember { mutableStateOf(bool("showGpuModel", "showGpuModel", gpuModelDefault)) }
    var dualBattery  by remember { mutableStateOf(bool("hudDualBattery", "hudDualBattery", "0")) }
    // GameNative-only extra metrics (absent = off is the intended default).
    var showGpuTemp  by remember { mutableStateOf(bool("showGpuTemp", "showGpuTemp", "0")) }
    var showBattery  by remember { mutableStateOf(bool("showBattery", "showBattery", "0")) }
    var showRuntime  by remember { mutableStateOf(bool("showRuntime", "showRuntime", "0")) }
    var showClock    by remember { mutableStateOf(bool("showClock", "showClock", clockDefault)) }
    var showCpuGraph by remember { mutableStateOf(bool("showCPUGraph", "showCPUGraph", "0")) }
    var showGpuGraph by remember { mutableStateOf(bool("showGPUGraph", "showGPUGraph", "0")) }
    // Fusion extra metrics + global lock (defaults match Container.DEFAULT_FPS_COUNTER_CONFIG).
    var showVram     by remember { mutableStateOf(bool("showVram", "showVram", "1")) }
    var showLow001   by remember { mutableStateOf(bool("showLow001", "showLow001", "1")) }
    var fpsDecimal   by remember { mutableStateOf(bool("fpsDecimal", "fpsDecimal", "1")) }
    var hudLocked    by remember { mutableStateOf(bool("hudLocked", "hudLocked", "0")) }
    // Fusion Mega-only metrics (defaults match Container.DEFAULT_FPS_COUNTER_CONFIG).
    var showPerCore  by remember { mutableStateOf(bool("showPerCore", "showPerCore", "1")) }
    var showSwap     by remember { mutableStateOf(bool("showSwap", "showSwap", "1")) }
    var showNet      by remember { mutableStateOf(bool("showNet", "showNet", "1")) }
    var showResolution by remember { mutableStateOf(bool("showResolution", "showResolution", "1")) }
    var showProton   by remember { mutableStateOf(bool("showProton", "showProton", "1")) }
    var showWrapper  by remember { mutableStateOf(bool("showWrapper", "showWrapper", "1")) }
    var showDxVer    by remember { mutableStateOf(bool("showDxVer", "showDxVer", "1")) }
    var showSession  by remember { mutableStateOf(bool("showSession", "showSession", "1")) }
    // Temperature display — same keys as the in-game drawer pane, so the two stay interchangeable.
    var tempUnitF  by remember { mutableStateOf(cfg.getOrDefault("tempUnit", "c").equals("f", true)) }
    var tempBands  by remember { mutableStateOf(cfg.getOrDefault("tempBands", "1") != "0") }
    var tempAuto   by remember { mutableStateOf(cfg.getOrDefault("tempAuto", "1") != "0") }
    var tempRedCpu by remember { mutableStateOf(cfg.getOrDefault("tempRedCpu", "90").toIntOrNull() ?: 90) }
    var tempRedGpu by remember { mutableStateOf(cfg.getOrDefault("tempRedGpu", "90").toIntOrNull() ?: 90) }
    var tempRedBat by remember { mutableStateOf(cfg.getOrDefault("tempRedBat", "48").toIntOrNull() ?: 48) }

    var hudScale by remember { mutableStateOf(cfg.getOrDefault("hudScale", Container.DEFAULT_HUD_SCALE.toString()).toIntOrNull() ?: Container.DEFAULT_HUD_SCALE) }
    var hudOpacity by remember { mutableStateOf(cfg.getOrDefault("hudOpacity", "80").toIntOrNull() ?: 80) }
    var hudTransparency by remember { mutableStateOf(cfg.getOrDefault("hudTransparency", "0").toIntOrNull() ?: 0) }

    val skins = listOf("classic", "neon", "mono")
    val colors = listOf("soft", "mid", "vivid")
    var skin by remember { mutableStateOf(cfg.getOrDefault("hudSkin", "classic")) }
    var color by remember { mutableStateOf(cfg.getOrDefault("hudColor", "mid")) }
    // hudOutline is a 0..100 intensity (legacy off/soft/strong strings map via parseHudOutline).
    var outlineValue by remember { mutableStateOf(parseHudOutline(cfg.getOrDefault("hudOutline", "40"))) }
    var outlineAccent by remember { mutableStateOf(cfg.getOrDefault("hudOutlineAccent", "1") == "1") }

    fun i(v: Boolean) = if (v) "1" else "0"
    fun buildConfig(): String = listOf(
        "hudStyle=$hudStyle",
        "hudEnabled=${i(hudEnabled)}",
        "hudSize=$fusionSize",
        "hudLocked=${i(hudLocked)}",
        "showVram=${i(showVram)}",
        "showLow001=${i(showLow001)}",
        "fpsDecimal=${i(fpsDecimal)}",
        "showPerCore=${i(showPerCore)}",
        "showSwap=${i(showSwap)}",
        "showNet=${i(showNet)}",
        "showResolution=${i(showResolution)}",
        "showProton=${i(showProton)}",
        "showWrapper=${i(showWrapper)}",
        "showDxVer=${i(showDxVer)}",
        "showSession=${i(showSession)}",
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
        "showGpuTemp=${i(showGpuTemp)}",
        "showBattery=${i(showBattery)}",
        "showRuntime=${i(showRuntime)}",
        "showClock=${i(showClock)}",
        "showCPUGraph=${i(showCpuGraph)}",
        "showGPUGraph=${i(showGpuGraph)}",
        "tempUnit=${if (tempUnitF) "f" else "c"}",
        "tempBands=${i(tempBands)}",
        "tempAuto=${i(tempAuto)}",
        "tempRedCpu=$tempRedCpu",
        "tempRedGpu=$tempRedGpu",
        "tempRedBat=$tempRedBat",
        "hudSkin=$skin",
        "hudColor=$color",
        "hudOutline=$outlineValue",
        "hudOutlineAccent=${if (outlineAccent) 1 else 0}",
        "hudScale=$hudScale",
        "hudOpacity=$hudOpacity",
        "hudTransparency=$hudTransparency"
    ).joinToString(",")

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("FPS Counter Settings") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.7f).dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Master toggle (parity with the in-game drawer): hides the whole overlay when off.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = hudEnabled,
                        onCheckedChange = { hudEnabled = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Show HUD")
                }
                Spacer(Modifier.height(12.dp))
                HudThreeStop(
                    "HUD style",
                    listOf("Classic", "GameHub", "GameNative", "Fusion"),
                    styles.indexOf(hudStyle).coerceAtLeast(0)
                ) { hudStyle = styles[it] }
                Text(
                    when (hudStyle) {
                        "gamehub" -> "Rich overlay: skins, colored fields, live FPS graph."
                        "gamenative" -> "GameNative-style overlay: compact pill or stacked list with live graphs."
                        "fusion" -> "Fusion overlay: one color-coded look in 5 sizes (Full/Tiles/Pill/Minimal/Mega) with percentile lows, VRAM + a Mega everything-view."
                        else -> "Classic Bannerlator overlay."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (fusion) {
                    Spacer(Modifier.height(8.dp))
                    HudThreeStop(
                        "Size",
                        listOf("Full", "Tiles", "Pill", "Minimal", "Mega"),
                        fusionSizes.indexOf(fusionSize).coerceAtLeast(0)
                    ) { fusionSize = fusionSizes[it] }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tip: tap the HUD in-game to switch vertical/horizontal layout.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))

                // Compact multi-select metric chips (filled = on) in a wrap layout,
                // so ~13 metrics fit in a few rows instead of stacked Switch rows.
                Text("Metrics", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                // Build the currently-VISIBLE chips first (respecting per-style gating), then chunk
                // into an aligned 3-wide grid — so hidden chips never leave holes. Each stays an
                // independent toggle writing the same state as before.
                // For Fusion, show only the chips the SELECTED SIZE draws (single source of truth in
                // FusionSize.supportedChips()). For the other styles, keep the existing style gating.
                // Hiding a chip is UI-only — buildConfig() still emits every key (strip-invariant).
                fun show(label: String, styleOk: Boolean): Boolean = if (fusion) label in fusionChips else styleOk
                val metricChips = buildList<Triple<String, Boolean, () -> Unit>> {
                    if (show("FPS", true)) add(Triple("FPS", showFPS) { showFPS = !showFPS })
                    if (show("FPS graph", rich)) add(Triple("FPS graph", showGraph) { showGraph = !showGraph })
                    if (show("CPU", true)) add(Triple("CPU", showCPU) { showCPU = !showCPU })
                    if (!fusion && gameNative) add(Triple("CPU graph", showCpuGraph) { showCpuGraph = !showCpuGraph })
                    if (show("GPU", true)) add(Triple("GPU", showGPU) { showGPU = !showGPU })
                    if (!fusion && gameNative) add(Triple("GPU graph", showGpuGraph) { showGpuGraph = !showGpuGraph })
                    if (show("VRAM", false)) add(Triple("VRAM", showVram) { showVram = !showVram })
                    if (show("RAM", true)) add(Triple("RAM", showRAM) { showRAM = !showRAM })
                    if (show("Power", true)) add(Triple("Power", showPower) { showPower = !showPower })
                    if (show("Temp", true)) add(Triple("Temp", showTemp) { showTemp = !showTemp })
                    if (show("GPU temp", gameNative)) add(Triple("GPU temp", showGpuTemp) { showGpuTemp = !showGpuTemp })
                    if (show("Battery", gameNative)) add(Triple("Battery", showBattery) { showBattery = !showBattery })
                    if (!fusion && gameNative) add(Triple("Runtime", showRuntime) { showRuntime = !showRuntime })
                    if (show("0.01% low", false)) add(Triple("0.01% low", showLow001) { showLow001 = !showLow001 })
                    if (show("FPS .1", false)) add(Triple("FPS .1", fpsDecimal) { fpsDecimal = !fpsDecimal })
                    // Fusion Mega-only metrics
                    if (show("Per-core", false)) add(Triple("Per-core", showPerCore) { showPerCore = !showPerCore })
                    if (show("Swap", false)) add(Triple("Swap", showSwap) { showSwap = !showSwap })
                    if (show("Network", false)) add(Triple("Network", showNet) { showNet = !showNet })
                    if (show("Resolution", false)) add(Triple("Resolution", showResolution) { showResolution = !showResolution })
                    if (show("Proton", false)) add(Triple("Proton", showProton) { showProton = !showProton })
                    if (show("Wrapper", false)) add(Triple("Wrapper", showWrapper) { showWrapper = !showWrapper })
                    if (show("DX ver", false)) add(Triple("DX ver", showDxVer) { showDxVer = !showDxVer })
                    if (show("Session", false)) add(Triple("Session", showSession) { showSession = !showSession })
                    // Clock: gamenative's own chip, and every Fusion size (subtle corner readout)
                    if (show("Clock", gameNative)) add(Triple("Clock", showClock) { showClock = !showClock })
                    if (show("Engine", true)) add(Triple("Engine", showEngine) { showEngine = !showEngine })
                    if (show("GPU model", rich)) add(Triple("GPU model", showGpuModel) { showGpuModel = !showGpuModel })
                    if (!fusion && gameHub) add(Triple("Dual battery", dualBattery) { dualBattery = !dualBattery })
                    // Global appearance control, shown for every style/size.
                    add(Triple("Lock in place", hudLocked) { hudLocked = !hudLocked })
                }
                ModeChipGrid(metricChips, perRow = 3)

                // ── Temperature display ── only meaningful when a temperature is on screen.
                if (showTemp || ((gameNative || fusion) && (showGpuTemp || showBattery))) {
                    Spacer(Modifier.height(12.dp))
                    HudThreeStop("Temp unit", listOf("\u00B0C", "\u00B0F"), if (tempUnitF) 1 else 0) {
                        tempUnitF = it == 1
                    }
                    Spacer(Modifier.height(4.dp))
                    // Danger bands as one 3-way rather than two toggles: "bands off but auto on"
                    // isn't a distinct state worth exposing.
                    val bandMode = if (!tempBands) 0 else if (tempAuto) 1 else 2
                    HudThreeStop("Danger colors", listOf("Off", "Auto", "Manual"), bandMode) {
                        tempBands = it != 0
                        tempAuto = it != 2
                    }
                    Text(
                        when (bandMode) {
                            0 -> "Temperatures use their normal color."
                            1 -> "Thresholds read from your device's own thermal trip points, falling back to safe defaults."
                            else -> "Set the red point per sensor; amber sits just below it."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (bandMode == 2) {
                        // Red point only; amber is derived. Always \u00B0C — thresholds never convert.
                        Spacer(Modifier.height(4.dp))
                        Text("CPU red at: $tempRedCpu\u00B0C", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = tempRedCpu.toFloat(),
                            onValueChange = { tempRedCpu = it.toInt() },
                            valueRange = 50f..110f, steps = 59
                        )
                        if ((gameNative || fusion) && showGpuTemp) {
                            Text("GPU red at: $tempRedGpu\u00B0C", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = tempRedGpu.toFloat(),
                                onValueChange = { tempRedGpu = it.toInt() },
                                valueRange = 50f..110f, steps = 59
                            )
                        }
                        Text("Battery red at: $tempRedBat\u00B0C", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = tempRedBat.toFloat(),
                            onValueChange = { tempRedBat = it.toInt() },
                            valueRange = 35f..60f, steps = 24
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("HUD Scale: $hudScale%", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = hudScale.toFloat(),
                    onValueChange = { hudScale = it.toInt().coerceAtLeast(50) },
                    valueRange = 50f..150f, steps = 99
                )

                if (rich) {
                    Spacer(Modifier.height(4.dp))
                    Text("HUD Opacity: $hudOpacity%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = hudOpacity.toFloat(),
                        onValueChange = { hudOpacity = it.toInt() },
                        valueRange = 0f..100f, steps = 99
                    )
                    Spacer(Modifier.height(8.dp))
                    if (gameHub) {
                        HudThreeStop("HUD skin", listOf("Classic", "Neon", "Mono"), skins.indexOf(skin)) { skin = skins[it] }
                    }
                    HudThreeStop("HUD color", listOf("Soft", "Mid", "Vivid"), colors.indexOf(color)) { color = colors[it] }
                    Spacer(Modifier.height(8.dp))
                    Text("HUD outline: $outlineValue", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = outlineValue.toFloat(),
                        onValueChange = { outlineValue = it.toInt() },
                        valueRange = 0f..100f, steps = 99
                    )
                    HudThreeStop("Outline color", listOf("Gray", "Accent"), if (outlineAccent) 1 else 0) { outlineAccent = it == 1 }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text("HUD Transparency: $hudTransparency", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = hudTransparency.toFloat(),
                        onValueChange = { hudTransparency = it.toInt() },
                        valueRange = 0f..50f, steps = 49
                    )
                }

                // General HUD action (not gated to a style): dump every sensor path + value to a
                // shareable text file so a device owner can report which nodes their SoC exposes.
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { exportHudDiagnostics(diagContext) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export HUD diagnostics")
                }
                Text(
                    "Saves a sensor report (CPU/GPU/temp/VRAM…) straight to your Downloads folder.",
                    style = MaterialTheme.typography.bodySmall
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

// Multi-select metric grid styled exactly like the in-game drawer's FullscreenModeButtons: each item
// toggles independently, but shares the box style (accent fill + bold black text ON; black bg +
// dimmed-accent 1dp border + accent medium text OFF) and the aligned equal-width grid (weight(1f),
// short rows padded with Spacer so widths stay equal). Callers build the VISIBLE list first, then
// this chunks per row so per-style gating never leaves holes. There's no LocalAccentDim in this
// screen, so we derive the dim border from a 40%-alpha primary — reads the same as accentDim.
@Composable
private fun ModeChipGrid(items: List<Triple<String, Boolean, () -> Unit>>, perRow: Int) {
    val accent = MaterialTheme.colorScheme.primary
    val accentDim = accent.copy(alpha = 0.4f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { (label, isOn, onTap) ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isOn) accent else Color.Black)
                            .border(
                                width = 1.dp,
                                color = if (isOn) accent else accentDim,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onTap() }
                            .padding(vertical = 9.dp)
                    ) {
                        Text(
                            label,
                            color = if (isOn) Color.Black else accent,
                            fontSize = 12.sp,
                            fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
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


