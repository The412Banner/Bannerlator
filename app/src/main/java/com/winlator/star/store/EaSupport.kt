package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.components.ComponentCatalog
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.WinePath
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.store.steamscript.InstallScriptExecutor
import java.io.File

/**
 * EA-published Steam titles ("EA support"): detection + the launch settings that make them run.
 *
 * Device-proven recipe (Need for Speed Payback, 2026-09-05, GE-Proton 11.0-6 arm64ec): the game must be
 * launched through the genuine Steam client (SteamLite), whose launch goes via EA's chain
 * (`Link2EA.exe` → `EADesktop.exe` → `EASteamProxy.exe` → `ActivationUI.exe` → the real game exe);
 * EA Desktop must be installed in the prefix (the depot's installScript "Run Process" step, which needs
 * wine-mono for its managed MSI custom actions); the SteamLite agent must know the chain
 * (`WN_STEAM_LAUNCH_CHAIN`) so it holds the session across the stub exe's hand-off; and FEX must keep
 * self-modifying-code checks ON (EA's Activation64 anti-tamper crashes under `FEX_SMCCHECKS=none`), so
 * the Extreme presets are clamped to their Performance counterparts for these launches.
 *
 * Titles shipping EA Javelin anti-cheat need a kernel driver and can never run under Wine — they are
 * flagged [Profile.javelinAntiCheat] so the UI refuses before the user sits through the EA setup.
 */
object EaSupport {
    private const val TAG = "EaSupport"

    /** Agent env: launcher-chain exe names (agent p5 / SteamLite v6). No default in the agent — must be set. */
    const val CHAIN_ENV = "WN_STEAM_LAUNCH_CHAIN"
    const val CHAIN_VALUE = "Link2EA.exe;EADesktop.exe;EASteamProxy.exe;EACefSubProcess.exe;ActivationUI.exe"

    /** Shortcut [Extra Data] tags, stamped at shortcut write (StarLaunchBridge) like storeSource/steamAppId. */
    const val EXTRA_EA = "eaSupport"
    const val EXTRA_JAVELIN = "eaAntiCheat"

    /** Catalog component names that provide wine-mono, most preferred first (10.4.1 = device-proven). */
    private val MONO_COMPONENTS = listOf("mono-10.4.1", "mono-10.3.0", "mono-10.1.0", "mono")

    data class Profile(
        /** Launch goes through EA Desktop (Link2EA chain) — needs SteamLite + the chain env + EA Desktop installed. */
        val eaChain: Boolean,
        /** Ships EA Javelin anti-cheat (kernel driver) — unsupported under Wine. */
        val javelinAntiCheat: Boolean,
    )

    // ---- Detection ------------------------------------------------------------------------------

    /** Detects EA markers on disk under the game's depot root. Null = not an EA title. */
    @JvmStatic
    fun detect(installDir: File?): Profile? {
        if (installDir == null || !installDir.isDirectory) return null
        val ea = File(installDir, "__Installer/Origin").isDirectory ||
            File(installDir, "Link2EA.exe").isFile ||
            File(installDir, "EASteamProxy.exe").isFile ||
            File(installDir, "Core/Activation64.dll").isFile ||
            File(installDir, "Core/Activation.dll").isFile ||
            File(installDir, "Core/ActivationUI.exe").isFile ||
            File(installDir, "EAAntiCheat.GameServiceLauncher.exe").isFile
        if (!ea) return null
        val javelin = File(installDir, "EAAntiCheat.GameServiceLauncher.exe").isFile ||
            File(installDir, "__Installer/EAAntiCheat").isDirectory ||
            File(installDir, "EAAntiCheat").isDirectory
        return Profile(eaChain = true, javelinAntiCheat = javelin)
    }

    /** The depot root for a Steam shortcut (resolves the Windows exe path back to the Android install dir). */
    @JvmStatic
    fun installDirOf(shortcut: Shortcut): File? {
        val exe = try { WinePath.resolveAndroidPath(shortcut.container, shortcut.path) } catch (e: Exception) { null }
        exe?.let { InstallScriptExecutor.locateInstallDir(it) }?.let { if (it.isDirectory) return it }
        // Fallback straight from the Exec path: "...\steam_games\<folder>\..." -> <imagefs>/steam_games/<folder>.
        // Covers a drive letter the resolver can't map (device-seen: Z: before it was taught the imagefs root).
        val win = shortcut.path.replace('\\', '/')
        val idx = win.indexOf("steam_games/", ignoreCase = true)
        if (idx >= 0) {
            val folder = win.substring(idx + "steam_games/".length).substringBefore('/')
            val imagefs = shortcut.container.rootDir.parentFile?.parentFile
            if (folder.isNotEmpty() && imagefs != null) {
                File(imagefs, "steam_games/$folder").takeIf { it.isDirectory }?.let { return it }
            }
        }
        Log.w(TAG, "installDirOf: could not resolve '${shortcut.path}' (container ${shortcut.container.id})")
        return null
    }

