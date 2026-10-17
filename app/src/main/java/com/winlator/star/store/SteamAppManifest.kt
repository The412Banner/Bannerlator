package com.winlator.star.store

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The Steam-format bookkeeping that makes a steamapps library usable by every other Steam client:
 * `<steamapps>/appmanifest_<appid>.acf` for each game and a `<steamapps>/libraryfolders.vdf`.
 *
 * Only games installed into a `steamapps/common/<game>` folder ([SteamInstallPaths.steamappsOfInstallDir])
 * get a manifest; the app's own `imagefs/steam_games` tree and the SD card's `bannerlator/steam_games` tree
 * have no `steamapps` folder and are never touched here.
 *
 * The rules, because the folder is shared with other clients:
 *  - an existing manifest is MERGED, never replaced: unknown keys survive, and only `StateFlags`,
 *    `LastUpdated`, `SizeOnDisk`, `buildid` and the `InstalledDepots` entries this app really installed are
 *    changed — and only when the manifest names the same `installdir` folder;
 *  - an unreadable manifest is left exactly as it is;
 *  - an existing `libraryfolders.vdf` is never modified (a minimal one is written only when absent);
 *  - every write is a temp file renamed over the target;
 *  - nothing here throws: the download path must never fail because a manifest could not be written.
 */
object SteamAppManifest {
    private const val TAG = "SteamAppManifest"

    private const val MAX_ACF_BYTES = 4L * 1024 * 1024

    /** What an `appmanifest_<appid>.acf` says about one install. */
    data class AcfInfo(
        val appId: Int,
        val name: String,
        /** The folder under `steamapps/common` (a plain name, never a path). */
        val installDir: String,
        val stateFlags: Long,
        val buildId: Long,
        val sizeOnDisk: Long,
        /** `betakey` of the install, "public" when it carries none. */
        val branch: String,
    ) {
        /** StateFlags bit 4 (fully installed) set and bit 2048 (being uninstalled) clear. */
        val isInstalled: Boolean get() = (stateFlags and 4L) != 0L && (stateFlags and 2048L) == 0L
    }

    fun manifestFile(steamapps: File, appId: Int): File = File(steamapps, "appmanifest_$appId.acf")

    // ── Reading ──────────────────────────────────────────────────────────────────────────────

    /** The manifest's summary, or null when it is missing, unreadable or names no usable `installdir`. */
    fun readInfo(file: File): AcfInfo? {
        return try {
            val app = parseFile(file)?.child("AppState") ?: return null
            val installDir = app.str("installdir")?.trim().orEmpty()
            if (!isSafeFolderName(installDir)) return null
            val branch = (app.child("UserConfig")?.str("betakey") ?: app.child("MountedConfig")?.str("betakey"))
                ?.trim().orEmpty().ifEmpty { "public" }
            AcfInfo(
                appId = app.str("appid")?.trim()?.toIntOrNull() ?: 0,
                name = app.str("name").orEmpty(),
                installDir = installDir,
                stateFlags = app.str("StateFlags")?.trim()?.toLongOrNull() ?: 0L,
                buildId = app.str("buildid")?.trim()?.toLongOrNull() ?: 0L,
                sizeOnDisk = app.str("SizeOnDisk")?.trim()?.toLongOrNull() ?: 0L,
                branch = branch,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "readInfo(${file.absolutePath}) failed: ${t.message}")
            null
        }
    }

    /** The `installdir` an existing manifest for [appId] records in [steamapps], or null. */
    fun existingInstallDir(steamapps: File, appId: Int): String? {
        val f = manifestFile(steamapps, appId)
        return if (f.isFile) readInfo(f)?.installDir else null
    }

    // ── Folder naming ────────────────────────────────────────────────────────────────────────

    /**
     * The folder name for a fresh install of [appId] under `<steamapps>/common`, in order: the
     * `installdir` of a manifest another client already wrote there (build on it), Valve's PICS
     * `installdir` (what other clients look for), else [safeName] — the name the app has always used,
     * only when PICS has not been seen for this app yet.
     */
    @JvmStatic
    fun installFolderName(steamapps: File, appId: Int, safeName: String): String {
        existingInstallDir(steamapps, appId)?.let { return it }
        // The installdir values a sync just learned are written in batches: write any still pending first.
        try { SteamRepository.getInstance().flushPicsInstallDirs() } catch (_: Throwable) {}
        val pics = sanitizeFolderName(try { SteamPrefs.getPicsInstallDir(appId) } catch (_: Throwable) { "" })
        if (pics.isNotEmpty()) return pics
        Log.i(TAG, "no PICS installdir known for app $appId — using '$safeName'")
        return safeName
    }

