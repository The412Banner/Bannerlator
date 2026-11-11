package com.winlator.star.xserver;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.collection.ArrayMap;

import com.winlator.star.winhandler.WinHandler;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class DesktopHelper {
    private static final String TAG = "DesktopHelper";
    private static final String CONSOLE_CLASS = "conhost.exe";
    // The application window last brought to the front (mapped or tapped); a newer one cancels the
    // pending re-raises below.
    private static volatile String raisedClass = null;
    private static volatile long raisedHandle = 0;
    private static final AtomicInteger raiseSeq = new AtomicInteger();

    public static void attachTo(final XServer xServer) {
        raisedClass = null;
        raisedHandle = 0;
        setupXResources(xServer);

        xServer.pointer.addOnPointerMotionListener(new Pointer.OnPointerMotionListener() {
            @Override
            public void onPointerButtonPress(Pointer.Button button) {
                updateFocusedWindow(xServer);
            }
        });

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                if (keepFrontOverConsole(xServer, window)) return;
                setFocusedWindow(xServer, window);
            }
        });
    }

    private static void updateFocusedWindow(XServer xServer) {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            Window focusedWindow = xServer.windowManager.getFocusedWindow();
            Window child = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(), xServer.pointer.getClampedY());
            if (child == null && focusedWindow != xServer.windowManager.rootWindow) {
                xServer.windowManager.setFocus(xServer.windowManager.rootWindow, WindowManager.FocusRevertTo.NONE);
            }
            else if (child != null && child != focusedWindow) {
                setFocusedWindow(xServer, child);
            }
        }
    }

    private static void setFocusedWindow(XServer xServer, Window window) {
        if (window.isApplicationWindow()) {
            boolean parentIsRoot = window.getParent() == xServer.windowManager.rootWindow;
            xServer.windowManager.setFocus(window, parentIsRoot ? WindowManager.FocusRevertTo.POINTER_ROOT : WindowManager.FocusRevertTo.PARENT);
            xServer.getWinHandler().bringToFront(window.getClassName(), window.getHandle());
            raisedClass = window.getClassName();
            raisedHandle = window.getHandle();
            raiseSeq.incrementAndGet();
        }
    }

    /**
     * A console window (conhost.exe) mapped while another application window is in front, e.g. a console
     * program a game or a helper starts: Wine activates it by itself, and a windowed game that loses
     * activation may throttle and mute itself (DiRT Showdown: 16 fps, no sound). Keep the window that was
     * in front: the console gets no focus here, and that window gets its activation back inside Wine
     * after 0.3 s and 1.3 s, unless another window was raised meanwhile. A tap still focuses the console.
     */
    private static boolean keepFrontOverConsole(XServer xServer, Window window) {
        final String cls = raisedClass;
        final long handle = raisedHandle;
        if (cls == null || cls.equalsIgnoreCase(CONSOLE_CLASS) || !window.isApplicationWindow()
                || !window.getClassName().equalsIgnoreCase(CONSOLE_CLASS)) return false;
        final int seq = raiseSeq.incrementAndGet();
        Log.i(TAG, "console window mapped (" + CONSOLE_CLASS + " hwnd 0x" + Long.toHexString(window.getHandle())
                + ") while " + cls + " was in front: no focus for it, " + cls + " raised again in Wine");
        Handler ui = new Handler(Looper.getMainLooper());
        for (long delay : new long[]{300L, 1300L}) {
            ui.postDelayed(() -> {
                WinHandler winHandler = xServer.getWinHandler();
                if (winHandler == null) return;
                if (seq != raiseSeq.get()) {
                    Log.i(TAG, cls + " not raised again after " + delay + " ms: another window was raised meanwhile");
                    return;
                }
                winHandler.bringToFront(cls, handle);
                Log.i(TAG, cls + " hwnd 0x" + Long.toHexString(handle) + " raised again after " + delay + " ms");
            }, delay);
        }
        return true;
    }

    private static void setupXResources(XServer xServer) {
        int atom = Atom.getId("RESOURCE_MANAGER");
        int type = Atom.getId("STRING");

        ArrayMap<String, String> values = new ArrayMap<>();
        values.put("size", "20");
        values.put("theme", "dmz");
        values.put("theme_core", "true");

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append("Xcursor")
              .append('.')
              .append(entry.getKey())
              .append(':')
              .append('\t')
              .append(entry.getValue())
              .append('\n');
        }

        byte[] data = sb.toString().getBytes(XServer.LATIN1_CHARSET);
        xServer.windowManager.rootWindow.modifyProperty(atom, type, Property.Format.BYTE_ARRAY, Property.Mode.APPEND, data);
    }
}