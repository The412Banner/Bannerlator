package com.winlator.star.contents

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Step 5 of the Wrapper Version Manager (issue #132) — a curated, downloadable wrapper catalog,
 * a near-clone of {@link com.winlator.star.reshade.ReshadeCatalog}. The catalog index lives on the
 * SAME winlator-contents repo the app already uses for components.json / reshade.json; each wrapper's
 * archive is a GitHub RELEASE ASSET (not a raw repo file), referenced by the entry's explicit "url".
 *
 * Catalog URL:  https://raw.githubusercontent.com/The412Banner/winlator-contents/main/wrappers.json
 *
 * Each archive is a zstd-compressed tar (.tzst) carrying the usual wrapper payload
 * (usr/lib/libvulkan_wrapper.so + friends). A downloaded archive is handed straight to
 * WrapperManager.importWrapper, so it runs through the SAME import pipeline (validation + capability
 * detection + env-scan + smart settings) as a hand-picked file.
 *
 * wrappers.json SCHEMA (mirrors reshade.json, plus gpuTargets):
 *
 *   {
 *     "schemaVersion": 1,
 *     "mirrorBase": "https://github.com/.../releases/download/wrappers-v1/",
 *     "count": 4,
 *     "wrappers": [
 *       {
 *         "id": "gamenative",                 // uniqueness key
 *         "name": "GameNative wrapper",        // display label + import name
 *         "description": "Integrated BCn ICD.",
 *         "author": "GameNative",
 *         "license": "MIT",
 *         "url": "https://github.com/.../releases/download/wrappers-v1/gamenative.tzst",
 *         "file_size": "5332000",              // bytes, as a string
 *         "file_checksum": "5532...AB",        // UPPERCASE MD5 of the .tzst — verified after download
 *         "version": 1,
 *         "gpuTargets": ["mali", "exynos"]     // applicability hints; missing/empty -> ["all"]
 *       }
 *     ]
 *   }
 *
 * gpuTargets vocabulary: "mali", "exynos", "adreno", "powervr", "all". Used only to flag applicability
 * against the detected GPU in the browser — it never blocks a download.
 *
 * Degrades gracefully: an absent catalog (URL 404s before the assets are seeded), an offline device,
 * or malformed JSON all yield an empty list (never a crash) — the browser then shows "none yet".
 */
data class WrapperCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val license: String,
    val url: String,
    val fileSize: Long,
    val checksum: String,          // UPPERCASE MD5 of the .tzst (may be blank -> no verification)
    val version: Int,
    val gpuTargets: List<String>,  // lowercased; ["all"] when unspecified
)

object WrapperCatalog {
    private const val TAG = "WrapperCatalog"
    const val URL = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/wrappers.json"
    private const val CACHE_FILE = "wrapper_catalog.json"

    /** Where the parsed entries came from, so the UI can say "live" vs "cached/offline". */
    enum class Source { NETWORK, CACHE, NONE }
    data class Result(val entries: List<WrapperCatalogEntry>, val source: Source)

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    /** Network-first, then offline cache. A successful fetch is cached to filesDir/wrapper_catalog.json;
     *  on network failure the cached JSON (if any) is parsed instead. NONE (empty) only when there's
     *  neither network nor a cache. Mirrors ReshadeCatalog.loadCached. Never throws. */
    fun loadCached(context: Context): Result {
        val json = Downloader.downloadString(URL)
        if (json != null) {
            val parsed = parse(json)
            if (parsed.isNotEmpty()) {
                runCatching { cacheFile(context).writeText(json) }
                    .onFailure { Log.w(TAG, "failed to cache catalog", it) }
                return Result(parsed, Source.NETWORK)
            }
            // A reachable-but-empty catalog (seeded schema, no wrappers yet) still counts as live.
            if (looksLikeCatalog(json)) {
                runCatching { cacheFile(context).writeText(json) }
                return Result(emptyList(), Source.NETWORK)
            }
        }
        val cache = cacheFile(context)
        if (cache.isFile) {
            val cached = runCatching { parse(cache.readText()) }.getOrNull()
            if (!cached.isNullOrEmpty()) return Result(cached, Source.CACHE)
        }
        return Result(emptyList(), Source.NONE)
    }

    /** Fetch + parse the catalog (network only). Empty list on failure. */
    fun load(): List<WrapperCatalogEntry> =
        Downloader.downloadString(URL)?.let { parse(it) } ?: emptyList()

    /** True when the JSON is a well-formed catalog object (even if it lists zero wrappers). */
    private fun looksLikeCatalog(json: String): Boolean {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return false
        return root.has("wrappers")
    }

    private fun parse(json: String): List<WrapperCatalogEntry> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val mirrorBase = root.optString("mirrorBase")
        val arr = root.optJSONArray("wrappers") ?: return emptyList()
        val out = ArrayList<WrapperCatalogEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { o.optString("name") }.trim()
            if (id.isEmpty()) continue
            // Prefer the explicit url; fall back to mirrorBase + id + ".tzst".
            val url = o.optString("url").ifBlank {
                if (mirrorBase.isBlank()) "" else mirrorBase.trimEnd('/') + "/" + id + ".tzst"
            }
            if (url.isEmpty()) continue
            out.add(
                WrapperCatalogEntry(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    description = o.optString("description"),
                    author = o.optString("author"),
                    license = o.optString("license"),
                    url = url,
                    fileSize = o.optString("file_size").toLongOrNull() ?: o.optLong("file_size", 0L),
                    checksum = o.optString("file_checksum").trim().uppercase(),
                    version = o.optInt("version", 1),
                    gpuTargets = parseGpuTargets(o),
                )
            )
        }
        return out
    }

    /** Tolerate a missing / empty gpuTargets (either "gpuTargets" or snake_case "gpu_targets") ->
     *  default ["all"]. Values are lowercased + de-duped so the browser can match case-insensitively. */
    private fun parseGpuTargets(o: JSONObject): List<String> {
        val arr = o.optJSONArray("gpuTargets") ?: o.optJSONArray("gpu_targets")
        if (arr == null || arr.length() == 0) return listOf("all")
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val t = arr.optString(i).trim().lowercase()
            if (t.isNotEmpty()) out.add(t)
        }
        return if (out.isEmpty()) listOf("all") else out.toList()
    }
}