    /** [name] with the characters no filesystem here accepts replaced by `_`; "" when nothing usable is left. */
    fun sanitizeFolderName(name: String): String {
        val s = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return if (s == "." || s == "..") "" else s
    }

    private fun isSafeFolderName(s: String): Boolean =
        s.isNotEmpty() && s != "." && s != ".." && '/' !in s && '\\' !in s && '\u0000' !in s

    // ── Library files ────────────────────────────────────────────────────────────────────────

    /**
     * Makes [steamapps] a Steam library folder: creates it and its `common/`, and writes a minimal valid
     * `libraryfolders.vdf` ONLY when none exists. `downloading/` and `temp/` are not created — this
     * app's downloader stages inside the game folder and uses neither. Returns false when `common/`
     * could not be made. Never throws.
     */
    @JvmStatic
    fun ensureLibrary(steamapps: File): Boolean {
        return try {
            val common = File(steamapps, SteamInstallPaths.COMMON)
            if (!common.isDirectory && !common.mkdirs()) return false
            val vdf = File(steamapps, "libraryfolders.vdf")
            if (!vdf.exists()) {
                val root = steamapps.parentFile ?: steamapps
                val ok = writeAtomic(vdf, libraryFoldersText(root.absolutePath))
                Log.i(TAG, "libraryfolders.vdf for ${root.absolutePath}: ${if (ok) "written" else "could not be written"}")
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "ensureLibrary(${steamapps.absolutePath}) failed: ${t.message}")
            false
        }
    }

    private fun libraryFoldersText(libraryRoot: String): String {
        val contentId = UUID.nameUUIDFromBytes(libraryRoot.toByteArray(Charsets.UTF_8)).mostSignificantBits and Long.MAX_VALUE
        val sb = StringBuilder()
        sb.append("\"libraryfolders\"\n{\n")
        sb.append("\t\"0\"\n\t{\n")
        sb.append("\t\t\"path\"\t\t\"").append(esc(libraryRoot)).append("\"\n")
        sb.append("\t\t\"label\"\t\t\"\"\n")
        sb.append("\t\t\"contentid\"\t\t\"").append(contentId).append("\"\n")
        sb.append("\t\t\"totalsize\"\t\t\"0\"\n")
        sb.append("\t\t\"apps\"\n\t\t{\n\t\t}\n")
        sb.append("\t}\n}\n")
        return sb.toString()
    }

    // ── Writing the install's manifest ───────────────────────────────────────────────────────

