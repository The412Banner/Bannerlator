package com.winlator.star.store

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.winlator.star.R
import com.winlator.star.components.Component
import com.winlator.star.components.ComponentExecInstaller
import com.winlator.star.components.ComponentStep
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.core.unpack.ReadBuffer
import com.winlator.star.core.unpack.SevenZip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GOG gap#3 P1 — auto-install a gen2 game's required GOG "dependencies" (VC++/.NET/DirectX/…
 * redistributables) into the container's Wine prefix so DRM/steam-free titles that link those
 * runtimes actually launch.
 *
 * This is the orchestrator that bridges two things we already have:
 *   - the game's required dep ids, captured at download time by
 *     {@link GogDownloadManager} into {@code gog_deps_<gameId>};
 *   - {@link GogDependencyRepository}, which resolves+assembles each redist installer; and
 *   - {@link ComponentExecInstaller}, the existing exec-installer plan/restart engine that RUNS an
 *     installer inside the container (so Windows registers the runtime) and drives multi-installer
 *     sequences across the app-restart a container session forces.
 *
 * Redists install into the CONTAINER's prefix (runtimes register system-wide, not per-game), so the
 * only valid trigger is at/after add-to-container — never at download time (there is no target yet).
 *
 * Clean-room: behaviour mirrors GameNative's {@code GOGDependencyFix} by NAME only; no GN Kotlin was
 * read. Everything here is reconstructed from the live GOG protocol + our own machinery.
 *
 * Fail-soft throughout: a redist that won't resolve/download/stage is logged and skipped; it never
 * hard-blocks the game or the other redists.
 */
object GogRedistInstaller {

    private const val PREFS = "bh_gog_prefs"
    private const val INSTALL_STORE = "component_installs"

    /** Parsed {@code gog_deps_<gameId>} record. */
    data class DepsInfo(val deps: List<String>, val scriptInterpreter: Boolean)

    /**
     * Redists we know need the extraction fallback (self-extracting wrapper won't run cleanly under
     * our Wine/FEX — crack it open and run the inner .msi/.exe instead). EMPTY by default in P1: the
     * primary path always RUNS the real installer (which is what registers the runtime). The
     * extraction machinery below is wired and callable, but auto-triggering it on a runtime failure
     * needs the P3 mid-plan exit-code work (completion is currently inferred from session end).
     */
    private val NEEDS_EXTRACTION: Set<String> = emptySet()

    private fun mainHandler() = Handler(Looper.getMainLooper())

    private fun debug(ctx: Context, msg: String) = GogCloudSaveManager.debug(ctx, "REDIST: $msg")

    // ── Dep record --------------------------------------------------------------------------------

    /** Reads the persisted dep list for a game, or null when none was captured (gen1 / no deps). */
    @JvmStatic
    fun readGameDeps(ctx: Context, gameId: String): DepsInfo? {
        val raw = ctx.getSharedPreferences(PREFS, 0).getString("gog_deps_$gameId", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("deps") ?: JSONArray()
            val list = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            DepsInfo(list, o.optBoolean("scriptInterpreter", false))
        }.getOrNull()
    }

    /** True when this game declared at least one GOG dependency (redist). */
    @JvmStatic
    fun hasPrereqs(ctx: Context, gameId: String): Boolean =
        readGameDeps(ctx, gameId)?.deps?.isNotEmpty() == true

    private fun installedMarkers(ctx: Context, containerId: Int): Set<String> =
        ctx.getSharedPreferences(INSTALL_STORE, Context.MODE_PRIVATE)
            .getStringSet("c$containerId", emptySet()) ?: emptySet()

    // ── Triggers ----------------------------------------------------------------------------------

