package com.winlator.star.core

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.util.Log
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.components.ComponentCatalog
import com.winlator.star.components.ComponentExecInstaller
import com.winlator.star.components.ComponentInstaller
import com.winlator.star.components.ComponentUndo
import com.winlator.star.components.GameRecommendations
import com.winlator.star.components.PrefixInstalledDetector
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerLayerUpdater
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentsManager
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.store.UbisoftSupport
import com.winlator.star.store.xbox.XboxCloudShortcuts
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale

/**
 * Moves a Wine game's entry to another container, or copies it there ([keepCopy]). What always
 * travels: the .desktop with all its per-game settings, its icon PNGs and its cover art. The exe is
 * re-pointed through the target's drive map (a drive letter is added to the target when none reaches
 * the game's folder); Z: is the same for every container. A pinned home-screen icon follows a move.
 * The original is deleted only after the new entry is written and loads.
 *
 * What the user can tick on top ([Options], offered by [plan]): the game's saves (backed up from the
 * source prefix and restored into the target), its folder when it is installed on the source's own C:
 * drive (moved by rename, or copied when keeping a copy; same C:\ path, so Exec stays), and the
 * extras it needs that the target lacks (copied from the source's install journal when both
 * containers are the same architecture, else installed from the catalog).
 *
 * Blocking file and network work: call off the main thread.
 */
object MoveGameToContainer {
    private const val TAG = "MoveGameToContainer"
    /** SaveLocator score for the same name or one name inside the other (token overlap is 50). */
    private const val SAVE_NAME_MATCH = 70
    private val ICON_SIZES = intArrayOf(64, 48, 32, 16)

    /** The game's save folders in the source: profile-relative roots, plus emulator saves for [appId]. */
    class Saves(val roots: List<String>, val appId: Int?, val label: String)

    /**
     * The game's folder on the source container's C: drive. [problem] non-null: it can't be brought.
     * [sharedWith]: another game in the source that runs from the same folder (moving would break it).
     */
    class GameFolder(val folder: File, val relPath: String, val sizeBytes: Long, val problem: String?, val sharedWith: String?)

    enum class ExtraSource { COPY, DOWNLOAD, UNAVAILABLE }

    /** An extra (redistributable) the game needs and the target doesn't have. [suggested]: ticked at first. */
    class Extra(val name: String, val label: String, val source: ExtraSource, val note: String, val suggested: Boolean)

    /** What can come along to one target. */
    class Plan(val saves: Saves?, val gameFolder: GameFolder?, val extras: List<Extra>)

    /** What the user ticked. [extras] holds [Extra.name]s. */
    class Options(val saves: Boolean = false, val gameFolder: Boolean = false, val extras: Set<String> = emptySet()) {
        val any: Boolean get() = saves || gameFolder || extras.isNotEmpty()
    }

    sealed class Result {
        /**
         * [file] is the entry in the target; [addedDrive] the letter added to the target's drives, if
         * any; [notes] what happened to the ticked extras; [emuConflicts] emulator account ids from the
         * saves that differ from the target's, for the caller to ask about.
         */
        class Done(
            val file: File,
            val addedDrive: String?,
            val notes: List<String> = emptyList(),
            val emuConflicts: List<GameSaveBackup.EmuIdConflict> = emptyList(),
        ) : Result()
        class Refused(val reason: String) : Result()
    }

