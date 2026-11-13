package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Static helper that resolves install paths for GOG games. */
public final class GogInstallPath {

    private GogInstallPath() {}

    /** Where the base folder a game was installed into is remembered, keyed by its folder name. */
    private static final String ROOT_PREFIX = "gog_root_dir_";
    /** The place the user picked for a game's next fresh download (StoreInstallLocations), by game id. */
    private static final Map<String, File> PENDING = new ConcurrentHashMap<>();

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("bh_gog_prefs", 0);
    }

    /** Internal storage's GOG folder (where every GOG game went before download locations). */
    public static File internalBase(Context ctx) {
        return new File(ctx.getFilesDir(), "imagefs/gog_games");
    }

    /**
     * The gog_games folder game folder {@code dirName} lives in: the download location it was installed to
     * (SD card, USB drive, a user folder — each keeps a gog_games segment), else internal storage.
     */
    public static File baseFor(Context ctx, String dirName) {
        String root = dirName == null || dirName.isEmpty() ? null : prefs(ctx).getString(ROOT_PREFIX + dirName, null);
        return root != null && !root.isEmpty() ? new File(root) : internalBase(ctx);
    }

    /**
     * Returns the install directory for a game.
     * Path: {base}/{dirName} — {base} from {@link #baseFor} (internal: {filesDir}/imagefs/gog_games).
     */
    public static File getInstallDir(Context ctx, String dirName) {
        return new File(baseFor(ctx, dirName), dirName);
    }

    /** Folders the user brought ("+ Import game library"), by game id: the download must land in exactly that folder. */
    private static final Map<String, File> PENDING_ADOPT = new ConcurrentHashMap<>();

    /** Install game {@code gameId} into the existing {@code folder} (an import): GOG's folder name must be its name. */
    public static void setPendingAdopt(String gameId, File folder) {
        if (gameId != null && folder != null) PENDING_ADOPT.put(gameId, folder);
    }

    public static boolean isPendingAdopt(String gameId) {
        return gameId != null && PENDING_ADOPT.containsKey(gameId);
    }

    /** Remembers the place the user picked for game {@code gameId}'s next fresh download. */
    public static void setPendingBase(String gameId, File base) {
        if (gameId != null && base != null) PENDING.put(gameId, base);
    }

    /**
     * Called by the downloader once the game's folder name is known: a fresh download of a game with a picked
     * place records that place for {@code dirName}. A game whose folder already exists (installed, partial) or that
     * already has a place keeps it.
     */
    public static String claimPendingBase(Context ctx, String gameId, String dirName) {
        File adopt = gameId == null ? null : PENDING_ADOPT.remove(gameId);
        if (adopt != null) {
            // An import: GOG puts the game in <base>/<its own folder name>, so that has to be the user's folder.
            if (dirName == null || !dirName.equals(adopt.getName())) {
                return "Rename the folder \"" + adopt.getName() + "\" to \"" + dirName + "\" (GOG's name for this game), then import again.";
            }
            File parent = adopt.getParentFile();
            if (parent != null) prefs(ctx).edit().putString(ROOT_PREFIX + dirName, parent.getAbsolutePath()).apply();
            return null;
        }
        File base = gameId == null ? null : PENDING.remove(gameId);
        if (base == null || dirName == null || dirName.isEmpty()) return null;
        if (prefs(ctx).contains(ROOT_PREFIX + dirName)) return null;
        if (new File(internalBase(ctx), dirName).exists()) return null;
        prefs(ctx).edit().putString(ROOT_PREFIX + dirName, base.getAbsolutePath()).apply();
        return null;
    }

    /** Forgets where {@code dirName} was installed (after an uninstall), so the next install asks again. */
    public static void forgetBase(Context ctx, String dirName) {
        if (dirName != null && !dirName.isEmpty()) prefs(ctx).edit().remove(ROOT_PREFIX + dirName).apply();
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
