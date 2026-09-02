package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.winlator.star.store.blsteam.BlLibraryCrawler;
import com.winlator.star.store.blsteam.BlSteamEngine;
import com.winlator.star.store.blsteam.BlSteamEngineFlag;
import com.winlator.star.store.blsteam.BlSteamSession;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.CheckAppBetaPasswordCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends;
import in.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendAddedCallback;
import in.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendMsgCallback;
import in.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendMsgEchoCallback;
import in.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendMsgHistoryCallback;
import in.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendsListCallback;
import in.dragonbra.javasteam.steam.handlers.steamfriends.callback.NicknameListCallback;
import in.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;

/**
 * Singleton managing the JavaSteam SteamClient lifecycle.
 *
 * Written in Java (not Kotlin) to avoid Kotlin metadata version
 * incompatibilities: JavaSteam is compiled with Kotlin 2.2.0 while
 * the base APK's Kotlin runtime is 1.9.24.  Java bytecode interop
 * bypasses all metadata version checks.
 *
 * Self-contained: uses SharedPreferences directly (no dependency on
 * SteamPrefs.kt which is compiled in a later Kotlin step).
 *
 * Lifecycle:
 *   SteamForegroundService.onStartCommand()
 *     → SteamRepository.getInstance().initialize(ctx)
 *     → SteamRepository.getInstance().connect()
 *   SteamForegroundService.onDestroy()
 *     → SteamRepository.getInstance().disconnect()
 */
public final class SteamRepository {

    private static final String TAG        = "SteamRepo";
    private static final String PREFS_NAME = "steam_prefs";

    // -------------------------------------------------------------------------
    // Library type-filter exceptions
    // -------------------------------------------------------------------------
    // Steam apps are normally shown only when their PICS common.type == "game"
    // (see the sync-time blocklist in processApps() and the display filter in
    // SteamGamesActivity.loadGames()). A few non-"game" apps are still worth
    // surfacing — e.g. utilities the user genuinely wants to run in a container.
    // Any appId in this set bypasses BOTH filters: it is ingested even if its
    // type is normally skipped (tool/hardware/etc.) AND shown in the library
    // list even though its type isn't "game". Add specific appIds here.
    //   993090 = Lossless Scaling
    public static final java.util.Set<Integer> LIBRARY_ALLOWLIST =
        java.util.Collections.unmodifiableSet(new java.util.HashSet<>(
            java.util.Arrays.asList(993090)));

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static final SteamRepository INSTANCE = new SteamRepository();
    public static SteamRepository getInstance() { return INSTANCE; }
    private SteamRepository() {}

    static {
        // JavaSteam's DepotManifest.serialize() does MessageDigest.getInstance("SHA-1", "BC"),
        // requesting the BouncyCastle provider by name. Android's built-in "BC" provider has had
        // SHA-1 (and most algorithms) stripped, so that call throws NoSuchAlgorithmException and
        // every depot download dies while saving the manifest. Replace the stock BC with the full
        // bundled BouncyCastle (bcprov-jdk15on) so "BC" SHA-1 resolves. AndroidOpenSSL (Conscrypt)
        // stays the default provider for TLS, so this only affects explicit "BC" lookups.
        try {
            java.security.Security.removeProvider("BC");
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            Log.i(TAG, "Registered full BouncyCastle provider (JavaSteam manifest SHA-1)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to register BouncyCastle provider", t);
        }
    }

    // -------------------------------------------------------------------------
    // Event listener
    // -------------------------------------------------------------------------

    public interface SteamEventListener {
        void onEvent(String event);
    }

    private final CopyOnWriteArrayList<SteamEventListener> listeners =
            new CopyOnWriteArrayList<>();

    public void addListener(SteamEventListener l)    { listeners.add(l); }
    public void removeListener(SteamEventListener l) { listeners.remove(l); }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private volatile boolean connected = false;
    private volatile boolean loggedIn  = false;

    public boolean isConnected() { return connected; }
    public boolean isLoggedIn()  { return loggedIn; }

    // -------------------------------------------------------------------------
    // SharedPreferences (set on initialize)
    // -------------------------------------------------------------------------

    private Context appContext = null;
    private SharedPreferences prefs = null;

    private String  pGet(String key, String  def) { return prefs != null ? prefs.getString(key, def)  : def; }
    private long    pGet(String key, long    def) { return prefs != null ? prefs.getLong(key, def)    : def; }
    private int     pGet(String key, int     def) { return prefs != null ? prefs.getInt(key, def)     : def; }

    private void    pPut(String key, String v)  { if (prefs != null) prefs.edit().putString(key, v).apply(); }
    private void    pPut(String key, long v)    { if (prefs != null) prefs.edit().putLong(key, v).apply(); }
    private void    pPut(String key, int v)     { if (prefs != null) prefs.edit().putInt(key, v).apply(); }

    private boolean isLoggedInPrefs() {
        return !pGet("refresh_token", "").isEmpty() && !pGet("username", "").isEmpty();
    }

    // -------------------------------------------------------------------------
    // Native Rust Steam engine (libblsteam.so) — Phase 0, hidden flag, default OFF
    // -------------------------------------------------------------------------
    // docs/STEAM_RUST_ENGINE_PLAN.md. When BlSteamEngineFlag is ON (read once in initialize()),
    // this repository's CM session is driven by BlSteamEngine instead of JavaSteam: connect(),
    // reconnectNow() and loginWithToken() route to rustConnect(), disconnect()/logout() stop the
    // engine, and the engine's state feeds the status pill + steam_prefs (steam_id_64/account_id,
    // rotated refresh_token) so SteamLite and Goldberg keep reading the same keys. The JavaSteam
    // objects are still built but never connected, and the JavaSteam-backed `connected`/`loggedIn`
    // flags stay false — every JavaSteam surface (library sync, depots, friends, cloud) therefore
    // refuses honestly ("not logged in") rather than hitting a dead SteamClient. Phase 1+ move those
    // surfaces over. Flag OFF: none of this runs.
    private static final String RUST_TAG = "BL_STEAM_REPO";
    private volatile boolean rustEngine = false;

    /** True when this process runs its Steam CM session on the native Rust engine. */
    public boolean isRustEngine() { return rustEngine; }

    private final BlSteamEngine.Listener rustListener = new BlSteamEngine.Listener() {
        @Override public void onEngineState(int state, long steamId64) {
            if (state == BlSteamEngine.STATE_LOGGED_ON) {
                if (steamId64 != 0L) {
                    pPut("steam_id_64", steamId64);
                    pPut("account_id", (int) (steamId64 & 0xFFFFFFFFL));
                }
                lastSessionStatus = "LoggedIn";
                setStatus(SteamStatus.ONLINE, "rust engine logged on");
                Log.i(RUST_TAG, SteamLogRedactor.redact("logged on as " + pGet("username", "") + " steamId=" + steamId64));
                // Same event the JavaSteam onLoggedOn emits, so the login screens / pill react alike.
                emit("LoggedIn:" + steamId64);
                // Phase 1-A: the owned library now comes from the engine — crawl it exactly where the
                // JavaSteam path would receive its LicenseList push.
                rustSyncLibrary("logon");
            } else if (state == BlSteamEngine.STATE_CONNECTING || state == BlSteamEngine.STATE_CONNECTED) {
                if (state == BlSteamEngine.STATE_CONNECTED) emit("Connected");
                if (!realSteamSuspended) setStatus(SteamStatus.CONNECTING, "rust engine state " + state);
            } else {
                if (realSteamSuspended)  setStatus(SteamStatus.PAUSED_FOR_GAME, "rust engine down — paused for in-game real-Steam session");
                else if (loggingOut)     setStatus(SteamStatus.SIGNED_OUT, "rust engine: signed out");
                else if (rustTokenRejected) setStatus(SteamStatus.SIGNED_OUT, "rust engine: refresh token rejected — needs re-auth");
                else                     setStatus(SteamStatus.OFFLINE, "rust engine disconnected");
                emit("Disconnected");
            }
        }
        @Override public void onLogonResult(int emsg, int eresult) {
            // 751 = ClientLogonResponse (non-OK), 757 = ClientLoggedOff. Mirror onLoggedOn/onLoggedOff:
            // a dead token → SIGNED_OUT + LoginFailed (user must re-auth); anything else is transient.
            String name = eresultName(eresult);
            lastSessionStatus = (emsg == 751 ? "LoginFailed:" : "LoggedOff:") + name;
            boolean rejected = eresult == 5 || eresult == 15 || eresult == 65 || eresult == 68 || eresult == 87;
            if (emsg == 751) {
                Log.w(RUST_TAG, "rust engine: logon failed eresult=" + eresult + " (" + name + ")");
                if (rejected) {
                    rustTokenRejected = true;
                    setStatus(SteamStatus.SIGNED_OUT, "rust engine: login rejected " + name);
                } else if (!realSteamSuspended) {
                    setStatus(SteamStatus.CONNECTING, "rust engine: login failed " + name + " (transient)");
                }
                emit("LoginFailed:" + name);
            } else {
                Log.i(RUST_TAG, "rust engine: logged off eresult=" + eresult + " (" + name + ")");
                if (eresult == 34 || eresult == 43) {   // LoggedInElsewhere / LogonSessionReplaced
                    if (!realSteamSuspended)
                        setStatus(SteamStatus.SIGNED_IN_ELSEWHERE, "rust engine: replaced by another client " + name);
                    emit("LoggedOut");
                }
            }
        }
        @Override public void onRefreshTokenRotated(String refreshToken) {
            SteamLogRedactor.registerSecret(refreshToken);
            pPut("refresh_token", refreshToken);
            slog("rust engine: refresh token rotated");
        }
        @Override public void onEngineFailure(String reason) {
            Log.w(RUST_TAG, "engine failure: " + reason);
            if (!realSteamSuspended) setStatus(SteamStatus.OFFLINE, "rust engine: " + reason);
        }
    };

    /** Rust-engine counterpart of connect()+loginWithToken(): resolve a CM, connect, token logon. */
    private void rustConnect(String source) {
        if (appContext == null) { Log.e(RUST_TAG, "rustConnect before initialize()"); return; }
        if (!isLoggedInPrefs()) {
            setStatus(SteamStatus.SIGNED_OUT, "rust engine: no saved session (" + source + ")");
            return;
        }
        if (BlSteamEngine.INSTANCE.isActive() && BlSteamEngine.INSTANCE.state() == BlSteamEngine.STATE_LOGGED_ON) {
            Log.i(RUST_TAG, "rustConnect skipped — already logged on (" + source + ")");
            return;
        }
        loggingOut = false;
        rustTokenRejected = false;
        SteamLogRedactor.registerSecret(pGet("username", ""));
        SteamLogRedactor.registerSecret(pGet("refresh_token", ""));
        setStatus(SteamStatus.CONNECTING, "rust engine connect (" + source + ")");
        BlSteamEngine.INSTANCE.start(appContext, pGet("username", ""), pGet("refresh_token", ""),
                pGet("steam_id_64", 0L), rustListener);
    }

    /** Set when Steam rejected the saved refresh token on the Rust engine (cleared by a fresh connect/login). */
    private volatile boolean rustTokenRejected = false;

    /**
     * Engine-agnostic "is there a live, logged-on CM session right now?" — the JavaSteam
     * {@code loggedIn} flag, or the Rust engine's LoggedOn state. The Phase-0 contract keeps
     * {@link #isLoggedIn()} false under the Rust engine so JavaSteam-only surfaces refuse honestly;
     * callers that only need a session (the launch pre-flight, the session manager) use this.
     */
    public boolean isSessionLoggedOn() {
        return rustEngine ? BlSteamEngine.INSTANCE.isLoggedOn() : loggedIn;
    }

    /** Human-readable Steam EResult name for the handful we act on (falls back to the number). */
    static String eresultName(int r) {
        switch (r) {
            case 1:  return "OK";
            case 2:  return "Fail";
            case 3:  return "NoConnection";
            case 5:  return "InvalidPassword";
            case 6:  return "LoggedInElsewhere";
            case 15: return "AccessDenied";
            case 20: return "ServiceUnavailable";
            case 34: return "LoggedInElsewhere";
            case 43: return "LogonSessionReplaced";
            case 63: return "AccountLogonDenied";
            case 65: return "AccountLogonDenied";
            case 68: return "AccountLoginDeniedNeedTwoFactor";
            case 84: return "RateLimitExceeded";
            case 85: return "AccountLoginDeniedThrottle";
            case 87: return "AccountLoginDeniedVerifiedEmailRequired";
            default: return "EResult" + r;
        }
    }

    // -------------------------------------------------------------------------
    // Rust engine — owned library / PICS (Phase 1-A)
    // -------------------------------------------------------------------------
    // With the flag ON the owned library is crawled through libblsteam.so (BlLibraryCrawler) instead
    // of the JavaSteam LicenseList → PICS callback chain above. Every result is fed through the SAME
    // per-app parser (processAppKv) into the SAME SteamDatabase tables and the SAME
    // LibraryProgress / LibrarySynced events, so the game list, detail page, branch selector, DLC tab
    // and the RealSteam launcher are engine-blind. The engine's appinfo arrives as JSON (key order
    // preserved — serde_json preserve_order + Android's insertion-ordered JSONObject) and is rebuilt
    // into a KeyValue tree, so depot ordering / DLC positional grouping match the VDF the JavaSteam
    // parser sees. Dev parity proof: see BL_STEAM_PICS below.
    private static final String PICS_DIFF_TAG = "BL_STEAM_PICS";
    private final AtomicBoolean rustSyncing = new AtomicBoolean(false);
    private volatile int rustSyncProcessed = 0;