    /** Why [shortcut] can't go to [target] right now, or null when it can. [run] checks this again. */
    fun problem(
        context: Context,
        shortcut: Shortcut,
        target: Container,
        keepCopy: Boolean,
        options: Options = Options(),
        plan: Plan? = null,
    ): String? {
        val source = shortcut.container
        if (target.id == source.id) return "\"${shortcut.name}\" is already in \"${target.name}\"."
        if (AndroidGames.isAndroidEntry(shortcut) || LinuxShortcuts.isLinuxEntry(shortcut)) {
            return "Only Windows games can change container."
        }
        if (XboxCloudShortcuts.isNotInstalled(shortcut)) return "Finish downloading \"${shortcut.name}\" first."
        val info = CopyGameToDriveC.parse(shortcut)
        if (info.exeAndroid == null) return "Couldn't find where \"${shortcut.name}\" is installed."
        if (info.onDriveC && !options.gameFolder) {
            return "\"${shortcut.name}\" is installed inside \"${source.name}\" (drive C:), so its game files have to come too."
        }
        if (options.gameFolder && !keepCopy) plan?.gameFolder?.sharedWith?.let {
            return "\"$it\" in \"${source.name}\" runs from the same folder. Keep a copy instead, or move both games."
        }
        val dir = target.desktopDir
        if (File(dir, shortcut.file.name).exists() || File(dir, "${shortcut.name}.lnk").exists()) {
            return "\"${target.name}\" already has a game called \"${shortcut.name}\"."
        }
        // A running session keeps writing to its own .desktop, its registry and its saves.
        if (!keepCopy || options.any) {
            val updater = ContainerLayerUpdater(context)
            if (updater.isRunning(source) || updater.isRunning(target)) return "Close the running game first."
        }
        return null
    }

    /** What [shortcut] can bring to [target]: its saves, its C: folder, the extras the target lacks. */
    fun plan(context: Context, shortcut: Shortcut, target: Container): Plan {
        val source = shortcut.container
        val info = CopyGameToDriveC.parse(shortcut)
        val exe = info.exeAndroid
        val saves = runCatching { findSaves(source, shortcut) }.getOrNull()
        val folder = if (info.onDriveC && exe != null) runCatching { gameFolder(source, target, exe, shortcut.file) }.getOrNull() else null
        val extras = runCatching { findExtras(context, source, target, exe) }
            .onFailure { Log.w(TAG, "extras for '${shortcut.name}'", it) }
            .getOrDefault(emptyList())
        return Plan(saves, folder, extras)
    }

    fun run(
        context: Context,
        shortcut: Shortcut,
        target: Container,
        keepCopy: Boolean,
        options: Options = Options(),
        plan: Plan? = null,
        onStage: (String) -> Unit = {},
    ): Result {
        problem(context, shortcut, target, keepCopy, options, plan)?.let { return Result.Refused(it) }
        val source = shortcut.container
        val destDir = target.desktopDir
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            return Result.Refused("Couldn't open the games folder of \"${target.name}\".")
        }
        val text = runCatching { shortcut.file.readText() }.getOrNull()
            ?: return Result.Refused("Couldn't read \"${shortcut.name}\".")
        val info = CopyGameToDriveC.parse(shortcut)

        // The game's folder first: the entry keeps its C:\ path, so it must be there before the entry is.
        var movedFolder: Pair<File, File>? = null
        var copiedFolder: File? = null
        if (options.gameFolder && info.onDriveC) {
            val gf = plan?.gameFolder ?: return Result.Refused("Couldn't find the game's folder on drive C:.")
            gf.problem?.let { return Result.Refused(it) }
            val dest = File(target.rootDir, ".wine/drive_c/${gf.relPath}")
            if (keepCopy) {
                if (!CopyGameToDriveC.hasRoomFor(context, gf.sizeBytes)) {
                    return Result.Refused("Not enough free space to copy the game's files (${gf.sizeBytes / (1024 * 1024)} MB).")
                }
                onStage("Copying the game's files…")
                try {
                    CopyGameToDriveC.copyTree(gf.folder, dest, gf.sizeBytes, { false }) { }
                } catch (e: Exception) {
                    return Result.Refused("Couldn't copy the game's files: ${e.message ?: "unknown error"}.")
                }
                copiedFolder = dest
            } else {
                onStage("Moving the game's files…")
                dest.parentFile?.mkdirs()
                // Both prefixes are on the same storage, so this is a rename.
                if (!gf.folder.renameTo(dest)) return Result.Refused("Couldn't move the game's files to \"${target.name}\".")
                movedFolder = gf.folder to dest
            }
        }
        fun undoFolder() {
            movedFolder?.let { (from, to) -> if (!to.renameTo(from)) Log.e(TAG, "couldn't move $to back to $from") }
            copiedFolder?.deleteRecursively()
        }

