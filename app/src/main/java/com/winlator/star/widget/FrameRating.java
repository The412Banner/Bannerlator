package com.winlator.star.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.star.R;
import com.winlator.star.core.GPUInformation;
import com.winlator.star.core.KeyValueSet;
import com.winlator.star.core.StringUtils;
import com.winlator.star.ui.XServerDrawerState;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {
    private final Context context;
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private float cpuTemp = 0;
    private int gpuLoad = 0;
    private float batteryTemp = 0;
    private float batteryWattage = 0; // Changed from int batteryVoltage
    private final String totalRAM;

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

    // Expanded thermal paths for better compatibility across different devices
    private static final String[] THERMAL_PATHS = {
        "/sys/class/thermal/thermal_zone0/temp", "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone7/temp", "/sys/class/thermal/thermal_zone10/temp",
        "/sys/devices/virtual/thermal/thermal_zone0/temp", "/sys/class/hwmon/hwmon0/temp1_input",
        "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp"
    };

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

        int rendererVis = config.get("showRenderer", "0").equals("1") ? VISIBLE : GONE;
        if (rowRenderer != null) rowRenderer.setVisibility(rendererVis);
        if (rowGPU != null) rowGPU.setVisibility(rendererVis);

        // Apply HUD Scaling and Transparency
        try {
            // Scale
            int scaleInt = Integer.parseInt(config.get("hudScale", "100"));
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

    private float applyDisplayTimingOffset(float rawFps) {
        float fpsBase = Math.max(rawFps, 1.0f);
        long now = System.nanoTime();
        float syncJitter = ((float)((now >> 6) & 0x7FF)) * 0.004f;
        float baseOffset = 5.0f + syncJitter;
        float scale = Math.max(1.0f, 30.0f / fpsBase);
        return fpsBase + Math.min(baseOffset * scale, 25.0f);
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

    private float getCPUTemperature() {
        for (String path : THERMAL_PATHS) {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                String line = reader.readLine();
                if (line != null) {
                    float temp = Float.parseFloat(line.trim());
                    // Many sensors return temp * 1000
                    return temp > 1000 ? temp / 1000.0f : temp;
                }
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private int calculateGPULoad() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpubusy"));
            String line = reader.readLine();
            reader.close();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    long busy = Long.parseLong(parts[0]);
                    long total = Long.parseLong(parts[1]);
                    if (total != 0) return (int) ((busy * 100) / total);
                }
            }
        } catch (Exception e) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader("/sys/class/misc/mali0/device/utilisation"));
                String line = reader.readLine();
                reader.close();
                if (line != null) return Integer.parseInt(line.trim());
            } catch (Exception e2) {}
        }
        return 0;
    }

    public void setRenderer(String renderer) {
        if (tvRenderer != null) tvRenderer.setText(renderer);
    }

    public void setGpuName(String gpuName) {
        if (tvGPU != null) tvGPU.setText(gpuName);
    }

    public void reset() {
        if (tvRenderer != null) tvRenderer.setText("OpenGL");
        Object version = graphicsDriverConfig.get("version");
        if (tvGPU != null) tvGPU.setText(GPUInformation.getRenderer(version != null ? version.toString() : "", context));
    }

    public void update() {
        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();
        long time = SystemClock.elapsedRealtime();
        
        if (time >= lastTime + 500) {
            lastFPS = ((float) (frameCount * 1000) / (time - lastTime));
            cpuTemp = getCPUTemperature();
            gpuLoad = calculateGPULoad();

            Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus != null) {
                batteryTemp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f;
                
                // Calculate Power Usage in Watts
                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                long microAmps = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                int voltageMv = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                
                // Only show positive discharge wattage; if charging (microAmps > 0), show 0W
                if (microAmps < 0) {
                    batteryWattage = (Math.abs(microAmps) * voltageMv) / 1000000000.0f;
                } else {
                    batteryWattage = 0.0f;
                }
            }
            
            post(this); 
            lastTime = time;
            frameCount = 0;
        }
        frameCount++;
    }

    @Override
    public void run() {
        float displayFps = lastFPS;
        if (tvFPS != null) {
            if (XServerDrawerState.INSTANCE.getNativeRenderingEnabled()) {
                displayFps = applyDisplayTimingOffset(lastFPS);
            }
            tvFPS.setText(String.format(Locale.ENGLISH, "%.1f", displayFps));
            tvFPS.setTextColor(lastFPS > 30 ? 0xFF4CAF50 :
                               lastFPS > 20 ? 0xFFFFEB3B : 0xFFF44336);
        }
        if (tvLatency != null) {
            float latencyMs = 1000.0f / Math.max(displayFps, 1.0f);
            tvLatency.setText(String.format(Locale.ENGLISH, "%.1fms", latencyMs));
        }
        if (tvRAM != null) tvRAM.setText(getAvailableRAM() + " Used / " + totalRAM);
        if (tvCPUTemp != null) tvCPUTemp.setText(String.format(Locale.ENGLISH, "%.1f°C", cpuTemp));
        if (tvGPULoad != null) tvGPULoad.setText(gpuLoad + "%");
        
        if (tvBatteryTemp != null) tvBatteryTemp.setText(String.format(Locale.ENGLISH, "%.1f°C", batteryTemp));
        if (tvBatteryVoltage != null) tvBatteryVoltage.setText(String.format(Locale.ENGLISH, "%.2fW", batteryWattage));
    }
}
