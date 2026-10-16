package com.winlator.star.core;

import android.content.Context;
import android.util.Log;

import com.winlator.star.store.SteamLogRedactor;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Headless Steam counterpart of {@link SteamLiteLogCollector}: one {@code headless_steam.txt}
 * per launch, next to the game's other logs, written on exit when the "Headless Steam" log toggle
 * is on and the launch actually went through the app's own Steam session host.
 *
 * It opens with a CHECKLIST — every step of a Headless Steam launch, in order, each marked
 * PASS / FAIL / WARN / SKIP from what the app, the host and the game-side bridge actually
 * reported — so a reader sees at a glance where a launch stopped, then the evidence in the
 * same order: the host's live events, the host's own log, Valve's client logs for that session,
 * the bridge lines from the game process, and the app-side Rust engine log.
 *
 * Same redaction as the SteamLite bundle: account name and tokens are scrubbed, Steam IDs masked.
 */
public final class HeadlessSteamLogCollector {
    private static final String TAG = "HeadlessSteamLogs";

    /** Output file name inside the per-game log dir (see {@link LogRotation}). */
    public static final String OUTPUT_NAME = "headless_steam.txt";

    private HeadlessSteamLogCollector() {}

    /** What the launch activity knew about the plan it armed. All fields optional/nullable. */
    public static final class Launch {
        public final boolean planArmed;
        public final int appId;
        public final String valveBuild;          // SteamHostComponent.installedVersion()
        public final boolean valveBuildVerified; // accepted: built in, build catalog, or on-device self-check
        public final Boolean layerHasLsteamclient;
        public final List<String> envKeys;       // guest env keys the plan added (names only)
        public final List<String> envRemoved;    // keys the plan strips (PROTON_DISABLE_LSTEAMCLIENT …)
        public final boolean hostReady;          // the activity saw host_ready
        public final String fallbackReason;      // why the plan was not armed, if known

        public Launch(boolean planArmed, int appId, String valveBuild, boolean valveBuildVerified,
                      Boolean layerHasLsteamclient, List<String> envKeys, List<String> envRemoved,
                      boolean hostReady, String fallbackReason) {
            this.planArmed = planArmed;
            this.appId = appId;
            this.valveBuild = valveBuild;
            this.valveBuildVerified = valveBuildVerified;
            this.layerHasLsteamclient = layerHasLsteamclient;
            this.envKeys = envKeys;
            this.envRemoved = envRemoved;
            this.hostReady = hostReady;
            this.fallbackReason = fallbackReason;
        }
    }

