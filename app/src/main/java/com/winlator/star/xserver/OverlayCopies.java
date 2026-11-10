package com.winlator.star.xserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps overlay pictures on top of a window that Wine fills by copying from several offscreen windows.
 *
 * With Wine's other-process window rendering, Chromium's GPU process (the Windows Steam client) draws the page and
 * each overlay child window (Big Picture's side menu, the Quick Access Menu) into its own offscreen window, and Wine
 * copies each one into the same toplevel window (CopyArea). For a fullscreen toplevel Wine copies the page over the
 * whole window without leaving out the child windows (winex11 X11DRV_client_surface_present: "exclusive fullscreen,
 * ignore everything else"), so every page frame wipes the overlay, which is only copied again when it changes.
 *
 * This remembers, per destination window, the last copy from every source window. After a copy, the remembered copies
 * from other sources that are smaller and overlap it are copied again from their source's current picture, most
 * recent last. A source that is gone or unmapped is forgotten (Chromium destroys an overlay's surface when the menu
 * closes).
 */
public final class OverlayCopies {
    private static final int MAX_PER_WINDOW = 16;

    private static final class Copy {
        final int src;
        final short srcX, srcY, dstX, dstY, width, height;

        Copy(int src, short srcX, short srcY, short dstX, short dstY, short width, short height) {
            this.src = src;
            this.srcX = srcX;
            this.srcY = srcY;
            this.dstX = dstX;
            this.dstY = dstY;
            this.width = width;
            this.height = height;
        }

        long area() {
            return (long) Math.max(width, 0) * Math.max(height, 0);
        }

        boolean overlaps(Copy o) {
            return dstX < o.dstX + o.width && o.dstX < dstX + width && dstY < o.dstY + o.height && o.dstY < dstY + height;
        }
    }

    private final Map<Integer, LinkedHashMap<Integer, Copy>> byDestination = new HashMap<>();

    /** Records the copy just made from window [srcId] into window [dstId] and lays the smaller overlays over it again. */
    public synchronized void afterCopy(XServer xServer, int srcId, int dstId, short srcX, short srcY, short dstX, short dstY,
                                       short width, short height, Drawable dst, GraphicsContext.Function function) {
        if (srcId == dstId || xServer.windowManager.getWindow(srcId) == null || xServer.windowManager.getWindow(dstId) == null) return;
        LinkedHashMap<Integer, Copy> copies = byDestination.get(dstId);
        if (copies == null) {
            copies = new LinkedHashMap<>();
            byDestination.put(dstId, copies);
        }
        Copy made = new Copy(srcId, srcX, srcY, dstX, dstY, width, height);
        copies.remove(srcId);
        copies.put(srcId, made);

        ArrayList<Copy> again = new ArrayList<>();
        Iterator<Map.Entry<Integer, Copy>> it = copies.entrySet().iterator();
        while (it.hasNext()) {
            Copy other = it.next().getValue();
            if (other.src == srcId) continue;
            Window source = xServer.windowManager.getWindow(other.src);
            Drawable picture = xServer.drawableManager.getDrawable(other.src);
            if (source == null || picture == null || !source.attributes.isMapped()) {
                it.remove();
                continue;
            }
            if (other.area() < made.area() && other.overlaps(made)) again.add(other);
        }
        for (Copy other : again) {
            Drawable picture = xServer.drawableManager.getDrawable(other.src);
            if (picture == null || picture.getData() == null || picture.visual.depth != dst.visual.depth) continue;
            dst.copyArea(other.srcX, other.srcY, other.dstX, other.dstY, other.width, other.height, picture, function);
        }
        while (copies.size() > MAX_PER_WINDOW) {
            Iterator<Integer> oldest = copies.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Forgets everything about window [windowId] (destroyed). */
    public synchronized void forget(int windowId) {
        byDestination.remove(windowId);
        for (LinkedHashMap<Integer, Copy> copies : byDestination.values()) copies.remove(windowId);
    }
}
