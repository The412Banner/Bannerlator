package com.winlator.star.ui.simple

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.ui.deck.settings.EditorScope
import com.winlator.star.ui.deck.settings.GraphicsRows
import com.winlator.star.ui.deck.settings.SettingsEnv
import com.winlator.star.ui.screens.contents.ContentsHubViewModel
import com.winlator.star.ui.screens.contents.ContentsInstaller
import com.winlator.star.ui.screens.contents.ContentsTypes
import kotlinx.coroutines.flow.first

/**
 * "+ Download from source" in Lab's setting pickers: which settings offer it, the Contents types their versions come
 * from, and reading a setting's list again after a download (the reloads Deck's download sheets trigger).
 */
internal object LabDownloads {
    private const val WINE = "windows.wineVersion"
    private const val BOX64 = "performance.box64Version"
    private const val FEX = "performance.fexVersion"
    private const val D7VK = "graphics.dx.d7vkVersion"
    private const val COMPOSITOR = "graphics.compositorDriver"
    private const val WAYLAND_GAME = "graphics.wl.gameDriver"
    private const val BOX64EC = "performance.box64ecVersion"

    /**
     * The Contents types (ContentsTypes names) [id]'s versions come from, or null when it has nothing to download.
     * The X11 "Graphics driver" lists Vulkan wrappers, not drivers: its Turnip is "Driver version".
     */
    fun typesFor(id: String, env: SettingsEnv): List<String>? = when (id) {
        WINE -> listOf("Wine", "Proton")
        GraphicsRows.ID_DXVK -> listOf("DXVK")
        GraphicsRows.ID_VKD3D -> listOf("VKD3D")
        D7VK -> listOf("D7VK")
        BOX64 -> listOf(if (env.arm64ec) "WOWBox64" else "Box64")
        FEX -> listOf("FEXCore")
        BOX64EC -> if (env.arm64ec) listOf("Box64EC") else null
        GraphicsRows.ID_DRIVER_VERSION, COMPOSITOR, WAYLAND_GAME -> listOf(ContentsTypes.GPU_DRIVERS)
        else -> null
    }

    /**
     * The component type whose full Lab download list [id] opens instead of the repository picker: Box64EC comes from the
     * component catalog (the same list as Translator downloads), which the repository picker doesn't offer.
     */
    fun catalogTypeFor(id: String): com.winlator.star.contents.ContentProfile.ContentType? = when (id) {
        BOX64EC -> com.winlator.star.contents.ContentProfile.ContentType.CONTENT_TYPE_BOX64EC
        else -> null
    }

    /** Reads [id]'s list of versions again, e.g. after a download. */
    suspend fun refresh(id: String, env: SettingsEnv) {
        val vm = env.vm
        when (id) {
            WINE -> vm?.refreshWineVersions()
            GraphicsRows.ID_DXVK, GraphicsRows.ID_VKD3D, D7VK -> env.graphics.reloadContents(env.arm64ec)
            BOX64 -> vm?.refreshBox64Versions()
            FEX -> vm?.refreshFEXCoreVersions()
            BOX64EC -> vm?.refreshBox64ECVersions()
            GraphicsRows.ID_DRIVER_VERSION, COMPOSITOR, WAYLAND_GAME -> {
                // As Deck after a Turnip install: only a container form looks for a usable compositor driver again.
                if (env.scope != EditorScope.GAME) vm?.onCompositorDriversChanged()
                env.loadAsync()
                env.graphics.reloadDrivers()
                env.graphics.reloadWaylandDrivers()
            }
        }
    }

    /** True while a component download or install is running. */
    fun downloading(): Boolean = ContentDownloadRegistry.states.value.values.any { !it.terminal }

    /** Returns once no component download or install is running. */
    suspend fun awaitDownloads() {
        ContentDownloadRegistry.states.first { m -> m.values.none { !it.terminal } }
    }
}

/**
 * "+ Download from source" as a Lab list over the picker it came from: the repositories that offer [types] (as in
 * Classic, one that names no types is listed too), the installed versions of [types] (info and remove, as Classic's
 * download sheet) and Install from a file; then one repository's versions of [types]. A on a version downloads and
 * installs it with the progress on its line; once that install (or one from a file) finished [onClose] runs, which
 * reads the setting's list again and brings its picker back. B steps back to the repositories, then closes.
 */
