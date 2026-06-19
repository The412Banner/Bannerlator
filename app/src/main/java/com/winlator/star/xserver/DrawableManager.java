package com.winlator.star.xserver;

import android.util.SparseArray;

import com.winlator.star.core.Callback;
import com.winlator.star.renderer.Texture;

public class DrawableManager extends XResourceManager implements XResourceManager.OnResourceLifecycleListener {
    private final XServer xServer;
    private final SparseArray<Drawable> drawables = new SparseArray<>();

    public DrawableManager(XServer xServer) {
        this.xServer = xServer;
        xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    public Drawable getDrawable(int id) {
        Drawable drawable = drawables.get(id);
        if (drawable != null && drawable.getData() == null) {
            throw new IllegalStateException("Drawable with id " + id + " has null data when fetched.");
        }
        return drawable;
    }

    public Drawable createDrawable(int id, short width, short height, byte depth) {
        return createDrawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth));
    }

    public Drawable createDrawable(int id, short width, short height, Visual visual) {
        if (id == 0) {
            Drawable drawable = new Drawable(id, width, height, visual);
            if (drawable.getData() == null) {
                throw new IllegalStateException("Drawable with id 0 has null data at creation.");
            }
            return drawable;
        }
        if (drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual);
        if (drawable.getData() == null) {
            throw new IllegalStateException("Drawable with id " + id + " has null data at creation.");
        }
        drawables.put(id, drawable);
        return drawable;
    }

    public void removeDrawable(int id) {
        Drawable drawable = drawables.get(id);
        if (drawable == null) {
            throw new IllegalStateException("Attempting to remove non-existent Drawable with id " + id);
        }

        final Texture texture = drawable.getTexture();
        if (texture != null) xServer.getRenderer().getXServerView().queueEvent(texture::destroy);

        Callback<Drawable> onDestroyListener = drawable.getOnDestroyListener();
        if (onDestroyListener != null) onDestroyListener.call(drawable);

        drawable.setOnDrawListener(null);
        drawables.remove(id);
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            Pixmap pixmap = (Pixmap) resource;
            Drawable drawable = pixmap.drawable;
            removeDrawable(drawable.id);
        }
    }

    public Visual getVisual() {
        return xServer.pixmapManager.visual;
    }
}
