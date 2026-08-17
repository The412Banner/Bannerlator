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
import org.json.JSONObject
import java.io.File

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
 *   "minSupported": 1,
 *   "apk": {
 *     "com.winlator.banner":    "Bannerlator-1.8-standard.apk",
 *     "com.ludashi.benchmark":  "Bannerlator-1.8-ludashi.apk",
 *     "com.tencent.ig":         "Bannerlator-1.8-pubg.apk"
 *   }
 * }
 */
object UpdateManager {
    private const val REPO = "The412Banner/Bannerlator"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val UPDATE_JSON_URL =
        "https://github.com/$REPO/releases/latest/download/update.json"
    private fun assetUrl(name: String) =
        "https://github.com/$REPO/releases/latest/download/$name"
    // Lists ALL releases newest-first, prereleases included (releases/latest skips them).
    private const val API_RELEASES_URL =
        "https://api.github.com/repos/$REPO/releases?per_page=30"

    // Reuse the FileProvider already declared in the manifest. The authority is keyed
    // to the per-flavor applicationId (${applicationId}.tileprovider) so the standard,
    // ludashi and pubg flavors don't collide on a single device — a fixed authority
    // caused INSTALL_FAILED_CONFLICTING_PROVIDER when two flavors were installed.
    private const val FILE_PROVIDER_SUFFIX = ".tileprovider"

    private const val PREF_NOTIFY = "update_notify_enabled"
    private const val PREF_SKIP = "update_skip_version"
    private const val PREF_LAST_CHECK = "update_last_check"
    private const val PREF_INCLUDE_PRE = "update_include_prereleases"
    private const val CACHE_NAME = "update_latest.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val apkName: String?,
        /** Resolved download URL for this flavor's APK on the matched release. */
        val apkUrl: String?,
    ) {
        /** True when the released build is newer than the installed one. */
        val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
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
     * Fetch the latest stable metadata off the main thread. [onResult] is
     * invoked on a background thread (callers must marshal to the UI thread).
     * On network failure, falls back to the last cached result so the banner
     * still works offline.
     */
    fun check(ctx: Context, onResult: (UpdateInfo?) -> Unit) {
        if (isIncludePrereleases(ctx)) checkViaApi(ctx, onResult)
        else checkStable(ctx, onResult)
    }

    /** Stable-only path: releases/latest only ever resolves to a non-prerelease. */
    private fun checkStable(ctx: Context, onResult: (UpdateInfo?) -> Unit) {
        HttpUtils.download(UPDATE_JSON_URL) { body ->
            val info = body?.let { parseUpdateJson(it) { n -> assetUrl(n) } }
            finish(ctx, body, info, onResult)
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
                finish(ctx, body, info, onResult)
            }
        }
    }

    private fun finish(
        ctx: Context, body: String?, info: UpdateInfo?, onResult: (UpdateInfo?) -> Unit,
    ) {
        if (info != null && body != null) {
            cache(ctx, body)
            prefs(ctx).edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
            onResult(info)
        } else {
            onResult(loadCached(ctx))
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
        UpdateInfo(
            versionCode = o.getInt("versionCode"),
            versionName = o.optString("versionName", ""),
            notes = o.optString("notes", ""),
            apkName = apkName,
            apkUrl = apkName?.let(resolveApk),
        )
    } catch (_: Exception) {
        null
    }

    private fun cache(ctx: Context, body: String) = try {
        File(ctx.cacheDir, CACHE_NAME).writeText(body)
    } catch (_: Exception) { }

    private fun loadCached(ctx: Context): UpdateInfo? = try {
        val f = File(ctx.cacheDir, CACHE_NAME)
        // Best-effort offline read; APK download needs network to re-resolve anyway.
        if (f.isFile) parseUpdateJson(f.readText()) { n -> assetUrl(n) } else null
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
     * Downloads the flavor-matched APK (shows the app's standard
     * [DownloadProgressDialog]) and launches the package installer.
     * If the "install unknown apps" grant is missing, routes the user to that
     * settings screen first and returns without downloading.
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
        val dir = File(activity.externalCacheDir, "update").apply { mkdirs() }
        val apk = File(dir, info.apkName ?: "Bannerlator-update.apk")
        HttpUtils.download(activity, url, apk) { ok ->
            if (ok) install(activity, apk) else AppUtils.showToast(activity, "Update download failed")
            onDone(ok)
        }
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
