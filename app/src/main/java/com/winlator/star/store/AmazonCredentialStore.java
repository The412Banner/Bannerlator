package com.winlator.star.store;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Persists Amazon OAuth2 credentials to:
 *   context.getFilesDir()/amazon/credentials.json
 *
 * Format:
 * {
 *   "access_token":  "...",
 *   "refresh_token": "...",
 *   "device_serial": "...",
 *   "client_id":     "...",
 *   "expires_at":    1234567890000   // epoch millis
 * }
 */
public class AmazonCredentialStore {

    private static final String TAG       = "BH_AMAZON";
    private static final String DIR_NAME  = "amazon";
    private static final String FILE_NAME = "credentials.json";

    public static class Credentials {
        public String accessToken;
        public String refreshToken;
        public String deviceSerial;
        public String clientId;
        public long   expiresAt;   // epoch millis
    }

    public static void save(Context ctx, Credentials creds) {
        try {
            JSONObject json = new JSONObject();
            json.put("access_token",  creds.accessToken);
            json.put("refresh_token", creds.refreshToken);
            json.put("device_serial", creds.deviceSerial);
            json.put("client_id",     creds.clientId);
            json.put("expires_at",    creds.expiresAt);

            File dir = new File(ctx.getFilesDir(), DIR_NAME);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(new File(dir, FILE_NAME))) {
                fos.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save credentials", e);
        }
    }

    public static Credentials load(Context ctx) {
        try {
            File file = new File(new File(ctx.getFilesDir(), DIR_NAME), FILE_NAME);
            if (!file.exists()) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            JSONObject json = new JSONObject(sb.toString());
            Credentials creds = new Credentials();
            creds.accessToken  = json.optString("access_token",  null);
            creds.refreshToken = json.optString("refresh_token", null);
            creds.deviceSerial = json.optString("device_serial", null);
            creds.clientId     = json.optString("client_id",     null);
            creds.expiresAt    = json.optLong("expires_at",      0L);
            return creds;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load credentials", e);
            return null;
        }
    }

    public static void clear(Context ctx) {
        try {
            File file = new File(new File(ctx.getFilesDir(), DIR_NAME), FILE_NAME);
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear credentials", e);
        }
    }

    /** Returns true if a non-null access_token is stored (ignores expiry — refresh handles that). */
    public static boolean isLoggedIn(Context ctx) {
        Credentials creds = load(ctx);
        return creds != null
            && creds.accessToken != null
            && !creds.accessToken.isEmpty();
    }

    /**
     * Returns a valid access token, refreshing it if within 5 minutes of expiry.
     * Returns null if not logged in or refresh fails.
     */
    public static String getValidAccessToken(Context ctx) {
        Credentials creds = load(ctx);
        if (creds == null || creds.accessToken == null) return null;

        long fiveMinutesMs = 5L * 60L * 1000L;
        boolean nearExpiry = (creds.expiresAt - System.currentTimeMillis()) < fiveMinutesMs;

        if (nearExpiry && creds.refreshToken != null) {
            Log.d(TAG, "Token near expiry, refreshing...");
            AmazonAuthClient.RegisterResult result =
                    AmazonAuthClient.refreshAccessToken(creds.refreshToken);
            if (result != null) {
                creds.accessToken = result.accessToken;
                creds.expiresAt   = System.currentTimeMillis() + (result.expiresIn * 1000L);
                // refreshToken unchanged — NOT returned in refresh response
                save(ctx, creds);
                Log.d(TAG, "Token refreshed OK");
            }
        }

        return creds.accessToken;
    }
}
