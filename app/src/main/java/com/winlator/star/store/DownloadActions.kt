package com.winlator.star.store

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.star.R
import com.winlator.star.store.compose.AddResultDialog
import com.winlator.star.store.compose.AddShortcutResult
import com.winlator.star.store.compose.AddToShortcutsRequest
import com.winlator.star.store.compose.ContainerPickerDialog
import com.winlator.star.store.compose.openShortcutsScreen
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.Store
import com.winlator.star.ui.screens.OutlinedAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a card of the cross-store Download Manager does, shared by [DownloadManagerActivity] and the XMB Home's Downloads page
 * (ui/deck/XmbDownloadsPage.kt): open a game's page in its store, launch (scan for exes, pick one, pick a container, write the
 * shortcut), uninstall, cancel. It holds the state of the dialogs those flows raise; call [Dialogs] once, next to the list, to
 * draw them. Lives in the `store` package (not `store.download`) so it can reach the package-private Java launch helpers, exactly
 * like the Steam Activities.
 *
 * [inlineAddResult]: a shortcut written without trouble is reported in the themed bar ([notice]) rather than in the "Added to
 * Shortcuts" dialog with its jump to the Shortcuts screen (the XMB Home shows the new game in its own row).
 * [onShortcutAdded] runs once a shortcut has been written, so a screen that stays open under this page can reload its games.
 */
