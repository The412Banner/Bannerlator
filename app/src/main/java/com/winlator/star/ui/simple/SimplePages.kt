package com.winlator.star.ui.simple

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.winlator.star.store.SaveManagerScreen
import com.winlator.star.ui.HelpSupportDialog
import com.winlator.star.ui.Screen
import com.winlator.star.ui.deck.DeckSetup
import com.winlator.star.ui.screens.FileManagerScreen
import com.winlator.star.ui.screens.LogManagerScreen
import com.winlator.star.ControlsEditorActivity
import com.winlator.star.ExternalControllerBindingsActivity
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.XrActivity
import com.winlator.star.container.Container
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.GameSaveBackup
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.ExternalController
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.ui.controllertest.SettingsControllerTestDialog
import com.winlator.star.ui.deck.DeckRoutes
import com.winlator.star.ui.deck.settings.DeckSettingsRoutes
import com.winlator.star.ui.screens.ContainersViewModel
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.adrenodownload.DriverSources
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverEntry
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverRepository
import com.winlator.star.ui.screens.contents.ContentsHubViewModel
import com.winlator.star.ui.screens.contents.ContentsInstaller
import com.winlator.star.ui.screens.contents.ContentsTypes
import com.winlator.star.ui.screens.contents.SetAsDefault
import com.winlator.star.ui.screens.contents.SetAsDefaultSheet
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.core.AppOrientation
import com.winlator.star.ui.components.ColorPicker
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.CUSTOM_PRESET_INDEX
import com.winlator.star.ui.theme.themePresets
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The Simple style's own page routes, registered by the Deck shell next to the Deck ones. */
internal object SimpleRoutes {
    const val TOOLS = "simple_tools"
    const val FILES = "simple_files"
    const val SAVES = "simple_saves"
    const val LOGS = "simple_logs"
    const val CONTAINERS = "simple_containers"
    const val COMPONENTS = "simple_components"
    const val COMPONENT_SOURCE = "simple_component_source?i={i}"
    const val DRIVERS = "simple_drivers"
    const val DRIVER_SOURCE = "simple_driver_source?i={i}"
    const val CONTROLS = "simple_controls"
    const val APPEARANCE = "simple_appearance"

    fun componentSource(i: Int) = "simple_component_source?i=$i"
    fun driverSource(i: Int) = "simple_driver_source?i=$i"
}

/** Calls [action] each time this page is shown again (after an editor, an activity or the file picker). */
@Composable
private fun OnResume(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) action() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

private fun toast(ctx: android.content.Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

/** A name to type (a new controls profile, a rename). */
@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
    )
}

// ───────────────────────────── Tools ─────────────────────────────

/** Tools: the file manager, save backups, logs, wrappers and drivers, and the account, About and help. */
@Composable
internal fun SimpleToolsPage(onNavigate: (String) -> Unit, onBack: () -> Unit, onMyAccount: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    var showHelp by remember { mutableStateOf(false) }
    SimpleMenuPage(
        overline = "Manage",
        title = "Tools",
        sections = listOf(
            MenuSection(
                key = "tools",
                title = "Tools",
                icon = Icons.Filled.Build,
                entries = listOf(
                    MenuEntry("files", "File manager", "Internal storage, SD card and every container's drives", icon = Icons.Filled.FolderOpen, onA = { onNavigate(SimpleRoutes.FILES) }),
                    MenuEntry("saves", "Save backups", "Back up, restore and sync game saves", icon = Icons.Filled.Save, onA = { onNavigate(SimpleRoutes.SAVES) }),
                    MenuEntry("logs", "Game logs", "What each game printed while it ran", icon = Icons.Filled.Description, onA = { onNavigate(SimpleRoutes.LOGS) }),
                    MenuEntry("wrappers", "Wrappers", "Graphics wrappers", icon = Icons.Filled.Layers, onA = { onNavigate(Screen.Wrappers.route) }),
                    MenuEntry("drivers", "GPU drivers", "Turnip and Qualcomm drivers", icon = Icons.Filled.Memory, onA = { onNavigate(SimpleRoutes.DRIVERS) }),
                ),
            ),
            MenuSection(
                key = "help",
                title = "Help",
                icon = Icons.Filled.Info,
                entries = listOf(
                    MenuEntry("account", "My account", "Sign in to manage your shared configs", icon = Icons.Filled.AccountCircle, onA = onMyAccount),
                    MenuEntry("about", "About", "Version, updates and credits", icon = Icons.Filled.Info, onA = onAbout),
                    MenuEntry("help", "Help and Support", "GitHub, issue tracker and Discord", icon = Icons.Filled.HelpOutline, onA = { showHelp = true }),
                    MenuEntry("setup", "Run setup again", "Permissions, theme and stores", icon = Icons.Filled.RestartAlt, onA = { DeckSetup.requestRerun() }),
                ),
            ),
        ),
        onBack = onBack,
    )
    if (showHelp) {
        HelpSupportDialog(
            onDismiss = { showHelp = false },
            onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
        )
    }
}

