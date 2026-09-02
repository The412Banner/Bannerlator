package com.winlator.star.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.winlator.star.contents.Downloader
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * App Steam — Valve's own `androidarm64` Steam client library set, delivered download-on-demand
 * from **Valve's client-update CDN only** (never bundled in the APK, never re-hosted), for the
 * `launchMode=AppSteam` path: the app's genuine Steam session host ([SteamHost], the
 * `bl-steam-host` executable) loads `libsteamclient.so` from here and serves the loopback
 * listeners the Wine game's Proton `lsteamclient` bridge talks to.
 *
 * ── Source (Valve, read-only, signed KeyValues manifest) ─────────────────────────────────
 *   https://client-update.steamstatic.com/steam_client_linuxarm64
 *     "version"  "1788291500"                 ← the Valve build (our version marker)
 *     "bins_androidarm64_linuxarm64" {
 *        "file" "bins_androidarm64_linuxarm64.zip.a0be…"   ← plain zip on the same host
 *        "size" "18018236"
 *        "sha2" "d8c1a969…"                   ← SHA-256 of that zip (mandatory check)
 *        "zipvz" …                            ← Valve VZip (LZMA) variant — NOT used
 *     }
 *   The zip holds androidarm64/{libsteamclient.so, steamservice.so, libtier0_s.so,
 *   libvstdlib_s.so, libsteamnetworkingsockets.so} — bionic ELFs.
 *
 * ── Install ──────────────────────────────────────────────────────────────────────────────
 *   {filesDir}/imagefs/usr/lib/steam-host/<the five .so> + `.valve_version` (manifest version).
 *   Installed check = libsteamclient.so present. Update check = manifest version compare
 *   (bounded, never blocks a launch). The download is ~18 MB; a corrupt or tampered file
 *   (sha2 mismatch) is discarded and never extracted.
 *
 * Mirrors [SteamLiteComponent]'s API shape (progress + done on the main thread) so the launch
 * popup / library screens treat it like the other on-demand components.
 */
object SteamHostComponent {

    private const val TAG = "BH_STEAMHOST"

    const val MANIFEST_URL = "https://client-update.steamstatic.com/steam_client_linuxarm64"
    private const val CDN_BASE = "https://client-update.steamstatic.com/"
    private const val PACKAGE_KEY = "bins_androidarm64_linuxarm64"
    private const val ZIP_PREFIX = "androidarm64/"

    /** The library the host dlopens — also the install marker. */
    private const val MARKER_REL = "libsteamclient.so"
    private const val VERSION_MARKER_REL = ".valve_version"

    /** The Valve builds whose private-interface slots the host has been verified against (client_iface.h). */
    val VERIFIED_BUILDS: Set<String> = setOf("1788291500")

    /** Every file the host needs; the extract refuses a package missing any of them. */
    private val REQUIRED = listOf(
        "libsteamclient.so", "steamservice.so", "libtier0_s.so", "libvstdlib_s.so", "libsteamnetworkingsockets.so",
    )

    const val UPDATE_CHECK_TIMEOUT_MS = 5_000L

    fun interface ProgressCallback { fun onProgress(fraction: Float) }
    fun interface DoneCallback { fun onDone(success: Boolean, message: String) }

    /** What the Valve manifest says about the Android package. */
    data class Manifest(
        val version: String,
        val file: String,
        val size: Long,
        val sha2: String,   // lowercase hex SHA-256
    ) {
        val url: String get() = CDN_BASE + file
        val downloadMb: Long get() = (size + 512 * 1024) / (1024 * 1024)
    }

    /** Installed vs manifest. [manifest] null = couldn't reach Valve (never a block). */
    data class UpdateCheck(val installed: String, val manifest: Manifest?) {
        val checked: Boolean get() = manifest != null
        val available: Boolean get() = manifest != null && manifest.version != installed && installed.isNotEmpty()
        /** The manifest build is one the host can drive; otherwise an update would be refused by the host. */
        val manifestVerified: Boolean get() = manifest != null && manifest.version in VERIFIED_BUILDS
        val installedVerified: Boolean get() = installed in VERIFIED_BUILDS
    }

    fun installDir(context: Context): File = File(context.filesDir, "imagefs/usr/lib/steam-host")
    fun libSteamClient(context: Context): File = File(installDir(context), MARKER_REL)
    fun isInstalled(context: Context): Boolean = libSteamClient(context).isFile

    /** The Valve manifest version of the extracted set, or "" when unknown. */
    fun installedVersion(context: Context): String {
        val marker = File(installDir(context), VERSION_MARKER_REL)
        if (!marker.isFile) return ""
        return runCatching { marker.readText().trim() }.getOrDefault("")
    }