        onStage("Writing the game's entry…")
        val newFile = File(destDir, shortcut.file.name)
        // A "not Ubisoft" stamp is not carried over: the target works it out again (it may have been stamped on a partial folder).
        if (!FileUtils.writeString(newFile, UbisoftSupport.dropStaleStamp(text))) {
            undoFolder()
            return Result.Refused("Couldn't write to \"${target.name}\".")
        }
        // Everything written into the target, removed again if any step fails.
        val written = arrayListOf(newFile)
        var oldCover: File? = null
        var addedDrive: String? = null
        try {
            iconOf(text)?.let { icon ->
                for (size in ICON_SIZES) {
                    val src = File(source.getIconsDir(size), "$icon.png")
                    val dst = File(target.getIconsDir(size), "$icon.png")
                    if (src.isFile && !dst.exists()) {
                        dst.parentFile?.mkdirs()
                        src.copyTo(dst)
                        written += dst
                    }
                }
            }

            val moved = Shortcut(target, newFile)
            // Only a cover stored in the source container is copied; one elsewhere is shared as it is.
            shortcut.customCoverArtPath?.takeIf { it.isNotEmpty() }?.let { File(it) }
                ?.takeIf { it.isFile && it.path.startsWith(source.rootDir.path + "/") }
                ?.let { src ->
                    val dst = File(target.rootDir, "app_data/cover_arts/${src.name}")
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                    written += dst
                    moved.putExtra("customCoverArtPath", dst.path)
                    oldCover = src
                }
            // A copy is a separate game: it gets its own home-screen id when it is pinned.
            if (keepCopy) moved.putExtra("uuid", null)
            moved.saveData()

            // A C:\ path is the same in the target (its folder came along above); others go through the drive map.
            if (!info.onDriveC) {
                val exe = info.exeAndroid ?: throw IOException("the game's folder isn't reachable")
                val sameFile = runCatching { WinePath.resolveAndroidPath(target, info.exeWin) }.getOrNull()
                    ?.absolutePath == exe.absolutePath
                if (!sameFile) {
                    val drivesBefore = target.drives
                    val newWin = CopyGameToDriveC.setShortcutExe(Shortcut(target, newFile), exe, info.argsSuffix)
                        ?: throw IOException("no free drive letter in \"${target.name}\" for the game's folder")
                    if (target.drives != drivesBefore) addedDrive = newWin.take(2)
                }
            }

            // The new entry must load like any other before the original goes.
            if (Shortcut(target, newFile).path.isBlank()) throw IOException("the new entry has no program to run")
        } catch (e: Exception) {
            Log.e(TAG, "move of '${shortcut.name}' to container ${target.id} failed", e)
            written.forEach { it.delete() }
            undoFolder()
            return Result.Refused("Couldn't ${if (keepCopy) "copy" else "move"} \"${shortcut.name}\": ${e.message ?: "unknown error"}.")
        }
        // Keeps its place in "Recently added".
        newFile.setLastModified(shortcut.file.lastModified())

        if (!keepCopy) {
            shortcut.file.delete()
            // A leftover .lnk would bring the game back into the source container.
            File(shortcut.file.parentFile, "${shortcut.name}.lnk").delete()
            oldCover?.delete()
            updatePin(context, Shortcut(target, newFile))
        }