    /**
     * Collect this run's Headless Steam evidence into {@code perGameLogDir/headless_steam.txt}.
     *
     * @param hostLog     the host's log ({@code bl-steam-host} stdout); its parent is the per-user
     *                    state dir, so Valve's own logs are at {@code <parent>/home/Steam/logs}
     * @param agentEvents the live agent/host channel lines ({@code {"ev":…}} JSON), may be null
     * @param logcatText  the app's logcat (already captured), may be null
     */
    public static void collect(Context context, File perGameLogDir, String gameName,
                               SteamLiteLogCollector.Info info, Launch launch, File hostLog,
                               List<String> agentEvents, String logcatText) {
        try {
            if (perGameLogDir == null) return;
            try {
                android.content.SharedPreferences sp =
                        context.getSharedPreferences("steam_prefs", Context.MODE_PRIVATE);
                SteamLogRedactor.registerSecret(sp.getString("username", ""));
                SteamLogRedactor.registerSecret(sp.getString("refresh_token", ""));
                // …and every OTHER saved account's name and token, so they can't end up in a shared log either.
                com.winlator.star.store.SteamAccountStore.INSTANCE.registerSecrets(context);
            } catch (Throwable ignored) {}

            int appId = launch != null ? launch.appId : 0;
            String hostText = hostLog != null ? SteamLiteLogCollector.readTail(hostLog, SteamLiteLogCollector.LAUNCHER_LOG_TAIL_BYTES) : null;

            // Valve's client logs for the HOST session (not the prefix: no in-container client ran).
            Map<String, String> steamRedacted = new LinkedHashMap<>();
            Map<String, String> steamRaw = new LinkedHashMap<>();
            File hostLogsDir = hostLog != null ? new File(hostLog.getParentFile(), "home/Steam/logs") : null;
            if (hostLogsDir != null && hostLogsDir.isDirectory()) {
                try {
                    LogRedactor.INSTANCE.learnAccounts(new File(hostLog.getParentFile(), "home/Steam/config/loginusers.vdf"));
                    LogRedactor.INSTANCE.learnOwnAddresses(context);
                } catch (Throwable ignored) {}
                for (String[] pair : SteamLiteLogCollector.INCLUDED) {
                    File f = new File(hostLogsDir, pair[0]);
                    if (!f.isFile()) continue;
                    String raw = SteamLiteLogCollector.readTail(f, SteamLiteLogCollector.STEAM_LOG_TAIL_BYTES);
                    if (raw == null) continue;
                    steamRaw.put(pair[0], raw);
                    steamRedacted.put(pair[0], SteamLiteLogCollector.redactBlock(raw));
                }
            }
            String since = SteamLiteLogCollector.sessionStart(steamRaw.get("gameprocess_log.txt"), appId);

            // Live capture first (started when the plan armed: survives the 256 KiB logcat ring that a
            // long session overruns), then the ring snapshot, then the app-pid capture.
            List<String> bridgeLines = bridgeLines(readLiveCapture(context));
            for (String l : bridgeLines(captureBridgeLogcat())) if (!bridgeLines.contains(l)) bridgeLines.add(l);
            if (bridgeLines.isEmpty()) bridgeLines = bridgeLines(logcatText);
            String wineText = SteamLiteLogCollector.readTail(new File(perGameLogDir, "wine_debug.log"), 1L * 1024 * 1024);
            List<String> engineLines = SteamLiteLogCollector.engineLines(context);
            String sessionTail = engineLines.isEmpty() ? null : SteamLiteLogCollector.readEngineSessionTail(context);

            StringBuilder out = new StringBuilder(8 * 1024);
            SteamLiteLogCollector.appendSummary(out, context, gameName, appId, info, null, null);
            replaceFirst(out, "===== SteamLite (Steam client) log =====", "===== Headless Steam (app session host) log =====");
            out.append("Launch mode: Headless Steam — the game ran on the app's own Steam session "
                    + "(bl-steam-host + Valve androidarm64 libsteamclient.so, reached through the Proton "
                    + "layer's lsteamclient bridge; no in-container Steam client)\n");
            if (!engineLines.isEmpty()) out.append("Steam engine: Rust (libblsteam.so) — app-side session log included\n");

            appendChecklist(out, launch, hostText, agentEvents, steamRaw, bridgeLines, since, wineText);
            appendLaunchPlan(out, launch);
            appendHostEvents(out, agentEvents);
            appendHostLog(out, hostText);
            if (!steamRedacted.isEmpty()) {
                out.append("\n===== VALVE CLIENT LOGS — the host session's own files (home/Steam/logs) =====\n");
                SteamLiteLogCollector.appendRawSections(out, steamRedacted, since);
            } else {
                out.append("\n===== VALVE CLIENT LOGS =====\n(none — the host did not get far enough to write any)\n");
            }
            appendBridgeSection(out, bridgeLines);
            SteamLiteLogCollector.appendEngineSection(out, engineLines, sessionTail);

            String finished = SteamLogRedactor.auditSteamClientText(out.toString());
            File target = new File(perGameLogDir, OUTPUT_NAME);
            if (FileUtils.writeString(target, finished)) {
                Log.i(TAG, "wrote " + target.getAbsolutePath() + " (" + steamRedacted.size() + " Valve logs, "
                        + bridgeLines.size() + " bridge lines)");
            }
        } catch (Throwable t) {
            // Never let log collection break a game exiting.
            Log.w(TAG, "collect failed", t);
        }
    }

    // ── Checklist ───────────────────────────────────────────────────────────────────────────────