internal class DownloadActions(
    private val activity: Activity,
    private val inlineAddResult: Boolean = false,
    private val onShortcutAdded: () -> Unit = {},
) {
    // Add-to-shortcuts flow state (mirrors SteamGamesActivity / SteamGameDetailActivity).
    private var showExePicker by mutableStateOf<ExePickerDataDm?>(null)
    private var addToShortcuts by mutableStateOf<AddToShortcutsRequest?>(null)
    private var addResult by mutableStateOf<AddShortcutResult?>(null)
    // Non-null while an uninstall is running → shows the blocking progress spinner.
    private var uninstallingName by mutableStateOf<String?>(null)
    // Non-null briefly after an uninstall (or a launch problem) → themed auto-dismiss confirmation bar (not a Toast).
    var notice by mutableStateOf<String?>(null)
        private set

    /** Draws the dialogs and the bar of the flows below. [onOpenShortcuts]: the "Open Shortcuts" button of the success dialog. */
    @Composable
    fun Dialogs(onOpenShortcuts: () -> Unit = { openShortcutsScreen(activity) }) {
        showExePicker?.let { data ->
            ExePickerDialogDm(
                gameName = data.gameName,
                candidates = data.candidates,
                onDismiss = { showExePicker = null },
                onSelected = { chosen ->
                    showExePicker = null
                    startAddToShortcuts(data.appId, data.gameName, chosen, data.coverUrl, data.epic)
                },
            )
        }

        uninstallingName?.let { UninstallProgressDialog(it) }
        notice?.let { UninstallResultBar(it) { notice = null } }

        addToShortcuts?.let { req ->
            ContainerPickerDialog(
                gameName = req.gameName,
                containers = req.containers,
                onDismiss = { addToShortcuts = null },
                onSelected = { container ->
                    addToShortcuts = null
                    StarLaunchBridge.writeShortcutAsync(
                        activity, container,
                        req.gameName, req.exePath, req.coverUrl, req.appId, req.epic,
                    ) { success, message ->
                        if (success) onShortcutAdded()
                        if (success && inlineAddResult) {
                            notice = activity.getString(R.string.xmb_dl_added_to_games, req.gameName)
                        } else {
                            addResult = AddShortcutResult(req.gameName, success, message)
                        }
                    }
                },
            )
        }

        addResult?.let { result ->
            AddResultDialog(
                result = result,
                onOpenShortcuts = {
                    addResult = null
                    onOpenShortcuts()
                },
                onDismiss = { addResult = null },
            )
        }
    }

    /** Tap a card → open that game's detail in its store (works for downloading + installed). A game found in a Steam library
     * has no store row of its own — the found record is its card — so its card opens nothing. */
    fun openDetail(entry: DownloadEntry) {
        if (entry.found) return
        when (entry.store) {
            Store.STEAM -> activity.startActivity(
                Intent(activity, SteamGameDetailActivity::class.java)
                    .putExtra(SteamGameDetailActivity.EXTRA_APP_ID, entry.id.toIntOrNull() ?: 0),
            )
            Store.AMAZON -> {
                // The card only carries id(=productId)/name/cover; hydrate the rest of the detail
                // extras from amazon_library_cache. Fall back to the entry's own fields so the page
                // still opens (with a functional install/launch) even if the cache is unavailable.
                val d = AmazonLibrarySync.cachedDetail(activity, entry.id)
                val title = d?.title?.takeIf { it.isNotEmpty() } ?: entry.name
                val art = d?.artUrl?.takeIf { it.isNotEmpty() } ?: entry.cover ?: ""
                activity.startActivity(
                    Intent(activity, AmazonGameDetailActivity::class.java).apply {
                        putExtra("product_id", entry.id)
                        putExtra("entitlement_id", d?.entitlementId ?: "")
                        putExtra("title", title)
                        putExtra("developer", d?.developer ?: "")
                        putExtra("publisher", d?.publisher ?: "")
                        putExtra("art_url", art)
                        putExtra("product_sku", d?.productSku ?: "")
                    },
                )
            }
            Store.GOG -> {
                // Hydrate the detail extras from gog_library_cache; fall back to the entry's own
                // fields so the page still opens even if the cache is unavailable.
                val d = GogLibrarySync.cachedDetail(activity, entry.id)
                val title = d?.title?.takeIf { it.isNotEmpty() } ?: entry.name
                val image = d?.imageUrl?.takeIf { it.isNotEmpty() } ?: entry.cover ?: ""
                activity.startActivity(
                    Intent(activity, GogGameDetailActivity::class.java).apply {
                        putExtra("game_id", entry.id)
                        putExtra("title", title)
                        putExtra("image_url", image)
                        putExtra("description", d?.description ?: "")
                        putExtra("developer", d?.developer ?: "")
                        putExtra("category", d?.category ?: "")
                        putExtra("generation", d?.generation ?: 0)
                    },
                )
            }
            Store.XBOX -> {
                // Installed record first (title, art, market), then the entry's own fields.
                val g = com.winlator.star.store.xbox.XboxInstallState.get(activity, entry.id)
                activity.startActivity(com.winlator.star.store.xbox.XboxMainActivity.detailIntent(activity,
                    com.winlator.star.store.xbox.XboxGame(entry.id, g?.title ?: entry.name, g?.heroUrl, g?.posterUrl ?: entry.cover,
                        market = g?.market ?: com.winlator.star.store.xbox.Xodus.market(), access = g?.access ?: "")))
            }
            Store.EPIC -> {
                // Hydrate the detail extras from epic_cache; fall back to the entry's own fields so
                // the page still opens even if the cache is unavailable.
                val d = EpicLibrarySync.cachedDetail(activity, entry.id)
                val title = d?.title?.takeIf { it.isNotEmpty() } ?: entry.name
                val art = d?.artCover?.takeIf { it.isNotEmpty() } ?: entry.cover ?: ""
                activity.startActivity(
                    Intent(activity, EpicGameDetailActivity::class.java).apply {
                        putExtra("app_name", entry.id)
                        putExtra("title", title)
                        putExtra("description", d?.description ?: "")
                        putExtra("developer", d?.developer ?: "")
                        putExtra("art_cover", art)
                        putExtra("namespace", d?.namespace ?: "")
                        putExtra("catalog_item_id", d?.catalogItemId ?: "")
                    },
                )
            }
        }
    }

    /** Same launch flow the Steam screens use: scan for exes, pick one, add a shortcut. */
    fun launch(entry: DownloadEntry) {
        val dir = entry.installPath
        if (dir.isNullOrEmpty()) {
            notice = "Install directory not set"
            return
        }
        if (entry.store == Store.XBOX) {
            // Xbox: the launch exe comes from MicrosoftGame.config and the shortcut carries the product id.
            val g = com.winlator.star.store.xbox.XboxInstallState.get(activity, entry.id)
            if (g == null) { notice = "This Xbox game is not installed"; return }
            StarLaunchBridge.addToLauncher(activity, g.title, File(g.folder, g.exe).path, g.posterUrl ?: g.heroUrl, "xbox:${g.id}", false)
            return
        }
        // Only a Steam entry's id is a Steam app ID; GOG ids are numeric too and must not be stamped as Steam.
        val appId = if (entry.store == Store.STEAM) entry.id.toIntOrNull() ?: 0 else 0
        val installDir = File(dir)
        Thread {
            val exeFiles = mutableListOf<File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)
            if (exeFiles.isEmpty()) {
                activity.runOnUiThread {
                    notice = "No .exe found in install directory"
                }
                return@Thread
            }
            val lowerTitle = entry.name.lowercase()
            exeFiles.sortWith { a, b ->
                AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
            }
            // Steam: Steam's own launch exe first (its launch entries; also kept for the launch's arguments).
            if (appId > 0) {
                val ranked = SteamLaunchEntries.rankCandidates(activity, appId, installDir, exeFiles).toList()
                exeFiles.clear()
                exeFiles.addAll(ranked)
            }
            // An Epic shortcut carries the game's Epic data: the launch arguments an Epic game needs come from it.
            val epicDetail = if (entry.store == Store.EPIC) EpicLibrarySync.cachedDetail(activity, entry.id) else null
            val epic = if (entry.store == Store.EPIC) StarLaunchBridge.EpicMeta(entry.id, epicDetail?.namespace, epicDetail?.catalogItemId) else null
            val coverUrl = when (entry.store) {
                Store.STEAM -> "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/library_600x900.jpg"
                Store.EPIC -> epicDetail?.artCover?.takeIf { it.isNotEmpty() } ?: entry.cover ?: ""
                else -> entry.cover ?: ""
            }

            if (exeFiles.size == 1) {
                activity.runOnUiThread { startAddToShortcuts(appId, entry.name, exeFiles[0].absolutePath, coverUrl, epic) }
                return@Thread
            }
            val candidates = exeFiles.map { it.absolutePath }
            activity.runOnUiThread { showExePicker = ExePickerDataDm(appId, entry.name, candidates, coverUrl, epic) }
        }.start()
    }

    /** Load containers, then show the M3 picker. Goldberg Cold-Client rewrites the exe. */
    private fun startAddToShortcuts(appId: Int, gameName: String, exePath: String, coverUrl: String?, epic: StarLaunchBridge.EpicMeta?) {
        val launchExe = GoldbergPatcher.resolveLaunchExe(activity, appId, exePath)
        StarLaunchBridge.loadContainers(activity) { containers ->
            addToShortcuts = AddToShortcutsRequest(gameName, launchExe, coverUrl, containers, appId, epic)
        }
    }

    /** Uninstall: mark uninstalled in the store DB and delete the install dir off the UI
     *  thread; once the delete is VERIFIED complete, drop the durable-library row (which also
     *  removes it from the live registry), dismiss the spinner, and report the real outcome.
     *  Every imported / found / kept case takes [uninstallForeign] instead: its files AND its
     *  appmanifest belong to the library and are kept; only Bannerlator's record of it goes.
     *  So this path only ever handles Bannerlator's own download. */
    fun uninstall(entry: DownloadEntry) {
        val steamId = if (entry.store == Store.STEAM) entry.id.toIntOrNull() else null
        // The one keep rule ([clientFilesKept], the same one the card labels use): a game another Steam client installed keeps its
        // folder and appmanifest. The folder keep rule below also covers an installed game whose folder has no OWNED marker.
        if (steamId != null && clientFilesKept(activity, entry)) {
            uninstallForeign(entry, steamId)
            return
        }
        uninstallingName = entry.name
        StoreUninstaller.runReporting(
            installDir = entry.installPath,
            mark = {
                if (entry.store == Store.STEAM) {
                    entry.id.toIntOrNull()?.let {
                        SteamAppManifest.removeFor(entry.installPath, it)
                        SteamRepository.getInstance().database.markUninstalled(it)
                        // Drop the found record too, in case the seeder ran before this uninstall: the persisted found card and
                        // its durable-library row go with the uninstall.
                        SteamLibraryFound.forget(activity, it)
                    }
                }
            },
            // A folder another game (a demo / server) also uses is kept, and so is one this app did not create (not marked
            // OWNED inside a steamapps library): it may be another Steam client's.
            keepFolder = {
                val keepId = if (entry.store == Store.STEAM) entry.id.toIntOrNull() else null
                val path = entry.installPath
                !path.isNullOrEmpty() && (
                    !SteamInstallPaths.mayDeleteWholeFolder(File(path)) ||
                        keepId != null && SteamInstallPaths.folderSharedWithOtherGame(activity, File(path), keepId)
                )
            },
        ) { ok, kept ->
            // Clear the durable-library row (also drops the live registry entry) AND the store's
            // OWN native install record — otherwise the originating store's list/detail keeps
            // reading "installed" from a record the Manager never touched (device-observed bug).
            DownloadRegistry.removeTerminal(entry.key)
            purgeNativeInstall(entry)
            // The game's shortcuts go with it. A folder kept for another game still holds exes that game's shortcuts run, so then only
            // the ones tagged with this game's own id go; a failed delete removes none.
            val removal = shortcutRemovalFor(ok, kept, imported = false)
            StoreShortcutCleanup.remove(activity, entry.store, entry.id, entry.installPath, removal)
            uninstallingName = null
            notice = when {
                // The folder was kept for safety (a library container, another game, or a folder this app did not create): the
                // record is gone, the files are not.
                ok && kept -> activity.getString(R.string.xmb_dl_uninstall_files_kept, entry.name)
                ok -> "${entry.name} uninstalled"
                else -> "Couldn't fully remove ${entry.name}"
            }
        }
    }

    /**
     * Uninstall of a game this app did not download: only Bannerlator's record of it goes — the card, the found record, the imported
     * mark (with its ignore, so the next scan/import cannot bring the game back), and the Steam DB row. Its folder, its
     * `appmanifest_<id>.acf` and the shortcuts that run from it belong to the other Steam client and all stay, so that client still
     * sees a healthy install and remains free to update or uninstall the game.
     */
    private fun uninstallForeign(entry: DownloadEntry, appId: Int) {
        uninstallingName = entry.name
        Thread {
            // The imported mark goes and its ignore keeps the import from putting the card back; the Steam DB row of an owned game
            // goes too. Nothing here touches the files or the manifest.
            SteamLibraryImport.releaseImported(activity, appId)
            // Drop the record before the worker ends: a scan that snapshotted it can still seed it once, but the ignore checks in
            // scanLibraries/seed keep it gone after that.
            SteamLibraryFound.forget(activity, appId)
            runCatching { SteamRepository.getInstance().database.markUninstalled(appId) }
            activity.runOnUiThread {
                DownloadRegistry.removeTerminal(entry.key)
                // The folder and its exes stay, so the game's shortcuts still work: leave them, exactly as the Steam tab and the
                // game page do for a kept/imported game.
                uninstallingName = null
                notice = activity.getString(R.string.steam_lib_uninstall_kept, entry.name)
            }
        }.start()
    }

    /**
     * Per-store native install-record purge — the generalized seam so a cross-store uninstall
     * clears each store's OWN "installed" bookkeeping, not just Steam's DB. Steam is already
     * handled by the `mark` callback above (DB `markUninstalled`); this covers the storefronts
     * whose install-state lives outside a DB.
     */
    private fun purgeNativeInstall(entry: DownloadEntry) {
        when (entry.store) {
            Store.AMAZON -> AmazonInstallState.purge(activity, entry.id)
            Store.GOG -> GogInstallState.purge(activity, entry.id)
            Store.EPIC -> EpicInstallState.purge(activity, entry.id)
            Store.XBOX -> com.winlator.star.store.xbox.XboxInstallState.remove(activity, entry.id)
            Store.STEAM -> Unit   // handled via SteamRepository.markUninstalled in `mark`
        }
    }

    /**
     * Cancel a download from the Download Manager. For Epic this raises the same keep/delete dialog
     * as the detail page + library list (synced via [EpicCancelPolicy]); the running download
     * coroutine acts on the choice. Other stores cancel directly (their keep/delete semantics are
     * unchanged).
     */
    fun cancel(entry: DownloadEntry) {
        if (entry.store == Store.EPIC && EpicInstallState.isInstalled(activity, entry.id)) {
            // An update of an installed game: the keep / delete choice is about partial files, and the installed game stays whatever
            // is chosen, so there is nothing to ask (EpicDownloadControl settles it back to installed).
            entry.cancel?.invoke()
        } else if (entry.store == Store.EPIC) {
            AlertDialog.Builder(activity)
                .setTitle("Cancel download?")
                .setMessage("Keep the partial download so you can resume later, or delete all " +
                        "downloaded files for this game?")
                .setPositiveButton("Keep files") { _, _ ->
                    EpicCancelPolicy.setDeleteOnCancel(entry.id, false)
                    entry.cancel?.invoke()
                }
                .setNegativeButton("Delete files") { _, _ ->
                    EpicCancelPolicy.setDeleteOnCancel(entry.id, true)
                    entry.cancel?.invoke()
                }
                .setNeutralButton("Keep downloading", null)
                .show()
        } else if (entry.store == Store.STEAM) {
            // By appId through the queue, not the row's handles (those die with the run that attached them).
            SteamDownloadControl.cancel(activity.applicationContext, entry)
        } else {
            entry.cancel?.invoke()
        }
    }

    /** Pause a running download, or resume a paused one. */
    fun pauseResume(entry: DownloadEntry) {
        when (entry.store) {
            Store.STEAM -> SteamDownloadControl.pauseResume(activity.applicationContext, entry)
            Store.EPIC -> EpicDownloadControl.pauseResume(entry)
            else -> entry.pause?.invoke()   // the store's own handle toggles
        }
    }

    /** Drop a failed / cancelled row. */
    fun dismiss(entry: DownloadEntry) {
        DownloadRegistry.remove(entry.key)
    }

    /** Queue reorder (Steam-only; a no-op for any id that isn't a live queued Steam appId). */
    fun moveUp(entry: DownloadEntry) {
        if (entry.store != Store.STEAM) return   // GOG ids are numeric too
        entry.id.toIntOrNull()?.let { id -> DownloadQueue.moveUp(id) }
    }

    fun moveToTop(entry: DownloadEntry) {
        if (entry.store != Store.STEAM) return
        entry.id.toIntOrNull()?.let { id -> DownloadQueue.moveToTop(id) }
    }

    companion object {
        /**
         * Seeds the durable library so a screen has rows even if opened before any producer touched the registry this session, and
         * the non-Steam libraries too (they self-heal orphaned installs and surface update-available). Idempotent; never throws
         * into startup.
         */
        fun prepare(context: Context) {
            SteamPrefs.init(context)
            SteamRepository.getInstance().initialize(context)
            DownloadRegistry.init(context)
            // Steam downloads the app was interrupted in come back as paused rows (the registry is memory only).
            SteamDownloadControl.seedInterrupted()
            AmazonLibrarySync.seed(context)
            GogLibrarySync.seed(context)
            EpicLibrarySync.seed(context)
            com.winlator.star.store.xbox.XboxDownloads.seed(context)
            // Games installed in a Steam library folder by something else show in Downloads like a downloaded game (on a worker
            // thread: the records' folders and manifests are checked on disk). A user opening the page asks for it: the throttle
            // is skipped, so a game installed a moment ago is not hidden by a scan that ran just before it.
            SteamLibraryFound.scanIfStaleAsync(context, force = true)
        }
    }
}

