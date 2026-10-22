package com.winlator.star.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.winlator.star.container.Shortcut
import com.winlator.star.core.FileUtils
import com.winlator.star.core.WinePath
import com.winlator.star.store.steamscript.InstallScriptExecutor
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Chosen Goldberg (gbe_fork) Steam-emulator mode for one installed game,
 * persisted per-appId in [SteamPrefs].
 *
 * The tiers are escalating fallbacks — try them in order if a game still
 * refuses to start:
 *  - OFF          — pristine game files, no emulation (real Steam required).
 *  - REGULAR      — swap steam_api(64).dll for Goldberg's api-shim only.
 *  - EXPERIMENTAL — Goldberg api-shim + a local steamclient(64).dll beside it.
 *  - COLDCLIENT   — leave the original api dll, launch through Goldberg's
 *                   ColdClientLoader (steamclient_loader_x64.exe) instead.
 */
enum class GoldbergMode {
    OFF, REGULAR, EXPERIMENTAL, COLDCLIENT;

    companion object {
        /** Parse a persisted enum name; unknown/absent → OFF. */
        @JvmStatic
        fun fromKey(name: String?): GoldbergMode =
            values().firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * Applies the Goldberg (gbe_fork) Steam emulator to an installed Steam game so
 * DRM/ownership-gated titles start without a running Steam client.
 *
 * IMPORTANT scope note (baked into the UI copy too): Goldberg only lets a game
 * that refuses to start without Steam *start*. It CANNOT make an online-only
 * game reach its publisher's servers — those still fail on their own.
 *
 * All work is pure file I/O under the app's private imagefs. Public entry
 * points hop to a worker thread and deliver the result back on the main thread.
 *
 * The Goldberg binaries are DOWNLOADED ON DEMAND from the winlator-contents
 * catalog (see [GoldbergComponent], which mirrors the ReShade delivery model) —
 * they are not bundled in the APK. Tier selection is gated behind that download
 * in the UI; the patcher also guards defensively and reports a clear message if
 * the component isn't installed yet.
 *
 * Every apply keeps a human-readable report (also logged to logcat under BH_GOLDBERG and persisted
 * for [GoldbergDiag]) so a launch that goes wrong can be diagnosed afterwards.
 */
object GoldbergPatcher {

    private const val TAG = "BH_GOLDBERG"

    /** How long an achievement-schema fetch may delay an apply / a launch. */
    const val SCHEMA_TIMEOUT_MS = 10_000L

    /** Don't re-try a schema fetch for a game with no cached definitions more often than this. */
    private const val SCHEMA_RETRY_MS = 6L * 60 * 60 * 1000

    /** Delivered on the main thread. */
    fun interface Callback {
        fun onResult(success: Boolean, message: String)
    }

    /** PE architecture of a steam_api dll — decides which Goldberg dll to use. */
    enum class Arch(
        val apiDll: String,
        val steamclientDll: String,
        val loaderExe: String,
        /** gbe_fork's own name for the 32-bit loader is steamclient_loader_x32.exe. */
        val loaderAlias: String? = null,
    ) {
        X64("steam_api64.dll", "steamclient64.dll", "steamclient_loader_x64.exe"),
        X86("steam_api.dll", "steamclient.dll", "steamclient_loader_x86.exe", "steamclient_loader_x32.exe"),
    }

    /** One steam_api dll found inside a game install, plus its detected arch. */
    data class PatchTarget(val dll: File, val arch: Arch) {
        val bakFile: File get() = File(dll.parentFile, dll.name + ".bak")
        val dir: File get() = dll.parentFile!!
    }

    /** Running log of one apply: goes to logcat AND to the persisted report. */
    private class Report {
        val lines = ArrayList<String>()
        fun add(msg: String) { lines.add(msg); Log.i(TAG, msg) }
        fun warn(msg: String) { lines.add("WARN: $msg"); Log.w(TAG, msg) }
    }

    /** What [resolveLaunch] decided the guest should run. */
    class LaunchResolution(
        @JvmField val exe: File,
        @JvmField val usedLoader: Boolean,
        @JvmField val note: String,
    )

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Applies [mode] to the game installed at [installDir] (worker thread).
     *
     * Runs SHARED PREP the first time (permanent .bak of every steam_api dll +
     * generated steam_interfaces.txt / steam_appid.txt), then applies the tier.
     * OFF restores the game to pristine. Persists the chosen mode via
     * [SteamPrefs] on success. Never throws to the caller — failures come back
     * through [cb] with a human message.
     */
    @JvmStatic
    fun applyModeAsync(
        context: Context,
        appId: Int,
        installDir: String,
        gameName: String,
        mode: GoldbergMode,
        cb: Callback,
    ) {
        applyModeAsync(context, appId, installDir, gameName, mode, null, cb)
    }

    /** As above, plus the game exe the shortcut really launches ([exePath]; COLDCLIENT places its loader beside it). */
    @JvmStatic
    fun applyModeAsync(
        context: Context,
        appId: Int,
        installDir: String,
        gameName: String,
        mode: GoldbergMode,
        exePath: String?,
        cb: Callback,
    ) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val r = runApply(appContext, appId, installDir, gameName, mode, exePath)
            main.post { cb.onResult(r.first, r.second) }
        }, "goldberg-patch").start()
    }

    /**
     * The launch-popup entry point. Resolves the game's appId + install dir + exe from the SHORTCUT
     * (extras first, then the installed-games DB and the exe path — a legacy shortcut without a
     * `steamAppId` still works), stamps `launchMode=Goldberg` on the shortcut (so every route in —
     * popup, preflight fallback, fail-card relaunch — really launches as Goldberg), applies [mode],
     * and reports the REAL result on the main thread. Never throws. A null [mode] means "the tier saved
     * for the game" (Off maps to Regular): it is looked up with the RESOLVED appId, so a legacy shortcut
     * without a `steamAppId` keeps its Experimental / Cold Client choice instead of being reset to Regular.
     */
    @JvmStatic
    fun applyForShortcutAsync(context: Context, shortcut: Shortcut, mode: GoldbergMode?, cb: Callback) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            var ok = false
            var msg = ""
            try {
                // A launch routed here IS a Goldberg launch, whatever the shortcut said before.
                try {
                    if (shortcut.getExtra("launchMode") != "Goldberg") {
                        shortcut.putExtra("launchMode", "Goldberg")
                        shortcut.saveData()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "couldn't stamp launchMode=Goldberg on ${shortcut.name}", t)
                }
                val target = resolveTarget(appContext, shortcut)
                Log.i(TAG, "launch target for '${shortcut.name}': appId=${target.appId} " +
                    "installDir=${target.installDir.ifEmpty { "<none>" }} exe=${target.exe?.path ?: "<unresolved>"}")
                if (target.appId <= 0) {
                    msg = "Goldberg: couldn't work out this game's Steam app id — launching WITHOUT the emulator. " +
                        "Re-add the game from the Steam library."
                    Log.w(TAG, msg)
                } else {
                    SteamPrefs.init(appContext)
                    val effMode = mode ?: resumeTier(appContext, target.appId)
                    // The tier is saved by runApply only once the patch has really worked.
                    if (target.installDir.isEmpty()) {
                        msg = "Goldberg: couldn't find this game's install folder — launching WITHOUT the emulator."
                        Log.w(TAG, msg)
                    } else {
                        val exeFile = target.exe
                        val r = runApply(
                            appContext, target.appId, target.installDir, shortcut.name, effMode,
                            if (exeFile != null && exeFile.isFile) exeFile.absolutePath else null,
                        )
                        ok = r.first
                        msg = r.second
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "applyForShortcut failed", t)
                msg = "Goldberg patch failed: ${t.message}"
            }
            val fOk = ok
            val fMsg = msg
            main.post { cb.onResult(fOk, fMsg) }
        }, "goldberg-apply-launch").start()
    }

    /** Wraps [applyModeBlocking]: logs, never throws, persists mode on success + the report always. */
    private fun runApply(
        appContext: Context,
        appId: Int,
        installDir: String,
        gameName: String,
        mode: GoldbergMode,
        exePath: String?,
    ): Pair<Boolean, String> {
        val rep = Report()
        val result: Pair<Boolean, String> = try {
            applyModeBlocking(appContext, appId, installDir, gameName, mode, exePath, rep)
        } catch (e: Exception) {
            Log.e(TAG, "applyMode failed", e)
            rep.warn("EXCEPTION: $e")
            Pair(false, "Goldberg patch failed: ${e.message}")
        }
        rep.add("apply result: ok=${result.first} — ${result.second.replace('\n', ' ')}")
        if (result.first) {
            SteamPrefs.init(appContext)
            SteamPrefs.setGoldbergMode(appId, mode)
        }
        GoldbergDiag.recordApply(appContext, appId, rep.lines)
        return result
    }

    /**
     * The path the guest should execute for a shortcut whose exe is [exePath] (host path).
     * See [resolveLaunch]; this keeps the string API the shortcut-writers use.
     */
    @JvmStatic
    fun resolveLaunchExe(context: Context, appId: Int, exePath: String): String =
        resolveLaunch(context, appId, exePath).exe.absolutePath

    /**
     * Decides what to actually run for the game exe [exePath]:
     *  - COLDCLIENT → the Goldberg loader beside the exe (matching the exe's arch; x32/x86 name accepted);
     *    when no loader is there the game exe comes back unchanged with a LOUD [LaunchResolution.note]
     *    (in that case Goldberg is NOT in effect — the original steam_api was restored).
     *  - a shortcut that still points at a loader which no longer exists (tier switched away from
     *    COLDCLIENT) → the biggest other exe in the same folder.
     *  - everything else → [exePath] unchanged.
     * Synchronous; assumes a worker thread. Never throws.
     */
    @JvmStatic
    fun resolveLaunch(context: Context, appId: Int, exePath: String): LaunchResolution {
        val exe = File(exePath)
        try {
            SteamPrefs.init(context.applicationContext)
            val dir = exe.parentFile ?: return LaunchResolution(exe, false, "exe has no parent dir")
            val isLoader = exe.name.startsWith("steamclient_loader", ignoreCase = true)
            if (isLoader && !exe.isFile) {
                val siblings = dir.listFiles() ?: emptyArray<File>()
                var alt: File? = null
                for (f in siblings) {
                    if (!f.isFile || !f.name.endsWith(".exe", ignoreCase = true)) continue
                    if (f.name.startsWith("steamclient_loader", ignoreCase = true)) continue
                    if (alt == null || f.length() > alt.length()) alt = f
                }
                return if (alt != null) {
                    Log.w(TAG, "shortcut points at missing loader ${exe.name}; using ${alt.name}")
                    LaunchResolution(alt, false, "shortcut pointed at a loader that no longer exists (${exe.name}); using ${alt.name}")
                } else {
                    LaunchResolution(exe, false, "shortcut points at ${exe.name} which does not exist and no other exe is beside it")
                }
            }
            if (isLoader) return LaunchResolution(exe, true, "shortcut already targets loader ${exe.name}")
            val mode = SteamPrefs.getGoldbergMode(appId)
            if (mode != GoldbergMode.COLDCLIENT) return LaunchResolution(exe, false, "tier=$mode (no loader needed)")
            // Prefer the loader matching the game exe's arch.
            val arch = detectArch(exe)
            val loader: File? = if (arch != null) findLoader(dir, arch)
            else (findLoader(dir, Arch.X64) ?: findLoader(dir, Arch.X86))
            if (loader != null) {
                // The loader reads its target from ColdClientLoader.ini; without it the loader would start nothing.
                if (!File(loader.parentFile, "ColdClientLoader.ini").isFile) {
                    val noIni = "ColdClient is selected but ColdClientLoader.ini is missing beside ${loader.name} — " +
                        "launching the game exe directly, Goldberg is NOT active"
                    Log.w(TAG, noIni)
                    return LaunchResolution(exe, false, noIni)
                }
                // The loader starts whichever program its ini names. A shortcut for a DIFFERENT program in the
                // same folder (a launcher, a settings tool) must start that program, not the main game.
                val target = loaderIniExeName(loader.parentFile)
                if (target != null && !target.equals(exe.name, ignoreCase = true)) {
                    return LaunchResolution(exe, false, "${exe.name} is not the game the Cold Client loader starts ($target) — launching it directly")
                }
                return LaunchResolution(loader, true, "ColdClient: launching ${loader.name} (game exe ${exe.name}, arch=$arch)")
            }
            val msg = "ColdClient is selected but no ${arch?.loaderExe ?: "steamclient_loader_*.exe"} is beside " +
                "${exe.name} — launching the game exe directly, Goldberg is NOT active"
            Log.w(TAG, msg)
            return LaunchResolution(exe, false, msg)
        } catch (e: Exception) {
            Log.w(TAG, "resolveLaunch failed, using original exe", e)
            return LaunchResolution(exe, false, "resolveLaunch threw: $e")
        }
    }

    /** The host-side game exe a shortcut launches, or null when its path can't be mapped. */
    @JvmStatic
    fun hostExeOf(shortcut: Shortcut): File? {
        try {
            var p: String = shortcut.path ?: return null
            p = p.trim()
            // The shortcut path may carry trailing args after the exe.
            val i = p.lowercase().indexOf(".exe")
            if (i > 0) p = p.substring(0, i + 4)
            return WinePath.resolveAndroidPath(shortcut.container, p)
        } catch (e: Exception) {
            Log.w(TAG, "hostExeOf failed for ${shortcut.name}", e)
            return null
        }
    }

    private fun findLoader(dir: File, arch: Arch): File? {
        val names = listOfNotNull(arch.loaderExe, arch.loaderAlias)
        for (n in names) {
            val f = File(dir, n)
            if (f.isFile) return f
        }
        return null
    }

    private class Target(val appId: Int, val installDir: String, val exe: File?)

    /** appId + install dir + exe for [sc]: extras → DB → exe path → steam_appid.txt. */
    private fun resolveTarget(ctx: Context, sc: Shortcut): Target {
        var appId = sc.getExtra("steamAppId", "").trim().toIntOrNull() ?: 0
        val exe = hostExeOf(sc)
        var installDir = ""
        val db: SteamDatabase? = try { SteamDatabase.getInstance(ctx) } catch (_: Throwable) { null }
        if (appId > 0) {
            val fromDb: String? = try { db?.getGame(appId)?.installDir } catch (_: Throwable) { null }
            installDir = fromDb.orEmpty()
        }
        if ((appId <= 0 || installDir.isEmpty()) && exe != null) {
            val depot: File? = try { InstallScriptExecutor.locateInstallDir(exe) } catch (_: Throwable) { null }
            if (depot != null) {
                val dp = depot.absolutePath.trimEnd('/')
                val fetched: List<SteamDatabase.GameRow>? = try { db?.getInstalledGames() } catch (_: Throwable) { null }
                val rows: List<SteamDatabase.GameRow> = fetched ?: emptyList()
                var row: SteamDatabase.GameRow? = null
                for (r in rows) {
                    val d = r.installDir ?: continue
                    if (d.trimEnd('/').equals(dp, ignoreCase = true)) { row = r; break }
                }
                if (row == null) {
                    for (r in rows) {
                        val d = r.installDir ?: continue
                        if (File(d).name.equals(depot.name, ignoreCase = true)) { row = r; break }
                    }
                }
                if (row != null) {
                    if (appId <= 0) appId = row.appId
                    if (installDir.isEmpty()) installDir = row.installDir.orEmpty()
                }
                // No DB row (e.g. a folder import): the depot dir we located is still the folder to patch.
                if (installDir.isEmpty()) installDir = dp
            }
        }
        if (appId <= 0) {
            // Last resort: a steam_appid.txt left in the game (ours, or the game's own).
            val dirs = ArrayList<File>()
            exe?.parentFile?.let { dirs.add(it) }
            if (installDir.isNotEmpty()) dirs.add(File(installDir))
            for (d in dirs) {
                for (c in arrayOf(File(d, "steam_appid.txt"), File(d, "steam_settings/steam_appid.txt"))) {
                    val v: Int? = try {
                        if (c.isFile) c.readText().trim().toIntOrNull() else null
                    } catch (_: Throwable) { null }
                    if (v != null && v > 0) { appId = v; break }
                }
                if (appId > 0) break
            }
        }
        return Target(appId, installDir, exe)
    }

    // ── Core (blocking) ──────────────────────────────────────────────────────

    private fun applyModeBlocking(
        context: Context,
        appId: Int,
        installDir: String,
        gameName: String,
        mode: GoldbergMode,
        exePath: String?,
        rep: Report,
    ): Pair<Boolean, String> {
        rep.add("apply start: appId=$appId mode=$mode game='$gameName' installDir=$installDir exe=${exePath ?: "-"}")
        val root = File(installDir)
        if (!root.isDirectory) {
            rep.warn("install dir is not a directory")
            return Pair(false, "Install directory not found.")
        }

        val targets = analyze(root)
        rep.add("steam_api dlls found: ${targets.size}" +
            targets.joinToString(prefix = " [", postfix = "]") { "${it.dll.path.removePrefix(root.path)} (${it.arch})" })
        if (targets.isEmpty()) {
            rep.warn("no steam_api(64).dll in the install — nothing to patch")
            return Pair(false, "This game doesn't use the Steam API — Goldberg doesn't apply.")
        }

        // A game update / Verify may have replaced the original dll since the backup was made: refresh it first.
        refreshStaleBackups(targets, rep)

        // SHARED PREP is idempotent and needed for every non-OFF tier (and does
        // no harm before a restore). It refuses to go on if a pristine backup can't be made —
        // overwriting a dll with no .bak would make the swap irreversible.
        if (!sharedPrep(context, targets, appId, rep, fetchSchema = mode != GoldbergMode.OFF)) {
            return Pair(false, "Couldn't back up the game's original steam_api dll — Goldberg was NOT applied.")
        }

        // GOLDEN RULE: always restore pristine dlls from .bak first, so switching
        // tiers never stacks Goldberg dlls on top of each other.
        if (!restoreDlls(targets, rep)) {
            return Pair(false, "Couldn't restore the game's original steam_api dll — Goldberg was NOT applied.")
        }

        // Leaving Cold Client: drop its loader, ini and overlay so an old loader shortcut can't keep using them.
        if (mode != GoldbergMode.COLDCLIENT) removeColdClientFiles(targets, root, gameName, exePath)

        if (mode == GoldbergMode.OFF) {
            removeAddedFiles(targets, root, gameName)
            rep.add("OFF: restored pristine files and removed Goldberg additions")
            return Pair(true, "Goldberg turned off — \"$gameName\" restored to its original files.")
        }

        // The downloaded Goldberg component is required from here on. The UI gates
        // tier selection behind the download, but guard defensively anyway.
        if (!GoldbergComponent.isInstalled(context)) {
            rep.warn("Goldberg component not installed")
            return Pair(false, "Download the Steam Emulator component first.")
        }
        val goldberg = GoldbergComponent.installDir(context)
        rep.add("component: ${goldberg.path} version=${GoldbergComponent.installedVersion(context)}")

        val applied: Pair<Boolean, String> = try {
            when (mode) {
                GoldbergMode.REGULAR -> applyRegular(targets, goldberg, gameName, rep)
                GoldbergMode.EXPERIMENTAL -> applyExperimental(targets, goldberg, gameName, rep)
                GoldbergMode.COLDCLIENT -> applyColdClient(targets, goldberg, root, appId, gameName, exePath, rep)
                GoldbergMode.OFF -> Pair(true, "") // unreachable
            }
        } catch (e: Exception) {
            Log.e(TAG, "apply step threw", e)
            rep.warn("EXCEPTION while applying: $e")
            Pair(false, "Goldberg patch failed: ${e.message}")
        }
        // A step that failed part-way must not leave the game half patched (one dll swapped, the next not).
        if (!applied.first) rollbackFailedApply(targets, root, gameName, rep)
        return applied
    }

    private fun applyRegular(
        targets: List<PatchTarget>, goldberg: File, gameName: String, rep: Report,
    ): Pair<Boolean, String> {
        for (t in targets) {
            val src = File(goldberg, "regular/${archDir(t.arch)}/${t.arch.apiDll}")
            if (!src.isFile) {
                rep.warn("missing ${src.path}")
                return Pair(false, "Missing bundled ${src.name} (regular/${archDir(t.arch)}).")
            }
            if (!copyChecked(src, t.dll, rep)) {
                return Pair(false, "Couldn't write ${t.dll.name} into the game folder (storage full or read-only?).")
            }
            rep.add("regular: ${t.dll.path} <- ${src.path} (${src.length()} bytes, ${t.arch})")
        }
        return Pair(true, "Regular Goldberg applied to \"$gameName\". Launch it — if it still won't start, try Experimental.")
    }

    private fun applyExperimental(
        targets: List<PatchTarget>, goldberg: File, gameName: String, rep: Report,
    ): Pair<Boolean, String> {
        for (t in targets) {
            val api = File(goldberg, "experimental/${archDir(t.arch)}/${t.arch.apiDll}")
            val client = File(goldberg, "experimental/${archDir(t.arch)}/${t.arch.steamclientDll}")
            if (!api.isFile) {
                rep.warn("missing ${api.path}")
                return Pair(false, "Missing bundled ${api.name} (experimental/${archDir(t.arch)}).")
            }
            if (!copyChecked(api, t.dll, rep)) {
                return Pair(false, "Couldn't write ${t.dll.name} into the game folder (storage full or read-only?).")
            }
            if (client.isFile) {
                // Back up a game-shipped steamclient before overwriting it, so
                // restore/tier-switch can put the original back (some games — e.g.
                // Portal 2 — ship their own steamclient.dll).
                val dest = File(t.dir, t.arch.steamclientDll)
                if (!backupIfOriginal(dest, rep, client)) {
                    return Pair(false, "Couldn't back up the game's own ${dest.name} — Goldberg was NOT applied.")
                }
                if (!copyChecked(client, dest, rep)) {
                    return Pair(false, "Couldn't write ${dest.name} into the game folder (storage full or read-only?).")
                }
            } else {
                rep.warn("experimental build has no ${client.name} for ${t.arch} — api dll only")
            }
            rep.add("experimental: ${t.dll.path} <- ${api.path} (${api.length()} bytes, ${t.arch})")
        }
        return Pair(true, "Experimental Goldberg applied to \"$gameName\". Launch it — if it still won't start, try Cold Client Loader.")
    }

    private fun applyColdClient(
        targets: List<PatchTarget>, goldberg: File, root: File, appId: Int, gameName: String,
        exePath: String?, rep: Report,
    ): Pair<Boolean, String> {
        // Leave the restored ORIGINAL steam_api dll in place (already restored).
        val loaderSrc = File(goldberg, "steamclient_experimental")
        if (!loaderSrc.isDirectory) {
            rep.warn("missing ${loaderSrc.path}")
            return Pair(false, "Missing bundled steamclient_experimental loader files.")
        }

        // Cold Client Loader must sit beside the exe the shortcut really launches. Prefer that exe;
        // fall back to the heuristic scorer only when the caller didn't know it.
        var given: File? = null
        if (exePath != null) {
            val g = File(exePath)
            if (g.isFile) given = g
        }
        // A shortcut made while Cold Client was already selected points at the LOADER, not the game.
        // Writing `Exe=<loader>` would make the loader launch itself, so recover the real game exe from
        // the ColdClientLoader.ini that sits beside it (or fall back to the heuristic pick below).
        if (given != null && given.name.startsWith("steamclient_loader", ignoreCase = true)) {
            val real = gameExeFromLoaderIni(given.parentFile)
            if (real != null) {
                rep.add("coldclient: shortcut points at the loader ${given.name}; the game exe is ${real.name}")
                given = real
            } else {
                rep.warn("shortcut points at the loader ${given.name} and no usable ColdClientLoader.ini is beside it — using heuristic pick")
                given = null
            }
        }
        val gameExe: File = given
            ?: AmazonLaunchHelper.choosePrimaryExe(root, gameName)
            ?: return Pair(false, "Couldn't find a game .exe to attach the Cold Client Loader to.")
        if (gameExe.name.startsWith("steamclient_loader", ignoreCase = true)) {
            return Pair(false, "Couldn't find the game .exe (only the Cold Client loader was found).")
        }
        if (given == null) rep.warn("shortcut exe unknown ($exePath) — using heuristic pick ${gameExe.path}")
        val exeDir = gameExe.parentFile ?: root
        rep.add("coldclient: exe=${gameExe.path} dir=${exeDir.path}")

        // Copy every loader support file (steamclient dlls, loader exes,
        // GameOverlayRenderer, etc.) next to the game exe, EXCEPT the template
        // ini which we generate below.
        val files = loaderSrc.listFiles() ?: emptyArray<File>()
        for (f in files) {
            if (!f.isFile) continue
            if (f.name.equals("ColdClientLoader.ini", ignoreCase = true)) continue
            // Back up any game-shipped file we're about to clobber (loader set
            // includes steamclient dlls that can collide with the game's own).
            val dest = File(exeDir, f.name)
            if (!backupIfOriginal(dest, rep, f)) {
                return Pair(false, "Couldn't back up the game's own ${dest.name} — Goldberg was NOT applied.")
            }
            if (!copyChecked(f, dest, rep)) {
                return Pair(false, "Couldn't write ${f.name} into the game folder (storage full or read-only?).")
            }
        }

        // The loader that matches the game exe's arch must now be there — otherwise the launch would
        // silently fall back to the bare game exe with no Goldberg at all.
        val arch = detectArch(gameExe) ?: Arch.X64
        val loader = findLoader(exeDir, arch)
        if (loader == null) {
            val have = loaderSrc.list()?.joinToString() ?: "?"
            rep.warn("no ${arch.loaderExe}${arch.loaderAlias?.let { " / $it" } ?: ""} after copy; component has: $have")
            return Pair(false, "The Steam Emulator package has no Cold Client loader for ${arch.name.lowercase()} games.")
        }

        // Generate ColdClientLoader.ini from the bundled template so we match its
        // exact section/key names — we only rewrite the value lines.
        val template = File(loaderSrc, "ColdClientLoader.ini")
        val iniBytes = buildColdClientIni(template, appId, gameExe.name).toByteArray(Charsets.UTF_8)
        val iniFile = File(exeDir, "ColdClientLoader.ini")
        val iniOk = try {
            iniFile.writeBytes(iniBytes)
            iniFile.isFile && iniFile.length() == iniBytes.size.toLong()
        } catch (e: Exception) {
            rep.warn("couldn't write ColdClientLoader.ini: $e")
            false
        }
        if (!iniOk) {
            return Pair(false, "Couldn't write ColdClientLoader.ini into the game folder (storage full or read-only?).")
        }
        rep.add("coldclient: loader=${loader.name} ini written (AppId=$appId Exe=${gameExe.name})")

        return Pair(true, "Cold Client Loader applied to \"$gameName\". It launches through the loader automatically.")
    }

    // ── Analyze ──────────────────────────────────────────────────────────────

    /**
     * Recursively finds every steam_api.dll / steam_api64.dll under [installDir]
     * and records its arch — read from the PE header, not just the filename.
     */
    @JvmStatic
    fun analyze(installDir: File): List<PatchTarget> {
        val out = ArrayList<PatchTarget>()
        collectApiDlls(installDir, out)
        return out
    }

    private fun collectApiDlls(dir: File, out: MutableList<PatchTarget>) {
        val entries = dir.listFiles() ?: return
        for (f in entries) {
            if (f.isDirectory) {
                collectApiDlls(f, out)
            } else {
                val n = f.name.lowercase()
                if (n == "steam_api.dll" || n == "steam_api64.dll") {
                    // PE header is authoritative; filename is the fallback hint.
                    val arch = detectArch(f) ?: if (n == "steam_api64.dll") Arch.X64 else Arch.X86
                    out.add(PatchTarget(f, arch))
                }
            }
        }
    }

    /**
     * Reads the PE IMAGE_FILE_HEADER Machine field: 0x8664 → x64, 0x14c → x86.
     * Returns null if the file isn't a recognisable PE.
     */
    private fun detectArch(dll: File): Arch? {
        return try {
            RandomAccessFile(dll, "r").use { raf ->
                if (raf.length() < 0x40) return null
                raf.seek(0x3C)
                val lfanew = readLE32(raf)
                if (lfanew <= 0 || lfanew + 6 > raf.length()) return null
                raf.seek(lfanew.toLong())
                val sig = ByteArray(4); raf.readFully(sig)
                // "PE\0\0"
                if (sig[0].toInt() != 0x50 || sig[1].toInt() != 0x45 ||
                    sig[2].toInt() != 0x00 || sig[3].toInt() != 0x00
                ) return null
                val machine = readLE16(raf)
                when (machine) {
                    0x8664 -> Arch.X64
                    0x014c -> Arch.X86
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "detectArch failed for ${dll.name}", e)
            null
        }
    }

    private fun readLE32(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readLE16(raf: RandomAccessFile): Int {
        val b = ByteArray(2); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    // ── Shared prep ──────────────────────────────────────────────────────────

    /**
     * Idempotent one-time prep run before applying any tier:
     *  1. Back up each pristine steam_api dll to `<dll>.bak` (source of truth). Returns false (and the
     *     caller aborts) when a backup can't be made.
     *  2. Scan the ORIGINAL dll for Steam interface version strings and write
     *     them to `steam_settings/steam_interfaces.txt` (re-implements Goldberg's
     *     generate_interfaces without running a foreign binary).
     *  3. Write steam_settings/steam_appid.txt and a steam_appid.txt beside the dll.
     *  4. Write steam_settings/achievements.json — the gbe_fork achievement DEFINITIONS
     *     (schema). Fetched ONCE for the whole install (bounded), then copied to every dll dir.
     */
    private fun sharedPrep(
        context: Context, targets: List<PatchTarget>, appId: Int, rep: Report, fetchSchema: Boolean = true,
    ): Boolean {
        for (t in targets) {
            // 1. Permanent pristine backup.
            if (!t.bakFile.exists()) {
                if (looksLikeGoldbergDll(t.dll)) {
                    // The backup is gone and the dll is already Goldberg's: saving it as "the original" would
                    // make every later restore put Goldberg's dll back.
                    rep.warn("no .bak for ${t.dll.path} and the dll is already Goldberg's: not saving it as the original (Verify the game files to get the original back)")
                } else if (!copyChecked(t.dll, t.bakFile, rep)) {
                    // Don't leave a truncated .bak behind: it would later be "restored" over the dll.
                    try { t.bakFile.delete() } catch (_: Exception) {}
                    return false
                } else {
                    rep.add("backup: ${t.bakFile.path} (${t.bakFile.length()} bytes)")
                }
            }

            // 2. Interfaces from the ORIGINAL (.bak) dll.
            val settingsDir = File(t.dir, "steam_settings").apply { mkdirs() }
            val interfaces = scanInterfaces(t.bakFile)
            if (interfaces.isNotEmpty()) {
                File(settingsDir, "steam_interfaces.txt").writeText(interfaces.joinToString("\n") + "\n")
                rep.add("steam_interfaces.txt: ${interfaces.size} entries for ${t.dll.name}")
            } else {
                rep.warn("no interface strings found in ${t.bakFile.name} — steam_interfaces.txt not written")
            }

            // 3. AppId, both in steam_settings and beside the dll. (steam_appid.txt
            //    is a Goldberg-convention file games don't ship, so it's treated as
            //    purely added — no backup, removed on restore.)
            File(settingsDir, "steam_appid.txt").writeText(appId.toString())
            File(t.dir, "steam_appid.txt").writeText(appId.toString())
        }

        // 4. Achievement DEFINITIONS (schema), once. Not for Off: those files are removed right after, so
        //    the fetch (up to SCHEMA_TIMEOUT_MS) would be wasted.
        if (fetchSchema) {
            ensureAchievementSchema(context, appId, targets.map { it.dir }, SCHEMA_TIMEOUT_MS, rep)
        }
        return true
    }

    /**
     * Writes gbe_fork's `steam_settings/achievements.json` (the achievement DEFINITIONS) into each of
     * [dirs]: [SteamAchievementStore.writeGbeAchievementSchema] runs ONCE (the first dir), bounded by
     * [timeoutMs], and the result is copied to the rest. A game with no cached definitions is only
     * re-fetched every few hours, so an offline launch isn't delayed every time. Returns how many
     * dirs ended up with a schema. Fully guarded; call off the main thread.
     */
    @JvmStatic
    fun ensureAchievementSchema(context: Context, appId: Int, dirs: List<File>, timeoutMs: Long): Int =
        ensureAchievementSchema(context, appId, dirs, timeoutMs, null)

    private fun ensureAchievementSchema(
        context: Context, appId: Int, dirs: List<File>, timeoutMs: Long, rep: Report?,
    ): Int {
        try {
            val settingsDirs = ArrayList<File>()
            for (d in dirs) {
                val sd = File(d, "steam_settings")
                if (settingsDirs.none { it.path == sd.path }) {
                    sd.mkdirs()
                    settingsDirs.add(sd)
                }
            }
            if (settingsDirs.isEmpty()) return 0
            val prefs = context.applicationContext.getSharedPreferences("goldberg_state", Context.MODE_PRIVATE)
            val key = "schema_try_$appId"
            val hasCache = SteamAchievementStore.cached(context, appId).isNotEmpty()
            if (!hasCache) {
                val last = prefs.getLong(key, 0L)
                if (System.currentTimeMillis() - last < SCHEMA_RETRY_MS) {
                    schemaNote(rep, false, "schema: no cached achievement definitions and a fetch was tried recently — skipped")
                    return 0
                }
                prefs.edit().putLong(key, System.currentTimeMillis()).apply()
            }
            val first = settingsDirs[0]
            val written = IntArray(1)
            val worker = Thread({
                try {
                    written[0] = SteamAchievementStore.writeGbeAchievementSchema(context, appId, first)
                } catch (t: Throwable) {
                    Log.w(TAG, "schema worker failed", t)
                }
            }, "goldberg-schema")
            worker.isDaemon = true
            worker.start()
            worker.join(timeoutMs)
            if (worker.isAlive) {
                schemaNote(rep, true, "schema: fetch still running after ${timeoutMs}ms — continuing without waiting")
                return 0
            }
            val src = File(first, "achievements.json")
            if (written[0] <= 0 || !src.isFile) {
                schemaNote(rep, false, "schema: no achievement definitions available (count=${written[0]})")
                return 0
            }
            var n = 1
            for (i in 1 until settingsDirs.size) {
                if (FileUtils.copy(src, File(settingsDirs[i], "achievements.json"))) n++
            }
            schemaNote(rep, false, "schema: ${written[0]} achievements written to $n dir(s)")
            return n
        } catch (t: Throwable) {
            Log.w(TAG, "ensureAchievementSchema failed", t)
            return 0
        }
    }

    private fun schemaNote(rep: Report?, warn: Boolean, msg: String) {
        if (rep != null) { if (warn) rep.warn(msg) else rep.add(msg) }
        else if (warn) Log.w(TAG, msg) else Log.i(TAG, msg)
    }

    /** Interface prefixes Goldberg's generate_interfaces looks for. */
    private val INTERFACE_PREFIXES = listOf(
        "SteamClient", "SteamGameServerStats", "SteamGameServer", "SteamUserStats",
        "SteamUser", "SteamFriends", "SteamUtils", "SteamMatchMakingServers",
        "SteamMatchMaking", "SteamApps", "SteamNetworkingUtils", "SteamNetworkingSockets",
        "SteamNetworkingMessages", "SteamNetworking", "SteamRemoteStorage",
        "SteamScreenshots", "SteamHTTP", "SteamController", "SteamUGC", "SteamAppList",
        "SteamMusicRemote", "SteamMusic", "SteamInventory", "SteamVideo",
        "SteamParentalSettings", "SteamInput", "SteamParties", "SteamRemotePlay",
        "SteamHTMLSurface", "SteamMasterServerUpdater", "SteamGameCoordinator",
    )

    /** Byte-scans a dll's ASCII for `<Prefix><3 digits>` interface version strings. */
    private fun scanInterfaces(dll: File): List<String> {
        return try {
            val bytes = dll.readBytes()
            // Interpret raw bytes as Latin-1 so every byte maps to one char.
            val text = String(bytes, Charsets.ISO_8859_1)
            val found = LinkedHashSet<String>()
            for (prefix in INTERFACE_PREFIXES) {
                Regex(Regex.escape(prefix) + "\\d{3}").findAll(text).forEach { found.add(it.value) }
            }
            found.toList()
        } catch (e: Exception) {
            Log.w(TAG, "scanInterfaces failed for ${dll.name}", e)
            emptyList()
        }
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    /** Copies every `<dll>.bak` back over `<dll>` (pristine steam_api). False if any copy failed. */
    private fun restoreDlls(targets: List<PatchTarget>, rep: Report): Boolean {
        var ok = true
        for (t in targets) {
            if (!t.bakFile.isFile) continue
            if (!isUsableBackup(t.bakFile)) {
                // A truncated .bak (from an old failed run) must never be copied over the real dll.
                rep.warn("${t.bakFile.path} is empty or not a valid dll — not restoring from it")
                continue
            }
            if (!copyChecked(t.bakFile, t.dll, rep)) ok = false
        }
        return ok
    }

    /** A usable backup is a non-empty file with a recognisable PE header. */
    private fun isUsableBackup(bak: File): Boolean = bak.isFile && bak.length() > 0L && detectArch(bak) != null

    /**
     * Goldberg's steam_api dlls (gbe_fork and the original) read their config from a `steam_settings` folder
     * and carry their own name; a genuine Valve steam_api dll has none of these strings. Used to tell "this dll
     * is the one we swapped in" from "this dll is the game's own (possibly newer) original".
     */
    private fun looksLikeGoldbergDll(dll: File): Boolean {
        return try {
            if (!dll.isFile || dll.length() > 64L * 1024 * 1024) return false
            val text = String(dll.readBytes(), Charsets.ISO_8859_1)
            text.contains("steam_settings") || text.contains("Goldberg") || text.contains("gbe_fork")
        } catch (e: Exception) {
            Log.w(TAG, "looksLikeGoldbergDll failed for ${dll.name}", e)
            false
        }
    }

    /**
     * A game update or Verify can put a NEWER original steam_api dll in place of the one saved in `.bak`.
     * Restoring would then overwrite the new dll with the old backup (and the interface list would be built
     * from it). So when a dll differs from its backup and is not Goldberg's, it is the game's new original:
     * refresh the backup from it. Never throws.
     */
    private fun refreshStaleBackups(targets: List<PatchTarget>, rep: Report) {
        for (t in targets) {
            try {
                val bak = t.bakFile
                if (!bak.isFile || !t.dll.isFile) continue
                if (looksLikeGoldbergDll(t.dll)) continue      // swapped in by us: the backup is still the original
                if (sameContent(bak, t.dll)) continue           // unchanged
                if (detectArch(t.dll) == null) continue         // not a usable dll: leave the backup alone
                if (copyChecked(t.dll, bak, rep)) {
                    rep.add("backup refreshed: ${t.dll.path} is newer than its .bak (game update or Verify)")
                }
            } catch (e: Exception) {
                rep.warn("backup check failed for ${t.dll.path}: $e")
            }
        }
    }

    /**
     * Restores [installDir] to pristine: restore dlls, then remove the files
     * Goldberg added (steam_settings, generated txts, and — where present — the
     * loader/steamclient dlls we placed). Also clears the COLDCLIENT shortcut
     * override implicitly (mode is set to OFF by the caller).
     */
    @JvmStatic
    fun restore(installDir: File, gameName: String) {
        val targets = analyze(installDir)
        val rep = Report()
        refreshStaleBackups(targets, rep)
        restoreDlls(targets, rep)
        removeAddedFiles(targets, installDir, gameName)
    }

    /**
     * SteamLite and Headless Steam run the game against the REAL Steam client, so a game that was patched
     * for Goldberg earlier must get its original steam_api files back first (otherwise the game keeps
     * talking to the emulator). Only when the folder at [installDir] still carries Goldberg's changes
     * ([looksPatched]) are the originals put back and what Goldberg added removed; the tier is then saved as
     * Off (and remembered for the next Goldberg launch). Returns null when there was nothing to do or the
     * restore worked, else a message for the user. Blocking: call it off the main thread. Never throws.
     */
    @JvmStatic
    fun restoreOriginalForRealSteam(context: Context, appId: Int, installDir: String, gameName: String): String? {
        val appContext = context.applicationContext
        try {
            // A game whose saved tier is Off was never patched (or was already restored): skip the
            // folder walk. An unresolved app id can't be checked, so it is still scanned.
            if (appId > 0) SteamPrefs.init(appContext)
            if (appId > 0 && SteamPrefs.getGoldbergMode(appId) == GoldbergMode.OFF) return null
            val root = if (installDir.isNotEmpty()) File(installDir) else return null
            if (!root.isDirectory) return null
            val targets = analyze(root)
            if (targets.isEmpty() || !looksPatched(targets)) return null

            val rep = Report()
            rep.add("real-Steam launch: '$gameName' still carries Goldberg files; restoring the original files")
            refreshStaleBackups(targets, rep)
            val restored = restoreDlls(targets, rep)
            removeAddedFiles(targets, root, gameName)
            rep.add("restore result: original dlls restored=$restored")
            if (restored && appId > 0) {
                // Remember the tier that was in use, so the next Goldberg launch brings it back.
                rememberTier(appContext, appId, SteamPrefs.getGoldbergMode(appId))
                SteamPrefs.setGoldbergMode(appId, GoldbergMode.OFF)
            }
            if (appId > 0) GoldbergDiag.recordApply(appContext, appId, rep.lines)
            Log.i(TAG, "restoreOriginalForRealSteam '$gameName': restored=$restored")
            return if (restored) null else
                "Couldn't restore this game's original Steam files — it may still run with " +
                    "Goldberg instead of the real Steam client."
        } catch (t: Throwable) {
            Log.w(TAG, "restoreOriginalForRealSteam failed", t)
            return null
        }
    }

    private const val LAST_TIER_PREFIX = "last_tier_"

    private fun rememberTier(ctx: Context, appId: Int, tier: GoldbergMode) {
        if (tier == GoldbergMode.OFF) return
        try {
            ctx.applicationContext.getSharedPreferences("goldberg_state", Context.MODE_PRIVATE)
                .edit().putString(LAST_TIER_PREFIX + appId, tier.name).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't remember the Goldberg tier for $appId", t)
        }
    }

    /**
     * The tier a Goldberg launch of [appId] should use: the saved one, or — when it is Off because a real-Steam
     * launch put the original files back — the tier that was in use before that. Regular when there is none.
     */
    @JvmStatic
    fun resumeTier(ctx: Context, appId: Int): GoldbergMode {
        SteamPrefs.init(ctx.applicationContext)
        val saved = SteamPrefs.getGoldbergMode(appId)
        if (saved != GoldbergMode.OFF) return saved
        val last = try {
            ctx.applicationContext.getSharedPreferences("goldberg_state", Context.MODE_PRIVATE)
                .getString(LAST_TIER_PREFIX + appId, null)
        } catch (_: Throwable) { null }
        val tier = GoldbergMode.fromKey(last)
        return if (tier == GoldbergMode.OFF) GoldbergMode.REGULAR else tier
    }

    /** Whether a game folder still carries Goldberg's changes (settings dir, loader/ini, or a swapped steam_api dll). */
    private fun looksPatched(targets: List<PatchTarget>): Boolean {
        for (t in targets) {
            if (File(t.dir, "steam_settings").isDirectory) return true
            if (File(t.dir, "ColdClientLoader.ini").isFile) return true
            if (File(t.dir, "steamclient_loader_x64.exe").isFile ||
                File(t.dir, "steamclient_loader_x86.exe").isFile ||
                File(t.dir, "steamclient_loader_x32.exe").isFile) return true
            // Only a Goldberg dll counts: a dll that differs from its .bak but is not Goldberg's is a newer original.
            if (t.bakFile.isFile && t.dll.isFile && looksLikeGoldbergDll(t.dll)) return true
        }
        return false
    }

    /** True when [a] and [b] are both files with the same size and the same MD5. */
    private fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length()) return false
        return try { md5Of(a) == md5Of(b) } catch (_: Exception) { false }
    }

    private fun md5Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Names of files Goldberg drops in that are safe to delete on restore. */
    private val ADDED_FILE_NAMES = setOf(
        "steamclient.dll", "steamclient64.dll",
        "steamclient_loader_x64.exe", "steamclient_loader_x86.exe", "steamclient_loader_x32.exe",
        "GameOverlayRenderer.dll", "GameOverlayRenderer64.dll",
        "ColdClientLoader.ini", "steam_appid.txt", "steam_interfaces.txt",
    )

    private fun removeAddedFiles(targets: List<PatchTarget>, root: File, gameName: String) {
        // Collect the directories Goldberg files could live in: every dll dir
        // plus the primary game exe dir (Cold Client Loader lives there).
        val dirs = LinkedHashSet<File>()
        targets.forEach { dirs.add(it.dir) }
        AmazonLaunchHelper.choosePrimaryExe(root, gameName)?.parentFile?.let { dirs.add(it) }

        for (dir in dirs) {
            File(dir, "steam_settings").takeIf { it.isDirectory }?.deleteRecursively()
            for (name in ADDED_FILE_NAMES) {
                val f = File(dir, name)
                val bak = File(dir, "$name.bak")
                when {
                    // The game shipped this file — restore its original and keep
                    // the .bak as the permanent pristine source (same as api dlls).
                    bak.isFile -> if (!FileUtils.copy(bak, f)) Log.w(TAG, "couldn't restore ${f.path} from its .bak")
                    // Purely Goldberg-added (loader exes, overlay, ini, our appid) —
                    // no original to preserve, so remove it.
                    f.isFile -> f.delete()
                }
            }
        }
    }

    /**
     * Backs a file up to `<name>.bak` before Goldberg overwrites it, but only if
     * the file exists and no backup is there yet — so the FIRST (pristine) copy is
     * preserved as the permanent source of truth, exactly like the steam_api .bak
     * in [sharedPrep]. Lets [removeAddedFiles] restore a game's own steamclient /
     * steam_appid instead of deleting it. Returns false when the backup couldn't be made (the
     * caller must then NOT overwrite [file]).
     */
    private fun backupIfOriginal(file: File, rep: Report, goldbergSrc: File? = null): Boolean {
        val bak = File(file.parentFile, file.name + ".bak")
        if (!file.isFile || bak.exists()) return true
        // A file that is just Goldberg's own copy from an earlier apply is not the game's original: backing
        // it up would make a later restore put Goldberg's file back instead of removing it.
        if (goldbergSrc != null && sameContent(file, goldbergSrc)) {
            rep.add("no backup of ${file.name}: it is Goldberg's own file from an earlier apply")
            return true
        }
        if (copyChecked(file, bak, rep)) return true
        // Don't leave a truncated .bak behind: it would later be "restored" over the original.
        try { bak.delete() } catch (_: Exception) {}
        return false
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun archDir(arch: Arch) = if (arch == Arch.X64) "x64" else "x86"

    /**
     * Copy + verify (FileUtils.copy only reports an IOException; also check the size landed). The copy is
     * written to a temp file beside [dst] and renamed into place, so a full disk or a pulled card can't
     * leave a half-written file at [dst] (a truncated dll is exactly how a black screen is made).
     */
    private fun copyChecked(src: File, dst: File, rep: Report): Boolean {
        val tmp = File(dst.parentFile, dst.name + ".bl_tmp")
        val ok = try { FileUtils.copy(src, tmp) } catch (e: Exception) { rep.warn("copy threw: $e"); false }
        val sized = tmp.isFile && tmp.length() == src.length()
        if (!ok || !sized) {
            rep.warn("copy FAILED ${src.path} -> ${dst.path} (copy=$ok srcLen=${src.length()} dstLen=${if (tmp.isFile) tmp.length() else -1})")
            try { tmp.delete() } catch (_: Exception) {}
            return false
        }
        if (!tmp.renameTo(dst)) {
            // Some filesystems refuse to rename over an existing file; replace it explicitly.
            try {
                Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                rep.warn("couldn't move ${tmp.path} into place at ${dst.path}: $e")
                try { tmp.delete() } catch (_: Exception) {}
                return false
            }
        }
        return true
    }

    /**
     * Best effort undo of a failed apply: put the original steam_api dlls back from their .bak files and
     * remove everything Goldberg added, so the game is never left half patched. Never throws.
     */
    private fun rollbackFailedApply(targets: List<PatchTarget>, root: File, gameName: String, rep: Report) {
        try {
            val restored = restoreDlls(targets, rep)
            removeAddedFiles(targets, root, gameName)
            rep.add("apply failed: rolled back (original dlls restored=$restored, Goldberg additions removed)")
        } catch (t: Throwable) {
            Log.w(TAG, "rollback of a failed apply failed", t)
            rep.warn("rollback FAILED: $t")
        }
    }

    /**
     * The game exe named by `Exe=` in the ColdClientLoader.ini beside a loader, as a file in the same
     * folder (only its file name is used, so a stale path in the ini can't point elsewhere); null when
     * there is no ini, no `Exe=` line, or the named file is missing or is itself a loader.
     */
    private fun gameExeFromLoaderIni(dir: File?): File? {
        if (dir == null) return null
        val name = loaderIniExeName(dir) ?: return null
        if (name.startsWith("steamclient_loader", ignoreCase = true)) return null
        val exe = File(dir, name)
        return if (exe.isFile) exe else null
    }

    /** The file name on the `Exe=` line of the ColdClientLoader.ini in [dir], or null when there is none. */
    private fun loaderIniExeName(dir: File?): String? {
        if (dir == null) return null
        return try {
            val ini = File(dir, "ColdClientLoader.ini")
            if (!ini.isFile) return null
            for (raw in ini.readLines()) {
                val line = raw.trim()
                if (line.startsWith("#") || line.startsWith(";")) continue
                if (line.substringBefore('=').trim().lowercase() != "exe") continue
                val name = File(line.substringAfter('=').trim().replace('\\', '/')).name
                return name.ifEmpty { null }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "loaderIniExeName failed for ${dir.path}", t)
            null
        }
    }

    /** Files only the Cold Client tier puts in the game folder (the other tiers never need them). */
    private val COLDCLIENT_ONLY_FILES = listOf(
        "steamclient_loader_x64.exe", "steamclient_loader_x86.exe", "steamclient_loader_x32.exe",
        "ColdClientLoader.ini", "GameOverlayRenderer.dll", "GameOverlayRenderer64.dll",
    )

    /**
     * Removes what only Cold Client needs (loader exes, its ini, the overlay dlls) when a game moves to
     * another tier, so no loader is left behind for an old shortcut to start through. A file the game itself
     * shipped is put back from its `.bak` instead of deleted. Never throws.
     */
    private fun removeColdClientFiles(targets: List<PatchTarget>, root: File, gameName: String, exePath: String?) {
        try {
            val dirs = LinkedHashSet<File>()
            targets.forEach { dirs.add(it.dir) }
            AmazonLaunchHelper.choosePrimaryExe(root, gameName)?.parentFile?.let { dirs.add(it) }
            exePath?.let { File(it).parentFile }?.let { dirs.add(it) }
            for (dir in dirs) {
                for (name in COLDCLIENT_ONLY_FILES) {
                    val f = File(dir, name)
                    val bak = File(dir, "$name.bak")
                    when {
                        bak.isFile -> if (!FileUtils.copy(bak, f)) Log.w(TAG, "couldn't restore ${f.path} from its .bak")
                        f.isFile -> f.delete()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "removeColdClientFiles failed", t)
        }
    }

    /**
     * Rewrites the bundled ColdClientLoader.ini template's value lines for this
     * game. Reads the template (so section/key names match Goldberg exactly) and
     * only substitutes Exe / ExeRunDir / ExeCommandLine / AppId. If no template
     * is bundled, falls back to a minimal [SteamClient] block.
     */
    private fun buildColdClientIni(template: File, appId: Int, exeName: String): String {
        if (!template.isFile) {
            return buildString {
                append("[SteamClient]\n")
                append("Exe=$exeName\n")
                append("ExeRunDir=.\n")
                append("ExeCommandLine=\n")
                append("AppId=$appId\n")
            }
        }
        val out = StringBuilder()
        for (raw in template.readText().lines()) {
            val line = raw
            val key = line.substringBefore('=').trim().lowercase()
            val rewritten = when {
                line.trimStart().startsWith("#") || line.trimStart().startsWith(";") -> line
                key == "exe" -> "Exe=$exeName"
                key == "exerundir" -> "ExeRunDir=."
                key == "execommandline" -> "ExeCommandLine="
                key == "appid" -> "AppId=$appId"
                else -> line
            }
            out.append(rewritten).append('\n')
        }
        return out.toString()
    }
}
