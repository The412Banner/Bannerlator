package com.winlator.star.core;

import android.app.Activity;
import android.graphics.Bitmap;

/**
 * Thin shim — delegates to PreloaderState (observed by the Compose overlay in MainActivity).
 * No Dialog, no XML layout, no theme issues.
 */
public class PreloaderDialog {
    private final Activity activity;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    public synchronized void show(int textResId) {
        PreloaderState.show(activity.getString(textResId));
    }

    public synchronized void show(String text, Bitmap icon) {
        PreloaderState.show(text);
    }

    public void showOnUiThread(final int textResId) {
        activity.runOnUiThread(() -> show(textResId));
    }

    public synchronized void close() {
        PreloaderState.hide();
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return PreloaderState.isVisible();
    }
}
