package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.components.ComponentCatalog
import com.winlator.star.components.ComponentExecInstaller
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

    @JvmStatic
    fun detectForShortcut(shortcut: Shortcut): Profile? = detect(installDirOf(shortcut))

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
     * Starts the wine-mono component install into [container] via the exec installer (its own
     * auto-closing Wine session; the app restarts afterwards and ComponentInstallResume finishes the
     * plan). Network: reads the components catalog. Returns true when a session was launched.
     */
    @JvmStatic
    fun startMonoInstall(context: Context, container: Container): Boolean {
        val catalog = try { ComponentCatalog.load() } catch (e: Exception) { emptyList() }
        val comp = MONO_COMPONENTS.firstNotNullOfOrNull { n -> catalog.firstOrNull { it.name.equals(n, true) } }
        if (comp == null) { Log.w(TAG, "no mono component in the catalog"); return false }
        return when (val r = ComponentExecInstaller.startInstall(context, container, comp) {}) {
            is ComponentExecInstaller.Result.Launched -> true
            is ComponentExecInstaller.Result.Done -> true   // nothing to run (already there) — caller re-checks
            is ComponentExecInstaller.Result.Error -> { Log.w(TAG, "mono install failed: ${r.message}"); false }
        }
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