    /** Crawl the owned library on the Rust engine. Idempotent while a crawl is running. Any thread. */
    private void rustSyncLibrary(String source) {
        if (!rustEngine) return;
        final BlSteamSession s = BlSteamEngine.INSTANCE.session();
        if (s == null || !BlSteamEngine.INSTANCE.isLoggedOn()) {
            Log.i(RUST_TAG, "library sync skipped — engine not logged on (" + source + ")");
            return;
        }
        if (!rustSyncing.compareAndSet(false, true)) {
            Log.i(RUST_TAG, "library sync already running (" + source + ")");
            return;
        }
        Log.i(RUST_TAG, "library sync starting on the Rust engine (" + source + ")");
        Thread t = new Thread(() -> {
            try {
                final SteamDatabase db = getDatabase();
                final Map<Integer, String> before = picsDiffSnapshot(db);
                final String beforeEngine = pGet("last_sync_engine", "");
                rustSyncProcessed = 0;
                new BlLibraryCrawler(s).run(new BlLibraryCrawler.Sink() {
                    private java.util.Set<Integer> licensedApps = null;
                    private int stored = 0;

                    @Override public void onLicenses(List<BlLibraryCrawler.License> licenses) {
                        Log.i(RUST_TAG, licenses.size() + " licenses received (rust)");
                        db.clearLicenses();
                        for (BlLibraryCrawler.License lic : licenses)
                            db.upsertLicense(lic.getPackageId(), lic.getTimeCreated(), lic.getFlags(), lic.getLicenseType());
                        emit("LibraryProgress:0:" + licenses.size());
                    }
                    @Override public void onPackagesResolved(Map<Integer, List<Integer>> packageApps, List<Integer> uniqueAppIds) {
                        for (Map.Entry<Integer, List<Integer>> e : packageApps.entrySet())
                            for (int appId : e.getValue()) db.linkLicenseApp(e.getKey(), appId);
                        Log.i(RUST_TAG, "PICS packages resolved " + uniqueAppIds.size() + " unique app IDs (rust)");
                        emit("LibraryProgress:1:" + uniqueAppIds.size());
                        licensedApps = new java.util.HashSet<>(db.getLicensedAppIds());
                    }
                    @Override public void onAppBatch(List<kotlin.Pair<Integer, JSONObject>> apps, int processed, int total) {
                        if (licensedApps == null) licensedApps = new java.util.HashSet<>(db.getLicensedAppIds());
                        int count = 0;
                        for (kotlin.Pair<Integer, JSONObject> p : apps) {
                            KeyValue root = jsonToKeyValue("appinfo", p.getSecond());
                            if (processAppKv(p.getFirst(), root, db, licensedApps)) count++;
                        }
                        stored += count;
                        rustSyncProcessed = processed;
                        emit("LibraryProgress:2:" + processed + ":" + total);
                        Log.i(RUST_TAG, "PICS app batch parsed (rust): +" + count + " (" + processed + "/" + total + ")");
                    }
                    @Override public void onFinished(int total) {
                        recordSyncTime();
                        pPut("last_sync_engine", "rust");
                        Log.i(RUST_TAG, "Library sync complete (rust): " + stored + " apps stored of " + total);
                        emit("LibrarySynced:" + stored);
                        picsDiffReport(db, before, beforeEngine, "rust");
                    }
                    @Override public void onFailed(String reason, int processed) {
                        Log.w(RUST_TAG, "Library sync stopped (rust): " + reason + " after " + processed + " apps");
                        if (processed > 0) { cachedGameRows = null; emit("LibrarySynced:" + stored); }
                    }
                    @Override public boolean isCancelled() {
                        return !rustEngine || !BlSteamEngine.INSTANCE.isLoggedOn() || BlSteamEngine.INSTANCE.session() != s;
                    }
                });
            } catch (Throwable err) {
                Log.w(RUST_TAG, "library sync crashed", err);
            } finally {
                rustSyncing.set(false);
            }
        }, "BlSteamLibrarySync");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Rebuild a JavaSteam {@link KeyValue} tree from the engine's JSON appinfo. Objects become nodes
     * with children (insertion order preserved), scalars become string leaves — exactly the shape
     * {@code PICSProductInfo.getKeyValues()} has, so {@link #processAppKv} parses both alike.
     */
    static KeyValue jsonToKeyValue(String name, Object json) {
        if (json instanceof JSONObject) {
            JSONObject o = (JSONObject) json;
            KeyValue kv = new KeyValue(name, "");
            List<KeyValue> children = new ArrayList<>(o.length());
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                children.add(jsonToKeyValue(k, o.opt(k)));
            }
            kv.setChildren(children);
            return kv;
        }
        return new KeyValue(name, json == null || json == JSONObject.NULL ? "" : String.valueOf(json));
    }

    /**
     * Engine-agnostic single-shot product-info fetch for a few apps → KeyValue roots (missing ids
     * absent). BLOCKS the caller up to {@code timeoutMs} (JavaSteam future) / the native 30 s per hop
     * (Rust). Never call from the pump thread.
     */
    private Map<Integer, KeyValue> fetchProductInfoKv(List<Integer> appIds, long timeoutMs) {
        Map<Integer, KeyValue> out = new java.util.HashMap<>();
        if (appIds.isEmpty()) return out;
        if (rustEngine) {
            BlSteamSession s = BlSteamEngine.INSTANCE.session();
            if (s == null || !BlSteamEngine.INSTANCE.isLoggedOn()) return out;
            for (kotlin.Pair<Integer, JSONObject> p : new BlLibraryCrawler(s).fetchApps(appIds))
                out.put(p.getFirst(), jsonToKeyValue("appinfo", p.getSecond()));
            return out;
        }
        SteamApps sa = steamApps;
        if (sa == null || !loggedIn) return out;
        try {
            List<PICSRequest> reqs = new ArrayList<>();
            for (int id : appIds) reqs.add(new PICSRequest(id));
            in.dragonbra.javasteam.types.AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                    sa.picsGetProductInfo(reqs, Collections.emptyList())
                            .toFuture().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            for (PICSProductInfoCallback cb : rs.getResults()) {
                for (Map.Entry<Integer, PICSProductInfo> e : cb.getApps().entrySet()) {
                    KeyValue kv = e.getValue() != null ? e.getValue().getKeyValues() : null;
                    if (kv != null) out.put(e.getKey(), kv);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchProductInfoKv(" + appIds + ") failed: " + e.getMessage());
        }
        return out;
    }

    // ── BL_STEAM_PICS: dev parity diff ────────────────────────────────────────────────────────────
    // Proves on-device that the Rust crawl writes the SAME rows the JavaSteam sync wrote: before a
    // Rust sync we snapshot every game's parsed signature (name|type|size|depot csv|included DLC|
    // branch build ids|depot manifest ids) as the DB holds it — i.e. the previous engine's output —
    // and after the sync we diff app-by-app, log the summary + first mismatches under BL_STEAM_PICS,
    // and write the full report to <externalFiles>/bl_steam_pics_diff.txt. The interesting run is
    // "previous=javasteam → now=rust" (flip the flag after a JavaSteam sync, restart, sign in).

    private static Map<Integer, String> picsDiffSnapshot(SteamDatabase db) {
        Map<Integer, String> out = new java.util.TreeMap<>();
        try {
            for (SteamDatabase.GameRow g : db.getAllGames()) {
                StringBuilder sb = new StringBuilder();
                sb.append("name=").append(g.name).append("|type=").append(g.type)
                  .append("|size=").append(g.sizeBytes).append("|depots=").append(g.depotIds)
                  .append("|dlc=").append(String.join(",", db.getIncludedDlcNames(g.appId)));
                sb.append("|branches=");
                for (SteamDatabase.BranchRow b : db.getBranches(g.appId))
                    sb.append(b.branchName).append(':').append(b.buildId).append(';');
                sb.append("|manifests=");
                for (SteamDatabase.DepotManifestRow m : db.getDepotManifests(g.appId))
                    sb.append(m.depotId).append(':').append(m.manifestId).append(':').append(m.sizeBytes).append(';');
                out.put(g.appId, sb.toString());
            }
        } catch (Throwable t) {
            Log.w(PICS_DIFF_TAG, "snapshot failed", t);
        }
        return out;
    }

    private void picsDiffReport(SteamDatabase db, Map<Integer, String> before, String beforeEngine, String nowEngine) {
        try {
            Map<Integer, String> after = picsDiffSnapshot(db);
            int same = 0, changed = 0;
            List<String> lines = new ArrayList<>();
            for (Map.Entry<Integer, String> e : after.entrySet()) {
                String prev = before.get(e.getKey());
                if (prev == null) { lines.add("ONLY-NOW  app " + e.getKey() + ": " + e.getValue()); continue; }
                if (prev.equals(e.getValue())) { same++; continue; }
                changed++;
                lines.add("CHANGED   app " + e.getKey() + "\n    prev: " + prev + "\n    now:  " + e.getValue());
            }
            int onlyPrev = 0;
            for (Integer id : before.keySet()) if (!after.containsKey(id)) { onlyPrev++; lines.add("ONLY-PREV app " + id + ": " + before.get(id)); }
            int onlyNow = after.size() - same - changed;
            String head = "diff previous=" + (beforeEngine.isEmpty() ? "?" : beforeEngine) + " → now=" + nowEngine
                    + ": prevApps=" + before.size() + " nowApps=" + after.size() + " same=" + same
                    + " changed=" + changed + " onlyPrev=" + onlyPrev + " onlyNow=" + onlyNow
                    + (beforeEngine.equals(nowEngine) ? " (same engine both times — not a cross-engine proof)" : "");
            Log.i(PICS_DIFF_TAG, head);
            int shown = 0;
            for (String l : lines) { if (shown++ >= 40) { Log.i(PICS_DIFF_TAG, "… " + (lines.size() - 40) + " more in bl_steam_pics_diff.txt"); break; } Log.i(PICS_DIFF_TAG, l); }
            if (appContext != null) {
                File dir = appContext.getExternalFilesDir(null);
                if (dir != null) {
                    File f = new File(dir, "bl_steam_pics_diff.txt");
                    try (BufferedWriter w = new BufferedWriter(new FileWriter(f, false))) {
                        w.write("[" + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "] " + head + "\n");
                        for (String l : lines) { w.write(l); w.write('\n'); }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(PICS_DIFF_TAG, "diff failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // JavaSteam instances
    // -------------------------------------------------------------------------

    private SteamClient    steamClient   = null;
    private CallbackManager manager      = null;
    private SteamUser      steamUser     = null;
    private SteamApps      steamApps     = null;
    private SteamCloud     steamCloud    = null;
    private SteamUserStats steamUserStats = null;
    private SteamFriends   steamFriends  = null;

    private HandlerThread     pumpThread  = null;
    private Handler           pumpHandler = null;
    private final AtomicBoolean pumping    = new AtomicBoolean(false);

    // Dedicated single-thread worker for library/PICS sync. The heavy PICS parse + Room writes
    // MUST NOT run on the pump thread: they block runWaitCallbacks() for seconds and the depot
    // manifest AsyncJob reply then can't be dispatched inside its ~10s window → CancellationException
    // → download dies at 0%. The pump callback handlers only marshal the payload out of the callback
    // and hand the parse/DB work here. Single-thread preserves the SYNC_PACKAGES→SYNC_APPS ordering.
    // Lifecycle tracks the pump: created in startPump(), shut down in stopPump().
    private volatile ExecutorService libraryWorker = null;
    /** True while a connect() call is in flight (posted to pump thread but not yet completed). */
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    // raw licenses (kept for Phase 5 DepotDownloader)
    private final List<License> licenses = new ArrayList<>();
    public List<License> getLicenses() {
        synchronized (licenses) { return new ArrayList<>(licenses); }
    }

    // -------------------------------------------------------------------------
    // Depot decryption keys (Phase 6)
    // -------------------------------------------------------------------------

    // depotId → AES-256-ECB key bytes (null if no encryption for that depot)
    private final Map<Integer, byte[]> depotKeys = new ConcurrentHashMap<>();

    public byte[] getDepotKey(int depotId) { return depotKeys.get(depotId); }

    // appId → compressed (network) download-size total of the SELECTED (windows/english,
    // downloadable) depots, computed during library sync. In-memory only — powers the
    // dual-color download/install progress bar's download denominator. A cache miss
    // (download resumed in a fresh process with no re-sync) returns 0 and the downloader
    // falls back to an install-size estimate.
    private final Map<Integer, Long> downloadSizeByApp = new ConcurrentHashMap<>();

    /** Compressed download size (bytes) of an app's selected depots, or 0 if unknown. */
    public long getSelectedDownloadSize(int appId) {
        Long v = downloadSizeByApp.get(appId);
        return v != null ? v : 0L;
    }

    /** Request a depot decryption key for the given depot. Result comes via DepotKeyCallback. */
    public void requestDepotKey(int depotId, int appId) {
        if (steamApps == null) return;
        steamApps.getDepotDecryptionKey(depotId, appId);
    }

    // -------------------------------------------------------------------------
    // In-memory game list cache
    // -------------------------------------------------------------------------

    /** Cached list of all rows from the DB (type filter applied by caller).
     *  Invalidated on LibrarySynced, DownloadComplete, and uninstall. */
    private volatile List<SteamDatabase.GameRow> cachedGameRows = null;

    /** Return cached rows if available, otherwise query the DB and cache. */
    public List<SteamDatabase.GameRow> getCachedGameRows() {
        List<SteamDatabase.GameRow> rows = cachedGameRows;
        if (rows != null) return rows;
        rows = getDatabase().getAllGames();
        cachedGameRows = rows;
        return rows;
    }

    /** Force the next getCachedGameRows() call to re-query the DB. */
    public void invalidateGameCache() {
        cachedGameRows = null;
    }

    /** Seconds since epoch of last successful PICS library sync. 0 = never. */
    public long getLastSyncTime() { return pGet("last_sync_time", 0L); }

    private void recordSyncTime() { pPut("last_sync_time", System.currentTimeMillis() / 1000L); }

    // -------------------------------------------------------------------------
    // Pluvia handlers: SteamCloud + SteamUserStats (exposed for SteamCloudSync
    // and SteamAppTicket which need them after login)
    // -------------------------------------------------------------------------

    public SteamCloud     getSteamCloud()     { return steamCloud; }
    public SteamUserStats getSteamUserStats() { return steamUserStats; }
    public SteamFriends   getSteamFriends()   { return steamFriends; }
    public CallbackManager getCallbackManager() { return manager; }

    // -------------------------------------------------------------------------
    // Manifest request codes (required since ~2022 to authenticate CDN manifests)
    // -------------------------------------------------------------------------

    // key = "depotId:manifestId", value = request code (ulong stored as long)
    private final Map<String, Long> manifestCodes = new ConcurrentHashMap<>();

    public long getManifestCode(int depotId, long manifestId) {
        Long code = manifestCodes.get(depotId + ":" + manifestId);
        return code != null ? code : 0L;
    }

    public void requestManifestCode(int appId, int depotId, long manifestId) {
        // Not available in this JavaSteam fork — fetched via Web API in SteamDepotDownloader
    }

    public void storeManifestCode(int depotId, long manifestId, long code) {
        manifestCodes.put(depotId + ":" + manifestId, code);
    }

    // -------------------------------------------------------------------------
    // CDN auth tokens (required to authenticate chunk downloads per CDN host)
    // -------------------------------------------------------------------------

    // cdnHost → auth token string
    private final Map<String, String> cdnTokens = new ConcurrentHashMap<>();

    public String getCdnAuthToken(String cdnHost) {
        String tok = cdnTokens.get(cdnHost);
        return tok != null ? tok : "";
    }

    public void requestCdnAuthToken(int appId, int depotId, String cdnHost) {
        // Not available in this JavaSteam fork — fetched via Web API in SteamDepotDownloader
    }

    public void storeCdnAuthToken(String cdnHost, String token) {
        cdnTokens.put(cdnHost, token);
    }

    // -------------------------------------------------------------------------
    // PICS sync state (Phase 4)
    // -------------------------------------------------------------------------

    private static final int SYNC_IDLE     = 0;
    private static final int SYNC_PACKAGES = 1;
    private static final int SYNC_APPS     = 2;
    private volatile int syncPhase = SYNC_IDLE;

    // Accumulated PICS responses (multiple callbacks may arrive for one request)
    private final Map<Integer, PICSProductInfo> pendingPackages = new ConcurrentHashMap<>();
    private final Map<Integer, PICSProductInfo> pendingApps     = new ConcurrentHashMap<>();

    // --- App-sync batching (Batch 1 core fix) --------------------------------------------------
    // The library used to fetch PICS product info for ALL owned app IDs (~372 on a large account) in
    // ONE picsGetProductInfo. That single huge request monopolises the shared CM TcpConnection: the
    // whole ~372-app response is parsed inline on the netThread and, while it parses, a concurrently
    // started depot download's own appinfo AsyncJob gets no reply inside its window → 60s
    // CancellationException → download stuck at 0%. We now walk the app list in small SEQUENTIAL
    // batches (each response drives the next), and PAUSE the sync entirely while a download is active
    // so the download's appinfo has a clear connection. All queue mutation is confined to the single
    // libraryWorker thread — the same ordering guarantee the existing SYNC_PACKAGES→SYNC_APPS design
    // already relies on — so no extra locking is needed.
    private static final int APP_SYNC_BATCH = 25;
    private final java.util.ArrayDeque<Integer> remainingAppIds = new java.util.ArrayDeque<>();
    private int appSyncTotal     = 0;   // total apps to fetch this sync (for the N/total progress line)
    private int appSyncProcessed = 0;   // running count of apps parsed+stored across all batches
    /** True while a depot download owns the CM connection — the app-sync batch loop must yield to it. */
    private volatile boolean downloadActive = false;
    /** True while the batch loop is parked mid-sync because a download is active (queue kept intact). */
    private volatile boolean appSyncPaused  = false;

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /** Build SteamClient and register callbacks. Idempotent. */
    public synchronized void initialize(Context ctx) {
        if (appContext == null) {
            appContext = ctx.getApplicationContext();
        }
        if (prefs == null) {
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        // Register the persisted username + refresh token so the log redactor strips them from every
        // diagnostic line, even before this session performs a login.
        SteamLogRedactor.registerSecret(pGet("username", ""));
        SteamLogRedactor.registerSecret(pGet("refresh_token", ""));
        SteamDatabase.getInstance(appContext);
        // Hidden dev flag, read once per process so a live session is never swapped mid-flight.
        rustEngine = BlSteamEngineFlag.isEnabled(appContext);
        if (rustEngine) Log.i(RUST_TAG, "use_rust_steam_engine=ON — CM session will run on libblsteam.so");
        if (steamClient != null) return;

        SteamConfiguration config = SteamConfiguration.create(b -> {
            // TCP-only: Ktor CIO engine (required for WebSocket) is not bundled in the APK
            // and causes a hard crash at runtime. TCP on port 27017 works reliably.
            b.withProtocolTypes(EnumSet.of(ProtocolTypes.TCP));
            b.withConnectionTimeout(30_000L);
            // REQUIRED: allow JavaSteam to fetch the CM server list from Steam's directory API.
            // Without this, if no server list is cached, getNextServerCandidate() returns null
            // and connect() immediately fires DisconnectedCallback without making any connection.
            b.withDirectoryFetch(true);
        });

        steamClient = new SteamClient(config);
        manager     = new CallbackManager(steamClient);
        steamUser   = steamClient.getHandler(SteamUser.class);
        steamApps   = steamClient.getHandler(SteamApps.class);
        // SteamCloud is a core JavaSteam handler (auto-registered by SteamClient). It was declared
        // + exposed via getSteamCloud() but never bound, so cloud saves could not work. Bind it here
        // so SteamCloudSaveManager (per-game cloud save up/download) has a live handle after login.
        steamCloud  = steamClient.getHandler(SteamCloud.class);
        // Same story for SteamUserStats: declared + exposed via getSteamUserStats() but never bound.
        // Bind it so SteamAchievementStore (per-game achievement fetch + optional sync-back) has a
        // live handle after login. Core handler — no extra registerCallbacks() entry is required
        // (getUserStats / storeUserStats use AsyncJobSingle futures, not manager.subscribe callbacks).
        steamUserStats = steamClient.getHandler(SteamUserStats.class);
        // SteamFriends is a core JavaSteam handler (auto-registered by SteamClient). Bind it here so
        // SteamFriendsStore (friends list + 1:1 chat) has a live handle after login. Its callbacks are
        // subscribed on the shared CallbackManager below and forwarded to that facade — READ/SEND only;
        // no change to the session/login/store paths.
        steamFriends = steamClient.getHandler(SteamFriends.class);

        registerCallbacks();
        Log.i(TAG, "SteamRepository initialised");
    }

    private void registerCallbacks() {
        manager.subscribe(ConnectedCallback.class,      cb -> onConnected());
        manager.subscribe(DisconnectedCallback.class,   this::onDisconnected);
        manager.subscribe(LoggedOnCallback.class,       this::onLoggedOn);
        manager.subscribe(LoggedOffCallback.class,      this::onLoggedOff);
        manager.subscribe(LicenseListCallback.class,     this::onLicenseList);
        manager.subscribe(PICSProductInfoCallback.class, this::onPICSProductInfo);
        manager.subscribe(DepotKeyCallback.class,        this::onDepotKey);
        // Friends list + 1:1 chat — forwarded to the SteamFriendsStore facade (Kotlin object). Purely
        // additive: presence + message callbacks, no effect on the existing session/download paths.
        manager.subscribe(FriendsListCallback.class,      SteamFriendsStore.INSTANCE::onFriendsList);
        manager.subscribe(PersonaStateCallback.class,     SteamFriendsStore.INSTANCE::onPersonaState);
        manager.subscribe(NicknameListCallback.class,     SteamFriendsStore.INSTANCE::onNicknameList);
        manager.subscribe(FriendAddedCallback.class,      SteamFriendsStore.INSTANCE::onFriendAdded);
        manager.subscribe(FriendMsgCallback.class,        SteamFriendsStore.INSTANCE::onFriendMsg);
        manager.subscribe(FriendMsgEchoCallback.class,    SteamFriendsStore.INSTANCE::onFriendMsgEcho);
        manager.subscribe(FriendMsgHistoryCallback.class, SteamFriendsStore.INSTANCE::onFriendMsgHistory);
        // CDN auth callbacks registered once correct class names are confirmed from JAR
        // manager.subscribe(ManifestRequestCodeCallback.class, this::onManifestRequestCode);
        // manager.subscribe(CDNAuthTokenCallback.class,        this::onCdnAuthToken);
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    public void connect() {
        if (steamClient == null) { Log.e(TAG, "connect() before initialize()"); return; }
        if (realSteamSuspended) {
            // FGS START_STICKY restart / foreground ON_START / pill tap while a real-Steam game holds
            // the account: stay down; keep the notification honest about why.
            Log.i(REALSTEAM_TAG, "connect() skipped — app session suspended for in-game real-Steam session");
            refreshFgsStatus();
            return;
        }
        if (rustEngine) { rustConnect("connect"); return; }
        if (connected) { Log.i(TAG, "connect() skipped — already connected"); return; }
        // Guard against double-connect (e.g. onStartCommand called twice for START_STICKY)
        if (!connecting.compareAndSet(false, true)) {
            Log.i(TAG, "connect() skipped — already connecting");
            return;
        }
        startPump();
        startReachabilityCheck();
        // Must NOT call steamClient.connect() on the main thread:
        // CMClient.connect() → SmartCMServerList.getNextServerCandidate() →
        // SteamDirectory.load() performs a synchronous HTTP call.  On Android,
        // network on the main thread is blocked (NetworkOnMainThreadException),
        // caught silently by runCatching → null servers → instant disconnect.
        // Also avoids 'assert connection == null' AssertionError when called
        // a second time while the previous TCP connection is still closing.
        pumpHandler.post(() -> {
            try {
                if (realSteamSuspended) {   // suspended between the post and the run — abandon the connect
                    connecting.set(false);
                    return;
                }
                steamClient.connect();
            } catch (Throwable t) {
                Log.e(TAG, "steamClient.connect() threw " + t.getClass().getSimpleName()
                        + ": " + t.getMessage(), t);
                connecting.set(false);
            }
        });
    }

    /** Quick background check — emits events so the UI can show a specific error message. */
    private void startReachabilityCheck() {
        new Thread(() -> {
            // Step 1: test general internet (Google connectivity check — works globally)
            boolean hasInternet = testUrl("https://connectivitycheck.gstatic.com/generate_204", 6000);
            if (!hasInternet) {
                // Try plain HTTP fallback in case HTTPS is blocked
                hasInternet = testUrl("http://connectivitycheck.gstatic.com/generate_204", 6000);
            }
            if (!hasInternet) {
                Log.w(TAG, "No internet connectivity");
                emit("NoInternet");
                return;
            }
            // Step 2: test Steam specifically
            boolean steamOk = testUrl("https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/?cellid=0", 6000);
            if (steamOk) {
                Log.i(TAG, "Steam API reachable");
                emit("Reachable");
            } else {
                Log.w(TAG, "Steam blocked on this network");
                emit("SteamBlocked");
            }
        }, "SteamReachCheck").start();
    }

    private boolean testUrl(String urlStr, int timeoutMs) {
        try {
            java.net.URL url = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            conn.disconnect();
            Log.i(TAG, "testUrl " + urlStr + " → " + code);
            return code > 0;
        } catch (Exception e) {
            Log.w(TAG, "testUrl " + urlStr + " failed: " + e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        if (rustEngine) BlSteamEngine.INSTANCE.stop();
        if (steamClient != null) steamClient.disconnect();
        stopPump();
        connected = false;
        loggedIn  = false;
    }

    private void startPump() {
        if (pumping.getAndSet(true)) return;
        pumpThread  = new HandlerThread("SteamPump");
        pumpThread.start();
        pumpHandler = new Handler(pumpThread.getLooper());
        libraryWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SteamLibraryWorker");
            t.setDaemon(true);
            return t;
        });
        schedulePump();
        // Arm the background network-availability self-heal now that the pump (and appContext) are up.
        registerNetworkCallback();
    }

    private void stopPump() {
        // Detach the network callback first so it can't fire a reconnect while we tear the pump down.
        unregisterNetworkCallback();
        pumping.set(false);
        if (pumpThread != null) { pumpThread.quitSafely(); pumpThread = null; }
        pumpHandler = null;
        ExecutorService w = libraryWorker;
        libraryWorker = null;
        if (w != null) w.shutdownNow();   // abandon any stale in-flight sync from this session
    }

    /**
     * Run library/PICS sync work off the pump thread. Keeps runWaitCallbacks() fast so AsyncJob
     * (depot manifest) replies flow. Falls back to a throwaway thread if the worker isn't up yet
     * (e.g. a sync triggered before startPump) or was just shut down mid-teardown.
     */
    private void runOnLibraryWorker(Runnable r) {
        ExecutorService w = libraryWorker;
        if (w != null && !w.isShutdown()) {
            try { w.execute(r); return; }
            catch (RejectedExecutionException ignored) { /* shutting down — fall through */ }
        }
        new Thread(r, "SteamLibrarySync").start();
    }

    private void schedulePump() {
        if (!pumping.get() || pumpHandler == null) return;
        pumpHandler.post(() -> {
            try { if (manager != null) manager.runWaitCallbacks(500L); }
            catch (Throwable t) { Log.e(TAG, "Pump error", t); }
            schedulePump();
        });
    }

    // -------------------------------------------------------------------------
    // Callback handlers
    // -------------------------------------------------------------------------

    private void onConnected() {
        Log.i(TAG, "Connected to Steam CM");
        connected = true;
        connecting.set(false);
        reconnectAttempts = 0;
        emit("Connected");
        setStatus(loggedIn ? SteamStatus.ONLINE : SteamStatus.CONNECTING, "CM connected");

        if (isLoggedInPrefs()) {
            Log.i(TAG, SteamLogRedactor.redact("Auto-login as " + pGet("username", "")));
            loginWithToken(pGet("username", ""), pGet("refresh_token", ""));
        }
    }

    private volatile int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    // --- Background network-availability self-heal (ConnectivityManager) --------------------------
    // If the network stays down longer than the ~30s auto-reconnect budget (MAX_RECONNECT_ATTEMPTS)
    // while the app is backgrounded (Doze, a tunnel, a wifi flap), onDisconnected exhausts its
    // attempts and we strand OFFLINE until the user reopens the app (ProcessLifecycle ON_START ->
    // reconnectNow()). A default-network callback closes that gap: the instant connectivity returns
    // we reset the attempt cap and re-drive the SAME reconnect entry point the status pill uses.
    // Guarded so it only acts when it makes sense (logged in per prefs, pump up, not already healthy,
    // no logon/connect in flight, not a deliberate sign-out) and debounced so a burst of
    // onAvailable/onCapabilitiesChanged callbacks kicks at most one reconnect.
    private ConnectivityManager networkCm = null;
    private ConnectivityManager.NetworkCallback networkCallback = null;
    private final Object networkCallbackLock = new Object();
    private volatile long lastNetworkReconnectAt = 0L;
    private static final long NETWORK_RECONNECT_DEBOUNCE_MS = 5_000L;

    /** Set by logout() so a user-initiated sign-out is not treated as an involuntary logoff to recover from. */
    private volatile boolean loggingOut = false;
    /** Set when we force a reconnect that must proceed even though the disconnect is "user-initiated" (see onLoggedOff). */
    private volatile boolean forceReconnect = false;
    /** Bounds relogin retries after an involuntary LoggedOff so a truly-dead token can't loop forever. */
    private volatile int logoffRecoveryAttempts = 0;
    private static final int MAX_LOGOFF_RECOVERY = 3;

    // --- Single-flight logon guard (fixes the self-inflicted LogonSessionReplaced) ---------------
    // Several call sites fire a token logon: onConnected auto-login, ensureLoggedIn, and the
    // interactive login activities. Two concurrent logOns on the SAME account make Steam reply
    // LogonSessionReplaced and kick us mid-session, leaving connected=true / loggedIn=false so
    // every depot download fails "session not ready". Coalesce them onto one in-flight logon.
    /** True while a logOn has been posted but no LoggedOn/LoggedOff/Disconnected has resolved it. */
    private final AtomicBoolean loggingOn = new AtomicBoolean(false);
    /** Wall-clock ms of the last logOn WE posted (guard start). */
    private volatile long logonStartedAt = 0L;
    /** Wall-clock ms the logOn was actually sent on the pump thread (for self-replace detection). */
    private volatile long lastSelfLogonAt = 0L;
    /** A logon with no callback older than this is treated as stalled and may be superseded. */
    private static final long LOGON_STALL_MS = 12_000L;
    /** A LogonSessionReplaced within this window of our own logon is our own newer session, not an eviction. */
    private static final long SELF_REPLACE_WINDOW_MS = 15_000L;
    /** Last session transition, surfaced into steam_debug.txt so the file the UI points to shows the cause. */
    private volatile String lastSessionStatus = "none";

    // --- In-app connection/login indicator state (drives the top-header status pill) --------------
    // The pill is the honest, always-visible replacement for the notification (which is cosmetic).
    // Every transition is written to the PERSISTENT steam_session.txt (survives across downloads,
    // which the per-download steam_debug.txt does not) and mirrored into the active download log.
    public enum SteamStatus { CONNECTING, ONLINE, SIGNED_IN_ELSEWHERE, OFFLINE, SIGNED_OUT, PAUSED_FOR_GAME }
    private volatile SteamStatus status = SteamStatus.OFFLINE;
    public SteamStatus getStatus() { return status; }

    /** Set the indicator state; on a real change, log it and emit "SteamStatus:<NAME>" for the pill. */
    private void setStatus(SteamStatus s, String reason) {
        SteamStatus prev = status;
        if (prev == s) return;
        status = s;
        slog(prev + " -> " + s + "  (" + reason + ")");
        emit("SteamStatus:" + s.name());
        // Mirror the honest connection state into the foreground-service notification so the FGS is
        // a legitimately-ongoing, TRUTHFUL indicator (not a frozen "Connecting…" string). Static
        // no-op when the service isn't running; guarded so any class-load/order issue on this path
        // can never break the pill. (A live download temporarily overrides this with a "Downloading
        // … N%" line from SteamDepotDownloader, which reverts here via refreshFgsStatus() on finish.)
        try { SteamForegroundService.setStatusText(fgsTextFor(s)); }
        catch (Throwable ignored) {}
    }

    /** Notification text for each connection state — the FGS's honest one-liner. */
    private static String fgsTextFor(SteamStatus s) {
        switch (s) {
            case ONLINE:              return "Steam: Online";
            case CONNECTING:          return "Connecting to Steam…";
            case SIGNED_IN_ELSEWHERE: return "Signed in elsewhere";
            case SIGNED_OUT:          return "Signed out";
            case PAUSED_FOR_GAME:     return "Paused — game session active";
            case OFFLINE:
            default:                  return "Offline";
        }
    }

    /**
     * Re-assert the CURRENT connection status into the FGS notification. Called by
     * SteamDepotDownloader when a download ends, to revert the transient "Downloading … N%" text
     * back to the honest connection state. Static no-op when the service isn't running.
     */
    public void refreshFgsStatus() {
        try { SteamForegroundService.setStatusText(fgsTextFor(status)); }
        catch (Throwable ignored) {}
    }

    /** Persistent, append-only session log so a mid/between-download LogonSessionReplaced is never lost. */
    private File sessionLogFile = null;
    private void slog(String rawMsg) {
        // Scrub username/email/token before this line touches any shared diagnostic file.
        String msg = SteamLogRedactor.redact(rawMsg);
        Log.i(TAG, "STATUS " + msg);
        try {
            if (sessionLogFile == null && appContext != null) {
                File dir = appContext.getExternalFilesDir(null);
                if (dir != null) sessionLogFile = new File(dir, "steam_session.txt");
            }
            if (sessionLogFile != null) {
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                try (BufferedWriter w = new BufferedWriter(new FileWriter(sessionLogFile, true))) {
                    w.write("[" + ts + "] " + msg + "\n");
                }
            }
        } catch (Exception ignored) {}
        // Mirror into the active download debug log (no-op when no download log is open) so a
        // download's steam_debug.txt carries the session-transition context inline. (dlog re-redacts.)
        try { SteamDepotDownloader.INSTANCE.mirrorSessionLine("[STATUS] " + msg); }
        catch (Throwable ignored) {}
    }

    /**
     * User-tapped the status pill to recover. Safe to call from the main thread — connect() and
     * loginWithToken() both post their network I/O to the pump thread. Bounded by the existing
     * guards; does nothing if there is no saved token (user must sign in interactively).
     */
    public void reconnectNow() {
        if (realSteamSuspended) {
            Log.i(REALSTEAM_TAG, "reconnectNow ignored — app session suspended for in-game real-Steam session");
            return;
        }
        loggingOut = false;
        logoffRecoveryAttempts = 0;
        reconnectAttempts = 0;
        rustTokenRejected = false;
        if (!isLoggedInPrefs()) return;
        setStatus(SteamStatus.CONNECTING, "user tapped reconnect");
        if (rustEngine) { rustConnect("reconnectNow"); return; }
        if (!connected) {
            connect();                                              // onConnected auto-logs-in
        } else if (!loggedIn) {
            loginWithToken(pGet("username", ""), pGet("refresh_token", ""));
        }
    }

    // --- Real-Steam (SteamLite) in-game session hand-off ------------------------------------------
    // A launchMode=RealSteam game logs the SAME account into genuine Steam from inside the container
    // (the steam.exe agent). If this repository keeps its own CM session up meanwhile, Steam sees two
    // sessions on one account: VAC Source titles tolerate the tug-of-war, live-service titles do not
    // ("could not connect to Steam servers" / "INCORRECT VERSION"), and the agent's LaunchApp can stall
    // (black screen). So while the game holds the account we take our session DOWN and pin every
    // reconnect path (user pill tap, FGS restart, foreground ON_START, network self-heal, involuntary
    // logoff recovery, download retry) shut. Credentials are untouched — prefs keep the refresh token,
    // username and steamId64 — so the plan can still be built and the next resume re-logs-on from them.
    // The flag is process-local: a process restart (the normal game-exit path restarts the app) or a
    // crash can never leave the app permanently offline.
    private static final String REALSTEAM_TAG = "BH_REALSTEAM";
    private volatile boolean realSteamSuspended = false;

    /** True while the app's own CM session is deliberately down because a real-Steam game holds the account. */
    public boolean isSuspendedForRealSteam() { return realSteamSuspended; }

    /**
     * Take the app's own CM session down for the duration of an in-container real-Steam session.
     * Idempotent. Safe from any non-UI thread (the disconnect closes the socket inline, like the FGS
     * teardown does); credentials are kept. Status becomes {@link SteamStatus#PAUSED_FOR_GAME}.
     */
    public void suspendForRealSteam() {
        if (realSteamSuspended) {
            Log.i(REALSTEAM_TAG, "suspendForRealSteam: already suspended");
            return;
        }
        realSteamSuspended = true;   // set FIRST so every reconnect path sees it before the socket drops
        Log.i(REALSTEAM_TAG, "suspending app CM session for in-game real-Steam session (connected="
                + connected + " loggedIn=" + loggedIn + " status=" + status + ")");
        // Neutralise every retry budget/intent so nothing re-drives a connect once the socket closes.
        loggingOn.set(false);
        forceReconnect = false;
        reconnectAttempts = 0;
        logoffRecoveryAttempts = 0;
        try { disconnect(); }
        catch (Throwable t) { Log.w(REALSTEAM_TAG, "disconnect during suspend threw", t); }
        // disconnect() stops the pump, so an in-flight connect() can never resolve — clear its latch or
        // the post-game connect() would be skipped as "already connecting" forever.
        connecting.set(false);
        setStatus(SteamStatus.PAUSED_FOR_GAME, "suspended for in-game real-Steam session");
    }

    /**
     * Release the real-Steam hold and bring the app's own CM session back (re-logon from the saved
     * token via {@link #reconnectNow()}). Idempotent — a no-op when not suspended. Safe from any
     * thread (network I/O is posted to the pump). When {@code awaitLoggedInMs > 0} the CALLER's thread
     * (never the UI thread) blocks up to that long for the fresh logon, so exit-time work that rides
     * the CM session (achievement sync-back, Steam Cloud upload) finds it live.
     *
     * @return true if the session is logged in when this returns.
     */
    public boolean resumeAfterRealSteam(long awaitLoggedInMs) {
        if (!realSteamSuspended) return connected && loggedIn;
        realSteamSuspended = false;
        boolean saved = isLoggedInPrefs();
        Log.i(REALSTEAM_TAG, "resuming app CM session after real-Steam game (savedSession=" + saved + ")");
        if (!saved) {
            setStatus(SteamStatus.SIGNED_OUT, "resumed after real-Steam game — no saved session");
            return false;
        }
        reconnectNow();
        if (awaitLoggedInMs > 0) {
            long deadline = System.currentTimeMillis() + awaitLoggedInMs;
            while (!loggedIn && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(150); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        return loggedIn;
    }

    /**
     * Register a default-network availability callback so the CM session self-heals in the background
     * the moment connectivity returns (see the field block near reconnectAttempts). Idempotent — a
     * no-op if already registered — and fully wrapped so any ConnectivityManager quirk can never crash
     * startup or the pump. Called from startPump() (primary) and re-armed from loginWithToken() so a
     * re-login after a logout that unregistered it (while the pump kept running) re-attaches it.
     */
    private void registerNetworkCallback() {
        if (appContext == null) return;
        synchronized (networkCallbackLock) {
            if (networkCallback != null) return;   // already registered — idempotent
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return;
                ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) {
                        onNetworkAvailable("onAvailable");
                    }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        if (caps != null
                                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            onNetworkAvailable("validated");
                        }
                    }
                };
                cm.registerDefaultNetworkCallback(cb);   // minSdk 26 >= API 24 requirement
                networkCm = cm;
                networkCallback = cb;
                Log.i(TAG, "Network availability callback registered (background self-heal)");
            } catch (Throwable t) {
                Log.w(TAG, "registerNetworkCallback failed — background self-heal disabled", t);
                networkCallback = null;
                networkCm = null;
            }
        }
    }

    /** Unregister the network-availability callback. Idempotent; safe from any thread. */
    private void unregisterNetworkCallback() {
        synchronized (networkCallbackLock) {
            ConnectivityManager cm = networkCm;
            ConnectivityManager.NetworkCallback cb = networkCallback;
            networkCallback = null;
            networkCm = null;
            if (cm != null && cb != null) {
                try { cm.unregisterNetworkCallback(cb); }
                catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Network became available while we may be stranded — self-heal the Steam session by re-driving
     * the exact recovery reconnectNow() performs. Runs on a ConnectivityManager callback thread; both
     * connect() and loginWithToken() post their network I/O to the pump, so calling reconnectNow()
     * from here is safe (same as the main-thread pill tap).
     *
     * Acts ONLY when it makes sense, and is idempotent / race-safe with the single-flight logon
     * guards:
     *   - pump running (pumping) and NOT a deliberate sign-out (loggingOut)
     *   - a saved session exists (isLoggedInPrefs) — otherwise the user must sign in interactively
     *   - not already healthy (connected && loggedIn)
     *   - no connect() in flight (connecting) and no logon in flight (loggingOn within its stall window)
     *   - debounced: ignore a duplicate within NETWORK_RECONNECT_DEBOUNCE_MS of the last kick
     * Resets reconnectAttempts so the 5-attempt cap can never permanently strand us.
     */
    private void onNetworkAvailable(String source) {
        try {
            if (!pumping.get()) return;                 // pump down (disconnected/stopped) — nothing to heal
            if (realSteamSuspended) return;             // a real-Steam game holds the account — do not tug
            if (loggingOut) return;                     // respect a deliberate sign-out
            if (!isLoggedInPrefs()) return;             // no saved token — user must sign in
            if (connected && loggedIn) return;          // already healthy
            if (connecting.get()) return;               // a connect() is already in flight
            long now = System.currentTimeMillis();
            if (loggingOn.get() && (now - logonStartedAt) < LOGON_STALL_MS) return;    // logon in flight
            if ((now - lastNetworkReconnectAt) < NETWORK_RECONNECT_DEBOUNCE_MS) return; // debounce burst
            lastNetworkReconnectAt = now;
            Log.i(TAG, "Network available (" + source + ") — self-healing Steam session");
            reconnectAttempts = 0;   // clear the auto-reconnect cap so it can't permanently strand us
            reconnectNow();          // reuse the existing recovery entry point (resets caps + connect()/relogin)
        } catch (Throwable t) {
            Log.w(TAG, "onNetworkAvailable handler failed", t);
        }
    }

    private void onDisconnected(DisconnectedCallback cb) {
        boolean forced = forceReconnect;
        forceReconnect = false;
        Log.i(TAG, "Disconnected (userInitiated=" + cb.isUserInitiated() + ", forced=" + forced
                + ", attempt=" + reconnectAttempts + ")");
        connected = false;
        loggedIn  = false;
        connecting.set(false);
        loggingOn.set(false);   // any in-flight logon died with the socket
        // Suspended for an in-game real-Steam session: this is the disconnect WE asked for (or a late
        // callback drained after it). Hold the paused state — no reconnect, no OFFLINE flip.
        if (realSteamSuspended) {
            setStatus(SteamStatus.PAUSED_FOR_GAME, "disconnected — paused for in-game real-Steam session");
            return;
        }
        // Reconnect on an involuntary socket drop, OR when we deliberately forced a reconnect to
        // recover from a clean CM logoff (onLoggedOff) — the latter arrives as "user-initiated".
        if ((forced || !cb.isUserInitiated()) && pumping.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            long delayMs = reconnectAttempts * 2000L;  // 2s, 4s, 6s, 8s, 10s
            setStatus(SteamStatus.CONNECTING, "auto-reconnect attempt " + reconnectAttempts);
            Log.i(TAG, "Auto-reconnect in " + delayMs + "ms (attempt " + reconnectAttempts + ")");
            if (pumpHandler != null) {
                pumpHandler.postDelayed(() -> {
                    if (pumping.get() && !connected && !realSteamSuspended) {
                        Log.i(TAG, "Auto-reconnect: calling connect()");
                        steamClient.connect();
                    }
                }, delayMs);
            }
        } else {
            reconnectAttempts = 0;
            setStatus(SteamStatus.OFFLINE, "disconnected");
            emit("Disconnected");
        }
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        loggingOn.set(false);   // this logon has resolved (success or failure) — release the guard
        if (cb.getResult() != EResult.OK) {
            Log.w(TAG, "Login failed: " + cb.getResult());
            lastSessionStatus = "LoginFailed:" + cb.getResult().name();
            // A token-rejection result won't self-heal (user must re-auth) -> SIGNED_OUT; anything
            // else (transient) stays CONNECTING so the pill shows we're still trying.
            String rn = cb.getResult().name();
            boolean rejected = rn.contains("Password") || rn.contains("Expired")
                    || rn.contains("Denied") || rn.contains("Revoked") || rn.contains("Invalid");
            setStatus(rejected ? SteamStatus.SIGNED_OUT : SteamStatus.CONNECTING, "login failed:" + rn);
            emit("LoginFailed:" + cb.getResult().name());
            return;
        }

        pPut("cell_id", cb.getCellID());
        long sid64 = cb.getClientSteamID().convertToUInt64();
        pPut("steam_id_64", sid64);
        pPut("account_id", (int)(sid64 & 0xFFFFFFFFL));

        loggedIn = true;
        logoffRecoveryAttempts = 0;   // fresh session established — reset involuntary-logoff recovery budget
        lastSessionStatus = "LoggedIn";
        setStatus(SteamStatus.ONLINE, "logged in");
        emit("LoggedIn:" + sid64);
        Log.i(TAG, SteamLogRedactor.redact("Logged in as " + pGet("username", "")));
    }

    private void onLoggedOff(LoggedOffCallback cb) {
        EResult r = cb.getResult();
        Log.i(TAG, "Logged off: " + r);
        lastSessionStatus = "LoggedOff:" + r.name();

        // SELF-REPLACEMENT: a LogonSessionReplaced landing right after OUR OWN logon is the eviction
        // of the session WE just replaced — our newer session is the live one. The single-flight
        // guard should prevent a second logon, but a reconnect race (onConnected relogin overlapping
        // ensureLoggedIn) can still slip one through. Treat it as a no-op: do NOT clear loggedIn or
        // emit LoggedOut, or we clobber the good LoggedOn and get stuck connected-but-not-logged-in
        // (the exact bug that made every download fail "session not ready").
        if (r == EResult.LogonSessionReplaced && !loggingOut
                && (System.currentTimeMillis() - lastSelfLogonAt) < SELF_REPLACE_WINDOW_MS
                && isLoggedInPrefs()) {
            Log.i(TAG, "LogonSessionReplaced within self-logon window -> ignoring (our newer session is live)");
            loggingOn.set(false);
            setStatus(SteamStatus.ONLINE, "self-replace ignored — newer session live");
            return;   // leave loggedIn as-is; the newer session's LoggedOn owns it
        }

        loggedIn = false;
        loggingOn.set(false);

        // Suspended for an in-game real-Steam session: whatever this logoff is (our own teardown, or
        // the agent's login replacing a session that hadn't fully closed yet), do NOT recover — the
        // game owns the account until resumeAfterRealSteam().
        if (realSteamSuspended) {
            setStatus(SteamStatus.PAUSED_FOR_GAME, "logged off — paused for in-game real-Steam session");
            return;
        }

        // User-initiated sign-out, or a logoff meaning the session is intentionally gone
        // (logged in elsewhere / session replaced by a DIFFERENT client) -> surface it, do NOT recover/loop.
        // We intentionally do NOT auto-reconnect here: a genuine different-client replacement means the
        // account is live elsewhere (e.g. desktop Steam), so relogging would start a logon tug-of-war.
        // The pill shows "Signed in elsewhere" and the user taps to reconnect once they've signed out there.
        if (loggingOut || r == EResult.LoggedInElsewhere || r == EResult.LogonSessionReplaced) {
            setStatus(loggingOut ? SteamStatus.SIGNED_OUT : SteamStatus.SIGNED_IN_ELSEWHERE,
                    loggingOut ? "user sign-out" : "replaced by another client: " + r.name());
            emit("LoggedOut");
            return;
        }

        // Otherwise this is an INVOLUNTARY logoff (e.g. EResult.Expired ~1h into a QR-approved
        // session). The socket is still up but the CM has ended our session, and depot downloads
        // ride that CM session, so they stall. Recover the way a socket drop already recovers:
        // force a reconnect so onConnected re-logs-on from the stored refresh token and mints a
        // fresh session. Bounded so a genuinely-dead token can't loop forever.
        if (pumping.get() && isLoggedInPrefs() && steamClient != null
                && logoffRecoveryAttempts < MAX_LOGOFF_RECOVERY) {
            logoffRecoveryAttempts++;
            setStatus(SteamStatus.CONNECTING, "involuntary logoff recovery " + logoffRecoveryAttempts);
            Log.i(TAG, "Involuntary logoff (" + r + ") -> forcing reconnect+relogin (recovery "
                    + logoffRecoveryAttempts + "/" + MAX_LOGOFF_RECOVERY + ")");
            forceReconnect = true;
            if (pumpHandler != null) pumpHandler.post(() -> { if (steamClient != null) steamClient.disconnect(); });
            else steamClient.disconnect();
        } else {
            Log.w(TAG, "Logged off (" + r + ") and not recovering (attempts=" + logoffRecoveryAttempts
                    + ") -> session needs re-auth");
            setStatus(SteamStatus.SIGNED_OUT, "logged off, needs re-auth: " + r.name());
            emit("SessionExpired");
            emit("LoggedOut");
        }
    }

    private void onLicenseList(LicenseListCallback cb) {
        // PUMP THREAD: only copy the payload out of the callback (it may be recycled once we return),
        // then hand the DB writes + PICS sync to the worker so runWaitCallbacks() returns immediately.
        final List<License> list = new ArrayList<>(cb.getLicenseList());
        Log.i(TAG, list.size() + " licenses received");
        runOnLibraryWorker(() -> {
            synchronized (licenses) {
                licenses.clear();
                licenses.addAll(list);
            }
            // Persist license records to DB
            SteamDatabase db = SteamDatabase.getInstance();
            db.clearLicenses();
            for (License lic : list) {
                long created = lic.getTimeCreated() != null ? lic.getTimeCreated().getTime() / 1000L : 0L;
                db.upsertLicense(lic.getPackageID(), created, 0, 0);
            }
            emit("LibraryProgress:0:" + list.size());
            syncPackages(list);
        });
    }

    /** Phase 4 step 1: request PICS product info for all owned packages. */
    private void syncPackages(List<License> licenseList) {
        if (steamApps == null) return;
        List<PICSRequest> pkgRequests = new ArrayList<>();
        for (License lic : licenseList) {
            pkgRequests.add(new PICSRequest(lic.getPackageID(), lic.getAccessToken()));
        }
        if (pkgRequests.isEmpty()) {
            emit("LibrarySynced:0");
            return;
        }
        syncPhase = SYNC_PACKAGES;
        pendingPackages.clear();
        Log.i(TAG, "PICS: requesting info for " + pkgRequests.size() + " packages");
        steamApps.picsGetProductInfo(Collections.emptyList(), pkgRequests, false);
    }

    /** Phase 4 step 2: seed the app-sync batch queue, then kick the first batch.
     *  WORKER THREAD (called from processPackages / syncLibrary). From here the sync advances one
     *  small batch at a time — see requestNextAppBatch() for why we no longer fetch all at once. */
    private void syncApps(List<Integer> appIds) {
        if (steamApps == null || appIds.isEmpty()) {
            syncPhase = SYNC_IDLE;
            emit("LibrarySynced:0");
            return;
        }
        // Reseed the queue + running counters. A mid-sync reconnect re-enters here (via
        // syncLibrary→syncPackages) and replaces any stale/paused queue wholesale, so a paused sync
        // is never lost or double-counted.
        remainingAppIds.clear();
        remainingAppIds.addAll(appIds);
        appSyncTotal     = appIds.size();
        appSyncProcessed = 0;
        appSyncPaused    = false;
        pendingApps.clear();
        Log.i(TAG, "PICS: app sync starting for " + appSyncTotal + " apps in batches of " + APP_SYNC_BATCH);
        requestNextAppBatch();
    }

    /**
     * WORKER THREAD: issue the next batch of app PICS requests. Polls up to APP_SYNC_BATCH ids off
     * remainingAppIds, sends ONE picsGetProductInfo for just that slice, and lets the response
     * (onPICSProductInfo → processApps) drive the following batch. Keeping each CM request small
     * stops the library sync from monopolising the shared TcpConnection and starving a concurrent
     * depot download's appinfo AsyncJob.
     *
     * PAUSE-DURING-DOWNLOAD: if a download is active, do NOT issue the next batch — park with the
     * queue intact (appSyncPaused) and return. The already-sent in-flight batch is ≤25 apps so it
     * drains fast; setDownloadActive(false) resumes us once the download releases the connection.
     */
    private void requestNextAppBatch() {
        if (steamApps == null) return;
        // Queue drained → the library sync is complete. Finish exactly as the old single-shot did.
        if (remainingAppIds.isEmpty()) {
            finishAppSync();
            return;
        }
        // A download owns the CM connection right now — yield to it and keep our place in the queue.
        if (downloadActive) {
            appSyncPaused = true;
            Log.i(TAG, "PICS app-sync paused (download active) — " + remainingAppIds.size() + " apps queued");
            return;
        }
        List<PICSRequest> appRequests = new ArrayList<>();
        for (int i = 0; i < APP_SYNC_BATCH && !remainingAppIds.isEmpty(); i++) {
            appRequests.add(new PICSRequest(remainingAppIds.poll()));
        }
        syncPhase = SYNC_APPS;
        pendingApps.clear();
        Log.i(TAG, "PICS: requesting info for " + appRequests.size() + " apps ("
                + remainingAppIds.size() + " remaining)");
        steamApps.picsGetProductInfo(appRequests, Collections.emptyList(), false);
    }

    /** WORKER THREAD: the batch queue is drained — close out the sync the way processApps used to,
     *  emitting the final LibrarySynced with the total app count accumulated across all batches. */
    private void finishAppSync() {
        syncPhase = SYNC_IDLE;
        pendingPackages.clear();
        pendingApps.clear();
        appSyncPaused = false;
        recordSyncTime();
        pPut("last_sync_engine", "javasteam");   // BL_STEAM_PICS: labels the next cross-engine diff
        Log.i(TAG, "Library sync complete: " + appSyncProcessed + " apps");
        emit("LibrarySynced:" + appSyncProcessed);
    }

    /**
     * Coordinate the background library sync with an active depot download. Called by
     * SteamDepotDownloader around a download: true while it owns the CM connection, false when it
     * releases it (success / failure / cancel / exception, from the download's finally).
     *
     * When set true the batch loop parks itself at the next batch boundary (see requestNextAppBatch);
     * when set false, if a sync is parked with work left and we're still logged in, resume it. The
     * resume runs on the libraryWorker so all queue mutation stays confined to that one thread — this
     * method itself is called from the download coroutine (Dispatchers.IO) and must not touch the
     * queue directly. Setting the flag is a cheap volatile write; no marshalling needed for that.
     */
    public void setDownloadActive(boolean active) {
        downloadActive = active;
        if (!active) {
            runOnLibraryWorker(() -> {
                if (appSyncPaused && !remainingAppIds.isEmpty() && loggedIn) {
                    appSyncPaused = false;
                    Log.i(TAG, "PICS app-sync resuming after download — " + remainingAppIds.size() + " apps queued");
                    requestNextAppBatch();
                }
            });
        }
    }

    /** Null-safe asString(): returns "" instead of null when a KeyValue has no value. */
    private static String kvStr(KeyValue kv) {
        String v = kv.asString();
        return v != null ? v : "";
    }

    /** Map Steam PICS flat genre IDs (numeric strings) to human-readable names. */
    private static String resolveGenreId(String id) {
        switch (id) {
            case "1":  return "Action";
            case "2":  return "Strategy";
            case "3":  return "RPG";
            case "4":  return "Casual";
            case "5":  return "Racing";
            case "6":  return "Sports";
            case "7":  return "Simulation";
            case "8":  return "Adventure";
            case "9":  return "Racing";
            case "18": return "Massively Multiplayer";
            case "23": return "Indie";
            case "25": return "Shooter";
            case "37": return "Free to Play";
            default:   return "";  // unknown IDs are hidden rather than shown as numbers
        }
    }

    /** Phase 4 step 3: handle PICS product info callbacks for packages and apps.
     *  PUMP THREAD: accumulate the (cheap) callback payload; when the response is complete, snapshot
     *  the accumulated PICSProductInfo values and hand the heavy parse + DB work to the worker so the
     *  pump keeps dispatching callbacks (including the depot manifest AsyncJob reply). The snapshot
     *  holds references to already-parsed PICSProductInfo objects, which survive after cb is recycled. */
    private void onPICSProductInfo(PICSProductInfoCallback cb) {
        if (syncPhase == SYNC_PACKAGES) {
            pendingPackages.putAll(cb.getPackages());
            if (!cb.isResponsePending()) {
                final List<PICSProductInfo> pkgs = new ArrayList<>(pendingPackages.values());
                runOnLibraryWorker(() -> processPackages(pkgs));
            }

        } else if (syncPhase == SYNC_APPS) {
            pendingApps.putAll(cb.getApps());
            if (!cb.isResponsePending()) {
                final List<PICSProductInfo> apps = new ArrayList<>(pendingApps.values());
                runOnLibraryWorker(() -> processApps(apps));
            }
        }
    }

    /** WORKER THREAD: resolve package PICS info into app IDs, persist license↔app mappings, then
     *  kick the apps sync. Runs off the pump (see onPICSProductInfo). */
    private void processPackages(List<PICSProductInfo> pkgs) {
        // All package info received — extract appIds and persist mappings
        SteamDatabase db = SteamDatabase.getInstance();
        List<Integer> appIds = new ArrayList<>();
        for (PICSProductInfo pkg : pkgs) {
            KeyValue appidsKv = pkg.getKeyValues().get("appids");
            List<KeyValue> children = appidsKv.getChildren();
            if (children != null) {
                for (KeyValue child : children) {
                    try {
                        String raw = child.getValue();
                        if (raw == null || raw.isEmpty()) continue;
                        int appId = Integer.parseInt(raw);
                        if (!appIds.contains(appId)) appIds.add(appId);
                        db.linkLicenseApp(pkg.getId(), appId);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        Log.i(TAG, "PICS packages resolved " + appIds.size() + " unique app IDs");
        emit("LibraryProgress:1:" + appIds.size());
        syncApps(appIds);
    }

    /** WORKER THREAD: parse app PICS info (name/icon/genres + depot-selection filter) and store games.
     *  Runs off the pump (see onPICSProductInfo). */
    private void processApps(List<PICSProductInfo> apps) {
        // All app info received — parse and store games
        SteamDatabase db = SteamDatabase.getInstance();
        // AppIds the account is licensed for — used to skip DLC depots the user doesn't own
        // (an owned game's depot list includes its DLC depots; selecting an UNOWNED one makes the
        // download engine try a depot it has no key for → "not available from this account" → 0 bytes
        // → the completion guard falsely fails the whole, complete, OWNED game). See appId 313830
        // "See No Evil": depot 320210 = its "Official Soundtrack" DLC the user didn't own.
        java.util.Set<Integer> licensedApps = new java.util.HashSet<>(db.getLicensedAppIds());
        int count = processAppsCore(apps, db, licensedApps);
        // Batch bookkeeping: this callback carried ONE batch (≤ APP_SYNC_BATCH apps). Add it to the
        // running total and emit progress as processed/total so the UI can show "Fetching N/372".
        appSyncProcessed += count;
        pendingApps.clear();
        emit("LibraryProgress:2:" + appSyncProcessed + ":" + appSyncTotal);
        Log.i(TAG, "PICS app batch parsed: +" + count + " (" + appSyncProcessed + "/" + appSyncTotal
                + " processed, " + remainingAppIds.size() + " queued)");
        // Drive the next batch — or finish when the queue is drained (finishAppSync emits
        // LibrarySynced). If a download became active meanwhile, this parks the sync instead of
        // issuing more CM traffic; setDownloadActive(false) later resumes it.
        requestNextAppBatch();
    }

    /**
     * WORKER THREAD: parse + store a batch of app PICS product infos (type filter + depot-selection
     * filter + beta branches). Extracted verbatim from {@link #processApps} so the single-app RealSteam
     * update refresh ({@link #refreshAppProductInfo}) resolves the LIVE manifest ids + branch build ids
     * through the EXACT same parse the library sync uses. Returns how many apps were stored. Carries NO
     * batch bookkeeping (progress / queue / sync-time) — the caller owns that, so a one-off refresh
     * never emits a spurious LibrarySynced or advances the periodic-sync clock.
     */
    private int processAppsCore(List<PICSProductInfo> apps, SteamDatabase db,
                                java.util.Set<Integer> licensedApps) {
        int count = 0;
        for (PICSProductInfo app : apps) {
            if (processAppKv(app.getId(), app.getKeyValues(), db, licensedApps)) count++;
        }
        return count;
    }

    /**
     * WORKER THREAD: parse + store ONE app's PICS product-info KeyValue tree (the per-app body of
     * {@link #processAppsCore}, extracted verbatim). Engine-agnostic: the JavaSteam path hands it the
     * {@code PICSProductInfo} KeyValues, the Rust engine path ({@link #rustSyncLibrary}) hands it the
     * SAME tree rebuilt from the engine's JSON appinfo (see {@link #jsonToKeyValue}) — so both engines
     * produce byte-identical {@code steam_games} / {@code depot_manifests} / {@code steam_branches} /
     * {@code steam_dlc} rows. Returns true when the app was stored, false when it was filtered/skipped.
     */
    boolean processAppKv(int appId, KeyValue root, SteamDatabase db, java.util.Set<Integer> licensedApps) {
                    try {
                        KeyValue common = root.get("common");
                        // "type" is absent on some entries (tools, hardware, etc.) — skip those
                        String type = kvStr(common.get("type")).toLowerCase();
                        // Allowlisted appIds bypass the type filter entirely (see LIBRARY_ALLOWLIST).
                        boolean allowlisted = LIBRARY_ALLOWLIST.contains(appId);
                        // Skip non-playable app types
                        if (!allowlisted
                                && ("tool".equals(type) || "hardware".equals(type)
                                || "music".equals(type) || "video".equals(type)
                                || "advertising".equals(type))) return false;
                        // Accept "game", "dlc", "application", "demo", "beta", ""
                        // Empty type means PICS didn't return common section — skip
                        if (type.isEmpty() && !allowlisted) return false;

                        String name       = kvStr(common.get("name"));
                        String icon       = kvStr(common.get("icon"));
                        String clientIcon = kvStr(common.get("clienticon"));
                        if (icon.isEmpty()) icon = clientIcon;

                        // Developer
                        String developer = kvStr(common.get("developer"));

                        // Metacritic score (0-100, 0 means not available)
                        int metacriticScore = 0;
                        String metaStr = kvStr(common.get("metacritic").get("score"));
                        if (!metaStr.isEmpty()) {
                            try { metacriticScore = Integer.parseInt(metaStr); }
                            catch (NumberFormatException ignored) {}
                        }

                        // Genres — children keyed "0","1",... each with a "description" subkey.
                        // When description is absent, the value is a raw numeric genre ID — resolve it.
                        StringBuilder genreSb = new StringBuilder();
                        List<KeyValue> genreChildren = common.get("genres").getChildren();
                        if (genreChildren != null) {
                            for (KeyValue g : genreChildren) {
                                String gname = kvStr(g.get("description"));
                                if (gname.isEmpty()) gname = resolveGenreId(kvStr(g));
                                if (!gname.isEmpty()) {
                                    if (genreSb.length() > 0) genreSb.append(", ");
                                    genreSb.append(gname);
                                }
                            }
                        }

                        // Collect depot IDs, manifest IDs, and sizes from the "depots" section.
                        //
                        // CRITICAL: only count depots the DepotDownloader will actually FETCH.
                        // Summing every depot child inflates the size ~2x on multi-platform games
                        // (it adds macOS/Linux + per-language + optional depots that never download).
                        // We mirror JavaSteam DepotDownloader.getDepotInfo()'s depot-selection filter
                        // exactly, for the flags our download path passes in SteamDepotDownloader:
                        //   os="windows", downloadAllArchs=true, language=null(→"english"),
                        //   downloadAllPlatforms=false, downloadAllLanguages=false, lowViolence=false.
                        // Rule (only applied when depots/{id}/config exists):
                        //   - oslist       : if present & non-blank, must contain "windows"
                        //   - language     : if present & non-blank, must equal "english"
                        //   - lowviolence  : if set (1/true), exclude
                        //   - osarch       : SKIPPED — we pass downloadAllArchs=true (never filters)
                        // A depot with no config, or empty oslist/language, is shared/common content
                        // and is always included. A depot with no public manifest can't be
                        // downloaded, so it is skipped entirely (contributes nothing).
                        StringBuilder depotSb = new StringBuilder();
                        java.util.List<Integer> includedDlcIds = new java.util.ArrayList<>();  // owned DLC bundled with the game
                        // The game's DLC appIds (extended/listofdlc). A depot whose id is in this set is
                        // a DLC depot (its depot id == the DLC appId) — see the depot loop for handling.
                        java.util.Set<Integer> dlcSet = new java.util.HashSet<>();
                        String listOfDlc = kvStr(root.get("extended").get("listofdlc"));
                        if (!listOfDlc.isEmpty()) {
                            for (String s : listOfDlc.split(",")) {
                                try { dlcSet.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
                            }
                        }
                        long totalSize     = 0L;   // uncompressed (install) total — SELECTED depots only
                        long totalDownload = 0L;   // compressed (network) total — SELECTED depots only
                        int selectedCount = 0, skippedCount = 0;
                        KeyValue depotsKv = root.get("depots");
                        List<KeyValue> depotChildren = depotsKv.getChildren();
                        if (depotChildren != null) {
                            for (KeyValue d : depotChildren) {
                                int depotId;
                                try { depotId = Integer.parseInt(d.getName()); }
                                catch (NumberFormatException ignored) { continue; } // "branches", "baselanguages", …

                                // Must have a public manifest to be downloadable.
                                KeyValue pub = d.get("manifests").get("public");
                                String manifestGid = kvStr(pub.get("gid"));
                                if (manifestGid.isEmpty()) manifestGid = kvStr(d.get("manifest")); // older format
                                if (manifestGid.isEmpty()) { skippedCount++; continue; }

                                // Mirror the DepotDownloader oslist/language/lowviolence filter.
                                KeyValue config = d.get("config");
                                String oslist = kvStr(config.get("oslist"));
                                if (!oslist.isEmpty()) {
                                    boolean windows = false;
                                    for (String os : oslist.split(",")) {
                                        if ("windows".equals(os.trim())) { windows = true; break; }
                                    }
                                    if (!windows) {
                                        Log.d(TAG, "app " + appId + " skip depot " + depotId
                                                + " oslist='" + oslist + "' (not windows)");
                                        skippedCount++; continue;
                                    }
                                }
                                String lang = kvStr(config.get("language")).trim();
                                if (!lang.isEmpty() && !"english".equalsIgnoreCase(lang)) {
                                    Log.d(TAG, "app " + appId + " skip depot " + depotId
                                            + " language='" + lang + "' (not english)");
                                    skippedCount++; continue;
                                }
                                String lv = kvStr(config.get("lowviolence")).trim();
                                if (lv.equals("1") || lv.equalsIgnoreCase("true")) {
                                    Log.d(TAG, "app " + appId + " skip depot " + depotId + " lowviolence");
                                    skippedCount++; continue;
                                }
                                // DLC handling. Steam does NOT tag a depot's config with dlcappid here;
                                // instead the game lists its DLC appIds in extended/listofdlc, and each
                                // DLC's depot id == that DLC's appId (verified: Just Cause 3 depots
                                // 388290.. == its DLC appIds; See No Evil depot 320210 == its soundtrack
                                // DLC). So a depot whose id is in the game's DLC set is a DLC depot:
                                //   - not licensed → SKIP (unowned DLC; else the engine tries a depot it
                                //     has no key for → 0 bytes → false "incomplete" on the owned game).
                                //   - licensed → keep + record for the detail-page "Includes DLC:" line.
                                if (dlcSet.contains(depotId)) {
                                    if (!licensedApps.contains(depotId)) {
                                        Log.d(TAG, "app " + appId + " skip DLC depot " + depotId + " (not owned)");
                                        skippedCount++; continue;
                                    }
                                    if (!includedDlcIds.contains(depotId)) includedDlcIds.add(depotId);
                                }

                                // Selected — count it.
                                if (depotSb.length() > 0) depotSb.append(',');
                                depotSb.append(depotId);
                                selectedCount++;

                                // Uncompressed size: modern PICS at manifests/public/size,
                                // older format at top-level maxsize.
                                String sizeStr = kvStr(pub.get("size"));
                                if (sizeStr.isEmpty()) sizeStr = kvStr(d.get("maxsize"));
                                long depotSize = 0L;
                                if (!sizeStr.isEmpty()) {
                                    try { depotSize = Long.parseLong(sizeStr); totalSize += depotSize; }
                                    catch (NumberFormatException ignored) {}
                                }
                                // Compressed download size: manifests/public/download.
                                String dlStr = kvStr(pub.get("download"));
                                if (!dlStr.isEmpty()) {
                                    try { totalDownload += Long.parseLong(dlStr); }
                                    catch (NumberFormatException ignored) {}
                                }

                                if (!manifestGid.isEmpty()) {
                                    try {
                                        long manifestId = Long.parseLong(manifestGid);
                                        db.upsertDepotManifest(appId, depotId, manifestId, depotSize);
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }

                        // Beta branches — depots/branches/* (skipped by the numeric-name depot loop
                        // above). Each child is a branch: name = branch id ("public", "beta", …) with
                        // sub-keys pwdrequired (0/1), buildid, timeupdated, description. Clear the app's
                        // prior rows first, then upsert each so removed branches don't linger. Drives
                        // the detail-page branch selector.
                        db.clearBranches(appId);
                        List<KeyValue> branchChildren = depotsKv.get("branches").getChildren();
                        if (branchChildren != null) {
                            for (KeyValue b : branchChildren) {
                                String branchName = b.getName();
                                if (branchName == null || branchName.isEmpty()) continue;
                                boolean pwdReq = "1".equals(kvStr(b.get("pwdrequired")).trim());
                                long buildId = 0L, timeUpdated = 0L;
                                try { buildId = Long.parseLong(kvStr(b.get("buildid")).trim()); }
                                catch (NumberFormatException ignored) {}
                                try { timeUpdated = Long.parseLong(kvStr(b.get("timeupdated")).trim()); }
                                catch (NumberFormatException ignored) {}
                                db.upsertBranch(appId, branchName, pwdReq, buildId, timeUpdated,
                                        kvStr(b.get("description")));
                            }
                        }

                        // Stash the compressed (network) total in memory for the dual-color
                        // download/install progress bar. Not persisted (avoids a schema change);
                        // a cache miss on a later session falls back to an estimate.
                        downloadSizeByApp.put(appId, totalDownload);
                        Log.i(TAG, "app " + appId + " depots: selected=" + selectedCount
                                + " skipped=" + skippedCount + " install=" + totalSize
                                + "B download=" + totalDownload + "B");

                        db.upsertGame(appId, name, icon, totalSize, depotSb.toString(), type,
                                developer, metacriticScore, genreSb.toString());
                        // Drop any depot rows no longer selected (e.g. an unowned DLC depot a
                        // pre-filter sync stored) so the completion guard can't fail on them.
                        db.pruneDepots(appId, depotSb.toString());
                        // Record owned DLC bundled with the game (for the "Includes DLC:" line).
                        StringBuilder dlcCsv = new StringBuilder();
                        for (int id : includedDlcIds) {
                            if (dlcCsv.length() > 0) dlcCsv.append(',');
                            dlcCsv.append(id);
                        }
                        db.setIncludedDlc(appId, dlcCsv.toString());
                        // Broadened DLC CATALOGUE for the detail-page DLC TAB: EVERY DLC the game
                        // lists in extended/listofdlc, split owned/unowned. included_dlc above stays
                        // the depot-bundled owned subset that drives the download picker/size — this
                        // is DISPLAY-ONLY and never changes what downloads. A DLC that's owned but has
                        // NO base-game depot (its content is in shared base depots or under its own
                        // app — e.g. Risk of Rain 2's Survivors of the Void / Seekers of the Storm)
                        // was invisible before; it's recorded here so the tab can show it. Its precise
                        // name + app-vs-entitlement kind are filled lazily by resolveOwnedDlc() on open
                        // (a DLC's real name lives in ITS OWN app PICS, not the base game's).
                        java.util.List<SteamDatabase.DlcRow> dlcRows = new java.util.ArrayList<>();
                        for (int dlcId : dlcSet) {
                            boolean owned = licensedApps.contains(dlcId);
                            String kind;
                            if (!owned)                              kind = "unowned";
                            else if (includedDlcIds.contains(dlcId)) kind = "depot"; // installs w/ game
                            else                                     kind = "";      // app|entitlement TBD
                            // Best-effort name from the DLC's own already-synced library row; music-type
                            // DLC are skipped by the type filter above → left blank for the PICS resolve.
                            String nm = "";
                            try {
                                SteamDatabase.GameRow gr = db.getGame(dlcId);
                                if (gr != null && gr.name != null) nm = gr.name;
                            } catch (Exception ignored) {}
                            dlcRows.add(new SteamDatabase.DlcRow(appId, dlcId, nm, owned, kind));
                        }
                        db.replaceDlcSet(appId, dlcRows);
                        return true;
                    } catch (Exception e) {
                        Log.w(TAG, "Skipping app " + appId + ": " + e.getMessage());
                        return false;
                    }
    }

    /** Handle depot decryption key callback. Stores key in memory for SteamDepotDownloader. */
    private void onDepotKey(DepotKeyCallback cb) {
        if (cb.getResult() == EResult.OK) {
            depotKeys.put(cb.getDepotID(), cb.getDepotKey());
            Log.i(TAG, "Depot key received for depot " + cb.getDepotID());
            emit("DepotKeyReady:" + cb.getDepotID());
        } else {
            Log.w(TAG, "Depot key request failed for depot " + cb.getDepotID() + ": " + cb.getResult());
            emit("DepotKeyFailed:" + cb.getDepotID() + ":" + cb.getResult().name());
        }
    }

    // Callback handlers for manifest codes and CDN tokens will be wired in once
    // the correct JavaSteam class names are confirmed from the JAR dump in CI.

    /** Trigger a full library re-sync (e.g. from pull-to-refresh). Safe to call from any thread. */
    public void syncLibrary() {
        if (rustEngine) { rustSyncLibrary("syncLibrary"); return; }
        List<License> copy;
        synchronized (licenses) { copy = new ArrayList<>(licenses); }
        if (copy.isEmpty()) {
            Log.w(TAG, "syncLibrary() called but license list is empty");
            return;
        }
        // picsGetProductInfo() does network I/O and must run off the main thread; route it to the
        // library worker (never the pump — the ensuing PICS parse + DB work would block callbacks).
        runOnLibraryWorker(() -> syncPackages(copy));
    }

    /**
     * Force a FRESH single-app PICS product-info fetch and reparse it into the DB — refreshing this
     * app's {@code depot_manifests} (manifest ids + sizes), {@code steam_branches} (branch build ids)
     * and {@code steam_games} row through the EXACT same parse the library sync runs (processAppsCore).
     *
     * Used by the RealSteam update-on-launch path ({@code SteamGameUpdater}) so an update/verify pass
     * resolves the LIVE manifest ids + current branch build id BEFORE the download — matching what
     * GameNative's {@code isUpdateOrVerify} does. Without it, the update pass would compare/stamp
     * against whatever stale build the DB last synced.
     *
     * BLOCKS the CALLING thread (the update worker — never the pump) up to {@code timeoutMs}. Issues no
     * batch bookkeeping, so it never emits a spurious LibrarySynced or advances the periodic-sync clock.
     * Best-effort: returns false (caller proceeds — the downloader still resolves live manifests itself)
     * when not signed in, the PICS query fails/times out, or the app returns metadata-only product info.
     *
     * @return true if the app's product info was fetched and stored.
     */
    public boolean refreshAppProductInfo(int appId, long timeoutMs) {
        if (!isSessionLoggedOn()) {
            Log.i(TAG, "refreshAppProductInfo(" + appId + "): skipped (not signed in)");
            return false;
        }
        try {
            KeyValue root = fetchProductInfoKv(Collections.singletonList(appId), timeoutMs).get(appId);
            // Metadata-only / missing-token response → no depot data to reparse. Leave the DB as-is.
            if (root == null || root.getChildren() == null || root.getChildren().isEmpty()) {
                Log.w(TAG, "refreshAppProductInfo(" + appId + "): no populated product info");
                return false;
            }
            SteamDatabase db = SteamDatabase.getInstance();
            boolean stored = processAppKv(appId, root, db, new java.util.HashSet<>(db.getLicensedAppIds()));
            Log.i(TAG, "refreshAppProductInfo(" + appId + "): refreshed product info (stored=" + stored + ")");
            return stored;
        } catch (Exception e) {
            Log.w(TAG, "refreshAppProductInfo(" + appId + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** Parse a base game's extended/listofdlc KeyValue into a set of DLC appIds. */
    private static java.util.Set<Integer> parseListOfDlc(KeyValue root) {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        String s = kvStr(root.get("extended").get("listofdlc"));
        if (!s.isEmpty()) {
            for (String part : s.split(",")) {
                try { set.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        return set;
    }

    /** Numeric depot ids under an app's "depots" section that carry a public manifest — i.e. the app
     *  ships downloadable content. Used to classify a DLC as "installs with game" (has content depots)
     *  vs entitlement-only. Ignores non-depot children ("branches", "baselanguages", …). */
    private static java.util.Set<Integer> collectPublicDepotIds(KeyValue root) {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        List<KeyValue> children = root.get("depots").getChildren();
        if (children != null) {
            for (KeyValue d : children) {
                int depotId;
                try { depotId = Integer.parseInt(d.getName()); }
                catch (NumberFormatException ignored) { continue; }
                String gid = kvStr(d.get("manifests").get("public").get("gid"));
                if (gid.isEmpty()) gid = kvStr(d.get("manifest"));   // older format
                if (!gid.isEmpty()) set.add(depotId);
            }
        }
        return set;
    }

    /**
     * Best-effort resolve of the detail-page DLC TAB for a base game — DISPLAY-ONLY: it populates the
     * steam_dlc catalogue and never touches steam_games.included_dlc, so the download picker/size and
     * the "Includes DLC:" line keep working exactly as before.
     *
     * (1) SELF-HEAL: if the base's DLC set was never resolved (e.g. right after the v10 schema bump,
     *     before a full library re-sync), fetch the base game's PICS once to populate its
     *     extended/listofdlc set + per-DLC ownership + depot-bundled flag.
     * (2) NAME/KIND: for owned DLC still missing a cached display name or a resolved kind, batch a PICS
     *     product-info request for those appIds — a DLC's real name is in ITS OWN app common/name, and
     *     its own app's depots section tells us whether it ships downloadable content ("Installs with
     *     game", kind=app) or is entitlement-only ("Owned — no separate download", kind=entitlement).
     *     Results are cached, so a repeat open does no network.
     *
     * BLOCKS the calling thread on PICS futures up to {@code timeoutMs} — call from a worker/IO thread,
     * NEVER the pump (the pump keeps dispatching callbacks so the futures resolve). Fully wrapped: any
     * failure leaves the DB as-is. Returns true if anything was (re)resolved.
     */
    public boolean resolveOwnedDlc(int baseAppId, long timeoutMs) {
        SteamDatabase db = SteamDatabase.getInstance();
        boolean changed = false;
        final boolean online = isSessionLoggedOn();
        try {
            java.util.Set<Integer> licensedApps = new java.util.HashSet<>(db.getLicensedAppIds());

            // (1) Self-heal an unresolved base from a fresh single-app PICS fetch.
            if (!db.isDlcResolved(baseAppId) && online) {
                try {
                    KeyValue root = fetchProductInfoKv(Collections.singletonList(baseAppId), timeoutMs).get(baseAppId);
                    if (root != null) {
                        java.util.Set<Integer> dlcSet   = parseListOfDlc(root);
                        java.util.Set<Integer> pubDepots = collectPublicDepotIds(root);
                        java.util.List<SteamDatabase.DlcRow> rows = new java.util.ArrayList<>();
                        for (int dlcId : dlcSet) {
                            boolean owned = licensedApps.contains(dlcId);
                            String kind;
                            if (!owned)                       kind = "unowned";
                            else if (pubDepots.contains(dlcId)) kind = "depot"; // base depot id==dlc appid
                            else                              kind = "";        // app|entitlement TBD below
                            rows.add(new SteamDatabase.DlcRow(baseAppId, dlcId, "", owned, kind));
                        }
                        db.replaceDlcSet(baseAppId, rows);   // empty set → writes resolved-empty sentinel
                        changed = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "resolveOwnedDlc self-heal(" + baseAppId + ") failed: " + e.getMessage());
                }
            }

            // (2) Fill display name + app/entitlement kind for owned DLC that still need it.
            java.util.List<SteamDatabase.DlcRow> need = db.getOwnedDlcNeedingResolve(baseAppId);
            if (!need.isEmpty() && online) {
                List<Integer> ids = new ArrayList<>();
                for (SteamDatabase.DlcRow r : need) ids.add(r.dlcAppId);
                try {
                    Map<Integer, KeyValue> got = fetchProductInfoKv(ids, timeoutMs);
                    for (SteamDatabase.DlcRow r : need) {
                        KeyValue root = got.get(r.dlcAppId);
                        String name = "";
                        String kind = null;   // null → updateDlcResolved leaves the stored kind as-is
                        if (root != null) {
                            name = kvStr(root.get("common").get("name"));
                            // Only classify app vs entitlement when the kind isn't already final
                            // ('depot' is set from the base game's depots and must not be overwritten).
                            if (r.kind == null || r.kind.isEmpty()) {
                                kind = collectPublicDepotIds(root).isEmpty() ? "entitlement" : "app";
                            }
                        }
                        db.updateDlcResolved(baseAppId, r.dlcAppId, name, kind);
                    }
                    changed = true;
                } catch (Exception e) {
                    Log.w(TAG, "resolveOwnedDlc names(" + baseAppId + ") failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveOwnedDlc(" + baseAppId + ") error: " + e.getMessage());
        }
        return changed;
    }

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    /** Auto-login using a stored refresh token. Must not be called on the main thread. */
    public void loginWithToken(String username, String refreshToken) {
        if (steamUser == null) return;
        if (realSteamSuspended) {
            Log.i(REALSTEAM_TAG, "loginWithToken skipped — app session suspended for in-game real-Steam session");
            return;
        }
        if (rustEngine) { rustConnect("loginWithToken"); return; }
        // Re-arm the background self-heal on any login attempt (idempotent) so a re-login after a
        // logout — which unregistered it while the pump kept running — re-attaches the callback.
        registerNetworkCallback();
        SteamLogRedactor.registerSecret(username);
        SteamLogRedactor.registerSecret(refreshToken);
        // Single-flight: a redundant logon while already logged in — or a second concurrent logon
        // — is exactly what triggers LogonSessionReplaced and evicts us. Skip both. Only supersede
        // a STALLED logon (posted but no callback within LOGON_STALL_MS) so we can never lock out.
        if (loggedIn) {
            Log.i(TAG, "loginWithToken skipped — already logged in");
            return;
        }
        long now = System.currentTimeMillis();
        if (loggingOn.get() && (now - logonStartedAt) < LOGON_STALL_MS) {
            Log.i(TAG, "loginWithToken skipped — logon already in flight");
            return;
        }
        loggingOn.set(true);
        logonStartedAt = now;
        loggingOut = false;   // a fresh logon means we are no longer in a sign-out
        setStatus(SteamStatus.CONNECTING, "logon posted");
        Runnable work = () -> {
            LogOnDetails details = new LogOnDetails();
            details.setUsername(username);
            details.setAccessToken(refreshToken);  // refreshToken goes in accessToken field
            details.setShouldRememberPassword(true);
            lastSelfLogonAt = System.currentTimeMillis();
            steamUser.logOn(details);
        };
        // steamUser.logOn() does network I/O — must run on the pump background thread.
        if (pumpHandler != null) {
            pumpHandler.post(work);
        } else {
            new Thread(work, "SteamLogin").start();
        }
    }

    /**
     * Ensure there is a live, logged-in Steam session before a depot download.
     *
     * Steam CM connections cycle routinely: onDisconnected clears {@code loggedIn} and the
     * auto-reconnect re-logs-on asynchronously, so a caller can briefly see
     * connected=true / loggedIn=false (the cached license list masks it). If we have a saved
     * session, kick a token logon and block the CALLING thread up to {@code timeoutMs} for the
     * LoggedOn callback to land — the pump thread keeps running callbacks meanwhile, so this
     * does not deadlock (never call this from the pump thread).
     *
     * @return true if logged in by the time we return.
     */
    public boolean ensureLoggedIn(long timeoutMs) {
        if (loggedIn) return true;
        if (steamClient == null || !connected) return false;
        if (!isLoggedInPrefs()) return false;   // no saved token — user must sign in
        loginWithToken(pGet("username", ""), pGet("refresh_token", ""));
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!loggedIn && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(150); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return loggedIn;
    }

    /**
     * Force a genuinely FRESH Steam session, then block the caller until it re-logs-in (or times out).
     *
     * ensureLoggedIn() short-circuits to {@code true} the instant {@code loggedIn} is set — which is
     * exactly wrong for a retry after a stalled download: the session can be ONLINE-but-stale (a masked
     * LogonSessionReplaced, or a socket that silently died) so the retry would run on the same dead
     * session and fail again. This method instead tears the session DOWN and rebuilds it: it disconnects
     * the CM and rides the SAME involuntary-logoff recovery path onLoggedOff() already uses —
     * {@code forceReconnect=true} so onDisconnected reconnects even though a client-initiated disconnect
     * arrives as "user-initiated", the in-flight-logon guard cleared (as onDisconnected does when the
     * socket dies), and {@code loggingOut=false} so this is NOT mistaken for a user sign-out. onConnected
     * then auto-logs-in from the saved refresh token and mints a brand-new session.
     *
     * Blocks the CALLING thread (the download worker — NEVER the pump) polling {@code loggedIn} every
     * ~150ms like ensureLoggedIn; the pump keeps running callbacks meanwhile, so this can't deadlock.
     *
     * @return true if a fresh session is logged in by the time we return; false if there is no saved
     *         token to recover to, or the fresh logon didn't land within {@code timeoutMs}.
     */
    public boolean reconnectAndRelogin(long timeoutMs) {
        if (steamClient == null) return false;
        if (realSteamSuspended) {
            Log.i(REALSTEAM_TAG, "reconnectAndRelogin refused — app session suspended for in-game real-Steam session");
            return false;
        }
        if (!isLoggedInPrefs()) return false;   // no saved token — nothing to recover to; caller must re-auth
        Log.i(TAG, "reconnectAndRelogin: forcing a fresh session (timeout " + timeoutMs + "ms)");
        setStatus(SteamStatus.CONNECTING, "forced reconnect+relogin for retry");
        // Arm the involuntary-logoff recovery path so the client-initiated disconnect below re-logs-in
        // instead of being treated as a user sign-out (see onDisconnected/onLoggedOff).
        loggingOut = false;
        forceReconnect = true;
        loggingOn.set(false);            // supersede any stalled in-flight logon (onDisconnected clears this too)
        reconnectAttempts = 0;           // give the forced reconnect its full retry budget
        logoffRecoveryAttempts = 0;
        loggedIn = false;                // drop the stale session immediately so the poll below waits for the NEW one
        final SteamClient sc = steamClient;
        // CM I/O must not run on the caller/worker thread — post the disconnect to the pump. onDisconnected
        // → auto-reconnect → onConnected → auto-login (isLoggedInPrefs) rebuilds the session.
        if (pumpHandler != null) {
            pumpHandler.post(() -> { try { sc.disconnect(); } catch (Throwable ignored) {} });
        } else {
            new Thread(() -> { try { sc.disconnect(); } catch (Throwable ignored) {} }, "SteamForcedReconnect").start();
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!loggedIn && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(150); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        Log.i(TAG, "reconnectAndRelogin: loggedIn=" + loggedIn + " connected=" + connected);
        return loggedIn;
    }

    /**
     * Persist credentials returned from the Steam auth session
     * (called from Phase 2 auth flow after pollingWaitForResult).
     */
    public void saveSession(String username, String refreshToken) {
        loggingOut = false;
        logoffRecoveryAttempts = 0;
        SteamLogRedactor.registerSecret(username);
        SteamLogRedactor.registerSecret(refreshToken);
        pPut("username", username);
        pPut("refresh_token", refreshToken);
    }

    /**
     * First-time credential login — stub for Phase 1.
     * Phase 2 will implement the full SteamAuthentication API flow.
     */
    public void loginWithCredentials(String username, String password) {
        Log.w(TAG, "loginWithCredentials: not yet implemented (Phase 2)");
        emit("LoginFailed:Phase2NotImplemented");
    }

    // -------------------------------------------------------------------------
    // Logout
    // -------------------------------------------------------------------------

    public void logout() {
        loggingOut = true;            // suppress involuntary-logoff recovery for this intentional sign-out
        logoffRecoveryAttempts = 0;
        // Detach the background self-heal so it can't fight this deliberate sign-out. (A later
        // re-login re-arms it via loginWithToken(); the isLoggedInPrefs()/loggingOut guards in
        // onNetworkAvailable are a second line of defence in case it is still attached.)
        unregisterNetworkCallback();
        rustTokenRejected = false;
        if (rustEngine) BlSteamEngine.INSTANCE.stop();
        if (steamUser != null) steamUser.logOff();
        if (prefs != null) {
            prefs.edit()
                .remove("username").remove("refresh_token")
                .remove("steam_id_64").remove("account_id")
                .remove("display_name").remove("last_pics_change")
                .apply();
        }
        synchronized (licenses) { licenses.clear(); }
        cachedGameRows = null;
        SteamLogRedactor.clearSecrets();   // creds are being removed; forget them from the redactor too
        setStatus(SteamStatus.SIGNED_OUT, "user logout");
        Log.i(TAG, "Logged out");
        emit("LoggedOut");
    }

    // -------------------------------------------------------------------------
    // Accessors for downstream phases
    // -------------------------------------------------------------------------

    /** Last session transition (LoggedIn / LoginFailed:&lt;r&gt; / LoggedOff:&lt;r&gt;) for debug logging. */
    public String getLastSessionStatus() { return lastSessionStatus; }

    /**
     * Raise the CM AsyncJob timeout for every currently-registered job still at the 10s default.
     *
     * DepotDownloader / Steam3Session create their CM jobs (picsGetAccessTokens, picsGetProductInfo,
     * getManifestRequestCode, depot-key, CDN-auth) internally and await() them immediately — there is
     * NO exposed per-job or Config timeout knob, and AsyncJob's 10 000ms default is hard-coded in its
     * constructor (no static setter). The only reachable lever is the live job map:
     *   SteamClient.getJobManager$javasteam() -> AsyncJobManager.getAsyncJobs() -> AsyncJob.setTimeout().
     * getJobManager$javasteam() is Kotlin-`internal` (mangled name) so this MUST live in Java — our
     * Kotlin cannot reference it without reflection.
     *
     * A download-scoped watchdog polls this so late-registered per-depot jobs are covered too. Bumping
     * the window lets a reply that is merely LATE (transient TcpConnection netThread head-of-line block
     * behind a large PICS parse) still land instead of being cancelled at 10s — while a genuine
     * no-reply still fails, just at the longer bound. Diagnostic + mitigation; the LogListener shows
     * exactly when (or whether) the reply arrives inside the extended window.
     */
    public void bumpPendingJobTimeouts(long timeoutMs) {
        SteamClient sc = steamClient;
        if (sc == null) return;
        try {
            int bumped = 0;
            for (in.dragonbra.javasteam.types.AsyncJob job : sc.getJobManager$javasteam().getAsyncJobs().values()) {
                if (job.getTimeout() < timeoutMs) { job.setTimeout(timeoutMs); bumped++; }
            }
            if (bumped > 0) Log.i(TAG, "bumpPendingJobTimeouts: raised " + bumped + " job(s) to " + timeoutMs + "ms");
        } catch (Throwable t) {
            Log.w(TAG, "bumpPendingJobTimeouts failed", t);
        }
    }

    public SteamClient   getSteamClient() { return steamClient; }
    public SteamApps     getSteamApps()   { return steamApps; }

    /** The SteamContent handler (manifest request codes + CDN server list) — auto-registered on the
     *  SteamClient. Used by DepotSizeResolver for metadata-only manifest fetches. Null if not connected. */
    public SteamContent  getSteamContent() {
        SteamClient sc = steamClient;
        return sc != null ? sc.getHandler(SteamContent.class) : null;
    }

    /** True while a depot download owns the CM connection. DepotSizeResolver must NOT issue CM
     *  traffic while this is true (it would contend with the download's AsyncJobs) — it serves the
     *  cached/estimate instead and defers. */
    public boolean isDownloadActive() { return downloadActive; }

    /** Submit work onto the single library/sync worker thread so DepotSizeResolver's manifest
     *  fetches stay serialized with (and off) the CM pump, exactly like the PICS sync. */
    public void submitLibraryWork(Runnable r) { runOnLibraryWorker(r); }
    public SteamDatabase getDatabase() {
        if (appContext != null) return SteamDatabase.getInstance(appContext);
        return SteamDatabase.getInstance();
    }

    // -------------------------------------------------------------------------
    // Beta-branch selector
    // -------------------------------------------------------------------------

    /** All known beta branches for an app (public-first). Empty = no branch data parsed yet. */
    public List<SteamDatabase.BranchRow> getBranches(int appId) {
        return getDatabase().getBranches(appId);
    }

    /**
     * Branches the user can actually select right now: every public (no-password) branch plus any
     * password-protected branch already unlocked via {@link #checkBranchPassword}. "public" is
     * always present in Steam's branch list, so it is included as the default.
     */
    public List<SteamDatabase.BranchRow> getSelectableBranches(int appId) {
        SteamDatabase db = getDatabase();
        List<String> unlocked = db.getUnlockedBranchNames(appId);
        List<SteamDatabase.BranchRow> out = new java.util.ArrayList<>();
        for (SteamDatabase.BranchRow b : db.getBranches(appId)) {
            if (!b.pwdRequired || unlocked.contains(b.branchName)) out.add(b);
        }
        return out;
    }

    /**
     * Verify a beta access code against Steam and persist every branch it unlocks. BLOCKS on a CM
     * round-trip (JavaSteam SteamApps.checkAppBetaPassword) — call OFF the main thread (e.g. via
     * {@link #submitLibraryWork} or a background coroutine). Returns true when at least one branch
     * was unlocked. Steam returns every beta password valid for the app in one response, so a single
     * correct code can unlock multiple branches at once.
     *
     * Ported from GameNative (GPL-3.0): app/gamenative/service/SteamService.checkPrivateBranchPassword.
     */
    public boolean checkBranchPassword(int appId, String password) {
        SteamApps sa = steamApps;
        if (sa == null || password == null || password.isEmpty()) return false;
        try {
            CheckAppBetaPasswordCallback cb = sa.checkAppBetaPassword(appId, password)
                    .toFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (cb != null && cb.getResult() == EResult.OK && !cb.getBetaPasswords().isEmpty()) {
                SteamDatabase db = getDatabase();
                for (String branchName : cb.getBetaPasswords().keySet()) {
                    db.insertUnlockedBranch(appId, branchName, password);
                }
                Log.i(TAG, "checkBranchPassword: app " + appId + " unlocked "
                        + cb.getBetaPasswords().keySet());
                return true;
            }
            Log.i(TAG, "checkBranchPassword: app " + appId + " rejected (result="
                    + (cb != null ? cb.getResult() : "null") + ")");
        } catch (Exception e) {
            Log.w(TAG, "checkBranchPassword failed for app " + appId + ": " + e.getMessage());
        }
        return false;
    }

    public String getUsername()     { return pGet("username", ""); }
    public String getRefreshToken()  { return pGet("refresh_token", ""); }
    public String getAccessToken()   { return pGet("refresh_token", ""); } // refresh token doubles as bearer
    public long   getSteamId64()    { return pGet("steam_id_64", 0L); }
    public int    getAccountId()    { return pGet("account_id", 0); }
    public String getDisplayName()  { return pGet("display_name", ""); }
    public void   setDisplayName(String name) { pPut("display_name", name); }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    public void emit(String event) {
        // Invalidate the in-memory game list on events that change DB state
        if (event.startsWith("LibrarySynced:") ||
            event.startsWith("DownloadComplete:") ||
            event.startsWith("DownloadCancelled:")) {
            cachedGameRows = null;
        }
        for (SteamEventListener l : listeners) {
            try { l.onEvent(event); }
            catch (Exception e) { Log.e(TAG, "Listener error for event " + event, e); }
        }
    }
}