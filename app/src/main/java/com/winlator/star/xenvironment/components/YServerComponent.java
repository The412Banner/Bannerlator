package com.winlator.star.xenvironment.components;

import android.os.SystemClock;
import android.util.Log;

import com.winlator.star.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Test-only (feat/yserver-spike): runs an external yserver binary as the X server instead of the
 * Java one, headless with the frame recorder. Selected by BANNER_YSERVER=1 in the game's env;
 * the binary and xkb data are staged by hand in files/yserver/. Logs and recorded frames go to
 * files/yserver/logs/.
 */
public class YServerComponent extends EnvironmentComponent {
    private static final String TAG = "YServerComponent";
    private final File home;
    private final File tmpRoot;
    private Process process;

    public YServerComponent(File home, File tmpRoot) {
        this.home = home;
        this.tmpRoot = tmpRoot;
    }

    public static boolean available(File home) {
        return new File(home, "yserver").canExecute();
    }

    @Override
    public void start() {
        if (process != null) return;
        File socket = new File(tmpRoot, ".X11-unix/X0");
        socket.delete();
        new File(tmpRoot, ".X0-lock").delete();
        File logs = new File(home, "logs");
        logs.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File log = new File(logs, "yserver-" + stamp + ".log");
        ProcessBuilder pb = new ProcessBuilder(Arrays.asList(
                new File(home, "yserver").getPath(), ":0", "-nolisten", "tcp",
                "--headless", "--rootless", "--rootless-record", new File(logs, "rec-" + stamp).getPath()));
        Map<String, String> env = pb.environment();
        env.put("YSERVER_TMPDIR", tmpRoot.getPath());
        env.put("XKB_CONFIG_ROOT", new File(home, "xkb").getPath());
        env.put("RUST_LOG", "info");
        env.put("YSERVER_RECORD_EVERY", "30");
        env.put("YSERVER_RECORD_MAX", "40");
        pb.redirectErrorStream(true);
        pb.redirectOutput(log);
        long t0 = SystemClock.uptimeMillis();
        try {
            process = pb.start();
        } catch (IOException e) {
            Log.e(TAG, "yserver did not start", e);
            return;
        }
        while (!socket.exists() && process.isAlive() && SystemClock.uptimeMillis() - t0 < 5000) {
            SystemClock.sleep(20);
        }
        Log.i(TAG, "yserver " + (socket.exists() ? "ready" : "NOT ready") + " after "
                + (SystemClock.uptimeMillis() - t0) + " ms, alive=" + process.isAlive() + ", log " + log);
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
}
