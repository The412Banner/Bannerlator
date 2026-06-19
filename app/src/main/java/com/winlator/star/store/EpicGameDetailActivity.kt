package com.winlator.star.store

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Html
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class EpicGameDetailActivity : ComponentActivity() {

    companion object {
        const val RESULT_REFRESH = 100
        private const val TAG = "BH_EPIC_DETAIL"
        private const val REQUEST_FOLDER_PICKER = 200
    }

    private var prefs: SharedPreferences? = null

    private var appName: String? = null
    private var title: String? = null
    private var description: String? = null
    private var developer: String? = null
    private var artCover: String? = null
    private var namespace: String? = null
    private var catalogItemId: String? = null

    private var exeNameText by mutableStateOf("")
    private var installBtnText by mutableStateOf("Install")
    private var installBtnColor by mutableIntStateOf(0xFF1A73E8.toInt())
    private var launchBtnVisible by mutableStateOf(false)
    private var installBtnVisible by mutableStateOf(true)
    private var setExeBtnVisible by mutableStateOf(false)
    private var uninstallBtnVisible by mutableStateOf(false)
    private var progressVisible by mutableStateOf(false)
    private var progressValue by mutableIntStateOf(0)
    private var progressLabelText by mutableStateOf("")
    private var progressLabelVisible by mutableStateOf(false)
    private var sizeText by mutableStateOf("Fetching\u2026")
    private var cancelDownload: Runnable? = null

    private var updateStatusText by mutableStateOf("")
    private var checkUpdatesEnabled by mutableStateOf(true)
    private var updateBtnVisible by mutableStateOf(false)

    private var dlcJson by mutableStateOf<String?>(null)

    private var cloudSaveDirText by mutableStateOf("No save folder set")
    private var cloudSaveDirColor by mutableIntStateOf(0xFF445566.toInt())
    private var cloudSaveStatusText by mutableStateOf("")
    private var cloudSaveStatusVisible by mutableStateOf(false)
    private var cloudButtonsEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("bh_epic_prefs", 0)

        appName = intent.getStringExtra("app_name")
        title = intent.getStringExtra("title")
        description = intent.getStringExtra("description")
        developer = intent.getStringExtra("developer")
        artCover = intent.getStringExtra("art_cover")
        namespace = intent.getStringExtra("namespace")
        catalogItemId = intent.getStringExtra("catalog_item_id")

        if (appName == null) { finish(); return }

        val savedDir = prefs!!.getString("epic_save_dir_$appName", null)
        if (savedDir != null) {
            cloudSaveDirText = shortenPath(savedDir)
            cloudSaveDirColor = 0xFFCCCCCC.toInt()
        }

        dlcJson = catalogItemId?.let { prefs!!.getString("epic_dlcs_$it", null) }

        setContent {
            WinlatorTheme {
                EpicGameDetailScreen(
                    appName = appName!!,
                    title = title ?: "",
                    description = description ?: "",
                    developer = developer ?: "",
                    artCover = artCover ?: "",
                    namespace = namespace ?: "",
                    catalogItemId = catalogItemId ?: "",
                    exeNameText = exeNameText,
                    installBtnText = installBtnText,
                    installBtnColor = installBtnColor,
                    launchBtnVisible = launchBtnVisible,
                    installBtnVisible = installBtnVisible,
                    setExeBtnVisible = setExeBtnVisible,
                    uninstallBtnVisible = uninstallBtnVisible,
                    progressVisible = progressVisible,
                    progressValue = progressValue,
                    progressLabelText = progressLabelText,
                    progressLabelVisible = progressLabelVisible,
                    sizeText = sizeText,
                    updateStatusText = updateStatusText,
                    checkUpdatesEnabled = checkUpdatesEnabled,
                    updateBtnVisible = updateBtnVisible,
                    dlcJson = dlcJson,
                    cloudSaveDirText = cloudSaveDirText,
                    cloudSaveDirColor = cloudSaveDirColor,
                    cloudSaveStatusText = cloudSaveStatusText,
                    cloudSaveStatusVisible = cloudSaveStatusVisible,
                    cloudButtonsEnabled = cloudButtonsEnabled,
                    onBack = { finish() },
                    onLaunchClick = { pendingLaunchExe() },
                    onInstallClick = { onInstallClicked() },
                    onSetExeClick = { onSetExeClicked() },
                    onUninstallClick = { confirmUninstall() },
                    onCheckUpdates = { doCheckUpdate() },
                    onUpdateClick = {
                        updateBtnVisible = false
                        updateStatusText = "Updating\u2026"
                        startInstallInternal()
                    },
                    onDlcInstall = { dlcApp, dlcNs, dlcCat, dlcTitle ->
                        dlcInstall(dlcApp, dlcNs, dlcCat, dlcTitle)
                    },
                    onCloudBrowse = {
                        startActivityForResult(
                            Intent(this@EpicGameDetailActivity, FolderPickerActivity::class.java),
                            REQUEST_FOLDER_PICKER,
                        )
                    },
                    onCloudUpload = { cloudUpload() },
                    onCloudDownload = { cloudDownload() },
                )
            }
        }

        refreshActionState()
        loadInstallSize()
    }

    override fun onBackPressed() {
        cancelDownload?.run()
        super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER_PICKER && resultCode == RESULT_OK && data != null) {
            val selectedPath = data.getStringExtra("path")
            if (!selectedPath.isNullOrEmpty()) {
                prefs!!.edit().putString("epic_save_dir_$appName", selectedPath).apply()
                cloudSaveDirText = shortenPath(selectedPath)
                cloudSaveDirColor = 0xFFCCCCCC.toInt()
                cloudButtonsEnabled = true
                Toast.makeText(this, "Save folder set", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshActionState() {
        val exe = prefs!!.getString("epic_exe_$appName", null)
        val dir = prefs!!.getString("epic_dir_$appName", null)
        val installed = exe != null

        exeNameText = if (installed) ".exe: ${File(exe!!).name}" else ""
        launchBtnVisible = installed
        installBtnVisible = !installed
        setExeBtnVisible = installed
        uninstallBtnVisible = dir != null

        if (!installed) installBtnText = "Install"
    }

    private fun onInstallClicked() {
        if (installBtnText == "Cancel") {
            cancelDownload?.run(); cancelDownload = null
            return
        }
        startInstallInternal()
    }

    private fun startInstallInternal() {
        installBtnText = "Cancel"
            installBtnColor = 0xFFCC3333.toInt()
        progressVisible = true
        progressLabelVisible = true
        launchBtnVisible = false
        setExeBtnVisible = false
        progressLabelText = ""

        val cancelled = AtomicBoolean(false)
        cancelDownload = Runnable { cancelled.set(true) }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = EpicCredentialStore.getValidAccessToken(this@EpicGameDetailActivity)
                if (token == null) { onInstallError("Login required"); return@launch }

                withContext(Dispatchers.Main) { progressLabelText = "Fetching manifest\u2026" }
                val manifestJson = EpicApiClient.getManifestApiJson(token, namespace, catalogItemId, appName)
                if (manifestJson == null) { onInstallError("Failed to fetch manifest"); return@launch }

                var sanitized = (title ?: "").replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim()
                if (sanitized.isEmpty()) sanitized = "epic_${appName.hashCode()}"
                val installDir = File(File(filesDir, "epic_games"), sanitized)
                prefs!!.edit().putString("epic_dir_$appName", installDir.absolutePath).apply()

                val ok = EpicDownloadManager.install(
                    this@EpicGameDetailActivity,
                    manifestJson,
                    token,
                    installDir.absolutePath,
                ) { msg, pct ->
                    if (!cancelled.get()) {
                        runOnUiThread {
                            progressValue = pct
                            progressLabelText = msg
                        }
                    }
                }

                if (cancelled.get()) { onInstallCancelled(); return@launch }
                if (!ok) { onInstallError("Download failed"); return@launch }

                try {
                    val vid = JSONObject(manifestJson).optString("versionId", "")
                    if (vid.isNotEmpty()) {
                        prefs!!.edit().putString("epic_manifest_version_$appName", vid).apply()
                    }
                } catch (_: Exception) {}

                val exeFiles = mutableListOf<File>()
                AmazonLaunchHelper.collectExe(installDir, exeFiles)
                if (exeFiles.isEmpty()) { onInstallError("No executable found"); return@launch }

                val lowerTitle = (title ?: "").lowercase()
                exeFiles.sortWith { a, b ->
                    AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
                }

                if (exeFiles.size == 1) {
                    prefs!!.edit().putString("epic_exe_$appName", exeFiles[0].absolutePath).apply()
                    onInstallComplete()
                } else {
                    val candidates = exeFiles.map { it.absolutePath }
                    withContext(Dispatchers.Main) {
                        showExePicker(candidates) { selected ->
                            val chosen = if (!selected.isNullOrEmpty()) selected else exeFiles[0].absolutePath
                            prefs!!.edit().putString("epic_exe_$appName", chosen).apply()
                            onInstallComplete()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!cancelled.get()) onInstallError(e.message ?: "Unknown error")
            }
        }
    }

    private fun onInstallComplete() {
        cancelDownload = null
        runOnUiThread {
            progressVisible = false
            progressLabelVisible = false
            setResult(RESULT_REFRESH)
            refreshActionState()
        }
    }

    private fun onInstallError(msg: String) {
        cancelDownload = null
        runOnUiThread {
            progressVisible = false
            progressLabelVisible = false
            installBtnText = "Install"
            installBtnColor = 0xFF1A73E8.toInt()
            launchBtnVisible = true
            setExeBtnVisible = true
            Toast.makeText(this@EpicGameDetailActivity, "Error: $msg", Toast.LENGTH_LONG).show()
        }
    }

    private fun onInstallCancelled() {
        cancelDownload = null
        runOnUiThread {
            progressVisible = false
            progressLabelVisible = false
            installBtnText = "Install"
            installBtnColor = 0xFF1A73E8.toInt()
            launchBtnVisible = true
            setExeBtnVisible = true
        }
    }

    private fun confirmUninstall() {
        AlertDialog.Builder(this)
            .setTitle("Uninstall $title?")
            .setMessage("This will delete all installed game files.")
            .setPositiveButton("Uninstall") { _, _ ->
                val dir = prefs!!.getString("epic_dir_$appName", null) ?: return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    deleteDir(File(dir))
                    prefs!!.edit()
                        .remove("epic_exe_$appName")
                        .remove("epic_dir_$appName")
                        .apply()
                    runOnUiThread {
                        setResult(RESULT_REFRESH)
                        refreshActionState()
                        Toast.makeText(this@EpicGameDetailActivity, "$title uninstalled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pendingLaunchExe() {
        val exe = prefs!!.getString("epic_exe_$appName", null) ?: return
        prefs!!.edit().putString("pending_epic_exe", exe).apply()
        val intent = Intent().apply {
            setClassName(packageName, "com.xj.landscape.launcher.ui.main.LandscapeLauncherMainActivity")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun onSetExeClicked() {
        val dir = prefs!!.getString("epic_dir_$appName", null) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val exeFiles = mutableListOf<File>()
            AmazonLaunchHelper.collectExe(File(dir), exeFiles)
            if (exeFiles.isEmpty()) {
                runOnUiThread { Toast.makeText(this@EpicGameDetailActivity, "No .exe files found", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            val candidates = exeFiles.map { it.absolutePath }
            runOnUiThread {
                showExePicker(candidates) { selected ->
                    if (!selected.isNullOrEmpty()) {
                        prefs!!.edit().putString("epic_exe_$appName", selected).apply()
                        refreshActionState()
                        setResult(RESULT_REFRESH)
                        Toast.makeText(this@EpicGameDetailActivity, "Exe set: ${File(selected).name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun loadInstallSize() {
        val cached = prefs!!.getLong("epic_size_$appName", -1L)
        if (cached > 0) {
            sizeText = formatBytes(cached)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val token = EpicCredentialStore.getValidAccessToken(this@EpicGameDetailActivity)
            val size = if (token != null)
                EpicDownloadManager.fetchInstallSizeBytes(token, namespace, catalogItemId, appName)
            else -1L
            if (size > 0) prefs!!.edit().putLong("epic_size_$appName", size).apply()
            val finalSize = size
            runOnUiThread {
                sizeText = if (finalSize > 0) formatBytes(finalSize) else "Unknown"
            }
        }
    }

    private fun doCheckUpdate() {
        updateStatusText = "Checking\u2026"
        checkUpdatesEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = EpicCredentialStore.getValidAccessToken(this@EpicGameDetailActivity)
                if (token == null) {
                    runOnUiThread {
                        checkUpdatesEnabled = true
                        updateStatusText = "Login required."
                    }
                    return@launch
                }
                val manifestJson = EpicApiClient.getManifestApiJson(token, namespace, catalogItemId, appName)
                var latestVer: String? = null
                if (manifestJson != null) {
                    try { latestVer = JSONObject(manifestJson).optString("versionId", null) } catch (_: Exception) {}
                }
                val latest = latestVer
                runOnUiThread {
                    checkUpdatesEnabled = true
                    if (latest.isNullOrEmpty()) {
                        updateStatusText = "Could not reach update server."
                        return@runOnUiThread
                    }
                    val stored = prefs!!.getString("epic_manifest_version_$appName", null)
                    if (stored == null) {
                        prefs!!.edit().putString("epic_manifest_version_$appName", latest).apply()
                        updateStatusText = "Up to date \u2713"
                        updateBtnVisible = false
                    } else if (stored == latest) {
                        updateStatusText = "Up to date \u2713"
                        updateBtnVisible = false
                    } else {
                        updateStatusText = "Update available!\nInstalled: ${stored.substring(0, minOf(12, stored.length))}\u2026  \u2192  Latest: ${latest.substring(0, minOf(12, latest.length))}\u2026"
                        updateBtnVisible = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    checkUpdatesEnabled = true
                    updateStatusText = "Check failed: ${e.message}"
                }
            }
        }
    }

    private fun dlcInstall(dlcApp: String, dlcNs: String, dlcCat: String, dlcTitle: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = EpicCredentialStore.getValidAccessToken(this@EpicGameDetailActivity)
                if (token == null) {
                    runOnUiThread { Toast.makeText(this@EpicGameDetailActivity, "Login required", Toast.LENGTH_SHORT).show() }
                    return@launch
                }

                val manifestJson = EpicApiClient.getManifestApiJson(token, dlcNs, dlcCat, dlcApp)
                if (manifestJson == null) {
                    runOnUiThread { Toast.makeText(this@EpicGameDetailActivity, "Failed to fetch manifest for DLC", Toast.LENGTH_SHORT).show() }
                    return@launch
                }

                var sanitized = dlcTitle.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim()
                if (sanitized.isEmpty()) sanitized = "dlc_${dlcApp.hashCode()}"
                val installDir = File(File(filesDir, "epic_games"), sanitized)
                prefs!!.edit().putString("epic_dir_$dlcApp", installDir.absolutePath).apply()

                val ok = EpicDownloadManager.install(
                    this@EpicGameDetailActivity,
                    manifestJson,
                    token,
                    installDir.absolutePath,
                ) { _, _ -> }
                if (!ok) {
                    runOnUiThread { Toast.makeText(this@EpicGameDetailActivity, "DLC download failed", Toast.LENGTH_SHORT).show() }
                    return@launch
                }

                val exeFiles = mutableListOf<File>()
                AmazonLaunchHelper.collectExe(installDir, exeFiles)
                if (exeFiles.isNotEmpty()) {
                    val lowerT = dlcTitle.lowercase()
                    exeFiles.sortWith { a, b ->
                        AmazonLaunchHelper.scoreExe(b, lowerT) - AmazonLaunchHelper.scoreExe(a, lowerT)
                    }
                    prefs!!.edit().putString("epic_exe_$dlcApp", exeFiles[0].absolutePath).apply()
                }

                runOnUiThread {
                    setResult(RESULT_REFRESH)
                    refreshActionState()
                    Toast.makeText(this@EpicGameDetailActivity, "$dlcTitle installed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@EpicGameDetailActivity, "DLC install error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun cloudUpload() {
        val dir = prefs!!.getString("epic_save_dir_$appName", null)
        if (dir == null) { Toast.makeText(this, "Set a save folder first", Toast.LENGTH_SHORT).show(); return }
        cloudButtonsEnabled = false
        cloudSaveStatusText = "Preparing upload\u2026"
        cloudSaveStatusVisible = true
        EpicCloudSaveManager.uploadSaves(this, appName!!, File(dir), object : EpicCloudSaveManager.Callback {
            override fun onStatus(msg: String) { runOnUiThread { cloudSaveStatusText = msg } }
            override fun onDone(msg: String) { runOnUiThread { cloudSaveStatusText = msg; cloudButtonsEnabled = true } }
            override fun onError(msg: String) { runOnUiThread { cloudSaveStatusText = "Error: $msg"; cloudButtonsEnabled = true } }
        })
    }

    private fun cloudDownload() {
        val dir = prefs!!.getString("epic_save_dir_$appName", null)
        if (dir == null) { Toast.makeText(this, "Set a save folder first", Toast.LENGTH_SHORT).show(); return }
        cloudButtonsEnabled = false
        cloudSaveStatusText = "Preparing download\u2026"
        cloudSaveStatusVisible = true
        EpicCloudSaveManager.downloadSaves(this, appName!!, File(dir), object : EpicCloudSaveManager.Callback {
            override fun onStatus(msg: String) { runOnUiThread { cloudSaveStatusText = msg } }
            override fun onDone(msg: String) { runOnUiThread { cloudSaveStatusText = msg; cloudButtonsEnabled = true } }
            override fun onError(msg: String) { runOnUiThread { cloudSaveStatusText = "Error: $msg"; cloudButtonsEnabled = true } }
        })
    }

    private fun showExePicker(candidates: List<String>, onSelected: (String?) -> Unit) {
        val labels = candidates.map { path ->
            val f = File(path)
            val parent = f.parentFile
            (if (parent != null) "${parent.name}/${f.name}" else f.name)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select game executable")
            .setItems(labels) { _, which -> onSelected(candidates[which]) }
            .setCancelable(false)
            .show()
    }

    private fun deleteDir(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { f -> if (f.isDirectory) deleteDir(f) else f.delete() }
        dir.delete()
    }

    private fun shortenPath(path: String): String {
        val parts = path.split("/")
        if (parts.size <= 3) return path
        return "\u2026/${parts[parts.size - 2]}/${parts[parts.size - 1]}"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "$bytes B"
    }
}

@Composable
private fun EpicGameDetailScreen(
    appName: String,
    title: String,
    description: String,
    developer: String,
    artCover: String,
    namespace: String,
    catalogItemId: String,
    exeNameText: String,
    installBtnText: String,
    installBtnColor: Int,
    launchBtnVisible: Boolean,
    installBtnVisible: Boolean,
    setExeBtnVisible: Boolean,
    uninstallBtnVisible: Boolean,
    progressVisible: Boolean,
    progressValue: Int,
    progressLabelText: String,
    progressLabelVisible: Boolean,
    sizeText: String,
    updateStatusText: String,
    checkUpdatesEnabled: Boolean,
    updateBtnVisible: Boolean,
    dlcJson: String?,
    cloudSaveDirText: String,
    cloudSaveDirColor: Int,
    cloudSaveStatusText: String,
    cloudSaveStatusVisible: Boolean,
    cloudButtonsEnabled: Boolean,
    onBack: () -> Unit,
    onLaunchClick: () -> Unit,
    onInstallClick: () -> Unit,
    onSetExeClick: () -> Unit,
    onUninstallClick: () -> Unit,
    onCheckUpdates: () -> Unit,
    onUpdateClick: () -> Unit,
    onDlcInstall: (String, String, String, String) -> Unit,
    onCloudBrowse: () -> Unit,
    onCloudUpload: () -> Unit,
    onCloudDownload: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("bh_epic_prefs", 0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D2040))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailButton("\u2190", 0xFF1A3050.toInt(), modifier = Modifier.height(36.dp), onClick = onBack)
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (artCover.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFF0D1A2E)),
            ) {
                AsyncImage(
                    model = artCover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        SectionHeader("GAME INFO")
        DetailCard {
            if (developer.isNotEmpty()) InfoRow("Developer", developer)
            if (appName.isNotEmpty()) InfoRow("App", appName)
            val releaseDate = prefs.getString("epic_release_$appName", null)
            if (!releaseDate.isNullOrEmpty()) {
                InfoRow("Released", formatDateStatic(releaseDate))
            }
            InfoRowWithRef("Install size", sizeText)
            if (description.isNotEmpty()) {
                val plain = Html.fromHtml(description, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                val desc = if (plain.length > 400) "${plain.substring(0, 400)}\u2026" else plain
                Text(
                    text = desc,
                    fontSize = 13.sp,
                    color = Color(0xFFCCCCCC),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        SectionHeader("ACTIONS")
        DetailCard {
            Text(
                text = exeNameText,
                fontSize = 12.sp,
                color = Color(0xFF888888),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (progressVisible) {
                LinearProgressIndicator(
                    progress = { progressValue / 100f },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).height(4.dp),
                    color = Color(0xFF0078F0),
                    trackColor = Color(0xFF2A2A2A),
                )
            }
            if (progressLabelVisible) {
                Text(
                    text = progressLabelText,
                    fontSize = 11.sp,
                    color = Color(0xFFAAAAAA),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (launchBtnVisible) DetailButton("Launch", 0xFF2E7D32.toInt(), onClick = onLaunchClick)
            if (installBtnVisible) DetailButton(installBtnText, installBtnColor, onClick = onInstallClick)
            if (setExeBtnVisible) DetailButton("Set .exe\u2026", 0xFF444444.toInt(), onClick = onSetExeClick)
            if (uninstallBtnVisible) DetailButton("Uninstall", 0xFF8B0000.toInt(), onClick = onUninstallClick)
        }

        SectionHeader("UPDATES")
        DetailCard {
            val installed = prefs.getString("epic_exe_$appName", null) != null
            if (!installed) {
                Text("Install the game first to check for updates.", fontSize = 13.sp, color = Color(0xFF445566))
            } else {
                val displayText = if (updateStatusText.isNotEmpty()) updateStatusText
                else {
                    val storedVer = prefs.getString("epic_manifest_version_$appName", null)
                    if (storedVer != null) "Installed: ${storedVer.substring(0, minOf(14, storedVer.length))}\u2026"
                    else "Version not recorded \u2014 tap Check to verify"
                }
                Text(
                    text = displayText,
                    fontSize = 13.sp,
                    color = Color(0xFFCCCCCC),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (updateBtnVisible) DetailButton("Update Now", 0xFF0D5CA8.toInt(), onClick = onUpdateClick)
                DetailButton("Check for Updates", 0xFF1A2A3A.toInt(), enabled = checkUpdatesEnabled, onClick = onCheckUpdates)
            }
        }

        SectionHeader("DLC")
        DetailCard {
            if (dlcJson.isNullOrEmpty() || dlcJson == "[]") {
                Text("No DLCs in your library for this game", fontSize = 13.sp, color = Color(0xFF445566))
            } else {
                val arr = runCatching { org.json.JSONArray(dlcJson) }.getOrNull()
                if (arr == null) {
                    Text("Error reading DLC data", fontSize = 13.sp, color = Color(0xFF445566))
                } else if (arr.length() == 0) {
                    Text("No DLCs in your library for this game", fontSize = 13.sp, color = Color(0xFF445566))
                } else {
                        Text(
                            text = "${arr.length()} DLC${if (arr.length() == 1) "" else "s"} owned",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888),
                        )
                        for (i in 0 until arr.length()) {
                            val dlc = arr.optJSONObject(i) ?: continue
                            val dlcApp = dlc.optString("app", "")
                            val dlcNs = dlc.optString("ns", "")
                            val dlcCat = dlc.optString("cat", "")
                            val dlcTitle = dlc.optString("title", "Unknown DLC")
                            val dlcInstalled = prefs.getString("epic_exe_$dlcApp", null) != null

                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1929)),
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = dlcTitle,
                                            fontSize = 13.sp,
                                            color = Color(0xFFDDDDDD),
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (dlcInstalled) {
                                            Text("\u2713 Installed", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                        }
                                    }
                                    if (dlcApp.isNotEmpty() && dlcNs.isNotEmpty() && dlcCat.isNotEmpty()) {
                                        DetailButton(
                                            if (dlcInstalled) "Reinstall" else "Install",
                                            if (dlcInstalled) 0xFF2A4A2A.toInt() else 0xFF1A73E8.toInt(),
                                            modifier = Modifier.padding(top = 4.dp).height(36.dp),
                                            onClick = { onDlcInstall(dlcApp, dlcNs, dlcCat, dlcTitle) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

        SectionHeader("CLOUD SAVES")
        DetailCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                Text(
                    text = cloudSaveDirText,
                    fontSize = 12.sp,
                    color = Color(cloudSaveDirColor),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                DetailButton("Browse", 0xFF333355.toInt(), modifier = Modifier.height(36.dp), onClick = onCloudBrowse)
            }
            if (cloudSaveStatusVisible) {
                Text(
                    text = cloudSaveStatusText,
                    fontSize = 12.sp,
                    color = Color(0xFF8888AA),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            DetailButton("Upload Saves", 0xFF0277BD.toInt(), enabled = cloudButtonsEnabled, onClick = onCloudUpload)
            DetailButton("Download Saves", 0xFF2E7D32.toInt(), enabled = cloudButtonsEnabled, onClick = onCloudDownload)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF6688AA),
        letterSpacing = 0.08.sp,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, end = 0.dp, bottom = 6.dp),
    )
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111A2A)),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFF1A2A3A)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(text = "$label: ", fontSize = 13.sp, color = Color(0xFF888888))
        Text(text = value, fontSize = 13.sp, color = Color(0xFFCCCCCC), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoRowWithRef(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(text = "$label: ", fontSize = 13.sp, color = Color(0xFF888888))
        Text(text = value, fontSize = 13.sp, color = Color(0xFFCCCCCC), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DetailButton(
    text: String,
    color: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Color(color)),
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp).height(42.dp),
        shape = RoundedCornerShape(6.dp),
    ) { Text(text, color = Color.White, fontSize = 13.sp) }
}

private fun formatDateStatic(iso: String): String {
    if (iso.length < 10) return iso
    val parts = iso.substring(0, 10).split("-")
    if (parts.size != 3) return iso.substring(0, 10)
    return try {
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        val day = parts[2].toInt()
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        if (month < 1 || month > 12) return iso.substring(0, 10)
        "${months[month - 1]} $day, $year"
    } catch (_: Exception) {
        iso.substring(0, 10)
    }
}
