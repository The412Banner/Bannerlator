package com.winlator.star.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Wine's idle-thread throttle (Wine layers v17 and newer; GameNative's "Sleep(0) throttle"): a game thread that keeps
 * calling Sleep(0) / SwitchToThread with nothing to do gets a short sleep per call, which frees a CPU core. A very fast
 * frame loop can look idle too, so Wine never throttles a game whose graphics API is in WINE_SLEEP0_THROTTLE_SKIP (Wine
 * tells the API from the graphics DLLs the game loaded). Unset, Wine uses "dx7". Older layers ignore the variable.
 *
 * The setting is that list ("none" = no API skipped): on the container (default {@link #DEFAULT}) and, optionally, on a
 * game's shortcut ({@link #KEY}), which then wins.
 */
public final class IdleThrottle {
    public static final String ENV = "WINE_SLEEP0_THROTTLE_SKIP";
    /** Wine's own on/off switch for the throttle; a user who sets it decides for themselves. */
    public static final String ENV_SWITCH = "WINE_SLEEP0_THROTTLE";
    /** The container data key and the shortcut extra. */
    public static final String KEY = "sleep0ThrottleSkip";
    public static final String NONE = "none";
    public static final String DEFAULT = "dx7";
    /** Wine's API tags, in the order the value lists them. */
    public static final List<String> APIS = Collections.unmodifiableList(
            Arrays.asList("dx7", "dx8", "dx9", "dx10", "dx11", "dx12", "gl", "vk"));

    private IdleThrottle() {}

    /**
     * The APIs [value] names, in {@link #APIS} order without repeats (unknown words dropped); empty for "none"; null when
     * [value] is blank or names no API.
     */
    public static List<String> parse(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        if (v.equals(NONE)) return Collections.emptyList();
        Set<String> given = new HashSet<>(Arrays.asList(v.split("[,;\\s]+")));
        List<String> out = new ArrayList<>(APIS.size());
        for (String api : APIS) if (given.contains(api)) out.add(api);
        return out.isEmpty() ? null : out;
    }

    /** The stored / env form of [apis]: comma-joined in {@link #APIS} order, "none" when it names no API. */
    public static String join(Collection<String> apis) {
        StringBuilder sb = new StringBuilder();
        if (apis != null) {
            for (String api : APIS) {
                if (!apis.contains(api)) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(api);
            }
        }
        return sb.length() == 0 ? NONE : sb.toString();
    }

    /** [value] in its stored form, or null when it is blank or not a valid list. */
    public static String normalize(String value) {
        List<String> apis = parse(value);
        return apis == null ? null : join(apis);
    }

    /** The value a launch uses: the game's own, else the container's, else {@link #DEFAULT}. */
    public static String resolve(String gameValue, String containerValue) {
        String game = normalize(gameValue);
        if (game != null) return game;
        String container = normalize(containerValue);
        return container != null ? container : DEFAULT;
    }

    /** The throttle variable the user set in the launch env ({@link #ENV} first, then {@link #ENV_SWITCH}), or null. */
    public static String userVariable(Predicate<String> has) {
        if (has.test(ENV)) return ENV;
        if (has.test(ENV_SWITCH)) return ENV_SWITCH;
        return null;
    }

    /** The settings editors' list form (the APIs switched off, comma-joined, "" for none) of a stored value. */
    public static String toOffList(String stored) {
        List<String> apis = parse(stored);
        if (apis == null) apis = parse(DEFAULT);
        return String.join(",", apis);
    }

    /** The stored form of an editor's off list ("" = none off). */
    public static String fromOffList(String offList) {
        String v = normalize(offList);
        return v != null ? v : NONE;
    }

    /** "DX7", "OpenGL", "Vulkan": an API's short name for a row's value. */
    public static String shortName(String api) {
        switch (api) {
            case "gl": return "OpenGL";
            case "vk": return "Vulkan";
            default: return api.toUpperCase(Locale.ROOT);
        }
    }

    /** "DirectX 7", "OpenGL", "Vulkan": an API's name in the pop-up. */
    public static String longName(String api) {
        if (api.startsWith("dx")) return "DirectX " + api.substring(2);
        return shortName(api);
    }

    /** The short names of the APIs [stored] switches off, comma-separated ("" for none). */
    public static String offNames(String stored) {
        List<String> apis = parse(stored);
        if (apis == null) apis = parse(DEFAULT);
        List<String> names = new ArrayList<>(apis.size());
        for (String api : apis) names.add(shortName(api));
        return String.join(", ", names);
    }
}
