package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.core.GameIdentifier
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadScope
import com.winlator.star.store.download.Store
import com.winlator.star.store.download.StoreDownloadHooks
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "+ Import game library" for Epic, GOG and Amazon (Steam has [SteamFolderAdopt]): the game folders inside a
 * picked folder are matched to owned, not-installed games, and each one is installed INTO its folder by the
 * store's normal download, which keeps the files already there that check out and fetches the rest:
 *  - Epic keeps a file whose size and SHA-1 match the manifest (a real repair).
 *  - GOG keeps a file whose size and MD5 match (gen1 games: size only). GOG names the game folder itself, so the
 *    folder must carry GOG's name for the game; otherwise the import stops and says which name to use.
 *  - Amazon keeps a file whose size matches (missing files are filled in; a changed file of the same size is not
 *    detected).
 * Every folder gets [SteamInstallPaths.ADOPTED_MARKER] first, so no cancel or uninstall deletes it. Imports run
 * one at a time (the Epic engine shares its progress counters).
 */
object StoreFolderImport {
    private const val TAG = "StoreFolderImport"
    private val serial = Mutex()

    class Game(val id: String, val name: String, val keys: List<String>)
    class Found(val folder: File, val game: Game?)
    class Result(val found: List<Found>, val games: List<Game>)

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    private fun sanitize(title: String) = title.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim()
    private fun prefs(ctx: Context, name: String) = ctx.applicationContext.getSharedPreferences(name, 0)

    /** Owned, not installed, not downloading games of [store]. Blocking. */
    fun candidates(ctx: Context, store: StoreInstallLocations.Store): List<Game> {
        val app = ctx.applicationContext
        fun busy(s: Store, id: String) = try { DownloadRegistry.isActive("$s:$id") } catch (_: Throwable) { false }
        return when (store) {
            StoreInstallLocations.Store.EPIC -> EpicLibraryRepo.cached(app)
                .filter { it.appName.isNotBlank() && !EpicInstallState.isInstalled(app, it.appName) && !busy(Store.EPIC, it.appName) }
                .map { Game(it.appName, it.title.ifBlank { it.appName }, listOf(it.title, sanitize(it.title), it.appName)) }
            StoreInstallLocations.Store.AMAZON -> {
                val p = prefs(app, "bh_amazon_prefs")
                AmazonLibraryRepo.cached(app)
                    .filter { it.productId.isNotBlank() && p.getString("amazon_exe_${it.productId}", null).isNullOrBlank() && !busy(Store.AMAZON, it.productId) }
                    .map { Game(it.productId, it.title.ifBlank { it.productId }, listOf(it.title, sanitize(it.title))) }
            }
            StoreInstallLocations.Store.GOG -> GogLibraryRepo.cached(app)
                .filter { it.gameId.isNotBlank() && !GogInstallState.isInstalled(app, it.gameId) && !busy(Store.GOG, it.gameId) }
                .map { g ->
                    val en = try { GogTitles.english(app, g) } catch (_: Throwable) { g.title }
                    Game(g.gameId, g.title.ifBlank { en }, listOf(g.title, en))
                }
        }.sortedBy { it.name.lowercase() }
    }

    /** A store id written in the folder itself (GOG: goggame-<id>.info or Bannerlator's _gog_manifest.json). */
    private fun idInFolder(store: StoreInstallLocations.Store, dir: File): String? {
        if (store != StoreInstallLocations.Store.GOG) return null
        dir.listFiles { f -> f.isFile && f.name.startsWith("goggame-") && f.name.endsWith(".info") }?.firstOrNull()?.let {
            return it.name.removePrefix("goggame-").removeSuffix(".info").takeIf { s -> s.all(Char::isDigit) }
        }
        val m = File(dir, "_gog_manifest.json")
        if (m.isFile) return runCatching { JSONObject(m.readText()).optString("gameId") }.getOrNull()?.takeIf { it.isNotBlank() }
        return null
    }