/** The Simple pages, registered in the Deck shell's nav graph. */
internal fun NavGraphBuilder.simpleRoutes(open: (String) -> Unit, back: () -> Unit, onMyAccount: () -> Unit, onAbout: () -> Unit) {
    composable(SimpleRoutes.TOOLS) { SimpleToolsPage(onNavigate = open, onBack = back, onMyAccount = onMyAccount, onAbout = onAbout) }
    composable(SimpleRoutes.FILES) { FileManagerScreen() }
    composable(SimpleRoutes.SAVES) { SaveManagerScreen(onBack = back) }
    composable(SimpleRoutes.LOGS) { LogManagerScreen(onClose = back) }
    composable(SimpleRoutes.CONTAINERS) { SimpleContainersPage(onNavigate = open, onBack = back) }
    composable(SimpleRoutes.COMPONENTS) { SimpleComponentsPage(onNavigate = open, onBack = back) }
    composable(SimpleRoutes.COMPONENT_SOURCE, arguments = listOf(navArgument("i") { type = NavType.IntType; defaultValue = 0 })) { e ->
        SimpleComponentSourcePage(index = e.arguments?.getInt("i") ?: 0, onBack = back)
    }
    composable(SimpleRoutes.DRIVERS) { SimpleDriversPage(onNavigate = open, onBack = back) }
    composable(SimpleRoutes.DRIVER_SOURCE, arguments = listOf(navArgument("i") { type = NavType.IntType; defaultValue = 0 })) { e ->
        SimpleDriverSourcePage(index = e.arguments?.getInt("i") ?: 0, onBack = back)
    }
    composable(SimpleRoutes.CONTROLS) { SimpleControlsPage(onBack = back) }
    composable(SimpleRoutes.APPEARANCE) { SimpleAppearancePage(onBack = back) }
}

// ───────────────────────────── Appearance ─────────────────────────────

