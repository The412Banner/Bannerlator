package com.winlator.star.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.preference.PreferenceManager
import com.winlator.star.R
import com.winlator.star.inputcontrols.Binding
import com.winlator.star.inputcontrols.ControlElement
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.xserver.ScreenInfo
import com.winlator.star.xserver.XServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputControlsViewRoutingTest {
    private var originalTouchscreenToggle: Boolean? = null
    private var hadTouchscreenToggle = false

    @After
    fun restorePreferences() {
        val original = originalTouchscreenToggle ?: return
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        if (hadTouchscreenToggle) editor.putBoolean("touchscreen_toggle", original)
        else editor.remove("touchscreen_toggle")
        editor.commit()
    }

    @Test
    fun outsideControlClosesExpandableAndReceivesSameTap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fixture = createFixture(includeTargetButton = true)
            tap(fixture.view, 0, fixture.expandable)
            assertTrue(fixture.expandable.isExpanded)

            send(fixture.view, singlePointerEvent(MotionEvent.ACTION_DOWN, 1, fixture.target!!))

            assertFalse(fixture.expandable.isExpanded)
            assertTrue(fixture.profile.gamepadState.isPressed(0))

            send(fixture.view, singlePointerEvent(MotionEvent.ACTION_UP, 1, fixture.target))
            assertFalse(fixture.profile.gamepadState.isPressed(0))
        }
    }

    @Test
    fun swallowedExpandablePointerDoesNotHideTouchpadPointerRelease() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fixture = createFixture(includeTargetButton = false)
            val touchpadX = 900f
            val touchpadY = 900f
            val expandableBounds = fixture.expandable.boundingBox
            val expandableX = expandableBounds.exactCenterX()
            val expandableY = expandableBounds.exactCenterY()
            val downTime = 1L

            send(fixture.view, motionEvent(
                downTime,
                1L,
                MotionEvent.ACTION_DOWN,
                Pointer(0, touchpadX, touchpadY),
            ))
            assertTrue(activeTouchpadFingerCount(fixture.touchpad) == 1)

            send(fixture.view, motionEvent(
                downTime,
                2L,
                MotionEvent.ACTION_POINTER_DOWN or
                    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(0, touchpadX, touchpadY),
                Pointer(1, expandableX, expandableY),
            ))
            assertTrue(fixture.expandable.isExpanded)

            send(fixture.view, motionEvent(
                downTime,
                3L,
                MotionEvent.ACTION_POINTER_UP,
                Pointer(0, touchpadX, touchpadY),
                Pointer(1, expandableX, expandableY),
            ))
            assertTrue(activeTouchpadFingerCount(fixture.touchpad) == 0)

            send(fixture.view, motionEvent(
                downTime,
                4L,
                MotionEvent.ACTION_UP,
                Pointer(1, expandableX, expandableY),
            ))
        }
    }

    @Test
    fun emptyOutsideTapOnlyClosesExpandable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fixture = createFixture(includeTargetButton = false)
            tap(fixture.view, 0, fixture.expandable)
            assertTrue(fixture.expandable.isExpanded)

            send(fixture.view, motionEvent(
                2L,
                2L,
                MotionEvent.ACTION_DOWN,
                Pointer(2, 900f, 900f),
            ))

            assertFalse(fixture.expandable.isExpanded)
            assertTrue(activeTouchpadFingerCount(fixture.touchpad) == 0)

            send(fixture.view, motionEvent(
                2L,
                3L,
                MotionEvent.ACTION_UP,
                Pointer(2, 900f, 900f),
            ))
        }
    }

    @Test
    fun releasingControlsKeepsTouchpadPointerOwnedUntilItsUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fixture = createFixture(includeTargetButton = false)
            send(fixture.view, motionEvent(
                1L,
                1L,
                MotionEvent.ACTION_DOWN,
                Pointer(0, 900f, 900f),
            ))
            assertTrue(activeTouchpadFingerCount(fixture.touchpad) == 1)

            fixture.view.releaseActiveControls()

            assertTrue(activeTouchpadFingerCount(fixture.touchpad) == 1)
            send(fixture.view, motionEvent(
                1L,
                2L,
                MotionEvent.ACTION_UP,
                Pointer(0, 900f, 900f),
            ))
            assertTrue(activeTouchpadFingerCount(fixture.touchpad) == 0)
        }
    }

    @Test
    fun hidingControlsMidGestureCleansOwnershipOnUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fixture = createFixture(includeTargetButton = true)
            send(fixture.view, motionEvent(
                1L,
                1L,
                MotionEvent.ACTION_DOWN,
                Pointer(0, 900f, 900f),
            ))
            fixture.view.setShowTouchscreenControls(false)
            send(fixture.view, motionEvent(
                1L,
                2L,
                MotionEvent.ACTION_UP,
                Pointer(0, 900f, 900f),
            ))
            assertTrue(activeTouchpadFingerCount(fixture.touchpad) == 0)

            fixture.view.setShowTouchscreenControls(true)
            send(fixture.view, singlePointerEvent(MotionEvent.ACTION_DOWN, 0, fixture.target!!))
            send(fixture.view, singlePointerEvent(MotionEvent.ACTION_UP, 0, fixture.target))
            assertFalse(fixture.profile.gamepadState.isPressed(0))
        }
    }

    @Test
    fun clearingProfileMidGestureStillRoutesTheFinalUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fixture = createFixture(includeTargetButton = false)
            send(fixture.view, motionEvent(
                1L,
                1L,
                MotionEvent.ACTION_DOWN,
                Pointer(0, 900f, 900f),
            ))
            fixture.view.setProfile(null)
            send(fixture.view, motionEvent(
                1L,
                2L,
                MotionEvent.ACTION_UP,
                Pointer(0, 900f, 900f),
            ))

            assertTrue(activeTouchpadFingerCount(fixture.touchpad) == 0)
        }
    }

    @Test
    fun reusedPointerIdIsReassignedAfterLifecycleRelease() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fixture = createFixture(includeTargetButton = true)
            send(fixture.view, motionEvent(
                1L,
                1L,
                MotionEvent.ACTION_DOWN,
                Pointer(0, 900f, 900f),
            ))
            fixture.view.releaseAllInputs()
            fixture.touchpad.releaseAllInputs()

            send(fixture.view, singlePointerEvent(MotionEvent.ACTION_DOWN, 0, fixture.target!!))
            assertTrue(fixture.profile.gamepadState.isPressed(0))
            send(fixture.view, singlePointerEvent(MotionEvent.ACTION_UP, 0, fixture.target))

            assertFalse(fixture.profile.gamepadState.isPressed(0))
        }
    }

    private fun createFixture(includeTargetButton: Boolean): Fixture {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.AppThemeFullscreen,
        )
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (originalTouchscreenToggle == null) {
            hadTouchscreenToggle = preferences.contains("touchscreen_toggle")
            originalTouchscreenToggle = preferences.getBoolean("touchscreen_toggle", false)
        }
        preferences
            .edit()
            .putBoolean("touchscreen_toggle", false)
            .commit()
        val view = InputControlsView(context)
        view.layout(0, 0, 1000, 1000)
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()

        val xServer = XServer(ScreenInfo(1000, 1000))
        val touchpad = TouchpadView(context, xServer, Handler(Looper.getMainLooper())) {}
        val profile = ControlsProfile(context, 9000).apply { name = "routing-test" }
        val expandable = ControlElement(view).apply {
            type = ControlElement.Type.EXPANDABLE_BUTTON
            setX(200)
            setY(500)
        }
        profile.addElement(expandable)

        val target = if (includeTargetButton) {
            ControlElement(view).apply {
                type = ControlElement.Type.BUTTON
                setX(800)
                setY(500)
                setBindingAt(0, Binding.GAMEPAD_BUTTON_A)
            }.also(profile::addElement)
        } else {
            null
        }

        view.setTouchpadView(touchpad)
        view.setProfile(profile)
        view.setShowTouchscreenControls(true)
        return Fixture(view, touchpad, profile, expandable, target)
    }

    private fun tap(view: InputControlsView, pointerId: Int, element: ControlElement) {
        send(view, singlePointerEvent(MotionEvent.ACTION_DOWN, pointerId, element))
        send(view, singlePointerEvent(MotionEvent.ACTION_UP, pointerId, element))
    }

    private fun singlePointerEvent(action: Int, pointerId: Int, element: ControlElement): MotionEvent {
        val bounds = element.boundingBox
        return motionEvent(
            1L,
            1L,
            action,
            Pointer(pointerId, bounds.exactCenterX(), bounds.exactCenterY()),
        )
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        vararg pointers: Pointer,
    ): MotionEvent {
        val properties = Array(pointers.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = pointers[index].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(pointers.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = pointers[index].x
                y = pointers[index].y
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            pointers.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun send(view: InputControlsView, event: MotionEvent) {
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun activeTouchpadFingerCount(touchpad: TouchpadView): Int {
        val field = TouchpadView::class.java.getDeclaredField("numFingers")
        field.isAccessible = true
        return field.getByte(touchpad).toInt()
    }

    private data class Pointer(val id: Int, val x: Float, val y: Float)

    private data class Fixture(
        val view: InputControlsView,
        val touchpad: TouchpadView,
        val profile: ControlsProfile,
        val expandable: ControlElement,
        val target: ControlElement?,
    )
}
