package com.winlator.star.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.star.BuildConfig;

import org.json.JSONObject;

import java.io.File;
import java.util.UUID;

/**
 * App-side wiring for the win-fg "training capture" mode (crowdsourced, opt-in frame-gen training data).
 *
 * The bundled Vulkan frame-gen layer (libwin_fg.so) is gaining a CAPTURE MODE that dumps raw
 * pre-interpolation game frames as a single lossless packed training file. That mode is gated by the
 * layer reading {@code WIN_FG_CAPTURE=1} (env) / conf.toml {@code capture=on}, with the output
 * directory from {@code WIN_FG_CAPTURE_DIR}. This class owns the APP half of that contract:
 *
 * <ul>
 *   <li>a single GLOBAL (not per-container) opt-in toggle, persisted in the default prefs;</li>
 *   <li>an ANONYMOUS consent-attestation record (NO PII — no account/name/email) generated when the
 *       user agrees, handed to the layer two ways — the {@code WIN_FG_CAPTURE_CONSENT} env var and a
 *       {@code consent.json} in the capture dir — so the layer can embed provenance ("willingly
 *       created + shared, agreeing to terms vX") into the capture container + manifest;</li>
 *   <li>the capture output directory under {@code Download/win-fg} (MANAGE_EXTERNAL_STORAGE — the same
 *       public-Download mechanism the app already uses for logs/backups/community configs).</li>
 * </ul>
 *
 * NOTE (device wiring): the bundled {@code libwin_fg.so} on main NOW carries the capture path (its
 * binary exports the {@code capture_shard_mb}/{@code wfgcap} symbols), so capture arms from this
 * wiring. It piggy-backs on the win-fg layer, which only loads when the game's frame-gen engine is
 * "bionic" (Win-FG) — capture does nothing on an Off/LSFG game. The env/conf keys below are the
 * contract the capture .so honors.
 */
public final class WinFgCapture {
    private WinFgCapture() {}

    private static final String TAG = "WinFgCapture";

    // ── Prefs (global; default SharedPreferences) ──────────────────────────────
    /** Global opt-in toggle (default false). */
    public static final String PREF_ENABLED         = "winfg_capture_enabled";
    /** Random anonymous contribution id, generated + persisted once per install. */
    public static final String PREF_ANON_ID         = "winfg_capture_anon_id";
    /** Terms version the user last agreed to (maps to the versioned consent string resource). */
    public static final String PREF_CONSENT_VERSION = "winfg_capture_consent_version";
    /** Epoch-ms timestamp of that agreement. */
    public static final String PREF_CONSENT_TS      = "winfg_capture_consent_ts";
    /** Capture resolution selection (default {@link #RES_MATCH}). */
    public static final String PREF_CAPTURE_RES     = "winfg_capture_res";
    /** Rolling-shard size cap in MiB (default {@link #DEFAULT_CAPTURE_SHARD_MB}); bounds per-file size. */
    public static final String PREF_CAPTURE_SHARD_MB = "winfg_capture_shard_mb";

    // ── win-fg layer contract (reconcile these with the capture .so) ───────────
    public static final String ENV_CAPTURE         = "WIN_FG_CAPTURE";          // "1" while recording
    public static final String ENV_CAPTURE_DIR     = "WIN_FG_CAPTURE_DIR";      // output directory
    public static final String ENV_CAPTURE_CONSENT = "WIN_FG_CAPTURE_CONSENT";  // compact consent record
    public static final String ENV_CAPTURE_W       = "WIN_FG_CAPTURE_W";        // target box width  (px)
    public static final String ENV_CAPTURE_H       = "WIN_FG_CAPTURE_H";        // target box height (px)
    public static final String ENV_CAPTURE_SHARD_MB = "WIN_FG_CAPTURE_SHARD_MB"; // rolling shard size cap (MiB)
    /** conf.toml gate values (mirror the env). */
    public static final String CONF_CAPTURE_ON  = "on";
    public static final String CONF_CAPTURE_OFF = "off";
    /** conf.toml resolution keys (mirror {@link #ENV_CAPTURE_W}/{@link #ENV_CAPTURE_H}). */
    public static final String CONF_CAPTURE_WIDTH  = "capture_width";
    public static final String CONF_CAPTURE_HEIGHT = "capture_height";
    /** conf.toml rolling-shard size cap key (MiB) — mirrors {@link #ENV_CAPTURE_SHARD_MB}. */
    public static final String CONF_CAPTURE_SHARD_MB = "capture_shard_mb";

