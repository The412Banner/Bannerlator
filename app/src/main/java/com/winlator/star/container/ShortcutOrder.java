package com.winlator.star.container;

/**
 * The "recent" order of the library: a game added just now counts as the latest played, until it is
 * really played. The time a shortcut was added lives in its own Extra Data key ({@link #EXTRA_ADDED_AT},
 * epoch millis) rather than in {@code lastPlayed}: everything that asks "was this game ever played?"
 * (Continue playing, the Played filter, the Last played label) reads {@code lastPlayed}, and a game
 * nobody started must not pass for played. Only the recent sort reads both, through {@link #recentKey}.
 *
 * <p>Pure JVM code (no Android classes) so the creation paths in Java and Kotlin share it and it can
 * be unit-tested.
 */
public final class ShortcutOrder {
    /** Extra Data key holding the wall-clock millis a shortcut was created (absent on older shortcuts). */
    public static final String EXTRA_ADDED_AT = "addedAt";

    private static final String EXTRA_HEADER = "[Extra Data]";

    private ShortcutOrder() {}

    /** The {@code addedAt=<now>} line, newline included, for a creation path that builds its text by hand. */
    public static String addedAtLine(long now) {
        return EXTRA_ADDED_AT + "=" + now + "\n";
    }

    /** A stored millis value; 0 for a missing, blank, malformed or negative one. */
    public static long parse(String value) {
        if (value == null) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** What the recent sort compares: the later of the last launch and the time the game was added. */
    public static long recentKey(long lastPlayed, long addedAt) {
        return Math.max(Math.max(lastPlayed, 0L), Math.max(addedAt, 0L));
    }

    /**
     * [desktopText] with its {@code addedAt} set to [now]: an existing line is replaced, the line goes
     * straight under {@code [Extra Data]}, and a file without that section gets one. For a .desktop
     * that was written by someone else (an imported file) rather than built by this app.
     */
    public static String withAddedAt(String desktopText, long now) {
        String entry = EXTRA_ADDED_AT + "=" + now;
        String[] lines = desktopText.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inserted = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith(EXTRA_ADDED_AT + "=")) continue;
            // The split leaves one empty piece after the final newline; it is re-added below, not here.
            boolean last = i == lines.length - 1;
            if (last && line.isEmpty()) break;
            out.append(line).append('\n');
            if (!inserted && trimmed.equals(EXTRA_HEADER)) {
                out.append(entry).append('\n');
                inserted = true;
            }
        }
        if (!inserted) out.append('\n').append(EXTRA_HEADER).append('\n').append(entry).append('\n');
        return out.toString();
    }
}
