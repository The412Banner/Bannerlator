package com.winlator.star.ui.dialogs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.star.ui.XServerDialogState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputControlsDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        XServerDialogState.reset()
    }

    @Test
    fun profileSettingsAppliesPendingValuesBeforeOpeningEditor() {
        val events = mutableListOf<String>()
        XServerDialogState.setInputProfiles(listOf("Test profile"))
        XServerDialogState.setSelectedProfileIdx(1)
        XServerDialogState.setShowTouchscreen(true)
        XServerDialogState.setTimeoutEnabled(false)
        XServerDialogState.setHapticsEnabled(true)
        XServerDialogState.onInputControlsConfirm =
            XServerDialogState.InputConfirmCallback { profile, touchscreen, timeout, haptics ->
                events += "confirm:$profile:$touchscreen:$timeout:$haptics"
            }
        XServerDialogState.onInputControlsSettings =
            XServerDialogState.InputSettingsCallback { profile -> events += "settings:$profile" }

        composeRule.setContent { InputControlsDialog(XServerDialogState) }
        composeRule.onNodeWithText("Profile Settings\u2026").performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf("confirm:1:true:false:true", "settings:1"),
                events,
            )
        }
    }

    @Test
    fun profileSettingsIsDisabledWithoutASelectedProfile() {
        XServerDialogState.setInputProfiles(emptyList())
        XServerDialogState.setSelectedProfileIdx(0)

        composeRule.setContent { InputControlsDialog(XServerDialogState) }

        composeRule.onNodeWithText("Profile Settings\u2026").assertIsNotEnabled()
    }
}