    /**
     * Records a finished install or update of [appId] in [installDir] — a `steamapps/common/<game>`
     * folder — as `appmanifest_<appid>.acf` beside it. A no-op for every other layout. A manifest that
     * already exists is merged (see the class doc); a new one is written in full.
     *
     * @param sizeBytes the install's size on disk; <= 0 falls back to the sum of the depot sizes
     * @param depots depotId -> manifest id this pass really installed; null derives it from the
     *   download journal in the game folder, else from the synced depot rows
     */
    @JvmStatic
    fun recordInstall(ctx: Context, appId: Int, installDir: File, sizeBytes: Long, depots: Map<Int, Long>?) {
        try {
            val steamapps = SteamInstallPaths.steamappsOfInstallDir(installDir) ?: return
            val db = SteamDatabase.getInstance(ctx)
            val name = db.getGame(appId)?.name?.takeIf { it.isNotBlank() } ?: installDir.name

            val rows = try { db.getDepotManifests(appId) } catch (_: Throwable) { emptyList() }
            val sizeOf = HashMap<Int, Long>()
            for (r in rows) sizeOf[r.depotId] = if (r.realSizeBytes > 0L) r.realSizeBytes else r.sizeBytes
            val installed: Map<Int, Long> = depots ?: run {
                val journal = BlDepotInstaller.journalInstalledManifests(installDir)
                if (journal.isNotEmpty()) journal
                else {
                    val excluded = try { SteamPrefs.getExcludedDlc(appId) } catch (_: Throwable) { emptySet() }
                    val picked = HashMap<Int, Long>()
                    for (r in rows) if (r.manifestId != 0L && r.depotId !in excluded) picked[r.depotId] = r.manifestId
                    picked
                }
            }

            val stamp = SteamGameUpdater.readInstalledStamp(installDir)
            val branch = stamp?.first ?: "public"
            val build = stamp?.second ?: 0L
            val size = if (sizeBytes > 0L) sizeBytes else installed.keys.sumOf { sizeOf[it] ?: 0L }
            val owner = try { SteamRepository.getInstance().steamId64 } catch (_: Throwable) { 0L }
            val now = (System.currentTimeMillis() / 1000L).toString()

            val file = manifestFile(steamapps, appId)
            val doc: Kv
            if (file.isFile) {
                val parsed = parseFile(file)
                val app = parsed?.child("AppState")
                if (parsed == null || app == null) {
                    Log.w(TAG, "${file.name} is not readable — left untouched")
                    return
                }
                val existingDir = app.str("installdir")?.trim().orEmpty()
                if (existingDir.isNotEmpty() && !existingDir.equals(installDir.name, ignoreCase = true)) {
                    Log.w(TAG, "${file.name} belongs to '$existingDir', not '${installDir.name}' — left untouched")
                    return
                }
                app.ensureLeaf("appid", appId.toString())
                app.ensureLeaf("Universe", "1")
                app.ensureLeaf("name", name)
                app.ensureLeaf("installdir", installDir.name)
                app.ensureLeaf("LastOwner", owner.toString())
                app.setLeaf("StateFlags", "4")
                app.setLeaf("LastUpdated", now)
                app.setLeaf("SizeOnDisk", size.toString())
                if (build > 0L) app.setLeaf("buildid", build.toString())
                // A finished install must LOOK finished: another client may have left "update pending" values
                // behind (or this app flagged it update-required before starting), so clear them.
                app.setLeaf("BytesToDownload", "0")
                app.setLeaf("BytesDownloaded", "0")
                app.setLeaf("UpdateResult", "0")
                app.setLeaf("TargetBuildID", if (build > 0L) build.toString() else "0")
                mergeDepots(app, installed, sizeOf)
                doc = parsed
            } else {
                val app = Kv("AppState")
                app.children = ArrayList()
                app.setLeaf("appid", appId.toString())
                app.setLeaf("Universe", "1")
                app.setLeaf("name", name)
                app.setLeaf("StateFlags", "4")
                app.setLeaf("installdir", installDir.name)
                app.setLeaf("LastUpdated", now)
                app.setLeaf("LastPlayed", "0")
                app.setLeaf("SizeOnDisk", size.toString())
                app.setLeaf("StagingSize", "0")
                // An unknown build is left out rather than written as 0 (a 0 would look like a stale build
                // to a real Steam client and could queue an update).
                if (build > 0L) app.setLeaf("buildid", build.toString())
                app.setLeaf("LastOwner", owner.toString())
                app.setLeaf("UpdateResult", "0")
                app.setLeaf("BytesToDownload", "0")
                app.setLeaf("BytesDownloaded", "0")
                app.setLeaf("BytesToStage", "0")
                app.setLeaf("BytesStaged", "0")
                app.setLeaf("TargetBuildID", "0")
                app.setLeaf("AutoUpdateBehavior", "0")
                app.setLeaf("AllowOtherDownloadsWhileRunning", "0")
                app.setLeaf("ScheduledAutoUpdate", "0")
                mergeDepots(app, installed, sizeOf)
                for (section in listOf("UserConfig", "MountedConfig")) {
                    val cfg = app.block(section)
                    cfg.setLeaf("language", "english")
                    if (branch != "public") cfg.setLeaf("betakey", branch)
                }
                doc = Kv("")
                doc.children = ArrayList()
                doc.children!!.add(app)
            }

            val ok = writeAtomic(file, serialize(doc))
            Log.i(TAG, "${file.name} for '${installDir.name}' (build $build, ${installed.size} depot(s)): " +
                if (ok) "written" else "could not be written")
        } catch (t: Throwable) {
            Log.w(TAG, "recordInstall($appId, ${installDir.absolutePath}) failed: ${t.message}")
        }
    }

