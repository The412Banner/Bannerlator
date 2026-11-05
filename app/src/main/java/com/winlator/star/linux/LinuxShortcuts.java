package com.winlator.star.linux;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.winlator.star.container.Container;
import com.winlator.star.container.Shortcut;
import com.winlator.star.container.ShortcutOrder;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.ImageUtils;

import java.io.File;

/**
 * The library entries the Linux runtime owns. There is exactly one that is always there once the
 * runtime is installed — Valve's native arm64 Steam client — and it is a plain {@code .desktop}
 * entry like every other shortcut, so the Games tab, Big Picture and pinned shortcuts all pick it
 * up without knowing anything about gamescope.
 *
 * <p>The entry carries {@code runtime=gamescope} itself rather than relying on its container, so a
 * shortcut in an ordinary Wine container still launches into the Linux runtime.
 */
public final class LinuxShortcuts {
    private static final String TAG = "LinuxShortcuts";

    public static final String STEAM_NAME = "Steam (Linux)";
    private static final String STEAM_FILE = "Steam (Linux).desktop";
    /**
     * Exec is never run as written — the Linux runtime decides what to launch from the extras
     * below. It keeps the "wine " prefix every other shortcut has because {@link Shortcut} derives
     * its {@code path} by cutting at that prefix, and an entry without it gets a mangled path.
     */
    private static final String STEAM_EXEC = "wine linux:steam";
    /**
     * The Games tab draws a card from {@code Icon=} resolved under the container's hicolor dirs,
     * not from the cover art - that one only reaches the launch screen. Both are the same image
     * here, written to both places.
     */
    private static final String ICON_NAME = "steam-linux";

    private LinuxShortcuts() {}

    public static File steamShortcutFile(Container container) {
        return new File(container.getDesktopDir(), STEAM_FILE);
    }

    public static boolean hasSteamShortcut(Container container) {
        return steamShortcutFile(container).isFile();
    }

    /**
     * Writes the Steam entry into {@code container}, replacing any earlier copy. Returns false only
     * if the file could not be written.
     */
    /**
     * The tile the Games tab shows for the entry: Steam's own capsule for the client (app 753),
     * the same artwork the store serves for it. Written out of the app's resources the first time
     * the shortcut is created, because a cover art is read from a file path.
     */
    private static File coverArtFile(Context context) {
        return new File(context.getFilesDir(), "app_data/cover_arts/" + STEAM_NAME + ".png");
    }

