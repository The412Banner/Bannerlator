package com.winlator.star.store;

import android.content.Context;
import android.util.Log;

import com.winlator.star.container.Shortcut;
import com.winlator.star.core.FileUtils;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RealSteamLauncher — the launch-orchestration layer for {@code launchMode=RealSteam}
 * (feature M3). Stages the download-on-demand SteamLite client + our clean-room agent
 * ({@code steam.exe}) into the launching container's Wine prefix, registers the game under
 * {@code steamapps\common\<CanonicalName>} so genuine Steam sees it INSTALLED (secure
 * {@code LaunchApp}, not an insecure CreateProcess), writes the per-game spec file the agent
 * reads, and produces the env + launch target for {@link com.winlator.star.XServerDisplayActivity}.
 *
 * <p>This is a pure port of the PROVEN on-device recipe (agent-src/test-scripts/{m0,m2,m2b}_setup.py,
 * REPRODUCE.md — user played real VAC online MP on L4D2 550 with it). The setup scripts ran as root
 * (python via the device bridge) and therefore chowned/restorecon'd everything back to the app UID;
 * here the APP ITSELF writes the files inside its own {@code filesDir}, so they already carry the app's
 * uid + SELinux label — no chown/restorecon is needed or possible.
 *
 * <p><b>Failure domain:</b> this addresses <i>emulation launch</i> only (making a genuine-Steam game
 * RUN under real Steam / VAC). It is unrelated to depot <i>download</i> reliability and to Goldberg
 * <i>offline emulation</i>; those are separate paths. Any missing prerequisite (token, appId, install
 * dir, or the SteamLite package not yet downloaded) makes {@link #prepare} return {@code null} so the
 * caller falls through to the normal (non-RealSteam) launch.
 *
 * <p><b>Token hygiene:</b> the Steam refresh token is a registered secret
 * ({@link SteamRepository#initialize} → SteamLogRedactor). It is read here and placed ONLY into the
 * returned {@link Plan#env} map — never logged, never written to any file. Diagnostic logs in this
 * class print at most the token LENGTH.
 */
public final class RealSteamLauncher {

    private static final String TAG = "BH_REALSTEAM";

    /** Guest-visible Steam install dir (where our agent lands as {@code steam.exe}). */
    public static final String STEAM_DIR_WIN = "C:\\Program Files (x86)\\Steam";
    /** Our clean-room agent, staged as the Steam client's {@code steam.exe} (also the SteamLite marker). */
    public static final String STEAM_EXE_NAME = "steam.exe";
    /** Name of the CommonFilesSteam sub-package inside the SteamLite install dir. */
    private static final String COMMON_FILES_SUBDIR = "CommonFilesSteam";

    /**
     * The result of a successful {@link #prepare}: the env to merge into the launch environment and the
     * pieces {@code getWineStartCommand()} needs to rewrite the launch target to run our agent as
     * {@code steam.exe} with the per-game spec as its argument. Immutable.
     */
    public static final class Plan {
        /** Env to merge into the launch {@code envVars} (contains the registered-secret token). */
        public final Map<String, String> env;
        /** Guest dir the agent runs from, e.g. {@code C:\Program Files (x86)\Steam} (UN-escaped). */
        public final String steamExeDirWin;
        /** The agent's guest filename, {@code steam.exe}. */
        public final String steamExeName;
        /** The spec-file argument passed to the agent, e.g. {@code C:\550.spec} (UN-escaped). */
        public final String specArgWin;
        /** Resolved steamapps\common folder name the game was symlinked under (for diagnostics). */
        public final String canonicalName;
        /** The resolved Steam appId (for diagnostics). */
        public final int appId;

        Plan(Map<String, String> env, String steamExeDirWin, String steamExeName,
             String specArgWin, String canonicalName, int appId) {
            this.env = env;
            this.steamExeDirWin = steamExeDirWin;
            this.steamExeName = steamExeName;
            this.specArgWin = specArgWin;
            this.canonicalName = canonicalName;
            this.appId = appId;
        }
    }

    private RealSteamLauncher() {}

    /**
     * Stage everything and build the launch plan. MUST be called off the main thread (it touches the
     * filesystem and the Steam prefs). Returns {@code null} — caller falls back to a normal launch — when
     * any prerequisite is missing (no token / no appId / install dir absent / SteamLite not downloaded)
     * or staging fails. Never throws; never logs the token.
     *
     * @param ctx                 app/activity context
     * @param driveC              host dir that the guest sees as {@code C:\} — the launching container's
     *                            {@code <container.rootDir>/.wine/drive_c} (equivalently the xuser-symlinked
     *                            {@code WINEPREFIX/drive_c}); derived by the caller, NOT hardcoded
     * @param steamLiteInstallDir {@link SteamLiteComponent#installDir(Context)}
     * @param shortcut            the game shortcut being launched (its {@code path} is the guest exe path)
     * @param appId               resolved Steam appId (from {@code resolveSteamAppRef})
     * @param displayName         the game's library display name (basis for the canonical folder name)
     * @param hostInstallDir      the game's real install dir on the host FS (from {@code SteamAppRef.installDir})
     */
    public static Plan prepare(Context ctx,
                               File driveC,
                               File steamLiteInstallDir,
                               Shortcut shortcut,
                               int appId,
                               String displayName,
                               String hostInstallDir) {
        try {
            // ── 0. Validate prerequisites ────────────────────────────────────────────────────────
            if (driveC == null) { Log.w(TAG, "prepare: null driveC — fallback"); return null; }
            if (shortcut == null) { Log.w(TAG, "prepare: null shortcut — fallback"); return null; }
            if (appId <= 0) { Log.w(TAG, "prepare: no appId — fallback"); return null; }
            if (steamLiteInstallDir == null
                    || !new File(steamLiteInstallDir, STEAM_EXE_NAME).isFile()) {
                Log.w(TAG, "prepare: SteamLite package not installed (no agent steam.exe) — fallback");
                return null;
            }
            if (hostInstallDir == null || hostInstallDir.isEmpty()
                    || !new File(hostInstallDir).isDirectory()) {
                Log.w(TAG, "prepare: game install dir missing on host ("
                        + hostInstallDir + ") — fallback");
                return null;
            }

            // ── 1. Steam identity + token (registered secret — never logged/written) ─────────────
            SteamRepository repo = SteamRepository.getInstance();
            String token = repo.getRefreshToken();
            String username = repo.getUsername();
            long steamId64 = repo.getSteamId64();
            if (token == null || token.isEmpty()) {
                Log.w(TAG, "prepare: no Steam refresh token (not logged in?) — fallback");
                return null;
            }
            // Presence-only breadcrumb — LENGTH only, never the value.
            Log.i(TAG, "prepare: identity ok (tokenLen=" + token.length()
                    + " userLen=" + (username != null ? username.length() : 0)
                    + " steamId=" + steamId64 + " appId=" + appId + ")");

            // ── 2. Canonical steamapps\common folder name ────────────────────────────────────────
            // The store installs the game to steam_games/<safeName> where illegal chars are replaced
            // with '_' (SteamDepotDownloader), so the real folder can be odd (e.g. CS:S →
            // "Counter-Strike_ Source"). We symlink it under a CLEANED name (illegal chars removed,
            // whitespace collapsed) which yields Valve's canonical installdir for the M4 test games
            // (L4D2 "Left 4 Dead 2", TF2 "Team Fortress 2", CS:S "Counter-Strike Source").
            String canonicalName = sanitizeFolderName(displayName);
            if (canonicalName.isEmpty()) canonicalName = new File(hostInstallDir).getName();
            if (canonicalName.isEmpty()) canonicalName = "App_" + appId;

            // ── 3. Host prefix layout ────────────────────────────────────────────────────────────
            File steamDir = new File(driveC, "Program Files (x86)/Steam");
            File commonFilesSteamDir = new File(driveC, "Program Files (x86)/Common Files/Steam");
            File steamappsDir = new File(steamDir, "steamapps");
            File commonDir = new File(steamappsDir, "common");
            File link = new File(commonDir, canonicalName);
            File acf = new File(steamappsDir, "appmanifest_" + appId + ".acf");
            File spec = new File(driveC, appId + ".spec");

            // ── 3a. Stage SteamLite client + agent into the prefix ───────────────────────────────
            if (!stageSteamLite(steamLiteInstallDir, steamDir, commonFilesSteamDir)) {
                Log.w(TAG, "prepare: SteamLite staging failed — fallback");
                return null;
            }

            // ── 3b. Register the game so LaunchApp is SECURE: symlink under the canonical name
            //        + write appmanifest_<appid>.acf (StateFlags=4 = installed). The real game dir may
            //        have an odd name, so the symlink NAME is the canonical name, TARGET the real dir.
            if (!commonDir.exists() && !commonDir.mkdirs()) {
                Log.w(TAG, "prepare: could not create steamapps/common — fallback");
                return null;
            }
            if (FileUtils.isSymlink(link)) {
                // Re-stage: drop the previous symlink so we can repoint it (target may have moved).
                FileUtils.delete(link);
            } else if (link.exists()) {
                // A real dir where our symlink should be — leave it (don't clobber user data) and log.
                Log.w(TAG, "prepare: steamapps/common/" + canonicalName
                        + " exists as a real directory; leaving as-is");
            }
            if (!FileUtils.isSymlink(link) && !link.exists()) {
                FileUtils.symlink(hostInstallDir, link.getAbsolutePath());
                if (!FileUtils.isSymlink(link)) {
                    Log.w(TAG, "prepare: failed to create steamapps/common symlink — fallback");
                    return null;
                }
            }
            writeAppManifest(acf, appId, displayName, canonicalName, steamId64);

            // ── 3c. Per-game spec: line1 = full Windows exe path UNDER steamapps\common (so the agent's
            //        stage_app_manifest sees the \steamapps\common\ marker), line2 = appId. ──────────
            String relExe = exeRelativeToInstall(shortcut.path);      // e.g. "left4dead2.exe" or "bin\\x64\\g.exe"
            String specExeWin = STEAM_DIR_WIN + "\\steamapps\\common\\" + canonicalName + "\\" + relExe;
            if (!FileUtils.writeString(spec, specExeWin + "\n" + appId + "\n")) {
                Log.w(TAG, "prepare: failed to write spec file — fallback");
                return null;
            }

            // ── 4. Env (contract: agent-src/main.cpp:168,896-907). Token goes ONLY here. ─────────
            Map<String, String> env = new LinkedHashMap<>();
            env.put("PROTON_DISABLE_LSTEAMCLIENT", "1");
            env.put("WN_STEAM_TOKEN", token);
            env.put("WN_STEAM_USERNAME", username != null ? username : "");
            env.put("WN_STEAM_STEAMID", String.valueOf(steamId64));
            env.put("WN_STEAM_APPID", String.valueOf(appId));
            // Absolute, drive-qualified path so the agent's fopen resolves it regardless of cwd.
            env.put("WN_STEAM_GAMEEXE_FILE", "C:\\" + appId + ".spec");

            String specArgWin = "C:\\" + appId + ".spec";
            Log.i(TAG, "prepare: staged appId=" + appId + " canonical=\"" + canonicalName
                    + "\" relExe=\"" + relExe + "\" (LaunchApp SECURE)");
            return new Plan(env, STEAM_DIR_WIN, STEAM_EXE_NAME, specArgWin, canonicalName, appId);
        } catch (Throwable t) {
            Log.w(TAG, "prepare: errored — fallback", t);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Copy the SteamLite package into the prefix: the top-level files (our {@code steam.exe} agent + the
     * matched genuine Valve client DLLs) → {@code Program Files (x86)\Steam\}, and the
     * {@code CommonFilesSteam\} sub-package → {@code Program Files (x86)\Common Files\Steam\}. Overwrites
     * the client files (a re-stage picks up a catalog update) but does NOT wipe the Steam dir, so an
     * existing {@code steamapps\} (our symlinks + ACF) and {@code config\} survive.
     */
    private static boolean stageSteamLite(File steamLiteInstallDir, File steamDir, File commonFilesSteamDir) {
        if (!steamDir.exists() && !steamDir.mkdirs()) return false;
        if (!commonFilesSteamDir.exists() && !commonFilesSteamDir.mkdirs()) return false;
        File[] children = steamLiteInstallDir.listFiles();
        if (children == null) return false;
        boolean ok = true;
        boolean sawAgent = false;
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory() && COMMON_FILES_SUBDIR.equals(name)) {
                File[] cf = child.listFiles();
                if (cf != null) {
                    for (File f : cf) {
                        if (!FileUtils.copy(f, new File(commonFilesSteamDir, f.getName()))) ok = false;
                    }
                }
            } else if (child.isFile()) {
                if (STEAM_EXE_NAME.equals(name)) sawAgent = true;
                if (!FileUtils.copy(child, new File(steamDir, name))) ok = false;
            } else if (child.isDirectory()) {
                // Any other bundled dir (e.g. config) copies straight into the Steam dir.
                if (!FileUtils.copy(child, new File(steamDir, name))) ok = false;
            }
        }
        if (!sawAgent && !new File(steamDir, STEAM_EXE_NAME).isFile()) {
            Log.w(TAG, "stageSteamLite: agent steam.exe not present after staging");
            return false;
        }
        return ok;
    }

    /**
     * Write a minimal but VALID appmanifest so genuine Steam reports the app INSTALLED (StateFlags=4)
     * even before the agent's own richer {@code stage_app_manifest} overwrites it at startup. Field
     * shapes + VDF escaping mirror agent-src/main.cpp:252-420 so the two never disagree.
     */
    private static void writeAppManifest(File acf, int appId, String displayName,
                                         String canonicalName, long steamId64) {
        String name = (displayName != null && !displayName.isEmpty()) ? displayName : canonicalName;
        String nameEsc = vdfEscape(name);
        String installdirEsc = vdfEscape(canonicalName);
        String owner = steamId64 > 0 ? String.valueOf(steamId64) : "0";
        String acfContent =
                "\"AppState\"\n"
                        + "{\n"
                        + "\t\"appid\"\t\t\"" + appId + "\"\n"
                        + "\t\"universe\"\t\t\"1\"\n"
                        + "\t\"LauncherPath\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe\"\n"
                        + "\t\"name\"\t\t\"" + nameEsc + "\"\n"
                        + "\t\"StateFlags\"\t\t\"4\"\n"
                        + "\t\"installdir\"\t\t\"" + installdirEsc + "\"\n"
                        + "\t\"LastOwner\"\t\t\"" + owner + "\"\n"
                        + "\t\"InstalledDepots\"\n\t{\n\t}\n"
                        + "\t\"UserConfig\"\n\t{\n\t\t\"language\"\t\t\"english\"\n\t}\n"
                        + "\t\"MountedConfig\"\n\t{\n\t\t\"language\"\t\t\"english\"\n\t}\n"
                        + "}\n";
        FileUtils.writeString(acf, acfContent);
    }

    /**
     * The game exe path relative to its install dir, in Windows form, derived from the shortcut's guest
     * exe path (e.g. {@code Z:\steam_games\<folder>\left4dead2.exe} → {@code left4dead2.exe}, and a nested
     * {@code ...\<folder>\bin\x64\game.exe} → {@code bin\x64\game.exe}). Falls back to the exe basename
     * when the path carries no {@code steam_games/} segment (e.g. a Copy-to-C game). Never null.
     */
    static String exeRelativeToInstall(String guestExePath) {
        if (guestExePath == null || guestExePath.isEmpty()) return "";
        String norm = guestExePath.replace('\\', '/');
        int idx = norm.toLowerCase().indexOf("steam_games/");
        String rel;
        if (idx >= 0) {
            String after = norm.substring(idx + "steam_games/".length()); // "<folder>/rel/exe"
            int slash = after.indexOf('/');
            rel = (slash >= 0) ? after.substring(slash + 1) : after;       // strip the "<folder>/"
        } else {
            int slash = norm.lastIndexOf('/');
            rel = (slash >= 0) ? norm.substring(slash + 1) : norm;         // basename fallback
        }
        rel = rel.trim();
        if (rel.isEmpty()) {
            int slash = norm.lastIndexOf('/');
            rel = (slash >= 0) ? norm.substring(slash + 1) : norm;
        }
        return rel.replace('/', '\\');
    }

    /** Strip Windows-illegal folder chars, collapse whitespace, trim. Removes (not underscores) so a
     *  display name like "Counter-Strike: Source" yields the canonical "Counter-Strike Source". */
    static String sanitizeFolderName(String name) {
        if (name == null) return "";
        String cleaned = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        // Trailing dots/spaces are illegal at the end of a Windows path component.
        while (cleaned.endsWith(".") || cleaned.endsWith(" "))
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    /** Escape a value for a VDF/ACF quoted field — mirrors agent-src/main.cpp vdf_escape(). */
    private static String vdfEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                default:   out.append(c);      break;
            }
        }
        return out.toString();
    }
}
