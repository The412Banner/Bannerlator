package com.winlator.star.core;

import android.opengl.GLES10;

public abstract class DefaultVersion {
    public static final String BOX64 = "0.3.7";
    public static final String WOWBOX64 = "0.3.7";
    public static final String FEXCORE = "2508";
    public static final String WRAPPER = "System";
    public static final String WRAPPER_ADRENO = "turnip-sdk36";
    // Direct Vulkan ICD turnip (Mesa Turnip 26.1.0, ICD format). Loaded as a plain system
    // Vulkan ICD (VK_ICD_FILENAMES -> freedreno_icd.aarch64.json) WITHOUT the adrenotools /
    // linkernsbypass linker-namespace hook, so it works on Android < 11 (e.g. SD845/Adreno 630
    // on Android 10). Asset: graphics_driver/turnip-26.1.0.tzst (Mesa, MIT; from Winlator v11.1).
    public static final String WRAPPER_TURNIP_ICD = "turnip-26.1.0";

    private static String dxvkDefault = null;

    public static String getDxvkDefault() {
        if (dxvkDefault == null) {
            try {
                dxvkDefault = GPUInformation.getRenderer(null, null).contains("Mali") ? "1.10.3" : "2.3.1-arm64ec-gplasync";
            } catch (UnsatisfiedLinkError e) {
                try {
                    String renderer = GLES10.glGetString(GLES10.GL_RENDERER);
                    dxvkDefault = (renderer != null && renderer.contains("Mali")) ? "1.10.3" : "2.3.1-arm64ec-gplasync";
                } catch (Exception e2) {
                    dxvkDefault = "2.3.1-arm64ec-gplasync";
                }
            }
        }
        return dxvkDefault;
    }

    private static String vegasDefault = null;

    public static String getVegasDefault() {
        if (vegasDefault == null) {
            vegasDefault = "2.7.3";
        }
        return vegasDefault;
    }

    public static final String D8VK = "1.0";
    public static final String VKD3D = "None";
}
