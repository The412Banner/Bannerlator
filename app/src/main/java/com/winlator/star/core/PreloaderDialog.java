package com.winlator.star.core;

import android.app.Activity;
import android.graphics.Bitmap;

/**
 * Thin shim — delegates to PreloaderState (observed by the Compose overlay in MainActivity and
 * XServerDisplayActivity). No Dialog, no XML layout, no theme issues.
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
        PreloaderState.show(text, icon);
    }

    public void showOnUiThread(final int textResId) {
        activity.runOnUiThread(() -> show(textResId));
    }

    // Determinate step bar (1..total). Safe to call from any thread.
    public synchronized void step(int index, String label) {
        PreloaderState.step(index, label);
    }

    public void stepOnUiThread(final int index, final String label) {
        activity.runOnUiThread(() -> step(index, label));
    }

    // Switch to the indeterminate guest-boot spinner.
    public synchronized void enterGuest(String tailLabel) {
        PreloaderState.enterGuest(tailLabel);
    }

    // Not-frozen reassurance line (null clears it).
    public synchronized void hint(String text) {
        PreloaderState.hint(text);
    }

    // Surface a launch-failure card.
    public synchronized void fail(String stage, String what, String detail, String logDir, boolean loggingEnabled) {
        PreloaderState.fail(stage, what, detail, logDir, loggingEnabled);
    }

    public void failOnUiThread(final String stage, final String what, final String detail,
                               final String logDir, final boolean loggingEnabled) {
        activity.runOnUiThread(() -> fail(stage, what, detail, logDir, loggingEnabled));
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
