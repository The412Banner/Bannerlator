package com.winlator.star.inputcontrols;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Fake evdev transport for the emulated Xbox 360 pads.
 *
 * <p>Replaces the old append-file transport (which desynced after ~15 min when an
 * unlooped {@code channel.write} left a partial event in the file and the
 * IOException was swallowed) with a fixed-size mmap RING BUFFER shared with the
 * native reader in {@code fakeinput.cpp}. Delta events are published into the
 * ring under a monotonic {@code write_seq}; an authoritative absolute-state
 * snapshot is published alongside under a seqlock so the native reader can replay
 * a full keyframe to heal any desync (open, ring overflow) without duplicate
 * input. Ring layout (magic/version/offsets) is a paired ABI with the native
 * {@code FakeInputRingHeader} — keep the two in lockstep.
 */
public class FakeInputWriter {
    private static final String TAG = "FakeInputWriter";

    // Event types
    public static final short EV_SYN = 0x00;
    public static final short EV_KEY = 0x01;
    public static final short EV_ABS = 0x03;
    public static final short EV_MSC = 0x04;

    // Event codes
    public static final short MSC_SCAN = 0x04;
    public static final short SYN_REPORT = 0x00;

    // Xbox 360 controller button codes
    public static final short BTN_A = 0x130;
    public static final short BTN_B = 0x131;
    public static final short BTN_X = 0x133;
    public static final short BTN_Y = 0x134;
    public static final short BTN_TL = 0x136;
    public static final short BTN_TR = 0x137;
    public static final short BTN_SELECT = 0x13A;
    public static final short BTN_START = 0x13B;
    public static final short BTN_THUMBL = 0x13D;
    public static final short BTN_THUMBR = 0x13E;

    // Absolute axis codes
    public static final short ABS_X = 0x00;
    public static final short ABS_Y = 0x01;
    public static final short ABS_RX = 0x03;
    public static final short ABS_RY = 0x04;
    public static final short ABS_GAS = 0x09;
    public static final short ABS_BRAKE = 0x0A;
    public static final short ABS_HAT0X = 0x10;
    public static final short ABS_HAT0Y = 0x11;

    // Button mapping (bit i of the snapshot buttons word)
    private static final short[] BUTTON_MAP = {
            BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR,
            BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR
    };

    private static final int EVENT_SIZE = 24;
    static final int MAX_EVENTS_PER_UPDATE = 32;
    private static final int BUFFER_SIZE = EVENT_SIZE * MAX_EVENTS_PER_UPDATE;
    private static final int MAX_FAKE_INPUT_SLOTS = 4;

    // Ring layout — MUST match FakeInputRingHeader in fakeinput.cpp.
    private static final int RING_CAPACITY_EVENTS = 4096;
    private static final int RING_HEADER_SIZE = 64;
    private static final int RING_SIZE = RING_HEADER_SIZE + (RING_CAPACITY_EVENTS * EVENT_SIZE);
    private static final int RING_MAGIC = 0x46494252; // FIBR
    private static final int RING_VERSION = 2;
    private static final int RING_MAGIC_OFFSET = 0;
    private static final int RING_VERSION_OFFSET = 4;
    private static final int RING_EVENT_SIZE_OFFSET = 8;
    private static final int RING_CAPACITY_OFFSET = 12;
    private static final int RING_WRITE_SEQ_OFFSET = 16;
    private static final int RING_GENERATION_OFFSET = 24;
    // Authoritative absolute-state snapshot the native reader replays as a full
    // keyframe to heal any delta-stream desync. Written under a seqlock
    // (RING_SNAPSHOT_SEQ_OFFSET: odd = write in progress).
    private static final int RING_SNAPSHOT_SEQ_OFFSET = 32;
    private static final int RING_SNAPSHOT_BUTTONS_OFFSET = 40;
    private static final int RING_SNAPSHOT_AXES_OFFSET = 44; // short[8]
    private static final String RING_DIR_NAME = "fakeinput-rings";

