package com.winlator.star.inputcontrols;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import org.libsdl.app.HIDDeviceManager;
import org.libsdl.app.SDL;

import java.util.Arrays;

/**
 * Optional Steam Controller support (Input Controls → Device → Steam Controller, off by default).
 *
 * The Steam Controller (the 2026 model, and the 2015 one over Bluetooth LE) speaks a Valve-specific
 * protocol: out of the box it acts as a keyboard + mouse ("lizard mode") and Android never reports a
 * gamepad, so the normal InputDevice path can't use it. SDL3's HIDAPI Steam drivers speak that
 * protocol: they open the pad over Bluetooth LE (a bonded "Steam Ctrl…" device) or USB (cable, or the
 * 2026 model's wireless puck), keep lizard mode off and parse the reports.
 *
 * SDL runs on a poll thread. Each pad reaches the main thread as an {@link ExternalController} with a
 * synthetic deviceId ({@link #DEVICE_ID_BASE} - SDL instance id), which WinHandler seats in the normal
 * XInput slot machinery like any hot-plugged pad (see WinHandler's "Steam Controller (SDL) pads").
 */
public final class SteamControllerBackend {
    private static final String TAG = "SteamControllerBackend";

    public static final int VALVE_VENDOR_ID = 0x28DE;
    /** Synthetic deviceIds are DEVICE_ID_BASE - SDL instance id: never an Android id (>= 0), never
     *  OSC's -1 and never ExternalController's -1 "not resolved yet". */
    public static final int DEVICE_ID_BASE = -1000;

    private static final long POLL_INTERVAL_MS = 4; // the 2026 pad reports at ~250 Hz
    // Right-trackpad mouse: pixels moved by one full swipe across the pad.
    private static final float TRACKPAD_PIXELS_PER_PAD = 900f;
    private static final float TRIGGER_FULL = 0.98f; // L2/R2 "pressed" like the Android path's == 1.0

    // Array layouts and button bits — keep in sync with steam_controller_bridge.cpp.
    private static final int MAX_PADS = 4;
    private static final int I_ID = 0, I_BUTTONS = 1, I_STRIDE = 2;
    private static final int F_LX = 0, F_LY = 1, F_RX = 2, F_RY = 3, F_LT = 4, F_RT = 5;
    private static final int F_RPAD_DOWN = 6, F_RPAD_X = 7, F_RPAD_Y = 8, F_STRIDE = 12;
    private static final int B_A = 0, B_B = 1, B_X = 2, B_Y = 3, B_LB = 4, B_RB = 5, B_BACK = 6,
            B_START = 7, B_LSTICK = 8, B_RSTICK = 9, B_GUIDE = 10, B_DPAD_UP = 11, B_DPAD_DOWN = 12,
            B_DPAD_LEFT = 13, B_DPAD_RIGHT = 14, B_RPAD_CLICK = 20;

    /** Main-thread callbacks. */
    public interface Listener {
        void onSteamPadConnected(ExternalController pad);

        void onSteamPadDisconnected(ExternalController pad);

        /** pad.state changed. guideDown is the Steam button, which GamepadState has no bit for. */
        void onSteamPadState(ExternalController pad, boolean guideDown);

        /** Right trackpad moved while touched (only when trackpad-as-mouse is on). */
        void onSteamPadMouseMove(int dx, int dy);

        /** Right trackpad clicked / released (only when trackpad-as-mouse is on). */
        void onSteamPadMouseButton(boolean down);
    }

    private static boolean librariesLoaded;
    private static boolean jniReady; // SDL.setupJNI() re-creates SDL's mutexes, so once per process

    private final Activity activity;
    private final Listener listener;
    private final boolean trackpadMouse;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HIDDeviceManager hidManager;
    private Thread pollThread;
    private volatile boolean running;

