package com.winlator.star.core

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** What each orientation mode asks Android for: the App-orientation modes as before, the XMB home's landscape on both sides. */
class AppOrientationTest {
    @Test fun appLandscapeStaysFixedLandscape() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, AppOrientation.requested(AppOrientation.LANDSCAPE))
    }

    @Test fun xmbLandscapeFollowsBothLandscapeRotations() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, AppOrientation.requestedXmb(AppOrientation.LANDSCAPE))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppOrientation.requestedXmb(AppOrientation.PORTRAIT))
    }

    @Test fun portraitStaysPortrait() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppOrientation.requested(AppOrientation.PORTRAIT))
    }

    @Test fun autoAndUnknownFollowTheSensor() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, AppOrientation.requested(AppOrientation.AUTO))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, AppOrientation.requested("something else"))
    }
}