    private static final Pattern EV = Pattern.compile("\"ev\"\\s*:\\s*\"([A-Za-z_]+)\"");
    private static final Pattern EV_REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EV_OK = Pattern.compile("\"ok\"\\s*:\\s*(true|false)");
    private static final Pattern HOST_PID = Pattern.compile("bl-steam-host starting \\(pid (\\d+)\\)");
    private static final Pattern SLOT_OK = Pattern.compile("Valve build (\\d+): (?:slot table verified|slots from ([^\\r\\n]+))");
    private static final Pattern SLOT_FALLBACK = Pattern.compile("(?i)slot table.*(fallback|not verified|unknown build)|UNVERIFIED override");
    private static final Pattern SELF_CHECK = Pattern.compile("self-check: (PASS|FAIL) in (\\d+) ms(?: — ([^\\r\\n]+))?");
    private static final Pattern SELF_CHECK_REFUSED = Pattern.compile("failed the safety self-check: ([^\"\\r\\n]+)|does not match its verified layout[^\"\\r\\n]*|disagree with the self-check[^\"\\r\\n]*");
    private static final Pattern IDENTITY_MISMATCH = Pattern.compile("identity: (\\S+) vptr base\\+0x[0-9a-f]+, analysis expected");
    private static final Pattern PRELOAD_FAIL = Pattern.compile("preload FAIL: (\\S+) — (.*)");
    private static final Pattern PRELOAD_OK = Pattern.compile("preload OK: (\\S+)");
    private static final Pattern PORTS_FREE = Pattern.compile("loopback ports free: (\\d+) (\\d+)");
    private static final Pattern PORT_BUSY = Pattern.compile("(?i)port.*(busy|in use)");
    private static final Pattern DLOPEN_OK = Pattern.compile("dlopen\\(libsteamclient\\.so\\) OK");
    private static final Pattern DLOPEN_FAIL = Pattern.compile("dlopen failed: (.*)");
    private static final Pattern LOGGED_ON = Pattern.compile("LOGGED ON after (\\d+) ms");
    private static final Pattern LOGON_FAILED = Pattern.compile("logon did not complete \\(eresult=(\\d+), (\\d+) ms\\)");
    private static final Pattern VTABLE_FAIL = Pattern.compile("(?i)vtable slot \\d+ .*(null|not inside|resolves into)");
    private static final Pattern HOST_FAIL_LINE = Pattern.compile("(?i)host_failed|FATAL|fail\\(");
    private static final Pattern GAME_TRACKED = Pattern.compile("(?i)InternalUpdateClientGame|AppID (\\d+) adding PID|games list");
    private static final Pattern BRIDGE_LOADED = Pattern.compile("lsteamclient: init: loaded host steamclient");
    private static final Pattern BRIDGE_INIT = Pattern.compile("lsteamclient: init: dlopen");
    private static final Pattern BRIDGE_FAIL = Pattern.compile("(?i)lsteamclient:.*(fail|error|cannot|unable)");
    private static final Pattern RECONNECT_LOOP = Pattern.compile("(?i)ONLINE -> CONNECTING|presence: in game \\d+ \\(reconnect\\)");
    private static final Pattern SERVICE_START = Pattern.compile("SteamService_StartThread\\(\"([^\"]*)\"\\)");
    private static final Pattern SERVICE_PIPE_FAIL = Pattern.compile("invalid name/address:port string provided to BSetIpPortFromName");

