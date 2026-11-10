package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.core.EnvVars
import com.winlator.star.core.VdfParser
import com.winlator.star.core.WinePath
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

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
 *
 * Nothing here throws.
 */
object WindowsSteamClient {
    private const val TAG = "WindowsSteamClient"

    const val ENV_DISABLE_LSTEAMCLIENT = "PROTON_DISABLE_LSTEAMCLIENT"
    const val ENV_LSTEAMCLIENT_OPT_IN = "WINE_LSTEAMCLIENT"

    private const val MACHINE_AMD64 = 0x8664
    private const val DEFAULT_LIBRARY = "SteamLibrary"

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
    fun prepareLaunch(ctx: Context, container: Container, winPath: String, env: EnvVars): List<String> {
        val notes = ArrayList<String>()
        try {
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
}
