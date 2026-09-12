package com.winlator.star.ui.screens

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.Base64
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import com.winlator.star.communityconfigs.AccountManager
import com.winlator.star.communityconfigs.CanonicalDevice
import com.winlator.star.communityconfigs.CanonicalGame
import com.winlator.star.communityconfigs.CommunityConfigApply
import com.winlator.star.communityconfigs.CommunityConfigFetcher
import com.winlator.star.communityconfigs.CommunityConfigRef
import com.winlator.star.communityconfigs.CommunityConfigRepository
import com.winlator.star.communityconfigs.CommunityConfigWorker
import com.winlator.star.communityconfigs.ConfigMeta
import com.winlator.star.communityconfigs.ConfigTranslator
import com.winlator.star.communityconfigs.DeviceIdentity
import com.winlator.star.communityconfigs.GameMatcher
import com.winlator.star.communityconfigs.InstalledComponents
import com.winlator.star.communityconfigs.ShortcutExporter
import com.winlator.star.communityconfigs.UploadedConfigsStore
import com.winlator.star.communityconfigs.WorkerComment
import com.winlator.star.communityconfigs.WorkerConfigEntry
import com.winlator.star.container.Shortcut
import com.winlator.star.core.GPUInformation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Community configs as XMB columns — the per-game sheet (communityConfigsFor) without pop-ups:
//   Community configs  (share / import / upload / my uploads · search · match · the game's configs)
//     › Config details (Apply first, then provenance, what it sets, the diff, votes and comments)
//       › Apply config (progress, then the result the sheet showed)
// The ViewModel calls behind the sheet aren't reachable from here (no VM in XmbScope), so
// [XmbCommunityData] re-issues the same repository / worker / apply-engine calls, line for line.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** The community calls ShortcutsViewModel makes for the sheet, on the same lower-level APIs. BLOCKING unless suspend. */
private object XmbCommunityData {
    // Same tie rule as the ViewModel: scores within this of the top one are a genuine tie (issue #167).
    private const val TIE_EPSILON = 1e-6
    private const val MAX_TIE_ALTERNATIVES = 6

    @Volatile private var repo: CommunityConfigRepository? = null

    private fun repo(context: Context): CommunityConfigRepository =
        repo ?: synchronized(this) {
            repo ?: CommunityConfigRepository(context.applicationContext).also { repo = it }
        }

    /** ShortcutsViewModel.matchCommunityConfigs: remembered pick, then Steam appId, then the name match. */
    fun match(context: Context, shortcut: Shortcut): CommunityMatchResult {
        val games = repo(context).getGames()
        val ranked = GameMatcher.match(shortcut.name, games)
        val rememberedId = shortcut.getExtra("communityGameIdentity", "")
        val remembered = rememberedId.takeIf { it.isNotBlank() }?.let { id -> games.firstOrNull { it.identity == id } }
        val appId = shortcut.getExtra("steamAppId", "").takeIf { it.isNotBlank() }
        val byAppId = if (remembered != null) null else appId?.let { id -> games.firstOrNull { it.steamAppId == id } }
        val best = remembered ?: byAppId ?: ranked.firstOrNull()?.game
        val alternatives = if (remembered != null || byAppId != null) emptyList() else {
            val top = ranked.firstOrNull()?.score
            if (top == null) emptyList()
            else ranked.filter { top - it.score <= TIE_EPSILON }
                .map { it.game }
                .take(MAX_TIE_ALTERNATIVES)
                .let { if (it.size <= 1) emptyList() else it }
        }
        val userSoc = DeviceIdentity.soc()
        val userGpu = DeviceIdentity.gpu(context)
        val devices = best?.let { GameMatcher.rankDevices(it.devices, userSoc, userGpu) } ?: emptyList()
        return CommunityMatchResult(
            query = shortcut.name,
            match = best,
            rankedDevices = devices,
            userHardwareLabel = userSoc ?: userGpu,
            userSoc = userSoc,
            userGpu = userGpu,
            alternatives = alternatives,
        )
    }

    /** ShortcutsViewModel.selectCommunityGame: the same suggest view for a game picked by hand. */
    fun select(context: Context, game: CanonicalGame): CommunityMatchResult {
        val userSoc = DeviceIdentity.soc()
        val userGpu = DeviceIdentity.gpu(context)
        return CommunityMatchResult(
            query = game.name,
            match = game,
            rankedDevices = GameMatcher.rankDevices(game.devices, userSoc, userGpu),
            userHardwareLabel = userSoc ?: userGpu,
            userSoc = userSoc,
            userGpu = userGpu,
        )
    }

    fun search(context: Context, query: String): List<CanonicalGame> = GameMatcher.search(query, repo(context).getGames())

    /**
     * ShortcutsViewModel.fetchGameConfigs: every folder of [game] in both namespaces (plus [extra]
     * in ours), merged, deduped by sha, votes-desc then date-desc.
     */
    suspend fun configs(game: CanonicalGame, extra: List<String>): List<Pair<String, WorkerConfigEntry>> =
        withContext(Dispatchers.IO) {
            val keys = game.folders.ifEmpty { listOf(game.name) }.distinct()
            val extras = extra.filter { it.isNotBlank() }.distinct()
            val jobs = ArrayList<Deferred<Pair<String, List<WorkerConfigEntry>>>>()
            for (key in keys) {
                jobs.add(async { key to CommunityConfigWorker.list(key) })
                jobs.add(async { key to CommunityConfigWorker.list(key, "bannerlator") })
            }
            for (key in extras) jobs.add(async { key to CommunityConfigWorker.list(key, "bannerlator") })
            val seen = HashSet<String>()
            val out = ArrayList<Pair<String, WorkerConfigEntry>>()
            for ((folder, list) in jobs.awaitAll()) {
                for (entry in list) {
                    val dedupKey = entry.sha.ifBlank { "$folder/${entry.filename}" }
                    if (seen.add(dedupKey)) out.add(folder to entry)
                }
            }
            out.sortedWith(
                compareByDescending<Pair<String, WorkerConfigEntry>> { it.second.votes }
                    .thenByDescending { it.second.date }
            )
        }

