package com.winlator.star.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class FgEngineSwitchTest {
    @Test public void nativeRendererOffersAllFourEngines() {
        assertEquals(Arrays.asList("off", "lsfg-native", "bionic", "wfg"),
            FgEngineSwitch.choices(true, false, true));
    }

    @Test public void noNativeRendererOffersNothing() {
        assertTrue(FgEngineSwitch.choices(false, false, true).isEmpty());
    }

    @Test public void guestLayerSessionCannotSwitch() {
        assertTrue(FgEngineSwitch.choices(true, true, false).isEmpty());
    }

    @Test public void winFgLeftOutWhenItWouldNeedTheGuestLayer() {
        assertEquals(Arrays.asList("off", "lsfg-native", "wfg"),
            FgEngineSwitch.choices(true, false, false));
    }

    @Test public void multiplierCarriesOverToTheNewEngine() {
        assertEquals(3, FgEngineSwitch.carryMultiplier("lsfg-native", 3));
        assertEquals(4, FgEngineSwitch.carryMultiplier("wfg", 4));
        assertEquals(2, FgEngineSwitch.carryMultiplier("bionic", 4));
        assertEquals(0, FgEngineSwitch.carryMultiplier("bionic", 0));
        assertEquals(0, FgEngineSwitch.carryMultiplier("off", 3));
        assertEquals(0, FgEngineSwitch.carryMultiplier("wfg", 1));
    }
}
