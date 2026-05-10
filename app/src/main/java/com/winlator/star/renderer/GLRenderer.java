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

public class GLRenderer implements GLSurfaceView.Renderer, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {

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
    private float magnifierZoom = 1.0f;
    private boolean magnifierEnabled = true;
    public int surfaceWidth;
    public int surfaceHeight;
    
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
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
        }

        if (effectComposer != null && effectComposer.hasEffects() && surfaceWidth > 0 && surfaceHeight > 0) {
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

        if (cursorVisible) renderCursor();

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
    @Override public void onUpdateWindowAttributes(Window window, Bitmask mask) { if (mask.isSet(WindowAttributes.FLAG_CURSOR)) xServerView.requestRender(); }
    @Override public void onPointerMove(short x, short y) { xServerView.requestRender(); }

    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material) {
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);

            XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);
            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
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

    private static class RenderableWindow {
        public final Drawable content;
        public int rootX, rootY;
        public RenderableWindow(Drawable content, int rootX, int rootY) {
            this.content = content; this.rootX = rootX; this.rootY = rootY;
        }
    }
}
