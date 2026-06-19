package com.winlator.star.contentdialog;

import android.content.Context;

import com.winlator.star.container.Container;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.KeyValueSet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class WineD3DConfigDialog {
    public static String DEFAULT_CONFIG = Container.DEFAULT_DXWRAPPERCONFIG;

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static List<String> loadGpuNames(Context context) {
        List<String> entries = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(FileUtils.readString(context, "gpu_cards.json"));
            for (int i = 0; i < arr.length(); i++)
                entries.add(arr.getJSONObject(i).getString("name"));
        } catch (JSONException ignored) {}
        return entries;
    }

    public static String getDeviceIdFromGPUName(Context context, String gpuName) {
        try {
            JSONArray arr = new JSONArray(FileUtils.readString(context, "gpu_cards.json"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj.getString("name").contains(gpuName)) return obj.getString("deviceID");
            }
        } catch (JSONException ignored) {}
        return "";
    }

    public static String getVendorIdFromGPUName(Context context, String gpuName) {
        try {
            JSONArray arr = new JSONArray(FileUtils.readString(context, "gpu_cards.json"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj.getString("name").contains(gpuName)) return obj.getString("vendorID");
            }
        } catch (JSONException ignored) {}
        return "";
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars vars) {
        String deviceID = getDeviceIdFromGPUName(context, config.get("gpuName"));
        String vendorID = getVendorIdFromGPUName(context, config.get("vendorID"));
        String wined3dConfig = "csmt=0x" + config.get("csmt") +
            ",strict_shader_math=0x" + config.get("strict_shader_math") +
            ",OffscreenRenderingMode=" + config.get("OffscreenRenderingMode") +
            ",VideoMemorySize=" + config.get("videoMemorySize") +
            ",VideoPciDeviceID=" + deviceID +
            ",VideoPciVendorID=" + vendorID +
            ",renderer=" + config.get("renderer");
        vars.put("WINE_D3D_CONFIG", wined3dConfig);
    }
}
