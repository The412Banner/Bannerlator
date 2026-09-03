package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.blsteam.BlSteamSession
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.discovery.IServerListProvider
import `in`.dragonbra.javasteam.steam.discovery.ServerRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Steam connection region" — one user setting (Settings → Steam) consumed by all four Steam
 * network paths, engine-aware (Phase 2-C of docs/STEAM_RUST_ENGINE_PLAN.md; ships to everyone):
 *
 *  1. **Rust engine CM pick** — [pickEngineCmUrl]: the chosen datacenter's WebSocket CM (the
 *     remembered fastest host first), falling back to the engine's own directory pick.
 *  2. **JavaSteam CM pick (flag OFF)** — [javaSteamServerListProvider]: an `IServerListProvider`
 *     handing JavaSteam the chosen datacenter's TCP CMs. Best-effort: JavaSteam is TCP-only and
 *     the directory only lists TCP endpoints (bare IPs) for datacenters near the caller, so a
 *     far-away choice may have none — then the provider returns nothing and JavaSteam does its
 *     usual directory pick. Auto mode leaves JavaSteam's own (cell-id based) pick alone. Read once
 *     per process at `SteamRepository.initialize()`.
 *  3. **Download CDN preference** — [cdnPreference] → `BlSteamSession.setCdnPreference`: the
 *     engine asks `GetServersForSteamPipe` with the account's cell id and moves
 *     `cache<N>-<dc>.steamcontent.com` hosts to the front of the pool. JavaSteam's CDN pool is
 *     internal to its DepotDownloader (not exposed) — unchanged.
 *  4. **In-container genuine client** — [writeCmListJson] (GameHub-format `cmlist.json`, staged by
 *     `RealSteamLauncher.prepare`) + `WN_STEAM_CMLIST` / `BL_STEAM_REGION` env for the agent, which
 *     seeds the client's `config.vdf` CM cache before `LogOn`.
 *
 * **Auto** = probe one host per datacenter once (parallel TCP connect to port 443, bounded),
 * remember the winner + timestamp in `steam_prefs`, re-probe after 24 h or when a connect using it
 * failed ([invalidateAuto]). Datacenters come from the CM directory
 * (`ISteamDirectory/GetCMListForConnect`, grouped by `dc`) merged with a catalog of Steam's known
 * codes so the picker is not limited to the caller's neighbourhood; a catalog-only code uses the
 * regular `cmp<N>-<dc>.steamserver.net` host pattern and is validated by the probe.
 *
 * Network only on worker threads; every call here is bounded and never throws.
 */
object SteamRegion {

    private const val TAG = "BL_STEAM_REGION"

    const val AUTO = "auto"

    private const val PREFS = "steam_prefs"
    private const val K_MODE = "steam_region"                 // "auto" | datacenter code
    private const val K_AUTO_DC = "steam_region_auto_dc"
    private const val K_AUTO_HOST = "steam_region_auto_host"
    private const val K_AUTO_MS = "steam_region_auto_ms"
    private const val K_AUTO_AT = "steam_region_auto_at"
    private const val K_BAD_HOST = "steam_region_bad_host"
    private const val K_BAD_AT = "steam_region_bad_at"

    private const val AUTO_TTL_MS = 24L * 60 * 60 * 1000
    private const val BAD_HOST_TTL_MS = 5L * 60 * 1000
    private const val DIRECTORY_URL = "https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/"
    private const val HTTP_TIMEOUT_MS = 8_000
    private const val PROBE_TIMEOUT_MS = 2_500
    private const val PROBE_TOTAL_MS = 4_000L

    data class Datacenter(val code: String, val name: String)

