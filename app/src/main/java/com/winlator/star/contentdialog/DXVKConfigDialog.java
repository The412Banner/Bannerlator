package com.winlator.star.contentdialog;

import android.content.Context;

import com.winlator.star.R;
import com.winlator.star.container.Container;
import com.winlator.star.contents.ContentProfile;
import com.winlator.star.contents.ContentsManager;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.KeyValueSet;
import com.winlator.star.core.StringUtils;
import com.winlator.star.core.VKD3DVersionItem;
import com.winlator.star.xenvironment.ImageFs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DXVKConfigDialog {
    public static final String DEFAULT_CONFIG = Container.DEFAULT_DXWRAPPERCONFIG;
    public static final int DXVK_TYPE_NONE = 0;
    public static final int DXVK_TYPE_ASYNC = 1;
    public static final int DXVK_TYPE_GPLASYNC = 2;
    public static final String[] VKD3D_FEATURE_LEVEL = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};
    // Sentinel version for the DDraw-Wrapper=D7VK version dropdown. Means "use the bundled, offline
    // d7vk.tzst asset" (the default). Any other value is a downloaded CONTENT_TYPE_D7VK profile,
    // identified by "verName-verCode" (mirrors the VKD3D version identifier).
    public static final String D7VK_BUNDLED = "Bundled (default)";

    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    public static Integer tryGetMajor(String s) {
        if (s == null) return null;
        Matcher m = SEMVER.matcher(s);
        if (!m.find()) return null;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    public static int compareVersion(String varA, String varB) {
        final String[] levelsA = varA.split("\\.");
        final String[] levelsB = varB.split("\\.");
        int minLen = Math.min(levelsA.length, levelsB.length);
        for (int i = 0; i < minLen; i++) {
            int numA = Integer.parseInt(levelsA[i]);
            int numB = Integer.parseInt(levelsB[i]);
            if (numA != numB) return numA - numB;
        }
        return levelsA.length - levelsB.length;
    }

    public static int getDXVKType(String version) {
        if (version.contains("gplasync")) return DXVK_TYPE_GPLASYNC;
        if (version.contains("async")) return DXVK_TYPE_ASYNC;
        return DXVK_TYPE_NONE;
    }

    public static List<String> loadDxvkVersionList(Context context, ContentsManager contentsManager, boolean isArm64EC) {
        String[] original = context.getResources().getStringArray(R.array.dxvk_version_entries);
        List<String> list = new ArrayList<>(Arrays.asList(original));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            String entry = ContentsManager.getEntryName(profile);
            int dash = entry.indexOf('-');
            list.add(entry.substring(dash + 1));
        }
        list.removeIf(v -> v.contains("arm64ec") && !isArm64EC);
        return list;
    }

    public static List<String> loadVkd3dVersionList(Context context, ContentsManager contentsManager) {
        String[] original = context.getResources().getStringArray(R.array.vkd3d_version_entries);
        List<String> list = new ArrayList<>(Arrays.asList(original));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            list.add(new VKD3DVersionItem(profile.verName, profile.verCode).getIdentifier());
        }
        return list;
    }

    public static List<String> loadD7vkVersionList(Context context, ContentsManager contentsManager) {
        // The bundled d7vk.tzst is always the first/default option (offline, zero-download).
        // Downloaded catalog profiles follow, identified as "verName-verCode" like VKD3D.
        List<String> list = new ArrayList<>();
        list.add(D7VK_BUNDLED);
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_D7VK)) {
            list.add(new VKD3DVersionItem(profile.verName, profile.verCode).getIdentifier());
        }
        return list;
    }

    public static List<String> loadVegasVersionList(Context context, ContentsManager contentsManager) {
        String[] original = context.getResources().getStringArray(R.array.vegas_version_entries);
        List<String> list = new ArrayList<>(Arrays.asList(original));

        // vegas WCP profiles have type CONTENT_TYPE_VEGAS, verName like "vegas-2.7.3"
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS)) {
            if (profile.verName != null && profile.verName.startsWith("vegas-")) {
                String ver = profile.verName.substring("vegas-".length());
                if (!list.contains(ver)) list.add(ver);
            }
        }

        return list;
    }

    public static List<String> loadVegasConfigSourceList(Context context) {
        String[] original = context.getResources().getStringArray(R.array.vegas_config_source_entries);
        return new ArrayList<>(Arrays.asList(original));
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        setEnvVars(context, config, envVars, null);
    }

    /**
     * @param logDirOverride where DXVK/VKD3D should write their logs — the launching activity passes
     *                       this game's folder so its logs sit beside the Wine log instead of in one
     *                       shared pile. Null keeps the old flat behaviour (used by config previews,
     *                       which have no game context).
     */
    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars,
                                  java.io.File logDirOverride) {
        String configFile = config.get("dxvkConfigFile");
        boolean hasConfigFile = configFile != null && !configFile.isEmpty() && !configFile.equals("0") && !configFile.equals("None");

        // DXVK_FRAME_RATE is a standalone env var, independent of DXVK_CONFIG / DXVK_CONFIG_FILE.
        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        // When a custom DXVK_CONFIG_FILE is selected, skip DXVK_CONFIG entirely
        // so the user's config file has full control (DXVK_CONFIG would override it).
        if (!hasConfigFile) {
            StringBuilder contentBuilder = new StringBuilder();
            if (!framerate.isEmpty() && !framerate.equals("0")) {
                contentBuilder.append("dxgi.maxFrameRate = ").append(framerate).append("; ");
                contentBuilder.append("d3d9.maxFrameRate = ").append(framerate);
            }

            // Append vegas-specific defaults — harmless for plain DXVK
            {
                if (contentBuilder.length() > 0) contentBuilder.append("; ");
                contentBuilder.append("dxvk.enableStarProfile = Auto; ");
                contentBuilder.append("vegas.enableUpscaler = Auto");
            }

            String content = contentBuilder.toString();
            if (!content.isEmpty())
                envVars.put("DXVK_CONFIG", content);
        }

        if (!config.get("async").isEmpty() && !config.get("async").equals("0"))
            envVars.put("DXVK_ASYNC", "1");
        if (!config.get("asyncCache").isEmpty() && !config.get("asyncCache").equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
        envVars.put("DXVK_STATE_CACHE_PATH", context.getFilesDir() + "/imagefs/" + ImageFs.CACHE_PATH);

        // Co-locate the DXVK/DXGI (and VKD3D-Proton) logs in the same user-chosen folder as
        // wine_debug.log (issue #70). These stay SEPARATE files (<app>_d3d11.log / <app>_dxgi.log /
        // vkd3d-proton.log), just written next to the wine log instead of the game working dir.
        // Honour the Log Manager's "DXVK & VKD3D" switch. DXVK logs unless told otherwise, so
        // turning it off means silencing it explicitly rather than just not choosing a path.
        //
        // Check the switch BEFORE resolving a directory: resolving one CREATES it, so asking first
        // is what keeps a switched-off Log Manager from still littering an empty folder per game on
        // every launch. Callers pass null for logDirOverride when logging is off, for the same
        // reason — see XServerDisplayActivity.dxvkLogDir().
        boolean dxvkLogs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context).getBoolean("enable_dxvk_logs", true);
        if (!dxvkLogs) {
            envVars.put("DXVK_LOG_LEVEL", "none");
            envVars.put("VKD3D_DEBUG", "none");
        } else {
            java.io.File logDir = logDirOverride != null
                    ? logDirOverride
                    : com.winlator.star.core.LogLocation.resolveLogDir(context);
            if (logDir != null) {
                envVars.put("DXVK_LOG_PATH", logDir.getAbsolutePath());
                envVars.put("VKD3D_LOG_FILE", new java.io.File(logDir, "vkd3d-proton.log").getAbsolutePath());
            }
        }

        // DXVK_CONFIG_FILE (config source path, e.g. /storage/emulated/0/dxvk.conf)
        if (hasConfigFile) {
            envVars.put("DXVK_CONFIG_FILE", configFile);
        }
    }
}
