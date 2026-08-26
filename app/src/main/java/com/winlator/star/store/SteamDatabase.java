package com.winlator.star.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite persistence layer for Steam library data.
 *
 * Written in Java (not Kotlin) to avoid Kotlin 2.2.0 metadata incompatibility.
 * Does NOT reference SteamGame.kt (Kotlin class compiled in a later step);
 * callers convert GameRow ↔ SteamGame in Kotlin.
 *
 * Tables:
 *   steam_games       — metadata for each owned/installed app
 *   steam_licenses    — Steam package (sub) records from LicenseList callback
 *   steam_license_apps — package → appId mapping
 *   steam_downloads   — active/queued/failed download tracking
 *
 * Initialised lazily via SteamRepository.initialize(); access via
 * SteamRepository.getInstance().getDatabase() or getInstance(ctx).
 */
public final class SteamDatabase extends SQLiteOpenHelper {

    private static final String TAG        = "SteamDB";
    private static final String DB_NAME    = "steam.db";
    // v4: additive true-depot-size columns (DepotSizeResolver) — real_size_bytes /
    //     real_download_bytes on depot_manifests, real_size_bytes on steam_games.
    // v5: additive real_disk_bytes (block-rounded true on-disk footprint estimate) on
    //     depot_manifests + steam_games — DepotSizeResolver sums ceil(fileSize/block) per file.
    // v6: reset real_disk_bytes — a v5 build computed it wrong (skipped every file, so it
    //     equalled real_size); zero it so the fixed block-rounding recomputes on next resolve.
    // v7: additive included_dlc (CSV of owned DLC appIds whose depots download with the game) on
    //     steam_games — surfaced as an "Includes DLC:" line on the detail page.
    // v8: additive steam_branches (beta-branch metadata parsed from depots/branches/*) +
    //     steam_unlocked_branches (per-app beta access passwords the user has verified). Both are
    //     new tables — NO existing library table is touched, so no re-sync required.
    // v9: additive steam_achievements (per-app achievement schema + earned state, from
    //     SteamUserStats.getUserStats → getExpandedAchievements). NEW table only — no existing
    //     library table is touched, so no re-sync required.
    private static final int    DB_VERSION = 9;

    // -------------------------------------------------------------------------
    // DDL
    // -------------------------------------------------------------------------

    private static final String SQL_GAMES =
            "CREATE TABLE steam_games (" +
            "  app_id          INTEGER PRIMARY KEY," +
            "  name            TEXT    NOT NULL DEFAULT ''," +
            "  install_dir     TEXT    NOT NULL DEFAULT ''," +
            "  icon_hash       TEXT    NOT NULL DEFAULT ''," +
            "  size_bytes      INTEGER NOT NULL DEFAULT 0," +
            "  depot_ids       TEXT    NOT NULL DEFAULT ''," +
            "  type            TEXT    NOT NULL DEFAULT 'game'," +
            "  is_installed    INTEGER NOT NULL DEFAULT 0," +
            "  last_updated    INTEGER NOT NULL DEFAULT 0," +
            "  developer       TEXT    NOT NULL DEFAULT ''," +
            "  metacritic_score INTEGER NOT NULL DEFAULT 0," +
            "  genres          TEXT    NOT NULL DEFAULT ''," +
            // True install size (uncompressed) summed from the SELECTED depots' manifests by
            // DepotSizeResolver. 0 = unresolved → callers fall back to the PICS size_bytes estimate.
            "  real_size_bytes INTEGER NOT NULL DEFAULT 0," +
            // Estimated real on-disk footprint (block-rounded per-file sum). 0 = unresolved.
            "  real_disk_bytes INTEGER NOT NULL DEFAULT 0," +
            // CSV of owned DLC appIds whose depots download with this game (for the detail-page
            // "Includes DLC:" line). Empty = no owned DLC bundled.
            "  included_dlc TEXT NOT NULL DEFAULT ''" +
            ")";

    private static final String SQL_LICENSES =
            "CREATE TABLE steam_licenses (" +
            "  package_id   INTEGER PRIMARY KEY," +
            "  time_created INTEGER NOT NULL DEFAULT 0," +
            "  flags        INTEGER NOT NULL DEFAULT 0," +
            "  license_type INTEGER NOT NULL DEFAULT 0" +
            ")";

    private static final String SQL_LICENSE_APPS =
            "CREATE TABLE steam_license_apps (" +
            "  package_id INTEGER NOT NULL," +
            "  app_id     INTEGER NOT NULL," +
            "  PRIMARY KEY (package_id, app_id)" +
            ")";

    private static final String SQL_DOWNLOADS =
            "CREATE TABLE steam_downloads (" +
            "  app_id           INTEGER PRIMARY KEY," +
            "  status           TEXT    NOT NULL DEFAULT 'queued'," +
            "  bytes_downloaded INTEGER NOT NULL DEFAULT 0," +
            "  bytes_total      INTEGER NOT NULL DEFAULT 0," +
            "  install_dir      TEXT    NOT NULL DEFAULT ''," +
            "  error_msg        TEXT    NOT NULL DEFAULT ''," +
            "  added_at         INTEGER NOT NULL DEFAULT 0" +
            ")";

