package com.winlator.star.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.star.R;
import com.winlator.star.core.KeyValueSet;
import com.winlator.star.core.StringUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

public class FrameRatingHorizontal extends FrameLayout implements Runnable {
    private final Context context;
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private float cpuTemp = 0;
    private int gpuLoad = 0;
    private float batteryTemp = 0;
    private float batteryWattage = 0;
    private final String totalRAM;

    private final TextView tvFPS, tvCPUTemp, tvGPULoad, tvRAM, tvBatteryTemp, tvBatteryVoltage, tvRenderer;
    
    // Drag handling
    private float lastX = 0;
    private float lastY = 0;
    private float offsetX = 0;
    private float offsetY = 0;

    public FrameRatingHorizontal(Context context) {
        this(context, null);
    }

    public FrameRatingHorizontal(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
        LayoutInflater.from(context).inflate(R.layout.hud_horizontal, this, true);

        tvFPS = findViewById(R.id.TVFPS);
        tvCPUTemp = findViewById(R.id.TVCPUTemp);
        tvGPULoad = findViewById(R.id.TVGPULoad);
        tvRAM = findViewById(R.id.TVRAM);
        tvBatteryTemp = findViewById(R.id.TVBatteryTemp);
        tvBatteryVoltage = findViewById(R.id.TVBatteryVoltage);
        tvRenderer = findViewById(R.id.TVRenderer);

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        totalRAM = StringUtils.formatBytes(mi.totalMem, false);
    }

    public void setRenderer(String renderer) {
        if (tvRenderer != null) post(() -> tvRenderer.setText(renderer));
    }

    public void reset() {
        lastTime = 0;
        frameCount = 0;
        lastFPS = 0;
        post(this);
    }

    public void applyConfig(String configString) {
        if (configString == null || configString.isEmpty()) return;
        KeyValueSet config = new KeyValueSet(configString);

        if (tvFPS != null) tvFPS.setVisibility(config.get("showFPS", "1").equals("1") ? VISIBLE : GONE);
        if (tvCPUTemp != null) tvCPUTemp.setVisibility(config.get("showCPULoad", "0").equals("1") ? VISIBLE : GONE);
        if (tvGPULoad != null) tvGPULoad.setVisibility(config.get("showGPULoad", "0").equals("1") ? VISIBLE : GONE);
        if (tvRAM != null) tvRAM.setVisibility(config.get("showRAM", "0").equals("1") ? VISIBLE : GONE);
        if (tvBatteryTemp != null) tvBatteryTemp.setVisibility(config.get("showBatteryTemp", "0").equals("1") ? VISIBLE : GONE);
        if (tvBatteryVoltage != null) tvBatteryVoltage.setVisibility(config.get("showBatteryVoltage", "0").equals("1") ? VISIBLE : GONE);

        int rendererVis = config.get("showRenderer", "0").equals("1") ? VISIBLE : GONE;
        if (tvRenderer != null) tvRenderer.setVisibility(rendererVis);

        try {
            int trans = Integer.parseInt(config.get("hudTransparency", "0"));
            this.setAlpha(1.0f - (Math.max(0, Math.min(50, trans)) / 100.0f));

            int scaleInt = Integer.parseInt(config.get("hudScale", "100"));
            float scaleFactor = Math.max(50, Math.min(150, scaleInt)) / 100.0f;
            this.setScaleX(scaleFactor);
            this.setScaleY(scaleFactor);
        } catch (Exception ignored) {}
    }

    public void update() {
        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();
        long time = SystemClock.elapsedRealtime();

        if (time >= lastTime + 500) {
            lastFPS = ((float) (frameCount * 1000) / (time - lastTime));
            cpuTemp = calculateCPULoad();
            gpuLoad = calculateGPULoad();

            Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus != null) {
                batteryTemp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f;
                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                long microAmps = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                int voltageMv = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                batteryWattage = (microAmps < 0) ? (Math.abs(microAmps) * voltageMv) / 1000000000.0f : 0.0f;
            }

            post(this);
            lastTime = time;
            frameCount = 0;
        }
        frameCount++;
    }

    @Override
    public void run() {
        if (tvFPS != null) tvFPS.setText(String.format(Locale.ENGLISH, "FPS: %.0f", lastFPS));
        if (tvCPUTemp != null) tvCPUTemp.setText(String.format(Locale.ENGLISH, "%.0f%%", cpuTemp));
        if (tvGPULoad != null) tvGPULoad.setText(gpuLoad + "%");
        if (tvRAM != null) tvRAM.setText(String.format(Locale.ENGLISH, "%.0f%%", getRAMPercentage()));
        if (tvBatteryTemp != null) tvBatteryTemp.setText(String.format(Locale.ENGLISH, "%.1f°C", batteryTemp));
        if (tvBatteryVoltage != null) tvBatteryVoltage.setText(String.format(Locale.ENGLISH, "%.0f%%", batteryWattage));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getRawX();
                lastY = event.getRawY();
                offsetX = getX();
                offsetY = getY();
                return true;
            
            case MotionEvent.ACTION_MOVE:
                float deltaX = event.getRawX() - lastX;
                float deltaY = event.getRawY() - lastY;
                setX(offsetX + deltaX);
                setY(offsetY + deltaY);
                return true;
            
            case MotionEvent.ACTION_UP:
                return true;
        }
        return super.onTouchEvent(event);
    }

    private float getRAMPercentage() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return ((mi.totalMem - mi.availMem) * 100.0f) / mi.totalMem;
    }

    private float calculateCPULoad() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line != null && line.startsWith("cpu ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 5) {
                    long user = Long.parseLong(parts[1]);
                    long system = Long.parseLong(parts[3]);
                    long idle = Long.parseLong(parts[4]);
                    long total = user + system + idle;
                    if (total > 0) {
                        return ((user + system) * 100.0f) / total;
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private int calculateGPULoad() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpubusy"))) {
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                long busy = Long.parseLong(parts[0]);
                long total = Long.parseLong(parts[1]);
                return total != 0 ? (int) ((busy * 100) / total) : 0;
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
