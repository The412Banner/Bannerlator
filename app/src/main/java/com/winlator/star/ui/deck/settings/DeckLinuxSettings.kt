package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.linux.LinuxAppGames
import com.winlator.star.linux.LinuxComponents
import com.winlator.star.linux.LinuxTuning
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// The Linux runtime page's last classic parts, for the Linux Steam client entry only (LINUX_ONLY, and
// the section returns nothing for any other editor): the Steam client and game core lists (the classic
// Advanced tab), the Games folders (its Linux tab), and the FEX / DXVK / VKD3D-Proton swaps (its
// Components tab). The core lists and the folders are the entry's own extras, written on Save like
// every row, in the classic format: "0,1,2" lists, and the folders joined with
// LinuxAppGames.FOLDER_SEPARATOR (removed when empty). The swaps belong to the Linux runtime's Protons,
// which every Linux entry shares, so like the classic tab they act at once (each asks first) and are not
// part of the draft. Never a Wine game's, a container's or All containers'.

internal object LinuxRuntimeRows {

    const val ID_GAMES_FOLDERS = "linux.gamesFolders"

    fun defs(): List<SettingDef> = listOf(
        cores(
            "linux.clientCores", "linuxClientCpuList", L("Steam client cores"),
            L("The client and the compositor drawing its interface. Never the games it launches."),
            L("Which CPU cores the Steam client and the compositor drawing its interface may run on. It reaches the client and its menus only, ") +
                L("never the games it launches: those have the list below. Steam holds its own interface renderer to a subset of the cores by default, ") +
                L("which is why Big Picture can feel sluggish; what you tick here replaces that choice."),
        ),
        cores(
            "linux.gameCores", "linuxGameCpuList", L("Game cores"),
            L("Games launched from the client. All cores lets Android place the work itself."),
            L("Which CPU cores a game launched from the Steam client may run on. It reaches games only: the client and its menus keep the list above. ") +
                L("Leaving every core ticked lets Android place the work itself, which is usually the best choice."),
        ),
        // The Games folders list (DeckLinuxSection) reads and writes the entry's extra through this row.
        hiddenDef(ID_GAMES_FOLDERS, "linux", vm = null, game = ownExtra(LinuxTuning.EXTRA_GAMES_FOLDERS), worlds = LINUX_ONLY, onDefaults = false),
    )

    /** A core list of the entry. Unset means every core, as the launch reads it (cpuListOrEmpty). */
    private fun cores(id: String, extra: String, label: String, hint: String, help: String) = SettingDef(
        id = id, category = "linux", group = L("CPU cores"), label = label, hint = hint, help = help, tech = extra,
        level = SettingLevel.ADVANCED,
        control = { _, _ -> Control.Cores },
        game = GameBinding(
            keys = listOf(extra),
            read = { e -> e.get(extra)?.takeIf { it.isNotBlank() } },
            write = { e, v -> e.put(extra, v) },
            inherit = Inherit.BUILT_IN,
            // What the classic editor shows for an entry without a list of its own.
            inherited = { env -> env.shortcut?.container?.getCPUList(true) ?: "" },
        ),
        visible = { env, _ -> env.scope == EditorScope.GAME },
        worlds = LINUX_ONLY,
    )
}

/** A swap, restore or delete waiting for its confirmation: a stray A press must never change a Proton. */
internal class LinuxAsk(val title: String, val body: String, val go: () -> Unit)