    private fun applyConfig(context: Context, shortcut: Shortcut, json: JSONObject): CommunityConfigApply.ConfigApplyResult =
        CommunityConfigApply.apply(
            shortcut = shortcut,
            config = ConfigTranslator.translate(json),
            installed = InstalledComponents.read(context),
            containerWineVersion = shortcut.container?.wineVersion,
            isAdreno = GPUInformation.isAdrenoGPU(context),
        )

    /** ShortcutsViewModel.applyCommunityConfigFile. */
    fun applyFile(context: Context, shortcut: Shortcut, ref: CommunityConfigRef): CommunityConfigApply.ConfigApplyResult {
        val fetched = CommunityConfigFetcher.fetchForFile(ref.workerGame, ref.filename, ref.ns)
            ?: return CommunityConfigApply.ConfigApplyResult(
                ok = false,
                message = "Couldn't fetch that config (offline, or it's no longer in the repo).",
            )
        return applyConfig(context, shortcut, fetched.json)
    }

    /** ShortcutsViewModel.applyCommunityConfig (the offline per-device fallback). */
    fun applyDevice(context: Context, shortcut: Shortcut, game: CanonicalGame, device: CanonicalDevice): CommunityConfigApply.ConfigApplyResult {
        val fetched = CommunityConfigFetcher.fetchForDevice(game, device)
            ?: return CommunityConfigApply.ConfigApplyResult(
                ok = false,
                message = "Couldn't fetch a config for ${device.model.ifBlank { "that device" }} " +
                    "(offline, or no matching file in the repo).",
            )
        return applyConfig(context, shortcut, fetched.json)
    }

    /** ShortcutsViewModel.importConfigFile, for a file picked in the XMB browser. */
    fun importFile(context: Context, file: File, target: Shortcut): CommunityConfigApply.ConfigApplyResult = try {
        val text = runCatching { file.readText() }.getOrNull()
        if (text == null) {
            CommunityConfigApply.ConfigApplyResult(ok = false, message = "Couldn't read that file.")
        } else {
            applyConfig(context, target, JSONObject(text))
        }
    } catch (e: Exception) {
        CommunityConfigApply.ConfigApplyResult(ok = false, message = "That file isn't a valid config (${e.message ?: "parse error"}).")
    }

    private fun preview(context: Context, target: Shortcut?, json: JSONObject): CommunityConfigApply.ConfigApplyResult? =
        target?.let {
            CommunityConfigApply.preview(
                shortcut = it,
                config = ConfigTranslator.translate(json),
                installed = InstalledComponents.read(context),
                containerWineVersion = it.container?.wineVersion,
                isAdreno = GPUInformation.isAdrenoGPU(context),
            )
        }

    /** ShortcutsViewModel.loadCommunityConfigDetail(ref, …): the exact file plus its social layer. */
    fun detailFile(context: Context, ref: CommunityConfigRef, target: Shortcut?): CommunityConfigDetail? {
        val fetched = CommunityConfigFetcher.fetchForFile(ref.workerGame, ref.filename, ref.ns) ?: return null
        val config = ConfigTranslator.translate(fetched.json)
        val meta = ConfigMeta.parse(fetched.json.optJSONObject("meta"), fetched.fileName)
        val preview = preview(context, target, fetched.json)
        var sha = ref.sha
        var votes = 0
        var downloads = 0
        CommunityConfigWorker.list(ref.workerGame, ref.ns).firstOrNull { it.filename == ref.filename }?.let { e ->
            if (sha.isNullOrBlank()) sha = e.sha.ifBlank { null }
            votes = e.votes
            downloads = e.downloads
        }
        val description = sha?.let { CommunityConfigWorker.desc(it) } ?: ""
        val comments = CommunityConfigWorker.comments(ref.workerGame, ref.filename)
        val device = CanonicalDevice(model = meta.device ?: "", gpu = "", soc = meta.soc ?: "")
        return CommunityConfigDetail(
            ref.game, device, fetched.fileName, meta, config, preview,
            sha = sha, workerGame = ref.workerGame, votes = votes, downloads = downloads,
            description = description, comments = comments,
        )
    }

    /** ShortcutsViewModel.loadCommunityConfigDetail(game, device, …): best file for a device row. */
    fun detailDevice(context: Context, game: CanonicalGame, device: CanonicalDevice, target: Shortcut?): CommunityConfigDetail? {
        val fetched = CommunityConfigFetcher.fetchForDevice(game, device) ?: return null
        val config = ConfigTranslator.translate(fetched.json)
        val meta = ConfigMeta.parse(fetched.json.optJSONObject("meta"), fetched.fileName)
        val preview = preview(context, target, fetched.json)
        var sha: String? = null
        var workerGame: String? = null
        var votes = 0
        var downloads = 0
        for (key in (game.folders + game.name).distinct()) {
            val entry = CommunityConfigWorker.list(key).firstOrNull { it.filename == fetched.fileName } ?: continue
            sha = entry.sha.ifBlank { null }
            workerGame = key
            votes = entry.votes
            downloads = entry.downloads
            break
        }
        val description = sha?.let { CommunityConfigWorker.desc(it) } ?: ""
        val comments = workerGame?.let { CommunityConfigWorker.comments(it, fetched.fileName) } ?: emptyList()
        return CommunityConfigDetail(
            game, device, fetched.fileName, meta, config, preview,
            sha = sha, workerGame = workerGame, votes = votes, downloads = downloads,
            description = description, comments = comments,
        )
    }

