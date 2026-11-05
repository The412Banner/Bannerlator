package com.winlator.star.ui.deck.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.components.Component
import com.winlator.star.components.ComponentCatalog
import com.winlator.star.components.ComponentExecInstaller
import com.winlator.star.components.ComponentInstallRecords
import com.winlator.star.components.ComponentInstallReturn
import com.winlator.star.components.ComponentInstaller
import com.winlator.star.components.ComponentUninstaller
import com.winlator.star.components.DependencyDetector
import com.winlator.star.components.GameRecommendations
import com.winlator.star.components.PrefixInstalledDetector
import com.winlator.star.container.Container
import com.winlator.star.ui.deck.DeckGlyph
import com.winlator.star.ui.deck.GlyphKind
import com.winlator.star.ui.deck.XmbTapToEdit
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.deck.deckLine
import com.winlator.star.ui.deck.rememberXmbEdit
import com.winlator.star.ui.deck.rememberXmbHome
import com.winlator.star.ui.screens.ComponentRemoveDialog
import com.winlator.star.ui.screens.OutlinedAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The Deck editor's Windows components page (category CAT_COMPONENTS) for a Wine game or a container:
// what the game needs (Recommended, Optional, and folded away "Also in the game's folder": installers its
// folder carries that nothing says it needs - GameRecommendations), then the builtin / native DLL rows (ordinary settings
// rows), then every component of the catalog with a search. Installs go into the container's prefix
// through exactly what the classic ComponentsSheet and RecommendedComponentsSection call, with the same
// "component_installs" record, so the classic screens and this page agree on what is installed.
// Nothing installs until its row is pressed. A on an installed row asks to remove it (ComponentUninstaller),
// which takes it back out of the container's prefix only once confirmed.

/**
 * The page's catalog, recommendations and install state. [shortcutBaseName], [exeFile] and [gameDir]
 * are the game's (null in a container's editor, which has no recommendations). [hasUnsavedEdits]
 * guards an installer session, which restarts the app and would drop the editor's draft.
 */
