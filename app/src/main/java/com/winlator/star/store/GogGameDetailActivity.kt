package com.winlator.star.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Html
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.theme.WinlatorTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

class GogGameDetailActivity : ComponentActivity() {

    companion object {
        const val RESULT_REFRESH = 100
        private const val TAG = "BH_GOG_DETAIL"
        private const val REQUEST_FOLDER_PICKER = 200

        fun formatBytes(bytes: Long): String {
            if (bytes >= 1_073_741_824L) return "%.1f GB".format(bytes / 1_073_741_824.0)
            return "%.0f MB".format(bytes / 1_048_576.0)
        }

        private fun formatDate(iso: String?): String {
            if (iso == null || iso.length < 10) return iso ?: ""
            val parts = iso.substring(0, 10).split("-")
            if (parts.size != 3) return iso.substring(0, 10)
            try {
                val year = parts[0].toInt()
                val month = parts[1].toInt()
                val day = parts[2].toInt()
                val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                if (month < 1 || month > 12) return iso.substring(0, 10)
                return "${months[month - 1]} $day, $year"
            } catch (_: Exception) {
                return iso.substring(0, 10)
            }
        }

        private fun shortenPath(path: String): String {
            val parts = path.split("/")
            if (parts.size <= 3) return path
            return "\u2026/${parts[parts.size - 2]}/${parts[parts.size - 1]}"
        }
    }

    private lateinit var prefs: android.content.SharedPreferences

    private var gameId by mutableStateOf("")
    private var title by mutableStateOf("")
    private var imageUrl by mutableStateOf("")
    private var description by mutableStateOf("")
    private var developer by mutableStateOf("")
    private var category by mutableStateOf("")
    private var generation by mutableIntStateOf(0)

    private var headerBitmap by mutableStateOf<Bitmap?>(null)
    private var cancelDownload by mutableStateOf<Runnable?>(null)

    private var exeNameText by mutableStateOf("")
    private var exeNameVisible by mutableStateOf(false)
    private var launchVisible by mutableStateOf(false)
    private var installVisible by mutableStateOf(true)
    private var installBtnText by mutableStateOf("Install")
    private var installBtnColor by mutableIntStateOf(0xFF5533CC.toInt())
    private var setExeVisible by mutableStateOf(false)
    private var uninstallVisible by mutableStateOf(false)
    private var copyVisible by mutableStateOf(false)
    private var progressVisible by mutableStateOf(false)
    private var progressValue by mutableIntStateOf(0)
    private var progressLabel by mutableStateOf("")
    private var progressLabelVisible by mutableStateOf(false)
    private var sizeText by mutableStateOf("Fetching\u2026")

    private var updatesInstalled by mutableStateOf(false)
    private var updateStatusText by mutableStateOf("")
    private var checkUpdateEnabled by mutableStateOf(true)
    private var updateBtnVisible by mutableStateOf(false)

    private var dlcJson by mutableStateOf<String?>(null)

    private var cloudSaveDir by mutableStateOf<String?>(null)
    private var cloudSaveDirText by mutableStateOf("No save folder set")
    private var cloudSaveDirColor by mutableIntStateOf(0xFF555577.toInt())
    private var cloudSaveStatusText by mutableStateOf("")
    private var cloudSaveStatusVisible by mutableStateOf(false)
    private var cloudBtnsEnabled by mutableStateOf(true)