    /** ShortcutsViewModel.loadMyUploads: local records + live votes/downloads. */
    fun myUploads(context: Context): List<MyUploadRow> =
        UploadedConfigsStore.all(context).map { rec ->
            val live = CommunityConfigWorker.list(rec.game, "bannerlator")
            val match = live.firstOrNull { it.sha == rec.sha } ?: live.firstOrNull { it.filename == rec.filename }
            MyUploadRow(record = rec, votes = match?.votes ?: 0, downloads = match?.downloads ?: 0, stillOnline = match != null)
        }
}

private const val K_MATCHING = "matching"
private const val K_CFG_LOADING = "cfgloading"

private fun xmbStoreLabel(isSteam: Boolean) = if (isSteam) "STEAM" else "TITLE"
private fun xmbStoreIcon(isSteam: Boolean): ImageVector = if (isSteam) Icons.Filled.SportsEsports else Icons.Filled.Public

private fun xmbConfigKey(folder: String, e: WorkerConfigEntry) = "cfg:" + e.sha.ifBlank { "$folder/${e.filename}" }

/** "label: value" as one Info row when it fits (value on the right), else wrapped over several. */
private fun xmbLabelValue(key: String, label: String, value: String, icon: ImageVector, context: Context): List<XmbRow> =
    if (value.length <= 26 && label.length + value.length + 3 <= XmbTools.lineChars(context)) {
        listOf(XmbRow.Info(key, label, icon, value = value))
    } else {
        XmbTools.para(key, "$label: $value", icon, context)
    }

/** Bulleted lines ("• …"), each wrapped if long; only the first line of the block carries [icon]. */
private fun xmbBullets(key: String, items: List<String>, icon: ImageVector, context: Context): List<XmbRow> =
    items.flatMapIndexed { i, s -> XmbTools.para("$key$i", "• $s", if (i == 0) icon else XmbTools.blankIcon, context) }

/**
 * "Community configs" for one game — the sheet as a column. Your-config actions first (as in the
 * sheet), the search, the matched game and its uploaded configs (or the per-device fallback when the
 * worker can't be reached). The highlight rides "Matching…" → "Loading configs…" → the first config.
 */
