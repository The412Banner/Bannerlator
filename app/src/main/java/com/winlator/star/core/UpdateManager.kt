package com.winlator.star.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.winlator.star.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app updater for Bannerlator.
 *
 * Source of truth = the integer [BuildConfig.VERSION_CODE] (gradle `versionCode`).
 * Each STABLE GitHub release attaches an `update.json` asset; because the
 * `releases/latest` redirect only ever resolves to the newest NON-prerelease,
 * nightly/test (prerelease) builds never trip the updater.
 *
 * update.json shape:
 * {
 *   "versionCode": 26,
 *   "versionName": "1.8",
 *   "notes": "What changed…",
 *   "highlights": [ {"title": "Small updates", "text": "…"} ],   // optional, up to 5
 *   "size": { "com.winlator.banner": 557000000, … },            // optional, bytes per flavor
 *   "publishedAt": "2026-10-10",                                 // optional
 *   "minSupported": 1,
 *   "apkUrl": { "com.winlator.banner": "https://github.com/…/releases/download/1.8/Bannerlator-1.8-standard.apk", … },  // optional, absolute
 *   "apk": {
 *     "com.winlator.banner":    "Bannerlator-1.8-standard.apk",
 *     "com.ludashi.benchmark":  "Bannerlator-1.8-ludashi.apk",
 *     "com.tencent.ig":         "Bannerlator-1.8-pubg.apk",
 *     "com.antutu.ABenchMark":  "Bannerlator-1.8-antutu.apk"
 *   }
 * }
 */
object UpdateManager {
    private const val REPO = "The412Banner/Bannerlator"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    // The manifests live on the orphan `manifest` branch, served raw: reading them there counts
    // nothing (the release-asset URL below bumped update.json's download_count on every check,
    // which was 85% of the repo's "downloads") and has no per-IP API limit. release.yml writes
    // update.json on stable cuts and update-pre.json on every cut.
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/$REPO/manifest/update.json"
    private const val MANIFEST_PRE_URL =
        "https://raw.githubusercontent.com/$REPO/manifest/update-pre.json"
    // Fallbacks for before the manifest branch exists (first release from this code publishes it).
    private const val UPDATE_JSON_URL =
        "https://github.com/$REPO/releases/latest/download/update.json"
    private fun assetUrl(name: String) =
        "https://github.com/$REPO/releases/latest/download/$name"
    // Lists ALL releases newest-first, prereleases included (releases/latest skips them).
    private const val API_RELEASES_URL =
        "https://api.github.com/repos/$REPO/releases?per_page=30"
    /** Automatic checks (launch, About, opening Settings) reuse the last answer this long. */
    private const val CHECK_COOLDOWN_MS = 6L * 60 * 60 * 1000

    // Reuse the FileProvider already declared in the manifest. The authority is keyed
    // to the per-flavor applicationId (${applicationId}.tileprovider) so the standard,
    // ludashi and pubg flavors don't collide on a single device — a fixed authority
    // caused INSTALL_FAILED_CONFLICTING_PROVIDER when two flavors were installed.
    private const val FILE_PROVIDER_SUFFIX = ".tileprovider"

    private const val PREF_NOTIFY = "update_notify_enabled"
    private const val PREF_SKIP = "update_skip_version"
    private const val PREF_LAST_CHECK = "update_last_check"
    private const val PREF_INCLUDE_PRE = "update_include_prereleases"
    // One cache per channel: stable and prerelease used to share a file, so flipping the toggle
    // could serve the other channel's answer offline.
    private const val CACHE_NAME = "update_latest.json"
    private const val CACHE_PRE_NAME = "update_latest_pre.json"