    private var showExePicker by mutableStateOf<ExePickerStateGame?>(null)
    private val cancelFlag = AtomicBoolean(false)

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val selectedPath = result.data!!.getStringExtra("path")
            if (selectedPath != null && selectedPath.isNotEmpty()) {
                prefs.edit().putString("gog_save_dir_$gameId", selectedPath).apply()
                cloudSaveDir = selectedPath
                cloudSaveDirText = shortenPath(selectedPath)
                cloudSaveDirColor = 0xFFCCCCCC.toInt()
                cloudBtnsEnabled = true
                Toast.makeText(this, "Save folder set", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("bh_gog_prefs", 0)

        val i = intent
        gameId = i.getStringExtra("game_id") ?: ""
        if (gameId.isEmpty()) { finish(); return }
        title = i.getStringExtra("title") ?: ""
        imageUrl = i.getStringExtra("image_url") ?: ""
        description = i.getStringExtra("description") ?: ""
        developer = i.getStringExtra("developer") ?: ""
        category = i.getStringExtra("category") ?: ""
        generation = i.getIntExtra("generation", 0)

        dlcJson = prefs.getString("gog_dlcs_$gameId", null)
        updateStatusText = prefs.getString("gog_build_$gameId", null)?.let {
            "Installed build: ${it.substring(0, minOf(12, it.length))}\u2026"
        } ?: "Build ID not recorded \u2014 tap Check to verify"
        updatesInstalled = prefs.getString("gog_exe_$gameId", null) != null
        if (!updatesInstalled) updateStatusText = ""
        checkUpdateEnabled = updatesInstalled

        cloudSaveDir = prefs.getString("gog_save_dir_$gameId", null)
        cloudSaveDirText = cloudSaveDir?.let { shortenPath(it) } ?: "No save folder set"
        cloudSaveDirColor = if (cloudSaveDir != null) 0xFFCCCCCC.toInt() else 0xFF555577.toInt()
        cloudBtnsEnabled = cloudSaveDir != null

        refreshActionState()
        loadInstallSize()

        setContent {
            WinlatorTheme {
                GogGameDetailScreen(
                    headerBitmap = headerBitmap,
                    title = title,
                    generation = generation,
                    developer = developer,
                    category = category,
                    description = description,
                    exeNameText = exeNameText,
                    exeNameVisible = exeNameVisible,
                    launchVisible = launchVisible,
                    installVisible = installVisible,
                    installBtnText = installBtnText,
                    installBtnColor = installBtnColor,
                    setExeVisible = setExeVisible,
                    uninstallVisible = uninstallVisible,
                    copyVisible = copyVisible,
                    progressVisible = progressVisible,
                    progressValue = progressValue,
                    progressLabel = progressLabel,
                    progressLabelVisible = progressLabelVisible,
                    sizeText = sizeText,
                    updatesInstalled = updatesInstalled,
                    updateStatusText = updateStatusText,
                    checkUpdateEnabled = checkUpdateEnabled,
                    updateBtnVisible = updateBtnVisible,
                    dlcJson = dlcJson,
                    cloudSaveDirText = cloudSaveDirText,
                    cloudSaveDirColor = cloudSaveDirColor,
                    cloudSaveStatusText = cloudSaveStatusText,
                    cloudSaveStatusVisible = cloudSaveStatusVisible,
                    cloudBtnsEnabled = cloudBtnsEnabled,
                    onBack = { finish() },
                    onLaunch = {
                        val exe = prefs.getString("gog_exe_$gameId", null)
                        if (exe != null) GogLaunchHelper.addToLauncher(this@GogGameDetailActivity, title, exe, imageUrl)
                    },
                    onInstall = { onInstallClicked() },
                    onSetExe = {
                        val dir = prefs.getString("gog_dir_$gameId", null) ?: return@GogGameDetailScreen
                        val installPath = GogInstallPath.getInstallDir(this@GogGameDetailActivity, dir)
                        lifecycleScope.launch(Dispatchers.IO) {
                            val candidates = GogDownloadManager.collectExeCandidates(installPath)
                            if (candidates.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@GogGameDetailActivity, "No .exe files found", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                            withContext(Dispatchers.Main) {
                                showExePicker = ExePickerStateGame(candidates) { selected ->
                                    if (selected != null && selected.isNotEmpty()) {
                                        prefs.edit().putString("gog_exe_$gameId", selected).apply()
                                        refreshActionState()
                                        setResult(RESULT_REFRESH)
                                        Toast.makeText(this@GogGameDetailActivity, "Exe set: ${File(selected).name}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    },
                    onUninstall = { confirmUninstall() },
                    onCopy = {
                        Toast.makeText(this@GogGameDetailActivity, "Copying\u2026", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch(Dispatchers.IO) {
                            val dest = GogDownloadManager.copyToDownloads(this@GogGameDetailActivity, gameId)
                            withContext(Dispatchers.Main) {
                                if (dest != null) Toast.makeText(this@GogGameDetailActivity, "Copied to: $dest", Toast.LENGTH_LONG).show()
                                else Toast.makeText(this@GogGameDetailActivity, "Copy failed \u2014 check storage permission", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onCheckUpdate = { doCheckUpdate() },
                    onUpdateNow = {
                        updateBtnVisible = false
                        updateStatusText = "Updating\u2026"
                        startInstall()
                    },
                    onBrowseCloud = {
                        val intent = Intent(this@GogGameDetailActivity, FolderPickerActivity::class.java)
                        folderPickerLauncher.launch(intent)
                    },
                    onUploadSaves = {
                        val dir = prefs.getString("gog_save_dir_$gameId", null)
                        if (dir == null) {
                            Toast.makeText(this@GogGameDetailActivity, "Set a save folder first", Toast.LENGTH_SHORT).show()
                            return@GogGameDetailScreen
                        }
                        cloudBtnsEnabled = false
                        showCloudStatus("Preparing upload\u2026")
                        GogCloudSaveManager.uploadSaves(this@GogGameDetailActivity, gameId, File(dir),
                            object : GogCloudSaveManager.Callback {
                                override fun onStatus(msg: String) { runOnUiThread { showCloudStatus(msg) } }
                                override fun onDone(msg: String) { runOnUiThread { showCloudStatus(msg); cloudBtnsEnabled = true } }
                                override fun onError(msg: String) { runOnUiThread { showCloudStatus("Error: $msg"); cloudBtnsEnabled = true } }
                            })
                    },
                    onDownloadSaves = {
                        val dir = prefs.getString("gog_save_dir_$gameId", null)
                        if (dir == null) {
                            Toast.makeText(this@GogGameDetailActivity, "Set a save folder first", Toast.LENGTH_SHORT).show()
                            return@GogGameDetailScreen
                        }
                        cloudBtnsEnabled = false
                        showCloudStatus("Preparing download\u2026")
                        GogCloudSaveManager.downloadSaves(this@GogGameDetailActivity, gameId, File(dir),
                            object : GogCloudSaveManager.Callback {
                                override fun onStatus(msg: String) { runOnUiThread { showCloudStatus(msg) } }
                                override fun onDone(msg: String) { runOnUiThread { showCloudStatus(msg); cloudBtnsEnabled = true } }
                                override fun onError(msg: String) { runOnUiThread { showCloudStatus("Error: $msg"); cloudBtnsEnabled = true } }
                            })
                    },
                )

                showExePicker?.let { state ->
                    ExePickerDialogGame(
                        candidates = state.candidates,
                        onSelected = { path ->
                            state.onSelected(path)
                            showExePicker = null
                        },
                        onDismiss = { showExePicker = null },
                    )
                }
            }
        }

        loadHeaderImage()
    }

    override fun onBackPressed() {
        cancelDownload?.run()
        super.onBackPressed()
    }

    private fun loadHeaderImage() {
        if (imageUrl.isEmpty()) return
        val url = if (imageUrl.startsWith("//")) "https:$imageUrl" else imageUrl
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "GOG Galaxy")
                if (conn.responseCode == 200) {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    if (bmp != null) headerBitmap = bmp
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun loadInstallSize() {
        val cached = prefs.getLong("gog_size_$gameId", -1)
        if (cached > 0) { sizeText = formatBytes(cached); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val token = prefs.getString("access_token", null)
            val size = GogDownloadManager.fetchInstallSizeBytes(gameId, token)
            if (size > 0) prefs.edit().putLong("gog_size_$gameId", size).apply()
            withContext(Dispatchers.Main) {
                sizeText = if (size > 0) formatBytes(size) else "Unknown"
            }
        }
    }

    private fun onInstallClicked() {
        val lbl = installBtnText
        if (lbl == "Cancel") {
            cancelDownload?.run()
            cancelDownload = null
            return
        }
        startInstall()
    }

    private fun startInstall() {
        installBtnText = "Cancel"
        installBtnColor = 0xFFCC3333.toInt()
        progressVisible = true
        progressLabelVisible = true
        launchVisible = false
        setExeVisible = false

        cancelDownload = GogDownloadManager.startDownload(this, makeGogGame(), object : GogDownloadManager.Callback {
            override fun onProgress(msg: String, pct: Int) {
                runOnUiThread {
                    progressValue = pct
                    progressLabel = msg
                }
            }
            override fun onComplete(exePath: String) {
                cancelDownload = null
                runOnUiThread {
                    progressValue = 100
                    progressVisible = false
                    progressLabelVisible = false
                    setResult(RESULT_REFRESH)
                    refreshActionState()
                }
            }
            override fun onError(msg: String) {
                cancelDownload = null
                runOnUiThread {
                    progressVisible = false
                    progressLabelVisible = false
                    installBtnText = "Install"
                    installBtnColor = 0xFF5533CC.toInt()
                    launchVisible = prefs.getString("gog_exe_$gameId", null) != null
                    setExeVisible = prefs.getString("gog_dir_$gameId", null) != null
                    Toast.makeText(this@GogGameDetailActivity, "Error: $msg", Toast.LENGTH_LONG).show()
                }
            }
            override fun onCancelled() {
                cancelDownload = null
                runOnUiThread {
                    progressVisible = false
                    progressLabelVisible = false
                    installBtnText = "Install"
                    installBtnColor = 0xFF5533CC.toInt()
                    launchVisible = prefs.getString("gog_exe_$gameId", null) != null
                    setExeVisible = prefs.getString("gog_dir_$gameId", null) != null
                }
            }
            override fun onSelectExe(candidates: MutableList<String>?, onSelected: Consumer<String>?) {
                showExePicker = if (candidates != null && onSelected != null) {
                    ExePickerStateGame(candidates) { onSelected.accept(it) }
                } else null
            }
        })
    }

    private fun confirmUninstall() {
        val dlgTitle = title
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Uninstall $dlgTitle?")
            .setMessage("This will delete all installed game files.")
            .setPositiveButton("Uninstall") { _, _ -> doUninstall() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doUninstall() {
        val dirName = prefs.getString("gog_dir_$gameId", null) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val installPath = GogInstallPath.getInstallDir(this@GogGameDetailActivity, dirName)
            deleteDir(installPath)
            prefs.edit()
                .remove("gog_dir_$gameId")
                .remove("gog_exe_$gameId")
                .remove("gog_cover_$gameId")
                .apply()
            withContext(Dispatchers.Main) {
                setResult(RESULT_REFRESH)
                refreshActionState()
                Toast.makeText(this@GogGameDetailActivity, "$title uninstalled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshActionState() {
        val exe = prefs.getString("gog_exe_$gameId", null)
        val dir = prefs.getString("gog_dir_$gameId", null)
        val installed = exe != null && dir != null

        if (installed) {
            exeNameText = ".exe: ${File(exe).name}"
            exeNameVisible = true
        } else {
            exeNameVisible = false
        }

        launchVisible = installed
        installVisible = !installed
        if (!installed) installBtnText = "Install"
        if (!installed) installBtnColor = 0xFF5533CC.toInt()
        setExeVisible = installed
        uninstallVisible = installed
        copyVisible = installed
    }

    private fun doCheckUpdate() {
        updateStatusText = "Checking\u2026"
        checkUpdateEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = prefs.getString("access_token", null)
                val url = "https://content-system.gog.com/products/$gameId/os/windows/builds?generation=2"
                val conn = URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "GOG Galaxy")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")

                var body = ""
                if (conn.responseCode == 200) {
                    val br = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (br.readLine().also { line = it } != null) sb.append(line)
                    body = sb.toString()
                }
                conn.disconnect()

                var latestBuild: String? = null
                if (body.isNotEmpty()) {
                    val j = org.json.JSONObject(body)
                    val items = j.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            if ("windows" == item.optString("os")) {
                                latestBuild = item.optString("build_id")
                                break
                            }
                        }
                    }
                }

                val latest = latestBuild
                withContext(Dispatchers.Main) {
                    checkUpdateEnabled = true
                    if (latest == null) {
                        updateStatusText = "Could not reach update server."
                        return@withContext
                    }
                    val stored = prefs.getString("gog_build_$gameId", null)
                    if (stored == null) {
                        prefs.edit().putString("gog_build_$gameId", latest).apply()
                        updateStatusText = "Up to date (build ${latest.substring(0, minOf(12, latest.length))}\u2026)"
                        updateBtnVisible = false
                    } else if (stored == latest) {
                        updateStatusText = "Up to date \u2713"
                        updateBtnVisible = false
                    } else {
                        updateStatusText = "Update available!\nInstalled: ${stored.substring(0, minOf(10, stored.length))}\u2026  \u2192  Latest: ${latest.substring(0, minOf(10, latest.length))}\u2026"
                        updateBtnVisible = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    checkUpdateEnabled = true
                    updateStatusText = "Check failed: ${e.message}"
                }
            }
        }
    }

    private fun makeGogGame() = GogGame(
        gameId,
        title.ifEmpty { "" },
        imageUrl.ifEmpty { "" },
        description.ifEmpty { "" },
        developer.ifEmpty { "" },
        category.ifEmpty { "" },
        generation,
    )

    private fun showCloudStatus(msg: String) {
        cloudSaveStatusText = msg
        cloudSaveStatusVisible = true
    }

    private fun deleteDir(dir: File) {
        if (dir == null || !dir.exists()) return
        val files = dir.listFiles()
        if (files != null) for (f in files) {
            if (f.isDirectory) deleteDir(f) else f.delete()
        }
        dir.delete()
    }

}

private data class ExePickerStateGame(
    val candidates: List<String>,
    val onSelected: (String) -> Unit,
)

// ── Composable Screen ──────────────────────────────────────────────────────

@Composable
private fun GogGameDetailScreen(
    headerBitmap: Bitmap?,
    title: String,
    generation: Int,
    developer: String,
    category: String,
    description: String,
    exeNameText: String,
    exeNameVisible: Boolean,
    launchVisible: Boolean,
    installVisible: Boolean,
    installBtnText: String,
    installBtnColor: Int,
    setExeVisible: Boolean,
    uninstallVisible: Boolean,
    copyVisible: Boolean,
    progressVisible: Boolean,
    progressValue: Int,
    progressLabel: String,
    progressLabelVisible: Boolean,
    sizeText: String,
    updatesInstalled: Boolean,
    updateStatusText: String,
    checkUpdateEnabled: Boolean,
    updateBtnVisible: Boolean,
    dlcJson: String?,
    cloudSaveDirText: String,
    cloudSaveDirColor: Int,
    cloudSaveStatusText: String,
    cloudSaveStatusVisible: Boolean,
    cloudBtnsEnabled: Boolean,
    onBack: () -> Unit,
    onLaunch: () -> Unit,
    onInstall: () -> Unit,
    onSetExe: () -> Unit,
    onUninstall: () -> Unit,
    onCopy: () -> Unit,
    onCheckUpdate: () -> Unit,
    onUpdateNow: () -> Unit,
    onBrowseCloud: () -> Unit,
    onUploadSaves: () -> Unit,
    onDownloadSaves: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .verticalScroll(rememberScrollState()),
    ) {
        // Fixed header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A2E))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("\u2190", color = Color.White, fontSize = 18.sp) }
            Text(
                text = title,
                fontSize = 15.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp).weight(1f),
            )
        }

        // Cover art
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (headerBitmap != null) {
                Image(
                    bitmap = headerBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF111122)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color(0xFF0055FF),
                        strokeWidth = 3.dp,
                    )
                }
            }
        }

        // Info Section
        SectionHeader("GAME INFO")
        InfoCard(generation, developer, category, description, sizeText)

        // Actions Section
        SectionHeader("ACTIONS")
        ActionsCard(
            exeNameText = exeNameText,
            exeNameVisible = exeNameVisible,
            launchVisible = launchVisible,
            installVisible = installVisible,
            installBtnText = installBtnText,
            installBtnColor = installBtnColor,
            setExeVisible = setExeVisible,
            uninstallVisible = uninstallVisible,
            copyVisible = copyVisible,
            progressVisible = progressVisible,
            progressValue = progressValue,
            progressLabel = progressLabel,
            progressLabelVisible = progressLabelVisible,
            onLaunch = onLaunch,
            onInstall = onInstall,
            onSetExe = onSetExe,
            onUninstall = onUninstall,
            onCopy = onCopy,
        )

        // Updates Section
        SectionHeader("UPDATES")
        UpdatesCard(
            installed = updatesInstalled,
            updateStatusText = updateStatusText,
            checkUpdateEnabled = checkUpdateEnabled,
            updateBtnVisible = updateBtnVisible,
            onCheckUpdate = onCheckUpdate,
            onUpdateNow = onUpdateNow,
        )

        // DLC Section
        SectionHeader("DLC")
        DlcCard(dlcJson = dlcJson)

        // Cloud Saves Section
        SectionHeader("CLOUD SAVES")
        CloudSavesCard(
            saveDirText = cloudSaveDirText,
            saveDirColor = cloudSaveDirColor,
            statusText = cloudSaveStatusText,
            statusVisible = cloudSaveStatusVisible,
            btnsEnabled = cloudBtnsEnabled,
            onBrowse = onBrowseCloud,
            onUpload = onUploadSaves,
            onDownload = onDownloadSaves,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = Color(0xFF8888AA),
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.08.sp,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, end = 0.dp, bottom = 6.dp),
    )
}

@Composable
private fun InfoCard(
    generation: Int,
    developer: String,
    category: String,
    description: String,
    sizeText: String,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (generation > 0) {
                Text(
                    text = "Gen $generation",
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .background(
                            color = if (generation == 2) Color(0xFF0277BD) else Color(0xFFE65100),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (developer.isNotEmpty()) {
                InfoRow("Developer", developer)
            }
            if (category.isNotEmpty()) {
                InfoRow("Genre", category)
            }
            InfoRow("Install size", sizeText)
            if (description.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = Html.fromHtml(description, Html.FROM_HTML_MODE_COMPACT).toString(),
                    fontSize = 13.sp,
                    color = Color(0xFFCCCCCC),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("$label: ", fontSize = 13.sp, color = Color(0xFF888888))
        Text(value, fontSize = 13.sp, color = Color(0xFFCCCCCC))
    }
}

@Composable
private fun ActionsCard(
    exeNameText: String,
    exeNameVisible: Boolean,
    launchVisible: Boolean,
    installVisible: Boolean,
    installBtnText: String,
    installBtnColor: Int,
    setExeVisible: Boolean,
    uninstallVisible: Boolean,
    copyVisible: Boolean,
    progressVisible: Boolean,
    progressValue: Int,
    progressLabel: String,
    progressLabelVisible: Boolean,
    onLaunch: () -> Unit,
    onInstall: () -> Unit,
    onSetExe: () -> Unit,
    onUninstall: () -> Unit,
    onCopy: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (exeNameVisible) {
                Text(
                    text = exeNameText,
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (progressVisible) {
                LinearProgressIndicator(
                    progress = { progressValue / 100f },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    color = Color(0xFFFF9800),
                    trackColor = Color(0xFF2A2A2A),
                )
            }
            if (progressLabelVisible) {
                Text(
                    text = progressLabel,
                    fontSize = 11.sp,
                    color = Color(0xFFAAAAAA),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (launchVisible) {
                ActionButton("Launch", 0xFF2E7D32.toInt(), onClick = onLaunch)
            }
            if (installVisible) {
                ActionButton(installBtnText, installBtnColor, onClick = onInstall)
            }
            if (setExeVisible) {
                ActionButton("Set .exe\u2026", 0xFF444444.toInt(), onClick = onSetExe)
            }
            if (uninstallVisible) {
                ActionButton("Uninstall", 0xFF8B0000.toInt(), onClick = onUninstall)
            }
            if (copyVisible) {
                ActionButton("Copy to Downloads", 0xFF333333.toInt(), onClick = onCopy)
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, color: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(color)),
        modifier = Modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, color = Color.White, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun UpdatesCard(
    installed: Boolean,
    updateStatusText: String,
    checkUpdateEnabled: Boolean,
    updateBtnVisible: Boolean,
    onCheckUpdate: () -> Unit,
    onUpdateNow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (!installed) {
                Text(
                    "Install the game first to check for updates.",
                    fontSize = 13.sp,
                    color = Color(0xFF555577),
                )
            } else {
                Text(
                    text = updateStatusText,
                    fontSize = 13.sp,
                    color = Color(0xFFCCCCCC),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (updateBtnVisible) {
                    ActionButton("Update Now", 0xFF0277BD.toInt(), onClick = onUpdateNow)
                }
                ActionButton("Check for Updates", 0xFF333355.toInt(), enabled = checkUpdateEnabled, onClick = onCheckUpdate)
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, color: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Color(color)),
        modifier = Modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, color = Color.White, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DlcCard(dlcJson: String?) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (dlcJson == null || dlcJson == "[]" || dlcJson.isEmpty()) {
                Text("No DLCs in your library for this game", fontSize = 13.sp, color = Color(0xFF555577))
            } else {
                val arr = runCatching { org.json.JSONArray(dlcJson) }.getOrNull()
                if (arr == null || arr.length() == 0) {
                    Text("No DLCs in your library for this game", fontSize = 13.sp, color = Color(0xFF555577))
                } else {
                    Text(
                        "${arr.length()} DLC${if (arr.length() == 1) "" else "s"} owned",
                        fontSize = 12.sp,
                        color = Color(0xFF888888),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "DLC content is included in gen2 game installs.",
                        fontSize = 11.sp,
                        color = Color(0xFF555577),
                        modifier = Modifier.padding(top = 3.dp, bottom = 6.dp),
                    )
                    for (i in 0 until arr.length()) {
                        val dlc = arr.optJSONObject(i) ?: continue
                        val dlcTitle = dlc.optString("title", "Unknown DLC")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E1E2E), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                dlcTitle,
                                fontSize = 13.sp,
                                color = Color(0xFFDDDDDD),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "Owned",
                                fontSize = 11.sp,
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudSavesCard(
    saveDirText: String,
    saveDirColor: Int,
    statusText: String,
    statusVisible: Boolean,
    btnsEnabled: Boolean,
    onBrowse: () -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = saveDirText,
                    fontSize = 12.sp,
                    color = Color(saveDirColor),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onBrowse,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333355)),
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("Browse", color = Color.White, fontSize = 13.sp)
                }
            }
            if (statusVisible) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = Color(0xFF8888AA),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            ActionButton("Upload Saves", 0xFF0277BD.toInt(), enabled = btnsEnabled, onClick = onUpload)
            ActionButton("Download Saves", 0xFF2E7D32.toInt(), enabled = btnsEnabled, onClick = onDownload)
        }
    }
}

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = Color(0xFF161622),
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A3A)),
    ) { content() }
}

@Composable
private fun ExePickerDialogGame(
    candidates: List<String>,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select game executable") },
        text = {
            Column {
                candidates.forEach { path ->
                    val f = File(path)
                    val parent = f.parentFile
                    val label = if (parent != null) "${parent.name}/${f.name}" else f.name
                    TextButton(
                        onClick = { onSelected(path) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label, modifier = Modifier.weight(1f), color = Color.White)
                    }
                }
            }
        },
        confirmButton = {},
    )
}
