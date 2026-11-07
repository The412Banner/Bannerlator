package com.winlator.star.ui.deck.settings

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.NewContainerDefaults
import com.winlator.star.core.WinePath
import com.winlator.star.linux.LinuxSettings
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.Screen
import com.winlator.star.ui.deck.isDeckCompact
import com.winlator.star.ui.deck.rememberXmbHome
import com.winlator.star.ui.screens.ContainerDetailViewModel
import com.winlator.star.ui.screens.GameMenuAction
import com.winlator.star.ui.screens.GamesScreenRequests
import com.winlator.star.ui.screens.LogManagerScreen
import com.winlator.star.ui.screens.ShortcutSettingsDialogScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** The Deck settings editor's routes, registered by the Deck shell on top of AppNavGraph's. */
internal object DeckSettingsRoutes {
    const val GAME = "deck_settings_game?path={path}"
    const val CONTAINER = "deck_settings_container/{id}"
    const val DEFAULTS = "deck_settings_defaults"
    const val APP = "deck_settings_app"

    fun game(path: String) = "deck_settings_game?path=" + Uri.encode(path)
    fun container(id: Int) = "deck_settings_container/$id"

    /** Every editor route. They draw their own header, so the shell hides its page bar for them. */
    fun isEditor(route: String?) = route != null && route.startsWith("deck_settings_")

    /** The container and All-containers editors sit under the Containers tab. */
    fun isContainerEditor(route: String?) = route == DEFAULTS || route?.startsWith("deck_settings_container") == true

    /** Where the Deck shell sends the Containers list's Edit and New Container Defaults. Create stays on the classic form. */
    fun containerEditorFor(id: Int): String? = when {
        id > 0 -> container(id)
        id == ContainerDetailViewModel.EDIT_DEFAULTS_ID -> DEFAULTS
        else -> null
    }
}

/**
 * Lets the Games screen (ShortcutsScreen), hosted inside the Deck shell, open a game's Deck editor
 * instead of the classic dialog. Set only while the Deck shell is composed, so the classic shell is
 * unchanged.
 */
object DeckSettingsBus {
    @Volatile
    var openGame: ((String) -> Unit)? = null
}

/** Called once by the Deck shell: routes the Games screen's Settings to the Deck editor while the shell is up. */
@Composable
internal fun BindDeckSettingsBus(navController: NavHostController) {
    DisposableEffect(navController) {
        val open: (String) -> Unit = { path -> navController.navigate(DeckSettingsRoutes.game(path)) { launchSingleTop = true } }
        DeckSettingsBus.openGame = open
        onDispose { if (DeckSettingsBus.openGame === open) DeckSettingsBus.openGame = null }
    }
}

internal fun NavGraphBuilder.deckSettingsRoutes(navController: NavHostController, onAbout: () -> Unit) {
    composable(
        route = DeckSettingsRoutes.GAME,
        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
    ) { entry ->
        DeckGameSettings(entry.arguments?.getString("path").orEmpty(), navController)
    }
    composable(
        route = DeckSettingsRoutes.CONTAINER,
        arguments = listOf(navArgument("id") { type = NavType.IntType }),
    ) { entry ->
        DeckContainerSettings(entry.arguments?.getInt("id") ?: -1, navController)
    }
    composable(DeckSettingsRoutes.DEFAULTS) {
        DeckContainerSettings(ContainerDetailViewModel.EDIT_DEFAULTS_ID, navController)
    }
    composable(DeckSettingsRoutes.APP) {
        DeckAppSettings(navController, onAbout)
    }
}