internal fun xmbCommunityConfigsMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val ctx = xmb.context
    val s = shortcut
    // The shortcut's own folder, sanitized the way the exporter keys uploads, so the user's own
    // Bannerlator upload shows up before it reaches the canonical index.
    val myFolder = s.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    var result by mutableStateOf<CommunityMatchResult?>(null)
    var configs by mutableStateOf<List<Pair<String, WorkerConfigEntry>>?>(null)   // null = loading
    var configsFor by mutableStateOf("")
    var matchesMine by mutableStateOf(false)
    var search by mutableStateOf("")
    var searchResults by mutableStateOf<List<CanonicalGame>>(emptyList())
    var uploading by mutableStateOf(false)
    var uploadStarted by mutableStateOf(false)
    var exporting by mutableStateOf(false)
    val jobs = ArrayList<Job>()
    lateinit var menu: XmbMenu

    fun loadConfigs(game: CanonicalGame) {
        configs = null
        configsFor = game.identity
        jobs += xmb.scope.launch {
            val list = XmbCommunityData.configs(game, listOf(myFolder))
            if (configsFor != game.identity) return@launch
            configs = list
            if (menu.selKey == K_CFG_LOADING) {
                val first = list.firstOrNull()?.let { xmbConfigKey(it.first, it.second) }
                    ?: if (result?.rankedDevices.isNullOrEmpty()) null else "dev0"
                if (first != null) menu.selKey = first
            }
        }
    }

    fun show(r: CommunityMatchResult) {
        result = r
        matchesMine = false   // per game, as the sheet's filter chip
        val game = r.match
        if (game == null) {
            configs = emptyList()
            configsFor = ""
            menu.selKey = "search"
        } else {
            menu.selKey = if (r.alternatives.size > 1) "alt0" else K_CFG_LOADING
            loadConfigs(game)
        }
    }

    // A tie or search pick: remembered per shortcut (issue #167), then the same suggest view.
    fun pickGame(g: CanonicalGame) {
        xmb.scope.launch(Dispatchers.IO) {
            s.putExtra("communityGameIdentity", g.identity)
            s.saveData()
        }
        search = ""
        searchResults = emptyList()
        jobs += xmb.scope.launch { show(withContext(Dispatchers.IO) { XmbCommunityData.select(ctx, g) }) }
    }

    fun runSearch(q: String) {
        search = q
        if (q.trim().length >= 2) {
            jobs += xmb.scope.launch {
                val found = withContext(Dispatchers.IO) { XmbCommunityData.search(ctx, q) }
                if (search == q) searchResults = found
            }
        } else {
            searchResults = emptyList()
        }
    }

    fun exportConfig() {
        if (exporting) return
        exporting = true
        jobs += xmb.scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { ShortcutExporter.fromShortcut(s, ctx.applicationContext) }.getOrNull() }
            exporting = false
            if (res == null) xmb.toast("Couldn't share the config.") else xmb.push(xmbCommunityExportMenu(xmb, res))
        }
    }

    // ShortcutsViewModel.uploadShortcutConfig: build the export, confirm a replace, upload, record it.
    // Not tied to this column's lifetime, so backing out doesn't lose the result toast.
    fun startUpload() {
        if (uploading) return
        uploading = true
        uploadStarted = false
        val app = ctx.applicationContext
        val failed = "Upload failed — check your connection and try again."
        xmb.scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { ShortcutExporter.fromShortcut(s, app) }.getOrNull() }
            val meta = res?.let { r -> try { JSONObject(r.json).optJSONObject("meta") } catch (e: Exception) { null } }
            val token = meta?.optString("upload_token", "")?.trim().orEmpty()
            val soc = meta?.optString("soc", "")?.trim().orEmpty()
            val device = meta?.optString("device", "")?.trim().orEmpty()
            if (res == null || token.isEmpty()) {
                uploading = false
                xmb.toast(failed)
                return@launch
            }
            val existing = withContext(Dispatchers.IO) { UploadedConfigsStore.forGame(app, res.game) }
            if (existing != null) {
                val gate = CompletableDeferred<Boolean>()
                xmb.push(xmbReplaceUploadMenu(xmb, s.name, gate))
                if (!gate.await()) {
                    uploading = false
                    return@launch
                }
            }
            uploadStarted = true
            val ok = withContext(Dispatchers.IO) {
                val b64 = Base64.encodeToString(res.json.toByteArray(), Base64.NO_WRAP)
                // Attributed to the signed-in account when there is one; anonymous otherwise.
                val session = AccountManager.session(app)
                val uploaded = CommunityConfigWorker.upload(res.game, res.fileName, b64, token, session = session)
                    ?: return@withContext false
                if (existing != null) {
                    CommunityConfigWorker.deleteUpload(existing.sha, existing.game, existing.filename, existing.token)
                }
                UploadedConfigsStore.add(
                    app,
                    UploadedConfigsStore.UploadedConfig(
                        game = res.game,
                        filename = res.fileName,
                        sha = uploaded.sha,
                        token = token,
                        soc = soc,
                        device = device,
                        date = System.currentTimeMillis(),
                    ),
                )
                true
            }
            uploading = false
            uploadStarted = false
            xmb.toast(if (ok) "Shared \"${res.game}\" with the community." else failed)
        }
    }

    fun importBrowser(): XmbMenu {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val start = File(downloads, "bannerlator/game-configs").takeIf { it.isDirectory } ?: downloads
        return xmbFileBrowserMenu(
            xmb, "Select a config .json", start, XmbTools.storageRoots(ctx, null),
            accept = { it.isFile && it.name.lowercase().endsWith(".json") },
            onPick = { f ->
                xmb.pop()   // the browser's one remaining column
                xmb.push(xmbCommunityApplyMenu(xmb, under = null) { c -> XmbCommunityData.importFile(c, f, s) })
            },
        )
    }

    fun rowsNow(): List<XmbRow> {
        val rows = ArrayList<XmbRow>()
        // Local export / import + online sharing for THIS game — the sheet's buttons, same order.
        rows += XmbRow.Action(key = "share", label = "Share config", icon = Icons.Filled.Share, disabledReason = if (exporting) "Preparing…" else null) { exportConfig() }
        rows += XmbRow.Link(key = "import", label = "Import", icon = Icons.Filled.FileUpload) { importBrowser() }
        val upLabel = when {
            !uploading -> "Upload to community"
            uploadStarted -> "Uploading…"
            else -> "Preparing…"
        }
        rows += XmbRow.Action(key = "upload", label = upLabel, icon = Icons.Filled.CloudUpload, disabledReason = if (uploading) upLabel else null) { startUpload() }
        rows += XmbRow.Link(key = "myuploads", label = "My uploads", icon = Icons.Filled.AccountCircle) { xmbMyUploadsMenu(xmb) }
        rows += XmbRow.Text(key = "search", label = "Search all games", icon = Icons.Filled.Search, value = search, placeholder = "Game name") { runSearch(it) }

        if (search.trim().length >= 2) {
            rows += XmbRow.Action(key = "clearsearch", label = "Clear search", icon = Icons.Filled.Close) {
                runSearch("")
                menu.selKey = "search"
            }
            if (searchResults.isEmpty()) {
                rows += XmbRow.Info("noresults", "No games match \"$search\".", Icons.Filled.Info)
            } else {
                searchResults.forEach { cg ->
                    rows += XmbRow.Action(
                        key = "sr:${cg.identity}", label = cg.name, icon = xmbStoreIcon(cg.isSteam),
                        value = "${cg.configCount}", subtitle = xmbStoreLabel(cg.isSteam),
                    ) { pickGame(cg) }
                }
            }
            return rows
        }

        val r = result
        if (r == null) {
            rows += XmbRow.Info(K_MATCHING, "Matching \"${s.name}\"…", Icons.Filled.HourglassEmpty)
            return rows
        }
        val game = r.match
        if (game == null) {
            rows += XmbTools.para("nomatch", "No auto-match for \"${s.name}\" — search above to pick one.", Icons.Filled.Info, ctx)
            return rows
        }
        // A genuine tie: ask which game this is (remembered, so only once).
        val ties = r.alternatives
        if (ties.size > 1) {
            rows += XmbRow.Header("which", "Which game is this?")
            ties.forEachIndexed { i, alt ->
                rows += XmbRow.Action(key = "alt$i", label = alt.name, icon = xmbStoreIcon(alt.isSteam), value = xmbStoreLabel(alt.isSteam)) { pickGame(alt) }
            }
        }
        val devWord = if (game.devices.size == 1) "device" else "devices"
        val cfgWord = if (game.configCount == 1) "config" else "configs"
        rows += XmbRow.Info(
            "game", game.name, xmbStoreIcon(game.isSteam),
            value = xmbStoreLabel(game.isSteam), subtitle = "${game.configCount} $cfgWord · ${game.devices.size} $devWord",
        )
        rows += XmbRow.Info("you", "Your device: ${deviceHeaderLabel(DeviceIdentity.deviceModel(), r.userHardwareLabel)}", Icons.Filled.PhoneAndroid)
        // "Matches my device" filters by SoC/GPU; only offered when we detected one to compare against.
        val uSoc = r.userSoc
        val uGpu = r.userGpu
        val hwKnown = uSoc != null || uGpu != null
        rows += XmbRow.Toggle(
            key = "mine", label = "Matches my device", icon = Icons.Filled.Memory, value = matchesMine,
            disabledReason = if (hwKnown) null else "Couldn't detect your device's chip",
        ) { matchesMine = it }
        rows += XmbRow.Header("configs", "Configs")

        val list = configs
        if (list == null) {
            rows += XmbRow.Info(K_CFG_LOADING, "Loading configs…", Icons.Filled.HourglassEmpty)
        } else if (list.isNotEmpty()) {
            // One row per uploaded config (votes-desc); opens its details with Apply.
            val shownList = if (!matchesMine) list else list.filter {
                GameMatcher.hardwareMatchesUser(uSoc, uGpu, listOf(it.second.device, it.second.soc))
            }
            if (shownList.isEmpty()) {
                rows += XmbRow.Info("nomine", "No uploaded configs match your device.", Icons.Filled.Info)
            }
            shownList.forEach { (folder, e) ->
                val isMatch = hwKnown && GameMatcher.hardwareMatchesUser(uSoc, uGpu, listOf(e.device, e.soc))
                val ns = if (e.appSource == "bannerlator") "bannerlator" else ""
                val pick = CommunityPick.File(game, CommunityConfigRef(game, folder, e.filename, e.sha.ifBlank { null }, ns = ns), e)
                val sub = listOf(
                    e.soc, e.date,
                    if (e.appSource == "bannerlator") "Bannerlator" else "",
                    if (isMatch) "your device" else "",
                ).filter { it.isNotBlank() }.joinToString(" · ")
                rows += XmbRow.Link(
                    key = xmbConfigKey(folder, e),
                    label = e.device.ifBlank { e.soc.ifBlank { e.filename } },
                    icon = if (isMatch) Icons.Filled.CheckCircle else Icons.Filled.Tune,
                    value = "★ ${e.votes}  ↓ ${e.downloads}",
                    subtitle = sub.ifEmpty { null },
                ) { xmbCommunityDetailMenu(xmb, s, pick) }
            }
        } else if (r.rankedDevices.isEmpty()) {
            rows += XmbRow.Info("nodevices", "No device configs listed.", Icons.Filled.Info)
        } else {
            // Offline / bucket miss: per-device rows, the best file for that device resolved at apply time.
            rows += XmbTools.para("offline", "Showing device configs (vote counts unavailable offline).", Icons.Filled.CloudOff, ctx)
            val devs = if (!matchesMine) r.rankedDevices else r.rankedDevices.filter { GameMatcher.deviceMatchesUser(it, uSoc, uGpu) }
            devs.forEachIndexed { i, d ->
                val isMatch = hwKnown && GameMatcher.deviceMatchesUser(d, uSoc, uGpu)
                rows += XmbRow.Link(
                    key = "dev$i",
                    label = d.model.ifBlank { "Unknown device" },
                    icon = if (isMatch) Icons.Filled.CheckCircle else Icons.Filled.PhoneAndroid,
                    subtitle = listOf(d.gpu, d.soc).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null },
                ) { xmbCommunityDetailMenu(xmb, s, CommunityPick.Device(game, d)) }
            }
        }
        return rows
    }

    menu = XmbMenu(
        title = "Community configs",
        icon = Icons.Filled.Public,
        initialKey = K_MATCHING,
        onClose = { jobs.forEach { it.cancel() } },
    ) { rowsNow() }
    jobs += xmb.scope.launch { show(withContext(Dispatchers.IO) { XmbCommunityData.match(ctx, s) }) }
    return menu
}

