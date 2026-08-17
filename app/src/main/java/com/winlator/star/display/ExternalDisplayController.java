package com.winlator.star.display;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * "Version A" external-display mode — game on the TV, handheld as the controller.
 *
 * When Android exposes a secondary presentation-capable display (wired USB-C→HDMI / DeX, or a
 * system wireless-display / Cast virtual display) we lift the existing {@code XServerView} out of
 * the phone's game host and drop it into an {@link Presentation} on that display. The game keeps
 * rendering to its single {@code SurfaceView} — the surface just now lives on the TV — so no host
 * renderer changes are needed. The Presentation is NOT_FOCUSABLE / NOT_TOUCH_MODAL so input stays
 * on the phone (a physical/handheld gamepad drives the game).
 *
 * Behaviour is gated by two flags surfaced in the in-game "TV" tab:
 *   - enabled  ("Play on TV")            — master switch.
 *   - autoSwap ("Auto-switch on connect") — if on, connecting a display moves the game immediately;
 *                                           if off, we only NOTIFY and wait for the user to move it
 *                                           from the TV tab (requestMoveToExternal / moveGameToInternal).
 *
 * All user-facing notifications are raised by the {@link Listener} (the activity routes them through
 * the Compose toast in XServerDialogState) — this class shows no android.widget.Toast of its own.
 */
public class ExternalDisplayController {
    private static final String TAG = "ExtDisplaySwap";

    /** Activity-side hook: update the drawer state + raise Compose-dialog notifications. Main thread. */
    public interface Listener {
        /** A TV/external display appeared (true) or went away (false). */
        void onTvConnectedChanged(boolean connected, String displayName);
        /** The game moved onto the external display (true) or back to the handheld (false). */
        void onGameOnExternalChanged(boolean onExternal);
    }

    private final Activity activity;
    private final View gameView;          // the XServerView (owns the render SurfaceView)
    private final ViewGroup internalHost; // FLXServerDisplay on the phone
    private final Listener listener;

    private final DisplayManager displayManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private GamePresentation presentation;
    private boolean gameOnExternal = false;
    private boolean connected = false;
    private boolean enabled = true;
    private boolean autoSwap = true;
    private boolean paused = false;
    private boolean tearingDown = false;
    private int preferredModeId = 0; // 0 = system default output mode
    private int overscanPercent = 0; // 0..8 % safe-area inset applied to the game on the TV

    public ExternalDisplayController(Activity activity, View gameView, ViewGroup internalHost, Listener listener) {
        this.activity = activity;
        this.gameView = gameView;
        this.internalHost = internalHost;
        this.listener = listener;
        this.displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { update(); }

        @Override public void onDisplayRemoved(int displayId) {
            if (presentation != null && presentation.getDisplay().getDisplayId() == displayId) dismiss();
            update();
        }

        @Override public void onDisplayChanged(int displayId) { update(); }
    };

    // ---- lifecycle -------------------------------------------------------------------------------

    public void start() {
        if (displayManager == null) return;
        displayManager.registerDisplayListener(displayListener, mainHandler);
        update();
    }

