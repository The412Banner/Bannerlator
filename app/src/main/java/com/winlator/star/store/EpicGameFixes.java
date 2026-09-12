/*
 * Per-game launch fixes for Epic titles (Feature #7).
 *
 * A small registry of Epic appName -> fix descriptor, applied at launch. Three fix kinds are
 * supported, each addressing a known per-title launch problem:
 *
 *   1. Registry "Installed Path" (Bethesda titles) — some Bethesda launchers/games read their
 *      install directory from a Wine-prefix registry value; without it they fail to find their
 *      data. We write HKLM\Software\Wow6432Node\Bethesda Softworks\<Game>\"Installed Path" =
 *      the game's Windows install directory, only when it is not already set.
 *
 *   2. WINEDLLOVERRIDES (Kingdom Hearts III) — the title needs Media Foundation disabled so Wine's
 *      builtin stubs are used (mf / mfmediaengine set to native=off). Merged into the launch env.
 *
 *   3. Folder cache-wipe (Hogwarts Legacy) — the game caches state under C:\ProgramData that
 *      occasionally leaves Denuvo/EOS wedged after a prior session; wiping it before each launch
 *      unsticks it. The highest-value fix of the three.
 *
 * The three games/values are factual data (which appName, which registry path, which DLL string,
 * which folder) taken from GameNative's gamefixes/EPIC_*.kt. Only that data is reused; this is an
 * independent implementation built around Bannerlator's own launch path.
 *
 * appName identity (Epic catalog appName):
 *   Registry "Installed Path":
 *     59a0c86d02da42e8ba6444cb171e61bf  The Elder Scrolls IV: Oblivion  -> Software\Wow6432Node\Bethesda Softworks\Oblivion
 *     b1b4e0b67a044575820cb5e63028dcae  Fallout 3                        -> Software\Wow6432Node\Bethesda Softworks\Fallout3
 *     dabb52e328834da7bbe99691e374cb84  Fallout: New Vegas               -> Software\Wow6432Node\Bethesda Softworks\FalloutNV
 *   WINEDLLOVERRIDES:
 *     e345fdb9186645a48d30c3f85a8951dc  Kingdom Hearts III               -> mf=n;mfmediaengine=n
 *   Folder cache-wipe (relative to drive_c):
 *     864c7bc2c2394f7dbd1b534aa068ff56  Hogwarts Legacy                  -> ProgramData/Hogwarts Legacy
 */
package com.winlator.star.store;

import android.content.Context;
import android.util.Log;

import com.winlator.star.container.Shortcut;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.WineRegistryEditor;
import com.winlator.star.xenvironment.ImageFs;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies per-game launch fixes for Epic titles. All entry points are best-effort and never throw —
 * a fix that can't apply leaves the launch unchanged.
 */
public final class EpicGameFixes {

    private static final String TAG = "BH_EPIC_GAMEFIX";

    private EpicGameFixes() {}

    /** The registry value name Bethesda titles read their install directory from. */
    private static final String BETHESDA_INSTALLED_PATH = "Installed Path";

    /** appName -> Bethesda registry key that needs an "Installed Path" value. */
    private static final Map<String, String> REGISTRY_INSTALLED_PATH = new HashMap<>();
    /** appName -> WINEDLLOVERRIDES entries to ensure present. */
    private static final Map<String, String> DLL_OVERRIDES = new HashMap<>();
    /** appName -> drive_c-relative folders to wipe before each launch. */
    private static final Map<String, List<String>> FOLDER_WIPES = new HashMap<>();

    static {
        REGISTRY_INSTALLED_PATH.put("59a0c86d02da42e8ba6444cb171e61bf",
                "Software\\Wow6432Node\\Bethesda Softworks\\Oblivion");
        REGISTRY_INSTALLED_PATH.put("b1b4e0b67a044575820cb5e63028dcae",
                "Software\\Wow6432Node\\Bethesda Softworks\\Fallout3");
        REGISTRY_INSTALLED_PATH.put("dabb52e328834da7bbe99691e374cb84",
                "Software\\Wow6432Node\\Bethesda Softworks\\FalloutNV");

        DLL_OVERRIDES.put("e345fdb9186645a48d30c3f85a8951dc", "mf=n;mfmediaengine=n");

        FOLDER_WIPES.put("864c7bc2c2394f7dbd1b534aa068ff56",
                Collections.singletonList("ProgramData/Hogwarts Legacy"));
    }

    /** The Epic appName stamped on {@code shortcut}, or "" when this isn't an Epic shortcut. */
    private static String epicAppName(Shortcut shortcut) {
        if (shortcut == null) return "";
        if (!"epic".equals(shortcut.getExtra("storeSource"))) return "";
        return shortcut.getExtra("epicAppName", "");
    }

    /**
     * Filesystem-side fixes applied before launch: the Bethesda registry "Installed Path" write and
     * the folder cache-wipe. Runs on the background launch setup thread (system.reg is already
     * generated and a folder wipe may be large), so blocking I/O here is ANR-safe. No-op for games
     * without a registered fix.
     */
    public static void applyPreLaunch(Context ctx, Shortcut shortcut) {
        String appName = epicAppName(shortcut);
        if (ctx == null || appName.isEmpty()) return;
        try {
            applyRegistryInstalledPath(ctx, shortcut, appName);
            applyFolderWipes(ctx, appName);
        } catch (Throwable t) {
            Log.w(TAG, "applyPreLaunch failed for " + appName, t);
        }
    }

