package com.winlator.star.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The frame-generation engines the in-game menu can switch between while a game runs. All of them
 * generate in our own compositor (the X11 Vulkan renderer or the Wayland compositor), which can
 * change engine at any time. The guest-side win-fg layer is loaded into the game at launch, so a
 * session that started with it keeps it.
 */
public final class FgEngineSwitch {
    public static final String OFF = "off";
    public static final String LSFG_NATIVE = "lsfg-native";
    /** "bionic" = win-fg; here always Win-FG Native (in our compositor). */
    public static final String WIN_FG = "bionic";
    public static final String WFG = "wfg";

    private FgEngineSwitch() {}

    /**
     * Engines offered in menu order; empty = the engine stays as launched.
     *
     * @param nativeRenderer   the session presents through our Vulkan compositor
     * @param guestLayerLoaded the guest-side win-fg layer was loaded at launch
     * @param winFgNativeOk    "bionic" would run as Win-FG Native in this session (not the guest layer)
     */
    public static List<String> choices(boolean nativeRenderer, boolean guestLayerLoaded, boolean winFgNativeOk) {
        if (!nativeRenderer || guestLayerLoaded) return Collections.emptyList();
        List<String> out = new ArrayList<>(4);
        out.add(OFF);
        out.add(LSFG_NATIVE);
        if (winFgNativeOk) out.add(WIN_FG);
        out.add(WFG);
        return Collections.unmodifiableList(out);
    }

    /** The level the new engine starts at: Win-FG is Off / On (On = 2), the others keep 2-4. */
    public static int carryMultiplier(String engine, int multiplier) {
        if (multiplier < 2 || OFF.equals(engine)) return 0;
        if (WIN_FG.equals(engine)) return 2;
        return Math.min(multiplier, 4);
    }
}
