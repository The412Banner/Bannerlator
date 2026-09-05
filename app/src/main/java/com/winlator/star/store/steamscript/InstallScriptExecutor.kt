package com.winlator.star.store.steamscript

import android.content.Context
import android.content.Intent
import android.util.Log
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.store.EaSupport
import com.winlator.star.core.FileUtils
import com.winlator.star.core.WinePath
import com.winlator.star.core.WineRegistryEditor
import java.io.File

/**
 * Runs a Steam game's `installScript.vdf` recipe inside a Wine container — the install-time step the
 * real Steam client performs and Winlator historically skipped. This is what makes EA-on-Steam titles
 * (Need for Speed Payback, any EA game) auto-install the bundled EA App (EA Desktop) + registry keys +
 * offline entitlement, so DRM/steam_api titles actually RUN after download.
 *
 * Three independent stages, each guarded and each usable on its own:
 *  1. **Registry** — writes via [WineRegistryEditor] into the container's `system.reg`/`user.reg`
 *     (WOW64 views + HKCU/HKLM handled by [InstallScriptTokens]).
 *  2. **Copy Files** — host-side copy of e.g. the Origin LocalContent `.dat` entitlement into
 *     `%PROGRAMDATA%` (same pattern as [com.winlator.star.store.AmazonSdkManager.deploySdkToPrefix]).
 *  3. **Run Process** — runs the bundled installer (EAappInstaller.exe) once, in-prefix, via the
 *     [RunProcessStage] seam (default: a live XServer installer session).
 *
 * The Run-Process stage is deliberately behind an interface so a future "pre-baked container with EA
 * Desktop already installed" path can replace ONLY that stage while Registry + Copy Files keep working
 * unchanged.
 *
 * Lifecycle: there is no container at Steam download-complete, so this runs when a container is bound —
 * primarily [com.winlator.star.store.StarLaunchBridge.writeShortcutAsync] (shortcut creation), with a
 * robustness pass in [XServerDisplayActivity] right before a `steamAppId`-tagged game first launches.
 * Guarded once per (appId, container).
 */
object InstallScriptExecutor {
    private const val TAG = "InstallScript"
    private const val PREFS = "installscript_state"

    /** Swappable Run-Process backend (the fallback seam). Default runs the installer live. */
    @Volatile
    @JvmStatic
    var runProcessStage: RunProcessStage = LiveInstallerRunProcessStage

    /** Outcome of an [execute] call. */
    sealed class Result {
        object NoScript : Result()          // no installScript.vdf for this game
        object AlreadyDone : Result()       // guard already satisfied for this container
        object LocalApplied : Result()      // registry/copy applied; no run-process needed/launched
        object RunLaunched : Result()
        /** A prerequisite (wine-mono) install session was launched first; the Run-Process step is re-driven by [resumePending]. */
        object PrerequisiteLaunched : Result()       // a Run-Process session was launched (app will restart)
        data class Error(val message: String) : Result()
    }

    // ---- Public entry points ---------------------------------------------------------------------

