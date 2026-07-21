package com.winlator.star.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.view.Surface;

import com.winlator.star.R;
import com.winlator.star.widget.XServerView;
import com.winlator.star.xserver.Bitmask;
import com.winlator.star.xserver.Cursor;
import com.winlator.star.xserver.CursorManager;
import com.winlator.star.xserver.Drawable;
import com.winlator.star.xserver.Pointer;
import com.winlator.star.xserver.Window;
import com.winlator.star.xserver.WindowAttributes;
import com.winlator.star.xserver.WindowManager;
import com.winlator.star.xserver.XServer;

import java.nio.ByteBuffer;

/**
 * DisplayX host renderer.
 *
 * <p>Ported from Pipetto-crypto/winlator (branch {@code winlator_bionic}, MIT) — his
 * {@code renderer/displayx.cpp} plus the {@code XServerView} JNI surface that drives it.
 * Every X11 window becomes its own {@link android.view.SurfaceControl} layer backed by an
 * AHardwareBuffer; SurfaceFlinger composites them. There is no GL or Vulkan pass, and no
 * render loop on this side — a native thread driven by AChoreographer owns presentation.</p>
 *
 * <p>This is a <em>faithful</em> port, kept deliberately close to upstream so that what we
 * measure is his design rather than a hybrid of his and ours. In particular it keeps his
 * fence-less {@code ASurfaceTransaction_setBuffer(..., -1)} and his CPU memcpy of window
 * content into the layer buffer. Our own {@link ASurfaceRenderer} does both better; if
 * DisplayX wins a measurement anyway, hardening comes after.</p>
 *
 * <p>Unlike {@link GLRenderer} this class is a thin shell: it forwards X-server events to
 * native and holds no scene state. Note it is the only renderer that needs window
 * <em>create</em> and <em>reparent</em> events, because native mirrors the whole window
 * tree; those callbacks are defaulted no-ops on the listener for everyone else.</p>
 */
