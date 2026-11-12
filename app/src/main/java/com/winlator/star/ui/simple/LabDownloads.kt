package com.winlator.star.ui.simple

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.ui.deck.settings.EditorScope
import com.winlator.star.ui.deck.settings.GraphicsRows
import com.winlator.star.ui.deck.settings.Opt
import com.winlator.star.ui.deck.settings.SettingsEnv
import com.winlator.star.ui.deck.settings.SimplePicker
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
        GraphicsRows.ID_DRIVER_VERSION, COMPOSITOR, WAYLAND_GAME -> listOf(ContentsTypes.GPU_DRIVERS)
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
 * "+ Download from source" as the same picker it came from: the repositories that offer [types] (as in Classic, one
 * that names no types is listed too), then one repository's versions of [types]. A on a version downloads and installs
 * it with the progress on its line; once that install finished [onClose] runs, which reads the setting's list again and
 * brings its picker back. B steps back to the repositories, then closes.
 */
@Composable
internal fun LabDownloadPicker(types: List<String>, onClose: () -> Unit, vm: ContentsHubViewModel = viewModel()) {
    val context = LocalContext.current
    val sources by vm.sources.collectAsState()
    val wanted = remember(types) { types.map(ContentsTypes::normalize).toSet() }
    var picked by remember { mutableStateOf<Int?>(null) }
    // The download started here, and while it runs, the "Cancel download" line on both lists.
    var started by remember { mutableStateOf<String?>(null) }
    val states by ContentDownloadRegistry.states.collectAsState()
    val running = started?.takeIf { states[it]?.terminal == false }
    val cancelLabel = if (running != null) L("Cancel download") else null
    val cancel: () -> Unit = { running?.let { ContentDownloadRegistry.requestCancel(it) } }
    val source = picked?.let { sources.getOrNull(it) }
    if (source == null) {
        val repos = sources.mapIndexedNotNull { i, s ->
            val pills = s.typePills.map { ContentsTypes.normalize(vm.canonicalType(it)) }
            if (pills.isEmpty() || pills.any { it in wanted }) Opt(i.toString(), s.name) else null
        }
        SimplePicker(
            title = L("Download from source"),
            options = repos.ifEmpty { listOf(Opt("", L("No repository offers these."))) },
            current = "",
            customLabel = cancelLabel,
            onDismiss = onClose,
            onPick = { v -> v.toIntOrNull()?.let { picked = it } },
            onCustom = cancel,
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
    val options = shown.map { item ->
        val key = ContentsInstaller.keyFor(item.type, item.sourceName, item.versionName)
        val st = states[key]
        val busy = st != null && !st.terminal
        // Read the key set so the line updates when an install finishes.
        val installed = installedKeys.let { vm.isInstalled(item) }
        val label = when {
            busy -> "${item.displayName} · ${(st!!.fraction * 100).toInt()}%"
            st != null && st.phase == ContentDownloadPhase.ERROR && !st.cancelled -> "${item.displayName} · ${L("Failed")}"
            else -> item.displayName
        }
        Opt(key, label, if (installed && !busy) L("Installed") else null)
    }
    SimplePicker(
        title = source.name,
        options = options.ifEmpty { listOf(Opt("", if (loading) L("Loading the list…") else L("Nothing to download here."))) },
        current = "",
        customLabel = cancelLabel,
        onDismiss = { picked = null },
        onPick = pick@{ key ->
            val item = shown.firstOrNull { ContentsInstaller.keyFor(it.type, it.sourceName, it.versionName) == key } ?: return@pick
            // One download at a time from here; another line waits until it finished or was cancelled.
            if (running != null || states[key]?.terminal == false) return@pick
            ContentsInstaller.install(context, item.type, item.sourceName, item.versionName, item.downloadUrl, keepRaw) {
                vm.refreshStatus(); vm.refreshFolders()
            }
            started = key
        },
        onCustom = cancel,
    )
}
