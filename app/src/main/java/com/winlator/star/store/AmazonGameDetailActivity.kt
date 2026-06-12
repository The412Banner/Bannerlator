package com.winlator.star.store

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AmazonGameDetailActivity : ComponentActivity() {

    companion object {
        const val RESULT_REFRESH = 100
        private const val TAG = "BH_AMAZON_DETAIL"

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            else -> "%.0f MB".format(bytes / 1_048_576.0)
        }
    }

    private var prefs: SharedPreferences? = null

    // Extras
    private var productId: String? = null
    private var entitlementId: String? = null
    private var titleText: String? = null
    private var developer: String? = null
    private var publisher: String? = null
    private var artUrl: String? = null
    private var productSku: String? = null

    // UI state
    private var exeNameText by mutableStateOf("")
    private var exeNameVisible by mutableStateOf(false)
    private var progressVisible by mutableStateOf(false)
    private var progressValue by mutableIntStateOf(0)
    private var progressLabel by mutableStateOf("")
    private var progressLabelVisible by mutableStateOf(false)
    private var launchBtnVisible by mutableStateOf(false)
    private var launchBtnEnabled by mutableStateOf(false)
    private var installBtnVisible by mutableStateOf(true)
    private var installBtnText by mutableStateOf("Install")
    private var installBtnColor by mutableIntStateOf(0xFFFF9900.toInt())
    private var installBtnEnabled by mutableStateOf(true)
    private var setExeBtnVisible by mutableStateOf(false)
    private var uninstallBtnVisible by mutableStateOf(false)
    private var sizeText by mutableStateOf("Fetching\u2026")

    // Updates section state
    private var updateStatusText by mutableStateOf("")
    private var checkUpdatesEnabled by mutableStateOf(true)
    private var updateBtnVisible by mutableStateOf(false)

    // DLC state
    private var dlcJson by mutableStateOf("")

    // Cancel download
    private var cancelDownload: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("bh_amazon_prefs", 0)

        val i = intent
        productId = i.getStringExtra("product_id")
        entitlementId = i.getStringExtra("entitlement_id")
        titleText = i.getStringExtra("title")
        developer = i.getStringExtra("developer")
        publisher = i.getStringExtra("publisher")
        artUrl = i.getStringExtra("art_url")
        productSku = i.getStringExtra("product_sku")

        if (productId == null) {
            finish()
            return
        }

        refreshActionState()
        loadDlcData()
        loadInstallSize()
        loadUpdateStatus()

        setContent {
            WinlatorTheme {
                AmazonGameDetailScreen(
                    artUrl = artUrl,
                    titleText = titleText,
                    developer = developer,
                    publisher = publisher,
                    productId = productId,
                    exeNameText = exeNameText,
                    exeNameVisible = exeNameVisible,
                    progressVisible = progressVisible,
                    progressValue = progressValue,
                    progressLabel = progressLabel,
                    progressLabelVisible = progressLabelVisible,
                    launchBtnVisible = launchBtnVisible,
                    launchBtnEnabled = launchBtnEnabled,
                    installBtnVisible = installBtnVisible,
                    installBtnText = installBtnText,
                    installBtnColor = installBtnColor,
                    installBtnEnabled = installBtnEnabled,
                    setExeBtnVisible = setExeBtnVisible,
                    uninstallBtnVisible = uninstallBtnVisible,
                    sizeText = sizeText,
                    updateStatusText = updateStatusText,
                    checkUpdatesEnabled = checkUpdatesEnabled,
                    updateBtnVisible = updateBtnVisible,
                    dlcJson = dlcJson,
                    onBack = { finish() },
                    onInstallClick = { onInstallClicked() },
                    onSetExeClick = { onSetExeClicked() },
                    onUninstallClick = { onUninstallClicked() },
                    onLaunchClick = { onLaunchClicked() },
                    onCheckUpdatesClick = { onCheckUpdatesClick() },
                    onUpdateNowClick = { onInstallClicked() },
                    onDlcInstall = { eid, pid, dlcTitle -> startDlcInstall(eid, pid, dlcTitle) },
                )
            }
        }
    }

    override fun onBackPressed() {
        cancelDownload?.invoke()
        super.onBackPressed()
    }

    // ── State refresh ─────────────────────────────────────────────────────

    private fun refreshActionState() {
        val exe = prefs!!.getString("amazon_exe_$productId", null)
        val dir = prefs!!.getString("amazon_dir_$productId", null)
        val installed = exe != null

        exeNameVisible = installed
        exeNameText = if (installed) ".exe: ${File(exe).name}" else ""

        launchBtnVisible = installed
        launchBtnEnabled = installed
        installBtnVisible = !installed
        if (!installed) installBtnText = "Install"
        setExeBtnVisible = installed
        uninstallBtnVisible = dir != null
    }

    // ── Install ───────────────────────────────────────────────────────────

    private fun onInstallClicked() {
        if (installBtnText == "Cancel") {
            cancelDownload?.invoke()
            cancelDownload = null
            return
        }

        installBtnText = "Cancel"
        installBtnColor = 0xFFCC3333.toInt()
        progressVisible = true
        progressLabelVisible = true
        launchBtnEnabled = false
        setExeBtnVisible = false

        val cancelled = AtomicBoolean(false)
        cancelDownload = { cancelled.set(true) }

        val game = AmazonGame().apply {
            productId = this@AmazonGameDetailActivity.productId ?: ""
            entitlementId = this@AmazonGameDetailActivity.entitlementId ?: ""
            title = this@AmazonGameDetailActivity.titleText ?: ""
            productSku = this@AmazonGameDetailActivity.productSku ?: ""
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val token = AmazonCredentialStore.getValidAccessToken(this@AmazonGameDetailActivity)
            if (token == null) {
                withContext(Dispatchers.Main) {
                    onInstallError("Login required")
                }
                return@launch
            }

            var sanitized = (titleText ?: "").replace("[^a-zA-Z0-9 \\-_]".toRegex(), "").trim()
            if (sanitized.isEmpty()) sanitized = "game_${productId.hashCode()}"
            val installDir = File(File(filesDir, "Amazon"), sanitized)
            prefs!!.edit().putString(
                "amazon_dir_$productId",
                installDir.absolutePath,
            ).apply()

            val ok = AmazonDownloadManager.install(
                this@AmazonGameDetailActivity, game, token, installDir,
                { dl, total, file ->
                    if (cancelled.get()) return@install
                    val pct = if (total > 0) (dl * 100L / total).toInt() else 0
                    val name = if (!file.isNullOrEmpty()) file else "Downloading\u2026"
                    runOnUiThread {
                        progressValue = pct
                        progressLabel = name
                    }
                },
                { cancelled.get() },
            )

            if (cancelled.get()) {
                withContext(Dispatchers.Main) { onInstallCancelled() }
                return@launch
            }
            if (!ok) {
                withContext(Dispatchers.Main) { onInstallError("Download failed") }
                return@launch
            }

            val exeFiles = mutableListOf<File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)
            if (exeFiles.isEmpty()) {
                withContext(Dispatchers.Main) { onInstallError("No executable found") }
                return@launch
            }

            val lowerTitle = (titleText ?: "").lowercase()
            exeFiles.sortWith { a, b ->
                AmazonLaunchHelper.scoreExe(b, lowerTitle) -
                    AmazonLaunchHelper.scoreExe(a, lowerTitle)
            }

            if (exeFiles.size == 1) {
                prefs!!.edit().putString(
                    "amazon_exe_$productId",
                    exeFiles[0].absolutePath,
                ).apply()
                withContext(Dispatchers.Main) { onInstallComplete() }
            } else {
                val candidates = exeFiles.map { it.absolutePath }
                withContext(Dispatchers.Main) {
                    showExePicker(candidates) { selected ->
                        val chosen = if (!selected.isNullOrEmpty()) selected
                        else exeFiles[0].absolutePath
                        prefs!!.edit().putString("amazon_exe_$productId", chosen).apply()
                        onInstallComplete()
                    }
                }
            }
        }
    }

    private fun onInstallComplete() {
        cancelDownload = null
        progressVisible = false
        progressLabelVisible = false
        setResult(RESULT_REFRESH)
        refreshActionState()
    }

    private fun onInstallError(msg: String) {
        cancelDownload = null
        progressVisible = false
        progressLabelVisible = false
        installBtnText = "Install"
        installBtnColor = 0xFFFF9900.toInt()
        launchBtnEnabled = true
        setExeBtnVisible = true
        Toast.makeText(this, "Error: $msg", Toast.LENGTH_LONG).show()
    }

    private fun onInstallCancelled() {
        cancelDownload = null
        progressVisible = false
        progressLabelVisible = false
        installBtnText = "Install"
        installBtnColor = 0xFFFF9900.toInt()
        launchBtnEnabled = true
        setExeBtnVisible = true
    }

    // ── Set .exe ──────────────────────────────────────────────────────────

    private fun onSetExeClicked() {
        val dir = prefs!!.getString("amazon_dir_$productId", null) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val exeFiles = mutableListOf<File>()
            AmazonLaunchHelper.collectExe(File(dir), exeFiles)
            if (exeFiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AmazonGameDetailActivity,
                        "No .exe files found",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            val candidates = exeFiles.map { it.absolutePath }
            withContext(Dispatchers.Main) {
                showExePicker(candidates) { selected ->
                    if (!selected.isNullOrEmpty()) {
                        prefs!!.edit()
                            .putString("amazon_exe_$productId", selected)
                            .apply()
                        setResult(RESULT_REFRESH)
                        refreshActionState()
                        Toast.makeText(
                            this@AmazonGameDetailActivity,
                            "Exe set: ${File(selected).name}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    // ── Uninstall ─────────────────────────────────────────────────────────

    private fun onUninstallClicked() {
        val dir = prefs!!.getString("amazon_dir_$productId", null) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            deleteDir(File(dir))
            prefs!!.edit()
                .remove("amazon_exe_$productId")
                .remove("amazon_dir_$productId")
                .apply()
            withContext(Dispatchers.Main) {
                setResult(RESULT_REFRESH)
                refreshActionState()
                Toast.makeText(
                    this@AmazonGameDetailActivity,
                    "$titleText uninstalled",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // ── Launch ────────────────────────────────────────────────────────────

    private fun onLaunchClicked() {
        val exe = prefs!!.getString("amazon_exe_$productId", null)
        if (exe != null) pendingLaunchExe(exe)
    }

    private fun pendingLaunchExe(absPath: String) {
        prefs!!.edit().putString("pending_amazon_exe", absPath).apply()
        val intent = Intent().apply {
            setClassName(
                packageName,
                "com.xj.landscape.launcher.ui.main.LandscapeLauncherMainActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    // ── Updates ───────────────────────────────────────────────────────────

    private fun loadUpdateStatus() {
        val storedVer = prefs!!.getString("amazon_manifest_version_$productId", null)
        val installed = prefs!!.getString("amazon_exe_$productId", null) != null
        updateStatusText = if (storedVer != null) {
            "Installed: v${storedVer.take(12)}\u2026"
        } else if (installed) {
            "Version not recorded \u2014 tap Check to verify"
        } else {
            "Install the game first to check for updates."
        }
    }

    private fun onCheckUpdatesClick() {
        updateStatusText = "Checking\u2026"
        checkUpdatesEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = AmazonCredentialStore.getValidAccessToken(this@AmazonGameDetailActivity)
                if (token == null) {
                    withContext(Dispatchers.Main) {
                        checkUpdatesEnabled = true
                        updateStatusText = "Login required."
                    }
                    return@launch
                }
                val spec = AmazonApiClient.getGameDownload(token, entitlementId ?: "")
                val latestVer = spec?.versionId
                withContext(Dispatchers.Main) {
                    checkUpdatesEnabled = true
                    if (latestVer.isNullOrEmpty()) {
                        updateStatusText = "Could not reach update server."
                        return@withContext
                    }
                    val stored = prefs!!.getString(
                        "amazon_manifest_version_$productId", null,
                    )
                    if (stored == null) {
                        prefs!!.edit()
                            .putString("amazon_manifest_version_$productId", latestVer)
                            .apply()
                        updateStatusText = "Up to date \u2713"
                        updateBtnVisible = false
                    } else if (stored == latestVer) {
                        updateStatusText = "Up to date \u2713"
                        updateBtnVisible = false
                    } else {
                        updateStatusText = "Update available!\n" +
                            "Installed: v${stored.take(12)}\u2026 " +
                            " \u2192  Latest: v${latestVer.take(12)}\u2026"
                        updateBtnVisible = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    checkUpdatesEnabled = true
                    updateStatusText = "Check failed: ${e.message}"
                }
            }
        }
    }

    // ── DLC ───────────────────────────────────────────────────────────────

    private fun loadDlcData() {
        dlcJson = productId?.let {
            prefs!!.getString("amazon_dlcs_$it", null)
        } ?: ""
    }

    private fun startDlcInstall(dlcEid: String, dlcPid: String, dlcTitle: String) {
        val dlcGame = AmazonGame().apply {
            entitlementId = dlcEid
            productId = if (dlcPid.isEmpty()) dlcEid else dlcPid
            title = dlcTitle
        }
        val pidKey = dlcGame.productId

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = AmazonCredentialStore.getValidAccessToken(
                    this@AmazonGameDetailActivity,
                )
                if (token == null) {
                    withContext(Dispatchers.Main) {
                        setResult(RESULT_REFRESH)
                        refreshActionState()
                    }
                    return@launch
                }
                var sanitized = dlcTitle.replace("[^a-zA-Z0-9 \\-_]".toRegex(), "").trim()
                if (sanitized.isEmpty()) sanitized = "dlc_${dlcEid.hashCode()}"
                val installDir = File(File(filesDir, "Amazon"), sanitized)
                prefs!!.edit()
                    .putString("amazon_dir_$pidKey", installDir.absolutePath)
                    .apply()

                val ok = AmazonDownloadManager.install(
                    this@AmazonGameDetailActivity, dlcGame, token, installDir,
                    { _, _, _ -> },
                    { false },
                )

                if (!ok) {
                    withContext(Dispatchers.Main) {
                        setResult(RESULT_REFRESH)
                        refreshActionState()
                    }
                    return@launch
                }

                val exeFiles = mutableListOf<File>()
                AmazonLaunchHelper.collectExe(installDir, exeFiles)
                if (exeFiles.isNotEmpty()) {
                    val lowerT = dlcTitle.lowercase()
                    exeFiles.sortWith { a, b ->
                        AmazonLaunchHelper.scoreExe(b, lowerT) -
                            AmazonLaunchHelper.scoreExe(a, lowerT)
                    }
                    prefs!!.edit()
                        .putString("amazon_exe_$pidKey", exeFiles[0].absolutePath)
                        .apply()
                }

                withContext(Dispatchers.Main) {
                    setResult(RESULT_REFRESH)
                    loadDlcData()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setResult(RESULT_REFRESH)
                    loadDlcData()
                }
            }
        }
    }

    // ── Size ──────────────────────────────────────────────────────────────

    private fun loadInstallSize() {
        val cached = prefs!!.getLong("amazon_size_$productId", -1L)
        if (cached > 0) {
            sizeText = formatBytes(cached)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val token = AmazonCredentialStore.getValidAccessToken(
                this@AmazonGameDetailActivity,
            )
            val size = if (token != null && !entitlementId.isNullOrEmpty()) {
                AmazonDownloadManager.fetchInstallSizeBytes(token, entitlementId!!)
            } else -1L
            if (size > 0) {
                prefs!!.edit().putLong("amazon_size_$productId", size).apply()
            }
            val finalSize = size
            withContext(Dispatchers.Main) {
                sizeText = if (finalSize > 0) formatBytes(finalSize) else "Unknown"
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun showExePicker(
        candidates: List<String>,
        onSelected: (String) -> Unit,
    ) {
        val labels = candidates.map { path ->
            val f = File(path)
            val parent = f.parentFile
            if (parent != null) "${parent.name}/${f.name}" else f.name
        }.toTypedArray()

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Select game executable")
            .setItems(labels) { _, which ->
                lifecycleScope.launch(Dispatchers.IO) {
                    onSelected(candidates[which])
                }
            }
            .setCancelable(false)
            .show()
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

// ─── Composable Screen ─────────────────────────────────────────────────────

@Composable
private fun AmazonGameDetailScreen(
    artUrl: String?,
    titleText: String?,
    developer: String?,
    publisher: String?,
    productId: String?,
    exeNameText: String,
    exeNameVisible: Boolean,
    progressVisible: Boolean,
    progressValue: Int,
    progressLabel: String,
    progressLabelVisible: Boolean,
    launchBtnVisible: Boolean,
    launchBtnEnabled: Boolean,
    installBtnVisible: Boolean,
    installBtnText: String,
    installBtnColor: Int,
    installBtnEnabled: Boolean,
    setExeBtnVisible: Boolean,
    uninstallBtnVisible: Boolean,
    sizeText: String,
    updateStatusText: String,
    checkUpdatesEnabled: Boolean,
    updateBtnVisible: Boolean,
    dlcJson: String,
    onBack: () -> Unit,
    onInstallClick: () -> Unit,
    onSetExeClick: () -> Unit,
    onUninstallClick: () -> Unit,
    onLaunchClick: () -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onUpdateNowClick: () -> Unit,
    onDlcInstall: (eid: String, pid: String, title: String) -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1000))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2000)),
                modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) { Text("\u2190", color = Color.White) }

            Text(
                text = titleText ?: "",
                fontSize = 15.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            // Cover art
            if (!artUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(artUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color(0xFF1A1000)),
                    contentScale = ContentScale.Crop,
                )
            }

            // Game Info section
            SectionHeader("GAME INFO")
            InfoCard(
                developer = developer,
                publisher = publisher,
                productId = productId,
                sizeText = sizeText,
            )
            Spacer(Modifier.height(8.dp))

            // Actions section
            SectionHeader("ACTIONS")
            ActionsCard(
                exeNameText = exeNameText,
                exeNameVisible = exeNameVisible,
                progressVisible = progressVisible,
                progressValue = progressValue,
                progressLabel = progressLabel,
                progressLabelVisible = progressLabelVisible,
                launchBtnVisible = launchBtnVisible,
                launchBtnEnabled = launchBtnEnabled,
                installBtnVisible = installBtnVisible,
                installBtnText = installBtnText,
                installBtnColor = installBtnColor,
                installBtnEnabled = installBtnEnabled,
                setExeBtnVisible = setExeBtnVisible,
                uninstallBtnVisible = uninstallBtnVisible,
                onLaunchClick = onLaunchClick,
                onInstallClick = onInstallClick,
                onSetExeClick = onSetExeClick,
                onUninstallClick = onUninstallClick,
            )
            Spacer(Modifier.height(8.dp))

            // Updates section
            SectionHeader("UPDATES")
            UpdatesCard(
                updateStatusText = updateStatusText,
                checkUpdatesEnabled = checkUpdatesEnabled,
                updateBtnVisible = updateBtnVisible,
                onCheckUpdatesClick = onCheckUpdatesClick,
                onUpdateNowClick = onUpdateNowClick,
            )
            Spacer(Modifier.height(8.dp))

            // DLC section
            SectionHeader("DLC")
            DlcCard(
                dlcJson = dlcJson,
                onDlcInstall = onDlcInstall,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─── Sub-components ────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = Color(0xFFAA8844),
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.08.sp,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun InfoCard(
    developer: String?,
    publisher: String?,
    productId: String?,
    sizeText: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color(0xFF1A1200),
                RoundedCornerShape(8.dp),
            )
            .padding(14.dp, 12.dp, 14.dp, 12.dp),
    ) {
        if (!developer.isNullOrEmpty()) {
            InfoRow("Developer", developer)
        }
        if (!publisher.isNullOrEmpty()) {
            InfoRow("Publisher", publisher)
        }
        if (productId != null) {
            val dot = productId.lastIndexOf('.')
            val shortId = if (dot >= 0 && dot < productId.length - 1)
                productId.substring(dot + 1) else productId
            InfoRow("ID", shortId)
        }
        InfoRow("Install size", sizeText)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Text(
            text = "$label: ",
            fontSize = 13.sp,
            color = Color(0xFF888888),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = Color(0xFFCCCCCC),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionsCard(
    exeNameText: String,
    exeNameVisible: Boolean,
    progressVisible: Boolean,
    progressValue: Int,
    progressLabel: String,
    progressLabelVisible: Boolean,
    launchBtnVisible: Boolean,
    launchBtnEnabled: Boolean,
    installBtnVisible: Boolean,
    installBtnText: String,
    installBtnColor: Int,
    installBtnEnabled: Boolean,
    setExeBtnVisible: Boolean,
    uninstallBtnVisible: Boolean,
    onLaunchClick: () -> Unit,
    onInstallClick: () -> Unit,
    onSetExeClick: () -> Unit,
    onUninstallClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1200), RoundedCornerShape(8.dp))
            .padding(14.dp, 12.dp, 14.dp, 12.dp),
    ) {
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
                modifier = Modifier.fillMaxWidth().height(4.dp).padding(bottom = 4.dp),
                color = Color(0xFFFF9900),
                trackColor = Color(0xFF333333),
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

        if (launchBtnVisible) {
            ActionButton(
                text = "Launch",
                color = 0xFF2E7D32.toInt(),
                enabled = launchBtnEnabled,
                onClick = onLaunchClick,
            )
        }

        if (installBtnVisible) {
            ActionButton(
                text = installBtnText,
                color = installBtnColor,
                enabled = installBtnEnabled,
                onClick = onInstallClick,
            )
        }

        if (setExeBtnVisible) {
            ActionButton(
                text = "Set .exe\u2026",
                color = 0xFF444444.toInt(),
                onClick = onSetExeClick,
            )
        }

        if (uninstallBtnVisible) {
            ActionButton(
                text = "Uninstall",
                color = 0xFF8B0000.toInt(),
                onClick = onUninstallClick,
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    color: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Color(color)),
        modifier = Modifier.fillMaxWidth().height(42.dp).padding(bottom = 8.dp),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun UpdatesCard(
    updateStatusText: String,
    checkUpdatesEnabled: Boolean,
    updateBtnVisible: Boolean,
    onCheckUpdatesClick: () -> Unit,
    onUpdateNowClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1200), RoundedCornerShape(8.dp))
            .padding(14.dp, 12.dp, 14.dp, 12.dp),
    ) {
        if (updateStatusText.startsWith("Install the game")) {
            Text(
                text = updateStatusText,
                fontSize = 13.sp,
                color = Color(0xFF554400),
            )
            return
        }

        Text(
            text = updateStatusText,
            fontSize = 13.sp,
            color = Color(0xFFCCCCCC),
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (updateBtnVisible) {
            ActionButton(
                text = "Update Now",
                color = 0xFFCC7700.toInt(),
                onClick = onUpdateNowClick,
            )
        }

        ActionButton(
            text = "Check for Updates",
            color = 0xFF332200.toInt(),
            enabled = checkUpdatesEnabled,
            onClick = onCheckUpdatesClick,
        )
    }
}

@Composable
private fun DlcCard(
    dlcJson: String,
    onDlcInstall: (eid: String, pid: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bh_amazon_prefs", 0) }

    val dlcArr = remember(dlcJson) {
        if (dlcJson.isNullOrEmpty() || dlcJson == "[]") null
        else runCatching { org.json.JSONArray(dlcJson) }.getOrNull()
    }
    val parseError = remember(dlcJson) {
        !dlcJson.isNullOrEmpty() && dlcJson != "[]" &&
            runCatching { org.json.JSONArray(dlcJson); false }.getOrDefault(true)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1200), RoundedCornerShape(8.dp))
            .padding(14.dp, 12.dp, 14.dp, 12.dp),
    ) {
        if (parseError) {
            Text(
                text = "Error reading DLC data",
                fontSize = 13.sp,
                color = Color(0xFF554400),
            )
            return
        }

        if (dlcArr == null || dlcArr.length() == 0) {
            Text(
                text = "No DLCs in your library for this game",
                fontSize = 13.sp,
                color = Color(0xFF554400),
            )
            return
        }

        Text(
            text = "${dlcArr.length()} DLC${if (dlcArr.length() == 1) "" else "s"} owned",
            fontSize = 12.sp,
            color = Color(0xFF888888),
            fontWeight = FontWeight.Bold,
        )

        for (i in 0 until dlcArr.length()) {
            val dlc = dlcArr.optJSONObject(i) ?: continue
            val dlcEid = dlc.optString("eid", "")
            val dlcPid = dlc.optString("pid", "")
            val dlcTitleText = dlc.optString("title", "Unknown DLC")

            val dlcInstalled = dlcPid.isNotEmpty() &&
                prefs.getString("amazon_exe_$dlcPid", null) != null

            var dlcStatusText by remember { mutableStateOf("") }
            var dlcBtnText by remember {
                mutableStateOf(if (dlcInstalled) "Reinstall" else "Install")
            }
            var dlcBtnColor by remember {
                mutableIntStateOf(if (dlcInstalled) 0xFF2A3A00.toInt() else 0xFFCC7700.toInt())
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .background(Color(0xFF1A1200), RoundedCornerShape(4.dp))
                    .padding(8.dp, 6.dp, 8.dp, 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dlcTitleText,
                        fontSize = 13.sp,
                        color = Color(0xFFDDDDDD),
                        modifier = Modifier.weight(1f),
                    )
                    if (dlcInstalled) {
                        Text(
                            text = "\u2713",
                            fontSize = 13.sp,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (dlcStatusText.isNotEmpty()) {
                    Text(
                        text = dlcStatusText,
                        fontSize = 11.sp,
                        color = Color(0xFF886600),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }

                if (dlcEid.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            if (dlcBtnText == "Downloading\u2026") return@Button
                            dlcBtnText = "Downloading\u2026"
                            dlcBtnColor = 0xFF444444.toInt()
                            dlcStatusText = "Starting\u2026"
                            onDlcInstall(dlcEid, dlcPid, dlcTitleText)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(dlcBtnColor),
                        ),
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = dlcBtnText,
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
        }
    }
}