/** Appearance: interface style, Simple layout, theme, accent colour, orientation and sizes, on the same AppThemeState as the Appearance screen. */
@Composable
internal fun SimpleAppearancePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val uiStyle by AppThemeState.uiStyle.collectAsState()
    val xmbHome by AppThemeState.deckXmbHome.collectAsState()
    val simpleHome by AppThemeState.simpleHome.collectAsState()
    val layout by AppThemeState.simpleLayout.collectAsState()
    val preset by AppThemeState.presetIndex.collectAsState()
    val customAccent by AppThemeState.customAccent.collectAsState()
    val uiScale by AppThemeState.uiScale.collectAsState()
    val fontScale by AppThemeState.fontScale.collectAsState()
    var orientation by remember { mutableStateOf(AppOrientation.mode(context)) }
    var accentOpen by remember { mutableStateOf(false) }
    fun mark(label: String, on: Boolean) = if (on) "$label  ✓" else label

    val style = when {
        uiStyle != AppThemeState.UI_STYLE_DECK -> "Classic"
        xmbHome -> "XMB"
        simpleHome -> "Simple"
        else -> "Deck"
    }
    val styles = listOf(
        MenuAction(mark("Simple", style == "Simple"), { AppThemeState.setDeckXmbHome(false); AppThemeState.setSimpleHome(true); AppThemeState.setUiStyle(AppThemeState.UI_STYLE_DECK) }),
        MenuAction(mark("Deck", style == "Deck"), { AppThemeState.setDeckXmbHome(false); AppThemeState.setSimpleHome(false); AppThemeState.setUiStyle(AppThemeState.UI_STYLE_DECK) }),
        MenuAction(mark("XMB", style == "XMB"), { AppThemeState.setDeckXmbHome(true); AppThemeState.setUiStyle(AppThemeState.UI_STYLE_DECK) }),
        MenuAction(mark("Classic", style == "Classic"), { AppThemeState.setUiStyle(AppThemeState.UI_STYLE_CLASSIC) }),
    )
    val layouts = listOf(AppThemeState.SIMPLE_LAYOUT_SHELF to "Shelf", AppThemeState.SIMPLE_LAYOUT_RAIL to "Rail", AppThemeState.SIMPLE_LAYOUT_FOCUS to "Focus")
    val orientations = listOf(AppOrientation.AUTO to "Auto", AppOrientation.PORTRAIT to "Portrait", AppOrientation.LANDSCAPE to "Landscape")
    val scales = listOf(0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f)
    fun pct(f: Float) = "${(f * 100).roundToInt()}%"

    val look = listOf(
        MenuEntry("style", "Interface style", "Simple, Deck, XMB or Classic", trailing = style, icon = Icons.Filled.Dashboard, actions = styles),
        MenuEntry(
            "layout", "Simple layout", "Shelf, Rail or Focus", trailing = layouts.firstOrNull { it.first == layout }?.second, icon = Icons.Filled.ViewQuilt,
            actions = layouts.map { (k, l) -> MenuAction(mark(l, k == layout), { AppThemeState.setSimpleLayout(k) }) },
        ),
        MenuEntry(
            "orientation", "Orientation", "The app's own screens; games keep theirs", trailing = orientations.firstOrNull { it.first == orientation }?.second,
            icon = Icons.Filled.ScreenRotation,
            actions = orientations.map { (m, l) ->
                MenuAction(mark(l, m == orientation), {
                    orientation = m
                    AppOrientation.setMode(context, m)
                    (context as? Activity)?.let { AppOrientation.apply(it) }
                })
            },
        ),
        MenuEntry("ui", "Interface size", trailing = pct(uiScale), icon = Icons.Filled.ZoomIn,
            actions = scales.map { f -> MenuAction(mark(pct(f), (f * 100).roundToInt() == (uiScale * 100).roundToInt()), { AppThemeState.setUiScale(f) }) }),
        MenuEntry("font", "Text size", trailing = pct(fontScale), icon = Icons.Filled.TextFields,
            actions = scales.map { f -> MenuAction(mark(pct(f), (f * 100).roundToInt() == (fontScale * 100).roundToInt()), { AppThemeState.setFontScale(f) }) }),
    )
    val themes = themePresets.mapIndexed { i, t ->
        val custom = i == CUSTOM_PRESET_INDEX
        MenuEntry(
            key = "t$i",
            title = t.name,
            subtitle = if (custom) "Your own accent colour · X to pick it" else null,
            swatch = if (custom) customAccent else t.primary,
            current = i == preset,
            onA = { AppThemeState.setPreset(i) },
            actions = if (custom) listOf(MenuAction("Pick the colour", { accentOpen = true })) else emptyList(),
        )
    }
    SimpleMenuPage(
        overline = "Settings",
        title = "Appearance",
        sections = listOf(
            MenuSection("look", "Look", Icons.Filled.Dashboard, look),
            MenuSection("theme", "Theme", Icons.Filled.Palette, themes),
        ),
        onBack = onBack,
    )
    if (accentOpen) {
        OutlinedAlertDialog(
            onDismissRequest = { accentOpen = false },
            confirmButton = { TextButton(onClick = { accentOpen = false }) { Text("Done") } },
            title = { Text("Accent colour") },
            text = { ColorPicker(initialColor = customAccent, onColorChanged = { AppThemeState.setCustomAccent(it) }) },
        )
    }
}

// ───────────────────────────── Containers ─────────────────────────────

