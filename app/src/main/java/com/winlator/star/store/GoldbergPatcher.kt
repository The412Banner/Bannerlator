package com.winlator.star.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.winlator.star.core.FileUtils
import java.io.File
import java.io.RandomAccessFile

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
 */
object GoldbergPatcher {

    private const val TAG = "BH_GOLDBERG"

    /** Delivered on the main thread. */
    fun interface Callback {
        fun onResult(success: Boolean, message: String)
    }

    /** PE architecture of a steam_api dll — decides which Goldberg dll to use. */
    enum class Arch(val apiDll: String, val steamclientDll: String, val loaderExe: String) {
        X64("steam_api64.dll", "steamclient64.dll", "steamclient_loader_x64.exe"),
        X86("steam_api.dll", "steamclient.dll", "steamclient_loader_x86.exe"),
    }

    /** One steam_api dll found inside a game install, plus its detected arch. */
    data class PatchTarget(val dll: File, val arch: Arch) {
        val bakFile: File get() = File(dll.parentFile, dll.name + ".bak")
        val dir: File get() = dll.parentFile!!
    }

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
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val (ok, msg) = try {
                applyModeBlocking(appContext, appId, installDir, gameName, mode)
            } catch (e: Exception) {
                Log.e(TAG, "applyMode failed", e)
                false to "Goldberg patch failed: ${e.message}"
            }
            if (ok) {
                SteamPrefs.init(appContext)
                SteamPrefs.setGoldbergMode(appId, mode)
            }
            main.post { cb.onResult(ok, msg) }
        }, "goldberg-patch").start()
    }

    /**
     * Resolves the executable a shortcut should actually launch.
     *
     * OFF/REGULAR/EXPERIMENTAL return [exePath] unchanged. COLDCLIENT returns the
     * Goldberg loader beside the chosen exe (steamclient_loader_x64/x86.exe) IF
     * it was placed there by [applyModeBlocking]; otherwise it safely falls back
     * to [exePath] so the launch path never breaks.
     *
     * Callable from the Java shortcut-writer (StarLaunchBridge) — synchronous,
     * assumes it is already on a worker thread.
     */
    @JvmStatic
    fun resolveLaunchExe(context: Context, appId: Int, exePath: String): String {
        return try {
            SteamPrefs.init(context.applicationContext)
            if (SteamPrefs.getGoldbergMode(appId) != GoldbergMode.COLDCLIENT) return exePath
            val exe = File(exePath)
            val dir = exe.parentFile ?: return exePath
            // Prefer the loader matching the game exe's arch; fall back to x64.
            val arch = detectArch(exe) ?: Arch.X64
            val loader = File(dir, arch.loaderExe)
            if (loader.isFile) loader.absolutePath
            else File(dir, Arch.X64.loaderExe).takeIf { it.isFile }?.absolutePath ?: exePath
        } catch (e: Exception) {
            Log.w(TAG, "resolveLaunchExe failed, using original exe", e)
            exePath
        }
    }

    // ── Core (blocking) ──────────────────────────────────────────────────────

    private fun applyModeBlocking(
        context: Context,
        appId: Int,
        installDir: String,
        gameName: String,
        mode: GoldbergMode,
    ): Pair<Boolean, String> {
        val root = File(installDir)
        if (!root.isDirectory) return false to "Install directory not found."

        val targets = analyze(root)
        if (targets.isEmpty()) {
            return false to "This game doesn't use the Steam API — Goldberg doesn't apply."
        }

        // SHARED PREP is idempotent and needed for every non-OFF tier (and does
        // no harm before a restore).
        sharedPrep(context, targets, appId)

        // GOLDEN RULE: always restore pristine dlls from .bak first, so switching
        // tiers never stacks Goldberg dlls on top of each other.
        restoreDlls(targets)

        if (mode == GoldbergMode.OFF) {
            removeAddedFiles(targets, root, gameName)
            return true to "Goldberg turned off — \"$gameName\" restored to its original files."
        }

        // The downloaded Goldberg component is required from here on. The UI gates
        // tier selection behind the download, but guard defensively anyway.
        if (!GoldbergComponent.isInstalled(context)) {
            return false to "Download the Steam Emulator component first."
        }
        val goldberg = GoldbergComponent.installDir(context)

        return when (mode) {
            GoldbergMode.REGULAR -> applyRegular(targets, goldberg, gameName)
            GoldbergMode.EXPERIMENTAL -> applyExperimental(targets, goldberg, gameName)
            GoldbergMode.COLDCLIENT -> applyColdClient(targets, goldberg, root, appId, gameName)
            GoldbergMode.OFF -> true to "" // unreachable
        }
    }

    private fun applyRegular(
        targets: List<PatchTarget>, goldberg: File, gameName: String,
    ): Pair<Boolean, String> {
        for (t in targets) {
            val src = File(goldberg, "regular/${archDir(t.arch)}/${t.arch.apiDll}")
            if (!src.isFile) return false to "Missing bundled ${src.name} (regular/${archDir(t.arch)})."
            FileUtils.copy(src, t.dll)
        }
        return true to "Regular Goldberg applied to \"$gameName\". Launch it — if it still won't start, try Experimental."
    }

    private fun applyExperimental(
        targets: List<PatchTarget>, goldberg: File, gameName: String,
    ): Pair<Boolean, String> {
        for (t in targets) {
            val api = File(goldberg, "experimental/${archDir(t.arch)}/${t.arch.apiDll}")
            val client = File(goldberg, "experimental/${archDir(t.arch)}/${t.arch.steamclientDll}")
            if (!api.isFile) return false to "Missing bundled ${api.name} (experimental/${archDir(t.arch)})."
            FileUtils.copy(api, t.dll)
            if (client.isFile) {
                // Back up a game-shipped steamclient before overwriting it, so
                // restore/tier-switch can put the original back (some games — e.g.
                // Portal 2 — ship their own steamclient.dll).
                val dest = File(t.dir, t.arch.steamclientDll)
                backupIfOriginal(dest)
                FileUtils.copy(client, dest)
            }
        }
        return true to "Experimental Goldberg applied to \"$gameName\". Launch it — if it still won't start, try Cold Client Loader."
    }

    private fun applyColdClient(
        targets: List<PatchTarget>, goldberg: File, root: File, appId: Int, gameName: String,
    ): Pair<Boolean, String> {
        // Leave the restored ORIGINAL steam_api dll in place (already restored).
        val loaderSrc = File(goldberg, "steamclient_experimental")
        if (!loaderSrc.isDirectory) {
            return false to "Missing bundled steamclient_experimental loader files."
        }

        // Cold Client Loader must sit beside the game exe and launch it. Pick the
        // best game exe (same scorer the launch flow uses).
        val gameExe = AmazonLaunchHelper.choosePrimaryExe(root, gameName)
            ?: return false to "Couldn't find a game .exe to attach the Cold Client Loader to."
        val exeDir = gameExe.parentFile ?: root

        // Copy every loader support file (steamclient dlls, loader exes,
        // GameOverlayRenderer, etc.) next to the game exe, EXCEPT the template
        // ini which we generate below.
        val files = loaderSrc.listFiles() ?: emptyArray()
        for (f in files) {
            if (!f.isFile) continue
            if (f.name.equals("ColdClientLoader.ini", ignoreCase = true)) continue
            // Back up any game-shipped file we're about to clobber (loader set
            // includes steamclient dlls that can collide with the game's own).
            val dest = File(exeDir, f.name)
            backupIfOriginal(dest)
            FileUtils.copy(f, dest)
        }

        // Generate ColdClientLoader.ini from the bundled template so we match its
        // exact section/key names — we only rewrite the value lines.
        val template = File(loaderSrc, "ColdClientLoader.ini")
        val ini = buildColdClientIni(template, appId, gameExe.name)
        File(exeDir, "ColdClientLoader.ini").writeText(ini)

        return true to (
            "Cold Client Loader applied to \"$gameName\".\n" +
                "Re-add this game to Shortcuts so it launches through the loader."
            )
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
     *  1. Back up each pristine steam_api dll to `<dll>.bak` (source of truth).
     *  2. Scan the ORIGINAL dll for Steam interface version strings and write
     *     them to `steam_settings/steam_interfaces.txt` (re-implements Goldberg's
     *     generate_interfaces without running a foreign binary).
     *  3. Write steam_settings/steam_appid.txt and a steam_appid.txt beside the dll.
     *  4. Write steam_settings/achievements.json — the gbe_fork achievement DEFINITIONS
     *     (schema). Without it gbe_fork doesn't know the game HAS achievements and shows
     *     every one as not-earned, regardless of the earned state seeded into GSE Saves.
     */
    private fun sharedPrep(context: Context, targets: List<PatchTarget>, appId: Int) {
        for (t in targets) {
            // 1. Permanent pristine backup.
            if (!t.bakFile.exists()) FileUtils.copy(t.dll, t.bakFile)

            // 2. Interfaces from the ORIGINAL (.bak) dll.
            val settingsDir = File(t.dir, "steam_settings").apply { mkdirs() }
            val interfaces = scanInterfaces(t.bakFile)
            if (interfaces.isNotEmpty()) {
                File(settingsDir, "steam_interfaces.txt").writeText(interfaces.joinToString("\n") + "\n")
            }

            // 3. AppId, both in steam_settings and beside the dll. (steam_appid.txt
            //    is a Goldberg-convention file games don't ship, so it's treated as
            //    purely added — no backup, removed on restore.)
            File(settingsDir, "steam_appid.txt").writeText(appId.toString())
            File(t.dir, "steam_appid.txt").writeText(appId.toString())

            // 4. Achievement DEFINITIONS (schema). gbe_fork reads these to know which
            //    achievements exist; without it the in-game list is empty/all-zero even
            //    though the earned state is seeded into GSE Saves separately. DEFS only;
            //    fully self-guarded and a no-op when no achievement defs are cached.
            SteamAchievementStore.writeGbeAchievementSchema(context, appId, settingsDir)
        }
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

    /** Copies every `<dll>.bak` back over `<dll>` (pristine steam_api). */
    private fun restoreDlls(targets: List<PatchTarget>) {
        for (t in targets) if (t.bakFile.isFile) FileUtils.copy(t.bakFile, t.dll)
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
        restoreDlls(targets)
        removeAddedFiles(targets, installDir, gameName)
    }

    /** Names of files Goldberg drops in that are safe to delete on restore. */
    private val ADDED_FILE_NAMES = setOf(
        "steamclient.dll", "steamclient64.dll",
        "steamclient_loader_x64.exe", "steamclient_loader_x86.exe",
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
                    bak.isFile -> FileUtils.copy(bak, f)
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
     * steam_appid instead of deleting it.
     */
    private fun backupIfOriginal(file: File) {
        val bak = File(file.parentFile, file.name + ".bak")
        if (file.isFile && !bak.exists()) FileUtils.copy(file, bak)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun archDir(arch: Arch) = if (arch == Arch.X64) "x64" else "x86"

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
