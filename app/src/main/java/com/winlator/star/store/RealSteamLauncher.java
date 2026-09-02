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
     * @param controllerPassthrough  per-game "Controller passthrough" toggle: when {@code true}, make the
     *                            genuine client get out of the pad's way for classic DInput games (levers
     *                            1 &amp; 2 below — Steam Input off in localconfig + overlay-renderer DLLs
     *                            removed). Lever 3 (force DInput on the launch) is applied by the caller
     *                            ({@link com.winlator.star.XServerDisplayActivity}). Fully gated: when
     *                            {@code false} the RealSteam launch is unchanged.
     */
    public static Plan prepare(Context ctx,
                               File driveC,
                               File steamLiteInstallDir,
                               Shortcut shortcut,
                               int appId,
                               String displayName,
                               String hostInstallDir,
                               boolean controllerPassthrough) {
        return prepare(ctx, driveC, steamLiteInstallDir, shortcut, appId, displayName, hostInstallDir,
                controllerPassthrough, 0);
    }

    /**
     * As {@link #prepare(Context, File, File, Shortcut, int, String, String, boolean)}, plus the live
     * agent channel: when {@code agentPort > 0} the plan env carries {@code BL_AGENT_PORT} and the
     * agent streams its login/launch/game events to {@code 127.0.0.1:<port>} (see
     * {@link SteamAgentChannel}). {@code 0} = no channel; an agent without the feature ignores the var.
     * The existing {@code WN_*} keys are unchanged.
     */
    public static Plan prepare(Context ctx,
                               File driveC,
                               File steamLiteInstallDir,
                               Shortcut shortcut,
                               int appId,
                               String displayName,
                               String hostInstallDir,
                               boolean controllerPassthrough,
                               int agentPort) {
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

            // ── 3a-bis. Controller passthrough (per-game toggle; device-proven fix for classic
            //    DInput games — HL2 / CS:S — that read the pad directly and get nothing while the
            //    genuine client holds it as Steam Input). Make the client step aside: (1) disable
            //    Steam Input's controller grabbing in this account's localconfig.vdf, and (2) drop
            //    the overlay-renderer DLLs whose FEX HID hook breaks DirectInput. Lever 3 (force
            //    DInput on the launch) is applied by the caller. Best-effort: any failure here is
            //    logged and swallowed so passthrough can never block a launch. Runs AFTER staging so
            //    a re-staged overlay DLL is removed again. No-op (byte-unchanged) when the toggle is off.
            if (controllerPassthrough) {
                applyControllerPassthrough(steamDir, repo);
            }

            // ── 3a-ter. Steam connection region for the genuine client (Settings → Steam). Writes a
            //    GameHub-format cmlist.json ({"datacenter","cm_list":[{"endpoint"}]}) for the chosen /
            //    remembered datacenter into the prefix Steam dir; the agent seeds the client's CM
            //    cache from it before LogOn (WN_STEAM_CMLIST) and reports the region over the agent
            //    socket (BL_STEAM_REGION). No preference → no file, the client discovers CMs itself.
            //    Best-effort: a failure here never blocks the launch.
            File cmList = new File(steamDir, "config/cmlist.json");
            boolean cmListWritten = false;
            try { cmListWritten = SteamRegion.INSTANCE.writeCmListJson(ctx, cmList); }
            catch (Throwable t) { Log.w(TAG, "prepare: cmlist.json skipped: " + t.getMessage()); }
            String regionDesc = SteamRegion.INSTANCE.describe(ctx);

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
            // The agent watches the process Steam's LaunchApp actually SPAWNS — the game's LAUNCHER exe
            // (<game>.exe), which internally hands off to the arch-specific <game>_win64.exe. If the
            // shortcut named the 64-bit exe, the agent would watch a process Steam never starts, miss
            // the secure launch, and fall back INSECURE (device-proven with CS:S: cstrike_win64.exe →
            // insecure, cstrike.exe → VAC-secure). So prefer the base launcher when it exists on disk.
            relExe = preferLauncherExe(relExe, hostInstallDir);
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
            // Cap the pre-launch app-info wait. The agent blocks up to this long for the CM's
            // AppInfoUpdateComplete before launching; for a game whose access tokens the CM denies
            // (strict live-service titles that aren't part of the VAC path) that update never lands,
            // so the default 30s was spent as a black screen every launch. 4s keeps the wait a
            // no-op for owned games (their app-info lands in ~1-2s) while capping the doomed case.
            // Note: WN_STEAM_SKIP_APPINFO=1 (supported by the agent) would drop it to zero, but is
            // left off by default so the proven VAC launch path (which relies on the refresh) is
            // unchanged until it's re-verified online with the skip on.
            env.put("WN_STEAM_APPINFO_WAIT_MS", "4000");
            // Live agent↔app channel (additive; the agent is fully functional without it).
            if (agentPort > 0) env.put("BL_AGENT_PORT", String.valueOf(agentPort));
            // Agent p3 friends/chat relay over that channel — only when the user opted into
            // friends/chat (no social footprint otherwise); the agent ignores it without a channel.
            boolean social = false;
            try { social = agentPort > 0 && SteamPrefs.INSTANCE.isSocialEnabled(ctx); } catch (Throwable ignored) {}
            env.put("BL_AGENT_FRIENDS", social ? "1" : "0");
            // Region seed for the genuine client (additive; an agent without the feature ignores both).
            env.put("BL_STEAM_REGION", regionDesc);
            if (cmListWritten) env.put("WN_STEAM_CMLIST", STEAM_DIR_WIN + "\\config\\cmlist.json");
            // Secure-launch policy (agent p3b): WN_STEAM_VAC=1 → the agent keeps its full ~60 s
            // Steam-owned (VAC-secure) window before any direct start; 0 → the title never needed a
            // secure launch, so an accepted-but-never-spawned LaunchApp falls back after ~15 s
            // instead of a minute of black screen. Per-shortcut override "steamVacLaunch" ("1"/"0",
            // the launch popup's "Requires secure (VAC) launch" toggle) wins; otherwise the PICS
            // marker recorded by the library sync (SteamDatabase.vac_secure: category_8 "Valve
            // Anti-Cheat enabled" or extended/vac*). An older agent ignores the variable.
            String vacOverride = shortcut.getExtra("steamVacLaunch", "").trim();
            boolean vacSecure;
            String vacSource;
            if ("1".equals(vacOverride) || "0".equals(vacOverride)) {
                vacSecure = "1".equals(vacOverride);
                vacSource = "shortcut override";
            } else {
                boolean detected = false;
                try { detected = repo.getDatabase().isVacSecure(appId); } catch (Throwable ignored) {}
                vacSecure = detected;
                vacSource = "app-info";
            }
            env.put("WN_STEAM_VAC", vacSecure ? "1" : "0");

            String specArgWin = "C:\\" + appId + ".spec";
            Log.i(TAG, "prepare: staged appId=" + appId + " canonical=\"" + canonicalName
                    + "\" relExe=\"" + relExe + "\" (LaunchApp SECURE, vac=" + (vacSecure ? 1 : 0)
                    + " from " + vacSource + ")");
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

    // ── Controller passthrough (per-game toggle) ──────────────────────────────────────────────────

    /** Steam Input controller-support keys we set to "0" in localconfig.vdf so the client stops
     *  reserving XInput slots and hands the pad to the game (device-proven for HL2 / CS:S). */
    private static final String[] STEAM_INPUT_KEYS = {
            "SteamController_XBoxSupport",
            "SteamController_GenericGamepadSupport",
            "SteamController_PSSupport",
            "SteamController_SwitchSupport",
    };

    /** Overlay renderer DLLs whose FEX HID hook breaks DirectInput — removed from the staged prefix Steam
     *  dir for a passthrough launch. The base package doesn't ship them, so normally there's nothing to do. */
    private static final String[] OVERLAY_DLLS = {
            "GameOverlayRenderer.dll",
            "GameOverlayRenderer64.dll",
    };

    /**
     * Apply passthrough levers 1 &amp; 2 on the staged prefix Steam dir. Wholly best-effort: every failure is
     * logged and swallowed — passthrough is a convenience that must never block a launch. Runs on the launch
     * worker thread (via {@link #prepare}); the blocking VDF write is therefore off the main thread.
     */
    private static void applyControllerPassthrough(File steamDir, SteamRepository repo) {
        try {
            // Lever 2 — remove the overlay renderer DLLs (only present if a stale prefix / package left them).
            for (String dll : OVERLAY_DLLS) {
                File f = new File(steamDir, dll);
                if (f.isFile() && f.delete()) Log.i(TAG, "passthrough: removed " + dll);
            }
            // Lever 1 — stop Steam Input reserving controller slots (per-account localconfig.vdf).
            File localConfig = resolveLocalConfig(steamDir, repo);
            if (localConfig != null) disableSteamControllerSupport(localConfig);
            else Log.w(TAG, "passthrough: no localconfig.vdf resolvable — Steam Input keys skipped");
        } catch (Throwable t) {
            Log.w(TAG, "passthrough: apply failed (non-fatal): " + t.getMessage());
        }
    }

    /**
     * The per-account localconfig.vdf under the staged prefix:
     * {@code Program Files (x86)\Steam\\userdata\<accountid>\config\localconfig.vdf}. The 32-bit account id
     * (SteamID3) comes from {@link SteamRepository#getAccountId()}; when it can't be resolved (0), fall back
     * to the first existing {@code userdata/<id>/config/localconfig.vdf} on disk. Returns the account-id path
     * even when the file doesn't exist yet ({@link #disableSteamControllerSupport} creates it), or {@code null}
     * only when the id is unknown and no existing file can be found.
     */
    private static File resolveLocalConfig(File steamDir, SteamRepository repo) {
        File userdata = new File(steamDir, "userdata");
        int accountId = (repo != null) ? repo.getAccountId() : 0;
        if (accountId > 0) return new File(userdata, accountId + "/config/localconfig.vdf");
        File[] users = userdata.listFiles();
        if (users != null) {
            for (File u : users) {
                File cfg = new File(u, "config/localconfig.vdf");
                if (cfg.isFile()) {
                    Log.i(TAG, "passthrough: accountId unresolved — using globbed " + u.getName());
                    return cfg;
                }
            }
        }
        return null;
    }

    /**
     * Idempotently set the four {@link #STEAM_INPUT_KEYS} to "0" inside {@code localconfig.vdf}'s top-level
     * {@code UserLocalConfigStore > system} block. Creates the file (with just the system block) when absent,
     * creates the system block when missing, rewrites an existing key's value in place, and never duplicates a
     * key. It's a Valve KeyValues/VDF text file; the editor preserves every other key, the file's indentation,
     * and its line endings, and leaves the file untouched (returning without a write) if the root block isn't
     * the expected {@code UserLocalConfigStore}.
     */
    private static void disableSteamControllerSupport(File localConfig) {
        try {
            File dir = localConfig.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "passthrough: could not create localconfig dir — Steam Input keys skipped");
                return;
            }
            String content = localConfig.isFile() ? FileUtils.readString(localConfig) : null;
            String updated = (content == null || content.trim().isEmpty())
                    ? freshLocalConfig()
                    : injectSteamInputKeys(content);
            if (updated == null) {
                Log.w(TAG, "passthrough: localconfig.vdf shape unexpected — Steam Input keys left as-is");
                return;
            }
            if (!updated.equals(content)) {
                if (FileUtils.writeString(localConfig, updated))
                    Log.i(TAG, "passthrough: Steam Input controller support disabled in localconfig.vdf");
                else
                    Log.w(TAG, "passthrough: failed writing localconfig.vdf");
            } else {
                Log.i(TAG, "passthrough: Steam Input already disabled (localconfig.vdf unchanged)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "passthrough: localconfig.vdf edit failed (non-fatal): " + t.getMessage());
        }
    }

    /** A minimal but valid localconfig.vdf carrying just the disabled Steam-Input keys (fresh prefix). */
    private static String freshLocalConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("\"UserLocalConfigStore\"\n{\n\t\"system\"\n\t{\n");
        for (String k : STEAM_INPUT_KEYS) sb.append("\t\t\"").append(k).append("\"\t\t\"0\"\n");
        sb.append("\t}\n}\n");
        return sb.toString();
    }

    /**
     * Return {@code content} with the four Steam-Input keys forced to "0" inside {@code UserLocalConfigStore >
     * system} — rewriting a present key in place and inserting any missing one before the block's close brace,
     * synthesizing the {@code system} block when absent. Returns {@code null} (caller skips the write) when the
     * root isn't {@code UserLocalConfigStore}. A brace-depth + block-name walk over the tab-indented KeyValues;
     * unit-tested for idempotency, in-place flips, block synthesis, CRLF preservation, and foreign-root safety.
     */
    static String injectSteamInputKeys(String content) {
        String nl = content.contains("\r\n") ? "\r\n" : "\n";
        java.util.List<String> lines =
                new java.util.ArrayList<>(java.util.Arrays.asList(content.split("\r\n|\r|\n", -1)));

        int rootOpen = -1, rootClose = -1, sysOpen = -1, sysClose = -1;
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        String pendingKey = null;
        int depth = 0;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty()) continue;
            if (t.equals("{")) {
                String parent = stack.peek();
                depth++;
                String name = (pendingKey != null) ? pendingKey : "";
                stack.push(name);
                pendingKey = null;
                if (depth == 1 && "UserLocalConfigStore".equalsIgnoreCase(name)) rootOpen = i;
                if (depth == 2 && "system".equalsIgnoreCase(name)
                        && "UserLocalConfigStore".equalsIgnoreCase(parent)) sysOpen = i;
                continue;
            }
            if (t.equals("}")) {
                String name = stack.isEmpty() ? "" : stack.pop();
                if ("system".equalsIgnoreCase(name) && sysOpen >= 0 && sysClose < 0) sysClose = i;
                if ("UserLocalConfigStore".equalsIgnoreCase(name) && rootOpen >= 0 && rootClose < 0) rootClose = i;
                depth--;
                pendingKey = null;
                continue;
            }
            String key = firstQuoted(t);
            if (key == null) { pendingKey = null; continue; }
            pendingKey = hasSecondQuoted(t) ? null : key;   // a lone quoted token is a block header
        }

        if (rootOpen < 0 || rootClose < 0) return null;     // not a localconfig shape — skip safely

        java.util.Set<String> targets = new java.util.LinkedHashSet<>(java.util.Arrays.asList(STEAM_INPUT_KEYS));
        if (sysOpen >= 0 && sysClose >= 0 && sysClose > sysOpen) {
            String leafIndent = null;
            java.util.Set<String> present = new java.util.HashSet<>();
            for (int i = sysOpen + 1; i < sysClose; i++) {
                String line = lines.get(i);
                if (line.trim().isEmpty()) continue;
                if (leafIndent == null) leafIndent = leadingWhitespace(line);
                String key = firstQuoted(line.trim());
                if (key != null && targets.contains(key)) {
                    lines.set(i, leadingWhitespace(line) + "\"" + key + "\"\t\t\"0\"");
                    present.add(key);
                }
            }
            if (leafIndent == null) leafIndent = leadingWhitespace(lines.get(sysOpen)) + "\t";
            java.util.List<String> insert = new java.util.ArrayList<>();
            for (String k : targets) if (!present.contains(k)) insert.add(leafIndent + "\"" + k + "\"\t\t\"0\"");
            lines.addAll(sysClose, insert);
        } else {
            String childIndent = null;
            for (int i = rootOpen + 1; i < rootClose; i++) {
                if (!lines.get(i).trim().isEmpty()) { childIndent = leadingWhitespace(lines.get(i)); break; }
            }
            if (childIndent == null) childIndent = leadingWhitespace(lines.get(rootOpen)) + "\t";
            String keyIndent = childIndent + "\t";
            java.util.List<String> block = new java.util.ArrayList<>();
            block.add(childIndent + "\"system\"");
            block.add(childIndent + "{");
            for (String k : targets) block.add(keyIndent + "\"" + k + "\"\t\t\"0\"");
            block.add(childIndent + "}");
            lines.addAll(rootClose, block);
        }
        return String.join(nl, lines);
    }

    private static String firstQuoted(String s) {
        int a = s.indexOf('"');
        if (a < 0) return null;
        int b = s.indexOf('"', a + 1);
        if (b < 0) return null;
        return s.substring(a + 1, b);
    }

    private static boolean hasSecondQuoted(String s) {
        int a = s.indexOf('"');
        if (a < 0) return false;
        int b = s.indexOf('"', a + 1);
        if (b < 0) return false;
        int c = s.indexOf('"', b + 1);
        if (c < 0) return false;
        return s.indexOf('"', c + 1) >= 0;
    }

    private static String leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return s.substring(0, i);
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

    /**
     * For a RealSteam launch, map an arch-specific game exe to its LAUNCHER exe when one exists on disk.
     * Steam's {@code LaunchApp} spawns the launcher {@code <game>.exe} (which internally hands off to the
     * arch child {@code <game>_win64.exe}); the agent must watch the launcher, or it never sees the
     * secure process and falls back insecure. So if {@code relExe}'s basename ends in a known arch tag
     * and {@code <base>.exe} exists in {@code hostInstallDir}, return that instead. Otherwise unchanged.
     */
    static String preferLauncherExe(String relExe, String hostInstallDir) {
        try {
            if (relExe == null || hostInstallDir == null || hostInstallDir.isEmpty()) return relExe;
            int slash = relExe.lastIndexOf('\\');
            String dir  = slash >= 0 ? relExe.substring(0, slash + 1) : "";
            String base = slash >= 0 ? relExe.substring(slash + 1) : relExe;
            if (!base.toLowerCase().endsWith(".exe")) return relExe;
            String stem = base.substring(0, base.length() - 4);            // "cstrike_win64"
            String[] archTags = { "_win64", "_win32", "_x64", "_x86", "_64", "_32" };
            for (String arch : archTags) {
                if (stem.toLowerCase().endsWith(arch)) {
                    String launcher = stem.substring(0, stem.length() - arch.length()) + ".exe"; // "cstrike.exe"
                    String hostRel  = (dir + launcher).replace('\\', File.separatorChar);
                    if (new File(hostInstallDir, hostRel).isFile()) {
                        Log.i(TAG, "prepare: RealSteam watch exe \"" + base + "\" -> launcher \""
                                + launcher + "\" (Steam spawns the launcher; \"" + base + "\" is its arch child)");
                        return dir + launcher;
                    }
                    break;  // matched an arch tag but the launcher isn't on disk — keep the original
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "preferLauncherExe failed: " + t.getMessage());
        }
        return relExe;
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
