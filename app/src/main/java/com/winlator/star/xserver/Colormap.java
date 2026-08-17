package com.winlator.star.xserver;

// ERL bug report #7: colormaps had no representation at all — Create/Free were no-ops
// and GetWindowAttributes always reported None. Visuals here are TrueColor-only, so this
// is pure id/lifecycle bookkeeping (no real color-index allocation), just enough for
// Create/Free/GetWindowAttributes to agree so Mesa stops warning "Window has no colormap!".
public class Colormap extends XResource {
    public final int window;
    public final int visual;

    public Colormap(int id, int window, int visual) {
        super(id);
        this.window = window;
        this.visual = visual;
    }
}
