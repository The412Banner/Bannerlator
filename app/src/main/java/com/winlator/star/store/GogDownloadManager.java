package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * GOG Gen 1 + Gen 2 download pipeline, mirroring BannerHub 5.3.5.
 *
 * startDownload() spawns a background thread.  Progress is reported via
 * Callback, which must post to the main thread before touching any Views.
 *
 * Pipeline (Gen 2):
 *   1. builds?generation=2 → build ID → manifest URL
 *   2. fetch + decompress manifest → installDirectory, depots[]
 *   3. per depot: fetch + decompress manifest → collect DepotFiles
 *   4. secure_link → CDN base URL
 *   5. per file: download chunks → zlib inflate → assemble
 *   6. write _gog_manifest.json, report done
 *
 * Fallback (Gen 1):
 *   1. builds?generation=1 → manifest URL
 *   2. per file: Range-based byte download → assemble
 */
public final class GogDownloadManager {

    private static final String TAG = "BH_GOG_DL";
    private static final int TIMEOUT = 30_000;
    /** Rust engine only: minimum inflate/hash/write pool (stream mode pins one chunk per worker). */
    private static final int RUST_MIN_PROCESS_WORKERS = 16;

    public interface Callback {
        void onProgress(String msg, int pct);
        void onComplete(String exePath);
        void onError(String msg);
        default void onCancelled() {}
        /**
         * Called when multiple executable candidates are found and the user must
         * choose.  {@code candidates} is a list of absolute paths.  Call
         * {@code onSelected.accept(path)} with the chosen path to continue.
         * Default: pick the first candidate automatically.
         */
        default void onSelectExe(java.util.List<String> candidates,
                                  java.util.function.Consumer<String> onSelected) {
            if (!candidates.isEmpty()) onSelected.accept(candidates.get(0));
        }
    }

    private GogDownloadManager() {}

