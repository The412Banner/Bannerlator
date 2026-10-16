package com.winlator.star.core;

import android.util.Log;

import com.winlator.star.inputcontrols.BannerinWriter;

/**
 * The app's writes into BANNERIN's control block (format minor 5): each slot's identity, read by the
 * evdev layer when a reader attaches (so a reconnect presents a new identity), and the session control,
 * read live by the readers (stick pacing) and by the XInput DLLs (Direct / forward / hidden).
 * One place, used at launch (before the guest starts) and from the in-game menu.
 */
public final class BannerinSessionControl {
    private static final String TAG = "BannerinControl";

    private BannerinSessionControl() {}

    /** Every slot's identity (generic included: an explicit generic beats the env fallback). */
    public static void writeSlotIdentities(String identity) {
        for (int slot = 0; slot < BannerinWriter.MAX_SLOTS; slot++) {
            BannerinWriter.setSlotIdentity(slot,
                    ControllerIdentity.SLOT_BUSTYPE,
                    ControllerIdentity.slotVendor(identity, slot),
                    ControllerIdentity.slotProduct(identity, slot),
                    ControllerIdentity.SLOT_VERSION,
                    identity,
                    ControllerIdentity.slotName(identity, slot));
        }
        Log.i(TAG, "slot identity: " + ControllerIdentity.describe(identity));
    }

    /**
     * Pacing and XInput mode for the session. {@code pacingFromEnv}: BANNERIN_AXIS_* are set in the env
     * vars, so the readers keep using those (mode 0 = unset/env).
     */
    public static void writeSessionControl(StickPacing pacing, int xinputMode, int xinputFlags, boolean pacingFromEnv) {
        int axisMode = BannerinWriter.AXIS_MODE_UNSET, axisHz = 0, autoMax = 0;
        if (!pacingFromEnv) {
            switch (pacing.mode) {
                case StickPacing.MODE_FIXED: axisMode = BannerinWriter.AXIS_MODE_FIXED; axisHz = pacing.hz; break;
                case StickPacing.MODE_OFF: axisMode = BannerinWriter.AXIS_MODE_UNPACED; break;
                default: axisMode = BannerinWriter.AXIS_MODE_AUTO; autoMax = pacing.hz; break;
            }
        }
        BannerinWriter.setSessionControl(axisMode, axisHz, autoMax, xinputMode, xinputFlags);
        Log.i(TAG, "session control: pacing " + (pacingFromEnv ? "from env vars" : pacing.format())
                + ", xinput mode " + xinputMode + " flags " + xinputFlags);
    }

    /** The XInput mode for a live Direct/Wine choice, when the session can switch. */
    public static int xinputModeFor(boolean direct) {
        return BannerinXInput.liveMode(direct);
    }
}
