package com.winlator.star.ui.simple

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.communityconfigs.AccountManager
import com.winlator.star.communityconfigs.CanonicalDevice
import com.winlator.star.communityconfigs.CanonicalGame
import com.winlator.star.communityconfigs.DeviceIdentity
import com.winlator.star.communityconfigs.GameMatcher
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.AccountAvatar
import com.winlator.star.ui.AccountUiBus
import com.winlator.star.ui.screens.CatalogSort
import com.winlator.star.ui.screens.CatalogStoreFilter
import com.winlator.star.ui.screens.CommunityCatalog
import com.winlator.star.ui.screens.CommunityConfigDetail
import com.winlator.star.ui.screens.CommunityMatchResult
import com.winlator.star.ui.screens.CommunityPick
import com.winlator.star.ui.screens.MyUploadRow
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.accountErrorMessage
import com.winlator.star.ui.screens.avatarErrorMessage
import com.winlator.star.ui.screens.communityFilePick
import com.winlator.star.ui.screens.communitySourceLabel
import com.winlator.star.ui.screens.communityVisibleGames
import com.winlator.star.ui.screens.compressAvatar
import com.winlator.star.ui.screens.configSummaryLines
import com.winlator.star.ui.screens.deviceHeaderLabel
import com.winlator.star.ui.screens.rememberGameConfigs
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Lab's community configs pages: the catalog, one catalog game, a game's own configs, a config's details, My uploads and
// My account. Same data and ViewModel calls as Classic's dialogs (ShortcutsScreen.kt); the apply flow is LabCommunity's.

private fun storeName(g: CanonicalGame): String = if (g.isSteam) "Steam" else L("Title")

private fun mark(label: String, on: Boolean) = if (on) "$label  ✓" else label

/** A page with nothing to list yet: its title and one line. */
@Composable
private fun LabCommunityNotice(title: String, line: String, back: () -> Unit) {
    SimpleMenuPage(
        overline = L("Community configs"),
        title = title,
        sections = listOf(MenuSection("none", title, Icons.Filled.Public, emptyList(), empty = line)),
        onBack = back,
    )
}

/**
 * The rows of one game's configs, as Classic's sheet and catalog list them: "Matches my device", then one row per
 * uploaded config (votes and downloads; a ✓ on the ones from hardware like yours), or offline the configs by device.
 * A row opens the Apply / Details choice, with [ctx] the game it applies to (null from the catalog).
 */
@Composable
private fun communityConfigRows(
    vm: ShortcutsViewModel,
    game: CanonicalGame,
    userSoc: String?,
    userGpu: String?,
    hardwareLabel: String?,
    deviceModel: String?,
    fallback: List<CanonicalDevice>,
    ctx: Shortcut?,
    extraFolders: List<String> = emptyList(),
): List<MenuEntry> {
    val cfg = rememberGameConfigs(vm, game, extraBannerlatorFolders = extraFolders)
    var mine by rememberSaveable(game.identity) { mutableStateOf(false) }
    val hwOn = userSoc != null || userGpu != null
    val group = L("Configs")
    val rows = ArrayList<MenuEntry>()
    rows += MenuEntry(
        "mine", L("Matches my device"), L("Your device: %1\$s", deviceHeaderLabel(deviceModel, hardwareLabel)),
        icon = Icons.Filled.PhoneAndroid, toggle = mine && hwOn, group = group, onA = { if (hwOn) mine = !mine },
    )
    when {
        cfg.loading -> rows += MenuEntry("c-loading", L("Loading configs…"), group = group)
        cfg.entries.isNotEmpty() -> {
            val shown = if (!mine) cfg.entries
                else cfg.entries.filter { GameMatcher.hardwareMatchesUser(userSoc, userGpu, listOf(it.second.device, it.second.soc)) }
            if (shown.isEmpty()) rows += MenuEntry("c-none", L("No uploaded configs match your device."), group = group)
            shown.forEach { (folder, e) ->
                val isMatch = hwOn && GameMatcher.hardwareMatchesUser(userSoc, userGpu, listOf(e.device, e.soc))
                rows += MenuEntry(
                    key = "c:$folder/${e.filename}",
                    title = e.device.ifBlank { e.soc.ifBlank { e.filename } },
                    subtitle = listOf(e.soc, e.date).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                    trailing = "★ ${e.votes}   ↓ ${e.downloads}",
                    pills = if (e.appSource == "bannerlator") listOf("BANNERLATOR") else emptyList(),
                    current = isMatch,
                    group = group,
                    onA = { LabCommunity.chooser = communityFilePick(game, folder, e) to ctx },
                )
            }
        }
        fallback.isEmpty() -> rows += MenuEntry("c-none", L("No configs listed."), group = group)
        else -> {
            val offline = L("Device configs (vote counts unavailable offline)")
            val hw = hardwareLabel?.lowercase()
            val devs = if (!mine) fallback else fallback.filter { GameMatcher.deviceMatchesUser(it, userSoc, userGpu) }
            devs.forEachIndexed { i, d ->
                val isMatch = hw != null && (
                    (d.soc.isNotBlank() && (hw.contains(d.soc.lowercase()) || d.soc.lowercase().contains(hw))) ||
                        (d.gpu.isNotBlank() && (hw.contains(d.gpu.lowercase()) || d.gpu.lowercase().contains(hw)))
                    )
                rows += MenuEntry(
                    key = "d:$i:${d.label}",
                    title = d.model.ifBlank { L("Unknown device") },
                    subtitle = listOf(d.gpu, d.soc).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                    current = isMatch,
                    group = offline,
                    onA = { LabCommunity.chooser = CommunityPick.Device(game, d) to ctx },
                )
            }
        }
    }
    return rows
}

