package com.winlator.star.ui.simple

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.communityconfigs.CommunityConfigApply
import com.winlator.star.communityconfigs.GameMatcher
import com.winlator.star.communityconfigs.InstalledComponents
import com.winlator.star.communityconfigs.ShortcutExporter
import com.winlator.star.communityconfigs.UploadedConfigsStore.UploadedConfig
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.screens.CommunityPick
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverEntry
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverRepository
import com.winlator.star.ui.screens.applyCommunityComponentAfterInstall
import com.winlator.star.ui.screens.contents.ContentsTypes
import com.winlator.star.ui.screens.downloadToCache
import com.winlator.star.ui.screens.installContent
import com.winlator.star.ui.screens.saveCommunityExportToDownloads
import com.winlator.star.ui.screens.shareCommunityExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lab's community-configs flow, shared by its pages: the Apply / Details choice for a config, the game to apply it to,
 * the apply itself and its result (with the installs a config needs), a game's export and its upload. The same steps
 * and ViewModel calls as Classic's (ShortcutActionDialogs); the pop-ups are drawn by [LabCommunityHost].
 */
internal object LabCommunity {
    /** A tapped config and the game it was opened for (null from the catalog): Apply to game… or View details. */
    var chooser by mutableStateOf<Pair<CommunityPick, Shortcut?>?>(null)
    /** What the config details page shows. */
    var detail by mutableStateOf<Pair<CommunityPick, Shortcut?>?>(null)
    var applyPicker by mutableStateOf<CommunityPick?>(null)
    var applyMismatch by mutableStateOf<Pair<Shortcut, CommunityPick>?>(null)
    var applyBusy by mutableStateOf(false)
    var applyResult by mutableStateOf<CommunityConfigApply.ConfigApplyResult?>(null)
    var applyTarget by mutableStateOf<Shortcut?>(null)
    /** The result's components and drivers installed since (drivers as "driver:<wanted>"): a check mark in place of Install. */
    val resolved = mutableStateListOf<String>()
    var browseComponent by mutableStateOf<CommunityConfigApply.MissingComponent?>(null)
    var browseDriver by mutableStateOf<CommunityConfigApply.MissingDriver?>(null)
    var exportResult by mutableStateOf<ShortcutExporter.ExportResult?>(null)
    /** The game whose config is being uploaded, and whether the upload itself has begun (after any replace question). */
    var uploadingFor by mutableStateOf<String?>(null)
    var uploadStarted by mutableStateOf(false)
    var replacePrompt by mutableStateOf<Triple<UploadedConfig, () -> Unit, () -> Unit>?>(null)

    /** The ViewModel the apply, import, export and upload run on: the Lab frame's, which outlives the pages. */
    internal var vm: ShortcutsViewModel? = null

    /** Apply to game…: straight to [sc] when the config was opened for a game, else the game to apply it to first. */
    fun startApply(pick: CommunityPick, sc: Shortcut?) {
        if (sc != null) runApply(sc, pick) else applyPicker = pick
    }

    /** A game picked for a catalog config; a different game asks first. */
    fun chooseTarget(sc: Shortcut, pick: CommunityPick) {
        applyPicker = null
        if (GameMatcher.match(sc.name, listOf(pick.game)).isNotEmpty()) runApply(sc, pick) else applyMismatch = sc to pick
    }

    fun runApply(sc: Shortcut, pick: CommunityPick) {
        val vm = vm ?: return
        begin(sc)
        val done: (CommunityConfigApply.ConfigApplyResult) -> Unit = { res -> applyBusy = false; applyResult = res }
        when (pick) {
            is CommunityPick.File -> vm.applyCommunityConfigFile(sc, pick.ref, done)
            is CommunityPick.Device -> vm.applyCommunityConfig(sc, pick.game, pick.device, done)
        }
    }

    /** A config file the user picked, applied to [sc] through the same result. */
    fun runImport(sc: Shortcut, uri: Uri) {
        val vm = vm ?: return
        begin(sc)
        vm.importConfigFile(uri, sc) { res -> applyBusy = false; applyResult = res }
    }

    private fun begin(sc: Shortcut) {
        applyBusy = true
        applyResult = null
        applyTarget = sc
        resolved.clear()
    }

