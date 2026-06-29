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
     * FLIP branch on the X11/epoll thread (NOT the GL draw thread). This is the lift of the
     * AHB-scanout body of {@code VulkanRenderer.onUpdateWindowContent}: unlock the GPUImage to get
     * its fence, hand the {@code AHardwareBuffer} to the game SurfaceControl (DirectScanout applies
     * the transaction inline, the GL model), then re-lock + refresh. On the FIRST delivered frame it
     * pauses X rendering so the GLSurfaceView idles and the opaque game SC can be promoted to an HWC
     * overlay (the whole point — SurfaceFlinger skips GL composition). The FLIP path bypasses the GL
     * effect chain and the HUD's copyArea driver, so tick the HUD here too or FPS freezes.
     *
     * <p>The caller (PresentExtension) already holds {@code content.renderLock}; the re-entrant
     * synchronize mirrors the Vulkan path and is harmless. {@code content.getTexture()} is the
     * pixmap's GPUImage (the branch swaps it in before calling this).
     */
    public void presentScanout(Window window, Drawable content) {
        if (scanout == null || !nativeMode || content == null) return;
        if (!window.attributes.isMapped()) return;
        int rx = window.getRootX(), ry = window.getRootY();
        synchronized (content.renderLock) {
            if (!(content.getTexture() instanceof GPUImage)) return;
            GPUImage g = (GPUImage) content.getTexture();
            long ahbPtr = g.getHardwareBufferPtr();
            if (ahbPtr == 0) return;

            boolean wasDelivered = scanout.isGameFrameDelivered();
            int fence = g.unlock();
            scanout.present(ahbPtr, rx, ry, content.width, content.height, fence);
            g.lock();
            content.refreshDataFromTexture();
            boolean delivered = scanout.isGameFrameDelivered();

            // First delivered frame: stop guest content updates so GLSurfaceView stops redrawing and
            // the opaque top game SC occludes the stale GL frame -> SurfaceFlinger can HWC-overlay it.
            if (!xRenderingPausedForScanout && !wasDelivered && delivered) {
                xServer.setRenderingEnabled(false);
                xRenderingPausedForScanout = true;
            }
            if (hudFrameTick != null) hudFrameTick.accept(window.id);
        }
    }

    private static class RenderableWindow {
        public final Drawable content;
        public int rootX, rootY;
        public RenderableWindow(Drawable content, int rootX, int rootY) {
            this.content = content; this.rootX = rootX; this.rootY = rootY;
        }
    }
}
