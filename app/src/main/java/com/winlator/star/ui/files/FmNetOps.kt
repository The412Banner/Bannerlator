package com.winlator.star.ui.files

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.winlator.star.core.StringUtils
import com.winlator.star.netdrive.NetDrive
import com.winlator.star.netdrive.NetEngine
import com.winlator.star.netdrive.NetFs
import com.winlator.star.netdrive.NetHub
import com.winlator.star.netdrive.NetKind
import com.winlator.star.netdrive.NetScan
import com.winlator.star.netdrive.NetStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The file manager's network drives: the Network section on Home (set up the engine, saved drives, shares found
 * on this network, search again, add a drive), opening and browsing a drive, and copy / move / rename / delete /
 * new folder on it. Lives in [FmOps] (`ops.net`), so Classic and Lab share it; each draws the dialogs in its look.
 *
 * Copies between a drive and this device (or two drives) run as the visible operation (label, progress, Cancel),
 * through rclone; a folder copy skips files already there with the same size and date.
 */
@Stable
internal class FmNetOps(private val context: Context, private val scope: CoroutineScope, private val ops: FmOps) {
    /** Network items copied (copy only: they are never cut). Setting it clears the device clipboard and back. */
    var clip by mutableStateOf<List<FmItem.Net>>(emptyList())

    // ── Dialog state ──
    /** The add / edit / log-in form, started from this drive (a new id when adding). */
    var editReq by mutableStateOf<NetDrive?>(null)
    var editIsNew by mutableStateOf(true)
    var testing by mutableStateOf(false)
    var testError by mutableStateOf<String?>(null)
    var removeReq by mutableStateOf<NetDrive?>(null)
    var newFolderIn by mutableStateOf<FmLoc.Net?>(null)
    var renameTarget by mutableStateOf<FmItem.Net?>(null)
    var deleteTargets by mutableStateOf<List<FmItem.Net>>(emptyList())
    /** The "Add to Games" dialog: which game folder on which drive, and where it goes. */
    var addReq by mutableStateOf<AddReq?>(null)
    /** The "Streamed games" dialog (space used, limit, how each game is kept). */
    var streamsOpen by mutableStateOf(false)
    var removeStreamReq by mutableStateOf<NetStream.Game?>(null)

    fun copy(items: List<FmItem.Net>) {
        ops.clipboard = emptyList()
        clip = items
    }

    // ── Home: the Network section ──

