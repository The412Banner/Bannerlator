package com.winlator.star.xserver;

import android.util.SparseArray;

import java.nio.IntBuffer;
import java.util.ArrayList;

public class CursorManager extends XResourceManager {
    private final SparseArray<Cursor> cursors = new SparseArray<>();
    private final DrawableManager drawableManager;
    private final ArrayList<OnCursorModificationListener> onCursorModificationListeners = new ArrayList<>();

    // Only the DisplayX renderer needs cursor lifecycle events (each cursor becomes an
    // AHardwareBuffer on its own SurfaceControl layer). Defaulted no-op for everyone else.
    public interface OnCursorModificationListener {
        default void onCreateCursor(Cursor cursor) {}

        default void onFreeCursor(Cursor cursor) {}
    }

    public CursorManager(DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
    }

    public void addOnCursorModificationListener(OnCursorModificationListener listener) {
        onCursorModificationListeners.add(listener);
    }

    public void removeOnCursorModificationListener(OnCursorModificationListener listener) {
        onCursorModificationListeners.remove(listener);
    }

    private void triggerOnCreateCursor(Cursor cursor) {
        for (int i = onCursorModificationListeners.size()-1; i >= 0; i--) {
            onCursorModificationListeners.get(i).onCreateCursor(cursor);
        }
    }

    private void triggerOnFreeCursor(Cursor cursor) {
        for (int i = onCursorModificationListeners.size()-1; i >= 0; i--) {
            onCursorModificationListeners.get(i).onFreeCursor(cursor);
        }
    }

    public Cursor getCursor(int id) {
        return cursors.get(id);
    }

    public Cursor createCursor(int id, short x, short y, Pixmap sourcePixmap, Pixmap maskPixmap) {
        if (cursors.indexOfKey(id) >= 0) return null;
        Drawable drawable = drawableManager.createDrawable(0, sourcePixmap.drawable.width, sourcePixmap.drawable.height, sourcePixmap.drawable.visual);
        Cursor cursor = new Cursor(id, x, y, drawable, sourcePixmap.drawable, maskPixmap != null ? maskPixmap.drawable : null);
        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        triggerOnCreateCursor(cursor);
        return cursor;
    }

    public void freeCursor(int id) {
        Cursor cursor = cursors.get(id);
        triggerOnFreeResourceListener(cursor);
        if (cursor != null) triggerOnFreeCursor(cursor);
        cursors.remove(id);
    }

    private static boolean isEmptyMaskImage(Drawable maskImage) {
        IntBuffer maskData = maskImage.getData().asIntBuffer();
        boolean result = true;
        for (int i = 0; i < maskData.capacity(); i++) {
            if (maskData.get(i) != 0x000000) {
                result = false;
                break;
            }
        }
        return result;
    }

    public void recolorCursor(Cursor cursor, byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue) {
        if (cursor.maskImage != null) {
            boolean visible = !isEmptyMaskImage(cursor.maskImage);
            cursor.setVisible(visible);
            if (visible) cursor.cursorImage.drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, cursor.sourceImage, cursor.maskImage);
        }
    }
}