package com.winlator.star.linux;

import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import com.winlator.star.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The glibc arm64 rootfs at {@code files/linuxfs} and the proot invocation that runs a program in
 * it as this app's own uid. It is a second runtime beside the Wine imagefs, not a container: no
 * Wine, no box64, no FEX. proot is packaged as {@code libproot.so} so the installer places it, with
 * its loader, in the native library directory — the only place an app on targetSdk 28 may execute
 * a file from.
 *
 * <p>Ported from WinNative's gamescope runtime (GPL-3.0).
 */
public final class LinuxRuntime {
    public static final String DIR = "linuxfs";
    public static final String SESSION_SCRIPT = "/usr/local/bin/bannerlator-session";
    public static final String MODE_DESKTOP = "desktop";
    public static final String MODE_STEAM = "steam";
    public static final String MODE_RUN = "run";
    /**
     * Where the guest sees the session's XDG_RUNTIME_DIR. A Unix socket's path must fit in 108 bytes,
     * and on adopted storage the app's own path (/mnt/expand/<uuid>/user/0/...) leaves the compositor's
     * and gamescope's sockets no room, so the directory is bound here too and the guest is pointed at
     * this. (From Droid-Deck/DroidDeck #119.)
     */
    public static final String GUEST_RUNTIME_DIR = "/run/bannerlator";
    /** Shortcut extra naming which of the modes above a Linux entry launches. */
    public static final String EXTRA_LINUX_MODE = "linux_mode";
    private static final String KGSL_DEVICE = "/dev/kgsl-3d0";
    /** Where every Linux session's debug log lands: public, so a user can just hand the folder over. */
    public static final String DEBUG_LOG_DIR = "Bannerlator-LinuxSteam";

