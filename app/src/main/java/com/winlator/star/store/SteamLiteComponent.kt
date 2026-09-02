package com.winlator.star.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.winlator.star.contents.Downloader
import com.winlator.star.core.TarCompressorUtils
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SteamLite — the Real-Steam (VAC) launch client, delivered download-on-demand
 * from the winlator-contents catalog, the SAME model as [GoldbergComponent]
 * (NOT bundled in the APK). Picked from the launch-method popup as the online /
 * VAC path; Goldberg remains the offline fallback.
 *
 * SteamLite is ONE global tool shared by every Steam game (not per-game, not
 * per-container): our headless agent (`steam.exe`) drives the genuine Valve
 * client in-Wine. Once downloaded, every Steam game's launch sees it installed
 * and the RealSteam branch stages it into the container prefix at launch.
 *
 * ── Catalog (steamlite.json) ─────────────────────────────────────────────────
 * Lives at the winlator-contents repo root, mirroring goldberg.json. Single
 * object (one global package, no array):
 *
 *   {
 *     "schemaVersion": 1,
 *     "category": "steam-client",
 *     "release": "steamlite-v1",
 *     "name": "SteamLite (Real Steam / VAC)",
 *     "version": 1,
 *     "url": "https://github.com/The412Banner/winlator-contents/releases/download/steamlite-v1/steamlite.tzst",
 *     "file_size": "18011869",          // bytes, as a string
 *     "file_checksum": "51E1BF9E...CA"  // UPPERCASE MD5 of the .tzst, verified after download
 *   }
 *
 * ── Package (.tzst) layout ───────────────────────────────────────────────────
 * ONE combined zstd tar whose ROOT holds the files directly (NO "steamlite/"
 * prefix — it extracts straight into the install dir below). Our GPL agent +
 * the matched genuine Valve client set:
 *
 *   steam.exe                         // our clean-room agent (= the marker)
 *   steamclient64.dll  steamclient.dll
 *   tier0_s64.dll  tier0_s.dll  vstdlib_s64.dll  vstdlib_s.dll
 *   Steam.dll  Steam2.dll
 *   CommonFilesSteam/steamservice.dll  steamservice.exe  service_*_versions.vdf
 *   NOTICE.md  SOURCE.txt  VALVE_COMPONENTS.txt  steamlite.version
 *
 * Install dir: {filesDir}/imagefs/opt/steamlite/ — the RealSteam launch branch
 * copies these into the container prefix's `Program Files (x86)\Steam\` (+
 * CommonFilesSteam → `Common Files\Steam\`). Installed check = the marker exists.
 */
object SteamLiteComponent {

    private const val TAG = "BH_STEAMLITE"

    const val CATALOG_URL =
        "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/steamlite.json"

    /** A file that only exists once the package is extracted — the install marker. */
    private const val MARKER_REL = "steam.exe"

    /**
     * Records the catalog [Catalog.version] of the currently-extracted copy so a
     * later catalog bump is seen by EXISTING installs (see [isOutdated]), not
     * just brand-new ones. Written into the install dir only after a confirmed
     * successful extract; absent for pre-versioning installs → treated as 0.
     */
    private const val VERSION_MARKER_REL = ".steamlite_version"

    /**
     * The lowest package version this build of the app fully works with: the agent channel (live
     * launch status, failure cards with Retry) and the in-game friends relay need the v4 agent. An
     * older package still launches — the agent is backwards compatible — those features just stay
     * off, so the launch popup and pre-flight offer "Update & Launch" rather than refusing.
     *
     * Deliberately NOT bumped for v5 (agent p3b): v5 only changes agent-internal timing (friends relay
     * armed after the game is running; WN_STEAM_VAC=0 shortens the never-spawned fallback window)
     * and adds optional fields/events the app tolerates when absent. A v4 package still launches
     * correctly — the catalog offers v5 as an ordinary "Update" — so no user is forced through a
     * download for a launch that already works.
     */
    const val MIN_AGENT_VERSION = 4

    /** Bound for a catalog fetch on the launch path — a slow CDN must never hold the popup. */
    const val UPDATE_CHECK_TIMEOUT_MS = 5_000L

    /** Delivered on the main thread as the download progresses (0..1). */
    fun interface ProgressCallback {
        fun onProgress(fraction: Float)
    }

    /** Delivered on the main thread when the download+extract finishes. */
    fun interface DoneCallback {
        fun onDone(success: Boolean, message: String)
    }

    /** One-package catalog entry parsed from steamlite.json. */
    data class Catalog(
        val name: String,
        val version: Int,
        val url: String,
        val fileSize: Long,
        val checksum: String, // UPPERCASE MD5, may be blank → no verification
    )

    /** Global install dir the RealSteam launch branch stages from. */
    fun installDir(context: Context): File = File(context.filesDir, "imagefs/opt/steamlite")

    /** The extracted agent binary (`steam.exe`) — also the install marker. */
    fun agentExe(context: Context): File = File(installDir(context), MARKER_REL)

    /** True once the package is downloaded + extracted. */
    fun isInstalled(context: Context): Boolean = agentExe(context).isFile

    /**
     * The catalog version of the currently-installed copy, or 0 when unknown —
     * absent marker (a pre-versioning install) or an unreadable one. Callers
     * treat 0 as out-of-date against any real catalog version (>= 1).
     */
    fun installedVersion(context: Context): Int {
        val marker = File(installDir(context), VERSION_MARKER_REL)
        if (!marker.isFile) return 0
        return runCatching { marker.readText().trim().toInt() }.getOrDefault(0)
    }

    /**
     * True when the catalog offers a newer build than what's on disk. Kept
     * SEPARATE from [isInstalled] (files present) so the launch check is
     * unaffected: an outdated copy is still "installed" (usable) until the user
     * re-downloads — this only drives the popup's "Update" affordance.
     */
    fun isOutdated(context: Context, catalogVersion: Int): Boolean =
        isInstalled(context) && catalogVersion > installedVersion(context)

    /** "v4" for a stamped install, "an older build" for a pre-versioning one (marker absent → 0). */
    fun versionLabel(version: Int): String = if (version > 0) "v$version" else "an older build"

    /**
     * What's installed vs what the catalog offers. [catalog] is null when the package isn't
     * installed or the fetch failed / timed out — callers treat that as "couldn't check" and
     * launch with what's on disk (never a block).
     */
    data class UpdateCheck(val installed: Int, val catalog: Catalog?) {
        /** The catalog was reached (an answer, even if "up to date"). */
        val checked: Boolean get() = catalog != null
        /** The catalog offers a newer package than the installed one. */
        val available: Boolean get() = catalog != null && catalog.version > installed
        /** Newer package available AND the installed one predates what this app relies on. */
        val required: Boolean get() = available && installed < MIN_AGENT_VERSION
        val latestVersion: Int get() = catalog?.version ?: installed
        /** Download size in whole MB for copy ("~18 MB"); 0 when the catalog didn't say. */
        val downloadMb: Long get() = ((catalog?.fileSize ?: 0L) + 512 * 1024) / (1024 * 1024)
    }

    /**
     * Compare the installed package with the catalog, waiting at most [timeoutMs] for the fetch
     * (the fetch itself keeps running on its own daemon thread; a late answer is dropped). Call
     * off the main thread.
     */
    fun checkUpdateBlocking(context: Context, timeoutMs: Long = UPDATE_CHECK_TIMEOUT_MS): UpdateCheck {
        val installed = installedVersion(context)
        if (!isInstalled(context)) return UpdateCheck(installed, null)
        val latch = CountDownLatch(1)
        var catalog: Catalog? = null
        Thread({
            try { catalog = loadCatalog() } catch (t: Throwable) { Log.w(TAG, "catalog check failed", t) }
            finally { latch.countDown() }
        }, "steamlite-catalog-check").apply { isDaemon = true }.start()
        val inTime = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return UpdateCheck(installed, if (inTime) catalog else null)
    }

    /** [checkUpdateBlocking] on a worker; result on the main thread. */
    fun checkUpdateAsync(
        context: Context,
        timeoutMs: Long = UPDATE_CHECK_TIMEOUT_MS,
        onResult: (UpdateCheck) -> Unit,
    ) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val r = checkUpdateBlocking(appContext, timeoutMs)
            main.post { onResult(r) }
        }, "steamlite-update-check").apply { isDaemon = true }.start()
    }

    /** Fetch + parse steamlite.json (network only). Null on failure. */
    fun loadCatalog(): Catalog? {
        val json = Downloader.downloadString(CATALOG_URL) ?: return null
        return parse(json)
    }

    /** Async catalog load; result on the main thread. */
    fun loadCatalogAsync(onResult: (Catalog?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val cat = runCatching { loadCatalog() }.getOrNull()
            main.post { onResult(cat) }
        }, "steamlite-catalog").start()
    }

    private fun parse(json: String): Catalog? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val url = o.optString("url").trim()
        if (url.isEmpty()) return null
        return Catalog(
            name = o.optString("name").ifBlank { "SteamLite (Real Steam / VAC)" },
            version = o.optInt("version", 1),
            url = url,
            fileSize = o.optString("file_size").toLongOrNull() ?: o.optLong("file_size", 0L),
            checksum = o.optString("file_checksum").trim().uppercase(),
        )
    }

    /**
     * Downloads steamlite.json → the .tzst, MD5-verifies, and extracts into the
     * install dir on a worker thread. Progress + result are posted to the main
     * thread. Mirrors [GoldbergComponent.downloadAsync] for the SteamLite tool.
     */
    fun downloadAsync(context: Context, progress: ProgressCallback, done: DoneCallback) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val (ok, msg) = try {
                downloadBlocking(appContext) { f -> main.post { progress.onProgress(f) } }
            } catch (e: Exception) {
                Log.e(TAG, "steamlite download failed", e)
                false to "Download failed: ${e.message}"
            }
            main.post { done.onDone(ok, msg) }
        }, "steamlite-download").start()
    }

    private fun downloadBlocking(context: Context, progress: (Float) -> Unit): Pair<Boolean, String> {
        val catalog = loadCatalog()
            ?: return false to "Couldn't reach the SteamLite catalog. Check your connection."

        val cacheDir = File(context.cacheDir, "steamlite_dl").apply { mkdirs() }
        val archive = File(cacheDir, "steamlite.tzst")
        try {
            if (!Downloader.downloadFile(catalog.url, archive) { f -> progress(f.coerceIn(0f, 1f)) }) {
                return false to "Download failed. Please try again."
            }
            // Verify the UPPERCASE MD5 from the catalog before trusting the archive (blank = skip).
            if (catalog.checksum.isNotBlank()) {
                val actual = md5Upper(archive)
                if (!actual.equals(catalog.checksum, ignoreCase = true)) {
                    Log.w(TAG, "checksum mismatch: expected ${catalog.checksum} got $actual")
                    return false to "Downloaded file was corrupt (checksum mismatch). Try again."
                }
            }
            val dest = installDir(context)
            // Replace any stale copy so a re-download is clean.
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
            // The tar root holds the files directly, so extract straight into the install dir.
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, dest)) {
                return false to "Couldn't unpack the SteamLite package."
            }
            if (!isInstalled(context)) {
                return false to "SteamLite package was missing expected files."
            }
            // Stamp the version we just installed AFTER the extract is confirmed
            // present, so a catalog bump later reads as outdated (see isOutdated).
            writeInstalledVersion(context, catalog.version)
            return true to "SteamLite installed."
        } finally {
            archive.delete()
        }
    }

    /** Best-effort write of the installed catalog version marker. Only called
     *  after a confirmed extract (isInstalled == true); a failure just leaves
     *  the copy looking like version 0 → offered as an update next time. */
    private fun writeInstalledVersion(context: Context, version: Int) {
        runCatching { File(installDir(context), VERSION_MARKER_REL).writeText(version.toString()) }
            .onFailure { Log.w(TAG, "couldn't write steamlite version marker", it) }
    }

    private fun md5Upper(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02X".format(it) }
    }
}
