package com.winlator.star.store;

import android.content.Context;
import android.util.Log;

import com.winlator.star.container.Shortcut;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.WineRegistryEditor;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AppSteamLauncher — the launch-orchestration layer for {@code launchMode=AppSteam} (Option B):
 * the game runs on the APP's own Steam session instead of a second client inside the container.
 *
 * <p>Sibling of {@link RealSteamLauncher} (SteamLite) with the opposite shape:
 * <ul>
 *   <li>NO agent, NO {@code WN_STEAM_*} staging, NO {@code PROTON_DISABLE_LSTEAMCLIENT} — the game's
 *       genuine {@code steam_api64.dll} loads {@code steamclient64.dll}, which Proton's ntdll hook
 *       redirects to {@code lsteamclient.dll} (built into our Proton 11 layer), whose unix side
 *       {@code dlopen}s Valve's {@code androidarm64/libsteamclient.so} ({@code WINESTEAMCLIENTPATH64})
 *       and talks over {@code Steam3Master} (127.0.0.1:57343) to…</li>
 *   <li>…the app's session HOST ({@link SteamHost}, the {@code bl-steam-host} process) that is logged
 *       into the same account with the engine's refresh token. The game exe is launched DIRECTLY by
 *       Wine (no {@code steam.exe -applaunch}); {@code getWineStartCommand()} is untouched.</li>
 *   <li>The app's own CM session (store, friends, chat, drawer Friends tab) is NOT suspended: "two
 *       logons, one player" — the engine must not report {@code ClientGamesPlayed} while the host
 *       runs (gated in {@code XServerDisplayActivity.announceOfflineSteamPresence}).</li>
 * </ul>
 *
 * <p><b>Failure domain:</b> emulation launch only (a genuine-Steam game RUNNING with a live client).
 * Not download reliability, not Goldberg. Any missing prerequisite (token / appId / host binary /
 * Valve client not downloaded / Proton layer without lsteamclient) makes {@link #prepare} return
 * {@code null} so the caller falls through to the normal launch.
 *
 * <p><b>Token hygiene:</b> the refresh token is a registered secret. It is read here and handed ONLY
 * to {@link SteamHost#start} (environment of the host process, scrubbed by the host on startup) —
 * never logged, never written to a file, never placed in the guest env.
 */
public final class AppSteamLauncher {

    private static final String TAG = "BH_APPSTEAM";

    public static final String LAUNCH_MODE = "AppSteam";
    /** Guest-visible Steam install dir ({@code SteamPath}; the genuine PE DLLs live here). */
    public static final String STEAM_DIR_WIN = "C:\\Program Files (x86)\\Steam";
    public static final String STEAM3_MASTER = "127.0.0.1:57343";
    public static final String STEAM_CLIENT_SERVICE = "127.0.0.1:57344";
    /** Host log file name inside the per-launch state dir (collected by SteamLiteLogCollector). */
    public static final String HOST_LOG_NAME = "steam_host.log";

    /** The genuine PE client DLLs the SteamLite package carries; staged so LoadLibrary's target exists. */
    private static final String[] CLIENT_DLLS = {"steamclient64.dll", "steamclient.dll"};

    /** Result of a successful {@link #prepare}: guest env + host config. Immutable. */
    public static final class Plan {
        /** Env to merge into the launch {@code envVars} (NO secrets). */
        public final Map<String, String> env;
        /** Env keys that must be REMOVED from the launch env (the SteamLite-only switches). */
        public final String[] envRemove;
        public final SteamHost.Config host;
        public final int appId;
        public final long steamId64;
        /** The host's stdout log (for the log collector). */
        public final File hostLog;
        /** Whether the active Proton layer ships lsteamclient (null = unknown, not checked). */
        public final Boolean layerHasLsteamclient;

        Plan(Map<String, String> env, String[] envRemove, SteamHost.Config host, int appId, long steamId64,
             File hostLog, Boolean layerHasLsteamclient) {
            this.env = env;
            this.envRemove = envRemove;
            this.host = host;
            this.appId = appId;
            this.steamId64 = steamId64;
            this.hostLog = hostLog;
            this.layerHasLsteamclient = layerHasLsteamclient;
        }
    }

    private AppSteamLauncher() {}

    /**
     * Build the plan and START the host. MUST be called off the main thread. Returns {@code null}
     * (caller falls back to a normal launch) when any prerequisite is missing. Never throws; never
     * logs the token.
     *
     * @param ctx           app/activity context
     * @param driveC        the launching container's {@code <rootDir>/.wine/drive_c} (caller-derived)
     * @param wineLibDir    the active Wine/Proton layer's {@code lib/wine} dir (to detect lsteamclient), may be null
     * @param guestHome     the guest's {@code HOME} (imagefs home) — for the stock {@code ~/.steam/sdkarm64} symlink
     * @param shortcut      the game shortcut (its path is the guest exe)
     * @param appId         resolved Steam appId
     * @param agentPort     the launch activity's agent-channel port (0 = none)
     */
    public static Plan prepare(Context ctx, File driveC, File wineLibDir, File guestHome, Shortcut shortcut,
                               int appId, int agentPort) {
        try {
            if (driveC == null || shortcut == null) { Log.w(TAG, "prepare: null driveC/shortcut — fallback"); return null; }
            if (appId <= 0) { Log.w(TAG, "prepare: no appId — fallback"); return null; }
            if (!SteamHost.INSTANCE.isAvailable(ctx)) { Log.w(TAG, "prepare: host binary missing — fallback"); return null; }
            if (!SteamHostComponent.INSTANCE.isInstalled(ctx)) {
                Log.w(TAG, "prepare: Valve client not downloaded — fallback");
                return null;
            }
            String valveVersion = SteamHostComponent.INSTANCE.installedVersion(ctx);
            if (!SteamHostComponent.INSTANCE.getVERIFIED_BUILDS().contains(valveVersion)) {
                Log.w(TAG, "prepare: Valve client build '" + valveVersion + "' not verified for the host — fallback");
                return null;
            }

            // ── 1. Identity + token (registered secret — never logged/written) ───────────────────
            SteamRepository repo = SteamRepository.getInstance();
            String token = repo.getRefreshToken();
            String username = repo.getUsername();
            long steamId64 = repo.getSteamId64();
            if (token == null || token.isEmpty() || username == null || username.isEmpty() || steamId64 == 0L) {
                Log.w(TAG, "prepare: no Steam sign-in (tokenLen=" + (token != null ? token.length() : 0)
                        + " userLen=" + (username != null ? username.length() : 0) + " sid=" + (steamId64 != 0) + ") — fallback");
                return null;
            }
            // Rule 3 (spike §3): renew an expiring token BEFORE session B starts, so both logons hold the
            // same token and the host never has to fight the engine's rotation mid-game. Cheap when the
            // token has weeks left (returns false without a network round-trip).
            try {
                if (SteamSessionManager.INSTANCE.maybeRenewRefreshToken(ctx)) {
                    token = repo.getRefreshToken();
                    Log.i(TAG, "prepare: refresh token renewed before the host start (len=" + token.length() + ")");
                }
            } catch (Throwable t) { Log.w(TAG, "prepare: token renewal check errored (continuing)", t); }
            Log.i(TAG, "prepare: identity ok (tokenLen=" + token.length() + " userLen=" + username.length()
                    + " appId=" + appId + " valveBuild=" + valveVersion + ")");

            // ── 2. Prefix: genuine PE client DLLs + registry ActiveProcess ───────────────────────
            File steamDir = new File(driveC, "Program Files (x86)/Steam");
            if (!steamDir.exists() && !steamDir.mkdirs()) { Log.w(TAG, "prepare: cannot create prefix Steam dir — fallback"); return null; }
            stageClientDlls(ctx, steamDir);
            seedActiveProcessRegistry(driveC, repo.getAccountId());

            // ── 3. Proton layer check (informational — the launch proceeds; the game will tell) ──
            Boolean layerOk = null;
            if (wineLibDir != null && wineLibDir.isDirectory()) {
                File pe = new File(wineLibDir, "aarch64-windows/lsteamclient.dll");
                File unix = new File(wineLibDir, "aarch64-unix/lsteamclient.so");
                layerOk = pe.isFile() && unix.isFile();
                Log.i(TAG, "prepare: Proton layer lsteamclient " + (layerOk ? "PRESENT" : "ABSENT (" + pe + " / " + unix + ")"));
            }

            // ── 4. Stock-Proton fallback path: <HOME>/.steam/sdkarm64/steamclient.so → Valve lib ──
            File valveLib = SteamHostComponent.INSTANCE.libSteamClient(ctx);
            if (guestHome != null) {
                try {
                    File sdk = new File(guestHome, ".steam/sdkarm64");
                    if (!sdk.exists()) sdk.mkdirs();
                    File link = new File(sdk, "steamclient.so");
                    if (FileUtils.isSymlink(link) || link.exists()) FileUtils.delete(link);
                    FileUtils.symlink(valveLib.getAbsolutePath(), link.getAbsolutePath());
                } catch (Throwable t) { Log.w(TAG, "prepare: ~/.steam/sdkarm64 symlink skipped: " + t.getMessage()); }
            }

            // ── 5. Host config (per-SteamID HOME so the client's cached credentials persist) ─────
            File stateDir = SteamHost.INSTANCE.stateDir(ctx, steamId64);
            File hostHome = new File(stateDir, "home");
            File hostLog = new File(stateDir, HOST_LOG_NAME);
            File breakpad = new File(ctx.getFilesDir(), "imagefs/usr/tmp/breakpad");
            breakpad.mkdirs();
            boolean persona = false;
            try { persona = SteamPrefs.INSTANCE.isSocialEnabled(ctx); } catch (Throwable ignored) {}
            Map<String, String> hostExtra = new LinkedHashMap<>();
            hostExtra.put("STEAM_BASE_FOLDER", steamDir.getAbsolutePath());
            hostExtra.put("BREAKPAD_DUMP_LOCATION", breakpad.getAbsolutePath());
            hostExtra.put("_STEAM_SETENV_MANAGER", "1");
            hostExtra.put("STEAMVIDEOTOKEN", "1");
            hostExtra.put("SteamOS", "1");
            hostExtra.put("ENABLE_VK_LAYER_VALVE_steam_overlay_1", "0");
            SteamHost.Config hostCfg = new SteamHost.Config(appId, hostHome, hostLog, STEAM3_MASTER,
                    STEAM_CLIENT_SERVICE, agentPort, persona, hostExtra);

            // ── 6. Guest env (GameNative BionicProgramLauncherComponent / WinNative WnWineEnvVars) ─
            Map<String, String> env = new LinkedHashMap<>();
            // A. lsteamclient's unix side → Valve's bionic client (both arches: the unix side is
            //    always aarch64 under arm64ec/wow64, so the same lib serves 32-bit games).
            env.put("WINESTEAMCLIENTPATH64", valveLib.getAbsolutePath());
            env.put("WINESTEAMCLIENTPATH", valveLib.getAbsolutePath());
            // B. the in-game client instance's bootstrap-gate handshake + IPC endpoints (= the host's)
            env.put("_STEAM_SETENV_MANAGER", "1");
            env.put("BREAKPAD_DUMP_LOCATION", breakpad.getAbsolutePath());
            env.put("STEAM_BASE_FOLDER", steamDir.getAbsolutePath());
            env.put("ENABLE_VK_LAYER_VALVE_steam_overlay_1", "0");
            env.put("SteamOS", "1");             // forces ISteamUtils::IsOverlayEnabled() so invite UI opens
            env.put("STEAMVIDEOTOKEN", "1");
            env.put("Steam3Master", STEAM3_MASTER);
            env.put("SteamClientService", STEAM_CLIENT_SERVICE);
            // C. Wine-side Steam identity expected by steam_api / Steamworks games
            env.put("SteamUser", username);
            env.put("SteamAppUser", username);
            env.put("SteamClientLaunch", "1");
            env.put("SteamEnv", "1");
            env.put("SteamPath", STEAM_DIR_WIN);
            env.put("ValvePlatformMutex", "c:\\Program Files (x86)\\Steam/");
            env.put("STEAMID", String.valueOf(steamId64));
            env.put("SteamGameId", String.valueOf(appId));
            env.put("SteamAppId", String.valueOf(appId));
            // D. marker for the log collector / diagnostics
            env.put("BL_STEAM_MODE", LAUNCH_MODE);
            // The SteamLite-only switch must be ABSENT (unset = lsteamclient enabled in ntdll).
            String[] remove = {"PROTON_DISABLE_LSTEAMCLIENT", "WN_STEAM_TOKEN", "WN_STEAM_USERNAME", "WN_STEAM_STEAMID",
                    "WN_STEAM_APPID", "WN_STEAM_GAMEEXE_FILE", "WN_STEAM_VAC", "WN_STEAM_CMLIST"};

            // ── 7. Start the host NOW (its logon overlaps the rest of the launch setup) ───────────
            if (!SteamHost.INSTANCE.start(ctx, hostCfg, token, username, steamId64)) {
                Log.w(TAG, "prepare: host did not start — fallback");
                return null;
            }
            Log.i(TAG, "prepare: AppSteam plan armed (appId=" + appId + ", host log " + hostLog.getName()
                    + ", agentPort " + agentPort + ", layerHasLsteamclient=" + layerOk + ")");
            return new Plan(env, remove, hostCfg, appId, steamId64, hostLog, layerOk);
        } catch (Throwable t) {
            Log.w(TAG, "prepare: errored — fallback", t);
            SteamHost.INSTANCE.stop("prepare errored");
            return null;
        }
    }

    /**
     * Copy the genuine {@code steamclient64.dll} / {@code steamclient.dll} from the SteamLite package
     * (when downloaded) into the prefix Steam dir so {@code steam_api64.dll}'s LoadLibrary target
     * exists — Proton's ntdll hook then swaps the mapped module for {@code lsteamclient.dll}. Only
     * copies what is missing/outdated; never touches the SteamLite agent, never wipes the dir.
     */
    private static void stageClientDlls(Context ctx, File steamDir) {
        try {
            File pkg = SteamLiteComponent.INSTANCE.installDir(ctx);
            int copied = 0, present = 0;
            for (String dll : CLIENT_DLLS) {
                File src = new File(pkg, dll);
                File dst = new File(steamDir, dll);
                if (dst.isFile() && (!src.isFile() || dst.length() == src.length())) { present++; continue; }
                if (!src.isFile()) continue;
                if (FileUtils.copy(src, dst)) copied++;
            }
            Log.i(TAG, "client DLLs: " + present + " present, " + copied + " copied"
                    + ((present + copied) < CLIENT_DLLS.length ? " (SteamLite package absent — steam_api's LoadLibrary target may be missing)" : ""));
        } catch (Throwable t) {
            Log.w(TAG, "client DLL staging failed (non-fatal): " + t.getMessage());
        }
    }

    /**
     * {@code HKCU\Software\Valve\Steam\ActiveProcess} as Proton's steam_helper / GameNative write it:
     * {@code SteamClientDll{,64}} = the genuine DLL paths in the prefix Steam dir (the hook redirects
     * the load), {@code ActiveUser} = the account id, {@code Universe = Public}, {@code pid} = 0.
     * Also {@code Software\Valve\Steam\SteamPath}. Best-effort.
     */
    private static void seedActiveProcessRegistry(File driveC, int accountId) {
        try {
            File userReg = new File(driveC.getParentFile(), "user.reg");
            if (!userReg.isFile()) { Log.w(TAG, "registry: user.reg missing at " + userReg); return; }
            String key = "Software\\Valve\\Steam\\ActiveProcess";
            try (WineRegistryEditor ed = new WineRegistryEditor(userReg)) {
                ed.setCreateKeyIfNotExist(true);
                ed.setDwordValue(key, "ActiveUser", accountId);
                ed.setDwordValue(key, "pid", 0);
                ed.setStringValue(key, "SteamClientDll", STEAM_DIR_WIN + "\\steamclient.dll");
                ed.setStringValue(key, "SteamClientDll64", STEAM_DIR_WIN + "\\steamclient64.dll");
                ed.setStringValue(key, "Universe", "Public");
                ed.setStringValue("Software\\Valve\\Steam", "SteamPath", STEAM_DIR_WIN.replace('\\', '/'));
                ed.setStringValue("Software\\Valve\\Steam", "SteamExe", STEAM_DIR_WIN.replace('\\', '/') + "/steam.exe");
            }
            Log.i(TAG, "registry: ActiveProcess seeded (ActiveUser=" + accountId + ")");
        } catch (Throwable t) {
            Log.w(TAG, "registry: ActiveProcess seed failed (non-fatal): " + t.getMessage());
        }
    }
}
