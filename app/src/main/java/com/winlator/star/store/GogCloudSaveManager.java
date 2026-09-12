package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GOG Cloud Save upload/download manager.
 *
 * API base: https://cloudstorage.gog.com/v1/{userId}/{clientId}
 *
 * List files:   GET  /v1/{userId}/{clientId}
 * Download:     GET  /v1/{userId}/{clientId}/{filename}
 * Upload:       PUT  /v1/{userId}/{clientId}/{filename}
 *
 * Uses the regular GOG access_token (Bearer) and gameId as clientId.
 * Token is refreshed automatically if needed before calls.
 */
public final class GogCloudSaveManager {

    private static final String TAG = "BH_GOG_CLOUD";
    private static final String BASE = "https://cloudstorage.gog.com/v1/";
    private static final int TIMEOUT = 30_000;
    // Save sync is round-trip-bound (many small files, one HEAD+PUT/GET each), so it runs the per-file
    // work across a small pool instead of sequentially — a ~20-file set that took >15s one-at-a-time
    // finishes in a few seconds. Kept modest so a big save set can't open dozens of sockets at once.
    private static final int SYNC_PARALLELISM = 6;

    public interface Callback {
        void onStatus(String message);
        void onDone(String summary);
        void onError(String message);
    }

    /** Scan local folder and upload files newer than cloud versions. */
    public static void uploadSaves(Context ctx, String gameId, File localFolder, Callback cb) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = ctx.getSharedPreferences("bh_gog_prefs", 0);
                String galaxyToken = getValidToken(ctx, prefs);
                if (galaxyToken == null) { cb.onError("Not logged in to GOG"); return; }
                String userId = prefs.getString("user_id", null);
                if (userId == null) { cb.onError("GOG user ID not found — please sign in again"); return; }
                String clientId = GogDownloadManager.getOrFetchClientId(ctx, gameId, galaxyToken);
                String token = getGameScopedToken(ctx, gameId, clientId, prefs);
                if (token == null) token = galaxyToken; // fallback
                debug(ctx, "GOG upload — gameId=" + gameId + " clientId=" + clientId + " scopedToken=" + (token.equals(galaxyToken) ? "fallback" : "ok"));

                cb.onStatus("Fetching cloud file list…");
                List<CloudFile> cloudFiles = listCloudFiles(ctx, userId, clientId, token);

                // Recursive walk: collect every file under localFolder (incl. subfolders
                // like ELDERBORN's inputConfig/). The cloud key is the path RELATIVE to
                // localFolder, with forward slashes — verified on-device that GOG accepts
                // '/' in object keys (it normalises %2F<->/, listing returns literal '/').
                List<File> localFiles = new ArrayList<>();
                collectFiles(localFolder, localFiles);
                if (localFiles.isEmpty()) {
                    cb.onDone("No local files to upload");
                    return;
                }

                // Parallel per-file upload (see SYNC_PARALLELISM). Each file is independent: newest-wins
                // HEAD + PUT if the local copy is newer. Counters are atomic; the first failure is
                // captured and short-circuits the rest. Progress is reported as a friendly count, not a
                // per-file path (some save/input filenames are very long).
                final String fUserId = userId, fClientId = clientId, fToken = token;
                final List<CloudFile> fCloud = cloudFiles;
                final int total = localFiles.size();
                final AtomicInteger uploaded = new AtomicInteger();
                final AtomicInteger skipped  = new AtomicInteger();
                final AtomicInteger completed = new AtomicInteger();
                final AtomicReference<String> firstError = new AtomicReference<>();

