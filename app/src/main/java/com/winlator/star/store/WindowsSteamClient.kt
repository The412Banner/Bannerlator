package com.winlator.star.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.container.ShortcutOrder
import com.winlator.star.core.EnvVars
import com.winlator.star.core.FileUtils
import com.winlator.star.core.VdfParser
import com.winlator.star.core.WinePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Valve's Windows Steam client started from a shortcut whose target is `steam.exe`.
 *
 * What it needs that a game does not:
 *  - X11 ([isSteamExe] is what the launcher checks): the client's Chromium pages are drawn by one
 *    process into a window owned by another, which our Wayland driver cannot show yet, so the window
 *    stays black or white there. The launcher applies this only when the game has no display system
 *    of its own set.
 *  - On the 64-bit client, `PROTON_DISABLE_LSTEAMCLIENT=1`. Otherwise the layer's loader keeps stock
 *    Proton's rule that rewrites `steamclient64.dll`'s `tier0_s64.dll` / `vstdlib_s64.dll` imports to
 *    ntdll. ntdll has no `g_pMemAllocSteam`, so steamclient64 faults in its own startup, Wine unloads
 *    it and Steam says "There was a problem with your Steam installation". The rule only matches the
 *    64-bit names, so the 32-bit client needs nothing.
 *  - A Steam library on every storage the container maps (internal storage, SD card): Steam offers
 *    only library folders it has registered, never drives ([ensureStorageLibraries]).
 *  - The app's games listed as non-Steam games ([SteamShortcutsSync]).
 *
 * Nothing here throws.
 */
object WindowsSteamClient {
    private const val TAG = "WindowsSteamClient"

    const val ENV_DISABLE_LSTEAMCLIENT = "PROTON_DISABLE_LSTEAMCLIENT"
    /** "0" turns on Proton's other-process window rendering, off by default in our layers on Android. */
    const val ENV_OTHER_PROCESS_RENDERING = "WINE_DISABLE_VULKAN_OPWR"