    /** One line of the release's quick highlights: a short bold title and one plain sentence. */
    data class Highlight(val title: String, val text: String)

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val apkName: String?,
        /** Resolved download URL for this flavor's APK on the matched release. */
        val apkUrl: String?,
        /** Up to five quick highlights from the release notes; empty on releases cut before they existed. */
        val highlights: List<Highlight> = emptyList(),
        /** Download size of this flavor's APK in bytes, or -1 when the release did not say. */
        val apkSize: Long = -1L,
        /** Release date as published, "2026-10-10", or "" when the release did not say. */
        val publishedAt: String = "",
    ) {
        /** True when the released build is newer than the installed one. */
        val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
    }

    // ── Session state the update UI observes ─────────────────────────────
    /**
     * Version the user dismissed with the pill this session. Unlike the old banner's Skip it is
     * not persisted: the pill comes back next launch, and Settings keeps offering the update.
     */
    @Volatile var dismissedThisSession: Int = 0

    /** Progress of the one update download that can run at a time; the UI renders it as a card. */
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val info: UpdateInfo, val bytes: Long, val total: Long, val bytesPerSec: Long) : DownloadState()
        data class Failed(val info: UpdateInfo, val reason: String) : DownloadState()
        /** Handed to the system installer; the card closes. */
        data class Installing(val info: UpdateInfo) : DownloadState()
    }
    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> get() = _download
    private val interrupt = AtomicBoolean()

    fun cancelDownload() {
        interrupt.set(true)
        _download.value = DownloadState.Idle
    }

    /** Clear a Failed or Installing card. */
    fun clearDownloadState() {
        if (_download.value !is DownloadState.Downloading) _download.value = DownloadState.Idle
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    // ── Preferences ──────────────────────────────────────────────────────
    fun isNotifyEnabled(ctx: Context) = prefs(ctx).getBoolean(PREF_NOTIFY, true)
    fun setNotifyEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(PREF_NOTIFY, enabled).apply()

    fun skippedVersionCode(ctx: Context) = prefs(ctx).getInt(PREF_SKIP, 0)
    fun skipVersion(ctx: Context, versionCode: Int) =
        prefs(ctx).edit().putInt(PREF_SKIP, versionCode).apply()

    fun isIncludePrereleases(ctx: Context) = prefs(ctx).getBoolean(PREF_INCLUDE_PRE, false)
    fun setIncludePrereleases(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(PREF_INCLUDE_PRE, enabled).apply()

    fun lastCheck(ctx: Context) = prefs(ctx).getLong(PREF_LAST_CHECK, 0L)

    /** Installed version string for display, e.g. "1.7". */
    fun installedVersionName() = BuildConfig.VERSION_NAME

    // ── Network check ────────────────────────────────────────────────────
    /**
     * Fetch the latest release metadata off the main thread. [onResult] is
     * invoked on a background thread (callers must marshal to the UI thread).
     * Automatic checks ([force] = false: launch, About, opening Settings) reuse
     * the cached answer for [CHECK_COOLDOWN_MS] after a successful check, so an
     * app opened twenty times a day asks GitHub once or twice. On network
     * failure, falls back to the last cached result so the pill still works offline.
     */
    fun check(ctx: Context, force: Boolean = false, onResult: (UpdateInfo?) -> Unit) {
        val pre = isIncludePrereleases(ctx)
        if (!force) {
            // Cooldown: the last successful check is fresh enough, answer from the cache without
            // touching the network. Only the Settings button and the prerelease toggle force.
            val age = System.currentTimeMillis() - lastCheck(ctx)
            if (age in 0 until CHECK_COOLDOWN_MS) {
                val cached = loadCached(ctx, pre)
                if (cached != null) {
                    onResult(cached)
                    return
                }
            }
        }
        if (pre) checkPre(ctx, onResult) else checkStable(ctx, onResult)
    }

    /** Stable channel: the raw manifest, else the release asset until the manifest branch exists. */
    private fun checkStable(ctx: Context, onResult: (UpdateInfo?) -> Unit) {
        HttpUtils.download(MANIFEST_URL) { raw ->
            val fromRaw = raw?.let { parseUpdateJson(it) { n -> assetUrl(n) } }
            if (fromRaw != null) {
                finish(ctx, raw, fromRaw, false, onResult)
                return@download
            }
            HttpUtils.download(UPDATE_JSON_URL) { body ->
                val info = body?.let { parseUpdateJson(it) { n -> assetUrl(n) } }
                finish(ctx, body, info, false, onResult)
            }
        }
    }

    /** Prerelease channel: the raw every-cut manifest, else the GitHub API walk. */
    private fun checkPre(ctx: Context, onResult: (UpdateInfo?) -> Unit) {
        HttpUtils.download(MANIFEST_PRE_URL) { raw ->
            // No name→URL fallback here: a prerelease's APK is not under releases/latest, so the
            // manifest has to carry absolute apkUrl entries (release.yml writes them).
            val fromRaw = raw?.let { parseUpdateJson(it) { null } }
            if (fromRaw != null && fromRaw.apkUrl != null) {
                finish(ctx, raw, fromRaw, true, onResult)
                return@download
            }
            checkViaApi(ctx, onResult)
        }
    }

    /**
     * Prerelease-aware path: list all releases (newest first, prereleases
     * included) via the GitHub API, take the newest one that carries an
     * update.json asset, and read it. APK URLs come from that release's own
     * assets, not releases/latest. Falls back to the stable path if the API is
     * unreachable or no release yet carries update.json.
     */
    private fun checkViaApi(ctx: Context, onResult: (UpdateInfo?) -> Unit) {
        HttpUtils.download(API_RELEASES_URL) { listBody ->
            val picked = listBody?.let { pickNewestWithUpdateJson(it) }
            if (picked == null) {
                checkStable(ctx, onResult)
                return@download
            }
            HttpUtils.download(picked.updateJsonUrl) { body ->
                val info = body?.let { uj -> parseUpdateJson(uj) { n -> picked.assets[n] } }
                finish(ctx, body, info, true, onResult)
            }
        }
    }

    private fun finish(
        ctx: Context, body: String?, info: UpdateInfo?, pre: Boolean, onResult: (UpdateInfo?) -> Unit,
    ) {
        if (info != null && body != null) {
            cache(ctx, body, pre)
            // Only a successful check starts the cooldown; a failed one retries next time.
            prefs(ctx).edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
            onResult(info)
        } else {
            onResult(loadCached(ctx, pre))
        }
    }

    private class PickedRelease(val updateJsonUrl: String, val assets: Map<String, String>)

    /**
     * Newest non-draft release (by published_at) that carries an update.json asset.
     *
     * The GitHub list-releases API does NOT return a pure newest-first order: it
     * pins the `make_latest` release to the top, then lists the rest by date. So
     * a freshly-cut stable would shadow a newer prerelease if we just took the
     * first entry. Sort by published_at descending ourselves before picking, so
     * the genuinely-newest release always wins. (ISO-8601 timestamps sort
     * lexicographically in chronological order.)
     */
    private fun pickNewestWithUpdateJson(listBody: String): PickedRelease? = try {
        val raw = org.json.JSONArray(listBody)
        val releases = ArrayList<JSONObject>(raw.length())
        for (k in 0 until raw.length()) releases.add(raw.getJSONObject(k))
        releases.sortWith(compareByDescending { it.optString("published_at", "") })
        val arr = org.json.JSONArray(releases)
        var result: PickedRelease? = null
        var i = 0
        while (i < arr.length() && result == null) {
            val rel = arr.getJSONObject(i)
            if (!rel.optBoolean("draft", false)) {
                val assetsArr = rel.optJSONArray("assets")
                if (assetsArr != null) {
                    val map = HashMap<String, String>()
                    var updateJsonUrl: String? = null
                    for (j in 0 until assetsArr.length()) {
                        val a = assetsArr.getJSONObject(j)
                        val name = a.optString("name")
                        val url = a.optString("browser_download_url")
                        if (name.isNotBlank() && url.isNotBlank()) {
                            map[name] = url
                            if (name == "update.json") updateJsonUrl = url
                        }
                    }
                    if (updateJsonUrl != null) result = PickedRelease(updateJsonUrl, map)
                }
            }
            i++
        }
        result
    } catch (_: Exception) {
        null
    }

    private fun parseUpdateJson(body: String, resolveApk: (String) -> String?): UpdateInfo? = try {
        val o = JSONObject(body)
        val apkName = o.optJSONObject("apk")
            ?.optString(BuildConfig.APPLICATION_ID, null)
            ?.takeIf { it.isNotBlank() }
        // Absolute URL when the manifest carries one (manifest-branch era); else resolve the name.
        val absoluteUrl = o.optJSONObject("apkUrl")
            ?.optString(BuildConfig.APPLICATION_ID, null)
            ?.takeIf { it.isNotBlank() }
        val highlights = ArrayList<Highlight>()
        o.optJSONArray("highlights")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 5)) {
                val h = arr.optJSONObject(i) ?: continue
                val title = h.optString("title", "").trim()
                val text = h.optString("text", "").trim()
                if (title.isNotEmpty() || text.isNotEmpty()) highlights.add(Highlight(title, text))
            }
        }
        UpdateInfo(
            versionCode = o.getInt("versionCode"),
            versionName = o.optString("versionName", ""),
            notes = o.optString("notes", ""),
            apkName = apkName,
            apkUrl = absoluteUrl ?: apkName?.let(resolveApk),
            highlights = highlights,
            apkSize = o.optJSONObject("size")?.optLong(BuildConfig.APPLICATION_ID, -1L) ?: -1L,
            publishedAt = o.optString("publishedAt", ""),
        )
    } catch (_: Exception) {
        null
    }

    private fun cacheFile(ctx: Context, pre: Boolean) = File(ctx.cacheDir, if (pre) CACHE_PRE_NAME else CACHE_NAME)

    private fun cache(ctx: Context, body: String, pre: Boolean) = try {
        cacheFile(ctx, pre).writeText(body)
    } catch (_: Exception) { }

    private fun loadCached(ctx: Context, pre: Boolean): UpdateInfo? = try {
        val f = cacheFile(ctx, pre)
        // Best-effort offline read; APK download needs network to re-resolve anyway. A cached
        // prerelease body without absolute URLs cannot resolve its APK (it is not under
        // releases/latest), so it comes back with apkUrl = null and the button falls back to
        // the releases page.
        if (f.isFile) parseUpdateJson(f.readText()) { n -> if (pre) null else assetUrl(n) } else null
    } catch (_: Exception) {
        null
    }

    // ── Install-permission (Android 8+) ──────────────────────────────────
    fun canInstallPackages(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName),
                )
            )
        }
    }

    // ── Download + install ───────────────────────────────────────────────
    /**
     * Downloads the flavor-matched APK and launches the package installer. Progress is published
     * on [download]; the app shell renders it as the update card. This deliberately does NOT use
     * the dialog-creating HttpUtils.download overload, so the first-launch setup screen can never
     * appear during an update. If the "install unknown apps" grant is missing, routes the user to
     * that settings screen first and returns without downloading.
     */
    fun downloadAndInstall(activity: Activity, info: UpdateInfo, onDone: (Boolean) -> Unit) {
        val url = info.apkUrl
        if (url == null) {
            AppUtils.showToast(activity, "No download available for this build")
            // Fall back to the release page so the user can grab it manually.
            openReleasesPage(activity)
            onDone(false)
            return
        }
        if (!canInstallPackages(activity)) {
            AppUtils.showToast(activity, "Allow installing apps from Bannerlator, then tap Update again")
            requestInstallPermission(activity)
            onDone(false)
            return
        }
        if (_download.value is DownloadState.Downloading) return  // one at a time; the card is already up
        val dir = File(activity.externalCacheDir, "update").apply { mkdirs() }
        // Prune any previously-downloaded installers before fetching the new one.
        // The installer filename embeds the version, so without this every update
        // ever applied would accumulate here (~560 MB each) and never be reclaimed
        // until the OS cache-clears — one user hit ~10 GB of stale APKs this way.
        pruneUpdateDir(dir)
        val apk = File(dir, info.apkName ?: "Bannerlator-update.apk")
        _download.value = DownloadState.Downloading(info, 0L, info.apkSize, 0L)
        val startedAt = System.currentTimeMillis()
        var lastAt = startedAt
        var lastBytes = 0L
        var rate = 0L
        HttpUtils.downloadToFile(url, apk, interrupt, { bytes, total ->
            val now = System.currentTimeMillis()
            val dt = now - lastAt
            if (dt >= 500) {
                val inst = (bytes - lastBytes) * 1000 / dt
                // Smooth the readout so it does not flicker with every burst.
                rate = if (rate == 0L) inst else (rate * 3 + inst) / 4
                lastAt = now; lastBytes = bytes
            }
            if (_download.value is DownloadState.Downloading) {
                _download.value = DownloadState.Downloading(info, bytes, if (total > 0) total else info.apkSize, rate)
            }
        }) { ok ->
            activity.runOnUiThread {
                when {
                    interrupt.get() -> _download.value = DownloadState.Idle
                    ok -> { _download.value = DownloadState.Installing(info); install(activity, apk) }
                    else -> _download.value = DownloadState.Failed(info, "The download did not finish. Check the connection and try again.")
                }
                onDone(ok && !interrupt.get())
            }
        }
    }

    /**
     * Clear the update cache at cold process start. Complements the download-time
     * prune: under normal use the folder is empty here, but this reclaims space
     * left by a build shipped BEFORE the download-time prune existed (whose own
     * update never cleaned up), and sweeps any partial APK from a download the OS
     * killed mid-flight. Safe against the bg/fg race by construction — this runs
     * only from [Application.onCreate], which fires on cold start, never on a
     * background→foreground bounce, so it can never collide with an in-session
     * download. Cheap even in the worst case: deletion is unlink, not proportional
     * to file size. Callers should invoke this off the main thread.
     */
    fun pruneUpdateCacheAtStartup(ctx: Context) {
        try {
            val dir = File(ctx.externalCacheDir, "update")
            if (dir.isDirectory) pruneUpdateDir(dir)
        } catch (_: Exception) { }
    }

    /**
     * Delete every file left in the update cache dir. Called just before a fresh
     * download so stale installers from earlier versions don't pile up — the app
     * loses control once the installer intent fires, so pruning up-front (rather
     * than after install) is the only reliable place to clean up.
     */
    private fun pruneUpdateDir(dir: File) {
        try {
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) { }
    }

    private fun install(activity: Activity, apk: File) {
        try {
            val authority = activity.packageName + FILE_PROVIDER_SUFFIX
            val uri = FileProvider.getUriForFile(activity, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            AppUtils.showToast(activity, "Could not open installer")
            openReleasesPage(activity)
        }
    }

    fun openReleasesPage(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)))
        } catch (_: Exception) { }
    }
}