    /** Steam datacenter codes (as reported by the CM directory's `dc`) with friendly names. */
    val CATALOG: List<Datacenter> = listOf(
        Datacenter("iad1", "US East — Virginia"),
        Datacenter("ord1", "US Central — Chicago"),
        Datacenter("atl3", "US East — Atlanta"),
        Datacenter("dfw2", "US Central — Dallas"),
        Datacenter("lax1", "US West — Los Angeles"),
        Datacenter("sea1", "US West — Seattle"),
        Datacenter("lhr1", "UK — London"),
        Datacenter("fra1", "Germany — Frankfurt"),
        Datacenter("fra2", "Germany — Frankfurt 2"),
        Datacenter("par1", "France — Paris"),
        Datacenter("ams1", "Netherlands — Amsterdam"),
        Datacenter("mad1", "Spain — Madrid"),
        Datacenter("sto2", "Sweden — Stockholm"),
        Datacenter("vie1", "Austria — Vienna"),
        Datacenter("waw1", "Poland — Warsaw"),
        Datacenter("gru1", "Brazil — São Paulo"),
        Datacenter("lim1", "Peru — Lima"),
        Datacenter("scl1", "Chile — Santiago"),
        Datacenter("syd1", "Australia — Sydney"),
        Datacenter("sgp1", "Singapore"),
        Datacenter("hkg1", "Hong Kong"),
        Datacenter("tyo1", "Japan — Tokyo"),
        Datacenter("seo1", "South Korea — Seoul"),
        Datacenter("bom1", "India — Mumbai"),
        Datacenter("maa1", "India — Chennai"),
        Datacenter("jnb1", "South Africa — Johannesburg"),
        Datacenter("dxb1", "UAE — Dubai"),
    )

    fun nameOf(code: String): String = CATALOG.firstOrNull { it.code == code }?.name ?: code.uppercase()

    /**
     * ISO-3166 country for each datacenter in [CATALOG] — the `cc=` the Steam *store* endpoints
     * want. The store prices in the account's country, and the connection region is the only
     * country-ish signal the app already has (nothing in `steam_prefs` records the account's real
     * store country), so this maps the resolved datacenter to its country and lets the device
     * locale fill in for Auto-with-no-winner. Purely a pricing hint: a wrong `cc` costs a wrong
     * currency on the store rails, never a broken screen.
     */
    private val DC_COUNTRY: Map<String, String> = mapOf(
        "iad1" to "US", "ord1" to "US", "atl3" to "US", "dfw2" to "US", "lax1" to "US", "sea1" to "US",
        "lhr1" to "GB", "fra1" to "DE", "fra2" to "DE", "par1" to "FR", "ams1" to "NL", "mad1" to "ES",
        "sto2" to "SE", "vie1" to "AT", "waw1" to "PL", "gru1" to "BR", "lim1" to "PE", "scl1" to "CL",
        "syd1" to "AU", "sgp1" to "SG", "hkg1" to "HK", "tyo1" to "JP", "seo1" to "KR", "bom1" to "IN",
        "maa1" to "IN", "jnb1" to "ZA", "dxb1" to "AE",
    )

    /**
     * Two-letter store country for the `cc=` parameter of `store.steampowered.com/api/` calls.
     * Resolution order: the explicit/remembered datacenter's country → the device locale's country
     * → "US". Never probes (so it is safe on the main thread) and never throws.
     */
    fun storeCountryCode(ctx: Context): String {
        val fromDc = try { resolveDc(ctx, allowProbe = false)?.let { DC_COUNTRY[it] } } catch (_: Throwable) { null }
        if (!fromDc.isNullOrBlank()) return fromDc
        val locale = try { java.util.Locale.getDefault().country } catch (_: Throwable) { "" }
        return locale.takeIf { it.length == 2 }?.uppercase() ?: "US"
    }

    /** One CM directory entry. `type` = "websockets" (host:443/27018/27019) or "netfilter" (TCP). */
    data class CmServer(val endpoint: String, val host: String, val port: Int, val dc: String, val type: String,
                        val load: Int, val wtdLoad: Double)

    data class Probe(val dc: String, val host: String, val ms: Long)   // ms < 0 = unreachable

    // ── prefs ─────────────────────────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [AUTO] or a datacenter code. */
    fun mode(ctx: Context): String = try { prefs(ctx).getString(K_MODE, AUTO)?.ifBlank { AUTO } ?: AUTO } catch (_: Throwable) { AUTO }

    fun setMode(ctx: Context, mode: String) {
        try { prefs(ctx).edit().putString(K_MODE, mode.trim().lowercase().ifBlank { AUTO }).apply() } catch (_: Throwable) {}
        Log.i(TAG, "region mode set to $mode")
    }