    fun export(sc: Shortcut) {
        vm?.exportShortcutConfig(sc) { exportResult = it }
    }

    /** Upload to community, as Classic's: a game already shared asks whether to replace it; the outcome is a toast. */
    fun upload(context: Context, sc: Shortcut) {
        val vm = vm ?: return
        if (uploadingFor != null) return
        uploadingFor = sc.file.path
        uploadStarted = false
        vm.uploadShortcutConfig(
            sc,
            onExisting = { existing, proceed, cancel -> replacePrompt = Triple(existing, proceed, cancel) },
            onStart = { uploadStarted = true },
            onResult = { _, msg ->
                uploadingFor = null
                uploadStarted = false
                replacePrompt = null
                toast(context, msg)
            },
        )
    }
}

/** Which repository a config came from, in its chooser line. */
private fun fromLabel(pick: CommunityPick, fallback: String): String = when (pick) {
    is CommunityPick.File -> pick.entry.device.ifBlank { pick.entry.soc.ifBlank { fallback } }
    is CommunityPick.Device -> pick.device.model.ifBlank { fallback }
}

/**
 * The community flow's pop-ups, over any Lab page (the Lab frame places it once): the Apply / Details choice, the game
 * to apply to, "Different game", Applying, the result with its installs, Share this game's config and the upload's
 * replace question.
 */
@Composable
internal fun LabCommunityHost(vm: ShortcutsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(vm) {
        LabCommunity.vm = vm
        onDispose { if (LabCommunity.vm === vm) LabCommunity.vm = null }
    }
    val c = LabCommunity
    val browsing = c.browseComponent != null || c.browseDriver != null

    c.chooser?.let { (pick, sc) ->
        SimpleActionSheet(
            pick.game.name,
            listOf(
                MenuAction(L("Apply to game…"), { c.startApply(pick, sc) }),
                MenuAction(L("View details"), { c.detail = pick to sc; LabNav.go(LabCommunityRoutes.CONFIG) }),
            ),
            onDismiss = { c.chooser = null },
            message = L("Config from %1\$s.", fromLabel(pick, L("that device"))),
        ) { a -> c.chooser = null; a.run() }
    }

    c.applyPicker?.let { pick ->
        val list = remember(pick) { vm.currentShortcuts() }
        SimpleActionSheet(
            L("Apply to game…"),
            list.map { sc -> MenuAction(LabTitles.of(sc), { c.chooseTarget(sc, pick) }) } + MenuAction(L("Cancel"), { c.applyPicker = null }),
            onDismiss = { c.applyPicker = null },
            message = if (list.isEmpty()) L("You have no shortcuts yet.")
                else L("Config from %1\$s for \"%2\$s\".", fromLabel(pick, L("a device")), pick.game.name),
        ) { a -> a.run() }
    }

    c.applyMismatch?.let { (sc, pick) ->
        SimpleActionSheet(
            L("Different game"),
            listOf(MenuAction(L("Apply anyway"), { c.runApply(sc, pick) }), MenuAction(L("Cancel"), {})),
            onDismiss = { c.applyMismatch = null },
            message = L("This config is for \"%1\$s\" — apply to \"%2\$s\" anyway?", pick.game.name, sc.name),
        ) { a -> c.applyMismatch = null; a.run() }
    }

    if (c.applyBusy) {
        LabPanelDialog(L("Applying config"), onDismiss = {}, buttons = emptyList(), dismissable = false) {
            LabBusyLine(L("Fetching and merging…"))
        }
    }

    if (!browsing) c.applyResult?.let { res -> LabApplyResult(res, vm) }

    c.browseComponent?.let { mc ->
        // The download picker reports no install of its own: what is installed of this type before and after tells.
        val key = remember(mc) { CommunityConfigApply.installedTypeKey(mc.type) }
        var before by remember(mc) { mutableStateOf<Set<String>?>(null) }
        LaunchedEffect(mc) { before = withContext(Dispatchers.IO) { key?.let { InstalledComponents.read(context).raw(it).toSet() }.orEmpty() } }
        LabDownloadPicker(listOf(mc.type.toString()), onClose = {
            c.browseComponent = null
            val was = before
            val target = c.applyTarget
            scope.launch {
                val now = withContext(Dispatchers.IO) { key?.let { InstalledComponents.read(context).raw(it).toSet() }.orEmpty() }
                if (target != null && was != null && now != was) labApplyAfterInstall(context, vm, target, mc)
            }
        })
    }

    c.browseDriver?.let { md ->
        var before by remember(md) { mutableStateOf<Set<String>?>(null) }
        LaunchedEffect(md) { before = withContext(Dispatchers.IO) { runCatching { AdrenotoolsManager(context).enumarateInstalledDrivers().toSet() }.getOrDefault(emptySet()) } }
        LabDownloadPicker(listOf(ContentsTypes.GPU_DRIVERS), onClose = {
            c.browseDriver = null
            val was = before
            val target = c.applyTarget
            scope.launch {
                val now = withContext(Dispatchers.IO) { runCatching { AdrenotoolsManager(context).enumarateInstalledDrivers().toSet() }.getOrDefault(emptySet()) }
                val added = if (was != null) (now - was).firstOrNull() else null
                if (target != null && added != null) {
                    val ok = withContext(Dispatchers.IO) { CommunityConfigApply.applyResolvedDriver(target, added) }
                    if (ok) {
                        if (("driver:" + md.wanted) !in c.resolved) c.resolved.add("driver:" + md.wanted)
                        vm.refresh()
                        toast(context, L("Driver applied to \"%1\$s\".", target.name))
                    }
                }
            }
        })
    }

    c.exportResult?.let { res ->
        SimpleActionSheet(
            L("Share this game's config"),
            listOf(
                MenuAction(L("Save to Downloads"), { saveCommunityExportToDownloads(context, scope, res) }),
                MenuAction(L("Share"), { shareCommunityExport(context, scope, res) }),
                MenuAction(L("Cancel"), {}),
            ),
            onDismiss = { c.exportResult = null },
            message = L("A config file for \"%1\$s\" is ready.", res.game) + "\n" + res.fileName,
        ) { a -> c.exportResult = null; a.run() }
    }

    c.replacePrompt?.let { (existing, proceed, cancel) ->
        val stop = {
            c.replacePrompt = null
            c.uploadingFor = null
            c.uploadStarted = false
            cancel()
        }
        SimpleActionSheet(
            L("Replace your shared config?"),
            listOf(MenuAction(L("Replace"), { c.replacePrompt = null; proceed() }), MenuAction(L("Cancel"), stop)),
            onDismiss = stop,
            message = L("You already shared a config for \"%1\$s\". Replace it?", existing.game),
        ) { a -> a.run() }
    }
}

