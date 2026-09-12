package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.SaveLocator
import java.io.File

/**
 * Epic cloud-save path resolution — the Epic peer of [SteamCloudSavePaths].
 *
 * Two jobs:
 *   1. [resolveContainer] — find the Wine container a given Epic game launches in, adapting the
 *      PATTERN of [SteamCloudSavePaths.resolveContainer]. Epic shortcuts are stamped
 *      `storeSource=epic` + `epicAppName=<appName>` by [StarLaunchBridge], so we match that extra
 *      directly (exact), falling back to install-dir path-matching for shortcuts written before the
 *      tag existed.
 *   2. [resolveSaveDirectory] — expand a game's Epic **CloudSaveFolder** token string
 *      (`customAttributes.CloudSaveFolder`, persisted per-game in `bh_epic_prefs` at
 *      `epic_save_folder_<appName>`) into a concrete directory inside the chosen container's Wine
 *      prefix. This is a clean-room reimplementation of the behaviour of Epic's launcher / Legendary's
 *      `resolve_save_path` — reimplemented from the documented token semantics, NOT copied from the
 *      GPL-3.0 GameNative/Legendary source.
 *
 * ── Token semantics (verified against Epic/Legendary, 2026-08) ────────────────────────────────
 * Epic's `{appdata}` maps to **%LOCALAPPDATA%** (AppData/Local), NOT Roaming — Legendary's
 * `resolve_save_path` expands `{appdata}` → `%LOCALAPPDATA%`. So both `{appdata}` and `{localappdata}`
 * resolve to AppData/Local here (this matches GameNative — which is correct in this instance, and
 * confirmed against Legendary rather than trusted blindly). Roaming AppData has its own explicit
 * `{roamingappdata}` token.
 *
 * SAFETY: `..` is allowed *within* the walk — Epic's own templates use it to reach the sibling
 * AppData roots (`{AppData}/../Roaming/...`, `{AppData}/../LocalLow/...`) — but every resolve refuses
 * any result that canonicalizes outside the container's user profile (install dir for `{installdir}`)— mirrors [SteamCloudSavePaths.toContainerPath]. Returns null (⇒ caller
 * skips / reports "can't resolve") rather than guessing.
 */
object EpicCloudSavePaths {

    private const val TAG = "BH_EPIC_CLOUD"
    private const val PREFS_NAME = "bh_epic_prefs"

    /** The persisted CloudSaveFolder token string for a game (written from the catalog parse). */
    fun cloudSaveFolder(ctx: Context, appName: String): String? =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("epic_save_folder_$appName", null)
            ?.takeIf { it.isNotBlank() }