/** Runs [action] when this destination is shown again, e.g. after the classic editor it opened is closed. */
@Composable
private fun OnReturn(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(action)
    DisposableEffect(owner) {
        var armed = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) armed = true
            if (event == Lifecycle.Event.ON_RESUME && armed) {
                armed = false
                latest()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun Loading(message: String? = null) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        if (message == null) CircularProgressIndicator()
        else Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun archOf(vm: ContainerDetailViewModel) =
    if (vm.isArm64EC) NewContainerDefaults.ARCH_ARM64EC else NewContainerDefaults.ARCH_X86_64

// ── A game ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeckGameSettings(path: String, navController: NavHostController) {
    val context = LocalContext.current
    val loaded = remember(path) {
        runCatching {
            val manager = ContainerManager(context)
            // The Linux Steam client and Android games live in their own homes, which are never in the container list.
            (manager.containers + listOfNotNull(runCatching { manager.linuxContainer }.getOrNull(), runCatching { manager.androidGamesContainer }.getOrNull())).firstNotNullOfOrNull { c ->
                val file = File(path)
                if (file.isFile && file.parentFile?.canonicalPath == c.desktopDir.canonicalPath) Shortcut(c, file) else null
            }
        }.getOrNull()
    }
    if (loaded == null) {
        Loading("This game is no longer in your library.")
        return
    }
    val shortcutState = remember(path) { mutableStateOf(loaded) }
    val containerId = loaded.container.id

    // Reloaded whenever we come back from another editor, since the container may have changed there.
    var generation by remember { mutableIntStateOf(0) }
    val containerVm: ContainerDetailViewModel = viewModel(key = "deck-game-$containerId-c$generation")
    val defaultsVm: ContainerDetailViewModel = viewModel(key = "deck-game-$containerId-d$generation")
    var ready by remember(containerVm) { mutableStateOf(false) }
    LaunchedEffect(containerVm, defaultsVm) {
        containerVm.init(containerId)
        defaultsVm.init(ContainerDetailViewModel.EDIT_DEFAULTS_ID)
        defaultsVm.selectDefaultsArch(archOf(containerVm))
        ready = true
    }
    var showClassic by remember { mutableStateOf(false) }
    val env = remember(containerVm) {
        SettingsEnv(context, EditorScope.GAME, containerVm, shortcutState.value)
    }
    val source = remember(env, defaultsVm) { GameSource(env, shortcutState, containerVm, defaultsVm) }
    val latestSource by rememberUpdatedState(source)
    // The Windows components page: a Wine game only, never the Linux Steam client entry or an Android game.
    val coroutines = rememberCoroutineScope()
    val components = remember(path) {
        if (env.world != World.WINE) null else {
            // The game's .exe on the Android side, for its recommendations (as the classic Win Components tab finds it).
            val exe = runCatching { WinePath.resolveAndroidPath(loaded.container, loaded.path) }.getOrNull()
            DeckComponentsState(context, loaded.container, loaded.name, exe, exe?.parentFile, coroutines) { latestSource.dirty }
        }
    }
    // The Graphics page's downloads and tools: a Wine game only, like the components page.
    val graphics = remember(source) { if (env.world != World.WINE) null else DeckGraphicsState(env, source, coroutines) }
    // The pages' lists, pickers and actions (each section says where it applies).
    val sections = remember(source) { deckSections(env, source, coroutines) }
    val extras = remember(components, graphics, sections) { categoryExtras(components, graphics, sections) }
    LaunchedEffect(components) { components?.load() }
    OnReturn {
        // A picker a section opened hands its result to this form, so it is not reloaded under it.
        val keep = sections.any { it.keepsForm }
        sections.forEach { it.onReturn() }
        if (!source.dirty && !keep) {
            generation++
            source.reloadFrom()
        }
        components?.let { coroutines.launch { it.refreshInstalled() } }
        graphics?.reloadContents()
    }
    if (!ready) {
        Loading()
        return
    }

    val shortcut = shortcutState.value
    val containerName = containerVm.container?.name ?: shortcut.container.name
    // Only a Wine game follows a Wine container: the Linux Steam client entry and Android games are
    // never linked to the container editor.
    val chain = if (env.world != World.WINE) emptyList() else listOf(
        ChainLink("All containers", Icons.Filled.Layers) { navController.navigate(DeckSettingsRoutes.DEFAULTS) },
        ChainLink(containerName, Icons.Filled.Inventory2) { navController.navigate(DeckSettingsRoutes.container(containerId)) },
        ChainLink("This game", Icons.Filled.SportsEsports, null),
    )
    DeckSettingsEditor(
        header = EditorHeader(
            overline = when (env.world) {
                World.LINUX -> "Linux Steam client"
                World.ANDROID -> "Android game"
                World.WINE -> "Game settings"
            },
            title = shortcut.name,
            icon = Icons.Filled.SportsEsports,
            chain = chain,
        ),
        source = source,
        categories = SettingsRegistry.categories,
        defs = SettingsRegistry.defs,
        handoffs = SettingsRegistry.handoffs(EditorScope.GAME, env),
        onExit = { navController.popBackStack() },
        onHandoff = { h ->
            when (h.target) {
                // The classic game editor is never opened for an Android game (it has none).
                SettingsRegistry.TARGET_CLASSIC -> { if (env.world != World.ANDROID) showClassic = true }
                SettingsRegistry.TARGET_DETAILS -> {
                    // Game details can rename the game's files, so this page (holding the old path) closes
                    // and the sheet opens over the page under it (the shell's DeckGameActionsHost).
                    navController.popBackStack()
                    GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.GAME_DETAILS, shortcut.file.path)
                }
                // Same for a new .exe or a copy to drive C: both repoint the entry this page holds.
                SettingsRegistry.TARGET_CHANGE_EXE -> {
                    navController.popBackStack()
                    GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.CHANGE_EXE, shortcut.file.path)
                }
                SettingsRegistry.TARGET_DRIVE_C -> {
                    navController.popBackStack()
                    GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.COPY_TO_DRIVE_C, shortcut.file.path)
                }
                // A move takes the entry out of this container, so this page closes the same way.
                SettingsRegistry.TARGET_MOVE_CONTAINER -> {
                    navController.popBackStack()
                    GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.MOVE_TO_CONTAINER, shortcut.file.path)
                }
                else -> {}
            }
        },
        onChainLink = { link -> link.onClick?.invoke() },
        extras = extras,
    )
    components?.let { DeckComponentsDialogs(it) }
    graphics?.let { DeckGraphicsDialogs(it) }
    sections.forEach { it.Dialogs() }

    if (showClassic) {
        val s = shortcutState.value
        ShortcutSettingsDialogScreen(
            shortcut = s,
            onDismiss = {
                showClassic = false
                source.reloadFrom()
            },
            // The two file operations repoint the game, so this page (holding the old one) closes and their
            // coordinators run over the page under it (the shell's DeckGameActionsHost), as Classic closes its editor first.
            onMoveToDriveC = {
                showClassic = false
                navController.popBackStack()
                GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.COPY_TO_DRIVE_C, s.file.path)
            },
            onChangeExe = {
                showClassic = false
                navController.popBackStack()
                GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.CHANGE_EXE, s.file.path)
            },
            onChangeContainer = {
                showClassic = false
                navController.popBackStack()
                GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.MOVE_TO_CONTAINER, s.file.path)
            },
        )
    }
}

