package com.winlator.star.ui.simple

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.LinuxVulkanDriverManager
import com.winlator.star.contents.WaylandGameDriverManager
import com.winlator.star.ui.screens.contents.AddRepoDialog
import com.winlator.star.ui.screens.contents.ContentsHubViewModel
import com.winlator.star.ui.screens.contents.ContentsInstaller
import com.winlator.star.ui.screens.contents.ContentsTypes
import com.winlator.star.ui.screens.contents.HubSource
import com.winlator.star.ui.screens.contents.LinuxRuntimeTab
import com.winlator.star.ui.screens.contents.SetAsDefault
import com.winlator.star.ui.screens.contents.SetAsDefaultSheet
import com.winlator.star.util.InAppFilePicker

/** A source's type as Classic names it (catalogs list some in lower case, FEX under two names). */
private fun typeName(t: String): String = when (t.lowercase()) {
    "dxvk" -> "DXVK"; "d7vk" -> "D7VK"; "vkd3d" -> "VKD3D"; "box64" -> "Box64"; "wowbox64" -> "WOWBox64"
    "fex", "fexcore" -> "FEXCore"; "wine" -> "Wine"; "proton" -> "Proton"; "box64ec" -> "Box64EC"; "vegas" -> "VEGAS"
    else -> if (ContentsTypes.isDriver(t)) L("GPU drivers") else t
}

/**
 * Lab's Contents: the Classic Contents screen's four tabs as Lab lists, on the same data and actions
 * (ContentsHubViewModel, ContentsInstaller, the three driver managers, SetAsDefault, LinuxRuntimeTab).
 * Download components: every repository with the types it offers; one opens its list (SimpleComponentSourcePage).
 * Installed: GPU drivers (Windows, Wayland game, Linux runtime) and components, the defaults marked.
 * My files: saved archives to reinstall or delete. Linux runtime: Classic's own tab.
 */