    /**
     * Starts the download in a background thread.
     * Returns a cancel Runnable: call it to stop the download and delete any
     * partially downloaded files for this game.
     */
    public static Runnable startDownload(Context ctx, GogGame game, Callback cb) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<File> installDirRef = new AtomicReference<>(null);
        Thread t = new Thread(() -> doDownload(ctx, game, cb, cancelled, installDirRef),
                "gog-dl-" + game.gameId);
        t.start();
        return () -> {
            cancelled.set(true);
            File dir = installDirRef.get();
            if (dir != null) deleteDir(dir);
            cb.onCancelled();
        };
    }

    /**
     * Verify / Repair entry point.  Clears the completion marker and re-runs the
     * normal download: the per-file size+MD5 resume check ({@link #fileVerified})
     * then re-pulls only the files that are missing or fail integrity — i.e. a
     * repair.  Returns the same cancel Runnable as {@link #startDownload}.
     */
    public static Runnable verifyRepair(Context ctx, GogGame game, Callback cb) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("bh_gog_prefs", 0);
            String dirName = prefs.getString("gog_dir_" + game.gameId, game.title);
            File installPath = GogInstallPath.getInstallDir(ctx, dirName);
            File marker = new File(installPath, "_gog_manifest.json");
            if (marker.exists()) marker.delete();
        } catch (Exception ignored) {}
        return startDownload(ctx, game, cb);
    }

    /**
     * gap#5 — installs one owned DLC into an already-installed base game.
     *
     * DLC lives in the SAME base build manifest, tagged by {@code productId}; its chunks live in a
     * per-product CDN namespace behind a per-product, entitlement-gated secure link. So this:
     *   1. re-fetches the base build manifest (shared head),
     *   2. collects depots where {@code depot.productId == dlcProductId} (+ language compat),
     *   3. requests a secure link for the DLC's OWN productId (403 ⇒ not owned / not propagated),
     *   4. downloads + MD5-verifies into the SAME base install dir via {@link #assembleDepotFile},
     *   5. records a per-DLC installed marker + written-file list (for a future per-DLC uninstall).
     *
     * Idempotent (per-file size+MD5 skip). Fail-soft: a DLC failure NEVER touches the base game.
     * Mirrors {@link #startDownload}'s threading; the returned Runnable cancels (it does NOT delete
     * anything — DLC files interleave into the base dir, which must survive a cancel).
     */
    public static Runnable installDlc(Context ctx, GogGame baseGame, String dlcProductId,
                                       String dlcTitle, Callback cb) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread t = new Thread(
                () -> doInstallDlc(ctx, baseGame, dlcProductId, dlcTitle, cb, cancelled),
                "gog-dlc-" + baseGame.gameId + "-" + dlcProductId);
        t.start();
        return () -> { cancelled.set(true); cb.onCancelled(); };
    }

    /** True if this DLC's per-DLC installed marker is set. */
    public static boolean isDlcInstalled(Context ctx, String baseId, String dlcProductId) {
        try {
            return ctx.getSharedPreferences("bh_gog_prefs", 0)
                    .getBoolean("gog_dlc_installed_" + baseId + "_" + dlcProductId, false);
        } catch (Exception e) { return false; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private static void doDownload(Context ctx, GogGame game, Callback cb,
                                    AtomicBoolean cancelled, AtomicReference<File> installDirRef) {
        StringBuilder dbg = new StringBuilder();
        dbg.append("=== BH GOG Debug === game=").append(game.gameId)
           .append(" title=").append(game.title).append("\n");
        try {
            if (cancelled.get()) return;
            cb.onProgress("Checking token…", 0);

            SharedPreferences prefs = ctx.getSharedPreferences("bh_gog_prefs", 0);
            String token = prefs.getString("access_token", null);
            if (token == null) { cb.onError("Not logged in to GOG"); return; }

            int loginTime  = prefs.getInt("bh_gog_login_time", 0);
            int expiresIn  = prefs.getInt("bh_gog_expires_in", 3600);
            int nowSec     = (int) (System.currentTimeMillis() / 1000L);
            if (loginTime == 0 || nowSec >= loginTime + expiresIn) {
                cb.onProgress("Refreshing token…", 0);
                String newToken = GogTokenRefresh.refresh(ctx);
                if (newToken == null) { cb.onError("Token expired — please sign in again"); return; }
                token = newToken;
            }
            dbg.append("token OK\n");

            if (cancelled.get()) return;
            cb.onProgress("Fetching builds…", 2);

            // Try Gen 2 — builds list is public, no auth needed; fall back to authed if null
            String buildsUrl = "https://content-system.gog.com/products/" + game.gameId
                    + "/os/windows/builds?generation=2";
            String buildsJson = httpGet(buildsUrl, null);
            if (buildsJson == null) buildsJson = httpGet(buildsUrl, token);
            dbg.append("gen2_builds_url=").append(buildsUrl).append("\n");
            dbg.append("gen2_builds_response=").append(buildsJson == null ? "NULL"
                    : buildsJson.substring(0, Math.min(300, buildsJson.length()))).append("\n");

            if (buildsJson != null) {
                String err = runGen2(ctx, game, token, buildsJson, cb, dbg, cancelled, installDirRef);
                if (err == null) { writeDebug(ctx, dbg); return; }
                if (err.startsWith("DISK_GUARD:")) {
                    // Hard stop — user already notified via cb.onError; do NOT fall through
                    // to Gen1 (same install would need the same missing space).
                    dbg.append("gen2_disk_guard=").append(err).append("\n");
                    writeDebug(ctx, dbg);
                    return;
                }
                dbg.append("gen2_failed=").append(err).append("\n");
            }

            if (cancelled.get()) return;
            cb.onProgress("Gen 2 unavailable, trying Gen 1…", 10);

            // Fallback Gen 1
            String builds1Url = "https://content-system.gog.com/products/" + game.gameId
                    + "/os/windows/builds?generation=1";
            String builds1Json = httpGet(builds1Url, null);
            if (builds1Json == null) builds1Json = httpGet(builds1Url, token);
            dbg.append("gen1_builds_response=").append(builds1Json == null ? "NULL"
                    : builds1Json.substring(0, Math.min(300, builds1Json.length()))).append("\n");
            if (builds1Json == null) {
                writeDebug(ctx, dbg);
                cb.onError("No builds available for this game"); return;
            }
            String err1 = runGen1(ctx, game, token, builds1Json, cb, dbg, cancelled, installDirRef);
            if (err1 != null) {
                dbg.append("gen1_failed=").append(err1).append("\n");

                // Both gen1 and gen2 empty → old installer system, fall back to direct download
                if ("NO_CS_BUILDS".equals(err1)) {
                    cb.onProgress("No Galaxy builds — trying installer download…", 12);
                    String installerErr = runInstaller(ctx, game, token, cb, dbg, cancelled, installDirRef);
                    if (installerErr == null) { writeDebug(ctx, dbg); return; }
                    dbg.append("installer_failed=").append(installerErr).append("\n");
                    writeDebug(ctx, dbg);
                    cb.onError("No downloadable builds for this game");
                } else {
                    writeDebug(ctx, dbg);
                    cb.onError("Download failed: " + err1);
                }
            } else {
                writeDebug(ctx, dbg);
            }
        } catch (Exception e) {
            dbg.append("EXCEPTION=").append(e).append("\n");
            writeDebug(ctx, dbg);
            cb.onError("Download error: " + e.getMessage());
        }
    }

    private static void writeDebug(Context ctx, StringBuilder dbg) {
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            java.io.File f = new java.io.File(dir, "bh_gog_debug.txt");
            writeFile(f, dbg.toString().getBytes("UTF-8"));
            Log.i(TAG, "Debug written to: " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "writeDebug failed", e);
        }
    }

    // Resolve the download-pool thread count. Default heuristic is (cores * 2)
    // clamped to [6, 16] — low-core devices stay conservative, fast phones scale
    // up without hammering the CDN or device I/O. An optional user override lives
    // in bh_gog_prefs under "gog_dl_threads" (>=1 wins; <1 or absent = auto).
    // This is our clean-room DownloadSpeedConfig equivalent: a plain int knob.
    private static int resolveDownloadThreads(Context ctx) {
        try {
            int override = ctx.getSharedPreferences("bh_gog_prefs", 0)
                    .getInt("gog_dl_threads", 0);
            if (override >= 1) return override;
        } catch (Exception ignored) {
            // Absent/invalid pref → fall through to the auto heuristic.
        }
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = cores * 2;
        if (threads < 6)  threads = 6;
        if (threads > 16) threads = 16;
        return threads;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gen 2 pipeline
    // ─────────────────────────────────────────────────────────────────────────

    // Returns null on success, error description string on failure.
    private static String runGen2(Context ctx, GogGame game, String token,
                                   String buildsJson, Callback cb, StringBuilder dbg,
                                   AtomicBoolean cancelled, AtomicReference<File> installDirRef) {
        try {
            dbg.append("\n--- Gen2 ---\n");
            cb.onProgress("Fetching manifest…", 5);
            // Shared build-manifest head (token → builds → windows build → meta → decompress → JSON),
            // reused by installDlc() so the fetch/decompress logic lives in one place.
            Gen2Manifest gm = resolveGen2Manifest(buildsJson, token, dbg);
            if (gm == null) return "gen2 manifest resolve failed";
            String buildId = gm.buildId;
            JSONObject manifest = gm.manifest;
            dbg.append("buildId=").append(buildId).append("\n");
            String installDir = manifest.optString("installDirectory", game.title);
            String manifestClientId     = manifest.optString("clientId", null);
            String manifestClientSecret = manifest.optString("clientSecret", null);
            JSONArray depots  = manifest.optJSONArray("depots");
            if (depots == null)
                return "no depots in manifest; keys=" + manifest.keys().toString();
            dbg.append("installDir=").append(installDir).append(" depots=").append(depots.length()).append("\n");

            // gap#3 (GOG redists / ISI): the build manifest carries the game's required GOG
            // "dependencies" (repository redist ids, e.g. ["MSVC2017_x64"]) and a scriptInterpreter
            // flag at the SAME level as depots. Persist them keyed by gameId so the redist installer
            // (GogRedistInstaller) and a future ISI step can read them at add-to-container time. This
            // is gen2-only — the gen1/installer paths are untouched.
            persistDependencies(ctx, game.gameId,
                    manifest.optJSONArray("dependencies"),
                    manifest.optBoolean("scriptInterpreter", false), dbg);

            // Extract temp_executable from products[0] (primary exe hint)
            String tempExe = null;
            JSONArray products = manifest.optJSONArray("products");
            if (products != null && products.length() > 0) {
                tempExe = products.getJSONObject(0).optString("temp_executable", null);
                if (tempExe != null && tempExe.isEmpty()) tempExe = null;
            }
            dbg.append("tempExe=").append(tempExe).append("\n");

            // gap#5 base-install fix: the base build manifest lists EVERY product's depots (base +
            // DLC), each tagged by its own productId, with a top-level baseProductId. Restrict the
            // base install to the BASE product's depots. Without this, DLC depots that pass the
            // language filter get pulled under the base secure_link — which is path-scoped to the
            // base product (§2 of the plan) → 403 on DLC chunks → the whole install fails. DLC is
            // installed separately via installDlc() with its own per-product secure link.
            // Type coercion: productId is numeric in JSON; optString() coerces both sides to the
            // same string form so "1508702879" == "1508702879".
            String baseProductId = manifest.optString("baseProductId", null);
            if (baseProductId == null || baseProductId.isEmpty()) {
                if (products != null && products.length() > 0)
                    baseProductId = products.getJSONObject(0).optString("productId", null);
            }
            if (baseProductId == null || baseProductId.isEmpty()) baseProductId = game.gameId;
            dbg.append("baseProductId=").append(baseProductId).append("\n");

            // Collect DepotFiles from each language-compatible BASE depot
            cb.onProgress("Reading depot manifests…", 10);
            List<DepotFile> files = new ArrayList<>();
            // The same inflated depot manifests, kept for the Rust engine (it parses them with the
            // same rules as parseDepotManifest — see docs/RUST_GOG_PARITY.md §3).
            List<String> depotJsons = new ArrayList<>();
            int baseDepotCount = 0, skippedDlcDepotCount = 0;
            for (int i = 0; i < depots.length(); i++) {
                JSONObject depot = depots.getJSONObject(i);
                // Skip any depot NOT belonging to the base product. Depots with no productId
                // (older / single-product manifests, e.g. ELDERBORN) are always kept so single-
                // product titles install exactly as before.
                String depotPid = depot.optString("productId", "");
                if (!depotPid.isEmpty() && !depotPid.equals(baseProductId)) {
                    skippedDlcDepotCount++;
                    dbg.append("depot[").append(i).append("] skipped: productId=").append(depotPid)
                       .append(" != base\n");
                    continue;
                }
                baseDepotCount++;
                boolean compatible = languageCompatible(depot);
                dbg.append("depot[").append(i).append("] langs=").append(depot.optJSONArray("languages"))
                   .append(" compat=").append(compatible).append("\n");
                if (!compatible) continue;

                // "manifest" field is a hash — build CDN URL from it
                String manifestHash = depot.optString("manifest");
                if (manifestHash == null || manifestHash.isEmpty()) continue;
                String metaUrl = "https://gog-cdn-fastly.gog.com/content-system/v2/meta/"
                        + buildCdnPath(manifestHash);
                dbg.append("depot[").append(i).append("] metaUrl=").append(metaUrl).append("\n");

                byte[] dmRaw = fetchBytes(metaUrl, null);  // CDN, no auth needed
                if (dmRaw == null) {
                    dbg.append("depot[").append(i).append("] meta fetch FAILED\n");
                    continue;
                }
                String dmStr = decompressBytes(dmRaw);
                if (dmStr == null) {
                    dbg.append("depot[").append(i).append("] decompress FAILED\n");
                    continue;
                }

                dbg.append("depot[").append(i).append("] manifest=")
                   .append(dmStr.substring(0, Math.min(500, dmStr.length()))).append("\n");
                int before = files.size();
                parseDepotManifest(dmStr, files);
                depotJsons.add(dmStr);
                dbg.append("depot[").append(i).append("] added ").append(files.size() - before).append(" files\n");
            }

            dbg.append("base depot filter: base=").append(baseDepotCount)
               .append(" skippedDlc=").append(skippedDlcDepotCount).append("\n");
            GogCloudSaveManager.debug(ctx, "DLC: base install productId=" + baseProductId
                    + " baseDepots=" + baseDepotCount + " skippedDlcDepots=" + skippedDlcDepotCount);
            if (files.isEmpty()) return "no depot files collected after processing all depots";
            dbg.append("total files=").append(files.size()).append("\n");

            // Fetch CDN base URL via secure_link (base product — path-scoped to baseProductId)
            cb.onProgress("Fetching CDN link…", 15);
            String secureLinkUrl = "https://content-system.gog.com/products/" + baseProductId
                    + "/secure_link?_version=2&generation=2&path=/";
            String secureLinkJson = httpGet(secureLinkUrl, token);
            dbg.append("secure_link_url=").append(secureLinkUrl).append("\n");
            dbg.append("secure_link_response=").append(secureLinkJson == null ? "NULL"
                    : secureLinkJson.substring(0, Math.min(400, secureLinkJson.length()))).append("\n");
            String cdnBase = parseCdnUrl(secureLinkJson);
            dbg.append("cdnBase=").append(cdnBase).append("\n");
            if (cdnBase == null)
                return "cdnBase null; secure_link_response=" + (secureLinkJson == null ? "NULL"
                        : secureLinkJson.substring(0, Math.min(200, secureLinkJson.length())));

            // Install dir
            File installPath = GogInstallPath.getInstallDir(ctx, installDir);
            installPath.mkdirs();
            installDirRef.set(installPath);
            File chunksDir = new File(installPath, ".gog_chunks");
            chunksDir.mkdirs();

            // Free-space guard: sum planned (decompressed) size, compare to usable bytes
            // on the install target. Hard-stop (via DISK_GUARD sentinel) before writing.
            long plannedBytes = 0;
            for (DepotFile df : files) plannedBytes += df.totalSize;
            if (plannedBytes > 0) {
                long usable = installPath.getUsableSpace();
                dbg.append("disk guard: planned=").append(plannedBytes)
                   .append(" usable=").append(usable).append("\n");
                if (usable > 0 && plannedBytes > usable) {
                    cb.onError("Not enough free space: need " + formatBytes(plannedBytes)
                            + ", only " + formatBytes(usable) + " free");
                    return "DISK_GUARD: need=" + plannedBytes + " usable=" + usable;
                }
            }

            // Largest-file-first (LPT) scheduling: sort the already-filtered install
            // list by descending total (decompressed) size so the biggest archives
            // start first and small files fill the tail — no lone-giant straggler at
            // the end. Pure ordering change; runs AFTER the base-product/language
            // filters so those selections are untouched. df.totalSize == Σ chunk.size.
            java.util.Collections.sort(files, (a, b) -> Long.compare(b.totalSize, a.totalSize));

            // Download + assemble files — pool size resolved by resolveDownloadThreads
            final int downloadThreads = resolveDownloadThreads(ctx);
            final int total = files.size();
            final AtomicInteger doneCount    = new AtomicInteger(0);
            final AtomicLong    totalBytes   = new AtomicLong(0);
            final AtomicLong    lastSpeedMs  = new AtomicLong(System.currentTimeMillis());
            final AtomicLong    lastSpeedB   = new AtomicLong(0);
            final AtomicLong    speedBps     = new AtomicLong(0);
            final AtomicBoolean anyFailed    = new AtomicBoolean(false);
            final AtomicReference<String> cdnBaseRef = new AtomicReference<>(cdnBase);
            final AtomicInteger cdnRefreshCount = new AtomicInteger(0);
            final int    MAX_CDN_REFRESH = 5;
            final String fSecureLinkUrl  = secureLinkUrl;
            final java.util.concurrent.ConcurrentLinkedQueue<String> fileLog2 =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();
            dbg.append("gen2 parallel download: ").append(total)
               .append(" files, ").append(downloadThreads).append(" threads (largest-first)\n");

            // Engine switch (docs/RUST_GOG_PARITY.md): the Rust engine replaces ONLY this
            // fetch/inflate/verify/assemble loop. Same resume/skip rule, same .bhtmp staging +
            // atomic rename, same progress strings + pct math, same secure-link refresh cap,
            // same anyFailed / cancelled semantics — everything before and after is shared.
            final boolean useRustEngine = useRustEngine(ctx);
            dbg.append("engine=").append(useRustEngine ? "rust" : "java").append("\n");
            if (useRustEngine) {
                runRustChunkEngine(ctx, com.winlator.star.store.blsteam.BlGogDownload.KIND_GEN2_CHUNKS,
                        "Verified…", depotJsons, installPath,
                        cdnBaseRef, fSecureLinkUrl, token, cdnRefreshCount, MAX_CDN_REFRESH,
                        downloadThreads, downloadThreads, true, "gog base=" + baseProductId,
                        cancelled, anyFailed, doneCount, total, totalBytes,
                        lastSpeedMs, lastSpeedB, speedBps, cb, true, null, fileLog2);
            } else {
            ExecutorService pool = Executors.newFixedThreadPool(downloadThreads);
            List<Future<Void>> futures = new ArrayList<>();
            for (DepotFile df : files) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    if (cancelled.get() || anyFailed.get()) return null;
                    File outFile = new File(installPath, df.relativePath);
                    outFile.getParentFile().mkdirs();

                    // Resume / repair: skip only if the existing file passes size+MD5.
                    // A corrupt or truncated partial fails this and is re-downloaded.
                    if (fileVerified(outFile, df.totalSize, df.md5)) {
                        int done = doneCount.incrementAndGet();
                        int pct  = 15 + (int) ((done / (float) total) * 80);
                        cb.onProgress("Verified…", pct);
                        return null;
                    }

                    // Download + verify + assemble via the shared MD5-verified chunk path (also used
                    // by the dependency-redist assembler). On success the whole file is size+MD5 clean.
                    boolean ok = assembleDepotFile(
                            df, outFile, cdnBaseRef, fSecureLinkUrl, token,
                            cdnRefreshCount, MAX_CDN_REFRESH, cancelled, fileLog2);
                    if (cancelled.get()) return null;
                    if (!ok) {
                        fileLog2.add("FAIL file=" + df.relativePath);
                        Log.e(TAG, "Gen2 file failed integrity/download: " + df.relativePath);
                        anyFailed.set(true);
                        return null;
                    }
                    long fileBytes = df.totalSize;
                    int done = doneCount.incrementAndGet();
                    long tb  = totalBytes.addAndGet(fileBytes);
                    int pct  = 15 + (int) ((done / (float) total) * 80);
                    long nowMs  = System.currentTimeMillis();
                    long prevMs = lastSpeedMs.get();
                    if (nowMs - prevMs >= 500 && lastSpeedMs.compareAndSet(prevMs, nowMs)) {
                        long prevB = lastSpeedB.getAndSet(tb);
                        long dt = nowMs - prevMs;
                        if (dt > 0) speedBps.set((tb - prevB) * 1000L / dt);
                    }
                    String speedStr = formatSpeed(speedBps.get());
                    String name = df.relativePath.contains("/")
                            ? df.relativePath.substring(df.relativePath.lastIndexOf('/') + 1)
                            : df.relativePath;
                    cb.onProgress("Downloading: " + name
                            + (speedStr.isEmpty() ? "" : "  " + speedStr), pct);
                    return null;
                }));
            }
            pool.shutdown();
            try {
                for (Future<Void> f : futures) f.get();
            } catch (Exception e) {
                pool.shutdownNow();
                return "parallel download error: " + e;
            }
            } // end Java engine
            for (String line : fileLog2) dbg.append(line).append("\n");
            if (cancelled.get()) return "cancelled";
            if (anyFailed.get()) return "one or more chunks failed to download";
            dbg.append("gen2 download complete: ").append(doneCount.get()).append(" files OK\n");

            // Write manifest marker
            String manifestMarker = "{\"gameId\":\"" + game.gameId
                    + "\",\"installDir\":\"" + installDir + "\"}";
            writeFile(new File(installPath, "_gog_manifest.json"), manifestMarker.getBytes("UTF-8"));

            // Delete chunks temp dir
            deleteDir(chunksDir);

            cb.onProgress("Install complete!", 100);

            // Save install dir + build ID + client ID before exe resolution
            SharedPreferences.Editor ed0 = ctx.getSharedPreferences("bh_gog_prefs", 0).edit();
            ed0.putString("gog_dir_" + game.gameId, installDir);
            if (buildId != null && !buildId.isEmpty()) {
                ed0.putString("gog_build_" + game.gameId, buildId);
            }
            if (manifestClientId != null && !manifestClientId.isEmpty()) {
                ed0.putString("gog_client_id_" + game.gameId, manifestClientId);
            }
            if (manifestClientSecret != null && !manifestClientSecret.isEmpty()) {
                ed0.putString("gog_client_secret_" + game.gameId, manifestClientSecret);
            }
            ed0.apply();

            // Find exe — prefer temp_executable hint from manifest
            if (tempExe != null) {
                File hinted = new File(installPath, tempExe);
                if (hinted.exists()) {
                    String exePath = hinted.getAbsolutePath();
                    ctx.getSharedPreferences("bh_gog_prefs", 0).edit()
                            .putString("gog_exe_" + game.gameId, exePath).apply();
                    cb.onComplete(exePath);
                    return null;
                }
            }

            // Collect all candidates; let user pick if ambiguous
            List<String> candidates = collectExeCandidates(installPath);
            if (candidates.size() == 1) {
                String exePath = candidates.get(0);
                ctx.getSharedPreferences("bh_gog_prefs", 0).edit()
                        .putString("gog_exe_" + game.gameId, exePath).apply();
                cb.onComplete(exePath);
            } else if (candidates.size() > 1) {
                cb.onSelectExe(candidates, selected -> {
                    if (selected != null && !selected.isEmpty()) {
                        ctx.getSharedPreferences("bh_gog_prefs", 0).edit()
                                .putString("gog_exe_" + game.gameId, selected).apply();
                    }
                    cb.onComplete(selected != null ? selected : "");
                });
            } else {
                cb.onComplete(""); // no exe found
            }
            return null; // success
        } catch (Exception e) {
            return "exception: " + e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared gen2 build-manifest head + language predicate
    // ─────────────────────────────────────────────────────────────────────────

    /** Parsed gen2 windows build manifest (shared by base install + DLC install). */
    private static final class Gen2Manifest {
        final JSONObject manifest;
        final String buildId;
        final String manifestUrl;
        Gen2Manifest(JSONObject manifest, String buildId, String manifestUrl) {
            this.manifest = manifest; this.buildId = buildId; this.manifestUrl = manifestUrl;
        }
    }

    /**
     * Picks the first windows build from a {@code builds?generation=2} response, fetches its meta
     * manifest, decompresses, and parses to JSON. Returns null on any failure (a short reason is
     * appended to {@code dbg}). Shared by {@link #runGen2} and {@link #doInstallDlc}.
     */
    private static Gen2Manifest resolveGen2Manifest(String buildsJson, String token, StringBuilder dbg) {
        try {
            JSONObject builds = new JSONObject(buildsJson);
            JSONArray items = builds.optJSONArray("items");
            if (items == null || items.length() == 0) { dbg.append("resolveGen2Manifest: no items\n"); return null; }
            dbg.append("items=").append(items.length()).append("\n");
            String buildId = null, manifestUrl = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if ("windows".equals(item.optString("os"))) {
                    buildId     = item.optString("build_id");
                    manifestUrl = item.optString("link");
                    if (manifestUrl == null || manifestUrl.isEmpty())
                        manifestUrl = item.optString("meta_url");
                    break;
                }
            }
            if (buildId == null || manifestUrl == null || manifestUrl.isEmpty()) {
                dbg.append("resolveGen2Manifest: no windows build / manifest URL empty\n"); return null;
            }
            byte[] manifestRaw = fetchBytes(manifestUrl, token);
            if (manifestRaw == null) { dbg.append("resolveGen2Manifest: manifest fetch null\n"); return null; }
            String manifestStr = decompressBytes(manifestRaw);
            if (manifestStr == null) { dbg.append("resolveGen2Manifest: decompress failed\n"); return null; }
            return new Gen2Manifest(new JSONObject(manifestStr), buildId, manifestUrl);
        } catch (Exception e) {
            dbg.append("resolveGen2Manifest exception: ").append(e).append("\n");
            return null;
        }
    }

    /** English/all-language depot predicate (shared by base install + DLC install). */
    private static boolean languageCompatible(JSONObject depot) {
        JSONArray languages = depot.optJSONArray("languages");
        if (languages == null || languages.length() == 0) return true;
        String langsStr = languages.toString();
        return langsStr.contains("*") || langsStr.contains("en-US")
                || langsStr.contains("\"en\"") || langsStr.contains("english");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DLC install (gap#5)
    // ─────────────────────────────────────────────────────────────────────────

    private static void doInstallDlc(Context ctx, GogGame baseGame, String dlcProductId,
                                      String dlcTitle, Callback cb, AtomicBoolean cancelled) {
        final String baseId = baseGame.gameId;
        final String dlgTag = "DLC: " + dlcProductId + " (" + dlcTitle + ") base=" + baseId;
        StringBuilder dbg = new StringBuilder();
        try {
            GogCloudSaveManager.debug(ctx, dlgTag + " install start");
            if (cancelled.get()) return;

            SharedPreferences prefs = ctx.getSharedPreferences("bh_gog_prefs", 0);

            // Base must be installed — DLC files interleave into the base install dir.
            String dirName = prefs.getString("gog_dir_" + baseId, null);
            if (dirName == null) {
                GogCloudSaveManager.debug(ctx, dlgTag + " ABORT base not installed");
                cb.onError("Install the game first"); return;
            }

            boolean already = isDlcInstalled(ctx, baseId, dlcProductId);
            if (already) GogCloudSaveManager.debug(ctx, dlgTag + " already marked installed — verify-and-skip run");

            cb.onProgress("Checking token…", 0);
            String token = prefs.getString("access_token", null);
            if (token == null) { cb.onError("Not logged in to GOG"); return; }
            int loginTime = prefs.getInt("bh_gog_login_time", 0);
            int expiresIn = prefs.getInt("bh_gog_expires_in", 3600);
            int nowSec    = (int) (System.currentTimeMillis() / 1000L);
            if (loginTime == 0 || nowSec >= loginTime + expiresIn) {
                cb.onProgress("Refreshing token…", 0);
                String newToken = GogTokenRefresh.refresh(ctx);
                if (newToken == null) { cb.onError("Token expired — please sign in again"); return; }
                token = newToken;
            }

            if (cancelled.get()) return;

            // Re-fetch the base build manifest (DLC lives in the SAME manifest, tagged by productId).
            cb.onProgress("Fetching manifest…", 3);
            String buildsUrl = "https://content-system.gog.com/products/" + baseId
                    + "/os/windows/builds?generation=2";
            String buildsJson = httpGet(buildsUrl, null);
            if (buildsJson == null) buildsJson = httpGet(buildsUrl, token);
            if (buildsJson == null) {
                GogCloudSaveManager.debug(ctx, dlgTag + " builds fetch null");
                cb.onError("DLC install failed: no builds"); return;
            }
            Gen2Manifest gm = resolveGen2Manifest(buildsJson, token, dbg);
            if (gm == null) {
                GogCloudSaveManager.debug(ctx, dlgTag + " manifest resolve failed: " + dbg);
                cb.onError("DLC install failed: manifest unavailable"); return;
            }
            JSONObject manifest = gm.manifest;
            JSONArray depots = manifest.optJSONArray("depots");
            if (depots == null) { cb.onError("DLC install failed: no depots"); return; }

            // Collect the DLC's own depots (productId == dlcProductId) + language compat.
            cb.onProgress("Reading DLC manifests…", 8);
            List<DepotFile> files = new ArrayList<>();
            List<String> depotJsons = new ArrayList<>();   // for the Rust engine (same strings)
            int matched = 0;
            for (int i = 0; i < depots.length(); i++) {
                if (cancelled.get()) return;
                JSONObject depot = depots.getJSONObject(i);
                if (!dlcProductId.equals(depot.optString("productId", ""))) continue;
                matched++;
                if (!languageCompatible(depot)) continue;
                String manifestHash = depot.optString("manifest");
                if (manifestHash == null || manifestHash.isEmpty()) continue;
                String metaUrl = "https://gog-cdn-fastly.gog.com/content-system/v2/meta/"
                        + buildCdnPath(manifestHash);
                byte[] dmRaw = fetchBytes(metaUrl, null); // CDN, no auth
                if (dmRaw == null) continue;
                String dmStr = decompressBytes(dmRaw);
                if (dmStr == null) continue;
                parseDepotManifest(dmStr, files);
                depotJsons.add(dmStr);
            }
            GogCloudSaveManager.debug(ctx, dlgTag + " depots matched=" + matched + " files=" + files.size());

            // A product with no depots of its own (data already ships in the base) → nothing to
            // download. Mark installed and finish cleanly (not an error).
            if (files.isEmpty()) {
                markDlcInstalled(ctx, baseId, dlcProductId, new ArrayList<>());
                GogCloudSaveManager.debug(ctx, dlgTag + " no own depots — content included in base, marked installed");
                cb.onProgress("Already included in the base game", 100);
                cb.onComplete("");
                return;
            }

            // The DLC's OWN secure link (path-scoped to dlcProductId). 403/parse-fail ⇒ not owned
            // (server-side entitlement gate) or the licence hasn't propagated yet.
            cb.onProgress("Fetching CDN link…", 12);
            String secureLinkUrl = "https://content-system.gog.com/products/" + dlcProductId
                    + "/secure_link?_version=2&generation=2&path=/";
            String secureLinkJson = httpGet(secureLinkUrl, token);
            String cdnBase = parseCdnUrl(secureLinkJson);
            if (cdnBase == null) {
                GogCloudSaveManager.debug(ctx, dlgTag + " secure_link FAILED (403/entitlement) resp="
                        + (secureLinkJson == null ? "NULL"
                           : secureLinkJson.substring(0, Math.min(160, secureLinkJson.length()))));
                cb.onError("You may not own this DLC (or the licence hasn't propagated yet)");
                return;
            }
            GogCloudSaveManager.debug(ctx, dlgTag + " secure_link OK");

            // Assemble into the SAME base install dir (never a separate target).
            File installPath = GogInstallPath.getInstallDir(ctx, dirName);
            installPath.mkdirs();

            // Free-space guard before writing.
            long plannedBytes = 0;
            for (DepotFile df : files) plannedBytes += df.totalSize;
            if (plannedBytes > 0) {
                long usable = installPath.getUsableSpace();
                if (usable > 0 && plannedBytes > usable) {
                    GogCloudSaveManager.debug(ctx, dlgTag + " disk guard need=" + plannedBytes + " usable=" + usable);
                    cb.onError("Not enough free space: need " + formatBytes(plannedBytes)
                            + ", only " + formatBytes(usable) + " free");
                    return;
                }
            }

            final int total = files.size();
            final AtomicInteger doneCount = new AtomicInteger(0);
            final AtomicBoolean anyFailed = new AtomicBoolean(false);
            final AtomicReference<String> cdnBaseRef = new AtomicReference<>(cdnBase);
            final AtomicInteger cdnRefreshCount = new AtomicInteger(0);
            final int MAX_CDN_REFRESH = 5;
            final String fSecureLinkUrl = secureLinkUrl;
            final String fToken = token;
            final File fInstallPath = installPath;
            final java.util.concurrent.ConcurrentLinkedQueue<String> fileLog =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();
            final java.util.concurrent.ConcurrentLinkedQueue<String> written =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();

            // Engine switch — see runGen2. DLC: fixed 8 workers, manifest order, no speed string.
            final boolean useRustEngine = useRustEngine(ctx);
            GogCloudSaveManager.debug(ctx, dlgTag + " engine=" + (useRustEngine ? "rust" : "java"));
            if (useRustEngine) {
                runRustChunkEngine(ctx, com.winlator.star.store.blsteam.BlGogDownload.KIND_GEN2_CHUNKS,
                        "Verified…", depotJsons, fInstallPath,
                        cdnBaseRef, fSecureLinkUrl, fToken, cdnRefreshCount, MAX_CDN_REFRESH,
                        8, 8, false, "gog dlc=" + dlcProductId + " base=" + baseId,
                        cancelled, anyFailed, doneCount, total, new AtomicLong(0),
                        new AtomicLong(System.currentTimeMillis()), new AtomicLong(0), new AtomicLong(0),
                        cb, false, written, fileLog);
            } else {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<Void>> futures = new ArrayList<>();
            for (DepotFile df : files) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    if (cancelled.get() || anyFailed.get()) return null;
                    File outFile = new File(fInstallPath, df.relativePath);
                    if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();

                    // Idempotent resume: skip if the existing file already passes size+MD5.
                    if (fileVerified(outFile, df.totalSize, df.md5)) {
                        written.add(df.relativePath);
                        int done = doneCount.incrementAndGet();
                        cb.onProgress("Verified…", 15 + (int) ((done / (float) total) * 80));
                        return null;
                    }
                    boolean ok = assembleDepotFile(df, outFile, cdnBaseRef, fSecureLinkUrl, fToken,
                            cdnRefreshCount, MAX_CDN_REFRESH, cancelled, fileLog);
                    if (cancelled.get()) return null;
                    if (!ok) {
                        fileLog.add("FAIL file=" + df.relativePath);
                        anyFailed.set(true);
                        return null;
                    }
                    written.add(df.relativePath);
                    int done = doneCount.incrementAndGet();
                    int pct = 15 + (int) ((done / (float) total) * 80);
                    String name = df.relativePath.contains("/")
                            ? df.relativePath.substring(df.relativePath.lastIndexOf('/') + 1)
                            : df.relativePath;
                    cb.onProgress("Downloading: " + name, pct);
                    return null;
                }));
            }
            pool.shutdown();
            try {
                for (Future<Void> f : futures) f.get();
            } catch (Exception e) {
                pool.shutdownNow();
                GogCloudSaveManager.debug(ctx, dlgTag + " pool error: " + e);
                cb.onError("DLC install failed: " + e.getMessage());
                return;
            }
            } // end Java engine
            for (String line : fileLog) GogCloudSaveManager.debug(ctx, dlgTag + " " + line);

            if (cancelled.get()) { GogCloudSaveManager.debug(ctx, dlgTag + " cancelled"); return; }
            if (anyFailed.get()) {
                GogCloudSaveManager.debug(ctx, dlgTag + " FAILED — one or more chunks failed");
                cb.onError("DLC install failed");
                return;
            }

            // Full success only: write the installed marker + the written-file list (for uninstall).
            markDlcInstalled(ctx, baseId, dlcProductId, new ArrayList<>(written));
            GogCloudSaveManager.debug(ctx, dlgTag + " DONE files=" + written.size() + " marker written");
            cb.onProgress("DLC installed!", 100);
            cb.onComplete("");
        } catch (Exception e) {
            GogCloudSaveManager.debug(ctx, dlgTag + " EXCEPTION " + e);
            cb.onError("DLC install error: " + e.getMessage());
        }
    }

    /**
     * Records the per-DLC installed marker {@code gog_dlc_installed_<baseId>_<dlcProductId>=true}
     * and the written-file list {@code gog_dlc_files_<baseId>_<dlcProductId>} (JSON array of
     * install-dir-relative paths) — the latter enables a future per-DLC uninstall (P2).
     */
    private static void markDlcInstalled(Context ctx, String baseId, String dlcProductId,
                                          List<String> writtenPaths) {
        try {
            JSONArray arr = new JSONArray();
            for (String p : writtenPaths) arr.put(p);
            ctx.getSharedPreferences("bh_gog_prefs", 0).edit()
                    .putBoolean("gog_dlc_installed_" + baseId + "_" + dlcProductId, true)
                    .putString("gog_dlc_files_" + baseId + "_" + dlcProductId, arr.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gen 1 pipeline
    // ─────────────────────────────────────────────────────────────────────────

    // Returns null on success, error description string on failure.
    private static String runGen1(Context ctx, GogGame game, String token,
                                   String buildsJson, Callback cb, StringBuilder dbg,
                                   AtomicBoolean cancelled, AtomicReference<File> installDirRef) {
        try {
            dbg.append("\n--- Gen1 ---\n");
            JSONObject builds = new JSONObject(buildsJson);
            JSONArray items = builds.optJSONArray("items");
            if (items == null || items.length() == 0)
                return builds.optInt("total_count", -1) == 0 ? "NO_CS_BUILDS" : "no items (api error)";

            String manifestUrl = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if ("windows".equals(item.optString("os"))) {
                    manifestUrl = item.optString("link");
                    break;
                }
            }
            if (manifestUrl == null || manifestUrl.isEmpty())
                return "no windows manifest URL";
            dbg.append("manifestUrl=").append(manifestUrl.substring(0, Math.min(80, manifestUrl.length()))).append("\n");

            cb.onProgress("Fetching Gen 1 manifest…", 12);
            byte[] raw = fetchBytes(manifestUrl, token);
            if (raw == null) return "manifest fetch null";
            String manifestStr = decompressBytes(raw);
            if (manifestStr == null) return "manifest decompress failed";

            JSONObject manifest = new JSONObject(manifestStr);
            String installDir  = manifest.optString("installDirectory", game.title);
            JSONArray depots   = manifest.optJSONArray("depot");
            if (depots == null) return "no depot array in manifest";

            // Collect files from all depots
            List<Gen1File> files = new ArrayList<>();
            for (int i = 0; i < depots.length(); i++) {
                JSONObject depot = depots.getJSONObject(i);
                boolean isSupport = depot.optBoolean("support", false);
                if (isSupport) continue;
                JSONArray jFiles = depot.optJSONArray("files");
                if (jFiles == null) continue;
                for (int j = 0; j < jFiles.length(); j++) {
                    JSONObject f = jFiles.getJSONObject(j);
                    String path   = f.optString("path");
                    long offset   = f.optLong("offset", 0);
                    long size     = f.optLong("size", 0);
                    String url    = f.optString("url");
                    if (path == null || url == null || size == 0) continue;
                    files.add(new Gen1File(path, url, offset, size));
                }
            }

            if (files.isEmpty()) return "no files in manifest";
            dbg.append("gen1 files=").append(files.size()).append("\n");

            File installPath = GogInstallPath.getInstallDir(ctx, installDir);
            installPath.mkdirs();
            installDirRef.set(installPath);

            final int totalG1                   = files.size();
            final AtomicInteger doneG1          = new AtomicInteger(0);
            final AtomicLong    totalBytesG1    = new AtomicLong(0);
            final AtomicLong    lastSpeedMsG1   = new AtomicLong(System.currentTimeMillis());
            final AtomicLong    lastSpeedBG1    = new AtomicLong(0);
            final AtomicLong    speedBpsG1      = new AtomicLong(0);
            final AtomicBoolean anyFailedG1     = new AtomicBoolean(false);
            final java.util.concurrent.ConcurrentLinkedQueue<String> fileLog1 =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();
            final int downloadThreadsG1 = resolveDownloadThreads(ctx);
            dbg.append("gen1 parallel download: ").append(totalG1)
               .append(" files, ").append(downloadThreadsG1).append(" threads\n");

            // Engine switch — see runGen2. gen1: one Range GET per file streamed to disk, size-only
            // resume ("Resuming…"), no secure-link refresh (cap 0), same speed string.
            final boolean useRustEngineG1 = useRustEngine(ctx);
            dbg.append("engine=").append(useRustEngineG1 ? "rust" : "java").append("\n");
            if (useRustEngineG1) {
                runRustChunkEngine(ctx, com.winlator.star.store.blsteam.BlGogDownload.KIND_GEN1_RANGES,
                        "Resuming…", java.util.Collections.singletonList(manifestStr), installPath,
                        new AtomicReference<>(""), null, null, new AtomicInteger(0), 0,
                        downloadThreadsG1, downloadThreadsG1, false, "gog gen1=" + game.gameId,
                        cancelled, anyFailedG1, doneG1, totalG1, totalBytesG1,
                        lastSpeedMsG1, lastSpeedBG1, speedBpsG1, cb, true, null, fileLog1);
            } else {
            ExecutorService poolG1 = Executors.newFixedThreadPool(downloadThreadsG1);
            List<Future<Void>> futuresG1 = new ArrayList<>();
            for (Gen1File gf : files) {
                futuresG1.add(poolG1.submit((Callable<Void>) () -> {
                    if (cancelled.get() || anyFailedG1.get()) return null;
                    File outFile = new File(installPath, gf.path);
                    outFile.getParentFile().mkdirs();

                    // Resume: skip if size already matches
                    if (outFile.exists() && outFile.length() == gf.size) {
                        int done = doneG1.incrementAndGet();
                        int pct  = 15 + (int) ((done / (float) totalG1) * 80);
                        cb.onProgress("Resuming…", pct);
                        return null;
                    }

                    for (int attempt = 1; attempt <= 3; attempt++) {
                        if (cancelled.get()) return null;
                        outFile.delete();
                        boolean ok = downloadRange(gf.url, gf.offset, gf.size, outFile);
                        if (ok) {
                            int done   = doneG1.incrementAndGet();
                            long tb    = totalBytesG1.addAndGet(gf.size);
                            int pct    = 15 + (int) ((done / (float) totalG1) * 80);
                            long nowMs  = System.currentTimeMillis();
                            long prevMs = lastSpeedMsG1.get();
                            if (nowMs - prevMs >= 500 && lastSpeedMsG1.compareAndSet(prevMs, nowMs)) {
                                long prevB = lastSpeedBG1.getAndSet(tb);
                                long dt = nowMs - prevMs;
                                if (dt > 0) speedBpsG1.set((tb - prevB) * 1000L / dt);
                            }
                            String speedStr = formatSpeed(speedBpsG1.get());
                            String name = gf.path.contains("/")
                                    ? gf.path.substring(gf.path.lastIndexOf('/') + 1) : gf.path;
                            cb.onProgress("Downloading: " + name
                                    + (speedStr.isEmpty() ? "" : "  " + speedStr), pct);
                            return null;
                        }
                        fileLog1.add("RETRY attempt=" + attempt + " file=" + gf.path);
                        if (attempt < 3) {
                            try { Thread.sleep(1000L << (attempt - 1)); }
                            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                        }
                    }
                    fileLog1.add("FAIL file=" + gf.path);
                    Log.e(TAG, "Gen1 file failed after 3 attempts: " + gf.path);
                    anyFailedG1.set(true);
                    return null;
                }));
            }
            poolG1.shutdown();
            try {
                for (Future<Void> f : futuresG1) f.get();
            } catch (Exception e) {
                poolG1.shutdownNow();
                return "gen1 parallel error: " + e;
            }
            } // end Java engine
            for (String line : fileLog1) dbg.append(line).append("\n");
            if (cancelled.get()) return "cancelled";
            if (anyFailedG1.get()) return "one or more gen1 files failed to download";
            dbg.append("gen1 download complete: ").append(doneG1.get()).append(" files OK\n");

            cb.onProgress("Install complete!", 100);

            SharedPreferences.Editor ed0 = ctx.getSharedPreferences("bh_gog_prefs", 0).edit();
            ed0.putString("gog_dir_" + game.gameId, installDir);
            ed0.apply();

            List<String> candidates = collectExeCandidates(installPath);
            if (candidates.size() == 1) {
                String exePath = candidates.get(0);
                ctx.getSharedPreferences("bh_gog_prefs", 0).edit()
                        .putString("gog_exe_" + game.gameId, exePath).apply();
                cb.onComplete(exePath);
            } else if (candidates.size() > 1) {
                cb.onSelectExe(candidates, selected -> {
                    if (selected != null && !selected.isEmpty()) {
                        ctx.getSharedPreferences("bh_gog_prefs", 0).edit()
                                .putString("gog_exe_" + game.gameId, selected).apply();
                    }
                    cb.onComplete(selected != null ? selected : "");
                });
            } else {
                cb.onComplete("");
            }
            return null; // success
        } catch (Exception e) {
            return "exception: " + e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Installer (old GOG downloader — games without content-system builds)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches the Windows installer for games that have no content-system builds.
     * Uses api.gog.com/products/{id}?expand=downloads → installers[].manualUrl
     * → follow redirect → download .exe to installDir.
     * Returns null on success, error string on failure.
     */
    private static String runInstaller(Context ctx, GogGame game, String token,
                                        Callback cb, StringBuilder dbg,
                                        AtomicBoolean cancelled, AtomicReference<File> installDirRef) {
        try {
            dbg.append("\n--- Installer fallback ---\n");
            String productUrl = "https://api.gog.com/products/" + game.gameId + "?expand=downloads";
            String productJson = httpGet(productUrl, token);
            dbg.append("product_response=").append(productJson == null ? "NULL"
                    : productJson.substring(0, Math.min(400, productJson.length()))).append("\n");
            if (productJson == null) return "product fetch null";

            JSONObject product = new JSONObject(productJson);
            JSONObject downloads = product.optJSONObject("downloads");
            if (downloads == null) return "no downloads object";

            JSONArray installers = downloads.optJSONArray("installers");
            if (installers == null || installers.length() == 0) return "no installers";

            // Find windows installer
            String manualUrl = null;
            String fileName  = null;
            for (int i = 0; i < installers.length(); i++) {
                JSONObject inst = installers.getJSONObject(i);
                if ("windows".equals(inst.optString("os"))) {
                    JSONArray files = inst.optJSONArray("files");
                    if (files != null && files.length() > 0) {
                        JSONObject f = files.getJSONObject(0);
                        manualUrl = f.optString("downlink", null);
                        if (manualUrl == null) manualUrl = f.optString("manualUrl", null);
                        fileName  = f.optString("filename", game.title + "_installer.exe");
                    }
                    if (manualUrl == null) manualUrl = inst.optString("manualUrl", null);
                    if (fileName == null)  fileName  = game.title + "_installer.exe";
                    break;
                }
            }
            dbg.append("manualUrl=").append(manualUrl).append(" fileName=").append(fileName).append("\n");
            if (manualUrl == null) return "no windows installer manualUrl";

            // Resolve the manualUrl → signed download URL (follows redirect)
            String downloadUrl = resolveRedirect(manualUrl, token);
            dbg.append("downloadUrl=").append(downloadUrl == null ? "NULL"
                    : downloadUrl.substring(0, Math.min(120, downloadUrl.length()))).append("\n");
            if (downloadUrl == null) return "redirect resolve failed";

            // Download the installer .exe
            File installDir = GogInstallPath.getInstallDir(ctx, game.title);
            installDir.mkdirs();
            installDirRef.set(installDir);
            if (cancelled.get()) return "cancelled";
            File outFile = new File(installDir, fileName);

            cb.onProgress("Downloading installer: " + fileName, 15);
            downloadWithProgress(downloadUrl, outFile, cb, cancelled);

            // Save prefs
            SharedPreferences.Editor ed = ctx.getSharedPreferences("bh_gog_prefs", 0).edit();
            ed.putString("gog_dir_" + game.gameId, game.title);
            ed.putString("gog_exe_" + game.gameId, outFile.getAbsolutePath());
            ed.apply();

            cb.onProgress("Installer downloaded!", 100);
            cb.onComplete(outFile.getAbsolutePath());
            return null; // success
        } catch (Exception e) {
            return "exception: " + e;
        }
    }

    /**
     * Follows HTTP redirects on the GOG manualUrl to get the actual download URL.
     * Handles: relative URLs, multi-hop redirects (up to 5), and GOG API endpoints
     * that return 200 JSON with a nested "downlink" field instead of a redirect.
     */
    private static String resolveRedirect(String url, String token) {
        try {
            // GOG manualUrl is relative like /downloader/get/... — prepend host
            if (url.startsWith("/")) url = "https://www.gog.com" + url;
            for (int hop = 0; hop < 5; hop++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setInstanceFollowRedirects(false);
                if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
                int code = conn.getResponseCode();
                String location = conn.getHeaderField("Location");
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    conn.disconnect();
                    if (location == null) return null;
                    if (location.startsWith("/")) location = "https://www.gog.com" + location;
                    url = location;
                    continue;
                }
                if (code == 200) {
                    // Some GOG API endpoints return JSON {"downlink":"https://..."} instead of redirect
                    String ct = conn.getContentType();
                    if (ct != null && ct.contains("application/json")) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line);
                        }
                        conn.disconnect();
                        JSONObject json = new JSONObject(sb.toString());
                        String inner = json.optString("downlink", null);
                        if (inner != null && !inner.isEmpty()) {
                            url = inner;
                            continue; // follow the inner URL
                        }
                    } else {
                        conn.disconnect();
                    }
                    return url; // final URL
                }
                conn.disconnect();
                return null; // unexpected response
            }
            return null; // too many hops
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatSpeed(long bps) {
        if (bps <= 0) return "";
        if (bps >= 1048576) return String.format("%.1f MB/s", bps / 1048576.0);
        return (bps / 1024) + " KB/s";
    }

    /** Downloads url to outFile, reporting progress via cb. */
    private static void downloadWithProgress(String url, File out, Callback cb, AtomicBoolean cancelled) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(60_000);
            int total = conn.getContentLength();
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[131072];
                int n, downloaded = 0;
                long speedWindowStart = System.currentTimeMillis();
                long speedWindowBytes = 0;
                long speedBps = 0;
                while ((n = is.read(buf)) != -1) {
                    if (cancelled.get()) return;
                    fos.write(buf, 0, n);
                    downloaded += n;
                    speedWindowBytes += n;
                    long elapsed = System.currentTimeMillis() - speedWindowStart;
                    if (elapsed >= 500) {
                        speedBps = speedWindowBytes * 1000L / elapsed;
                        speedWindowStart = System.currentTimeMillis();
                        speedWindowBytes = 0;
                    }
                    if (total > 0) {
                        int pct = 15 + (int) ((downloaded / (float) total) * 80);
                        String speed = formatSpeed(speedBps);
                        cb.onProgress("Downloading: " + out.getName()
                                + (speed.isEmpty() ? "" : "  " + speed), pct);
                    }
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "downloadWithProgress failed: " + e.getClass().getSimpleName());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Parses a Gen 2 depot manifest and appends DepotFile entries to {@code out}. */
    private static void parseDepotManifest(String json, List<DepotFile> out) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject depotObj = root.optJSONObject("depot");
            if (depotObj == null) return;
            JSONArray depot = depotObj.optJSONArray("items");
            if (depot == null) return;
            for (int i = 0; i < depot.length(); i++) {
                JSONObject entry = depot.getJSONObject(i);
                String path = entry.optString("path", "").replace("\\", "/");
                if (path.startsWith("/")) path = path.substring(1);
                JSONArray chunks = entry.optJSONArray("chunks");
                if (path.isEmpty() || chunks == null || chunks.length() == 0) continue;

                DepotFile df = new DepotFile(path);
                String fileMd5 = entry.optString("md5", null);
                if (fileMd5 != null && !fileMd5.isEmpty()) df.md5 = fileMd5;
                long fileTotal = 0;
                for (int c = 0; c < chunks.length(); c++) {
                    JSONObject chunk = chunks.getJSONObject(c);
                    String compressedMd5 = chunk.optString("compressedMd5");
                    String decMd5        = chunk.optString("md5");
                    long compressedSize  = chunk.optLong("compressedSize", 0);
                    long decSize         = chunk.optLong("size", 0);
                    // GOG v2 CDN path is keyed on the compressed MD5 (fall back to md5).
                    String hash = (compressedMd5 != null && !compressedMd5.isEmpty())
                            ? compressedMd5 : decMd5;
                    if (hash == null || hash.isEmpty()) continue;
                    df.chunks.add(new DepotFile.ChunkRef(
                            hash, compressedMd5, decMd5, compressedSize, decSize));
                    fileTotal += decSize;
                }
                df.totalSize = fileTotal;
                if (!df.chunks.isEmpty()) out.add(df);
            }
        } catch (Exception e) {
            Log.w(TAG, "parseDepotManifest error", e);
        }
    }

    /** Builds the CDN path from a chunk hash: "ab/cd/abcdef..." */
    static String buildCdnPath(String hash) {
        return hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash;
    }

    /**
     * Persists a gen2 game's required GOG dependency ids + scriptInterpreter flag into
     * {@code bh_gog_prefs} under {@code gog_deps_<gameId>} as
     * {@code {"deps":[...],"scriptInterpreter":bool}}. Read by GogRedistInstaller at
     * add-to-container time. Fail-soft: any error is logged and swallowed (never blocks a download).
     */
    private static void persistDependencies(Context ctx, String gameId,
                                            JSONArray dependencies, boolean scriptInterpreter,
                                            StringBuilder dbg) {
        try {
            JSONArray deps = dependencies != null ? dependencies : new JSONArray();
            JSONObject rec = new JSONObject();
            rec.put("deps", deps);
            rec.put("scriptInterpreter", scriptInterpreter);
            ctx.getSharedPreferences("bh_gog_prefs", 0).edit()
                    .putString("gog_deps_" + gameId, rec.toString()).apply();
            dbg.append("gog deps captured: ").append(deps.toString())
               .append(" scriptInterpreter=").append(scriptInterpreter).append("\n");
        } catch (Exception e) {
            dbg.append("persistDependencies failed: ").append(e).append("\n");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared gen2 chunk assembler (game files AND dependency redists)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Downloads every chunk of {@code df}, verifying each end-to-end
     * (compressed size/MD5 → inflate → decompressed size/MD5) via {@link #fetchChunkVerified},
     * assembles them into {@code outFile}, then verifies the whole-file size + MD5. Writes to a
     * {@code .bhtmp} sibling and atomically renames on success. Returns true on a verified file.
     *
     * This is the single MD5-verified assembly path shared by the game-file download loop in
     * {@link #runGen2} and the dependency-redist assembler {@link #assembleDependencyInstaller}
     * — so redist integrity is guaranteed by exactly the same code as game files.
     *
     * For the unauthenticated dependency store pass {@code secureLinkUrl=null},
     * {@code token=null}, {@code maxCdnRefresh=0} (there is no secure_link to refresh).
     */
    private static boolean assembleDepotFile(
            DepotFile df, File outFile,
            AtomicReference<String> cdnBaseRef, String secureLinkUrl, String token,
            AtomicInteger cdnRefreshCount, int maxCdnRefresh, AtomicBoolean cancelled,
            java.util.concurrent.ConcurrentLinkedQueue<String> log) {
        File tmpFile = new File(outFile.getAbsolutePath() + ".bhtmp");
        File parent = outFile.getParentFile();
        if (parent != null) parent.mkdirs();
        tmpFile.delete();
        boolean ok = true;
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            for (DepotFile.ChunkRef chunk : df.chunks) {
                if (cancelled.get()) { ok = false; break; }
                String chunkPath = buildCdnPath(chunk.hash);
                byte[] inflated = fetchChunkVerified(
                        cdnBaseRef, chunkPath, chunk, secureLinkUrl, token,
                        cdnRefreshCount, maxCdnRefresh, cancelled, log);
                if (inflated == null) { ok = false; break; }
                fos.write(inflated);
            }
        } catch (Exception e) {
            ok = false;
        }
        if (!ok || cancelled.get()) { tmpFile.delete(); return false; }

        if (df.totalSize > 0 && tmpFile.length() != df.totalSize) {
            log.add("FILE size mismatch file=" + df.relativePath
                    + " exp=" + df.totalSize + " got=" + tmpFile.length());
            tmpFile.delete();
            return false;
        }
        if (df.md5 != null && !df.md5.isEmpty()) {
            String actual = md5HexFile(tmpFile);
            if (actual == null || !actual.equalsIgnoreCase(df.md5)) {
                log.add("FILE md5 mismatch file=" + df.relativePath);
                tmpFile.delete();
                return false;
            }
        }
        if (outFile.exists()) outFile.delete();
        return tmpFile.renameTo(outFile);
    }

    /**
     * Assembles a GOG dependency (redist) installer from its depot manifest into {@code destDir},
     * reusing the shared MD5-verified {@link #assembleDepotFile} chunk path against the
     * unauthenticated dependency store (no secure_link). {@code depManifestJson} is the inflated
     * per-dependency depot manifest; {@code storeBase} is
     * {@code .../content-system/v2/dependencies/store}; {@code exeRelPath} is the repository entry's
     * {@code executable.path} (e.g. {@code __redist/MSVC2017_x64/VC_redist.x64.exe}).
     *
     * Returns the assembled installer file (the entry's executable), or null on any failure.
     * Called from GogRedistInstaller on a background thread.
     */
    public static File assembleDependencyInstaller(
            String depManifestJson, String storeBase, File destDir, String exeRelPath,
            AtomicBoolean cancelled,
            java.util.concurrent.ConcurrentLinkedQueue<String> log) {
        try {
            List<DepotFile> files = new ArrayList<>();
            parseDepotManifest(depManifestJson, files);
            if (files.isEmpty()) { log.add("dep manifest had no depot files"); return null; }

            AtomicReference<String> baseRef = new AtomicReference<>(storeBase);
            AtomicInteger refresh = new AtomicInteger(0);

            String wantTail = exeRelPath == null ? null
                    : exeRelPath.replace("\\", "/").replaceFirst("^/+", "");

            // Engine switch — see runGen2. No Context reaches this entry point (GogRedistInstaller
            // calls it), so the flag is read through the registry's application Context; when
            // that is not initialised yet the Java assembler runs, as before.
            Context engineCtx = com.winlator.star.store.download.DownloadRegistry.INSTANCE.appContext();
            if (engineCtx != null && useRustEngine(engineCtx)) {
                log.add("engine=rust (dependency)");
                AtomicBoolean anyFailed = new AtomicBoolean(false);
                runRustChunkEngine(engineCtx, com.winlator.star.store.blsteam.BlGogDownload.KIND_GEN2_CHUNKS,
                        "Verified…", java.util.Collections.singletonList(depManifestJson), destDir,
                        baseRef, null, null, refresh, 0,
                        1, 1, false, "gog dep=" + (wantTail == null ? "?" : wantTail),
                        cancelled, anyFailed, new AtomicInteger(0), files.size(), new AtomicLong(0),
                        new AtomicLong(System.currentTimeMillis()), new AtomicLong(0), new AtomicLong(0),
                        null, false, null, log);
                if (cancelled.get() || anyFailed.get()) {
                    log.add("dep chunk assembly failed (rust engine)");
                    return null;
                }
                File rustExeOut = null;
                for (DepotFile df : files) {
                    File outFile = new File(destDir, df.relativePath);
                    if (wantTail != null && df.relativePath.endsWith(wantTail)) rustExeOut = outFile;
                    if (rustExeOut == null) rustExeOut = outFile; // fallback: first file
                }
                return rustExeOut;
            }
            File exeOut = null;
            for (DepotFile df : files) {
                File outFile = new File(destDir, df.relativePath);
                if (!assembleDepotFile(df, outFile, baseRef, null, null,
                        refresh, 0, cancelled, log)) {
                    log.add("dep chunk assembly failed for " + df.relativePath);
                    return null;
                }
                if (wantTail != null && df.relativePath.endsWith(wantTail)) exeOut = outFile;
                if (exeOut == null) exeOut = outFile; // fallback: first file
            }
            return exeOut;
        } catch (Exception e) {
            log.add("assembleDependencyInstaller exception: " + e);
            return null;
        }
    }

    /** Parses the secure_link JSON → CDN base URL (strips trailing /{path}). */
    private static String parseCdnUrl(String json) {
        if (json == null) return null;
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray urls = obj.optJSONArray("urls");
            if (urls == null || urls.length() == 0) return null;
            JSONObject first = urls.getJSONObject(0);
            String urlFormat = first.optString("url_format");
            JSONObject params = first.optJSONObject("parameters");
            if (urlFormat == null || params == null) return null;

            // Replace {key} placeholders
            java.util.Iterator<String> keys = params.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = params.optString(k);
                urlFormat = urlFormat.replace("{" + k + "}", v);
            }
            urlFormat = urlFormat.replace("\\/", "/");

            // Strip trailing /{path}
            int idx = urlFormat.indexOf("/{path}");
            if (idx >= 0) urlFormat = urlFormat.substring(0, idx);
            return urlFormat;
        } catch (Exception e) {
            return null;
        }
    }

    /** Downloads a byte range from {@code url} and writes it to {@code out}. */
    private static boolean downloadRange(String url, long offset, long size, File out) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + size - 1));
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[131072];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }
            conn.disconnect();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "downloadRange failed: " + out.getName() + " (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** HTTP GET, returns response body string or null on failure. */
    static String httpGet(String url, String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", "GOG Galaxy");
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches URL bytes, returns null on failure. */
    static byte[] fetchBytes(String url, String token) {
        return fetchBytesEx(url, token).body;
    }

    /**
     * Detects gzip (0x1F 0x8B) or zlib (0x78 xx) and decompresses.
     * Falls back to raw UTF-8 string.
     */
    static String decompressBytes(byte[] data) {
        if (data == null || data.length < 2) return null;
        try {
            int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF;
            if (b0 == 0x1F && b1 == 0x8B) {
                // gzip
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = gzip.read(buf)) != -1) bos.write(buf, 0, n);
                }
                return bos.toString("UTF-8");
            }
            if (b0 == 0x78) {
                // zlib
                Inflater inf = new Inflater();
                inf.setInput(data);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n == 0) break;
                    bos.write(buf, 0, n);
                }
                inf.end();
                return bos.toString("UTF-8");
            }
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** zlib-inflate a raw deflate block (chunk data). */
    private static byte[] inflateZlib(byte[] data) {
        try {
            if (data == null || data.length < 2) return null;
            int b0 = data[0] & 0xFF;
            if (b0 != 0x78) return null; // not zlib
            Inflater inf = new Inflater();
            inf.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) break;
                bos.write(buf, 0, n);
            }
            inf.end();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integrity + resilient chunk fetch (GOG reliability bundle)
    // ─────────────────────────────────────────────────────────────────────────

    private static final class HttpResult {
        final byte[] body; final int status;
        HttpResult(byte[] body, int status) { this.body = body; this.status = status; }
    }

    /** Like fetchBytes but surfaces the HTTP status (or -1 on connect error). */
    private static HttpResult fetchBytesEx(String url, String token) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", "GOG Galaxy");
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            if (code != 200) { conn.disconnect(); return new HttpResult(null, code); }
            int contentLength = conn.getContentLength();
            ByteArrayOutputStream bos = contentLength > 0
                    ? new ByteArrayOutputStream(contentLength) : new ByteArrayOutputStream();
            byte[] buf = new byte[131072];
            try (InputStream is = conn.getInputStream()) {
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            }
            conn.disconnect();
            return new HttpResult(bos.toByteArray(), code);
        } catch (Exception e) {
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
            return new HttpResult(null, -1);
        }
    }

    /** Appends the chunk path BEFORE any query string so the secure-link token survives. */
    private static String buildChunkUrl(String base, String chunkPath) {
        int qIdx = base.indexOf('?');
        return qIdx >= 0
                ? base.substring(0, qIdx) + "/" + chunkPath + base.substring(qIdx)
                : base + "/" + chunkPath;
    }

    /** lowercase hex MD5 of an in-memory buffer (chunks are bounded — safe to hash directly). */
    private static String md5Hex(byte[] data) {
        try {
            return toHex(java.security.MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) { return null; }
    }

    /** Streaming lowercase hex MD5 of a file — never buffers the whole file (OOM-safe). */
    private static String md5HexFile(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[131072];
            int n;
            while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
            return toHex(md.digest());
        } catch (Exception e) { return null; }
    }

    private static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            int v = b & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /**
     * Resume/repair predicate: true only if {@code f} exists and passes the size
     * (when known) and MD5 (when known) checks.  Falls back to exists+non-empty
     * when the manifest supplies neither — preserving old resume behavior.
     */
    private static boolean fileVerified(File f, long expectedSize, String expectedMd5) {
        if (f == null || !f.exists() || f.length() == 0) return false;
        if (expectedSize > 0 && f.length() != expectedSize) return false;
        if (expectedMd5 != null && !expectedMd5.isEmpty()) {
            String actual = md5HexFile(f);
            if (actual == null || !actual.equalsIgnoreCase(expectedMd5)) return false;
        }
        return true;
    }

    private static void sleepBackoff(int attempt) {
        try { Thread.sleep(Math.min(1000L << Math.max(0, attempt - 1), 8000L)); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /**
     * Re-requests the secure link and rebuilds the CDN base when a chunk 401/403/
     * 404/500s (expired link).  Capped by {@code refreshCount}/{@code cap}.
     * Returns true if the base is now usable (refreshed, or another thread already
     * refreshed it); false if the cap is hit or the refresh failed.
     */
    private static boolean tryRefreshCdn(AtomicReference<String> cdnBaseRef, String staleBase,
            String secureLinkUrl, String token, AtomicInteger refreshCount, int cap,
            java.util.concurrent.ConcurrentLinkedQueue<String> log) {
        synchronized (cdnBaseRef) {
            String current = cdnBaseRef.get();
            if (staleBase == null || !staleBase.equals(current)) return true; // already refreshed
            if (refreshCount.get() >= cap) {
                log.add("CDN refresh cap reached (" + cap + ")");
                return false;
            }
            String json = httpGet(secureLinkUrl, token);
            String neu = parseCdnUrl(json);
            if (neu == null || neu.isEmpty()) {
                log.add("CDN refresh FAILED (secure_link null/parse)");
                return false;
            }
            cdnBaseRef.set(neu);
            int n = refreshCount.incrementAndGet();
            log.add("secure-link refreshed #" + n);
            return true;
        }
    }

    /**
     * Fetches one chunk and verifies it end-to-end:
     *   compressed size + compressed MD5 (before inflate) → inflate →
     *   decompressed size + decompressed MD5 (after inflate).
     * Re-fetches on mismatch (bounded to 3 hard failures) and refreshes the secure
     * link on expiry HTTP codes (which do not count as a hard failure).  Returns the
     * verified, inflated bytes, or null on unrecoverable failure.
     */
    private static byte[] fetchChunkVerified(AtomicReference<String> cdnBaseRef, String chunkPath,
            DepotFile.ChunkRef chunk, String secureLinkUrl, String token,
            AtomicInteger cdnRefreshCount, int maxCdnRefresh, AtomicBoolean cancelled,
            java.util.concurrent.ConcurrentLinkedQueue<String> log) {
        int hardFail = 0;
        int iterGuard = 0;
        while (iterGuard++ < 8) {
            if (cancelled.get()) return null;
            String base = cdnBaseRef.get();
            String url = buildChunkUrl(base, chunkPath);
            HttpResult res = fetchBytesEx(url, null);
            if (res.body == null) {
                int code = res.status;
                if (code == 401 || code == 403 || code == 404 || code == 500) {
                    if (tryRefreshCdn(cdnBaseRef, base, secureLinkUrl, token,
                            cdnRefreshCount, maxCdnRefresh, log)) {
                        continue; // retry with fresh base — not a hard failure
                    }
                }
                log.add("chunk http fail code=" + code + " chunk=" + chunk.hash);
                if (++hardFail >= 3) return null;
                sleepBackoff(hardFail);
                continue;
            }
            byte[] raw = res.body;
            if (chunk.compressedSize > 0 && raw.length != chunk.compressedSize) {
                log.add("chunk compressed-size mismatch chunk=" + chunk.hash
                        + " exp=" + chunk.compressedSize + " got=" + raw.length);
                if (++hardFail >= 3) return null;
                sleepBackoff(hardFail);
                continue;
            }
            if (chunk.compressedMd5 != null && !chunk.compressedMd5.isEmpty()) {
                String actual = md5Hex(raw);
                if (actual == null || !actual.equalsIgnoreCase(chunk.compressedMd5)) {
                    log.add("chunk compressed-md5 mismatch chunk=" + chunk.hash);
                    if (++hardFail >= 3) return null;
                    sleepBackoff(hardFail);
                    continue;
                }
            }
            byte[] inflated = inflateZlib(raw);
            if (inflated == null) inflated = raw; // stored (non-zlib) chunk
            if (chunk.size > 0 && inflated.length != chunk.size) {
                log.add("chunk decompressed-size mismatch chunk=" + chunk.hash
                        + " exp=" + chunk.size + " got=" + inflated.length);
                if (++hardFail >= 3) return null;
                sleepBackoff(hardFail);
                continue;
            }
            if (chunk.md5 != null && !chunk.md5.isEmpty()) {
                String actual = md5Hex(inflated);
                if (actual == null || !actual.equalsIgnoreCase(chunk.md5)) {
                    log.add("chunk decompressed-md5 mismatch chunk=" + chunk.hash);
                    if (++hardFail >= 3) return null;
                    sleepBackoff(hardFail);
                    continue;
                }
            }
            return inflated;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rust fetch engine (gen2 chunk path) — docs/RUST_GOG_PARITY.md
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * True when the Rust GOG engine flag is ON and {@code libblsteam.so} loads with its GOG JNI
     * exports bound. Any failure degrades to the Java loop (never throws).
     */
    private static boolean useRustEngine(Context ctx) {
        try {
            return ctx != null
                    && com.winlator.star.store.blsteam.BlStoreEngineFlag.isGogEnabled(ctx)
                    && com.winlator.star.store.blsteam.BlGogDownload.isAvailable();
        } catch (Throwable t) {
            Log.w(TAG, "rust engine unavailable: " + t);
            return false;
        }
    }

    /**
     * Drives the Rust engine for ONE fetch loop ({@code kind} = gen2 chunk loop for base install /
     * DLC install / dependency redist assembly, or the gen1 range loop), producing exactly what
     * the Java pool loop produced:
     * <ul>
     *   <li>per verified (resume-skipped) file: {@code doneCount++}, {@code verifiedMsg}
     *       ({@code "Verified…"} gen2 / {@code "Resuming…"} gen1) at {@code 15 + done/total*80};</li>
     *   <li>per assembled file: {@code doneCount++}, {@code totalBytes += size}, the same 500 ms
     *       speed window and {@code "Downloading: <name>  <speed>"} string;</li>
     *   <li>the DLC written-file list gets both kinds, like the Java tasks;</li>
     *   <li>engine log lines go to the same debug buffer as the Java {@code fileLog};</li>
     *   <li>secure-link expiry (a run dying on HTTP 401/403/404/500) → {@link #tryRefreshCdn} with
     *       the SAME counter/cap, then a re-run for the files not yet done (the engine is told which
     *       paths are already complete so they are neither re-hashed nor re-reported);</li>
     *   <li>any other failure → {@code anyFailed = true} (the caller's "one or more chunks failed"
     *       path); cancel → returns with {@code cancelled} left as the caller set it.</li>
     * </ul>
     * Cancel is propagated by polling the caller's {@code cancelled} flag every 250 ms.
     */
    private static void runRustChunkEngine(
            Context ctx, int kind, String verifiedMsg,
            List<String> depotJsons, File installPath,
            AtomicReference<String> cdnBaseRef, String secureLinkUrl, String token,
            AtomicInteger cdnRefreshCount, int maxCdnRefresh,
            int maxWorkers, int processWorkers, boolean sortLargestFirst, String label,
            AtomicBoolean cancelled, AtomicBoolean anyFailed,
            AtomicInteger doneCount, int total, AtomicLong totalBytes,
            AtomicLong lastSpeedMs, AtomicLong lastSpeedB, AtomicLong speedBps,
            Callback cb, boolean showSpeed,
            java.util.Collection<String> written,
            java.util.concurrent.ConcurrentLinkedQueue<String> log) {
        final String caPath;
        try {
            caPath = com.winlator.star.store.blsteam.CaBundleExtractor.INSTANCE.ensureBundle(ctx);
        } catch (Throwable t) {
            log.add("rust engine: CA bundle unavailable: " + t);
            anyFailed.set(true);
            return;
        }
        // Improvement round 1: the Rust path takes its ceiling from the Steam speed tier (Fast =
        // window 32) and at least RUST_MIN_PROCESS_WORKERS inflate/hash/write threads (stream mode
        // pins each chunk to one pool worker). The Java loop keeps its own counts (maxWorkers /
        // processWorkers are still what it would have used — logged for the A/B).
        int rustWorkers = maxWorkers;
        int rustProcess = Math.max(processWorkers, RUST_MIN_PROCESS_WORKERS);
        try {
            DownloadSpeedConfig cfg = StoreDownloadTier.config(ctx);
            rustWorkers = Math.max(1, Math.min(128, cfg.getMaxNetworkWindow()));
            rustProcess = Math.max(rustProcess, Math.max(1, Math.min(32, cfg.getMaxDecompress())));
        } catch (Throwable t) {
            log.add("rust engine: speed config unavailable (" + t + "), keeping Java counts");
        }
        log.add("rust engine concurrency: workers=" + rustWorkers + " process_workers=" + rustProcess
                + " (java loop would use " + maxWorkers + ")");
        final String[] manifests = depotJsons.toArray(new String[0]);
        // Files completed by earlier runs of THIS download (refresh re-runs skip them silently).
        final java.util.Set<String> donePaths = java.util.concurrent.ConcurrentHashMap.newKeySet();
        int run = 0;
        while (!cancelled.get()) {
            run++;
            final String base = cdnBaseRef.get();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean okRef = new AtomicBoolean(false);
            final AtomicBoolean linkExpiryRef = new AtomicBoolean(false);
            final AtomicReference<String> errRef = new AtomicReference<>("");
            com.winlator.star.store.blsteam.BlGogDownloadListener listener =
                    new com.winlator.star.store.blsteam.BlGogDownloadListener() {
                @Override
                public void onProgress(long bytesDone, long bytesTotal, int filesDone, int filesTotal,
                                       String file, long fileBytes, boolean verified) {
                    donePaths.add(file);
                    if (written != null) written.add(file);
                    int done = doneCount.incrementAndGet();
                    int pct  = 15 + (int) ((done / (float) total) * 80);
                    if (verified) {
                        if (cb != null) cb.onProgress(verifiedMsg, pct);
                        return;
                    }
                    long tb = totalBytes.addAndGet(fileBytes);
                    String speedStr = "";
                    if (showSpeed) {
                        long nowMs  = System.currentTimeMillis();
                        long prevMs = lastSpeedMs.get();
                        if (nowMs - prevMs >= 500 && lastSpeedMs.compareAndSet(prevMs, nowMs)) {
                            long prevB = lastSpeedB.getAndSet(tb);
                            long dt = nowMs - prevMs;
                            if (dt > 0) speedBps.set((tb - prevB) * 1000L / dt);
                        }
                        speedStr = formatSpeed(speedBps.get());
                    }
                    String name = file.contains("/")
                            ? file.substring(file.lastIndexOf('/') + 1) : file;
                    if (cb != null) cb.onProgress("Downloading: " + name
                            + (speedStr.isEmpty() ? "" : "  " + speedStr), pct);
                }
                @Override
                public void onLog(String line) { log.add(line); }
                @Override
                public void onComplete(boolean success, boolean wasCancelled, boolean linkExpiry,
                                       String error, long bytesWritten, int filesDone) {
                    okRef.set(success);
                    linkExpiryRef.set(linkExpiry);
                    errRef.set(error == null ? "" : error);
                    latch.countDown();
                }
            };
            long handle = com.winlator.star.store.blsteam.BlGogDownload.start(
                    kind, manifests, base, installPath.getAbsolutePath(),
                    donePaths.toArray(new String[0]), caPath,
                    rustWorkers, rustProcess, sortLargestFirst, label + " run=" + run, listener);
            if (handle == 0L) {
                log.add("rust engine: start failed (see logcat BL_GOG_DL)");
                anyFailed.set(true);
                return;
            }
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        if (latch.await(250, TimeUnit.MILLISECONDS)) break;
                    } catch (InterruptedException ie) {
                        interrupted = true;
                    }
                    if (cancelled.get() || interrupted) {
                        com.winlator.star.store.blsteam.BlGogDownload.cancel(handle);
                    }
                }
            } finally {
                com.winlator.star.store.blsteam.BlGogDownload.release(handle);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
                log.add("rust engine: interrupted");
                anyFailed.set(true);
                return;
            }
            if (cancelled.get()) return;
            if (okRef.get()) return;
            String err = errRef.get();
            if (linkExpiryRef.get() && tryRefreshCdn(cdnBaseRef, base, secureLinkUrl, token,
                    cdnRefreshCount, maxCdnRefresh, log)) {
                log.add("rust engine: secure-link expiry (" + err
                        + ") — refreshed, re-running for the remaining files");
                continue;
            }
            log.add("rust engine FAILED: " + err);
            anyFailed.set(true);
            return;
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) for (File c : children) deleteDir(c);
        dir.delete();
    }

    /**
     * Scans {@code installDir} recursively for the first .exe that is not in
     * a "redist" or "Redist" path (same heuristic as BannerHub).
     * Returns absolute path or null.
     */
    static String findExe(File installDir, String gameId, String relDir) {
        String found = findExeRecursive(installDir);
        return found;
    }

    private static String findExeRecursive(File dir) {
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".exe")) {
                String path = f.getAbsolutePath().toLowerCase();
                if (!path.contains("redist") && !path.contains("unins")) {
                    return f.getAbsolutePath();
                }
            }
        }
        for (File f : files) {
            if (f.isDirectory()) {
                String sub = findExeRecursive(f);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    /**
     * Collects ALL qualifying .exe candidates under {@code dir}, excluding
     * known helper/redist patterns.  Returns absolute paths, shallowest first.
     */
    static List<String> collectExeCandidates(File dir) {
        List<String> result = new ArrayList<>();
        collectExeRecursive(dir, result);
        return result;
    }

    private static void collectExeRecursive(File dir, List<String> out) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        // Files before subdirs so shallowest paths appear first
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".exe")) {
                String path = f.getAbsolutePath().toLowerCase();
                if (!path.contains("redist") && !path.contains("unins")
                        && !path.contains("setup") && !path.contains("crash")
                        && !path.contains("report") && !path.contains("helper")
                        && !path.contains("dotnet") && !path.contains("vcredist")
                        && !path.contains("directx")) {
                    out.add(f.getAbsolutePath());
                }
            }
        }
        for (File f : files) {
            if (f.isDirectory()) collectExeRecursive(f, out);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data holders
    // ─────────────────────────────────────────────────────────────────────────

    private static class DepotFile {
        final String relativePath;
        final List<ChunkRef> chunks = new ArrayList<>();
        String md5;         // whole-file MD5 from manifest (null if absent)
        long totalSize;     // sum of chunk decompressed sizes (0 if unknown)

        DepotFile(String relativePath) { this.relativePath = relativePath; }

        static class ChunkRef {
            final String hash;          // CDN-path hash (compressedMd5 preferred, else md5)
            final String compressedMd5; // verify compressed bytes before inflate (may be empty)
            final String md5;           // verify decompressed bytes after inflate (may be empty)
            final long compressedSize;  // expected compressed size, 0 if absent
            final long size;            // expected decompressed size, 0 if absent
            ChunkRef(String hash, String compressedMd5, String md5,
                     long compressedSize, long size) {
                this.hash = hash;
                this.compressedMd5 = compressedMd5;
                this.md5 = md5;
                this.compressedSize = compressedSize;
                this.size = size;
            }
        }
    }

    private static class Gen1File {
        final String path, url;
        final long offset, size;
        Gen1File(String path, String url, long offset, long size) {
            this.path = path; this.url = url; this.offset = offset; this.size = size;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pre-install size check
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches the estimated installed size in bytes for a game.
     * Gen 2: fetches builds → manifest → sums depot[].size fields (2 HTTP calls).
     * Gen 1: checks items[].total_size in builds response.
     * Returns -1 if the size cannot be determined.
     * Runs on the calling thread — call from a background thread.
     */
    public static long fetchGameSize(Context ctx, GogGame game) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("bh_gog_prefs", 0);
            String token = prefs.getString("access_token", null);
            if (token == null) return -1;

            // Gen 2: builds → manifest → sum depot sizes
            String buildsUrl = "https://content-system.gog.com/products/" + game.gameId
                    + "/os/windows/builds?generation=2";
            String buildsJson = httpGet(buildsUrl, null);
            if (buildsJson == null) buildsJson = httpGet(buildsUrl, token);
            if (buildsJson != null) {
                JSONObject builds = new JSONObject(buildsJson);
                JSONArray items = builds.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        if (!"windows".equals(item.optString("os"))) continue;
                        String mUrl = item.optString("link");
                        if (mUrl == null || mUrl.isEmpty()) mUrl = item.optString("meta_url");
                        if (mUrl == null || mUrl.isEmpty()) break;
                        byte[] raw = fetchBytes(mUrl, token);
                        if (raw == null) break;
                        String mStr = decompressBytes(raw);
                        if (mStr == null) break;
                        JSONObject manifest = new JSONObject(mStr);
                        JSONArray depots = manifest.optJSONArray("depots");
                        if (depots != null) {
                            long total = 0;
                            for (int d = 0; d < depots.length(); d++)
                                total += depots.getJSONObject(d).optLong("size", 0);
                            if (total > 0) return total;
                        }
                        break;
                    }
                }
            }

            // Gen 1 fallback: check total_size in build items
            String builds1Url = "https://content-system.gog.com/products/" + game.gameId
                    + "/os/windows/builds?generation=1";
            String builds1Json = httpGet(builds1Url, null);
            if (builds1Json == null) builds1Json = httpGet(builds1Url, token);
            if (builds1Json != null) {
                JSONObject builds1 = new JSONObject(builds1Json);
                JSONArray items1 = builds1.optJSONArray("items");
                if (items1 != null) {
                    for (int i = 0; i < items1.length(); i++) {
                        JSONObject item = items1.getJSONObject(i);
                        if (!"windows".equals(item.optString("os"))) continue;
                        long sz = item.optLong("total_size", 0);
                        if (sz > 0) return sz;
                        break;
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Formats a byte count as a human-readable string (KB / MB / GB). */
    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "Unknown";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Install size (public, called during library sync and from detail page)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the total uncompressed install size (bytes) by fetching the
     * Gen 2 top-level manifest and summing depot.size for en/all depots.
     * Returns -1 on failure. Runs on the calling thread — call from a bg thread.
     */
    public static long fetchInstallSizeBytes(String gameId, String token) {
        try {
            String buildsUrl = "https://content-system.gog.com/products/" + gameId
                    + "/os/windows/builds?generation=2";
            String buildsJson = httpGet(buildsUrl, null);
            if (buildsJson == null) buildsJson = httpGet(buildsUrl, token);
            if (buildsJson == null) return -1;

            JSONObject builds = new JSONObject(buildsJson);
            JSONArray items = builds.optJSONArray("items");
            if (items == null || items.length() == 0) return -1;

            String manifestUrl = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if ("windows".equals(item.optString("os"))) {
                    manifestUrl = item.optString("link");
                    if (manifestUrl == null || manifestUrl.isEmpty())
                        manifestUrl = item.optString("meta_url");
                    break;
                }
            }
            if (manifestUrl == null || manifestUrl.isEmpty()) return -1;

            byte[] raw = fetchBytes(manifestUrl, token);
            if (raw == null) return -1;
            String manifestStr = decompressBytes(raw);
            if (manifestStr == null) return -1;

            JSONObject manifest = new JSONObject(manifestStr);
            JSONArray depots = manifest.optJSONArray("depots");
            if (depots == null) return -1;

            long total = 0;
            for (int i = 0; i < depots.length(); i++) {
                JSONObject depot = depots.getJSONObject(i);
                JSONArray langs = depot.optJSONArray("languages");
                boolean ok = (langs == null || langs.length() == 0);
                if (!ok) {
                    String ls = langs.toString();
                    ok = ls.contains("*") || ls.contains("en-US")
                            || ls.contains("\"en\"") || ls.contains("english");
                }
                if (ok) total += depot.optLong("size", 0);
            }
            return total > 0 ? total : -1;
        } catch (Exception e) {
            Log.w(TAG, "fetchInstallSizeBytes " + gameId + ": " + e.getClass().getSimpleName());
            return -1;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Copy to Downloads (public, called from GogGamesActivity)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Copies an installed game's directory to the public Downloads/GOG Games/ folder.
     * Runs on the calling thread — must be called from a background thread.
     * Returns the destination path, or null on failure.
     */
    public static String copyToDownloads(Context ctx, String gameId) {
        SharedPreferences prefs = ctx.getSharedPreferences("bh_gog_prefs", 0);
        String dirName = prefs.getString("gog_dir_" + gameId, null);
        if (dirName == null) return null;

        File src = GogInstallPath.getInstallDir(ctx, dirName);
        if (!src.exists()) return null;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ — MediaStore.Downloads, no permission needed
            try {
                String relPath = "Download/GOG Games/" + dirName;
                copyDirMediaStore(ctx, src, relPath);
                return relPath;
            } catch (Exception e) {
                Log.e(TAG, "copyToDownloads (MediaStore) failed", e);
                return null;
            }
        } else {
            // Android 9 and below — direct File copy
            File dest = new File(
                    new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), "GOG Games"), dirName);
            dest.mkdirs();
            try {
                copyDir(src, dest);
                return dest.getAbsolutePath();
            } catch (Exception e) {
                Log.e(TAG, "copyToDownloads failed", e);
                return null;
            }
        }
    }

    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.Q)
    private static void copyDirMediaStore(Context ctx, File src, String relativePath)
            throws IOException {
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                copyDirMediaStore(ctx, f, relativePath + "/" + f.getName());
            } else {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.getName());
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, relativePath + "/");
                cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);

                android.net.Uri uri = ctx.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IOException("MediaStore insert failed: " + f.getName());

                try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                     FileInputStream fis = new FileInputStream(f)) {
                    if (os == null) throw new IOException("openOutputStream null");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
                }

                cv.clear();
                cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, cv, null, null);
            }
        }
    }

    private static void copyDir(File src, File dst) throws IOException {
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            File target = new File(dst, f.getName());
            if (f.isDirectory()) {
                target.mkdirs();
                copyDir(f, target);
            } else {
                copyFile(f, target);
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        byte[] buf = new byte[8192];
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            int n;
            while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }

    /**
     * Returns the Galaxy clientId for a game (needed for cloudstorage.gog.com URLs).
     * Checks bh_gog_prefs first; if missing, fetches the Gen2 builds endpoint,
     * downloads + decompresses the top-level manifest, extracts clientId, and caches it.
     * Falls back to gameId if everything fails.
     */
    public static String getOrFetchClientId(Context ctx, String gameId, String token) {
        SharedPreferences prefs = ctx.getSharedPreferences("bh_gog_prefs", 0);
        String cached = prefs.getString("gog_client_id_" + gameId, null);
        String cachedSecret = prefs.getString("gog_client_secret_" + gameId, null);
        // Only skip the fetch if BOTH clientId and clientSecret are cached
        if (cached != null && !cached.isEmpty()
                && cachedSecret != null && !cachedSecret.isEmpty()) return cached;

        Log.d(TAG, "clientId or clientSecret not cached for " + gameId + ", fetching from builds API");
        try {
            String buildsUrl = "https://content-system.gog.com/products/" + gameId
                    + "/os/windows/builds?generation=2";
            String buildsJson = httpGet(buildsUrl, token);
            if (buildsJson == null) {
                Log.w(TAG, "builds API returned null for " + gameId);
                return gameId;
            }
            JSONObject builds = new JSONObject(buildsJson);
            JSONArray items = builds.optJSONArray("items");
            if (items == null || items.length() == 0) return gameId;

            String manifestUrl = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                manifestUrl = item.optString("link");
                if (manifestUrl == null || manifestUrl.isEmpty())
                    manifestUrl = item.optString("meta_url");
                if (manifestUrl != null && !manifestUrl.isEmpty()) break;
            }
            if (manifestUrl == null || manifestUrl.isEmpty()) return gameId;

            byte[] manifestRaw = fetchBytes(manifestUrl, token);
            if (manifestRaw == null) return gameId;
            String manifestStr = decompressBytes(manifestRaw);
            if (manifestStr == null) return gameId;

            JSONObject manifest = new JSONObject(manifestStr);
            String clientId     = manifest.optString("clientId", null);
            String clientSecret = manifest.optString("clientSecret", null);
            if (clientId != null && !clientId.isEmpty()) {
                SharedPreferences.Editor ed = prefs.edit().putString("gog_client_id_" + gameId, clientId);
                if (clientSecret != null && !clientSecret.isEmpty())
                    ed.putString("gog_client_secret_" + gameId, clientSecret);
                ed.apply();
                Log.d(TAG, "fetched and cached clientId=" + clientId + " for " + gameId);
                return clientId;
            }
        } catch (Exception e) {
            Log.w(TAG, "getOrFetchClientId failed for " + gameId + ": " + e.getClass().getSimpleName());
        }
        return gameId; // last resort fallback
    }
}
