package com.winlator.star.inputcontrols;

public class GamepadState {
    public float thumbLX = 0;
    public float thumbLY = 0;
    public float thumbRX = 0;
    public float thumbRY = 0;
    public float triggerL = 0;
    public float triggerR = 0;
    public final boolean[] dpad = new boolean[4];
    public short buttons = 0;

    public void setPressed(int buttonIdx, boolean pressed) {
        int flag = 1<<buttonIdx;
        if (pressed) {
            buttons |= flag;
        }
        else buttons &= ~flag;
    }

    public boolean isPressed(int buttonIdx) {
        return (buttons & (1<<buttonIdx)) != 0;
    }

    public byte getDPadX() {
        return (byte)(dpad[1] ? 1 : (dpad[3] ? -1 : 0));
    }

    public byte getDPadY() {
        return (byte)(dpad[0] ? -1 : (dpad[2] ? 1 : 0));
    }

    public void copy(GamepadState other) {
        thumbLX = other.thumbLX;
        thumbLY = other.thumbLY;
        thumbRX = other.thumbRX;
        thumbRY = other.thumbRY;
        triggerL = other.triggerL;
        triggerR = other.triggerR;
        buttons = other.buttons;
        System.arraycopy(other.dpad, 0, dpad, 0, dpad.length);
    }

    public void reset() {
        thumbLX = 0;
        thumbLY = 0;
        thumbRX = 0;
        thumbRY = 0;
        triggerL = 0;
        triggerR = 0;
        buttons = 0;
        java.util.Arrays.fill(dpad, false);
    }

}