@Composable
internal fun SimpleContentsPage(onNavigate: (String) -> Unit, onBack: () -> Unit, vm: ContentsHubViewModel = viewModel()) {
    val context = LocalContext.current
    val sources by vm.sources.collectAsState()
    val saved by vm.savedFolders.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    OnResume { vm.refreshStatus(); vm.refreshFolders(); tick++ }
    var defaultItem by remember { mutableStateOf<SetAsDefault.Item?>(null) }
    var addRepo by remember { mutableStateOf(false) }

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
            (context as? Activity)?.runOnUiThread { toast(context, if (ok) L("Installed %1\$s", name) else L("Could not install %1\$s", name)); vm.refreshStatus(); vm.refreshFolders(); tick++ }
        }
    }
    val fromFile = { picker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.WCP + InAppFilePicker.DRIVER, L("Select content or driver"))) }

    // ── Download components ──
    val download = sources.mapIndexed { i, s ->
        MenuEntry(
            key = "s$i-${s.name}",
            title = s.name,
            subtitle = s.displayFormat,
            pills = s.typePills.map(::typeName).distinct(),
            icon = if (s.driverOnly) Icons.Filled.Memory else Icons.Filled.CloudDownload,
            group = if (s.driverOnly) L("GPU drivers") else L("Components"),
            onA = { onNavigate(SimpleRoutes.componentSource(i)) },
            actions = listOf(
                MenuAction(L("Browse"), { onNavigate(SimpleRoutes.componentSource(i)) }),
                MenuAction(
                    if (s.removeIsHide) L("Hide this repository") else L("Remove this repository"),
                    { vm.removeSource(s) }, danger = true,
                    confirm = if (s.removeIsHide) L("Hide %1\$s? It can be shown again from Classic's Contents settings.", s.name) else L("Remove %1\$s?", s.name),
                ),
            ),
        )
    }.sortedBy { if (it.group == L("Components")) 0 else 1 } +
        MenuEntry("fromfile", L("Install content from file"), icon = Icons.Filled.FolderOpen, primary = true, onA = fromFile)

    // ── Installed ──
    fun defaultFor(item: SetAsDefault.Item?): Boolean = item != null &&
        listOf(SetAsDefault.ARM64EC, SetAsDefault.X86_64).any { a -> a in item.archs && SetAsDefault.currentDefault(context, a, item.kind) == item.value }
    val installed = remember(tick, cm) {
        buildList {
            runCatching { adreno?.enumarateInstalledDrivers()?.toList() }.getOrNull().orEmpty().forEach { id ->
                val name = adreno?.getDriverName(id) ?: id
                val item = SetAsDefault.driverItem(id, name)
                add(
                    MenuEntry(
                        key = "d$id", title = name, group = L("GPU drivers"),
                        subtitle = listOfNotNull(adreno?.getDriverVersion(id), adreno?.getDriverVendor(id)).filter { it.isNotBlank() }.joinToString(" · "),
                        trailing = if (defaultFor(item)) L("Default") else null, current = defaultFor(item),
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
                add(
                    MenuEntry(
                        key = "w$id", title = name, group = L("Wayland game drivers"),
                        subtitle = wayland?.getDriverVersion(id)?.takeIf { it.isNotBlank() }, icon = Icons.Filled.Memory,
                        actions = listOf(MenuAction(L("Remove"), { wayland?.removeDriver(id); tick++; toast(context, L("Removed %1\$s", name)) }, danger = true, confirm = L("Remove %1\$s?", name))),
                    ),
                )
            }
            runCatching { linux?.enumerateInstalledDrivers() }.getOrNull().orEmpty().forEach { id ->
                val name = linux?.getDriverName(id) ?: id
                add(
                    MenuEntry(
                        key = "l$id", title = name, group = L("Linux runtime drivers"),
                        subtitle = linux?.getDriverVersion(id)?.takeIf { it.isNotBlank() }, icon = Icons.Filled.Memory,
                        actions = listOf(MenuAction(L("Remove"), { linux?.removeDriver(id); tick++; toast(context, L("Removed %1\$s", name)) }, danger = true, confirm = L("Remove %1\$s?", name))),
                    ),
                )
            }
            ContentProfile.ContentType.values().forEach { t ->
                val label = TYPE_LABELS[t.name] ?: t.name
                cm?.getProfiles(t).orEmpty().filter { it.remoteUrl == null }.forEach { p ->
                    val item = cm?.let { m -> runCatching { SetAsDefault.componentItem(context, m, p) }.getOrNull() }
                    val def = defaultFor(item)
                    add(
                        MenuEntry(
                            key = "i${t.name}-${p.verName}-${p.verCode}", title = "$label ${p.verName}", group = label,
                            subtitle = p.desc?.takeIf { it.isNotBlank() },
                            trailing = if (def) L("Default") else null, current = def,
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
                    (context as? Activity)?.runOnUiThread { if (ok) vm.markInstalledFromSaved(f); toast(context, if (ok) L("Installed %1\$s", f.name) else L("Could not install %1\$s", f.name)); vm.refreshStatus(); tick++ }
                }
            }
            MenuEntry(
                key = "f${f.uri}", title = f.name, group = folder.ifBlank { typeName(f.type) },
                subtitle = L("%.1f MB · saved").format(f.sizeBytes / 1_048_576.0),
                icon = Icons.Filled.Save,
                onA = reinstall, play = reinstall, playIcon = Icons.Filled.Download, playLabel = L("Reinstall"),
                actions = listOf(
                    MenuAction(L("Reinstall"), reinstall),
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
        onBack = onBack,
        banner = com.winlator.star.store.download.ContentDownloadRegistry.states.collectAsState().value.values
            .firstOrNull { !it.terminal }?.let { L("Downloading %1\$s · %2\$s%%", it.title, (it.fraction * 100).toInt()) },
    )
    defaultItem?.let { item ->
        SetAsDefaultSheet(item, onDismiss = { defaultItem = null }) { defaultItem = null; tick++; toast(context, L("Set as default")) }
    }
    if (addRepo) AddRepoDialog(vm, onDismiss = { addRepo = false })
}
