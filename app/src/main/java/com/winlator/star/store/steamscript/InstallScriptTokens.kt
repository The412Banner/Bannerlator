package com.winlator.star.store.steamscript

import com.winlator.star.container.Container
import com.winlator.star.xenvironment.ImageFs
import java.io.File

/**
 * Resolves installScript path tokens and Steam registry hive/WOW64 roots to concrete locations for a
 * specific container.
 *
 * Two path spaces exist and must not be mixed (see the recon notes):
 *  - **Windows space** — what goes into registry *values* and onto a `wine …` command line.
 *    `%INSTALLDIR%` is the game under the shared imagefs root, seen by Wine as **`Z:\steam_games\<name>`**
 *    (Winlator symlinks `dosdevices/z:` -> the imagefs root), NOT `C:`. `%PROGRAMDATA%` etc. are on the
 *    per-container `C:` drive.
 *  - **Host space** — real Android files, used to *copy* files in. `%INSTALLDIR%` is the on-disk depot
 *    dir; `%PROGRAMDATA%` is `<container>/.wine/drive_c/ProgramData`.
 *
 * Registry mapping mirrors how Wine stores hives on disk (matching the hand-rolled WOW64 handling in
 * [com.winlator.star.components.ComponentExecInstaller]): HKLM -> `system.reg`, HKCU -> `user.reg`,
 * and a `_WOW64_32` view rewrites a leading `Software\` to `Software\Wow6432Node\`.
 *
 * @param container  the target container (owns the prefix + drive_c)
 * @param installDir the on-disk depot directory (`%INSTALLDIR%` in host space)
 * @param imageFsRoot the app's imagefs root (`…/files/imagefs`), used to derive the Wine `Z:` path
 */
