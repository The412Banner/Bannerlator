package com.winlator.star.xenvironment.components;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.star.core.EnvVars;
import com.winlator.star.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Test (feat/yserver-spike): yserver as the X server instead of the Java one. BANNER_YSERVER=1 in the
 * container's or the game's env: yserver runs rootless as a client of the embedded Wayland compositor,
 * which shows its windows; the guest stays on winex11. BANNER_YSERVER=record: headless with the frame
 * recorder (no picture). A binary staged in files/yserver/ wins over the one in the APK. Logs go to
 * files/yserver/logs/.
 */
public class YServerComponent extends EnvironmentComponent {
    private static final String TAG = "YServerComponent";
    public static final String MODE_HOSTED = "hosted";
    public static final String MODE_RECORD = "record";

    private final Context context;
    private final String mode;
    private final File tmpRoot;
    private final File waylandRuntimeDir;
    private Process process;

    public YServerComponent(Context context, String mode, File tmpRoot, File waylandRuntimeDir) {
        this.context = context.getApplicationContext();
        this.mode = mode;
        this.tmpRoot = tmpRoot;
        this.waylandRuntimeDir = waylandRuntimeDir;
    }

    /** The mode BANNER_YSERVER asks for (the game's value wins), or null when off. */
    public static String requestedMode(String containerEnv, String shortcutEnv) {
        String value = null;
        for (String raw : new String[]{containerEnv, shortcutEnv}) {
            if (raw == null || raw.isEmpty()) continue;
            EnvVars env = new EnvVars(raw);
            if (env.has("BANNER_YSERVER")) value = env.get("BANNER_YSERVER");
        }
        if (value == null) return null;
        value = value.trim();
        if (value.isEmpty() || value.equals("0") || value.equalsIgnoreCase("off")) return null;
        return value.equalsIgnoreCase(MODE_RECORD) ? MODE_RECORD : MODE_HOSTED;
    }

    public static File home(Context context) {
        return new File(context.getFilesDir(), "yserver");
    }

    /** files/yserver/yserver when staged by hand, else the APK's libyserver.so; null when neither. */
    public static File binary(Context context) {
        File staged = new File(home(context), "yserver");
        if (staged.canExecute()) return staged;
        File bundled = new File(context.getApplicationInfo().nativeLibraryDir, "libyserver.so");
        return bundled.canExecute() ? bundled : null;
    }

    /** XKB data: files/yserver/xkb when staged, else the imagefs copy. */
    private File xkbRoot() {
        File staged = new File(home(context), "xkb");
        if (new File(staged, "rules").isDirectory()) return staged;
        return new File(tmpRoot.getParentFile(), "share/X11/xkb");
    }

    @Override
    public void start() {
        if (process != null) return;
        File bin = binary(context);
        if (bin == null) {
            Log.e(TAG, "no yserver binary (files/yserver/yserver or libyserver.so)");
            return;
        }
        File socket = new File(tmpRoot, ".X11-unix/X0");
        socket.delete();
        new File(tmpRoot, ".X0-lock").delete();
        File logs = new File(home(context), "logs");
        logs.mkdirs();
        pruneLogs(logs, 12);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File log = new File(logs, "yserver-" + stamp + ".log");

        List<String> args = new ArrayList<>();
        args.add(bin.getPath());
        args.add(":0");
        args.add("-nolisten");
        args.add("tcp");
        if (MODE_RECORD.equals(mode)) {
            args.add("--headless");
            args.add("--rootless");
            args.add("--rootless-record");
            args.add(new File(logs, "rec-" + stamp).getPath());
        } else {
            args.add("--rootless");
        }
        ProcessBuilder pb = new ProcessBuilder(args);
        Map<String, String> env = pb.environment();
        env.put("YSERVER_TMPDIR", tmpRoot.getPath());
        env.put("XKB_CONFIG_ROOT", xkbRoot().getPath());
        String rustLog = System.getProperty("banner.yserver.log");
        env.put("RUST_LOG", rustLog != null ? rustLog : "info");
        if (MODE_RECORD.equals(mode)) {
            env.put("YSERVER_RECORD_EVERY", "30");
            env.put("YSERVER_RECORD_MAX", "40");
        } else {
            env.put("XDG_RUNTIME_DIR", waylandRuntimeDir.getPath());
            env.put("WAYLAND_DISPLAY", "wayland-0");
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(log);

        long t0 = SystemClock.uptimeMillis();
        if (!MODE_RECORD.equals(mode)) {
            // The compositor binds its socket once its surface exists (startWaylandCompositor).
            // A stale socket file can be left from an earlier session, so wait until one accepts.
            File wl = new File(waylandRuntimeDir, "wayland-0");
            boolean up = false;
            while (!(up = socketAccepts(wl)) && SystemClock.uptimeMillis() - t0 < 15000) SystemClock.sleep(25);
            Log.i(TAG, "compositor socket " + (up ? "up" : "NOT accepting") + " after "
                    + (SystemClock.uptimeMillis() - t0) + " ms");
        }
        try {
            process = pb.start();
        } catch (IOException e) {
            Log.e(TAG, "yserver did not start", e);
            return;
        }
        long t1 = SystemClock.uptimeMillis();
        while (!socket.exists() && process.isAlive() && SystemClock.uptimeMillis() - t1 < 8000) {
            SystemClock.sleep(10);
        }
        Log.i(TAG, "yserver (" + mode + ", " + bin + ") " + (socket.exists() ? "ready" : "NOT ready")
                + " after " + (SystemClock.uptimeMillis() - t1) + " ms, alive=" + process.isAlive() + ", log " + log);
    }

    @Override
    public void stop() {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        Log.i(TAG, "yserver stopped");
        process = null;
    }

    private static boolean socketAccepts(File path) {
        if (!path.exists()) return false;
        android.net.LocalSocket s = new android.net.LocalSocket();
        try {
            s.connect(new android.net.LocalSocketAddress(path.getPath(), android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static void pruneLogs(File dir, int keep) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= keep) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < files.length; i++) deleteTree(files[i]);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