    private static void appendChecklist(StringBuilder out, Launch launch, String hostText,
                                        List<String> events, Map<String, String> steamRaw,
                                        List<String> bridgeLines, String since, String wineText) {
        out.append("\n===== CHECKLIST — every step of a Headless Steam launch, in order =====\n");
        List<String> rows = new ArrayList<>();
        String host = hostText != null ? hostText : "";
        List<String> evs = new ArrayList<>();
        Map<String, String> evLine = new LinkedHashMap<>();
        if (events != null) {
            for (String line : events) {
                Matcher m = EV.matcher(line);
                if (m.find()) { evs.add(m.group(1)); if (!evLine.containsKey(m.group(1))) evLine.put(m.group(1), line); }
            }
        }

        // 1. Plan
        if (launch == null || !launch.planArmed) {
            rows.add(fail("PLAN", "Headless Steam was requested but the launch plan was NOT armed"
                    + (launch != null && launch.fallbackReason != null ? " — " + launch.fallbackReason : "")
                    + ". The game ran as a plain launch (no Steam session)."));
            flush(out, rows);
            return;
        }
        rows.add(pass("PLAN", "launch plan armed for appId " + launch.appId
                + " (" + (launch.envKeys != null ? launch.envKeys.size() : 0) + " guest env keys; removed: "
                + (launch.envRemoved != null ? String.join(", ", launch.envRemoved) : "—") + ")"));

        // 2. Valve client set
        if (launch.valveBuild == null || launch.valveBuild.isEmpty())
            rows.add(fail("VALVE CLIENT", "no Valve Android client set installed (steam-host dir empty)"));
        else
            rows.add((launch.valveBuildVerified ? pass("VALVE CLIENT", "build " + launch.valveBuild + " installed and accepted (built in, build catalog, or on-device self-check)")
                    : warn("VALVE CLIENT", "build " + launch.valveBuild + " installed but not accepted (not built in, not in the build catalog, no on-device self-check record)")));

        // 3. Layer bridge
        if (launch.layerHasLsteamclient == null) rows.add(warn("LAYER BRIDGE", "lsteamclient presence in the Proton layer was not checked"));
        else if (launch.layerHasLsteamclient) rows.add(pass("LAYER BRIDGE", "lsteamclient.dll + lsteamclient.so present in the Proton layer (WINE_LSTEAMCLIENT=1 set)"));
        else rows.add(fail("LAYER BRIDGE", "the Proton layer has NO lsteamclient — the game cannot reach the host (needs a v9+ arm64ec layer)"));

        // 4. Host process
        Matcher pid = HOST_PID.matcher(host);
        if (pid.find()) rows.add(pass("HOST START", "bl-steam-host started (pid " + pid.group(1) + ")"));
        else if (evs.contains("started")) rows.add(pass("HOST START", "host reported 'started' on the status channel"));
        else rows.add(fail("HOST START", "the host never started (no host log, no 'started' event)"));

        // 5. Slot table
        Matcher sc = SELF_CHECK.matcher(host);
        Matcher refused = SELF_CHECK_REFUSED.matcher(host);
        if (refused.find()) rows.add(fail("SELF-CHECK", "the host refused this Valve build: " + clip(refused.group())));
        else if (sc.find()) rows.add("PASS".equals(sc.group(1))
                ? pass("SELF-CHECK", "every private slot found by structure and every code fingerprint matched (" + sc.group(2) + " ms)")
                : warn("SELF-CHECK", "the structural check could not confirm this build (" + clip(sc.group(3)) + ") — pinned table used"));
        Matcher idm = IDENTITY_MISMATCH.matcher(host);
        if (idm.find()) rows.add(warn("SELF-CHECK", idm.group(1) + " is not the class the analysis found (see HOST LOG 'identity:')"));
        Matcher slot = SLOT_OK.matcher(host);
        if (SLOT_FALLBACK.matcher(host).find()) rows.add(warn("SLOT TABLE", "host drove a build the self-check could not confirm (developer override)"));
        else if (slot.find()) rows.add(pass("SLOT TABLE", "Valve build " + slot.group(1) + (slot.group(2) != null ? ": slots from " + slot.group(2) : " verified")));
        else if (pid.find(0)) rows.add(warn("SLOT TABLE", "no slot-table line in the host log"));

        // 6. Ports
        Matcher ports = PORTS_FREE.matcher(host);
        if (ports.find()) rows.add(pass("LOOPBACK PORTS", ports.group(1) + " (Steam3Master) and " + ports.group(2) + " (SteamClientService) were free"));
        else if (evs.contains("port_busy") || PORT_BUSY.matcher(host).find()) rows.add(fail("LOOPBACK PORTS", "57343/57344 busy — another Steam host or a leftover process holds them"));

        // 7. Preloads
        List<String> preloadFails = new ArrayList<>();
        Matcher pf = PRELOAD_FAIL.matcher(host);
        while (pf.find()) preloadFails.add(pf.group(1) + " — " + clip(pf.group(2)));
        int preloadOk = 0;
        Matcher po = PRELOAD_OK.matcher(host);
        while (po.find()) preloadOk++;
        if (!preloadFails.isEmpty())
            rows.add(fail("PRELOADS", preloadFails.size() + " of Valve's helper libraries failed to load: " + preloadFails.get(0)
                    + (preloadFails.size() > 1 ? " (+" + (preloadFails.size() - 1) + " more)" : "")
                    + ". A 'cannot locate symbol' here means a library search-path clash, not a Valve bug."));
        else if (preloadOk > 0) rows.add(pass("PRELOADS", preloadOk + " helper libraries loaded"));

        // 8. libsteamclient
        if (DLOPEN_OK.matcher(host).find()) rows.add(pass("STEAMCLIENT", "libsteamclient.so loaded in the host"));
        else {
            Matcher df = DLOPEN_FAIL.matcher(host);
            if (df.find() && preloadFails.isEmpty()) rows.add(fail("STEAMCLIENT", "libsteamclient.so failed to load: " + clip(df.group(1))));
            else if (pid.find(0)) rows.add(fail("STEAMCLIENT", "the host never reached dlopen(libsteamclient.so)"));
        }
        if (VTABLE_FAIL.matcher(host).find()) rows.add(fail("VTABLE", "a vtable slot did not resolve into libsteamclient.so — the slot table does not match this Valve build"));

        // 8b. Valve's client service (steamservice.so): must LISTEN on SteamClientService (:57344).
        //     Logon/cloud/stats bypass it; the in-game server browser (and anything else the client
        //     routes through the service) hangs when it never bound.
        Matcher svc = SERVICE_START.matcher(host);
        int pipeFails = 0;
        Matcher pf2 = SERVICE_PIPE_FAIL.matcher(host);
        while (pf2.find()) pipeFails++;
        if (pipeFails > 0)
            rows.add(fail("CLIENT SERVICE", "Valve's client service never bound its endpoint (" + pipeFails
                    + "x 'invalid name/address:port string provided to BSetIpPortFromName') — calls routed through "
                    + "the service, the in-game server browser first, hang. The host must start it on ip:port."));
        else if (svc.find())
            rows.add(svc.group(1).matches("SteamClientService_\\d+")
                    ? pass("CLIENT SERVICE", "steamservice registered in-process as " + svc.group(1) + " (the name the client looks up)")
                    : warn("CLIENT SERVICE", "steamservice started as '" + svc.group(1) + "' — the client looks up SteamClientService_<pid>"));
        else if (pid.find(0)) rows.add(warn("CLIENT SERVICE", "no SteamService_StartThread line in the host log"));

        // 9. Logon
        Matcher lo = LOGGED_ON.matcher(host);
        Matcher lf = LOGON_FAILED.matcher(host);
        if (lo.find()) rows.add(pass("LOGON", "logged on after " + lo.group(1) + " ms"));
        else if (lf.find()) rows.add(fail("LOGON", "logon did not complete (EResult " + lf.group(1) + ", " + lf.group(2) + " ms)"));
        else if (evs.contains("logged_in")) rows.add(pass("LOGON", "host reported 'logged_in'"));
        else if (evs.contains("login_failed")) rows.add(fail("LOGON", "login_failed" + reason(evLine.get("login_failed"))));
        else rows.add(fail("LOGON", "no logon result — the host stopped before logging on"));

        // 10. App-info + ownership
        if (evs.contains("appinfo")) rows.add(pass("APP INFO", "app-info update completed"));
        if (evs.contains("ownership")) {
            String l = evLine.get("ownership");
            Matcher ok = EV_OK.matcher(l != null ? l : "");
            boolean good = ok.find() && "true".equals(ok.group(1));
            rows.add(good ? pass("OWNERSHIP", "app ownership ticket for appId " + launch.appId + " ready")
                          : warn("OWNERSHIP", "ownership ticket not confirmed (the game may still run; DRM checks can fail)"));
        }

        // 11. host_ready
        if (launch.hostReady || evs.contains("host_ready")) rows.add(pass("HOST READY", "host_ready — the game was released to start"));
        else if (evs.contains("host_failed")) rows.add(fail("HOST READY", "host_failed" + reason(evLine.get("host_failed"))));
        else rows.add(fail("HOST READY", "host_ready never arrived (the launch timed out or fell back)"));

        // 12. Bridge in the game process
        boolean bridgeLoaded = false, bridgeInit = false, bridgeFail = false;
        for (String l : bridgeLines) {
            if (BRIDGE_LOADED.matcher(l).find()) bridgeLoaded = true;
            if (BRIDGE_INIT.matcher(l).find()) bridgeInit = true;
            if (BRIDGE_FAIL.matcher(l).find()) bridgeFail = true;
        }
        if (bridgeLoaded) rows.add(pass("GAME BRIDGE", "lsteamclient loaded Valve's libsteamclient.so inside the game process"));
        else if (bridgeInit || bridgeFail) rows.add(fail("GAME BRIDGE", "lsteamclient started but did not load the host client — see the bridge section"));
        else rows.add(warn("GAME BRIDGE", "no lsteamclient lines in logcat — the game may not use steam_api, or logcat capture is off"));

        // 13. Game attached to the host session
        String gameproc = steamRaw.get("gameprocess_log.txt");
        boolean tracked = gameproc != null && GAME_TRACKED.matcher(since != null ? after(gameproc, since) : gameproc).find();
        if (tracked) rows.add(pass("GAME ATTACHED", "Valve's client tracked the game in its games list (gameprocess_log)"));
        else if (lo.find(0)) rows.add(warn("GAME ATTACHED", "no games-list change in gameprocess_log — the game may not have connected to the host"));

        // 13b. In-game signatures (wine_debug.log, this run)
        String wine = wineText != null ? wineText : "";
        boolean sbOpened = wine.contains("RequestInternetServerList") || wine.contains("RequestLANServerList");
        boolean sbOurs = false, sbListening = false, sbListOk = false, sbListErr = false;
        String sbDetail = "";
        for (String l : bridgeLines) {
            if (l.contains("Bannerlator server browser enabled")) sbOurs = true;
            if (l.contains("server-list service listening") || l.contains("server-list service stopped") || l.contains("LIST app=")) sbListening = true;
            if (l.contains("BH_APPSTEAM_SB") && l.contains("LIST app=") && l.contains("servers in")) { sbListOk = true; sbDetail = l.substring(l.indexOf("LIST app=")); }
            if (l.contains("BH_APPSTEAM_SB") && l.contains("failed:")) { sbListErr = true; sbDetail = l.substring(l.indexOf("LIST app=")); }
        }
        if (sbListening) rows.add(pass("SERVER LIST SERVICE", "app served the server list on 127.0.0.1:57345 (Web API GetServerList, engine web token)"));
        else if (lo.find(0)) rows.add(warn("SERVER LIST SERVICE", "not listening — Find Servers returns an empty list (layer v13+ bridge expects it)"));
        if (sbOpened) {
            if (sbListOk) rows.add(pass("SERVER BROWSER", "Bannerlator browser answered the game — " + sbDetail));
            else if (sbListErr) rows.add(fail("SERVER BROWSER", "list request failed — " + sbDetail));
            else if (sbOurs) rows.add(warn("SERVER BROWSER", "Bannerlator browser active but no LIST reached the app (host/app port mismatch?)"));
            else rows.add(fail("SERVER BROWSER", "the game used Valve's in-game browser (layer without the Bannerlator browser, or BL_SERVER_BROWSER unset) — it hangs on this library"));
        }
        if (wine.contains("Thread synchronization object is unuseable"))
            rows.add(fail("IN-GAME CLIENT", "Valve's in-game half reported 'Thread synchronization object is unuseable' "
                    + "(tier0 threadtools) — the game hangs after this; seen when the client service is not listening"));
        if (wine.contains("steamclient64.dll") && wine.contains("failed to load"))
            rows.add(fail("IN-GAME CLIENT", "steamclient64.dll failed to load in the game — the bridge did not take over"));

        // 14. Engine presence gate
        boolean loop = false;
        for (String l : bridgeLines) if (RECONNECT_LOOP.matcher(l).find()) { loop = true; break; }
        rows.add(loop ? fail("PRESENCE GATE", "the app's own session went into a reconnect loop — it announced the game while the host owned it (the AppSteam gate must suppress ClientGamesPlayed)")
                      : pass("PRESENCE GATE", "no session reconnect loop seen (the app session stayed quiet while the host owned the game)"));

        // 15. VAC
        rows.add(info("VAC", "secure (VAC) servers are not yet proven on Headless Steam — use SteamLite for VAC titles until this says otherwise"));

        if (evs.contains("session_lost")) rows.add(warn("SESSION", "session_lost during play — online features may have dropped"));
        if (evs.contains("shutdown") || evs.contains("game_exited")) rows.add(info("END", "host shut down cleanly after the game exited"));
        if (HOST_FAIL_LINE.matcher(host).find() && !evs.contains("host_failed")) rows.add(warn("HOST", "the host log contains a failure line — see the host log section"));

        flush(out, rows);
    }

