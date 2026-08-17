package com.winlator.star.store;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;

import com.winlator.star.R;
import com.winlator.star.container.Container;
import com.winlator.star.container.ContainerManager;
import com.winlator.star.container.Shortcut;
import com.winlator.star.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * Launch bridge for store integrations (GOG / Epic / Amazon).
 *
 * Presents a container picker dialog after a game is downloaded, writes a
 * .desktop shortcut into the chosen Wine container's desktop directory, and
 * downloads + saves the game's cover art as both the shortcut icon (shown in
 * the Shortcuts grid) and as customCoverArt in [Extra Data].
 *
 * Cover art resolution strategy:
 *   1. Use the store-provided URL (passed in by the caller — Epic gives tall
 *      portrait DieselGameBoxTall ~1200x1600; GOG uses SteamGridDB 600x900
 *      first then falls back to its icon CDN).
 *   2. If the store URL is absent or fails to download, fall back to
 *      SteamGridDB autocomplete → grids API (600x900 portrait cover).
 *
 * Shortcut format (Winlator .desktop):
 *   [Desktop Entry]
 *   Name=<game name>
 *   Exec=wine <Z:\path\to\game.exe>
 *   Icon=<safeName>
 *   Type=Application
 *   StartupWMClass=explorer
 *
 *   [Extra Data]
 *   customCoverArtPath=<absolute path to PNG>
 */
public final class StarLaunchBridge {

    private static final String TAG = "BH_BRIDGE";
    private static final String SGDB_KEY = "cf89227f12c773bb1117b6b109ae1659";

    private StarLaunchBridge() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Delivers the container list to the caller on the main thread. */
    public interface ContainersCallback {
        void onContainers(ArrayList<Container> containers);
    }

    /** Delivers a shortcut-write outcome to the caller on the main thread. */
    public interface ResultCallback {
        void onResult(boolean success, String message);
    }