/** Containers: each container (A = its settings, X = start its desktop, duplicate, back up saves, export, remove), backups to import, and the defaults. */
@Composable
internal fun SimpleContainersPage(onNavigate: (String) -> Unit, onBack: () -> Unit, vm: ContainersViewModel = viewModel()) {
    val context = LocalContext.current
    val containers by vm.containers.collectAsState()
    val message by vm.message.collectAsState()
    var backups by remember { mutableStateOf(emptyList<java.io.File>()) }
    OnResume { vm.refresh(); backups = runCatching { vm.availableBackups() }.getOrDefault(emptyList()) }
    LaunchedEffect(message) { message?.let { toast(context, it); vm.messageShown() } }

    fun startDesktop(c: Container) {
        val activity = context as? Activity ?: return
        if (!XrActivity.isEnabled(context)) {
            val intent = Intent(context, XServerDisplayActivity::class.java).putExtra("container_id", c.id)
            if (c.displayBackend == Container.DISPLAY_BACKEND_WAYLAND) intent.putExtra("wayland_mode", true)
            activity.startActivity(intent)
        } else XrActivity.openIntent(activity, c.id, null)
    }

    val gameCounts = remember(containers) { containers.associate { it.id to runCatching { vm.shortcutsFor(it).size }.getOrDefault(0) } }
    val entries = containers.map { c ->
        val games = gameCounts[c.id] ?: 0
        MenuEntry(
            key = "c${c.id}",
            title = c.name,
            subtitle = listOf(c.wineVersion, "$games ${if (games == 1) "game" else "games"}").joinToString(" · "),
            trailing = if (c.displayBackend == Container.DISPLAY_BACKEND_WAYLAND) "Wayland" else "X11",
            icon = Icons.Filled.Inventory2,
            onA = { onNavigate(DeckSettingsRoutes.container(c.id)) },
            actions = listOf(
                MenuAction("Settings", { onNavigate(DeckSettingsRoutes.container(c.id)) }),
                MenuAction("Start its Windows desktop", { startDesktop(c) }),
                MenuAction("Duplicate", { vm.duplicate(c) { toast(context, "Duplicated ${c.name}") } }, confirm = "Make a copy of ${c.name}, with its games and settings?"),
                MenuAction("Back up all saves", {
                    GameSaveBackup.backup(context, c) { r -> (context as? Activity)?.runOnUiThread { toast(context, if (r.ok) "Saves backed up (${r.fileCount} files)" else "Backup failed: ${r.error ?: "unknown"}") } }
                }),
                MenuAction("Export", { vm.exportContainer(c) { path -> (context as? Activity)?.runOnUiThread { toast(context, if (path != null) "Exported to $path" else "Export failed") } } }),
                MenuAction("Remove", { vm.remove(c, context) { toast(context, "Removed ${c.name}") } }, danger = true, confirm = "Remove ${c.name} and everything in it? Its games' shortcuts go too."),
            ),
        )
    }
    SimpleMenuPage(
        overline = "Manage",
        title = "Containers",
        sections = listOf(
            MenuSection(
                "containers", "Containers", Icons.Filled.Inventory2, entries,
                empty = "No containers yet.",
                primary = MenuAction("New container", {
                    if (com.winlator.star.xenvironment.ImageFs.find(context).isValid()) onNavigate(DeckSettingsRoutes.container(DeckSettingsRoutes.NEW_CONTAINER_ID))
                    else toast(context, "The Windows system files are not installed yet.")
                }),
            ),
            MenuSection(
                "import", "Backups", Icons.Filled.Restore,
                backups.map { f ->
                    MenuEntry(
                        "b${f.absolutePath}", f.name, f.parent, icon = Icons.Filled.Restore,
                        actions = listOf(MenuAction("Import this container", { vm.importContainer(f) { toast(context, "Imported ${f.name}") } }, confirm = "Import ${f.name} as a new container?")),
                    )
                },
                empty = "No container backups in Downloads/Winlator/Backups/Containers.",
            ),
            MenuSection(
                "defaults", "Defaults", Icons.Filled.Tune,
                listOf(MenuEntry("defaults", "New container defaults", "What every new container starts with", icon = Icons.Filled.Tune, onA = { onNavigate(DeckSettingsRoutes.DEFAULTS) })),
            ),
        ),
        onBack = onBack,
    )
}

// ───────────────────────────── Components ─────────────────────────────

private val TYPE_LABELS = mapOf(
    "CONTENT_TYPE_WINE" to "Wine", "CONTENT_TYPE_PROTON" to "Proton", "CONTENT_TYPE_DXVK" to "DXVK", "CONTENT_TYPE_D7VK" to "D7VK",
    "CONTENT_TYPE_VKD3D" to "VKD3D", "CONTENT_TYPE_BOX64" to "Box64", "CONTENT_TYPE_WOWBOX64" to "WOWBox64",
    "CONTENT_TYPE_FEXCORE" to "FEXCore", "CONTENT_TYPE_BOX64EC" to "Box64EC", "CONTENT_TYPE_VEGAS" to "VEGAS",
)

