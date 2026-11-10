/*
 * Per-game launch fixes for Epic titles (Feature #7).
 *
 * A small registry of Epic games -> fixes, applied at launch. Fix kinds:
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
 *      unsticks it.
 *
 *   4. Config-file lines (Trover Saves the Universe) — key=value lines merged into the game's own
 *      Unreal Engine.ini under drive_c; every other line in the file is kept. Trover: start on the
 *      lobby map instead of the startup-movie map (its video playback fails), an 8-bit colour target
 *      (r.SceneColorFormat=2, black screen on Turnip otherwise) and the VR plugins off.
 *
 *   5. Environment defaults and launch arguments (Trover) — a variable is only set when the launch
 *      environment does not have it yet (shortcut/container values win), an argument only when the
 *      command does not already carry it.
 *
 * Games are keyed by their Epic catalog item id, the id GameNative's gamefixes/EPIC_<id>.kt files
 * use (its GameFixesRegistry looks Epic fixes up by catalog id). Shortcuts carry it as the
 * epicCatalogId extra; older shortcuts without it are matched through their appName
 * (APP_NAME_ALIASES).
 *
 * The games/values are factual data taken from GameNative's gamefixes/EPIC_*.kt; this is an
 * independent implementation built around Bannerlator's own launch path. Trover's values come from
 * GameNative #1953 by Mo Kakwan (Wavewash). Its container-wide changes (Box64, Wine, Turnip and DXVK
 * versions, dxvk.conf, 1280x720 and lower graphics quality, DLL overrides) are left out: they name
 * GameNative's own components and would overwrite the user's container settings.
 *
 * Catalog id -> game (appName), checked on egdata.app 2026-11-10:
 *   59a0c86d02da42e8ba6444cb171e61bf  The Elder Scrolls IV: Oblivion GOTY  (0d0c62755e5a45c7a07fd9cc0b61c39c)
 *   b1b4e0b67a044575820cb5e63028dcae  Fallout 3 GOTY                       (adeae8bbfc94427db57c7dfecce3f1d4)
 *   dabb52e328834da7bbe99691e374cb84  Fallout: New Vegas                   (5daeb974a22a435988892319b3a4f476)
 *   e345fdb9186645a48d30c3f85a8951dc  Kingdom Hearts III + Re Mind         (fd711544a06543e0ab1b0808de334120)
 *   864c7bc2c2394f7dbd1b534aa068ff56  Hogwarts Legacy                      (fa4240e57a3c46b39f169041b7811293)
 *   7f6bb22e14044be880ba254f683cd928  Trover Saves the Universe            (Sweetpea)
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private static final String OBLIVION = "59a0c86d02da42e8ba6444cb171e61bf";
    private static final String FALLOUT_3 = "b1b4e0b67a044575820cb5e63028dcae";
    private static final String FALLOUT_NV = "dabb52e328834da7bbe99691e374cb84";
    private static final String KINGDOM_HEARTS_3 = "e345fdb9186645a48d30c3f85a8951dc";
    private static final String HOGWARTS_LEGACY = "864c7bc2c2394f7dbd1b534aa068ff56";
    private static final String TROVER = "7f6bb22e14044be880ba254f683cd928";

    /** appName -> catalog id, for shortcuts made without the epicCatalogId extra. */
    private static final Map<String, String> APP_NAME_ALIASES = new HashMap<>();

    /** catalog id -> Bethesda registry key that needs an "Installed Path" value. */
    private static final Map<String, String> REGISTRY_INSTALLED_PATH = new HashMap<>();
    /** catalog id -> WINEDLLOVERRIDES entries to ensure present. */
    private static final Map<String, String> DLL_OVERRIDES = new HashMap<>();
    /** catalog id -> drive_c-relative folders to wipe before each launch. */
    private static final Map<String, List<String>> FOLDER_WIPES = new HashMap<>();
    /** catalog id -> environment values set only when the launch environment does not have the name. */
    private static final Map<String, Map<String, String>> ENV_DEFAULTS = new HashMap<>();
    /** catalog id -> folders under the game folder ("" = the game folder) for WINEPATH, when it is unset. */
    private static final Map<String, List<String>> WINEPATH_DIRS = new HashMap<>();
    /** catalog id -> arguments appended to the command when it does not carry them yet. */
    private static final Map<String, List<String>> LAUNCH_ARGS = new HashMap<>();
    /** catalog id -> lines to have in sections of drive_c-relative .ini files. */
    private static final Map<String, List<IniEdit>> INI_EDITS = new HashMap<>();

    /** Lines to have in one section of a drive_c-relative .ini file. */
    static final class IniEdit {
        final String file;
        final String section;
        final List<String> lines;

        IniEdit(String file, String section, String... lines) {
            this.file = file;
            this.section = section;
            this.lines = Arrays.asList(lines);
        }
    }

    static {
        APP_NAME_ALIASES.put("0d0c62755e5a45c7a07fd9cc0b61c39c", OBLIVION);
        APP_NAME_ALIASES.put("adeae8bbfc94427db57c7dfecce3f1d4", FALLOUT_3);
        APP_NAME_ALIASES.put("5daeb974a22a435988892319b3a4f476", FALLOUT_NV);
        APP_NAME_ALIASES.put("fd711544a06543e0ab1b0808de334120", KINGDOM_HEARTS_3);
        APP_NAME_ALIASES.put("fa4240e57a3c46b39f169041b7811293", HOGWARTS_LEGACY);
        APP_NAME_ALIASES.put("Sweetpea", TROVER);

        REGISTRY_INSTALLED_PATH.put(OBLIVION, "Software\\Wow6432Node\\Bethesda Softworks\\Oblivion");
        REGISTRY_INSTALLED_PATH.put(FALLOUT_3, "Software\\Wow6432Node\\Bethesda Softworks\\Fallout3");
        REGISTRY_INSTALLED_PATH.put(FALLOUT_NV, "Software\\Wow6432Node\\Bethesda Softworks\\FalloutNV");

        DLL_OVERRIDES.put(KINGDOM_HEARTS_3, "mf=n;mfmediaengine=n");

        FOLDER_WIPES.put(HOGWARTS_LEGACY, Collections.singletonList("ProgramData/Hogwarts Legacy"));

        Map<String, String> troverEnv = new LinkedHashMap<>();
        troverEnv.put("BOX64_AVX", "1");
        troverEnv.put("BOX64_MMAP32", "0");
        troverEnv.put("OPENSSL_ia32cap", "~0x200000200000000");
        ENV_DEFAULTS.put(TROVER, troverEnv);
        WINEPATH_DIRS.put(TROVER, Arrays.asList("Trover\\Binaries\\Win64", ""));
        LAUNCH_ARGS.put(TROVER, Arrays.asList("-nohmd", "-dx11"));
        String troverEngineIni = "users/xuser/AppData/Local/Trover/Saved/Config/WindowsNoEditor/Engine.ini";
        INI_EDITS.put(TROVER, Arrays.asList(
                new IniEdit(troverEngineIni, "/Script/EngineSettings.GameMapsSettings",
                        "ServerDefaultMap=/Game/Squanch/LobbyLevel/Lobby_master",
                        "GameDefaultMap=/Game/Squanch/LobbyLevel/Lobby_master"),
                new IniEdit(troverEngineIni, "Plugins",
                        "DisabledPlugins=\"SteamVR\"",
                        "DisabledPlugins=\"SteamVRInput\"",
                        "DisabledPlugins=\"OculusVR\"",
                        "DisabledPlugins=\"OculusAudio\"",
                        "DisabledPlugins=\"OnlineSubsystemOculus\"",
                        "DisabledPlugins=\"WmfMedia\""),
                new IniEdit(troverEngineIni, "/Script/MoviePlayer.MoviePlayerSettings",
                        "bWaitForMoviesToComplete=False",
                        "bMoviesAreSkippable=True",
                        "!StartupMovies=ClearArray"),
                new IniEdit(troverEngineIni, "SystemSettings",
                        "vr.HeadMountedDisplay.Supported=False",
                        "r.HMDEnable=0",
                        "vr.InstancedStereo=0",
                        "vr.MultiView=0",
                        "vr.SteamVR.EnableVRInput=0",
                        "r.HDR.EnableHDROutput=0",
                        "r.DefaultFeature.HDRUI=0",
                        "r.AllowHDR=0",
                        "r.SceneColorFormat=2"),
                new IniEdit(troverEngineIni, "/Script/Engine.Engine",
                        "bAllowStereoSettings=False")));
    }

    /** Whether [key] has any registered fix. */
    private static boolean isKnown(String key) {
        return REGISTRY_INSTALLED_PATH.containsKey(key) || DLL_OVERRIDES.containsKey(key)
                || FOLDER_WIPES.containsKey(key) || ENV_DEFAULTS.containsKey(key)
                || WINEPATH_DIRS.containsKey(key) || LAUNCH_ARGS.containsKey(key) || INI_EDITS.containsKey(key);
    }

    /**
     * The key a game's fixes are registered under: its catalog id when that has fixes, else the catalog id
     * its appName maps to, else the catalog id or appName as given ("" when both are empty).
     */
    static String fixKey(String catalogId, String appName) {
        String catalog = catalogId == null ? "" : catalogId.trim();
        String app = appName == null ? "" : appName.trim();
        if (!catalog.isEmpty() && isKnown(catalog)) return catalog;
        String alias = APP_NAME_ALIASES.get(app);
        if (alias != null) return alias;
        return !catalog.isEmpty() ? catalog : app;
    }

    /** The fix key of {@code shortcut}, or "" when it isn't an Epic shortcut. */
    private static String fixKey(Shortcut shortcut) {
        if (shortcut == null) return "";
        if (!"epic".equals(shortcut.getExtra("storeSource"))) return "";
        return fixKey(shortcut.getExtra("epicCatalogId", ""), shortcut.getExtra("epicAppName", ""));
    }

    /**
     * Filesystem-side fixes applied before launch: the Bethesda registry "Installed Path" write, the
     * folder cache-wipe and the config-file lines. Runs on the background launch setup thread
     * (system.reg is already generated and a folder wipe may be large), so blocking I/O here is
     * ANR-safe. No-op for games without a registered fix.
     */
    public static void applyPreLaunch(Context ctx, Shortcut shortcut) {
        String key = fixKey(shortcut);
        if (ctx == null || key.isEmpty() || !isKnown(key)) return;
        try {
            applyRegistryInstalledPath(ctx, shortcut, key);
            applyFolderWipes(ctx, key);
            applyIniEdits(ctx, key);
        } catch (Throwable t) {
            Log.w(TAG, "applyPreLaunch failed for " + key, t);
        }
    }

    /**
     * Env-side fixes applied while the launch environment is being assembled: required
     * WINEDLLOVERRIDES entries are merged in (a user's entry for the same DLL wins), and default
     * variables and WINEPATH are set only when the environment does not have them yet. No-op for
     * games without a registered fix.
     */
    public static void applyEnv(Context ctx, Shortcut shortcut, EnvVars envVars) {
        String key = fixKey(shortcut);
        if (envVars == null || key.isEmpty() || !isKnown(key)) return;
        try {
            String required = DLL_OVERRIDES.get(key);
            if (required != null) {
                String merged = mergeDllOverrides(envVars.has("WINEDLLOVERRIDES") ? envVars.get("WINEDLLOVERRIDES") : null,
                        required);
                envVars.put("WINEDLLOVERRIDES", merged);
                Log.i(TAG, "applied WINEDLLOVERRIDES for " + key + ": " + merged);
            }
            Map<String, String> defaults = ENV_DEFAULTS.get(key);
            if (defaults != null) {
                for (Map.Entry<String, String> e : defaults.entrySet()) {
                    if (envVars.has(e.getKey())) continue;
                    envVars.put(e.getKey(), e.getValue());
                    Log.i(TAG, "env for " + key + ": " + e.getKey() + "=" + e.getValue());
                }
            }
            List<String> dirs = WINEPATH_DIRS.get(key);
            if (dirs != null && !envVars.has("WINEPATH")) {
                String winePath = winePath(windowsInstallDir(shortcut), dirs);
                if (!winePath.isEmpty()) {
                    envVars.put("WINEPATH", winePath);
                    Log.i(TAG, "env for " + key + ": WINEPATH=" + winePath);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyEnv failed for " + key, t);
        }
    }

    /**
     * The registered launch arguments of {@code shortcut}'s game that {@code command} does not carry yet,
     * space-joined ("" when none).
     */
    public static String launchArgs(Shortcut shortcut, String command) {
        try {
            List<String> args = LAUNCH_ARGS.get(fixKey(shortcut));
            if (args == null) return "";
            String have = " " + (command == null ? "" : command).toLowerCase(Locale.ROOT) + " ";
            StringBuilder sb = new StringBuilder();
            for (String arg : args) {
                if (have.contains(" " + arg.toLowerCase(Locale.ROOT) + " ")) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(arg);
            }
            if (sb.length() > 0) Log.i(TAG, "launch args for " + fixKey(shortcut) + ": " + sb);
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "launchArgs failed", t);
            return "";
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

    /**
     * WINEPATH for a game whose exe sits in {@code exeDir}: each of {@code dirs} under the game folder
     * ("" = the game folder itself). The game folder is {@code exeDir}, or its parent when the exe sits
     * in one of {@code dirs}.
     */
    static String winePath(String exeDir, List<String> dirs) {
        if (exeDir == null || exeDir.isEmpty()) return "";
        String root = exeDir;
        for (String d : dirs) {
            if (d.isEmpty()) continue;
            String tail = "\\" + d;
            if (root.toLowerCase(Locale.ROOT).endsWith(tail.toLowerCase(Locale.ROOT))) {
                root = root.substring(0, root.length() - tail.length());
                break;
            }
        }
        List<String> parts = new ArrayList<>();
        for (String d : dirs) parts.add(d.isEmpty() ? root : root + "\\" + d);
        return String.join(";", parts);
    }

    private static void applyRegistryInstalledPath(Context ctx, Shortcut shortcut, String key) {
        String regKey = REGISTRY_INSTALLED_PATH.get(key);
        if (regKey == null) return;

        String winInstallDir = windowsInstallDir(shortcut);
        if (winInstallDir.isEmpty()) {
            Log.w(TAG, "registry fix: could not derive install dir for " + key + "; skipping");
            return;
        }

        File systemReg = new File(ImageFs.find(ctx).wineprefix, "system.reg");
        if (!systemReg.exists()) {
            Log.w(TAG, "registry fix: system.reg not found at " + systemReg.getAbsolutePath());
            return;
        }
        try (WineRegistryEditor editor = new WineRegistryEditor(systemReg)) {
            editor.setCreateKeyIfNotExist(true);
            String existing = editor.getStringValue(regKey, BETHESDA_INSTALLED_PATH, null);
            if (existing == null || existing.isEmpty()) {
                editor.setStringValue(regKey, BETHESDA_INSTALLED_PATH, winInstallDir);
                Log.i(TAG, "registry fix: set [" + regKey + "] \"" + BETHESDA_INSTALLED_PATH + "\"="
                        + winInstallDir + " for " + key);
            }
        }
    }

    private static void applyFolderWipes(Context ctx, String key) {
        List<String> folders = FOLDER_WIPES.get(key);
        if (folders == null) return;
        File driveC = new File(ImageFs.find(ctx).wineprefix, "drive_c");
        for (String rel : folders) {
            File target = new File(driveC, rel);
            if (!target.exists()) continue;
            if (FileUtils.delete(target)) {
                Log.i(TAG, "folder-wipe: deleted " + target.getAbsolutePath() + " for " + key);
            } else {
                Log.w(TAG, "folder-wipe: partial/failed delete of " + target.getAbsolutePath() + " for " + key);
            }
        }
    }

    private static void applyIniEdits(Context ctx, String key) {
        List<IniEdit> edits = INI_EDITS.get(key);
        if (edits == null) return;
        File driveC = new File(ImageFs.find(ctx).wineprefix, "drive_c");
        Map<String, List<IniEdit>> byFile = new LinkedHashMap<>();
        for (IniEdit e : edits) {
            List<IniEdit> list = byFile.get(e.file);
            if (list == null) {
                list = new ArrayList<>();
                byFile.put(e.file, list);
            }
            list.add(e);
        }
        for (Map.Entry<String, List<IniEdit>> f : byFile.entrySet()) {
            File ini = new File(driveC, f.getKey());
            try {
                // Unreal writes its .ini files as plain text, or UTF-16 with a byte-order mark; the file is
                // written back in the encoding and byte order it was read in.
                Charset charset = StandardCharsets.UTF_8;
                byte[] bom = new byte[0];
                String text = "";
                if (ini.isFile()) {
                    byte[] bytes = Files.readAllBytes(ini.toPath());
                    if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
                        charset = StandardCharsets.UTF_16LE;
                        bom = new byte[]{(byte) 0xFF, (byte) 0xFE};
                    } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
                        charset = StandardCharsets.UTF_16BE;
                        bom = new byte[]{(byte) 0xFE, (byte) 0xFF};
                    }
                    text = new String(bytes, bom.length, bytes.length - bom.length, charset);
                }
                String merged = mergeIni(text, f.getValue());
                if (merged.equals(text)) continue;
                File dir = ini.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                    Log.w(TAG, "ini fix: could not create " + dir.getAbsolutePath());
                    continue;
                }
                if (ini.exists() && !ini.canWrite()) ini.setWritable(true);
                byte[] out = merged.getBytes(charset);
                byte[] all = new byte[bom.length + out.length];
                System.arraycopy(bom, 0, all, 0, bom.length);
                System.arraycopy(out, 0, all, bom.length, out.length);
                Files.write(ini.toPath(), all);
                Log.i(TAG, "ini fix: updated " + ini.getAbsolutePath() + " for " + key);
            } catch (Throwable t) {
                Log.w(TAG, "ini fix: " + ini.getAbsolutePath() + " for " + key, t);
            }
        }
    }

    /**
     * {@code text} (an .ini file, possibly empty) with every line of {@code edits} present in its section.
     * A line whose key (the part before '=') is unique within its edit replaces the section's line with that
     * key; a key listed more than once in an edit, or starting with an Unreal array marker (! + - .), is
     * matched as the whole line and added when missing. Missing sections are appended. Everything else is
     * kept as it was, including the line endings.
     */
    static String mergeIni(String text, List<IniEdit> edits) {
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        String body = text;
        String bom = "";
        if (body.startsWith("\uFEFF")) {
            bom = "\uFEFF";
            body = body.substring(1);
        }
        List<String> lines = new ArrayList<>(Arrays.asList(body.split("\r?\n", -1)));
        if (lines.size() == 1 && lines.get(0).isEmpty()) lines.clear();
        boolean endsWithNewline = body.isEmpty() || body.endsWith("\n");
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty() && endsWithNewline) lines.remove(lines.size() - 1);

        for (IniEdit edit : edits) {
            Map<String, Integer> keyCount = new HashMap<>();
            for (String l : edit.lines) {
                String k = iniKey(l);
                keyCount.put(k, keyCount.containsKey(k) ? keyCount.get(k) + 1 : 1);
            }
            int start = -1;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("[") && t.endsWith("]") && t.substring(1, t.length() - 1).trim().equalsIgnoreCase(edit.section)) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) lines.add("");
                lines.add("[" + edit.section + "]");
                lines.addAll(edit.lines);
                continue;
            }
            for (String want : edit.lines) {
                int end = start + 1;
                while (end < lines.size() && !isSectionHeader(lines.get(end))) end++;
                int insertAt = end;
                while (insertAt > start + 1 && lines.get(insertAt - 1).trim().isEmpty()) insertAt--;
                String k = iniKey(want);
                boolean whole = keyCount.get(k) > 1 || k.startsWith("!") || k.startsWith("+")
                        || k.startsWith("-") || k.startsWith(".");
                boolean done = false;
                for (int i = start + 1; i < end; i++) {
                    String have = lines.get(i).trim();
                    if (whole) {
                        if (have.equals(want)) {
                            done = true;
                            break;
                        }
                    } else if (iniKey(have).equalsIgnoreCase(k)) {
                        if (!have.equals(want)) lines.set(i, want);
                        done = true;
                        break;
                    }
                }
                if (!done) lines.add(insertAt, want);
            }
        }
        if (lines.isEmpty()) return text;
        return bom + String.join(nl, lines) + nl;
    }

    private static boolean isSectionHeader(String line) {
        String t = line.trim();
        return t.startsWith("[") && t.endsWith("]");
    }

    private static String iniKey(String line) {
        int eq = line.indexOf('=');
        return (eq >= 0 ? line.substring(0, eq) : line).trim();
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
        String key = fixKey(shortcut);
        return !key.isEmpty() && isKnown(key);
    }
}