// ───────────────────────────── The catalog ─────────────────────────────

/**
 * Community configs: every game in the community index, with Classic's search, store filter, "Matches my device" and
 * sort, the index refresh and My account. A game opens its configs.
 */
@Composable
internal fun LabCommunityBrowserPage(open: (String) -> Unit, back: () -> Unit, vm: ShortcutsViewModel = viewModel()) {
    val context = LocalContext.current
    var catalog by remember { mutableStateOf<CommunityCatalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var mine by rememberSaveable { mutableStateOf(false) }
    var store by rememberSaveable { mutableStateOf(CatalogStoreFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(CatalogSort.CONFIGS) }
    var sheet by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.getCommunityCatalog { catalog = it; loading = false } }
    val userSoc = catalog?.userSoc
    val userGpu = catalog?.userGpu
    val games = catalog?.games.orEmpty()
    val hwOn = userSoc != null || userGpu != null
    val visible = remember(games, query, mine, store, sort, userSoc, userGpu) {
        communityVisibleGames(games, query, sort) { g ->
            val storeOk = when (store) {
                CatalogStoreFilter.ALL -> true
                CatalogStoreFilter.STEAM -> g.isSteam
                CatalogStoreFilter.TITLE -> !g.isSteam
            }
            storeOk && (!mine || g.devices.any { GameMatcher.deviceMatchesUser(it, userSoc, userGpu) })
        }
    }
    fun storeLabel(f: CatalogStoreFilter) = when (f) { CatalogStoreFilter.ALL -> L("All"); CatalogStoreFilter.STEAM -> "Steam"; CatalogStoreFilter.TITLE -> L("Title") }
    fun sortLabel(s: CatalogSort) = when (s) { CatalogSort.CONFIGS -> L("Configs"); CatalogSort.NAME -> L("Name"); CatalogSort.DEVICES -> L("Devices") }
    fun refresh() {
        if (refreshing) return
        refreshing = true
        vm.refreshCommunityIndex { fresh ->
            refreshing = false
            if (fresh != null) {
                catalog = fresh
                toast(context, L("Community index refreshed (%1\$s games)", fresh.games.size))
            } else toast(context, L("Couldn't refresh the community index."))
        }
    }
    val browse = L("Browse")
    val filters = listOf(
        MenuEntry(
            "search", L("Search all games"), query.ifBlank { null }, icon = Icons.Filled.Search, group = browse, onA = { sheet = "search" },
            actions = if (query.isNotBlank()) listOf(MenuAction(L("Search all games"), { sheet = "search" }), MenuAction(L("Clear search"), { query = "" })) else emptyList(),
        ),
        MenuEntry("store", L("Store"), trailing = storeLabel(store), icon = Icons.Filled.Storefront, group = browse, onA = { sheet = "store" }),
        MenuEntry("mine", L("Matches my device"), icon = Icons.Filled.PhoneAndroid, group = browse, toggle = mine && hwOn, onA = { if (hwOn) mine = !mine }),
        MenuEntry("sort", L("Sort"), trailing = sortLabel(sort), icon = Icons.Filled.SwapVert, group = browse, onA = { sheet = "sort" }),
        MenuEntry("refresh", L("Refresh community index"), if (refreshing) L("Refreshing…") else null, icon = Icons.Filled.Refresh, group = browse, onA = { refresh() }),
        MenuEntry("account", L("My account"), AccountUiBus.account?.username, icon = Icons.Filled.AccountCircle, group = browse, onA = { open(LabCommunityRoutes.ACCOUNT) }),
    )
    val gamesGroup = if (visible.size == 1) L("1 game") else L("%1\$s games", visible.size)
    val gameRows = remember(visible, userGpu, loading, games.isEmpty()) {
        when {
            loading -> listOf(MenuEntry("g-loading", L("Loading the community index…"), group = L("Games")))
            games.isEmpty() -> listOf(MenuEntry("g-none", L("No community configs available yet (offline, or the index hasn't been fetched)."), group = L("Games")))
            visible.isEmpty() -> listOf(MenuEntry("g-none", L("No games match your filters."), group = gamesGroup))
            else -> visible.map { g ->
                MenuEntry(
                    "g:" + g.identity, g.name, CanonicalGame.countsLine(g, userGpu), trailing = storeName(g), group = gamesGroup,
                    onA = { open(LabCommunityRoutes.game(g.identity)) },
                )
            }
        }
    }
    SimpleMenuPage(
        overline = L("Community"),
        title = L("Community configs"),
        sections = listOf(MenuSection("games", L("Games"), Icons.Filled.Public, filters + gameRows)),
        onBack = back,
        banner = catalog?.let { L("Your device: %1\$s", deviceHeaderLabel(it.deviceModel, it.hardwareLabel)) },
    )
    when (sheet) {
        "search" -> LabInputDialog(
            L("Search all games"), query, L("Game name"), L("Search"), onDismiss = { sheet = null }, allowBlank = true,
        ) { q -> query = q.trim(); sheet = null }
        "store" -> SimpleActionSheet(
            L("Store"), CatalogStoreFilter.entries.map { f -> MenuAction(mark(storeLabel(f), f == store), { store = f }) }, onDismiss = { sheet = null },
        ) { a -> sheet = null; a.run() }
        "sort" -> SimpleActionSheet(
            L("Sort"), CatalogSort.entries.map { s -> MenuAction(mark(sortLabel(s), s == sort), { sort = s }) }, onDismiss = { sheet = null },
        ) { a -> sheet = null; a.run() }
    }
}

/** One catalog game: its counts and store, "Matches my device" and its configs (each: Apply to game… or View details). */
@Composable
internal fun LabCommunityGamePage(identity: String, back: () -> Unit, vm: ShortcutsViewModel = viewModel()) {
    var catalog by remember { mutableStateOf<CommunityCatalog?>(null) }
    LaunchedEffect(Unit) { vm.getCommunityCatalog { catalog = it } }
    val cat = catalog ?: run { LabCommunityNotice(L("Community configs"), L("Loading the community index…"), back); return }
    val game = cat.games.firstOrNull { it.identity == identity }
        ?: run { LabCommunityNotice(L("Community configs"), L("This game is no longer in the community index."), back); return }
    val fallback = remember(game, cat.userSoc, cat.userGpu) { GameMatcher.rankDevices(game.devices, cat.userSoc, cat.userGpu) }
    val rows = communityConfigRows(vm, game, cat.userSoc, cat.userGpu, cat.hardwareLabel, cat.deviceModel, fallback, ctx = null)
    SimpleMenuPage(
        overline = L("Community configs"),
        title = game.name,
        sections = listOf(MenuSection("configs", L("Configs"), Icons.Filled.Public, rows)),
        onBack = back,
        banner = CanonicalGame.countsLine(game, cat.userGpu) + " · " + storeName(game),
    )
}

// ───────────────────────────── A game's own configs ─────────────────────────────

/**
 * A game's Community configs (its game menu), as Classic's sheet: the matched community game (or "Which game is this?"
 * on a tie, or a search of all games), "Matches my device" and its configs; Share config, Import, Upload to community and
 * My uploads under Share.
 */
@Composable
internal fun LabGameCommunityPage(path: String, open: (String) -> Unit, back: () -> Unit, vm: ShortcutsViewModel = viewModel()) {
    val context = LocalContext.current
    val s = remember(path) { loadLabShortcut(context, path) }
        ?: run { LabCommunityNotice(L("Community configs"), L("This game is no longer in your library."), back); return }
    LaunchedEffect(s.file.path) { LabArt.selected = s }
    var result by remember(s.file.path) { mutableStateOf<CommunityMatchResult?>(null) }
    var loading by remember(s.file.path) { mutableStateOf(true) }
    var query by rememberSaveable(s.file.path) { mutableStateOf("") }
    var found by remember(s.file.path) { mutableStateOf<List<CanonicalGame>>(emptyList()) }
    var searchOpen by remember { mutableStateOf(false) }
    LaunchedEffect(s.file.path) { vm.matchCommunityConfigs(s) { result = it; loading = false } }
    LaunchedEffect(query) {
        if (query.trim().length >= 2) vm.searchCommunityGames(query) { found = it } else found = emptyList()
    }
    // A config file picked here applies straight to this game.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { uri -> LabCommunity.runImport(s, uri) }
    }
    val name = s.name
    val rows = ArrayList<MenuEntry>()
    rows += MenuEntry(
        "search", L("Search all games"), query.ifBlank { null }, icon = Icons.Filled.Search, onA = { searchOpen = true },
        actions = if (query.isNotBlank()) listOf(MenuAction(L("Search all games"), { searchOpen = true }), MenuAction(L("Clear search"), { query = "" })) else emptyList(),
    )
    val game = result?.match
    if (query.trim().length >= 2) {
        val g = L("Results for \"%1\$s\"", query.trim())
        if (found.isEmpty()) rows += MenuEntry("s-none", L("No games match \"%1\$s\".", query.trim()), group = g)
        found.forEach { cg ->
            rows += MenuEntry(
                "s:" + cg.identity, cg.name, null, trailing = "${cg.configCount} · ${storeName(cg)}", group = g,
                onA = {
                    // A game picked by search is remembered for this game next time (issue #167), as a tie's pick is.
                    vm.rememberCommunityGame(s, cg)
                    vm.selectCommunityGame(cg) { result = it }
                    query = ""
                },
            )
        }
    } else when {
        loading -> rows += MenuEntry("m-loading", L("Matching \"%1\$s\"…", name))
        game == null -> rows += MenuEntry("m-none", L("No auto-match for \"%1\$s\" — search above to pick one.", name))
        else -> {
            val ties = result?.alternatives.orEmpty()
            if (ties.size > 1) {
                val q = L("Which game is this?")
                ties.forEach { alt ->
                    rows += MenuEntry(
                        "t:" + alt.identity, alt.name, null, trailing = storeName(alt), current = alt.identity == game.identity, group = q,
                        onA = { vm.rememberCommunityGame(s, alt); vm.selectCommunityGame(alt) { result = it } },
                    )
                }
            }
            rows += MenuEntry(
                "game", game.name, CanonicalGame.countsLine(game, result?.userGpu), trailing = storeName(game),
                icon = Icons.Filled.Info, group = L("Matched game"),
            )
            // The game's own folder as the exporter keys uploads, so the user's own Bannerlator upload shows too.
            val myFolder = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            rows += communityConfigRows(
                vm, game, result?.userSoc, result?.userGpu, result?.userHardwareLabel, DeviceIdentity.deviceModel(),
                result?.rankedDevices.orEmpty(), ctx = s, extraFolders = listOf(myFolder),
            )
        }
    }
    val uploading = LabCommunity.uploadingFor == s.file.path
    val share = listOf(
        MenuEntry("export", L("Share config"), null, icon = Icons.Filled.Share, onA = { LabCommunity.export(s) }),
        MenuEntry(
            "import", L("Import"), null, icon = Icons.Filled.FileUpload,
            onA = { importer.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.JSON, L("Select a config .json"))) },
        ),
        MenuEntry(
            "upload", L("Upload to community"),
            if (uploading) (if (LabCommunity.uploadStarted) L("Uploading…") else L("Preparing…")) else null,
            icon = Icons.Filled.CloudUpload, onA = { if (!uploading) LabCommunity.upload(context, s) },
        ),
        MenuEntry("uploads", L("My uploads"), null, icon = Icons.Filled.AccountCircle, onA = { open(LabCommunityRoutes.UPLOADS) }),
    )
    SimpleMenuPage(
        overline = L("Community configs"),
        title = LabTitles.of(s),
        sections = listOf(
            MenuSection("configs", L("Configs"), Icons.Filled.Public, rows),
            MenuSection("share", L("Share"), Icons.Filled.Share, share),
        ),
        onBack = back,
    )
    if (searchOpen) {
        LabInputDialog(L("Search all games"), query, L("Game name"), L("Search"), onDismiss = { searchOpen = false }, allowBlank = true) { q ->
            query = q.trim()
            searchOpen = false
        }
    }
}

