package com.winlator.star.linux;

import com.winlator.star.container.Shortcut;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The performance switches a Linux session runs with, as set per entry in the Steam (Linux)
 * settings.
 *
 * <p>The client's interface is not short of CPU or GPU: measured on an Adreno 840, its menus ran at
 * ~14 fps with the core override confirmed applied while a game on the same device ran at 89. The
 * cost is the chain that draws the menu — Chromium to ANGLE to Zink to Turnip — so these make that
 * chain cheaper rather than asking for more hardware.
 *
 * <p>Each switch is one extra on the entry, {@code "1"} or {@code "0"}, empty meaning the default
 * below. Three set an environment variable for the session; {@link #EXTRA_STEAMDECK} is turned by
 * the session script into the client's own command line instead. Whatever ends up in effect is
 * written into the session's {@code device.txt}, so a measurement names what produced it.
 *
 * <p>None of the defaults is device-proven — they are the ranked candidates. Arbitrary variables
 * beyond these belong in the entry's own "Env vars" field, which is applied after these.
 */
public final class LinuxTuning {
    /** Zink's GL front end marshals on the calling thread; this moves that to a second one. */
    public static final String EXTRA_GLTHREAD = "linuxGlThread";
    /** The cheaper Zink descriptor path. */
    public static final String EXTRA_LAZY_DESCRIPTORS = "linuxLazyDescriptors";
    /** Skips GL error bookkeeping in the hot path. */
    public static final String EXTRA_NO_GL_ERROR = "linuxNoGlError";
    /** Runs the client as Deck hardware, which is what puts the Quick Access Menu on screen. */
    public static final String EXTRA_STEAMDECK = "linuxSteamDeckMode";

    /** The Steam client's update channel; unset follows Deck mode. */
    public static final String EXTRA_STEAM_CHANNEL = "linuxSteamChannel";

    /**
     * The client update channels offered, the empty first entry meaning "follow Deck mode".
     * Both carry an ARM64 client: publicbeta is what every session ran on before, and steamdeck_publicbeta is the Deck's beta channel.
     * The session script accepts only these two, so a stale saved value can never leave the client without an update to install.
     */
    public static final String[] STEAM_CHANNELS = {"", "publicbeta", "steamdeck_publicbeta"};

    /** Puts the Games tab's own games in the client's library as non-Steam shortcuts; on unless turned off. */
    public static final String EXTRA_APP_GAMES = "linuxAppGamesInSteam";
    /** Links those games' save folders to their containers' own, so both sides share progress; on unless turned off. */
    public static final String EXTRA_SHARE_SAVES = "linuxAppGamesShareSaves";
    /** Folders whose subfolders are games to add as well, joined with {@link LinuxAppGames#FOLDER_SEPARATOR}. */
    public static final String EXTRA_GAMES_FOLDERS = "linuxGamesFolders";

    /** gamescope's --force-windows-fullscreen: a game that shrinks its window comes back filling the screen. On unless turned off; the drawer changes it live. */
    public static final String EXTRA_FILL_SCREEN = "linuxFillScreen";
    /** Quake III engine games told to run windowed at the session's size, the one way they get OpenGL here. On unless turned off. */
    public static final String EXTRA_IDTECH3 = "linuxIdTech3Windowed";
    /** The on-screen Steam and Quick Access Menu buttons in a Steam session. On unless turned off. */
    public static final String EXTRA_STEAM_BUTTONS = "linuxSteamButtons";
    /** Two Back presses within half a second open Steam's Quick Access Menu; one still opens the drawer. On unless turned off. */
    public static final String EXTRA_DOUBLE_BACK_QAM = "linuxDoubleBackQam";
    /** PROTON_USE_XALIA=0 for the games the client starts. Off unless turned on. */
    public static final String EXTRA_NO_XALIA = "linuxNoXalia";
    /** PROOT_NO_SECCOMP=1: proot traces every system call itself instead of filtering them with seccomp. Off unless turned on. */
    public static final String EXTRA_PROOT_NO_SECCOMP = "linuxProotNoSeccomp";
    /** Turnip's sysmem rendering (TU_DEBUG=sysmem): "" automatic, "1" on, "0" off. */
    public static final String EXTRA_TU_SYSMEM = "linuxTurnipSysmem";
    /** The choices for {@link #EXTRA_TU_SYSMEM}, the empty first entry meaning automatic. */
    public static final String[] TU_SYSMEM_CHOICES = {"", "1", "0"};

    /** gamescope's upscaler type; unset leaves gamescope's own default. */
    public static final String EXTRA_SCALER = "linuxScaler";
    /** gamescope's upscaler filter; unset leaves gamescope's own default. */
    public static final String EXTRA_FILTER = "linuxFilter";

    /**
     * The scaler and filter values gamescope accepts, as its --help lists them for this build.
     * The empty first entry means the flag is not passed at all.
     * Nothing outside these lists is ever handed to gamescope: a value it does not know stops the session from starting.
     */
    public static final String[] SCALERS = {"", "auto", "integer", "fit", "fill", "stretch"};
    public static final String[] FILTERS = {"", "linear", "nearest", "pixel", "fsr", "nis", "sgsr"};

    private LinuxTuning() {}

    /**
     * Whether a switch is on when the entry has never been edited.
     *
     * <p>The three environment ones are on so a tester who changes nothing is still testing them.
     * Deck mode is off, and the editor only turns it on through a warning, because on device it
     * breaks games: Steam Input takes the pad ({@code uses xinput : true} in Steam's controller log)
     * and the virtual pad it hands the game never arrives. Its scaling is offered on its own instead
     * ({@link #EXTRA_SCALER}, {@link #EXTRA_FILTER}); a frame cap is the in-game drawer's FPS limit.
     * Only {@code -steamdeck} is passed and never {@code -steamos3}; the session script says why.
     * The two troubleshooting switches ({@link #EXTRA_NO_XALIA}, {@link #EXTRA_PROOT_NO_SECCOMP}) are
     * off: each takes away something Valve or proot does on purpose, for a device where it misbehaves.
     */
    public static boolean defaultOn(String extra) {
        return !EXTRA_STEAMDECK.equals(extra)
                && !EXTRA_NO_XALIA.equals(extra)
                && !EXTRA_PROOT_NO_SECCOMP.equals(extra);
    }

    /** A switch's state for this entry: its own value, or the default when it has none. */
    public static boolean isOn(Shortcut shortcut, String extra) {
        String v = shortcut != null ? shortcut.getExtra(extra, "") : "";
        if (v == null || v.isEmpty()) return defaultOn(extra);
        return "1".equals(v);
    }

    /**
     * The client update channel this entry runs on.
     * An explicit choice wins; otherwise Deck mode takes the Deck's channel and everything else takes publicbeta.
     * Deck mode on publicbeta reinstalled the same client at every start, because the client read "installed version 0" against that manifest and exited 42 to apply it, while steamdeck_publicbeta comes up clean on the second launch. (Seen on device in The412Banner/SteamDeck, 2026-09-23.)
     */
    public static String steamChannel(Shortcut shortcut) {
        String chosen = oneOf(shortcut, EXTRA_STEAM_CHANNEL, STEAM_CHANNELS);
        if (!chosen.isEmpty()) return chosen;
        return isOn(shortcut, EXTRA_STEAMDECK) ? "steamdeck_publicbeta" : "publicbeta";
    }

    /** The saved sysmem choice: "" automatic, "1" on or "0" off. */
    public static String turnipSysmemChoice(Shortcut shortcut) {
        return oneOf(shortcut, EXTRA_TU_SYSMEM, TU_SYSMEM_CHOICES);
    }

    /**
     * Whether the session runs Turnip in sysmem mode.
     * Automatic turns it on for an imported driver from the A710/A720/A722 builds, which is what their
     * authors advise for those GPUs and what nothing else in the list needs. (From The412Banner/DroidDeck.)
     *
     * @param drawDriverLabel the draw driver's display name, or "" for the runtime's own
     */
    public static boolean turnipSysmem(Shortcut shortcut, String drawDriverLabel) {
        String chosen = turnipSysmemChoice(shortcut);
        if (!chosen.isEmpty()) return "1".equals(chosen);
        String name = drawDriverLabel != null ? drawDriverLabel.toLowerCase(java.util.Locale.ROOT) : "";
        return name.contains("710") || name.contains("720") || name.contains("722");
    }

    /**
     * The per-game options the Proton wrappers read at every game start, and the session script's
     * fill-screen watcher, as files in {@code dir}: {@code fill}, {@code idtech3} and {@code xalia},
     * each "1" or "0". Written at session start from the entry's settings, and again by the in-game
     * drawer, which is what makes them live.
     */
    public static void writeLive(java.io.File dir, Shortcut shortcut) {
        writeLive(dir, "fill", isOn(shortcut, EXTRA_FILL_SCREEN));
        writeLive(dir, "idtech3", isOn(shortcut, EXTRA_IDTECH3));
        // The file holds whether xalia may run, so the switch's sense is inverted here.
        writeLive(dir, "xalia", !isOn(shortcut, EXTRA_NO_XALIA));
    }

    /** One live option file, replaced by a rename so a reader never sees it half-written. */
    public static void writeLive(java.io.File dir, String name, boolean on) {
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            java.io.File staged = new java.io.File(dir, name + ".staged");
            java.nio.file.Files.write(staged.toPath(), (on ? "1" : "0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (!staged.renameTo(new java.io.File(dir, name))) {
                //noinspection ResultOfMethodCallIgnored
                staged.delete();
            }
        } catch (java.io.IOException e) {
            android.util.Log.w("LinuxTuning", "could not write the live option " + name, e);
        }
    }

    /** The saved scaler, or "" when it is unset or not one gamescope accepts. */
    public static String scaler(Shortcut shortcut) {
        return oneOf(shortcut, EXTRA_SCALER, SCALERS);
    }

    /** The saved filter, or "" when it is unset or not one gamescope accepts. */
    public static String filter(Shortcut shortcut) {
        return oneOf(shortcut, EXTRA_FILTER, FILTERS);
    }

    private static String oneOf(Shortcut shortcut, String extra, String[] allowed) {
        String v = shortcut != null ? shortcut.getExtra(extra, "") : "";
        if (v == null) return "";
        for (String a : allowed) if (a.equals(v)) return v;
        return "";
    }

    /** What the switches come to: the environment half, in the order they are shown. */
    public static Map<String, String> environment(Shortcut shortcut) {
        Map<String, String> env = new LinkedHashMap<>();
        if (isOn(shortcut, EXTRA_GLTHREAD)) env.put("mesa_glthread", "true");
        if (isOn(shortcut, EXTRA_LAZY_DESCRIPTORS)) env.put("ZINK_DESCRIPTORS", "lazy");
        if (isOn(shortcut, EXTRA_NO_GL_ERROR)) env.put("MESA_NO_ERROR", "1");
        return env;
    }

    /**
     * Adds the switches to a session's guest environment.
     *
     * <p>Deck mode travels as {@code BL_STEAMDECK} because the session script, not the guest,
     * is what turns it into {@code -steamdeck -steamos3} on the client's command line.
     */
    public static void apply(List<String> guest, Shortcut shortcut) {
        for (Map.Entry<String, String> e : environment(shortcut).entrySet()) {
            guest.add(e.getKey() + "=" + e.getValue());
        }
        guest.add("BL_STEAMDECK=" + (isOn(shortcut, EXTRA_STEAMDECK) ? "1" : "0"));
        guest.add("BL_STEAM_CHANNEL=" + steamChannel(shortcut));
        // The session script checks these against the same lists before gamescope sees them.
        String sc = scaler(shortcut);
        if (!sc.isEmpty()) guest.add("BL_SCALER=" + sc);
        String fi = filter(shortcut);
        if (!fi.isEmpty()) guest.add("BL_FILTER=" + fi);
        // What gamescope starts with; the drawer's changes go through the live file instead.
        guest.add("BL_FILL=" + (isOn(shortcut, EXTRA_FILL_SCREEN) ? "1" : "0"));
    }

    /** One line per switch for the session's device report, so a number names its settings. */
    public static String report(Shortcut shortcut) {
        String[][] rows = {
                {"Threaded GL", EXTRA_GLTHREAD},
                {"Lazy descriptors", EXTRA_LAZY_DESCRIPTORS},
                {"Skip GL error checks", EXTRA_NO_GL_ERROR},
                {"Steam Deck mode", EXTRA_STEAMDECK},
                {"App games in Steam", EXTRA_APP_GAMES},
                {"Share saves with app", EXTRA_SHARE_SAVES},
                {"Fill the screen", EXTRA_FILL_SCREEN},
                {"Quake-engine windowed", EXTRA_IDTECH3},
                {"Steam/QAM buttons", EXTRA_STEAM_BUTTONS},
                {"Double Back opens QAM", EXTRA_DOUBLE_BACK_QAM},
                {"Xalia off", EXTRA_NO_XALIA},
                {"proot without seccomp", EXTRA_PROOT_NO_SECCOMP},
        };
        StringBuilder b = new StringBuilder();
        for (String[] row : rows) {
            boolean on = isOn(shortcut, row[1]);
            String raw = shortcut != null ? shortcut.getExtra(row[1], "") : "";
            boolean set = raw != null && !raw.isEmpty();
            b.append(String.format("%-24s", row[0])).append(on ? "on" : "off")
             .append(set ? "" : " (default)").append('\n');
        }
        String chosen = oneOf(shortcut, EXTRA_STEAM_CHANNEL, STEAM_CHANNELS);
        b.append(String.format("%-24s", "Client update channel")).append(steamChannel(shortcut))
         .append(chosen.isEmpty() ? " (follows Deck mode)" : "").append('\n');
        String sc = scaler(shortcut);
        b.append(String.format("%-24s", "Scaling mode")).append(sc.isEmpty() ? "(gamescope default)" : sc).append('\n');
        String fi = filter(shortcut);
        b.append(String.format("%-24s", "Scaling filter")).append(fi.isEmpty() ? "(gamescope default)" : fi).append('\n');
        String sysmem = turnipSysmemChoice(shortcut);
        b.append(String.format("%-24s", "Turnip sysmem")).append(sysmem.isEmpty() ? "automatic" : ("1".equals(sysmem) ? "on" : "off")).append('\n');
        return b.toString();
    }
}
