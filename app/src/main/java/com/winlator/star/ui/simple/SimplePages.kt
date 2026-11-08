package com.winlator.star.ui.simple

import android.app.Activity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import com.winlator.star.core.HttpUtils
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material.icons.filled.Download
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
internal fun OnResume(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) action() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

internal fun toast(ctx: android.content.Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

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
                    MenuEntry("saves", "Save Manager", "Back up, restore and sync game saves", icon = Icons.Filled.Save, onA = { onNavigate(SimpleRoutes.SAVES) }),
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
internal fun NavGraphBuilder.simpleRoutes(
    open: (String) -> Unit,
    back: () -> Unit,
    onMyAccount: () -> Unit,
    onAbout: () -> Unit,
    onLaunchStore: (Screen) -> Unit,
) {
    composable(LabRoutes.LIBRARY) { LabLibraryPage(onNavigate = open) }
    composable(LabRoutes.STORES) { LabStoresPage(onLaunchStore = onLaunchStore, onBack = back) }
    composable(LabRoutes.GAME, arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })) { e ->
        LabGamePage(path = e.arguments?.getString("path").orEmpty(), onNavigate = open, onBack = back)
    }
    composable(SimpleRoutes.TOOLS) { SimpleToolsPage(onNavigate = open, onBack = back, onMyAccount = onMyAccount, onAbout = onAbout) }
    composable(SimpleRoutes.FILES) { FileManagerScreen() }
    composable(SimpleRoutes.SAVES) { SaveManagerScreen(onBack = back, lab = AppThemeState.simpleLook) }
    composable(SimpleRoutes.LOGS) { LogManagerScreen(onClose = back) }
    composable(SimpleRoutes.CONTAINERS) { SimpleContainersPage(onNavigate = open, onBack = back) }
    composable(SimpleRoutes.COMPONENTS) { SimpleComponentsPage(onNavigate = open, onBack = back) }
    composable(SimpleRoutes.COMPONENT_SOURCE, arguments = listOf(navArgument("i") { type = NavType.IntType; defaultValue = 0 })) { e ->
        SimpleComponentSourcePage(index = e.arguments?.getInt("i") ?: 0, onBack = back)
    }
    // Lab's Contents (Classic's Contents screen as Lab lists); the rail's "Contents".
    composable(SimpleRoutes.DRIVERS) { SimpleContentsPage(onNavigate = open, onBack = back) }
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
        simpleHome -> "Lab"
        else -> "Deck"
    }
    val styles = listOf(
        MenuAction(mark("Lab", style == "Lab"), { AppThemeState.setDeckXmbHome(false); AppThemeState.setSimpleHome(true); AppThemeState.setUiStyle(AppThemeState.UI_STYLE_DECK) }),
        MenuAction(mark("Deck", style == "Deck"), { AppThemeState.setDeckXmbHome(false); AppThemeState.setSimpleHome(false); AppThemeState.setUiStyle(AppThemeState.UI_STYLE_DECK) }),
        MenuAction(mark("XMB", style == "XMB"), { AppThemeState.setDeckXmbHome(true); AppThemeState.setUiStyle(AppThemeState.UI_STYLE_DECK) }),
        MenuAction(mark("Classic", style == "Classic"), { AppThemeState.setUiStyle(AppThemeState.UI_STYLE_CLASSIC) }),
    )
    val layouts = listOf(AppThemeState.SIMPLE_LAYOUT_SHELF to "Shelf", AppThemeState.SIMPLE_LAYOUT_RAIL to "Rail", AppThemeState.SIMPLE_LAYOUT_FOCUS to "Focus")
    val orientations = listOf(AppOrientation.AUTO to "Auto", AppOrientation.PORTRAIT to "Portrait", AppOrientation.LANDSCAPE to "Landscape")
    val scales = listOf(0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f)
    fun pct(f: Float) = "${(f * 100).roundToInt()}%"

    val look = listOf(
        MenuEntry("style", "Interface style", "Lab, Deck, XMB or Classic", trailing = style, icon = Icons.Filled.Dashboard, actions = styles),
        MenuEntry(
            "look", "Look", "Dark or light; the accent is your theme's", trailing = if (AppThemeState.labLight) "Light" else "Dark", icon = Icons.Filled.Palette,
            actions = listOf(
                MenuAction(mark("Dark", !AppThemeState.labLight), { AppThemeState.setLabLight(false) }),
                MenuAction(mark("Light", AppThemeState.labLight), { AppThemeState.setLabLight(true) }),
            ),
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
            play = { startDesktop(c) },
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

internal val TYPE_LABELS = mapOf(
    "CONTENT_TYPE_WINE" to "Wine", "CONTENT_TYPE_PROTON" to "Proton", "CONTENT_TYPE_DXVK" to "DXVK", "CONTENT_TYPE_D7VK" to "D7VK",
    "CONTENT_TYPE_VKD3D" to "VKD3D", "CONTENT_TYPE_BOX64" to "Box64", "CONTENT_TYPE_WOWBOX64" to "WOWBox64",
    "CONTENT_TYPE_FEXCORE" to "FEXCore", "CONTENT_TYPE_BOX64EC" to "Box64EC", "CONTENT_TYPE_VEGAS" to "VEGAS",
)

/** The file name an Android content URI points at, for an install from a file. */
internal fun displayNameOf(ctx: android.content.Context, uri: Uri): String =
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
                    // The download button on the row (Right from the row, or Start), unless it is installed or on its way.
                    play = if (isIn || running) null else ({
                        ContentsInstaller.install(context, item.type, item.sourceName, item.versionName, item.downloadUrl, keepRaw) {
                            vm.refreshStatus(); vm.refreshFolders(); tick++
                        }
                    }),
                    playIcon = Icons.Filled.Download, playLabel = "Download",
                    actions = buildList {
                        if (running) add(MenuAction("Cancel the download", { ContentDownloadRegistry.requestCancel(key) }))
                        add(MenuAction("Save the file only", { vm.saveArchive(item) }))
                    },
                )
            },
        )
    }
    SimpleMenuPage(
        overline = "Contents",
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
        title = "Drivers & components",
        sections = listOf(
            MenuSection("installed", "Installed", Icons.Filled.Memory, installed, empty = "Only the phone's own driver so far.", primary = fromFile),
            MenuSection(
                "get", "Get drivers", Icons.Filled.CloudDownload,
                sources.mapIndexed { i, s -> MenuEntry("s$i-${s.name}", s.name, "Browse and download", icon = Icons.Filled.CloudDownload, onA = { onNavigate(SimpleRoutes.driverSource(i)) }) },
                empty = "No driver sources.", primary = fromFile,
            ),
            MenuSection(
                "components", "Components", Icons.Filled.Extension,
                listOf(
                    MenuEntry("comp", "Wine, Proton, DXVK, VKD3D, FEX, Box64", "Installed ones, set as default, download more", icon = Icons.Filled.Extension, onA = { onNavigate(SimpleRoutes.COMPONENTS) }),
                ),
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
    // Classic's Controls screen as Lab lists: the same four tabs (On-screen, Controller, Assign, Device) on the same
    // data (InputControlsManager, the profile's controllers); Assign and Device are Classic's own sections.
    val context = LocalContext.current
    val manager = remember { InputControlsManager(context) }
    var tick by remember { mutableIntStateOf(0) }
    OnResume { tick++ }
    // Controllers plugged in or out while the page is open: rebuild the list (as Classic does).
    DisposableEffect(Unit) {
        val im = context.getSystemService(android.content.Context.INPUT_SERVICE) as? android.hardware.input.InputManager
        val l = object : android.hardware.input.InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { tick++ }
            override fun onInputDeviceRemoved(deviceId: Int) { tick++ }
            override fun onInputDeviceChanged(deviceId: Int) { tick++ }
        }
        im?.registerInputDeviceListener(l, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { im?.unregisterInputDeviceListener(l) }
    }
    val profiles = remember(tick) { runCatching { manager.profiles.toList() }.getOrDefault(emptyList()) }
    // The profile the Controller tab works on (Classic's profile picker); the first one until another is picked.
    var selId by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(-1) }
    val sel = profiles.firstOrNull { it.id == selId } ?: profiles.firstOrNull()
    val layouts = remember(tick) { profiles.associate { it.id to runCatching { it.hasElementsOnDisk() }.getOrDefault(false) } }
    // As Classic: the profile's saved controllers plus the connected ones, the Default template on its own row.
    val loaded = remember(tick, sel?.id) { runCatching { sel?.loadControllers()?.toList() }.getOrNull().orEmpty() }
    val controllers = remember(tick, sel?.id) {
        val list = loaded.toMutableList()
        runCatching { ExternalController.getControllers() }.getOrNull()?.forEach { c -> if (c !in list) list.add(c) }
        list.filter { it.id != ControlsProfile.DEFAULT_CONTROLLER_ID }
    }
    val defaultBinds = loaded.firstOrNull { it.id == ControlsProfile.DEFAULT_CONTROLLER_ID }?.controllerBindingCount ?: 0
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ControlsProfile?>(null) }
    var testOpen by remember { mutableStateOf(false) }
    var community by remember { mutableStateOf<List<String>?>(null) }
    var communityOpen by remember { mutableStateOf(false) }
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
    fun copyBindings(fromId: String, to: ExternalController) {
        val p = sel ?: return
        p.loadControllers()
        val src = p.getController(fromId)
        // Never copy an empty set: it would wipe the target's bindings.
        if (src == null || src.controllerBindingCount == 0) { toast(context, "Nothing to copy"); return }
        p.addController(to.id)?.let { it.copyBindingsFrom(src); p.save(); tick++; toast(context, "Bindings copied") }
    }
    fun exported(f: java.io.File?) = toast(context, if (f != null) "Exported to ${f.path}" else "Export failed")

    // ── On-screen: the profiles (pick one, edit its layout) ──
    val onScreen = profiles.map { p ->
        val on = p.id == sel?.id
        MenuEntry(
            key = "p${p.id}", title = p.name,
            subtitle = if (layouts[p.id] == true) "On-screen layout" else "No on-screen layout yet",
            trailing = if (on) "Selected" else null, current = on,
            icon = Icons.Filled.Gamepad, group = "Profiles",
            onA = { selId = p.id },
            play = { selId = p.id; editLayout(p) }, playIcon = Icons.Filled.Edit, playLabel = "Edit layout",
            actions = listOf(
                MenuAction("Edit the on-screen layout", { selId = p.id; editLayout(p) }),
                MenuAction("Controller bindings", { selId = p.id; bindings(p) }),
                MenuAction("Rename", { renaming = p }),
                MenuAction("Duplicate", { manager.duplicateProfile(p); tick++ }, confirm = "Make a copy of ${p.name}?"),
                MenuAction("Export (.icpx)", { exported(runCatching { manager.exportProfile(p) }.getOrNull()) }),
                MenuAction("Export (.icp, older versions)", { exported(runCatching { manager.exportLegacyProfile(p) }.getOrNull()) }),
                MenuAction("Delete", { manager.removeProfile(p); if (selId == p.id) selId = -1; tick++ }, danger = true, confirm = "Delete ${p.name}?"),
            ),
        )
    } + listOf(
        MenuEntry("import", "Import a profile", ".icp or .icpx file", icon = Icons.Filled.Add, group = "Share / transfer",
            onA = { importer.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.ICP, "Select controls profile")) }),
        MenuEntry("community", "Download community profiles", "The online list", icon = Icons.Filled.CloudDownload, group = "Share / transfer",
            onA = {
                communityOpen = true
                if (community == null) HttpUtils.download("https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/index.txt") { content ->
                    (context as? Activity)?.runOnUiThread {
                        community = content?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                        if (content == null) toast(context, "Could not load the list")
                    }
                }
            }),
    )

    // ── Controller: the selected profile's bindings for each controller ──
    val controllerTab = if (sel == null) emptyList() else buildList {
        add(MenuEntry("prof", "Profile: ${sel.name}", "The bindings below belong to this profile", icon = Icons.Filled.Gamepad,
            actions = profiles.map { p -> MenuAction(p.name, { selId = p.id }) }))
        add(MenuEntry("test", "Test and bind controllers", "Remap buttons and check every input", icon = Icons.Filled.SportsEsports, onA = { testOpen = true }))
        add(MenuEntry("default", "Default / Any controller", "$defaultBinds bindings · new controllers inherit these", icon = Icons.Filled.SportsEsports,
            group = "Controllers", onA = { bindings(sel) }))
        controllers.forEach { c ->
            val dev = if (c.isConnected) android.view.InputDevice.getDevice(c.deviceId) else null
            val battery = runCatching {
                if (dev != null && android.os.Build.VERSION.SDK_INT >= 29) dev.batteryState.takeIf { it.isPresent }?.capacity?.takeIf { it >= 0f }?.let { Math.round(it * 100f) } else null
            }.getOrNull()
            val vib = runCatching { dev?.vibrator?.takeIf { it.hasVibrator() } }.getOrNull()
            add(
                MenuEntry(
                    key = "c${c.id}", title = c.name ?: c.id, group = "Controllers",
                    subtitle = listOfNotNull("${c.controllerBindingCount} bindings", if (c.isConnected) "Connected" else "Not connected", battery?.let { "Battery $it%" }).joinToString(" · "),
                    current = c.isConnected,
                    icon = Icons.Filled.SportsEsports,
                    onA = { bindings(sel, c.id) },
                    actions = buildList {
                        add(MenuAction("Edit its bindings", { bindings(sel, c.id) }))
                        add(MenuAction("Copy bindings from Default / Any controller", { copyBindings(ControlsProfile.DEFAULT_CONTROLLER_ID, c) }))
                        controllers.filter { it.id != c.id && it.controllerBindingCount > 0 }.forEach { o ->
                            add(MenuAction("Copy bindings from ${o.name}", { copyBindings(o.id, c) }))
                        }
                        if (vib != null) add(MenuAction("Test rumble", {
                            runCatching { vib.vibrate(android.os.VibrationEffect.createOneShot(250, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) }
                        }))
                        if (c.controllerBindingCount > 0) add(MenuAction("Remove its bindings", { sel.removeController(c); sel.save(); tick++ }, danger = true, confirm = "Remove the bindings of ${c.name}?"))
                    },
                ),
            )
        }
    }

    SimpleMenuPage(
        overline = "Manage",
        title = "Controls",
        sections = listOf(
            MenuSection("onscreen", "On-screen", Icons.Filled.TouchApp, onScreen, empty = "No profiles yet.", primary = MenuAction("New profile", { creating = true })),
            MenuSection("controller", "Controller", Icons.Filled.SportsEsports, controllerTab, empty = "Make a profile first (On-screen tab)."),
            MenuSection("assign", "Assign", Icons.Filled.People, emptyList(), content = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    com.winlator.star.ui.screens.GlobalPlayerSlotsSection()
                }
            }),
            MenuSection("device", "Device", Icons.Filled.Build, emptyList(), content = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    com.winlator.star.ui.screens.GyroscopeSection()
                    Spacer(Modifier.height(16.dp))
                    com.winlator.star.ui.screens.SteamControllerSection()
                    Spacer(Modifier.height(16.dp))
                    com.winlator.star.ui.screens.MenuButtonSection()
                }
            }),
        ),
        onBack = onBack,
    )
    if (creating) NameDialog("New profile", "Profile ${profiles.size + 1}", onDismiss = { creating = false }) { name -> creating = false; manager.createProfile(name)?.let { selId = it.id }; tick++ }
    renaming?.let { p -> NameDialog("Rename", p.name, onDismiss = { renaming = null }) { name -> renaming = null; p.name = name; p.save(); tick++ } }
    if (communityOpen) {
        val items = community
        key(items?.size ?: -1) {
            SimpleActionSheet(
                title = "Community profiles",
                actions = items.orEmpty().map { f ->
                    MenuAction(f.removeSuffix(".icp"), {
                        HttpUtils.download("https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/$f") { content ->
                            val ok = content != null && runCatching { manager.importProfile(JSONObject(content)) != null }.getOrDefault(false)
                            (context as? Activity)?.runOnUiThread { toast(context, if (ok) "Imported ${f.removeSuffix(".icp")}" else "Could not import $f"); tick++ }
                        }
                    })
                },
                message = if (items == null) "Loading the list…" else if (items.isEmpty()) "Nothing in the list." else null,
                onDismiss = { communityOpen = false },
            ) { a -> communityOpen = false; a.run() }
        }
    }
    if (testOpen) {
        SettingsControllerTestDialog(
            onDismiss = { testOpen = false; tick++ },
            profile = sel,
            allProfiles = profiles,
            onSelectProfile = { p -> selId = p.id },
            onCreateProfile = { name -> manager.createProfile(name); tick++ },
            onRenameProfile = { },
            onBindingsChanged = { tick++ },
            onOpenAllBindings = { sel?.let { bindings(it) } },
        )
    }
}
