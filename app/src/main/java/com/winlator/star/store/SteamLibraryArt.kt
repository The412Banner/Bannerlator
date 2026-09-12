package com.winlator.star.store

import android.content.Context
import com.winlator.star.store.blsteam.BlSteamEngine
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Steam's OWN published artwork for owned apps, read from the engine's library snapshot.
 *
 * ## Why this exists
 * Every other art source in the storefront either constructs a URL from a hard-coded CDN host and
 * hopes (`capsuleCandidates`), or asks the network for one (`appdetails`, SteamGridDB). PICS
 * appinfo is different: it is the asset list the app itself PUBLISHES, which is how Steam's own
 * client shows art for legacy titles whose `header.jpg` doesn't exist at the path we'd guess, and
 * for newer titles that use content-hashed asset paths.
 *
 * The engine already downloads it for every owned game, and until now Kotlin threw it away — the
 * snapshot's `owned_apps[].art` object was produced and never read. This is the consumer.
 *
 * ## Properties that make it the best candidate
 * No network call, no rate limit, no region dependency, and no guessing: an absent slot comes back
 * as `""` from the engine and is stored as null here, never as a fabricated URL. That is why
 * [header] slots in as capsule candidate #1 for owned games.
 *
 * ## Lifetime
 * The snapshot only exists while the engine is logged on, but the Library grid has to render art
 * offline too — so the parsed map is mirrored to a small JSON file under `filesDir` and reloaded on
 * first use. No database schema is involved.
 *
 * Every entry point is best-effort and never throws.
 */
object SteamLibraryArt {

    private const val TAG = StorefrontLog.LIBRARY
    private const val CACHE_FILE = "steam_app_art.json"

    /**
     * One app's published artwork. Every URL is nullable and null means "the app does not publish
     * this slot" — never "we couldn't guess it".
     */
    data class AppArt(
        /** The 92:43 store capsule. The one the storefront's cards are laid out around. */
        val header: String? = null,
        val smallCapsule: String? = null,
        /** Portrait 600x900 library cover — the shape shortcuts and the games wall want. */
        val libraryCapsule: String? = null,
        val libraryCapsule2x: String? = null,
        val libraryHeader: String? = null,
        val libraryHero: String? = null,
        val libraryLogo: String? = null,
        val icon: String? = null,
        val clientIcon: String? = null,
        val logo: String? = null,
        /** Steam's asset timestamp; content-hashed paths change with it. 0 = unknown. */
        val storeAssetMtime: Long = 0L,
        val logoPosition: LogoPosition? = null,
    ) {
        val isEmpty: Boolean
            get() = header == null && smallCapsule == null && libraryCapsule == null &&
                libraryCapsule2x == null && libraryHeader == null && libraryHero == null &&
                libraryLogo == null && icon == null && clientIcon == null && logo == null
    }

    /** Where the app wants its logo drawn over the hero. Carried through for a future hero layout. */
    data class LogoPosition(
        val widthPct: Double,
        val heightPct: Double,
        val pinnedPosition: String?,
    )

    private val art = ConcurrentHashMap<Int, AppArt>()

    @Volatile private var loadedFromDisk = false
    @Volatile private var appContext: Context? = null