                ExecutorService pool = Executors.newFixedThreadPool(Math.min(SYNC_PARALLELISM, total));
                for (File local : localFiles) {
                    pool.execute(() -> {
                        try {
                            if (firstError.get() != null) return;   // a sibling already failed — bail
                            String key = relKey(localFolder, local);
                            long localModMs = local.lastModified();
                            CloudFile cloud = findCloud(fCloud, key);
                            // Real newest-wins: HEAD the cloud copy for its Last-Modified. Unknown
                            // (no cloud copy, or HEAD failed) => -1 => push (safe fallback).
                            long cloudModMs = (cloud == null) ? -1L
                                    : resolveCloudMtime(ctx, fUserId, fClientId, fToken, cloud);
                            if (cloudModMs >= 0 && cloudModMs >= localModMs) {
                                skipped.incrementAndGet();
                                debug(ctx, "upload skip (cloud newer/equal): " + key);
                                return;
                            }
                            // Local is newer by TIME, but the game may have just re-touched the file
                            // without changing its bytes (ELDERBORN rewrites its input-config files every
                            // launch). Compare CONTENT: if the local MD5 matches the cloud's ETag, skip the
                            // upload and realign the local mtime to the cloud's so the cheap time check
                            // skips it next run (no MD5 needed) — an unchanged-content exit uploads nothing.
                            if (cloud != null && cloud.etag != null) {
                                String localMd5 = md5(local);
                                if (localMd5 != null && localMd5.equalsIgnoreCase(cloud.etag)) {
                                    if (cloud.lastModifiedMs > 0) local.setLastModified(cloud.lastModifiedMs);
                                    skipped.incrementAndGet();
                                    debug(ctx, "upload skip (content identical): " + key);
                                    return;
                                }
                            }
                            byte[] data = readFile(local);
                            if (data == null) { firstError.compareAndSet(null, "Failed to read: " + key); return; }
                            if (!putFile(fUserId, fClientId, fToken, key, data)) {
                                firstError.compareAndSet(null, "Upload failed for: " + key); return;
                            }
                            // Sync the local file's mtime to the cloud's freshly-assigned Last-Modified so
                            // the next launch's newest-wins treats them as equal and SKIPS (rather than
                            // re-downloading this exact save, since the server stamps the object a few
                            // seconds after the local file was written). One cheap HEAD, parallelized.
                            long cm = headModifiedMs(ctx, fUserId, fClientId, fToken, key, null);
                            if (cm > 0) local.setLastModified(cm);
                            uploaded.incrementAndGet();
                        } catch (Exception e) {
                            firstError.compareAndSet(null, e.getMessage());
                        } finally {
                            // Neutral verb: this fires per file completed whether it was uploaded OR
                            // skipped (newest-wins), so it must not claim every file is "uploading".
                            cb.onStatus("Backing up game saves… (" + completed.incrementAndGet() + "/" + total + ")");
                        }
                    });
                }
                pool.shutdown();
                pool.awaitTermination(TIMEOUT / 1000 + 30, TimeUnit.SECONDS);

