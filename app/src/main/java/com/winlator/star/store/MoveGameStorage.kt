package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.CopyGameToDriveC
import java.io.File

/**
 * Moves an INSTALLED Steam game between internal storage (`imagefs/steam_games`) and the SD card
 * (`<sd>/bannerlator/steam_games`) after the fact, without a re-download.
 *
 * [SteamSdInstall] already lets a FRESH download land on the card; this is the same destination
 * reached from the other side, for a game that is already on disk. The gear menu on the detail page
 * offers whichever direction the game isn't currently in.
 *
 * ── What has to travel, and what doesn't ──────────────────────────────────────────────────────
 * A Steam game's state is spread over three places, and only ONE of them is the install folder:
 *
 *  • **The install folder** — game files, DLC, the Goldberg-swapped `steam_api*.dll` + its `.bak`
 *    pristine backup ([GoldbergPatcher]), the `.bl_depot/` manifest journal and `.bannerlator_build`
 *    marker (so updates/verify still work without a re-download), and any saves a game writes beside
 *    its own exe (the `%GameInstall%` UFS root). All of that is inside the tree, so a full mirror
 *    carries it — nothing is filtered out of the copy on purpose.
 *  • **The container prefix** — achievements (`AppData/Roaming/GSE Saves/<appId>/achievements.json`,
 *    see [AchievementWatcher.gseAchievementsFile]), Steam remote storage, and every profile-relative
 *    save root. The move never touches a prefix, so these are unaffected by construction.
 *  • **Two pointers at the folder** — the `steam_games.install_dir` DB column and each shortcut's
 *    `Exec=` line. These are the only things that actually have to be REWRITTEN, and they must move
 *    together: cloud-save sync resolves a game's container by matching one against the other
 *    ([SteamCloudSavePaths.resolveContainer]), so a half-applied move is what breaks cloud saves.
 *
 * Everything else re-derives itself at launch from those two pointers: the SteamLite
 * `steamapps\common\<Name>` symlink is re-staged every launch and explicitly re-points when its
 * target moved ([RealSteamLauncher] step 3b), and Goldberg re-analyses the install dir each time.
 *
 * ── Order of operations (the safety contract) ─────────────────────────────────────────────────
 * copy → verify → repoint shortcuts → update DB → *only then* delete the source. Any failure before
 * the delete rolls the shortcuts back to their exact previous `Exec=` lines, drops the partial
 * destination and leaves the original install untouched, so a failed move is a no-op rather than a
 * broken game. The delete is last because it is the one irreversible step.
 */
object MoveGameStorage {
    private const val TAG = "MoveGameStorage"

    /** Where a game's files sit. */
    enum class Place { INTERNAL, SD }

    /** A move destination: the `steam_games` base to create the game folder under, plus free space. */
    data class Target(val place: Place, val base: File, val label: String, val freeBytes: Long)

    /** A validated, ready-to-run move. Built by [buildPlan]; consumed by [execute]. */
    data class Plan(
        val appId: Int,
        val gameName: String,
        val source: File,
        val dest: File,
        val sizeBytes: Long,
        val from: Place,
        val to: Target,
        /** Shortcuts (across every container) whose exe lives under [source] and will be repointed. */
        val shortcuts: List<Shortcut>,
        /** EA-published title — an install-path change can force a re-activation. Warned about in the UI. */
        val isEaTitle: Boolean,
    ) {
        val fitsOnTarget: Boolean get() = to.freeBytes >= sizeBytes + SteamSdInstall.FREE_SPACE_MARGIN
    }

    /** Why a move can't be offered/run. [message] is user-facing. */
    class MoveException(message: String) : Exception(message)

    /**
     * True while a move is copying. Consulted by [SteamForegroundService.stopIfIdle] so the service
     * (and its wakelock) isn't torn down under a long copy, and by the detail page so a second move
     * can't be started on top of the first.
     */
    @Volatile
    var moveInProgress: Boolean = false
        private set

    // ── Planning ─────────────────────────────────────────────────────────────────────────────

    /** The internal `steam_games` base — where a download lands when the SD toggle is off. */
    fun internalBase(ctx: Context): File = File(ctx.filesDir, "imagefs/steam_games")

    /** Which side of the fence [installDir] is on. Anything not under [internalBase] counts as SD. */
    fun placeOf(ctx: Context, installDir: String): Place {
        if (installDir.isBlank()) return Place.INTERNAL
        val internal = internalBase(ctx).absolutePath.trimEnd('/')
        val abs = File(installDir).absolutePath.trimEnd('/')
        return if (abs == internal || abs.startsWith("$internal/")) Place.INTERNAL else Place.SD
    }

