package com.winlator.star.renderer;

import com.winlator.star.widget.XServerView;

public interface HostRenderer {
    XServerView getXServerView();
    void setRenderingEnabled(boolean enabled);
    void requestRender();
    void forceCleanup();
    void setCursorVisible(boolean visible);
    boolean isCursorVisible();
    void setUnviewableWMClasses(String wmClasses);
    void setFilterMode(int mode);
    void setMagnifierZoom(float zoom);
    float getMagnifierZoom();
    void toggleFullscreen();
    boolean isFullscreen();
    // Fullscreen aspect-ratio mode (issue #71): Container.FULLSCREEN_OFF/FIT/STRETCH/...
    // isFullscreen() stays == (mode != OFF) so existing upscaler/magnifier gates behave as before.
    void setFullscreenMode(int mode);
    int getFullscreenMode();
    void setScreenOffsetYRelativeToCursor(boolean b);
    boolean isScreenOffsetYRelativeToCursor();
    void setFpsWindowId(int id);
    // One-shot callback fired on the FIRST real game frame (a GPUImage present reaching the
    // present path), passing the presenting window id. Used to dismiss the launch overlay only
    // once the game is actually rendering — launcher/splash/GDI windows go through the CPU path
    // and never fire this. Fires at most once, then is a no-op with zero steady-state cost.
    void setOnFirstGameFrame(java.util.function.IntConsumer c);
    void setFrameRating(Object fr);
    int getFpsLimit();
    void setFpsLimit(int limit);
    int getSurfaceWidth();
    int getSurfaceHeight();
}