    /**
     * Primary hook (shortcut creation). Best-effort; never throws. Applies the script's local stages
     * and, if present and not yet run, launches the bundled installer for [container]. [exePath] is the
     * game's Android exe path (used to locate the depot's install dir); [steamAppId] must be > 0.
     */
    @JvmStatic
    fun runForShortcut(context: Context, container: Container, steamAppId: Int, exePath: String) {
        if (steamAppId <= 0) return
        try {
            val installDir = locateInstallDir(File(exePath)) ?: return
            when (val r = execute(context, container, steamAppId, installDir, allowRunProcess = true)) {
                is Result.Error -> Log.w(TAG, "installScript for appId $steamAppId: ${r.message}")
                else -> Log.d(TAG, "installScript for appId $steamAppId -> ${r::class.simpleName}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "runForShortcut failed for appId $steamAppId", e)
        }
    }

    /**
     * Robustness hook (pre-first-launch). Applies ONLY the local stages (Registry + Copy Files) so a
     * game's keys/entitlement are present before it boots, even for shortcuts created before this
     * feature existed. The Run-Process stage is intentionally not driven from inside a launching game
     * session; the primary hook owns it. Best-effort; never throws.
     */
    @JvmStatic
    fun applyLocalStagesForLaunch(context: Context, container: Container, steamAppId: Int, exePath: String) {
        if (steamAppId <= 0) return
        try {
            val installDir = locateInstallDir(File(exePath)) ?: return
            execute(context, container, steamAppId, installDir, allowRunProcess = false)
        } catch (e: Exception) {
            Log.w(TAG, "applyLocalStagesForLaunch failed for appId $steamAppId", e)
        }
    }

    /**
     * Full executor. Locates + parses the script, applies stages honouring per-(appId, container)
     * guards. When [allowRunProcess] is false, only Registry + Copy Files run.
     */
    fun execute(
        context: Context, container: Container, appId: Int, installDir: File, allowRunProcess: Boolean,
    ): Result {
        val scriptFile = locateScript(installDir) ?: return Result.NoScript
        val model = try {
            InstallScriptModel.parse(scriptFile.readText())
        } catch (e: Exception) {
            return Result.Error("parse failed: ${e.message}")
        }

        val imageFsRoot = File(context.filesDir, "imagefs")
        val tokens = InstallScriptTokens(container, installDir, imageFsRoot)

        // Local stages (idempotent). Registry only lands when the prefix has booted at least once,
        // else wineboot could clobber a hand-written hive; the launch-time hook re-applies then.
        var localComplete = true
        if (!localDone(context, container.id, appId)) {
            val registryApplied = applyRegistry(container, model, tokens)
            applyCopyFiles(model, tokens)
            localComplete = registryApplied || !model.hasRegistry
            if (localComplete) markLocalDone(context, container.id, appId)
        }

        if (!allowRunProcess) return Result.LocalApplied

        if (model.hasRunProcess && !runProcDone(context, container.id, appId) && !runGuardSatisfied(model, tokens)) {
            // Prerequisite: EA's bundled installer (EAappInstaller.exe -> EA Desktop MSI) runs MANAGED
            // .NET custom actions, which need wine-mono in the prefix — without it the MSI dies with
            // 0x8007065b (device-proven, Aug 2026). Install the mono component first (its own
            // auto-closing session; the app restarts), remember this script, and let [resumePending]
            // re-drive the Run-Process step once mono is in.
            val firstRun = model.runProcesses.firstOrNull { it.process.isNotBlank() }
            if (firstRun != null && EaSupport.runProcessNeedsMono(firstRun.process) && !EaSupport.hasMono(container)) {
                savePending(context, container.id, appId, installDir)
                val started = EaSupport.startMonoInstall(context, container)
                Log.i(TAG, "Run-Process needs wine-mono (container ${container.id}) — mono install " + (if (started) "launched" else "NOT started"))
                return if (started) Result.PrerequisiteLaunched else Result.Error("wine-mono is required but could not be installed")
            }
            clearPending(context)
            // Mark optimistically before we hand off — the session restarts the app, mirroring
            // ComponentExecInstaller's fire-and-forget cursor advance.
            markRunProcDone(context, container.id, appId)
            val launched = runProcessStage.run(context, container, model.runProcesses, tokens)
            return if (launched) Result.RunLaunched else Result.LocalApplied
        }
        return if (localComplete) Result.LocalApplied else Result.AlreadyDone
    }

    // ---- Stage 1: Registry -----------------------------------------------------------------------

    /** Applies all registry writes; returns true if the prefix was ready and writes were attempted. */
    fun applyRegistry(container: Container, model: InstallScriptModel, tokens: InstallScriptTokens): Boolean {
        if (!model.hasRegistry) return true
        val systemReg = File(container.rootDir, ".wine/system.reg")
        if (!systemReg.exists()) {
            // Prefix not generated yet — defer to the launch-time hook so wineboot doesn't overwrite us.
            Log.d(TAG, "Registry deferred: prefix not booted (container ${container.id})")
            return false
        }
        // Group by hive file so each hive is opened/rewritten once (close() clones + renames).
        val byFile = HashMap<File, MutableList<Pair<InstallScriptTokens.RegTarget, InstallScriptModel.RegistryWrite>>>()
        for (w in model.registryWrites) {
            val target = tokens.registryTarget(w.hiveRoot, w.keyPath)
            if (target == null) {
                Log.w(TAG, "Unmapped hive root '${w.hiveRoot}' — skipping ${w.keyPath}\\${w.name}")
                continue
            }
            byFile.getOrPut(target.hiveFile) { ArrayList() }.add(target to w)
        }
        for ((hiveFile, writes) in byFile) {
            try {
                WineRegistryEditor(hiveFile).use { reg ->
                    reg.setCreateKeyIfNotExist(true)
                    for ((target, w) in writes) writeOne(reg, target.key, w, tokens)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Registry write to ${hiveFile.name} failed", e)
            }
        }
        return true
    }

    private fun writeOne(
        reg: WineRegistryEditor, key: String, w: InstallScriptModel.RegistryWrite, tokens: InstallScriptTokens,
    ) {
        when (w.type) {
            "string", "expand_string", "multi_string" -> {
                // expand_string/multi_string are written as REG_SZ (WineRegistryEditor has no native
                // writer for them); Wine still expands %VARS% at read time. See TODO in the summary.
                reg.setStringValue(key, w.name, tokens.substituteWindows(w.value))
            }
            "dword" -> reg.setDwordValue(key, w.name, parseInt(tokens.substituteWindows(w.value)))
            "qword" -> reg.setStringValue(key, w.name, tokens.substituteWindows(w.value)) // no qword writer
            "binary" -> reg.setHexValue(key, w.name, tokens.substituteWindows(w.value).replace(" ", ""))
            else -> reg.setStringValue(key, w.name, tokens.substituteWindows(w.value))
        }
    }

    private fun parseInt(s: String): Int =
        try { Integer.decode(s.trim()) } catch (e: NumberFormatException) { s.trim().toLongOrNull(16)?.toInt() ?: 0 }

    // ---- Stage 2: Copy Files ---------------------------------------------------------------------

    /** Copies each `Copy Files` entry host-side (source under %INSTALLDIR%, dest under %PROGRAMDATA%). */
    fun applyCopyFiles(model: InstallScriptModel, tokens: InstallScriptTokens) {
        for (cf in model.copyFiles) {
            val src = tokens.resolveHostPath(cf.src)
            val dst = tokens.resolveHostPath(cf.dst)
            if (!src.exists()) {
                Log.w(TAG, "Copy Files: source missing ${src.absolutePath}")
                continue
            }
            // Idempotent: skip when an identical-size copy is already in place.
            if (dst.isFile && dst.length() == src.length()) continue
            dst.parentFile?.mkdirs()
            if (FileUtils.copy(src, dst)) Log.d(TAG, "Copied ${src.name} -> ${dst.absolutePath}")
            else Log.w(TAG, "Copy failed ${src.absolutePath} -> ${dst.absolutePath}")
        }
    }

    // ---- Stage 3: Run Process guard --------------------------------------------------------------

    /** True when the script's own HasRunKey is already present with the expected value (faithful guard). */
    private fun runGuardSatisfied(model: InstallScriptModel, tokens: InstallScriptTokens): Boolean {
        for (rp in model.runProcesses) {
            val keyPath = rp.hasRunKey ?: continue
            val (target, name) = tokens.resolveRunGuard(keyPath) ?: continue
            if (!target.hiveFile.exists()) continue
            val current = try {
                WineRegistryEditor(target.hiveFile).use { it.getStringValue(target.key, name) }
            } catch (e: Exception) { null }
            val expected = rp.hasRunValue
            if (current != null && (expected == null || current.equals(expected, true))) {
                Log.d(TAG, "Run-Process guard satisfied by '$keyPath'")
                return true
            }
        }
        return false
    }

    // ---- Script / install-dir location -----------------------------------------------------------

    /** The depot's install directory (`steam_games/<name>`) that [exeOrDir] lives under, or null. */
    fun locateInstallDir(exeOrDir: File): File? {
        val parts = exeOrDir.absolutePath.split('/')
        val idx = parts.indexOf("steam_games")
        if (idx < 0 || idx + 1 >= parts.size) return null
        return File(parts.subList(0, idx + 2).joinToString("/"))
    }

    /** Finds the game's main installScript.vdf under [installDir] (excludes `*_installScript.vdf`). */
    fun locateScript(installDir: File): File? {
        val direct = installDir.listFiles()?.firstOrNull {
            it.isFile && it.name.equals("installScript.vdf", true)
        }
        if (direct != null) return direct
        // Shallow fallback: a *installscript.vdf that isn't an uninstall companion.
        return installDir.listFiles()?.firstOrNull {
            it.isFile && it.name.lowercase().endsWith("installscript.vdf") &&
                !it.name.lowercase().contains("_installscript")
        }
    }

    // ---- Pending resume (prerequisite chain) -----------------------------------------------------

    private const val PENDING_KEY = "pending_after_prereq"   // "<containerId>|<appId>|<installDir>"

    private fun savePending(c: Context, containerId: Int, appId: Int, installDir: File) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PENDING_KEY, "$containerId|$appId|${installDir.absolutePath}").apply()

    private fun clearPending(c: Context) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING_KEY).apply()

    /** True when a Run-Process step is waiting on a prerequisite install (see [resumePending]). */
    @JvmStatic
    fun hasPending(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PENDING_KEY)

    /**
     * Re-drives a script whose Run-Process step was deferred behind a prerequisite (wine-mono). Call
     * once the component installer reports its plan finished (ComponentInstallResume → Done). Returns
     * the executor result, or null when nothing was pending. Best-effort; never throws.
     */
    @JvmStatic
    fun resumePending(context: Context): Result? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING_KEY, null) ?: return null
        return try {
            val parts = raw.split('|', limit = 3)
            val containerId = parts[0].toInt(); val appId = parts[1].toInt(); val installDir = File(parts[2])
            val container = ContainerManager(context).getContainerById(containerId)
            if (container == null || !installDir.isDirectory) { clearPending(context); return Result.Error("pending target gone") }
            val r = execute(context, container, appId, installDir, allowRunProcess = true)
            if (r !is Result.PrerequisiteLaunched) clearPending(context)
            Log.i(TAG, "resumePending appId=$appId container=$containerId -> ${r::class.simpleName}")
            r
        } catch (e: Exception) {
            clearPending(context)
            Log.w(TAG, "resumePending failed", e)
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * True when the script's own Run-Process guard (e.g. EA Desktop's `InstallSuccessful`) is already
     * satisfied in [container] — i.e. the bundled client is installed and a launch can proceed without
     * an install session. False when the script has no Run-Process step (nothing to install).
     */
    @JvmStatic
    fun clientInstalled(context: Context, container: Container, installDir: File): Boolean {
        val scriptFile = locateScript(installDir) ?: return true
        val model = try { InstallScriptModel.parse(scriptFile.readText()) } catch (e: Exception) { return true }
        if (!model.hasRunProcess) return true
        val tokens = InstallScriptTokens(container, installDir, File(context.filesDir, "imagefs"))
        return runGuardSatisfied(model, tokens)
    }

