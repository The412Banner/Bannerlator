package com.winlator.star.core;

import android.content.Context;
import android.util.Log;

import com.winlator.star.contents.AdrenotoolsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * "Mali (PanVK)" mode: the settings for FristOneRR's PanVK driver on an Arm Mali GPU
 * (https://github.com/FristOneRR/FristOneRR-Panvk-Driver, MIT). Lives in the Wayland driver settings
 * (the gear), shown only on a Mali (vendor 0x13B5); stored in graphicsDriverConfig, so the shortcut's
 * copy overrides the container's like every other key there.
 * <ul>
 *   <li>{@value #KEY_MODE} "1"/"0" — absent = on when the driver's name contains "panvk". On: the
 *       Wayland adapter's three Mali switches (BANNER_MALI_*), each with its own sub-switch (absent =
 *       on; NO_ACQUIRE_SIGNAL only counts with NO_SUBMIT_WAITS, as in the adapter). The adapter hides
 *       the extensions itself, so WRAPPER_EXTENSION_BLACKLIST is left as the user set it. While the key
 *       is absent BCn emulation also defaults to none (the gear writes bcnEmulation=none when Mali
 *       mode is switched on).</li>
 *   <li>the five driver variables the README calls safe — {@value #KEY_HEAP}, {@value #KEY_TILER_HEAP},
 *       {@value #KEY_POLY_HEAP}, {@value #KEY_ATOM_STRIDE}, {@value #KEY_TRACE}; empty = unset = not
 *       exported, out-of-range = not exported.</li>
 * </ul>
 * Nothing here runs on any other GPU, even when a config carries these keys (a container copied
 * from a Mali phone), so Adreno launches are unchanged. graphicsDriverConfig never travels in a
 * community config apart from its driver version and bcnCompatSparse, so these keys stay on the device.
 */
public final class MaliPanvk {
    private static final String TAG = "XServerVulkan";

    public static final int VENDOR_ARM = 0x13B5;

    public static final String KEY_MODE = "maliMode";
    public static final String KEY_HEAP = "panvkHeapMb";
    public static final String KEY_TILER_HEAP = "panvkTilerHeapMb";
    public static final String KEY_POLY_HEAP = "panvkPolyHeapMb";
    public static final String KEY_ATOM_STRIDE = "panvkAtomStride";
    public static final String KEY_TRACE = "panvkTrace";
    /** Mali mode's adapter sub-switches, "0" = off, anything else = on. */
    public static final String KEY_HIDE_EXTS = "maliHideExts";
    public static final String KEY_NO_SUBMIT_WAITS = "maliNoSubmitWaits";
    public static final String KEY_NO_ACQUIRE_SIGNAL = "maliNoAcquireSignal";
    /** Every key this mode owns (dialogs that rebuild graphicsDriverConfig carry these through). */
    public static final List<String> KEYS = Arrays.asList(
            KEY_MODE, KEY_HIDE_EXTS, KEY_NO_SUBMIT_WAITS, KEY_NO_ACQUIRE_SIGNAL,
            KEY_HEAP, KEY_TILER_HEAP, KEY_POLY_HEAP, KEY_ATOM_STRIDE, KEY_TRACE);

    // README ranges (MB).
    public static final int HEAP_MIN = 256, HEAP_MAX = 16384;
    public static final int TILER_HEAP_MIN = 16, TILER_HEAP_MAX = 2048, TILER_HEAP_DEFAULT = 512;
    public static final int POLY_HEAP_MIN = 4, POLY_HEAP_MAX = 512, POLY_HEAP_DEFAULT = 16;
    /** Atom strides PANVK_ATOM_STRIDE takes besides auto (auto = unset). */
    public static final List<String> ATOM_STRIDES = Arrays.asList("56", "64");


    private static volatile Integer vendorId;

    private MaliPanvk() {}

    /** True on an Arm Mali GPU. The probe runs once per process. */
    public static boolean isMaliGpu() {
        Integer v = vendorId;
        if (v == null) {
            try {
                v = GPUInformation.getVendorID(null, null);
            } catch (Throwable t) {
                return false; // probe unavailable: not Mali, and not cached so a later call retries
            }
            vendorId = v;
        }
        return v == VENDOR_ARM;
    }

    /** Whether the AdrenoTools driver {@code driverId} (its id or meta.json name) mentions "panvk". */
    public static boolean looksLikePanvk(Context context, String driverId) {
        if (driverId == null || driverId.isEmpty()) return false;
        if (driverId.toLowerCase().contains("panvk")) return true;
        try {
            String name = new AdrenotoolsManager(context).getDriverName(driverId);
            return name != null && name.toLowerCase().contains("panvk");
        } catch (Exception e) {
            return false;
        }
    }

    /** Mali mode for this config: the stored switch, else on for a PanVK driver. */
    public static boolean modeOn(Context context, Map<String, String> cfg, String driverId) {
        String v = cfg.get(KEY_MODE);
        if ("1".equals(v)) return true;
        if ("0".equals(v)) return false;
        return looksLikePanvk(context, driverId);
    }

    /** A sub-switch's state: on unless stored "0". */
    public static boolean subOn(Map<String, String> cfg, String key) {
        return !"0".equals(cfg.get(key));
    }

    /** {@code value} when it is a whole number within [min, max], else null. */
    public static String validMb(String value, int min, int max) {
        if (value == null) return null;
        try {
            int n = Integer.parseInt(value.trim());
            return n >= min && n <= max ? String.valueOf(n) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Launch env (X11 or Wayland). Mali only — on any other GPU it returns at once and every key is
     * ignored: Mali mode's adapter switches (with no stored switch it also sets bcnEmulation=none in
     * {@code cfg} before the BCn block reads it), and each PANVK_* that is set. Returns what was
     * applied, or null when nothing was.
     */
    public static String applyToLaunchEnv(Context context, EnvVars envVars, Map<String, String> cfg, String driverId) {
        if (!isMaliGpu()) return null;
        List<String> applied = new ArrayList<>();
        if (modeOn(context, cfg, driverId)) {
            boolean hide = subOn(cfg, KEY_HIDE_EXTS);
            boolean noWaits = subOn(cfg, KEY_NO_SUBMIT_WAITS);
            boolean noAcquire = noWaits && subOn(cfg, KEY_NO_ACQUIRE_SIGNAL);
            if (hide) envVars.put("BANNER_MALI_HIDE_EXTS", "1");
            if (noWaits) envVars.put("BANNER_MALI_NO_SUBMIT_WAITS", "1");
            if (noAcquire) envVars.put("BANNER_MALI_NO_ACQUIRE_SIGNAL", "1");
            applied.add("mali mode (HIDE_EXTS=" + (hide ? 1 : 0) + " NO_SUBMIT_WAITS=" + (noWaits ? 1 : 0)
                    + " NO_ACQUIRE_SIGNAL=" + (noAcquire ? 1 : 0) + ")");
            if (cfg.get(KEY_MODE) == null || cfg.get(KEY_MODE).isEmpty()) {
                cfg.put("bcnEmulation", "none");
                applied.add("bcnEmulation=none (default)");
            }
        }
        String heap = validMb(cfg.get(KEY_HEAP), HEAP_MIN, HEAP_MAX);
        if (heap != null) { envVars.put("PANVK_HEAP_MB", heap); applied.add("PANVK_HEAP_MB=" + heap); }
        String tiler = validMb(cfg.get(KEY_TILER_HEAP), TILER_HEAP_MIN, TILER_HEAP_MAX);
        if (tiler != null) { envVars.put("PANVK_TILER_HEAP_MB", tiler); applied.add("PANVK_TILER_HEAP_MB=" + tiler); }
        String poly = validMb(cfg.get(KEY_POLY_HEAP), POLY_HEAP_MIN, POLY_HEAP_MAX);
        if (poly != null) { envVars.put("PANVK_POLY_HEAP_MB", poly); applied.add("PANVK_POLY_HEAP_MB=" + poly); }
        String atom = cfg.get(KEY_ATOM_STRIDE);
        if (atom != null && ATOM_STRIDES.contains(atom)) {
            envVars.put("PANVK_ATOM_STRIDE", atom);
            applied.add("PANVK_ATOM_STRIDE=" + atom);
        }
        if ("1".equals(cfg.get(KEY_TRACE))) { envVars.put("PANVK_TRACE", "1"); applied.add("PANVK_TRACE=1"); }
        if (applied.isEmpty()) return null;
        String summary = "Mali (PanVK): " + String.join(", ", applied) + " (driver " + driverId + ")";
        Log.i(TAG, summary);
        return summary;
    }
}
