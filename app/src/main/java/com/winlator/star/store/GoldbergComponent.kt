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

/**
 * The Goldberg (gbe_fork) Steam emulator, delivered download-on-demand from the
 * winlator-contents catalog — the SAME model as ReShade effects
 * (ReshadeCatalog/ReshadeDownloader), NOT bundled in the APK.
 *
 * Goldberg is ONE global tool shared by every game (not per-game, not
 * per-container), so this is a single package with a dir-exists "installed"
 * check. Once downloaded, every game's detail page sees it as installed and
 * offers the tier toggle with no re-download.
 *
 * ── Catalog (goldberg.json) ──────────────────────────────────────────────────
 * Lives at the winlator-contents repo root, mirroring reshade.json. Single
 * object (one global package, no array):
 *
 *   {
 *     "schemaVersion": 1,
 *     "category": "steam-emulator",
 *     "release": "goldberg-v1",
 *     "name": "Steam Emulator (Goldberg)",
 *     "version": 1,
 *     "url": "https://github.com/The412Banner/winlator-contents/releases/download/goldberg-v1/goldberg.tzst",
 *     "file_size": "1234567",          // bytes, as a string
 *     "file_checksum": "AB12...FF"      // UPPERCASE MD5 of the .tzst, verified after download
 *   }
 *
 * ── Package (.tzst) layout ───────────────────────────────────────────────────
 * ONE combined zstd tar whose ROOT holds the tier folders directly (NO
 * "goldberg/" prefix — it extracts straight into the install dir below):
 *
 *   regular/x64/steam_api64.dll
 *   regular/x86/steam_api.dll
 *   experimental/x64/steam_api64.dll
 *   experimental/x64/steamclient64.dll
 *   experimental/x86/steam_api.dll
 *   experimental/x86/steamclient.dll
 *   steamclient_experimental/ColdClientLoader.ini
 *   steamclient_experimental/steamclient_loader_x64.exe
 *   steamclient_experimental/steamclient_loader_x86.exe
 *   steamclient_experimental/steamclient64.dll
 *   steamclient_experimental/steamclient.dll
 *   steamclient_experimental/GameOverlayRenderer64.dll
 *   steamclient_experimental/GameOverlayRenderer.dll
 *
 * Install dir: {filesDir}/imagefs/opt/goldberg/ — so e.g. the patcher reads
 * {filesDir}/imagefs/opt/goldberg/regular/x64/steam_api64.dll. Installed check =
 * that marker file exists.
 */
object GoldbergComponent {

    private const val TAG = "BH_GOLDBERG"

    const val CATALOG_URL =
        "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/goldberg.json"

    /** A file that only exists once the package is extracted — the install marker. */
    private const val MARKER_REL = "regular/x64/steam_api64.dll"

    /**
     * Records the catalog [Catalog.version] of the currently-extracted copy so a
     * later catalog bump is seen by EXISTING installs (see [isOutdated]), not
     * just brand-new ones. Written into the install dir only after a confirmed
     * successful extract; absent for pre-versioning installs → treated as 0.
     */
    private const val VERSION_MARKER_REL = ".gbe_version"

    /** Delivered on the main thread as the download progresses (0..1). */
    fun interface ProgressCallback {
        fun onProgress(fraction: Float)
    }

    /** Delivered on the main thread when the download+extract finishes. */
    fun interface DoneCallback {
        fun onDone(success: Boolean, message: String)
    }

    /** One-package catalog entry parsed from goldberg.json. */
    data class Catalog(
        val name: String,
        val version: Int,
        val url: String,
        val fileSize: Long,
        val checksum: String, // UPPERCASE MD5, may be blank → no verification
    )

    /** Global install dir the patcher copies tier dlls out of. */
    fun installDir(context: Context): File = File(context.filesDir, "imagefs/opt/goldberg")

    /** True once the package is downloaded + extracted. */
    fun isInstalled(context: Context): Boolean =
        File(installDir(context), MARKER_REL).isFile

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
     * SEPARATE from [isInstalled] (files present) so the patcher's install check
     * is unaffected: an outdated copy is still "installed" (usable) until the
     * user re-downloads — this only drives the popup's "Update" affordance.
     */
    fun isOutdated(context: Context, catalogVersion: Int): Boolean =
        isInstalled(context) && catalogVersion > installedVersion(context)

    /** Fetch + parse goldberg.json (network only). Null on failure. */
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
        }, "goldberg-catalog").start()
    }

    private fun parse(json: String): Catalog? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val url = o.optString("url").trim()
        if (url.isEmpty()) return null
        return Catalog(
            name = o.optString("name").ifBlank { "Steam Emulator (Goldberg)" },
            version = o.optInt("version", 1),
            url = url,
            fileSize = o.optString("file_size").toLongOrNull() ?: o.optLong("file_size", 0L),
            checksum = o.optString("file_checksum").trim().uppercase(),
        )
    }

    /**
     * Downloads goldberg.json → the .tzst, MD5-verifies, and extracts into the
     * install dir on a worker thread. Progress + result are posted to the main
     * thread. Mirrors ReshadeDownloader.install but for the single global tool.
     */
    fun downloadAsync(context: Context, progress: ProgressCallback, done: DoneCallback) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val (ok, msg) = try {
                downloadBlocking(appContext) { f -> main.post { progress.onProgress(f) } }
            } catch (e: Exception) {
                Log.e(TAG, "goldberg download failed", e)
                false to "Download failed: ${e.message}"
            }
            main.post { done.onDone(ok, msg) }
        }, "goldberg-download").start()
    }

    private fun downloadBlocking(context: Context, progress: (Float) -> Unit): Pair<Boolean, String> {
        val catalog = loadCatalog()
            ?: return false to "Couldn't reach the Steam Emulator catalog. Check your connection."

        val cacheDir = File(context.cacheDir, "goldberg_dl").apply { mkdirs() }
        val archive = File(cacheDir, "goldberg.tzst")
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
            // The tar root holds the tier folders, so extract straight into the install dir.
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, dest)) {
                return false to "Couldn't unpack the Steam Emulator package."
            }
            if (!isInstalled(context)) {
                return false to "Steam Emulator package was missing expected files."
            }
            // Stamp the version we just installed AFTER the extract is confirmed
            // present, so a catalog bump later reads as outdated (see isOutdated).
            writeInstalledVersion(context, catalog.version)
            return true to "Steam Emulator installed. Pick a mode below."
        } finally {
            archive.delete()
        }
    }

    /** Best-effort write of the installed catalog version marker. Only called
     *  after a confirmed extract (isInstalled == true); a failure just leaves
     *  the copy looking like version 0 → offered as an update next time. */
    private fun writeInstalledVersion(context: Context, version: Int) {
        runCatching { File(installDir(context), VERSION_MARKER_REL).writeText(version.toString()) }
            .onFailure { Log.w(TAG, "couldn't write goldberg version marker", it) }
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