    private static void flush(StringBuilder out, List<String> rows) {
        int pass = 0, fail = 0, warn = 0;
        for (String r : rows) { if (r.startsWith("[PASS]")) pass++; else if (r.startsWith("[FAIL]")) fail++; else if (r.startsWith("[WARN]")) warn++; }
        out.append(String.format(Locale.ENGLISH, "Result: %d passed, %d failed, %d warnings%s\n\n",
                pass, fail, warn, fail == 0 ? " — the Headless Steam path worked end to end" : " — read the first FAIL: everything after it is a consequence"));
        for (String r : rows) out.append(r).append('\n');
    }

    private static String pass(String step, String what) { return "[PASS] " + step + ": " + what; }
    private static String fail(String step, String what) { return "[FAIL] " + step + ": " + what; }
    private static String warn(String step, String what) { return "[WARN] " + step + ": " + what; }
    private static String info(String step, String what) { return "[INFO] " + step + ": " + what; }

    private static String reason(String evLine) {
        if (evLine == null) return "";
        Matcher m = EV_REASON.matcher(evLine);
        return m.find() ? " — " + clip(m.group(1)) : "";
    }

    private static String after(String text, String since) {
        int i = text.indexOf(since);
        return i >= 0 ? text.substring(i) : text;
    }

    private static String clip(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 160 ? s.substring(0, 157) + "…" : s;
    }

