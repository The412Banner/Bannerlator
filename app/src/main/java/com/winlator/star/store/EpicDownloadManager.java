package com.winlator.star.store;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Inflater;

/**
 * Epic Games download pipeline.
 *
 * Handles manifest API JSON parsing, CDN selection, manifest binary download/parse,
 * chunk download with CDN rotation, and file assembly.
 *
 * Critical notes (from GameNative EpicDownloadManager):
 *   - Skip cloudflare.epicgamescdn.com CDN — 403 on chunks
 *   - Chunk subfolder = DECIMAL groupNum "%02d" (NOT hex)
 *   - NO auth tokens on chunk URLs — only on manifest binary download
 *   - Binary manifest magic = 0x44BEC00C; JSON manifest if magic doesn't match
 *
 * Selective install tags (language filtering), full-file SHA-1 delta/resume and
 * chunk-level SHA-1 verification are ported from GameNative's
 * service/epic/EpicDownloadManager.kt + manifest/ManifestUtils.kt, which are in turn
 * derived from Legendary (Epic launcher CLI). GameNative and Legendary are GPL-3.0;
 * this ported logic carries that attribution.
 *
 * Credits: The GameNative Team — https://github.com/utkarshdalal/GameNative
 *          Legendary — https://github.com/derrod/legendary (GPL-3.0)
 */
public class EpicDownloadManager {

    private static final String TAG = "BH_EPIC";
    private static final String UA  = "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit";

    // ── Public interface ──────────────────────────────────────────────────────

    public interface ProgressCallback {
        void onProgress(String message, int pct);
    }

    // ── Data classes (public for EpicApiClient size calc) ─────────────────────

    /** One CDN entry from the manifest API response. */
    public static class CdnUrl {
        public final String baseUrl;    // e.g. "https://fastly-download.epicgames.com"
        public final String cloudDir;   // e.g. "/Builds/Org/o-xxx/yyy/default"
        public final String authParams; // e.g. "?f_token=..." or ""

        public CdnUrl(String baseUrl, String cloudDir, String authParams) {
            this.baseUrl    = baseUrl;
            this.cloudDir   = cloudDir;
            this.authParams = authParams;
        }
    }

    /** Chunk info from ChunkDataList section. */
    public static class ChunkInfo {
        public int[]  guid = new int[4]; // 4 uint32 in binary read order
        public long   hash;              // uint64 stored as signed long
        public byte[] sha1;              // 20-byte SHA-1 of the DECOMPRESSED chunk (null for JSON manifests)
        public int    groupNum;          // uint8 subfolder (DECIMAL)
        public int    windowSize;        // uncompressed size
        public long   fileSize;          // compressed download size

        public String guidStr() {
            return String.format("%08X%08X%08X%08X", guid[0], guid[1], guid[2], guid[3]);
        }

        /** Full chunk path: "ChunksV4/94/HASH_GUID.chunk" */
        public String getPath(String chunkDir) {
            String sub     = String.format("%02d", groupNum);  // DECIMAL — critical!
            String hashHex = String.format("%016X", hash);
            return chunkDir + "/" + sub + "/" + hashHex + "_" + guidStr() + ".chunk";
        }
    }

    /** One file part referencing a specific chunk. */
    public static class ChunkPart {
        public int[] guid = new int[4];
        public int   offset;
        public int   size;

        public String guidStr() {
            return String.format("%08X%08X%08X%08X", guid[0], guid[1], guid[2], guid[3]);
        }
    }

    /** One file from FileManifestList. */
    public static class FileInfo {
        public String          filename    = "";
        public List<ChunkPart> parts       = new ArrayList<>();
        /** Install-tag names (e.g. "German", "de-DE"). Empty = required/base file (always installed). */
        public List<String>    installTags = new ArrayList<>();
        /** 20-byte full-file SHA-1 (null when the manifest doesn't carry it, e.g. JSON manifests). */
        public byte[]          sha1        = null;

        /** Installed (uncompressed) file size = sum of its chunk-part sizes. */
        public long fileSize() {
            long total = 0;
            for (ChunkPart p : parts) total += (p.size & 0xFFFFFFFFL);
            return total;
        }
    }

    // ── EpicManifest (parsed result + static parse methods) ───────────────────

    public static class EpicManifest {
        public String          chunkDir     = "ChunksV4";
        public List<ChunkInfo> uniqueChunks = new ArrayList<>();
        public List<FileInfo>  files        = new ArrayList<>();

        // Parsed manifest also holds CDN URLs for download
        public List<CdnUrl> cdnUrls = new ArrayList<>();

        /**
         * Parse the manifest API JSON, download the manifest binary, and parse it.
         * Returns null on any failure.
         */
        public static ParsedManifest parseManifestApiJson(String manifestApiJson,
                                                           String accessToken) {
            try {
                List<CdnUrl> cdnUrls = parseCdnUrls(manifestApiJson);
                if (cdnUrls.isEmpty()) {
                    Log.e(TAG, "No CDN URLs in manifest API response");
                    return null;
                }

                byte[] manifestBytes = downloadManifest(manifestApiJson, cdnUrls);
                if (manifestBytes == null || manifestBytes.length == 0) {
                    Log.e(TAG, "Manifest binary download failed");
                    return null;
                }

                ParsedManifest pm = parseManifest(manifestBytes);
                if (pm != null) pm.cdnUrls = cdnUrls;
                return pm;
            } catch (Exception e) {
                Log.e(TAG, "parseManifestApiJson failed", e);
                return null;
            }
        }

        public static class ParsedManifest extends EpicManifest {
            // cdnUrls inherited from EpicManifest
        }
    }

    // ── Main entry: download + install ────────────────────────────────────────

    /**
     * Download and install an Epic game.
     *
     * @param manifestApiJson  Raw JSON string from EpicApiClient.getManifestApiJson()
     * @param accessToken      Access token (NOT used on chunk URLs — only stored for future use)
     * @param installDirPath   Absolute path where game files should be written
     * @param progressCallback Optional progress callback
     * @return true on success
     */
    public static boolean install(
            android.content.Context ctx,
            String manifestApiJson,
            String accessToken,
            String installDirPath,
            ProgressCallback progressCallback) {
        // installTags == null → download EVERYTHING (legacy behavior, used by DLC installs).
        return install(ctx, manifestApiJson, accessToken, installDirPath, null, progressCallback);
    }

    /**
     * Download and install an Epic game, restricted to the given install tags.
     *
     * @param installTags Language/optional install tags to include (in addition to required/base
     *                    files). {@code null} = no filtering (download all files, legacy behavior);
     *                    empty list = required/base files only. Ported from GameNative's
     *                    getFilesForSelectedInstallTags — required (untagged) files are ALWAYS
     *                    included, plus any file carrying at least one of these tags.
     *
     * Files already present on disk with a matching size + full-file SHA-1 are skipped, which gives
     * both resume-of-interrupted-installs and delta-update (only changed files re-download) for free.
     */
    public static boolean install(
            android.content.Context ctx,
            String manifestApiJson,
            String accessToken,
            String installDirPath,
            List<String> installTags,
            ProgressCallback progressCallback) {
        // No cancel flag → best-effort, never-cancelled (legacy / DLC path).
        return install(ctx, manifestApiJson, accessToken, installDirPath, installTags, null, progressCallback);
    }

    /**
     * Cancel-aware install. {@code cancelFlag} is polled inside the parallel chunk pool and the
     * assemble loop; flipping it to {@code true} stops the download promptly (no new chunk starts,
     * in-flight chunks are interrupted, partial files stay on disk for a later #3 delta-resume).
     * {@code null} = never cancel.
     */
    /** True if a cancel has been requested. Null flag = never cancel (legacy/DLC path). */
    private static boolean isCancelled(AtomicBoolean cancelFlag) {
        return cancelFlag != null && cancelFlag.get();
    }