    // Poll thread -> main thread hand-off: the poll thread publishes its newest frame and posts the
    // apply runnable only when none is queued, so a busy main thread coalesces frames. The main thread
    // never calls into SDL (the poll thread holds the bridge lock during SDL updates).
    private final Object frameLock = new Object();
    private int[] frameInts;
    private float[] frameFloats;
    private String[] frameNames;
    private String[] framePaths;
    private int frameCount;
    private boolean applyQueued;
    private final Runnable applyFrame = this::applyFrame;

    // Main-thread state.
    private final SparseArray<Pad> pads = new SparseArray<>();

    private static final class Pad {
        final ExternalController controller;
        int buttons = -1; // force the first frame through
        final float[] axes = new float[6];
        boolean padDown;
        float padX, padY, accX, accY;
        boolean clickDown;

        Pad(ExternalController controller) {
            this.controller = controller;
        }
    }

    public SteamControllerBackend(Activity activity, boolean trackpadMouse, Listener listener) {
        this.activity = activity;
        this.trackpadMouse = trackpadMouse;
        this.listener = listener;
    }

    /** True when SDL may use Bluetooth: BLUETOOTH_CONNECT on Android 12+, BLUETOOTH below. */
    public static boolean hasBluetoothPermission(Context context) {
        String permission = Build.VERSION.SDK_INT >= 31
                ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Main thread. Returns false (and leaves the normal input path untouched) if SDL can't load. */
    public boolean start() {
        if (running)
            return true;
        if (!loadLibraries())
            return false;
        try {
            if (!jniReady) {
                SDL.setupJNI();
                jniReady = true;
            }
            SDL.initialize();
            SDL.setContext(activity);
            hidManager = HIDDeviceManager.acquire(activity);
        } catch (Throwable t) {
            Log.e(TAG, "SDL Java setup failed; Steam Controller support stays off", t);
            return false;
        }
        // Without the permission SDL would request it itself from the poll thread (SDLActivity.
        // requestPermission). Never prompt in-game: run USB-only instead, the setting asks for it.
        final boolean bluetooth = hasBluetoothPermission(activity);
        running = true;
        pollThread = new Thread(() -> pollLoop(bluetooth), "SteamCtrlPoll");
        pollThread.start();
        Log.i(TAG, "Started (bluetooth " + bluetooth + ", trackpad mouse " + trackpadMouse + ")");
        return true;
    }

    /** Main thread, at session teardown. Silent: no disconnect callbacks, the session is ending. */
    public void stop() {
        if (pollThread == null)
            return;
        running = false;
        try {
            pollThread.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (pollThread.isAlive())
            Log.w(TAG, "Poll thread still shutting SDL down after 1.5 s");
        pollThread = null;
        mainHandler.removeCallbacks(applyFrame);
        pads.clear();
        if (hidManager != null) {
            HIDDeviceManager.release(hidManager);
            hidManager = null;
        }
        SDL.setContext(null);
        Log.i(TAG, "Stopped");
    }

    /** Any thread (the vibration listener). low/high are raw 0..65535 XInput motor values. */
    public void rumble(int deviceId, int low, int high, int durationMs) {
        if (running)
            nativeRumble(DEVICE_ID_BASE - deviceId, low, high, durationMs);
    }

    private static synchronized boolean loadLibraries() {
        if (librariesLoaded)
            return true;
        try {
            System.loadLibrary("SDL3"); // explicitly first: SDL's JNI_OnLoad registers its natives
            System.loadLibrary("steamctrl");
            librariesLoaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "Could not load SDL3 / steamctrl; Steam Controller support stays off", t);
        }
        return librariesLoaded;
    }

    // ---- poll thread ----

    private void pollLoop(boolean bluetooth) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        if (!nativeInit(bluetooth)) {
            Log.w(TAG, "SDL init failed; Steam Controller support stays off this session");
            running = false;
            return;
        }
        int[] ints = new int[MAX_PADS * I_STRIDE];
        float[] floats = new float[MAX_PADS * F_STRIDE];
        String[] names = new String[MAX_PADS];
        String[] paths = new String[MAX_PADS];
        int[] lastInts = new int[ints.length];
        float[] lastFloats = new float[floats.length];
        int lastCount = 0;
        SparseArray<String[]> identities = new SparseArray<>(); // SDL id -> {name, path}
        while (running) {
            int count = nativePoll(ints, floats);
            if (count < 0)
                break;
            // Idle pads produce identical frames; only hand real changes to the main thread.
            if (count != lastCount || !Arrays.equals(ints, lastInts) || !Arrays.equals(floats, lastFloats)) {
                for (int p = 0; p < count; p++) {
                    int id = ints[p * I_STRIDE + I_ID];
                    String[] identity = identities.get(id);
                    if (identity == null) {
                        identity = new String[] { nativeGetName(id), nativeGetPath(id) };
                        identities.put(id, identity);
                    }
                    names[p] = identity[0];
                    paths[p] = identity[1];
                }
                publish(ints, floats, names, paths, count);
                System.arraycopy(ints, 0, lastInts, 0, ints.length);
                System.arraycopy(floats, 0, lastFloats, 0, floats.length);
                lastCount = count;
            }
            SystemClock.sleep(POLL_INTERVAL_MS);
        }
        nativeShutdown();
    }

