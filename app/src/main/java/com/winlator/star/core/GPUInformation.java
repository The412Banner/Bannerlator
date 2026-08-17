package com.winlator.star.core;

import android.content.Context;

public abstract class GPUInformation {

    public static boolean isAdrenoGPU(Context context) {
        return getRenderer(null, context).toLowerCase().contains("adreno");
    }

    public static boolean isDriverSupported(String driverName, Context context) {
        if (!isAdrenoGPU(context) && !driverName.equals("System"))
            return false;

        // turnip-26.1.0 (the direct Vulkan ICD) has been removed — never report it as supported,
        // so a saved container pointing at it can't keep it alive in any picker.
        if (DefaultVersion.REMOVED_TURNIP_ICD.equals(driverName))
            return false;

        String renderer = getRenderer(driverName, context);

        return !renderer.toLowerCase().contains("unknown");
    }
    /**
     * Extract a short GPU model (e.g. {@code "Adreno 750"}, {@code "Mali-G715"}, {@code "Xclipse 920"})
     * from a raw Vulkan/GL renderer string such as
     * {@code "zink Vulkan 1.4(Wrapper(Adreno (TM) 750) (MESA_TURNIP))"} — what the guest reports via
     * {@code _MESA_DRV_GPU_NAME}. Used for the perf-HUD GPU-model row so it shows the chip, not the whole
     * driver string. Falls back to the trimmed input when no known vendor token is found, so unknown
     * GPUs still show something rather than blank.
     */
    public static String extractModelName(String raw) {
        if (raw == null) return null;
        String s = raw.replace("(TM)", "").replace("(R)", "").replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher m;

        m = java.util.regex.Pattern.compile("Adreno\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) return "Adreno " + m.group(1);

        // ARM Immortalis-G### / Mali-<letter>### — keep the vendor-model form.
        m = java.util.regex.Pattern.compile("(Immortalis|Mali)[\\s-]*([A-Za-z]?\\d+[A-Za-z0-9]*)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) {
            String vendor = m.group(1);
            vendor = Character.toUpperCase(vendor.charAt(0)) + vendor.substring(1).toLowerCase();
            return vendor + "-" + m.group(2).toUpperCase();
        }

        m = java.util.regex.Pattern.compile("Xclipse\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) return "Xclipse " + m.group(1);

        m = java.util.regex.Pattern.compile("PowerVR\\s+([A-Za-z0-9]+(?:\\s+[A-Za-z0-9]+)?)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) return "PowerVR " + m.group(1);

        // Fallback for GPUs we have no explicit pattern for: strip the wrapper/driver scaffolding so a
        // raw "zink Vulkan X(Wrapper(<NAME>) (<DRIVER>))" never leaks "Wrapper" or the driver tag into
        // the HUD. Take the inner Wrapper(...) name when present, then drop MESA/driver tags and parens.
        java.util.regex.Matcher wrap = java.util.regex.Pattern.compile("(?i)wrapper\\(").matcher(s);
        if (wrap.find()) s = s.substring(wrap.end());
        s = s.replaceAll("(?i)\\(?\\bMESA[_A-Za-z0-9]*\\)?", " ") // MESA_TURNIP / (MESA...) driver tags
             .replaceAll("[()]", " ")
             .replaceAll("\\s+", " ")
             .trim();
        return s;
    }

    /**
     * Whether {@code renderer} names a Mali GPU on leegao compat_layer's Valhall floor (r32p1+). The
     * DX12/VKD3D feature-emulation layer used by the "Wrapper + compat + bcn" driver needs a Valhall
     * Mali; Bifrost/Midgard Mali and sub-r32p1 parts pass the {@code != 0x5143} vendor gate but fail
     * the layer's own floor. Runtime driver-version isn't detectable, so we gate on the GPU MODEL name,
     * normalized through the same {@link #extractModelName} used by the perf HUD. Allowlist (Valhall):
     * Mali-G57/G68/G77/G78/G310/G610/G710/G615/G715/G720/G925 and Immortalis-G715/G720/G925.
     */
    public static boolean isCompatLayerSupportedGpu(String renderer) {
        String model = extractModelName(renderer);
        if (model == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b(Mali|Immortalis)-G(\\d+)").matcher(model);
        if (!m.find()) return false;
        switch (m.group(2)) {
            case "57": case "68": case "77": case "78": case "310":
            case "610": case "710": case "615": case "715": case "720": case "925":
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether the device GPU is one of the Adreno parts that benefit from forcing Turnip's GMEM
     * (tiled) rendering path via {@code TU_DEBUG=gmem} — Adreno <b>710 / 720 / 722</b> only (NOT 732).
     * Used by the "Turnip GMEM = Auto" resolution in {@link com.winlator.star.XServerDisplayActivity}:
     * Auto adds {@code gmem} ONLY when this returns true, so every other GPU keeps an unchanged
     * environment. Reuses the same {@link #extractModelName} normalization as the perf HUD / compat
     * gate so the match is robust against the wrapper/driver scaffolding in the raw renderer string.
     */
    public static boolean isAutoGmemGpu(Context context) {
        return isAutoGmemGpu(getRenderer(null, context));
    }

    public static boolean isAutoGmemGpu(String renderer) {
        String model = extractModelName(renderer);
        if (model == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)Adreno\\s*(\\d+)").matcher(model);
        if (!m.find()) return false;
        switch (m.group(1)) {
            case "710": case "720": case "722":
                return true;
            default:
                return false;
        }
    }

    public native static String getVulkanVersion(String driverName, Context context);
    public native static int getVendorID(String driverName, Context context);
    public native static String getRenderer(String driverName, Context context);
    public native static String[] enumerateExtensions(String driverName, Context context);
    // True when the most recent native probe (getRenderer/enumerateExtensions/getVendorID)
    // selected a real installed Adrenotools custom driver that couldn't load on this GPU and
    // silently fell back to the system Vulkan ICD instead of crashing. Read right after a
    // probe to tell the user their chosen driver didn't actually engage.
    public native static boolean driverLoadedFellBack();

    static {
        System.loadLibrary("winlator");
    }
}