// ───────────────────────────── Config details ─────────────────────────────

/**
 * One config's details, as Classic's page: where it comes from, what it sets in this app's terms, the changes it would
 * make to the game it was opened for, and (when the community server lists it) its votes, description and comments.
 * Apply runs the same apply flow.
 */
@Composable
internal fun LabConfigDetailsPage(back: () -> Unit, vm: ShortcutsViewModel = viewModel()) {
    val context = LocalContext.current
    val picked = LabCommunity.detail ?: run { LabCommunityNotice(L("Config details"), L("Nothing to show."), back); return }
    val (pick, ctx) = picked
    var detail by remember(pick, ctx) { mutableStateOf<CommunityConfigDetail?>(null) }
    var loading by remember(pick, ctx) { mutableStateOf(true) }
    var failed by remember(pick, ctx) { mutableStateOf(false) }
    LaunchedEffect(pick, ctx) {
        loading = true
        failed = false
        val got: (CommunityConfigDetail?) -> Unit = { d -> detail = d; loading = false; failed = d == null }
        when (pick) {
            is CommunityPick.File -> vm.loadCommunityConfigDetail(pick.ref, ctx, got)
            is CommunityPick.Device -> vm.loadCommunityConfigDetail(pick.game, pick.device, ctx, got)
        }
    }
    val game = pick.game
    val device = when (pick) {
        is CommunityPick.File -> CanonicalDevice(pick.entry.device, "", pick.entry.soc)
        is CommunityPick.Device -> pick.device
    }
    // Votes and comments, seeded from the details once they arrive; a vote is remembered per config as Classic's is.
    val votePrefs = remember { context.getSharedPreferences("banner_config_votes", android.content.Context.MODE_PRIVATE) }
    var votes by remember(detail) { mutableStateOf(detail?.votes ?: 0) }
    var comments by remember(detail) { mutableStateOf(detail?.comments ?: emptyList()) }
    var voted by remember(detail) { mutableStateOf(detail?.sha?.let { votePrefs.getBoolean(it, false) } ?: false) }
    var voting by remember(detail) { mutableStateOf(false) }
    var commenting by remember(detail) { mutableStateOf(false) }
    var commentOpen by remember { mutableStateOf(false) }

    val d = detail
    val rows = ArrayList<MenuEntry>()
    rows += MenuEntry(
        "apply", if (ctx != null) L("Apply to %1\$s", LabTitles.of(ctx)) else L("Apply to game…"), null, icon = Icons.Filled.Check, primary = true,
        onA = { if (d != null) { back(); LabCommunity.startApply(pick, ctx) } else toast(context, L("Couldn't fetch this config.")) },
    )
    val from = L("From")
    val meta = d?.meta
    rows += MenuEntry("p-game", game.name, null, trailing = storeName(game), group = from)
    val hw = listOfNotNull(meta?.device ?: device.model.ifBlank { null }, device.gpu.ifBlank { null }, meta?.soc ?: device.soc.ifBlank { null }).distinct().joinToString(" · ")
    if (hw.isNotEmpty()) rows += MenuEntry("p-hw", hw, null, icon = Icons.Filled.PhoneAndroid, group = from)
    meta?.uploadedDate?.let { rows += MenuEntry("p-date", L("Uploaded %1\$s", it), null, group = from) }
    if (meta != null) {
        rows += MenuEntry("p-src", L("From %1\$s", communitySourceLabel(meta.appSource)) + (meta.bhVersion?.let { " $it" } ?: ""), null, group = from)
        rows += MenuEntry(
            "p-by", meta.uploaderName?.let { L("by %1\$s", it) } ?: L("Anonymous user"), null, group = from,
            thumb = { AccountAvatar(avatarUrl = meta.uploaderAvatarUrl, size = 24.dp, fallbackTint = SimpleMuted) },
        )
    }
    when {
        loading -> rows += MenuEntry("x-loading", L("Loading config…"))
        failed || d == null -> rows += MenuEntry("x-failed", L("Couldn't fetch this config (offline, or no matching file in the repo)."))
        else -> {
            val sets = L("What this config sets")
            val lines = configSummaryLines(d.config)
            if (lines.isEmpty()) rows += MenuEntry("s-none", L("Nothing this app can set."), group = sets)
            lines.forEachIndexed { i, (label, value) -> rows += MenuEntry("s:$i", label, value, group = sets) }
            d.config.advisories["wineVersion"]?.let { proton -> rows += MenuEntry("s-proton", L("Proton (container-only)"), proton, group = sets) }
            d.preview?.let { pre ->
                val ch = L("Changes to \"%1\$s\"", game.name)
                rows += MenuEntry("v-msg", pre.message, null, group = ch)
                pre.changed.forEachIndexed { i, t -> rows += MenuEntry("v-c$i", t, L("Would change"), group = ch) }
                pre.missingComponents.forEachIndexed { i, m -> rows += MenuEntry("v-m$i", m.label, L("Needs a component"), group = ch) }
                pre.missingDrivers.forEachIndexed { i, m ->
                    rows += MenuEntry("v-d$i", L("Turnip %1\$s", m.wanted) + (m.current?.let { c -> L(" (you have %1\$s)", c) } ?: ""), L("Needs a GPU driver"), group = ch)
                }
                pre.advisories.forEachIndexed { i, t -> rows += MenuEntry("v-a$i", t, L("Heads up"), group = ch) }
            }
        }
    }
    val sections = arrayListOf(MenuSection("config", L("Config"), Icons.Filled.Info, rows))
    val workerGame = d?.workerGame
    if (d != null && workerGame != null) {
        val social = ArrayList<MenuEntry>()
        social += MenuEntry(
            "votes", when { voting -> L("Voting…"); voted -> L("Voted ✓"); else -> L("Upvote") }, "★ $votes   ↓ ${d.downloads}",
            icon = Icons.Filled.ThumbUp,
            onA = onA@{
                val sha = d.sha ?: return@onA
                if (voted || voting) return@onA
                voting = true
                vm.voteConfig(sha, workerGame, d.fileName) { n ->
                    voting = false
                    if (n != null) {
                        votes = n
                        voted = true
                        votePrefs.edit().putBoolean(sha, true).apply()
                    } else toast(context, L("Couldn't record your vote."))
                }
            },
        )
        if (d.description.isNotBlank()) social += MenuEntry("desc", d.description, null, icon = Icons.Filled.Star)
        val cg = L("Comments")
        if (comments.isEmpty()) social += MenuEntry("c-none", L("No comments yet."), group = cg)
        comments.forEachIndexed { i, c ->
            social += MenuEntry("c:$i", c.text, listOf(c.device, c.date).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }, group = cg)
        }
        social += MenuEntry(
            "comment", if (commenting) L("Sending…") else L("Add a comment"), null, icon = Icons.Filled.Comment, primary = true,
            onA = { if (!commenting) commentOpen = true },
        )
        sections += MenuSection("social", L("Votes and comments"), Icons.Filled.ThumbUp, social)
    }
    SimpleMenuPage(overline = L("Config details"), title = game.name, sections = sections, onBack = back)
    if (commentOpen && d != null && workerGame != null) {
        LabInputDialog(L("Add a comment"), "", L("Comment"), L("Send"), onDismiss = { commentOpen = false }, multiline = true, maxLength = 500) { text ->
            commentOpen = false
            val t = text.trim()
            if (t.isEmpty()) return@LabInputDialog
            commenting = true
            vm.addConfigComment(workerGame, d.fileName, t, Build.MANUFACTURER + "_" + Build.MODEL) { refreshed ->
                commenting = false
                if (refreshed != null) comments = refreshed else toast(context, L("Couldn't post your comment."))
            }
        }
    }
}

