package com.winlator.star.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winlator.star.MainActivity
import com.winlator.star.xenvironment.ImageFs
import com.winlator.star.xenvironment.ImageFsInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SplashViewModel(app: Application) : AndroidViewModel(app) {

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    /** True once installation reaches 100% — show the Proceed button. */
    private val _showProceed = MutableStateFlow(false)
    val showProceed: StateFlow<Boolean> = _showProceed

    /**
     * Check whether the system image needs (re)installation.
     * Returns true if an install was triggered; the caller should show the install overlay.
     */
    fun installIfNeeded(activity: MainActivity): Boolean {
        if (_isInstalling.value) return true   // already running

        val imageFs = ImageFs.find(activity)
        if (imageFs.isValid && imageFs.version >= ImageFsInstaller.LATEST_VERSION) {
            // imagefs is current, so no re-extract — but the frame-gen layers and ffmpeg-8 ship as
            // APK assets alongside it, and an app update can carry newer builds of them. Without
            // this they would only ever land on a full re-extract, so a bundled layer update
            // silently never reached the device. Off the main thread: this can copy several MB.
            viewModelScope.launch(Dispatchers.IO) {
                ImageFsInstaller.stageBundledComponents(getApplication(), imageFs)
            }
            return false
        }

        _isInstalling.value = true
        _progress.value = 0

        ImageFsInstaller.installFromAssetsWithCallback(
            activity,
            { pct ->
                _progress.value = pct
            },
            {
                // Install complete. Progress is derived from an estimated content length
                // (and the post-extraction steps report nothing), so the last tick lands
                // around 98%. Snap to 100% here — the true completion point — so the bar
                // reads "Installation complete" instead of freezing under the Proceed button.
                _progress.value = 100
                _showProceed.value = true
            },
        )
        return true
    }

    /** Called when user taps Proceed; hides the splash overlay. */
    fun dismissSplash() {
        _showProceed.value = false
        _isInstalling.value = false
    }
}
