package com.winlator.star.ui.simple

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.R
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.DriverInstaller
import com.winlator.star.contents.LinuxVulkanDriverManager
import com.winlator.star.contents.WaylandGameDriverManager
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.ui.screens.contents.ContentsHubViewModel
import com.winlator.star.ui.screens.contents.ContentsInstaller
import com.winlator.star.ui.screens.contents.ContentsTypes
import com.winlator.star.ui.screens.contents.HubSource
import com.winlator.star.ui.screens.contents.RemoteSourceRepository
import com.winlator.star.ui.screens.contents.SetAsDefault
import com.winlator.star.ui.screens.contents.shareArchive
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * True when [uri] is a zip with a libvulkan_freedreno*.so in it, as the Wayland and Linux driver lists take. Reads the
 * zip: call off the main thread.
 */
private fun hasFreedrenoLib(context: android.content.Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        java.util.zip.ZipInputStream(input).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val n = java.io.File(e.name).name
                if (!e.isDirectory && n.startsWith("libvulkan_freedreno") && n.endsWith(".so")) return@runCatching true
                e = zis.nextEntry
            }
        }
    }
    false
}.getOrDefault(false)

/** A source's type as Classic names it (catalogs list some in lower case, FEX under two names). */
private fun typeName(t: String): String = when (t.lowercase()) {
    "dxvk" -> "DXVK"; "d7vk" -> "D7VK"; "vkd3d" -> "VKD3D"; "box64" -> "Box64"; "wowbox64" -> "WOWBox64"
    "fex", "fexcore" -> "FEXCore"; "wine" -> "Wine"; "proton" -> "Proton"; "box64ec" -> "Box64EC"; "vegas" -> "VEGAS"
    else -> if (ContentsTypes.isDriver(t)) L("GPU drivers") else t
}

