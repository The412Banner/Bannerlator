package com.winlator.star.store.steamscript

import android.content.Context
import android.util.Log
import com.winlator.star.components.ComponentExecInstaller
import com.winlator.star.container.Container
import com.winlator.star.core.VdfParser
import com.winlator.star.core.WinePath
import com.winlator.star.core.WineRegistryEditor
import java.io.File

/**
 * Phase 1 of "Ubisoft-via-installScript": runs the **Run Process** step of a Steam depot's
 * `installScript.vdf` inside the game's Wine container at add-to-shortcut time — the install-time step
 * the real Steam client performs and Winlator has always skipped.
 *
 * The concrete win: Trackmania (appId 2225070) and other Ubisoft-DRM Steam titles ship
 * `Installer/ubisoftconnect_install.vdf`:
 * ```
 * "installscript" { "Run Process" { "Uplay_steam_installer" {
 *     "process 1" "%INSTALLDIR%\UbisoftConnectInstaller.exe"
 *     "command 1" "/S"
 *     "NoCleanUp" "1" } } }
 * ```
 * The real Steam client executes this so Ubisoft Connect installs into the prefix; we download the vdf
 * but never run it, so the game hits its "Ubisoft Connect is not currently installed" dialog. Running
 * the bundled `UbisoftConnectInstaller.exe /S` once, in-prefix, gets past it. GameHub is device-proven
 * doing exactly this (see the installScript recon), and — unlike EA Desktop, whose installer hits a
 * hard MSI wall under Wine — Ubisoft Connect installs cleanly under Wine/FEX, so this path is viable.
 *
 * Scope is deliberately **Run-Process ONLY**. The Registry / Copy Files blocks (which the EA scripts
 * need) are intentionally not handled here — Ubisoft's script uses neither, and keeping this slice
 * small is the make-or-break Phase 1 deliverable.
 *
 * Everything is best-effort: this runs on the install/launch path, so it must never throw. Normal
 * (non-installScript) Steam games no-op cleanly — [locateScript] simply finds nothing.
 *
 * Lifecycle: there is no container bound at Steam download-complete, so this fires when the container
 * is chosen — from [com.winlator.star.store.StarLaunchBridge.writeShortcutAsync], the first point where
 * the container, appId and on-disk install dir all exist. Guarded once per (container, appId).
 */
object InstallScriptExecutor {
    private const val TAG = "InstallScript"

    /** Once-guard store: a SharedPreferences file holding one StringSet of executed appIds per container. */
    private const val PREFS = "installscript_executed"

    /** Skip any candidate `.vdf` larger than this when sniffing for the installscript root (they're tiny). */
    private const val MAX_SCRIPT_BYTES = 512 * 1024L

    /** Outcome of a [maybeRun] call — useful for logging/tests; the hook ignores it. */
    sealed class Result {
        object NoScript : Result()        // no installscript vdf under the install dir (the common case)
        object NoRunProcess : Result()    // script found but no Run Process step (Registry/Copy only — out of P1 scope)
        object AlreadyDone : Result()     // guard already satisfied for this (container, appId)
        object GuardSatisfied : Result()  // the script's own HasRunStringKey shows it already ran (client present)
        object Launched : Result()        // a Run-Process session was launched (the app restarts when it ends)
        data class Error(val message: String) : Result()
    }

    // ---- Public entry points ---------------------------------------------------------------------