    // ── Sections ────────────────────────────────────────────────────────────────────────────────

    private static void appendLaunchPlan(StringBuilder out, Launch launch) {
        out.append("\n===== LAUNCH PLAN — what the app set up before the game started =====\n");
        if (launch == null) { out.append("(no plan)\n"); return; }
        out.append("appId: ").append(launch.appId).append('\n');
        out.append("Valve client build: ").append(launch.valveBuild == null || launch.valveBuild.isEmpty() ? "(none)" : launch.valveBuild)
           .append(launch.valveBuildVerified ? " (verified)" : " (NOT verified)").append('\n');
        out.append("Proton layer lsteamclient: ").append(launch.layerHasLsteamclient == null ? "unknown" : launch.layerHasLsteamclient ? "present" : "ABSENT").append('\n');
        out.append("host_ready seen by the app: ").append(launch.hostReady ? "yes" : "no").append('\n');
        if (launch.envKeys != null) out.append("guest env keys added (values omitted): ").append(String.join(", ", launch.envKeys)).append('\n');
        if (launch.envRemoved != null) out.append("guest env keys removed: ").append(String.join(", ", launch.envRemoved)).append('\n');
    }

    private static void appendHostEvents(StringBuilder out, List<String> events) {
        out.append("\n===== HOST EVENTS — the status channel, in order (host → app) =====\n");
        if (events == null || events.isEmpty()) { out.append("(no events — the channel never opened or the host sent nothing)\n"); return; }
        for (String line : events) out.append(SteamLogRedactor.redactSteamClientLine(line)).append('\n');
    }