/**
 * Lab's Contents: the Classic Contents screen's four tabs as Lab lists, on the same data and actions
 * (ContentsHubViewModel, ContentsInstaller, the three driver managers, SetAsDefault, LinuxRuntimeTab's calls).
 * Download components: search across the repositories, every repository with the types it offers (one opens its
 * list, SimpleComponentSourcePage), hidden built-in repositories to list again one by one, and the list's import /
 * export, save location and restore.
 * Installed: GPU drivers (Windows, Wayland game, Linux runtime, each with its own install / import) and components, the
 * defaults marked per architecture. My files: saved archives to reinstall, share or delete. Linux runtime: the runtime,
 * its warnings and the Proton builds (LabContentsLinux.kt). Installs from a file show Lab's progress pop-up with Cancel.
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
    var driverAsk by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<LabHelpText?>(null) }
    // A running download's progress pop-up asked for from its row; file installs open theirs by themselves.
    var progressKey by remember { mutableStateOf<String?>(null) }
    var linuxShown by remember { mutableStateOf(startSection == "linux") }
    // A driver zip AdrenoTools refused that has no "kind" in its meta.json but a Wayland / Linux library: which list.
    var otherList by remember { mutableStateOf<Uri?>(null) }

    val adreno = remember { (context as? Activity)?.let { runCatching { AdrenotoolsManager(it) }.getOrNull() } }
    val wayland = remember { runCatching { WaylandGameDriverManager(context) }.getOrNull() }
    val linux = remember { runCatching { LinuxVulkanDriverManager(context) }.getOrNull() }
    // Runs on the install's thread when a GPU driver from a file was not installed.
    fun offerOtherList(type: String, name: String, uri: Uri, ok: Boolean) {
        if (ok || !ContentsTypes.isDriver(type)) return
        if (ContentDownloadRegistry.get(ContentsInstaller.keyFor(type, "file", name))?.replaceable == true) return
        if (DriverInstaller.zipKind(context, uri).isNotEmpty() || !hasFreedrenoLib(context, uri)) return
        (context as? Activity)?.runOnUiThread { otherList = uri }
    }
    val cm = remember(tick) { runCatching { ContentsManager(context).apply { syncContents() } }.getOrNull() }

    // Install from a file, as in Classic: .wcp / .tzst go to the component pipeline, anything else is a GPU driver.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: InAppFilePicker.pickedUri(result.data) ?: return@rememberLauncherForActivityResult
        val name = displayNameOf(context, uri)
        val lower = name.lowercase()
        val type = if (lower.endsWith(".wcp") || lower.endsWith(".tzst")) "" else ContentsTypes.GPU_DRIVERS
        ContentsInstaller.installFromFile(context, type, name, uri) { ok ->
            val msg = fileInstallMessage(type, name, ok)
            (context as? Activity)?.runOnUiThread { msg?.let { toast(context, it) }; vm.refreshStatus(); vm.refreshFolders(); tick++ }
            offerOtherList(type, name, uri, ok)
        }
    }
    val fromFile = { picker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.WCP + InAppFilePicker.DRIVER, L("Select content or driver"))) }
    // Classic's Install GPU driver (the GPU drivers screen): its warning first, then the in-app picker or the system one.
    val driverPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: InAppFilePicker.pickedUri(result.data) ?: return@rememberLauncherForActivityResult
        val name = displayNameOf(context, uri)
        ContentsInstaller.installFromFile(context, ContentsTypes.GPU_DRIVERS, name, uri) { ok ->
            (context as? Activity)?.runOnUiThread { vm.refreshStatus(); tick++ }
            offerOtherList(ContentsTypes.GPU_DRIVERS, name, uri, ok)
        }
    }
    // Classic's Import Wayland game driver / Import Linux runtime driver: straight into that list, so a zip whose
    // meta.json names no kind is taken too (Install from file would hand it to AdrenoTools, which refuses it).
    fun importDriver(uri: Uri, linuxDriver: Boolean) {
        val name = displayNameOf(context, uri)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { if (linuxDriver) linux!!.let { m -> m.getDriverName(m.installDriver(uri, name)) } else wayland!!.let { m -> m.getDriverName(m.installDriver(uri, name)) } }
            }
            r.onSuccess { n -> toast(context, if (linuxDriver) L("Imported Linux runtime driver: %1\$s", n) else L("Imported Wayland game driver: %1\$s", n)); tick++ }
                .onFailure { e -> toast(context, L("Not imported: %1\$s", e.message ?: e.javaClass.simpleName)) }
            vm.refreshStatus()
        }
    }
    val waylandPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        (result.data?.data ?: InAppFilePicker.pickedUri(result.data))?.let { importDriver(it, linuxDriver = false) }
    }
    val linuxPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        (result.data?.data ?: InAppFilePicker.pickedUri(result.data))?.let { importDriver(it, linuxDriver = true) }
    }

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
                    onProgress = { progressKey = it },
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
                            confirm = if (s.removeIsHide) L("Hide %1\$s? It stays under Hidden repositories, where it can be listed again.", s.name) else L("Remove %1\$s?", s.name),
                        ),
                    )
                },
            )
        }.sortedBy { if (it.group == L("Components")) 0 else 1 }
        // Hidden built-in repositories, each to list again on its own (Classic switches GPU driver sources on and off).
        val hidden = remember(sources) { runCatching { vm.hiddenSources() }.getOrDefault(emptyList()) }.map { s ->
            val show = { vm.showSource(s); toast(context, L("%1\$s is listed again", s.name)) }
            MenuEntry(
                key = "h-${s.driverOnly}-${s.name}", title = s.name, subtitle = s.displayFormat,
                icon = Icons.Filled.VisibilityOff, group = L("Hidden repositories"),
                onA = show, actions = listOf(MenuAction(L("List it again"), show)),
            )
        }
        listOf(
            MenuEntry("search", L("Search all repositories"), icon = Icons.Filled.Search, onA = { searchOpen = true }),
            MenuEntry("refresh", L("Refresh the lists"), icon = Icons.Filled.Refresh, onA = { refreshLists() }),
        ) + repos + listOf(
            MenuEntry("fromfile", L("Install content from file"), icon = Icons.Filled.FolderOpen, primary = true, onA = fromFile),
        ) + hidden + listOf(
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
            add(MenuEntry("d-install", L("Install GPU driver"), icon = Icons.Filled.Add, group = L("GPU drivers"), onA = { driverAsk = true }))
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
            if (wayland != null) {
                val pick = { waylandPicker.launch(InAppFilePicker.buildIntent(context, arrayOf("zip"), L("Select Wayland game driver zip"))) }
                add(
                    MenuEntry(
                        "w-import", L("Import Wayland game driver (.zip)"),
                        subtitle = if (runCatching { wayland.enumerateInstalledDrivers().isEmpty() }.getOrDefault(true)) L("None imported. Containers on Wayland use the Turnips bundled in the Proton.") else null,
                        icon = Icons.Filled.Add, group = L("Wayland game drivers"), onA = pick,
                        actions = listOf(
                            MenuAction(L("Import a zip"), pick),
                            MenuAction(L("What is this?"), {
                                note = LabHelpText(L("Wayland game drivers"), L("The Vulkan driver a game renders on under the Wayland display backend. Import a zip with a libvulkan_freedreno*.so built for Wayland/Linux (optional libdrm.so, meta.json). Android Turnip zips (vulkan.adXXXX.so) belong under GPU drivers and are rejected here."))
                            }),
                        ),
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
            if (linux != null) {
                val pick = { linuxPicker.launch(InAppFilePicker.buildIntent(context, arrayOf("zip"), L("Select Linux runtime driver zip"))) }
                add(
                    MenuEntry(
                        "l-import", L("Import Linux runtime driver (.zip)"),
                        subtitle = if (runCatching { linux.enumerateInstalledDrivers().isEmpty() }.getOrDefault(true)) L("None imported. Linux sessions use the Turnip built into the runtime.") else null,
                        icon = Icons.Filled.Add, group = L("Linux runtime drivers"), onA = pick,
                        actions = listOf(
                            MenuAction(L("Import a zip"), pick),
                            MenuAction(L("What is this?"), {
                                note = LabHelpText(L("Linux runtime drivers"), L("The Vulkan driver the Linux runtime draws with: the native Steam client's interface and every game it launches. Import a \"-Linux\" Turnip zip (glibc). The client and its games are Linux processes, so an Android (vulkan.adXXXX.so) or \"-Wayland\" zip cannot be loaded by them and is rejected here. Frames still reach the screen through the GPU driver above."))
                            }),
                        ),
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
                ContentsInstaller.installFromFile(context, f.type, f.name, f.uri) { ok ->
                    val msg = fileInstallMessage(f.type, f.name, ok)
                    (context as? Activity)?.runOnUiThread { if (ok) vm.markInstalledFromSaved(f); msg?.let { toast(context, it) }; vm.refreshStatus(); tick++ }
                    offerOtherList(f.type, f.name, f.uri, ok)
                }
            }
            MenuEntry(
                key = "f${f.uri}", title = f.name, group = folder.ifBlank { typeName(f.type) },
                // As Classic: a "Save the file only" download is marked until it is installed from here.
                subtitle = RemoteSourceRepository.formatFileSize(f.sizeBytes).let { size -> if (f.savedOnly) L("%1\$s · saved only, not installed", size) else size },
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

    // ── Linux runtime ──
    val linuxRows = rememberLabLinuxRuntime(active = linuxShown, refresh = tick) { note = it }

    SimpleMenuPage(
        overline = L("Manage"),
        title = L("Contents"),
        sections = listOf(
            MenuSection("download", L("Download components"), Icons.Filled.Download, download, empty = L("No repositories."), primary = MenuAction(L("Add a repository"), { addRepo = true })),
            MenuSection("installed", L("Installed"), Icons.Filled.CheckCircle, installed, empty = L("Only the built-in components so far."), primary = MenuAction(L("Install content from file"), { fromFile() })),
            MenuSection("files", L("My files"), Icons.Filled.Save, files, empty = L("No saved files. Use ⋯ › Save the file only on a download.")),
            MenuSection("linux", L("Linux runtime"), Icons.Filled.Terminal, linuxRows),
        ),
        // B ends a search first.
        onBack = { if (query.isNotBlank()) vm.setQuery("") else onBack() },
        banner = labContentsBanner(states),
        startSection = startSection,
        onSection = { if (it == "linux") linuxShown = true },
    )
    LabInstallProgressHost(request = progressKey, onRequestTaken = { progressKey = null }, auto = { it.contains("::file::") })
    defaultItem?.let { item ->
        LabSetAsDefaultDialog(item, onDismiss = { defaultItem = null }) { out -> defaultItem = null; tick++; outcome = out }
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
    if (addRepo) LabAddRepoDialog(vm, onDismiss = { addRepo = false })
    if (searchOpen) {
        LabTextEntryDialog(L("Search all repositories"), query, L("Search"), onDismiss = { searchOpen = false }, allowBlank = true, search = true) { q ->
            searchOpen = false
            vm.setQuery(q)
        }
    }
    if (driverAsk) {
        SimpleActionSheet(
            title = L("Install GPU driver"),
            actions = listOf(
                MenuAction(L("Browse files"), { driverPicker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.DRIVER, L("Select GPU driver"))) }),
                MenuAction(L("Pick via system…"), {
                    driverPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" })
                }),
                MenuAction(L("Cancel"), {}),
            ),
            onDismiss = { driverAsk = false },
            message = context.getString(R.string.install_drivers_message) + "\n\n" + context.getString(R.string.install_drivers_warning),
        ) { a -> driverAsk = false; a.run() }
    }
    note?.let { n -> LabHelpSheet(n) { note = null } }
    otherList?.let { uri ->
        SimpleActionSheet(
            title = L("Not an AdrenoTools driver"),
            actions = listOfNotNull(
                if (wayland != null) MenuAction(L("Install as a Wayland game driver"), { importDriver(uri, linuxDriver = false) }) else null,
                if (linux != null) MenuAction(L("Install as a Linux runtime driver"), { importDriver(uri, linuxDriver = true) }) else null,
                MenuAction(L("Cancel"), {}),
            ),
            onDismiss = { otherList = null },
            message = L("Its meta.json says no kind, and it carries a libvulkan_freedreno library. Install it into one of these lists?"),
        ) { a -> otherList = null; a.run() }
    }
    if (restoreAsk) {
        SimpleConfirm(L("Restore default repositories"), L("Hidden built-in repositories come back. Yours stay."), danger = false, onDismiss = { restoreAsk = false }) {
            restoreAsk = false
            vm.restoreDefaultSources()
        }
    }
}