/** The file name an Android content URI points at, for an install from a file. */
private fun displayNameOf(ctx: android.content.Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "content"

/** Components: what is installed (set as default, remove), where to get more, and the files saved for later. */
@Composable
internal fun SimpleComponentsPage(onNavigate: (String) -> Unit, onBack: () -> Unit, vm: ContentsHubViewModel = viewModel()) {
    val context = LocalContext.current
    val sources by vm.sources.collectAsState()
    val saved by vm.savedFolders.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    OnResume { vm.refreshStatus(); vm.refreshFolders(); tick++ }
    var defaultItem by remember { mutableStateOf<SetAsDefault.Item?>(null) }
    val cm = remember(tick) { runCatching { ContentsManager(context).apply { syncContents() } }.getOrNull() }
    val installed = remember(cm) {
        runCatching {
            ContentProfile.ContentType.values().flatMap { t -> cm?.getProfiles(t).orEmpty().filter { it.remoteUrl == null }.map { t to it } }
        }.getOrDefault(emptyList())
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: InAppFilePicker.pickedUri(result.data) ?: return@rememberLauncherForActivityResult
        val name = displayNameOf(context, uri)
        val lower = name.lowercase()
        val type = if (lower.endsWith(".wcp") || lower.endsWith(".tzst")) "" else ContentsTypes.GPU_DRIVERS
        toast(context, "Installing $name…")
        ContentsInstaller.installFromFile(context, type, name, uri) { ok ->
            (context as? Activity)?.runOnUiThread { toast(context, if (ok) "Installed $name" else "Could not install $name"); vm.refreshStatus(); tick++ }
        }
    }
    val installedEntries = installed.map { (t, p) ->
        val label = TYPE_LABELS[t.name] ?: t.name
        MenuEntry(
            key = "i${t.name}-${p.verName}-${p.verCode}",
            title = "$label ${p.verName}",
            subtitle = p.desc?.takeIf { it.isNotBlank() },
            trailing = label,
            icon = Icons.Filled.Extension,
            actions = buildList {
                cm?.let { m -> runCatching { SetAsDefault.componentItem(context, m, p) }.getOrNull() }?.let { item -> add(MenuAction("Set as default…", { defaultItem = item })) }
                add(MenuAction("Remove", {
                    runCatching { cm?.removeContent(p); cm?.syncContents() }
                    vm.refreshStatus(); tick++
                    toast(context, "Removed $label ${p.verName}")
                }, danger = true, confirm = "Remove $label ${p.verName}? Containers that use it fall back to their other choice."))
            },
        )
    }
    val sourceEntries = sources.mapIndexed { i, s ->
        MenuEntry("s$i-${s.name}", s.name, "Browse and download", icon = Icons.Filled.CloudDownload, onA = { onNavigate(SimpleRoutes.componentSource(i)) })
    }
    val savedEntries = saved.values.flatten().map { f ->
        MenuEntry(
            key = "f${f.uri}",
            title = f.name,
            subtitle = f.type,
            trailing = "%.1f MB".format(f.sizeBytes / 1_048_576.0),
            icon = Icons.Filled.Save,
            onA = {
                toast(context, "Installing ${f.name}…")
                ContentsInstaller.installFromFile(context, f.type, f.name, f.uri) { ok ->
                    (context as? Activity)?.runOnUiThread { if (ok) vm.markInstalledFromSaved(f); toast(context, if (ok) "Installed ${f.name}" else "Could not install ${f.name}"); tick++ }
                }
            },
            actions = listOf(MenuAction("Delete the saved file", { vm.deleteSaved(f) }, danger = true, confirm = "Delete ${f.name}?")),
        )
    }
    val fromFile = MenuAction("Install from a file", { picker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.WCP, "Select content pack")) })
    SimpleMenuPage(
        overline = "Manage",
        title = "Components",
        sections = listOf(
            MenuSection("installed", "Installed", Icons.Filled.Extension, installedEntries, empty = "Only the built-in components so far.", primary = fromFile),
            MenuSection("get", "Get more", Icons.Filled.CloudDownload, sourceEntries, empty = "No download sources.", primary = fromFile),
            MenuSection("saved", "Saved files", Icons.Filled.Save, savedEntries, empty = "No saved component files."),
            MenuSection(
                "more", "More", Icons.Filled.Terminal,
                listOf(MenuEntry("linux", "Linux runtime and every list", "The full components screen", icon = Icons.Filled.Terminal, onA = { onNavigate(DeckRoutes.COMPONENTS) })),
            ),
        ),
        onBack = onBack,
    )
    defaultItem?.let { item ->
        SetAsDefaultSheet(item, onDismiss = { defaultItem = null }) { defaultItem = null; toast(context, "Set as default") }
    }
}