class InstallScriptTokens(
    private val container: Container,
    val installDir: File,
    imageFsRoot: File,
) {
    private val driveC = File(container.rootDir, ".wine/drive_c")

    /** `%INSTALLDIR%` as a Wine path, e.g. `Z:\steam_games\Need for Speed Payback`. */
    private val installDirWindows: String = run {
        val root = imageFsRoot.absolutePath.trimEnd('/')
        val abs = installDir.absolutePath
        val rel = if (abs.startsWith(root)) abs.substring(root.length).trimStart('/') else abs.trimStart('/')
        "Z:\\" + rel.replace('/', '\\')
    }

    // ---- Windows-space substitution (registry values, exe/command lines) -------------------------

    /** Substitutes the documented path tokens into a Wine (`C:`/`Z:`) path. Case-insensitive tokens. */
    fun substituteWindows(text: String): String {
        var s = text
        for ((token, value) in WINDOWS_TOKENS) s = replaceIgnoreCase(s, token, value)
        return s
    }

    private val WINDOWS_TOKENS = listOf(
        "%INSTALLDIR%" to installDirWindows,
        "%PROGRAMDATA%" to "C:\\ProgramData",
        "%USERDIR%" to "C:\\users\\${ImageFs.USER}",
        "%WINDIR%" to "C:\\windows",
        "%SYSTEMROOT%" to "C:\\windows",
    )

    /**
     * `%INSTALLDIR%` for **registry values**, e.g.
     * `C:\Program Files (x86)\Steam\steamapps\common\Need for Speed Payback`.
     *
     * Frostbite/EA titles read the game's module path out of their EA Games registry key and fail on a
     * non-ASCII one — which is why [com.winlator.star.store.RealSteamLauncher] links the depot into
     * `steamapps\common\<ascii name>`. EA Desktop launches the game from that registry value, so it must
     * be the ASCII link, not the raw `Z:\steam_games\<name>` depot path (a `™` there makes the game start
     * and quit again without ever creating its D3D device). Falls back to the `Z:` path when no such link
     * resolves to this depot (non-Steam launch, or the link is not made yet).
     *
     * Recomputed per read: the link is created by `RealSteamLauncher.prepare()`, which can run after these
     * tokens are constructed.
     */
    private val installDirRegistry: String
        get() {
            val depot = installDir.canonicalOrAbsolute()
            val common = File(driveC, "Program Files (x86)/Steam/steamapps/common")
            val link = (common.listFiles() ?: emptyArray()).firstOrNull { entry ->
                entry.name.all { it.code in 0x20..0x7E } && entry.canonicalOrAbsolute() == depot
            } ?: return installDirWindows
            return "C:\\Program Files (x86)\\Steam\\steamapps\\common\\" + link.name
        }

    private fun File.canonicalOrAbsolute(): File =
        runCatching { canonicalFile }.getOrElse { absoluteFile }

    /**
     * Like [substituteWindows], but resolves `%INSTALLDIR%` to the ASCII `steamapps\common` link when one
     * exists. Used for registry VALUES only — process/command paths keep the depot path they are proven on.
     */
    fun substituteWindowsForRegistry(text: String): String {
        var s = replaceIgnoreCase(text, "%INSTALLDIR%", installDirRegistry)
        for ((token, value) in WINDOWS_TOKENS) {
            if (token != "%INSTALLDIR%") s = replaceIgnoreCase(s, token, value)
        }
        return s
    }

    // ---- Host-space resolution (Copy Files / Delete Files) ---------------------------------------

    /** Resolves a token-bearing installScript path to a real Android [File] for host-side copies. */
    fun resolveHostPath(text: String): File {
        var s = text
        s = replaceIgnoreCase(s, "%INSTALLDIR%", installDir.absolutePath)
        s = replaceIgnoreCase(s, "%PROGRAMDATA%", File(driveC, "ProgramData").absolutePath)
        s = replaceIgnoreCase(s, "%USERDIR%", File(driveC, "users/${ImageFs.USER}").absolutePath)
        s = replaceIgnoreCase(s, "%WINDIR%", File(driveC, "windows").absolutePath)
        s = replaceIgnoreCase(s, "%SYSTEMROOT%", File(driveC, "windows").absolutePath)
        return File(s.replace('\\', '/'))
    }

    // ---- Registry hive / WOW64 mapping -----------------------------------------------------------

    /** Where a registry write lands: which hive file, and the key path below the hive root. */
    data class RegTarget(val hiveFile: File, val key: String)

    private val systemReg = File(container.rootDir, ".wine/system.reg")
    private val userReg = File(container.rootDir, ".wine/user.reg")

    /**
     * Maps a Steam hive-root token + a `\`-separated key path (below the hive) to the on-disk hive
     * file and the key path Wine's registry uses. Returns null for an unrecognised hive token.
     */
    fun registryTarget(hiveRoot: String, keyPath: String): RegTarget? {
        val h = hiveRoot.uppercase()
        val isUser = h.startsWith("HKEY_CURRENT_USER") || h.startsWith("HKCU")
        val isMachine = h.startsWith("HKEY_LOCAL_MACHINE") || h.startsWith("HKLM")
        if (!isUser && !isMachine) return null

        val wow32 = h.endsWith("_WOW64_32")
        val key = canonicalCase(if (wow32) toWow6432(keyPath) else keyPath)
        return RegTarget(if (isUser) userReg else systemReg, key)
    }

    /**
     * Every registry view a write must land in. The Steam client that runs installscripts on Windows
     * is a 32-bit process, so a hive root WITHOUT a `_WOW64_32` / `_WOW64_64` suffix is written under
     * WOW64 redirection: a plain `HKEY_LOCAL_MACHINE\\SOFTWARE\\Origin Games\\<id>` ends up in
     * `Software\\Wow6432Node\\Origin Games\\<id>`, which is where 32-bit readers (EA's Origin-era
     * `ActivationUI.exe`) look it up again, while a 64-bit reader sees the plain key. Writing only the
     * native view left ActivationUI reporting "DisplayName field missing from registry" on every
     * launch. Plain roots therefore go to BOTH views; explicit `_WOW64_32` / `_WOW64_64` roots stay
     * single-view as written.
     */
    fun registryTargets(hiveRoot: String, keyPath: String): List<RegTarget> {
        val primary = registryTarget(hiveRoot, keyPath) ?: return emptyList()
        val h = hiveRoot.uppercase()
        if (h.endsWith("_WOW64_32") || h.endsWith("_WOW64_64")) return listOf(primary)
        val redirected = canonicalCase(toWow6432(keyPath))
        if (redirected == primary.key) return listOf(primary)
        return listOf(primary, RegTarget(primary.hiveFile, redirected))
    }

    /**
     * Wine's .reg files store well-known key segments in a fixed case (`Software`, `Wow6432Node`,
     * `Microsoft\\Windows\\CurrentVersion\\Uninstall`) and [WineRegistryEditor] matches section headers
     * byte-for-byte, while Steam scripts spell them `SOFTWARE\\…`. Device-proven miss (2026-09-05): EA
     * Desktop's `InstallSuccessful` guard was present in both views yet read as absent, so the setup
     * dialog kept re-appearing. Normalise the leading segments to Wine's casing.
     */
    private fun canonicalCase(keyPath: String): String {
        val canon = mapOf(
            "software" to "Software", "wow6432node" to "Wow6432Node", "microsoft" to "Microsoft",
            "windows" to "Windows", "currentversion" to "CurrentVersion", "uninstall" to "Uninstall",
            "classes" to "Classes", "wine" to "Wine",
        )
        return keyPath.split('\\').joinToString("\\") { seg -> canon[seg.lowercase()] ?: seg }
    }

    /**
     * Parses a full `HasRunKey`-style path (which carries its own hive prefix and a trailing value
     * name) into a hive target plus the value name. e.g.
     * `HKEY_LOCAL_MACHINE\SOFTWARE\Electronic Arts\EA Desktop\InstallSuccessful`
     * -> (system.reg, key=`SOFTWARE\Electronic Arts\EA Desktop`, name=`InstallSuccessful`).
     */
    fun resolveRunGuard(fullPath: String): Pair<RegTarget, String>? {
        val norm = fullPath.trim().replace('/', '\\')
        val firstSep = norm.indexOf('\\')
        if (firstSep <= 0) return null
        val hive = norm.substring(0, firstSep)
        val rest = norm.substring(firstSep + 1)
        val lastSep = rest.lastIndexOf('\\')
        if (lastSep <= 0) return null
        val key = rest.substring(0, lastSep)
        val name = rest.substring(lastSep + 1)
        val target = registryTarget(hive, key) ?: return null
        return target to name
    }

    private fun toWow6432(keyPath: String): String {
        // Insert Wow6432Node after a leading "Software" segment (case-insensitive).
        val parts = keyPath.split('\\')
        if (parts.isNotEmpty() && parts[0].equals("Software", true) && (parts.size < 2 || !parts[1].equals("Wow6432Node", true))) {
            return (listOf(parts[0], "Wow6432Node") + parts.drop(1)).joinToString("\\")
        }
        return keyPath
    }

    companion object {
        private fun replaceIgnoreCase(text: String, token: String, value: String): String =
            Regex(Regex.escape(token), RegexOption.IGNORE_CASE).replace(text, Regex.escapeReplacement(value))
    }
}
