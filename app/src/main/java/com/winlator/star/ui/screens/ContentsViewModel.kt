package com.winlator.star.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ContentsViewModel(app: Application) : AndroidViewModel(app) {

    val manager = ContentsManager(app)

    private val _filter = MutableStateFlow(ContentProfile.ContentType.CONTENT_TYPE_WINE)
    val filter: StateFlow<ContentProfile.ContentType> = _filter

    private val _profiles = MutableStateFlow<List<ContentProfile>>(emptyList())
    val profiles: StateFlow<List<ContentProfile>> = _profiles

    /** Map of profile.verName → [0.0, 1.0] download progress; absent = not downloading */
    private val _downloadingKeys = MutableStateFlow<Set<String>>(emptySet())
    val downloadingKeys: StateFlow<Set<String>> = _downloadingKeys

    private val _isLoadingRemote = MutableStateFlow(false)
    val isLoadingRemote: StateFlow<Boolean> = _isLoadingRemote

    init {
        manager.syncContents()
        refreshList()
    }

    fun setFilter(type: ContentProfile.ContentType) {
        _filter.value = type
        refreshList()
    }

    fun refreshList() {
        _profiles.value = manager.getProfiles(_filter.value)
    }

    /** Fetch remote profile JSON and reload list. */
    fun syncRemote(remoteUrl: String) {
        _isLoadingRemote.value = true
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) { Downloader.downloadString(remoteUrl) }
            if (json != null) {
                manager.setRemoteProfiles(json)
                refreshList()
            }
            _isLoadingRemote.value = false
        }
    }

    /** Download a remote content file then call back with the local Uri on the main thread. */
    fun downloadRemote(
        profile: ContentProfile,
        cacheDir: File,
        onReady: (Uri) -> Unit,
    ) {
        val key = profileKey(profile)
        _downloadingKeys.value = _downloadingKeys.value + key
        viewModelScope.launch {
            val output = withContext(Dispatchers.IO) {
                val f = File(cacheDir, "temp_${System.currentTimeMillis()}")
                if (Downloader.downloadFile(profile.remoteUrl, f)) f else null
            }
            _downloadingKeys.value = _downloadingKeys.value - key
            if (output != null) onReady(Uri.fromFile(output))
        }
    }

    fun removeContent(profile: ContentProfile) {
        manager.removeContent(profile)
        manager.syncContents()
        refreshList()
    }

    companion object {
        fun profileKey(p: ContentProfile) = "${p.type.name}_${p.verName}_${p.verCode}"
    }
}