@Composable
internal fun LabDownloadPicker(types: List<String>, onClose: () -> Unit, vm: ContentsHubViewModel = viewModel()) {
    val context = LocalContext.current
    val sources by vm.sources.collectAsState()
    val wanted = remember(types) { types.map(ContentsTypes::normalize).toSet() }
    // A repository's index, or INSTALLED for the installed versions.
    var picked by remember { mutableStateOf<Int?>(null) }
    // The download started here, and while it runs, the "Cancel download" line on both lists.
    var started by remember { mutableStateOf<String?>(null) }
    val states by ContentDownloadRegistry.states.collectAsState()
    val running = started?.takeIf { states[it]?.terminal == false }
    val cancelLine = running?.let { k ->
        DlLine("__cancel__", L("Cancel download"), sub = states[k]?.title, accent = true, onA = { ContentDownloadRegistry.requestCancel(k) })
    }
    val driver = types.any { ContentsTypes.isDriver(it) }
    val fileInstall = rememberLabFileInstall(driver = driver) { vm.refreshStatus(); vm.refreshFolders(); onClose() }
    val profileTypes = remember(types) { types.mapNotNull { ContentsTypes.contentTypeFor(it) } }
    val source = picked?.takeIf { it >= 0 }?.let { sources.getOrNull(it) }

    if (picked == INSTALLED) {
        LabInstalledVersions(profileTypes, onBack = { picked = null })
        return
    }
    if (source == null) {
        val repos = sources.mapIndexedNotNull { i, s ->
            val pills = s.typePills.map { ContentsTypes.normalize(vm.canonicalType(it)) }
            if (pills.isEmpty() || pills.any { it in wanted }) DlLine("r$i", s.name, onA = { picked = i }) else null
        }
        LabDownloadList(
            title = L("Download from source"),
            lines = buildList<DlLine> {
                if (repos.isEmpty()) add(DlLine("__none__", L("No repository offers these."), enabled = false)) else addAll(repos)
                if (profileTypes.isNotEmpty()) add(DlLine("__installed__", L("Installed versions"), onA = { picked = INSTALLED }))
                add(fileInstall.line())
                cancelLine?.let { add(it) }
            },
            onDismiss = onClose,
        )
        return
    }

    val items by vm.detailItems.collectAsState()
    val loading by vm.detailLoading.collectAsState()
    val keepRaw by vm.keepRaw.collectAsState()
    val installedKeys by vm.installedKeys.collectAsState()
    LaunchedEffect(source.name) { vm.selectSource(source) }
    val shown = if (loading) emptyList() else items.filter { ContentsTypes.normalize(it.type) in wanted }
    // The install started here finished: back to the setting's picker, which lists it now.
    val finished = started?.let { states[it] }?.phase == ContentDownloadPhase.DONE
    LaunchedEffect(finished) { if (finished) onClose() }
    val lines = shown.map { item ->
        val key = ContentsInstaller.keyFor(item.type, item.sourceName, item.versionName)
        val st = states[key]
        val busy = st != null && !st.terminal
        // Read the key set so the line updates when an install finishes.
        val installed = installedKeys.let { vm.isInstalled(item) }
        DlLine(
            key = key,
            title = item.displayName,
            sub = if (installed && !busy) L("Installed") else null,
            trailing = when {
                busy -> "${(st!!.fraction * 100).toInt()}%"
                st != null && st.phase == ContentDownloadPhase.ERROR && !st.cancelled -> L("Failed")
                else -> null
            },
            progress = if (busy) st!!.fraction else null,
            onA = pick@{
                // One download at a time from here; another line waits until it finished or was cancelled.
                if (running != null || states[key]?.terminal == false) return@pick
                ContentsInstaller.install(context, item.type, item.sourceName, item.versionName, item.downloadUrl, keepRaw) {
                    vm.refreshStatus(); vm.refreshFolders()
                }
                started = key
            },
        )
    }
    LabDownloadList(
        title = source.name,
        lines = buildList<DlLine> {
            if (lines.isEmpty()) add(DlLine("__none__", if (loading) L("Loading the list…") else L("Nothing to download here."), enabled = false))
            addAll(lines)
            cancelLine?.let { add(it) }
        },
        onDismiss = { picked = null },
    )
}

private const val INSTALLED = -1

/**
 * The installed versions of [types] (Classic's download sheet lists them with the downloads): A or X shows Info and
 * Remove, as there. B goes back.
 */
@Composable
private fun LabInstalledVersions(types: List<ContentProfile.ContentType>, onBack: () -> Unit) {
    val context = LocalContext.current
    val cm = remember { ContentsManager(context) }
    var tick by remember { mutableIntStateOf(0) }
    val installed = remember(tick, types) {
        runCatching { cm.syncContents(); types.associateWith { t -> cm.getProfiles(t).orEmpty().filter { it.remoteUrl == null } } }.getOrDefault(emptyMap())
    }
    var info by remember { mutableStateOf<ContentProfile?>(null) }
    val lines = buildList<DlLine> {
        installed.forEach { (t, list) ->
            if (list.isEmpty()) return@forEach
            add(DlLine("__h$t", t.toString(), trailing = list.size.toString(), header = true))
            list.forEach { p ->
                add(
                    DlLine(
                        key = "$t|${ContentsManager.getEntryName(p)}",
                        title = p.verName ?: ContentsManager.getEntryName(p),
                        sub = listOfNotNull(if (p.verCode > 0) "v${p.verCode}" else null, p.desc?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null },
                        actions = listOf(
                            MenuAction(L("Info"), { info = p }),
                            MenuAction(L("Remove"), {
                                cm.removeContent(p)
                                cm.syncContents()
                                tick++
                            }, danger = true, confirm = L("Remove \"%1\$s\"?", p.verName)),
                        ),
                    ),
                )
            }
        }
        if (isEmpty()) add(DlLine("__none__", L("Nothing installed yet."), enabled = false))
    }
    LabDownloadList(title = L("Installed versions"), lines = lines, onDismiss = onBack)
    info?.let { p -> LabNote(L("Content info"), contentInfoText(p)) { info = null } }
}
