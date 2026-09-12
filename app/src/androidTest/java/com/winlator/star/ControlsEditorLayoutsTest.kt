package com.winlator.star

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlsEditorLayoutsTest {
    @Test
    fun sidebarWidthUsesAvailableWindowWidth() {
        assertEquals(272, ControlsEditorActivity.calculateSidebarWidth(320, 1f))
        assertEquals(300, ControlsEditorActivity.calculateSidebarWidth(500, 1f))
    }

    @Test
    fun standaloneEditorLayoutInflates() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.AppThemeFullscreen,
        )
        val root = LayoutInflater.from(context).inflate(R.layout.controls_editor_activity, null)

        assertNotNull(root.findViewById<android.view.View>(R.id.ComposeToolbar))
        assertNotNull(root.findViewById<android.view.View>(R.id.SVSidebar))
        assertNotNull(root.findViewById<android.view.View>(R.id.ComposeDialogHost))
    }

    @Test
    fun inGameEditorLayoutInflates() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.AppThemeFullscreen,
        )
        val root = LayoutInflater.from(context).inflate(R.layout.in_game_controls_editor_overlay, null)

        assertNotNull(root.findViewById<android.view.View>(R.id.InGameComposeToolbar))
        assertNotNull(root.findViewById<android.view.View>(R.id.InGameSidebar))
        assertNotNull(root.findViewById<android.view.View>(R.id.InGameComposeDialogHost))
    }
}