/**
 * One config: the chooser ("Apply to game…") and the detail page merged. Apply is first; below it
 * the provenance, what the config sets, the pre-apply diff against this game, then votes/comments.
 */
private fun xmbCommunityDetailMenu(xmb: XmbScope, s: Shortcut, pick: CommunityPick): XmbMenu {
    val ctx = xmb.context
    val game = pick.game
    // Provenance fallback: the device row itself, or one synthesized from the uploaded file's fields.
    val provDevice = when (pick) {
        is CommunityPick.File -> CanonicalDevice(pick.entry.device, "", pick.entry.soc)
        is CommunityPick.Device -> pick.device
    }
    val fromLabel = when (pick) {
        is CommunityPick.File -> pick.entry.device.ifBlank { pick.entry.soc.ifBlank { "that device" } }
        is CommunityPick.Device -> pick.device.model.ifBlank { "that device" }
    }
    var detail by mutableStateOf<CommunityConfigDetail?>(null)
    var loading by mutableStateOf(true)
    var votes by mutableIntStateOf(0)
    var voted by mutableStateOf(false)
    var voting by mutableStateOf(false)
    var comments by mutableStateOf<List<WorkerComment>>(emptyList())
    var commenting by mutableStateOf(false)
    // Local per-sha vote dedup, the same prefs file the detail dialog uses.
    val votePrefs = ctx.getSharedPreferences("banner_config_votes", Context.MODE_PRIVATE)
    val me = XmbOpen()

    val job = xmb.scope.launch {
        val d = withContext(Dispatchers.IO) {
            runCatching {
                when (pick) {
                    is CommunityPick.File -> XmbCommunityData.detailFile(ctx, pick.ref, s)
                    is CommunityPick.Device -> XmbCommunityData.detailDevice(ctx, pick.game, pick.device, s)
                }
            }.getOrNull()
        }
        detail = d
        loading = false
        if (d != null) {
            votes = d.votes
            comments = d.comments
            voted = d.sha?.let { votePrefs.getBoolean(it, false) } ?: false
        }
    }

    fun applyThis() {
        xmb.push(
            xmbCommunityApplyMenu(xmb, under = me) { c ->
                when (pick) {
                    is CommunityPick.File -> XmbCommunityData.applyFile(c, s, pick.ref)
                    is CommunityPick.Device -> XmbCommunityData.applyDevice(c, s, pick.game, pick.device)
                }
            }
        )
    }

    fun vote(sha: String, d: CommunityConfigDetail) {
        val g = d.workerGame ?: return
        if (voted || voting) return
        voting = true
        xmb.scope.launch {
            val newVotes = withContext(Dispatchers.IO) { CommunityConfigWorker.vote(sha, g, d.fileName) }
            voting = false
            if (newVotes != null) {
                votes = newVotes
                voted = true
                votePrefs.edit().putBoolean(sha, true).apply()
            } else {
                xmb.toast("Couldn't record your vote.")
            }
        }
    }

    fun postComment(workerGame: String, d: CommunityConfigDetail, raw: String) {
        val text = raw.trim().take(500)   // the worker caps comments at 500 chars
        if (text.isEmpty() || commenting) return
        commenting = true
        val dev = Build.MANUFACTURER + "_" + Build.MODEL
        xmb.scope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                if (CommunityConfigWorker.postComment(workerGame, d.fileName, text, dev)) CommunityConfigWorker.comments(workerGame, d.fileName)
                else null
            }
            commenting = false
            if (refreshed != null) comments = refreshed else xmb.toast("Couldn't post your comment.")
        }
    }

    return XmbMenu(
        title = "Config details",
        icon = Icons.Filled.Tune,
        initialKey = "apply",
        onClose = { me.isOpen = false; job.cancel() },
    ) {
        val rows = ArrayList<XmbRow>()
        rows += XmbRow.Action(key = "apply", label = "Apply", icon = Icons.Filled.Download, subtitle = "Config from $fromLabel.") { applyThis() }

        // Provenance — prefers the config's own meta, falling back to the device row.
        val d = detail
        val meta = d?.meta
        rows += XmbRow.Info("game", game.name, xmbStoreIcon(game.isSteam), value = xmbStoreLabel(game.isSteam))
        val dev = meta?.device ?: provDevice.model.ifBlank { null }
        val soc = meta?.soc ?: provDevice.soc.ifBlank { null }
        val hw = listOfNotNull(dev, provDevice.gpu.ifBlank { null }, soc).distinct().joinToString(" · ")
        if (hw.isNotEmpty()) rows += XmbRow.Info("hw", hw, Icons.Filled.PhoneAndroid)
        meta?.uploadedDate?.let { rows += XmbRow.Info("date", "Uploaded $it", Icons.Filled.Schedule) }
        if (meta != null) {
            rows += XmbRow.Info("source", "From ${communitySourceLabel(meta.appSource)}" + (meta.bhVersion?.let { " $it" } ?: ""), Icons.Filled.Public)
            rows += XmbRow.Info("by", meta.uploaderName?.let { "by $it" } ?: "Anonymous user", Icons.Filled.Person)
        }

        when {
            loading -> rows += XmbRow.Info("loading", "Loading config…", Icons.Filled.HourglassEmpty)
            d == null -> rows += XmbTools.para("failed", "Couldn't fetch this config (offline, or no matching file in the repo).", Icons.Filled.CloudOff, ctx)
            else -> {
                rows += XmbRow.Header("sets", "What this config sets")
                val lines = configSummaryLines(d.config)
                if (lines.isEmpty()) {
                    rows += XmbRow.Info("nothing", "Nothing this app can set.", Icons.Filled.Info)
                } else {
                    lines.forEachIndexed { i, (label, value) -> rows += xmbLabelValue("set$i", label, value, Icons.Filled.Tune, ctx) }
                }
                d.config.advisories["wineVersion"]?.let { proton ->
                    rows += xmbLabelValue("proton", "Proton (container-only)", proton, Icons.Filled.Info, ctx)
                }

                // Pre-apply diff against this game (nothing is written until Apply).
                d.preview?.let { pre ->
                    rows += XmbRow.Info("diff", "Changes to \"${game.name}\"", Icons.Filled.SwapHoriz)
                    rows += XmbTools.para("premsg", pre.message, XmbTools.blankIcon, ctx)
                    if (pre.changed.isNotEmpty()) {
                        rows += XmbRow.Header("would", "Would change")
                        rows += xmbBullets("ch", pre.changed, Icons.Filled.Tune, ctx)
                    }
                    if (pre.missingComponents.isNotEmpty()) {
                        rows += XmbRow.Header("needs", "Needs a component")
                        rows += xmbBullets("mc", pre.missingComponents.map { it.label }, Icons.Filled.Download, ctx)
                    }
                    if (pre.missingDrivers.isNotEmpty()) {
                        rows += XmbRow.Header("drivers", "Needs a GPU driver")
                        rows += xmbBullets(
                            "md",
                            pre.missingDrivers.map { md -> "Turnip ${md.wanted}" + (md.current?.let { c -> " (you have $c)" } ?: "") },
                            Icons.Filled.Memory, ctx,
                        )
                    }
                    if (pre.advisories.isNotEmpty()) {
                        rows += XmbRow.Header("headsup", "Heads up")
                        rows += xmbBullets("adv", pre.advisories, Icons.Filled.Warning, ctx)
                    }
                }

                // Live social layer — only when a worker /list entry matched this file.
                val workerGame = d.workerGame
                if (workerGame != null) {
                    rows += XmbRow.Header("social", "Votes & comments")
                    rows += XmbRow.Info("votes", "★ $votes   ↓ ${d.downloads}", Icons.Filled.ThumbUp)
                    if (d.description.isNotBlank()) rows += XmbTools.para("desc", d.description, Icons.Filled.Description, ctx)
                    val sha = d.sha
                    if (sha != null) {
                        val voteLabel = when {
                            voting -> "Voting…"
                            voted -> "Voted ✓"
                            else -> "Upvote"
                        }
                        rows += XmbRow.Action(
                            key = "upvote", label = voteLabel, icon = Icons.Filled.ThumbUp,
                            disabledReason = if (voted || voting) voteLabel else null,
                        ) { vote(sha, d) }
                    }
                    rows += XmbRow.Header("comments", "Comments")
                    if (comments.isEmpty()) {
                        rows += XmbRow.Info("nocomments", "No comments yet.", Icons.Filled.Comment)
                    } else {
                        comments.forEachIndexed { i, c ->
                            val head = listOf(c.device, c.date).filter { it.isNotBlank() }.joinToString(" · ")
                            rows += XmbTools.para("c$i", c.text, Icons.Filled.Comment, ctx, subtitle = head.ifEmpty { null })
                        }
                    }
                    rows += XmbRow.Text(
                        key = "addcomment", label = if (commenting) "Sending…" else "Add a comment", icon = Icons.Filled.Edit,
                        value = "", placeholder = "Add a comment",
                        disabledReason = if (commenting) "Sending…" else null,
                    ) { postComment(workerGame, d, it) }
                }
            }
        }
        rows
    }
}

