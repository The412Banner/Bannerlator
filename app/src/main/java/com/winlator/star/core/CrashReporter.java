package com.winlator.star.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

/**
 * Writes a readable crash report when the app dies of an uncaught exception. The app had no crash
 * handler at all before this — a crash left nothing behind but whatever the user managed to catch in
 * logcat before it scrolled away.
 *
 * Modelled on GameNative's {@code CrashHandler.kt}: device and app identification first, then the
 * stack trace, then a logcat tail. That ordering matters — it is what someone filing a bug report
 * needs to paste, and the device block is exactly what the Mali report board asks for.
 *
 * Reports land in {@code <log dir>/_app/} alongside the app logcat, and the oldest are pruned to the
 * same keep-count the rest of the log manager uses.
 *
 * Chains to the previously installed handler, so this never swallows a crash — the app still dies
 * the way it would have, we just leave a note behind first.
 */
public final class CrashReporter implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashReporter";
    /** Public so LogInventory and LogReport recognise our crash reports without re-spelling this. */
    public static final String PREFIX = "crash_";
    /** Fewer lines than a manual capture: a crash report is read by a human, not grepped. */
    private static final int CRASH_LOGCAT_LINES = 400;

    private final Context context;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashReporter(Context context, Thread.UncaughtExceptionHandler previous) {
        this.context = context.getApplicationContext();
        this.previous = previous;
    }

    /** Install once, from the Application. Safe to call more than once. */
    public static void install(Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashReporter) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashReporter(context, current));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            write(thread, throwable);
        } catch (Throwable t) {
            // A failure in here must never replace the real crash.
            Log.w(TAG, "could not write crash report", t);
        }
        if (previous != null) previous.uncaughtException(thread, throwable);
    }

    private void write(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));

        // Redact the exception text and stack trace too, not just the logcat tail: an exception
        // message routinely carries the thing that broke — a URL with a token in the query, a path
        // under the user's account. LogcatCapture.capture() already redacts its own output.
        String body = LogcatCapture.deviceHeader(context)
                + "---------- Cause ----------\n"
                + "Thread: " + thread.getName() + "\n"
                + "Exception: " + throwable.getClass().getName() + "\n"
                + "Message: " + LogcatCapture.redact(String.valueOf(throwable.getMessage())) + "\n\n"
                + "---------- Stack trace ----------\n"
                + LogcatCapture.redact(sw.toString()) + "\n"
                + "---------- Logcat ----------\n"
                // Respect the Log Manager's logcat switch: someone who turned it off does not
                // expect a crash report to contain it anyway.
                + (androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean("enable_logcat", true)
                    ? LogcatCapture.capture(CRASH_LOGCAT_LINES)
                    : "(logcat disabled in Log Manager)");

        File dir = LogLocation.resolveAppLogDir(context);
        File out = new File(dir, PREFIX + LogcatCapture.timestamp() + ".txt");
        FileUtils.writeString(out, body);
        prune(dir, LogLocation.keepLastRuns(context));
        Log.e(TAG, "crash report written to " + out);
    }

    /** Keep the newest {@code keep} reports; delete the rest. */
    private static void prune(File dir, int keep) {
        File[] reports = dir.listFiles(f -> f.isFile() && f.getName().startsWith(PREFIX));
        if (reports == null || reports.length <= Math.max(keep, 1)) return;
        // Filenames carry a sortable timestamp, so name order is chronological and survives a copy.
        Arrays.sort(reports, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = Math.max(keep, 1); i < reports.length; i++) //noinspection ResultOfMethodCallIgnored
            reports[i].delete();
    }
}
