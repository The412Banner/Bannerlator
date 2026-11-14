package com.winlator.star.ui.simple

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.DriverInstaller
import com.winlator.star.ui.screens.adrenodownload.DriverFeed
import com.winlator.star.ui.screens.adrenodownload.DriverSourceStore
import com.winlator.star.ui.screens.adrenodownload.DriverSources
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverEntry
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverRepository
import com.winlator.star.ui.screens.adrenodownload.feedSummary
import com.winlator.star.ui.screens.adrenodownload.normDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Classic's "Download GPU drivers" sheet (AdrenoDriverDownloadSheet) as Lab lists: the driver sources, then one
 * source's drivers. A on a driver asks, then downloads and installs it with the progress on its line (the same
 * DriverInstaller steps and messages); a driver for the Android list is handed to [onDriverInstalled]. "Manage
 * sources" turns built-in sources on or off and adds or removes your own (a JSON list or a GitHub repository).
 */
@Composable
internal fun LabTurnipDownloadSheet(onDismiss: () -> Unit, onDriverInstalled: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { RemoteDriverRepository(context) }
    var manageKey by remember { mutableIntStateOf(0) }
    val sources = remember(manageKey) { DriverSources.allSources(context) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var manage by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<RemoteDriverEntry>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf<RemoteDriverEntry?>(null) }
    var busy by remember { mutableStateOf<RemoteDriverEntry?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf("") }
    var installKey by remember { mutableIntStateOf(0) }
    val installedNorm = remember(installKey) {
        runCatching { AdrenotoolsManager(context).enumarateInstalledDrivers() }.getOrDefault(arrayListOf()).map { normDriver(it) }.toSet()
    }
    val installedUrls = remember(installKey) { runCatching { DriverInstaller.installedSourceUrls(context) }.getOrDefault(emptySet()) }

    LaunchedEffect(sources.size) { if ((picked ?: 0) > sources.lastIndex) picked = null }
    val source = picked?.let { sources.getOrNull(it) }
    LaunchedEffect(source?.name, refreshKey) {
        val s = source ?: return@LaunchedEffect
        loading = true
        error = null
        entries = emptyList()
        repo.fetchEntries(s).fold(
            onSuccess = { entries = it },
            onFailure = { error = it.message ?: it::class.java.simpleName },
        )
        loading = false
    }

    fun download(entry: RemoteDriverEntry) {
        busy = entry
        progress = 0
        stage = L("Downloading")
        scope.launch {
            repo.downloadEntry(entry) { pct -> progress = pct }.fold(
                onSuccess = { file ->
                    stage = L("Installing")
                    // A "-Linux" or "-Wayland" zip goes into its own list; the message says which.
                    val r = withContext(Dispatchers.IO) {
                        DriverInstaller.install(context, Uri.fromFile(file), file.name, sourceUrl = entry.downloadUrl).also { file.delete() }
                    }
                    busy = null
                    installKey++
                    Toast.makeText(context, DriverInstaller.message(r), if (r.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    // The caller selects it in a Graphics Driver list, which holds AdrenoTools drivers only.
                    if (r.ok && r.place == DriverInstaller.Place.ANDROID) onDriverInstalled(r.id)
                },
                onFailure = { t ->
                    busy = null
                    Toast.makeText(context, L("Download failed: %1\$s", t.message ?: L("unknown error")), Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    if (manage) {
        LabDriverSourcesManager(onDismiss = { manage = false }, onChanged = { manageKey++ })
        return
    }
    if (source == null) {
        LabDownloadList(
            title = L("Download GPU drivers"),
            lines = sources.mapIndexed { i, s -> DlLine("s$i-${s.name}", s.name, onA = { picked = i }) } +
                DlLine("__manage__", L("Manage sources…"), accent = true, onA = { manage = true }),
            onDismiss = onDismiss,
        )
        return
    }
    val lines = buildList<DlLine> {
        when {
            loading -> add(DlLine("__loading__", L("Loading drivers…"), enabled = false))
            error != null -> {
                add(DlLine("__error__", L("Could not load drivers"), sub = error, enabled = false))
                add(DlLine("__retry__", L("Retry"), accent = true, onA = { refreshKey++ }))
            }
            entries.isEmpty() -> add(DlLine("__none__", L("No drivers found."), enabled = false))
            else -> entries.forEachIndexed { i, e ->
                val mine = busy == e
                val installed = DriverInstaller.normUrl(e.downloadUrl) in installedUrls || normDriver(e.displayName) in installedNorm
                add(
                    DlLine(
                        key = "e$i-${e.downloadUrl}",
                        title = e.displayName,
                        sub = when {
                            mine -> stage
                            installed -> L("Installed")
                            else -> null
                        },
                        trailing = if (mine && stage == L("Downloading")) "$progress%" else if (mine) "…" else null,
                        progress = if (mine && stage == L("Downloading")) progress / 100f else null,
                        enabled = busy == null || mine,
                        onA = { if (busy == null) ask = e },
                    ),
                )
            }
        }
    }
    LabDownloadList(
        title = source.name,
        lines = lines,
        // B steps back to the sources; never while a driver downloads or installs.
        onDismiss = { picked = null },
        blockDismiss = busy != null,
    )
    ask?.let { e ->
        SimpleConfirm(
            title = L("Download driver?"),
            body = e.displayName + "\n\n" + L("Source: %1\$s", e.source) + "\n\n" + L("The driver will be downloaded and installed automatically."),
            danger = false,
            onDismiss = { ask = null },
        ) { ask = null; download(e) }
    }
}

/**
 * Classic's "Manage driver sources" as a Lab list: the built-in sources as switches, your own sources (X or A:
 * Remove) and Add source (a name, then a JSON URL or a GitHub owner and repository), on the same DriverSourceStore.
 */
@Composable
private fun LabDriverSourcesManager(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DriverSourceStore(context) }
    var version by remember { mutableIntStateOf(0) }
    val disabled = remember(version) { store.disabledBuiltIns() }
    val custom = remember(version) { store.customSources() }
    fun mutate(block: () -> Unit) { block(); version++; onChanged() }
    // Add source, one field at a time: name → kind → URL, or owner → repo.
    var step by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newOwner by remember { mutableStateOf("") }
    fun msg(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    val lines = buildList<DlLine> {
        add(DlLine("__hb", L("Built-in sources"), header = true))
        DriverSources.builtIns().forEach { src ->
            val on = src.name !in disabled
            add(DlLine("b-${src.name}", src.name, toggle = on, onA = { mutate { store.setBuiltInEnabled(src.name, !on) } }))
        }
        if (custom.isNotEmpty()) {
            add(DlLine("__hc", L("Custom sources"), header = true))
            custom.forEach { src ->
                add(DlLine("c-${src.name}", src.name, sub = feedSummary(src), actions = listOf(MenuAction(L("Remove"), { mutate { store.removeCustom(src.name) } }, danger = true))))
            }
        }
        add(DlLine("__add__", L("Add source…"), accent = true, onA = { newName = ""; newOwner = ""; step = "name" }))
    }
    LabDownloadList(title = L("Manage driver sources"), lines = lines, onDismiss = onDismiss)

    when (step) {
        "name" -> LabFieldDialog(title = L("Add source"), initial = newName, hint = L("Name"), confirm = L("Next"), onDismiss = { step = null }) { v ->
            if (v.trim().isEmpty()) msg(L("Enter a name")) else { newName = v.trim(); step = "kind" }
        }
        "kind" -> SimpleActionSheet(
            title = newName,
            actions = listOf(MenuAction(L("JSON URL"), { step = "url" }), MenuAction(L("GitHub repo"), { step = "owner" })),
            onDismiss = { step = null },
        ) { it.run() }
        "url" -> LabFieldDialog(title = L("JSON URL"), initial = "", hint = "https://…", confirm = L("Add source"), onDismiss = { step = null }) { v ->
            if (v.isBlank()) msg(L("Enter a JSON URL"))
            else { mutate { store.addCustom(newName, DriverFeed.Json(v.trim())) }; step = null }
        }
        "owner" -> LabFieldDialog(title = L("Owner (or github.com/owner/repo)"), initial = newOwner, confirm = L("Next"), onDismiss = { step = null }) { v ->
            newOwner = v
            // A full github.com/owner/repo needs no repo of its own.
            val gh = DriverSourceStore.parseGithubRepo(v, "")
            if (gh != null) { mutate { store.addCustom(newName, DriverFeed.GithubReleases(gh.first, gh.second)) }; step = null }
            else if (v.isBlank()) msg(L("Enter owner and repo"))
            else step = "repo"
        }
        "repo" -> LabFieldDialog(title = L("Repo"), initial = "", confirm = L("Add source"), onDismiss = { step = null }) { v ->
            val gh = DriverSourceStore.parseGithubRepo(newOwner, v)
            if (gh == null) msg(L("Enter owner and repo"))
            else { mutate { store.addCustom(newName, DriverFeed.GithubReleases(gh.first, gh.second)) }; step = null }
        }
    }
}