/** The Linux runtime page's Games folders and Proton components, for the Linux Steam client entry only. */
internal class DeckLinuxSection(
    val env: SettingsEnv,
    val source: SettingsSource,
    private val scope: CoroutineScope,
) : DeckSection {
    private val access = DefAccess(source)
    // The HARD line: the Linux Steam client entry only, never Wine.
    private val applies = env.scope == EditorScope.GAME && env.world == World.LINUX

    var pickFolder by mutableStateOf(false)
    var pickWcp by mutableStateOf(false)
    // A picker activity is open: coming back must not reload the form, or the pick lands on a form that is thrown away.
    private var awaiting = false

    override val keepsForm: Boolean get() = awaiting

    override fun onReturn() { awaiting = false }

    fun startPicker() { awaiting = true }

    // The components, as the classic tab keeps them.
    var snapshot by mutableStateOf<LinuxComponents.Snapshot?>(null)
        private set
    var catalog by mutableStateOf<List<LinuxComponents.CatalogItem>>(emptyList())
        private set
    var catalogAt by mutableLongStateOf(0L)
        private set
    var protonId by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var downloads by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var ask by mutableStateOf<LinuxAsk?>(null)
    /** The component whose choices are open. */
    var choosing by mutableStateOf<String?>(null)
    var choosingProton by mutableStateOf(false)
    private var loaded = false

    override fun extras(): Map<String, CategoryExtra> {
        if (!applies || access.def(LinuxRuntimeRows.ID_GAMES_FOLDERS) == null) return emptyMap()
        return mapOf("linux" to CategoryExtra(bottom = ::bottomItems))
    }

    private fun toast(text: String) = Toast.makeText(env.context, text, Toast.LENGTH_LONG).show()

    // ── Games folders ───────────────────────────────────────────────────────────────────────────

    /** LinuxAppGames.folders over the draft. */
    private fun folders(): List<String> =
        access.value(LinuxRuntimeRows.ID_GAMES_FOLDERS).split(LinuxAppGames.FOLDER_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun setFolders(list: List<String>) = access.set(LinuxRuntimeRows.ID_GAMES_FOLDERS, list.joinToString(LinuxAppGames.FOLDER_SEPARATOR))

    /** A picked folder, checked as the classic picker checks it. */
    fun addFolder(path: String) {
        when {
            path.isEmpty() || !File(path).isDirectory -> toast(L("That folder cannot be used: pick one on internal storage or an SD card"))
            path.contains(LinuxAppGames.FOLDER_SEPARATOR) -> toast(L("Folder names with | cannot be used"))
            path !in folders() -> setFolders(folders() + path)
        }
    }

    fun removeFolder(path: String) = setFolders(folders() - path)

    // ── Components (LinuxComponents, as ScLinuxComponentsTab runs them) ─────────────────────────

    /** The first time the page is shown: saves every Proton's originals, then reads them. */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        refresh(snapshotFirst = true)
    }

    /** Reads the Protons off the main thread, first running the swaps a closed game was holding back. */
    fun refresh(snapshotFirst: Boolean = false) {
        val context = env.context
        scope.launch {
            val (applied, snap, cat) = withContext(Dispatchers.IO) {
                if (snapshotFirst) runCatching { LinuxComponents.snapshotAll(context) }
                val applied = runCatching { LinuxComponents.applyQueued(context) }.getOrDefault(emptyList())
                val snap = runCatching { LinuxComponents.snapshot(context) }.getOrNull()
                val cat = runCatching { LinuxComponents.catalog(context, false) }.getOrNull()
                Triple(applied, snap, cat)
            }
            snapshot = snap ?: LinuxComponents.Snapshot(emptyList(), emptyList())
            if (cat != null && cat.fetchedAt > 0) { catalog = cat.items; catalogAt = cat.fetchedAt }
            if (protonId == null || snap?.protons?.none { it.proton.id == protonId } == true) protonId = snap?.protons?.firstOrNull()?.proton?.id
            if (applied.isNotEmpty()) toast(L("Applied: ") + applied.joinToString(", "))
        }
    }

    /** One action at a time; what happened is said in a toast, then the Protons are read again. */
    fun action(label: String, work: () -> String) {
        if (busy != null) return
        busy = label
        scope.launch {
            val message = withContext(Dispatchers.IO) { runCatching(work).getOrElse { e -> "$label failed: ${e.message ?: e.javaClass.simpleName}" } }
            busy = null
            toast(message)
            refresh()
        }
    }

    fun refreshCatalog() {
        if (checking) return
        checking = true
        val context = env.context
        scope.launch {
            val cat = withContext(Dispatchers.IO) { runCatching { LinuxComponents.catalog(context, true) }.getOrNull() }
            checking = false
            if (cat == null || cat.items.isEmpty()) toast(L("The Nightlies could not be reached"))
            else { catalog = cat.items; catalogAt = cat.fetchedAt }
        }
    }

    fun download(item: LinuxComponents.CatalogItem) {
        if (downloads.containsKey(item.file)) return
        downloads = downloads + (item.file to -1)
        val context = env.context
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val pkg = LinuxComponents.download(context, item) { pc ->
                        scope.launch { if (downloads.containsKey(item.file)) downloads = downloads + (item.file to pc) }
                    }
                    "Stored ${pkg.version}"
                }.getOrElse { e -> "Download failed: ${e.message ?: e.javaClass.simpleName}" }
            }
            downloads = downloads - item.file
            toast(message)
            refresh()
        }
    }

    fun importWcp(path: String) {
        val context = env.context
        action(L("Importing")) { L("Imported ") + LinuxComponents.importPackage(context, File(path), File(path).name).version }
    }

    private fun currentView(): LinuxComponents.ProtonView? {
        val views = snapshot?.protons ?: return null
        return views.firstOrNull { it.proton.id == protonId } ?: views.firstOrNull()
    }

    /** A component's choices, value to label: the originals by build, then the stored packages (by file where two share a version). */
    fun options(view: LinuxComponents.ProtonView, comp: String): List<Pair<String, String>> {
        val build = LinuxComponents.safeName(view.proton.version)
        val stored = snapshot?.packages.orEmpty().filter { it.comp == comp }
        val versions = stored.groupingBy { it.version }.eachCount()
        return view.originals.filter { it.comp == comp }.map { ("orig:" + it.protonVersion) to (if (it.protonVersion == build) L("Original") else "Original · ${it.protonVersion}") } +
            stored.map { it.file to (if ((versions[it.version] ?: 0) > 1) it.file.removeSuffix(".wcp") else it.version) }
    }

    private fun currentValue(view: LinuxComponents.ProtonView, comp: String): String =
        view.components.getValue(comp).activeFile ?: ("orig:" + LinuxComponents.safeName(view.proton.version))

    /** A pick from a component's list: asks before it swaps or restores, as the classic tab does. */
    fun pick(comp: String, value: String) {
        choosing = null
        val view = currentView() ?: return
        val st = view.components.getValue(comp)
        if (value == currentValue(view, comp) && st.queued == null) return
        val p = view.proton
        val name = LinuxComponents.LABEL[comp] ?: comp
        val label = options(view, comp).firstOrNull { it.first == value }?.second ?: value
        val waits = if (view.inUseByGame) L(" A game is running on this Proton, so it waits until the game closes.") else L(" It applies the next time a game starts.")
        val context = env.context
        ask = if (value.startsWith("orig:")) {
            val v = value.removePrefix("orig:")
            LinuxAsk("Restore $name?", "Put the $v original back into ${p.name}?$waits") {
                action(L("Restoring")) { LinuxComponents.restore(context, p.id, comp, v) }
            }
        } else {
            LinuxAsk("Swap $name?", "Put $label into ${p.name}? Its shipped files stay saved as an original bundle.$waits") {
                action(L("Swapping")) { LinuxComponents.swap(context, p.id, value) }
            }
        }
    }

    private fun mb(size: Long) = String.format(Locale.ENGLISH, "%.1f MB", size / 1048576.0)

    private fun time(epochSeconds: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000))

    // ── The page ────────────────────────────────────────────────────────────────────────────────

    private fun bottomItems(): List<PageExtra> = folderItems() + componentItems()

    private fun folderItems(): List<PageExtra> = buildList {
        add(PageExtra("linux-folders-label") { GroupLabel(L("Games folders")) })
        val list = folders()
        list.forEachIndexed { i, f ->
            add(PageExtra("linux-folder:$f") {
                GroupCard(first = i == 0, last = false, highlight = false) {
                    DeckLine(File(f).name.ifEmpty { f }, f, icon = Icons.Filled.Folder) {
                        DeckSmallButton(L("Remove"), Icons.Filled.Delete) { removeFolder(f) }
                    }
                }
            })
        }
        add(PageExtra("linux-folders-add") {
            GroupCard(first = list.isEmpty(), last = true, highlight = false) {
                DeckActionRow(DeckAction("add", Icons.Filled.CreateNewFolder, L("Add a games folder"),
                    L("Each folder inside it is one game; the app picks the program to start in it. They show in Steam like your Games-tab games.")) { pickFolder = true })
            }
        })
    }

    private fun componentItems(): List<PageExtra> = buildList {
        val snap = snapshot
        val views = snap?.protons.orEmpty()
        val view = currentView()
        add(PageExtra("linux-comp-label") { GroupLabel(L("Proton components")) })
        add(PageExtra("linux-comp-about") {
            LaunchedEffect(Unit) { ensureLoaded() }
            GroupCard(first = true, last = false, highlight = false) {
                DeckLine(
                    L("FEX, DXVK and VKD3D-Proton"),
                    L("For each Proton the Steam client runs games with. A swap applies the next time a game starts, and waits for a game running on that Proton to close. ") +
                        L("Each Proton build's own files are kept as its original, and your choice is put back before every launch if anything changed them."),
                    icon = Icons.Filled.Info,
                )
            }
        })
        busy?.let { b ->
            add(PageExtra("linux-comp-busy") {
                GroupCard(first = false, last = false, highlight = false) {
                    DeckLine("$b…", L("One change at a time.")) { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp)) }
                }
            })
        }
        when {
            snap == null -> add(PageExtra("linux-comp-reading") {
                GroupCard(first = false, last = true, highlight = false) {
                    DeckLine(L("Reading the Protons…")) { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp)) }
                }
            })
            view == null -> add(PageExtra("linux-comp-none") {
                GroupCard(first = false, last = true, highlight = false) {
                    DeckLine(
                        L("No Proton installed yet"),
                        L("Start the Steam client once so it downloads its ARM64 Proton, or add GE-Proton / CachyOS from the Linux Protons catalog."),
                    )
                }
            })
            else -> {
                val p = view.proton
                val status = listOfNotNull(
                    if (p.valve) L("Valve's own Proton, replaced when Steam updates it; its originals are kept per build") else L("Installed in compatibilitytools.d"),
                    L("a game is running on it").takeIf { view.inUseByGame },
                    view.reappliedAt.takeIf { it > 0 }?.let { L("re-applied at launch ") + time(it) },
                ).joinToString(" · ")
                add(PageExtra("linux-comp-proton") {
                    GroupCard(first = false, last = false, highlight = false) {
                        // Name and build together: two installs of one Proton would otherwise read the same.
                        DeckLine(
                            "${p.name} · ${p.version}",
                            status + if (views.size > 1) " · ${views.size} Protons, A picks another" else "",
                            icon = Icons.Filled.Terminal,
                            onClick = if (views.size > 1) ({ choosingProton = true }) else null,
                        )
                    }
                })
                LinuxComponents.COMPONENTS.forEachIndexed { ci, comp ->
                    val st = view.components.getValue(comp)
                    val opts = options(view, comp)
                    val current = currentValue(view, comp)
                    val name = LinuxComponents.LABEL[comp] ?: comp
                    val details = listOf(st.detected, st.detail).filter { it.isNotEmpty() }.joinToString(" · ")
                    val last = ci == LinuxComponents.COMPONENTS.lastIndex && st.queued == null
                    add(PageExtra("linux-comp:$comp") {
                        GroupCard(first = false, last = last, highlight = false) {
                            if (opts.isEmpty()) {
                                DeckLine(name, L("Nothing stored yet") + (if (details.isNotEmpty()) " · $details" else ""), icon = Icons.Filled.SwapHoriz)
                            } else {
                                DeckLine(
                                    name,
                                    "In use: ${st.inUse}" + (if (details.isNotEmpty()) " · $details" else ""),
                                    icon = Icons.Filled.SwapHoriz,
                                    enabled = busy == null,
                                    onClick = { choosing = comp },
                                ) {
                                    Text(
                                        opts.firstOrNull { it.first == current }?.second ?: st.inUse,
                                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp),
                                    )
                                }
                            }
                        }
                    })
                    st.queued?.let { q ->
                        add(PageExtra("linux-comp-queued:$comp") {
                            GroupCard(first = false, last = ci == LinuxComponents.COMPONENTS.lastIndex, highlight = false) {
                                DeckLine(
                                    "Cancel the waiting $name swap",
                                    "Next: $q, after the game closes.",
                                    icon = Icons.Filled.Schedule,
                                    enabled = busy == null,
                                    onClick = {
                                        val context = env.context
                                        action(L("Cancelling")) { LinuxComponents.cancelQueued(context, p.id, comp); L("The waiting swap was cancelled.") }
                                    },
                                )
                            }
                        })
                    }
                }
                addAll(storedItems(view, views))
                addAll(nightlyItems())
            }
        }
    }

    /** This Proton's older-build originals (the installed build's is the way back and stays) and the packages every Proton shares. */
    private fun storedItems(view: LinuxComponents.ProtonView, views: List<LinuxComponents.ProtonView>): List<PageExtra> = buildList {
        val p = view.proton
        val build = LinuxComponents.safeName(p.version)
        val olderOriginals = view.originals.filter { it.protonVersion != build }
        val packages = snapshot?.packages.orEmpty()
        val context = env.context
        add(PageExtra("linux-stored-label") { GroupLabel(L("Stored")) })
        if (olderOriginals.isEmpty() && packages.isEmpty()) {
            add(PageExtra("linux-stored-none") {
                GroupCard(first = true, last = false, highlight = false) {
                    DeckLine(L("Nothing stored yet"), L("Each Proton's own files were saved as its original when this page opened."))
                }
            })
        }
        olderOriginals.forEachIndexed { i, o ->
            val name = LinuxComponents.LABEL[o.comp] ?: o.comp
            add(PageExtra("linux-stored-orig:${o.comp}:${o.protonVersion}") {
                GroupCard(first = i == 0, last = false, highlight = false) {
                    DeckLine("$name · Original · ${o.protonVersion}", "Earlier build of ${p.name} · ${mb(o.size)}") {
                        DeckSmallButton(L("Delete"), Icons.Filled.Delete, enabled = busy == null) {
                            ask = LinuxAsk(L("Delete this original?"), "The ${o.protonVersion} original of $name belongs to an earlier build of ${p.name}. The installed build's original is always kept.") {
                                action(L("Deleting")) { LinuxComponents.deleteOriginal(context, p.id, o.comp, o.protonVersion) }
                            }
                        }
                    }
                }
            })
        }
        packages.forEachIndexed { i, s ->
            val inUseBy = views.filter { it.components[s.comp]?.activeFile == s.file }.map { it.proton.name }
            add(PageExtra("linux-stored-pkg:${s.file}") {
                GroupCard(first = i == 0 && olderOriginals.isEmpty(), last = false, highlight = false) {
                    DeckLine(
                        "${LinuxComponents.LABEL[s.comp] ?: s.comp} · ${s.version}",
                        mb(s.size) + if (inUseBy.isNotEmpty()) L(" · in use by ") + inUseBy.joinToString(", ") else "",
                    ) {
                        // A package a Proton runs can't go: restore that Proton's original first.
                        DeckSmallButton(L("Delete"), Icons.Filled.Delete, enabled = busy == null && inUseBy.isEmpty()) {
                            ask = LinuxAsk("Delete ${s.version}?", L("Its package file is removed from the app. You can download it again any time.")) {
                                action(L("Deleting")) { LinuxComponents.deletePackage(context, s.file) }
                            }
                        }
                    }
                }
            })
        }
        add(PageExtra("linux-stored-import") {
            GroupCard(first = false, last = true, highlight = false) {
                DeckActionRow(DeckAction("import", Icons.Filled.FileOpen, L("Import a .wcp"), L("A component package from the Nightlies \"-Linux\" releases."), enabled = busy == null) { pickWcp = true })
            }
        })
    }

    private fun nightlyItems(): List<PageExtra> = buildList {
        val storedNames = snapshot?.packages.orEmpty().map { it.file }.toSet()
        val available = catalog.filter { LinuxComponents.safeName(it.file) !in storedNames }
        add(PageExtra("linux-nightlies-label") { GroupLabel(L("On the Nightlies")) })
        add(PageExtra("linux-nightlies-check") {
            GroupCard(first = true, last = available.isEmpty(), highlight = false) {
                DeckActionRow(DeckAction(
                    "check", Icons.Filled.Refresh,
                    if (checking) L("Checking…") else L("Check the Nightlies"),
                    when {
                        catalogAt > 0 && available.isEmpty() -> "Checked ${time(catalogAt)}. Nothing new since then."
                        catalogAt > 0 -> "Checked ${time(catalogAt)}."
                        else -> L("Never checked. Lists the FEX, DXVK and VKD3D-Proton packages to download.")
                    },
                    enabled = !checking,
                ) { refreshCatalog() })
            }
        })
        available.forEachIndexed { i, d ->
            val progress = downloads[d.file]
            add(PageExtra("linux-nightly:${d.file}") {
                GroupCard(first = false, last = i == available.lastIndex, highlight = false) {
                    DeckLine("${LinuxComponents.LABEL[d.comp] ?: d.comp} · " + d.file.removeSuffix(".wcp"), "${d.release} · ${mb(d.size)}") {
                        if (progress == null) {
                            DeckSmallButton(L("Download"), Icons.Filled.CloudDownload, enabled = busy == null) { download(d) }
                        } else {
                            Column(modifier = Modifier.width(96.dp)) {
                                Text(if (progress < 0) "…" else "$progress%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (progress < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                    }
                }
            })
        }
    }

    @Composable
    override fun Dialogs() {
        if (extras().isEmpty()) return
        val context = LocalContext.current
        // The pickers are registered here, which is always composed, so a result is never lost to a scrolled-away line.
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data)?.let { addFolder(it) }
        }
        val wcpPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val path = if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data) else null
            if (path != null) importWcp(path)
        }
        LaunchedEffect(pickFolder) {
            if (!pickFolder) return@LaunchedEffect
            pickFolder = false
            startPicker()
            // The built-in picker, not SAF: it returns a real path, so an SD card folder is /storage/<uuid>/...
            folderPicker.launch(InAppFilePicker.buildDirIntent(context, L("Select a games folder")))
        }
        LaunchedEffect(pickWcp) {
            if (!pickWcp) return@LaunchedEffect
            pickWcp = false
            startPicker()
            wcpPicker.launch(InAppFilePicker.buildIntent(context, arrayOf("wcp"), L("Choose a component package (-linux .wcp)")))
        }
        choosing?.let { comp ->
            currentView()?.let { view ->
                LinuxPickDialog(
                    title = "${LinuxComponents.LABEL[comp] ?: comp} for ${view.proton.name}",
                    options = options(view, comp),
                    current = currentValue(view, comp),
                    onDismiss = { choosing = null },
                    onPick = { pick(comp, it) },
                )
            }
        }
        if (choosingProton) {
            val views = snapshot?.protons.orEmpty()
            LinuxPickDialog(
                title = "Proton",
                options = views.map { it.proton.id to "${it.proton.name} · ${it.proton.version}" },
                current = currentView()?.proton?.id ?: "",
                onDismiss = { choosingProton = false },
                onPick = { protonId = it; choosingProton = false },
            )
        }
        ask?.let { a ->
            OutlinedAlertDialog(
                onDismissRequest = { ask = null },
                title = { Text(a.title) },
                text = { Text(a.body) },
                confirmButton = { TextButton(onClick = { ask = null; a.go() }) { Text(a.title.substringBefore(' ')) } },
                dismissButton = { TextButton(onClick = { ask = null }) { Text(L("Cancel")) } },
            )
        }
    }
}

/** One pick from a list, value to label; the line in use is ticked and has the focus first. */
@Composable
private fun LinuxPickDialog(title: String, options: List<Pair<String, String>>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val focusAt = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, (value, label) ->
                    EnvChoiceLine(label, null, selected = value == current, multi = false, modifier = if (i == focusAt) Modifier.focusRequester(first) else Modifier) { onPick(value) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("Cancel")) } },
    )
}
