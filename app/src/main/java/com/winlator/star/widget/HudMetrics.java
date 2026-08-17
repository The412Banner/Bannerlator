package com.winlator.star.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Shared live-metric collector for the performance HUDs. Encapsulates the sysfs/Android
 * readers (GPU load, temperature, RAM, power/charging) ported from {@link FrameRating},
 * plus the two metrics GameHub's HUD adds: overall CPU usage % (from {@code /proc/stat})
 * and a dual-battery power fix that sums the per-cell current channels.
 *
 * Not thread-safe; call from a single (UI) thread on the HUD's refresh tick.
 */
public class HudMetrics {
    private final Context context;

    public HudMetrics(Context context) { this.context = context; }

    // ---- CPU usage % (/proc/stat aggregate delta) -------------------------
    private long prevCpuTotal = 0, prevCpuIdle = 0;

    /** Overall device CPU usage 0..100, computed from the delta since the last call. */
    public float getCPUUsage() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = r.readLine();
            if (line == null || !line.startsWith("cpu ")) return 0;
            String[] p = line.trim().split("\\s+");
            long total = 0, idle = 0;
            // fields: user nice system idle iowait irq softirq steal ...
            for (int i = 1; i < p.length; i++) {
                long v = Long.parseLong(p[i]);
                total += v;
                if (i == 4 || i == 5) idle += v; // idle + iowait
            }
            long dTotal = total - prevCpuTotal;
            long dIdle = idle - prevCpuIdle;
            prevCpuTotal = total;
            prevCpuIdle = idle;
            if (dTotal <= 0) return 0;
            float usage = (dTotal - dIdle) * 100f / dTotal;
            return Math.max(0, Math.min(100, usage));
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- GPU load % (ported from FrameRating) -----------------------------
    public int getGPULoad() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpubusy"));
            String line = reader.readLine();
            reader.close();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    long busy = Long.parseLong(parts[0]);
                    long total = Long.parseLong(parts[1]);
                    if (total != 0) return (int) ((busy * 100) / total);
                }
            }
        } catch (Exception e) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader("/sys/class/misc/mali0/device/utilisation"));
                String line = reader.readLine();
                reader.close();
                if (line != null) return Integer.parseInt(line.trim());
            } catch (Exception e2) {}
        }
        return 0;
    }

    // ---- RAM % ------------------------------------------------------------
    public float getRAMPercent() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem <= 0) return 0;
        return (mi.totalMem - mi.availMem) * 100f / mi.totalMem;
    }

    // ---- Temperature (CPU thermal zones, ported from FrameRating) ---------
    private static final String[] THERMAL_PATHS = {
        "/sys/class/thermal/thermal_zone0/temp", "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone7/temp", "/sys/class/thermal/thermal_zone10/temp",
        "/sys/devices/virtual/thermal/thermal_zone0/temp", "/sys/class/hwmon/hwmon0/temp1_input",
        "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp"
    };
    private String[] cpuThermalPaths = null;

    public float getTemperature() {
        float max = 0;
        for (String path : discoverCpuThermalPaths()) {
            float t = readTemp(path);
            if (t > max) max = t;
        }
        if (max > 0) return max;
        for (String path : THERMAL_PATHS) {
            float t = readTemp(path);
            if (t > 0) return t;
        }
        return 0;
    }

    private String[] discoverCpuThermalPaths() {
        if (cpuThermalPaths != null) return cpuThermalPaths;
        ArrayList<String> found = new ArrayList<>();
        try {
            File thermalDir = new File("/sys/class/thermal");
            File[] zones = thermalDir.listFiles((dir, name) -> name.startsWith("thermal_zone"));
            if (zones != null) {
                for (File zone : zones) {
                    try (BufferedReader r = new BufferedReader(new FileReader(new File(zone, "type")))) {
                        String type = r.readLine();
                        if (type == null) continue;
                        type = type.trim().toLowerCase(Locale.ENGLISH);
                        if (type.contains("cpu") && !type.contains("gpu")) {
                            File tempFile = new File(zone, "temp");
                            if (tempFile.canRead()) found.add(tempFile.getAbsolutePath());
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        cpuThermalPaths = found.toArray(new String[0]);
        return cpuThermalPaths;
    }

    private float readTemp(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            if (line != null) {
                float temp = Float.parseFloat(line.trim());
                if (temp > 1000) temp /= 1000.0f;
                if (temp > 0 && temp < 150) return temp;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ---- Power (W) + charging --------------------------------------------
    public static final class Battery {
        public final float watts; public final boolean charging;
        Battery(float watts, boolean charging) { this.watts = watts; this.charging = charging; }
    }

    /** power_supply current_now channels (µA) for the dual-battery sum. */
    private static final String[] CURRENT_CHANNELS = {
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/main/current_now",
    };

    /**
     * @param dualBattery when true, sum the per-cell current channels (battery + bms/main)
     *                    to correct devices that report only one cell's current and read low.
     */
    public Battery getBattery(boolean dualBattery) {
        Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean charging = false;
        int voltageMv = 0;
        if (status != null) {
            charging = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            voltageMv = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        }
        long microAmps;
        if (dualBattery) {
            long sum = 0; int n = 0;
            for (String path : CURRENT_CHANNELS) {
                Long v = readLong(path);
                if (v != null) { sum += Math.abs(v); n++; }
            }
            if (n > 0) {
                microAmps = -sum; // treat as discharge magnitude
            } else {
                microAmps = readCurrentNowFallback();
            }
        } else {
            microAmps = readCurrentNowFallback();
        }
        float watts = 0f;
        if (microAmps < 0) {
            watts = (Math.abs(microAmps) * (float) voltageMv) / 1_000_000_000.0f;
        }
        return new Battery(watts, charging);
    }

    private long readCurrentNowFallback() {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        return bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
    }

    private Long readLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            if (line != null) return Long.parseLong(line.trim());
        } catch (Exception ignored) {}
        return null;
    }
}