/**
 * Runs an apply (or an import) and shows what the sheet's result dialog showed: what changed, what
 * needs installing, the heads-ups. Done closes it and [under] (the detail column), landing back on
 * the config list the way the sheet did. Missing components/drivers are listed, not installable here.
 */
private fun xmbCommunityApplyMenu(
    xmb: XmbScope,
    under: XmbOpen?,
    work: (Context) -> CommunityConfigApply.ConfigApplyResult,
): XmbMenu {
    val ctx = xmb.context
    var res by mutableStateOf<CommunityConfigApply.ConfigApplyResult?>(null)
    var reloadPending = false   // the game changed: reload the games bar once we're out of here
    val me = XmbOpen()

    xmb.scope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching { work(ctx.applicationContext) }.getOrElse { e ->
                CommunityConfigApply.ConfigApplyResult(ok = false, message = e.message ?: "Couldn't apply")
            }
        }
        if (r.ok && r.changed.isNotEmpty()) reloadPending = true
        res = r
    }

    fun reloadIfNeeded() {
        if (reloadPending) {
            reloadPending = false
            xmb.reloadGames()
        }
    }

    return XmbMenu(
        title = "Apply config",
        icon = Icons.Filled.Download,
        initialKey = "busy",
        onClose = { me.isOpen = false; reloadIfNeeded() },
    ) {
        val r = res
        if (r == null) {
            listOf<XmbRow>(
                XmbRow.Header("title", "Applying config"),
                XmbRow.Info("busy", "Fetching and merging…", Icons.Filled.HourglassEmpty),
            )
        } else {
            buildList<XmbRow> {
                add(XmbRow.Header("title", if (r.ok) "Config applied" else "Couldn't apply"))
                add(
                    XmbRow.Action(key = "done", label = "Done", icon = Icons.Filled.Check) {
                        val reload = reloadPending
                        reloadPending = false
                        me.close(xmb)
                        under?.close(xmb)
                        if (reload) xmb.reloadGames()
                    }
                )
                addAll(XmbTools.para("msg", r.message, if (r.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, ctx))
                if (r.changed.isNotEmpty()) {
                    add(XmbRow.Header("changed", "Changed"))
                    addAll(xmbBullets("ch", r.changed, Icons.Filled.Tune, ctx))
                }
                if (r.missingComponents.isNotEmpty()) {
                    add(XmbRow.Header("needs", "Needs a component"))
                    addAll(xmbBullets("mc", r.missingComponents.map { it.label }, Icons.Filled.Download, ctx))
                }
                if (r.missingDrivers.isNotEmpty()) {
                    add(XmbRow.Header("drivers", "Needs a GPU driver"))
                    addAll(
                        xmbBullets(
                            "md",
                            r.missingDrivers.map { md -> "Turnip ${md.wanted}" + (md.current?.let { c -> " (you have $c)" } ?: "") },
                            Icons.Filled.Memory, ctx,
                        )
                    )
                }
                if (r.advisories.isNotEmpty()) {
                    add(XmbRow.Header("headsup", "Heads up"))
                    addAll(xmbBullets("adv", r.advisories, Icons.Filled.Warning, ctx))
                }
            }
        }
    }
}

