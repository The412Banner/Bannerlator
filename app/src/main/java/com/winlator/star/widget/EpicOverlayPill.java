package com.winlator.star.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * A small in-game draggable "Epic pill" shown only for Epic shortcuts whose Friends-Overlay
 * toggle is on. A tap synthesises the EOS overlay hotkey (Shift+F3) into the guest so a
 * touch-only user can summon the overlay the game's own EOS SDK renders; a drag moves it and,
 * on release, it magnetises to the nearest left/right screen edge (net-new edge-snap — the OSC
 * grid-snap is a different beast). The dragged position is persisted per game by the host.
 *
 * <p>Drag/tap/long-press mechanics are delegated to {@link HudLockController} (same engine the
 * perf HUDs use). Long-press locks the pill in place (padlock badge), matching the HUD idiom;
 * while locked a tap re-flashes the badge instead of injecting.
 *
 * <p>The pill does NOT gate the overlay: the EOS SDK owns the real Shift+F3 hotkey, so a physical
 * keyboard summons the overlay independently — the pill only synthesises the same keystrokes.
 *
 * <p>Main-thread only (touch + draw), like the HUD overlays it sits beside.
 */
public class EpicOverlayPill extends View {

    /** Tap callback → inject the overlay hotkey. */
    public interface OnTapListener { void onTap(); }
    /** Drag/snap released → persist the final (x, y). */
    public interface OnMovedListener { void onMoved(float x, float y); }

    private static final long SNAP_MS = 160L;

    private final float density;
    private final HudLockController lockController;

    private OnTapListener onTapListener;
    private OnMovedListener onMovedListener;

    // Epic-styled mark (brand-neutral: dark rounded pill + a bold "E" glyph; no trademark art).
    private static final int PILL_BG     = 0xF21B1B1F; // near-black, slightly translucent
    private static final int PILL_STROKE = 0x33FFFFFF;
    private final Paint bgPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rr = new RectF();

    public EpicOverlayPill(Context context) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;

        int side = Math.round(44 * density);
        setMinimumWidth(side);
        setMinimumHeight(side);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(PILL_BG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.2f * density);
        strokePaint.setColor(PILL_STROKE);
        glyphPaint.setColor(Color.WHITE);
        glyphPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setTextSize(20 * density);

        lockController = new HudLockController(context, this, new HudLockController.Callbacks() {
            @Override public void onTap() {
                if (onTapListener != null) onTapListener.onTap();
            }
            @Override public void onMoved(float x, float y) {
                snapToNearestEdge();   // ignore the raw release pos; magnetise + persist the snapped one
            }
            @Override public void onLockChanged(boolean locked) {
                // No persisted lock chip for the pill; the HUD badge flash is feedback enough.
            }
        });
    }

    public void setOnTapListener(OnTapListener l)   { this.onTapListener = l; }
    public void setOnMovedListener(OnMovedListener l) { this.onMovedListener = l; }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int side = Math.round(44 * density);
        setMeasuredDimension(resolveSize(side, widthMeasureSpec), resolveSize(side, heightMeasureSpec));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return lockController.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = 2f * density;
        rr.set(pad, pad, getWidth() - pad, getHeight() - pad);
        float radius = 10f * density;
        canvas.drawRoundRect(rr, radius, radius, bgPaint);
        canvas.drawRoundRect(rr, radius, radius, strokePaint);

        // Centred bold "E" mark.
        Paint.FontMetrics fm = glyphPaint.getFontMetrics();
        float cy = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("E", getWidth() / 2f, cy, glyphPaint);

        // Padlock badge fade (drawn by the shared controller) on lock/unlock.
        lockController.drawBadge(canvas);
    }

    /**
     * Magnetise the pill to the nearest left/right edge of its parent on drag release, keeping the
     * dragged Y (clamped into bounds), then persist the settled position. Net-new edge-snap magnet.
     */
    private void snapToNearestEdge() {
        View parent = (getParent() instanceof View) ? (View) getParent() : null;
        if (parent == null) {
            if (onMovedListener != null) onMovedListener.onMoved(getX(), getY());
            return;
        }
        float margin = 8f * density;
        float leftX  = margin;
        float rightX = Math.max(margin, parent.getWidth() - getWidth() - margin);
        float center = getX() + getWidth() / 2f;
        float targetX = (center < parent.getWidth() / 2f) ? leftX : rightX;

        float maxY = Math.max(0f, parent.getHeight() - getHeight());
        float targetY = Math.max(0f, Math.min(getY(), maxY));

        animate().x(targetX).y(targetY).setDuration(SNAP_MS).withEndAction(() -> {
            if (onMovedListener != null) onMovedListener.onMoved(getX(), getY());
        }).start();
    }
}