    private static final String SQL_DEPOT_MANIFESTS =
            "CREATE TABLE depot_manifests (" +
            "  app_id      INTEGER NOT NULL," +
            "  depot_id    INTEGER NOT NULL," +
            "  manifest_id INTEGER NOT NULL DEFAULT 0," +
            "  size_bytes  INTEGER NOT NULL DEFAULT 0," +
            // True per-depot sizes from the depot MANIFEST (metadata only), resolved lazily by
            // DepotSizeResolver. 0 = unresolved. Keyed with manifest_id: a GID change (new build)
            // in upsertDepotManifest resets these to 0 so a stale size can't survive an update.
            "  real_size_bytes     INTEGER NOT NULL DEFAULT 0," +  // uncompressed (install)
            "  real_download_bytes INTEGER NOT NULL DEFAULT 0," +  // compressed  (network)
            "  real_disk_bytes     INTEGER NOT NULL DEFAULT 0," +  // block-rounded on-disk estimate
            "  PRIMARY KEY (app_id, depot_id)" +
            ")";

    // Beta-branch metadata (depots/branches/* from PICS). One row per branch per app. Re-parsed on
    // every library sync (SteamRepository clears the app's rows first, then upserts each branch).
    private static final String SQL_BRANCHES =
            "CREATE TABLE IF NOT EXISTS steam_branches (" +
            "  app_id       INTEGER NOT NULL," +
            "  branch_name  TEXT    NOT NULL," +
            "  pwd_required INTEGER NOT NULL DEFAULT 0," +   // 1 = password-protected beta
            "  build_id     INTEGER NOT NULL DEFAULT 0," +
            "  time_updated INTEGER NOT NULL DEFAULT 0," +   // epoch seconds of last build
            "  description  TEXT    NOT NULL DEFAULT ''," +
            "  PRIMARY KEY (app_id, branch_name)" +
            ")";

    // Beta access passwords the user has successfully verified (SteamApps.checkAppBetaPassword).
    // Persisted so an unlocked branch stays selectable — and downloadable — across sessions.
    private static final String SQL_UNLOCKED_BRANCHES =
            "CREATE TABLE IF NOT EXISTS steam_unlocked_branches (" +
            "  app_id      INTEGER NOT NULL," +
            "  branch_name TEXT    NOT NULL," +
            "  password    TEXT    NOT NULL DEFAULT ''," +
            "  PRIMARY KEY (app_id, branch_name)" +
            ")";

    // Per-app achievement schema + earned state (SteamUserStats.getUserStats → getExpandedAchievements).
    // One row per achievement per app. icon/icon_gray store the raw CDN FILENAME (not the full URL) —
    // SteamAchievementStore rebuilds the URL and resolves the on-disk cached path. Re-upserted on every
    // fetch (SteamAchievementStore clears nothing; upsert refreshes each row's earned state in place).
    private static final String SQL_ACHIEVEMENTS =
            "CREATE TABLE IF NOT EXISTS steam_achievements (" +
            "  app_id       INTEGER NOT NULL," +
            "  api_name     TEXT    NOT NULL," +
            "  display_name TEXT    NOT NULL DEFAULT ''," +
            "  description  TEXT    NOT NULL DEFAULT ''," +
            "  hidden       INTEGER NOT NULL DEFAULT 0," +
            "  icon         TEXT    NOT NULL DEFAULT ''," +     // color-icon CDN filename
            "  icon_gray    TEXT    NOT NULL DEFAULT ''," +     // locked/gray CDN filename
            "  unlocked     INTEGER NOT NULL DEFAULT 0," +
            "  unlock_time  INTEGER NOT NULL DEFAULT 0," +      // epoch seconds; 0 if locked
            "  PRIMARY KEY (app_id, api_name)" +
            ")";

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static volatile SteamDatabase INSTANCE;

