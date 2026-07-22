package com.winlator.star.winhandler;

import android.content.SharedPreferences;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.preference.PreferenceManager;

import com.winlator.star.XServerDisplayActivity;
import com.winlator.star.core.GyroCalibrator;
import com.winlator.star.core.StringUtils;
import com.winlator.star.inputcontrols.ControlsProfile;
import com.winlator.star.inputcontrols.ExternalController;
import com.winlator.star.inputcontrols.FakeInputWriter;
import com.winlator.star.inputcontrols.GamepadState;
import com.winlator.star.widget.InputControlsView;
import com.winlator.star.xserver.XServer;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.SystemClock;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WinHandler {
    private static final short SERVER_PORT = 7947;
    private static final short CLIENT_PORT = 7946;
    public static final byte FLAG_INPUT_TYPE_XINPUT = 0x04;
    public static final byte FLAG_INPUT_TYPE_DINPUT = 0x08;
    public static final byte DEFAULT_INPUT_TYPE = FLAG_INPUT_TYPE_XINPUT;
    private DatagramSocket socket;
    private final ByteBuffer sendData = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer receiveData = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final DatagramPacket sendPacket = new DatagramPacket(sendData.array(), 64);
    private final DatagramPacket receivePacket = new DatagramPacket(receiveData.array(), 64);
    private final ArrayDeque<Runnable> actions = new ArrayDeque<>();
    private boolean initReceived = false;
    private boolean running = false;
    private OnGetProcessInfoListener onGetProcessInfoListener;
    private final Map<Integer, ExternalController> controllers = new HashMap<>(); // map deviceId -> controller
                                                                                  // implementation
    private InetAddress localhost;
    private byte inputType = DEFAULT_INPUT_TYPE;
    private final XServerDisplayActivity activity;
    private final List<Integer> gamepadClients = new CopyOnWriteArrayList<>();
    private SharedPreferences preferences;

    // Multi-controller support
    private static final int MAX_CONTROLLERS = 4;
    private static final int OSC_DEVICE_ID = -1;
    private FakeInputWriter[] writers = new FakeInputWriter[MAX_CONTROLLERS];
    private Map<Integer, Integer> deviceToSlot = new HashMap<>();
    private Set<Integer> usedSlots = new HashSet<>();
    private String fakeInputBasePath;
    private LocalServerSocket vibrationServer;
    private volatile boolean vibrationRunning = false;
    private boolean[] vibrationEnabledSlots = new boolean[MAX_CONTROLLERS]; // per-slot vibration toggle
    private boolean vibrationMasterEnabled = true; // master switch — off = NO controller vibration at all

    // Per-container rumble tuning, pushed from XServerDisplayActivity.setupUI (once the Container is
    // resolved) and re-pushed live from the in-game drawer. Mirrors Container.getVibrationMode()/
    // getVibrationIntensity() — see those for the mode values (0=Off 1=Controller 2=Device 3=Both).
    public static final int VIBRATION_MODE_OFF = 0;
    public static final int VIBRATION_MODE_CONTROLLER = 1;
    public static final int VIBRATION_MODE_DEVICE = 2;
    public static final int VIBRATION_MODE_BOTH = 3;
    private volatile int vibrationMode = VIBRATION_MODE_CONTROLLER;
    private volatile int vibrationIntensity = 100;

    // Gyro (motion aim) — "hold the activator + tilt the device" -> right stick / left stick / mouse.
    // Rate-mode math (deadzone -> invert -> sensitivity -> low-pass -> clamp) follows the reference
    // gyro implementation in the WinNative tree (WinHandler.updateGyroData / getOutputGamepadState).
    // Sensitivity/deadzone/smoothing/invert/activator are live user settings (in-game drawer, Controls
    // tab); the defaults below reproduce the original hardcoded P1 behaviour exactly.
    public static final int GYRO_TARGET_RIGHT_STICK = 0;
    public static final int GYRO_TARGET_LEFT_STICK = 1;
    public static final int GYRO_TARGET_MOUSE = 2;
    // Activator = the button that gates the gyro while held. ALWAYS = no gate (no hold needed).
    public static final int GYRO_ACTIVATOR_L1 = 0;
    public static final int GYRO_ACTIVATOR_L2 = 1;
    public static final int GYRO_ACTIVATOR_R1 = 2;
    public static final int GYRO_ACTIVATOR_R3 = 3;
    public static final int GYRO_ACTIVATOR_ALWAYS = 4;
    // How the activator gates the gyro. HOLD = the original behaviour (gyro runs while the button is
    // down); TOGGLE = a tap latches the gyro on until the next tap. Meaningless with ALWAYS, which has
    // no button to latch, so the gate below ignores the mode in that case.
    public static final int GYRO_ACTIVATION_HOLD = 0;
    public static final int GYRO_ACTIVATION_TOGGLE = 1;
    // How the tilt is read. RATE = the original path (angular velocity -> deflection; the stick
    // recentres as soon as you stop moving). ORIENTATION = "tilt to aim": the stick follows the ANGLE
    // the device is held at relative to a captured zero, so a held tilt keeps the stick deflected.
    // The two modes never share a sample: the activity picks ONE sensor from this value and rate
    // samples land in updateGyroData while orientation samples land in updateGyroOrientation. Nothing
    // in updateGyroData branches on the mode, so rate mode is byte-identical to before this existed.
    public static final int GYRO_MODE_RATE = 0;
    public static final int GYRO_MODE_ORIENTATION = 1;

    public static final boolean GYRO_ENABLED_DEFAULT = true;
    public static final int GYRO_TARGET_DEFAULT = GYRO_TARGET_RIGHT_STICK;
    public static final float GYRO_DEADZONE_DEFAULT = 0.05f;    // rad/s; below this is hand tremor
    public static final float GYRO_SENSITIVITY_DEFAULT = 2.0f;  // rad/s -> stick deflection gain
    public static final float GYRO_SMOOTHING_DEFAULT = 0.5f;    // 0 = raw, ->1 = heavier low-pass
    public static final int GYRO_ACTIVATOR_DEFAULT = GYRO_ACTIVATOR_L1;
    public static final int GYRO_ACTIVATION_MODE_DEFAULT = GYRO_ACTIVATION_HOLD;
    public static final int GYRO_MODE_DEFAULT = GYRO_MODE_RATE;

    private static final float GYRO_AXIS_EPSILON = 0.001f; // don't re-inject for sub-noise changes
    // rad/s * sensitivity -> pixels per sensor sample in mouse mode. Picked so sensitivity 2.0 gives
    // roughly the same "feel" as the right-stick path at the SENSOR_DELAY_GAME (~50 Hz) sample rate.
    private static final float GYRO_MOUSE_SCALE = 12.0f;
    // rad of tilt * sensitivity -> stick deflection in orientation mode. At sensitivity 1.0 that's
    // full deflection at ~14 deg off centre, so the stock 2.0 lands at ~7 deg — a wrist's worth.
    private static final float GYRO_ORIENTATION_STICK_GAIN = 4.0f;
    // getOrientation()'s yaw is undefined as the remapped pitch approaches +/-90 deg (gimbal lock):
    // the azimuth flips wildly for a motionless device. Past this we hold the last deflection rather
    // than emit garbage. 1.3 rad ~= 74.5 deg, comfortably outside any normal hand-held pose.
    private static final float GYRO_ORIENTATION_GIMBAL_LIMIT = 1.3f;
    // Axis/handedness verification aid — off unless `setprop log.tag.BLGyroOrient DEBUG` is set, and
    // rate-limited so even then it can't flood a 200 Hz path. Ship-safe, so it never needs removing.
    private static final String GYRO_ORIENT_LOG_TAG = "BLGyroOrient";
    private static final long GYRO_ORIENT_LOG_INTERVAL_MS = 200; // ~5 Hz

    private volatile boolean gyroEnabled = GYRO_ENABLED_DEFAULT;
    private volatile int gyroTarget = GYRO_TARGET_DEFAULT;
    private volatile float gyroDeadzone = GYRO_DEADZONE_DEFAULT;
    private volatile float gyroSensitivity = GYRO_SENSITIVITY_DEFAULT;
    private volatile float gyroSmoothing = GYRO_SMOOTHING_DEFAULT;
    private volatile boolean gyroInvertX = false;
    private volatile boolean gyroInvertY = false;
    private volatile int gyroActivator = GYRO_ACTIVATOR_DEFAULT;
    private volatile int gyroActivationMode = GYRO_ACTIVATION_MODE_DEFAULT;
    private volatile int gyroMode = GYRO_MODE_DEFAULT;
    // False until the launch-time container/shortcut resolution lands (applyGyroTuning). The sample
    // path is a strict no-op while it's false, so the gyro never runs on values the user didn't pick.
    private volatile boolean gyroConfigApplied = false;
    // Zero-rate bias measured by the out-of-game calibration (Input Controls -> Gyroscope). 0 = the
    // uncalibrated default, which makes the subtraction in updateGyroData a strict no-op.
    private volatile float gyroBiasX = 0.0f;
    private volatile float gyroBiasY = 0.0f;

    private float smoothedGyroX = 0.0f;
    private float smoothedGyroY = 0.0f;
    private float currentGyroStickX = 0.0f;
    private float currentGyroStickY = 0.0f;
    // Toggle-mode latch plus the previous activator level it edge-detects against. Both are touched
    // ONLY from the sensor path (updateGyroActivation) and the reset/config helpers, all on the main
    // thread, so plain fields are enough — no volatile, no lock on a 50-200 Hz path.
    private boolean gyroToggleLatched = false;
    private boolean gyroActivatorWasPressed = false;
    // Sub-pixel carry for mouse mode, so slow tilts still move the pointer instead of rounding to 0.
    private float accumulatedGyroMouseX = 0.0f;
    private float accumulatedGyroMouseY = 0.0f;
    // Orientation-mode zero reference: the pose captured the moment the gyro became active, which the
    // offsets below are measured against. Touched only from the sensor path and the reset/config
    // helpers (main thread), same as the toggle latch above.
    private boolean gyroOrientationCalibrated = false;
    private float orientationYawZero = 0.0f;
    private float orientationPitchZero = 0.0f;
    private long lastGyroOrientLogMs = 0;
    // Last controller that pushed state; the gyro re-injects through it so a held tilt keeps panning.
    private ExternalController gyroTargetController;
    private boolean gyroApplyLogged = false;
    // Scratch state for the gyro overlay — never handed out, so no per-event allocation.
    private final GamepadState outputGamepadState = new GamepadState();

    // Mouse-mode injection scratch. The pending delta is accumulated under this lock and drained by a
    // SINGLE pre-allocated Runnable, so a sustained tilt never allocates per sample (this path runs
    // while the game renders) and bursts coalesce into one packet instead of flooding the send queue.
    private final Object gyroMouseLock = new Object();
    private int pendingGyroMouseDx = 0;
    private int pendingGyroMouseDy = 0;
    private boolean gyroMouseActionQueued = false;
    private final Runnable gyroMouseAction = () -> {
        final int dx, dy;
        synchronized (gyroMouseLock) {
            dx = pendingGyroMouseDx;
            dy = pendingGyroMouseDy;
            pendingGyroMouseDx = 0;
            pendingGyroMouseDy = 0;
            gyroMouseActionQueued = false;
        }
        if (dx == 0 && dy == 0)
            return;
        sendData.rewind();
        sendData.put(RequestCodes.MOUSE_EVENT);
        sendData.putInt(10);
        sendData.putInt(MouseEventFlags.MOVE);
        sendData.putShort((short) dx);
        sendData.putShort((short) dy);
        sendData.putShort((short) 0);
        sendData.put((byte) 1); // cursor pos feedback (MOVE)
        sendPacket(CLIENT_PORT);
    };

    private boolean xinputDisabled;
    private boolean xinputDisabledInitialized = false;

    private int fallbackSlot = -1;

    private final InputManager inputManager;
    private final InputManager.InputDeviceListener inputDeviceListener;

    public WinHandler(XServerDisplayActivity activity) {
        this.activity = activity;
        this.inputManager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        this.inputDeviceListener = new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                releaseSlot(deviceId);
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
            }
        };
        inputManager.registerInputDeviceListener(inputDeviceListener, null);

        preferences = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());

        // Load per-slot vibration preferences (default: enabled)
        for (int i = 0; i < MAX_CONTROLLERS; i++) {
            vibrationEnabledSlots[i] = preferences.getBoolean("vibration_slot_" + i, true);
        }
        // Master switch (default: enabled) — a single kill-switch for ALL controller vibration.
        vibrationMasterEnabled = preferences.getBoolean("vibration_master_enabled", true);

        // Gyro tuning is NOT read here any more — it lives on the container (and, for the game-facing
        // half, on the shortcut). XServerDisplayActivity resolves the chain once at launch and pushes
        // it in via applyGyroTuning; until then the pipeline stays inert (gyroConfigApplied == false),
        // so a sample arriving before the container is known can't act on stale defaults.

        // Calibration bias — read once here (never on the sample path). GyroCalibrator honours the
        // device stamp, so a bias restored from another phone by Android auto-backup comes back 0.
        float[] bias = new float[2];
        GyroCalibrator.loadBias(activity, bias, preferences);
        gyroBiasX = bias[0];
        gyroBiasY = bias[1];
    }

    private boolean sendPacket(int port) {
        try {
            int size = sendData.position();
            if (size == 0)
                return false;
            sendPacket.setAddress(localhost);
            sendPacket.setPort(port);
            socket.send(sendPacket);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void exec(String command) {
        command = command.trim();
        if (command.isEmpty())
            return;

        // The `split` function here should be sensitive to paths with spaces.
        // Instead of splitting, let's assume that command is directly provided in two
        // parts: filename and parameters.
        // Adjust command splitting based on whether it contains quotes.

        String filename;
        String parameters;

        if (command.contains("\"")) {
            // If the command is quoted, extract the quoted part as the filename
            int firstQuote = command.indexOf("\"");
            int lastQuote = command.lastIndexOf("\"");
            filename = command.substring(firstQuote + 1, lastQuote);
            if (lastQuote + 1 < command.length()) {
                parameters = command.substring(lastQuote + 1).trim();
            } else {
                parameters = "";
            }
        } else {
            // Standard split when no quotes
            String[] cmdList = command.split(" ", 2);
            filename = cmdList[0];
            if (cmdList.length > 1) {
                parameters = cmdList[1];
            } else {
                parameters = "";
            }
        }

        addAction(() -> {
            byte[] filenameBytes = filename.getBytes();
            byte[] parametersBytes = parameters.getBytes();

            sendData.rewind();
            sendData.put(RequestCodes.EXEC);
            sendData.putInt(filenameBytes.length + parametersBytes.length + 8);
            sendData.putInt(filenameBytes.length);
            sendData.putInt(parametersBytes.length);
            sendData.put(filenameBytes);
            sendData.put(parametersBytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void killProcess(final String processName) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.KILL_PROCESS);
            byte[] bytes = processName.getBytes();
            sendData.putInt(bytes.length);
            sendData.put(bytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void listProcesses() {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.LIST_PROCESSES);
            sendData.putInt(0);

            boolean sentLP = sendPacket(CLIENT_PORT);
            Log.d("WinHandlerTM", "listProcesses sent=" + sentLP + " initReceived=" + initReceived + " listener=" + (onGetProcessInfoListener != null));
            if (!sentLP && onGetProcessInfoListener != null) {
                onGetProcessInfoListener.onGetProcessInfo(0, 0, null);
            }
        });
    }

    public void setProcessAffinity(final String processName, final int affinityMask) {
        addAction(() -> {
            byte[] bytes = processName.getBytes();
            sendData.rewind();
            sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            sendData.putInt(9 + bytes.length);
            sendData.putInt(0);
            sendData.putInt(affinityMask);
            sendData.put((byte) bytes.length);
            sendData.put(bytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void setProcessAffinity(final int pid, final int affinityMask) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            sendData.putInt(9);
            sendData.putInt(pid);
            sendData.putInt(affinityMask);
            sendData.put((byte) 0);
            sendPacket(CLIENT_PORT);
        });
    }

    public void mouseEvent(int flags, int dx, int dy, int wheelDelta) {
        if (!initReceived)
            return;
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.MOUSE_EVENT);
            sendData.putInt(10);
            sendData.putInt(flags);
            sendData.putShort((short) dx);
            sendData.putShort((short) dy);
            sendData.putShort((short) wheelDelta);
            sendData.put((byte) ((flags & MouseEventFlags.MOVE) != 0 ? 1 : 0)); // cursor pos feedback
            sendPacket(CLIENT_PORT);
        });
    }

    public void keyboardEvent(byte vkey, int flags) {
        if (!initReceived)
            return;
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.KEYBOARD_EVENT);
            sendData.put(vkey);
            sendData.putInt(flags);
            sendPacket(CLIENT_PORT);
        });
    }

    public void bringToFront(final String processName) {
        bringToFront(processName, 0);
    }

    public void bringToFront(final String processName, final long handle) {
        addAction(() -> {
            sendData.rewind();
            try {
                sendData.put(RequestCodes.BRING_TO_FRONT);
                byte[] bytes = processName.getBytes();
                sendData.putInt(bytes.length);
                // FIXME: Chinese and Japanese got from winhandler.exe are broken, and they
                // cause overflow.
                sendData.put(bytes);
                sendData.putLong(handle);
            } catch (java.nio.BufferOverflowException e) {
                e.printStackTrace();
                sendData.rewind();
            }
            sendPacket(CLIENT_PORT);
        });
    }

    private void addAction(Runnable action) {
        synchronized (actions) {
            actions.add(action);
            actions.notify();
        }
    }

    public OnGetProcessInfoListener getOnGetProcessInfoListener() {
        return onGetProcessInfoListener;
    }

    public void setOnGetProcessInfoListener(OnGetProcessInfoListener onGetProcessInfoListener) {
        synchronized (actions) {
            this.onGetProcessInfoListener = onGetProcessInfoListener;
        }
    }

    private void startSendThread() {
        Executors.newSingleThreadExecutor().execute(() -> {
            while (running) {
                synchronized (actions) {
                    while (initReceived && !actions.isEmpty())
                        actions.poll().run();
                    try {
                        actions.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        });
    }

    public void stop() {
        running = false;
        closeFakeInputWriter();

        if (socket != null) {
            socket.close();
            socket = null;
        }

        synchronized (actions) {
            actions.notify();
        }
    }

    public void startVibrationListener() {
        if (vibrationRunning)
            return;
        vibrationRunning = true;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                vibrationServer = new LocalServerSocket("winlator_vibration");
                Log.d("WinHandler", "Vibration listener started on abstract socket: winlator_vibration");

                while (vibrationRunning) {
                    LocalSocket client = vibrationServer.accept();
                    try {
                        java.io.InputStream is = client.getInputStream();
                        byte[] buf = new byte[8];
                        int read = is.read(buf);
                        if (read == 8) {
                            int strong = (buf[0] & 0xFF) | ((buf[1] & 0xFF) << 8);
                            int weak = (buf[2] & 0xFF) | ((buf[3] & 0xFF) << 8);
                            int durationMs = (buf[4] & 0xFF) | ((buf[5] & 0xFF) << 8);
                            int slot = (buf[6] & 0xFF) | ((buf[7] & 0xFF) << 8);
                            triggerVibration(strong, weak, durationMs, slot);
                        }
                        client.close();
                    } catch (IOException e) {
                        Log.e("WinHandler", "Vibration client error: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (vibrationRunning) {
                    Log.e("WinHandler", "Vibration listener error: " + e.getMessage());
                }
            }
        });
    }

    private void triggerVibration(int strong, int weak, int durationMs, int slot) {
        // Master kill-switch: off = no controller vibration at all, regardless of slot. This catches
        // rumble on ANY slot (incl. an out-of-range/unmapped slot the per-slot gate below would miss).
        if (!vibrationMasterEnabled)
            return;

        // Per-container mode: Off short-circuits everything below (per-slot gate, dispatch).
        int mode = vibrationMode;
        if (mode == VIBRATION_MODE_OFF)
            return;

        // Check if vibration is enabled for this slot
        if (slot >= 0 && slot < MAX_CONTROLLERS && !vibrationEnabledSlots[slot])
            return;

        int duration = Math.max(1, durationMs);
        boolean stopping = strong <= 0 && weak <= 0;

        // Controller (physical, or OSC/phone-fallback exactly as before) — Controller and Both modes.
        if (mode == VIBRATION_MODE_CONTROLLER || mode == VIBRATION_MODE_BOTH) {
            dispatchControllerVibration(strong, weak, duration, slot, stopping);
        }
        // Phone's own vibrator, independent of slot mapping — Device and Both modes.
        if (mode == VIBRATION_MODE_DEVICE || mode == VIBRATION_MODE_BOTH) {
            dispatchDeviceVibration(strong, weak, duration, stopping);
        }
    }

    /**
     * Controller-mode dispatch: resolves the same deviceId-owns-slot / OSC-or-no-vibrator phone
     * fallback that this method always used, then delivers via independent low/high motors
     * (VibratorManager, API 31+) when the target exposes ≥1 vibrator id, blending to one motor
     * otherwise. Below API 31 this always blends — identical to the pre-dual-motor behavior.
     */
    private void dispatchControllerVibration(int strong, int weak, int duration, int slot, boolean stopping) {
        // Find which deviceId owns this slot
        Integer deviceId = null;
        for (Map.Entry<Integer, Integer> entry : deviceToSlot.entrySet()) {
            if (entry.getValue() == slot) {
                deviceId = entry.getKey();
                break;
            }
        }

        android.view.InputDevice device = null;
        Vibrator fallbackVibrator = null;

        if (deviceId != null && deviceId == OSC_DEVICE_ID) {
            // OSC is mapped to this slot — use the phone vibrator
            fallbackVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        } else if (deviceId != null) {
            // Physical controller
            android.view.InputDevice candidate = android.view.InputDevice.getDevice(deviceId);
            if (candidate != null) {
                Vibrator v = candidate.getVibrator();
                if (v != null && v.hasVibrator()) {
                    device = candidate;
                } else {
                    // Fallback to phone vibrator if OSC is off and no other controller has fallen back
                    if (!deviceToSlot.containsKey(OSC_DEVICE_ID) && (fallbackSlot == -1 || fallbackSlot == slot)) {
                        fallbackVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                        fallbackSlot = slot;
                    }
                }
            }
        }

        if (stopping) {
            if (device != null) stopVibrationTarget(device);
            if (fallbackVibrator != null) fallbackVibrator.cancel();
            return;
        }

        if (device != null) {
            if (!dispatchDualMotor(device, strong, weak, duration)) {
                Vibrator v = device.getVibrator();
                if (v != null && v.hasVibrator()) {
                    vibrateBlended(v, strong, weak, duration);
                }
            }
            return;
        }

        if (fallbackVibrator != null && fallbackVibrator.hasVibrator()) {
            vibrateBlended(fallbackVibrator, strong, weak, duration);
        }
    }

    /** Device-mode dispatch: always the phone's own vibrator, regardless of which slot/controller
     *  triggered the rumble. Single-motor blend — phones don't expose independent XInput motors. */
    private void dispatchDeviceVibration(int strong, int weak, int duration, boolean stopping) {
        Vibrator vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator())
            return;

        if (stopping) {
            vibrator.cancel();
        } else {
            vibrateBlended(vibrator, strong, weak, duration);
        }
    }

    /**
     * Dual-motor delivery for a physical controller on API 31+: strong (low-frequency) drives the
     * first vibrator id, weak (high-frequency) drives the second, dispatched together via
     * CombinedVibration so both motors start in the same frame. Falls back to a single blended
     * motor when the device exposes exactly one vibrator id. Returns false (caller should fall
     * back to {@link #vibrateBlended}) when below API 31, the device has no VibratorManager, or
     * both scaled amplitudes are zero.
     */
    private boolean dispatchDualMotor(android.view.InputDevice device, int strong, int weak, long duration) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S)
            return false;

        android.os.VibratorManager vm = device.getVibratorManager();
        if (vm == null)
            return false;

        int[] ids = vm.getVibratorIds();
        if (ids == null || ids.length == 0)
            return false;

        // Sort ascending for a deterministic "low motor = ids[0], high motor = ids[1]" assignment.
        int[] sortedIds = ids.clone();
        java.util.Arrays.sort(sortedIds);

        int strongAmp = rawToAmplitude(strong);
        int weakAmp = rawToAmplitude(weak);
        if (strongAmp == 0 && weakAmp == 0)
            return false;

        if (sortedIds.length == 1) {
            Vibrator only = vm.getVibrator(sortedIds[0]);
            if (only == null) return false;
            // strongAmp/weakAmp are already intensity-scaled (rawToAmplitude) — just take the max,
            // no further intensity scaling here.
            int blended = Math.min(255, Math.max(1, Math.max(strongAmp, weakAmp)));
            only.vibrate(VibrationEffect.createOneShot(duration, blended));
            return true;
        }

        android.os.CombinedVibration.ParallelCombination combo = android.os.CombinedVibration.startParallel();
        boolean any = false;
        if (strongAmp > 0) {
            combo.addVibrator(sortedIds[0], VibrationEffect.createOneShot(duration, strongAmp));
            any = true;
        }
        if (weakAmp > 0) {
            combo.addVibrator(sortedIds[1], VibrationEffect.createOneShot(duration, weakAmp));
            any = true;
        }
        if (!any) return false;

        vm.vibrate(combo.combine());
        return true;
    }

    /** Stops an in-flight dual-motor rumble on a physical controller (VibratorManager cancel on
     *  API 31+, single-Vibrator cancel otherwise). */
    private void stopVibrationTarget(android.view.InputDevice device) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.os.VibratorManager vm = device.getVibratorManager();
            if (vm != null) {
                vm.cancel();
                return;
            }
        }
        Vibrator v = device.getVibrator();
        if (v != null) v.cancel();
    }

    /** Single-motor blend of strong+weak using the pre-dual-motor formula (max of the two, scaled to
     *  0..255), then applies the per-container intensity. Used for the API<31 fallback and for any
     *  single-vibrator target (phone, or a controller with exactly one motor id). */
    private void vibrateBlended(Vibrator vibrator, int strong, int weak, long duration) {
        int intensity = Math.max(strong, weak);
        int amplitude = Math.min(255, Math.max(1, (int) ((intensity / 65535.0f) * 255)));
        int scaled = applyIntensity(amplitude);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, scaled));
        } else {
            vibrator.vibrate(duration);
        }
    }

    /** Raw XInput amplitude (0..65535) -> 0..255, then scaled by the per-container intensity
     *  (floored at 1 so a non-zero input never quantises away to silence, 0 stays 0). */
    private int rawToAmplitude(int raw) {
        if (raw <= 0) return 0;
        int amplitude = Math.min(255, Math.max(1, (int) ((raw / 65535.0f) * 255)));
        return applyIntensity(amplitude);
    }

    /** Applies the per-container intensity (0..100) to an already-0..255 amplitude. */
    private int applyIntensity(int amplitude255) {
        if (amplitude255 <= 0 || vibrationIntensity <= 0) return 0;
        int scaled = (amplitude255 * vibrationIntensity) / 100;
        return Math.min(255, Math.max(1, scaled));
    }

    public boolean isVibrationEnabledForSlot(int slot) {
        if (slot >= 0 && slot < MAX_CONTROLLERS)
            return vibrationEnabledSlots[slot];
        return false;
    }

    public void setVibrationEnabledForSlot(int slot, boolean enabled) {
        if (slot >= 0 && slot < MAX_CONTROLLERS) {
            vibrationEnabledSlots[slot] = enabled;
            preferences.edit().putBoolean("vibration_slot_" + slot, enabled).apply();
        }
    }

    /** Master controller-vibration switch (persisted globally). Off = ALL controller rumble suppressed. */
    public boolean isVibrationMasterEnabled() {
        return vibrationMasterEnabled;
    }

    public void setVibrationMasterEnabled(boolean enabled) {
        vibrationMasterEnabled = enabled;
        preferences.edit().putBoolean("vibration_master_enabled", enabled).apply();
    }

    /** Per-container rumble mode/intensity, pushed once at launch (setupUI, after the Container is
     *  resolved) and again live from the in-game drawer / container editor. NOT persisted here —
     *  Container.setVibrationMode/setVibrationIntensity is the source of truth; this is just the
     *  live cache triggerVibration reads on the dispatch thread. Invalid mode falls back to
     *  Controller (matches Container's own default) rather than silently doing nothing.
     */
    public void setVibrationTuning(int mode, int intensity) {
        vibrationMode = (mode < VIBRATION_MODE_OFF || mode > VIBRATION_MODE_BOTH) ? VIBRATION_MODE_CONTROLLER : mode;
        vibrationIntensity = Math.min(100, Math.max(0, intensity));
    }

    public int getVibrationMode() {
        return vibrationMode;
    }

    public int getVibrationIntensity() {
        return vibrationIntensity;
    }

    public int getMaxControllers() {
        return MAX_CONTROLLERS;
    }

    private void handleRequest(byte requestCode, final int port) {
        switch (requestCode) {
            case RequestCodes.INIT: {
                initReceived = true;
                Log.d("WinHandlerTM", "INIT received from guest");

                preferences = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());

                if (!xinputDisabledInitialized) {
                    xinputDisabled = preferences.getBoolean("xinput_toggle", false);
                }
                synchronized (actions) {
                    actions.notify();
                }
                break;
            }

            case RequestCodes.GET_PROCESS: {
                if (onGetProcessInfoListener == null)
                    return;
                receiveData.position(receiveData.position() + 4);
                int numProcesses = receiveData.getShort();
                int index = receiveData.getShort();
                int pid = receiveData.getInt();
                long memoryUsage = receiveData.getLong();
                int affinityMask = receiveData.getInt();
                boolean wow64Process = receiveData.get() == 1;

                byte[] bytes = new byte[32];
                receiveData.get(bytes);
                String name = StringUtils.fromANSIString(bytes);

                Log.d("WinHandlerTM", "GET_PROCESS idx=" + index + "/" + numProcesses + " name=" + name);
                onGetProcessInfoListener.onGetProcessInfo(index, numProcesses,
                        new ProcessInfo(pid, name, memoryUsage, affinityMask, wow64Process));
                break;
            }
            case RequestCodes.GET_GAMEPAD: {
                break;
            }
            case RequestCodes.GET_GAMEPAD_STATE: {
                break;
            }
            case RequestCodes.RELEASE_GAMEPAD: {
                // currentController = null; // No longer needed
                // Maybe clear all controllers or reset mapping?
                // For now, doing nothing is safest as mapping is sticky.
            }
            case RequestCodes.CURSOR_POS_FEEDBACK: {
                short x = receiveData.getShort();
                short y = receiveData.getShort();
                XServer xServer = activity.getXServer();
                xServer.pointer.setX(x);
                xServer.pointer.setY(y);
                activity.getXServerView().requestRender();
                break;
            }
            default: {
                // Handle any other request codes if needed
                break;
            }
        }
    }

    public void start() {
        try {
            localhost = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            try {
                localhost = InetAddress.getByName("127.0.0.1");
            } catch (UnknownHostException ex) {
            }
        }

        running = true;
        startSendThread();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress((InetAddress) null, SERVER_PORT));

                while (running) {
                    socket.receive(receivePacket);

                    synchronized (actions) {
                        receiveData.rewind();
                        byte requestCode = receiveData.get();
                        Log.d("WinHandlerTM", "recv code=" + requestCode + " from port " + receivePacket.getPort());
                        handleRequest(requestCode, receivePacket.getPort());
                    }
                }
            } catch (IOException e) {
            }
        });
    }

    public void sendGamepadState() {
        final InputControlsView inputControlsView = activity.getInputControlsView();
        if (inputControlsView == null) return;
        final ControlsProfile profile = inputControlsView.getProfile();
        if (profile == null) {
            releaseSlot(OSC_DEVICE_ID);
            return;
        }

        final GamepadState gamepadState = profile.getGamepadState();
        final boolean useVirtualGamepad = profile.isVirtualGamepad()
                && inputControlsView.isShowTouchscreenControls();

        // Handle virtual gamepad (on-screen controls)
        if (useVirtualGamepad) {
            int slot = assignSlot(OSC_DEVICE_ID);
            if (slot >= 0 && writers[slot] != null) {
                writers[slot].writeGamepadState(getOutputGamepadState(gamepadState));
            }
        } else {
            releaseSlot(OSC_DEVICE_ID);
        }

    }

    public void sendGamepadState(ExternalController controller) {
        if (controller == null)
            return;

        // Remember the live controller so the gyro can re-push through it between input events.
        gyroTargetController = controller;

        // Check if this controller has bindings in the current profile
        // If it does, we should NOT send the raw state here, because InputControlsView
        // will send the remapped state via the no-arg sendGamepadState().
        InputControlsView inputControlsView = activity.getInputControlsView();
        ControlsProfile profile = inputControlsView != null ? inputControlsView.getProfile() : null;
        if (profile != null) {
            ExternalController profileController = profile.getController(controller.getDeviceId());
            if (profileController != null && profileController.getControllerBindingCount() > 0) {
                // If bindings are present, use the remappedState from the controller
                // This reverts the single-slot consolidation where the no-arg
                // sendGamepadState()
                // was solely responsible for sending remapped states.
                int slot = assignSlot(controller.getDeviceId());
                if (slot >= 0 && writers[slot] != null) {
                    writers[slot].writeGamepadState(getOutputGamepadState(controller.remappedState));
                }
                return; // Suppress raw state sending if remapped state was sent
            }
        }

        int slot = assignSlot(controller.getDeviceId());
        if (slot >= 0 && writers[slot] != null) {
            writers[slot].writeGamepadState(getOutputGamepadState(controller.state));
        }
    }

    /**
     * Feeds one gyroscope sample (rad/s about the device X and Y axes) into the configured target
     * (right stick, left stick or the mouse pointer). Called on the main thread from the activity's
     * SensorEventListener, so it shares a thread with the controller/OSC input path and needs no
     * extra synchronization.
     */
    public void updateGyroData(float rawGyroX, float rawGyroY) {
        if (!gyroConfigApplied || !gyroEnabled) {
            resetGyroRuntimeState();
            return;
        }

        // Calibration bias first, and deliberately so: BEFORE the deadzone (otherwise the deadzone has
        // to exceed the bias just to hold still, which is what makes fine aim mushy), BEFORE invert
        // (an inverted axis would subtract with the wrong sign), BEFORE sensitivity (the bias is in
        // rad/s, sensitivity is a unitless gain) and BEFORE the low-pass (which would otherwise
        // converge on bias*sensitivity and never let the stick recenter). Uncalibrated = 0 = no-op.
        rawGyroX -= gyroBiasX;
        rawGyroY -= gyroBiasY;

        // Toggle-mode edge, evaluated HERE and nowhere else. Two reasons it can't move:
        //  - not into sendGamepadState(): it has several call sites and the gyro re-enters it itself
        //    (pushGyroToActiveTarget), and the raw/remapped split means one physical press can arrive
        //    twice — the latch would flip twice and land back where it started.
        //  - not below the deadzone block: with the device held still both axes deadzone to zero and
        //    this method returns early further down, so a tap made while holding still would never be
        //    seen at all.
        // Caveat by design: the edge is sampled at the sensor rate (SENSOR_DELAY_GAME, ~50 Hz worst
        // case), so a sub-20ms tap can slip between samples. Real presses run 60-150ms, so this is
        // fine — don't "fix" it by moving the edge into the input event path.
        updateGyroActivation();

        if (Math.abs(rawGyroX) < gyroDeadzone)
            rawGyroX = 0.0f;
        if (Math.abs(rawGyroY) < gyroDeadzone)
            rawGyroY = 0.0f;

        if (gyroInvertX)
            rawGyroX = -rawGyroX;
        if (gyroInvertY)
            rawGyroY = -rawGyroY;

        if (gyroTarget == GYRO_TARGET_MOUSE) {
            updateGyroMouse(rawGyroX, rawGyroY);
            return;
        }

        rawGyroX *= gyroSensitivity;
        rawGyroY *= gyroSensitivity;

        final float smoothing = gyroSmoothing;
        smoothedGyroX = (smoothedGyroX * smoothing) + (rawGyroX * (1.0f - smoothing));
        smoothedGyroY = (smoothedGyroY * smoothing) + (rawGyroY * (1.0f - smoothing));

        float nextGyroStickX = clamp(smoothedGyroX, -1.0f, 1.0f);
        float nextGyroStickY = clamp(smoothedGyroY, -1.0f, 1.0f);
        // Snap the low-pass tail to exact zero so the stick fully recenters once motion stops.
        if (Math.abs(nextGyroStickX) < GYRO_AXIS_EPSILON) nextGyroStickX = 0.0f;
        if (Math.abs(nextGyroStickY) < GYRO_AXIS_EPSILON) nextGyroStickY = 0.0f;
        if (Math.abs(nextGyroStickX - currentGyroStickX) <= GYRO_AXIS_EPSILON
                && Math.abs(nextGyroStickY - currentGyroStickY) <= GYRO_AXIS_EPSILON) {
            return;
        }
        currentGyroStickX = nextGyroStickX;
        currentGyroStickY = nextGyroStickY;

        pushGyroToActiveTarget();
    }

    /**
     * Feeds one ORIENTATION sample (yaw and pitch in radians, already remapped for the display
     * rotation by the activity) into the configured stick. Deliberately a separate entry point rather
     * than a branch inside updateGyroData: rate mode is the shipped, device-proven path and stays
     * textually untouched, so selecting rate mode is a provable no-op. The activity registers exactly
     * one sensor for the selected mode, so only one of the two methods is ever called in a session.
     *
     * The pipeline mirrors rate mode — deadzone, invert, sensitivity, low-pass, clamp — but on the
     * ANGLE OFFSET from a captured zero instead of an angular rate, so a held tilt sustains the
     * deflection. Same thread and the same allocation-free rules as updateGyroData.
     */
    public void updateGyroOrientation(float yaw, float pitch) {
        if (!gyroConfigApplied || !gyroEnabled) {
            resetGyroRuntimeState();
            return;
        }

        // NOT subtracted here, on purpose: the calibration bias is a zero-RATE offset in rad/s and the
        // input on this path is an angle, so the subtraction would be dimensionally meaningless. The
        // zero-reference capture below already cancels any constant offset, which is what the bias
        // does for rate mode. The bias stays live for rate mode and its UI stays visible in both.

        // Same single call site as rate mode — this is the only per-sample hook in orientation mode,
        // so the toggle latch would never see an edge without it.
        updateGyroActivation();

        if (!isGyroActivatorHeld()) {
            // Inactive: drop the zero reference so the NEXT activation recaptures centre (this is the
            // auto-recenter for Hold and Toggle alike — Hold never reaches updateGyroActivation), then
            // clear the filter and the overlay so the stick doesn't stay deflected.
            gyroOrientationCalibrated = false;
            smoothedGyroX = 0.0f;
            smoothedGyroY = 0.0f;
            clearGyroStickOverlay();
            return;
        }

        // Gimbal guard: hold the previous deflection instead of emitting a garbage yaw. Placed before
        // the capture too, so a zero reference can't be taken from a pose where yaw is undefined.
        if (Math.abs(pitch) > GYRO_ORIENTATION_GIMBAL_LIMIT)
            return;

        if (!gyroOrientationCalibrated) {
            orientationYawZero = yaw;
            orientationPitchZero = pitch;
            gyroOrientationCalibrated = true;
        }

        float deltaX = wrapAngle(yaw - orientationYawZero);
        float deltaY = wrapAngle(pitch - orientationPitchZero);

        // Same setting as rate mode, reinterpreted: here it's a dead CONE around the captured centre
        // rather than a minimum rate, so the stock 0.05 reads as ~2.9 degrees of slop. One setting on
        // purpose — a second "orientation deadzone" would be one more knob describing the same hand.
        if (Math.abs(deltaX) < gyroDeadzone)
            deltaX = 0.0f;
        if (Math.abs(deltaY) < gyroDeadzone)
            deltaY = 0.0f;

        if (gyroInvertX)
            deltaX = -deltaX;
        if (gyroInvertY)
            deltaY = -deltaY;

        deltaX *= gyroSensitivity * GYRO_ORIENTATION_STICK_GAIN;
        deltaY *= gyroSensitivity * GYRO_ORIENTATION_STICK_GAIN;

        final float smoothing = gyroSmoothing;
        smoothedGyroX = (smoothedGyroX * smoothing) + (deltaX * (1.0f - smoothing));
        smoothedGyroY = (smoothedGyroY * smoothing) + (deltaY * (1.0f - smoothing));

        // The clamp is load-bearing here in a way it isn't in rate mode: tilt far enough and the raw
        // gain runs well past 1.0, and the stick has to saturate rather than wrap or overshoot.
        float nextGyroStickX = clamp(smoothedGyroX, -1.0f, 1.0f);
        float nextGyroStickY = clamp(smoothedGyroY, -1.0f, 1.0f);
        if (Math.abs(nextGyroStickX) < GYRO_AXIS_EPSILON) nextGyroStickX = 0.0f;
        if (Math.abs(nextGyroStickY) < GYRO_AXIS_EPSILON) nextGyroStickY = 0.0f;
        // Keep this early-out: a held tilt is near-constant, so without it every single sample would
        // re-push an identical gamepad state for as long as the user holds the device still.
        if (Math.abs(nextGyroStickX - currentGyroStickX) <= GYRO_AXIS_EPSILON
                && Math.abs(nextGyroStickY - currentGyroStickY) <= GYRO_AXIS_EPSILON) {
            return;
        }
        currentGyroStickX = nextGyroStickX;
        currentGyroStickY = nextGyroStickY;

        logGyroOrientation(nextGyroStickX, nextGyroStickY);
        pushGyroToActiveTarget();
    }

    /**
     * Wraps a radian difference back into [-PI, PI] so crossing the yaw discontinuity doesn't throw
     * the stick to the opposite extreme. Two branches rather than Math.IEEEremainder: the inputs are
     * two angles already in [-PI, PI], so the difference is in [-2PI, 2PI] and one correction is
     * always enough — and this costs a compare instead of a libm call on the sensor path.
     */
    private static float wrapAngle(float radians) {
        if (radians > (float) Math.PI) radians -= (float) (2.0 * Math.PI);
        else if (radians < -(float) Math.PI) radians += (float) (2.0 * Math.PI);
        return radians;
    }

    /**
     * Rate-limited debug of the computed deflection, used to verify the axis mapping and the invert
     * handedness on real hardware (`adb shell setprop log.tag.BLGyroOrient DEBUG`). isLoggable is a
     * cached property read, so with the tag off this is a compare and a return.
     */
    private void logGyroOrientation(float stickX, float stickY) {
        if (!Log.isLoggable(GYRO_ORIENT_LOG_TAG, Log.DEBUG))
            return;
        long now = SystemClock.uptimeMillis();
        if (now - lastGyroOrientLogMs < GYRO_ORIENT_LOG_INTERVAL_MS)
            return;
        lastGyroOrientLogMs = now;
        Log.d(GYRO_ORIENT_LOG_TAG, "deltaX=" + stickX + " deltaY=" + stickY + " target=" + gyroTarget);
    }

    /**
     * Manual recenter: the next orientation sample recaptures the current pose as centre. The drawer's
     * Recenter row is the only caller and it is NOT optional — with the "Always" activator there is no
     * rising edge for the whole session, so this is the only way to fix a drifted centre.
     */
    public void recenterGyroOrientation() {
        gyroOrientationCalibrated = false;
        smoothedGyroX = 0.0f;
        smoothedGyroY = 0.0f;
    }

    /**
     * Re-injects the current gamepad state for whichever target holds the activator, so a sustained
     * tilt keeps panning even while no controller/touch event is arriving. A no-op when the
     * activator isn't held, and FakeInputWriter still diffs the axes, so an unchanged state writes
     * nothing.
     */
    private void pushGyroToActiveTarget() {
        ExternalController controller = gyroTargetController;
        if (controller != null && (isGyroTargetActive(controller.state)
                || isGyroTargetActive(controller.remappedState))) {
            sendGamepadState(controller);
            return;
        }
        if (activity.getInputControlsView() == null)
            return;
        ControlsProfile profile = activity.getInputControlsView().getProfile();
        if (profile != null && isGyroTargetActive(profile.getGamepadState())) {
            sendGamepadState();
        }
    }

    /**
     * Same walk as pushGyroToActiveTarget, but WITHOUT the activation gate. Used the moment the toggle
     * un-latches: the button press that turned it off was injected while the latch was still on (the
     * edge is detected here on the sensor path, not in the event path), so the game is holding a state
     * that still carries the gyro deflection and the gated push would refuse to correct it.
     */
    private void pushGyroClearToLastTarget() {
        ExternalController controller = gyroTargetController;
        if (controller != null) {
            sendGamepadState(controller);
            return;
        }
        if (activity.getInputControlsView() == null)
            return;
        if (activity.getInputControlsView().getProfile() != null)
            sendGamepadState();
    }

    /**
     * Toggle-mode edge detect — called exactly once per gyro sample from updateGyroData. Reads the
     * activator off the same sources isGyroActivatorHeld() walks (the target controller's raw and
     * remapped state, then the on-screen-controls profile), so the latch responds to whichever one the
     * user is actually pressing. Hold mode and "Always" leave immediately: nothing to latch, and the
     * early-out keeps the hot path exactly as cheap as it was before toggle existed.
     */
    private void updateGyroActivation() {
        if (gyroActivationMode != GYRO_ACTIVATION_TOGGLE || gyroActivator == GYRO_ACTIVATOR_ALWAYS)
            return;

        // OR across every source, exactly as isGyroActivatorHeld does — a pad can be connected without
        // being the thing the user is pressing, so falling through to the on-screen controls matters.
        boolean pressed = false;
        ExternalController controller = gyroTargetController;
        if (controller != null && (isGyroActivatorPressed(controller.state)
                || isGyroActivatorPressed(controller.remappedState))) {
            pressed = true;
        }
        if (!pressed && activity.getInputControlsView() != null) {
            ControlsProfile profile = activity.getInputControlsView().getProfile();
            pressed = profile != null && isGyroActivatorPressed(profile.getGamepadState());
        }

        if (pressed && !gyroActivatorWasPressed) {
            gyroToggleLatched = !gyroToggleLatched;
            if (!gyroToggleLatched) {
                // Latched off: drop the deflection and the filter/carry state, then push once through
                // the ungated path so the stick actually recenters instead of waiting for whatever
                // controller event happens to come next.
                currentGyroStickX = 0.0f;
                currentGyroStickY = 0.0f;
                smoothedGyroX = 0.0f;
                smoothedGyroY = 0.0f;
                accumulatedGyroMouseX = 0.0f;
                accumulatedGyroMouseY = 0.0f;
                // Orientation mode: drop the zero reference too, so the next tap-on recaptures centre
                // from wherever the device is being held rather than resuming an hours-old pose.
                gyroOrientationCalibrated = false;
                pushGyroClearToLastTarget();
            }
        }
        gyroActivatorWasPressed = pressed;
    }

    /**
     * Rate-mode mouse path: the tilt drives the POINTER instead of a stick, which is the only gyro
     * target that does anything on a Wine desktop or in a mouse-look game. Deltas stay proportional
     * to the tilt rate (no fixed-speed drift) and the sub-pixel remainder is carried between samples
     * so a slow tilt still moves. Allocation-free — see gyroMouseAction.
     */
    private void updateGyroMouse(float rawGyroX, float rawGyroY) {
        // Mouse mode never touches the sticks; drop any overlay a previous stick session left behind.
        clearGyroStickOverlay();

        if (!isGyroActivatorHeld()) {
            smoothedGyroX = 0.0f;
            smoothedGyroY = 0.0f;
            accumulatedGyroMouseX = 0.0f;
            accumulatedGyroMouseY = 0.0f;
            return;
        }

        final float smoothing = gyroSmoothing;
        smoothedGyroX = (smoothedGyroX * smoothing) + (rawGyroX * (1.0f - smoothing));
        smoothedGyroY = (smoothedGyroY * smoothing) + (rawGyroY * (1.0f - smoothing));

        accumulatedGyroMouseX += smoothedGyroX * gyroSensitivity * GYRO_MOUSE_SCALE;
        accumulatedGyroMouseY += smoothedGyroY * gyroSensitivity * GYRO_MOUSE_SCALE;

        int dx = (int) accumulatedGyroMouseX;
        int dy = (int) accumulatedGyroMouseY;
        if (dx == 0 && dy == 0)
            return;
        accumulatedGyroMouseX -= dx;
        accumulatedGyroMouseY -= dy;

        // Same seam the on-screen-controls mouse timer uses: relative mode goes to winhandler.exe as
        // real relative motion (mouse-look games), otherwise we move the X pointer (desktop/windowed).
        XServer xServer = activity != null ? activity.getXServer() : null;
        if (xServer != null && !xServer.isRelativeMouseMovement()) {
            xServer.injectPointerMoveDelta(dx, dy);
            return;
        }
        queueGyroMouseEvent(dx, dy);
    }

    /** Coalescing, allocation-free enqueue of a relative mouse move onto the existing send thread. */
    private void queueGyroMouseEvent(int dx, int dy) {
        if (!initReceived)
            return;
        synchronized (gyroMouseLock) {
            pendingGyroMouseDx += dx;
            pendingGyroMouseDy += dy;
            if (gyroMouseActionQueued)
                return;
            gyroMouseActionQueued = true;
        }
        addAction(gyroMouseAction);
    }

    /** Zeroes the stick overlay and re-pushes once, so the stick can't stay stuck deflected. */
    private void clearGyroStickOverlay() {
        if (currentGyroStickX == 0.0f && currentGyroStickY == 0.0f)
            return;
        currentGyroStickX = 0.0f;
        currentGyroStickY = 0.0f;
        pushGyroToActiveTarget();
    }

    /**
     * Full runtime reset — used when the gyro is switched off, re-pointed at another target or
     * re-configured, when the target controller disappears, and when the activity drops the sensor
     * listener. Public because XServerDisplayActivity has to be able to clear a toggle latch on
     * pause: no samples arrive in the background, so a latched-on overlay would otherwise stay frozen
     * into the last written gamepad state.
     */
    public void resetGyroRuntimeState() {
        smoothedGyroX = 0.0f;
        smoothedGyroY = 0.0f;
        accumulatedGyroMouseX = 0.0f;
        accumulatedGyroMouseY = 0.0f;
        // Overlay first, THEN the latch: clearGyroStickOverlay re-pushes through the activation gate,
        // and dropping the latch ahead of it would gate that clean push away — freezing the last
        // deflected state into the game, which is the exact bug this method exists to prevent.
        clearGyroStickOverlay();
        gyroToggleLatched = false;
        gyroActivatorWasPressed = false;
        gyroOrientationCalibrated = false;
    }

    /**
     * The one activation gate: in toggle mode the latch IS the state, in hold mode it's the live
     * button. "Always" keeps ignoring the mode — there's no button to tap, so the gate always passes.
     */
    private boolean isGyroTargetActive(GamepadState state) {
        if (gyroActivationMode == GYRO_ACTIVATION_TOGGLE && gyroActivator != GYRO_ACTIVATOR_ALWAYS)
            return gyroToggleLatched;
        return isGyroActivatorPressed(state);
    }

    /** True when the activator is held on any live target (or the user chose "Always on"). */
    private boolean isGyroActivatorHeld() {
        if (gyroActivator == GYRO_ACTIVATOR_ALWAYS)
            return true;
        // Toggle mode: the latch is the whole answer, so short-circuit before walking the targets.
        // That's also how the mouse path inherits the latch without any plumbing of its own.
        if (gyroActivationMode == GYRO_ACTIVATION_TOGGLE)
            return gyroToggleLatched;
        ExternalController controller = gyroTargetController;
        if (controller != null && (isGyroActivatorPressed(controller.state)
                || isGyroActivatorPressed(controller.remappedState)))
            return true;
        if (activity.getInputControlsView() == null)
            return false;
        ControlsProfile profile = activity.getInputControlsView().getProfile();
        return profile != null && isGyroActivatorPressed(profile.getGamepadState());
    }

    // Activation gate: gyro only contributes while the chosen button is held on the state we're about
    // to inject. "Always on" needs no button at all, so it short-circuits before the state lookup.
    private boolean isGyroActivatorPressed(GamepadState state) {
        if (gyroActivator == GYRO_ACTIVATOR_ALWAYS)
            return true;
        if (state == null)
            return false;
        return state.isPressed(gyroActivatorButtonIndex());
    }

    private byte gyroActivatorButtonIndex() {
        switch (gyroActivator) {
            case GYRO_ACTIVATOR_L2: return ExternalController.IDX_BUTTON_L2;
            case GYRO_ACTIVATOR_R1: return ExternalController.IDX_BUTTON_R1;
            case GYRO_ACTIVATOR_R3: return ExternalController.IDX_BUTTON_R3;
            default:                return ExternalController.IDX_BUTTON_L1;
        }
    }

    /**
     * Overlays the gyro deflection on the configured stick. Returns baseState untouched whenever the
     * gyro isn't contributing, so the normal controller path stays byte-identical; when it is, the
     * deltas are added to (never replace) the physical stick and clamped back into range.
     */
    private GamepadState getOutputGamepadState(GamepadState baseState) {
        if (baseState == null)
            return baseState;
        if (currentGyroStickX == 0.0f && currentGyroStickY == 0.0f)
            return baseState;
        if (gyroTarget == GYRO_TARGET_MOUSE)
            return baseState;
        if (!isGyroTargetActive(baseState))
            return baseState;

        outputGamepadState.copy(baseState);
        if (gyroTarget == GYRO_TARGET_LEFT_STICK) {
            outputGamepadState.thumbLX = clamp(baseState.thumbLX + currentGyroStickX, -1.0f, 1.0f);
            outputGamepadState.thumbLY = clamp(baseState.thumbLY + currentGyroStickY, -1.0f, 1.0f);
        } else {
            outputGamepadState.thumbRX = clamp(baseState.thumbRX + currentGyroStickX, -1.0f, 1.0f);
            outputGamepadState.thumbRY = clamp(baseState.thumbRY + currentGyroStickY, -1.0f, 1.0f);
        }

        if (!gyroApplyLogged) {
            gyroApplyLogged = true;
            Log.i("WinHandlerGyro", "Gyro applied to stick (first sample): target=" + gyroTarget
                    + " x=" + currentGyroStickX + " y=" + currentGyroStickY);
        }
        return outputGamepadState;
    }

    // ---- Gyro settings (runtime mirror only — the container/shortcut owns persistence) ----
    //
    // These fields are written exactly twice per session: once by applyGyroTuning at launch, then by
    // the in-game drawer callbacks (which persist to the shortcut/container themselves). Nothing on
    // the sample path ever reads the container, so updateGyroData stays allocation-free.

    public boolean isGyroEnabled()      { return gyroEnabled; }
    public int getGyroTarget()          { return gyroTarget; }
    public float getGyroSensitivity()   { return gyroSensitivity; }
    public float getGyroDeadzone()      { return gyroDeadzone; }
    public float getGyroSmoothing()     { return gyroSmoothing; }
    public boolean isGyroInvertX()      { return gyroInvertX; }
    public boolean isGyroInvertY()      { return gyroInvertY; }
    public int getGyroActivator()       { return gyroActivator; }
    public int getGyroActivationMode()  { return gyroActivationMode; }
    public int getGyroMode()            { return gyroMode; }

    /**
     * Applies the whole resolved gyro configuration in one shot (launch time, from
     * XServerDisplayActivity once the container/shortcut chain is known) and arms the sample path.
     * Same clamps as the individual setters below, so a hand-edited container JSON can't get through.
     */
    public void applyGyroTuning(boolean enabled, int target, int activator, int activationMode,
                                int mode, float sensitivity, float deadzone, float smoothing,
                                boolean invertX, boolean invertY) {
        gyroEnabled = enabled;
        gyroTarget = (target < GYRO_TARGET_RIGHT_STICK || target > GYRO_TARGET_MOUSE)
                ? GYRO_TARGET_DEFAULT : target;
        gyroMode = sanitizeGyroMode(mode);
        gyroActivator = (activator < GYRO_ACTIVATOR_L1 || activator > GYRO_ACTIVATOR_ALWAYS)
                ? GYRO_ACTIVATOR_DEFAULT : activator;
        gyroActivationMode = (activationMode < GYRO_ACTIVATION_HOLD || activationMode > GYRO_ACTIVATION_TOGGLE)
                ? GYRO_ACTIVATION_MODE_DEFAULT : activationMode;
        gyroSensitivity = clamp(sensitivity, 0.1f, 10.0f);
        gyroDeadzone = clamp(deadzone, 0.0f, 0.5f);
        gyroSmoothing = clamp(smoothing, 0.0f, 0.95f);
        gyroInvertX = invertX;
        gyroInvertY = invertY;
        // Target may have changed under us — drop any stale deflection before arming.
        resetGyroRuntimeState();
        gyroConfigApplied = true;
    }

    public void setGyroEnabled(boolean enabled) {
        gyroEnabled = enabled;
        if (!enabled)
            resetGyroRuntimeState();
    }

    /** Switching target must drop the old overlay, or the previous stick stays deflected forever. */
    public void setGyroTarget(int target) {
        gyroTarget = (target < GYRO_TARGET_RIGHT_STICK || target > GYRO_TARGET_MOUSE)
                ? GYRO_TARGET_DEFAULT : target;
        // Re-run the mouse rule: picking the mouse target has to knock orientation mode back to rate.
        gyroMode = sanitizeGyroMode(gyroMode);
        resetGyroRuntimeState();
    }

    /** Switching read mode drops the zero reference and the overlay via the shared reset. */
    public void setGyroMode(int mode) {
        gyroMode = sanitizeGyroMode(mode);
        resetGyroRuntimeState();
    }

    /**
     * Range check plus the one rule the two settings share: orientation mode can NOT drive the mouse.
     * That path integrates into a sub-pixel accumulator and emits relative deltas, so a held tilt is a
     * constant delta and the pointer accelerates into a screen edge and pegs there. The UI disables
     * the combination, and this makes a hand-edited container JSON unable to reach it either.
     */
    private int sanitizeGyroMode(int mode) {
        if (mode < GYRO_MODE_RATE || mode > GYRO_MODE_ORIENTATION)
            return GYRO_MODE_DEFAULT;
        if (mode == GYRO_MODE_ORIENTATION && gyroTarget == GYRO_TARGET_MOUSE)
            return GYRO_MODE_RATE;
        return mode;
    }

    public void setGyroSensitivity(float sensitivity) {
        gyroSensitivity = clamp(sensitivity, 0.1f, 10.0f);
    }

    public void setGyroDeadzone(float deadzone) {
        gyroDeadzone = clamp(deadzone, 0.0f, 0.5f);
    }

    public void setGyroSmoothing(float smoothing) {
        gyroSmoothing = clamp(smoothing, 0.0f, 0.95f);
    }

    public void setGyroInvertX(boolean invert) {
        gyroInvertX = invert;
    }

    public void setGyroInvertY(boolean invert) {
        gyroInvertY = invert;
    }

    public void setGyroActivator(int activator) {
        gyroActivator = (activator < GYRO_ACTIVATOR_L1 || activator > GYRO_ACTIVATOR_ALWAYS)
                ? GYRO_ACTIVATOR_DEFAULT : activator;
        resetGyroRuntimeState();
    }

    /** Changing the mode has to drop the latch too, or a toggled-on gyro survives the switch to hold. */
    public void setGyroActivationMode(int activationMode) {
        gyroActivationMode = (activationMode < GYRO_ACTIVATION_HOLD || activationMode > GYRO_ACTIVATION_TOGGLE)
                ? GYRO_ACTIVATION_MODE_DEFAULT : activationMode;
        resetGyroRuntimeState();
    }

    /**
     * Pushes a freshly measured calibration bias in (Input Controls -> Gyroscope, then back to the
     * running session). Both fields are volatile and the sample path only reads them, so this stays a
     * plain assignment — the bias is never re-read from prefs on the sample path.
     */
    public void setGyroBias(float biasX, float biasY) {
        gyroBiasX = biasX;
        gyroBiasY = biasY;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Assign a slot to a device using FCFS. Sticky slots - disconnect keeps
     * reservation.
     */
    private int assignSlot(int deviceId) {
        Integer existing = deviceToSlot.get(deviceId);
        if (existing != null)
            return existing;

        for (int slot = 0; slot < MAX_CONTROLLERS; slot++) {
            if (!usedSlots.contains(slot)) {
                usedSlots.add(slot);
                deviceToSlot.put(deviceId, slot);
                if (fakeInputBasePath != null && writers[slot] == null) {
                    writers[slot] = new FakeInputWriter(fakeInputBasePath, slot);
                    writers[slot].open();
                    Log.d("WinHandler", "Assigned device " + deviceId + " to slot " + slot);
                }
                return slot;
            }
        }
        Log.w("WinHandler", "No slots available for device " + deviceId);
        return -1;
    }

    private void releaseSlot(int deviceId) {
        Integer slot = deviceToSlot.remove(deviceId);
        if (slot != null) {
            if (fallbackSlot == slot) fallbackSlot = -1;
            if (writers[slot] != null) {
                writers[slot].softRelease();
            }
            usedSlots.remove(slot);
            if (gyroTargetController != null && gyroTargetController.getDeviceId() == deviceId) {
                gyroTargetController = null;
                // Worst case for toggle mode: latched on and then the pad is unplugged, leaving no
                // button anywhere to tap it back off. Drop the latch along with the device.
                resetGyroRuntimeState();
            }
            controllers.remove(deviceId);
            Log.d("WinHandler", "Device " + deviceId + " disconnected (or OSC disabled). Slot released: " + slot);
        }
    }

    public void setXInputDisabled(boolean disabled) {
        this.xinputDisabled = disabled;
        this.xinputDisabledInitialized = true;
        Log.d("WinHandler", "XInput Disabled set to: " + xinputDisabled);
    }

    /**
     * @param fakeInputPath Path to the fake-input directory (e.g.,
     *                      /home/xuser/fake-input)
     */
    public void setFakeInputPath(String fakeInputPath) {
        if (fakeInputPath != null && !fakeInputPath.isEmpty()) {
            this.fakeInputBasePath = fakeInputPath;
            Log.d("WinHandler", "FakeInputWriter base path set: " + fakeInputPath);
            startVibrationListener();
        }
    }

    public void closeFakeInputWriter() {
        if (inputManager != null && inputDeviceListener != null) {
            inputManager.unregisterInputDeviceListener(inputDeviceListener);
        }
        for (int i = 0; i < MAX_CONTROLLERS; i++) {
            if (writers[i] != null) {
                writers[i].destroy();
                writers[i] = null;
            }
        }
        deviceToSlot.clear();
        usedSlots.clear();
        controllers.clear();
        fallbackSlot = -1;

        vibrationRunning = false;
        if (vibrationServer != null) {
            try {
                vibrationServer.close();
            } catch (IOException e) {
            }
            vibrationServer = null;
        }
    }

    private ExternalController getController(int deviceId) {
        if (controllers.containsKey(deviceId)) {
            return controllers.get(deviceId);
        }
        ExternalController controller = ExternalController.getController(deviceId);
        if (controller != null) {
            controllers.put(deviceId, controller);
        }
        return controller;
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean handled = false;
        ExternalController controller = getController(event.getDeviceId());

        if (controller != null) {
            handled = controller.updateStateFromMotionEvent(event);
            if (handled)
                sendGamepadState(controller);
        }
        return handled;
    }

    public boolean onKeyEvent(KeyEvent event) {
        boolean handled = false;
        ExternalController controller = getController(event.getDeviceId());

        if (controller != null && event.getRepeatCount() == 0) {
            int action = event.getAction();

            if (action == KeyEvent.ACTION_DOWN) {
                handled = controller.updateStateFromKeyEvent(event);
            } else if (action == KeyEvent.ACTION_UP) {
                handled = controller.updateStateFromKeyEvent(event);
            }

            if (handled)
                sendGamepadState(controller);
        }
        return handled;
    }

    public void releaseAllControllerInputs() {
        for (ExternalController controller : controllers.values()) {
            controller.state.reset();
            controller.remappedState.reset();
            sendGamepadState(controller);
        }
        InputControlsView inputControlsView = activity.getInputControlsView();
        ControlsProfile profile = inputControlsView != null ? inputControlsView.getProfile() : null;
        if (profile == null) return;
        for (ExternalController controller : profile.getControllers()) {
            controller.state.reset();
            controller.remappedState.reset();
            sendGamepadState(controller);
        }
    }

    public byte getInputType() {
        return inputType;
    }

    public void setInputType(byte inputType) {
        this.inputType = inputType;
    }

    public void execWithDelay(String command, int delaySeconds) {
        if (command == null || command.trim().isEmpty() || delaySeconds < 0)
            return;

        // Use a scheduled executor for delay
        Executors.newSingleThreadScheduledExecutor().schedule(() -> exec(command), delaySeconds, TimeUnit.SECONDS);
    }

}