/** The export hand-off: Save to Downloads or Share (Android's share sheet), same sinks as the sheet. */
private fun xmbCommunityExportMenu(xmb: XmbScope, res: ShortcutExporter.ExportResult): XmbMenu {
    val ctx = xmb.context
    val me = XmbOpen()

    fun saveToDownloads() {
        me.close(xmb)
        xmb.scope.launch {
            val path = withContext(Dispatchers.IO) {
                try {
                    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    // Exported configs live under Download/bannerlator/game-configs/ (created if absent).
                    val exportDir = File(downloads, "bannerlator/game-configs")
                    if (!exportDir.exists()) exportDir.mkdirs()
                    val out = File(exportDir, res.fileName)
                    out.writeText(res.json)
                    out.setReadable(true, false)
                    MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), null, null)
                    out.absolutePath
                } catch (e: Exception) {
                    null
                }
            }
            xmb.toast(if (path != null) "Saved to $path" else "Couldn't save the config.")
        }
    }

    fun share() {
        me.close(xmb)
        xmb.scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "community_configs/export").apply { mkdirs() }
                    File(dir, res.fileName).also { it.writeText(res.json) }
                }.getOrNull()
            }
            try {
                if (file == null) throw IllegalStateException("export not written")
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".tileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, res.game)
                    putExtra(Intent.EXTRA_TEXT, "Bannerlator config for ${res.game}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                XmbTools.start(ctx, Intent.createChooser(send, "Share config"))
            } catch (e: Exception) {
                xmb.toast("Couldn't share the config.")
            }
        }
    }

    return XmbMenu(title = "Share config", icon = Icons.Filled.Share, initialKey = "save", onClose = { me.isOpen = false }) {
        listOf<XmbRow>(
            XmbRow.Action(key = "save", label = "Save to Downloads", icon = Icons.Filled.Download) { saveToDownloads() },
            XmbRow.External(key = "share", label = "Share", icon = Icons.Filled.Share) { share() },
            XmbRow.Info("ready", "A config file for \"${res.game}\" is ready.", Icons.Filled.Description),
            XmbRow.Info("file", res.fileName, Icons.Filled.InsertDriveFile),
        )
    }
}