// ── A container, or All containers ──────────────────────────────────────────────────────────────

@Composable
private fun DeckContainerSettings(id: Int, navController: NavHostController) {
    val context = LocalContext.current
    val defaultsMode = id == ContainerDetailViewModel.EDIT_DEFAULTS_ID
    var generation by remember { mutableIntStateOf(0) }
    // All containers keeps a profile per architecture; the one being edited survives a save or discard.
    var arch by rememberSaveable { mutableStateOf(NewContainerDefaults.ARCH_ARM64EC) }
    val vm: ContainerDetailViewModel = viewModel(key = "deck-container-$id-f$generation")
    val defaultsVm: ContainerDetailViewModel? =
        if (defaultsMode) null else viewModel(key = "deck-container-$id-d$generation")
    var ready by remember(vm) { mutableStateOf(false) }
    LaunchedEffect(vm, defaultsVm) {
        vm.init(id)
        if (defaultsMode) vm.selectDefaultsArch(arch)
        defaultsVm?.let { d ->
            d.init(ContainerDetailViewModel.EDIT_DEFAULTS_ID)
            d.selectDefaultsArch(archOf(vm))
        }
        ready = true
    }
    val scope = if (defaultsMode) EditorScope.DEFAULTS else EditorScope.CONTAINER
    val env = remember(vm) { SettingsEnv(context, scope, vm, null) }
    val source = remember(env, defaultsVm) { ContainerFormSource(env, vm, defaultsVm) { generation++ } }
    val latestSource by rememberUpdatedState(source)
    val coroutines = rememberCoroutineScope()
    // The Windows components page's install lists: an existing Wine container only. All containers has no
    // prefix (its page is just the DLL rows), and the Linux Steam client's home is never a Wine container.
    var components by remember { mutableStateOf<DeckComponentsState?>(null) }
    // The Graphics page's downloads and tools: every Wine container and All containers, never the Linux Steam client's home.
    val graphics = remember(source, vm.container) {
        val c = vm.container
        if (!defaultsMode && c != null && LinuxSettings.isLinuxContainer(c)) null else DeckGraphicsState(env, source, coroutines)
    }
    // The pages' lists, pickers and actions (each section says where it applies).
    val sections = remember(source) { deckSections(env, source, coroutines) }
    OnReturn {
        // A picker a section opened (a drive's folder, a wallpaper) hands its result to this form, so it is not reloaded under it.
        val keep = sections.any { it.keepsForm }
        sections.forEach { it.onReturn() }
        if (!source.dirty && !keep) generation++
        components?.let { coroutines.launch { it.refreshInstalled() } }
        graphics?.reloadContents()
    }
    if (!ready) {
        Loading()
        return
    }
    if (!defaultsMode && vm.container == null) {
        Loading("This container no longer exists.")
        return
    }
    val container = vm.container
    LaunchedEffect(id, container != null) {
        if (components == null && !defaultsMode && container != null && !LinuxSettings.isLinuxContainer(container)) {
            val state = DeckComponentsState(context, container, null, null, null, coroutines) { latestSource.dirty }
            components = state
            state.load()
        }
    }
    val extras = remember(components, graphics, sections) { categoryExtras(components, graphics, sections) }

    val header = if (defaultsMode) {
        EditorHeader(
            overline = "Defaults for every container",
            title = "All containers",
            icon = Icons.Filled.Layers,
            subtitle = "New containers start from these values. Existing containers keep their own.",
        )
    } else {
        EditorHeader(
            overline = "Container settings",
            title = vm.container?.name ?: vm.containerName,
            icon = Icons.Filled.Inventory2,
            chain = listOf(
                ChainLink("All containers", Icons.Filled.Layers) { navController.navigate(DeckSettingsRoutes.DEFAULTS) },
                ChainLink("This container", Icons.Filled.Inventory2, null),
            ),
            meta = listOf(vm.selectedWineVersion, if (vm.isArm64EC) "arm64ec" else "x86_64").filter { it.isNotEmpty() }.joinToString(" · "),
        )
    }
    DeckSettingsEditor(
        header = header,
        source = source,
        categories = SettingsRegistry.categories,
        defs = SettingsRegistry.defs,
        handoffs = SettingsRegistry.handoffs(scope, env),
        onExit = { navController.popBackStack() },
        onHandoff = { h -> if (h.target == SettingsRegistry.TARGET_CLASSIC) navController.navigate("container_detail?id=$id") },
        onChainLink = { link -> link.onClick?.invoke() },
        headerExtra = if (!defaultsMode) null else ({
            ArchSwitch(arch) { next ->
                // Switching reloads the form from the other profile, so unsaved edits must be saved or discarded first.
                if (next != arch) {
                    if (source.dirty) {
                        Toast.makeText(context, "Save or discard your changes first.", Toast.LENGTH_SHORT).show()
                    } else {
                        arch = next
                        vm.selectDefaultsArch(next)
                    }
                }
            }
        }),
        extras = extras,
    )
    components?.let { DeckComponentsDialogs(it) }
    graphics?.let { DeckGraphicsDialogs(it) }
    sections.forEach { it.Dialogs() }
}

