package com.winlator.star.ui.screens

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class ContainersViewModel(app: Application) : AndroidViewModel(app) {

    private val _containers = MutableStateFlow<List<Container>>(emptyList())
    val containers: StateFlow<List<Container>> = _containers

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var manager: ContainerManager = ContainerManager(app)

    init {
        refresh()
    }

    fun refresh() {
        manager = ContainerManager(getApplication())
        _containers.value = manager.getContainers().toList()
    }

    fun duplicate(container: Container, onDone: () -> Unit) {
        _isLoading.value = true
        // duplicateContainerAsync posts its callback on the main Handler internally
        manager.duplicateContainerAsync(container) {
            _isLoading.value = false
            refresh()
            onDone()
        }
    }

    fun exportContainer(container: Container, onDone: (exportPath: String?) -> Unit) {
        _isLoading.value = true
        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Winlator/Backups/Containers"
        )
        manager.exportContainer(container) {
            _isLoading.value = false
            val path = File(exportDir, container.getRootDir().name)
            onDone(if (path.exists()) path.absolutePath else null)
        }
    }

    fun importContainer(dir: File, onDone: () -> Unit) {
        _isLoading.value = true
        manager.importContainer(dir) {
            _isLoading.value = false
            refresh()
            onDone()
        }
    }

    fun availableBackups(): List<File> {
        val backupDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Winlator/Backups/Containers"
        )
        return backupDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
    }

    fun remove(container: Container, context: Context, onDone: () -> Unit) {
        // Disable any home-screen shortcuts pinned for this container before removing it
        manager.loadShortcuts()
            .filter { it.container == container }
            .forEach { ShortcutsViewModel.disableOnScreen(context, it) }

        _isLoading.value = true
        // removeContainerAsync posts its callback on the main Handler internally
        manager.removeContainerAsync(container) {
            _isLoading.value = false
            refresh()
            onDone()
        }
    }
}
