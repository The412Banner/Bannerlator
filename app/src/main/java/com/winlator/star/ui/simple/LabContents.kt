package com.winlator.star.ui.simple

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.LinuxVulkanDriverManager
import com.winlator.star.contents.WaylandGameDriverManager
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.contents.AddRepoDialog
import com.winlator.star.ui.screens.contents.ContentsHubViewModel
import com.winlator.star.ui.screens.contents.ContentsInstaller
import com.winlator.star.ui.screens.contents.ContentsTypes
import com.winlator.star.ui.screens.contents.HubSource
import com.winlator.star.ui.screens.contents.LinuxRuntimeTab
import com.winlator.star.ui.screens.contents.SetAsDefault
import com.winlator.star.ui.screens.contents.SetAsDefaultSheet
import com.winlator.star.ui.screens.contents.shareArchive
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A source's type as Classic names it (catalogs list some in lower case, FEX under two names). */
private fun typeName(t: String): String = when (t.lowercase()) {
    "dxvk" -> "DXVK"; "d7vk" -> "D7VK"; "vkd3d" -> "VKD3D"; "box64" -> "Box64"; "wowbox64" -> "WOWBox64"
    "fex", "fexcore" -> "FEXCore"; "wine" -> "Wine"; "proton" -> "Proton"; "box64ec" -> "Box64EC"; "vegas" -> "VEGAS"
    else -> if (ContentsTypes.isDriver(t)) L("GPU drivers") else t
}

/**
 * Lab's Contents: the Classic Contents screen's four tabs as Lab lists, on the same data and actions
 * (ContentsHubViewModel, ContentsInstaller, the three driver managers, SetAsDefault, LinuxRuntimeTab).
 * Download components: search across the repositories, every repository with the types it offers (one opens its
 * list, SimpleComponentSourcePage), and the list's import / export, save location and restore.
 * Installed: GPU drivers (Windows, Wayland game, Linux runtime) and components, the defaults marked per architecture.
 * My files: saved archives to reinstall, share or delete. Linux runtime: Classic's own tab.
 * [startSection] is the tab opened first ("download", "installed", "files", "linux").
 */
