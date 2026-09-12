package com.winlator.star.core;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Android logcat capture, in the two forms the device actually allows.
 *
 * <b>Without root</b> an app may only read its OWN output, which {@code --pid=<our pid>} selects.
 * That needs no permission and no su — the technique is GameNative's ({@code CrashHandler.kt}). It
 * still catches our native layers and crashes, but it will NOT contain system-wide GPU driver
 * messages, so the UI must not promise otherwise.
 *
 * <b>With root</b> the pid filter is dropped and the whole buffer is read through
 * {@link com.winlator.star.perf.RootManager}, which is the app's single su path.
 *
 * Capture is a one-shot dump ({@code logcat -d}), never a live stream: a streaming reader would mean
 * a subprocess and continuous I/O for the entire session, which is exactly the cost this feature is
 * supposed to avoid. Ticking "logcat" therefore does not slow a game down.
 */
public final class LogcatCapture {

    private static final String TAG = "LogcatCapture";
    /** Lines to take. Matches GameNative's crash-report depth; plenty for a diagnosis, small on disk. */
    public static final int DEFAULT_LINES = 10000;

    private LogcatCapture() {}

    public static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
    }

    /**
     * Strip credentials and PII before anything reaches a file the user is expected to SHARE.
     *
     * Reuses {@link com.winlator.star.store.SteamLogRedactor}, which was written for the Steam
     * diagnostic files: exact-match on the registered username/refresh token, plus patterns for
     * emails, JWTs, long opaque tokens and SteamID64s.
     *
     * ⚠️ Its guarantee is bounded. Exact-match only knows secrets THIS app registered, and the
     * patterns are generic. A system-wide (rooted) capture contains other apps' output, whose
     * secrets we cannot know or match — see the warning on the root capture option in the UI.
     */
    public static String redact(String line) {
        try {
            return com.winlator.star.store.SteamLogRedactor.redact(line);
        } catch (Throwable t) {
            // Redaction failing must not silently produce an UNREDACTED file — drop the line.
            return "[line withheld: redaction failed]";
        }
    }

    /**
     * Dump the last {@code lines} of OUR OWN logcat output.
     *
     * Deliberately app-scoped even when root is available. A system-wide capture would contain other
     * apps' log output — their tokens, their notification text — which our redactor cannot know or
     * pattern-match, and these files exist to be shared. Users with root who want the full buffer
     * have their own logcat readers; that is the right tool for it, and it keeps other people's data
     * out of a file we generate and invite them to post.
     *
     * Never throws; returns a readable message on failure so the captured file explains itself
     * instead of being mysteriously empty.
     */
    public static String capture(int lines) {
        String cmd = "logcat -d -t " + lines + " --pid=" + android.os.Process.myPid();
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                // Redact per line rather than once at the end: the registered-secret pass is a
                // substring scan, and running it over a multi-megabyte single string is needlessly
                // slow when the same work per line is linear and streams.
                while ((line = r.readLine()) != null) sb.append(redact(line)).append('\n');
            }
            p.destroy();
            return sb.length() == 0 ? "(logcat returned no lines)" : sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "logcat capture failed", e);
            return "Failed to capture logcat: " + e.getMessage();
        }
    }

    /** The device/app header worth having at the top of every captured log and crash report. */
    public static String deviceHeader(Context context) {
        String appVersion;
        try {
            android.content.pm.PackageInfo pi =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            appVersion = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception e) {
            appVersion = "unknown";
        }
        return "=== Bannerlator log ===\n"
                + "Captured: " + timestamp() + "\n"
                + "App: " + appVersion + "\n"
                + "Device: " + deviceName() + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                + "Scope: Bannerlator only (system-wide capture is deliberately not offered)\n\n";
    }

    /**
     * Manufacturer/brand/model without the repetition. Vendors set all three, and they overlap far
     * more often than not — on the AYANEO Pocket FIT the naive join reads "AYANEO AYANEO Pocket FIT",
     * and plenty of OEMs already bake the brand into MODEL. Keep the first occurrence of each token.
     */
    private static String deviceName() {
        StringBuilder out = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String part : new String[]{Build.MANUFACTURER, Build.BRAND, Build.MODEL}) {
            if (part == null) continue;
            for (String token : part.trim().split("\\s+")) {
                if (token.isEmpty() || !seen.add(token.toLowerCase(Locale.US))) continue;
                if (out.length() > 0) out.append(' ');
                out.append(token);
            }
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }

    /**
     * Capture to {@code <log dir>/_app/logcat.log}. Returns the file, or null if it could not be
     * written. Call off the main thread — it spawns a process and writes a file.
     */
    public static File captureToFile(Context context, int lines) {
        try {
            File dir = LogLocation.resolveAppLogDir(context);
            File out = new File(dir, "logcat.log");
            FileUtils.writeString(out, deviceHeader(context) + capture(lines));
            return out;
        } catch (Exception e) {
            Log.w(TAG, "could not write logcat file", e);
            return null;
        }
    }
}
