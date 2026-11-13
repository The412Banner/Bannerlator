package com.winlator.star.core

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** What each App-orientation mode asks Android for: landscape follows both landscape rotations only in the XMB home, nothing else changes. */
class AppOrientationTest {
    @Test fun landscapeIsThePlainLockOutsideTheXmbHome() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, AppOrientation.requested(AppOrientation.LANDSCAPE))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, AppOrientation.requested(AppOrientation.LANDSCAPE, xmbHome = false))
    }

    @Test fun landscapeFollowsBothLandscapeRotationsInTheXmbHome() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, AppOrientation.requested(AppOrientation.LANDSCAPE, xmbHome = true))
    }

    @Test fun portraitStaysPortrait() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppOrientation.requested(AppOrientation.PORTRAIT))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppOrientation.requested(AppOrientation.PORTRAIT, xmbHome = true))
    }

    @Test fun autoAndUnknownFollowTheSensor() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, AppOrientation.requested(AppOrientation.AUTO))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, AppOrientation.requested("something else"))
    }
}