/** The folder the keep rule checks: a found record's (authoritative while a seed runs) else the row's own. */
internal fun foundOrRowDir(ctx: Context, entry: DownloadEntry): File? {
    val fromRecord = if (entry.found && entry.store == Store.STEAM) {
        entry.id.toIntOrNull()?.let { SteamLibraryFound.record(ctx, it)?.installDir }
    } else null
    return (fromRecord?.takeIf { it.isNotEmpty() } ?: entry.installPath?.takeIf { it.isNotEmpty() })?.let(::File)
}

/**
 * True when uninstalling this card keeps the files: its game is another Steam client's ([SteamLibraryFound.keepsClientFiles]), or a
 * found card, whose record is the proof. The card labels and [DownloadActions.uninstall] both ask this, so they never disagree.
 * Blocking (preference and DB reads): off the UI thread, see [rememberClientFilesKept].
 */
internal fun clientFilesKept(ctx: Context, entry: DownloadEntry): Boolean =
    entry.store == Store.STEAM && entry.id.toIntOrNull()?.let { id ->
        entry.found || SteamLibraryFound.keepsClientFiles(ctx, id, foundOrRowDir(ctx, entry))
    } == true

/**
 * The keys of the [entries] whose files [clientFilesKept] keeps, for the uninstall labels. The exact set is computed on Dispatchers.IO
 * (the reads are blocking) and redone only when a keep-relevant field (state / found / installPath) changes, not on every progress tick.
 * Found and imported cards are known to keep their files up front, so the set starts with them instead of empty.
 */
