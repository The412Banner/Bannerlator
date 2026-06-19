package com.winlator.star.xserver.events;

import com.winlator.star.xserver.Bitmask;
import com.winlator.star.xserver.Window;

public class KeyPress extends InputDeviceEvent {
    public KeyPress(byte keycode, Window root, Window event, Window child, short rootX, short rootY, short eventX, short eventY, Bitmask state) {
        super(2, keycode, root, event, child, rootX, rootY, eventX, eventY, state);
    }
}
