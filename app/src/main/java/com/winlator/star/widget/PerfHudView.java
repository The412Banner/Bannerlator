package com.winlator.star.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.winlator.star.core.KeyValueSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

/**
 * GameHub-style performance overlay — a second, selectable in-game HUD alongside
 * {@link FrameRating}. Pure Canvas drawing (no layout XML), modeled on GameHub 6.0.9's
 * overlay: horizontal pill or vertical list, per-field colors, a live FPS line graph,
 * and Classic/Neon/Mono skins with color-intensity / outline / scale / opacity controls.
 *
 * Metrics are pushed in via setters; the host ({@code XServerDisplayActivity}) feeds the
 * same values it collects for {@link FrameRating}. See docs/GAMEHUB_PERF_HUD_PORT_PLAN.md.
 */
public class PerfHudView extends View {

    // ---- Appearance presets (mirror GameHub's enums) ----------------------
    public enum Skin { CLASSIC, NEON, MONO }
    public enum ColorIntensity {
        SOFT(0.72f), MID(0.88f), VIVID(1.0f);
        public final float factor;
        ColorIntensity(float f) { this.factor = f; }
    }
    public enum Outline {
        OFF(0f), SOFT(1.0f), STRONG(1.4f);
        public final float widthDp;
        Outline(float w) { this.widthDp = w; }
    }

    // ---- Per-field base colors (Classic skin) -----------------------------
    private static final int C_ENGINE = Color.rgb(255, 80, 160);
    private static final int C_MODEL  = Color.rgb(200, 200, 210);
    private static final int C_GPU    = Color.rgb(100, 255, 100);
    private static final int C_CPU    = Color.rgb(255, 180, 0);
    private static final int C_RAM    = Color.rgb(80, 200, 255);
    private static final int C_PWR    = Color.rgb(170, 130, 255);
    private static final int C_TMP    = Color.rgb(255, 80, 80);
    private static final int C_FPS    = Color.rgb(255, 255, 80);
    private static final int C_CHG    = Color.rgb(0, 255, 0);
    private static final int C_VALUE  = Color.WHITE;
    private static final int C_GRAPH  = Color.rgb(0, 255, 0);
    private static final int C_OUTLINE = Color.argb(132, 0, 0, 0);
    private static final int C_SEP    = Color.argb(120, 120, 220, 255);

    // Neon / Mono overrides
    private static final int NEON_LABEL = Color.rgb(120, 255, 120);
    private static final int NEON_VALUE = Color.rgb(255, 245, 165);
    private static final int MONO_LABEL = Color.rgb(205, 205, 205);
    private static final int MONO_VALUE = Color.rgb(238, 238, 238);

    // ---- Config ------------------------------------------------------------
    private boolean showEngine = true, showGpuModel = false, showGPU = true, showCPU = true;
    private boolean showRAM = true, showPower = true, showFPS = true, showGraph = false, showTemp = true;
    private boolean vertical = false;
    private Skin skin = Skin.CLASSIC;
    private ColorIntensity intensity = ColorIntensity.MID;
    private Outline outline = Outline.SOFT;
    private float scale = 0.92f;      // [0.6, 1.4]
    private float bgOpacity = 0.8f;   // [0, 1]
    private boolean dualBattery = false;

    // ---- Live metric values ------------------------------------------------
    private float fps = 0f, frameTimeMs = 0f, cpuUsage = 0f, gpuUsage = 0f;
    private float ramPercent = 0f, powerW = 0f, tempC = 0f;
    private String gpuModel = "", engineLabel = "";
    private boolean charging = false;

    private final ArrayDeque<Float> fpsHistory = new ArrayDeque<>();
    private static final int GRAPH_SAMPLES = 50;

    // ---- Metric collection (frame-tick, mirrors FrameRating.update()) -----
    private final HudMetrics metrics;
    private long lastTime = 0;
    private int frameCount = 0;

    // ---- Paints (rebuilt when scale/skin/outline change) ------------------
    private final float density;
    private Paint fillPaint, strokePaint, graphPaint, guidePaint, bgPaint, sepPaint;
    private float textSizePx, padPx, gapPx, rowGapPx, graphWPx, graphBandPx;

    // ---- Drag-to-move + tap-to-toggle (mirrors FrameRating) ---------------
    private final Context ctx;
    private float lastX, lastY, offsetX, offsetY;
    private long downTime;
    private boolean moved;
    private Runnable onTapListener = null;
    public void setOnTapListener(Runnable r) { this.onTapListener = r; }