    /**
     * Post-add prompt: offered right after the game is added to a container (the moment we know the
     * target prefix). Shows what will be installed, warns a brief setup window will appear, and on
     * confirm drives the install. No-op (silent) when the game declared no deps.
     */
    @JvmStatic
    fun promptAndInstall(activity: Activity, container: Container, gameId: String) {
        val info = readGameDeps(activity, gameId)
        if (info == null || info.deps.isEmpty()) return
        mainHandler().post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val listStr = info.deps.joinToString(", ")
            AlertDialog.Builder(activity, R.style.StoreAlertDialogDark)
                .setTitle("Install prerequisites?")
                .setMessage(
                    "This game needs: $listStr.\n\n" +
                        "They'll be installed into \"${container.name}\". A brief setup window will " +
                        "appear for each one and close on its own."
                )
                .setPositiveButton("Install") { _, _ -> installPrereqs(activity, container, gameId) }
                .setNegativeButton("Skip", null)
                .show()
        }
    }

    /**
     * Manual "Install prerequisites" entry (GOG detail page): picks a container (add the game to one
     * first if there are none), then runs {@link #promptAndInstall}.
     */
    @JvmStatic
    fun promptContainerAndInstall(activity: Activity, gameId: String) {
        if (!hasPrereqs(activity, gameId)) {
            mainHandler().post { toast(activity, "No prerequisites needed for this game.") }
            return
        }
        Thread {
            val containers = runCatching { ContainerManager(activity).containers }.getOrNull()
            mainHandler().post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (containers.isNullOrEmpty()) {
                    toast(activity, "Add this game to a container first.")
                    return@post
                }
                if (containers.size == 1) {
                    promptAndInstall(activity, containers[0], gameId)
                    return@post
                }
                val names = containers.map { it.name?.ifEmpty { "Container ${it.id}" } ?: "Container ${it.id}" }
                    .toTypedArray()
                AlertDialog.Builder(activity, R.style.StoreAlertDialogDark)
                    .setTitle("Install prerequisites into…")
                    .setItems(names) { _, which -> promptAndInstall(activity, containers[which], gameId) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.also { it.name = "gog-redist-picker" }.start()
    }

    // ── Install orchestration ---------------------------------------------------------------------

    /**
     * Resolve → download → stage → build a plan → drive the exec-installer. Runs its network/assembly
     * on a background thread, then launches the container session on the main thread.
     */
    @JvmStatic
    fun installPrereqs(activity: Activity, container: Container, gameId: String) {
        val info = readGameDeps(activity, gameId) ?: return
        if (info.deps.isEmpty()) return
        val app = activity.applicationContext
        debug(app, "start game=$gameId container=${container.id} deps=${info.deps} " +
            "scriptInterpreter=${info.scriptInterpreter}")

        Thread {
            val cancelled = AtomicBoolean(false)
            val log = ConcurrentLinkedQueue<String>()
            val steps = ArrayList<ComponentStep>()
            try {
                val repo = GogDependencyRepository.fetchRepository()
                debug(app, "repository build_id=${GogDependencyRepository.lastBuildId()} entries=${repo.size}")
                if (repo.isEmpty()) {
                    finish(activity, "Couldn't reach GOG's redist repository — launch anyway.")
                    return@Thread
                }
                val installed = installedMarkers(app, container.id)
                val stageRoot = File(app.cacheDir, "gog_redist/$gameId").apply { mkdirs() }

                // Install in the game's declared dependency order (order matters for some stacks).
                for (depId in info.deps) {
                    val entry = repo[depId]
                    if (entry == null) { debug(app, "dep '$depId' not in repository — skip"); continue }
                    if (entry.internal) { debug(app, "dep '$depId' internal — skip"); continue }
                    val marker = "GOG:$depId"
                    if (marker in installed) { debug(app, "dep '$depId' already installed — skip"); continue }

                    val destDir = File(stageRoot, depId).apply { mkdirs() }
                    var installer = GogDependencyRepository.downloadRedist(entry, destDir, cancelled, log)
                    if (installer == null) {
                        debug(app, "dep '$depId' download/assemble FAILED — skip (game proceeds)")
                        continue
                    }
                    var action = if (entry.isMsi()) "install_msi" else "install_exe"
                    var args = entry.arguments

                    // Fallback: crack a stubborn self-extracting wrapper and run the inner installer.
                    if (depId in NEEDS_EXTRACTION) {
                        val inner = extractInnerInstaller(app, installer, log)
                        if (inner != null) {
                            installer = inner.first
                            action = if (inner.first.extension.equals("msi", true)) "install_msi" else "install_exe"
                            args = inner.second
                            debug(app, "dep '$depId' extraction fallback → ${installer.name}")
                        } else {
                            debug(app, "dep '$depId' extraction fallback failed — using original installer")
                        }
                    }

                    steps.add(buildStep(action, installer, args, marker))
                    debug(app, "resolved '$depId' → ${installer.name} args='$args' " +
                        "size=${entry.size} staged=${installer.absolutePath}")
                }

                for (line in log) debug(app, line)

                if (steps.isEmpty()) {
                    finish(activity, "Prerequisites already installed (or nothing to do).")
                    return@Thread
                }

                // ONE umbrella component with an ordered install_exe/msi step per redist. The
                // exec-installer's plan engine drives each as its own container session and resumes
                // across the app-restart a session forces — so a multi-redist game (e.g. Witcher 2 EE)
                // is driven to completion automatically. Each step carries its own GOG:<depId> marker.
                val component = Component(
                    name = "GOG Prerequisites",
                    description = "Auto-installed GOG redistributables",
                    provider = "gog",
                    status = "ready",
                    dependencies = emptyList(),
                    steps = steps,
                )
                debug(app, "plan launched: ${steps.size} redist step(s) into container ${container.id}")
                mainHandler().post {
                    if (activity.isFinishing || activity.isDestroyed) {
                        // Activity gone: the plan is persisted only once startInstall runs, so if we
                        // can't launch, just report. (Manual re-run from the detail page recovers.)
                        return@post
                    }
                    val res = ComponentExecInstaller.startInstall(app, container, component) {}
                    when (res) {
                        is ComponentExecInstaller.Result.Launched ->
                            toast(activity, "Installing prerequisites — a setup window will appear.")
                        is ComponentExecInstaller.Result.Done ->
                            toast(activity, "Prerequisites installed.")
                        is ComponentExecInstaller.Result.Error -> {
                            debug(app, "startInstall error: ${res.message}")
                            toast(activity, "Couldn't start prerequisite install: ${res.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                debug(app, "installPrereqs exception: $e")
                finish(activity, "Prerequisite install error — launch anyway.")
            }
        }.also { it.name = "gog-redist-install" }.start()
    }

    /** Build one exec-installer step pointing at a pre-staged local installer + its idempotency marker. */
    private fun buildStep(action: String, installer: File, arguments: String, marker: String): ComponentStep {
        val env = JSONObject()
            .put("local_file", installer.absolutePath)
            .put("file_name", installer.name)
            .put("arguments", arguments ?: "")
            .put("component_marker", marker)
        val obj = JSONObject().put("action", action).put("environment", env)
        return ComponentStep(action, obj)
    }

    // ── Extraction fallback -----------------------------------------------------------------------

    /**
     * Cracks a self-extracting redist wrapper with the File Manager's 7-Zip engine and returns the
     * inner installer to run instead ({@code file} to {@code arguments}). Prefers an inner {@code .msi}
     * (→ msiexec /qn), else an inner {@code .exe}. Returns null if extraction fails or yields nothing
     * usable — the caller then falls back to running the original wrapper.
     *
     * This is the plan's "reuse the in-app File Manager extract methods to pull the inner installer";
     * running still does the registering, extraction is only the unpack tool.
     */
    private fun extractInnerInstaller(
        ctx: Context, wrapper: File, log: ConcurrentLinkedQueue<String>,
    ): Pair<File, String>? {
        if (!SevenZip.isAvailable(ctx)) { log.add("7z unavailable — no extraction fallback"); return null }
        val dest = File(ctx.cacheDir, "gog_redist_extract/${wrapper.nameWithoutExtension}").apply {
            deleteRecursively(); mkdirs()
        }
        val res = runCatching {
            SevenZip.extract(ctx, wrapper, dest, mmt = 1, bufferBytes = ReadBuffer.MB1.bytes,
                listener = object : SevenZip.Listener {
                    override fun onProgress(percent: Int, currentFile: String?) {}
                    override fun onFile(name: String) {}
                }, onProcess = {})
        }.getOrNull()
        if (res == null || res.exitCode > 1) { log.add("extraction exit=${res?.exitCode}"); return null }

        // Run the inner installer with no extra args (Wine associates .msi with msiexec and runs an
        // .exe directly); the exec-installer shows its wizard, matching the visible-wizard model.
        val files = dest.walkTopDown().filter { it.isFile }.toList()
        files.firstOrNull { it.extension.equals("msi", true) }?.let { return it to "" }
        files.firstOrNull {
            it.extension.equals("exe", true) && !it.name.equals(wrapper.name, ignoreCase = true)
        }?.let { return it to "" }
        log.add("extraction produced no inner installer")
        return null
    }

    // ── UI helpers --------------------------------------------------------------------------------

    private fun finish(activity: Activity, msg: String) = mainHandler().post {
        if (!activity.isFinishing && !activity.isDestroyed) toast(activity, msg)
    }

    /**
     * Activities that host redist install feedback implement this so messages render on the shared
     * themed bar instead of a system Toast (which draws as an unreadable black box on this ROM,
     * targetSDK 28). GogGameDetailActivity implements it; other callers fall back to Toast.
     */
    interface Host { fun onRedistMessage(msg: String) }

    private fun toast(ctx: Context, msg: String) {
        if (ctx is Host) ctx.onRedistMessage(msg)
        else Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
}
