package com.winlator.star.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.star.container.Container;

import com.winlator.star.R;
import com.winlator.star.core.KeyValueSet;
import com.winlator.star.core.StringUtils;

import java.util.Locale;

public class FrameRatingHorizontal extends FrameLayout implements Runnable {
    private final Context context;
    // FPS is sourced from the shared FpsCounter; lastRefreshTime only throttles metric reads + post.
    private long lastRefreshTime = 0;
    private float lastFPS = 0;
    private float cpuTemp = 0;
    private int gpuLoad = 0;
    private float batteryTemp = 0;
    private float batteryWattage = 0;
    private final String totalRAM;

    private FpsCounter fpsCounter = null;
    /** Shared authoritative FPS source; set by the host so every overlay shows the identical number. */
    public void setFpsCounter(FpsCounter c) { this.fpsCounter = c; }

    // Device-complete metric readers (GPU load / CPU temp / RAM) live in the single shared collector.
    private final HudMetrics metrics;
    private HudMetrics.TempDisplay tempDisplay = HudMetrics.TempDisplay.from(null);
    private int defaultCpuTempColor = 0xFFFFFFFF;
    private int defaultBatteryTempColor = 0xFFFFFFFF;

    private final TextView tvFPS, tvCPUTemp, tvGPULoad, tvRAM, tvBatteryTemp, tvBatteryVoltage, tvRenderer, tvLatency;

    // Each metric is grouped (label + value) so the whole group can be toggled together.
    private final View groupFPS, groupCPUTemp, groupGPULoad, groupRAM, groupBatteryTemp, groupBatteryVoltage, groupRenderer;
    // Leading separator for each group; hidden on the first visible group.
    private final View sepFPS, sepCPUTemp, sepGPULoad, sepRAM, sepBatteryTemp, sepBatteryVoltage, sepRenderer;

    // Drag handling
    private float lastX = 0;
    private float lastY = 0;
    private float offsetX = 0;
    private float offsetY = 0;
    // Tap-to-toggle-orientation handling.
    private long downTime = 0;
    private boolean moved = false;
    private Runnable onTapListener = null;

    /** Invoked on a single tap (not a drag); used to toggle HUD orientation in-game. */
    public void setOnTapListener(Runnable r) { this.onTapListener = r; }

    /** Invoked when a drag ends, with the overlay's final (x, y). Used to persist HUD position. */
    private java.util.function.BiConsumer<Float, Float> onMovedListener = null;
    public void setOnMovedListener(java.util.function.BiConsumer<Float, Float> l) { this.onMovedListener = l; }

    // Shared lock / tap / drag behaviour (long-press toggles the position lock).
    private HudLockController lockController;
    private java.util.function.Consumer<Boolean> onLockChangedListener = null;
    public void setOnLockChangedListener(java.util.function.Consumer<Boolean> l) { this.onLockChangedListener = l; }

    public FrameRatingHorizontal(Context context) {
        this(context, null);
    }

    public FrameRatingHorizontal(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
        this.metrics = new HudMetrics(context);
        LayoutInflater.from(context).inflate(R.layout.hud_horizontal, this, true);

        tvFPS = findViewById(R.id.TVFPS);
        tvCPUTemp = findViewById(R.id.TVCPUTemp);
        tvGPULoad = findViewById(R.id.TVGPULoad);
        tvRAM = findViewById(R.id.TVRAM);
        tvBatteryTemp = findViewById(R.id.TVBatteryTemp);
        tvBatteryVoltage = findViewById(R.id.TVBatteryVoltage);
        tvRenderer = findViewById(R.id.TVRenderer);
        tvLatency = findViewById(R.id.TVLatency);

        groupFPS = findViewById(R.id.GroupFPS);
        groupCPUTemp = findViewById(R.id.GroupCPUTemp);
        groupGPULoad = findViewById(R.id.GroupGPULoad);
        groupRAM = findViewById(R.id.GroupRAM);
        groupBatteryTemp = findViewById(R.id.GroupBatteryTemp);
        groupBatteryVoltage = findViewById(R.id.GroupBatteryVoltage);
        groupRenderer = findViewById(R.id.GroupRenderer);

        sepFPS = findViewById(R.id.SepFPS);
        sepCPUTemp = findViewById(R.id.SepCPUTemp);
        sepGPULoad = findViewById(R.id.SepGPULoad);
        sepRAM = findViewById(R.id.SepRAM);
        sepBatteryTemp = findViewById(R.id.SepBatteryTemp);
        // Snapshot for restoring a row when danger bands are switched off (only FPS recolours here).
        defaultCpuTempColor = tvCPUTemp != null ? tvCPUTemp.getCurrentTextColor() : 0xFFFFFFFF;
        defaultBatteryTempColor = tvBatteryTemp != null ? tvBatteryTemp.getCurrentTextColor() : 0xFFFFFFFF;
        sepBatteryVoltage = findViewById(R.id.SepBatteryVoltage);
        sepRenderer = findViewById(R.id.SepRenderer);

        if (tvRenderer != null) tvRenderer.setText("OpenGL");

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        totalRAM = StringUtils.formatBytes(mi.totalMem, false);

        lockController = new HudLockController(context, this, new HudLockController.Callbacks() {
            @Override public void onTap() { if (onTapListener != null) onTapListener.run(); }
            @Override public void onMoved(float x, float y) { if (onMovedListener != null) onMovedListener.accept(x, y); }
            @Override public void onLockChanged(boolean locked) { if (onLockChangedListener != null) onLockChangedListener.accept(locked); }
        });
    }

