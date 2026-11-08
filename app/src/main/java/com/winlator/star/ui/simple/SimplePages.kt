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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel")) } },
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
        overline = L("Manage"),
        title = L("Tools"),
        sections = listOf(
            MenuSection(
                key = "tools",
                title = L("Tools"),
                icon = Icons.Filled.Build,
                entries = listOf(
                    MenuEntry("files", L("File manager"), L("Internal storage, SD card and every container's drives"), icon = Icons.Filled.FolderOpen, onA = { onNavigate(SimpleRoutes.FILES) }),
                    MenuEntry("saves", L("Save Manager"), L("Back up, restore and sync game saves"), icon = Icons.Filled.Save, onA = { onNavigate(SimpleRoutes.SAVES) }),
                    MenuEntry("logs", L("Game logs"), L("What each game printed while it ran"), icon = Icons.Filled.Description, onA = { onNavigate(SimpleRoutes.LOGS) }),
                    MenuEntry("wrappers", L("Wrappers"), L("Graphics wrappers"), icon = Icons.Filled.Layers, onA = { onNavigate(Screen.Wrappers.route) }),
                    MenuEntry("drivers", L("GPU drivers"), L("Turnip and Qualcomm drivers"), icon = Icons.Filled.Memory, onA = { onNavigate(SimpleRoutes.DRIVERS) }),
                ),
            ),
            MenuSection(
                key = "help",
                title = L("Help"),
                icon = Icons.Filled.Info,
                entries = listOf(
                    MenuEntry("account", L("My account"), L("Sign in to manage your shared configs"), icon = Icons.Filled.AccountCircle, onA = onMyAccount),
                    MenuEntry("about", L("About"), L("Version, updates and credits"), icon = Icons.Filled.Info, onA = onAbout),
                    MenuEntry("help", L("Help and Support"), L("GitHub, issue tracker and Discord"), icon = Icons.Filled.HelpOutline, onA = { showHelp = true }),
                    MenuEntry("setup", L("Run setup again"), L("Permissions, theme and stores"), icon = Icons.Filled.RestartAlt, onA = { DeckSetup.requestRerun() }),
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
    composable(
        LabRoutes.DETAILS,
        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }, navArgument("tab") { type = NavType.IntType; defaultValue = 0 }),
    ) { e ->
        LabGameDetailsPage(path = e.arguments?.getString("path").orEmpty(), tab = e.arguments?.getInt("tab") ?: 0, onNavigate = open, onBack = back)
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
    val orientations = listOf(AppOrientation.AUTO to L("Auto"), AppOrientation.PORTRAIT to L("Portrait"), AppOrientation.LANDSCAPE to L("Landscape"))
    val scales = listOf(0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f)
    fun pct(f: Float) = "${(f * 100).roundToInt()}%"

    val langTag = LabLang.tag(context)
    val look = listOf(
        // Lab's language: every language Lab is translated into, each named in its own language.
        MenuEntry(
            "language", L("Language"), trailing = LabLang.name(context), icon = Icons.Filled.Translate,
            actions = LabLang.LANGUAGES.map { (t, n) ->
                MenuAction(
                    mark(if (t.isEmpty()) L("System default") else n, t == langTag),
                    { (context as? Activity)?.let { LabLang.set(it, t) } },
                    confirm = if (t == langTag) null else L("Bannerlator restarts to switch the language."),
                )
            },
        ),
        MenuEntry("style", L("Interface style"), L("Lab, Deck, XMB or Classic"), trailing = style, icon = Icons.Filled.Dashboard, actions = styles),
        MenuEntry(
            "look", L("Look"), L("Dark or light; the accent is your theme's"), trailing = if (AppThemeState.labLight) L("Light") else L("Dark"), icon = Icons.Filled.Palette,
            actions = listOf(
                MenuAction(mark(L("Dark"), !AppThemeState.labLight), { AppThemeState.setLabLight(false) }),
                MenuAction(mark(L("Light"), AppThemeState.labLight), { AppThemeState.setLabLight(true) }),
            ),
        ),
        // Game art (LabArt): the art behind the game pages, previews in Home's big tile, how the tiles sit over it, the cards.
        MenuEntry(
            "gameart", L("Game art background"), L("Off, game art, 3D art that slowly drifts in depth, or with previews: a trailer in Home's big tile"),
            trailing = LabArt.label(LabArt.MODES, LabArt.mode), icon = Icons.Filled.Image,
            actions = LabArt.MODES.map { (v, l) -> MenuAction(mark(L(l), v == LabArt.mode), { LabArt.chooseMode(v) }) },
        ),
        MenuEntry("wifi", L("Previews on Wi-Fi only"), L("Trailers download only on Wi-Fi"), icon = Icons.Filled.Wifi, toggle = LabArt.wifiOnly, onA = { LabArt.chooseWifiOnly(!LabArt.wifiOnly) }),
        MenuEntry("sound", L("Preview sound"), L("Off: previews play muted"), icon = Icons.AutoMirrored.Filled.VolumeUp, toggle = LabArt.sound, onA = { LabArt.chooseSound(!LabArt.sound) }),
        MenuEntry(
            "panels", L("Panels over art"), L("How see-through the tiles are over the art"), trailing = LabArt.label(LabArt.PANELS, LabArt.panels), icon = Icons.Filled.Layers,
            actions = LabArt.PANELS.map { (v, l) -> MenuAction(mark(L(l), v == LabArt.panels), { LabArt.choosePanels(v) }) },
        ),
        MenuEntry("grid", L("Grid over art"), L("Lab's graph paper and signals on top of the art"), icon = Icons.Filled.GridOn, toggle = LabArt.grid, onA = { LabArt.chooseGrid(!LabArt.grid) }),
        MenuEntry(
            "cards", L("Library cards"), L("Covers, wide covers or icons on Home and Library"), trailing = LabArt.label(LabArt.CARDS, LabArt.cards), icon = Icons.Filled.ViewModule,
            actions = LabArt.CARDS.map { (v, l) -> MenuAction(mark(L(l), v == LabArt.cards), { LabArt.chooseCards(v) }) },
        ),
        MenuEntry(
            "cardsize", L("Card size"), L("The Library grid"), trailing = LabArt.label(LabArt.SIZES, LabArt.cardSize), icon = Icons.Filled.PhotoSizeSelectLarge,
            actions = LabArt.SIZES.map { (v, l) -> MenuAction(mark(L(l), v == LabArt.cardSize), { LabArt.chooseCardSize(v) }) },
        ),
        MenuEntry(
            "orientation", L("Orientation"), L("The app's own screens; games keep theirs"), trailing = orientations.firstOrNull { it.first == orientation }?.second,
            icon = Icons.Filled.ScreenRotation,
            actions = orientations.map { (m, l) ->
                MenuAction(mark(l, m == orientation), {
                    orientation = m
                    AppOrientation.setMode(context, m)
                    (context as? Activity)?.let { AppOrientation.apply(it) }
                })
            },
        ),
        MenuEntry("ui", L("Interface size"), trailing = pct(uiScale), icon = Icons.Filled.ZoomIn,
            actions = scales.map { f -> MenuAction(mark(pct(f), (f * 100).roundToInt() == (uiScale * 100).roundToInt()), { AppThemeState.setUiScale(f) }) }),
        MenuEntry("font", L("Text size"), trailing = pct(fontScale), icon = Icons.Filled.TextFields,
            actions = scales.map { f -> MenuAction(mark(pct(f), (f * 100).roundToInt() == (fontScale * 100).roundToInt()), { AppThemeState.setFontScale(f) }) }),
    )
    val themes = themePresets.mapIndexed { i, t ->
        val custom = i == CUSTOM_PRESET_INDEX
        MenuEntry(
            key = "t$i",
            title = t.name,
            subtitle = if (custom) L("Your own accent colour · X to pick it") else null,
            swatch = if (custom) customAccent else t.primary,
            current = i == preset,
            onA = { AppThemeState.setPreset(i) },
            actions = if (custom) listOf(MenuAction(L("Pick the colour"), { accentOpen = true })) else emptyList(),
        )
    }
    SimpleMenuPage(
        overline = L("Settings"),
        title = L("Appearance"),
        sections = listOf(
            MenuSection("look", L("Look"), Icons.Filled.Dashboard, look),
            MenuSection("theme", L("Theme"), Icons.Filled.Palette, themes),
        ),
        onBack = onBack,
    )
    if (accentOpen) {
        OutlinedAlertDialog(
            onDismissRequest = { accentOpen = false },
            confirmButton = { TextButton(onClick = { accentOpen = false }) { Text(L("Done")) } },
            title = { Text(L("Accent colour")) },
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
                MenuAction(L("Settings"), { onNavigate(DeckSettingsRoutes.container(c.id)) }),
                MenuAction(L("Start its Windows desktop"), { startDesktop(c) }),
                MenuAction(L("Duplicate"), { vm.duplicate(c) { toast(context, L("Duplicated %1\$s", c.name)) } }, confirm = L("Make a copy of %1\$s, with its games and settings?", c.name)),
                MenuAction(L("Back up all saves"), {
                    GameSaveBackup.backup(context, c) { r -> (context as? Activity)?.runOnUiThread { toast(context, if (r.ok) L("Saves backed up (%1\$s files)", r.fileCount) else L("Backup failed: %1\$s", r.error ?: L("unknown"))) } }
                }),
                MenuAction(L("Export"), { vm.exportContainer(c) { path -> (context as? Activity)?.runOnUiThread { toast(context, if (path != null) L("Exported to %1\$s", path) else L("Export failed")) } } }),
                MenuAction(L("Remove"), { vm.remove(c, context) { toast(context, L("Removed %1\$s", c.name)) } }, danger = true, confirm = L("Remove %1\$s and everything in it? Its games' shortcuts go too.", c.name)),
            ),
        )
    }
    SimpleMenuPage(
        overline = L("Manage"),
        title = L("Containers"),
        sections = listOf(
            MenuSection(
                "containers", L("Containers"), Icons.Filled.Inventory2, entries,
                empty = L("No containers yet."),
                primary = MenuAction(L("New container"), {
                    if (com.winlator.star.xenvironment.ImageFs.find(context).isValid()) onNavigate(DeckSettingsRoutes.container(DeckSettingsRoutes.NEW_CONTAINER_ID))
                    else toast(context, L("The Windows system files are not installed yet."))
                }),
            ),
            MenuSection(
                "import", L("Backups"), Icons.Filled.Restore,
                backups.map { f ->
                    MenuEntry(
                        "b${f.absolutePath}", f.name, f.parent, icon = Icons.Filled.Restore,
                        actions = listOf(MenuAction(L("Import this container"), { vm.importContainer(f) { toast(context, L("Imported %1\$s", f.name)) } }, confirm = L("Import %1\$s as a new container?", f.name))),
                    )
                },
                empty = L("No container backups in Downloads/Winlator/Backups/Containers."),
            ),
            MenuSection(
                "defaults", L("Defaults"), Icons.Filled.Tune,
                listOf(MenuEntry("defaults", L("New container defaults"), L("What every new container starts with"), icon = Icons.Filled.Tune, onA = { onNavigate(DeckSettingsRoutes.DEFAULTS) })),
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
        toast(context, L("Installing %1\$s…", name))
        ContentsInstaller.installFromFile(context, type, name, uri) { ok ->
            (context as? Activity)?.runOnUiThread { toast(context, if (ok) L("Installed %1\$s", name) else L("Could not install %1\$s", name)); vm.refreshStatus(); tick++ }
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
                cm?.let { m -> runCatching { SetAsDefault.componentItem(context, m, p) }.getOrNull() }?.let { item -> add(MenuAction(L("Set as default…"), { defaultItem = item })) }
                add(MenuAction(L("Remove"), {
                    runCatching { cm?.removeContent(p); cm?.syncContents() }
                    vm.refreshStatus(); tick++
                    toast(context, L("Removed %1\$s %2\$s", label, p.verName))
                }, danger = true, confirm = L("Remove %1\$s %2\$s? Containers that use it fall back to their other choice.", label, p.verName)))
            },
        )
    }
    val sourceEntries = sources.mapIndexed { i, s ->
        MenuEntry("s$i-${s.name}", s.name, L("Browse and download"), icon = Icons.Filled.CloudDownload, onA = { onNavigate(SimpleRoutes.componentSource(i)) })
    }
    val savedEntries = saved.values.flatten().map { f ->
        MenuEntry(
            key = "f${f.uri}",
            title = f.name,
            subtitle = f.type,
            trailing = L("%.1f MB").format(f.sizeBytes / 1_048_576.0),
            icon = Icons.Filled.Save,
            onA = {
                toast(context, L("Installing %1\$s…", f.name))
                ContentsInstaller.installFromFile(context, f.type, f.name, f.uri) { ok ->
                    (context as? Activity)?.runOnUiThread { if (ok) vm.markInstalledFromSaved(f); toast(context, if (ok) L("Installed %1\$s", f.name) else L("Could not install %1\$s", f.name)); tick++ }
                }
            },
            actions = listOf(MenuAction(L("Delete the saved file"), { vm.deleteSaved(f) }, danger = true, confirm = L("Delete %1\$s?", f.name))),
        )
    }
    val fromFile = MenuAction(L("Install from a file"), { picker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.WCP, L("Select content pack"))) })
    SimpleMenuPage(
        overline = L("Manage"),
        title = L("Components"),
        sections = listOf(
            MenuSection("installed", L("Installed"), Icons.Filled.Extension, installedEntries, empty = L("Only the built-in components so far."), primary = fromFile),
            MenuSection("get", L("Get more"), Icons.Filled.CloudDownload, sourceEntries, empty = L("No download sources."), primary = fromFile),
            MenuSection("saved", L("Saved files"), Icons.Filled.Save, savedEntries, empty = L("No saved component files.")),
            MenuSection(
                "more", L("More"), Icons.Filled.Terminal,
                listOf(MenuEntry("linux", L("Linux runtime and every list"), L("The full components screen"), icon = Icons.Filled.Terminal, onA = { onNavigate(DeckRoutes.COMPONENTS) })),
            ),
        ),
        onBack = onBack,
    )
    defaultItem?.let { item ->
        SetAsDefaultSheet(item, onDismiss = { defaultItem = null }) { defaultItem = null; toast(context, L("Set as default")) }
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
                        isIn -> L("Installed")
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
                    playIcon = Icons.Filled.Download, playLabel = L("Download"),
                    actions = buildList {
                        if (running) add(MenuAction(L("Cancel the download"), { ContentDownloadRegistry.requestCancel(key) }))
                        add(MenuAction(L("Save the file only"), { vm.saveArchive(item) }))
                    },
                )
            },
        )
    }
    SimpleMenuPage(
        overline = L("Contents"),
        title = source?.name ?: L("Source"),
        sections = sections.ifEmpty { listOf(MenuSection("none", L("Components"), Icons.Filled.Extension, emptyList(), empty = if (loading) L("Loading the list…") else L("Nothing to download here."))) },
        onBack = onBack,
        banner = states.values.firstOrNull { !it.terminal }?.let { L("Downloading %1\$s · %2\$s%%", it.title, (it.fraction * 100).toInt()) },
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
        toast(context, if (id.isNotEmpty()) L("Installed %1\$s", manager?.getDriverName(id) ?: id) else L("That file is not a driver package"))
        tick++
    }
    val installed = drivers.map { id ->
        val name = manager?.getDriverName(id) ?: id
        MenuEntry(
            key = "d$id", title = name,
            subtitle = listOfNotNull(manager?.getDriverVersion(id), manager?.getDriverVendor(id)).filter { it.isNotBlank() }.joinToString(" · "),
            icon = Icons.Filled.Memory,
            actions = listOf(
                MenuAction(L("Set as default…"), { defaultItem = SetAsDefault.driverItem(id, name) }),
                MenuAction(L("Remove"), { manager?.removeDriver(id); tick++; toast(context, L("Removed %1\$s", name)) }, danger = true, confirm = L("Remove %1\$s? Games set to it go back to their container's driver.", name)),
            ),
        )
    }
    val fromFile = MenuAction(L("Install from a file"), { picker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.DRIVER, L("Select GPU driver"))) })
    SimpleMenuPage(
        overline = L("Manage"),
        title = L("Drivers & components"),
        sections = listOf(
            MenuSection("installed", L("Installed"), Icons.Filled.Memory, installed, empty = L("Only the phone's own driver so far."), primary = fromFile),
            MenuSection(
                "get", L("Get drivers"), Icons.Filled.CloudDownload,
                sources.mapIndexed { i, s -> MenuEntry("s$i-${s.name}", s.name, L("Browse and download"), icon = Icons.Filled.CloudDownload, onA = { onNavigate(SimpleRoutes.driverSource(i)) }) },
                empty = L("No driver sources."), primary = fromFile,
            ),
            MenuSection(
                "components", L("Components"), Icons.Filled.Extension,
                listOf(
                    MenuEntry("comp", L("Wine, Proton, DXVK, VKD3D, FEX, Box64"), L("Installed ones, set as default, download more"), icon = Icons.Filled.Extension, onA = { onNavigate(SimpleRoutes.COMPONENTS) }),
                ),
            ),
        ),
        onBack = onBack,
    )
    defaultItem?.let { item ->
        SetAsDefaultSheet(item, onDismiss = { defaultItem = null }) { defaultItem = null; toast(context, L("Set as default")) }
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
            .onFailure { error = it.message ?: L("Could not load the list"); entries = emptyList() }
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
                toast(context, if (id.isNotEmpty()) L("Installed %1\$s", e.displayName) else L("Could not install %1\$s", e.displayName))
            }.onFailure { toast(context, L("Download failed: %1\$s", it.message ?: L("unknown"))) }
        }
    }
    SimpleMenuPage(
        overline = L("GPU drivers"),
        title = source?.name ?: L("Source"),
        sections = listOf(
            MenuSection(
                "list", L("Drivers"), Icons.Filled.Memory,
                entries.orEmpty().map { e ->
                    val p = progress[e.downloadUrl]
                    MenuEntry(
                        key = "e${e.downloadUrl}", title = e.displayName, subtitle = e.source,
                        trailing = p?.let { "${(it * 100).toInt()}%" }, progress = p,
                        icon = Icons.Filled.CloudDownload,
                        onA = { download(e) },
                        actions = listOf(MenuAction(L("Download and install"), { download(e) })),
                    )
                },
                empty = when {
                    entries == null -> L("Loading the list…")
                    error != null -> error!!
                    else -> L("No drivers in this source.")
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
        toast(context, if (ok) L("Profile imported") else L("That file is not a controls profile"))
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
        if (src == null || src.controllerBindingCount == 0) { toast(context, L("Nothing to copy")); return }
        p.addController(to.id)?.let { it.copyBindingsFrom(src); p.save(); tick++; toast(context, L("Bindings copied")) }
    }
    fun exported(f: java.io.File?) = toast(context, if (f != null) L("Exported to %1\$s", f.path) else L("Export failed"))

    // ── On-screen: the profiles (pick one, edit its layout) ──
    val onScreen = profiles.map { p ->
        val on = p.id == sel?.id
        MenuEntry(
            key = "p${p.id}", title = p.name,
            subtitle = if (layouts[p.id] == true) L("On-screen layout") else L("No on-screen layout yet"),
            trailing = if (on) L("Selected") else null, current = on,
            icon = Icons.Filled.Gamepad, group = L("Profiles"),
            onA = { selId = p.id },
            play = { selId = p.id; editLayout(p) }, playIcon = Icons.Filled.Edit, playLabel = L("Edit layout"),
            actions = listOf(
                MenuAction(L("Edit the on-screen layout"), { selId = p.id; editLayout(p) }),
                MenuAction(L("Controller bindings"), { selId = p.id; bindings(p) }),
                MenuAction(L("Rename"), { renaming = p }),
                MenuAction(L("Duplicate"), { manager.duplicateProfile(p); tick++ }, confirm = L("Make a copy of %1\$s?", p.name)),
                MenuAction(L("Export (.icpx)"), { exported(runCatching { manager.exportProfile(p) }.getOrNull()) }),
                MenuAction(L("Export (.icp, older versions)"), { exported(runCatching { manager.exportLegacyProfile(p) }.getOrNull()) }),
                MenuAction(L("Delete"), { manager.removeProfile(p); if (selId == p.id) selId = -1; tick++ }, danger = true, confirm = L("Delete %1\$s?", p.name)),
            ),
        )
    } + listOf(
        MenuEntry("import", L("Import a profile"), L(".icp or .icpx file"), icon = Icons.Filled.Add, group = L("Share / transfer"),
            onA = { importer.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.ICP, L("Select controls profile"))) }),
        MenuEntry("community", L("Download community profiles"), L("The online list"), icon = Icons.Filled.CloudDownload, group = L("Share / transfer"),
            onA = {
                communityOpen = true
                if (community == null) HttpUtils.download("https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/index.txt") { content ->
                    (context as? Activity)?.runOnUiThread {
                        community = content?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                        if (content == null) toast(context, L("Could not load the list"))
                    }
                }
            }),
    )

    // ── Controller: the selected profile's bindings for each controller ──
    val controllerTab = if (sel == null) emptyList() else buildList {
        add(MenuEntry("prof", L("Profile: %1\$s", sel.name), L("The bindings below belong to this profile"), icon = Icons.Filled.Gamepad,
            actions = profiles.map { p -> MenuAction(p.name, { selId = p.id }) }))
        add(MenuEntry("test", L("Test and bind controllers"), L("Remap buttons and check every input"), icon = Icons.Filled.SportsEsports, onA = { testOpen = true }))
        add(MenuEntry("default", L("Default / Any controller"), L("%1\$s bindings · new controllers inherit these", defaultBinds), icon = Icons.Filled.SportsEsports,
            group = L("Controllers"), onA = { bindings(sel) }))
        controllers.forEach { c ->
            val dev = if (c.isConnected) android.view.InputDevice.getDevice(c.deviceId) else null
            val battery = runCatching {
                if (dev != null && android.os.Build.VERSION.SDK_INT >= 29) dev.batteryState.takeIf { it.isPresent }?.capacity?.takeIf { it >= 0f }?.let { Math.round(it * 100f) } else null
            }.getOrNull()
            val vib = runCatching { dev?.vibrator?.takeIf { it.hasVibrator() } }.getOrNull()
            add(
                MenuEntry(
                    key = "c${c.id}", title = c.name ?: c.id, group = L("Controllers"),
                    subtitle = listOfNotNull(L("%1\$s bindings", c.controllerBindingCount), if (c.isConnected) L("Connected") else L("Not connected"), battery?.let { L("Battery %1\$s%%", it) }).joinToString(" · "),
                    current = c.isConnected,
                    icon = Icons.Filled.SportsEsports,
                    onA = { bindings(sel, c.id) },
                    actions = buildList {
                        add(MenuAction(L("Edit its bindings"), { bindings(sel, c.id) }))
                        add(MenuAction(L("Copy bindings from Default / Any controller"), { copyBindings(ControlsProfile.DEFAULT_CONTROLLER_ID, c) }))
                        controllers.filter { it.id != c.id && it.controllerBindingCount > 0 }.forEach { o ->
                            add(MenuAction(L("Copy bindings from %1\$s", o.name), { copyBindings(o.id, c) }))
                        }
                        if (vib != null) add(MenuAction(L("Test rumble"), {
                            runCatching { vib.vibrate(android.os.VibrationEffect.createOneShot(250, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) }
                        }))
                        if (c.controllerBindingCount > 0) add(MenuAction(L("Remove its bindings"), { sel.removeController(c); sel.save(); tick++ }, danger = true, confirm = L("Remove the bindings of %1\$s?", c.name)))
                    },
                ),
            )
        }
    }

    SimpleMenuPage(
        overline = L("Manage"),
        title = L("Controls"),
        sections = listOf(
            MenuSection("onscreen", L("On-screen"), Icons.Filled.TouchApp, onScreen, empty = L("No profiles yet."), primary = MenuAction(L("New profile"), { creating = true })),
            MenuSection("controller", L("Controller"), Icons.Filled.SportsEsports, controllerTab, empty = L("Make a profile first (On-screen tab).")),
            MenuSection("assign", L("Assign"), Icons.Filled.People, emptyList(), content = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    com.winlator.star.ui.screens.GlobalPlayerSlotsSection()
                }
            }),
            MenuSection("device", L("Device"), Icons.Filled.Build, emptyList(), content = {
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
    if (creating) NameDialog(L("New profile"), L("Profile %1\$s", profiles.size + 1), onDismiss = { creating = false }) { name -> creating = false; manager.createProfile(name)?.let { selId = it.id }; tick++ }
    renaming?.let { p -> NameDialog(L("Rename"), p.name, onDismiss = { renaming = null }) { name -> renaming = null; p.name = name; p.save(); tick++ } }
    if (communityOpen) {
        val items = community
        key(items?.size ?: -1) {
            SimpleActionSheet(
                title = L("Community profiles"),
                actions = items.orEmpty().map { f ->
                    MenuAction(f.removeSuffix(".icp"), {
                        HttpUtils.download("https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/$f") { content ->
                            val ok = content != null && runCatching { manager.importProfile(JSONObject(content)) != null }.getOrDefault(false)
                            (context as? Activity)?.runOnUiThread { toast(context, if (ok) L("Imported %1\$s", f.removeSuffix(".icp")) else L("Could not import %1\$s", f)); tick++ }
                        }
                    })
                },
                message = if (items == null) L("Loading the list…") else if (items.isEmpty()) L("Nothing in the list.") else null,
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