    /**
     * The gear row's wording for this game — "Move to SD card" / "Move to internal storage" — or
     * null when a move can't be offered: not installed, the folder isn't really there, or it's on
     * internal storage and the device has no card to move it to.
     *
     * Reads the DATABASE row rather than the in-memory [SteamGame], deliberately: that object's
     * `installDir` starts life as the PICS `config.installdir` folder NAME and only becomes a host
     * path once `markInstalled` overwrites the column, so the row is the one authoritative answer to
     * "where is this game". Touches the filesystem and the volume list — call it off the UI thread.
     */
    fun offerLabel(ctx: Context, appId: Int): String? = runCatching {
        val row = SteamDatabase.getInstance(ctx).getGame(appId) ?: return null
        if (!row.isInstalled || row.installDir.isNullOrBlank()) return null
        if (!File(row.installDir).isDirectory) return null
        val place = placeOf(ctx, row.installDir)
        if (place == Place.INTERNAL && SteamSdInstall.detect(ctx) == null) return null
        menuLabel(place)
    }.getOrNull()

    /**
     * Builds the move for [appId] — always to the side it isn't on. Does the folder-size walk and
     * the shortcut scan, so it belongs on a background thread.
     *
     * @throws MoveException with a user-facing reason when a move isn't possible.
     */
    fun buildPlan(ctx: Context, appId: Int, isCancelled: () -> Boolean = { false }): Plan {
        val row = SteamDatabase.getInstance(ctx).getGame(appId)
            ?: throw MoveException("This game isn't in the library database.")
        val installDir = row.installDir.orEmpty()
        if (!row.isInstalled || installDir.isBlank()) throw MoveException("This game isn't installed.")

        val source = File(installDir)
        if (!source.isDirectory) {
            throw MoveException("The game folder is missing:\n$installDir")
        }

        val from = placeOf(ctx, installDir)
        val to = when (from) {
            Place.INTERNAL -> {
                val sd = SteamSdInstall.detect(ctx)
                    ?: throw MoveException("No SD card is mounted, so there's nowhere to move this game.")
                Target(Place.SD, sd.steamGamesBase, sd.label, sd.freeBytes)
            }
            Place.SD -> {
                val base = internalBase(ctx)
                Target(Place.INTERNAL, base, "Internal storage", CopyGameToDriveC.freeBytes(ctx))
            }
        }

        // Keep the folder NAME across the move — the installer's safe-name, whatever it is — so the
        // SteamLite canonical-name derivation and any user muscle memory stay put. Only collide-rename
        // if something is already sitting there.
        val dest = CopyGameToDriveC.autoRenamedDest(File(to.base, source.name))

        val size = CopyGameToDriveC.folderSize(source, isCancelled)
        val shortcuts = shortcutsUnder(ctx, source)
        val isEa = runCatching { EaSupport.detect(source) != null }.getOrDefault(false)

        return Plan(appId, row.name.orEmpty().ifBlank { source.name }, source, dest, size, from, to, shortcuts, isEa)
    }

    /**
     * Every shortcut in every container whose exe resolves to a file under [source]. Uses the same
     * drive-map resolution the launcher does, so it finds a game whether it's addressed as `Z:\…`
     * (internal) or through an auto-mounted letter (card).
     */
    private fun shortcutsUnder(ctx: Context, source: File): List<Shortcut> {
        val all = runCatching { ContainerManager(ctx).loadShortcuts() }.getOrElse {
            Log.w(TAG, "loadShortcuts failed", it); return emptyList()
        }
        return all.filter { sc ->
            val exe = runCatching { CopyGameToDriveC.parse(sc).exeAndroid }.getOrNull()
            CopyGameToDriveC.isAncestor(source, exe)
        }
    }

    // ── Execution ────────────────────────────────────────────────────────────────────────────

    /** Coarse phase for the progress UI. */
    enum class Phase { COPYING, VERIFYING, REPOINTING, CLEANING }