public class DisplayXRenderer implements HostRenderer,
        WindowManager.OnWindowModificationListener,
        CursorManager.OnCursorModificationListener,
        Pointer.OnPointerMotionListener {

    private static final String TAG = "DisplayXRenderer";

    static { System.loadLibrary("displayx"); }

    /** ASurfaceControl / ASurfaceTransaction require API 29+, same gate as ASR. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    public final XServerView xServerView;
    private final XServer xServer;

    // Root cursor pixels are decoded here (same source as GLRenderer.createRootCursorDrawable)
    // and handed to native as a direct ByteBuffer. Upstream decodes assets/cursor.png natively
    // with stb_image; we already have the bitmap as a resource, so we skip that dependency.
    // Held for the lifetime of the renderer — native keeps a raw pointer into this buffer.
    private final Drawable rootCursorDrawable;

    private int surfaceWidth;
    private int surfaceHeight;
    private boolean surfaceInitialized = false;

    private boolean cursorVisible = true;
    private boolean fullscreen = false;
    private int fullscreenMode = 0;
    private boolean screenOffsetYRelativeToCursor = false;
    private float magnifierZoom = 1.0f;
    private int fpsLimit = 0;
    private int fpsWindowId = -1;
    private Object hudRef = null;

    public DisplayXRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;

        this.rootCursorDrawable = createRootCursorDrawable(xServerView.getContext());

        nativeInit(xServer, rootCursorDrawable.getData(),
                rootCursorDrawable.width, rootCursorDrawable.height);

        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.cursorManager.addOnCursorModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private static Drawable createRootCursorDrawable(Context context) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    // ---- surface lifecycle -------------------------------------------------

    public void onSurfaceCreated(Surface surface) {
        nativeCreateSurface(surface);
    }

    public void onSurfaceChanged(int width, int height) {
        this.surfaceWidth = width;
        this.surfaceHeight = height;
        this.surfaceInitialized = true;
        nativeChangeSurface(width, height);
    }

    public void onSurfaceDestroyed() {
        this.surfaceInitialized = false;
        nativeDestroySurface();
    }

    public void onPause() { nativePause(); }

    public void onResume() { nativeResume(); }

    public void onDestroy() { nativeStop(); }

    // ---- direct content (DRI3 AHardwareBuffer pixmaps) ---------------------

    public void addDirectContent(int windowId, Drawable drawable, GPUImage gpuImage) {
        nativeAddDirectContent(windowId, drawable, gpuImage);
    }

    public void updateDirectContent(int windowId, int drawableId) {
        nativeUpdateDirectContent(windowId, drawableId);
    }

    public void removeDirectContent(int windowId, int drawableId) {
        nativeRemoveDirectContent(windowId, drawableId);
    }

    // ---- X server events ---------------------------------------------------

    @Override
    public void onCreateWindow(Window window, Window parent) {
        nativeCreateWindow(window, parent != null ? parent.id : -1);
    }

    @Override
    public void onReparentWindow(Window window, Window newParent) {
        nativeReparentWindow(window.id, newParent.id);
    }

    @Override
    public void onMapWindow(Window window) {
        nativeMapWindow(window.id);
    }

    @Override
    public void onUnmapWindow(Window window) {
        nativeUnmapWindow(window.id);
    }

    @Override
    public void onDestroyWindow(Window window) {
        nativeDestroyWindow(window.id);
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        nativeUpdateWindowContent(window.id, window.getContent().getData());
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        nativeUpdateWindowGeometry(window.id, window.getWidth(), window.getHeight(),
                window.getX(), window.getY(), resized);
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            Cursor cursor = window.attributes.getCursor();
            if (cursor != null) {
                nativeBindCursor(window.id, cursor.id, cursor.isVisible(), cursor.cursorImage.getData());
            }
        }
    }

    @Override
    public void onCreateCursor(Cursor cursor) {
        nativeCreateCursor(cursor);
    }

    @Override
    public void onFreeCursor(Cursor cursor) {
        nativeFreeCursor(cursor.id);
    }

    @Override
    public void onPointerMove(short x, short y) {
        nativePointerMove(x, y);
    }

    // ---- HostRenderer ------------------------------------------------------

    @Override public XServerView getXServerView() { return xServerView; }

    @Override
    public void setRenderingEnabled(boolean enabled) {
        xServer.windowManager.setRenderingEnabled(enabled);
    }

    /** DisplayX presents from its own native thread; there is nothing to request. */
    @Override public void requestRender() { }

    @Override public void forceCleanup() { onSurfaceDestroyed(); }

    @Override
    public void setCursorVisible(boolean visible) {
        this.cursorVisible = visible;
        nativeSetCursorVisible(visible);
    }

    @Override public boolean isCursorVisible() { return cursorVisible; }

    /** Not implemented upstream for DisplayX — window filtering stays a GL/Vulkan feature. */
    @Override public void setUnviewableWMClasses(String wmClasses) { }

    /** SurfaceFlinger picks the scaling filter; there is no sampler for us to set. */
    @Override public void setFilterMode(int mode) { }

    /** Magnifier is a compositor-pass feature; DisplayX has no compositor pass. */
    @Override public void setMagnifierZoom(float zoom) { this.magnifierZoom = zoom; }

    @Override public float getMagnifierZoom() { return magnifierZoom; }

    @Override
    public void toggleFullscreen() {
        this.fullscreen = !this.fullscreen;
        nativeToggleFullscreen();
    }

    @Override public boolean isFullscreen() { return fullscreen; }

    @Override
    public void setFullscreenMode(int mode) {
        // Upstream DisplayX only has the binary stretch toggle (its toggleFullscreen swaps the
        // src/dst rects of the root layer), so anything non-OFF maps onto that single mode.
        this.fullscreenMode = mode;
        boolean wanted = mode != 0;
        if (wanted != fullscreen) toggleFullscreen();
    }

    @Override public int getFullscreenMode() { return fullscreenMode; }

    @Override
    public void setScreenOffsetYRelativeToCursor(boolean b) { this.screenOffsetYRelativeToCursor = b; }

    @Override
    public boolean isScreenOffsetYRelativeToCursor() { return screenOffsetYRelativeToCursor; }

    @Override public void setFpsWindowId(int id) { this.fpsWindowId = id; }

    @Override public void setFrameRating(Object fr) { this.hudRef = fr; }

    @Override public int getFpsLimit() { return fpsLimit; }

    @Override public void setFpsLimit(int limit) { this.fpsLimit = limit; }

    @Override public int getSurfaceWidth() { return surfaceWidth; }

    @Override public int getSurfaceHeight() { return surfaceHeight; }

    // ---- native ------------------------------------------------------------

    private native void nativeInit(XServer xServer, ByteBuffer rootCursorData, int cursorWidth, int cursorHeight);
    private native void nativeCreateSurface(Surface surface);
    private native void nativeChangeSurface(int width, int height);
    private native void nativeDestroySurface();
    private native void nativePause();
    private native void nativeResume();
    private native void nativeStop();
    private native void nativeCreateWindow(Window window, int parentId);
    private native void nativeDestroyWindow(int id);
    private native void nativeMapWindow(int id);
    private native void nativeUnmapWindow(int id);
    private native void nativeReparentWindow(int id, int parentId);
    private native void nativeUpdateWindowGeometry(int id, int width, int height, int x, int y, boolean resized);
    private native void nativeUpdateWindowContent(int id, ByteBuffer data);
    private native void nativeCreateCursor(Cursor cursor);
    private native void nativeFreeCursor(int id);
    private native void nativeBindCursor(int windowId, int cursorId, boolean visible, ByteBuffer data);
    private native void nativePointerMove(int x, int y);
    private native void nativeSetCursorVisible(boolean visible);
    private native void nativeToggleFullscreen();
    private native void nativeAddDirectContent(int windowId, Drawable drawable, GPUImage gpuImage);
    private native void nativeUpdateDirectContent(int windowId, int drawableId);
    private native void nativeRemoveDirectContent(int windowId, int drawableId);
}
