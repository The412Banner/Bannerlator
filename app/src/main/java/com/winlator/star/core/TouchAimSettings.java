package com.winlator.star.core;

import android.content.SharedPreferences;

import com.winlator.star.xserver.AimTracker;

import java.util.function.Function;

/**
 * Where the Touch and Aim mode settings live and how older settings carry over.
 *
 * Touch: touchpad (a drag moves the pointer) / direct (the pointer sits under the finger) /
 * touchscreen (the finger presses where it lands; per game only, the old "simTouchScreen").
 * Aim mode: auto / on (the old Relative Mouse) / off.
 *
 * App: prefs "touch_mode" and "aim_mode". Game: shortcut extras "touchMode", "simTouchScreen",
 * "aimMode". Container (what the in-game menu saves in a session without a shortcut): the same extras.
 * Carried over: Relative Mouse "1" (shortcut or container "relativeMouse") = Aim On; Cursor to
 * Touch ("move_cursor_to_touchpoint") or the old Touchscreen mode ("touchscreen_toggle") = Direct.
 */
public final class TouchAimSettings {
    public static final String TOUCHPAD = "touchpad";
    public static final String DIRECT = "direct";
    public static final String TOUCHSCREEN = "touchscreen";

    public static final String AUTO = "auto";
    public static final String ON = "on";
    public static final String OFF = "off";

    public static final String PREF_TOUCH = "touch_mode";
    public static final String PREF_AIM = "aim_mode";
    public static final String EXTRA_TOUCH = "touchMode";
    public static final String EXTRA_SIM_TOUCH = "simTouchScreen";
    public static final String EXTRA_AIM = "aimMode";
    public static final String EXTRA_RELATIVE_MOUSE = "relativeMouse";

    private TouchAimSettings() {}

    public static boolean isTouch(String v) {
        return TOUCHPAD.equals(v) || DIRECT.equals(v) || TOUCHSCREEN.equals(v);
    }

    static boolean isAppTouch(String v) {
        return TOUCHPAD.equals(v) || DIRECT.equals(v);
    }

    public static boolean isAim(String v) {
        return AUTO.equals(v) || ON.equals(v) || OFF.equals(v);
    }

    /** App Touch from its stored value, else from the settings it replaces. */
    public static String appTouch(String stored, boolean cursorToTouch, boolean oldTouchscreenMode) {
        if (isAppTouch(stored)) return stored;
        return cursorToTouch || oldTouchscreenMode ? DIRECT : TOUCHPAD;
    }

    /** App Aim mode from its stored value; Auto when there is none. */
    public static String appAimOf(String stored) {
        return isAim(stored) ? stored : AUTO;
    }

    /** A game's own Touch, or null when it follows the app. [extra] returns null or "" for a missing key. */
    public static String gameTouch(Function<String, String> extra) {
        if ("1".equals(extra.apply(EXTRA_SIM_TOUCH))) return TOUCHSCREEN;
        String v = extra.apply(EXTRA_TOUCH);
        return isAppTouch(v) ? v : null;
    }

    /** A game's (or container's) own Aim mode, or null when it follows the next level. */
    public static String ownAim(Function<String, String> extra) {
        String v = extra.apply(EXTRA_AIM);
        if (isAim(v)) return v;
        return "1".equals(extra.apply(EXTRA_RELATIVE_MOUSE)) ? ON : null;
    }

    public static String resolveTouch(Function<String, String> game, Function<String, String> container, String appTouch) {
        String v = game != null ? gameTouch(game) : null;
        if (v == null && container != null) v = gameTouch(container);
        return v != null ? v : appTouch;
    }

    public static String resolveAim(Function<String, String> game, Function<String, String> container, String appAim) {
        String v = game != null ? ownAim(game) : null;
        if (v == null && container != null) v = ownAim(container);
        return v != null ? v : appAim;
    }

    public static String appTouch(SharedPreferences p) {
        return appTouch(p.getString(PREF_TOUCH, null),
                p.getBoolean("move_cursor_to_touchpoint", false),
                p.getBoolean("touchscreen_toggle", false));
    }

    public static String appAim(SharedPreferences p) {
        return appAimOf(p.getString(PREF_AIM, null));
    }

    public static int trackerMode(String aim) {
        if (ON.equals(aim)) return AimTracker.MODE_ON;
        if (OFF.equals(aim)) return AimTracker.MODE_OFF;
        return AimTracker.MODE_AUTO;
    }
}
