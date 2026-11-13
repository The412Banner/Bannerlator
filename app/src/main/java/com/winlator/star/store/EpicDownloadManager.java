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
import java.util.Base64;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *   - Auth tokens (signed query) go on the manifest binary download only; chunk URLs are unsigned
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

    /**
     * Short reason for the last failed {@link #install} (e.g. "manifest refused by CDN (HTTP 403)"),
     * null when it did not fail or was cancelled. Never holds URLs or tokens. Static, so it is only
     * meaningful right after {@code install()} returns false (latest install wins).
     */
    public static volatile String lastFailureReason = null;

    /** {@code base}, plus ": reason" when the last install recorded one. */
    public static String withFailureReason(String base) {
        String why = lastFailureReason;
        return (why == null || why.isEmpty()) ? base : base + ": " + why;
    }

    // ── Public interface ──────────────────────────────────────────────────────

    public interface ProgressCallback {
        void onProgress(String message, int pct);

        /**
         * Byte-level progress of the chunk download: {@code bytesDone}/{@code bytesTotal} = chunk bytes
         * fetched / to fetch, {@code speedBps} = smoothed network speed (0 = unknown), {@code etaSeconds}
         * = time left (-1 = unknown). Called right before the matching {@link #onProgress}, at the same
         * cadence, so implementations should only store the values. Never called for phases without a
         * byte count (manifest, verifying files). Once every chunk is in, a single (total, total, 0, -1)
         * call marks the file-writing phase. Optional: the default ignores it.
         */
        default void onBytes(long bytesDone, long bytesTotal, long speedBps, long etaSeconds) {}
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
        public byte[] sha1;              // 20-byte SHA-1 of the DECOMPRESSED chunk (null when the manifest has none)
        public int    groupNum;          // uint8 subfolder (DECIMAL)
        public int    windowSize;        // uncompressed size
        public long   fileSize;          // compressed download size
        /** Feature level 22+ (ChunksV5): 4 uint32 secret GUID; null or all-zero = "plain" (unencrypted). */
        public int[]  secretGuid;

        public String guidStr() {
            return String.format("%08X%08X%08X%08X", guid[0], guid[1], guid[2], guid[3]);
        }

        /** Full chunk path: "ChunksV4/94/HASH_GUID.chunk" (see {@link #buildChunkPath} for ChunksV5). */
        public String getPath(String chunkDir) {
            return buildChunkPath(chunkDir, groupNum, hash, guid, secretGuid);
        }
    }

    // ── Chunk path / URL helpers (pure — unit-tested in EpicChunkUrlTest) ─────

    private static final String CHUNKS_V5 = "ChunksV5";

    /** Chunk directory for a manifest feature level, as Legendary's get_chunk_dir. */
    static String chunkDirForVersion(int version) {
        if (version >= 22) return CHUNKS_V5;
        if (version >= 15) return "ChunksV4";
        if (version >= 6)  return "ChunksV3";
        if (version >= 3)  return "ChunksV2";
        return "Chunks";
    }

    /** URL-safe base64 without padding of little-endian ints (Legendary: struct '<IIII' / '<Q'). */
    private static String b64(ByteBuffer b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b.array());
    }

    private static String b64(int[] ints) {
        ByteBuffer b = ByteBuffer.allocate(ints.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : ints) b.putInt(v);
        return b64(b);
    }

    /**
     * Relative chunk path under the CDN cloud dir (Legendary models/manifest.py ChunkInfo.path).
     * ChunksV4 and older: {@code dir/%02d(group)/%016X(hash)_%032X(guid).chunk}.
     * ChunksV5: {@code dir/<secret|plain>/%02d(group)/<hash>_<guid>.chunk}, each part URL-safe base64
     * of its little-endian bytes without padding; "plain" stands in for a zero/absent secret GUID.
     */
    static String buildChunkPath(String chunkDir, int groupNum, long hash, int[] guid, int[] secretGuid) {
        String sub = String.format("%02d", groupNum);  // DECIMAL — critical!
        if (CHUNKS_V5.equals(chunkDir)) {
            boolean plain = secretGuid == null
                    || (secretGuid[0] | secretGuid[1] | secretGuid[2] | secretGuid[3]) == 0;
            String hashB64 = b64(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(hash));
            return chunkDir + "/" + (plain ? "plain" : b64(secretGuid)) + "/" + sub + "/"
                    + hashB64 + "_" + b64(guid) + ".chunk";
        }
        String hashHex = String.format("%016X", hash);
        String guidHex = String.format("%08X%08X%08X%08X", guid[0], guid[1], guid[2], guid[3]);
        return chunkDir + "/" + sub + "/" + hashHex + "_" + guidHex + ".chunk";
    }

    private static final Pattern ENGINE_HTTP_STATUS = Pattern.compile("HTTP status \\((\\d{3})\\)");

    /** HTTP status out of a Rust engine error such as "... unexpected HTTP status (404)", else null. */
    static Integer httpStatusFromEngineError(String err) {
        if (err == null) return null;
        Matcher m = ENGINE_HTTP_STATUS.matcher(err);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** "<what> refused by CDN (HTTP 403, 404)" from the non-200 statuses, or null when there are none. */
    private static String refusedByCdn(String what, java.util.Collection<Integer> statuses) {
        Set<Integer> refused = new LinkedHashSet<>();
        for (int s : statuses) if (s > 0 && s != 200) refused.add(s);
        if (refused.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(what).append(" refused by CDN (HTTP ");
        String sep = "";
        for (int s : refused) { sb.append(sep).append(s); sep = ", "; }
        return sb.append(")").toString();
    }

    /**
     * Reason for a failed chunk phase. HTTP statuses (from the Java pool and/or the native engine's
     * error) win: "chunk download refused by CDN (HTTP 404)".
     */
    static String chunkFailureReason(java.util.Collection<Integer> statuses, String engineError,
                                     int failCount, int encryptedChunks) {
        if (encryptedChunks > 0) return "this game uses encrypted chunks, not supported yet";
        List<Integer> all = new ArrayList<>(statuses);
        Integer engineStatus = httpStatusFromEngineError(engineError);
        if (engineStatus != null) all.add(engineStatus);
        String refused = refusedByCdn("chunk download", all);
        if (refused != null) return refused;
        if (engineError != null) return "chunk download failed (native engine)";
        return failCount + " chunk(s) could not be downloaded from any CDN";
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
        /** 20-byte full-file SHA-1 (null when the manifest doesn't carry it). */
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
        /** Binary manifest stored_as bit 0x2: the file names (and launch info) are AES-GCM encrypted. */
        public boolean         encrypted    = false;
        public List<ChunkInfo> uniqueChunks = new ArrayList<>();
        public List<FileInfo>  files        = new ArrayList<>();

        // Parsed manifest also holds CDN URLs for download
        public List<CdnUrl> cdnUrls = new ArrayList<>();

        /** The manifest's prerequisite (usually UE4PrereqSetup_x64.exe: VC++ + DirectX), or "" when none.
         *  Kept at install for the Windows-components recommendations ({@code StoreRedists}). */
        public String prereqName = "";
        public String prereqPath = "";
        /** The manifest's app name (the Epic appName) and the launch command the Epic launcher passes
         *  to the game (e.g. "-PCStore=EGS"); "" when absent. Kept at install by {@link EpicLaunchArgs}. */
        public String appName = "";
        public String launchCommand = "";

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
        lastFailureReason = null;
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
                lastFailureReason = "no CDN URLs in manifest response";
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
            List<Integer> manifestStatuses = new ArrayList<>();
            byte[] manifestBytes = downloadManifest(manifestApiJson, cdnUrls, manifestStatuses);
            if (manifestBytes == null) {
                lastFailureReason = manifestFailureReason(manifestStatuses);
                dbg.append("ERROR: Manifest binary download failed: ").append(lastFailureReason).append("\n");
                writeDebug(ctx, dbg);
                Log.e(TAG, "Manifest binary download failed: " + lastFailureReason);
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
                lastFailureReason = "manifest could not be parsed";
                return false;
            }
            manifest.cdnUrls = cdnUrls;
            dbg.append("chunkDir=").append(manifest.chunkDir)
               .append(" chunks=").append(manifest.uniqueChunks.size())
               .append(" files=").append(manifest.files.size()).append("\n");
            Log.i(TAG, "Manifest: chunkDir=" + manifest.chunkDir
                    + " chunks=" + manifest.uniqueChunks.size()
                    + " files=" + manifest.files.size());
            if (manifest.encrypted) {
                // The file names sit in an AES-GCM block (Legendary Manifest.decrypt). Not decoded
                // here, and installing with the names missing would be worse than stopping.
                dbg.append("ERROR: manifest is encrypted - not supported yet\n");
                writeDebug(ctx, dbg);
                Log.e(TAG, "Manifest is encrypted (stored_as & 2), not supported yet");
                lastFailureReason = "this game's manifest is encrypted, not supported yet";
                return false;
            }
            final Set<Integer> chunkStatuses = java.util.Collections.synchronizedSet(new LinkedHashSet<Integer>());
            final AtomicInteger encryptedChunks = new AtomicInteger(0);

            File installDir  = new File(installDirPath);
            installDir.mkdirs();
            // Its prerequisite, for the Windows-components recommendations (kept by install folder).
            com.winlator.star.components.StoreRedists.rememberEpicPrereq(ctx, installDir, manifest.prereqName, manifest.prereqPath);
            // Its launch command, added to the game's arguments at launch.
            EpicLaunchArgs.rememberManifestLaunchCommand(ctx, manifest.appName, manifest.launchCommand);
            File chunkCacheDir = new File(installDir, ".chunks");
            chunkCacheDir.mkdirs();

            // Feature #2 — selective install tags: narrow the manifest file list to
            // required(base) + the container-language files before downloading anything.
            // installTags == null → no filtering (legacy: every file). Degrades gracefully:
            // if the manifest carries no tags, resolveInstallFiles returns all files.
            List<FileInfo> selectedFiles = resolveInstallFiles(manifest, installTags);
            for (FileInfo f : selectedFiles) {
                if (!isSafeRelativePath(f.filename)) {
                    dbg.append("ERROR: unsafe file path in manifest: ").append(f.filename).append("\n");
                    writeDebug(ctx, dbg);
                    Log.e(TAG, "Manifest file path leaves the install folder: " + f.filename);
                    lastFailureReason = "this game's manifest has a file path outside its folder";
                    return false;
                }
            }
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
            // Speed/ETA reported through ProgressCallback.onBytes: sliding window + EMA over the bytes
            // actually fetched (netBytes excludes chunks already in the resume cache, which would
            // otherwise show up as a burst of speed).
            final EpicSpeedMeter speedMeter    = new EpicSpeedMeter();
            final AtomicLong netBytes          = new AtomicLong(0);
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
            String engineError = null;
            boolean tryRust = com.winlator.star.store.blsteam.BlStoreEngineFlag.isEpicEnabled(ctx);
            if (tryRust && CHUNKS_V5.equals(manifest.chunkDir)) {
                // The native engine only knows the ChunksV4-and-older path layout, so ChunksV5 manifests
                // go through the Java pool.
                tryRust = false;
                dbg.append("rust engine skipped (ChunksV5 manifest) -> Java chunk pool\n");
                Log.i(TAG, "Rust Epic engine skipped: ChunksV5 manifest");
            }
            if (tryRust) {
                RustPoolResult rr = runRustChunkPool(ctx, dbg, manifestBytes, manifest, pendingFiles,
                        neededChunks, fTotalBytes, installDirPath, cdnUrls, cancelFlag, progressCallback,
                        completedBytes, completedCount, lastSpeedMs, lastSpeedBytes, currentSpeedBps,
                        speedMeter);
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
                        Integer rustStatus = httpStatusFromEngineError(rr.error);
                        if (rustStatus != null && (rustStatus == 401 || rustStatus == 403 || rustStatus == 404)
                                && completedCount.get() == 0 && !neededChunks.isEmpty()) {
                            // The CDN refused the engine's URLs before a single chunk landed: let the
                            // Java pool have a go instead of failing the install.
                            dbg.append("rust engine refused by CDN (HTTP ").append(rustStatus)
                               .append(") before any chunk -> Java chunk pool\n");
                            Log.w(TAG, "Rust Epic engine refused by CDN (HTTP " + rustStatus + "), using Java chunk pool");
                            useJavaPool = true;
                        } else {
                            // Surfaces through the shared "N chunks failed" exit below, like a Java
                            // chunk that exhausted every CDN.
                            chunkLog.add("FAIL engine=rust " + rr.error);
                            engineError = rr.error;
                            failCount.incrementAndGet();
                        }
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
                        boolean fetched = false;
                        if (!cachedFile.exists()) {
                            if (!downloadChunkStreaming(fc, manifest.chunkDir, cdnUrls, cachedFile,
                                    chunkStatuses, encryptedChunks)) {
                                Log.e(TAG, "Chunk download failed: " + fc.guidStr());
                                chunkLog.add("FAIL chunk=" + fc.guidStr());
                                failCount.incrementAndGet();
                                return;
                            }
                            fetched = true;
                        }
                        long net  = fetched ? netBytes.addAndGet(Math.max(fc.fileSize, 1)) : netBytes.get();
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
                        downloadProgress(progressCallback, speedMeter, net, done, fTotalBytes,
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
                    lastFailureReason = "download interrupted";
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
                lastFailureReason = chunkFailureReason(new ArrayList<>(chunkStatuses), engineError,
                        failCount.get(), encryptedChunks.get());
                dbg.append("ERROR: ").append(failCount.get()).append(" chunks failed: ")
                   .append(lastFailureReason).append("\n");
                writeDebug(ctx, dbg);
                Log.e(TAG, failCount.get() + " chunks failed to download: " + lastFailureReason);
                return false;
            }
            dbg.append("chunksOK=").append(completedCount.get()).append("\n");
            // All bytes are in: the rest is writing files out (no network speed any more).
            if (progressCallback != null) progressCallback.onBytes(fTotalBytes, fTotalBytes, 0L, -1L);

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
                            lastFailureReason = "missing downloaded chunk while writing " + displayName;
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
            lastFailureReason = "unexpected error (" + e.getClass().getSimpleName() + ")";
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
            AtomicLong currentSpeedBps,
            EpicSpeedMeter speedMeter) {
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
            // The engine credits the chunks already in the resume cache up front, in one burst (driver.rs: same rule, a cached
            // chunk's size or 1). Those bytes were not fetched, so the speed meter must not see them: it gets the bytes past this.
            final File rustChunkCache = new File(installDirPath, ".chunks");
            long cachedBytes = 0;
            for (ChunkInfo c : neededChunks) {
                if (new File(rustChunkCache, c.guidStr()).exists()) cachedBytes += Math.max(c.fileSize, 1);
            }
            final long preBytes = cachedBytes;
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
                    // Bytes actually fetched: the cached chunks credited at the start are not (as netBytes in the Java pool).
                    long net  = Math.max(0L, bytesDone - preBytes);
                    int  cnt  = chunksDone;
                    int  pct  = (int)(done * 80L / fTotalBytes);

                    long nowMs     = System.currentTimeMillis();
                    long prevMs    = lastSpeedMs.get();
                    long timeDelta = nowMs - prevMs;
                    if (timeDelta >= 500 && lastSpeedMs.compareAndSet(prevMs, nowMs)) {
                        long prevB  = lastSpeedBytes.getAndSet(net);
                        long bDelta = net - prevB;
                        if (timeDelta > 0) currentSpeedBps.set(bDelta * 1000L / timeDelta);
                    }

                    String mb    = String.format("%.1f / %.1f MB", done / 1048576.0, fTotalBytes / 1048576.0);
                    String speed = formatSpeed(currentSpeedBps.get());
                    downloadProgress(progressCallback, speedMeter, net, done, fTotalBytes,
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
     *
     * The wrapper is read with org.json: EpicApiClient re-serializes the API response with org.json,
     * which escapes every "/" as "\/", so slicing the raw text leaves literal backslashes in the CDN
     * URLs and in the signed query values (the manifest request is then refused).
     */
    public static List<CdnUrl> parseCdnUrls(String json) {
        List<CdnUrl> result = new ArrayList<>();
        try {
            JSONArray manifests = manifestsArray(json);
            if (manifests == null) return result;

            for (int i = 0; i < manifests.length(); i++) {
                JSONObject m = manifests.optJSONObject(i);
                if (m == null) continue;
                String uri = m.optString("uri", "");

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

                String authParams = buildAuthParams(m.optJSONArray("queryParams"));
                result.add(new CdnUrl(baseUrl, cloudDir, authParams));
            }
        } catch (Exception e) {
            Log.e(TAG, "parseCdnUrls error: " + e.getClass().getSimpleName());
        }
        return result;
    }

    /** The wrapper's top-level "manifests" array, or null when absent. Throws on malformed JSON. */
    private static JSONArray manifestsArray(String json) throws org.json.JSONException {
        if (json == null) return null;
        return new JSONObject(json).optJSONArray("manifests");
    }

    /** "?name=value&name2=value2" from a queryParams array of {name,value}, or "" when empty. */
    private static String buildAuthParams(JSONArray queryParams) {
        if (queryParams == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < queryParams.length(); i++) {
            JSONObject p = queryParams.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name", "");
            if (name.isEmpty()) continue;
            sb.append(sb.length() == 0 ? "?" : "&").append(name).append("=").append(p.optString("value", ""));
        }
        return sb.toString();
    }

    // ── Manifest download ─────────────────────────────────────────────────────

    /**
     * Download the manifest binary, trying each CDN in order.
     * Auth tokens (queryParams) are appended to the manifest URL only; chunk URLs are unsigned.
     */
    public static byte[] downloadManifest(String json, List<CdnUrl> cdnUrls) {
        return downloadManifest(json, cdnUrls, null);
    }

    /**
     * As above; when {@code statusOut} is non-null it receives the HTTP status of every CDN attempt
     * (-1 = no response), for the failure reason.
     */
    public static byte[] downloadManifest(String json, List<CdnUrl> cdnUrls, List<Integer> statusOut) {
        try {
            JSONArray manifests = manifestsArray(json);
            if (manifests == null) return null;
            String firstUri = null;
            for (int i = 0; i < manifests.length(); i++) {
                JSONObject m = manifests.optJSONObject(i);
                if (m == null) continue;
                String u = m.optString("uri", "");
                if (!u.isEmpty()) { firstUri = u; break; }
            }
            if (firstUri == null) return null;

            String uriPath = firstUri.contains("?") ? firstUri.substring(0, firstUri.indexOf("?")) : firstUri;
            int lastSlash = uriPath.lastIndexOf("/");
            if (lastSlash < 0) return null;
            String manifestFilename = uriPath.substring(lastSlash + 1);
            Log.i(TAG, "Manifest filename: " + manifestFilename);

            for (CdnUrl cdn : cdnUrls) {
                String url = cdn.baseUrl + cdn.cloudDir + "/" + manifestFilename + cdn.authParams;
                Log.i(TAG, "Trying manifest CDN: " + cdn.baseUrl);
                HttpResult res = fetch(url, null);
                if (statusOut != null) statusOut.add(res.status);
                byte[] bytes = res.body;
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

    /** Reason for a failed manifest download, from the per-CDN HTTP statuses (-1 = no response). */
    private static String manifestFailureReason(List<Integer> statuses) {
        String refused = refusedByCdn("manifest", statuses);
        if (refused != null) return refused;
        if (statuses.isEmpty()) return "manifest URL could not be read";
        return statuses.contains(200) ? "manifest response was empty or truncated"
                : "manifest download failed (network error)";
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

            // ManifestMeta section: only its prerequisite is read (best effort), then skipped whole.
            int metaStart = body.position();
            int metaSize = body.getInt();
            String[] prereq = readMeta(body);
            // Like Legendary, the chunk directory follows the META feature level (the uint32 after
            // meta_size and data_version); the header version is only the fallback.
            int featureLevel = body.getInt(metaStart + 5);
            int chunkVersion = featureLevel > 0 ? featureLevel : version;
            String chunkDir = chunkDirForVersion(chunkVersion);
            body.position(metaStart + metaSize);

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
            // ChunksV5 (feature level 22+) adds the secret GUIDs here (then window_size_compressed and
            // the GCM tags, which are skipped with the rest of the section below). The secret GUID is
            // part of the chunk's path.
            if (chunkVersion >= 22 && body.position() + (long) chunkCount * 16 <= cdlStart + cdlSize) {
                for (ChunkInfo c : chunks) {
                    c.secretGuid = new int[] { body.getInt(), body.getInt(), body.getInt(), body.getInt() };
                }
            }

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
            result.encrypted    = (storedAs & 2) != 0;
            result.uniqueChunks = new ArrayList<>(seenMap.values());
            result.files        = files;
            result.prereqName   = prereq[0];
            result.prereqPath   = prereq[1];
            result.appName      = prereq[2];
            result.launchCommand = prereq[3];
            return result;

        } catch (Exception e) {
            Log.e(TAG, "parseManifest error", e);
            return null;
        }
    }

    // ── JSON manifest (older games) ───────────────────────────────────────────

    /**
     * Whether a manifest file name stays inside the install folder: not empty, not absolute (no leading
     * slash or drive letter) and no ".." part.
     */
    static boolean isSafeRelativePath(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String p = name.replace('\\', '/');
        if (p.startsWith("/") || (p.length() >= 2 && p.charAt(1) == ':')) return false;
        for (String part : p.split("/")) {
            if (part.equals("..")) return false;
        }
        return true;
    }

    /**
     * A number in the JSON manifests' "blob" form: each byte as three decimal digits, least significant
     * byte first (Legendary blob_to_num; "013000000000" = 13). Groups past eight bytes are ignored and a
     * group that is not a number counts as 0.
     */
    static long blobToLong(String blob) {
        if (blob == null) return 0;
        long num = 0;
        int shift = 0;
        for (int i = 0; i < blob.length() && shift < 64; i += 3, shift += 8) {
            int v;
            try { v = Integer.parseInt(blob.substring(i, Math.min(i + 3, blob.length()))); }
            catch (NumberFormatException e) { v = 0; }
            num |= ((long) (v & 0xFF)) << shift;
        }
        return num;
    }

    /** The first {@code size} bytes of a "blob" string (three decimal digits each), or null when it is shorter or not numeric. */
    static byte[] blobToBytes(String blob, int size) {
        if (blob == null || blob.length() < size * 3) return null;
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            try { out[i] = (byte) Integer.parseInt(blob.substring(i * 3, i * 3 + 3)); }
            catch (NumberFormatException e) { return null; }
        }
        return out;
    }

    /** A 40-digit hex SHA-1 as 20 bytes, or null. */
    static byte[] sha1FromHex(String hex) {
        if (hex == null || hex.length() != 40) return null;
        byte[] out = new byte[20];
        for (int i = 0; i < 20; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16), lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * Older games' JSON manifests. Numbers (version, chunk hash, group, sizes, offsets) and the file
     * SHA-1 are "blob" strings, chunk SHA-1s are hex (Legendary's json_manifest.py, GameNative's
     * JsonManifestParser).
     */
    static EpicManifest.ParsedManifest parseJsonManifest(byte[] bytes) {
        try {
            String jsonStr = new String(bytes, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(jsonStr);

            // Legendary's default when the field is missing: "013000000000" (version 13).
            int manifestVersion = (int) blobToLong(root.optString("ManifestFileVersion", "013000000000"));
            String chunkDir = chunkDirForVersion(manifestVersion);

            JSONObject chunkHashList     = root.optJSONObject("ChunkHashList");
            JSONObject dataGroupList     = root.optJSONObject("DataGroupList");
            JSONObject chunkFilesizeList = root.optJSONObject("ChunkFilesizeList");
            JSONObject chunkShaList      = root.optJSONObject("ChunkShaList");

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

                c.hash = blobToLong(hashHex);
                if (dataGroupList != null) c.groupNum = (int) blobToLong(dataGroupList.optString(guidHex, "0"));
                if (chunkFilesizeList != null) c.fileSize = blobToLong(chunkFilesizeList.optString(guidHex, "0"));
                if (chunkShaList != null) c.sha1 = sha1FromHex(chunkShaList.optString(guidHex, ""));
                // JSON manifests carry no window size; Legendary uses 1 MiB.
                c.windowSize = 1048576;
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

                fi.sha1 = blobToBytes(fileObj.optString("FileHash", ""), 20);

                // Optional per-file install tags (older JSON manifests).
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
                        part.offset = (int) blobToLong(partObj.optString("Offset", "0"));
                        part.size = (int) blobToLong(partObj.optString("Size", "0"));
                        fi.parts.add(part);
                    }
                }
                files.add(fi);
            }

            EpicManifest.ParsedManifest result = new EpicManifest.ParsedManifest();
            result.chunkDir     = chunkDir;
            result.uniqueChunks = new ArrayList<>(chunkMap.values());
            result.files        = files;
            result.prereqName   = root.optString("PrereqName", "");
            result.prereqPath   = root.optString("PrereqPath", "");
            result.appName      = root.optString("AppNameString", "");
            result.launchCommand = root.optString("LaunchCommand", "");
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

    /** Outcome of one GET: HTTP status (-1 = no response) and the body (null unless 200 and fully read). */
    private static final class HttpResult {
        int    status = -1;
        byte[] body   = null;
    }

    public static byte[] downloadBytes(String urlStr, String bearerToken) {
        return fetch(urlStr, bearerToken).body;
    }

    private static HttpResult fetch(String urlStr, String bearerToken) {
        HttpResult res = new HttpResult();
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
            res.status = code;
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " for " + StoreLog.redactUrl(urlStr));
                return res;
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
            res.body = out.toByteArray();
            return res;
        } catch (Exception e) {
            Log.w(TAG, "downloadBytes error [" + StoreLog.redactUrl(urlStr) + "]: " + e.getClass().getSimpleName());
            return res;
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
                                                   List<CdnUrl> cdnUrls, File outFile,
                                                   Set<Integer> refusedStatuses,
                                                   AtomicInteger encryptedChunks) {
        String chunkPath = chunk.getPath(chunkDir);
        // Write to a .part temp and only rename into the final cache name AFTER the SHA-1 check
        // passes. A cached chunk therefore exists in its final name ONLY if it fully downloaded and
        // verified — so a cancel/crash mid-write leaves an inert .part (never reused), and resume can
        // never assemble a truncated chunk. Atomic rename (same dir → same filesystem).
        File tmp = new File(outFile.getPath() + ".part");
        tmp.delete(); // clear any stale partial from a prior interrupted attempt
        for (CdnUrl cdn : cdnUrls) {
            // Chunk URLs are unsigned (like Legendary); only the manifest request carries the query.
            String url = cdn.baseUrl + cdn.cloudDir + "/" + chunkPath;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", UA);
                int code = conn.getResponseCode();
                if (code != 200) {
                    refusedStatuses.add(code);  // for the failure reason
                    conn.disconnect();
                    continue;
                }

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

                    if ((storedAs & 2) != 0) {
                        // AES-GCM encrypted chunk (ChunksV5 with a secret GUID, see Legendary's
                        // Chunk.data). Not supported yet: say so instead of writing ciphertext.
                        encryptedChunks.incrementAndGet();
                        Log.w(TAG, "Encrypted chunk, not supported yet: " + chunk.guidStr());
                        tmp.delete();
                        conn.disconnect();
                        return false;
                    }

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

    /**
     * {prerequisite name, prerequisite path, app name, launch command} from a binary manifest's
     * ManifestMeta, read from just after its size field: data version, feature level, is-file-data,
     * app id, app name, build version, launch exe, launch command, prerequisite ids, then the
     * prerequisite's name and path. A field that can't be read is ""; the caller re-positions past
     * the section either way.
     */
    private static String[] readMeta(ByteBuffer body) {
        String[] out = {"", "", "", ""};
        try {
            body.get();      // data version
            body.getInt();   // feature level
            body.get();      // is file data
            body.getInt();   // app id
            String appName = readFString(body);
            if (appName != null) out[2] = appName;
            readFString(body); // build version
            readFString(body); // launch exe
            String launchCommand = readFString(body);
            if (launchCommand != null) out[3] = launchCommand;
            int ids = body.getInt();
            if (ids < 0 || ids > 64) return out;
            for (int i = 0; i < ids; i++) readFString(body);
            String name = readFString(body);
            String path = readFString(body);
            out[0] = name != null ? name : "";
            out[1] = path != null ? path : "";
        } catch (Throwable t) {
            // keep what was read
        }
        return out;
    }

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
            android.content.Context ctx, String manifestApiJson, String accessToken, String installDirPath,
            List<String> installTags, ProgressCallback progressCallback) {
        try {
            EpicManifest.ParsedManifest manifest =
                    EpicManifest.parseManifestApiJson(manifestApiJson, accessToken);
            if (manifest == null) return null;
            // Games installed before the launch command was kept get it here.
            EpicLaunchArgs.rememberManifestLaunchCommand(ctx, manifest.appName, manifest.launchCommand);
            List<FileInfo> files = resolveInstallFiles(manifest, installTags);
            File installDir = new File(installDirPath);
            VerifyResult r = new VerifyResult();
            int i = 0;
            for (FileInfo f : files) {
                i++;
                progress(progressCallback, "Verifying (" + i + "/" + files.size() + ")",
                        (int) (i * 100L / Math.max(files.size(), 1)));
                if (!isSafeRelativePath(f.filename)) { r.checked++; r.corrupt++; continue; }
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

    /**
     * Chunk-download progress: the byte pair, smoothed speed and ETA via {@code onBytes}, then the
     * usual message / pct via {@code onProgress} (same cadence as before; onBytes only hands over numbers).
     * {@code netBytes} = bytes actually fetched so far (the speed basis); {@code done}/{@code total} =
     * chunk bytes completed / to complete (the counter basis).
     */
    private static void downloadProgress(ProgressCallback cb, EpicSpeedMeter meter, long netBytes,
                                         long done, long total, String msg, int pct) {
        if (cb != null) {
            long bps = meter.addNow(netBytes);
            cb.onBytes(done, total, bps, meter.etaSeconds(done, total));
        }
        progress(cb, msg, pct);
    }

    private static void progress(ProgressCallback cb, String msg, int pct) {
        if (cb != null) cb.onProgress(msg, pct);
        Log.i(TAG, "[" + pct + "%] " + msg);
    }
}
