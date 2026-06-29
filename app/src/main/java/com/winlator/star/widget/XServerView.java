package com.winlator.star.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.star.renderer.ASurfaceRenderer;
import com.winlator.star.renderer.GLRenderer;
import com.winlator.star.renderer.HostRenderer;
import com.winlator.star.renderer.vulkan.VulkanRenderer;
import com.winlator.star.xserver.XServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("ViewConstructor")
public class XServerView extends FrameLayout {
    private HostRenderer renderer;
    private SurfaceView vulkanSurfaceView;
    private GLSurfaceView glSurfaceView;
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private XServer xServer;

    // VRR / refresh-rate matching. The last frame-rate vote requested by the activity is remembered
    // here and re-asserted in surfaceChanged() so it survives calls made before the surface exists
    // and any surface recreation. 0f means "no vote" (clears any previous request). The compatibility
    // is Surface.FRAME_RATE_COMPATIBILITY_DEFAULT (== 0) so SurfaceFlinger picks the seamless mapping
    // (e.g. 60 -> 59.94) itself.
    private float lastFrameRate = 0f;
    private int lastFrameRateCompat = 0;
    // Flip to true to only switch the panel mode when it can be done seamlessly (no black frame).
    // API 31+ only; default keeps it simple and lets SurfaceFlinger decide.
    private static final boolean FRAME_RATE_SEAMLESS_ONLY = false;

    public XServerView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void initRenderer(boolean vulkan) {
        initRenderer(vulkan ? "vulkan" : "gl");
    }

