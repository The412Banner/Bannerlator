package com.winlator.star.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** When Faster BCn on Mali switches the launch from the default wrapper to the GameNative wrapper. */
public class BcnFastLayerTest {

    private static Map<String, String> cfg(String layer, String mode, String type) {
        Map<String, String> m = new HashMap<>();
        if (layer != null) m.put(BcnFastLayer.KEY, layer);
        if (mode != null) m.put("bcnEmulation", mode);
        if (type != null) m.put("bcnEmulationType", type);
        return m;
    }

    @Test
    public void fastLayerOnDefaultWrapper_switches() {
        assertTrue(BcnFastLayer.needsGamenativeWrapper(true, "wrapper", cfg("fast", "auto", "compute"), false));
        assertTrue(BcnFastLayer.needsGamenativeWrapper(true, "wrapper", cfg("fast", "full", "compute"), false));
    }

    @Test
    public void notMaliOrNotFastOrLayerNotRunning_stays() {
        assertFalse(BcnFastLayer.needsGamenativeWrapper(false, "wrapper", cfg("fast", "auto", "compute"), false));
        assertFalse(BcnFastLayer.needsGamenativeWrapper(true, "wrapper", cfg(null, "auto", "compute"), false));
        assertFalse(BcnFastLayer.needsGamenativeWrapper(true, "wrapper", cfg("fast", "auto", "software"), false));
        assertFalse(BcnFastLayer.needsGamenativeWrapper(true, "wrapper", cfg("fast", "none", "compute"), false));
        assertFalse(BcnFastLayer.needsGamenativeWrapper(true, "wrapper", cfg("fast", null, "compute"), false));
    }

    @Test
    public void otherWrapperOrUsersOwnDefaultSlot_stays() {
        assertFalse(BcnFastLayer.needsGamenativeWrapper(true, "wrapper-gamenative", cfg("fast", "auto", "compute"), false));
        assertFalse(BcnFastLayer.needsGamenativeWrapper(true, "wrapper-leegao", cfg("fast", "auto", "compute"), false));
        assertFalse(BcnFastLayer.needsGamenativeWrapper(true, "wrapper", cfg("fast", "auto", "compute"), true));
        assertFalse(BcnFastLayer.needsGamenativeWrapper(true, null, cfg("fast", "auto", "compute"), false));
        assertFalse(BcnFastLayer.needsGamenativeWrapper(true, "wrapper", null, false));
    }
}
