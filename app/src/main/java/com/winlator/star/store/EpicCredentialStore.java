package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Persists Epic OAuth2 credentials in SharedPreferences "bh_epic_prefs".
 *
 * Keys: access_token, refresh_token, account_id, display_name, expires_at (epoch ms)
 */
public class EpicCredentialStore {

    private static final String TAG        = "BH_EPIC";
    private static final String PREFS_NAME = "bh_epic_prefs";

    public static class Credentials {
        public String accessToken;
        public String refreshToken;
        public String accountId;
        public String displayName;
        public long   expiresAt;   // epoch millis
    }

    public static void save(Context ctx, Credentials creds) {
        try {
            ctx.getSharedPreferences(PREFS_NAME, 0).edit()
                    .putString("access_token",  creds.accessToken)
                    .putString("refresh_token", creds.refreshToken)
                    .putString("account_id",    creds.accountId)
                    .putString("display_name",  creds.displayName != null ? creds.displayName : "")
                    .putLong("expires_at",      creds.expiresAt)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save Epic credentials: " + e.getClass().getSimpleName());
        }
    }

    public static Credentials load(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, 0);
            String token = sp.getString("access_token", null);
            if (token == null || token.isEmpty()) return null;

            Credentials creds = new Credentials();
            creds.accessToken  = token;
            creds.refreshToken = sp.getString("refresh_token", null);
            creds.accountId    = sp.getString("account_id",    "");
            creds.displayName  = sp.getString("display_name",  "");
            creds.expiresAt    = sp.getLong("expires_at",      0L);
            return creds;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load Epic credentials: " + e.getClass().getSimpleName());
            return null;
        }
    }

    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, 0).edit()
                .remove("access_token")
                .remove("refresh_token")
                .remove("account_id")
                .remove("display_name")
                .remove("expires_at")
                .apply();
    }

    public static boolean isLoggedIn(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, 0);
        String token = sp.getString("access_token", null);
        return token != null && !token.isEmpty();
    }

    /** One refresh at a time: callers that wait reuse the token the first one stored. */
    private static final Object REFRESH_LOCK = new Object();
    private static final long REFRESH_MARGIN_MS = 5L * 60L * 1000L;

    /**
     * Returns a valid access token, refreshing it when it expires within 5 minutes. When the refresh
     * fails, a token that has not expired yet is still returned; an expired one is not (null: the
     * caller asks the user to sign in again). Null when not logged in.
     */
    public static String getValidAccessToken(Context ctx) {
        Credentials creds = load(ctx);
        if (creds == null || creds.accessToken == null) return null;
        if (creds.expiresAt - System.currentTimeMillis() >= REFRESH_MARGIN_MS) return creds.accessToken;

        synchronized (REFRESH_LOCK) {
            // Another caller may have refreshed while this one waited.
            creds = load(ctx);
            if (creds == null || creds.accessToken == null) return null;
            long now = System.currentTimeMillis();
            if (creds.expiresAt - now >= REFRESH_MARGIN_MS) return creds.accessToken;

            if (creds.refreshToken != null && !creds.refreshToken.isEmpty()) {
                Log.d(TAG, "Epic token near expiry, refreshing...");
                EpicAuthClient.TokenResult result = EpicAuthClient.refreshToken(creds.refreshToken);
                if (result != null && result.accessToken != null && !result.accessToken.isEmpty()) {
                    creds.accessToken  = result.accessToken;
                    creds.refreshToken = result.refreshToken != null ? result.refreshToken : creds.refreshToken;
                    creds.expiresAt    = result.expiresAt;
                    save(ctx, creds);
                    Log.d(TAG, "Epic token refreshed OK");
                    return creds.accessToken;
                }
                Log.w(TAG, "Epic token refresh failed");
            }
            if (creds.expiresAt > now) return creds.accessToken;
            Log.w(TAG, "Epic token expired and could not be refreshed: sign in again");
            return null;
        }
    }
}
