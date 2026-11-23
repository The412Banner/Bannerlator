package com.winlator.star.store

import android.content.Context
import com.winlator.star.components.SteamRedists
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
        return entries.mapNotNull { e ->
            val r = resolveDepot(e.depotId, e.sourceAppId, branch, lookup)
            if (r == null) log("Shared depot ${e.depotId} from app ${e.sourceAppId}: no manifest found, left out")
            if (r != null && e.codeAppId > 0) r.copy(codeAppId = e.codeAppId) else r
        }
    }

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
