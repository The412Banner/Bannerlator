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
    private final EnvVars gameEnv;
    private final WindowMapListener mapListener;
    private Process process;
    private Thread reader;
    private int frameCapHz = 0;

    /** The session's FPS limit at launch (0 = none): yserver paces each window's presents to it. */
    public void setFrameCap(int hz) {
        frameCapHz = Math.max(0, hz);
    }

    /** A new FPS limit during the session (0 = none): yserver reads its fps file a few times a second. */
    public void setFrameCapLive(int hz) {
        frameCapHz = Math.max(0, hz);
        writeFpsFile();
    }

    private File fpsFile() {
        return new File(tmpRoot, ".yserver-fps");
    }

    private void writeFpsFile() {
        try (java.io.FileWriter w = new java.io.FileWriter(fpsFile())) {
            w.write(String.valueOf(frameCapHz));
        } catch (IOException e) {
            Log.w(TAG, "fps file: " + e.getMessage());
        }
    }

    /** A Wine application window was mapped (yserver's YSERVER-MAP line). */
    public interface WindowMapListener {
        void onWineWindowMapped(String className, long hwnd, int pid, boolean wow64);
    }

    /** The busiest window's presents per second (yserver's YSERVER-FPS line, once a second). */
    public interface FpsListener {
        void onGameFps(float fps);
    }

    private volatile FpsListener fpsListener;
    private String hideClass;

    public void setFpsListener(FpsListener listener) {
        fpsListener = listener;
    }

    /** Windows of this WM_CLASS draw nothing and clear to black (YSERVER_HIDE_CLASS), unless the game's
     *  env sets its own. Bannerlator's X11 renderer and compositor leave Wine's desktop out the same way. */
    public void setHideClass(String wmClass) {
        hideClass = wmClass;
    }

    /** gameEnv: the session's env; its YSERVER_* and RUST_LOG entries reach yserver. */
    public YServerComponent(Context context, String mode, File tmpRoot, File waylandRuntimeDir, EnvVars gameEnv,
                            WindowMapListener mapListener) {
        this.context = context.getApplicationContext();
        this.mode = mode;
        this.tmpRoot = tmpRoot;
        this.waylandRuntimeDir = waylandRuntimeDir;
        this.gameEnv = gameEnv;
        this.mapListener = mapListener;
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
        env.put("RUST_LOG", "info");
        // Blit copies (Adreno blob) and presents that run at once (as the Java X server does) unless the
        // game's env says otherwise below.
        // Presents run at once (as the Java X server's do) while no cap is set; with the FPS limiter
        // on, yserver's Present clock runs at the cap and a window's presents take one tick each.
        // The cap follows the in-game limiter live through the fps file.
        env.put("YSERVER_PRESENT_ASYNC", "1");
        writeFpsFile();
        env.put("YSERVER_FPS_FILE", fpsFile().getPath());
        if (hideClass != null && !hideClass.isEmpty()) env.put("YSERVER_HIDE_CLASS", hideClass);
        StringBuilder passed = new StringBuilder();
        if (gameEnv != null) {
            for (String name : gameEnv) {
                if (name.startsWith("YSERVER_") || name.equals("RUST_LOG")) {
                    env.put(name, gameEnv.get(name));
                    passed.append(' ').append(name).append('=').append(gameEnv.get(name));
                }
            }
        }
        if (passed.length() > 0) Log.i(TAG, "yserver env from the game:" + passed);
        // The map notifications must reach us whatever RUST_LOG the game set.
        env.put("RUST_LOG", env.get("RUST_LOG") + ",yserver::bannerlator=info");
        if (MODE_RECORD.equals(mode)) {
            env.put("YSERVER_RECORD_EVERY", "30");
            env.put("YSERVER_RECORD_MAX", "40");
        } else {
            env.put("XDG_RUNTIME_DIR", waylandRuntimeDir.getPath());
            env.put("WAYLAND_DISPLAY", "wayland-0");
        }
        pb.redirectErrorStream(true);

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
        startReader(process, log);
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

    /** Copy yserver's output into the log file and act on its YSERVER-MAP lines. */
    private void startReader(Process p, File log) {
        // Keeps draining whatever happens to the file: an unread pipe would block yserver's logging.
        reader = new Thread(() -> {
            java.io.Writer out = null;
            try {
                out = new java.io.BufferedWriter(new java.io.FileWriter(log));
            } catch (IOException e) {
                Log.w(TAG, "yserver log file: " + e.getMessage());
            }
            try (java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    // Once a second: the HUD's frame rate, not kept in the log file.
                    int fpsAt = line.indexOf("YSERVER-FPS ");
                    if (fpsAt >= 0) {
                        onFpsLine(line.substring(fpsAt + 12));
                        continue;
                    }
                    if (out != null) {
                        try {
                            out.write(line);
                            out.write('\n');
                        } catch (IOException e) {
                            out = null;
                        }
                    }
                    int at = line.indexOf("YSERVER-MAP ");
                    if (at >= 0) {
                        try { if (out != null) out.flush(); } catch (IOException ignored) {}
                        try {
                            onMapLine(line.substring(at + 12));
                        } catch (RuntimeException e) {
                            Log.w(TAG, "YSERVER-MAP: " + e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "yserver output: " + e.getMessage());
            } finally {
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
        }, "yserver-log");
        reader.setDaemon(true);
        reader.start();
    }

    private void onFpsLine(String fields) {
        FpsListener l = fpsListener;
        if (l == null) return;
        for (String f : fields.trim().split(" ")) {
            if (!f.startsWith("fps=")) continue;
            try {
                l.onGameFps(Float.parseFloat(f.substring(4)));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void onMapLine(String fields) {
        String cls = "";
        long hwnd = 0;
        int pid = 0;
        boolean wow64 = false;
        for (String f : fields.trim().split(" ")) {
            int eq = f.indexOf('=');
            if (eq <= 0) continue;
            String k = f.substring(0, eq), v = f.substring(eq + 1);
            try {
                if (k.equals("hwnd")) hwnd = Long.parseLong(v.startsWith("0x") ? v.substring(2) : v, 16);
                else if (k.equals("pid")) pid = Integer.parseInt(v);
                else if (k.equals("wow64")) wow64 = v.equals("1");
                else if (k.equals("class")) cls = v;
            } catch (NumberFormatException ignored) {}
        }
        Log.i(TAG, "Wine window mapped: " + cls + " hwnd 0x" + Long.toHexString(hwnd) + " pid " + pid);
        if (mapListener != null && !cls.isEmpty()) mapListener.onWineWindowMapped(cls, hwnd, pid, wow64);
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
