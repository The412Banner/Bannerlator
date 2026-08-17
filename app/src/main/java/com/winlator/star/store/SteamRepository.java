package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

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
        libraryWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SteamLibraryWorker");
            t.setDaemon(true);
            return t;
        });
        schedulePump();
    }

    private void stopPump() {
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
    public enum SteamStatus { CONNECTING, ONLINE, SIGNED_IN_ELSEWHERE, OFFLINE, SIGNED_OUT }
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
        loggingOut = false;
        logoffRecoveryAttempts = 0;
        reconnectAttempts = 0;
        if (!isLoggedInPrefs()) return;
        setStatus(SteamStatus.CONNECTING, "user tapped reconnect");
        if (!connected) {
            connect();                                              // onConnected auto-logs-in
        } else if (!loggedIn) {
            loginWithToken(pGet("username", ""), pGet("refresh_token", ""));
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
        // Reconnect on an involuntary socket drop, OR when we deliberately forced a reconnect to
        // recover from a clean CM logoff (onLoggedOff) — the latter arrives as "user-initiated".
        if ((forced || !cb.isUserInitiated()) && pumping.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            long delayMs = reconnectAttempts * 2000L;  // 2s, 4s, 6s, 8s, 10s
            setStatus(SteamStatus.CONNECTING, "auto-reconnect attempt " + reconnectAttempts);
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
        int count = 0;
        for (PICSProductInfo app : apps) {
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
                                        Log.d(TAG, "app " + app.getId() + " skip depot " + depotId
                                                + " oslist='" + oslist + "' (not windows)");
                                        skippedCount++; continue;
                                    }
                                }
                                String lang = kvStr(config.get("language")).trim();
                                if (!lang.isEmpty() && !"english".equalsIgnoreCase(lang)) {
                                    Log.d(TAG, "app " + app.getId() + " skip depot " + depotId
                                            + " language='" + lang + "' (not english)");
                                    skippedCount++; continue;
                                }
                                String lv = kvStr(config.get("lowviolence")).trim();
                                if (lv.equals("1") || lv.equalsIgnoreCase("true")) {
                                    Log.d(TAG, "app " + app.getId() + " skip depot " + depotId + " lowviolence");
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
                                        Log.d(TAG, "app " + app.getId() + " skip DLC depot " + depotId + " (not owned)");
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
                                        db.upsertDepotManifest(app.getId(), depotId, manifestId, depotSize);
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }

                        // Stash the compressed (network) total in memory for the dual-color
                        // download/install progress bar. Not persisted (avoids a schema change);
                        // a cache miss on a later session falls back to an estimate.
                        downloadSizeByApp.put(app.getId(), totalDownload);
                        Log.i(TAG, "app " + app.getId() + " depots: selected=" + selectedCount
                                + " skipped=" + skippedCount + " install=" + totalSize
                                + "B download=" + totalDownload + "B");

                        db.upsertGame(app.getId(), name, icon, totalSize, depotSb.toString(), type,
                                developer, metacriticScore, genreSb.toString());
                        // Drop any depot rows no longer selected (e.g. an unowned DLC depot a
                        // pre-filter sync stored) so the completion guard can't fail on them.
                        db.pruneDepots(app.getId(), depotSb.toString());
                        // Record owned DLC bundled with the game (for the "Includes DLC:" line).
                        StringBuilder dlcCsv = new StringBuilder();
                        for (int id : includedDlcIds) {
                            if (dlcCsv.length() > 0) dlcCsv.append(',');
                            dlcCsv.append(id);
                        }
                        db.setIncludedDlc(app.getId(), dlcCsv.toString());
                        count++;
                    } catch (Exception e) {
                        Log.w(TAG, "Skipping app " + app.getId() + ": " + e.getMessage());
                    }
        }
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
        // picsGetProductInfo() does network I/O and must run off the main thread; route it to the
        // library worker (never the pump — the ensuing PICS parse + DB work would block callbacks).
        runOnLibraryWorker(() -> syncPackages(copy));
    }

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    /** Auto-login using a stored refresh token. Must not be called on the main thread. */
    public void loginWithToken(String username, String refreshToken) {
        if (steamUser == null) return;
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