internal class DeckComponentsState(
    private val context: Context,
    val container: Container,
    private val shortcutBaseName: String?,
    private val exeFile: File?,
    private val gameDir: File?,
    private val scope: CoroutineScope,
    private val hasUnsavedEdits: () -> Boolean,
) {
    val forGame: Boolean get() = shortcutBaseName != null

    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf(false)
        private set
    var components by mutableStateOf<List<Component>>(emptyList())
        private set
    private var byName by mutableStateOf<Map<String, Component>>(emptyMap())
    var recs by mutableStateOf<List<DependencyDetector.Recommendation>>(emptyList())
        private set
    var installed by mutableStateOf<Set<String>>(emptySet())
        private set
    var installing by mutableStateOf<String?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var stage by mutableStateOf("")
        private set
    var query by mutableStateOf("")
    var showSession by mutableStateOf(false)
    /** The "Also in the game's folder" group is open. */
    var showFolder by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var confirmExec by mutableStateOf<Component?>(null)
    /** The component going back out of the container (its row shows the progress). */
    var removing by mutableStateOf<String?>(null)
        private set
    /** An installed row was pressed: how it would come out, awaiting the confirmation. */
    var confirmRemove by mutableStateOf<Pair<Component, ComponentUninstaller.Plan>?>(null)
    val searchFocus = FocusRequester()
    /** The search bar's own way in, set by the bar: it focuses it, and in the XMB home (where it is tap-to-edit) starts typing too. */
    var openSearch: () -> Unit = { searchFocus.requestFocus() }

    private val main = Handler(Looper.getMainLooper())

    // The classic screens' record of what went in, per container.
    private val installsPrefs = context.getSharedPreferences("component_installs", Context.MODE_PRIVATE)
    private val installKey = "c${container.id}"

    private fun markInstalled(name: String) {
        installed = installed + name
        installsPrefs.edit().putStringSet(installKey, installed).apply()
    }

    /** What the prefix has: recorded installs plus what [PrefixInstalledDetector] finds there. */
    private suspend fun readInstalled(): Set<String> {
        val recorded = withContext(Dispatchers.IO) { ComponentInstallRecords.verified(context, container) }
        val detected = withContext(Dispatchers.IO) { runCatching { PrefixInstalledDetector.detect(container) }.getOrDefault(emptySet()) }
        return recorded + detected
    }

    /** Called once when the page's editor opens. The catalog is fetched from winlator-contents. */
    suspend fun load() {
        loading = true
        installed = readInstalled()
        val list = withContext(Dispatchers.IO) { runCatching { ComponentCatalog.load() }.getOrDefault(emptyList()) }
        components = list
        byName = list.associateBy { it.name }
        if (exeFile != null || gameDir != null) recs = withContext(Dispatchers.IO) { GameRecommendations.detect(exeFile, gameDir, context) }
        loadError = list.isEmpty()
        loading = false
    }

    /** Re-reads what is installed, e.g. after coming back from another screen. */
    suspend fun refreshInstalled() {
        if (installing == null && removing == null) installed = readInstalled()
    }

    /** The catalog's file-drop `_dll` variant when it has one: it copies DLLs straight in, with no container session. */
    fun targetOf(c: Component): Component = byName["${c.name}_dll"] ?: c

    /** Why [c] can't be installed here, or null (the classic row's check: exec engine or file-drop installer). */
    fun blockedReason(c: Component): String? =
        if (ComponentExecInstaller.handlesComponent(c)) ComponentExecInstaller.execBlockedReason(c)
        else ComponentInstaller.blockedReason(c)

    /** Opens the container to run the installer when it has to; otherwise false. */
    fun opensContainer(c: Component): Boolean = ComponentExecInstaller.isExecComponent(targetOf(c))

    /** What the game needs, by the name the list shows (a `_dll` pick shows as its base). */
    fun recommended(): List<Pair<Component, DependencyDetector.Recommendation>> = recs.mapNotNull { r ->
        val base = r.componentName.removeSuffix("_dll").takeIf { byName.containsKey(it) } ?: r.componentName
        byName[base]?.let { it to r }
    }.distinctBy { it.first.name }

    /** A row was pressed: an installed one asks to remove it, any other installs. */
    fun press(c: Component) {
        if (c.name in installed) askRemove(c) else install(c)
    }

    /** Install [c] into the container, routed as the classic sheet routes it. [again]: a reinstall of one it has. */
    fun install(c: Component, again: Boolean = false) {
        if (installing != null || removing != null || !again && c.name in installed) return
        val target = targetOf(c)
        blockedReason(target)?.let { message = it; return }
        // The installers need the container's prefix; a container that never started has none yet.
        if (!File(container.rootDir, ".wine").isDirectory) {
            message = if (forGame) "Launch the game once first, then install its components here."
                      else "Start this container once first, then install components here."
            return
        }
        when {
            // An installer that must run → confirm, then a container session (which restarts the app).
            ComponentExecInstaller.isExecComponent(target) ->
                if (hasUnsavedEdits()) message = "Save or discard your changes first: ${c.name} opens the container and the app restarts when it closes."
                else confirmExec = target
            // Local-only steps the exec driver does inline (set_windows/uninstall).
            ComponentExecInstaller.handlesComponent(target) -> runExecInstall(target, c.name)
            else -> {
                installing = c.name; progress = 0f; stage = ""
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        ComponentInstaller.install(context, container, target,
                            { f -> main.post { progress = f } },
                            { t -> main.post { stage = t } })
                    }
                    installing = null
                    if (err == null) {
                        // Components it bundled were recorded as they went in.
                        installed = installed + (installsPrefs.getStringSet(installKey, emptySet()) ?: emptySet())
                        markInstalled(c.name)
                    } else message = "Couldn't install ${c.name}: $err"
                }
            }
        }
    }

    /** Runs an installer-based component (the confirm dialog's Continue, or an inline exec step). Recorded under [shownName]. */
    fun runExecInstall(c: Component, shownName: String = c.name.removeSuffix("_dll")) {
        installing = shownName; progress = 0f; stage = ""
        // Where the app returns after the session restarts it: this game's settings, or Games for a container.
        // Cleared below if no session actually launched (inline set_windows/uninstall, or error).
        ComponentInstallReturn.set(context, container.id, shortcutBaseName)
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                ComponentExecInstaller.startInstall(context, container, c) { f -> main.post { progress = f } }
            }
            installing = null
            when (res) {
                is ComponentExecInstaller.Result.Launched -> { /* session launched; return target consumed after restart */ }
                is ComponentExecInstaller.Result.Done -> { markInstalled(shownName); ComponentInstallReturn.clear(context) }
                is ComponentExecInstaller.Result.Error -> {
                    message = "Couldn't install ${c.name}: ${res.message}"
                    ComponentInstallReturn.clear(context)
                }
            }
        }
    }

    /** Works out how [c] would come out of the container, then asks (the dialog's Remove runs [remove]). */
    fun askRemove(c: Component) {
        if (installing != null || removing != null) return
        scope.launch {
            confirmRemove = c to withContext(Dispatchers.IO) { ComponentUninstaller.plan(context, container, c.name, installed, byName) }
        }
    }

    /** Takes [plan]'s component back out of the container's prefix: confirmed in the dialog first. */
    fun remove(plan: ComponentUninstaller.Plan) {
        // Its own uninstaller runs in a container session, which restarts the app and would drop the draft.
        if (plan.viaUninstaller && hasUnsavedEdits()) {
            message = "Save or discard your changes first: removing ${plan.name} opens the container and the app restarts when it closes."
            return
        }
        removing = plan.name; progress = 0f; stage = ""
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                ComponentUninstaller.uninstall(context, container, plan,
                    { f -> main.post { progress = f } },
                    { t -> main.post { stage = t } })
            }
            removing = null
            when (out) {
                is ComponentUninstaller.Outcome.Removed -> {
                    installed = installed - setOf(plan.name, "${plan.name}_dll")
                    if (out.notes.isNotEmpty()) message = "Removed ${plan.name}. " + out.notes.joinToString(". ") + "."
                }
                // Its own uninstaller is running in the container; the app restarts when that closes.
                is ComponentUninstaller.Outcome.Launched -> installed = installed - setOf(plan.name, "${plan.name}_dll")
                is ComponentUninstaller.Outcome.Failed -> message = "Couldn't remove ${plan.name}: ${out.message}"
            }
        }
    }

    /** The page's items around its DLL rows, for the editor (see [CategoryExtra]). */
    fun categoryExtra(): CategoryExtra = CategoryExtra(
        top = this::topItems,
        bottom = this::bottomItems,
        searchKey = SEARCH_KEY,
        focusSearch = { openSearch() },
    )

    // Recommended and Optional, then folded away what only sits in the game's folder; for a game only.
    private fun topItems(): List<PageExtra> {
        if (!forGame) return emptyList()
        if (loading) return listOf(PageExtra("rec-loading") { StatusLine("Looking for what this game needs…", spinner = true) })
        val picks = recommended()
        if (picks.isEmpty()) return emptyList()
        fun of(g: GameRecommendations.Group) = picks.filter { GameRecommendations.group(it.second) == g }
        val rec = of(GameRecommendations.Group.RECOMMENDED)
        val opt = of(GameRecommendations.Group.OPTIONAL)
        val folder = of(GameRecommendations.Group.FOLDER)
        fun reasonIn(list: List<Pair<Component, DependencyDetector.Recommendation>>): (Component) -> String? =
            { c -> list.firstOrNull { it.first.name == c.name }?.second?.let(GameRecommendations::reason) }
        return buildList {
            section("rec", "Recommended for this game", "What the game's store, its install script or its exe says it needs.", rec.map { it.first }, reasonIn(rec))
            section("opt", "Optional", "Ships with the game: install only if it misbehaves.", opt.map { it.first }, reasonIn(opt))
            if (folder.isNotEmpty()) {
                add(PageExtra("folder-toggle") {
                    GroupCard(first = true, last = !showFolder, highlight = false) {
                        FoldToggle("Also in the game's folder (${folder.size})", "Installers in its folder that nothing says the game needs.", showFolder) { showFolder = !showFolder }
                    }
                })
                if (showFolder) folder.forEachIndexed { i, (c, r) ->
                    add(PageExtra("folder:${c.name}") {
                        GroupCard(first = false, last = i == folder.lastIndex, highlight = false) { ComponentRow(this@DeckComponentsState, c, GameRecommendations.reason(r)) }
                    })
                }
            }
        }
    }

    // The search and every component: what the container has, the rest, and folded away the ones that open the container.
    private fun bottomItems(): List<PageExtra> = buildList {
        add(PageExtra("all-label") { GroupLabel("All components") })
        add(PageExtra(SEARCH_KEY) { ComponentSearch(this@DeckComponentsState) })
        when {
            loading -> add(PageExtra("all-loading") { StatusLine("Loading the component catalog…", spinner = true) })
            loadError -> add(PageExtra("all-error") { StatusLine("Couldn't load the component catalog. Check the connection and open this page again.", spinner = false) })
            else -> {
                val q = query
                // The file-drop `_dll` variants are install plumbing (reached through targetOf), so they are not listed.
                val shown = components.filter {
                    !it.name.endsWith("_dll") && (q.isBlank() || it.name.contains(q, true) || it.description.contains(q, true))
                }
                // The game's picks are at the top of the page; a search finds them here too.
                val recNames = if (q.isBlank()) recommended().map { it.first.name }.toSet() else emptySet()
                val list = shown.filter { it.name !in recNames }
                val have = list.filter { it.name in installed }
                val rest = list.filter { it.name !in installed }
                val others = rest.filterNot(this@DeckComponentsState::opensContainer)
                val session = rest.filter(this@DeckComponentsState::opensContainer)
                if (shown.isEmpty()) add(PageExtra("all-none") { StatusLine("Nothing matches \"$q\".", spinner = false) })
                section("have", "Installed in this container", null, have)
                section("rest", "Not installed", null, others)
                if (session.isNotEmpty()) {
                    add(PageExtra("session-toggle") {
                        GroupCard(first = true, last = !showSession, highlight = false) {
                            SessionToggle(session.size, showSession) { showSession = !showSession }
                        }
                    })
                    if (showSession) session.forEachIndexed { i, c ->
                        add(PageExtra("session:${c.name}") {
                            GroupCard(first = false, last = i == session.lastIndex, highlight = false) { ComponentRow(this@DeckComponentsState, c, null) }
                        })
                    }
                }
            }
        }
    }

    private fun MutableList<PageExtra>.section(
        key: String,
        title: String,
        subtitle: String?,
        list: List<Component>,
        note: (Component) -> String? = { null },
    ) {
        if (list.isEmpty()) return
        add(PageExtra("$key-label") {
            Column {
                GroupLabel("$title (${list.size})")
                subtitle?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.5.sp, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                }
            }
        })
        list.forEachIndexed { i, c ->
            add(PageExtra("$key:${c.name}") {
                GroupCard(first = i == 0, last = i == list.lastIndex, highlight = false) { ComponentRow(this@DeckComponentsState, c, note(c)) }
            })
        }
    }

    companion object {
        const val SEARCH_KEY = "components-search"
    }
}