    /** Auto winner (dc, host, ms) if remembered within the last 24 h. */
    fun rememberedAuto(ctx: Context): Probe? {
        return try {
            val p = prefs(ctx)
            val dc = p.getString(K_AUTO_DC, "") ?: ""
            val host = p.getString(K_AUTO_HOST, "") ?: ""
            val at = p.getLong(K_AUTO_AT, 0L)
            if (dc.isEmpty() || host.isEmpty() || System.currentTimeMillis() - at > AUTO_TTL_MS) null
            else Probe(dc, host, p.getLong(K_AUTO_MS, -1L))
        } catch (_: Throwable) { null }
    }

    fun rememberAuto(ctx: Context, p: Probe) {
        try {
            prefs(ctx).edit().putString(K_AUTO_DC, p.dc).putString(K_AUTO_HOST, p.host)
                .putLong(K_AUTO_MS, p.ms).putLong(K_AUTO_AT, System.currentTimeMillis()).apply()
        } catch (_: Throwable) {}
    }

    /** Forget the auto winner (a connect through it failed) and remember its host as bad for 5 min. */
    fun invalidateAuto(ctx: Context, badHost: String? = null) {
        try {
            val e = prefs(ctx).edit().remove(K_AUTO_AT)
            if (!badHost.isNullOrEmpty()) e.putString(K_BAD_HOST, badHost).putLong(K_BAD_AT, System.currentTimeMillis())
            e.apply()
            Log.i(TAG, "auto region invalidated" + (if (badHost != null) " (bad host $badHost)" else ""))
        } catch (_: Throwable) {}
    }

    private fun badHost(ctx: Context): String? = try {
        val p = prefs(ctx)
        val h = p.getString(K_BAD_HOST, "") ?: ""
        if (h.isNotEmpty() && System.currentTimeMillis() - p.getLong(K_BAD_AT, 0L) < BAD_HOST_TTL_MS) h else null
    } catch (_: Throwable) { null }

    /** Short description for logs / the agent env: "auto", "auto:<dc>" (remembered) or "<dc>". */
    fun describe(ctx: Context): String {
        val m = mode(ctx)
        if (m != AUTO) return m
        val r = rememberedAuto(ctx)
        return if (r != null) "auto:${r.dc}" else AUTO
    }

    // ── directory ─────────────────────────────────────────────────────────────────────────────

    @Volatile private var directoryCache: Pair<Long, List<CmServer>>? = null

