package com.winlator.star.core

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Which Wine/Proton layers can run DirectAudio (the Wine→AAudio native audio driver, winedirectaudio.drv),
 * and keeping each layer on the newest driver. Decided from the layer's files, not a list of names:
 *  - the layer must be arm64ec (lib/wine/aarch64-unix + aarch64-windows), and
 *  - it either ships the driver itself, or the app bundles a build for its Wine audio interface:
 *    Wine 11 classic (any 11.0-x), Wine 11 system-thread (winepulse.so imports PsCreateSystemThread,
 *    Proton-CachyOS), or Wine 10 (10.0-4 / 10.0-34, the builds the Wine 10 set was made against).
 * A driver built for the wrong interface plays silence rather than failing, so the set is picked per layer.
 *
 * At launch [installDriver] puts the bundled build on the layer unless the layer's own driver is the same
 * version or newer. A layer driver's version is read from the marker this app writes beside it, or from the
 * hash table of every released build (assets/directaudio/known-builds.txt); an unrecognised build is kept,
 * since it cannot be shown to be older.
 */
object DirectAudioSupport {
    private const val TAG = "DirectAudio"
    private const val MARKER = ".directaudio_bundled"
    private val WINE10_BUILDS = listOf("10.0-4", "10.0-34")

    /** Shown where the DirectAudio option is greyed out. */
    const val UNSUPPORTED_NOTE = "This Proton layer doesn't include DirectAudio"

    private var app: Context? = null
    private var known: Map<String, String>? = null

    /** Called once from the Application so the checks can find layer folders from a layer name. */
    @JvmStatic
    fun init(context: Context) {
        app = context.applicationContext
    }

    /**
     * The install folder of a content-entry layer name ("Proton-11.0-9.1-arm64ec-14" →
     * files/contents/Proton/11.0-9.1-arm64ec-14), or null when the name is not an installed entry.
     */
    @JvmStatic
    fun layerDir(context: Context, wineVersionName: String?): File? {
        val dash = wineVersionName?.indexOf('-') ?: -1
        if (dash <= 0) return null
        val dir = File(context.filesDir, "contents/${wineVersionName!!.substring(0, dash)}/${wineVersionName.substring(dash + 1)}")
        return dir.takeIf { it.isDirectory }
    }

    private fun wineDir(layer: File) = File(layer, "lib/wine")

    private fun isArm64ec(layer: File) =
        File(wineDir(layer), "aarch64-unix").isDirectory && File(wineDir(layer), "aarch64-windows").isDirectory

    private fun shipsDriver(layer: File) =
        File(wineDir(layer), "aarch64-unix/winedirectaudio.so").isFile &&
            File(wineDir(layer), "aarch64-windows/winedirectaudio.drv").isFile

    /** The bundled asset set for this layer's Wine audio interface, or null when none fits. */
    @JvmStatic
    fun bundledSet(layer: File): String? {
        if (!isArm64ec(layer)) return null
        val name = layer.name
        return when {
            name.contains("11.0-") -> if (usesSystemThreads(layer)) "wine11-systhread" else "wine11"
            WINE10_BUILDS.any { name.contains(it) } -> "wine10"
            else -> null
        }
    }

    /** Upstream Wine's system-thread audio loop: winepulse.so imports PsCreateSystemThread. */
    private fun usesSystemThreads(layer: File): Boolean {
        val pulse = File(wineDir(layer), "aarch64-unix/winepulse.so")
        if (!pulse.isFile) return false
        return runCatching { String(pulse.readBytes(), Charsets.ISO_8859_1).contains("PsCreateSystemThread") }.getOrDefault(false)
    }

    /**
     * True when DirectAudio can run on the selected layer. A blank name, a layer that isn't installed, or one
     * that is not arm64ec is unsupported - the app default is PulseAudio, and this re-evaluates once a layer
     * is picked.
     */
    @JvmStatic
    fun isSupported(wineVersionName: String?): Boolean {
        val context = app ?: return false
        val layer = layerDir(context, wineVersionName) ?: return false
        if (!isArm64ec(layer)) return false
        return shipsDriver(layer) || bundledSet(layer) != null
    }

    /** Version of the bundled build for [set] (assets/directaudio/<set>/version.txt). */
    private fun bundledVersion(context: Context, set: String): String? =
        runCatching { context.assets.open("directaudio/$set/version.txt").bufferedReader().use { it.readText().trim() } }.getOrNull()

