package com.winlator.star.store;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Epic Games Store API client.
 *
 * Library:  GET https://library-service.live.use1a.on.epicgames.com/library/api/public/items
 * Catalog:  GET https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace/{ns}/bulk/items
 * Manifest: GET https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/public/assets/v2/...
 *
 * All requests: Authorization: Bearer <accessToken>
 * User-Agent:   Legendary/0.1.0 (GameNative)
 */
public class EpicApiClient {

    private static final String TAG = "BH_EPIC";

    private static final String LIBRARY_URL =
            "https://library-service.live.use1a.on.epicgames.com/library/api/public/items?includeMetadata=true";
    private static final String CATALOG_BASE =
            "https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace";
    private static final String MANIFEST_BASE =
            "https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/public/assets/v2/platform/Windows/namespace";

    private static final String LEGENDARY_UA = "Legendary/0.1.0 (GameNative)";

    // ── Library Sync ──────────────────────────────────────────────────────────

    /**
     * Fetches the full owned-games list (paginated).
     * Filters out UE assets, private sandboxes, and non-Windows games.
     * Returns new EpicGame objects with appName, namespace, catalogItemId filled.
     */
    public static List<EpicGame> getLibraryItems(String accessToken) {
        List<EpicGame> result = new ArrayList<>();
        String cursor = null;

        do {
            try {
                String url = LIBRARY_URL;
                if (cursor != null) url += "&cursor=" + cursor;

                String resp = EpicAuthClient.getRequest(url, accessToken);
                if (resp == null) break;

                JSONObject json = new JSONObject(resp);
                JSONArray records = json.optJSONArray("records");
                if (records == null) break;

                for (int i = 0; i < records.length(); i++) {
                    JSONObject rec = records.getJSONObject(i);
                    String appName = rec.optString("appName", "");
                    if (appName.isEmpty() || appName.equals("1")) continue;

                    String namespace = rec.optString("namespace", "");
                    // Skip Unreal Engine assets
                    if ("ue".equals(namespace)) continue;
                    if ("89efe5924d3d467c839449ab6ab52e7f".equals(namespace)) continue;

                    // Skip private sandboxes
                    if ("PRIVATE".equals(rec.optString("sandboxType", ""))) continue;

                    // Skip non-Windows games (if platform array is present and doesn't include Windows)
                    JSONArray platforms = rec.optJSONArray("platform");
                    if (platforms != null && platforms.length() > 0) {
                        boolean hasWindows = false;
                        for (int j = 0; j < platforms.length(); j++) {
                            String p = platforms.optString(j, "");
                            if ("Windows".equals(p) || "Win32".equals(p)) {
                                hasWindows = true;
                                break;
                            }
                        }
                        if (!hasWindows) continue;
                    }

                    EpicGame game = new EpicGame();
                    game.appName       = appName;
                    game.namespace     = namespace;
                    game.catalogItemId = rec.optString("catalogItemId", "");
                    result.add(game);
                }

                // Pagination
                JSONObject meta = json.optJSONObject("responseMetadata");
                String nextCursor = meta != null ? meta.optString("nextCursor", null) : null;
                if (nextCursor == null || nextCursor.isEmpty() || nextCursor.equals(cursor)) {
                    cursor = null;
                } else {
                    cursor = nextCursor;
                }

            } catch (Exception e) {
                Log.e(TAG, "getLibraryItems page failed", e);
                break;
            }
        } while (cursor != null);

        return result;
    }