    // ── Capture resolution options (global; how the layer sizes the recorded frame) ──
    /** Record at the session's actual resolved render size — native, no downscale (DEFAULT). */
    public static final String RES_MATCH = "match";
    /** Force a 1280×720 target box. */
    public static final String RES_720P  = "720p";
    /** Force a 1920×1080 target box. */
    public static final String RES_1080P = "1080p";
    public static final String DEFAULT_CAPTURE_RES = RES_MATCH;
    /**
     * Default rolling-shard size cap (MiB). The capture layer opens a new .wfgcap file each time the
     * current one passes this, so it bounds per-FILE size (NOT total data). Kept small (256) so a
     * session lands as several manageable files instead of multi-GB shards — friendlier to move/upload.
     * The layer's own built-in default is 1024; the app stamps this smaller value into conf.toml + env.
     * Layer clamps to [16, 65536].
     */
    public static final int DEFAULT_CAPTURE_SHARD_MB = 256;
    // Fixed target boxes for the preset options. The capture .so downscales aspect-preserving to the
    // box and NEVER upscales, so a preset larger than the native frame is effectively native.
    private static final int RES_720P_W  = 1280, RES_720P_H  = 720;
    private static final int RES_1080P_W = 1920, RES_1080P_H = 1080;

    /**
     * The versioned consent terms identifier. Maps to {@code R.string.winfg_capture_consent_v1} — the
     * exact wording the user agreed to. Bump BOTH (this constant + a new string resource) together
     * whenever the terms change, so an embedded {@code consent_version} always maps to known terms.
     */
    public static final String CONSENT_VERSION = "winfg-capture-consent-v1";