@Composable
internal fun rememberClientFilesKept(entries: List<DownloadEntry>): Set<String> {
    val ctx = LocalContext.current.applicationContext
    // Only what can change the answer: a progress tick (pct / speed / bytes) must not re-run the blocking IO classification, so
    // the key is one keep signature per entry rather than the entries themselves.
    val signature = entries.map { "${it.key}:${it.state.name}:${it.found}:${it.installPath.orEmpty()}" }
    // The keeps already known without touching the disk: a found card always keeps its files, and an imported game is another
    // client's. Computed on the composition thread (prefs set membership is cheap) so the FIRST frame never labels those cards
    // "Uninstall"; the IO pass below then adds the games whose client files the library actually holds.
    val obvious = remember(signature) {
        entries.filter {
            it.found || (it.store == Store.STEAM && it.id.toIntOrNull()?.let { id -> SteamLibraryImport.isImported(ctx, id) } == true)
        }.map { it.key }.toSet()
    }
    val kept by produceState(initialValue = obvious, key1 = signature) {
        value = withContext(Dispatchers.IO) { entries.filter { clientFilesKept(ctx, it) }.map { it.key }.toSet() }
    }
    return kept
}

private data class ExePickerDataDm(
    val appId: Int,
    val gameName: String,
    val candidates: List<String>,
    val coverUrl: String,
    val epic: StarLaunchBridge.EpicMeta?,
)

@Composable
private fun ExePickerDialogDm(
    gameName: String,
    candidates: List<String>,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select executable for \"$gameName\"") },
        text = {
            // Cap at ~half the screen height so a long bin/*.exe list still scrolls to the
            // real game exe (same treatment as the Steam screens' exe pickers).
            val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
            Column(
                modifier = Modifier
                    .heightIn(max = maxListHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                candidates.forEach { path ->
                    val f = File(path)
                    val parent = f.parentFile
                    val label = if (parent != null) "${parent.name}/${f.name}" else f.name
                    TextButton(
                        onClick = { onSelected(path) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label, modifier = Modifier.weight(1f)) }
                }
            }
        },
        confirmButton = {},
    )
}