/** One download source's components: A downloads and installs, with progress on the line. */
@Composable
internal fun SimpleComponentSourcePage(index: Int, onBack: () -> Unit, vm: ContentsHubViewModel = viewModel()) {
    val context = LocalContext.current
    val sources by vm.sources.collectAsState()
    val items by vm.detailItems.collectAsState()
    val loading by vm.detailLoading.collectAsState()
    val keepRaw by vm.keepRaw.collectAsState()
    val states by ContentDownloadRegistry.states.collectAsState()
    val installedKeys by vm.installedKeys.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val source = sources.getOrNull(index)
    LaunchedEffect(source?.name) { if (source != null) vm.selectSource(source) }
    val byType = items.groupBy { it.type }
    val sections = byType.entries.map { (type, list) ->
        MenuSection(
            key = "t$type", title = type, icon = if (type == ContentsTypes.GPU_DRIVERS) Icons.Filled.Memory else Icons.Filled.Extension,
            entries = list.map { item ->
                val key = ContentsInstaller.keyFor(item.type, item.sourceName, item.versionName)
                val st = states[key]
                val running = st != null && !st.terminal
                // Read installedKeys so the line updates when an install finishes.
                val isIn = installedKeys.let { vm.isInstalled(item) }
                MenuEntry(
                    key = "d${item.type}-${item.versionName}-${item.downloadUrl}",
                    title = item.displayName,
                    subtitle = listOfNotNull(item.versionName, item.publishedAt?.take(10)).joinToString(" · "),
                    trailing = when {
                        running -> "${(st!!.fraction * 100).toInt()}%"
                        isIn -> "Installed"
                        item.sizeBytes != null -> "%.0f MB".format(item.sizeBytes / 1_048_576.0)
                        else -> null
                    },
                    current = isIn,
                    progress = if (running) st!!.fraction else null,
                    icon = Icons.Filled.CloudDownload,
                    onA = {
                        if (!running) ContentsInstaller.install(context, item.type, item.sourceName, item.versionName, item.downloadUrl, keepRaw) {
                            vm.refreshStatus(); vm.refreshFolders(); tick++
                        }
                    },
                    actions = buildList {
                        if (running) add(MenuAction("Cancel the download", { ContentDownloadRegistry.requestCancel(key) }))
                        add(MenuAction("Save the file only", { vm.saveArchive(item) }))
                    },
                )
            },
        )
    }
    SimpleMenuPage(
        overline = "Components",
        title = source?.name ?: "Source",
        sections = sections.ifEmpty { listOf(MenuSection("none", "Components", Icons.Filled.Extension, emptyList(), empty = if (loading) "Loading the list…" else "Nothing to download here.")) },
        onBack = onBack,
        banner = states.values.firstOrNull { !it.terminal }?.let { "Downloading ${it.title} · ${(it.fraction * 100).toInt()}%" },
    )
}

// ───────────────────────────── GPU drivers ─────────────────────────────

