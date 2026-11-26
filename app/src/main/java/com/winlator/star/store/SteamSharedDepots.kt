package com.winlator.star.store

import android.content.Context
import com.winlator.star.components.SteamRedists
import com.winlator.star.core.FileUtils
import org.json.JSONObject
import java.io.File

/**
 * Depots a game installs from another app's manifest: they have no manifest of their own and
 * `depotfromapp` names the app that has it (Counter-Strike takes Half-Life's depots 1-3 from app 70).
 * The library sync ([SteamRepository.processAppKv]) keeps the ones that pass its depot filter; the Rust
 * installer ([BlDepotInstaller]) resolves their manifests in the source app before each download.
 * Separate shared installs (`sharedinstall` 1, such as the app 228980 redistributables) are left out.
 */
object SteamSharedDepots {

    private const val PREFS = "steam_borrowed_depots"

    /** A remembered depot: its [sourceAppId], and [codeAppId] when its manifest code is asked under another app (0 = the source). */
    data class Entry(val depotId: Int, val sourceAppId: Int, val codeAppId: Int)

    /** A depot resolved to [manifestId] in [sourceAppId]'s app info on [branch]; [codeAppId] asks for its manifest code. */
    data class Resolved(
        val depotId: Int,
        val manifestId: Long,
        val sourceAppId: Int,
        val codeAppId: Int,
        val branch: String,
        val size: Long,
        val download: Long,
    )

    /** The app a manifest-less depot is installed from, or 0 when it is not taken from another app. */
    @JvmStatic
    fun sourceApp(depotFromApp: String?, sharedInstall: String?): Int {
        val from = depotFromApp?.trim().orEmpty()
        if (from == SteamRedists.SHARED_APP || sharedInstall?.trim() == "1") return 0
        return from.toIntOrNull()?.takeIf { it > 0 } ?: 0
    }