    /**
     * Fetch catalog item details and populate title, developer, art URLs, description.
     * Modifies game in place. Returns true on success.
     */
    public static boolean enrichFromCatalog(String accessToken, EpicGame game) {
        if (game.namespace.isEmpty() || game.catalogItemId.isEmpty()) return false;
        try {
            String url = CATALOG_BASE + "/" + game.namespace
                    + "/bulk/items?id=" + game.catalogItemId
                    + "&includeDLCDetails=true&includeMainGameDetails=true&country=US";

            String resp = getWithLegendaryUA(url, accessToken);
            if (resp == null) return false;

            JSONObject root = new JSONObject(resp);
            JSONObject item = root.optJSONObject(game.catalogItemId);
            if (item == null) {
                // Try first key if catalogItemId doesn't match exactly
                if (root.length() > 0) {
                    String firstKey = root.keys().next();
                    item = root.optJSONObject(firstKey);
                }
                if (item == null) return false;
            }

            game.title       = item.optString("title",       game.title.isEmpty() ? game.appName : game.title);
            game.developer   = item.optString("developer",   "");
            game.description = item.optString("description", "");

            // DLC detection
            game.isDLC = item.has("mainGameItem");
            if (game.isDLC) {
                JSONObject mainGameItem = item.optJSONObject("mainGameItem");
                if (mainGameItem != null) {
                    game.baseGameCatalogItemId = mainGameItem.optString("id", "");
                }
            }

            // Key images
            JSONArray keyImages = item.optJSONArray("keyImages");
            if (keyImages != null) {
                for (int i = 0; i < keyImages.length(); i++) {
                    JSONObject img  = keyImages.getJSONObject(i);
                    String type     = img.optString("type", "");
                    String imgUrl   = img.optString("url", "");
                    if (imgUrl.isEmpty()) continue;
                    switch (type) {
                        case "DieselGameBoxTall":  game.artCover  = imgUrl; break;
                        case "DieselGameBox":
                        case "Thumbnail":
                            if (game.artSquare.isEmpty()) game.artSquare = imgUrl;
                            break;
                    }
                }
            }

            // CanRunOffline + CloudSaveFolder from customAttributes
            JSONObject attrs = item.optJSONObject("customAttributes");
            if (attrs != null) {
                JSONObject offlineAttr = attrs.optJSONObject("CanRunOffline");
                if (offlineAttr != null) {
                    game.canRunOffline = !"false".equalsIgnoreCase(offlineAttr.optString("value", "true"));
                }
                // CloudSaveFolder = the Windows-platform save-folder token string Epic ships for
                // cloud-save-enabled titles (e.g. "{userdir}/My Games/<Game>/Saves"). Its presence
                // (non-empty value) is our cloudSaveEnabled signal. Persisted per-game so the Save
                // Manager's Epic tab can resolve it to a real prefix dir without another catalog call.
                game.cloudSaveFolder = parseCloudSaveFolder(attrs);
                game.cloudSaveEnabled = !game.cloudSaveFolder.isEmpty();
            }

            // Release date
            String rd = item.optString("viewableDate", "");
            if (rd.isEmpty()) rd = item.optString("effectiveDate", "");
            if (!rd.isEmpty()) game.releaseDate = rd;

            return true;

        } catch (Exception e) {
            Log.e(TAG, "enrichFromCatalog failed for " + game.appName, e);
            return false;
        }
    }

    /**
     * Fetch the manifest API JSON for a game.
     * Returns raw JSON string (pass to EpicDownloadManager.install()).
     *
     * URL: /launcher/api/public/assets/v2/platform/Windows/namespace/{ns}/catalogItem/{catId}/app/{appName}/label/Live
     *
     * IMPORTANT: <appName> is from the library API records[].appName — NOT from catalog releaseInfo.appId.
     */
    public static String getManifestApiJson(String accessToken,
                                             String namespace,
                                             String catalogItemId,
                                             String appName) {
        try {
            String url = MANIFEST_BASE + "/" + namespace
                    + "/catalogItem/" + catalogItemId
                    + "/app/" + appName
                    + "/label/Live";
            String resp = getWithLegendaryUA(url, accessToken);
            if (resp == null) return null;

            // The real manifests array is inside elements[0].manifests
            // Flatten it for EpicDownloadManager by returning a JSON object with top-level "manifests" array
            JSONObject root = new JSONObject(resp);
            JSONArray elements = root.optJSONArray("elements");
            if (elements == null || elements.length() == 0) return null;

            JSONObject firstElement = elements.getJSONObject(0);
            // Return as a wrapper with top-level "manifests" key that EpicDownloadManager expects
            JSONObject wrapper = new JSONObject();
            wrapper.put("manifests", firstElement.optJSONArray("manifests"));
            // buildVersion is the version identifier in the assets/v2 response
            String ver = firstElement.optString("buildVersion", "");
            if (ver.isEmpty()) ver = firstElement.optString("versionId", ""); // fallback
            wrapper.put("versionId", ver);
            return wrapper.toString();

        } catch (Exception e) {
            Log.e(TAG, "getManifestApiJson failed for " + appName, e);
            return null;
        }
    }

