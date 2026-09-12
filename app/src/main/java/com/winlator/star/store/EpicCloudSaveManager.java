package com.winlator.star.store;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Epic Cloud Save upload/download manager.
 *
 * API base: https://datastorage-public-service-liveegs.live.use1a.on.epicgames.com
 *
 * List files:        GET  /api/v1/access/egstore/savesync/{accountId}/{appName}/
 * Request writeLinks: POST /api/v1/access/egstore/savesync/{accountId}/{appName}/
 * Upload file:       PUT  {writeLink}   (no auth header — pre-signed URL)
 * Download file:     GET  {readLink}    (no auth header — pre-signed URL)
 */
public final class EpicCloudSaveManager {

    private static final String TAG = "BH_EPIC_CLOUD";
    private static final String BASE =
            "https://datastorage-public-service-liveegs.live.use1a.on.epicgames.com" +
            "/api/v1/access/egstore/savesync/";
    private static final int TIMEOUT = 30_000;

    public interface Callback {
        void onStatus(String message);
        void onDone(String summary);
        void onError(String message);
    }

    /** Upload local saves to Epic cloud (only files newer than cloud version). */
    public static void uploadSaves(Context ctx, String appName, File localFolder, Callback cb) {
        new Thread(() -> {
            try {
                String token = EpicCredentialStore.getValidAccessToken(ctx);
                if (token == null) { cb.onError("Not logged in to Epic"); return; }
                EpicCredentialStore.Credentials creds = EpicCredentialStore.load(ctx);
                String accountId = creds != null ? creds.accountId : null;
                if (accountId == null || accountId.isEmpty()) { cb.onError("Epic account ID not found — please sign in again"); return; }
                debug(ctx, "Epic upload — appName=" + appName);

                cb.onStatus("Fetching cloud file list…");
                List<CloudFile> cloudFiles = listCloudFiles(ctx, accountId, appName, token);

                // Recursive walk: collect every file under localFolder as a '/'-joined RELATIVE path
                // (the Epic savesync key is the relative path, so a nested save tree round-trips).
                List<String> localRel = new ArrayList<>();
                collectRelativeFiles(localFolder, "", localRel);
                debug(ctx, "upload localFolder=" + localFolder + " localFiles=" + localRel.size() + " cloudFiles=" + cloudFiles.size());
                if (localRel.isEmpty()) {
                    debug(ctx, "upload — no local files under resolved folder");
                    cb.onDone("No local files to upload");
                    return;
                }

                // Determine which files need uploading (newer than the cloud copy of the SAME rel path).
                List<String> toUpload = new ArrayList<>();
                for (String rel : localRel) {
                    File local = new File(localFolder, rel);
                    long localModMs = local.lastModified();
                    long cloudModMs = getCloudModifiedMs(cloudFiles, rel);
                    if (localModMs > cloudModMs) toUpload.add(rel);
                }
                debug(ctx, "upload toUpload=" + toUpload.size() + " of " + localRel.size() + " (newer-than-cloud) " + toUpload);

                if (toUpload.isEmpty()) {
                    debug(ctx, "upload — already up to date, nothing PUT");
                    cb.onDone("Already up to date");
                    return;
                }

                cb.onStatus("Requesting upload links for " + toUpload.size() + " file(s)…");
                List<WriteLink> writeLinks = requestWriteLinks(accountId, appName, token, toUpload);
                debug(ctx, "upload writeLinks granted=" + writeLinks.size() + " for requested=" + toUpload.size());

                int uploaded = 0;
                for (WriteLink wl : writeLinks) {
                    cb.onStatus("Uploading: " + wl.name);
                    File local = new File(localFolder, wl.name);
                    if (!local.exists()) { debug(ctx, "upload PUT skip (missing local): " + wl.name); continue; }
                    byte[] data = readFile(local);
                    if (data == null) { cb.onError("Failed to read: " + wl.name); return; }
                    boolean ok = putToPresignedUrl(wl.url, data);
                    debug(ctx, "upload PUT " + wl.name + " bytes=" + data.length + " -> " + (ok ? "ok" : "FAIL"));
                    if (!ok) { cb.onError("Upload failed for: " + wl.name); return; }
                    uploaded++;
                }

                markSynced(ctx, appName);
                debug(ctx, "upload complete — uploaded=" + uploaded + " file(s)");
                cb.onDone("Uploaded " + uploaded + " file" + (uploaded == 1 ? "" : "s"));

            } catch (Exception e) {
                Log.e(TAG, "uploadSaves failed", e);
                cb.onError("Upload error: " + e.getMessage());
            }
        }, "epic-cloud-upload-" + appName).start();
    }

