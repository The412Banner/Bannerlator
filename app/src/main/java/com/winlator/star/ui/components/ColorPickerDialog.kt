package com.winlator.star.ui.components

import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.winlator.star.core.Callback
import com.winlator.star.ui.theme.WinlatorTheme

// Hosts the shared Compose ColorPicker inside an AlertDialog so the legacy Java input-controls
// editor (InputControlsFragment) can reuse the same picker as the Appearance screen and the in-game
// drawer. onColorChanged fires live for each adjustment, reporting the ARGB int.
//
// A ComposeView placed in an AlertDialog is NOT under the activity's content view, so its view tree
// has no lifecycle/saved-state/viewmodel owners and Compose would crash — we copy them from the host
// activity (which implements all three) before showing.
fun showControlsColorPickerDialog(activity: ComponentActivity, initialArgb: Int, onColorChanged: Callback<Int>) {
    val composeView = ComposeView(activity).apply {
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
        setContent {
            WinlatorTheme {
                Box(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    ColorPicker(
                        initialColor = Color(initialArgb),
                        onColorChanged = { onColorChanged.call(it.toArgb()) }
                    )
                }
            }
        }
    }
    AlertDialog.Builder(activity)
        .setTitle("Controls accent color")
        .setView(composeView)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
