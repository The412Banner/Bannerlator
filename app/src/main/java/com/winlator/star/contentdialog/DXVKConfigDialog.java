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
        String framerate = config.get("framerate");
        StringBuilder contentBuilder = new StringBuilder();
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            contentBuilder.append("dxgi.maxFrameRate = ").append(framerate).append("; ");
            contentBuilder.append("d3d9.maxFrameRate = ").append(framerate);
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        // Append vegas-specific defaults always — harmless for plain DXVK
        {
            if (contentBuilder.length() > 0) contentBuilder.append("; ");
            contentBuilder.append("dxvk.enableStarProfile = Auto; ");
            contentBuilder.append("vegas.enableUpscaler = Auto");
        }

        String content = contentBuilder.toString();
        if (!content.isEmpty())
            envVars.put("DXVK_CONFIG", content);

        if (!config.get("async").isEmpty() && !config.get("async").equals("0"))
            envVars.put("DXVK_ASYNC", "1");
        if (!config.get("asyncCache").isEmpty() && !config.get("asyncCache").equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
        envVars.put("DXVK_STATE_CACHE_PATH", context.getFilesDir() + "/imagefs/" + ImageFs.CACHE_PATH);

        // DXVK_CONFIG_FILE (config source path, e.g. /storage/emulated/0/dxvk.conf)
        String configFile = config.get("dxvkConfigFile");
        if (configFile != null && !configFile.isEmpty() && !configFile.equals("0") && !configFile.equals("None")) {
            envVars.put("DXVK_CONFIG_FILE", configFile);
        }
    }
}
