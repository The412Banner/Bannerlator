package com.winlator.star;

import static com.winlator.star.core.AppUtils.showToast;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import androidx.compose.ui.platform.ComposeView;
import com.winlator.star.ui.XServerDrawerKt;
import com.winlator.star.ui.XServerDrawerState;
import com.winlator.star.ui.XServerDialogHostKt;
import com.winlator.star.ui.XServerDialogState;
import com.winlator.star.container.Container;
import com.winlator.star.container.ContainerManager;
import com.winlator.star.container.Shortcut;
import com.winlator.star.contentdialog.ContentDialog;
import com.winlator.star.contentdialog.DXVKConfigDialog;
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.star.contentdialog.WineD3DConfigDialog;
import com.winlator.star.contents.ContentProfile;
import com.winlator.star.contents.ContentsManager;
import com.winlator.star.contents.AdrenotoolsManager;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.DefaultVersion;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.GPUInformation;
import com.winlator.star.core.KeyValueSet;
import com.winlator.star.core.OnExtractFileListener;
import com.winlator.star.core.PreloaderDialog;
import com.winlator.star.core.ProcessHelper;
import com.winlator.star.core.StringUtils;
import com.winlator.star.core.TarCompressorUtils;
import com.winlator.star.core.WineInfo;
import com.winlator.star.core.WineRegistryEditor;
import com.winlator.star.core.WineRequestHandler;
import com.winlator.star.core.WineStartMenuCreator;
import com.winlator.star.core.Callback;
import com.winlator.star.core.WineThemeManager;
import com.winlator.star.core.WineUtils;
import com.winlator.star.inputcontrols.ControlsProfile;
import com.winlator.star.inputcontrols.ExternalController;
import com.winlator.star.inputcontrols.InputControlsManager;
import com.winlator.star.inputcontrols.VisualStyle;
import com.winlator.star.math.Mathf;
import com.winlator.star.math.XForm;
import com.winlator.star.midi.MidiHandler;
import com.winlator.star.midi.MidiManager;
import com.winlator.star.renderer.EffectComposer;
import com.winlator.star.renderer.GLRenderer;
import com.winlator.star.renderer.HostRenderer;
import com.winlator.star.renderer.effects.CRTEffect;
import com.winlator.star.renderer.effects.ColorEffect;
import com.winlator.star.renderer.effects.FXAAEffect;
import com.winlator.star.renderer.effects.NTSCCombinedEffect;
import com.winlator.star.renderer.effects.ToonEffect;
import com.winlator.star.renderer.effects.HDREffect;
import com.winlator.star.widget.FrameRating;
import com.winlator.star.widget.FrameRatingHorizontal;
import com.winlator.star.widget.InputControlsView;
import com.winlator.star.widget.LogView;
import com.winlator.star.widget.PerfHudView;
import com.winlator.star.widget.TouchpadView;
import com.winlator.star.widget.XServerView;
import com.winlator.star.winhandler.MouseEventFlags;
import com.winlator.star.winhandler.OnGetProcessInfoListener;
import com.winlator.star.winhandler.ProcessInfo;
import com.winlator.star.winhandler.WinHandler;
import com.winlator.star.core.CPUStatus;
import com.winlator.star.xserver.XLock;
import com.winlator.star.xconnector.UnixSocketConfig;
import com.winlator.star.xenvironment.ImageFs;
import com.winlator.star.xenvironment.XEnvironment;
import com.winlator.star.xenvironment.components.ALSAServerComponent;
import com.winlator.star.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.star.xenvironment.components.PulseAudioComponent;
import com.winlator.star.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.star.xenvironment.components.XServerComponent;
import com.winlator.star.xserver.Pointer;
import com.winlator.star.xserver.Property;
import com.winlator.star.xserver.ScreenInfo;
import com.winlator.star.xserver.Window;
import com.winlator.star.xserver.WindowManager;
import com.winlator.star.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;

public class XServerDisplayActivity extends AppCompatActivity {
    public static String NOTIFICATION_CHANNEL_ID = "Winlator";
    public static int NOTIFICATION_ID = -1;
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    protected Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private ImageFs imageFs;
    private FrameRating frameRating = null;
    private FrameRatingHorizontal frameRatingHorizontal = null;
    private PerfHudView perfHud = null;          // GameHub-style HUD (used when hudStyle=gamehub instead of the two above)
    private boolean fpsHudHorizontal = false;   // active FPS-overlay orientation (tap to toggle in-game)
    // Async-arriving HUD labels are cached so a HUD built live (style swapped mid-game) is populated too.
    private String hudRendererLabel = null;     // full "Vulkan | DXVK" label for classic FrameRating.setRenderer
    private String hudEngineShort = null;       // short API/dx name for PerfHudView.setEngineLabel
    private String hudGpuName = null;           // GPU model string from _MESA_DRV_GPU_NAME
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    private HashMap<String, String> graphicsDriverConfig;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String emulator = Container.DEFAULT_EMULATOR;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private KeyValueSet dxwrapperConfig;
    private String startupSelection;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private boolean firstTimeBoot = false;
    private SharedPreferences preferences;
    private Callback<String> wineDebugLogCallback;
    private java.io.PrintWriter wineDebugWriter;
    private OnExtractFileListener onExtractFileListener;
    private WinHandler winHandler;
    private WineRequestHandler wineRequestHandler;
    private float globalCursorSpeed = 1.0f;
    private short taskAffinityMask = 0;
    private short taskAffinityMaskWoW64 = 0;
    private int frameRatingWindowId = -1;
    private boolean cursorLock; // Flag to track if pointer capture was requested
    private final float[] xform = XForm.getInstance();
    private ContentsManager contentsManager;
    private MidiHandler midiHandler;
    private String midiSoundFont = "";
    private String lc_all = "";
    private String vkbasaltConfig = "";
    // Supersampling ("Render scale"): true when the launch resolution was multiplied above the
    // display res, so the Vulkan compositor should run a quality Lanczos downscale. Resolved in
    // onCreate (from the container/shortcut "renderScale" extra) and consumed in setupUI.
    private boolean hqDownscale = false;
    PreloaderDialog preloaderDialog = null;
    private Runnable configChangedCallback = null;
    private boolean isPaused = false;
    private boolean isRelativeMouseMovement = false;
    private boolean isMouseDisabled = false;
    private boolean pointerCaptureRequested = false;

    // Inside the XServerDisplayActivity class
    private SensorManager sensorManager;

    // Playtime stats tracking
    private long startTime;
    private SharedPreferences playtimePrefs;
    private String shortcutName;
    private Handler handler;
    private Runnable savePlaytimeRunnable;
    private static final long SAVE_INTERVAL_MS = 1000;

    private Handler  timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable hideControlsRunnable;

    // Task Manager refresh runs on a plain main-thread Handler (render-independent), not a Compose
    // LaunchedEffect timer. The Compose effect clock stalls on the Vulkan host-render path, so the
    // old in-drawer delay() loop never fired there (Task Manager stayed empty). This mirrors the
    // java.util.Timer the pre-Compose TaskManagerDialog used and works on both renderers.
    private final Handler tmPollHandler = new Handler(Looper.getMainLooper());
    private final Runnable tmPollRunnable = new Runnable() {
        @Override
        public void run() {
            XServerDialogState ds = XServerDialogState.INSTANCE;
            if (winHandler != null) winHandler.listProcesses();
            updateTmCpuMemory(ds);
            tmPollHandler.postDelayed(this, 1000);
        }
    };

    // ---- Component installer auto-exit (Phase 3b) ----
    // When launched to run a component installer (.NET/vcredist), watch the guest process list and
    // auto-close the session once the installer (and any msiexec it spawns) has exited, so the user
    // doesn't have to manually leave the container after each installer.
    private String componentInstallerExe;
    private boolean installerProcSeen = false;
    private int installerGoneTicks = 0;
    private final java.util.ArrayList<String> installerTickNames = new java.util.ArrayList<>();
    private final Handler installerWatchHandler = new Handler(Looper.getMainLooper());
    private final OnGetProcessInfoListener installerProcListener = new OnGetProcessInfoListener() {
        @Override
        public void onGetProcessInfo(int index, int count, ProcessInfo info) {
            if (index == 0) installerTickNames.clear();
            if (info != null && info.name != null) installerTickNames.add(info.name.toLowerCase());
            if (count == 0 || index == count - 1) evaluateInstallerTick();
        }
    };
    private final Runnable installerWatchRunnable = new Runnable() {
        @Override
        public void run() {
            if (winHandler != null) {
                // Re-assert our listener (the Task Manager may have taken it) then request the list.
                winHandler.setOnGetProcessInfoListener(installerProcListener);
                winHandler.listProcesses();
            }
            installerWatchHandler.postDelayed(this, 2000);
        }
    };

    // ---- Auto-close session on game exit (per-container / per-shortcut) ----
    // For launches from a game shortcut, watch the guest process list and close the session once the
    // game's own executable has been seen and then disappears — so the user isn't left sitting on the
    // empty Wine desktop (black screen) after quitting the game. Mirrors the installer auto-exit above.
    // Gated to shortcut launches only and to the per-game/-container "autoCloseOnExit" setting.
    private boolean autoCloseOnExitEnabled = false;
    private String autoCloseExeName;          // lowercased basename of the launched game exe
    private boolean gameProcSeen = false;
    private int gameGoneTicks = 0;
    private final java.util.ArrayList<String> gameTickNames = new java.util.ArrayList<>();
    private final Handler gameExitWatchHandler = new Handler(Looper.getMainLooper());
    private final OnGetProcessInfoListener gameExitProcListener = new OnGetProcessInfoListener() {
        @Override
        public void onGetProcessInfo(int index, int count, ProcessInfo info) {
            if (index == 0) gameTickNames.clear();
            if (info != null && info.name != null) gameTickNames.add(info.name.toLowerCase());
            if (count == 0 || index == count - 1) evaluateGameExitTick();
        }
    };
    private final Runnable gameExitWatchRunnable = new Runnable() {
        @Override
        public void run() {
            if (winHandler != null) {
                // Re-assert our listener (Task Manager polling may have taken it) then request the list.
                winHandler.setOnGetProcessInfoListener(gameExitProcListener);
                winHandler.listProcesses();
            }
            gameExitWatchHandler.postDelayed(this, 2000);
        }
    };

    // Live detection of which Direct3D API the running game actually uses, so the FPS-counter
    // overlay can show VKD3D for D3D12 titles instead of always printing the D3D9/10/11 wrapper
    // name (DXVK/VEGAS). Both wrappers are always present in the prefix, so the only reliable tell
    // is which d3d module the game has mapped in — we scan /proc/<pid>/maps (the app's own wine
    // processes are the only ones visible under our uid).
    private Thread dxApiThread;