    /** Version of the driver on [layer]: this app's marker, else a released build recognised by hash. */
    private fun layerVersion(context: Context, layer: File): String? {
        val unixDir = File(wineDir(layer), "aarch64-unix")
        val so = File(unixDir, "winedirectaudio.so")
        if (!so.isFile) return null
        val marker = File(unixDir, MARKER)
        if (marker.isFile) {
            // "<set> <version>"; an older marker held only the version.
            runCatching { marker.readText().trim().substringAfterLast(' ') }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return knownBuilds(context)[sha256(so)]
    }

    private fun knownBuilds(context: Context): Map<String, String> = known ?: runCatching {
        context.assets.open("directaudio/known-builds.txt").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line -> line.trim().split(' ').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }
                .toMap()
        }
    }.getOrDefault(emptyMap()).also { known = it }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Compares dotted versions numerically ("1.3.10" > "1.3.2"); a missing part counts as 0. */
    @JvmStatic
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /**
     * Before a DirectAudio launch: puts the bundled driver set for [wineVersionName]'s layer in place unless the
     * layer already has the same version or newer, or has a build this app does not recognise. Copies all three
     * files (64-bit PE, 32-bit PE when the layer has i386-windows, unixlib) - the game's bitness picks the PE.
     * Best-effort: any failure leaves the layer's driver as it was.
     */
    @JvmStatic
    fun installDriver(context: Context, wineVersionName: String?) {
        try {
            val layer = layerDir(context, wineVersionName) ?: return
            val set = bundledSet(layer) ?: return
            val want = bundledVersion(context, set) ?: return
            val unixDir = File(wineDir(layer), "aarch64-unix")
            val marker = File(unixDir, MARKER)
            val tag = "$set $want"
            if (marker.isFile && runCatching { marker.readText().trim() }.getOrNull() == tag) return
            if (shipsDriver(layer)) {
                val have = layerVersion(context, layer)
                if (have == null) {
                    Log.i(TAG, "${layer.name}: keeping its own driver (build not recognised)")
                    return
                }
                if (compareVersions(have, want) > 0 || (compareVersions(have, want) == 0 && !marker.isFile)) {
                    Log.i(TAG, "${layer.name}: keeping its own driver v$have (bundled v$want)")
                    return
                }
            }
            val sdk = if (android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) >= 16384) "sdk35" else "sdk28"
            val src = "directaudio/$set/$sdk/"
            copyAsset(context, src + "winedirectaudio.so", File(unixDir, "winedirectaudio.so"))
            copyAsset(context, src + "winedirectaudio-aarch64.drv", File(wineDir(layer), "aarch64-windows/winedirectaudio.drv"))
            val win32Dir = File(wineDir(layer), "i386-windows")
            if (win32Dir.isDirectory) copyAsset(context, src + "winedirectaudio-i386.drv", File(win32Dir, "winedirectaudio.drv"))
            marker.writeText(tag)
            Log.i(TAG, "installed bundled driver v$want ($set/$sdk) -> ${layer.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "driver install failed (keeping existing)", t)
        }
    }

    /** Copies an APK asset over [dst] through a temporary file, readable and executable (Wine dlopens the .so). */
    private fun copyAsset(context: Context, asset: String, dst: File) {
        val tmp = File(dst.parentFile, dst.name + ".new")
        context.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        tmp.setReadable(true, false)
        tmp.setExecutable(true, false)
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            throw java.io.IOException("could not replace ${dst.path}")
        }
    }

    /**
     * The launch-env flag the DirectAudio driver's unixlib reads to open an AAudio INPUT (mic) stream,
     * exactly like the other BANNER_AUDIO_DIRECT_* knobs it reads at stream open. Default UNSET = no mic
     * (today's playback-only behaviour). The app never records — it only sets this flag; the driver owns
     * capture. Lives in the per-scope env string (container DEFAULT_ENV_VARS / shortcut envVars), so it
     * round-trips per scope and reaches the launch env through the same merge the cog keys use;
     * applyDirectAudioConfig never touches _MIC, so it survives to the guest untouched.
     */
    const val MIC_ENV_KEY = "BANNER_AUDIO_DIRECT_MIC"
    private const val MIC_ENV_ON = "$MIC_ENV_KEY=1"

    /** True when the per-scope env string carries the mic-enable flag. */
    @JvmStatic
    fun isMicEnabledInEnv(env: String?): Boolean =
        env != null && env.split(" ").any { it == MIC_ENV_ON }

    /**
     * Add or remove the mic-enable flag in a space-joined env string. ON appends BANNER_AUDIO_DIRECT_MIC=1;
     * OFF removes the key entirely (absent, never "=0") — matching how the flag is contracted with the
     * driver. Every other token is preserved.
     */
    @JvmStatic
    fun withMicEnabled(env: String?, enabled: Boolean): String {
        val kept = (env ?: "").split(" ").filter { it.isNotBlank() && !it.startsWith("$MIC_ENV_KEY=") }
        val out = if (enabled) kept + MIC_ENV_ON else kept
        return out.joinToString(" ")
    }
}