    /** [execArgs] without Steam's `-cef-disable-gpu`, for sessions where the client draws on the GPU. */
    @JvmStatic
    fun withoutCefDisableGpu(execArgs: String?): String =
        execArgs.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() && !it.equals("-cef-disable-gpu", ignoreCase = true) }
            .joinToString(" ")
    const val ENV_LSTEAMCLIENT_OPT_IN = "WINE_LSTEAMCLIENT"

    private const val MACHINE_AMD64 = 0x8664
    private const val DEFAULT_LIBRARY = "SteamLibrary"

    /** storeSource of the shortcut "Add Windows Steam client x64" writes; its badge and its place first in the library key on it. */
    const val STORE_SOURCE = "windows_steam"
    const val SHORTCUT_NAME = "Windows Steam Client x64"
    const val BADGE_LABEL = "Windows Steam"

    /** Name of the client's folder among the app's Steam library folders (Steam store download and move targets). */
    const val LIBRARY_LABEL = "Windows Steam"

    /**
     * Shortcut extra: "1" = the session opens Big Picture once the client's desktop window is up (steam://open/bigpicture,
     * XServerDisplayActivity). Not -gamepadui: started that way the client never makes its desktop window, so leaving
     * Big Picture showed nothing.
     */
    const val EXTRA_BIG_PICTURE = "steamBigPicture"

    /**
     * Makes the client's folder [steamRoot] one of the app's Steam library folders, so the app's Steam store can
     * download and move games into its `steamapps/common`; their appmanifest files ([SteamAppManifest]) make Steam
     * list them as installed. Only for a folder on internal storage or an SD card. Returns a line for the launch
     * log, or null when nothing changed.
     */
    @JvmStatic
    fun registerLibrary(ctx: Context, steamRoot: File): String? {
        return try {
            if (WinePath.storageVolumeRootOf(steamRoot.absolutePath) == null) return null
            val steamapps = SteamInstallPaths.steamappsDirOf(steamRoot).absolutePath
            if (SteamLibraryLocations.libraries(ctx).any { SteamLibraryLocations.steamappsOf(it.path).absolutePath == steamapps }) {
                return null
            }
            when (val r = SteamLibraryLocations.add(ctx, steamRoot.absolutePath, LIBRARY_LABEL)) {
                is SteamLibraryLocations.AddResult.Added -> "Steam store library added: ${r.library.label} (${steamRoot.absolutePath})"
                is SteamLibraryLocations.AddResult.Rejected -> "Steam store library not added: ${r.reason}"
            }
        } catch (t: Throwable) {
            Log.w(TAG, "registerLibrary", t)
            null
        }
    }

    // Valve's update server. The steam_client_win64 manifest's steam_win64 package is the 64-bit steam.exe starter
    // (one file); it downloads the rest of the client on its first start.
    private const val CLIENT_HOST = "https://client-update.steamstatic.com/"
    private const val CLIENT_MANIFEST = "steam_client_win64"

    /** Start options of the shortcut that runs the 64-bit client (Pocket FIT Elite, 2026-11-09). */
    const val EXEC_ARGS = "-nocrashmonitor -noshaders -no-shared-textures -cef-single-process -cef-in-process-gpu " +
        "-cef-disable-sandbox -disable-winh264 -no-cef-sandbox -vrdisable -cef-disable-breakpad -cef-disable-gpu"

    /** True for the shortcut "Add Windows Steam client x64" writes. */
    @JvmStatic
    fun isWindowsSteamShortcut(s: Shortcut?): Boolean = s != null && s.getExtra("storeSource") == STORE_SOURCE

    /** The client's folder on the storage the user picks: `<volume>/bannerlator/windows_steam`. */
    const val STORAGE_SUBDIR = "bannerlator/windows_steam"

    /** Space the client takes once it has updated itself (1.1 GB on the Pocket FIT, 2026-11-10). */
    private const val CLIENT_BYTES = 1_200L * 1024 * 1024

    /**
     * A storage the client can go on. [dir] is the client's folder there; [hasClient] = a 64-bit steam.exe is already
     * in it (put there for another container), so adding it needs no download.
     */
    data class Place(val label: String, val dir: File, val freeBytes: Long, val removable: Boolean, val hasClient: Boolean) {
        val fits: Boolean get() = hasClient || freeBytes >= CLIENT_BYTES + SteamSdInstall.FREE_SPACE_MARGIN
    }

    /** Internal storage, then the SD card when one is in. Reads the disk: call off the main thread. */
    fun places(ctx: Context): List<Place> {
        val out = ArrayList<Place>()
        out.add(place("Internal storage", android.os.Environment.getExternalStorageDirectory(), removable = false))
        try {
            SteamSdInstall.detect(ctx)?.let { out.add(place(it.label, it.root, removable = true)) }
        } catch (t: Throwable) {
            Log.w(TAG, "SD card", t)
        }
        return out
    }

    private fun place(label: String, root: File, removable: Boolean): Place {
        val dir = File(root, STORAGE_SUBDIR)
        return Place(label, dir, SteamSdInstall.freeBytes(root), removable, peMachine(File(dir, "steam.exe")) == MACHINE_AMD64)
    }

    /** The container's Windows Steam shortcut, or null. */
    @JvmStatic
    fun findShortcut(container: Container): File? =
        container.desktopDir.listFiles { f -> f.isFile && f.name.endsWith(".desktop") }
            ?.firstOrNull { f -> try { isWindowsSteamShortcut(Shortcut(container, f)) } catch (t: Throwable) { false } }

    sealed class AddResult {
        data class Added(val shortcut: File, val downloaded: Boolean) : AddResult()
        data class AlreadyAdded(val shortcut: File) : AddResult()
        data class Failed(val message: String) : AddResult()
    }

    /**
     * Puts the 64-bit client into [dir] (a [Place.dir]) and writes [container]'s shortcut to it; the shortcut reaches
     * [dir] through a drive letter, mapped on the container when it has none for that storage. Downloads Valve's
     * steam.exe starter from Valve's update server (size and SHA-256 checked against Valve's manifest) unless a 64-bit
     * steam.exe is already there, so containers can share one client. A container that already has the shortcut gets
     * no second one. Blocking: call off the main thread. [progress] gets 0..100 while downloading.
     */
    fun addClient(ctx: Context, container: Container, dir: File, progress: (Int) -> Unit = {}): AddResult {
        try {
            findShortcut(container)?.let { return AddResult.AlreadyAdded(it) }
            val exe = File(dir, "steam.exe")
            var downloaded = false
            if (peMachine(exe) != MACHINE_AMD64) {
                downloadStarter(ctx, dir, progress)?.let { return AddResult.Failed(it) }
                if (peMachine(exe) != MACHINE_AMD64) return AddResult.Failed("The download is not the 64-bit steam.exe")
                downloaded = true
            }
            val file = writeShortcut(container, exe)
            registerLibrary(ctx, dir)?.let { Log.i(TAG, it) }
            try {
                coverArt(ctx)?.let { Shortcut(container, file).saveCustomCoverArt(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "cover art", t)
            }
            Log.i(TAG, "Windows Steam client added to ${container.name}: ${file.absolutePath} (downloaded=$downloaded)")
            return AddResult.Added(file, downloaded)
        } catch (e: WinePath.NoFreeDriveLetterException) {
            return AddResult.Failed("This container has no free drive letters left")
        } catch (t: Throwable) {
            Log.w(TAG, "addClient failed", t)
            return AddResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Valve's manifest → the steam_win64 package → steam.exe in [dir]. Returns an error message, or null when done. */
    private fun downloadStarter(ctx: Context, dir: File, progress: (Int) -> Unit): String? {
        val manifest = try { httpText(CLIENT_HOST + CLIENT_MANIFEST) } catch (t: Throwable) {
            return "Could not reach Steam's update server (${t.message ?: t.javaClass.simpleName})"
        }
        val pkg = VdfParser.parse(manifest).node("win64")?.node("steam_win64")
        val name = pkg?.string("file")
        val sha2 = pkg?.string("sha2")?.lowercase()
        val size = pkg?.string("size")?.toLongOrNull() ?: -1L
        if (name.isNullOrBlank() || sha2.isNullOrBlank() || name.contains('/') || name.contains("..")) {
            return "Steam's update list has no 64-bit client"
        }
        val zip = File(ctx.cacheDir, "steam_win64.zip.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val conn = open(CLIENT_HOST + name)
            try {
                if (conn.responseCode != 200) return "Steam's update server answered ${conn.responseCode}"
                val total = if (size > 0) size else conn.contentLengthLong
                var done = 0L
                var shown = -1
                conn.inputStream.use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != shown) { shown = pct; progress(pct) }
                            }
                        }
                    }
                }
                if (size > 0 && done != size) return "The download was cut short ($done of $size bytes)"
            } finally {
                conn.disconnect()
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != sha2) return "The download does not match Steam's checksum"
            Log.i(TAG, "$name: $size bytes, sha256 $got (= Valve's manifest)")

            if (!dir.isDirectory && !dir.mkdirs()) return "Could not create ${dir.absolutePath}"
            val start = firstEntryOffset(zip)
            if (start < 0) return "The download is not a zip file"
            val root = dir.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().also { it.channel.position(start) }).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val target = File(dir, entry.name)
                    if (!target.canonicalPath.startsWith(root)) continue
                    if (entry.isDirectory) { target.mkdirs(); continue }
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, target.name + ".bl-tmp")
                    tmp.outputStream().use { zin.copyTo(it) }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) { tmp.delete(); return "Could not write ${target.name}" }
                }
            }
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "download failed", t)
            return "Download failed (${t.message ?: t.javaClass.simpleName})"
        } finally {
            zip.delete()
        }
    }

    /**
     * Offset of the zip's first entry header, or -1. Valve's client packages start with 20 bytes of their own before
     * it, and ZipInputStream reads no entry at all unless the stream starts at a header.
     */
    private fun firstEntryOffset(zip: File): Long {
        val head = ByteArray(64 * 1024)
        var n = 0
        zip.inputStream().use { input ->
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r <= 0) break
                n += r
            }
        }
        for (i in 0..n - 4) {
            if (head[i] == 0x50.toByte() && head[i + 1] == 0x4B.toByte() && head[i + 2] == 0x03.toByte() && head[i + 3] == 0x04.toByte()) {
                return i.toLong()
            }
        }
        return -1
    }

    private fun writeShortcut(container: Container, exe: File): File {
        val desktopDir = container.desktopDir
        if (!desktopDir.isDirectory) desktopDir.mkdirs()
        val winPath = WinePath.resolveWindowsPath(container, exe.absolutePath)
        val file = File(desktopDir, "$SHORTCUT_NAME.desktop")
        val content = buildString {
            append("[Desktop Entry]\n")
            append("Name=").append(SHORTCUT_NAME).append("\n")
            append("Exec=wine ").append(WinePath.escapeForExec(winPath)).append("\n")
            append("Icon=").append(SHORTCUT_NAME).append("\n")
            append("Type=Application\n")
            append("StartupWMClass=explorer\n")
            append("\n")
            append("[Extra Data]\n")
            append("storeSource=").append(STORE_SOURCE).append("\n")
            // The exe itself, never one of the app's Steam modes.
            append("launchMode=Raw\n")
            append("execArgs=").append(EXEC_ARGS).append("\n")
            append(ShortcutOrder.addedAtLine(System.currentTimeMillis()))
        }
        file.writeText(content)
        return file
    }

    /** Steam's logo on Steam's dark blue, as a 600x900 cover. */
    private fun coverArt(ctx: Context): Bitmap? {
        val logo = BitmapFactory.decodeResource(ctx.resources, R.drawable.store_logo_steam) ?: return null
        val cover = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cover)
        canvas.drawColor(Color.rgb(0x1B, 0x28, 0x38))
        val w = 360
        val h = (logo.height * w.toFloat() / logo.width).toInt().coerceAtLeast(1)
        val left = (600 - w) / 2
        val top = (900 - h) / 2
        canvas.drawBitmap(logo, null, Rect(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        return cover
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Bannerlator")
    }

    private fun httpText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** True when [winPath] (a shortcut's Windows path) names `steam.exe`. */
    @JvmStatic
    fun isSteamExe(winPath: String?): Boolean {
        if (winPath.isNullOrBlank()) return false
        val name = winPath.trim().trim('"').replace('/', '\\').substringAfterLast('\\')
        return name.equals("steam.exe", ignoreCase = true)
    }

    /** The PE machine field of [file] (0x14c i386, 0x8664 x64), or -1 when it is not a readable PE file. */
    @JvmStatic
    fun peMachine(file: File): Int {
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 0x40) return -1
                if (raf.read() != 'M'.code || raf.read() != 'Z'.code) return -1
                raf.seek(0x3c)
                val peOffset = Integer.reverseBytes(raf.readInt()).toLong() and 0xffffffffL
                if (peOffset < 0x40 || peOffset + 6 > raf.length()) return -1
                raf.seek(peOffset)
                if (Integer.reverseBytes(raf.readInt()) != 0x00004550) return -1 // "PE\0\0"
                return java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xffff
            }
        } catch (t: Throwable) {
            return -1
        }
    }

    /** True when the `steam.exe` behind [winPath] in [container] is the 64-bit client. */
    @JvmStatic
    fun is64Bit(container: Container, winPath: String): Boolean {
        val file = try { WinePath.resolveAndroidPath(container, winPath) } catch (t: Throwable) { null } ?: return false
        return peMachine(file) == MACHINE_AMD64
    }

    /**
     * Everything a launch of the client at [winPath] needs before Wine starts: the environment switch for
     * a 64-bit client (put into [env] unless the user set it, or opted into lsteamclient, themselves) and
     * the per-storage libraries. Returns one line per decision, for the launch log.
     */
    @JvmStatic
    fun prepareLaunch(ctx: Context, container: Container, winPath: String, env: EnvVars, gpuDrawing: Boolean = false): List<String> {
        val notes = ArrayList<String>()
        try {
            // GPU drawing: Chromium's GPU process draws into windows of the browser process, which Proton's
            // other-process rendering copies over; the launcher leaves -cef-disable-gpu out of the arguments.
            if (gpuDrawing) {
                if (!env.has(ENV_OTHER_PROCESS_RENDERING)) env.put(ENV_OTHER_PROCESS_RENDERING, "0")
                notes.add("GPU drawing on: $ENV_OTHER_PROCESS_RENDERING=${env.get(ENV_OTHER_PROCESS_RENDERING)}, -cef-disable-gpu left out")
            } else {
                notes.add("GPU drawing off (only with the GL renderer on X11): Steam draws on the CPU")
            }
            if (is64Bit(container, winPath)) {
                when {
                    env.has(ENV_DISABLE_LSTEAMCLIENT) ->
                        notes.add("64-bit client; $ENV_DISABLE_LSTEAMCLIENT=${env.get(ENV_DISABLE_LSTEAMCLIENT)} set by the user, left as is")
                    env.has(ENV_LSTEAMCLIENT_OPT_IN) ->
                        notes.add("64-bit client; $ENV_LSTEAMCLIENT_OPT_IN set by the user, lsteamclient left on")
                    else -> {
                        env.put(ENV_DISABLE_LSTEAMCLIENT, "1")
                        notes.add("64-bit client: $ENV_DISABLE_LSTEAMCLIENT=1, so steamclient64.dll keeps Steam's own tier0_s64.dll")
                    }
                }
            } else {
                notes.add("32-bit client: no environment change")
            }
            val added = ensureStorageLibraries(ctx, container, winPath)
            notes.add(if (added.isEmpty()) "Steam libraries: every mapped storage already has one"
                      else "Steam libraries added: " + added.joinToString(", "))
            // The app's games as non-Steam games, written while the client is not running yet.
            val steamRoot = try { WinePath.resolveAndroidPath(container, winPath)?.parentFile } catch (t: Throwable) { null }
            if (steamRoot != null) {
                registerLibrary(ctx, steamRoot)?.let { notes.add(it) }
                // Store games as installed games, before the non-Steam list (which leaves those out).
                notes.add(SteamLibraryMirror.sync(ctx, container, steamRoot))
                notes.add(SteamShortcutsSync.sync(ctx, container, steamRoot))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "prepareLaunch failed", t)
            notes.add("Steam client preparation failed: ${t.message}")
        }
        return notes
    }

    /**
     * Registers one Steam library per storage volume the container maps, in the client's
     * `steamapps/libraryfolders.vdf` and its `config/` copy, for every volume that has none yet.
     *
     * A volume's library is the user's Bannerlator Steam library on it when one is reachable through the
     * container's drives (same `steamapps`, so both clients see the same games); otherwise
     * `<drive>:\SteamLibrary` on the drive that maps the volume closest to its root (internal storage is
     * often mapped twice, as its root and as its Download folder; one library is enough). Entries already
     * in the file are kept exactly as they are; nothing is removed. Returns the Windows paths it added.
     */
    @JvmStatic
    fun ensureStorageLibraries(ctx: Context, container: Container, steamExeWinPath: String): List<String> {
        try {
            val exe = WinePath.resolveAndroidPath(container, steamExeWinPath) ?: return emptyList()
            val steamRoot = exe.parentFile ?: return emptyList()
            val steamRootWin = steamExeWinPath.trim().trim('"').replace('/', '\\').substringBeforeLast('\\')
            val primary = File(steamRoot, "steamapps/libraryfolders.vdf")
            val copy = File(steamRoot, "config/libraryfolders.vdf")

            val doc = if (primary.isFile) VdfParser.parse(primary.readText()) else VdfParser.VdfNode()
            var folders = doc.node("libraryfolders")
            if (folders == null) {
                // A file Steam wrote but this parser does not understand is left alone.
                if (primary.isFile) return emptyList()
                // No list yet (the client has never run here): start it the way Steam does, with its own folder first.
                folders = VdfParser.VdfNode()
                folders.add("0", libraryEntry(steamRootWin, steamRoot))
                doc.add("libraryfolders", folders)
            }

            val covered = HashSet<String>()
            var nextKey = 0
            for ((key, node) in folders.childNodes()) {
                key.toIntOrNull()?.let { if (it >= nextKey) nextKey = it + 1 }
                val path = node.string("path") ?: continue
                volumeOfWinPath(container, path)?.let { covered.add(it) }
            }

            // The drive closest to each volume's root.
            val drives = HashMap<String, Pair<String, String>>() // volume -> (letter, mount)
            for (d in container.drivesIterator()) {
                val letter = d[0].uppercase()
                if (letter == "C" || letter == "Z") continue
                val mount = d[1].trimEnd('/')
                if (mount.isEmpty()) continue
                val volume = WinePath.storageVolumeRootOf(mount) ?: continue
                val best = drives[volume]
                if (best == null || mount.length < best.second.length) drives[volume] = letter to mount
            }

            val added = ArrayList<String>()
            for ((volume, drive) in drives) {
                if (volume in covered) continue
                val (hostDir, winDir) = userLibraryOn(ctx, container, volume)
                    ?: (File(drive.second, DEFAULT_LIBRARY) to "${drive.first}:\\$DEFAULT_LIBRARY")
                val common = File(hostDir, "steamapps/common")
                if (!common.isDirectory && !common.mkdirs()) {
                    Log.w(TAG, "could not create ${common.absolutePath}; no library for $volume")
                    continue
                }
                folders.add((nextKey++).toString(), libraryEntry(winDir, hostDir))
                covered.add(volume)
                added.add(winDir)
            }
            if (added.isEmpty() && primary.isFile) return emptyList()

            val text = StringBuilder().also { serialize(doc, it, 0) }.toString()
            if (!writeAtomic(primary, text)) return emptyList()
            writeAtomic(copy, text)
            Log.i(TAG, "libraryfolders.vdf in ${steamRoot.absolutePath}: added $added")
            return added
        } catch (t: Throwable) {
            Log.w(TAG, "ensureStorageLibraries failed", t)
            return emptyList()
        }
    }

    /**
     * Adds the library [winPath] (the folder [hostDir]) to the client's `steamapps/libraryfolders.vdf` and its `config/`
     * copy when it is not listed yet. True when it is listed afterwards.
     */
    @JvmStatic
    fun addLibraryFolder(steamRoot: File, winPath: String, hostDir: File): Boolean {
        try {
            val primary = File(steamRoot, "steamapps/libraryfolders.vdf")
            if (!primary.isFile) return false
            val doc = VdfParser.parse(primary.readText())
            val folders = doc.node("libraryfolders") ?: return false
            var nextKey = 0
            for ((key, node) in folders.childNodes()) {
                key.toIntOrNull()?.let { if (it >= nextKey) nextKey = it + 1 }
                if (node.string("path")?.trimEnd('\\').equals(winPath.trimEnd('\\'), ignoreCase = true)) return true
            }
            folders.add(nextKey.toString(), libraryEntry(winPath, hostDir))
            val text = StringBuilder().also { serialize(doc, it, 0) }.toString()
            if (!writeAtomic(primary, text)) return false
            writeAtomic(File(steamRoot, "config/libraryfolders.vdf"), text)
            Log.i(TAG, "libraryfolders.vdf in ${steamRoot.absolutePath}: added $winPath")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "addLibraryFolder $winPath", t)
            return false
        }
    }

    /** The user's Bannerlator Steam library on [volume], as (folder, Windows path) through an existing drive, or null. */
    private fun userLibraryOn(ctx: Context, container: Container, volume: String): Pair<File, String>? {
        val libraries = try { SteamLibraryLocations.libraries(ctx) } catch (t: Throwable) { emptyList() }
        for (lib in libraries) {
            val steamapps = SteamInstallPaths.steamappsDirOf(File(lib.path))
            val root = steamapps.parentFile ?: continue
            if (!steamapps.isDirectory) continue
            if (WinePath.storageVolumeRootOf(root.absolutePath) != volume) continue
            val win = windowsPathOf(container, root.absolutePath) ?: continue
            return root to win
        }
        return null
    }

    /** [hostPath] as a Windows path through the container's longest matching drive, without mapping a new one. */
    private fun windowsPathOf(container: Container, hostPath: String): String? {
        var best: Pair<String, String>? = null
        for (d in container.drivesIterator()) {
            val letter = d[0].uppercase()
            if (letter == "C" || letter == "Z") continue
            val mount = d[1].trimEnd('/')
            if (mount.isEmpty()) continue
            if (hostPath == mount || hostPath.startsWith("$mount/")) {
                if (best == null || mount.length > best.second.length) best = letter to mount
            }
        }
        val (letter, mount) = best ?: return null
        val rel = hostPath.removePrefix(mount).trimStart('/').replace('/', '\\')
        return if (rel.isEmpty()) "$letter:\\" else "$letter:\\$rel"
    }

    private fun volumeOfWinPath(container: Container, winPath: String): String? {
        val file = try { WinePath.resolveAndroidPath(container, winPath) } catch (t: Throwable) { null } ?: return null
        return WinePath.storageVolumeRootOf(file.absolutePath)
    }

    private fun libraryEntry(winPath: String, hostDir: File): VdfParser.VdfNode {
        // Same derivation as SteamAppManifest's library files, so a folder keeps one id.
        val contentId = UUID.nameUUIDFromBytes(hostDir.absolutePath.toByteArray(Charsets.UTF_8)).mostSignificantBits and Long.MAX_VALUE
        return VdfParser.VdfNode().apply {
            add("path", winPath)
            add("label", "")
            add("contentid", contentId.toString())
            add("totalsize", "0")
            add("update_clean_bytes_tally", "0")
            add("time_last_update_verified", "0")
            add("apps", VdfParser.VdfNode())
        }
    }

    /** Writes [node] in Steam's own layout: tab indents, `"key"\t\t"value"`, braces on their own lines. */
    private fun serialize(node: VdfParser.VdfNode, sb: StringBuilder, depth: Int) {
        val indent = "\t".repeat(depth)
        for ((key, value) in node.entries) {
            if (value is VdfParser.VdfNode) {
                sb.append(indent).append('"').append(esc(key)).append("\"\n")
                sb.append(indent).append("{\n")
                serialize(value, sb, depth + 1)
                sb.append(indent).append("}\n")
            } else {
                sb.append(indent).append('"').append(esc(key)).append("\"\t\t\"").append(esc(value as String)).append("\"\n")
            }
        }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun writeAtomic(target: File, text: String): Boolean {
        return try {
            val dir = target.parentFile ?: return false
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val tmp = File(dir, target.name + ".bl-tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                false
            } else true
        } catch (t: Throwable) {
            Log.w(TAG, "could not write ${target.absolutePath}: ${t.message}")
            false
        }
    }

    // ── Uninstall ─────────────────────────────────────────────────────────────────────────────

    /**
     * A removed Windows Steam shortcut whose client folder no other shortcut uses. [keepContainerData] = another
     * steam.exe shortcut in the same container still needs its sign-in data and service files.
     */
    data class UninstallOffer(
        val container: Container,
        val clientDir: File,
        val keepContainerData: Boolean,
        /** The removed shortcut's cover picture in the container's app_data/cover_arts, when no other shortcut uses it. */
        val coverArt: File? = null,
    )

    /** What "Uninstall" deletes for an [UninstallOffer], with its size. */
    data class UninstallPlan(
        val offer: UninstallOffer,
        /** "internal storage", "the SD card" or "<container>'s C: drive". */
        val where: String,
        /** Bytes of everything deleted. */
        val bytes: Long,
        /** Games installed in the client's own folder (deleted with it). */
        val games: Int,
        /** AppData\Local\Steam (sign-in data, web cache) and Common Files\Steam (Steam service) of the container. */
        val containerDirs: List<File>,
        /** SteamLibrary folders that hold no game. */
        val emptyLibraries: List<File>,
        /** Library folders outside the client that hold games: kept. */
        val keptLibraries: Int,
        /** Games the app's Steam store installed (or is downloading) in the client's folder: kept, with their appmanifest. */
        val keptGames: Int = 0,
        val keepDirs: Set<String> = emptySet(),
        val keepAppIds: Set<Int> = emptySet(),
    )

    private val offers = MutableStateFlow<List<UninstallOffer>>(emptyList())

    /** Removed Windows Steam shortcuts waiting for the "Also uninstall Steam?" question, oldest first. */
    val uninstallOffers: StateFlow<List<UninstallOffer>> = offers.asStateFlow()

    @JvmStatic
    fun offerUninstall(offer: UninstallOffer) {
        offers.update { list -> if (list.any { it.clientDir == offer.clientDir }) list else list + offer }
    }

    @JvmStatic
    fun dismissUninstall(offer: UninstallOffer) {
        offers.update { it - offer }
    }

    private val running = MutableStateFlow<Set<File>>(emptySet())

    /** Client folders being deleted right now. */
    val uninstalling: StateFlow<Set<File>> = running.asStateFlow()

    /**
     * [uninstall] on its own thread, so it finishes even when the screen that started it goes away. [onDone] gets
     * null or an error message, on the main thread, after the offer is dismissed.
     */
    fun uninstallAsync(ctx: Context, plan: UninstallPlan, onDone: (String?) -> Unit) {
        val dir = plan.offer.clientDir
        var started = false
        running.update { if (dir in it) it else { started = true; it + dir } }
        if (!started) return
        Thread({
            val error = uninstall(ctx, plan)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                running.update { it - dir }
                dismissUninstall(plan.offer)
                onDone(error)
            }
        }, "WindowsSteamUninstall").start()
    }

    /**
     * Call BEFORE [shortcut]'s file is deleted (its client folder is found through the shortcut and its container's
     * drives). Null unless [shortcut] is a Windows Steam shortcut whose folder is Steam's own and no other shortcut in
     * [all] (every container's) points into it.
     */
    @JvmStatic
    fun uninstallOffer(shortcut: Shortcut, all: List<Shortcut>): UninstallOffer? {
        try {
            if (!isWindowsSteamShortcut(shortcut)) return null
            val container = shortcut.container ?: return null
            val exe = WinePath.resolveAndroidPath(container, shortcut.path ?: return null) ?: return null
            val dir = exe.parentFile ?: return null
            if (!looksLikeClient(container, dir)) return null
            val dirPath = dir.canonicalPath
            var keepContainerData = false
            for (s in all) {
                if (s.file?.absolutePath == shortcut.file?.absolutePath) continue
                val c = s.container ?: continue
                val path = s.path ?: continue
                val target = try { WinePath.resolveAndroidPath(c, path)?.canonicalPath } catch (t: Throwable) { null } ?: continue
                if (target == dirPath || target.startsWith(dirPath + File.separator)) return null
                if (c.id == container.id && isSteamExe(path)) keepContainerData = true
            }
            val coverDir = runCatching { File(container.rootDir, "app_data/cover_arts").canonicalPath }.getOrNull()
            val cover = shortcut.customCoverArtPath?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?.takeIf { f -> coverDir != null && f.isFile && isWithin(f, coverDir) }
                ?.takeIf { f -> all.none { it.file?.absolutePath != shortcut.file?.absolutePath && it.customCoverArtPath == f.path } }
            return UninstallOffer(container, dir, keepContainerData, cover)
        } catch (t: Throwable) {
            Log.w(TAG, "uninstallOffer", t)
            return null
        }
    }

    /** Sizes up [offer]. Reads the disk and the app's Steam database: call off the main thread. */
    fun planUninstall(ctx: Context, offer: UninstallOffer): UninstallPlan {
        val dir = offer.clientDir
        val dirPath = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        val (keepDirs, keepAppIds) = storeGamesIn(ctx, dir, dirPath)
        val driveC = File(offer.container.rootDir, ".wine/drive_c")
        val containerDirs = if (offer.keepContainerData) emptyList() else
            listOf(File(driveC, "users/xuser/AppData/Local/Steam"), File(driveC, "Program Files (x86)/Common Files/Steam"),
                SteamLibraryMirror.libraryDir(offer.container))
                .filter { it.exists() && !isWithin(it, dirPath) }

        val emptyLibraries = ArrayList<File>()
        var kept = 0
        try {
            val vdf = File(dir, "steamapps/libraryfolders.vdf")
            val folders = if (vdf.isFile) VdfParser.parse(vdf.readText()).node("libraryfolders") else null
            for ((_, node) in folders?.childNodes().orEmpty()) {
                val lib = node.string("path")?.let { WinePath.resolveAndroidPath(offer.container, it) } ?: continue
                if (!lib.isDirectory || isWithin(lib, dirPath) || runCatching { lib.canonicalPath }.getOrNull() == dirPath) continue
                when {
                    countGames(lib) > 0 -> kept++
                    lib.name == DEFAULT_LIBRARY && holdsNoFiles(lib) -> emptyLibraries.add(lib)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "planUninstall libraries", t)
        }

        val where = when {
            isWithin(dir, runCatching { driveC.canonicalPath }.getOrDefault(driveC.absolutePath)) -> "${offer.container.name}'s C: drive"
            dirPath.startsWith("/storage/emulated/") -> "internal storage"
            else -> "the SD card"
        }
        val keptBytes = keepDirs.sumOf { FileUtils.totalSize(File(it)) }
        val bytes = FileUtils.totalSize(dir) - keptBytes + containerDirs.sumOf { FileUtils.totalSize(it) } + emptyLibraries.sumOf { FileUtils.totalSize(it) }
        val steamGames = countGames(dir) - keepAppIds.count { File(dir, "steamapps/appmanifest_$it.acf").isFile }
        return UninstallPlan(offer, where, bytes.coerceAtLeast(0L), steamGames.coerceAtLeast(0), containerDirs, emptyLibraries, kept,
            keepAppIds.size, keepDirs, keepAppIds)
    }

    /**
     * Deletes what [plan] lists (symbolic links are removed, never followed). Games of the app's Steam store in the
     * client's folder stay, with their appmanifest, and the folder stays one of the app's library folders for them;
     * without such games the folder goes entirely and is forgotten as a library folder. Null when done, else a message.
     */
    fun uninstall(ctx: Context, plan: UninstallPlan): String? {
        val failed = ArrayList<String>()
        val client = plan.offer.clientDir
        if (plan.keepDirs.isEmpty()) {
            try {
                if (client.exists() && !FileUtils.delete(client)) failed.add(client.absolutePath)
            } catch (t: Throwable) {
                Log.w(TAG, "uninstall ${client.absolutePath}", t)
                failed.add(client.absolutePath)
            }
            forgetLibrary(ctx, client)
        } else {
            deleteKeeping(client, plan.keepDirs, plan.keepAppIds, failed)
        }
        for (target in plan.containerDirs + plan.emptyLibraries + listOfNotNull(plan.offer.coverArt)) {
            try {
                if (target.exists() && !FileUtils.delete(target)) failed.add(target.absolutePath)
            } catch (t: Throwable) {
                Log.w(TAG, "uninstall ${target.absolutePath}", t)
                failed.add(target.absolutePath)
            }
        }
        Log.i(TAG, "Windows Steam uninstalled from ${plan.offer.clientDir.absolutePath} (${plan.bytes} bytes, failed: ${failed.size})")
        return if (failed.isEmpty()) null else "Could not delete ${failed.first()}"
    }

    /** Install folders (canonical) and app ids of the app's Steam store games installed in, or downloading into, [dir]. */
    private fun storeGamesIn(ctx: Context, dir: File, dirPath: String): Pair<Set<String>, Set<Int>> {
        val dirs = HashSet<String>()
        val ids = HashSet<Int>()
        try {
            val db = SteamDatabase.getInstance(ctx)
            val rows = db.installedGames.map { it.appId to it.installDir } + db.activeDownloads.map { it.appId to it.installDir }
            for ((appId, installDir) in rows) {
                if (installDir.isNullOrBlank()) continue
                val f = File(installDir)
                val canonical = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                if (canonical != dirPath && canonical.startsWith(dirPath + File.separator)) {
                    dirs.add(canonical)
                    ids.add(appId)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "storeGamesIn", t)
        }
        return dirs to ids
    }

    /**
     * Deletes the client in [dir] but keeps `steamapps` with the app's store games: their folders under `common`
     * ([keepDirs]), their appmanifest files ([keepAppIds]) and libraryfolders.vdf.
     */
    private fun deleteKeeping(dir: File, keepDirs: Set<String>, keepAppIds: Set<Int>, failed: MutableList<String>) {
        fun del(f: File) {
            try {
                if (f.exists() && !FileUtils.delete(f)) failed.add(f.absolutePath)
            } catch (t: Throwable) {
                Log.w(TAG, "uninstall ${f.absolutePath}", t)
                failed.add(f.absolutePath)
            }
        }
        for (child in dir.listFiles().orEmpty()) {
            if (child.name != "steamapps" || FileUtils.isSymlink(child)) { del(child); continue }
            for (s in child.listFiles().orEmpty()) {
                when {
                    s.name == "common" && !FileUtils.isSymlink(s) -> for (g in s.listFiles().orEmpty()) {
                        val p = runCatching { g.canonicalPath }.getOrDefault(g.absolutePath)
                        if (p !in keepDirs) del(g)
                    }
                    s.name.startsWith("appmanifest_") && s.name.endsWith(".acf") -> {
                        val id = s.name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull()
                        if (id == null || id !in keepAppIds) del(s)
                    }
                    s.name == "libraryfolders.vdf" -> Unit
                    else -> del(s)
                }
            }
        }
    }

    /** Removes the app's library-folder entry for the client in [steamRoot]; refused by it while store games are recorded there. */
    private fun forgetLibrary(ctx: Context, steamRoot: File) {
        try {
            val steamapps = SteamInstallPaths.steamappsDirOf(steamRoot).absolutePath
            SteamLibraryLocations.libraries(ctx)
                .filter { SteamLibraryLocations.steamappsOf(it.path).absolutePath == steamapps }
                .forEach { Log.i(TAG, "forget library ${it.label}: ${SteamLibraryLocations.remove(ctx, it.id)}") }
        } catch (t: Throwable) {
            Log.w(TAG, "forgetLibrary", t)
        }
    }

    /**
     * True when [dir] holds Steam's client and nothing else the user keeps: steam.exe plus Steam's own files (or the
     * folder this app puts it in), and never a storage root, a drive's root, a Download folder or the C: drive itself.
     */
    private fun looksLikeClient(container: Container, dir: File): Boolean {
        if (!File(dir, "steam.exe").isFile) return false
        val path = try { dir.canonicalPath } catch (t: Throwable) { return false }
        val volume = WinePath.storageVolumeRootOf(path)
        if (volume == path) return false
        val driveC = File(container.rootDir, ".wine/drive_c")
        val keep = arrayListOf(
            driveC, File(driveC, "Program Files"), File(driveC, "Program Files (x86)"),
            android.os.Environment.getExternalStorageDirectory(),
            File(android.os.Environment.getExternalStorageDirectory(), "Download"),
        )
        if (volume != null) keep.add(File(volume, "Download"))
        for (d in container.drivesIterator()) keep.add(File(d[1]))
        if (keep.any { runCatching { it.canonicalPath }.getOrNull() == path }) return false
        val steamOwn = listOf("steamclient64.dll", "steamclient.dll", "SteamUI.dll", "package")
        return steamOwn.any { File(dir, it).exists() } ||
            path.endsWith("/$STORAGE_SUBDIR") ||
            path == runCatching { File(driveC, "Program Files/Steam").canonicalPath }.getOrNull()
    }

    /** appmanifest files in [libraryRoot]/steamapps, without Steamworks Common Redistributables (228980). */
    private fun countGames(libraryRoot: File): Int =
        File(libraryRoot, "steamapps").listFiles { f ->
            f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") && f.name != "appmanifest_228980.acf"
        }?.size ?: 0

    /**
     * True when no file under [dir] has content besides what Steam puts in every library folder: libraryfolder.vdf,
     * and a copy of its steam.dll in the folder's root.
     */
    private fun holdsNoFiles(dir: File): Boolean {
        val stack = ArrayDeque<File>().apply { add(dir) }
        var seen = 0
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (f in d.listFiles().orEmpty()) {
                if (++seen > 10_000 || FileUtils.isSymlink(f)) return false
                if (f.isDirectory) stack.add(f)
                else if (f.length() > 0 && f.name != "libraryfolder.vdf" && f.name != "libraryfolders.vdf" &&
                    !(d == dir && f.name.equals("steam.dll", ignoreCase = true))) return false
            }
        }
        return true
    }

    private fun isWithin(file: File, dirPath: String): Boolean {
        val p = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return p == dirPath || p.startsWith(dirPath + File.separator)
    }
}
