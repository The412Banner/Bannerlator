package com.winlator.star.store.blsteam

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owned-library crawl on the native Rust engine (Phase 1-A of docs/STEAM_RUST_ENGINE_PLAN.md).
 *
 * Mirrors the JavaSteam PICS state machine in `SteamRepository` step for step — license list →
 * package product-info (package → appIds) → app access tokens → app product-info in small
 * sequential batches — but every hop is a blocking call into `libblsteam.so` instead of a CM
 * callback. The crawler is deliberately engine-only: it hands raw results to a [Sink] and never
 * touches the database, so `SteamRepository` stays the single owner of the `steam_*` tables and
 * the emitted `LibraryProgress` / `LibrarySynced` events (same rows, same events, either engine).
 *
 * Product info reaches the sink as the engine's JSON `appinfo` object (keys in source order —
 * the crate enables serde_json `preserve_order`, and Android's JSONObject keeps insertion order),
 * which the repository rebuilds into the same `KeyValue` tree the JavaSteam parser consumes.
 *
 * Blocking; run on a worker thread. Bounded per hop by the native 30 s request timeout.
 */
class BlLibraryCrawler(private val session: BlSteamSession) {

    /** One entry of the post-logon license list. */
    data class License(
        val packageId: Int,
        val accessToken: Long,
        val timeCreated: Long,
        val flags: Int,
        val licenseType: Int,
    )

    /** Receives the crawl's results in order; every callback runs on the crawl thread. */
    interface Sink {
        /** Step 0: the account's license list (may be called once). */
        fun onLicenses(licenses: List<License>)

        /** Step 1: package product-info resolved to app ids (packageId → appIds) + the unique app set. */
        fun onPackagesResolved(packageApps: Map<Int, List<Int>>, uniqueAppIds: List<Int>)

        /** Step 2: one batch of app product-info (`appId` → the `appinfo` JSON object). */
        fun onAppBatch(apps: List<Pair<Int, JSONObject>>, processed: Int, total: Int)

        /** The crawl completed; [total] = app records delivered through [onAppBatch]. */
        fun onFinished(total: Int)

        /** The crawl stopped early (engine gone, no licenses, request failure). */
        fun onFailed(reason: String, processed: Int)

        /** Polled between hops so a stopped engine / superseded sync ends the crawl promptly. */
        fun isCancelled(): Boolean
    }

    /** Full owned-library crawl. */
    fun run(sink: Sink) {
        var processed = 0
        try {
            // ── 0. License list (pushed right after logon; poll briefly for it) ─────────────────
            val licenses = awaitLicenses(sink)
            if (licenses == null) { sink.onFailed("cancelled", 0); return }
            if (licenses.isEmpty()) { sink.onFailed("no licenses received", 0); return }
            sink.onLicenses(licenses)
            if (sink.isCancelled()) { sink.onFailed("cancelled", 0); return }

            // ── 1. Package product-info → appIds ───────────────────────────────────────────────
            val packageApps = LinkedHashMap<Int, List<Int>>()
            val unique = LinkedHashSet<Int>()
            for (chunk in licenses.chunked(PACKAGE_BATCH)) {
                if (sink.isCancelled()) { sink.onFailed("cancelled", 0); return }
                val json = session.getPicsPackageInfo(chunk.map { it.packageId }, chunk.map { it.accessToken })
                if (json == null) {
                    Log.w(TAG, "package product-info request failed for ${chunk.size} packages — continuing")
                    continue
                }
                val arr = try { JSONArray(json) } catch (t: Throwable) { JSONArray() }
                for (i in 0 until arr.length()) {
                    val pkg = arr.optJSONObject(i) ?: continue
                    val id = pkg.optInt("packageid", 0)
                    if (id == 0) continue
                    val ids = ArrayList<Int>()
                    val appArr = pkg.optJSONArray("appids")
                    if (appArr != null) for (k in 0 until appArr.length()) {
                        val a = appArr.optInt(k, 0)
                        if (a > 0) { ids.add(a); unique.add(a) }
                    }
                    packageApps[id] = ids
                }
            }
            val appIds = unique.toList()
            sink.onPackagesResolved(packageApps, appIds)
            if (appIds.isEmpty()) { sink.onFinished(0); return }

            // ── 2a. App access tokens (private/hidden apps need one for full appinfo) ──────────
            val tokens = fetchAppTokens(appIds, sink) ?: run { sink.onFailed("cancelled", 0); return }

            // ── 2b. App product-info, small sequential batches (same batch size as JavaSteam) ──
            val total = appIds.size
            for (chunk in appIds.chunked(APP_BATCH)) {
                if (sink.isCancelled()) { sink.onFailed("cancelled", processed); return }
                val apps = fetchAppsWithTokens(chunk, tokens)
                processed += chunk.size
                sink.onAppBatch(apps, processed, total)
            }
            sink.onFinished(total)
        } catch (t: Throwable) {
            Log.w(TAG, "library crawl failed", t)
            sink.onFailed("${t.javaClass.simpleName}: ${t.message}", processed)
        }
    }