    private static String writeCoverArt(Context context) {
        File out = coverArtFile(context);
        if (out.isFile()) return out.getPath();
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create " + dir);
            return "";
        }
        return copyTile(context, out) ? out.getPath() : "";
    }

    private static File iconFile(Container container) {
        return new File(container.getIconsDir(64), ICON_NAME + ".png");
    }

    /** Copies the tile into the container's icon dir. Returns false if it could not be written. */
    private static boolean writeIcon(Context context, Container container) {
        File out = iconFile(container);
        if (out.isFile()) return true;
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create " + dir);
            return false;
        }
        return copyTile(context, out);
    }

    private static boolean copyTile(Context context, File out) {
        try (java.io.InputStream in = context.getResources().openRawResource(
                     com.winlator.star.R.drawable.steam_tile);
             java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not write " + out, e);
            return false;
        }
    }

    public static boolean createSteamShortcut(Container container) {
        return createSteamShortcut(container, null);
    }

    /**
     * One entry per game the Linux client installed for itself. Same runtime and mode as the
     * client's own entry, plus the app id, which the session hands to the client as a rungameid
     * URL. Idempotent: an entry that already exists is left exactly as the user has it.
     */
    public static boolean createClientGameShortcut(Container container, String appId, String title) {
        File desktopDir = container.getDesktopDir();
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) return false;
        String safe = title.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (safe.isEmpty()) safe = "App " + appId;
        File file = new File(desktopDir, safe + " (Linux).desktop");
        if (file.isFile()) return false;
        String content = "[Desktop Entry]\n"
                + "Name=" + safe + "\n"
                + "Icon=" + ICON_NAME + "\n"
                + "Exec=" + STEAM_EXEC + "\n"
                + "Type=Application\n"
                + "StartupWMClass=gamescope\n\n"
                + "[Extra Data]\n"
                + ShortcutOrder.addedAtLine(System.currentTimeMillis())
                + Container.EXTRA_RUNTIME + "=" + Container.RUNTIME_GAMESCOPE + "\n"
                + LinuxRuntime.EXTRA_LINUX_MODE + "=" + LinuxRuntime.MODE_STEAM + "\n"
                + "app_id=" + appId + "\n"
                + "displayBackend=wayland\n"
                // Written out rather than inherited: a Linux session sizes gamescope by this, and
                // the client's own interface is the most expensive thing it draws. A container set
                // to 1080p, or a TV mode, would otherwise carry the client with it. The editor's
                // Screen size row changes it as for any entry.
                + "screenSize=" + Container.DEFAULT_SCREEN_SIZE + "\n";
        return FileUtils.writeString(file, content);
    }

    public static boolean createSteamShortcut(Container container, Context context) {
        File desktopDir = container.getDesktopDir();
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) {
            Log.w(TAG, "cannot create " + desktopDir);
            return false;
        }
        boolean haveIcon = context != null && writeIcon(context, container);
        String content = "[Desktop Entry]\n"
                + "Name=" + STEAM_NAME + "\n"
                + (haveIcon ? "Icon=" + ICON_NAME + "\n" : "")
                + "Exec=" + STEAM_EXEC + "\n"
                + "Type=Application\n"
                + "StartupWMClass=gamescope\n"
                + "\n"
                + "[Extra Data]\n"
                + ShortcutOrder.addedAtLine(System.currentTimeMillis())
                + Container.EXTRA_RUNTIME + "=" + Container.RUNTIME_GAMESCOPE + "\n"
                + LinuxRuntime.EXTRA_LINUX_MODE + "=" + LinuxRuntime.MODE_STEAM + "\n"
                // gamescope is a Wayland client; the launch path would pin this anyway.
                + "displayBackend=" + Container.DISPLAY_BACKEND_WAYLAND + "\n"
                // 720p by default, whatever the container is set to (see the game entry above).
                + "screenSize=" + Container.DEFAULT_SCREEN_SIZE + "\n";
        if (context != null) {
            String cover = writeCoverArt(context);
            if (!cover.isEmpty()) content += "customCoverArtPath=" + cover + "\n";
        }
        boolean ok = FileUtils.writeString(steamShortcutFile(container), content);
        if (!ok) Log.w(TAG, "could not write " + steamShortcutFile(container));
        return ok;
    }

    /**
     * True for any entry the Linux runtime owns, whatever it is called. Matching on the runtime
     * extra rather than on {@link #STEAM_NAME} keeps entries written before the name settled -
     * and any the user made by hand - working like the one this class writes today.
     */
    public static boolean isLinuxEntry(Shortcut shortcut) {
        return shortcut != null
                && Container.RUNTIME_GAMESCOPE.equals(shortcut.getExtra(Container.EXTRA_RUNTIME));
    }

    /**
     * Gives a Linux entry the Steam tile if it has none yet. Entries written before the tile
     * existed carry no {@code customCoverArtPath}, and {@link Shortcut}'s name-based fallback is a
     * relative path that never resolves from an app process - so without this they keep the
     * generic placeholder for good. The bitmap is decoded here too: the path alone would only
     * show up on the load after next.
     */
    public static void ensureArt(Context context, Shortcut shortcut) {
        if (context == null || !isLinuxEntry(shortcut)) return;

        String current = shortcut.getCustomCoverArtPath();
        if (current == null || current.isEmpty() || !new File(current).isFile()) {
            String cover = writeCoverArt(context);
            if (!cover.isEmpty()) {
                shortcut.setCustomCoverArtPath(cover);
                shortcut.setCoverArt(ImageUtils.decodeCoverArt(cover));
            }
        }

        if (shortcut.icon != null || shortcut.container == null) return;
        if (!writeIcon(context, shortcut.container)) return;
        addIconLine(shortcut.file);
        shortcut.icon = ImageUtils.decodeCoverArt(iconFile(shortcut.container).getPath());
    }

    /**
     * Adds {@code Icon=} to an entry that has none. It cannot go through {@code putExtra}: that
     * writes the [Extra Data] section, and the card reads the [Desktop Entry] one.
     */
    private static void addIconLine(File desktop) {
        StringBuilder out = new StringBuilder();
        boolean inserted = false;
        for (String line : FileUtils.readLines(desktop)) {
            if (line.trim().startsWith("Icon=")) return;
            out.append(line).append('\n');
            if (!inserted && line.trim().startsWith("Name=")) {
                out.append("Icon=").append(ICON_NAME).append('\n');
                inserted = true;
            }
        }
        if (!inserted) return;
        FileUtils.writeString(desktop, out.toString());
    }

    /** True when any entry in the container's desktop directory is the Linux runtime's. */
    public static boolean hasLinuxEntry(Container container) {
        File[] files = container.getDesktopDir().listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (!f.getName().endsWith(".desktop")) continue;
            String text = FileUtils.readString(f);
            if (text != null && text.contains(Container.EXTRA_RUNTIME + "=" + Container.RUNTIME_GAMESCOPE)) return true;
        }
        return false;
    }

    public static boolean removeSteamShortcut(Container container) {
        File file = steamShortcutFile(container);
        return !file.isFile() || file.delete();
    }

    /**
     * Moves the Steam entry from one container to another exactly as it is - the file IS the
     * user's settings for it (driver, cores, resolution, everything the editor wrote) - and gives
     * the destination the icon the entry names. Used once, when the entry leaves the Wine
     * container it used to need for the Linux runtime's own settings container. Returns false and
     * leaves the source alone if the copy could not be written.
     */
    public static boolean moveSteamShortcut(Container from, Container to, Context context) {
        File src = steamShortcutFile(from);
        if (!src.isFile()) return false;
        File desktopDir = to.getDesktopDir();
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) {
            Log.w(TAG, "cannot create " + desktopDir);
            return false;
        }
        String content = FileUtils.readString(src);
        if (content == null || content.isEmpty()) return false;
        if (context != null) writeIcon(context, to);
        if (!FileUtils.writeString(steamShortcutFile(to), content)) {
            Log.w(TAG, "could not write " + steamShortcutFile(to));
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        src.delete();
        Log.i(TAG, "moved the Steam entry out of container " + from.id + " into the Linux runtime's own");
        return true;
    }
}
