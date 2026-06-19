package com.winlator.star.store;

import android.content.Context;

import java.io.File;

/** Static helper that resolves install paths for GOG games. */
public final class GogInstallPath {

    private GogInstallPath() {}

    /**
     * Returns the install directory for a game.
     * Path: {filesDir}/gog_games/{dirName}
     */
    public static File getInstallDir(Context ctx, String dirName) {
        return new File(new File(ctx.getFilesDir(), "imagefs/gog_games"), dirName);
    }

    /**
     * Converts an absolute Android path under imagefs/ to a Wine Z: path.
     * Winlator maps Z: → {filesDir}/imagefs, so we strip that prefix and
     * replace forward slashes with backslashes.
     *
     * e.g. .../imagefs/gog_games/Game/game.exe → Z:\gog_games\Game\game.exe
     */
    public static String toWinePath(Context ctx, String absExePath) {
        String imageFsRoot = new File(ctx.getFilesDir(), "imagefs").getAbsolutePath();
        String rel = absExePath.startsWith(imageFsRoot)
                ? absExePath.substring(imageFsRoot.length())
                : absExePath;
        return "Z:" + rel.replace("/", "\\");
    }
}
