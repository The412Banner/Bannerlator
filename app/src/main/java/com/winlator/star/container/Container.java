package com.winlator.star.container;

import android.os.Environment;

import com.winlator.star.box64.Box64Preset;
import com.winlator.star.contentdialog.DXVKConfigDialog;
import com.winlator.star.contentdialog.WineD3DConfigDialog;
import com.winlator.star.core.DefaultVersion;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.KeyValueSet;
import com.winlator.star.core.WineInfo;
import com.winlator.star.core.WineThemeManager;
import com.winlator.star.fexcore.FEXCorePreset;
import com.winlator.star.winhandler.WinHandler;
import com.winlator.star.xenvironment.ImageFs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import android.opengl.GLES10;

public class Container {
    public enum XrControllerMapping {
        BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y, BUTTON_GRIP, BUTTON_TRIGGER,
        THUMBSTICK_UP, THUMBSTICK_DOWN, THUMBSTICK_LEFT, THUMBSTICK_RIGHT
    }
    public static final String DEFAULT_ENV_VARS = "WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 TU_DEBUG=noconform,sysmem DXVK_HUD=devinfo,fps,frametimes,gpuload,version,api";
    public static final String DEFAULT_SCREEN_SIZE = "1280x720";
    public static final String DEFAULT_GRAPHICS_DRIVER = "wrapper";
    public static final String DEFAULT_AUDIO_DRIVER = "alsa";
    public static final String DEFAULT_EMULATOR = "FEXCore";
    public static final String DEFAULT_DXWRAPPER = "dxvk+vkd3d";
    public static final String DEFAULT_DXWRAPPERCONFIG = "version=" + DefaultVersion.getVegasDefault() + ",framerate=0,async=0,asyncCache=0" + ",vkd3dVersion=2.8" + ",vkd3dLevel=12_1" + ",ddrawrapper=" + Container.DEFAULT_DDRAWRAPPER + ",csmt=3" + ",gpuName=NVIDIA GeForce GTX 480" + ",videoMemorySize=2048" + ",strict_shader_math=1" + ",OffscreenRenderingMode=fbo" + ",renderer=gl";
    public static final String DEFAULT_GRAPHICSDRIVERCONFIG =
            "vulkanVersion=1.3" + ";version=" + ";blacklistedExtensions=" + ";maxDeviceMemory=0" + ";presentMode=mailbox" + ";syncFrame=0" + ";disablePresentWait=0" + ";resourceType=auto" + ";bcnEmulation=auto" + ";bcnEmulationType=compute" + ";bcnEmulationCache=0" + ";gpuName=Device" + ";fdDevFeatures=0";
    public static final String DEFAULT_DDRAWRAPPER = "none";
    public static final String DEFAULT_FPS_COUNTER_CONFIG = "hudMode=horizontal,showFPS=1,showCPULoad=1,showGPULoad=1,showRAM=1,showRenderer=1,showBatteryTemp=1,hudScale=75";
    public static final String DEFAULT_WINCOMPONENTS = "direct3d=1,directsound=0,directmusic=0,directshow=0,directplay=0,xaudio=0,vcrun2010=1";
    public static final String FALLBACK_WINCOMPONENTS = "direct3d=1,directsound=1,directmusic=1,directshow=1,directplay=1,xaudio=1,vcrun2010=1";
    public static final String DEFAULT_DRIVES = "F:"+Environment.getExternalStorageDirectory().getAbsolutePath()+"D:"+Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    public static final byte STARTUP_SELECTION_NORMAL = 0;
    public static final byte STARTUP_SELECTION_ESSENTIAL = 1;
    public static final byte STARTUP_SELECTION_AGGRESSIVE = 2;
    public static final byte MAX_DRIVE_LETTERS = 26;
    public final int id;
    private String name;
    private String screenSize = DEFAULT_SCREEN_SIZE;
    private String envVars = DEFAULT_ENV_VARS;
    private String graphicsDriver = DEFAULT_GRAPHICS_DRIVER;
    private String graphicsDriverConfig = DEFAULT_GRAPHICSDRIVERCONFIG;
    private String dxwrapper = DEFAULT_DXWRAPPER;
    private String dxwrapperConfig = "";
    private String fpsCounterConfig = DEFAULT_FPS_COUNTER_CONFIG;
    private String wincomponents = DEFAULT_WINCOMPONENTS;
    private String audioDriver = DEFAULT_AUDIO_DRIVER;
    private String drives = DEFAULT_DRIVES;
    private String wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
    private boolean showFPS;
    private boolean rendererNative = false;
    private String rendererPresentMode = "fifo";
    private String rendererDriverId = "system";
    private int rendererFilterMode = 0;
    private boolean rendererSwapRB = false;
    private boolean fullscreenStretched;
    private byte startupSelection = STARTUP_SELECTION_ESSENTIAL;
    private String cpuList;
    private String cpuListWoW64;
    private String desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME;
    private String fexcoreVersion;
    private String fexcorePreset = FEXCorePreset.INTERMEDIATE;
    private String box64Preset = Box64Preset.COMPATIBILITY;
    private File rootDir;
    private JSONObject extraData;
    private String midiSoundFont = "";
    private int inputType = WinHandler.DEFAULT_INPUT_TYPE;
    private String lc_all = "";
    private int primaryController = 1;
    private String controllerMapping = new String(new char[XrControllerMapping.values().length]);
    private String box64Version;
    private String emulator;
    private String renderer = "vulkan";
    private boolean exclusiveXInput = true;
    private ContainerManager containerManager;



    
    public static boolean isMaliGPU() {
        try {
            String renderer = GLES10.glGetString(GLES10.GL_RENDERER);
            return renderer != null && renderer.contains("Mali");
        } catch (Exception e) {
            return false;
        }
    }