    private static final Object RING_LOCK = new Object();
    private static final RingSlot[] RING_SLOTS = new RingSlot[MAX_FAKE_INPUT_SLOTS];

    static {
        System.loadLibrary("winlator");
    }

    private static native void nativeStoreFence();

    private final File eventFile;
    private final int slot;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

    private boolean isOpen = false;
    private volatile boolean destroyed = false;

    private final boolean[] prevButtonStates = new boolean[12];
    private int prevThumbLX, prevThumbLY, prevThumbRX, prevThumbRY;
    private int prevTriggerL, prevTriggerR;
    private int prevHatX, prevHatY;
    private boolean hasChanges = false;
    // If a publish fails after prev* state has already advanced, the next frame
    // must re-emit the whole state once; otherwise an unchanged control would stay
    // silent and the ring snapshot would never catch up.
    private boolean pendingFullResend = false;
    private boolean forceResend = false;

    public FakeInputWriter(String fakeInputPath, int slot) {
        this.slot = slot;
        this.eventFile = new File(fakeInputPath, "event" + slot);
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    private static final class RingSlot {
        ByteBuffer data;
        File ringFile;
        FileChannel ringChannel;
        RandomAccessFile ringRaf;
        String exportPath;
        long generation;
        boolean active;
        boolean everActivated;
    }

    // ---- Static ring management (shared by all writers + the env exporter) ----

    public static void prepareRingSlots(File fakeInputDir, int slotCount) {
        int boundedSlotCount = Math.max(0, Math.min(slotCount, MAX_FAKE_INPUT_SLOTS));
        synchronized (RING_LOCK) {
            for (int slot = 0; slot < boundedSlotCount; slot++) {
                ensureRingSlotLocked(slot, fakeInputDir);
            }
        }
    }

    /**
     * Prepares every slot ring and returns the {@code slot=path;...} spec the native
     * reader consumes via FAKE_EVDEV_MEMFD_PATHS.
     */
    public static String getRingEnv(File fakeInputDir) {
        synchronized (RING_LOCK) {
            prepareRingSlotsLocked(fakeInputDir, MAX_FAKE_INPUT_SLOTS);
            return buildRingEnvLocked();
        }
    }

    public static void releaseAllRingSlots() {
        synchronized (RING_LOCK) {
            for (int slot = 0; slot < RING_SLOTS.length; slot++) {
                releaseRingSlotLocked(slot);
            }
        }
    }

    private static String buildRingEnvLocked() {
        StringBuilder builder = new StringBuilder();
        for (int slot = 0; slot < RING_SLOTS.length; slot++) {
            RingSlot ringSlot = RING_SLOTS[slot];
            if (ringSlot == null || ringSlot.data == null || ringSlot.exportPath == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(slot).append('=').append(ringSlot.exportPath);
        }
        return builder.toString();
    }

    private static File getRingDir(File fakeInputDir) {
        if (fakeInputDir == null) {
            return null;
        }
        File inputDir = fakeInputDir.getAbsoluteFile();
        File parent = inputDir.getParentFile();
        return new File(parent != null ? parent : inputDir, RING_DIR_NAME);
    }

    private static File getRingFile(File fakeInputDir, int slot) {
        File ringDir = getRingDir(fakeInputDir);
        return ringDir != null ? new File(ringDir, "ring" + slot) : null;
    }

    private static String getCanonicalOrAbsolutePath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static void initializeRingHeader(ByteBuffer data) {
        data.order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(RING_MAGIC_OFFSET, RING_MAGIC);
        data.putInt(RING_VERSION_OFFSET, RING_VERSION);
        data.putInt(RING_EVENT_SIZE_OFFSET, EVENT_SIZE);
        data.putInt(RING_CAPACITY_OFFSET, RING_CAPACITY_EVENTS);
        data.putLong(RING_WRITE_SEQ_OFFSET, 0L);
        data.putLong(RING_GENERATION_OFFSET, 0L);
        data.putLong(RING_SNAPSHOT_SEQ_OFFSET, 0L);
        data.putInt(RING_SNAPSHOT_BUTTONS_OFFSET, 0);
        for (int i = 0; i < 8; i++) {
            data.putShort(RING_SNAPSHOT_AXES_OFFSET + (i * 2), (short) 0);
        }
    }

    private static void releaseRingSlotLocked(int slot) {
        RingSlot ringSlot = RING_SLOTS[slot];
        if (ringSlot == null) {
            return;
        }
        ringSlot.data = null;
        if (ringSlot.ringChannel != null) {
            try {
                ringSlot.ringChannel.close();
            } catch (IOException ignored) {
            }
            ringSlot.ringChannel = null;
        }
        if (ringSlot.ringRaf != null) {
            try {
                ringSlot.ringRaf.close();
            } catch (IOException ignored) {
            }
            ringSlot.ringRaf = null;
        }
        if (ringSlot.ringFile != null && ringSlot.ringFile.exists()) {
            ringSlot.ringFile.delete();
        }
        RING_SLOTS[slot] = null;
    }

    private static void prepareRingSlotsLocked(File fakeInputDir, int slotCount) {
        int boundedSlotCount = Math.max(0, Math.min(slotCount, MAX_FAKE_INPUT_SLOTS));
        for (int slot = 0; slot < boundedSlotCount; slot++) {
            ensureRingSlotLocked(slot, fakeInputDir);
        }
    }

    private static RingSlot ensureRingSlotLocked(int slot, File fakeInputDir) {
        if (slot < 0 || slot >= MAX_FAKE_INPUT_SLOTS || fakeInputDir == null) {
            return null;
        }

        File desiredRingFile = getRingFile(fakeInputDir, slot);
        if (desiredRingFile == null) {
            return null;
        }
        desiredRingFile = desiredRingFile.getAbsoluteFile();
        RingSlot existing = RING_SLOTS[slot];
        if (existing != null && existing.data != null) {
            if (existing.ringFile != null && existing.ringFile.equals(desiredRingFile)) {
                return existing;
            }
            releaseRingSlotLocked(slot);
        }

        RingSlot fileRingSlot = createFileRingSlotLocked(slot, desiredRingFile);
        if (fileRingSlot != null) {
            RING_SLOTS[slot] = fileRingSlot;
            return fileRingSlot;
        }
        return null;
    }

    private static RingSlot createFileRingSlotLocked(int slot, File ringFile) {
        File ringDir = ringFile.getParentFile();
        if (ringDir == null || (!ringDir.exists() && !ringDir.mkdirs())) {
            Log.e(TAG, "Failed to create fake input ring directory for slot " + slot);
            return null;
        }

        RandomAccessFile raf = null;
        FileChannel channel = null;
        try {
            raf = new RandomAccessFile(ringFile, "rw");
            raf.setLength(RING_SIZE);
            channel = raf.getChannel();
            ByteBuffer data = channel.map(FileChannel.MapMode.READ_WRITE, 0, RING_SIZE);
            initializeRingHeader(data);

            RingSlot ringSlot = new RingSlot();
            ringSlot.data = data;
            ringSlot.ringFile = ringFile.getAbsoluteFile();
            ringSlot.ringRaf = raf;
            ringSlot.ringChannel = channel;
            ringSlot.exportPath = getCanonicalOrAbsolutePath(ringFile);
            Log.i(TAG, "Created fake input file ring for slot " + slot + ": " + ringSlot.exportPath);
            return ringSlot;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create fake input file ring for slot " + slot + ": " + e.getMessage());
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
            return null;
        }
    }

    private RingSlot ensureRingSlot() {
        synchronized (RING_LOCK) {
            return ensureRingSlotLocked(this.slot, this.eventFile.getParentFile());
        }
    }

    private boolean activateRingSlot() {
        RingSlot ringSlot = ensureRingSlot();
        if (ringSlot == null || ringSlot.data == null) {
            return false;
        }
        synchronized (ringSlot) {
            if (!ringSlot.active) {
                if (ringSlot.everActivated) {
                    ringSlot.generation++;
                } else {
                    ringSlot.everActivated = true;
                }
                ringSlot.data.putLong(RING_WRITE_SEQ_OFFSET, 0L);
                clearSnapshotLocked(ringSlot.data);
                ringSlot.data.putLong(RING_GENERATION_OFFSET, ringSlot.generation);
                ringSlot.active = true;
                Log.d(TAG, "Activated fake input ring for slot " + this.slot
                        + " generation=" + ringSlot.generation);
            }
        }
        return true;
    }

    private void deactivateRingSlot() {
        RingSlot ringSlot;
        synchronized (RING_LOCK) {
            ringSlot = this.slot >= 0 && this.slot < RING_SLOTS.length ? RING_SLOTS[this.slot] : null;
        }
        if (ringSlot == null) {
            return;
        }
        synchronized (ringSlot) {
            if (ringSlot.active && ringSlot.data != null) {
                ringSlot.generation++;
                ringSlot.data.putLong(RING_WRITE_SEQ_OFFSET, 0L);
                clearSnapshotLocked(ringSlot.data);
                ringSlot.data.putLong(RING_GENERATION_OFFSET, ringSlot.generation);
                Log.i(TAG, "Deactivated fake input ring for slot " + this.slot
                        + " generation=" + ringSlot.generation);
            }
            ringSlot.active = false;
        }
    }

    private boolean flushBufferToRing() {
        RingSlot ringSlot = ensureRingSlot();
        if (ringSlot == null || ringSlot.data == null) {
            return false;
        }

        // Raw byte copy: endianness is irrelevant since we never interpret
        // multi-byte fields out of the source here.
        ByteBuffer source = this.buffer.duplicate();
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            long writeSeq = ring.getLong(RING_WRITE_SEQ_OFFSET);
            int sourceLimit = source.limit();
            while (source.remaining() >= EVENT_SIZE) {
                int eventIndex = (int) (writeSeq % RING_CAPACITY_EVENTS);
                int targetOffset = RING_HEADER_SIZE + (eventIndex * EVENT_SIZE);
                // Bulk-copy one event (EVENT_SIZE bytes) instead of byte-by-byte.
                // Bound the source to a single event, position the ring at the slot,
                // then let put(ByteBuffer) move the whole block. Mutating the ring's
                // Java position is harmless: the native reader uses a raw pointer, not
                // this position. API-26 safe (no JDK 13+ absolute bulk put).
                source.limit(source.position() + EVENT_SIZE);
                ring.position(targetOffset);
                ring.put(source);
                source.limit(sourceLimit);
                writeSeq++;
            }
            // Publish the resulting absolute state. prev* now hold the post-update
            // values, i.e. exactly the state the events just written transition to.
            writeSnapshotLocked(ring);
            nativeStoreFence();
            ring.putLong(RING_WRITE_SEQ_OFFSET, writeSeq);
        }
        return true;
    }

    // Publishes the full absolute controller state for the native reader to replay
    // as a keyframe. seqlock: bump to odd, write fields, bump to even, with
    // store-store fences so the reader's acquire loads see consistent payloads.
    private void writeSnapshotLocked(ByteBuffer ring) {
        int buttons = 0;
        for (int i = 0; i < BUTTON_MAP.length; i++) {
            if (this.prevButtonStates[i]) {
                buttons |= (1 << i);
            }
        }
        long seq = ring.getLong(RING_SNAPSHOT_SEQ_OFFSET);
        ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 1); // odd: write in progress
        nativeStoreFence();
        ring.putInt(RING_SNAPSHOT_BUTTONS_OFFSET, buttons);
        // Axis order must match the native kSnapshotAxisCodes:
        // X, Y, RX, RY, GAS(=triggerR), BRAKE(=triggerL), HAT0X, HAT0Y.
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET, clampShort(this.prevThumbLX));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 2, clampShort(this.prevThumbLY));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 4, clampShort(this.prevThumbRX));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 6, clampShort(this.prevThumbRY));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 8, clampShort(this.prevTriggerR));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 10, clampShort(this.prevTriggerL));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 12, clampShort(this.prevHatX));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 14, clampShort(this.prevHatY));
        nativeStoreFence();
        ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 2); // even: write complete
    }

    private static short clampShort(int value) {
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) value;
    }

    private static void clearSnapshotLocked(ByteBuffer ring) {
        long seq = ring.getLong(RING_SNAPSHOT_SEQ_OFFSET);
        ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 1);
        nativeStoreFence();
        ring.putInt(RING_SNAPSHOT_BUTTONS_OFFSET, 0);
        for (int i = 0; i < 8; i++) {
            ring.putShort(RING_SNAPSHOT_AXES_OFFSET + (i * 2), (short) 0);
        }
        nativeStoreFence();
        ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 2);
    }

    private boolean flushBuffer() {
        return flushBufferToRing();
    }

    public synchronized boolean open() {
        if (destroyed)
            return false;
        if (isOpen)
            return true;

        try {
            eventFile.getParentFile().mkdirs();
            if (!eventFile.exists()) {
                eventFile.createNewFile();
            }
            if (!activateRingSlot()) {
                if (eventFile.exists()) {
                    eventFile.delete();
                }
                Log.e(TAG, "Failed to open fake input mmap ring: " + eventFile.getAbsolutePath());
                return false;
            }
            isOpen = true;
            Log.i(TAG, "Opened fake input: " + eventFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to open: " + e.getMessage());
            return false;
        }
    }

    public synchronized void close() {
        isOpen = false;
    }

    /**
     * Reset all input state to neutral (all buttons released, axes zeroed).
     * This keeps the ring active so games that cache the file descriptor can still
     * reconnect.
     */
    public synchronized void reset() {
        if (!isOpen && !open())
            return;

        buffer.clear();
        hasChanges = false;

        // Release all buttons
        for (int i = 0; i < BUTTON_MAP.length; i++) {
            if (prevButtonStates[i]) {
                prevButtonStates[i] = false;
                writeEvent(EV_MSC, MSC_SCAN, BUTTON_MAP[i]);
                writeEvent(EV_KEY, BUTTON_MAP[i], 0);
            }
        }

        // Zero all axes
        if (prevThumbLX != 0) {
            prevThumbLX = 0;
            writeEvent(EV_ABS, ABS_X, 0);
        }
        if (prevThumbLY != 0) {
            prevThumbLY = 0;
            writeEvent(EV_ABS, ABS_Y, 0);
        }
        if (prevThumbRX != 0) {
            prevThumbRX = 0;
            writeEvent(EV_ABS, ABS_RX, 0);
        }
        if (prevThumbRY != 0) {
            prevThumbRY = 0;
            writeEvent(EV_ABS, ABS_RY, 0);
        }
        if (prevTriggerL != 0) {
            prevTriggerL = 0;
            writeEvent(EV_ABS, ABS_BRAKE, 0);
        }
        if (prevTriggerR != 0) {
            prevTriggerR = 0;
            writeEvent(EV_ABS, ABS_GAS, 0);
        }
        if (prevHatX != 0) {
            prevHatX = 0;
            writeEvent(EV_ABS, ABS_HAT0X, 0);
        }
        if (prevHatY != 0) {
            prevHatY = 0;
            writeEvent(EV_ABS, ABS_HAT0Y, 0);
        }

        if (hasChanges) {
            writeEvent(EV_SYN, SYN_REPORT, 0);
            buffer.flip();
            if (!flushBuffer())
                Log.e(TAG, "Reset write error: fake input mmap ring unavailable");
        }
        Log.i(TAG, "Reset fake input to neutral state: " + eventFile.getAbsolutePath());
    }

    public synchronized void softRelease() {
        reset();
        close();
        Log.i(TAG, "Soft released fake input: " + eventFile.getAbsolutePath());
    }

    /**
     * Full destroy - reset, tear the ring generation down, and delete the discovery node.
     */
    public synchronized void destroy() {
        destroyed = true;
        reset();
        close();
        deactivateRingSlot();
        if (eventFile != null && eventFile.exists()) {
            boolean deleted = eventFile.delete();
            Log.i(TAG, "Deleted fake input discovery node: " + eventFile.getAbsolutePath() + " (" + deleted + ")");
        }
    }

    private void writeEvent(short type, short code, int value) {
        long timeMs = System.currentTimeMillis();
        buffer.putLong(timeMs / 1000);
        buffer.putLong((timeMs % 1000) * 1000);
        buffer.putShort(type);
        buffer.putShort(code);
        buffer.putInt(value);
        hasChanges = true;
    }

    private void writeButton(int idx, boolean pressed) {
        if (idx < 0 || idx >= BUTTON_MAP.length)
            return;
        if (!forceResend && prevButtonStates[idx] == pressed)
            return;
        prevButtonStates[idx] = pressed;
        writeEvent(EV_MSC, MSC_SCAN, BUTTON_MAP[idx]);
        writeEvent(EV_KEY, BUTTON_MAP[idx], pressed ? 1 : 0);
    }

    public synchronized void writeGamepadState(GamepadState state) {
        if (!isOpen && !open())
            return;

        // Keep the ring delta-first for latency. Full event frames are only used as
        // a one-shot repair after a failed publish; normal open/overflow recovery is
        // handled by the native snapshot keyframe.
        forceResend = pendingFullResend;
        pendingFullResend = false;
        if (forceResend) {
            Log.d(TAG, "Re-emitting full gamepad state after failed publish for slot " + slot);
        }

        buffer.clear();
        hasChanges = false;

        // Buttons
        for (int i = 0; i < 10; i++) {
            writeButton(i, state.isPressed((byte) i));
        }

        // Sticks
        int lx = (int) (state.thumbLX * 32767);
        int ly = (int) (state.thumbLY * 32767);
        int rx = (int) (state.thumbRX * 32767);
        int ry = (int) (state.thumbRY * 32767);

        // The fake evdev ring is event-queue semantics, so unchanged axes normally
        // stay silent; forceResend overrides that to emit a complete keyframe.
        if (forceResend || lx != prevThumbLX) {
            prevThumbLX = lx;
            writeEvent(EV_ABS, ABS_X, lx);
        }
        if (forceResend || ly != prevThumbLY) {
            prevThumbLY = ly;
            writeEvent(EV_ABS, ABS_Y, ly);
        }
        if (forceResend || rx != prevThumbRX) {
            prevThumbRX = rx;
            writeEvent(EV_ABS, ABS_RX, rx);
        }
        if (forceResend || ry != prevThumbRY) {
            prevThumbRY = ry;
            writeEvent(EV_ABS, ABS_RY, ry);
        }

        // L2 and R2 (Triggers)
        int tl = (int) (state.triggerL * 255);
        int tr = (int) (state.triggerR * 255);
        if (forceResend || tl != prevTriggerL) {
            prevTriggerL = tl;
            writeEvent(EV_ABS, ABS_BRAKE, tl);
        }
        if (forceResend || tr != prevTriggerR) {
            prevTriggerR = tr;
            writeEvent(EV_ABS, ABS_GAS, tr);
        }

        // D-pad
        int hatX = state.dpad[3] ? -1 : (state.dpad[1] ? 1 : 0);
        int hatY = state.dpad[0] ? -1 : (state.dpad[2] ? 1 : 0);
        if (forceResend || hatX != prevHatX) {
            prevHatX = hatX;
            writeEvent(EV_ABS, ABS_HAT0X, hatX);
        }
        if (forceResend || hatY != prevHatY) {
            prevHatY = hatY;
            writeEvent(EV_ABS, ABS_HAT0Y, hatY);
        }

        // Detect change else no need to write
        if (hasChanges) {
            writeEvent(EV_SYN, SYN_REPORT, 0);
            buffer.flip();
            if (!flushBuffer()) {
                Log.e(TAG, "Gamepad write error: fake input mmap ring unavailable");
                // Couldn't publish; re-assert the whole state on the next frame.
                pendingFullResend = true;
            }
        }
        forceResend = false;
    }

    public boolean isOpen() {
        return isOpen;
    }
}