    fun versionLabel(version: String): String = if (version.isNotEmpty()) "build $version" else "an unknown build"

    /** Fetch + parse the Valve manifest (network only). Null on failure or when the package is absent. */
    fun loadManifest(): Manifest? {
        val text = Downloader.downloadString(MANIFEST_URL) ?: return null
        return parseManifest(text)
    }

    /**
     * Minimal Valve KeyValues (text VDF) reader for the two things we need: the root's
     * `"version"` and the [PACKAGE_KEY] block's `file` / `size` / `sha2`. Tolerates the
     * `kvsign2` / `kvsignatures` blocks and `//` comments. Returns null when either is missing.
     */
    internal fun parseManifest(text: String): Manifest? {
        val root = KeyValues.parse(text) ?: return null
        val body = root.values.values.firstOrNull { it is KeyValues.Node } as? KeyValues.Node ?: return null
        val version = (body.values["version"] as? String)?.trim().orEmpty()
        val pkg = body.values[PACKAGE_KEY] as? KeyValues.Node ?: return null
        val file = (pkg.values["file"] as? String)?.trim().orEmpty()
        val size = (pkg.values["size"] as? String)?.trim()?.toLongOrNull() ?: 0L
        val sha2 = (pkg.values["sha2"] as? String)?.trim()?.lowercase().orEmpty()
        if (version.isEmpty() || file.isEmpty() || sha2.length != 64) return null
        // Defensive: the CDN file name must be a plain name on the same host (no path tricks).
        if (file.contains('/') || file.contains("..")) return null
        return Manifest(version, file, size, sha2)
    }

    /** Compare the installed set with Valve's manifest, waiting at most [timeoutMs]. Off the main thread. */
    fun checkUpdateBlocking(context: Context, timeoutMs: Long = UPDATE_CHECK_TIMEOUT_MS): UpdateCheck {
        val installed = installedVersion(context)
        if (!isInstalled(context)) return UpdateCheck(installed, null)
        val latch = CountDownLatch(1)
        var manifest: Manifest? = null
        Thread({
            try { manifest = loadManifest() } catch (t: Throwable) { Log.w(TAG, "manifest check failed", t) }
            finally { latch.countDown() }
        }, "steamhost-manifest-check").apply { isDaemon = true }.start()
        val inTime = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return UpdateCheck(installed, if (inTime) manifest else null)
    }

