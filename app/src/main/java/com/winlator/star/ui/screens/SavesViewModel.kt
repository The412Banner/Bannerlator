package com.winlator.star.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.core.FileUtils
import com.winlator.star.core.TarCompressorUtils
import com.winlator.star.saves.Save
import com.winlator.star.saves.SaveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavesViewModel(app: Application) : AndroidViewModel(app) {

    private val localizedContext: Context
        get() = ContextCompat.getContextForLanguage(getApplication())

    private val _saves = MutableStateFlow<List<Save>>(emptyList())
    val saves: StateFlow<List<Save>> = _saves

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val saveManager = SaveManager(app)
    private val containerManager = ContainerManager(app)

    val containers: List<Container> get() = containerManager.getContainers()

    init { refresh() }

    fun refresh() {
        _saves.value = saveManager.getSaves()
    }

    fun addSave(title: String, path: String, container: Container, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                saveManager.addSave(title, path, container)
                withContext(Dispatchers.Main) { refresh(); onDone(true, "") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone(false, localizedContext.getString(R.string.saves_add_failed)) }
            }
        }
    }

    fun updateSave(save: Save, newTitle: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                saveManager.updateSave(save, newTitle, save.path, save.container)
                withContext(Dispatchers.Main) { refresh(); onDone(true, "") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone(false, localizedContext.getString(R.string.saves_update_failed)) }
            }
        }
    }

    fun transferSave(save: Save, newContainer: Container, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                saveManager.transferSave(save, newContainer)
                withContext(Dispatchers.Main) { refresh(); onDone(true, "") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone(false, localizedContext.getString(R.string.saves_transfer_failed)) }
            }
        }
    }

    fun removeSave(save: Save) {
        saveManager.removeSave(save)
        refresh()
    }

    fun exportSave(context: Context, save: Save, shareAfterExport: Boolean) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saveDirectory = File(save.path)
                if (!saveDirectory.exists() || !saveDirectory.isDirectory) {
                    withContext(Dispatchers.Main) {
                        _isLoading.value = false
                        Toast.makeText(context, localizedContext.getString(R.string.saves_invalid_directory), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val saveJsonFile = saveManager.getSaveJsonFile(save)
                if (!saveJsonFile.exists()) {
                    withContext(Dispatchers.Main) {
                        _isLoading.value = false
                        Toast.makeText(context, localizedContext.getString(R.string.saves_json_missing), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val exportDirectory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Winlator/Saves",
                )
                if (!exportDirectory.exists()) exportDirectory.mkdirs()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val exportFile = File(exportDirectory, "${save.getTitle()}_${timestamp}.tar.xz")
                val tempDir = File(exportDirectory, "temp_${save.getTitle()}_${timestamp}")
                tempDir.mkdirs()

                FileUtils.copy(saveJsonFile, File(tempDir, saveJsonFile.name))
                FileUtils.copy(saveDirectory, File(tempDir, saveDirectory.name))
                TarCompressorUtils.compress(TarCompressorUtils.Type.XZ, tempDir, exportFile, 3, null)
                FileUtils.delete(tempDir)

                MediaScannerConnection.scanFile(context, arrayOf(exportFile.absolutePath), null, null)
                exportFile.setReadable(true, false)

                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    Toast.makeText(context, localizedContext.getString(R.string.saves_exported_to, exportFile.absolutePath), Toast.LENGTH_LONG).show()
                    if (shareAfterExport) {
                        val authority = context.packageName + ".tileprovider"
                        val fileUri = FileProvider.getUriForFile(context, authority, exportFile)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, fileUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, localizedContext.getString(R.string.saves_share_archive)))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    Toast.makeText(context, localizedContext.getString(R.string.saves_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun importSave(context: Context, uri: Uri, container: Container, onDone: (Boolean, String) -> Unit) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(context.cacheDir, "import_temp")
                if (tempDir.exists()) FileUtils.delete(tempDir)
                tempDir.mkdirs()

                if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, tempDir)) {
                    withContext(Dispatchers.Main) {
                        _isLoading.value = false
                        onDone(false, localizedContext.getString(R.string.saves_decompress_failed))
                    }
                    return@launch
                }

                val extractedFiles = tempDir.listFiles()
                if (extractedFiles == null || extractedFiles.size != 1 || !extractedFiles[0].isDirectory) {
                    withContext(Dispatchers.Main) {
                        _isLoading.value = false
                        onDone(false, localizedContext.getString(R.string.saves_unexpected_archive))
                    }
                    return@launch
                }

                val extractedDir = extractedFiles[0]
                val jsonFiles = extractedDir.listFiles { _, name -> name.endsWith(".json") }
                if (jsonFiles == null || jsonFiles.size != 1) {
                    withContext(Dispatchers.Main) {
                        _isLoading.value = false
                        onDone(false, localizedContext.getString(R.string.saves_archive_json_missing))
                    }
                    return@launch
                }

                val saveData = JSONObject(FileUtils.readString(jsonFiles[0]))
                val title = saveData.getString("Title")
                val savePath = saveData.getString("Path")

                val destRootDir = File(container.getRootDir(), ".wine/drive_c")
                val driveCIndex = savePath.indexOf("drive_c")
                val relativePath = if (driveCIndex != -1) savePath.substring(driveCIndex + "drive_c/".length) else savePath
                val destSaveDir = File(destRootDir, relativePath)
                destSaveDir.parentFile?.mkdirs()

                FileUtils.copy(File(extractedDir, File(savePath).name), destSaveDir)
                saveManager.addSave(title, destSaveDir.absolutePath, container)
                FileUtils.delete(tempDir)

                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    refresh()
                    onDone(true, localizedContext.getString(R.string.saves_import_success))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    onDone(false, localizedContext.getString(R.string.saves_import_failed))
                }
            }
        }
    }
}