    /**
     * Compute total install size from manifest CDN listing.
     * Returns 0 if unavailable.
     */
    public static long getInstallSize(String accessToken, EpicGame game) {
        try {
            String manifestJson = getManifestApiJson(
                    accessToken, game.namespace, game.catalogItemId, game.appName);
            if (manifestJson == null) return 0;

            EpicDownloadManager.EpicManifest.ParsedManifest manifest =
                    EpicDownloadManager.EpicManifest.parseManifestApiJson(manifestJson, accessToken);
            if (manifest == null) return 0;

            // Sum windowSize (uncompressed/installed size), fall back to fileSize if zero
            long total = 0;
            for (EpicDownloadManager.ChunkInfo c : manifest.uniqueChunks) {
                total += c.windowSize > 0 ? c.windowSize : c.fileSize;
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Pull the raw CloudSaveFolder token string out of a catalog item's customAttributes, or "" if
     * the title isn't cloud-save enabled. The Windows-platform key is preferred; a bare
     * `CloudSaveFolder` is the common shape.
     */
    private static String parseCloudSaveFolder(JSONObject customAttributes) {
        String[] keys = { "CloudSaveFolder", "CloudSaveFolder_Windows" };
        for (String k : keys) {
            JSONObject attr = customAttributes.optJSONObject(k);
            if (attr == null) continue;
            String value = attr.optString("value", "");
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    /**
     * On-demand fetch of just the CloudSaveFolder token string for a game (namespace + catalogItemId),
     * for the Save Manager Epic tab when the store list was never enriched this install. Returns "" if
     * unavailable / not cloud-save enabled. Network call — off the main thread.
     */
    public static String getCloudSaveFolder(String accessToken, String namespace, String catalogItemId) {
        if (namespace == null || namespace.isEmpty() || catalogItemId == null || catalogItemId.isEmpty()) return "";
        try {
            String url = CATALOG_BASE + "/" + namespace
                    + "/bulk/items?id=" + catalogItemId
                    + "&includeMainGameDetails=true&country=US";
            String resp = getWithLegendaryUA(url, accessToken);
            if (resp == null) return "";
            JSONObject root = new JSONObject(resp);
            JSONObject item = root.optJSONObject(catalogItemId);
            if (item == null && root.length() > 0) item = root.optJSONObject(root.keys().next());
            if (item == null) return "";
            JSONObject attrs = item.optJSONObject("customAttributes");
            if (attrs == null) return "";
            return parseCloudSaveFolder(attrs);
        } catch (Exception e) {
            Log.e(TAG, "getCloudSaveFolder failed", e);
            return "";
        }
    }

    // ── HTTP helper with Legendary User-Agent ─────────────────────────────────

    static String getWithLegendaryUA(String urlStr, String accessToken) {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("User-Agent",    LEGENDARY_UA);

            int code = conn.getResponseCode();
            java.io.InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            conn.disconnect();

            if (code < 200 || code >= 300) {
                Log.e(TAG, "Epic GET HTTP " + code + " from " + StoreLog.redactUrl(urlStr) + " (error body suppressed)");
                return null;
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Epic getWithLegendaryUA failed: " + StoreLog.redactUrl(urlStr) + " (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }
}