    private void publish(int[] ints, float[] floats, String[] names, String[] paths, int count) {
        synchronized (frameLock) {
            frameInts = ints.clone();
            frameFloats = floats.clone();
            frameNames = names.clone();
            framePaths = paths.clone();
            frameCount = count;
            if (applyQueued)
                return;
            applyQueued = true;
        }
        mainHandler.post(applyFrame);
    }

    // ---- main thread ----

    private void applyFrame() {
        int[] ints;
        float[] floats;
        String[] names;
        String[] paths;
        int count;
        synchronized (frameLock) {
            ints = frameInts;
            floats = frameFloats;
            names = frameNames;
            paths = framePaths;
            count = frameCount;
            applyQueued = false;
        }
        if (!running || ints == null)
            return;

        for (int i = pads.size() - 1; i >= 0; i--) {
            int id = pads.keyAt(i);
            boolean present = false;
            for (int p = 0; p < count; p++) {
                if (ints[p * I_STRIDE + I_ID] == id) { present = true; break; }
            }
            if (!present) {
                Pad pad = pads.valueAt(i);
                pads.removeAt(i);
                releaseTrackpadButton(pad);
                Log.i(TAG, "Disconnected: " + pad.controller.getName() + " (" + pad.controller.getId() + ")");
                listener.onSteamPadDisconnected(pad.controller);
            }
        }

        for (int p = 0; p < count; p++) {
            int id = ints[p * I_STRIDE + I_ID];
            Pad pad = pads.get(id);
            if (pad == null) {
                pad = new Pad(createController(id, names[p], paths[p]));
                pads.put(id, pad);
                Log.i(TAG, "Connected: " + pad.controller.getName() + " (" + pad.controller.getId()
                        + ", deviceId " + pad.controller.getDeviceId() + ")");
                listener.onSteamPadConnected(pad.controller);
            }
            applyPad(pad, ints[p * I_STRIDE + I_BUTTONS], floats, p * F_STRIDE);
        }
    }

    private static ExternalController createController(int sdlId, String name, String path) {
        ExternalController controller = new ExternalController();
        if (name == null || name.isEmpty())
            name = "Steam Controller";
        controller.setName(name);
        // Stable per controller (BLE path = "SteamController.<MAC>"), so Players-tab pins persist.
        controller.setId("sdl:" + (path != null && !path.isEmpty() ? path : name + "#" + sdlId));
        controller.setDeviceId(DEVICE_ID_BASE - sdlId);
        return controller;
    }