@Composable
internal fun SimpleContentsPage(onNavigate: (String) -> Unit, onBack: () -> Unit, startSection: String? = null, vm: ContentsHubViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by vm.sources.collectAsState()
    val saved by vm.savedFolders.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.searchResults.collectAsState()
    val searching by vm.searching.collectAsState()
    val installedKeys by vm.installedKeys.collectAsState()
    val savedKeys by vm.savedKeys.collectAsState()
    val keepRaw by vm.keepRaw.collectAsState()
    val baseDisplay by vm.baseDisplay.collectAsState()
    val states by ContentDownloadRegistry.states.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    OnResume { vm.refreshStatus(); vm.refreshFolders(); tick++ }
    // A download started on a repository's page can end after this page is back: read the lists again when one does.
    val finished = states.filterValues { it.phase == ContentDownloadPhase.DONE }.keys
    LaunchedEffect(finished) { if (finished.isNotEmpty()) { vm.refreshStatus(); vm.refreshFolders(); tick++ } }
    var defaultItem by remember { mutableStateOf<SetAsDefault.Item?>(null) }
    var outcome by remember { mutableStateOf<SetAsDefault.Outcome?>(null) }
    var addRepo by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var restoreAsk by remember { mutableStateOf(false) }

    val adreno = remember { (context as? Activity)?.let { runCatching { AdrenotoolsManager(it) }.getOrNull() } }
    val wayland = remember { runCatching { WaylandGameDriverManager(context) }.getOrNull() }
    val linux = remember { runCatching { LinuxVulkanDriverManager(context) }.getOrNull() }
    val cm = remember(tick) { runCatching { ContentsManager(context).apply { syncContents() } }.getOrNull() }

    // Install from a file, as in Classic: .wcp / .tzst go to the component pipeline, anything else is a GPU driver.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: InAppFilePicker.pickedUri(result.data) ?: return@rememberLauncherForActivityResult
        val name = displayNameOf(context, uri)
        val lower = name.lowercase()
        val type = if (lower.endsWith(".wcp") || lower.endsWith(".tzst")) "" else ContentsTypes.GPU_DRIVERS
        toast(context, L("Installing %1\$s…", name))
        ContentsInstaller.installFromFile(context, type, name, uri) { ok ->
            val msg = fileInstallMessage(type, name, ok)
            (context as? Activity)?.runOnUiThread { msg?.let { toast(context, it) }; vm.refreshStatus(); vm.refreshFolders(); tick++ }
        }
    }
    val fromFile = { picker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.WCP + InAppFilePicker.DRIVER, L("Select content or driver"))) }

    // The repository list as Classic's Import / export and Save location dialogs do it, on the in-app file manager.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: InAppFilePicker.pickedUri(result.data) ?: return@rememberLauncherForActivityResult
        val json = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        toast(context, if (json != null && vm.importRepoListJson(json, merge = true)) L("Repositories imported") else L("That file is not a repository list"))
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        InAppFilePicker.pickedPath(result.data)?.let { path -> vm.library.setFileBase(path); vm.refreshBase(); vm.refreshFolders() }
    }
    fun exportList() = runCatching {
        val share = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_TEXT, vm.exportRepoListJson()) }
        context.startActivity(Intent.createChooser(share, L("Export repositories")))
    }
    // Classic's refresh: the source list read again and the cached lists dropped, so the next open loads them fresh.
    fun refreshLists() {
        vm.reloadSources()
        vm.refreshSelected()
        if (query.isNotBlank()) vm.setQuery(query) else toast(context, L("Refreshed"))
    }
    fun openPage(s: HubSource) {
        val url = vm.browseUrl(s)
        if (url.isNotBlank()) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // ── Download components ──
    val download: List<MenuEntry> = if (query.isNotBlank()) buildList {
        // Search across every repository (Classic's search field): the results, grouped by type.
        add(
            MenuEntry(
                "search", query,
                when { searching -> L("Searching…"); results.size == 1 -> L("1 result"); else -> L("%1\$s results", results.size) },
                icon = Icons.Filled.Search,
                onA = { searchOpen = true },
                actions = listOf(MenuAction(L("Search again"), { searchOpen = true }), MenuAction(L("Clear the search"), { vm.setQuery("") })),
            ),
        )
        if (!searching) results.groupBy { it.type }.values.flatten().forEach { item ->
            add(
                catalogEntry(
                    context, vm, item,
                    st = states[ContentsInstaller.keyFor(item.type, item.sourceName, item.versionName)],
                    installed = installedKeys.let { vm.isInstalled(item) },
                    saved = savedKeys.let { vm.isSaved(item) },
                    keepRaw = keepRaw, showSource = true, group = typeName(item.type),
                ) { tick++ },
            )
        }
    } else {
        val repos = sources.mapIndexed { i, s ->
            MenuEntry(
                key = "s$i-${s.name}",
                title = s.name,
                subtitle = s.displayFormat,
                pills = s.typePills.map(::typeName).distinct(),
                icon = if (s.driverOnly) Icons.Filled.Memory else Icons.Filled.CloudDownload,
                group = if (s.driverOnly) L("GPU drivers") else L("Components"),
                onA = { onNavigate(SimpleRoutes.componentSource(i)) },
                actions = buildList {
                    add(MenuAction(L("Browse"), { onNavigate(SimpleRoutes.componentSource(i)) }))
                    if (vm.browseUrl(s).isNotBlank()) add(MenuAction(L("Open source page"), { openPage(s) }))
                    add(
                        MenuAction(
                            if (s.removeIsHide) L("Hide this repository") else L("Remove this repository"),
                            { vm.removeSource(s) }, danger = true,
                            confirm = if (s.removeIsHide) L("Hide %1\$s? Restore default repositories shows it again.", s.name) else L("Remove %1\$s?", s.name),
                        ),
                    )
                },
            )
        }.sortedBy { if (it.group == L("Components")) 0 else 1 }
        listOf(
            MenuEntry("search", L("Search all repositories"), icon = Icons.Filled.Search, onA = { searchOpen = true }),
            MenuEntry("refresh", L("Refresh the lists"), icon = Icons.Filled.Refresh, onA = { refreshLists() }),
        ) + repos + listOf(
            MenuEntry("fromfile", L("Install content from file"), icon = Icons.Filled.FolderOpen, primary = true, onA = fromFile),
            MenuEntry(
                "io", L("Import or export the list"), icon = Icons.Filled.ImportExport, group = L("Manage"),
                actions = listOf(
                    MenuAction(L("Import a list from a file"), { importer.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.JSON, L("Select repository list"))) }),
                    MenuAction(L("Export the list"), { exportList() }),
                ),
            ),
            MenuEntry(
                "location", L("Save location"), baseDisplay, icon = Icons.Filled.FolderSpecial, group = L("Manage"),
                actions = listOf(
                    MenuAction(L("Downloads › bannerlator (default)"), { vm.library.setDefaultBase(); vm.refreshBase(); vm.refreshFolders() }),
                    MenuAction(L("App-private storage"), { vm.library.setFileBase(vm.library.appPrivateBasePath()); vm.refreshBase(); vm.refreshFolders() }),
                    MenuAction(L("Choose another folder…"), {
                        folderPicker.launch(InAppFilePicker.buildDirIntent(context, title = L("Select save folder"), initialDir = vm.library.currentFileBasePath()))
                    }),
                ),
            ),
            MenuEntry("restore", L("Restore default repositories"), icon = Icons.Filled.Restore, group = L("Manage"), onA = { restoreAsk = true }),
        )
    }

    // ── Installed ──
    fun defaultArchs(item: SetAsDefault.Item?): List<String> = item?.let { i ->
        listOf(SetAsDefault.ARM64EC, SetAsDefault.X86_64).filter { a -> a in i.archs && SetAsDefault.currentDefault(context, a, i.kind) == i.value }
    }.orEmpty()
    // Classic's gold badges: which architecture's new containers it is the default for.
    fun defaultLabel(archs: List<String>): String? =
        if (archs.isEmpty()) null else L("Default · %1\$s", archs.joinToString(", ") { SetAsDefault.archLabel(it) })
    val installed = remember(tick, cm) {
        buildList {
            runCatching { adreno?.enumarateInstalledDrivers()?.toList() }.getOrNull().orEmpty().forEach { id ->
                val name = adreno?.getDriverName(id) ?: id
                val item = SetAsDefault.driverItem(id, name)
                val archs = defaultArchs(item)
                add(
                    MenuEntry(
                        key = "d$id", title = name, group = L("GPU drivers"),
                        subtitle = listOfNotNull(adreno?.getDriverVersion(id), adreno?.getDriverVendor(id)).filter { it.isNotBlank() }.joinToString(" · "),
                        trailing = defaultLabel(archs), current = archs.isNotEmpty(),
                        icon = Icons.Filled.Memory,
                        actions = listOf(
                            MenuAction(L("Set as default…"), { defaultItem = item }),
                            MenuAction(L("Remove"), { adreno?.removeDriver(id); tick++; toast(context, L("Removed %1\$s", name)) }, danger = true, confirm = L("Remove %1\$s? Games set to it go back to their container's driver.", name)),
                        ),
                    ),
                )
            }
            runCatching { wayland?.enumerateInstalledDrivers() }.getOrNull().orEmpty().forEach { id ->
                val name = wayland?.getDriverName(id) ?: id
                // As Classic: a build without the Wayland WSI is marked.
                val noWsi = runCatching { wayland?.hasWaylandWsi(id) }.getOrNull() == false
                add(
                    MenuEntry(
                        key = "w$id", title = name, group = L("Wayland game drivers"),
                        subtitle = listOfNotNull(wayland?.getDriverVersion(id)?.takeIf { it.isNotBlank() }, if (noWsi) L("No Wayland WSI detected") else null).joinToString(" · ").ifBlank { null },
                        icon = Icons.Filled.Memory,
                        actions = listOf(MenuAction(L("Remove"), { wayland?.removeDriver(id); tick++; toast(context, L("Removed %1\$s", name)) }, danger = true, confirm = L("Remove %1\$s?", name))),
                    ),
                )
            }
            runCatching { linux?.enumerateInstalledDrivers() }.getOrNull().orEmpty().forEach { id ->
                val name = linux?.getDriverName(id) ?: id
                val glibc = runCatching { linux?.getMinGlibc(id) }.getOrNull()?.takeIf { it.isNotBlank() }
                add(
                    MenuEntry(
                        key = "l$id", title = name, group = L("Linux runtime drivers"),
                        subtitle = listOfNotNull(linux?.getDriverVersion(id)?.takeIf { it.isNotBlank() }, glibc?.let { "glibc $it+" }).joinToString(" · ").ifBlank { null },
                        icon = Icons.Filled.Memory,
                        actions = listOf(MenuAction(L("Remove"), { linux?.removeDriver(id); tick++; toast(context, L("Removed %1\$s", name)) }, danger = true, confirm = L("Remove %1\$s?", name))),
                    ),
                )
            }
            ContentProfile.ContentType.values().forEach { t ->
                val label = TYPE_LABELS[t.name] ?: t.name
                cm?.getProfiles(t).orEmpty().filter { it.remoteUrl == null }.forEach { p ->
                    val item = cm?.let { m -> runCatching { SetAsDefault.componentItem(context, m, p) }.getOrNull() }
                    val archs = defaultArchs(item)
                    add(
                        MenuEntry(
                            key = "i${t.name}-${p.verName}-${p.verCode}", title = "$label ${p.verName}", group = label,
                            subtitle = p.desc?.takeIf { it.isNotBlank() },
                            trailing = defaultLabel(archs), current = archs.isNotEmpty(),
                            icon = Icons.Filled.Extension,
                            actions = buildList {
                                if (item != null) add(MenuAction(L("Set as default…"), { defaultItem = item }))
                                add(MenuAction(L("Remove"), {
                                    runCatching { cm?.removeContent(p); cm?.syncContents() }
                                    vm.refreshStatus(); tick++
                                    toast(context, L("Removed %1\$s %2\$s", label, p.verName))
                                }, danger = true, confirm = L("Remove %1\$s %2\$s? Containers that use it fall back to their other choice.", label, p.verName)))
                            },
                        ),
                    )
                }
            }
        }
    }

    // ── My files ──
    val files = saved.entries.flatMap { (folder, list) ->
        list.map { f ->
            val reinstall = {
                toast(context, L("Installing %1\$s…", f.name))
                ContentsInstaller.installFromFile(context, f.type, f.name, f.uri) { ok ->
                    val msg = fileInstallMessage(f.type, f.name, ok)
                    (context as? Activity)?.runOnUiThread { if (ok) vm.markInstalledFromSaved(f); msg?.let { toast(context, it) }; vm.refreshStatus(); tick++ }
                }
            }
            MenuEntry(
                key = "f${f.uri}", title = f.name, group = folder.ifBlank { typeName(f.type) },
                subtitle = L("%.1f MB · saved").format(f.sizeBytes / 1_048_576.0),
                icon = Icons.Filled.Save,
                onA = reinstall, play = reinstall, playIcon = Icons.Filled.Download, playLabel = L("Reinstall"),
                actions = listOf(
                    MenuAction(L("Reinstall"), reinstall),
                    MenuAction(L("Share"), { shareArchive(context, f) }),
                    MenuAction(L("Delete the saved file"), { vm.deleteSaved(f) }, danger = true, confirm = L("Delete %1\$s?", f.name)),
                ),
            )
        }
    }

    SimpleMenuPage(
        overline = L("Manage"),
        title = L("Contents"),
        sections = listOf(
            MenuSection("download", L("Download components"), Icons.Filled.Download, download, empty = L("No repositories."), primary = MenuAction(L("Add a repository"), { addRepo = true })),
            MenuSection("installed", L("Installed"), Icons.Filled.CheckCircle, installed, empty = L("Only the built-in components so far."), primary = MenuAction(L("Install content from file"), { fromFile() })),
            MenuSection("files", L("My files"), Icons.Filled.Save, files, empty = L("No saved files. Use ⋯ › Save the file only on a download.")),
            MenuSection("linux", L("Linux runtime"), Icons.Filled.Terminal, emptyList(), content = { LinuxRuntimeTab() }),
        ),
        // B ends a search first.
        onBack = { if (query.isNotBlank()) vm.setQuery("") else onBack() },
        banner = states.values.firstOrNull { !it.terminal }?.let { L("Downloading %1\$s · %2\$s%%", it.title, (it.fraction * 100).toInt()) },
        startSection = startSection,
    )
    defaultItem?.let { item ->
        SetAsDefaultSheet(item, onDismiss = { defaultItem = null }) { out -> defaultItem = null; tick++; outcome = out }
    }
    // What Set as default did (running containers skipped, failures), with Classic's Undo.
    outcome?.let { o ->
        SimpleActionSheet(
            title = L("Set as default"),
            actions = listOfNotNull(
                MenuAction(L("OK"), {}),
                if (o.canUndo) MenuAction(L("Undo"), {
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) { SetAsDefault.undo(context.applicationContext) }
                        tick++
                        toast(context, msg)
                    }
                }) else null,
            ),
            onDismiss = { outcome = null },
            message = o.message,
        ) { a -> outcome = null; a.run() }
    }
    if (addRepo) AddRepoDialog(vm, onDismiss = { addRepo = false })
    if (searchOpen) SearchDialog(query, onDismiss = { searchOpen = false }) { q -> searchOpen = false; vm.setQuery(q) }
    if (restoreAsk) {
        SimpleConfirm(L("Restore default repositories"), L("Hidden built-in repositories come back. Yours stay."), danger = false, onDismiss = { restoreAsk = false }) {
            restoreAsk = false
            vm.restoreDefaultSources()
        }
    }
}

/** The search over every repository: what to look for; Search (or the keyboard's search key) runs it. */
@Composable
private fun SearchDialog(initial: String, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSearch(text.trim()) }) { Text(L("Search")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel")) } },
        title = { Text(L("Search all repositories")) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
    )
}
