package com.winlator.star.xserver;

import android.util.SparseArray;

// ERL bug report #7. Mirrors PixmapManager: tracks Colormap resources so Create/Free and
// per-client cleanup on disconnect work like every other resource type.
public class ColormapManager extends XResourceManager {
    private final SparseArray<Colormap> colormaps = new SparseArray<>();

    public Colormap createColormap(int id, int window, int visual) {
        if (colormaps.indexOfKey(id) >= 0) return null;
        Colormap colormap = new Colormap(id, window, visual);
        colormaps.put(id, colormap);
        triggerOnCreateResourceListener(colormap);
        return colormap;
    }

    public void freeColormap(int id) {
        Colormap colormap = colormaps.get(id);
        if (colormap != null) {
            triggerOnFreeResourceListener(colormap);
            colormaps.remove(id);
        }
    }

    public Colormap getColormap(int id) {
        return colormaps.get(id);
    }
}
