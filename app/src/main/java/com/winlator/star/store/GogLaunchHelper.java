package com.winlator.star.store;

import android.app.Activity;

/**
 * Launch bridge for GOG games in Star (Winlator fork).
 * Delegates to StarLaunchBridge which shows a container picker and
 * writes a .desktop shortcut with cover art.
 */
public final class GogLaunchHelper {

    private GogLaunchHelper() {}

    public static void addToLauncher(Activity activity, String gameName,
                                     String exePath, String coverArtUrl) {
        StarLaunchBridge.addToLauncher(activity, gameName, exePath, coverArtUrl);
    }

    public static void addToLauncher(Activity activity, String gameName, String exePath) {
        StarLaunchBridge.addToLauncher(activity, gameName, exePath, null);
    }
}