/** The page's dialogs: why an install can't run or failed, the confirmation before a container session, and before a removal. */
@Composable
internal fun DeckComponentsDialogs(state: DeckComponentsState) {
    state.confirmRemove?.let { (c, plan) ->
        ComponentRemoveDialog(
            plan = plan,
            containerName = state.container.name,
            forGame = state.forGame,
            onRemove = { state.confirmRemove = null; state.remove(plan) },
            onReinstall = { state.confirmRemove = null; state.install(c, again = true) },
            onDismiss = { state.confirmRemove = null },
        )
    }
    state.message?.let { m ->
        OutlinedAlertDialog(
            onDismissRequest = { state.message = null },
            confirmButton = { TextButton(onClick = { state.message = null }) { Text("OK") } },
            text = { Text(m) },
        )
    }
    state.confirmExec?.let { c ->
        val name = c.name.removeSuffix("_dll")
        OutlinedAlertDialog(
            onDismissRequest = { state.confirmExec = null },
            confirmButton = {
                TextButton(onClick = { state.confirmExec = null; state.runExecInstall(c) }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { state.confirmExec = null }) { Text("Cancel") } },
            title = { Text("Run the $name installer") },
            text = {
                Text(
                    if (state.forGame) "This installs $name into this game's container (shared by every game in it). The container opens to a " +
                        "Windows desktop and runs the installer: click through its window, then close the container to finish. You'll return here afterwards."
                    else "This opens the container to a Windows desktop and runs the $name installer. Click through the installer window " +
                        "that appears, then close the container when it's done. You'll be brought back to finish setup."
                )
            },
        )
    }
}

