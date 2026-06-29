package com.winlator.star.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import com.winlator.star.R;
import com.winlator.star.XrActivity;
import com.winlator.star.math.Mathf;
import com.winlator.star.math.XForm;
import com.winlator.star.renderer.material.CursorMaterial;
import com.winlator.star.renderer.material.ScanoutBlitMaterial;
import com.winlator.star.renderer.material.ScanoutBlitRot90Material;
import com.winlator.star.renderer.material.ShaderMaterial;
import com.winlator.star.renderer.material.WindowMaterial;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLRenderer implements GLSurfaceView.Renderer, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener, HostRenderer {

    public final XServerView xServerView;
    private final XServer xServer;
    public final VertexAttribute quadVertices = new VertexAttribute("position", 2);
    private final float[] tmpXForm1 = XForm.getInstance();
    private final float[] tmpXForm2 = XForm.getInstance();
    private final CursorMaterial cursorMaterial = new CursorMaterial();
    private final WindowMaterial windowMaterial = new WindowMaterial();
    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final Drawable rootCursorDrawable;
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    
    private boolean fullscreen = false;
    private boolean toggleFullscreen = false;
    public boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private boolean screenOffsetYRelativeToCursor = false;
    private String[] unviewableWMClasses = null;

    @Override
    public void setUnviewableWMClasses(String classes) {
        this.unviewableWMClasses = classes != null ? classes.split(";") : null;
    }
    private float magnifierZoom = 1.0f;
    private boolean magnifierEnabled = true;
    public int surfaceWidth;
    public int surfaceHeight;

    // ---- GL Native Rendering (direct scanout via SurfaceControl / SurfaceFlinger) ----
    // P3: lifecycle only. Mirrors VulkanRenderer's nativeMode model, adapted to GL's pull loop.
    // When nativeMode is on we build the sibling game + cursor SurfaceControls (z-order > 0 so they
    // composite ABOVE the GLSurfaceView's EGL content) and route the cursor through the cursor SC.
    // The per-frame game AHB push (PresentExtension FLIP branch + first-delivery X-rendering pause)
    // is P4 — until then the game is still composited by GL; the opaque game SC is bufferless so it
    // does not occlude the GL frame.
    private DirectScanout scanout;
    private boolean nativeMode = false;
    private boolean xRenderingPausedForScanout = false;
    private boolean swapRB = false;
    private Cursor lastScanoutCursor = null;

    // Per-present HUD driver (P4). The GL-native FLIP path bypasses onDrawFrame AND copyArea, so the
    // perf HUD is never ticked in native mode unless we drive it from presentScanout. Decoupled from
    // any specific HUD widget (works for FrameRating classic + horizontal + GameHub PerfHud).
    // Mirrors VulkanRenderer.setHudFrameTick / ASurfaceRenderer.setHudFrameTick.
    private java.util.function.IntConsumer hudFrameTick = null;
    public void setHudFrameTick(java.util.function.IntConsumer c) { hudFrameTick = c; }

    // ---- EXPERIMENT A: COMPOSER_OVERLAY host-AHB blit (diagnostic, branch-only) ----
    // Probe whether the Adreno DPU rejects the game scanout layer because the guest buffer lacks the
    // COMPOSER_OVERLAY usage flag / is non-UBWC (renderer-agnostic) rather than because of its ROT_90.
    // Instead of presenting the guest GPUImage AHB directly, we blit it (one passthrough quad, SAME
    // size + orientation + scale — NO pre-rotation, NO geometry change) into this host-allocated AHB
    // that carries GPU_FRAMEBUFFER | GPU_SAMPLED | COMPOSER_OVERLAY, then present THAT. All GL work runs
    // on the GL thread (mirrors captureScreenshot's queueEvent+FBO pattern). If any step fails the path
    // falls back to the original direct guest-buffer present, so native rendering never breaks.
    // These GL objects are owned by the GL thread.
    private GPUImage hostScanoutImage;            // host overlay-eligible AHB (GL render target)
    private int hostScanoutFbo = 0;
    private int hostScanoutW = 0, hostScanoutH = 0;
    private boolean hostScanoutFailed = false;    // sticky: once setup fails, stop trying (just fall back)
    private int hostScanoutTimeouts = 0;          // GL-thread round-trip timeouts -> disable after a few
    private final ScanoutBlitMaterial scanoutBlitMaterial = new ScanoutBlitMaterial();
    // EXPERIMENT B: rotate the guest frame 90° into a PANEL-RES host AHB (transform 0, src==dst, no
    // SC-scale), with the activity locked to the panel's native portrait orientation so nothing forces
    // a per-layer transform back on. Neutralizes rotation + scale together, isolating UBWC.
    private final ScanoutBlitRot90Material scanoutBlitRot90Material = new ScanoutBlitRot90Material();
    private final float[] scanoutBlitXForm = XForm.getInstance();

    // Selectable sampler filter for window/content drawables only (the cursor stays LINEAR so the
    // pointer never goes blocky). Mirrors the Vulkan filter-int convention used by setUpscaler:
    //   0/default & 1 -> GL_LINEAR (bilinear), 2 -> GL_NEAREST (point). Applied per-frame in
    //   renderDrawable, which keeps the GPUImage zero-copy texture untouched (sampler state only,
    //   no CPU upload) and supports live mid-game switching.
    private volatile int windowTexFilter = GLES20.GL_LINEAR;
    
    private final EffectComposer effectComposer;

    /**
     * Interface used for window screenshot results.
     */
    @FunctionalInterface
    public interface ScreenshotCallback {
        void onScreenshotTaken(Bitmap bitmap);
    }

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        this.effectComposer = new EffectComposer(this);
        rootCursorDrawable = createRootCursorDrawable();

        quadVertices.put(new float[]{
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        });

        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    /**
     * Initiates a window screenshot capture safely on the GL thread.
     */
    public void captureScreenshot(Window window, int width, int height, ScreenshotCallback callback) {
        if (window == null || callback == null) return;
        
        xServerView.queueEvent(() -> {
            Drawable content = null;
            try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                content = findDrawable(window);
            }

            if (content != null) {
                Bitmap bitmap = takeScreenshotInternal(content, width, height);
                callback.onScreenshotTaken(bitmap);
            } else {
                callback.onScreenshotTaken(null);
            }
        });
    }

    private Drawable findDrawable(Window window) {
        if (window == null) return null;
        if (window.getContent() != null) return window.getContent();
        
        for (Window child : window.getChildren()) {
            if (child.attributes.isMapped()) {
                Drawable childContent = findDrawable(child);
                if (childContent != null) return childContent;
            }
        }
        return null;
    }

    private Bitmap takeScreenshotInternal(Drawable content, int width, int height) {
        width = Math.max(1, width);
        height = Math.max(1, height);

        synchronized (content.renderLock) {
            Texture texture = content.getTexture();
            texture.updateFromDrawable(content);

            int[] framebuffers = new int[1];
            GLES20.glGenFramebuffers(1, framebuffers, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0]);

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[0], 0);

            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glDeleteFramebuffers(1, framebuffers, 0);
                GLES20.glDeleteTextures(1, textures, 0);
                return null;
            }

            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            windowMaterial.use();
            quadVertices.bind(windowMaterial.programId);
            GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"), width, height);

            float[] screenshotXForm = XForm.getInstance();
            // In Winlator, set(matrix, x, y, w, h) builds the transformation to fill the viewport
            XForm.set(screenshotXForm, 0, 0, width, height);
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            GLES20.glUniform1i(windowMaterial.getUniformLocation("texture"), 0);
            GLES20.glUniform1fv(windowMaterial.getUniformLocation("xform"), screenshotXForm.length, screenshotXForm, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());

            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
            
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            // Flip horizontally because GL is bottom-up
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.preScale(1.0f, -1.0f);
            Bitmap flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
            bitmap.recycle();

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDeleteFramebuffers(1, framebuffers, 0);
            GLES20.glDeleteTextures(1, textures, 0);
            viewportNeedsUpdate = true;

            return flippedBitmap;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GPUImage.checkIsSupported();
        GLES20.glFrontFace(GLES20.GL_CCW);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        // Rebuild the scanout SurfaceControls on a (re)created GL surface (rotation, app-switch) when
        // native mode is active — mirrors VulkanRenderer.onSurfaceCreated's nativeMode restore block.
        if (nativeMode) enableScanout();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (XrActivity.isEnabled(null)) {
            XrActivity activity = XrActivity.getInstance();
            activity.init();
            width = activity.getWidth();
            height = activity.getHeight();
            GLES20.glViewport(0, 0, width, height);
            magnifierEnabled = false;
        }

        surfaceWidth = width;
        surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        viewportNeedsUpdate = true;
        if (nativeMode && scanout != null) {
            scanout.setSurfaceSize(surfaceWidth, surfaceHeight);
            updateScanoutDst();
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
            if (nativeMode) updateScanoutDst();
        }

        if (effectComposer != null && effectComposer.isActive() && surfaceWidth > 0 && surfaceHeight > 0) {
            try {
                effectComposer.render();
            } catch (Exception e) {
                drawFrame();
            }
        } else {
            drawFrame();
        }
    }

    public void drawFrame() {
        boolean xrFrame = false;
        boolean xrImmersive = false;
        if (XrActivity.isEnabled(null)) {
            xrImmersive = XrActivity.getImmersive();
            xrFrame = XrActivity.getInstance().beginFrame(xrImmersive, XrActivity.getSBS());
        }

        if (viewportNeedsUpdate && magnifierEnabled) {
            if (fullscreen) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            }
            else {
                GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
            viewportNeedsUpdate = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (magnifierEnabled) {
            float pointerX = 0;
            float pointerY = 0;
            float magnifierZoom = !screenOffsetYRelativeToCursor ? this.magnifierZoom : 1.0f;

            if (magnifierZoom != 1.0f) {
                pointerX = Mathf.clamp(xServer.pointer.getX() * magnifierZoom - xServer.screenInfo.width * 0.5f, 0, xServer.screenInfo.width * Math.abs(1.0f - magnifierZoom));
            }

            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) {
                float scaleY = magnifierZoom != 1.0f ? Math.abs(1.0f - magnifierZoom) : 0.5f;
                float offsetY = xServer.screenInfo.height * (screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
                pointerY = Mathf.clamp(xServer.pointer.getY() * magnifierZoom - offsetY, 0, xServer.screenInfo.height * scaleY);
            }

            XForm.makeTransform(tmpXForm2, -pointerX, -pointerY, magnifierZoom, magnifierZoom, 0);
        } else {
            if (!fullscreen) {
                int pointerY = 0;
                if (screenOffsetYRelativeToCursor) {
                    short halfScreenHeight = (short)(xServer.screenInfo.height / 2);
                    pointerY = Mathf.clamp(xServer.pointer.getY() - halfScreenHeight / 2, 0, halfScreenHeight);
                }

                XForm.makeTransform(tmpXForm2, viewTransformation.sceneOffsetX, viewTransformation.sceneOffsetY - pointerY, viewTransformation.sceneScaleX, viewTransformation.sceneScaleY, 0);

                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            } else {
                XForm.identity(tmpXForm2);
            }
        }

        renderWindows();

        // In native mode the pointer is composited by the cursor SurfaceControl (above the GL
        // content), so skip the GL cursor pass to avoid a double-drawn cursor.
        if (cursorVisible && !nativeMode) renderCursor();

        if (!magnifierEnabled && !fullscreen) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }

        if (xrFrame) {
            XrActivity.getInstance().endFrame();
            XrActivity.updateControllers();
            xServerView.requestRender();
        }
    }

    @Override public void onMapWindow(Window window) { xServerView.queueEvent(this::updateScene); xServerView.requestRender(); }
    @Override public void onUnmapWindow(Window window) { xServerView.queueEvent(this::updateScene); xServerView.requestRender(); }
    @Override public void onChangeWindowZOrder(Window window) { xServerView.queueEvent(this::updateScene); xServerView.requestRender(); }
    @Override public void onUpdateWindowContent(Window window) { xServerView.requestRender(); }
    @Override public void onUpdateWindowGeometry(final Window window, boolean resized) { if (resized) xServerView.queueEvent(this::updateScene); else xServerView.queueEvent(() -> updateWindowPosition(window)); xServerView.requestRender(); }
    @Override public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            if (nativeMode && scanout != null) {
                Window pw = xServer.inputDeviceManager.getPointWindow();
                if (pw == window) { lastScanoutCursor = window.attributes.getCursor(); sendCursorToScanout(lastScanoutCursor); }
            }
            xServerView.requestRender();
        }
    }
    @Override public void onPointerMove(short x, short y) {
        if (nativeMode && scanout != null) {
            Window pw = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
            if (cursor != lastScanoutCursor) { lastScanoutCursor = cursor; sendCursorToScanout(cursor); }
            short hotX = 0, hotY = 0;
            if (cursor != null) { hotX = (short) cursor.hotSpotX; hotY = (short) cursor.hotSpotY; }
            scanout.setCursorPos(x, y, hotX, hotY);
        }
        xServerView.requestRender();
    }

    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material) {
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);

            XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);
            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            // Apply the user-selected filter only to window/content drawables. The cursor goes
            // through cursorMaterial and is left at its texture's default LINEAR so the pointer
            // stays smooth. This is sampler state only — it does NOT touch the GPUImage upload path.
            if (material == windowMaterial) {
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, windowTexFilter);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, windowTexFilter);
            }
            GLES20.glUniform1i(material.getUniformLocation("texture"), 0);
            GLES20.glUniform1fv(material.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private void renderWindows() {
        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            int startIndex = 0;
            int screenWidth = xServer.screenInfo.width;
            int screenHeight = xServer.screenInfo.height;

            for (int i = renderableWindows.size() - 1; i >= 0; i--) {
                RenderableWindow rWin = renderableWindows.get(i);
                if (rWin.content != null && rWin.content.width >= screenWidth && rWin.content.height >= screenHeight) {
                    startIndex = i; 
                    break;
                }
            }

            for (int i = startIndex; i < renderableWindows.size(); i++) {
                RenderableWindow window = renderableWindows.get(i);
                renderDrawable(window.content, window.rootX, window.rootY, windowMaterial);
            }
        }

        quadVertices.disable();

        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e("GLRenderer", "OpenGL Error: " + error);
        }
    }

    private void renderCursor() {
        cursorMaterial.use();
        GLES20.glUniform2f(cursorMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(cursorMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            Window pointWindow = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
            short x = xServer.pointer.getClampedX();
            short y = xServer.pointer.getClampedY();

            if (cursor != null) {
                if (cursor.isVisible()) renderDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY, cursorMaterial);
            }
            else renderDrawable(rootCursorDrawable, x, y, cursorMaterial);
        }
        quadVertices.disable();
    }

    public void toggleFullscreen() {
        toggleFullscreen = true;
        xServerView.requestRender();
    }

    // ---- GL spatial-upscaler support (EffectComposer low-res render-target stage) ----
    // Render ONLY the windows (no cursor) into the currently-bound framebuffer, with the
    // viewport scaled by `scale`. This produces the low-resolution source that the
    // EffectComposer SGSR/FSR pass upsamples back to surface resolution. It mirrors the
    // default frame path (magnifier-enabled, zoom == 1, windowed) used by drawFrame()
    // exactly, minus the cursor — so the pointer is NEVER rendered into the low-res target
    // and stays crisp (it is composited full-res afterwards by drawCursorFullRes()).
    // drawFrame() itself is left byte-identical; this is only ever called on the GL
    // upscaler path, which EffectComposer gates to the windowed/non-magnified case.
    public void drawWindowsScaled(float scale) {
        int vx = Math.round(viewTransformation.viewOffsetX * scale);
        int vy = Math.round(viewTransformation.viewOffsetY * scale);
        int vw = Math.max(1, Math.round(viewTransformation.viewWidth  * scale));
        int vh = Math.max(1, Math.round(viewTransformation.viewHeight * scale));
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glViewport(vx, vy, vw, vh);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        XForm.makeTransform(tmpXForm2, 0, 0, 1, 1, 0);
        renderWindows();
        viewportNeedsUpdate = true; // restore the normal viewport on the next non-upscaler frame
    }

    // Composite the cursor at full surface resolution on top of the already-upscaled frame.
    // Uses the same view-region viewport + identity scene transform as the default windowed
    // frame, so the pointer position matches the non-upscaler path and is never point-sampled
    // or run through the upscaler. Caller must have the screen framebuffer (0) bound.
    public void drawCursorFullRes() {
        if (!cursorVisible) return;
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                          viewTransformation.viewWidth, viewTransformation.viewHeight);
        XForm.makeTransform(tmpXForm2, 0, 0, 1, 1, 0);
        renderCursor();
        viewportNeedsUpdate = true;
    }

    private Drawable createRootCursorDrawable() {
        Context context = xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    private void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            collectRenderableWindows(xServer.windowManager.rootWindow, xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());
        }
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;
            if (unviewableWMClasses != null) {
                String wmClass = window.getClassName();
                for (String unviewableWMClass : unviewableWMClasses) {
                    if (wmClass.contains(unviewableWMClass)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false;
                        break;
                    }
                }
            }
            if (viewable)
                renderableWindows.add(new RenderableWindow(window.getContent(), x, y));
        }
        for (Window child : window.getChildren()) {
            collectRenderableWindows(child, child.getX() + x, child.getY() + y);
        }
    }

    private void updateWindowPosition(Window window) {
        for (RenderableWindow renderableWindow : renderableWindows) {
            if (renderableWindow.content == window.getContent()) {
                renderableWindow.rootX = window.getRootX();
                renderableWindow.rootY = window.getRootY();
                break;
            }
        }
    }

    public void setCursorVisible(boolean cursorVisible) { this.cursorVisible = cursorVisible; xServerView.requestRender(); }
    public boolean isCursorVisible() { return cursorVisible; }
    public boolean isScreenOffsetYRelativeToCursor() { return screenOffsetYRelativeToCursor; }
    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) { this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor; xServerView.requestRender(); }
    public boolean isFullscreen() { return fullscreen; }
    public float getMagnifierZoom() { return magnifierZoom; }
    public void setMagnifierZoom(float magnifierZoom) { this.magnifierZoom = magnifierZoom; xServerView.requestRender(); }
    public int getSurfaceWidth() { return surfaceWidth; }
    public int getSurfaceHeight() { return surfaceHeight; }
    public boolean isViewportNeedsUpdate() { return viewportNeedsUpdate; }
    public void setViewportNeedsUpdate(boolean viewportNeedsUpdate) { this.viewportNeedsUpdate = viewportNeedsUpdate; }
    public VertexAttribute getQuadVertices() { return quadVertices; }
    public EffectComposer getEffectComposer (){ return effectComposer; }
    public void setUnviewableWMClasses(String... unviewableWMNames) { this.unviewableWMClasses = unviewableWMNames; }

    // HostRenderer implementation
    @Override public XServerView getXServerView() { return xServerView; }
    // Forward to the X server so the direct-scanout first-frame pause (P4) can stop guest content
    // updates; was a no-op before native rendering came to GL. Mirrors VulkanRenderer.setRenderingEnabled.
    @Override public void setRenderingEnabled(boolean enabled) { xServer.setRenderingEnabled(enabled); }
    @Override public void requestRender() { xServerView.requestRender(); }
    @Override public void forceCleanup() {
        // EXPERIMENT A: best-effort GL-thread release of the host overlay AHB/FBO (process may be tearing
        // down; if the GL thread is already gone the queued event simply won't run and the buffer dies
        // with the EGL context).
        xServerView.queueEvent(() -> {
            releaseHostScanout();
            hostScanoutFailed = false;
            hostScanoutTimeouts = 0;
        });
        if (scanout != null) scanout.disable();
        xServer.setRenderingEnabled(true);
        xRenderingPausedForScanout = false;
    }
    @Override public void setFilterMode(int mode) {
        // Convention shared with the Vulkan side (setUpscaler modes 1/2): 2 == Nearest (point),
        // everything else (0=default, 1=linear) == Linear (bilinear). Window/content drawables only.
        windowTexFilter = (mode == 2) ? GLES20.GL_NEAREST : GLES20.GL_LINEAR;
        xServerView.requestRender();
    }
    @Override public void setFpsWindowId(int id) {}
    @Override public void setFrameRating(Object fr) {}
    @Override public int getFpsLimit() { return 0; }
    @Override public void setFpsLimit(int limit) {}

    // ---- GL Native Rendering (direct scanout) lifecycle — mirrors VulkanRenderer ----

    /** R/B-swap hint for the game SurfaceControl color transform. Seeded at launch from the
     *  container's renderer swap-R/B setting; consumed by {@link DirectScanout#enable}. */
    public void setSwapRB(boolean enabled) { this.swapRB = enabled; }

    public boolean isNativeMode() { return nativeMode; }

    // EXPERIMENT B: force the activity to the panel's native (portrait) orientation while scanout is
    // active so the window/base surface and the panel agree -> nothing forces a per-layer ROT_90 back
    // onto the (pre-rotated, panel-res) game SC. The activity declares orientation|screenSize in
    // configChanges, so this reconfigures in place (GL surfaceChanged) without an activity recreation.
    // Restored to the manifest default (sensorLandscape) on native-off / teardown. UI thread.
    private void setProbeOrientation(boolean portrait) {
        android.content.Context ctx = xServerView.getContext();
        if (!(ctx instanceof android.app.Activity)) return;
        try {
            ((android.app.Activity) ctx).setRequestedOrientation(portrait
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } catch (Exception e) {
            Log.w("GLRenderer", "EXP-B: setRequestedOrientation failed: " + e);
        }
    }

    // Set the desired native (direct-scanout) mode BEFORE the surface is created. onSurfaceCreated
    // builds the scanout SurfaceControls when nativeMode is already true, so this is the correct
    // entry point for applying the container's "native" toggle at launch (setNativeMode() is for
    // toggling at runtime, with the full SurfaceControl rebuild + toast). Mirrors VulkanRenderer.
    public void setInitialNativeMode(boolean v) { this.nativeMode = v; }

    /** Runtime toggle of native rendering: builds/tears down the scanout SurfaceControls and shows a
     *  toast. Mirrors VulkanRenderer.setNativeMode (minus the per-frame scanout push, which is P4). */
    public void setNativeMode(boolean mode) {
        if (this.nativeMode == mode) return;
        this.nativeMode = mode;
        xRenderingPausedForScanout = false;
        if (mode) {
            xServer.setRenderingEnabled(true);
            enableScanout();
        } else {
            disableScanout();
            xServerView.post(() -> {
                setProbeOrientation(false);   // EXPERIMENT B: restore landscape
                xServer.setRenderingEnabled(true);
                xServerView.requestRender();
            });
        }
        xServerView.queueEvent(this::updateScene);
        final String msg = mode ? "Native Rendering+ Enabled" : "Native Rendering+ Disabled";
        // Use the app's styled toast (white text on a custom background); a raw Toast here would
        // inherit the dark app theme and render as a black box with invisible text.
        xServerView.post(() -> com.winlator.star.core.AppUtils.showToast(xServerView.getContext(), msg));
    }

    // Build the sibling game + cursor SurfaceControls under the GLSurfaceView's SurfaceControl. The SC
    // ops must run on the UI thread AFTER the surface is created (same constraint as the Vulkan path),
    // so this posts to the view; getSurfaceControl() is non-null for GL since P2.
    private void enableScanout() {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        xServerView.post(() -> {
            try {
                // EXPERIMENT B: lock to the panel's native portrait orientation so the pre-rotated,
                // panel-res game SC needs neither a per-layer rotation nor a scale.
                setProbeOrientation(true);
                android.view.SurfaceControl parent =
                    (android.view.SurfaceControl) xServerView.getSurfaceControl();
                if (parent == null) {
                    Log.w("GLRenderer", "Native Rendering: GL SurfaceControl is null; cannot enable scanout");
                    return;
                }
                if (scanout == null) scanout = new DirectScanout();
                float targetFps = xServerView.getDisplay() != null
                    ? xServerView.getDisplay().getRefreshRate() : 60f;
                // DirectScanout builds the child game (layer 1) + cursor (layer 2) SCs, both above the
                // GL surface content; the opaque game SC stays bufferless until the P4 per-frame push.
                scanout.enable(parent, xServer.screenInfo.width, xServer.screenInfo.height, targetFps, swapRB);
                scanout.setSurfaceSize(surfaceWidth, surfaceHeight);
                updateScanoutDst();
                sendCursorToScanout(lastScanoutCursor);
            } catch (Exception e) {
                Log.w("GLRenderer", "GL scanout enable failed: " + e);
            }
        });
    }

    private void disableScanout() {
        // EXPERIMENT A: free the host overlay AHB/FBO on the GL thread and reset the sticky guards so a
        // later re-enable retries the probe. Queue this before tearing down the scanout SCs.
        xServerView.queueEvent(() -> {
            releaseHostScanout();
            hostScanoutFailed = false;
            hostScanoutTimeouts = 0;
        });
        if (scanout == null) return;
        final DirectScanout s = scanout;
        xServerView.post(s::disable);
    }

    /** Called by XServerView when the GL surface is destroyed (rotation, app-switch): release the
     *  scanout SurfaceControls so they don't leak/orphan against the dead GL surface. They are rebuilt
     *  in onSurfaceCreated when nativeMode is still on. */
    public void onSurfaceDestroyed() {
        disableScanout();
    }

    // Feed the destination (on-screen) rect to the scanout context — the letterbox/fullscreen mapping
    // the GL pass uses for its glViewport. Same data the Vulkan updateTransform feeds nativeScanoutSetDst.
    private void updateScanoutDst() {
        if (scanout == null || !nativeMode) return;
        if (fullscreen) {
            scanout.setDst(0, 0, surfaceWidth, surfaceHeight);
        } else {
            scanout.setDst(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                           viewTransformation.viewWidth, viewTransformation.viewHeight);
        }
    }

    // Push the current cursor image to the cursor SurfaceControl (applies inline in the native lib).
    // Mirrors VulkanRenderer.sendCursorToNative's scanout cursor path. Runs on the epoll thread.
    private void sendCursorToScanout(Cursor cursor) {
        if (scanout == null || !nativeMode) return;
        Drawable cd;
        if (cursor != null) {
            if (!cursor.isVisible()) return;
            cd = cursor.cursorImage;
        } else {
            cd = rootCursorDrawable;
        }
        if (cd != null && cd.getBuffer() != null) {
            synchronized (cd.renderLock) {
                ByteBuffer buf = cd.getBuffer();
                short stride = (short) (buf.capacity() / (cd.height * 4));
                scanout.setCursorImage(buf, cd.width, cd.height, stride);
            }
        }
    }

    /**
     * Per-frame game-AHB push for GL Native Rendering (P4). Called from PresentExtension's GL-native
     * FLIP branch on the X11/epoll thread (NOT the GL draw thread). On the FIRST delivered frame it
     * pauses X rendering so the GLSurfaceView idles and the opaque game SC can be promoted to an HWC
     * overlay (the whole point — SurfaceFlinger skips GL composition). The FLIP path bypasses the GL
     * effect chain and the HUD's copyArea driver, so tick the HUD here too or FPS freezes.
     *
     * <p><b>EXPERIMENT A (this branch only):</b> instead of presenting the GUEST buffer directly, blit
     * it into a host AHB carrying {@code COMPOSER_OVERLAY | GPU_FRAMEBUFFER} (same size/orientation/scale,
     * NO pre-rotation) and present THAT — to test whether the Adreno DPU rejects the layer for usage/UBWC
     * reasons rather than its ROT_90. The blit runs on the GL thread; on any failure/timeout this falls
     * back to the original direct guest-buffer present, so native rendering is never broken.
     *
     * <p>The caller (PresentExtension) already holds {@code content.renderLock}; the re-entrant
     * synchronize mirrors the Vulkan path and is harmless. {@code content.getTexture()} is the
     * pixmap's GPUImage (the branch swaps it in before calling this).
     */
    public void presentScanout(Window window, Drawable content) {
        if (scanout == null || !nativeMode || content == null) return;
        if (!window.attributes.isMapped()) return;
        final int rx = window.getRootX(), ry = window.getRootY();
        synchronized (content.renderLock) {
            if (!(content.getTexture() instanceof GPUImage)) return;
            final GPUImage g = (GPUImage) content.getTexture();
            final long guestAhb = g.getHardwareBufferPtr();
            if (guestAhb == 0) return;
            final int w = content.width, h = content.height;

            final boolean wasDelivered = scanout.isGameFrameDelivered();

            // EXPERIMENT A: try the COMPOSER_OVERLAY host-AHB blit on the GL thread; if it cannot run
            // (sticky failure / repeated timeouts) present the guest buffer directly (original P4 path).
            // The GL blit MUST run on the GL thread (this method is on the X11/epoll thread, which has no
            // GL context), so we marshal it via queueEvent and block here (briefly) for the result — the
            // epoll thread already holds content.renderLock for the whole present, so the guest buffer
            // stays valid while the GL thread samples it. The GL runnable does NOT re-take renderLock.
            boolean handled = false;
            if (!hostScanoutFailed && hostScanoutTimeouts < 3) {
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final boolean[] ok = { false };
                xServerView.queueEvent(() -> {
                    try { ok[0] = blitGuestIntoHostScanoutAndPresent(g, content, rx, ry, w, h); }
                    catch (Throwable t) { ok[0] = false; Log.w("GLRenderer", "scanout host-AHB blit threw: " + t); }
                    finally { latch.countDown(); }
                });
                try {
                    if (latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        handled = ok[0];
                        hostScanoutTimeouts = 0;
                    } else {
                        // GL thread didn't complete in time; skip the fallback for THIS frame to avoid a
                        // double present (the queued runnable may still present later). Count it so a
                        // persistently stuck GL thread disables the experiment and we return to direct present.
                        hostScanoutTimeouts++;
                        handled = true;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    handled = true; // don't fall through to a second present
                }
            }

            if (!handled) {
                // Fallback / control path: present the guest buffer directly (original behavior).
                int fence = g.unlock();
                scanout.present(guestAhb, rx, ry, w, h, fence);
                g.lock();
                content.refreshDataFromTexture();
            }

            final boolean delivered = scanout.isGameFrameDelivered();
            // First delivered frame: stop guest content updates so GLSurfaceView stops redrawing and
            // the opaque top game SC occludes the stale GL frame -> SurfaceFlinger can HWC-overlay it.
            if (!xRenderingPausedForScanout && !wasDelivered && delivered) {
                xServer.setRenderingEnabled(false);
                xRenderingPausedForScanout = true;
            }
            if (hudFrameTick != null) hudFrameTick.accept(window.id);
        }
    }

    /**
     * EXPERIMENT B — GL thread only. Blit the guest game texture into a PANEL-RESOLUTION host
     * overlay-eligible AHB while rotating it 90° (UV transpose), then present src==dst==full-panel at
     * transform 0 (no SC-scale; combined with the portrait activity lock, no per-layer rotation either).
     * Returns true if the host buffer was presented; false if setup failed (caller then falls back to a
     * direct guest-buffer present). DIAGNOSTIC: orientation/aspect of the visible image are accepted as
     * wrong. Must run with the EGL context current (queued via xServerView.queueEvent, like captureScreenshot).
     */
    private boolean blitGuestIntoHostScanoutAndPresent(GPUImage g, Drawable content,
                                                        int rx, int ry, int w, int h) {
        // EXPERIMENT B: the host scanout buffer is allocated at PANEL resolution (the activity is locked
        // to the panel's native portrait orientation, so surfaceWidth x surfaceHeight == the panel), and
        // the guest landscape frame is rotated 90° to fill it. We then present src==dst==full-panel with
        // transform 0 -> no SC-level scale, and (because window+panel agree) no per-layer rotation. This
        // neutralizes the ROT_90 and the scale at once, leaving UBWC as the last variable.
        final int pw = surfaceWidth, ph = surfaceHeight;
        if (pw <= 0 || ph <= 0) return false;
        if (!ensureHostScanout(pw, ph)) return false;

        // Ensure the guest GPUImage has its EGLImage-backed GL texture (native FLIP mode never renders
        // it through renderWindows, so it may not be allocated yet). allocateTexture is idempotent.
        g.updateFromDrawable(content);
        int srcTex = g.getTextureId();
        if (srcTex == 0) return false;

        // Preserve the guest buffer's lock lifecycle (matches the direct path: unlock for the consumer
        // to read coherently, re-lock + refresh afterward). The texture is the zero-copy EGLImage, so it
        // stays valid across the CPU unlock.
        g.unlock();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hostScanoutFbo);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glViewport(0, 0, pw, ph);

        scanoutBlitRot90Material.use();
        quadVertices.bind(scanoutBlitRot90Material.programId);
        GLES20.glUniform2f(scanoutBlitRot90Material.getUniformLocation("viewSize"), pw, ph);
        XForm.set(scanoutBlitXForm, 0, 0, pw, ph);
        GLES20.glUniform1fv(scanoutBlitRot90Material.getUniformLocation("xform"),
                scanoutBlitXForm.length, scanoutBlitXForm, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
        // Rotated UVs ride the [0,1] edges -> clamp so we never wrap-sample the opposite edge.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glUniform1i(scanoutBlitRot90Material.getUniformLocation("texture"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());

        quadVertices.disable();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        // Restore the blend state the base path expects (set once in onSurfaceCreated).
        GLES20.glEnable(GLES20.GL_BLEND);
        // The base GLSurfaceView viewport may have been clobbered; force a recompute next base frame.
        viewportNeedsUpdate = true;

        // The probe favors simplicity/correctness over throughput: make sure the GPU has finished
        // writing the host buffer before SurfaceFlinger reads it, then present with no fence (-1).
        GLES20.glFinish();
        // src == dst == full panel: tell the scanout context the buffer is panel-sized and the dst is
        // the whole panel, so ScanoutContext::setGeometry emits src==dst (no scale) at transform 0.
        scanout.setContainerSize(pw, ph);
        scanout.setDst(0, 0, pw, ph);
        scanout.present(hostScanoutImage.getHardwareBufferPtr(), 0, 0, pw, ph, -1);

        // Restore the guest buffer's CPU lock (mirrors the direct path's g.lock() + refresh).
        g.lock();
        content.refreshDataFromTexture();
        return true;
    }

    /** EXPERIMENT A — GL thread only. (Re)allocate the host overlay AHB + its FBO on first use / size
     *  change. Sticky-fails (so we stop retrying and fall back) if allocation or FBO completeness fails. */
    private boolean ensureHostScanout(int w, int h) {
        if (hostScanoutFailed) return false;
        if (w <= 0 || h <= 0) return false;
        if (hostScanoutImage != null && hostScanoutFbo != 0 && hostScanoutW == w && hostScanoutH == h)
            return true;

        releaseHostScanout();

        GPUImage img = new GPUImage((short) w, (short) h, true);
        img.allocateTexture((short) w, (short) h, null);
        if (img.getHardwareBufferPtr() == 0 || img.getTextureId() == 0) {
            Log.w("GLRenderer", "EXP-A: host scanout AHB/texture alloc failed; falling back to direct present");
            img.destroy();
            hostScanoutFailed = true;
            return false;
        }

        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, img.getTextureId(), 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w("GLRenderer", "EXP-A: host scanout FBO incomplete (0x" + Integer.toHexString(status)
                    + "); falling back to direct present");
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            img.destroy();
            hostScanoutFailed = true;
            return false;
        }

        hostScanoutImage = img;
        hostScanoutFbo = fbo[0];
        hostScanoutW = w;
        hostScanoutH = h;
        Log.d("GLRenderer", "EXP-A: host overlay scanout AHB ready " + w + "x" + h);
        return true;
    }

    /** EXPERIMENT A — GL thread only. Free the host overlay AHB + FBO. */
    private void releaseHostScanout() {
        if (hostScanoutFbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{ hostScanoutFbo }, 0);
            hostScanoutFbo = 0;
        }
        if (hostScanoutImage != null) {
            hostScanoutImage.destroy();
            hostScanoutImage = null;
        }
        hostScanoutW = hostScanoutH = 0;
    }

    private static class RenderableWindow {
        public final Drawable content;
        public int rootX, rootY;
        public RenderableWindow(Drawable content, int rootX, int rootY) {
            this.content = content; this.rootX = rootX; this.rootY = rootY;
        }
    }
}