                if (firstError.get() != null) { cb.onError("Upload error: " + firstError.get()); return; }
                int up = uploaded.get(), sk = skipped.get();
                if (up == 0 && sk > 0) {
                    cb.onDone("Already up to date (" + sk + " file" + (sk == 1 ? "" : "s") + ")");
                } else if (up > 0) {
                    cb.onDone("Uploaded " + up + " file" + (up == 1 ? "" : "s")
                            + (sk > 0 ? ", skipped " + sk : ""));
                } else {
                    cb.onDone("No files to upload");
                }

            } catch (Exception e) {
                Log.e(TAG, "uploadSaves failed", e);
                debug(ctx, "uploadSaves exception: " + e.getClass().getSimpleName());
                if ("CLOUD_SAVES_NOT_SUPPORTED".equals(e.getMessage()))
                    cb.onError("This game does not support GOG cloud saves");
                else
                    cb.onError("Upload error: " + e.getMessage());
            }
        }, "gog-cloud-upload-" + gameId).start();
    }

    /** Download all cloud save files to local folder, overwriting local copies. */
    public static void downloadSaves(Context ctx, String gameId, File localFolder, Callback cb) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = ctx.getSharedPreferences("bh_gog_prefs", 0);
                String galaxyToken = getValidToken(ctx, prefs);
                if (galaxyToken == null) { cb.onError("Not logged in to GOG"); return; }
                String userId = prefs.getString("user_id", null);
                if (userId == null) { cb.onError("GOG user ID not found — please sign in again"); return; }
                String clientId = GogDownloadManager.getOrFetchClientId(ctx, gameId, galaxyToken);
                String token = getGameScopedToken(ctx, gameId, clientId, prefs);
                if (token == null) token = galaxyToken; // fallback
                debug(ctx, "GOG download — gameId=" + gameId + " clientId=" + clientId + " scopedToken=" + (token.equals(galaxyToken) ? "fallback" : "ok"));

                cb.onStatus("Fetching cloud file list…");
                List<CloudFile> cloudFiles = listCloudFiles(ctx, userId, clientId, token);

                if (cloudFiles.isEmpty()) {
                    cb.onDone("No cloud saves found");
                    return;
                }

                if (!localFolder.exists()) localFolder.mkdirs();

                // Parallel per-file download (see SYNC_PARALLELISM), mirroring the upload path. Each
                // cloud file is independent: symmetric newest-wins skips a newer-or-equal local copy,
                // else pull. Progress reported as a friendly count, not a per-file path.
                final String fUserId = userId, fClientId = clientId, fToken = token;
                final int total = cloudFiles.size();
                final AtomicInteger downloaded = new AtomicInteger();
                final AtomicInteger skipped    = new AtomicInteger();
                final AtomicInteger completed  = new AtomicInteger();
                final AtomicReference<String> firstError = new AtomicReference<>();

                ExecutorService pool = Executors.newFixedThreadPool(Math.min(SYNC_PARALLELISM, total));
                for (CloudFile cf : cloudFiles) {
                    pool.execute(() -> {
                        try {
                            if (firstError.get() != null) return;   // a sibling already failed — bail
                            // cf.name may be a subpath ("inputConfig/foo.cfg"); File resolves it
                            // against localFolder as nested dirs.
                            File dest = new File(localFolder, cf.name);
                            // Symmetric newest-wins: don't clobber a newer-or-equal local save with an
                            // older cloud one. Unknown cloud mtime (HEAD failed) => -1 => pull.
                            if (dest.isFile()) {
                                long cloudModMs = resolveCloudMtime(ctx, fUserId, fClientId, fToken, cf);
                                long localModMs = dest.lastModified();
                                if (cloudModMs >= 0 && localModMs >= cloudModMs) {
                                    skipped.incrementAndGet();
                                    debug(ctx, "download skip (local newer/equal): " + cf.name);
                                    return;
                                }
                                // Cloud is newer by TIME, but if the bytes are identical (local MD5 == cloud
                                // ETag) skip the pull and realign local mtime to the cloud's.
                                if (cf.etag != null) {
                                    String localMd5 = md5(dest);
                                    if (localMd5 != null && localMd5.equalsIgnoreCase(cf.etag)) {
                                        if (cloudModMs > 0) dest.setLastModified(cloudModMs);
                                        skipped.incrementAndGet();
                                        debug(ctx, "download skip (content identical): " + cf.name);
                                        return;
                                    }
                                }
                            }
                            long[] cloudMt = new long[]{-1L};
                            byte[] data = getFile(fUserId, fClientId, fToken, cf.name, cloudMt);
                            if (data == null) { firstError.compareAndSet(null, "Download failed for: " + cf.name); return; }
                            // writeFile does NOT mkdirs — create parent dirs for subpath keys.
                            File parent = dest.getParentFile();
                            if (parent != null && !parent.exists()) parent.mkdirs();
                            writeFile(dest, data);
                            // Stamp the local file with the cloud's Last-Modified so that after this save is
                            // later played + re-uploaded, the NEXT launch's newest-wins sees them as equal
                            // and SKIPS — instead of re-pulling identical bytes every launch. Best-effort.
                            if (cloudMt[0] > 0) dest.setLastModified(cloudMt[0]);
                            downloaded.incrementAndGet();
                        } catch (Exception e) {
                            firstError.compareAndSet(null, e.getMessage());
                        } finally {
                            // Neutral verb: fires per file completed whether pulled OR skipped.
                            cb.onStatus("Syncing game saves… (" + completed.incrementAndGet() + "/" + total + ")");
                        }
                    });
                }
                pool.shutdown();
                pool.awaitTermination(TIMEOUT / 1000 + 30, TimeUnit.SECONDS);

                if (firstError.get() != null) { cb.onError("Download error: " + firstError.get()); return; }
                int dl = downloaded.get(), sk = skipped.get();
                if (dl == 0 && sk > 0) {
                    cb.onDone("Already up to date (" + sk + " file" + (sk == 1 ? "" : "s") + ")");
                } else {
                    cb.onDone("Downloaded " + dl + " file" + (dl == 1 ? "" : "s")
                            + (sk > 0 ? ", skipped " + sk : ""));
                }

            } catch (Exception e) {
                Log.e(TAG, "downloadSaves failed", e);
                debug(ctx, "downloadSaves exception: " + e.getClass().getSimpleName());
                if ("CLOUD_SAVES_NOT_SUPPORTED".equals(e.getMessage()))
                    cb.onError("This game does not support GOG cloud saves");
                else
                    cb.onError("Download error: " + e.getMessage());
            }
        }, "gog-cloud-download-" + gameId).start();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Obtains a game-scoped GOG access token for cloud storage.
     * GOG's cloudstorage API requires a token issued with the game's own
     * client_id/client_secret (not the Galaxy app credentials).
     * We re-use the Galaxy refresh_token but swap in the game credentials.
     * Returns null if clientSecret is missing or the exchange fails.
     */
    private static String getGameScopedToken(Context ctx, String gameId, String clientId,
                                              SharedPreferences prefs) {
        String clientSecret = prefs.getString("gog_client_secret_" + gameId, null);
        if (clientSecret == null || clientSecret.isEmpty()) {
            debug(ctx, "getGameScopedToken: no clientSecret cached for " + gameId + " — falling back");
            return null;
        }
        String refreshToken = prefs.getString("refresh_token", null);
        if (refreshToken == null) {
            debug(ctx, "getGameScopedToken: no Galaxy refresh_token");
            return null;
        }
        try {
            String urlStr = "https://auth.gog.com/token"
                    + "?client_id=" + java.net.URLEncoder.encode(clientId, "UTF-8")
                    + "&client_secret=" + java.net.URLEncoder.encode(clientSecret, "UTF-8")
                    + "&grant_type=refresh_token"
                    + "&refresh_token=" + java.net.URLEncoder.encode(refreshToken, "UTF-8");
            debug(ctx, "getGameScopedToken: requesting scoped token for clientId=" + clientId);
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", "GOG Galaxy");
            int code = conn.getResponseCode();
            debug(ctx, "getGameScopedToken: HTTP=" + code);
            if (code != 200) { conn.disconnect(); return null; }
            String body = readStream(conn.getInputStream());
            conn.disconnect();
            JSONObject json = new JSONObject(body);
            String accessToken = json.optString("access_token", null);
            if (accessToken != null && !accessToken.isEmpty()) {
                debug(ctx, "getGameScopedToken: OK, got scoped token");
                return accessToken;
            }
        } catch (Exception e) {
            debug(ctx, "getGameScopedToken exception: " + e.getClass().getSimpleName());
        }
        return null;
    }

    private static String getValidToken(Context ctx, SharedPreferences prefs) {
        String token = prefs.getString("access_token", null);
        if (token == null) return GogTokenRefresh.refresh(ctx);
        // Refresh if within 5 minutes of expiry
        long loginTime = prefs.getInt("bh_gog_login_time", 0) * 1000L;
        long expiresIn = prefs.getInt("bh_gog_expires_in", 3600) * 1000L;
        if (System.currentTimeMillis() > loginTime + expiresIn - 300_000L) {
            String fresh = GogTokenRefresh.refresh(ctx);
            return fresh != null ? fresh : token;
        }
        return token;
    }

    private static class CloudFile {
        String name;
        long lastModifiedMs;   // epoch milliseconds; 0 = unknown/not fetched
        boolean mtimeFetched;  // true once a HEAD (or a listing with mtime) has resolved lastModifiedMs
        String etag;           // cloud object's ETag == MD5 of its content (verified on-device); null if unknown
    }

    private static List<CloudFile> listCloudFiles(Context ctx, String userId, String clientId, String token)
            throws Exception {
        String url = BASE + userId + "/" + clientId;
        // NB: url embeds the GOG userId in its path — log only the clientId.
        debug(ctx, "listCloudFiles clientId=" + clientId);
        String body = getRequest(url, token);
        debug(ctx, "listCloudFiles response len=" + (body == null ? "null" : body.length()));
        List<CloudFile> result = new ArrayList<>();
        if (body == null || body.isEmpty()) return result;
        String trimmed = body.trim();
        // GOG's cloudstorage list endpoint returns a PLAIN newline-delimited list of
        // filenames (verified on-device), NOT JSON — e.g.
        //     Elderborn_Slot_00.jurmum\naudio.cfg\ngraphics.cfg
        // The old `new JSONArray(body)` threw JSONException on any non-empty cloud,
        // which broke both upload dedup and download. Our own 404 fallback still hands
        // back "[]"; tolerate that (and a defensive JSON-array shape) too.
        if (trimmed.equals("[]")) return result;
        if (trimmed.startsWith("[")) {
            JSONArray arr = new JSONArray(trimmed);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                CloudFile cf = new CloudFile();
                cf.name = obj.optString("name", "");
                long lastMod = obj.optLong("last_modified", 0L);
                cf.lastModifiedMs = lastMod > 1_000_000_000_000L ? lastMod : lastMod * 1000L;
                cf.mtimeFetched = cf.lastModifiedMs > 0L; // listing already carried a real mtime
                if (!cf.name.isEmpty()) result.add(cf);
            }
        } else {
            for (String line : trimmed.split("\\r?\\n")) {
                String name = line.trim();
                if (name.isEmpty()) continue;
                CloudFile cf = new CloudFile();
                cf.name = name;
                // The listing carries no mtime. Leave it unfetched (0/false) — newest-wins
                // resolves it lazily via a per-file HEAD (resolveCloudMtime) only when a
                // local counterpart exists, so an empty file set costs zero HEAD requests.
                cf.lastModifiedMs = 0L;
                result.add(cf);
            }
        }
        debug(ctx, "listCloudFiles parsed " + result.size() + " file(s)");
        return result;
    }

    /** Recursively collect every regular file under {@code dir} into {@code out}. */
    private static void collectFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collectFiles(k, out);
            else if (k.isFile()) out.add(k);
        }
    }

    /**
     * Path of {@code file} relative to {@code root}, as a cloud object key: leading
     * separators stripped and OS separators normalised to '/'. Falls back to the bare
     * file name if {@code file} is somehow not under {@code root}.
     */
    private static String relKey(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        String rel = filePath.startsWith(rootPath) ? filePath.substring(rootPath.length())
                                                    : file.getName();
        rel = rel.replace(File.separatorChar, '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        return rel.isEmpty() ? file.getName() : rel;
    }

    private static CloudFile findCloud(List<CloudFile> cloudFiles, String name) {
        for (CloudFile cf : cloudFiles) {
            if (cf.name.equals(name)) return cf;
        }
        return null;
    }

    /**
     * Encode a cloud object key for use in a URL PATH. {@link java.net.URLEncoder} does FORM encoding,
     * which maps space -> '+' — wrong for a path segment and actively corrupting: a save filename with
     * spaces (e.g. ELDERBORN's "controllerMapSaveData controllerType …") gets stored under a '+'-mangled
     * key, then downloads back as a SEPARATE '+'-named duplicate that never matches the space-named
     * original, so it re-syncs every launch. In a path a space must be "%20". Real '+' characters are
     * already emitted as "%2B" by URLEncoder, so the only '+' left to fix is the one it made from a
     * space — replacing it with "%20" is safe. '/' stays "%2F" (GOG normalises %2F <-> '/').
     */
    private static String encodeKey(String name) throws Exception {
        return java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20");
    }

    /**
     * Lazily resolves a cloud file's Last-Modified via a HEAD (cached on the CloudFile).
     * Returns epoch milliseconds, or -1 if unknown (HEAD failed or header absent), in
     * which case callers fall back to the safe push/pull behaviour for that file.
     */
    private static long resolveCloudMtime(Context ctx, String userId, String clientId,
                                          String token, CloudFile cf) {
        if (cf.mtimeFetched) return cf.lastModifiedMs > 0L ? cf.lastModifiedMs : -1L;
        cf.mtimeFetched = true;
        String[] et = new String[1];
        long ms = headModifiedMs(ctx, userId, clientId, token, cf.name, et);
        cf.lastModifiedMs = ms > 0L ? ms : 0L;
        if (et[0] != null) cf.etag = et[0];   // cached content hash for the newest-content skip
        return ms;
    }

    /**
     * HEADs a cloud object and parses its Last-Modified header (RFC 1123, e.g.
     * "Sat, 22 Aug 2026 17:11:23 GMT" — verified on-device). Returns epoch ms, or -1.
     */
    private static long headModifiedMs(Context ctx, String userId, String clientId,
                                       String token, String filename, String[] outEtag) {
        try {
            String urlStr = BASE + userId + "/" + clientId + "/"
                    + encodeKey(filename);
            HttpURLConnection conn = openConn(urlStr, "HEAD", token);
            int code = conn.getResponseCode();
            String lastMod = conn.getHeaderField("Last-Modified");
            String etag = conn.getHeaderField("ETag");
            conn.disconnect();
            // GOG's ETag is the content MD5 (verified on-device), no weak-validator prefix; strip any quotes.
            if (outEtag != null) outEtag[0] = (etag != null && !etag.isEmpty())
                    ? etag.replace("\"", "").trim() : null;
            if (code < 200 || code >= 300) {
                debug(ctx, "HEAD http=" + code + " for " + filename);
                return -1L;
            }
            if (lastMod == null || lastMod.isEmpty()) {
                debug(ctx, "HEAD missing Last-Modified for " + filename);
                return -1L;
            }
            long ms = parseHttpDate(lastMod);
            if (ms < 0L) debug(ctx, "HEAD unparseable Last-Modified '" + lastMod + "' for " + filename);
            return ms;
        } catch (Exception e) {
            debug(ctx, "headModifiedMs exception " + e.getClass().getSimpleName() + " for " + filename);
            return -1L;
        }
    }

    /** Lowercase hex MD5 of a local file (to compare against a cloud object's ETag), or null on failure. */
    private static String md5(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse an RFC 1123 HTTP date to epoch ms, or -1 on failure. */
    private static long parseHttpDate(String s) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            Date d = fmt.parse(s.trim());
            return d != null ? d.getTime() : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String getRequest(String urlStr, String token) throws Exception {
        HttpURLConnection conn = openConn(urlStr, "GET", token);
        int code = conn.getResponseCode();
        Log.d(TAG, "GET cloud-save → " + code);
        if (code == 404) { conn.disconnect(); return "[]"; }
        if (code < 200 || code >= 300) {
            String errBody = "";
            try { errBody = readStream(conn.getErrorStream()); } catch (Exception ignored) {}
            conn.disconnect();
            if (errBody.contains("not_enabled_for_client") || errBody.contains("disabled"))
                throw new Exception("CLOUD_SAVES_NOT_SUPPORTED");
            throw new Exception("HTTP " + code + " body=" + errBody.substring(0, Math.min(200, errBody.length())));
        }
        String body = readStream(conn.getInputStream());
        conn.disconnect();
        return body;
    }

    /**
     * GETs a cloud object. If {@code outMtime} is non-null, its [0] is set to the response's
     * Last-Modified epoch-ms (or -1 if absent/unparseable) so the caller can stamp the local file with
     * the cloud mtime — keeping the two in lockstep for newest-wins.
     */
    private static byte[] getFile(String userId, String clientId, String token, String filename,
                                  long[] outMtime) throws Exception {
        String urlStr = BASE + userId + "/" + clientId + "/" + encodeKey(filename);
        HttpURLConnection conn = openConn(urlStr, "GET", token);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new Exception("HTTP " + code + " for " + filename);
        }
        if (outMtime != null) {
            String lm = conn.getHeaderField("Last-Modified");
            outMtime[0] = (lm != null && !lm.isEmpty()) ? parseHttpDate(lm) : -1L;
        }
        byte[] data = readBytes(conn.getInputStream());
        conn.disconnect();
        return data;
    }

    private static boolean putFile(String userId, String clientId, String token,
                                    String filename, byte[] data) {
        try {
            String urlStr = BASE + userId + "/" + clientId + "/" + encodeKey(filename);
            HttpURLConnection conn = openConn(urlStr, "PUT", token);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Content-Length", String.valueOf(data.length));
            conn.getOutputStream().write(data);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.e(TAG, "putFile failed for " + filename, e);
            return false;
        }
    }

    private static HttpURLConnection openConn(String urlStr, String method, String token)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("User-Agent", "GOG Galaxy");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        return conn;
    }

    private static String readStream(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
        return sb.toString();
    }

    private static byte[] readBytes(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static byte[] readFile(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "readFile failed: " + f, e);
            return null;
        }
    }

    private static void writeFile(File dest, byte[] data) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(data);
        }
    }

    // ── Debug file helper ─────────────────────────────────────────────────────

    static void debug(Context ctx, String msg) {
        try {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String line = ts + " [GOG] " + msg + "\n";
            File f = new File(android.os.Environment.getExternalStorageDirectory(), "bh_cloud_debug.txt");
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(line.getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
        Log.d(TAG, msg);
    }

    private GogCloudSaveManager() {}
}