// ── Rows ────────────────────────────────────────────────────────────────────────────────────────

/** One component: name, why or what it is, and its state. The whole row is the focus target; A installs, or on an installed one asks to remove it. */
@Composable
private fun ComponentRow(state: DeckComponentsState, c: Component, note: String?) {
    val cs = MaterialTheme.colorScheme
    val reason = state.blockedReason(state.targetOf(c))
    val isInstalled = c.name in state.installed
    val isRemoving = state.removing == c.name
    val isInstalling = state.installing == c.name || isRemoving
    val busy = state.installing != null || state.removing != null
    val stage = if (isInstalling) state.stage else ""
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .deckFocusRing(RoundedCornerShape(12.dp), scaleTo = 1f)
            // Stays focusable when there is nothing to do, so the pad can still walk the list; press() ignores it then.
            .clickable { state.press(c) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(c.name, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    stage.isNotEmpty() -> stage
                    isRemoving -> "Removing…"
                    isInstalled && note != null -> "Installed · $note"
                    isInstalled -> "Installed"
                    note != null -> note
                    reason != null -> reason
                    c.description.isNotEmpty() -> c.description
                    else -> c.provider
                }
                if (sub.isNotEmpty()) {
                    Text(sub, color = if (isInstalled) cs.primary else cs.onSurfaceVariant, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            when {
                isInstalling -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                // Installed: the check, and what A does on it.
                isInstalled -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Installed", tint = cs.primary, modifier = Modifier.size(24.dp))
                    StatePill("Remove", accent = false, dim = busy, icon = Icons.Filled.Delete)
                }
                reason != null -> StatePill("N/A", accent = false, dim = false)
                else -> StatePill(if (state.opensContainer(c)) "Run installer" else "Install", accent = true, dim = busy)
            }
        }
        if (isInstalling) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = state.progress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = cs.primary,
                trackColor = cs.surfaceVariant,
            )
        }
    }
}