    fun homeSection(): FmHomeSection {
        val tint = androidx.compose.ui.graphics.Color(0xFF4F8FE0)
        val tiles = buildList<FmHomeTile> {
            when (val st = NetEngine.state) {
                NetEngine.State.Installed -> {
                    NetHub.saved.forEach { d ->
                        val r = NetHub.reach[d.id]
                        add(FmHomeTile(
                            d.name, HomeIcons.network, tint, File("/"),
                            subtitle = when (r) {
                                null -> "Checking…"
                                false -> "Can't reach"
                                true -> "${d.kind.short} · ${d.host}" + (d.folder.trim('/').takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                            },
                            target = FmLoc.Net(d, ""),
                            enabled = r != false,
                            id = "net:" + d.id,
                            menu = { driveMenu(d) },
                        ))
                    }
                    NetHub.found.filter { NetHub.savedFor(it) == null }.forEach { f ->
                        val guest = guestDrive(f)
                        add(FmHomeTile(
                            f.name, HomeIcons.network, tint, File("/"),
                            subtitle = when (f.open) {
                                true -> "Open · " + f.shares.joinToString(", ").ifEmpty { "SMB" }
                                false -> "Needs login"
                                null -> "Checking…"
                            },
                            target = if (f.open == true) FmLoc.Net(guest, "") else null,
                            action = when (f.open) {
                                true -> null
                                false -> ({ login(f) })
                                null -> ({ ops.toast("Still checking ${f.name}…") })
                            },
                            id = "found:" + f.host,
                            menu = { foundMenu(f) },
                        ))
                    }
                    NetHub.copying?.let { c ->
                        add(FmHomeTile(
                            c.name, HomeIcons.networkSetup, tint, File("/"),
                            subtitle = "Adding to Games" + (c.progress?.let { " · ${(it * 100).toInt()} %" } ?: "…"),
                            action = { }, id = "net:copying",
                        ))
                    }
                    if (NetHub.streams.isNotEmpty()) add(FmHomeTile(
                        "Streamed games", HomeIcons.networkSetup, tint, File("/"),
                        subtitle = StringUtils.formatBytes(NetHub.streamsTotal) + " of " + limitLabel(NetStream.limit(context)),
                        action = { streamsOpen = true; NetHub.refreshStreams() },
                        id = "net:streams",
                    ))
                    add(FmHomeTile(
                        "Search again", HomeIcons.networkSearch, tint, File("/"),
                        subtitle = when {
                            NetHub.scanning -> "Searching…"
                            !NetHub.onLan -> "No Wi-Fi"
                            else -> NetHub.found.size.let { if (it == 1) "1 found" else "$it found" }
                        },
                        action = { NetHub.scan() },
                        id = "net:search",
                    ))
                    add(FmHomeTile("Add drive", HomeIcons.networkAdd, tint, File("/"), subtitle = "SMB · SFTP · WebDAV · FTP", action = { addDrive() }, id = "net:add"))
                }
                is NetEngine.State.Downloading -> add(FmHomeTile(
                    "Network drives", HomeIcons.networkSetup, tint, File("/"),
                    subtitle = "Downloading " + (if (st.total > 0) "${(st.bytes * 100 / st.total).toInt()} %" else StringUtils.formatBytes(st.bytes)),
                    action = { }, id = "net:setup",
                ))
                NetEngine.State.Verifying -> add(FmHomeTile("Network drives", HomeIcons.networkSetup, tint, File("/"), subtitle = "Checking the download…", action = { }, id = "net:setup"))
                is NetEngine.State.Failed -> add(FmHomeTile(
                    "Network drives", HomeIcons.networkSetup, tint, File("/"),
                    subtitle = st.reason + " · tap to try again", action = { NetEngine.install(context) }, id = "net:setup",
                ))
                else -> add(FmHomeTile(
                    "Network drives", HomeIcons.networkSetup, tint, File("/"),
                    subtitle = "Set up · ${StringUtils.formatBytes(NetEngine.GZ_SIZE)} download", action = { NetEngine.install(context) }, id = "net:setup",
                ))
            }
        }
        return FmHomeSection("Network", tiles)
    }

    private fun guestDrive(f: NetScan.Found) = NetDrive(id = "found:" + f.host, kind = NetKind.SMB, name = f.name, host = f.host)

    private fun driveMenu(d: NetDrive): List<FmMenuEntry> = listOf(
        FmMenuEntry("Edit…", Icons.Filled.Edit) { editReq = d; editIsNew = false; testError = null },
        FmMenuEntry("Check again", Icons.Filled.Refresh) { NetHub.refresh() },
        FmMenuEntry("Remove", Icons.Filled.Delete, danger = true) { removeReq = d },
    )

    private fun foundMenu(f: NetScan.Found): List<FmMenuEntry> = buildList {
        if (f.open == true) add(FmMenuEntry("Save to my drives", Icons.Filled.Save) {
            runCatching { NetHub.save(context, guestDrive(f).copy(id = NetDrive.newId())) }
                .onSuccess { ops.toast("Saved ${f.name}") }
                .onFailure { ops.toast(it.message ?: "Couldn't save the drive") }
        })
        add(FmMenuEntry("Log in…", Icons.AutoMirrored.Filled.Login) { login(f) })
    }

    fun addDrive() {
        editReq = NetDrive(id = NetDrive.newId(), kind = NetKind.SMB, name = "", host = "")
        editIsNew = true
        testError = null
    }

    private fun login(f: NetScan.Found) {
        editReq = NetDrive(id = NetDrive.newId(), kind = NetKind.SMB, name = f.name, host = f.host, folder = f.shares.singleOrNull().orEmpty())
        editIsNew = true
        testError = null
    }

    /** The form's Save: checks the drive answers (unless [anyway]), then saves it. */
    fun submit(d: NetDrive, anyway: Boolean) {
        val drive = d.copy(name = d.name.trim().ifBlank { d.folder.trim('/').substringAfterLast('/').ifBlank { d.host } }, host = d.host.trim())
        scope.launch {
            testing = true
            testError = null
            val err = if (anyway) null else NetFs.test(context, drive)
            testing = false
            if (err != null) { testError = err; return@launch }
            withContext(Dispatchers.IO) { runCatching { NetHub.save(context, drive) } }
                .onSuccess { editReq = null; ops.toast("Saved ${drive.name}") }
                .onFailure { testError = it.message ?: "Couldn't save the drive" }
        }
    }

    fun remove(d: NetDrive) {
        removeReq = null
        runCatching { NetHub.remove(context, d.id) }.onSuccess { ops.toast("Removed ${d.name}") }
    }

    // ── Items on a drive ──

    fun openItem(p: FmPane, item: FmItem.Net) {
        if (item.isDir) { p.open(FmLoc.Net(item.drive, item.path)); return }
        if (isRunnable(item.name)) { ops.toast("Copy it to this device to run it"); return }
        val cached = File(context.cacheDir, "netview/" + item.drive.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + "/" + item.path)
        ops.runOp("Opening ${item.name}…") {
            try {
                withContext(Dispatchers.IO) { cached.parentFile?.mkdirs() }
                NetFs.transfer(context, NetFs.Side.Remote(item.drive, item.path), NetFs.Side.Local(cached), isDir = false, move = false) { b, t ->
                    ops.opProgress = if (t > 0) (b.toFloat() / t).coerceIn(0f, 1f) else null
                }
                ops.openCached(p, cached, item.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ops.toast(e.message ?: "Couldn't open ${item.name}")
            }
        }
    }

    fun itemMenu(p: FmPane, item: FmItem.Net): List<FmMenuEntry> = buildList {
        add(FmMenuEntry("Open", if (item.isDir) Icons.Filled.FolderOpen else Icons.AutoMirrored.Filled.OpenInNew) { openItem(p, item) })
        add(FmMenuEntry("Select", Icons.Filled.CheckBox) { p.startSelect(item) })
        add(FmMenuEntry("Copy", Icons.Filled.ContentCopy) { copy(listOf(item)) })
        if (item.isDir || item.extension == "exe") add(FmMenuEntry("Add to Games…", Icons.Filled.AddToHomeScreen) { startAdd(item) })
        add(FmMenuEntry("Rename", Icons.Filled.Edit) { renameTarget = item })
        add(FmMenuEntry("Properties", Icons.Filled.Info) { ops.propsTarget = item })
        add(FmMenuEntry("Delete", Icons.Filled.Delete, danger = true) { deleteTargets = listOf(item) })
    }

    fun newMenuEntries(loc: FmLoc.Net): List<FmMenuEntry> = listOf(
        FmMenuEntry("New folder", Icons.Filled.FolderOpen) { newFolderIn = loc },
    )

    fun createFolder(loc: FmLoc.Net, name: String) = ops.runOp("Creating folder…") {
        runCatching { NetFs.mkdir(context, loc.drive, NetFs.join(loc.path, name.trim())) }
            .onFailure { if (it is CancellationException) throw it; ops.toast(it.message ?: "Couldn't create the folder") }
    }

    fun rename(item: FmItem.Net, newName: String) = ops.runOp("Renaming…") {
        runCatching { NetFs.rename(context, item.drive, item.path, item.isDir, newName.trim()) }
            .onFailure { if (it is CancellationException) throw it; ops.toast(it.message ?: "Couldn't rename ${item.name}") }
    }

    fun delete(items: List<FmItem.Net>) = ops.runOp("Deleting…") {
        var failed = 0
        var last: String? = null
        for ((i, x) in items.withIndex()) {
            ops.opLabel = "Deleting" + (if (items.size > 1) " ${i + 1}/${items.size}" else "") + " — " + x.name
            try {
                NetFs.delete(context, x.drive, x.path, x.isDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                last = e.message
            }
        }
        if (failed > 0) ops.toast(if (items.size == 1) (last ?: "Couldn't delete") else "$failed couldn't be deleted")
    }

    // ── Add to Games: copy a game folder from a drive to this device, then add its exe to Games ──

    /**
     * One "Add to Games" request. [levels] are the folders the game could be (the exe's folder and the ones above it,
     * nearest first; one entry when started on a folder); [level] is the one picked, [exeRel] the exe inside it when
     * the request started on an exe. [size] is measured after the dialog opens (null while measuring, -1 unknown).
     */
    inner class AddReq(val drive: NetDrive, val exePath: String?, val levels: List<String>, start: String) {
        var level by mutableStateOf(start)
        var size by mutableStateOf<Long?>(null)
        var target by mutableStateOf<File?>(null)
        var container by mutableStateOf<com.winlator.star.container.Container?>(null)
        /** Stream from the drive (test) instead of copying first. */
        var stream by mutableStateOf(false)
        /** What a streamed game keeps between sessions. */
        var streamMode by mutableStateOf(NetStream.Mode.KEEP)
        /** The game-folder levels list is open (it starts closed: one line with the picked folder). */
        var levelsOpen by mutableStateOf(false)
        /** The game folder's exes (path in it, size), best first; null while the folder is being listed. */
        var exes by mutableStateOf<List<Pair<String, Long>>?>(null)
        /** The exe Games will start, as a path in the game folder. */
        var exePick by mutableStateOf<String?>(null)
        /** The exe list is open (it starts closed: one line with the picked exe). */
        var exesOpen by mutableStateOf(false)
        val name: String get() = NetFs.nameOf(level).ifEmpty { drive.name }
        /** The exe the request started on, as a path in the game folder (null when it started on a folder or lies outside). */
        private val clickedRel: String? get() = exePath?.let { p ->
            if (level.isEmpty()) p else p.removePrefix(level.trimEnd('/') + "/").takeIf { it != p }
        }
        val exeRel: String? get() = exePick ?: clickedRel

        fun measure() {
            val at = level
            size = null
            exes = null
            exePick = null
            exesOpen = false
            scope.launch {
                val r = runCatching { NetFs.size(context, drive, at).first }.getOrDefault(-1L)
                if (level == at) size = r
            }
            scope.launch {
                val listed = runCatching { NetFs.listAll(context, drive, at) }.getOrDefault(emptyList())
                    .filter { !it.isDir && it.name.endsWith(".exe", true) }
                val sizes = listed.associate { it.path to it.size }
                val ranked = com.winlator.star.core.GameFolderScanner.rankListed(NetFs.nameOf(at).ifEmpty { drive.name }, listed.map { it.path to it.size })
                    .toMutableList()
                val clicked = clickedRel
                if (clicked != null && clicked !in ranked && clicked in sizes) ranked.add(0, clicked)
                if (level != at) return@launch
                exes = ranked.map { it to (sizes[it] ?: 0L) }
                // The exe the user started on, unless it is a launcher and the folder has a game exe ranked above it.
                fun launcher(rel: String) = com.winlator.star.core.GameIdentifier.isLauncherExeName(rel.substringAfterLast('/').substringBeforeLast('.'))
                val best = ranked.firstOrNull()
                exePick = when {
                    clicked != null && clicked in ranked && !(launcher(clicked) && best != null && best != clicked && !launcher(best)) -> clicked
                    else -> best ?: clicked
                }
            }
        }
    }

    /** Where a game folder can go: each volume's Winlator/Games (Internal first), with its free space. */
    fun addTargets(): List<Pair<String, File>> = buildList {
        add("Internal storage" to File("/storage/emulated/0/Winlator/Games"))
        runCatching { com.winlator.star.core.StorageRoots.list(context) }.getOrDefault(emptyList())
            .filter { it.removable && it.readable }
            .forEach { add(it.label.ifBlank { it.dir.name } to File(it.dir, "Winlator/Games")) }
    }

    /** Free space of the app's own storage (where streamed games keep what they read). */
    fun appFree(): Long = freeSpace(context.filesDir)

    private fun prefs() = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

    /** The folder the last copy went to, when it is still there; else Internal storage's Winlator/Games. */
    fun defaultTarget(): File {
        val targets = addTargets()
        val last = prefs().getString("fmNetAddTarget", null)?.let(::File)
        return when {
            last == null -> targets.first().second
            targets.any { it.second == last } -> last
            last.isDirectory -> last
            else -> targets.first().second
        }
    }

    fun freeSpace(dir: File): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return runCatching { d?.usableSpace ?: 0L }.getOrDefault(0L)
    }

    fun startAdd(item: FmItem.Net) {
        if (NetHub.copying != null) { ops.toast("A game is already being copied"); return }
        scope.launch {
            val req = if (item.isDir) {
                AddReq(item.drive, null, listOf(item.path), item.path)
            } else {
                // The game folder: the exe's own, or above it past build folders (Binaries/Win64), as the "+" importer finds it.
                var dir = NetFs.parentOf(item.path)
                repeat(3) { if (dir.isNotEmpty() && NetFs.nameOf(dir).lowercase() in com.winlator.star.core.GameIdentifier.BUILD_DIRS) dir = NetFs.parentOf(dir) }
                if (com.winlator.star.core.GameIdentifier.isUnrealShippingName(item.name.substringBeforeLast('.')) && dir.isNotEmpty()) {
                    val parent = NetFs.parentOf(dir)
                    val hasEngine = runCatching { NetFs.list(context, item.drive, parent).any { it.isDir && it.name.equals("Engine", true) } }.getOrDefault(false)
                    if (hasEngine) dir = parent
                }
                val levels = buildList {
                    var d = NetFs.parentOf(item.path)
                    while (d.isNotEmpty() && size < 6) { add(d); d = NetFs.parentOf(d) }
                }.ifEmpty { listOf("") }
                AddReq(item.drive, item.path, levels, if (dir.isEmpty() && levels.first().isNotEmpty()) levels.first() else dir)
            }
            req.target = defaultTarget()
            req.container = ops.containerList().firstOrNull()
            addReq = req
            req.measure()
        }
    }

    /** The dialog's Copy: copies the game folder into [AddReq.target], then adds its exe to Games in the chosen container. */
    fun confirmAdd(req: AddReq) {
        val container = req.container ?: run { ops.toast("No container yet: create one first"); return }
        if (req.stream) { confirmStream(req, container); return }
        val base = req.target ?: return
        addReq = null
        prefs().edit().putString("fmNetAddTarget", base.absolutePath).apply()
        val gameDir = File(base, req.name)
        val exeRel = req.exeRel
        val app = context.applicationContext
        val started = NetHub.startGameCopy(req.name) { c ->
            fun say(msg: String) = android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show() }
            try {
                c.label = "Copying ${req.name} to Games"
                withContext(Dispatchers.IO) { gameDir.mkdirs() }
                NetFs.transfer(app, NetFs.Side.Remote(req.drive, req.level), NetFs.Side.Local(gameDir), isDir = true, move = false) { b, t ->
                    c.progress = if (t > 0) (b.toFloat() / t).coerceIn(0f, 1f) else null
                }
                c.label = "Adding ${req.name} to Games"
                c.progress = null
                val added = withContext(Dispatchers.IO) {
                    val exe = exeRel?.let { File(gameDir, it) }?.takeIf { it.isFile }
                        ?: com.winlator.star.core.GameFolderScanner.gameIn(gameDir)?.exe
                        ?: return@withContext null
                    val id = runCatching { com.winlator.star.core.GameIdentifier.identify(exe) }.getOrNull()
                    val display = id?.let { com.winlator.star.core.GameIdentifier.displayName(it, exe, gameDir) } ?: req.name
                    runCatching { com.winlator.star.ui.screens.ExeShortcutImporter.addToShortcuts(app, container, exe, display, id?.appId).nameWithoutExtension }.getOrNull()
                }
                say(if (added != null) "Added \"$added\" to Games" else "Copied ${req.name}, but no game .exe was found in it")
            } catch (e: CancellationException) {
                say("Stopped copying ${req.name} (copy again to finish it)")
                throw e
            } catch (e: Exception) {
                say("Couldn't copy ${req.name}: ${e.message ?: "error"}")
            }
        }
        if (!started) ops.toast("A game is already being copied")
    }

    /**
     * The dialog's Add with Stream picked (test): builds the game's stand-in in app storage (folders and empty files
     * at their real sizes; exes, dlls and small files fetched whole), then adds its exe to Games. The game then fetches
     * the rest from the drive as it reads it (netdrive/NetStream.kt + libnetfs.so).
     */
    private fun confirmStream(req: AddReq, container: com.winlator.star.container.Container) {
        addReq = null
        val app = context.applicationContext
        var drive = req.drive
        if (drive.id.startsWith("found:")) {
            // A share found on the network but not saved: save it, the game needs it at every launch.
            val saved = drive.copy(id = NetDrive.newId())
            if (runCatching { NetHub.save(app, saved) }.isSuccess) drive = saved
        }
        val d = drive
        val dir = com.winlator.star.netdrive.NetStream.newDir(app, req.name)
        val exeRel = req.exeRel
        val started = NetHub.startGameCopy(req.name) { c ->
            fun say(msg: String) = android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show() }
            try {
                val mode = req.streamMode
                NetStream.create(app, d, req.level, dir, mode) { line, f ->
                    c.label = "${req.name}: $line"
                    c.progress = f
                }
                if (mode == NetStream.Mode.FULL) {
                    NetStream.games(app).firstOrNull { it.dir == dir.canonicalFile }?.let { g ->
                        NetStream.fetchAll(app, g) { done, total ->
                            c.label = "${req.name}: fetching all of it"
                            c.progress = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
                c.label = "Adding ${req.name} to Games"
                c.progress = null
                val added = withContext(Dispatchers.IO) {
                    if (!com.winlator.star.netdrive.NetStream.ensureDrive(app, container)) return@withContext null
                    val exe = exeRel?.let { File(dir, it) }?.takeIf { it.isFile }
                        ?: com.winlator.star.core.GameFolderScanner.gameIn(dir)?.exe
                        ?: return@withContext null
                    val id = runCatching { com.winlator.star.core.GameIdentifier.identify(exe) }.getOrNull()
                    val display = id?.let { com.winlator.star.core.GameIdentifier.displayName(it, exe, dir) } ?: req.name
                    runCatching { com.winlator.star.ui.screens.ExeShortcutImporter.addToShortcuts(app, container, exe, display, id?.appId).nameWithoutExtension }.getOrNull()
                }
                say(if (added != null) "Added \"$added\" to Games (streams from ${d.name})" else "Made ${req.name}'s stand-in, but no game .exe was found in it")
            } catch (e: CancellationException) {
                say("Stopped setting up ${req.name}")
                throw e
            } catch (e: Exception) {
                say("Couldn't set up ${req.name}: ${e.message ?: "error"}")
            }
        }
        if (!started) ops.toast("A game is already being copied")
    }

    // ── Streamed games: what they keep on the device ──

    val limitChoices: List<Long> = listOf(5L shl 30, 10L shl 30, 20L shl 30, 50L shl 30, 0L)
    fun limitLabel(bytes: Long): String = if (bytes <= 0) "no limit" else "${bytes shr 30} GB"

    private fun streamsJob(label: String, block: suspend () -> String?) {
        scope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { it.message ?: "$label failed" } }
            NetHub.refreshStreams()
            msg?.let(ops::toast)
        }
    }

    fun setLimit(bytes: Long) = streamsJob("Limit") {
        NetStream.setLimit(context, bytes)
        NetStream.enforceLimit(context)
        null
    }

    fun setStreamMode(game: NetStream.Game, mode: NetStream.Mode) {
        if (game.mode == mode) return
        NetStream.setMode(context, game, mode)
        when (mode) {
            // Nothing stays between sessions: empty it now (it isn't running while this page is open).
            NetStream.Mode.STREAM -> streamsJob("Emptying") { "${game.name}: ${StringUtils.formatBytes(NetStream.clear(context, game))} freed" }
            NetStream.Mode.FULL -> {
                val app = context.applicationContext
                val started = NetHub.startGameCopy(game.name) { c ->
                    try {
                        c.label = "${game.name}: fetching all of it"
                        NetStream.fetchAll(app, game) { done, total -> c.progress = (done.toFloat() / total).coerceIn(0f, 1f) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(app, "Couldn't fetch all of ${game.name}: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
                    } finally {
                        NetHub.refreshStreams()
                    }
                }
                if (!started) ops.toast("A game is already being copied")
            }
            NetStream.Mode.KEEP -> NetHub.refreshStreams()
        }
    }

    fun emptyStream(game: NetStream.Game) = streamsJob("Emptying") { "${game.name}: ${StringUtils.formatBytes(NetStream.clear(context, game))} freed" }

    fun freeUpStreams() = streamsJob("Free up space") { "${StringUtils.formatBytes(NetStream.freeUp(context))} freed" }

    fun removeStream(game: NetStream.Game) {
        removeStreamReq = null
        streamsJob("Remove") { NetStream.remove(context, game); "Removed ${game.name}" }
    }

    // ── Copying between drives and this device ──

    /** Whether the clipboard (device files or network items) can be pasted into [loc]. */
    fun canPasteInto(loc: FmLoc): Boolean =
        (ops.clipboard.isNotEmpty() || clip.isNotEmpty()) && (loc is FmLoc.Dir || loc is FmLoc.Net)

    /** What is on either clipboard, for the paste capsule ("Copy 3 items"), or null when both are empty. */
    fun clipSummary(): String? = when {
        ops.clipboard.isNotEmpty() -> (if (ops.clipCut) "Move " else "Copy ") + (if (ops.clipboard.size == 1) ops.clipboard[0].name else "${ops.clipboard.size} items")
        clip.isNotEmpty() -> "Copy " + (if (clip.size == 1) clip[0].name else "${clip.size} items")
        else -> null
    }

    fun clearClips() {
        ops.clipboard = emptyList()
        clip = emptyList()
    }

    fun pasteInto(loc: FmLoc) {
        when {
            ops.clipboard.isNotEmpty() && loc is FmLoc.Dir -> ops.paste(ops.clipboard, loc.dir, ops.clipCut)
            ops.clipboard.isNotEmpty() && loc is FmLoc.Net -> {
                val files = ops.clipboard
                val cut = ops.clipCut
                if (cut) ops.clipboard = emptyList()
                sendFiles(files, loc, cut)
            }
            clip.isNotEmpty() && (loc is FmLoc.Dir || loc is FmLoc.Net) -> sendItems(clip, loc)
        }
    }

    /** Device files to a network folder (a move deletes them here once copied). */
    fun sendFiles(files: List<File>, dest: FmLoc.Net, move: Boolean) =
        transfer(files.map { Src(NetFs.Side.Local(it), it.name, it.isDirectory) }, dest, move)

    /** Network items to a device folder or another network folder (always a copy). */
    fun sendItems(items: List<FmItem.Net>, dest: FmLoc) =
        transfer(items.map { Src(NetFs.Side.Remote(it.drive, it.path), it.name, it.isDir) }, dest, move = false)

    private class Src(val side: NetFs.Side, val name: String, val isDir: Boolean)

    private fun transfer(srcs: List<Src>, dest: FmLoc, move: Boolean) {
        if (srcs.isEmpty()) return
        val verb = if (move) "Moving" else "Copying"
        ops.runOp("$verb…") {
            val existing: Set<String> = try {
                when (dest) {
                    is FmLoc.Dir -> withContext(Dispatchers.IO) { dest.dir.list()?.toSet() ?: emptySet() }
                    is FmLoc.Net -> NetFs.list(context, dest.drive, dest.path).map { it.name }.toSet()
                    else -> return@runOp
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ops.toast(e.message ?: "Couldn't open the folder")
                return@runOp
            }
            val taken = existing.toMutableSet()
            var applyAll: ConflictChoice? = null
            var done = 0
            var failed = 0
            var skipped = 0
            var last: String? = null
            for ((i, s) in srcs.withIndex()) {
                if (dest is FmLoc.Net && s.side is NetFs.Side.Remote && s.side.drive.id == dest.drive.id &&
                    (dest.path == s.side.path || dest.path.startsWith(s.side.path.trimEnd('/') + "/"))) { failed++; continue }
                var name = s.name
                if (name in taken) {
                    val choice = applyAll ?: run {
                        val (c, all) = ops.askConflict(name, s.isDir, srcs.size > 1)
                        if (all && c != null) applyAll = c
                        c
                    }
                    when (choice) {
                        null, ConflictChoice.SKIP -> { skipped++; continue }
                        ConflictChoice.KEEP_BOTH -> name = uniqueName(taken, name)
                        ConflictChoice.OVERWRITE, ConflictChoice.MERGE -> Unit
                    }
                }
                taken += name
                ops.opLabel = buildString {
                    append(verb)
                    if (srcs.size > 1) append(" ${i + 1}/${srcs.size}")
                    append(" — ").append(s.name)
                }
                ops.opProgress = 0f
                val dst = when (dest) {
                    is FmLoc.Dir -> NetFs.Side.Local(File(dest.dir, name))
                    is FmLoc.Net -> NetFs.Side.Remote(dest.drive, NetFs.join(dest.path, name))
                    else -> continue
                }
                try {
                    NetFs.transfer(context, s.side, dst, s.isDir, move) { b, t ->
                        ops.opProgress = if (t > 0) (b.toFloat() / t).coerceIn(0f, 1f) else null
                    }
                    done++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    last = e.message
                }
            }
            val msg = when {
                failed > 0 && srcs.size == 1 -> last ?: "Couldn't copy ${srcs[0].name}"
                failed > 0 -> "$done done, $failed failed" + (last?.let { ": $it" } ?: "")
                skipped > 0 -> "$done done, $skipped skipped"
                srcs.size > 1 -> "$done items ${if (move) "moved" else "copied"}"
                else -> null
            }
            msg?.let(ops::toast)
        }
    }

    private fun uniqueName(taken: Set<String>, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val c = "$base ($i)$ext"
            if (c !in taken) return c
            i++
        }
    }
}

/**
 * Keeps the Network section fresh while [active] (a Home page shows it): the saved drives' reach is checked and,
 * when the last search is over a minute old, the network is searched again; also after [tick] (coming back to the
 * app, Refresh) and once the engine finishes installing.
 */
@Composable
internal fun FmNetWatcher(active: Boolean, tick: Int) {
    val context = LocalContext.current
    DisposableEffect(active) {
        if (active) NetHub.homeShown(context)
        onDispose { if (active) NetHub.homeHidden() }
    }
    val installed = NetEngine.state == NetEngine.State.Installed
    LaunchedEffect(active, tick, installed) { if (active) NetHub.refresh() }
}