    public void setRenderer(String renderer) {
        if (tvRenderer != null) post(() -> tvRenderer.setText(renderer));
    }

    public void reset() {
        lastRefreshTime = 0;
        lastFPS = 0;
        post(this);
    }

    public void applyConfig(String configString) {
        if (configString == null || configString.isEmpty()) return;
        KeyValueSet config = new KeyValueSet(configString);

        setGroupVisible(groupRenderer, config.get("showRenderer", "0").equals("1"));
        setGroupVisible(groupCPUTemp, config.get("showCPULoad", "0").equals("1"));
        setGroupVisible(groupGPULoad, config.get("showGPULoad", "0").equals("1"));
        setGroupVisible(groupRAM, config.get("showRAM", "0").equals("1"));
        setGroupVisible(groupBatteryVoltage, config.get("showBatteryVoltage", "0").equals("1"));
        setGroupVisible(groupBatteryTemp, config.get("showBatteryTemp", "0").equals("1"));
        tempDisplay = HudMetrics.TempDisplay.from(config);
        if (lockController != null) lockController.setLocked(config.get("hudLocked", "0").equals("1"));
        setGroupVisible(groupFPS, config.get("showFPS", "1").equals("1"));

        updateSeparators();

        try {
            int trans = Integer.parseInt(config.get("hudTransparency", "0"));
            this.setAlpha(1.0f - (Math.max(0, Math.min(50, trans)) / 100.0f));

            int scaleInt = Integer.parseInt(config.get("hudScale", String.valueOf(Container.DEFAULT_HUD_SCALE)));
            float scaleFactor = Math.max(50, Math.min(150, scaleInt)) / 100.0f;
            this.setScaleX(scaleFactor);
            this.setScaleY(scaleFactor);
        } catch (Exception ignored) {}
    }

    private void setGroupVisible(View group, boolean visible) {
        if (group != null) group.setVisibility(visible ? VISIBLE : GONE);
    }

    // Hide the leading separator of the first visible group so the bar reads "A | B | C".
    private void updateSeparators() {
        View[] groups = {groupRenderer, groupCPUTemp, groupGPULoad, groupRAM, groupBatteryVoltage, groupBatteryTemp, groupFPS};
        View[] seps = {sepRenderer, sepCPUTemp, sepGPULoad, sepRAM, sepBatteryVoltage, sepBatteryTemp, sepFPS};
        boolean firstVisibleSeen = false;
        for (int i = 0; i < groups.length; i++) {
            if (groups[i] == null) continue;
            boolean groupVisible = groups[i].getVisibility() == VISIBLE;
            if (seps[i] != null) {
                seps[i].setVisibility(groupVisible && firstVisibleSeen ? VISIBLE : GONE);
            }
            if (groupVisible) firstVisibleSeen = true;
        }
    }

    /**
     * Called once per presented frame from the host tick sites. The FPS number comes from the shared
     * {@link FpsCounter}; metric reads + the UI post are self-throttled to 500 ms.
     */
    public void update() {
        long time = SystemClock.elapsedRealtime();
        if (lastRefreshTime != 0 && time < lastRefreshTime + 500) return;
        lastRefreshTime = time;

        lastFPS = fpsCounter != null ? fpsCounter.getCurrentFPS() : 0f;
        cpuTemp = metrics.getTemperature();
        gpuLoad = metrics.getGPULoad();

        Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus != null) {
            batteryTemp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f;
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            long microAmps = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            int voltageMv = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            // current_now sign is device-dependent (Xiaomi/Poco report discharge as POSITIVE) — use
            // the magnitude so the power figure isn't 0W on battery on those devices.
            batteryWattage = (Math.abs(microAmps) * voltageMv) / 1000000000.0f;
        }

        post(this);
    }

    @Override
    public void run() {
        float displayFps = lastFPS;
        if (tvFPS != null) {
            tvFPS.setText(String.format(Locale.ENGLISH, "FPS: %.0f", displayFps));
            tvFPS.setTextColor(lastFPS > 30 ? 0xFF4CAF50 :
                               lastFPS > 20 ? 0xFFFFEB3B : 0xFFF44336);
        }
        if (tvLatency != null) {
            float latencyMs = 1000.0f / Math.max(displayFps, 1.0f);
            tvLatency.setText(String.format(Locale.ENGLISH, "%.1fms", latencyMs));
        }
        applyTemp(tvCPUTemp, cpuTemp, HudMetrics.TempSensor.CPU, defaultCpuTempColor);
        if (tvGPULoad != null) tvGPULoad.setText(gpuLoad + "%");
        if (tvRAM != null) tvRAM.setText(String.format(Locale.ENGLISH, "%.0f%%", metrics.getRAMPercent()));
        applyTemp(tvBatteryTemp, batteryTemp, HudMetrics.TempSensor.BATTERY, defaultBatteryTempColor);
        if (tvBatteryVoltage != null) tvBatteryVoltage.setText(String.format(Locale.ENGLISH, "%.2fW", batteryWattage));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return lockController.onTouchEvent(event);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (lockController != null) lockController.drawBadge(canvas);
    }

    /** Writes a temperature in the user's unit and colours the row by danger band. */
    private void applyTemp(TextView tv, float celsius, HudMetrics.TempSensor sensor, int defaultColor) {
        if (tv == null) return;
        HudMetrics.Thresholds t = metrics.resolveThresholds(sensor, tempDisplay);
        tv.setText(HudMetrics.formatTemp(celsius, tempDisplay, true));
        tv.setTextColor(HudMetrics.tempColor(celsius, t, tempDisplay, defaultColor));
    }
}