    /** Invoked when a drag ends, with the overlay's final (x, y). Used to persist HUD position. */
    private java.util.function.BiConsumer<Float, Float> onMovedListener = null;
    public void setOnMovedListener(java.util.function.BiConsumer<Float, Float> l) { this.onMovedListener = l; }

    public PerfHudView(Context context) { this(context, null); }
    public PerfHudView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.ctx = context;
        this.density = context.getResources().getDisplayMetrics().density;
        this.metrics = new HudMetrics(context);
        buildPaints();
    }

    // -----------------------------------------------------------------------
    private void buildPaints() {
        textSizePx  = 11f * density * scale;
        padPx       = 6f  * density * scale;
        gapPx       = 7f  * density * scale;   // horizontal gap between cells
        rowGapPx    = 3f  * density * scale;   // vertical gap between rows
        graphWPx    = 64f * density * scale;
        graphBandPx = 18f * density * scale;

        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setTypeface(mono);
        fillPaint.setTextSize(textSizePx);
        fillPaint.setLetterSpacing(0.04f);
        fillPaint.setStyle(Paint.Style.FILL);

        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setTypeface(mono);
        strokePaint.setTextSize(textSizePx);
        strokePaint.setLetterSpacing(0.04f);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(C_OUTLINE);
        strokePaint.setStrokeWidth(outline.widthDp * density * scale);

        graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        graphPaint.setStyle(Paint.Style.STROKE);
        graphPaint.setStrokeWidth(1.5f * density * scale);
        graphPaint.setStrokeCap(Paint.Cap.ROUND);
        graphPaint.setStrokeJoin(Paint.Join.ROUND);
        graphPaint.setColor(C_GRAPH);

        guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1f * density * scale);
        guidePaint.setColor(Color.argb(90, 255, 255, 255));

        sepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sepPaint.setStrokeWidth(1f * density * scale);
        sepPaint.setColor(C_SEP);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(Paint.Style.FILL);
    }

    private int applyIntensity(int color) {
        float f = intensity.factor;
        if (f >= 0.999f) return color;
        int a = Color.alpha(color);
        int r = Math.round(Color.red(color) * f);
        int g = Math.round(Color.green(color) * f);
        int b = Math.round(Color.blue(color) * f);
        return Color.argb(a, Math.min(255, r), Math.min(255, g), Math.min(255, b));
    }

    private int labelColorFor(int baseColor) {
        switch (skin) {
            case NEON: return applyIntensity(NEON_LABEL);
            case MONO: return applyIntensity(MONO_LABEL);
            default:   return applyIntensity(baseColor);
        }
    }
    private int valueColorFor() {
        switch (skin) {
            case NEON: return applyIntensity(NEON_VALUE);
            case MONO: return applyIntensity(MONO_VALUE);
            default:   return applyIntensity(C_VALUE);
        }
    }

    // ---- Cell model: label (colored) + optional white value --------------
    private static final class Cell {
        final String label, value; final int labelColor;
        Cell(String label, String value, int labelColor) {
            this.label = label; this.value = value; this.labelColor = labelColor;
        }
    }

    private ArrayList<Cell> buildCells() {
        ArrayList<Cell> cells = new ArrayList<>();
        if (showEngine && !engineLabel.isEmpty()) cells.add(new Cell(engineLabel, "", C_ENGINE));
        if (showGpuModel && !gpuModel.isEmpty())  cells.add(new Cell(gpuModel, "", C_MODEL));
        if (showGPU)   cells.add(new Cell("GPU", String.format(Locale.ENGLISH, "%.0f%%", gpuUsage), C_GPU));
        if (showCPU)   cells.add(new Cell("CPU", String.format(Locale.ENGLISH, "%.0f%%", cpuUsage), C_CPU));
        if (showRAM)   cells.add(new Cell("RAM", String.format(Locale.ENGLISH, "%.1f%%", ramPercent), C_RAM));
        if (showPower) cells.add(new Cell(charging ? "CHG" : "PWR",
                                          String.format(Locale.ENGLISH, "%.1fW", powerW),
                                          charging ? C_CHG : C_PWR));
        if (showTemp)  cells.add(new Cell("TMP", String.format(Locale.ENGLISH, "%.1f°C", tempC), C_TMP));
        if (showFPS)   cells.add(new Cell("FPS", String.format(Locale.ENGLISH, "%.0f", fps), C_FPS));
        return cells;
    }

    private float cellWidth(Cell c) {
        float w = fillPaint.measureText(c.label);
        if (!c.value.isEmpty()) w += fillPaint.measureText(" " + c.value);
        return w;
    }

    // -----------------------------------------------------------------------
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        ArrayList<Cell> cells = buildCells();
        Paint.FontMetrics fm = fillPaint.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float w, h;
        if (vertical) {
            float maxCell = 0f;
            for (Cell c : cells) maxCell = Math.max(maxCell, cellWidth(c));
            w = maxCell;
            int rows = cells.size() + (showGraph ? 1 : 0);
            h = rows * lineH + Math.max(0, rows - 1) * rowGapPx;
            if (showGraph) w = Math.max(w, graphWPx);
        } else {
            float total = 0f;
            for (int i = 0; i < cells.size(); i++) {
                total += cellWidth(cells.get(i));
                if (i < cells.size() - 1) total += gapPx;
            }
            if (showGraph) total += gapPx + graphWPx;
            w = total;
            h = lineH;
        }
        int vw = Math.round(w + padPx * 2);
        int vh = Math.round(h + padPx * 2);
        setMeasuredDimension(
            resolveSize(vw, widthMeasureSpec),
            resolveSize(vh, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        ArrayList<Cell> cells = buildCells();
        Paint.FontMetrics fm = fillPaint.getFontMetrics();
        float lineH = fm.descent - fm.ascent;

        // Background pill
        bgPaint.setColor(Color.argb(Math.round(255 * bgOpacity), 0, 0, 0));
        float radius = 6f * density * scale;
        canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius, bgPaint);

        float baseline = padPx - fm.ascent;
        if (vertical) {
            float y = baseline;
            for (Cell c : cells) {
                drawCell(canvas, padPx, y, c);
                y += lineH + rowGapPx;
            }
            if (showGraph) {
                float gy = y + graphBandPx / 2f - rowGapPx;
                drawGraph(canvas, padPx, gy);
            }
        } else {
            float x = padPx;
            for (int i = 0; i < cells.size(); i++) {
                Cell c = cells.get(i);
                x = drawCell(canvas, x, baseline, c);
                if (i < cells.size() - 1) {
                    float midY = getHeight() / 2f;
                    canvas.drawLine(x + gapPx / 2f, midY - lineH * 0.32f,
                                    x + gapPx / 2f, midY + lineH * 0.32f, sepPaint);
                    x += gapPx;
                }
            }
            if (showGraph) {
                x += gapPx;
                drawGraph(canvas, x, getHeight() / 2f);
            }
        }
    }

    /** Draws "LABEL value" at (x, baseline); returns the x cursor after the text. */
    private float drawCell(Canvas canvas, float x, float baseline, Cell c) {
        boolean stroke = outline != Outline.OFF;
        // label
        fillPaint.setColor(labelColorFor(c.labelColor));
        if (stroke) canvas.drawText(c.label, x, baseline, strokePaint);
        canvas.drawText(c.label, x, baseline, fillPaint);
        float adv = fillPaint.measureText(c.label);
        if (!c.value.isEmpty()) {
            String v = " " + c.value;
            fillPaint.setColor(valueColorFor());
            if (stroke) canvas.drawText(v, x + adv, baseline, strokePaint);
            canvas.drawText(v, x + adv, baseline, fillPaint);
            adv += fillPaint.measureText(v);
        }
        return x + adv;
    }

    /** FPS line graph: last 50 samples, peak clamped >=60, faint 30fps guide. */
    private void drawGraph(Canvas canvas, float x, float centerY) {
        if (fpsHistory.size() < 2) return;
        float band = graphBandPx;
        float bottom = centerY + band / 2f;
        float top = centerY - band / 2f;
        float peak = 60f;
        for (float v : fpsHistory) peak = Math.max(peak, v);

        float refY = bottom - clamp01(30f / peak) * band;
        canvas.drawLine(x, refY, x + graphWPx, refY, guidePaint);

        Float[] arr = fpsHistory.toArray(new Float[0]);
        int n = arr.length;
        float step = graphWPx / (n - 1);
        Path path = new Path();
        for (int i = 0; i < n; i++) {
            float px = x + i * step;
            float py = bottom - clamp01(arr[i] / peak) * band;
            if (py < top) py = top;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        canvas.drawPath(path, graphPaint);
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    // ---- Frame tick: count frames, refresh metrics every 500ms ------------
    /** Called by the host on each presented frame (same cadence as FrameRating.update()). */
    public void update() {
        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();
        long time = SystemClock.elapsedRealtime();
        frameCount++;
        if (time >= lastTime + 500) {
            fps = (float) (frameCount * 1000) / (time - lastTime);
            frameTimeMs = 1000f / Math.max(fps, 1f);
            cpuUsage = metrics.getCPUUsage();
            gpuUsage = metrics.getGPULoad();
            ramPercent = metrics.getRAMPercent();
            tempC = metrics.getTemperature();
            HudMetrics.Battery b = metrics.getBattery(dualBattery);
            powerW = b.watts;
            charging = b.charging;
            fpsHistory.addLast(fps);
            while (fpsHistory.size() > GRAPH_SAMPLES) fpsHistory.removeFirst();
            lastTime = time;
            frameCount = 0;
            // update() runs on the X-server epoll thread; requestLayout()/invalidate()
            // must touch the view on the UI thread (FrameRating does the same via post()).
            post(refreshOnUi);
        }
    }

    // Reused each refresh (~2×/sec) to relayout+redraw on the UI thread.
    private final Runnable refreshOnUi = () -> { requestLayout(); invalidate(); };
    public void setEngineLabel(String s) { this.engineLabel = s == null ? "" : s; }
    public void setGpuModel(String s) { this.gpuModel = s == null ? "" : s; }

    // ---- Config parsing ----------------------------------------------------
    public void applyConfig(String configString) {
        if (configString == null || configString.isEmpty()) return;
        KeyValueSet cfg = new KeyValueSet(configString);
        showFPS      = cfg.get("showFPS", "1").equals("1");
        showGraph    = cfg.get("showFPSGraph", "0").equals("1");
        showCPU      = cfg.get("showCPUUsage", "1").equals("1");
        showGPU      = cfg.get("showGPULoad", "1").equals("1");
        showRAM      = cfg.get("showRAM", "1").equals("1");
        showPower    = cfg.get("showPower", "1").equals("1");
        showTemp     = cfg.get("showTemp", "1").equals("1");
        showEngine   = cfg.get("showEngine", "1").equals("1");
        showGpuModel = cfg.get("showGpuModel", "0").equals("1");
        dualBattery  = cfg.get("hudDualBattery", "0").equals("1");
        vertical     = cfg.get("hudMode", "horizontal").equals("vertical");

        switch (cfg.get("hudSkin", "classic")) {
            case "neon": skin = Skin.NEON; break;
            case "mono": skin = Skin.MONO; break;
            default:     skin = Skin.CLASSIC;
        }
        switch (cfg.get("hudColor", "mid")) {
            case "soft":  intensity = ColorIntensity.SOFT; break;
            case "vivid": intensity = ColorIntensity.VIVID; break;
            default:      intensity = ColorIntensity.MID;
        }
        switch (cfg.get("hudOutline", "soft")) {
            case "off":    outline = Outline.OFF; break;
            case "strong": outline = Outline.STRONG; break;
            default:       outline = Outline.SOFT;
        }
        try {
            int sc = Integer.parseInt(cfg.get("hudScale", "92"));
            scale = Math.max(60, Math.min(140, sc)) / 100f;
        } catch (Exception e) { scale = 0.92f; }
        try {
            int op = Integer.parseInt(cfg.get("hudOpacity", "80"));
            bgOpacity = Math.max(0, Math.min(100, op)) / 100f;
        } catch (Exception e) { bgOpacity = 0.8f; }

        buildPaints();
        requestLayout();
        invalidate();
    }

    public boolean isVertical() { return vertical; }
    public void setVertical(boolean v) { this.vertical = v; requestLayout(); invalidate(); }

    // ---- Touch: tap toggles orientation, drag moves the overlay -----------
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getRawX(); lastY = event.getRawY();
                offsetX = getX(); offsetY = getY();
                downTime = event.getEventTime(); moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - lastX, dy = event.getRawY() - lastY;
                int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                setX(offsetX + dx); setY(offsetY + dy);
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved
                        && (event.getEventTime() - downTime) <= ViewConfiguration.getLongPressTimeout()
                        && onTapListener != null) {
                    onTapListener.run();
                } else if (moved && onMovedListener != null) {
                    onMovedListener.accept(getX(), getY());
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
