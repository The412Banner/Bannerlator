package com.winlator.star.core;

import android.content.Context;
import android.util.Log;

import com.winlator.star.store.SteamLogRedactor;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gathers the genuine Steam client's OWN logs after a SteamLite (Real-Steam / VAC) launch and folds
 * them — with a plain-English summary and an auto-scanned diagnostics section on top — into a single
 * shareable {@code steamlite.txt} in the game's log folder, right beside {@code wine_debug.log}.
 *
 * The real client ({@code steam.exe}, staged by {@link com.winlator.star.store.RealSteamLauncher})
 * writes timestamped logs inside the container prefix at
 * {@code drive_c/Program Files (x86)/Steam/logs/*.txt}. Those are exactly the logs that explain an
 * online failure — a logon rejected because the account is in use elsewhere, a VAC-insecure launch, a
 * cloud-save conflict — none of which appear in the Wine or DXVK logs. This collector copies only the
 * useful ones, in debug order (auth → session → networking → the rest), each fully scrubbed.
 *
 * Everything is best-effort and NEVER throws: it runs on the exit path, and log collection must never
 * be able to break a game exiting. If the SteamLite marker/logs aren't in the prefix (a normal,
 * non-SteamLite launch) it returns quietly and writes nothing.
 *
 * <p><b>Redaction.</b> Every line goes through {@link SteamLogRedactor#redactSteamClientLine}, and the
 * finished file is re-scanned by {@link SteamLogRedactor#auditSteamClientText}. Auth tokens, cookies
 * and the account name are removed; the SteamID is partially masked; timestamps, servers, EResults and
 * paths are kept so the log stays debuggable.
 */
public final class SteamLiteLogCollector {

    private static final String TAG = "SteamLiteLogs";

    /** The single combined file we write. Lowercase so it matches {@code LogRotation.isOurRunLog}. */
    public static final String OUTPUT_NAME = "steamlite.txt";

    /** Steam client log dir, relative to the container's {@code drive_c}. */
    private static final String STEAM_DIR_REL = "Program Files (x86)/Steam";
    private static final String LOGS_SUBDIR = "logs";

    /** SteamLite install markers staged into the prefix Steam dir (see SteamLiteComponent / .tzst). */
    private static final String[] MARKERS = { "steamlite.version", ".steamlite_version", "steam.exe" };

    /** Per Steam log: how much of its (accumulating) tail to carry, and the head cap on wine_debug. */
    private static final long STEAM_LOG_TAIL_BYTES = 256L * 1024;
    private static final long WINE_SCAN_TAIL_BYTES = 4L * 1024 * 1024;
    /** The agent's own log is small and rewritten each run; a modest tail covers the whole file. */
    private static final long LAUNCHER_LOG_TAIL_BYTES = 256L * 1024;
    /** Most recent engine-log lines folded into the bundle (Rust engine only). */
    private static final int ENGINE_LINES_MAX = 400;
    // Exact "watching ... for exit (<path>)" suffixes from the agent's launch log; the "for exit ("
    // prefix keeps the steamservice line ("... will use CreateProcess fallback") from matching.
    private static final String LAUNCHER_SECURE_MARK   = "for exit (LaunchApp path)";
    private static final String LAUNCHER_FALLBACK_MARK = "for exit (CreateProcess fallback)";
    private static final long DX_SCAN_TAIL_BYTES   = 1L * 1024 * 1024;

    /** The Steam logs we include, in the debug-flow order the summary reads top-to-bottom. */
    private static final String[][] INCLUDED = {
            { "connection_log.txt",     "login & network" },
            { "gameprocess_log.txt",    "game launch/exit" },
            { "remote_connections.txt", "game networking / P2P" },
            { "content_log.txt",        "downloads" },
            { "cloud_log.txt",          "cloud saves" },
            { "stats_log.txt",          "achievements" },
            { "shader_log.txt",         "shader cache" },
    };

    private SteamLiteLogCollector() {}

    /** Immutable launch-context values for the summary header — the SAME ones {@code wine_debug.log}
     *  prints, passed straight from the launch activity rather than re-derived. */
    public static final class Info {
        public final String containerId;
        public final String wineLayer;       // container.getWineVersion()
        public final String emulator;        // container.getEmulator() (raw; translator is derived)
        public final boolean arm64ec;        // wineInfo.isArm64EC()
        public final String dxwrapper;       // this.dxwrapper
        public final String dxvkVersion;     // dxwrapperConfig.get("version")
        public final String vkd3dVersion;    // dxwrapperConfig.get("vkd3dVersion")

        public Info(String containerId, String wineLayer, String emulator, boolean arm64ec,
                    String dxwrapper, String dxvkVersion, String vkd3dVersion) {
            this.containerId = containerId;
            this.wineLayer = wineLayer;
            this.emulator = emulator;
            this.arm64ec = arm64ec;
            this.dxwrapper = dxwrapper;
            this.dxvkVersion = dxvkVersion;
            this.vkd3dVersion = vkd3dVersion;
        }
    }

    /**
     * Collect this run's SteamLite logs into {@code perGameLogDir/steamlite.txt}. No-op (returns
     * quietly) when the SteamLite marker/logs dir isn't present in the prefix.
     *
     * @param driveC         the container's {@code .wine/drive_c} (caller-derived, exactly as
     *                       {@link com.winlator.star.store.RealSteamLauncher#prepare} takes it — NOT
     *                       hardcoded to any xuser id)
     * @param perGameLogDir  the resolved per-game log dir (from
     *                       {@link LogLocation#resolveGameLogDir}) — where {@code steamlite.txt} lands
     *                       and where {@code wine_debug.log}/DXVK logs already sit for the scan
     */
    public static void collect(Context context, File driveC, File perGameLogDir,
                               String gameName, int appId, Info info) {
        collect(context, driveC, perGameLogDir, gameName, appId, info, null);
    }

    /**
     * As {@link #collect(Context, File, File, String, int, Info)}, plus the live agent-channel event
     * lines of this launch ({@link com.winlator.star.store.SteamAgentChannel#eventLines()}) when the
     * agent connected. When present they are the DEFINITIVE record of sign-in / secure-launch / exit
     * and take precedence over the log-file inference in the DIAGNOSTICS section; they are also
     * appended as their own raw section. Null/empty = no channel this run (older agent).
     */
    public static void collect(Context context, File driveC, File perGameLogDir,
                               String gameName, int appId, Info info, List<String> agentEvents) {
        try {
            if (driveC == null || perGameLogDir == null) return;

            // A Steam account NAME isn't pattern-detectable — the scrub can only strip it by exact
            // match against registered secrets. This collector runs on the game-launch path, where
            // SteamRepository (which normally registers them) may never have initialised in this
            // process, leaving SECRETS empty. Register the saved username + refresh token from the
            // same prefs SteamRepository uses, so the account name can never survive into the file a
            // user is invited to share. (The token is also caught by the pattern backstops; the name
            // is not, which is why this matters.)
            try {
                android.content.SharedPreferences sp =
                        context.getSharedPreferences("steam_prefs", Context.MODE_PRIVATE);
                SteamLogRedactor.registerSecret(sp.getString("username", ""));
                SteamLogRedactor.registerSecret(sp.getString("refresh_token", ""));
            } catch (Throwable ignored) {}

            File steamDir = new File(driveC, STEAM_DIR_REL);
            File logsDir = new File(steamDir, LOGS_SUBDIR);
            String steamLiteVersion = readMarkerVersion(steamDir);

            // Not a SteamLite launch (or the client left nothing) → collect nothing, don't error.
            if (!logsDir.isDirectory() && steamLiteVersion == null) {
                Log.d(TAG, "no SteamLite marker/logs in prefix — skipping");
                return;
            }

            // Read the Steam client logs (redacted for output, raw kept only long enough to scan).
            Map<String, String> steamRedacted = new LinkedHashMap<>();
            Map<String, String> steamRaw = new LinkedHashMap<>();
            for (String[] pair : INCLUDED) {
                File f = new File(logsDir, pair[0]);
                if (!f.isFile()) continue;
                String raw = readTail(f, STEAM_LOG_TAIL_BYTES);
                if (raw == null) continue;
                steamRaw.put(pair[0], raw);
                steamRedacted.put(pair[0], redactBlock(raw));
            }

            // Sibling session logs in the SAME folder, scanned (not copied — they are their own files).
            String wineText = readTail(new File(perGameLogDir, "wine_debug.log"), WINE_SCAN_TAIL_BYTES);
            String dxText = readFirstDxvk(perGameLogDir);
            // Our steam.exe agent's own log (C:\wn-launcher.log, rewritten every run) — the definitive
            // record of whether Steam's LaunchApp spawned the game or the agent fell back to CreateProcess.
            String launcherLog = readTail(new File(driveC, "wn-launcher.log"), LAUNCHER_LOG_TAIL_BYTES);

            // Rust engine (use_rust_steam_engine ON): the app-side session brain keeps its own
            // record (steam_engine.txt — AUTH / SESSION / CLOUD / ACHV / DL lines, redacted at the
            // source). Its lines feed the same AUTH / SESSION / CLOUD / ACHIEVEMENTS diagnostics the
            // genuine client's logs feed, and ride along as a raw section. The always-on
            // steam_session.txt (status transitions) is tailed next to it. Empty on JavaSteam.
            List<String> engineLines = engineLines(context);
            String sessionTail = engineLines.isEmpty() ? null : readEngineSessionTail(context);

            StringBuilder out = new StringBuilder(8 * 1024);
            appendSummary(out, context, gameName, appId, info, steamLiteVersion, dxText);
            if (!engineLines.isEmpty()) out.append("Steam engine: Rust (libblsteam.so) — app-side session log included\n");
            // The genuine Steam client logs accumulate across every launch — anchor to THIS run so the
            // diagnostics and raw sections report this session, not days of history. Null = no anchor.
            String since = sessionStart(steamRaw.get("gameprocess_log.txt"), appId);
            appendDiagnostics(out, appId, wineText, dxText, steamRaw, since, launcherLog, agentEvents, engineLines);
            appendRawSections(out, steamRedacted, since);
            appendAgentSection(out, agentEvents);
            appendEngineSection(out, engineLines, sessionTail);

            // Belt-and-suspenders: re-scan the finished file for anything a header line carried through.
            String finished = SteamLogRedactor.auditSteamClientText(out.toString());

            File target = new File(perGameLogDir, OUTPUT_NAME);
            if (FileUtils.writeString(target, finished)) {
                Log.i(TAG, "wrote " + target.getAbsolutePath() + " (" + steamRedacted.size() + " Steam logs)");
            }
        } catch (Throwable t) {
            // Never let log collection break a game exiting.
            Log.w(TAG, "collect failed", t);
        }
    }

    // ── Summary header ──────────────────────────────────────────────────────────────────────────

    /**
     * The header block, mirroring {@code wine_debug.log}'s. There is no single reusable
     * "assemble the wine header" call — that header is written inline in XServerDisplayActivity and
     * {@code LogReport.facts()} emits GitHub-flavoured markdown — so this reuses the genuinely-shared
     * pieces: {@link LogcatCapture#deviceHeader} (app/device/date), the GPU/driver extraction
     * technique + regexes from {@code LogReport}, and {@link GPUInformation}. Launch-specific fields
     * come straight from {@link Info}, i.e. the same values the wine header prints.
     */
    private static void appendSummary(StringBuilder out, Context context, String gameName, int appId,
                                      Info info, String steamLiteVersion, String dxText) {
        out.append("===== SteamLite (Steam client) log =====\n\n");
        out.append(LogcatCapture.deviceHeader(context));   // === Bannerlator log ===, Captured/App/Device/Android

        out.append("Game: ").append(safe(gameName)).append(appId > 0 ? " (appID " + appId + ")" : "").append('\n');
        if (info != null) {
            out.append("Container ID: ").append(safe(info.containerId)).append('\n');
            out.append("Wine / Proton: ").append(safe(info.wineLayer)).append('\n');
            out.append("Emulator: ").append(translator(info)).append('\n');
            out.append("DX wrapper: ").append(safe(info.dxwrapper))
               .append("  (DXVK ").append(orDash(info.dxvkVersion))
               .append(", VKD3D ").append(orDash(info.vkd3dVersion)).append(")\n");
        }

        // GPU + driver: prefer the DXVK log's own report (as LogReport does), else ask the driver.
        String gpu = firstMatch(dxText, "(?m)^info: +Device *: *(\\S.*?) *$");
        String driver = firstMatch(dxText, "(?m)^info: +Driver *: *(\\S.*?) *$");
        if (gpu == null) {
            try { gpu = GPUInformation.getRenderer(null, context); } catch (Throwable ignored) {}
        }
        if (gpu != null) {
            try {
                String model = GPUInformation.extractModelName(gpu);
                if (model != null && !model.trim().isEmpty()) gpu = model.trim();
            } catch (Throwable ignored) {}
        }
        if (gpu != null) out.append("GPU: ").append(gpu).append('\n');
        if (driver != null) out.append("Driver: ").append(driver).append('\n');
        out.append("SteamLite version: ").append(orDash(steamLiteVersion)).append('\n');

        // The scrub note, matching the app's own wording (Log Manager / LogReport).
        out.append('\n');
        out.append("Safe to share — auth tokens, cookies and your Steam account name are removed and ")
           .append("your Steam ID is partially masked; timestamps, servers, results and file paths are ")
           .append("kept for debugging.\n");
    }

    private static String translator(Info info) {
        if (info == null) return "—";
        String emu = info.emulator == null ? "" : info.emulator.toLowerCase(Locale.US);
        // Same derivation as XServerDisplayActivity#seedRuntimeBackend.
        if (!info.arm64ec) return "Box64";
        if (emu.contains("wowbox64")) return "wowbox64";
        return "FEXCore";
    }

    // ── Diagnostics (auto-scan) ─────────────────────────────────────────────────────────────────

    /** A curated scan of THIS session's logs into plain-English one-liners. The value of the file:
     *  a reader sees the known-failure summary without reading four logs. */
    private static void appendDiagnostics(StringBuilder out, int appId, String wineText, String dxText,
                                          Map<String, String> steam, String since, String launcherLog,
                                          List<String> agentEvents, List<String> engineLines) {
        out.append("\n===== DIAGNOSTICS (auto-scan) =====\n");
        if (since != null)
            out.append("- Session scope: findings below are from THIS run (since ").append(since).append(").\n");
        else
            out.append("- Session scope: could not anchor this run in the logs; some counts may include "
                    + "earlier sessions.\n");
        List<String> f = new ArrayList<>();

        // ── Live agent channel (definitive when present — the agent TOLD us what happened) ──
        boolean agentVerdict = appendAgentChannel(f, agentEvents);

        String connection = steam.get("connection_log.txt");
        String gameproc = steam.get("gameprocess_log.txt");
        String content = steam.get("content_log.txt");
        String cloud = steam.get("cloud_log.txt");
        String stats = steam.get("stats_log.txt");
        // A single haystack for signatures that could land in any Steam log.
        String allSteam = join(steam.values());

        // ── FONT / GDI (wine_debug is per-run, so NOT session-bounded) ──
        Hit font = scan(wineText, FONT_HANDLES);
        if (font.count > 0) {
            f.add("FONT: Wine ran out of font handles (" + font.count + "x) — text/VGUI can fail to "
                    + "render and a big burst can FREEZE on a map change. Fix: a larger font-handle cap "
                    + "in the Wine build, or set cl_disablehtmlmotd 1." + when(font));
        } else {
            Hit fontErr = scan(wineText, ERR_FONT);
            if (fontErr.count > 0)
                f.add("FONT: Wine font errors (err:font:) x" + fontErr.count + "." + when(fontErr));
        }

        // ── SteamLite agent / secure-launch health (reads wine_debug ORDER + real-Steam tracking) ──
        // Skipped when the live channel already gave the definitive verdict above.
        if (!agentVerdict) appendSecureLaunch(f, wineText, gameproc, appId, since, launcherLog);
        boolean lsteam = matches(wineText, LSTEAMCLIENT_OFF) || matches(allSteam, LSTEAMCLIENT_OFF);
        f.add("CLIENT: Real Steam client mode (lsteamclient disabled — expected for SteamLite) = "
                + yn(lsteam) + ".");
        if (matches(wineText, STEAMCLIENT_FAIL))
            f.add("CLIENT: steamclient64.dll FAILED to load — the real Steam client may not have initialised.");
        Hit appinfoDenied = scanSince(allSteam, APPINFO_DENIED, since);
        if (appinfoDenied.count > 0)
            f.add("APPINFO: app-info access tokens DENIED — a strict live-service title; the VAC/online "
                    + "path may not work (Brawlhalla-style)." + when(appinfoDenied));

        // ── AUTH + SESSION CONFLICT (connection_log, bounded to this run) ──
        appendAuth(f, connection, since);
        appendSessionConflict(f, connection, since);

        // ── CONTENT / ACHIEVEMENTS / CLOUD (bounded to this run) ──
        Hit dlFail = scanSince(content, CONTENT_FAIL, since);
        if (dlFail.count > 0)
            f.add("CONTENT: download/manifest failures (" + dlFail.count + "x)." + when(dlFail));
        appendAchievements(f, stats, since);
        appendCloud(f, cloud, since);

        // ── Rust engine (app-side session): AUTH / SESSION / CLOUD / ACHIEVEMENTS from steam_engine.txt ──
        appendEngineDiagnostics(f, engineLines);

        // ── CRASH / teardown (wine_debug — per-run) ──
        Hit crash = scan(wineText, CRASH);
        if (crash.count > 0)
            f.add("CRASH: native crash detected (" + crash.count + "x — unhandled exception / access "
                    + "violation)." + when(crash));
        appendTeardown(f, wineText);

        // Emit. If wine_debug wasn't captured, say so and connect the two toggles.
        if (wineText == null)
            out.append("- wine_debug.log was not captured this run, so font/GDI and native-crash detail "
                    + "is unavailable. Turn on 'Wine debug' in Log Manager to capture it.\n");
        if (f.isEmpty()) {
            out.append("- No notable signatures found in this session's logs.\n");
        } else {
            for (String line : f) out.append("- ").append(line).append('\n');
        }
    }

    /**
     * Secure launch = the real Steam client actually launched the game this session. Primary signal:
     * gameprocess_log tracked this appId (Goldberg/offline never produces this log at all). ORDER
     * confirmation, when wine_debug is present: the steam.exe agent's DXVK "Game:" marker appears
     * before the game's. This reads WINE_DEBUG for the ordering — the previous version scanned
     * gameprocess_log for a "steam.exe" line that only exists in wine_debug, so it always came up empty
     * and false-flagged INSECURE. INSECURE is now only reported on positive evidence.
     */
    private static void appendSecureLaunch(List<String> f, String wineText, String gameproc, int appId,
                                           String since, String launcherLog) {
        Pattern tracked = ci("appid\\s+" + appId + "\\b[^\\n]*adding\\s+pid");
        boolean realSteamTracked = gameproc != null && tracked.matcher(gameproc).find();

        // DEFINITIVE signal, when present: the agent's own per-run log records which path started the
        // game ("watching \"<exe>\" for exit (LaunchApp path|CreateProcess fallback)"). A fallback run
        // still gets tracked by Steam and shows the DXVK markers in the "secure" order, so the
        // heuristics below would wrongly say YES for it — the agent log wins.
        if (launcherLog != null) {
            if (launcherLog.contains(LAUNCHER_FALLBACK_MARK)) {
                f.add("SECURE LAUNCH: NO — the steam.exe agent started the game via CreateProcess fallback "
                        + "(Steam's LaunchApp did not spawn it) = INSECURE launch: VAC servers will reject "
                        + "it and live-service titles report a wrong build (INCORRECT VERSION).");
                appendTrackedCount(f, gameproc, tracked, since);
                return;
            }
            if (launcherLog.contains(LAUNCHER_SECURE_MARK)) {
                f.add("SECURE LAUNCH: launched through the real Steam client (agent log: LaunchApp path) "
                        + "= YES (VAC-eligible secure launch).");
                appendTrackedCount(f, gameproc, tracked, since);
                return;
            }
        }

        // Order from wine_debug DXVK markers: "info:  Game: steam.exe" then "info:  Game: <game>.exe".
        Boolean orderSecure = null;
        if (wineText != null) {
            int agent = firstLineIndex(wineText, DXVK_GAME_STEAM);
            int game = firstLineIndex(wineText, DXVK_GAME_NONSTEAM);
            if (agent >= 0 && game >= 0) orderSecure = agent < game;
            else if (agent >= 0) orderSecure = Boolean.TRUE;   // agent initialised at all
        }

        if (realSteamTracked || Boolean.TRUE.equals(orderSecure)) {
            f.add("SECURE LAUNCH: launched through the real Steam client"
                    + (Boolean.TRUE.equals(orderSecure) ? " (steam.exe agent initialised before the game)"
                       : " (appID " + appId + " tracked by the Steam agent)")
                    + " = YES (VAC-eligible secure launch).");
        } else if (Boolean.FALSE.equals(orderSecure)) {
            f.add("SECURE LAUNCH: the game's render marker appeared before the steam.exe agent's — "
                    + "possible INSECURE launch; if VAC kicks you, check the Game: order in wine_debug.log.");
        } else if (gameproc != null || wineText != null) {
            f.add("SECURE LAUNCH: could not confirm the steam.exe agent launched the game this session "
                    + "(no real-Steam tracking line found) — check the raw sections if VAC kicks you.");
        }

        appendTrackedCount(f, gameproc, tracked, since);
    }

    /**
     * Fold the live agent-channel events (agent-src/AGENT_CHANNEL.md) into plain-English lines. Every
     * line is app-side data already scrubbed by the agent (masked SteamID, no token). Returns true
     * when the events settled the secure-launch question (a {@code game_spawned} / fallback /
     * refusal / sign-in failure was seen), so the file-based inference can be skipped.
     */
    private static boolean appendAgentChannel(List<String> f, List<String> agentEvents) {
        if (agentEvents == null || agentEvents.isEmpty()) return false;
        boolean started = false, loggedIn = false, verdict = false;
        String loginFail = null, refused = null, fallback = null, spawned = null, exited = null, shutdown = null;
        int achievements = 0;
        boolean sessionLost = false;
        for (String line : agentEvents) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(line);
                String ev = o.optString("ev", "");
                switch (ev) {
                    case "started": started = true; break;
                    case "logged_in": loggedIn = true; break;
                    case "login_failed":
                        loginFail = "EResult " + o.optInt("eresult", 0) + " " + o.optString("reason", "");
                        break;
                    case "launch_refused":
                        refused = o.optString("reason", "") + " (error " + o.optInt("error", -1) + ")";
                        break;
                    case "insecure_fallback":
                        fallback = o.optString("exe", "") + " — " + o.optString("reason", "")
                                + (o.has("vac") ? (o.optBoolean("vac", true) ? " (VAC title: secure launch LOST)"
                                                                              : " (non-VAC title: direct start is fine)") : "");
                        break;
                    case "direct_exe":
                        fallback = o.optString("exe", "") + " — direct-exe mode";
                        break;
                    case "game_spawned":
                        spawned = o.optString("exe", "") + (o.optBoolean("secure", false) ? " SECURE" : " INSECURE");
                        break;
                    case "session_lost": sessionLost = true; break;
                    case "achievement": achievements++; break;
                    case "game_exited": exited = "after " + (o.optLong("ms", 0L) / 1000L) + "s"; break;
                    case "shutdown": shutdown = o.optString("reason", "") + " (code " + o.optInt("code", 0) + ")"; break;
                    default: break;
                }
            } catch (Exception ignored) {}
        }
        if (!started) {
            f.add("AGENT: the live channel opened but the agent never reported 'started' — treat the lines below as partial.");
        }
        if (loginFail != null) {
            f.add("AUTH (agent): sign-in FAILED inside the container — " + loginFail.trim()
                    + ". The game did not get a Steam session; check the saved sign-in / re-auth.");
            verdict = true;
        } else if (loggedIn) {
            f.add("AUTH (agent): signed in to Steam inside the container = OK.");
        } else if (started) {
            f.add("AUTH (agent): no sign-in result was reported (agent ended before logon completed).");
        }
        if (spawned != null) {
            boolean secure = spawned.endsWith(" SECURE");
            f.add("SECURE LAUNCH (agent): " + (secure
                    ? "Steam's LaunchApp started " + spawned.replace(" SECURE", "") + " = YES (VAC-eligible secure launch)."
                    : "the game (" + spawned.replace(" INSECURE", "") + ") was started WITHOUT Steam's LaunchApp = NO — "
                      + "INSECURE launch: VAC servers will reject it and live-service titles may report a wrong build."));
            verdict = true;
        } else if (fallback != null) {
            f.add("SECURE LAUNCH (agent): NO — CreateProcess fallback (" + fallback + ").");
            verdict = true;
        } else if (refused != null) {
            f.add("SECURE LAUNCH (agent): Steam REFUSED the launch — " + refused + "; no game process was reported.");
            verdict = true;
        }
        if (refused != null && spawned != null && spawned.endsWith(" SECURE"))
            f.add("LAUNCH (agent): LaunchApp was refused first, then Steam spawned the game late inside the grace window (benign).");
        if (sessionLost) f.add("SESSION (agent): the in-game Steam session was LOST while the game ran.");
        if (achievements > 0) f.add("ACHIEVEMENTS (agent): " + achievements + " unlock event(s) reported live.");
        if (exited != null) f.add("EXIT (agent): game exited " + exited + ".");
        if (shutdown != null) f.add("EXIT (agent): agent shutdown reason " + shutdown + ".");
        return verdict;
    }

    // ── Rust engine log (Phase 3b-4) ────────────────────────────────────────────────────────────

    /** The engine's recent lines when the Rust engine drives the session; empty otherwise. */
    private static List<String> engineLines(Context context) {
        try {
            if (!com.winlator.star.store.blsteam.BlSteamEngineFlag.isEnabled(context))
                return java.util.Collections.emptyList();
            List<String> lines = com.winlator.star.store.blsteam.BlSteamEngineLog.lines();
            if (lines.isEmpty()) {
                // The process may have restarted since the launch — fall back to the file's tail.
                File f = com.winlator.star.store.blsteam.BlSteamEngineLog.file();
                if (f == null) {
                    File dir = context.getExternalFilesDir(null);
                    if (dir != null) f = new File(dir, com.winlator.star.store.blsteam.BlSteamEngineLog.FILE_NAME);
                }
                String tail = f != null ? readTail(f, STEAM_LOG_TAIL_BYTES) : null;
                if (tail == null) return java.util.Collections.emptyList();
                lines = new ArrayList<>();
                for (String l : tail.split("\n")) if (!l.trim().isEmpty()) lines.add(l);
            }
            int keep = Math.min(lines.size(), ENGINE_LINES_MAX);
            return new ArrayList<>(lines.subList(lines.size() - keep, lines.size()));
        } catch (Throwable t) {
            Log.w(TAG, "engine log read failed: " + t.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /** Tail of the always-on steam_session.txt (status transitions), or null. */
    private static String readEngineSessionTail(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return null;
            return readTail(new File(dir, "steam_session.txt"), 32L * 1024);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Value of `key=` inside an engine line, or null. */
    private static String engineField(String line, String key) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(key) + "=([^\\s,()]+)").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Diagnostics lines derived from the engine's own record. Only what the engine logged for this
     * process is used — the lines carry EResults, counts and app ids, never a token or a name.
     */
    private static void appendEngineDiagnostics(List<String> f, List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        int loggedOn = 0, logonFail = 0, loggedOff = 0, disconnects = 0, failures = 0, rotated = 0;
        String lastLogonFail = null, lastLoggedOff = null, lastFailure = null;
        boolean elsewhere = false, tokenRejected = false, signInOk = false, signInFail = false;
        int cloudDl = 0, cloudDlFail = 0, cloudUl = 0, cloudUlFail = 0, cloudBatchRefused = 0;
        String cloudLaunchIntent = null;
        int achvFetch = 0, achvNoReply = 0, achvStoreOk = 0, achvStoreFail = 0;
        int dlStarted = 0, dlComplete = 0, dlFailed = 0;
        String lastDlFail = null;
        for (String line : lines) {
            int i = line.indexOf("] ");
            String body = i >= 0 ? line.substring(i + 2) : line;
            if (body.startsWith("SESSION: ")) {
                String s = body.substring(9);
                if (s.startsWith("logged on")) loggedOn++;
                else if (s.startsWith("logged off by Steam")) {
                    loggedOff++; lastLoggedOff = s;
                    String er = engineField(s, "eresult");
                    if ("34".equals(er) || "43".equals(er)) elsewhere = true;
                } else if (s.startsWith("disconnected")) {
                    disconnects++;
                    if (s.contains("token rejected")) tokenRejected = true;
                    if (s.contains("another client")) elsewhere = true;
                } else if (s.startsWith("engine failure")) { failures++; lastFailure = s; }
            } else if (body.startsWith("AUTH: ")) {
                String s = body.substring(6);
                if (s.startsWith("token logon failed")) { logonFail++; lastLogonFail = s; }
                else if (s.contains("sign-in OK")) signInOk = true;
                else if (s.contains("sign-in FAILED")) { signInFail = true; lastLogonFail = s; }
                else if (s.startsWith("refresh token rotated")) rotated++;
            } else if (body.startsWith("CLOUD: ")) {
                String s = body.substring(7);
                if (s.startsWith("download ")) { if (s.contains("FAILED")) cloudDlFail++; else cloudDl++; }
                else if (s.startsWith("upload batch") && s.contains("REFUSED")) cloudBatchRefused++;
                else if (s.startsWith("upload ") && !s.startsWith("upload batch")) { if (s.contains("FAILED")) cloudUlFail++; else cloudUl++; }
                else if (s.startsWith("app launch intent")) cloudLaunchIntent = s;
            } else if (body.startsWith("ACHV: ")) {
                String s = body.substring(6);
                if (s.startsWith("GetUserStats")) { if (s.contains("no reply")) achvNoReply++; else achvFetch++; }
                else if (s.startsWith("StoreUserStats")) { if ("1".equals(engineField(s, "eresult"))) achvStoreOk++; else achvStoreFail++; }
            } else if (body.startsWith("DL: ")) {
                String s = body.substring(4);
                if (s.contains(" started ")) dlStarted++;
                else if (s.startsWith("complete")) dlComplete++;
                else if (s.startsWith("FAILED")) { dlFailed++; lastDlFail = s; }
            }
        }
        // AUTH (engine)
        if (signInFail && !signInOk) f.add("AUTH (engine): an interactive sign-in FAILED — " + lastLogonFail + ".");
        else if (signInOk) f.add("AUTH (engine): interactive sign-in = OK.");
        if (tokenRejected) f.add("AUTH (engine): the saved sign-in was REJECTED by Steam — the user must sign in again"
                + (lastLogonFail != null ? " (" + lastLogonFail + ")" : "") + ".");
        else if (logonFail > 0) f.add("AUTH (engine): token logon failed " + logonFail + "x (transient) — last: " + lastLogonFail + ".");
        else if (loggedOn > 0) f.add("AUTH (engine): saved-token logon succeeded (" + loggedOn + "x this process).");
        if (rotated > 0) f.add("AUTH (engine): refresh token rotated " + rotated + "x (value never logged).");
        // SESSION (engine)
        if (elsewhere) f.add("SESSION (engine): the account was taken by ANOTHER client (LoggedInElsewhere / "
                + "LogonSessionReplaced) — expected around a SteamLite game, a conflict otherwise.");
        if (loggedOff > 0 && !elsewhere) f.add("SESSION (engine): Steam logged the app session off " + loggedOff + "x — last: " + lastLoggedOff + ".");
        if (disconnects > 1) f.add("SESSION (engine): " + disconnects + " disconnects this process (reconnect ladder engaged).");
        if (failures > 0) f.add("SESSION (engine): engine failures " + failures + "x — last: " + lastFailure + ".");
        // CLOUD (engine)
        if (cloudDl > 0 || cloudDlFail > 0) f.add("CLOUD (engine): " + cloudDl + " save file(s) downloaded"
                + (cloudDlFail > 0 ? ", " + cloudDlFail + " FAILED" : "") + ".");
        if (cloudUl > 0 || cloudUlFail > 0) f.add("CLOUD (engine): " + cloudUl + " save file(s) uploaded"
                + (cloudUlFail > 0 ? ", " + cloudUlFail + " FAILED" : "") + ".");
        if (cloudBatchRefused > 0) f.add("CLOUD (engine): Steam REFUSED to open an upload batch " + cloudBatchRefused + "x.");
        if (cloudLaunchIntent != null && cloudLaunchIntent.contains("pending ops"))
            f.add("CLOUD (engine): Steam reported PENDING cloud operations from another machine at launch (" + cloudLaunchIntent + ").");
        // ACHIEVEMENTS (engine)
        if (achvFetch > 0) f.add("ACHIEVEMENTS (engine): achievement state fetched " + achvFetch + "x from Steam.");
        if (achvNoReply > 0) f.add("ACHIEVEMENTS (engine): GetUserStats got NO reply " + achvNoReply + "x.");
        if (achvStoreOk > 0 || achvStoreFail > 0) f.add("ACHIEVEMENTS (engine): sync-back to Steam (StoreUserStats) = "
                + (achvStoreFail > 0 ? achvStoreFail + "x FAILED" + (achvStoreOk > 0 ? ", " + achvStoreOk + "x OK" : "") : "OK") + ".");
        // DOWNLOADS (engine)
        if (dlFailed > 0) f.add("CONTENT (engine): " + dlFailed + " download pass(es) FAILED — last: " + lastDlFail + ".");
        else if (dlStarted > 0) f.add("CONTENT (engine): " + dlComplete + " of " + dlStarted + " download pass(es) completed this process.");
    }

    /** Raw section: the engine's own lines (redacted at the source) + the session-status tail. */
    private static void appendEngineSection(StringBuilder out, List<String> lines, String sessionTail) {
        if (lines == null || lines.isEmpty()) return;
        out.append("\n===== steam_engine.txt — Rust engine (app-side session) =====\n");
        for (String line : lines) out.append(SteamLogRedactor.redactSteamClientLine(line)).append('\n');
        if (sessionTail != null && !sessionTail.trim().isEmpty()) {
            out.append("\n===== steam_session.txt — app session status transitions =====\n");
            for (String line : sessionTail.split("\n")) {
                if (line.trim().isEmpty()) continue;
                out.append(SteamLogRedactor.redactSteamClientLine(line)).append('\n');
            }
        }
    }

    /** Raw section: the agent-channel lines verbatim (already token-free / SteamID-masked). */
    private static void appendAgentSection(StringBuilder out, List<String> agentEvents) {
        if (agentEvents == null || agentEvents.isEmpty()) return;
        out.append("\n===== agent channel — live events (steam.exe → app) =====\n");
        for (String line : agentEvents) {
            out.append(SteamLogRedactor.redactSteamClientLine(line)).append('\n');
        }
    }

    private static void appendTrackedCount(List<String> f, String gameproc, Pattern tracked, String since) {
        if (gameproc != null) {
            int launches = countMatchesSince(gameproc, tracked, since);
            if (launches > 1)
                f.add("SECURE LAUNCH: the game was tracked " + launches + " times this session — possible "
                        + "double-launch or a launcher/arch hand-off (e.g. game.exe -> game_win64.exe).");
        }
    }

    private static void appendAuth(List<String> f, String connection, String since) {
        if (connection == null) return;
        boolean logonOk = matchesSince(connection, LOGON_OK, since);
        Hit logonFail = scanSince(connection, LOGON_FAIL, since);
        if (logonOk) f.add("AUTH: logon succeeded (OK).");
        if (logonFail.count > 0) {
            String er = firstGroup(connection, ERESULT);
            f.add("AUTH: a logon failure was logged" + (er != null ? " (EResult " + er + ")" : "")
                    + "." + when(logonFail));
        }
        if (matchesSince(connection, JWT_USED, since)) {
            String exp = firstGroup(connection, JWT_EXPIRY);
            f.add("AUTH: a JWT session token was used (value scrubbed)"
                    + (exp != null ? "; expiry " + exp : "") + ".");
        }
        Hit cmFail = scanSince(connection, CM_CONNECT_FAIL, since);
        if (cmFail.count > 0)
            f.add("AUTH: CM (Steam server) connection problems (" + cmFail.count + "x)." + when(cmFail));
    }

    /** LoggedInElsewhere & friends — "is another session clashing with this one?" as one clear line. */
    private static void appendSessionConflict(List<String> f, String connection, String since) {
        if (connection == null) return;
        String reason = null;
        String stamp = null;
        String[] lines = connection.split("\n", -1);
        for (String ln : lines) {
            if (since != null) { String ts = stampOf(ln); if (ts != null && ts.compareTo(since) < 0) continue; }
            String r = conflictReason(ln);
            if (r != null) { reason = r; stamp = stampOf(ln); break; }
        }
        if (reason != null) {
            f.add("SESSION CONFLICT: another Steam session conflict = YES — at " + (stamp == null ? "?" : stamp)
                    + ", reason " + reason + " — a second Steam login (another device/PC/session) took over; "
                    + "this explains any auth or connection drop.");
        } else {
            f.add("SESSION CONFLICT: another Steam session conflict = NO — this client held the session cleanly.");
        }
    }

    private static void appendAchievements(List<String> f, String stats, String since) {
        if (stats == null) return;
        List<String> names = new ArrayList<>();
        int unlocks = 0;
        String[] lines = stats.split("\n", -1);
        for (String ln : lines) {
            if (since != null) { String ts = stampOf(ln); if (ts != null && ts.compareTo(since) < 0) continue; }
            Matcher m = ACH_UNLOCK.matcher(ln);
            if (m.find()) {
                unlocks++;
                if (m.groupCount() >= 1 && m.group(1) != null) {
                    String name = m.group(1).trim();
                    if (!name.isEmpty() && names.size() < 20 && !names.contains(name)) names.add(name);
                }
            }
        }
        if (unlocks > 0) {
            f.add("ACHIEVEMENTS: " + unlocks + " unlocked this session"
                    + (names.isEmpty() ? "." : " (" + join(names, ", ") + ")."));
        }
        // Did the unlock actually reach Steam's servers?
        boolean storeCall = matchesSince(stats, STORE_STATS, since);
        if (storeCall) {
            boolean storeFail = matchesSince(stats, STORE_STATS_FAIL, since);
            f.add("ACHIEVEMENTS: stats synced to Steam (StoreUserStats) = " + (storeFail ? "FAILED" : "OK") + ".");
        } else if (unlocks > 0) {
            f.add("ACHIEVEMENTS: no StoreUserStats call seen this session — unlocks may not have reached Steam.");
        }
    }

    private static void appendCloud(List<String> f, String cloud, String since) {
        if (cloud == null) return;
        int down = countFiles(cloud, CLOUD_DOWNLOAD, since);
        int up = countFiles(cloud, CLOUD_UPLOAD, since);
        if (down > 0) f.add("CLOUD: " + down + " save file(s) downloaded from Steam Cloud at launch.");
        if (up > 0) f.add("CLOUD: " + up + " save file(s) uploaded to Steam Cloud.");
        if (matchesSince(cloud, CLOUD_CONFLICT, since)) {
            f.add("CLOUD: *** CONFLICT *** Steam Cloud reported a save conflict — two devices wrote the "
                    + "same save; resolve it before playing again to avoid losing progress.");
        } else if (matchesSince(cloud, CLOUD_FAIL, since)) {
            f.add("CLOUD: sync result = FAILED.");
        } else if (matchesSince(cloud, CLOUD_DISABLED, since)) {
            f.add("CLOUD: sync result = disabled for this app.");
        } else if (down > 0 || up > 0) {
            f.add("CLOUD: sync result = OK.");
        }
    }

    private static void appendTeardown(List<String> f, String text) {
        Hit esync = scan(text, ESYNC);
        Hit surface = scan(text, SURFACE_LOST);
        Hit xbroken = scan(text, X_BROKEN);
        if (esync.count == 0 && surface.count == 0 && xbroken.count == 0) return;
        StringBuilder b = new StringBuilder("TEARDOWN: session-end markers (usually just how a session closes) —");
        if (esync.count > 0) b.append(" err:esync x").append(esync.count).append(';');
        if (surface.count > 0) b.append(" VK_ERROR_SURFACE_LOST_KHR x").append(surface.count).append(';');
        if (xbroken.count > 0) b.append(" X connection broken x").append(xbroken.count).append(';');
        f.add(b.toString());
    }

    // ── Raw sections ────────────────────────────────────────────────────────────────────────────

    private static void appendRawSections(StringBuilder out, Map<String, String> redacted, String since) {
        out.append('\n');
        if (redacted.isEmpty()) {
            out.append("(No Steam client logs were present in the prefix for this run.)\n");
            return;
        }
        if (since != null)
            out.append("(Raw sections below are trimmed to this session, since ").append(since).append(".)\n");
        for (String[] pair : INCLUDED) {
            String body = redacted.get(pair[0]);
            if (body == null) continue;
            String shown = since == null ? body : filterSince(body, since);
            if (shown.trim().isEmpty()) shown = "(no lines in this session's window)\n";
            out.append("\n===== ").append(pair[0]).append(" — ").append(pair[1]).append(" =====\n");
            out.append(shown);
            if (!shown.endsWith("\n")) out.append('\n');
        }
    }

    // ── Signature patterns ──────────────────────────────────────────────────────────────────────

    private static final Pattern STAMP =
            Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\]");

    private static final Pattern FONT_HANDLES = ci("out of realized font handles");
    private static final Pattern ERR_FONT = Pattern.compile("err:font:");
    private static final Pattern CRASH =
            ci("unhandled exception|\\bc0000005\\b|EXCEPTION_ACCESS_VIOLATION|^\\s*backtrace:");
    private static final Pattern ESYNC = ci("err:esync");
    private static final Pattern SURFACE_LOST = Pattern.compile("VK_ERROR_SURFACE_LOST_KHR");
    private static final Pattern X_BROKEN = ci("X connection to .* broken|XIO:\\s*fatal IO error");

    private static final Pattern GAME_PROC_LINE = ci("\\b(game|process|launch|adding|spawn)\\b");
    private static final Pattern EXE_REF = ci("\\.exe\\b");
    private static final Pattern AGENT_EXE = ci("\\bsteam\\.exe\\b");
    /** DXVK "Game:" markers in wine_debug: the agent inits DXVK ("info:  Game: steam.exe") before the
     *  game ("info:  Game: <game>.exe"). Used for the secure-launch ORDER check (per-line matched). */
    private static final Pattern DXVK_GAME_STEAM = ci("^info:\\s+Game:\\s+steam\\.exe");
    private static final Pattern DXVK_GAME_NONSTEAM = ci("^info:\\s+Game:\\s+(?!steam\\.exe)\\S+\\.exe");

    private static final Pattern LSTEAMCLIENT_OFF = ci("lsteamclient disabled|PROTON_DISABLE_LSTEAMCLIENT");
    private static final Pattern STEAMCLIENT_FAIL =
            ci("steamclient(64)?\\.dll[^\\n]*(fail|error|not found|could not|unable)");
    private static final Pattern APPINFO_DENIED = ci("appinfo[^\\n]*(denied|no access|access denied|forbidden)");
    private static final Pattern APPINFO_OK = ci("appinfo[^\\n]*(granted|access token|update ?complete|ok)");

    private static final Pattern LOGON_OK = ci("logon (success|succeeded|ok)|logged on|logon response ok");
    private static final Pattern LOGON_FAIL = ci("logon[^\\n]*(fail|denied|error|rejected)|invalid password");
    private static final Pattern ERESULT = ci("EResult[\\s:=]*([A-Za-z0-9_]+)");
    private static final Pattern JWT_USED = ci("using jwt|jwt (accepted|valid)");
    private static final Pattern JWT_EXPIRY = ci("expir\\w*[\\s:=]*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern CM_CONNECT_OK = ci("connected to cm|cm connect(ed)?|connection established");
    private static final Pattern CM_CONNECT_FAIL = ci("connection to cm[^\\n]*(fail|lost|closed|refused)|cm connect[^\\n]*fail");

    private static final Pattern CONTENT_FAIL = ci("(download|manifest|depot)[^\\n]*(fail|error|denied|missing)");

    private static final Pattern ACH_UNLOCK = ci("unlock(?:ed)?\\s+achievement[:\\s\"']+([A-Za-z0-9_.]+)|achievement[^\\n]*unlocked");
    private static final Pattern STORE_STATS = ci("storeuserstats|store user stats|storestats");
    private static final Pattern STORE_STATS_FAIL = ci("(storeuserstats|storestats)[^\\n]*(fail|error|denied)");

    private static final Pattern CLOUD_DOWNLOAD = ci("download|pulled|fetched");
    private static final Pattern CLOUD_UPLOAD = ci("upload|pushed|synced up|committed");
    private static final Pattern CLOUD_FILE_COUNT = ci("(\\d+)\\s+file");
    private static final Pattern CLOUD_CONFLICT = ci("conflict");
    private static final Pattern CLOUD_FAIL = ci("cloud[^\\n]*(fail|error)");
    private static final Pattern CLOUD_DISABLED = ci("cloud[^\\n]*(disabled|not enabled|off)");

    /** One Steam session-conflict signal: a pattern and the plain reason it stands for. */
    private static final class Conflict {
        final Pattern p; final String reason;
        Conflict(Pattern p, String reason) { this.p = p; this.reason = reason; }
    }

    // Tested individually (not one big alternation) so the finding can NAME which one matched.
    private static final Conflict[] CONFLICTS = {
            new Conflict(ci("LoggedInElsewhere"),                       "LoggedInElsewhere"),
            new Conflict(ci("LogonSessionReplaced"),                    "LogonSessionReplaced"),
            new Conflict(ci("account is (currently )?in use"),          "account in use"),
            new Conflict(ci("another (client|session|computer|device)[^\\n]*(logged on|logged in|connected)"),
                    "another session logged on"),
            new Conflict(ci("logged on with this account"),             "another session logged on"),
            new Conflict(ci("logged in from another"),                  "logged in elsewhere"),
            new Conflict(ci("(RecvMsg)?ClientLoggedOff"),               "ClientLoggedOff"),
    };

    // ── Scan primitives ─────────────────────────────────────────────────────────────────────────

    private static final class Hit { int count; String first; String last; }

    private static Hit scan(String text, Pattern p) {
        Hit h = new Hit();
        if (text == null) return h;
        for (String line : text.split("\n", -1)) {
            if (p.matcher(line).find()) {
                h.count++;
                String ts = stampOf(line);
                if (h.first == null) h.first = ts;
                h.last = ts;
            }
        }
        return h;
    }

    private static boolean matches(String text, Pattern p) {
        return text != null && p.matcher(text).find();
    }

    /** Like {@link #scan} but only counts lines timestamped at/after {@code since} (this session).
     *  A line with no timestamp is always considered (multi-line continuation). since==null == scan. */
    private static Hit scanSince(String text, Pattern p, String since) {
        if (since == null) return scan(text, p);
        Hit h = new Hit();
        if (text == null) return h;
        for (String line : text.split("\n", -1)) {
            String ts = stampOf(line);
            if (ts != null && ts.compareTo(since) < 0) continue;
            if (p.matcher(line).find()) {
                h.count++;
                if (h.first == null) h.first = ts;
                h.last = ts;
            }
        }
        return h;
    }

    private static boolean matchesSince(String text, Pattern p, String since) {
        return scanSince(text, p, since).count > 0;
    }

    private static int countMatchesSince(String text, Pattern p, String since) {
        return scanSince(text, p, since).count;
    }

    /** Index of the first line matching {@code p}, or -1. */
    private static int firstLineIndex(String text, Pattern p) {
        if (text == null) return -1;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) if (p.matcher(lines[i]).find()) return i;
        return -1;
    }

    /** This session's start = timestamp of the LAST "AppID <appId> ... adding PID" in gameprocess_log
     *  (when the game launched this run), or null if not determinable. Timestamps are
     *  "YYYY-MM-DD HH:MM:SS", so a lexical compare is chronological. */
    private static String sessionStart(String gameproc, int appId) {
        if (gameproc == null) return null;
        Pattern p = ci("appid\\s+" + appId + "\\b[^\\n]*adding\\s+pid");
        String anchor = null;
        for (String line : gameproc.split("\n", -1)) {
            if (p.matcher(line).find()) { String ts = stampOf(line); if (ts != null) anchor = ts; }
        }
        return anchor;
    }

    /** Keep only this-session lines of a raw block: a line at/after {@code since}, plus any following
     *  un-timestamped continuation lines so multi-line records aren't cut mid-entry. */
    private static String filterSince(String text, String since) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        boolean including = false;
        for (String line : text.split("\n", -1)) {
            String ts = stampOf(line);
            if (ts != null) including = ts.compareTo(since) >= 0;
            if (including) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** " [first -> last]" / " [stamp]" for a hit, or "" when no timestamps were found. */
    private static String when(Hit h) {
        if (h == null || h.count == 0 || h.first == null) return "";
        if (h.first.equals(h.last) || h.last == null) return " [" + h.first + "]";
        return " [" + h.first + " -> " + h.last + "]";
    }

    private static String stampOf(String line) {
        if (line == null) return null;
        Matcher m = STAMP.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static String conflictReason(String line) {
        for (Conflict c : CONFLICTS) {
            if (c.p.matcher(line).find()) return c.reason;
        }
        return null;
    }

    /** Count save files a cloud line touches: an explicit "<n> files" if present, else 1 per line.
     *  Bounded to this session when {@code since} is set. */
    private static int countFiles(String text, Pattern direction, String since) {
        if (text == null) return 0;
        int total = 0;
        for (String line : text.split("\n", -1)) {
            if (since != null) { String ts = stampOf(line); if (ts != null && ts.compareTo(since) < 0) continue; }
            if (!direction.matcher(line).find()) continue;
            Matcher m = CLOUD_FILE_COUNT.matcher(line);
            if (m.find()) {
                try { total += Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { total += 1; }
            } else {
                total += 1;
            }
        }
        return total;
    }

    // ── IO + text helpers ───────────────────────────────────────────────────────────────────────

    /** The version string from whichever SteamLite marker exists in the Steam dir, or null. */
    private static String readMarkerVersion(File steamDir) {
        for (String name : MARKERS) {
            File m = new File(steamDir, name);
            if (!m.isFile()) continue;
            if (name.equals("steam.exe")) return "installed";   // agent present but no version file
            String v = readTail(m, 4096);
            if (v != null) { v = v.trim(); if (!v.isEmpty()) return v; }
            return "installed";
        }
        return null;
    }

    /** Last {@code maxBytes} of a file as text (whole file if smaller), or null. Bounded so a huge
     *  {@code +seh} wine_debug.log can't OOM the exit path; a partial first line is dropped. */
    private static String readTail(File f, long maxBytes) {
        if (f == null || !f.isFile()) return null;
        try {
            long len = f.length();
            long from = Math.max(0, len - maxBytes);
            int count = (int) Math.min(len, maxBytes);
            byte[] bytes = new byte[Math.max(count, 0)];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(from);
                raf.readFully(bytes);
            }
            String s = new String(bytes);
            if (from > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "read failed: " + f, t);
            return null;
        }
    }

    /** The first DXVK/VKD3D log in the folder, HEAD only — the "Device :"/"Driver :" lines we read
     *  for the GPU/driver summary are written at startup, so the head is where they live. */
    private static String readFirstDxvk(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && (n.equals("vkd3d-proton.log") || n.endsWith("_dxgi.log")
                    || n.matches(".*_d3d\\d+\\.log")))
                return readHead(f, DX_SCAN_TAIL_BYTES);
        }
        return null;
    }

    /** First {@code maxBytes} of a file as text (whole file if smaller), or null. A partial last line
     *  is dropped so a truncated read never ends mid-line. */
    private static String readHead(File f, long maxBytes) {
        if (f == null || !f.isFile()) return null;
        try {
            int count = (int) Math.min(f.length(), maxBytes);
            byte[] bytes = new byte[Math.max(count, 0)];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.readFully(bytes);
            }
            String s = new String(bytes);
            if (f.length() > maxBytes) {
                int nl = s.lastIndexOf('\n');
                if (nl >= 0) s = s.substring(0, nl + 1);
            }
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "read failed: " + f, t);
            return null;
        }
    }

    /** Redact a whole block line-by-line (bounds a redaction failure to its own line). */
    private static String redactBlock(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 64);
        for (String line : text.split("\n", -1)) {
            sb.append(SteamLogRedactor.redactSteamClientLine(line)).append('\n');
        }
        return sb.toString();
    }

    /** Group 1 of the first match of a regex STRING (carries its own {@code (?m)} where needed). */
    private static String firstMatch(String haystack, String regex) {
        if (haystack == null) return null;
        try {
            return firstGroup(haystack, Pattern.compile(regex));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Group 1 of the first match of a compiled pattern, trimmed and length-capped. */
    private static String firstGroup(String haystack, Pattern p) {
        if (haystack == null) return null;
        Matcher m = p.matcher(haystack);
        if (m.find() && m.groupCount() >= 1 && m.group(1) != null) {
            String s = m.group(1).trim();
            return s.length() > 80 ? s.substring(0, 80) : s;
        }
        return null;
    }

    private static Pattern ci(String regex) { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE); }

    private static String cat(String a, String b) {
        return (a == null ? "" : a) + "\n" + (b == null ? "" : b);
    }

    private static String join(Iterable<String> parts) { return join(parts, "\n"); }

    private static String join(Iterable<String> parts, String sep) {
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (String p : parts) {
            if (p == null) continue;
            if (!first) b.append(sep);
            b.append(p);
            first = false;
        }
        return b.toString();
    }

    private static String yn(boolean b) { return b ? "YES" : "NO"; }
    private static String safe(String s) { return (s == null || s.trim().isEmpty()) ? "—" : s.trim(); }
    private static String orDash(String s) { return (s == null || s.trim().isEmpty()) ? "—" : s.trim(); }
}