// ───────────────────────────── My uploads ─────────────────────────────

/** My uploads: the configs the user shared, with their votes and downloads; each can get a new description or be deleted. */
@Composable
internal fun LabMyUploadsPage(back: () -> Unit, vm: ShortcutsViewModel = viewModel()) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<MyUploadRow>?>(null) }
    val account = AccountUiBus.account
    // Read again when the page shows and after a sign-in (a sign-in brings the account's uploads to this device).
    LaunchedEffect(account?.userId) { vm.loadMyUploads { rows = it } }
    var editFor by remember { mutableStateOf<MyUploadRow?>(null) }
    var descLoaded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editFor) {
        val r = editFor ?: return@LaunchedEffect
        descLoaded = null
        vm.loadMyUploadDescription(r) { descLoaded = it }
    }
    val list = rows
    val entries = list.orEmpty().map { row ->
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(row.record.date))
        MenuEntry(
            key = "u:" + row.record.sha + row.record.filename,
            title = row.record.game,
            subtitle = (listOf(row.record.device, row.record.soc, date).filter { it.isNotBlank() } + (if (row.stillOnline) L("Online") else L("Removed"))).joinToString(" · "),
            trailing = "★${row.votes}  ↓${row.downloads}",
            icon = Icons.Filled.CloudUpload,
            actions = listOf(
                MenuAction(L("Edit description"), { editFor = row }),
                MenuAction(
                    L("Delete"),
                    {
                        vm.deleteMyUpload(row) { ok ->
                            if (ok) {
                                rows = rows?.filterNot { it.record.sha == row.record.sha }
                                toast(context, L("Deleted your shared config."))
                            } else toast(context, L("Couldn't reach the server."))
                        }
                    },
                    danger = true,
                    confirm = L("Delete your shared config for \"%1\$s\"?", row.record.game),
                ),
            ),
        )
    }
    SimpleMenuPage(
        overline = L("Community configs"),
        title = L("My uploads"),
        sections = listOf(
            MenuSection(
                "uploads", L("My uploads"), Icons.Filled.CloudUpload, entries,
                empty = if (list == null) L("Loading…") else L("You haven't shared any configs yet."),
            ),
        ),
        onBack = back,
        banner = list?.takeIf { it.isNotEmpty() }?.let { r ->
            (if (r.size == 1) L("Shared 1 config") else L("Shared %1\$s configs", r.size)) + " · ↓ ${r.sumOf { it.downloads }} · ★ ${r.sumOf { it.votes }}"
        },
    )
    editFor?.let { row ->
        val loaded = descLoaded
        if (loaded == null) {
            LabPanelDialog(L("Description"), onDismiss = { editFor = null }, buttons = listOf(LabDialogButton(L("Cancel"), { editFor = null }))) {
                LabBusyLine(L("Loading description…"))
            }
        } else {
            LabInputDialog(
                L("Description"), loaded, L("Description"), L("Save"), onDismiss = { editFor = null }, multiline = true, maxLength = 500, allowBlank = true,
                message = row.record.game,
            ) { text ->
                editFor = null
                vm.editMyUploadDescription(row, text) { ok -> toast(context, if (ok) L("Description updated.") else L("Couldn't reach the server.")) }
            }
        }
    }
}