    /** Matches the folders in [picked] (or [picked] itself when it is one game's folder) to [store]'s games. Blocking. */
    fun scan(ctx: Context, store: StoreInstallLocations.Store, picked: File): Result {
        val games = candidates(ctx, store)
        val byId = games.associateBy { it.id }
        val byKey = HashMap<String, MutableSet<String>>()
        for (g in games) for (k in g.keys) {
            val n = norm(k)
            if (n.length >= 2) byKey.getOrPut(n) { HashSet() }.add(g.id)
        }
        fun match(dir: File): Found {
            idInFolder(store, dir)?.let { id -> byId[id]?.let { return Found(dir, it) } }
            val ids = byKey[norm(dir.name)]
            if (ids != null && ids.size == 1) byId[ids.first()]?.let { return Found(dir, it) }
            return Found(dir, null)
        }
        val self = match(picked)
        val found = if (self.game != null) listOf(self) else
            (picked.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()).map(::match)
        val used = HashSet<String>()
        val unique = found.map { f -> val g = f.game; if (g == null || used.add(g.id)) f else Found(f.folder, null) }
        Log.i(TAG, "scan ${store.key} ${picked.path}: ${unique.count { it.game != null }} matched, ${unique.count { it.game == null }} not matched")
        return Result(unique, games)
    }

    /**
     * Installs [game] into [folder] in the background (queued behind other imports). Returns an error message for a
     * folder that can't be used, or null when the import was queued; progress shows in the Download Manager.
     */
    fun adopt(ctx: Context, store: StoreInstallLocations.Store, game: Game, folder: File): String? {
        val app = ctx.applicationContext
        SteamFolderAdopt.refuseReason(folder)?.let { return it }
        try { File(folder, SteamInstallPaths.ADOPTED_MARKER).writeText("${store.key}:${game.id}\n") } catch (t: Throwable) {
            return "Bannerlator can't write to that folder."
        }
        val s = when (store) {
            StoreInstallLocations.Store.EPIC -> Store.EPIC
            StoreInstallLocations.Store.GOG -> Store.GOG
            StoreInstallLocations.Store.AMAZON -> Store.AMAZON
        }
        val cancelled = AtomicBoolean(false)
        // Registered before any install record is written: a store's startup check drops records of folders
        // without a complete install unless a download owns them.
        StoreDownloadHooks.registerDownload(store = s, id = game.id, name = game.name, cancel = { cancelled.set(true) })
        DownloadScope.io.launch {
            serial.withLock {
                try {
                    when (store) {
                        StoreInstallLocations.Store.EPIC -> runEpic(app, game, folder, cancelled)
                        StoreInstallLocations.Store.AMAZON -> runAmazon(app, game, folder, cancelled)
                        StoreInstallLocations.Store.GOG -> runGog(app, game, folder, cancelled)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "import ${store.key}:${game.id} failed", t)
                    StoreDownloadHooks.markFailed(s, game.id, t.message ?: "Import failed")
                }
            }
        }
        return null
    }

    private fun bestExe(folder: File, title: String): File? {
        val exes = mutableListOf<File>()
        AmazonLaunchHelper.collectExe(folder, exes)
        val lower = title.lowercase()
        return exes.maxByOrNull { AmazonLaunchHelper.scoreExe(it, lower) }
    }

    private fun runEpic(app: Context, game: Game, folder: File, cancelled: AtomicBoolean) {
        val p = prefs(app, "bh_epic_prefs")
        val eg = EpicLibraryRepo.cached(app).firstOrNull { it.appName == game.id }
            ?: return StoreDownloadHooks.markFailed(Store.EPIC, game.id, "Not in your Epic library")
        p.edit().putString("epic_dir_${game.id}", folder.absolutePath).apply()
        val token = EpicCredentialStore.getValidAccessToken(app)
            ?: return StoreDownloadHooks.markFailed(Store.EPIC, game.id, "Login required")
        val manifest = EpicApiClient.getManifestApiJson(token, eg.namespace, eg.catalogItemId, eg.appName)
            ?: return StoreDownloadHooks.markFailed(Store.EPIC, game.id, "Failed to fetch manifest")
        val tags = EpicInstallTags.tagsForCurrentContainer(app)
        val ok = EpicDownloadManager.install(app, manifest, token, folder.absolutePath, tags, cancelled) { _, pct ->
            if (!cancelled.get()) StoreDownloadHooks.tick(Store.EPIC, game.id, pct,
                installDone = EpicDownloadManager.progressDone, installTotal = EpicDownloadManager.progressTotal)
        }
        if (cancelled.get()) {
            EpicCancelPolicy.consumeDeleteOnCancel(game.id)   // the user's folder is never deleted
            return StoreDownloadHooks.markCancelled(Store.EPIC, game.id)
        }
        if (!ok) return StoreDownloadHooks.markFailed(Store.EPIC, game.id, EpicDownloadManager.withFailureReason("Download failed"))
        EpicInstallState.rememberVersion(app, game.id, manifest)
        val exe = bestExe(folder, eg.title) ?: return StoreDownloadHooks.markFailed(Store.EPIC, game.id, "No executable found")
        p.edit().putString("epic_exe_${game.id}", exe.absolutePath).apply()
        EpicEosDetector.scanAsync(app, game.id, folder, null)
        StoreDownloadHooks.markInstalled(Store.EPIC, game.id, folder.absolutePath, p.getLong("epic_size_${game.id}", 0L))
        Log.i(TAG, "epic import ${game.id} → ${folder.path} done")
    }