    /**
     * `GetCMListForConnect` (all transport types, nearest ~N to the caller by Steam's geolocation).
     * Cached 10 min. Empty on failure. Worker thread only.
     */
    fun fetchDirectory(cellId: Int = 0, force: Boolean = false): List<CmServer> {
        directoryCache?.let { (at, list) ->
            if (!force && cellId == 0 && System.currentTimeMillis() - at < 10 * 60 * 1000L && list.isNotEmpty()) return list
        }
        val out = ArrayList<CmServer>()
        try {
            val url = URL("$DIRECTORY_URL?cellid=$cellId&maxcount=80")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS; readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("User-Agent", "Valve/Steam HTTP Client 1.0")
            }
            try {
                if (conn.responseCode != 200) { Log.w(TAG, "directory HTTP ${conn.responseCode}"); return out }
                val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                val resp = JSONObject(body).optJSONObject("response") ?: return out
                val list = resp.optJSONArray("serverlist") ?: return out
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val endpoint = o.optString("endpoint", "")
                    val colon = endpoint.lastIndexOf(':')
                    if (colon <= 0) continue
                    val port = endpoint.substring(colon + 1).toIntOrNull() ?: continue
                    out.add(CmServer(endpoint, endpoint.substring(0, colon), port, o.optString("dc", "").lowercase(),
                        o.optString("type", ""), o.optInt("load", 0), o.optDouble("wtd_load", 0.0)))
                }
            } finally { conn.disconnect() }
        } catch (t: Throwable) {
            Log.w(TAG, "directory fetch failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        if (cellId == 0 && out.isNotEmpty()) directoryCache = System.currentTimeMillis() to out
        Log.i(TAG, "directory: ${out.size} CM(s), dcs=${out.map { it.dc }.distinct().joinToString(",")}")
        return out
    }

    /** Datacenters to offer: catalog ∪ directory-discovered (discovered-but-unknown codes get their code as name). */
    fun datacenters(discovered: List<CmServer>): List<Datacenter> {
        val out = LinkedHashMap<String, Datacenter>()
        CATALOG.forEach { out[it.code] = it }
        discovered.map { it.dc }.filter { it.isNotEmpty() }.distinct().forEach { if (it !in out) out[it] = Datacenter(it, it.uppercase()) }
        return out.values.toList()
    }

    /** WebSocket CM hosts for [dc]: directory entries first (load order), then the regular pattern. */
    private fun webSocketHostsFor(dc: String, directory: List<CmServer>): List<Pair<String, Int>> {
        val out = LinkedHashSet<Pair<String, Int>>()
        directory.filter { it.dc == dc && it.type.startsWith("websocket") }.sortedBy { it.wtdLoad }
            .forEach { out.add(it.host to it.port) }
        for (n in 1..2) for (port in intArrayOf(443, 27018, 27019)) out.add("cmp$n-$dc.steamserver.net" to port)
        return out.toList()
    }

    // ── probe ─────────────────────────────────────────────────────────────────────────────────

    /** Parallel TCP-connect probe: one representative host (port 443) per datacenter. Bounded ~4 s. */
    fun probe(dcs: List<String>, directory: List<CmServer>): List<Probe> {
        val results = java.util.Collections.synchronizedList(ArrayList<Probe>())
        val latch = CountDownLatch(dcs.size)
        for (dc in dcs) {
            val host = webSocketHostsFor(dc, directory).firstOrNull { it.second == 443 }?.first ?: "cmp1-$dc.steamserver.net"
            Thread({
                var ms = -1L
                try {
                    val t0 = System.nanoTime()
                    Socket().use { it.connect(InetSocketAddress(host, 443), PROBE_TIMEOUT_MS) }
                    ms = (System.nanoTime() - t0) / 1_000_000L
                } catch (_: Throwable) {}
                results.add(Probe(dc, host, ms))
                latch.countDown()
            }, "steam-region-probe-$dc").apply { isDaemon = true }.start()
        }
        latch.await(PROBE_TOTAL_MS, TimeUnit.MILLISECONDS)
        val list = results.toList().sortedWith(compareBy({ it.ms < 0 }, { it.ms }))
        Log.i(TAG, "probe: " + list.joinToString(" ") { "${it.dc}=${if (it.ms < 0) "x" else "${it.ms}ms"}" })
        return list
    }

    /** Probe every known datacenter (catalog ∪ discovered) — the Settings "Test" button. */
    fun probeAll(ctx: Context): List<Probe> {
        val dir = fetchDirectory()
        val res = probe(datacenters(dir).map { it.code }, dir)
        res.firstOrNull { it.ms >= 0 }?.let { if (mode(ctx) == AUTO) rememberAuto(ctx, it) }
        return res
    }

    /**
     * The datacenter to use right now: the explicit choice, or Auto's remembered winner
     * (re-probed after 24 h / after a failure when [allowProbe]). Null = no preference.
     */
    fun resolveDc(ctx: Context, allowProbe: Boolean): String? {
        val m = mode(ctx)
        if (m != AUTO) return m
        rememberedAuto(ctx)?.let { return it.dc }
        if (!allowProbe) return null
        val dir = fetchDirectory()
        // Auto probes the datacenters Steam itself considers near this connection (directory), plus
        // the catalog so a mis-geolocated connection can still find the real nearest one.
        val res = probe(datacenters(dir).map { it.code }, dir)
        val winner = res.firstOrNull { it.ms >= 0 } ?: return null
        rememberAuto(ctx, winner)
        return winner.dc
    }

    // ── consumers ─────────────────────────────────────────────────────────────────────────────

    /**
     * (1) Rust engine: the CM WebSocket URL to connect to. Region-aware pick with the engine's own
     * directory pick as the fallback (empty = nothing at all). Worker thread only.
     */
    fun pickEngineCmUrl(ctx: Context, caBundlePath: String): String {
        try {
            val dc = resolveDc(ctx, allowProbe = true)
            if (dc != null) {
                val dir = fetchDirectory()
                val bad = badHost(ctx)
                val remembered = rememberedAuto(ctx)?.takeIf { it.dc == dc }?.host
                val hosts = ArrayList<Pair<String, Int>>()
                if (remembered != null && remembered != bad) hosts.add(remembered to 443)
                webSocketHostsFor(dc, dir).filter { it.first != bad }.forEach { if (it !in hosts) hosts.add(it) }
                val pick = hosts.firstOrNull()
                if (pick != null) {
                    val url = "wss://${pick.first}:${pick.second}/cmsocket/"
                    Log.i(TAG, "engine CM pick: $url (region ${describe(ctx)})")
                    return url
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "region pick failed — falling back to the engine's directory pick", t)
        }
        val fallback = try { BlSteamSession.pickCmUrl(caBundlePath) } catch (_: Throwable) { "" }
        Log.i(TAG, "engine CM pick (directory fallback): $fallback")
        return fallback
    }

    /** Host part of a `wss://host:port/cmsocket/` URL (for [invalidateAuto]). */
    fun hostOf(url: String): String? = try { URL(url.replaceFirst("wss://", "https://")).host } catch (_: Throwable) { null }

    /**
     * (2) JavaSteam: server-list provider for `SteamConfiguration.withServerListProvider`. Returns
     * the chosen datacenter's TCP CMs (an explicit choice, or Auto's remembered winner), or nothing
     * so JavaSteam performs its normal directory pick. Consulted by JavaSteam on its first connect
     * only (later reconnects reuse its in-memory list) — a change applies on the next app start.
     */
    fun javaSteamServerListProvider(ctx: Context): IServerListProvider = object : IServerListProvider {
        override val lastServerListRefresh: Instant get() = Instant.now()
        override fun fetchServerList(): List<ServerRecord> {
            return try {
                val m = mode(ctx)
                val dc = if (m == AUTO) rememberedAuto(ctx)?.dc else m
                if (dc == null) return emptyList()
                val tcp = fetchDirectory().filter { it.dc == dc && it.type == "netfilter" }
                if (tcp.isEmpty()) {
                    Log.i(TAG, "JavaSteam: no TCP CM listed for $dc — using JavaSteam's own directory pick")
                    return emptyList()
                }
                Log.i(TAG, "JavaSteam: ${tcp.size} TCP CM(s) for $dc")
                tcp.map { ServerRecord.createServer(it.host, it.port, ProtocolTypes.TCP) }
            } catch (t: Throwable) {
                Log.w(TAG, "JavaSteam provider failed", t); emptyList()
            }
        }
        override fun updateServerList(list: List<ServerRecord>) { /* JavaSteam's own cache; not persisted */ }
    }

    /**
     * (3) Engine CDN preference: (account cell id from `steam_prefs`, preferred dc or ""). Cell id
     * 0 lets Steam pick the pool by the connection's location.
     */
    fun cdnPreference(ctx: Context): Pair<Int, String> {
        val cell = try { prefs(ctx).getInt("cell_id", 0) } catch (_: Throwable) { 0 }
        val dc = try { resolveDc(ctx, allowProbe = false) ?: "" } catch (_: Throwable) { "" }
        return cell to dc
    }

    /**
     * (4) In-container client: GameHub-format `cmlist.json` for the chosen datacenter —
     * `{"datacenter":"<code>","cm_list":[{"endpoint":"cmp2-<dc>.steamserver.net:27019"},…]}`.
     * Returns false (file removed) when there is no region preference, so the genuine client keeps
     * its own discovery. Worker thread only.
     */
    fun writeCmListJson(ctx: Context, file: File): Boolean {
        return try {
            val dc = resolveDc(ctx, allowProbe = true)
            if (dc == null) { if (file.exists()) file.delete(); return false }
            val hosts = webSocketHostsFor(dc, fetchDirectory())
            val arr = JSONArray()
            hosts.forEach { arr.put(JSONObject().put("endpoint", "${it.first}:${it.second}")) }
            val json = JSONObject().put("datacenter", dc).put("cm_list", arr)
            file.parentFile?.mkdirs()
            file.writeText(json.toString(2))
            Log.i(TAG, "wrote ${file.name}: dc=$dc ${hosts.size} endpoint(s)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cmlist.json write failed", t); false
        }
    }
}