    /**
     * Keeps [appId]'s depots taken from other apps (depot id to source app id; library sync, worker thread).
     * A free-to-download game asks for their manifest codes under its own id.
     */
    @JvmStatic
    fun remember(context: Context?, appId: Int, depots: Map<Int, Int>, freeToDownload: Boolean) {
        if (context == null || appId <= 0) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = appId.toString()
            if (depots.isEmpty()) {
                if (prefs.contains(key)) prefs.edit().remove(key).apply()
                return
            }
            val value = depots.entries.joinToString(",") { (depot, source) ->
                if (freeToDownload) "$depot:$source:$appId" else "$depot:$source"
            }
            if (prefs.getString(key, null) != value) prefs.edit().putString(key, value).apply()
        }
    }

    fun remembered(context: Context?, appId: Int): List<Entry> = runCatching {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(appId.toString(), null)
            ?.split(',')?.mapNotNull { entry ->
                val p = entry.split(':').map { it.trim() }
                val depot = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val source = p.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                Entry(depot, source, p.getOrNull(2)?.toIntOrNull() ?: 0)
            }
    }.getOrNull().orEmpty()

    /**
     * [appId]'s remembered depots (minus [skip]) resolved on [branch]: the source app's manifest for that
     * branch, else its public one, following further `depotfromapp` links. [appInfo] returns an app's
     * appinfo JSON or null. A depot that does not resolve is left out and reported through [log].
     */
    fun resolve(
        context: Context?, appId: Int, branch: String, skip: Set<Int>,
        appInfo: (Int) -> JSONObject?, log: (String) -> Unit,
    ): List<Resolved> {
        val entries = remembered(context, appId).filter { it.depotId !in skip }
        if (entries.isEmpty()) return emptyList()
        val infos = HashMap<Int, JSONObject?>()
        val lookup = { id: Int -> if (infos.containsKey(id)) infos[id] else appInfo(id).also { infos[id] = it } }
        val resolved = entries.mapNotNull { e ->
            val r = resolveDepot(e.depotId, e.sourceAppId, branch, lookup)
            if (r == null) log("Shared depot ${e.depotId} from app ${e.sourceAppId}: no manifest found, left out")
            if (r != null && e.codeAppId > 0) r.copy(codeAppId = e.codeAppId) else r
        }
        rememberSources(context, appId, entries, resolved, lookup)
        return resolved
    }

    /** Build, name and borrowed-depot sizes of a source app, as installed (prefs key `src_<appId>`). */
    data class Source(val appId: Int, val buildId: Long, val name: String, val depotSizes: Map<Int, Long>)

    private fun rememberSources(
        context: Context?, appId: Int, entries: List<Entry>, resolved: List<Resolved>, appInfo: (Int) -> JSONObject?,
    ) {
        if (context == null || resolved.isEmpty()) return
        runCatching {
            val json = JSONObject()
            for (source in entries.map { it.sourceAppId }.distinct()) {
                val depots = entries.filter { it.sourceAppId == source }.map { it.depotId }.toSet()
                val mine = resolved.filter { it.depotId in depots }
                if (mine.isEmpty()) continue
                val info = appInfo(source)
                val sizes = JSONObject()
                for (r in mine) sizes.put(r.depotId.toString(), r.size)
                json.put(source.toString(), JSONObject()
                    .put("build", branchBuildId(info, mine.first().branch))
                    .put("name", info?.optJSONObject("common")?.optString("name").orEmpty())
                    .put("sizes", sizes))
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("src_$appId", json.toString()).apply()
        }
    }

    /** The build id of [branch] (else public) in an app's appinfo JSON, 0 when absent. */
    fun branchBuildId(info: JSONObject?, branch: String): Long {
        val branches = info?.optJSONObject("depots")?.optJSONObject("branches") ?: return 0L
        val b = branches.optJSONObject(branch) ?: branches.optJSONObject("public") ?: return 0L
        return b.optString("buildid").trim().toLongOrNull() ?: 0L
    }

    /** The source apps saved for [appId] at its last install or update. */
    fun sources(context: Context?, appId: Int): Map<Int, Source> = runCatching {
        val raw = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("src_$appId", null)
            ?: return@runCatching emptyMap<Int, Source>()
        parseSources(raw)
    }.getOrNull().orEmpty()

    internal fun parseSources(raw: String): Map<Int, Source> {
        val json = JSONObject(raw)
        val out = LinkedHashMap<Int, Source>()
        for (k in json.keys()) {
            val id = k.toIntOrNull() ?: continue
            val o = json.optJSONObject(k) ?: continue
            val sizes = HashMap<Int, Long>()
            o.optJSONObject("sizes")?.let { s -> for (d in s.keys()) d.toIntOrNull()?.let { sizes[it] = s.optLong(d, 0L) } }
            out[id] = Source(id, o.optLong("build", 0L), o.optString("name", ""), sizes)
        }
        return out
    }

    /** What [stageSourceApps] does with a source app's record. */
    enum class SourceAction {
        /** No record, or one this app wrote (for this game's folder or another borrowing game's): write it. */
        WRITE,
        /** Steam's own unfinished install of the source app (its folder is empty or missing): replace it. */
        TAKE_OVER,
        /** A real install of the source app in its own folder, or a record that cannot be read: leave it. */
        LEAVE,
    }

    internal fun sourceAction(
        exists: Boolean, info: SteamAppManifest.AcfInfo?, gameFolder: String, lastWritten: String?,
        folderHasFiles: (String) -> Boolean,
    ): SourceAction = when {
        !exists -> SourceAction.WRITE
        info == null -> SourceAction.LEAVE
        info.installDir.equals(gameFolder, ignoreCase = true) -> SourceAction.WRITE
        !lastWritten.isNullOrEmpty() && info.installDir.equals(lastWritten, ignoreCase = true) -> SourceAction.WRITE
        folderHasFiles(info.installDir) -> SourceAction.LEAVE
        else -> SourceAction.TAKE_OVER
    }

    /**
     * Records the apps [appId] borrows depots from as installed in the game's own folder [gameFolder]
     * (under [steamapps]`/common`), in the real Steam client's library. Steam lists such depots as the
     * game's SharedDepots and needs the source app installed too (Half-Life for Counter-Strike): without a
     * record it reports "Update Required", starts downloading the source app and LaunchApp refuses, so there
     * is no secure launch. Only depots the game's download journal in [installDir] holds are listed. A source
     * app installed in a folder of its own is left alone; when the record was Steam's own unfinished install,
     * its partial download is removed. Returns one log line per source app.
     */
    @JvmStatic
    fun stageSourceApps(
        context: Context?, appId: Int, installDir: String?, steamapps: File, gameFolder: String,
        ownerSteamId: Long, language: String,
    ): List<String> {
        val dir = installDir?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: return emptyList()
        val entries = remembered(context, appId)
        if (entries.isEmpty()) return emptyList()
        val journal = runCatching { BlDepotInstaller.journalInstalledManifests(dir) }.getOrDefault(emptyMap())
        val installed = entries.filter { it.sourceAppId != appId && journal.containsKey(it.depotId) }
        if (installed.isEmpty()) return emptyList()
        val saved = sources(context, appId)
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = ArrayList<String>()
        for ((source, list) in installed.groupBy { it.sourceAppId }) {
            val acf = SteamAppManifest.manifestFile(steamapps, source)
            val existing = if (acf.isFile) SteamAppManifest.readInfo(acf) else null
            val lastWritten = runCatching { prefs?.getString("written_$source", null) }.getOrNull()
            val action = sourceAction(acf.isFile, existing, gameFolder, lastWritten) { name ->
                File(File(steamapps, "common"), name).list()?.isNotEmpty() == true
            }
            if (action == SourceAction.LEAVE) {
                out.add("app $source: record left as is (${existing?.installDir ?: "unreadable"})")
                continue
            }
            val s = saved[source]
            val build = s?.buildId?.takeIf { it > 0L } ?: dbBuildId(source)
            val name = s?.name?.takeIf { it.isNotBlank() } ?: existing?.name?.takeIf { it.isNotBlank() } ?: "App $source"
            val depots = list.map { Triple(it.depotId, journal.getValue(it.depotId), s?.depotSizes?.get(it.depotId) ?: 0L) }
            val text = sourceManifestText(source, name, gameFolder, build, ownerSteamId, language, depots,
                System.currentTimeMillis() / 1000L)
            if (!writeAtomic(acf, text)) {
                out.add("app $source: record could not be written")
                continue
            }
            runCatching { prefs?.edit()?.putString("written_$source", gameFolder)?.apply() }
            var note = ""
            if (action == SourceAction.TAKE_OVER) {
                val downloading = File(steamapps, "downloading")
                FileUtils.delete(File(downloading, source.toString()))
                downloading.listFiles { f -> f.isFile && f.name.startsWith("state_${source}_") && f.name.endsWith(".patch") }
                    ?.forEach { it.delete() }
                existing?.installDir?.let { old ->
                    val f = File(File(steamapps, "common"), old)
                    if (!FileUtils.isSymlink(f) && f.isDirectory && f.list()?.isEmpty() == true) f.delete()
                }
                note = ", replaced Steam's unfinished install in \"${existing?.installDir}\""
            }
            out.add("app $source: recorded in \"$gameFolder\" (build $build, depots ${list.joinToString(",") { it.depotId.toString() }}$note)")
        }
        return out
    }

    private fun dbBuildId(appId: Int): Long = runCatching {
        val rows = SteamRepository.getInstance().database.getBranches(appId)
        rows.firstOrNull { it.branchName == "public" }?.buildId ?: 0L
    }.getOrDefault(0L)

    /** An `appmanifest_<appid>.acf` for a source app installed in [installDir]; [depots] = depot, manifest, size. */
    internal fun sourceManifestText(
        appId: Int, name: String, installDir: String, buildId: Long, ownerSteamId: Long, language: String,
        depots: List<Triple<Int, Long, Long>>, now: Long,
    ): String {
        val sb = StringBuilder()
        sb.append("\"AppState\"\n{\n")
        fun kv(k: String, v: String) { sb.append("\t\"").append(k).append("\"\t\t\"").append(vdfEscape(v)).append("\"\n") }
        kv("appid", appId.toString())
        kv("universe", "1")
        kv("LauncherPath", "C:\\Program Files (x86)\\Steam\\steam.exe")
        kv("name", name)
        kv("StateFlags", "4")
        kv("installdir", installDir)
        kv("LastUpdated", now.toString())
        kv("LastPlayed", "0")
        kv("SizeOnDisk", depots.sumOf { it.third }.toString())
        kv("StagingSize", "0")
        kv("buildid", buildId.toString())
        kv("LastOwner", if (ownerSteamId > 0L) ownerSteamId.toString() else "0")
        kv("DownloadType", "1")
        kv("UpdateResult", "0")
        kv("BytesToDownload", "0")
        kv("BytesDownloaded", "0")
        kv("BytesToStage", "0")
        kv("BytesStaged", "0")
        kv("TargetBuildID", buildId.toString())
        kv("AutoUpdateBehavior", "0")
        kv("AllowOtherDownloadsWhileRunning", "0")
        kv("ScheduledAutoUpdate", "0")
        sb.append("\t\"InstalledDepots\"\n\t{\n")
        for ((depot, manifest, size) in depots) {
            sb.append("\t\t\"").append(depot).append("\"\n\t\t{\n")
            sb.append("\t\t\t\"manifest\"\t\t\"").append(manifest.toULong().toString()).append("\"\n")
            sb.append("\t\t\t\"size\"\t\t\"").append(size).append("\"\n")
            sb.append("\t\t}\n")
        }
        sb.append("\t}\n")
        sb.append("\t\"UserConfig\"\n\t{\n\t\t\"language\"\t\t\"").append(vdfEscape(language)).append("\"\n\t}\n")
        sb.append("\t\"MountedConfig\"\n\t{\n\t\t\"language\"\t\t\"").append(vdfEscape(language)).append("\"\n\t}\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun vdfEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun writeAtomic(target: File, text: String): Boolean = runCatching {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { tmp.delete(); return@runCatching false }
        true
    }.getOrDefault(false)

    fun resolveDepot(depotId: Int, sourceAppId: Int, branch: String, appInfo: (Int) -> JSONObject?): Resolved? {
        var app = sourceAppId
        val visited = HashSet<Int>()
        while (app > 0 && visited.add(app)) {
            val depot = appInfo(app)?.optJSONObject("depots")?.optJSONObject(depotId.toString()) ?: return null
            val manifests = depot.optJSONObject("manifests")
            val manifestBranch = if (manifests?.has(branch) == true) branch else "public"
            val raw = manifests?.opt(manifestBranch)
            if (raw != null) {
                val gid = if (raw is JSONObject) raw.optString("gid").ifEmpty { raw.optString("value") } else raw.toString()
                val manifest = gid.trim().toULongOrNull()?.toLong() ?: return null
                val size = (raw as? JSONObject)?.optString("size")?.toLongOrNull() ?: 0L
                val download = (raw as? JSONObject)?.optString("download")?.toLongOrNull() ?: 0L
                return Resolved(depotId, manifest, app, app, manifestBranch, size.coerceAtLeast(0L), download.coerceAtLeast(0L))
            }
            app = depot.optString("depotfromapp").trim().toIntOrNull() ?: 0
        }
        return null
    }

    /** [csv] (`depot:manifest:size,…`) plus the remembered depots the journal in [installDir] holds. */
    @JvmStatic
    fun withInstalled(context: Context?, appId: Int, installDir: String?, csv: String?): String {
        val saved = csv.orEmpty()
        val dir = installDir?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: return saved
        val entries = remembered(context, appId)
        if (entries.isEmpty()) return saved
        val journal = runCatching { BlDepotInstaller.journalInstalledManifests(dir) }.getOrDefault(emptyMap())
        val listed = saved.split(',').mapNotNull { it.substringBefore(':').trim().toIntOrNull() }.toSet()
        val extra = entries.filter { it.depotId !in listed }
            .mapNotNull { e -> journal[e.depotId]?.let { "${e.depotId}:${it.toULong()}:0" } }
        return (listOf(saved).filter { it.isNotEmpty() } + extra).joinToString(",")
    }
}
