package com.winlator.star.reshade

import com.winlator.star.contents.Downloader
import org.json.JSONObject

/**
 * One downloadable ReShade effect listed in the remote catalog (reshade.json), hosted on the SAME
 * winlator-contents repo the app already uses for components.json / contents.json.
 *
 * Catalog URL:   https://raw.githubusercontent.com/The412Banner/winlator-contents/main/reshade.json
 * Archive URL:   <base>/<archive>  (archive is repo-relative, e.g. "reshade/Technicolor.tzst"),
 *                or an absolute http(s) URL if the catalog supplies one.
 *
 * Each archive is a zstd-compressed tar (.tzst) carrying ONE self-contained effect: the .fx plus its
 * co-located .fxh includes and any textures — the exact layout that lives under
 * getExternalFilesDir/ReShade/<id>/ once installed. [id] is the drop-in folder name AND the archive
 * base name, so it round-trips with ReshadeManager's scanner.
 *
 * reshade.json SCHEMA (mirrors components.json's { "components": [...] } convention):
 *
 *   {
 *     "version": 1,                        // catalog format version (optional, informational)
 *     "reshade": [
 *       {
 *         "id": "Technicolor",             // REQUIRED. stable id == drop-in folder name == archive base name
 *         "name": "Technicolor",           // display label (defaults to id)
 *         "description": "Cinematic ...",  // one-line blurb
 *         "category": "Color",             // grouping: Color | Sharpen | Bloom | CRT | Tonemap | AA | ...
 *         "author": "Marty McFly",         // credit
 *         "license": "MIT",                // license id (credit + future filtering)
 *         "archive": "reshade/Technicolor.tzst", // repo-relative (or absolute http) .tzst path; defaults to "reshade/<id>.tzst"
 *         "size": 18432,                   // archive size in bytes (informational; 0 if unknown)
 *         "version": "1.0",                // effect version string
 *         "sha256": "",                    // optional archive checksum (reserved; not yet enforced)
 *         "fx": "Technicolor.fx"           // optional main .fx filename hint (defaults to <id>.fx)
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
    val archive: String,
    val size: Long,
    val version: String,
    val sha256: String,
    val fx: String,
)

object ReshadeCatalog {
    const val BASE = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main"
    const val URL = "$BASE/reshade.json"

    /** Fetch + parse the catalog. Returns an empty list on any network/parse failure (caller falls
     *  back to whatever is already in the drop-in folder). Mirrors ComponentCatalog.load(). */
    fun load(): List<ReshadeCatalogEntry> {
        val json = Downloader.downloadString(URL) ?: return emptyList()
        val arr = JSONObject(json).optJSONArray("reshade") ?: return emptyList()
        val out = ArrayList<ReshadeCatalogEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { o.optString("name") }.trim()
            if (id.isEmpty()) continue
            out.add(
                ReshadeCatalogEntry(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    description = o.optString("description"),
                    category = o.optString("category").ifBlank { "Other" },
                    author = o.optString("author"),
                    license = o.optString("license"),
                    archive = o.optString("archive").ifBlank { "reshade/$id.tzst" },
                    size = o.optLong("size", 0L),
                    version = o.optString("version"),
                    sha256 = o.optString("sha256"),
                    fx = o.optString("fx").ifBlank { "$id.fx" },
                )
            )
        }
        return out
    }

    /** Absolute archive URL: the catalog's `archive` verbatim if it already has a scheme, else
     *  resolved against the winlator-contents raw base. */
    fun resolveUrl(entry: ReshadeCatalogEntry): String =
        if (entry.archive.startsWith("http", ignoreCase = true)) entry.archive
        else "$BASE/${entry.archive.trimStart('/')}"
}