/** After a component install: the build on disk goes to the game, as Classic's apply-after-install does. */
private suspend fun labApplyAfterInstall(context: Context, vm: ShortcutsViewModel, target: Shortcut, mc: CommunityConfigApply.MissingComponent) {
    val ok = withContext(Dispatchers.IO) { applyCommunityComponentAfterInstall(context, target, mc) }
    if (ok) {
        if (mc.label !in LabCommunity.resolved) LabCommunity.resolved.add(mc.label)
        vm.refresh()
        toast(context, L("Installed and applied to \"%1\$s\".", target.name))
    } else {
        toast(context, L("Installed, but couldn't auto-apply — open \"Browse all versions\" to finish."))
    }
}

/** "Config applied" / "Couldn't apply": what changed, what it still needs (installable here) and the notes. */
@Composable
private fun LabApplyResult(res: CommunityConfigApply.ConfigApplyResult, vm: ShortcutsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LabCommunity
    // The downloadable versions for the components it needs, once per result (null = still loading).
    val cm = remember(res) { ContentsManager(context) }
    var remoteByType by remember(res) { mutableStateOf<Map<ContentProfile.ContentType, List<ContentProfile>>?>(null) }
    LaunchedEffect(res) {
        if (res.missingComponents.isEmpty()) { remoteByType = emptyMap(); return@LaunchedEffect }
        val types = res.missingComponents.map { it.type }.toSet()
        remoteByType = withContext(Dispatchers.IO) {
            val json = Downloader.downloadString(ContentsManager.REMOTE_PROFILES)
            if (json != null) cm.setRemoteProfiles(json) else cm.syncContents()
            types.associateWith { t -> (cm.getProfiles(t) ?: emptyList()).filter { it.remoteUrl != null } }
        }
    }
    LabPanelDialog(
        if (res.ok) L("Config applied") else L("Couldn't apply"),
        onDismiss = { c.applyResult = null },
        buttons = listOf(LabDialogButton(L("Done"), { c.applyResult = null }, primary = true)),
    ) {
        LabDialogText(res.message)
        if (res.changed.isNotEmpty()) {
            LabDialogHeading(L("Changed"))
            res.changed.forEach { LabDialogText("• $it", muted = true) }
        }
        if (res.missingComponents.isNotEmpty()) {
            LabDialogHeading(L("Needs a component"))
            res.missingComponents.forEach { mc ->
                LabComponentInstallRow(
                    mc = mc,
                    done = mc.label in c.resolved,
                    candidates = remoteByType?.get(mc.type) ?: emptyList(),
                    catalogLoading = remoteByType == null,
                    cm = cm,
                    onBrowseAll = { c.browseComponent = mc },
                    onInstalled = { c.applyTarget?.let { t -> scope.launch { labApplyAfterInstall(context, vm, t, mc) } } },
                )
            }
        }
        if (res.missingDrivers.isNotEmpty()) {
            LabDialogHeading(L("Needs a GPU driver"))
            res.missingDrivers.forEach { md ->
                LabDriverInstallRow(
                    md = md,
                    vm = vm,
                    done = ("driver:" + md.wanted) in c.resolved,
                    onBrowseAll = { c.browseDriver = md },
                    onApplied = { driverId ->
                        val target = c.applyTarget
                        if (target != null) scope.launch {
                            val ok = withContext(Dispatchers.IO) { CommunityConfigApply.applyResolvedDriver(target, driverId) }
                            if (ok) {
                                if (("driver:" + md.wanted) !in c.resolved) c.resolved.add("driver:" + md.wanted)
                                vm.refresh()
                                toast(context, L("Driver installed and applied to \"%1\$s\".", target.name))
                            } else toast(context, L("Installed, but couldn't apply the driver."))
                        }
                    },
                )
            }
        }
        if (res.advisories.isNotEmpty()) {
            LabDialogHeading(L("Heads up"))
            res.advisories.forEach { LabDialogText("• $it", muted = true) }
        }
    }
}

