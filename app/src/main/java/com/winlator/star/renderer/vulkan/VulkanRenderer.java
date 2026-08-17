package com.winlator.star.renderer.vulkan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.widget.Toast;

import com.winlator.star.R;
import com.winlator.star.container.Container;
import com.winlator.star.renderer.GPUImage;
import com.winlator.star.renderer.HostRenderer;
import com.winlator.star.renderer.ViewTransformation;
import com.winlator.star.widget.FrameRating;
import com.winlator.star.widget.XServerView;
import com.winlator.star.xserver.Bitmask;
import com.winlator.star.xserver.Cursor;
import com.winlator.star.xserver.Drawable;
import com.winlator.star.xserver.Pointer;
import com.winlator.star.xserver.Window;
import com.winlator.star.xserver.WindowAttributes;
import com.winlator.star.xserver.WindowManager;
import com.winlator.star.xserver.XLock;
import com.winlator.star.xserver.XServer;

import java.util.ArrayList;

public class VulkanRenderer implements WindowManager.OnWindowModificationListener,
                                       Pointer.OnPointerMotionListener, HostRenderer {

    static { System.loadLibrary("vulkan_renderer"); }

    public final XServerView xServerView;
    private final XServer xServer;
    private long nativeHandle = 0;
    private final Object lock = new Object();

    public final ViewTransformation viewTransformation = new ViewTransformation();
    // Fullscreen aspect-ratio mode (#71). STRETCH fills the surface (distorts); OFF/FIT letterbox.
    private int fullscreenMode = Container.FULLSCREEN_OFF;
    private boolean isStretch() { return fullscreenMode == Container.FULLSCREEN_STRETCH; }
    private float magnifierZoom = 1.0f;
    private boolean screenOffsetYRelativeToCursor = false;
    public int surfaceWidth;
    public int surfaceHeight;
    private String[] unviewableWMClasses = null;
    private boolean cursorVisible = false;
    private boolean nativeMode = false;
    private String driverPath = null;
    private java.util.concurrent.ExecutorService initExecutor = null;
    private volatile boolean initComplete = false;
    private String driverLibraryName = null;
    private String nativeLibDir = null;
    private Drawable rootCursorDrawable;
    private Cursor lastCursor = null;
    private boolean xRenderingPausedForScanout = false;

    private volatile ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private android.view.SurfaceControl scanoutGameSC;
    private android.view.SurfaceControl scanoutCursorSC;
    private android.view.Surface        scanoutGameSurface;
    private android.view.Surface        scanoutCursorSurface;

    public VulkanRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        rootCursorDrawable = createRootCursorDrawable();
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable() {
        try {
            Context context = xServerView.getContext();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap);
        } catch (Exception e) { return null; }
    }

    private native long nativeInit(Surface surface, int screenWidth, int screenHeight, String driverPath, String libraryName, String nativeLibDir);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeDestroy(long handle);
    private native void nativeUpdateWindowContent(long handle, long id, java.nio.ByteBuffer pixels,
        short width, short height, short stride, int x, int y);
    private native void nativeUpdateWindowContentAHB(long handle, long id, long ahbPtr,
        short width, short height, int x, int y);
    private native void nativeSetTransform(long handle, float ox, float oy, float sx, float sy);
    private native void nativeSetPointerPos(long handle, short x, short y);
    private native void nativeSetCursorVisible(long handle, boolean visible);
    private native void nativeUpdateCursorImage(long handle, java.nio.ByteBuffer pixels,
        short width, short height, short hotX, short hotY);
    private native void nativeSetRenderList(long handle, long[] ids, int[] xs, int[] ys, int count);
    private native void nativeRemoveWindow(long handle, long id);

    private native void nativeInitScanout(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, android.view.Surface surface);
    private native void nativeDestroyScanout(long handle);
    private native void nativeScanoutSetBuffer(long handle, long ahbPtr, int x, int y, int w, int h, int fenceFd);
    private native void nativeScanoutSetCursorImage(long handle, java.nio.ByteBuffer pixels, short w, short h, short stride);
    private native void nativeScanoutSetCursorPos(long handle, short x, short y, short hotX, short hotY);
    private native boolean nativeIsScanoutActive(long handle);
    private native boolean nativeIsGameFrameDelivered(long handle);
    private native void nativeSetScanoutWindow(long handle, android.view.Surface game, android.view.Surface cursor);
    private native void nativeScanoutSetDst(long handle, int x, int y, int w, int h);
    private native void nativeSetVerboseLog(long handle, boolean v);
    private native void nativeDumpRendererInfo(long handle);
    private native void nativeSetFilterMode(long handle, int mode);
    private native void nativeSetUpscaler(long handle, int mode);
    private native void nativeSetHqDownscale(long handle, boolean enabled);
    private native void nativeSetCas(long handle, boolean enabled, int sharpness);
    private native void nativeSetHdr(long handle, boolean enabled);
    private native void nativeSetDeband(long handle, boolean enabled, int strength);
    private native void nativeSetUpscaleSharpness(long handle, int sharpness);
    private native void nativeSetFxaa(long handle, boolean enabled);
    private native void nativeSetToon(long handle, boolean enabled);
    private native void nativeSetCrt(long handle, boolean enabled);
    private native void nativeSetNtsc(long handle, boolean enabled);
    private native void nativeSetColorGrade(long handle, float brightness, float contrast, float gamma);
    private native void nativeSetSwapRB(long handle, boolean enabled);
    private native void nativeSetPresentMode(long handle, int mode);
    private native int[] nativeGetSupportedPresentModes(long handle);

    private static volatile boolean gpuImageChecked = false;

    private static long did(Drawable d) {
        return (long) System.identityHashCode(d);
    }

    public void onSurfaceCreated(Surface surface) {
        if (!gpuImageChecked) { GPUImage.checkIsSupported(); gpuImageChecked = true; }
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        initExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        initExecutor.execute(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) {
                    boolean ok = nativeReattachSurface(nativeHandle, surface);
                    if (!ok) {
                        nativeDestroy(nativeHandle);
                        nativeHandle = 0;
                    } else {
                        initComplete = true;
                        xServerView.queueEvent(this::updateScene);
                        return;
                    }
                }
                nativeHandle = nativeInit(surface, xServer.screenInfo.width, xServer.screenInfo.height, driverPath, driverLibraryName, nativeLibDir);
                if (nativeHandle != 0) {
                    nativeSetPresentMode(nativeHandle, pendingPresentMode);
                    nativeSetFilterMode(nativeHandle, pendingFilterMode);
                    nativeSetUpscaler(nativeHandle, pendingUpscaler);
                    nativeSetHqDownscale(nativeHandle, pendingHqDownscale);
                    nativeSetUpscaleSharpness(nativeHandle, pendingUpscaleSharpness);
                    nativeSetCas(nativeHandle, pendingCasEnabled, pendingCasSharpness);
                    nativeSetHdr(nativeHandle, pendingHdrEnabled);
                    nativeSetDeband(nativeHandle, pendingDebandEnabled, pendingDebandStrength);
                    nativeSetFxaa(nativeHandle, pendingFxaaEnabled);
                    nativeSetToon(nativeHandle, pendingToonEnabled);
                    nativeSetCrt(nativeHandle, pendingCrtEnabled);
                    nativeSetNtsc(nativeHandle, pendingNtscEnabled);
                    nativeSetColorGrade(nativeHandle, pendingColorBrightness, pendingColorContrast, pendingColorGamma);
                    nativeSetSwapRB(nativeHandle, pendingSwapRB);
                    updateTransform();
                    nativeSetCursorVisible(nativeHandle, cursorVisible);
                    if (nativeMode) {
                        xServerView.post(() -> {
                            releaseScanoutSurfaces();
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                try {
                                    android.view.SurfaceControl xsc = (android.view.SurfaceControl) xServerView.getSurfaceControl();
                                    scanoutGameSC = new android.view.SurfaceControl.Builder()
                                        .setParent(xsc).setName("winlator_game").setOpaque(true).build();
                                    scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                                    scanoutCursorSC = new android.view.SurfaceControl.Builder()
                                        .setParent(xsc).setName("winlator_cursor").setFormat(1).build();
                                    scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                                    new android.view.SurfaceControl.Transaction()
                                        .setLayer(scanoutGameSC,   1)
                                        .setLayer(scanoutCursorSC, 2)
                                        .setVisibility(scanoutGameSC,   true)
                                        .setVisibility(scanoutCursorSC, true)
                                        .apply();
                                    applyScanoutSwapTransform();
                                    synchronized (lock) {
                                        if (nativeHandle != 0) {
                                            nativeSetScanoutWindow(nativeHandle, scanoutGameSurface, scanoutCursorSurface);
                                            updateTransform();
                                        }
                                    }
                                } catch (Exception e) {
                                    android.util.Log.w("VulkanRenderer", "SC recreate failed on surface restore: " + e);
                                    synchronized (lock) {
                                        if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                                    }
                                }
                            } else {
                                synchronized (lock) { if (nativeHandle != 0) nativeInitScanout(nativeHandle); }
                            }
                        });
                    }
                }
            }
            synchronized (lock) {
                if (nativeHandle != 0) {
                    nativeSetVerboseLog(nativeHandle, true);
                    nativeDumpRendererInfo(nativeHandle);
                }
            }
            initComplete = true;
            xServerView.queueEvent(this::updateScene);
        });
    }

    public void onSurfaceChanged(int width, int height) {
        surfaceWidth = width; surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height, fullscreenMode);
        synchronized (lock) {
            if (nativeHandle != 0) { nativeResize(nativeHandle, width, height); updateTransform(); }
        }
    }

    public void onSurfaceDestroyed() {
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        synchronized (lock) {
            if (nativeHandle != 0) {
                if (nativeMode) {
                    nativeDestroyScanout(nativeHandle);
                    nativeDestroy(nativeHandle);
                    nativeHandle = 0;
                } else {
                    nativeDetachSurface(nativeHandle);
                }
            }
        }
        if (nativeMode) xServerView.post(this::releaseScanoutSurfaces);
    }

    public void forceCleanup() {
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        synchronized (lock) {
            if (nativeHandle != 0) {
                if (nativeMode) nativeDestroyScanout(nativeHandle);
                nativeDestroy(nativeHandle);
                nativeHandle = 0;
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.view.SurfaceControl.Transaction txn = new android.view.SurfaceControl.Transaction();
                if (scanoutGameSC != null) txn.setVisibility(scanoutGameSC, false);
                if (scanoutCursorSC != null) txn.setVisibility(scanoutCursorSC, false);
                txn.apply();
            } catch (Exception ignored) {}
        }
        releaseScanoutSurfaces();
    }

    private void releaseScanoutSurfaces() {
        if (scanoutGameSurface   != null) { scanoutGameSurface.release();   scanoutGameSurface   = null; }
        if (scanoutCursorSurface != null) { scanoutCursorSurface.release(); scanoutCursorSurface = null; }
        if (scanoutGameSC        != null) { scanoutGameSC.release();        scanoutGameSC        = null; }
        if (scanoutCursorSC      != null) { scanoutCursorSC.release();      scanoutCursorSC      = null; }
    }

    private void applyScanoutSwapTransform() {
        if (scanoutGameSC == null || android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            android.view.SurfaceControl.Transaction txn = new android.view.SurfaceControl.Transaction();
            float[] matrix = pendingSwapRB
                ? new float[]{0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f}
                : new float[]{1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
            float[] translation = new float[]{0f, 0f, 0f};
            java.lang.reflect.Method setColorTransform = android.view.SurfaceControl.Transaction.class.getMethod(
                "setColorTransform",
                android.view.SurfaceControl.class,
                float[].class,
                float[].class
            );
            setColorTransform.invoke(txn, scanoutGameSC, matrix, translation);
            txn.apply();
            txn.close();
        } catch (Exception e) {
            android.util.Log.w("VulkanRenderer", "Scanout color transform unavailable: " + e);
        }
    }

    private void updateTransform() {
        if (nativeHandle == 0) return;
        // Refresh the letterbox/crop/integer geometry for the current mode. updateTransform() is the
        // single funnel for every mode/zoom/offset change, so recomputing here keeps FILL/INTEGER live
        // on the in-game toggle without a surface change (STRETCH ignores viewTransformation anyway).
        if (surfaceWidth > 0 && surfaceHeight > 0)
            viewTransformation.update(surfaceWidth, surfaceHeight,
                xServer.screenInfo.width, xServer.screenInfo.height, fullscreenMode);
        float zoom = magnifierZoom;
        if (isStretch()) {
            // Cursor-follow magnifier (parity with GLRenderer.drawFrame). The native compositor
            // normalises this transform by containerWidth/Height (= the GUEST screenInfo dims; see
            // VulkanRendererContext::recordCmdBuf push constants), and the window positions it
            // composites are already in guest pixels. xServer.pointer.getX/getY are ALSO guest
            // coordinates, so the entire offset is computed in GUEST space with no surface-pixel
            // scaling. (The old code pivoted around surfaceWidth/2, i.e. the wrong point whenever
            // guest res != surface res; guest dims are the correct space here.)
            float gw = xServer.screenInfo.width;
            float gh = xServer.screenInfo.height;
            float px = xServer.pointer.getX();
            float py = xServer.pointer.getY();
            // Keep the point under the cursor fixed under zoom, then clamp so the magnified content
            // still covers the screen (no black gutters). At zoom == 1 the clamp range collapses to
            // [0,0] -> identity, independent of the pointer.
            float ox = Math.max(gw * (1f - zoom), Math.min(0f, gw * 0.5f - px * zoom));
            float oy = Math.max(gh * (1f - zoom), Math.min(0f, gh * 0.5f - py * zoom));
            nativeSetTransform(nativeHandle, ox, oy, zoom, zoom);
            viewTransformation.update(surfaceWidth, surfaceHeight,
                xServer.screenInfo.width, xServer.screenInfo.height);
            nativeScanoutSetDst(nativeHandle,
                viewTransformation.viewOffsetX,
                viewTransformation.viewOffsetY,
                viewTransformation.viewWidth,
                viewTransformation.viewHeight);
        } else {
            float py = 0;
            if (screenOffsetYRelativeToCursor) {
                short halfH = (short)(xServer.screenInfo.height / 2);
                py = Math.max(0, Math.min(xServer.pointer.getY() - halfH / 2.0f, halfH));
            }
            float baseOx = viewTransformation.sceneOffsetX;
            float baseOy = viewTransformation.sceneOffsetY - py;
            float baseSx = viewTransformation.sceneScaleX;
            float baseSy = viewTransformation.sceneScaleY;
            // Cursor-follow magnifier (parity with GLRenderer, which follows the cursor in
            // WINDOWED mode too — it has no fullscreen gate). Magnify in GUEST space around the
            // pointer FIRST, THEN apply the existing scene placement:
            //   d'     = zoom*d + magOff                       (magnifier, guest px)
            //   screen = sceneOffset + sceneScale*d'
            //          = (sceneOffset + sceneScale*magOff) + (sceneScale*zoom)*d
            // The native compositor normalises this transform by containerWidth = the GUEST
            // screenInfo (see VulkanRendererContext::recordCmdBuf push constants: ndc =
            // (ox + d*sx)/cw), so sceneOffset and magOff are GUEST pixels and sceneScale is
            // dimensionless. The old code pivoted around the SURFACE centre (cx = surfaceWidth/2)
            // and multiplied baseOx by zoom — both wrong: the compositor pivots in guest space and
            // the scene offset must stay unscaled. At zoom == 1 magOff clamps to 0, so this reduces
            // EXACTLY to (baseOx, baseOy, baseSx, baseSy) = today's non-magnified windowed
            // transform (no regression). The screenOffsetYRelativeToCursor Y shift is folded into
            // baseOy above and composes correctly through the scene scale.
            float gw = xServer.screenInfo.width;
            float gh = xServer.screenInfo.height;
            float px = xServer.pointer.getX();
            float magOffX = Math.max(gw * (1f - zoom), Math.min(0f, gw * 0.5f - px * zoom));
            float magOffY = Math.max(gh * (1f - zoom), Math.min(0f, gh * 0.5f - xServer.pointer.getY() * zoom));
            nativeSetTransform(nativeHandle,
                baseOx + baseSx * magOffX,
                baseOy + baseSy * magOffY,
                baseSx * zoom,
                baseSy * zoom);
            nativeScanoutSetDst(nativeHandle,
                viewTransformation.viewOffsetX,
                viewTransformation.viewOffsetY,
                viewTransformation.viewWidth,
                viewTransformation.viewHeight);
        }
    }

    public void updateScene() {
        ArrayList<RenderableWindow> newList = new ArrayList<>();
        try (XLock xl = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            collectWindows(newList, xServer.windowManager.rootWindow,
                xServer.windowManager.rootWindow.getX(),
                xServer.windowManager.rootWindow.getY());
        }
        synchronized (lock) {
            renderableWindows = newList;
            pushRenderList(newList);
        }
    }

    private void collectWindows(ArrayList<RenderableWindow> list, Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;
            if (unviewableWMClasses != null) {
                String wc = window.getClassName();
                for (String cls : unviewableWMClasses) {
                    if (wc.contains(cls)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false; break;
                    }
                }
            }
            if (viewable) list.add(new RenderableWindow(window.getContent(), x, y));
        }
        for (Window child : window.getChildren())
            collectWindows(list, child, child.getX() + x, child.getY() + y);
    }

    private void pushRenderList(ArrayList<RenderableWindow> list) {
        if (nativeHandle == 0) return;
        int screenW = xServer.screenInfo.width, screenH = xServer.screenInfo.height;

        int start = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            RenderableWindow rw = list.get(i);
            if (rw.content != null && rw.content.width >= screenW && rw.content.height >= screenH) {
                start = i; break;
            }
        }

        if (nativeMode) {
            ArrayList<RenderableWindow> ns = new ArrayList<>();
            for (int i = start; i < list.size(); i++) {
                RenderableWindow rw = list.get(i);
                if (rw.content != null && !rw.content.isDirectScanout()) ns.add(rw);
            }
            int n = ns.size();
            long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                ids[i] = did(ns.get(i).content);
                xs[i] = ns.get(i).rootX; ys[i] = ns.get(i).rootY;
            }
            nativeSetRenderList(nativeHandle, ids, xs, ys, n);
            return;
        }
        if (isStretch()) {
            int n = list.size() - start;
            if (n <= 0) { nativeSetRenderList(nativeHandle, new long[0], new int[0], new int[0], 0); return; }
            long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                RenderableWindow rw = list.get(start + i);
                ids[i] = did(rw.content);
                xs[i] = rw.rootX; ys[i] = rw.rootY;
            }
            nativeSetRenderList(nativeHandle, ids, xs, ys, n);
            return;
        }

        int n = list.size() - start;
        long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            RenderableWindow rw = list.get(start + i);
            ids[i] = did(rw.content);
            xs[i] = rw.rootX; ys[i] = rw.rootY;
        }
        nativeSetRenderList(nativeHandle, ids, xs, ys, n);
    }

    private void sendCursorToNative(Cursor cursor) {
        if (nativeHandle == 0) return;
        Drawable cd; short hotX = 0, hotY = 0;
        boolean effVis = cursorVisible;
        if (cursor != null) {
            if (!cursor.isVisible()) effVis = false;
            cd = cursor.cursorImage; hotX = (short)cursor.hotSpotX; hotY = (short)cursor.hotSpotY;
        } else { cd = rootCursorDrawable; }
        nativeSetCursorVisible(nativeHandle, effVis);
        if (effVis && cd != null && cd.getBuffer() != null) {
            synchronized (cd.renderLock) {
                nativeUpdateCursorImage(nativeHandle, cd.getBuffer(), cd.width, cd.height, hotX, hotY);
                if (nativeMode) {
                    java.nio.ByteBuffer buf = cd.getBuffer();
                    short stride = (short)(buf.capacity() / (cd.height * 4));
                    nativeScanoutSetCursorImage(nativeHandle, buf, cd.width, cd.height, stride);
                }
            }
        }
    }

    public void onUpdateWindowContentDirect(Window window, Drawable pixmap, short xOff, short yOff) {
    if (hudFrameTick != null) hudFrameTick.accept(window.id);
    if (!nativeMode && window.id == fpsWindowId) {
        if (classicHudRef != null && classicHudRef.isAttachedToWindow()) classicHudRef.post(classicHudRef);
    }
    synchronized (lock) {
            if (nativeHandle == 0 || pixmap == null) return;
            int rx = window.getRootX() + xOff, ry = window.getRootY() + yOff;
            synchronized (pixmap.renderLock) {
                if (pixmap.getTexture() instanceof GPUImage) {
                    GPUImage g = (GPUImage) pixmap.getTexture();
                    long ahbPtr = g.getHardwareBufferPtr();
                    if (ahbPtr != 0) {
                        if (nativeMode && pixmap.isDirectScanout() && nativeIsScanoutActive(nativeHandle)) {
                            int fence = g.unlock();
                            nativeScanoutSetBuffer(nativeHandle, ahbPtr,
                                rx, ry, pixmap.width, pixmap.height, fence);
                            g.lock();
                            pixmap.refreshDataFromTexture();
                        } else {
                            long contentId = did(window.getContent());
                            nativeUpdateWindowContentAHB(nativeHandle, contentId, ahbPtr,
                                pixmap.width, pixmap.height, rx, ry);
                        }
                        return;
                    }
                    java.nio.ByteBuffer vd = g.getVirtualData();
                    if (vd != null) {
                        short s = g.getStride() > 0 ? g.getStride() : pixmap.width;
                        nativeUpdateWindowContent(nativeHandle, did(window.getContent()), vd,
                            pixmap.width, pixmap.height, s, rx, ry);
                        return;
                    }
                }
                java.nio.ByteBuffer buf = pixmap.getBuffer();
                if (buf == null) return;
                short stride = (short)(buf.capacity() / (pixmap.height * 4));
                nativeUpdateWindowContent(nativeHandle, did(window.getContent()), buf,
                    pixmap.width, pixmap.height, stride, rx, ry);
            }
        }
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            Drawable drawable = window.getContent();
            if (drawable == null || !window.attributes.isMapped()) return;
            if (unviewableWMClasses != null) {
                String wc = window.getClassName();
                for (String cls : unviewableWMClasses) if (wc.contains(cls)) return;
            }
            int rx = window.getRootX(), ry = window.getRootY();
            synchronized (drawable.renderLock) {
                if (drawable.getTexture() instanceof GPUImage) {
                    GPUImage g = (GPUImage) drawable.getTexture();
                    long ahbPtr = g.getHardwareBufferPtr();
                    if (ahbPtr != 0) {
                        boolean scanoutNow = nativeMode && nativeIsScanoutActive(nativeHandle);
                        if (nativeMode && drawable.isDirectScanout() && scanoutNow) {
                            boolean wasDelivered = nativeIsGameFrameDelivered(nativeHandle);

                            int fence = g.unlock();
                            nativeScanoutSetBuffer(nativeHandle, ahbPtr,
                                rx, ry, drawable.width, drawable.height, fence);
                            g.lock();
                            drawable.refreshDataFromTexture();
                            boolean delivered = nativeIsGameFrameDelivered(nativeHandle);

                            if (!xRenderingPausedForScanout && !wasDelivered && delivered) {
                                xServer.setRenderingEnabled(false);
                                xRenderingPausedForScanout = true;
                            }
                            // FLIP/scanout present bypasses onUpdateWindowContentDirect, so tick the
                            // active (horizontal) HUD here too or it freezes in Native Rendering mode.
                            if (hudFrameTick != null) hudFrameTick.accept(window.id);
                            if (classicHudRef != null) {
                                classicHudRef.post(classicHudRef);
                            }
                            if (classicHudRef != null && classicHudRef.isAttachedToWindow()) classicHudRef.post(classicHudRef);
                        } else if (!scanoutNow) {
                            nativeUpdateWindowContentAHB(nativeHandle, did(drawable), ahbPtr,
                                drawable.width, drawable.height, rx, ry);
                        }
                        return;
                    }
                    java.nio.ByteBuffer vd = g.getVirtualData();
                    if (vd != null) {
                        short s = g.getStride() > 0 ? g.getStride() : drawable.width;
                        nativeUpdateWindowContent(nativeHandle, did(drawable), vd,
                            drawable.width, drawable.height, s, rx, ry);
                        return;
                    }
                }
                java.nio.ByteBuffer buf = drawable.getBuffer();
                if (buf == null) return;
                short stride = (short)(buf.capacity() / (drawable.height * 4));
                nativeUpdateWindowContent(nativeHandle, did(drawable), buf,
                    drawable.width, drawable.height, stride, rx, ry);
            }
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, x, y);
            Window pw = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
            if (cursor != lastCursor) { lastCursor = cursor; sendCursorToNative(cursor); }
            if (nativeMode) {
                short hotX = 0, hotY = 0;
                if (cursor != null) { hotX = (short)cursor.hotSpotX; hotY = (short)cursor.hotSpotY; }
                nativeScanoutSetCursorPos(nativeHandle, x, y, hotX, hotY);
            }
            // Vulkan is event-driven (no continuous render loop), so the magnifier transform must
            // be recomputed on pointer motion for the zoomed region to track the cursor; GLRenderer
            // gets this for free from its per-frame render loop. Re-transform while magnified too.
            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) updateTransform();
        }
    }

    @Override
    public void onDestroyWindow(Window window) {
        final long id = did(window.getContent());
        xServerView.queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, id);
            }
            updateScene();
        });
    }

    @Override public void onMapWindow(Window window) { xServerView.queueEvent(this::updateScene); }

    @Override
    public void onUnmapWindow(Window window) {
        final long id = did(window.getContent());
        xServerView.queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, id);
            }
            updateScene();
        });
    }

    @Override public void onChangeWindowZOrder(Window window) { xServerView.queueEvent(this::updateScene); }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        xServerView.queueEvent(this::updateScene);
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            synchronized (lock) {
                Window pw = xServer.inputDeviceManager.getPointWindow();
                if (pw == window) { lastCursor = window.attributes.getCursor(); sendCursorToNative(lastCursor); }
            }
        }
    }

    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        synchronized (lock) {
            if (nativeHandle != 0) { nativeSetCursorVisible(nativeHandle, visible); if (visible) sendCursorToNative(lastCursor); }
        }
    }

    public boolean isCursorVisible() { return cursorVisible; }

    public void setNativeMode(boolean mode) {
        if (this.nativeMode == mode) return;
        this.nativeMode = mode;
        xRenderingPausedForScanout = false;
        if (mode) {
            xServer.setRenderingEnabled(true);
            xServerView.post(() -> {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    try {
                        android.view.SurfaceControl xsc = (android.view.SurfaceControl) xServerView.getSurfaceControl();
                        scanoutGameSC = new android.view.SurfaceControl.Builder()
                            .setParent(xsc).setName("winlator_game").setOpaque(true).build();
                        scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                        scanoutCursorSC = new android.view.SurfaceControl.Builder()
                            .setParent(xsc).setName("winlator_cursor").setFormat(1).build();
                        scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                        android.view.SurfaceControl.Transaction scTxn =
                            new android.view.SurfaceControl.Transaction()
                            .setLayer(scanoutGameSC,   1)
                            .setLayer(scanoutCursorSC, 2)
                            .setVisibility(scanoutGameSC,   true)
                            .setVisibility(scanoutCursorSC, true);

                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            float targetFps = fpsLimit > 0 ? (float)fpsLimit
                                : xServerView.getDisplay() != null
                                    ? xServerView.getDisplay().getRefreshRate() : 60f;
                            scTxn.setFrameRate(scanoutGameSC, targetFps,
                                android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                        }
                        scTxn.apply();
                        applyScanoutSwapTransform();
                        synchronized (lock) {
                            if (nativeHandle != 0) {
                                nativeSetScanoutWindow(nativeHandle,
                                    scanoutGameSurface, scanoutCursorSurface);
                                updateTransform();
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.w("VulkanRenderer", "Sibling SC failed, using child SC: " + e);
                        synchronized (lock) {
                            if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                        }
                    }
                } else {
                    synchronized (lock) { if (nativeHandle != 0) nativeInitScanout(nativeHandle); }
                }
            });
        } else {
            synchronized (lock) {
                if (nativeHandle != 0) nativeDestroyScanout(nativeHandle);
            }

            xServerView.post(() -> {
                xServer.setRenderingEnabled(true);
                releaseScanoutSurfaces();
            });
        }
        if (classicHudRef != null && classicHudRef.isAttachedToWindow()) classicHudRef.post(classicHudRef);
        xServerView.queueEvent(this::updateScene);
        final String msg = mode ? "Native Rendering+ Enabled" : "Native Rendering+ Disabled";
        // Use the app's styled toast (white text on a custom background); a raw Toast.makeText here
        // inherits the dark app theme and renders as a black box with invisible text.
        xServerView.post(() -> com.winlator.star.core.AppUtils.showToast(xServerView.getContext(), msg));
    }

    public boolean isNativeMode() { return nativeMode; }

    // Set the desired native (direct-scanout) mode BEFORE the surface is created. onSurfaceCreated
    // sets up the scanout SurfaceControls when nativeMode is already true, so this is the correct
    // entry point for applying the container's "native" toggle at launch (setNativeMode() is for
    // toggling at runtime, with the full SurfaceControl rebuild + toast).
    public void setInitialNativeMode(boolean v) { this.nativeMode = v; }

    public void setDriverInfo(String driverPath, String libraryName, String nativeLibDir) {
        this.driverPath = driverPath;
        this.driverLibraryName = libraryName;
        this.nativeLibDir = nativeLibDir;
        android.util.Log.d("Winlator_Renderer",
            "setDriverInfo: path=" + driverPath + " lib=" + libraryName);
    }

    public void setVerboseLog(boolean v) {
        synchronized (lock) { if (nativeHandle != 0) nativeSetVerboseLog(nativeHandle, v); }
    }

    public void dumpRendererInfo() {
        synchronized (lock) { if (nativeHandle != 0) nativeDumpRendererInfo(nativeHandle); }
    }

    public void setFilterMode(int mode) {
        pendingFilterMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetFilterMode(nativeHandle, mode); }
    }

    // Scaling mode (spatial upscaler). Enum mirrors the native side:
    //   0=none 1=linear 2=nearest 3=sgsr 4=fsr(fill) 5=fsr_fit(letterbox)
    // Modes 1/2 also set the base sampler filter natively; modes 3-5 run the
    // SGSR/FSR shader passes (only when the game renders below display res).
    public void setUpscaler(int mode) {
        pendingUpscaler = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetUpscaler(nativeHandle, mode); }
    }

    // High-quality Lanczos downscale for supersampling (game render res above the
    // display). Independent of the scaling mode; engages only when render>display.
    // The app multiplies the X11 screen resolution by the SS factor at launch, so
    // this is effectively a pre-launch (container/shortcut) setting.
    public void setHqDownscale(boolean enabled) {
        pendingHqDownscale = enabled;
        synchronized (lock) { if (nativeHandle != 0) nativeSetHqDownscale(nativeHandle, enabled); }
    }

    // Composable AMD CAS sharpen (sharpness 0..100). Layers on any scaling mode and
    // runs even at native res. Drawer-only / session-live, like the scaling mode.
    public void setCas(boolean enabled, int sharpness) {
        pendingCasEnabled = enabled;
        pendingCasSharpness = sharpness;
        synchronized (lock) { if (nativeHandle != 0) nativeSetCas(nativeHandle, enabled, sharpness); }
    }

    // Composable fake-HDR (binary on/off). Drawer-only / session-live.
    public void setHdr(boolean enabled) {
        pendingHdrEnabled = enabled;
        synchronized (lock) { if (nativeHandle != 0) nativeSetHdr(nativeHandle, enabled); }
    }

    // Terminal debanding / dither. strength 0..200 -> strength/100 LSBs (100 = 1 LSB,
    // the default). Drawer-only / session-live, mirrors setCas/setHdr.
    public void setDeband(boolean enabled, int strength) {
        pendingDebandEnabled = enabled;
        pendingDebandStrength = strength;
        synchronized (lock) { if (nativeHandle != 0) nativeSetDeband(nativeHandle, enabled, strength); }
    }

    // Real upscaler sharpness (RCAS stops + SGSR EdgeSharpness) from a 0..100 slider.
    // Default 75 == the previously hard-coded 0.25 RCAS stops. Drawer-only / session-live.
    public void setUpscaleSharpness(int sharpness) {
        pendingUpscaleSharpness = sharpness;
        synchronized (lock) { if (nativeHandle != 0) nativeSetUpscaleSharpness(nativeHandle, sharpness); }
    }

    // Phase 2 composable screen effects (GL EffectComposer parity). Color grade takes
    // the raw slider values (brightness/contrast -100..100, gamma 0.5..3.0); neutral
    // (0,0,1) is a no-op. FXAA/Toon/CRT/NTSC are binary. Drawer-only / session-live.
    public void setScreenEffects(float brightness, float contrast, float gamma,
                                 boolean fxaa, boolean toon, boolean crt, boolean ntsc) {
        pendingColorBrightness = brightness;
        pendingColorContrast   = contrast;
        pendingColorGamma      = gamma;
        pendingFxaaEnabled = fxaa;
        pendingToonEnabled = toon;
        pendingCrtEnabled  = crt;
        pendingNtscEnabled = ntsc;
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeSetColorGrade(nativeHandle, brightness, contrast, gamma);
                nativeSetFxaa(nativeHandle, fxaa);
                nativeSetToon(nativeHandle, toon);
                nativeSetCrt(nativeHandle, crt);
                nativeSetNtsc(nativeHandle, ntsc);
            }
        }
    }

    public void setSwapRB(boolean enabled) {
        pendingSwapRB = enabled;
        synchronized (lock) { if (nativeHandle != 0) nativeSetSwapRB(nativeHandle, enabled); }
    }

    public void setVkPresentMode(int mode) {
        pendingPresentMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetPresentMode(nativeHandle, mode); }
    }

    public int[] getSupportedPresentModes() {
        synchronized (lock) {
            if (nativeHandle != 0) return nativeGetSupportedPresentModes(nativeHandle);
        }
        return new int[0];
    }
    public void setNativeColorFormat(int format) {}
    public int getNativeColorFormat() { return 0; }

    private FrameRating classicHudRef = null;
    private int fpsWindowId = -1;

    public void setFpsWindowId(int id) { fpsWindowId = id; }

    // The FPS/perf HUD is normally ticked from copyArea's onDrawListener, but the Vulkan AHB
    // present path bypasses copyArea, so the HUD froze (no values) on the Vulkan renderer. The
    // activity sets this to tick the HUD per present; it passes window.id so the activity can gate
    // on its FPS window. Decoupled from classicHudRef so it works for both HUD variants.
    private java.util.function.IntConsumer hudFrameTick = null;
    public void setHudFrameTick(java.util.function.IntConsumer c) { hudFrameTick = c; }

    public void setFrameRating(Object fr) {
        if (fr instanceof FrameRating) classicHudRef = (FrameRating) fr;
    }

    public boolean isFullscreen() { return fullscreenMode != Container.FULLSCREEN_OFF; }
    public int getFullscreenMode() { return fullscreenMode; }
    public void setFullscreenMode(int mode) {
        fullscreenMode = mode;
        synchronized (lock) { updateTransform(); }
        xServerView.queueEvent(this::updateScene);
    }
    public void toggleFullscreen() { setFullscreenMode(Container.nextFullscreenMode(fullscreenMode)); }
    public void setScreenOffsetYRelativeToCursor(boolean b) { screenOffsetYRelativeToCursor = b; synchronized (lock) { updateTransform(); } }
    public boolean isScreenOffsetYRelativeToCursor() { return screenOffsetYRelativeToCursor; }
    public void setMagnifierZoom(float zoom) {
        magnifierZoom = zoom;
        synchronized (lock) { updateTransform(); }
    }
    public float getMagnifierZoom() { return magnifierZoom; }
    @Override
    public void setUnviewableWMClasses(String classes) {
        this.unviewableWMClasses = classes != null ? classes.split(";") : null;
    }
    private int fpsLimit = 0;
    private int     pendingPresentMode    = 2;
    private int     pendingFilterMode     = 0;
    private int     pendingUpscaler       = 0;
    private boolean pendingHqDownscale    = false;
    private boolean pendingCasEnabled     = false;
    private int     pendingCasSharpness   = 60;
    private boolean pendingHdrEnabled     = false;
    private boolean pendingDebandEnabled  = false;
    private int     pendingDebandStrength = 100;    // slider 100 -> 1.0 LSB dither (default)
    private int     pendingUpscaleSharpness = 75;   // 75 -> 0.25 RCAS stops (legacy default)
    private boolean pendingFxaaEnabled    = false;
    private boolean pendingToonEnabled    = false;
    private boolean pendingCrtEnabled     = false;
    private boolean pendingNtscEnabled    = false;
    private float   pendingColorBrightness = 0.0f;  // -100..100 slider; 0 = neutral
    private float   pendingColorContrast   = 0.0f;  // -100..100 slider; 0 = neutral
    private float   pendingColorGamma      = 1.0f;  // 0.5..3.0 slider; 1.0 = neutral
    private boolean pendingSwapRB         = false;
    public int getFpsLimit() { return fpsLimit; }
    public void setFpsLimit(int limit) {
        this.fpsLimit = limit;
        if (android.os.Build.VERSION.SDK_INT >= 30 && scanoutGameSC != null) {
            float targetFps = limit > 0 ? (float)limit
                : xServerView.getDisplay() != null
                    ? xServerView.getDisplay().getRefreshRate() : 60f;
            new android.view.SurfaceControl.Transaction()
                .setFrameRate(scanoutGameSC, targetFps,
                    android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                .apply();
        }
    }
    public int getSurfaceWidth() { return surfaceWidth; }
    public int getSurfaceHeight() { return surfaceHeight; }
    public void requestRender() {}

    // HostRenderer
    @Override public XServerView getXServerView() { return xServerView; }
    @Override public void setRenderingEnabled(boolean enabled) { xServer.setRenderingEnabled(enabled); }

    private static class RenderableWindow {
        public final Drawable content; public int rootX, rootY;
        public RenderableWindow(Drawable c, int x, int y) { content=c; rootX=x; rootY=y; }
    }
}
