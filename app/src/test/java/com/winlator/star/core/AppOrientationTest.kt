package com.winlator.star.core

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** What each App-orientation mode asks Android for: landscape follows both landscape rotations, nothing else changes. */
class AppOrientationTest {
    @Test fun landscapeFollowsBothLandscapeRotations() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, AppOrientation.requested(AppOrientation.LANDSCAPE))
    }

    @Test fun portraitStaysPortrait() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppOrientation.requested(AppOrientation.PORTRAIT))
    }

    @Test fun autoAndUnknownFollowTheSensor() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, AppOrientation.requested(AppOrientation.AUTO))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, AppOrientation.requested("something else"))
    }
}
