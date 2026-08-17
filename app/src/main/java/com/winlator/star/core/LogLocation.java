package com.winlator.star.core;

import android.content.Context;
import android.os.Environment;

import androidx.preference.PreferenceManager;

import java.io.File;

/**
 * Central resolver for the folder every log file is written to (issue #70). The tester wanted the
 * Wine debug log — and the DXVK/DXGI/VKD3D logs — landing somewhere a file manager can actually
 * reach, since the default {@code getExternalFilesDir(null)} (Android/data/&lt;pkg&gt;/files) is
 * hidden on Android 12+.
 *
 * The choice lives in the default SharedPreferences under {@link #PREF_MODE} (one of the MODE_*
 * constants) plus {@link #PREF_CUSTOM_PATH} for the "Choose folder…" case. Everything that opens a
 * log goes through {@link #resolveLogDir(Context)}, which mkdirs()+writability-checks the target and
 * silently falls back to the app-data dir so a bad path can never kill logging.
 */
public final class LogLocation {

    public static final String PREF_MODE = "log_location_mode";
    public static final String PREF_CUSTOM_PATH = "log_location_custom_path";

    public static final String MODE_APP_DATA = "app_data";   // getExternalFilesDir(null) — default
    public static final String MODE_DOWNLOAD = "download";    // /sdcard/Download/bannerlator
    public static final String MODE_DOCUMENTS = "documents";  // /sdcard/Documents/bannerlator
    public static final String MODE_CUSTOM = "custom";        // user-picked folder

    private LogLocation() {}

    /**
     * Resolve the configured log directory, creating it if needed. Falls back to
     * {@code getExternalFilesDir(null)} whenever the chosen dir is missing or unwritable.
     */
    public static File resolveLogDir(Context context) {
        File fallback = context.getExternalFilesDir(null);
        try {
            String mode = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(PREF_MODE, MODE_APP_DATA);
            File storage = Environment.getExternalStorageDirectory();
            File dir;
            switch (mode != null ? mode : MODE_APP_DATA) {
                case MODE_DOWNLOAD:
                    dir = new File(storage, "Download/bannerlator");
                    break;
                case MODE_DOCUMENTS:
                    dir = new File(storage, "Documents/bannerlator");
                    break;
                case MODE_CUSTOM:
                    String custom = PreferenceManager.getDefaultSharedPreferences(context)
                            .getString(PREF_CUSTOM_PATH, null);
                    dir = (custom != null && !custom.isEmpty()) ? new File(custom) : fallback;
                    break;
                case MODE_APP_DATA:
                default:
                    dir = fallback;
                    break;
            }
            if (dir == null) return fallback;
            if (!dir.exists()) dir.mkdirs();
            if (dir.isDirectory() && dir.canWrite()) return dir;
        } catch (Exception e) {
            // ignore — degrade to the app-data dir below
        }
        return fallback;
    }
}