    private fun runAmazon(app: Context, game: Game, folder: File, cancelled: AtomicBoolean) {
        val p = prefs(app, "bh_amazon_prefs")
        val ag = AmazonLibraryRepo.cached(app).firstOrNull { it.productId == game.id }
            ?: return StoreDownloadHooks.markFailed(Store.AMAZON, game.id, "Not in your Amazon library")
        p.edit().putString("amazon_dir_${game.id}", folder.absolutePath).apply()
        val token = AmazonCredentialStore.getValidAccessToken(app)
            ?: return StoreDownloadHooks.markFailed(Store.AMAZON, game.id, "Login required")
        val ok = AmazonDownloadManager.install(app, ag, token, folder, { dl, total, _ ->
            if (!cancelled.get()) {
                val pct = if (total > 0) (dl * 100L / total).toInt() else 0
                StoreDownloadHooks.tick(Store.AMAZON, game.id, pct, installDone = dl, installTotal = total)
            }
        }, { cancelled.get() })
        if (cancelled.get()) return StoreDownloadHooks.markCancelled(Store.AMAZON, game.id)
        if (!ok) return StoreDownloadHooks.markFailed(Store.AMAZON, game.id, "Download failed")
        val exe = bestExe(folder, ag.title) ?: return StoreDownloadHooks.markFailed(Store.AMAZON, game.id, "No executable found")
        p.edit().putString("amazon_exe_${game.id}", exe.absolutePath).apply()
        StoreDownloadHooks.markInstalled(Store.AMAZON, game.id, folder.absolutePath, p.getLong("amazon_size_${game.id}", 0L))
        Log.i(TAG, "amazon import ${game.id} → ${folder.path} done")
    }

    private suspend fun runGog(app: Context, game: Game, folder: File, cancelled: AtomicBoolean) {
        val gg = GogLibraryRepo.cached(app).firstOrNull { it.gameId == game.id }
            ?: return StoreDownloadHooks.markFailed(Store.GOG, game.id, "Not in your GOG library")
        val p = prefs(app, "bh_gog_prefs")
        // GOG names the folder itself: the download goes to <parent>/<GOG's name> and only proceeds when that is
        // this folder (GogInstallPath.claimPendingBase refuses otherwise, see the engine).
        GogInstallPath.setPendingAdopt(game.id, folder)
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancel = GogDownloadManager.startDownload(app, gg, object : GogDownloadManager.Callback {
            override fun onProgress(msg: String, pct: Int) {
                if (!cancelled.get()) StoreDownloadHooks.tick(Store.GOG, game.id, pct)
            }
            override fun onBytes(done: Long, total: Long, speedBps: Long, etaSeconds: Long) {
                if (!cancelled.get() && total > 0) StoreDownloadHooks.tick(Store.GOG, game.id, (done * 100L / total).toInt(), installDone = done, installTotal = total)
            }
            override fun onComplete(exePath: String) {
                val exe = p.getString("gog_exe_${game.id}", null)
                val dir = p.getString("gog_dir_${game.id}", null)
                if (exe != null && dir != null) {
                    StoreDownloadHooks.markInstalled(Store.GOG, game.id, GogInstallPath.getInstallDir(app, dir).absolutePath, p.getLong("gog_size_${game.id}", 0L))
                } else {
                    StoreDownloadHooks.markFailed(Store.GOG, game.id, "No executable found")
                }
                done.complete(Unit)
            }
            override fun onError(msg: String) { StoreDownloadHooks.markFailed(Store.GOG, game.id, msg); done.complete(Unit) }
            override fun onCancelled() { StoreDownloadHooks.markCancelled(Store.GOG, game.id); done.complete(Unit) }
            override fun onSelectExe(candidates: MutableList<String>?, onSelected: java.util.function.Consumer<String>?) {
                onSelected?.accept(candidates?.firstOrNull() ?: "")
            }
        })
        // The Download Manager's cancel flips [cancelled]; hand it to the GOG engine (its cancel keeps the folder:
        // a game folder with the adopted marker is never deleted).
        while (!done.isCompleted) {
            if (cancelled.get()) { cancel.run(); break }
            kotlinx.coroutines.delay(500)
        }
        done.await()
    }
}
