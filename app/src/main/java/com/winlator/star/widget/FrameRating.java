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
import com.winlator.star.core.GPUInformation;
import com.winlator.star.core.KeyValueSet;
import com.winlator.star.core.StringUtils;

import java.util.HashMap;
import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {
    private final Context context;
    // FPS is sourced from the shared FpsCounter (single source of truth); this view no longer counts
    // frames. lastRefreshTime only throttles the (relatively expensive) metric reads + UI post.
    private long lastRefreshTime = 0;
    private float lastFPS = 0;
    private float cpuTemp = 0;
    private int gpuLoad = 0;
    private float batteryTemp = 0;
    private float batteryWattage = 0; // Changed from int batteryVoltage
    private final String totalRAM;

    private FpsCounter fpsCounter = null;
    /** Shared authoritative FPS source; set by the host so every overlay shows the identical number. */
    public void setFpsCounter(FpsCounter c) { this.fpsCounter = c; }

    // Device-complete metric readers (GPU load / CPU temp) live in the single shared collector.
    private final HudMetrics metrics;
    private HudMetrics.TempDisplay tempDisplay = HudMetrics.TempDisplay.from(null);
    private int defaultCpuTempColor = 0xFFFFFFFF;
    private int defaultBatteryTempColor = 0xFFFFFFFF;

    private final TextView tvFPS;
    private final TextView tvRenderer;
    private final TextView tvGPU;
    private final TextView tvRAM;
    private final TextView tvCPUTemp;
    private final TextView tvGPULoad;
    private final TextView tvBatteryTemp;
    private final TextView tvBatteryVoltage; // Displays Wattage
    private final TextView tvLatency;

    private final View rowFPS;
    private final View rowLatency;
    private final View rowGPU;
    private final View rowRAM;
    private final View rowRenderer;
    private final View rowCPUTemp;
    private final View rowGPULoad;
    private final View rowBatteryTemp;
    private final View rowBatteryVoltage;

    private final HashMap<String, ?> graphicsDriverConfig;

    // Drag-to-move + tap-to-toggle-orientation handling.
    private float lastX = 0, lastY = 0, offsetX = 0, offsetY = 0;
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

    public FrameRating(Context context, HashMap<String, ?> graphicsDriverConfig) {
        this(context, graphicsDriverConfig, null);
    }

    public FrameRating(Context context, HashMap<String, ?> graphicsDriverConfig, AttributeSet attrs) {
        this(context, graphicsDriverConfig, attrs, 0);
    }

    public FrameRating(Context context, HashMap<String, ?> graphicsDriverConfig, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        this.graphicsDriverConfig = graphicsDriverConfig;
        this.metrics = new HudMetrics(context);

        LayoutInflater.from(context).inflate(R.layout.frame_rating, this, true);

        tvFPS = findViewById(R.id.TVFPS);
        tvRAM = findViewById(R.id.TVRAM);
        tvRenderer = findViewById(R.id.TVRenderer);
        tvGPU = findViewById(R.id.TVGPU);
        tvCPUTemp = findViewById(R.id.TVCPULoad);
        tvGPULoad = findViewById(R.id.TVGPULoad);
        tvBatteryTemp = findViewById(R.id.TVBatteryTemp);
        tvBatteryVoltage = findViewById(R.id.TVBatteryVoltage);
        tvLatency = findViewById(R.id.TVLatency);
        // Captured once so a temperature row can be restored when danger bands are switched off.
        // Safe to snapshot here: nothing else in this overlay recolours these views (only FPS is
        // dynamically coloured, and that's a different TextView).
        defaultCpuTempColor = tvCPUTemp != null ? tvCPUTemp.getCurrentTextColor() : 0xFFFFFFFF;
        defaultBatteryTempColor = tvBatteryTemp != null ? tvBatteryTemp.getCurrentTextColor() : 0xFFFFFFFF;

        rowFPS = findViewById(R.id.RowFPS);
        rowRAM = findViewById(R.id.RowRAM);
        rowRenderer = findViewById(R.id.RowRenderer);
        rowGPU = findViewById(R.id.RowGPU);
        rowCPUTemp = findViewById(R.id.RowCPULoad);
        rowGPULoad = findViewById(R.id.RowGPULoad);
        rowBatteryTemp = findViewById(R.id.RowBatteryTemp);
        rowBatteryVoltage = findViewById(R.id.RowBatteryVoltage);
        rowLatency = findViewById(R.id.RowLatency);

        this.totalRAM = getTotalRAM();

        lockController = new HudLockController(context, this, new HudLockController.Callbacks() {
            @Override public void onTap() { if (onTapListener != null) onTapListener.run(); }
            @Override public void onMoved(float x, float y) { if (onMovedListener != null) onMovedListener.accept(x, y); }
            @Override public void onLockChanged(boolean locked) { if (onLockChangedListener != null) onLockChangedListener.accept(locked); }
        });
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

    public void applyConfig(String configString) {
        if (configString == null || configString.isEmpty()) return;
        KeyValueSet config = new KeyValueSet(configString);

        boolean showFps = config.get("showFPS", "1").equals("1");
        if (rowFPS != null) rowFPS.setVisibility(showFps ? VISIBLE : GONE);
        if (rowLatency != null) rowLatency.setVisibility(showFps ? VISIBLE : GONE);
        if (rowRAM != null) rowRAM.setVisibility(config.get("showRAM", "0").equals("1") ? VISIBLE : GONE);
        if (rowCPUTemp != null) rowCPUTemp.setVisibility(config.get("showCPULoad", "0").equals("1") ? VISIBLE : GONE);
        if (rowGPULoad != null) rowGPULoad.setVisibility(config.get("showGPULoad", "0").equals("1") ? VISIBLE : GONE);
        if (rowBatteryTemp != null) rowBatteryTemp.setVisibility(config.get("showBatteryTemp", "0").equals("1") ? VISIBLE : GONE);
        if (rowBatteryVoltage != null) rowBatteryVoltage.setVisibility(config.get("showBatteryVoltage", "0").equals("1") ? VISIBLE : GONE);
        tempDisplay = HudMetrics.TempDisplay.from(config);
        if (lockController != null) lockController.setLocked(config.get("hudLocked", "0").equals("1"));

        int rendererVis = config.get("showRenderer", "0").equals("1") ? VISIBLE : GONE;
        if (rowRenderer != null) rowRenderer.setVisibility(rendererVis);
        if (rowGPU != null) rowGPU.setVisibility(rendererVis);

        // Apply HUD Scaling and Transparency
        try {
            // Scale
            int scaleInt = Integer.parseInt(config.get("hudScale", String.valueOf(Container.DEFAULT_HUD_SCALE)));
            float scaleFactor = Math.max(50, Math.min(150, scaleInt)) / 100.0f;
            this.setPivotX(0);
            this.setPivotY(0);
            this.setScaleX(scaleFactor);
            this.setScaleY(scaleFactor);

            // Transparency (0 = Darkest/Solid, 50 = Lightest/Transparent)
            int trans = Integer.parseInt(config.get("hudTransparency", "0"));
            float alpha = 1.0f - (Math.max(0, Math.min(50, trans)) / 100.0f);
            this.setAlpha(alpha);
        } catch (Exception e) {
            this.setScaleX(1.0f);
            this.setScaleY(1.0f);
            this.setAlpha(1.0f);
        }

        updateParentVisibility();
    }

    private void updateParentVisibility() {
        boolean anyVisible = (rowFPS != null && rowFPS.getVisibility() == VISIBLE) ||
                             (rowLatency != null && rowLatency.getVisibility() == VISIBLE) ||
                             (rowRAM != null && rowRAM.getVisibility() == VISIBLE) ||
                             (rowRenderer != null && rowRenderer.getVisibility() == VISIBLE) ||
                             (rowGPU != null && rowGPU.getVisibility() == VISIBLE) ||
                             (rowCPUTemp != null && rowCPUTemp.getVisibility() == VISIBLE) ||
                             (rowGPULoad != null && rowGPULoad.getVisibility() == VISIBLE) ||
                             (rowBatteryTemp != null && rowBatteryTemp.getVisibility() == VISIBLE) ||
                             (rowBatteryVoltage != null && rowBatteryVoltage.getVisibility() == VISIBLE);
        setVisibility(anyVisible ? VISIBLE : GONE);
    }

    private String getTotalRAM() {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return StringUtils.formatBytes(memoryInfo.totalMem);
    }

    private String getAvailableRAM() {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        return StringUtils.formatBytes(usedMem, false);
    }

    public void setRenderer(String renderer) {
        if (tvRenderer != null) tvRenderer.setText(renderer);
    }

    public void setGpuName(String gpuName) {
        if (tvGPU != null) tvGPU.setText(gpuName);
    }

    public void reset() {
        lastRefreshTime = 0;
        lastFPS = 0;
        if (tvRenderer != null) tvRenderer.setText("OpenGL");
        Object version = graphicsDriverConfig.get("version");
        if (tvGPU != null) tvGPU.setText(GPUInformation.getRenderer(version != null ? version.toString() : "", context));
    }

    /**
     * Called once per presented frame from the host tick sites. The FPS number comes from the shared
     * {@link FpsCounter}; metric reads + the UI post are self-throttled to 500 ms so sysfs is not hit
     * every present on the epoll thread.
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

            // Calculate Power Usage in Watts
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            long microAmps = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            int voltageMv = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);

            // current_now's sign is device-dependent (Xiaomi/Poco report discharge as POSITIVE), so
            // gating on the sign reads 0W on battery on those devices. Use the magnitude for the power
            // figure regardless of sign.
            batteryWattage = (Math.abs(microAmps) * voltageMv) / 1000000000.0f;
        }

        post(this);
    }

    @Override
    public void run() {
        float displayFps = lastFPS;
        if (tvFPS != null) {
            tvFPS.setText(String.format(Locale.ENGLISH, "%.1f", displayFps));
            tvFPS.setTextColor(lastFPS > 30 ? 0xFF4CAF50 :
                               lastFPS > 20 ? 0xFFFFEB3B : 0xFFF44336);
        }
        if (tvLatency != null) {
            float latencyMs = 1000.0f / Math.max(displayFps, 1.0f);
            tvLatency.setText(String.format(Locale.ENGLISH, "%.1fms", latencyMs));
        }
        if (tvRAM != null) tvRAM.setText(getAvailableRAM() + " Used / " + totalRAM);
        applyTemp(tvCPUTemp, cpuTemp, HudMetrics.TempSensor.CPU, defaultCpuTempColor);
        if (tvGPULoad != null) tvGPULoad.setText(gpuLoad + "%");

        applyTemp(tvBatteryTemp, batteryTemp, HudMetrics.TempSensor.BATTERY, defaultBatteryTempColor);
        if (tvBatteryVoltage != null) tvBatteryVoltage.setText(String.format(Locale.ENGLISH, "%.2fW", batteryWattage));
    }

    /** Writes a temperature in the user's unit and colours the row by danger band. */
    private void applyTemp(TextView tv, float celsius, HudMetrics.TempSensor sensor, int defaultColor) {
        if (tv == null) return;
        HudMetrics.Thresholds t = metrics.resolveThresholds(sensor, tempDisplay);
        tv.setText(HudMetrics.formatTemp(celsius, tempDisplay, true));
        tv.setTextColor(HudMetrics.tempColor(celsius, t, tempDisplay, defaultColor));
    }
}
