package com.winlator.star.store.xbox

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.winlator.star.R
import com.winlator.star.core.AppOrientation
import com.winlator.star.store.CatalogArt
import com.winlator.star.store.StarLaunchBridge
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.InfoChip
import com.winlator.star.store.download.Store
import com.winlator.star.store.download.StoreBadge
import com.winlator.star.store.download.StoreDetailScaffold
import com.winlator.star.store.download.StoreDetailState
import com.winlator.star.store.download.StoreGearItem
import com.winlator.star.store.download.StorePrimaryAction
import com.winlator.star.store.download.StoreSection
import com.winlator.star.store.download.StoreStatusText
import com.winlator.star.store.download.formatDownloadSize
import com.winlator.star.store.download.formatDownloadSpeed
import com.winlator.star.store.download.formatEta
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One Xbox PC game: Install (with a package choice when the offer has several PC packages), progress from the
 * shared Download Manager, Add to Games (a shortcut in a container of the user's choice, tagged xbox),
 * Update / verify and Uninstall. Playing goes through the shortcut: each launch prepares a licensed launch
 * view (XboxRuntime) inside the Wine session.
 */
class XboxGameDetailActivity : ComponentActivity() {
    private lateinit var game: XboxGame
    private var installed by mutableStateOf<XboxInstallState.Installed?>(null)
    private var downloading by mutableStateOf(false)
    private var progressPct by mutableStateOf(0)
    private var progressLabel by mutableStateOf("")
    private var busy by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)
    private var addToShortcuts by mutableStateOf<com.winlator.star.store.compose.AddToShortcutsRequest?>(null)
    private var addResult by mutableStateOf<com.winlator.star.store.compose.AddShortcutResult?>(null)

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppOrientation.apply(this)
        DownloadRegistry.init(this)
        val i = intent
        game = XboxGame(i.getStringExtra("id").orEmpty(), i.getStringExtra("title").orEmpty(), i.getStringExtra("hero"),
            i.getStringExtra("poster"), i.getStringExtra("publisher").orEmpty(), i.getStringExtra("developer").orEmpty(),
            i.getStringExtra("description").orEmpty(), i.getLongExtra("size", 0L), i.getStringExtra("market") ?: Xodus.market(),
            i.getStringExtra("access").orEmpty())
        refresh()
        observeRegistry()
        setContent {
            WinlatorTheme {
                var tab by androidx.compose.runtime.remember { mutableStateOf(0) }
                val inst = installed
                val primary = when {
                    downloading -> StorePrimaryAction.Progress(progressLabel.ifBlank { "Downloading… $progressPct%" }, progressPct / 100f)
                    busy != null -> StorePrimaryAction.Progress(busy!!, 0f)
                    inst != null -> StorePrimaryAction.Button("Add to Games", ::addToGames)
                    game.access.isEmpty() -> StorePrimaryAction.Button("Not in your library", {}, enabled = false)
                    else -> StorePrimaryAction.Button("Install", ::install)
                }
                val gear = buildList {
                    if (downloading) add(StoreGearItem("🗑", "Cancel download", danger = true) { XboxDownloads.cancel(game.id) })
                    if (inst != null && !downloading) {
                        add(StoreGearItem("🔄", "Update / verify") { update(inst) })
                        add(StoreGearItem("🗑️", "Uninstall", danger = true, onClick = ::confirmUninstall))
                    }
                }
                addToShortcuts?.let { req ->
                    com.winlator.star.store.compose.ContainerPickerDialog(
                        gameName = req.gameName,
                        containers = req.containers,
                        onDismiss = { addToShortcuts = null },
                        // Proton older than 11 lacks Windows' PropertySet; Xbox sign-in then uses a compatibility module.
                        note = { c -> Regex("""Proton-(\d+)""").find(c.wineVersion.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                            ?.takeIf { it < 11 }?.let { "Older Proton: Xbox support uses compatibility modules here. Proton 11 recommended." } },
                        onSelected = { container ->
                            addToShortcuts = null
                            StarLaunchBridge.writeShortcutAsync(this@XboxGameDetailActivity, container, req.gameName, req.exePath,
                                req.coverUrl, 0, null, "xbox:${game.id}", false) { success, message ->
                                addResult = com.winlator.star.store.compose.AddShortcutResult(req.gameName, success, message)
                            }
                        },
                    )
                }
                addResult?.let { result ->
                    com.winlator.star.store.compose.AddResultDialog(
                        result = result,
                        onOpenShortcuts = {
                            addResult = null
                            com.winlator.star.store.compose.openShortcutsScreen(this@XboxGameDetailActivity)
                        },
                        onDismiss = { addResult = null },
                    )
                }
                StoreDetailScaffold(
                    onBack = { finish() },
                    title = game.title,
                    storeBadge = { StoreBadge(Store.XBOX) },
                    hero = {
                        val art = listOfNotNull(game.heroUrl, game.posterUrl)
                        if (art.isNotEmpty()) CatalogArt(candidates = art, title = game.title, modifier = Modifier.fillMaxSize(), aspectRatio = null)
                        else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                    },
                    subtitle = {
                        Spacer(Modifier.height(4.dp))
                        when {
                            inst != null -> StoreStatusText("Installed · ${inst.exe.substringAfterLast('/')}${if (inst.version.isNotBlank()) " · v${inst.version}" else ""}", StoreDetailState.INSTALLED)
                            game.gamePass -> StoreStatusText("Included with PC Game Pass")
                            game.owned -> StoreStatusText("Owned")
                            else -> StoreStatusText("Not owned by this account")
                        }
                        status?.let { Spacer(Modifier.height(2.dp)); StoreStatusText(it, StoreDetailState.FAILED) }
                    },
                    primary = primary,
                    gear = gear,
                    tabs = listOf("Details"),
                    selectedTab = tab,
                    onSelectTab = { tab = it },
                ) {
                    Column(Modifier.padding(bottom = 16.dp)) {
                        FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (game.sizeBytes > 0) InfoChip("Up to ${formatDownloadSize(game.sizeBytes)}")
                            if (game.developer.isNotBlank()) InfoChip(game.developer)
                            if (game.publisher.isNotBlank() && game.publisher != game.developer) InfoChip(game.publisher)
                            InfoChip("ID: ${game.id}")
                        }
                        if (game.description.isNotBlank()) StoreSection(title = "About") {
                            Text(game.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StoreSection(title = "How it runs") {
                            Text("The PC version is downloaded with your license and runs in a Wine container you pick when " +
                                "adding it to Games. Each launch checks the license with Microsoft, so play online the first time. " +
                                "Cloud saves are ${if (XboxMainActivity.cloudSaves(this@XboxGameDetailActivity)) "on" else "off"} (Xbox › Profile).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        lifecycleScope.launch {
            installed = withContext(Dispatchers.IO) { XboxInstallState.get(applicationContext, game.id)?.takeIf { File(it.folder, it.exe).isFile } }
        }
    }

    private fun observeRegistry() {
        val key = "${Store.XBOX}:${game.id}"
        lifecycleScope.launch {
            DownloadRegistry.entries.collect { list ->
                val e = list.firstOrNull { it.key == key }
                if (e != null && (e.state == DownloadState.DOWNLOADING || e.state == DownloadState.PAUSED || e.state == DownloadState.QUEUED)) {
                    downloading = true
                    progressPct = e.pct
                    progressLabel = listOf(
                        if (e.installTotal > 0) "${e.pct}%  ·  ${formatDownloadSize((e.installTotal - e.installDone).coerceAtLeast(0))} to go" else "Downloading… ${e.pct}%",
                        formatDownloadSpeed(e.speedBps), formatEta(e.etaSeconds),
                    ).filter { it.isNotEmpty() }.joinToString("  ·  ")
                } else {
                    if (downloading) refresh()
                    downloading = false
                    if (e?.state == DownloadState.FAILED) status = e.error
                }
            }
        }
    }

    private fun install() {
        status = null
        busy = "Checking the PC package…"
        lifecycleScope.launch {
            val choices = withContext(Dispatchers.IO) { runCatching { XboxDownloads.resolve(applicationContext, game) } }
            busy = null
            choices.onFailure { status = it.message ?: "The Xbox package could not be resolved." }.onSuccess { list ->
                if (list.size == 1) XboxDownloads.start(applicationContext, game, list.single().contentId)
                else AlertDialog.Builder(this@XboxGameDetailActivity, R.style.StoreAlertDialogDark)
                    .setTitle("Choose the PC package")
                    .setItems(list.map { it.label }.toTypedArray()) { _, which -> XboxDownloads.start(applicationContext, game, list[which].contentId) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun update(inst: XboxInstallState.Installed) {
        status = null
        if (inst.contentId.isBlank()) { install(); return }
        XboxDownloads.start(applicationContext, game.copy(market = inst.market, access = inst.access.ifBlank { game.access }), inst.contentId)
    }

    private fun addToGames() {
        val inst = installed ?: return
        // The shared Compose flow (as on the Steam pages): container picker, then "Added to Shortcuts" with Open Shortcuts.
        val exe = File(inst.folder, inst.exe).path
        StarLaunchBridge.loadContainers(this) { containers ->
            addToShortcuts = com.winlator.star.store.compose.AddToShortcutsRequest(game.title, exe, game.posterUrl ?: game.heroUrl, containers)
        }
    }

    private fun confirmUninstall() {
        AlertDialog.Builder(this, R.style.StoreAlertDialogDark)
            .setTitle("Uninstall ${game.title}?")
            .setMessage("The game's files are deleted. Shortcuts that point to it stop working until you install it again.")
            .setPositiveButton("Uninstall") { _, _ ->
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { XboxDownloads.uninstall(applicationContext, game.id) } }
                    r.onFailure { status = it.message }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