// ───────────────────────────── My account ─────────────────────────────

/**
 * My account (optional; it only recovers and names the configs a user shares), as Classic's sheet: signed out, Create
 * account, Log in and the reset with the recovery key; right after creating one, the one-time recovery key; signed in,
 * the picture, the recovery key, My uploads and Log out.
 */
@Composable
internal fun LabMyAccountPage(open: (String) -> Unit, back: () -> Unit, vm: ShortcutsViewModel = viewModel()) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(AccountManager.current(context)) }
    var justCreated by remember { mutableStateOf<AccountManager.CreateData?>(null) }
    var form by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var avatarBusy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        avatarBusy = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { compressAvatar(context, uri) }
            if (bytes == null) {
                avatarBusy = false
                toast(context, L("Couldn't read that image."))
                return@launch
            }
            val r = withContext(Dispatchers.IO) { AccountManager.uploadAvatar(context, bytes, "image/jpeg") }
            avatarBusy = false
            when (r) {
                is AccountManager.AccountResult.Success -> {
                    account = AccountManager.current(context)
                    AccountUiBus.refresh(context)
                    toast(context, L("Profile picture updated."))
                }
                is AccountManager.AccountResult.Error -> toast(context, L(avatarErrorMessage(r.code)))
            }
        }
    }
    val acc = account
    val entries = if (acc != null) listOf(
        MenuEntry(
            "pic", acc.username, if (avatarBusy) L("Uploading…") else L("Change picture"),
            thumb = { AccountAvatar(avatarUrl = acc.displayAvatarUrl, size = 40.dp, fallbackTint = MaterialTheme.colorScheme.primary) },
            onA = { if (!avatarBusy) picker.launch("image/*") },
        ),
        MenuEntry("key", L("Show my recovery key"), null, icon = Icons.Filled.Visibility, onA = { showKey = true }),
        MenuEntry("uploads", L("My uploads"), null, icon = Icons.Filled.CloudUpload, onA = { open(LabCommunityRoutes.UPLOADS) }),
        MenuEntry(
            "logout", L("Log out"), null, icon = Icons.AutoMirrored.Filled.Logout,
            onA = {
                AccountManager.logout(context)
                account = null
                showKey = false
                AccountUiBus.refresh(context)
            },
        ),
    ) else listOf(
        MenuEntry("create", L("Create account"), null, icon = Icons.Filled.PersonAdd, onA = { form = "create" }),
        MenuEntry("login", L("Log in"), null, icon = Icons.AutoMirrored.Filled.Login, onA = { form = "login" }),
        MenuEntry("reset", L("Forgot password? Reset with recovery key"), null, icon = Icons.Filled.LockOpen, onA = { form = "reset" }),
    )
    SimpleMenuPage(
        overline = L("Community configs"),
        title = L("My account"),
        sections = listOf(MenuSection("account", L("My account"), Icons.Filled.AccountCircle, entries)),
        onBack = back,
    )

    form?.let { f ->
        LabAccountForm(
            kind = f, vm = vm, onDismiss = { form = null },
            onCreated = { data -> form = null; justCreated = data },
            onSignedIn = { form = null; account = AccountManager.current(context); AccountUiBus.refresh(context) },
        )
    }
    justCreated?.let { data ->
        val saved = {
            justCreated = null
            account = AccountManager.current(context)
            AccountUiBus.refresh(context)
        }
        LabPanelDialog(
            L("Your recovery key"),
            onDismiss = saved,
            buttons = listOf(
                LabDialogButton(L("Copy"), { clipboard.setText(AnnotatedString(data.recoveryKey)); toast(context, L("Recovery key copied.")) }),
                LabDialogButton(L("I've saved it"), saved, primary = true),
            ),
        ) {
            LabDialogText(L("Account \"%1\$s\" created.", data.username))
            Text(data.recoveryKey, color = SimpleStrong, fontSize = 18.sp)
            LabDialogText(L("⚠️ Save this — it's the only way to reset your password if you forget it."), muted = true)
        }
    }
    if (showKey) {
        val key = remember { AccountManager.recoveryKey(context) }
        LabPanelDialog(
            L("My recovery key"),
            onDismiss = { showKey = false },
            buttons = listOfNotNull(
                key?.let { k -> LabDialogButton(L("Copy"), { clipboard.setText(AnnotatedString(k)); toast(context, L("Recovery key copied.")) }) },
                LabDialogButton(L("Close"), { showKey = false }, primary = true),
            ),
        ) {
            if (key != null) Text(key, color = SimpleStrong, fontSize = 18.sp)
            else LabDialogText(L("No recovery key is saved on this device."), muted = true)
        }
    }
}

