package com.winlator.star.core;

import com.winlator.star.wayland.WaylandCompositor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Types into a Wayland session. While a program in the guest accepts text input the text goes to it as text (any
 * character, and a paste in one piece); otherwise ASCII is typed on the keys of the session's US layout, the same keys
 * the activity sends for a hardware keyboard. Keys that are not characters always go as key presses.
 */
public final class GuestWaylandTyper implements GuestTyper {
    /** Whether a program in the guest accepts text input right now. */
    public interface TextMode {
        boolean active();
    }

    static final long HOLD_MS = 14;
    static final long GAP_MS = 6;
    static final int MAX_CHARS = 2000;

    /** Linux evdev codes. */
    static final int EVDEV_ESC = 1;
    static final int EVDEV_BACKSPACE = 14;
    static final int EVDEV_TAB = 15;
    static final int EVDEV_ENTER = 28;
    static final int EVDEV_SHIFT_L = 42;
    static final int EVDEV_UP = 103;
    static final int EVDEV_LEFT = 105;
    static final int EVDEV_RIGHT = 106;
    static final int EVDEV_DOWN = 108;

    // Rows of the US layout in evdev order: the characters of each key without Shift, and with it.
    private static final String[] ROW_PLAIN = {"1234567890-=", "qwertyuiop[]", "asdfghjkl;'`", "\\", "zxcvbnm,./", " "};
    private static final String[] ROW_SHIFTED = {"!@#$%^&*()_+", "QWERTYUIOP{}", "ASDFGHJKL:\"~", "|", "ZXCVBNM<>?", " "};
    private static final int[] ROW_FIRST = {2, 16, 30, 43, 44, 57};

    private static final int[] CODE = new int[128];
    private static final boolean[] SHIFT = new boolean[128];

    static {
        for (int row = 0; row < ROW_PLAIN.length; row++) {
            for (int i = 0; i < ROW_PLAIN[row].length(); i++) {
                char plain = ROW_PLAIN[row].charAt(i);
                char shifted = ROW_SHIFTED[row].charAt(i);
                CODE[plain] = ROW_FIRST[row] + i;
                if (shifted != plain) {
                    CODE[shifted] = ROW_FIRST[row] + i;
                    SHIFT[shifted] = true;
                }
            }
        }
    }

    /** One key press: the evdev code and whether Shift is held for it. */
    public static final class Stroke {
        public final int evdev;
        public final boolean shift;

        Stroke(int evdev, boolean shift) {
            this.evdev = evdev;
            this.shift = shift;
        }
    }

    /** The key of the US layout that types an ASCII character; null for any other character. */
    public static Stroke resolve(int codePoint) {
        if (codePoint < 0 || codePoint >= CODE.length || CODE[codePoint] == 0) return null;
        return new Stroke(CODE[codePoint], SHIFT[codePoint]);
    }

    public static int evdevOf(Key key) {
        switch (key) {
            case ENTER: return EVDEV_ENTER;
            case TAB: return EVDEV_TAB;
            case BACKSPACE: return EVDEV_BACKSPACE;
            case ESCAPE: return EVDEV_ESC;
            case LEFT: return EVDEV_LEFT;
            case RIGHT: return EVDEV_RIGHT;
            case UP: return EVDEV_UP;
            default: return EVDEV_DOWN;
        }
    }

    private final TextMode textMode;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "guest-keys");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private final Set<Integer> held = Collections.synchronizedSet(new HashSet<>());

    public GuestWaylandTyper(TextMode textMode) {
        this.textMode = textMode;
    }

    @Override
    public void type(String text) {
        if (text == null || text.isEmpty()) return;
        final int[] points = GuestKeyTyper.normalizeNewlines(text).codePoints().limit(MAX_CHARS).toArray();
        final int gen = generation.get();
        worker.execute(() -> {
            try {
                boolean asText = textMode.active();
                StringBuilder run = new StringBuilder();
                for (int cp : points) {
                    if (gen != generation.get()) return;
                    if (cp == '\n' || cp == '\t') {
                        flush(run);
                        strike(cp == '\n' ? EVDEV_ENTER : EVDEV_TAB, false);
                    } else if (asText) {
                        if (cp >= 0x20 && !(cp >= 0x7F && cp < 0xA0)) run.appendCodePoint(cp);
                    } else {
                        Stroke s = resolve(cp);
                        if (s != null) strike(s.evdev, s.shift);
                    }
                    if (!asText) Thread.sleep(GAP_MS);
                }
                flush(run);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void flush(StringBuilder run) {
        if (run.length() == 0) return;
        WaylandCompositor.textInputCommit(run.toString());
        run.setLength(0);
    }

    @Override
    public void press(Key key) {
        final int gen = generation.get();
        worker.execute(() -> {
            try {
                if (gen != generation.get()) return;
                strike(evdevOf(key), false);
                Thread.sleep(GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void strike(int evdev, boolean shift) throws InterruptedException {
        if (shift) down(EVDEV_SHIFT_L);
        try {
            down(evdev);
            try {
                Thread.sleep(HOLD_MS);
            } finally {
                up(evdev);
            }
        } finally {
            if (shift) up(EVDEV_SHIFT_L);
        }
    }

    private void down(int evdev) {
        held.add(evdev);
        WaylandCompositor.nativeSendKey(evdev, 1);
    }

    private void up(int evdev) {
        if (held.remove(evdev)) WaylandCompositor.nativeSendKey(evdev, 0);
    }

    @Override
    public void releaseAll() {
        generation.incrementAndGet();
        Integer[] keys = held.toArray(new Integer[0]);
        for (Integer key : keys) up(key);
    }

    @Override
    public void close() {
        releaseAll();
        worker.shutdownNow();
    }
}