    /** Absolute on-device install dir for a game (`imagefs/epic_games/<sanitized>`), or null. */
    fun installDir(ctx: Context, appName: String): File? =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("epic_dir_$appName", null)
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }

    // ── Container resolution ──────────────────────────────────────────────────────

    /**
     * The Wine container this Epic game launches in. Primary match: a shortcut tagged
     * `storeSource=epic` whose `epicAppName` extra equals [appName] (exact — the shortcut
     * [StarLaunchBridge] wrote at launch). Fallback: a shortcut whose exec target points into the
     * game's install dir (the [SteamCloudSavePaths.resolveContainer] pattern), for shortcuts written
     * before the epicAppName tag. Null ⇒ the game isn't set up in a container yet.
     */
    fun resolveContainer(ctx: Context, appName: String, installDir: String?): Container? {
        val shortcuts = try { ContainerManager(ctx).loadShortcuts() } catch (e: Exception) {
            Log.w(TAG, "loadShortcuts failed", e); return null
        }
        return matchContainer(ctx, shortcuts, appName, installDir)
    }

    /** [resolveContainer] over a pre-loaded shortcut list — so a batch ([listStatuses]) loads once. */
    private fun matchContainer(
        ctx: Context,
        shortcuts: List<Shortcut>,
        appName: String,
        installDir: String?,
    ): Container? {
        // Primary: exact epicAppName tag.
        for (sc in shortcuts) {
            if (sc.getExtra("storeSource") == "epic" && sc.getExtra("epicAppName") == appName) {
                return sc.container
            }
        }

        // Fallback: install-dir path match (mirrors SteamCloudSavePaths.resolveContainer).
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

    /** Human label for dialogs/rows, e.g. "Container 2 — Default". Mirrors the Steam helper. */
    fun containerLabel(container: Container): String {
        val name = container.name
        return if (!name.isNullOrBlank()) "Container ${container.id} — $name"
        else "Container ${container.id}"
    }

    // ── Save Manager Epic-tab listing (models CustomSaveVault.CustomGameStatus / .listStatuses) ──

    /** Per-installed-Epic-game row for the Save Manager's Epic tab. */
    data class EpicSaveStatus(
        val appName: String,
        val name: String,
        /** The launch container's label, or null when the game isn't set up in a container yet. */
        val containerLabel: String?,
        /** True when the catalog reported a CloudSaveFolder for this game (sync is meaningful). */
        val cloudSaveEnabled: Boolean,
        /**
         * True once we've fetched this game's cloud-save metadata at least once (via an Epic-store
         * library refresh). Distinguishes "no cloud-save support" (checked, [cloudSaveEnabled] false)
         * from "not refreshed yet" (never checked).
         */
        val metadataChecked: Boolean,
        /** Last successful sync (epoch millis) from `epic_sync_timestamp_`, or 0 if never. */
        val lastSyncMillis: Long,
        /** Cover art URL from the Epic library cache (artCover → artSquare), or null if none cached. */
        val coverUrl: String?,
    )

    /**
     * Enumerate every installed Epic game (`epic_exe_<appName>` keys in `bh_epic_prefs`) and build
     * its Epic-tab status: display name (from the `epic_cache` metadata), launch container, whether
     * it's cloud-save enabled, and its last-sync time. BLOCKING (loads shortcuts once) — call off the
     * main thread. Sorted cloud-enabled-first, then by name.
     */
    fun listStatuses(ctx: Context): List<EpicSaveStatus> {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appNames = prefs.all.keys
            .filter { it.startsWith("epic_exe_") }
            .map { it.removePrefix("epic_exe_") }
            .filter { it.isNotEmpty() }
            .distinct()
        if (appNames.isEmpty()) return emptyList()

        val shortcuts: List<Shortcut> = try { ContainerManager(ctx).loadShortcuts() } catch (e: Exception) {
            Log.w(TAG, "listStatuses: loadShortcuts failed", e); emptyList()
        }

        return appNames.map { an ->
            val detail = EpicLibrarySync.cachedDetail(ctx, an)
            val title = detail?.title?.takeIf { it.isNotBlank() } ?: an
            val installDir = prefs.getString("epic_dir_$an", null)
            val container = matchContainer(ctx, shortcuts, an, installDir)
            EpicSaveStatus(
                appName = an,
                name = title,
                containerLabel = container?.let {
                    it.name?.takeIf { n -> n.isNotBlank() } ?: "Container ${it.id}"
                },
                cloudSaveEnabled = cloudSaveFolder(ctx, an) != null,
                metadataChecked = prefs.getBoolean("epic_cloud_checked_$an", false) ||
                    cloudSaveFolder(ctx, an) != null,
                lastSyncMillis = parseIso8601Ms(prefs.getString("epic_sync_timestamp_$an", null)),
                coverUrl = detail?.artCover?.takeIf { it.isNotBlank() },
            )
        }.sortedWith(compareBy({ !it.cloudSaveEnabled }, { it.name.lowercase() }))
    }

    /** Best-effort parse of an ISO-8601 UTC "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" marker → epoch millis (0 if none). */
    private fun parseIso8601Ms(s: String?): Long {
        if (s.isNullOrBlank() || s.length < 19) return 0L
        return try {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(
                s.substring(0, 4).toInt(), s.substring(5, 7).toInt() - 1, s.substring(8, 10).toInt(),
                s.substring(11, 13).toInt(), s.substring(14, 16).toInt(), s.substring(17, 19).toInt(),
            )
            cal.timeInMillis
        } catch (e: Exception) { 0L }
    }

    // ── Save-directory resolution (the prize) ─────────────────────────────────────

    /**
     * Resolve a game's CloudSaveFolder token string into the concrete directory to sync, inside
     * [container]'s Wine prefix. Requires the persisted `epic_save_folder_<appName>` string. Null if
     * that string is missing, the leading token is unknown/unsupported, the token needs a container
     * we don't have, or the resolved result would escape the container's user profile.
     *
     * After resolving, descends into the first non-empty per-user subdirectory when the resolved
     * folder itself holds no files but wraps one or more populated sub-folders (some Epic titles nest
     * saves under an unnamed per-user/account id folder the token string doesn't spell out).
     */
    fun resolveSaveDirectory(ctx: Context, appName: String, container: Container?): File? {
        val raw = cloudSaveFolder(ctx, appName) ?: run {
            Log.w(TAG, "resolveSaveDirectory: no CloudSaveFolder for $appName"); return null
        }
        // '\'→'/', drop a leading slash; split leading {token} from the literal remainder.
        val norm = raw.replace('\\', '/').trim().trimStart('/')
        val tokenMatch = Regex("^\\{([^}]+)\\}").find(norm)
        if (tokenMatch == null) {
            Log.w(TAG, "resolveSaveDirectory: no leading token in CloudSaveFolder '$raw'"); return null
        }
        val token = tokenMatch.groupValues[1].lowercase()

        val accountId = EpicCredentialStore.load(ctx)?.accountId ?: ""

        // Leading token → base directory + the containment boundary the resolved path may not escape.
        // Epic templates legitimately hop between the sibling AppData roots with '..'
        // ("{AppData}/../Roaming/...", "{AppData}/../LocalLow/..."), so for profile-relative tokens the
        // boundary is the whole user profile — NOT the individual AppData sub-dir the token names.
        val base: File
        val boundary: File
        when (token) {
            "installdir" -> {
                val inst = installDir(ctx, appName) ?: run {
                    Log.w(TAG, "resolveSaveDirectory: {installdir} but no install dir for $appName"); return null
                }
                base = inst; boundary = inst
            }
            "userprofile" -> { val p = profileOrNull(container) ?: return null; base = p; boundary = p }
            "userdir" -> { val p = profileOrNull(container) ?: return null; base = File(p, "Documents"); boundary = p }
            "usersavedgames" -> { val p = profileOrNull(container) ?: return null; base = File(p, "Saved Games"); boundary = p }
            // Epic's {appdata} == %LOCALAPPDATA% (verified against Legendary) → AppData/Local.
            "appdata", "localappdata" -> { val p = profileOrNull(container) ?: return null; base = File(p, "AppData/Local"); boundary = p }
            "roamingappdata" -> { val p = profileOrNull(container) ?: return null; base = File(p, "AppData/Roaming"); boundary = p }
            else -> {
                Log.w(TAG, "resolveSaveDirectory: unknown token '{$token}' in '$raw'"); return null
            }
        }

        // Remainder after the leading token; expand inline {epicid}/{appname}.
        var remainder = norm.substring(tokenMatch.value.length).trimStart('/')
        remainder = remainder
            .replace("{epicid}", accountId, ignoreCase = true)
            .replace("{appname}", appName, ignoreCase = true)
        val segments = remainder.split('/').filter { it.isNotEmpty() && it != "." }

        // Walk each segment against what's actually on disk. A '..' climbs (Epic uses it to reach the
        // sibling AppData roots); a normal segment matches case-insensitively, then — only when that
        // fails AND the match is unambiguous — punctuation-insensitively, because a game's on-disk
        // publisher folder can differ from Epic's catalog spelling by separators (AIR/Unity write
        // "amanita-design.samorost3" where the catalog says "amanitadesign.samorost3"). Falls back to
        // the literal name for a not-yet-created path (a download creates it). The escape guard below
        // is the real safety net.
        var dir = base
        for (seg in segments) {
            if (seg == "..") { dir = dir.parentFile ?: File(dir, ".."); continue }
            val kids = dir.listFiles()
            val existing = kids?.firstOrNull { it.name.equals(seg, ignoreCase = true) }
                ?: kids?.let { k ->
                    val target = normalizeFolderName(seg)
                    if (target.isEmpty()) null
                    else k.filter { normalizeFolderName(it.name) == target }.singleOrNull()
                }
            dir = existing ?: File(dir, seg)
        }

        // Escape guard: canonical dest must be the boundary or strictly under it.
        val boundaryCanon = try { boundary.canonicalPath } catch (e: Exception) { return null }
        val destCanon = try { dir.canonicalPath } catch (e: Exception) { return null }
        if (destCanon != boundaryCanon && !destCanon.startsWith(boundaryCanon + File.separator)) {
            Log.w(TAG, "resolveSaveDirectory: path escapes profile for '$raw'"); return null
        }

        return descendToUserSubdir(dir)
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun profileOrNull(container: Container?): File? =
        container?.let { SaveLocator.profileDir(it) }

    /**
     * Punctuation-insensitive folder-name key: lowercase, alphanumerics only. Used as a *fallback*
     * match (only when an exact case-insensitive match fails and exactly one candidate normalizes to
     * the same key) to bridge separator differences between Epic's catalog spelling and the folder a
     * Wine-run engine actually wrote (e.g. "amanitadesign.samorost3" vs "amanita-design.samorost3").
     */
    private fun normalizeFolderName(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Some Epic titles nest their saves one level deeper, under an unnamed per-user / account-id
     * folder the CloudSaveFolder string doesn't spell out. If [dir] exists and holds no regular files
     * but does contain a populated sub-directory, descend into the first such sub-directory; otherwise
     * return [dir] unchanged. Best-effort — never throws.
     */
    private fun descendToUserSubdir(dir: File): File {
        return try {
            val children = dir.listFiles() ?: return dir
            val hasFiles = children.any { it.isFile }
            if (hasFiles) return dir
            val firstNonEmptyDir = children
                .filter { it.isDirectory }
                .firstOrNull { (it.listFiles()?.isNotEmpty() == true) }
            firstNonEmptyDir ?: dir
        } catch (t: Throwable) {
            dir
        }
    }
}