/**
 * Every section of the game, container and All-containers editors. Each one returns no page items
 * where it doesn't apply (scope, world), so one list serves all three.
 */
private fun deckSections(env: SettingsEnv, source: SettingsSource, scope: CoroutineScope): List<DeckSection> = listOf(
    DeckIconSection(env, source, scope),
    DeckWrapperDetectedSection(env, source, scope),
    DeckPerformanceSection(env, source, scope),
    DeckTranslatorDownloads(env, source, scope),
    DeckReshadeSection(env, source, scope),
    DeckAudioSection(env, source, scope),
    DeckInputSection(env, source, scope),
    DeckOverlaySection(env, source, scope),
    DeckWindowsSection(env, source, scope),
    DeckLinuxSection(env, source, scope),
    DeckEnvVarsSection(env, source, scope),
)

/**
 * What the pages add besides their rows: the Windows components lists, the Graphics page's tools and
 * the sections. An imported wrapper's detected settings sit above the Graphics page's downloads.
 */
private fun categoryExtras(components: DeckComponentsState?, graphics: DeckGraphicsState?, sections: List<DeckSection>): Map<String, CategoryExtra> {
    val own = buildMap<String, CategoryExtra> {
        components?.let { put(SettingsRegistry.CAT_COMPONENTS, it.categoryExtra()) }
        graphics?.let { put("graphics", it.categoryExtra()) }
    }
    val (first, rest) = sections.partition { it is DeckWrapperDetectedSection }
    return mergeExtras(first.map { it.extras() } + listOf(own) + rest.map { it.extras() })
}