    public static SteamDatabase getInstance(Context ctx) {
        if (INSTANCE == null) {
            synchronized (SteamDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SteamDatabase(ctx.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    /** Access the already-initialised instance (throws if not yet initialised). */
    public static SteamDatabase getInstance() {
        if (INSTANCE == null) throw new IllegalStateException("SteamDatabase not initialised — call getInstance(ctx) first");
        return INSTANCE;
    }

    private SteamDatabase(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    // -------------------------------------------------------------------------
    // SQLiteOpenHelper
    // -------------------------------------------------------------------------

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_GAMES);
        db.execSQL(SQL_LICENSES);
        db.execSQL(SQL_LICENSE_APPS);
        db.execSQL(SQL_DOWNLOADS);
        db.execSQL(SQL_DEPOT_MANIFESTS);
        db.execSQL(SQL_BRANCHES);
        db.execSQL(SQL_UNLOCKED_BRANCHES);
        db.execSQL(SQL_ACHIEVEMENTS);
        Log.i(TAG, "steam.db created (v" + DB_VERSION + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "Upgrading steam.db v" + oldVersion + " → v" + newVersion);
        // Legacy destructive path: anything older than v3 gets recreated at the latest schema
        // (onCreate builds the CURRENT DDL, which already includes the v4 real_* columns).
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS depot_manifests");
            db.execSQL("DROP TABLE IF EXISTS steam_downloads");
            db.execSQL("DROP TABLE IF EXISTS steam_license_apps");
            db.execSQL("DROP TABLE IF EXISTS steam_licenses");
            db.execSQL("DROP TABLE IF EXISTS steam_games");
            onCreate(db);
            return;
        }
        // v3 → v4: ADDITIVE — add the true-size columns, defaults 0, existing rows untouched
        // (no library re-sync required). Guarded so a partial/re-run upgrade can't hard-fail.
        if (oldVersion < 4) {
            addColumnIfMissing(db, "depot_manifests", "real_size_bytes",     "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, "depot_manifests", "real_download_bytes", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, "steam_games",     "real_size_bytes",     "INTEGER NOT NULL DEFAULT 0");
        }
        // v4 → v5: ADDITIVE — on-disk footprint estimate columns, defaults 0, rows untouched.
        if (oldVersion < 5) {
            addColumnIfMissing(db, "depot_manifests", "real_disk_bytes", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, "steam_games",     "real_disk_bytes", "INTEGER NOT NULL DEFAULT 0");
        }
        // v5 → v6: the v5 footprint math skipped every file (linkTarget "" != null) so real_disk_bytes
        // wrongly equalled real_size. Zero it so the fixed block-rounding recomputes on next resolve.
        if (oldVersion < 6) {
            try { db.execSQL("UPDATE depot_manifests SET real_disk_bytes = 0"); } catch (Exception e) {
                Log.w(TAG, "v6 reset depot_manifests.real_disk_bytes: " + e.getMessage());
            }
            try { db.execSQL("UPDATE steam_games SET real_disk_bytes = 0"); } catch (Exception e) {
                Log.w(TAG, "v6 reset steam_games.real_disk_bytes: " + e.getMessage());
            }
        }
        // v6 → v7: ADDITIVE — included_dlc CSV column, default '', rows untouched.
        if (oldVersion < 7) {
            addColumnIfMissing(db, "steam_games", "included_dlc", "TEXT NOT NULL DEFAULT ''");
        }
        // v7 → v8: ADDITIVE — two NEW tables for beta branches + unlocked passwords. CREATE IF NOT
        // EXISTS, and crucially NO drop of the library tables (steam_games etc. stay intact).
        if (oldVersion < 8) {
            db.execSQL(SQL_BRANCHES);
            db.execSQL(SQL_UNLOCKED_BRANCHES);
        }
        // v8 → v9: ADDITIVE — one NEW table for per-app achievements. CREATE IF NOT EXISTS, no drop
        // of any existing table (library data stays intact).
        if (oldVersion < 9) {
            db.execSQL(SQL_ACHIEVEMENTS);
        }
    }

    /**
     * A newer build wrote a higher DB version, then the user rolled back to this (older) build.
     * The default SQLiteOpenHelper.onDowngrade THROWS ("Can't downgrade database from version N
     * to M"), which hard-crashed the Steam screen when a v4 build was rolled back to v3. Rebuild
     * the schema at this build's version instead: the cached library/licenses are lost but re-sync
     * on next login, and — crucially — the app no longer crashes on open. Additive-only columns
     * mean a downgrade would otherwise be harmless, but we can't rely on that across arbitrary gaps.
     */
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "Downgrading steam.db v" + oldVersion + " → v" + newVersion + " — rebuilding schema (cache lost, re-syncs on login)");
        db.execSQL("DROP TABLE IF EXISTS depot_manifests");
        db.execSQL("DROP TABLE IF EXISTS steam_downloads");
        db.execSQL("DROP TABLE IF EXISTS steam_license_apps");
        db.execSQL("DROP TABLE IF EXISTS steam_licenses");
        db.execSQL("DROP TABLE IF EXISTS steam_games");
        db.execSQL("DROP TABLE IF EXISTS steam_branches");
        db.execSQL("DROP TABLE IF EXISTS steam_unlocked_branches");
        db.execSQL("DROP TABLE IF EXISTS steam_achievements");
        onCreate(db);
    }

    /** ALTER TABLE ... ADD COLUMN, ignoring the "duplicate column name" error so re-runs are safe. */
    private static void addColumnIfMissing(SQLiteDatabase db, String table, String column, String type) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (Exception e) {
            Log.w(TAG, "addColumn " + table + "." + column + " skipped: " + e.getMessage());
        }
    }

    // =========================================================================
    // Lightweight row class (callers convert to SteamGame in Kotlin)
    // =========================================================================

    public static final class GameRow {
        public final int     appId;
        public final String  name;
        public final String  installDir;
        public final String  iconHash;
        public final long    sizeBytes;
        public final String  depotIds;       // comma-separated ints
        public final String  type;
        public final boolean isInstalled;
        public final String  developer;
        public final int     metacriticScore; // 0 = not rated
        public final String  genres;          // comma-separated genre names

