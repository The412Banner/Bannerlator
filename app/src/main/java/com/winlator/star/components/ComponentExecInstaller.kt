package com.winlator.star.components

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.contents.Downloader
import com.winlator.star.core.WinePath
import com.winlator.star.core.WineRegistryEditor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 3b — runs installer-based components (.NET runtimes, vcredist, …) whose Bottles steps
 * include `install_exe` / `install_msi` and therefore need Wine running inside the container.
 *
 * Because finishing a container session restarts the whole app (XServerDisplayActivity.exit ->
 * AppUtils.restartApplication), an install can't run start-to-finish in one go. So we persist an
 * ordered **install plan** (component + steps + a cursor) to SharedPreferences and drive it across
 * restarts:
 *
 *  1. Apply every leading "local" step (registry/override/file-drop, set_windows) immediately.
 *  2. On the first `install_exe`/`install_msi`: download the installer into the container's drive_c,
 *     write a transient .desktop that runs `wine <installer> <args>` with the step's env vars, bump
 *     the cursor past it, persist, and launch the container session. Control leaves here; the app
 *     restarts when the user closes the session.
 *  3. On next app start [resume] picks the plan back up from the cursor — the previous installer is
 *     assumed to have run (matching winetricks' fire-and-forget sequencing) — applies the following
 *     local steps, and either launches the next installer or finalizes and clears the plan.
 *
 * Completion of an installer is detected heuristically (session ended == installer done); there is
 * no exit code available across the app restart. This mirrors how winetricks/Bottles sequence these.
 */
object ComponentExecInstaller {
    private const val PREF_PLAN = "pending_component_install"
    private const val DLL_OVERRIDES = "Software\\Wine\\DllOverrides"

    // Local (no-Wine) actions we can apply directly to the prefix; the file-drop ones are delegated
    // to ComponentInstaller.runStep, set_windows/uninstall are handled here.
    private val FILE_DROP_ACTIONS = setOf(
        "download_archive", "archive_extract", "copy_dll", "copy_file",
        "override_dll", "set_register_key", "delete_dlls"
    )
    private val LOCAL_ACTIONS = FILE_DROP_ACTIONS + setOf("set_windows", "uninstall")
    private val EXEC_ACTIONS = setOf("install_exe", "install_msi")
    private val SUPPORTED_ACTIONS = LOCAL_ACTIONS + EXEC_ACTIONS

    // Keys inside an install_exe `environment` object that describe the file, not env vars.
    private val INSTALL_FILE_KEYS = setOf(
        "file_name", "url", "mirror", "rename", "file_checksum", "file_size", "arguments", "for"
    )

    sealed class Result {
        /** A container session was launched to run an installer; the app will restart when it ends. */
        object Launched : Result()
        /** All steps applied; component fully installed. */
        object Done : Result()
        data class Error(val message: String) : Result()
    }

    /** True when the component has any installer (`install_exe`/`install_msi`) step. */
    fun isExecComponent(c: Component): Boolean = c.steps.any { it.action in EXEC_ACTIONS }

    /**
     * True when this component must go through this installer rather than the pure file-drop path —
     * i.e. it has an installer step (`install_exe`/`install_msi`) OR a local action the file-drop
     * installer doesn't handle (`set_windows`/`uninstall`). Pure-`set_windows` comps (win7/winXP)
     * run fully inline here with no Wine session.
     */
    fun handlesComponent(c: Component): Boolean =
        c.steps.any { it.action in EXEC_ACTIONS || it.action == "set_windows" || it.action == "uninstall" }

    /** True when this is a component we can drive — mirrored and every step supported. */
    fun isExecInstallable(c: Component): Boolean =
        c.ready && c.steps.isNotEmpty() &&
            c.steps.all { it.action in SUPPORTED_ACTIONS }

    /** Reason a component routed here can't be driven yet, or null if it can. */
    fun execBlockedReason(c: Component): String? = when {
        c.status == "needs-upstream" -> "Needs a large package that isn't mirrored yet"
        c.status == "pending-manual" -> "Source unavailable — awaiting a re-hosted file"
        !isExecInstallable(c) -> "Uses an installer step not yet supported"
        else -> null
    }

    // ---- Plan persistence ------------------------------------------------------------------------

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun hasPendingPlan(context: Context): Boolean = prefs(context).contains(PREF_PLAN)

    /** Name of the component whose install is awaiting resume, or null. */
    fun pendingComponentName(context: Context): String? =
        prefs(context).getString(PREF_PLAN, null)?.let {
            runCatching { JSONObject(it).optString("componentName") }.getOrNull()
        }

    fun clearPlan(context: Context) = prefs(context).edit().remove(PREF_PLAN).apply()

    private fun savePlan(context: Context, containerId: Int, name: String, steps: List<ComponentStep>, cursor: Int) {
        val arr = JSONArray()
        steps.forEach { arr.put(it.obj) }
        val plan = JSONObject()
            .put("containerId", containerId)
            .put("componentName", name)
            .put("cursor", cursor)
            .put("steps", arr)
        prefs(context).edit().putString(PREF_PLAN, plan.toString()).apply()
    }

    // ---- Entry points ----------------------------------------------------------------------------

    /** Begin installing an exec component from the first step. */
    fun startInstall(context: Context, container: Container, c: Component, onProgress: (Float) -> Unit): Result {
        if (!File(container.rootDir, ".wine").isDirectory)
            return Result.Error("Container has no Wine prefix yet — launch it once first.")
        return runFrom(context, container, c.name, c.steps, 0, onProgress)
    }

    /**
     * Resume a persisted plan after an app restart (call from app startup). The installer launched
     * before the restart is assumed to have completed. Returns null if there is no pending plan.
     */
    fun resume(context: Context, onProgress: (Float) -> Unit = {}): Result? {
        val raw = prefs(context).getString(PREF_PLAN, null) ?: return null
        val plan = runCatching { JSONObject(raw) }.getOrNull()
            ?: run { clearPlan(context); return Result.Error("Corrupt install plan — cleared.") }
        val containerId = plan.optInt("containerId", 0)
        val name = plan.optString("componentName")
        val cursor = plan.optInt("cursor", 0)
        val stepsArr = plan.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsArr.length()).map {
            val o = stepsArr.getJSONObject(it); ComponentStep(o.optString("action", ""), o)
        }
        val container = ContainerManager(context).getContainerById(containerId)
            ?: run { clearPlan(context); return Result.Error("Container for $name no longer exists.") }
        return runFrom(context, container, name, steps, cursor, onProgress)
    }

    // ---- Driver ----------------------------------------------------------------------------------

    private fun runFrom(
        context: Context, container: Container, name: String,
        steps: List<ComponentStep>, startCursor: Int, onProgress: (Float) -> Unit,
    ): Result {
        val root = container.rootDir
        val system32 = File(root, ".wine/drive_c/windows/system32")
        val syswow64 = File(root, ".wine/drive_c/windows/syswow64")
        val userReg = File(root, ".wine/user.reg")
        val systemReg = File(root, ".wine/system.reg")
        val tmp = File(context.cacheDir, "compexec_${name}").apply { mkdirs() }
        val total = steps.size.coerceAtLeast(1)
        try {
            var i = startCursor
            while (i < steps.size) {
                val step = steps[i]
                onProgress(i.toFloat() / total)
                when {
                    step.action in EXEC_ACTIONS -> {
                        // Persist the cursor PAST this step (optimistic) before we leave for the session,
                        // so resume continues after it once the app restarts.
                        savePlan(context, container.id, name, steps, i + 1)
                        launchInstaller(context, container, name, step, onProgress)
                        return Result.Launched
                    }
                    step.action == "set_windows" -> setWindowsVersion(systemReg, step.str("version"))
                    step.action == "uninstall" -> { /* needs Wine; skipped in v1 (mscoree override handles .NET) */ }
                    step.action in FILE_DROP_ACTIONS -> ComponentInstaller.runStep(step, tmp, system32, syswow64, userReg)
                    else -> throw IllegalStateException("unsupported action: ${step.action}")
                }
                i++
            }
            // Reached the end with no further installer — done. Drop the staged installer exes.
            File(root, ".wine/drive_c/windows/temp/bannerlator_components").deleteRecursively()
            clearPlan(context)
            onProgress(1f)
            return Result.Done
        } catch (e: Exception) {
            e.printStackTrace()
            clearPlan(context)
            return Result.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ---- install_exe / install_msi --------------------------------------------------------------

    /** Resolve an install step's file fields from either the top level or a nested `environment` object. */
    private fun installFields(step: ComponentStep): Pair<JSONObject, JSONObject?> {
        val env = step.obj.optJSONObject("environment")
        // The object carrying file_name/url/arguments: nested environment if present, else the step itself.
        val fields = env ?: step.obj
        return fields to env
    }

    private fun launchInstaller(
        context: Context, container: Container, name: String, step: ComponentStep, onProgress: (Float) -> Unit,
    ) {
        val (fields, env) = installFields(step)
        val url = fields.optString("mirror").ifEmpty { fields.optString("url") }
        if (!url.startsWith("http")) throw IllegalStateException("$name: installer has no download URL")
        val rawName = fields.optString("rename").ifEmpty {
            fields.optString("file_name").ifEmpty { url.substringBefore('?').substringAfterLast('/') }
        }
        val safe = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_").ifEmpty { "installer.exe" }

        // Stage the installer inside the container's drive_c so it's reachable from Wine.
        val destDir = File(container.rootDir, ".wine/drive_c/windows/temp/bannerlator_components").apply { mkdirs() }
        val installer = File(destDir, safe)
        if (!Downloader.downloadFile(url, installer) { f -> onProgress(f) })
            throw IllegalStateException("$name: download failed ($safe)")

        // Wine runs an .exe directly and associates .msi with msiexec, so handing either to
        // `wine <path>` works; we pass the manifest's arguments through unchanged.
        val winPath = WinePath.resolveWindowsPath(container, installer.absolutePath)
        val execTarget = WinePath.escapeForExec(winPath)
        val execArgs = fields.optString("arguments").trim()

        // Env vars = whatever's in the environment object that isn't a file field (e.g. WINEDLLOVERRIDES).
        val envPairs = StringBuilder()
        env?.let { e ->
            e.keys().forEach { k ->
                if (k !in INSTALL_FILE_KEYS) {
                    if (envPairs.isNotEmpty()) envPairs.append(' ')
                    envPairs.append(k).append('=').append(e.optString(k))
                }
            }
        }

        val desktopDir = File(context.filesDir, "desktops").apply { mkdirs() }
        val shortcutName = "component_${name}"
        val shortcut = File(desktopDir, "$shortcutName.desktop").apply {
            writeText(buildString {
                append("[Desktop Entry]\n")
                append("Name=").append(name).append(" installer\n")
                append("Exec=wine ").append(execTarget).append("\n")
                append("Icon=").append(shortcutName).append("\n")
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
        // Tells XServerDisplayActivity to auto-close the session once this installer process exits,
        // so the user doesn't have to manually exit the container after each installer.
        intent.putExtra("component_installer_exe", safe)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ---- set_windows -----------------------------------------------------------------------------

    private data class WinVer(
        val version: String, val build: String, val csd: String,
        val product: String, val spLevel: Int, val major: Int, val minor: Int,
    )

    // winetricks/winecfg-aligned values for the versions used by the system-library set.
    private val WIN_VERSIONS = mapOf(
        "win20" to WinVer("4.0", "1381", "Service Pack 6a", "Microsoft Windows NT", 6, 4, 0),
        "winxp" to WinVer("5.1", "2600", "Service Pack 3", "Microsoft Windows XP", 3, 5, 1),
        "win2k" to WinVer("5.0", "2195", "Service Pack 4", "Microsoft Windows 2000", 4, 5, 0),
        "win2k3" to WinVer("5.2", "3790", "Service Pack 2", "Microsoft Windows 2003", 2, 5, 2),
        "vista" to WinVer("6.0", "6002", "Service Pack 2", "Microsoft Windows Vista", 2, 6, 0),
        "win7" to WinVer("6.1", "7601", "Service Pack 1", "Microsoft Windows 7", 1, 6, 1),
        "win8" to WinVer("6.2", "9200", "", "Microsoft Windows 8", 0, 6, 2),
        "win81" to WinVer("6.3", "9600", "", "Microsoft Windows 8.1", 0, 6, 3),
        "win10" to WinVer("10.0", "19043", "", "Microsoft Windows 10", 0, 10, 0),
        "win11" to WinVer("10.0", "22000", "", "Microsoft Windows 11", 0, 10, 0),
    )

    /** Set the prefix's reported Windows version (system.reg), mirroring winecfg's version dropdown. */
    private fun setWindowsVersion(systemReg: File, version: String) {
        val v = WIN_VERSIONS[version] ?: return
        if (!systemReg.exists()) return
        WineRegistryEditor(systemReg).use { reg ->
            reg.setCreateKeyIfNotExist(true)
            for (base in listOf(
                "Software\\Microsoft\\Windows NT\\CurrentVersion",
                "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion",
            )) {
                reg.setStringValue(base, "CurrentVersion", v.version)
                reg.setStringValue(base, "CurrentBuildNumber", v.build)
                reg.setStringValue(base, "CSDVersion", v.csd)
                reg.setStringValue(base, "ProductName", v.product)
                if (v.major >= 10) {
                    reg.setDwordValue(base, "CurrentMajorVersionNumber", v.major)
                    reg.setDwordValue(base, "CurrentMinorVersionNumber", v.minor)
                } else {
                    reg.removeValue(base, "CurrentMajorVersionNumber")
                    reg.removeValue(base, "CurrentMinorVersionNumber")
                }
            }
            reg.setStringValue("System\\CurrentControlSet\\Control\\ProductOptions", "ProductType", "WinNT")
            reg.setDwordValue("System\\CurrentControlSet\\Control\\Windows", "CSDVersion", v.spLevel shl 8)
            // Drop any per-app version override so the global version takes effect.
            reg.removeValue("Software\\Wine\\AppDefaults", "Version")
        }
    }
}
