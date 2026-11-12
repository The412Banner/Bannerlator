package com.winlator.star.ui.simple

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.deck.DeckActions
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.settings.EditorScope
import com.winlator.star.ui.deck.settings.GraphicsRows
import com.winlator.star.ui.deck.settings.SettingsEnv
import com.winlator.star.ui.screens.contents.ContentsHubViewModel
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

    /** How a list of types reads in a title: "DXVK", "Wine / Proton", "GPU drivers". */
    fun title(types: List<String>): String =
        types.joinToString(" / ") { if (ContentsTypes.isDriver(it)) L("GPU drivers") else it }
}

/**
 * The download pages over a settings page: the repositories that offer [types], then one repository's [types].
 * A full-screen window, so the page under it (and its unsaved changes) stays as it is. B on the first page closes it.
 */
@Composable
internal fun LabDownloadOverlay(types: List<String>, onClose: () -> Unit) {
    var source by remember { mutableStateOf<Int?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DeckDialogBars()
        DeckDialogScaled {
            // The pages' L2/R2 and button legend stay in this window, not the shell's (the page under it keeps its own).
            CompositionLocalProvider(LocalDeckActions provides remember { DeckActions() }) {
                Box(Modifier.fillMaxSize()) {
                    LabWallpaper()
                    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                        val i = source
                        if (i == null) LabDownloadSourcesPage(types, onOpen = { source = it }, onBack = onClose)
                        else SimpleComponentSourcePage(index = i, onBack = { source = null }, onlyTypes = types)
                    }
                }
            }
        }
    }
}

/** The repositories that offer [types], as Lab's Contents lists them. */
@Composable
internal fun LabDownloadSourcesPage(types: List<String>, onOpen: (Int) -> Unit, onBack: () -> Unit, vm: ContentsHubViewModel = viewModel()) {
    val sources by vm.sources.collectAsState()
    val wanted = types.map(ContentsTypes::normalize).toSet()
    val entries = sources.mapIndexedNotNull { i, s ->
        val pills = s.typePills.map { ContentsTypes.normalize(vm.canonicalType(it)) }
        // A repository that names no types may still carry them (as in Classic): listed, its page says when it has none.
        val offers = pills.isEmpty() || pills.any { it in wanted }
        if (!offers) null
        else MenuEntry(
            key = "s$i-${s.name}",
            title = s.name,
            subtitle = s.displayFormat,
            icon = if (s.driverOnly) Icons.Filled.Memory else Icons.Filled.CloudDownload,
            onA = { onOpen(i) },
        )
    }
    SimpleMenuPage(
        overline = L("Download from source"),
        title = LabDownloads.title(types),
        sections = listOf(
            MenuSection("sources", L("Repositories"), Icons.Filled.CloudDownload, entries, empty = L("No repository offers these.")),
        ),
        onBack = onBack,
    )
}