        GameRow(int appId, String name, String installDir, String iconHash,
                long sizeBytes, String depotIds, String type, boolean isInstalled,
                String developer, int metacriticScore, String genres) {
            this.appId           = appId;
            this.name            = name;
            this.installDir      = installDir;
            this.iconHash        = iconHash;
            this.sizeBytes       = sizeBytes;
            this.depotIds        = depotIds;
            this.type            = type;
            this.isInstalled     = isInstalled;
            this.developer       = developer;
            this.metacriticScore = metacriticScore;
            this.genres          = genres;
        }
    }

    public static final class DownloadRow {
        public final int    appId;
        public final String status;
        public final long   bytesDownloaded;
        public final long   bytesTotal;
        public final String installDir;
        public final String errorMsg;

        DownloadRow(int appId, String status, long bytesDownloaded,
                    long bytesTotal, String installDir, String errorMsg) {
            this.appId           = appId;
            this.status          = status;
            this.bytesDownloaded = bytesDownloaded;
            this.bytesTotal      = bytesTotal;
            this.installDir      = installDir;
            this.errorMsg        = errorMsg;
        }
    }

    /** One beta branch's metadata (from depots/branches/*). */
    public static final class BranchRow {
        public final int     appId;
        public final String  branchName;
        public final boolean pwdRequired;   // true = password-protected beta
        public final long    buildId;
        public final long    timeUpdated;    // epoch seconds of the branch's last build (0 = unknown)
        public final String  description;    // Steam-authored branch blurb (may be empty)

        BranchRow(int appId, String branchName, boolean pwdRequired,
                  long buildId, long timeUpdated, String description) {
            this.appId       = appId;
            this.branchName  = branchName;
            this.pwdRequired = pwdRequired;
            this.buildId     = buildId;
            this.timeUpdated = timeUpdated;
            this.description = description;
        }
    }

    /** One achievement's persisted schema + earned state. icon/iconGray are raw CDN FILENAMES. */
    public static final class AchievementRow {
        public final int     appId;
        public final String  apiName;
        public final String  displayName;
        public final String  description;
        public final boolean hidden;
        public final String  icon;         // color-icon CDN filename (not a URL)
        public final String  iconGray;     // locked/gray CDN filename (not a URL)
        public final boolean unlocked;
        public final long    unlockTime;   // epoch seconds; 0 if locked

        public AchievementRow(int appId, String apiName, String displayName, String description,
                              boolean hidden, String icon, String iconGray,
                              boolean unlocked, long unlockTime) {
            this.appId       = appId;
            this.apiName     = apiName;
            this.displayName = displayName;
            this.description = description;
            this.hidden      = hidden;
            this.icon        = icon;
            this.iconGray    = iconGray;
            this.unlocked    = unlocked;
            this.unlockTime  = unlockTime;
        }
    }

    // =========================================================================
    // steam_games
    // =========================================================================

    /**
     * Insert or update game metadata. Does NOT overwrite install_dir / is_installed.
     * @param depotIds comma-separated depot IDs, e.g. "12345,12346"
     */
    public void upsertGame(int appId, String name, String iconHash,
                           long sizeBytes, String depotIds, String type,
                           String developer, int metacriticScore, String genres) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis() / 1000L;
        ContentValues cv = new ContentValues();
        cv.put("app_id",           appId);
        cv.put("name",             name != null ? name : "");
        cv.put("icon_hash",        iconHash != null ? iconHash : "");
        cv.put("size_bytes",       sizeBytes);
        cv.put("depot_ids",        depotIds != null ? depotIds : "");
        cv.put("type",             type != null ? type : "game");
        cv.put("developer",        developer != null ? developer : "");
        cv.put("metacritic_score", metacriticScore);
        cv.put("genres",           genres != null ? genres : "");
        cv.put("last_updated",     now);
        db.insertWithOnConflict("steam_games", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        // On collision: update metadata but preserve install state
        ContentValues upd = new ContentValues();
        upd.put("name",             cv.getAsString("name"));
        upd.put("icon_hash",        cv.getAsString("icon_hash"));
        upd.put("size_bytes",       sizeBytes);
        upd.put("depot_ids",        cv.getAsString("depot_ids"));
        upd.put("type",             cv.getAsString("type"));
        upd.put("developer",        cv.getAsString("developer"));
        upd.put("metacritic_score", metacriticScore);
        upd.put("genres",           cv.getAsString("genres"));
        upd.put("last_updated",     now);
        db.update("steam_games", upd, "app_id = ?", new String[]{String.valueOf(appId)});
    }

    /** Record the owned DLC (appId CSV) whose depots download with this game. Separate from
     *  upsertGame so its signature (and all callers) stay unchanged. */
    public void setIncludedDlc(int appId, String csv) {
        ContentValues cv = new ContentValues();
        cv.put("included_dlc", csv != null ? csv : "");
        getWritableDatabase().update("steam_games", cv, "app_id = ?", new String[]{String.valueOf(appId)});
    }

