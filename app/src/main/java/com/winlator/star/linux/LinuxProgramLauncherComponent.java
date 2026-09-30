package com.winlator.star.linux;

import android.util.Log;

import com.winlator.star.core.Callback;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.ProcessHelper;
import com.winlator.star.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one program in the Linux runtime for the length of the session; the session ends when it
 * exits. The command is a complete proot invocation from {@link LinuxRuntime#command}, so there is
 * no Wine, no wineserver and no prefix behind this — proot is the only process we start.
 *
 * <p>Ported from WinNative's gamescope runtime (GPL-3.0).
 */
public class LinuxProgramLauncherComponent extends EnvironmentComponent {
    private static final String TAG = "LinuxLauncher";

    private final List<String> command;
    private final EnvVars envVars;
    private final File workingDir;
    private final Callback<Integer> terminationCallback;
    private final Object lock = new Object();
    private int pid = -1;

    public LinuxProgramLauncherComponent(List<String> command, EnvVars envVars, File workingDir,
                                         Callback<Integer> terminationCallback) {
        this.command = command;
        this.envVars = envVars;
        this.workingDir = workingDir;
        this.terminationCallback = terminationCallback;
    }

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            StringBuilder line = new StringBuilder();
            for (String arg : command) {
                if (line.length() > 0) line.append(' ');
                line.append(arg.replace(" ", "\\ "));
            }
            Log.i(TAG, "exec " + line);
            pid = ProcessHelper.exec(line.toString(), envVars.toStringArray(), workingDir, (status) -> {
                synchronized (lock) {
                    pid = -1;
                }
                if (terminationCallback != null) terminationCallback.call(status);
            });
        }
    }

    /** How long proot is given to take its own tree down before it is killed outright. */
    private static final long GRACE_MS = 1200L;

    /**
     * Ends the session.
     *
     * <p>This used to be a single {@code Process.killProcess(pid)} on proot, on the understanding
     * that {@code --kill-on-exit} would take the tree with it. It does not: that is proot's own
     * cleanup, and SIGKILL is the one signal it cannot catch, so the cleanup never runs. What was
     * left behind was worse than an ordinary leak - every process under proot is traced by it, and
     * an untraced survivor gets ENOSYS from every syscall proot used to answer. It cannot fail and
     * it cannot proceed, so it spins. One such orphan was found holding a whole core for
     * seventy-four minutes with seven unreaped children, and it is the likeliest source of the
     * "Function not implemented" failures seen on both devices.
     *
     * <p>So proot is asked first and killed only if it does not go, and anything still standing
     * afterwards is swept. The tree is read <em>before</em> proot dies, because once it is gone its
     * children are reparented to init and nothing distinguishes them from the rest of the app -
     * a Wine container can be running in the same process at the same time, and a sweep by user id
     * would take the game with it.
     */
    @Override
    public void stop() {
        synchronized (lock) {
            if (pid == -1) return;
            int prootPid = pid;
            pid = -1;
            List<long[]> tree = descendants(prootPid);
            ProcessHelper.terminateProcess(prootPid);
            if (!waitForExit(prootPid, GRACE_MS)) {
                Log.w(TAG, "proot " + prootPid + " did not exit on SIGTERM; killing it");
                ProcessHelper.killProcess(prootPid);
            }
            sweep(tree);
            killGuestLeftovers();
        }
    }

    /** How long the session script gets to pick up a clean-exit request before the session is torn down without it. */
    private static final long STEAM_PICKUP_MS = 1500L;
    /** How long the Steam client then gets to shut itself down before the session is torn down anyway. */
    private static final long STEAM_EXIT_MS = 10_000L;

    /**
     * True when the session script is listening for a clean-exit request: its watcher is armed and proot is still running.
     * Cheap, so it can be asked on the main thread.
     */
    public boolean steamStopArmed(File liveDir) {
        int prootPid;
        synchronized (lock) {
            prootPid = pid;
        }
        return steamStopArmed(prootPid, liveDir);
    }

    private static boolean steamStopArmed(int prootPid, File liveDir) {
        return prootPid > 1 && new File("/proc/" + prootPid).exists()
                && new File(liveDir, "steam-stop-ready").exists();
    }

    /**
     * Asks the Steam client to shut down by itself before {@link #stop} takes the session down, and waits for it.
     * A killed client leaves .crash behind for the next start and loses whatever it had not yet written.
     * The session script runs the client's own -shutdown on the request and removes steam-stop-ready once the client has exited.
     * Blocks for up to {@link #STEAM_PICKUP_MS} plus {@link #STEAM_EXIT_MS}, so it must never run on the main thread.
     * It returns at once when nothing is listening, and {@link #stop} still kills whatever is left afterwards.
     * (From Droid-Deck/DroidDeck #68.)
     */
    public void askSteamToExit(File liveDir) {
        int prootPid;
        synchronized (lock) {
            prootPid = pid;
        }
        if (!steamStopArmed(prootPid, liveDir)) return;
        File ready = new File(liveDir, "steam-stop-ready");
        File request = new File(liveDir, "steam-stop");
        try {
            new java.io.FileOutputStream(request).close();
        } catch (IOException e) {
            Log.w(TAG, "could not ask the Steam client to exit", e);
            return;
        }
        long started = System.currentTimeMillis();
        // The watcher removes the request when it acts on it; one still there after the pickup window was never read.
        if (!waitFor(() -> !request.exists() || !ready.exists() || !new File("/proc/" + prootPid).exists(), STEAM_PICKUP_MS)) {
            //noinspection ResultOfMethodCallIgnored
            request.delete();
            Log.w(TAG, "the session did not pick up the request to stop the Steam client; tearing it down");
            return;
        }
        boolean exited = waitFor(() -> !ready.exists() || !new File("/proc/" + prootPid).exists(), STEAM_EXIT_MS);
        Log.i(TAG, "Steam client " + (exited ? "exited" : "did not exit") + " after "
                + (System.currentTimeMillis() - started) + " ms");
    }

    /** True once {@code done} holds, false if it still does not after {@code timeout}. */
    private static boolean waitFor(java.util.function.BooleanSupplier done, long timeout) {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            if (done.getAsBoolean()) return true;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return done.getAsBoolean();
    }

    /**
     * Every process of ours still running a program out of the Linux runtime once proot is gone.
     * The sweep above reaches what proot still traced when the session ended; a tracee it had
     * already lost - an Xwayland that aborted from one thread and then looped on a syscall proot's
     * own seccomp filter, with no tracer left, answers ENOSYS - is reparented to init before the
     * tree is read, and wrote 9 GB of one line into the session log before anything killed it.
     * A Wine container running in the same process is safe: its programs live under imagefs, not
     * the runtime. (From The412Banner/DroidDeck.)
     */
    private void killGuestLeftovers() {
        String rootfs;
        try {
            rootfs = workingDir.getCanonicalPath() + "/";
        } catch (IOException e) {
            return;
        }
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return;
        int me = android.os.Process.myPid();
        int killed = 0;
        for (File entry : entries) {
            int candidate;
            try {
                candidate = Integer.parseInt(entry.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            if (candidate == me) continue;
            String exe;
            try {
                exe = new File(entry, "exe").getCanonicalPath(); // another uid's is unreadable; a gone process has none
            } catch (IOException | SecurityException e) {
                continue;
            }
            if (!exe.startsWith(rootfs)) continue;
            ProcessHelper.killProcess(candidate);
            killed++;
        }
        if (killed > 0) Log.w(TAG, "killed " + killed + " guest process(es) proot no longer tracked");
    }

    /**
     * Every process below {@code root}, each as {@code {pid, start time}}. The start time is kept
     * so the sweep cannot kill a stranger: pids are reused, and by the time we look again the
     * number may belong to something else entirely.
     */
    private static List<long[]> descendants(int root) {
        Map<Integer, Integer> parents = new HashMap<>();
        Map<Integer, Long> started = new HashMap<>();
        Map<Integer, List<Integer>> children = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries != null) {
            for (File entry : entries) {
                int candidate;
                try {
                    candidate = Integer.parseInt(entry.getName());
                } catch (NumberFormatException e) {
                    continue; // not a process
                }
                long[] stat = readStat(candidate);
                if (stat == null) continue;
                int parent = (int) stat[0];
                parents.put(candidate, parent);
                started.put(candidate, stat[1]);
                List<Integer> kids = children.get(parent);
                if (kids == null) {
                    kids = new ArrayList<>();
                    children.put(parent, kids);
                }
                kids.add(candidate);
            }
        }
        List<long[]> out = new ArrayList<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(root);
        int myPid = android.os.Process.myPid();
        while (!queue.isEmpty()) {
            List<Integer> kids = children.get(queue.poll());
            if (kids == null) continue;
            for (int kid : kids) {
                // Guards against a cycle in a racing read, and against ever queueing ourselves.
                if (kid <= 1 || kid == myPid || kid == root) continue;
                Long when = started.get(kid);
                if (when == null) continue;
                out.add(new long[]{kid, when});
                queue.add(kid);
            }
        }
        return out;
    }

    /** {@code {ppid, start time}} from {@code /proc/pid/stat}, or null. */
    private static long[] readStat(int pid) {
        // Read defensively rather than through FileUtils.readString: that throws on a file it
        // cannot read, and a /proc entry can disappear between listing it and opening it - which
        // is not an error here, it is the normal case for a process that has just exited.
        String stat;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(
                    new File("/proc/" + pid + "/stat").toPath());
            stat = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        if (stat.isEmpty()) return null;
        // The second field is the executable name in parentheses and may itself contain spaces and
        // parentheses, so the fields are counted from the LAST close paren, never split from the
        // start. After it: state, ppid, ... with start time the twentieth.
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 >= stat.length()) return null;
        String[] fields = stat.substring(close + 2).trim().split("\\s+");
        if (fields.length < 20) return null;
        try {
            return new long[]{Long.parseLong(fields[1]), Long.parseLong(fields[19])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True once {@code pid} is gone, false if it is still there after {@code timeout}. */
    private static boolean waitForExit(int pid, long timeout) {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            if (!new File("/proc/" + pid).exists()) return true;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !new File("/proc/" + pid).exists();
    }

    /** Kills whatever of the captured tree outlived proot, skipping any pid since recycled. */
    private static void sweep(List<long[]> tree) {
        int killed = 0;
        for (long[] entry : tree) {
            int pid = (int) entry[0];
            long[] stat = readStat(pid);
            if (stat == null) continue;      // already gone
            if (stat[1] != entry[1]) continue; // same number, different process
            ProcessHelper.killProcess(pid);
            killed++;
        }
        if (killed > 0) Log.i(TAG, "swept " + killed + " process(es) proot left behind");
    }
}