@Composable
private fun StatePill(label: String, accent: Boolean, dim: Boolean, icon: ImageVector? = if (accent) Icons.Filled.Download else null) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    val ink = when {
        dim -> cs.onSurfaceVariant.copy(alpha = 0.5f)
        accent -> cs.primary
        else -> cs.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(shape)
            .background(if (accent && !dim) cs.primary.copy(alpha = 0.14f) else cs.surfaceVariant.copy(alpha = 0.45f))
            .border(1.5.dp, if (accent && !dim) cs.primary else deckLine().copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp)) }
        Text(label, color = ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun SessionToggle(count: Int, open: Boolean, onClick: () -> Unit) =
    FoldToggle("Open the container to install ($count)", "These run their own installer on a Windows desktop.", open, onClick)

/** A folded group's header row: [title], [subtitle], and whether it is [open]. The whole row is the focus target; A opens or closes it. */
@Composable
private fun FoldToggle(title: String, subtitle: String, open: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .deckFocusRing(RoundedCornerShape(12.dp), scaleTo = 1f)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = cs.onSurfaceVariant, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ComponentSearch(state: DeckComponentsState) {
    val cs = MaterialTheme.colorScheme
    val xmbHome = rememberXmbHome()
    // Only the XMB home has a typing state to start; the other Deck modes keep the plain focus.
    val edit = if (xmbHome) rememberXmbEdit() else null
    // Y opens the bar: in the XMB home it takes focus and starts typing, as A on it would.
    SideEffect {
        state.openSearch = if (edit != null) ({ runCatching { edit.outer.requestFocus() }; edit.start() }) else ({ state.searchFocus.requestFocus() })
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        XmbTapToEdit(
            Modifier.weight(1f).then(if (xmbHome) Modifier else Modifier.focusRequester(state.searchFocus)),
            RoundedCornerShape(16.dp),
            edit,
        ) { fieldModifier, stopEditing ->
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                singleLine = true,
                placeholder = { Text("Search components") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (state.query.isEmpty()) null else ({
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { state.query = "" },
                    ) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                }),
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = if (xmbHome) KeyboardActions(onSearch = { stopEditing() }) else KeyboardActions.Default,
                modifier = fieldModifier,
            )
        }
        Spacer(Modifier.width(12.dp))
        DeckGlyph("Y", GlyphKind.Y)
        Spacer(Modifier.width(6.dp))
        Text("Search", color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
    }
}

@Composable
private fun StatusLine(text: String, spinner: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp)) {
        if (spinner) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}
