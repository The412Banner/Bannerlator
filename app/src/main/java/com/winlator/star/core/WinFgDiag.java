package com.winlator.star.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.star.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * App-side wiring for diagnosing win-fg freezes/crashes on NON-ROOTED devices.
 *
 * Enabling win-fg frame generation freezes/crashes the app on some GPUs (Adreno 840 / Wrapper and
 * others) while working on Adreno 750. Non-rooted users can't pull tombstones — but an app CAN read
 * its OWN process's logcat (logd only hands a normal app the entries for its own UID). Because the
 * game (XServerDisplayActivity) shares this app's process/UID, that stream contains the native
 * {@code win-fg}-tagged lines, the guest, and the app Java — everything needed to debug a freeze.
 *
 * Two independent, GLOBAL, off-by-default controls (persisted in the default prefs, exactly like
 * {@link WinFgCapture}):
 *
 * <ol>
 *   <li><b>Extra win-fg logging</b> — when on, the game-launch env gets {@code WIN_FG_DEBUG=1} and
 *       conf.toml gets {@code debug=on}, turning on the win-fg layer's verbose present-path logging.
 *       See {@link #applyLaunchEnv} / {@link #confDebugLine}; wired next to the capture flags in
 *       XServerDisplayActivity. No-op when off, so a normal launch is untouched.</li>
 *   <li><b>Diagnostic log capture</b> — STREAMS this process's logcat (all tags, verbose) to a fresh
 *       per-session file under {@code Download/win-fg-logs}. It is a streaming capture (never a
 *       one-shot {@code logcat -d} dump), so a late crash can't roll out of the ring buffer. The
 *       subprocess is a process-lifetime singleton — it survives navigating into the game (same
 *       process) — and is killed on stop and in {@code MainActivity.onDestroy}.</li>
 * </ol>
 *
 * NOTE (device wiring): the {@code WIN_FG_DEBUG=1} / {@code debug=on} contract is honored by a
 * separately-built DEBUG win-fg {@code .so}; this class is only the app half of that contract.
 */
public final class WinFgDiag {
    private WinFgDiag() {}

    private static final String TAG = "WinFgDiag";

    // ── Prefs (global; default SharedPreferences) ──────────────────────────────
    /** "Extra win-fg logging" toggle (default false). */
    public static final String PREF_EXTRA_LOGGING = "winfg_extra_logging";

    // ── win-fg layer contract (reconcile these with the debug .so) ─────────────
    /** Env var the win-fg layer reads to turn on verbose present-path logging. */
    public static final String ENV_DEBUG = "WIN_FG_DEBUG";  // "1" when extra logging is on
    /** conf.toml gate key + values (mirror the env), same lenient on/off form as {@code capture}. */
    public static final String CONF_DEBUG_KEY = "debug";
    public static final String CONF_DEBUG_ON  = "on";
    public static final String CONF_DEBUG_OFF = "off";

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
    }

    // ── Extra win-fg logging toggle ────────────────────────────────────────────
    public static boolean isExtraLoggingEnabled(Context c) {
        return prefs(c).getBoolean(PREF_EXTRA_LOGGING, false);
    }

    public static void setExtraLoggingEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_EXTRA_LOGGING, on).apply();
    }

    /**
     * The conf.toml line to stamp for the extra-logging flag (always {@code debug = on/off}, mirroring
     * how {@code capture} is stamped so an in-game conf.toml rewrite keeps it in sync). Includes the
     * trailing newline.
     */
    public static String confDebugLine(Context c) {
        return CONF_DEBUG_KEY + " = " + (isExtraLoggingEnabled(c) ? CONF_DEBUG_ON : CONF_DEBUG_OFF) + "\n";
    }

    /**
     * Wire the extra-logging flag into the game-launch env. No-op (returns false) when the toggle is
     * off, so an ordinary launch is untouched. Call alongside the win-fg layer load (WIN_FG_ENABLE=1);
     * {@code writeWinFgConfig} stamps the matching {@code debug=on/off} into conf.toml.
     */
    public static boolean applyLaunchEnv(Context c, EnvVars envVars) {
        if (!isExtraLoggingEnabled(c)) return false;
        envVars.put(ENV_DEBUG, "1");
        return true;
    }

    // ── Diagnostic log capture (logcat → Download/win-fg-logs/winfg-log-<ts>.log) ──
    /** Public {@code Download/win-fg-logs} — where the diagnostic log files land, for the user to share. */
    public static File logDir() {
        return new File(Environment.getExternalStorageDirectory(), "Download/win-fg-logs");
    }

    // Streaming state (process-lifetime singleton; all mutation under the class monitor).
    private static Process sLogcat;
    private static Thread sPump;
    private static volatile boolean sStopping;
    private static volatile boolean sRecording;
    private static File sCurrentFile;

    /** Candidate logcat command lines, tried in order until one stays alive (best-effort buffers). */
    private static String[][] candidates() {
        return new String[][] {
            // Preferred: all the buffers we care about, threadtime format, everything at verbose.
            { "logcat", "-v", "threadtime", "-b", "main,crash,system", "*:V" },
            // Fallback: default buffers (some devices reject the explicit -b list above).
            { "logcat", "-v", "threadtime", "*:V" },
            // Barest fallback.
            { "logcat", "-v", "threadtime" },
        };
    }

    /** True while the diagnostic log capture is armed. */
    public static boolean isRecording() {
        return sRecording;
    }

    /** The file the current/last session streamed to (kept after stop so the UI can show the path). */
    public static synchronized File currentFile() {
        return sCurrentFile;
    }

    /**
     * Start streaming this process's logcat to a NEW per-session file under {@code Download/win-fg-logs}.
     * Returns the file being written, or null if it could not be started. If already recording, returns
     * the current file without starting a second stream.
     */
    public static synchronized File startDiagLog(Context c) {
        if (sRecording) return sCurrentFile;
        File dir = logDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create diag log dir " + dir);
            return null;
        }
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        final File out = new File(dir, "winfg-log-" + ts + ".log");
        // Header so the file is meaningful (which device/build) even before the first log line.
        FileUtils.writeString(out, header());
        sCurrentFile = out;
        sStopping = false;
        sRecording = true;
        sPump = new Thread(new Runnable() {
            @Override public void run() { pump(out); }
        }, "winfg-diag-logcat");
        sPump.setDaemon(true);
        sPump.start();
        Log.i(TAG, "Diagnostic log capture started -> " + out);
        return out;
    }

    private static String header() {
        return "==== Bannerlator win-fg diagnostic log ====\n"
                + "captured: " + new Date() + "\n"
                + "app: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") "
                + BuildConfig.APPLICATION_ID + "\n"
                + "device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")\n"
                + "android: " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")\n"
                + "===========================================\n";
    }

    // Runs on the pump thread: start logcat (trying candidate buffer sets in order), stream straight
    // to the file via the OS (redirectOutput) so nothing is buffered in the JVM and the child keeps
    // writing even if the app's Java freezes; fall back to the next candidate if a set is rejected fast.
    private static void pump(File out) {
        for (String[] cmd : candidates()) {
            if (sStopping) break;
            long t0 = System.currentTimeMillis();
            Process p;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);                             // logcat's own errors land in the file too
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out)); // OS writes direct to disk (nothing lost)
                p = pb.start();
            } catch (IOException e) {
                Log.w(TAG, "logcat start failed for " + Arrays.toString(cmd), e);
                continue;
            }
            synchronized (WinFgDiag.class) { sLogcat = p; }
            int code;
            try {
                code = p.waitFor();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            if (sStopping) break;                       // normal stop() destroyed it
            long alive = System.currentTimeMillis() - t0;
            Log.w(TAG, "logcat exited code=" + code + " after " + alive + "ms");
            if (alive >= 4000) break;                   // it ran a while then died — don't spam-restart
            // else: the buffer set was likely rejected → fall through to the next candidate.
        }
        synchronized (WinFgDiag.class) {
            sLogcat = null;
            if (!sStopping) sRecording = false;         // logcat died on its own (all candidates exhausted)
        }
    }

    /** Stop the diagnostic log capture (kill the logcat subprocess). Safe to call when not recording. */
    public static synchronized void stopDiagLog(Context c) {
        sStopping = true;
        sRecording = false;
        Process p = sLogcat;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) {}
        }
        Thread t = sPump;
        if (t != null) t.interrupt();
        sLogcat = null;
        sPump = null;
        // sCurrentFile is intentionally kept so the UI can still show/toast the saved path after stop.
    }
}
