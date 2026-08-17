package com.winlator.star.core;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Post-mortem crash capture WITHOUT root, via {@link ActivityManager#getHistoricalProcessExitReasons}.
 *
 * <p>The Android system records why every app process last died — including <b>native</b> crashes
 * (SIGSEGV/SIGABRT/…) that die in a separate {@code crash_dump} process and so never appear in the
 * app's own logcat, and that the Java {@link CrashReporter} (a {@code UncaughtExceptionHandler})
 * cannot see either. On the NEXT launch we can read that record and, for a native crash, decode the
 * tombstone from {@link ApplicationExitInfo#getTraceInputStream()}. No permission, no su — the one
 * way to record a native crash on an unrooted device.
 *
 * <p>The history is <b>system-retained</b> across the crash+restart, so this still works when reading
 * is triggered only AFTER the crash. Needs Android 11 (API 30 / R).
 *
 * <p>Adapted from WinNative's {@code LogManager.logLastExitReasons} (both apps GPL-3.0). The native
 * trace is a Tombstone protobuf, decoded below into the crashing thread's symbolized backtrace.
 */
public final class ExitReasonReporter {

    private static final String TAG = "ExitReasonReporter";
    /** Own subfolder next to the other logs, so these never mix with per-game or app logcat files. */
    public static final String FOLDER = "exit-reasons";
    /** Auto-write the report on launch. OFF by default — the data is system-retained, so opt-in
     *  after a crash still surfaces it; the toggle only controls whether we file it automatically. */
    public static final String PREF_AUTOSAVE = "exit_reasons_autosave";

    private static final int MAX_RECORDS = 8;
    private static final int MAX_FRAMES = 64;          // per thread
    private static final int MAX_TRACE_BYTES = 2 * 1024 * 1024;

    private ExitReasonReporter() {}

    /** True when the API this relies on exists (Android 11 / R). */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * Build a human-readable report of the most recent process exits. Never throws; returns a readable
     * message on failure / when unsupported so a saved file always explains itself.
     */
    @SuppressLint("NewApi") // every ApplicationExitInfo use is behind the isSupported() gate below
    public static String capture(Context context) {
        if (!isSupported())
            return "Exit-reason capture needs Android 11 (SDK 30) or newer — this device is SDK "
                    + Build.VERSION.SDK_INT + ".";
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> infos =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, MAX_RECORDS);
            if (infos == null || infos.isEmpty())
                return "No exit records yet — the system has not recorded a previous exit for this app.";

            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (ApplicationExitInfo info : infos) {
                sb.append("--- exit #").append(i++).append(" ---\n");
                sb.append("when      : ").append(fmt.format(new Date(info.getTimestamp()))).append('\n');
                sb.append("reason    : ").append(reasonName(info.getReason()))
                  .append(" (").append(info.getReason()).append(")\n");
                sb.append("desc      : ").append(String.valueOf(info.getDescription())).append('\n');
                sb.append("importance: ").append(info.getImportance()).append('\n');
                sb.append("memory    : pss ").append(info.getPss())
                  .append(" KB / rss ").append(info.getRss()).append(" KB\n");
                if (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE)
                    sb.append(traceExcerpt(info));
                sb.append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "capture failed", t);
            return "Failed to read exit reasons: " + t.getMessage();
        }
    }

    @SuppressLint("NewApi")
    private static String traceExcerpt(ApplicationExitInfo info) {
        byte[] data;
        InputStream in = null;
        try {
            in = info.getTraceInputStream();
            if (in == null) return "native trace: (no trace stream available)\n";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r, total = 0;
            while ((r = in.read(buf)) != -1 && total < MAX_TRACE_BYTES) {
                bos.write(buf, 0, r);
                total += r;
            }
            data = bos.toByteArray();
        } catch (Throwable t) {
            return "native trace: (could not read: " + t.getMessage() + ")\n";
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
        // A NATIVE crash trace is a Tombstone protobuf, NOT text — decode the crashing thread + frames.
        try {
            String decoded = summarizeTombstone(data);
            if (decoded != null && !decoded.isEmpty()) return decoded;
        } catch (Throwable t) {
            Log.w(TAG, "tombstone decode failed", t);
        }
        // Fallback: printable strings, so a decode miss still yields something usable rather than binary.
        return "native trace (proto decode unavailable; printable strings):\n"
                + printableStrings(data, 6000);
    }

    // ── Minimal Tombstone-protobuf decoder ────────────────────────────────────────────────────────
    // getTraceInputStream() for a native crash returns a Tombstone protobuf. We walk the wire format
    // for only the fields we need and render the crashing thread's symbolized backtrace. Field numbers
    // per AOSP system/core/debuggerd/proto/tombstone.proto (append-only, so stable across versions):
    //   Tombstone : tid=6, signal_info=10, abort_message=14, threads(map)=16
    //   Signal    : name=2, code_name=4, fault_address=9
    //   Thread    : id=1, name=2, current_backtrace=4
    //   Frame     : pc=2, function_name=4, function_offset=5, file_name=6
    // Any parse trouble returns null so the caller falls back to raw strings.

    private static String summarizeTombstone(byte[] d) {
        long[] tid = { -1 };
        String[] sig = { null };
        String[] abort = { null };
        LinkedHashMap<Long, String[]> threads = new LinkedHashMap<>(); // id -> {name, framesText}
        parseTombstone(new Cur(d, 0, d.length), tid, sig, abort, threads);
        if (sig[0] == null && threads.isEmpty()) return null;

        StringBuilder out = new StringBuilder();
        out.append("signal      : ").append(sig[0] != null ? sig[0] : "(unknown)").append('\n');
        if (abort[0] != null && !abort[0].isEmpty())
            out.append("abort msg   : ").append(LogcatCapture.redact(abort[0])).append('\n');
        out.append("crashing tid: ").append(tid[0]).append('\n');

        String[] crash = threads.get(tid[0]);
        if (crash != null && crash[1] != null && !crash[1].isEmpty()) {
            out.append("\n>>> thread ").append(tid[0]).append(" \"").append(crash[0])
               .append("\" (CRASHED) <<<\n").append(crash[1]);
        } else {
            // tid didn't resolve to a thread with frames — show the first thread that has any.
            for (Map.Entry<Long, String[]> e : threads.entrySet()) {
                if (e.getValue()[1] != null && !e.getValue()[1].isEmpty()) {
                    out.append("\n--- thread ").append(e.getKey()).append(" \"").append(e.getValue()[0])
                       .append("\" ---\n").append(e.getValue()[1]);
                    break;
                }
            }
        }
        if (threads.size() > 1) {
            out.append("\nthreads present: ");
            boolean first = true;
            for (Map.Entry<Long, String[]> e : threads.entrySet()) {
                if (!first) out.append(", ");
                first = false;
                out.append(e.getValue()[0]).append('(').append(e.getKey()).append(')');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void parseTombstone(Cur c, long[] tid, String[] sig, String[] abort,
                                       LinkedHashMap<Long, String[]> threads) {
        while (c.has()) {
            long tag = varint(c);
            int field = (int) (tag >>> 3), wt = (int) (tag & 7);
            if (wt == 2) {
                int[] sl = slice(c);
                if (field == 10) sig[0] = parseSignal(new Cur(c.b, sl[0], sl[1]));
                else if (field == 14) abort[0] = utf8(c.b, sl[0], sl[1]);
                else if (field == 16) parseThreadEntry(new Cur(c.b, sl[0], sl[1]), threads);
            } else if (wt == 0) {
                long v = varint(c);
                if (field == 6) tid[0] = v;
            } else skip(c, wt);
        }
    }

    private static String parseSignal(Cur c) {
        String name = null, code = null; long fault = 0; boolean hasFault = false;
        while (c.has()) {
            long tag = varint(c);
            int f = (int) (tag >>> 3), wt = (int) (tag & 7);
            if (wt == 2) {
                int[] sl = slice(c); String s = utf8(c.b, sl[0], sl[1]);
                if (f == 2) name = s; else if (f == 4) code = s;
            } else if (wt == 0) {
                long v = varint(c); if (f == 9) { fault = v; hasFault = true; }
            } else skip(c, wt);
        }
        StringBuilder sb = new StringBuilder(name != null ? name : "?");
        if (code != null) sb.append(" (").append(code).append(')');
        if (hasFault) sb.append(", fault addr 0x").append(Long.toHexString(fault));
        return sb.toString();
    }

    private static void parseThreadEntry(Cur c, LinkedHashMap<Long, String[]> threads) {
        long key = -1; int[] tSlice = null;
        while (c.has()) {
            long tag = varint(c);
            int f = (int) (tag >>> 3), wt = (int) (tag & 7);
            if (wt == 0) { long v = varint(c); if (f == 1) key = v; }
            else if (wt == 2) { int[] sl = slice(c); if (f == 2) tSlice = sl; }
            else skip(c, wt);
        }
        if (tSlice != null) {
            String[] t = parseThread(new Cur(c.b, tSlice[0], tSlice[1])); // {name, frames, id}
            if (key < 0) try { key = Long.parseLong(t[2]); } catch (Exception ignored) {}
            threads.put(key, new String[]{ t[0], t[1] });
        }
    }

    private static String[] parseThread(Cur c) {
        String name = "?"; long id = -1; StringBuilder frames = new StringBuilder(); int n = 0;
        while (c.has()) {
            long tag = varint(c);
            int f = (int) (tag >>> 3), wt = (int) (tag & 7);
            if (wt == 0) { long v = varint(c); if (f == 1) id = v; }
            else if (wt == 2) {
                int[] sl = slice(c);
                if (f == 2) name = utf8(c.b, sl[0], sl[1]);
                else if (f == 4 && n < MAX_FRAMES) { frames.append(frame(new Cur(c.b, sl[0], sl[1]), n)); n++; }
            } else skip(c, wt);
        }
        return new String[]{ name, frames.toString(), Long.toString(id) };
    }

    private static String frame(Cur c, int idx) {
        long pc = 0, off = 0; String fn = null, file = null;
        while (c.has()) {
            long tag = varint(c);
            int f = (int) (tag >>> 3), wt = (int) (tag & 7);
            if (wt == 0) { long v = varint(c); if (f == 2) pc = v; else if (f == 5) off = v; }
            else if (wt == 2) {
                int[] sl = slice(c); String s = utf8(c.b, sl[0], sl[1]);
                if (f == 4) fn = s; else if (f == 6) file = s;
            } else skip(c, wt);
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "  #%02d pc 0x%x  ", idx, pc));
        sb.append(file != null && !file.isEmpty() ? file : "?");
        if (fn != null && !fn.isEmpty()) sb.append(" (").append(fn).append('+').append(off).append(')');
        return sb.append('\n').toString();
    }

    // Wire-format primitives. Cur is a bounded cursor over the byte[]; every read advances c.p.
    private static final class Cur {
        final byte[] b; int p; final int end;
        Cur(byte[] b, int p, int end) { this.b = b; this.p = p; this.end = end; }
        boolean has() { return p < end; }
    }

    private static long varint(Cur c) {
        long r = 0; int s = 0;
        while (c.p < c.end) {
            int x = c.b[c.p++] & 0xff;
            r |= ((long) (x & 0x7f)) << s;
            if ((x & 0x80) == 0) break;
            s += 7;
            if (s > 63) break;
        }
        return r;
    }

    /** Length-delimited field: returns {start, end} of the payload and advances past it. */
    private static int[] slice(Cur c) {
        int len = (int) varint(c);
        int s = c.p;
        int e = Math.min(c.p + len, c.end);
        c.p = e;
        return new int[]{ s, e };
    }

    private static void skip(Cur c, int wt) {
        if (wt == 0) varint(c);
        else if (wt == 1) c.p += 8;
        else if (wt == 2) { int len = (int) varint(c); c.p += len; }
        else if (wt == 5) c.p += 4;
        else c.p = c.end; // unknown wire type: stop this message
    }

    private static String utf8(byte[] b, int s, int e) {
        try { return new String(b, s, Math.max(0, e - s), StandardCharsets.UTF_8).trim(); }
        catch (Throwable t) { return ""; }
    }

    /** Last-resort readable extraction: printable ASCII runs of length >= 4, one per line, redacted. */
    private static String printableStrings(byte[] d, int cap) {
        StringBuilder sb = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < d.length && sb.length() < cap; i++) {
            int ch = d[i] & 0xff;
            if (ch >= 0x20 && ch < 0x7f) cur.append((char) ch);
            else {
                if (cur.length() >= 4) sb.append(LogcatCapture.redact(cur.toString())).append('\n');
                cur.setLength(0);
            }
        }
        if (cur.length() >= 4) sb.append(cur);
        return sb.toString();
    }

    @SuppressLint("NewApi")
    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH:             return "JAVA_CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:      return "NATIVE_CRASH";
            case ApplicationExitInfo.REASON_ANR:               return "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY:        return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_SIGNALED:          return "SIGNALED";
            case ApplicationExitInfo.REASON_USER_REQUESTED:    return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED:      return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:   return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_EXIT_SELF:         return "EXIT_SELF";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_OTHER:             return "OTHER";
            case 7:  return "INITIALIZATION_FAILURE";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "UNKNOWN";
        }
    }

    /** Where the exit-reason reports are filed: {@code <log dir>/exit-reasons/}. */
    public static File folder(Context context) {
        File dir = new File(LogLocation.resolveLogDir(context), FOLDER);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Write a timestamped exit-reason report. Returns the file, or null on failure / unsupported.
     * Call off the main thread — it reads a system stream and writes a file.
     */
    public static File captureToFile(Context context) {
        if (!isSupported()) return null;
        try {
            File out = new File(folder(context), "exit-reasons-" + LogcatCapture.timestamp() + ".log");
            FileUtils.writeString(out, LogcatCapture.deviceHeader(context) + capture(context));
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "could not write exit-reasons file", t);
            return null;
        }
    }
}
