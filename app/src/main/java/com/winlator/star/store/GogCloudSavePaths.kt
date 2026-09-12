package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.SaveLocator
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GOG cloud-save path resolution — the GOG peer of [EpicCloudSavePaths] / [SteamCloudSavePaths].
 *
 * Kills the manual "Browse" folder pick: given an installed GOG game it auto-resolves the concrete
 * save directory inside the Wine container's prefix, exactly the way Epic already does. The transport
 * ([GogCloudSaveManager] → `cloudstorage.gog.com/v1/{userId}/{clientId}`) is untouched — only how the
 * LOCAL folder is obtained changes.
 *
 * Two jobs, mirroring [EpicCloudSavePaths]:
 *   1. [resolveContainer] — find the Wine container a GOG game launches in. GOG shortcuts are UNTAGGED
 *      (unlike Epic's `storeSource=epic`; [StarLaunchBridge] only stamps steam/epic), and they install
 *      under `imagefs/gog_games/<dir>`, so the ONLY match is the install-dir path match — the fallback
 *      branch of [EpicCloudSavePaths.resolveContainer], which itself adapts [SteamCloudSavePaths].
 *   2. [resolveSaveDirectory] — expand the game's GOG cloud-storage **location template** into a
 *      concrete directory inside the chosen container's Wine prefix. Clean-room reimplementation from
 *      the documented GOG cloud-storage token semantics, NOT copied from GPL-3.0 GameNative/gogdl.
 *
 * ── Where the location template comes from (VERIFIED ON-DEVICE, ELDERBORN, 2026-08-22) ────────────
 * The installed `goggame-<clientId>.info` and `<Name>_GOG.json` files carry NO save-location entry —
 * only buildId/clientId/playTasks. GOG's per-game cloud-save location lives in the PUBLIC remote-config
 * endpoint, keyed by the game's Galaxy **clientId**:
 *
 *   GET https://remote-config.gog.com/components/galaxy_client/clients/<clientId>?component_version=2.0.45
 *   → content.Windows.cloudStorage = { enabled: bool, locations: [ { name, location } ] }
 *
 * For ELDERBORN (clientId 53002674479823021) this returns:
 *   { "enabled": true, "locations": [ { "name":"saves",
 *       "location":"<?APPLICATION_DATA_LOCAL_LOW?>/Hyperstrange/ELDERBORN" } ] }
 *
 * The endpoint needs no auth (verified with a plain GET), so no token is threaded here. The resolved
 * template is cached in `bh_gog_prefs` (`gog_cloud_location_<gameId>`) so subsequent resolves are
 * offline. When the metadata says `enabled=false` or has no locations we cache that too and return
 * null cleanly → the UI shows a no-cloud-support state (never a crash, never a guessed path).
 *
 * ── Location token semantics (GOG Galaxy SDK cloud-storage locations) ─────────────────────────────
 * GOG's location strings begin with an angle-bracket token (`<?APPLICATION_DATA_LOCAL_LOW?>` etc.);
 * some titles use Windows env-var style (`%LOCALAPPDATA%`). Both forms are expanded. Every profile-
 * relative token maps under the container's Wine user profile ([SaveLocator.profileDir]); `<?INSTALL?>`
 * maps to the game's install dir. Case-insensitive on-disk walk + `..`/escape guard are copied verbatim
 * in spirit from [EpicCloudSavePaths.resolveSaveDirectory]; any result canonicalizing outside its
 * boundary is refused (null), never guessed.
 */
object GogCloudSavePaths {

    private const val TAG = "BH_GOG_CLOUD"
    private const val PREFS_NAME = "bh_gog_prefs"
    private const val REMOTE_CONFIG_FMT =
        "https://remote-config.gog.com/components/galaxy_client/clients/%s?component_version=2.0.45"
    private const val TIMEOUT_MS = 15_000

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Cheap, offline lookups (no network) ───────────────────────────────────────

    /** Install dir NAME for a game (`gog_dir_<gameId>` — the `imagefs/gog_games/<name>` leaf), or null. */
    fun installDirName(ctx: Context, gameId: String): String? =
        prefs(ctx).getString("gog_dir_$gameId", null)?.takeIf { it.isNotBlank() }

    /** Absolute on-device install dir (`imagefs/gog_games/<name>`) for a game, or null. */
    fun installDir(ctx: Context, gameId: String): File? =
        installDirName(ctx, gameId)?.let { GogInstallPath.getInstallDir(ctx, it) }

    /**
     * Reverse-map a launched shortcut's exec target back to the GOG gameId it belongs to, by matching
     * the `gog_games/<dir>` leaf against the per-game install-dir prefs (`gog_dir_<gameId>`). GOG
     * shortcuts carry no `storeSource` tag, so this install-path match is the ONLY link from an
     * in-flight shortcut (e.g. the running game in [XServerDisplayActivity]) back to its store gameId —
     * the inverse of [matchContainer]. Offline (pref scan only). Null when the path has no
     * `gog_games/<dir>` segment, or no installed game claims that dir. Case-insensitive, slash-normalized
     * so "Game" can't false-match "Game 2".
     */
    fun gameIdForExecPath(ctx: Context, execPath: String?): String? {
        if (execPath.isNullOrBlank()) return null
        val norm = execPath.replace('\\', '/').lowercase()
        val marker = "gog_games/"
        val idx = norm.indexOf(marker)
        if (idx < 0) return null
        val after = norm.substring(idx + marker.length)
        val slash = after.indexOf('/')
        val dir = (if (slash >= 0) after.substring(0, slash) else after).trim()
        if (dir.isEmpty()) return null
        for ((key, value) in prefs(ctx).all) {
            if (!key.startsWith("gog_dir_")) continue
            val v = (value as? String)?.trim()?.lowercase() ?: continue
            if (v == dir) return key.substring("gog_dir_".length)
        }
        return null
    }

    /**
     * The game's Galaxy clientId used to key remote-config. Prefer the cached pref
     * (`gog_client_id_<gameId>`, written at install/browse time); fall back to parsing the installed
     * `goggame-<gameId>.info` (offline). Null ⇒ can't fetch cloud-save metadata.
     */
    fun clientId(ctx: Context, gameId: String): String? {
        prefs(ctx).getString("gog_client_id_$gameId", null)?.takeIf { it.isNotBlank() }?.let { return it }
        // Offline fallback: goggame-<gameId>.info in the install dir carries "clientId".
        val inst = installDir(ctx, gameId) ?: return null
        val info = File(inst, "goggame-$gameId.info")
        if (!info.isFile) return null
        return try {
            JSONObject(info.readText()).optString("clientId", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "clientId: failed to parse ${info.name}", e); null
        }
    }

    /** Cached cloud-storage location template for a game (`gog_cloud_location_<gameId>`), or null. */
    fun cachedLocation(ctx: Context, gameId: String): String? =
        prefs(ctx).getString("gog_cloud_location_$gameId", null)?.takeIf { it.isNotBlank() }

    /** True once this game's cloud metadata has been fetched at least once (checked, even if unsupported). */
    fun metadataChecked(ctx: Context, gameId: String): Boolean =
        prefs(ctx).getBoolean("gog_cloud_checked_$gameId", false) || cachedLocation(ctx, gameId) != null

    // ── Container resolution ──────────────────────────────────────────────────────

    /**
     * The Wine container this GOG game launches in. GOG shortcuts are UNTAGGED, so we match purely on
     * the install-dir path appearing in the shortcut's exec target (`Exec=wine Z:\gog_games\<dir>\…`) —
     * the fallback path of [EpicCloudSavePaths.resolveContainer]. Null ⇒ the game isn't attached to a
     * container yet (⇒ UI prompts to add it first).
     */
    fun resolveContainer(ctx: Context, gameId: String, installDir: String?): Container? {
        val shortcuts = try { ContainerManager(ctx).loadShortcuts() } catch (e: Exception) {
            Log.w(TAG, "loadShortcuts failed", e); return null
        }
        return matchContainer(ctx, shortcuts, installDir)
    }

    /** [resolveContainer] over a pre-loaded shortcut list — so a batch listing loads shortcuts once. */
    private fun matchContainer(ctx: Context, shortcuts: List<Shortcut>, installDir: String?): Container? {
        if (installDir.isNullOrBlank()) return null
        val imageFsRoot = File(ctx.filesDir, "imagefs").absolutePath.replace('\\', '/').trimEnd('/')
        val instAbs = installDir.replace('\\', '/').trimEnd('/')
        val instRel = if (instAbs.lowercase().startsWith(imageFsRoot.lowercase()))
            instAbs.substring(imageFsRoot.length).trimStart('/') else instAbs.trimStart('/')
        val keys = listOf("/${instRel.lowercase()}/", "/${instAbs.trimStart('/').lowercase()}/")

        for (sc in shortcuts) {
            val raw = sc.path ?: continue
            var exec = raw.replace('\\', '/').lowercase().trim()
            exec = exec.replaceFirst(Regex("^[a-z]:"), "")
            if (!exec.startsWith("/")) exec = "/$exec"
            if (keys.any { it.length > 2 && exec.contains(it) }) return sc.container
        }
        return null
    }

    /** Human label for dialogs/rows, e.g. "Container 2 — Default". Mirrors the Epic/Steam helpers. */
    fun containerLabel(container: Container): String {
        val name = container.name
        return if (!name.isNullOrBlank()) "Container ${container.id} — $name"
        else "Container ${container.id}"
    }

    // ── Location-template acquisition ─────────────────────────────────────────────

    /**
     * The game's cloud-storage location template. Cached-first (offline); on a miss, fetches GOG's
     * public remote-config (keyed by clientId), extracts the first Windows cloud-storage location, and
     * caches it. Caches the "checked"/"enabled" flags either way. Returns null when the game has no
     * clientId, the fetch fails, or GOG ships no cloud-save location for the title. NETWORK — off-main.
     */
    fun locationTemplate(ctx: Context, gameId: String): String? {
        cachedLocation(ctx, gameId)?.let { return it }
        val clientId = clientId(ctx, gameId) ?: run {
            debug(ctx, "autopath: no clientId for gameId=$gameId — cannot fetch cloud location"); return null
        }
        return fetchAndCacheLocation(ctx, gameId, clientId)
    }

    /** Fetch remote-config for [clientId], parse the first Windows cloud-storage location, cache it. */
    private fun fetchAndCacheLocation(ctx: Context, gameId: String, clientId: String): String? {
        val url = String.format(REMOTE_CONFIG_FMT, clientId)
        val body = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "GOG Galaxy")
            val code = conn.responseCode
            if (code < 200 || code >= 300) {
                conn.disconnect()
                debug(ctx, "autopath: remote-config HTTP=$code clientId=$clientId"); return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            text
        } catch (e: Exception) {
            debug(ctx, "autopath: remote-config fetch failed clientId=$clientId (${e.javaClass.simpleName})")
            return null
        }

        val location = try {
            val cloud = JSONObject(body)
                .optJSONObject("content")
                ?.optJSONObject("Windows")
                ?.optJSONObject("cloudStorage")
            val enabled = cloud?.optBoolean("enabled", false) ?: false
            val locations = cloud?.optJSONArray("locations")
            // Prefer a location named "saves"; else the first entry (P1 syncs a single folder).
            var chosen: String? = null
            if (locations != null && locations.length() > 0) {
                for (i in 0 until locations.length()) {
                    val o = locations.optJSONObject(i) ?: continue
                    val loc = o.optString("location", "")
                    if (loc.isBlank()) continue
                    if (o.optString("name", "").equals("saves", ignoreCase = true)) { chosen = loc; break }
                    if (chosen == null) chosen = loc
                }
            }
            if (!enabled) debug(ctx, "autopath: cloudStorage disabled for clientId=$clientId")
            if (locations != null && locations.length() > 1)
                debug(ctx, "autopath: ${locations.length()} locations for clientId=$clientId — using first/saves")
            chosen
        } catch (e: Exception) {
            debug(ctx, "autopath: remote-config parse failed clientId=$clientId (${e.javaClass.simpleName})")
            null
        }

        // Cache result (and the "checked" flag) regardless — a null means "checked, unsupported".
        prefs(ctx).edit().apply {
            putBoolean("gog_cloud_checked_$gameId", true)
            if (location != null) putString("gog_cloud_location_$gameId", location)
            apply()
        }
        debug(ctx, "autopath: template source=remote clientId=$clientId location=${location ?: "<none>"}")
        return location
    }

    // ── Save-directory resolution (the prize) ─────────────────────────────────────

    /**
     * Resolve a game's GOG cloud-save location template into the concrete directory to sync, inside
     * [container]'s Wine prefix. Null when: no container, no location template (missing clientId / no
     * cloud support / fetch failure), an unknown leading token, or a result that would escape its
     * boundary. NETWORK on a cache-miss ([locationTemplate]) — MUST be called off the main thread.
     */
    fun resolveSaveDirectory(ctx: Context, gameId: String, container: Container?): File? {
        if (container == null) {
            debug(ctx, "autopath: no container for gameId=$gameId — cannot resolve"); return null
        }
        val raw = locationTemplate(ctx, gameId) ?: run {
            debug(ctx, "autopath: no cloud-save location for gameId=$gameId — null"); return null
        }

        // Split the leading token (either <?TOKEN?> or %TOKEN%) from the literal remainder.
        val norm = raw.replace('\\', '/').trim().trimStart('/')
        val tokenMatch = Regex("^(<\\?[^?>]+\\?>|%[^%]+%)").find(norm)
        if (tokenMatch == null) {
            debug(ctx, "autopath: no leading token in location '$raw' — null"); return null
        }
        val token = tokenMatch.groupValues[1].trim('<', '>', '?', '%').uppercase()

        val profile = SaveLocator.profileDir(container)
        val base: File
        val boundary: File
        when (token) {
            "INSTALL" -> {
                val inst = installDir(ctx, gameId) ?: run {
                    debug(ctx, "autopath: <?INSTALL?> but no install dir for gameId=$gameId — null"); return null
                }
                base = inst; boundary = inst
            }
            "SAVED_GAMES" -> { base = File(profile, "Saved Games"); boundary = profile }
            "DOCUMENTS" -> { base = File(profile, "Documents"); boundary = profile }
            "APPLICATION_DATA_LOCAL", "LOCALAPPDATA" -> { base = File(profile, "AppData/Local"); boundary = profile }
            "APPLICATION_DATA_LOCAL_LOW" -> { base = File(profile, "AppData/LocalLow"); boundary = profile }
            "APPLICATION_DATA_ROAMING", "APPDATA" -> { base = File(profile, "AppData/Roaming"); boundary = profile }
            "USERPROFILE" -> { base = profile; boundary = profile }
            "PROGRAMDATA" -> {
                val pd = File(container.rootDir, ".wine/drive_c/ProgramData"); base = pd; boundary = pd
            }
            else -> {
                debug(ctx, "autopath: unknown token '$token' in '$raw' — null"); return null
            }
        }

        val remainder = norm.substring(tokenMatch.value.length).trimStart('/')
        val segments = remainder.split('/').filter { it.isNotEmpty() && it != "." }

        // Walk each segment against what's on disk (case-insensitive; '..' climbs). Falls back to the
        // literal name for a not-yet-created path (a download creates it). Escape guard is the safety net.
        var dir = base
        for (seg in segments) {
            if (seg == "..") { dir = dir.parentFile ?: File(dir, ".."); continue }
            val kids = dir.listFiles()
            val existing = kids?.firstOrNull { it.name.equals(seg, ignoreCase = true) }
            dir = existing ?: File(dir, seg)
        }

        val boundaryCanon = try { boundary.canonicalPath } catch (e: Exception) { return null }
        val destCanon = try { dir.canonicalPath } catch (e: Exception) { return null }
        if (destCanon != boundaryCanon && !destCanon.startsWith(boundaryCanon + File.separator)) {
            debug(ctx, "autopath: resolved path escapes boundary for '$raw' — null"); return null
        }

        debug(ctx, "autopath: resolved gameId=$gameId container=${container.id} → $destCanon")
        return dir
    }

    /**
     * Container + save dir in one off-main call for the wiring sites. Returns (container, dir): a null
     * container ⇒ "add to a container first"; a null dir with a non-null container ⇒ "no cloud location".
     * NETWORK on a cache-miss — MUST be called off the main thread.
     */
    fun resolve(ctx: Context, gameId: String): Pair<Container?, File?> {
        val installDir = installDir(ctx, gameId)?.absolutePath
        val container = try { resolveContainer(ctx, gameId, installDir) } catch (e: Exception) { null }
        val dir = container?.let { resolveSaveDirectory(ctx, gameId, it) }
        return container to dir
    }

    // ── Debug (reuses the existing GOG cloud debug buffer) ────────────────────────

    private fun debug(ctx: Context, msg: String) {
        try { GogCloudSaveManager.debug(ctx, msg) } catch (e: Throwable) { Log.d(TAG, msg) }
    }
}