    public void initRenderer(String rendererType) {
        boolean vulkan = "vulkan".equalsIgnoreCase(rendererType);
        boolean surfaceFlinger = "surfaceflinger".equalsIgnoreCase(rendererType);
        // ASR needs every Drawable backed by a composer-compatible AHardwareBuffer. Global flag —
        // set before any window content Drawable is created (and clear it for GL/Vulkan).
        com.winlator.star.xserver.Drawable.setAsrMode(surfaceFlinger);
        if (surfaceFlinger) {
            vulkanSurfaceView = new SurfaceView(getContext());
            vulkanSurfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            addView(vulkanSurfaceView);
            final ASurfaceRenderer asrRenderer = new ASurfaceRenderer(this, xServer);
            renderer = asrRenderer;
            vulkanSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    asrRenderer.onSurfaceCreated(holder.getSurface());
                }
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    asrRenderer.onSurfaceChanged(holder.getSurface(), width, height);
                    reassertFrameRate();
                }
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    asrRenderer.onSurfaceDestroyed();
                }
            });
        } else if (vulkan) {
            vulkanSurfaceView = new SurfaceView(getContext());
            vulkanSurfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            addView(vulkanSurfaceView);
            renderer = new VulkanRenderer(this, xServer);
            vulkanSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    ((VulkanRenderer)renderer).onSurfaceCreated(holder.getSurface());
                }
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    ((VulkanRenderer)renderer).onSurfaceChanged(width, height);
                    reassertFrameRate();
                }
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    ((VulkanRenderer)renderer).onSurfaceDestroyed();
                }
            });
        } else {
            glSurfaceView = new GLSurfaceView(getContext());
            glSurfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            glSurfaceView.setEGLContextClientVersion(3);
            glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
            glSurfaceView.setPreserveEGLContextOnPause(true);
            final GLRenderer glRenderer = new GLRenderer(this, xServer);
            renderer = glRenderer;
            glSurfaceView.setRenderer(glRenderer);
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            // GLSurfaceView manages its own SurfaceHolder.Callback for EGL; we add an extra one to
            // re-assert the VRR frame-rate vote when the surface is (re)created, and to tear down the
            // GL native-rendering scanout SurfaceControls when the surface goes away (rotation,
            // app-switch). The scanout SCs are rebuilt in GLRenderer.onSurfaceCreated.
            glSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {}
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    reassertFrameRate();
                }
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    glRenderer.onSurfaceDestroyed();
                }
            });
            addView(glSurfaceView);
        }
    }

    public HostRenderer getRenderer() {
        return renderer;
    }

    public SurfaceHolder getHolder() {
        return vulkanSurfaceView != null ? vulkanSurfaceView.getHolder() : null;
    }

    public void requestRender() {
        if (glSurfaceView != null) glSurfaceView.requestRender();
        else if (renderer != null) renderer.requestRender();
    }

    public void queueEvent(Runnable r) {
        if (glSurfaceView != null) glSurfaceView.queueEvent(r);
        else eventExecutor.execute(r);
    }

    public void onPause() {
        if (glSurfaceView != null) glSurfaceView.onPause();
    }

    public void onResume() {
        if (glSurfaceView != null) glSurfaceView.onResume();
    }

    public Object getSurfaceControl() {
        // GLSurfaceView extends SurfaceView, so it inherits getSurfaceControl() (API 29+).
        // Returning the GL surface's SurfaceControl lets DirectScanout host child game/cursor
        // SurfaceControls under it (GL Native Rendering, P2+). For Vulkan, unchanged.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (vulkanSurfaceView != null) return vulkanSurfaceView.getSurfaceControl();
            if (glSurfaceView != null) return glSurfaceView.getSurfaceControl();
        }
        return null;
    }

    // Vote a target display refresh rate for the active host renderer's window (VRR / refresh-rate
    // matching). ONE Surface-level vote covers all three renderers: SurfaceFlinger aggregates
    // frame-rate votes over a layer subtree, so a vote on the parent SurfaceView also covers the
    // Vulkan/ASR native child SurfaceControls. fps == 0f clears the vote (lets the panel run free).
    // The request is remembered and re-asserted in surfaceChanged() (see reassertFrameRate()).
    // Safe to call before the surface exists. Surface.setFrameRate is API 30+, so it is a no-op
    // below that (minSdk is 26).
    public void setDisplayFrameRate(float fps, int compatibility) {
        lastFrameRate = fps;
        lastFrameRateCompat = compatibility;
        applyFrameRateToSurface(fps, compatibility);
    }

    // Re-apply the last requested vote; called from the surfaceChanged callbacks so the vote
    // survives surface (re)creation and any early call made before the surface was valid.
    private void reassertFrameRate() {
        applyFrameRateToSurface(lastFrameRate, lastFrameRateCompat);
    }

    private void applyFrameRateToSurface(float fps, int compatibility) {
        if (Build.VERSION.SDK_INT < 30) return;
        SurfaceHolder holder = null;
        if (vulkanSurfaceView != null) holder = vulkanSurfaceView.getHolder();
        else if (glSurfaceView != null) holder = glSurfaceView.getHolder();
        if (holder == null) return;
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // The 2-arg setFrameRate() defaults to CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS, which a
                // peak-refresh panel (e.g. locked at 144) ignores — it won't drop to the cap. To make the
                // refresh rate actually follow the FPS we pass the strategy explicitly: ALWAYS (force the
                // switch, brief flash OK) unless FRAME_RATE_SEAMLESS_ONLY opts into seamless-only.
                int strategy = FRAME_RATE_SEAMLESS_ONLY
                        ? Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                        : Surface.CHANGE_FRAME_RATE_ALWAYS;
                surface.setFrameRate(fps, compatibility, strategy);
            } else {
                // API 30 only has the 2-arg overload (no strategy parameter).
                surface.setFrameRate(fps, compatibility);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Surface was released, or the rate/compat is rejected — ignore, vote is best-effort.
        }
    }

    /** True if this display can actually do VRR (refresh-rate matching): Surface.setFrameRate exists
     *  (API 30+) AND the panel exposes more than one distinct refresh rate to switch between. */
    public static boolean isDisplayVrrCapable(android.view.Display display) {
        if (Build.VERSION.SDK_INT < 30 || display == null) return false;
        java.util.HashSet<Integer> rates = new java.util.HashSet<>();
        for (android.view.Display.Mode m : display.getSupportedModes()) rates.add(Math.round(m.getRefreshRate()));
        return rates.size() > 1;
    }

    /** The display's current (live) refresh rate, rounded. 0 if unavailable. Reflects the panel's
     *  active mode, so it shows what VRR/Auto actually landed on (not just what we requested). */
    public static int getCurrentRefreshRate(android.view.Display display) {
        if (display == null) return 0;
        android.view.Display.Mode mode = display.getMode();
        float rate = mode != null ? mode.getRefreshRate() : display.getRefreshRate();
        return Math.round(rate);
    }

    /** Distinct supported refresh rates (rounded, ascending). Empty if <2 (nothing to pick). */
    public static java.util.List<Integer> getSupportedRefreshRates(android.view.Display display) {
        java.util.TreeSet<Integer> rates = new java.util.TreeSet<>();
        if (Build.VERSION.SDK_INT >= 30 && display != null)
            for (android.view.Display.Mode m : display.getSupportedModes()) rates.add(Math.round(m.getRefreshRate()));
        return rates.size() > 1 ? new java.util.ArrayList<>(rates) : new java.util.ArrayList<>();
    }
}