/** Create account, Log in or Reset password: Classic's fields, checks and messages, in a Lab pop-up. */
@Composable
private fun LabAccountForm(
    kind: String,
    vm: ShortcutsViewModel,
    onDismiss: () -> Unit,
    onCreated: (AccountManager.CreateData) -> Unit,
    onSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val reset = kind == "reset"
    val ready = !busy && username.isNotBlank() && password.isNotBlank() && (!reset || key.isNotBlank())
    fun submit() {
        if (!ready) return
        busy = true
        error = null
        when (kind) {
            "create" -> vm.createAccount(username.trim(), password) { r ->
                busy = false
                when (r) {
                    is AccountManager.AccountResult.Success -> onCreated(r.data)
                    is AccountManager.AccountResult.Error -> error = L(accountErrorMessage(r.code, isReset = false))
                }
            }
            "login" -> vm.loginAccount(username.trim(), password) { r ->
                busy = false
                when (r) {
                    is AccountManager.AccountResult.Success -> { toast(context, L("Signed in.")); onSignedIn() }
                    is AccountManager.AccountResult.Error -> error = L(accountErrorMessage(r.code, isReset = false))
                }
            }
            else -> vm.resetAccountPassword(username.trim(), key.trim(), password) { r ->
                busy = false
                when (r) {
                    is AccountManager.AccountResult.Success -> { toast(context, L("Password reset — you're signed in.")); onSignedIn() }
                    is AccountManager.AccountResult.Error -> error = L(accountErrorMessage(r.code, isReset = true))
                }
            }
        }
    }
    val title = when (kind) { "create" -> L("Create account"); "login" -> L("Log in"); else -> L("Reset password") }
    val confirm = when {
        busy -> L("Please wait…")
        kind == "create" -> L("Create account")
        kind == "login" -> L("Log in")
        else -> L("Reset")
    }
    LabPanelDialog(
        title,
        onDismiss = { if (!busy) onDismiss() },
        dismissable = !busy,
        initialFocus = first,
        buttons = listOf(
            LabDialogButton(L("Cancel"), onDismiss, enabled = !busy),
            LabDialogButton(confirm, { submit() }, primary = true, enabled = ready),
        ),
    ) {
        if (reset) LabDialogText(L("Enter your username, your recovery key, and a new password."), muted = true)
        LabTextField(username, { username = it; error = null }, L("Username"), requester = first, enabled = !busy)
        if (reset) LabTextField(key, { key = it; error = null }, L("Recovery key"), enabled = !busy)
        LabTextField(password, { password = it; error = null }, if (reset) L("New password") else L("Password"), password = true, enabled = !busy)
        if (kind == "create") {
            LabDialogText(L("This is just to recover your shared configs — use a throwaway password, not one you use elsewhere."), muted = true)
        }
        error?.let { LabDialogText(it, error = true) }
    }
}
