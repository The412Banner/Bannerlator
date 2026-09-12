package com.winlator.star.wayland;

import android.view.Surface;

/**
 * Embedded Wayland compositor (experimental parallel display runtime).
 *
 * Brings up a libwayland-server compositor in-process so games launched through
 * Wine's winewayland.drv can present into it (companion to the winewayland Proton
 * build). Committed frames (dmabufs from winewayland's Vulkan WSI) are composited
 * onto the given Surface via the native Vulkan present backend. Not on the default
 * X11 path.
 */
public final class WaylandCompositor {
    static {
        System.loadLibrary("bannerwayland");
    }

    private WaylandCompositor() {}

    private static volatile Runnable firstFrameListener;

    /** Register a callback fired once, when the compositor presents the first client
     *  frame to the output Surface. Used to dismiss the launch overlay in wayland mode
     *  (there is no XServer window-content hook). Runs on the compositor thread — the
     *  listener must marshal to the UI thread itself. */
    public static void setFirstFrameListener(Runnable r) { firstFrameListener = r; }

    /** Invoked from native (banner_on_first_frame) on the first present. */
    @SuppressWarnings("unused")
    static void onFirstFramePresented() {
        Runnable r = firstFrameListener;
        if (r != null) r.run();
    }

    /** The in-game performance HUD's feed in wayland mode (X11 binds the HUD to the window
     *  carrying _MESA_DRV and counts X presents; there is no X server here). */
    public interface GameListener {
        /** A window started presenting GPU frames ({@code window} describes it; {@code gpuName} is the
         *  compositor's GPU), or {@code window == null} when that window closed. Compositor thread. */
        void onGameSurface(String window, String gpuName);
        /** One GPU frame from that window. Compositor thread — keep it cheap. */
        void onGameFrame();
    }

    private static volatile GameListener gameListener;

    public static void setGameListener(GameListener l) { gameListener = l; }

    /** Invoked from native (banner_on_game_surface). */
    @SuppressWarnings("unused")
    static void onGameSurface(String window, String gpuName) {
        GameListener l = gameListener;
        if (l != null) l.onGameSurface(window, gpuName);
    }

    /** Invoked from native (banner_on_game_frame) for every frame of the HUD's window. */
    @SuppressWarnings("unused")
    static void onGameFrame() {
        GameListener l = gameListener;
        if (l != null) l.onGameFrame();
    }

    /** Start the compositor headless (no output window) — bring-up tests only. */
    public static native void nativeStart(String xdgRuntimeDir);

    /** Start the compositor rendering to {@code surface}. XDG_RUNTIME_DIR = an
     *  app-writable dir for the wayland socket (e.g. context.getFilesDir()).
     *  driverPath/libraryName/nativeLibDir select the Turnip driver via adrenotools
     *  (all null -> system libvulkan, which can't do dmabuf import). */
    public static native void nativeStartWithSurface(Surface surface, String xdgRuntimeDir,
                                                     String driverPath, String libraryName,
                                                     String nativeLibDir);

    /** Replace/clear the output window when the SurfaceView is (re)created/destroyed. */
    public static native void nativeSetSurface(Surface surface);

    /** Inject a pointer event into the compositor (from the SurfaceView touch listener).
     *  action: 0=down, 1=move, 2=up. x/y in compositor output space (0..1919, 0..1079). */
    public static native void nativeSendPointer(int action, int x, int y);

    /** Inject a key event. evdev = Linux input keycode (KEY_A=30…); state: 1=down, 0=up. */
    public static native void nativeSendKey(int evdev, int state);

    /** One screen refresh (Choreographer frame callback). The compositor draws the newest state
     *  once per tick, so games run unthrottled and the screen shows their latest frame. */
    public static native void nativeVsync(long frameTimeNanos);

    /** Shortcut launches: don't draw explorer's windows (desktop, taskbar, Start menu), matching the
     *  X11 renderer's unviewable "explorer.exe". Set before the compositor starts. */
    public static native void nativeSetHideShell(boolean hide);

    /** The panel's refresh rate in Hz, advertised on the Wayland output so Wine's display modes
     *  carry the real rate (games that insist on their saved 144 Hz mode find it, as on X11).
     *  Set before the compositor starts. */
    public static native void nativeSetOutputRefreshRate(float hz);

    /** The in-game FPS limiter: frames per second, 0 = unlimited. Paces when replaced buffers go
     *  back to the game, like the X11 IdleNotify pacer, so the game itself slows to the cap. */
    public static native void nativeSetFpsLimit(int fps);

    /** Inject the app's X-server input in scene (virtual desktop) pixels. type 2 = move to a,b;
     *  3 = evdev button a (BTN_LEFT=0x110…) pressed (b=1) or released (b=0); 4 = a wheel steps,
     *  negative = up. */
    public static native void nativeSendSceneInput(int type, int a, int b);
}