    private void applyPad(Pad pad, int buttons, float[] floats, int base) {
        boolean changed = buttons != pad.buttons;
        for (int a = 0; a < pad.axes.length; a++) {
            if (pad.axes[a] != floats[base + a]) {
                pad.axes[a] = floats[base + a];
                changed = true;
            }
        }
        if (changed) {
            pad.buttons = buttons;
            GamepadState s = pad.controller.state;
            s.thumbLX = deadZone(floats[base + F_LX]);
            s.thumbLY = deadZone(floats[base + F_LY]);
            s.thumbRX = deadZone(floats[base + F_RX]);
            s.thumbRY = deadZone(floats[base + F_RY]);
            s.triggerL = floats[base + F_LT];
            s.triggerR = floats[base + F_RT];
            s.setPressed(ExternalController.IDX_BUTTON_A, bit(buttons, B_A));
            s.setPressed(ExternalController.IDX_BUTTON_B, bit(buttons, B_B));
            s.setPressed(ExternalController.IDX_BUTTON_X, bit(buttons, B_X));
            s.setPressed(ExternalController.IDX_BUTTON_Y, bit(buttons, B_Y));
            s.setPressed(ExternalController.IDX_BUTTON_L1, bit(buttons, B_LB));
            s.setPressed(ExternalController.IDX_BUTTON_R1, bit(buttons, B_RB));
            s.setPressed(ExternalController.IDX_BUTTON_SELECT, bit(buttons, B_BACK));
            s.setPressed(ExternalController.IDX_BUTTON_START, bit(buttons, B_START));
            s.setPressed(ExternalController.IDX_BUTTON_L3, bit(buttons, B_LSTICK));
            s.setPressed(ExternalController.IDX_BUTTON_R3, bit(buttons, B_RSTICK));
            s.setPressed(ExternalController.IDX_BUTTON_L2, s.triggerL >= TRIGGER_FULL);
            s.setPressed(ExternalController.IDX_BUTTON_R2, s.triggerR >= TRIGGER_FULL);
            s.dpad[0] = bit(buttons, B_DPAD_UP);
            s.dpad[1] = bit(buttons, B_DPAD_RIGHT);
            s.dpad[2] = bit(buttons, B_DPAD_DOWN);
            s.dpad[3] = bit(buttons, B_DPAD_LEFT);
            listener.onSteamPadState(pad.controller, bit(buttons, B_GUIDE));
        }
        if (trackpadMouse)
            applyTrackpadMouse(pad, buttons, floats, base);
    }

    private void applyTrackpadMouse(Pad pad, int buttons, float[] floats, int base) {
        boolean down = floats[base + F_RPAD_DOWN] > 0.5f;
        float x = floats[base + F_RPAD_X];
        float y = floats[base + F_RPAD_Y];
        if (down && pad.padDown) {
            pad.accX += (x - pad.padX) * TRACKPAD_PIXELS_PER_PAD;
            pad.accY += (y - pad.padY) * TRACKPAD_PIXELS_PER_PAD;
            int dx = (int) pad.accX;
            int dy = (int) pad.accY;
            if (dx != 0 || dy != 0) {
                pad.accX -= dx;
                pad.accY -= dy;
                listener.onSteamPadMouseMove(dx, dy);
            }
        } else {
            pad.accX = 0;
            pad.accY = 0;
        }
        pad.padDown = down;
        pad.padX = x;
        pad.padY = y;

        boolean click = bit(buttons, B_RPAD_CLICK);
        if (click != pad.clickDown) {
            pad.clickDown = click;
            listener.onSteamPadMouseButton(click);
        }
    }

    private void releaseTrackpadButton(Pad pad) {
        if (pad.clickDown) {
            pad.clickDown = false;
            listener.onSteamPadMouseButton(false);
        }
    }

    private static boolean bit(int buttons, int b) {
        return (buttons & (1 << b)) != 0;
    }

    private static float deadZone(float v) {
        return Math.abs(v) >= ControlElement.STICK_DEAD_ZONE ? v : 0.0f;
    }

    private static native boolean nativeInit(boolean bluetooth);

    private static native int nativePoll(int[] ints, float[] floats);

    private static native String nativeGetName(int id);

    private static native String nativeGetPath(int id);

    private static native void nativeRumble(int id, int low, int high, int durationMs);

    private static native void nativeShutdown();
}