    /** "VKD3D" if a D3D12 game module is loaded, [fallback] if a D3D9/10/11 one is, else null. */
    private String detectActiveDxApi(String fallback) {
        java.io.File[] pids = new java.io.File("/proc").listFiles();
        if (pids == null) return null;
        boolean d3d11 = false;
        for (java.io.File p : pids) {
            if (!p.isDirectory() || !android.text.TextUtils.isDigitsOnly(p.getName())) continue;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(new java.io.File(p, "maps")))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.indexOf(".dll") < 0) continue;
                    if (line.indexOf("d3d12core.dll") >= 0 || line.indexOf("d3d12.dll") >= 0)
                        return "VKD3D";   // D3D12 wins if both are present
                    if (line.indexOf("d3d11.dll") >= 0 || line.indexOf("d3d10.dll") >= 0
                            || line.indexOf("d3d9.dll") >= 0) d3d11 = true;
                }
            } catch (Exception ignore) {}
        }
        return d3d11 ? fallback : null;
    }

    /** Poll for the active D3D API just after launch and update the overlay label once detected. */
    private void startDxApiDetection(final String prefix, final String fallback) {
        stopDxApiDetection();
        dxApiThread = new Thread(() -> {
            for (int i = 0; i < 40 && !Thread.currentThread().isInterrupted(); i++) {
                String api = detectActiveDxApi(fallback);
                if (api != null) {
                    final String label = prefix + api;
                    runOnUiThread(() -> {
                        hudRendererLabel = label;
                        hudEngineShort = api;
                        if (frameRatingHorizontal != null) frameRatingHorizontal.setRenderer(label);
                        if (frameRating != null) frameRating.setRenderer(label);
                        if (perfHud != null) perfHud.setEngineLabel(api);
                    });
                    return;
                }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "dx-api-detect");
        dxApiThread.start();
    }

    private void stopDxApiDetection() {
        if (dxApiThread != null) { dxApiThread.interrupt(); dxApiThread = null; }
    }

    private boolean isDarkMode;

    private String screenEffectProfile;

    private GuestProgramLauncherComponent guestProgramLauncherComponent;
    private EnvVars overrideEnvVars;

    private void createNotifcationChannel() {
        String name = "Winlator";
        String description = "Winlator XServer Messages";
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (configChangedCallback != null) {
            configChangedCallback.run();
            configChangedCallback = null;
        }
    }
    
    private float pickHighestRefreshRate() {
    	android.view.Display display = getWindowManager().getDefaultDisplay();
    	android.view.Display.Mode[] modes = display.getSupportedModes();
    	
    	float maxRefresh = 0f;
    	
    	for (android.view.Display.Mode mode : modes) {
			if (mode.getRefreshRate() > maxRefresh)
    	    	maxRefresh = mode.getRefreshRate();
    	}

    	Log.d("XServerDisplayActivity", "Picking refresh rate " + maxRefresh);

    	return maxRefresh;
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
               
        android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.preferredRefreshRate = pickHighestRefreshRate();
        getWindow().setAttributes(params);
        
        setContentView(R.layout.xserver_display_activity);
        com.winlator.star.ui.PreloaderOverlayHelper.attach(this);

        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        cursorLock = preferences.getBoolean("cursor_lock", false);

        // Check for Dark Mode
        isDarkMode = preferences.getBoolean("dark_mode", false);

        boolean isOpenWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean isShareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);



        // Check if xinputDisabled extra is passed
        boolean xinputDisabledFromShortcut = false;




        // Initialize SensorManager



        // Record the start time
        startTime = System.currentTimeMillis();

        // Initialize handler for periodic saving
        handler = new Handler(Looper.getMainLooper());
        savePlaytimeRunnable = new Runnable() {
            @Override
            public void run() {
                savePlaytimeData();
                handler.postDelayed(this, SAVE_INTERVAL_MS);
            }
        };
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);


        // Handler and Runnable to manage timeout for hiding controls

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", true);

        hideControlsRunnable = () -> {
            if (isTimeoutEnabled) {
                inputControlsView.setVisibility(View.GONE);
                Log.d("XServerDisplayActivity", "Touchscreen controls hidden after timeout.");
            }
        };


        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();

        drawerLayout = findViewById(R.id.DrawerLayout);

        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(@NonNull View drawerView) {

            }
            @Override public void onDrawerClosed(@NonNull View drawerView) {
                // If the user left Relative Mouse enabled, recapture.
                if (isRelativeMouseMovement && !pointerCaptureRequested) {
                    drawerLayout.postDelayed(() -> ensurePointerCapture("drawer-closed"), 2000);
                }
            }
        });
        
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        // Wire Compose in-game drawer
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getBoolean("enable_box64_logs", false);
        boolean allowMagnifier = !XrActivity.isEnabled(this);
        XServerDrawerState state = XServerDrawerState.INSTANCE;
        state.reset();
        state.setShowLogs(enableLogs);
        state.setShowMagnifier(allowMagnifier);
        state.setIsPaused(isPaused);
        state.setIsRelativeMouseMovement(isRelativeMouseMovement);
        state.setIsMouseDisabled(isMouseDisabled);
        state.onClose                  = () -> runOnUiThread(() -> drawerLayout.closeDrawers());
        state.onKeyboard               = () -> AppUtils.showKeyboard(this);
        state.onInputControls          = () -> showInputControlsDialog();
        state.onScreenEffects          = () -> showScreenEffectsDialog();
        state.onGraphicEngine          = () -> { XServerDrawerState.INSTANCE.selectTab(com.winlator.star.ui.TabType.GRAPHICS); runOnUiThread(() -> drawerLayout.openDrawer(GravityCompat.START)); };
        state.onVibration              = () -> showVibrationDialog();
        state.onOverlayOpacityChange   = () -> {
            float v = XServerDrawerState.INSTANCE.getOverlayOpacityValue();
            if (inputControlsView != null) inputControlsView.setOverlayOpacity(v); // setter invalidates → live redraw
            preferences.edit().putFloat("overlay_opacity", v).apply();
        };
        state.onControlsColorChange    = () -> {
            // Per-profile on-screen controls accent. Write the two drawer values onto the ACTIVE
            // profile (the one bound to the running game), persist, and invalidate for a live redraw.
            // Toggling Follow-theme back ON also invalidates so the controls return to the theme accent.
            ControlsProfile profile = inputControlsView != null ? inputControlsView.getProfile() : null;
            if (profile != null) {
                profile.setCustomAccentEnabled(!XServerDrawerState.INSTANCE.getControlsFollowThemeValue());
                profile.setCustomAccentColor(XServerDrawerState.INSTANCE.getControlsAccentColorValue());
                profile.save();
            }
            if (inputControlsView != null) inputControlsView.invalidate();
        };
        state.onNativeRenderingToggle   = () -> {
            boolean next = !XServerDrawerState.INSTANCE.getNativeRenderingEnabled();
            XServerDrawerState.INSTANCE.setNativeRenderingEnabled(next);
            // Actually drive the renderer (this was previously only flipping the UI flag, so the
            // toggle had no effect and no "Native Rendering+ Enabled" toast). Native (direct
            // scanout) only exists on the Vulkan renderer.
            HostRenderer r = xServerView.getRenderer();
            if (r instanceof com.winlator.star.renderer.vulkan.VulkanRenderer) {
                com.winlator.star.renderer.vulkan.VulkanRenderer vkr =
                    (com.winlator.star.renderer.vulkan.VulkanRenderer) r;
                vkr.setNativeMode(next);
                // Direction B: native (direct scanout) bypasses the compositor post pass, where ALL
                // the Vulkan presets live. Turning native ON resets every preset so the drawer is
                // truthful (no toggles left "on" doing nothing). Only sets renderer + StateFlows —
                // never invokes the preset apply callbacks, so there's no feedback loop.
                if (next) resetVulkanPresets(vkr);
            } else if (r instanceof GLRenderer) {
                // GL Native Rendering (direct scanout) — P3 lifecycle. Builds/tears down the child
                // game/cursor SurfaceControls under the GLSurfaceView's SC.
                GLRenderer glr = (GLRenderer) r;
                glr.setNativeMode(next);
                // Direction B (P5): GL native bypasses the entire EffectComposer chain + the GL
                // scaling/upscaler modes. Turning native ON resets every GL effect so the drawer is
                // truthful (no toggles left "on" doing nothing). Only sets EffectComposer + StateFlows
                // — never invokes the apply callbacks, so there's no feedback loop. While native stays
                // on, the GraphicsContent composable greys the GL effect/scaling controls out.
                if (next) resetGlEffectsForNative(glr);
            }
        };
        // bionic-fg live controls (frame gen multiplier/flow + fps limiter). Each in-menu slider
        // updates the drawer StateFlows then fires this; we rewrite conf.toml (hot-reloads in the
        // layer) and persist to the container. Only effective when the layer is loaded this session
        // (bionicFgActive). NOTE: initial drawer state is synced after `container` is loaded (below);
        // this callback is lazy so it safely captures the field.
        state.onBionicFgConfigChange = () -> {
            XServerDrawerState s = XServerDrawerState.INSTANCE;
            if (!s.getBionicFgActive().getValue()) return; // layer not loaded -> needs a relaunch
            boolean fgOn   = s.getFrameGenEnabled().getValue();
            int   mult     = fgOn ? s.getFrameGenMultiplier().getValue() : 0;
            float flow     = s.getFrameGenFlowScale().getValue();
            // Route the single in-game multiplier/flow control to whichever engine is running this
            // session (honors a per-game engine override, else the container's engine).
            if (resolvedFrameGenEngine().equals("lsfg")) {
                // lsfg-vk: rewrite its conf.toml — the fork layer watches the file mtime and reloads
                // live (swapchain recreate). Passthrough = multiplier 1 (layer treats <=1 as off).
                File dll = new File(getFilesDir(), "lsfg-vk/Lossless.dll");
                // mult is already 0 when the in-game toggle is Off (or FG disabled). lsfg-vk treats
                // multiplier <= 1 as passthrough, so map anything below 2 to 1 — NOT max(2,mult),
                // which would force 2x on Off.
                writeLsfgConfig(mult >= 2 ? mult : 1, flow, dll.getAbsolutePath());
                if (fgOn) container.setFrameGenMultiplier(mult);
                container.setFrameGenFlowScale(flow);
                container.saveData();
                // lsfg multiplier may have crossed the >=2 threshold -> re-evaluate the limiter
                // guard (lsfgGovernsFps) so the cap steps aside / resumes without extra user action.
                reapplyFpsLimit();
                return;
            }
            // FPS limiter is no longer part of frame gen — it's a standalone host pacer
            // (onFpsLimitChange). bionic-fg conf carries frame gen only; pass the limiter off.
            writeBionicFgConfig(mult, flow, false, 0);
            if (fgOn) container.setFrameGenMultiplier(mult);
            container.setFrameGenFlowScale(flow);
            container.saveData();
        };
        // Standalone FPS limiter: paces the X11 Present extension (delays IdleNotify) so the GAME
        // itself throttles — works live with any frame-gen engine or none, all host renderers, all
        // APIs. Output of the guest present is what's capped. Persists to the container.
        state.onFpsLimitChange = () -> {
            XServerDrawerState s = XServerDrawerState.INSTANCE;
            boolean limOn  = s.getFpsLimiterEnabled().getValue();
            int   limitVal = s.getFpsLimit().getValue();   // remembered slider value, kept across on/off
            applyFpsLimit(limOn && limitVal > 0 ? limitVal : 0);
            container.setFpsLimiterEnabled(limOn);
            if (limitVal > 0) container.setFpsLimiterValue(limitVal);
            container.saveData();
        };
        // VRR / refresh-rate matching toggle. Persists to the container and re-votes the panel
        // refresh rate live (applyVrr). Independent of frame-gen; works on all 3 host renderers.
        state.onMatchRefreshChange = () -> {
            boolean on = XServerDrawerState.INSTANCE.getMatchRefreshRate().getValue();
            container.setMatchRefreshRate(on);
            container.saveData();
            reapplyVrr();
        };
        // Manual refresh-rate lock (Auto OFF). Persists the chosen rate and re-applies the panel vote
        // live (reapplyVrr reads the limiter state; applyVrr uses the manual rate when Auto is off).
        state.onManualRefreshChange = () -> {
            int rate = XServerDrawerState.INSTANCE.getManualRefreshRate().getValue();
            container.setManualRefreshRate(rate);
            container.saveData();
            reapplyVrr();
        };
        // Drawer HUD/FPS tab opened — refresh the live display-rate readout.
        state.onRefreshRatePoll = this::updateCurrentRefreshRate;
        state.onToggleFullscreen       = () -> {
            xServerView.getRenderer().toggleFullscreen();
            touchpadView.toggleFullscreen();
        };
        state.onPauseResume            = () -> {
            if (isPaused) {
                ProcessHelper.resumeAllWineProcesses();
                isPaused = false;
            } else {
                ProcessHelper.pauseAllWineProcesses();
                isPaused = true;
            }
            state.setIsPaused(isPaused);
        };
        state.onPipMode                = () -> enterPictureInPictureMode();
        state.onActiveWindows          = () -> showActiveWindowsDialog();
        state.onTaskManager            = () -> {
            XServerDrawerState.INSTANCE.selectTab(com.winlator.star.ui.TabType.TASK_MANAGER);
            XServerDialogState.INSTANCE.setTmProcesses(new ArrayList<>());
            startTmPolling();
        };
        state.onMagnifier              = () -> showMagnifierOverlay();
        state.onLogs                   = () -> XServerDialogState.INSTANCE.show(XServerDialogState.ActiveDialog.DEBUG);
        state.onExit                   = () -> exit();
        state.onMoveCursorToTouchpoint = () -> MoveCursorToTouchpoint();
        state.onRelativeMouseMovement  = () -> {
            isRelativeMouseMovement = !isRelativeMouseMovement;
            state.setIsRelativeMouseMovement(isRelativeMouseMovement);
            xServer.setRelativeMouseMovement(isRelativeMouseMovement);
        };
        state.onDisableMouse           = () -> {
            isMouseDisabled = !isMouseDisabled;
            state.setIsMouseDisabled(isMouseDisabled);
            touchpadView.setMouseEnabled(!isMouseDisabled);
        };
        String fpsCfg = container != null ? container.getFPSCounterConfig() : Container.DEFAULT_FPS_COUNTER_CONFIG;
        state.setFpsConfig(fpsCfg);
        state.onFpsConfigApply = (newConfig) -> {
            if (newConfig == null) return;
            state.setFpsConfig(newConfig);
            runOnUiThread(() -> {
                boolean wantGameHub = new com.winlator.star.core.KeyValueSet(newConfig)
                    .get("hudStyle", "classic").equals("gamehub");
                boolean haveGameHub = perfHud != null;
                boolean haveAnyHud = perfHud != null || frameRating != null || frameRatingHorizontal != null;
                // Live style swap: build the requested HUD and tear down the other, but only if a HUD
                // is already on screen (FPS was enabled for this launch). View mutation is safe here —
                // this callback runs on the UI thread.
                if (haveAnyHud && wantGameHub != haveGameHub) {
                    if (wantGameHub) { removeClassicHud(); buildPerfHud(newConfig); }
                    else { removePerfHud(); buildClassicHud(newConfig); }
                } else {
                    // Same style (or no HUD built): just push the new config to whatever exists.
                    if (frameRating != null) frameRating.applyConfig(newConfig);
                    if (frameRatingHorizontal != null) frameRatingHorizontal.applyConfig(newConfig);
                    if (perfHud != null) perfHud.applyConfig(newConfig);
                }
            });
            if (container != null) {
                container.setFPSCounterConfig(newConfig);
                container.saveData();
            }
        };

        if (inputControlsView != null) inputControlsView.setVisualStyle(VisualStyle.GAMEHUB);

        ComposeView drawerComposeView = findViewById(R.id.XServerDrawerComposeView);
        XServerDrawerKt.setupComposeView(drawerComposeView);

        // Dialog host: a full-size ComposeView on top of the game surface for
        // in-game dialogs and floating overlays (magnifier, FSR panel).
        FrameLayout xServerDisplay = findViewById(R.id.FLXServerDisplay);
        ComposeView dialogHostView = new ComposeView(this);
        dialogHostView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        xServerDisplay.addView(dialogHostView);
        XServerDialogHostKt.setupDialogHost(dialogHostView);

        imageFs = ImageFs.find(this);

        // Prepare dev/input directory - actual event files created after shortcut is loaded
        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {
            for (int i = 0; i < 4; i++) {
                File eventFile = new File(devInputDir, "event" + i);
                if (eventFile.exists()) eventFile.delete();
            }
        }

        // Initialize the WinHandler
        winHandler = new WinHandler(this);
        winHandler.setFakeInputPath(devInputDir.getAbsolutePath());

        String screenSize = Container.DEFAULT_SCREEN_SIZE;
        containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));

        componentInstallerExe = getIntent().getStringExtra("component_installer_exe");

        // Log shortcut_path
        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        Log.d("XServerDisplayActivity", "Shortcut Path: " + shortcutPath);


        // Determine container ID
        int containerId = getIntent().getIntExtra("container_id", 0);
        Log.d("XServerDisplayActivity", "Container ID from Intent: " + containerId);
        if (containerId == 0) {
            Log.d("XServerDisplayActivity", "Container ID is 0, attempting to parse from .desktop file");
            // Proceed with .desktop file parsing
        }


        // If container_id is 0, read from the .desktop file
        if (containerId == 0 && shortcutPath != null && !shortcutPath.isEmpty()) {
            File shortcutFile = new File(shortcutPath);
            containerId = parseContainerIdFromDesktopFile(shortcutFile);
            Log.d("XServerDisplayActivity", "Parsed Container ID from .desktop file: " + containerId);
        }

        // Initialize playtime tracking
        playtimePrefs = getSharedPreferences("playtime_stats", MODE_PRIVATE);
        shortcutName = getIntent().getStringExtra("shortcut_name");

        // Ensure shortcutPath is not null before proceeding
        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            if (shortcutName == null || shortcutName.isEmpty()) {
                shortcutName = parseShortcutNameFromDesktopFile(new File(shortcutPath));
                Log.d("XServerDisplayActivity", "Parsed Shortcut Name from .desktop file: " + shortcutName);
            }
        } else {
            Log.d("XServerDisplayActivity", "No shortcut path provided, skipping shortcut parsing.");
        }

        // Increment play count at the start of a session
        incrementPlayCount();

        // Log the final container_id
        Log.d("XServerDisplayActivity", "Final Container ID: " + containerId);

        // Retrieve the container and check if it's null
        container = containerManager.getContainerById(containerId);

        if (container == null) {
            Log.e("XServerDisplayActivity", "Failed to retrieve container with ID: " + containerId);
            finish();  // Gracefully exit the activity to avoid crashing
            return;
        }

        // Construct the shortcut (if any) up front so per-game overrides (frame-gen engine, fps
        // limiter, renderer) can be resolved against it below; each falls back to the container value.
        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            shortcut = new Shortcut(container, new File(shortcutPath));
        }

        // Sync the in-game frame-generation controls. bionicFgActive = is a frame-gen layer actually
        // loaded this session? Live FG tuning only works when it is; the drawer uses this to gate the
        // FG multiplier row. The engine honors per-game overrides (resolvedFrameGenEngine), else the
        // container's value. multiplier/flow are NOT per-game — live-tuned in-game, persisted on the
        // container. The FPS limiter is independent (host pacer) and is always available regardless.
        String fgEngine = resolvedFrameGenEngine();
        boolean fgEnabled = fgEngine.equals("bionic");
        boolean lsfgOn = fgEngine.equals("lsfg");
        boolean fpsLimOn = resolvedFpsLimiterEnabled();
        boolean bionicFgActive = fgEnabled || lsfgOn;
        XServerDrawerState.INSTANCE.setBionicFgActive(bionicFgActive);
        XServerDrawerState.INSTANCE.setFrameGenEnabled(fgEnabled || lsfgOn);
        // Frame gen ALWAYS starts OFF in-game (multiplier 0) regardless of the container setting.
        // The layer is still loaded at launch (below), so the user can opt in per session from the
        // FG drawer (live hot-reload). The persisted container multiplier is left untouched.
        XServerDrawerState.INSTANCE.setFrameGenMultiplier(0);
        XServerDrawerState.INSTANCE.setFrameGenFlowScale(container.getFrameGenFlowScale());
        XServerDrawerState.INSTANCE.setFrameGenEngine(fgEngine);
        XServerDrawerState.INSTANCE.setFpsLimiterEnabled(fpsLimOn);
        XServerDrawerState.INSTANCE.setFpsLimit(container.getFpsLimiterValue());
        XServerDrawerState.INSTANCE.setMatchRefreshRate(resolvedMatchRefreshRate());
        XServerDrawerState.INSTANCE.setVrrSupported(
            com.winlator.star.widget.XServerView.isDisplayVrrCapable(getWindowManager().getDefaultDisplay()));
        XServerDrawerState.INSTANCE.setSupportedRefreshRates(
            com.winlator.star.widget.XServerView.getSupportedRefreshRates(getWindowManager().getDefaultDisplay()));
        XServerDrawerState.INSTANCE.setManualRefreshRate(resolvedManualRefreshRate());
        updateCurrentRefreshRate();

        containerManager.activateContainer(container);

        // Pre-create all 4 event files so Wine registers every slot at startup.
        // Wine scans /dev/input/ once on boot — slots that don't exist then are never seen,
        // even if created later. OSC takes slot 0; physical controllers need slots 1-3.
        for (int i = 0; i < 4; i++) {
            try { new File(devInputDir, "event" + i).createNewFile(); } catch (Exception e) {}
        }
        Log.d("XServerDisplayActivity", "Pre-created 4 controller event file(s)");

        taskAffinityMask = (short) ProcessHelper.getAffinityMask(container.getCPUList(true));
        taskAffinityMaskWoW64 = (short) ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));

        if (shortcut != null) {
            taskAffinityMask = (short) ProcessHelper.getAffinityMask(shortcut.getExtra("cpuList", container.getCPUList(true)));
            taskAffinityMaskWoW64 = taskAffinityMask;
        }

        // Determine the class name for the startup workarounds
        String wmClass = shortcut != null ? shortcut.getExtra("wmClass", "") : "";
        Log.d("XServerDisplayActivity", "Startup wmClass: " + wmClass);

        firstTimeBoot = container.getExtra("appVersion").isEmpty();

        String wineVersion = container.getWineVersion();
        wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersion);

        imageFs.setWinePath(wineInfo.path);

        ProcessHelper.removeAllDebugCallbacks();
        XServerDialogState.INSTANCE.clearLog();
        if (enableLogs) {
            LogView.setFilename(getExecutable());
            ProcessHelper.addDebugCallback(line -> XServerDialogState.INSTANCE.appendLog(line));
        }

        graphicsDriver = container.getGraphicsDriver();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriver = container.getAudioDriver();
        emulator = container.getEmulator();
        midiSoundFont = container.getMIDISoundFont();
        dxwrapper = container.getDXWrapper();
        String fpsCounterConfig = container.getFPSCounterConfig();
        String dxwrapperConfig = container.getDXWrapperConfig();
        screenSize = container.getScreenSize();
        winHandler.setInputType((byte) container.getInputType());
        lc_all = container.getLC_ALL();

        // Log the entire intent to verify the extras
        Intent intent = getIntent();
        Log.d("XServerDisplayActivity", "Intent Extras: " + intent.getExtras());

        if (shortcut != null) {
            graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
            graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
            audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
            emulator = shortcut.getExtra("emulator", container.getEmulator());
            dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
            dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
            screenSize = shortcut.getExtra("screenSize", container.getScreenSize());
            lc_all = shortcut.getExtra("lc_all", container.getLC_ALL());
            String inputType = shortcut.getExtra("inputType");
            if (!inputType.isEmpty()) winHandler.setInputType(Byte.parseByte(inputType));
            String xinputDisabledString = shortcut.getExtra("disableXinput", "false");
            xinputDisabledFromShortcut = parseBoolean(xinputDisabledString);
            // Pass the value to WinHandler
            winHandler.setXInputDisabled(xinputDisabledFromShortcut);
            String sharpnessEffect = shortcut.getExtra("sharpnessEffect", "None");
            if (!sharpnessEffect.equals("None")) {
                double sharpnessLevel = Double.parseDouble(shortcut.getExtra("sharpnessLevel", "100"));
                double sharpnessDenoise = Double.parseDouble(shortcut.getExtra("sharpnessDenoise", "100"));
                vkbasaltConfig = "effects=" + sharpnessEffect.toLowerCase() + ";" + "casSharpness=" + sharpnessLevel / 100 + ";" + "dlsSharpness=" + sharpnessLevel / 100  + ";" + "dlsDenoise=" + sharpnessDenoise / 100 + ";" + "enableOnLaunch=True";
            }
            Log.d("XServerDisplayActivity", "XInput Disabled from Shortcut: " + xinputDisabledFromShortcut);
        }

        // VEGAS runs its own native DLLs from vegas-<ver>.tzst — no alias to DXVK.

        this.graphicsDriverConfig = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);
        this.dxwrapperConfig = DXVKConfigDialog.parseConfig(dxwrapperConfig);

        if (!wineInfo.isWin64()) {
            onExtractFileListener = (file, size) -> {
                String path = file.getPath();
                if (path.contains("system32/")) return null;
                return new File(path.replace("syswow64/", "system32/"));
            };
        }

        if (shortcut == null)
            preloaderDialog.show(container.getName(), null);
        else {
            preloaderDialog.show(shortcut.name, shortcut.icon);
        }

        // Supersampling ("Render scale"): multiply the game's render resolution so it renders above
        // display res, then let the Vulkan compositor Lanczos-downscale it (see setHqDownscale below).
        // Stored via the "renderScale" extra; the per-game shortcut overrides the container default.
        // Off / 1.0 = no change. This must run before ScreenInfo is built so Wine/the X server use it.
        float renderScale;
        try {
            String rsStr = (shortcut != null)
                ? shortcut.getExtra("renderScale", container.getExtra("renderScale", "1.0"))
                : container.getExtra("renderScale", "1.0");
            renderScale = Float.parseFloat(rsStr);
        } catch (NumberFormatException e) {
            renderScale = 1.0f;
        }
        if (renderScale > 1.0f) {
            String[] wh = screenSize.split("x");
            if (wh.length == 2) {
                try {
                    final int MAX_W = 7680, MAX_H = 4320; // same upper bound as the resolution picker
                    int baseW = Integer.parseInt(wh[0].trim());
                    int baseH = Integer.parseInt(wh[1].trim());
                    // Clamp the factor so neither dimension exceeds the max, preserving aspect ratio.
                    float factor = Math.min(renderScale, Math.min((float) MAX_W / baseW, (float) MAX_H / baseH));
                    int scaledW = Math.min(Math.round(baseW * factor), MAX_W);
                    int scaledH = Math.min(Math.round(baseH * factor), MAX_H);
                    // X servers / Wine desktops want even dimensions.
                    if ((scaledW & 1) == 1) scaledW--;
                    if ((scaledH & 1) == 1) scaledH--;
                    if (scaledW > baseW || scaledH > baseH) {
                        screenSize = scaledW + "x" + scaledH;
                        hqDownscale = true;
                    }
                } catch (NumberFormatException e) { /* keep the base screenSize */ }
            }
        }

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(new ScreenInfo(screenSize));
        xServer.setWinHandler(winHandler);

        boolean[] winStarted = {false};

        // Add the OnWindowModificationListener for dynamic workarounds
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (!winStarted[0] && window.isApplicationWindow()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    winStarted[0] = true;
                }
                    
                if (frameRatingWindowId == window.id) {
                    if (frameRating != null) frameRating.update();
                    if (frameRatingHorizontal != null) frameRatingHorizontal.update();
                    if (perfHud != null) perfHud.update();
                }
            }
           
            @Override
            public void onMapWindow(Window window) {
                // Log the class name of the mapped window
                Log.d("XServerDisplayActivity", "onMapWindow: Detected window className: " + window.getClassName());
                assignTaskAffinity(window);
            }

            @Override
            public void onModifyWindowProperty(Window window, Property property) {
                changeFrameRatingVisibility(window, property);
            }    

            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, null);
            }
        });

        if (!midiSoundFont.equals("")) {
            InputStream in = null;
            InputStream finalIn = in;
            MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() {
                @Override
                public void onSuccess(SF2Soundbank soundbank) {
                    midiHandler = new MidiHandler();
                    midiHandler.setSoundBank(soundbank);
                    midiHandler.start();
                }

                @Override
                public void onFailed(Exception e) {
                    try {
                        finalIn.close();
                    } catch (Exception e2) {}
                }
            };
            try {
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    in = getAssets().open(MidiManager.SF2_ASSETS_DIR + "/" + midiSoundFont);
                    MidiManager.load(in, callback);
                } else
                    MidiManager.load(new File(MidiManager.getSoundFontDir(this), midiSoundFont), callback);
            } catch (Exception e) {}
        }

        // Check if a profile is defined by the shortcut
        String controlsProfile = shortcut != null ? shortcut.getExtra("controlsProfile", "") : "";

        createNotifcationChannel();

        Intent notificationIntent = new Intent(this, XServerDisplayActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
                .setContentTitle("Winlator")
                .setContentText("Winlator is running, do not kill or swipe this notification")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false);

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());

        Runnable runnable = () -> {
            setupUI();
            if (controlsProfile.isEmpty()) {
                // No profile defined, run the simulated dialog confirmation for input controls
                simulateConfirmInputControlsDialog();
            }
            Executors.newSingleThreadExecutor().execute(() -> {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
                try {
                    setupXEnvironment();
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });
        };

        if (xServer.screenInfo.height > xServer.screenInfo.width) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            configChangedCallback = runnable;
        } else
              runnable.run();
    }

    // Method to parse container_id from .desktop file
    private int parseContainerIdFromDesktopFile(File desktopFile) {
        int containerId = 0;
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("container_id:")) {
                        containerId = Integer.parseInt(line.split(":")[1].trim());
                        break;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                Log.e("XServerDisplayActivity", "Error parsing container_id from .desktop file", e);
            }
        }
        return containerId;
    }

    private boolean parseBoolean(String value) {
        // Return true for "true", "1", "yes" (case-insensitive)
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        // Return false for any other value, including "false", "0", "no"
        return false;
    }

    // Inside XServerDisplayActivity class
    private void handleCapturedPointer(MotionEvent event) {
        boolean handled = false;

        int actionButton = event.getActionButton();
        switch (event.getAction()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE); // Add this line for middle mouse button press
                }
                handled = true;
                break;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE); // Add this line for middle mouse button release
                }
                handled = true;
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                if (xServer.isRelativeMouseMovement())
                    xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
                else
                    xServer.injectPointerMoveDelta((int)transformedPoint[0], (int)transformedPoint[1]);
                handled = true;
                break;
            case MotionEvent.ACTION_SCROLL:
                float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY <= -1.0f) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int)scrollY * 270);
                    else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    }
                } else if (scrollY >= 1.0f) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0,(int)scrollY * 270);
                    else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    }
                }
                handled = true;
                break;
        }
    }

    private void ensurePointerCapture(String reason) {
        if (!isRelativeMouseMovement || touchpadView == null) return;

        final int[] tries = {0};
        Runnable attempt = new Runnable() {
            @Override public void run() {
                if (!hasWindowFocus()) { touchpadView.postDelayed(this, 50); return; }
                if (!touchpadView.isAttachedToWindow()) { touchpadView.postDelayed(this, 50); return; }

                // Make sure the view can take focus
                touchpadView.setFocusableInTouchMode(true);
                touchpadView.requestFocus();

                touchpadView.requestPointerCapture();
                touchpadView.setOnCapturedPointerListener((v, e) -> { handleCapturedPointer(e); return true; });
                pointerCaptureRequested = true;

            }
        };
        // Try quickly a few times to dodge transient focus transitions
        touchpadView.postDelayed(attempt, 50); // First attempt
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }


    @Override
    public void onResume() {
        super.onResume();

        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
        startTime = System.currentTimeMillis();
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);
        ProcessHelper.resumeAllWineProcesses();
        // Re-assert the VRR vote — onStop() released it when backgrounded.
        reapplyVrr();
        // Track the live panel rate again (the readout shows it while Auto is on).
        registerVrrDisplayListener();
        updateCurrentRefreshRate();
    }

    @Override
    public void onPause() {
        super.onPause();

        // Check if we are entering Picture-in-Picture mode
        if (!isInPictureInPictureMode()) {
            // Only pause environment and xServerView if not in PiP mode
            if (environment != null) {
                environment.onPause();
                xServerView.onPause();
            }
        }

        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
        ProcessHelper.pauseAllWineProcesses();
    }


    // Writes the bionic-fg layer config (TOML) into the guest HOME so it is present before the
    // first swapchain present. The layer hot-reloads this file, so it doubles as the live-control
    // path (see in-game drawer). Keys: multiplier (2-4), flow_scale (0.2-1.0), model (0/1).
    // multiplier: 0 = frame gen off (Off in the menu), else 2-4. fpsLimit: 0 = no cap, else 10-200.
    private void writeBionicFgConfig(int multiplier, float flowScale, boolean fpsLimiterEnabled, int fpsLimitValue) {
        try {
            File configDir = new File(imageFs.home_path, ".config/bionic-fg");
            configDir.mkdirs();
            File confFile = new File(configDir, "conf.toml");
            // conf.toml is self-describing: the enabled flag and the remembered cap
            // value are written separately so toggling the limiter off in the UI does
            // not throw away the chosen value (the layer keeps it as the remembered cap).
            String toml = "# Written by Bannerlator (per-container frame generation)\n"
                    + "multiplier = " + multiplier + "\n"
                    + "flow_scale = " + String.format(java.util.Locale.US, "%.2f", flowScale) + "\n"
                    + "model = 0\n"
                    + "fps_limit_enabled = " + (fpsLimiterEnabled ? "true" : "false") + "\n"
                    + "fps_limit = " + fpsLimitValue + "\n";
            FileUtils.writeString(confFile, toml);
        }
        catch (Exception e) {
            Log.e("BionicFG", "Failed to write bionic-fg conf.toml", e);
        }
    }

    // lsfg-vk (GameNative fork) conf.toml. The layer watches this file's mtime in its present hook
    // and forces a swapchain recreate when it changes, re-reading multiplier/flow — so rewriting it
    // from the in-game menu re-applies live. exe MUST equal the LSFG_PROCESS env value.
    void writeLsfgConfig(int multiplier, float flowScale, String dllPath) {
        try {
            File configDir = new File(imageFs.home_path, ".config/lsfg-vk");
            configDir.mkdirs();
            File confFile = new File(configDir, "conf.toml");
            String toml = "# Written by Bannerlator (per-container lsfg-vk frame generation)\n"
                    + "version = 1\n\n"
                    + "[global]\n"
                    + "dll = \"" + dllPath + "\"\n"
                    + "no_fp16 = false\n\n"
                    + "[[game]]\n"
                    + "exe = \"bannerlator-lsfg\"\n"
                    + "multiplier = " + multiplier + "\n"
                    + "flow_scale = " + String.format(java.util.Locale.US, "%.2f", flowScale) + "\n"
                    + "performance_mode = false\n"
                    + "hdr_mode = false\n"
                    + "experimental_present_mode = \"fifo\"\n";
            FileUtils.writeString(confFile, toml);
        }
        catch (Exception e) {
            Log.e("lsfg-vk", "Failed to write lsfg-vk conf.toml", e);
        }
    }

    // === ReShade (vkBasalt) per-game effect config =============================================
    // Writes ONE merged vkBasalt.conf when a ReShade effect is selected, folding in the existing
    // CAS/DLS sharpness path so the two never fight over the env (the old code set an inline
    // VKBASALT_CONFIG for CAS; ReShade needs a config FILE for the source/include/texture paths and
    // uniform values — so when both are wanted, everything goes through the single file here).
    //
    // Layout: the chosen effect's whole subfolder is COPIED into the container's guest HOME
    // (.config/vkBasalt/effects/<name>/) so every path in the conf is HOST-ABSOLUTE inside rootDir
    // (this fork does NOT proot — proven by the spike), and any .fxh includes / textures travel with
    // it. effects = <reshade>:cas  (sharpen LAST, the usual order). Returns the host-absolute conf
    // path for VKBASALT_CONFIG_FILE, or null when no ReShade effect is selected (caller then keeps
    // the legacy inline CAS path untouched).
    private String writeVkBasaltConfig() { return writeVkBasaltConfig(true); }

    // enableOnLaunch controls the live ReShade on/off: our patched libvkbasalt watches this conf's
    // mtime and re-reads `enableOnLaunch` into presentEffect (passthrough vs effect) WITHOUT a
    // recompile, so flipping it here is what makes the in-game "Effect" toggle disable the effect
    // live. The effect stays in the chain (still compiled) either way — only the present is bypassed.
    private String writeVkBasaltConfig(boolean enableOnLaunch) {
        String effectName = resolvedReshadeEffect();
        if (effectName == null || effectName.equals("None") || effectName.isEmpty()) return null;
        if (!reshadeSupported()) return null; // WineD3D/GL/GDI titles can't carry the layer

        com.winlator.star.reshade.ReshadeManager.ReshadeEffect effect =
            com.winlator.star.reshade.ReshadeManager.findEffect(this, effectName);
        if (effect == null) {
            Log.w("VkBasalt", "Selected ReShade effect not found in drop-in folder: " + effectName);
            return null;
        }

        try {
            File configDir = new File(imageFs.home_path, ".config/vkBasalt");
            File effectsRoot = new File(configDir, "effects");
            File destDir = new File(effectsRoot, effect.name);
            // Refresh the staged copy each launch so edits to the drop-in folder take effect.
            FileUtils.delete(destDir);
            destDir.mkdirs();
            if (!FileUtils.copy(effect.dir, destDir)) {
                Log.e("VkBasalt", "Failed to stage ReShade effect folder: " + effect.dir);
                return null;
            }
            String destDirPath = destDir.getAbsolutePath();           // host-absolute
            File fxDest = new File(destDir, effect.fxFile.getName());
            String fxPath = fxDest.getAbsolutePath();

            // Parsed per-uniform overrides (shortcut/container JSON) layered over the .fx defaults.
            org.json.JSONObject paramJson = null;
            String paramsRaw = resolvedReshadeParams();
            if (paramsRaw != null && !paramsRaw.isEmpty()) {
                try { paramJson = new org.json.JSONObject(paramsRaw); }
                catch (org.json.JSONException ignored) {}
            }

            // The effect technique name vkBasalt keys on. vkBasalt derives it from the config key
            // (effectKey = path). Use a stable, lower-case, syntax-safe key.
            String effectKey = effect.name.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(java.util.Locale.US);
            if (effectKey.isEmpty()) effectKey = "reshade";

            StringBuilder sb = new StringBuilder();
            sb.append("# Written by Bannerlator (per-game ReShade effect via vkBasalt)\n");
            // Sharpen LAST: reshade first, then the existing CAS/DLS chain (if any).
            StringBuilder chain = new StringBuilder(effectKey);
            if (vkbasaltConfig != null && !vkbasaltConfig.isEmpty()) {
                // The legacy inline string is "effects=<cas|dls>;casSharpness=..;...". Pull the
                // effect name + carry the sharpness keys into this merged file.
                appendSharpnessFromInline(sb, chain);
            }
            sb.append("effects = ").append(chain).append("\n");
            sb.append(effectKey).append(" = ").append(fxPath).append("\n");
            sb.append("reshadeTexturePath = ").append(destDirPath).append("\n");
            sb.append("reshadeIncludePath = ").append(destDirPath).append("\n");

            // Reflected uniform values (defaults, overridden by the saved per-game/container JSON).
            // vkBasalt reads `.fx` shaders (NOT ReShade preset .ini), so we materialize the values as
            // top-level conf keys named after each uniform. seedValues() resolves the value-map (one
            // entry per uniform, or per-component for COLOR); formatUniformLine() is the single place
            // that maps those to the "<effectKey>_<uniform>[_c]" conf keys our patched libvkbasalt reads.
            HashMap<String, Float> resolved = new HashMap<>();
            for (com.winlator.star.reshade.ReshadeManager.ReshadeParam p : effect.params) {
                com.winlator.star.reshade.ReshadeManager.seedValues(p, paramJson, resolved);
            }
            for (com.winlator.star.reshade.ReshadeManager.ReshadeParam p : effect.params) {
                sb.append(formatUniformLine(effectKey, p, resolved));
            }

            sb.append("toggleKey = Home\n");
            sb.append("enableOnLaunch = ").append(enableOnLaunch ? "True" : "False").append("\n");

            File confFile = new File(configDir, "vkBasalt.conf");
            FileUtils.writeString(confFile, sb.toString());
            String confPath = confFile.getAbsolutePath();
            Log.d("VkBasalt", "Wrote merged ReShade conf (" + chain + ") -> " + confPath);
            return confPath;
        } catch (Exception e) {
            Log.e("VkBasalt", "Failed to write ReShade vkBasalt.conf", e);
            return null;
        }
    }

    // Fold the legacy inline CAS/DLS string ("effects=cas;casSharpness=..;dlsSharpness=..;
    // dlsDenoise=..;enableOnLaunch=True") into the merged file: append the sharpen effect to the
    // chain and copy its sharpness keys as conf lines.
    private void appendSharpnessFromInline(StringBuilder sb, StringBuilder chain) {
        String sharpenEffect = null;
        for (String kv : vkbasaltConfig.split(";")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq).trim();
            String v = kv.substring(eq + 1).trim();
            if (k.equals("effects")) sharpenEffect = v;
            else if (k.equals("casSharpness") || k.equals("dlsSharpness") || k.equals("dlsDenoise"))
                sb.append(k).append(" = ").append(v).append("\n");
        }
        if (sharpenEffect != null && !sharpenEffect.isEmpty() && !sharpenEffect.equals("none"))
            chain.append(":").append(sharpenEffect);
    }

    // Single source of truth for how a reflected uniform value is written into vkBasalt.conf.
    // Our patched libvkbasalt (patches/vkbasalt-reshade-livereload.patch, ReshadeUniform::setFromConfig)
    // reads per-uniform overrides under "<effectKey>_<uniform>" for single-component uniforms and
    // "<effectKey>_<uniform>_<c>" for each component of a multi-component one (the SAME effectKey used
    // in `effects = <effectKey>`), pushing the value into the live UBO. `values` is the resolved
    // value-map from ReshadeManager.seedValues (keys "<uniform>" or, for COLOR, "<uniform>_<c>").
    //   BOOL  -> <effectKey>_<uniform> = 0|1          (read as getOption<bool>)
    //   COMBO -> <effectKey>_<uniform> = <index>      (read as getOption<int32_t>)
    //   INT   -> <effectKey>_<uniform> = <int>
    //   COLOR -> <effectKey>_<uniform>_0..N-1 = <f>   (read per-component as getOption<float>)
    //   FLOAT -> <effectKey>_<uniform> = <f>
    private String formatUniformLine(String effectKey, com.winlator.star.reshade.ReshadeManager.ReshadeParam p,
                                     Map<String, Float> values) {
        String base = effectKey + "_" + p.name;
        switch (p.type) {
            case BOOL:
                return base + " = " + (getF(values, p.name, p.defaultValue) >= 0.5f ? "1" : "0") + "\n";
            case COMBO:
            case INT:
                return base + " = " + Math.round(getF(values, p.name, p.defaultValue)) + "\n";
            case COLOR: {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < p.components; c++) {
                    String k = p.name + "_" + c;
                    float def = (p.componentDefaults != null && c < p.componentDefaults.length)
                            ? p.componentDefaults[c] : 0f;
                    sb.append(base).append("_").append(c).append(" = ")
                      .append(String.format(java.util.Locale.US, "%.4f", getF(values, k, def))).append("\n");
                }
                return sb.toString();
            }
            case FLOAT:
            default:
                return base + " = " + String.format(java.util.Locale.US, "%.4f", getF(values, p.name, p.defaultValue)) + "\n";
        }
    }

    private static float getF(Map<String, Float> values, String key, float fallback) {
        Float v = values != null ? values.get(key) : null;
        return v != null ? v : fallback;
    }

    // Seed the in-game ReShade drawer controls from the resolved launch config. Runs in setupUI
    // (after container/shortcut are assigned). enableOnLaunch=True, so a selected effect is ON.
    private void seedReshadeDrawerState(XServerDialogState ds) {
        boolean supported = reshadeSupported();
        ds.setReshadeSupported(supported);
        String effectName = resolvedReshadeEffect();
        boolean hasEffect = supported && effectName != null && !effectName.equals("None") && !effectName.isEmpty();
        if (!hasEffect) {
            ds.setReshadeEffectName("None");
            ds.setReshadeEnabled(false);
            ds.setReshadeParams(new ArrayList<>());
            ds.setReshadeValues(new HashMap<>());
            return;
        }
        com.winlator.star.reshade.ReshadeManager.ReshadeEffect effect =
            com.winlator.star.reshade.ReshadeManager.findEffect(this, effectName);
        ds.setReshadeEffectName(effect != null ? effect.name : "None");
        ds.setReshadeEnabled(effect != null);
        if (effect == null) {
            ds.setReshadeParams(new ArrayList<>());
            ds.setReshadeValues(new HashMap<>());
            return;
        }
        ds.setReshadeParams(effect.params);
        // Values: saved JSON (shortcut override -> container default) layered over the .fx defaults.
        // seedValues handles the value-map key scheme (one entry per uniform; per-component for COLOR).
        org.json.JSONObject saved = null;
        String raw = resolvedReshadeParams();
        if (raw != null && !raw.isEmpty()) {
            try { saved = new org.json.JSONObject(raw); } catch (JSONException ignored) {}
        }
        HashMap<String, Float> values = new HashMap<>();
        for (com.winlator.star.reshade.ReshadeManager.ReshadeParam p : effect.params) {
            com.winlator.star.reshade.ReshadeManager.seedValues(p, saved, values);
        }
        ds.setReshadeValues(values);
    }

    // SINGLE pluggable seam for ReShade live-apply. For NOW it persists the new uniform values to
    // the active store and rewrites the merged vkBasalt.conf, so the change is correct on the next
    // launch (the bundled vkBasalt has no config-watch — see RESHADE_STEP3_PLAN.md §5). When the
    // live mechanism lands (a vkBasalt config-watch patch for live uniforms, and/or an X11 Home-key
    // inject for live on/off), wire it HERE — this is the only place the rest of the feature calls.
    private void applyReshadeLive(boolean enabled, Map<String, Float> values) {
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            if (values != null) {
                for (Map.Entry<String, Float> e : values.entrySet())
                    json.put(e.getKey(), (double) e.getValue());
            }
            String jsonStr = json.length() == 0 ? null : json.toString();
            if (shortcut != null) {
                shortcut.putExtra("reshadeParams", jsonStr);
                shortcut.saveData();
            } else if (container != null) {
                container.setReshadeParams(jsonStr);
                container.saveData();
            }
        } catch (JSONException ignored) {}
        // Rewrite the conf so a relaunch reflects the new values (resolvedReshadeParams now reads
        // the freshly-saved JSON) AND so the live config-watch in our patched libvkbasalt picks up
        // both the new uniform values and the on/off state. `enabled` -> enableOnLaunch: toggling
        // the drawer "Effect" switch off writes enableOnLaunch=False, which the layer reloads into
        // presentEffect=false (passthrough) on the next frame -> the effect actually turns off.
        writeVkBasaltConfig(enabled);
    }

    private void savePlaytimeData() {
        long endTime = System.currentTimeMillis();
        long playtime = endTime - startTime;

        // Ensure that playtime is not negative
        if (playtime < 0) {
            playtime = 0;
        }

        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playtimeKey = shortcutName + "_playtime";

        // Accumulate the playtime into totalPlaytime
        long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0) + playtime;
        editor.putLong(playtimeKey, totalPlaytime);
        editor.apply();

        // Reset startTime to the current time for the next interval
        startTime = System.currentTimeMillis();
    }


    private void incrementPlayCount() {
        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playCountKey = shortcutName + "_play_count";
        int playCount = playtimePrefs.getInt(playCountKey, 0) + 1;
        editor.putInt(playCountKey, playCount);
        editor.apply();
    }

    private void exit() {
        installerWatchHandler.removeCallbacks(installerWatchRunnable);
        gameExitWatchHandler.removeCallbacks(gameExitWatchRunnable);
        stopDxApiDetection();
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        preloaderDialog.showOnUiThread(R.string.shutdown);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                savePlaytimeData(); // Save on destroy
                handler.removeCallbacks(savePlaytimeRunnable);
                if (midiHandler != null) midiHandler.stop();
                // Unregister sensor listener to avoid memory leaks
                if (environment != null) environment.stopEnvironmentComponents();
                if (preloaderDialog != null && preloaderDialog.isShowing()) preloaderDialog.closeOnUiThread();
                if (winHandler != null) winHandler.stop();
                if (wineRequestHandler != null) wineRequestHandler.stop();
                /* Gracefully terminate all running wine processes */
                ProcessHelper.terminateAllWineProcesses();
                /* Wait until all processes have gracefully terminated, forcefully killing them only after a certain amount of time */
                long start = System.currentTimeMillis();
                while (!ProcessHelper.listRunningWineProcesses().isEmpty()) {
                    long elapsed = System.currentTimeMillis() - start;
                    if (elapsed >= 1500) {
                        break;
                    }
                }
                preloaderDialog.closeOnUiThread();
                AppUtils.restartApplication(getApplicationContext());
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDxApiDetection();
        if (wineDebugLogCallback != null) {
            ProcessHelper.removeDebugCallback(wineDebugLogCallback);
            wineDebugLogCallback = null;
        }
        if (wineDebugWriter != null) {
            wineDebugWriter.close();
            wineDebugWriter = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
        // Release the panel refresh-rate vote while backgrounded so we don't pin the display rate
        // for whatever is composited on top. onResume() re-asserts it.
        if (xServerView != null) xServerView.setDisplayFrameRate(0f, VRR_FRAME_RATE_COMPATIBILITY);
        unregisterVrrDisplayListener();
    }

    private void releasePointerCaptureIfNeeded(String reason) {
        if (pointerCaptureRequested && touchpadView != null) {
            touchpadView.releasePointerCapture();
            touchpadView.setOnCapturedPointerListener(null);
            pointerCaptureRequested = false;
            Log.d("PointerCapture", "Released: " + reason);
        }
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }

    private void openXServerDrawer() {
        if (environment != null) {
            releasePointerCaptureIfNeeded("open-drawer/shortcut");
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            } else {
                drawerLayout.closeDrawers();
            }
        }
    }


    
    private void showVibrationDialog() {
        if (winHandler == null) return;
        int max = winHandler.getMaxControllers();
        java.util.List<android.util.Pair<String, Boolean>> slots = new java.util.ArrayList<>();
        for (int i = 0; i < max; i++) {
            slots.add(new android.util.Pair<>(
                getString(R.string.vibration_slot, i + 1),
                winHandler.isVibrationEnabledForSlot(i)));
        }
        // Convert android.util.Pair to kotlin.Pair for XServerDialogState
        java.util.List<kotlin.Pair<String, Boolean>> kSlots = new java.util.ArrayList<>();
        for (android.util.Pair<String, Boolean> p : slots) {
            kSlots.add(new kotlin.Pair<>(p.first, p.second));
        }
        XServerDialogState ds = XServerDialogState.INSTANCE;
        ds.setVibrationSlots(kSlots);
        ds.onVibrationSlotChanged = (slot, enabled) -> winHandler.setVibrationEnabledForSlot(slot, enabled);
        ds.show(XServerDialogState.ActiveDialog.VIBRATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && cursorLock) {
            touchpadView.requestPointerCapture();
            touchpadView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                @Override
                public boolean onCapturedPointer(View view, MotionEvent event) {
                    handleCapturedPointer(event);
                    return true;
                }
            });
        }
        else if (!hasFocus) {
            touchpadView.releasePointerCapture();
            touchpadView.setOnCapturedPointerListener(null);
        }
    }

    // private void extractInputDLLs() {
    //     String inputAsset = "input_dlls.tzst";
    //     File wineFolder = new File(imageFs.getWinePath() + "/lib/wine/");
    //     boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, inputAsset, wineFolder);
    //     if (!success)
    //         Log.d("XServerDisplayActivity", "Failed to extract input dlls");
    // }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String imgVersion = String.valueOf(imageFs.getVersion());
        boolean containerDataChanged = false;

        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("imgVersion").equals(imgVersion)) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("imgVersion", imgVersion);
            containerDataChanged = true;
        }

        String dxwrapper = this.dxwrapper;

        if (dxwrapper.contains("dxvk")) {
            String dxvkWrapper = "dxvk-" + dxwrapperConfig.get("version");
            String vkd3dWrapper = "vkd3d-" + dxwrapperConfig.get("vkd3dVersion");
            String ddrawrapper = dxwrapperConfig.get("ddrawrapper");
            dxwrapper = dxvkWrapper + ";" + vkd3dWrapper + ";" + ddrawrapper;
        }
        else if (dxwrapper.contains("vegas")) {
            String vegasVersion = dxwrapperConfig.get("version");
            if (vegasVersion == null || vegasVersion.isEmpty())
                vegasVersion = DefaultVersion.getVegasDefault();
            String ddrawrapper = dxwrapperConfig.get("ddrawrapper");
            String vkd3dVersion = dxwrapperConfig.get("vkd3dVersion");
            String vkd3dPart = (vkd3dVersion != null && !vkd3dVersion.isEmpty() && !vkd3dVersion.equals("none") && !vkd3dVersion.equals("None"))
                ? "vkd3d-" + vkd3dVersion : "";
            dxwrapper = "vegas-" + vegasVersion + ";" + vkd3dPart + ";" + ddrawrapper;
        }

        if (!dxwrapper.equals(container.getExtra("dxwrapper"))) {
            if (extractDXWrapperFiles(dxwrapper)) {
                container.putExtra("dxwrapper", dxwrapper);
                containerDataChanged = true;
            }
        }

        String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container);
        
        // Configure Wine joystick registry keys based on DInput setting
        int inputType = container.getInputType();
        if (shortcut != null) {
            String shortcutInputType = shortcut.getExtra("inputType");
            if (!shortcutInputType.isEmpty()) {
                inputType = Byte.parseByte(shortcutInputType);
            }
        }
        boolean dinputEnabled = (inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) == WinHandler.FLAG_INPUT_TYPE_DINPUT;
        
        boolean exclusiveXInput = container.isExclusiveXInput();
        if (shortcut != null) {
            String extra = shortcut.getExtra("exclusiveXInput");
            if (!extra.isEmpty()) exclusiveXInput = extra.equals("1");
        }
        
        WineUtils.setJoystickRegistryKeys(container, dinputEnabled, exclusiveXInput);

        if (shortcut != null)
            startupSelection = shortcut.getExtra("startupSelection", String.valueOf(container.getStartupSelection()));
        else
            startupSelection = String.valueOf(container.getStartupSelection());

        if (!startupSelection.equals(container.getExtra("startupSelection"))) {
            WineUtils.changeServicesStatus(container, startupSelection);
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }
        if (containerDataChanged) container.saveData();
    }

    private void setupXEnvironment() throws PackageManager.NameNotFoundException {

        // Set environment variables
        envVars.put("LC_ALL", lc_all);
        envVars.put("WINEPREFIX", imageFs.wineprefix);

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        // Always include +err,+warn so the debug log captures crash info even when verbose debug is off.
        // Prepend them to whatever the user has selected; "-all" baseline is dropped so errors surface.
        String wineDebugValue;
        if (enableWineDebug && !wineDebugChannels.isEmpty()) {
            wineDebugValue = "+" + wineDebugChannels.replace(",", ",+");
        } else {
            wineDebugValue = "+err,+warn,+fixme,-all";
        }
        envVars.put("WINEDEBUG", wineDebugValue);

        // ── Wine debug file log ────────────────────────────────────────────────
        // Writes all Wine stdout/stderr to a readable file.
        // Path: /sdcard/Android/data/com.winlator.star/files/wine_debug.log
        try {
            File logDir = getExternalFilesDir(null);
            if (logDir != null) {
                logDir.mkdirs();
                File logFile = new File(logDir, "wine_debug.log");
                wineDebugWriter = new java.io.PrintWriter(
                        new java.io.BufferedWriter(new java.io.FileWriter(logFile, false)), true);
                // Header: print context that helps diagnose the crash
                wineDebugWriter.println("=== Wine Debug Log ===");
                wineDebugWriter.println("WINEDEBUG: " + wineDebugValue);
                wineDebugWriter.println("WINEPREFIX: " + imageFs.wineprefix);
                wineDebugWriter.println("Container ID: " + (container != null ? container.id : "null"));
                if (shortcut != null) {
                    wineDebugWriter.println("Shortcut file: " + shortcut.file.getPath());
                    wineDebugWriter.println("Shortcut path (resolved): " + shortcut.path);
                } else {
                    wineDebugWriter.println("Shortcut: null (launching Wine File Manager)");
                }
                // DX wrapper diagnostic
                wineDebugWriter.println("--- DX Wrapper State ---");
                wineDebugWriter.println("dxwrapper type: " + this.dxwrapper);
                wineDebugWriter.println("dxwrapperConfig (raw): " + (container != null ? container.getDXWrapperConfig() : "null"));
                wineDebugWriter.println("vkd3dVersion (parsed): " + dxwrapperConfig.get("vkd3dVersion"));
                wineDebugWriter.println("dxvk version (parsed): " + dxwrapperConfig.get("version"));
                wineDebugWriter.println("ddrawrapper (parsed): " + dxwrapperConfig.get("ddrawrapper"));
                String cachedDxwrapper = (container != null ? container.getExtra("dxwrapper") : "none");
                wineDebugWriter.println("cached dxwrapper extra: " + cachedDxwrapper);
                if (this.dxwrapper.contains("dxvk")) {
                    String expectedDxvkWrapper = "dxvk-" + dxwrapperConfig.get("version");
                    String expectedVkd3dWrapper = "vkd3d-" + dxwrapperConfig.get("vkd3dVersion");
                    String expectedDdra = dxwrapperConfig.get("ddrawrapper");
                    String expectedFull = expectedDxvkWrapper + ";" + expectedVkd3dWrapper + ";" + expectedDdra;
                    wineDebugWriter.println("expected full string: " + expectedFull);
                    wineDebugWriter.println("extraction will run: " + (!expectedFull.equals(cachedDxwrapper)));
                }
                wineDebugWriter.println("--- End DX Wrapper State ---");
                wineDebugWriter.println("=== Wine output below ===");
                wineDebugLogCallback = line -> {
                    if (wineDebugWriter != null) {
                        wineDebugWriter.println(line);
                    }
                };
                ProcessHelper.addDebugCallback(wineDebugLogCallback);
                Log.d("WineDebug", "Wine debug log → " + logFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e("WineDebug", "Failed to open wine debug log file", e);
        }

        // Clear any temporary directory
        String rootPath = imageFs.getRootDir().getPath();
        FileUtils.clear(imageFs.getTmpDir());


        guestProgramLauncherComponent = new GuestProgramLauncherComponent(
                contentsManager,
                contentsManager.getProfileByEntryName(container.getWineVersion()),
                shortcut
        );

        // Additional container checks and environment configuration
        if (container != null) {
            if (Byte.parseByte(startupSelection) == Container.STARTUP_SELECTION_AGGRESSIVE) {
                // winHandler.killProcess("services.exe"); 
            }
            guestProgramLauncherComponent.setContainer(this.container);
            guestProgramLauncherComponent.setWineInfo(this.wineInfo);

            String guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " + getWineStartCommand();

            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());

            // Frame-gen engine: lsfg-vk or bionic-fg (mutually exclusive), or off. Honors per-game
            // overrides (else the container's value), matching the drawer sync above.
            // NOTE: the FPS limiter is no longer wired here — it's a standalone host-side pacer
            // applied to the renderer (see renderer.setFpsLimit below), independent of frame gen.
            if (resolvedFrameGenEngine().equals("lsfg")) {
                // lsfg-vk engine (mutually exclusive with bionic-fg). Opt-in via ENABLE_LSFG so the
                // staged layer stays inert elsewhere. Driven by conf.toml (NOT the LSFG_LEGACY env):
                // the GameNative-fork layer watches the conf.toml mtime in its present hook and forces
                // a swapchain recreate on change, so rewriting the file re-applies multiplier/flow LIVE
                // in-game. It HARD-EXITS if it can't read the Lossless.dll, so only enable when the
                // user-imported copy exists. LSFG_PROCESS must match the conf.toml [[game]].exe (under
                // Wine /proc/self/exe is the loader, so the real exe name is unusable).
                File losslessDll = new File(getFilesDir(), "lsfg-vk/Lossless.dll");
                if (losslessDll.isFile()) {
                    // Start in passthrough (multiplier 1 = frame gen off). ENABLE_LSFG still loads the
                    // layer, so the FG drawer can enable it live in-session (the conf.toml mtime watch
                    // re-applies the user's multiplier without a relaunch). Container value untouched.
                    writeLsfgConfig(1, container.getFrameGenFlowScale(), losslessDll.getAbsolutePath());
                    File lsfgConf = new File(imageFs.home_path, ".config/lsfg-vk/conf.toml");
                    envVars.put("ENABLE_LSFG", "1");
                    envVars.put("LSFG_CONFIG", lsfgConf.getAbsolutePath());
                    envVars.put("LSFG_PROCESS", "bannerlator-lsfg");
                } else {
                    Log.w("XServerDisplayActivity", "lsfg-vk selected but no Lossless.dll imported (Settings) — leaving frame gen off");
                }
            } else {
                // bionic-fg layer: load it only when frame generation is the selected engine. The
                // FPS limiter is handled separately (host pacer), so it no longer forces this layer
                // to load. multiplier=0 -> frame gen starts Off in-game (layer loaded, enable live).
                boolean fgOn = resolvedFrameGenEngine().equals("bionic");
                if (fgOn) {
                    envVars.put("BIONIC_FG_ENABLE", "1");
                    writeBionicFgConfig(
                            0,
                            container.getFrameGenFlowScale(),
                            false,
                            0);
                }
            }

            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));

            if (!envVars.has("WINEESYNC")) {
                envVars.put("WINEESYNC", "1");
            }

            ArrayList<String> bindingPaths = new ArrayList<>();
            for (String[] drive : container.drivesIterator()) {
                bindingPaths.add(drive[1]);
            }

            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));

            guestProgramLauncherComponent.setBox64Preset(
                    shortcut != null
                            ? shortcut.getExtra("box64Preset", container.getBox64Preset())
                            : container.getBox64Preset()
            );

            guestProgramLauncherComponent.setFEXCorePreset(
                    shortcut != null
                            ? shortcut.getExtra("fexcorePreset", container.getFEXCorePreset())
                            : container.getFEXCorePreset()
            );
        }

        // Merge overrideEnvVars if present
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars.clear(); // Clear overrideEnvVars as per smali logic
        }

        // Create our overall XEnvironment with various components
        environment = new XEnvironment(this, imageFs);
        environment.addComponent(
                new SysVSharedMemoryComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)
                )
        );
        environment.addComponent(
                new XServerComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)
                )
        );

        // Audio driver logic
        if (audioDriver.equals("alsa")) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            environment.addComponent(
                    new ALSAServerComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH)
                    )
            );
        } else if (audioDriver.equals("pulseaudio")) {
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(
                    new PulseAudioComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH)
                    )
            );
        }

        // Pass final envVars to the launcher
        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());

        // Add the launcher to our environment
        environment.addComponent(guestProgramLauncherComponent);

        // Initialize fake input for controller emulation - MUST be before Wine starts! Deleting old ones should also be done here ofc.
        // Initialize fake input for controller emulation - MUST be before Wine starts!
        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (devInputDir.exists() || devInputDir.mkdirs()) {
             // Cleanup moved to onCreate
        }

        // Start all environment components (XServer, Audio, Wine, etc.)
        environment.startEnvironmentComponents();

        // Start the WinHandler (writes events to the file)
        winHandler.start();

        // If this session was launched to run a component installer, watch for it to finish and
        // auto-close the container (see componentInstallerExe / installerWatchRunnable).
        if (componentInstallerExe != null && !componentInstallerExe.isEmpty()) startInstallerWatch();
        // Otherwise, for a game-shortcut launch, optionally auto-close the session when the game exits
        // so the user isn't left on the empty Wine desktop. Skipped for plain container/file-manager
        // launches (shortcut == null) and during installer runs.
        else if (shortcut != null && resolvedAutoCloseOnExit()) {
            autoCloseOnExitEnabled = true;
            startGameExitWatch();
        }

        if (wineRequestHandler != null) wineRequestHandler.start();

        // Reset dxwrapper config
        dxwrapperConfig = null;

    }

    private void createWrapperScript(String path, String content) {
        File scriptFile = new File(path);
        FileUtils.writeString(scriptFile, content);
        scriptFile.setExecutable(true);
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        String rendererType = container != null ? resolvedRenderer() : "vulkan";
        // SurfaceFlinger (ASR) requires API 29+; fall back to Vulkan if unsupported.
        if ("surfaceflinger".equalsIgnoreCase(rendererType)
                && !com.winlator.star.renderer.ASurfaceRenderer.isSupported()) {
            rendererType = "vulkan";
        }
        boolean useVulkan = "vulkan".equals(rendererType);
        xServerView.initRenderer(rendererType);
        final HostRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);

        // Standalone FPS limiter (guest-side, via the X11 Present extension): apply the resolved
        // per-game/container value up front, independent of the frame-gen engine. The in-game toggle
        // (onFpsLimitChange) updates it live afterwards.
        applyFpsLimit(resolvedFpsLimiterEnabled() ? container.getFpsLimiterValue() : 0);

        // Apply the container's advanced Vulkan present settings (native rendering, present mode,
        // filter, swap R/B). Source of truth = the container's dedicated renderer* fields. The old
        // code parsed these out of graphicsDriverConfig via KeyValueSet, but that splits on COMMA
        // while graphicsDriverConfig is SEMICOLON-separated -> every value silently fell to default,
        // so these options never applied. These are container-level (not per-game shortcut extras).
        if (useVulkan && renderer instanceof com.winlator.star.renderer.vulkan.VulkanRenderer) {
            com.winlator.star.renderer.vulkan.VulkanRenderer vkRenderer =
                (com.winlator.star.renderer.vulkan.VulkanRenderer) renderer;
            String pm = container.getRendererPresentMode();
            int pmInt = "immediate".equals(pm) ? 0 : "mailbox".equals(pm) ? 1 : 2; // VkPresentModeKHR
            vkRenderer.setVkPresentMode(pmInt);
            // Scaling mode owns the base sampler filter on Vulkan (modes 1/2 call setFilterMode
            // internally), so drive the launch through setUpscaler instead of a separate
            // setFilterMode call — keeping the in-game "Scaling mode" picker the single source of
            // truth for scaling/filtering. Default the scaling mode to Linear (1) — the safe,
            // artifact-free choice for a global default — and only seed Nearest (2) when the
            // container's filter mode is explicitly Nearest; mirror it into the drawer.
            // (filterMode: 0=default -> Linear, 1=linear -> Linear, 2=nearest -> Nearest.)
            int initialUpscaler = container.getRendererFilterMode() == 2 ? 2 : 1;
            vkRenderer.setUpscaler(initialUpscaler);
            XServerDialogState.INSTANCE.setUpscalerMode(initialUpscaler);
            // Supersampling: when the launch resolution was scaled above display res (see onCreate),
            // run the compositor's quality Lanczos downscale. No-op when render scale is Off.
            vkRenderer.setHqDownscale(hqDownscale);
            // Composable CAS / fake-HDR + real upscaler sharpness — drawer-only / session-live,
            // default off (sharpness defaults to the legacy 0.25 RCAS stops == slider 75). Seed
            // the renderer and mirror the defaults into the drawer state.
            vkRenderer.setUpscaleSharpness(75);
            vkRenderer.setCas(false, 60);
            vkRenderer.setHdr(false);
            XServerDialogState.INSTANCE.setUpscaleSharpness(75);
            XServerDialogState.INSTANCE.setCasEnabled(false);
            XServerDialogState.INSTANCE.setCasSharpness(60);
            XServerDialogState.INSTANCE.setHdrVkEnabled(false);
            // Phase 2 screen effects (GL parity) — drawer-only / session-live, default
            // off / neutral grade. Seed the renderer and mirror into the drawer state.
            vkRenderer.setScreenEffects(0f, 0f, 1.0f, false, false, false, false);
            XServerDialogState.INSTANCE.setVkBrightness(0f);
            XServerDialogState.INSTANCE.setVkContrast(0f);
            XServerDialogState.INSTANCE.setVkGamma(1.0f);
            XServerDialogState.INSTANCE.setVkFxaa(false);
            XServerDialogState.INSTANCE.setVkToon(false);
            XServerDialogState.INSTANCE.setVkCrt(false);
            XServerDialogState.INSTANCE.setVkNtsc(false);
            vkRenderer.setSwapRB(container.getRendererSwapRB());
            // Must run before the surface is created so onSurfaceCreated sets up the scanout path.
            boolean nativeOn = container.isRendererNative();
            vkRenderer.setInitialNativeMode(nativeOn);
            XServerDrawerState.INSTANCE.setNativeRenderingEnabled(nativeOn); // keep the toggle in sync
            // Tick the perf HUD per present (the Vulkan AHB path bypasses copyArea, which normally
            // drives it). Gate on the FPS window so we only count game frames.
            vkRenderer.setHudFrameTick(wid -> {
                if (wid == frameRatingWindowId) {
                    if (frameRating != null) frameRating.update();
                    if (frameRatingHorizontal != null) frameRatingHorizontal.update();
                    if (perfHud != null) perfHud.update();
                }
            });
        }

        // GL renderer: apply the container's filter mode to the window/content sampler. The Vulkan
        // path above drives this through setUpscaler (modes 1/2); on GL the dedicated setFilterMode
        // is the single source of truth. Gated to GLRenderer so it stays a no-op on Vulkan/ASR.
        // (filterMode: 0=default -> Linear, 1=linear -> Linear, 2=nearest -> Nearest.)
        if (renderer instanceof GLRenderer) {
            GLRenderer glr = (GLRenderer) renderer;
            glr.setFilterMode(container.getRendererFilterMode());
            // GL Native Rendering (direct scanout) lifecycle — mirror the Vulkan launch wiring. Must
            // run before the surface is created so GLRenderer.onSurfaceCreated builds the scanout
            // SurfaceControls when native is on. swapRB feeds the game SC color transform.
            glr.setSwapRB(container.getRendererSwapRB());
            boolean glNativeOn = container.isRendererNative();
            glr.setInitialNativeMode(glNativeOn);
            XServerDrawerState.INSTANCE.setNativeRenderingEnabled(glNativeOn); // keep the toggle in sync
            // GL native (FLIP/scanout) bypasses both onDrawFrame and copyArea, so drive the perf HUD
            // per present here (same as the Vulkan/ASR ticks) — otherwise the HUD freezes in native mode.
            glr.setHudFrameTick(wid -> {
                if (wid == frameRatingWindowId) {
                    if (frameRating != null) frameRating.update();
                    if (frameRatingHorizontal != null) frameRatingHorizontal.update();
                    if (perfHud != null) perfHud.update();
                }
            });
        }

        // ASR has no compositor copyArea path either, so drive the perf HUD per present (same as
        // the Vulkan tick above) — otherwise the HUD shows no FPS under the SurfaceFlinger renderer.
        if (renderer instanceof com.winlator.star.renderer.ASurfaceRenderer) {
            ((com.winlator.star.renderer.ASurfaceRenderer) renderer).setHudFrameTick(wid -> {
                if (wid == frameRatingWindowId) {
                    if (frameRating != null) frameRating.update();
                    if (frameRatingHorizontal != null) frameRatingHorizontal.update();
                    if (perfHud != null) perfHud.update();
                }
            });
        }

        if (shortcut != null) {
            renderer.setUnviewableWMClasses("explorer.exe");
        }

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        touchpadView = new TouchpadView(this, xServer, timeoutHandler, hideControlsRunnable);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMouseEnabled(!isMouseDisabled);
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this, timeoutHandler, hideControlsRunnable);
        float savedOverlayOpacity = preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY);
        inputControlsView.setOverlayOpacity(savedOverlayOpacity);
        XServerDrawerState.INSTANCE.setOverlayOpacity(savedOverlayOpacity); // seed the Controls-tab slider
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        inputControlsView.setVisualStyle(VisualStyle.GAMEHUB);


        startTouchscreenTimeout();

        // Inside onCreate(), after initializing controls
        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        if (isTimeoutEnabled) {
            startTouchscreenTimeout();
        }

        // Reseed the in-game drawer's HUD state from the now-loaded container config.
        // The early seed in setupUI runs before `container` is assigned, so it defaults
        // to classic; without this the drawer shows classic toggles even when the
        // container (and the live overlay below) are configured for the GameHub HUD.
        if (container != null) XServerDrawerState.INSTANCE.setFpsConfig(container.getFPSCounterConfig());

        if (container != null && container.isShowFPS()) {
            String fpsConfigString = container.getFPSCounterConfig();
            com.winlator.star.core.KeyValueSet fpsConfig = new com.winlator.star.core.KeyValueSet(fpsConfigString);
            fpsHudHorizontal = fpsConfig.get("hudMode", "vertical").equals("horizontal");
            boolean gameHubHud = fpsConfig.get("hudStyle", "classic").equals("gamehub");

            String resolvedR = resolvedRenderer();
            String rendererMode = "vulkan".equals(resolvedR) ? "Vulkan"
                : "surfaceflinger".equals(resolvedR) ? "SurfaceFlinger" : "OpenGL";
            String dxName = dxwrapper.contains("dxvk") ? "DXVK" : dxwrapper.contains("vegas") ? "VEGAS" : "WineD3D";
            hudRendererLabel = rendererMode + " | " + dxName;
            hudEngineShort = dxName;

            // Build whichever HUD the container selected. The other style is created on demand
            // if the user swaps hudStyle in the in-game drawer (see buildPerfHud/buildClassicHud).
            if (gameHubHud) buildPerfHud(fpsConfigString);
            else buildClassicHud(fpsConfigString);

            // The label above is the configured D3D9/10/11 wrapper; probe what the game actually
            // loads and upgrade it to VKD3D for D3D12 titles (or confirm the wrapper for D3D11).
            startDxApiDetection(rendererMode + " | ", dxName);
        }

        // Get the fullscreen stretched extra from the shortcut if available
        String shortcutFullscreenStretched = shortcut != null ? shortcut.getExtra("fullscreenStretched") : null;

        // Proceed based on container and shortcut settings
        boolean shouldStretch = false;

        if (shortcut != null && shortcutFullscreenStretched != null) {
            // Shortcut exists and has a valid setting
            shouldStretch = shortcutFullscreenStretched.equals("1");
        } else if (container != null && container.isFullscreenStretched()) {
            // No shortcut or shortcut doesn't override, use the container's setting
            shouldStretch = true;
        }

        if (shouldStretch) {
            // Toggle fullscreen mode based on the final decision
            renderer.toggleFullscreen();
            touchpadView.toggleFullscreen();
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }

            String simTouchScreen = shortcut.getExtra("simTouchScreen");
            touchpadView.setSimTouchScreen(simTouchScreen.equals("1"));
        }

        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);

        // Initialize inline tab states (Graphics, Controls, HUD)
        initInlineTabStates(renderer);
    }

    // Vulkan preset <-> Native Rendering mutual exclusion (the two presets cannot coexist: native
    // direct scanout bypasses the compositor post pass where all presets live).

    /** Direction A: a preset was enabled, so turn Native Rendering off. Guarded — no-op (and no
     *  repeated toast) when native is already off, since screen-effect sliders fire continuously. */
    private void disableNativeRenderingForPreset() {
        if (!XServerDrawerState.INSTANCE.getNativeRenderingEnabled()) return;
        HostRenderer r = xServerView.getRenderer();
        if (r instanceof com.winlator.star.renderer.vulkan.VulkanRenderer)
            ((com.winlator.star.renderer.vulkan.VulkanRenderer) r).setNativeMode(false);
        else if (r instanceof GLRenderer)
            ((GLRenderer) r).setNativeMode(false); // GL direct scanout bypasses the EffectComposer too
        XServerDrawerState.INSTANCE.setNativeRenderingEnabled(false); // flips the toggle UI off
        showToast(this, "Native Rendering off — needed for post-processing");
    }

    /** Direction B: Native Rendering was enabled, so reset every Vulkan preset to neutral so the
     *  drawer is truthful. Only touches renderer setters + StateFlows — never the apply callbacks,
     *  so this cannot re-enter disableNativeRenderingForPreset(). */
    private void resetVulkanPresets(com.winlator.star.renderer.vulkan.VulkanRenderer vkr) {
        XServerDialogState ds = XServerDialogState.INSTANCE;
        vkr.setUpscaler(0);                          ds.setUpscalerMode(0);
        vkr.setCas(false, ds.getCasSharpness().getValue()); ds.setCasEnabled(false);
        vkr.setHdr(false);                           ds.setHdrVkEnabled(false);
        vkr.setScreenEffects(0f, 0f, 1.0f, false, false, false, false);
        ds.setVkBrightness(0f); ds.setVkContrast(0f); ds.setVkGamma(1.0f);
        ds.setVkFxaa(false); ds.setVkToon(false); ds.setVkCrt(false); ds.setVkNtsc(false);
    }

    /** Direction B (GL): GL Native Rendering (direct scanout) bypasses the entire GL EffectComposer
     *  chain + the GL spatial upscalers/scaling modes, so enabling native resets every GL effect to
     *  neutral so the drawer is truthful (no toggles left "on" doing nothing while bypassed). Mirrors
     *  resetVulkanPresets(): only touches the EffectComposer + StateFlows — never the apply callbacks,
     *  so this cannot re-enter disableNativeRenderingForPreset(). */
    private void resetGlEffectsForNative(GLRenderer glr) {
        XServerDialogState ds = XServerDialogState.INSTANCE;
        EffectComposer comp = glr.getEffectComposer();
        // Scaling mode -> None (linear base sampler), default sharpness.
        glr.setFilterMode(1);
        comp.setUpscaler(0);
        ds.setGlUpscalerMode(0);
        ds.setGlUpscaleSharpness(75);
        // SGSR/CAS sharpen (FSREffect) + HDR off — remove the effects (mirrors onSgsrUpdate teardown).
        com.winlator.star.renderer.effects.FSREffect fsr =
            comp.getEffect(com.winlator.star.renderer.effects.FSREffect.class);
        if (fsr != null) comp.removeEffect(fsr);
        HDREffect hdr = comp.getEffect(HDREffect.class);
        if (hdr != null) comp.removeEffect(hdr);
        ds.setSgsrEnabled(false); ds.setSgsrSharpness(50); ds.setHdrEnabled(false);
        // Screen effects: color grade neutral + FXAA/CRT/Toon/NTSC off.
        applyScreenEffects(glr, 0f, 0f, 1.0f, false, false, false, false);
        ds.setSeBrightness(0f); ds.setSeContrast(0f); ds.setSeGamma(1.0f);
        ds.setSeFxaa(false); ds.setSeCrt(false); ds.setSeToon(false); ds.setSeNtsc(false);
        // Terminal debanding off.
        comp.setDeband(false, 100);
        ds.setDebandEnabled(false); ds.setDebandStrength(100);
    }

    private void initInlineTabStates(HostRenderer renderer) {
        // SGSR/HDR/screen-effect shaders are GL EffectComposer features; the Vulkan renderer has no
        // post-process pipeline, so their callbacks below are never set. Flag it so the drawer grays
        // those toggles out instead of showing dead switches.
        XServerDialogState.INSTANCE.setEffectsSupported(renderer instanceof GLRenderer);
        XServerDialogState ds = XServerDialogState.INSTANCE;

        // Scaling mode (spatial upscaler) is a Vulkan-only control — the inverse of the GL-only
        // effects above. Flag it for the drawer gate and wire the apply callback here, BEFORE the
        // GL-only early return below, so it works on the Vulkan renderer. setUpscaler covers
        // modes 0..5 and drives the base sampler filter for modes 1/2 (single source of truth).
        boolean vulkanActive = renderer instanceof com.winlator.star.renderer.vulkan.VulkanRenderer;
        ds.setVulkanSupported(vulkanActive);
        if (vulkanActive) {
            com.winlator.star.renderer.vulkan.VulkanRenderer vkr =
                (com.winlator.star.renderer.vulkan.VulkanRenderer) renderer;
            // Direction A: enabling any preset that lives in the compositor post pass turns Native
            // Rendering OFF (it bypasses that pass). disableNativeRenderingForPreset() is guarded so
            // it's a no-op (and no repeated toast) when native is already off — important because
            // onVulkanScreenEffectsApply fires continuously during slider drags.
            ds.onUpscalerApply = (mode) -> {
                if (mode >= 3) disableNativeRenderingForPreset(); // 3=SGSR 4=FSR 5=FSR-Fit 6=Sharpen
                vkr.setUpscaler(mode);
            };
            ds.onCasApply = (enabled, sharpness) -> {
                if (enabled) disableNativeRenderingForPreset();
                vkr.setCas(enabled, sharpness);
            };
            ds.onHdrApply = (enabled) -> {
                if (enabled) disableNativeRenderingForPreset();
                vkr.setHdr(enabled);
            };
            // Terminal debanding (TPDF dither) — runs in the compositor post pass, so enabling
            // it (like CAS/HDR) turns Native Rendering off. Default off; seed the drawer state.
            ds.setDebandEnabled(false);
            ds.setDebandStrength(100);
            ds.onDebandApply = (enabled, strength) -> {
                if (enabled) disableNativeRenderingForPreset();
                vkr.setDeband(enabled, strength);
            };
            ds.onUpscaleSharpnessApply = (sharpness) -> vkr.setUpscaleSharpness(sharpness);
            ds.onVulkanScreenEffectsApply = (brightness, contrast, gamma, fxaa, toon, crt, ntsc) -> {
                // color grade neutral = brightness 0 / contrast 0 / gamma 1.0
                if (fxaa || toon || crt || ntsc || brightness != 0f || contrast != 0f || gamma != 1.0f)
                    disableNativeRenderingForPreset();
                vkr.setScreenEffects(brightness, contrast, gamma, fxaa, toon, crt, ntsc);
            };
        } else {
            ds.onUpscalerApply = null;
            ds.onCasApply = null;
            ds.onHdrApply = null;
            ds.onDebandApply = null;
            ds.onUpscaleSharpnessApply = null;
            ds.onVulkanScreenEffectsApply = null;
        }

        // ReShade (vkBasalt) drawer state — renderer-agnostic (the layer hooks the guest Vulkan
        // swapchain, not our host renderer), gated only on DXVK/VKD3D. Seeded HERE, in setupUI,
        // because `container`/`shortcut` are assigned by now (seeding in onCreate would capture a
        // null effect). Live-apply rides the single onReshadeApply -> applyReshadeLive seam.
        seedReshadeDrawerState(ds);
        ds.onReshadeApply = (enabled, values) -> applyReshadeLive(enabled, values);

        // Input Controls state (renderer-independent: controller profiles + vibration work on
        // BOTH the GL and Vulkan host renderers, so this must run before the GL-only guard below.
        // Previously the early return for non-GL renderers left the profile dropdown empty.)
        ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
        ArrayList<String> profileNames = new ArrayList<>();
        int selectedPosition = 0;
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (inputControlsView.getProfile() != null && profile.id == inputControlsView.getProfile().id)
                selectedPosition = i + 1;
            profileNames.add(profile.getName());
        }
        ds.setInputProfiles(profileNames);
        ds.setSelectedProfileIdx(selectedPosition);
        ds.setShowTouchscreen(inputControlsView.isShowTouchscreenControls());
        ds.setTimeoutEnabled(preferences.getBoolean("touchscreen_timeout_enabled", false));
        ds.setHapticsEnabled(preferences.getBoolean("touchscreen_haptics_enabled", false));
        // Seed the Controls-tab accent toggle/picker from the ACTIVE profile (the one bound to the
        // running game via showInputControls, set before this runs).
        seedControlsColorState();

        ds.onInputControlsConfirm = (profileIndex, showTouchscreen, timeout, haptics) -> {
            inputControlsView.setShowTouchscreenControls(showTouchscreen);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("touchscreen_timeout_enabled", timeout);
            editor.putBoolean("touchscreen_haptics_enabled", haptics);
            editor.apply();
            if (timeout) startTouchscreenTimeout();
            else touchpadView.setOnTouchListener(null);
            if (profileIndex > 0) showInputControls(inputControlsManager.getProfiles().get(profileIndex - 1));
            else hideInputControls();
            // The active profile may have changed — re-seed the accent toggle/picker so the Controls
            // tab reflects the newly-selected profile's saved accent.
            seedControlsColorState();
        };

        ds.onInputControlsSettings = () -> {
            int currentIdx = ds.getSelectedProfileIdx().getValue();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id",
                currentIdx > 0 ? inputControlsManager.getProfiles().get(currentIdx - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
            };
            controlsEditorActivityResultLauncher.launch(intent);
        };

        // Vibration state
        if (winHandler != null) {
            int max = winHandler.getMaxControllers();
            java.util.List<kotlin.Pair<String, Boolean>> kSlots = new java.util.ArrayList<>();
            for (int i = 0; i < max; i++) {
                kSlots.add(new kotlin.Pair<>(
                    getString(com.winlator.star.R.string.vibration_slot, i + 1),
                    winHandler.isVibrationEnabledForSlot(i)));
            }
            ds.setVibrationSlots(kSlots);
            ds.onVibrationSlotChanged = (slot, enabled) -> winHandler.setVibrationEnabledForSlot(slot, enabled);
        }

        // Task Manager actions (End Process / Bring to Front / New Task / Set Affinity) are
        // renderer-independent (UDP to winhandler.exe + host X-server focus). They MUST be wired
        // before the GL-only early return below — otherwise on the Vulkan/ASR renderers the
        // ds.onTm* callbacks stay null and the drawer's `onTm...?.invoke()` is a silent no-op,
        // so End Process / Bring to Front "do nothing" (the process list still populates because
        // startTmPolling() registers its own listener). This was the root cause of the Vulkan/ASR
        // Task Manager bug.
        setupTmCallbacks();

        // Screen Effects / SGSR / HDR are GL EffectComposer features; the Vulkan renderer has no
        // post-process pipeline, so bail out here — AFTER the renderer-independent setup above.
        if (!(renderer instanceof GLRenderer)) return;
        GLRenderer glRenderer = (GLRenderer) renderer;

        // Screen Effects state
        ColorEffect ce   = (ColorEffect)        glRenderer.getEffectComposer().getEffect(ColorEffect.class);
        FXAAEffect  fxaa = (FXAAEffect)         glRenderer.getEffectComposer().getEffect(FXAAEffect.class);
        CRTEffect   crt  = (CRTEffect)          glRenderer.getEffectComposer().getEffect(CRTEffect.class);
        ToonEffect  toon = (ToonEffect)         glRenderer.getEffectComposer().getEffect(ToonEffect.class);
        NTSCCombinedEffect ntsc = (NTSCCombinedEffect) glRenderer.getEffectComposer().getEffect(NTSCCombinedEffect.class);

        ds.setSeBrightness(ce   != null ? ce.getBrightness() * 100f : 0f);
        ds.setSeContrast  (ce   != null ? ce.getContrast()   * 100f : 0f);
        ds.setSeGamma     (ce   != null ? ce.getGamma()             : 1.0f);
        ds.setSeFxaa      (fxaa != null);
        ds.setSeCrt       (crt  != null);
        ds.setSeToon      (toon != null);
        ds.setSeNtsc      (ntsc != null);

        java.util.Set<String> rawSet = new java.util.LinkedHashSet<>(
            preferences.getStringSet("screen_effect_profiles", new java.util.LinkedHashSet<>()));
        final ArrayList<String> seProfileNames = new ArrayList<>();
        for (String p : rawSet) seProfileNames.add(p.split(":")[0]);
        ds.setSeProfiles(seProfileNames);
        String currentProfile = getScreenEffectProfile();
        int selIdx = 0;
        for (int i = 0; i < seProfileNames.size(); i++) {
            if (seProfileNames.get(i).equals(currentProfile)) { selIdx = i + 1; break; }
        }
        ds.setSeSelectedProfile(selIdx);

        ds.onScreenEffectsApply = (brightness, contrast, gamma, fxaaEn, crtEn, toonEn, ntscEn, profileIndex) -> {
            if (glRenderer == null) return;
            // Direction A: any non-neutral screen effect runs in the EffectComposer, which GL native
            // bypasses — so engaging one turns Native Rendering off (guarded; no-op when already off).
            if (fxaaEn || crtEn || toonEn || ntscEn || brightness != 0f || contrast != 0f || gamma != 1.0f)
                disableNativeRenderingForPreset();
            applyScreenEffects(glRenderer, brightness, contrast, gamma, fxaaEn, crtEn, toonEn, ntscEn);
            if (profileIndex > 0 && profileIndex - 1 < seProfileNames.size()) {
                String name = seProfileNames.get(profileIndex - 1);
                saveScreenEffectProfile(name, brightness, contrast, gamma, fxaaEn, crtEn, toonEn, ntscEn);
                setScreenEffectProfile(name);
            }
        };

        ds.onInitGraphicsTab = () -> {};

        // SGSR state
        HDREffect hdr = (HDREffect) glRenderer.getEffectComposer().getEffect(HDREffect.class);
        ds.setSgsrEnabled(false);
        ds.setSgsrSharpness(50);
        ds.setHdrEnabled(hdr != null);

        ds.onSgsrUpdate = (enabled, sharpness, hdrEn) -> {
            if (glRenderer == null) return;
            // Direction A: CAS sharpen / HDR are EffectComposer post passes that GL native bypasses.
            if (enabled || hdrEn) disableNativeRenderingForPreset();
            com.winlator.star.renderer.effects.FSREffect cur = (com.winlator.star.renderer.effects.FSREffect) glRenderer.getEffectComposer().getEffect(com.winlator.star.renderer.effects.FSREffect.class);
            if (cur != null) glRenderer.getEffectComposer().removeEffect(cur);
            // The drawer snaps this slider to 5 stops {0,25,50,75,100}; stop 0 = OFF (no CAS
            // pass, passthrough), so only sharpness > 0 adds the effect.
            if (enabled && sharpness > 0) {
                com.winlator.star.renderer.effects.FSREffect newFsr = new com.winlator.star.renderer.effects.FSREffect();
                // FSREffect level scale is inverted (level 1 = sharpest, level 5 = softest);
                // map the 0..100 "Sharpness" slider so higher = sharper -> lower level.
                newFsr.setLevel((100.0f - (float)sharpness) / 25.0f + 1.0f);
                newFsr.setMode(com.winlator.star.renderer.effects.FSREffect.MODE_SUPER_RESOLUTION);
                glRenderer.getEffectComposer().addEffect(newFsr);
            }
            HDREffect curHdr = (HDREffect) glRenderer.getEffectComposer().getEffect(HDREffect.class);
            if (curHdr != null) glRenderer.getEffectComposer().removeEffect(curHdr);
            if (hdrEn) {
                HDREffect newHdr = new HDREffect();
                newHdr.setStrength(1.0f);
                glRenderer.getEffectComposer().addEffect(newHdr);
            }
        };

        // GL "Scaling mode" (real SGSR / FSR1 spatial upscalers) — parity with the Vulkan
        // picker; drawer-only / session-live, default None. Seed the drawer state + a default
        // sharpness, prime the composer, and wire the apply callbacks. GL renderer only (this
        // method already returned for non-GL above), so these never fire on Vulkan/ASR.
        // Seed the picker to match the base sampler filter the launch already applied
        // (container filter mode), mirroring the Vulkan seed: Nearest -> 2, else Linear (1).
        int glSeedMode = (container != null && container.getRendererFilterMode() == 2) ? 2 : 1;
        ds.setGlUpscalerMode(glSeedMode);
        ds.setGlUpscaleSharpness(75);
        glRenderer.getEffectComposer().setUpscaler(glSeedMode, 0.75f);
        ds.onGlUpscalerApply = (mode) -> {
            if (glRenderer == null) return;
            // Direction A: a spatial scaling mode lives in the EffectComposer low-res stage, which
            // GL native (direct scanout) bypasses — so engaging one turns Native Rendering off.
            // Guarded inside disableNativeRenderingForPreset(), so this no-ops when native is already
            // off (and the drawer greys these controls out while native is on, so it rarely fires).
            if (mode >= 3) disableNativeRenderingForPreset(); // 3=SGSR 4=FSR 5=FSR-Fit 6=Sharpen 7=NIS
            // None/Linear/spatial/sharpen -> linear base sampler; Nearest -> point.
            glRenderer.setFilterMode(mode == 2 ? 2 : 1);
            glRenderer.getEffectComposer().setUpscaler(mode); // keeps the current sharpness
        };
        ds.onGlUpscaleSharpnessApply = (sharpness) -> {
            if (glRenderer == null) return;
            glRenderer.getEffectComposer().setUpscaleSharpness(sharpness / 100.0f);
        };

        // GL terminal debanding (TPDF dither) — drawer-only / session-live, default off.
        ds.setDebandEnabled(false);
        ds.setDebandStrength(100);
        ds.onDebandApply = (enabled, strength) -> {
            if (glRenderer == null) return;
            // Direction A: terminal debanding is a final EffectComposer pass that GL native bypasses.
            if (enabled) disableNativeRenderingForPreset();
            glRenderer.getEffectComposer().setDeband(enabled, strength);
        };

        // NOTE: setupTmCallbacks() is intentionally called earlier (before the GL-only early
        // return) so the Task Manager actions work on every renderer. Do not move it back here.
    }



    private ActivityResultLauncher<Intent> controlsEditorActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (editInputControlsCallback != null) {
                    editInputControlsCallback.run();
                    editInputControlsCallback = null;
                }
            }
    );

    private String parseShortcutNameFromDesktopFile(File desktopFile) {
        String shortcutName = "";
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Name=")) {
                        shortcutName = line.split("=")[1].trim();
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e("XServerDisplayActivity", "Error reading shortcut name from .desktop file", e);
            }
        }
        return shortcutName;
    }

    private void setTextColorForDialog(ViewGroup viewGroup, int color) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                // If the child is a ViewGroup, recursively apply the color
                setTextColorForDialog((ViewGroup) child, color);
            } else if (child instanceof TextView) {
                // If the child is a TextView, set its text color
                ((TextView) child).setTextColor(color);
            }
        }
    }

    private void showInputControlsDialog() {
        ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
        ArrayList<String> profileNames = new ArrayList<>();
        int selectedPosition = 0;
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (inputControlsView.getProfile() != null && profile.id == inputControlsView.getProfile().id)
                selectedPosition = i + 1;
            profileNames.add(profile.getName());
        }

        XServerDialogState ds = XServerDialogState.INSTANCE;
        ds.setInputProfiles(profileNames);
        ds.setSelectedProfileIdx(selectedPosition);
        ds.setShowTouchscreen(inputControlsView.isShowTouchscreenControls());
        ds.setTimeoutEnabled(preferences.getBoolean("touchscreen_timeout_enabled", false));
        ds.setHapticsEnabled(preferences.getBoolean("touchscreen_haptics_enabled", false));
        // Seed the Controls-tab accent toggle/picker from the ACTIVE profile (the one bound to the
        // running game via showInputControls, set before this runs).
        seedControlsColorState();

        ds.onInputControlsConfirm = (profileIndex, showTouchscreen, timeout, haptics) -> {
            inputControlsView.setShowTouchscreenControls(showTouchscreen);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("touchscreen_timeout_enabled", timeout);
            editor.putBoolean("touchscreen_haptics_enabled", haptics);
            editor.apply();
            if (timeout) startTouchscreenTimeout();
            else touchpadView.setOnTouchListener(null);
            if (profileIndex > 0) showInputControls(inputControlsManager.getProfiles().get(profileIndex - 1));
            else hideInputControls();
            // The active profile may have changed — re-seed the accent toggle/picker so the Controls
            // tab reflects the newly-selected profile's saved accent.
            seedControlsColorState();
        };

        ds.onInputControlsSettings = () -> {
            int currentIdx = ds.getSelectedProfileIdx().getValue();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id",
                currentIdx > 0 ? inputControlsManager.getProfiles().get(currentIdx - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
            };
            controlsEditorActivityResultLauncher.launch(intent);
        };

        ds.show(XServerDialogState.ActiveDialog.INPUT_CONTROLS);
    }

    private void simulateConfirmInputControlsDialog() {
        // Simulate setting the relative mouse movement and touchscreen controls from preferences

        boolean isShowTouchscreenControls = preferences.getBoolean("show_touchscreen_controls_enabled", false); // default is false (hidden)
        inputControlsView.setShowTouchscreenControls(isShowTouchscreenControls);

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        boolean isHapticsEnabled = preferences.getBoolean("touchscreen_haptics_enabled", false);

        // Apply these settings as if the user confirmed the dialog
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
        editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
        editor.apply();

        // If no profile is selected, hide the controls
        int selectedProfileIndex = preferences.getInt("selected_profile_index", -1); // Default to -1 for no profile

        if (selectedProfileIndex >= 0 && selectedProfileIndex < inputControlsManager.getProfiles().size()) {
            // A profile is selected, show the controls
            ControlsProfile profile = inputControlsManager.getProfiles().get(selectedProfileIndex);
            showInputControls(profile);
        } else {
            // No profile selected, ensure the controls are hidden
            hideInputControls();
        }

        // Timeout logic should only apply if the controls are visible
        if (isTimeoutEnabled && inputControlsView.getVisibility() == View.VISIBLE) {
            startTouchscreenTimeout(); // Start timeout if enabled and controls are visible
        } else {
            touchpadView.setOnTouchListener(null); // Disable the timeout listener if not needed
        }

        Log.d("XServerDisplayActivity", "Input controls simulated confirmation executed.");
    }

    private void startTouchscreenTimeout() {
        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);

        if (isTimeoutEnabled) {
            // Show controls initially and set up touch event listeners
            inputControlsView.setVisibility(View.VISIBLE);
            Log.d("XServerDisplayActivity", "Timeout is enabled, setting up timeout logic.");

            // Attach the OnTouchListener to reset the timeout on touch events
            touchpadView.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    // Reset the timeout on any touch event
                    //Log.d("XServerDisplayActivity", "Touch detected, resetting timeout.");

                    // Keep the controls visible
                    inputControlsView.setVisibility(View.VISIBLE);

                    // Remove any pending hide callbacks and reset the timeout
                    timeoutHandler.removeCallbacks(hideControlsRunnable);
                    timeoutHandler.postDelayed(hideControlsRunnable, 5000); // Reset timeout
                }

                return false; // Allow the touch event to propagate
            });

            // Reset the timeout when the controls are initially displayed
            timeoutHandler.removeCallbacks(hideControlsRunnable);
            timeoutHandler.postDelayed(hideControlsRunnable, 5000); // Hide after 5 seconds of inactivity
        } else {
            // If timeout is disabled, keep the controls always visible
            Log.d("XServerDisplayActivity", "Timeout is disabled, controls will stay visible.");

            inputControlsView.setVisibility(View.VISIBLE); // Ensure controls are visible
            timeoutHandler.removeCallbacks(hideControlsRunnable); // Remove any existing hide callbacks
            touchpadView.setOnTouchListener(null); // Remove the touch listener
        }
    }

    // Push the active profile's per-profile controls accent (follow-theme + custom color) into the
    // in-game drawer state so the Controls-tab toggle/picker reflect it. Defaults (follow theme,
    // app blue) when no profile is active. The drawer's onControlsColorChange writes back.
    private void seedControlsColorState() {
        ControlsProfile profile = inputControlsView != null ? inputControlsView.getProfile() : null;
        if (profile != null) {
            XServerDrawerState.INSTANCE.setControlsFollowTheme(!profile.isCustomAccentEnabled());
            XServerDrawerState.INSTANCE.setControlsAccentColor(profile.getCustomAccentColor());
        }
        else {
            XServerDrawerState.INSTANCE.setControlsFollowTheme(true);
            XServerDrawerState.INSTANCE.setControlsAccentColor(0xFF0055FF);
        }
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        inputControlsView.invalidate();
        winHandler.sendGamepadState();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        inputControlsView.invalidate();
        winHandler.sendGamepadState();
    }

    private void extractGraphicsDriverFiles() {
    // 1. Retrieve the selected driver name from the config
    String selectedDriver = graphicsDriverConfig.get("graphicsDriver");
    if (selectedDriver == null) selectedDriver = graphicsDriverConfig.get("graphics_driver");
    
    String adrenoToolsDriverId = graphicsDriverConfig.get("version");

    Log.d("GraphicsDriverExtraction", "Selected Driver from Config: " + selectedDriver);
    Log.d("GraphicsDriverExtraction", "Adrenotools DriverID: " + adrenoToolsDriverId);

    File rootDir = imageFs.getRootDir();

    // Perform wrapper extraction based on selected version
    if (graphicsDriver.startsWith("wrapper-original")) {
        Log.d("GraphicsDriverExtraction", "Extracting: graphics_driver/wrapper-original.tzst");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper-original.tzst", rootDir);
    } 
    else if (graphicsDriver.startsWith("wrapper-leegao")) {
        Log.d("GraphicsDriverExtraction", "Extracting: graphics_driver/wrapper-leegao.tzst");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper-leegao.tzst", rootDir);
    } 
    else if (graphicsDriver.startsWith("wrapper-legacy")) {
        Log.d("GraphicsDriverExtraction", "Extracting: graphics_driver/wrapper-legacy.tzst");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper-legacy.tzst", rootDir);
    }
    else if (graphicsDriver.startsWith("wrapper-gamenative")) {
        Log.d("GraphicsDriverExtraction", "Extracting: graphics_driver/wrapper-gamenative.tzst");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper-gamenative.tzst", rootDir);
    }

    // Original logic for DXWrapper and environment variables
    if (dxwrapper.contains("dxvk")) {
        DXVKConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
        String version = dxwrapperConfig.get("version");
        if (version != null && version.equals("1.11.1-sarek")) {
            Log.d("GraphicsDriverExtraction", "Disabling Wrapper PATCH_OPCONSTCOMP SPIR-V pass");
            envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
        }
    }
    else if (dxwrapper.contains("vegas")) {
        DXVKConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
    }
    else {
        WineD3DConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
    }

    boolean useDRI3 = preferences.getBoolean("use_dri3", true);
    if (!useDRI3) {
        envVars.put("MESA_VK_WSI_DEBUG", "sw");
    }

    envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/wrapper_icd.aarch64.json");
    envVars.put("GALLIUM_DRIVER", "zink");

    // 2. SHARED LIBS EXTRACTION
    // First boot extracts everything. After that, extra_libs.tzst carries the vkBasalt layer
    // (libvkbasalt.so + the implicit_layer.d manifest) that powers the CAS/DLS sharpness AND the
    // ReShade feature — containers created before that bundle landed never got it, so the feature
    // silently no-ops on them. Re-extract extra_libs whenever the layer .so is missing from this
    // container's rootDir, so pre-existing containers heal on next launch. Cheap: a single exists()
    // check guards the (one-time, idempotent) re-extraction.
    File vkBasaltSo = new File(imageFs.getLibDir(), "libvkbasalt.so");
    if (firstTimeBoot) {
        Log.d("XServerDisplayActivity", "First time container boot, re-extracting layers and extra_libs");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "layers" + ".tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs.tzst", rootDir);
    }
    else if (!vkBasaltSo.exists()) {
        Log.d("XServerDisplayActivity", "vkBasalt layer absent (pre-existing container) — re-extracting extra_libs");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs.tzst", rootDir);
    }

    // 3. Driver integration.
    //
    // Two mutually-exclusive turnip delivery modes share the "version" slot:
    //   (a) Direct Vulkan ICD (turnip-26.1.0): point the Vulkan loader straight at the Mesa
    //       turnip ICD and DO NOT touch the ADRENOTOOLS_* env. This avoids the adrenotools /
    //       linkernsbypass linker-namespace hook (which needs ~Android 11+) and is the only
    //       turnip that works on Android < 11 (SD845/Adreno 630 / Android 10 reporter, #18).
    //   (b) Everything else (turnip-sdk36, v819, custom-installed): adrenotools as before.
    boolean directIcdTurnip = DefaultVersion.WRAPPER_TURNIP_ICD.equals(adrenoToolsDriverId);
    if (directIcdTurnip) {
        installDirectIcdTurnip();
        // VK_ICD_FILENAMES uses host-side absolute paths in this fork (the guest program runs
        // with host LD_LIBRARY_PATH=<rootDir>/usr/lib:/system/lib64, see
        // GuestProgramLauncherComponent), so the loader reads this manifest off the host fs,
        // exactly like wrapper_icd above. Override wrapper_icd -> freedreno_icd.
        envVars.put("VK_ICD_FILENAMES",
            new File(imageFs.getShareDir(), "vulkan/icd.d/freedreno_icd.aarch64.json").getAbsolutePath());
        // NOTE: deliberately NO ADRENOTOOLS_* env here — that hook is the whole reason this
        // mode exists. Leaving them unset keeps the loader on the plain-ICD path.
    }
    else if (adrenoToolsDriverId != null && !adrenoToolsDriverId.equals("System")) {
        AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(this);
        adrenotoolsManager.setDriverById(envVars, imageFs, adrenoToolsDriverId);
    }

    // --- Environment Variable Setup ---
    String vulkanVersion = graphicsDriverConfig.get("vulkanVersion");
    // The direct-ICD turnip is not an adrenotools driver, so the native getVulkanVersion()
    // probe can't describe it and may return a non-dotted string -> split(".")[2] would crash.
    // turnip-26.1.0's ICD manifest advertises api_version 1.4.318, so use that patch directly.
    String vulkanVersionPatch = directIcdTurnip
        ? "318"
        : GPUInformation.getVulkanVersion(adrenoToolsDriverId, this).split("\\.")[2];
    vulkanVersion = (vulkanVersion != null ? vulkanVersion : "1.3") + "." + vulkanVersionPatch;
    envVars.put("WRAPPER_VK_VERSION", vulkanVersion);

    String blacklistedExtensions = graphicsDriverConfig.get("blacklistedExtensions");
    envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions != null ? blacklistedExtensions : "");

    String gpuName = graphicsDriverConfig.get("gpuName");
    String dxvkVersion = dxwrapperConfig.get("version");
    if (gpuName != null && !gpuName.equals("Device") && dxvkVersion != null && !dxvkVersion.equals("1.11.1-sarek")) {
        envVars.put("WRAPPER_DEVICE_NAME", gpuName);
        envVars.put("WRAPPER_DEVICE_ID", WineD3DConfigDialog.getDeviceIdFromGPUName(this, gpuName));
        envVars.put("WRAPPER_VENDOR_ID", WineD3DConfigDialog.getVendorIdFromGPUName(this, gpuName));
    }

    String maxDeviceMemory = graphicsDriverConfig.get("maxDeviceMemory");
    if (maxDeviceMemory != null && Integer.parseInt(maxDeviceMemory) > 0)
        envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory);
    
    String presentMode = graphicsDriverConfig.get("presentMode");
    if (presentMode != null) {
        if (presentMode.contains("immediate")) {
            envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1");
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);
    }

    String resourceType = graphicsDriverConfig.get("resourceType");
    if (resourceType != null) envVars.put("WRAPPER_RESOURCE_TYPE", resourceType);

    String syncFrame = graphicsDriverConfig.get("syncFrame");
    if (syncFrame != null && syncFrame.equals("1"))
        envVars.put("MESA_VK_WSI_DEBUG", "forcesync");

    String disablePresentWait = graphicsDriverConfig.get("disablePresentWait");
    if (disablePresentWait != null) envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait);

    String bcnEmulation = graphicsDriverConfig.get("bcnEmulation");
    String bcnEmulationType = graphicsDriverConfig.get("bcnEmulationType");

    if (bcnEmulation != null) {
        switch (bcnEmulation) {
            case "auto" -> {
                if ("compute".equals(bcnEmulationType) && GPUInformation.getVendorID(null, null) != 0x5143) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "1");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "3");
            }
            case "full" -> {
                if ("compute".equals(bcnEmulationType) && GPUInformation.getVendorID(null, null) != 0x5143) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "0");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "2");
            }
            case "none" -> envVars.put("WRAPPER_EMULATE_BCN", "0");
            default -> envVars.put("WRAPPER_EMULATE_BCN", "1");
        }
    }

    String bcnEmulationCache = graphicsDriverConfig.get("bcnEmulationCache");
    if (bcnEmulationCache != null) envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache);

    String fdDevFeatures = graphicsDriverConfig.get("fdDevFeatures");
    if (fdDevFeatures != null && fdDevFeatures.equals("1"))
        envVars.put("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1");

    // ReShade (vkBasalt) — when a per-game/container effect is selected, write ONE merged conf
    // file that also folds in the CAS/DLS sharpness chain, and point the layer at it via the config
    // FILE env. When no ReShade effect is selected, fall back to the legacy inline CAS path
    // UNCHANGED (the existing sharpness feature keeps working exactly as before).
    String reshadeConf = writeVkBasaltConfig();
    if (reshadeConf != null) {
        envVars.put("ENABLE_VKBASALT", "1");
        envVars.put("VKBASALT_CONFIG_FILE", reshadeConf);
    }
    else if (vkbasaltConfig != null && !vkbasaltConfig.isEmpty()) {
        envVars.put("ENABLE_VKBASALT", "1");
        envVars.put("VKBASALT_CONFIG", vkbasaltConfig);
    }
}
    
    
    // Installs the Mesa Turnip 26.1.0 driver as a plain Vulkan ICD (issue #18) and rewrites the
    // ICD manifest's library_path to a path the loader can actually resolve here.
    //
    // The Winlator asset hardcodes library_path to "/data/data/com.winlator/files/rootfs/lib/
    // libvulkan_freedreno.so" (its own package + a "rootfs" layout we don't use), so it MUST be
    // rewritten. We point it at the absolute HOST path imageFs.getLibDir()/libvulkan_freedreno.so,
    // because in this fork the Vulkan loader runs with host LD_LIBRARY_PATH = <rootDir>/usr/lib:
    // /system/lib64 (GuestProgramLauncherComponent) and VK_ICD_FILENAMES itself is a host
    // getShareDir() path — i.e. the existing wrapper_icd resolves its library off the host fs in
    // exactly the same <rootDir>/usr/lib directory. The absolute form is unambiguous (no reliance
    // on search order, and won't accidentally bind /system/lib64).
    //
    // This runs AFTER the firstTimeBoot extra_libs.tzst extraction above, so the (newer,
    // universal a6xx/a7xx/a8xx) 26.1.0 turnip wins over the dormant older freedreno that
    // extra_libs ships but nothing currently selects.
    private void installDirectIcdTurnip() {
        File rootDir = imageFs.getRootDir();
        Log.d("GraphicsDriverExtraction", "Extracting direct Vulkan ICD turnip: graphics_driver/turnip-26.1.0.tzst");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/turnip-26.1.0.tzst", rootDir);

        File soFile = new File(imageFs.getLibDir(), "libvulkan_freedreno.so");
        File icdJson = new File(imageFs.getShareDir(), "vulkan/icd.d/freedreno_icd.aarch64.json");
        try {
            JSONObject icd = new JSONObject();
            icd.put("api_version", "1.4.318");
            icd.put("library_arch", "64");
            icd.put("library_path", soFile.getAbsolutePath());
            JSONObject root = new JSONObject();
            root.put("ICD", icd);
            root.put("file_format_version", "1.0.1");
            icdJson.getParentFile().mkdirs();
            FileUtils.writeString(icdJson, root.toString());
            Log.d("GraphicsDriverExtraction", "Rewrote freedreno ICD library_path -> " + soFile.getAbsolutePath());
        }
        catch (JSONException e) {
            Log.e("GraphicsDriverExtraction", "Failed to rewrite freedreno ICD manifest", e);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        boolean handledByWinHandler = false;
        boolean handledByTouchpadView = false;

        // Let winHandler process the event if available
        if (winHandler != null) {
            handledByWinHandler = winHandler.onGenericMotionEvent(event);
            if (handledByWinHandler) {
                //Log.d("XServerDisplayActivity", "Event handled by winHandler");
            }
        }

        // Let touchpadView process the event if available
        if (touchpadView != null) {
            handledByTouchpadView = touchpadView.onExternalMouseEvent(event);
            if (handledByTouchpadView) {
                //Log.d("XServerDisplayActivity", "Event handled by touchpadView");
            }
        }

        // Pass the event to the super method to ensure system-level handling
        boolean handledBySuper = super.dispatchGenericMotionEvent(event);
        if (!handledBySuper) {
            //Log.d("XServerDisplayActivity", "Event not handled by super");
        }

        // Combine the results: any handler consuming the event indicates it was handled
        return handledByWinHandler || handledByTouchpadView || handledBySuper;
    }


    private static final int RECAPTURE_DELAY_MS = 10000; // 10 seconds

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {

        // Handle the PlayStation or Xbox Home button to open the drawer
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE || event.getKeyCode() == KeyEvent.KEYCODE_HOME || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_SELECT) {
                boolean handled = inputControlsView.onKeyEvent(event) || (winHandler != null && winHandler.onKeyEvent(event)) && (xServer != null && xServer.keyboard.onKeyEvent(event));
                return true;
            }
        }

        // Fallback to existing input handling
        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
                (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private static final String TAG = "DXWrapperExtraction";

    private boolean extractDXWrapperFiles(String dxwrapper) {
        final String[] dlls = {"d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll", "d3dimm.dll"};

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        if (dxwrapper.contains("dxvk")) {
            Log.d(TAG, "Extracting DXVK wrapper files, version: " + dxwrapper);

            String dxvkWrapper = dxwrapper.split(";")[0];
            String vkd3dWrapper = dxwrapper.split(";")[1];
            String ddrawrapper = dxwrapper.split(";")[2];
            
            ContentProfile dxvkProfile = contentsManager.getProfileByEntryName(dxvkWrapper);
            if (dxvkProfile != null) {
                Log.d(TAG, "Applying user-defined DXVK content profile: " + dxvkWrapper);
                contentsManager.applyContent(dxvkProfile);
            } else {
                Log.d(TAG, "Extracting fallback DXVK .tzst archive: " + dxvkWrapper);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + dxvkWrapper + ".tzst", windowsDir, onExtractFileListener);

                if (compareVersion(dxvkWrapper, "2.4") < 0) {
                    Log.d(TAG, "Extracting d8vk as part of DXVK version " + dxvkWrapper);
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-" + DefaultVersion.D8VK + ".tzst", windowsDir, onExtractFileListener);
                }
            }

            boolean vkd3dOk;
            if (vkd3dWrapper.contains("None")) {
                Log.d(TAG, "No VKD3D has been selected, restoring original d3d12");
                restoreOriginalDllFiles(new String[]{"d3d12.dll", "d3d12core.dll"});
                vkd3dOk = true;
            } else {
                ContentProfile vkd3dProfile = contentsManager.getProfileByEntryName(vkd3dWrapper);
                if (vkd3dProfile != null) {
                    Log.d(TAG, "Applying user-defined VKD3D content profile: " + vkd3dWrapper);
                    contentsManager.applyContent(vkd3dProfile);
                    vkd3dOk = true;
                } else {
                    Log.d(TAG, "Extracting fallback VKD3D .tzst archive: " + vkd3dWrapper);
                    vkd3dOk = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + vkd3dWrapper + ".tzst", windowsDir, onExtractFileListener);
                    if (!vkd3dOk) Log.e(TAG, "VKD3D extraction failed: " + vkd3dWrapper);
                }
            }
            if (!vkd3dOk) return false;

            Log.d(TAG, "Extracting nglide wrapper");
TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/nglide.tzst", windowsDir, onExtractFileListener);

if (ddrawrapper.contains("None")) {
    Log.d(TAG, "No DDRaw wrapper has been selected, restoring original ddraw files");
    restoreOriginalDllFiles(new String[]{ "ddraw.dll", "d3dimm.dll" });
}
else {
    if (ddrawrapper.equals("cnc-ddraw")) {
        envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");
    }
    // Fixed: Ensure no hidden characters (\u200b) exist before 'else if'
    else if (ddrawrapper.equals("dgvoodoo")) {
        Log.d(TAG, "Applying dgvoodoo ddrawrapper");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/dgvoodoo.tzst", windowsDir, onExtractFileListener);
    }

    Log.d(TAG, "Extracting ddrawrapper " + ddrawrapper);
    // Only extract if it wasn't already handled specifically above
    if (!ddrawrapper.equals("dgvoodoo")) {
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/" + ddrawrapper + ".tzst", windowsDir, onExtractFileListener);
    }
}

Log.d(TAG, "Finished extraction of DXVK wrapper files, version: " + dxwrapper);
return true;
} else if (dxwrapper.contains("vegas")) {
    Log.d(TAG, "Extracting VEGAS wrapper files: " + dxwrapper);

    String[] parts = dxwrapper.split(";");
    String vegasWrapper = parts[0];
    String vkd3dWrapper = parts.length > 1 ? parts[1] : "";
    String ddrawrapper = parts.length > 2 ? parts[2] : "";

    // Extract vegas DLL archive
    // vegas WCPs use CONTENT_TYPE_VEGAS, verName like "vegas-2.7.3"
    // getProfileByEntryName("vegas-2.7.3") can't resolve because the installed
    // profile has verName="vegas-2.7.3" and verCode≥1, so we search manually.
    ContentProfile vegasProfile = contentsManager.getProfileByEntryName(vegasWrapper);
    if (vegasProfile == null) {
        String needVersion = vegasWrapper.substring("vegas-".length());
        Log.d(TAG, "Searching VEGAS profiles for version: " + needVersion);
        for (ContentProfile p : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS)) {
            String pVer = (p.verName != null && p.verName.startsWith("vegas-"))
                    ? p.verName.substring("vegas-".length()) : p.verName;
            if (needVersion.equals(pVer)) {
                vegasProfile = p;
                Log.d(TAG, "Found matching VEGAS content profile: " + ContentsManager.getEntryName(p));
                break;
            }
        }
    }
    if (vegasProfile != null) {
        Log.d(TAG, "Applying user-defined VEGAS content profile: " + vegasWrapper);
        contentsManager.applyContent(vegasProfile);
    } else {
        Log.d(TAG, "Extracting fallback VEGAS .tzst archive: " + vegasWrapper);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + vegasWrapper + ".tzst", windowsDir, onExtractFileListener);
    }

    // Extract VKD3D if part of vegas+vkd3d combo
    boolean hasVkd3d = vkd3dWrapper != null && !vkd3dWrapper.isEmpty() && !vkd3dWrapper.contains("None");
    if (hasVkd3d) {
        Log.d(TAG, "Extracting VKD3D wrapper files for VEGAS combo: " + vkd3dWrapper);
        ContentProfile vkd3dProfile = contentsManager.getProfileByEntryName(vkd3dWrapper);
        if (vkd3dProfile != null) {
            Log.d(TAG, "Applying user-defined VKD3D content profile: " + vkd3dWrapper);
            contentsManager.applyContent(vkd3dProfile);
        } else {
            Log.d(TAG, "Extracting VKD3D .tzst archive: " + vkd3dWrapper);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + vkd3dWrapper + ".tzst", windowsDir, onExtractFileListener);
        }
    } else {
        // Restore original d3d12 (vanilla vegas does not include VKD3D)
        restoreOriginalDllFiles(new String[]{"d3d12.dll", "d3d12core.dll"});
    }

    // Extract nglide
    Log.d(TAG, "Extracting nglide wrapper");
    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/nglide.tzst", windowsDir, onExtractFileListener);

    // Handle ddrawrapper
    if (ddrawrapper.contains("None")) {
        Log.d(TAG, "No DDraw wrapper selected, restoring original ddraw files");
        restoreOriginalDllFiles(new String[]{ "ddraw.dll", "d3dimm.dll" });
    }
    else {
        if (ddrawrapper.equals("cnc-ddraw")) {
            envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");
        }
        else if (ddrawrapper.equals("dgvoodoo")) {
            Log.d(TAG, "Applying dgvoodoo ddrawrapper");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/dgvoodoo.tzst", windowsDir, onExtractFileListener);
        }

        Log.d(TAG, "Extracting ddrawrapper " + ddrawrapper);
        if (!ddrawrapper.equals("dgvoodoo")) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/" + ddrawrapper + ".tzst", windowsDir, onExtractFileListener);
        }
    }

    Log.d(TAG, "Finished extraction of VEGAS wrapper files: " + dxwrapper);
    return true;
} else if (dxwrapper.contains("wined3d")) {
    Log.d(TAG, "Restoring original DLL files for wined3d.");
    restoreOriginalDllFiles(dlls);
        }
        return true;
    }

    private static int compareVersion(String varA, String varB) {
        int[] a = parseSemverLoose(varA);
        int[] b = parseSemverLoose(varB);

        if (a[0] != b[0]) return a[0] - b[0];
        if (a[1] != b[1]) return a[1] - b[1];
        return a[2] - b[2];
    }

    private static final Pattern SEMVER_LOOSE =
            Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static int[] parseSemverLoose(String s) {
        if (s == null) return new int[]{0, 0, 0};

        Matcher m = SEMVER_LOOSE.matcher(s);

        String g1 = null, g2 = null, g3 = null;
        while (m.find()) {
            g1 = m.group(1);
            g2 = m.group(2);
            g3 = m.group(3);
        }

        if (g1 == null || g2 == null) {
            return new int[]{0, 0, 0};
        }

        int major = safeParseInt(g1);
        int minor = safeParseInt(g2);
        int patch = safeParseInt(g3);
        return new int[]{major, minor, patch};
    }

    private static int safeParseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
    
    private void extractWinComponentFiles() {
        Log.d("XServerDisplayActivity", "Extracting WinComponents");
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();

            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1]) && !firstTimeBoot) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, onExtractFileListener);
                }
                else {
                    JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                    for (int i = 0; i < dlnames.length(); i++) {
                        String dlname = dlnames.getString(i);
                        dlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                    }
                }
                Log.d("XServerDisplayActivity", "Setting wincomponent " + identifier + " to " + String.valueOf(useNative));
                WineUtils.overrideWinComponentDlls(this, container, identifier, useNative);
                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative, this);
            }

            if (!dlls.isEmpty()) restoreOriginalDllFiles(dlls.toArray(new String[0]));
        }
        catch (JSONException e) {}
    }

    private void restoreOriginalDllFiles(final String... dlls) {
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File system32dlls = null;
        File syswow64dlls = null;

        if (wineInfo.isArm64EC())
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/aarch64-windows");
        else
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/x86_64-windows");

        syswow64dlls = new File(imageFs.getWinePath() + "/lib/wine/i386-windows");


        for (String dll : dlls) {
            File srcFile = new File(system32dlls, dll);
            File dstFile = new File(windowsDir, "system32/" + dll);
            FileUtils.copy(srcFile, dstFile);
            srcFile = new File(syswow64dlls, dll);
            dstFile = new File(windowsDir, "syswow64/" + dll);
            FileUtils.copy(srcFile, dstFile);
        }
   }

    private String getWineStartCommand() {
        // Initialize overrideEnvVars if not already done
        EnvVars envVars = getOverrideEnvVars();

        // Define default arguments
        String args = "";

        if (shortcut != null) {
            String execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " " + execArgs : "";

            if (shortcut.path.endsWith(".lnk")) {
                args += "\"" + shortcut.path + "\"" + execArgs;
            } else {
                String exeDir = FileUtils.getDirname(shortcut.path);
                String filename = FileUtils.getName(shortcut.path);

                int dotIndex = filename.lastIndexOf(".");
                int spaceIndex = (dotIndex != -1) ? filename.indexOf(" ", dotIndex) : -1;

                if (spaceIndex != -1) {
                    execArgs = filename.substring(spaceIndex + 1) + execArgs;
                    filename = filename.substring(0, spaceIndex);
                }

                args += "/dir " + StringUtils.escapeDOSPath(exeDir) + " \"" + filename + "\"" + execArgs;
            }
        } else {
            // Append EXTRA_EXEC_ARGS from overrideEnvVars if it exists
            if (envVars.has("EXTRA_EXEC_ARGS")) {
                args += " " + envVars.get("EXTRA_EXEC_ARGS");
                envVars.remove("EXTRA_EXEC_ARGS"); // Remove the key after use
            } else {
                args += "\"wfm.exe\"";
            }
        }
        // Construct the final command
        String command = "winhandler.exe " + args;

        return command;
    }

    private String getExecutable() {
        String filename = "";
        if (shortcut != null) {
            filename = FileUtils.getName(shortcut.path);
        }
        else
            filename = "wfm.exe";
        return filename;
    }

    // Per-game overrides for renderer / frame-gen engine / fps limiter: use the shortcut's value if it
    // has one, otherwise follow the container. Read-only — never written back to the container, so a
    // per-game choice can't leak into the container's saved settings (the in-game toggle calls saveData).
    private String resolvedRenderer() {
        if (container == null) return "vulkan";
        return shortcut != null ? shortcut.getExtra("renderer", container.getRenderer()) : container.getRenderer();
    }

    private String resolvedFrameGenEngine() {
        return shortcut != null ? shortcut.getExtra("frameGenEngine", container.getFrameGenEngine()) : container.getFrameGenEngine();
    }

    // ReShade effect selection: shortcut overrides the container default. "None"/empty -> no effect.
    private String resolvedReshadeEffect() {
        if (container == null) return "None";
        return shortcut != null ? shortcut.getExtra("reshadeEffect", container.getReshadeEffect()) : container.getReshadeEffect();
    }

    // Per-uniform value overrides (JSON {name: value}); shortcut wins over the container default.
    private String resolvedReshadeParams() {
        if (container == null) return "";
        return shortcut != null ? shortcut.getExtra("reshadeParams", container.getReshadeParams()) : container.getReshadeParams();
    }

    // ReShade only rides the guest-side Vulkan swapchain (DXVK/VKD3D via Turnip), so it's a no-op on
    // WineD3D/GL/GDI titles. Renderer-agnostic (works under any host renderer). Drives the editor
    // hint + the in-game drawer grey-out (mirrors how SGSR/HDR are gated).
    private boolean reshadeSupported() {
        return dxwrapper != null && (dxwrapper.contains("dxvk") || dxwrapper.contains("vegas"));
    }

    private boolean resolvedFpsLimiterEnabled() {
        if (shortcut != null) {
            return shortcut.getExtra("fpsLimiterEnabled", container.isFpsLimiterEnabled() ? "1" : "0").equals("1");
        }
        return container.isFpsLimiterEnabled();
    }

    // Per-game override for VRR / refresh-rate matching (shortcut wins over the container default).
    // Mirrors resolvedFpsLimiterEnabled(). Null-safe for early calls before the container is loaded.
    private boolean resolvedMatchRefreshRate() {
        if (container == null) return false;
        if (shortcut != null) {
            return shortcut.getExtra("matchRefreshRate", container.isMatchRefreshRate() ? "1" : "0").equals("1");
        }
        return container.isMatchRefreshRate();
    }

    // Per-game override for the manual refresh-rate lock (shortcut wins over the container default).
    // Mirrors resolvedMatchRefreshRate(). 0 = no manual lock. Null-safe for early calls.
    private int resolvedManualRefreshRate() {
        if (container == null) return 0;
        if (shortcut != null) {
            try {
                return Integer.parseInt(shortcut.getExtra("manualRefreshRate",
                    String.valueOf(container.getManualRefreshRate())));
            } catch (NumberFormatException e) {
                return container.getManualRefreshRate();
            }
        }
        return container.getManualRefreshRate();
    }

    // lsfg-vk does its OWN frame pacing when it is multiplying (multiplier >= 2). Layering the
    // standalone IdleNotify limiter on top double-paces the present stream: our pacer throttles
    // lsfg's already-multiplied output, clamping the panel to the limiter value (killing the FG
    // smoothness gain and wasting GPU on interpolated frames that then get blocked). So the limiter
    // steps aside and lets lsfg govern whenever lsfg is active at mult >= 2.
    //
    // >>> IF USERS REPORT "THE FPS LIMITER DOESN'T WORK / NO CAP" ON lsfg-vk, THIS GUARD IS WHY:
    //     the limiter is intentionally disabled while lsfg-vk multiplies (mult >= 2). It is NOT
    //     disabled for bionic-fg, for Off, or for lsfg at 1x (passthrough) -- those still cap.
    //
    // Behavior ported from GameNative (its "limiterControlledByLsfg"):
    // https://github.com/utkarshdalal/GameNative. See README Credits.
    private boolean lsfgGovernsFps() {
        XServerDrawerState s = XServerDrawerState.INSTANCE;
        return "lsfg".equals(resolvedFrameGenEngine())
            && s.getFrameGenEnabled().getValue()
            && s.getFrameGenMultiplier().getValue() >= 2;
    }

    // Re-evaluate and re-apply the FPS cap from the remembered limiter state. Called when the
    // frame-gen config changes live (e.g. switching lsfg multiplier) so the lsfgGovernsFps() guard
    // takes effect immediately without the user touching the limiter toggle.
    private void reapplyFpsLimit() {
        XServerDrawerState s = XServerDrawerState.INSTANCE;
        boolean limOn  = s.getFpsLimiterEnabled().getValue();
        int   limitVal = s.getFpsLimit().getValue();
        applyFpsLimit(limOn && limitVal > 0 ? limitVal : 0);
    }

    // Apply the FPS cap (0 = off). The real limiter is the X11 Present extension, which throttles
    // the guest by pacing its IdleNotify (so the game itself slows -> in-game HUD reflects it, GPU
    // drops). Also feeds the renderer's SurfaceControl frame-rate hint (active in Vulkan native mode).
    private void applyFpsLimit(int fps) {
        // Capture the un-guarded cap (the limiter value, 0 = uncapped) BEFORE the lsfg guard zeroes
        // the local `fps`. VRR votes the DISPLAYED rate, which in the lsfg-governs case is cap x mult
        // even though the present pacer steps aside (fps -> 0). This is the only place the two diverge.
        int vrrCap = fps;
        // Step aside while lsfg-vk is multiplying -- it paces itself (see lsfgGovernsFps()).
        if (lsfgGovernsFps()) fps = 0;
        com.winlator.star.xserver.extensions.PresentExtension pe =
                xServer.getExtension(com.winlator.star.xserver.extensions.PresentExtension.MAJOR_OPCODE);
        if (pe != null) pe.setFrameRateLimit(fps);
        if (xServerView != null) {
            HostRenderer r = xServerView.getRenderer();
            if (r != null) r.setFpsLimit(fps);
        }
        // VRR / refresh-rate matching: vote the panel cadence to match the displayed FPS.
        applyVrr(vrrCap);
    }

    // Surface.FRAME_RATE_COMPATIBILITY_DEFAULT (== 0). Referenced as a literal so the call site is not
    // an API-30 field access; the real API call is guarded inside XServerView.setDisplayFrameRate.
    private static final int VRR_FRAME_RATE_COMPATIBILITY = 0;

    // Vote a panel refresh rate that matches the DISPLAYED frame rate (VRR / refresh-rate matching).
    // Complementary to the FPS limiter: the limiter caps the producer/render rate, this matches the
    // display/panel rate so the panel cadence follows render cadence (smoother + power savings).
    //   Auto ON, cap == 0 (limiter off)    -> vote 0f (clear; panel runs free)
    //   Auto ON, normal / bionic-fg        -> vote cap
    //   Auto ON, lsfg multiplying (>= 2)   -> vote cap x mult (the displayed rate)
    //   Auto OFF, manual rate > 0          -> vote that rate (lock, independent of the FPS cap)
    //   Auto OFF, manual rate == 0         -> vote 0f (no lock; panel runs free)
    private void applyVrr(int cap) {
        if (xServerView == null) return;
        float vrrRate = 0.0f;
        if (container != null && resolvedMatchRefreshRate()) {
            // Auto (match FPS): vote the panel cadence to follow the displayed FPS while capping.
            if (cap > 0) {
                if (lsfgGovernsFps()) {
                    int mult = XServerDrawerState.INSTANCE.getFrameGenMultiplier().getValue();
                    vrrRate = (float) cap * (mult >= 2 ? mult : 1);
                } else {
                    vrrRate = (float) cap;
                }
            }
        } else if (container != null) {
            // Manual: lock the panel to the chosen rate, independent of the FPS cap. 0 = no lock.
            int manual = resolvedManualRefreshRate();
            if (manual > 0) vrrRate = (float) manual;
        }
        xServerView.setDisplayFrameRate(vrrRate, VRR_FRAME_RATE_COMPATIBILITY);
        // onCreate pins the window's preferredRefreshRate to the panel max (for smooth UI). That
        // window-level request out-votes the VRR surface vote, so the panel never leaves max. When VRR is
        // matching a capped rate, lower the window preference to that rate too; otherwise restore the max.
        applyWindowPreferredRefreshRate(vrrRate);
    }

    // Keep the window's preferred refresh rate in step with VRR so it doesn't fight the surface vote.
    // vrrRate > 0 -> prefer that exact rate (the panel switches to the matching mode); 0 -> restore max.
    private void applyWindowPreferredRefreshRate(float vrrRate) {
        runOnUiThread(() -> {
            android.view.WindowManager.LayoutParams p = getWindow().getAttributes();
            float desired = vrrRate > 0f ? vrrRate : pickHighestRefreshRate();
            if (p.preferredRefreshRate != desired) {
                p.preferredRefreshRate = desired;
                getWindow().setAttributes(p);
            }
        });
    }

    // Re-apply the VRR vote from the current remembered limiter state (used on resume and when the
    // match-refresh toggle changes live, without re-poking the present pacer / renderer).
    private void reapplyVrr() {
        XServerDrawerState s = XServerDrawerState.INSTANCE;
        boolean limOn  = s.getFpsLimiterEnabled().getValue();
        int   limitVal = s.getFpsLimit().getValue();
        applyVrr(limOn && limitVal > 0 ? limitVal : 0);
    }

    // Push the live (actual) display refresh rate into the drawer so the readout can show what the
    // panel is really running at while Auto (match FPS) is on and the manual slider is greyed.
    private void updateCurrentRefreshRate() {
        int rate = com.winlator.star.widget.XServerView.getCurrentRefreshRate(getWindowManager().getDefaultDisplay());
        XServerDrawerState.INSTANCE.setCurrentRefreshRate(rate);
    }

    // Listen for panel mode switches so the readout tracks the real rate live (e.g. when VRR drops
    // the panel 144->60 after a vote). Registered while resumed, released on stop.
    private android.hardware.display.DisplayManager.DisplayListener vrrDisplayListener;

    private void registerVrrDisplayListener() {
        if (vrrDisplayListener != null) return;
        android.hardware.display.DisplayManager dm =
            (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return;
        vrrDisplayListener = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) { updateCurrentRefreshRate(); }
        };
        dm.registerDisplayListener(vrrDisplayListener, handler);
    }

    private void unregisterVrrDisplayListener() {
        if (vrrDisplayListener == null) return;
        android.hardware.display.DisplayManager dm =
            (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.unregisterDisplayListener(vrrDisplayListener);
        vrrDisplayListener = null;
    }


    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) {
            overrideEnvVars = new EnvVars();
        }
        return overrideEnvVars;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = imageFs.getRootDir();
            File userRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals("alsa")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals("pulseaudio")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = imageFs.getRootDir();
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "container_pattern_common.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    private void assignTaskAffinity(Window window) {
        if (taskAffinityMask == 0 || taskAffinityMaskWoW64 == 0) return;
        int processId = window.getProcessId();
        String className = window.getClassName();
        int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;

        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        }
        else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    /** Flip the in-game FPS overlay between horizontal and vertical layouts (tap on the overlay). */
    /** Build the GameHub-style HUD and add it to the overlay. Safe to call live (UI thread). */
    private void buildPerfHud(String fpsConfigString) {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        perfHud = new PerfHudView(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP | android.view.Gravity.START
        );
        plp.topMargin = 10;
        plp.leftMargin = 10;
        perfHud.setLayoutParams(plp);
        perfHud.applyConfig(fpsConfigString);
        perfHud.setOnTapListener(this::toggleFpsHudOrientation);
        if (hudEngineShort != null) perfHud.setEngineLabel(hudEngineShort);
        if (hudGpuName != null) perfHud.setGpuModel(hudGpuName);
        perfHud.setVertical(!fpsHudHorizontal);
        // Visible immediately if the game window is already mapped (live swap); otherwise it is
        // revealed by changeFrameRatingVisibility once the window appears (launch path).
        perfHud.setVisibility(frameRatingWindowId != -1 ? View.VISIBLE : View.GONE);
        rootView.addView(perfHud);
    }

    /** Build the classic FrameRating HUD (both orientations) and add it. Safe to call live (UI thread). */
    private void buildClassicHud(String fpsConfigString) {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        boolean shown = frameRatingWindowId != -1;

        // Create BOTH orientations up front so the user can flip between them in-game with a tap;
        // only the active one is ever made visible.
        frameRatingHorizontal = new FrameRatingHorizontal(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL
        );
        lp.topMargin = 10;
        frameRatingHorizontal.setLayoutParams(lp);
        frameRatingHorizontal.applyConfig(fpsConfigString);
        // setOnClickListener never fires: the widget overrides onTouchEvent and consumes the
        // event without performClick(). Use the widget's own tap callback instead.
        frameRatingHorizontal.setOnTapListener(this::toggleFpsHudOrientation);
        frameRatingHorizontal.setVisibility(shown && fpsHudHorizontal ? View.VISIBLE : View.GONE);
        rootView.addView(frameRatingHorizontal);

        frameRating = new FrameRating(this, graphicsDriverConfig);
        // Explicit WRAP_CONTENT params: without them, FrameLayout's default params are
        // MATCH_PARENT x MATCH_PARENT, so the vertical HUD's view (and thus its tap-to-toggle
        // hit area) covered the WHOLE screen — a tap far from the overlay flipped orientation.
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP | android.view.Gravity.START
        );
        frameRating.setLayoutParams(vlp);
        frameRating.applyConfig(fpsConfigString);
        frameRating.setOnTapListener(this::toggleFpsHudOrientation);
        frameRating.setVisibility(shown && !fpsHudHorizontal ? View.VISIBLE : View.GONE);
        rootView.addView(frameRating);

        if (hudRendererLabel != null) {
            frameRatingHorizontal.setRenderer(hudRendererLabel);
            frameRating.setRenderer(hudRendererLabel);
        }
        if (hudGpuName != null) frameRating.setGpuName(hudGpuName);
    }

    private void removePerfHud() {
        if (perfHud != null) {
            FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
            rootView.removeView(perfHud);
            perfHud = null;
        }
    }

    private void removeClassicHud() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        if (frameRating != null) { rootView.removeView(frameRating); frameRating = null; }
        if (frameRatingHorizontal != null) { rootView.removeView(frameRatingHorizontal); frameRatingHorizontal = null; }
    }

    private void toggleFpsHudOrientation() {
        if (perfHud == null && frameRating == null && frameRatingHorizontal == null) return;
        fpsHudHorizontal = !fpsHudHorizontal;
        if (perfHud != null) {
            // One view draws both layouts; vertical = !horizontal.
            perfHud.setVertical(!fpsHudHorizontal);
        } else {
            boolean wasShown =
                (frameRatingHorizontal != null && frameRatingHorizontal.getVisibility() == View.VISIBLE)
                || (frameRating != null && frameRating.getVisibility() == View.VISIBLE);
            if (frameRating != null) frameRating.setVisibility(View.GONE);
            if (frameRatingHorizontal != null) frameRatingHorizontal.setVisibility(View.GONE);
            if (wasShown) {
                if (fpsHudHorizontal) {
                    if (frameRatingHorizontal != null) { frameRatingHorizontal.setVisibility(View.VISIBLE); frameRatingHorizontal.update(); }
                } else {
                    if (frameRating != null) { frameRating.setVisibility(View.VISIBLE); frameRating.update(); }
                }
            }
        }
        // Persist the chosen orientation to the container's FPS config.
        if (container != null) {
            com.winlator.star.core.KeyValueSet cfg = new com.winlator.star.core.KeyValueSet(container.getFPSCounterConfig());
            cfg.put("hudMode", fpsHudHorizontal ? "horizontal" : "vertical");
            container.setFPSCounterConfig(cfg.toString());
            container.saveData();
        }
    }

    private void changeFrameRatingVisibility(Window window, Property property) {
        if (perfHud == null && frameRating == null && frameRatingHorizontal == null) return;

        if (property != null) {
            if (frameRatingWindowId == -1 && property.nameAsString().contains("_MESA_DRV")) {
                frameRatingWindowId = window.id;
                Log.d("XServerDisplayActivity", "Showing hud for Window " + window.getName());

                runOnUiThread(() -> {
                    // Show only the active orientation (both widgets exist for tap-toggle).
                    if (perfHud != null) perfHud.setVisibility(View.VISIBLE);
                    if (fpsHudHorizontal) {
                        if (frameRatingHorizontal != null) frameRatingHorizontal.setVisibility(View.VISIBLE);
                    } else {
                        if (frameRating != null) frameRating.setVisibility(View.VISIBLE);
                    }
                });

                if (frameRating != null) frameRating.update();
                if (frameRatingHorizontal != null) frameRatingHorizontal.update();
                if (perfHud != null) perfHud.update();
            }
            if (property.nameAsString().contains("_MESA_DRV_GPU_NAME")) {
                hudGpuName = property.toString();
                runOnUiThread(() -> {
                    if (frameRating != null) frameRating.setGpuName(hudGpuName);
                    if (perfHud != null) perfHud.setGpuModel(hudGpuName);
                });
            }
        }
        else if (frameRatingWindowId != -1) {
            frameRatingWindowId = -1;
            Log.d("XServerDisplayActivity", "Hiding hud for Window " + window.getName());
            runOnUiThread(() -> {
                if (frameRating != null) {
                    frameRating.setVisibility(View.GONE);
                    frameRating.reset();
                }
                if (frameRatingHorizontal != null) {
                    frameRatingHorizontal.setVisibility(View.GONE);
                    frameRatingHorizontal.reset();
                }
                if (perfHud != null) perfHud.setVisibility(View.GONE);
            });
        }
    }


    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

    private void MoveCursorToTouchpoint() {
        // Toggle the preference value
        boolean currentValue = preferences.getBoolean("move_cursor_to_touchpoint", false);
        boolean newValue = !currentValue;
        
        preferences.edit().putBoolean("move_cursor_to_touchpoint", newValue).apply();
        
        // Update the touchpadView state
        if (touchpadView != null) {
            touchpadView.setMoveCursorToTouchpoint(newValue);
        }
    } // Closes MoveCursorToTouchpoint

    private void showActiveWindowsDialog() {
        ArrayList<com.winlator.star.xserver.Window> activeWindows = new ArrayList<>();
        ArrayList<android.graphics.Bitmap> activeIcons = new ArrayList<>();
        try {
            try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                findAppWindowsForCompose(xServer.windowManager.rootWindow, activeWindows);
                for (com.winlator.star.xserver.Window w : activeWindows) {
                    activeIcons.add(xServer.pixmapManager.getWindowIcon(w));
                }
            }
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Error reading windows", e);
        }

        ArrayList<XServerDialogState.ActiveWindow> windowInfoList = new ArrayList<>();
        for (int i = 0; i < activeWindows.size(); i++) {
            com.winlator.star.xserver.Window w = activeWindows.get(i);
            String title = w.getName();
            String cls   = w.getClassName() != null ? w.getClassName() : "";
            if (title == null || title.isEmpty()) title = cls;
            if (title.isEmpty()) title = "Unnamed Window";
            windowInfoList.add(new XServerDialogState.ActiveWindow(
                title, cls, activeIcons.get(i), null, w.getHandle()));
        }

        XServerDialogState ds = XServerDialogState.INSTANCE;
        ds.setAwWindows(windowInfoList);
        ds.onWindowClick = (cls, handle) -> {
            WinHandler wh = getWinHandler();
            if (wh != null) wh.bringToFront(cls, handle);
        };
        ds.show(XServerDialogState.ActiveDialog.ACTIVE_WINDOWS);

        HostRenderer _r = xServerView != null ? xServerView.getRenderer() : null;
        GLRenderer renderer = _r instanceof GLRenderer ? (GLRenderer)_r : null;
        if (renderer != null) {
            float density = getResources().getDisplayMetrics().density;
            int previewW = (int)(240 * density);
            int previewH = (int)(160 * density);
            for (int i = 0; i < activeWindows.size(); i++) {
                final int idx = i;
                final com.winlator.star.xserver.Window win = activeWindows.get(i);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                    renderer.captureScreenshot(win, previewW, previewH, bitmap -> {
                        if (bitmap != null) runOnUiThread(() -> ds.updateAwScreenshot(idx, bitmap));
                    }), idx * 100L);
            }
        }
    }

    private void findAppWindowsForCompose(com.winlator.star.xserver.Window parent,
                                          ArrayList<com.winlator.star.xserver.Window> result) {
        if (parent == null) return;
        for (com.winlator.star.xserver.Window child : parent.getChildren()) {
            if (child.attributes.isMapped()) {
                String className = child.getClassName();
                boolean isSystem = false;
                if (className != null) {
                    String cls = className.toLowerCase();
                    if (cls.contains("progman") || cls.contains("shell_traywnd") || cls.equals("explorer.exe"))
                        isSystem = true;
                }
                String title  = child.getName();
                boolean hasTitle = title != null && !title.isEmpty();
                boolean hasClass = className != null && !className.isEmpty();
                if (!isSystem && (hasTitle || hasClass)) {
                    if (child.getWidth() < xServer.screenInfo.width || child.getHeight() < xServer.screenInfo.height
                            || child.getParent() != xServer.windowManager.rootWindow
                            || (title != null && !title.isEmpty()
                                && !title.equalsIgnoreCase("Default - Wine desktop"))) {
                        result.add(child);
                        continue;
                    }
                }
            }
            findAppWindowsForCompose(child, result);
        }
    }

    private void showScreenEffectsDialog() {
        HostRenderer _r = xServerView != null ? xServerView.getRenderer() : null;
        GLRenderer r = _r instanceof GLRenderer ? (GLRenderer)_r : null;
        XServerDialogState ds = XServerDialogState.INSTANCE;

        ColorEffect ce   = r != null ? (ColorEffect)        r.getEffectComposer().getEffect(ColorEffect.class)        : null;
        FXAAEffect  fxaa = r != null ? (FXAAEffect)         r.getEffectComposer().getEffect(FXAAEffect.class)         : null;
        CRTEffect   crt  = r != null ? (CRTEffect)          r.getEffectComposer().getEffect(CRTEffect.class)          : null;
        ToonEffect  toon = r != null ? (ToonEffect)         r.getEffectComposer().getEffect(ToonEffect.class)         : null;
        NTSCCombinedEffect ntsc = r != null ? (NTSCCombinedEffect) r.getEffectComposer().getEffect(NTSCCombinedEffect.class) : null;

        ds.setSeBrightness(ce   != null ? ce.getBrightness() * 100f : 0f);
        ds.setSeContrast  (ce   != null ? ce.getContrast()   * 100f : 0f);
        ds.setSeGamma     (ce   != null ? ce.getGamma()             : 1.0f);
        ds.setSeFxaa      (fxaa != null);
        ds.setSeCrt       (crt  != null);
        ds.setSeToon      (toon != null);
        ds.setSeNtsc      (ntsc != null);

        java.util.Set<String> rawSet = new java.util.LinkedHashSet<>(
            preferences.getStringSet("screen_effect_profiles", new java.util.LinkedHashSet<>()));
        final ArrayList<String> profileNames = new ArrayList<>();
        for (String p : rawSet) profileNames.add(p.split(":")[0]);
        ds.setSeProfiles(profileNames);

        String currentProfile = getScreenEffectProfile();
        int selIdx = 0;
        for (int i = 0; i < profileNames.size(); i++) {
            if (profileNames.get(i).equals(currentProfile)) { selIdx = i + 1; break; }
        }
        ds.setSeSelectedProfile(selIdx);

        ds.onScreenEffectsApply = (brightness, contrast, gamma, fxaaEn, crtEn, toonEn, ntscEn, profileIndex) -> {
            if (r == null) return;
            applyScreenEffects(r, brightness, contrast, gamma, fxaaEn, crtEn, toonEn, ntscEn);
            if (profileIndex > 0 && profileIndex - 1 < profileNames.size()) {
                String name = profileNames.get(profileIndex - 1);
                saveScreenEffectProfile(name, brightness, contrast, gamma, fxaaEn, crtEn, toonEn, ntscEn);
                setScreenEffectProfile(name);
            }
        };

        ds.onSeAddProfile = name -> {
            java.util.Set<String> profiles = new java.util.LinkedHashSet<>(
                preferences.getStringSet("screen_effect_profiles", new java.util.LinkedHashSet<>()));
            boolean exists = false;
            for (String p : profiles) { if (p.split(":")[0].equals(name)) { exists = true; break; } }
            if (!exists) {
                profiles.add(name + ":");
                preferences.edit().putStringSet("screen_effect_profiles", profiles).apply();
                profileNames.add(name);
                ds.setSeProfiles(new ArrayList<>(profileNames));
            }
        };

        ds.onSeRemoveProfile = name -> {
            java.util.Set<String> profiles = new java.util.LinkedHashSet<>(
                preferences.getStringSet("screen_effect_profiles", new java.util.LinkedHashSet<>()));
            profiles.removeIf(p -> p.split(":")[0].equals(name));
            preferences.edit().putStringSet("screen_effect_profiles", profiles).apply();
            profileNames.removeIf(n -> n.equals(name));
            ds.setSeProfiles(new ArrayList<>(profileNames));
            ds.setSeSelectedProfile(0);
        };

        ds.show(XServerDialogState.ActiveDialog.SCREEN_EFFECTS);
    }

    private void applyScreenEffects(GLRenderer r, float brightness, float contrast, float gamma,
                                    boolean fxaaEn, boolean crtEn, boolean toonEn, boolean ntscEn) {
        ColorEffect ce = (ColorEffect) r.getEffectComposer().getEffect(ColorEffect.class);
        if (brightness == 0 && contrast == 0 && gamma == 1.0f) {
            if (ce != null) r.getEffectComposer().removeEffect(ce);
        } else {
            if (ce == null) ce = new ColorEffect();
            ce.setBrightness(brightness / 100f);
            ce.setContrast(contrast / 100f);
            ce.setGamma(gamma);
            r.getEffectComposer().addEffect(ce);
        }
        FXAAEffect fxaa = (FXAAEffect) r.getEffectComposer().getEffect(FXAAEffect.class);
        if (fxaaEn) { if (fxaa == null) r.getEffectComposer().addEffect(new FXAAEffect()); }
        else if (fxaa != null) r.getEffectComposer().removeEffect(fxaa);

        CRTEffect crt = (CRTEffect) r.getEffectComposer().getEffect(CRTEffect.class);
        if (crtEn) { if (crt == null) r.getEffectComposer().addEffect(new CRTEffect()); }
        else if (crt != null) r.getEffectComposer().removeEffect(crt);

        ToonEffect toon = (ToonEffect) r.getEffectComposer().getEffect(ToonEffect.class);
        if (toonEn) { if (toon == null) r.getEffectComposer().addEffect(new ToonEffect()); }
        else if (toon != null) r.getEffectComposer().removeEffect(toon);

        NTSCCombinedEffect ntsc = (NTSCCombinedEffect) r.getEffectComposer().getEffect(NTSCCombinedEffect.class);
        if (ntscEn) { if (ntsc == null) r.getEffectComposer().addEffect(new NTSCCombinedEffect()); }
        else if (ntsc != null) r.getEffectComposer().removeEffect(ntsc);
    }

    private void saveScreenEffectProfile(String name, float brightness, float contrast, float gamma,
                                         boolean fxaa, boolean crt, boolean toon, boolean ntsc) {
        com.winlator.star.core.KeyValueSet settings = new com.winlator.star.core.KeyValueSet();
        settings.put("brightness",  brightness);
        settings.put("contrast",    contrast);
        settings.put("gamma",       gamma);
        settings.put("fxaa",        fxaa);
        settings.put("crt_shader",  crt);
        settings.put("toon_shader", toon);
        settings.put("ntsc_effect", ntsc);
        java.util.Set<String> oldProfiles = new java.util.LinkedHashSet<>(
            preferences.getStringSet("screen_effect_profiles", new java.util.LinkedHashSet<>()));
        java.util.Set<String> newProfiles = new java.util.LinkedHashSet<>();
        for (String p : oldProfiles) {
            String n = p.split(":")[0];
            newProfiles.add(n.equals(name) ? name + ":" + settings.toString() : p);
        }
        preferences.edit().putStringSet("screen_effect_profiles", newProfiles).apply();
    }

    private void showMagnifierOverlay() {
        // Drive the magnifier through the HostRenderer interface (declares get/setMagnifierZoom,
        // implemented by GL, Vulkan and ASR). The old code cast to GLRenderer and no-op'd for
        // any other renderer, so on the default Vulkan renderer the overlay opened stuck at 100%
        // and the +/- buttons did nothing (issue #22). VulkanRenderer.setMagnifierZoom applies
        // the zoom live via updateTransform().
        HostRenderer r = xServerView != null ? xServerView.getRenderer() : null;
        XServerDialogState ds = XServerDialogState.INSTANCE;

        ds.setMagnifierZoom(r != null ? r.getMagnifierZoom() : 1.0f);
        ds.onMagnifierZoom = delta -> {
            if (r == null) return;
            float z = Mathf.clamp(r.getMagnifierZoom() + delta, 1.0f, 3.0f);
            r.setMagnifierZoom(z);
            ds.setMagnifierZoom(z);
        };
        ds.onMagnifierHide = () -> ds.setMagnifierVisible(false);
        ds.setMagnifierVisible(true);
    }

    private void startTmPolling() {
        registerTmProcessInfoListener();
        tmPollHandler.removeCallbacks(tmPollRunnable);
        tmPollHandler.post(tmPollRunnable);
    }

    // ---- Component installer auto-exit (Phase 3b) ----

    private void startInstallerWatch() {
        installerProcSeen = false;
        installerGoneTicks = 0;
        installerWatchHandler.removeCallbacks(installerWatchRunnable);
        // Give Wine a head start to boot and actually launch the installer before we begin watching,
        // so we don't conclude "finished" before it has even appeared.
        installerWatchHandler.postDelayed(installerWatchRunnable, 8000);
    }

    private boolean looksLikeInstallerProc(String name) {
        if (name == null) return false;
        String target = componentInstallerExe != null ? componentInstallerExe.toLowerCase() : "";
        // The bootstrapper may relaunch itself from %temp% under its original name and spawn msiexec,
        // so match the staged name plus the usual installer/runtime process names.
        return name.equals(target)
                || name.contains("msiexec")
                || name.contains("redist")
                || name.contains("vcredist")
                || name.contains("dotnet")
                || name.contains("ndp")
                || name.contains("setup")
                || name.contains("install");
    }

    private void evaluateInstallerTick() {
        if (componentInstallerExe == null) return;
        boolean present = false;
        for (String n : installerTickNames) {
            if (looksLikeInstallerProc(n)) { present = true; break; }
        }
        if (present) {
            installerProcSeen = true;
            installerGoneTicks = 0;
        } else if (installerProcSeen) {
            installerGoneTicks++;
            // Require a few consecutive empty ticks so a brief gap (bootstrapper exits, then msiexec
            // spawns) doesn't trip an early exit.
            if (installerGoneTicks >= 3) {
                installerWatchHandler.removeCallbacks(installerWatchRunnable);
                componentInstallerExe = null;
                if (winHandler != null) winHandler.setOnGetProcessInfoListener(null);
                runOnUiThread(XServerDisplayActivity.this::exit);
            }
        }
    }

    private void stopTmPolling() {
        tmPollHandler.removeCallbacks(tmPollRunnable);
        if (winHandler != null) winHandler.setOnGetProcessInfoListener(null);
        XServerDialogState.INSTANCE.setTmProcesses(new ArrayList<>());
    }

    /** Per-game/-container "close session when the game exits", defaulting to ON. */
    private boolean resolvedAutoCloseOnExit() {
        if (container == null) return false;
        String def = container.getExtra("autoCloseOnExit", "1");
        String v = shortcut != null ? shortcut.getExtra("autoCloseOnExit", def) : def;
        return v.equals("1");
    }

    private void startGameExitWatch() {
        autoCloseExeName = getExecutable().toLowerCase();
        gameProcSeen = false;
        gameGoneTicks = 0;
        gameExitWatchHandler.removeCallbacks(gameExitWatchRunnable);
        // Give Wine time to boot and the game to actually appear before we start watching, so a slow
        // first launch isn't mistaken for "already exited".
        gameExitWatchHandler.postDelayed(gameExitWatchRunnable, 12000);
    }

    private void evaluateGameExitTick() {
        if (!autoCloseOnExitEnabled || autoCloseExeName == null) return;
        boolean present = false;
        for (String n : gameTickNames) {
            if (n.equals(autoCloseExeName)) { present = true; break; }
        }
        if (present) {
            gameProcSeen = true;
            gameGoneTicks = 0;
        } else if (gameProcSeen) {
            gameGoneTicks++;
            // Require a few consecutive empty ticks so a brief gap (e.g. a loader that relaunches the
            // same exe) doesn't trigger an early close.
            if (gameGoneTicks >= 3) {
                gameExitWatchHandler.removeCallbacks(gameExitWatchRunnable);
                autoCloseOnExitEnabled = false;
                if (winHandler != null) winHandler.setOnGetProcessInfoListener(null);
                runOnUiThread(XServerDisplayActivity.this::exit);
            }
        }
    }

    private void setupTmCallbacks() {
        XServerDialogState ds = XServerDialogState.INSTANCE;

        ds.onTmRefresh = () -> {
            if (winHandler != null) winHandler.listProcesses();
            updateTmCpuMemory(ds);
        };

        ds.onTmDismissed = () -> stopTmPolling();

        // Show the Compose New Task dialog instead of the native ContentDialog.prompt — the native
        // prompt is invisible over the Vulkan/ASR fullscreen SurfaceView (same class of bug that
        // killed the old Task Manager confirm). The submit path is unchanged: OK runs winHandler.exec.
        ds.onTmNewTask = () -> ds.show(XServerDialogState.ActiveDialog.NEW_TASK);
        ds.onTmNewTaskSubmit = command -> { if (winHandler != null) winHandler.exec(command); };

        // Bring to Front: drive it host-side (renderer-agnostic). Look up the real X window for the
        // target Windows pid, set X-server input focus on it, and send bringToFront with the REAL
        // window handle (not 0). On native-rendering Vulkan/ASR a UDP-only restack may have no
        // visible effect, so we also raise + redraw via the host window manager (mirrors
        // DesktopHelper.setFocusedWindow / GameNative). Falls back to a plain by-name UDP send if
        // we can't resolve the window.
        ds.onTmBringToFront = (name, pid) -> {
            if (winHandler == null) return;
            Window target = null;
            try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                target = xServer.windowManager.findWindowWithProcessId(pid);
            } catch (Exception ignored) {}
            if (target != null) {
                final Window window = target;
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    Window parent = window.getParent();
                    boolean parentIsRoot = parent != null && parent == xServer.windowManager.rootWindow;
                    xServer.windowManager.setFocus(window,
                        parentIsRoot ? WindowManager.FocusRevertTo.POINTER_ROOT : WindowManager.FocusRevertTo.PARENT);
                } catch (Exception ignored) {}
                winHandler.bringToFront(window.getClassName(), window.getHandle());
                // Force a recomposite so the restack is visible on the native Vulkan/ASR path too.
                try { xServerView.requestRender(); } catch (Exception ignored) {}
            } else {
                // Couldn't resolve the window host-side; fall back to a plain by-name UDP raise.
                winHandler.bringToFront(name);
            }
        };

        // End Process runs the same renderer-agnostic winhandler command the rest of the app uses.
        // It used to be wrapped in a native ContentDialog.confirm, but that dialog does not display
        // over the Vulkan/ASR fullscreen SurfaceView, so End Process silently did nothing there (the
        // confirm never appeared). Run the command directly so it works on every renderer. (The deeper
        // cause of the Vulkan/ASR breakage was this whole method bailing before the callbacks were
        // wired — now fixed by calling setupTmCallbacks() ahead of the GL-only early return.)
        ds.onTmKillProcess = name -> {
            if (winHandler != null) winHandler.killProcess(name);
        };

        ds.onTmSetAffinity = (pid, mask) -> {
            if (winHandler != null) winHandler.setProcessAffinity(pid, mask);
        };

        registerTmProcessInfoListener();

        updateTmCpuMemory(ds);
    }

    private void registerTmProcessInfoListener() {
        XServerDialogState ds = XServerDialogState.INSTANCE;
        if (winHandler != null) {
            winHandler.setOnGetProcessInfoListener(new OnGetProcessInfoListener() {
                private final ArrayList<XServerDialogState.TmProcess> buffer = new ArrayList<>();

                @Override
                public void onGetProcessInfo(int index, int numProcesses, ProcessInfo info) {
                    android.graphics.Bitmap icon = null;
                    try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        com.winlator.star.xserver.Window w = xServer.windowManager.findWindowWithProcessId(info.pid);
                        if (w != null) icon = xServer.pixmapManager.getWindowIcon(w);
                    } catch (Exception ignored) {}

                    final android.graphics.Bitmap finalIcon = icon;
                    runOnUiThread(() -> {
                        if (index == 0) buffer.clear();
                        buffer.add(new XServerDialogState.TmProcess(
                            index, info.pid, info.name,
                            info.getFormattedMemoryUsage(), info.wow64Process, finalIcon));
                        if (numProcesses == 0 || index == numProcesses - 1) {
                            ds.setTmProcesses(new ArrayList<>(buffer));
                            ds.setTmCount(numProcesses);
                        }
                    });
                }
            });
        }
    }

    private void updateTmCpuMemory(XServerDialogState ds) {
        try {
            short[] clocks = CPUStatus.getCurrentClockSpeeds();
            int total = 0; short maxClock = 0;
            ArrayList<String> cores = new ArrayList<>();
            for (int i = 0; i < clocks.length; i++) {
                short max = CPUStatus.getMaxClockSpeed(i);
                cores.add(clocks[i] + "/" + max + " MHz");
                total += clocks[i];
                if (max > maxClock) maxClock = max;
            }
            int avg = clocks.length > 0 ? total / clocks.length : 0;
            int pct = maxClock > 0 ? (int)(((float) avg / maxClock) * 100) : 0;
            ds.setTmCpuCores(cores);
            ds.setTmCpuTitle("CPU (" + pct + "%)");

            android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long used = mi.totalMem - mi.availMem;
            int memPct = (int)(((double) used / mi.totalMem) * 100);
            ds.setTmMemTitle("Memory (" + memPct + "%)");
            ds.setTmMemInfo(StringUtils.formatBytes(used, false) + " / " +
                StringUtils.formatBytes(mi.totalMem));
        } catch (Exception ignored) {}
    }


} // Closes the XServerDisplayActivity class



















































