package com.winlator.star.xserver;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Diagnostic log of what the X server does with windows (logcat tag BLXDiag), on only while {@link #enabled}
 * (Windows Steam sessions): window create / map / unmap / reparent / configure / destroy, the moments a window's
 * picture is reset by a resize, name and opacity changes, and once a second a summary of every PutImage,
 * ShmPutImage, CopyArea and Present per target window and client, plus the list of windows the renderer draws
 * whenever it changes.
 */
public final class XDiag {
    private static final String TAG = "BLXDiag";
    public static volatile boolean enabled = false;

    private XDiag() {}

    // key "dst|op|client|src" -> {count, pixels, minX, minY, maxX, maxY}
    private static final Map<String, long[]> writes = new ConcurrentHashMap<>();
    private static volatile Thread flusher;
    private static volatile String lastScene = "";

    public static void event(String what, Window window, XClient client) {
        if (!enabled) return;
        Log.i(TAG, what + " " + describe(window) + " c=" + clientId(client));
    }

    public static void log(String message) {
        if (enabled) Log.i(TAG, message);
    }

    /** A write of [w]x[h] pixels at ([x],[y]) into drawable [dstId] ([op]: put, shmput, copy, present) by [client]. */
    public static void write(int dstId, String op, XClient client, int srcId, int x, int y, int w, int h) {
        if (!enabled) return;
        String key = Integer.toHexString(dstId) + "|" + op + "|" + clientId(client) + "|" + Integer.toHexString(srcId);
        long[] v = writes.get(key);
        if (v == null) {
            v = new long[]{0, 0, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
            long[] prev = writes.putIfAbsent(key, v);
            if (prev != null) v = prev;
        }
        synchronized (v) {
            v[0]++;
            v[1] += (long) Math.max(w, 0) * Math.max(h, 0);
            v[2] = Math.min(v[2], x);
            v[3] = Math.min(v[3], y);
            v[4] = Math.max(v[4], x + w);
            v[5] = Math.max(v[5], y + h);
        }
        startFlusher();
    }

    /** The renderer's window list (id, position, size, in drawing order); logged when it changes. */
    public static void scene(List<String> windows) {
        if (!enabled) return;
        String s = String.join(" ", windows);
        if (s.equals(lastScene)) return;
        lastScene = s;
        Log.i(TAG, "scene " + windows.size() + ": " + s);
    }

    public static String describe(Window w) {
        if (w == null) return "null";
        String name = "";
        String cls = "";
        boolean transparent = false;
        try {
            name = w.getName();
            cls = w.getClassName();
            transparent = w.isFullyTransparent();
        } catch (RuntimeException ignored) {
        }
        Window parent = w.getParent();
        Drawable content = w.getContent();
        return "0x" + Integer.toHexString(w.id)
                + " p=0x" + (parent != null ? Integer.toHexString(parent.id) : "-")
                + " " + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight()
                + " d=" + (content != null && content.visual != null ? content.visual.depth : 0)
                + " " + (w.attributes.isMapped() ? "mapped" : "unmapped")
                + (w.attributes.isOverrideRedirect() ? " override" : "")
                + (transparent ? " transparent" : "")
                + " name='" + name + "' class='" + cls.replace('\0', ' ').trim() + "'";
    }

    private static String clientId(XClient client) {
        return client != null && client.resourceIDBase != null ? Integer.toHexString(client.resourceIDBase) : "-";
    }

    private static void startFlusher() {
        if (flusher != null) return;
        synchronized (XDiag.class) {
            if (flusher != null) return;
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    flush();
                }
            }, "BLXDiag");
            t.setDaemon(true);
            flusher = t;
            t.start();
        }
    }

    private static void flush() {
        if (writes.isEmpty()) return;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, long[]> e : writes.entrySet()) {
            long[] v = e.getValue();
            synchronized (v) {
                if (v[0] == 0) continue;
                String[] k = e.getKey().split("\\|");
                lines.add("dst=0x" + k[0] + " " + k[1] + " c=" + k[2] + (k[3].equals("0") ? "" : " src=0x" + k[3])
                        + " n=" + v[0] + " px=" + v[1] + " box=" + v[2] + "," + v[3] + "-" + v[4] + "," + v[5]);
                v[0] = 0; v[1] = 0;
                v[2] = Long.MAX_VALUE; v[3] = Long.MAX_VALUE; v[4] = Long.MIN_VALUE; v[5] = Long.MIN_VALUE;
            }
        }
        if (!enabled || lines.isEmpty()) return;
        for (String line : lines) Log.i(TAG, "writes " + line);
    }
}
