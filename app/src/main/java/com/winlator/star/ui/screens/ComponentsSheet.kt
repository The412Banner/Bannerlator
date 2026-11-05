package com.winlator.star.ui.screens

import android.content.Context
import com.winlator.star.ui.deck.XmbTapToEdit
import com.winlator.star.ui.deck.xmbDoneActions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.theme.Divider as DividerColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Browse + install Wine dependency components (mono, gecko, dotnet, vcredist, d3dx, …) into a
 * container's prefix. Reads the live components.json catalog from winlator-contents.
 *
 * Laid out as DroidDeck's components page is: what the game needs first, each with why (when the
 * sheet is opened for a game: [exeFile] / [gameDir]), then what this container already has, then
 * everything else, and last - folded away - the few that still open the container to run their
 * installer. A component shows in one section only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentsSheet(container: Container, onDismiss: () -> Unit, exeFile: File? = null, gameDir: File? = null) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var components by remember { mutableStateOf<List<Component>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var stage by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmExec by remember { mutableStateOf<Component?>(null) }
    var recs by remember { mutableStateOf<List<DependencyDetector.Recommendation>>(emptyList()) }
    var showSession by remember { mutableStateOf(false) }
    // The "Also in the game's folder" group is open.
    var showFolder by remember { mutableStateOf(false) }
    // Removing: the component going out (its row shows the progress), and the one awaiting confirmation.
    var removing by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<Pair<Component, ComponentUninstaller.Plan>?>(null) }

    // "Installed" must survive closing/reopening the sheet, so persist it per container
    // (the in-memory set alone reset every time the sheet recomposed).
    val installsPrefs = remember { context.getSharedPreferences("component_installs", Context.MODE_PRIVATE) }
    val installKey = "c${container.id}"
    fun markInstalled(name: String) {
        installed = installed + name
        installsPrefs.edit().putStringSet(installKey, installed).apply()
    }

    // Run an installer-based component: download its installer, then launch the container session.
    fun runExecInstall(c: Component) {
        installing = c.name; progress = 0f
        // Container editor has no originating shortcut → container-only return target (Tier 1: land
        // back on Games instead of stranding the user after the install session restarts the app).
        // Cleared below if no session actually launched (inline set_windows/uninstall, or error).
        ComponentInstallReturn.set(context, container.id, null)
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                ComponentExecInstaller.startInstall(context, container, c) { f ->
                    activity?.runOnUiThread { progress = f }
                }
            }
            installing = null
            when (res) {
                is ComponentExecInstaller.Result.Launched -> { /* session launched; return target consumed after restart */ }
                is ComponentExecInstaller.Result.Done -> { markInstalled(c.name); ComponentInstallReturn.clear(context) }
                is ComponentExecInstaller.Result.Error -> {
                    message = "Couldn't install ${c.name}: ${res.message}"
                    ComponentInstallReturn.clear(context)
                }
            }
        }
    }

    // An installed row's Remove: works out how it would come out, then asks first. Nothing is removed without the confirmation.
    fun askRemove(c: Component) {
        if (installing != null || removing != null) return
        scope.launch {
            val plan = withContext(Dispatchers.IO) { ComponentUninstaller.plan(context, container, c.name, installed) }
            confirmRemove = c to plan
        }
    }

    fun remove(plan: ComponentUninstaller.Plan) {
        removing = plan.name; progress = 0f; stage = ""
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                ComponentUninstaller.uninstall(context, container, plan,
                    { f -> activity?.runOnUiThread { progress = f } },
                    { t -> activity?.runOnUiThread { stage = t } })
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

    LaunchedEffect(Unit) {
        val recorded = withContext(Dispatchers.IO) { ComponentInstallRecords.verified(context, container) }
        // Backfill: components installed before we recorded installs (or by hand) that leave a
        // non-baseline prefix footprint (OpenAL/mono/PhysX/GFWL) — so they show as installed too.
        val detected = withContext(Dispatchers.IO) { PrefixInstalledDetector.detect(container) }
        installed = recorded + detected
        val list = withContext(Dispatchers.IO) { ComponentCatalog.load() }
        components = list
        if (exeFile != null || gameDir != null) recs = withContext(Dispatchers.IO) { GameRecommendations.detect(exeFile, gameDir, context) }
        loadError = list.isEmpty()
        loading = false
    }

    message?.let { m ->
        OutlinedAlertDialog(
            onDismissRequest = { message = null },
            containerColor = cs.surfaceContainerHigh,
            text = { Text(m, color = cs.onSurface) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    confirmExec?.let { c ->
        OutlinedAlertDialog(
            onDismissRequest = { confirmExec = null },
            containerColor = cs.surfaceContainerHigh,
            title = { Text("Run ${c.name} installer", color = cs.onSurface) },
            text = {
                Text(
                    "This opens the container to a Windows desktop and runs the ${c.name} installer. " +
                        "Click through the installer window that appears, then close the container when it's " +
                        "done — you'll be brought back to finish setup.",
                    color = cs.onSurface,
                )
            },
            confirmButton = {
                TextButton(onClick = { val comp = c; confirmExec = null; runExecInstall(comp) }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmExec = null }) { Text("Cancel") } },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = cs.surface, contentColor = cs.onSurface) {
        DeckDialogBars()
        DeckDialogScaled {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(bottom = 12.dp)) {
                Text("Components", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
                Text("Install Wine dependencies into this container",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                XmbTapToEdit(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { fieldModifier, stopEditing ->
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search components") },
                        modifier = fieldModifier,
                        keyboardActions = xmbDoneActions(stopEditing),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Divider(color = DividerColor)

                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = cs.primary)
                    }
                    loadError -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        Text("Couldn't load the component catalog.", color = cs.onSurfaceVariant)
                    }
                    else -> {
                        // Name → component, for routing a base install to its file-drop `_dll` variant below.
                        // Built from the full list (the `_dll` variants are hidden from `shown`, not dropped).
                        val byName = remember(components) { components.associateBy { it.name } }
                        // Installed components float to the top (checked), so what the container already
                        // has is obvious and users don't reinstall. Stable within each group; re-sorts
                        // live when something finishes installing (installed is a key). The file-drop `_dll`
                        // variants are install-implementation (reached via routing in onInstall), so they're
                        // filtered out here — otherwise vcredist2010 AND vcredist2010_dll show as duplicates.
                        // The file-drop `_dll` variants are install-implementation (reached via routing in
                        // install()), so they're hidden - otherwise vcredist2010 AND vcredist2010_dll show twice.
                        val shown = remember(components, query) {
                            components.filter {
                                !it.name.endsWith("_dll") &&
                                    (query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true))
                            }
                        }
                        // What the game needs, by the name the list shows (a `_dll` pick shows as its base): its
                        // reason and group (recs come strongest first, so the first per name wins).
                        val picks = remember(recs, byName) {
                            val m = LinkedHashMap<String, DependencyDetector.Recommendation>()
                            recs.forEach { r ->
                                val base = r.componentName.removeSuffix("_dll").takeIf { byName.containsKey(it) } ?: r.componentName
                                if (base !in m) m[base] = r
                            }
                            m
                        }
                        val reasons = remember(picks) { picks.mapValues { GameRecommendations.reason(it.value) } }
                        val opensContainer: (Component) -> Boolean = { c -> (byName["${c.name}_dll"] ?: c).let { ComponentExecInstaller.isExecComponent(it) } }
                        fun inGroup(g: GameRecommendations.Group) = shown.filter { c -> picks[c.name]?.let { GameRecommendations.group(it) == g } == true }
                        val recommended = inGroup(GameRecommendations.Group.RECOMMENDED)
                        val optional = inGroup(GameRecommendations.Group.OPTIONAL)
                        val inFolder = inGroup(GameRecommendations.Group.FOLDER)
                        val have = shown.filter { it.name in installed && it.name !in reasons }
                        val rest = shown.filter { it.name !in reasons && it.name !in installed }
                        val others = rest.filterNot(opensContainer)
                        val session = rest.filter(opensContainer)

                        fun install(c: Component) {
                            // Prefer the file-drop `_dll` variant when the catalog carries one: it copies DLLs
                            // straight into the prefix. Installed-state is recorded under the BASE name.
                            val target = byName["${c.name}_dll"] ?: c
                            when {
                                // An installer that must run → confirm, then a container session.
                                ComponentExecInstaller.isExecComponent(target) -> confirmExec = target
                                // Local-only steps the exec driver does inline (set_windows/uninstall).
                                ComponentExecInstaller.handlesComponent(target) -> runExecInstall(target)
                                else -> {
                                    installing = c.name; progress = 0f; stage = ""
                                    scope.launch {
                                        val err = withContext(Dispatchers.IO) {
                                            ComponentInstaller.install(context, container, target,
                                                { f -> activity?.runOnUiThread { progress = f } },
                                                { t -> activity?.runOnUiThread { stage = t } })
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

                        confirmRemove?.let { (c, plan) ->
                            ComponentRemoveDialog(
                                plan = plan,
                                containerName = container.name,
                                forGame = exeFile != null || gameDir != null,
                                containerColor = cs.surfaceContainerHigh,
                                onRemove = { confirmRemove = null; remove(plan) },
                                onReinstall = { confirmRemove = null; install(c) },
                                onDismiss = { confirmRemove = null },
                            )
                        }

                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                fun section(title: String, list: List<Component>, note: (Component) -> String? = { null }) {
                                    if (list.isEmpty()) return
                                    item(key = "h-$title") { SectionHeader("$title (${list.size})") }
                                    items(list, key = { it.name }) { c ->
                                        ComponentRow(
                                            c = c,
                                            isInstalled = c.name in installed,
                                            isInstalling = installing == c.name,
                                            isRemoving = removing == c.name,
                                            progress = if (installing == c.name || removing == c.name) progress else null,
                                            stage = if (installing == c.name || removing == c.name) stage else "",
                                            note = note(c),
                                            enabled = installing == null && removing == null,
                                            onInstall = { install(c) },
                                            onRemove = { askRemove(c) },
                                        )
                                        Divider(color = DividerColor.copy(alpha = 0.5f))
                                    }
                                }
                                section("Recommended for this game", recommended) { reasons[it.name] }
                                section("Optional", optional) { reasons[it.name] }
                                if (inFolder.isNotEmpty()) {
                                    item(key = "h-folder") {
                                        SectionHeader(
                                            (if (showFolder) "▾ " else "▸ ") + "Also in the game's folder (${inFolder.size})",
                                            Modifier.clickable { showFolder = !showFolder },
                                        )
                                    }
                                    if (showFolder) {
                                        items(inFolder, key = { it.name }) { c ->
                                            ComponentRow(
                                                c = c,
                                                isInstalled = c.name in installed,
                                                isInstalling = installing == c.name,
                                                isRemoving = removing == c.name,
                                                progress = if (installing == c.name || removing == c.name) progress else null,
                                                stage = if (installing == c.name || removing == c.name) stage else "",
                                                note = reasons[c.name],
                                                enabled = installing == null && removing == null,
                                                onInstall = { install(c) },
                                                onRemove = { askRemove(c) },
                                            )
                                            Divider(color = DividerColor.copy(alpha = 0.5f))
                                        }
                                    }
                                }
                                section("Installed in this container", have)
                                section("All components", others)
                                if (session.isNotEmpty()) {
                                    item(key = "h-session") {
                                        SectionHeader(
                                            (if (showSession) "▾ " else "▸ ") + "Open the container to install (${session.size})",
                                            Modifier.clickable { showSession = !showSession },
                                        )
                                    }
                                    if (showSession) {
                                        items(session, key = { it.name }) { c ->
                                            ComponentRow(
                                                c = c,
                                                isInstalled = c.name in installed,
                                                isInstalling = installing == c.name,
                                                isRemoving = removing == c.name,
                                                progress = if (installing == c.name || removing == c.name) progress else null,
                                                stage = if (installing == c.name || removing == c.name) stage else "",
                                                note = null,
                                                enabled = installing == null && removing == null,
                                                onInstall = { install(c) },
                                                onRemove = { askRemove(c) },
                                            )
                                            Divider(color = DividerColor.copy(alpha = 0.5f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close", color = cs.primary) }
                }
            }
        }
    }
}

@Composable
private fun ComponentRow(
    c: Component,
    isInstalled: Boolean,
    isInstalling: Boolean,
    isRemoving: Boolean,
    progress: Float?,
    stage: String,
    note: String?,
    enabled: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val installedBlue = Color(0xFF4FC3F7) // intentional: status color (installed indicator, distinct from action accent)
    // Components needing the exec engine (installer steps, or set_windows/uninstall) are gated by it;
    // the rest by the file-drop installer.
    val reason = if (ComponentExecInstaller.handlesComponent(c)) ComponentExecInstaller.execBlockedReason(c)
                 else ComponentInstaller.blockedReason(c)
    val installable = reason == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                val sub = when {
                    (isInstalling || isRemoving) && stage.isNotEmpty() -> stage
                    isRemoving -> "Removing…"
                    isInstalled && note != null -> "Installed · $note"
                    isInstalled -> "Installed"
                    note != null -> note
                    !installable -> reason ?: ""
                    c.description.isNotEmpty() -> c.description
                    else -> c.provider
                }
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.labelSmall,
                        color = if (isInstalled) installedBlue else cs.onSurfaceVariant)
                }
            }
            when {
                isInstalling || isRemoving -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                // Installed: the check, and Remove (which asks first).
                isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Installed",
                        tint = installedBlue, modifier = Modifier.size(22.dp))
                    IconButton(onClick = onRemove, enabled = enabled) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${c.name}",
                            tint = if (enabled) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.38f))
                    }
                }
                installable -> IconButton(onClick = onInstall, enabled = enabled) {
                    Icon(Icons.Filled.Download, contentDescription = "Install", tint = cs.primary)
                }
                else -> Box(
                    Modifier.background(cs.surfaceContainerHigh, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("N/A", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
            }
        }
        if (isInstalling || isRemoving) {
            Spacer(Modifier.height(6.dp))
            val frac = (progress ?: 0f).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = frac, modifier = Modifier.fillMaxWidth().height(4.dp),
                color = cs.primary, trackColor = cs.surfaceContainerHighest)
        }
    }
}

/** A section's title in the list. */
@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}
