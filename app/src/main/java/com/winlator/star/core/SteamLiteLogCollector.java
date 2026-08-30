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

            StringBuilder out = new StringBuilder(8 * 1024);
            appendSummary(out, context, gameName, appId, info, steamLiteVersion, dxText);
            appendDiagnostics(out, wineText, dxText, steamRaw);
            appendRawSections(out, steamRedacted);

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
    private static void appendDiagnostics(StringBuilder out, String wineText, String dxText,
                                          Map<String, String> steam) {
        out.append("\n===== DIAGNOSTICS (auto-scan) =====\n");
        List<String> f = new ArrayList<>();

        String connection = steam.get("connection_log.txt");
        String gameproc = steam.get("gameprocess_log.txt");
        String content = steam.get("content_log.txt");
        String cloud = steam.get("cloud_log.txt");
        String stats = steam.get("stats_log.txt");
        // A single haystack for signatures that could land in any Steam log.
        String allSteam = join(steam.values());

        // ── FONT / GDI (a known recurring freeze — kept prominent) ──
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

        // ── SteamLite agent / secure-launch health ──
        appendSecureLaunch(f, gameproc);
        boolean lsteam = matches(wineText, LSTEAMCLIENT_OFF) || matches(allSteam, LSTEAMCLIENT_OFF);
        f.add("CLIENT: Real Steam client mode (lsteamclient disabled — expected for SteamLite) = "
                + yn(lsteam) + ".");
        if (matches(allSteam, STEAMCLIENT_FAIL) || matches(wineText, STEAMCLIENT_FAIL))
            f.add("CLIENT: steamclient64.dll FAILED to load — the real Steam client may not have initialised.");
        Hit appinfoDenied = scan(allSteam, APPINFO_DENIED);
        Hit appinfoOk = scan(allSteam, APPINFO_OK);
        if (appinfoDenied.count > 0)
            f.add("APPINFO: app-info access tokens DENIED — a strict live-service title; the VAC/online "
                    + "path may not work (Brawlhalla-style)." + when(appinfoDenied));
        else if (appinfoOk.count > 0)
            f.add("APPINFO: app-info access tokens granted." + when(appinfoOk));

        // ── AUTH (connection_log) ──
        appendAuth(f, connection);

        // ── Steam session conflict (connection_log) — a distinct, obvious line ──
        appendSessionConflict(f, connection);

        // ── CONTENT (content_log) ──
        Hit dlFail = scan(content, CONTENT_FAIL);
        if (dlFail.count > 0)
            f.add("CONTENT: download/manifest failures (" + dlFail.count + "x)." + when(dlFail));

        // ── ACHIEVEMENTS (stats_log) ──
        appendAchievements(f, stats);

        // ── CLOUD (cloud_log) — both directions ──
        appendCloud(f, cloud);

        // ── CRASH / teardown ──
        Hit crash = scan(cat(wineText, allSteam), CRASH);
        if (crash.count > 0)
            f.add("CRASH: native crash detected (" + crash.count + "x — unhandled exception / access "
                    + "violation)." + when(crash));
        appendTeardown(f, cat(wineText, allSteam));

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

    private static void appendSecureLaunch(List<String> f, String gameproc) {
        if (gameproc == null) return;
        int steamIdx = -1, gameIdx = -1, gameCount = 0;
        String[] lines = gameproc.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            boolean isGameLine = GAME_PROC_LINE.matcher(ln).find() && EXE_REF.matcher(ln).find();
            if (!isGameLine) continue;
            boolean isAgent = AGENT_EXE.matcher(ln).find();
            if (isAgent) { if (steamIdx < 0) steamIdx = i; }
            else { if (gameIdx < 0) gameIdx = i; gameCount++; }
        }
        if (steamIdx >= 0 && gameIdx >= 0) {
            boolean secure = steamIdx < gameIdx;
            f.add("SECURE LAUNCH: steam.exe agent started " + (secure ? "BEFORE" : "AFTER")
                    + " the game = " + yn(secure)
                    + (secure ? " (secure)." : " (INSECURE — VAC may kick this session)."));
        } else if (steamIdx >= 0) {
            f.add("SECURE LAUNCH: steam.exe agent started; the game process was not seen in "
                    + "gameprocess_log this run.");
        } else if (gameIdx >= 0) {
            f.add("SECURE LAUNCH: the game process started but the steam.exe agent was not seen first "
                    + "= NO (INSECURE — VAC may kick this session).");
        }
        if (gameCount > 1)
            f.add("SECURE LAUNCH: the game process launched " + gameCount + " times — possible "
                    + "double-launch or a launcher/arch hand-off (e.g. game.exe -> game_win64.exe).");
    }

    private static void appendAuth(List<String> f, String connection) {
        if (connection == null) return;
        boolean logonOk = matches(connection, LOGON_OK);
        Hit logonFail = scan(connection, LOGON_FAIL);
        if (logonOk) f.add("AUTH: logon succeeded (OK).");
        if (logonFail.count > 0) {
            String er = firstGroup(connection, ERESULT);
            f.add("AUTH: a logon failure was logged" + (er != null ? " (EResult " + er + ")" : "")
                    + "." + when(logonFail));
        }
        if (matches(connection, JWT_USED)) {
            String exp = firstGroup(connection, JWT_EXPIRY);
            f.add("AUTH: a JWT session token was used (value scrubbed)"
                    + (exp != null ? "; expiry " + exp : "") + ".");
        }
        Hit cmOk = scan(connection, CM_CONNECT_OK);
        Hit cmFail = scan(connection, CM_CONNECT_FAIL);
        if (cmFail.count > 0)
            f.add("AUTH: CM (Steam server) connection problems (" + cmFail.count + "x)." + when(cmFail));
        else if (cmOk.count > 0)
            f.add("AUTH: connected to a Steam CM server." + when(cmOk));
    }

    /** LoggedInElsewhere & friends — "is another session clashing with this one?" as one clear line. */
    private static void appendSessionConflict(List<String> f, String connection) {
        if (connection == null) return;
        String reason = null;
        String stamp = null;
        String[] lines = connection.split("\n", -1);
        for (String ln : lines) {
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

    private static void appendAchievements(List<String> f, String stats) {
        if (stats == null) return;
        List<String> names = new ArrayList<>();
        int unlocks = 0;
        String[] lines = stats.split("\n", -1);
        for (String ln : lines) {
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
        boolean storeCall = matches(stats, STORE_STATS);
        if (storeCall) {
            boolean storeFail = matches(stats, STORE_STATS_FAIL);
            f.add("ACHIEVEMENTS: stats synced to Steam (StoreUserStats) = " + (storeFail ? "FAILED" : "OK") + ".");
        } else if (unlocks > 0) {
            f.add("ACHIEVEMENTS: no StoreUserStats call seen this session — unlocks may not have reached Steam.");
        }
    }

    private static void appendCloud(List<String> f, String cloud) {
        if (cloud == null) return;
        int down = countFiles(cloud, CLOUD_DOWNLOAD);
        int up = countFiles(cloud, CLOUD_UPLOAD);
        if (down > 0) f.add("CLOUD: " + down + " save file(s) downloaded from Steam Cloud at launch.");
        if (up > 0) f.add("CLOUD: " + up + " save file(s) uploaded to Steam Cloud.");
        if (matches(cloud, CLOUD_CONFLICT)) {
            f.add("CLOUD: *** CONFLICT *** Steam Cloud reported a save conflict — two devices wrote the "
                    + "same save; resolve it before playing again to avoid losing progress.");
        } else if (matches(cloud, CLOUD_FAIL)) {
            f.add("CLOUD: sync result = FAILED.");
        } else if (matches(cloud, CLOUD_DISABLED)) {
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

    private static void appendRawSections(StringBuilder out, Map<String, String> redacted) {
        out.append('\n');
        if (redacted.isEmpty()) {
            out.append("(No Steam client logs were present in the prefix for this run.)\n");
            return;
        }
        for (String[] pair : INCLUDED) {
            String body = redacted.get(pair[0]);
            if (body == null) continue;
            out.append("\n===== ").append(pair[0]).append(" — ").append(pair[1]).append(" =====\n");
            out.append(body);
            if (!body.endsWith("\n")) out.append('\n');
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

    /** Count save files a cloud line touches: an explicit "<n> files" if present, else 1 per line. */
    private static int countFiles(String text, Pattern direction) {
        if (text == null) return 0;
        int total = 0;
        for (String line : text.split("\n", -1)) {
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