    private static void appendHostLog(StringBuilder out, String text) {
        out.append("\n===== HOST LOG — bl-steam-host (steam_host.log) =====\n");
        if (text == null || text.trim().isEmpty()) { out.append("(no host log — the host never started, or its log was not written)\n"); return; }
        int cbLines = 0;
        for (String line : text.split("\n")) {
            if (line.contains("callback id=")) { cbLines++; continue; }
            if (line.trim().isEmpty()) continue;
            out.append(SteamLogRedactor.redactSteamClientLine(line)).append('\n');
        }
        if (cbLines > 0) out.append("(").append(cbLines).append(" callback-trace lines omitted)\n");
    }

    private static final Pattern BRIDGE_TAGS = Pattern.compile("\\b(lsteamclient|BH_APPSTEAM|BH_APPSTEAM_SB|BH_STEAMHOST|BlSteamHost|SteamHost)\\b");

    private static void replaceFirst(StringBuilder sb, String from, String to) {
        int i = sb.indexOf(from);
        if (i >= 0) sb.replace(i, i + from.length(), to);
    }

    /**
     * Our own logcat read, tag-filtered and NOT limited to the app's pid: the bridge logs from the
     * game process and the host logs from the host process, both other pids of our uid, which the
     * shared {@link LogcatCapture#capture} (--pid=app) never sees.
     */
    private static String captureBridgeLogcat() {
        // No tag filter: filtered by BRIDGE_TAGS afterwards, so a tag spelled differently by one
        // of the processes still lands here. 8000 lines covers a launch comfortably.
        String[] cmd = { "logcat", "-d", "-t", "8000", "-v", "threadtime" };
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.destroy();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "bridge logcat capture failed", e);
            return null;
        }
    }

    // ── Live capture: `logcat` streaming the feature's tags to a cache file for the whole session ──
    private static Process liveProc;

    private static File liveFile(Context ctx) { return new File(ctx.getCacheDir(), "headless_bridge_logcat.txt"); }

    /** Start streaming the launcher/host/bridge tags to a file (idempotent; call when the plan arms). */
    public static synchronized void startLiveCapture(Context ctx) {
        stopLiveCapture();
        File f = liveFile(ctx);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        try {
            liveProc = new ProcessBuilder("logcat", "-v", "threadtime",
                    "BH_APPSTEAM:*", "BH_APPSTEAM_SB:*", "BH_STEAMHOST:*", "BlSteamHost:*", "lsteamclient:*", "SteamHost:*", "*:S")
                    .redirectErrorStream(true).redirectOutput(f).start();
        } catch (Exception e) {
            Log.w(TAG, "live logcat capture failed to start", e);
            liveProc = null;
        }
    }

    public static synchronized void stopLiveCapture() {
        if (liveProc != null) { try { liveProc.destroy(); } catch (Throwable ignored) {} liveProc = null; }
    }

    private static String readLiveCapture(Context ctx) {
        stopLiveCapture();
        File f = liveFile(ctx);
        if (!f.isFile() || f.length() == 0) return null;
        return SteamLiteLogCollector.readTail(f, 2L * 1024 * 1024);
    }

    /** The app's logcat lines that belong to this feature: the game-side bridge + host + launcher tags. */
    private static List<String> bridgeLines(String logcatText) {
        List<String> out = new ArrayList<>();
        if (logcatText == null) return out;
        for (String line : logcatText.split("\n")) {
            if (BRIDGE_TAGS.matcher(line).find()) out.add(line);
        }
        return out;
    }

    private static void appendBridgeSection(StringBuilder out, List<String> lines) {
        out.append("\n===== GAME-SIDE BRIDGE + LAUNCHER — logcat (lsteamclient / BH_APPSTEAM / BH_STEAMHOST / BlSteamHost) =====\n");
        if (lines.isEmpty()) { out.append("(none captured — enable Android logcat in Log Manager to include these)\n"); return; }
        for (String line : lines) out.append(SteamLogRedactor.redactSteamClientLine(LogcatCapture.redact(line))).append('\n');
    }
}