/** GPU drivers: the installed ones (set as default, remove), the sources to download more from, and install from a file. */
@Composable
internal fun SimpleDriversPage(onNavigate: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val manager = remember { activity?.let { AdrenotoolsManager(it) } }
    var tick by remember { mutableIntStateOf(0) }
    OnResume { tick++ }
    val drivers = remember(tick) { runCatching { manager?.enumarateInstalledDrivers()?.toList() }.getOrNull().orEmpty() }
    val sources = remember { runCatching { DriverSources.allSources(context) }.getOrDefault(emptyList()) }
    var defaultItem by remember { mutableStateOf<SetAsDefault.Item?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: InAppFilePicker.pickedUri(result.data) ?: return@rememberLauncherForActivityResult
        val id = runCatching { manager?.installDriver(uri) }.getOrNull().orEmpty()
        toast(context, if (id.isNotEmpty()) "Installed ${manager?.getDriverName(id) ?: id}" else "That file is not a driver package")
        tick++
    }
    val installed = drivers.map { id ->
        val name = manager?.getDriverName(id) ?: id
        MenuEntry(
            key = "d$id", title = name,
            subtitle = listOfNotNull(manager?.getDriverVersion(id), manager?.getDriverVendor(id)).filter { it.isNotBlank() }.joinToString(" · "),
            icon = Icons.Filled.Memory,
            actions = listOf(
                MenuAction("Set as default…", { defaultItem = SetAsDefault.driverItem(id, name) }),
                MenuAction("Remove", { manager?.removeDriver(id); tick++; toast(context, "Removed $name") }, danger = true, confirm = "Remove $name? Games set to it go back to their container's driver."),
            ),
        )
    }
    val fromFile = MenuAction("Install from a file", { picker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.DRIVER, "Select GPU driver")) })
    SimpleMenuPage(
        overline = "Manage",
        title = "GPU drivers",
        sections = listOf(
            MenuSection("installed", "Installed", Icons.Filled.Memory, installed, empty = "Only the phone's own driver so far.", primary = fromFile),
            MenuSection(
                "get", "Get drivers", Icons.Filled.CloudDownload,
                sources.mapIndexed { i, s -> MenuEntry("s$i-${s.name}", s.name, "Browse and download", icon = Icons.Filled.CloudDownload, onA = { onNavigate(SimpleRoutes.driverSource(i)) }) },
                empty = "No driver sources.", primary = fromFile,
            ),
        ),
        onBack = onBack,
    )
    defaultItem?.let { item ->
        SetAsDefaultSheet(item, onDismiss = { defaultItem = null }) { defaultItem = null; toast(context, "Set as default") }
    }
}

/** One driver source's drivers: A downloads and installs, with progress on the line. */
@Composable
internal fun SimpleDriverSourcePage(index: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val source = remember { runCatching { DriverSources.allSources(context) }.getOrDefault(emptyList()).getOrNull(index) }
    var entries by remember { mutableStateOf<List<RemoteDriverEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val progress = remember { mutableStateMapOf<String, Float>() }
    LaunchedEffect(source?.name) {
        if (source == null) return@LaunchedEffect
        RemoteDriverRepository(context).fetchEntries(source)
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "Could not load the list"; entries = emptyList() }
    }
    fun download(e: RemoteDriverEntry) {
        if (progress.containsKey(e.downloadUrl) || activity == null) return
        progress[e.downloadUrl] = 0f
        scope.launch {
            val file = RemoteDriverRepository(context).downloadEntry(e) { p -> progress[e.downloadUrl] = p / 100f }
            progress.remove(e.downloadUrl)
            file.onSuccess { f ->
                val id = withContext(Dispatchers.IO) { runCatching { AdrenotoolsManager(activity).installDriver(Uri.fromFile(f)) }.getOrNull().orEmpty() }
                f.delete()
                toast(context, if (id.isNotEmpty()) "Installed ${e.displayName}" else "Could not install ${e.displayName}")
            }.onFailure { toast(context, "Download failed: ${it.message ?: "unknown"}") }
        }
    }
    SimpleMenuPage(
        overline = "GPU drivers",
        title = source?.name ?: "Source",
        sections = listOf(
            MenuSection(
                "list", "Drivers", Icons.Filled.Memory,
                entries.orEmpty().map { e ->
                    val p = progress[e.downloadUrl]
                    MenuEntry(
                        key = "e${e.downloadUrl}", title = e.displayName, subtitle = e.source,
                        trailing = p?.let { "${(it * 100).toInt()}%" }, progress = p,
                        icon = Icons.Filled.CloudDownload,
                        onA = { download(e) },
                        actions = listOf(MenuAction("Download and install", { download(e) })),
                    )
                },
                empty = when {
                    entries == null -> "Loading the list…"
                    error != null -> error!!
                    else -> "No drivers in this source."
                },
            ),
        ),
        onBack = onBack,
    )
}

// ───────────────────────────── Controls ─────────────────────────────