    /**
     * Primary entry point (called from [com.winlator.star.store.StarLaunchBridge] after the shortcut is
     * written). Locates, parses and — if a Run Process step exists and hasn't run yet — launches the
     * depot's bundled installer for [appId] in [container]. Never throws: every failure degrades to a
     * logged [Result.Error].
     *
     * @param installDir the depot's root directory (see [locateInstallDir]); its own folder and its
     *                   `Installer/` subfolder are scanned for the script.
     * @param appId      the Steam appId; must be > 0 (non-Steam shortcuts are ignored).
     */
    @JvmStatic
    fun maybeRun(context: Context, container: Container, appId: Int, installDir: File): Result {
        return try {
            runInternal(context, container, appId, installDir)
        } catch (e: Exception) {
            Log.w(TAG, "maybeRun failed for appId $appId", e)
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun runInternal(context: Context, container: Container, appId: Int, installDir: File): Result {
        if (appId <= 0) return Result.NoScript

        val script = locateScript(installDir) ?: return Result.NoScript
        if (guardContains(context, container.id, appId)) return Result.AlreadyDone

        // "installscript" wrapper (case-insensitive); tolerate its absence and read from the root.
        val root = VdfParser.parse(script.readText())
        val installNode = root.node("installscript") ?: root

        val runProcesses = collectRunProcesses(installNode)
        if (runProcesses.isEmpty()) return Result.NoRunProcess

        // Honour the script's own idempotency key: if the client's install marker
        // (HasRunStringKey/HasRunKey) is already in the prefix registry, don't reinstall — record our
        // guard so we stop re-checking, and stop here.
        if (anyRunGuardSatisfied(container, runProcesses)) {
            markGuard(context, container.id, appId)
            return Result.GuardSatisfied
        }

        val rp = runProcesses.firstOrNull { it.process.isNotBlank() } ?: return Result.NoRunProcess

        // Resolve %INSTALLDIR% to the container's REAL Windows drive path — Z: for an internal
        // install, F:/G:/… for one parked on an SD card. This is why we go through
        // WinePath.resolveWindowsPath rather than hardcoding Z: (which the EA-era prototype did and
        // which would break every SD install). %PROGRAMDATA% -> C:\ProgramData.
        val installDirWindows = WinePath.resolveWindowsPath(container, installDir.absolutePath)
        val winProcess = substituteTokens(rp.process, installDirWindows)
        val winCommand = substituteTokens(rp.command, installDirWindows)
        val exeBaseName = winProcess.substringAfterLast('\\').substringAfterLast('/')
        val (envPairs, execArgs) = splitCommand(winCommand)

        // Mark the guard optimistically BEFORE we hand off: launching the session restarts the whole
        // app (XServerDisplayActivity.exit -> restartApplication), so we can't confirm afterwards from
        // here — this mirrors ComponentExecInstaller's fire-and-forget cursor advance.
        markGuard(context, container.id, appId)

        // NoCleanUp is honoured intrinsically: we run the installer in place from the depot dir (never
        // a staged copy), so there is nothing for us to clean up regardless of the flag's value.
        ComponentExecInstaller.launchPrefixExe(
            context = context,
            container = container,
            shortcutKey = "installscript_" + safeName(rp.name),
            displayName = rp.name.ifBlank { "Steam installScript" },
            execTarget = WinePath.escapeForExec(winProcess),   // args passed VERBATIM (keeps /S)
            execArgs = execArgs,
            envPairs = envPairs,
            autoCloseExe = exeBaseName,
        )
        Log.d(TAG, "Launched installScript Run-Process '${rp.name}' ($exeBaseName $execArgs) " +
                "for appId $appId in container ${container.id}")
        return Result.Launched
    }

    // ---- Script / install-dir location -----------------------------------------------------------

    /**
     * Derives the depot's root directory from the game's [exeOrDir]. Steam depots (internal and SD)
     * both live under `…/steam_games/<name>/…`, so we cut at that segment. Falls back to the exe's own
     * folder for any layout that doesn't follow the convention.
     */
    @JvmStatic
    fun locateInstallDir(exeOrDir: File): File? {
        val parts = exeOrDir.absolutePath.split('/')
        val idx = parts.indexOf("steam_games")
        if (idx in 0 until parts.size - 1) {
            return File(parts.subList(0, idx + 2).joinToString("/"))
        }
        return if (exeOrDir.isFile) exeOrDir.parentFile else exeOrDir
    }

    /**
     * Finds the installScript by scanning [installDir] and its `Installer/` subfolder for a `.vdf`
     * whose root key is `installscript` (case-insensitive). Trackmania's is
     * `Installer/ubisoftconnect_install.vdf`, so we can't match on a fixed filename — we sniff the
     * root key instead. Returns the first match, or null when the game ships no such script.
     */
    @JvmStatic
    fun locateScript(installDir: File): File? {
        // installDir itself plus any "Installer" subfolder (case-insensitive: Android's FS is
        // case-sensitive, and depots vary — Trackmania uses `Installer/`).
        val dirs = ArrayList<File>()
        dirs.add(installDir)
        installDir.listFiles { f -> f.isDirectory && f.name.equals("Installer", true) }
            ?.let { dirs.addAll(it) }
        for (dir in dirs) {
            val vdfs = dir.listFiles { f ->
                f.isFile && f.name.lowercase().endsWith(".vdf") && f.length() in 1L..MAX_SCRIPT_BYTES
            } ?: continue
            for (f in vdfs) if (isInstallScript(f)) return f
        }
        return null
    }

    /** True when [f] parses as a KeyValues doc whose root key is `installscript`. */
    private fun isInstallScript(f: File): Boolean = try {
        VdfParser.parse(f.readText()).node("installscript") != null
    } catch (e: Exception) {
        false
    }

    // ---- Run Process extraction ------------------------------------------------------------------

    /** One `Run Process` step: the exe + its verbatim command line, plus an optional once-guard key. */
    private data class RunProcess(
        val name: String,
        val process: String,       // exe path, token-bearing (e.g. %INSTALLDIR%\UbisoftConnectInstaller.exe)
        val command: String,       // args/env, passed VERBATIM (silent flags like /S are NOT stripped)
        val hasRunKey: String?,    // full registry path whose presence means "already ran", or null
        val hasRunValue: String?,  // expected value at hasRunKey, or null for a presence-only guard
    )

    /** Flattens every `Run Process` group under [installNode] into [RunProcess] records, in order. */
    private fun collectRunProcesses(installNode: VdfParser.VdfNode): List<RunProcess> {
        val out = ArrayList<RunProcess>()
        for (runNode in installNode.nodes("Run Process")) {
            for ((name, group) in runNode.childNodes()) {
                var process = ""
                var command = ""
                // Steam numbers the leaves ("process 1"/"command 1"); take the first of each.
                for ((k, v) in group.stringEntries()) {
                    val lower = k.lowercase().replace(" ", "")
                    when {
                        process.isEmpty() && lower.startsWith("process") -> process = v
                        command.isEmpty() && lower.startsWith("command") -> command = v
                    }
                }
                // Steam uses both "HasRunKey"/"HasRunValue" and "HasRunStringKey"/"HasRunStringValue".
                val hasRunKey = group.string("HasRunStringKey") ?: group.string("HasRunKey")
                val hasRunValue = group.string("HasRunStringValue") ?: group.string("HasRunValue")
                out.add(RunProcess(name, process, command, hasRunKey, hasRunValue))
            }
        }
        return out
    }

    // ---- Token substitution (Windows space) ------------------------------------------------------

    /** Substitutes the path tokens an installScript exe/command line may carry. Tokens are case-insensitive. */
    private fun substituteTokens(text: String, installDirWindows: String): String {
        var s = replaceIgnoreCase(text, "%INSTALLDIR%", installDirWindows)
        s = replaceIgnoreCase(s, "%PROGRAMDATA%", "C:\\ProgramData")
        return s
    }

    /**
     * Splits a command line into (envVars, args): a run of leading `NAME=VALUE` tokens are env vars
     * (Winlator's shortcut format carries them in `[Extra Data] envVars`), everything else is args.
     * Ubisoft's `/S` has no `=`, so it lands wholly in args. Args are passed through verbatim.
     */
    private fun splitCommand(command: String): Pair<String, String> {
        val env = StringBuilder()
        val args = StringBuilder()
        var stillEnv = true
        for (t in command.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }) {
            if (stillEnv && t.matches(Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*"""))) {
                if (env.isNotEmpty()) env.append(' '); env.append(t)
            } else {
                stillEnv = false
                if (args.isNotEmpty()) args.append(' '); args.append(t)
            }
        }
        return env.toString() to args.toString()
    }

    private fun safeName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifEmpty { "run" }

    // ---- Script HasRun guard (registry) ----------------------------------------------------------

    /** True when any Run Process's own HasRunStringKey is already present (with the expected value) in the prefix. */
    private fun anyRunGuardSatisfied(container: Container, runProcesses: List<RunProcess>): Boolean {
        for (rp in runProcesses) {
            val keyPath = rp.hasRunKey ?: continue
            val target = resolveRunGuard(container, keyPath) ?: continue
            if (!target.hiveFile.exists()) continue
            val current = try {
                WineRegistryEditor(target.hiveFile).use { it.getStringValue(target.key, target.name) }
            } catch (e: Exception) {
                null
            }
            if (current != null && (rp.hasRunValue == null || current.equals(rp.hasRunValue, true))) {
                Log.d(TAG, "Run-Process guard already satisfied by '$keyPath'")
                return true
            }
        }
        return false
    }

    /** Where a HasRunStringKey lands on disk: which hive file, the key path below it, and the value name. */
    private data class RunGuardTarget(val hiveFile: File, val key: String, val name: String)

    /**
     * Parses a full `HasRunStringKey` path (its own hive prefix + a trailing value name) into a hive
     * file + key + value name. e.g.
     * `HKEY_LOCAL_MACHINE\Software\Ubisoft\Launcher\InstallDir` ->
     * (system.reg, key=`Software\Ubisoft\Launcher`, name=`InstallDir`). A `_WOW64_32` hive token
     * rewrites a leading `Software\` to `Software\Wow6432Node\`, mirroring how Wine stores 32-bit hives.
     */
    private fun resolveRunGuard(container: Container, fullPath: String): RunGuardTarget? {
        val norm = fullPath.trim().replace('/', '\\')
        val firstSep = norm.indexOf('\\')
        if (firstSep <= 0) return null
        val hive = norm.substring(0, firstSep).uppercase()
        val rest = norm.substring(firstSep + 1)
        val lastSep = rest.lastIndexOf('\\')
        if (lastSep <= 0) return null
        var key = rest.substring(0, lastSep)
        val name = rest.substring(lastSep + 1)

        val isUser = hive.startsWith("HKEY_CURRENT_USER") || hive.startsWith("HKCU")
        val isMachine = hive.startsWith("HKEY_LOCAL_MACHINE") || hive.startsWith("HKLM")
        if (!isUser && !isMachine) return null
        if (hive.endsWith("_WOW64_32")) key = toWow6432(key)

        val hiveFile = File(container.rootDir, if (isUser) ".wine/user.reg" else ".wine/system.reg")
        return RunGuardTarget(hiveFile, key, name)
    }

    private fun toWow6432(keyPath: String): String {
        val parts = keyPath.split('\\')
        return if (parts.isNotEmpty() && parts[0].equals("Software", true) &&
            (parts.size < 2 || !parts[1].equals("Wow6432Node", true))) {
            (listOf(parts[0], "Wow6432Node") + parts.drop(1)).joinToString("\\")
        } else keyPath
    }

    // ---- Once-guard (prefs) ----------------------------------------------------------------------

    private fun guardContains(context: Context, containerId: Int, appId: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("c$containerId", emptySet())?.contains(appId.toString()) == true

    private fun markGuard(context: Context, containerId: Int, appId: Int) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "c$containerId"
        val cur = p.getStringSet(key, emptySet()) ?: emptySet()
        // getStringSet's returned set must not be mutated in place — build a fresh one.
        if (appId.toString() !in cur) p.edit().putStringSet(key, cur + appId.toString()).apply()
    }

    private fun replaceIgnoreCase(text: String, token: String, value: String): String =
        Regex(Regex.escape(token), RegexOption.IGNORE_CASE).replace(text, Regex.escapeReplacement(value))
}