    /**
     * Before this app writes into a game folder another client installed: flags that client's manifest as
     * "installed, update required" (StateFlags 6), so a cancelled or failed run can never leave a half-updated
     * folder under a manifest that still says "fully installed". [recordInstall] sets it back to 4 when the
     * install finishes. Only touches a manifest that names exactly this folder and is currently marked
     * installed; never creates one. Never throws.
     */
    @JvmStatic
    fun markUpdateRequired(installDir: File, appId: Int) {
        try {
            val steamapps = SteamInstallPaths.steamappsOfInstallDir(installDir) ?: return
            val file = manifestFile(steamapps, appId)
            if (!file.isFile) return
            val parsed = parseFile(file) ?: return
            val app = parsed.child("AppState") ?: return
            val dir = app.str("installdir")?.trim().orEmpty()
            if (!dir.equals(installDir.name, ignoreCase = true)) return
            val flags = app.str("StateFlags")?.trim()?.toLongOrNull() ?: return
            if ((flags and 4L) == 0L || (flags and 2L) != 0L) return   // not installed, or already flagged
            app.setLeaf("StateFlags", (flags or 2L).toString())
            val ok = writeAtomic(file, serialize(parsed))
            Log.i(TAG, "${file.name}: flagged update-required before building on '${installDir.name}' (${if (ok) "written" else "write failed"})")
        } catch (t: Throwable) {
            Log.w(TAG, "markUpdateRequired(${installDir.absolutePath}, $appId) failed: ${t.message}")
        }
    }

    private fun mergeDepots(app: Kv, installed: Map<Int, Long>, sizeOf: Map<Int, Long>) {
        val section = app.block("InstalledDepots")
        for ((depotId, manifestId) in installed) {
            val entry = section.block(depotId.toString())
            entry.setLeaf("manifest", manifestId.toString())
            val size = sizeOf[depotId] ?: 0L
            // A depot another client recorded keeps its own size when ours is unresolved.
            if (size > 0L || entry.child("size") == null) entry.setLeaf("size", size.toString())
        }
    }

    /**
     * Removes the manifest for [appId] that sits beside [installDir] — only when it names that exact
     * folder as its `installdir` (so a manifest for a different folder is never touched) and only for a
     * `steamapps/common/<game>` folder. Called when the game's folder is deleted. Never throws.
     */
    @JvmStatic
    fun removeFor(installDir: String?, appId: Int) {
        try {
            if (installDir.isNullOrBlank()) return
            val dir = File(installDir)
            val steamapps = SteamInstallPaths.steamappsOfInstallDir(dir) ?: return
            val file = manifestFile(steamapps, appId)
            if (!file.isFile) return
            val info = readInfo(file) ?: return
            if (info.installDir.equals(dir.name, ignoreCase = true) && file.delete()) {
                Log.i(TAG, "removed ${file.name} with '${dir.name}'")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "removeFor($installDir, $appId) failed: ${t.message}")
        }
    }

    /** After a game moved from [oldDir] to [newDir]: the new library gets its manifest, the old one loses its. */
    @JvmStatic
    fun relocate(ctx: Context, appId: Int, oldDir: File, newDir: File, sizeBytes: Long) {
        recordInstall(ctx, appId, newDir, sizeBytes, null)
        removeFor(oldDir.absolutePath, appId)
    }

    // ── Atomic write ─────────────────────────────────────────────────────────────────────────