    public Container(int id) {
        this.id = id;
        this.name = "Container-"+id;
    }

    public Container(int id, ContainerManager containerManager) {
        this.id = id;
        this.name = "Container-"+id;
        this.containerManager = containerManager;
    }

    public ContainerManager getManager() {
        return containerManager;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScreenSize() {
        return screenSize;
    }

    public void setScreenSize(String screenSize) {
        this.screenSize = screenSize;
    }

    public String getEnvVars() {
        return envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars != null ? envVars : "";
    }

    public String getGraphicsDriver() {
        return graphicsDriver;
    }

    public void setGraphicsDriver(String graphicsDriver) {
        this.graphicsDriver = graphicsDriver;
    }

    public String getGraphicsDriverConfig() { return this.graphicsDriverConfig; }

    public void setGraphicsDriverConfig(String graphicsDriverConfig) { this.graphicsDriverConfig = graphicsDriverConfig; }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public String getDXWrapperConfig() {
        return dxwrapperConfig;
    }

    public void setDXWrapperConfig(String dxwrapperConfig) {
        this.dxwrapperConfig = dxwrapperConfig != null ? dxwrapperConfig : "";
    }

    public String getAudioDriver() {
        return audioDriver;
    }

    public void setAudioDriver(String audioDriver) {
        this.audioDriver = audioDriver;
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public String getDrives() {
        return drives;
    }

    public void setDrives(String drives) {
        this.drives = drives;
    }

    public String getLC_ALL() {
        return lc_all;
    }

    public void setLC_ALL(String lc_all) {
        this.lc_all = lc_all;
    }

    public int getPrimaryController() {
        return primaryController;
    }

    public void setPrimaryController(int primaryController) {
        this.primaryController = primaryController;
    }

    public byte getControllerMapping(XrControllerMapping input) {
        return (byte) controllerMapping.charAt(input.ordinal());
    }

    public void setControllerMapping(String controllerMapping) {
        this.controllerMapping = controllerMapping;
    }

    public boolean isFullscreenStretched() { return fullscreenStretched; }

    public boolean isShowFPS() {
        return showFPS;
    }

    public void setFullscreenStretched(boolean fullscreenStretched) { this.fullscreenStretched = fullscreenStretched; }

    public void setShowFPS(boolean showFPS) {
        this.showFPS = showFPS;
    }

    public String getFPSCounterConfig() {
        return fpsCounterConfig;
    }

    public void setFPSCounterConfig(String fpsCounterConfig) {
        this.fpsCounterConfig = fpsCounterConfig;
    }

    public byte getStartupSelection() {
        return startupSelection;
    }

    public void setStartupSelection(byte startupSelection) {
        this.startupSelection = startupSelection;
    }

    public String getCPUList() {
        return getCPUList(false);
    }

    public String getCPUList(boolean allowFallback) {
        return cpuList != null ? cpuList : (allowFallback ? getFallbackCPUList() : null);
    }

    public void setCPUList(String cpuList) {
        this.cpuList = cpuList != null && !cpuList.isEmpty() ? cpuList : null;
    }

    public String getCPUListWoW64() {
        return getCPUListWoW64(false);
    }

    public String getCPUListWoW64(boolean allowFallback) {
        return cpuListWoW64 != null ? cpuListWoW64 : (allowFallback ? getFallbackCPUListWoW64() : null);
    }

    public void setCPUListWoW64(String cpuListWoW64) {
        this.cpuListWoW64 = cpuListWoW64 != null && !cpuListWoW64.isEmpty() ? cpuListWoW64 : null;
    }

    public void setFEXCoreVersion(String version) {
        this.fexcoreVersion = version;
    }

    public String getFEXCoreVersion() {
        return this.fexcoreVersion;
    }

    public void setFEXCorePreset(String preset) {
        this.fexcorePreset = preset;
    }

    public String getFEXCorePreset() {
        return fexcorePreset;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public String getBox64Version() { return box64Version; }

    public void setBox64Version(String version) { this.box64Version = version; }

    public void setEmulator(String emulator) {
        this.emulator = emulator;
    }

    public String getEmulator() {
        return this.emulator;
    }

    public File getRootDir() {
        return rootDir;
    }

    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    public void setExtraData(JSONObject extraData) {
        this.extraData = extraData;
    }

    public String getExtra(String name) {
        return getExtra(name, "");
    }

    public String getExtra(String name, String fallback) {
        try {
            return extraData != null && extraData.has(name) ? extraData.getString(name) : fallback;
        }
        catch (JSONException e) {
            return fallback;
        }
    }

    public void putExtra(String name, Object value) {
        if (extraData == null) extraData = new JSONObject();
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);
        }
        catch (JSONException e) {}
    }

    // --- bionic-fg frame generation (per-container), stored in extraData ---
    // The on/off flag is set in the container settings; multiplier & flow scale
    // are tuned live from the in-game side menu (both hot-reload via conf.toml).
    public static final int FRAMEGEN_DEFAULT_MULTIPLIER = 2;
    public static final float FRAMEGEN_DEFAULT_FLOW_SCALE = 0.6f;

    public boolean isFrameGenEnabled() {
        return getExtra("frameGenEnabled", "0").equals("1");
    }

    public void setFrameGenEnabled(boolean enabled) {
        putExtra("frameGenEnabled", enabled ? "1" : "0");
    }

    // Frame-gen engine selection: "off" | "bionic" | "lsfg". bionic-fg and lsfg-vk are mutually
    // exclusive. Default migrates the legacy boolean (frameGenEnabled=1 -> bionic).
    public String getFrameGenEngine() {
        String e = getExtra("frameGenEngine", "");
        if (e.isEmpty()) return isFrameGenEnabled() ? "bionic" : "off";
        return e;
    }

    public void setFrameGenEngine(String engine) {
        if (engine == null || engine.isEmpty()) engine = "off";
        putExtra("frameGenEngine", engine);
        // Keep the legacy bionic flag in sync so the existing bionic-fg launch/drawer paths stay
        // correct (bionic loads only when the bionic engine is chosen; lsfg uses its own gate).
        setFrameGenEnabled(engine.equals("bionic"));
    }

    public boolean isLsfgEngine() {
        return getFrameGenEngine().equals("lsfg");
    }

    // Allowed values: 0 (Off, set live from the in-game menu) or 2-4. Anything else -> default.
    public int getFrameGenMultiplier() {
        try {
            int m = Integer.parseInt(getExtra("frameGenMultiplier", String.valueOf(FRAMEGEN_DEFAULT_MULTIPLIER)));
            if (m == 0) return 0;
            return (m < 2 || m > 4) ? FRAMEGEN_DEFAULT_MULTIPLIER : m;
        }
        catch (NumberFormatException e) {
            return FRAMEGEN_DEFAULT_MULTIPLIER;
        }
    }

    public void setFrameGenMultiplier(int multiplier) {
        putExtra("frameGenMultiplier", String.valueOf(multiplier));
    }

    public float getFrameGenFlowScale() {
        try {
            float f = Float.parseFloat(getExtra("frameGenFlowScale", String.valueOf(FRAMEGEN_DEFAULT_FLOW_SCALE)));
            // Mirror the layer's clamp range (layer.cpp parseConfigFile).
            return (f < 0.2f || f > 1.0f) ? FRAMEGEN_DEFAULT_FLOW_SCALE : f;
        }
        catch (NumberFormatException e) {
            return FRAMEGEN_DEFAULT_FLOW_SCALE;
        }
    }

    public void setFrameGenFlowScale(float flowScale) {
        putExtra("frameGenFlowScale", String.valueOf(flowScale));
    }

    // FPS limiter (implemented by the bionic-fg layer: paces the real/base frames, so with
    // frame gen on the on-screen rate is limit × multiplier). Tuned live from the in-game menu.
    public static final int FPS_LIMITER_DEFAULT = 60;

    public boolean isFpsLimiterEnabled() {
        return getExtra("fpsLimiterEnabled", "0").equals("1");
    }

    public void setFpsLimiterEnabled(boolean enabled) {
        putExtra("fpsLimiterEnabled", enabled ? "1" : "0");
    }

    public int getFpsLimiterValue() {
        try {
            int v = Integer.parseInt(getExtra("fpsLimiterValue", String.valueOf(FPS_LIMITER_DEFAULT)));
            return (v < 10 || v > 200) ? FPS_LIMITER_DEFAULT : v;
        }
        catch (NumberFormatException e) {
            return FPS_LIMITER_DEFAULT;
        }
    }

    public void setFpsLimiterValue(int value) {
        putExtra("fpsLimiterValue", String.valueOf(value));
    }

    // Match the Android display refresh rate to the game's FPS (votes Surface.setFrameRate). Default
    // ON: it's safe because it only ever votes a rate while the FPS limiter is actually capping;
    // otherwise it votes 0 (no-op / panel runs free). Complementary to the limiter (producer rate).
    public boolean isMatchRefreshRate() {
        return getExtra("matchRefreshRate", "1").equals("1");
    }

    public void setMatchRefreshRate(boolean enabled) {
        putExtra("matchRefreshRate", enabled ? "1" : "0");
    }

    // Manual refresh-rate lock (Hz). Used when "Auto (match FPS)" is OFF: the panel is pinned to this
    // rate regardless of the FPS cap. 0 = no manual lock (panel runs free / native).
    public int getManualRefreshRate() {
        try { return Integer.parseInt(getExtra("manualRefreshRate", "0")); }
        catch (NumberFormatException e) { return 0; }
    }

    public void setManualRefreshRate(int rate) {
        putExtra("manualRefreshRate", String.valueOf(rate));
    }

    // --- ReShade effect (vkBasalt drop-in), per-container default; the per-game shortcut can
    // override both keys via its extras (resolvedReshade* in XServerDisplayActivity). "None" / empty
    // = no effect. reshadeParams is a JSON object of {uniformName: value} overriding the .fx defaults.
    public String getReshadeEffect() {
        return getExtra("reshadeEffect", "None");
    }

    public void setReshadeEffect(String effect) {
        putExtra("reshadeEffect", (effect == null || effect.isEmpty()) ? "None" : effect);
    }

    public String getReshadeParams() {
        return getExtra("reshadeParams", "");
    }

    public void setReshadeParams(String json) {
        putExtra("reshadeParams", (json == null || json.isEmpty()) ? null : json);
    }

    // --- ReShade multi-effect loadout (Tier 1). reshadeLoadout is a JSON array of
    // {"name":..,"enabled":..} in chain order; reshadeMode is "solo" (one active) or "stack" (any
    // subset). The nested reshadeParams ({"<effect>":{uniform:value}}) hold per-effect overrides.
    // When reshadeLoadout is absent the legacy single reshadeEffect + flat reshadeParams are migrated
    // transparently (see ReshadeLoadout.parse / .paramsForEffect). "" = no loadout set.
    public String getReshadeLoadout() {
        return getExtra("reshadeLoadout", "");
    }

    public void setReshadeLoadout(String json) {
        putExtra("reshadeLoadout", (json == null || json.isEmpty()) ? null : json);
    }

    public String getReshadeMode() {
        return getExtra("reshadeMode", "solo");
    }

    public void setReshadeMode(String mode) {
        putExtra("reshadeMode", (mode == null || mode.isEmpty()) ? null : mode);
    }

    // Master (whole-chain) on/off — mirrors the in-game ReShade drawer switch (enableOnLaunch). ON is
    // the default whenever a loadout is present; only an explicit in-game OFF is stored ("0") so the
    // absence of the key keeps the historical "on when there's something to show" behaviour.
    public boolean getReshadeMasterEnabled() {
        return !getExtra("reshadeMasterEnabled", "1").equals("0");
    }

    public void setReshadeMasterEnabled(boolean enabled) {
        putExtra("reshadeMasterEnabled", enabled ? null : "0");
    }

    public String getWineVersion() {
        return wineVersion;
    }

    public void setWineVersion(String wineVersion) {
        this.wineVersion = wineVersion;
    }

    public File getConfigFile() {
        return new File(rootDir, ".container");
    }

    public File getDesktopDir() {
        return new File(rootDir, ".wine/drive_c/users/"+ImageFs.USER+"/Desktop/");
    }

    public File getStartMenuDir() {
        return new File(rootDir, ".wine/drive_c/ProgramData/Microsoft/Windows/Start Menu/");
    }

    public File getIconsDir(int size) {
        return new File(rootDir, ".local/share/icons/hicolor/"+size+"x"+size+"/apps/");
    }

    public String getDesktopTheme() {
        return desktopTheme;
    }

    public void setDesktopTheme(String desktopTheme) {
        this.desktopTheme = desktopTheme;
    }

    public String getMIDISoundFont() {
        return midiSoundFont;
    }

    public void setMidiSoundFont(String fileName) {
        midiSoundFont = fileName;
    }

    public int getInputType() {
        return inputType;
    }

    public void setInputType(int inputType) {
        this.inputType = inputType;
    }

    public boolean isExclusiveXInput() {
        return exclusiveXInput;
    }

    public void setExclusiveXInput(boolean exclusiveXInput) {
        this.exclusiveXInput = exclusiveXInput;
    }



    public String getRenderer() { return renderer; }
    public void setRenderer(String renderer) { this.renderer = renderer; }

    public boolean isRendererNative() { return rendererNative; }
    public void setRendererNative(boolean v) { this.rendererNative = v; }
    public String getRendererPresentMode() { return rendererPresentMode; }
    public void setRendererPresentMode(String v) { this.rendererPresentMode = v != null ? v : "fifo"; }
    public String getRendererDriverId() { return rendererDriverId; }
    public void setRendererDriverId(String v) { this.rendererDriverId = v != null ? v : ""; }
    public int getRendererFilterMode() { return rendererFilterMode; }
    public void setRendererFilterMode(int v) { this.rendererFilterMode = v; }
    public boolean getRendererSwapRB() { return rendererSwapRB; }
    public void setRendererSwapRB(boolean v) { this.rendererSwapRB = v; }

    public static String getDefaultVulkanConfig() {
        return "native=false;presentMode=fifo;driverId=system;filterMode=0;swapRB=false";
    }

    public Iterable<String[]> drivesIterator() {
        return drivesIterator(drives);
    }

    public static Iterable<String[]> drivesIterator(final String drives) {
        final int[] index = {drives.indexOf(":")};
        final String[] item = new String[2];
        return () -> new Iterator<String[]>() {
            @Override
            public boolean hasNext() {
                return index[0] != -1;
            }

            @Override
            public String[] next() {
                item[0] = String.valueOf(drives.charAt(index[0]-1));
                int nextIndex = drives.indexOf(":", index[0]+1);
                item[1] = drives.substring(index[0]+1, nextIndex != -1 ? nextIndex-1 : drives.length());
                index[0] = nextIndex;
                return item;
            }
        };
    }

    public void saveData() {
        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("screenSize", screenSize);
            data.put("envVars", envVars);
            data.put("cpuList", cpuList);
            data.put("cpuListWoW64", cpuListWoW64);
            data.put("graphicsDriver", graphicsDriver);
            data.put("graphicsDriverConfig", graphicsDriverConfig);
            data.put("emulator", emulator);
            data.put("dxwrapper", dxwrapper);
            if (!dxwrapperConfig.isEmpty()) data.put("dxwrapperConfig", dxwrapperConfig);
            data.put("audioDriver", audioDriver);
            data.put("wincomponents", wincomponents);
            data.put("drives", drives);
            data.put("showFPS", showFPS);
            data.put("fpsCounterConfig", fpsCounterConfig);
            data.put("fullscreenStretched", fullscreenStretched);
            data.put("inputType", inputType);
            data.put("startupSelection", startupSelection);
            data.put("box64Version", box64Version);
            data.put("fexcorePreset", fexcorePreset);
            data.put("fexcoreVersion", fexcoreVersion);
            data.put("box64Preset", box64Preset);
            data.put("desktopTheme", desktopTheme);
            if (extraData != null) data.put("extraData", extraData);
            data.put("midiSoundFont", midiSoundFont);
            data.put("lc_all", lc_all);
            data.put("primaryController", primaryController);
            data.put("controllerMapping", controllerMapping);
            data.put("exclusiveXInput", exclusiveXInput);
            data.put("renderer", renderer);
            data.put("rendererNative", rendererNative);
            data.put("rendererPresentMode", rendererPresentMode);
            if (!rendererDriverId.isEmpty()) data.put("rendererDriverId", rendererDriverId);
            if (rendererFilterMode != 0) data.put("rendererFilterMode", rendererFilterMode);
            if (rendererSwapRB) data.put("rendererSwapRB", true);
            if (!WineInfo.isMainWineVersion(wineVersion)) data.put("wineVersion", wineVersion);
            FileUtils.writeString(getConfigFile(), data.toString());
        }
        catch (JSONException e) {}
    }