    fun checkUpdateAsync(context: Context, timeoutMs: Long = UPDATE_CHECK_TIMEOUT_MS, onResult: (UpdateCheck) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val r = checkUpdateBlocking(app, timeoutMs)
            main.post { onResult(r) }
        }, "steamhost-update-check").apply { isDaemon = true }.start()
    }

    /** Manifest → zip → sha2 verify → extract, on a worker; progress + result on the main thread. */
    fun downloadAsync(context: Context, progress: ProgressCallback, done: DoneCallback) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val (ok, msg) = try {
                downloadBlocking(app) { f -> main.post { progress.onProgress(f) } }
            } catch (e: Exception) {
                Log.e(TAG, "Valve client download failed", e)
                false to "Download failed: ${e.message}"
            }
            main.post { done.onDone(ok, msg) }
        }, "steamhost-download").start()
    }

    fun downloadBlocking(context: Context, progress: (Float) -> Unit): Pair<Boolean, String> {
        val manifest = loadManifest()
            ?: return false to "Couldn't read Valve's Steam client manifest. Check your connection."
        if (manifest.version !in VERIFIED_BUILDS) {
            // Valve moved on to a build the host hasn't been verified against. Refuse rather than
            // install something the host would then refuse to drive (private vtable slots drift).
            Log.w(TAG, "Valve manifest build ${manifest.version} is not in the verified set $VERIFIED_BUILDS")
            return false to "Valve published Steam client build ${manifest.version}, which this version of the app " +
                "hasn't been verified with yet. An app update is needed before App Steam can use it."
        }
        val cacheDir = File(context.cacheDir, "steamhost_dl").apply { mkdirs() }
        val zip = File(cacheDir, "bins_androidarm64.zip")
        try {
            Log.i(TAG, "downloading ${manifest.file} (${manifest.size} B, build ${manifest.version}) from Valve")
            if (!Downloader.downloadFile(manifest.url, zip) { f -> progress((f * 0.9f).coerceIn(0f, 0.9f)) }) {
                return false to "Download from Valve's CDN failed. Please try again."
            }
            if (manifest.size > 0 && zip.length() != manifest.size) {
                Log.w(TAG, "size mismatch: manifest ${manifest.size} got ${zip.length()}")
                return false to "Downloaded file was incomplete (${zip.length()} of ${manifest.size} bytes). Try again."
            }
            val actual = sha256Hex(zip)
            if (actual != manifest.sha2) {
                Log.w(TAG, "sha2 mismatch: manifest ${manifest.sha2} got $actual")
                return false to "Downloaded file failed Valve's checksum. Try again."
            }
            val dest = installDir(context)
            val staging = File(dest.parentFile, "steam-host.tmp")
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            val extracted = extractAndroidLibs(zip, staging)
            val missing = REQUIRED.filter { !File(staging, it).isFile }
            if (missing.isNotEmpty()) {
                Log.w(TAG, "package missing $missing (extracted $extracted)")
                staging.deleteRecursively()
                return false to "Valve's package was missing expected files (${missing.joinToString()})."
            }
            for (name in REQUIRED) File(staging, name).setReadable(true, false)
            if (dest.exists()) dest.deleteRecursively()
            if (!staging.renameTo(dest)) {
                staging.deleteRecursively()
                return false to "Couldn't move the Steam client into place."
            }
            File(dest, VERSION_MARKER_REL).writeText(manifest.version)
            progress(1f)
            Log.i(TAG, "installed Valve client build ${manifest.version} (${extracted.size} files) into ${dest.absolutePath}")
            return true to "Valve Steam client (build ${manifest.version}) installed."
        } finally {
            zip.delete()
        }
    }

    /** Extract only `androidarm64/<name>.so` entries, flattened, basename-only (zip-slip safe). */
    private fun extractAndroidLibs(zip: File, dest: File): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(FileInputStream(zip).buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name
                if (e.isDirectory || !name.startsWith(ZIP_PREFIX)) { zin.closeEntry(); continue }
                val base = name.substringAfterLast('/')
                if (base.isEmpty() || !base.endsWith(".so") || base.contains("..")) { zin.closeEntry(); continue }
                val target = File(dest, base)
                target.outputStream().buffered().use { o -> zin.copyTo(o, 256 * 1024) }
                out.add(base)
                zin.closeEntry()
            }
        }
        return out
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** One-line status for UI/logs. */
    fun describe(context: Context): String =
        if (!isInstalled(context)) "not downloaded"
        else "installed (${versionLabel(installedVersion(context))})"

    // ── Valve KeyValues text reader (only what the manifest needs) ───────────────────────────
    internal object KeyValues {
        /** A block: ordered name → String | Node. Duplicate keys keep the last. */
        class Node { val values = LinkedHashMap<String, Any>() }

        /** Parse a KeyValues document. Returns a synthetic root whose single child is the document's root block. */
        fun parse(text: String): Node? {
            val toks = tokenize(text)
            var i = 0
            fun parseBlock(node: Node) {
                while (i < toks.size) {
                    val t = toks[i]
                    if (t == "}") { i++; return }
                    val key = if (t == "{" ) { i++; continue } else { i++; t }
                    val next = toks.getOrNull(i) ?: return
                    if (next == "{") {
                        i++
                        val child = Node()
                        parseBlock(child)
                        node.values[stripQuotes(key)] = child
                    } else if (next == "}") {
                        node.values[stripQuotes(key)] = ""
                    } else {
                        i++
                        node.values[stripQuotes(key)] = stripQuotes(next)
                    }
                }
            }
            val root = Node()
            return try { parseBlock(root); root } catch (t: Throwable) { null }
        }

        private fun stripQuotes(s: String): String =
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

        /** Quoted strings (with \" escapes) kept quoted, braces, bare words; `//` comments dropped. */
        private fun tokenize(text: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && i + 1 < n && text[i + 1] == '/' -> { while (i < n && text[i] != '\n') i++ }
                    c == '{' || c == '}' -> { out.add(c.toString()); i++ }
                    c == '"' -> {
                        val sb = StringBuilder("\"")
                        i++
                        while (i < n && text[i] != '"') {
                            if (text[i] == '\\' && i + 1 < n) { sb.append(text[i + 1]); i += 2 } else { sb.append(text[i]); i++ }
                        }
                        i++ // closing quote
                        sb.append('"')
                        out.add(sb.toString())
                    }
                    else -> {
                        val s = i
                        while (i < n && !text[i].isWhitespace() && text[i] != '{' && text[i] != '}') i++
                        out.add(text.substring(s, i))
                    }
                }
            }
            return out
        }
    }

    /** For the Log Manager / diagnostics: the JSON facts of the install. */
    fun factsJson(context: Context): JSONObject = JSONObject().apply {
        put("installed", isInstalled(context))
        put("version", installedVersion(context))
        put("verified", installedVersion(context) in VERIFIED_BUILDS)
        put("dir", installDir(context).absolutePath)
    }
}
