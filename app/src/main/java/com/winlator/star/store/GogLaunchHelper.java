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
        StarLaunchBridge.addToLauncher(activity, gameName, exePath, coverArtUrl, "gog", false);
    }

    public static void addToLauncher(Activity activity, String gameName, String exePath) {
        StarLaunchBridge.addToLauncher(activity, gameName, exePath, null, "gog", false);
    }

    /**
     * As {@link #addToLauncher(Activity, String, String, String)}, but after the container is picked
     * offers to auto-install the game's required GOG redistributables (gap#3) into that prefix. Use
     * this from the GOG detail page so the redist trigger fires at the add-to-container moment.
     */
    public static void addToLauncherWithPrereqs(Activity activity, String gameName,
                                                String exePath, String coverArtUrl, String gameId) {
        StarLaunchBridge.addToLauncher(activity, gameName, exePath, coverArtUrl, null,
                container -> GogRedistInstaller.promptAndInstall(activity, container, gameId));
    }
}
