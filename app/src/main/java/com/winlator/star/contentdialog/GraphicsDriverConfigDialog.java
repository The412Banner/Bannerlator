package com.winlator.star.contentdialog;

import java.util.HashMap;
import java.util.Map;

public class GraphicsDriverConfigDialog {

    public static HashMap<String, String> parseGraphicsDriverConfig(String graphicsDriverConfig) {
        HashMap<String, String> mappedConfig = new HashMap<>();
        String[] configElements = graphicsDriverConfig.split(";");
        for (String element : configElements) {
            String key;
            String value;
            String[] splittedElement = element.split("=");
            key = splittedElement[0];
            if (splittedElement.length > 1)
                value = element.split("=")[1];
            else
                value = "";
            mappedConfig.put(key, value);
        }
        return mappedConfig;
    }

    public static String toGraphicsDriverConfig(HashMap<String, String> config) {
        String graphicsDriverConfig = "";
        for (Map.Entry<String, String> entry : config.entrySet()) {
            graphicsDriverConfig += entry.getKey() + "=" + entry.getValue() + ";";
        }
        return graphicsDriverConfig.substring(0, graphicsDriverConfig.length() - 1);
    }

    public static String getVersion(String graphicsDriverConfig) {
        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);
        return config.get("version");
    }

    public static String getExtensionsBlacklist(String graphicsDriverConfig) {
        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);
        return config.get("blacklistedExtensions");
    }
}