        // The ticked extras. The game is already in the target; a failure here is reported, not undone.
        val notes = ArrayList<String>()
        var emuConflicts = emptyList<GameSaveBackup.EmuIdConflict>()
        if (options.saves) plan?.saves?.let { saves ->
            onStage("Copying saves…")
            try {
                val (count, conflicts) = copySaves(context, source, target, shortcut, saves)
                notes += if (count > 0) "$count save file${if (count == 1) "" else "s"} copied" else "no save files to copy"
                emuConflicts = conflicts
            } catch (e: Exception) {
                Log.e(TAG, "saves of '${shortcut.name}' not copied", e)
                notes += "saves not copied (${e.message ?: "unknown error"})"
            }
        }
        plan?.extras.orEmpty().filter { it.name in options.extras }.forEach { extra ->
            onStage("Adding ${extra.label}…")
            val error = runCatching { addExtra(context, source, target, extra) }.getOrElse { it.message ?: "unknown error" }
            notes += if (error == null) "${extra.label} added" else "${extra.label} not added ($error)"
        }

        Log.i(TAG, "${if (keepCopy) "copied" else "moved"} '${shortcut.name}' from container ${source.id} to ${target.id}" +
            (addedDrive?.let { ", added drive $it" } ?: "") + (if (notes.isEmpty()) "" else "; " + notes.joinToString("; ")))
        return Result.Done(newFile, addedDrive, notes, emuConflicts)
    }

    /** What to tell the user about [result]. */
    fun message(result: Result, gameName: String, targetName: String, keepCopy: Boolean): String = when (result) {
        is Result.Refused -> result.reason
        is Result.Done -> buildString {
            append(if (keepCopy) "Copied" else "Moved").append(" \"").append(gameName).append("\" to \"").append(targetName).append("\".")
            result.addedDrive?.let { append(" Added drive ").append(it).append(" to \"").append(targetName).append("\" for it.") }
            if (result.notes.isNotEmpty()) append(" ").append(result.notes.joinToString("; ").replaceFirstChar { it.uppercase() }).append(".")
        }
    }

    /** The intent a pinned home-screen icon of a Wine game opens. */
    fun pinIntent(context: Context, shortcut: Shortcut): Intent =
        Intent(context, XServerDisplayActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("container_id", shortcut.container.id)
            putExtra("shortcut_path", shortcut.file.path)
        }

    // ---- saves ----------------------------------------------------------------------------------

    /**
     * The game's remembered save folders, else the discovered ones that match its name outright,
     * plus its emulator saves. A shared word alone ("DiRT Showdown" vs "DiRT Rally 2.0") is not
     * enough here: the copy lands on the target's own folder of that name.
     */
    private fun findSaves(source: Container, shortcut: Shortcut): Saves? {
        val key = SaveLocator.gameKey(shortcut.wmClass, shortcut.file.name)
        val roots = SaveLocator.loadMap(source, key)?.roots?.takeIf { it.isNotEmpty() }
            ?: SaveLocator.discover(source, shortcut.name, shortcut.path ?: "", shortcut.wmClass ?: "")
                .filter { it.score >= SAVE_NAME_MATCH }
                .map { it.relPath }
        val appId = EmuSaveRoots.appIdFor(source, shortcut)
        if (roots.isEmpty() && appId == null) return null
        val parts = roots.map { it.trimEnd('/') } + listOfNotNull(appId?.let { "emulator saves for app $it" })
        return Saves(roots, appId, parts.joinToString(", "))
    }

    private fun copySaves(
        context: Context,
        source: Container,
        target: Container,
        shortcut: Shortcut,
        saves: Saves,
    ): Pair<Int, List<GameSaveBackup.EmuIdConflict>> {
        val zip = File(context.cacheDir, "move-saves-${System.currentTimeMillis()}.zip")
        try {
            val backup = GameSaveBackup.backupToFile(source, saves.roots, GameSaveBackup.BackupLayout.WINLATOR, zip, saves.appId)
            if (!backup.ok) {
                if (backup.fileCount == 0) return 0 to emptyList()
                throw IOException(backup.error ?: "backup failed")
            }
            val restored = GameSaveBackup.restoreInto(FileInputStream(zip), target.rootDir, File(context.cacheDir, "emu-id-restore"))
            if (!restored.ok) throw IOException(restored.error ?: "restore failed")
            // The folders the user taught for this game, so the target's backups find them too.
            val key = SaveLocator.gameKey(shortcut.wmClass, shortcut.file.name)
            SaveLocator.loadMap(source, key)?.let { SaveLocator.saveMap(target, key, it.name, it.roots) }
            return backup.fileCount to restored.emuConflicts
        } finally {
            zip.delete()
        }
    }

    // ---- the game's folder on C: ------------------------------------------------------------------

    private fun gameFolder(source: Container, target: Container, exe: File, entry: File): GameFolder {
        val driveC = File(source.rootDir, ".wine/drive_c")
        val folder = CopyGameToDriveC.defaultSourceRoot(exe)
        val base = driveC.absolutePath + "/"
        val rel = folder.absolutePath.takeIf { it.startsWith(base) }?.removePrefix(base)?.trimEnd('/') ?: ""
        val shown = "C:\\" + rel.replace('/', '\\')
        val problem = when {
            unsafeFolder(rel) -> "The game sits in $shown, which holds more than this game, so it can't be moved on its own."
            File(target.rootDir, ".wine/drive_c/$rel").exists() -> "\"${target.name}\" already has $shown."
            else -> null
        }
        val size = if (problem == null) CopyGameToDriveC.folderSize(folder) else 0L
        // Exec lines escape each backslash several times over; one is enough to compare paths.
        val prefix = (shown + "\\").lowercase(Locale.ROOT)
        val sharedWith = source.desktopDir.listFiles { f -> f.name.endsWith(".desktop") && f.name != entry.name }
            ?.firstOrNull { f ->
                val exec = runCatching { f.readLines().firstOrNull { it.startsWith("Exec=") } }.getOrNull() ?: return@firstOrNull false
                exec.replace(Regex("""\\\\+"""), "\\\\").lowercase(Locale.ROOT).contains(prefix)
            }?.name?.removeSuffix(".desktop")
        return GameFolder(folder, rel, size, problem, sharedWith)
    }

    /** Folders that belong to Windows or hold more than one game: never moved as "the game". */
    private fun unsafeFolder(rel: String): Boolean {
        val parts = rel.lowercase(Locale.ROOT).split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts[0] == "windows") return true
        // "games" is where Copy to Drive C puts every game (C:\Games\<name>).
        if (parts.size == 1 && parts[0] in setOf("users", "program files", "program files (x86)", "programdata", "games")) return true
        // users/<name> and its Desktop, Documents, AppData…
        return parts[0] == "users" && parts.size <= 3
    }

    // ---- extras (redistributables) -----------------------------------------------------------------

    private fun findExtras(context: Context, source: Container, target: Container, exe: File?): List<Extra> {
        val needed = GameRecommendations.detect(exe, exe?.parentFile, context)
            .filter { GameRecommendations.group(it) == GameRecommendations.Group.RECOMMENDED }
        if (needed.isEmpty()) return emptyList()
        // What the target really has: an install journal, or files the detector can see. The
        // component_installs record alone is not trusted (it outlives a deleted container whose id is reused).
        val targetSeen = PrefixInstalledDetector.detect(target)
        val sourceSeen = PrefixInstalledDetector.detect(source)
        val sourceRecorded = context.getSharedPreferences("component_installs", Context.MODE_PRIVATE)
            .getStringSet("c${source.id}", emptySet()).orEmpty()
        val sameArch = sameArch(context, source, target)
        var catalog: Map<String, com.winlator.star.components.Component>? = null
        val out = ArrayList<Extra>()
        for (r in needed) {
            val name = r.componentName
            if (ComponentUndo.journalsFor(target.rootDir, name).isNotEmpty() || name in targetSeen) continue
            val journals = ComponentUndo.journalsFor(source.rootDir, name)
            if (journals.isNotEmpty() && sameArch) {
                out += Extra(name, r.label, ExtraSource.COPY, "Copied from \"${source.name}\"", suggested = true)
                continue
            }
            val inSource = journals.isNotEmpty() || name in sourceSeen || name in sourceRecorded
            val cat = catalog ?: runCatching { ComponentCatalog.load() }.getOrDefault(emptyList()).associateBy { it.name }
                .also { catalog = it }
            val c = cat["${name}_dll"] ?: cat[name]
            out += when {
                cat.isEmpty() -> Extra(name, r.label, ExtraSource.UNAVAILABLE, "No connection: add it later from the game's Win Components", false)
                c == null -> Extra(name, r.label, ExtraSource.UNAVAILABLE, "Not in the components list", false)
                ComponentExecInstaller.isExecComponent(c) || ComponentExecInstaller.handlesComponent(c) ||
                    ComponentInstaller.blockedReason(c) != null ->
                    Extra(name, r.label, ExtraSource.UNAVAILABLE, "Needs its installer: add it later from the game's Win Components", false)
                else -> Extra(
                    name, r.label, ExtraSource.DOWNLOAD,
                    if (inSource) "Downloaded (\"${source.name}\" has it)" else "Downloaded (\"${source.name}\" doesn't have it either)",
                    suggested = inSource,
                )
            }
        }
        return out
    }

    /** Null when added, else why not. */
    private fun addExtra(context: Context, source: Container, target: Container, extra: Extra): String? {
        when (extra.source) {
            ExtraSource.COPY -> {
                val journals = ComponentUndo.journalsFor(source.rootDir, extra.name)
                if (journals.isEmpty()) return "no install record in \"${source.name}\""
                // Oldest first, as they went in.
                for (j in journals.reversed()) ComponentUndo.copyInto(source.rootDir, target.rootDir, j)
            }
            ExtraSource.DOWNLOAD -> {
                val c = ComponentCatalog.byName["${extra.name}_dll"] ?: ComponentCatalog.byName[extra.name]
                    ?: return "not in the components list"
                ComponentInstaller.install(context, target, c) { }?.let { return it }
            }
            ExtraSource.UNAVAILABLE -> return extra.note
        }
        // Recorded under the base name, as the Win Components screens do.
        val prefs = context.getSharedPreferences("component_installs", Context.MODE_PRIVATE)
        val key = "c${target.id}"
        prefs.edit().putStringSet(key, prefs.getStringSet(key, emptySet()).orEmpty() + extra.name).commit()
        return null
    }

    private fun sameArch(context: Context, a: Container, b: Container): Boolean = runCatching {
        val cm = ContentsManager(context)
        cm.syncContents()
        WineInfo.fromIdentifier(context, cm, a.wineVersion).isArm64EC == WineInfo.fromIdentifier(context, cm, b.wineVersion).isArm64EC
    }.getOrDefault(false)

    // ---- entry helpers -----------------------------------------------------------------------------

    /** Points a pinned home-screen icon of [moved] (same id) at its new place. Nothing when it isn't pinned. */
    private fun updatePin(context: Context, moved: Shortcut) {
        val id = moved.getExtra("uuid").ifEmpty { return }
        try {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return
            if (sm.pinnedShortcuts.none { it.id == id }) return
            val info = ShortcutInfo.Builder(context, id)
                .setShortLabel(moved.name)
                .setLongLabel(moved.name)
                .setIntent(pinIntent(context, moved))
                .build()
            if (!sm.updateShortcuts(listOf(info))) Log.w(TAG, "home-screen icon of '${moved.name}' not updated (rate limited)")
        } catch (e: Exception) {
            Log.w(TAG, "home-screen icon of '${moved.name}' not updated", e)
        }
    }

    private fun iconOf(desktopText: String): String? = desktopText.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("Icon=") }
        ?.removePrefix("Icon=")
        ?.takeIf { it.isNotEmpty() }
}
