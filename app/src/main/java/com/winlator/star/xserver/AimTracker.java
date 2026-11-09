package com.winlator.star.xserver;

/**
 * Aim mode Auto: notices when a game is doing mouse-look by moving the pointer back itself (Windows
 * SetCursorPos every frame: an X11 WarpPointer, or a short lock with a position hint on Wayland) and
 * reports it, so touch drags turn into camera movement instead of a pointer that jumps under the finger.
 *
 * Starts after {@link #WARPS_TO_START} warps within {@link #WARP_WINDOW_MS}. Ends when the player has
 * moved the pointer {@link #MOTION_TO_STOP} pixels and the game has not warped for {@link #QUIET_MS}
 * (a menu with a normal cursor). Idle time alone never ends it, because a mouse-look game only warps
 * after it sees motion.
 *
 * Mode On (the old Relative Mouse) and Off never report a detection. Thread-safe; the listener runs on
 * the calling thread, outside the lock.
 *
 * Idea from Max (MaxsTechReview)'s DroidDeck gamescope patches 0128 / 0131 (Droid-Deck/DroidDeck #2053,
 * #2280); this is Bannerlator's own code.
 */
public final class AimTracker {
    public static final int MODE_AUTO = 0;
    public static final int MODE_ON = 1;
    public static final int MODE_OFF = 2;

    static final int WARPS_TO_START = 3;
    static final long WARP_WINDOW_MS = 1000L;
    static final long QUIET_MS = 400L;
    static final int MOTION_TO_STOP = 48;

    public interface Listener {
        void onAimChanged(boolean active);
    }

    private int mode = MODE_AUTO;
    private boolean active;
    private final long[] warpTimes = new long[WARPS_TO_START];
    private int warpCount;
    private int warpNext;
    private long lastWarpMs;
    private long motionSinceWarp;
    private volatile Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized int getMode() {
        return mode;
    }

    /** True while Auto has detected mouse-look. */
    public synchronized boolean isActive() {
        return active;
    }

    public void setMode(int mode) {
        boolean changed;
        synchronized (this) {
            this.mode = mode;
            changed = active;
            clear();
        }
        if (changed) notifyChanged(false);
    }

    /** The game moved the pointer itself. */
    public void onWarp(long nowMs) {
        boolean started = false;
        synchronized (this) {
            if (mode != MODE_AUTO) return;
            lastWarpMs = nowMs;
            motionSinceWarp = 0;
            if (active) return;
            warpTimes[warpNext] = nowMs;
            warpNext = (warpNext + 1) % WARPS_TO_START;
            if (warpCount < WARPS_TO_START) warpCount++;
            if (warpCount == WARPS_TO_START) {
                long oldest = warpTimes[warpNext];
                if (nowMs - oldest <= WARP_WINDOW_MS) {
                    active = true;
                    started = true;
                }
            }
        }
        if (started) notifyChanged(true);
    }

    /** The player moved the pointer by dx,dy (any input: touch, mouse, a stick bound to the mouse). */
    public void onMotion(int dx, int dy, long nowMs) {
        boolean ended = false;
        synchronized (this) {
            if (!active) return;
            motionSinceWarp += Math.abs((long) dx) + Math.abs((long) dy);
            if (nowMs - lastWarpMs >= QUIET_MS && motionSinceWarp >= MOTION_TO_STOP) {
                clear();
                ended = true;
            }
        }
        if (ended) notifyChanged(false);
    }

    /** Forget everything (a new session, the game window went away). */
    public void reset() {
        boolean changed;
        synchronized (this) {
            changed = active;
            clear();
        }
        if (changed) notifyChanged(false);
    }

    private void clear() {
        active = false;
        warpCount = 0;
        warpNext = 0;
        motionSinceWarp = 0;
    }

    private void notifyChanged(boolean nowActive) {
        Listener l = listener;
        if (l != null) l.onAimChanged(nowActive);
    }
}