/** Controls: the on-screen / controller profiles, the connected controllers, and the controller test. */
@Composable
internal fun SimpleControlsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { InputControlsManager(context) }
    var tick by remember { mutableIntStateOf(0) }
    OnResume { tick++ }
    val profiles = remember(tick) { runCatching { manager.profiles.toList() }.getOrDefault(emptyList()) }
    val controllers = remember(tick) { runCatching { ExternalController.getControllers().toList() }.getOrDefault(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ControlsProfile?>(null) }
    var testOpen by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: InAppFilePicker.pickedUri(result.data) ?: return@rememberLauncherForActivityResult
        val ok = runCatching {
            val json = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            manager.importProfile(JSONObject(json)) != null
        }.getOrDefault(false)
        toast(context, if (ok) "Profile imported" else "That file is not a controls profile")
        tick++
    }
    fun editLayout(p: ControlsProfile) = context.startActivity(Intent(context, ControlsEditorActivity::class.java).putExtra("profile_id", p.id))
    fun bindings(p: ControlsProfile, controllerId: String = ControlsProfile.DEFAULT_CONTROLLER_ID) =
        context.startActivity(Intent(context, ExternalControllerBindingsActivity::class.java).putExtra("profile_id", p.id).putExtra("controller_id", controllerId))

    val profileEntries = profiles.map { p ->
        MenuEntry(
            key = "p${p.id}", title = p.name, subtitle = "On-screen layout and controller bindings", icon = Icons.Filled.Gamepad,
            onA = { editLayout(p) },
            actions = listOf(
                MenuAction("Edit the on-screen layout", { editLayout(p) }),
                MenuAction("Controller bindings", { bindings(p) }),
                MenuAction("Rename", { renaming = p }),
                MenuAction("Duplicate", { manager.duplicateProfile(p); tick++ }, confirm = "Make a copy of ${p.name}?"),
                MenuAction("Export", { val f = runCatching { manager.exportProfile(p) }.getOrNull(); toast(context, if (f != null) "Exported to ${f.path}" else "Export failed") }),
                MenuAction("Remove", { manager.removeProfile(p); tick++ }, danger = true, confirm = "Remove ${p.name}?"),
            ),
        )
    }
    val controllerEntries = controllers.map { c ->
        MenuEntry(
            key = "c${c.id}", title = c.name ?: c.id, subtitle = if (c.isConnected) "Connected" else "Not connected",
            icon = Icons.Filled.SportsEsports,
            onA = { profiles.firstOrNull()?.let { bindings(it, c.id) } },
        )
    }
    SimpleMenuPage(
        overline = "Manage",
        title = "Controls",
        sections = listOf(
            MenuSection("profiles", "Profiles", Icons.Filled.Gamepad, profileEntries, empty = "No profiles yet.", primary = MenuAction("New profile", { creating = true })),
            MenuSection("controllers", "Controllers", Icons.Filled.SportsEsports, controllerEntries, empty = "No controller seen yet. Press a button on it."),
            MenuSection(
                "tools", "Tools", Icons.Filled.Build,
                listOf(
                    MenuEntry("test", "Controller test", "Check every button, stick and trigger", icon = Icons.Filled.SportsEsports, onA = { testOpen = true }),
                    MenuEntry("import", "Import a profile", "An .icp file from your storage", icon = Icons.Filled.Add, onA = { importer.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.ICP, "Select controls profile")) }),
                ),
            ),
        ),
        onBack = onBack,
    )
    if (creating) NameDialog("New profile", "Profile ${profiles.size + 1}", onDismiss = { creating = false }) { name -> creating = false; manager.createProfile(name); tick++ }
    renaming?.let { p -> NameDialog("Rename", p.name, onDismiss = { renaming = null }) { name -> renaming = null; p.name = name; p.save(); tick++ } }
    if (testOpen) {
        SettingsControllerTestDialog(
            onDismiss = { testOpen = false },
            profile = profiles.firstOrNull(),
            allProfiles = profiles,
            onSelectProfile = { },
            onCreateProfile = { name -> manager.createProfile(name); tick++ },
            onRenameProfile = { },
            onBindingsChanged = { tick++ },
            onOpenAllBindings = { profiles.firstOrNull()?.let { bindings(it) } },
        )
    }
}
