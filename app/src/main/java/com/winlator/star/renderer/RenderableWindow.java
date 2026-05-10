package com.winlator.star.renderer;

import com.winlator.star.xserver.Drawable;

class RenderableWindow {
    final Drawable content;
    short rootX;
    short rootY;

    public RenderableWindow(Drawable content, int rootX, int rootY) {
        this.content = content;
        this.rootX = (short) rootX;
        this.rootY = (short) rootY;
    }
}