    public static File debugLogDir() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), DEBUG_LOG_DIR);
    }

    private LinuxRuntime() {}

    public static File rootDir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    /** Where the runtime carries the host-side proot; see tools/linuxfs/prebuilt/proot/README.md. */
    private static final String HOST_DIR = "opt/android-host";

    /**
     * proot, preferred from the installed runtime and falling back to the copy in the apk.
     *
     * <p>It is an Android binary rather than part of the rootfs — it is what creates the rootfs —
     * but it travels in the runtime tarball so that reinstalling the app cannot replace the one
     * binary everything else depends on, and so a device with no working packaged proot can still
     * run the runtime.
     */
    public static File prootBinary(Context context) {
        File shipped = new File(rootDir(context), HOST_DIR + "/proot");
        if (shipped.isFile()) return shipped;
        return new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    public static File prootLoader(Context context) {
        File shipped = new File(rootDir(context), HOST_DIR + "/loader");
        if (shipped.isFile()) return shipped;
        return new File(context.getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
    }

    /**
     * Whether this proot takes {@code -i uid:gid}, i.e. whether it is the runtime's own build.
     *
     * <p>Asked of the binary that was chosen rather than of the device, so a runtime that arrives
     * without its host directory, or is still downloading, falls back to the apk copy and is given
     * the option list that copy actually has.
     */
    static boolean emulatesIdentityByOption(Context context, File proot) {
        File shipped = new File(rootDir(context), HOST_DIR + "/proot");
        return shipped.isFile() && shipped.getPath().equals(proot.getPath());
    }

    /** proot links against libtalloc, which sits beside it; empty when the apk copy is in use. */
    public static String prootLibraryPath(Context context) {
        File dir = new File(rootDir(context), HOST_DIR);
        return new File(dir, "proot").isFile() ? dir.getPath() : "";
    }

    /** The rootfs is present with gamescope and the session script the launcher hands control to. */
    public static boolean isInstalled(Context context) {
        File root = rootDir(context);
        return new File(root, "usr/bin/gamescope").isFile()
                && new File(root, SESSION_SCRIPT.substring(1)).isFile()
                && prootBinary(context).isFile()
                && prootLoader(context).isFile();
    }

    /** The Vulkan ICD manifest the rootfs ships for the device GPU, or null when it has none. */
    public static File vulkanIcd(Context context) {
        File icdDir = new File(rootDir(context), "usr/share/vulkan/icd.d");
        File[] manifests = icdDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (manifests == null) return null;
        for (File manifest : manifests) {
            if (manifest.getName().contains("freedreno")) return manifest;
        }
        return manifests.length > 0 ? manifests[0] : null;
    }

    /**
     * The proot command line running {@code guestCommand} inside the rootfs. Host paths the session
     * needs — the app's files directory for the compositor and audio sockets, external storage for
     * the user's games — are bound at their own paths, so nothing on either side needs translating
     * and proot never touches the fds a dma-buf travels in. Android has no /dev/shm; a directory
     * under the cache stands in, which glibc's shm_open and Chromium's shared memory accept.
     */
    public static List<String> command(Context context, ImageFs imageFs, File runtimeDir,
                                       File externalStorage, List<String> guestCommand) {
        return command(context, imageFs, runtimeDir, externalStorage, null, guestCommand);
    }

    /** As above, plus {@code host:guest} bind specs — the installed games handed to Steam. */
    public static List<String> command(Context context, ImageFs imageFs, File runtimeDir,
                                       File externalStorage, List<String> extraBinds,
                                       List<String> guestCommand) {
        File root = rootDir(context);
        File proot = prootBinary(context);
        List<String> cmd = new ArrayList<>();
        cmd.add(proot.getPath());
        cmd.add("--kill-on-exit");
        // Android's app seccomp policy traps the whole set*id family.
        // Xwayland's Popen() calls setgid()/setuid() before it execs xkbcomp, and _exit(127)s when they fail.
        // Without this the keymap never compiles and Xwayland dies.
        // -i makes the runtime's proot answer those calls itself while still reporting our real ids, so nothing inside sees a different user.
        //
        // The copy in the apk takes no such option.
        // It answers set*id from its own seccomp handler unconditionally (src/tracee/seccomp.c, PR_setuid and its family, granting an id the process already holds and refusing any other).
        // Its option table is only -r/-b/-w/--kill-on-exit/-v/-V/-h.
        // An option it does not know is fatal in cli.c before a single guest process starts.
        // A session that fell back to it died instantly with no window and nothing in the log.
        // So the flag goes only to the binary that accepts it.
        // (The same class of fault, found and fixed independently in WinNative, maxjivi05, b6b2fce8.)
        if (emulatesIdentityByOption(context, proot)) {
            int uid = Process.myUid();
            cmd.add("-i");
            cmd.add(uid + ":" + uid);
        }
        cmd.add("-r");
        cmd.add(root.getPath());
        cmd.add("-w");
        cmd.add("/root");
        bind(cmd, "/dev");
        bind(cmd, "/proc");
        bind(cmd, "/sys");
        bind(cmd, "/dev/urandom:/dev/random");
        bind(cmd, "/proc/self/fd:/dev/fd");
        bind(cmd, "/proc/self/fd/0:/dev/stdin");
        bind(cmd, "/proc/self/fd/1:/dev/stdout");
        bind(cmd, "/proc/self/fd/2:/dev/stderr");
        bind(cmd, new File(root, "etc/bannerlator/empty").getPath() + ":/sys/fs/selinux");
        bind(cmd, context.getFilesDir().getPath());
        bind(cmd, context.getCacheDir().getPath());
        bind(cmd, runtimeDir.getPath());
        bind(cmd, runtimeDir.getPath() + ":" + GUEST_RUNTIME_DIR);
        if (imageFs != null) bind(cmd, imageFs.getRootDir().getPath());
        if (externalStorage != null && externalStorage.isDirectory()) {
            bind(cmd, externalStorage.getPath());
        }
        File shm = new File(context.getCacheDir(), "shm");
        shm.mkdirs();
        bind(cmd, shm.getPath() + ":/dev/shm");

        // Android denies apps these; glibc, Steam and libcap read them at startup.
        File fakeProc = new File(root, "etc/bannerlator/proc");
        // libpci picks its procfs backend on whether it can read the /proc/bus/pci directory, which the app can.
        // It then opens the devices file inside it, which the app cannot, and its error path is die() - exit(1) on the calling process.
        // Chromium loads libpci in its GPU process to name the video card, so that exit kills the process.
        // After a few tries CEF gives up on hardware and draws the rest of the session on SwiftShader, which is the client's interface rendered on the CPU.
        // An empty list is the truthful answer from in here: nothing the app can see is on a PCI bus.
        // It is created at session start rather than shipped in the rootfs, so an installed runtime is fixed too.
        // The table's guard below binds it only when the real file cannot be read, so it can never stand in front of real data.
        // (WinNative, maxjivi05, deff1ac6: 44 -> 85 fps scrolling the Big Picture library on a OnePlus 15, GPU-process crashes 12 -> 0.)
        File pciDevices = new File(fakeProc, "pci_devices");
        if (!pciDevices.isFile()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                pciDevices.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                pciDevices.createNewFile();
            } catch (IOException e) {
                // It then fails the isFile() test below and the session runs as it did before.
            }
        }
        String[][] procFiles = {
                {"stat", "/proc/stat"},
                {"version", "/proc/version"},
                {"loadavg", "/proc/loadavg"},
                {"uptime", "/proc/uptime"},
                {"vmstat", "/proc/vmstat"},
                {"pci_devices", "/proc/bus/pci/devices"},
                {"cap_last_cap", "/proc/sys/kernel/cap_last_cap"},
                {"overflowuid", "/proc/sys/kernel/overflowuid"},
                {"overflowgid", "/proc/sys/kernel/overflowgid"},
        };
        for (String[] entry : procFiles) {
            File fake = new File(fakeProc, entry[0]);
            // A live stand-in the session binds itself (LinuxCpuStatComponent's /proc/stat) wins.
            boolean live = false;
            if (extraBinds != null) for (String spec : extraBinds) live |= spec.endsWith(":" + entry[1]);
            if (!live && fake.isFile() && !new File(entry[1]).canRead()) {
                bind(cmd, fake.getPath() + ":" + entry[1]);
            }
        }
        bindGpuNode(context, cmd);
        bindAdrenoStats(cmd, extraBinds);
        bindCpuTemps(cmd, root);
        if (extraBinds != null) {
            for (String spec : extraBinds) bind(cmd, spec);
        }
        cmd.addAll(guestCommand);
        return cmd;
    }

    /**
     * An app process may not open {@code /dev/dri} — the nodes exist but are labelled
     * {@code graphics_device}, which stock policy grants surfaceflinger and not us — yet libdrm and
     * everything built on it identify a GPU by its render node, and gamescope refuses to offer
     * linux-dmabuf without one. The KGSL device Turnip actually drives ({@code gpu_device}, which we
     * may open) stands in: it appears as a render node with the sysfs entries libdrm reads, and our
     * Turnip build reports the same device numbers for it.
     *
     * The same {@code drm/sys} folder over {@code /sys/dev/char} also carries the Steam Deck
     * controller's {@code 240:16} entry when a session presents the pad as one (SteamDeckPad), so a
     * device with no KGSL node still gets that folder bound whenever the entry is there.
     */
    private static void bindGpuNode(Context context, List<String> cmd) {
        File base = new File(context.getCacheDir(), "drm");
        StructStat st;
        try {
            st = Os.stat(KGSL_DEVICE);
        } catch (ErrnoException e) {
            bindDeckCharDir(base, cmd);
            return;
        }
        long dev = st.st_rdev;
        long major = ((dev >> 8) & 0xfff) | ((dev >> 32) & ~0xfffL);
        long minor = (dev & 0xff) | ((dev >> 12) & ~0xffL);
        String node = "renderD" + minor;
        File dri = new File(base, "dri");
        File device = new File(base, "sys/" + major + ":" + minor + "/device");
        File drm = new File(device, "drm/" + node);
        try {
            if ((!dri.isDirectory() && !dri.mkdirs()) || (!drm.isDirectory() && !drm.mkdirs())) {
                bindDeckCharDir(base, cmd);
                return;
            }
            new File(dri, node).createNewFile();
            Files.write(new File(drm, "dev").toPath(),
                    (major + ":" + minor + "\n").getBytes(StandardCharsets.UTF_8));
            Files.write(new File(device, "uevent").toPath(),
                    "DRIVER=kgsl-3d0\nMODALIAS=platform:kgsl-3d0\n".getBytes(StandardCharsets.UTF_8));
            File subsystem = new File(device, "subsystem");
            if (!Files.isSymbolicLink(subsystem.toPath())) {
                Os.symlink("/sys/bus/platform", subsystem.getPath());
            }
            // What MangoHud names a GPU's driver by; msm_drm is how an Adreno's render node reads on a mainline kernel, and the driver it reads the load of (bindAdrenoStats).
            File driver = new File(device, "driver");
            if (!Files.isSymbolicLink(driver.toPath())) {
                Os.symlink("/sys/bus/platform/drivers/msm_drm", driver.getPath());
            }
        } catch (IOException | ErrnoException e) {
            bindDeckCharDir(base, cmd);
            return;
        }
        bind(cmd, new File(base, "sys").getPath() + ":/sys/dev/char");
        bind(cmd, dri.getPath() + ":/dev/dri");
        bind(cmd, KGSL_DEVICE + ":/dev/dri/" + node);
        bindDrmClass(base, cmd, node, major + ":" + minor);
    }

    /**
     * MangoHud (Deck mode's performance overlay, mangoapp) finds the GPU by listing {@code /sys/class/drm}, and an exception from that listing ends the process.
     * Under an enforcing SELinux policy - every retail phone - an app may not list it, so mangoapp died on start, gamescope restarted it until the wrapper gave up, and there was no overlay.
     * Where the app cannot list it, the session's {@code /sys/class/drm} is ours: the render node above, named as an Adreno's is.
     * MangoHud then reads the GPU's stats from KGSL's sysfs, which such a device refuses too - LinuxGpuStatsComponent stands in for that.
     * Where the listing is allowed it is left alone. (From Droid-Deck/DroidDeck #127.)
     */
    private static void bindDrmClass(File base, List<String> cmd, String node, String devNumbers) {
        if (new File("/sys/class/drm").list() != null) return;
        File drmClass = new File(base, "class");
        try {
            if (!drmClass.isDirectory() && !drmClass.mkdirs()) return;
            // Only symlinks are ever made here; deleting them must not follow one into its target.
            File[] old = drmClass.listFiles();
            if (old != null) for (File f : old) Files.deleteIfExists(f.toPath());
            Os.symlink("/sys/dev/char/" + devNumbers, new File(drmClass, node).getPath());
        } catch (IOException | ErrnoException e) {
            return;
        }
        bind(cmd, drmClass.getPath() + ":/sys/class/drm");
        android.util.Log.i("LinuxRuntime", "hud: /sys/class/drm is not listable here; the session's lists " + node);
        // Also refused to list, also asked for by MangoHud twice a second - an error line each time in the session log - and holding nothing it reads on an Adreno phone.
        if (new File("/sys/class/powercap").list() == null) {
            File empty = new File(base, "empty-powercap");
            if (empty.isDirectory() || empty.mkdirs()) bind(cmd, empty.getPath() + ":/sys/class/powercap");
        }
    }

    /** The temperature of a CPU zone the app may read (the first by name), or null. */
    public static String cpuTempSource() {
        for (java.util.Map.Entry<String, File> e : new java.util.TreeMap<>(thermalZones()).entrySet()) {
            File temp = new File(e.getValue(), "temp");
            if (e.getKey().contains("cpu") && !e.getKey().contains("gpu") && temp.canRead()) return temp.getPath();
        }
        return null;
    }

    /** The file the GPU's temperature reads from, or null where the app may read none. */
    public static String gpuTempSource() {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        return firstReadable(kgsl + "temp", kgsl + "devfreq/temp", thermalZone("gpu"));
    }

    private static String gpuLoadSource() {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        return firstReadable(kgsl + "gpu_busy_percentage", kgsl + "devfreq/gpu_load");
    }

    /** Without a GPU node: binds {@code drm/sys} over {@code /sys/dev/char} only for the Deck controller's entry, if this session made one. */
    private static void bindDeckCharDir(File base, List<String> cmd) {
        File sys = new File(base, "sys");
        if (Files.isSymbolicLink(new File(sys, SteamDeckPad.CHAR_DEV).toPath())) {
            bind(cmd, sys.getPath() + ":/sys/dev/char");
        }
    }

    /**
     * Valve's mangoapp (Deck mode's performance overlay) reads an Adreno GPU's load, clock and temperatures from where they are on Valve's own hardware.
     * Every Adreno under Android keeps them in KGSL's sysfs, readable by the app, though which files a kernel has differs between Snapdragon generations.
     * So each value takes the first source that exists, in the order other Android PC emulators (GameNative, Winlator forks) read them.
     * A value found nowhere is left alone. (From Droid-Deck/DroidDeck #55.)
     */
    private static void bindAdrenoStats(List<String> cmd, List<String> extraBinds) {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        String gpuTemp = gpuTempSource();
        String[][] stats = {
                {gpuLoadSource(), "/sys/kernel/debug/dri/0/perf_now"},
                {firstReadable(kgsl + "devfreq/cur_freq", kgsl + "gpuclk"),
                        "/sys/devices/platform/soc@0/3d00000.gpu/devfreq/3d00000.gpu/cur_freq"},
                {gpuTemp, "/sys/class/thermal/thermal_zone28/temp"},
                {gpuTemp, "/sys/class/thermal/thermal_zone26/temp"},
                {thermalZone("ddr"), "/sys/class/thermal/thermal_zone22/temp"},
        };
        for (String[] stat : stats) {
            // A value the session feeds itself (LinuxGpuStatsComponent, where KGSL is refused) wins.
            boolean fed = false;
            if (extraBinds != null) for (String spec : extraBinds) fed |= spec.endsWith(":" + stat[1]);
            if (stat[0] != null && !fed) bind(cmd, stat[0] + ":" + stat[1]);
        }
        // Which of the overlay's GPU values this device lets the app read: a phone under an enforcing policy refuses some of them, and the overlay then has no line for that value.
        android.util.Log.i("LinuxRuntime", "hud sources: gpu load " + orNone(stats[0][0]) + ", gpu clock "
                + orNone(stats[1][0]) + ", gpu temp " + orNone(gpuTemp) + ", ddr temp " + orNone(stats[4][0])
                + ", thermal zones " + thermalZones().size()
                + ", kgsl gpubusy " + (new File(kgsl + "gpubusy").canRead() ? "readable" : "refused"));
    }

    private static String orNone(String path) {
        return path != null ? path : "none";
    }

    /**
     * mangoapp's CPU temperature is the mean of the thermal zones named cpuN-thermal or cpuN-top-thermal, as mainline kernels name them.
     * Android kernels name the same sensors cpuss-0, cpu-1-0, cpu0-silver-usr, apc1-cpu0-usr and the like, so those zones are shown under the mainline name.
     * (From Droid-Deck/DroidDeck #55.)
     */
    private static void bindCpuTemps(List<String> cmd, File root) {
        File name = new File(root, "etc/bannerlator/cpu-thermal-type");
        try {
            if (!name.isFile()) Files.write(name.toPath(), "cpu0-thermal\n".getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return;
        }
        for (java.util.Map.Entry<String, File> zone : thermalZones().entrySet()) {
            String type = zone.getKey();
            if (type.contains("cpu") && !type.contains("gpu") && !type.matches("cpu\\d-(top-)?thermal")) {
                bind(cmd, name.getPath() + ":" + new File(zone.getValue(), "type").getPath());
            }
        }
    }

    private static String firstReadable(String... paths) {
        for (String path : paths) {
            if (path != null && new File(path).canRead()) return path;
        }
        return null;
    }

    /** The temp file of the thermal zone for a sensor: the one named so, else the first whose name has it. */
    private static String thermalZone(String sensor) {
        java.util.Map<String, File> zones = thermalZones();
        File zone = zones.get(sensor);
        for (java.util.Map.Entry<String, File> e : new java.util.TreeMap<>(zones).entrySet()) {
            if (zone == null && e.getKey().contains(sensor)) zone = e.getValue();
        }
        return zone != null ? new File(zone, "temp").getPath() : null;
    }

    /** The device's thermal zones by their sensor's name, in lower case. */
    private static java.util.Map<String, File> thermalZones() {
        java.util.Map<String, File> byType = new java.util.HashMap<>();
        File[] zones = new File("/sys/class/thermal").listFiles((dir, n) -> n.startsWith("thermal_zone"));
        if (zones == null) return byType;
        for (File zone : zones) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new File(zone, "type")))) {
                byType.putIfAbsent(String.valueOf(r.readLine()).trim().toLowerCase(java.util.Locale.ROOT), zone);
            } catch (IOException ignored) {
            }
        }
        return byType;
    }

    private static void bind(List<String> cmd, String spec) {
        cmd.add("-b");
        cmd.add(spec);
    }

    /** X access control and Steam look the session user up by uid: the app uid is root inside. */
    public static void writeAccounts(Context context) throws IOException {
        File root = rootDir(context);
        int uid = Process.myUid();
        Files.write(new File(root, "etc/passwd").toPath(),
                ("root:x:" + uid + ":" + uid + ":root:/root:/bin/bash\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "etc/group").toPath(),
                ("root:x:" + uid + ":\n").getBytes(StandardCharsets.UTF_8));
    }
}
