package com.winlator.star.reshade

import android.content.Context
import android.util.Log
import com.winlator.star.contents.Downloader
import org.json.JSONObject
import java.io.File

/**
 * One downloadable ReShade effect from the LIVE catalog (reshade.json) hosted on the winlator-contents
 * repo — the SAME repo the app uses for components.json / contents.json. The catalog index lives at
 * the repo root; each effect's archive is a GitHub RELEASE ASSET (not a raw repo file), referenced by
 * the entry's explicit "url" field.
 *
 * Catalog URL:  https://raw.githubusercontent.com/The412Banner/winlator-contents/main/reshade.json
 *
 * Each archive is a zstd-compressed tar (.tzst) whose tar contains the folder "<id>/..." (the .fx plus
 * its co-located .fxh includes and any textures). It extracts into the ReShade ROOT drop-in folder
 * (getExternalFilesDir/ReShade/) → yielding getExternalFilesDir/ReShade/<id>/, the exact dir
 * ReshadeManager's scanner reads. [id] is the drop-in subfolder name (uniqueness key).
 *
 * LIVE reshade.json SCHEMA (exact published field names):
 *
 *   {
 *     "schemaVersion": 1,
 *     "category": "reshade-effect",
 *     "release": "reshade-v1",
 *     "mirrorBase": "https://github.com/.../releases/download/reshade-v1/",
 *     "count": 100,
 *     "note": "...",
 *     "effects": [
 *       {
 *         "id": "Technicolor",            // drop-in subfolder name (uniqueness key)
 *         "name": "Technicolor",          // display label
 *         "description": "Technicolor — prod80",
 *         "category": "Color/Tone",       // Bloom | Sharpen | CRT-Retro | Film-Grain | Vignette | Anti-aliasing | Lens-Geometry | Color-Tone | Other
 *         "author": "prod80",
 *         "license": "MIT",
 *         "url": "https://github.com/.../releases/download/reshade-v1/Technicolor.tzst", // download verbatim
 *         "file_size": "5332",            // bytes, as a string
 *         "file_checksum": "5532...AB",   // UPPERCASE MD5 of the .tzst — verified after download
 *         "version": 1
 *       }
 *     ]
 *   }
 */
data class ReshadeCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val author: String,
    val license: String,
    val url: String,
    val fileSize: Long,
    val checksum: String,   // UPPERCASE MD5 of the .tzst (may be blank → no verification)
    val version: Int,
)

object ReshadeCatalog {
    private const val TAG = "ReshadeCatalog"
    const val URL = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/reshade.json"
    private const val CACHE_FILE = "reshade_catalog.json"

    /** Result of an offline-aware catalog load: the parsed entries + where they came from, so the UI
     *  can tell the user whether they're seeing a live or a cached/offline list. */
    enum class Source { NETWORK, CACHE, NONE }
    data class Result(val entries: List<ReshadeCatalogEntry>, val source: Source)

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    /** Network-first, then offline cache. On a successful fetch the raw JSON is cached to
     *  filesDir/reshade_catalog.json for next time. On network failure the cached JSON (if any) is
     *  parsed instead, so the full list still renders offline. Returns NONE (empty) only when there's
     *  neither network nor a cache — the picker then falls back to scanning the drop-in folder. */
    fun loadCached(context: Context): Result {
        val json = Downloader.downloadString(URL)
        if (json != null) {
            val parsed = parse(json)
            if (parsed.isNotEmpty()) {
                runCatching { cacheFile(context).writeText(json) }
                    .onFailure { Log.w(TAG, "failed to cache catalog", it) }
                return Result(parsed, Source.NETWORK)
            }
        }
        val cache = cacheFile(context)
        if (cache.isFile) {
            val cached = runCatching { parse(cache.readText()) }.getOrNull()
            if (!cached.isNullOrEmpty()) return Result(cached, Source.CACHE)
        }
        return Result(emptyList(), Source.NONE)
    }

    /** Fetch + parse the catalog (network only). Empty list on failure. Mirrors ComponentCatalog.load(). */
    fun load(): List<ReshadeCatalogEntry> =
        Downloader.downloadString(URL)?.let { parse(it) } ?: emptyList()

    private fun parse(json: String): List<ReshadeCatalogEntry> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val mirrorBase = root.optString("mirrorBase")
        val arr = root.optJSONArray("effects") ?: return emptyList()
        val out = ArrayList<ReshadeCatalogEntry>(arr.length())
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
                ReshadeCatalogEntry(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    description = o.optString("description"),
                    category = o.optString("category").ifBlank { "Other" },
                    author = o.optString("author"),
                    license = o.optString("license"),
                    url = url,
                    fileSize = o.optString("file_size").toLongOrNull() ?: o.optLong("file_size", 0L),
                    checksum = o.optString("file_checksum").trim().uppercase(),
                    version = o.optInt("version", 1),
                )
            )
        }
        return out
    }
}