    /** Live move progress: which phase, and (during COPYING) how far the byte copy has got. */
    data class Progress(
        val phase: Phase,
        val copiedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val currentFile: String = "",
    ) {
        val pct: Int get() = if (totalBytes <= 0L) 0 else ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    /**
     * Runs [plan] on the CALLING (background) thread. Returns the new install dir on success.
     *
     * Cancellation is honoured during the copy only — once the shortcuts are being repointed the move
     * is committing and runs to completion (it is a handful of small file writes; interrupting there
     * is what would leave the two pointers disagreeing).
     *
     * @throws CopyGameToDriveC.CancelledException when [isCancelled] goes true during the copy.
     * @throws MoveException on a failure that has already been rolled back.
     */
    fun execute(
        ctx: Context,
        plan: Plan,
        isCancelled: () -> Boolean,
        onProgress: (Progress) -> Unit,
    ): File {
        if (!plan.fitsOnTarget) {
            throw MoveException(
                "Not enough room on ${plan.to.label}: the game needs " +
                    "${SteamSdInstall.fmtBytes(plan.sizeBytes)} and only " +
                    "${SteamSdInstall.fmtBytes(plan.to.freeBytes)} is free."
            )
        }
        moveInProgress = true
        SteamForegroundService.start(ctx)
        val shortName = plan.gameName.take(28)
        try {
            // ── 1. Copy. copyTree deletes its own partial destination on cancel/IO failure, so a
            //       throw from here leaves nothing behind but the untouched source. ──────────────
            SteamForegroundService.setStatusText("Moving $shortName — 0%")
            var lastPct = -1
            CopyGameToDriveC.copyTree(plan.source, plan.dest, plan.sizeBytes, isCancelled) { p ->
                val prog = Progress(Phase.COPYING, p.copiedBytes, p.totalBytes, p.currentFile)
                onProgress(prog)
                if (prog.pct != lastPct) {
                    lastPct = prog.pct
                    SteamForegroundService.setStatusText("Moving $shortName — ${prog.pct}%")
                }
            }

            // ── 2. Verify before anything is repointed or deleted: same file count, same bytes. A
            //       short read that didn't throw (a card yanked mid-copy) shows up here. ─────────
            onProgress(Progress(Phase.VERIFYING, plan.sizeBytes, plan.sizeBytes))
            SteamForegroundService.setStatusText("Moving $shortName — verifying")
            verifyCopy(plan.source, plan.dest)

            // ── 3. Repoint every shortcut, remembering each original Exec line for rollback. ────
            onProgress(Progress(Phase.REPOINTING, plan.sizeBytes, plan.sizeBytes))
            SteamForegroundService.setStatusText("Moving $shortName — updating shortcuts")
            val undo = ArrayList<Pair<File, String>>(plan.shortcuts.size)
            try {
                for (sc in plan.shortcuts) {
                    val before = execLineOf(sc.file)
                    val newWin = repoint(ctx, sc, plan.source, plan.dest)
                        ?: throw MoveException(
                            "Couldn't update the shortcut \"${sc.name}\" — it may be out of drive letters. " +
                                "Nothing was moved."
                        )
                    if (before != null) undo.add(sc.file to before)
                    Log.i(TAG, "repointed '${sc.name}' (container ${sc.container.id}) -> $newWin")
                }

                // ── 4. The database pointer. Same call the installer makes on completion. ───────
                // Keep the row's EXISTING size: `size_bytes` means the Steam manifest install size
                // everywhere else (BlDepotInstaller passes the install total), and a move doesn't
                // change how big a game is. Writing the folder-walk total here put on-disk bytes in
                // a manifest-bytes column until the size resolver happened to correct it.
                val db = SteamDatabase.getInstance(ctx)
                val knownSize = runCatching { db.getGame(plan.appId)?.sizeBytes }.getOrNull()
                    ?.takeIf { it > 0L } ?: plan.sizeBytes
                db.markInstalled(plan.appId, plan.dest.absolutePath, knownSize)
                // markInstalled (unlike markUninstalled) doesn't invalidate, and the library list
                // caches install_dir — refresh it so the SD badge and paths repaint immediately.
                runCatching { SteamRepository.getInstance().invalidateGameCache() }
            } catch (t: Throwable) {
                for ((file, line) in undo) runCatching { restoreExecLine(file, line) }
                runCatching { plan.dest.deleteRecursively() }
                throw if (t is MoveException) t
                else MoveException("Couldn't update this game's shortcuts, so nothing was moved: ${t.message}")
            }

            // ── 5. Committed — drop the original. A failure here is cosmetic (wasted space), never
            //       a broken game, so it is logged rather than surfaced as a failed move. ────────
            onProgress(Progress(Phase.CLEANING, plan.sizeBytes, plan.sizeBytes))
            SteamForegroundService.setStatusText("Moving $shortName — freeing the old copy")
            if (!plan.source.deleteRecursively()) {
                Log.w(TAG, "old copy not fully deleted: ${plan.source.absolutePath}")
            }
            Log.i(TAG, "moved appId=${plan.appId} ${plan.source.absolutePath} -> ${plan.dest.absolutePath}")
            return plan.dest
        } finally {
            moveInProgress = false
            runCatching { SteamForegroundService.stopIfIdle(ctx) }
        }
    }

    /**
     * Structural check that [dest] really is [src], compared **per file**: every relative path
     * present on both sides, with the same length. Deliberately NOT a hash — re-reading tens of GB
     * back off a FUSE-backed card would roughly double the time of every move, so byte-level
     * re-reading belongs behind an explicit "thorough check" opt-in, not in the default path.
     *
     * Per-file rather than totals: comparing only (count, total bytes) can be fooled by two errors
     * that cancel out — one file short, another long — and it can't name what went wrong. Walking
     * both trees into a path→size map costs the same two directory walks and catches a single
     * truncated or missing file, which is the realistic failure here.
     */
    private fun verifyCopy(src: File, dest: File) {
        val srcFiles = fileSizesByRelPath(src)
        val destFiles = fileSizesByRelPath(dest)

        val missing = srcFiles.keys.asSequence().filter { it !in destFiles }.take(5).toList()
        val mismatched = srcFiles.asSequence()
            .filter { (rel, size) -> destFiles[rel]?.let { it != size } == true }
            .map { it.key }.take(5).toList()

        if (missing.isEmpty() && mismatched.isEmpty() && srcFiles.size == destFiles.size) return

        runCatching { dest.deleteRecursively() }
        val detail = when {
            missing.isNotEmpty() ->
                "${srcFiles.size - destFiles.size} file(s) didn't arrive, e.g. ${missing.first()}"
            mismatched.isNotEmpty() ->
                "${mismatched.size} file(s) came out the wrong size, e.g. ${mismatched.first()}"
            else -> "${destFiles.size} files arrived, expected ${srcFiles.size}"
        }
        throw MoveException(
            "The copy didn't come out complete ($detail). " +
                "Nothing was moved — the game is still where it was."
        )
    }

    /** Every file under [dir] as `relative/path` → length. Directories are structure, not content. */
    private fun fileSizesByRelPath(dir: File): Map<String, Long> {
        val root = dir.absolutePath.trimEnd('/')
        val out = HashMap<String, Long>()
        val stack = ArrayDeque<File>().apply { addLast(dir) }
        while (stack.isNotEmpty()) {
            val kids = stack.removeLast().listFiles() ?: continue
            for (k in kids) {
                if (k.isDirectory) stack.addLast(k)
                else out[k.absolutePath.removePrefix(root).removePrefix("/")] = k.length()
            }
        }
        return out
    }

    /**
     * Repoints one shortcut from [source] to [dest], keeping its launch args.
     *
     * Steam games are addressed two different ways and the rewrite has to honour both — the same
     * rule the shortcut writer applies when a game is first added ([StarLaunchBridge]): a game under
     * `imagefs/` is reachable as the container's fixed `Z:` drive, and anything off `imagefs` (the
     * card) goes through the container drive map, which allocates and persists a letter. Passing the
     * imagefs root to [CopyGameToDriveC.setShortcutExe] is what keeps a move BACK to internal from
     * burning a fresh drive letter on an app-private path that isn't a storage volume.
     */
    private fun repoint(ctx: Context, sc: Shortcut, source: File, dest: File): String? {
        val info = CopyGameToDriveC.parse(sc)
        val exe = info.exeAndroid ?: return null
        val srcPath = source.absolutePath.trimEnd('/')
        val rel = when {
            exe.absolutePath == srcPath -> ""
            exe.absolutePath.startsWith("$srcPath/") -> exe.absolutePath.substring(srcPath.length + 1)
            else -> return null
        }
        val newExe = if (rel.isEmpty()) dest else File(dest, rel)
        return CopyGameToDriveC.setShortcutExe(sc, newExe, info.argsSuffix, File(ctx.filesDir, "imagefs"))
    }

    /** The shortcut's current `Exec=` line verbatim, for rollback. Null when there isn't one. */
    private fun execLineOf(desktop: File): String? =
        runCatching { desktop.readLines().firstOrNull { it.startsWith("Exec=") } }.getOrNull()

    /** Puts a remembered `Exec=` line back exactly as it was. */
    private fun restoreExecLine(desktop: File, line: String) {
        val lines = desktop.readLines()
        if (lines.none { it.startsWith("Exec=") }) return
        desktop.writeText(lines.joinToString("\n") { if (it.startsWith("Exec=")) line else it } + "\n")
    }

    /** Gear-menu wording for the direction on offer — always the side the game ISN'T on. */
    fun menuLabel(from: Place): String = when (from) {
        Place.INTERNAL -> "Move to SD card"
        Place.SD -> "Move to internal storage"
    }
}