@Composable
private fun ArchSwitch(arch: String, onArch: (String) -> Unit) {
    // XMB two-pane layout: the slim top line (see SlimEditorTop), so this is the compact form too.
    val slim = rememberXmbHome() && !isDeckCompact(LocalConfiguration.current)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (slim) 8.dp else 12.dp)) {
        Text("Container type", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = if (slim) 12.5.sp else 14.sp)
        DeckSegmented(
            options = listOf(Opt(NewContainerDefaults.ARCH_ARM64EC, "ARM64EC"), Opt(NewContainerDefaults.ARCH_X86_64, "x86-64")),
            value = arch,
            onPick = onArch,
            slim = slim,
        )
    }
}

// ── App settings ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeckAppSettings(navController: NavHostController, onAbout: () -> Unit) {
    val context = LocalContext.current
    val env = remember { SettingsEnv(context, EditorScope.APP, null, null) }
    val source = remember { AppSource(env) }
    var showLogs by remember { mutableStateOf(false) }
    DeckSettingsEditor(
        header = EditorHeader(
            overline = "Bannerlator",
            title = "Settings",
            icon = Icons.Filled.Settings,
            subtitle = "Every app setting applies straight away.",
        ),
        source = source,
        categories = AppSettingsRegistry.categories,
        defs = AppSettingsRegistry.defs,
        handoffs = AppSettingsRegistry.handoffs,
        onExit = null,
        onHandoff = { h ->
            when (h.target) {
                AppSettingsRegistry.TARGET_APPEARANCE -> navController.navigate(Screen.Appearance.route)
                AppSettingsRegistry.TARGET_LOGS -> showLogs = true
                AppSettingsRegistry.TARGET_ABOUT -> onAbout()
                AppSettingsRegistry.TARGET_INPUT_CONTROLS -> navController.navigate(Screen.InputControls.route)
                AppSettingsRegistry.TARGET_CONTENTS -> navController.navigate(Screen.Contents.route)
                AppSettingsRegistry.TARGET_DEFAULTS -> navController.navigate(DeckSettingsRoutes.DEFAULTS)
                else -> navController.navigate(Screen.Settings.route)
            }
        },
    )
    if (showLogs) {
        Dialog(onDismissRequest = { showLogs = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            DeckDialogBars()
            DeckDialogScaled {
                LogManagerScreen(onClose = { showLogs = false })
            }
        }
    }
}