    /** Download all Epic cloud saves to local folder, overwriting local copies. */
    public static void downloadSaves(Context ctx, String appName, File localFolder, Callback cb) {
        new Thread(() -> {
            try {
                String token = EpicCredentialStore.getValidAccessToken(ctx);
                if (token == null) { cb.onError("Not logged in to Epic"); return; }
                EpicCredentialStore.Credentials creds = EpicCredentialStore.load(ctx);
                String accountId = creds != null ? creds.accountId : null;
                if (accountId == null || accountId.isEmpty()) { cb.onError("Epic account ID not found — please sign in again"); return; }
                debug(ctx, "Epic download — appName=" + appName);

                cb.onStatus("Fetching cloud file list…");
                List<CloudFile> cloudFiles = listCloudFiles(ctx, accountId, appName, token);
                debug(ctx, "download cloudFiles=" + cloudFiles.size() + " -> localFolder=" + localFolder);

                if (cloudFiles.isEmpty()) {
                    debug(ctx, "download — no cloud saves found");
                    cb.onDone("No cloud saves found");
                    return;
                }

                if (!localFolder.exists()) localFolder.mkdirs();

                int downloaded = 0;
                for (CloudFile cf : cloudFiles) {
                    if (cf.readLink == null || cf.readLink.isEmpty()) continue;
                    cb.onStatus("Downloading: " + cf.name);
                    byte[] data = getFromPresignedUrl(cf.readLink);
                    if (data == null) { cb.onError("Download failed for: " + cf.name); return; }
                    // cf.name is a '/'-joined RELATIVE path — recreate the nested tree under localFolder.
                    File dest = new File(localFolder, cf.name);
                    File parent = dest.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    writeFile(dest, data);
                    debug(ctx, "download GET " + cf.name + " bytes=" + data.length + " -> " + dest);
                    downloaded++;
                }

                markSynced(ctx, appName);
                debug(ctx, "download complete — downloaded=" + downloaded + " file(s)");
                cb.onDone("Downloaded " + downloaded + " file" + (downloaded == 1 ? "" : "s"));

            } catch (Exception e) {
                Log.e(TAG, "downloadSaves failed", e);
                cb.onError("Download error: " + e.getMessage());
            }
        }, "epic-cloud-download-" + appName).start();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static class CloudFile {
        String name;
        long lastModifiedMs;
        String readLink;
    }

    private static class WriteLink {
        String name;
        String url;
    }

    private static List<CloudFile> listCloudFiles(Context ctx, String accountId, String appName, String token)
            throws Exception {
        String urlStr = BASE + accountId + "/" + appName + "/";
        // NB: urlStr embeds the Epic accountId in its path — log only the appName.
        debug(ctx, "listCloudFiles appName=" + appName);
        HttpURLConnection conn = openConn(urlStr, "GET", token);
        int code = conn.getResponseCode();
        debug(ctx, "listCloudFiles HTTP=" + code);
        if (code == 404) {
            debug(ctx, "listCloudFiles 404 — no saves on cloud yet");
            conn.disconnect();
            return new ArrayList<>();
        }
        if (code < 200 || code >= 300) {
            String errBody = "";
            try { errBody = readStream(conn.getErrorStream()); } catch (Exception ignored) {}
            conn.disconnect();
            debug(ctx, "listCloudFiles error HTTP " + code);
            throw new Exception("HTTP " + code + " listing saves");
        }
        String body = readStream(conn.getInputStream());
        conn.disconnect();
        debug(ctx, "listCloudFiles body len=" + (body == null ? 0 : body.length()));

        List<CloudFile> result = new ArrayList<>();
        if (body == null || body.isEmpty()) return result;

        JSONObject root = new JSONObject(body);
        JSONObject files = root.optJSONObject("files");
        if (files == null) return result;

        Iterator<String> keys = files.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = files.optJSONObject(key);
            if (entry == null) continue;
            CloudFile cf = new CloudFile();
            // Epic's GET listing returns the full canonical storage key
            // ("savesync/<hash>/<appName>/<relPath>"), while requestWriteLinks echoes the
            // bare name we asked for. Normalize to the game-relative tail so download writes
            // to localFolder/<relPath> (not a recreated server tree) and the upload
            // newer-than-cloud dedup matches local relative paths. See stripCloudKeyPrefix.
            cf.name = stripCloudKeyPrefix(key, appName);
            cf.readLink = entry.optString("readLink", null);
            String lastModStr = entry.optString("lastModified", null);
            cf.lastModifiedMs = parseIso8601Ms(lastModStr);
            result.add(cf);
        }
        return result;
    }