    /**
     * Loads the Wine container list on a worker thread and delivers it on the
     * main thread. Never delivers null — failures deliver an empty list.
     * Used by the Compose add-to-shortcuts flow (Steam store).
     */
    public static void loadContainers(Activity activity, ContainersCallback cb) {
        Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            ArrayList<Container> containers = null;
            try {
                containers = new ContainerManager(activity).getContainers();
            } catch (Exception e) {
                Log.e(TAG, "loadContainers failed", e);
            }
            ArrayList<Container> result = (containers != null) ? containers : new ArrayList<>();
            h.post(() -> cb.onContainers(result));
        }, "store-launcher-picker").start();
    }

    /**
     * Show a container picker, write a shortcut, then download cover art.
     *
     * @param activity     calling Activity
     * @param gameName     display name (used as shortcut filename and title)
     * @param exePath      absolute Android path to the .exe (under imagefs/)
     * @param coverArtUrl  URL of the game's cover art image, or null to fall
     *                     back to SteamGridDB
     */
    public static void addToLauncher(Activity activity,
                                     String gameName,
                                     String exePath,
                                     String coverArtUrl) {
        Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                ContainerManager manager = new ContainerManager(activity);
                ArrayList<Container> containers = manager.getContainers();

                if (containers == null || containers.isEmpty()) {
                    h.post(() -> showToast(activity,
                            "No Wine container found — create one first in the Containers screen."));
                    return;
                }

                String[] names = new String[containers.size()];
                for (int i = 0; i < containers.size(); i++) {
                    String n = containers.get(i).getName();
                    names[i] = (n != null && !n.isEmpty()) ? n : "Container " + (i + 1);
                }

                ArrayList<Container> finalContainers = containers;
                h.post(() -> new AlertDialog.Builder(activity, R.style.StoreAlertDialogDark)
                        .setTitle("Add \"" + gameName + "\" to…")
                        .setItems(names, (dialog, which) ->
                                writeShortcut(activity, finalContainers.get(which),
                                        gameName, exePath, coverArtUrl))
                        .setNegativeButton("Cancel", null)
                        .show());

            } catch (Exception e) {
                Log.e(TAG, "addToLauncher failed", e);
                h.post(() -> showToast(activity,
                        "Error loading containers: " + e.getMessage()));
            }
        }, "store-launcher-picker").start();
    }

    /**
     * Convenience overload — falls back to SteamGridDB for cover art.
     */
    public static void addToLauncher(Activity activity, String gameName, String exePath) {
        addToLauncher(activity, gameName, exePath, null);
    }

    /**
     * Writes a .desktop shortcut (plus cover art) into {@code container} on a
     * worker thread and reports the outcome via {@code cb} on the main thread.
     * Same logic as the legacy toast path — callers decide how to surface the
     * result (the Compose Steam flow shows an M3 dialog).
     */
    public static void writeShortcutAsync(Activity activity,
                                          Container container,
                                          String gameName,
                                          String exePath,
                                          String coverArtUrl,
                                          ResultCallback cb) {
        Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File desktopDir = container.getDesktopDir();
                if (desktopDir == null) {
                    h.post(() -> cb.onResult(false,
                            "Container desktop directory not found."));
                    return;
                }
                if (!desktopDir.exists() && !desktopDir.mkdirs()) {
                    h.post(() -> cb.onResult(false,
                            "Could not create container desktop directory."));
                    return;
                }

                // Sanitise game name → safe filename
                String safeName = gameName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (safeName.isEmpty()) safeName = "game";

                File shortcutFile = new File(desktopDir, safeName + ".desktop");

                // Derive path relative to imagefs root (forward slashes)
                String imageFsRoot = new java.io.File(activity.getFilesDir(), "imagefs").getAbsolutePath();
                String relPath = exePath.startsWith(imageFsRoot)
                        ? exePath.substring(imageFsRoot.length()) : exePath;
                if (relPath.startsWith("/")) relPath = relPath.substring(1);

                // Convert to Windows path — match Winlator's native shortcut format.
                // No WINEPREFIX in Exec=; Winlator derives WINEPREFIX from the container
                // object via container_id.
                // Use 4 backslashes per separator so StringUtils.unescape() produces
                // a valid Z:\path\to\game.exe after its two-pass strip.
                String windowsPath = relPath.replace("/", "\\\\\\\\");

                // Icon= references a PNG saved in container.getIconsDir(64) by saveCoverArt().
                String content = "[Desktop Entry]\n"
                        + "Name=" + gameName + "\n"
                        + "Exec=wine Z:\\\\\\\\" + windowsPath + "\n"
                        + "Icon=" + safeName + "\n"
                        + "Type=Application\n"
                        + "StartupWMClass=explorer\n"
                        + "\n"
                        + "[Extra Data]\n";

                try (FileWriter fw = new FileWriter(shortcutFile)) {
                    fw.write(content);
                }

                Log.d(TAG, "Wrote shortcut: " + shortcutFile.getPath());

                // Resolve cover art URL: fix protocol-relative, then try store URL,
                // fall back to SteamGridDB if needed.
                String artUrl = normalizeUrl(coverArtUrl);
                if (artUrl == null || artUrl.isEmpty()) {
                    Log.d(TAG, "No store cover art URL — trying SteamGridDB for: " + gameName);
                    artUrl = sgdbFetchCover(gameName);
                }

                if (artUrl != null && !artUrl.isEmpty()) {
                    saveCoverArt(activity, container, shortcutFile, safeName, artUrl);
                } else {
                    Log.d(TAG, "No cover art found for: " + gameName);
                }

                h.post(() -> cb.onResult(true,
                        "\"" + gameName + "\" added to Shortcuts.\n"
                                + "Open the side menu → Shortcuts to launch and configure it."));

            } catch (Exception e) {
                Log.e(TAG, "writeShortcut failed for " + gameName, e);
                h.post(() -> cb.onResult(false,
                        "Failed to add shortcut: " + e.getMessage()));
            }
        }, "store-write-shortcut").start();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /** Legacy path (GOG / Epic / Amazon): write the shortcut, toast the result. */
    private static void writeShortcut(Activity activity,
                                      Container container,
                                      String gameName,
                                      String exePath,
                                      String coverArtUrl) {
        writeShortcutAsync(activity, container, gameName, exePath, coverArtUrl,
                (success, message) -> showToast(activity, message));
    }

    /**
     * Shows a Toast with an explicit custom view. With targetSdk 28 toasts are
     * app-rendered and inherit {@code android:colorBackground} from AppTheme,
     * which this app forces to #000000 — the stock toast then draws black text
     * on a black pill. An explicit white-on-dark-grey view stays readable.
     */
    private static void showToast(Context ctx, String message) {
        float density = ctx.getResources().getDisplayMetrics().density;

        TextView tv = new TextView(ctx);
        tv.setText(message);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int padH = Math.round(16 * density);
        int padV = Math.round(10 * density);
        tv.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6323232);
        bg.setCornerRadius(16 * density);
        tv.setBackground(bg);

        Toast toast = new Toast(ctx.getApplicationContext());
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setView(tv);
        toast.show();
    }

    /**
     * Downloads cover art from {@code url}, saves it as the shortcut icon in
     * {@code container.getIconsDir(64)} (so ShortcutsFragment displays it), and
     * also registers it via {@link Shortcut#saveCustomCoverArt(Bitmap)} so the
     * detail view can use the full-resolution copy.
     *
     * If the store URL fails, a SteamGridDB lookup is attempted automatically
     * before giving up.
     */
    public static void saveCoverArt(Context ctx, Container container,
                                    File shortcutFile, String safeName,
                                    String url) {
        Bitmap bmp = downloadBitmap(url);

        // If store URL failed, try SteamGridDB as last resort
        if (bmp == null) {
            Log.w(TAG, "Store cover art download failed for " + safeName + ", trying SteamGridDB");
            String sgdbUrl = sgdbFetchCover(safeName);
            if (sgdbUrl != null && !sgdbUrl.isEmpty()) {
                bmp = downloadBitmap(sgdbUrl);
            }
        }

        if (bmp == null) {
            Log.w(TAG, "All cover art sources failed for " + safeName);
            return;
        }

        try {
            // Save to icons dir — this is what ShortcutsFragment reads via Icon= field.
            File iconsDir = container.getIconsDir(64);
            if (iconsDir != null) {
                if (!iconsDir.exists()) iconsDir.mkdirs();
                File iconFile = new File(iconsDir, safeName + ".png");
                FileUtils.saveBitmapToFile(bmp, iconFile);
                Log.d(TAG, "Saved icon to: " + iconFile.getPath());
            }

            // Also register as customCoverArt so the shortcut detail view has it.
            Shortcut shortcut = new Shortcut(container, shortcutFile);
            shortcut.saveCustomCoverArt(bmp);
            Log.d(TAG, "Cover art saved for " + safeName);
        } catch (Exception e) {
            Log.w(TAG, "Cover art save failed for " + safeName + ": " + e.getMessage());
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    /** Prepends https: to protocol-relative URLs (//cdn.example.com/…). */
    private static String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        return url.startsWith("//") ? "https:" + url : url;
    }

    /** Downloads a URL and decodes it as a Bitmap. Returns null on any failure. */
    private static Bitmap downloadBitmap(String url) {
        try {
            Log.d(TAG, "Downloading cover art: " + url);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "Cover art HTTP " + code + " for " + url);
                conn.disconnect();
                return null;
            }
            Bitmap bmp;
            try (InputStream is = conn.getInputStream()) {
                bmp = BitmapFactory.decodeStream(is);
            }
            conn.disconnect();
            if (bmp == null) Log.w(TAG, "Cover art decode returned null for " + url);
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "downloadBitmap failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Searches SteamGridDB for a 600x900 portrait cover matching {@code title}.
     * Returns the image URL or an empty string on failure.
     */
    private static String sgdbFetchCover(String title) {
        try {
            String encoded = java.net.URLEncoder.encode(title, "UTF-8");
            String searchJson = httpGet(
                    "https://www.steamgriddb.com/api/v2/search/autocomplete/" + encoded);
            if (searchJson == null) return "";
            JSONArray results = new JSONObject(searchJson).optJSONArray("data");
            if (results == null || results.length() == 0) return "";
            int gameId = results.getJSONObject(0).getInt("id");

            String gridsJson = httpGet(
                    "https://www.steamgriddb.com/api/v2/grids/game/" + gameId
                            + "?dimensions=600x900&mimes=image/jpeg,image/png&limit=1");
            if (gridsJson == null) return "";
            JSONArray grids = new JSONObject(gridsJson).optJSONArray("data");
            if (grids == null || grids.length() == 0) return "";
            String imgUrl = grids.getJSONObject(0).optString("url", "");
            Log.d(TAG, "SteamGridDB cover for \"" + title + "\": " + imgUrl);
            return imgUrl;
        } catch (Exception e) {
            Log.w(TAG, "sgdbFetchCover failed for \"" + title + "\": " + e.getMessage());
            return "";
        }
    }

    /**
     * Searches SteamGridDB for all available covers matching {@code title}
     * and returns a JSON array of {thumb, url} objects, or "[]" on failure.
     */
    public static String sgdbFetchGridsJson(String title) {
        try {
            String encoded = java.net.URLEncoder.encode(title, "UTF-8");
            String searchJson = httpGet(
                    "https://www.steamgriddb.com/api/v2/search/autocomplete/" + encoded);
            if (searchJson == null) return "[]";
            JSONArray results = new JSONObject(searchJson).optJSONArray("data");
            if (results == null || results.length() == 0) return "[]";
            int gameId = results.getJSONObject(0).getInt("id");

            String gridsJson = httpGet(
                    "https://www.steamgriddb.com/api/v2/grids/game/" + gameId
                            + "?dimensions=600x900&mimes=image/jpeg,image/png");
            if (gridsJson == null) return "[]";
            JSONArray grids = new JSONObject(gridsJson).optJSONArray("data");
            if (grids == null || grids.length() == 0) return "[]";

            JSONArray out = new JSONArray();
            for (int i = 0; i < grids.length(); i++) {
                JSONObject g = grids.getJSONObject(i);
                JSONObject entry = new JSONObject();
                entry.put("thumb", g.optString("thumb", ""));
                entry.put("url", g.optString("url", ""));
                out.put(entry);
            }
            Log.d(TAG, "SteamGridDB found " + out.length() + " covers for \"" + title + "\"");
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "sgdbFetchGridsJson failed for \"" + title + "\": " + e.getMessage());
            return "[]";
        }
    }

    private static String httpGet(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Authorization", "Bearer " + SGDB_KEY);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
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
}