/**
 * The upload's replace-confirm. Custom rather than xmb.confirm because Cancel must release the
 * parked upload ([gate] false) — backing out does the same.
 */
private fun xmbReplaceUploadMenu(xmb: XmbScope, gameName: String, gate: CompletableDeferred<Boolean>): XmbMenu {
    val me = XmbOpen()
    return XmbMenu(
        title = "Upload",
        icon = Icons.Filled.CloudUpload,
        initialKey = "cancel",
        onClose = { me.isOpen = false; gate.complete(false) },
    ) {
        listOf<XmbRow>(
            XmbRow.Header("title", "Replace your shared config?"),
            XmbRow.Action(key = "cancel", label = "Cancel", icon = Icons.Filled.Close) { me.close(xmb) },
            XmbRow.Action(key = "replace", label = "Replace", icon = Icons.Filled.CloudUpload) {
                gate.complete(true)
                me.close(xmb)
            },
        ) + XmbTools.para("msg", "You already shared a config for \"$gameName\". Replace it?", Icons.Filled.Info, xmb.context)
    }
}

/** "My uploads": the configs this user shared (reinstall-proof list), each with description + delete. */
private fun xmbMyUploadsMenu(xmb: XmbScope): XmbMenu {
    val ctx = xmb.context
    var uploads by mutableStateOf<List<MyUploadRow>?>(null)   // null = loading
    val job = xmb.scope.launch {
        uploads = withContext(Dispatchers.IO) { runCatching { XmbCommunityData.myUploads(ctx.applicationContext) }.getOrDefault(emptyList()) }
    }
    return XmbMenu(title = "My uploads", icon = Icons.Filled.AccountCircle, initialKey = "loading", onClose = { job.cancel() }) {
        val list = uploads
        when {
            list == null -> listOf(XmbRow.Info("loading", "Loading…", Icons.Filled.HourglassEmpty))
            list.isEmpty() -> listOf(XmbRow.Info("none", "You haven't shared any configs yet.", Icons.Filled.Info))
            else -> buildList<XmbRow> {
                add(
                    XmbRow.Info(
                        "summary",
                        "Shared ${list.size} config${if (list.size == 1) "" else "s"} · ↓ ${list.sumOf { it.downloads }} · ★ ${list.sumOf { it.votes }}",
                        Icons.Filled.CloudUpload,
                    )
                )
                list.forEach { row ->
                    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(row.record.date))
                    val sub = listOf(row.record.device, row.record.soc, dateStr).filter { it.isNotBlank() }.joinToString(" · ") +
                        if (!row.stillOnline) " · offline" else ""
                    add(
                        XmbRow.Link(
                            key = "u:${row.record.sha}", label = row.record.game, icon = Icons.Filled.Tune,
                            value = "★${row.votes}  ↓${row.downloads}", subtitle = sub,
                        ) {
                            xmbMyUploadMenu(xmb, row) { sha -> uploads = uploads?.filterNot { it.record.sha == sha } }
                        }
                    )
                }
            }
        }
    }
}

/** One of the user's uploads: online state, the editable description, and Delete (with confirm). */
private fun xmbMyUploadMenu(xmb: XmbScope, row: MyUploadRow, onDeleted: (String) -> Unit): XmbMenu {
    val ctx = xmb.context
    var desc by mutableStateOf("")
    var descLoading by mutableStateOf(true)
    var deleting by mutableStateOf(false)
    val me = XmbOpen()
    val job = xmb.scope.launch {
        desc = withContext(Dispatchers.IO) { CommunityConfigWorker.desc(row.record.sha) }
        descLoading = false
    }

    fun saveDescription(text: String) {
        val clamped = text.take(500)   // the worker's /describe limit
        desc = clamped
        xmb.scope.launch {
            val ok = withContext(Dispatchers.IO) { CommunityConfigWorker.describe(row.record.sha, row.record.token, clamped) }
            xmb.toast(if (ok) "Description updated." else "Couldn't reach the server.")
        }
    }

    fun delete() {
        xmb.confirm(
            XmbConfirm(
                title = "Delete shared config?",
                message = "Delete your shared config for \"${row.record.game}\"?",
                okLabel = "Delete",
                danger = true,
            )
        ) {
            deleting = true
            xmb.scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    // Already gone server-side → just prune the local record.
                    val serverOk = if (!row.stillOnline) true else CommunityConfigWorker.deleteUpload(
                        row.record.sha, row.record.game, row.record.filename, row.record.token,
                    )
                    if (serverOk) UploadedConfigsStore.remove(ctx.applicationContext, row.record.sha)
                    serverOk
                }
                deleting = false
                if (ok) {
                    onDeleted(row.record.sha)
                    xmb.toast("Deleted your shared config.")
                    me.close(xmb)
                } else {
                    xmb.toast("Couldn't reach the server.")
                }
            }
        }
    }

    return XmbMenu(title = row.record.game, icon = Icons.Filled.Tune, initialKey = "desc", onClose = { me.isOpen = false; job.cancel() }) {
        val busy = if (deleting) "Deleting…" else null
        listOf<XmbRow>(
            XmbRow.Info("status", if (row.stillOnline) "● Online" else "● Removed", if (row.stillOnline) Icons.Filled.CheckCircle else Icons.Filled.CloudOff),
            XmbRow.Info("stats", "★ ${row.votes}   ↓ ${row.downloads}", Icons.Filled.ThumbUp),
            XmbRow.Text(
                key = "desc", label = "Description", icon = Icons.Filled.Edit, value = desc,
                subtitle = "${desc.length}/500",
                disabledReason = busy ?: if (descLoading) "Loading description…" else null,
            ) { saveDescription(it) },
            XmbRow.Action(key = "delete", label = "Delete", icon = Icons.Filled.Delete, danger = true, disabledReason = busy) { delete() },
        )
    }
}
