package com.winlator.star.core;

import androidx.annotation.NonNull;

import java.util.Iterator;
import java.util.LinkedHashMap;

public class EnvVars implements Iterable<String> {
    private final LinkedHashMap<String, String> data = new LinkedHashMap<>();

    public EnvVars() {}

    public EnvVars(String values) {
        putAll(values);
    }

    public void put(String name, Object value) {
        data.put(name, String.valueOf(value));
    }

    public void putAll(String values) {
        if (values == null || values.isEmpty()) return;
        String[] parts = values.split(" ");
        for (String part : parts) {
            int index = part.indexOf("=");
            // Skip anything that is not NAME=VALUE instead of throwing. indexOf returns -1 for a token
            // with no '=', and substring(0, -1) then blows up — inside the launch path, so a single
            // typo in the environment box cost the whole launch rather than the one variable. index 0
            // is skipped for the same reason: "=value" has no name to bind to. Reachable from the
            // editor's raw "Edit as text" mode (which stores what you type verbatim), a hand-edited
            // .desktop, or an imported container config.
            if (index <= 0) continue;
            String name = part.substring(0, index);
            String value = part.substring(index+1);
            data.put(name, value);
        }
    }

    public void putAll(EnvVars envVars) {
        data.putAll(envVars.data);
    }

    public String get(String name) {
        return data.getOrDefault(name, "");
    }

    public void remove(String name) {
        data.remove(name);
    }

    public boolean has(String name) {
        return data.containsKey(name);
    }

    public void clear() {
        data.clear();
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    @NonNull
    @Override
    public String toString() {
        return String.join(" ", toStringArray());
    }

    public String toEscapedString() {
        String result = "";
        for (String key : data.keySet()) {
            if (!result.isEmpty()) result += " ";
            String value = data.get(key);
            result += key+"="+value.replace(" ", "\\ ");
        }
        return result;
    }

    public String[] toStringArray() {
        String[] stringArray = new String[data.size()];
        int index = 0;
        for (String key : data.keySet()) stringArray[index++] = key+"="+data.get(key);
        return stringArray;
    }

    @NonNull
    @Override
    public Iterator<String> iterator() {
        return data.keySet().iterator();
    }
}
