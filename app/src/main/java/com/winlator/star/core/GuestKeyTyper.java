package com.winlator.star.core;

import com.winlator.star.xserver.XKeycode;
import com.winlator.star.xserver.XServer;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Types into an X11 session by pressing keys of the server's keyboard, the way a hardware keyboard would. A character is
 * found on the key that carries it in the server's own key map (with Shift when it is the shifted symbol), so the guest's
 * key map is never rewritten for ASCII. Characters the map has no key for (accented letters, other scripts) go through a
 * spare key whose symbol is set to that character, which is what the soft keyboard's text already does for them.
 */
public final class GuestKeyTyper implements GuestTyper {
    /** How long a key stays down, and the pause after it: short enough to type fast, long enough for a game that polls its keys. */
    static final long HOLD_MS = 14;
    static final long GAP_MS = 6;

    /** A long clipboard would otherwise keep the worker typing for minutes. */
    static final int MAX_CHARS = 2000;

    /** Symbols above Latin-1 are sent as Unicode keysyms. */
    private static final int UNICODE_KEYSYM = 0x01000000;

    /** The key given the symbol of a character the map has none for: it has no symbol of its own in the server's map. */
    static final XKeycode SPARE_KEY = XKeycode.KEY_SCROLL_LOCK;

    private static final XKeycode[] BY_ID = createIdTable();

    /** One key press: the key, whether Shift is held for it, and the symbol to hand the server with it. */
    public static final class Stroke {
        public final XKeycode keycode;
        public final boolean shift;
        public final int keysym;

        Stroke(XKeycode keycode, boolean shift, int keysym) {
            this.keycode = keycode;
            this.shift = shift;
            this.keysym = keysym;
        }
    }

    private final XServer xServer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "guest-keys");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private final Set<XKeycode> held = Collections.synchronizedSet(EnumSet.noneOf(XKeycode.class));

    public GuestKeyTyper(XServer xServer) {
        this.xServer = xServer;
    }

    private static XKeycode[] createIdTable() {
        XKeycode[] table = new XKeycode[256];
        for (XKeycode k : XKeycode.values()) {
            int id = k.id & 0xFF;
            // KEY_MAX repeats the last key's id.
            if (table[id] == null) table[id] = k;
        }
        return table;
    }

    /**
     * The key press that types a character, looked up in the server's key map ({@code keysyms}: two symbols per key from key
     * code 8, unshifted then shifted). Null for a character that cannot be typed (a control character).
     */
    public static Stroke resolve(int[] keysyms, int codePoint) {
        if (codePoint < 0x20 || (codePoint >= 0x7F && codePoint < 0xA0) || (codePoint >= 0xD800 && codePoint <= 0xDFFF)
                || codePoint > 0x10FFFF) return null;
        int keysym = codePoint < 0x100 ? codePoint : UNICODE_KEYSYM | codePoint;
        Stroke shifted = null;
        for (int i = 0; i + 1 < keysyms.length; i += 2) {
            int code = i / 2 + 8;
            XKeycode key = code < BY_ID.length ? BY_ID[code] : null;
            if (key == null) continue;
            // The unshifted symbol wins, so a character on two keys' two slots (a space) is typed without Shift.
            if (keysyms[i] == keysym) return new Stroke(key, false, keysym);
            if (shifted == null && keysyms[i + 1] == keysym) shifted = new Stroke(key, true, keysym);
        }
        return shifted != null ? shifted : new Stroke(SPARE_KEY, false, keysym);
    }

    /** The key a non-character key is pressed on. */
    public static XKeycode keycodeOf(Key key) {
        switch (key) {
            case ENTER: return XKeycode.KEY_ENTER;
            case TAB: return XKeycode.KEY_TAB;
            case BACKSPACE: return XKeycode.KEY_BKSP;
            case ESCAPE: return XKeycode.KEY_ESC;
            case LEFT: return XKeycode.KEY_LEFT;
            case RIGHT: return XKeycode.KEY_RIGHT;
            case UP: return XKeycode.KEY_UP;
            default: return XKeycode.KEY_DOWN;
        }
    }

    /** Line ends of every kind become the one newline the guest gets as Enter. */
    public static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    @Override
    public void type(String text) {
        if (text == null || text.isEmpty()) return;
        final int[] points = normalizeNewlines(text).codePoints().limit(MAX_CHARS).toArray();
        final int gen = generation.get();
        worker.execute(() -> {
            try {
                for (int cp : points) {
                    if (gen != generation.get()) return;
                    if (cp == '\n') strike(XKeycode.KEY_ENTER, 0, false);
                    else if (cp == '\t') strike(XKeycode.KEY_TAB, 0, false);
                    else {
                        Stroke s = resolve(xServer.keyboard.keysyms, cp);
                        if (s != null) strike(s.keycode, s.keysym, s.shift);
                    }
                    Thread.sleep(GAP_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void press(Key key) {
        final int gen = generation.get();
        worker.execute(() -> {
            try {
                if (gen != generation.get()) return;
                strike(keycodeOf(key), 0, false);
                Thread.sleep(GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Presses a key (with Shift around it when asked), holds it for {@link #HOLD_MS}, and always lets go again. */
    private void strike(XKeycode key, int keysym, boolean shift) throws InterruptedException {
        if (shift) down(XKeycode.KEY_SHIFT_L, 0);
        try {
            down(key, keysym);
            try {
                Thread.sleep(HOLD_MS);
            } finally {
                up(key);
            }
        } finally {
            if (shift) up(XKeycode.KEY_SHIFT_L);
        }
    }

    private void down(XKeycode key, int keysym) {
        held.add(key);
        xServer.injectKeyPress(key, keysym);
    }

    private void up(XKeycode key) {
        if (held.remove(key)) xServer.injectKeyRelease(key);
    }

    @Override
    public void releaseAll() {
        generation.incrementAndGet();
        XKeycode[] keys = held.toArray(new XKeycode[0]);
        for (XKeycode key : keys) up(key);
    }

    @Override
    public void close() {
        releaseAll();
        worker.shutdownNow();
    }
}
