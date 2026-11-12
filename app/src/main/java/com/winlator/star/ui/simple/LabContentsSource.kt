package com.winlator.star.ui.simple

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.contents.DriverGpuLabel
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.ContentDownloadState
import com.winlator.star.ui.screens.contents.ContentsHubViewModel
import com.winlator.star.ui.screens.contents.ContentsInstaller
import com.winlator.star.ui.screens.contents.ContentsTypes

/**
 * One download in a repository's list or in the search results, as Classic's row: A downloads and installs it
 * (installed: A opens its actions), the error of a failed install under the name, the Adreno GPUs a driver is for
 * and Saved as labels, X for Cancel, Download and replace, and Save the file only (again, once saved).
 */
internal fun catalogEntry(
    context: Context,
    vm: ContentsHubViewModel,
    item: ContentsHubViewModel.CatalogItem,
    st: ContentDownloadState?,
    installed: Boolean,
    saved: Boolean,
    keepRaw: Boolean,
    showSource: Boolean = false,
    group: String? = null,
    onChanged: () -> Unit = {},
): MenuEntry {
    val key = ContentsInstaller.keyFor(item.type, item.sourceName, item.versionName)
    val running = st != null && !st.terminal
    val failed = st != null && st.phase == ContentDownloadPhase.ERROR && !st.cancelled
    val gpu = if (item.isDriver) runCatching { DriverGpuLabel.of(item.displayName, item.fileName) }.getOrNull() else null
    fun install(replace: Boolean = false) =
        ContentsInstaller.install(context, item.type, item.sourceName, item.versionName, item.downloadUrl, keepRaw, replace = replace) {
            vm.refreshStatus(); vm.refreshFolders(); onChanged()
        }
    return MenuEntry(
        key = "d${item.type}-${item.versionName}-${item.downloadUrl}",
        title = item.displayName,
        subtitle = when {
            failed -> st?.error ?: L("Failed")
            st != null && st.phase == ContentDownloadPhase.DONE && st.note != null -> st.note
            else -> listOfNotNull(item.sourceName.takeIf { showSource }, item.versionName, item.publishedAt?.take(10)).joinToString(" · ")
        },
        trailing = when {
            running -> "${(st!!.fraction * 100).toInt()}%"
            installed -> L("Installed")
            failed -> L("Failed")
            item.sizeBytes != null -> "%.0f MB".format(item.sizeBytes / 1_048_576.0)
            else -> null
        },
        pills = listOfNotNull(gpu, if (saved) L("Saved") else null),
        current = installed,
        progress = if (running) st!!.fraction else null,
        icon = Icons.Filled.CloudDownload,
        group = group,
        // Installed: A opens the actions (Download and replace, Save the file only) instead of downloading it again.
        onA = if (installed) null else ({ if (!running) install() }),
        // The download button on the row (Right from the row, or Start), unless it is installed or on its way.
        play = if (installed || running) null else ({ install() }),
        playIcon = Icons.Filled.Download, playLabel = L("Download"),
        actions = buildList {
            if (running) add(MenuAction(L("Cancel the download"), { ContentDownloadRegistry.requestCancel(key) }))
            else {
                // A driver already installed, or refused because one with its name is: download it again over that one.
                if (item.isDriver && (installed || st?.replaceable == true)) add(MenuAction(L("Download and replace"), { install(replace = true) }))
                if (saved) add(MenuAction(L("Save the file again"), { vm.saveArchive(item, force = true) }))
                else add(MenuAction(L("Save the file only"), { vm.saveArchive(item) }))
            }
        },
    )
}

/**
 * The line to show when an install from a file ends, or null for a GPU driver: ContentsInstaller already shows the
 * driver installer's own line (which list it went into, or why not). A failed component says the install's error.
 */
internal fun fileInstallMessage(type: String, name: String, ok: Boolean): String? {
    if (ContentsTypes.isDriver(type)) return null
    if (ok) return L("Installed %1\$s", name)
    val why = ContentDownloadRegistry.get(ContentsInstaller.keyFor(type, "file", name))?.error
    return if (why.isNullOrBlank()) L("Could not install %1\$s", name) else L("Could not install %1\$s: %2\$s", name, why)
}

/** One repository's components: A downloads and installs, with progress on the line; Y reloads the list. */
@Composable
internal fun SimpleComponentSourcePage(index: Int, onBack: () -> Unit, vm: ContentsHubViewModel = viewModel()) {
    val context = LocalContext.current
    val sources by vm.sources.collectAsState()
    val items by vm.detailItems.collectAsState()
    val loading by vm.detailLoading.collectAsState()
    val keepRaw by vm.keepRaw.collectAsState()
    val states by ContentDownloadRegistry.states.collectAsState()
    val installedKeys by vm.installedKeys.collectAsState()
    val savedKeys by vm.savedKeys.collectAsState()
    val source = sources.getOrNull(index)
    LaunchedEffect(source?.name) { if (source != null) vm.selectSource(source) }
    // As Classic's refresh: drops the cached lists and loads this one again.
    val refresh = MenuAction(L("Refresh"), { vm.refreshSelected() })
    val sections = items.groupBy { it.type }.entries.map { (type, list) ->
        MenuSection(
            key = "t$type", title = type, icon = if (type == ContentsTypes.GPU_DRIVERS) Icons.Filled.Memory else Icons.Filled.Extension,
            entries = list.map { item ->
                catalogEntry(
                    context, vm, item,
                    st = states[ContentsInstaller.keyFor(item.type, item.sourceName, item.versionName)],
                    // Read the key sets so the line updates when an install or a save finishes.
                    installed = installedKeys.let { vm.isInstalled(item) },
                    saved = savedKeys.let { vm.isSaved(item) },
                    keepRaw = keepRaw,
                )
            },
            primary = refresh,
        )
    }
    SimpleMenuPage(
        overline = L("Contents"),
        title = source?.name ?: L("Source"),
        sections = sections.ifEmpty {
            listOf(MenuSection("none", L("Components"), Icons.Filled.Extension, emptyList(), empty = if (loading) L("Loading the list…") else L("Nothing to download here."), primary = refresh))
        },
        onBack = onBack,
        banner = states.values.firstOrNull { !it.terminal }?.let { L("Downloading %1\$s · %2\$s%%", it.title, (it.fraction * 100).toInt()) },
    )
}