    /** Call once with any Context so the disk mirror can be found; safe to call repeatedly. */
    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
        loadFromDiskOnce()
    }

    /** The 92:43 store capsule for [appId], or null when the app publishes none. */
    fun header(appId: Int): String? = lookup(appId)?.header

    /** The portrait 600x900 library cover for [appId], or null. */
    fun libraryCapsule(appId: Int): String? =
        lookup(appId)?.let { it.libraryCapsule ?: it.libraryCapsule2x }

    /** Everything known about [appId]'s art, or null when the app isn't in the snapshot. */
    fun of(appId: Int): AppArt? = lookup(appId)

    private fun lookup(appId: Int): AppArt? {
        if (appId <= 0) return null
        loadFromDiskOnce()
        return art[appId]
    }

    /**
     * Re-read the engine's library snapshot and replace the art map.
     *
     * BLOCKING (JSON parse of the whole snapshot) — call off the main thread, e.g. from the
     * `LibrarySynced:` event. A no-op when the engine isn't logged on, in which case whatever was
     * mirrored to disk stays in place rather than being cleared.
     */
    fun refreshFromSnapshot(ctx: Context) {
        init(ctx)
        val json = try {
            BlSteamEngine.session()?.getLibrarySnapshotJson()
        } catch (t: Throwable) {
            StorefrontLog.w(TAG, "library snapshot unavailable for art", t); null
        }
        if (json.isNullOrBlank()) {
            StorefrontLog.i(TAG, "PICS art: no snapshot (engine not logged on) — keeping ${art.size} cached entry(s)")
            return
        }
        val parsed = parseSnapshot(json)
        if (parsed.isEmpty()) {
            StorefrontLog.i(TAG, "PICS art: snapshot carried no artwork — keeping ${art.size} cached entry(s)")
            return
        }
        art.clear()
        art.putAll(parsed)
        val withHeader = parsed.values.count { it.header != null }
        StorefrontLog.i(
            TAG,
            "PICS art: ${parsed.size} owned app(s), $withHeader with a published header capsule " +
                "(no network, no rate limit)",
        )
        saveToDisk()
    }

    /** `{"owned_apps":[{"id":…,"art":{…}}]}` -> appId -> [AppArt]. Entries with no art are skipped. */
    private fun parseSnapshot(json: String): Map<Int, AppArt> {
        return try {
            val apps = JSONObject(json).optJSONArray("owned_apps") ?: return emptyMap()
            val out = HashMap<Int, AppArt>(apps.length())
            for (i in 0 until apps.length()) {
                val app = apps.optJSONObject(i) ?: continue
                val id = app.optInt("id", 0).takeIf { it > 0 } ?: continue
                val a = parseArt(app.optJSONObject("art")) ?: continue
                if (!a.isEmpty) out[id] = a
            }
            out
        } catch (e: Exception) {
            StorefrontLog.w(TAG, "PICS art: snapshot parse failed — ${e.javaClass.simpleName}: ${e.message}")
            emptyMap()
        }
    }

    private fun parseArt(o: JSONObject?): AppArt? {
        o ?: return null
        // The engine writes "" for a slot the app does not publish. Blank must stay null so the
        // candidate chain skips it rather than requesting an empty URL.
        fun str(key: String): String? = o.optString(key, "").takeIf { it.isNotBlank() }
        val pos = o.optJSONObject("logo_position")?.let {
            LogoPosition(
                widthPct = it.optDouble("width_pct", 0.0),
                heightPct = it.optDouble("height_pct", 0.0),
                pinnedPosition = it.optString("pinned_position", "").takeIf { p -> p.isNotBlank() },
            )
        }
        return AppArt(
            header = str("header"),
            smallCapsule = str("small_capsule"),
            libraryCapsule = str("library_capsule"),
            libraryCapsule2x = str("library_capsule_2x"),
            libraryHeader = str("library_header"),
            libraryHero = str("library_hero"),
            libraryLogo = str("library_logo"),
            icon = str("icon"),
            clientIcon = str("client_icon"),
            logo = str("logo"),
            storeAssetMtime = o.optLong("store_asset_mtime", 0L),
            logoPosition = pos,
        )
    }

    // ── Disk mirror ───────────────────────────────────────────────────────────────────────────

    private fun cacheFile(): File? = appContext?.let { File(it.filesDir, CACHE_FILE) }

    @Synchronized
    private fun loadFromDiskOnce() {
        if (loadedFromDisk) return
        loadedFromDisk = true
        val f = cacheFile() ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            for (key in root.keys()) {
                val id = key.toIntOrNull() ?: continue
                val a = parseArt(root.optJSONObject(key)) ?: continue
                if (!a.isEmpty) art.putIfAbsent(id, a)
            }
            StorefrontLog.i(TAG, "PICS art: ${art.size} entry(s) restored from the on-disk mirror")
        } catch (e: Exception) {
            StorefrontLog.w(TAG, "PICS art: on-disk mirror unreadable — ${e.javaClass.simpleName}")
            runCatching { f.delete() }
        }
    }

    /** Mirror the map so the Library still shows art when the engine isn't logged on. */
    private fun saveToDisk() {
        val f = cacheFile() ?: return
        try {
            val root = JSONObject()
            for ((id, a) in art) {
                root.put(
                    id.toString(),
                    JSONObject().apply {
                        a.header?.let { put("header", it) }
                        a.smallCapsule?.let { put("small_capsule", it) }
                        a.libraryCapsule?.let { put("library_capsule", it) }
                        a.libraryCapsule2x?.let { put("library_capsule_2x", it) }
                        a.libraryHeader?.let { put("library_header", it) }
                        a.libraryHero?.let { put("library_hero", it) }
                        a.libraryLogo?.let { put("library_logo", it) }
                        a.icon?.let { put("icon", it) }
                        a.clientIcon?.let { put("client_icon", it) }
                        a.logo?.let { put("logo", it) }
                        if (a.storeAssetMtime > 0L) put("store_asset_mtime", a.storeAssetMtime)
                        a.logoPosition?.let { p ->
                            put(
                                "logo_position",
                                JSONObject()
                                    .put("width_pct", p.widthPct)
                                    .put("height_pct", p.heightPct)
                                    .put("pinned_position", p.pinnedPosition ?: ""),
                            )
                        }
                    },
                )
            }
            f.writeText(root.toString())
        } catch (e: Exception) {
            StorefrontLog.w(TAG, "PICS art: could not write the on-disk mirror — ${e.javaClass.simpleName}")
        }
    }
}