    /**
     * Env-side fix applied while the launch environment is being assembled: merge any required
     * WINEDLLOVERRIDES entries into {@code envVars} for this game. Existing overrides are preserved —
     * our entries are appended if absent (never clobbering a user/container value). No-op for games
     * without a registered fix.
     */
    public static void applyEnv(Context ctx, Shortcut shortcut, EnvVars envVars) {
        String appName = epicAppName(shortcut);
        if (envVars == null || appName.isEmpty()) return;
        try {
            String required = DLL_OVERRIDES.get(appName);
            if (required == null) return;
            String merged = mergeDllOverrides(envVars.has("WINEDLLOVERRIDES") ? envVars.get("WINEDLLOVERRIDES") : null,
                    required);
            envVars.put("WINEDLLOVERRIDES", merged);
            Log.i(TAG, "applied WINEDLLOVERRIDES for " + appName + ": " + merged);
        } catch (Throwable t) {
            Log.w(TAG, "applyEnv failed for " + appName, t);
        }
    }

    /**
     * Merge {@code required} (";"-separated dll=mode entries) into an existing WINEDLLOVERRIDES
     * string, appending only entries whose dll key isn't already present so a user's explicit
     * override for the same dll wins.
     */
    static String mergeDllOverrides(String existing, String required) {
        if (existing == null || existing.trim().isEmpty()) return required;
        StringBuilder sb = new StringBuilder(existing.trim());
        String lowerExisting = existing.toLowerCase(java.util.Locale.ROOT);
        for (String entry : required.split(";")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int eq = e.indexOf('=');
            String key = (eq >= 0 ? e.substring(0, eq) : e).trim().toLowerCase(java.util.Locale.ROOT);
            // Skip if this dll already has an override entry (match "key=" or bare "key").
            if (lowerExisting.matches(".*(^|;)\\s*" + java.util.regex.Pattern.quote(key) + "\\s*(=|;|$).*")) continue;
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ';') sb.append(';');
            sb.append(e);
        }
        return sb.toString();
    }

    private static void applyRegistryInstalledPath(Context ctx, Shortcut shortcut, String appName) {
        String key = REGISTRY_INSTALLED_PATH.get(appName);
        if (key == null) return;

        String winInstallDir = windowsInstallDir(shortcut);
        if (winInstallDir.isEmpty()) {
            Log.w(TAG, "registry fix: could not derive install dir for " + appName + "; skipping");
            return;
        }

        File systemReg = new File(ImageFs.find(ctx).wineprefix, "system.reg");
        if (!systemReg.exists()) {
            Log.w(TAG, "registry fix: system.reg not found at " + systemReg.getAbsolutePath());
            return;
        }
        try (WineRegistryEditor editor = new WineRegistryEditor(systemReg)) {
            editor.setCreateKeyIfNotExist(true);
            String existing = editor.getStringValue(key, BETHESDA_INSTALLED_PATH, null);
            if (existing == null || existing.isEmpty()) {
                editor.setStringValue(key, BETHESDA_INSTALLED_PATH, winInstallDir);
                Log.i(TAG, "registry fix: set [" + key + "] \"" + BETHESDA_INSTALLED_PATH + "\"="
                        + winInstallDir + " for " + appName);
            }
        }
    }

    private static void applyFolderWipes(Context ctx, String appName) {
        List<String> folders = FOLDER_WIPES.get(appName);
        if (folders == null) return;
        File driveC = new File(ImageFs.find(ctx).wineprefix, "drive_c");
        for (String rel : folders) {
            File target = new File(driveC, rel);
            if (!target.exists()) continue;
            if (FileUtils.delete(target)) {
                Log.i(TAG, "folder-wipe: deleted " + target.getAbsolutePath() + " for " + appName);
            } else {
                Log.w(TAG, "folder-wipe: partial/failed delete of " + target.getAbsolutePath() + " for " + appName);
            }
        }
    }

    /**
     * The game's install directory as a Windows path (e.g. {@code Z:\epic_games\game}), derived from
     * the shortcut's exe path (which is stored as a DOS path like {@code Z:\...\game.exe}). Returns ""
     * when no directory component can be found.
     */
    static String windowsInstallDir(Shortcut shortcut) {
        if (shortcut == null || shortcut.path == null) return "";
        String p = shortcut.path.trim();
        if (p.isEmpty()) return "";
        int slash = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
        if (slash <= 0) return "";
        return p.substring(0, slash);
    }

    /** Whether any per-game fix is registered for the given Epic shortcut (for callers/logging). */
    public static boolean hasFixFor(Shortcut shortcut) {
        String appName = epicAppName(shortcut);
        if (appName.isEmpty()) return false;
        return REGISTRY_INSTALLED_PATH.containsKey(appName)
                || DLL_OVERRIDES.containsKey(appName)
                || FOLDER_WIPES.containsKey(appName);
    }

    // Kept for readability of the registered-appName set; not otherwise referenced.
    @SuppressWarnings("unused")
    private static final List<String> ALL_FIXED_APPS = Arrays.asList(
            "59a0c86d02da42e8ba6444cb171e61bf", "b1b4e0b67a044575820cb5e63028dcae",
            "dabb52e328834da7bbe99691e374cb84", "e345fdb9186645a48d30c3f85a8951dc",
            "864c7bc2c2394f7dbd1b534aa068ff56");
}