/** A small Lab button inside a pop-up's body (Install, a version to install). */
@Composable
private fun LabInlineButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.background(SimpleCard, SimpleShape).labTile(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** The trailing state of an install row: installed, working, or its Install button. */
@Composable
private fun InstallTrail(done: Boolean, busy: Boolean, onInstall: () -> Unit) {
    when {
        done -> Text(L("Installed ✓"), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        busy -> androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.padding(4.dp).size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        else -> LabInlineButton(L("Install"), onInstall)
    }
}

/**
 * One component the config needs, with Classic's quick install: the exact version (asked first), else the closest
 * versions and "Browse all versions…". The download and install are Classic's (downloadToCache / installContent).
 */
@Composable
private fun LabComponentInstallRow(
    mc: CommunityConfigApply.MissingComponent,
    done: Boolean,
    candidates: List<ContentProfile>,
    catalogLoading: Boolean,
    cm: ContentsManager,
    onBrowseAll: () -> Unit,
    onInstalled: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val shortlist = remember(candidates, mc.wanted) { CommunityConfigApply.rankVersions(mc.wanted, candidates) }
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ContentProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    fun install(p: ContentProfile) {
        confirm = null
        expanded = false
        busy = true
        installing = false
        progress = 0f
        scope.launch {
            val uri = withContext(Dispatchers.IO) { downloadToCache(context, p) { f -> activity?.runOnUiThread { progress = f } } }
            if (uri == null) {
                busy = false
                toast(context, L("Download failed."))
                return@launch
            }
            installing = true
            progress = 0f
            installContent(context, cm, uri, onProgress = { f, _ -> progress = maxOf(progress, f) }) { ok ->
                busy = false
                if (ok) onInstalled() else toast(context, L("Install failed."))
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("• ${mc.label}", color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            InstallTrail(done, busy || catalogLoading) {
                val exact = shortlist.exact
                when {
                    exact != null -> confirm = exact
                    shortlist.closest.isEmpty() -> onBrowseAll()
                    else -> expanded = !expanded
                }
            }
        }
        if (busy) {
            val f = progress.coerceIn(0f, 1f)
            Text(if (installing) L("Installing…") else L("Downloading %1\$s%%…", (f * 100).toInt()), color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp, top = 2.dp))
            LabBar(f, Modifier.fillMaxWidth().padding(top = 3.dp))
        }
        if (!busy && !done && expanded && shortlist.exact == null) {
            Column(Modifier.padding(start = 10.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shortlist.closest.forEach { p -> LabInlineButton(L("Install %1\$s", p.verName)) { install(p) } }
                LabInlineButton(L("Browse all versions…")) { expanded = false; onBrowseAll() }
            }
        }
    }
    confirm?.let { p ->
        SimpleActionSheet(
            L("Install %1\$s?", p.verName),
            listOf(MenuAction(L("Install"), { install(p) }), MenuAction(L("Cancel"), { confirm = null })),
            onDismiss = { confirm = null },
            message = L("Download and install %1\$s %2\$s, then apply it to this shortcut?", mc.type.toString(), p.verName),
        ) { a -> a.run() }
    }
}

/**
 * One GPU driver the config needs, with Classic's quick install over the driver repositories: every exact build, the
 * closest others and "Browse all drivers…", each asked first. Download and install as Classic's (RemoteDriverRepository,
 * AdrenotoolsManager).
 */
@Composable
private fun LabDriverInstallRow(
    md: CommunityConfigApply.MissingDriver,
    vm: ShortcutsViewModel,
    done: Boolean,
    onBrowseAll: () -> Unit,
    onApplied: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { RemoteDriverRepository(context) }
    val label = remember(md) {
        buildString {
            append(L("config wants %1\$s", md.wanted))
            md.current?.let { append(L("; you have %1\$s", it)) }
        }
    }
    var loading by remember { mutableStateOf(false) }
    var shortlist by remember { mutableStateOf<CommunityConfigApply.DriverShortlist?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<RemoteDriverEntry?>(null) }
    var busy by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }

    fun reveal(sl: CommunityConfigApply.DriverShortlist) {
        if (sl.exactMatches.isEmpty() && sl.closest.isEmpty()) onBrowseAll() else expanded = true
    }
    fun onInstall() {
        val sl = shortlist
        if (sl != null) { reveal(sl); return }
        if (loading) return
        loading = true
        vm.fetchDriverShortlist(md.wanted) { fetched -> shortlist = fetched; loading = false; reveal(fetched) }
    }
    fun install(e: RemoteDriverEntry) {
        confirm = null
        expanded = false
        busy = true
        installing = false
        progress = 0
        scope.launch {
            repo.downloadEntry(e) { pct -> progress = pct }.fold(
                onSuccess = { file ->
                    installing = true
                    val id = withContext(Dispatchers.IO) { AdrenotoolsManager(context).installDriver(Uri.fromFile(file)) }
                    file.delete()
                    busy = false
                    if (id.isNotEmpty()) onApplied(id) else toast(context, L("Install failed — invalid driver package"))
                },
                onFailure = { t ->
                    busy = false
                    toast(context, L("Download failed: %1\$s", t.message ?: L("unknown error")))
                },
            )
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("• $label", color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            InstallTrail(done, busy || loading) { onInstall() }
        }
        if (busy) {
            Text(if (installing) L("Installing…") else L("Downloading %1\$s%%…", progress), color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp, top = 2.dp))
            LabBar((progress / 100f).coerceIn(0f, 1f), Modifier.fillMaxWidth().padding(top = 3.dp))
        }
        val sl = shortlist
        if (!busy && !done && expanded && sl != null) {
            Column(Modifier.padding(start = 10.dp, top = 6.dp).heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (sl.exactMatches + sl.closest).forEach { e -> LabInlineButton(L("Install %1\$s · %2\$s", e.source, e.displayName)) { confirm = e } }
                LabInlineButton(L("Browse all drivers…")) { expanded = false; onBrowseAll() }
            }
        }
    }
    confirm?.let { e ->
        SimpleActionSheet(
            L("Quick install %1\$s?", e.displayName),
            listOf(MenuAction(L("Install"), { install(e) }), MenuAction(L("Cancel"), { confirm = null })),
            onDismiss = { confirm = null },
            message = L("Download and install this Turnip driver from %1\$s, then apply it to this shortcut?", e.source),
        ) { a -> a.run() }
    }
}
