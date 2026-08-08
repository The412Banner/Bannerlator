package com.winlator.star.ui.screens

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class ContainersViewModel(app: Application) : AndroidViewModel(app) {

    private val localizedContext: Context
        get() = ContextCompat.getContextForLanguage(getApplication())

    private val _containers = MutableStateFlow<List<Container>>(emptyList())
    val containers: StateFlow<List<Container>> = _containers

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // One-shot user message (e.g. a failed duplicate). Screen shows it then calls messageShown().
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var manager: ContainerManager = ContainerManager(app)

    init {
        refresh()
    }

    fun refresh() {
        manager = ContainerManager(getApplication())
        _containers.value = manager.getContainers().toList()
    }

    fun messageShown() {
        _message.value = null
    }

    fun duplicate(container: Container, onDone: () -> Unit) {
        _isLoading.value = true
        // duplicateContainerAsync posts its callback (with the new Container, or null on
        // hard failure) on the main Handler internally.
        manager.duplicateContainerAsync(container) { result ->
            _isLoading.value = false
            refresh()
            _message.value = localizedContext.getString(
                if (result == null) R.string.final_audit_container_duplicate_failed
                else R.string.final_audit_container_duplicate_succeeded,
            )
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

    /** Installed games (shortcuts) belonging to [container] — the source for the per-game backup picker. */
    fun shortcutsFor(container: Container): List<Shortcut> =
        manager.loadShortcuts().filter { it.container.id == container.id }

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
