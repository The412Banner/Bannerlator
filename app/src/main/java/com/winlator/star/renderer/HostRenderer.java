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
    // Callback fired on EVERY real game frame (a GPUImage present reaching the present path),
    // passing the presenting window id. The activity uses the continuity of these presents to
    // dismiss the launch overlay only once the game is rendering *steadily* — a brief intro/logo
    // burst that then stops for a long black load does not qualify. Launcher/splash/GDI windows go
    // through the CPU path and never fire this. Steady-state cost is a null check plus one virtual
    // call per present (mirrors setHudFrameTick), no allocation.
    void setOnGameFramePresented(java.util.function.IntConsumer c);
    void setFrameRating(Object fr);
    int getFpsLimit();
    void setFpsLimit(int limit);
    int getSurfaceWidth();
    int getSurfaceHeight();
}