    /** Owned DLC bundled with this game as an ordered appId → display-name map (resolved from
     *  included_dlc → steam_games.name; falls back to "DLC <appId>"). Empty = none. Drives the
     *  DLC picker (needs appIds to toggle) and the "Includes DLC:" line. */
    public java.util.LinkedHashMap<Integer, String> getIncludedDlcEntries(int appId) {
        java.util.LinkedHashMap<Integer, String> out = new java.util.LinkedHashMap<>();
        String csv;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT included_dlc FROM steam_games WHERE app_id = ?",
                new String[]{String.valueOf(appId)})) {
            if (!c.moveToNext()) return out;
            csv = c.getString(0);
        } catch (Exception e) { return out; }
        if (csv == null || csv.isEmpty()) return out;
        for (String part : csv.split(",")) {
            String id = part.trim();
            if (id.isEmpty()) continue;
            int dlcAppId;
            try { dlcAppId = Integer.parseInt(id); } catch (NumberFormatException e) { continue; }
            String nm = null;
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT name FROM steam_games WHERE app_id = ?", new String[]{id})) {
                if (c.moveToNext()) nm = c.getString(0);
            } catch (Exception ignored) {}
            out.put(dlcAppId, nm != null && !nm.isEmpty() ? nm : "DLC " + id);
        }
        return out;
    }

    /** Display names of the owned DLC bundled with this game. Empty list = none. */
    public List<String> getIncludedDlcNames(int appId) {
        return new ArrayList<>(getIncludedDlcEntries(appId).values());
    }

    /** Mark a game as installed at the given path. */
    public void markInstalled(int appId, String installDir, long sizeBytes) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("is_installed", 1);
        cv.put("install_dir",  installDir != null ? installDir : "");
        cv.put("size_bytes",   sizeBytes);
        db.update("steam_games", cv, "app_id = ?", new String[]{String.valueOf(appId)});
    }

    /** Clear install state without removing the game record. */
    public void markUninstalled(int appId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("is_installed", 0);
        cv.put("install_dir",  "");
        db.update("steam_games", cv, "app_id = ?", new String[]{String.valueOf(appId)});
        // Invalidate in-memory cache so the library list reflects the new state immediately
        SteamRepository.getInstance().invalidateGameCache();
    }

    /** All games in the library, ordered by name. */
    public List<GameRow> getAllGames() {
        return queryGames(null, null);
    }

    /** Only games with is_installed = 1. */
    public List<GameRow> getInstalledGames() {
        return queryGames("is_installed = 1", null);
    }

    /** Single game record, or null if not present. */
    public GameRow getGame(int appId) {
        List<GameRow> r = queryGames("app_id = ?", new String[]{String.valueOf(appId)});
        return r.isEmpty() ? null : r.get(0);
    }

    /** All appIds currently in steam_games (for delta PICS sync). */
    public List<Integer> getAllAppIds() {
        List<Integer> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT app_id FROM steam_games", null)) {
            while (c.moveToNext()) ids.add(c.getInt(0));
        }
        return ids;
    }

    /** Delete game record and any associated download row. */
    public void deleteGame(int appId) {
        String[] a = {String.valueOf(appId)};
        SQLiteDatabase db = getWritableDatabase();
        db.delete("steam_games",     "app_id = ?", a);
        db.delete("steam_downloads", "app_id = ?", a);
    }

    private List<GameRow> queryGames(String where, String[] args) {
        List<GameRow> result = new ArrayList<>();
        String sql = "SELECT app_id,name,install_dir,icon_hash,size_bytes,depot_ids,type," +
                     "is_installed,developer,metacritic_score,genres" +
                     " FROM steam_games" +
                     (where != null ? " WHERE " + where : "") +
                     " ORDER BY name COLLATE NOCASE";
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                result.add(new GameRow(
                        c.getInt(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getLong(4),
                        c.getString(5),
                        c.getString(6),
                        c.getInt(7) != 0,
                        c.getString(8),
                        c.getInt(9),
                        c.getString(10)));
            }
        }
        return result;
    }

    // =========================================================================
    // steam_licenses
    // =========================================================================

    public void upsertLicense(int packageId, long timeCreated, int flags, int licenseType) {
        ContentValues cv = new ContentValues();
        cv.put("package_id",   packageId);
        cv.put("time_created", timeCreated);
        cv.put("flags",        flags);
        cv.put("license_type", licenseType);
        getWritableDatabase().insertWithOnConflict(
                "steam_licenses", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void linkLicenseApp(int packageId, int appId) {
        ContentValues cv = new ContentValues();
        cv.put("package_id", packageId);
        cv.put("app_id",     appId);
        getWritableDatabase().insertWithOnConflict(
                "steam_license_apps", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** All distinct appIds the user is licensed for. */
    public List<Integer> getLicensedAppIds() {
        List<Integer> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT app_id FROM steam_license_apps ORDER BY app_id", null)) {
            while (c.moveToNext()) ids.add(c.getInt(0));
        }
        return ids;
    }

    public List<Integer> getLicensedPackageIds() {
        List<Integer> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT package_id FROM steam_licenses ORDER BY package_id", null)) {
            while (c.moveToNext()) ids.add(c.getInt(0));
        }
        return ids;
    }

    /** Wipe all license rows (call before full re-sync). */
    public void clearLicenses() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("steam_license_apps", null, null);
        db.delete("steam_licenses",     null, null);
    }

    // =========================================================================
    // depot_manifests
    // =========================================================================

    public static final class DepotManifestRow {
        public final int  appId;
        public final int  depotId;
        public final long manifestId;
        public final long sizeBytes;          // PICS-declared (unreliable) uncompressed estimate
        public final long realSizeBytes;      // manifest-true uncompressed (install), 0 = unresolved
        public final long realDownloadBytes;  // manifest-true compressed  (network), 0 = unresolved
        public final long realDiskBytes;      // block-rounded on-disk footprint estimate, 0 = unresolved

        DepotManifestRow(int appId, int depotId, long manifestId, long sizeBytes,
                         long realSizeBytes, long realDownloadBytes, long realDiskBytes) {
            this.appId             = appId;
            this.depotId           = depotId;
            this.manifestId        = manifestId;
            this.sizeBytes         = sizeBytes;
            this.realSizeBytes     = realSizeBytes;
            this.realDownloadBytes = realDownloadBytes;
            this.realDiskBytes     = realDiskBytes;
        }
    }

    /**
     * Upsert a depot's PICS metadata (manifest GID + declared size). Preserves any resolved
     * real_*_bytes when the manifest GID is UNCHANGED; a GID change (new build) resets them to 0
     * so DepotSizeResolver re-fetches. Called on every library sync — must not clobber real sizes.
     */
    public void upsertDepotManifest(int appId, int depotId, long manifestId, long sizeBytes) {
        long realSize = 0L, realDownload = 0L, realDisk = 0L;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT manifest_id, real_size_bytes, real_download_bytes, real_disk_bytes FROM depot_manifests" +
                " WHERE app_id = ? AND depot_id = ?",
                new String[]{String.valueOf(appId), String.valueOf(depotId)})) {
            if (c.moveToNext() && c.getLong(0) == manifestId) {
                realSize     = c.getLong(1);   // same build → keep the resolved sizes
                realDownload = c.getLong(2);
                realDisk     = c.getLong(3);
            }
        } catch (Exception ignored) {}
        ContentValues cv = new ContentValues();
        cv.put("app_id",              appId);
        cv.put("depot_id",            depotId);
        cv.put("manifest_id",         manifestId);
        cv.put("size_bytes",          sizeBytes);
        cv.put("real_size_bytes",     realSize);
        cv.put("real_download_bytes", realDownload);
        cv.put("real_disk_bytes",     realDisk);
        getWritableDatabase().insertWithOnConflict(
                "depot_manifests", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Drop depot rows for an app that are no longer in the current SELECTED set — e.g. an unowned DLC
     * depot that a pre-filter sync had stored. Without this, upsert (add/update only) would leave the
     * stale depot behind and the completion guard would keep failing on it. selectedCsv is the
     * comma-separated selected depot ids (all validated ints, safe to inline); empty → drop all.
     */
    public void pruneDepots(int appId, String selectedCsv) {
        SQLiteDatabase db = getWritableDatabase();
        if (selectedCsv == null || selectedCsv.isEmpty()) {
            db.delete("depot_manifests", "app_id = ?", new String[]{String.valueOf(appId)});
        } else {
            db.delete("depot_manifests", "app_id = ? AND depot_id NOT IN (" + selectedCsv + ")",
                    new String[]{String.valueOf(appId)});
        }
    }

    /** All depots (with manifest IDs + resolved real sizes) for a given app. */
    public List<DepotManifestRow> getDepotManifests(int appId) {
        List<DepotManifestRow> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT app_id,depot_id,manifest_id,size_bytes,real_size_bytes,real_download_bytes,real_disk_bytes" +
                " FROM depot_manifests WHERE app_id = ? ORDER BY depot_id",
                new String[]{String.valueOf(appId)})) {
            while (c.moveToNext()) {
                rows.add(new DepotManifestRow(
                        c.getInt(0), c.getInt(1), c.getLong(2), c.getLong(3),
                        c.getLong(4), c.getLong(5), c.getLong(6)));
            }
        }
        return rows;
    }

    /**
     * Persist a depot's manifest-true sizes (DepotSizeResolver). Guarded on manifest_id so a
     * reply that arrives after the depot's GID changed can't write a stale size onto the new build.
     */
    public void updateDepotRealSize(int appId, int depotId, long manifestId,
                                    long realSizeBytes, long realDownloadBytes, long realDiskBytes) {
        ContentValues cv = new ContentValues();
        cv.put("real_size_bytes",     realSizeBytes);
        cv.put("real_download_bytes", realDownloadBytes);
        cv.put("real_disk_bytes",     realDiskBytes);
        getWritableDatabase().update("depot_manifests", cv,
                "app_id = ? AND depot_id = ? AND manifest_id = ?",
                new String[]{String.valueOf(appId), String.valueOf(depotId), String.valueOf(manifestId)});
    }

    /** App-level resolved true install size (uncompressed), or 0 if unresolved. */
    public long getGameRealSize(int appId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT real_size_bytes FROM steam_games WHERE app_id = ?",
                new String[]{String.valueOf(appId)})) {
            if (c.moveToNext()) return c.getLong(0);
        } catch (Exception ignored) {}
        return 0L;
    }

    /** Cache the app-level resolved true install size (uncompressed). */
    public void setGameRealSize(int appId, long realSizeBytes) {
        ContentValues cv = new ContentValues();
        cv.put("real_size_bytes", realSizeBytes);
        getWritableDatabase().update("steam_games", cv,
                "app_id = ?", new String[]{String.valueOf(appId)});
    }

    /** App-level estimated real on-disk footprint (block-rounded), or 0 if unresolved. */
    public long getGameRealDisk(int appId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT real_disk_bytes FROM steam_games WHERE app_id = ?",
                new String[]{String.valueOf(appId)})) {
            if (c.moveToNext()) return c.getLong(0);
        } catch (Exception ignored) {}
        return 0L;
    }

    /** Cache the app-level estimated real on-disk footprint (block-rounded). */
    public void setGameRealDisk(int appId, long realDiskBytes) {
        ContentValues cv = new ContentValues();
        cv.put("real_disk_bytes", realDiskBytes);
        getWritableDatabase().update("steam_games", cv,
                "app_id = ?", new String[]{String.valueOf(appId)});
    }

    // =========================================================================
    // steam_branches / steam_unlocked_branches (beta-branch selector)
    // =========================================================================

    /** Insert or replace one branch's metadata for an app. */
    public void upsertBranch(int appId, String branchName, boolean pwdRequired,
                             long buildId, long timeUpdated, String description) {
        ContentValues cv = new ContentValues();
        cv.put("app_id",       appId);
        cv.put("branch_name",  branchName != null ? branchName : "");
        cv.put("pwd_required", pwdRequired ? 1 : 0);
        cv.put("build_id",     buildId);
        cv.put("time_updated", timeUpdated);
        cv.put("description",  description != null ? description : "");
        getWritableDatabase().insertWithOnConflict(
                "steam_branches", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** All known branches for an app, ordered public-first then by name. Empty = no branch data. */
    public List<BranchRow> getBranches(int appId) {
        List<BranchRow> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT app_id,branch_name,pwd_required,build_id,time_updated,description" +
                " FROM steam_branches WHERE app_id = ?" +
                " ORDER BY (branch_name = 'public') DESC, branch_name COLLATE NOCASE",
                new String[]{String.valueOf(appId)})) {
            while (c.moveToNext()) {
                rows.add(new BranchRow(
                        c.getInt(0), c.getString(1), c.getInt(2) != 0,
                        c.getLong(3), c.getLong(4), c.getString(5)));
            }
        } catch (Exception ignored) {}
        return rows;
    }

    /** Drop all branch rows for an app (call before re-parsing a sync's branch list). */
    public void clearBranches(int appId) {
        getWritableDatabase().delete("steam_branches", "app_id = ?", new String[]{String.valueOf(appId)});
    }

    /** Persist a verified beta access password for a branch (unlocks it for selection + download). */
    public void insertUnlockedBranch(int appId, String branchName, String password) {
        ContentValues cv = new ContentValues();
        cv.put("app_id",      appId);
        cv.put("branch_name", branchName != null ? branchName : "");
        cv.put("password",    password != null ? password : "");
        getWritableDatabase().insertWithOnConflict(
                "steam_unlocked_branches", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Branch names the user has unlocked (verified a password for) for this app. */
    public List<String> getUnlockedBranchNames(int appId) {
        List<String> names = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT branch_name FROM steam_unlocked_branches WHERE app_id = ?",
                new String[]{String.valueOf(appId)})) {
            while (c.moveToNext()) names.add(c.getString(0));
        } catch (Exception ignored) {}
        return names;
    }

    /** The stored beta password for an unlocked branch, or null if the branch isn't unlocked. */
    public String getUnlockedBranchPassword(int appId, String branch) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT password FROM steam_unlocked_branches WHERE app_id = ? AND branch_name = ?",
                new String[]{String.valueOf(appId), branch != null ? branch : ""})) {
            if (c.moveToNext()) return c.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    // =========================================================================
    // steam_achievements
    // =========================================================================

    /** Insert or replace one achievement's schema + earned state (icon/iconGray are raw filenames). */
    public void upsertAchievement(int appId, String apiName, String displayName, String description,
                                  boolean hidden, String icon, String iconGray,
                                  boolean unlocked, long unlockTime) {
        ContentValues cv = new ContentValues();
        cv.put("app_id",       appId);
        cv.put("api_name",     apiName != null ? apiName : "");
        cv.put("display_name", displayName != null ? displayName : "");
        cv.put("description",  description != null ? description : "");
        cv.put("hidden",       hidden ? 1 : 0);
        cv.put("icon",         icon != null ? icon : "");
        cv.put("icon_gray",    iconGray != null ? iconGray : "");
        cv.put("unlocked",     unlocked ? 1 : 0);
        cv.put("unlock_time",  unlockTime);
        getWritableDatabase().insertWithOnConflict(
                "steam_achievements", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Batch upsert of an app's achievements in one transaction (called on every fetch). */
    public void upsertAchievements(int appId, List<AchievementRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (AchievementRow r : rows) {
                upsertAchievement(appId, r.apiName, r.displayName, r.description,
                        r.hidden, r.icon, r.iconGray, r.unlocked, r.unlockTime);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** All achievements for an app, unlocked-first then by display name. Empty = none cached yet. */
    public List<AchievementRow> getAchievements(int appId) {
        List<AchievementRow> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT app_id,api_name,display_name,description,hidden,icon,icon_gray,unlocked,unlock_time" +
                " FROM steam_achievements WHERE app_id = ?" +
                " ORDER BY unlocked DESC, display_name COLLATE NOCASE",
                new String[]{String.valueOf(appId)})) {
            while (c.moveToNext()) {
                rows.add(new AchievementRow(
                        c.getInt(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getInt(4) != 0, c.getString(5), c.getString(6),
                        c.getInt(7) != 0, c.getLong(8)));
            }
        } catch (Exception ignored) {}
        return rows;
    }

    /** One achievement by api_name, or null if not cached. */
    public AchievementRow getAchievement(int appId, String apiName) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT app_id,api_name,display_name,description,hidden,icon,icon_gray,unlocked,unlock_time" +
                " FROM steam_achievements WHERE app_id = ? AND api_name = ?",
                new String[]{String.valueOf(appId), apiName != null ? apiName : ""})) {
            if (c.moveToNext()) {
                return new AchievementRow(
                        c.getInt(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getInt(4) != 0, c.getString(5), c.getString(6),
                        c.getInt(7) != 0, c.getLong(8));
            }
        } catch (Exception ignored) {}
        return null;
    }

    // =========================================================================
    // steam_downloads
    // =========================================================================

    public static final String DL_QUEUED      = "queued";
    public static final String DL_DOWNLOADING = "downloading";
    public static final String DL_PAUSED      = "paused";
    public static final String DL_COMPLETE    = "complete";
    public static final String DL_FAILED      = "failed";

    /** Queue a new download (replaces any existing record for this appId). */
    public void queueDownload(int appId, long totalBytes, String installDir) {
        ContentValues cv = new ContentValues();
        cv.put("app_id",           appId);
        cv.put("status",           DL_QUEUED);
        cv.put("bytes_downloaded", 0L);
        cv.put("bytes_total",      totalBytes);
        cv.put("install_dir",      installDir != null ? installDir : "");
        cv.put("error_msg",        "");
        cv.put("added_at",         System.currentTimeMillis() / 1000L);
        getWritableDatabase().insertWithOnConflict(
                "steam_downloads", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void updateDownloadProgress(int appId, long bytesDownloaded) {
        ContentValues cv = new ContentValues();
        cv.put("status",           DL_DOWNLOADING);
        cv.put("bytes_downloaded", bytesDownloaded);
        getWritableDatabase().update(
                "steam_downloads", cv, "app_id = ?", new String[]{String.valueOf(appId)});
    }

    public void markDownloadComplete(int appId) {
        ContentValues cv = new ContentValues();
        cv.put("status", DL_COMPLETE);
        getWritableDatabase().update(
                "steam_downloads", cv, "app_id = ?", new String[]{String.valueOf(appId)});
    }

    public void markDownloadPaused(int appId, long bytesDownloaded) {
        ContentValues cv = new ContentValues();
        cv.put("status",           DL_PAUSED);
        cv.put("bytes_downloaded", bytesDownloaded);
        getWritableDatabase().update(
                "steam_downloads", cv, "app_id = ?", new String[]{String.valueOf(appId)});
    }

    /** Set status back to downloading (keeps bytes_downloaded intact for UI continuity). */
    public void markDownloadResuming(int appId) {
        ContentValues cv = new ContentValues();
        cv.put("status", DL_DOWNLOADING);
        getWritableDatabase().update(
                "steam_downloads", cv, "app_id = ?", new String[]{String.valueOf(appId)});
    }

    public void markDownloadFailed(int appId, String reason) {
        ContentValues cv = new ContentValues();
        cv.put("status",    DL_FAILED);
        cv.put("error_msg", reason != null ? reason : "");
        getWritableDatabase().update(
                "steam_downloads", cv, "app_id = ?", new String[]{String.valueOf(appId)});
    }

    public void deleteDownload(int appId) {
        getWritableDatabase().delete(
                "steam_downloads", "app_id = ?", new String[]{String.valueOf(appId)});
    }

    /** Download row for a specific app, or null if not in table. */
    public DownloadRow getDownload(int appId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT app_id,status,bytes_downloaded,bytes_total,install_dir,error_msg" +
                " FROM steam_downloads WHERE app_id = ?",
                new String[]{String.valueOf(appId)})) {
            if (!c.moveToFirst()) return null;
            return new DownloadRow(
                    c.getInt(0), c.getString(1), c.getLong(2),
                    c.getLong(3), c.getString(4), c.getString(5));
        }
    }

    /** All downloads not yet complete or failed. */
    public List<DownloadRow> getActiveDownloads() {
        List<DownloadRow> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT app_id,status,bytes_downloaded,bytes_total,install_dir,error_msg" +
                " FROM steam_downloads WHERE status NOT IN ('complete','failed')" +
                " ORDER BY added_at", null)) {
            while (c.moveToNext()) {
                rows.add(new DownloadRow(
                        c.getInt(0), c.getString(1), c.getLong(2),
                        c.getLong(3), c.getString(4), c.getString(5)));
            }
        }
        return rows;
    }
}