    private fun writeAtomic(target: File, text: String): Boolean {
        val tmp = File(target.parentFile, target.name + ".bl_tmp")
        return try {
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(target)) {
                true
            } else {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "write of ${target.absolutePath} failed: ${t.message}")
            try { tmp.delete() } catch (_: Throwable) {}
            false
        }
    }

    // ── Valve KeyValues (text) ───────────────────────────────────────────────────────────────

    /** One node of a KeyValues tree: a leaf ([value] set) or a block ([children] set). */
    private class Kv(var key: String) {
        var value: String? = null
        var children: MutableList<Kv>? = null

        fun child(name: String): Kv? = children?.firstOrNull { it.key.equals(name, ignoreCase = true) }

        /** The value of the leaf [name], or null when absent or a block. */
        fun str(name: String): String? = child(name)?.takeIf { it.children == null }?.value

        /** Sets leaf [name], replacing a leaf or a block of that name, appending when absent. */
        fun setLeaf(name: String, v: String) {
            val list = children ?: ArrayList<Kv>().also { children = it }
            val existing = list.firstOrNull { it.key.equals(name, ignoreCase = true) }
            if (existing == null) {
                list.add(Kv(name).also { it.value = v })
            } else {
                existing.children = null
                existing.value = v
            }
        }

        /** Adds leaf [name] only when no node of that name exists. */
        fun ensureLeaf(name: String, v: String) {
            if (child(name) == null) setLeaf(name, v)
        }

        /** The block [name], created when absent (a leaf of that name becomes a block). */
        fun block(name: String): Kv {
            val list = children ?: ArrayList<Kv>().also { children = it }
            val existing = list.firstOrNull { it.key.equals(name, ignoreCase = true) }
            if (existing != null) {
                if (existing.children == null) {
                    existing.value = null
                    existing.children = ArrayList()
                }
                return existing
            }
            return Kv(name).also {
                it.children = ArrayList()
                list.add(it)
            }
        }
    }

    /** Parses a KeyValues file into a synthetic root whose children are the top-level nodes; null on any problem. */
    private fun parseFile(file: File): Kv? {
        if (!file.isFile || file.length() > MAX_ACF_BYTES) return null
        return parse(file.readText(Charsets.UTF_8))
    }

    private fun parse(text: String): Kv? {
        val root = Kv("")
        root.children = ArrayList()
        val p = KvParser(text)
        return if (p.parseBlock(root, false)) root else null
    }

    private class KvParser(private val s: String) {
        private var i = 0

        private fun skipWhitespaceAndComments() {
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace() || c == '\uFEFF') {
                    i++
                } else if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                    while (i < s.length && s[i] != '\n') i++
                } else {
                    return
                }
            }
        }

        /** A quoted string (with `\n \t \r \\ \"` escapes) or a bare word; null at end of input or on an open quote. */
        private fun readString(): String? {
            skipWhitespaceAndComments()
            if (i >= s.length) return null
            val sb = StringBuilder()
            if (s[i] == '"') {
                i++
                while (i < s.length) {
                    val c = s[i]
                    if (c == '"') {
                        i++
                        return sb.toString()
                    }
                    if (c == '\\' && i + 1 < s.length) {
                        when (val n = s[i + 1]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            '\\' -> sb.append('\\')
                            '"' -> sb.append('"')
                            else -> sb.append('\\').append(n)
                        }
                        i += 2
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                return null
            }
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace() || c == '{' || c == '}' || c == '"') break
                sb.append(c)
                i++
            }
            return if (sb.isEmpty()) null else sb.toString()
        }

        /** Reads nodes into [parent] until the matching `}` (when [expectClose]) or the end of input. */
        fun parseBlock(parent: Kv, expectClose: Boolean): Boolean {
            while (true) {
                skipWhitespaceAndComments()
                if (i >= s.length) return !expectClose
                val c = s[i]
                if (c == '}') {
                    if (!expectClose) return false
                    i++
                    return true
                }
                if (c == '{') return false
                val key = readString() ?: return false
                skipWhitespaceAndComments()
                if (i >= s.length) return false
                val node = Kv(key)
                if (s[i] == '{') {
                    i++
                    node.children = ArrayList()
                    if (!parseBlock(node, true)) return false
                } else {
                    if (s[i] == '}') return false
                    node.value = readString() ?: return false
                }
                parent.children!!.add(node)
            }
        }
    }

    private fun serialize(doc: Kv): String {
        val sb = StringBuilder()
        doc.children?.forEach { write(it, 0, sb) }
        return sb.toString()
    }

    private fun write(node: Kv, depth: Int, sb: StringBuilder) {
        val indent = "\t".repeat(depth)
        val kids = node.children
        if (kids != null) {
            sb.append(indent).append('"').append(esc(node.key)).append("\"\n")
            sb.append(indent).append("{\n")
            for (k in kids) write(k, depth + 1, sb)
            sb.append(indent).append("}\n")
        } else {
            sb.append(indent).append('"').append(esc(node.key)).append("\"\t\t\"")
                .append(esc(node.value.orEmpty())).append("\"\n")
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
