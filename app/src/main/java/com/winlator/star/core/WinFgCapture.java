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
 * NOTE (device wiring): as of this change the bundled {@code libwin_fg.so} on main is still the v0.2
 * quality build with NO capture support — the capture-enabled .so is swapped in separately once it is
 * proven. This class is the toggle wiring; it goes live when that .so lands. The env/conf keys below
 * are the contract the capture .so must honor.
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

    // ── win-fg layer contract (reconcile these with the capture .so) ───────────
    public static final String ENV_CAPTURE         = "WIN_FG_CAPTURE";          // "1" while recording
    public static final String ENV_CAPTURE_DIR     = "WIN_FG_CAPTURE_DIR";      // output directory
    public static final String ENV_CAPTURE_CONSENT = "WIN_FG_CAPTURE_CONSENT";  // compact consent record
    /** conf.toml gate values (mirror the env). */
    public static final String CONF_CAPTURE_ON  = "on";
    public static final String CONF_CAPTURE_OFF = "off";

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
     */
    public static boolean applyLaunchEnv(Context c, EnvVars envVars) {
        if (!isEnabled(c)) return false;
        ensureCaptureDir();
        writeConsentJson(c); // refresh in case storage perm was granted / the dir was cleared since agreement
        envVars.put(ENV_CAPTURE, "1");
        envVars.put(ENV_CAPTURE_DIR, captureDir().getAbsolutePath());
        envVars.put(ENV_CAPTURE_CONSENT, consentEnvValue(c));
        return true;
    }
}
