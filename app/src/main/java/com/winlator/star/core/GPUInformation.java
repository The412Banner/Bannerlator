package com.winlator.star.core;

import android.content.Context;

public abstract class GPUInformation {

    public static boolean isAdrenoGPU(Context context) {
        return getRenderer(null, context).toLowerCase().contains("adreno");
    }

    public static boolean isDriverSupported(String driverName, Context context) {
        if (!isAdrenoGPU(context) && !driverName.equals("System"))
            return false;

        // Direct Vulkan ICD turnip (turnip-26.1.0) is NOT an adrenotools driver: it is loaded
        // as a plain system Vulkan ICD, so the native getRenderer() adrenotools probe cannot
        // describe it (and that very probe is what fails on Android < 11, hiding turnip-sdk36
        // from the picker on devices like the SD845/Adreno 630 / Android 10 reporter). Gate it
        // on the GPU family only (Turnip == Freedreno == Adreno) so it stays selectable exactly
        // where it works, without going through the failing hook probe.
        if (DefaultVersion.WRAPPER_TURNIP_ICD.equals(driverName))
            return isAdrenoGPU(context);

        String renderer = getRenderer(driverName, context);

        return !renderer.toLowerCase().contains("unknown");
    }
    public native static String getVulkanVersion(String driverName, Context context);
    public native static int getVendorID(String driverName, Context context);
    public native static String getRenderer(String driverName, Context context);
    public native static String[] enumerateExtensions(String driverName, Context context);

    static {
        System.loadLibrary("winlator");
    }
}