    /** Log the cancel point, flush debug, and return false — the common cancel exit. */
    private static boolean cancelOut(android.content.Context ctx, StringBuilder dbg, String where) {
        dbg.append("CANCELLED ").append(where).append("\n");
        writeDebug(ctx, dbg);
        Log.i(TAG, "Epic install cancelled (" + where + ")");
        return false;
    }

    public static boolean install(
            android.content.Context ctx,
            String manifestApiJson,
            String accessToken,
            String installDirPath,
            List<String> installTags,
            AtomicBoolean cancelFlag,
            ProgressCallback progressCallback) {
        StringBuilder dbg = new StringBuilder();
        dbg.append("=== BH Epic Debug ===\n");
        dbg.append("installDirPath=").append(installDirPath).append("\n");
        try {
            if (isCancelled(cancelFlag)) return cancelOut(ctx, dbg, "before start");
            progress(progressCallback, "Parsing CDN URLs...", 0);

            List<CdnUrl> cdnUrls = parseCdnUrls(manifestApiJson);
            if (cdnUrls.isEmpty()) {
                dbg.append("ERROR: No CDN URLs in manifest API response\n");
                writeDebug(ctx, dbg);
                Log.e(TAG, "No CDN URLs in manifest API response");
                return false;
            }
            for (CdnUrl c : cdnUrls) {
                dbg.append("CDN: ").append(c.baseUrl)
                   .append("  cloudDir=").append(c.cloudDir)
                   .append("  auth=").append(c.authParams.isEmpty() ? "(none)" : "YES").append("\n");
                Log.i(TAG, "  CDN: " + c.baseUrl + "  auth: " + (c.authParams.isEmpty() ? "(none)" : "YES"));
            }

            if (isCancelled(cancelFlag)) return cancelOut(ctx, dbg, "after CDN parse");
            progress(progressCallback, "Downloading manifest...", 0);
            byte[] manifestBytes = downloadManifest(manifestApiJson, cdnUrls);
            if (manifestBytes == null) {
                dbg.append("ERROR: Manifest binary download failed\n");
                writeDebug(ctx, dbg);
                Log.e(TAG, "Manifest binary download failed");
                return false;
            }
            dbg.append("manifestBytes=").append(manifestBytes.length).append("\n");
            Log.i(TAG, "Manifest bytes: " + manifestBytes.length);

            if (isCancelled(cancelFlag)) return cancelOut(ctx, dbg, "after manifest download");
            progress(progressCallback, "Parsing manifest...", 0);
            EpicManifest.ParsedManifest manifest = parseManifest(manifestBytes);
            if (manifest == null) {
                dbg.append("ERROR: Manifest parse failed\n");
                writeDebug(ctx, dbg);
                Log.e(TAG, "Manifest parse failed");
                return false;
            }
            manifest.cdnUrls = cdnUrls;
            dbg.append("chunkDir=").append(manifest.chunkDir)
               .append(" chunks=").append(manifest.uniqueChunks.size())
               .append(" files=").append(manifest.files.size()).append("\n");
            Log.i(TAG, "Manifest: chunkDir=" + manifest.chunkDir
                    + " chunks=" + manifest.uniqueChunks.size()
                    + " files=" + manifest.files.size());

            File installDir  = new File(installDirPath);
            installDir.mkdirs();
            File chunkCacheDir = new File(installDir, ".chunks");
            chunkCacheDir.mkdirs();

            // Feature #2 — selective install tags: narrow the manifest file list to
            // required(base) + the container-language files before downloading anything.
            // installTags == null → no filtering (legacy: every file). Degrades gracefully:
            // if the manifest carries no tags, resolveInstallFiles returns all files.
            List<FileInfo> selectedFiles = resolveInstallFiles(manifest, installTags);
            dbg.append("installTags=").append(installTags == null ? "(all)" : installTags.toString())
               .append(" selectedFiles=").append(selectedFiles.size())
               .append("/").append(manifest.files.size()).append("\n");
            Log.i(TAG, "Selected " + selectedFiles.size() + "/" + manifest.files.size()
                    + " files for tags " + (installTags == null ? "(all)" : installTags));

            // Feature #3 — delta / resume: skip files already on disk with matching size + SHA-1.
            // Fresh install → nothing on disk → no hashing cost. Resume/repair → re-hash existing
            // completed files (streamed, off the main thread) and only fetch the rest.
            progress(progressCallback, "Verifying existing files…", 0);
            List<FileInfo> pendingFiles = new ArrayList<>(selectedFiles.size());
            int alreadyGood = 0;
            int checked = 0;
            final int selCount = selectedFiles.size();
            for (FileInfo f : selectedFiles) {
                // Cancel: hashing an already-installed game can take minutes — honor cancel here too,
                // not just in the chunk pool. This is the "Verifying…"/0% phase the UI shows.
                if (cancelFlag != null && cancelFlag.get()) {
                    dbg.append("CANCELLED during verify (").append(checked).append("/")
                       .append(selCount).append(" files hashed)\n");
                    writeDebug(ctx, dbg);
                    Log.i(TAG, "Epic install cancelled during verify phase");
                    return false;
                }
                File out = new File(installDir, f.filename.replace("\\", "/"));
                if (fileExistsWithCorrectHash(out, f.fileSize(), f.sha1)) alreadyGood++;
                else pendingFiles.add(f);
                checked++;
                // Report verify progress every 64 files so the phase is visible and responsive.
                if ((checked & 63) == 0) {
                    progress(progressCallback, "Verifying existing files… (" + checked + "/" + selCount + ")", 0);
                }
            }
            dbg.append("delta: ").append(alreadyGood).append(" up-to-date, ")
               .append(pendingFiles.size()).append(" to download\n");
            Log.i(TAG, "Delta: " + alreadyGood + " already correct, " + pendingFiles.size() + " to (re)download");

            // Nothing to do (fully-installed re-check, or repair found no damage): success.
            if (pendingFiles.isEmpty()) {
                deleteDir(chunkCacheDir);
                dbg.append("INSTALL COMPLETE (nothing to download): ").append(installDirPath).append("\n");
                writeDebug(ctx, dbg);
                progress(progressCallback, "Complete", 100);
                Log.i(TAG, "Epic install complete (delta no-op): " + installDirPath);
                return true;
            }

            // Only the chunks referenced by the pending files need downloading.
            List<ChunkInfo> neededChunks = uniqueChunksForFiles(manifest, pendingFiles);

            // Calculate total download bytes for smooth byte-level progress
            long totalBytes = 0;
            for (ChunkInfo chunk : neededChunks) totalBytes += Math.max(chunk.fileSize, 1);
            final long fTotalBytes = totalBytes;
            final int totalChunks  = neededChunks.size();
            final AtomicLong completedBytes    = new AtomicLong(0);
            final AtomicInteger completedCount = new AtomicInteger(0);
            final AtomicInteger failCount      = new AtomicInteger(0);
            final AtomicLong lastSpeedMs       = new AtomicLong(System.currentTimeMillis());
            final AtomicLong lastSpeedBytes    = new AtomicLong(0);
            final AtomicLong currentSpeedBps   = new AtomicLong(0);
            final java.util.concurrent.ConcurrentLinkedQueue<String> chunkLog =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();

            dbg.append("totalDownloadBytes=").append(totalBytes)
               .append(String.format(" (%.1f MB)\n", totalBytes / 1048576.0));

            // Engine switch (docs/RUST_EPIC_PARITY.md): the native fetch core replaces ONLY the
            // pool below — same inputs (needed chunks, CDN list, cache dir), same outputs
            // (`.chunks/<GUID>` verified cache files), same progress / cancel / failure handling
            // and the same debug lines. The Java pool is byte-identical and runs when the flag is
            // OFF or the engine could not start (library missing, plan cross-check failed) —
            // nothing has been fetched in that case, so falling through is safe.
            boolean useJavaPool = true;
            if (com.winlator.star.store.blsteam.BlStoreEngineFlag.isEpicEnabled(ctx)) {
                RustPoolResult rr = runRustChunkPool(ctx, dbg, manifestBytes, manifest, pendingFiles,
                        neededChunks, fTotalBytes, installDirPath, cdnUrls, cancelFlag, progressCallback,
                        completedBytes, completedCount, lastSpeedMs, lastSpeedBytes, currentSpeedBps);
                if (rr.started) {
                    useJavaPool = false;
                    if (rr.cancelled) {
                        dbg.append("CANCELLED during chunk download ")
                           .append("(").append(completedCount.get()).append("/").append(totalChunks)
                           .append(" chunks)\n");
                        writeDebug(ctx, dbg);
                        Log.i(TAG, "Epic install cancelled during chunk download");
                        return false;
                    }
                    if (!rr.success) {
                        // Surfaces through the shared "N chunks failed" exit below, like a Java
                        // chunk that exhausted every CDN.
                        chunkLog.add("FAIL engine=rust " + rr.error);
                        failCount.incrementAndGet();
                    }
                } else {
                    dbg.append("rust engine not started (").append(rr.error)
                       .append(") -> Java chunk pool\n");
                    Log.w(TAG, "Rust Epic engine not started (" + rr.error + "), using Java chunk pool");
                }
            }
            if (useJavaPool) {
                // Download unique chunks — 8 parallel threads
                ExecutorService pool = Executors.newFixedThreadPool(8);
                for (ChunkInfo chunk : neededChunks) {
                    final ChunkInfo fc = chunk;
                    pool.submit(() -> {
                        // Cancel: don't start new chunk work once the user has cancelled.
                        if (cancelFlag != null && cancelFlag.get()) return;
                        File cachedFile = new File(chunkCacheDir, fc.guidStr());
                        if (!cachedFile.exists()) {
                            if (!downloadChunkStreaming(fc, manifest.chunkDir, cdnUrls, cachedFile)) {
                                Log.e(TAG, "Chunk download failed: " + fc.guidStr());
                                chunkLog.add("FAIL chunk=" + fc.guidStr());
                                failCount.incrementAndGet();
                                return;
                            }
                        }
                        long done = completedBytes.addAndGet(Math.max(fc.fileSize, 1));
                        int  cnt  = completedCount.incrementAndGet();
                        int  pct  = (int)(done * 80L / fTotalBytes);

                        long nowMs     = System.currentTimeMillis();
                        long prevMs    = lastSpeedMs.get();
                        long timeDelta = nowMs - prevMs;
                        if (timeDelta >= 500 && lastSpeedMs.compareAndSet(prevMs, nowMs)) {
                            long prevB  = lastSpeedBytes.getAndSet(done);
                            long bDelta = done - prevB;
                            if (timeDelta > 0) currentSpeedBps.set(bDelta * 1000L / timeDelta);
                        }

                        String mb    = String.format("%.1f / %.1f MB", done / 1048576.0, fTotalBytes / 1048576.0);
                        String speed = formatSpeed(currentSpeedBps.get());
                        progress(progressCallback,
                                "Downloading chunks (" + cnt + "/" + totalChunks + ")  " + mb
                                + (speed.isEmpty() ? "" : "  " + speed), pct);
                    });
                }
                pool.shutdown();
                try {
                    // Poll instead of a single infinite await so a cancel is honored within ~250ms:
                    // in-flight chunks are interrupted, queued ones never start (closure guard above).
                    while (!pool.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                        if (cancelFlag != null && cancelFlag.get()) {
                            pool.shutdownNow();
                            pool.awaitTermination(5, TimeUnit.SECONDS);
                            dbg.append("CANCELLED during chunk download ")
                               .append("(").append(completedCount.get()).append("/").append(totalChunks)
                               .append(" chunks)\n");
                            writeDebug(ctx, dbg);
                            Log.i(TAG, "Epic install cancelled during chunk download");
                            return false;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pool.shutdownNow();
                    dbg.append("ERROR: chunk pool interrupted\n");
                    writeDebug(ctx, dbg);
                    return false;
                }
            }

            // Cancel could also have landed on the last poll boundary, after the pool drained.
            if (cancelFlag != null && cancelFlag.get()) {
                dbg.append("CANCELLED after chunk download\n");
                writeDebug(ctx, dbg);
                Log.i(TAG, "Epic install cancelled after chunk download");
                return false;
            }

            // Drain per-chunk failures into dbg
            for (String line : chunkLog) dbg.append(line).append("\n");

            if (failCount.get() > 0) {
                dbg.append("ERROR: ").append(failCount.get()).append(" chunks failed\n");
                writeDebug(ctx, dbg);
                Log.e(TAG, failCount.get() + " chunks failed to download");
                return false;
            }
            dbg.append("chunksOK=").append(completedCount.get()).append("\n");

            // Assemble files — only the pending set (already-correct files are left untouched).
            int totalFiles = pendingFiles.size();
            int doneFiles  = 0;
            dbg.append("assembling ").append(totalFiles).append(" files\n");
            for (FileInfo file : pendingFiles) {
                if (cancelFlag != null && cancelFlag.get()) {
                    dbg.append("CANCELLED during assembly (").append(doneFiles).append("/")
                       .append(totalFiles).append(" files)\n");
                    writeDebug(ctx, dbg);
                    Log.i(TAG, "Epic install cancelled during assembly");
                    return false;
                }
                String relPath = file.filename.replace("\\", "/");
                File outFile   = new File(installDir, relPath);
                File parent    = outFile.getParentFile();
                if (parent != null) parent.mkdirs();

                String displayName = relPath.contains("/")
                        ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
                int pct = 80 + (int)(doneFiles * 20L / totalFiles);
                progress(progressCallback, "Writing: " + displayName, pct);

                try (FileOutputStream fos = new FileOutputStream(outFile);
                     BufferedOutputStream bos = new BufferedOutputStream(fos, 65536)) {
                    for (ChunkPart part : file.parts) {
                        File cachedChunk = new File(chunkCacheDir, part.guidStr());
                        if (!cachedChunk.exists()) {
                            dbg.append("ERROR: missing chunk ").append(part.guidStr())
                               .append(" for ").append(relPath).append("\n");
                            writeDebug(ctx, dbg);
                            Log.e(TAG, "Missing cached chunk " + part.guidStr() + " for " + relPath);
                            return false;
                        }
                        byte[] chunkData = readFile(cachedChunk);
                        bos.write(chunkData, part.offset, part.size);
                    }
                }

                doneFiles++;
            }

            deleteDir(chunkCacheDir);
            dbg.append("INSTALL COMPLETE: ").append(installDirPath).append("\n");
            writeDebug(ctx, dbg);
            Log.i(TAG, "Epic install complete: " + installDirPath);
            return true;

        } catch (Exception e) {
            dbg.append("EXCEPTION: ").append(e).append("\n");
            writeDebug(ctx, dbg);
            Log.e(TAG, "Epic install failed", e);
            return false;
        }
    }

    // ── Rust fetch core (engine switch) ───────────────────────────────────────

    /** Outcome of the native chunk pool; {@code started == false} → the Java pool must run. */
    private static final class RustPoolResult {
        boolean started;
        boolean success;
        boolean cancelled;
        String  error = "";
    }

    /**
     * Run the native chunk pool for {@code pendingFiles}: the engine re-parses the SAME manifest
     * bytes, rebuilds the SAME needed-chunk list (cross-checked against Java's count + byte total
     * before any fetch), skips chunks already in the cache, and writes verified chunks with the
     * same {@code .part} + rename protocol into the same {@code .chunks/<GUID>} names — so the
     * assembly loop and every resume path below see exactly what the Java pool would have left.
     * Progress mirrors the pool task's message / pct / speed logic on the SAME counters so the UI
     * and registry ticks are indistinguishable. Never throws.
     */
    private static RustPoolResult runRustChunkPool(
            android.content.Context ctx,
            StringBuilder dbg,
            byte[] manifestBytes,
            EpicManifest.ParsedManifest manifest,
            List<FileInfo> pendingFiles,
            List<ChunkInfo> neededChunks,
            long totalBytes,
            String installDirPath,
            List<CdnUrl> cdnUrls,
            AtomicBoolean cancelFlag,
            ProgressCallback progressCallback,
            AtomicLong completedBytes,
            AtomicInteger completedCount,
            AtomicLong lastSpeedMs,
            AtomicLong lastSpeedBytes,
            AtomicLong currentSpeedBps) {
        RustPoolResult r = new RustPoolResult();
        try {
            // pendingFiles are FileInfo objects out of manifest.files (identity, no equals()).
            java.util.IdentityHashMap<FileInfo, Integer> index = new java.util.IdentityHashMap<>();
            for (int i = 0; i < manifest.files.size(); i++) index.put(manifest.files.get(i), i);
            int[] pendingIdx = new int[pendingFiles.size()];
            for (int i = 0; i < pendingFiles.size(); i++) {
                Integer k = index.get(pendingFiles.get(i));
                if (k == null) { r.error = "pending file not in manifest"; return r; }
                pendingIdx[i] = k;
            }
            String[] prefixes = new String[cdnUrls.size()];
            for (int i = 0; i < cdnUrls.size(); i++)
                prefixes[i] = cdnUrls.get(i).baseUrl + cdnUrls.get(i).cloudDir;
            String caPath = com.winlator.star.store.blsteam.CaBundleExtractor.INSTANCE.ensureBundle(ctx);

            final int  totalChunks = neededChunks.size();
            final long fTotalBytes = totalBytes;
            // Improvements round 1: the native path takes its ceilings from the Steam speed tier
            // (Fast = window 32, decompress = cores/2) instead of the Java pool's fixed 8; the
            // adapter splits the window across the distinct CDNs (per_host_cap). The Java
            // fallback pool below keeps its own fixed counts.
            DownloadSpeedConfig cfg = StoreDownloadTier.config(ctx);
            final int rustWorkers = Math.max(1, Math.min(128, cfg.getMaxNetworkWindow()));
            final int rustProcess = Math.max(2, Math.max(1, Math.min(32, cfg.getMaxDecompress())));
            com.winlator.star.store.blsteam.BlEpicDownload.Result res =
                    com.winlator.star.store.blsteam.BlEpicDownload.run(
                            manifestBytes, installDirPath, prefixes, pendingIdx, totalChunks, totalBytes,
                            caPath, rustWorkers, rustProcess, cancelFlag,
                            new com.winlator.star.store.blsteam.BlEpicDownload.Listener() {
                @Override public void onPlan(int chunksTotal, long bytesTotal, String chunkDir) {
                    synchronized (dbg) {
                        dbg.append("[rust] plan chunks=").append(chunksTotal)
                           .append(" bytes=").append(bytesTotal)
                           .append(" chunkDir=").append(chunkDir).append("\n");
                    }
                }

                @Override public void onProgress(long bytesDone, long bytesTotal, int chunksDone, int chunksTotal) {
                    // Mirrors the pool task above: same counters, same pct, same 500 ms speed sample,
                    // same message.
                    completedBytes.set(bytesDone);
                    completedCount.set(chunksDone);
                    long done = bytesDone;
                    int  cnt  = chunksDone;
                    int  pct  = (int)(done * 80L / fTotalBytes);

                    long nowMs     = System.currentTimeMillis();
                    long prevMs    = lastSpeedMs.get();
                    long timeDelta = nowMs - prevMs;
                    if (timeDelta >= 500 && lastSpeedMs.compareAndSet(prevMs, nowMs)) {
                        long prevB  = lastSpeedBytes.getAndSet(done);
                        long bDelta = done - prevB;
                        if (timeDelta > 0) currentSpeedBps.set(bDelta * 1000L / timeDelta);
                    }

                    String mb    = String.format("%.1f / %.1f MB", done / 1048576.0, fTotalBytes / 1048576.0);
                    String speed = formatSpeed(currentSpeedBps.get());
                    progress(progressCallback,
                            "Downloading chunks (" + cnt + "/" + totalChunks + ")  " + mb
                            + (speed.isEmpty() ? "" : "  " + speed), pct);
                }

                @Override public void onLog(String line) {
                    synchronized (dbg) { dbg.append("[rust] ").append(line).append("\n"); }
                }

                @Override public void onComplete(boolean success, String error, long bytesCredited) {
                    // Terminal state is read from the facade's Result; nothing to do here.
                }
            });
            r.started   = res.started;
            r.success   = res.success;
            r.cancelled = res.cancelled;
            r.error     = res.error == null ? "" : res.error;
        } catch (Throwable t) {
            // Any facade / link failure → Java pool.
            r.started = false;
            r.error   = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return r;
    }

    private static void writeDebug(android.content.Context ctx, StringBuilder dbg) {
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            java.io.File f = new java.io.File(dir, "bh_epic_debug.txt");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(dbg.toString().getBytes("UTF-8"));
            }
            Log.i(TAG, "Debug written to: " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "writeDebug failed", e);
        }
    }

    // ── CDN URL parsing ───────────────────────────────────────────────────────

    /**
     * Extract all CDN entries from the manifest API JSON.
     * Skips cloudflare.epicgamescdn.com.
     */
    public static List<CdnUrl> parseCdnUrls(String json) {
        List<CdnUrl> result = new ArrayList<>();
        try {
            int manifestsIdx = json.indexOf("\"manifests\"");
            if (manifestsIdx < 0) return result;
            int arrStart = json.indexOf("[", manifestsIdx);
            if (arrStart < 0) return result;

            int cursor = arrStart + 1;
            while (true) {
                int uriKeyIdx = json.indexOf("\"uri\"", cursor);
                if (uriKeyIdx < 0) break;

                int colon = json.indexOf(":", uriKeyIdx + 5);
                if (colon < 0) break;
                int q1 = json.indexOf("\"", colon + 1);
                if (q1 < 0) break;
                int q2 = json.indexOf("\"", q1 + 1);
                if (q2 < 0) break;
                String uri = json.substring(q1 + 1, q2);
                cursor = q2 + 1;

                int buildsIdx = uri.indexOf("/Builds");
                if (buildsIdx < 0) continue;

                String baseUrl = uri.substring(0, buildsIdx);
                if (!baseUrl.startsWith("http")) continue;
                if (baseUrl.contains("cloudflare.epicgamescdn.com")) continue;

                String afterBase = uri.substring(buildsIdx);
                int qMark = afterBase.indexOf("?");
                if (qMark >= 0) afterBase = afterBase.substring(0, qMark);
                int lastSlash = afterBase.lastIndexOf("/");
                if (lastSlash < 0) continue;
                String cloudDir = afterBase.substring(0, lastSlash);

                String authParams = extractQueryParams(json, uriKeyIdx);
                result.add(new CdnUrl(baseUrl, cloudDir, authParams));
            }
        } catch (Exception e) {
            Log.e(TAG, "parseCdnUrls error: " + e.getClass().getSimpleName());
        }
        return result;
    }

    private static String extractQueryParams(String json, int nearPos) {
        try {
            int end    = Math.min(json.length(), nearPos + 2000);
            int qpIdx  = json.indexOf("\"queryParams\"", nearPos);
            if (qpIdx < 0 || qpIdx > end) return "";
            int arrOpen  = json.indexOf("[", qpIdx);
            if (arrOpen < 0) return "";
            int arrClose = json.indexOf("]", arrOpen);
            if (arrClose < 0) return "";
            String arrContent = json.substring(arrOpen + 1, arrClose).trim();
            if (arrContent.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("?");
            boolean first = true;
            int pos = 0;
            while (pos < arrContent.length()) {
                int nameIdx = arrContent.indexOf("\"name\"", pos);
                if (nameIdx < 0) break;
                int nColon = arrContent.indexOf(":", nameIdx + 6);
                if (nColon < 0) break;
                int nq1 = arrContent.indexOf("\"", nColon + 1);
                if (nq1 < 0) break;
                int nq2 = arrContent.indexOf("\"", nq1 + 1);
                if (nq2 < 0) break;
                String name = arrContent.substring(nq1 + 1, nq2);

                int valIdx = arrContent.indexOf("\"value\"", nq2);
                if (valIdx < 0) break;
                int vColon = arrContent.indexOf(":", valIdx + 7);
                if (vColon < 0) break;
                int vq1 = arrContent.indexOf("\"", vColon + 1);
                if (vq1 < 0) break;
                int vq2 = arrContent.indexOf("\"", vq1 + 1);
                if (vq2 < 0) break;
                String value = arrContent.substring(vq1 + 1, vq2);

                if (!first) sb.append("&");
                sb.append(name).append("=").append(value);
                first = false;
                pos = vq2 + 1;
            }
            return first ? "" : sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ── Manifest download ─────────────────────────────────────────────────────

    /**
     * Download the manifest binary, trying each CDN in order.
     * Auth tokens (queryParams) are appended to the manifest URL — but NOT to chunk URLs.
     */
    public static byte[] downloadManifest(String json, List<CdnUrl> cdnUrls) {
        try {
            int manifestsIdx = json.indexOf("\"manifests\"");
            if (manifestsIdx < 0) return null;
            int uriIdx = json.indexOf("\"uri\"", manifestsIdx);
            if (uriIdx < 0) return null;
            int colon = json.indexOf(":", uriIdx + 5);
            if (colon < 0) return null;
            int q1 = json.indexOf("\"", colon + 1);
            if (q1 < 0) return null;
            int q2 = json.indexOf("\"", q1 + 1);
            if (q2 < 0) return null;
            String firstUri = json.substring(q1 + 1, q2);

            String uriPath = firstUri.contains("?") ? firstUri.substring(0, firstUri.indexOf("?")) : firstUri;
            int lastSlash = uriPath.lastIndexOf("/");
            if (lastSlash < 0) return null;
            String manifestFilename = uriPath.substring(lastSlash + 1);
            Log.i(TAG, "Manifest filename: " + manifestFilename);

            for (CdnUrl cdn : cdnUrls) {
                String url = cdn.baseUrl + cdn.cloudDir + "/" + manifestFilename + cdn.authParams;
                Log.i(TAG, "Trying manifest CDN: " + cdn.baseUrl);
                byte[] bytes = downloadBytes(url, null);
                if (bytes != null && bytes.length > 4) {
                    Log.i(TAG, "Manifest OK (" + bytes.length + " bytes) from " + cdn.baseUrl);
                    return bytes;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "downloadManifest error: " + e.getClass().getSimpleName());
        }
        return null;
    }

    // ── Manifest parsing ──────────────────────────────────────────────────────

    public static EpicManifest.ParsedManifest parseManifest(byte[] bytes) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

            int magic = buf.getInt();
            if (magic != 0x44BEC00C) {
                Log.w(TAG, "Non-binary manifest, trying JSON parser");
                return parseJsonManifest(bytes);
            }

            int headerSize       = buf.getInt();
            int sizeUncompressed = buf.getInt();
            /* sizeCompressed */ buf.getInt();
            buf.position(buf.position() + 20); // skip SHA-1
            int storedAs = buf.get() & 0xFF;
            int version  = buf.getInt();

            String chunkDir;
            if      (version >= 15) chunkDir = "ChunksV4";
            else if (version >= 6)  chunkDir = "ChunksV3";
            else if (version >= 3)  chunkDir = "ChunksV2";
            else                    chunkDir = "Chunks";

            buf.position(headerSize);
            byte[] bodyBytes = new byte[buf.remaining()];
            buf.get(bodyBytes);

            if ((storedAs & 1) != 0) {
                Inflater inflater = new Inflater();
                inflater.setInput(bodyBytes);
                byte[] decomp = new byte[sizeUncompressed];
                int got = inflater.inflate(decomp);
                inflater.end();
                if (got != sizeUncompressed) {
                    Log.e(TAG, "Decomp size mismatch: expected " + sizeUncompressed + " got " + got);
                    return null;
                }
                bodyBytes = decomp;
            }

            ByteBuffer body = ByteBuffer.wrap(bodyBytes).order(ByteOrder.LITTLE_ENDIAN);

            // Skip ManifestMeta section
            int metaSize = body.getInt();
            body.position(body.position() - 4 + metaSize);

            // ChunkDataList section
            int cdlStart = body.position();
            int cdlSize  = body.getInt();
            body.get(); // version byte
            int chunkCount = body.getInt();

            List<ChunkInfo> chunks = new ArrayList<>(chunkCount);
            for (int i = 0; i < chunkCount; i++) chunks.add(new ChunkInfo());

            for (ChunkInfo c : chunks) {
                c.guid[0] = body.getInt();
                c.guid[1] = body.getInt();
                c.guid[2] = body.getInt();
                c.guid[3] = body.getInt();
            }
            for (ChunkInfo c : chunks) c.hash       = body.getLong();
            for (ChunkInfo c : chunks) { c.sha1 = new byte[20]; body.get(c.sha1); } // per-chunk SHA-1
            for (ChunkInfo c : chunks) c.groupNum   = body.get() & 0xFF;
            for (ChunkInfo c : chunks) c.windowSize = body.getInt();
            for (ChunkInfo c : chunks) c.fileSize   = body.getLong();

            body.position(cdlStart + cdlSize);

            Map<String, ChunkInfo> chunkMap = new LinkedHashMap<>(chunkCount * 2);
            for (ChunkInfo c : chunks) chunkMap.put(c.guidStr(), c);

            // FileManifestList section
            int fmlStart = body.position();
            int fmlSize  = body.getInt();
            body.get(); // version byte
            int fileCount = body.getInt();

            List<FileInfo> files = new ArrayList<>(fileCount);
            for (int i = 0; i < fileCount; i++) files.add(new FileInfo());

            for (FileInfo f : files) f.filename = readFString(body);
            for (int i = 0; i < fileCount; i++) readFString(body);           // symlink targets
            for (FileInfo f : files) { f.sha1 = new byte[20]; body.get(f.sha1); } // per-file SHA-1
            body.position(body.position() + fileCount);                        // skip flags
            for (int i = 0; i < fileCount; i++) {                             // install tags
                int tagCount = body.getInt();
                for (int j = 0; j < tagCount; j++) {
                    String tag = readFString(body);
                    if (tag != null && !tag.isEmpty()) files.get(i).installTags.add(tag);
                }
            }
            for (FileInfo f : files) {
                int partCount = body.getInt();
                for (int j = 0; j < partCount; j++) {
                    int partStart      = body.position();
                    int partStructSize = body.getInt();
                    ChunkPart part = new ChunkPart();
                    part.guid[0] = body.getInt();
                    part.guid[1] = body.getInt();
                    part.guid[2] = body.getInt();
                    part.guid[3] = body.getInt();
                    part.offset  = body.getInt();
                    part.size    = body.getInt();
                    f.parts.add(part);
                    body.position(partStart + partStructSize);
                }
            }

            body.position(fmlStart + fmlSize);

            Map<String, ChunkInfo> seenMap = new LinkedHashMap<>(chunkCount * 2);
            for (ChunkInfo c : chunks) seenMap.put(c.guidStr(), c);

            EpicManifest.ParsedManifest result = new EpicManifest.ParsedManifest();
            result.chunkDir     = chunkDir;
            result.uniqueChunks = new ArrayList<>(seenMap.values());
            result.files        = files;
            return result;

        } catch (Exception e) {
            Log.e(TAG, "parseManifest error", e);
            return null;
        }
    }

    // ── JSON manifest (older games) ───────────────────────────────────────────

    private static EpicManifest.ParsedManifest parseJsonManifest(byte[] bytes) {
        try {
            String jsonStr = new String(bytes, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(jsonStr);

            int manifestVersion = 0;
            try { manifestVersion = Integer.parseInt(root.optString("ManifestFileVersion", "0")); }
            catch (NumberFormatException ignored) {}
            String chunkDir;
            if      (manifestVersion >= 15) chunkDir = "ChunksV4";
            else if (manifestVersion >= 6)  chunkDir = "ChunksV3";
            else if (manifestVersion >= 3)  chunkDir = "ChunksV2";
            else                            chunkDir = "ChunksV4";

            JSONObject chunkHashList     = root.optJSONObject("ChunkHashList");
            JSONObject dataGroupList     = root.optJSONObject("DataGroupList");
            JSONObject chunkFilesizeList = root.optJSONObject("ChunkFilesizeList");

            if (chunkHashList == null) {
                Log.e(TAG, "JSON manifest: no ChunkHashList");
                return null;
            }

            Map<String, ChunkInfo> chunkMap = new LinkedHashMap<>();
            Iterator<String> keys = chunkHashList.keys();
            while (keys.hasNext()) {
                String guidHex = keys.next();
                if (guidHex.length() < 32) continue;
                String hashHex = chunkHashList.getString(guidHex);

                ChunkInfo c = new ChunkInfo();
                c.guid[0] = (int) Long.parseLong(guidHex.substring(0, 8),  16);
                c.guid[1] = (int) Long.parseLong(guidHex.substring(8,  16), 16);
                c.guid[2] = (int) Long.parseLong(guidHex.substring(16, 24), 16);
                c.guid[3] = (int) Long.parseLong(guidHex.substring(24, 32), 16);

                if (hashHex != null && hashHex.length() >= 16) {
                    try { c.hash = Long.parseUnsignedLong(hashHex.substring(0, 16), 16); }
                    catch (Exception ignored) { c.hash = 0; }
                }
                if (dataGroupList != null) {
                    try { c.groupNum = Integer.parseInt(dataGroupList.optString(guidHex, "0")); }
                    catch (NumberFormatException ignored) { c.groupNum = 0; }
                }
                if (chunkFilesizeList != null) {
                    try { c.fileSize = Long.parseLong(chunkFilesizeList.optString(guidHex, "0"), 16); }
                    catch (NumberFormatException ignored) { c.fileSize = 0; }
                }
                c.windowSize = 0;
                chunkMap.put(guidHex, c);
            }

            JSONArray fileList = root.optJSONArray("FileManifestList");
            if (fileList == null) {
                Log.e(TAG, "JSON manifest: no FileManifestList");
                return null;
            }

            List<FileInfo> files = new ArrayList<>(fileList.length());
            for (int i = 0; i < fileList.length(); i++) {
                JSONObject fileObj = fileList.getJSONObject(i);
                FileInfo fi = new FileInfo();
                fi.filename = fileObj.optString("Filename", "");

                // Optional per-file install tags (older JSON manifests). SHA-1 is left null:
                // JSON manifests encode hashes in a per-byte "blob" form we don't parse, so
                // full-file verification simply falls back to re-download for these games.
                JSONArray installTags = fileObj.optJSONArray("InstallTags");
                if (installTags != null) {
                    for (int t = 0; t < installTags.length(); t++) {
                        String tag = installTags.optString(t, "");
                        if (!tag.isEmpty()) fi.installTags.add(tag);
                    }
                }

                JSONArray chunkParts = fileObj.optJSONArray("FileChunkParts");
                if (chunkParts != null) {
                    for (int j = 0; j < chunkParts.length(); j++) {
                        JSONObject partObj = chunkParts.getJSONObject(j);
                        ChunkPart part = new ChunkPart();
                        String partGuid = partObj.optString("Guid", "");
                        if (partGuid.length() >= 32) {
                            part.guid[0] = (int) Long.parseLong(partGuid.substring(0, 8),  16);
                            part.guid[1] = (int) Long.parseLong(partGuid.substring(8,  16), 16);
                            part.guid[2] = (int) Long.parseLong(partGuid.substring(16, 24), 16);
                            part.guid[3] = (int) Long.parseLong(partGuid.substring(24, 32), 16);
                        }
                        try { part.offset = Integer.parseInt(partObj.optString("Offset", "0")); }
                        catch (NumberFormatException ignored) { part.offset = 0; }
                        try { part.size = Integer.parseInt(partObj.optString("Size", "0")); }
                        catch (NumberFormatException ignored) { part.size = 0; }
                        fi.parts.add(part);
                    }
                }
                files.add(fi);
            }

            EpicManifest.ParsedManifest result = new EpicManifest.ParsedManifest();
            result.chunkDir     = chunkDir;
            result.uniqueChunks = new ArrayList<>(chunkMap.values());
            result.files        = files;
            Log.i(TAG, "JSON manifest: chunkDir=" + chunkDir
                    + " chunks=" + result.uniqueChunks.size()
                    + " files=" + files.size());
            return result;

        } catch (Exception e) {
            Log.e(TAG, "parseJsonManifest error", e);
            return null;
        }
    }

    // ── Chunk download ────────────────────────────────────────────────────────

    public static boolean downloadChunk(ChunkInfo chunk, String chunkDir,
                                         List<CdnUrl> cdnUrls, File outFile) {
        String chunkPath = chunk.getPath(chunkDir);
        for (CdnUrl cdn : cdnUrls) {
            // NO auth tokens on chunk URLs — Fastly/Akamai serve chunks publicly
            String url = cdn.baseUrl + cdn.cloudDir + "/" + chunkPath;
            try {
                byte[] raw = downloadBytes(url, null);
                if (raw == null) continue;

                byte[] data = decompressChunk(raw, chunk.windowSize);
                if (data == null) {
                    Log.w(TAG, "Decompress failed from " + cdn.baseUrl);
                    continue;
                }

                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(data);
                }
                return true;

            } catch (Exception e) {
                Log.w(TAG, "CDN " + cdn.baseUrl + " failed for chunk " + chunk.guidStr()
                        + ": " + e.getMessage());
            }
        }
        Log.e(TAG, "All CDNs failed for chunk " + chunk.guidStr());
        return false;
    }

    public static byte[] decompressChunk(byte[] raw, int expectedSize) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int magic = buf.getInt();
            if (magic != 0xB1FE3AA2) {
                Log.e(TAG, "Bad chunk magic: 0x" + Integer.toHexString(magic));
                return null;
            }
            buf.getInt(); // headerVersion
            int headerSize     = buf.getInt();
            int compressedSize = buf.getInt();
            buf.position(buf.position() + 16); // skip GUID
            buf.position(buf.position() + 8);  // skip hash
            int storedAs = buf.get() & 0xFF;

            if (headerSize < 0 || headerSize >= raw.length) {
                Log.e(TAG, "Bad chunk headerSize: " + headerSize);
                return null;
            }
            byte[] data = new byte[compressedSize];
            System.arraycopy(raw, headerSize, data, 0, compressedSize);

            if ((storedAs & 1) != 0) {
                Inflater inflater = new Inflater();
                inflater.setInput(data);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(
                        expectedSize > 0 ? expectedSize : 1048576);
                byte[] ibuf = new byte[65536];
                int n;
                while ((n = inflater.inflate(ibuf)) > 0) baos.write(ibuf, 0, n);
                inflater.end();
                byte[] result = baos.toByteArray();
                if (result.length == 0) {
                    Log.e(TAG, "Chunk inflate produced 0 bytes");
                    return null;
                }
                return result;
            }
            return data;

        } catch (Exception e) {
            Log.e(TAG, "decompressChunk error: " + e.getClass().getSimpleName());
            return null;
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    public static byte[] downloadBytes(String urlStr, String bearerToken) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            if (bearerToken != null && !bearerToken.isEmpty())
                conn.setRequestProperty("Authorization", bearerToken);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " for " + StoreLog.redactUrl(urlStr));
                return null;
            }
            int contentLength = conn.getContentLength();
            ByteArrayOutputStream out = contentLength > 0
                    ? new ByteArrayOutputStream(contentLength)
                    : new ByteArrayOutputStream();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[131072];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "downloadBytes error [" + StoreLog.redactUrl(urlStr) + "]: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Streams a chunk directly from CDN to outFile without holding the full
     * compressed + decompressed data in memory simultaneously.
     * Reads HTTP stream → parses chunk header → inflates/copies payload → writes to file.
     */
    private static boolean downloadChunkStreaming(ChunkInfo chunk, String chunkDir,
                                                   List<CdnUrl> cdnUrls, File outFile) {
        String chunkPath = chunk.getPath(chunkDir);
        // Write to a .part temp and only rename into the final cache name AFTER the SHA-1 check
        // passes. A cached chunk therefore exists in its final name ONLY if it fully downloaded and
        // verified — so a cancel/crash mid-write leaves an inert .part (never reused), and resume can
        // never assemble a truncated chunk. Atomic rename (same dir → same filesystem).
        File tmp = new File(outFile.getPath() + ".part");
        tmp.delete(); // clear any stale partial from a prior interrupted attempt
        for (CdnUrl cdn : cdnUrls) {
            String url = cdn.baseUrl + cdn.cloudDir + "/" + chunkPath;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", UA);
                if (conn.getResponseCode() != 200) { conn.disconnect(); continue; }

                // Verify the DECOMPRESSED chunk against the manifest's per-chunk SHA-1 when present
                // (binary manifests). null/all-zero → skip verification (JSON manifests). This makes
                // a corrupt/truncated chunk fail over to the next CDN instead of poisoning the file.
                MessageDigest sha = null;
                if (chunk.sha1 != null && chunk.sha1.length == 20) {
                    boolean allZero = true;
                    for (byte b : chunk.sha1) if (b != 0) { allZero = false; break; }
                    if (!allZero) { try { sha = MessageDigest.getInstance("SHA-1"); } catch (Exception ignored) {} }
                }

                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(tmp)) {

                    // Read first 41 bytes: magic(4)+headerVersion(4)+headerSize(4)+
                    //   compressedSize(4)+GUID(16)+hash(8)+storedAs(1)
                    byte[] hdrBuf = new byte[41];
                    readFully(in, hdrBuf);
                    ByteBuffer hdr = ByteBuffer.wrap(hdrBuf).order(ByteOrder.LITTLE_ENDIAN);
                    int magic = hdr.getInt();
                    if (magic != 0xB1FE3AA2) {
                        Log.w(TAG, "Bad chunk magic (streaming): 0x" + Integer.toHexString(magic));
                        continue;
                    }
                    hdr.getInt(); // headerVersion
                    int headerSize     = hdr.getInt();
                    int compressedSize = hdr.getInt();
                    hdr.position(hdr.position() + 24); // skip GUID(16) + hash(8)
                    int storedAs = hdr.get() & 0xFF;

                    // Skip any extra header bytes beyond the 41 we already read
                    if (headerSize > 41) skipFully(in, headerSize - 41);

                    // Stream payload → file
                    byte[] iobuf = new byte[131072];
                    if ((storedAs & 1) != 0) {
                        // zlib-compressed payload
                        Inflater inflater = new Inflater();
                        byte[] obuf = new byte[131072];
                        int remaining = compressedSize;
                        try {
                            while (remaining > 0 && !inflater.finished()) {
                                if (inflater.needsInput()) {
                                    int toRead = Math.min(iobuf.length, remaining);
                                    int n = in.read(iobuf, 0, toRead);
                                    if (n <= 0) break;
                                    remaining -= n;
                                    inflater.setInput(iobuf, 0, n);
                                }
                                int out = inflater.inflate(obuf);
                                if (out > 0) { fos.write(obuf, 0, out); if (sha != null) sha.update(obuf, 0, out); }
                            }
                            // drain any remaining output
                            int out;
                            while ((out = inflater.inflate(obuf)) > 0) { fos.write(obuf, 0, out); if (sha != null) sha.update(obuf, 0, out); }
                        } finally {
                            inflater.end();
                        }
                    } else {
                        // stored as-is
                        int remaining = compressedSize;
                        while (remaining > 0) {
                            int toRead = Math.min(iobuf.length, remaining);
                            int n = in.read(iobuf, 0, toRead);
                            if (n <= 0) break;
                            fos.write(iobuf, 0, n);
                            if (sha != null) sha.update(iobuf, 0, n);
                            remaining -= n;
                        }
                    }
                }

                if (sha != null && !MessageDigest.isEqual(sha.digest(), chunk.sha1)) {
                    Log.w(TAG, "Chunk SHA-1 mismatch (streaming) for " + chunk.guidStr() + ", trying next CDN");
                    tmp.delete();
                    conn.disconnect();
                    continue;
                }
                // Verified → publish atomically. If the rename can't happen, treat as a failure
                // rather than leaving a bad/again-partial file under the final name.
                if (!tmp.renameTo(outFile)) {
                    Log.w(TAG, "Chunk rename failed for " + chunk.guidStr() + ", trying next CDN");
                    tmp.delete();
                    conn.disconnect();
                    continue;
                }
                conn.disconnect();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "CDN " + cdn.baseUrl + " streaming failed for "
                        + chunk.guidStr() + ": " + e.getMessage());
                tmp.delete(); // never leave a partial under the .part name for the next CDN attempt
                if (conn != null) conn.disconnect();
            }
        }
        tmp.delete();
        Log.e(TAG, "All CDNs failed (streaming) for chunk " + chunk.guidStr());
        return false;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int n = in.read(buf, offset, buf.length - offset);
            if (n < 0) throw new IOException("Stream ended after " + offset + "/" + buf.length + " bytes");
            offset += n;
        }
    }

    private static void skipFully(InputStream in, int count) throws IOException {
        byte[] skip = new byte[Math.min(count, 4096)];
        int remaining = count;
        while (remaining > 0) {
            int n = in.read(skip, 0, Math.min(skip.length, remaining));
            if (n < 0) throw new IOException("Stream ended during skip");
            remaining -= n;
        }
    }

    // ── FString ───────────────────────────────────────────────────────────────

    public static String readFString(ByteBuffer buf) {
        int length = buf.getInt();
        if (length == 0) return "";
        if (length < 0) {
            int chars = (-length) - 1;
            byte[] bytes = new byte[chars * 2];
            buf.get(bytes);
            buf.getShort(); // null terminator
            return new String(bytes, StandardCharsets.UTF_16LE);
        } else {
            byte[] bytes = new byte[length - 1];
            buf.get(bytes);
            buf.get(); // null terminator
            return new String(bytes, StandardCharsets.US_ASCII);
        }
    }

    // ── Install size (no download) ────────────────────────────────────────────

    /**
     * Fetches the total uncompressed install size (bytes) by downloading and
     * parsing just the manifest. Does NOT download any game files.
     * Returns -1 on failure. Call from a background thread.
     */
    public static long fetchInstallSizeBytes(String accessToken, String namespace,
                                              String catalogItemId, String appName) {
        return fetchInstallSizeBytes(accessToken, namespace, catalogItemId, appName, null);
    }

    /**
     * Tag-aware install size. When {@code installTags} is non-null the returned size reflects only
     * the required(base) + selected-language files that will actually be downloaded (Feature #2), so
     * the detail screen shows the true footprint. {@code null} = full install size (legacy).
     */
    public static long fetchInstallSizeBytes(String accessToken, String namespace,
                                              String catalogItemId, String appName,
                                              List<String> installTags) {
        try {
            String manifestApiJson = EpicApiClient.getManifestApiJson(
                    accessToken, namespace, catalogItemId, appName);
            if (manifestApiJson == null) return -1;
            EpicManifest.ParsedManifest manifest =
                    EpicManifest.parseManifestApiJson(manifestApiJson, accessToken);
            if (manifest == null) return -1;
            long total = 0;
            if (installTags == null) {
                for (ChunkInfo chunk : manifest.uniqueChunks)
                    total += Math.max(chunk.windowSize, 0);
            } else {
                for (FileInfo f : resolveInstallFiles(manifest, installTags))
                    total += Math.max(f.fileSize(), 0);
            }
            return total > 0 ? total : -1;
        } catch (Exception e) {
            Log.w(TAG, "fetchInstallSizeBytes Epic: " + e.getClass().getSimpleName());
            return -1;
        }
    }

    // ── Install-tag filtering + verify/repair (ported from GameNative ManifestUtils.kt) ──

    /**
     * Files that make up the required/base install: those with no install tags. If NO file carries
     * a tag (manifest didn't ship tags, or all empty) every file is "required", so this returns all —
     * preserving the download-everything fallback. Port of ManifestUtils.getRequiredInstallFiles.
     */
    public static List<FileInfo> getRequiredInstallFiles(EpicManifest manifest) {
        List<FileInfo> required = new ArrayList<>();
        for (FileInfo f : manifest.files) if (f.installTags.isEmpty()) required.add(f);
        return required.isEmpty() ? new ArrayList<>(manifest.files) : required;
    }

    /**
     * The file set to download for "required + these tags". Port of
     * ManifestUtils.getFilesForSelectedInstallTags.
     *   - selectedTags == null → ALL files (no filtering / legacy behavior).
     *   - selectedTags empty    → required(base) files only.
     *   - otherwise             → base files + any file carrying at least one selected tag; if that
     *                             matched nothing (manifest uses tag names we don't recognize) fall
     *                             back to required-only so a runnable install is always produced.
     */
    public static List<FileInfo> resolveInstallFiles(EpicManifest manifest, List<String> selectedTags) {
        if (selectedTags == null) return new ArrayList<>(manifest.files);
        if (selectedTags.isEmpty()) return getRequiredInstallFiles(manifest);
        Set<String> want = new LinkedHashSet<>(selectedTags);
        List<FileInfo> out = new ArrayList<>();
        for (FileInfo f : manifest.files) {
            if (f.installTags.isEmpty()) { out.add(f); continue; }
            for (String t : f.installTags) { if (want.contains(t)) { out.add(f); break; } }
        }
        return out.isEmpty() ? getRequiredInstallFiles(manifest) : out;
    }

    /** Unique chunks referenced by the given file list. Port of getRequiredChunksForFileList. */
    public static List<ChunkInfo> uniqueChunksForFiles(EpicManifest manifest, List<FileInfo> files) {
        Map<String, ChunkInfo> byGuid = new LinkedHashMap<>();
        for (ChunkInfo c : manifest.uniqueChunks) byGuid.put(c.guidStr(), c);
        Set<String> seen = new LinkedHashSet<>();
        List<ChunkInfo> chunks = new ArrayList<>();
        for (FileInfo f : files) {
            for (ChunkPart p : f.parts) {
                String g = p.guidStr();
                if (seen.add(g)) {
                    ChunkInfo c = byGuid.get(g);
                    if (c != null) chunks.add(c);
                }
            }
        }
        return chunks;
    }

    /**
     * True when the file on disk matches the manifest's size and full-file SHA-1, so it can be
     * skipped on resume / left alone on repair. A null or all-zero manifest hash is treated as
     * unverifiable → the file is (re)downloaded. Streams the file in 64KB blocks (no full-file
     * buffer) so a 60GB install won't OOM. Port of GameNative fileExistsWithCorrectHash.
     */
    public static boolean fileExistsWithCorrectHash(File outputFile, long expectedSize, byte[] expectedHash) {
        if (outputFile == null || !outputFile.exists()) return false;
        if (outputFile.length() != expectedSize) return false;
        if (expectedHash == null || expectedHash.length != 20) return false;
        boolean allZero = true;
        for (byte b : expectedHash) if (b != 0) { allZero = false; break; }
        if (allZero) return false;
        try (FileInputStream fis = new FileInputStream(outputFile)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) != -1) digest.update(buf, 0, n);
            return MessageDigest.isEqual(digest.digest(), expectedHash);
        } catch (Exception e) {
            Log.w(TAG, "Could not verify existing file " + outputFile.getName() + ", will re-download");
            return false;
        }
    }

    /** Result of a verify pass: how many installed files were checked, OK, corrupt, or missing. */
    public static class VerifyResult {
        public int checked;
        public int ok;
        public int corrupt;   // present but wrong size/SHA-1
        public int missing;   // not on disk
        public int unverifiable; // manifest carried no SHA-1 for the file (JSON manifests)
        public boolean needsRepair() { return corrupt > 0 || missing > 0; }
    }

    /**
     * Feature #3 — verify installed files against the latest manifest. Re-hashes every selected file
     * on disk (streamed, off the main thread) and reports missing/corrupt counts. Does NOT modify
     * anything; the caller decides whether to repair (which is just {@link #install} again — it
     * re-downloads exactly the missing/corrupt files via the delta path). Returns null on failure
     * (fall back = assume no update / no repair, never crash).
     */
    public static VerifyResult verifyInstall(
            String manifestApiJson, String accessToken, String installDirPath,
            List<String> installTags, ProgressCallback progressCallback) {
        try {
            EpicManifest.ParsedManifest manifest =
                    EpicManifest.parseManifestApiJson(manifestApiJson, accessToken);
            if (manifest == null) return null;
            List<FileInfo> files = resolveInstallFiles(manifest, installTags);
            File installDir = new File(installDirPath);
            VerifyResult r = new VerifyResult();
            int i = 0;
            for (FileInfo f : files) {
                i++;
                progress(progressCallback, "Verifying (" + i + "/" + files.size() + ")",
                        (int) (i * 100L / Math.max(files.size(), 1)));
                File out = new File(installDir, f.filename.replace("\\", "/"));
                r.checked++;
                if (!out.exists()) { r.missing++; continue; }
                if (f.sha1 == null || f.sha1.length != 20) { r.unverifiable++; r.ok++; continue; }
                if (fileExistsWithCorrectHash(out, f.fileSize(), f.sha1)) r.ok++;
                else r.corrupt++;
            }
            return r;
        } catch (Exception e) {
            Log.w(TAG, "verifyInstall failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public static byte[] readFile(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int r = fis.read(data, off, data.length - off);
                if (r < 0) break;
                off += r;
            }
            return data;
        }
    }

    public static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private static String formatSpeed(long bps) {
        if (bps <= 0) return "";
        if (bps >= 1048576) return String.format("%.1f MB/s", bps / 1048576.0);
        return (bps / 1024) + " KB/s";
    }

    private static void progress(ProgressCallback cb, String msg, int pct) {
        if (cb != null) cb.onProgress(msg, pct);
        Log.i(TAG, "[" + pct + "%] " + msg);
    }
}