    private static List<WriteLink> requestWriteLinks(String accountId, String appName,
                                                      String token, List<String> filenames)
            throws Exception {
        String urlStr = BASE + accountId + "/" + appName + "/";
        JSONObject reqBody = new JSONObject();
        JSONArray arr = new JSONArray();
        for (String f : filenames) arr.put(f);
        reqBody.put("files", arr);
        String bodyStr = reqBody.toString();

        HttpURLConnection conn = openConn(urlStr, "POST", token);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] bodyBytes = bodyStr.getBytes("UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) { os.write(bodyBytes); }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new Exception("HTTP " + code + " requesting write links");
        }
        String resp = readStream(conn.getInputStream());
        conn.disconnect();

        List<WriteLink> result = new ArrayList<>();
        JSONObject root = new JSONObject(resp);
        JSONObject files = root.optJSONObject("files");
        if (files == null) return result;

        Iterator<String> keys = files.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = files.optJSONObject(key);
            if (entry == null) continue;
            String wl = entry.optString("writeLink", null);
            if (wl == null || wl.isEmpty()) continue;
            WriteLink writeLink = new WriteLink();
            writeLink.name = key;
            writeLink.url  = wl;
            result.add(writeLink);
        }
        return result;
    }

    private static boolean putToPresignedUrl(String urlStr, byte[] data) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Content-Length", String.valueOf(data.length));
            try (OutputStream os = conn.getOutputStream()) { os.write(data); }
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.e(TAG, "putToPresignedUrl failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static byte[] getFromPresignedUrl(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) { conn.disconnect(); return null; }
            byte[] data = readBytes(conn.getInputStream());
            conn.disconnect();
            return data;
        } catch (Exception e) {
            Log.e(TAG, "getFromPresignedUrl failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static HttpURLConnection openConn(String urlStr, String method, String token)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("User-Agent", "EpicGamesLauncher/15.17.1-22692490");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        return conn;
    }

    /**
     * Recursively collect every regular file under [dir] as a '/'-joined path relative to the walk
     * root, accumulating into [out]. [prefix] is the relative path of [dir] itself ("" at the root).
     */
    private static void collectRelativeFiles(File dir, String prefix, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String rel = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) {
                collectRelativeFiles(child, rel, out);
            } else if (child.isFile()) {
                out.add(rel);
            }
        }
    }

    /**
     * Persist the last-successful-sync marker for [appName] to {@code bh_epic_prefs} as an ISO-8601
     * UTC timestamp (lexically comparable). Stored now for later conflict detection (P2); the current
     * MVP doesn't read it back. Best-effort — never throws into the sync path.
     */
    private static void markSynced(Context ctx, String appName) {
        try {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            ctx.getApplicationContext()
                    .getSharedPreferences("bh_epic_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("epic_sync_timestamp_" + appName, iso.format(new Date()))
                    .apply();
        } catch (Exception ignored) {}
    }

    /**
     * Reduce an Epic cloud storage key to the game-relative path.
     * Epic returns keys as "savesync/&lt;hash&gt;/&lt;appName&gt;/&lt;relPath&gt;"; strip everything
     * up to and including the "/&lt;appName&gt;/" segment so the tail is what the game reads
     * under its save folder. Also handles a key already rooted at "&lt;appName&gt;/", and leaves
     * an already-bare key (no prefix) untouched so this is safe to apply unconditionally.
     */
    private static String stripCloudKeyPrefix(String key, String appName) {
        if (key == null) return null;
        if (appName != null && !appName.isEmpty()) {
            String marker = "/" + appName + "/";
            int idx = key.indexOf(marker);
            if (idx >= 0) return key.substring(idx + marker.length());
            String rooted = appName + "/";
            if (key.startsWith(rooted)) return key.substring(rooted.length());
        }
        return key;
    }

    private static long getCloudModifiedMs(List<CloudFile> cloudFiles, String name) {
        for (CloudFile cf : cloudFiles) {
            if (cf.name.equals(name)) return cf.lastModifiedMs;
        }
        return 0L;
    }

    /** Parse ISO8601 like "2026-03-29T10:00:00.000Z" to epoch millis. */
    private static long parseIso8601Ms(String s) {
        if (s == null || s.length() < 19) return 0L;
        try {
            // "2026-03-29T10:00:00.000Z"
            int year  = Integer.parseInt(s.substring(0, 4));
            int month = Integer.parseInt(s.substring(5, 7));
            int day   = Integer.parseInt(s.substring(8, 10));
            int hour  = Integer.parseInt(s.substring(11, 13));
            int min   = Integer.parseInt(s.substring(14, 16));
            int sec   = Integer.parseInt(s.substring(17, 19));
            // Use Calendar UTC
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            cal.set(year, month - 1, day, hour, min, sec);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
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
            String line = ts + " [EPIC] " + msg + "\n";
            File f = new File(android.os.Environment.getExternalStorageDirectory(), "bh_cloud_debug.txt");
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(line.getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
        Log.d(TAG, msg);
    }

    private EpicCloudSaveManager() {}
}