    /** True when the shortcut carries the EA tag (no disk access). */
    @JvmStatic
    fun isTagged(shortcut: Shortcut): Boolean = shortcut.getExtra(EXTRA_EA, "") == "1"

    /**
     * Tag first (stamped at shortcut write — no path resolution involved), then on-disk detection for
     * shortcuts written before the tag existed; a positive disk result is written back onto the shortcut
     * so the next launch is a plain lookup.
     */
    @JvmStatic
    fun detectForShortcut(shortcut: Shortcut): Profile? {
        if (isTagged(shortcut)) {
            return Profile(eaChain = true, javelinAntiCheat = shortcut.getExtra(EXTRA_JAVELIN, "") == "1")
        }
        val fromDisk = detect(installDirOf(shortcut)) ?: return null
        try {
            shortcut.putExtra(EXTRA_EA, "1")
            if (fromDisk.javelinAntiCheat) shortcut.putExtra(EXTRA_JAVELIN, "1")
        } catch (e: Exception) { Log.w(TAG, "could not stamp EA tag on ${shortcut.name}", e) }
        return fromDisk
    }

    // ---- Launch settings ------------------------------------------------------------------------

    /**
     * EA's Activation64 anti-tamper self-modifies code at runtime; FEX_SMCCHECKS=none (the Extreme
     * presets) executes stale code → c0000005 in the game exe. Clamp to the Performance twin, which
     * keeps the same TSO choice with default SMC checks (the user's device-proven configuration).
     */
    @JvmStatic
    fun clampPresetForEa(preset: String?): String? = when (preset) {
        FEXCorePreset.EXTREME -> FEXCorePreset.PERFORMANCE
        FEXCorePreset.EXTREME_TSO -> FEXCorePreset.PERFORMANCE_TSO
        else -> preset
    }

    // ---- wine-mono prerequisite -----------------------------------------------------------------

    @JvmStatic
    fun hasMono(container: Container): Boolean =
        File(container.rootDir, ".wine/drive_c/windows/mono").isDirectory

    /** EA's bundled installer is the only known Run-Process exe whose MSI needs managed (.NET) custom actions. */
    @JvmStatic
    fun runProcessNeedsMono(processPath: String): Boolean =
        processPath.contains("EAappInstaller", ignoreCase = true) || processPath.contains("EAapp", ignoreCase = true)

    /**
     * Downloads the wine-mono MSI (from the components catalog's mono entry) into the container's
     * `windows\temp\bannerlator_components` — the same staging dir the component installer uses —
     * and returns it. It is NOT run here: the installScript setup session installs it silently
     * (`msiexec /i … /qn`) right before the bundled EA installer, in one session. Network. Null on failure.
     */
    @JvmStatic
    fun stageMonoMsi(context: Context, container: Container): File? {
        val catalog = try { ComponentCatalog.load() } catch (e: Exception) { emptyList() }
        val comp = MONO_COMPONENTS.firstNotNullOfOrNull { n -> catalog.firstOrNull { it.name.equals(n, true) } }
        val step = comp?.steps?.firstOrNull { it.action == "install_msi" }
        if (comp == null || step == null) { Log.w(TAG, "no mono install_msi component in the catalog"); return null }
        val fields = step.obj.optJSONObject("environment") ?: step.obj
        val url = fields.optString("mirror").ifEmpty { fields.optString("url") }
        if (!url.startsWith("http")) { Log.w(TAG, "mono component has no download URL"); return null }
        val rawName = fields.optString("rename").ifEmpty { fields.optString("file_name").ifEmpty { url.substringBefore('?').substringAfterLast('/') } }
        val safe = rawName.replace(Regex("""[\\/:*?"<>|\s]"""), "_").ifEmpty { "wine-mono.msi" }
        val destDir = File(container.rootDir, ".wine/drive_c/windows/temp/bannerlator_components").apply { mkdirs() }
        val dest = File(destDir, safe)
        val expected = fields.optString("file_size").toLongOrNull() ?: 0L
        if (dest.isFile && (expected == 0L || dest.length() == expected)) return dest
        val ok = try { com.winlator.star.contents.Downloader.downloadFile(url, dest) { } } catch (e: Exception) { Log.w(TAG, "mono download failed", e); false }
        if (!ok || !dest.isFile || (expected > 0L && dest.length() != expected)) { dest.delete(); Log.w(TAG, "mono download incomplete"); return null }
        return dest
    }

    // ---- Readiness --------------------------------------------------------------------------------

    /**
     * True when the prefix is ready for an EA launch: wine-mono present and the depot's installScript
     * Run-Process guard (EA Desktop `InstallSuccessful`) satisfied. False → run
     * [InstallScriptExecutor.runForShortcut] first (it chains mono → EA installer) instead of launching.
     */
    @JvmStatic
    fun prefixReady(context: Context, container: Container, installDir: File): Boolean {
        if (!InstallScriptExecutor.hasRunProcessScript(installDir)) return true
        return hasMono(container) && InstallScriptExecutor.clientInstalled(context, container, installDir)
    }
}