    /** True when [installDir] ships a main installScript with a Run-Process step (a bundled client installer). */
    @JvmStatic
    fun hasRunProcessScript(installDir: File): Boolean {
        val scriptFile = locateScript(installDir) ?: return false
        return try { InstallScriptModel.parse(scriptFile.readText()).hasRunProcess } catch (e: Exception) { false }
    }

    // ---- Per-(appId, container) guards -----------------------------------------------------------

    private fun localDone(c: Context, id: Int, appId: Int) = has(c, "local_c$id", appId)
    private fun markLocalDone(c: Context, id: Int, appId: Int) = mark(c, "local_c$id", appId)
    private fun runProcDone(c: Context, id: Int, appId: Int) = has(c, "runproc_c$id", appId)
    private fun markRunProcDone(c: Context, id: Int, appId: Int) = mark(c, "runproc_c$id", appId)

    private fun has(c: Context, key: String, appId: Int): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(key, emptySet())?.contains(appId.toString()) == true

    private fun mark(c: Context, key: String, appId: Int) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = p.getStringSet(key, emptySet()) ?: emptySet()
        if (appId.toString() !in cur) p.edit().putStringSet(key, cur + appId.toString()).apply()
    }

    // ---- Run-Process seam ------------------------------------------------------------------------

    /**
     * Pluggable backend for the script's `Run Process` step. The default [LiveInstallerRunProcessStage]
     * runs the bundled installer in a live container session. A future pre-baked-container backend can
     * implement this to no-op (EA Desktop already present) without touching Registry/Copy Files.
     */
    interface RunProcessStage {
        /** Runs [processes] for [container]. Returns true if a session was launched (app will restart). */
        fun run(
            context: Context, container: Container,
            processes: List<InstallScriptModel.RunProcess>, tokens: InstallScriptTokens,
        ): Boolean
    }

    /**
     * Runs the first `Run Process` entry as a live installer session, reusing the same auto-close
     * mechanism as [com.winlator.star.components.ComponentExecInstaller]: a transient `.desktop` with
     * `Exec=wine <exe>` + `[Extra Data]` execArgs/envVars, launched into [XServerDisplayActivity] with
     * `component_installer_exe` so the session ends when the installer exits.
     *
     * IMPORTANT: the command line is passed **verbatim** (leading `KEY=VALUE` tokens become env vars,
     * the rest args) — silent/quiet flags are NOT stripped (unlike ComponentExecInstaller), because the
     * EA installer is meant to run unattended (`EAX_LAUNCH_CLIENT=0 IGNORE_INSTALLED=1`).
     */
    object LiveInstallerRunProcessStage : RunProcessStage {
        override fun run(
            context: Context, container: Container,
            processes: List<InstallScriptModel.RunProcess>, tokens: InstallScriptTokens,
        ): Boolean {
            val rp = processes.firstOrNull { it.process.isNotBlank() } ?: return false
            val winPath = tokens.substituteWindows(rp.process)
            val execTarget = WinePath.escapeForExec(winPath)
            val exeBaseName = winPath.substringAfterLast('\\').substringAfterLast('/')
            val (envPairs, execArgs) = splitCommand(tokens.substituteWindows(rp.command))

            val desktopDir = File(context.filesDir, "desktops").apply { mkdirs() }
            val safe = rp.name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifEmpty { "installscript" }
            val shortcut = File(desktopDir, "installscript_$safe.desktop").apply {
                writeText(buildString {
                    append("[Desktop Entry]\n")
                    append("Name=").append(rp.name).append("\n")
                    append("Exec=wine ").append(execTarget).append("\n")
                    append("Type=Application\n")
                    append("StartupWMClass=explorer\n")
                    append("\ncontainer_id:").append(container.id).append("\n")
                    append("\n[Extra Data]\n")
                    if (execArgs.isNotEmpty()) append("execArgs=").append(execArgs).append("\n")
                    if (envPairs.isNotEmpty()) append("envVars=").append(envPairs).append("\n")
                })
            }

            val intent = Intent(context, XServerDisplayActivity::class.java)
            intent.putExtra("container_id", container.id)
            intent.putExtra("shortcut_path", shortcut.absolutePath)
            intent.putExtra("shortcut_name", shortcut.nameWithoutExtension)
            intent.putExtra("component_installer_exe", exeBaseName)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "Launched Run-Process session for '${rp.name}' ($exeBaseName)")
            return true
        }

        /** Splits a command line into (envVars, args): leading `NAME=VALUE` tokens are env vars. */
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
    }

    /**
     * Fallback seam target: a container pre-baked with EA Desktop already installed. Registry + Copy
     * Files still run (they're per-game); the Run-Process step becomes a no-op. Not wired by default —
     * a future setup can `InstallScriptExecutor.runProcessStage = PrebakedContainerRunProcessStage`.
     */
    object PrebakedContainerRunProcessStage : RunProcessStage {
        override fun run(
            context: Context, container: Container,
            processes: List<InstallScriptModel.RunProcess>, tokens: InstallScriptTokens,
        ): Boolean {
            Log.d(TAG, "Run-Process skipped (pre-baked container backend)")
            return false
        }
    }
}
