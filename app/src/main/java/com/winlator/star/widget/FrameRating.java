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
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.star.R;
import com.winlator.star.core.GPUInformation;
import com.winlator.star.core.KeyValueSet;
import com.winlator.star.core.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
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

    // Fallback thermal paths (used only if zone auto-discovery finds nothing).
    private static final String[] THERMAL_PATHS = {
        "/sys/class/thermal/thermal_zone0/temp", "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone7/temp", "/sys/class/thermal/thermal_zone10/temp",
        "/sys/devices/virtual/thermal/thermal_zone0/temp", "/sys/class/hwmon/hwmon0/temp1_input",
        "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp"
    };

    // CPU temp `temp` files discovered by matching each thermal zone's `type` against CPU sensor
    // names. The hardcoded zone indices above are wrong on many SoCs (e.g. SM8350/Pocket FIT),
    // which is why CPU read 0.0°C. Discovered once and cached.
    private String[] cpuThermalPaths = null;

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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getRawX();
                lastY = event.getRawY();
                offsetX = getX();
                offsetY = getY();
                downTime = event.getEventTime();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - lastX;
                float dy = event.getRawY() - lastY;
                int slop = ViewConfiguration.get(context).getScaledTouchSlop();
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                setX(offsetX + dx);
                setY(offsetY + dy);
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

    // Scan /sys/class/thermal/thermal_zone* once and keep the `temp` files whose `type` names a
    // CPU sensor (cpu, cpuss, cpu-*-usr, mtktscpu, …). Caches the result (even if empty) so we
    // only scan once. Returns the list (possibly empty).
    private String[] discoverCpuThermalPaths() {
        if (cpuThermalPaths != null) return cpuThermalPaths;
        ArrayList<String> found = new ArrayList<>();
        try {
            File thermalDir = new File("/sys/class/thermal");
            File[] zones = thermalDir.listFiles((dir, name) -> name.startsWith("thermal_zone"));
            if (zones != null) {
                for (File zone : zones) {
                    try (BufferedReader r = new BufferedReader(new FileReader(new File(zone, "type")))) {
                        String type = r.readLine();
                        if (type == null) continue;
                        type = type.trim().toLowerCase(Locale.ENGLISH);
                        // CPU cores on Qualcomm/MediaTek expose types like cpuss, cpu-0-0-usr,
                        // mtktscpu, cpu_thermal. Exclude GPU/non-cpu zones.
                        if (type.contains("cpu") && !type.contains("gpu")) {
                            File tempFile = new File(zone, "temp");
                            if (tempFile.canRead()) found.add(tempFile.getAbsolutePath());
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        cpuThermalPaths = found.toArray(new String[0]);
        return cpuThermalPaths;
    }

    private float readTemp(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            if (line != null) {
                float temp = Float.parseFloat(line.trim());
                // Sensors report milli-°C or °C.
                if (temp > 1000) temp /= 1000.0f;
                // Reject implausible readings (offline sensors report 0 or huge values).
                if (temp > 0 && temp < 150) return temp;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private float getCPUTemperature() {
        // Prefer auto-discovered CPU zones; report the hottest core.
        float max = 0;
        for (String path : discoverCpuThermalPaths()) {
            float t = readTemp(path);
            if (t > max) max = t;
        }
        if (max > 0) return max;
        // Fallback to the legacy hardcoded list.
        for (String path : THERMAL_PATHS) {
            float t = readTemp(path);
            if (t > 0) return t;
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
