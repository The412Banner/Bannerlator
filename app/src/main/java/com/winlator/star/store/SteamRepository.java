package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends;
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
    // Singleton
    // -------------------------------------------------------------------------

    private static final SteamRepository INSTANCE = new SteamRepository();
    public static SteamRepository getInstance() { return INSTANCE; }
    private SteamRepository() {}

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
    // JavaSteam instances
    // -------------------------------------------------------------------------

    private SteamClient    steamClient   = null;
    private CallbackManager manager      = null;
    private SteamUser      steamUser     = null;
    private SteamApps      steamApps     = null;
    private SteamCloud     steamCloud    = null;
    private SteamUserStats steamUserStats = null;

    private HandlerThread     pumpThread  = null;
    private Handler           pumpHandler = null;
    private final AtomicBoolean pumping    = new AtomicBoolean(false);
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
        SteamDatabase.getInstance(appContext);
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
        // CDN auth callbacks registered once correct class names are confirmed from JAR
        // manager.subscribe(ManifestRequestCodeCallback.class, this::onManifestRequestCode);
        // manager.subscribe(CDNAuthTokenCallback.class,        this::onCdnAuthToken);
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    public void connect() {
        if (steamClient == null) { Log.e(TAG, "connect() before initialize()"); return; }
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
        schedulePump();
    }

    private void stopPump() {
        pumping.set(false);
        if (pumpThread != null) { pumpThread.quitSafely(); pumpThread = null; }
        pumpHandler = null;
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

        if (isLoggedInPrefs()) {
            Log.i(TAG, "Auto-login as " + pGet("username", ""));
            loginWithToken(pGet("username", ""), pGet("refresh_token", ""));
        }
    }

    private volatile int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    private void onDisconnected(DisconnectedCallback cb) {
        Log.i(TAG, "Disconnected (userInitiated=" + cb.isUserInitiated() + ", attempt=" + reconnectAttempts + ")");
        connected = false;
        loggedIn  = false;
        connecting.set(false);
        if (!cb.isUserInitiated() && pumping.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            long delayMs = reconnectAttempts * 2000L;  // 2s, 4s, 6s, 8s, 10s
            Log.i(TAG, "Auto-reconnect in " + delayMs + "ms (attempt " + reconnectAttempts + ")");
            if (pumpHandler != null) {
                pumpHandler.postDelayed(() -> {
                    if (pumping.get() && !connected) {
                        Log.i(TAG, "Auto-reconnect: calling connect()");
                        steamClient.connect();
                    }
                }, delayMs);
            }
        } else {
            reconnectAttempts = 0;
            emit("Disconnected");
        }
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            Log.w(TAG, "Login failed: " + cb.getResult());
            emit("LoginFailed:" + cb.getResult().name());
            return;
        }

        pPut("cell_id", cb.getCellID());
        long sid64 = cb.getClientSteamID().convertToUInt64();
        pPut("steam_id_64", sid64);
        pPut("account_id", (int)(sid64 & 0xFFFFFFFFL));

        loggedIn = true;
        emit("LoggedIn:" + sid64);
        Log.i(TAG, "Logged in as " + pGet("username", ""));
    }

    private void onLoggedOff(LoggedOffCallback cb) {
        Log.i(TAG, "Logged off: " + cb.getResult());
        loggedIn = false;
        emit("LoggedOut");
    }

    private void onLicenseList(LicenseListCallback cb) {
        List<License> list = cb.getLicenseList();
        Log.i(TAG, list.size() + " licenses received");
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

    /** Phase 4 step 2: request PICS product info for all resolved app IDs. */
    private void syncApps(List<Integer> appIds) {
        if (steamApps == null || appIds.isEmpty()) {
            syncPhase = SYNC_IDLE;
            emit("LibrarySynced:0");
            return;
        }
        syncPhase = SYNC_APPS;
        pendingApps.clear();
        List<PICSRequest> appRequests = new ArrayList<>();
        for (int id : appIds) {
            appRequests.add(new PICSRequest(id));
        }
        Log.i(TAG, "PICS: requesting info for " + appRequests.size() + " apps");
        steamApps.picsGetProductInfo(appRequests, Collections.emptyList(), false);
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

    /** Phase 4 step 3: handle PICS product info callbacks for packages and apps. */
    private void onPICSProductInfo(PICSProductInfoCallback cb) {
        if (syncPhase == SYNC_PACKAGES) {
            pendingPackages.putAll(cb.getPackages());
            if (!cb.isResponsePending()) {
                // All package info received — extract appIds and persist mappings
                SteamDatabase db = SteamDatabase.getInstance();
                List<Integer> appIds = new ArrayList<>();
                for (PICSProductInfo pkg : pendingPackages.values()) {
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

        } else if (syncPhase == SYNC_APPS) {
            pendingApps.putAll(cb.getApps());
            if (!cb.isResponsePending()) {
                // All app info received — parse and store games
                SteamDatabase db = SteamDatabase.getInstance();
                int count = 0;
                for (PICSProductInfo app : pendingApps.values()) {
                    try {
                        KeyValue root = app.getKeyValues();
                        KeyValue common = root.get("common");
                        // "type" is absent on some entries (tools, hardware, etc.) — skip those
                        String type = kvStr(common.get("type")).toLowerCase();
                        // Skip non-playable app types
                        if ("tool".equals(type) || "hardware".equals(type)
                                || "music".equals(type) || "video".equals(type)
                                || "advertising".equals(type)) continue;
                        // Accept "game", "dlc", "application", "demo", "beta", ""
                        // Empty type means PICS didn't return common section — skip
                        if (type.isEmpty()) continue;

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

                        // Collect depot IDs, manifest IDs, and sizes from the "depots" section
                        StringBuilder depotSb = new StringBuilder();
                        long totalSize = 0L;
                        KeyValue depotsKv = root.get("depots");
                        List<KeyValue> depotChildren = depotsKv.getChildren();
                        if (depotChildren != null) {
                            for (KeyValue d : depotChildren) {
                                int depotId;
                                try { depotId = Integer.parseInt(d.getName()); }
                                catch (NumberFormatException ignored) { continue; }
                                if (depotSb.length() > 0) depotSb.append(',');
                                depotSb.append(depotId);
                                // Extract manifest GID from depots/{id}/manifests/public/gid
                                String manifestGid = kvStr(d.get("manifests").get("public").get("gid"));
                                if (manifestGid.isEmpty()) {
                                    // Some depots use "manifest" directly (older format)
                                    manifestGid = kvStr(d.get("manifest"));
                                }
                                // Modern PICS stores size at manifests/public/size (uncompressed).
                                // Older format uses the top-level maxsize field. Try both.
                                String sizeStr = kvStr(d.get("manifests").get("public").get("size"));
                                if (sizeStr.isEmpty()) sizeStr = kvStr(d.get("maxsize"));
                                long depotSize = 0L;
                                if (!sizeStr.isEmpty()) {
                                    try { depotSize = Long.parseLong(sizeStr); totalSize += depotSize; }
                                    catch (NumberFormatException ignored) {}
                                }
                                if (!manifestGid.isEmpty()) {
                                    try {
                                        long manifestId = Long.parseLong(manifestGid);
                                        db.upsertDepotManifest(app.getId(), depotId, manifestId, depotSize);
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }

                        db.upsertGame(app.getId(), name, icon, totalSize, depotSb.toString(), type,
                                developer, metacriticScore, genreSb.toString());
                        count++;
                    } catch (Exception e) {
                        Log.w(TAG, "Skipping app " + app.getId() + ": " + e.getMessage());
                    }
                }
                syncPhase = SYNC_IDLE;
                pendingPackages.clear();
                pendingApps.clear();
                recordSyncTime();
                Log.i(TAG, "Library sync complete: " + count + " apps");
                emit("LibrarySynced:" + count);
            }
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
        List<License> copy;
        synchronized (licenses) { copy = new ArrayList<>(licenses); }
        if (copy.isEmpty()) {
            Log.w(TAG, "syncLibrary() called but license list is empty");
            return;
        }
        // picsGetProductInfo() does network I/O — must run on the pump background thread.
        if (pumpHandler != null) {
            pumpHandler.post(() -> syncPackages(copy));
        } else {
            new Thread(() -> syncPackages(copy), "SteamSync").start();
        }
    }

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    /** Auto-login using a stored refresh token. Must not be called on the main thread. */
    public void loginWithToken(String username, String refreshToken) {
        if (steamUser == null) return;
        Runnable work = () -> {
            LogOnDetails details = new LogOnDetails();
            details.setUsername(username);
            details.setAccessToken(refreshToken);  // refreshToken goes in accessToken field
            details.setShouldRememberPassword(true);
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
     * Persist credentials returned from the Steam auth session
     * (called from Phase 2 auth flow after pollingWaitForResult).
     */
    public void saveSession(String username, String refreshToken) {
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
        Log.i(TAG, "Logged out");
        emit("LoggedOut");
    }

    // -------------------------------------------------------------------------
    // Accessors for downstream phases
    // -------------------------------------------------------------------------

    public SteamClient   getSteamClient() { return steamClient; }
    public SteamApps     getSteamApps()   { return steamApps; }
    public SteamDatabase getDatabase() {
        if (appContext != null) return SteamDatabase.getInstance(appContext);
        return SteamDatabase.getInstance();
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