    /**
     * One-shot product-info fetch for [appIds] (access tokens resolved first) — the engine-side
     * counterpart of a single `picsGetProductInfo` future. Returns whatever came back (missing ids
     * are simply absent). Blocking; bounded by the native timeouts.
     */
    fun fetchApps(appIds: List<Int>): List<Pair<Int, JSONObject>> {
        if (appIds.isEmpty()) return emptyList()
        val tokens = fetchAppTokens(appIds, null) ?: emptyMap()
        val out = ArrayList<Pair<Int, JSONObject>>()
        for (chunk in appIds.chunked(APP_BATCH)) out.addAll(fetchAppsWithTokens(chunk, tokens))
        return out
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private fun awaitLicenses(sink: Sink): List<License>? {
        val deadline = System.currentTimeMillis() + LICENSE_WAIT_MS
        while (true) {
            if (sink.isCancelled()) return null
            val list = parseLicenses(session.getLicenseList())
            if (list.isNotEmpty()) return list
            if (System.currentTimeMillis() >= deadline) return emptyList()
            try { Thread.sleep(250) } catch (_: InterruptedException) { return null }
        }
    }

    private fun parseLicenses(json: String?): List<License> {
        if (json.isNullOrEmpty()) return emptyList()
        val arr = try { JSONArray(json) } catch (t: Throwable) { return emptyList() }
        val out = ArrayList<License>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("packageId", 0)
            if (id == 0) continue
            out.add(
                License(
                    packageId = id,
                    accessToken = o.optLong("accessToken", 0L),
                    timeCreated = o.optLong("timeCreated", 0L),
                    flags = o.optInt("flags", 0),
                    licenseType = o.optInt("licenseType", 0),
                ),
            )
        }
        return out
    }

    /** appId → access token (0 when public / denied). Null only when cancelled. */
    private fun fetchAppTokens(appIds: List<Int>, sink: Sink?): Map<Int, Long>? {
        val tokens = HashMap<Int, Long>()
        for (chunk in appIds.chunked(TOKEN_BATCH)) {
            if (sink?.isCancelled() == true) return null
            val json = session.getPicsAccessTokens(chunk, emptyList())
            if (json == null) {
                Log.w(TAG, "access-token request failed for ${chunk.size} apps — treating as public")
                continue
            }
            try {
                val appTokens = JSONObject(json).optJSONObject("appTokens") ?: continue
                val keys = appTokens.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toIntOrNull() ?: continue
                    // Tokens are u64 serialised as decimal strings; parse unsigned-safe.
                    val tok = appTokens.optString(k, "0").toULongOrNull()?.toLong() ?: 0L
                    if (tok != 0L) tokens[id] = tok
                }
            } catch (t: Throwable) {
                Log.w(TAG, "access-token parse failed", t)
            }
        }
        return tokens
    }

    private fun fetchAppsWithTokens(chunk: List<Int>, tokens: Map<Int, Long>): List<Pair<Int, JSONObject>> {
        val json = session.getPicsAppProductInfo(chunk, chunk.map { tokens[it] ?: 0L })
        if (json == null) {
            Log.w(TAG, "app product-info request failed for ${chunk.size} apps — batch skipped")
            return emptyList()
        }
        val arr = try { JSONArray(json) } catch (t: Throwable) { return emptyList() }
        val out = ArrayList<Pair<Int, JSONObject>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("appid", 0)
            val info = o.optJSONObject("appinfo") ?: continue
            if (id > 0) out.add(id to info)
        }
        return out
    }

    companion object {
        private const val TAG = "BL_STEAM_LIB"
        /** Same as SteamRepository.APP_SYNC_BATCH so the two engines pace the CM identically. */
        const val APP_BATCH = 25
        private const val PACKAGE_BATCH = 100
        private const val TOKEN_BATCH = 200
        private const val LICENSE_WAIT_MS = 15_000L
    }
}
