package com.winlator.star.xserver;

import android.util.SparseArray;

import com.winlator.star.core.CursorLocker;
import com.winlator.star.renderer.HostRenderer;
import com.winlator.star.winhandler.WinHandler;
import com.winlator.star.xserver.extensions.BigReqExtension;
import com.winlator.star.xserver.extensions.DRI3Extension;
import com.winlator.star.xserver.extensions.Extension;
import com.winlator.star.xserver.extensions.GLXExtension;
import com.winlator.star.xserver.extensions.XComposite;
import com.winlator.star.xserver.extensions.MITSHMExtension;
import com.winlator.star.xserver.extensions.PresentExtension;
import com.winlator.star.xserver.extensions.RandrExtension;
import com.winlator.star.xserver.extensions.RenderExtension;
import com.winlator.star.xserver.extensions.SyncExtension;
import com.winlator.star.xserver.extensions.GenericEventExtension;
import com.winlator.star.xserver.extensions.XInput2Extension;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final SparseArray<Extension> extensions = new SparseArray<>();
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ColormapManager colormapManager = new ColormapManager();
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private SHMSegmentManager shmSegmentManager;
    private HostRenderer renderer;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;
    private boolean simulateTouchScreen = false;
    private boolean isGrabbed = false;
    private XClient grabbingClient = null;

    public XServer(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
        cursorLocker = new CursorLocker(this);
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        DesktopHelper.attachTo(this);
        setupExtensions();
    }

    /** Relative (delta) mouse delivery: the user's Relative Mouse toggle, or — Wayland mode only —
     *  a program holding a pointer lock in the compositor, which needs deltas the same way. */
    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement || externalRelativeMode;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    private volatile boolean externalRelativeMode;

    /** Aim mode Auto's detector: fed with the game's warps (WarpPointer, Wayland warps) and the player's motion. */
    public final AimTracker aimTracker = new AimTracker();

    /** Touch should act as camera movement: Relative Mouse / Aim On, a Wayland lock, or Auto detected mouse-look. */
    public boolean isAiming() {
        return isRelativeMouseMovement() || aimTracker.isActive();
    }

    /** Wayland mode: a program locked the pointer (zwp_pointer_constraints_v1), so the input path
     *  must deliver deltas while it holds. Leaves the X11 cursor locker alone (no X clients here). */
    public void setExternalRelativeMode(boolean on) {
        externalRelativeMode = on;
    }

    public boolean isSimulateTouchScreen() { return simulateTouchScreen; }

    public void setSimulateTouchScreen(boolean simulateTouchScreen) {
        this.simulateTouchScreen = simulateTouchScreen;
    }

    public HostRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(HostRenderer renderer) {
        this.renderer = renderer;
    }

    public void setRenderingEnabled(boolean enabled) {
        windowManager.setRenderingEnabled(enabled);
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (int i = 0; i < extensions.size(); i++) {
            Extension extension = extensions.valueAt(i);
            if (extension.getName().equals(name)) return extension;
        }
        return null;
    }

    /** Wayland mode: no X client is connected, so the input the app injects (on-screen controls,
     *  mouse, mapped keys) is handed to the Wayland compositor as well. Coordinates are in screen
     *  pixels (the virtual desktop), keys are evdev codes. */
    public interface InputSink {
        void onPointerMove(int x, int y);
        void onPointerButton(Pointer.Button button, boolean pressed);
        void onKey(int evdev, boolean pressed);
    }

    private volatile InputSink inputSink;

    public void setInputSink(InputSink sink) {
        inputSink = sink;
    }

    /** Any backend: called after the app injects pointer motion or a button (touch, mouse, an
     *  on-screen or physical stick bound to the mouse) - never for a client's WarpPointer. Drives
     *  the X11 pointer's idle / controller auto-hide. */
    private volatile Runnable pointerActivityListener;

    public void setPointerActivityListener(Runnable listener) {
        pointerActivityListener = listener;
    }

    /** While set, injected button presses are dropped (a touch that only wakes a hidden pointer must
     *  not click). Releases still go through for a button that is down, so nothing is left stuck. */
    private volatile boolean suppressPointerButtons;

    public void setSuppressPointerButtons(boolean suppress) {
        suppressPointerButtons = suppress;
    }

    /** A dropped press still counts as the player touching the pointer (it wakes it). */
    private void notifyPointerActivity() {
        Runnable activity = pointerActivityListener;
        if (activity != null) activity.run();
    }

    private void sinkPointerMove() {
        InputSink sink = inputSink;
        if (sink != null) sink.onPointerMove(pointer.getX(), pointer.getY());
        Runnable activity = pointerActivityListener;
        if (activity != null) activity.run();
    }

    private void sinkPointerButton(Pointer.Button button, boolean pressed) {
        InputSink sink = inputSink;
        if (sink != null) sink.onPointerButton(button, pressed);
        Runnable activity = pointerActivityListener;
        if (activity != null) activity.run();
    }

    private void sinkKey(XKeycode xKeycode, boolean pressed) {
        InputSink sink = inputSink;
        if (sink != null) sink.onKey((xKeycode.id & 0xff) - 8, pressed);
    }

    public void injectPointerMove(int x, int y) {
        int dx, dy;
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            dx = x - pointer.getX();
            dy = y - pointer.getY();
            pointer.setPosition(x, y);
            emitRawFromAbsolute(x, y);
        }
        aimTracker.onMotion(dx, dy, android.os.SystemClock.uptimeMillis());
        sinkPointerMove();
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(pointer.getX() + dx, pointer.getY() + dy);
            XInput2Extension xi = xInput2;
            if (xi != null) xi.emitRawMotion(dx, dy);
        }
        aimTracker.onMotion(dx, dy, android.os.SystemClock.uptimeMillis());
        sinkPointerMove();
    }

    /** Wayland: the game moved the pointer (a warp). Follow it without counting it as the player's motion. */
    public void syncPointerPosition(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
            rawAnchorValid = false;
        }
    }

    // XInput 2 raw motion for absolute moves (touchscreen mode, a hovering mouse or stylus): the
    // distance from the last absolute position WE injected, not the pointer's, so a game warping
    // the pointer to the centre doesn't turn the next finger move into a huge jump. A new touch
    // lands somewhere else entirely, so the caller marks it (markPointerJump) and that one move
    // only re-anchors.
    private volatile XInput2Extension xInput2;
    private volatile boolean rawFromAbsolute = true;
    private boolean rawAnchorValid = false;
    private int rawAnchorX, rawAnchorY;

    /** The next absolute move is a jump (a finger landing), not motion. */
    public void markPointerJump() {
        try (XLock lock = lock(Lockable.INPUT_DEVICE)) {
            rawAnchorValid = false;
        }
    }

    private void emitRawFromAbsolute(int x, int y) {
        XInput2Extension xi = xInput2;
        if (xi != null && rawFromAbsolute && rawAnchorValid) xi.emitRawMotion(x - rawAnchorX, y - rawAnchorY);
        rawAnchorX = x;
        rawAnchorY = y;
        rawAnchorValid = true;
    }

    private void emitRawButton(Pointer.Button button, boolean pressed) {
        XInput2Extension xi = xInput2;
        if (xi != null) xi.emitRawButton(button.code(), pressed);
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        if (suppressPointerButtons) { notifyPointerActivity(); return; }
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);
            emitRawButton(buttonCode, true);
        }
        sinkPointerButton(buttonCode, true);
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        if (suppressPointerButtons && !pointer.isButtonPressed(buttonCode)) return;
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);
            emitRawButton(buttonCode, false);
        }
        sinkPointerButton(buttonCode, false);
    }

    public void injectPointerButtonPulse(Pointer.Button buttonCode) {
        if (suppressPointerButtons) { notifyPointerActivity(); return; }
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);
            pointer.setButton(buttonCode, false);
            emitRawButton(buttonCode, true);
            emitRawButton(buttonCode, false);
        }
        sinkPointerButton(buttonCode, true);
        sinkPointerButton(buttonCode, false);
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.id, keysym);
        }
        sinkKey(xKeycode, true);
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.id);
        }
        sinkKey(xKeycode, false);
    }

    private void setupExtensions() {
        extensions.put(BigReqExtension.MAJOR_OPCODE, new BigReqExtension());
        extensions.put(MITSHMExtension.MAJOR_OPCODE, new MITSHMExtension());
        extensions.put(DRI3Extension.MAJOR_OPCODE, new DRI3Extension());
        extensions.put(PresentExtension.MAJOR_OPCODE, new PresentExtension());
        extensions.put(SyncExtension.MAJOR_OPCODE, new SyncExtension());
        extensions.put(RandrExtension.MAJOR_OPCODE, new RandrExtension(screenInfo));
    }

    /**
     * Renderers leave out windows that are fully transparent (_NET_WM_WINDOW_OPACITY 0) together with the windows
     * inside them, as a compositing X server does. Set for Windows Steam sessions, where Wine's offscreen surfaces
     * (children of its transparent dummy parent) would otherwise cover Steam's own windows at the screen origin.
     */
    public volatile boolean hideTransparentWindows = false;

    /**
     * Lays overlay pictures (Chromium's side menu / Quick Access in the Windows Steam client) back over a window after
     * Wine copies a fullscreen page frame over all of it; see OverlayCopies. Set for Windows Steam sessions.
     */
    public volatile boolean keepOverlayCopies = false;
    public final OverlayCopies overlayCopies = new OverlayCopies();

    private volatile boolean serverGlx = false;

    /** Opt-in server-side GLX (BANNER_X11_GLX=1): registers the GLX + Composite extensions and makes
     *  DRI3/Present report 1.2, so a DRI-mode Mesa libGL (GLX + Zink via kopper) finds its fbconfigs.
     *  Off by default; when never called the extension list and versions are exactly as before. Must
     *  run before the first client connects (the extension list is read at QueryExtension time). */
    public void enableServerGlx() {
        if (serverGlx) return;
        extensions.put(GLXExtension.MAJOR_OPCODE, new GLXExtension(this));
        extensions.put(XComposite.MAJOR_OPCODE, new XComposite(this));
        serverGlx = true;
    }

    private volatile boolean renderCursors = false;

    /** Full-colour cursors: registers the cursor subset of RENDER (extensions.RenderExtension) so
     *  libXcursor hands over ARGB cursors instead of two-colour core ones. Wine sessions only, and
     *  only once the prefix has "ClientSideWithRender"="N" so winex11 keeps its GDI off RENDER.
     *  Must run before the first client connects, like enableServerGlx(). */
    public void enableRenderCursors() {
        if (renderCursors) return;
        extensions.put(RenderExtension.MAJOR_OPCODE, new RenderExtension(this));
        renderCursors = true;
    }

    public boolean isRenderCursorsEnabled() {
        return renderCursors;
    }

    /** Raw mouse: registers XInput 2 (extensions.XInput2Extension) so Wine's winex11 finds XI2 and
     *  games reading the mouse through raw input get movement. withGenericEvents also advertises
     *  XGE; fromAbsolute also turns absolute moves (touchscreen mode) into raw motion. Wine on X11
     *  only. Must run before the first client connects, like enableRenderCursors(). */
    public void enableXInput2(boolean withGenericEvents, boolean fromAbsolute) {
        if (xInput2 != null) return;
        XInput2Extension xi = new XInput2Extension();
        extensions.put(XInput2Extension.MAJOR_OPCODE, xi);
        if (withGenericEvents) extensions.put(GenericEventExtension.MAJOR_OPCODE, new GenericEventExtension());
        rawFromAbsolute = fromAbsolute;
        xInput2 = xi;
    }

    public XInput2Extension getXInput2() {
        return xInput2;
    }

    public boolean isServerGlxEnabled() {
        return serverGlx;
    }

    public <T extends Extension> T getExtension(int opcode) {
        return (T)extensions.get(opcode);
    }

    public synchronized void setGrabbed(boolean grabbed, XClient client) {
        this.isGrabbed = grabbed;
        this.grabbingClient = client;
    }

    public synchronized boolean isGrabbedBy(XClient client) {
        return isGrabbed && grabbingClient == client;
    }
}