    public void loadData(JSONObject data) throws JSONException {
        wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
        dxwrapperConfig = "";
        checkObsoleteOrMissingProperties(data);

        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String key = it.next();
            switch (key) {
                case "name" :
                    setName(data.getString(key));
                    break;
                case "screenSize" :
                    setScreenSize(data.getString(key));
                    break;
                case "envVars" :
                    setEnvVars(data.getString(key));
                    break;
                case "cpuList" :
                    setCPUList(data.getString(key));
                    break;
                case "cpuListWoW64" :
                    setCPUListWoW64(data.getString(key));
                    break;
                case "graphicsDriver" :
                    setGraphicsDriver(data.getString(key));
                    break;
                case "graphicsDriverConfig" :
                    setGraphicsDriverConfig(data.getString(key));
                    break;
                case "emulator":
                    setEmulator(data.getString(key));
                    break;
                case "wincomponents" :
                    setWinComponents(data.getString(key));
                    break;
                case "dxwrapper" :
                    setDXWrapper(data.getString(key));
                    break;
                case "dxwrapperConfig" :
                    setDXWrapperConfig(data.getString(key));
                    break;
                case "drives" :
                    setDrives(data.getString(key));
                    break;
                case "showFPS" :
                    setShowFPS(data.getBoolean(key));
                    break;
                case "fpsCounterConfig" :
                    setFPSCounterConfig(data.getString(key));
                    break;
                case "fullscreenStretched" :
                    setFullscreenStretched(data.getBoolean(key));
                    break;
                case "inputType" :
                    setInputType(data.getInt(key));
                    break;
                case "startupSelection" :
                    setStartupSelection((byte)data.getInt(key));
                    break;
                case "extraData" : {
                    JSONObject extraData = data.getJSONObject(key);
                    checkObsoleteOrMissingProperties(extraData);
                    setExtraData(extraData);
                    break;
                }
                case "wineVersion" :
                    setWineVersion(data.getString(key));
                    break;
                case "box64Version":
                    setBox64Version(data.getString(key));
                    break;
                case "fexcoreVersion":
                    setFEXCoreVersion(data.getString(key));
                    break;
                case "fexcorePreset":
                    setFEXCorePreset(data.getString(key));
                    break;
                case "box64Preset" :
                    setBox64Preset(data.getString(key));
                    break;
                case "audioDriver" :
                    setAudioDriver(data.getString(key));
                    break;
                case "desktopTheme" :
                    setDesktopTheme(data.getString(key));
                    break;
                case "midiSoundFont" :
                    setMidiSoundFont(data.getString(key));
                    break;
                case "lc_all" :
                    setLC_ALL(data.getString(key));
                    break;
                case "primaryController" :
                    setPrimaryController(data.getInt(key));
                    break;
                case "controllerMapping" :
                    controllerMapping = data.getString(key);
                    break;
                case "exclusiveXInput" :
                    setExclusiveXInput(data.getBoolean(key));
                    break;
                case "renderer" :
                    setRenderer(data.getString(key));
                    break;
                case "rendererNative" :
                    rendererNative = data.getBoolean(key);
                    break;
                case "rendererPresentMode" :
                    rendererPresentMode = data.getString(key);
                    break;
                case "rendererDriverId":
                    rendererDriverId = data.getString(key);
                    break;
                case "rendererFilterMode" :
                    rendererFilterMode = data.getInt(key);
                    break;
                case "rendererSwapRB":
                    rendererSwapRB = data.getBoolean(key);
                    break;
            }
        }
    }

    public static void checkObsoleteOrMissingProperties(JSONObject data) {
        try {
            if (data.has("dxcomponents")) {
                data.put("wincomponents", data.getString("dxcomponents"));
                data.remove("dxcomponents");
            }

            if (data.has("dxwrapper")) {
                String dxwrapper = data.getString("dxwrapper");
                if (dxwrapper.equals("original-wined3d")) {
                    data.put("dxwrapper", DEFAULT_DXWRAPPER);
                }
                else if (dxwrapper.startsWith("d8vk-") || dxwrapper.startsWith("dxvk-")) {
                    data.put("dxwrapper", dxwrapper);
                }

            }

            if (data.has("graphicsDriver")) {
                String graphicsDriver = data.getString("graphicsDriver");
                if (graphicsDriver.equals("turnip-zink") || graphicsDriver.equals("turnip")) {
                    data.put("graphicsDriver", "wrapper");
                }
                else if (graphicsDriver.equals("llvmpipe")) {
                    data.put("graphicsDriver", "virgl");
                }
            }

            if (data.has("envVars") && data.has("extraData")) {
                JSONObject extraData = data.getJSONObject("extraData");
                int appVersion = Integer.parseInt(extraData.optString("appVersion", "0"));
                if (appVersion < 16) {
                    EnvVars defaultEnvVars = new EnvVars(DEFAULT_ENV_VARS);
                    EnvVars envVars = new EnvVars(data.getString("envVars"));
                    for (String name : defaultEnvVars) if (!envVars.has(name)) envVars.put(name, defaultEnvVars.get(name));
                    data.put("envVars", envVars.toString());
                }
            }

            KeyValueSet wincomponents1 = new KeyValueSet(DEFAULT_WINCOMPONENTS);
            KeyValueSet wincomponents2 = new KeyValueSet(data.getString("wincomponents"));
            String result = "";

            for (String[] wincomponent1 : wincomponents1) {
                String value = wincomponent1[1];

                for (String[] wincomponent2 : wincomponents2) {
                    if (wincomponent1[0].equals(wincomponent2[0])) {
                        value = wincomponent2[1];
                        break;
                    }
                }

                result += (!result.isEmpty() ? "," : "")+wincomponent1[0]+"="+value;
            }

            data.put("wincomponents", result);
        }
        catch (JSONException e) {}
    }

    public static String getFallbackCPUList() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < numProcessors; i++) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        return cpuList;
    }

    public static String getFallbackCPUListWoW64() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = numProcessors / 2; i < numProcessors; i++) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        return cpuList;
    }

    // Check if a specific environment variable exists
    public boolean hasEnvVar(String keyValue) {
        if (envVars == null || envVars.isEmpty()) return false;
        String[] vars = envVars.split(",");
        for (String var : vars) {
            if (var.trim().equalsIgnoreCase(keyValue.trim())) {
                return true; // Found the variable
            }
        }
        return false;
    }

}