    public void stop() {
        tearingDown = true;
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
        }
        dismiss();
        moveGameToInternal(); // make sure the game is back on the phone as we tear down
    }

    /** Re-evaluate on resume in case a display was (un)plugged while we were away, and clear pause. */
    public void onResume() { setPaused(false); update(); }

    /** Temporarily stop reacting to display changes (used while the wireless caster owns the game view,
     *  so the two don't fight over reparenting). The game is left exactly where it is. */
    public void pauseForCast() {
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
        }
    }

    /** Resume reacting to display changes after the caster released the game view. */
    public void resumeAfterCast() {
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, mainHandler);
        }
        update();
    }

    // ---- settings (from the TV tab) --------------------------------------------------------------

    /** "Play on TV" master switch. */
    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        update();
    }

    /** "Auto-switch on connect": on → connecting moves the game immediately; off → notify + wait. */
    public void setAutoSwap(boolean value) {
        if (autoSwap == value) return;
        autoSwap = value;
        update();
    }

    public boolean isAutoSwap() { return autoSwap; }
    public boolean isTvConnected() { return connected; }
    public boolean isGameOnExternal() { return gameOnExternal; }

    // ---- TV output display mode (resolution + refresh rate) --------------------------------------

    /** Modes the connected external display advertises (empty if none connected). */
    public Display.Mode[] getSupportedModes() {
        Display d = findPresentationDisplay();
        return d != null ? d.getSupportedModes() : new Display.Mode[0];
    }

    /** The external display's currently-active mode id (0 if none). */
    public int getActiveModeId() {
        Display d = findPresentationDisplay();
        return d != null ? d.getMode().getModeId() : 0;
    }

    /** Request a specific output mode on the TV (0 = system default). Applied to the Presentation
     *  window; also re-applied whenever the Presentation is (re)created. */
    public void setPreferredModeId(int id) {
        preferredModeId = id;
        applyPreferredMode();
    }

    private void applyPreferredMode() {
        if (presentation == null) return;
        android.view.Window w = presentation.getWindow();
        if (w == null) return;
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.preferredDisplayModeId = preferredModeId;
        w.setAttributes(lp);
    }

    /** Safe-area inset (0..8 %) for TVs that crop the picture: pads the Presentation root so the game
     *  shrinks inward off the cropped edges. Applied live and re-asserted on each (re)create. */
    public void setOverscanPercent(int pct) {
        overscanPercent = Math.max(0, Math.min(8, pct));
        applyOverscan();
    }

    private void applyOverscan() {
        if (presentation == null) return;
        FrameLayout root = presentation.getRoot();
        if (root == null) return;
        int w = root.getWidth(), h = root.getHeight();
        // Fall back to the display metrics before the root is laid out.
        if (w == 0 || h == 0) {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            presentation.getDisplay().getRealMetrics(dm);
            w = dm.widthPixels; h = dm.heightPixels;
        }
        int padX = (int) (w * (overscanPercent / 100f) / 2f);
        int padY = (int) (h * (overscanPercent / 100f) / 2f);
        root.setPadding(padX, padY, padX, padY);
    }

    /** Comma-separated HDR types the connected display reports (e.g. "HDR10, Dolby Vision"); "" if
     *  none. Read-only capability info — actually emitting HDR is a separate, larger pipeline change. */
    public String getHdrSummary() {
        Display d = findPresentationDisplay();
        if (d == null) return "";
        Display.HdrCapabilities caps = d.getHdrCapabilities();
        if (caps == null) return "";
        int[] types = caps.getSupportedHdrTypes();
        if (types == null || types.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int t : types) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(hdrTypeName(t));
        }
        return sb.toString();
    }

    private static String hdrTypeName(int t) {
        switch (t) {
            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: return "Dolby Vision";
            case Display.HdrCapabilities.HDR_TYPE_HDR10:        return "HDR10";
            case Display.HdrCapabilities.HDR_TYPE_HLG:          return "HLG";
            case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS:   return "HDR10+";
            default:                                            return "HDR";
        }
    }

    /** User tapped "Move game to TV" in the TV tab. */
    public void requestMoveToExternal() {
        Display target = findPresentationDisplay();
        if (target == null) return;
        ensurePresentation(target);
        moveGameToExternal();
    }

    /** User tapped "Bring game back to handheld" in the TV tab. */
    public void bringBackToHandheld() {
        moveGameToInternal();
    }

    // ---- pause indicator on the TV ---------------------------------------------------------------

    /**
     * Show/hide a "Paused" card on the external display. Called when the game freezes — including the
     * automatic pause when the app is backgrounded — so the TV shows a clear paused state instead of a
     * frozen frame.
     */
    public void setPaused(boolean value) {
        paused = value;
        if (presentation != null) presentation.setPausedScrim(value && gameOnExternal);
    }

    // ---- core ------------------------------------------------------------------------------------

    private Display findPresentationDisplay() {
        if (displayManager == null) return null;
        // Never target the display the activity's own window is already on. Samsung DeX exposes its
        // virtual desktop as a DISPLAY_CATEGORY_PRESENTATION display, but the app is *running* on it —
        // reparenting the game into a Presentation over the same display breaks fullscreen, the HUD,
        // and pointer input (issue #339). A real USB-C→HDMI TV is a different displayId, so genuine
        // external output is unaffected.
        int currentId = Display.DEFAULT_DISPLAY;
        Display cur = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ? activity.getDisplay()
                : activity.getWindowManager().getDefaultDisplay();
        if (cur != null) currentId = cur.getDisplayId();
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display d : displays) {
            // "HiddenDisplay" is a virtual overlay some OEMs expose; never target it.
            if (d != null
                    && !"HiddenDisplay".equals(d.getName())
                    && d.getDisplayId() != currentId) return d;
        }
        return null;
    }

    private void update() {
        Display target = findPresentationDisplay();
        setConnected(target != null, target);

        if (!enabled || target == null) {
            dismiss();
            moveGameToInternal();
            return;
        }
        if (autoSwap) {
            ensurePresentation(target);
            moveGameToExternal();
        }
        // autoSwap OFF: leave the game where it is and wait for a manual move from the TV tab. If it is
        // already on this display keep it; nothing to do here.
    }

    private void setConnected(boolean value, Display display) {
        if (connected == value) return;
        connected = value;
        final String name = display != null ? display.getName() : null;
        if (listener != null) mainHandler.post(() -> listener.onTvConnectedChanged(value, name));
    }

    private void ensurePresentation(Display target) {
        boolean needsNew = presentation == null
                || presentation.getDisplay().getDisplayId() != target.getDisplayId();
        if (!needsNew) return;
        dismiss();
        GamePresentation p = new GamePresentation(activity, target);
        try {
            p.show();
        } catch (WindowManager.InvalidDisplayException e) {
            Log.w(TAG, "presentation display went away before show()", e);
            return;
        }
        presentation = p;
        applyPreferredMode(); // re-assert the user's chosen output mode on the new Presentation window
        applyOverscan();      // re-assert the safe-area inset on the fresh root
    }

    private void moveGameToExternal() {
        if (presentation == null) return;
        FrameLayout root = presentation.getRoot();
        if (root == null) return;
        ViewGroup parent = (ViewGroup) gameView.getParent();
        if (parent == root) return;
        if (parent != null) parent.removeView(gameView);
        gameView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(gameView, 0); // behind the pause scrim
        applyOverscan();            // shrink the game inward if a safe-area inset is set
        presentation.setPausedScrim(paused);
        setGameOnExternal(true);
    }

    private void moveGameToInternal() {
        ViewGroup parent = (ViewGroup) gameView.getParent();
        if (parent == internalHost) { setGameOnExternal(false); return; }
        if (parent != null) parent.removeView(gameView);
        gameView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // keep the game behind the on-phone overlays / dialog host (they were added after it)
        internalHost.addView(gameView, 0);
        setGameOnExternal(false);
    }

    private void setGameOnExternal(boolean value) {
        if (gameOnExternal == value) return;
        gameOnExternal = value;
        if (!tearingDown && listener != null) mainHandler.post(() -> listener.onGameOnExternalChanged(value));
    }

    private void dismiss() {
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Exception ignored) {}
            presentation = null;
        }
    }

    /** Presentation whose content is a full-screen FrameLayout we reparent the game into, plus the
     *  shared Compose pause pill we can toggle on top of it (the game SurfaceView is not Z-ordered on
     *  top, so a sibling view drawn after it composites above the game frame). */
    private static class GamePresentation extends Presentation {
        private FrameLayout root;
        private View pausedView;

        GamePresentation(Activity activity, Display display) {
            super(activity, display);
        }

        FrameLayout getRoot() { return root; }

        @Override protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            root = new FrameLayout(getContext());
            root.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // Black root so the overscan / safe-area inset (padding) shows BLACK bars, not the
            // Presentation window's default light background.
            root.setBackgroundColor(0xFF000000);
            // Also paint the window itself black behind the game surface.
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
            // Don't take focus/touch from the phone — the phone stays the input device.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            setContentView(root);
        }

        void setPausedScrim(boolean show) {
            if (root == null) return;
            if (show) {
                if (pausedView == null) pausedView = buildPauseView();
                if (pausedView.getParent() == null) root.addView(pausedView); // on top (added last)
                pausedView.setVisibility(View.VISIBLE);
            } else if (pausedView != null) {
                pausedView.setVisibility(View.GONE);
            }
        }

        // A plain Android view (NOT Compose): the pause indicator must draw on the external display
        // while the host app is backgrounded, when a ComposeView tied to the stopped activity would
        // stop composing. Styled to echo the on-phone pause pill: a dark rounded "▶ Paused" over a dim
        // scrim of the frozen frame.
        private View buildPauseView() {
            Context ctx = getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            FrameLayout scrim = new FrameLayout(ctx);
            scrim.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrim.setBackgroundColor(0x99000000); // 60% black; frozen frame stays faintly visible

            TextView pill = new TextView(ctx);
            pill.setText("▶  Paused");
            pill.setTextColor(0xFFFFFFFF);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            pill.setTypeface(pill.getTypeface(), Typeface.BOLD);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEE1B1B1B);
            bg.setCornerRadius(24 * d);
            pill.setBackground(bg);
            int padH = (int) (28 * d), padV = (int) (16 * d);
            pill.setPadding(padH, padV, padH, padV);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            scrim.addView(pill, lp);
            return scrim;
        }
    }
}