    /** Public {@code Download/win-fg} — where capture files (and consent.json) land, for the user to share. */
    public static File captureDir() {
        return new File(Environment.getExternalStorageDirectory(), "Download/win-fg");
    }

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
    }

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(PREF_ENABLED, false);
    }

    /** Turn capture off. (Enabling goes through {@link #recordConsentAndEnable} — consent is mandatory.) */
    public static void disable(Context c) {
        prefs(c).edit().putBoolean(PREF_ENABLED, false).apply();
    }

    /** The persisted capture-resolution selection ({@link #RES_MATCH}/{@link #RES_720P}/{@link #RES_1080P}). */
    public static String captureRes(Context c) {
        String r = prefs(c).getString(PREF_CAPTURE_RES, DEFAULT_CAPTURE_RES);
        return (r == null || r.isEmpty()) ? DEFAULT_CAPTURE_RES : r;
    }

    /** Persist the capture-resolution selection (global, like the toggle). */
    public static void setCaptureRes(Context c, String res) {
        prefs(c).edit().putString(PREF_CAPTURE_RES, res).apply();
    }

    /** The persisted rolling-shard size cap (MiB), default {@link #DEFAULT_CAPTURE_SHARD_MB}. */
    public static int captureShardMb(Context c) {
        int v = prefs(c).getInt(PREF_CAPTURE_SHARD_MB, DEFAULT_CAPTURE_SHARD_MB);
        return v >= 16 ? v : DEFAULT_CAPTURE_SHARD_MB;   // mirror the layer's lower clamp
    }

    /** Persist the rolling-shard size cap (MiB, global like the toggle). */
    public static void setCaptureShardMb(Context c, int mb) {
        prefs(c).edit().putInt(PREF_CAPTURE_SHARD_MB, mb).apply();
    }

    /**
     * Resolve the capture target box (width, height) for the current selection:
     * <ul>
     *   <li>{@link #RES_720P}  → 1280×720</li>
     *   <li>{@link #RES_1080P} → 1920×1080</li>
     *   <li>{@link #RES_MATCH} (default) → the session's actual resolved render size
     *       ({@code gameW}×{@code gameH}) so capture is native (no downscale, best domain match);
     *       falls back to 720p when the render size can't be resolved (either dim ≤ 0).</li>
     * </ul>
     * The capture .so downscales aspect-preserving to this box and never upscales, so passing the
     * native size records at full source resolution.
     */
    public static int[] resolveCaptureSize(Context c, int gameW, int gameH) {
        String res = captureRes(c);
        if (RES_720P.equals(res))  return new int[]{ RES_720P_W,  RES_720P_H  };
        if (RES_1080P.equals(res)) return new int[]{ RES_1080P_W, RES_1080P_H };
        // RES_MATCH (default): native render size, or fall back to 720p if it can't be resolved.
        if (gameW > 0 && gameH > 0) return new int[]{ gameW, gameH };
        return new int[]{ RES_720P_W, RES_720P_H };
    }

    /** The stable random anonymous contribution id (no PII), created once per install and reused forever. */
    public static synchronized String anonId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString(PREF_ANON_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            p.edit().putString(PREF_ANON_ID, id).apply();
        }
        return id;
    }

    /**
     * Persist the anonymous consent attestation and enable capture. Call this only after the user has
     * ticked "I understand" and confirmed in the consent dialog. Records the terms version + agreement
     * timestamp, ensures the anon id exists, flips the toggle on, and writes consent.json into the
     * capture dir so the layer can pick it up even on the very first launch after opting in.
     */
    public static void recordConsentAndEnable(Context c) {
        long ts = System.currentTimeMillis();
        anonId(c); // ensure it exists before we compose the record
        prefs(c).edit()
                .putBoolean(PREF_ENABLED, true)
                .putString(PREF_CONSENT_VERSION, CONSENT_VERSION)
                .putLong(PREF_CONSENT_TS, ts)
                .apply();
        ensureCaptureDir();
        writeConsentJson(c);
    }

    private static String deviceModel() {
        String m = Build.MODEL == null ? "" : Build.MODEL;
        // The compact env form is pipe-delimited; keep the model from breaking the split.
        return m.replace('|', '_').trim();
    }

    private static String appVersion() {
        return BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE;
    }

    private static long consentTs(Context c) {
        long ts = prefs(c).getLong(PREF_CONSENT_TS, 0L);
        return ts > 0 ? ts : System.currentTimeMillis();
    }

    private static String consentVersion(Context c) {
        return prefs(c).getString(PREF_CONSENT_VERSION, CONSENT_VERSION);
    }

    /**
     * Compact consent record for the {@code WIN_FG_CAPTURE_CONSENT} env:
     * <pre>version|epochMs|anonUUID|appVerName+Code|model|agreed</pre>
     * ANONYMOUS by construction — no account, name, email, or other PII. {@code model} is Build.MODEL,
     * included for dataset-diversity stats only.
     */
    public static String consentEnvValue(Context c) {
        return consentVersion(c) + "|" + consentTs(c) + "|" + anonId(c) + "|"
                + appVersion() + "|" + deviceModel() + "|true";
    }

    /** consent.json (the same fields as the env, expanded) written into the capture dir on agreement. */
    public static boolean writeConsentJson(Context c) {
        try {
            File dir = captureDir();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Log.w(TAG, "Could not create capture dir " + dir);
                return false;
            }
            JSONObject o = new JSONObject();
            o.put("consent_version", consentVersion(c));
            o.put("agreed", true);
            o.put("timestamp", consentTs(c));
            o.put("anon_contribution_id", anonId(c));
            o.put("app_version_name", BuildConfig.VERSION_NAME);
            o.put("app_version_code", BuildConfig.VERSION_CODE);
            o.put("device_model", deviceModel());
            return FileUtils.writeString(new File(dir, "consent.json"), o.toString(2));
        } catch (Exception e) {
            Log.e(TAG, "Failed to write consent.json", e);
            return false;
        }
    }

    public static boolean ensureCaptureDir() {
        File dir = captureDir();
        if (dir.isDirectory()) return true;
        boolean ok = dir.mkdirs();
        if (!ok) Log.w(TAG, "Could not create capture dir " + dir);
        return ok;
    }

    /**
     * Wire capture into the game-launch environment. Call this only when the win-fg (bionic) layer is
     * actually being loaded ({@code WIN_FG_ENABLE=1}) — capture piggy-backs on that layer, so it only
     * makes sense with the win-fg frame-gen engine selected. No env is set (and nothing is written)
     * when the toggle is off, so an ordinary launch is untouched. Returns true when capture was armed.
     *
     * <p>{@code gameW}/{@code gameH} are the session's actual resolved render size (from the X server's
     * screen info); they only matter for the {@link #RES_MATCH} selection — see
     * {@link #resolveCaptureSize(Context, int, int)}.
     */
    public static boolean applyLaunchEnv(Context c, EnvVars envVars, int gameW, int gameH) {
        if (!isEnabled(c)) return false;
        ensureCaptureDir();
        writeConsentJson(c); // refresh in case storage perm was granted / the dir was cleared since agreement
        envVars.put(ENV_CAPTURE, "1");
        envVars.put(ENV_CAPTURE_DIR, captureDir().getAbsolutePath());
        envVars.put(ENV_CAPTURE_CONSENT, consentEnvValue(c));
        int[] wh = resolveCaptureSize(c, gameW, gameH);
        envVars.put(ENV_CAPTURE_W, Integer.toString(wh[0]));
        envVars.put(ENV_CAPTURE_H, Integer.toString(wh[1]));
        envVars.put(ENV_CAPTURE_SHARD_MB, Integer.toString(captureShardMb(c)));
        return true;
    }
}
